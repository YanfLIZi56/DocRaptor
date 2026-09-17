package com.yanglizi.docraptor.algorithm;

import java.util.List;

/**
 * RAPTOR 建树的<b>层间收敛判定</b>（纯函数，便于单测）。
 *
 * <h3>为什么需要它</h3>
 * 建树的递归靠「每往上一层，节点数都会减少」来收敛。但下面几种退化情况会让节点数<b>不再减少</b>，
 * 于是树会一直往上长（或把大量单节点簇"原样提升"成父节点，摘要退化成对单个块的复述）：
 * <ul>
 *   <li>实测 {@code model.bic(x)} 在缺少结构的数据上随 k 单调下降，{@code if (r.bic() < best.bic())}
 *       会一路选到 kMax，n=11 时可能选出 4~8 个簇 → 单节点簇；</li>
 *   <li>全同向量 / 高维奇异协方差 → 拟合失败 → 退化成"每点一簇"；</li>
 *   <li>簇数上限被配成很大时，{@code k} 可能 ≥ 当前层节点数。</li>
 * </ul>
 * 判定规则（命中任意一条即<b>立刻停止向上递归</b>并把整层合并成一个父节点）：
 * <pre>
 *   · 本层只剩 1 个节点        → CONVERGED（正常收敛，它即根）
 *   · 簇数 ≤ 1                → CANNOT_SPLIT（无法再分）
 *   · 簇数 ≥ 当前层节点数      → NOT_SHRINKING（新层不会比当前层小，递归不收敛）
 *   · 所有簇的成员数都 < 阈值  → NO_REAL_MERGE（没有任何一个簇真正合并了 ≥2 个节点）
 * 阈值 = max(2, minClusterSize)，因此"全是一节点簇"也一定会被拦住。
 * </pre>
 *
 * <p><b>补充说明（实测）</b>：在当前实现里，簇数上限已经被
 * {@link ClusterPipeline#effectiveKMax} 压到 {@code max(2, min(配置值, 6, ceil(sqrt(n))))}，
 * 而 {@code ceil(sqrt(n)) < n} 对所有 n ≥ 2 成立，因此 {@code NOT_SHRINKING} 目前不会真正触发；
 * {@code NO_REAL_MERGE} 也会被 {@code RaptorTreeService} 的小簇并入逻辑提前消化掉。
 * 也就是说这道判定当前是<b>冗余保险</b>：一旦有人去掉 sqrt 硬顶、调大簇数上限、或改动小簇并入逻辑，
 * 它会立刻兜住，保证建树仍然收敛而不是无限往上长。
 */
public final class TreeBuildGuard {

    /** 停止原因。 */
    public enum Stop {
        /** 本层只剩 1 个节点，它即根 */
        CONVERGED,
        /** 簇数 ≤ 1，无法再分 */
        CANNOT_SPLIT,
        /** 簇数 ≥ 当前层节点数，新层不会更小 → 递归不收敛 */
        NOT_SHRINKING,
        /** 所有簇的成员数都小于阈值，没有任何一个簇真正合并了 ≥2 个节点 */
        NO_REAL_MERGE
    }

    private TreeBuildGuard() {
    }

    /**
     * @param currentCount   当前层节点数
     * @param clusterSizes   本层聚类结果各簇的成员数（null/空表示未产出簇）
     * @param minClusterSize 配置的最小簇规模
     * @return {@code null} 表示可以继续向上建；否则返回必须停止的原因
     */
    public static Stop reasonToStop(int currentCount, List<Integer> clusterSizes, int minClusterSize) {
        if (currentCount <= 1) {
            return Stop.CONVERGED;
        }
        if (clusterSizes == null || clusterSizes.isEmpty() || clusterSizes.size() <= 1) {
            return Stop.CANNOT_SPLIT;
        }
        if (clusterSizes.size() >= currentCount) {
            // 新层节点数不会减少 → 再往上只会得到同样规模的层，必须立刻停
            return Stop.NOT_SHRINKING;
        }
        int threshold = Math.max(2, minClusterSize);
        int maxSize = 0;
        for (Integer size : clusterSizes) {
            if (size != null && size > maxSize) {
                maxSize = size;
            }
        }
        if (maxSize < threshold) {
            return Stop.NO_REAL_MERGE;
        }
        return null;
    }
}
