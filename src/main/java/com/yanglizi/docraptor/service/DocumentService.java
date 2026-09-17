package com.yanglizi.docraptor.service;

import com.yanglizi.docraptor.async.AsyncTaskService;
import com.yanglizi.docraptor.async.DocImportTaskWorker;
import com.yanglizi.docraptor.common.BizException;
import com.yanglizi.docraptor.common.ErrorCode;
import com.yanglizi.docraptor.common.JsonUtils;
import com.yanglizi.docraptor.common.PageResult;
import com.yanglizi.docraptor.common.TimeUtils;
import com.yanglizi.docraptor.config.DocRaptorProperties;
import com.yanglizi.docraptor.domain.dto.ChunkRow;
import com.yanglizi.docraptor.domain.entity.DocumentEntity;
import com.yanglizi.docraptor.domain.entity.KnowledgeBase;
import com.yanglizi.docraptor.domain.enums.FileType;
import com.yanglizi.docraptor.domain.enums.StepStatus;
import com.yanglizi.docraptor.dto.response.ChunkVO;
import com.yanglizi.docraptor.dto.response.DocumentVO;
import com.yanglizi.docraptor.dto.response.SimpleVOs;
import com.yanglizi.docraptor.mapper.DocumentMapper;
import com.yanglizi.docraptor.mapper.SummaryNodeMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 模块1：文档上传/列表/详情/启用禁用/分块列表（契约 4.6 ~ 4.9）。 */
@Slf4j
@Service
public class DocumentService {

    private static final int PREVIEW_DIMS = 8;

    private final DocumentMapper documentMapper;
    private final SummaryNodeMapper nodeMapper;
    private final KnowledgeBaseService knowledgeBaseService;
    private final AsyncTaskService asyncTaskService;
    private final DocImportTaskWorker importWorker;
    private final DocRaptorProperties props;

    public DocumentService(DocumentMapper documentMapper, SummaryNodeMapper nodeMapper,
                           KnowledgeBaseService knowledgeBaseService, AsyncTaskService asyncTaskService,
                           DocImportTaskWorker importWorker, DocRaptorProperties props) {
        this.documentMapper = documentMapper;
        this.nodeMapper = nodeMapper;
        this.knowledgeBaseService = knowledgeBaseService;
        this.asyncTaskService = asyncTaskService;
        this.importWorker = importWorker;
        this.props = props;
    }

    /* ============================ 上传（契约 4.6） ============================ */

