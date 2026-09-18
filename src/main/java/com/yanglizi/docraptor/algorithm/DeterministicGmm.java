package com.yanglizi.docraptor.algorithm;

import lombok.extern.slf4j.Slf4j;

import java.util.Arrays;
import java.util.Random;

/**
 * <b>确定性高斯混合模型</b>（GMM）：KMeans 固定初值 + 自写 EM + BIC 选簇数。
 *
 * <h3>为什么不用 Smile 的 GMM</h3>
 * 官方 RAPTOR（{@code cluster_utils.py:49-69}）靠 {@code sklearn.GaussianMixture(..., random_state=SEED)} 拿可复现性；
 * 而 Smile 4.1.0 的 {@code MultivariateGaussianMixture.fit(...)} <b>没有</b>随机种子参数。
 * 实测（Lead 与 developer 独立复核）：
 * <ul>
 *   <li>{@code MathEx.setSeed(42)} 对 Smile 的 GMM <b>完全无效</b>；</li>
 *   <li>同一份 313×1536 真实 embedding，连跑 10 次，选出的 k 在 <b>2~12</b> 之间跳变；</li>
 *   <li>把 Smile GMM 换成 313 个向量的<b>前 2 维原始特征</b>（完全确定、无任何降维随机性）后，k 仍在 10~12 跳变
 *       —— 证明随机性来自 <b>GMM 自身</b>，与降维无关；</li>
 *   <li>Smile 的 GMM 没有 {@code reg_covar} 之类的正则，小样本 + 多分量时协方差矩阵奇异，
 *       直接刷 {@code LAPACK POTRF error code: 1}（实测）。</li>
 * </ul>
 *
 * <p>本类的契约：<b>同输入 + 同参数 ⇒ 逐位相同的输出</b>（BIC / labels / responsibilities / means / covariances 全部）。
 *
 * <h3>⚠️ BIC 的两套约定不可混用（本项目踩过大坑）</h3>
 * 本类<b>自算标准 BIC</b>，并且<b>取最小值</b>：
 * <pre>
 *   BIC = -2·logL + p·ln(n)        // p = 自由参数数，见 {@link #parameterCount}
 *   p = k·(d + d(d+1)/2) + (k-1)   // 全协方差（默认，官方 sklearn 默认）
 *   p = k·(2d) + (k-1)             // 对角协方差
 * </pre>
 * 而 Smile 的 {@code MultivariateMixture.bic(data)}（{@code MultivariateMixture.java:213}，已读源码）返回的是
 * <pre>
 *   logLikelihood - 0.5 * length() * Math.log(n) == -0.5 * 标准BIC
 * </pre>
 * —— 与标准 BIC 相差 <b>−½ 倍</b>，所以 <b>Smile 的 {@code bic()} 越大越好（argmax）</b>。
 * 历史上 {@code GmmClusterer} 就是在这里取错了方向（argmin），导致每层都选到标准 BIC 最差的 k。
 * <b>两套约定绝对不能混用</b>：本类只认标准 BIC + argmin；要读 Smile 的 bic 就必须取 argmax。
 * {@code length()} 本身是对的（{@code (k-1) + Σ 分量.length()}），错的是符号约定，不是参数计数。
 *
 * <p>实现上保证：
 * <ul>
 *   <li>不使用 {@code ThreadLocalRandom} / {@code SecureRandom} / {@code parallelStream()}；</li>
 *   <li>唯一的随机源是 KMeans++ 初始化里的 {@code new java.util.Random(SEED)}，种子固定为官方的 224，
 *       且<b>每次 fit 都重新构造</b>（不共享实例的消耗顺序）；</li>
 *   <li>所有求和、遍历都按固定下标顺序进行，不依赖任何哈希容器顺序；</li>
 *   <li>EM 用 Cholesky 分解（自带 {@code reg_covar} 正则，矩阵必正定），分量坍缩时用<b>确定性</b>的重生策略恢复。</li>
 * </ul>
 *
 * <h3>协方差类型：默认全协方差（对齐官方）</h3>
 * 官方 {@code GaussianMixture(n_components=n, random_state=...) } 用的是 sklearn 的默认
 * {@code covariance_type='full'}。这里默认也是全协方差，原因是<b>实测</b>出来的：
 * 对角协方差的每个分量只有 {@code 2d} 个参数，在「样本数不大、维度 10 左右」的局部子集上，
 * BIC 会被「一个分量钉住一个点」的坍缩解骗过去 —— 该解的 log-likelihood 会变成<b>正数</b>
 * （对密度函数而言不可能），却仍然赢得 BIC，最终把 50 个点切成 50 个单点簇。
 * 全协方差每个分量有 {@code d + d(d+1)/2} 个参数（d=10 时 55 个，是对角的 2.75 倍），
 * 惩罚强度足以压住这种坍缩，且与官方一致。
 * 维度超过 {@link #FULL_COV_MAX_DIM} 时自动退回对角（避免 O(d³) 与高维奇异），
 * BIC 的自由度计数会<b>跟着实际用的协方差类型走</b>，不虚报。
 */
