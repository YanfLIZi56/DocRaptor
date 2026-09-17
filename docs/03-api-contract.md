# DocRaptor REST 接口契约（docs/03-api-contract.md）

> **状态：已冻结（FROZEN）**。本文件是前后端共同实现的唯一契约。
> 路径、字段名、枚举值、错误码一经定稿，后端与前端**均不得单方面修改**；如需变更必须有 Lead 批准的契约变更记录（见第 10 节）。
> 版本：v1.0　｜　产出：architect　｜　配套：`docs/01-architecture.md`、`docs/02-schema.sql`

---

## 1. 通用约定

| 项 | 约定 |
|---|---|
| Base Path | 所有接口以 `/api` 开头（见第 4 节清单） |
| 传输格式 | 请求与响应均为 `application/json; charset=UTF-8`；上传接口为 `multipart/form-data` |
| 时间字段 | 所有时间字段为 **毫秒级 Unix 时间戳（number）**，字段名以 `At` 结尾（如 `createdAt`、`finishedAt`） |
| ID 字段 | 所有 ID 为 **UUID 字符串**（36 字符，含连字符），字段名以 `Id` 结尾；多值时为字符串数组 |
| 命名风格 | JSON 字段一律 **lowerCamelCase**（与数据库 snake_case 通过 MyBatis `mapUnderscoreToCamelCase` 映射） |
| 分页 | 入参 `page`（默认 1）、`pageSize`（默认 20，上限 200）；出参 `{list, total, page, pageSize}` |
| 空值 | 无值输出 `null`（不省略字段）；空集合输出 `[]`（不输出 `null`） |
| 认证 | **无**。所有接口直接操作，不需要 token/登录（本项目明确不做权限模块） |
| 幂等 | `GET` 天然幂等；`PUT` 为幂等覆盖；`POST` 非幂等（重复调用会创建新资源或新任务，任务类接口受 `CONFLICT_RUNNING_TASK` 保护） |
| 字符编码 | 请求体与响应体均为 UTF-8；中文查询串直接传原文，无需转义 |

### 1.1 统一响应包装

**所有接口（含出错）都返回 HTTP 200 + 统一包装体**（除网关级错误），前端只判 `code`：

```json
{
  "code": 0,
  "message": "success",
  "data": {}
}
```

| 字段 | 类型 | 必有 | 说明 |
|---|---|---|---|
| `code` | number | 是 | `0` 表示成功；非 0 为错误码（见第 3 节）。**不要用 HTTP status 判成败** |
| `message` | string | 是 | 成功固定 `"success"`；失败为中文/英文错误描述，可直接展示给用户 |
| `data` | object \| array \| null | 是 | 业务数据；无数据时返回 `null` |

> **约定例外（仅 1 处）**：文件上传体积超过 `spring.servlet.multipart` 限制时，由容器在进入 Controller 前拦截，
> 此时返回 HTTP 413 + 本包装体的 `code=41301`（由 `GlobalExceptionHandler` 兜底转换）。

### 1.2 分页响应结构

```json
{
  "code": 0,
  "message": "success",
  "data": { "list": [], "total": 0, "page": 1, "pageSize": 20 }
}
```

### 1.3 异步任务型接口的统一模式

导入、建树、评估等长任务统一为两步：**触发接口立即返回 `taskId` → 前端轮询任务进度接口**。

```json
{ "code": 0, "message": "success", "data": { "taskId": "…", "status": "PENDING" } }
```

轮询：`GET /api/async-tasks/{taskId}` → `data.progress`（0~100）、`data.currentStage`、`data.status`。
建议前端轮询间隔 1000ms，`status` 变为 `SUCCESS` / `PARTIAL_SUCCESS` / `FAILED` / `CANCELED` 时停止轮询。

### 1.4 枚举字典（前后端共用的唯一取值来源）

| 枚举 | 取值 | 说明 |
|---|---|---|
| `nodeType` | `LEAF` / `SUMMARY` | 文本块 / 摘要节点 |
| `chunkStrategy` | `FIXED_SIZE` / `PARAGRAPH` / `RECURSIVE` | 定长滑窗 / 段落聚合 / 递归分隔符 |
| `fileType` | `PDF` / `DOCX` / `MARKDOWN` / `TXT` | 按扩展名映射（`.md`/`.markdown` → `MARKDOWN`） |
| `stepStatus` | `PENDING` / `RUNNING` / `SUCCESS` / `FAILED` / `SKIPPED` | 4 个流水线步骤的状态 |
| `taskType` | `DOC_IMPORT` / `DOC_PARSE` / `DOC_CHUNK` / `DOC_EMBED` / `RAPTOR_BUILD` / `EVAL_RUN` | 异步任务类型 |
| `taskStatus` | `PENDING` / `RUNNING` / `SUCCESS` / `PARTIAL_SUCCESS` / `FAILED` / `CANCELED` | 任务状态 |
| `taskStage` | `PARSE` / `CHUNK` / `EMBED` / `TREE_BUILD` / `EVAL` / `DONE` | 任务当前阶段 |
| `retrievalMode` | `VECTOR` / `BM25` / `HYBRID` | 纯向量 / 纯 BM25 / RRF 混合 |
| `retrievalScope` | `LEAF_ONLY` / `ALL_LEVELS` / `SPECIFIED_LEVEL` | 仅叶子 / 全部层级(折叠树) / 指定层级 |
| `logType` | `SEARCH` / `EVAL` | 检索日志类型 |

> **重要**：`LEAF_ONLY` 只返回 `nodeType=LEAF`；`ALL_LEVELS` 会返回 `LEAF` 与 `SUMMARY` 混合结果并**折叠树**
> （同一条祖先链上只保留最终排名最高的一个节点）；`SPECIFIED_LEVEL` 必须传 `levels`。

---

## 2. 接口总览（21 个）

### 模块1 知识库管理（8 个）

| # | 方法 | 路径 | 说明 |
|---|---|---|---|
| 1 | POST | `/api/knowledge-bases` | 新建知识库（可配置 chunkSize/overlap/strategy） |
| 2 | GET | `/api/knowledge-bases` | 知识库列表（分页） |
| 3 | GET | `/api/knowledge-bases/{id}` | 知识库详情 |
| 4 | PUT | `/api/knowledge-bases/{id}` | 重命名/改描述（**不允许改分块参数**） |
| 5 | DELETE | `/api/knowledge-bases/{id}` | 删除知识库（级联删文档与节点） |
| 6 | POST | `/api/documents/upload` | 文档上传导入（异步） |
| 7 | GET | `/api/documents` | 文档列表（**可按 knowledgeBaseId 筛选**） |
| 8 | GET | `/api/documents/{id}` | 文档详情 |
| 8b | PUT | `/api/documents/{id}/enabled` | **启用/禁用文档**（唯一可写业务字段） |
| 8c | GET | `/api/chunks` | **分块列表查询**（每块显示来源文档 ID、块序号、字符数） |

### 模块2 RAPTOR 树构建（3 个）

| # | 方法 | 路径 | 说明 |
|---|---|---|---|
| 9 | POST | `/api/raptor/trees` | 触发建树（异步，可传 maxLevel 与聚类参数覆盖） |
| 10 | GET | `/api/raptor/trees/{documentId}` | 查询某文档的树结构（嵌套树 + 扁平节点） |
| 11 | GET | `/api/raptor/trees/{documentId}/stats` | 树统计（各层节点数、深度、是否唯一根） |

