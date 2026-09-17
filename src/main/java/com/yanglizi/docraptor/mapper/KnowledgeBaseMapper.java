package com.yanglizi.docraptor.mapper;

import com.yanglizi.docraptor.domain.entity.KnowledgeBase;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.UUID;

@Mapper
public interface KnowledgeBaseMapper {

    @Insert("""
            INSERT INTO knowledge_base
                (id, name, description, chunk_size, chunk_overlap, chunk_strategy,
                 document_count, node_count, embedding_model, embedding_dimension)
            VALUES
                (#{id}, #{name}, #{description}, #{chunkSize}, #{chunkOverlap}, #{chunkStrategy},
                 #{documentCount}, #{nodeCount}, #{embeddingModel}, #{embeddingDimension})
            """)
    int insert(KnowledgeBase kb);

    @Select("SELECT * FROM knowledge_base WHERE id = #{id}")
    KnowledgeBase selectById(UUID id);

    @Select("""
            <script>
            SELECT * FROM knowledge_base
            <where>
              <if test="keyword != null and keyword != ''">
                AND lower(name) LIKE lower(concat('%', #{keyword}, '%'))
              </if>
            </where>
            ORDER BY created_at DESC
            LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<KnowledgeBase> selectPage(@Param("keyword") String keyword,
                                   @Param("offset") int offset,
                                   @Param("limit") int limit);

    @Select("""
            <script>
            SELECT count(*) FROM knowledge_base
            <where>
              <if test="keyword != null and keyword != ''">
                AND lower(name) LIKE lower(concat('%', #{keyword}, '%'))
              </if>
            </where>
            </script>
            """)
    long countPage(@Param("keyword") String keyword);

    /**
     * 重名检查；excludeId 用于 PUT 时排除自身。
     *
     * <p>注意：注解形式的 SQL 不做 XML 解析，所以 {@code <>} 必须直接写（不能写 {@code &lt;&gt;}）；
     * 且 excludeId 可为 null，必须 CAST 成 uuid，否则 PG 无法推断 NULL 参数类型。
     */
    @Select("""
            SELECT count(*) FROM knowledge_base
            WHERE lower(name) = lower(#{name})
              AND (CAST(#{excludeId} AS uuid) IS NULL OR id <> CAST(#{excludeId} AS uuid))
            """)
    long countByName(@Param("name") String name, @Param("excludeId") UUID excludeId);

    /**
     * 更新名称与描述。契约 4.4 的「null 表示不改、"" 表示清空」语义由 Service 层解析后传入最终值，
     * SQL 只做覆盖（description 允许为 null）。
     */
    @Update("""
            UPDATE knowledge_base
            SET name = #{name},
                description = #{description}
            WHERE id = #{id}
            """)
    int updateNameAndDescription(@Param("id") UUID id,
                                 @Param("name") String name,
                                 @Param("description") String description);

    @Delete("DELETE FROM knowledge_base WHERE id = #{id}")
    int deleteById(UUID id);

    /** 重算冗余统计 document_count / node_count。 */
    @Update("""
            UPDATE knowledge_base kb
            SET document_count = (SELECT count(*) FROM document d WHERE d.knowledge_base_id = kb.id),
                node_count     = (SELECT count(*) FROM summary_nodes n WHERE n.knowledge_base_id = kb.id)
            WHERE kb.id = #{id}
            """)
    int refreshStats(UUID id);
}