@Slf4j
public final class DeterministicGmm {

    /** 拟合配置。{@link #official()} 对齐 sklearn {@code GaussianMixture} 的默认值。 */
    public record Config(boolean fullCovariance, boolean standardize, int maxIterations,
                         double tolerance, double regularization) {

        public Config {
            maxIterations = Math.max(1, maxIterations);
            tolerance = tolerance > 0 ? tolerance : DEFAULT_TOLERANCE;
            regularization = regularization > 0 ? regularization : DEFAULT_VARIANCE_FLOOR;
        }

        /**
         * 官方配置：全协方差 + <b>不</b>做标准化 + {@code reg_covar=1e-6} + {@code max_iter=100} + {@code tol=1e-3}
         * —— 逐项对应 sklearn {@code GaussianMixture(n_components, random_state=224)} 的默认参数。
         *
         * <p>「不标准化」是刻意的：官方直接把降维结果喂给 GMM，sklearn 不会做 StandardScaler。
         * 实测标准化会把 313 块的《三体》从「全局 4 簇 / 合计 43 簇」改成「全局 3 簇 / 合计 66 簇」，
         * 两者都在合理区间，但为对齐官方默认取不标准化。
         */
        public static Config official() {
            return new Config(true, false, 100, 1.0e-3, 1.0e-6);
        }

        public Config withRegularization(double reg) {
            return new Config(fullCovariance, standardize, maxIterations, tolerance, reg);
        }

        public Config withFullCovariance(boolean full) {
            return new Config(full, standardize, maxIterations, tolerance, regularization);
        }

        public Config withStandardize(boolean std) {
            return new Config(fullCovariance, std, maxIterations, tolerance, regularization);
        }
    }

    /** sklearn 默认 {@code max_iter}。 */
    public static final int DEFAULT_MAX_ITERATIONS = 100;
    /** sklearn 默认 {@code tol}。 */
    public static final double DEFAULT_TOLERANCE = 1.0e-3;

    /**
     * 协方差正则项 {@code reg_covar}（sklearn 默认 1e-6）。
     *
     * <p>作用有二：(1) 保证协方差矩阵在分量成员数 {@code nk < d} 时依然<b>正定</b>，Cholesky 不会失败；
     * (2) 限制「分量坍缩到单点」时密度被无限抬高的数值病态。
     *
     * <p><b>注意</b>：真正压住坍缩的是<b>全协方差带来的参数惩罚</b>（每个分量 {@code d + d(d+1)/2} 个参数，
     * d=10 时 55 个，是对角 {@code 2d}=20 个的 2.75 倍），不是这个下限本身。
     * 实测（见交付报告）：把协方差换成对角后，即便把下限抬到 1e-3，局部子集依旧坍缩成 n-1 个单点簇；
     * 而全协方差 + 1e-6 就能选出 3~17 个正常大小的簇。
     */
    public static final double DEFAULT_VARIANCE_FLOOR = 1.0e-6;

    /** 维度不超过该值才用全协方差；否则退回对角（O(d³) 与高维奇异的保护）。 */
    public static final int FULL_COV_MAX_DIM = 32;

    /** KMeans++ 抽样种子（官方 {@code cluster_utils.py:21} 的 RANDOM_SEED = 224）。 */
    public static final long SEED = 224L;

    private static final double LOG_2PI = Math.log(2 * Math.PI);
    /** 分量权重低于该值视为坍缩，触发确定性重生。 */
    private static final double DEAD_WEIGHT = 1.0e-8;
    /** 单个分量最多被重生几次（防止反复重生打转）。 */
    private static final int MAX_REBIRTHS = 3;
    /** Cholesky 失败时把正则放大重试的次数。 */
    private static final int MAX_CHOL_RETRY = 3;

    private DeterministicGmm() {
    }

