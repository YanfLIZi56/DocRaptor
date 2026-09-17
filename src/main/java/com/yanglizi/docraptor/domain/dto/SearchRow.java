package com.yanglizi.docraptor.domain.dto;

import lombok.Data;

import java.util.UUID;

/** 向量路 / BM25 路的召回行（两路共用，raw_score 语义不同：向量=1-余弦距离，BM25=paradedb.score）。 */
@Data
public class SearchRow {
    private UUID nodeId;
    private String nodeType;
    private Short level;
    private UUID documentId;
    private String documentName;
    private Boolean documentEnabled;
    private Integer chunkIndex;
    private Integer startChunkIndex;
    private Integer endChunkIndex;
    private Integer charCount;
    private String content;
    private Double rawScore;
}
