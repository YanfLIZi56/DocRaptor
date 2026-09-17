package com.yanglizi.docraptor.controller;

import com.yanglizi.docraptor.common.PageResult;
import com.yanglizi.docraptor.common.R;
import com.yanglizi.docraptor.dto.request.RetrievalRequest;
import com.yanglizi.docraptor.dto.response.RetrievalLogVO;
import com.yanglizi.docraptor.dto.response.RetrievalResultVO;
import com.yanglizi.docraptor.service.RetrievalService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 模块3：三模式检索 + 检索日志（契约 6.1 / 6.2）。 */
@RestController
public class RetrievalController {

    private final RetrievalService service;

    public RetrievalController(RetrievalService service) {
        this.service = service;
    }

    @PostMapping("/api/retrieval/vector")
    public R<RetrievalResultVO> vector(@RequestBody RetrievalRequest request) {
        return R.ok(service.vectorSearch(request));
    }

    @PostMapping("/api/retrieval/bm25")
    public R<RetrievalResultVO> bm25(@RequestBody RetrievalRequest request) {
        return R.ok(service.bm25Search(request));
    }

    @PostMapping("/api/retrieval/hybrid")
    public R<RetrievalResultVO> hybrid(@RequestBody RetrievalRequest request) {
        return R.ok(service.hybridSearch(request));
    }

    @GetMapping("/api/retrieval/logs")
    public R<PageResult<RetrievalLogVO>> logs(@RequestParam(required = false) String knowledgeBaseId,
                                              @RequestParam(required = false) String mode,
                                              @RequestParam(required = false) String logType,
                                              @RequestParam(required = false) String queryKeyword,
                                              @RequestParam(required = false) Long startTime,
                                              @RequestParam(required = false) Long endTime,
                                              @RequestParam(required = false) Integer page,
                                              @RequestParam(required = false) Integer pageSize) {
        return R.ok(service.listLogs(knowledgeBaseId, mode, logType, queryKeyword, startTime, endTime,
                page, pageSize));
    }
}
