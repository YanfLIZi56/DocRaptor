package com.yanglizi.docraptor.algorithm;

import lombok.extern.slf4j.Slf4j;
import smile.feature.extraction.PCA;
import smile.manifold.UMAP;

/**
 * 降维器：UMAP 为主，PCA 兜底。降维结果供 {@link GmmClusterer} 聚类。
 *
 * <p><b>实测 API（smile-core 4.1.0，已用 javap 确认）</b>：
 * <pre>
 * static double[][] UMAP.of(double[][] data, int k, int d, int epochs, double learningRate,
 *                           double minDist, double spread, int negativeSamples,
 *                           double repulsionStrength, double localConnectivity)
 * static PCA PCA.fit(double[][] data);   PCA.getProjection(int d).apply(double[][])   // 同在 smile-core
 * </pre>
 *
 * <p><b>⚠️ UMAP 没有随机种子参数</b>：Smile 4.1.0 的 {@code UMAP.of} 不暴露 randomState，
 * 因此本项目的降维结果<b>不可复现</b>。配置里的 {@code random-state} 仅为兼容配置表而保留，
 * 实际不生效；测试与文档中<b>不得</b>把「UMAP 结果可复现」作为断言。
 *
 * <h3>两条硬约束（docs/00-environment-facts.md 第 10 节；Lead 实测 + developer 本机用编译产物复核）</h3>
 * <ol>
 *   <li><b>GMM 绝对不能吃原始 1536 维向量。</b>
 *       {@code MultivariateGaussianMixture.fit(k, raw1536, true/false)} 在 n=11/20/40/80 上
 *       <b>一律</b>抛 {@code ArithmeticException: LAPACK POTRF error code: -4/11/20/...} ——
 *       1536 维下协方差矩阵在 n 小时严重奇异，Cholesky 分解必然失败，{@code diagonal=true} 也救不了。
 *       本机复核（n=11 / n=20，两个 diagonal 取值）：4/4 全部抛异常。
 *       → <b>「降维后再聚类」是硬性要求，不是优化。</b></li>
 *   <li><b>UMAP 的目标维度必须满足严格不等式 {@code 2d + 3 < n}</b>：
 *       UMAP 内部谱初始化需要 {@code NEV = 2d + 3} 个特征值，不满足即抛
 *       {@code IllegalArgumentException: Invalid NEV parameter k}。
 *       实测 n=11：d=2 ✅、d=3 ✅、<b>d=4 ❌</b>（2*4+3=11，不满足严格小于）、d=5/10 ❌。
 *       {@code nNeighbors} 还必须 ≤ n-1，否则 {@code ArrayIndexOutOfBoundsException}。</li>
 * </ol>
 *
 * <p><b>因此本类的契约是：要么返回一个 GMM 吃得下的低维矩阵，要么返回 {@code null}</b>
 * （由上层 {@link ClusterPipeline} 退化成「整层合并成一个父节点」）。
 * <b>绝不返回原始 1536 维矩阵</b> —— 那等于必然退化成单簇、建出一棵没有层次的树。
 */
@Slf4j
public final class UmapReducer {

    /** GMM 在点数少时能安全处理的维度上限（再高协方差矩阵就接近奇异）。 */
    public static final int GMM_SAFE_DIM_CAP = 8;

    private UmapReducer() {
    }

    /**
     * 样本太少时不值得降维：kNN 图会退化，且 {@code 2d+3 < n} 在 n ≤ 7 时对 d ≥ 2 恒不成立，
     * UMAP 必然抛异常（n=8 时 d=2 才恰好可用）。
     */
    public static boolean shouldReduce(int sampleCount, int nNeighbors) {
        return sampleCount >= 8 && sampleCount > nNeighbors + 1;
    }

    /**
     * 计算安全的目标维度；返回 0 表示「当前样本量下无法安全降维」。
     *
     * <p>两个约束取交集：
     * <ul>
     *   <li>UMAP 的严格限制 {@code 2d + 3 < n} → {@code d ≤ (n-4)/2}；</li>
     *   <li>GMM 的协方差可解性 → {@code d ≤ max(2, min(8, n/3))}（每簇平均只有 n/k 个点）。</li>
     * </ul>
     * n=11 → 3；n=8 → 2；n ≤ 7 → 0。
     */
    public static int safeTargetDim(int sampleCount, int configuredDim) {
        if (sampleCount <= 7) {
            return 0;
        }
        int byUmap = (sampleCount - 4) / 2;
        int byGmm = Math.max(2, Math.min(GMM_SAFE_DIM_CAP, sampleCount / 3));
        int max = Math.min(byUmap, byGmm);
        if (max < 2) {
            return 0;
        }
        return Math.min(Math.max(2, configuredDim), max);
    }

