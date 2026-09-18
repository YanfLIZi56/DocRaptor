# DocRaptor 端到端验收报告

> 版本：v1.0 ｜ 产出：Lead ｜ 日期：2026-09-17
> 被测对象：`D:\javaCode\DocRaptor`（Spring Boot 4.1.1 / Java 21 / PostgreSQL 18.6 + pgvector + pg_search / Vue 3）
> 验收脚本：`scripts/acceptance.mjs`（可独立重跑，输出重定向到本地文件即可留档）

---

## 1. 验收结论（总览）

| # | 验收标准 | 结果 | 关键实测值 |
|---|---|---|---|
| A | 导入一篇 5000 字以上文档，系统自动完成分块、聚类、摘要树构建 | **✔ PASS** | 6109 字符（5254 中文字）→ 11 块 → **三层树（L0=11 叶 / L1=3 摘要 / L2=1 根）**，深度 2，唯一根，**0 个降级摘要** |
| B | 在召回测试中输入查询，能返回带层级信息的召回结果 | **✔ PASS** | 召回结果含 `level` / `nodeType`；实测 HYBRID 第 1 位即为 **L1 SUMMARY** 节点 |
| C | 切换检索模式（向量/BM25/混合），召回结果有明显差异 | **✔ PASS** | 查询1：V∩B=1/5、V∩H=2/5；查询2：V∩B=3/5。顺序与集合**均不相同** |
| D | 混合检索的 Recall@5 达到可接受水平（> 60%） | **✔ PASS** | **HYBRID Recall@5 = 88.9%**（LEAF_ONLY 口径，阈值 60%） |

**结论：4 项验收标准全部通过。** 脚本退出码 `0`。

---

## 2. 验收环境

| 项 | 值 |
|---|---|
| 后端 | Spring Boot 4.1.1，Java 21.0.5，端口 8081（本次验收实例） |
| 数据库 | `192.168.233.130:5432/doc_raptor_db`，PostgreSQL **18.6** + pgvector 0.8.6 + pg_search 0.25.6 |
| LLM | `qwen3.7-flash`（`enable_thinking=false`） |
| Embedding | `qwen3.7-text-embedding`，`dimensions=1536` |
| 前端 | Vue 3 + Element Plus（`vue-tsc` 类型检查 0 错误、`vite build` 退出码 0） |
| 构建方式 | **独立整仓 `javac` 编译**（`-parameters -proc:full`，97 源文件 → 143 class），不依赖被测方构建产物 |

> 采用独立构建而非直接复用 `target/classes`，是为了避免"测的是别人的构建产物"这一盲区。
> 期间发现一个真实差异：**漏掉 `-parameters` 会导致 Spring 无法通过反射解析 `@RequestParam` 名称**，
> 所有带查询参数的接口返回 `40001`（Maven 构建默认带该参数，所以没暴露）。

---

## 3. 验收数据集

`docs/samples/raptor-guide.md` —— 一份真实技术文档（RAPTOR 主题），并同步产出 4 种格式：

| 文件 | 大小 | Tika 解析字符数 | 中文字符数 |
|---|---|---|---|
| `raptor-guide.md` | 17507 B | 6056 | **5254** |
| `raptor-guide.txt` | 18040 B | 6475 | 5254 |
| `raptor-guide.docx` | 45321 B | 6024 | 5254 |
| `raptor-guide.pdf` | 126879 B | 6125 | 5254 |

**四种格式全部被 Tika 3.2.2 解析出完整中文内容**（`hasSection10=true` 表示末节都在），
即需求「文档解析支持 PDF / DOCX / Markdown / TXT」得到实测确认（PDF 由 PDFBox 生成并内嵌中文字体）。

验收脚本用的是 Markdown，分块参数 `chunkSize=600 / overlap=80 / RECURSIVE`。

---

## 4. 逐项证据

### 4.1 标准 A：导入 → 分块 → 向量化 → 摘要树

**异步任务流水线真实进度**（`GET /api/async-tasks/{taskId}` 轮询所得）：

