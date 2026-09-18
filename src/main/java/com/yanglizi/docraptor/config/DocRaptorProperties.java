package com.yanglizi.docraptor.config;

import com.yanglizi.docraptor.algorithm.DeterministicGmm;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * DocRaptor 业务配置。前缀 {@code docraptor}，逐项对照 docs/01-architecture.md 第 8 节配置表。
 * 仅"接口未传参时"作为默认值来源；实际建树使用的聚类参数会写入 {@code summary_nodes.metadata} 以便追溯。
 */
@Data
@Component
@ConfigurationProperties(prefix = "docraptor")
public class DocRaptorProperties {

    private Chunk chunk = new Chunk();
    private Embedding embedding = new Embedding();
    private Raptor raptor = new Raptor();
    private Summary summary = new Summary();
    private Retrieval retrieval = new Retrieval();
    private ImportConfig importConfig = new ImportConfig();
    private Async async = new Async();
    private Eval eval = new Eval();

    @Data
    public static class Chunk {
        private int size = 512;
        private int overlap = 64;
        private String strategy = "FIXED_SIZE";
        private int minChunkChars = 32;
        private boolean normalizeWhitespace = true;
    }

    @Data
    public static class Embedding {
        /**
         * 单次 embedding 请求的最大文本条数。
         *
         * <p><b>⚠️ 硬上限 20（Lead 实测）</b>：本项目的 embedding 端点单次最多接受 20 条，
         * 第 21 条起直接返回 {@code 400 InternalError.Algo.InvalidParameter:
         * Value error, batch size is invalid, it should not be larger than 20.}。
         *
         * <p>超大文档会切成几百块（实测一份文档 376 块），全靠 {@code SpringAiGateway} 按本值切片分批，
         * 因此<b>本值绝不能大于 20</b>。默认取 16 留安全余量；换其它 embedding 服务时需按其实际上限重设。
         */
        private int batchSize = 16;
        private int dimensions = 1536;
        private int maxRetries = 3;
        private long retryBackoffMs = 1000L;
        private long timeoutMs = 30000L;
    }

    @Data
    public static class Raptor {
        private int maxLevel = 3;
        /**
         * <b>簇数上限约束（只作用于 {@code effectiveKMax}）</b>，默认 8。
         *
         * <p>它把簇数上限压到 {@code n / minClusterSize}，防止两级聚类的<b>局部阶段</b>
         * （全局簇只有十几到几十个成员、{@code kMax = min(50, n) = n}）被 BIC 选成 k=n、
         * 每个节点自成一簇。Lead v1.2 实测（313 块）：`minSize=1 → 309 簇 / 99% 单节点簇`；
         * `minSize=8 → 23 簇 / 4% 单节点簇`。
         *
         * <p>当前实现（确定性 GMM + 全协方差 + 自动折算 token 上限）下的实测：
         * <pre>
         * minSize=1 → 58 簇 / 最大 18 / 单节点 1 个（2%）
         * minSize=8 → 35 簇 / 最大 18 / 单节点 0 个（0%）   ← 落在验收区间 20~40
         * </pre>
         *
         * <p>⚠️ <b>刻意与另外两个同名字段解耦</b>（否则会误伤）：
         * <ul>
         *   <li>{@code TreeBuildGuard} 的阈值固定用 2 —— 它的职责只是拦「全是一节点簇」的退化，
         *       若把 8 传进去，健康的层（最大簇 < 8）也会被判 {@code NO_REAL_MERGE} 而<b>提前收根、压掉树深</b>；</li>
         *   <li>落库前的小簇合并阈值固定用 2 —— 与簇数上限叠加会二次压低簇数（58 → 35 → 更少）。</li>
         * </ul>
         */
        private int minClusterSize = 8;
        private int summaryConcurrency = 4;
        private boolean rebuildOnConflict = true;