### 模块3 检索与召回测试（7 个）

| # | 方法 | 路径 | 说明 |
|---|---|---|---|
| 12 | POST | `/api/retrieval/vector` | 纯向量检索 |
| 13 | POST | `/api/retrieval/bm25` | 纯 BM25 检索 |
| 14 | POST | `/api/retrieval/hybrid` | 混合检索（RRF） |
| 15 | GET | `/api/retrieval/logs` | 检索日志查询 |
| 16 | POST | `/api/eval/cases` | 新建评估用例（测试查询 + 期望块 ID） |
| 17 | GET | `/api/eval/cases` | 评估用例列表 |
| 18 | PUT | `/api/eval/cases/{id}` | 修改评估用例（含整体替换期望块） |
| 19 | DELETE | `/api/eval/cases/{id}` | 删除评估用例 |
| 20 | POST | `/api/eval/run` | **召回率评估执行**（Recall@K / Hit Rate@K / MRR） |

### 模块4 基础功能（1 个）

| # | 方法 | 路径 | 说明 |
|---|---|---|---|
| 21 | GET | `/api/async-tasks/{taskId}` | **异步任务进度查询** |
| 21b | GET | `/api/async-tasks` | 任务列表（按文档/知识库筛选） |

> **不存在 `DELETE /api/documents/{id}`**。需求明确"已导入文档不可修改/删除正文，只能禁用"，
> 删除文档只能通过删除整个知识库级联完成。

---

## 3. 统一错误码表

`code` 分段：

| 区间 | 含义 |
|---|---|
| `0` | 成功 |
| `4xxxx` | 客户端错误（参数、资源不存在、状态冲突） |
| `5xxxx` | 服务端错误（LLM/向量/数据库/未知） |

| code | 常量名 | HTTP | message（默认） | 触发场景 |
|---|---|---|---|---|
| `0` | `SUCCESS` | 200 | `success` | 成功 |
| `40001` | `PARAM_INVALID` | 200 | `请求参数不合法：{detail}` | 参数校验失败（含 `SPECIFIED_LEVEL` 未传 levels、`chunkOverlap >= chunkSize`） |
| `40002` | `TOP_K_EXCEEDED` | 200 | `topK 超出上限，最大 100` | `topK > docraptor.retrieval.max-top-k` |
| `40003` | `MODE_INVALID` | 200 | `检索模式不合法，仅支持 VECTOR/BM25/HYBRID` | `mode` 非法 |
| `40004` | `SCOPE_INVALID` | 200 | `检索范围不合法，仅支持 LEAF_ONLY/ALL_LEVELS/SPECIFIED_LEVEL` | `scope` 非法 |
| `40005` | `RATIO_OUT_OF_RANGE` | 200 | `hybridRatio 必须在 [0,1] 区间内` | `hybridRatio`/`bm25Weight`/`similarityThreshold` 越界 |
| `40006` | `RRF_K_OUT_OF_RANGE` | 200 | `rrfK 必须在 [1,1000] 区间内` | `rrfK` 越界 |
| `40007` | `FILE_TYPE_UNSUPPORTED` | 200 | `不支持的文件类型：{ext}，仅支持 pdf/docx/md/markdown/txt` | 扩展名不在白名单 |
| `40008` | `FILE_EMPTY` | 200 | `上传文件为空` | 空文件 |
| `40009` | `CHUNK_PARAM_INVALID` | 200 | `分块参数不合法：overlap 必须小于 chunkSize` | 建库/建树参数不合法 |
| `40010` | `EXPECTED_CHUNK_INVALID` | 200 | `期望块不合法：{nodeId} 不是该知识库下的叶子节点` | 评估用例引用了非 LEAF 或跨库节点 |
| `40011` | `LEVELS_REQUIRED` | 200 | `scope=SPECIFIED_LEVEL 时必须提供 levels` | 缺少 `levels` |
| `41301` | `PAYLOAD_TOO_LARGE` | 413 | `上传文件超过大小限制（10MB）` | multipart 超限 |
| `40400` | `RESOURCE_NOT_FOUND` | 200 | `资源不存在：{type}#{id}` | 知识库/文档/节点/用例/任务不存在 |
| `40401` | `KNOWLEDGE_BASE_NOT_FOUND` | 200 | `知识库不存在` | 指定 knowledgeBaseId 查不到 |
| `40402` | `DOCUMENT_NOT_FOUND` | 200 | `文档不存在` | 指定 documentId 查不到 |
| `40403` | `TASK_NOT_FOUND` | 200 | `异步任务不存在` | taskId 查不到 |
| `40404` | `EVAL_CASE_NOT_FOUND` | 200 | `评估用例不存在` | caseId 查不到 |
| `40901` | `NAME_DUPLICATED` | 200 | `名称已存在：{name}` | 知识库名重复（唯一约束 `uk_knowledge_base_name`） |
| `40902` | `CONFLICT_RUNNING_TASK` | 200 | `该文档已有进行中的任务：{taskId}` | 重复触发同一文档同类任务（唯一索引 `uk_async_task_active_doc`） |
| `40903` | `TREE_ALREADY_EXISTS` | 200 | `该文档已存在构建完成的 RAPTOR 树` | 未传 `forceRebuild=true` 时重复建树 |
| `40904` | `DOCUMENT_DISABLED` | 200 | `文档已禁用，不参与检索` | 检索时 `documentIds` 全部指向已禁用文档 |
| `40905` | `DOCUMENT_NOT_READY` | 200 | `文档尚未完成向量化，无法检索/建树` | `embedStatus != SUCCESS` |
| `40906` | `EVAL_CASE_DISABLED` | 200 | `用例已禁用，不参与评估` | 评估时全部用例 `enabled=false` |
| `50001` | `DOCUMENT_PARSE_FAILED` | 200 | `文档解析失败：{detail}` | Tika 解析异常（损坏/加密文件） |
| `50002` | `EMBEDDING_FAILED` | 200 | `向量化失败：{detail}` | Embedding 接口报错/超限 |
| `50003` | `LLM_FAILED` | 200 | `摘要生成失败：{detail}` | Chat 接口报错/超限 |
| `50004` | `TREE_BUILD_FAILED` | 200 | `RAPTOR 树构建失败：{detail}` | UMAP/GMM/写库异常 |
| `50005` | `DB_ERROR` | 200 | `数据库操作失败` | SQL 异常（含 pgvector 维度不符 `22000`） |
| `50006` | `STORAGE_ERROR` | 200 | `文件存储失败：{detail}` | 落盘失败 |
| `50099` | `INTERNAL_ERROR` | 200 | `服务器内部错误` | 未捕获异常兜底 |

**错误响应示例**

```json
{
  "code": 40902,
  "message": "该文档已有进行中的任务：3f2a9c10-5b7e-4d21-9a3c-1e0f8d7b6a54",
  "data": { "conflictTaskId": "3f2a9c10-5b7e-4d21-9a3c-1e0f8d7b6a54", "status": "RUNNING", "progress": 40 }
}
```

---

## 4. 模块1 知识库管理

### 4.1 新建知识库

`POST /api/knowledge-bases`

**请求体**

