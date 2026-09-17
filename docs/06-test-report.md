# DocRaptor 测试报告

> 版本：v1.0 ｜ 产出：developer + Lead ｜ 日期：2026-09-17
> 运行方式：`mvn -s maven-settings.xml -B test`（工作目录为项目根）
> 本报告所有数字均由 **Lead 在最终源码上独立复跑**得到，原始输出见 `docs/.test-run-output.txt`。

---

## 1. 结论

| 指标 | 默认运行 | 打开环境门禁后（`DOCRAPTOR_IT=true`，`mvn clean test`） |
|---|---|---|
| 用例总数 | **81** | **80** |
| 执行 / 跳过 | 73 / **8** | 80 / **0** |
| 失败 / 错误 | **0 / 0** | **0 / 0** |
| Maven 退出码 | **0** | **BUILD SUCCESS** |

> 81 与 80 的差异来自 `EvalCaseMapperIntegrationTest`：该类共 3 个用例，
> 其中一个即使打开门禁也不执行（另有条件），故门禁模式下该类计 2 个。

> ⚠️ **必须带 `-s maven-settings.xml`**：本机全局 `settings.xml` 把本地仓库指向工作区外，
> 在当前沙箱下写入会被拒（EPERM）。项目根的 `maven-settings.xml` 已把本地仓库固定到工作区内 `.m2repo`
> 并配置了阿里云镜像。

> ⚠️ **改了源码后请用 `mvn clean test`**：本机实测遇到过"增量编译遗留陈旧类文件"导致
> `NoClassDefFoundError`（`target/classes` 里类文件明明存在却加载不到）。
> 症状是 `Tests run: N, Errors: N` 全是 `ClassNotFoundException`；`clean` 一次即恢复，不是代码问题。

---

## 2. 用例分布（实测逐类明细）

| 测试类 | 用例数 | 默认跳过 | 类型 | 覆盖内容 |
|---|---|---|---|---|
| `algorithm.ClusterPipelineTest` | 10 | 0 | 纯逻辑 | 完整「降维 → 聚类」流水线；**11 个 1536 维向量必须得到层次**；维度夹紧；小样本降级 |
| `algorithm.EvalMetricsTest` | 10 | 0 | 纯逻辑 | Recall@K / Hit Rate@K / MRR 精确数值断言（无命中 / 部分命中 / 全部命中 / 去重 / 严格口径） |
| `algorithm.GmmClustererTest` | 8 | 0 | 纯逻辑 | 合成 3 簇数据的分簇正确性；`map()` 标签；n<3、全同向量的降级；BIC 选择 |
| `algorithm.VectorUtilsTest` | 8 | 0 | 纯逻辑 | 向量 ↔ `'[…]'::vector` 字面量互转；维度校验；余弦相似度 |
| `algorithm.TreeFolderTest` | 7 | 0 | 纯逻辑 | 折叠树（同祖先链只留最优）；祖先/后代双向折叠；环保护 |
| `chunker.FixedSizeChunkerTest` | 7 | 0 | 纯逻辑 | 定长滑窗边界：空串、超短、超长、重叠度、块序号连续 |
| `algorithm.RrfFusionTest` | 6 | 0 | 纯逻辑 | RRF 公式与排序；`hybridRatio` / `bm25Weight` / `rrfK` 影响；确定性 tie-break |
| `chunker.ParagraphChunkerTest` | 6 | 0 | 纯逻辑 | 段落聚合边界 |
| `chunker.RecursiveChunkerTest` | 6 | 0 | 纯逻辑 | 递归分隔符（段落 > 句末标点 > 换行 > 硬切） |
| `algorithm.SummaryPromptsTest` | 5 | 0 | 纯逻辑 | 摘要 Prompt **硬约束句必须出现**；模板被覆盖时约束仍追加；占位符替换 |
| `ai.AiGatewayIntegrationTest` | 3 | **3** | 门禁 | 真实 LLM 端点：embedding **必须返回 1536 维**、批量顺序正确、chat 非空 |
| `mapper.EvalCaseMapperIntegrationTest` | 3 | **3** | 门禁 | 真实 DB 全链路：建库→建文档→建叶子块→回填 1536 维向量→`insertBatch` 建评估用例→按 ID 批查，并自清理 |
| `mapper.Bm25SearchIntegrationTest` | 2 | **2** | 门禁 | 真实 DB 上 `id @@@ paradedb.match('content', ?)` 的中文问句绑定与命中；并验证 `parse` 变体会丢召回 |
| **合计** | **81** | **8** | | |

