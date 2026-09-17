package com.yanglizi.docraptor.dto.request;

import lombok.Data;

import java.util.List;

/** 三个检索接口共用的请求体（契约 6.1）。mode 由路径决定，请求体里传会被忽略。 */
@Data
public class RetrievalRequest {
    private String knowledgeBaseId;
    private String query;
    private Integer topK;
    private Double similarityThreshold;
    private Double hybridRatio;
    private Double bm25Weight;
    private Integer rrfK;
    private String scope;
    private List<Integer> levels;
    private List<String> documentIds;
    private Boolean withContent;
    private Boolean withScoreBreakdown;
}
