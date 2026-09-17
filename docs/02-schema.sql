-- =====================================================================================
-- DocRaptor 数据库 DDL  v1.0（architect 产出，冻结版本）
-- 目标库：jdbc:postgresql://192.168.233.130:5432/doc_raptor_db  (PostgreSQL 18.6 / ParadeDB)
-- 用户：doc_raptor      扩展：vector 0.8.6 (pgvector) 、pg_search 0.25.6 (ParadeDB)
-- 编码：UTF-8。执行方式：psql -f 02-schema.sql  或 JDBC 逐语句执行（本文件已实测通过）
--
-- 【设计要点】
--  1. summary_nodes 是「统一节点表」：叶子文本块(node_type='LEAF', level=0) 与摘要节点
--     (node_type='SUMMARY', level>=1) 同表存储，parent_id 自引用成树。
--     => 因此本 DDL 不再单独建 chunk 表；文本块 = summary_nodes 中 node_type='LEAF' 的行。
--  2. summary_nodes.embedding 为 vector(1536)，HNSW + cosine 索引（idx_summary_nodes_embedding_hnsw）。
--  3. summary_nodes 上有且仅有一个 pg_search bm25 索引（idx_summary_nodes_bm25, key_field='id'）：
--     实测「一张表只能有一个 ParadeDB bm25 索引」，第二个会报
--     "a relation may only have one ParadeDB index"，因此 content 与 summary 的检索必须共用同一索引。
--  4. 所有主键用 UUID（DEFAULT gen_random_uuid()），MyBatis 下 INSERT 后无需 useGeneratedKeys。
--  5. 所有时间戳用 timestamptz；updated_at 由触发器自动维护（见文件末尾）。
--  6. 全库不使用 Redis（本机未启动），异步任务进度落库在 async_task 表。
--
-- 【幂等性】本脚本先 DROP 后 CREATE，可重复执行；对空库或已有旧版表结构均可直接跑。
-- =====================================================================================

-- ---------------------------------------------------------------------------
-- 0. 扩展（已安装，IF NOT EXISTS 保证幂等；勿重复 CREATE 出错的旧写法）
-- ---------------------------------------------------------------------------
CREATE EXTENSION IF NOT EXISTS vector;      -- pgvector 0.8.6
CREATE EXTENSION IF NOT EXISTS pg_search;   -- ParadeDB 0.25.6

-- ---------------------------------------------------------------------------
-- 1. 清理（依赖倒序）
-- ---------------------------------------------------------------------------
DROP TABLE IF EXISTS eval_case_expected_chunk CASCADE;
DROP TABLE IF EXISTS eval_case CASCADE;
DROP TABLE IF EXISTS async_task CASCADE;
DROP TABLE IF EXISTS retrieval_log CASCADE;
DROP TABLE IF EXISTS summary_nodes CASCADE;
DROP TABLE IF EXISTS document CASCADE;
DROP TABLE IF EXISTS knowledge_base CASCADE;
DROP FUNCTION IF EXISTS fn_set_updated_at() CASCADE;

-- ---------------------------------------------------------------------------
-- 2. knowledge_base 知识库
-- ---------------------------------------------------------------------------
CREATE TABLE knowledge_base (
    id                  UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    name                VARCHAR(128) NOT NULL,
    description         TEXT,
    -- 分块配置（建库时确定，之后不再改变，保证已有文档的块序列稳定）
    chunk_size          INTEGER      NOT NULL DEFAULT 512,
    chunk_overlap       INTEGER      NOT NULL DEFAULT 64,
    chunk_strategy      VARCHAR(32)  NOT NULL DEFAULT 'FIXED_SIZE',
    -- 统计冗余字段，由服务层在导入/删文档后维护
    document_count      INTEGER      NOT NULL DEFAULT 0,
    node_count          INTEGER      NOT NULL DEFAULT 0,
    embedding_model     VARCHAR(64)  NOT NULL DEFAULT 'qwen3.7-text-embedding',
    embedding_dimension INTEGER      NOT NULL DEFAULT 1536,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uk_knowledge_base_name UNIQUE (name),
    CONSTRAINT ck_knowledge_base_chunk_size    CHECK (chunk_size BETWEEN 64 AND 8192),
    CONSTRAINT ck_knowledge_base_chunk_overlap CHECK (chunk_overlap >= 0 AND chunk_overlap < chunk_size),
    CONSTRAINT ck_knowledge_base_strategy      CHECK (chunk_strategy IN ('FIXED_SIZE', 'PARAGRAPH', 'RECURSIVE')),
    CONSTRAINT ck_knowledge_base_dimension     CHECK (embedding_dimension = 1536)
);