| 字段 | 类型 | 必填 | 默认值 | 说明 |
|---|---|---|---|---|
| `name` | string | 是 | — | 知识库名称，1~128 字符，全局唯一 |
| `description` | string | 否 | `null` | 描述，最长 2000 字符 |
| `chunkSize` | number | 否 | `512` | 分块目标字符数，范围 [64, 8192] |
| `chunkOverlap` | number | 否 | `64` | 相邻块重叠字符数，范围 [0, chunkSize)，且必须 < chunkSize |
| `chunkStrategy` | string | 否 | `"FIXED_SIZE"` | `FIXED_SIZE` / `PARAGRAPH` / `RECURSIVE` |

```json
{ "name": "RAPTOR 论文与实现", "description": "RAPTOR 相关资料", "chunkSize": 512, "chunkOverlap": 64, "chunkStrategy": "FIXED_SIZE" }
```

> **分块参数在建库时固化**，之后 `PUT` 不能修改（改参数会让已有文档的块序列与新参数不一致）。
> 需要新参数请新建知识库。

**响应 `data`**

```json
{
  "id": "8f14e45f-ceea-467a-9f9b-2b1d6e1a7c33",
  "name": "RAPTOR 论文与实现",
  "description": "RAPTOR 相关资料",
  "chunkSize": 512,
  "chunkOverlap": 64,
  "chunkStrategy": "FIXED_SIZE",
  "documentCount": 0,
  "nodeCount": 0,
  "embeddingModel": "qwen3.7-text-embedding",
  "embeddingDimension": 1536,
  "createdAt": 1789000000000,
  "updatedAt": 1789000000000
}
```

**错误场景**：`40001`（name 为空或超长）、`40009`（chunkOverlap ≥ chunkSize）、`40901`（名称重复）。

---

### 4.2 知识库列表

`GET /api/knowledge-bases?page=1&pageSize=20&keyword=RAPTOR`

| 参数 | 位置 | 类型 | 必填 | 默认值 | 说明 |
|---|---|---|---|---|---|
| `page` | query | number | 否 | `1` | 页码，从 1 开始 |
| `pageSize` | query | number | 否 | `20` | 每页条数，[1, 200] |
| `keyword` | query | string | 否 | `null` | 按 `name` 模糊匹配（不区分大小写） |

**响应 `data`**：`{list, total, page, pageSize}`，`list` 元素结构同 4.1 的响应对象，额外含 `documentCount` 实时统计。

```json
{
  "code": 0, "message": "success",
  "data": {
    "list": [ { "id": "8f14e45f-…", "name": "RAPTOR 论文与实现", "chunkSize": 512, "chunkOverlap": 64, "chunkStrategy": "FIXED_SIZE",
                "documentCount": 3, "nodeCount": 148, "embeddingModel": "qwen3.7-text-embedding", "embeddingDimension": 1536,
                "createdAt": 1789000000000, "updatedAt": 1789000000000 } ],
    "total": 1, "page": 1, "pageSize": 20
  }
}
```

**错误场景**：`40001`（pageSize 越界）。

---

### 4.3 知识库详情

`GET /api/knowledge-bases/{id}`

**响应 `data`**：同 4.1 响应对象。**错误场景**：`40401`。

---

### 4.4 重命名 / 改描述

`PUT /api/knowledge-bases/{id}`

| 字段 | 类型 | 必填 | 默认值 | 说明 |
|---|---|---|---|---|
| `name` | string | 否 | 保持原值 | 新名称，1~128 字符，唯一 |
| `description` | string | 否 | 保持原值 | 新描述 |

> 传 `null` 表示不改；`description` 传空串 `""` 表示清空。
> **不可修改**：`chunkSize` / `chunkOverlap` / `chunkStrategy`（传入会被忽略，不报错，以保证前端表单兼容）。

**响应 `data`**：同 4.1。**错误场景**：`40401`、`40901`、`40001`。

---

### 4.5 删除知识库

`DELETE /api/knowledge-bases/{id}?confirm=true`

| 参数 | 位置 | 类型 | 必填 | 默认值 | 说明 |
|---|---|---|---|---|---|
| `confirm` | query | boolean | 是 | — | 必须显式传 `true`，否则报 `40001`（防误删） |

**副作用**：级联删除该知识库下所有 `document`、`summary_nodes`、`async_task`、`eval_case`、`eval_case_expected_chunk`；
`retrieval_log.knowledgeBaseId` 置为 `null` 但**日志保留**。

**响应 `data`**

```json
{ "id": "8f14e45f-ceea-467a-9f9b-2b1d6e1a7c33", "deletedDocuments": 3, "deletedNodes": 148, "deletedEvalCases": 2 }
```

**错误场景**：`40401`、`40001`（未传 confirm）。

---

### 4.6 文档上传导入

`POST /api/documents/upload` （`multipart/form-data`）

| 字段 | 类型 | 必填 | 默认值 | 说明 |
|---|---|---|---|---|
| `file` | file | 是 | — | 单个文件，≤10MB；扩展名 ∈ {pdf, docx, md, markdown, txt} |
| `knowledgeBaseId` | string | 是 | — | 目标知识库 ID |
| `buildTree` | boolean | 否 | `true` | 导入完成后是否自动建 RAPTOR 树（等价于兜底执行模块2） |
| `maxLevel` | number | 否 | `3` | 自动建树时的深度上限，[1, 10] |

**响应 `data`**

```json
{
  "documentId": "5c2b1a90-7d3e-4f88-b001-6a9d2e4c8f11",
  "taskId": "3f2a9c10-5b7e-4d21-9a3c-1e0f8d7b6a54",
  "fileName": "raptor-paper.pdf",
  "fileType": "PDF",
  "fileSize": 2048576,
  "status": "PENDING"
}
```

前端随后轮询 `GET /api/async-tasks/{taskId}` 展示进度（`progress` / `currentStage` / `progressMessage`）。

**错误场景**：`40007`（类型不支持）、`40008`（空文件）、`40401`（知识库不存在）、`41301`（超 10MB）、`50006`（落盘失败）、`40001`。

---

### 4.7 文档列表

`GET /api/documents?knowledgeBaseId=…&page=1&pageSize=20&enabled=true&keyword=raptor&treeStatus=SUCCESS`

| 参数 | 位置 | 类型 | 必填 | 默认值 | 说明 |
|---|---|---|---|---|---|
| `knowledgeBaseId` | query | string | 否 | `null` | **按知识库筛选**；不传则返回全部知识库的文档 |
| `page` | query | number | 否 | `1` | 页码 |
| `pageSize` | query | number | 否 | `20` | [1, 200] |
| `enabled` | query | boolean | 否 | `null` | `true` 只看启用；`false` 只看禁用；不传全部 |
| `keyword` | query | string | 否 | `null` | 按 `fileName` 模糊匹配 |
| `treeStatus` | query | string | 否 | `null` | 按建树状态过滤（`stepStatus` 枚举） |

**响应 `data.list[]`**

```json
{
  "id": "5c2b1a90-7d3e-4f88-b001-6a9d2e4c8f11",
  "knowledgeBaseId": "8f14e45f-ceea-467a-9f9b-2b1d6e1a7c33",
  "knowledgeBaseName": "RAPTOR 论文与实现",
  "fileName": "raptor-paper.pdf",
  "fileType": "PDF",
  "fileSize": 2048576,
  "charCount": 48210,
  "chunkCount": 96,
  "enabled": true,
  "parseStatus": "SUCCESS",
  "chunkStatus": "SUCCESS",
  "embedStatus": "SUCCESS",
  "treeStatus": "SUCCESS",
  "parseError": null,
  "metadata": { "title": "RAPTOR: Recursive Abstractive Processing", "pageCount": 12 },
  "createdAt": 1789000000000,
  "updatedAt": 1789000300000
}
```

