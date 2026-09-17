# DocRaptor 架构设计文档（docs/01-architecture.md）

> 版本：v1.0（architect 产出，与 `docs/02-schema.sql`、`docs/03-api-contract.md` 同批冻结）
> 事实来源：`docs/00-environment-facts.md`（Lead 实测）。本文所有与环境相关的结论均以该文件为准；
> 已在该简报基础上逐条复核，复核结论见第 10.6 节（**未发现冲突项**）。
> 本文只描述设计与 DDL，不含 Java/Vue 实现代码。

---

## 1. 系统定位与范围

DocRaptor 是一个 **RAPTOR 风格文档检索系统的最小可用版（MVP）**：

```
导入文档 → 解析 → 分块 → 向量化 → UMAP 降维 + GMM 语义聚类 → LLM 逐簇摘要
        → 递归对上层摘要再聚类再摘要 → 建摘要树 → 多模式检索与召回测试
```

**功能边界**

| 模块 | 内容 |
|---|---|
| 模块1 知识库管理 | 知识库 CRUD（可配置 chunkSize/overlap/chunkStrategy）、文档上传导入（PDF/DOCX/Markdown/TXT）、文档列表（可按知识库筛选）、块列表查询（来源文档 ID、块序号、字符数） |
| 模块2 RAPTOR 树构建 | 分块后自动向量化 → UMAP + GMM 聚类 → 每簇 LLM 摘要作为父节点 → 递归至唯一根 → 每层记录 level / parent_id / 覆盖块范围 / 摘要 / 向量。**树深上限 L3** |
| 模块3 检索与召回测试 | 纯向量 / 纯 BM25 / 混合(RRF)；参数 topK、similarityThreshold、hybridRatio、rrfK(默认 60)；范围：仅叶子 / 全部层级(折叠树) / 指定层级；结果展示层级、来源文档、各路原始排名与分数；Recall@K、Hit Rate@K、MRR 评估 |
| 模块4 基础功能 | 导入/分块/向量化/建树均为异步任务且进度可查；检索日志落库；**已导入文档不可改删正文，只能禁用** |

**明确不做**：用户权限/登录鉴权（所有接口直接操作，无鉴权）；rerank 环节（端点 404）；MQ；Redis。

---

## 2. 架构决策清单（继承 Lead 拍板的 6 条 + 本设计的落地方式）

| # | 决策 | 本设计如何落地 |
|---|---|---|
| D1 | 统一节点表 `summary_nodes`，叶子与摘要同表，`node_type ∈ (LEAF, SUMMARY)`，叶子 `level=0`，`parent_id` 自引用 | 见 `02-schema.sql` 第 4 节。**本设计不单独建 chunk 表**：文本块 = `summary_nodes` 中 `node_type='LEAF'` 的行；在 DDL 内与 `03-api-contract.md` 中均显式说明 |
| D2 | 检索同表走向量 + BM25 两路，RRF 在 Java 侧算，不依赖数据库扩展 | `VectorSearchService` 用 `<=>` 算余弦距离；`Bm25SearchService` 用 `@@@`+`paradedb.score`；`RrfFusionService` 纯 Java 融合 |
| D3 | 异步任务用 Spring `@Async` + 线程池，进度落库 `async_task`，不用 Redis / MQ | `AsyncTaskService` + `AsyncTaskProgressReporter`（`REQUIRES_NEW` 独立事务）；`spring.data.redis` 配置保留但**运行时不依赖**（从未注入 `RedisTemplate` 到任何业务链路） |
| D4 | 不做权限/登录模块 | 无 Spring Security 依赖、无鉴权过滤器、无 userId 字段 |
| D5 | 文档导入后不可改删正文，只能 `enabled=false` 禁用 | `document` 无 update-content 接口；`enabled` 是唯一可写业务字段；所有检索 SQL 强制 `JOIN document d ON d.enabled = true` |
| D6 | 向量维度 1536，embedding 调用必须带 `dimensions=1536` | `summary_nodes.embedding vector(1536)` + HNSW(cosine) 索引；`EmbeddingService` 固定传 `dimensions=1536`；知识库表 `embedding_dimension` 有 `CHECK (=1536)` 兜底 |

**额外决策（本设计新增，需 Lead 确认）**

| # | 决策 | 理由 |
|---|---|---|
| D7 | 全库主键使用 **UUID**（`DEFAULT gen_random_uuid()`），不用自增 BIGINT | MyBatis 下 INSERT 后无需 `useGeneratedKeys`，应用侧直接生成 ID，兼容 `@Options` 差异；且与 pgvector / pg_search 的 `key_field` 语义天然一致 |
| D8 | `async_task` 建 **部分唯一索引** `uk_async_task_active_doc`，保证同一 (document_id, task_type) 同时只有一个 PENDING/RUNNING 任务 | 幂等与并发保护的底线，由数据库强制而非应用判断（已实测生效，见第 9 节 (4)） |
| D9 | 检索参数 `rrfK` 只作 RRF 平滑常数；"向量 vs BM25 权重"由 `hybridRatio`（向量权重，BM25 权重 = 1 − hybridRatio）+ `bm25Weight`（整体缩放系数，默认 1.0）共同表达 | 严格满足"hybridRatio（向量 vs BM25 权重）+ rrfK（默认 60）"两项要求，同时给前端一个可独立微调 BM25 的旋钮 |
| D10 | `updated_at` 由数据库触发器 `fn_set_updated_at()` 维护，应用侧不写 | MyBatis 无 JPA 的 `@PreUpdate`，触发器是唯一不依赖 ORM 的可靠做法 |
| D11 | 摘要 Prompt 使用**固定模板 + 硬约束段**，模板来自 `ai.summary-prompt`，结尾两句为不可删改的约束句 | 严控幻觉，见第 4.6 节 |
| D12 | `eval_case` 的期望块用**子表** `eval_case_expected_chunk` 存储（而非 JSON/数组列） | 支持一个查询对应多个期望块；FK `ON DELETE RESTRICT` 防止被引用的块被误删 |

---

## 3. 分层架构与包结构

根包 `com.yanglizi.docraptor`。分层：**接口层 → 应用/服务层 → 领域数据层 → 基础设施层**，同层单向依赖，禁止反向与跨层直接调用 Mapper。

