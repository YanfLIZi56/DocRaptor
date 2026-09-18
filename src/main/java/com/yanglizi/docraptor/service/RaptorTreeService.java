package com.yanglizi.docraptor.service;

import com.yanglizi.docraptor.ai.AiGateway;
import com.yanglizi.docraptor.algorithm.ClusterPipeline;
import com.yanglizi.docraptor.algorithm.RaptorClusterer;
import com.yanglizi.docraptor.algorithm.SummaryPrompts;
import com.yanglizi.docraptor.algorithm.TokenEstimator;
import com.yanglizi.docraptor.algorithm.TreeBuildGuard;
import com.yanglizi.docraptor.algorithm.UmapReducer;
import com.yanglizi.docraptor.algorithm.VectorUtils;
import com.yanglizi.docraptor.async.AsyncTaskProgressReporter;
import com.yanglizi.docraptor.common.BizException;
import com.yanglizi.docraptor.common.ErrorCode;
import com.yanglizi.docraptor.common.JsonUtils;
import com.yanglizi.docraptor.config.DocRaptorProperties;
import com.yanglizi.docraptor.domain.entity.SummaryNode;
import com.yanglizi.docraptor.mapper.SummaryNodeMapper;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * 模块2 核心：RAPTOR 递归建树。
 *
 * <p>流程（docs/01-architecture.md 5.1）：叶子块向量 → UMAP 降维 → GMM 聚类（簇数在 [kMin,kMax] 内按 BIC 选）
 * → 逐簇并发 LLM 摘要 → 父节点向量化 → 递归向上 → 到深度上限（默认 L3）或只剩一个节点时停止。
 *
 * <p><b>并发模型</b>：同层各簇的 LLM 调用互相独立，用固定线程池并行（并发度 = {@code docraptor.raptor.summary-concurrency}）；
 * 但<b>所有数据库写操作回到调用线程串行执行</b>，避免把 MyBatis SqlSession 跨线程使用。
 *
 * <p><b>三层兜底</b>，保证递归一定收敛、且不会让整棵树构建失败：
 * <ol>
 *   <li>节点数 &lt; 3 或聚类不可行（簇数 ≤ 1）→ 直接把全部节点合并成一个父节点；</li>
 *   <li>UMAP 抛异常 → 退回原始向量空间聚类（{@link UmapReducer} 内部 catch）；</li>
 *   <li>某簇 LLM 摘要连续失败 → 该簇降级为「成员原文前 N 字符拼接」（{@code metadata.degraded=true}）。</li>
 * </ol>
 */
@Slf4j
@Service
public class RaptorTreeService {

    private final SummaryNodeMapper nodeMapper;
    private final AiGateway aiGateway;
    private final SummaryService summaryService;
    private final DocRaptorProperties props;

    public RaptorTreeService(SummaryNodeMapper nodeMapper, AiGateway aiGateway,
                             SummaryService summaryService, DocRaptorProperties props) {
        this.nodeMapper = nodeMapper;
        this.aiGateway = aiGateway;
        this.summaryService = summaryService;
        this.props = props;
    }

    /** 建树参数（请求级覆盖，未传取配置默认值）。实际使用值会写进 summary_nodes.metadata。 */
    @Data
    public static class BuildParams {
        private int maxLevel = 3;
        private int umapNNeighbors = 10;
        private double umapMinDist = 0.1;
        private int gmmMaxClusters = 50;
        /**
         * 协方差类型。<b>默认 full</b>（官方 sklearn 默认）：对角协方差每个分量只要 {@code 2d} 个参数，
         * 在「样本几十、维度 10」的局部子集上会被「一个分量钉住一个点」的坍缩解骗过去
         * （实测 logL 变正、50 个点切成 50 个单点簇）；全协方差每分量 {@code d + d(d+1)/2} 个参数足以压住。
         *
         * <p>注意：请求体里显式传 {@code gmmCovarianceType=diagonal} 仍会被尊重（契约冻结），
         * 但结果会退化成大量单节点簇，请谨慎。
         */
        private String gmmCovarianceType = "full";
        private String summaryPrompt;

        public static BuildParams defaults(DocRaptorProperties p) {
            BuildParams b = new BuildParams();
            b.maxLevel = p.getRaptor().getMaxLevel();
            b.umapNNeighbors = p.getRaptor().getUmap().getNNeighbors();
            b.umapMinDist = p.getRaptor().getUmap().getMinDist();
            b.gmmMaxClusters = p.getRaptor().getGmm().getMaxClusters();
            b.gmmCovarianceType = p.getRaptor().getGmm().getCovarianceType();
            return b;
        }
    }

