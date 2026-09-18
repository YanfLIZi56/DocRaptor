# DocRaptor

<p align="center">
  <img src="https://img.shields.io/badge/Java-21-blue?logo=openjdk" alt="Java">
  <img src="https://img.shields.io/badge/Spring%20Boot-4.1-green?logo=spring" alt="Spring Boot">
  <img src="https://img.shields.io/badge/Vue-3-42b883?logo=vuedotjs" alt="Vue 3">
  <img src="https://img.shields.io/badge/PostgreSQL+%20pgvector-18.6-336791?logo=postgresql" alt="PostgreSQL">
  <img src="https://img.shields.io/badge/License-MIT-yellow" alt="License">
</p>

> 🚀 基于 RAPTOR 风格的文档检索系统，支持层级树结构组织

DocRaptor 是一个本地单机运行的 **RAPTOR (Recursive Abstractive Processing for Tree-Organized Retrieval)** 实现。通过文档解析、分块、向量化、降维、高斯混合聚类和 LLM 递归摘要，将文档转化为语义树结构。

> 📝 **注意**: 本项目是 [RAPTOR: Recursive Abstractive Processing for Tree-Organized Retrieval](https://arxiv.org/abs/2401.18059) (Sarthi et al., ICLR 2024) 的**非官方社区重实现**，
> 使用 Java 从零重构，并增加了额外工程工作（混合检索、召回率评估、Web UI）。
> **与原始作者或微软研究院无关联**。
>
> **参考实现**：论文作者的原始代码是 [parthsarthi03/raptor](https://github.com/parthsarthi03/raptor)（Python，MIT）。
> DocRaptor 的聚类环节**遵循该实现的语义** —— 两级聚类（全局 → 每个全局簇内局部）、
> 软聚类（`责任度 > 阈值`）、按簇规模触发的递归细分、以及 `n_neighbors = int(sqrt(n-1))`。
> 有意保留的差异见[聚类算法](#-聚类算法)一节。

## ✨ 功能特性

- 📄 **多格式文档支持** — PDF、DOCX、Markdown、TXT（基于 Apache Tika）
- 🌳 **层级树结构** — RAPTOR 树，支持多级摘要
- 🔍 **混合检索** — 向量检索 (pgvector HNSW) + BM25 全文检索 (ParadeDB) + RRF 融合
- 📊 **召回率评估** — 内置指标：Recall@K、Hit Rate@K、MRR
- 🎯 **灵活检索模式** — VECTOR / BM25 / HYBRID，支持仅叶子节点或全部层级
- 🛠️ **丰富配置项** — 可调的分块、聚类和检索参数
- 🎨 **现代 Web 管理界面** — Vue 3 + Element Plus
- 🔁 **聚类可复现** — 确定性 GMM（固定种子的 k-means++ 初值 + 自写 EM），同一输入必得完全相同的划分

## 🏗️ 技术栈

| 层级 | 技术 |
|-----|------|
| 后端 | Java 21 + Spring Boot 4.1.1 |
| 数据库 | PostgreSQL 18.6 + pgvector 0.8.6 + pg_search 0.25.6 (ParadeDB) |
| 文档解析 | Apache Tika 3.2.2 |
| 机器学习 / 聚类 | Smile 4.1.0 (UMAP / PCA) + 自研确定性 GMM |
| AI 集成 | Spring AI 2.0.1 (OpenAI 兼容接口) |
| 前端 | Vue 3 + Element Plus + Vite |

> **为什么要自研 GMM？** Smile 的 `MultivariateGaussianMixture` 不暴露 `random_state`，
> 且 `MathEx.setSeed` 也无法控制它（实测：同一输入连跑 10 次，簇数在 2~12 之间跳变，
> 树结构因此不可复现）。`DeterministicGmm` 用固定种子 224（官方实现所用种子）的
> KMeans++ 初始化 + 自写 EM 迭代 + 自算标准 BIC 替代它。

## 🚀 快速开始

### 前置条件

- Docker 环境
- Docker Compose 环境

### Docker 一键部署

#### 1. 准备项目目录

在项目根目录下创建以下目录和文件结构：

```
项目根目录
├─ Dockerfile
├─ docker-compose.yml
├─ app
│   ├─ DocRaptor.jar      # 打包后的jar文件
│   └─ config
│       └─ application.yml # 配置文件
├─ nginx
│   ├─ nginx.conf
│   └─ dist
│       └── index.html    # 前端页面
└─ pg-init
    └── init.sql          # 数据库初始化脚本(来自 docs/02-schema.sql)
```

#### 2. 构建 PostgreSQL 镜像（单独构建）

由于 Dockerfile-pgsql 包含编译步骤和网络请求，建议单独构建：

```bash
# 构建包含 pgvector 扩展的 PostgreSQL 镜像
docker build -t my-paradedb-pgvector -f Dockerfile-pgsql .
```

#### 3. 配置应用

在 `app/config/application.yml` 中自定义配置：

```yaml
spring:
  datasource:
    url: jdbc:postgresql://pgsql:5432/doc_raptor_db
    username: postgres
    password: 123456
  ...
```

#### 4. 一键启动

```bash
# 在项目根目录下执行
docker compose up -d
```

#### 5. 验证服务

- Nginx 前端：http://localhost:80
- API 接口：http://localhost:8080（内部网络）

#### 停止服务

```bash
docker compose down

# 如需初始化数据库
docker compose down -v
```

如需重新构建镜像：

```bash
docker compose build --no-cache
docker compose up -d
```

## 🖥️ 开发环境部署

### 前置条件

- JDK 21+
- Maven 3.9+
- Node.js 22+
- PostgreSQL 18.6（需安装 **`pgvector`** 和 **`pg_search`** 扩展；ParadeDB 发行版已内置两者）

### 1. 克隆项目

```bash
git clone https://github.com/yourusername/DocRaptor.git
cd DocRaptor
mvn clean package -DskipTests
```

### 2. 配置环境

创建 `src/main/resources/application-local.yml`：

```yaml
pg:
  host: localhost
  port: 5432
  user: your_user
  password: your_password
  database: doc_raptor_db

spring:
  ai:
    openai:
      base-url: ${API_URL}
      api-key: ${API_KEY}
```

设置环境变量：

```bash
export API_URL=https://api.openai.com/v1
export API_KEY=your_api_key
```

> ⚠️ 这两项在**启动时就必须存在** —— `application.yml` 直接引用了 `${API_URL}` / `${API_KEY}`。
> 嵌入模型必须返回 **1536 维**（由 `spring.ai.openai.embedding.dimensions` 指定），
> 否则会被表结构里的 `vector(1536)` 拒绝。

### 3. 初始化数据库

执行数据库脚本：

```bash
psql -h localhost -U your_user -d doc_raptor_db -f docs/02-schema.sql
```

### 4. 启动服务

```bash
# 后端 (端口 8080)
mvn spring-boot:run

# 前端 (开发模式)
cd ui && npm install && npm run dev
```

访问 `http://localhost:5173` 打开管理界面。

## 📖 使用流程

1. **创建知识库** — 设置分块大小、重叠度、分块策略（FIXED_SIZE / PARAGRAPH / RECURSIVE）
2. **上传文档** — 支持 PDF / DOCX / Markdown / TXT，单文件 ≤10MB
3. **查看进度** — 异步执行：解析 → 分块 → 向量化 → 构建 RAPTOR 树
4. **查看分块** — 浏览解析后的文本块及来源信息
5. **查看摘要树** — 可视化层级摘要结构
6. **召回测试** — 选择不同检索模式和参数，查看结果
7. **召回率评估** — 录入测试用例，运行 Recall@K 等指标

## ⚙️ 核心参数

### 检索参数

| 参数 | 默认值 | 说明 |
|-----|-------|------|
| `topK` | 10 | 返回结果数 [1, 100] |
| `mode` | HYBRID | 检索模式：VECTOR / BM25 / HYBRID |
| `similarityThreshold` | 0.0 | **仅作用于向量路**的过滤阈值 [0, 1]。**不要设成 0.5** —— 1536 维文本向量的余弦相似度通常只在 ±0.06 附近 |
| `hybridRatio` | 0.5 | 混合检索中向量权重（BM25 权重 = `(1 - hybridRatio) × bm25Weight`） |
| `rrfK` | 60 | RRF 平滑常数 [1, 1000] |
| `scope` | ALL_LEVELS | 检索范围：LEAF_ONLY / ALL_LEVELS / SPECIFIED_LEVEL |

### 聚类参数（构建树时使用）

以下均为 `application.yml` 中的 `docraptor.raptor.*` 配置项。

| 参数 | 默认值 | 说明 |
|-----|-------|------|
| `reduction` | PCA | `PCA`（确定性）/ `UMAP` / `NONE`。**建议保持 PCA** —— Smile 的 UMAP 不可复现，会破坏确定性 |
| `reduction-dimension` | 10 | 降维目标维度（与官方 `reduction_dimension` 一致） |
| `threshold` | 0.1 | 软聚类责任度阈值（官方取值） |
| `two-stage` | true | 先全局聚类，再在每个全局簇内做局部聚类 |
| `max-clusters` | 50 | BIC 搜索的簇数上限（官方 `min(50, n)`） |
| `min-cluster-size` | 8 | 同时把 `kMax` 压到 `n / minClusterSize`。调大得到更少更大的簇；设为 `1` 即关闭 |
| `max-cluster-tokens` | 0 | `0` = 按块大小自动折算；`<0` = 关闭；`>0` = 字面值上限 |
| `nodes-per-cluster-at-cap` | 35 | 上面自动折算用的参数（官方：3500 token ÷ 约 100 token/块） |
| `auto-global-n-neighbors` | true | 全局 `n_neighbors = int(sqrt(n-1))`（官方行为）。`false` 时用 `global-n-neighbors` |
| `local-n-neighbors` | 10 | 局部阶段的 UMAP 近邻数（官方取值） |
| `max-level` | 3 | 树的最大深度 |

> **块大小会影响这个上限。** 官方的 `max_length_in_cluster = 3500` token 是按
> 它自己约 100 token 的块标定的（单簇约 35 块）。我们 700 中文字符的块约 700 token，
> 照搬 3500 只能容纳约 5 块，递归会把树切成几百个单节点簇。
> `max-cluster-tokens: 0` 改为按实际块长中位数自动折算。

## 🌲 聚类算法

树是自底向上构建的。每一层的流程：

1. 对当前层节点向量降维（`reduction-dimension`，默认 10）。
2. **全局聚类** —— 在降维空间上跑 GMM，簇数由 BIC 在 `[2, min(maxClusters, n/minClusterSize, n)]` 内选出。
3. **局部聚类** —— 在**每个**全局簇内部再降维、再聚类一次；局部标签会加偏移量以保持全局唯一。
4. **软聚类归属** —— 只要某个分量的责任度超过 `threshold`（0.1）就归入该簇，因此边界点可以同时属于多个簇。
5. **超大簇递归细分**（按 token 预算，见上）；单节点簇原样保留。
6. 每个簇交给 LLM 生成一个父节点摘要；重复直到只剩一个根，或达到 `max-level`。

**与官方实现的有意差异：**

| 方面 | 官方（Python） | DocRaptor |
|---|---|---|
| GMM 实现 | `sklearn.GaussianMixture`（有 `random_state`） | 自研 `DeterministicGmm`（KMeans++ 种子 224 + 自写 EM） |
| 可复现性 | 依赖 sklearn 播种 | 连跑 10 次逐位相同（已实测） |
| UMAP | `umap-learn`，`metric="cosine"` | 默认 `reduction: PCA`（确定性）；UMAP 可用但**不可复现** |
| 停止规则 | 某层节点数 `≤ reduction_dimension + 1` 时停止，**允许存在多个根** | 保留**唯一根**（把剩余节点合并），与本项目的 API 契约一致 |

## 📂 项目结构

```
DocRaptor/
├── src/main/java/.../docraptor/    # 后端源码
│   ├── algorithm/                  # DeterministicGmm、RaptorClusterer、RrfFusion 等
│   ├── chunker/                    # FIXED_SIZE / PARAGRAPH / RECURSIVE 三种分块
│   ├── service/                    # 导入、建树、检索、评估
│   ├── controller/                 # REST 接口
│   └── async/                      # 异步任务与进度上报
├── ui/                              # Vue 3 前端
├── docs/                            # 文档
│   ├── 01-architecture.md           # 架构设计
│   ├── 02-schema.sql                # 数据库 schema
│   ├── 03-api-contract.md           # REST API 契约
│   ├── 04-tech-selection.md         # 技术选型
│   ├── 05-maven-dependencies.txt    # 依赖树
│   ├── 06-test-report.md            # 测试报告
│   ├── 07-acceptance-report.md      # 端到端验收报告
│   └── samples/                     # 示例文档（MD / TXT / DOCX / PDF）
├── scripts/                         # 工具脚本
│   └── acceptance.mjs               # 端到端验收脚本
└── pom.xml                          # Maven 配置
```

## 🧪 测试

> ⚠️ 本仓库**不包含单元测试** —— `src/test/` 是刻意不提供的。
> 开发期的测试套件不属于交付物；开发过程中实际验证过什么、以及当前**没有**自动化覆盖的部分，
> 见 `docs/06-test-report.md`。

```bash
# 编译
mvn clean package -DskipTests

# 端到端验收（需要后端已启动，且 API_URL / API_KEY 有效）
node scripts/acceptance.mjs
```

`scripts/acceptance.mjs` 会自动：建知识库 → 上传 `docs/samples/raptor-guide.md` → 等待异步流水线
→ 校验分块元信息与摘要树 → 对比三种检索模式 → 运行 Recall@K / Hit Rate@K / MRR 评估，
任一检查失败即以非零码退出。

## 📚 文档索引

| 文件 | 内容 |
|---|---|
| `docs/01-architecture.md` | 分层设计、数据流转、建树伪代码、RRF 公式、指标定义 |
| `docs/02-schema.sql` | 可直接执行的 DDL（7 张表，HNSW + BM25 索引） |
| `docs/03-api-contract.md` | 已冻结的 REST 契约（21 个接口） |
| `docs/04-tech-selection.md` | 技术选型决策（附实测证据） |
| `docs/05-maven-dependencies.txt` | 完整 Maven 依赖树 |
| `docs/06-test-report.md` | 测试了什么、什么没有覆盖 |
| `docs/07-acceptance-report.md` | 端到端验收结果 |

## 📄 许可证

本项目基于 [MIT 许可证](LICENSE) 开源。

## 🤝 欢迎贡献

欢迎提交 Pull Request！
