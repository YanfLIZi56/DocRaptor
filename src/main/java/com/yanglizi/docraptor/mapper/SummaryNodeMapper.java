package com.yanglizi.docraptor.mapper;

import com.yanglizi.docraptor.domain.dto.ChunkRow;
import com.yanglizi.docraptor.domain.dto.LevelCount;
import com.yanglizi.docraptor.domain.dto.NodeParentPair;
import com.yanglizi.docraptor.domain.dto.SearchRow;
import com.yanglizi.docraptor.domain.entity.SummaryNode;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.UUID;

/**
 * summary_nodes 统一节点表访问。复杂 SQL（向量检索 / BM25 检索 / 树查询 / 递归折叠）全部在
 * {@code resources/mapper/SummaryNodeMapper.xml}。
 *
 * <p><b>向量参数写法</b>：统一用「字符串字面量 + CAST(? AS vector)」，
 * 由 {@code VectorUtils.toLiteral(float[])} 生成 {@code [0.1,0.2,...]}。
 */
@Mapper
public interface SummaryNodeMapper {

    /* ------------------------------ 写入 ------------------------------ */

    int insertLeaf(SummaryNode node);

    int insertLeaves(@Param("list") List<SummaryNode> nodes);

    int insertSummary(SummaryNode node);

    int updateEmbedding(@Param("id") UUID id, @Param("embedding") String embedding);

    int updateParentForIds(@Param("ids") List<UUID> ids, @Param("parentId") UUID parentId);

    /**
     * 把该文档全部叶子块的 parent_id 置空。
     *
     * <p><b>调用顺序有硬性要求</b>：必须先调本方法，再调 {@link #deleteSummariesByDocument}。
     * 原因见 {@code RaptorTreeService#build} 第 0 步的注释：自引用外键是 ON DELETE CASCADE，
     * 直接删 SUMMARY 会级联删掉叶子，而叶子被评估用例以 RESTRICT 引用时会导致整个重建失败。
     */
    int resetLeafParents(@Param("documentId") UUID documentId);

    /** 删除该文档全部摘要节点。必须在 {@link #resetLeafParents} <b>之后</b>调用。 */
    int deleteSummariesByDocument(@Param("documentId") UUID documentId);

    int updateMetadata(@Param("id") UUID id, @Param("metadata") String metadata);

    /* ------------------------------ 建树读取 ------------------------------ */

    List<SummaryNode> selectLeavesWithEmbedding(@Param("documentId") UUID documentId);

    /** 断点续跑用：只取尚未向量化的叶子块。 */
    List<SummaryNode> selectLeavesWithoutEmbedding(@Param("documentId") UUID documentId);

    List<SummaryNode> selectLeavesByDocument(@Param("documentId") UUID documentId);

    List<SummaryNode> selectAllByDocument(@Param("documentId") UUID documentId);

    List<SummaryNode> selectSummaryNodesByDocument(@Param("documentId") UUID documentId);

    List<SummaryNode> selectByIds(@Param("ids") List<UUID> ids);

    /* ------------------------------ 检索 ------------------------------ */

    /**
     * 向量路：{@code 1 - (embedding <=> ?::vector)} 作为相似度，按余弦距离升序取前 limit 条。
     * 只返回「文档未禁用」的节点。
     */
    List<SearchRow> vectorSearch(@Param("kbId") UUID kbId,
                                 @Param("queryVector") String queryVector,
                                 @Param("limit") int limit,
                                 @Param("leafOnly") boolean leafOnly,
                                 @Param("specifiedLevel") boolean specifiedLevel,
                                 @Param("levels") List<Integer> levels,
                                 @Param("documentIds") List<UUID> documentIds);

    /** BM25 路：{@code id @@@ paradedb.match('content', ?)} + {@code paradedb.score(id)}（参数绑定，无注入风险）。 */
    List<SearchRow> bm25Search(@Param("kbId") UUID kbId,
                               @Param("query") String query,
                               @Param("limit") int limit,
                               @Param("leafOnly") boolean leafOnly,
                               @Param("specifiedLevel") boolean specifiedLevel,
                               @Param("levels") List<Integer> levels,
                               @Param("documentIds") List<UUID> documentIds);

    /** 折叠树用：知识库内全部节点的 id → parent_id。 */
    List<NodeParentPair> selectIdParentPairs(@Param("kbId") UUID kbId);

    /**
     * 事务内设置 HNSW 检索广度（{@code SET LOCAL hnsw.ef_search}，需在事务块内生效）。
     * value 来自 int 配置，用 ${} 直接拼字面量（PG 的 SET 不支持占位符绑定）。
     */
    @org.apache.ibatis.annotations.Update("SET LOCAL hnsw.ef_search = ${value}")
    int setHnswEfSearch(@Param("value") int value);

    /* ------------------------------ 分块列表 ------------------------------ */

    List<ChunkRow> selectLeafChunks(@Param("documentId") UUID documentId,
                                    @Param("kbId") UUID kbId,
                                    @Param("offset") int offset,
                                    @Param("limit") int limit);

    long countLeafChunks(@Param("documentId") UUID documentId, @Param("kbId") UUID kbId);

    /* ------------------------------ 统计 ------------------------------ */

    List<LevelCount> selectLevelCounts(@Param("documentId") UUID documentId);

    long countByKnowledgeBase(@Param("kbId") UUID kbId);

    long countByDocumentAndType(@Param("documentId") UUID documentId, @Param("nodeType") String nodeType);

    /** 供分页/统计使用的计数：某文档下指定层级的节点数。 */
    long countByDocumentAndLevel(@Param("documentId") UUID documentId, @Param("level") int level);

    /** 树根（level 最高的、parent_id 为 NULL 的 SUMMARY 节点）的 metadata，用于取 builtAt / buildDurationMs。 */
    String selectRootMetadata(@Param("documentId") UUID documentId);

    /** metadata 中某个布尔标记为 true 的节点数（用于 degradedSummaryCount / forcedRoot）。 */
    long countByMetadataFlag(@Param("documentId") UUID documentId, @Param("key") String key);

    /** 摘要节点平均簇规模。 */
    Double selectAvgClusterSize(@Param("documentId") UUID documentId);

    /**
     * 删除知识库前显式按顺序清理节点。
     * 原因：{@code eval_case_expected_chunk.node_id} 是 {@code ON DELETE RESTRICT}，
     * PostgreSQL 的 RESTRICT 是立即检查而非延迟到语句末，靠 FK CASCADE 的顺序不保证能先清掉引用行，
     * 所以删除知识库时由 Service 层在事务内按「期望块 → 用例 → 任务 → 节点 → 文档 → 知识库」显式清理。
     */
    int deleteByKnowledgeBase(@Param("kbId") UUID kbId);
}