    /** 建树结果（写入 async_task.result 与 summary_nodes.metadata）。 */
    @Data
    public static class BuildResult {
        private String documentId;
        private String rootNodeId;
        private int maxLevel;
        private int summaryNodeCount;
        private int actualDepth;
        private boolean hasUniqueRoot;
        private int degradedSummaryCount;
        private boolean forcedRoot;
        private long durationMs;
    }

    /**
     * {@link TreeBuildGuard} 的最小簇规模阈值：固定 2，<b>刻意不跟随</b>
     * {@code docraptor.raptor.min-cluster-size}（后者只管簇数上限）。见调用点注释。
     */
    private static final int TREE_GUARD_MIN_CLUSTER_SIZE = 2;

    /** 落库前「小簇并入最相似大簇」的阈值：固定 2，同样不跟随簇数上限配置。 */
    private static final int MERGE_TINY_CLUSTER_SIZE = 2;

    /** 构建中的节点引用（内存态，避免反复查库）。 */
    private static class NodeRef {
        UUID id;
        double[] vector;
        int startChunk;
        int endChunk;
        int chunkIndex;
        String text;
        Map<String, Object> metadata;
    }

    /** 一簇的摘要产物（LLM + embedding 已在并发阶段完成，尚未落库）。 */
    private static class PreparedSummary {
        String summary;
        float[] vector;
        boolean degraded;
        int start;
        int end;
        int clusterSize;
        Map<String, Object> metadata;
    }

