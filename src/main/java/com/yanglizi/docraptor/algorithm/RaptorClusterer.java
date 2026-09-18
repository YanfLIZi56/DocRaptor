package com.yanglizi.docraptor.algorithm;

import lombok.extern.slf4j.Slf4j;
import smile.feature.extraction.PCA;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

/**
 * <b>官方对齐的两级聚类</b>：全局降维 + GMM → 逐全局簇再降维 + 再 GMM → 局部簇（软聚类 + 编号偏移）。
 *
 * <p>对应官方 {@code raptor/cluster_utils.py:72-126 perform_clustering} 与
 * {@code :135-188 RAPTOR_Clustering.perform_clustering}。这是我们此前<b>完全缺失</b>的一环 ——
 * 只有单级 GMM 时，313 块的《三体》只能得到 3 个簇；两级之后簇数会上升到数十个量级
 * （最终簇数 ≈ 各全局簇的局部簇数之和）。
 *
 * <p>关键点（逐条对应官方实现）：
 * <ol>
 *   <li>全局降维维度 {@code reduction_dimension=10}，并按 {@code 2d+3 < n} 夹紧；</li>
 *   <li>全局 {@code n_neighbors = int(sqrt(n-1))}（n=313 → 17），局部固定 {@code 10}；</li>
 *   <li>每个全局簇的成员<b>单独再降维、再 GMM</b>；成员数 ≤ {@code dim+1} 时整簇算一个局部簇；</li>
 *   <li>局部簇编号加偏移，避免跨全局簇重号；</li>
 *   <li>软聚类：{@code responsibilities > threshold}（官方 0.1）的<b>全部</b>归属，可多标签；</li>
 *   <li>总 token &gt; {@code max_cluster_tokens}（官方 3500）的簇<b>递归再聚类</b>；单节点簇原样保留。</li>
 * </ol>
 *
 * <h3>相比官方的两点有意偏离（都会记录在案）</h3>
 * <ol>
 *   <li><b>软聚类可能产生空标签集</b>：官方 {@code np.where(prob > threshold)} 有可能为空，
 *       那样该节点会从所有簇里消失、整块内容丢失。这里做了保护 —— 空标签集时退回硬 argmax，
 *       保证每个节点至少归属一个簇。</li>
 *   <li><b>降维默认用 PCA 而非 UMAP</b>：官方 Python 的 {@code umap.UMAP(...)} 其实<b>也没有设 random_state</b>，
 *       而 Smile 4.1.0 的 UMAP 同样无法播种 —— 实测同输入连跑 3 次结果<b>逐位不同</b>。
 *       本项目 P0 目标是「同输入必得同输出」，所以默认走确定性 PCA（实测 5 次逐位相同），
 *       UMAP 仍可通过 {@code reduction=UMAP} 打开做对照实验。</li>
 * </ol>
 */
@Slf4j
public final class RaptorClusterer {

    /** 降维方式。 */
    public static final String REDUCTION_PCA = "PCA";
    public static final String REDUCTION_UMAP = "UMAP";
    public static final String REDUCTION_NONE = "NONE";

    /** PCA 兜底时允许的最大目标维度（防止降维失败后把 1536 维直接喂 GMM）。 */
    private static final int PCA_FALLBACK_MAX_DIM = 64;

    private RaptorClusterer() {
    }

