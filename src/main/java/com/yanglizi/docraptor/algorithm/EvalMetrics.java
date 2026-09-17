package com.yanglizi.docraptor.algorithm;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 召回评估指标：Recall@K / Hit Rate@K / MRR，纯函数。
 *
 * <p>口径（docs/01-architecture.md 第 7 节，严格口径）：
 * <pre>
 * hit_q@K   = { i ∈ [1,K] : retrieved[i] ∈ expected }
 * Recall_q@K   = |hit_q@K| / |expected|
 * HitRate_q@K  = 1 若 hit_q@K ≠ ∅，否则 0
 * RR_q         = min(hit_q@K)，无命中记 0
 * MRR_q        = 1 / RR_q（无命中记 0）
 * 数据集级指标 = 对未跳过用例求算术平均
 * </pre>
 *
 * <p><b>命中判定为严格口径</b>：必须命中期望的<b>叶子块 ID 本身</b>；命中其父摘要节点<b>不算</b>命中。
 */
public final class EvalMetrics {

    private EvalMetrics() {
    }

    /** 单查询指标。hitRanks 为命中位置（升序），missedIds 为未召回的期望块。 */
    public record QueryMetrics(double recall, double hitRate, double reciprocalRank,
                               List<Integer> hitRanks, List<String> hitIds, List<String> missedIds) {
    }

    /**
     * 计算单查询在给定 K 下的指标。
     *
     * @param retrievedIds 检索返回的有序结果（index 0 = rank 1）
     * @param expectedIds  期望块 ID 集合（relevance=1 的 LEAF 节点）
     * @param k            截断深度
     */
    public static QueryMetrics compute(List<String> retrievedIds, Set<String> expectedIds, int k) {
        List<Integer> hitRanks = new ArrayList<>();
        Set<String> hitIds = new LinkedHashSet<>();
        if (expectedIds != null && !expectedIds.isEmpty() && retrievedIds != null) {
            int limit = Math.min(Math.max(k, 0), retrievedIds.size());
            for (int i = 0; i < limit; i++) {
                String id = retrievedIds.get(i);
                // 去重：同一节点重复出现只算一次命中（正常检索结果无重复，这里做防御）
                if (expectedIds.contains(id) && hitIds.add(id)) {
                    hitRanks.add(i + 1);
                }
            }
        }
        int n = expectedIds == null ? 0 : expectedIds.size();
        double recall = n == 0 ? 0.0 : (double) hitRanks.size() / n;
        double hitRate = hitRanks.isEmpty() ? 0.0 : 1.0;
        double rr = hitRanks.isEmpty() ? 0.0 : 1.0 / hitRanks.get(0);

        List<String> missed = new ArrayList<>();
        if (expectedIds != null) {
            for (String id : expectedIds) {
                if (!hitIds.contains(id)) {
                    missed.add(id);
                }
            }
        }
        return new QueryMetrics(recall, hitRate, rr, hitRanks, new ArrayList<>(hitIds), missed);
    }

    /** 数据集级指标 = 各查询指标的算术平均（调用方需先把跳过的用例剔除）。 */
    public static double mean(java.util.List<Double> values) {
        if (values == null || values.isEmpty()) {
            return 0.0;
        }
        double sum = 0.0;
        for (Double v : values) {
            sum += v == null ? 0.0 : v;
        }
        return sum / values.size();
    }

    /** 四舍五入到 4 位小数，与契约示例（0.2500 / 0.6667）一致。 */
    public static double round4(double v) {
        return Math.round(v * 10000.0) / 10000.0;
    }
}