    /**
     * 建树。调用方（worker）负责任务终态、文档 tree_status 与 knowledge_base 统计。
     *
     * @param taskId   任务 ID，用于写进度；可为 null
     * @param kbId     所属知识库
     * @param docId    文档 ID
     * @param reporter 进度写入器；可为 null
     */
    public BuildResult build(UUID taskId, UUID kbId, UUID docId, BuildParams params,
                             AsyncTaskProgressReporter reporter) {
        long started = System.currentTimeMillis();
        String buildId = UUID.randomUUID().toString();

        // ---------- 0. 幂等复位：先断开「叶子 → 摘要」的父子边，再删旧 SUMMARY ----------
        // ⚠️ 顺序绝对不能反（实测踩到，forceRebuild 会 100% 失败）：
        //   summary_nodes.parent_id 是 ON DELETE CASCADE 的**自引用外键**，
        //   直接 DELETE ... WHERE node_type='SUMMARY' 会级联把它下面的子节点一起删掉 —— 包括 LEAF！
        //   而 LEAF 一旦被 eval_case_expected_chunk 以 ON DELETE RESTRICT 引用，
        //   整个删除就会报 fk_eval_exp_node RESTRICT 违规（报错信息里那个 key 其实是一个 LEAF 节点，
        //   第一次看会误以为是「删摘要」出了问题）。
        //   先把叶子的 parent_id 置空，级联就只可能在 SUMMARY 之间发生（而它们本来就要被删），这才是安全的。
        nodeMapper.resetLeafParents(docId);
        nodeMapper.deleteSummariesByDocument(docId);
        if (reporter != null) {
            reporter.progress(taskId, 70, "TREE_BUILD", "已清理旧摘要节点，开始聚类");
        }

        BuildResult result = new BuildResult();
        result.setDocumentId(docId.toString());
        result.setMaxLevel(params.getMaxLevel());

        // ---------- 1. 取当前层节点（叶子） ----------
        List<SummaryNode> leaves = nodeMapper.selectLeavesWithEmbedding(docId);
        if (leaves.size() <= 1) {
            // 单块文档无需建树（架构 5.1 的 IF |current| <= 1: RETURN）
            result.setSummaryNodeCount(0);
            result.setActualDepth(0);
            result.setHasUniqueRoot(false);
            result.setDurationMs(System.currentTimeMillis() - started);
            log.info("文档 {} 叶子块 {} 个，无需建树", docId, leaves.size());
            return result;
        }

        List<NodeRef> current = toRefs(leaves);
        int degraded = 0;
        int summaryCount = 0;
        int level = 1;
        boolean forcedRoot = false;

        while (true) {
            if (current.size() <= 1) {
                break;                                  // 收敛：只剩一个节点，它即根
            }
            if (level > params.getMaxLevel()) {
                forcedRoot = true;                      // 命中深度上限
                break;
            }

            ClusterOutcome co = cluster(current, params);
            List<List<NodeRef>> groups = co.groups();

            // 每层诊断日志（规格 §2-10）：n / reduction / dim / k / kMax / 簇规模分布 / 递归细分 / 软聚类多归属
            log.info("建树第 {} 层聚类：n={} reduction={} dim={} k={} 全局簇={} kMax={} 递归细分={} 多归属={} 规模={}",
                    level, current.size(), co.reduction(), co.dimension(), co.k(),
                    co.globalClusterCount(), current.size() > 0 ? ClusterPipeline
                            .effectiveKMax(current.size(), params.getGmmMaxClusters(),
                                    props.getRaptor().getGmm().getAbsoluteMaxClusters(),
                                    props.getRaptor().getMinClusterSize()) : 0,
                    co.recursionSplits(), co.multiMembershipNodes(), describeSizes(co.clusterSizes()));

            // ---------- 层间收敛判定（TreeBuildGuard）----------
            // 命中任意一条就立刻停止向上递归，并把整层合并成「一个」父节点：
            //   · 这样保证树仍然有唯一根（契约 prefer hasUniqueRoot=true），而不是留下多个顶层节点
            //     让前端去提示用户"未收敛"；
            //   · 同时挡住 BIC 选爆 kMax、全同向量、单节点簇等所有"新层不会更小"的退化情况。
            List<Integer> clusterSizes = new ArrayList<>(groups.size());
            for (List<NodeRef> g : groups) {
                clusterSizes.add(g.size());
            }
            // ⚠️ 这里刻意**不**用 props.raptor.min-cluster-size（它只管簇数上限，默认 8）：
            //    TreeBuildGuard 的阈值只该拦「全是一节点簇」这种退化，用 2 就够。
            //    若把 8 传进来，健康的层（最大簇 < 8）也会被判 NO_REAL_MERGE 而提前收根，**压掉树深**。
            TreeBuildGuard.Stop stop = TreeBuildGuard.reasonToStop(current.size(), clusterSizes,
                    TREE_GUARD_MIN_CLUSTER_SIZE);
            if (stop != null && stop != TreeBuildGuard.Stop.CONVERGED) {
                log.info("建树第 {} 层触发收敛保护（{}：当前层 {} 个节点，簇规模 {}），本层合并为一个父节点后停止",
                        level, stop, current.size(), clusterSizes);
                Map<String, Object> extra = new LinkedHashMap<>();
                extra.put("stopReason", stop.name());
                // degenerateStop 只在真正的「退化」情况下打标；CANNOT_SPLIT 是树顶正常的合并收尾，
                // 不打这个标记，免得排查时误以为出了故障。
                if (stop == TreeBuildGuard.Stop.NOT_SHRINKING || stop == TreeBuildGuard.Stop.NO_REAL_MERGE) {
                    extra.put("degenerateStop", true);
                }
                List<List<NodeRef>> allGroups = List.of(new ArrayList<>(current));
                List<PreparedSummary> prepared = prepareAll(allGroups, level, buildId, params, reporter,
                        extra, co);
                List<NodeRef> next = persistAll(kbId, docId, prepared, allGroups, level);
                summaryCount += next.size();
                degraded += countDegraded(prepared);
                current = next;
                level++;
                break;
            }

            List<PreparedSummary> prepared = prepareAll(groups, level, buildId, params, reporter, co);
            List<NodeRef> next = persistAll(kbId, docId, prepared, groups, level);
            summaryCount += next.size();
            degraded += countDegraded(prepared);
            current = next;
            level++;

            if (reporter != null) {
                int total = Math.max(1, params.getMaxLevel());
                int pct = Math.min(99, (int) Math.round((level - 1) * 100.0 / (total + 1)));
                reporter.progress(taskId, 70 + (int) Math.round(30 * pct / 100.0), "TREE_BUILD",
                        "已完成 level=" + (level - 1) + " 摘要，本层 " + current.size() + " 个节点");
            }
        }

        // ---------- 收尾：深度上限处仍有多节点 → 补一次合并摘要，保证树有唯一根 ----------
        if (forcedRoot && current.size() > 1) {
            // TODO(contract): 架构 5.2 要求「在 level = maxLevel 上补一次合并摘要」，
            //   但此时 children 也已经是 level = maxLevel，会出现「父子同层」（DDL 的 level 约束允许）。
            //   这里按文档字面实现；若需严格递增层级，请 Lead 裁决后调整。
            List<PreparedSummary> prepared = prepareAll(List.of(new ArrayList<>(current)), params.getMaxLevel(),
                    buildId, params, reporter, Map.of("forcedRoot", true), ClusterOutcome.none());
            List<NodeRef> next = persistAll(kbId, docId, prepared, List.of(new ArrayList<>(current)),
                    params.getMaxLevel());
            summaryCount += next.size();
            degraded += countDegraded(prepared);
            current = next;
        }

        UUID rootId = current.size() == 1 ? current.get(0).id : null;
        if (rootId != null) {
            markRoot(rootId, buildId, System.currentTimeMillis() - started);
        }

        int actualDepth = nodeMapper.selectLevelCounts(docId).stream()
                .map(lc -> lc.getLevel() == null ? 0 : (int) lc.getLevel())
                .max(Comparator.naturalOrder()).orElse(0);

        result.setRootNodeId(rootId == null ? null : rootId.toString());
        result.setSummaryNodeCount(summaryCount);
        result.setActualDepth(actualDepth);
        result.setHasUniqueRoot(rootId != null);
        result.setDegradedSummaryCount(degraded);
        result.setForcedRoot(forcedRoot);
        result.setDurationMs(System.currentTimeMillis() - started);
        log.info("文档 {} 建树完成：summary={} depth={} uniqueRoot={} degraded={} forcedRoot={} 耗时 {}ms",
                docId, summaryCount, actualDepth, rootId != null, degraded, forcedRoot, result.getDurationMs());
        return result;
    }

