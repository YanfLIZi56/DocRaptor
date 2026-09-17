package com.yanglizi.docraptor.controller;

import com.yanglizi.docraptor.async.AsyncTaskService;
import com.yanglizi.docraptor.async.EvalTaskWorker;
import com.yanglizi.docraptor.common.PageResult;
import com.yanglizi.docraptor.common.R;
import com.yanglizi.docraptor.common.TimeUtils;
import com.yanglizi.docraptor.dto.request.EvalCaseRequest;
import com.yanglizi.docraptor.dto.request.EvalRunRequest;
import com.yanglizi.docraptor.dto.response.EvalCaseVO;
import com.yanglizi.docraptor.dto.response.EvalRunVO;
import com.yanglizi.docraptor.dto.response.SimpleVOs;
import com.yanglizi.docraptor.service.EvalService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** 模块3：评估用例 CRUD 与召回率评估执行（契约 6.3 ~ 6.5）。 */
@RestController
@RequestMapping("/api/eval")
public class EvalController {

    private final EvalService evalService;
    private final AsyncTaskService asyncTaskService;
    private final EvalTaskWorker evalTaskWorker;

    public EvalController(EvalService evalService, AsyncTaskService asyncTaskService,
                          EvalTaskWorker evalTaskWorker) {
        this.evalService = evalService;
        this.asyncTaskService = asyncTaskService;
        this.evalTaskWorker = evalTaskWorker;
    }

    @PostMapping("/cases")
    public R<EvalCaseVO> createCase(@RequestBody EvalCaseRequest request) {
        return R.ok(evalService.createCase(request));
    }

    @GetMapping("/cases")
    public R<PageResult<EvalCaseVO>> listCases(@RequestParam(required = false) String knowledgeBaseId,
                                               @RequestParam(required = false) Boolean enabled,
                                               @RequestParam(required = false) Integer page,
                                               @RequestParam(required = false) Integer pageSize) {
        return R.ok(evalService.listCases(knowledgeBaseId, enabled, page, pageSize));
    }

    @PutMapping("/cases/{id}")
    public R<EvalCaseVO> updateCase(@PathVariable String id, @RequestBody EvalCaseRequest request) {
        return R.ok(evalService.updateCase(id, request));
    }

    @DeleteMapping("/cases/{id}")
    public R<SimpleVOs.DeletedVO> deleteCase(@PathVariable String id) {
        return R.ok(evalService.deleteCase(id));
    }

    /**
     * 召回率评估执行。async=false 同步返回指标；async=true 立即返回 taskId，
     * 进度查 /api/async-tasks/{taskId}，结果在 data.result 中。
     */
    @PostMapping("/run")
    public R<Object> run(@RequestBody EvalRunRequest request) {
        UUID kbId = TimeUtils.parseUuid(request.getKnowledgeBaseId(), "knowledgeBaseId");
        if (!Boolean.TRUE.equals(request.getAsync())) {
            return R.ok(evalService.runSync(request));
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("mode", request.getMode());
        payload.put("topK", request.getTopK());
        payload.put("kList", request.getKList());
        payload.put("caseIds", request.getCaseIds());
        payload.put("scope", request.getScope());
        var task = asyncTaskService.createTask("EVAL_RUN", kbId, null, payload);
        evalTaskWorker.run(task.getId(), request);
        return R.ok(SimpleVOs.TaskSubmitVO.of(task.getId().toString(), task.getStatus()));
    }
}