```
com.yanglizi.docraptor
├── DocRaptorApplication.java              （已存在，空壳启动类，不改）
│
├── common/                    通用层：统一返回、错误码、异常、工具
│   ├── R.java                              统一响应包装 {code,message,data}
│   ├── ErrorCode.java                      错误码枚举（见 03-api-contract.md 第 3 节）
│   ├── BizException.java                   业务异常（携带 ErrorCode）
│   ├── GlobalExceptionHandler.java         @RestControllerAdvice，全部异常转 R
│   ├── PageResult.java                     分页返回 {list,total,page,pageSize}
│   └── IdUtils.java                        UUID 生成/校验
│
├── config/                    配置层
│   ├── AsyncConfig.java                    @EnableAsync + docRaptorTaskExecutor 线程池
│   ├── MyBatisConfig.java                  扫描 Mapper（启动类已有 @MapperScan 亦可）
│   ├── WebMvcConfig.java                   CORS、Jackson 时间序列化（毫秒时间戳）
│   └── properties/
│       ├── ChunkProperties.java            docraptor.chunk.*
│       ├── RaptorProperties.java           docraptor.raptor.*（UMAP / GMM / maxLevel）
│       ├── SummaryProperties.java          docraptor.summary.*（含 summary-prompt）
│       ├── RetrievalProperties.java        docraptor.retrieval.*（topK / rrfK 等默认值）
│       ├── EmbeddingProperties.java        docraptor.embedding.*
│       └── ImportProperties.java           docraptor.import.*（存储路径、大小限制、并发）
│
├── controller/                接口层：只做参数校验 + 调 Service + 包 R，不含业务逻辑
│   ├── KnowledgeBaseController.java        /api/knowledge-bases/**
│   ├── DocumentController.java             /api/documents/**（上传/列表/详情/禁用/块列表）
│   ├── RaptorTreeController.java           /api/raptor/**
│   ├── RetrievalController.java            /api/retrieval/**
│   ├── EvalController.java                 /api/eval/**（用例 CRUD + 评估执行）
│   └── AsyncTaskController.java            /api/async-tasks/**
│
├── domain/                    领域模型层：与表一一对应的 POJO（Lombok @Data）
│   ├── entity/
│   │   ├── KnowledgeBase.java              knowledge_base
│   │   ├── DocumentEntity.java             document（避免与 java.io.File 命名冲突）
│   │   ├── SummaryNode.java                summary_nodes（叶子块与摘要节点共用）
│   │   ├── RetrievalLog.java               retrieval_log
│   │   ├── AsyncTask.java                  async_task
│   │   ├── EvalCase.java                   eval_case
│   │   └── EvalCaseExpectedChunk.java      eval_case_expected_chunk
│   └── enums/
│       ├── NodeType.java                   LEAF / SUMMARY
│       ├── ChunkStrategy.java              FIXED_SIZE / PARAGRAPH / RECURSIVE
│       ├── FileType.java                   PDF / DOCX / MARKDOWN / TXT
│       ├── TaskType.java                   DOC_IMPORT / DOC_PARSE / DOC_CHUNK / DOC_EMBED / RAPTOR_BUILD / EVAL_RUN
│       ├── TaskStatus.java                 PENDING / RUNNING / SUCCESS / PARTIAL_SUCCESS / FAILED / CANCELED
│       ├── StepStatus.java                 PENDING / RUNNING / SUCCESS / FAILED / SKIPPED
│       ├── RetrievalMode.java              VECTOR / BM25 / HYBRID
│       └── RetrievalScope.java             LEAF_ONLY / ALL_LEVELS / SPECIFIED_LEVEL
│
├── dto/                       传输对象层（请求 DTO / 响应 VO），字段名与 03-api-contract.md 严格一致
│   ├── request/
│   │   ├── KnowledgeBaseCreateRequest.java / KnowledgeBaseUpdateRequest.java
│   │   ├── RetrievalRequest.java            （mode/topK/similarityThreshold/hybridRatio/bm25Weight/rrfK/scope/levels）
│   │   ├── EvalCaseCreateRequest.java / EvalRunRequest.java
│   │   └── RaptorBuildRequest.java          （maxLevel / 聚类参数覆盖）
│   └── response/
│       ├── KnowledgeBaseVO.java / DocumentVO.java / ChunkVO.java
│       ├── SummaryNodeVO.java / TreeNodeVO.java / TreeVO.java
│       ├── RetrievalHitVO.java / RetrievalResultVO.java / ScoreBreakdownVO.java
│       ├── EvalResultVO.java / EvalCaseVO.java
│       └── AsyncTaskVO.java
│
├── mapper/                    MyBatis 接口层
│   ├── KnowledgeBaseMapper.java
│   ├── DocumentMapper.java
│   ├── SummaryNodeMapper.java              含 selectByVector / selectByBm25 / selectTree / insertBatch
│   ├── RetrievalLogMapper.java
│   ├── AsyncTaskMapper.java
│   ├── EvalCaseMapper.java
│   └── EvalCaseExpectedChunkMapper.java
│
├── service/                   业务服务层（接口 + impl）
│   ├── KnowledgeBaseService.java
│   ├── DocumentImportService.java          编排 导入→解析→分块→向量化（同步顺序执行，被异步任务调用）
│   ├── ChunkService.java                   三种分块策略
│   ├── EmbeddingService.java               Spring AI EmbeddingModel，批量 + 重试 + dimensions=1536
│   ├── RaptorTreeService.java              UMAP + GMM + 递归摘要建树（核心算法）
│   ├── SummaryService.java                 LLM 摘要（严格 Prompt 约束）
│   ├── RetrievalService.java               三模式检索总入口
│   ├── VectorSearchService.java            向量路（<=>）
│   ├── Bm25SearchService.java              BM25 路（@@@ + paradedb.score）
│   ├── RrfFusionService.java               RRF 融合 + 三种 scope 过滤/折叠
│   ├── EvalService.java                    Recall@K / Hit Rate@K / MRR
│   └── TreeQueryService.java               树结构查询与统计
│
├── parser/                    文档解析层
│   ├── DocumentParser.java                 接口：String parse(InputStream, FileType)
│   └── TikaDocumentParser.java             基于 Tika 3.2.2 AutoDetectParser 的实现
│
├── chunker/                   分块策略层（策略模式）
│   ├── Chunker.java                        接口：List<Chunk> split(String text, ChunkOptions)
│   ├── FixedSizeChunker.java               定长滑窗 + overlap
│   ├── ParagraphChunker.java               按空行段落聚合
│   ├── RecursiveChunker.java               递归分隔符：段落 → 句号/问号/感叹号 → 换行 → 硬切
│   └── ChunkerFactory.java                 按 ChunkStrategy 分发
│
├── algorithm/                 纯算法层（无 Spring、无 DB，可单测）
│   ├── UmapReducer.java                    Smile UMAP 封装
│   ├── GmmClusterer.java                   Smile GMM 封装 + 簇数选择 + 退化保护
│   └── RrfFusion.java                      RRF 公式实现（纯函数）
│
├── async/                     异步任务层
│   ├── AsyncTaskService.java               建任务、抢占、状态流转
│   ├── AsyncTaskProgressReporter.java      REQUIRES_NEW 独立事务写进度/心跳
│   ├── DocImportTaskWorker.java            @Async("docRaptorTaskExecutor")
│   └── RaptorBuildTaskWorker.java          @Async("docRaptorTaskExecutor")
│
└── exception/                 业务异常细分（全部继承 BizException）
    ├── DocumentParseException.java
    ├── LlmInvokeException.java
    ├── EmbeddingInvokeException.java
    └── RetrievalException.java
```

**MyBatis XML**：`src/main/resources/mapper/**/*.xml`（`application.yml` 已配 `classpath*:mapper/**/*.xml`），
向量/BM25/递归 CTE 三类复杂 SQL 全部写在 `SummaryNodeMapper.xml`，`mapUnderscoreToCamelCase` 已开启，列名 snake_case 直接映射驼峰字段。

---

## 4. 完整数据流转

### 4.1 端到端时序（含异步边界）

```
[前端] POST /api/documents/upload (multipart)
   │
   ▼
[Controller] 校验扩展名/大小 → 落盘 data/upload/{docId}.{ext} → INSERT document(4 个 step_status=PENDING)
   │        → INSERT async_task(DOC_IMPORT, status=PENDING, payload={chunkSize,chunkOverlap,chunkStrategy,maxLevel})
   │        → 返回 {documentId, taskId}   ← HTTP 在此立即返回（异步边界①）
   ▼
[AsyncWorker] DocImportTaskWorker.run(taskId)   ← 异步边界②：独立线程 + 独立事务
   │
   ├─ 阶段 PARSE   (progress 0→15)
   │    Tika AutoDetectParser 抽取纯文本 → 归一化（去零宽字符、合并多余空行）
   │    → UPDATE document SET parse_status=SUCCESS, char_count=N
   │
   ├─ 阶段 CHUNK   (progress 15→35)
   │    ChunkerFactory 按 chunkStrategy 切块 → List<Chunk{index, text}>
   │    → INSERT summary_nodes(node_type=LEAF, level=0, chunk_index=i,
   │                          start_chunk_index=i, end_chunk_index=i, content, char_count)
   │    → UPDATE document SET chunk_status=SUCCESS, chunk_count=M
   │
   ├─ 阶段 EMBED   (progress 35→70)
   │    批量调用 embedding（batch=32，dimensions=1536，失败重试 3 次指数退避）
   │    → UPDATE summary_nodes SET embedding = ?::vector WHERE id = ?   （逐块回填）
   │    → 每批更新 progress_message="已向量化 x/M 块"
   │    → UPDATE document SET embed_status=SUCCESS
   │
   ├─ 阶段 TREE_BUILD (progress 70→100)  ← 见 4.3 / 第 6 节
   │    递归：聚类 → 逐簇摘要 → 写 SUMMARY 节点 → 改叶子 parent_id → 上一层重复
   │    → UPDATE document SET tree_status=SUCCESS
   │
   └─ 完成：UPDATE async_task SET status=SUCCESS, progress=100, result={...}, finished_at=now()
```

**异步边界小结**

| 边界 | 位置 | 说明 |
|---|---|---|
| ① HTTP 返回 vs 流水线执行 | Controller 返回后 | 上传接口不做任何解析/向量化，只落盘 + 建任务 |
| ② 异步工作线程 | `@Async("docRaptorTaskExecutor")` | 专用线程池，与 Tomcat 线程隔离 |
| ③ 进度写入 vs 业务事务 | `AsyncTaskProgressReporter` | 用 `REQUIRES_NEW`，保证业务回滚时进度不丢 |
| ④ LLM / Embedding 远程调用 | `SummaryService` / `EmbeddingService` | 长耗时且会限流；重试与退避在服务层内部，不外溢到任务层 |

