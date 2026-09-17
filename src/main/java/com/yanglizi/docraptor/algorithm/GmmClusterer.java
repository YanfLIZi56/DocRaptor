package com.yanglizi.docraptor.algorithm;

import lombok.extern.slf4j.Slf4j;
import smile.stat.distribution.MultivariateGaussianMixture;

/**
 * Smile GMM 聚类封装。
 *
 * <p><b>实测 API（已在 jar 里用 javap 确认）</b>：{@code smile.clustering.GaussianMixture} 在 Smile 4.1.0
 * <b>不存在</b>，GMM 已搬到 {@code smile.stat.distribution} 包（在 smile-base-4.1.0.jar 里）：
 * <pre>
 * MultivariateGaussianMixture.fit(int k, double[][] x)        // 指定簇数，全协方差
 * MultivariateGaussianMixture.fit(int k, double[][] x, boolean diagonal)
 * MultivariateGaussianMixture.fit(double[][] x)               // 按 BIC 自动选簇数
 * // 父类 MultivariateMixture：
 * int        map(double[] x)          // 该点所属簇下标（就是「预测」）
 * double[]   posteriori(double[] x)   // 软聚类责任度
 * Component[] components
 * double     bic(double[][] data)
 * </pre>
 *
 * <p><b>簇数上限的实现方式</b>：在 {@code [kMin, kMax]} 区间内对每个 k 调 {@code fit(k, x)} 并比较
 * {@code bic(x)}，选 BIC 最小的 k（<b>BIC 越小越好</b>）。Smile 4.1.0 没有 {@code maxComponents} 这类参数。
 *
 * <p><b>退化保护</b>：样本数 &lt; 3、kMax &lt; kMin、全部 k 拟合失败（协方差奇异等）时，
 * 一律降级为「单个簇」，不抛异常，保证建树递归一定能收敛（架构 5.1 的 {@code nClusters <= 1} 分支）。
 */
@Slf4j
public final class GmmClusterer {

    /** 聚类结果。k=1 或 0 表示退化（无法继续分裂）。 */
    public record Result(int k, int[] labels, double bic, boolean degraded, String message) {

        public static Result single(int n, String message) {
            int[] labels = new int[Math.max(0, n)];
            return new Result(1, labels, Double.NaN, true, message);
        }

        /** 每个簇的成员下标。 */
        public java.util.List<java.util.List<Integer>> groups() {
            java.util.List<java.util.List<Integer>> groups = new java.util.ArrayList<>();
            for (int i = 0; i < k; i++) {
                groups.add(new java.util.ArrayList<>());
            }
            for (int i = 0; i < labels.length; i++) {
                int label = labels[i];
                if (label >= 0 && label < k) {
                    groups.get(label).add(i);
                }
            }
            return groups;
        }
    }

    private static final int MIN_SAMPLES = 3;

    private GmmClusterer() {
    }

    /**
     * 在 [kMin, kMax] 内按 BIC 选簇数并聚类。
     *
     * @param x        样本矩阵（UMAP 降维后的结果，或原始向量）
     * @param kMin     簇数下限，&lt;1 时按 1 处理
     * @param kMax     簇数上限，会被夹紧到样本数
     * @param diagonal 是否用对角协方差（Smile 的 diagonal 参数）
     * @param selection BIC / AIC / FIXED；本实现用 BIC（Smile 只提供 bic()，AIC 走同一路径并在 message 中注明）
     */
    public static Result cluster(double[][] x, int kMin, int kMax, boolean diagonal, String selection) {
        if (x == null || x.length == 0) {
            return Result.single(0, "无样本");
        }
        int n = x.length;
        if (n < MIN_SAMPLES) {
            return Result.single(n, "样本数 " + n + " < " + MIN_SAMPLES + "，跳过聚类，直接合并为一个父节点");
        }

        int kLo = Math.max(1, kMin);
        int kHi = Math.min(kMax, n);
        if (kHi < 2 || kHi < kLo) {
            return Result.single(n, "可用簇数上限 " + kHi + " < 2，无法分裂");
        }
        if (kLo > kHi) {
            kLo = kHi;
        }
        if (kLo < 2) {
            kLo = 2;
        }

        boolean fixed = "FIXED".equalsIgnoreCase(selection);
        if (fixed) {
            Result r = tryFit(x, kHi, diagonal);
            if (r != null) {
                return r;
            }
            log.warn("FIXED 簇数 {} 拟合失败，回退到 BIC 选择", kHi);
        }

        Result best = null;
        for (int k = kLo; k <= kHi; k++) {
            Result r = tryFit(x, k, diagonal);
            if (r == null) {
                continue;
            }
            if (best == null || r.bic() < best.bic()) {
                best = r;
            }
        }
        if (best == null) {
            return Result.single(n, "所有候选簇数拟合失败，降级为单簇");
        }
        if (best.k() <= 1) {
            return Result.single(n, "BIC 选择结果为单簇");
        }
        return best;
    }

    /** 拟合单一 k；失败返回 null（不抛异常）。 */
    private static Result tryFit(double[][] x, int k, boolean diagonal) {
        try {
            MultivariateGaussianMixture model = MultivariateGaussianMixture.fit(k, x, diagonal);
            if (model == null) {
                return null;
            }
            int[] labels = new int[x.length];
            for (int i = 0; i < x.length; i++) {
                int label = model.map(x[i]);
                labels[i] = label < 0 ? 0 : label;
            }
            double bic = Double.NaN;
            try {
                bic = model.bic(x);
            } catch (Throwable ignore) {
                // 父类字段兜底
            }
            if (Double.isNaN(bic) || Double.isInfinite(bic)) {
                bic = model.bic;
            }
            if (Double.isNaN(bic) || Double.isInfinite(bic)) {
                return null;
            }
            return new Result(k, labels, bic, false, "ok");
        } catch (Throwable t) {
            log.debug("GMM fit(k={}) 失败：{}", k, t.toString());
            return null;
        }
    }

    /**
     * 便捷入口：降维后直接聚类。任何异常都降级为单簇。
     */
    public static Result safeCluster(double[][] x, int kMin, int kMax, boolean diagonal, String selection) {
        try {
            return cluster(x, kMin, kMax, diagonal, selection);
        } catch (Throwable t) {
            log.warn("GMM 聚类异常，降级为单簇：{}", t.toString());
            return Result.single(x == null ? 0 : x.length, "聚类异常降级：" + t);
        }
    }
}