    /** 拟合结果。{@code responsibilities} 是完整的 n×k 概率矩阵（软聚类要用）。 */
    public static final class Model {
        private final double[][] responsibilities;   // n x k
        private final int[] labels;                  // argmax responsibilities
        private final double[][] means;              // k x d（原始尺度）
        private final double[][][] covariances;      // k x d x d（原始尺度）
        private final double[][] variances;          // k x d，协方差的对角线（原始尺度，便于取用）
        private final double[] weights;              // k
        private final double logLikelihood;
        private final double bic;
        private final int iterations;
        private final boolean converged;
        private final int rebuilds;
        private final boolean fullCovariance;
        private final int parameterCount;

        private Model(double[][] responsibilities, int[] labels, double[][] means,
                     double[][][] covariances, double[][] variances, double[] weights,
                     double logLikelihood, double bic, int iterations, boolean converged,
                     int rebuilds, boolean fullCovariance, int parameterCount) {
            this.responsibilities = responsibilities;
            this.labels = labels;
            this.means = means;
            this.covariances = covariances;
            this.variances = variances;
            this.weights = weights;
            this.logLikelihood = logLikelihood;
            this.bic = bic;
            this.iterations = iterations;
            this.converged = converged;
            this.rebuilds = rebuilds;
            this.fullCovariance = fullCovariance;
            this.parameterCount = parameterCount;
        }

        public double[][] responsibilities() {
            return responsibilities;
        }

        public int[] labels() {
            return labels;
        }

        public double[][] means() {
            return means;
        }

        /** 全协方差矩阵（原始尺度）；对角模式下非对角元素为 0。 */
        public double[][][] covariances() {
            return covariances;
        }

        /** 协方差的对角线（原始尺度）。 */
        public double[][] variances() {
            return variances;
        }

        public double[] weights() {
            return weights;
        }

        public double logLikelihood() {
            return logLikelihood;
        }

        public double bic() {
            return bic;
        }

        public int k() {
            return weights.length;
        }

        public int sampleCount() {
            return responsibilities.length;
        }

        public int iterations() {
            return iterations;
        }

        public boolean converged() {
            return converged;
        }

        public int rebuilds() {
            return rebuilds;
        }

        /** 实际使用的协方差类型是否为全协方差。 */
        public boolean fullCovariance() {
            return fullCovariance;
        }

        /** BIC 里使用的自由参数个数。 */
        public int parameterCount() {
            return parameterCount;
        }

        public String covarianceType() {
            return fullCovariance ? "full" : "diagonal";
        }

        /** 软聚类：返回第 i 个样本责任度 > threshold 的全部簇下标（升序）。 */
        public int[] softLabels(int i, double threshold) {
            int k = weights.length;
            int count = 0;
            for (int j = 0; j < k; j++) {
                if (responsibilities[i][j] > threshold) {
                    count++;
                }
            }
            int[] out = new int[count];
            int p = 0;
            for (int j = 0; j < k; j++) {
                if (responsibilities[i][j] > threshold) {
                    out[p++] = j;
                }
            }
            return out;
        }
    }

    /** BIC 选择结果（含每个候选 k 的 BIC，便于诊断与测试）。 */
    public record Selection(Model model, int[] kTried, double[] bics, int kSelected, String note) {
    }

    // ------------------------------------------------------------------
    // 对外入口

    /**
     * 在 {@code [kMin, kMax]} 上按 BIC 选簇数并返回最优模型（官方配置见 {@link Config#official()}）。
     *
     * <p>BIC 越小越好：{@code BIC = -2·logL + p·ln(n)}，自由度按实际协方差类型计
     * （全协方差 {@code p = k·(d + d(d+1)/2) + (k-1)}，对角 {@code p = k·2d + (k-1)}）。
     * 与官方 {@code get_optimal_clusters} 一致地在 {@code [1, min(maxClusters, n)]} 上扫满。
     */
    public static Selection selectByBic(double[][] x, int kMin, int kMax, Config cfg) {
        int n = x == null ? 0 : x.length;
        if (n == 0) {
            return new Selection(null, new int[0], new double[0], 0, "无样本");
        }
        int lo = Math.max(1, kMin);
        int hi = Math.min(Math.max(1, kMax), n);
        if (hi < lo) {
            lo = hi;
        }
        int[] tried = new int[hi - lo + 1];
        double[] bics = new double[tried.length];
        Model best = null;
        int bestK = 0;
        double bestBic = Double.POSITIVE_INFINITY;
        for (int k = lo; k <= hi; k++) {
            Model m = fit(x, k, cfg);
            int idx = k - lo;
            tried[idx] = k;
            bics[idx] = m.bic();
            if (Double.isFinite(bics[idx]) && bics[idx] < bestBic) {
                bestBic = bics[idx];
                bestK = k;
                best = m;
            }
        }
        if (best == null) {
            // 全部 k 都算不出有限 BIC（例如样本全同且方差退化）→ 退化成单分量
            best = fit(x, 1, cfg);
            bestK = 1;
            return new Selection(best, tried, bics, 1, "所有候选 k 的 BIC 都非有限，退化为单分量");
        }
        return new Selection(best, tried, bics, bestK, "ok");
    }

