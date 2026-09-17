package com.yanglizi.docraptor.service;

import com.yanglizi.docraptor.algorithm.EvalMetrics;
import com.yanglizi.docraptor.common.BizException;
import com.yanglizi.docraptor.common.ErrorCode;
import com.yanglizi.docraptor.common.PageResult;
import com.yanglizi.docraptor.common.TimeUtils;
import com.yanglizi.docraptor.config.DocRaptorProperties;
import com.yanglizi.docraptor.domain.entity.EvalCase;
import com.yanglizi.docraptor.domain.entity.EvalCaseExpectedChunk;
import com.yanglizi.docraptor.domain.entity.SummaryNode;
import com.yanglizi.docraptor.domain.enums.RetrievalMode;
import com.yanglizi.docraptor.domain.enums.RetrievalScope;
import com.yanglizi.docraptor.dto.request.EvalCaseRequest;
import com.yanglizi.docraptor.dto.request.EvalRunRequest;
import com.yanglizi.docraptor.dto.request.RetrievalRequest;
import com.yanglizi.docraptor.dto.response.EvalCaseVO;
import com.yanglizi.docraptor.dto.response.EvalRunVO;
import com.yanglizi.docraptor.dto.response.RetrievalResultVO;
import com.yanglizi.docraptor.dto.response.SimpleVOs;
import com.yanglizi.docraptor.mapper.EvalCaseExpectedChunkMapper;
import com.yanglizi.docraptor.mapper.EvalCaseMapper;
import com.yanglizi.docraptor.mapper.SummaryNodeMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 模块3：召回测试与评估（契约 6.3 / 6.4 / 6.5）。
 *
 * <p><b>口径（严格口径）</b>：命中判定必须命中期望的<b>叶子块 ID 本身</b>，命中其父摘要节点不算命中；
 * 数据集级指标 = 对未跳过用例（期望块数 &gt; 0）求算术平均。
 */
@Slf4j
@Service
public class EvalService {

    private final EvalCaseMapper caseMapper;
    private final EvalCaseExpectedChunkMapper expectedMapper;
    private final SummaryNodeMapper nodeMapper;
    private final RetrievalService retrievalService;
    private final KnowledgeBaseService knowledgeBaseService;
    private final DocRaptorProperties props;

    public EvalService(EvalCaseMapper caseMapper, EvalCaseExpectedChunkMapper expectedMapper,
                       SummaryNodeMapper nodeMapper, RetrievalService retrievalService,
                       KnowledgeBaseService knowledgeBaseService, DocRaptorProperties props) {
        this.caseMapper = caseMapper;
        this.expectedMapper = expectedMapper;
        this.nodeMapper = nodeMapper;
        this.retrievalService = retrievalService;
        this.knowledgeBaseService = knowledgeBaseService;
        this.props = props;
    }

    /* ============================ 用例 CRUD ============================ */

