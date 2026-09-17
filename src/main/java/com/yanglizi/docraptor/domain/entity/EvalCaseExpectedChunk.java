package com.yanglizi.docraptor.domain.entity;

import lombok.Data;

import java.time.OffsetDateTime;
import java.util.UUID;

/** eval_case_expected_chunk 表：用例的期望命中块（必须是 LEAF 节点）。 */
@Data
public class EvalCaseExpectedChunk {
    private UUID id;
    private UUID evalCaseId;
    private UUID nodeId;
    private UUID documentId;
    private Integer chunkIndex;
    private Integer relevance;
    private OffsetDateTime createdAt;
}