**错误场景**：`40401`（knowledgeBaseId 不存在）、`40001`。

---

### 4.8 文档详情 & 启用/禁用文档

**详情**：`GET /api/documents/{id}` → `data` 同 4.7 的 list 元素。**错误场景**：`40402`。

**启用/禁用**：`PUT /api/documents/{id}/enabled`

| 字段 | 类型 | 必填 | 默认值 | 说明 |
|---|---|---|---|---|
| `enabled` | boolean | 是 | — | `false` 禁用（不参与检索，数据完整保留）；`true` 恢复参与检索 |

```json
{ "enabled": false }
```

**响应 `data`**

```json
{ "id": "5c2b1a90-7d3e-4f88-b001-6a9d2e4c8f11", "enabled": false, "updatedAt": 1789000500000 }
```

**语义（前端必须如此展示）**：
- 禁用是**可逆**的软操作：`summary_nodes`、向量、树结构全部保留；重新启用后立刻可检索。
- 禁用后，该文档的全部节点在**向量路与 BM25 路都被过滤掉**（检索 SQL 强制 `d.enabled = true`）。
- **没有接口可以修改或删除文档正文/文本块**，这是需求约束，不是遗漏。

**错误场景**：`40402`、`40001`（`enabled` 缺失）。

---

### 4.9 分块列表查询

`GET /api/chunks?knowledgeBaseId=…&documentId=…&page=1&pageSize=20`

**每块必须显示：来源文档 ID（`documentId`）、块序号（`chunkIndex`）、字符数（`charCount`）。**

| 参数 | 位置 | 类型 | 必填 | 默认值 | 说明 |
|---|---|---|---|---|---|
| `knowledgeBaseId` | query | string | 否 | `null` | 按知识库筛选（与 documentId 同时传时以 documentId 为准） |
| `documentId` | query | string | 否 | `null` | 指定文档的全部文本块 |
| `page` | query | number | 否 | `1` | 页码 |
| `pageSize` | query | number | 否 | `20` | [1, 200] |
| `withContent` | query | boolean | 否 | `true` | `false` 时 `content` 只返回前 120 字符（列表页省流量） |
| `withEmbedding` | query | boolean | 否 | `false` | `true` 时额外返回 `embeddingDimension` 与向量前 8 维预览（调试用） |

**响应 `data.list[]`**（固定 `nodeType=LEAF`，按 `documentId, chunkIndex` 升序）

```json
{
  "nodeId": "a1b2c3d4-0001-4000-8000-000000000001",
  "documentId": "5c2b1a90-7d3e-4f88-b001-6a9d2e4c8f11",
  "documentName": "raptor-paper.pdf",
  "knowledgeBaseId": "8f14e45f-ceea-467a-9f9b-2b1d6e1a7c33",
  "nodeType": "LEAF",
  "level": 0,
  "chunkIndex": 0,
  "startChunkIndex": 0,
  "endChunkIndex": 0,
  "charCount": 512,
  "tokenCount": null,
  "parentId": "b2c3d4e5-0100-4000-8000-000000000100",
  "hasEmbedding": true,
  "embeddingDimension": 1536,
  "embeddingPreview": null,
  "content": "RAPTOR 引入了一种递归的树结构检索方法……",
  "createdAt": 1789000100000
}
```

> `parentId` 在建树前为 `null`，建树后为所属 `level=1` 摘要节点 ID（详见 `01-architecture.md` 第 10.5 节）。

**错误场景**：`40402`（documentId 不存在）、`40401`、`40001`。

---

## 5. 模块2 RAPTOR 树构建

### 5.1 触发建树（异步）

`POST /api/raptor/trees`

| 字段 | 类型 | 必填 | 默认值 | 说明 |
|---|---|---|---|---|
| `documentId` | string | 是 | — | 目标文档；其 `embedStatus` 必须为 `SUCCESS` |
| `maxLevel` | number | 否 | `3` | **树深上限，默认 L3**，范围 [1, 10] |
| `forceRebuild` | boolean | 否 | `false` | `true` 时删除已有 SUMMARY 节点并重建（叶子块与向量不重建）；`false` 时若树已存在报 `40903` |
| `umapNNeighbors` | number | 否 | `10` | UMAP `n_neighbors` 覆盖值，[2, 100] |
| `umapMinDist` | number | 否 | `0.1` | UMAP `min_dist` 覆盖值，[0.0, 1.0] |
| `gmmMaxClusters` | number | 否 | `8` | GMM 聚类数量上限覆盖值，[2, 64] |
| `gmmCovarianceType` | string | 否 | `null` | `full` / `tied` / `diagonal` / `spherical`；`null` 用配置默认 |
| `summaryPrompt` | string | 否 | `null` | 覆盖摘要 Prompt 模板（调试用）；`null` 用配置的正式模板。**无论怎么覆盖，平台都会在末尾追加 5.3 的硬约束句** |

> 3 个聚类参数（`umapNNeighbors` / `umapMinDist` / `gmmMaxClusters`）**均为可配置项**，
> 未传时取 `application.yml` 的 `docraptor.raptor.*` 默认值，实际使用值写入 `summary_nodes.metadata` 以便追溯。

```json
{ "documentId": "5c2b1a90-7d3e-4f88-b001-6a9d2e4c8f11", "maxLevel": 3, "forceRebuild": false,
  "umapNNeighbors": 10, "umapMinDist": 0.1, "gmmMaxClusters": 8 }
```

**响应 `data`**

```json
{ "taskId": "9a8b7c60-1234-4abc-9def-0123456789ab", "documentId": "5c2b1a90-…", "status": "PENDING" }
```

**错误场景**：`40402`、`40905`（`embedStatus != SUCCESS`）、`40902`（已有进行中任务）、`40903`（树已存在且未 forceRebuild）、`40001`（参数越界）。

---

### 5.2 查询树结构

`GET /api/raptor/trees/{documentId}?format=nested`

| 参数 | 位置 | 类型 | 必填 | 默认值 | 说明 |
|---|---|---|---|---|---|
| `format` | query | string | 否 | `nested` | `nested`（嵌套 children 树，前端画树）/ `flat`（扁平节点数组 + parentId，前端自组装） |
| `withContent` | query | boolean | 否 | `true` | `false` 时 `content` 截断到 200 字符 |
| `includeLeaves` | query | boolean | 否 | `true` | `false` 时只返回 `SUMMARY` 节点（只看摘要骨架） |

**响应 `data`（`format=nested`）**

