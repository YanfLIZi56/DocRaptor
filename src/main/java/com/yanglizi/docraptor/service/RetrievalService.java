package com.yanglizi.docraptor.service;

import com.yanglizi.docraptor.ai.AiGateway;
import com.yanglizi.docraptor.algorithm.RrfFusion;
import com.yanglizi.docraptor.algorithm.TreeFolder;
import com.yanglizi.docraptor.algorithm.VectorUtils;
import com.yanglizi.docraptor.common.BizException;
import com.yanglizi.docraptor.common.ErrorCode;
import com.yanglizi.docraptor.common.JsonUtils;
import com.yanglizi.docraptor.common.TimeUtils;
import com.yanglizi.docraptor.config.DocRaptorProperties;
import com.yanglizi.docraptor.domain.dto.NodeParentPair;
import com.yanglizi.docraptor.domain.dto.SearchRow;
import com.yanglizi.docraptor.domain.entity.DocumentEntity;
import com.yanglizi.docraptor.domain.entity.RetrievalLog;
import com.yanglizi.docraptor.domain.enums.RetrievalMode;
import com.yanglizi.docraptor.domain.enums.RetrievalScope;
import com.yanglizi.docraptor.dto.request.RetrievalRequest;
import com.yanglizi.docraptor.dto.response.RetrievalResultVO;
import com.yanglizi.docraptor.mapper.DocumentMapper;
import com.yanglizi.docraptor.mapper.RetrievalLogMapper;
import com.yanglizi.docraptor.mapper.SummaryNodeMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * 模块3：检索总入口（契约 6.1）。
 *
 * <p>三种模式 VECTOR / BM25 / HYBRID；
 * RRF 公式 {@code score(d) = w_vector·1/(rrfK+vectorRank) + w_bm25·1/(rrfK+bm25Rank)}，某路未召回该路项为 0；
 * 范围 LEAF_ONLY / ALL_LEVELS（折叠树）/ SPECIFIED_LEVEL；
 * 每次检索写 {@code retrieval_log}（含各路原始排名、分数与耗时）。
 */
@Slf4j
@Service
public class RetrievalService {

    /** 至少包含一个可分词字符才走 BM25，避免纯标点查询让 ParadeDB 报空查询。 */
    private static final Pattern TOKENIZABLE = Pattern.compile("[\\p{IsHan}\\p{L}\\p{N}]");

    private final SummaryNodeMapper nodeMapper;
    private final DocumentMapper documentMapper;
    private final com.yanglizi.docraptor.mapper.KnowledgeBaseMapper kbMapper;
    private final RetrievalLogMapper logMapper;
    private final AiGateway aiGateway;
    private final DocRaptorProperties props;
    private final TransactionTemplate txTemplate;

    public RetrievalService(SummaryNodeMapper nodeMapper, DocumentMapper documentMapper,
                            com.yanglizi.docraptor.mapper.KnowledgeBaseMapper kbMapper,
                            RetrievalLogMapper logMapper, AiGateway aiGateway, DocRaptorProperties props,
                            PlatformTransactionManager txManager) {
        this.nodeMapper = nodeMapper;
        this.documentMapper = documentMapper;
        this.kbMapper = kbMapper;
        this.logMapper = logMapper;
        this.aiGateway = aiGateway;
        this.props = props;
        this.txTemplate = new TransactionTemplate(txManager);
    }

    /* ============================== 入口 ============================== */

    public RetrievalResultVO vectorSearch(RetrievalRequest req) {
        return search(req, "VECTOR", "SEARCH").result();
    }

    public RetrievalResultVO bm25Search(RetrievalRequest req) {
        return search(req, "BM25", "SEARCH").result();
    }

    public RetrievalResultVO hybridSearch(RetrievalRequest req) {
        return search(req, "HYBRID", "SEARCH").result();
    }

    /** 检索结果 + 本次落库的 retrieval_log.id（评估需要追溯，所以额外返回）。 */
    public record SearchWithLog(RetrievalResultVO result, UUID logId) {
    }

