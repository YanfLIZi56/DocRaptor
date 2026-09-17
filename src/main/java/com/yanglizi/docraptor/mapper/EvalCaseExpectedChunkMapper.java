package com.yanglizi.docraptor.mapper;

import com.yanglizi.docraptor.domain.entity.EvalCaseExpectedChunk;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.UUID;

@Mapper
public interface EvalCaseExpectedChunkMapper {

    @Insert("""
            INSERT INTO eval_case_expected_chunk
                (id, eval_case_id, node_id, document_id, chunk_index, relevance)
            VALUES
                (#{id}, #{evalCaseId}, #{nodeId}, #{documentId}, #{chunkIndex}, #{relevance})
            """)
    int insert(EvalCaseExpectedChunk row);

    /**
     * 批量插入期望块。
     *
     * <p><b>必须包 &lt;script&gt;</b>：MyBatis 的注解 SQL 默认按纯文本处理，只有包在 &lt;script&gt; 里
     * 才会解析 &lt;foreach&gt;/&lt;if&gt; 等动态标签；否则会在设置参数时报
     * {@code BindingException: Parameter 'r' not found}（已被端到端验收实测到）。
     */
    @Insert("""
            <script>
            INSERT INTO eval_case_expected_chunk
                (id, eval_case_id, node_id, document_id, chunk_index, relevance)
            VALUES
            <foreach collection="list" item="r" separator=",">
                (#{r.id}, #{r.evalCaseId}, #{r.nodeId}, #{r.documentId}, #{r.chunkIndex}, #{r.relevance})
            </foreach>
            </script>
            """)
    int insertBatch(@Param("list") List<EvalCaseExpectedChunk> rows);

    @Select("""
            SELECT * FROM eval_case_expected_chunk
            WHERE eval_case_id = #{evalCaseId}
            ORDER BY chunk_index NULLS LAST, created_at
            """)
    List<EvalCaseExpectedChunk> selectByCaseId(UUID evalCaseId);

    @Select("""
            <script>
            SELECT * FROM eval_case_expected_chunk
            WHERE eval_case_id IN
            <foreach collection="caseIds" item="id" open="(" separator="," close=")">#{id}</foreach>
            ORDER BY eval_case_id, chunk_index NULLS LAST
            </script>
            """)
    List<EvalCaseExpectedChunk> selectByCaseIds(@Param("caseIds") List<UUID> caseIds);

    @Delete("DELETE FROM eval_case_expected_chunk WHERE eval_case_id = #{evalCaseId}")
    int deleteByCaseId(UUID evalCaseId);

    @Select("SELECT count(*) FROM eval_case_expected_chunk WHERE eval_case_id = #{evalCaseId}")
    long countByCaseId(UUID evalCaseId);

    /** 删除知识库前先清掉期望块（node_id 上的 RESTRICT 是立即检查的，必须先删引用行）。 */
    @Delete("""
            DELETE FROM eval_case_expected_chunk
            WHERE eval_case_id IN (SELECT id FROM eval_case WHERE knowledge_base_id = #{kbId})
            """)
    int deleteByKnowledgeBase(UUID kbId);
}
