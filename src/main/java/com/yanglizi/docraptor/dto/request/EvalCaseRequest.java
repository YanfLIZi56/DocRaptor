package com.yanglizi.docraptor.dto.request;

import lombok.Data;

import java.util.List;

/** POST/PUT /api/eval/cases 请求体（契约 6.3 / 6.4）。 */
@Data
public class EvalCaseRequest {
    private String knowledgeBaseId;
    private String name;
    private String queryText;
    private List<String> expectedChunkIds;
    private String remark;
    private Boolean enabled;
}