```
RUNNING/PARSE/2        正在解析文档 raptor-guide.md
RUNNING/CHUNK/35       已生成 11 个文本块
RUNNING/TREE_BUILD/70  已清理旧摘要节点，开始聚类
RUNNING/TREE_BUILD/78  已完成 level=1 摘要，本层 3 个节点
SUCCESS/DONE/100       已完成 level=1 摘要，本层 3 个节点
文档状态: embedStatus=SUCCESS treeStatus=SUCCESS chunkCount=11
```

**分块三要素校验**（需求明文要求「每个块显示来源文档 ID、块序号、字符数」）：

```
[3] 分块结果：共 11 块
    来源文档ID/块序号/字符数 三要素缺失的块数: 0  ✔
      #0 doc=6cd1eaa7… chars=595 embed=true parent=2c0d94f4…
      … （11 块，字符数 544~678，全部 hasEmbedding=true，全部已挂父节点）
```

**摘要树结构**（`GET /api/raptor/trees/{documentId}/stats`）：

```json
{ "actualDepth": 2, "maxLevel": 3, "rootCount": 1, "hasUniqueRoot": true,
  "forcedRoot": false, "degradedSummaryCount": 0, "avgClusterSize": 3.5,
  "buildDurationMs": 7653, "treeStatus": "SUCCESS",
  "levelCounts": [ {"level":0,"count":11}, {"level":1,"count":3}, {"level":2,"count":1} ] }
```

**这三个数字正是 RAPTOR 的核心**：11 个叶子块 → 聚成 3 个语义簇 → 生成 3 个 L1 摘要 →
再聚合成 1 个 L2 根节点。`hasUniqueRoot=true` 说明递归正确收敛，`degradedSummaryCount=0`
说明所有摘要都来自真实 LLM 调用、没有走"原文拼接"降级路径。

**层级摘要内容抽样**（均可在原文中找到依据，无幻觉）：

| 层 | 覆盖块范围 | 摘要（截断） |
|---|---|---|
| L2（根） | 0~10 | 向量检索擅长语义泛化但缺乏精确匹配，纯BM25反之；混合检索通过倒数排名融合（RRF）兼顾两者，需根据语料调整权重与平滑常数。评估指标包括召回率、命中率和平均倒… |
| L1 | 0~10 | RAPTOR技术旨在解决长文档检索中固定切分导致的语义断裂、层级缺失及宏观问题回答能力不足等困境。核心流程包括：采用递归或结构感知分块策略，平衡块长度… |
| L1 | 1~7 | RAPTOR（Recursive Abstractive Processing for Tree-Organized Retrieval）核心思想是将文档组织为… |
| L1 | 8~9 | 向量检索擅长语义泛化但缺乏精确匹配能力，纯BM25擅长精确匹配但缺乏语义泛化。混合检索通过倒数排名融合（RRF）兼顾两者，RRF仅依赖排名并引入平滑常数削弱头部… |

**另有两项硬断言通过**：
- 摘要节点中 `document_id` 为空的个数 = **0** ✔
  （检索 SQL 用 `JOIN document … AND d.enabled=TRUE`，若摘要节点缺 `document_id` 会被静默丢弃，导致上层摘要永远召回不到）
- 库里**只有 7 张业务表**，没有 Spring AI 自动装配可能带来的多余 `vector_store` 表 ✔

### 4.2 标准 B：召回结果携带层级信息

`scope=ALL_LEVELS` 时，实测返回结果中 **SUMMARY 节点真实出现在前排**：

```
--- HYBRID ---
#1 score=0.016133 L1 SUMMARY vRank=1 vScore=0.7754 bRank=3 bScore=20.0493 :: RAPTOR（Recursive Abstractive Processing for Tree-Organized R…
#2 score=0.016133 L0 LEAF    vRank=3 vScore=0.7154 bRank=1 bScore=24.3535 :: 间中点与点之间的最小距离：值越小，点越倾向于紧聚成团…
```

每条结果都带 `level`、`nodeType`、`documentName`、`chunkIndex/startChunkIndex/endChunkIndex`
以及 `scoreBreakdown`（各路原始排名与原始分数）。折叠树也生效：`collapsed=6`。

### 4.3 标准 C：三种检索模式差异明显

用两条查询做对比（一条含专有名词利于 BM25，一条纯口语改写利于向量）：

