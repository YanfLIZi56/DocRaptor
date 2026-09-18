# DocRaptor

<p align="center">
  <img src="https://img.shields.io/badge/Java-21-blue?logo=openjdk" alt="Java">
  <img src="https://img.shields.io/badge/Spring%20Boot-4.1-green?logo=spring" alt="Spring Boot">
  <img src="https://img.shields.io/badge/Vue-3-42b883?logo=vuedotjs" alt="Vue 3">
  <img src="https://img.shields.io/badge/PostgreSQL+%20pgvector-18.6-336791?logo=postgresql" alt="PostgreSQL">
  <img src="https://img.shields.io/badge/License-MIT-yellow" alt="License">
</p>

> 🚀 基于 RAPTOR 风格的文档检索系统，支持层级树结构组织

DocRaptor 是一个本地单机运行的 **RAPTOR (Recursive Abstractive Processing for Tree-Organized Retrieval)** 实现。通过文档解析、分块、向量化、UMAP 降维、GMM 语义聚类和 LLM 递归摘要，将文档转化为语义树结构。

## ✨ 功能特性

- 📄 **多格式文档支持** — PDF、DOCX、Markdown、TXT（基于 Apache Tika）
- 🌳 **层级树结构** — RAPTOR 树，支持多级摘要
- 🔍 **混合检索** — 向量检索 (pgvector HNSW) + BM25 全文检索 (ParadeDB) + RRF 融合
- 📊 **召回率评估** — 内置指标：Recall@K、Hit Rate@K、MRR
- 🎯 **灵活检索模式** — VECTOR / BM25 / HYBRID，支持仅叶子节点或全部层级
- 🛠️ **丰富配置项** — 可调的分块、聚类和检索参数
- 🎨 **现代 Web 管理界面** — Vue 3 + Element Plus

## 🏗️ 技术栈

| 层级 | 技术 |
|-----|------|
| 后端 | Java 21 + Spring Boot 4.1.1 |
| 数据库 | PostgreSQL 18.6 + pgvector 0.8.6 + ParadeDB |
| 文档解析 | Apache Tika 3.2.2 |
| 机器学习 / 聚类 | Smile 4.1.0 (UMAP + GMM) |
| AI 集成 | Spring AI (OpenAI 兼容接口) |
| 前端 | Vue 3 + Element Plus + Vite |

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
│       └─ index.html     # 前端页面
└─ pg-init
    └─ init.sql           # 数据库初始化脚本(来自 docs/02-schema.sql)
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

## 🖥️开发环境部署

### 前置条件

- JDK 21+
- Maven 3.9+
- Node.js 22+
- PostgreSQL 18.6（需安装 `pgvector` 和 `pg_search` 扩展）

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
| `hybridRatio` | 0.5 | 混合检索中向量权重 |
| `scope` | ALL_LEVELS | 检索范围：LEAF_ONLY / ALL_LEVELS |

### 聚类参数（构建树时使用）

| 参数 | 默认值 | 说明 |
|-----|-------|------|
| `nNeighbors` | 5 | UMAP 近邻数（必须小于节点数） |
| `targetDim` | 2 | UMAP 目标维度 |
| `maxClusters` | 6 | GMM 最大簇数 |
| `maxLevel` | 3 | 树的最大深度 |

## 📂 项目结构

```
DocRaptor/
├── src/main/java/.../docraptor/    # 后端源码
├── src/test/java/.../docraptor/    # 单元测试和集成测试
├── ui/                              # Vue 3 前端
├── docs/                            # 文档
│   ├── 01-architecture.md           # 架构设计
│   ├── 02-schema.sql                # 数据库 schema
│   ├── 03-api-contract.md          # REST API 契约
│   └── 04-tech-selection.md         # 技术选型
├── scripts/                         # 工具脚本
└── pom.xml                          # Maven 配置
```

## 🧪 测试

```bash
# 单元测试
mvn test

# 集成测试（需要数据库和 LLM）
DOCRAPTOR_IT=true mvn clean test

# 端到端验收测试（需要后端运行）
node scripts/acceptance.mjs
```

## 📄 许可证

本项目基于 [MIT 许可证](LICENSE) 开源。

## 🤝 欢迎贡献

欢迎提交 Pull Request！