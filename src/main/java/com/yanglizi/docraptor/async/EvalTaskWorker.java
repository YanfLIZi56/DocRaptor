package com.yanglizi.docraptor.async;

import com.yanglizi.docraptor.common.ErrorCode;
import com.yanglizi.docraptor.config.AsyncConfig;
import com.yanglizi.docraptor.dto.request.EvalRunRequest;
import com.yanglizi.docraptor.service.EvalService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * EVAL_RUN 异步工作线程（契约 6.5 的 {@code async=true}）。
 * 结果整体写入 {@code async_task.result}，与同步模式响应的 data 结构一致。
 */
@Slf4j
@Component
public class EvalTaskWorker {

    private final EvalService evalService;
    private final AsyncTaskProgressReporter reporter;

    public EvalTaskWorker(EvalService evalService, AsyncTaskProgressReporter reporter) {
        this.evalService = evalService;
        this.reporter = reporter;
    }

    @Async(AsyncConfig.EXECUTOR)
    public void run(UUID taskId, EvalRunRequest request) {
        try {
            reporter.markRunning(taskId, "EVAL", "开始执行召回评估");
            var result = evalService.execute(request, taskId, reporter);
            reporter.finishWithStatus(taskId, "SUCCESS", 100,
                    com.yanglizi.docraptor.common.JsonUtils.toMap(
                            com.yanglizi.docraptor.common.JsonUtils.toJson(result)), null);
            log.info("EVAL_RUN 任务 {} 完成：评估 {} 条用例", taskId, result.getEvaluatedCases());
        } catch (Exception e) {
            log.error("EVAL_RUN 任务 {} 失败", taskId, e);
            String message = e instanceof com.yanglizi.docraptor.common.BizException
                    ? e.getMessage()
                    : ErrorCode.INTERNAL_ERROR.message() + "：" + e.getClass().getSimpleName() + " " + e.getMessage();
            reporter.finish(taskId, false, null, message);
        }
    }
}
