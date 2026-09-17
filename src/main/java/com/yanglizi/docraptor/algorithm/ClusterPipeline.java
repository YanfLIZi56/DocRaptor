package com.yanglizi.docraptor.algorithm;

import lombok.extern.slf4j.Slf4j;

/**
 * 「降维 → GMM 聚类」的完整流水线（纯算法，无 Spring / 无 DB，可直接单测）。
 *
 * <p>存在的理由：docs/00-environment-facts.md 第 10 节的两条硬约束 + 「BIC 在合成数据上随 k 单调下降」，
 * 必须集中在一处守住，否则很容易被无意改回「原始 1536 维直接喂 GMM」或「放任 BIC 选到 kMax」，
 * 结果就是建出一棵「N 个叶子 + 1 个根」的、完全没有层次的树 —— 而这个过程<b>不抛异常、不报错</b>，
 * 静默退化，纯逻辑单测也测不出来。
 *
 * <p>守住的四件事：
 * <ol>
 *   <li>降维结果必须是 GMM 吃得下的低维（{@link UmapReducer#gmmSafe}），
 *       <b>绝不把原始 1536 维送进 {@link GmmClusterer}</b>（送进去必然 LAPACK POTRF 异常 → 退化成单簇）；</li>
 *   <li>UMAP 失败时用 PCA 兜底，而不是回退原始维度；</li>
 *   <li>降维彻底不可用时返回 {@code k=1}，由上层把整层合并成一个父节点（保证递归收敛，不抛异常）；</li>
 *   <li>对簇数上限设硬顶 {@code kMax = max(2, min(configuredMax, ceil(sqrt(n))))}，不让 BIC 自由搜索到上限。</li>
 * </ol>
 */
@Slf4j
public final class ClusterPipeline {

    /** 降维方式，写进 summary_nodes.metadata 便于排查「为什么树没层次」。 */
    public static final String REDUCTION_UMAP = "UMAP";
    public static final String REDUCTION_PCA = "PCA";
    public static final String REDUCTION_NONE = "NONE";

    /**
     * 簇数绝对上限默认值（Lead 推荐：{@code kMax = max(2, min(6, ceil(sqrt(n))))}）。
     * 存在的理由：实测 BIC 在合成数据上随 k 单调下降，放任它自由搜索就会一路选到上限，
     * 于是摘要退化成对单个块的复述；必须同时用 sqrt(n) 和这个绝对上限把它压住。
     */
    public static final int DEFAULT_ABSOLUTE_KMAX = 6;

    private ClusterPipeline() {
    }

    /** 配置参数（由 DocRaptorProperties 映射过来，保持算法层与 config 层解耦）。 */
    public static class Params {
        public boolean umapEnabled = true;
        public int nNeighbors = 5;
        public int targetDim = 2;
        public int epochs = 200;
        public double learningRate = 1.0;
        public double minDist = 0.05;
        public double spread = 1.0;
        public int negativeSamples = 5;
        public double repulsionStrength = 1.0;
        public double localConnectivity = 1.0;
        public int kMin = 2;
        public int kMax = 8;
        /** 簇数绝对上限，独立于 kMax 配置值再压一层（Lead 推荐 6）。 */
        public int absoluteKMax = DEFAULT_ABSOLUTE_KMAX;
        public boolean diagonal = true;
        public String selection = "BIC";
    }

    /**
     * @param labels    每个样本的簇标签；{@code k=1} 时全为 0
     * @param k         簇数；1 表示「无法分裂，整层合并」
     * @param reduction UMAP / PCA / NONE
     * @param dimension <b>实际喂给 GMM 的维度</b>；{@code 0} 表示压根没进聚类
     *                  （无法安全降维 → 本层直接合并成一个父节点）。
     *                  这样写是为了避免出现「dimension=1536 且 reduction=NONE」这种看起来像 bug 的元数据
     *                  —— 那种情况恰恰说明我们把 1536 维挡住了，没有送进 GMM。
     * @param kMaxUsed  实际生效的簇数上限（已被 sqrt 硬顶夹紧）
     */
    public record Outcome(int[] labels, int k, String reduction, int dimension,
                          int kMaxUsed, double bic, boolean degraded, String message) {

        public boolean canSplit() {
            return k > 1;
        }
    }

