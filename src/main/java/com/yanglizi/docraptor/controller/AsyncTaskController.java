package com.yanglizi.docraptor.controller;

import com.yanglizi.docraptor.async.AsyncTaskService;
import com.yanglizi.docraptor.common.PageResult;
import com.yanglizi.docraptor.common.R;
import com.yanglizi.docraptor.dto.response.AsyncTaskVO;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 模块4：异步任务进度查询（契约 7.1 / 7.2）。 */
@RestController
@RequestMapping("/api/async-tasks")
public class AsyncTaskController {

    private final AsyncTaskService service;

    public AsyncTaskController(AsyncTaskService service) {
        this.service = service;
    }

    @GetMapping("/{taskId}")
    public R<AsyncTaskVO> detail(@PathVariable String taskId) {
        return R.ok(service.get(taskId));
    }

    @GetMapping
    public R<PageResult<AsyncTaskVO>> list(@RequestParam(required = false) String documentId,
                                           @RequestParam(required = false) String knowledgeBaseId,
                                           @RequestParam(required = false) String status,
                                           @RequestParam(required = false) String taskType,
                                           @RequestParam(required = false) Integer page,
                                           @RequestParam(required = false) Integer pageSize) {
        return R.ok(service.list(documentId, knowledgeBaseId, status, taskType, page, pageSize));
    }
}