```json
{
  "documentId": "5c2b1a90-7d3e-4f88-b001-6a9d2e4c8f11",
  "documentName": "raptor-paper.pdf",
  "knowledgeBaseId": "8f14e45f-ceea-467a-9f9b-2b1d6e1a7c33",
  "maxLevel": 3,
  "actualDepth": 2,
  "rootNodeId": "c3d4e5f6-0300-4000-8000-000000000300",
  "nodeCount": 96,
  "summaryNodeCount": 14,
  "leafNodeCount": 96,
  "builtAt": 1789000300000,
  "root": {
    "nodeId": "c3d4e5f6-0300-4000-8000-000000000300",
    "nodeType": "SUMMARY",
    "level": 2,
    "parentId": null,
    "startChunkIndex": 0,
    "endChunkIndex": 95,
    "charCount": 386,
    "summary": "本文提出 RAPTOR……",
    "content": "本文提出 RAPTOR……",
    "clusterLabel": 0,
    "clusterSize": 3,
    "childCount": 3,
    "documentId": "5c2b1a90-7d3e-4f88-b001-6a9d2e4c8f11",
    "hasEmbedding": true,
    "metadata": { "buildId": "b7f1…", "isRoot": true, "umap": { "nNeighbors": 10, "minDist": 0.1 },
                  "gmm": { "nComponents": 3, "covarianceType": "diagonal" } },
    "children": [
      {
        "nodeId": "b2c3d4e5-0200-4000-8000-000000000200",
        "nodeType": "SUMMARY", "level": 1, "parentId": "c3d4e5f6-0300-4000-8000-000000000300",
        "startChunkIndex": 0, "endChunkIndex": 47, "charCount": 402,
        "summary": "该簇讨论 RAPTOR 的整体流程……", "content": "该簇讨论 RAPTOR 的整体流程……",
        "clusterLabel": 1, "clusterSize": 4, "childCount": 4, "hasEmbedding": true, "metadata": {},
        "children": [
          { "nodeId": "a1b2c3d4-0001-4000-8000-000000000001", "nodeType": "LEAF", "level": 0,
            "parentId": "b2c3d4e5-0200-4000-8000-000000000200", "chunkIndex": 0,
            "startChunkIndex": 0, "endChunkIndex": 0, "charCount": 512,
            "summary": null, "content": "RAPTOR 引入了一种递归的树结构检索方法……",
            "clusterLabel": 1, "clusterSize": 4, "childCount": 0, "hasEmbedding": true, "metadata": {}, "children": [] }
        ]
      }
    ]
  }
}
```

**响应 `data`（`format=flat`）**：把 `root` 换成 `nodes` 数组，元素为上面各节点对象（去掉 `children` 字段），`parentId` 保留用于自组装。

**错误场景**：`40402`（文档不存在）、`40400`（该文档尚未建树，`data=null` 且 `code=40400`，前端提示"未建树"）。

---

### 5.3 树统计

`GET /api/raptor/trees/{documentId}/stats`

**响应 `data`**

```json
{
  "documentId": "5c2b1a90-7d3e-4f88-b001-6a9d2e4c8f11",
  "treeStatus": "SUCCESS",
  "actualDepth": 2,
  "maxLevel": 3,
  "rootCount": 1,
  "hasUniqueRoot": true,
  "levelCounts": [ { "level": 0, "count": 96 }, { "level": 1, "count": 12 }, { "level": 2, "count": 2 } ],
  "avgClusterSize": 6.86,
  "degradedSummaryCount": 0,
  "forcedRoot": false,
  "builtAt": 1789000300000,
  "buildDurationMs": 84210
}
```

> `hasUniqueRoot=false` 说明建树未收敛（异常态，通常由 `maxLevel` 过小或聚类退化导致），前端应提示用户用更大的 `maxLevel` + `forceRebuild=true` 重建。
> `degradedSummaryCount` 为 LLM 摘要重试失败后降级为原文拼接的节点数（见 `01-architecture.md` 5.3）。

**错误场景**：`40402`。

---

## 6. 模块3 检索与召回测试

### 6.1 三个检索接口与请求体

三个接口路径不同（便于前端路由与日志区分 `mode`），**请求体结构完全一致**：

| 接口 | 方法 | 路径 | 强制 mode |
|---|---|---|---|
| 纯向量 | POST | `/api/retrieval/vector` | `VECTOR`（请求体传 `mode` 会被忽略） |
| 纯 BM25 | POST | `/api/retrieval/bm25` | `BM25`（忽略 `hybridRatio` / `rrfK` / `similarityThreshold`） |
| 混合 RRF | POST | `/api/retrieval/hybrid` | `HYBRID` |

**请求体**

| 字段 | 类型 | 必填 | 默认值 | 说明 |
|---|---|---|---|---|
| `knowledgeBaseId` | string | 是 | — | 检索范围所在知识库 |
| `query` | string | 是 | — | 查询原文，1~2000 字符 |
| `topK` | number | 否 | `10` | 返回条数，[1, 100]，超过 100 报 `40002` |
| `similarityThreshold` | number | 否 | `0.0` | **仅作用于向量路**的硬过滤阈值，[0.0, 1.0]；`vectorRawScore < 该值` 的候选被丢弃 |
| `hybridRatio` | number | 否 | `0.5` | **仅 HYBRID**：向量路权重 `w_vector`，[0.0, 1.0]；`w_bm25 = (1 - hybridRatio) × bm25Weight` |
| `bm25Weight` | number | 否 | `1.0` | **仅 HYBRID**：BM25 路整体缩放系数，[0.0, 10.0] |
| `rrfK` | number | 否 | `60` | **仅 HYBRID**：RRF 平滑常数 k，[1, 1000] |
| `scope` | string | 否 | `"ALL_LEVELS"` | `LEAF_ONLY` / `ALL_LEVELS` / `SPECIFIED_LEVEL` |
| `levels` | number[] | 否 | `null` | `scope=SPECIFIED_LEVEL` 时**必填**，如 `[1,2]`；元素 ∈ [0,10]；为空或未传报 `40011` |
| `documentIds` | string[] | 否 | `null` | 限定在这些文档内检索；`null` 表示不限。传了但对应文档全部被禁用 → `40904` |
| `withContent` | boolean | 否 | `true` | `false` 时 `content` 截断到 200 字符 |
| `withScoreBreakdown` | boolean | 否 | `true` | 是否返回 `scoreBreakdown` 各路明细 |

```json
{
  "knowledgeBaseId": "8f14e45f-ceea-467a-9f9b-2b1d6e1a7c33",
  "query": "RAPTOR 如何用 UMAP 和 GMM 构建摘要树？",
  "topK": 10,
  "similarityThreshold": 0.0,
  "hybridRatio": 0.5,
  "bm25Weight": 1.0,
  "rrfK": 60,
  "scope": "ALL_LEVELS",
  "levels": null,
  "documentIds": null,
  "withContent": true,
  "withScoreBreakdown": true
}
```

**响应 `data`（三个接口结构一致）**

