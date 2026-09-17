package com.yanglizi.docraptor.service;

import com.yanglizi.docraptor.algorithm.VectorUtils;
import com.yanglizi.docraptor.common.JsonUtils;
import com.yanglizi.docraptor.common.TimeUtils;
import com.yanglizi.docraptor.domain.dto.ChunkRow;
import com.yanglizi.docraptor.domain.dto.TaskRow;
import com.yanglizi.docraptor.domain.entity.AsyncTask;
import com.yanglizi.docraptor.domain.entity.DocumentEntity;
import com.yanglizi.docraptor.domain.entity.EvalCase;
import com.yanglizi.docraptor.domain.entity.EvalCaseExpectedChunk;
import com.yanglizi.docraptor.domain.entity.KnowledgeBase;
import com.yanglizi.docraptor.domain.entity.RetrievalLog;
import com.yanglizi.docraptor.dto.response.AsyncTaskVO;
import com.yanglizi.docraptor.dto.response.ChunkVO;
import com.yanglizi.docraptor.dto.response.DocumentVO;
import com.yanglizi.docraptor.dto.response.EvalCaseVO;
import com.yanglizi.docraptor.dto.response.KnowledgeBaseVO;
import com.yanglizi.docraptor.dto.response.RetrievalLogVO;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** entity → VO 转换（时间统一转毫秒时间戳，字段名严格按契约）。 */
public final class VoConverter {

    private VoConverter() {
    }

    public static String str(UUID id) {
        return id == null ? null : id.toString();
    }

    public static KnowledgeBaseVO toKbVO(KnowledgeBase kb) {
        if (kb == null) {
            return null;
        }
        KnowledgeBaseVO vo = new KnowledgeBaseVO();
        vo.setId(str(kb.getId()));
        vo.setName(kb.getName());
        vo.setDescription(kb.getDescription());
        vo.setChunkSize(kb.getChunkSize());
        vo.setChunkOverlap(kb.getChunkOverlap());
        vo.setChunkStrategy(kb.getChunkStrategy());
        vo.setDocumentCount(kb.getDocumentCount());
        vo.setNodeCount(kb.getNodeCount());
        vo.setEmbeddingModel(kb.getEmbeddingModel());
        vo.setEmbeddingDimension(kb.getEmbeddingDimension());
        vo.setCreatedAt(TimeUtils.toMillis(kb.getCreatedAt()));
        vo.setUpdatedAt(TimeUtils.toMillis(kb.getUpdatedAt()));
        return vo;
    }

    public static DocumentVO toDocumentVO(DocumentEntity d) {
        if (d == null) {
            return null;
        }
        DocumentVO vo = new DocumentVO();
        vo.setId(str(d.getId()));
        vo.setKnowledgeBaseId(str(d.getKnowledgeBaseId()));
        vo.setKnowledgeBaseName(d.getKnowledgeBaseName());
        vo.setFileName(d.getFileName());
        vo.setFileType(d.getFileType());
        vo.setFileSize(d.getFileSize());
        vo.setCharCount(d.getCharCount());
        vo.setChunkCount(d.getChunkCount());
        vo.setEnabled(d.getEnabled());
        vo.setParseStatus(d.getParseStatus());
        vo.setChunkStatus(d.getChunkStatus());
        vo.setEmbedStatus(d.getEmbedStatus());
        vo.setTreeStatus(d.getTreeStatus());
        vo.setParseError(d.getParseError());
        vo.setMetadata(JsonUtils.toMap(d.getMetadata()));
        vo.setCreatedAt(TimeUtils.toMillis(d.getCreatedAt()));
        vo.setUpdatedAt(TimeUtils.toMillis(d.getUpdatedAt()));
        return vo;
    }

    public static ChunkVO toChunkVO(ChunkRow row, boolean withContent, boolean withEmbedding, int previewDims) {
        ChunkVO vo = new ChunkVO();
        vo.setNodeId(str(row.getNodeId()));
        vo.setDocumentId(str(row.getDocumentId()));
        vo.setDocumentName(row.getDocumentName());
        vo.setKnowledgeBaseId(str(row.getKnowledgeBaseId()));
        vo.setNodeType(row.getNodeType());
        vo.setLevel(row.getLevel());
        vo.setChunkIndex(row.getChunkIndex());
        vo.setStartChunkIndex(row.getStartChunkIndex());
        vo.setEndChunkIndex(row.getEndChunkIndex());
        vo.setCharCount(row.getCharCount());
        vo.setTokenCount(row.getTokenCount());
        vo.setParentId(str(row.getParentId()));
        vo.setHasEmbedding(Boolean.TRUE.equals(row.getHasEmbedding()));
        vo.setEmbeddingDimension(row.getEmbeddingDimension());
        vo.setCreatedAt(TimeUtils.toMillis(row.getCreatedAt()));

        String content = row.getContent() == null ? "" : row.getContent();
        if (!withContent && content.length() > 120) {
            content = content.substring(0, 120);
        }
        vo.setContent(content);

        if (withEmbedding) {
            List<Double> preview = new ArrayList<>();
            double[] parsed = parsePreview(row.getEmbeddingPreview(), previewDims);
            for (double v : parsed) {
                preview.add(v);
            }
            vo.setEmbeddingPreview(preview);
        }
        return vo;
    }