        /**
         * 降维方式：{@code PCA}（默认）/ {@code UMAP}（官方）/ {@code NONE}（仅调试）。
         *
         * <p><b>为什么默认 PCA 而不是官方的 UMAP</b>：官方 Python 侧 {@code umap.UMAP(...)} 其实<b>也没有设
         * random_state</b>，而 Smile 4.1.0 的 UMAP API 同样<b>没有种子参数</b> —— 实测同一份
         * 313×1536 embedding 连跑 3 次，输出逐位不同，直接导致树不可复现（k 在 2~12 跳变）。
         * 本项目 P0 目标是「同输入必得同输出」，因此默认确定性 PCA；要复现官方随机行为可显式改 UMAP。
         */
        private String reduction = "PCA";
        /** 官方 {@code reduction_dimension = 10}（改造前是 2，是「只分出 3 个簇」的直接原因之一）。 */
        private int reductionDimension = 10;
        /** 官方 {@code threshold = 0.1}（软聚类：责任度 > 0.1 的全部归属）。 */
        private double threshold = 0.1;
        /** 是否启用官方两级聚类（全局 GMM → 逐全局簇局部 GMM）；false 仅用于对照实验。 */
        private boolean twoStage = true;
        /**
         * 官方 {@code max_length_in_cluster}。语义：{@code <0} 关闭递归细分；{@code 0}（默认）
         * 按块大小<b>自动折算</b>；{@code >0} 字面 token 数。
         *
         * <p>⚠️ <b>不要照搬官方的 3500</b>（Lead 实测）：官方的 3500 是按它自己<b>约 100 token 的块</b>
         * 标定的，等于「单簇约 35 块」。我们的块是 700 中文字符 ≈ 470~670 token，同样的 3500
         * 只容得下 5~7 块 → 递归细分被疯狂触发、把簇切碎
         * （313 块实测：3500 → 102 簇、24% 单节点簇；20000 → 47 簇、2% 单节点簇）。
         */
        private int maxClusterTokens = 0;
        /** 自动折算时「单簇可容纳的块数」，对应官方的 3500/≈100 ≈ 35 块。 */
        private int nodesPerClusterAtCap = 35;
        /** true → 全局 {@code n_neighbors = int(sqrt(n-1))}（官方行为，n=313 → 17）。 */
        private boolean autoGlobalNNeighbors = true;
        /** {@code autoGlobalNNeighbors=false} 时生效；&lt;=0 表示回退 sqrt 公式。 */
        private int globalNNeighbors = 0;
        /** 官方局部 {@code num_neighbors = 10}。 */
        private int localNNeighbors = 10;
        private Umap umap = new Umap();
        private Gmm gmm = new Gmm();

        @Data
        public static class Umap {
            private boolean enabled = true;
            /**
             * UMAP 最近邻数。⚠️ 必须 < 当前层节点数：nNeighbors >= n 时 {@code shouldReduce} 为 false，
             * 会直接跳过降维，导致 GMM 在 1536 维上必然失败 → 单簇 → 整棵树没有层次。
             * 一篇 6000 字文档按 600 字分块约 11 块，故取 5。
             */
            private int nNeighbors = 5;
            private double minDist = 0.05;
            /**
             * UMAP 目标维度。⚠️ 受严格约束 {@code 2d + 3 < n}（Smile 4.1.0 谱初始化需要 NEV = 2d+3）。
             * n=11 时 d 只能取 2 或 3；取 10 会抛 {@code Invalid NEV parameter k: 23}。
             * 实际使用值还会被 {@code UmapReducer} 按 n 再夹紧一次。
             */
            private int nComponents = 2;
            private String metric = "cosine";
            private int epochs = 200;
            private double learningRate = 1.0;
            private double spread = 1.0;
            private int negativeSamples = 5;
            private double repulsionStrength = 1.0;
            private double localConnectivity = 1.0;
            /** Smile 4.1.0 的 UMAP API 没有随机种子参数，此项目无法固定随机性，见实现注释。 */
            private int randomState = 42;
        }

