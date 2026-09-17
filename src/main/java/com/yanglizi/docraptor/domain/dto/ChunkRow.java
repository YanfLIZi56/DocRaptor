package com.yanglizi.docraptor.domain.dto;

import lombok.Data;

import java.time.OffsetDateTime;
import java.util.UUID;

/** 分块列表行（契约 4.9）。 */
@Data
public class ChunkRow {
    private UUID nodeId;
    private UUID documentId;
    private String documentName;
    private UUID knowledgeBaseId;
    private String nodeType;
    private Short level;
    private Integer chunkIndex;
    private Integer startChunkIndex;
    private Integer endChunkIndex;
    private Integer charCount;
    private Integer tokenCount;
    private UUID parentId;
    private Boolean hasEmbedding;
    private Integer embeddingDimension;
    /** 向量文本前若干字符（用于前 8 维预览；withEmbedding=false 时前端不展示） */
    private String embeddingPreview;
    private String content;
    private OffsetDateTime createdAt;
}