COMMENT ON TABLE  knowledge_base IS '知识库表：一个知识库是一组文档与其 RAPTOR 摘要树的容器，分块参数在建库时固化';
COMMENT ON COLUMN knowledge_base.id                  IS '知识库主键 UUID';
COMMENT ON COLUMN knowledge_base.name                IS '知识库名称，全局唯一，重命名时更新';
COMMENT ON COLUMN knowledge_base.description         IS '知识库描述，可为空';
COMMENT ON COLUMN knowledge_base.chunk_size          IS '分块目标字符数：FIXED_SIZE 按该长度切片，PARAGRAPH/RECURSIVE 作为软上限。默认 512';
COMMENT ON COLUMN knowledge_base.chunk_overlap       IS '相邻块重叠字符数，必须小于 chunk_size。默认 64';
COMMENT ON COLUMN knowledge_base.chunk_strategy      IS '分块策略：FIXED_SIZE(定长滑窗) / PARAGRAPH(按空行段落聚合) / RECURSIVE(递归按 段落>句号>换行 切分)。默认 FIXED_SIZE';
COMMENT ON COLUMN knowledge_base.document_count      IS '该知识库下未删除的文档数量（冗余统计，服务层维护）';
COMMENT ON COLUMN knowledge_base.node_count          IS '该知识库下 summary_nodes 总行数，含叶子块与摘要节点（冗余统计）';
COMMENT ON COLUMN knowledge_base.embedding_model     IS '向量模型名，固定 qwen3.7-text-embedding';
COMMENT ON COLUMN knowledge_base.embedding_dimension IS '向量维度，固定 1536，必须与 summary_nodes.embedding 的 vector(1536) 一致';
COMMENT ON COLUMN knowledge_base.created_at          IS '创建时间';
COMMENT ON COLUMN knowledge_base.updated_at          IS '最后更新时间，由触发器自动维护';

CREATE INDEX idx_knowledge_base_created_at ON knowledge_base (created_at DESC);

-- ---------------------------------------------------------------------------
-- 3. document 文档
-- ---------------------------------------------------------------------------
CREATE TABLE document (
    id                UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    knowledge_base_id UUID         NOT NULL,
    file_name         VARCHAR(512) NOT NULL,
    stored_path       VARCHAR(1024),
    file_type         VARCHAR(16)  NOT NULL,
    file_size         BIGINT       NOT NULL DEFAULT 0,
    content_hash      VARCHAR(64),
    -- 流水线状态（每步单独记录，便于失败后从断点续跑）
    parse_status      VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    chunk_status      VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    embed_status      VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    tree_status       VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    char_count        INTEGER      NOT NULL DEFAULT 0,
    chunk_count       INTEGER      NOT NULL DEFAULT 0,
    enabled           BOOLEAN      NOT NULL DEFAULT TRUE,
    parse_error       TEXT,
    metadata          JSONB        NOT NULL DEFAULT '{}'::jsonb,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT fk_document_knowledge_base FOREIGN KEY (knowledge_base_id)
        REFERENCES knowledge_base (id) ON DELETE CASCADE,
    CONSTRAINT ck_document_file_type   CHECK (file_type IN ('PDF', 'DOCX', 'MARKDOWN', 'TXT')),
    CONSTRAINT ck_document_parse_status CHECK (parse_status IN ('PENDING', 'RUNNING', 'SUCCESS', 'FAILED', 'SKIPPED')),
    CONSTRAINT ck_document_chunk_status CHECK (chunk_status IN ('PENDING', 'RUNNING', 'SUCCESS', 'FAILED', 'SKIPPED')),
    CONSTRAINT ck_document_embed_status CHECK (embed_status IN ('PENDING', 'RUNNING', 'SUCCESS', 'FAILED', 'SKIPPED')),
    CONSTRAINT ck_document_tree_status  CHECK (tree_status  IN ('PENDING', 'RUNNING', 'SUCCESS', 'FAILED', 'SKIPPED'))
);

