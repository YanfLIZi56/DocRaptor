package com.yanglizi.docraptor.async;

import com.yanglizi.docraptor.common.JsonUtils;
import com.yanglizi.docraptor.domain.entity.AsyncTask;
import com.yanglizi.docraptor.mapper.AsyncTaskMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.UUID;

/**
 * 任务进度写入器：独立事务（REQUIRES_NEW），保证业务事务回滚时进度不丢，
 * 也保证前端轮询能立刻看到阶段推进。
 */
@Slf4j
@Component
public class AsyncTaskProgressReporter {

    private final AsyncTaskMapper mapper;

    public AsyncTaskProgressReporter(AsyncTaskMapper mapper) {
        this.mapper = mapper;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markRunning(UUID taskId, String stage, String message) {
        mapper.markRunning(taskId, stage, message);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void progress(UUID taskId, int percent, String stage, String message) {
        mapper.updateProgress(taskId, percent, stage, truncate(message, 500));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void finish(UUID taskId, boolean success, Map<String, Object> result, String errorMessage) {
        mapper.markFinished(taskId, success ? "SUCCESS" : "FAILED", success ? 100 : 0,
                result == null ? null : JsonUtils.toJson(result),
                errorMessage == null ? null : truncate(errorMessage, 4000));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void finishWithStatus(UUID taskId, String status, int progress, Map<String, Object> result,
                                 String errorMessage) {
        mapper.markFinished(taskId, status, progress,
                result == null ? null : JsonUtils.toJson(result),
                errorMessage == null ? null : truncate(errorMessage, 4000));
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void incrementRetry(UUID taskId, int delta) {
        mapper.incrementRetry(taskId, delta);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void heartbeat(UUID taskId) {
        AsyncTask task = mapper.selectById(taskId);
        if (task != null) {
            mapper.updateProgress(taskId, task.getProgress() == null ? 0 : task.getProgress(),
                    task.getCurrentStage(), task.getProgressMessage());
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