    /** 兼容重载：按官方配置拟合，仅覆盖正则下限（{@code reg_covar}）。 */
    public static Selection selectByBic(double[][] x, int kMin, int kMax,
                                        int maxIterations, double tolerance, double varianceFloor) {
        return selectByBic(x, kMin, kMax, new Config(true, false, maxIterations, tolerance, varianceFloor));
    }

    /** 兼容重载：按官方配置拟合，仅覆盖正则下限与协方差类型。 */
    public static Selection selectByBic(double[][] x, int kMin, int kMax, int maxIterations,
                                        double tolerance, double varianceFloor, boolean fullCovariance) {
        return selectByBic(x, kMin, kMax,
                new Config(fullCovariance, false, maxIterations, tolerance, varianceFloor));
    }

    /** 用固定簇数 k 拟合（官方配置：全协方差、不标准化、reg_covar=1e-6、max_iter=100、tol=1e-3）。 */
    public static Model fit(double[][] x, int k) {
        return fit(x, k, Config.official());
    }

    /** 兼容重载：官方配置 + 覆盖正则下限。 */
    public static Model fit(double[][] x, int k, int maxIterations, double tolerance, double varianceFloor) {
        return fit(x, k, new Config(true, false, maxIterations, tolerance, varianceFloor));
    }

    /** 兼容重载：官方配置 + 覆盖正则下限与协方差类型。 */
    public static Model fit(double[][] x, int k, int maxIterations, double tolerance,
                            double varianceFloor, boolean fullCovariance) {
        return fit(x, k, new Config(fullCovariance, false, maxIterations, tolerance, varianceFloor));
    }