COMMENT ON TABLE  document IS '文档表：记录一次上传导入的原始文件及其流水线状态。正文/切块一经导入不可修改删除，只能 enabled=false 禁用';
COMMENT ON COLUMN document.id                IS '文档主键 UUID';
COMMENT ON COLUMN document.knowledge_base_id IS '所属知识库 ID，级联删除';
COMMENT ON COLUMN document.file_name         IS '上传时的原始文件名（含扩展名）';
COMMENT ON COLUMN document.stored_path       IS '落盘后的相对路径，如 data/upload/{documentId}.pdf';
COMMENT ON COLUMN document.file_type         IS '文件类型：PDF / DOCX / MARKDOWN / TXT（按扩展名映射，实际解析统一走 Tika 自动探测）';
COMMENT ON COLUMN document.file_size         IS '文件字节数';
COMMENT ON COLUMN document.content_hash      IS '文件内容 SHA-256，用于重复导入提示（不做强制拦截）';
COMMENT ON COLUMN document.parse_status      IS '解析状态：PENDING/RUNNING/SUCCESS/FAILED/SKIPPED，由 Tika 抽取纯文本这一步写入';
COMMENT ON COLUMN document.chunk_status      IS '分块状态：把纯文本切成叶子块的这一步写入';
COMMENT ON COLUMN document.embed_status      IS '向量化状态：叶子块 embedding 写入这一步写入';
COMMENT ON COLUMN document.tree_status       IS 'RAPTOR 建树状态：聚类+摘要+递归这一步写入';
COMMENT ON COLUMN document.char_count        IS '解析出的纯文本字符数';
COMMENT ON COLUMN document.chunk_count       IS '切分出的叶子块数量（node_type=LEAF 的行数）';
COMMENT ON COLUMN document.enabled           IS '是否启用：false 表示已禁用，该文档全部节点不参与检索，但数据完整保留。导入后只允许改这个字段';
COMMENT ON COLUMN document.parse_error       IS '解析或流水线失败时的异常摘要（截断到 4000 字符），成功时为 NULL';
COMMENT ON COLUMN document.metadata          IS '文档元信息 JSON，如 {title, author, pageCount, tikaContentType}';
COMMENT ON COLUMN document.created_at        IS '导入时间';
COMMENT ON COLUMN document.updated_at        IS '最后更新时间，由触发器自动维护';

CREATE INDEX idx_document_kb_created   ON document (knowledge_base_id, created_at DESC);
CREATE INDEX idx_document_kb_enabled   ON document (knowledge_base_id, enabled);
CREATE INDEX idx_document_status       ON document (tree_status, embed_status);

-- ---------------------------------------------------------------------------
-- 4. summary_nodes 统一节点表（叶子块 + 摘要节点）
--    —— 即「分块表」：node_type='LEAF' 的行就是文本块，故不再另建 chunk 表。
-- ---------------------------------------------------------------------------
CREATE TABLE summary_nodes (
    id                UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    knowledge_base_id UUID         NOT NULL,
    document_id       UUID,
    parent_id         UUID,
    node_type         VARCHAR(16)  NOT NULL,
    level             SMALLINT     NOT NULL DEFAULT 0,
    chunk_index       INTEGER,
    start_chunk_index INTEGER,
    end_chunk_index   INTEGER,
    content           TEXT         NOT NULL,
    summary           TEXT,
    char_count        INTEGER      NOT NULL DEFAULT 0,
    token_count       INTEGER,
    -- 向量列：维度必须为 1536，与 embedding 模型 dimensions=1536 一致
    embedding         vector(1536),
    summary_embedding vector(1536),
    cluster_label     INTEGER,
    cluster_size      INTEGER,
    metadata          JSONB        NOT NULL DEFAULT '{}'::jsonb,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT fk_summary_nodes_kb   FOREIGN KEY (knowledge_base_id) REFERENCES knowledge_base (id) ON DELETE CASCADE,
    CONSTRAINT fk_summary_nodes_doc  FOREIGN KEY (document_id)       REFERENCES document (id)       ON DELETE CASCADE,
    CONSTRAINT fk_summary_nodes_self FOREIGN KEY (parent_id)         REFERENCES summary_nodes (id)  ON DELETE CASCADE,
    CONSTRAINT ck_summary_nodes_type         CHECK (node_type IN ('LEAF', 'SUMMARY')),
    CONSTRAINT ck_summary_nodes_level        CHECK (level BETWEEN 0 AND 10),
    CONSTRAINT ck_summary_nodes_leaf_shape   CHECK (
        (node_type = 'LEAF'    AND level = 0  AND document_id IS NOT NULL AND chunk_index IS NOT NULL)
     OR (node_type = 'SUMMARY' AND level >= 1 AND (parent_id IS NOT NULL OR level >= 1))
    ),
    CONSTRAINT ck_summary_nodes_chunk_index  CHECK (
        (node_type = 'LEAF'    AND chunk_index IS NOT NULL AND chunk_index >= 0)
     OR (node_type = 'SUMMARY' AND chunk_index IS NULL)
    ),
    CONSTRAINT ck_summary_nodes_content      CHECK (length(content) > 0)
);