    /** 防御性检查：该矩阵是否可以直接喂 GMM。 */
    public static boolean gmmSafe(double[][] matrix) {
        if (matrix == null || matrix.length == 0 || matrix[0].length == 0) {
            return false;
        }
        int dim = matrix[0].length;
        return dim <= GMM_SAFE_DIM_CAP && dim < matrix.length;
    }

    /**
     * UMAP 降维（不抛异常）。
     *
     * <p>维度按 {@code 2d+3 < n} 与 GMM 安全性夹紧；首次失败会退一步试 {@code d=2}。
     *
     * <p><b>降级绝不能回退到原始高维向量</b>：1536 维下协方差矩阵在样本少时严重奇异，
     * GMM 对任何 k 都会抛 {@code ArithmeticException}（LAPACK POTRF），最终退化成单簇 → 整棵树没有层次。
     * 因此这里失败返回 {@code null}，由 {@link #reduce} 转交给 PCA 兜底。
     */
    public static double[][] tryUmap(double[][] x, int nNeighbors, int nComponents, int epochs,
                                     double learningRate, double minDist, double spread,
                                     int negativeSamples, double repulsionStrength, double localConnectivity) {
        if (x == null || x.length < 3) {
            return null;
        }
        int n = x.length;
        int k = clamp(nNeighbors, 2, Math.min(100, n - 1));
        int maxDim = safeTargetDim(n, nComponents);
        if (maxDim < 2) {
            log.debug("n={} 太小，UMAP 无可用目标维度（需 2d+3<n 且 d≥2）", n);
            return null;
        }
        int ep = Math.max(10, epochs);
        int dim = maxDim;
        try {
            return UMAP.of(x, k, dim, ep, learningRate, minDist, spread,
                    negativeSamples, repulsionStrength, localConnectivity);
        } catch (Throwable t) {
            log.warn("UMAP 降维失败(n={} k={} d={})：{}，回退尝试 d=2", n, k, dim, t.toString());
        }
        if (dim > 2) {
            try {
                return UMAP.of(x, k, 2, ep, learningRate, minDist, spread,
                        negativeSamples, repulsionStrength, localConnectivity);
            } catch (Throwable t) {
                log.warn("UMAP d=2 仍失败(n={} k={})：{}", n, k, t.toString());
            }
        }
        return null;
    }

    /**
     * PCA 兜底降维（确定性、无随机性；比 UMAP 慢但更稳）。失败返回 {@code null}。
     * 实测 11×1536 → 2 维约 430ms，可接受。PCA 结果喂 GMM 实测正常（n=11、k=3 → bic=-73.5）。
     */
    public static double[][] tryPca(double[][] x, int dim) {
        if (x == null || x.length <= 2) {
            return null;
        }
        int n = x.length;
        int d = Math.min(dim, Math.min(n - 1, Math.max(2, x[0].length)));
        if (d < 2) {
            return null;
        }
        try {
            PCA pca = PCA.fit(x);
            double[][] y = pca.getProjection(d).apply(x);
            if (y == null || y.length != n || y[0].length < 1) {
                return null;
            }
            return y;
        } catch (Throwable t) {
            log.warn("PCA 兜底降维失败(n={} d={})：{}", n, d, t.toString());
            return null;
        }
    }

    /**
     * 降维总入口：UMAP → PCA → {@code null}（交给上层合并成单簇）。<b>绝不返回原始高维矩阵。</b>
     *
     * @return 低维矩阵；{@code null} 表示当前样本量下无法安全降维
     */
    public static double[][] reduce(double[][] x, int nNeighbors, int nComponents, int epochs,
                                    double learningRate, double minDist, double spread,
                                    int negativeSamples, double repulsionStrength, double localConnectivity) {
        if (x == null || x.length < 3) {
            return null;
        }
        int dim = safeTargetDim(x.length, nComponents);
        if (dim < 2) {
            return null;
        }
        double[][] y = tryUmap(x, nNeighbors, nComponents, epochs, learningRate, minDist, spread,
                negativeSamples, repulsionStrength, localConnectivity);
        if (y != null && gmmSafe(y)) {
            return y;
        }
        double[][] pca = tryPca(x, dim);
        if (pca != null && gmmSafe(pca)) {
            log.info("UMAP 不可用，改用 PCA 降到 {} 维（n={}）", pca[0].length, x.length);
            return pca;
        }
        log.warn("UMAP 与 PCA 都无法得到 GMM 可用的低维表示（n={} dim={}），交上层合并为单簇", x.length, x[0].length);
        return null;
    }

    private static int clamp(int v, int lo, int hi) {
        if (hi < lo) {
            return lo;
        }
        return Math.max(lo, Math.min(hi, v));
    }
}
