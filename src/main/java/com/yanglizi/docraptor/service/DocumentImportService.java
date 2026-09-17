package com.yanglizi.docraptor.service;

import com.yanglizi.docraptor.ai.AiGateway;
import com.yanglizi.docraptor.async.AsyncTaskProgressReporter;
import com.yanglizi.docraptor.chunker.Chunker;
import com.yanglizi.docraptor.chunker.ChunkerFactory;
import com.yanglizi.docraptor.chunker.TextChunk;
import com.yanglizi.docraptor.common.BizException;
import com.yanglizi.docraptor.common.ErrorCode;
import com.yanglizi.docraptor.common.JsonUtils;
import com.yanglizi.docraptor.config.DocRaptorProperties;
import com.yanglizi.docraptor.domain.entity.DocumentEntity;
import com.yanglizi.docraptor.domain.entity.SummaryNode;
import com.yanglizi.docraptor.mapper.DocumentMapper;
import com.yanglizi.docraptor.mapper.SummaryNodeMapper;
import com.yanglizi.docraptor.parser.DocumentParser;
import com.yanglizi.docraptor.parser.ParsedDocument;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 导入流水线：解析 → 分块 → 向量化（架构 4.1）。
 *
 * <p>每一步的产物都带状态落库，失败后可从「最后一个 SUCCESS 的步骤之后」续跑：
 * <ul>
 *   <li>分块幂等：该文档若已有 LEAF 节点则直接跳过（同时满足 D5「已导入正文不可改删」）；</li>
 *   <li>向量化幂等：只处理 {@code embedding IS NULL} 的叶子块，重跑不重复扣 token。</li>
 * </ul>
 */
@Slf4j
@Service
public class DocumentImportService {

    private final DocumentMapper documentMapper;
    private final SummaryNodeMapper nodeMapper;
    private final com.yanglizi.docraptor.mapper.KnowledgeBaseMapper kbMapper;
    private final DocumentParser parser;
    private final AiGateway aiGateway;
    private final DocRaptorProperties props;

    public DocumentImportService(DocumentMapper documentMapper, SummaryNodeMapper nodeMapper,
                                 com.yanglizi.docraptor.mapper.KnowledgeBaseMapper kbMapper,
                                 DocumentParser parser, AiGateway aiGateway, DocRaptorProperties props) {
        this.documentMapper = documentMapper;
        this.nodeMapper = nodeMapper;
        this.kbMapper = kbMapper;
        this.parser = parser;
        this.aiGateway = aiGateway;
        this.props = props;
    }

    /* ============================ PARSE ============================ */

    /** 解析文档，返回归一化后的纯文本，并更新 document 的 parse_status / char_count / metadata。 */
    public String parse(UUID taskId, UUID docId, AsyncTaskProgressReporter reporter) {
        DocumentEntity doc = requireDocument(docId);
        if (reporter != null) {
            reporter.progress(taskId, 2, "PARSE", "正在解析文档 " + doc.getFileName());
        }
        Path path = resolveStoredPath(doc);
        if (!Files.exists(path)) {
            documentMapper.updateParseResult(docId, "FAILED", 0, "源文件不存在：" + path);
            throw BizException.of(ErrorCode.DOCUMENT_PARSE_FAILED, "源文件不存在：" + path);
        }

        ParsedDocument parsed;
        try (InputStream in = Files.newInputStream(path)) {
            parsed = parser.parse(in, doc.getFileName());
        } catch (BizException e) {
            documentMapper.updateParseResult(docId, "FAILED", 0, truncate(e.getMessage(), 4000));
            throw e;
        } catch (IOException e) {
            documentMapper.updateParseResult(docId, "FAILED", 0, truncate(e.toString(), 4000));
            throw BizException.of(ErrorCode.DOCUMENT_PARSE_FAILED, e.toString());
        }

        String text = parsed.text() == null ? "" : parsed.text();
        if (text.isBlank()) {
            documentMapper.updateParseResult(docId, "FAILED", 0, "解析结果为空文本");
            throw BizException.of(ErrorCode.DOCUMENT_PARSE_FAILED, "解析结果为空文本");
        }

        Map<String, Object> meta = new LinkedHashMap<>(JsonUtils.toMap(doc.getMetadata()));
        meta.putAll(parsed.metadata());
        documentMapper.updateParseResult(docId, "SUCCESS", text.length(), null);
        documentMapper.updateMetadata(docId, JsonUtils.toJson(meta));

        if (reporter != null) {
            reporter.progress(taskId, 15, "PARSE", "解析完成，共 " + text.length() + " 字符");
        }
        log.info("文档 {} 解析完成：{} 字符", docId, text.length());
        return text;
    }