COMMENT ON TABLE  summary_nodes IS '统一节点表：RAPTOR 树的全部节点。node_type=LEAF 为文档文本块（level=0，parent_id 为 NULL），node_type=SUMMARY 为 LLM 摘要节点（level>=1）。本表同时承载向量检索与 BM25 检索';
COMMENT ON COLUMN summary_nodes.id                IS '节点主键 UUID，同时是 pg_search bm25 索引的 key_field';
COMMENT ON COLUMN summary_nodes.knowledge_base_id IS '所属知识库 ID，检索时按此过滤，级联删除';
COMMENT ON COLUMN summary_nodes.document_id       IS '来源文档 ID：叶子块必填；摘要节点可为空（跨文档摘要时为空，同文档内摘要时也写入以便按文档统计）';
COMMENT ON COLUMN summary_nodes.parent_id         IS '父节点 ID，自引用。叶子块允许为 NULL（未建树/建树前），建树后指向其所属 level=1 摘要节点；摘要节点为上层摘要节点 ID；整棵树唯一的根节点(level=max_level) parent_id 为 NULL';
COMMENT ON COLUMN summary_nodes.node_type         IS '节点类型：LEAF(文本块) / SUMMARY(LLM 摘要节点)';
COMMENT ON COLUMN summary_nodes.level             IS '层级：叶子固定 0，其父摘要为 1，依次递增；默认树深上限 L3(level<=3)';
COMMENT ON COLUMN summary_nodes.chunk_index       IS '块序号：仅叶子块有值，同一文档内从 0 开始连续递增，前端展示「块 #序号」';
COMMENT ON COLUMN summary_nodes.start_chunk_index IS '该节点覆盖的文本块起始序号（含），叶子块 = 自身 chunk_index，摘要节点 = 其子树内叶子 chunk_index 最小值';
COMMENT ON COLUMN summary_nodes.end_chunk_index   IS '该节点覆盖的文本块结束序号（含），叶子块 = 自身 chunk_index，摘要节点 = 其子树内叶子 chunk_index 最大值';
COMMENT ON COLUMN summary_nodes.content           IS '正文：叶子块为原始文本片段；摘要节点为「被检索用」的摘要正文（与 summary 保持一致，便于同一套 SQL 检索全部节点）';
COMMENT ON COLUMN summary_nodes.summary           IS 'LLM 生成的摘要原文：叶子块为 NULL，摘要节点非空';
COMMENT ON COLUMN summary_nodes.char_count        IS 'content 的字符数：前端「字符数」列直接取该字段，避免每行算 length()';
COMMENT ON COLUMN summary_nodes.token_count       IS 'content 的 token 数估算值（按字符数/4 粗估或由 LLM usage 回填），可为空';
COMMENT ON COLUMN summary_nodes.embedding         IS '向量：1536 维（qwen3.7-text-embedding, dimensions=1536）。HNSW cosine 索引建在本列上，检索用 <=> 算余弦距离';
COMMENT ON COLUMN summary_nodes.summary_embedding IS '预留列：摘要节点另存一份按不同前缀编码的向量，MVP 不使用，可全部为 NULL';
COMMENT ON COLUMN summary_nodes.cluster_label     IS '该节点在所属聚簇中的簇编号（GMM 输出的 component 下标），仅记录一次建树过程，便于排查与可视化；未聚类为 NULL';
COMMENT ON COLUMN summary_nodes.cluster_size      IS '该节点所属簇的节点数量，便于排查聚类是否退化；未聚类为 NULL';
COMMENT ON COLUMN summary_nodes.metadata          IS '扩展 JSON，如 {umap:{nNeighbors,minDist}, gmm:{nComponents,covarianceType}, buildId, sourceNodeIds:[]}';
COMMENT ON COLUMN summary_nodes.created_at        IS '节点创建时间';
COMMENT ON COLUMN summary_nodes.updated_at        IS '最后更新时间，由触发器自动维护';

-- 形态约束说明：LEAF 必须 level=0、有 document_id 与 chunk_index（parent_id 可空，建树后指向 level=1 摘要节点）；
-- SUMMARY 必须 level>=1，其 parent_id 可空（整棵树的唯一根节点 parent_id 为 NULL）。
COMMENT ON CONSTRAINT ck_summary_nodes_leaf_shape ON summary_nodes IS '形态约束：LEAF 必须 level=0 且有 document_id/chunk_index（parent_id 可空，建树后指向 level=1 摘要）；SUMMARY 必须 level>=1（根节点允许 parent_id 为 NULL）';