### 4.2 各阶段失败恢复

**核心原则：每一步的产物都落库并带状态标记，失败后从"最后一个 SUCCESS 的步骤之后"重跑，不重做已完成的工作。**

| 失败点 | 已落库的产物 | 恢复动作 | 是否需要重新向量化 |
|---|---|---|---|
| 解析失败（加密 PDF、损坏 DOCX） | `document` 行，`parse_status=FAILED`，`parse_error` | 任务置 `FAILED`；用户修正文件后重新上传。**不允许在原文档上重试解析**（D5：正文不可改） | — |
| 分块失败 | 无节点写入（事务回滚） | 重新触发 `RAPTOR_BUILD` 或 `DOC_IMPORT` 的 CHUNK 之后步骤 | 否（尚未产生向量） |
| 向量化部分失败 | 已成功的叶子块已有 `embedding` | 重跑 EMBED 阶段时只处理 `embedding IS NULL` 的叶子块（`WHERE embedding IS NULL`） | 否，续跑 |
| 建树失败（LLM 限流 / 摘要超时） | 叶子块向量完整；已写入的 SUMMARY 节点存在 | **幂等重建**：删除该文档全部 `node_type='SUMMARY'` 节点并重置叶子 `parent_id=NULL`，再从 level=0 重新聚类；叶子块与其向量**绝不重建** | 否 |
| 树构建被中断（进程崩溃） | `async_task.heartbeat_at` 停止刷新 | 巡检：`status='RUNNING' AND heartbeat_at < now() - 5min` → 标为 `FAILED(error_message='heartbeat timeout')`，随后按上一行幂等重建 | 否 |
| 任务终态写入失败 | — | `finished_at` 与 `status` 在同一 UPDATE 写入；若失败则任务停留在 RUNNING，由心跳超时巡检兜底 | — |

**幂等性保障**

1. 所有主键为 UUID，由应用侧生成 → 同一逻辑行重复 INSERT 会撞 PK 而不是产生重复知识。
2. 建树前先 `DELETE FROM summary_nodes WHERE document_id=? AND node_type='SUMMARY'`，叶子块用 `UPDATE ... SET parent_id=NULL` 复位（这是把 D1 设计成"叶子 parent_id 可空"的原因，见第 9 节踩坑记录）。
3. `uk_async_task_active_doc` 阻止重复触发同一文档的同类任务 → 用户连点"重新建树"不会并发跑两遍。
4. 向量回填按 `embedding IS NULL` 过滤 → 重跑不重复扣 token。

### 4.3 跨文档 vs 单文档建树（MVP 取舍）

**MVP 采用"按文档独立建树"**：每个文档各自形成一棵 RAPTOR 树（`summary_nodes.document_id` 均非空），
因为文档级树让"覆盖的文本块范围"有明确定义（`start_chunk_index`/`end_chunk_index` 在同一文档内有意义），
也让失败恢复的粒度是文档级。跨文档/知识库级建树作为后续扩展：届时 `summary_nodes.document_id` 允许为 NULL，
`metadata.sourceNodeIds` 记录来源节点集合（DDL 已为此预留：`document_id` 可空 + `metadata JSONB`）。

### 4.4 检索链路

```
[前端] POST /api/retrieval/search  {knowledgeBaseId, query, mode, topK, ...}
   │
   ▼
RetrievalService.search()
   ├─ 1. 参数归一化与默认值填充（topK=10, similarityThreshold=0, hybridRatio=0.5, bm25Weight=1.0, rrfK=60, scope=ALL_LEVELS）
   ├─ 2. 若 mode ∈ {VECTOR, HYBRID}：query → embedding(dimensions=1536) → VectorSearchService（<=> + LIMIT topK*3）
   ├─ 3. 若 mode ∈ {BM25, HYBRID}：Bm25SearchService（@@@ + paradedb.score + LIMIT topK*3）
   ├─ 4. 若 mode = HYBRID：RrfFusionService.fuse(vectorRank, bm25Rank, hybridRatio, bm25Weight, rrfK)
   ├─ 5. 带上限过滤（similarityThreshold 只作用于向量路的 rawScore；BM25 路无阈值概念，不参与）
   ├─ 6. scope 处理：LEAF_ONLY 直接过滤；SPECIFIED_LEVEL 按 level IN (...) 过滤；
   │             ALL_LEVELS 走"折叠树"（同一条祖先链上只保留最终排名最高的一个节点）
   ├─ 7. 取 topK，组装 RetrievalHitVO（含 level/nodeType/documentInfo/scoreBreakdown/rank）
   └─ 8. 写 retrieval_log（log_type=SEARCH，含全部参数 + 各路耗时 + result_node_ids）
```

**文档禁用过滤**：向量路与 BM25 路的 SQL 都带
`JOIN document d ON d.id = s.document_id AND d.enabled = TRUE`（摘要节点若 `document_id IS NULL` 则不参与，MVP 不产生这类节点）。
**先过滤后排序**是最简且正确的做法；由于 MVP 数据量小（单文档几十到几百块），无需考虑 HNSW 预过滤的性能优化。

---

## 5. RAPTOR 递归建树算法

### 5.1 伪代码

```
输入：documentId, knowledgeBaseId, maxLevel（默认 3，可配置）
前提：该文档的 LEAF 节点已全部写入且 embedding 非空

RAPTOR_BUILD(documentId, kbId, maxLevel):
    # ---------- 0. 幂等复位 ----------
    DELETE FROM summary_nodes WHERE document_id=docId AND node_type='SUMMARY'
    UPDATE summary_nodes SET parent_id=NULL WHERE document_id=docId AND node_type='LEAF'
    buildId = uuid()                                  # 写入 metadata.buildId，便于排查

    current = SELECT id, embedding FROM summary_nodes
              WHERE document_id=docId AND node_type='LEAF' AND embedding IS NOT NULL
              ORDER BY chunk_index
    标记 scope = { chunkIndex: [start,end] }           # 每个节点的覆盖块范围

    IF |current| <= 1:
        RETURN                                          # 单块文档无需建树

    level = 1
    LOOP:
        # ---------- 1. 停止条件 ----------
        IF |current| <= 1:            BREAK              # 只剩一个节点，已成根
        IF level > maxLevel:          BREAK              # 命中深度上限 L3

        # ---------- 2. UMAP 降维 ----------
        X = matrix(|current| × 1536)                     # 取当前层节点向量
        IF |current| <= nNeighbors:                      # 样本太少，UMAP kNN 退化
            nComp = max(2, |current| - 2)                # 降维目标维度
            useUmap = FALSE                              # 直接在原空间聚类
            Y = X
        ELSE:
            nComp = umap.nComponents                     # 默认 10
            Y = UMAP(nNeighbors = umap.nNeighbors,       # 默认 10（可配置）
                     minDist     = umap.minDist,         # 默认 0.1（可配置）
                     nComponents = nComp,
                     metric      = 'cosine',
                     randomState = umap.randomState)     # 默认 42，保证可复现

        # ---------- 3. GMM 聚类 ----------
        nClusters = chooseClusterCount(Y)
        labels   = GMM(Y, k = nClusters,                 # 默认 max 8（可配置）
                       covarianceType = gmm.covarianceType,   # 默认 'diagonal'
                       randomState    = gmm.randomState).predict(Y)
        # 退化保护：簇数被夹紧到 [1, max(1, floor(|current| / minClusterSize))]
        IF nClusters <= 1:
            # 无法继续分裂：把本层所有节点直接提升为一个父节点（单副本地摘要）
            CREATE_SUMMARY_NODE(level, scope = union(all), children = current)
            BREAK

        # ---------- 4. 逐簇摘要（并发，受 maxConcurrency 限制） ----------
        newLevel = []
        PARALLEL FOR c IN 0 .. nClusters-1:
            members    = [ node for node,label in zip(current,labels) if label == c ]
            IF members 为空: CONTINUE
            clusterText = concat(sourceText(m) for m in members)     # 带 [块#idx] 标注
            summaryText = LLM_SUMMARIZE(clusterText)                 # 见 5.3 Prompt 约束
            summaryVec  = EMBED(summaryText, dimensions=1536)
            node = INSERT INTO summary_nodes(
                       id=uuid(), knowledge_base_id=kbId, document_id=docId,
                       parent_id=NULL,                                   # 根节点留空，非根节点后续修正
                       node_type='SUMMARY', level=level,
                       start_chunk_index=min(scope[m].start for m in members),
                       end_chunk_index  =max(scope[m].end   for m in members),
                       content=summaryText, summary=summaryText,
                       char_count=len(summaryText),
                       embedding=summaryVec,
                       cluster_label=c, cluster_size=|members|,
                       metadata={buildId, umap:{...}, gmm:{...},
                                 sourceNodeIds:[m.id for m in members]})
            UPDATE summary_nodes SET parent_id = node.id
                   WHERE id IN (m.id for m in members)              # 建立父子边
            newLevel.add(node)

        current = newLevel
        level   = level + 1

    # ---------- 5. 收尾 ----------
    IF level-1 >= 1 AND |current| == 1:
        # 唯一根节点的 parent_id 保持 NULL（DDL 允许，见 ck_summary_nodes_leaf_shape）
        标记该节点 metadata.isRoot = true
    UPDATE document SET tree_status='SUCCESS'
    返回 { rootNodeId, maxLevel=level-1, nodeCount, summaryNodeCount }
```