```json
{
  "query": "RAPTOR 如何用 UMAP 和 GMM 构建摘要树？",
  "mode": "HYBRID",
  "knowledgeBaseId": "8f14e45f-ceea-467a-9f9b-2b1d6e1a7c33",
  "scope": "ALL_LEVELS",
  "params": { "topK": 10, "similarityThreshold": 0.0, "hybridRatio": 0.5, "bm25Weight": 1.0, "rrfK": 60, "levels": null, "documentIds": null },
  "costMs": 42,
  "costBreakdown": { "embeddingMs": 18, "vectorMs": 9, "bm25Ms": 12, "fusionMs": 2, "totalMs": 42 },
  "totalHits": 7,
  "collapsedCount": 3,
  "truncatedByThreshold": 1,
  "hits": [
    {
      "finalRank": 1,
      "finalScore": 0.032786,
      "nodeId": "b2c3d4e5-0200-4000-8000-000000000200",
      "nodeType": "SUMMARY",
      "level": 1,
      "documentId": "5c2b1a90-7d3e-4f88-b001-6a9d2e4c8f11",
      "documentName": "raptor-paper.pdf",
      "documentEnabled": true,
      "chunkIndex": null,
      "startChunkIndex": 0,
      "endChunkIndex": 47,
      "charCount": 402,
      "content": "该簇讨论 RAPTOR 的整体流程：先对文本块做 UMAP 降维……",
      "collapsedByNodeId": null,
      "scoreBreakdown": {
        "vectorRawScore": 0.8123,
        "vectorRank": 2,
        "vectorWeightedScore": 0.008064,
        "bm25RawScore": 1.0542,
        "bm25Rank": 1,
        "bm25WeightedScore": 0.008197,
        "rrfK": 60
      }
    },
    {
      "finalRank": 2,
      "finalScore": 0.024193,
      "nodeId": "a1b2c3d4-0001-4000-8000-000000000001",
      "nodeType": "LEAF",
      "level": 0,
      "documentId": "5c2b1a90-7d3e-4f88-b001-6a9d2e4c8f11",
      "documentName": "raptor-paper.pdf",
      "documentEnabled": true,
      "chunkIndex": 0,
      "startChunkIndex": 0,
      "endChunkIndex": 0,
      "charCount": 512,
      "content": "RAPTOR 引入了一种递归的树结构检索方法……",
      "collapsedByNodeId": null,
      "scoreBreakdown": {
        "vectorRawScore": 0.7901,
        "vectorRank": 3,
        "vectorWeightedScore": 0.007936,
        "bm25RawScore": 0.9456,
        "bm25Rank": 2,
        "bm25WeightedScore": 0.007352,
        "rrfK": 60
      }
    }
  ]
}
```

**字段语义要点（前端展示口径）**

| 字段 | 说明 |
|---|---|
| `finalScore` | 融合后最终分：`VECTOR` 模式 = `vectorRawScore`；`BM25` 模式 = `bm25RawScore`；`HYBRID` = RRF 分（公式见 `01-architecture.md` 6.2） |
| `scoreBreakdown.vectorRank` / `bm25Rank` | **各路原始排名**（1 起）；该路未召回时为 `null` |
| `scoreBreakdown.*RawScore` | **各路原始分数**；未召回为 `null` |
| `scoreBreakdown.*WeightedScore` | `w_r × 1/(rrfK + rank)`，即该路对 `finalScore` 的实际贡献；`VECTOR`/`BM25` 模式下为 `null` |
| `collapsedByNodeId` | 非空表示该结果被"折叠树"策略折叠掉（同链只留最优），指向保留的节点；折叠掉的条目**不会出现在 `hits` 里**，仅作调试计数，用 `collapsedCount` 汇总 |
| `truncatedByThreshold` | 被 `similarityThreshold` 过滤掉的候选条数（仅向量路） |
| `costBreakdown` | 便于验证性能；`BM25` 模式 `embeddingMs` 为 `0` |

**错误场景**

| code | 场景 |
|---|---|
| `40001` | `query` 为空或超 2000 字符、`knowledgeBaseId` 缺失 |
| `40002` | `topK > 100` |
| `40004` | `scope` 非法 |
| `40005` | `hybridRatio` / `bm25Weight` / `similarityThreshold` 越界 |
| `40006` | `rrfK` 越界 |
| `40011` | `scope=SPECIFIED_LEVEL` 但未传 `levels` |
| `40401` | 知识库不存在 |
| `40904` | `documentIds` 指定的文档全部已禁用 |
| `50002` | `query` 向量化失败（LLM/Embedding 服务异常） |
| `50005` | 向量维度不符等数据库错误 |

---

### 6.2 检索日志查询

`GET /api/retrieval/logs?knowledgeBaseId=…&mode=HYBRID&logType=SEARCH&startTime=…&endTime=…&page=1&pageSize=20`

| 参数 | 位置 | 类型 | 必填 | 默认值 | 说明 |
|---|---|---|---|---|---|
| `knowledgeBaseId` | query | string | 否 | `null` | 按知识库过滤 |
| `mode` | query | string | 否 | `null` | `VECTOR` / `BM25` / `HYBRID` / `EVAL` |
| `logType` | query | string | 否 | `null` | `SEARCH` / `EVAL` |
| `queryKeyword` | query | string | 否 | `null` | 按查询原文模糊匹配 |
| `startTime` | query | number | 否 | `null` | 起始时间（毫秒时间戳，含） |
| `endTime` | query | number | 否 | `null` | 结束时间（毫秒时间戳，含） |
| `page` / `pageSize` | query | number | 否 | `1` / `20` | 分页 |

**响应 `data.list[]`**

```json
{
  "id": "d4e5f6a7-0400-4000-8000-000000000400",
  "knowledgeBaseId": "8f14e45f-…",
  "logType": "SEARCH",
  "queryText": "RAPTOR 如何用 UMAP 和 GMM 构建摘要树？",
  "mode": "HYBRID",
  "params": { "topK": 10, "similarityThreshold": 0.0, "hybridRatio": 0.5, "bm25Weight": 1.0, "rrfK": 60, "scope": "ALL_LEVELS", "levels": null, "documentIds": null },
  "resultCount": 7,
  "resultNodeIds": ["b2c3d4e5-…", "a1b2c3d4-…"],
  "latencyMs": 42,
  "latencyBreakdown": { "vectorMs": 9, "bm25Ms": 12, "fusionMs": 2 },
  "success": true,
  "errorMessage": null,
  "createdAt": 1789000600000
}
```

**错误场景**：`40001`（时间区间非法）。

---

### 6.3 新建评估用例

`POST /api/eval/cases`

| 字段 | 类型 | 必填 | 默认值 | 说明 |
|---|---|---|---|---|
| `knowledgeBaseId` | string | 是 | — | 所属知识库 |
| `name` | string | 是 | — | 用例名，1~128 字符，便于报告识别 |
| `queryText` | string | 是 | — | 测试查询原文 |
| `expectedChunkIds` | string[] | 是 | — | **期望命中的块 ID 数组**（`summary_nodes.id`，必须是该知识库下的 `nodeType=LEAF` 节点），至少 1 个 |
| `remark` | string | 否 | `null` | 备注 |

```json
{
  "knowledgeBaseId": "8f14e45f-ceea-467a-9f9b-2b1d6e1a7c33",
  "name": "RAPTOR 聚类原理",
  "queryText": "RAPTOR 如何用 UMAP 和 GMM 构建摘要树？",
  "expectedChunkIds": ["a1b2c3d4-0001-4000-8000-000000000001", "a1b2c3d4-0001-4000-8000-000000000002"],
  "remark": "对应论文第 3 节"
}
```

**响应 `data`**

```json
{
  "id": "e5f6a7b8-0500-4000-8000-000000000500",
  "knowledgeBaseId": "8f14e45f-…",
  "name": "RAPTOR 聚类原理",
  "queryText": "RAPTOR 如何用 UMAP 和 GMM 构建摘要树？",
  "remark": "对应论文第 3 节",
  "enabled": true,
  "expectedChunks": [
    { "nodeId": "a1b2c3d4-0001-4000-8000-000000000001", "documentId": "5c2b1a90-…", "chunkIndex": 0, "relevance": 1 },
    { "nodeId": "a1b2c3d4-0001-4000-8000-000000000002", "documentId": "5c2b1a90-…", "chunkIndex": 1, "relevance": 1 }
  ],
  "lastRecallAtK": null,
  "lastMrr": null,
  "lastEvaluatedAt": null,
  "createdAt": 1789000700000
}
```