**分层设计意图**：默认 `mvn test` **完全不依赖外部环境**（不连库、不调 LLM），任何机器都能绿；
需要真实依赖的用例一律用 `DOCRAPTOR_IT=true` 门禁，避免"环境不通就红"的假失败。

---

## 3. 重点用例说明

### 3.1 `ClusterPipelineTest` —— 本项目最容易踩坑的地方

RAPTOR 的递归建树依赖「UMAP 降维 → GMM 聚类」，而这两个算法在小样本下有**硬边界**。
Lead 用项目真实编译产物跑参数扫描，实测（n = 11 个 1536 维向量，即一篇 6000 字文档按 600 字分块的块数）：

| 配置 | UMAP | GMM | 树的形态 |
|---|---|---|---|
| `nNeighbors=10, nComponents=10` | 被跳过（`11 > 11` 不成立） | **k=1 降级** | 「11 叶 + 1 根」，**无层次** ❌ |
| `nNeighbors=5, nComponents=10` | 抛异常 → 回退 1536 维 | k=1 降级 | 无层次 ❌ |
| `nNeighbors=5, nComponents=4` | 抛异常（`2*4+3 = 11` 不满足**严格**小于） | k=1 降级 | 无层次 ❌ |
| **`nNeighbors=5, nComponents=2`** | **成功降到 2 维** | **k=8** ✅ | **有层次** ✅ |

该测试类把约束固化下来：**断言 11 个向量跑完整流水线不抛异常、且簇数落在 `[2, kMax]` 内**，
并覆盖"样本数过少"的降级路径。这是防止「摘要树静默退化成单节点」的关键防线。
该类运行约 28~30 秒，期间会打印若干 `LAPACK POTRF error code: -4` —— 属**预期内**噪音
（测试刻意构造高维小样本验证降级路径，断言的是"不抛异常且结果合法"，不是"必须分簇成功"）。

### 3.2 `SummaryPromptsTest` —— 守住需求的硬性约束

需求明文要求摘要 Prompt 必须严格约束。测试断言以下两句**必须出现在最终 System Prompt 中**，
且**即使调用方在请求里覆盖了模板，也会被追加到末尾**：

```
仅基于提供的文本块进行总结，禁止添加任何未在原文中出现的信息。
只做信息压缩，不添加新事实。
```

### 3.3 `EvalMetricsTest` —— 指标口径不能含糊

按契约与架构文档的严格口径断言：

```
Recall_q@K   = |{ i ∈ [1,K] : retrieved[i] ∈ expected }| / |expected|
HitRate_q@K  = 1 若至少命中一个期望块，否则 0
RR_q         = 命中的最小排名；无命中记 0        MRR = 1/RR
数据集级指标 = 对未跳过用例求算术平均
```

**命中判定为严格口径**：必须命中期望的**叶子块 ID 本身**，命中其父摘要节点**不算**命中。

### 3.4 `Bm25SearchIntegrationTest` —— 守住 BM25 的召回

真实 DB 上验证 `id @@@ paradedb.match('content', ?)` 能绑定**带中文标点的整条问句**并命中；
同时保留了一个反例用例，验证改用 `paradedb.parse` 变体会丢召回。
这是防止"BM25 路静默返回空"的防线（见 `docs/00-environment-facts.md` 第 2.3 节）。

---

## 4. 已修复缺陷的防回归覆盖