### 5.2 停止条件（三选一，命中任意一条即停）

| 条件 | 判定 | 结果 |
|---|---|---|
| **收敛** | 本层节点数 ≤ 1 | 该节点即根节点，树高 = 当前 level |
| **深度上限** | `level > maxLevel`（默认 `maxLevel = 3`，即最深节点 `level ≤ 3`） | 停止递归；若此时最高层仍有多个节点，**在 level = maxLevel 上补一次"合并摘要"**：把这些节点合并成**一个** `level=maxLevel` 的根节点（记为 `metadata.forcedRoot=true`），保证树有唯一根 |
| **无法再分** | 聚类结果 `nClusters ≤ 1`（GMM 判定所有节点同属一簇） | 生成一个父节点收尾，停止 |

> **`maxLevel` 的语义**：`maxLevel = 3` 表示**允许存在的最大 level 值为 3**（叶子 level=0，其父 level=1，再上 level=2，再上 level=3）。
> 因此"深度上限 L3"在本系统 = 最多 3 层摘要、最多 4 层节点。DDL 中 `ck_summary_nodes_level CHECK (level BETWEEN 0 AND 10)` 给实现留了余量，但默认配置与验收口径按 L3。

### 5.3 摘要 Prompt（硬约束，不可删改）

`ai.summary-prompt`（即架构配置项 `docraptor.summary.prompt`）的正式内容如下，**结尾两句为不可删改的约束句**：

```
你是一个严谨的文档摘要器。请对下面提供的文本块做一个信息压缩式的摘要。

要求：
1. 仅基于提供的文本块进行总结，禁止添加任何未在原文中出现的信息。只做信息压缩，不添加新事实。
2. 保留原文中的关键术语、专有名词、数字、结论与因果关系；不要引入外部知识或常识补充。
3. 删除冗余表述与重复内容，长度控制在 {maxSummaryChars} 字以内。
4. 若文本块内容彼此无关，请按原文顺序分别概括，不要强行编造它们的共同主题。
5. 只输出摘要正文，不要输出"摘要："之类的前缀，不要输出任何解释或评价。

待摘要的文本块：
---
{clusterText}
---

再次强调：仅基于提供的文本块进行总结，禁止添加任何未在原文中出现的信息。只做信息压缩，不添加新事实。
```

**工程约束**

| 项 | 做法 |
|---|---|
| 思考模式 | 必须传 `enable_thinking=false`（实测 completion_tokens 从 1034 → 23，既慢又贵） |
| temperature | 0.2（摘要要稳，不要发散） |
| 输入裁剪 | 单簇输入超过 `maxInputChars`（默认 12000）时先做**分段摘要再合并**，避免超长上下文被截断丢信息 |
| 并发与限流 | `maxConcurrency=4`，`maxRetries=3`，指数退避 1s/2s/4s；记录 `async_task.retry_count` |
| 失败处理 | 单簇摘要连续失败 3 次 → 该簇降级：**用成员原文前 N 字符直接拼接**作为父节点 content（`metadata.degraded=true`），不阻塞整棵树 |

### 5.4 UMAP / GMM 参数配置表

见第 8 节完整配置表，关键三项：`umap.n-neighbors`（默认 10）、`umap.min-dist`（默认 0.1）、`gmm.max-clusters`（默认 8）**全部可配置**，且每次建树时把实际使用值写进 `summary_nodes.metadata`，保证可追溯。

---

## 6. 混合检索与 RRF 融合算法

### 6.1 两路召回

| 路 | SQL 要点 | 原始分 | 分数量纲 |
|---|---|---|---|
| 向量路 | `SELECT id, 1 - (embedding <=> ?::vector) AS raw_score FROM summary_nodes WHERE knowledge_base_id=? AND embedding IS NOT NULL ORDER BY embedding <=> ?::vector LIMIT ?` | `1 - cosine_distance` ∈ [-1, 1]，实际正文多为 [0, 1] | 相似度，**越大越相关** |
| BM25 路 | `SELECT id, paradedb.score(id) AS raw_score FROM summary_nodes WHERE knowledge_base_id=? AND id @@@ paradedb.match('content', ?) ORDER BY raw_score DESC LIMIT ?` | `paradedb.score(id)`（real） | 测验量级约 0.28 ~ 1.18，**越大越相关** |

两路各自先取 `candidateK = min(max(topK * candidateMultiplier, topK), 200)`（`candidateMultiplier` 默认 3），
保证融合时有足够候选，又不会把全表拉进内存。

### 6.2 RRF 融合公式

设第 r 条召回路的排名列表为 `L_r = [d_{r,1}, d_{r,2}, …]`（下标从 1 开始，按该路原始分降序），
权重为 `w_r`，平滑常数为 `k = rrfK`（**默认 60**），则文档 `d` 的融合分：

```
rank_r(d) = 位置下标 i 使得 d_{r,i} = d   （d 未出现在 L_r 中时该路不贡献）
RRF(d)    = Σ_r  w_r · 1 / (k + rank_r(d))

其中：
  w_vector = hybridRatio          （默认 0.5，范围 [0,1]）
  w_bm25   = (1 - hybridRatio) · bm25Weight   （bm25Weight 默认 1.0）
  k        = rrfK                 （默认 60，范围 [1, 1000]）
```

**按 RRF(d) 降序排列，取前 topK 条作为最终结果。**

- **`rrfK = 60` 的作用**：它是**排名平滑项**。`1/(k+rank)` 使得 k 越大，头部名次之间的差距被压得越平（`rank=1` 与 `rank=2` 的分差从 k=0 时的 0.5 降到 k=60 时的 0.00027），
  于是"某一路排第 1"这件事的相对优势变小，**多路共同召回的条目更容易胜出** —— 这正是 RRF 抗单路噪声的原理。k 越小则越偏向某一路的头部结果。
  默认 60 是 RRF 原始论文的经验值，本项目沿用，且允许前端调参做对比实验。
- **`hybridRatio` 的作用**：控制两条路的相对话语权。`hybridRatio = 1.0` 时退化为纯向量排序（BM25 权重为 0），`= 0.0` 时退化为纯 BM25 排序，`= 0.5` 为等权。
- **为什么不让 `similarityThreshold` 参与 RRF 打分**：阈值是**硬过滤**（向量路 raw_score < 阈值 → 该条不进排名列表），不是权重。若把它做成软权重，会导致"同一个阈值在不同 query 下效果不一致"，不利于实验复现。

### 6.3 融合后处理：三种 scope

| scope | 语义 | 实现 |
|---|---|---|
| `LEAF_ONLY` | 只看叶子块，模拟"没有 RAPTOR 树"的基线 | 两路 SQL 直接加 `AND node_type='LEAF'` |
| `SPECIFIED_LEVEL` | 只看指定层级（`levels` 数组，如 `[1,2]`） | 两路 SQL 直接加 `AND level IN (…)`；`levels` 为空 → 参数错误 `40001` |
| `ALL_LEVELS` | 全层级，**折叠树**：把命中的祖先与后代视为同一信息源，避免同一段内容在结果里刷屏 | 见下方算法 |

