package com.yanglizi.docraptor.mapper;

import com.yanglizi.docraptor.domain.entity.EvalCase;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.UUID;

@Mapper
public interface EvalCaseMapper {

    @Insert("""
            INSERT INTO eval_case (id, knowledge_base_id, name, query_text, remark, enabled)
            VALUES (#{id}, #{knowledgeBaseId}, #{name}, #{queryText}, #{remark}, #{enabled})
            """)
    int insert(EvalCase evalCase);

    @Select("SELECT * FROM eval_case WHERE id = #{id}")
    EvalCase selectById(UUID id);

    @Select("""
            <script>
            SELECT * FROM eval_case
            <where>
              <if test="knowledgeBaseId != null">AND knowledge_base_id = #{knowledgeBaseId}</if>
              <if test="enabled != null">AND enabled = #{enabled}</if>
            </where>
            ORDER BY created_at DESC
            LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<EvalCase> selectPage(@Param("knowledgeBaseId") UUID knowledgeBaseId,
                              @Param("enabled") Boolean enabled,
                              @Param("offset") int offset,
                              @Param("limit") int limit);

    @Select("""
            <script>
            SELECT count(*) FROM eval_case
            <where>
              <if test="knowledgeBaseId != null">AND knowledge_base_id = #{knowledgeBaseId}</if>
              <if test="enabled != null">AND enabled = #{enabled}</if>
            </where>
            </script>
            """)
    long countPage(@Param("knowledgeBaseId") UUID knowledgeBaseId, @Param("enabled") Boolean enabled);

    @Select("""
            SELECT * FROM eval_case
            WHERE knowledge_base_id = #{kbId} AND enabled = TRUE
            ORDER BY created_at
            """)
    List<EvalCase> selectEnabledByKnowledgeBase(UUID kbId);

    /** 注解 SQL 必须包 &lt;script&gt; 才会解析 &lt;foreach&gt;（否则 BindingException: Parameter 'id' not found）。 */
    @Select("""
            <script>
            SELECT * FROM eval_case
            WHERE id IN
            <foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
            ORDER BY created_at
            </script>
            """)
    List<EvalCase> selectByIds(@Param("ids") List<UUID> ids);

    /**
     * 同一知识库内用例名唯一（uk_eval_case_kb_name）。
     * 注解 SQL 不做 XML 解析，{@code <>} 直接写；excludeId 可为 null，需 CAST 成 uuid。
     */
    @Select("""
            SELECT count(*) FROM eval_case
            WHERE knowledge_base_id = #{kbId} AND lower(name) = lower(#{name})
              AND (CAST(#{excludeId} AS uuid) IS NULL OR id <> CAST(#{excludeId} AS uuid))
            """)
    long countByName(@Param("kbId") UUID kbId, @Param("name") String name, @Param("excludeId") UUID excludeId);

    @Update("""
            UPDATE eval_case
            SET name = #{name}, query_text = #{queryText}, remark = #{remark}, enabled = #{enabled}
            WHERE id = #{id}
            """)
    int update(EvalCase evalCase);

    @Update("""
            UPDATE eval_case
            SET last_recall_at_k = #{recall}, last_mrr = #{mrr}, last_evaluated_at = now()
            WHERE id = #{id}
            """)
    int updateLastMetrics(@Param("id") UUID id, @Param("recall") Double recall, @Param("mrr") Double mrr);

    @Delete("DELETE FROM eval_case WHERE id = #{id}")
    int deleteById(UUID id);

    @Select("SELECT count(*) FROM eval_case WHERE knowledge_base_id = #{kbId}")
    long countByKnowledgeBase(UUID kbId);

    @Delete("DELETE FROM eval_case WHERE knowledge_base_id = #{kbId}")
    int deleteByKnowledgeBase(UUID kbId);
}