    /** 聚类参数（由 DocRaptorProperties 映射，保持算法层与 config 层解耦）。 */
    public static class Params {
        /** 官方 reduction_dimension = 10。 */
        public int reductionDimension = 10;
        /** 官方 threshold = 0.1（软聚类偏好阈值）。 */
        public double threshold = 0.1;
        /** 官方 max_clusters = 50。 */
        public int maxClusters = 50;
        /**
         * <b>最小簇规模约束</b>：把簇数上限压到 {@code n / minClusterSize}，<b>默认 8</b>。
         *
         * <p><b>为什么需要</b>：官方公式是 {@code kMax = min(50, n)}，
         * 而两级聚类的<b>局部阶段</b>成员数必然不多（十几到几十个），于是 {@code kMax} 就等于 {@code n}，
         * BIC 一旦被「每点一个分量」的解骗过去，就会输出 n 个单节点簇。
         * 实测（《三体》313 块）：`minSize=1 → 309~319 个簇、单节点簇占 97~99%`；
         * `minSize=8 → 23~35 个簇、单节点簇 0~4%`。
         *
         * <p><b>它加在全局与局部两处</b>（外加超大簇递归细分那一步），三处都用
         * {@link #effectiveKMax(int, int, int, int)}，避免任何一层出现 {@code kMax = n}。
         *
         * <p>另一条独立的防线是全协方差（见 {@link DeterministicGmm.Config#official()}）：
         * 对角协方差每分量只要 {@code 2d} 个参数，在局部子集上会直接塌成 k=n；全协方差要
         * {@code d + d(d+1)/2} 个参数，塌不动。两者叠加后本参数只是「兜底 + 把簇数压进验收区间」，
         * 不再是唯一防线。
         */
        public int minClusterSize = 8;
        /** &lt;=0 表示不设绝对上限（由 maxClusters 与 n 兜住）。 */
        public int absoluteMaxClusters = 0;
        /** true → 全局 nNeighbors = int(sqrt(n-1))（官方行为）。 */
        public boolean autoGlobalNNeighbors = true;
        /** autoGlobalNNeighbors=false 时使用；&lt;=0 时回退到 sqrt 公式。 */
        public int globalNNeighbors = 0;
        /** 官方局部 num_neighbors = 10。 */
        public int localNNeighbors = 10;
        public String metric = "cosine";
        /** 是否启用两级聚类（false 便于与单级做对照实验）。 */
        public boolean twoStage = true;
        /** PCA / UMAP / NONE（NONE = 直接对原始向量聚类，仅调试用）。 */
        public String reduction = REDUCTION_PCA;
        /**
         * GMM 拟合配置。默认 {@link DeterministicGmm.Config#official()} =
         * 全协方差 + 不标准化 + {@code reg_covar=1e-6} + {@code max_iter=100} + {@code tol=1e-3}，
         * 逐项对齐官方用的 sklearn {@code GaussianMixture} 默认值。
         */
        public DeterministicGmm.Config gmm = DeterministicGmm.Config.official();
        /**
         * 官方 {@code max_length_in_cluster}。语义：
         * <ul>
         *   <li>{@code < 0}：<b>关闭</b>超大簇递归细分；</li>
         *   <li>{@code == 0}（默认）：<b>自动折算</b> —— 见 {@link #resolveMaxClusterTokens}；</li>
         *   <li>{@code > 0}：字面 token 数。</li>
         * </ul>
         *
         * <p><b>为什么不照搬官方的 3500</b>（Lead 实测）：官方的 3500 是按它自己<b>约 100 token 的块</b>
         * 标定的，等于「单簇约 35 块」。我们的块是 700 中文字符 ≈ 470~670 token，
         * 同样 3500 只容得下 5~7 块 → 递归细分被疯狂触发，把簇切得极碎
         * （313 块实测：3500 → 102 簇、24% 单节点簇；20000 → 47 簇、2% 单节点簇）。
         */
        public int maxClusterTokens = 0;
        /** 自动折算时「单簇可容纳的块数」，对应官方的 3500/≈100 ≈ 35 块。 */
        public int nodesPerClusterAtCap = 35;
        /** 超大簇递归细分的最大深度（官方无显式上限，这里加保护）。 */
        public int maxRecursionDepth = 4;
        // ---- 仅在 reduction=UMAP 时生效 ----
        public int umapEpochs = 200;
        public double umapMinDist = 0.1;
        public double umapLearningRate = 1.0;
        public double umapSpread = 1.0;
        public int umapNegativeSamples = 5;
        public double umapRepulsionStrength = 1.0;
        public double umapLocalConnectivity = 1.0;
    }

    /** 聚类结果。{@code clusters} 里的下标可以重复出现（软聚类多归属）。 */
    public static class Result {
        /** 每个簇的成员下标（升序、无重复）。 */
        public List<List<Integer>> clusters = new ArrayList<>();
        /** 全局聚类分出的簇数（诊断用）。 */
        public int globalClusterCount;
        /** 各全局簇的局部簇数（诊断用，长度 = globalClusterCount）。 */
        public int[] localCounts = new int[0];
        public String reduction = REDUCTION_NONE;
        public int dimension;
        /** 实际生效的簇数上限。 */
        public int kMaxUsed;
        /** 簇规模降序排列，便于一眼看出是否还有超大簇。 */
        public int[] clusterSizes = new int[0];
        /** 是否发生了退化（无法降维 / 只有一个簇）。 */
        public boolean degenerate;
        public String note = "ok";
        /** 超大簇递归细分次数。 */
        public int recursionSplits;
        /**
         * 被<b>多个簇</b>同时收录的节点数（软聚类 + {@code threshold} 的直接后果）。
         *
         * <p>官方是「塌缩树」语义，一个节点可以挂在多个父节点下；而本项目的
         * {@code summary_nodes.parent_id} 是<b>单值外键 + 冻结的 DDL</b>，一个节点只能有一个父节点。
         * 因此建树落库时按「下标最小的簇优先」取唯一父节点（见
         * {@code RaptorTreeService.persistAll}），同时把本计数写进节点元数据，
         * 便于事后判断软聚类到底影响有多大。
         */
        public int multiMembershipNodes;
        /** 本次实际生效的超大簇 token 上限（自动折算的结果；<=0 表示关闭）。 */
        public int maxClusterTokensUsed;