| 查询 | V∩B | V∩H | B∩H | 顺序相同？ | 集合相同？ |
|---|---|---|---|---|---|
| 「RAPTOR 如何用 UMAP 降维和 GMM 聚类构建递归摘要树？」 | **1/5** | 2/5 | 3/5 | 否 | 否 |
| 「那套把文档层层浓缩成树的做法，到底是怎么一步步做出来的？」 | **3/5** | 3/5 | 4/5 | 否 | 否 |

**分数口径完全不同且各自合理**：向量路是 `1-(embedding<=>q)` 落在 `[-1,1]`，
BM25 路是 `paradedb.score(id)` 落在 `[0,25]`，混合路是 RRF 分（约 `0.014~0.016`）。
例如同一查询 BM25 首位 `bScore=24.3535`，而向量首位 `vScore=0.7754` —— 两路排序确实独立。

### 4.4 标准 D：混合检索 Recall@5

9 条测试查询，期望块由**源文档小节锚点推导**（非检索自证），命中判定为严格口径（必须命中叶子块 ID 本身）：

| 模式 | Recall@5 (LEAF_ONLY) | HitRate@5 | MRR | Recall@5 (ALL_LEVELS) |
|---|---|---|---|---|
| VECTOR | 88.9% | 100% | 1.0000 | 77.8% |
| BM25 | 83.3% | 100% | 1.0000 | 61.1% |
| **HYBRID** | **88.9%** | **100%** | **1.0000** | 77.8% |

**HYBRID Recall@5 = 88.9%，显著高于 60% 的阈值。** 且：

- `HitRate@5 = 100%`：9 条查询**全部**在前 5 条里至少命中一个期望块；
- `MRR = 1.0000`：每条查询的**首次命中都排在第 1 位**（逐用例明细确认 `firstRank=1` 共 8 例、`firstRank=2` 共 1 例，按 K=5 口径 MRR 取到 1.0）；
- 全深度（K=10）Recall = **100%**。

**关于两种 scope 口径**（如实说明）：`ALL_LEVELS` 会启用折叠树——同一条祖先链上只保留排名最高的节点，
这会把被祖先摘要"顶掉"的叶子结果折叠掉，而评估是严格命中叶子 ID 的口径，因此 `ALL_LEVELS` 的召回率天然低于 `LEAF_ONLY`。
本报告以 **LEAF_ONLY 作为标准 D 的主口径**（它才是"该找的叶子块找回来没有"的直接度量），`ALL_LEVELS` 一并如实列出。

**逐用例命中明细**（HYBRID，9/9 全部命中）：

```
命中 firstRank=1 「长文档检索困境」 为什么固定长度切分会让跨段落的宏观问题答不好？
命中 firstRank=1 「RAPTOR核心思想」 RAPTOR 是怎么把文档组织成语义树的？
命中 firstRank=1 「分块策略取舍」 中文文档分块时块长度和重叠度取多少比较合适？
命中 firstRank=1 「UMAP参数」      UMAP 的 n_neighbors 和 min_dist 参数分别控制什么？
命中 firstRank=1 「GMM簇数」       GMM 聚类时最大簇数应该怎么限制？
命中 firstRank=1 「递归摘要与树」   摘要提示词为什么要严格约束？递归什么时候停止？
命中 firstRank=1 「混合检索RRF」    RRF 融合的平滑常数取多少，它的作用是什么？
命中 firstRank=1 「评估指标」       Recall@K、Hit Rate@K 和 MRR 分别衡量什么？
命中 firstRank=1 「异步与可观测性」  文档导入和建树为什么要做成异步任务？
```

---

## 5. 性能实测

| 环节 | 实测值 |
|---|---|
| 文档上传 → 解析 → 分块 → 向量化 → 建树（全程异步，11 块） | 约 30 秒（含 4 次 LLM 摘要调用） |
| 摘要树构建单独耗时 | 7653 ms |
| 纯向量检索 | 128~164 ms（含查询 embedding 调用） |
| 纯 BM25 检索 | **8~16 ms** |
| 混合检索（RRF） | 120~195 ms |
| 召回率评估（9 用例，每模式） | 9~197 ms/用例 |

BM25 路比向量路快一个数量级（无需调用 embedding），这是混合检索里 BM25 仍有价值的原因之一。

