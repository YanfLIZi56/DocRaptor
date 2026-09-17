package com.yanglizi.docraptor.dto.response;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/** 检索结果（契约 6.1，三个接口结构一致）。 */
@Data
public class RetrievalResultVO {
    private String query;
    private String mode;
    private String knowledgeBaseId;
    private String scope;
    private ParamsVO params;
    private Long costMs;
    private CostBreakdownVO costBreakdown;
    private Integer totalHits;
    private Integer collapsedCount;
    private Integer truncatedByThreshold;
    private List<HitVO> hits = new ArrayList<>();

    /** 请求参数回显。 */
    @Data
    public static class ParamsVO {
        private Integer topK;
        private Double similarityThreshold;
        private Double hybridRatio;
        private Double bm25Weight;
        private Integer rrfK;
        private List<Integer> levels;
        private List<String> documentIds;
    }

    /** 各路耗时。BM25 模式 embeddingMs 为 0。 */
    @Data
    public static class CostBreakdownVO {
        private Long embeddingMs;
        private Long vectorMs;
        private Long bm25Ms;
        private Long fusionMs;
        private Long totalMs;
    }

    /** 单条命中。 */
    @Data
    public static class HitVO {
        private Integer finalRank;
        private Double finalScore;
        private String nodeId;
        private String nodeType;
        private Short level;
        private String documentId;
        private String documentName;
        private Boolean documentEnabled;
        private Integer chunkIndex;
        private Integer startChunkIndex;
        private Integer endChunkIndex;
        private Integer charCount;
        private String content;
        /** 非空表示该结果被折叠（当前实现里被折叠条目不会出现在 hits 中，仅作调试用） */
        private String collapsedByNodeId;
        private ScoreBreakdownVO scoreBreakdown;
    }

    /** 各路原始排名与原始分数（契约 6.1 的 scoreBreakdown）。 */
    @Data
    public static class ScoreBreakdownVO {
        private Double vectorRawScore;
        private Integer vectorRank;
        private Double vectorWeightedScore;
        private Double bm25RawScore;
        private Integer bm25Rank;
        private Double bm25WeightedScore;
        private Integer rrfK;
    }
}
