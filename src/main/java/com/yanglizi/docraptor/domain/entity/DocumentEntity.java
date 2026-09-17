package com.yanglizi.docraptor.domain.entity;

import lombok.Data;

import java.time.OffsetDateTime;
import java.util.UUID;

/** document 表。正文一经导入不可改删，enabled 是唯一可写业务字段。 */
@Data
public class DocumentEntity {
    private UUID id;
    private UUID knowledgeBaseId;
    private String fileName;
    private String storedPath;
    private String fileType;
    private Long fileSize;
    private String contentHash;
    private String parseStatus;
    private String chunkStatus;
    private String embedStatus;
    private String treeStatus;
    private Integer charCount;
    private Integer chunkCount;
    private Boolean enabled;
    private String parseError;
    /** JSONB，MyBatis 中按字符串读写并在 SQL 里 ::jsonb 转换 */
    private String metadata;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
    /** 非表字段：列表/详情查询 JOIN knowledge_base 带出，列别名 knowledge_base_name。 */
    private String knowledgeBaseName;
}