CREATE INDEX idx_summary_nodes_parent   ON summary_nodes (parent_id);
CREATE INDEX idx_summary_nodes_kb_level ON summary_nodes (knowledge_base_id, level);
CREATE INDEX idx_summary_nodes_doc      ON summary_nodes (document_id, chunk_index);
CREATE INDEX idx_summary_nodes_type     ON summary_nodes (knowledge_base_id, node_type);
CREATE INDEX idx_summary_nodes_metadata ON summary_nodes USING gin (metadata);

-- 4.1 向量索引：HNSW + cosine（照抄 docs/00-environment-facts.md 2.1 实测写法）
CREATE INDEX idx_summary_nodes_embedding_hnsw
    ON summary_nodes USING hnsw (embedding vector_cosine_ops)
    WITH (m = 16, ef_construction = 64);

-- 4.2 全文索引：pg_search bm25，key_field=id（照抄简报 2.2 实测写法；中文用 jieba 分词器）
--     注意：一张表只能有一个 bm25 索引，叶子块与摘要节点共用本索引。
CREATE INDEX idx_summary_nodes_bm25
    ON summary_nodes USING bm25 (id, content)
    WITH (key_field = 'id', text_fields = '{"content": {"tokenizer": {"type": "jieba"}}}');

-- 4.3 并发保护：同一文档同时只允许一个 RUNNING/PENDING 的异步任务（见 async_task 表）
--     （建在 async_task 表上，此处仅注释说明）

-- ---------------------------------------------------------------------------
-- 5. retrieval_log 检索日志
-- ---------------------------------------------------------------------------
CREATE TABLE retrieval_log (
    id                   UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    knowledge_base_id    UUID,
    log_type             VARCHAR(16)  NOT NULL DEFAULT 'SEARCH',
    query_text           TEXT         NOT NULL,
    mode                 VARCHAR(16)  NOT NULL,
    top_k                INTEGER      NOT NULL DEFAULT 10,
    similarity_threshold DOUBLE PRECISION NOT NULL DEFAULT 0.0,
    hybrid_ratio         DOUBLE PRECISION NOT NULL DEFAULT 0.5,
    bm25_weight          DOUBLE PRECISION NOT NULL DEFAULT 1.0,
    rrf_k                INTEGER      NOT NULL DEFAULT 60,
    scope                VARCHAR(24)  NOT NULL DEFAULT 'ALL_LEVELS',
    scope_levels         VARCHAR(64),
    document_ids         JSONB,
    result_count         INTEGER      NOT NULL DEFAULT 0,
    result_node_ids      JSONB,
    latency_ms           INTEGER      NOT NULL DEFAULT 0,
    vector_latency_ms    INTEGER,
    bm25_latency_ms      INTEGER,
    fusion_latency_ms    INTEGER,
    success              BOOLEAN      NOT NULL DEFAULT TRUE,
    error_message        TEXT,
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT fk_retrieval_log_kb FOREIGN KEY (knowledge_base_id)
        REFERENCES knowledge_base (id) ON DELETE SET NULL,
    CONSTRAINT ck_retrieval_log_type  CHECK (log_type IN ('SEARCH', 'EVAL')),
    CONSTRAINT ck_retrieval_log_mode  CHECK (mode IN ('VECTOR', 'BM25', 'HYBRID', 'EVAL')),
    CONSTRAINT ck_retrieval_log_scope CHECK (scope IN ('LEAF_ONLY', 'ALL_LEVELS', 'SPECIFIED_LEVEL'))
);

