package com.yanglizi.docraptor.domain.entity;

import lombok.Data;

import java.time.OffsetDateTime;
import java.util.UUID;

/** retrieval_log 表：每次检索/评估一条。 */
@Data
public class RetrievalLog {
    private UUID id;
    private UUID knowledgeBaseId;
    private String logType;
    private String queryText;
    private String mode;
    private Integer topK;
    private Double similarityThreshold;
    private Double hybridRatio;
    private Double bm25Weight;
    private Integer rrfK;
    private String scope;
    private String scopeLevels;
    /** JSONB 数组字符串，如 ["uuid1","uuid2"] */
    private String documentIds;
    private Integer resultCount;
    /** JSONB 数组字符串，按最终排名顺序 */
    private String resultNodeIds;
    private Integer latencyMs;
    private Integer vectorLatencyMs;
    private Integer bm25LatencyMs;
    private Integer fusionLatencyMs;
    private Boolean success;
    private String errorMessage;
    private OffsetDateTime createdAt;
}