    /* ============================ CHUNK ============================ */

    /** 按知识库固化的分块参数切块并写入 LEAF 节点。返回叶子块数量。 */
    public int chunk(UUID taskId, UUID docId, String text, AsyncTaskProgressReporter reporter) {
        DocumentEntity doc = requireDocument(docId);
        long existing = nodeMapper.countByDocumentAndType(docId, "LEAF");
        if (existing > 0) {
            log.info("文档 {} 已有 {} 个叶子块，跳过重复分块（D5：正文不可重建）", docId, existing);
            if (reporter != null) {
                reporter.progress(taskId, 35, "CHUNK", "已存在 " + existing + " 个文本块，跳过");
            }
            return (int) existing;
        }

        var kb = kbServiceView(doc);
        if (reporter != null) {
            reporter.progress(taskId, 17, "CHUNK", "按 " + kb.strategy + " 策略分块");
        }

        Chunker chunker = ChunkerFactory.get(kb.strategy);
        List<TextChunk> chunks = chunker.split(text, kb.size, kb.overlap, props.getChunk().getMinChunkChars());
        if (chunks.isEmpty()) {
            documentMapper.updateChunkResult(docId, "FAILED", 0);
            throw BizException.of(ErrorCode.CHUNK_PARAM_INVALID, "分块结果为空");
        }

        List<SummaryNode> nodes = new ArrayList<>(chunks.size());
        for (TextChunk c : chunks) {
            String content = c.text() == null ? "" : c.text().strip();
            if (content.isEmpty()) {
                continue;
            }
            SummaryNode node = new SummaryNode();
            node.setId(UUID.randomUUID());
            node.setKnowledgeBaseId(doc.getKnowledgeBaseId());
            node.setDocumentId(docId);
            node.setParentId(null);
            node.setNodeType("LEAF");
            node.setLevel((short) 0);
            node.setChunkIndex(c.index());
            node.setStartChunkIndex(c.index());
            node.setEndChunkIndex(c.index());
            node.setContent(content);
            node.setCharCount(content.length());
            node.setTokenCount(content.length() / 4);
            node.setMetadata(JsonUtils.toJson(Map.of("strategy", kb.strategy, "chunkSize", kb.size,
                    "chunkOverlap", kb.overlap)));
            nodes.add(node);
        }
        // 重新编号，保证 chunk_index 从 0 开始连续（空块被过滤后可能不连续）
        for (int i = 0; i < nodes.size(); i++) {
            nodes.get(i).setChunkIndex(i);
            nodes.get(i).setStartChunkIndex(i);
            nodes.get(i).setEndChunkIndex(i);
        }
        if (nodes.isEmpty()) {
            documentMapper.updateChunkResult(docId, "FAILED", 0);
            throw BizException.of(ErrorCode.CHUNK_PARAM_INVALID, "所有文本块都是空白，无有效内容可入库");
        }
        nodeMapper.insertLeaves(nodes);
        documentMapper.updateChunkResult(docId, "SUCCESS", nodes.size());
        if (reporter != null) {
            reporter.progress(taskId, 35, "CHUNK", "已生成 " + nodes.size() + " 个文本块");
        }
        log.info("文档 {} 分块完成：{} 块（strategy={}）", docId, nodes.size(), kb.strategy);
        return nodes.size();
    }