**折叠树（ALL_LEVELS）算法**

```
输入：融合后的有序列表 fused（已含 vectorRank/bm25Rank/rawScore）
输出：折叠后的结果列表

1. 为每个候选节点 n，沿 parent_id 向上走到根，得到祖先链
   ancestors(n) = [n.parent, n.parent.parent, …, root]        # 用递归 CTE 一次查出，见第 9 节 (3) 实测
2. 按 fused 顺序遍历；维护集合 kept（已保留的结果）
3. 对候选 n：
     IF ∃ m ∈ kept 使得 (m ∈ ancestors(n)) 或 (n ∈ ancestors(m)):
         丢弃 n（它是已保留结果的祖先或后代，属于同一条语义链）
     ELSE
         kept.add(n)
4. 取 kept 的前 topK 条返回
   若某条被丢弃，在其 VO.collapsedByNodeId 字段记下"被哪个保留节点折叠"，
   并在响应 costMs 同级给出 collapsedCount，便于前端展示与调试
```

> 折叠是"同链只留最优"的策略：因为父节点摘要覆盖了子节点的信息，检索时同一链条上给出多个结果对用户是噪声。
> 想看到全部层级明细时，用 `SPECIFIED_LEVEL` 指定单层即可绕过折叠。

### 6.4 结果 VO 必含字段（对应需求"各路原始排名与分数"）

```
RetrievalHitVO {
  nodeId, nodeType, level, documentId, documentName,
  chunkIndex, startChunkIndex, endChunkIndex, charCount,
  content,                       // 摘要节点展示 summary，叶子展示原文
  finalRank,                     // 融合后最终排名（1 起）
  finalScore,                    // 向量路 = rawScore；BM25 路 = rawScore；HYBRID = RRF 分
  scoreBreakdown {               // HYBRID 时逐路明细；单路模式另一路为 null
     vectorRawScore, vectorRank, vectorWeightedScore,
     bm25RawScore,  bm25Rank,  bm25WeightedScore,
     rrfK
  },
  collapsedByNodeId              // 被折叠时非空，指向保留的节点
}
```

---

## 7. 召回评估指标精确定义

**符号约定**

- `Q`：本次评估的查询集合（单查询评估时 `|Q| = 1`）
- 对查询 `q`：`R_q` = 检索返回的有序结果列表（按最终排名 1..K），`K = topK`
- `E_q` = 该用例的期望命中块 ID 集合，取自 `eval_case_expected_chunk` 中 `relevance = 1` 且 `node_id` 属于**叶子节点**的行；
  `N_q = |E_q|`（**当 `N_q = 0` 时该用例被跳过，不计入任何指标的分母**，并在响应 `skippedCases` 中列出）
- `hit_q@K = { i ∈ [1,K] : R_q[i] ∈ E_q }`（命中位置集合）
- **命中判定说明**：由于 RAPTOR 树里摘要节点代表其覆盖的叶子块，评估时按 **"命中叶子块本身"** 判定（严格口径，不做祖先折叠的宽松判定）。
  若希望把"命中某摘要节点"也算作其覆盖范围内全部叶子块命中，属于宽松口径，MVP **不采用**，以保证指标可复现。

**逐查询指标**

| 指标 | 公式 | 取值 |
|---|---|---|
| **Recall@K** | `Recall_q@K = |hit_q@K| / N_q` | [0,1]，越大越好 |
| **Hit Rate@K** | `HitRate_q@K = 1 若 hit_q@K ≠ ∅，否则 0` | {0,1}，越大越好 |
| **MRR** | `RR_q = min(hit_q@K)`（最小命中位置）；`RR_q = 0` 若 `hit_q@K = ∅`；`MRR_q = 1 / RR_q` | (0,1]，越大越好（未命中记 0） |

> MRR 的定义域内有 `K` 的截断：`hit_q@K` 为空时 `MRR_q = 0`（业界标准做法，便于直接求平均）。

**数据集级指标（对未跳过用例求算术平均）**

```
Recall@K   = (1/|Q'|) · Σ_{q∈Q'} Recall_q@K
HitRate@K  = (1/|Q'|) · Σ_{q∈Q'} HitRate_q@K
MRR        = (1/|Q'|) · Σ_{q∈Q'} MRR_q

其中 Q' = { q ∈ Q : N_q > 0 }
```

**返回值约定**

- 单次评估请求可同时返回多个 K 的指标：`metrics` 数组，默认 `kList = [1, 3, 5, 10]`（与请求的 `topK` 无关，`topK` 决定实际召回深度，`kList` 中的 K 必须 ≤ `topK` 才能计算，超过 `topK` 的 K 会被自动裁剪并在响应 `truncatedKList` 中说明）。
- 同时返回 `perQuery` 明细数组（每条含 `caseId, query, recall, hitRate, reciprocalRank, hitNodeIds, missedNodeIds, latencyMs`），用于失败用例定位。
- 汇总还含 `avgLatencyMs`、`evaluatedCases`、`skippedCases`，以及**检索日志 id 列表** `retrievalLogIds`（每次评估调用都落 `retrieval_log`，`log_type='EVAL'`），保证指标可追溯到原始召回结果。

**计算示例（便于前后端对齐口径）**

```
查询 q 的期望块 E_q = {A, B, C}，N_q = 3
返回 topK=5 的顺序：[X, A, Y, B, Z]
  hit_q@5 = {2, 4}          → |hit| = 2
  Recall@5      = 2/3 ≈ 0.6667
  HitRate@5     = 1
  RR_q          = min{2,4} = 2   → MRR_q = 1/2 = 0.5
若返回 topK=1 的顺序：[X]      （用 kList 里的 K=1 计算）
  Recall@1 = 0/3 = 0，HitRate@1 = 0，MRR@1 = 0
```

---

## 8. 可配置项配置表

前缀统一为 `docraptor`。所有项都放在 `application.yml`（默认值）+ `application-local.yml`（本机覆盖）中，
由第 3 节的 `*Properties` 类绑定。**`ai.*` 为既有配置，不改名。**

### 8.1 分块（`docraptor.chunk.*`；知识库级可覆盖，建库时固化到 `knowledge_base` 表）

| key | 默认值 | 含义 | 取值范围 |
|---|---|---|---|
| `docraptor.chunk.size` | `512` | 分块目标字符数 | [64, 8192] |
| `docraptor.chunk.overlap` | `64` | 相邻块重叠字符数，必须 < size | [0, size) |
| `docraptor.chunk.strategy` | `FIXED_SIZE` | 分块策略 | `FIXED_SIZE` / `PARAGRAPH` / `RECURSIVE` |
| `docraptor.chunk.min-chunk-chars` | `32` | 小于该长度的尾块并入前一块，避免碎片 | [0, 512] |
| `docraptor.chunk.normalize-whitespace` | `true` | 是否归一化空白（去零宽字符、合并空行） | true / false |

### 8.2 向量化（`docraptor.embedding.*`）

| key | 默认值 | 含义 | 取值范围 |
|---|---|---|---|
| `docraptor.embedding.batch-size` | `32` | 单次 embedding 请求的文本条数 | [1, 64] |
| `docraptor.embedding.dimensions` | `1536` | 输出维度，**必须 1536** | 固定 1536（改则必须同步改 DDL 与 HNSW 索引） |
| `docraptor.embedding.max-retries` | `3` | 失败重试次数 | [0, 5] |
| `docraptor.embedding.retry-backoff-ms` | `1000` | 退避基数（1s/2s/4s 指数） | [100, 10000] |
| `docraptor.embedding.timeout-ms` | `30000` | 单次请求超时 | [1000, 120000] |

### 8.3 RAPTOR 建树（`docraptor.raptor.*`）

