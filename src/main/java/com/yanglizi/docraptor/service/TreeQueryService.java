package com.yanglizi.docraptor.service;

import com.yanglizi.docraptor.common.BizException;
import com.yanglizi.docraptor.common.ErrorCode;
import com.yanglizi.docraptor.common.JsonUtils;
import com.yanglizi.docraptor.common.TimeUtils;
import com.yanglizi.docraptor.config.DocRaptorProperties;
import com.yanglizi.docraptor.domain.dto.LevelCount;
import com.yanglizi.docraptor.domain.entity.DocumentEntity;
import com.yanglizi.docraptor.domain.entity.SummaryNode;
import com.yanglizi.docraptor.dto.response.TreeVO;
import com.yanglizi.docraptor.mapper.DocumentMapper;
import com.yanglizi.docraptor.mapper.SummaryNodeMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 模块2：树结构查询与统计（契约 5.2 / 5.3）。 */
@Slf4j
@Service
public class TreeQueryService {

    private static final int CONTENT_PREVIEW = 200;

    private final SummaryNodeMapper nodeMapper;
    private final DocumentMapper documentMapper;
    private final DocRaptorProperties props;

    public TreeQueryService(SummaryNodeMapper nodeMapper, DocumentMapper documentMapper,
                            DocRaptorProperties props) {
        this.nodeMapper = nodeMapper;
        this.documentMapper = documentMapper;
        this.props = props;
    }

    /** 契约 5.2。未建树时抛 40400（data=null）。 */
    public TreeVO getTree(String documentId, String format, Boolean withContent, Boolean includeLeaves) {
        UUID docId = TimeUtils.parseUuid(documentId, "documentId");
        DocumentEntity doc = documentMapper.selectById(docId);
        if (doc == null) {
            throw BizException.of(ErrorCode.DOCUMENT_NOT_FOUND);
        }
        List<SummaryNode> all = nodeMapper.selectAllByDocument(docId);
        long summaryCount = all.stream().filter(n -> "SUMMARY".equals(n.getNodeType())).count();
        if (summaryCount == 0) {
            // 该文档尚未建树
            throw BizException.of(ErrorCode.RESOURCE_NOT_FOUND, null, "RaptorTree", docId);
        }

        boolean fullContent = withContent == null || withContent;
        boolean withLeaves = includeLeaves == null || includeLeaves;

        Map<String, List<SummaryNode>> childrenOf = new HashMap<>();
        Map<String, Integer> childCounts = new HashMap<>();
        SummaryNode root = null;
        for (SummaryNode n : all) {
            String pid = n.getParentId() == null ? null : n.getParentId().toString();
            if (pid == null) {
                if ("SUMMARY".equals(n.getNodeType())
                        && (root == null || n.getLevel() > root.getLevel())) {
                    root = n;
                }
                continue;
            }
            childrenOf.computeIfAbsent(pid, k -> new ArrayList<>()).add(n);
            childCounts.merge(pid, 1, Integer::sum);
        }
        if (root == null) {
            throw BizException.of(ErrorCode.RESOURCE_NOT_FOUND, null, "RaptorTree", docId);
        }

        TreeVO vo = new TreeVO();
        vo.setDocumentId(documentId);
        vo.setDocumentName(doc.getFileName());
        vo.setKnowledgeBaseId(doc.getKnowledgeBaseId() == null ? null : doc.getKnowledgeBaseId().toString());
        vo.setActualDepth((int) all.stream().filter(n -> "SUMMARY".equals(n.getNodeType()))
                .map(n -> (int) n.getLevel()).max(Comparator.naturalOrder()).orElse(0));
        vo.setMaxLevel(resolveMaxLevel(docId, all));
        vo.setRootNodeId(root.getId().toString());
        vo.setNodeCount(all.size());
        vo.setSummaryNodeCount((int) summaryCount);
        vo.setLeafNodeCount(all.size() - (int) summaryCount);
        Map<String, Object> rootMeta = JsonUtils.toMap(root.getMetadata());
        Object builtAt = rootMeta.get("builtAt");
        vo.setBuiltAt(builtAt instanceof Number num ? num.longValue() : TimeUtils.toMillis(root.getCreatedAt()));

        if ("flat".equalsIgnoreCase(format)) {
            List<TreeVO.TreeNodeVO> nodes = new ArrayList<>();
            for (SummaryNode n : all) {
                if (!withLeaves && "LEAF".equals(n.getNodeType())) {
                    continue;
                }
                nodes.add(toNodeVO(n, childCounts.getOrDefault(n.getId().toString(), 0), fullContent, false));
            }
            nodes.sort(Comparator.comparing((TreeVO.TreeNodeVO n) -> n.getLevel())
                    .thenComparing(n -> n.getStartChunkIndex() == null ? 0 : n.getStartChunkIndex()));
            vo.setNodes(nodes);
        } else {
            vo.setRoot(buildNested(root, childrenOf, childCounts, fullContent, withLeaves));
        }
        return vo;
    }