    /* ============================ EMBED ============================ */

    /** 批量向量化尚未处理的叶子块，写入 embedding。返回本次向量化的块数。 */
    public int embed(UUID taskId, UUID docId, AsyncTaskProgressReporter reporter) {
        DocumentEntity doc = requireDocument(docId);
        List<SummaryNode> pending = nodeMapper.selectLeavesWithoutEmbedding(docId);
        long total = nodeMapper.countByDocumentAndType(docId, "LEAF");
        if (total == 0) {
            documentMapper.updateEmbedStatus(docId, "FAILED");
            throw BizException.of(ErrorCode.DOCUMENT_NOT_READY, "该文档没有文本块可向量化");
        }
        if (pending.isEmpty()) {
            documentMapper.updateEmbedStatus(docId, "SUCCESS");
            if (reporter != null) {
                reporter.progress(taskId, 70, "EMBED", "全部 " + total + " 块已有向量，跳过");
            }
            return 0;
        }

        int batchSize = Math.max(1, props.getEmbedding().getBatchSize());
        int done = 0;
        for (int start = 0; start < pending.size(); start += batchSize) {
            List<SummaryNode> batch = pending.subList(start, Math.min(start + batchSize, pending.size()));
            List<String> texts = new ArrayList<>(batch.size());
            for (SummaryNode n : batch) {
                texts.add(n.getContent());
            }
            List<float[]> vectors = aiGateway.embedBatch(texts);
            for (int i = 0; i < batch.size(); i++) {
                nodeMapper.updateEmbedding(batch.get(i).getId(),
                        com.yanglizi.docraptor.algorithm.VectorUtils.toLiteral(vectors.get(i)));
            }
            done += batch.size();
            if (reporter != null) {
                int pct = (int) Math.round(done * 100.0 / pending.size());
                reporter.progress(taskId, 35 + (int) Math.round(35 * pct / 100.0), "EMBED",
                        "已向量化 " + done + "/" + pending.size() + " 块");
            }
        }
        documentMapper.updateEmbedStatus(docId, "SUCCESS");
        log.info("文档 {} 向量化完成：{} 块", docId, done);
        return done;
    }

    /* ============================ helpers ============================ */

    public DocumentEntity requireDocument(UUID docId) {
        DocumentEntity doc = documentMapper.selectById(docId);
        if (doc == null) {
            throw BizException.of(ErrorCode.DOCUMENT_NOT_FOUND);
        }
        return doc;
    }

    public Path resolveStoredPath(DocumentEntity doc) {
        String stored = doc.getStoredPath();
        if (stored == null || stored.isBlank()) {
            stored = Path.of(props.getImportConfig().getStorageDir(), doc.getId() + "." + extensionOf(doc)).toString();
        }
        return Path.of(stored).toAbsolutePath().normalize();
    }

    private static String extensionOf(DocumentEntity doc) {
        switch (doc.getFileType() == null ? "TXT" : doc.getFileType()) {
            case "PDF":
                return "pdf";
            case "DOCX":
                return "docx";
            case "MARKDOWN":
                return "md";
            default:
                return "txt";
        }
    }

    /** 分块参数的只读视图（来自知识库，建库时已固化）。 */
    private KbChunkView kbServiceView(DocumentEntity doc) {
        var kb = kbMapper.selectById(doc.getKnowledgeBaseId());
        if (kb == null) {
            throw BizException.of(ErrorCode.KNOWLEDGE_BASE_NOT_FOUND);
        }
        KbChunkView v = new KbChunkView();
        v.size = kb.getChunkSize();
        v.overlap = kb.getChunkOverlap();
        v.strategy = kb.getChunkStrategy();
        return v;
    }

    private static class KbChunkView {
        int size;
        int overlap;
        String strategy;
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }
}