| key | 默认值 | 含义 | 取值范围 |
|---|---|---|---|
| `docraptor.raptor.max-level` | `3` | **树深上限 L3**：允许存在的最大 level 值 | [1, 10] |
| `docraptor.raptor.umap.enabled` | `true` | 是否启用 UMAP 降维（false 则直接原空间聚类，用于对比实验） | true / false |
| `docraptor.raptor.umap.n-neighbors` | `10` | **UMAP n_neighbors**，控制局部/全局结构权衡，越小越关注局部 | [2, 100] |
| `docraptor.raptor.umap.min-dist` | `0.1` | **UMAP min_dist**，控制降维后点的最小间距，越小簇越紧 | [0.0, 1.0] |
| `docraptor.raptor.umap.n-components` | `10` | 降维目标维度 | [2, 100] |
| `docraptor.raptor.umap.metric` | `cosine` | 距离度量 | `cosine` / `euclidean` |
| `docraptor.raptor.umap.random-state` | `42` | 随机种子，保证可复现 | 任意整数 |
| `docraptor.raptor.gmm.max-clusters` | `8` | **GMM 聚类数量上限**（实际簇数经选择算法夹紧到该上限内） | [2, 64] |
| `docraptor.raptor.gmm.min-clusters` | `2` | 簇数下限（不足则判定无法再分） | [1, max-clusters] |
| `docraptor.raptor.gmm.covariance-type` | `diagonal` | GMM 协方差类型 | `full` / `tied` / `diag` / `spherical`（Smile 取值为 `diagonal`） |
| `docraptor.raptor.gmm.random-state` | `42` | 随机种子 | 任意整数 |
| `docraptor.raptor.gmm.selection` | `BIC` | 簇数选择准则 | `BIC` / `AIC` / `FIXED`（FIXED 直接用 `max-clusters`） |
| `docraptor.raptor.min-cluster-size` | `2` | 小于该规模的簇不单独成簇（并入最近簇），避免大量单节点摘要 | [1, 32] |
| `docraptor.raptor.summary-concurrency` | `4` | 同层摘要的并发 LLM 调用数 | [1, 16] |
| `docraptor.raptor.rebuild-on-conflict` | `true` | 建树前是否自动清理旧 SUMMARY 节点（幂等重建） | true / false |

### 8.4 摘要（`docraptor.summary.*`；Prompt 模板同时映射到既有 `ai.summaryPrompt`）

| key | 默认值 | 含义 | 取值范围 |
|---|---|---|---|
| `docraptor.summary.prompt` | 见 5.3 | 摘要 Prompt 模板，含 `{clusterText}` `{maxSummaryChars}` 占位符 | 非空；**必须保留 5.3 的约束句** |
| `docraptor.summary.max-summary-chars` | `400` | 单簇摘要长度上限（字符） | [50, 4000] |
| `docraptor.summary.max-input-chars` | `12000` | 单次摘要的输入上限，超出则分段摘要再合并 | [1000, 100000] |
| `docraptor.summary.max-retries` | `3` | LLM 调用重试次数 | [0, 5] |
| `docraptor.summary.temperature` | `0.2` | 采样温度 | [0.0, 1.0] |
| `docraptor.summary.enable-thinking` | `false` | **必须 false**（实测 token 1034→23） | true / false |
| `docraptor.summary.max-tokens` | `1024` | 单次生成上限 | [64, 8192] |

### 8.5 检索（`docraptor.retrieval.*`；均为**接口未传参时的默认值**，前端可逐次覆盖）

| key | 默认值 | 含义 | 取值范围 |
|---|---|---|---|
| `docraptor.retrieval.default-mode` | `HYBRID` | 默认检索模式 | `VECTOR` / `BM25` / `HYBRID` |
| `docraptor.retrieval.default-top-k` | `10` | 默认 topK | [1, 100] |
| `docraptor.retrieval.max-top-k` | `100` | topK 上限，超过报 40002 | [1, 1000] |
| `docraptor.retrieval.default-similarity-threshold` | `0.0` | 默认相似度阈值（向量路硬过滤） | [0.0, 1.0] |
| `docraptor.retrieval.default-hybrid-ratio` | `0.5` | 默认向量权重 w_vector | [0.0, 1.0] |
| `docraptor.retrieval.default-bm25-weight` | `1.0` | 默认 BM25 路整体缩放系数 | [0.0, 10.0] |
| `docraptor.retrieval.default-rrf-k` | `60` | **默认 rrfK = 60** | [1, 1000] |
| `docraptor.retrieval.default-scope` | `ALL_LEVELS` | 默认检索范围 | `LEAF_ONLY` / `ALL_LEVELS` / `SPECIFIED_LEVEL` |
| `docraptor.retrieval.candidate-multiplier` | `3` | 各路候选数 = topK × 该系数（上限 200） | [1, 10] |
| `docraptor.retrieval.hnsw-ef-search` | `100` | `SET LOCAL hnsw.ef_search`（事务内生效） | [10, 1000] |
| `docraptor.retrieval.fold-tree` | `true` | `ALL_LEVELS` 是否启用折叠树 | true / false |
| `docraptor.retrieval.log-enabled` | `true` | 是否写 `retrieval_log` | true / false |

### 8.6 导入与异步（`docraptor.import.*`、`docraptor.async.*`）

| key | 默认值 | 含义 | 取值范围 |
|---|---|---|---|
| `docraptor.import.storage-dir` | `./data/upload` | 上传文件落盘目录 | 可写路径 |
| `docraptor.import.max-file-size-mb` | `10` | 单文件大小上限（与 `spring.servlet.multipart` 一致） | [1, 10]（受 multipart 限制） |
| `docraptor.import.allowed-extensions` | `pdf,docx,md,markdown,txt` | 允许的扩展名 | 逗号分隔 |
| `docraptor.import.max-parse-chars` | `2000000` | 解析文本字符数上限，超出截断并记 `metadata.truncated=true` | [10000, 10000000] |
| `docraptor.import.auto-build-tree` | `true` | 导入完成后是否自动建树 | true / false |
| `docraptor.async.core-pool-size` | `2` | 任务线程池核心线程数 | [1, 16] |
| `docraptor.async.max-pool-size` | `4` | 最大线程数 | [core, 32] |
| `docraptor.async.queue-capacity` | `100` | 队列容量 | [1, 10000] |
| `docraptor.async.heartbeat-stale-seconds` | `300` | 心跳超时判定秒数，超时任务被标 FAILED | [30, 3600] |

### 8.7 既有配置（不改名、不动语义）

| key | 当前值 | 备注 |
|---|---|---|
| `spring.ai.openai.base-url` | `${API_URL}` | 从环境变量读，绝不硬编码 |
| `spring.ai.openai.api-key` | `${API_KEY}` | 同上 |
| `spring.ai.openai.chat.model` | `qwen3.7-flash` | 摘要用 |
| `spring.ai.openai.embedding.model` | `qwen3.7-text-embedding` | 向量化用 |
| `spring.ai.openai.embedding.dimensions` | `1536` | **必须保持 1536** |
| `pg.database` | ⚠️ 需由 `DocRaptor` 改为 **`doc_raptor_db`** | 见第 10 节 冲突项 1 |
| `ai.summaryPrompt` | ⚠️ 占位"压缩待补充" | 替换为 5.3 的正式 Prompt |
| `spring.data.redis.*` | 保留但**不使用** | 本机 Redis 未启动；任何业务链路都不得注入 `RedisTemplate` |

---

## 9. 数据库表清单与 ER 关系

### 9.1 表清单（7 张，均由 `docs/02-schema.sql` 创建）

| # | 表名 | 中文名 | 主键 | 关键外键 | 说明 |
|---|---|---|---|---|---|
| 1 | `knowledge_base` | 知识库 | `id` UUID | — | 承载 chunkSize/overlap/strategy 配置，`name` 唯一 |
| 2 | `document` | 文档 | `id` UUID | `knowledge_base_id → knowledge_base` (CASCADE) | 4 个步骤状态 + `enabled`（禁用开关）；**即"文档表"** |
| 3 | `summary_nodes` | **统一节点表** | `id` UUID | `knowledge_base_id → knowledge_base` (CASCADE)、`document_id → document` (CASCADE)、`parent_id → summary_nodes` (CASCADE) | **叶子块 + 摘要节点同表**；`node_type ∈ (LEAF,SUMMARY)`；叶子 `level=0`。**本设计不再单独建 chunk 表** |
| 4 | `retrieval_log` | 检索日志 | `id` UUID | `knowledge_base_id → knowledge_base` (SET NULL) | 每次检索/评估一条，含全部参数与耗时 |
| 5 | `async_task` | 异步任务 | `id` UUID | `knowledge_base_id` (CASCADE)、`document_id → document` (CASCADE) | 进度落库；部分唯一索引防重复触发 |
| 6 | `eval_case` | 召回评估用例 | `id` UUID | `knowledge_base_id → knowledge_base` (CASCADE) | 测试查询 |
| 7 | `eval_case_expected_chunk` | 用例期望命中块 | `id` UUID | `eval_case_id` (CASCADE)、`node_id → summary_nodes` (**RESTRICT**)、`document_id → document` (SET NULL) | 期望块 ID 集合 |

