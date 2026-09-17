package com.yanglizi.docraptor.controller;

import com.yanglizi.docraptor.async.AsyncTaskService;
import com.yanglizi.docraptor.async.RaptorBuildTaskWorker;
import com.yanglizi.docraptor.common.BizException;
import com.yanglizi.docraptor.common.ErrorCode;
import com.yanglizi.docraptor.common.R;
import com.yanglizi.docraptor.config.DocRaptorProperties;
import com.yanglizi.docraptor.domain.entity.DocumentEntity;
import com.yanglizi.docraptor.dto.request.RaptorBuildRequest;
import com.yanglizi.docraptor.dto.response.SimpleVOs;
import com.yanglizi.docraptor.dto.response.TreeVO;
import com.yanglizi.docraptor.service.DocumentService;
import com.yanglizi.docraptor.service.RaptorTreeService;
import com.yanglizi.docraptor.service.TreeQueryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** 模块2：RAPTOR 树构建与查询（契约 5.1 ~ 5.3）。 */
@RestController
@RequestMapping("/api/raptor/trees")
public class RaptorTreeController {

    private final RaptorTreeService treeService;
    private final TreeQueryService treeQueryService;
    private final DocumentService documentService;
    private final AsyncTaskService asyncTaskService;
    private final RaptorBuildTaskWorker buildWorker;
    private final DocRaptorProperties props;

    public RaptorTreeController(RaptorTreeService treeService, TreeQueryService treeQueryService,
                                DocumentService documentService, AsyncTaskService asyncTaskService,
                                RaptorBuildTaskWorker buildWorker, DocRaptorProperties props) {
        this.treeService = treeService;
        this.treeQueryService = treeQueryService;
        this.documentService = documentService;
        this.asyncTaskService = asyncTaskService;
        this.buildWorker = buildWorker;
        this.props = props;
    }

    /** 触发建树（异步）。 */
    @PostMapping
    public R<SimpleVOs.TaskSubmitVO> build(@RequestBody RaptorBuildRequest request) {
        UUID docId = com.yanglizi.docraptor.common.TimeUtils.parseUuid(request.getDocumentId(), "documentId");
        DocumentEntity doc = documentService.requireDocument(docId);
        if (!"SUCCESS".equals(doc.getEmbedStatus())) {
            throw BizException.of(ErrorCode.DOCUMENT_NOT_READY);
        }
        boolean force = Boolean.TRUE.equals(request.getForceRebuild());
        if (!force && "SUCCESS".equals(doc.getTreeStatus())) {
            throw BizException.of(ErrorCode.TREE_ALREADY_EXISTS);
        }

        RaptorTreeService.BuildParams params = RaptorTreeService.BuildParams.defaults(props);
        if (request.getMaxLevel() != null) {
            if (request.getMaxLevel() < 1 || request.getMaxLevel() > 10) {
                throw BizException.of(ErrorCode.PARAM_INVALID, "maxLevel 需在 [1, 10] 之间");
            }
            params.setMaxLevel(request.getMaxLevel());
        }
        if (request.getUmapNNeighbors() != null) {
            if (request.getUmapNNeighbors() < 2 || request.getUmapNNeighbors() > 100) {
                throw BizException.of(ErrorCode.PARAM_INVALID, "umapNNeighbors 需在 [2, 100] 之间");
            }
            params.setUmapNNeighbors(request.getUmapNNeighbors());
        }
        if (request.getUmapMinDist() != null) {
            if (request.getUmapMinDist() < 0.0 || request.getUmapMinDist() > 1.0) {
                throw BizException.of(ErrorCode.PARAM_INVALID, "umapMinDist 需在 [0.0, 1.0] 之间");
            }
            params.setUmapMinDist(request.getUmapMinDist());
        }
        if (request.getGmmMaxClusters() != null) {
            if (request.getGmmMaxClusters() < 2 || request.getGmmMaxClusters() > 64) {
                throw BizException.of(ErrorCode.PARAM_INVALID, "gmmMaxClusters 需在 [2, 64] 之间");
            }
            params.setGmmMaxClusters(request.getGmmMaxClusters());
        }
        if (request.getGmmCovarianceType() != null) {
            String ct = request.getGmmCovarianceType();
            if (!"full".equals(ct) && !"tied".equals(ct) && !"diagonal".equals(ct) && !"diag".equals(ct)
                    && !"spherical".equals(ct)) {
                throw BizException.of(ErrorCode.PARAM_INVALID,
                        "gmmCovarianceType 只支持 full/tied/diagonal/spherical");
            }
            params.setGmmCovarianceType("diag".equals(ct) ? "diagonal" : ct);
        }
        if (request.getSummaryPrompt() != null && !request.getSummaryPrompt().isBlank()) {
            params.setSummaryPrompt(request.getSummaryPrompt());
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("documentId", docId.toString());
        payload.put("maxLevel", params.getMaxLevel());
        payload.put("forceRebuild", force);
        payload.put("umapNNeighbors", params.getUmapNNeighbors());
        payload.put("umapMinDist", params.getUmapMinDist());
        payload.put("gmmMaxClusters", params.getGmmMaxClusters());
        payload.put("gmmCovarianceType", params.getGmmCovarianceType());

        var task = asyncTaskService.createTask("RAPTOR_BUILD", doc.getKnowledgeBaseId(), docId, payload);
        buildWorker.run(task.getId(), doc.getKnowledgeBaseId(), docId, params);

        SimpleVOs.TaskSubmitVO vo = SimpleVOs.TaskSubmitVO.of(task.getId().toString(), task.getStatus());
        vo.setDocumentId(docId.toString());
        return R.ok(vo);
    }

    @GetMapping("/{documentId}")
    public R<TreeVO> tree(@PathVariable String documentId,
                          @RequestParam(required = false) String format,
                          @RequestParam(required = false) Boolean withContent,
                          @RequestParam(required = false) Boolean includeLeaves) {
        return R.ok(treeQueryService.getTree(documentId, format, withContent, includeLeaves));
    }

    @GetMapping("/{documentId}/stats")
    public R<TreeVO.TreeStatsVO> stats(@PathVariable String documentId) {
        return R.ok(treeQueryService.stats(documentId));
    }
}