        public int k() {
            return clusters.size();
        }

        public int largestClusterSize() {
            int max = 0;
            for (List<Integer> c : clusters) {
                max = Math.max(max, c.size());
            }
            return max;
        }
    }

    /** 簇数上限：{@code min(maxClusters, 绝对上限, n)}；绝对上限 &lt;=0 表示不限。 */
    public static int effectiveKMax(int sampleCount, int configuredMax, int absoluteMax) {
        return effectiveKMax(sampleCount, configuredMax, absoluteMax, 1);
    }

    /**
     * 簇数上限（含最小簇规模约束）：{@code min(maxClusters, 绝对上限, n / minClusterSize, n)}。
     *
     * <p>{@code minClusterSize <= 1} 时退化为官方原式 {@code min(maxClusters, n)}。
     * 加上这个约束是为了防止局部阶段 {@code kMax = n} → k=n → 全部单节点簇（见
     * {@link Params#minClusterSize} 里的实测数据）。
     */
    public static int effectiveKMax(int sampleCount, int configuredMax, int absoluteMax, int minClusterSize) {
        int cap = configuredMax > 0 ? configuredMax : 50;
        if (absoluteMax > 0) {
            cap = Math.min(cap, absoluteMax);
        }
        if (minClusterSize > 1) {
            cap = Math.min(cap, Math.max(1, sampleCount / minClusterSize));
        }
        return Math.max(1, Math.min(cap, sampleCount));
    }

    /** 全局 nNeighbors：官方 {@code int(sqrt(n-1))}。 */
    public static int autoGlobalNNeighbors(int n) {
        return Math.max(2, (int) Math.sqrt(Math.max(1, n - 1)));
    }

    /** 官方的 token 上限字面值（仅在拿不到 token 数时兜底）。 */
    public static final int OFFICIAL_MAX_CLUSTER_TOKENS = 3500;

    /**
     * 折算实际生效的 {@code maxClusterTokens}。
     *
     * <p>官方 {@code max_length_in_cluster=3500} 是按「约 100 token 的块」标定的，等价于
     * <b>单簇约 35 块</b>。直接照搬到 700 中文字符（≈470~670 token）的块上，单簇只能装 5~7 块，
     * 递归细分会把簇切碎（实测 313 块：3500 → 102 簇 / 24% 单节点簇）。
     * 因此自动模式下保持「单簇容纳的块数」不变：
     * <pre>
     *   cap = round(nodesPerClusterAtCap × 中位数(每块 token 数))
     * </pre>
     * 用中位数而不是均值，避免个别超长块把上限抬飞。
     *
     * @return 折算后的 token 上限；{@code <=0} 表示关闭递归细分
     */
    public static int resolveMaxClusterTokens(int[] tokenCounts, Params p) {
        if (p.maxClusterTokens < 0) {
            return -1;
        }
        if (p.maxClusterTokens > 0) {
            return p.maxClusterTokens;
        }
        if (tokenCounts == null || tokenCounts.length == 0) {
            return OFFICIAL_MAX_CLUSTER_TOKENS;
        }
        int[] sorted = tokenCounts.clone();
        Arrays.sort(sorted);
        int mid = sorted.length / 2;
        double median = sorted.length % 2 == 1
                ? sorted[mid]
                : (sorted[mid - 1] + sorted[mid]) / 2.0;
        if (!(median > 0)) {
            return OFFICIAL_MAX_CLUSTER_TOKENS;
        }
        long cap = Math.round(Math.max(1, p.nodesPerClusterAtCap) * median);
        return (int) Math.max(1, Math.min(Integer.MAX_VALUE, cap));
    }

