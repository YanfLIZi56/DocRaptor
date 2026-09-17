package com.yanglizi.docraptor.dto.request;

import lombok.Data;

import java.util.List;

/** POST /api/eval/run 请求体（契约 6.5）。 */
@Data
public class EvalRunRequest {
    private String knowledgeBaseId;
    private List<String> caseIds;
    private Integer topK;
    private List<Integer> kList;
    private String mode;
    private Double similarityThreshold;
    private Double hybridRatio;
    private Double bm25Weight;
    private Integer rrfK;
    private String scope;
    private List<Integer> levels;
    /** false：同步返回指标；true：返回 taskId 异步执行。 */
    private Boolean async;
}