    /**
     * 簇数硬顶：{@code max(2, min(kMax配置值, 绝对上限, ceil(sqrt(n))))}，且不超过样本数。
     *
     * <p>用默认绝对上限 6（Lead 推荐）。n=11（验收文档的块数）→ ceil(sqrt(11))=4 → 取 4；
     * n=40 → ceil(sqrt(40))=7，但被绝对上限压到 6。
     */
    public static int effectiveKMax(int sampleCount, int configuredMax) {
        return effectiveKMax(sampleCount, configuredMax, DEFAULT_ABSOLUTE_KMAX);
    }

    public static int effectiveKMax(int sampleCount, int configuredMax, int absoluteMax) {
        int bySqrt = (int) Math.ceil(Math.sqrt(Math.max(1, sampleCount)));
        int cap = Math.max(2, Math.min(Math.min(configuredMax, absoluteMax), bySqrt));
        return Math.min(cap, Math.max(1, sampleCount));
    }

    /**
     * 跑完整流水线。任何情况下都不抛异常。
     */
    public static Outcome run(double[][] raw, Params p) {
        if (raw == null || raw.length == 0) {
            return new Outcome(new int[0], 1, REDUCTION_NONE, 0, 1, Double.NaN, true, "无样本");
        }
        int n = raw.length;
        int rawDim = raw[0].length;
        if (n < 3) {
            // 节点太少：直接合并成一个父节点（架构 5.1 的 nClusters<=1 分支）
            return new Outcome(zeros(n), 1, REDUCTION_NONE, 0, 1, Double.NaN, true,
                    "样本数 " + n + " < 3，直接合并");
        }

        // ---------- 1. 降维（UMAP → PCA → 放弃） ----------
        double[][] matrix = raw;
        String reduction = REDUCTION_NONE;
        int safeDim = UmapReducer.safeTargetDim(n, p.targetDim);

        if (safeDim >= 2) {
            double[][] umap = p.umapEnabled
                    ? UmapReducer.tryUmap(raw, p.nNeighbors, p.targetDim, p.epochs, p.learningRate,
                    p.minDist, p.spread, p.negativeSamples, p.repulsionStrength, p.localConnectivity)
                    : null;
            if (umap != null && UmapReducer.gmmSafe(umap)) {
                matrix = umap;
                reduction = REDUCTION_UMAP;
            } else {
                double[][] pca = UmapReducer.tryPca(raw, safeDim);
                if (pca != null && UmapReducer.gmmSafe(pca)) {
                    matrix = pca;
                    reduction = REDUCTION_PCA;
                    log.info("降维走 PCA 兜底（n={} rawDim={} → d={}）", n, rawDim, pca[0].length);
                }
            }
        }

        // ---------- 2. 安全闸门：绝不让高维/奇异矩阵进 GMM ----------
        if (!UmapReducer.gmmSafe(matrix)) {
            log.warn("降维不可用（n={} rawDim={}），本层不聚类、直接合并为一个父节点；GMM 未被调用",
                    n, rawDim);
            return new Outcome(zeros(n), 1, REDUCTION_NONE, 0,
                    effectiveKMax(n, p.kMax), Double.NaN, true,
                    "无法安全降维（rawDim=" + rawDim + "），本层合并为一个父节点，未调用 GMM");
        }

        // ---------- 3. GMM 聚类（簇数硬顶，不让 BIC 自由搜索） ----------
        int kMax = effectiveKMax(n, p.kMax, p.absoluteKMax);
        int kMin = Math.max(2, Math.min(p.kMin, kMax));
        GmmClusterer.Result res = GmmClusterer.safeCluster(matrix, kMin, kMax, p.diagonal, p.selection);

        if (res.k() <= 1) {
            return new Outcome(zeros(n), 1, reduction, matrix[0].length, kMax, res.bic(), true,
                    "GMM 判定无法分裂：" + res.message());
        }
        return new Outcome(res.labels(), res.k(), reduction, matrix[0].length, kMax, res.bic(), false, "ok");
    }

    private static int[] zeros(int n) {
        return new int[Math.max(0, n)];
    }
}
