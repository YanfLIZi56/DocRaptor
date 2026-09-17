# DocRaptor

<p align="center">
  <img src="https://img.shields.io/badge/Java-21-blue?logo=openjdk" alt="Java">
  <img src="https://img.shields.io/badge/Spring%20Boot-4.1-green?logo=spring" alt="Spring Boot">
  <img src="https://img.shields.io/badge/Vue-3-42b883?logo=vuedotjs" alt="Vue 3">
  <img src="https://img.shields.io/badge/PostgreSQL+%20pgvector-18.6-336791?logo=postgresql" alt="PostgreSQL">
  <img src="https://img.shields.io/badge/License-MIT-yellow" alt="License">
</p>

> 🚀 A RAPTOR-style document retrieval system with hierarchical tree organization

DocRaptor is a local standalone **RAPTOR (Recursive Abstractive Processing for Tree-Organized Retrieval)** implementation. It transforms documents into semantic tree structures through chunking, embedding, UMAP dimensionality reduction, GMM clustering, and LLM-powered recursive summarization.

## ✨ Features

- 📄 **Multi-format Document Support** — PDF, DOCX, Markdown, TXT via Apache Tika
- 🌳 **Hierarchical Tree Structure** — RAPTOR tree with multi-level summarization
- 🔍 **Hybrid Retrieval** — Vector (pgvector HNSW) + BM25 (ParadeDB) + RRF fusion
- 📊 **Recall Evaluation** — Built-in metrics: Recall@K, Hit Rate@K, MRR
- 🎯 **Flexible Retrieval Modes** — VECTOR / BM25 / HYBRID, leaf-only or all-levels
- 🛠️ **Rich Configuration** — Tunable chunking, clustering, and retrieval parameters
- 🎨 **Modern Web UI** — Vue 3 + Element Plus management dashboard

## 🏗️ Tech Stack

| Layer | Technology |
|-------|------------|
| Backend | Java 21 + Spring Boot 4.1.1 |
| Database | PostgreSQL 18.6 + pgvector 0.8.6 + ParadeDB |
| Document Parser | Apache Tika 3.2.2 |
| ML / Clustering | Smile 4.1.0 (UMAP + GMM) |
| AI Integration | Spring AI (OpenAI compatible) |
| Frontend | Vue 3 + Element Plus + Vite |

## 🚀 Quick Start

### Prerequisites

- JDK 21+
- Maven 3.9+
- Node.js 22+
- PostgreSQL 18.6 with `pgvector` and `pg_search` extensions

### 1. Clone & Build

```bash
git clone https://github.com/yourusername/DocRaptor.git
cd DocRaptor
mvn clean package -DskipTests
```

### 2. Configure Environment

Create `src/main/resources/application-local.yml`:

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

Set environment variables:

```bash
export API_URL=https://api.openai.com/v1
export API_KEY=your_api_key
```

### 3. Initialize Database

Execute the schema script:

```bash
psql -h localhost -U your_user -d doc_raptor_db -f docs/02-schema.sql
```

### 4. Run

```bash
# Backend (port 8080)
mvn spring-boot:run

# Frontend (dev)
cd ui && npm install && npm run dev
```

Visit `http://localhost:5173` to access the management UI.

## 📖 Usage Flow

1. **Create Knowledge Base** — Set chunk size, overlap, and strategy (FIXED_SIZE / PARAGRAPH / RECURSIVE)
2. **Upload Documents** — PDF / DOCX / Markdown / TXT (≤10MB each)
3. **Monitor Progress** — Async pipeline: parse → chunk → embed → build RAPTOR tree
4. **View Chunks** — Browse parsed chunks with source info
5. **Explore Tree** — Visualize hierarchical summary structure
6. **Search** — Test retrieval with different modes and parameters
7. **Evaluate** — Run recall metrics with test cases

## ⚙️ Key Parameters

### Retrieval

| Parameter | Default | Description |
|-----------|---------|-------------|
| `topK` | 10 | Number of results [1, 100] |
| `mode` | HYBRID | VECTOR / BM25 / HYBRID |
| `hybridRatio` | 0.5 | Vector weight in hybrid mode |
| `scope` | ALL_LEVELS | LEAF_ONLY / ALL_LEVELS |

### Clustering (for tree building)

| Parameter | Default | Description |
|-----------|---------|-------------|
| `nNeighbors` | 5 | UMAP neighbors (must < node count) |
| `targetDim` | 2 | UMAP target dimension |
| `maxClusters` | 6 | GMM max clusters |
| `maxLevel` | 3 | Maximum tree depth |

## 📂 Project Structure

```
DocRaptor/
├── src/main/java/.../docraptor/    # Backend source
├── src/test/java/.../docraptor/    # Unit & integration tests
├── ui/                              # Vue 3 frontend
├── docs/                            # Documentation
│   ├── 01-architecture.md           # Architecture design
│   ├── 02-schema.sql                # Database schema
│   ├── 03-api-contract.md          # REST API contracts
│   └── 04-tech-selection.md         # Tech decisions
├── scripts/                         # Utility scripts
└── pom.xml                          # Maven config
```

## 🧪 Testing

```bash
# Unit tests
mvn test

# Integration tests (requires DB + LLM)
DOCRAPTOR_IT=true mvn clean test

# Acceptance tests (requires running backend)
node scripts/acceptance.mjs
```

## 📄 License

This project is licensed under the [MIT License](LICENSE).

## 🤝 Contributing

Contributions are welcome! Please feel free to submit a Pull Request.