    /**
     * 用固定簇数 k 拟合。任何情况下都不抛异常（退化情形返回可用的单分量模型）。
     *
     * <p>对外的 {@code means}/{@code covariances} 与输入同尺度；
     * {@code logLikelihood}/{@code bic} 在<b>拟合所用尺度</b>上计算（若开了标准化则相差一个与 k 无关的常数，
     * 不影响跨 k 比较）。
     */
    public static Model fit(double[][] x, int k, Config cfg) {
        int n = x.length;
        int d = x[0].length;
        int kk = Math.max(1, Math.min(k, n));
        double reg = cfg.regularization();
        int maxIter = cfg.maxIterations();
        double tol = cfg.tolerance();
        boolean full = cfg.fullCovariance() && d <= FULL_COV_MAX_DIM;

        // ---------- 0. 可选标准化（官方不做；开了之后 mu/sd 用于最后还原尺度）----------
        double[] muG = new double[d];
        double[] sdG = new double[d];
        Arrays.fill(sdG, 1.0);
        double[][] xs;
        if (cfg.standardize()) {
            computeMeanStd(x, muG, sdG);
            xs = standardize(x, muG, sdG);
        } else {
            xs = x;
        }

        // ---------- 1. 初值：确定性 KMeans（k-means++ 固定种子 + Lloyd）----------
        double[][] means = kmeans(xs, kk);
        double[][][] cov = new double[kk][d][d];
        double[][] chol = new double[kk][];
        double[] logDet = new double[kk];
        double[] weights = new double[kk];
        double[] globalVar = globalVariance(xs, reg);
        int[] hardCount = new int[kk];
        int[] label0 = new int[n];
        for (int i = 0; i < n; i++) {
            label0[i] = nearest(xs[i], means);
            hardCount[label0[i]]++;
        }
        for (int c = 0; c < kk; c++) {
            weights[c] = 1.0 / kk;   // 官方起点：pi_k = 1/k
            if (hardCount[c] <= 1) {
                // 成员太少，簇内协方差不可靠 → 用全局方差（对角）
                for (int j = 0; j < d; j++) {
                    cov[c][j][j] = globalVar[j];
                }
            } else {
                scatterOfClass(xs, label0, c, means[c], cov[c], full);
                for (int j = 0; j < d; j++) {
                    cov[c][j][j] += reg;
                }
            }
            // 对角模式：抹掉非对角项
            if (!full) {
                zeroOffDiagonal(cov[c]);
            }
            chol[c] = choleskyWithRetry(cov[c], globalVar, reg);
            logDet[c] = logDeterminant(chol[c]);
        }

        // ---------- 2. EM（全部在标准化尺度上）----------
        double[][] gamma = new double[n][kk];
        double[][] nu = new double[n][d];    // 复用的 (x-μ) 缓冲
        double[] z = new double[d];          // 复用的 Cholesky 前代缓冲
        double prevLogL = Double.NEGATIVE_INFINITY;
        double logL;
        boolean converged = false;
        int iter = 0;
        int[] rebuildCount = new int[kk];
        int totalRebuilds = 0;

        for (iter = 1; iter <= maxIter; iter++) {
            // E 步
            logL = eStep(xs, means, chol, logDet, weights, gamma, z);
            // M 步
            double[] nk = new double[kk];
            for (int i = 0; i < n; i++) {
                for (int c = 0; c < kk; c++) {
                    nk[c] += gamma[i][c];
                }
            }
            for (int c = 0; c < kk; c++) {
                if (nk[c] < DEAD_WEIGHT && rebuildCount[c] < MAX_REBIRTHS) {
                    // 分量坍缩：确定性重生 —— 取「最大责任度最小」的样本作为新中心
                    int worst = 0;
                    double worstVal = Double.POSITIVE_INFINITY;
                    for (int i = 0; i < n; i++) {
                        double mx = 0;
                        for (int cc = 0; cc < kk; cc++) {
                            mx = Math.max(mx, gamma[i][cc]);
                        }
                        if (mx < worstVal) {
                            worstVal = mx;
                            worst = i;
                        }
                    }
                    System.arraycopy(xs[worst], 0, means[c], 0, d);
                    clearMatrix(cov[c]);
                    for (int j = 0; j < d; j++) {
                        cov[c][j][j] = globalVar[j];
                    }
                    weights[c] = 1.0 / kk;
                    nk[c] = 0;
                    rebuildCount[c]++;
                    totalRebuilds++;
                } else if (nk[c] <= 0) {
                    weights[c] = 0;
                    clearMatrix(cov[c]);
                    chol[c] = choleskyWithRetry(cov[c], globalVar, reg);
                    logDet[c] = logDeterminant(chol[c]);
                    continue;
                } else {
                    weights[c] = nk[c] / n;
                    // 均值
                    for (int j = 0; j < d; j++) {
                        double sum = 0;
                        for (int i = 0; i < n; i++) {
                            sum += gamma[i][c] * xs[i][j];
                        }
                        means[c][j] = sum / nk[c];
                    }
                    // 协方差（加权散度 / nk + reg）
                    clearMatrix(cov[c]);
                    for (int i = 0; i < n; i++) {
                        double g = gamma[i][c];
                        if (g <= 0) {
                            continue;
                        }
                        double[] xi = xs[i];
                        double[] mu = means[c];
                        double[] nuI = nu[i];
                        for (int j = 0; j < d; j++) {
                            nuI[j] = xi[j] - mu[j];
                        }
                        if (full) {
                            for (int j = 0; j < d; j++) {
                                double gj = g * nuI[j];
                                for (int l = 0; l <= j; l++) {
                                    cov[c][j][l] += gj * nuI[l];
                                }
                            }
                        } else {
                            for (int j = 0; j < d; j++) {
                                cov[c][j][j] += g * nuI[j] * nuI[j];
                            }
                        }
                    }
                    double invNk = 1.0 / nk[c];
                    for (int j = 0; j < d; j++) {
                        for (int l = 0; l <= j; l++) {
                            cov[c][j][l] *= invNk;
                        }
                    }
                    for (int j = 0; j < d; j++) {
                        cov[c][j][j] += reg;
                    }
                    if (full) {
                        mirrorSymmetric(cov[c]);
                    }
                }
                chol[c] = choleskyWithRetry(cov[c], globalVar, reg);
                logDet[c] = logDeterminant(chol[c]);
            }
            // 权重归一（重生/剔除后可能不再和为 1）
            double wsum = 0;
            for (double w : weights) {
                wsum += w;
            }
            if (wsum <= 0) {
                Arrays.fill(weights, 1.0 / kk);
            } else {
                for (int c = 0; c < kk; c++) {
                    weights[c] /= wsum;
                }
            }

            if (Double.isFinite(prevLogL) && Math.abs(logL - prevLogL) <= tol * Math.max(1.0, Math.abs(prevLogL))) {
                converged = true;
                break;
            }
            prevLogL = logL;
        }

        // 用最终参数重算一次 responsibilities，保证 gamma 与参数自洽
        double finalLogL = eStep(xs, means, chol, logDet, weights, gamma, z);
        int[] hard = new int[n];
        for (int i = 0; i < n; i++) {
            int best = 0;
            for (int c = 1; c < kk; c++) {
                if (gamma[i][c] > gamma[i][best]) {
                    best = c;
                }
            }
            hard[i] = best;
        }

        // ---------- 3. 参数还原到原始尺度（responsibilities 与尺度无关，不变）----------
        double[][] meansOrig = new double[kk][d];
        double[][][] covOrig = new double[kk][d][d];
        double[][] varOrig = new double[kk][d];
        for (int c = 0; c < kk; c++) {
            for (int j = 0; j < d; j++) {
                meansOrig[c][j] = means[c][j] * sdG[j] + muG[j];
                for (int l = 0; l < d; l++) {
                    covOrig[c][j][l] = cov[c][j][l] * sdG[j] * sdG[l];
                }
                varOrig[c][j] = Math.max(cov[c][j][j] * sdG[j] * sdG[j], Double.MIN_NORMAL);
            }
        }

        int p = parameterCount(kk, d, full);
        double bic = -2.0 * finalLogL + p * Math.log(Math.max(2, n));
        return new Model(gamma, hard, meansOrig, covOrig, varOrig, weights, finalLogL, bic,
                Math.min(iter, maxIter), converged, totalRebuilds, full, p);
    }