    public SimpleVOs.UploadVO upload(MultipartFile file, String knowledgeBaseId, Boolean buildTree, Integer maxLevel) {
        if (file == null || file.isEmpty()) {
            throw BizException.of(ErrorCode.FILE_EMPTY);
        }
        UUID kbId = TimeUtils.parseUuid(knowledgeBaseId, "knowledgeBaseId");
        KnowledgeBase kb = knowledgeBaseService.require(kbId);

        String originalName = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
        String ext = extension(originalName);
        FileType fileType = FileType.fromExtension(ext);
        if (fileType == null || !allowedExtensions().contains(ext.toLowerCase(java.util.Locale.ROOT))) {
            throw BizException.of(ErrorCode.FILE_TYPE_UNSUPPORTED, ext);
        }
        long maxBytes = (long) props.getImportConfig().getMaxFileSizeMb() * 1024 * 1024;
        if (file.getSize() > maxBytes) {
            throw BizException.of(ErrorCode.PAYLOAD_TOO_LARGE);
        }

        UUID documentId = UUID.randomUUID();
        String storedPath = Path.of(props.getImportConfig().getStorageDir(),
                documentId + "." + fileType.storedExtension()).toString();
        Path target = Path.of(storedPath).toAbsolutePath().normalize();
        String hash;
        try {
            Files.createDirectories(target.getParent());
            try (InputStream in = file.getInputStream()) {
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
            }
            hash = sha256(target);
        } catch (IOException e) {
            log.error("落盘失败 {}", target, e);
            throw BizException.of(ErrorCode.STORAGE_ERROR, e.toString());
        }

        int level = maxLevel == null ? props.getRaptor().getMaxLevel() : maxLevel;
        if (level < 1 || level > 10) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "maxLevel 需在 [1, 10] 之间");
        }
        boolean autoTree = buildTree == null ? props.getImportConfig().isAutoBuildTree() : buildTree;

        DocumentEntity doc = new DocumentEntity();
        doc.setId(documentId);
        doc.setKnowledgeBaseId(kbId);
        doc.setFileName(originalName.isBlank() ? documentId + "." + fileType.storedExtension() : originalName);
        doc.setStoredPath(storedPath);
        doc.setFileType(fileType.name());
        doc.setFileSize(file.getSize());
        doc.setContentHash(hash);
        doc.setParseStatus("PENDING");
        doc.setChunkStatus("PENDING");
        doc.setEmbedStatus("PENDING");
        doc.setTreeStatus("PENDING");
        doc.setCharCount(0);
        doc.setChunkCount(0);
        doc.setEnabled(true);
        doc.setMetadata("{}");
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("chunkSize", kb.getChunkSize());
        meta.put("chunkOverlap", kb.getChunkOverlap());
        meta.put("chunkStrategy", kb.getChunkStrategy());
        doc.setMetadata(JsonUtils.toJson(meta));
        documentMapper.insert(doc);

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("chunkSize", kb.getChunkSize());
        payload.put("chunkOverlap", kb.getChunkOverlap());
        payload.put("chunkStrategy", kb.getChunkStrategy());
        payload.put("maxLevel", level);
        payload.put("autoBuildTree", autoTree);
        payload.put("fileName", doc.getFileName());
        payload.put("storagePath", storedPath);

        var task = asyncTaskService.createTask("DOC_IMPORT", kbId, documentId, payload);
        knowledgeBaseService.refreshStats(kbId);

        // 异步边界：Controller 返回后由独立线程池执行解析/分块/向量化/建树
        importWorker.run(task.getId(), documentId, level, autoTree);

        SimpleVOs.UploadVO vo = new SimpleVOs.UploadVO();
        vo.setDocumentId(documentId.toString());
        vo.setTaskId(task.getId().toString());
        vo.setFileName(doc.getFileName());
        vo.setFileType(doc.getFileType());
        vo.setFileSize(doc.getFileSize());
        vo.setStatus(task.getStatus());
        return vo;
    }

    /* ============================ 列表 / 详情 ============================ */

    public PageResult<DocumentVO> list(String knowledgeBaseId, Integer page, Integer pageSize,
                                       Boolean enabled, String keyword, String treeStatus) {
        int[] pg = Paging.normalize(page, pageSize);
        UUID kbId = null;
        if (knowledgeBaseId != null && !knowledgeBaseId.isBlank()) {
            kbId = TimeUtils.parseUuid(knowledgeBaseId, "knowledgeBaseId");
            knowledgeBaseService.require(kbId);
        }
        if (treeStatus != null && !treeStatus.isBlank() && !StepStatus.isValid(treeStatus)) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "treeStatus 不合法：" + treeStatus);
        }
        List<DocumentEntity> rows = documentMapper.selectPage(kbId, enabled, treeStatus, keyword,
                (pg[0] - 1) * pg[1], pg[1]);
        long total = documentMapper.countPage(kbId, enabled, treeStatus, keyword);
        List<DocumentVO> list = new ArrayList<>(rows.size());
        for (DocumentEntity d : rows) {
            list.add(VoConverter.toDocumentVO(d));
        }
        return PageResult.of(list, total, pg[0], pg[1]);
    }

    public DocumentVO detail(String id) {
        return VoConverter.toDocumentVO(requireDocument(TimeUtils.parseUuid(id, "id")));
    }

    public DocumentEntity requireDocument(UUID id) {
        DocumentEntity doc = documentMapper.selectById(id);
        if (doc == null) {
            throw BizException.of(ErrorCode.DOCUMENT_NOT_FOUND);
        }
        return doc;
    }

    /** 契约 4.8：唯一可写的业务字段（禁用的唯一入口，可逆）。 */
    public SimpleVOs.DocumentEnabledVO setEnabled(String id, Boolean enabled) {
        if (enabled == null) {
            throw BizException.of(ErrorCode.PARAM_INVALID, "enabled 不能为空");
        }
        UUID docId = TimeUtils.parseUuid(id, "id");
        requireDocument(docId);
        documentMapper.updateEnabled(docId, enabled);
        DocumentEntity after = documentMapper.selectById(docId);
        SimpleVOs.DocumentEnabledVO vo = new SimpleVOs.DocumentEnabledVO();
        vo.setId(id);
        vo.setEnabled(after.getEnabled());
        vo.setUpdatedAt(TimeUtils.toMillis(after.getUpdatedAt()));
        return vo;
    }

    /* ============================ 分块列表（契约 4.9） ============================ */

    public PageResult<ChunkVO> chunks(String knowledgeBaseId, String documentId, Integer page, Integer pageSize,
                                      Boolean withContent, Boolean withEmbedding) {
        int[] pg = Paging.normalize(page, pageSize);
        UUID docId = null;
        if (documentId != null && !documentId.isBlank()) {
            docId = TimeUtils.parseUuid(documentId, "documentId");
            requireDocument(docId);
        }
        UUID kbId = null;
        if (docId == null && knowledgeBaseId != null && !knowledgeBaseId.isBlank()) {
            kbId = TimeUtils.parseUuid(knowledgeBaseId, "knowledgeBaseId");
            knowledgeBaseService.require(kbId);
        }
        boolean content = withContent == null || withContent;
        boolean embedding = withEmbedding != null && withEmbedding;

        List<ChunkRow> rows = nodeMapper.selectLeafChunks(docId, kbId, (pg[0] - 1) * pg[1], pg[1]);
        long total = nodeMapper.countLeafChunks(docId, kbId);
        List<ChunkVO> list = new ArrayList<>(rows.size());
        for (ChunkRow row : rows) {
            list.add(VoConverter.toChunkVO(row, content, embedding, PREVIEW_DIMS));
        }
        return PageResult.of(list, total, pg[0], pg[1]);
    }

    /* ============================ helpers ============================ */

    private java.util.Set<String> allowedExtensions() {
        java.util.Set<String> set = new java.util.LinkedHashSet<>();
        for (String s : props.getImportConfig().getAllowedExtensions().split(",")) {
            if (!s.isBlank()) {
                set.add(s.trim().toLowerCase(java.util.Locale.ROOT));
            }
        }
        return set;
    }

    static String extension(String fileName) {
        int i = fileName.lastIndexOf('.');
        return i < 0 ? "" : fileName.substring(i + 1);
    }

    private static String sha256(Path path) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[8192];
            try (InputStream in = Files.newInputStream(path)) {
                int n;
                while ((n = in.read(buf)) > 0) {
                    md.update(buf, 0, n);
                }
            }
            StringBuilder sb = new StringBuilder(64);
            for (byte b : md.digest()) {
                sb.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
            }
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 不可用", e);
        }
    }
}
