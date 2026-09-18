# DocRaptor

<p align="center">
  <img src="https://img.shields.io/badge/Java-21-blue?logo=openjdk" alt="Java">
  <img src="https://img.shields.io/badge/Spring%20Boot-4.1-green?logo=spring" alt="Spring Boot">
  <img src="https://img.shields.io/badge/Vue-3-42b883?logo=vuedotjs" alt="Vue 3">
  <img src="https://img.shields.io/badge/PostgreSQL+%20pgvector-18.6-336791?logo=postgresql" alt="PostgreSQL">
  <img src="https://img.shields.io/badge/License-MIT-yellow" alt="License">
</p>

> 🚀 A RAPTOR-style document retrieval system with hierarchical tree organization

DocRaptor is a local standalone **RAPTOR (Recursive Abstractive Processing for Tree-Organized Retrieval)** implementation. It transforms documents into semantic tree structures through chunking, embedding, dimensionality reduction, Gaussian Mixture clustering, and LLM-powered recursive summarization.

> 📝 **Note**: This project is an **unofficial community reimplementation** of
> [RAPTOR: Recursive Abstractive Processing for Tree-Organized Retrieval](https://arxiv.org/abs/2401.18059) (Sarthi et al., ICLR 2024),
> rewritten from scratch in Java with additional engineering work
> (hybrid retrieval, recall evaluation, web UI).
> **Not affiliated with** the original authors or Microsoft Research.
>
> **Reference implementation**: the original authors' code is
> [parthsarthi03/raptor](https://github.com/parthsarthi03/raptor) (Python, MIT).
> DocRaptor's clustering stage **follows that implementation's semantics** —
> two-stage clustering (global → per-global-cluster local), soft clustering
> (`responsibility > threshold`), cluster-size-bounded recursion, and
> `n_neighbors = int(sqrt(n-1))`. See [Clustering Algorithm](#-clustering-algorithm)
> for the deliberate differences.

## ✨ Features

- 📄 **Multi-format Document Support** — PDF, DOCX, Markdown, TXT via Apache Tika
- 🌳 **Hierarchical Tree Structure** — RAPTOR tree with multi-level summarization
- 🔍 **Hybrid Retrieval** — Vector (pgvector HNSW) + BM25 (ParadeDB) + RRF fusion
- 📊 **Recall Evaluation** — Built-in metrics: Recall@K, Hit Rate@K, MRR
- 🎯 **Flexible Retrieval Modes** — VECTOR / BM25 / HYBRID, leaf-only or all-levels
- 🛠️ **Rich Configuration** — Tunable chunking, clustering, and retrieval parameters
- 🎨 **Modern Web UI** — Vue 3 + Element Plus management dashboard
- 🔁 **Reproducible Clustering** — Deterministic GMM (fixed-seed k-means++ init + hand-written EM); same input yields byte-identical clusters

## 🏗️ Tech Stack

| Layer | Technology |
|-------|------------|
| Backend | Java 21 + Spring Boot 4.1.1 |
| Database | PostgreSQL 18.6 + pgvector 0.8.6 + pg_search 0.25.6 (ParadeDB) |
| Document Parser | Apache Tika 3.2.2 |
| ML / Clustering | Smile 4.1.0 (UMAP / PCA) + in-house deterministic GMM |
| AI Integration | Spring AI 2.0.1 (OpenAI compatible) |
| Frontend | Vue 3 + Element Plus + Vite |

> **Why an in-house GMM?** Smile's `MultivariateGaussianMixture` exposes no
> `random_state` and `MathEx.setSeed` cannot control it, so cluster assignments
> were not reproducible (measured: k jumped between 2 and 12 on identical input).
> `DeterministicGmm` replaces it with a KMeans++ initialization seeded at 224
> (the seed used by the official implementation) plus a hand-written EM loop and
> a self-computed standard BIC.

## 🚀 Quick Start

### Prerequisites

- Docker environment
- Docker Compose environment

### Docker Deployment

#### 1. Prepare Project Directory

Create the following directory structure in the project root:

```
Project Root
├─ Dockerfile
├─ docker-compose.yml
├─ app
│   ├─ DocRaptor.jar      # Packaged JAR file
│   └─ config
│       └─ application.yml # Configuration file
├─ nginx
│   ├─ nginx.conf
│   └─ dist
│       └── index.html    # Frontend page
└─ pg-init
    └── init.sql          # Database init script (from docs/02-schema.sql)
```

#### 2. Build PostgreSQL Image (Separate Build)

Since Dockerfile-pgsql contains compilation steps and network requests, build it separately:

```bash
# Build PostgreSQL image with pgvector extension
docker build -t my-paradedb-pgvector -f Dockerfile-pgsql .
```

#### 3. Configure Application

Create `app/config/application.yml` with database connection:

```yaml
spring:
  datasource:
    url: jdbc:postgresql://pgsql:5432/doc_raptor_db
    username: postgres
    password: 123456
  ...
```

#### 4. One-Click Start

```bash
# Execute in project root
docker compose up -d
```

#### 5. Verify Services

- Nginx Frontend: http://localhost:80
- API: http://localhost:8080 (internal network)

#### Stop Services

```bash
docker compose down

# If you need to initialize the database
docker compose down -v
```

To rebuild images:

```bash
docker compose build --no-cache
docker compose up -d
```

### Development Environment

**Prerequisites**

- JDK 21+
- Maven 3.9+
- Node.js 22+
- PostgreSQL 18.6 with **`pgvector`** and **`pg_search`** extensions
  (the ParadeDB distribution ships both)

**1. Clone and Build**

```bash
git clone https://github.com/yourusername/DocRaptor.git
cd DocRaptor
mvn clean package -DskipTests
```

**2. Configure Environment**

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

> ⚠️ Both are **required at startup** — `application.yml` references `${API_URL}` /
> `${API_KEY}`. The embedding model must return **1536 dimensions** (configured via
> `spring.ai.openai.embedding.dimensions`); the schema's `vector(1536)` column rejects
> anything else.

**3. Initialize Database**

Execute the schema script:

```bash
psql -h localhost -U your_user -d doc_raptor_db -f docs/02-schema.sql
```

**4. Run**

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
| `similarityThreshold` | 0.0 | Vector-only filter, [0, 1]. **Never set this to 0.5** — cosine similarity between 1536-dim text embeddings typically sits near ±0.06 |
| `hybridRatio` | 0.5 | Vector weight in hybrid mode (`w_bm25 = (1 - hybridRatio) × bm25Weight`) |
| `rrfK` | 60 | RRF smoothing constant [1, 1000] |
| `scope` | ALL_LEVELS | LEAF_ONLY / ALL_LEVELS / SPECIFIED_LEVEL |

### Clustering (for tree building)

All values below are `docraptor.raptor.*` in `application.yml`.

| Parameter | Default | Description |
|-----------|---------|-------------|
| `reduction` | PCA | `PCA` (deterministic) / `UMAP` / `NONE`. **Keep PCA** — UMAP is not reproducible in Smile and breaks determinism |
| `reduction-dimension` | 10 | Target dimensionality (same as the official `reduction_dimension`) |
| `threshold` | 0.1 | Soft-clustering responsibility threshold (official value) |
| `two-stage` | true | Global clustering, then a local clustering inside each global cluster |
| `max-clusters` | 50 | Upper bound for the BIC search (official `min(50, n)`) |
| `min-cluster-size` | 8 | Also bounds `kMax` to `n / minClusterSize`. Raise it for fewer, larger clusters; set to `1` to disable |
| `max-cluster-tokens` | 0 | `0` = auto-scale by chunk size; `<0` = disabled; `>0` = literal cap |
| `nodes-per-cluster-at-cap` | 35 | Used by the auto-scaling above (official: 3500 tokens ÷ ~100 tokens/chunk) |
| `auto-global-n-neighbors` | true | Global `n_neighbors = int(sqrt(n-1))` (official). `false` uses `global-n-neighbors` |
| `local-n-neighbors` | 10 | Local-stage UMAP neighbors (official value) |
| `max-level` | 3 | Maximum tree depth |

> **Chunk-size awareness matters.** The official `max_length_in_cluster = 3500`
> tokens was calibrated for ~100-token chunks (≈35 chunks per cluster). With
> 700-character Chinese chunks (~700 tokens) a literal 3500 would allow only
> ~5 chunks per cluster and the recursion would shred the tree into hundreds of
> singleton clusters. `max-cluster-tokens: 0` scales the cap by the actual median
> chunk size instead.

## 🌲 Clustering Algorithm

The tree is built bottom-up. At each level:

1. Reduce dimensionality of the current layer's node embeddings (`reduction-dimension`, default 10).
2. **Global clustering** — GMM over the reduced space; cluster count chosen by BIC over `[2, min(maxClusters, n/minClusterSize, n)]`.
3. **Local clustering** — inside *each* global cluster, reduce again and cluster again; local labels are offset to stay globally unique.
4. **Soft assignment** — a node joins every component whose responsibility exceeds `threshold` (0.1), so points on a boundary can belong to several clusters.
5. **Oversized clusters** are recursively split (token budget, see above); single-node clusters are kept as-is.
6. Each cluster is summarized by the LLM into one parent node; repeat until a single root remains or `max-level` is reached.

**Deliberate differences from the official implementation:**

| Aspect | Official (Python) | DocRaptor |
|---|---|---|
| GMM library | `sklearn.GaussianMixture` (has `random_state`) | In-house `DeterministicGmm` (KMeans++ seed 224 + hand-written EM) |
| Reproducibility | Seeded sklearn | Byte-identical across runs (10/10 verified) |
| UMAP | `umap-learn` with `metric="cosine"` | `reduction: PCA` by default (deterministic); UMAP available but **not** reproducible |
| Stopping rule | Stops when a layer has `≤ reduction_dimension + 1` nodes and **leaves multiple roots** | Keeps a **single unique root** (merges the remainder), matching this project's API contract |

## 📂 Project Structure

```
DocRaptor/
├── src/main/java/.../docraptor/    # Backend source
│   ├── algorithm/                  # DeterministicGmm, RaptorClusterer, RrfFusion, …
│   ├── chunker/                    # FIXED_SIZE / PARAGRAPH / RECURSIVE
│   ├── service/                    # Ingestion, tree building, retrieval, evaluation
│   ├── controller/                 # REST endpoints
│   └── async/                      # Async task workers + progress reporting
├── ui/                              # Vue 3 frontend
├── docs/                            # Documentation
│   ├── 01-architecture.md           # Architecture design
│   ├── 02-schema.sql                # Database schema
│   ├── 03-api-contract.md           # REST API contracts
│   ├── 04-tech-selection.md         # Tech decisions
│   ├── 05-maven-dependencies.txt    # Dependency tree
│   ├── 06-test-report.md            # Test report
│   ├── 07-acceptance-report.md      # End-to-end acceptance report
│   └── samples/                     # Sample documents (MD / TXT / DOCX / PDF)
├── scripts/                         # Utility scripts
│   └── acceptance.mjs               # End-to-end acceptance runner
└── pom.xml                          # Maven config
```

## 🧪 Testing

> ⚠️ This repository **ships without unit tests** — `src/test/` is intentionally
> absent. The development-time test suite is not part of the deliverable; see
> `docs/06-test-report.md` for what was verified during development and what is
> therefore *not* covered by automated checks today.

```bash
# Compile
mvn clean package -DskipTests

# End-to-end acceptance (requires a running backend with valid API_URL / API_KEY)
node scripts/acceptance.mjs
```

`scripts/acceptance.mjs` creates a knowledge base, uploads
`docs/samples/raptor-guide.md`, waits for the async pipeline, verifies chunk
metadata and the summary tree, compares the three retrieval modes, then runs
Recall@K / Hit Rate@K / MRR evaluation and exits non-zero if any check fails.

## 📚 Documentation

| File | Contents |
|---|---|
| `docs/01-architecture.md` | Layered design, data flow, build pseudocode, RRF formula, metric definitions |
| `docs/02-schema.sql` | Executable DDL (7 tables, HNSW + BM25 indexes) |
| `docs/03-api-contract.md` | Frozen REST contract (21 endpoints) |
| `docs/04-tech-selection.md` | Library/API decisions with measured evidence |
| `docs/05-maven-dependencies.txt` | Full Maven dependency tree |
| `docs/06-test-report.md` | What was tested, what is not covered |
| `docs/07-acceptance-report.md` | End-to-end acceptance results |

## 📄 License

This project is licensed under the [MIT License](LICENSE).

## 🤝 Contributing

Contributions are welcome! Please feel free to submit a Pull Request.