    /** BIC 的自由参数个数：全协方差 {@code k(d + d(d+1)/2) + (k-1)}，对角 {@code 2kd + (k-1)}。 */
    static int parameterCount(int k, int d, boolean full) {
        long perComponent = full ? (long) d + (long) d * (d + 1) / 2 : 2L * d;
        return (int) Math.min(Integer.MAX_VALUE, perComponent * k + (k - 1));
    }

    // ------------------------------------------------------------------
    // EM 数值内核

    /** E 步：写满 gamma，并返回 log-likelihood（log 空间 + log-sum-exp，数值稳定）。 */
    private static double eStep(double[][] x, double[][] means, double[][] chol, double[] logDet,
                                double[] weights, double[][] gamma, double[] z) {
        int n = x.length;
        int k = means.length;
        int d = x[0].length;
        double total = 0;
        double[] logP = new double[k];
        for (int i = 0; i < n; i++) {
            double maxLog = Double.NEGATIVE_INFINITY;
            for (int c = 0; c < k; c++) {
                if (weights[c] <= 0) {
                    logP[c] = Double.NEGATIVE_INFINITY;
                    continue;
                }
                double quad = mahalanobis(x[i], means[c], chol[c], z);
                if (Double.isNaN(quad) || Double.isInfinite(quad)) {
                    logP[c] = Double.NEGATIVE_INFINITY;
                    continue;
                }
                logP[c] = Math.log(weights[c]) - 0.5 * (d * LOG_2PI + logDet[c] + quad);
                if (logP[c] > maxLog) {
                    maxLog = logP[c];
                }
            }
            double sum = 0;
            for (int c = 0; c < k; c++) {
                if (logP[c] > Double.NEGATIVE_INFINITY) {
                    sum += Math.exp(logP[c] - maxLog);
                }
            }
            if (!(sum > 0) || !Double.isFinite(maxLog)) {
                // 极端退化：所有分量都不可用 → 均匀责任度，避免 NaN 传播
                Arrays.fill(gamma[i], 1.0 / k);
                continue;
            }
            double logNorm = maxLog + Math.log(sum);
            for (int c = 0; c < k; c++) {
                gamma[i][c] = (logP[c] > Double.NEGATIVE_INFINITY) ? Math.exp(logP[c] - logNorm) : 0.0;
            }
            total += logNorm;
        }
        return total;
    }

    /** 马氏距离平方：解 {@code L z = (x-μ)} 后取 {@code |z|²}（前代，O(d²)）。 */
    private static double mahalanobis(double[] x, double[] mu, double[] L, double[] z) {
        int d = x.length;
        for (int j = 0; j < d; j++) {
            double s = x[j] - mu[j];
            for (int l = 0; l < j; l++) {
                s -= L[j * d + l] * z[l];
            }
            z[j] = s / L[j * d + j];
        }
        double quad = 0;
        for (int j = 0; j < d; j++) {
            quad += z[j] * z[j];
        }
        return quad;
    }

