package com.yanglizi.docraptor.controller;

import com.yanglizi.docraptor.common.PageResult;
import com.yanglizi.docraptor.common.R;
import com.yanglizi.docraptor.dto.request.DocumentEnabledRequest;
import com.yanglizi.docraptor.dto.response.ChunkVO;
import com.yanglizi.docraptor.dto.response.DocumentVO;
import com.yanglizi.docraptor.dto.response.SimpleVOs;
import com.yanglizi.docraptor.service.DocumentService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** 模块1：文档上传/列表/详情/启用禁用/分块列表（契约 4.6 ~ 4.9）。 */
@RestController
public class DocumentController {

    private final DocumentService service;

    public DocumentController(DocumentService service) {
        this.service = service;
    }

    @PostMapping("/api/documents/upload")
    public R<SimpleVOs.UploadVO> upload(@RequestParam("file") MultipartFile file,
                                        @RequestParam("knowledgeBaseId") String knowledgeBaseId,
                                        @RequestParam(value = "buildTree", required = false) Boolean buildTree,
                                        @RequestParam(value = "maxLevel", required = false) Integer maxLevel) {
        return R.ok(service.upload(file, knowledgeBaseId, buildTree, maxLevel));
    }

    @GetMapping("/api/documents")
    public R<PageResult<DocumentVO>> list(@RequestParam(required = false) String knowledgeBaseId,
                                          @RequestParam(required = false) Integer page,
                                          @RequestParam(required = false) Integer pageSize,
                                          @RequestParam(required = false) Boolean enabled,
                                          @RequestParam(required = false) String keyword,
                                          @RequestParam(required = false) String treeStatus) {
        return R.ok(service.list(knowledgeBaseId, page, pageSize, enabled, keyword, treeStatus));
    }

    @GetMapping("/api/documents/{id}")
    public R<DocumentVO> detail(@PathVariable String id) {
        return R.ok(service.detail(id));
    }

    /** 文档唯一可写的业务字段：启用/禁用（可逆，数据完整保留）。 */
    @PutMapping("/api/documents/{id}/enabled")
    public R<SimpleVOs.DocumentEnabledVO> setEnabled(@PathVariable String id,
                                                     @RequestBody DocumentEnabledRequest request) {
        return R.ok(service.setEnabled(id, request == null ? null : request.getEnabled()));
    }

    /** 分块列表：每块必须返回 documentId / chunkIndex / charCount。 */
    @GetMapping("/api/chunks")
    public R<PageResult<ChunkVO>> chunks(@RequestParam(required = false) String knowledgeBaseId,
                                         @RequestParam(required = false) String documentId,
                                         @RequestParam(required = false) Integer page,
                                         @RequestParam(required = false) Integer pageSize,
                                         @RequestParam(required = false) Boolean withContent,
                                         @RequestParam(required = false) Boolean withEmbedding) {
        return R.ok(service.chunks(knowledgeBaseId, documentId, page, pageSize, withContent, withEmbedding));
    }
}
