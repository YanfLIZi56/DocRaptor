package com.yanglizi.docraptor.algorithm;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 折叠树（scope=ALL_LEVELS）：同一条祖先链上只保留最终排名最高的那个节点。
 *
 * <p>算法（docs/01-architecture.md 6.3）：
 * <pre>
 * 按融合后的顺序遍历候选；
 * 若候选 n 与某个已保留节点 m 存在「祖先-后代」关系（m ∈ ancestors(n) 或 n ∈ ancestors(m)），
 * 则丢弃 n 并记下 collapsedByNodeId = m；否则保留 n。
 * </pre>
 *
 * <p>实现上维护「已保留节点集合 kept」与「kept 中全部节点的祖先集合 ancestorsOfKept」，
 * 单次遍历即可判定，复杂度 O(候选数 × 树深)。
 */
public final class TreeFolder {

    private TreeFolder() {
    }

    /**
     * @param orderedCandidateIds 已按最终排名排好序的候选节点 ID
     * @param parentOf            nodeId → parentId 的完整映射（root 的 parentId 为 null）
     */
    public static Result fold(List<String> orderedCandidateIds, Map<String, String> parentOf) {
        List<String> kept = new ArrayList<>();
        Map<String, String> collapsedBy = new LinkedHashMap<>();
        Set<String> keptSet = new HashSet<>();
        Set<String> ancestorsOfKept = new HashSet<>();

        for (String id : orderedCandidateIds) {
            List<String> ancestors = ancestorsOf(id, parentOf);

            // 1) 候选的祖先里已有保留节点 → 候选是其后代，被折叠
            String keeper = null;
            for (String a : ancestors) {
                if (keptSet.contains(a)) {
                    keeper = a;
                    break;
                }
            }
            // 2) 候选本身是某个保留节点的祖先 → 也被折叠
            if (keeper == null && ancestorsOfKept.contains(id)) {
                keeper = findKeptAncestor(id, kept, parentOf);
            }

            if (keeper != null) {
                collapsedBy.put(id, keeper);
                continue;
            }

            kept.add(id);
            keptSet.add(id);
            ancestorsOfKept.addAll(ancestors);
        }
        return new Result(kept, collapsedBy);
    }

    /** 向上收集祖先（不含自身），带环保护。 */
    static List<String> ancestorsOf(String id, Map<String, String> parentOf) {
        List<String> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        seen.add(id);
        String cur = id;
        while (true) {
            String parent = parentOf.get(cur);
            if (parent == null || seen.contains(parent)) {
                break;
            }
            out.add(parent);
            seen.add(parent);
            cur = parent;
        }
        return out;
    }

    /** 找到 kept 里那个「以 id 为祖先」的节点。 */
    private static String findKeptAncestor(String id, List<String> kept, Map<String, String> parentOf) {
        for (String m : kept) {
            if (ancestorsOf(m, parentOf).contains(id)) {
                return m;
            }
        }
        return null;
    }

    /** 折叠结果：kept 为保留顺序；collapsedBy 为「被折叠节点 → 折叠它的保留节点」。 */
    public record Result(List<String> kept, Map<String, String> collapsedBy) {

        public int collapsedCount() {
            return collapsedBy.size();
        }
    }
}