    @Transactional
    public EvalCaseVO createCase(EvalCaseRequest req) {
        UUID kbId = TimeUtils.parseUuid(req.getKnowledgeBaseId(), "knowledgeBaseId");
        knowledgeBaseService.require(kbId);

        String name = req.getName() == null ? "" : req.getName().strip();
        if (name.isEmpty() || name.length() > 128) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "name 必填且长度需在 1~128 之间");
        }
        String query = req.getQueryText() == null ? "" : req.getQueryText().strip();
        if (query.isEmpty()) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "queryText 不能为空");
        }
        if (caseMapper.countByName(kbId, name, null) > 0) {
            throw BizException.of(ErrorCode.NAME_DUPLICATED, name);
        }
        List<UUID> expectedIds = parseExpectedIds(req.getExpectedChunkIds());
        List<EvalCaseExpectedChunk> expected = validateAndBuildExpected(null, kbId, expectedIds);

        EvalCase c = new EvalCase();
        c.setId(UUID.randomUUID());
        c.setKnowledgeBaseId(kbId);
        c.setName(name);
        c.setQueryText(query);
        c.setRemark(req.getRemark());
        c.setEnabled(req.getEnabled() == null || req.getEnabled());
        try {
            caseMapper.insert(c);
        } catch (DuplicateKeyException e) {
            throw BizException.of(ErrorCode.NAME_DUPLICATED, name);
        }
        for (EvalCaseExpectedChunk row : expected) {
            row.setEvalCaseId(c.getId());
        }
        if (!expected.isEmpty()) {
            expectedMapper.insertBatch(expected);
        }
        return reload(c.getId());
    }

    public PageResult<EvalCaseVO> listCases(String knowledgeBaseId, Boolean enabled, Integer page, Integer pageSize) {
        int[] pg = Paging.normalize(page, pageSize);
        UUID kbId = null;
        if (knowledgeBaseId != null && !knowledgeBaseId.isBlank()) {
            kbId = TimeUtils.parseUuid(knowledgeBaseId, "knowledgeBaseId");
            knowledgeBaseService.require(kbId);
        }
        List<EvalCase> rows = caseMapper.selectPage(kbId, enabled, (pg[0] - 1) * pg[1], pg[1]);
        long total = caseMapper.countPage(kbId, enabled);
        List<EvalCaseVO> list = new ArrayList<>(rows.size());
        for (EvalCase c : rows) {
            list.add(VoConverter.toEvalCaseVO(c, expectedMapper.selectByCaseId(c.getId())));
        }
        return PageResult.of(list, total, pg[0], pg[1]);
    }

    @Transactional
    public EvalCaseVO updateCase(String id, EvalCaseRequest req) {
        UUID caseId = TimeUtils.parseUuid(id, "id");
        EvalCase c = requireCase(caseId);

        String name = req.getName() == null ? c.getName() : req.getName().strip();
        if (name.isEmpty() || name.length() > 128) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "name 长度需在 1~128 之间");
        }
        if (!name.equals(c.getName()) && caseMapper.countByName(c.getKnowledgeBaseId(), name, caseId) > 0) {
            throw BizException.of(ErrorCode.NAME_DUPLICATED, name);
        }
        if (req.getQueryText() != null) {
            String q = req.getQueryText().strip();
            if (q.isEmpty()) {
                throw BizException.of(ErrorCode.PARAM_INVALID, "queryText 不能为空");
            }
            c.setQueryText(q);
        }
        c.setName(name);
        if (req.getRemark() != null) {
            c.setRemark(req.getRemark());
        }
        if (req.getEnabled() != null) {
            c.setEnabled(req.getEnabled());
        }
        caseMapper.update(c);

        // expectedChunkIds 传 null 表示不改，传数组表示整体替换
        if (req.getExpectedChunkIds() != null) {
            List<UUID> ids = parseExpectedIds(req.getExpectedChunkIds());
            List<EvalCaseExpectedChunk> expected = validateAndBuildExpected(caseId, c.getKnowledgeBaseId(), ids);
            expectedMapper.deleteByCaseId(caseId);
            for (EvalCaseExpectedChunk row : expected) {
                row.setEvalCaseId(caseId);
            }
            if (!expected.isEmpty()) {
                expectedMapper.insertBatch(expected);
            }
        }
        return reload(caseId);
    }

    @Transactional
    public SimpleVOs.DeletedVO deleteCase(String id) {
        UUID caseId = TimeUtils.parseUuid(id, "id");
        requireCase(caseId);
        expectedMapper.deleteByCaseId(caseId);
        caseMapper.deleteById(caseId);
        return SimpleVOs.DeletedVO.of(id);
    }

    private EvalCaseVO reload(UUID caseId) {
        EvalCase c = requireCase(caseId);
        return VoConverter.toEvalCaseVO(c, expectedMapper.selectByCaseId(caseId));
    }

    private EvalCase requireCase(UUID caseId) {
        EvalCase c = caseMapper.selectById(caseId);
        if (c == null) {
            throw BizException.of(ErrorCode.EVAL_CASE_NOT_FOUND);
        }
        return c;
    }

    private static List<UUID> parseExpectedIds(List<String> raw) {
        if (raw == null || raw.isEmpty()) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "expectedChunkIds 至少需要 1 个元素");
        }
        List<UUID> ids = new ArrayList<>(raw.size());
        for (String s : raw) {
            ids.add(TimeUtils.parseUuid(s, "expectedChunkIds"));
        }
        return ids;
    }

    /** 校验期望块：必须是该知识库下的 LEAF 节点（契约 40010），且不能有被禁用的文档。 */
    private List<EvalCaseExpectedChunk> validateAndBuildExpected(UUID caseId, UUID kbId, List<UUID> nodeIds) {
        List<SummaryNode> nodes = nodeMapper.selectByIds(nodeIds);
        java.util.Map<UUID, SummaryNode> byId = new java.util.HashMap<>();
        for (SummaryNode n : nodes) {
            byId.put(n.getId(), n);
        }
        List<EvalCaseExpectedChunk> rows = new ArrayList<>(nodeIds.size());
        Set<UUID> seen = new HashSet<>();
        for (UUID id : nodeIds) {
            SummaryNode n = byId.get(id);
            if (n == null || !"LEAF".equals(n.getNodeType()) || !kbId.equals(n.getKnowledgeBaseId())) {
                throw BizException.of(ErrorCode.EXPECTED_CHUNK_INVALID, id);
            }
            if (!seen.add(id)) {
                continue;
            }
            EvalCaseExpectedChunk row = new EvalCaseExpectedChunk();
            row.setId(UUID.randomUUID());
            row.setEvalCaseId(caseId);
            row.setNodeId(id);
            row.setDocumentId(n.getDocumentId());
            row.setChunkIndex(n.getChunkIndex());
            row.setRelevance(1);
            rows.add(row);
        }
        return rows;
    }

    /* ============================ 评估执行 ============================ */

    /** 同步评估（契约 6.5，async=false）。 */
    public EvalRunVO runSync(EvalRunRequest req) {
        return execute(req, null, null);
    }

    /**
     * 评估执行主体。taskId/reporter 非空时同时写任务进度（async=true 的路径）。
     */
    public EvalRunVO execute(EvalRunRequest req, UUID taskId,
                             com.yanglizi.docraptor.async.AsyncTaskProgressReporter reporter) {
        UUID kbId = TimeUtils.parseUuid(req.getKnowledgeBaseId(), "knowledgeBaseId");
        knowledgeBaseService.require(kbId);

        String mode = req.getMode() == null ? props.getRetrieval().getDefaultMode() : req.getMode();
        if (!RetrievalMode.isValid(mode)) {
            throw BizException.of(ErrorCode.MODE_INVALID);
        }
        int topK = req.getTopK() == null ? props.getRetrieval().getDefaultTopK() : req.getTopK();
        if (topK > props.getRetrieval().getMaxTopK()) {
            throw BizException.of(ErrorCode.TOP_K_EXCEEDED);
        }
        if (topK < 1) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "topK 必须 ≥ 1");
        }
        String scope = req.getScope() == null ? props.getRetrieval().getDefaultScope() : req.getScope();
        if (!RetrievalScope.isValid(scope)) {
            throw BizException.of(ErrorCode.SCOPE_INVALID);
        }
        List<Integer> levels = req.getLevels();
        if (RetrievalScope.SPECIFIED_LEVEL.name().equals(scope) && (levels == null || levels.isEmpty())) {
            throw BizException.of(ErrorCode.LEVELS_REQUIRED);
        }

        // kList：默认 [1,3,5,10]；> topK 的 K 被裁剪并在 truncatedKList 说明
        List<Integer> requestedK = req.getKList() == null ? parseDefaultKList() : req.getKList();
        List<Integer> kList = new ArrayList<>();
        List<Integer> truncated = new ArrayList<>();
        for (Integer k : requestedK) {
            if (k == null || k < 1) {
                throw BizException.of(ErrorCode.PARAM_INVALID, "kList 元素必须 ≥ 1");
            }
            if (k > topK) {
                truncated.add(k);
            } else if (!kList.contains(k)) {
                kList.add(k);
            }
        }
        if (kList.isEmpty()) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "kList 在裁剪后为空（所有 K 都大于 topK=" + topK + "）");
        }
        kList.sort(Comparator.naturalOrder());

        // 选用例
        List<EvalCase> cases;
        if (req.getCaseIds() != null && !req.getCaseIds().isEmpty()) {
            List<UUID> ids = new ArrayList<>();
            for (String s : req.getCaseIds()) {
                ids.add(TimeUtils.parseUuid(s, "caseIds"));
            }
            cases = caseMapper.selectByIds(ids);
            List<EvalCase> enabledOnly = new ArrayList<>();
            for (EvalCase c : cases) {
                if (Boolean.TRUE.equals(c.getEnabled()) && kbId.equals(c.getKnowledgeBaseId())) {
                    enabledOnly.add(c);
                }
            }
            if (enabledOnly.isEmpty()) {
                throw BizException.of(ErrorCode.EVAL_CASE_DISABLED);
            }
            cases = enabledOnly;
        } else {
            cases = caseMapper.selectEnabledByKnowledgeBase(kbId);
            if (cases.isEmpty()) {
                throw BizException.of(ErrorCode.EVAL_CASE_DISABLED);
            }
        }

        EvalRunVO out = new EvalRunVO();
        out.setKnowledgeBaseId(kbId.toString());
        out.setMode(mode);
        out.setTopK(topK);
        EvalRunVO.EvalParamsVO params = new EvalRunVO.EvalParamsVO();
        params.setSimilarityThreshold(req.getSimilarityThreshold() == null
                ? props.getRetrieval().getDefaultSimilarityThreshold() : req.getSimilarityThreshold());
        params.setHybridRatio(req.getHybridRatio() == null
                ? props.getRetrieval().getDefaultHybridRatio() : req.getHybridRatio());
        params.setBm25Weight(req.getBm25Weight() == null
                ? props.getRetrieval().getDefaultBm25Weight() : req.getBm25Weight());
        params.setRrfK(req.getRrfK() == null ? props.getRetrieval().getDefaultRrfK() : req.getRrfK());
        params.setScope(scope);
        params.setLevels(levels);
        // 与检索接口共用同一套取值范围校验（40005 / 40006）
        if (params.getSimilarityThreshold() < 0 || params.getSimilarityThreshold() > 1
                || params.getHybridRatio() < 0 || params.getHybridRatio() > 1
                || params.getBm25Weight() < 0 || params.getBm25Weight() > 10) {
            throw BizException.of(ErrorCode.RATIO_OUT_OF_RANGE);
        }
        if (params.getRrfK() < 1 || params.getRrfK() > 1000) {
            throw BizException.of(ErrorCode.RRF_K_OUT_OF_RANGE);
        }
        out.setParams(params);
        out.setTruncatedKList(truncated);

        List<EvalRunVO.PerQueryVO> perQuery = new ArrayList<>();
        List<Double> recallAtK = new ArrayList<>();
        List<Double> hitRateAtK = new ArrayList<>();
        List<Double> rrAtK = new ArrayList<>();
        List<Double> recallAtTopK = new ArrayList<>();
        List<Double> hitRateAtTopK = new ArrayList<>();
        List<Double> rrAtTopK = new ArrayList<>();
        for (int i = 0; i < kList.size(); i++) {
            recallAtK.add(0.0);
            hitRateAtK.add(0.0);
            rrAtK.add(0.0);
        }
        long totalLatency = 0;
        int evaluated = 0;

        for (int ci = 0; ci < cases.size(); ci++) {
            EvalCase c = cases.get(ci);
            List<EvalCaseExpectedChunk> expected = expectedMapper.selectByCaseId(c.getId());
            Set<String> expectedIds = new LinkedHashSet<>();
            List<String> expectedIdList = new ArrayList<>();
            for (EvalCaseExpectedChunk e : expected) {
                // relevance=0 为弱相关，不参与计算
                if (e.getRelevance() != null && e.getRelevance() == 0) {
                    continue;
                }
                String nid = e.getNodeId().toString();
                if (expectedIds.add(nid)) {
                    expectedIdList.add(nid);
                }
            }
            if (expectedIds.isEmpty()) {
                EvalRunVO.SkippedCaseVO skipped = new EvalRunVO.SkippedCaseVO();
                skipped.setCaseId(c.getId().toString());
                skipped.setName(c.getName());
                skipped.setReason("EXPECTED_EMPTY");
                out.getSkippedCases().add(skipped);
                continue;
            }

            RetrievalRequest rreq = new RetrievalRequest();
            rreq.setKnowledgeBaseId(kbId.toString());
            rreq.setQuery(c.getQueryText());
            rreq.setTopK(topK);
            rreq.setSimilarityThreshold(params.getSimilarityThreshold());
            rreq.setHybridRatio(params.getHybridRatio());
            rreq.setBm25Weight(params.getBm25Weight());
            rreq.setRrfK(params.getRrfK());
            rreq.setScope(scope);
            rreq.setLevels(levels);
            rreq.setWithContent(false);
            rreq.setWithScoreBreakdown(false);

            long t0 = System.currentTimeMillis();
            RetrievalService.SearchWithLog swl = retrievalService.search(rreq, mode, "EVAL");
            long latency = System.currentTimeMillis() - t0;
            totalLatency += latency;
            RetrievalResultVO result = swl.result();

            List<String> retrieved = new ArrayList<>(result.getHits().size());
            for (RetrievalResultVO.HitVO h : result.getHits()) {
                retrieved.add(h.getNodeId());
            }
            if (swl.logId() != null) {
                out.getRetrievalLogIds().add(swl.logId().toString());
            }

            EvalRunVO.PerQueryVO pq = new EvalRunVO.PerQueryVO();
            pq.setCaseId(c.getId().toString());
            pq.setName(c.getName());
            pq.setQuery(c.getQueryText());
            pq.setExpectedChunkIds(expectedIdList);
            pq.setRetrievedNodeIds(retrieved);
            pq.setLatencyMs(latency);
            pq.setRetrievalLogId(swl.logId() == null ? null : swl.logId().toString());

            Set<String> hitAll = new LinkedHashSet<>();
            int firstHit = 0;
            for (int i = 0; i < kList.size(); i++) {
                int k = kList.get(i);
                EvalMetrics.QueryMetrics qm = EvalMetrics.compute(retrieved, expectedIds, k);
                EvalRunVO.PerQueryMetricVO pm = new EvalRunVO.PerQueryMetricVO();
                pm.setK(k);
                pm.setRecall(EvalMetrics.round4(qm.recall()));
                pm.setHitRate((int) qm.hitRate());
                pm.setReciprocalRank(EvalMetrics.round4(qm.reciprocalRank()));
                pq.getMetrics().add(pm);
                recallAtK.set(i, recallAtK.get(i) + qm.recall());
                hitRateAtK.set(i, hitRateAtK.get(i) + qm.hitRate());
                rrAtK.set(i, rrAtK.get(i) + qm.reciprocalRank());
            }
            // 数据集 MRR 的 k=topK 口径
            EvalMetrics.QueryMetrics atTopK = EvalMetrics.compute(retrieved, expectedIds, topK);
            recallAtTopK.add(atTopK.recall());
            hitRateAtTopK.add(atTopK.hitRate());
            rrAtTopK.add(atTopK.reciprocalRank());
            hitAll.addAll(atTopK.hitIds());
            if (!atTopK.hitRanks().isEmpty()) {
                firstHit = atTopK.hitRanks().get(0);
            }

            pq.setHitNodeIds(new ArrayList<>(hitAll));
            List<String> missed = new ArrayList<>();
            for (String id : expectedIdList) {
                if (!hitAll.contains(id)) {
                    missed.add(id);
                }
            }
            pq.setMissedNodeIds(missed);
            pq.setFirstHitRank(firstHit == 0 ? null : firstHit);
            perQuery.add(pq);
            evaluated++;

            // 回写用例最近一次指标
            caseMapper.updateLastMetrics(c.getId(), EvalMetrics.round4(atTopK.recall()),
                    EvalMetrics.round4(atTopK.reciprocalRank()));
            if (reporter != null) {
                reporter.progress(taskId, com.yanglizi.docraptor.async.AsyncTaskService.stageProgress(
                        "EVAL", (int) Math.round((ci + 1) * 100.0 / cases.size())), "EVAL",
                        "已评估 " + (ci + 1) + "/" + cases.size() + " 条用例");
            }
        }

        if (evaluated == 0) {
            throw BizException.of(ErrorCode.EVAL_CASE_DISABLED);
        }

        for (int i = 0; i < kList.size(); i++) {
            EvalRunVO.MetricVO m = new EvalRunVO.MetricVO();
            m.setK(kList.get(i));
            m.setRecall(EvalMetrics.round4(recallAtK.get(i) / evaluated));
            m.setHitRate(EvalMetrics.round4(hitRateAtK.get(i) / evaluated));
            // mrr：数据集级 MRR@k（各用例 1/RR_q@k 的算术平均）
            m.setMrr(EvalMetrics.round4(rrAtK.get(i) / evaluated));
            out.getMetrics().add(m);
        }
        out.setEvaluatedCases(evaluated);
        out.setAvgLatencyMs(evaluated == 0 ? 0 : totalLatency / evaluated);
        out.setPerQuery(perQuery);
        log.info("评估完成 kb={} mode={} topK={} 用例={} 跳过={} Recall@{}={} MRR@{}={}",
                kbId, mode, topK, evaluated, out.getSkippedCases().size(), topK,
                EvalMetrics.round4(recallAtTopK.isEmpty() ? 0 : EvalMetrics.mean(recallAtTopK)),
                topK, EvalMetrics.round4(rrAtTopK.isEmpty() ? 0 : EvalMetrics.mean(rrAtTopK)));
        return out;
    }

    private List<Integer> parseDefaultKList() {
        List<Integer> list = new ArrayList<>();
        for (String s : props.getEval().getDefaultKList().split(",")) {
            if (!s.isBlank()) {
                list.add(Integer.parseInt(s.trim()));
            }
        }
        return list;
    }
}
