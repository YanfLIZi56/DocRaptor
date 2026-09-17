package com.yanglizi.docraptor.controller;

import com.yanglizi.docraptor.common.PageResult;
import com.yanglizi.docraptor.common.R;
import com.yanglizi.docraptor.dto.request.KnowledgeBaseCreateRequest;
import com.yanglizi.docraptor.dto.request.KnowledgeBaseUpdateRequest;
import com.yanglizi.docraptor.dto.response.KnowledgeBaseVO;
import com.yanglizi.docraptor.dto.response.SimpleVOs;
import com.yanglizi.docraptor.service.KnowledgeBaseService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 模块1：知识库管理（契约 4.1 ~ 4.5）。 */
@RestController
@RequestMapping("/api/knowledge-bases")
public class KnowledgeBaseController {

    private final KnowledgeBaseService service;

    public KnowledgeBaseController(KnowledgeBaseService service) {
        this.service = service;
    }

    @PostMapping
    public R<KnowledgeBaseVO> create(@RequestBody KnowledgeBaseCreateRequest request) {
        return R.ok(service.create(request));
    }

    @GetMapping
    public R<PageResult<KnowledgeBaseVO>> list(@RequestParam(required = false) String keyword,
                                               @RequestParam(required = false) Integer page,
                                               @RequestParam(required = false) Integer pageSize) {
        return R.ok(service.list(keyword, page, pageSize));
    }

    @GetMapping("/{id}")
    public R<KnowledgeBaseVO> detail(@PathVariable String id) {
        return R.ok(service.detail(id));
    }

    @PutMapping("/{id}")
    public R<KnowledgeBaseVO> update(@PathVariable String id, @RequestBody KnowledgeBaseUpdateRequest request) {
        return R.ok(service.update(id, request));
    }

    @DeleteMapping("/{id}")
    public R<SimpleVOs.KnowledgeBaseDeleteVO> delete(@PathVariable String id,
                                                     @RequestParam(required = false) Boolean confirm) {
        return R.ok(service.delete(id, confirm));
    }
}