    /**
     * 解析被截断的向量字面量，只取前 dims 个完整数值。
     * SQL 侧用 left(embedding::text, 320) 取前缀，末位可能是半个数字，这里丢掉。
     */
    static double[] parsePreview(String truncatedLiteral, int dims) {
        if (truncatedLiteral == null || truncatedLiteral.isBlank()) {
            return new double[0];
        }
        String s = truncatedLiteral;
        int close = s.indexOf(']');
        boolean complete = close >= 0;
        if (complete) {
            s = s.substring(0, close);
        }
        if (s.startsWith("[")) {
            s = s.substring(1);
        }
        String[] parts = s.split(",");
        int limit = Math.min(dims, parts.length);
        List<Double> out = new ArrayList<>(limit);
        for (int i = 0; i < limit; i++) {
            String p = parts[i].trim();
            if (p.isEmpty()) {
                break;
            }
            if (!complete && i == parts.length - 1 && p.endsWith("E")) {
                break;
            }
            try {
                out.add(Double.parseDouble(p));
            } catch (NumberFormatException e) {
                if (!complete && i == parts.length - 1) {
                    break;
                }
                throw e;
            }
        }
        double[] arr = new double[out.size()];
        for (int i = 0; i < arr.length; i++) {
            arr[i] = out.get(i);
        }
        return arr;
    }

    public static AsyncTaskVO toTaskVO(AsyncTask t) {
        if (t == null) {
            return null;
        }
        AsyncTaskVO vo = new AsyncTaskVO();
        vo.setTaskId(str(t.getId()));
        vo.setTaskType(t.getTaskType());
        vo.setStatus(t.getStatus());
        vo.setProgress(t.getProgress());
        vo.setCurrentStage(t.getCurrentStage());
        vo.setProgressMessage(t.getProgressMessage());
        vo.setKnowledgeBaseId(str(t.getKnowledgeBaseId()));
        vo.setDocumentId(str(t.getDocumentId()));
        vo.setDocumentName(t instanceof TaskRow row ? row.getDocumentName() : null);
        vo.setRetryCount(t.getRetryCount());
        vo.setErrorMessage(t.getErrorMessage());
        vo.setPayload(JsonUtils.toMap(t.getPayload()));
        vo.setResult(JsonUtils.toMap(t.getResult()));
        vo.setCreatedAt(TimeUtils.toMillis(t.getCreatedAt()));
        vo.setStartedAt(TimeUtils.toMillis(t.getStartedAt()));
        vo.setFinishedAt(TimeUtils.toMillis(t.getFinishedAt()));
        vo.setHeartbeatAt(TimeUtils.toMillis(t.getHeartbeatAt()));
        return vo;
    }

    public static EvalCaseVO toEvalCaseVO(EvalCase c, List<EvalCaseExpectedChunk> expected) {
        EvalCaseVO vo = new EvalCaseVO();
        vo.setId(str(c.getId()));
        vo.setKnowledgeBaseId(str(c.getKnowledgeBaseId()));
        vo.setName(c.getName());
        vo.setQueryText(c.getQueryText());
        vo.setRemark(c.getRemark());
        vo.setEnabled(c.getEnabled());
        vo.setLastRecallAtK(c.getLastRecallAtK());
        vo.setLastMrr(c.getLastMrr());
        vo.setLastEvaluatedAt(TimeUtils.toMillis(c.getLastEvaluatedAt()));
        vo.setCreatedAt(TimeUtils.toMillis(c.getCreatedAt()));
        List<EvalCaseVO.ExpectedChunkVO> list = new ArrayList<>();
        if (expected != null) {
            for (EvalCaseExpectedChunk e : expected) {
                EvalCaseVO.ExpectedChunkVO ec = new EvalCaseVO.ExpectedChunkVO();
                ec.setNodeId(str(e.getNodeId()));
                ec.setDocumentId(str(e.getDocumentId()));
                ec.setChunkIndex(e.getChunkIndex());
                ec.setRelevance(e.getRelevance());
                list.add(ec);
            }
        }
        vo.setExpectedChunks(list);
        return vo;
    }

    public static RetrievalLogVO toRetrievalLogVO(RetrievalLog log) {
        RetrievalLogVO vo = new RetrievalLogVO();
        vo.setId(str(log.getId()));
        vo.setKnowledgeBaseId(str(log.getKnowledgeBaseId()));
        vo.setLogType(log.getLogType());
        vo.setQueryText(log.getQueryText());
        vo.setMode(log.getMode());

        RetrievalLogVO.ParamsVO p = new RetrievalLogVO.ParamsVO();
        p.setTopK(log.getTopK());
        p.setSimilarityThreshold(log.getSimilarityThreshold());
        p.setHybridRatio(log.getHybridRatio());
        p.setBm25Weight(log.getBm25Weight());
        p.setRrfK(log.getRrfK());
        p.setScope(log.getScope());
        p.setLevels(log.getScopeLevels());
        p.setDocumentIds(JsonUtils.toStringList(log.getDocumentIds()));
        vo.setParams(p);

        vo.setResultCount(log.getResultCount());
        vo.setResultNodeIds(JsonUtils.toStringList(log.getResultNodeIds()));

        RetrievalLogVO.LatencyBreakdownVO lb = new RetrievalLogVO.LatencyBreakdownVO();
        lb.setVectorMs(log.getVectorLatencyMs());
        lb.setBm25Ms(log.getBm25LatencyMs());
        lb.setFusionMs(log.getFusionLatencyMs());
        vo.setLatencyBreakdown(lb);
        vo.setLatencyMs(log.getLatencyMs());
        vo.setSuccess(log.getSuccess());
        vo.setErrorMessage(log.getErrorMessage());
        vo.setCreatedAt(TimeUtils.toMillis(log.getCreatedAt()));
        return vo;
    }

    /** 预览用：把 pgvector 字面量解析成 double[]（单测/调试用）。 */
    public static double[] parseLiteral(String literal) {
        return VectorUtils.parse(literal);
    }
}
