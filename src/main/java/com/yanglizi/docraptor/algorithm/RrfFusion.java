package com.yanglizi.docraptor.algorithm;

import lombok.Data;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RRF（Reciprocal Rank Fusion）融合，纯函数、无 Spring / DB 依赖。
 *
 * <p>公式（docs/01-architecture.md 6.2）：
 * <pre>
 * rank_r(d) = d 在第 r 路排名列表中的位置下标（从 1 开始）；d 未出现在该路时不贡献
 * RRF(d)    = Σ_r  w_r · 1 / (k + rank_r(d))
 * w_vector  = hybridRatio
 * w_bm25    = (1 - hybridRatio) · bm25Weight
 * k         = rrfK（默认 60）
 * </pre>
 * 某路未召回时该路项记为 0（即 weightedScore 为 null、对 totalScore 无贡献）。
 */
public final class RrfFusion {

    private RrfFusion() {
    }

    /** 单路召回列表中的一个条目；列表顺序即为排名顺序（1 起）。 */
    public record Ranked(String id, double rawScore) {
    }

    /** 融合结果。 */
    @Data
    public static class Fused {
        private String id;
        /** RRF 融合分（= vectorWeightedScore + bm25WeightedScore，未召回路记 0） */
        private double finalScore;
        /** 各路原始排名，未召回为 null */
        private Integer vectorRank;
        private Integer bm25Rank;
        /** 各路原始分数，未召回为 null */
        private Double vectorRawScore;
        private Double bm25RawScore;
        /** w_r × 1/(rrfK + rank)，即该路对 finalScore 的实际贡献；未召回为 null */
        private Double vectorWeightedScore;
        private Double bm25WeightedScore;
        private int rrfK;
    }

    /**
     * 融合两路召回。
     *
     * @param vectorRanked 向量路召回列表（按 rawScore 降序，下标 0 即 rank 1）
     * @param bm25Ranked   BM25 路召回列表（按 rawScore 降序）
     * @param wVector      向量路权重 w_vector
     * @param wBm25        BM25 路权重 w_bm25（= (1-hybridRatio) × bm25Weight）
     * @param rrfK         RRF 平滑常数
     * @return 按 finalScore 降序的结果列表
     */
    public static List<Fused> fuse(List<Ranked> vectorRanked, List<Ranked> bm25Ranked,
                                   double wVector, double wBm25, int rrfK) {
        Map<String, Fused> byId = new LinkedHashMap<>();

        if (vectorRanked != null) {
            for (int i = 0; i < vectorRanked.size(); i++) {
                Ranked r = vectorRanked.get(i);
                int rank = i + 1;
                Fused f = byId.computeIfAbsent(r.id(), id -> newFused(id, rrfK));
                f.vectorRank = rank;
                f.vectorRawScore = r.rawScore();
                f.vectorWeightedScore = wVector / (rrfK + rank);
                f.finalScore += f.vectorWeightedScore;
            }
        }
        if (bm25Ranked != null) {
            for (int i = 0; i < bm25Ranked.size(); i++) {
                Ranked r = bm25Ranked.get(i);
                int rank = i + 1;
                Fused f = byId.computeIfAbsent(r.id(), id -> newFused(id, rrfK));
                f.bm25Rank = rank;
                f.bm25RawScore = r.rawScore();
                f.bm25WeightedScore = wBm25 / (rrfK + rank);
                f.finalScore += f.bm25WeightedScore;
            }
        }

        List<Fused> out = new ArrayList<>(byId.values());
        // 稳定排序：分数降序 → 最优单路排名升序 → id 升序（保证结果确定）
        out.sort(Comparator
                .comparingDouble(Fused::getFinalScore).reversed()
                .thenComparingInt(RrfFusion::bestRank)
                .thenComparing(Fused::getId));
        return out;
    }

    /** Bm25 路权重 = (1 - hybridRatio) × bm25Weight。 */
    public static double bm25Weight(double hybridRatio, double bm25Weight) {
        return (1.0 - hybridRatio) * bm25Weight;
    }

    private static Fused newFused(String id, int rrfK) {
        Fused f = new Fused();
        f.id = id;
        f.finalScore = 0.0;
        f.rrfK = rrfK;
        return f;
    }

    private static int bestRank(Fused f) {
        int v = f.vectorRank == null ? Integer.MAX_VALUE : f.vectorRank;
        int b = f.bm25Rank == null ? Integer.MAX_VALUE : f.bm25Rank;
        return Math.min(v, b);
    }
}
