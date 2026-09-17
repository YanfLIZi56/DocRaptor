package com.yanglizi.docraptor.async;

import com.yanglizi.docraptor.common.BizException;
import com.yanglizi.docraptor.common.ErrorCode;
import com.yanglizi.docraptor.common.JsonUtils;
import com.yanglizi.docraptor.common.PageResult;
import com.yanglizi.docraptor.common.TimeUtils;
import com.yanglizi.docraptor.config.DocRaptorProperties;
import com.yanglizi.docraptor.domain.dto.TaskRow;
import com.yanglizi.docraptor.domain.entity.AsyncTask;
import com.yanglizi.docraptor.domain.enums.TaskStatus;
import com.yanglizi.docraptor.dto.response.AsyncTaskVO;
import com.yanglizi.docraptor.mapper.AsyncTaskMapper;
import com.yanglizi.docraptor.service.Paging;
import com.yanglizi.docraptor.service.VoConverter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 异步任务服务：建任务、抢占、状态流转、僵死任务巡检。
 * 不使用 Redis/MQ，全部落 {@code async_task} 表（契约 7.1 / 7.2）。
 */
@Slf4j
@Service
public class AsyncTaskService {

    private final AsyncTaskMapper mapper;
    private final DocRaptorProperties props;

    public AsyncTaskService(AsyncTaskMapper mapper, DocRaptorProperties props) {
        this.mapper = mapper;
        this.props = props;
    }

    /**
     * 创建任务（状态 PENDING，写入 payload 快照）。
     * 若该文档已有进行中的任务 → 40902，并在 data 里带上冲突任务详情。
     */
    @Transactional
    public AsyncTask createTask(String taskType, UUID knowledgeBaseId, UUID documentId, Map<String, Object> payload) {
        sweepStaleTasks();
        if (documentId != null) {
            List<AsyncTask> active = mapper.selectActiveByDocument(documentId);
            if (!active.isEmpty()) {
                AsyncTask conflict = active.get(0);
                Map<String, Object> detail = new LinkedHashMap<>();
                detail.put("conflictTaskId", conflict.getId().toString());
                detail.put("status", conflict.getStatus());
                detail.put("progress", conflict.getProgress());
                throw BizException.withPayload(ErrorCode.CONFLICT_RUNNING_TASK, detail,
                        conflict.getId().toString());
            }
        }
        AsyncTask task = new AsyncTask();
        task.setId(UUID.randomUUID());
        task.setTaskType(taskType);
        task.setKnowledgeBaseId(knowledgeBaseId);
        task.setDocumentId(documentId);
        task.setStatus("PENDING");
        task.setProgress(0);
        task.setCurrentStage("PARSE");
        task.setProgressMessage("任务已入队");
        task.setPayload(JsonUtils.toJson(payload == null ? Map.of() : payload));
        task.setRetryCount(0);
        try {
            mapper.insert(task);
        } catch (DuplicateKeyException e) {
            // 唯一索引 uk_async_task_active_doc 兜底（并发触发）
            throw BizException.of(ErrorCode.CONFLICT_RUNNING_TASK, "并发触发被数据库唯一索引拒绝");
        }
        return mapper.selectById(task.getId());
    }

    public AsyncTaskVO get(String taskId) {
        UUID id = TimeUtils.parseUuid(taskId, "taskId");
        TaskRow row = mapper.selectRowById(id);
        if (row == null) {
            throw BizException.of(ErrorCode.TASK_NOT_FOUND);
        }
        return VoConverter.toTaskVO(row);
    }

    public AsyncTask require(UUID taskId) {
        AsyncTask task = mapper.selectById(taskId);
        if (task == null) {
            throw BizException.of(ErrorCode.TASK_NOT_FOUND);
        }
        return task;
    }

    public PageResult<AsyncTaskVO> list(String documentId, String knowledgeBaseId, String status,
                                        String taskType, Integer page, Integer pageSize) {
        int[] pg = Paging.normalize(page, pageSize);
        if (status != null && !status.isBlank() && !TaskStatus.isValid(status)) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "status 不合法：" + status);
        }
        UUID docId = (documentId == null || documentId.isBlank()) ? null : TimeUtils.parseUuid(documentId, "documentId");
        UUID kbId = (knowledgeBaseId == null || knowledgeBaseId.isBlank())
                ? null : TimeUtils.parseUuid(knowledgeBaseId, "knowledgeBaseId");

        List<TaskRow> rows = mapper.selectPage(docId, kbId, status, taskType, (pg[0] - 1) * pg[1], pg[1]);
        long total = mapper.countPage(docId, kbId, status, taskType);
        List<AsyncTaskVO> list = new ArrayList<>(rows.size());
        for (TaskRow row : rows) {
            list.add(VoConverter.toTaskVO(row));
        }
        return PageResult.of(list, total, pg[0], pg[1]);
    }

    /**
     * 僵死任务巡检：RUNNING 且心跳超时 → FAILED。
     * 在建任务前调用，避免僵死任务永久阻塞同一文档。
     */
    @Transactional
    public int sweepStaleTasks() {
        int n = mapper.markStaleFailed(props.getAsync().getHeartbeatStaleSeconds());
        if (n > 0) {
            log.warn("巡检发现 {} 个心跳超时的僵死任务，已标记 FAILED", n);
        }
        return n;
    }

    /** 任务的进度权重区间（契约 7.1 的进度条映射）。 */
    public static int stageProgress(String stage, int percentInStage) {
        int base;
        int span;
        switch (stage) {
            case "PARSE" -> {
                base = 0;
                span = 15;
            }
            case "CHUNK" -> {
                base = 15;
                span = 20;
            }
            case "EMBED" -> {
                base = 35;
                span = 35;
            }
            case "TREE_BUILD" -> {
                base = 70;
                span = 30;
            }
            case "EVAL" -> {
                base = 0;
                span = 95;
            }
            default -> {
                return 100;
            }
        }
        int pct = Math.max(0, Math.min(100, percentInStage));
        return base + (int) Math.round(span * pct / 100.0);
    }
}