    /* ============================== 聚类 ============================== */

    /**
     * 一层的聚类产物：簇成员分组 + 本层实际使用的降维方式/维度/簇数。
     * 显式返回而不是存实例字段 —— {@code RaptorTreeService} 是单例，线程池里可能同时建两棵树的库。
     */
    record ClusterOutcome(List<List<NodeRef>> groups, String reduction, int dimension, int k,
                          int globalClusterCount, int[] localCounts, int[] clusterSizes,
                          int recursionSplits, int multiMembershipNodes) {

        static ClusterOutcome none() {
            return new ClusterOutcome(List.of(), ClusterPipeline.REDUCTION_NONE, 0, 1, 0,
                    new int[0], new int[0], 0, 0);
        }
    }

    /**
     * 降维 + 聚类，返回按簇分组的成员列表；任何异常都退化成「一个簇」。
     *
     * <p>实际的「降维 → 安全闸门 → GMM（簇数硬顶）」逻辑全部委托给 {@link ClusterPipeline}（纯算法、可单测）。
     * 关键点：<b>绝不把原始 1536 维向量送进 GMM</b>（必然 LAPACK POTRF 异常 → 退化成单簇 → 树没层次），
     * UMAP 不可用时走 PCA 兜底，两者都不可用才合并成一个父节点。
     */
    ClusterOutcome cluster(List<NodeRef> nodes, BuildParams params) {
        if (nodes.size() < 3) {
            int dim = nodes.isEmpty() ? 0 : nodes.get(0).vector.length;
            return new ClusterOutcome(List.of(new ArrayList<>(nodes)), ClusterPipeline.REDUCTION_NONE, dim, 1,
                    0, new int[0], new int[]{nodes.size()}, 0, 0);
        }
        double[][] x = new double[nodes.size()][];
        // 官方 max_length_in_cluster 判断需要每个节点的 token 数（CJK 感知估算，官方用 tiktoken）
        int[] tokenCounts = new int[nodes.size()];
        for (int i = 0; i < nodes.size(); i++) {
            x[i] = nodes.get(i).vector;
            tokenCounts[i] = TokenEstimator.estimate(nodes.get(i).text);
        }

        var umapCfg = props.getRaptor().getUmap();
        var gmmCfg = props.getRaptor().getGmm();
        ClusterPipeline.Params cp = new ClusterPipeline.Params();
        cp.reduction = props.getRaptor().getReduction();
        cp.reductionDimension = props.getRaptor().getReductionDimension();
        cp.threshold = props.getRaptor().getThreshold();
        cp.maxClusters = params.getGmmMaxClusters();
        cp.absoluteKMax = gmmCfg.getAbsoluteMaxClusters();
        cp.minClusterSize = props.getRaptor().getMinClusterSize();
        cp.maxClusterTokens = props.getRaptor().getMaxClusterTokens();
        cp.nodesPerClusterAtCap = props.getRaptor().getNodesPerClusterAtCap();
        cp.twoStage = props.getRaptor().isTwoStage();
        cp.autoGlobalNNeighbors = props.getRaptor().isAutoGlobalNNeighbors();
        cp.globalNNeighbors = props.getRaptor().getGlobalNNeighbors();
        cp.localNNeighbors = props.getRaptor().getLocalNNeighbors();
        cp.metric = umapCfg.getMetric();
        cp.gmm = gmmCfg.toGmmConfig(params.getGmmCovarianceType());
        cp.nNeighbors = params.getUmapNNeighbors();
        cp.epochs = umapCfg.getEpochs();
        cp.learningRate = umapCfg.getLearningRate();
        cp.minDist = params.getUmapMinDist();
        cp.spread = umapCfg.getSpread();
        cp.negativeSamples = umapCfg.getNegativeSamples();
        cp.repulsionStrength = umapCfg.getRepulsionStrength();
        cp.localConnectivity = umapCfg.getLocalConnectivity();

        ClusterPipeline.Outcome outcome = ClusterPipeline.run(x, tokenCounts, cp);

        if (!outcome.canSplit()) {
            log.info("建树某层无法分裂（n={} reduction={} d={}：{}），本层合并为一个父节点",
                    nodes.size(), outcome.reduction(), outcome.dimension(), outcome.message());
            return new ClusterOutcome(List.of(new ArrayList<>(nodes)), outcome.reduction(), outcome.dimension(), 1,
                    outcome.globalClusterCount(), outcome.localCounts(), outcome.clusterSizes(),
                    outcome.recursionSplits(), 0);
        }

        // 官方是软聚类（prob > threshold 的**全部**归属），所以同一节点可能落在多个簇里。
        // 这里直接按簇收成员（而不是先压成硬标签），保证摘要覆盖到的成员与簇定义一致。
        List<List<NodeRef>> groups = new ArrayList<>();
        List<NodeRef> covered = new ArrayList<>();
        boolean[] seen = new boolean[nodes.size()];
        for (List<Integer> cluster : outcome.clusters()) {
            List<NodeRef> members = new ArrayList<>(cluster.size());
            for (int idx : cluster) {
                if (idx >= 0 && idx < nodes.size()) {
                    members.add(nodes.get(idx));
                    if (!seen[idx]) {
                        seen[idx] = true;
                        covered.add(nodes.get(idx));
                    }
                }
            }
            if (!members.isEmpty()) {
                groups.add(members);
            }
        }
        // 理论上不会发生（每个节点至少有一个标签），但绝不能让节点从树里消失
        if (covered.size() < nodes.size()) {
            for (int i = 0; i < nodes.size(); i++) {
                if (!seen[i]) {
                    covered.add(nodes.get(i));
                }
            }
        }

        // ⚠️ 同样刻意不用 props.raptor.min-cluster-size（那是簇数上限，默认 8）：
        //    落库前的小簇合并只针对「单节点簇」这种明显不划算的摘要，阈值 2 即可；
        //    若跟着抬到 8，会和簇数上限叠加、二次压低簇数（实测 58 → 35 → 更少）。
        if (MERGE_TINY_CLUSTER_SIZE > 1) {
            groups = mergeTinyClusters(groups, MERGE_TINY_CLUSTER_SIZE);
        }
        if (groups.size() <= 1) {
            return new ClusterOutcome(List.of(new ArrayList<>(nodes)), outcome.reduction(), outcome.dimension(), 1,
                    outcome.globalClusterCount(), outcome.localCounts(), outcome.clusterSizes(),
                    outcome.recursionSplits(), 0);
        }
        return new ClusterOutcome(groups, outcome.reduction(), outcome.dimension(), groups.size(),
                outcome.globalClusterCount(), outcome.localCounts(), outcome.clusterSizes(),
                outcome.recursionSplits(),
                RaptorClusterer.countMultiMembership(outcome.clusters(), nodes.size()));
    }

