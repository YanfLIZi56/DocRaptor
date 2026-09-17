package com.yanglizi.docraptor.domain.entity;

import lombok.Data;

import java.time.OffsetDateTime;
import java.util.UUID;

/** eval_case 表：测试查询 + 期望命中块。 */
@Data
public class EvalCase {
    private UUID id;
    private UUID knowledgeBaseId;
    private String name;
    private String queryText;
    private String remark;
    private Boolean enabled;
    private Double lastRecallAtK;
    private Double lastMrr;
    private OffsetDateTime lastEvaluatedAt;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
