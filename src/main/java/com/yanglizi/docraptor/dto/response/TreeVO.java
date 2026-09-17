package com.yanglizi.docraptor.dto.response;

import lombok.Data;

import java.util.List;
import java.util.Map;

/** 树结构（契约 5.2）。 */
@Data
public class TreeVO {
    private String documentId;
    private String documentName;
    private String knowledgeBaseId;
    private Integer maxLevel;
    private Integer actualDepth;
    private String rootNodeId;
    private Integer nodeCount;
    private Integer summaryNodeCount;
    private Integer leafNodeCount;
    private Long builtAt;
    /** format=nested 时非空 */
    private TreeNodeVO root;
    /** format=flat 时非空（元素不含 children） */
    private List<TreeNodeVO> nodes;

    /** 树节点；nested 模式带 children，flat 模式 children 为 null。 */
    @Data
    public static class TreeNodeVO {
        private String nodeId;
        private String nodeType;
        private Short level;
        private String parentId;
        private Integer chunkIndex;
        private Integer startChunkIndex;
        private Integer endChunkIndex;
        private Integer charCount;
        private String summary;
        private String content;
        private Integer clusterLabel;
        private Integer clusterSize;
        private Integer childCount;
        private String documentId;
        private Boolean hasEmbedding;
        private Map<String, Object> metadata;
        private List<TreeNodeVO> children;
    }

    /** 树统计（契约 5.3）。 */
    @Data
    public static class TreeStatsVO {
        private String documentId;
        private String treeStatus;
        private Integer actualDepth;
        private Integer maxLevel;
        private Integer rootCount;
        private Boolean hasUniqueRoot;
        private List<LevelCountVO> levelCounts;
        private Double avgClusterSize;
        private Integer degradedSummaryCount;
        private Boolean forcedRoot;
        private Long builtAt;
        private Long buildDurationMs;
    }

    @Data
    public static class LevelCountVO {
        private Short level;
        private Integer count;
    }
}
