package com.yanglizi.docraptor.dto.response;

import lombok.Data;

/** 知识库对象（契约 4.1 / 4.2 / 4.3 / 4.4 的 data）。 */
@Data
public class KnowledgeBaseVO {
    private String id;
    private String name;
    private String description;
    private Integer chunkSize;
    private Integer chunkOverlap;
    private String chunkStrategy;
    private Integer documentCount;
    private Integer nodeCount;
    private String embeddingModel;
    private Integer embeddingDimension;
    private Long createdAt;
    private Long updatedAt;
}
