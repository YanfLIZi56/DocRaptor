package com.yanglizi.docraptor.dto.response;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/** 召回评估结果（契约 6.5）。 */
@Data
public class EvalRunVO {
    private String knowledgeBaseId;
    private String mode;
    private Integer topK;
    private EvalParamsVO params;
    private Integer evaluatedCases;
    private List<SkippedCaseVO> skippedCases = new ArrayList<>();
    /** 被裁剪掉的 K（> topK 的那些） */
    private List<Integer> truncatedKList = new ArrayList<>();
    private List<MetricVO> metrics = new ArrayList<>();
    private Long avgLatencyMs;
    private List<String> retrievalLogIds = new ArrayList<>();
    private List<PerQueryVO> perQuery = new ArrayList<>();

    @Data
    public static class EvalParamsVO {
        private Double similarityThreshold;
        private Double hybridRatio;
        private Double bm25Weight;
        private Integer rrfK;
        private String scope;
        private List<Integer> levels;
    }

    /** 数据集级指标（对未跳过用例求算术平均）。mrr 对 k=topK 口径计算，与 k 无关。 */
    @Data
    public static class MetricVO {
        private Integer k;
        private Double recall;
        private Double hitRate;
        private Double mrr;
    }

    @Data
    public static class SkippedCaseVO {
        private String caseId;
        private String name;
        private String reason;
    }

    @Data
    public static class PerQueryVO {
        private String caseId;
        private String name;
        private String query;
        private List<String> expectedChunkIds = new ArrayList<>();
        private List<String> retrievedNodeIds = new ArrayList<>();
        private List<String> hitNodeIds = new ArrayList<>();
        private List<String> missedNodeIds = new ArrayList<>();
        private Integer firstHitRank;
        private List<PerQueryMetricVO> metrics = new ArrayList<>();
        private Long latencyMs;
        private String retrievalLogId;
    }

    @Data
    public static class PerQueryMetricVO {
        private Integer k;
        private Double recall;
        private Integer hitRate;
        private Double reciprocalRank;
    }
}