    private TreeVO.TreeNodeVO buildNested(SummaryNode node, Map<String, List<SummaryNode>> childrenOf,
                                          Map<String, Integer> childCounts, boolean fullContent,
                                          boolean withLeaves) {
        String id = node.getId().toString();
        TreeVO.TreeNodeVO vo = toNodeVO(node, childCounts.getOrDefault(id, 0), fullContent, true);
        List<TreeVO.TreeNodeVO> children = new ArrayList<>();
        for (SummaryNode child : childrenOf.getOrDefault(id, List.of())) {
            if (!withLeaves && "LEAF".equals(child.getNodeType())) {
                continue;
            }
            children.add(buildNested(child, childrenOf, childCounts, fullContent, withLeaves));
        }
        children.sort(Comparator.comparing(c -> c.getStartChunkIndex() == null ? 0 : c.getStartChunkIndex()));
        vo.setChildren(children);
        return vo;
    }

    private static TreeVO.TreeNodeVO toNodeVO(SummaryNode n, int childCount, boolean fullContent, boolean withChildren) {
        TreeVO.TreeNodeVO vo = new TreeVO.TreeNodeVO();
        vo.setNodeId(n.getId().toString());
        vo.setNodeType(n.getNodeType());
        vo.setLevel(n.getLevel());
        vo.setParentId(n.getParentId() == null ? null : n.getParentId().toString());
        vo.setChunkIndex(n.getChunkIndex());
        vo.setStartChunkIndex(n.getStartChunkIndex());
        vo.setEndChunkIndex(n.getEndChunkIndex());
        vo.setCharCount(n.getCharCount());
        vo.setSummary(n.getSummary());
        String content = n.getContent();
        if (!fullContent && content != null && content.length() > CONTENT_PREVIEW) {
            content = content.substring(0, CONTENT_PREVIEW);
        }
        vo.setContent(content);
        vo.setClusterLabel(n.getClusterLabel());
        vo.setClusterSize(n.getClusterSize());
        vo.setChildCount(childCount);
        vo.setDocumentId(n.getDocumentId() == null ? null : n.getDocumentId().toString());
        vo.setHasEmbedding(n.getEmbedding() != null && !n.getEmbedding().isBlank());
        vo.setMetadata(JsonUtils.toMap(n.getMetadata()));
        if (withChildren) {
            vo.setChildren(new ArrayList<>());
        }
        return vo;
    }

    /** 契约 5.3 树统计。 */
    public TreeVO.TreeStatsVO stats(String documentId) {
        UUID docId = TimeUtils.parseUuid(documentId, "documentId");
        DocumentEntity doc = documentMapper.selectById(docId);
        if (doc == null) {
            throw BizException.of(ErrorCode.DOCUMENT_NOT_FOUND);
        }
        List<SummaryNode> all = nodeMapper.selectAllByDocument(docId);
        List<LevelCount> levelCounts = nodeMapper.selectLevelCounts(docId);

        TreeVO.TreeStatsVO vo = new TreeVO.TreeStatsVO();
        vo.setDocumentId(documentId);
        vo.setTreeStatus(doc.getTreeStatus());
        int actualDepth = levelCounts.stream()
                .map(lc -> lc.getLevel() == null ? 0 : (int) lc.getLevel())
                .max(Comparator.naturalOrder()).orElse(0);
        vo.setActualDepth(actualDepth);
        vo.setMaxLevel(resolveMaxLevel(docId, all));

        int rootCount = 0;
        for (SummaryNode n : all) {
            if ("SUMMARY".equals(n.getNodeType()) && n.getParentId() == null) {
                rootCount++;
            }
        }
        vo.setRootCount(rootCount);
        vo.setHasUniqueRoot(rootCount == 1);

        List<TreeVO.LevelCountVO> lcs = new ArrayList<>(levelCounts.size());
        for (LevelCount lc : levelCounts) {
            TreeVO.LevelCountVO v = new TreeVO.LevelCountVO();
            v.setLevel(lc.getLevel());
            v.setCount(lc.getCount());
            lcs.add(v);
        }
        vo.setLevelCounts(lcs);

        Double avg = nodeMapper.selectAvgClusterSize(docId);
        vo.setAvgClusterSize(avg == null ? null : Math.round(avg * 100.0) / 100.0);
        vo.setDegradedSummaryCount((int) nodeMapper.countByMetadataFlag(docId, "degraded"));
        vo.setForcedRoot(nodeMapper.countByMetadataFlag(docId, "forcedRoot") > 0);

        Map<String, Object> rootMeta = rootMetadata(docId);
        Object builtAt = rootMeta.get("builtAt");
        vo.setBuiltAt(builtAt instanceof Number num ? num.longValue() : null);
        Object duration = rootMeta.get("buildDurationMs");
        vo.setBuildDurationMs(duration instanceof Number num ? num.longValue() : null);
        return vo;
    }

    private Map<String, Object> rootMetadata(UUID docId) {
        String json = nodeMapper.selectRootMetadata(docId);
        return json == null ? new LinkedHashMap<>() : JsonUtils.toMap(json);
    }

    private int resolveMaxLevel(UUID docId, List<SummaryNode> all) {
        Map<String, Object> meta = rootMetadata(docId);
        Object v = meta.get("maxLevel");
        if (v instanceof Number num) {
            return num.intValue();
        }
        for (SummaryNode n : all) {
            if ("SUMMARY".equals(n.getNodeType())) {
                Object lv = JsonUtils.toMap(n.getMetadata()).get("maxLevel");
                if (lv instanceof Number num2) {
                    return num2.intValue();
                }
            }
        }
        return props.getRaptor().getMaxLevel();
    }
}