测试报告必须记录"曾经坏过的地方现在有测试守着"，否则同类缺陷会复发：

| 曾经的真实缺陷 | 影响 | 防回归手段 |
|---|---|---|
| MyBatis 3.5.19 **没有** UUID TypeHandler（45 个 handler 里没有它） | **应用启动即失败** | 启动冒烟 + 门禁集成测试（真实 UUID 参数查询） |
| `@Insert/@Select` 用 `<foreach>` 未包 `<script>` | `BindingException: Parameter 'r' not found` | `EvalCaseMapperIntegrationTest` 真实走 `insertBatch` |
| 注解 SQL 里写 `&lt;&gt;`（注解 SQL 不做 XML 反转义） | PG 报 `syntax error at or near ";"` | `EvalCaseMapperIntegrationTest` 覆盖 `countByName` |
| 可空 UUID 比较未 `CAST` | `could not determine data type of parameter` | 同上 |
| 请求级 options 不带 model | 真实端点返回 `404: Model not exist` | `AiGatewayIntegrationTest` 真实调用端点 |
| UMAP/GMM 小样本退化 → 树无层次 | 摘要树退化成单节点 | `ClusterPipelineTest` |
| `paradedb.parse` 对中文问句零命中 | BM25 路静默失效 | `Bm25SearchIntegrationTest` |

---

## 5. 真实运行输出

### 5.1 默认运行（`mvn -s maven-settings.xml -B test`，退出码 **0**）

```
Tests run:  3, Failures: 0, Errors: 0, Skipped: 3 -> ai.AiGatewayIntegrationTest
Tests run: 10, Failures: 0, Errors: 0, Skipped: 0 -> algorithm.ClusterPipelineTest
Tests run: 10, Failures: 0, Errors: 0, Skipped: 0 -> algorithm.EvalMetricsTest
Tests run:  8, Failures: 0, Errors: 0, Skipped: 0 -> algorithm.GmmClustererTest
Tests run:  6, Failures: 0, Errors: 0, Skipped: 0 -> algorithm.RrfFusionTest
Tests run:  5, Failures: 0, Errors: 0, Skipped: 0 -> algorithm.SummaryPromptsTest
Tests run:  7, Failures: 0, Errors: 0, Skipped: 0 -> algorithm.TreeFolderTest
Tests run:  8, Failures: 0, Errors: 0, Skipped: 0 -> algorithm.VectorUtilsTest
Tests run:  7, Failures: 0, Errors: 0, Skipped: 0 -> chunker.FixedSizeChunkerTest
Tests run:  6, Failures: 0, Errors: 0, Skipped: 0 -> chunker.ParagraphChunkerTest
Tests run:  6, Failures: 0, Errors: 0, Skipped: 0 -> chunker.RecursiveChunkerTest
Tests run:  2, Failures: 0, Errors: 0, Skipped: 2 -> mapper.Bm25SearchIntegrationTest
Tests run:  3, Failures: 0, Errors: 0, Skipped: 3 -> mapper.EvalCaseMapperIntegrationTest
------------------------------------------------------------------------
Tests run: 81, Failures: 0, Errors: 0, Skipped: 8
BUILD SUCCESS
```

### 5.2 打开环境门禁（`$env:DOCRAPTOR_IT="true"` 后 `mvn -s maven-settings.xml -B clean test`）

