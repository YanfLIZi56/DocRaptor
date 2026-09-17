package com.yanglizi.docraptor.dto.response;

import lombok.Data;

import java.util.List;

/** 检索日志元素（契约 6.2）。 */
@Data
public class RetrievalLogVO {
    private String id;
    private String knowledgeBaseId;
    private String logType;
    private String queryText;
    private String mode;
    private ParamsVO params;
    private Integer resultCount;
    private List<String> resultNodeIds;
    private Integer latencyMs;
    private LatencyBreakdownVO latencyBreakdown;
    private Boolean success;
    private String errorMessage;
    private Long createdAt;

    @Data
    public static class ParamsVO {
        private Integer topK;
        private Double similarityThreshold;
        private Double hybridRatio;
        private Double bm25Weight;
        private Integer rrfK;
        private String scope;
        private String levels;
        private List<String> documentIds;
    }

    @Data
    public static class LatencyBreakdownVO {
        private Integer vectorMs;
        private Integer bm25Ms;
        private Integer fusionMs;
    }
}
