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
 * <p><b>⚠️ BIC 方向（关键，曾长期搞反）</b>：Smile 的 {@code MultivariateMixture.bic(data)} 返回的是
 * <pre>
 *   logLikelihood - 0.5 * length() * Math.log(n)          // MultivariateMixture.java:213（已读源码）
 * </pre>
 * 而标准 BIC 是 {@code -2*logL + p*ln(n)}。两者相差 <b>−½ 倍</b>：
 * <pre>
 *   Smile.bic == -0.5 * 标准BIC      ⇒      Smile 的 bic() <b>越大越好，必须取 argmax</b>
 * </pre>
 * 改造前这里写的是 {@code r.bic() < best.bic()}（取最小值），方向正好反了 ——
 * 于是每一层都选到「标准 BIC 最差」的 k，{{@code k=2}} 这类最小簇数几乎是必然结果。
 * 这正是用户 VM 上「L0=313 → L1 只有 3 块」的主因之一（Lead 于 2026-09-18 定位）。
 * {@code length()} 本身没问题：它返回 {@code (k-1) + Σ 分量.length()}，是真实自由参数数
 * （{@code MultivariateMixture.java:180-187}），错的是符号约定，不是参数计数。
 *
 * <p><b>注意：本项目生产路径已不再走本类</b>（建树现在用
 * {@link DeterministicGmm}/{@link RaptorClusterer}，那里自算标准 BIC 并取 argmin）。
 * 本类保留为对照实验与历史兼容用途，但方向必须是对的，否则后人复制粘贴会再踩一次。
 *
 * <p><b>簇数上限的实现方式</b>：在 {@code [kMin, kMax]} 区间内对每个 k 调 {@code fit(k, x)} 并比较
 * {@code bic(x)}，取 Smile bic <b>最大</b>的 k（等价于标准 BIC 最小）。
 * Smile 4.1.0 没有 {@code maxComponents} 这类参数。
 *
 * <p><b>退化保护</b>：样本数 &lt; 3、kMax &lt; kMin、全部 k 拟合失败（协方差奇异等）时，
 * 一律降级为「单个簇」，不抛异常，保证建树递归一定能收敛（架构 5.1 的 {@code nClusters <= 1} 分支）。
 *
 * <h3>⚠️ 已废弃：生产路径不再使用本类</h3>
 * 本类已被 {@link DeterministicGmm} + {@link RaptorClusterer} 取代，三条理由都是实测出来的：
 * <ol>
 *   <li><b>不可复现</b>：Smile 4.1.0 的 {@code MultivariateGaussianMixture.fit} 没有随机种子参数，
 *       {@code MathEx.setSeed} 实测对它无效；同一份 313×1536 真实 embedding 连跑 10 次，
 *       选出的 k 在 2~12 之间跳变（建树结果因此不可复现）；</li>
 *   <li><b>BIC 符号约定易错</b>：Smile 的 {@code bic()} 是 {@code -BIC/2}（越大越好），
 *       与标准 BIC（越小越好）相反 —— 本类历史上就在这里取错了方向；</li>
 *   <li><b>没有 reg_covar 正则</b>：小样本 + 多分量时协方差奇异，直接刷
 *       {@code LAPACK POTRF error code: 1}（实测）。</li>
 * </ol>
 * <b>当前状态</b>：{@code src/main} 中已无任何调用点（{@code ClusterPipeline} 走
 * {@link RaptorClusterer} → {@link DeterministicGmm}）。<b>新代码不要再用它</b>；
 * 保留仅为对照实验与历史参考，其 BIC 方向已修正为 argmax 并有守卫测试防止回退。
 *
 * @deprecated 用 {@link RaptorClusterer}（官方两级聚类）+ {@link DeterministicGmm}（确定性 GMM）替代。
 */
@Deprecated(since = "2026-09-18", forRemoval = false)
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
            // ⚠️ Smile 的 bic() = -BIC/2，越大越好 → argmax（详见类注释；这里曾是 argmin，方向反了）
            if (best == null || r.bic() > best.bic()) {
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
