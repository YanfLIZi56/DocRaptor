package com.yanglizi.docraptor.config;

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
        private int batchSize = 32;
        private int dimensions = 1536;
        private int maxRetries = 3;
        private long retryBackoffMs = 1000L;
        private long timeoutMs = 30000L;
    }

    @Data
    public static class Raptor {
        private int maxLevel = 3;
        private int minClusterSize = 2;
        private int summaryConcurrency = 4;
        private boolean rebuildOnConflict = true;
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
            private int maxClusters = 8;
            /**
             * 簇数<b>绝对</b>上限，在 {@code maxClusters} 之上再压一层。
             * ⚠️ 实测 BIC 在合成数据上随 k 单调下降、会一路选到上限，摘要随之退化成对单个块的复述；
             * 因此必须同时用 {@code ceil(sqrt(n))} 与本值把簇数硬顶住（Lead 推荐 6）。
             * 实际生效值 = {@code max(2, min(maxClusters, absoluteMaxClusters, ceil(sqrt(n))))}。
             */
            private int absoluteMaxClusters = 6;
            private int minClusters = 2;
            private String covarianceType = "diagonal";
            private int randomState = 42;
            /** BIC / AIC / FIXED */
            private String selection = "BIC";
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