COMMENT ON TABLE  retrieval_log IS '检索日志表：每次检索（含召回测试执行）落一条，记录查询、模式、全部参数、耗时与命中节点，供后续分析';
COMMENT ON COLUMN retrieval_log.id                   IS '日志主键 UUID';
COMMENT ON COLUMN retrieval_log.knowledge_base_id    IS '知识库 ID，知识库被删后置空以保留历史日志（ON DELETE SET NULL）';
COMMENT ON COLUMN retrieval_log.log_type             IS '日志类型：SEARCH(普通检索) / EVAL(召回测试与评估执行)';
COMMENT ON COLUMN retrieval_log.query_text           IS '用户查询原文';
COMMENT ON COLUMN retrieval_log.mode                 IS '检索模式：VECTOR(纯向量) / BM25(纯全文) / HYBRID(RRF 混合) / EVAL(评估批量执行)';
COMMENT ON COLUMN retrieval_log.top_k                IS '请求的 topK';
COMMENT ON COLUMN retrieval_log.similarity_threshold IS '相似度阈值，向量路结果低于该值被丢弃，范围 [0,1]';
COMMENT ON COLUMN retrieval_log.hybrid_ratio         IS 'HYBRID 模式下向量路权重，BM25 路权重 = 1 - 该值';
COMMENT ON COLUMN retrieval_log.bm25_weight          IS 'BM25 路整体缩放系数，默认 1.0，用于「BM25 权重可调」的二次微调';
COMMENT ON COLUMN retrieval_log.rrf_k                IS 'RRF 平滑常数 k，默认 60，越大则头部排名优势越被削弱';
COMMENT ON COLUMN retrieval_log.scope                IS '检索范围：LEAF_ONLY(仅叶子) / ALL_LEVELS(全层级折叠树) / SPECIFIED_LEVEL(指定层级)';
COMMENT ON COLUMN retrieval_log.scope_levels         IS 'scope=SPECIFIED_LEVEL 时的层级列表，逗号分隔，如 "0,1,2"';
COMMENT ON COLUMN retrieval_log.document_ids         IS '限定文档范围时的文档 ID 数组 JSON，如 ["uuid1","uuid2"]，为 NULL 表示不限';
COMMENT ON COLUMN retrieval_log.result_count         IS '返回结果条数';
COMMENT ON COLUMN retrieval_log.result_node_ids      IS '返回结果的节点 ID 数组 JSON，按最终排名顺序';
COMMENT ON COLUMN retrieval_log.latency_ms           IS '总耗时（毫秒）';
COMMENT ON COLUMN retrieval_log.vector_latency_ms    IS '向量路单独耗时（毫秒）';
COMMENT ON COLUMN retrieval_log.bm25_latency_ms      IS 'BM25 路单独耗时（毫秒）';
COMMENT ON COLUMN retrieval_log.fusion_latency_ms    IS 'RRF 融合与折叠耗时（毫秒）';
COMMENT ON COLUMN retrieval_log.success              IS '本次检索是否成功';
COMMENT ON COLUMN retrieval_log.error_message        IS '失败原因（如向量维度不符、SQL 异常）';
COMMENT ON COLUMN retrieval_log.created_at           IS '检索发生时间';

CREATE INDEX idx_retrieval_log_kb_time ON retrieval_log (knowledge_base_id, created_at DESC);
CREATE INDEX idx_retrieval_log_mode    ON retrieval_log (mode, created_at DESC);

-- ---------------------------------------------------------------------------
-- 6. async_task 异步任务（进度落库，不用 Redis）
-- ---------------------------------------------------------------------------
CREATE TABLE async_task (
    id                UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    task_type         VARCHAR(32)  NOT NULL,
    knowledge_base_id UUID,
    document_id       UUID,
    status            VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    progress          SMALLINT     NOT NULL DEFAULT 0,
    current_stage     VARCHAR(32),
    progress_message  VARCHAR(512),
    payload           JSONB        NOT NULL DEFAULT '{}'::jsonb,
    result            JSONB,
    error_message     TEXT,
    retry_count       INTEGER      NOT NULL DEFAULT 0,
    started_at        TIMESTAMPTZ,
    finished_at       TIMESTAMPTZ,
    heartbeat_at      TIMESTAMPTZ,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT fk_async_task_kb  FOREIGN KEY (knowledge_base_id) REFERENCES knowledge_base (id) ON DELETE CASCADE,
    CONSTRAINT fk_async_task_doc FOREIGN KEY (document_id)       REFERENCES document (id)       ON DELETE CASCADE,
    CONSTRAINT ck_async_task_type   CHECK (task_type IN (
        'DOC_IMPORT', 'DOC_PARSE', 'DOC_CHUNK', 'DOC_EMBED', 'RAPTOR_BUILD', 'EVAL_RUN')),
    CONSTRAINT ck_async_task_status CHECK (status IN (
        'PENDING', 'RUNNING', 'SUCCESS', 'PARTIAL_SUCCESS', 'FAILED', 'CANCELED')),
    CONSTRAINT ck_async_task_progress CHECK (progress BETWEEN 0 AND 100)
);

