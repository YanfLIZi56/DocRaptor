package com.yanglizi.docraptor.service;

import com.yanglizi.docraptor.common.BizException;
import com.yanglizi.docraptor.common.ErrorCode;
import com.yanglizi.docraptor.common.PageResult;
import com.yanglizi.docraptor.common.TimeUtils;
import com.yanglizi.docraptor.config.DocRaptorProperties;
import com.yanglizi.docraptor.domain.entity.KnowledgeBase;
import com.yanglizi.docraptor.domain.enums.ChunkStrategy;
import com.yanglizi.docraptor.dto.request.KnowledgeBaseCreateRequest;
import com.yanglizi.docraptor.dto.request.KnowledgeBaseUpdateRequest;
import com.yanglizi.docraptor.dto.response.KnowledgeBaseVO;
import com.yanglizi.docraptor.dto.response.SimpleVOs;
import com.yanglizi.docraptor.mapper.AsyncTaskMapper;
import com.yanglizi.docraptor.mapper.DocumentMapper;
import com.yanglizi.docraptor.mapper.EvalCaseExpectedChunkMapper;
import com.yanglizi.docraptor.mapper.EvalCaseMapper;
import com.yanglizi.docraptor.mapper.KnowledgeBaseMapper;
import com.yanglizi.docraptor.mapper.SummaryNodeMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** 模块1：知识库 CRUD（契约 4.1 ~ 4.5）。 */
@Slf4j
@Service
public class KnowledgeBaseService {

    private static final int MAX_NAME_LEN = 128;
    private static final int MAX_DESC_LEN = 2000;

    private final KnowledgeBaseMapper kbMapper;
    private final DocumentMapper documentMapper;
    private final SummaryNodeMapper nodeMapper;
    private final EvalCaseMapper evalCaseMapper;
    private final EvalCaseExpectedChunkMapper expectedChunkMapper;
    private final AsyncTaskMapper asyncTaskMapper;
    private final DocRaptorProperties props;

    public KnowledgeBaseService(KnowledgeBaseMapper kbMapper, DocumentMapper documentMapper,
                                SummaryNodeMapper nodeMapper, EvalCaseMapper evalCaseMapper,
                                EvalCaseExpectedChunkMapper expectedChunkMapper,
                                AsyncTaskMapper asyncTaskMapper, DocRaptorProperties props) {
        this.kbMapper = kbMapper;
        this.documentMapper = documentMapper;
        this.nodeMapper = nodeMapper;
        this.evalCaseMapper = evalCaseMapper;
        this.expectedChunkMapper = expectedChunkMapper;
        this.asyncTaskMapper = asyncTaskMapper;
        this.props = props;
    }

    @Value("${ai.model.embedding}")
    private String embedModel;

