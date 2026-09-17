package com.yanglizi.docraptor.dto.response;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/** 评估用例对象（契约 6.3 / 6.4）。 */
@Data
public class EvalCaseVO {
    private String id;
    private String knowledgeBaseId;
    private String name;
    private String queryText;
    private String remark;
    private Boolean enabled;
    private List<ExpectedChunkVO> expectedChunks = new ArrayList<>();
    private Double lastRecallAtK;
    private Double lastMrr;
    private Long lastEvaluatedAt;
    private Long createdAt;

    @Data
    public static class ExpectedChunkVO {
        private String nodeId;
        private String documentId;
        private Integer chunkIndex;
        private Integer relevance;
    }
}