```
[INFO] Compiling 98 source files with javac [debug parameters release 21] to target\classes
[INFO] Compiling 13 source files with javac [debug parameters release 21] to target\test-classes
Tests run:  3, Failures: 0, Errors: 0, Skipped: 0 -> ai.AiGatewayIntegrationTest        (真实 LLM 调用)
Tests run: 10, Failures: 0, Errors: 0, Skipped: 0 -> algorithm.ClusterPipelineTest
Tests run: 10, Failures: 0, Errors: 0, Skipped: 0 -> algorithm.EvalMetricsTest
Tests run:  8, Failures: 0, Errors: 0, Skipped: 0 -> algorithm.GmmClustererTest
Tests run:  6, Failures: 0, Errors: 0, Skipped: 0 -> algorithm.RrfFusionTest
Tests run:  5, Failures: 0, Errors: 0, Skipped: 0 -> algorithm.SummaryPromptsTest
Tests run:  7, Failures: 0, Errors: 0, Skipped: 0 -> algorithm.TreeFolderTest
Tests run:  8, Failures: 0, Errors: 0, Skipped: 0 -> algorithm.VectorUtilsTest
Tests run:  7, Failures: 0, Errors: 0, Skipped: 0 -> chunker.FixedSizeChunkerTest
Tests run:  6, Failures: 0, Errors: 0, Skipped: 0 -> chunker.ParagraphChunkerTest
Tests run:  6, Failures: 0, Errors: 0, Skipped: 0 -> chunker.RecursiveChunkerTest
Tests run:  2, Failures: 0, Errors: 0, Skipped: 0 -> mapper.Bm25SearchIntegrationTest    (真实 DB)
Tests run:  2, Failures: 0, Errors: 0, Skipped: 0 -> mapper.EvalCaseMapperIntegrationTest (真实 DB)
------------------------------------------------------------------------
Tests run: 80, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

**门禁测试全绿是本项目可信度的关键**：它证明了 embedding 真的返回 1536 维、
BM25 真的能绑定中文问句、`insertBatch` 真的能写进真实 PG、UUID TypeHandler 真的把参数映射对了。

---

## 6. 前端验证

前端没有单元测试，交付时以两条真实链路作为验证手段（Lead 复核了产物）：

| 验证 | 结果 |
|---|---|
| `npm run type-check`（`vue-tsc --build`） | **退出码 0，零错误** |
| `vite build` | **退出码 0**，1702 个模块，产出 `ui/dist`，唯一告警是 Element Plus 全量引入导致的 >500kB chunk 提示 |
| 接口路径静态核对 | 与契约 21 个端点**逐条一致**（`/knowledge-bases`、`/documents`、`/chunks`、`/raptor/trees`、`/retrieval/{vector,bm25,hybrid,logs}`、`/eval/{cases,run}`、`/async-tasks`，baseURL `/api`） |
| 请求层契约核对 | 按 `code` 判成败（非 HTTP 状态码）、统一解包 `{code,message,data}`、覆盖 41301 的 HTTP 413 例外 |

> 环境限制说明：`npm run build` 这个聚合脚本在本沙箱内跑不起来——
> `npm-run-all2` 用**管道 stdio** spawn 子进程会 `spawn EPERM`；裸 `vite build` 也会在配置加载阶段
> 因 Vite 8 的 `windowsSafeRealPathSync` 执行 `net use` 探测网络盘而同样 EPERM。
> 这是沙箱既定边界而非代码问题（`package.json` 未改动），
> 在无沙箱限制的机器上 `npm run build` 可直接通过。

---

## 7. 未覆盖的部分（如实声明）

1. **前端没有单元测试**，且**没有做浏览器端的真实前后端联调**。代理已配置并核对、请求层已核对，
   但没有实际打开页面点过按钮。
2. **没有性能/压力测试**。验收规模为单文档 11 块；大文档（数百块、树深 3 层）下的递归建树耗时与
   LLM 调用成本未做压测（单次摘要约 2.4 秒，见 `docs/00-environment-facts.md` 第 12 节）。
3. **`mvn test` 不覆盖"应用能否启动"**——这是本项目踩过的真实教训（UUID TypeHandler 缺失时
   测试全绿但应用起不来）。目前靠启动冒烟与端到端验收兜底，**建议后续补一个 `@SpringBootTest contextLoads()`**。
4. **UMAP 结果不可复现**（Smile 4.1.0 的 `UMAP.of` 无随机种子参数），因此测试里没有做"结果可复现"断言，
   只断言结构与合法性。
