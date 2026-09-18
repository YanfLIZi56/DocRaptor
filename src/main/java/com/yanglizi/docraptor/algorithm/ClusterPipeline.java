package com.yanglizi.docraptor.algorithm;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * 「降维 → 两级 GMM 聚类」流水线（纯算法，无 Spring / 无 DB，可直接单测）。
 *
 * <p>本类现在只是 <b>{@link RaptorClusterer} 的薄封装</b>：把配置层的参数翻译成
 * {@link RaptorClusterer.Params}，再把结果翻回建树层要的形状。真正的官方对齐逻辑
 * （全局降维+GMM → 逐全局簇局部降维+GMM → 软聚类 → 超大簇递归细分）在
 * {@link RaptorClusterer} 里。
 *
 * <h3>为什么原来要「簇数硬顶」而现在不要了</h3>
 * 改造前这里有一个 {@code kMax = max(2, min(configuredMax, 绝对上限 6, ceil(sqrt(n))))} 的硬顶。
 * 它是为了堵住一个真实故障：Smile 的 GMM <b>无法播种</b>（同输入连跑 10 次 k 在 2~12 跳变），
 * 且 BIC 会一路选到上限，于是树只剩「N 个叶子 + 1 个根」。
 *
 * <p>现在根因已经解决（{@link DeterministicGmm} 确定性 + 全协方差），官方参数本身就能给出
 * 合理的簇数，因此硬顶被<b>移除</b>：{@code kMax = min(maxClusters, 绝对上限, n / minClusterSize, n)}，
 * 与官方 {@code max_clusters = min(50, len(embeddings))} 一致（绝对上限默认 0 = 不限）。
 * 硬顶曾经把 313 块的《三体》压成 3 个全局簇 —— 也就是「树只有 2 层」的直接原因。
 *
 * <p>仍然守住的底线：
 * <ol>
 *   <li><b>绝不把原始 1536 维送进 GMM</b>（必然 LAPACK POTRF 异常 → 退化成单簇），
 *       一律先降维（{@link #REDUCTION_PCA} 或 {@link #REDUCTION_UMAP}）；</li>
 *   <li>降维彻底不可用时返回 {@code k=1}，由上层把整层合并成一个父节点（保证递归收敛，不抛异常）；</li>
 *   <li>任何异常都不外泄。</li>
 * </ol>
 */
@Slf4j
public final class ClusterPipeline {

    /** 降维方式，写进 summary_nodes.metadata 便于排查「为什么树没层次」。 */
    public static final String REDUCTION_UMAP = RaptorClusterer.REDUCTION_UMAP;
    public static final String REDUCTION_PCA = RaptorClusterer.REDUCTION_PCA;
    public static final String REDUCTION_NONE = RaptorClusterer.REDUCTION_NONE;

    private ClusterPipeline() {
    }

    /** 配置参数（由 DocRaptorProperties 映射过来，保持算法层与 config 层解耦）。 */
    public static class Params {
        /** 降维方式：PCA（默认，确定性）/ UMAP（官方，但 Smile 侧无法播种）/ NONE（仅调试）。 */
        public String reduction = RaptorClusterer.REDUCTION_PCA;
        /** 官方 reduction_dimension = 10。 */
        public int reductionDimension = 10;
        /** 官方 threshold = 0.1（软聚类偏好阈值）。 */
        public double threshold = 0.1;
        /** 官方 max_clusters = 50。 */
        public int maxClusters = 50;
        /** 簇数绝对上限；&lt;=0 表示不限（默认 0 = 与官方一致）。 */
        public int absoluteKMax = 0;
        /** 最小簇规模约束（簇数上限 {@code n/minClusterSize}），默认 8。见 {@link RaptorClusterer.Params#minClusterSize}。 */
        public int minClusterSize = 8;
        /**
         * 官方 {@code max_length_in_cluster}。语义：{@code <0} 关闭递归细分；{@code 0}（默认）
         * 按块大小<b>自动折算</b>（官方的 3500 是按 ≈100 token 的块标定的，照搬到中文长块会把簇切碎）；
         * {@code >0} 字面 token 数。
         */
        public int maxClusterTokens = 0;
        /** 自动折算时「单簇可容纳的块数」（对应官方的 3500/≈100 ≈ 35 块）。 */
        public int nodesPerClusterAtCap = 35;
        /** 两级聚类开关（false 仅用于对照实验）。 */
        public boolean twoStage = true;
        /** true → 全局 nNeighbors = int(sqrt(n-1))（官方行为）。 */
        public boolean autoGlobalNNeighbors = true;
        public int globalNNeighbors = 0;
        /** 官方局部 num_neighbors = 10。 */
        public int localNNeighbors = 10;
        public String metric = "cosine";
        /** GMM 拟合配置（默认 = 官方 sklearn 默认：全协方差、不标准化、reg_covar=1e-6）。 */
        public DeterministicGmm.Config gmm = DeterministicGmm.Config.official();
        // ---- 仅在 reduction=UMAP 时生效 ----
        public int nNeighbors = 5;
        public int epochs = 200;
        public double learningRate = 1.0;
        public double minDist = 0.05;
        public double spread = 1.0;
        public int negativeSamples = 5;
        public double repulsionStrength = 1.0;
        public double localConnectivity = 1.0;
    }

    /**
     * 聚类结果。
     *
     * @param clusters         每个簇的成员下标（升序）。<b>软聚类下同一节点可以出现在多个簇里</b>
     *                         （官方 {@code prob > threshold} 的全部归属），因此各簇大小之和 ≥ n。
     * @param k                簇数（{@code clusters.size()}）；1 表示「无法分裂，整层合并」
     * @param reduction        PCA / UMAP / NONE
     * @param dimension        <b>实际喂给 GMM 的维度</b>；0 表示压根没进聚类（无法安全降维 → 本层合并）
     * @param kMaxUsed         实际生效的簇数上限
     * @param globalClusterCount 全局阶段分出的簇数（诊断：它≈0.x 时说明局部阶段没起作用）
     * @param localCounts      各全局簇的局部簇数
     * @param clusterSizes     簇规模降序（诊断：一眼看出有没有超大簇/单节点簇）
     * @param recursionSplits  「总 token 超限 → 递归细分」触发的次数
     */
    public record Outcome(List<List<Integer>> clusters, int k, String reduction, int dimension,
                          int kMaxUsed, double bic, boolean degraded, String message,
                          int globalClusterCount, int[] localCounts, int[] clusterSizes,
                          int recursionSplits, int maxClusterTokensUsed) {

        public boolean canSplit() {
            return k > 1;
        }

        /** 硬标签（首个命中的簇）；仅为兼容旧调用方，建树请直接用 {@link #clusters()}。 */
        public int[] labels() {
            if (clusters == null || clusters.isEmpty()) {
                return new int[0];
            }
            int n = 0;
            for (List<Integer> c : clusters) {
                for (int idx : c) {
                    n = Math.max(n, idx + 1);
                }
            }
            int[] out = new int[n];
            java.util.Arrays.fill(out, -1);
            for (int c = 0; c < clusters.size(); c++) {
                for (int idx : clusters.get(c)) {
                    if (out[idx] < 0) {
                        out[idx] = c;
                    }
                }
            }
            for (int i = 0; i < n; i++) {
                if (out[i] < 0) {
                    out[i] = 0;
                }
            }
            return out;
        }
    }

    /** 簇数上限：{@code min(maxClusters, 绝对上限, n / minClusterSize, n)}（与官方 {@code min(50, n)} 同族）。 */
    public static int effectiveKMax(int sampleCount, int configuredMax) {
        return effectiveKMax(sampleCount, configuredMax, 0, 1);
    }

    public static int effectiveKMax(int sampleCount, int configuredMax, int absoluteMax) {
        return effectiveKMax(sampleCount, configuredMax, absoluteMax, 1);
    }

    public static int effectiveKMax(int sampleCount, int configuredMax, int absoluteMax, int minClusterSize) {
        return RaptorClusterer.effectiveKMax(sampleCount, configuredMax, absoluteMax, minClusterSize);
    }

    /** 无 token 信息时跑流水线（等价于关闭超大簇递归细分）。 */
    public static Outcome run(double[][] raw, Params p) {
        return run(raw, null, p);
    }

    /**
     * 跑完整流水线。任何情况下都不抛异常。
     *
     * @param tokenCounts 每个节点的 token 数（官方 {@code max_length_in_cluster} 用它判断是否需要递归细分）；
     *                    null 或长度不符时跳过该步
     */
    public static Outcome run(double[][] raw, int[] tokenCounts, Params p) {
        if (raw == null || raw.length == 0) {
            return single(0, REDUCTION_NONE, 0, 0, "无样本");
        }
        int n = raw.length;
        int rawDim = raw[0].length;
        if (n < 3) {
            // 节点太少：直接合并成一个父节点（架构 5.1 的 nClusters<=1 分支）
            return single(n, REDUCTION_NONE, 0, 0, "样本数 " + n + " < 3，直接合并");
        }
        try {
            RaptorClusterer.Params rp = new RaptorClusterer.Params();
            rp.reduction = p.reduction;
            rp.reductionDimension = p.reductionDimension;
            rp.threshold = p.threshold;
            rp.maxClusters = p.maxClusters;
            rp.absoluteMaxClusters = p.absoluteKMax;
            rp.minClusterSize = p.minClusterSize;
            rp.maxClusterTokens = p.maxClusterTokens;
            rp.nodesPerClusterAtCap = p.nodesPerClusterAtCap;
            rp.twoStage = p.twoStage;
            rp.autoGlobalNNeighbors = p.autoGlobalNNeighbors;
            rp.globalNNeighbors = p.globalNNeighbors;
            rp.localNNeighbors = p.localNNeighbors;
            rp.metric = p.metric;
            rp.gmm = p.gmm;
            rp.umapEpochs = p.epochs;
            rp.umapMinDist = p.minDist;
            rp.umapLearningRate = p.learningRate;
            rp.umapSpread = p.spread;
            rp.umapNegativeSamples = p.negativeSamples;
            rp.umapRepulsionStrength = p.repulsionStrength;
            rp.umapLocalConnectivity = p.localConnectivity;

            RaptorClusterer.Result r = RaptorClusterer.cluster(raw, tokenCounts, rp);

            if (r.clusters.isEmpty() || r.k() <= 1) {
                return single(n, r.reduction, r.dimension, r.kMaxUsed,
                        "聚类未产出多个簇：" + r.note);
            }
            return new Outcome(r.clusters, r.k(), r.reduction, r.dimension, r.kMaxUsed,
                    Double.NaN, r.degenerate, r.note, r.globalClusterCount, r.localCounts,
                    r.clusterSizes, r.recursionSplits, r.maxClusterTokensUsed);
        } catch (Throwable t) {
            // 兜底：聚类异常绝不能让建树失败（树必须收敛到一个根）
            log.warn("聚类异常，本层合并为一个父节点：{}", t.toString());
            return single(n, REDUCTION_NONE, 0, effectiveKMax(n, p.maxClusters, p.absoluteKMax, p.minClusterSize),
                    "聚类异常降级：" + t);
        }
    }

    private static Outcome single(int n, String reduction, int dimension, int kMaxUsed, String message) {
        List<List<Integer>> clusters = new ArrayList<>(1);
        List<Integer> all = new ArrayList<>(Math.max(0, n));
        for (int i = 0; i < n; i++) {
            all.add(i);
        }
        clusters.add(all);
        return new Outcome(clusters, 1, reduction, dimension, kMaxUsed, Double.NaN, true, message,
                0, new int[0], new int[]{n}, 0, 0);
    }
}