    /** 降维目标维度：{@code min(reductionDimension, n-2)}，并按 UMAP 的严格 {@code 2d+3 < n} 夹紧。 */
    public static int targetDim(int n, int reductionDimension) {
        int d = Math.min(Math.max(1, reductionDimension), Math.max(1, n - 2));
        // UMAP 走谱初始化时需要 NEV = 2d+3 < n —— 这里统一按最坏情况夹紧，PCA 也一并适用（无害）
        int byUmap = (n - 4) / 2;
        if (byUmap >= 1) {
            d = Math.min(d, byUmap);
        }
        return Math.max(1, d);
    }

    /**
     * 主入口：对 {@code x}（n × d 的原始 embedding）做两级聚类。
     *
     * @param x           n × d 原始向量（通常是 1536 维 embedding）
     * @param tokenCounts 每个节点的 token 数（用于超大簇递归细分；null 则跳过该步）
     */
    public static Result cluster(double[][] x, int[] tokenCounts, Params p) {
        Result result = new Result();
        if (x == null || x.length == 0) {
            result.degenerate = true;
            result.note = "无样本";
            return result;
        }
        int n = x.length;
        int dim = targetDim(n, p.reductionDimension);
        result.dimension = dim;

        // 节点太少（官方停止条件 n <= dim+1）→ 整层一个簇
        if (n <= dim + 1 || n < 3) {
            result.clusters.add(allIndices(n));
            result.degenerate = true;
            result.note = "n=" + n + " <= reductionDimension+1=" + (dim + 1) + "，整层合并为一个簇";
            result.clusterSizes = new int[]{n};
            result.reduction = "NONE";
            result.dimension = 0;
            return result;
        }

        List<List<Integer>> raw = twoStage(x, dim, p, result);
        if (raw == null || raw.isEmpty()) {
            result.clusters.add(allIndices(n));
            result.degenerate = true;
            result.note = "聚类未产出有效簇，整层合并";
            result.clusterSizes = new int[]{n};
            return result;
        }

        // 超大簇递归细分（官方 max_length_in_cluster；上限按块大小自动折算，见 resolveMaxClusterTokens）
        int tokenCap = resolveMaxClusterTokens(tokenCounts, p);
        result.maxClusterTokensUsed = tokenCap;
        result.clusters = tokenCap > 0 && tokenCounts != null
                ? splitOversized(raw, x, tokenCounts, dim, p, result, 0, tokenCap)
                : raw;

        result.clusterSizes = sizesDesc(result.clusters);
        result.multiMembershipNodes = countMultiMembership(result.clusters, n);
        if (result.clusters.size() <= 1) {
            result.degenerate = true;
            if ("ok".equals(result.note)) {
                result.note = "只产出 1 个簇";
            }
        }
        return result;
    }

    /** 统计被多个簇同时收录的节点数（软聚类诊断）。 */
    public static int countMultiMembership(List<List<Integer>> clusters, int n) {
        int[] hits = new int[Math.max(0, n)];
        for (List<Integer> c : clusters) {
            for (int i : c) {
                if (i >= 0 && i < hits.length) {
                    hits[i]++;
                }
            }
        }
        int multi = 0;
        for (int h : hits) {
            if (h > 1) {
                multi++;
            }
        }
        return multi;
    }

    // ------------------------------------------------------------------