### 9.2 ER 关系

```
                       ┌──────────────────┐
                       │ knowledge_base   │
                       │  id (PK)         │
                       │  name (UNIQUE)   │
                       │  chunk_size      │
                       │  chunk_overlap   │
                       │  chunk_strategy  │
                       └───┬──────────┬───┘
              1:N          │          │      1:N
        ┌──────────────────┘          └──────────────────┐
        ▼                                                ▼
┌──────────────────┐                          ┌──────────────────┐
│ document         │                          │ eval_case        │
│  id (PK)         │                          │  id (PK)         │
│  knowledge_base_id│                         │  knowledge_base_id│
│  enabled (禁用)  │                          │  query_text      │
│  parse/chunk/    │                          └────────┬─────────┘
│  embed/tree_status│                                1:N
└───┬──────────┬───┘                                  ▼
    │ 1:N      │ 1:N                       ┌──────────────────────────┐
    ▼          ▼                           │ eval_case_expected_chunk │
┌────────────────────────────────┐         │  eval_case_id (FK)       │
│ summary_nodes  (统一节点表)     │◄────────│  node_id (FK, RESTRICT)  │
│  id (PK)                       │  N:1    └──────────────────────────┘
│  knowledge_base_id (FK)        │
│  document_id (FK, 可空)         │        ┌──────────────────┐
│  parent_id (FK → self)         │        │ retrieval_log    │
│  node_type LEAF|SUMMARY        │        │  id (PK)         │
│  level  (叶子=0)                │        │  knowledge_base_id│
│  chunk_index                   │        │  mode/params/... │
│  start/end_chunk_index         │        └──────────────────┘
│  content / summary             │
│  embedding vector(1536)        │        ┌──────────────────┐
│    → HNSW cosine 索引           │        │ async_task       │
│  content → bm25 索引(key=id)    │        │  id (PK)         │
└────────────────────────────────┘        │  document_id (FK)│
                                          │  status/progress │
                                          └──────────────────┘
```

**关系要点**

1. `knowledge_base 1:N document`：删知识库级联删文档，进而级联删其全部节点。
2. `document 1:N summary_nodes`：一个文档的所有叶子块与其各层摘要节点。**禁用文档不影响这些行**（D5：数据保留）。
3. `summary_nodes.parent_id → summary_nodes.id`（自引用）：树的父子边。叶子建树前 `parent_id` 为 NULL，建树后指向其 level=1 摘要；
   非根摘要节点指向上一层摘要；**唯一根节点 `parent_id` 为 NULL**。
4. `eval_case 1:N eval_case_expected_chunk N:1 summary_nodes`：期望块必须是 `node_type='LEAF'` 的节点（由服务层校验），
   FK 用 `ON DELETE RESTRICT` 保证被评测引用的块无法被误删（已实测，见 9.4 (6)）。
5. `retrieval_log.knowledge_base_id` 用 `ON DELETE SET NULL`：删知识库后仍保留历史检索日志用于追溯。

### 9.3 关键索引

| 索引 | 表 | 定义 | 用途 |
|---|---|---|---|
| `idx_summary_nodes_embedding_hnsw` | `summary_nodes` | `USING hnsw (embedding vector_cosine_ops) WITH (m=16, ef_construction=64)` | **向量检索（cosine）** |
| `idx_summary_nodes_bm25` | `summary_nodes` | `USING bm25 (id, content) WITH (key_field='id', text_fields='{"content": {"tokenizer": {"type": "jieba"}}}')` | **BM25 全文检索**；一张表只允许一个 ParadeDB 索引（已实测） |
| `idx_summary_nodes_parent` | `summary_nodes` | `(parent_id)` | 自底向上递归 CTE（折叠树） |
| `idx_summary_nodes_kb_level` | `summary_nodes` | `(knowledge_base_id, level)` | `SPECIFIED_LEVEL` 范围过滤 |
| `idx_summary_nodes_doc` | `summary_nodes` | `(document_id, chunk_index)` | 块列表分页、建树取同一层节点 |
| `idx_summary_nodes_type` | `summary_nodes` | `(knowledge_base_id, node_type)` | `LEAF_ONLY` 过滤 |
| `uk_async_task_active_doc` | `async_task` | `UNIQUE (document_id, task_type) WHERE status IN ('PENDING','RUNNING') AND document_id IS NOT NULL` | 防重复触发同一文档的同类任务（已实测生效） |
| `uk_eval_case_kb_name` | `eval_case` | `UNIQUE (knowledge_base_id, name)` | 同一知识库内用例名唯一（支撑契约错误码 `40901`） |
| `uk_eval_exp_case_node` | `eval_case_expected_chunk` | `UNIQUE (eval_case_id, node_id)` | 同一用例内不重复挂同一期望块 |
| `idx_document_kb_enabled` | `document` | `(knowledge_base_id, enabled)` | 检索时的启用过滤 |

---

## 10. DDL 执行验证（实测）

### 10.1 执行方式与环境

```
执行器    : 自建 JDBC 执行器（D:\javaCode\DocRaptor\.arch-tmp\DbRun.java）
驱动      : .m2repo\org\postgresql\postgresql\42.7.4\postgresql-42.7.4.jar
命令      : java -cp ".arch-tmp\out;<postgresql-42.7.4.jar>" DbRun docs\02-schema.sql .arch-tmp\ddl-run-final.txt
连接      : jdbc:postgresql://192.168.233.130:5432/doc_raptor_db  (user=doc_raptor)
验证方式  : 逐语句 execute（不做单事务包裹），每条语句记录 SQLSTATE 与耗时
原始输出  : docs\.ddl-execution-result.txt（完整 259 行，未删改）
```

### 10.2 执行结果（原文摘录）

```
== JDBC DDL EXECUTION ==
file      : D:\javaCode\DocRaptor\docs\02-schema.sql
url       : jdbc:postgresql://192.168.233.130:5432/doc_raptor_db
statements: 160
time      : Thu Sep 17 22:00:11 CST 2026

server_version : PostgreSQL 18.6 (Debian 18.6-1.pgdg13+2) on x86_64-pc-linux-gnu, compiled by gcc (Debian 14.2.0-19) 14.2.0, 64-bit
database       : doc_raptor_db
user           : doc_raptor
extensions     : fuzzystrmatch 1.2, pg_ivm 1.13, pg_search 0.25.6, pg_stat_statements 1.12, plpgsql 1.0, postgis 3.6.4, postgis_tiger_geocoder 3.6.4, postgis_topology 3.6.4, vector 0.8.6

== RESULT: ok=160 fail=0 total=160 ==
```

**结论：160 条语句全部成功，0 报错。所有语句均为 `OK`，无任何 `FAIL` 行（输出文件中检索 `FAIL` 无匹配）。**

> 本 DDL 共执行过 3 轮：第 1 轮 159/159 通过后暴露了 10.5 的设计缺陷，修正后第 2 轮 159/159 通过，
> 追加 `uk_eval_case_kb_name` 唯一索引后第 3 轮（即上表 160/160）通过。脚本开头有 `DROP ... CASCADE`，
> **可重复执行**，3 轮执行均在同一个 `doc_raptor_db` 上连续成功。

### 10.3 创建结果核对（原文摘录）

```
== TABLE LIST ==
 - async_task
 - document
 - eval_case
 - eval_case_expected_chunk
 - knowledge_base
 - retrieval_log
 - summary_nodes
（另有 spatial_ref_sys / geography_columns / geometry_columns / pg_stat_statements* 等系统表）

== 关键索引（原文摘录）==
 - idx_summary_nodes_bm25
     CREATE INDEX idx_summary_nodes_bm25 ON public.summary_nodes USING bm25 (id, content) WITH (key_field=id, text_fields='{"content": {"tokenizer": {"type": "jieba"}}}')
 - idx_summary_nodes_embedding_hnsw
     CREATE INDEX idx_summary_nodes_embedding_hnsw ON public.summary_nodes USING hnsw (embedding vector_cosine_ops) WITH (m='16', ef_construction='64')

== COLUMN TYPE CHECK: summary_nodes.embedding ==
 - embedding | USER-DEFINED | vector
 - summary_embedding | USER-DEFINED | vector
 - content | text | text

== TRIGGERS ==
 - async_task   | trg_async_task_updated_at
 - document     | trg_document_updated_at
 - eval_case    | trg_eval_case_updated_at
 - knowledge_base | trg_knowledge_base_updated_at
 - summary_nodes | trg_summary_nodes_updated_at
```