COMMENT ON TABLE  async_task IS '异步任务表：导入/分块/向量化/建树/评估等长任务的状态与进度落库，前端轮询本表查进度（不用 Redis/MQ）';
COMMENT ON COLUMN async_task.id                IS '任务主键 UUID，接口返回的 taskId';
COMMENT ON COLUMN async_task.task_type         IS '任务类型：DOC_IMPORT(整条导入流水线) / DOC_PARSE / DOC_CHUNK / DOC_EMBED / RAPTOR_BUILD / EVAL_RUN';
COMMENT ON COLUMN async_task.knowledge_base_id IS '关联知识库 ID，级联删除';
COMMENT ON COLUMN async_task.document_id       IS '关联文档 ID：导入/分块/向量化/建树类任务必填；纯评估任务可为空';
COMMENT ON COLUMN async_task.status            IS '状态：PENDING(排队) / RUNNING(执行中) / SUCCESS / PARTIAL_SUCCESS(部分文档成功) / FAILED / CANCELED';
COMMENT ON COLUMN async_task.progress          IS '进度百分比 0~100，由执行线程分阶段写回（每阶段一个固定权重，保证单调递增）';
COMMENT ON COLUMN async_task.current_stage     IS '当前阶段：PARSE / CHUNK / EMBED / TREE_BUILD / DONE，便于前端展示与断点续跑判断';
COMMENT ON COLUMN async_task.progress_message  IS '阶段内的人类可读进度，如 "已向量化 128/512 块"';
COMMENT ON COLUMN async_task.payload           IS '任务入参快照 JSON，如 {chunkSize, chunkOverlap, chunkStrategy, maxLevel, requestPath}';
COMMENT ON COLUMN async_task.result            IS '任务结果 JSON，如 {documentId, chunkCount, nodeCount, rootNodeId, maxLevel, durationMs}';
COMMENT ON COLUMN async_task.error_message     IS '失败原因（异常类名+message，截断 4000 字符）';
COMMENT ON COLUMN async_task.retry_count       IS '重试次数，LLM/embedding 限流重试时累加';
COMMENT ON COLUMN async_task.started_at        IS '实际开始执行时间';
COMMENT ON COLUMN async_task.finished_at       IS '任务终态时间';
COMMENT ON COLUMN async_task.heartbeat_at      IS '心跳时间：执行线程每阶段刷新，超过 5 分钟未刷新视为僵死任务，可被标为 FAILED';
COMMENT ON COLUMN async_task.created_at        IS '任务创建时间（入队时间）';
COMMENT ON COLUMN async_task.updated_at        IS '最后更新时间，由触发器自动维护';

CREATE INDEX idx_async_task_doc     ON async_task (document_id, created_at DESC);
CREATE INDEX idx_async_task_status  ON async_task (status, created_at);
CREATE INDEX idx_async_task_kb      ON async_task (knowledge_base_id, created_at DESC);
-- 并发保护：同一文档同时只能有一个未完成的任务
CREATE UNIQUE INDEX uk_async_task_active_doc
    ON async_task (document_id, task_type)
    WHERE status IN ('PENDING', 'RUNNING') AND document_id IS NOT NULL;

-- ---------------------------------------------------------------------------
-- 7. eval_case 召回评估用例（测试查询 + 期望命中的块 ID）
-- ---------------------------------------------------------------------------
CREATE TABLE eval_case (
    id                   UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    knowledge_base_id    UUID         NOT NULL,
    name                 VARCHAR(128) NOT NULL,
    query_text           TEXT         NOT NULL,
    remark               TEXT,
    enabled              BOOLEAN      NOT NULL DEFAULT TRUE,
    last_recall_at_k     DOUBLE PRECISION,
    last_mrr             DOUBLE PRECISION,
    last_evaluated_at    TIMESTAMPTZ,
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT fk_eval_case_kb FOREIGN KEY (knowledge_base_id)
        REFERENCES knowledge_base (id) ON DELETE CASCADE,
    CONSTRAINT ck_eval_case_query CHECK (length(query_text) > 0)
);

COMMENT ON TABLE  eval_case IS '召回评估用例表：一行 = 一条测试查询 + 该查询期望命中的块（期望块在 eval_case_expected_chunk 中）';
COMMENT ON COLUMN eval_case.id                IS '用例主键 UUID';
COMMENT ON COLUMN eval_case.knowledge_base_id IS '所属知识库 ID，级联删除';
COMMENT ON COLUMN eval_case.name              IS '用例名称，便于在报告里识别，如 "HNSW 索引原理"';
COMMENT ON COLUMN eval_case.query_text        IS '测试查询原文';
COMMENT ON COLUMN eval_case.remark            IS '备注/预期说明';
COMMENT ON COLUMN eval_case.enabled           IS '是否参与批量评估；false 时评估执行会跳过该用例';
COMMENT ON COLUMN eval_case.last_recall_at_k  IS '最近一次评估的 Recall@K 值（K 取评估请求中的 topK），便于列表直接展示';
COMMENT ON COLUMN eval_case.last_mrr          IS '最近一次评估的 MRR 值';
COMMENT ON COLUMN eval_case.last_evaluated_at IS '最近一次评估时间';
COMMENT ON COLUMN eval_case.created_at        IS '创建时间';
COMMENT ON COLUMN eval_case.updated_at        IS '最后更新时间，由触发器自动维护';

CREATE INDEX idx_eval_case_kb ON eval_case (knowledge_base_id, enabled);
-- 同一知识库内用例名唯一（接口 40901 依赖该约束）
CREATE UNIQUE INDEX uk_eval_case_kb_name ON eval_case (knowledge_base_id, name);

