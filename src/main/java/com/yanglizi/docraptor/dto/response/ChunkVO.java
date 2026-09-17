package com.yanglizi.docraptor.dto.response;

import lombok.Data;

import java.util.List;

/** 分块列表元素（契约 4.9）。每块必须含 documentId / chunkIndex / charCount。 */
@Data
public class ChunkVO {
    private String nodeId;
    private String documentId;
    private String documentName;
    private String knowledgeBaseId;
    private String nodeType;
    private Short level;
    private Integer chunkIndex;
    private Integer startChunkIndex;
    private Integer endChunkIndex;
    private Integer charCount;
    private Integer tokenCount;
    private String parentId;
    private Boolean hasEmbedding;
    private Integer embeddingDimension;
    private List<Double> embeddingPreview;
    private String content;
    private Long createdAt;
}
