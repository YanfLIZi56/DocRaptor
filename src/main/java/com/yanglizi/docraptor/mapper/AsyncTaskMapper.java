package com.yanglizi.docraptor.mapper;

import com.yanglizi.docraptor.domain.dto.TaskRow;
import com.yanglizi.docraptor.domain.entity.AsyncTask;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.UUID;

/**
 * async_task 表访问：任务进度落库（本项目不用 Redis/MQ）。
 *
 * <p>并发保护依赖唯一索引 {@code uk_async_task_active_doc}：同一 (document_id, task_type)
 * 同时只允许一个 PENDING/RUNNING 任务，重复触发会抛 duplicate key，由 Service 转成 40902。
 */
@Mapper
public interface AsyncTaskMapper {

    @Insert("""
            INSERT INTO async_task
                (id, task_type, knowledge_base_id, document_id, status, progress, current_stage,
                 progress_message, payload, retry_count)
            VALUES
                (#{id}, #{taskType}, #{knowledgeBaseId}, #{documentId}, #{status}, #{progress}, #{currentStage},
                 #{progressMessage}, CAST(#{payload} AS jsonb), #{retryCount})
            """)
    int insert(AsyncTask task);

    @Select("SELECT * FROM async_task WHERE id = #{id}")
    AsyncTask selectById(UUID id);

    @Select("""
            <script>
            SELECT t.*, d.file_name AS document_name
            FROM async_task t
            LEFT JOIN document d ON d.id = t.document_id
            <where>
              <if test="documentId != null">AND t.document_id = #{documentId}</if>
              <if test="knowledgeBaseId != null">AND t.knowledge_base_id = #{knowledgeBaseId}</if>
              <if test="status != null and status != ''">AND t.status = #{status}</if>
              <if test="taskType != null and taskType != ''">AND t.task_type = #{taskType}</if>
            </where>
            ORDER BY t.created_at DESC
            LIMIT #{limit} OFFSET #{offset}
            </script>
            """)
    List<TaskRow> selectPage(@Param("documentId") UUID documentId,
                             @Param("knowledgeBaseId") UUID knowledgeBaseId,
                             @Param("status") String status,
                             @Param("taskType") String taskType,
                             @Param("offset") int offset,
                             @Param("limit") int limit);

    @Select("""
            <script>
            SELECT count(*) FROM async_task t
            <where>
              <if test="documentId != null">AND t.document_id = #{documentId}</if>
              <if test="knowledgeBaseId != null">AND t.knowledge_base_id = #{knowledgeBaseId}</if>
              <if test="status != null and status != ''">AND t.status = #{status}</if>
              <if test="taskType != null and taskType != ''">AND t.task_type = #{taskType}</if>
            </where>
            </script>
            """)
    long countPage(@Param("documentId") UUID documentId,
                   @Param("knowledgeBaseId") UUID knowledgeBaseId,
                   @Param("status") String status,
                   @Param("taskType") String taskType);

    /** 同一文档下所有未完成任务（40902 冲突检测 + 冲突详情）。 */
    @Select("""
            SELECT * FROM async_task
            WHERE document_id = #{documentId} AND status IN ('PENDING', 'RUNNING')
            ORDER BY created_at DESC
            """)
    List<AsyncTask> selectActiveByDocument(UUID documentId);

    @Update("""
            UPDATE async_task
            SET status = 'RUNNING', started_at = COALESCE(started_at, now()), heartbeat_at = now(),
                current_stage = #{stage}, progress_message = #{message}
            WHERE id = #{id}
            """)
    int markRunning(@Param("id") UUID id, @Param("stage") String stage, @Param("message") String message);

    /** 进度只增不减：用 GREATEST 保证单调。 */
    @Update("""
            UPDATE async_task
            SET progress = GREATEST(progress, #{progress}),
                current_stage = #{stage},
                progress_message = #{message},
                heartbeat_at = now(),
                status = CASE WHEN status = 'PENDING' THEN 'RUNNING' ELSE status END,
                started_at = COALESCE(started_at, now())
            WHERE id = #{id}
            """)
    int updateProgress(@Param("id") UUID id, @Param("progress") int progress,
                       @Param("stage") String stage, @Param("message") String message);

    @Update("""
            UPDATE async_task
            SET status = #{status}, progress = #{progress}, current_stage = 'DONE',
                result = CAST(#{result} AS jsonb), error_message = #{errorMessage},
                finished_at = now(), heartbeat_at = now()
            WHERE id = #{id}
            """)
    int markFinished(@Param("id") UUID id, @Param("status") String status, @Param("progress") int progress,
                     @Param("result") String result, @Param("errorMessage") String errorMessage);

    @Update("UPDATE async_task SET retry_count = retry_count + #{delta} WHERE id = #{id}")
    int incrementRetry(@Param("id") UUID id, @Param("delta") int delta);

    /**
     * 僵死任务巡检：RUNNING 且心跳超过 staleSeconds 秒未刷新 → 标 FAILED。
     * 返回被标记的任务数。
     */
    @Update("""
            UPDATE async_task
            SET status = 'FAILED',
                error_message = COALESCE(error_message, 'heartbeat timeout'),
                finished_at = now()
            WHERE status = 'RUNNING'
              AND COALESCE(heartbeat_at, started_at, created_at) < now() - make_interval(secs => #{staleSeconds})
            """)
    int markStaleFailed(@Param("staleSeconds") int staleSeconds);

    @Select("SELECT count(*) FROM async_task WHERE knowledge_base_id = #{kbId}")
    long countByKnowledgeBase(UUID kbId);

    /** 任务行 + 关联文档名（契约 7.1 的 documentName）。 */
    @Select("""
            SELECT t.*, d.file_name AS document_name
            FROM async_task t
            LEFT JOIN document d ON d.id = t.document_id
            WHERE t.id = #{id}
            """)
    TaskRow selectRowById(UUID id);

    @org.apache.ibatis.annotations.Delete("DELETE FROM async_task WHERE knowledge_base_id = #{kbId}")
    int deleteByKnowledgeBase(UUID kbId);
}