    /** 检索 + 写 retrieval_log。mode 由接口路径强制，请求体里的 mode 被忽略。 */
    public SearchWithLog search(RetrievalRequest req, String mode, String logType) {
        Normalized n = normalize(req, mode);
        long totalStart = System.nanoTime();
        long embeddingMs = 0;
        long vectorMs = 0;
        long bm25Ms = 0;
        long fusionMs = 0;
        int truncatedByThreshold = 0;
        int collapsedCount = 0;

        List<SearchRow> vectorRows = new ArrayList<>();
        List<SearchRow> bm25Rows = new ArrayList<>();

        // ---------- 1. 向量路 ----------
        if (RetrievalMode.VECTOR.name().equals(mode) || RetrievalMode.HYBRID.name().equals(mode)) {
            long t0 = System.nanoTime();
            float[] qv;
            try {
                qv = aiGateway.embedOne(n.query);
            } catch (BizException e) {
                throw e;
            } catch (Exception e) {
                throw BizException.of(ErrorCode.EMBEDDING_FAILED, e.toString());
            }
            embeddingMs = (System.nanoTime() - t0) / 1_000_000;

            long t1 = System.nanoTime();
            String literal = VectorUtils.toLiteral(qv);
            vectorRows = txTemplate.execute(status -> {
                try {
                    nodeMapper.setHnswEfSearch(props.getRetrieval().getHnswEfSearch());
                } catch (Exception e) {
                    log.debug("SET LOCAL hnsw.ef_search 未生效（可忽略）：{}", e.toString());
                }
                return nodeMapper.vectorSearch(n.kbId, literal, n.candidateK, n.leafOnly, n.specifiedLevel,
                        n.levels, n.documentIds);
            });
            if (vectorRows == null) {
                vectorRows = new ArrayList<>();
            }
            vectorMs = (System.nanoTime() - t1) / 1_000_000;

            // 2.5 相似度阈值：只作用于向量路的 rawScore（硬过滤，不参与 RRF 打分）
            if (n.similarityThreshold > 0) {
                List<SearchRow> kept = new ArrayList<>(vectorRows.size());
                for (SearchRow row : vectorRows) {
                    double score = row.getRawScore() == null ? Double.NEGATIVE_INFINITY : row.getRawScore();
                    if (score < n.similarityThreshold) {
                        truncatedByThreshold++;
                    } else {
                        kept.add(row);
                    }
                }
                vectorRows = kept;
            }
        }

        // ---------- 2. BM25 路 ----------
        if (RetrievalMode.BM25.name().equals(mode) || RetrievalMode.HYBRID.name().equals(mode)) {
            long t1 = System.nanoTime();
            if (TOKENIZABLE.matcher(n.query).find()) {
                bm25Rows = nodeMapper.bm25Search(n.kbId, n.query, n.candidateK, n.leafOnly, n.specifiedLevel,
                        n.levels, n.documentIds);
            } else {
                log.warn("查询串不含可分词字符，跳过 BM25 路：{}", n.query);
            }
            bm25Ms = (System.nanoTime() - t1) / 1_000_000;
        }

        // ---------- 3. 融合 ----------
        long t2 = System.nanoTime();
        List<RetrievalResultVO.HitVO> fused;
        if (RetrievalMode.HYBRID.name().equals(mode)) {
            fused = fuseHybrid(vectorRows, bm25Rows, n);
        } else if (RetrievalMode.VECTOR.name().equals(mode)) {
            fused = singlePath(vectorRows, true, n);
        } else {
            fused = singlePath(bm25Rows, false, n);
        }

        // ---------- 4. scope 折叠（仅 ALL_LEVELS） ----------
        if (RetrievalScope.ALL_LEVELS.name().equals(n.scope) && props.getRetrieval().isFoldTree()) {
            FoldOutcome fo = fold(fused, n.kbId);
            fused = fo.kept;
            collapsedCount = fo.collapsedCount;
        }

        // ---------- 5. 取 topK ----------
        if (fused.size() > n.topK) {
            fused = new ArrayList<>(fused.subList(0, n.topK));
        }
        for (int i = 0; i < fused.size(); i++) {
            fused.get(i).setFinalRank(i + 1);
            if (!n.withContent) {
                String c = fused.get(i).getContent();
                if (c != null && c.length() > 200) {
                    fused.get(i).setContent(c.substring(0, 200));
                }
            }
            if (!n.withScoreBreakdown) {
                fused.get(i).setScoreBreakdown(null);
            }
        }
        fusionMs = (System.nanoTime() - t2) / 1_000_000;
        long totalMs = (System.nanoTime() - totalStart) / 1_000_000;

        RetrievalResultVO vo = new RetrievalResultVO();
        vo.setQuery(n.query);
        vo.setMode(mode);
        vo.setKnowledgeBaseId(n.kbId.toString());
        vo.setScope(n.scope);
        RetrievalResultVO.ParamsVO params = new RetrievalResultVO.ParamsVO();
        params.setTopK(n.topK);
        params.setSimilarityThreshold(n.similarityThreshold);
        params.setHybridRatio(n.hybridRatio);
        params.setBm25Weight(n.bm25Weight);
        params.setRrfK(n.rrfK);
        params.setLevels(n.levels);
        params.setDocumentIds(n.documentIdStrings);
        vo.setParams(params);

        RetrievalResultVO.CostBreakdownVO cost = new RetrievalResultVO.CostBreakdownVO();
        cost.setEmbeddingMs(embeddingMs);
        cost.setVectorMs(vectorMs);
        cost.setBm25Ms(bm25Ms);
        cost.setFusionMs(fusionMs);
        cost.setTotalMs(totalMs);
        vo.setCostMs(totalMs);
        vo.setCostBreakdown(cost);
        vo.setTotalHits(fused.size());
        vo.setCollapsedCount(collapsedCount);
        vo.setTruncatedByThreshold(truncatedByThreshold);
        vo.setHits(fused);

        // ---------- 6. 写检索日志 ----------
        UUID logId = null;
        if (props.getRetrieval().isLogEnabled()) {
            logId = writeLog(n, mode, logType, fused, (int) totalMs, (int) vectorMs, (int) bm25Ms, (int) fusionMs);
        }
        return new SearchWithLog(vo, logId);
    }