**错误场景**：`40401`、`40010`（某 `expectedChunkIds` 元素不是该知识库下的 LEAF 节点）、`40001`（`expectedChunkIds` 为空）、`40901`（同库下用例名重复）。

---

### 6.4 评估用例列表 / 修改 / 删除

- `GET /api/eval/cases?knowledgeBaseId=…&enabled=true&page=1&pageSize=20`
  → `data.list[]` 同 6.3 响应对象（`expectedChunks` 一并返回，便于前端直接编辑）。错误场景：`40401`、`40001`。
- `PUT /api/eval/cases/{id}`
  → 请求体字段同 6.3（`knowledgeBaseId` 不可改，传入忽略）；`expectedChunkIds` 传 `null` 表示不改，传数组表示**整体替换**。
  错误场景：`40404`、`40010`、`40001`。
- `DELETE /api/eval/cases/{id}` → `data: { "id": "…", "deleted": true }`。错误场景：`40404`。

---

### 6.5 召回率评估执行

`POST /api/eval/run`

| 字段 | 类型 | 必填 | 默认值 | 说明 |
|---|---|---|---|---|
| `knowledgeBaseId` | string | 是 | — | 评估范围 |
| `caseIds` | string[] | 否 | `null` | 只跑指定用例；`null` 表示跑该知识库下全部 `enabled=true` 的用例 |
| `topK` | number | 否 | `10` | **实际召回深度**，[1, 100] |
| `kList` | number[] | 否 | `[1,3,5,10]` | 要计算的 K 列表；元素必须 ≤ `topK`，超过的会被裁剪并在 `truncatedKList` 中说明 |
| `mode` | string | 否 | `"HYBRID"` | 参与评估的检索模式：`VECTOR` / `BM25` / `HYBRID` |
| `similarityThreshold` | number | 否 | `0.0` | 同 6.1 |
| `hybridRatio` | number | 否 | `0.5` | 同 6.1 |
| `bm25Weight` | number | 否 | `1.0` | 同 6.1 |
| `rrfK` | number | 否 | `60` | 同 6.1 |
| `scope` | string | 否 | `"ALL_LEVELS"` | 同 6.1 |
| `levels` | number[] | 否 | `null` | 同 6.1 |
| `async` | boolean | 否 | `false` | `false`：同步执行并直接返回指标（用例少时推荐）；`true`：返回 `taskId`，异步执行，进度查 `/api/async-tasks/{taskId}`，结果在 `data.result` 中 |

```json
{
  "knowledgeBaseId": "8f14e45f-ceea-467a-9f9b-2b1d6e1a7c33",
  "caseIds": null,
  "topK": 10,
  "kList": [1, 3, 5, 10],
  "mode": "HYBRID",
  "similarityThreshold": 0.0,
  "hybridRatio": 0.5,
  "bm25Weight": 1.0,
  "rrfK": 60,
  "scope": "ALL_LEVELS",
  "levels": null,
  "async": false
}
```

**响应 `data`（同步模式；异步模式为 `{taskId, status}`）**

```json
{
  "knowledgeBaseId": "8f14e45f-ceea-467a-9f9b-2b1d6e1a7c33",
  "mode": "HYBRID",
  "topK": 10,
  "params": { "similarityThreshold": 0.0, "hybridRatio": 0.5, "bm25Weight": 1.0, "rrfK": 60, "scope": "ALL_LEVELS", "levels": null },
  "evaluatedCases": 2,
  "skippedCases": [
    { "caseId": "e5f6a7b8-0501-4000-8000-000000000501", "name": "空期望块用例", "reason": "EXPECTED_EMPTY" }
  ],
  "truncatedKList": [10],
  "metrics": [
    { "k": 1,  "recall": 0.2500, "hitRate": 0.5000, "mrr": 0.5000 },
    { "k": 3,  "recall": 0.5000, "hitRate": 1.0000, "mrr": 0.6667 },
    { "k": 5,  "recall": 0.7500, "hitRate": 1.0000, "mrr": 0.6667 },
    { "k": 10, "recall": 1.0000, "hitRate": 1.0000, "mrr": 0.6667 }
  ],
  "avgLatencyMs": 45,
  "retrievalLogIds": ["d4e5f6a7-0400-4000-8000-000000000400", "d4e5f6a7-0401-4000-8000-000000000401"],
  "perQuery": [
    {
      "caseId": "e5f6a7b8-0500-4000-8000-000000000500",
      "name": "RAPTOR 聚类原理",
      "query": "RAPTOR 如何用 UMAP 和 GMM 构建摘要树？",
      "expectedChunkIds": ["a1b2c3d4-0001-4000-8000-000000000001", "a1b2c3d4-0001-4000-8000-000000000002"],
      "retrievedNodeIds": ["b2c3d4e5-…", "a1b2c3d4-0001-…", "a1b2c3d4-0002-…"],
      "hitNodeIds": ["a1b2c3d4-0001-…", "a1b2c3d4-0002-…"],
      "missedNodeIds": [],
      "firstHitRank": 2,
      "metrics": [
        { "k": 1,  "recall": 0.0,    "hitRate": 0, "reciprocalRank": 0.0 },
        { "k": 3,  "recall": 1.0,    "hitRate": 1, "reciprocalRank": 0.5 },
        { "k": 5,  "recall": 1.0,    "hitRate": 1, "reciprocalRank": 0.5 },
        { "k": 10, "recall": 1.0,    "hitRate": 1, "reciprocalRank": 0.5 }
      ],
      "latencyMs": 43,
      "retrievalLogId": "d4e5f6a7-0400-4000-8000-000000000400"
    }
  ]
}
```

**指标口径（前后端必须一致，公式详见 `01-architecture.md` 第 7 节）**

```
Recall_q@K      = |{ i ∈ [1,K] : retrieved[i] ∈ expected }| / |expected|
HitRate_q@K     = 1 若至少命中一个期望块，否则 0
RR_q            = 命中的最小排名；无命中记 0        MRR_q = 1 / RR_q
数据集级指标     = 对未跳过用例求算术平均
```

- 命中判定为**严格口径**：必须命中期望的**叶子块 ID** 本身；命中其父摘要节点**不算**命中。
- `metrics[].k` 即为 K；`mrr` 字段是数据集级 MRR（对 `k=topK` 口径计算，与 `k` 无关，此处统一放在数组元素里便于前端成表格）。
- 每次评估同时落 `retrieval_log`（`logType=EVAL`），`retrievalLogIds` 用于追溯。

**错误场景**：`40401`、`40002`（`topK > 100`）、`40005` / `40006`（参数越界）、`40011`、`40906`（所有用例都被禁用 / `caseIds` 全无效）、`50002`。

---

## 7. 模块4 异步任务进度查询

### 7.1 任务详情 / 进度

`GET /api/async-tasks/{taskId}`

**响应 `data`**

