package com.yanglizi.docraptor.dto.request;

import lombok.Data;

/** PUT /api/knowledge-bases/{id} 请求体（契约 4.4）。null 表示不改，"" 表示清空描述。 */
@Data
public class KnowledgeBaseUpdateRequest {
    private String name;
    private String description;
}
