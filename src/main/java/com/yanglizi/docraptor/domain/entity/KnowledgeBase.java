package com.yanglizi.docraptor.domain.entity;

import lombok.Data;

import java.time.OffsetDateTime;
import java.util.UUID;

/** knowledge_base 表。分块参数建库时固化，之后 PUT 不可修改。 */
@Data
public class KnowledgeBase {
    private UUID id;
    private String name;
    private String description;
    private Integer chunkSize;
    private Integer chunkOverlap;
    private String chunkStrategy;
    private Integer documentCount;
    private Integer nodeCount;
    private String embeddingModel;
    private Integer embeddingDimension;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