    @Transactional
    public KnowledgeBaseVO create(KnowledgeBaseCreateRequest req) {
        String name = req.getName() == null ? "" : req.getName().strip();
        if (name.isEmpty() || name.length() > MAX_NAME_LEN) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "name 必填且长度需在 1~" + MAX_NAME_LEN + " 之间");
        }
        String description = req.getDescription();
        if (description != null && description.length() > MAX_DESC_LEN) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "description 最长 " + MAX_DESC_LEN + " 字符");
        }

        int chunkSize = req.getChunkSize() == null ? props.getChunk().getSize() : req.getChunkSize();
        int chunkOverlap = req.getChunkOverlap() == null ? props.getChunk().getOverlap() : req.getChunkOverlap();
        String strategy = req.getChunkStrategy() == null ? props.getChunk().getStrategy() : req.getChunkStrategy();

        if (chunkSize < 64 || chunkSize > 8192) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "chunkSize 需在 [64, 8192] 之间，收到 " + chunkSize);
        }
        if (chunkOverlap < 0 || chunkOverlap >= chunkSize) {
            throw BizException.of(ErrorCode.CHUNK_PARAM_INVALID);
        }
        if (!ChunkStrategy.isValid(strategy)) {
            throw BizException.of(ErrorCode.PARAM_INVALID,
                    "chunkStrategy 只支持 FIXED_SIZE/PARAGRAPH/RECURSIVE，收到 " + strategy);
        }
        if (kbMapper.countByName(name, null) > 0) {
            throw BizException.of(ErrorCode.NAME_DUPLICATED, name);
        }

        KnowledgeBase kb = new KnowledgeBase();
        kb.setId(UUID.randomUUID());
        kb.setName(name);
        kb.setDescription(description);
        kb.setChunkSize(chunkSize);
        kb.setChunkOverlap(chunkOverlap);
        kb.setChunkStrategy(strategy);
        kb.setDocumentCount(0);
        kb.setNodeCount(0);
        kb.setEmbeddingModel(embedModel);
        kb.setEmbeddingDimension(props.getEmbedding().getDimensions());
        try {
            kbMapper.insert(kb);
        } catch (DuplicateKeyException e) {
            // 唯一约束 uk_knowledge_base_name 兜底（并发）
            throw BizException.of(ErrorCode.NAME_DUPLICATED, name);
        }
        return VoConverter.toKbVO(kbMapper.selectById(kb.getId()));
    }

    public PageResult<KnowledgeBaseVO> list(String keyword, int page, int pageSize) {
        int[] pg = Paging.normalize(page, pageSize);
        List<KnowledgeBase> rows = kbMapper.selectPage(keyword, (pg[0] - 1) * pg[1], pg[1]);
        long total = kbMapper.countPage(keyword);
        List<KnowledgeBaseVO> list = new ArrayList<>(rows.size());
        for (KnowledgeBase kb : rows) {
            list.add(VoConverter.toKbVO(kb));
        }
        return PageResult.of(list, total, pg[0], pg[1]);
    }

    public KnowledgeBaseVO detail(String id) {
        return VoConverter.toKbVO(require(UUID.fromString(id)));
    }

    public KnowledgeBase require(UUID id) {
        KnowledgeBase kb = kbMapper.selectById(id);
        if (kb == null) {
            throw BizException.of(ErrorCode.KNOWLEDGE_BASE_NOT_FOUND);
        }
        return kb;
    }

    /** 契约 4.4：null 表示不改；description 传空串表示清空。分块参数传入会被忽略。 */
    @Transactional
    public KnowledgeBaseVO update(String id, KnowledgeBaseUpdateRequest req) {
        UUID kbId = TimeUtils.parseUuid(id, "id");
        KnowledgeBase kb = require(kbId);

        String newName = kb.getName();
        if (req.getName() != null) {
            String n = req.getName().strip();
            if (n.isEmpty() || n.length() > MAX_NAME_LEN) {
                throw BizException.of(ErrorCode.PARAM_INVALID, "name 长度需在 1~" + MAX_NAME_LEN + " 之间");
            }
            if (kbMapper.countByName(n, kbId) > 0) {
                throw BizException.of(ErrorCode.NAME_DUPLICATED, n);
            }
            newName = n;
        }
        String newDesc = kb.getDescription();
        if (req.getDescription() != null) {
            if (req.getDescription().length() > MAX_DESC_LEN) {
                throw BizException.of(ErrorCode.PARAM_INVALID, "description 最长 " + MAX_DESC_LEN + " 字符");
            }
            newDesc = req.getDescription().isEmpty() ? null : req.getDescription();
        }
        try {
            kbMapper.updateNameAndDescription(kbId, newName, newDesc);
        } catch (DuplicateKeyException e) {
            throw BizException.of(ErrorCode.NAME_DUPLICATED, newName);
        }
        return VoConverter.toKbVO(kbMapper.selectById(kbId));
    }

    /**
     * 契约 4.5：必须显式 confirm=true。
     *
     * <p>删除顺序是刻意显式的：{@code eval_case_expected_chunk.node_id} 为 ON DELETE RESTRICT
     * 且 RESTRICT 是立即检查，不能指望 FK CASCADE 的顺序。
     */
    @Transactional
    public SimpleVOs.KnowledgeBaseDeleteVO delete(String id, Boolean confirm) {
        if (!Boolean.TRUE.equals(confirm)) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "删除知识库必须显式传 confirm=true");
        }
        UUID kbId = TimeUtils.parseUuid(id, "id");
        require(kbId);

        long documents = documentMapper.countByKnowledgeBase(kbId);
        long nodes = nodeMapper.countByKnowledgeBase(kbId);
        long evalCases = evalCaseMapper.countByKnowledgeBase(kbId);

        expectedChunkMapper.deleteByKnowledgeBase(kbId);
        evalCaseMapper.deleteByKnowledgeBase(kbId);
        asyncTaskMapper.deleteByKnowledgeBase(kbId);
        nodeMapper.deleteByKnowledgeBase(kbId);
        documentMapper.deleteByKnowledgeBase(kbId);
        kbMapper.deleteById(kbId);

        SimpleVOs.KnowledgeBaseDeleteVO vo = new SimpleVOs.KnowledgeBaseDeleteVO();
        vo.setId(id);
        vo.setDeletedDocuments(documents);
        vo.setDeletedNodes(nodes);
        vo.setDeletedEvalCases(evalCases);
        log.info("删除知识库 {} 文档 {} 节点 {} 用例 {}", id, documents, nodes, evalCases);
        return vo;
    }

    /** 重算冗余统计（导入/删文档后调用）。 */
    @Transactional
    public void refreshStats(UUID kbId) {
        kbMapper.refreshStats(kbId);
    }
}
