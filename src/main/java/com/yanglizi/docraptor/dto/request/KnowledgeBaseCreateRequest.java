package com.yanglizi.docraptor.dto.request;

import lombok.Data;

/** POST /api/knowledge-bases 请求体（契约 4.1）。 */
@Data
public class KnowledgeBaseCreateRequest {
    private String name;
    private String description;
    private Integer chunkSize;
    private Integer chunkOverlap;
    private String chunkStrategy;
}
