package com.yanglizi.docraptor.mapper;

import com.yanglizi.docraptor.domain.entity.DocumentEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.UUID;

/**
 * document 表访问。
 *
 * <p><b>D5 约束</b>：没有任何修改正文/文本块的 SQL；{@code enabled} 是唯一可写业务字段。
 */
@Mapper
public interface DocumentMapper {

    @Insert("""
            INSERT INTO document
                (id, knowledge_base_id, file_name, stored_path, file_type, file_size, content_hash,
                 parse_status, chunk_status, embed_status, tree_status, char_count, chunk_count,
                 enabled, metadata)
            VALUES
                (#{id}, #{knowledgeBaseId}, #{fileName}, #{storedPath}, #{fileType}, #{fileSize}, #{contentHash},
                 #{parseStatus}, #{chunkStatus}, #{embedStatus}, #{treeStatus}, #{charCount}, #{chunkCount},
                 #{enabled}, CAST(#{metadata} AS jsonb))
            """)
    int insert(DocumentEntity doc);

    @Select("""
            SELECT d.*, kb.name AS knowledge_base_name
            FROM document d
            JOIN knowledge_base kb ON kb.id = d.knowledge_base_id
            WHERE d.id = #{id}
            """)
    DocumentEntity selectById(UUID id);

    @Select("""
            <script>
            SELECT d.*, kb.name AS knowledge_base_name
            FROM document d
            JOIN knowledge_base kb ON kb.id = d.knowledge_base_id
            <where>
              <if test="knowledgeBaseId != null">AND d.knowledge_base_id = #{knowledgeBaseId}</if>
              <if test="enabled != null">AND d.enabled = #{enabled}</if>
              <if test="treeStatus != null and treeStatus != ''">AND d.tree_status = #{treeStatus}</if>
              <if test="keyword != null and keyword != ''">
                AND lower(d.file_name) LIKE lower(concat('%', #{keyword}, '%'))
              </if>
            </where>
            ORDER BY d.created_at DESC
            LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<DocumentEntity> selectPage(@Param("knowledgeBaseId") UUID knowledgeBaseId,
                                    @Param("enabled") Boolean enabled,
                                    @Param("treeStatus") String treeStatus,
                                    @Param("keyword") String keyword,
                                    @Param("offset") int offset,
                                    @Param("limit") int limit);

    @Select("""
            <script>
            SELECT count(*)
            FROM document d
            <where>
              <if test="knowledgeBaseId != null">AND d.knowledge_base_id = #{knowledgeBaseId}</if>
              <if test="enabled != null">AND d.enabled = #{enabled}</if>
              <if test="treeStatus != null and treeStatus != ''">AND d.tree_status = #{treeStatus}</if>
              <if test="keyword != null and keyword != ''">
                AND lower(d.file_name) LIKE lower(concat('%', #{keyword}, '%'))
              </if>
            </where>
            </script>
            """)
    long countPage(@Param("knowledgeBaseId") UUID knowledgeBaseId,
                   @Param("enabled") Boolean enabled,
                   @Param("treeStatus") String treeStatus,
                   @Param("keyword") String keyword);

    /** 唯一允许的业务写操作：启用/禁用。 */
    @Update("UPDATE document SET enabled = #{enabled} WHERE id = #{id}")
    int updateEnabled(@Param("id") UUID id, @Param("enabled") boolean enabled);

    @Update("""
            UPDATE document
            SET parse_status = #{status}, char_count = #{charCount}, parse_error = #{parseError}
            WHERE id = #{id}
            """)
    int updateParseResult(@Param("id") UUID id, @Param("status") String status,
                          @Param("charCount") int charCount, @Param("parseError") String parseError);

    @Update("""
            UPDATE document
            SET chunk_status = #{status}, chunk_count = #{chunkCount}
            WHERE id = #{id}
            """)
    int updateChunkResult(@Param("id") UUID id, @Param("status") String status, @Param("chunkCount") int chunkCount);

    @Update("UPDATE document SET embed_status = #{status} WHERE id = #{id}")
    int updateEmbedStatus(@Param("id") UUID id, @Param("status") String status);

    @Update("UPDATE document SET tree_status = #{status} WHERE id = #{id}")
    int updateTreeStatus(@Param("id") UUID id, @Param("status") String status);

    /**
     * 建树失败：<b>只动 tree_status</b>，绝不碰 parse/chunk/embed。
     *
     * <p><b>为什么必须单独一个方法（实测踩到的自锁死坑）</b>：建树失败时叶子块与其向量是<b>完好</b>的
     * （建树只增删 SUMMARY 节点），如果把 embed_status 一起标成 FAILED，
     * 之后 {@code POST /api/raptor/trees} 会一直返回 40905「文档尚未完成向量化」，
     * 而重新向量化又没有入口 —— 文档就被一次失败的重建永久锁死了。
     * 错误摘要写 {@code parse_error}（DDL 注释里该列就是「解析或流水线失败时的异常摘要」）。
     */
    @Update("UPDATE document SET tree_status = #{status}, parse_error = #{error} WHERE id = #{id}")
    int updateTreeResult(@Param("id") UUID id, @Param("status") String status, @Param("error") String error);

    @Update("UPDATE document SET metadata = CAST(#{metadata} AS jsonb) WHERE id = #{id}")
    int updateMetadata(@Param("id") UUID id, @Param("metadata") String metadata);

    /**
     * 导入流水线失败：只把「尚未成功」的步骤标成失败，<b>已经 SUCCESS 的步骤保持不变</b>。
     *
     * <p>这样架构 4.2 的「从最后一个 SUCCESS 的步骤之后重跑」才有依据；
     * 也避免「EMBED 阶段失败却把已经成功的 PARSE/CHUNK 也标成 FAILED」这类误导性状态。
     * 注意本方法是给 <b>DOC_IMPORT 整条流水线</b>用的；建树单独失败请用 {@link #updateTreeResult}。
     */
    @Update("""
            UPDATE document
            SET parse_status = CASE WHEN parse_status = 'SUCCESS' THEN 'SUCCESS' ELSE #{status} END,
                chunk_status = CASE WHEN chunk_status = 'SUCCESS' THEN 'SUCCESS' ELSE #{status} END,
                embed_status = CASE WHEN embed_status = 'SUCCESS' THEN 'SUCCESS' ELSE #{status} END,
                tree_status  = CASE WHEN tree_status  = 'SUCCESS' THEN 'SUCCESS' ELSE #{status} END,
                parse_error = #{error}
            WHERE id = #{id}
            """)
    int markPipelineFailed(@Param("id") UUID id, @Param("status") String status, @Param("error") String error);

    /** 按知识库统计（删除知识库前取删除计数用）。 */
    @Select("SELECT count(*) FROM document WHERE knowledge_base_id = #{kbId}")
    long countByKnowledgeBase(UUID kbId);

    @Select("SELECT id FROM document WHERE knowledge_base_id = #{kbId}")
    List<UUID> selectIdsByKnowledgeBase(UUID kbId);

    @Select("SELECT count(*) FROM document WHERE knowledge_base_id = #{kbId} AND enabled = TRUE")
    long countEnabledByKnowledgeBase(UUID kbId);

    @org.apache.ibatis.annotations.Delete("DELETE FROM document WHERE knowledge_base_id = #{kbId}")
    int deleteByKnowledgeBase(UUID kbId);
}
