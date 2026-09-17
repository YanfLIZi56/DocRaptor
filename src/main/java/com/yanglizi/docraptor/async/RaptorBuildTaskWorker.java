package com.yanglizi.docraptor.async;

import com.yanglizi.docraptor.config.AsyncConfig;
import com.yanglizi.docraptor.mapper.DocumentMapper;
import com.yanglizi.docraptor.service.KnowledgeBaseService;
import com.yanglizi.docraptor.service.RaptorTreeService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** RAPTOR_BUILD 异步工作线程：只做建树（叶子块与向量不重建）。 */
@Slf4j
@Component
public class RaptorBuildTaskWorker {

    private final RaptorTreeService treeService;
    private final DocumentMapper documentMapper;
    private final AsyncTaskProgressReporter reporter;
    private final KnowledgeBaseService knowledgeBaseService;

    public RaptorBuildTaskWorker(RaptorTreeService treeService, DocumentMapper documentMapper,
                                 AsyncTaskProgressReporter reporter, KnowledgeBaseService knowledgeBaseService) {
        this.treeService = treeService;
        this.documentMapper = documentMapper;
        this.reporter = reporter;
        this.knowledgeBaseService = knowledgeBaseService;
    }

    @Async(AsyncConfig.EXECUTOR)
    public void run(UUID taskId, UUID knowledgeBaseId, UUID documentId, RaptorTreeService.BuildParams params) {
        try {
            reporter.markRunning(taskId, "TREE_BUILD", "开始构建 RAPTOR 树");
            documentMapper.updateTreeStatus(documentId, "RUNNING");
            RaptorTreeService.BuildResult tree =
                    treeService.build(taskId, knowledgeBaseId, documentId, params, reporter);
            documentMapper.updateTreeStatus(documentId, "SUCCESS");

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("documentId", documentId.toString());
            result.put("rootNodeId", tree.getRootNodeId());
            result.put("maxLevel", tree.getActualDepth());
            result.put("summaryNodeCount", tree.getSummaryNodeCount());
            result.put("actualDepth", tree.getActualDepth());
            result.put("hasUniqueRoot", tree.isHasUniqueRoot());
            result.put("degradedSummaryCount", tree.getDegradedSummaryCount());
            result.put("durationMs", tree.getDurationMs());
            reporter.finish(taskId, true, result, null);
            log.info("RAPTOR_BUILD 任务 {} 完成：文档 {} 摘要 {} 个", taskId, documentId, tree.getSummaryNodeCount());
        } catch (Exception e) {
            log.error("RAPTOR_BUILD 任务 {} 失败（文档 {}）", taskId, documentId, e);
            try {
                // ⚠️ 只动 tree_status：建树失败时叶子块与向量完好，绝不能把 embed/parse/chunk 标成 FAILED，
                //   否则之后 POST /api/raptor/trees 会一直返回 40905，文档被永久锁死无法重建。
                documentMapper.updateTreeResult(documentId, "FAILED", DocImportTaskWorker.errorMessage(e));
            } catch (Exception ignore) {
                // 忽略状态写入失败
            }
            reporter.finish(taskId, false, null, DocImportTaskWorker.errorMessage(e));
        } finally {
            if (knowledgeBaseId != null) {
                try {
                    knowledgeBaseService.refreshStats(knowledgeBaseId);
                } catch (Exception e) {
                    log.warn("刷新知识库统计失败：{}", e.toString());
                }
            }
        }
    }
}