```json
{
  "taskId": "3f2a9c10-5b7e-4d21-9a3c-1e0f8d7b6a54",
  "taskType": "DOC_IMPORT",
  "status": "RUNNING",
  "progress": 55,
  "currentStage": "EMBED",
  "progressMessage": "已向量化 53/96 块",
  "knowledgeBaseId": "8f14e45f-ceea-467a-9f9b-2b1d6e1a7c33",
  "documentId": "5c2b1a90-7d3e-4f88-b001-6a9d2e4c8f11",
  "documentName": "raptor-paper.pdf",
  "retryCount": 0,
  "errorMessage": null,
  "payload": { "chunkSize": 512, "chunkOverlap": 64, "chunkStrategy": "FIXED_SIZE", "maxLevel": 3, "autoBuildTree": true },
  "result": null,
  "createdAt": 1789000100000,
  "startedAt": 1789000102000,
  "finishedAt": null,
  "heartbeatAt": 1789000140000
}
```

**`result` 在各任务类型下的结构**

| `taskType` | `result` 字段 |
|---|---|
| `DOC_IMPORT` / `DOC_PARSE` / `DOC_CHUNK` / `DOC_EMBED` | `{ "documentId": "…", "charCount": 48210, "chunkCount": 96, "embeddedCount": 96, "durationMs": 42800 }` |
| `RAPTOR_BUILD` | `{ "documentId": "…", "rootNodeId": "…", "maxLevel": 2, "summaryNodeCount": 14, "actualDepth": 2, "hasUniqueRoot": true, "degradedSummaryCount": 0, "durationMs": 84210 }` |
| `EVAL_RUN` | 与 6.5 同步模式响应的 `data` 完全一致（含 `metrics`、`perQuery`） |

**前端进度条建议映射（`progress` 已按此权重写库，直接用作百分比即可）**

| 阶段 | `currentStage` | `progress` 区间 |
|---|---|---|
| 解析 | `PARSE` | 0 ~ 15 |
| 分块 | `CHUNK` | 15 ~ 35 |
| 向量化 | `EMBED` | 35 ~ 70 |
| 建树 | `TREE_BUILD` | 70 ~ 100 |
| 完成 | `DONE` | 100 |

**错误场景**：`40403`（taskId 不存在）。

---

### 7.2 任务列表

`GET /api/async-tasks?documentId=…&knowledgeBaseId=…&status=RUNNING&taskType=DOC_IMPORT&page=1&pageSize=20`

| 参数 | 类型 | 必填 | 默认值 | 说明 |
|---|---|---|---|---|
| `documentId` | string | 否 | `null` | 按文档筛选 |
| `knowledgeBaseId` | string | 否 | `null` | 按知识库筛选 |
| `status` | string | 否 | `null` | `taskStatus` 枚举值 |
| `taskType` | string | 否 | `null` | `taskType` 枚举值 |
| `page` / `pageSize` | number | 否 | `1` / `20` | 分页 |

**响应 `data`**：`{list, total, page, pageSize}`，`list` 元素同 7.1。**错误场景**：`40001`。

---

## 8. 前端实现要点（避免歧义）

1. **判成败只看 `code`**：HTTP 状态码几乎总是 200（除 413），不要用 `response.status` 判断。
2. **异步任务**：上传/建树的返回值里 `taskId` 是唯一进度来源，轮询 `GET /api/async-tasks/{taskId}`；`progress` 可直接绑定进度条。
3. **文档禁用**：`PUT /api/documents/{id}/enabled`。禁用后文档仍在列表中（用 `enabled=false` 徽标标记），**没有删除按钮**。
4. **块列表**：用 `GET /api/chunks?documentId=…`；每行显示 `documentId`、`chunkIndex`、`charCount` 三列（需求明确要求）。
5. **检索结果表格列**：`finalRank`、`level`、`nodeType`、`documentName`、`finalScore`、`scoreBreakdown.vectorRank` / `vectorRawScore`、`scoreBreakdown.bm25Rank` / `bm25RawScore`。`ALL_LEVELS` 下会有 `SUMMARY` 行，用不同底色区分。
6. **参数控件范围**：`topK` 1~100（步进 1）；`similarityThreshold` 0~1（步进 0.01）；`hybridRatio` 0~1（步进 0.05，标注"向量权重"）；`rrfK` 1~1000（默认 60）；`scope` 为单选，选 `SPECIFIED_LEVEL` 时才显示 `levels` 多选（0~maxLevel）。
7. **`levels` 与 `scope` 的联动校验在前端先做**：`scope=SPECIFIED_LEVEL` 且 `levels` 为空时直接拦住，不要发请求（后端会报 `40011`）。
8. **评估报告**：`metrics` 数组直接渲染成 Recall@K / Hit Rate@K / MRR 表格（K 为行）；`perQuery` 用于"未命中用例"清单。

---

## 9. 契约自查清单（冻结前已逐项核对）

| 需求 | 对应接口 | 覆盖 |
|---|---|---|
| 新建知识库（可配置 chunkSize/overlap/strategy） | 4.1 | ✅ |
| 知识库列表 / 重命名 / 删除 | 4.2 / 4.4 / 4.5 | ✅ |
| 文档上传导入（PDF/DOCX/Markdown/TXT） | 4.6 | ✅ |
| 文档列表（可按知识库筛选） | 4.7 | ✅ |
| 查看某文档的所有文本块（文档 ID、块序号、字符数） | 4.9 | ✅ |
| 分块后自动向量化 | 4.6（`DOC_IMPORT` 流水线内含 EMBED 阶段）；4.7 的 `embedStatus` 可观测 | ✅ |
| UMAP + GMM 聚类、LLM 逐簇摘要、递归至根、树深上限 L3 | 5.1（`maxLevel` 默认 3 + 3 个聚类参数可调）、5.2、5.3 | ✅ |
| 每层记录 level / parent_id / 覆盖块范围 / 摘要 / 向量 | 5.2 响应含 `level`/`parentId`/`startChunkIndex`/`endChunkIndex`/`summary`/`hasEmbedding`；`withEmbedding=true` 时 4.9 给向量预览 | ✅ |
| 三种检索模式 | 6.1（三个接口） | ✅ |
| 可调 topK / similarityThreshold / hybridRatio / rrfK(默认 60) | 6.1 请求体 | ✅ |
| 检索范围：仅叶子 / 全部层级(折叠树) / 指定层级 | 6.1 `scope` + `levels` | ✅ |
| 结果展示内容、层级、来源文档、各路原始排名与分数 | 6.1 `hits[]` + `scoreBreakdown` | ✅ |
| 召回测试：测试查询 + 期望块 ID | 6.3 / 6.4 | ✅ |
| Recall@K / Hit Rate@K / MRR | 6.5 | ✅ |
| 导入/分块/向量化/建树异步任务 + 进度查询 | 4.6 + 7.1 / 7.2 | ✅ |
| 检索日志（查询、模式、参数、耗时） | 6.1 自动落库 + 6.2 查询接口 | ✅ |
| 文档不可改删正文，只能禁用 | 4.8（唯一的写接口）；**无** DELETE/改正文接口 | ✅ |
| 无用户权限模块 | 全文档无 auth 相关字段与接口 | ✅ |

---

## 10. 变更记录（冻结后任何修改必须登记）

| 版本 | 日期 | 变更 | 影响面 | 批准 |
|---|---|---|---|---|
| v1.0 | 2026-09-17 | 初版冻结：21 个接口、错误码表、枚举字典 | 后端 / 前端 | architect |

**变更规则**：新增/修改路径、请求字段名、响应字段名、枚举取值、错误码，必须在本表追加一行并注明影响面；
仅"新增可选请求字段"属于向后兼容变更，可直接追加版本号；删除或重命名字段属于破坏性变更，必须同步通知前后端。
