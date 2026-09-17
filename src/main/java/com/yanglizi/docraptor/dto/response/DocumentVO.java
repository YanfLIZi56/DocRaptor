package com.yanglizi.docraptor.dto.response;

import lombok.Data;

import java.util.Map;

/** 文档对象（契约 4.7 list 元素 / 4.8 详情）。 */
@Data
public class DocumentVO {
    private String id;
    private String knowledgeBaseId;
    private String knowledgeBaseName;
    private String fileName;
    private String fileType;
    private Long fileSize;
    private Integer charCount;
    private Integer chunkCount;
    private Boolean enabled;
    private String parseStatus;
    private String chunkStatus;
    private String embedStatus;
    private String treeStatus;
    private String parseError;
    private Map<String, Object> metadata;
    private Long createdAt;
    private Long updatedAt;
}