7 张业务表、2 个必需索引（HNSW cosine + bm25）、唯一索引 `uk_eval_case_kb_name`、5 个 `updated_at` 触发器全部创建成功。

### 10.4 功能冒烟测试（同库真实数据，原始输出 `docs\.ddl-smoke-test-result.txt`）

| 验证项 | 结果 |
|---|---|
| 叶子块插入（含 1536 维向量）+ 摘要节点插入 + 叶子挂到父节点 | `leaf -> parent attach rows = 3` ✅ |
| **向量检索** `ORDER BY embedding <=> ?::vector` | 命中自身 `sim=1.0000`，其余 `0.0299 / 0.0247`，一次查询 17~21 ms ✅ |
| **BM25 检索** `content @@@ '检索'` + `paradedb.score(id)` | 2 条命中，`score=0.9456 / 0.8108`（中文经 jieba 分词命中）✅ |
| BM25 参数绑定写法 `id @@@ paradedb.match('content', ?)` | 2 条命中，`score=0.9656 / 0.8108`，**可直接用于 MyBatis `#{}`** ✅ |
| 递归 CTE 向上走父链（折叠树用） | 3 个叶子 depth=0，根节点 depth=1 ✅ |
| `async_task` 进度落库 | `progress=75 stage=EMBED msg=已向量化 3/3 块 trigger_updated_at=true`（触发器生效）✅ |
| **部分唯一索引防重复任务** | 第二个活跃任务被拒：`SQLSTATE=23505 duplicate key ... uk_async_task_active_doc` ✅ |
| 评估用例 + 期望块 ID 关联查询 | 正确关联到 `expectedChunk=…#0` ✅ |
| 检索日志写入（含 JSONB 参数） | OK ✅ |
| 约束负向测试（7 项） | 全部按预期拒绝：LEAF level=1 → `23514`；SUMMARY level=0 → `23514`；重名知识库 → `23505`；`chunk_overlap ≥ chunk_size` → `23514`；**1024 维向量 → `22000 expected 1536 dimensions, not 1024`**；**同表第二个 bm25 索引 → `XX000 a relation may only have one ParadeDB index`**；删除被评估引用的块 → `23001 violates RESTRICT` ✅ |
| 临时数据清理 | `rows left: 0 kb, 0 task, 0 node, 0 log, 0 case`（冒烟数据已全部清空，库中保留正式空表）✅ |

**结论：`docs/02-schema.sql` 已在本机真实数据库上执行通过，且建出的表/索引/约束经真实读写验证可用。后端可直接使用该库中的这些表。**

### 10.5 实测发现的设计缺陷与修正（重要，务必知悉）

**缺陷**：DDL 初版把 `ck_summary_nodes_leaf_shape` 写成
`LEAF 必须 ... AND parent_id IS NULL`，导致"把叶子块挂到摘要父节点"的 UPDATE 直接被拒：
`ERROR: new row for relation "summary_nodes" violates check constraint "ck_summary_nodes_leaf_shape"`（SQLSTATE `23514`）。

**根因**：这违反了 D1 的统一节点表设计本身 —— 叶子与摘要在同一张表里，树边只能靠 `parent_id` 表达，叶子在**已建树**状态下必须能持有父指针。原约束把"叶子无父"的**初始状态**误当成**不变式**。

**修正**：约束放宽为
`LEAF 必须 level=0 且有 document_id/chunk_index（parent_id 可空）`；
`SUMMARY 必须 level>=1（根节点 parent_id 可为 NULL）`。
已在 `docs/02-schema.sql` 中修正并重新执行（本次即 10.2 的 159/159 通过结果）。

**对其他同学的影响**：所有文档里"叶子节点 `parent_id` 为 NULL"的表述都应理解为
**"建树前为 NULL，建树后指向其 level=1 摘要节点"**；`docs/03-api-contract.md` 与前端树形图渲染均按此口径实现。

### 10.6 与 `docs/00-environment-facts.md` 的一致性

| 简报条目 | 实测复核 | 结论 |
|---|---|---|
| 第 2 节：PG 18.6 / doc_raptor_db / vector 0.8.6 / pg_search 0.25.6 | 完全一致（见 10.2 的 `server_version` 与 `extensions` 行） | 一致，无需修正 |
| 第 2.1 节：HNSW + cosine 写法 | 原样照抄，创建成功 | 一致 |
| 第 2.2 节：bm25 + `key_field='id'`，一表只能一个 bm25 索引 | 原样照抄创建成功；再建第二个确实报 `a relation may only have one ParadeDB index` | 一致，**已在 DDL 注释中把该限制写成硬约束** |
| 第 5 节 D1~D6 | 全部落实（见第 2 节映射表） | 一致 |

**未发现与简报冲突的实测结果。**

---

## 11. 已知限制与风险

| # | 限制/风险 | 影响 | 缓解 |
|---|---|---|---|
| R1 | 单文档规模较大时（>1000 块）UMAP + GMM + 逐簇 LLM 摘要耗时长、token 花费高 | 导入体验慢 | 全部异步 + 进度可查；`embedding`/`summary` 分阶段失败可续跑（4.2）；`max-level=3` 限制递归层数 |
| R2 | LLM 摘要可能产生幻觉 | 摘要节点内容不忠实 | Prompt 硬约束（5.3）+ `temperature=0.2` + `enable_thinking=false`；建议用召回评估的"严格口径"（命中叶子）来度量质量，而非宽松口径 |
| R3 | 小文档（块数 < `min-clusters`）无法聚类，只能退化摘要 | 树的语义分层不明显 | 退化路径已在算法内定义（5.1 的 `nClusters ≤ 1` 分支），不会报错 |
| R4 | HNSW 索引对**低选择性过滤**（如按 knowledge_base_id）不友好，PG 可能选择顺序扫描 | 大库下检索变慢 | 当前数据量小可接受；必要时启用 `SET LOCAL hnsw.ef_search`（默认 100）或改用分区表，属于后续优化 |
| R5 | 中文分词依赖 jieba tokenizer 的词典覆盖 | 生僻词 BM25 召回差 | 向量路兜底；`PARAGRAPH`/`RECURSIVE` 分块让文本块语义更完整 |
| R6 | `summary_nodes` 同时是向量表与 BM25 表，且**只能有一个 bm25 索引** | 无法为 `summary` 列单独建第二个 bm25 索引 | 摘要节点的 `content` 与 `summary` 写入相同文本，`bm25(id, content)` 一个索引即可覆盖两类节点 |
| R7 | 无鉴权（D4 明确） | 任何能访问端口的人可读写全部数据 | 仅本机/内网部署；接口不做暴露公网 |
| R8 | `vector(1536)` 与 embedding 模型强绑定 | 换模型需重建全部向量 | `knowledge_base.embedding_model/dimension` 记录元数据；`CHECK (embedding_dimension = 1536)` 提前阻断误配（实测 1024 维插入会被 DB 直接拒绝） |

---

## 12. 交付物与验收对照

| 交付物 | 状态 |
|---|---|
| `docs/01-architecture.md` | 本文（架构、流转、算法、指标、配置表、ER、DDL 验证） |
| `docs/02-schema.sql` | 已在本机 `doc_raptor_db` 真实执行通过（**160/160，0 报错**），并完成真实读写冒烟测试 |
| `docs/03-api-contract.md` | REST 契约，覆盖 4 个模块全部接口，冻结 |
| 执行证据 | `docs\.ddl-execution-result.txt`（DDL 执行原始输出）、`docs\.ddl-smoke-test-result.txt`（冒烟测试原始输出） |

**给 developer / frontend 的三句话**

1. 表已经在库里了，**不要自己建表**；叶子块 = `summary_nodes` 里 `node_type='LEAF'` 的行，没有单独的 chunk 表。
2. 向量检索用 `ORDER BY embedding <=> ?::vector`；BM25 用 `WHERE id @@@ paradedb.match('content', ?)` + `paradedb.score(id)`（参数绑定写法已实测，别用 LIKE）。
3. 叶子节点 `parent_id` **建树前为 NULL、建树后指向 level=1 摘要**（第 10.5 节的修正），别按"叶子永远没有 parent"去写代码。