        @Data
        public static class Gmm {
            /**
             * 官方 {@code max_clusters = 50}。
             *
             * <p>⚠️ 改造前是 8：与 {@code absoluteMaxClusters=6} 一起把 313 块的《三体》
             * 压成 3 个全局簇（树只有 2 层）。官方值就是 50，BIC 自己会选出远小于它的 k。
             */
            private int maxClusters = 50;
            /**
             * 簇数<b>绝对</b>上限。<b>默认 0 = 不设限（与官方一致）</b>。
             *
             * <p>改造前默认 6，是为了堵住「Smile GMM 无法播种 + BIC 一路选到上限」导致的
             * 「摘要退化成单块复述」。现在随机性已由 {@link DeterministicGmm} 解决，
             * 这个硬顶反而有害（它正是「只有 3 个簇」的原因之一），故默认关闭，仅留作应急开关。
             */
            private int absoluteMaxClusters = 0;
            /** 保留字段：当前实现以 BIC 为准（与官方 {@code get_optimal_clusters} 一致）。 */
            private int minClusters = 2;
            /**
             * 协方差类型：{@code full}（默认，官方 sklearn 默认）/ {@code diagonal}。
             *
             * <p>⚠️ 必须默认 full：对角协方差每个分量只要 {@code 2d} 个参数，在「样本数几十、维度 10」
             * 的局部子集上会被「一个分量钉住一个点」的坍缩解骗过去（实测 log-likelihood 变成正数、
             * 50 个点切成 50 个单点簇）；全协方差每个分量 {@code d + d(d+1)/2} 个参数（d=10 时 55 个），
             * 惩罚足以压住坍缩 —— 用官方 sklearn 工具链在同一份装置上验证：全协方差 → 43 簇、最大 11%，
             * 对角 → 315 簇、最大 3%。
             */
            private String covarianceType = "full";
            /** 保留字段：本项目 GMM 完全确定性，无需种子（{@code DeterministicGmm.SEED=224} 固定）。 */
            private int randomState = 42;
            /** BIC / AIC / FIXED（当前实现只有 BIC，与官方一致）。 */
            private String selection = "BIC";
            /** 是否在拟合前做逐维标准化（默认 false = 官方行为，sklearn 侧不做 StandardScaler）。 */
            private boolean standardize = false;
            /** 迭代上限（官方 sklearn 默认 100）。 */
            private int maxIterations = 100;
            /** 收敛阈值（官方 sklearn 默认 1e-3）。 */
            private double tolerance = 0.001;
            /** 协方差正则 {@code reg_covar}（官方 sklearn 默认 1e-6）。 */
            private double regularization = 0.000001;

            /** 把配置映射成算法层的 {@link DeterministicGmm.Config}（{@code override} 非空时覆盖协方差类型）。 */
            public DeterministicGmm.Config toGmmConfig(String override) {
                String cov = override != null && !override.isBlank() ? override : covarianceType;
                boolean full = !"diagonal".equalsIgnoreCase(cov) && !"tied".equalsIgnoreCase(cov);
                return new DeterministicGmm.Config(full, standardize, maxIterations, tolerance, regularization);
            }
        }
    }

    @Data
    public static class Summary {
        private int maxSummaryChars = 400;
        private int maxInputChars = 12000;
        private int maxRetries = 3;
        private double temperature = 0.2;
        private boolean enableThinking = false;
        private int maxTokens = 1024;
        /** 单簇摘要连续失败后降级为原文拼接时，每个成员保留的字符数。 */
        private int degradedMemberChars = 200;
    }

    @Data
    public static class Retrieval {
        private String defaultMode = "HYBRID";
        private int defaultTopK = 10;
        private int maxTopK = 100;
        private double defaultSimilarityThreshold = 0.0;
        private double defaultHybridRatio = 0.5;
        private double defaultBm25Weight = 1.0;
        private int defaultRrfK = 60;
        private String defaultScope = "ALL_LEVELS";
        private int candidateMultiplier = 3;
        private int candidateMax = 200;
        private int hnswEfSearch = 100;
        private boolean foldTree = true;
        private boolean logEnabled = true;
    }

    @Data
    public static class ImportConfig {
        private String storageDir = "./data/upload";
        private int maxFileSizeMb = 10;
        private String allowedExtensions = "pdf,docx,md,markdown,txt";
        private int maxParseChars = 2000000;
        private boolean autoBuildTree = true;
    }

    @Data
    public static class Async {
        private int corePoolSize = 2;
        private int maxPoolSize = 4;
        private int queueCapacity = 100;
        private int heartbeatStaleSeconds = 300;
    }

    @Data
    public static class Eval {
        /** 用例级默认 kList */
        private String defaultKList = "1,3,5,10";
        /** 同步评估时允许的最大用例数，超过建议用 async=true */
        private int maxSyncCases = 50;
    }
}