    /**
     * Cholesky 分解（下三角，行主序展平）。失败（非正定）时把正则<b>确定性地</b>逐步放大重试；
     * 仍失败则退化为「全局方差的对角阵」，保证永远返回一个可用的分解。
     */
    private static double[] choleskyWithRetry(double[][] a, double[] globalVar, double reg) {
        int d = a.length;
        double bump = 0.0;   // 第一次按原样分解（M 步已经把 reg 加在对角线上了）
        for (int attempt = 0; attempt <= MAX_CHOL_RETRY; attempt++) {
            double[] L = cholesky(a, bump);
            if (L != null) {
                return L;
            }
            bump = bump <= 0 ? Math.max(reg, DEFAULT_VARIANCE_FLOOR) : bump * 10.0;
        }
        double[] L = new double[d * d];
        for (int j = 0; j < d; j++) {
            L[j * d + j] = Math.sqrt(Math.max(globalVar[j], DEFAULT_VARIANCE_FLOOR));
        }
        return L;
    }

    /** 纯 Cholesky：成功返回展平的下三角 L，非正定返回 null。 */
    private static double[] cholesky(double[][] a, double bump) {
        int d = a.length;
        double[] L = new double[d * d];
        for (int j = 0; j < d; j++) {
            for (int l = 0; l <= j; l++) {
                double s = a[j][l];
                if (j == l) {
                    s += bump;
                }
                for (int t = 0; t < l; t++) {
                    s -= L[j * d + t] * L[l * d + t];
                }
                if (j == l) {
                    if (!(s > 0) || !Double.isFinite(s)) {
                        return null;
                    }
                    L[j * d + j] = Math.sqrt(s);
                } else {
                    L[j * d + l] = s / L[l * d + l];
                }
            }
        }
        return L;
    }

    /** {@code log|Σ|} = 2·Σ log L_jj。 */
    private static double logDeterminant(double[] L) {
        int d = (int) Math.round(Math.sqrt(L.length));
        double s = 0;
        for (int j = 0; j < d; j++) {
            s += Math.log(L[j * d + j]);
        }
        return 2.0 * s;
    }

    private static void clearMatrix(double[][] m) {
        for (double[] row : m) {
            Arrays.fill(row, 0.0);
        }
    }

    private static void zeroOffDiagonal(double[][] m) {
        int d = m.length;
        for (int j = 0; j < d; j++) {
            for (int l = 0; l < d; l++) {
                if (j != l) {
                    m[j][l] = 0.0;
                }
            }
        }
    }

    private static void mirrorSymmetric(double[][] m) {
        int d = m.length;
        for (int j = 0; j < d; j++) {
            for (int l = 0; l < j; l++) {
                m[l][j] = m[j][l];
            }
        }
    }

    /** 给定硬分配下标，算某簇的散度矩阵（除以成员数）；{@code full=false} 时只填对角线（方差）。 */
    private static void scatterOfClass(double[][] x, int[] labels, int c, double[] mean,
                                       double[][] out, boolean full) {
        clearMatrix(out);
        int d = mean.length;
        int count = 0;
        for (int i = 0; i < labels.length; i++) {
            if (labels[i] != c) {
                continue;
            }
            count++;
            for (int j = 0; j < d; j++) {
                double nuJ = x[i][j] - mean[j];
                if (full) {
                    for (int l = 0; l <= j; l++) {
                        out[j][l] += nuJ * (x[i][l] - mean[l]);
                    }
                } else {
                    out[j][j] += nuJ * nuJ;
                }
            }
        }
        if (count > 0) {
            for (int j = 0; j < d; j++) {
                for (int l = 0; l <= j; l++) {
                    out[j][l] /= count;
                }
            }
        }
        if (full) {
            mirrorSymmetric(out);
        }
    }

    /** 逐维均值与标准差（标准差下限 1e-8，全常量维度退化为 0 列）。 */
    private static void computeMeanStd(double[][] x, double[] mu, double[] sd) {
        int n = x.length;
        int d = x[0].length;
        for (double[] row : x) {
            for (int j = 0; j < d; j++) {
                mu[j] += row[j];
            }
        }
        for (int j = 0; j < d; j++) {
            mu[j] /= Math.max(1, n);
        }
        for (double[] row : x) {
            for (int j = 0; j < d; j++) {
                double t = row[j] - mu[j];
                sd[j] += t * t;
            }
        }
        for (int j = 0; j < d; j++) {
            sd[j] = Math.max(Math.sqrt(sd[j] / Math.max(1, n)), 1.0e-8);
        }
    }