    /** 官方 perform_clustering：全局 → 逐全局簇局部。返回 null 表示无法聚类。 */
    private static List<List<Integer>> twoStage(double[][] x, int dim, Params p, Result diag) {
        int n = x.length;
        int nnGlobal = p.autoGlobalNNeighbors ? autoGlobalNNeighbors(n)
                : (p.globalNNeighbors > 0 ? p.globalNNeighbors : autoGlobalNNeighbors(n));

        String[] used = new String[1];
        double[][] reducedGlobal = reduce(x, dim, nnGlobal, p, used);
        if (reducedGlobal == null) {
            diag.note = "全局降维失败";
            return null;
        }
        diag.reduction = used[0];

        int kMaxGlobal = effectiveKMax(reducedGlobal.length, p.maxClusters, p.absoluteMaxClusters, p.minClusterSize);
        diag.kMaxUsed = kMaxGlobal;
        int[][] globalLabels = softLabels(reducedGlobal, kMaxGlobal, p);
        int kGlobal = 0;
        for (int[] ls : globalLabels) {
            for (int l : ls) {
                kGlobal = Math.max(kGlobal, l + 1);
            }
        }
        diag.globalClusterCount = kGlobal;
        if (kGlobal <= 0) {
            return null;
        }

        List<List<Integer>> clusters = new ArrayList<>();
        int[] localCounts = new int[kGlobal];
        int totalClusters = 0;

        for (int gi = 0; gi < kGlobal; gi++) {
            List<Integer> members = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                for (int l : globalLabels[i]) {
                    if (l == gi) {
                        members.add(i);
                        break;
                    }
                }
            }
            if (members.isEmpty()) {
                continue;
            }
            List<List<Integer>> localClusters;
            if (!p.twoStage || members.size() <= dim + 1) {
                // 官方：成员数 <= dim+1 时整簇视为一个局部簇
                localClusters = List.of(members);
            } else {
                double[][] sub = submatrix(x, members);
                int subDim = targetDim(sub.length, p.reductionDimension);
                String[] subUsed = new String[1];
                double[][] reducedLocal = reduce(sub, subDim, p.localNNeighbors, p, subUsed);
                if (reducedLocal == null || reducedLocal.length != sub.length) {
                    localClusters = List.of(members);
                } else {
                    int kMaxLocal = effectiveKMax(reducedLocal.length, p.maxClusters, p.absoluteMaxClusters, p.minClusterSize);
                    int[][] localLabels = softLabels(reducedLocal, kMaxLocal, p);
                    int kLocal = 0;
                    for (int[] ls : localLabels) {
                        for (int l : ls) {
                            kLocal = Math.max(kLocal, l + 1);
                        }
                    }
                    List<List<Integer>> tmp = new ArrayList<>();
                    for (int li = 0; li < kLocal; li++) {
                        List<Integer> lm = new ArrayList<>();
                        for (int i = 0; i < sub.length; i++) {
                            for (int l : localLabels[i]) {
                                if (l == li) {
                                    lm.add(members.get(i));
                                    break;
                                }
                            }
                        }
                        if (!lm.isEmpty()) {
                            tmp.add(lm);
                        }
                    }
                    localClusters = tmp.isEmpty() ? List.of(members) : tmp;
                }
            }
            // 局部簇编号加偏移：这里用「顺序追加」天然实现偏移，避免跨全局簇重号
            for (List<Integer> lc : localClusters) {
                clusters.add(lc);
                localCounts[gi]++;
                totalClusters++;
            }
        }
        diag.localCounts = localCounts;
        log.info("两级聚类完成：n={} dim={} reduction={} 全局簇={} 局部簇合计={}",
                n, dim, diag.reduction, kGlobal, totalClusters);
        return clusters;
    }

    /**
     * 官方后处理：单节点簇原样保留；总 token 超限的簇递归再聚类。
     * 递归有深度上限，并且只在「确实能分成多个簇」时才替换，保证一定终止。
     */
    private static List<List<Integer>> splitOversized(List<List<Integer>> clusters, double[][] x,
                                                      int[] tokenCounts, int dim, Params p,
                                                      Result diag, int depth, int cap) {
        List<List<Integer>> out = new ArrayList<>();
        for (List<Integer> c : clusters) {
            if (c.size() <= 1) {
                out.add(c);   // 官方：单节点簇原样保留
                continue;
            }
            int tokens = 0;
            for (int i : c) {
                tokens += Math.max(0, tokenCounts[i]);
            }
            if (tokens <= cap || depth >= p.maxRecursionDepth) {
                out.add(c);
                continue;
            }
            // 递归：在这个子集上再做一次两级聚类
            double[][] sub = submatrix(x, c);
            int subDim = targetDim(sub.length, dim);
            String[] used = new String[1];
            double[][] reduced = reduce(sub, subDim, autoGlobalNNeighbors(sub.length), p, used);
            if (reduced == null || reduced.length != sub.length) {
                out.add(c);
                continue;
            }
            int kMax = effectiveKMax(reduced.length, p.maxClusters, p.absoluteMaxClusters, p.minClusterSize);
            int[][] labels = softLabels(reduced, kMax, p);
            int kFound = 0;
            for (int[] ls : labels) {
                for (int l : ls) {
                    kFound = Math.max(kFound, l + 1);
                }
            }
            List<List<Integer>> subClusters = new ArrayList<>();
            for (int li = 0; li < kFound; li++) {
                List<Integer> lm = new ArrayList<>();
                for (int i = 0; i < sub.length; i++) {
                    for (int l : labels[i]) {
                        if (l == li) {
                            lm.add(c.get(i));
                            break;
                        }
                    }
                }
                if (!lm.isEmpty()) {
                    subClusters.add(lm);
                }
            }
            if (subClusters.size() <= 1) {
                out.add(c);   // 分不动就别硬分，避免无限递归
                continue;
            }
            diag.recursionSplits++;
            out.addAll(splitOversized(subClusters, x, tokenCounts, subDim, p, diag, depth + 1, cap));
        }
        return out;
    }

    /**
     * 降维。{@code reduction=UMAP} 时先试 UMAP、失败再 PCA；{@code PCA} 直接 PCA。
     *
     * @param used 出参：实际使用的降维方式
     * @return 降维后的矩阵；null 表示无法安全降维
     */
    static double[][] reduce(double[][] x, int dim, int nNeighbors, Params p, String[] used) {
        int n = x.length;
        int d = x[0].length;
        if (n < 3) {
            used[0] = REDUCTION_NONE;
            return null;
        }
        int target = Math.min(Math.max(1, dim), Math.min(n - 1, d));
        if (target >= d) {
            // 本来维度就不高，不必降维（但仍要保证低于样本数，防协方差退化）
            used[0] = REDUCTION_NONE;
            return x;
        }

        if (REDUCTION_UMAP.equalsIgnoreCase(p.reduction)) {
            double[][] y = UmapReducer.tryUmap(x, nNeighbors, target, p.umapEpochs, p.umapLearningRate,
                    p.umapMinDist, p.umapSpread, p.umapNegativeSamples, p.umapRepulsionStrength,
                    p.umapLocalConnectivity);
            if (y != null && y.length == n && y[0].length >= 1) {
                used[0] = REDUCTION_UMAP;
                return y;
            }
            log.warn("UMAP 不可用（n={} dim={}），回退 PCA", n, target);
        }

        double[][] pca = pca(x, target);
        if (pca != null) {
            used[0] = REDUCTION_PCA;
            return pca;
        }
        // 最后兜底：缩到更小的维度再试一次（绝不放原始 1536 维过去）
        int fallback = Math.min(PCA_FALLBACK_MAX_DIM, Math.max(2, Math.min(n - 1, d) / 2));
        if (fallback < target) {
            double[][] pca2 = pca(x, fallback);
            if (pca2 != null) {
                used[0] = REDUCTION_PCA;
                return pca2;
            }
        }
        used[0] = REDUCTION_NONE;
        return null;
    }

    private static double[][] pca(double[][] x, int dim) {
        try {
            int target = Math.min(Math.max(1, dim), Math.min(x.length - 1, x[0].length));
            if (target < 1) {
                return null;
            }
            PCA pca = PCA.fit(x);
            double[][] y = pca.getProjection(target).apply(x);
            if (y == null || y.length != x.length || y[0].length < 1) {
                return null;
            }
            return y;
        } catch (Throwable t) {
            log.warn("PCA 降维失败（n={} dim={}）：{}", x.length, dim, t.toString());
            return null;
        }
    }

    /** 软聚类标签：{@code responsibilities > threshold} 的全部簇；空集时退回硬 argmax（防丢节点）。 */
    static int[][] softLabels(double[][] reduced, int kMax, Params p) {
        int n = reduced.length;
        DeterministicGmm.Selection sel = DeterministicGmm.selectByBic(reduced, 1, kMax, p.gmm);
        DeterministicGmm.Model m = sel.model();
        int[][] out = new int[n][];
        for (int i = 0; i < n; i++) {
            int[] soft = m.softLabels(i, p.threshold);
            if (soft.length == 0) {
                // 保护：所有责任度都不超过阈值时，至少给一个硬标签，否则该节点会从树里消失
                soft = new int[]{m.labels()[i]};
            }
            out[i] = soft;
        }
        return out;
    }

    private static List<Integer> allIndices(int n) {
        List<Integer> all = new ArrayList<>(n);
        for (int i = 0; i < n; i++) {
            all.add(i);
        }
        return all;
    }

    private static double[][] submatrix(double[][] x, List<Integer> idx) {
        double[][] sub = new double[idx.size()][];
        for (int i = 0; i < idx.size(); i++) {
            sub[i] = x[idx.get(i)];
        }
        return sub;
    }

    private static int[] sizesDesc(List<List<Integer>> clusters) {
        int[] sizes = new int[clusters.size()];
        for (int i = 0; i < clusters.size(); i++) {
            sizes[i] = clusters.get(i).size();
        }
        Integer[] boxed = new Integer[sizes.length];
        for (int i = 0; i < sizes.length; i++) {
            boxed[i] = sizes[i];
        }
        Arrays.sort(boxed, Comparator.reverseOrder());
        for (int i = 0; i < sizes.length; i++) {
            sizes[i] = boxed[i];
        }
        return sizes;
    }
}