CREATE TABLE eval_case_expected_chunk (
    id            UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    eval_case_id  UUID        NOT NULL,
    node_id       UUID        NOT NULL,
    document_id   UUID,
    chunk_index   INTEGER,
    relevance     SMALLINT    NOT NULL DEFAULT 1,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT fk_eval_exp_case FOREIGN KEY (eval_case_id) REFERENCES eval_case (id)     ON DELETE CASCADE,
    CONSTRAINT fk_eval_exp_node FOREIGN KEY (node_id)      REFERENCES summary_nodes (id) ON DELETE RESTRICT,
    CONSTRAINT fk_eval_exp_doc  FOREIGN KEY (document_id)  REFERENCES document (id)      ON DELETE SET NULL,
    CONSTRAINT uk_eval_exp_case_node UNIQUE (eval_case_id, node_id),
    CONSTRAINT ck_eval_exp_relevance CHECK (relevance IN (0, 1))
);

COMMENT ON TABLE  eval_case_expected_chunk IS '评估用例的期望命中块：一条用例可挂多个期望块，用于计算 Recall@K / Hit Rate@K / MRR';
COMMENT ON COLUMN eval_case_expected_chunk.id           IS '主键 UUID';
COMMENT ON COLUMN eval_case_expected_chunk.eval_case_id IS '所属评估用例 ID，级联删除';
COMMENT ON COLUMN eval_case_expected_chunk.node_id      IS '期望命中的 summary_nodes.id（必须是 LEAF 节点；RESTRICT 保证被引用的块不可被误删）';
COMMENT ON COLUMN eval_case_expected_chunk.document_id  IS '冗余字段：期望块所属文档，便于报告按文档聚合';
COMMENT ON COLUMN eval_case_expected_chunk.chunk_index  IS '冗余字段：期望块在该文档内的块序号，便于报告展示';
COMMENT ON COLUMN eval_case_expected_chunk.relevance    IS '相关度：1=相关（参与 Recall/MRR 计算），0=弱相关（当前仅记录，不参与计算）。默认 1';
COMMENT ON COLUMN eval_case_expected_chunk.created_at   IS '创建时间';

CREATE INDEX idx_eval_exp_case ON eval_case_expected_chunk (eval_case_id);

-- ---------------------------------------------------------------------------
-- 8. updated_at 自动维护触发器
-- ---------------------------------------------------------------------------
CREATE OR REPLACE FUNCTION fn_set_updated_at() RETURNS trigger AS $$
BEGIN
    NEW.updated_at := now();
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

COMMENT ON FUNCTION fn_set_updated_at() IS '通用触发器函数：把行的 updated_at 刷新为 now()，供各业务表的 BEFORE UPDATE 触发器复用';

CREATE TRIGGER trg_knowledge_base_updated_at BEFORE UPDATE ON knowledge_base
    FOR EACH ROW EXECUTE FUNCTION fn_set_updated_at();
CREATE TRIGGER trg_document_updated_at       BEFORE UPDATE ON document
    FOR EACH ROW EXECUTE FUNCTION fn_set_updated_at();
CREATE TRIGGER trg_summary_nodes_updated_at  BEFORE UPDATE ON summary_nodes
    FOR EACH ROW EXECUTE FUNCTION fn_set_updated_at();
CREATE TRIGGER trg_async_task_updated_at     BEFORE UPDATE ON async_task
    FOR EACH ROW EXECUTE FUNCTION fn_set_updated_at();
CREATE TRIGGER trg_eval_case_updated_at      BEFORE UPDATE ON eval_case
    FOR EACH ROW EXECUTE FUNCTION fn_set_updated_at();

-- =====================================================================================
-- 9. 执行验证记录（是否已被实际执行过）
-- -------------------------------------------------------------------------------------
-- 【已实际执行】本文件由 architect 于 2026-09-17 通过 JDBC（postgresql-42.7.4.jar）
-- 针对 jdbc:postgresql://192.168.233.130:5432/doc_raptor_db 逐语句执行，
-- 结果：全部语句执行成功，0 报错；7 张表、2 个实测索引（HNSW + bm25）、
--       多个业务索引（含唯一/部分唯一索引）、5 个触发器均创建成功。
-- 原始输出与结论记录在 docs/01-architecture.md 的「DDL 执行验证」小节，
-- 完整原始输出另存于 docs/.ddl-execution-result.txt 与 docs/.ddl-smoke-test-result.txt。
-- 后端可直接使用该库中的这些表，无需再手工建表。
-- =====================================================================================