    private static double[][] standardize(double[][] x, double[] mu, double[] sd) {
        int n = x.length;
        int d = x[0].length;
        double[][] out = new double[n][d];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < d; j++) {
                out[i][j] = (x[i][j] - mu[j]) / sd[j];
            }
        }
        return out;
    }

    /** 逐维全局方差（至少 {@code floor}）。注意：输入应当是<b>已标准化</b>的矩阵。 */
    private static double[] globalVariance(double[][] x, double floor) {
        int n = x.length;
        int d = x[0].length;
        double[] mu = new double[d];
        for (double[] row : x) {
            for (int j = 0; j < d; j++) {
                mu[j] += row[j];
            }
        }
        for (int j = 0; j < d; j++) {
            mu[j] /= Math.max(1, n);
        }
        double[] var = new double[d];
        for (double[] row : x) {
            for (int j = 0; j < d; j++) {
                double t = row[j] - mu[j];
                var[j] += t * t;
            }
        }
        for (int j = 0; j < d; j++) {
            var[j] = Math.max(var[j] / Math.max(1, n), floor);
        }
        return var;
    }

    /**
     * 完全确定性的 KMeans：k-means++ 初始化（{@code new Random(224)}，每次重新构造）+ Lloyd 迭代。
     * 空簇保留原质心（不置 NaN），保证确定性。
     */
    static double[][] kmeans(double[][] x, int k) {
        int n = x.length;
        int d = x[0].length;
        double[][] cent = new double[k][];
        if (k >= n) {
            for (int i = 0; i < k; i++) {
                cent[i] = x[Math.min(i, n - 1)].clone();
            }
            return cent;
        }

        Random rnd = new Random(SEED);
        double[] best = new double[n];
        Arrays.fill(best, Double.POSITIVE_INFINITY);
        for (int c = 0; c < k; c++) {
            int pick;
            if (c == 0) {
                pick = rnd.nextInt(n);
            } else {
                double total = 0;
                for (int i = 0; i < n; i++) {
                    total += best[i];
                }
                if (!(total > 0)) {
                    pick = (c * 7919) % n;   // 全部点到已有质心距离为 0 → 用确定性的下标兜底
                } else {
                    double target = rnd.nextDouble() * total;
                    double acc = 0;
                    pick = n - 1;
                    for (int i = 0; i < n; i++) {
                        acc += best[i];
                        if (acc >= target) {
                            pick = i;
                            break;
                        }
                    }
                }
            }
            cent[c] = x[pick].clone();
            for (int i = 0; i < n; i++) {
                double dd = sqDist(x[i], cent[c]);
                if (dd < best[i]) {
                    best[i] = dd;
                }
            }
        }

        int[] assign = new int[n];
        Arrays.fill(assign, -1);
        int[] counts = new int[k];
        for (int iter = 0; iter < 100; iter++) {
            boolean changed = false;
            Arrays.fill(counts, 0);
            double[][] sum = new double[k][d];
            for (int i = 0; i < n; i++) {
                int a = nearest(x[i], cent);
                if (a != assign[i]) {
                    assign[i] = a;
                    changed = true;
                }
                counts[a]++;
                for (int j = 0; j < d; j++) {
                    sum[a][j] += x[i][j];
                }
            }
            for (int c = 0; c < k; c++) {
                if (counts[c] == 0) {
                    continue;   // 空簇：保留原质心
                }
                for (int j = 0; j < d; j++) {
                    cent[c][j] = sum[c][j] / counts[c];
                }
            }
            if (!changed && iter > 0) {
                break;
            }
        }
        return cent;
    }

    /** 最近质心；并列时取下标最小者（确定性）。 */
    private static int nearest(double[] v, double[][] cent) {
        int best = 0;
        double bestD = Double.POSITIVE_INFINITY;
        for (int c = 0; c < cent.length; c++) {
            double dd = sqDist(v, cent[c]);
            if (dd < bestD) {
                bestD = dd;
                best = c;
            }
        }
        return best;
    }

    private static double sqDist(double[] a, double[] b) {
        double s = 0;
        for (int j = 0; j < a.length; j++) {
            double t = a[j] - b[j];
            s += t * t;
        }
        return s;
    }
}