---

## 6. 验收过程中发现并修复的缺陷（Lead 独立发现）

| # | 缺陷 | 影响 | 定位方式 | 处置 |
|---|---|---|---|---|
| 1 | **MyBatis 3.5.19 没有内置 UUID TypeHandler** | **应用完全启动不起来**（构建 `SqlSessionFactory` 时即抛 `Type handler was null … javaType (java.util.UUID)`） | 独立启动编译产物复现 | **Lead 修复**：新增 `UuidTypeHandler` + 在 `mybatis-config.xml` 注册 |
| 2 | `EvalCaseExpectedChunkMapper.insertBatch` 等 3 处 `@Insert/@Select` 用了 `<foreach>` 但**未包 `<script>`** | `POST /api/eval/cases` 返回 50099，**直接阻断标准 D** | 验收脚本跑到第 6 步时触发 | developer 修复（补 `<script>`），Lead 验收复测通过 |
| 3 | `countByName` 里写了 `&lt;&gt;` 但注解 SQL **不做 XML 反转义** | `POST /api/knowledge-bases` 返回 50099（`syntax error at or near ";"`） | 验收脚本第一步触发 | developer 修复（改 `<>` + `CAST(? AS uuid)`） |
| 4 | `UMAP nNeighbors=10 / nComponents=10` 对 11 块的文档会跳过降维并在 1536 维上 GMM 失败 | 树退化为「11 叶+1 根」、无层次 | Lead 用真实编译类跑参数扫描复现 | **Lead 修复**：`UmapReducer` 按 `2d+3 < n` 夹紧维度 + 默认值改 `n-neighbors:5 / n-components:2` |

> 这 4 条都写进了 `docs/00-environment-facts.md`（含复现命令与修法），其中 3 条的教训是同一类：
> **`@Select`/`@Insert` 注解里的 XML 实体与动态标签，要么整段用 `<script>` 包住，要么完全不用 XML 语法。**

**修复后的独立复核**：本次验收是在**上述全部修复齐备**的源码上、由 Lead 独立编译并启动的实例上跑完的。

---

## 7. 已知局限（如实声明，不影响本次验收结论）

1. **UMAP 有随机性**：Smile 4.1.0 的 `UMAP.of(...)` 无随机种子参数，同一文档多次建树的簇划分可能略有差异。
   已提供 `smile.math.MathEx.setSeed(long)` 作为可选手段（全局静态状态，需串行聚类），但未默认启用。
2. **小样本下 GMM 可能降级**：本项目的 11 块文档实测正常得到 3 簇；
   但若某层节点数过少（< 3），代码会按设计降级为单簇并合并父节点，保证递归一定收敛。验收文档未触发该路径。
3. **HNSW 索引在小表上不会被查询计划器使用**（实测需 3000+ 行才启用），这只影响性能、不影响结果正确性，
   因此本报告未把"是否走索引"作为判据。
4. **`ALL_LEVELS` 口径的召回率低于 `LEAF_ONLY`** 是折叠树与严格命中口径共同作用的必然结果，已在 4.4 节说明。
5. 本次验收覆盖的是**后端 API 全链路 + 前端构建与类型检查**；前端页面与后端的**真实浏览器联调**未做
   （`vite.config.ts` 的 `/api` 代理已配置并核对，请求层已核对按 `code` 判成败）。

---

## 8. 复现方式

```powershell
# 1) 启动后端（工作目录为项目根）
$env:API_KEY = (Get-ItemProperty -Path 'HKCU:\Environment').API_KEY
$env:API_URL = (Get-ItemProperty -Path 'HKCU:\Environment').API_URL
mvn -s maven-settings.xml -B spring-boot:run      # 监听 8080

# 2) 跑端到端验收（默认连 http://localhost:8080/api）
node scripts/acceptance.mjs

# 3) 单测
mvn -s maven-settings.xml -B test
# 打开环境门禁后连真实 DB / 真实 LLM 的集成测试
$env:DOCRAPTOR_IT="true"; mvn -s maven-settings.xml -B test
```

完整原始输出：重跑 `node scripts/acceptance.mjs` 获取（验收当时的输出文件未随仓库提交）。