    /** 簇规模分布摘要（只打前 12 个 + 总数，避免日志爆行）。 */
    private static String describeSizes(int[] sizes) {
        if (sizes == null || sizes.length == 0) {
            return "[]";
        }
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < Math.min(12, sizes.length); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(sizes[i]);
        }
        if (sizes.length > 12) {
            sb.append(",...共").append(sizes.length).append("个簇");
        }
        return sb.append(']').toString();
    }

    /** 把小簇并入「与它首元素向量最相似」的大簇，避免出现大量单节点摘要（{@code minClusterSize>1} 时启用）。 */
    private List<List<NodeRef>> mergeTinyClusters(List<List<NodeRef>> groups, int minSize) {
        List<List<NodeRef>> big = new ArrayList<>();
        List<NodeRef> smallOnes = new ArrayList<>();
        for (List<NodeRef> g : groups) {
            if (g.size() < minSize) {
                smallOnes.addAll(g);
            } else {
                big.add(g);
            }
        }
        if (smallOnes.isEmpty()) {
            return groups;
        }
        if (big.isEmpty()) {
            big.add(new ArrayList<>(smallOnes));
            return big;
        }
        for (NodeRef member : smallOnes) {
            int best = 0;
            double bestSim = -Double.MAX_VALUE;
            for (int gi = 0; gi < big.size(); gi++) {
                double sim = cosine(member.vector, big.get(gi).get(0).vector);
                if (sim > bestSim) {
                    bestSim = sim;
                    best = gi;
                }
            }
            big.get(best).add(member);
        }
        return big;
    }

    /* ============================== 摘要（并发） ============================== */

    /** 同层各簇的 LLM 调用并发执行（并发度受限），任一族失败降级为原文拼接。 */
    private List<PreparedSummary> prepareAll(List<List<NodeRef>> groups, int level, String buildId,
                                             BuildParams params, AsyncTaskProgressReporter reporter,
                                             ClusterOutcome co) {
        return prepareAll(groups, level, buildId, params, reporter, Map.of(), co);
    }

    private List<PreparedSummary> prepareAll(List<List<NodeRef>> groups, int level, String buildId,
                                             BuildParams params, AsyncTaskProgressReporter reporter,
                                             Map<String, Object> extraMetadata, ClusterOutcome co) {
        List<Callable<PreparedSummary>> tasks = new ArrayList<>(groups.size());
        for (int i = 0; i < groups.size(); i++) {
            List<NodeRef> members = groups.get(i);
            int label = i;
            tasks.add(() -> prepareOne(members, level, label, buildId, params, extraMetadata, co));
        }
        List<PreparedSummary> out = runConcurrently(tasks, props.getRaptor().getSummaryConcurrency());
        // 极端情况下并发任务返回 null（线程被中断/取消）→ 就地降级补齐，保证落库数量与簇数一致
        for (int i = 0; i < out.size(); i++) {
            if (out.get(i) == null) {
                log.warn("簇 {} 的并发摘要任务未返回结果，就地降级重算", i);
                out.set(i, prepareOne(groups.get(i), level, i, buildId, params, extraMetadata, co));
            }
        }
        return out;
    }

    private PreparedSummary prepareOne(List<NodeRef> members, int level, int clusterLabel, String buildId,
                                       BuildParams params, Map<String, Object> extraMetadata, ClusterOutcome co) {
        StringBuilder clusterText = new StringBuilder();
        for (NodeRef m : members) {
            clusterText.append(SummaryPrompts.memberHeader(m.chunkIndex)).append('\n')
                    .append(m.text == null ? "" : m.text).append("\n\n");
        }

        PreparedSummary ps = new PreparedSummary();
        try {
            ps.summary = summaryService.summarize(clusterText.toString(), params.getSummaryPrompt());
        } catch (Exception e) {
            log.warn("簇摘要失败，降级为原文拼接：level={} members={} err={}", level, members.size(), e.toString());
            ps.degraded = true;
        }
        if (ps.summary == null || ps.summary.isBlank()) {
            List<String> texts = new ArrayList<>(members.size());
            for (NodeRef m : members) {
                texts.add(m.text);
            }
            ps.summary = summaryService.degradedSummary(texts);
            ps.degraded = true;
        }

        try {
            ps.vector = aiGateway.embedOne(ps.summary);
        } catch (Exception e) {
            log.warn("摘要向量化失败，沿用成员向量均值：{}", e.toString());
            ps.vector = averageVector(members);
            ps.degraded = true;
        }

        int start = Integer.MAX_VALUE;
        int end = Integer.MIN_VALUE;
        for (NodeRef m : members) {
            start = Math.min(start, m.startChunk);
            end = Math.max(end, m.endChunk);
        }
        ps.start = start == Integer.MAX_VALUE ? 0 : start;
        ps.end = end == Integer.MIN_VALUE ? 0 : end;
        ps.clusterSize = members.size();

        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("buildId", buildId);
        meta.put("isRoot", false);
        meta.put("degraded", ps.degraded);
        meta.put("maxLevel", params.getMaxLevel());
        Map<String, Object> umapMeta = new LinkedHashMap<>();
        umapMeta.put("nNeighbors", params.getUmapNNeighbors());
        umapMeta.put("minDist", params.getUmapMinDist());
        umapMeta.put("targetDim", props.getRaptor().getUmap().getNComponents());
        umapMeta.put("enabled", props.getRaptor().getUmap().isEnabled());
        // 本层实际用的降维方式与维度：排查「为什么树没层次」时先看这两个字段
        umapMeta.put("reduction", co == null ? ClusterPipeline.REDUCTION_NONE : co.reduction());
        umapMeta.put("dimension", co == null ? 0 : co.dimension());
        meta.put("umap", umapMeta);
        Map<String, Object> gmmMeta = new LinkedHashMap<>();
        // 本层 GMM 实际分出的簇数（不是 clusterLabel+1）。契约 5.2 的示例里根节点 clusterLabel=0 而
        // nComponents=3，指的就是这个「真实簇数」；写 clusterLabel+1 会让人误以为每层只分了 1~k 个簇。
        gmmMeta.put("nComponents", co == null ? 1 : co.k());
        gmmMeta.put("clusterLabel", clusterLabel);
        gmmMeta.put("covarianceType", params.getGmmCovarianceType());
        // 配置的簇数上限；实际生效值 = min(maxClusters, 绝对上限, n / minClusterSize, n)（见 ClusterPipeline.effectiveKMax）
        gmmMeta.put("maxClusters", params.getGmmMaxClusters());
        gmmMeta.put("reductionDimension", props.getRaptor().getReductionDimension());
        gmmMeta.put("threshold", props.getRaptor().getThreshold());
        gmmMeta.put("twoStage", props.getRaptor().isTwoStage());
        if (co != null) {
            // 官方两级聚类的诊断字段：全局簇数 / 各全局簇的局部簇数 / 是否触发超大簇递归细分 / 软聚类多归属数。
            // 排查「树只有两层」时先看 globalClusterCount 与 clusterSizes（前者过小=全局阶段没分动，
            // 后者出现单节点簇=局部阶段过度切分）。
            gmmMeta.put("globalClusterCount", co.globalClusterCount());
            gmmMeta.put("localClusterCounts", co.localCounts());
            gmmMeta.put("clusterSizes", co.clusterSizes());
            gmmMeta.put("recursionSplits", co.recursionSplits());
            gmmMeta.put("multiMembershipNodes", co.multiMembershipNodes());
        }
        meta.put("gmm", gmmMeta);
        List<String> sourceIds = new ArrayList<>(members.size());
        for (NodeRef m : members) {
            sourceIds.add(m.id.toString());
        }
        meta.put("sourceNodeIds", sourceIds);
        meta.putAll(extraMetadata);
        ps.metadata = meta;
        return ps;
    }

    /* ============================== 落库（串行） ============================== */

    private List<NodeRef> persistAll(UUID kbId, UUID docId, List<PreparedSummary> prepared,
                                     List<List<NodeRef>> groups, int level) {
        List<NodeRef> out = new ArrayList<>(prepared.size());
        // 官方是软聚类，同一节点可能属于多个簇（prob > threshold）——**摘要内容**要覆盖全部软成员。
        // 但 summary_nodes.parent_id 是单值外键（DDL 已冻结），一个节点只能有一个父节点，
        // 否则 updateParentForIds 会「后写的赢」，出现「父节点登记的 sourceNodeIds 与库里实际孩子不一致」。
        // 折中：建父子边时按「簇下标最小者优先」取唯一父节点（确定性），多归属数量已写进 metadata。
        java.util.Set<UUID> assignedParents = new java.util.HashSet<>();
        for (int label = 0; label < prepared.size(); label++) {
            PreparedSummary ps = prepared.get(label);
            SummaryNode node = new SummaryNode();
            node.setId(UUID.randomUUID());
            node.setKnowledgeBaseId(kbId);
            node.setDocumentId(docId);
            node.setParentId(null);
            node.setNodeType("SUMMARY");
            node.setLevel((short) level);
            node.setChunkIndex(null);
            node.setStartChunkIndex(ps.start);
            node.setEndChunkIndex(ps.end);
            node.setContent(ps.summary);
            node.setSummary(ps.summary);
            node.setCharCount(ps.summary.length());
            node.setTokenCount(TokenEstimator.estimate(ps.summary));
            node.setEmbedding(VectorUtils.toLiteral(ps.vector));
            node.setClusterLabel(label);
            node.setClusterSize(ps.clusterSize);
            node.setMetadata(JsonUtils.toJson(ps.metadata));
            nodeMapper.insertSummary(node);

            // 建立父子边：本簇成员的 parent_id 指向刚写入的摘要节点（多归属时先到先得）
            if (groups != null && label < groups.size()) {
                List<UUID> memberIds = new ArrayList<>(groups.get(label).size());
                for (NodeRef m : groups.get(label)) {
                    if (m.id != null && assignedParents.add(m.id)) {
                        memberIds.add(m.id);
                    }
                }
                if (!memberIds.isEmpty()) {
                    nodeMapper.updateParentForIds(memberIds, node.getId());
                }
            }

            NodeRef ref = new NodeRef();
            ref.id = node.getId();
            ref.vector = toDouble(ps.vector);
            ref.startChunk = ps.start;
            ref.endChunk = ps.end;
            ref.text = ps.summary;
            ref.chunkIndex = -1;
            ref.metadata = ps.metadata;
            out.add(ref);
        }
        return out;
    }

    private void markRoot(UUID rootId, String buildId, long durationMs) {
        List<SummaryNode> nodes = nodeMapper.selectByIds(List.of(rootId));
        if (nodes.isEmpty()) {
            return;
        }
        Map<String, Object> meta = JsonUtils.toMap(nodes.get(0).getMetadata());
        meta.put("buildId", buildId);
        meta.put("isRoot", true);
        meta.put("builtAt", System.currentTimeMillis());
        meta.put("buildDurationMs", durationMs);
        nodeMapper.updateMetadata(rootId, JsonUtils.toJson(meta));
    }

    private static int countDegraded(List<PreparedSummary> list) {
        int n = 0;
        for (PreparedSummary ps : list) {
            if (ps != null && ps.degraded) {
                n++;
            }
        }
        return n;
    }

    private static List<NodeRef> toRefs(List<SummaryNode> nodes) {
        List<NodeRef> refs = new ArrayList<>(nodes.size());
        for (SummaryNode n : nodes) {
            NodeRef ref = new NodeRef();
            ref.id = n.getId();
            ref.vector = VectorUtils.parse(n.getEmbedding());
            ref.chunkIndex = n.getChunkIndex() == null ? 0 : n.getChunkIndex();
            ref.startChunk = n.getStartChunkIndex() == null ? ref.chunkIndex : n.getStartChunkIndex();
            ref.endChunk = n.getEndChunkIndex() == null ? ref.chunkIndex : n.getEndChunkIndex();
            ref.text = n.getContent();
            refs.add(ref);
        }
        return refs;
    }

    /**
     * 并发执行（有界线程池）。任一任务抛异常会被吞掉转成 null，由调用方补降级值，
     * 保证建树整体不因单个簇失败而失败。
     */
    static <T> List<T> runConcurrently(List<Callable<T>> tasks, int concurrency) {
        if (tasks.isEmpty()) {
            return new ArrayList<>();
        }
        int n = Math.max(1, Math.min(concurrency, tasks.size()));
        ExecutorService pool = Executors.newFixedThreadPool(n, r -> {
            Thread t = new Thread(r, "raptor-summary");
            t.setDaemon(true);
            return t;
        });
        try {
            List<Future<T>> futures = pool.invokeAll(tasks);
            List<T> out = new ArrayList<>(futures.size());
            for (Future<T> f : futures) {
                try {
                    out.add(f.get());
                } catch (Exception e) {
                    out.add(null);
                }
            }
            return out;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw BizException.of(ErrorCode.TREE_BUILD_FAILED, "建树被中断");
        } finally {
            pool.shutdownNow();
            try {
                pool.awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException ignore) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static double cosine(double[] a, double[] b) {
        if (a == null || b == null || a.length != b.length) {
            return 0;
        }
        double dot = 0;
        double na = 0;
        double nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        if (na == 0 || nb == 0) {
            return 0;
        }
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    private static float[] averageVector(List<NodeRef> members) {
        if (members.isEmpty()) {
            return new float[0];
        }
        int dim = members.get(0).vector.length;
        float[] avg = new float[dim];
        for (NodeRef m : members) {
            double[] v = m.vector;
            for (int i = 0; i < dim && i < v.length; i++) {
                avg[i] += (float) v[i];
            }
        }
        for (int i = 0; i < dim; i++) {
            avg[i] /= members.size();
        }
        return avg;
    }

    private static double[] toDouble(float[] v) {
        double[] d = new double[v.length];
        for (int i = 0; i < v.length; i++) {
            d[i] = v[i];
        }
        return d;
    }
}
