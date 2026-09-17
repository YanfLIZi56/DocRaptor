package com.yanglizi.docraptor.async;

import com.yanglizi.docraptor.common.BizException;
import com.yanglizi.docraptor.config.AsyncConfig;
import com.yanglizi.docraptor.config.DocRaptorProperties;
import com.yanglizi.docraptor.domain.entity.DocumentEntity;
import com.yanglizi.docraptor.mapper.DocumentMapper;
import com.yanglizi.docraptor.service.DocumentImportService;
import com.yanglizi.docraptor.service.KnowledgeBaseService;
import com.yanglizi.docraptor.service.RaptorTreeService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * DOC_IMPORT 异步工作线程：解析 → 分块 → 向量化 →（可选）建树。
 *
 * <p>进度权重严格按契约 7.1：PARSE 0~15 / CHUNK 15~35 / EMBED 35~70 / TREE_BUILD 70~100。
 */
@Slf4j
@Component
public class DocImportTaskWorker {

    private final DocumentImportService importService;
    private final RaptorTreeService treeService;
    private final DocumentMapper documentMapper;
    private final AsyncTaskProgressReporter reporter;
    private final KnowledgeBaseService knowledgeBaseService;
    private final DocRaptorProperties props;

    public DocImportTaskWorker(DocumentImportService importService, RaptorTreeService treeService,
                               DocumentMapper documentMapper, AsyncTaskProgressReporter reporter,
                               KnowledgeBaseService knowledgeBaseService, DocRaptorProperties props) {
        this.importService = importService;
        this.treeService = treeService;
        this.documentMapper = documentMapper;
        this.reporter = reporter;
        this.knowledgeBaseService = knowledgeBaseService;
        this.props = props;
    }

    @Async(AsyncConfig.EXECUTOR)
    public void run(UUID taskId, UUID documentId, int maxLevel, boolean autoBuildTree) {
        long started = System.currentTimeMillis();
        UUID kbId = null;
        try {
            reporter.markRunning(taskId, "PARSE", "开始解析文档");
            DocumentEntity doc = importService.requireDocument(documentId);
            kbId = doc.getKnowledgeBaseId();

            // ---------- PARSE ----------
            String text = importService.parse(taskId, documentId, reporter);

            // ---------- CHUNK ----------
            int chunkCount = importService.chunk(taskId, documentId, text, reporter);

            // ---------- EMBED ----------
            int embedded = importService.embed(taskId, documentId, reporter);

            // ---------- TREE_BUILD ----------
            RaptorTreeService.BuildResult tree = null;
            if (autoBuildTree) {
                reporter.progress(taskId, 70, "TREE_BUILD", "开始构建 RAPTOR 树");
                documentMapper.updateTreeStatus(documentId, "RUNNING");
                RaptorTreeService.BuildParams params = RaptorTreeService.BuildParams.defaults(props);
                params.setMaxLevel(maxLevel);
                tree = treeService.build(taskId, kbId, documentId, params, reporter);
                documentMapper.updateTreeStatus(documentId, "SUCCESS");
            } else {
                documentMapper.updateTreeStatus(documentId, "SKIPPED");
            }

            long duration = System.currentTimeMillis() - started;
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("documentId", documentId.toString());
            result.put("charCount", text.length());
            result.put("chunkCount", chunkCount);
            result.put("embeddedCount", embedded);
            result.put("durationMs", duration);
            if (tree != null) {
                result.put("rootNodeId", tree.getRootNodeId());
                result.put("maxLevel", tree.getActualDepth());
                result.put("summaryNodeCount", tree.getSummaryNodeCount());
                result.put("actualDepth", tree.getActualDepth());
                result.put("hasUniqueRoot", tree.isHasUniqueRoot());
                result.put("degradedSummaryCount", tree.getDegradedSummaryCount());
            }
            reporter.finish(taskId, true, result, null);
            log.info("DOC_IMPORT 任务 {} 完成：文档 {} {} 块，耗时 {}ms", taskId, documentId, chunkCount, duration);
        } catch (Exception e) {
            log.error("DOC_IMPORT 任务 {} 失败（文档 {}）", taskId, documentId, e);
            markFailed(taskId, documentId, e);
        } finally {
            if (kbId != null) {
                try {
                    knowledgeBaseService.refreshStats(kbId);
                } catch (Exception e) {
                    log.warn("刷新知识库统计失败：{}", e.toString());
                }
            }
        }
    }

    private void markFailed(UUID taskId, UUID documentId, Exception e) {
        String message = errorMessage(e);
        try {
            documentMapper.markPipelineFailed(documentId, "FAILED", message);
        } catch (Exception ignore) {
            // 文档状态写入失败不影响任务终态
        }
        reporter.finish(taskId, false, null, message);
    }

    static String errorMessage(Exception e) {
        String detail = e instanceof BizException ? e.getMessage() : e.getClass().getSimpleName() + ": " + e.getMessage();
        if (detail == null) {
            detail = e.getClass().getName();
        }
        return detail.length() > 4000 ? detail.substring(0, 4000) : detail;
    }
}