    /* ============================== 检索日志查询（契约 6.2） ============================== */

    public com.yanglizi.docraptor.common.PageResult<com.yanglizi.docraptor.dto.response.RetrievalLogVO> listLogs(
            String knowledgeBaseId, String mode, String logType, String queryKeyword,
            Long startTime, Long endTime, Integer page, Integer pageSize) {
        int[] pg = Paging.normalize(page, pageSize);
        if (mode != null && !mode.isBlank()
                && !RetrievalMode.isValid(mode) && !"EVAL".equals(mode)) {
            throw BizException.of(ErrorCode.MODE_INVALID);
        }
        if (startTime != null && endTime != null && startTime > endTime) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "startTime 不能大于 endTime");
        }
        UUID kbId = (knowledgeBaseId == null || knowledgeBaseId.isBlank())
                ? null : TimeUtils.parseUuid(knowledgeBaseId, "knowledgeBaseId");
        List<RetrievalLog> rows = logMapper.selectPage(kbId, mode, logType, queryKeyword, startTime, endTime,
                (pg[0] - 1) * pg[1], pg[1]);
        long total = logMapper.countPage(kbId, mode, logType, queryKeyword, startTime, endTime);
        List<com.yanglizi.docraptor.dto.response.RetrievalLogVO> list = new ArrayList<>(rows.size());
        for (RetrievalLog row : rows) {
            list.add(VoConverter.toRetrievalLogVO(row));
        }
        return com.yanglizi.docraptor.common.PageResult.of(list, total, pg[0], pg[1]);
    }

    /* ============================== 参数归一化 ============================== */

    private static class Normalized {
        UUID kbId;
        String query;
        int topK;
        double similarityThreshold;
        double hybridRatio;
        double bm25Weight;
        int rrfK;
        String scope;
        List<Integer> levels;
        List<UUID> documentIds;
        List<String> documentIdStrings;
        boolean withContent = true;
        boolean withScoreBreakdown = true;
        int candidateK;
        boolean leafOnly;
        boolean specifiedLevel;
    }

    private Normalized normalize(RetrievalRequest req, String mode) {
        Normalized n = new Normalized();
        var r = props.getRetrieval();

        n.kbId = TimeUtils.parseUuid(req.getKnowledgeBaseId(), "knowledgeBaseId");
        if (kbMapper.selectById(n.kbId) == null) {
            throw BizException.of(ErrorCode.KNOWLEDGE_BASE_NOT_FOUND);
        }
        if (!RetrievalMode.isValid(mode)) {
            throw BizException.of(ErrorCode.MODE_INVALID);
        }

        n.query = req.getQuery() == null ? "" : req.getQuery();
        if (n.query.isBlank() || n.query.length() > 2000) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "query 必填且长度需在 1~2000 之间");
        }

        n.topK = req.getTopK() == null ? r.getDefaultTopK() : req.getTopK();
        if (n.topK > r.getMaxTopK()) {
            throw BizException.of(ErrorCode.TOP_K_EXCEEDED);
        }
        if (n.topK < 1) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "topK 必须 ≥ 1");
        }

        n.similarityThreshold = req.getSimilarityThreshold() == null
                ? r.getDefaultSimilarityThreshold() : req.getSimilarityThreshold();
        if (n.similarityThreshold < 0 || n.similarityThreshold > 1) {
            throw BizException.of(ErrorCode.RATIO_OUT_OF_RANGE);
        }

        n.hybridRatio = req.getHybridRatio() == null ? r.getDefaultHybridRatio() : req.getHybridRatio();
        if (n.hybridRatio < 0 || n.hybridRatio > 1) {
            throw BizException.of(ErrorCode.RATIO_OUT_OF_RANGE);
        }

        n.bm25Weight = req.getBm25Weight() == null ? r.getDefaultBm25Weight() : req.getBm25Weight();
        if (n.bm25Weight < 0 || n.bm25Weight > 10) {
            throw BizException.of(ErrorCode.RATIO_OUT_OF_RANGE);
        }

        n.rrfK = req.getRrfK() == null ? r.getDefaultRrfK() : req.getRrfK();
        if (n.rrfK < 1 || n.rrfK > 1000) {
            throw BizException.of(ErrorCode.RRF_K_OUT_OF_RANGE);
        }

        n.scope = req.getScope() == null ? r.getDefaultScope() : req.getScope();
        if (!RetrievalScope.isValid(n.scope)) {
            throw BizException.of(ErrorCode.SCOPE_INVALID);
        }
        n.levels = req.getLevels();
        if (RetrievalScope.SPECIFIED_LEVEL.name().equals(n.scope)) {
            if (n.levels == null || n.levels.isEmpty()) {
                throw BizException.of(ErrorCode.LEVELS_REQUIRED);
            }
            for (Integer lv : n.levels) {
                if (lv == null || lv < 0 || lv > 10) {
                    throw BizException.of(ErrorCode.PARAM_INVALID, "levels 元素需在 [0, 10] 之间");
                }
            }
        } else {
            n.levels = null;
        }
        n.leafOnly = RetrievalScope.LEAF_ONLY.name().equals(n.scope);
        n.specifiedLevel = RetrievalScope.SPECIFIED_LEVEL.name().equals(n.scope);

        n.documentIds = new ArrayList<>();
        n.documentIdStrings = new ArrayList<>();
        if (req.getDocumentIds() != null && !req.getDocumentIds().isEmpty()) {
            boolean anyEnabled = false;
            for (String idStr : req.getDocumentIds()) {
                UUID id = TimeUtils.parseUuid(idStr, "documentIds");
                n.documentIds.add(id);
                n.documentIdStrings.add(id.toString());
                DocumentEntity doc = documentMapper.selectById(id);
                if (doc != null && Boolean.TRUE.equals(doc.getEnabled())) {
                    anyEnabled = true;
                }
            }
            if (!anyEnabled) {
                throw BizException.of(ErrorCode.DOCUMENT_DISABLED);
            }
        } else {
            n.documentIds = null;
            n.documentIdStrings = null;
        }

        n.withContent = req.getWithContent() == null || req.getWithContent();
        n.withScoreBreakdown = req.getWithScoreBreakdown() == null || req.getWithScoreBreakdown();
        n.candidateK = Math.min(Math.max(n.topK * Math.max(1, r.getCandidateMultiplier()), n.topK),
                r.getCandidateMax());
        return n;
    }

    /* ============================== 融合 ============================== */

    /** 单路模式：finalScore 直接就是该路 rawScore，加权贡献字段为 null（契约 6.1 字段语义表）。 */
    private List<RetrievalResultVO.HitVO> singlePath(List<SearchRow> rows, boolean isVector, Normalized n) {
        List<RetrievalResultVO.HitVO> out = new ArrayList<>(rows.size());
        for (SearchRow row : rows) {
            RetrievalResultVO.HitVO hit = toHit(row);
            double raw = row.getRawScore() == null ? 0.0 : row.getRawScore();
            hit.setFinalScore(raw);
            RetrievalResultVO.ScoreBreakdownVO sb = new RetrievalResultVO.ScoreBreakdownVO();
            sb.setRrfK(n.rrfK);
            if (isVector) {
                sb.setVectorRawScore(row.getRawScore());
                sb.setVectorRank(out.size() + 1);
            } else {
                sb.setBm25RawScore(row.getRawScore());
                sb.setBm25Rank(out.size() + 1);
            }
            hit.setScoreBreakdown(sb);
            out.add(hit);
        }
        return out;
    }

    private List<RetrievalResultVO.HitVO> fuseHybrid(List<SearchRow> vectorRows, List<SearchRow> bm25Rows,
                                                     Normalized n) {
        Map<String, SearchRow> byId = new HashMap<>();
        List<RrfFusion.Ranked> vList = new ArrayList<>(vectorRows.size());
        for (SearchRow row : vectorRows) {
            String id = row.getNodeId().toString();
            byId.put(id, row);
            vList.add(new RrfFusion.Ranked(id, row.getRawScore() == null ? 0.0 : row.getRawScore()));
        }
        List<RrfFusion.Ranked> bList = new ArrayList<>(bm25Rows.size());
        for (SearchRow row : bm25Rows) {
            String id = row.getNodeId().toString();
            byId.put(id, row);
            bList.add(new RrfFusion.Ranked(id, row.getRawScore() == null ? 0.0 : row.getRawScore()));
        }

        double wVector = n.hybridRatio;
        double wBm25 = RrfFusion.bm25Weight(n.hybridRatio, n.bm25Weight);
        List<RrfFusion.Fused> fused = RrfFusion.fuse(vList, bList, wVector, wBm25, n.rrfK);

        List<RetrievalResultVO.HitVO> out = new ArrayList<>(fused.size());
        for (RrfFusion.Fused f : fused) {
            SearchRow row = byId.get(f.getId());
            if (row == null) {
                continue;
            }
            RetrievalResultVO.HitVO hit = toHit(row);
            hit.setFinalScore(f.getFinalScore());
            RetrievalResultVO.ScoreBreakdownVO sb = new RetrievalResultVO.ScoreBreakdownVO();
            sb.setVectorRawScore(f.getVectorRawScore());
            sb.setVectorRank(f.getVectorRank());
            sb.setVectorWeightedScore(f.getVectorWeightedScore());
            sb.setBm25RawScore(f.getBm25RawScore());
            sb.setBm25Rank(f.getBm25Rank());
            sb.setBm25WeightedScore(f.getBm25WeightedScore());
            sb.setRrfK(n.rrfK);
            hit.setScoreBreakdown(sb);
            out.add(hit);
        }
        return out;
    }

    /* ============================== 折叠树 ============================== */

    private static class FoldOutcome {
        List<RetrievalResultVO.HitVO> kept;
        int collapsedCount;
    }

    /** 同一条祖先链上只保留最终排名最高的节点（架构 6.3）。 */
    private FoldOutcome fold(List<RetrievalResultVO.HitVO> hits, UUID kbId) {
        FoldOutcome outcome = new FoldOutcome();
        List<NodeParentPair> pairs = nodeMapper.selectIdParentPairs(kbId);
        Map<String, String> parentOf = new HashMap<>(pairs.size() * 2);
        for (NodeParentPair p : pairs) {
            parentOf.put(p.getId().toString(), p.getParentId() == null ? null : p.getParentId().toString());
        }
        List<String> ordered = new ArrayList<>(hits.size());
        Map<String, RetrievalResultVO.HitVO> byId = new HashMap<>();
        for (RetrievalResultVO.HitVO h : hits) {
            ordered.add(h.getNodeId());
            byId.put(h.getNodeId(), h);
        }
        TreeFolder.Result result = TreeFolder.fold(ordered, parentOf);
        List<RetrievalResultVO.HitVO> kept = new ArrayList<>(result.kept().size());
        for (String id : result.kept()) {
            kept.add(byId.get(id));
        }
        outcome.kept = kept;
        outcome.collapsedCount = result.collapsedCount();
        return outcome;
    }

    /* ============================== 辅助 ============================== */

    private static RetrievalResultVO.HitVO toHit(SearchRow row) {
        RetrievalResultVO.HitVO hit = new RetrievalResultVO.HitVO();
        hit.setNodeId(row.getNodeId().toString());
        hit.setNodeType(row.getNodeType());
        hit.setLevel(row.getLevel());
        hit.setDocumentId(row.getDocumentId() == null ? null : row.getDocumentId().toString());
        hit.setDocumentName(row.getDocumentName());
        hit.setDocumentEnabled(row.getDocumentEnabled());
        hit.setChunkIndex(row.getChunkIndex());
        hit.setStartChunkIndex(row.getStartChunkIndex());
        hit.setEndChunkIndex(row.getEndChunkIndex());
        hit.setCharCount(row.getCharCount());
        hit.setContent(row.getContent());
        hit.setCollapsedByNodeId(null);
        return hit;
    }

    private UUID writeLog(Normalized n, String mode, String logType, List<RetrievalResultVO.HitVO> hits,
                          int totalMs, int vectorMs, int bm25Ms, int fusionMs) {
        try {
            RetrievalLog entry = new RetrievalLog();
            entry.setId(UUID.randomUUID());
            entry.setKnowledgeBaseId(n.kbId);
            entry.setLogType(logType);
            entry.setQueryText(n.query.length() > 2000 ? n.query.substring(0, 2000) : n.query);
            entry.setMode(mode);
            entry.setTopK(n.topK);
            entry.setSimilarityThreshold(n.similarityThreshold);
            entry.setHybridRatio(n.hybridRatio);
            entry.setBm25Weight(n.bm25Weight);
            entry.setRrfK(n.rrfK);
            entry.setScope(n.scope);
            entry.setScopeLevels(levelsToCsv(n.levels));
            entry.setDocumentIds(n.documentIdStrings == null ? null : JsonUtils.toJson(n.documentIdStrings));
            entry.setResultCount(hits.size());
            List<String> ids = new ArrayList<>(hits.size());
            for (RetrievalResultVO.HitVO h : hits) {
                ids.add(h.getNodeId());
            }
            entry.setResultNodeIds(JsonUtils.toJson(ids));
            entry.setLatencyMs(totalMs);
            entry.setVectorLatencyMs(vectorMs);
            entry.setBm25LatencyMs(bm25Ms);
            entry.setFusionLatencyMs(fusionMs);
            entry.setSuccess(true);
            logMapper.insert(entry);
            return entry.getId();
        } catch (Exception e) {
            // 检索日志写失败不能影响检索结果
            log.warn("写 retrieval_log 失败（不影响检索结果）：{}", e.toString());
            return null;
        }
    }

    static String levelsToCsv(List<Integer> levels) {
        if (levels == null || levels.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < levels.size(); i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(levels.get(i));
        }
        return sb.toString();
    }
}
