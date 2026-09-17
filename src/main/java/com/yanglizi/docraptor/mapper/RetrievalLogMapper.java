package com.yanglizi.docraptor.mapper;

import com.yanglizi.docraptor.domain.entity.RetrievalLog;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.UUID;

@Mapper
public interface RetrievalLogMapper {

    @Insert("""
            INSERT INTO retrieval_log
                (id, knowledge_base_id, log_type, query_text, mode, top_k, similarity_threshold,
                 hybrid_ratio, bm25_weight, rrf_k, scope, scope_levels, document_ids,
                 result_count, result_node_ids, latency_ms, vector_latency_ms, bm25_latency_ms,
                 fusion_latency_ms, success, error_message)
            VALUES
                (#{id}, #{knowledgeBaseId}, #{logType}, #{queryText}, #{mode}, #{topK}, #{similarityThreshold},
                 #{hybridRatio}, #{bm25Weight}, #{rrfK}, #{scope}, #{scopeLevels},
                 CAST(#{documentIds} AS jsonb), #{resultCount}, CAST(#{resultNodeIds} AS jsonb),
                 #{latencyMs}, #{vectorLatencyMs}, #{bm25LatencyMs}, #{fusionLatencyMs},
                 #{success}, #{errorMessage})
            """)
    int insert(RetrievalLog log);

    @Select("""
            <script>
            SELECT * FROM retrieval_log
            <where>
              <if test="knowledgeBaseId != null">AND knowledge_base_id = #{knowledgeBaseId}</if>
              <if test="mode != null and mode != ''">AND mode = #{mode}</if>
              <if test="logType != null and logType != ''">AND log_type = #{logType}</if>
              <if test="queryKeyword != null and queryKeyword != ''">
                AND lower(query_text) LIKE lower(concat('%', #{queryKeyword}, '%'))
              </if>
              <if test="startTime != null">AND created_at &gt;= to_timestamp(#{startTime} / 1000.0)</if>
              <if test="endTime != null">AND created_at &lt;= to_timestamp(#{endTime} / 1000.0)</if>
            </where>
            ORDER BY created_at DESC
            LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<RetrievalLog> selectPage(@Param("knowledgeBaseId") UUID knowledgeBaseId,
                                  @Param("mode") String mode,
                                  @Param("logType") String logType,
                                  @Param("queryKeyword") String queryKeyword,
                                  @Param("startTime") Long startTime,
                                  @Param("endTime") Long endTime,
                                  @Param("offset") int offset,
                                  @Param("limit") int limit);

    @Select("""
            <script>
            SELECT count(*) FROM retrieval_log
            <where>
              <if test="knowledgeBaseId != null">AND knowledge_base_id = #{knowledgeBaseId}</if>
              <if test="mode != null and mode != ''">AND mode = #{mode}</if>
              <if test="logType != null and logType != ''">AND log_type = #{logType}</if>
              <if test="queryKeyword != null and queryKeyword != ''">
                AND lower(query_text) LIKE lower(concat('%', #{queryKeyword}, '%'))
              </if>
              <if test="startTime != null">AND created_at &gt;= to_timestamp(#{startTime} / 1000.0)</if>
              <if test="endTime != null">AND created_at &lt;= to_timestamp(#{endTime} / 1000.0)</if>
            </where>
            </script>
            """)
    long countPage(@Param("knowledgeBaseId") UUID knowledgeBaseId,
                   @Param("mode") String mode,
                   @Param("logType") String logType,
                   @Param("queryKeyword") String queryKeyword,
                   @Param("startTime") Long startTime,
                   @Param("endTime") Long endTime);
}
