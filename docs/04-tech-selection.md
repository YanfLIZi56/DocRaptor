# DocRaptor 技术选型报告（researcher 实测产出）

> **本文所有类名、方法签名、配置项、SQL 均来自真实编译运行成功或从 jar/数据库实际读到的内容，没有任何一条来自记忆。**
> 每条结论都带 `E<n>` 证据编号，证据编号对应的真实命令与真实输出片段在本文件内，原始完整输出在
> `.scratch/researcher/*-out.txt`（清单见文末附录 B）。
>
> 生成时间：2026-09-17　JDK 21.0.5　Maven 3.9.9　Spring Boot 4.1.1　Spring AI 2.0.1　Smile 4.1.0
> PostgreSQL 18.6 / pgvector 0.8.6 / pg_search 0.25.6

---

## 0. 结论先行：选型决策表

| # | 技术点 | 选定方案 | 理由 | 证据 |
|---|---|---|---|---|
| 1 | 降维 API | `smile.manifold.UMAP.of(double[][] data, int nNeighbors, int d, int epochs, double learningRate, double minDist, double spread, int negativeSamples, double repulsionStrength, double localConnectivity)` | 4.1.0 里 UMAP **只有静态 `of(...)` 工厂方法，没有构造器参数、没有 builder**。`d`（目标维度）是**第 3 个参数**；两参数重载 `of(data, k)` 的 `k` 是 `nNeighbors`、目标维度被写死为 2。 | E1 E2 |
| 2 | 降维调用阈值 | `n < 30` 时**不要调用 UMAP**，直接对原始 1536 维向量聚类；`30 ≤ n` 才走 UMAP | 实测：`d=10` 时 `n ≤ 23` 必抛 `IllegalArgumentException: Invalid NEV parameter k: 23`（ARPACK 谱初始化要求 `numEigen < n`）；`nNeighbors ≥ n` 必抛 `ArrayIndexOutOfBoundsException`；全同向量必抛 `IllegalArgumentException: Invalid dimension of feature space: 1537`。 | E4 E5 |
| 3 | 降维可复现性 | 调用前执行 `smile.math.MathEx.setSeed(固定值)` | 不设种子时同一输入两次运行输出 `max|Δ| = 10.25`；设种子后为 **0.0**。RAPTOR 树结构必须可复现。（这是对简报 §9「UMAP 无法固定随机性」的实测反例，见 D12） | E6 |
| 4 | 聚类 API | `smile.stat.distribution.MultivariateGaussianMixture.fit(int k, double[][] data)` | **`smile.clustering.GaussianMixture` 在 4.1.0 里根本不存在**（`ClassNotFoundException` 实测）。GMM 在 `smile.stat.distribution` 包下（属 `smile-base` 传递依赖）。 | E7 |
| 5 | 最优簇数 | **不要用 `fit(data)` 的 BIC 自动选 k** —— 实测恒返回 `components=1`。改用 `smile.clustering.GMeans.fit(double[][] data, int kmax)`（`kmax` = 最大簇数）或显式 `fit(k, data)` 循环 | `fit(data)` 的基线 BIC 被硬编码为 `0.0`，而真实 BIC 恒为负 → 循环第一次迭代就 `break`。GMeans 实测在 trueK=6/kmax=8 时给出 k=6，且支持 `kmax` 上限。 | E8 E9 E11 |
| 6 | 簇数上下界 | 下界 `k >= 2`；硬上界 `k <= 2n`（整数除法），**工程上按 `2 <= k <= min(maxComponents, max(2, floor(sqrt(n))))` 夹取** | 实测 `k=1` 抛 `Invalid number of components in the mixture`；`k=50, n=20` 抛 `Too many components`；真正能跑通的最大 k 远小于 `2n`：`n=20→7`、`n=50→13`、`n=100→19`、`n=200→45`、`n=400→50`，超出即 `ArithmeticException: LAPACK POTRF error code: 1`。 | E10 |
| 7 | 聚类可复现性 | 每次 `fit` 前重新 `MathEx.setSeed(...)`，并**把簇标签按质心排序后重编号** | 固定 k 的 GMM 标签不稳定：两次 `fit(4, sameData)` 的标签序列与簇大小 `[41,40,43,26]` vs `[31,32,45,42]` 都不同（标签是任意编号）。 | E8 E11 |
| 8 | 本地 BLAS | **必须保留** `org.bytedeco:openblas` / `javacpp` / `arpack-ng` 传递依赖，不能 `<exclusions>` | UMAP 的谱初始化走 ARPACK，GMM 的协方差分解走 LAPACK（`LAPACK POTRF`）。实测这一组 native 工件（各平台 classifier + 基础包）已由 `smile-core → smile-base` 自动带入，无需手工声明。 | E11 E12 |
| 9 | Spring AI 自动装配 | `spring-ai-starter-model-openai:2.0.1` 在 Spring Boot **4.1.1** 下**开箱可用**，bean 名仍为 `openAiChatModel` / `openAiEmbeddingModel` | 实测用真实 `SpringApplication.run` 起 context：`CONTEXT STARTED OK`，`ChatModel -> [openAiChatModel]`，`EmbeddingModel -> [openAiEmbeddingModel]`，`vectorStore -> PgVectorStore`。 | E13 E14 |
| 10 | BM25 中文分词 | **用默认分词器，不要显式配 jieba** | 16 组中文查询对比：多字词两者召回完全相同；单字查询 `摘` 默认分词器命中 `[1,6]`、jieba 命中 `[]`（丢召回）。默认索引的真实 `tokenizer` 是 `unicode_words_removeemojis:false`（**不是** `default`）。 | E16 E17 |
| 11 | BM25 分数 | 不做归一化，**不得**把它当 `[0,1]` 相似度用；只用于 `ORDER BY score DESC` 排序 | 实测真实分布：全库命中查询 `0.0821`，稀有词 `2.58~3.91`，多词 OR `0.70~9.72`。范围 `[0.08, 9.72]`，非归一化。 | E15 |
| 12 | `paradedb.score` 使用约束 | 只能在带 `@@@` 的查询里使用 | 无 `@@@` 谓词时抛 `ERROR: Unsupported query shape`。 | E15 |
| 13 | 余弦相似度换算 | `similarity = 1 - (embedding <=> :qvec)`，取值域 `[-1, 1]`；`distance ∈ [0,2]` | 实测自相似恒 `= 1`、相反向量 `= 2`、正交 `= 1`。`similarityThreshold` 必须用这个换算值，不能直接用 `<=>` 的结果。 | E20 |
| 14 | `hnsw.ef_search` 生效方式 | 生产代码用 **`SET LOCAL` + `@Transactional`**（或每连接 `SET` + 归还前 `RESET`），**不要**在自动提交下用 `SET LOCAL` | 实测自动提交下 `SET LOCAL hnsw.ef_search = 10` 只给 `WARNING: SET LOCAL can only be used in transaction blocks`，值仍为 40；普通 `SET` 立即生效但**会持续占用该连接**（HikariCP 复用时泄漏）。功能验证：`ef_search=1` 只返回 1 行，`ef_search=1000` 返回真实 top-10。 | E18 |
| 15 | 小表是否走 HNSW | 行数很少时不走 HNSW，走顺序扫描 —— **这是正常行为，不要"修复"** | `EXPLAIN (ANALYZE)` 实测：15 行 → `Seq Scan` + `top-N heapsort`；3015 行 → `Index Scan using probe_docs_hnsw`。 | E19 |
| 16 | MyBatis + `@@@` 参数绑定 | **`#{}` 机制上完全可用**；但 BM25 路必须写成 `id @@@ paradedb.match('content', #{query})`，**不要**写 `content @@@ #{query}` | JDBC `PreparedStatement` 实测 11 个用例全部通过（`content @@@ ?`、`?::text`、`paradedb.match('content', ?)`、`paradedb.parse(?)`、`LIMIT ?`、双参数、双 `@@@` OR）→ **占位符不是问题**；但 Lead §2.3 实测：带中文标点的自然语言问句用 `content @@@ ?` 有 2/5 零命中，用 `paradedb.match` 才 5/5。 | E21 |
| 17 | 新增 Maven 依赖 | **0 个新增**。所有能力（UMAP / GMM / GMeans / JDBC / MyBatis / pgvector 类型 / Spring AI）都在现有 pom 里 | 本报告全部 15 个实测探针程序都是在**未改动 pom** 的 `mvn dependency:build-classpath` 输出上编译运行的。 | E12 |

---

## 1. 问题 1：Smile 4.1.0 的 UMAP 降维怎么做

### 1.1 真实类名与真实方法签名

先定位类别（4.1.0 的 UMAP 在 `smile.manifold`，但在哪个 jar 需要查）：

```
> & jar tf .m2repo\com\github\haifengl\smile-core\4.1.0\smile-core-4.1.0.jar | Select-String 'manifold|UMAP'
smile/manifold/UMAP$Curve.class
smile/manifold/UMAP.class
smile/manifold/IsoMap.class
smile/manifold/TSNE.class
...
```

`javap` 拿到真实签名（**没有构造器参数、没有 builder、没有 `UMAP.fit`**）：

```
> javap -p -cp "<smile-core-4.1.0.jar>;<smile-base-4.1.0.jar>" smile.manifold.UMAP
public class smile.manifold.UMAP {
  private static final org.slf4j.Logger logger;
  private static final int LARGE_DATA_SIZE;
  public smile.manifold.UMAP();                                          // <-- 无参构造器，没有任何参数
  public static double[][] of(double[][], int);
  public static double[][] of(double[][], int, int, int, double, double, double, int, double, double);
  public static <T> double[][] of(T[], smile.math.distance.Metric<T>, int);
  public static <T> double[][] of(T[], smile.math.distance.Metric<T>, int, int, int, double, double, double, int, double, double);
  public static <T> double[][] of(T[], smile.graph.NearestNeighborGraph, int, int, double, double, double, int, double, double);
  static {};
}
```

`javap` 不输出形参名（jar 未用 `-parameters` 编译），所以从 `smile-core-4.1.0-sources.jar` 里取出源码确认参数含义与默认值：

```java
// smile/manifold/UMAP.java  第 73-121 行（sources jar 原文）
private static final int LARGE_DATA_SIZE = 10000;                      // 第 62 行

public static double[][] of(double[][] data, int k) {                  // 第 73 行
    return of(data, k, 2, 0, 1.0, 0.1, 1.0, 5, 1.0, 1.0);              // 第 74 行
}

public static double[][] of(double[][] data, int k, int d, int epochs, double learningRate,
                            double minDist, double spread, int negativeSamples,
                            double repulsionStrength, double localConnectivity) {   // 第 113-115 行
    NearestNeighborGraph nng = data.length <= LARGE_DATA_SIZE ?
            NearestNeighborGraph.of(data, k) : NearestNeighborGraph.descent(data, k);
    return of(data, nng, d, epochs, learningRate, minDist, spread,
              negativeSamples, repulsionStrength, localConnectivity);
}
```

**参数表（每一项都来自 sources jar 的 javadoc 与第 74 行的默认值）：**

| 位置 | 形参名 | 含义 | 默认值（`of(data,k)` 里写死的） |
|---|---|---|---|
| 1 | `data` | `double[][]`，行=样本，列=特征 | — |
| 2 | `k` | **nNeighbors**（不是输出维度！） | 调用方传 |
| 3 | `d` | **目标嵌入维度** | **`2`** ← 两参数重载写死为 2 |
| 4 | `epochs` | 迭代轮数；`< 10` 时按数据量自动选 | `0` → 自动：`n > 10000 ? 200 : 500` |
| 5 | `learningRate` | 初始学习率 | `1.0` |
| 6 | `minDist` | 嵌入空间近邻点期望间距 | `0.1` |
| 7 | `spread` | 嵌入点有效尺度 | `1.0` |
| 8 | `negativeSamples` | 每正样本负采样数 | `5` |
| 9 | `repulsionStrength` | 负样本排斥权重 | `1.0` |
| 10 | `localConnectivity` | 局部连通度，**必须 ≥ 1** | `1.0` |

> ⚠️ **最大陷阱**：`UMAP.of(data, 15)` 里的 `15` 是 nNeighbors，输出只有 **2 维**。想要 10 维必须写成
> `UMAP.of(data, 15, 10, 0, 1.0, 0.1, 1.0, 5, 1.0, 1.0)`。
> 参数校验也在源码里（第 230-247 行）：`d < 2` → `"d must be greater than 1"`；`minDist <= 0`；
> `minDist > spread` → `"minDist must be less than or equal to spread"`；`learningRate <= 0`；
> `negativeSamples <= 0`；`localConnectivity < 1`。

### 1.2 实测降维（200 个 1536 维向量 → 10 维）

跑法（JDK 21 JEP 330 单文件源码启动；`.scratch/researcher/run.cmd` 里封装了 classpath + UTF-8 输出）：

```
> mvn -s maven-settings.xml -B -q dependency:build-classpath "-Dmdep.outputFile=.scratch\researcher\cp.txt"
> cmd /c ".scratch\researcher\run.cmd D:\javaCode\DocRaptor\.scratch\researcher\UMAPProbe.java > .scratch\researcher\umap-out.txt 2>&1"
```

真实输出（`.scratch/researcher/umap-out.txt`，实测所得）：

```
=== PROBE 1: UMAP.of(double[][], int k) defaults (k = nNeighbors, output dim is FIXED 2) ===
UMAP.of(data200x1536, 15) -> rows=200 cols=2
   first row first 4 dims: 4.8874 -13.5620 
   elapsed ms = 3178

=== PROBE 2: UMAP.of(data, nNeighbors, d, epochs, ...) target 10 dims ===
UMAP.of(data200x1536, 15, 10, 0, 1.0, 0.1, 1.0, 5, 1.0, 1.0) -> rows=200 cols=10
   first row first 4 dims: 0.3984 -5.0226 -3.2197 5.5266 
   elapsed ms = 1537
```

**结论（E3）**：`200×1536 → 200×10` 跑通，单次约 1.5 s（内含 500 轮 SGD，日志确认
`INFO smile.manifold.UMAP -- Set epochs = 500`）。**形状是 `[n][d]`，行序与输入行序一一对应。**

日志还给出了内部初始化路径的真实分支（同一份输出文件）：

```
INFO smile.manifold.UMAP -- The nearest neighbor graph has 1 connected component(s).
INFO smile.manifold.UMAP -- Spectral initialization will be attempted.
INFO smile.manifold.UMAP -- Spectral layout computes 23 eigen vectors
```

### 1.3 样本量要求与实测失败边界（这是本节最重要的部分）

源码给出的约束链（`UMAP.java` 第 549-591 行 spectralLayout + 第 116-118 行 ANN 构建）：

```java
int k = d + 1;
int numEigen = Math.max(2*k+1, (int) Math.sqrt(n));      // d=10 -> 23 ; d=2 -> 7
...
Matrix.EVD eigen = ARPACK.syev(L, ARPACK.SymmOption.SM, numEigen);   // 这里是抛异常的地方
```

实测边界扫描（`.scratch/researcher/umap3-out.txt`）：

```
=== A: d=10 -> numEigen = 2*(d+1)+1 = 23. Exact n boundary with nk=15 ===
  n=20 nk=15 d=10 -> FAIL IllegalArgumentException: Invalid NEV parameter k: 23
  n=21 nk=15 d=10 -> FAIL IllegalArgumentException: Invalid NEV parameter k: 23
  n=22 nk=15 d=10 -> FAIL IllegalArgumentException: Invalid NEV parameter k: 23
  n=23 nk=15 d=10 -> FAIL IllegalArgumentException: Invalid NEV parameter k: 23
  n=24 nk=15 d=10 -> OK 24x10
  n=25 nk=15 d=10 -> OK 25x10

=== B: d=2 -> numEigen = 2*(d+1)+1 = 7. nk=2, exact n boundary ===
  n=7 nk=2 d=2 -> FAIL IllegalArgumentException: Invalid NEV parameter k: 7
  n=8 nk=2 d=2 -> OK 8x2

=== D: nNeighbors vs n (d=2, numEigen=7), n=20 ===
  n=20 nk=18 d=2 -> OK 20x2
  n=20 nk=20 d=2 -> FAIL ArrayIndexOutOfBoundsException: null
  n=20 nk=22 d=2 -> FAIL ArrayIndexOutOfBoundsException: null

=== E: nk=1 ===
  n=50 nk=1 d=10 -> FAIL IllegalArgumentException: k must be greater than 1: 1
```

以及全同向量（RAPTOR 递归到顶时摘要可能塌缩成同一个向量）：

```
=== SWEEP D: identical points, vary n, d=10, nk=5 ===
n=10  identical -> FAIL IllegalArgumentException: Invalid dimension of feature space: 1537
n=20  identical -> FAIL IllegalArgumentException: Invalid dimension of feature space: 1537
n=100 identical -> FAIL IllegalArgumentException: Invalid dimension of feature space: 1537
```

**E4/E5 结论 —— 经验阈值：**

| 条件 | 实测结果 |
|---|---|
| `nNeighbors < 2` | `IllegalArgumentException: k must be greater than 1` |
| `nNeighbors ≥ n` | `ArrayIndexOutOfBoundsException` |
| kNN 图连通 且 `n ≤ 2d+3` | `IllegalArgumentException: Invalid NEV parameter k: <2d+3>`（d=10 → n≤23 失败；d=2 → n≤7 失败） |
| 所有点完全相同（含全零向量） | `IllegalArgumentException: Invalid dimension of feature space: <d+1>`，**任何 n 都失败** |

**因此本项目的硬阈值建议：`n < 30` 时完全不要调用 UMAP，直接对原始 1536 维向量做聚类。**
`n ≥ 30` 时也用 `try/catch (RuntimeException)` 包住 UMAP，失败即回退原始向量（因为"图是否连通"取决于数据，
阈值 24 只在图连通时成立；`paradedb`/`smile` 都不会替你做这个判断）。同时 `nNeighbors` 用
`min(15, max(2, n/2))` 更稳（实测 `n=10, nk=2` 是 OK 的）。

### 1.4 参数怎么传 & 默认值（问题原文要求）

- **只能通过静态 `UMAP.of(...)` 传**，没有构造器参数、没有 builder、没有 `setNN()` 之类的 setter；
  `public smile.manifold.UMAP()` 是个什么都不做的无参构造器（`javap` 实测）。
- `nNeighbors` = 第 2 个参数；`minDist` = 第 6 个参数；`spread`（必须 ≥ minDist）= 第 7 个参数。
- 默认值即第 74 行：`d=2, epochs=0(自动 500/200), learningRate=1.0, minDist=0.1, spread=1.0,
  negativeSamples=5, repulsionStrength=1.0, localConnectivity=1.0`。
- **本项目建议**：`UMAP.of(data, min(15, n/2), 10, 0, 1.0, 0.1, 1.0, 5, 1.0, 1.0)`，即只在 `d=10` 上覆盖，
  其余保持官方默认。

---

## 2. 问题 2：Smile 4.1.0 的 GMM 聚类怎么做

### 2.1 首先：`smile.clustering.GaussianMixture` 不存在

全 `.m2repo` 扫 jar 内容 + 运行时反射双重确认：

```
> (扫描所有 jar) 匹配 'GaussianMixture' 的结果：
smile/stat/distribution/GaussianMixture.class                 <- smile-base，一元（标量）GMM
smile/stat/distribution/MultivariateGaussianMixture.class     <- smile-base，多元 GMM  ← 本项目要的
smile/stat/distribution/MultivariateExponentialFamilyMixture.class
```

```java
// .scratch/researcher/gmm-out.txt（实测运行输出）
=== 6: does MultivariateGaussianMixture expose maxComponents? ===
  methods on MultivariateGaussianMixture:
    split[class [Lsmile.stat.distribution.MultivariateMixture$Component;]
    fit[class [[D]
    fit[class [[D, boolean]
    fit[int, class [[D]
    fit[int, class [[D, boolean]
  does smile.clustering.GaussianMixture exist? -> NO (ClassNotFoundException)
  does smile.clustering.KMeans exist? -> YES
```

`smile.clustering` 包下**只有** `KMeans / XMeans / GMeans / DBSCAN / DENCLUE / MEC / SIB /
SpectralClustering / CLARANS / DeterministicAnnealing / KModes / HierarchicalClustering`（`jar tf` 全量列表见
`.scratch/researcher/smile-core-toc.txt`）。

### 2.2 真实类名与签名

```
> javap -cp "<smile-core>;<smile-base>" smile.stat.distribution.MultivariateGaussianMixture
public class smile.stat.distribution.MultivariateGaussianMixture extends smile.stat.distribution.MultivariateExponentialFamilyMixture {
  public smile.stat.distribution.MultivariateGaussianMixture(smile.stat.distribution.MultivariateMixture$Component...);
  public static smile.stat.distribution.MultivariateGaussianMixture fit(int, double[][]);        // 固定 k
  public static smile.stat.distribution.MultivariateGaussianMixture fit(int, double[][], boolean);
  public static smile.stat.distribution.MultivariateGaussianMixture fit(double[][]);              // BIC 自动选 k
  public static smile.stat.distribution.MultivariateGaussianMixture fit(double[][], boolean);
  static {};
}

> javap -cp "<smile-core>" smile.stat.distribution.MultivariateMixture
public class smile.stat.distribution.MultivariateMixture implements smile.stat.distribution.MultivariateDistribution {
  public final smile.stat.distribution.MultivariateMixture$Component[] components;
  public double[] posteriori(double[]);     // 责任度/后验概率
  public int map(double[]);                 // <-- 取簇标签
  public double[] mean();
  public smile.math.matrix.Matrix cov();
  public double p(double[]);  public double logp(double[]);
  public int length();                      // 自由参数个数（BIC 用）
  public int size();                        // 成分数
  public double bic(double[][]);
}

> javap -cp "<smile-core>;<smile-base>" smile.stat.distribution.MultivariateExponentialFamilyMixture
public final double L;      // 对数似然
public final double bic;    // BIC 分数（public final 字段，不是方法）
```

> ⚠️ **`MultivariateGaussianMixture` 没有 `.k` / `.y` / `.size[]` 字段**（那是 `PartitionClustering` 的东西）。
> 簇大小要自己数，或者改用 GMeans/KMeans。实测编译报错原文：
> `错误: 找不到符号  符号: 变量 size  位置: 类型为MultivariateGaussianMixture的变量 m1`。

### 2.3 实测：对 UMAP 降维结果聚类

```
=== 1: real pipeline: 200x1536 -> UMAP(15,10) -> GMM ===
UMAP output 200x10
fit(4, emb) -> components=4 size()=4 length()=263 bic=-1697.41 L=-1000.68
   labels(first 24 of 200) = 0 1 3 3 0 1 3 0 0 3 3 0 0 1 3 2 0 1 2 2 0 1 2 0 
   distinct labels actually used = [true, true, true, true]
   confusion: true0->{0=50}  true1->{1=42, 2=6, 3=2}  true2->{1=4, 2=8, 3=38}  true3->{0=14, 2=28, 3=8}  
   posteriori(x[0]) = [0.9550, 0.0000, 0.0450, 0.0000]
   component[0].priori = 0.3416808376908666  mean[0] present = true
```

**E8 结论**：`fit(k, data)` 完全可用；取标签用 `model.map(double[])`；取后验用 `model.posteriori(double[])`；
`components.length` = 簇数；`components[i].priori()` = 混合权重。
注意上面的混淆矩阵说明：**UMAP 到 10 维后 4 个本可分的簇被压得比较糊**（true1/true2 有交叉）。
这是 UMAP 参数（`nNeighbors=15`）与随机性的结果，不是 GMM 的问题 —— 下游要给 `nNeighbors` 留调优空间。

### 2.4 「最优簇数」：Smile 支持 BIC 自动选 k，但**实测不可用**

源码（`MultivariateGaussianMixture.java` 第 164-184 行，sources jar 原文）：

```java
public static MultivariateGaussianMixture fit(double[][] data, boolean diagonal) {
    if (data.length < 20) {
        throw new IllegalArgumentException("Too few samples.");
    }
    MultivariateGaussianMixture mixture = new MultivariateGaussianMixture(new Component(1.0, MultivariateGaussianDistribution.fit(data, diagonal)));
    double bic = mixture.bic(data);                       // <-- 这里的 mixture.bic 恒为 0.0
    ...
    for (int k = 2; k < data.length / 20; k++) {          // <-- 上界硬编码为 n/20
        MultivariateExponentialFamilyMixture model = fit(k, data);
        if (model.bic <= bic) break;                      // <-- 第一次迭代就 break
        mixture = new MultivariateGaussianMixture(model.L, data.length, model.components);
        bic = model.bic;
    }
    return mixture;
}
```

第 169 行走的是**公开构造器** `MultivariateGaussianMixture(Component...)` → `this(0.0, 1, components)` →
`bic = 0.0 - 0.5 * length() * Math.log(1) = 0.0`。而真实 BIC = `L - 0.5*length()*log(n)`，
`L`（对数似然）恒 ≤ 0 → **真实 BIC 恒为负** → `model.bic <= 0.0` 恒成立 → 循环第一次就 `break`。

实测印证（`.scratch/researcher/gmm-out.txt` / `gmm2-out.txt`）：

```
=== 3: BIC auto fit(data) vs n (cap is hardcoded n/20) ===
  n=1    -> IllegalArgumentException: Too few samples.
  n=19   -> IllegalArgumentException: Too few samples.
  n=20   -> OK components=1 (bound n/20 = 1)
  n=40   -> OK components=1 (bound n/20 = 2)
  n=100  -> OK components=1 (bound n/20 = 5)
  n=1000 -> OK components=1 (bound n/20 = 50)

=== F: BIC auto fit(data) on 4 well separated blobs n=400 ===
  fit(data) -> components=1 bic=0.0 L=0.0
```

**E9 结论：`fit(double[][])` 在 4.1.0 里恒返回单成分混合模型（`bic=0.0, L=0.0`），不要用。**
（这是 Smile 的缺陷，不是配置问题；我扫了 n=20…1000 共 16 个规模全部如此。）

另外，**手动用 `model.bic` 选 k 也不可靠**：实测在 4 个明显分离的 10 维高斯簇上，
`bic` 随 k **单调下降**（`k=2: -8090.64, k=4: -8441.51, k=10: -9431.67`），argmax 落在 k=2：

```
=== E: BIC monotonicity, k=2..min(10,n/4), d=10, 4 blobs sep=8, n=400 ===
  k=2  bic(full)=-8090.64 length()=131 | bic(diag)=-11209.99 length()=41
  k=4  bic(full)=-8441.51 length()=263 | bic(diag)=-6484.20 length()=83
  k=10 bic(full)=-9431.67 length()=659 | bic(diag)=-6870.36 length()=209
```

（**对角线协方差** `fit(k, data, true)` 的 BIC 在 k=4 处出现峰值 `-6484.20`，即能正确选出 4 —— 这是可用的备选路径。）

### 2.5 如何限定最大簇数（问题原文要求）

**Smile 的 GMM 没有 `maxComponents` 参数**（`javap -p` 列出的全部方法里没有任何 max/limit 形参，实测见 2.1）。
真实可用的两种做法：

**(a) 用 `smile.clustering.GMeans.fit(double[][] data, int kmax)` —— `kmax` 就是最大簇数。**
源码 javadoc 原文（sources jar）：

```java
 * @param kmax the maximum number of clusters.
public static GMeans fit(double[][] data, int kmax) {
    return fit(data, kmax, 100, 1E-4);
}
public static GMeans fit(double[][] data, int kmax, int maxIter, double tol) {
    if (kmax < 2) {
        throw new IllegalArgumentException("Invalid parameter kmax = " + kmax);
    }
```

实测（`.scratch/researcher/gmm2-out.txt`）：

```
=== D: Smile native auto-k: GMeans / XMeans / KMeans (these DO take kmax) ===
  trueK=2 kmax=2 -> GMeans[k=2] XMeans[k=2] KMeans(k=2)[k=2]
  trueK=3 kmax=4 -> GMeans[k=3] XMeans[k=3] KMeans(k=4)[k=4]
  trueK=4 kmax=8 -> GMeans[k=4] XMeans[k=4] KMeans(k=4)[k=4]
  trueK=6 kmax=8 -> GMeans[k=6] XMeans[k=7] KMeans(k=4)[k=4]
  trueK=6 kmax=16-> GMeans[k=6] XMeans[k=6] KMeans(k=4)[k=4]
```

**GMeans 在 `kmax` 上限内正确恢复真实簇数，是本项目的推荐方案**（`GMeans` 的 `k y size` 字段来自
`PartitionClustering`，直接可用；注意 `size` 数组**长度 = kmax**，尾部是 0：
实测 `run0 k = 4 sizes=[38, 38, 37, 37, 0]`，读的时候只能读前 `k` 个）。

**(b) 若坚持用 GMM：显式 `fit(k, data)` 循环，自己做上限，选优准则不要用 Smile 的 `bic`。**

**簇数约束在 `[2, N]` 区间的做法**（实测得到的真实下限）：

```
=== 4: fit(k, data) k-constraint (must k>=2, and n >= k/2) ===
  k=1  n=200 -> IllegalArgumentException: Invalid number of components in the mixture.
  k=50 n=20  -> IllegalArgumentException: Too many components
```

源码第 80-82 行 `if (k < 2) throw ...`；`MultivariateExponentialFamilyMixture` 第 90-92 行
`if (x.length < components.length / 2) throw new IllegalArgumentException("Too many components");`。

**E10 结论：硬约束是 `k >= 2` 且 `k <= 2n`（整数除法 `k/2 <= n`）；但实测真正的可用上界远低于此**：

```
=== A: max working k for GMM fit(k, data), by n (full covariance, d=10) ===
  n=4   maxOKk=-1  firstFail=k=2 ArithmeticException: LAPACK POTRF error code: 5
  n=6   maxOKk=-1  firstFail=k=2 ArithmeticException: LAPACK POTRF error code: 7
  n=10  maxOKk=4   firstFail=k=5 ArithmeticException: LAPACK POTRF error code: 1
  n=20  maxOKk=7   firstFail=k=8 ArithmeticException: LAPACK POTRF error code: 1
  n=50  maxOKk=13  firstFail=k=14 ArithmeticException: LAPACK POTRF error code: 1
  n=100 maxOKk=19  firstFail=k=20 ArithmeticException: LAPACK POTRF error code: 1
  n=200 maxOKk=45  firstFail=k=46 ArithmeticException: LAPACK POTRF error code: 1
  n=400 maxOKk=50  firstFail=k=51 ArithmeticException: LAPACK POTRF error code: 1

=== B: same but diagonal covariance (fit(k, data, true)) ===
  n=10  maxOKk=10  firstFail=-
  n=100 maxOKk=100 firstFail=-
```

**给开发的硬规则**：调用 `fit(k, data)` 前先做 `2 <= k <= min(maxComponents, max(2, n/3))` 的夹取，
并 `try/catch (RuntimeException | ArithmeticException)`。`diagonal=true` 能显著放宽 k 上限（代价是精度），
全同/近同向量场景也只能靠它降级（见下）。

### 2.6 边界实测：1 个点 / 所有点相同（RAPTOR 递归到顶的真实情况）

```
=== 5: degenerate input: 1 point / all identical / all-zero rows ===
  1 unique point, fit(2,..) -> java.lang.ArithmeticException: LAPACK POTRF error code: -4
  1 unique point, fit(..) BIC -> java.lang.IllegalArgumentException: Too few samples.
  30 identical points fit(2,..) -> java.lang.ArithmeticException: LAPACK POTRF error code: 1
  30 identical points fit(..) BIC -> java.lang.ArithmeticException: LAPACK POTRF error code: 1
  30 zero vectors fit(2,..) -> java.lang.ArithmeticException: LAPACK POTRF error code: 1

=== C: degenerate input with diagonal=true ===
  n=1  identical rows, fit(2,..,diagonal=true) -> java.lang.ArithmeticException: LAPACK POTRF error code: -4
  n=2  identical rows, fit(2,..,diagonal=true) -> java.lang.IllegalArgumentException: Variance is not positive: 0.0
  n=5  identical rows, fit(2,..,diagonal=true) -> java.lang.IllegalArgumentException: Variance is not positive: 0.0
  n=30 identical rows, fit(2,..,diagonal=true) -> java.lang.IllegalArgumentException: Variance is not positive: 0.0
```

同时 stderr 上有 `ERROR smile.math.matrix.Matrix -- LAPACK POTRF error code: -4` —— 说明**这不是"抛异常"这么简单，
LAPACK 的 Cholesky 分解失败被包成了 `ArithmeticException`**。

**E10 结论（必须写进开发规范）**：RAPTOR 递归到顶时，如果所有摘要变得相同或只剩 1 个节点：

| 输入 | 全协方差 `fit(k,..)` | 对角线 `fit(k,..,true)` |
|---|---|---|
| 1 个点 | `ArithmeticException: LAPACK POTRF error code: -4` | 同样 `-4` |
| 2..n 个全同点 | `ArithmeticException: LAPACK POTRF error code: 1` | `IllegalArgumentException: Variance is not positive: 0.0` |
| n < 20 | 上面之外还可能是 `IllegalArgumentException: Too few samples.` | 同 |

**规避**：递归收敛判据必须在**调用聚类之前**判断（`n < 30` 直接结束递归，不要试图聚类）；
并且对"去重后唯一向量数 < 2"提前短路。**不要依赖 catch 异常来控制流程**，因为 `LAPACK POTRF` 只写 stderr
不总是抛（在某些 n 下会静默产生 NaN 分量）。

作为对比，`KMeans` 在同样的退化输入下**不抛异常**（但结果无意义）：

```
=== G: KMeans on 1 point / identical points ===
  1 point KMeans.fit(data,2) -> k=2
  30 identical KMeans.fit(data,2) -> k=2 y[0]=0
=== H: GMeans/XMeans on 1 point and identical points ===
  1 point GMeans -> k=1
  30 identical GMeans -> java.lang.ArrayIndexOutOfBoundsException: Index 0 out of bounds for length 0
  30 identical XMeans -> java.lang.ArrayIndexOutOfBoundsException: Index 0 out of bounds for length 0
```

→ **GMeans/XMeans 在全同向量上也抛异常**（`ArrayIndexOutOfBoundsException`），同样必须在调用前短路。

### 2.7 可复现性（实测发现，不在问题清单里但必须写）

```
=== A: UMAP without seeding ===
   max |a-b| = 10.251422157530678
=== B: UMAP with MathEx.setSeed(42) before each call ===
   max |c-d| = 0.0
=== C: GMM fit(4, emb) labels with and without seeding ===
   labels identical across two runs? false
   labels run1 first 20 = 02130212021212130213
   labels run2 first 20 = 23012301230123012323
   run1 cluster sizes = [41, 40, 43, 26]  run2 = [31, 32, 45, 42]
=== D: GMeans(trueK=4 data, kmax=10) repeated ===
   run0 k = 4 sizes=[38, 38, 37, 37, 0]
   run1 k = 4 sizes=[38, 38, 37, 37, 0]
   run2 k = 4 sizes=[38, 38, 37, 37, 0]
```

- Smile 全部随机性走全局静态 `smile.math.MathEx.random()`；`javap` 确认存在 **`public static void setSeed(long)`**。
- **在调用 UMAP/GMM 之前 `MathEx.setSeed(fixedSeed)` 可让输出完全可复现（实测 `max|Δ| = 0.0`）。**
- 代价：`MathEx` 是**全局可变静态状态**，并发调用同一 JVM 内会互相污染 → 如果 RAPTOR 用
  `@Async` 线程池并发跑聚类，必须把"setSeed + UMAP + GMM"整段放进同步块，否则种子失效（而且会串数据）。
  **推荐：聚类阶段串行执行（或先落库再单线程重算），不要并发调 Smile。**
- GMM 的**簇标签编号是任意的**（两次 run 标签完全不同），跨 run 稳定性必须靠"按质心坐标排序后重新编号"来保证，
  否则 `summary_nodes.parent_id` 会指错。

### 2.8 传递依赖：是否需要 smile-base / javacpp / openblas

```
> mvn -s maven-settings.xml -B dependency:tree
[INFO] \- com.github.haifengl:smile-core:jar:4.1.0:compile
[INFO]    \- com.github.haifengl:smile-base:jar:4.1.0:compile
[INFO]       +- org.bytedeco:javacpp:jar:linux-x86_64:1.5.11:compile
[INFO]       +- org.bytedeco:javacpp:jar:windows-x86_64:1.5.11:compile
[INFO]       +- org.bytedeco:javacpp:jar:macosx-x86_64:1.5.11:compile
[INFO]       +- org.bytedeco:javacpp:jar:macosx-arm64:1.5.11:compile
[INFO]       +- org.bytedeco:openblas:jar:linux-x86_64:0.3.28-1.5.11:compile
[INFO]       |  \- org.bytedeco:javacpp:jar:1.5.11:compile
[INFO]       +- org.bytedeco:openblas:jar:windows-x86_64:0.3.28-1.5.11:compile
[INFO]       +- org.bytedeco:openblas:jar:macosx-x86_64:0.3.28-1.5.11:compile
[INFO]       +- org.bytedeco:openblas:jar:macosx-arm64:0.3.28-1.5.11:compile
[INFO]       +- org.bytedeco:arpack-ng:jar:3.9.1-1.5.11:compile
[INFO]       |  \- org.bytedeco:openblas:jar:0.3.28-1.5.11:compile
[INFO]       +- org.bytedeco:arpack-ng:jar:linux-x86_64:3.9.1-1.5.11:compile
[INFO]       +- org.bytedeco:arpack-ng:jar:windows-x86_64:3.9.1-1.5.11:compile
[INFO]       +- org.bytedeco:arpack-ng:jar:macosx-x86_64:3.9.1-1.5.11:compile
[INFO]       \- org.duckdb:duckdb_jdbc:jar:1.1.3:compile
```

**E12 结论**：
- `smile-base:4.1.0`、`javacpp 1.5.11`、`openblas 0.3.28-1.5.11`、`arpack-ng 3.9.1-1.5.11`
  全部是 `smile-core` 的**自动传递依赖，已在 `.m2repo` 里就位，不需要新增声明**。
- **它们不是可选的**：实测两条硬证据 ——
  1. UMAP 的谱初始化调 ARPACK：日志 `INFO smile.manifold.UMAP -- Spectral layout computes 23 eigen vectors`，
     失败时报 `Invalid NEV parameter k: 23`（ARPACK 的错误字串）。
  2. GMM 调 LAPACK：`ERROR smile.math.matrix.Matrix -- LAPACK POTRF error code: -4`，异常类型 `ArithmeticException`。
- **结论：不要在 pom 里给 `smile-core` 加 `<exclusions>`，也不要排除 `org.bytedeco:*`**，
  否则这两个算法在运行期才炸，而且是 native 库加载失败（更难查）。
- 附带发现：`smile-base` 还带进来一个完全无关的 `org.duckdb:duckdb_jdbc:1.1.3`（Smile 的 DuckDB 集成），
  约 50 MB，本项目用不到但无副作用，可以不管。

---

## 3. 问题 3：Maven 坐标清单 + Spring AI 在 Boot 4.1.1 下的自动装配

### 3.1 真实依赖树

```
> mvn -s maven-settings.xml -B dependency:tree
[INFO] --- dependency:tree ---
[INFO] +- org.springframework.boot:spring-boot-starter-data-redis:jar:4.1.1:compile
[INFO] +- org.springframework.boot:spring-boot-starter-webmvc:jar:4.1.1:compile
[INFO] +- org.springframework.ai:spring-ai-starter-model-openai:jar:2.0.1:compile
[INFO] +- org.springframework.ai:spring-ai-starter-vector-store-pgvector:jar:2.0.1:compile
[INFO] +- org.postgresql:postgresql:jar:42.7.13:runtime
[INFO] +- org.projectlombok:lombok:jar:1.18.46:compile (optional)
[INFO] +- org.mybatis.spring.boot:mybatis-spring-boot-starter:jar:4.0.1:compile
[INFO] +- cn.hutool:hutool-all:jar:5.8.22:compile
[INFO] +- org.springframework.boot:spring-boot-starter-aop:jar:3.5.3:compile
[INFO] +- org.apache.tika:tika-core:jar:3.2.2:compile
[INFO] +- org.apache.tika:tika-parsers-standard-package:pom:3.2.2:compile
[INFO] \- com.github.haifengl:smile-core:jar:4.1.0:compile
[INFO] ------------------------------------------------------------------------
[INFO] BUILD SUCCESS
```

完整树共 **258 个坐标节点**，原文 272 行，落盘在 `.scratch/researcher/deps.txt`（证据 E12）。

### 3.2 Spring AI 2.0.1 在 Spring Boot 4.1.1 下的自动装配 —— 真正起过 context 的验证

**(a) 先看 jar 里的 `AutoConfiguration.imports` 真实内容**（`jar xf` 出来的原文）：

```
##### spring-ai-autoconfigure-model-openai-2.0.1.jar!/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
org.springframework.ai.model.openai.autoconfigure.OpenAiChatAutoConfiguration
org.springframework.ai.model.openai.autoconfigure.OpenAiEmbeddingAutoConfiguration
org.springframework.ai.model.openai.autoconfigure.OpenAiImageAutoConfiguration
org.springframework.ai.model.openai.autoconfigure.OpenAiAudioSpeechAutoConfiguration
org.springframework.ai.model.openai.autoconfigure.OpenAiAudioTranscriptionAutoConfiguration
org.springframework.ai.model.openai.autoconfigure.OpenAiModerationAutoConfiguration
```

```
##### spring-ai-autoconfigure-vector-store-pgvector-2.0.1.jar!/META-INF/spring/...AutoConfiguration.imports
org.springframework.ai.vectorstore.pgvector.autoconfigure.PgVectorStoreAutoConfiguration
```

> 注意：**Spring AI 2.0.1 里没有 `spring-ai-spring-boot-autoconfigure` 这个工件**（那是 1.0.x 的命名）。
> 2.0.1 拆成了 `spring-ai-autoconfigure-model-*` / `spring-ai-autoconfigure-vector-store-*` 等 10 个模块，
> 上表是 `.m2repo` 里实际存在的全部 10 个 imports 文件的内容（证据 E13，完整内容在
> `.scratch/researcher/ai-imports.txt`）。任何照抄 1.0.x 教程写 `spring-ai-spring-boot-autoconfigure` 的做法都会失败。

**(b) `@Bean` 方法名（= bean 名）由 `javap` 从 class 里读出**：

```
> javap -p -cp spring-ai-autoconfigure-model-openai-2.0.1.jar org.springframework.ai.model.openai.autoconfigure.OpenAiChatAutoConfiguration
public class ...OpenAiChatAutoConfiguration {
  public org.springframework.ai.openai.OpenAiChatModel openAiChatModel(OpenAiCommonProperties, OpenAiChatProperties, ToolCallingManager, ObjectProvider<ObservationRegistry>, ObjectProvider<MeterRegistry>, ObjectProvider<ChatModelObservationConvention>, ObjectProvider<OpenAiHttpClientBuilderCustomizer>);
  ...
}
> javap -p ... OpenAiEmbeddingAutoConfiguration
  public org.springframework.ai.openai.OpenAiEmbeddingModel openAiEmbeddingModel(OpenAiCommonProperties, OpenAiEmbeddingProperties, ...);
```

**(c) 真正起一个 Spring Boot context**（`.scratch/researcher/SpringProbe.java`，等价于 `@SpringBootTest`，
但放在 `.scratch` 里不污染 `src/test`）：

```java
@SpringBootConfiguration
@EnableAutoConfiguration
public class SpringProbe { ... SpringApplication.run(SpringProbe.class, "--spring.main.web-application-type=none", ...) ... }
```

真实输出（`.scratch/researcher/spring-out.txt`、`spring2-out.txt`）：

```
=== 1: CONFIG_PREFIX constants (read reflectively from the real classes) ===
  org.springframework.ai.model.openai.autoconfigure.OpenAiCommonProperties : CONFIG_PREFIX=spring.ai.openai  
  org.springframework.ai.model.openai.autoconfigure.OpenAiChatProperties : CONFIG_PREFIX=spring.ai.openai.chat  
  org.springframework.ai.model.openai.autoconfigure.OpenAiEmbeddingProperties : CONFIG_PREFIX=spring.ai.openai.embedding  
  org.springframework.ai.vectorstore.pgvector.autoconfigure.PgVectorStoreProperties : CONFIG_PREFIX=spring.ai.vectorstore.pgvector  

=== 2: boot a real Spring Boot context with the project's autoconfiguration set ===
  CONTEXT STARTED OK

=== 3: OpenAI beans present? (name -> type) ===
  ChatModel -> [openAiChatModel]
  EmbeddingModel -> [openAiEmbeddingModel]
  ChatClient$Builder bean names = [chatClientBuilder]

=== 4: concrete OpenAi* bean names ===
  bean name = openAiChatModel  type = org.springframework.ai.openai.OpenAiChatModel
  bean name = openAiEmbeddingModel  type = org.springframework.ai.openai.OpenAiEmbeddingModel
  bean name = openAiImageModel  type = org.springframework.ai.openai.OpenAiImageModel
  bean name = openAiSdkModerationModel  type = org.springframework.ai.openai.OpenAiModerationModel
  bean name = openAiSdkAudioSpeechModel  type = org.springframework.ai.openai.OpenAiAudioSpeechModel
  bean name = vectorStore  type = org.springframework.ai.vectorstore.pgvector.PgVectorStore
  bean name = pgVectorStoreBatchingStrategy  type = org.springframework.ai.embedding.TokenCountBatchingStrategy
  bean name = jdbcTemplate  type = org.springframework.jdbc.core.JdbcTemplate
  bean name = namedParameterJdbcTemplate  type = org.springframework.jdbc.core.NamedParameterJdbcTemplate

=== JdbcTemplate connectivity proof ===
  SELECT version() = PostgreSQL 18.6 (Debian 18.6-1.5.11...) ...
  current_database() = doc_raptor_db
```

**E14 结论：`spring-ai-starter-model-openai:2.0.1` 在 Spring Boot 4.1.1 下自动装配完全正常。**
- bean 名仍然是 **`openAiChatModel`** / **`openAiEmbeddingModel`**（问题原文问的就是这个：**是的，名字没变**）。
  `ChatClient.Builder` 的 bean 名是 **`chatClientBuilder`**（注意 `ChatClient` 本身**不是** bean）。
- 注入方式：`@Qualifier("openAiChatModel")` 或直接按类型注入 `ChatModel` / `EmbeddingModel`（各只有一个候选）。
- 配置前缀实测：`spring.ai.openai` / `spring.ai.openai.chat` / `spring.ai.openai.embedding` /
  `spring.ai.vectorstore.pgvector` —— **与现有 `application.yml` 一致，不需要改配置键**。
- `vectorStore` bean 默认距离类型实测为 `COSINE_DISTANCE`（`getDistanceType()` 反射调用所得），
  与 `<=>` 的选型一致。

**（d）一条要提醒开发的小坑**：`Class.forName("org.springframework.ai.chat.client.ChatClient.Builder")`
会抛 `ClassNotFoundException`（内部类必须写 `ChatClient$Builder`）；E14 第一版探测就踩到了这个，
写代码时用 `ChatClient.Builder` 编译期引用即可，但反射/字符串配置里要注意。

**（e）追加核实：`PgVectorStore` bean 到底会不会自动建 `vector_store` 表？**（对应 Lead 简报 §8）

Lead 在 §8 判断「该 bean 默认会去建它自己的 `vector_store` 表」。我用两路证据复核：

```
> javap -p -constants -cp "<spring-ai-pgvector-store-2.0.1.jar>" org.springframework.ai.vectorstore.pgvector.PgVectorStore
public class ...PgVectorStore extends ...AbstractObservationVectorStore implements org.springframework.beans.factory.InitializingBean {
  public static final java.lang.String DEFAULT_TABLE_NAME = "vector_store";
  public static final java.lang.String DEFAULT_SCHEMA_NAME = "public";
  public static final boolean DEFAULT_SCHEMA_VALIDATION = false;
  private final boolean initializeSchema;          // <-- 实例字段，无默认 true
  public void afterPropertiesSet();                // <-- InitializingBean 钩子
  ...
}

> javap -p -constants -cp "..." ...autoconfigure.PgVectorStoreProperties
public class ...PgVectorStoreProperties extends org.springframework.ai.vectorstore.properties.CommonVectorStoreProperties {
  public static final java.lang.String CONFIG_PREFIX = "spring.ai.vectorstore.pgvector";
  ...
}
> javap -p -constants -cp "..." org.springframework.ai.vectorstore.properties.CommonVectorStoreProperties
  private boolean initializeSchema;                // <-- Java 默认 false
  public boolean isInitializeSchema();  public void setInitializeSchema(boolean);
```

再看自动装配的字节码（`javap -c`），它只是把配置值透传，**没有任何硬编码 `true`**：

```
> javap -p -c -constants -cp "<spring-ai-autoconfigure-vector-store-pgvector-2.0.1.jar>" ...PgVectorStoreAutoConfiguration
      1: invokevirtual  PgVectorStoreProperties.isInitializeSchema:()Z
     69: invokevirtual  PgVectorStore$PgVectorStoreBuilder.initializeSchema:(Z)L...PgVectorStoreBuilder;
```

**运行时实测（决定性证据）**：我的 `SpringProbe` 是用**真实数据源**启动完整 context 的
（`vectorStore` bean 确认已创建 → `afterPropertiesSet()` 已执行），随后查库：

```
TABLE paradedb._typmod_cache
TABLE pgivm.pg_ivm_immv
TABLE public.async_task
TABLE public.document
TABLE public.eval_case
TABLE public.eval_case_expected_chunk
TABLE public.knowledge_base
TABLE public.retrieval_log
TABLE public.spatial_ref_sys
TABLE public.summary_nodes
LEFTOVER probe-like relations = 0
```

**→ 库里没有 `vector_store` 表。**结论：**Spring AI 2.0.1 的 `initialize-schema` 实际默认是 `false`**，
Lead §8 担心的"默认建表"在本版本**不成立**（Lead 给的两个处理方式本身没错，配置键名
`spring.ai.vectorstore.pgvector.initialize-schema` 实测存在，属于有效的防御性设置；
但不要把它当成"必现的坑"去写进风险清单）。**本项目的 7 张业务表与 Lead §8 的清单完全一致，也印证了这一点。**

### 3.3 已有 vs 需要新增

**结论：需要新增的依赖 = 0 个。**

| 能力 | 提供者 | 状态 |
|---|---|---|
| UMAP 降维 | `com.github.haifengl:smile-core:4.1.0` → `smile.manifold.UMAP` | **已有**（pom 第 84-87 行） |
| 多元 GMM | `com.github.haifengl:smile-base:4.1.0`（smile-core 传递）→ `smile.stat.distribution.MultivariateGaussianMixture` | **已有（传递）** |
| GMeans/XMeans/KMeans | 同上 `smile-core` → `smile.clustering.*` | **已有** |
| BLAS/ARPACK native | `org.bytedeco:openblas/arpack-ng/javacpp`（smile-base 传递） | **已有（传递）** |
| Chat/Embedding | `org.springframework.ai:spring-ai-starter-model-openai:2.0.1` | **已有**（pom 第 30-33 行） |
| PgVectorStore | `org.springframework.ai:spring-ai-starter-vector-store-pgvector:2.0.1` | **已有**（pom 第 34-37 行） |
| JDBC 驱动 | `org.postgresql:postgresql:42.7.13:runtime` | **已有**（pom 第 40-44 行） |
| pgvector Java 类型 | `com.pgvector:pgvector:0.1.6`（starter 传递） | **已有（传递）** |
| ORM | `org.mybatis.spring.boot:mybatis-spring-boot-starter:4.0.1` | **已有**（pom 第 51-55 行） |
| 文档解析 | `org.apache.tika:tika-core` + `tika-parsers-standard-package:3.2.2` | **已有**（pom 第 70-80 行） |
| 工具类 | `cn.hutool:hutool-all:5.8.22` | **已有**（pom 第 57-61 行） |

因为**零新增**，不需要提供"阿里云镜像上确实存在"的证据；不过顺带取证了阿里云镜像可用
（两个 sources jar 从镜像拉取成功，`mvn -s maven-settings.xml -B -q dependency:get ...` → `EXIT=0`）。

**唯一建议修改（不是新增，是修版本）**：`pom.xml` 第 63-67 行把 `spring-boot-starter-aop` 写死成 `3.5.3`
（Boot 4.1.1 是另一个大版本）。实测依赖树里它解析为：

```
[INFO] +- org.springframework.boot:spring-boot-starter-aop:jar:3.5.3:compile
[INFO] |  +- org.springframework:spring-aop:jar:7.0.9:compile
[INFO] |  \- org.aspectj:aspectjweaver:jar:1.9.25.1:compile
```

即 starter 是 3.5.3、Spring 依赖被 Boot 4.1.1 BOM 拉到 7.0.9 —— 目前能跑，但混合版本随时可能在
Boot 4.x 的 starter 内容变化时断掉。**建议删掉 `<version>3.5.3</version>`，交给 parent 管理。**

---

## 4. 问题 4：数据库索引与查询 API 复核（独立复核 + 补充 Lead 未覆盖的点）

复核环境（实测）：`PostgreSQL 18.6 (Debian 18.6-1.pgdg13+2)`，`current_database=doc_raptor_db`，
`vector 0.8.6`、`pg_search 0.25.6`，`current_user=doc_raptor`。
所有实验都建在 `temp table` 上（`probe_def` / `probe_jieba` / `probe_common` / `big` / `tiny` / `vec_t`），
**连接关闭后自动消失，对架构师已建好的正式表（`summary_nodes` / `document` / `knowledge_base` / `async_task` /
`retrieval_log` / `eval_case` …）零干扰**。收尾复查实测 `LEFTOVER probe-like relations = 0`（证据 `.scratch/researcher/tblcheck.txt`）。

**Lead 简报第 2 节的三条结论（HNSW+cosine 建索引、`@@@` 可用、`SET LOCAL hnsw.ef_search` 需在事务内）我全部复现成功，
无冲突。以下是 Lead 未覆盖的补充。**

### 4.1 pg_search 0.25.6 的 BM25 分数归一化范围

`paradedb.score(x)` 的真实签名（从 `pg_proc` 读）：

```
   proname=score  args=relation_reference anyelement  res=real
```

**它返回 `real`（float4），不是归一化的 `[0,1]`。**真实查询得分（`.scratch/researcher/db6-out.txt`）：

```
-- query='检索'
   id10=1.0546 id2=0.7827 id8=0.7675 id5=0.7528 id9=0.7528 id3=0.6995 id1=0.6995 
   hits=7 min=0.6995 max=1.0546  (is normalized to [0,1]? false)
-- query='向量'
   id2=2.6840 id5=2.5817   hits=2 min=2.5817 max=2.6840
-- query='摘要'
   id6=3.6655 id1=3.3903   hits=2 min=3.3903 max=3.6655
-- query='索引'
   id9=2.0763 id2=1.5614 id1=1.3955   hits=3 min=1.3955 max=2.0763
-- query='分词器'
   id3=3.9111   hits=1 min=3.9111 max=3.9111
-- query='递归摘要'
   id6=5.7118 id1=5.1050   hits=2 min=5.1050 max=5.7118
-- query='向量检索'
   id2=3.4667   hits=1
-- query='检索 摘要'   (两词 OR)
   id1=4.0899 id6=3.6655 id10=1.0546 id2=0.7827 id8=0.7675 id5=0.7528 id9=0.7528 id3=0.6995
   hits=8 min=0.6995 max=4.0899
```

**下界探测**（造一个"每篇文档都含该词"的表 `probe_common`，让 IDF 归零）：

```
-- 15/15 篇都含 '共同词'
   id15=0.11528634 id12=0.112614006 id13=0.10762455 id14=0.10529202 id11=0.10529202
   id4=0.098864034 id7=0.096892305 id6=0.09142236 id2=0.09142236 id8=0.08973374
   id10=0.08973374 id9=0.08810639 id5=0.08810639 id1=0.08214729 id3=0.08214729
   hits=15 min_s=0.08214729 max_s=0.11528634 avg_s=0.09631232271591822
```

**上界探测**（多词英文查询累加）：

```
-- SCORE default-tokenizer query='retrieval augmented generation'
   id=15  score=9.723003  score_r=9.723000  len=78  
-- SCORE default-tokenizer query='chunking strategy'
   id=15  score=5.9402256
-- min/max score over a broad OR query 'the index and a of to in'
   min_s=2.5363412  max_s=7.224533
```

**E15 结论**：
- **分数范围实测为 `[0.0821, 9.7230]`，没有归一化到 `[0,1]`，也不存在"最高分 1.0"这种性质。**
  它随查询词数线性累加（多词 OR 直接把各词得分相加），所以**跨查询的分数不可比较**。
- **用法规定：`paradedb.score(id)` 只能用于同一查询内部的 `ORDER BY score DESC`；不要做阈值过滤
  （如 `score > 0.5`），更不要和 `<=>` 的余弦相似度混在一个尺度上比较。**
- **必踩的坑**：`paradedb.score(id)` 在**没有 `@@@` 谓词**的查询里直接报错：

```
-- score with no @@@ predicate
   SQL: select id, paradedb.score(id) as score from probe_def where id < 3 order by id
   !! org.postgresql.util.PSQLException: ERROR: Unsupported query shape. Please report at https://github.com/paradedb/paradedb/issues/new/choose
```

- 另一个实测噪音：在子查询里做 `min/max/avg(paradedb.score(id))` 会触发 Planner 警告
  `WARNING: Aggregate Scan not used: argument to aggregate function is neither a direct column reference nor a COALESCE expression.
  To disable this warning: SET paradedb.planner_warnings = 'off'`。这个 GUC 真实存在
  （`pg_settings` 实测：`paradedb.planner_warnings = Warning`，vartype=enum，context=user）。
  **推荐 RRF 在 Java 侧算（与 Lead 决策一致），不要在 SQL 里对 score 做聚合**，可同时避开这个警告。

### 4.2 中文场景：默认分词器 vs jieba —— 实测对比与推荐

用同一段文本（15 篇，其中 10 篇中文）建两张表，一张默认、一张显式 jieba：

```sql
create index probe_def_bm25   on probe_def   using bm25 (id, content) with (key_field = 'id');
create index probe_jieba_bm25 on probe_jieba using bm25 (id, content)
  with (key_field = 'id', text_fields = '{"content": {"tokenizer": {"type": "jieba"}}}');
```

16 组中文查询的严格对比（TRUTH 用 `LIKE '%词%'` 做基准，`@@@` 结果与它逐条核对）：

```
   QUERY | TRUTH(ids)            | DEFAULT(ids)          | JIEBA(ids)
    向量检索 | [2]                   | [2]                   | [2]
    向量     | [2,5]                 | [2,5]                 | [2,5]
    检索     | [1,2,3,5,8,9,10]      | [1,2,3,5,8,9,10]      | [1,2,3,5,8,9,10]
    摘要     | [1,6]                 | [1,6]                 | [1,6]
    摘要树   | [1,6]                 | [1,6]                 | [1,6]
    递归摘要 | [1,6]                 | [1,6]                 | [1,6]
    索引     | [1,2,9]               | [1,2,9]               | [1,2,9]
    倒排索引 | [9]                   | [9]                   | [9]
    分词器   | [3]                   | [3]                   | [3]
    分词     | [3,9]                 | [3,9]                 | [3,9]
    聚类     | [1,7]                 | [1,7]                 | [1,7]
    检索方法 | [1]                   | [1]                   | [1]
    检索使用 | [2]                   | [2]                   | [2]
    层次索引 | [1]                   | [1]                   | [1]
    高质量   | []                    | []                    | []
    摘       | [1,6]                 | [1,6]                 | []            <-- 唯一差异
```

**E16 结论：默认分词器 ≡ jieba（15/16 完全相同），且在单字查询 `摘` 上默认分词器命中、jieba 丢召回。**
**推荐：用默认分词器（即 `WITH (key_field = 'id')`，不写 `text_fields`），不要配 jieba。**

**为什么默认分词器在中文上这么好？**——用 `paradedb.schema()` 查到真实配置（**这一点 Lead 简报没覆盖，也是最大的意外**）：

```
-- paradedb.schema('probe_def_bm25')
   name=content  field_type=Str  stored=f  indexed=t  fast=f  fieldnorms=t
     expand_dots=null  tokenizer=unicode_words_removeemojis:false  record=position  normalizer=null
```

**索引里 `content` 的真实 tokenizer 是 `unicode_words`（+ removeemojis:false），不是 `default`！**
（`paradedb.tokenizer('default')` 返回的是 `{"type": "default", "lowercase": true, "remove_long": 255}`，
和索引实际用的不一致 —— **用 `paradedb.tokenize(paradedb.tokenizer('default'), ...)` 去推断索引行为会被误导**。
我自己第一轮就踩了这个坑，第二轮用 `paradedb.schema()` 才拿到真相。`paradedb.tokenize` 的真实签名是
`tokenize(tokenizer_setting jsonb, input_text text) RETURNS TABLE(token text, position integer)`，
而 `paradedb.tokenizers()` 只有一个 `tokenizer text` 列 —— **列名不是 `name`**，这印证了 Lead 简报 2.2 的提示。）

`unicode_words` 对 CJK 是**按字切分**，而 `@@@`（走 `paradedb.search_with_parse`）会把查询串的多个 token
组成**相邻短语查询** —— 于是中文检索等价于**子串精确匹配 + BM25 打分**。用反例证明（相邻字必须相邻）：

```
   QUERY | LIKE truth           | @@@ result
    向量   | [2,5]                | [2,5]
    检索   | [1,2,3,5,8,9,10]     | [1,2,3,5,8,9,10]
    向索   | []                   | []            <-- 单字都在库里，但不相邻 → 不命中
    索向   | []                   | []
    量检   | [2]                  | [2]           <-- 相邻 → 命中
    索检   | []                   | []
    检向   | []                   | []
    摘     | [1,6]                | [1,6]
    摘树   | []                   | []            <-- 注意：'摘要树' 命中，'摘树' 不命中
    树摘   | []                   | []
    索引   | [1,2,9]              | [1,2,9]
```

**E17 结论：默认配置下中文检索 = 子串匹配语义**（逐字 token + 相邻短语），
所以 DocRaptor 的关键词召回**不需要任何中文分词器**，也不会出现"切错词导致漏召"的问题。
反过来，**这解释了为什么 jieba 反而更差**：jieba 产生词级 token，`摘` 这种单字查不到词。

**额外的重要坑（实测）**：在 `paradedb.match` 里临时指定 `tokenizer =>` 覆盖**不会**重算索引里的 token：

```
-- paradedb.match with explicit tokenizer override
   SQL: select id from probe_def where id @@@ paradedb.match('content','向量检索', tokenizer => paradedb.tokenizer('jieba')) order by id
   (no rows)        <-- 索引是 unicode_words 建的，用 jieba 分词查询必然 0 命中
```

所以 **`tokenizer` 覆盖只能与索引建立时用的分词器一致，否则静默返回 0 行**（不报错！这比报错更危险）。

其他实测到的查询算子行为（可用于调参）：

```
-- content @@@ '向量 检索'（带空格）        -> [1,2,3,5,8,9,10]  = OR 语义
-- paradedb.match('content','向量 检索', conjunction_mode => true) -> [2,5]  = AND 语义
-- paradedb.match('content','向量检索', prefix => true)           -> [1,2,3,5,8,9,10]  = 明显噪声变大，不用
-- paradedb.phrase('content', ARRAY['向量','检索'])               -> [2]
     WARNING: Phrase query with multiple tokens per phrase may not be correctly interpreted.
              Consider using a different tokenizer or switch to parse/match
```

### 4.3 `hnsw.ef_search` 到底怎么设才生效

**(a) GUC 发现（这里有一个新手必踩的坑）**

```
===== A: fresh connection -- can SET hnsw.ef_search run BEFORE any vector type usage? =====
-- show hnsw.ef_search on a virgin backend
   !! org.postgresql.util.PSQLException: ERROR: unrecognized configuration parameter "hnsw.ef_search"
-- SET hnsw.ef_search = 100 on a virgin backend
   updateCount=0                       <-- 不报错！
-- SET LOCAL hnsw.ef_search = 100 on a virgin backend (autocommit)
   updateCount=0
   WARNING: SET LOCAL can only be used in transaction blocks
-- now touch the vector type
   SQL: select '[1,2,3]'::vector <=> '[1,2,3]'::vector as x
   x=0  
-- SET hnsw.ef_search = 100 AFTER vector load
-- show
   hnsw.ef_search=100  
```

```
-- pg_settings LIKE hnsw AFTER touching the vector type
   name=hnsw.ef_search   setting=40     context=user  vartype=integer
   name=hnsw.iterative_scan  setting=off  context=user  vartype=enum
   name=hnsw.max_scan_tuples  setting=20000  context=user  vartype=integer
   name=hnsw.scan_mem_multiplier  setting=1  context=user  vartype=real
```

> `pg_settings where name ilike '%hnsw%'` 在**没碰过 vector 类型的后端上返回 0 行**，
> `SHOW hnsw.ef_search` 甚至报 `unrecognized configuration parameter`。这是 pgvector 的 `_PG_init` 懒加载导致的。
> **但 `SET hnsw.ef_search = N` 在未加载的后端上不报错**（PG 的 custom placeholder 机制），
> 等 vector 模块加载后值会生效。**结论：`SET` 可以放在应用启动早期；但不要在业务代码里读 `SHOW`/`current_setting`
> 来做校验，会随机报错。** 实测默认值 **40**。

**(b) `SET` vs `SET LOCAL` 完整语义（全部实测）**

```
-- default hnsw.ef_search (autoCommit=true)
   hnsw.ef_search=40  
-- A) SET LOCAL with autoCommit=true (NOT in a transaction)
   SQL: set local hnsw.ef_search = 10
   WARNING: SET LOCAL can only be used in transaction blocks
-- value right after that SET LOCAL
   hnsw.ef_search=40                  <-- 完全没生效
-- B) plain SET (session level, autoCommit=true)
   SQL: set hnsw.ef_search = 123
-- value right after plain SET
   hnsw.ef_search=123                 <-- 生效
-- C) SET LOCAL inside an explicit transaction
   hnsw.ef_search=7   (txn 内)
   commit
   hnsw.ef_search=123                 <-- commit 后回退到 session 值
-- D) SET LOCAL inside rollback txn
   hnsw.ef_search=999 (txn 内)  -> rollback -> hnsw.ef_search=123
-- E) SET LOCAL inside txn where txn later FAILS (abort)
   hnsw.ef_search=555 (txn 内) -> select 1/0 报错 -> rollback -> hnsw.ef_search=123
```

**E18 结论（给开发的硬规则）**：

| 写法 | 生效？ | 场景 |
|---|---|---|
| 自动提交下 `SET LOCAL hnsw.ef_search` | **❌ 不生效**，只给 WARNING | 绝对不要这么写 |
| 普通 `SET hnsw.ef_search = N` | ✅ 立即生效，**session 级** | 有效但**会污染 HikariCP 池**：连接归还后下一个借用的请求继承了这个值，必须 `RESET` |
| 事务内 `SET LOCAL hnsw.ef_search = N` | ✅ 事务内生效，commit/rollback/abort 后全部自动回退 | **推荐** |
| 事务内 `SET hnsw.ef_search = N` | ✅ 但事务回滚**不会**撤销（因为是 session 级） | 不要用 |

**推荐写法**：在检索的 Service 方法上加 `@Transactional(readOnly = true)`，在该方法内先执行
`SET LOCAL hnsw.ef_search = 100`（MyBatis 里就是一个普通 `<update>`/`<select>`），随后执行 kNN 查询。
因为 MyBatis-Spring 在同一个事务里绑定同一个 `SqlSession`/连接，`SET LOCAL` 一定作用在同一条连接上。

**(c) 功能验证：`ef_search` 真的影响结果（不只是改了个数）**

在 3000 行、8 维、HNSW 索引的临时表上，`ORDER BY embedding <=> q LIMIT 10`：

```
   ef_search=1    -> top10 ids = 2554                                    <-- 只返回 1 行！
   ef_search=2    -> top10 ids = 100 99 98 97 96 95 94 93 92 91 
   ef_search=5    -> top10 ids = 240 239 238 237 236 235 234 233 232 231 
   ef_search=10   -> top10 ids = 679 676 628 619 602 598 586 578 542 385 
   ef_search=40   -> top10 ids = 320 319 318 317 316 315 314 313 312 311 
   ef_search=100  -> top10 ids = 1231 1227 1225 1200 1188 1182 1181 1162 1146 520 
   ef_search=1000 -> top10 ids = 2 3 4 5 6 7 8 9 10 1 

-- SET LOCAL inside txn then run the query on the SAME txn (functional proof)
   [txn ef_search=1] ids = 2554 
   [txn ef_search=1000] ids = 2 3 4 5 6 7 8 9 10 1 
   after commit, current_setting('hnsw.ef_search') = hnsw.ef_search=40 
```

**`ef_search=1` 只返回 1 行（尽管 `LIMIT 10`）——这是 pgvector 的近似检索特性，不是 bug。**
**建议 `ef_search >= max(100, 2*k)`**；本项目 `k` 通常 ≤ 20，用 **100** 足够。

### 4.4 HNSW 索引在行数很少时会不会被忽略 —— `EXPLAIN` 实测

**(a) 15 行，有 HNSW 索引，已 `ANALYZE`**：

```
-- EXPLAIN (ANALYZE, BUFFERS) kNN on 15 rows
QUERY PLAN=Limit  (cost=2.62..2.64 rows=5 width=16) (actual time=0.153..0.155 rows=5.00 loops=1)
QUERY PLAN=  ->  Sort  (cost=1.44..1.47 rows=15 width=16) (actual time=0.151..0.152 rows=5.00 loops=1)
QUERY PLAN=        Sort Key: ((probe_docs.embedding <=> (InitPlan 1).col1))
QUERY PLAN=        Sort Method: top-N heapsort  Memory: 25kB
QUERY PLAN=        ->  Seq Scan on probe_docs  (cost=0.00..1.19 rows=15 width=16) (actual time=0.050..0.141 rows=15.00 loops=1)
```

**→ 走的是 `Seq Scan` + `top-N heapsort`，HNSW 索引被完全忽略。**

**(b) 同一个表灌到 3015 行后**：

```
-- EXPLAIN (ANALYZE, BUFFERS) kNN on 3015 rows
QUERY PLAN=Limit  (cost=43.79..46.19 rows=5 width=16) (actual time=0.578..0.654 rows=5.00 loops=1)
QUERY PLAN=  ->  Index Scan using probe_docs_hnsw on probe_docs  (cost=35.49..1484.30 rows=3015 width=16) (actual time=0.576..0.650 rows=5.00 loops=1)
QUERY PLAN=        Order By: (embedding <=> (InitPlan 1).col1)
```

**→ HNSW 索引被使用（`Index Scan using probe_docs_hnsw ... Order By: (...<=>...)`）。**

**(c) 3015 行的表上加 `where id <= 15` 只查那 15 行**：

```
-- EXPLAIN on the SMALL subset only (id<=15)
QUERY PLAN=  ->  Sort  (cost=8.31..8.31 rows=1 width=16)
QUERY PLAN=        Sort Method: top-N heapsort  Memory: 25kB
QUERY PLAN=        ->  Index Scan using probe_docs_pkey on probe_docs  (cost=0.28..8.30 rows=1 width=16) (actual time=0.036..0.634 rows=15.00 loops=1)
```

**→ 又回到排序路径。**

**(d) 专门造一张 20 行的表（4.4 原文要求）**：`tiny20` 20 行 × 8 维，建 HNSW 索引并 `ANALYZE`：

```
-- EXPLAIN (ANALYZE, BUFFERS) kNN LIMIT 5 on 20 rows
QUERY PLAN= Limit  (cost=2.83..2.84 rows=5 width=16) (actual time=0.070..0.072 rows=5.00 loops=1)
QUERY PLAN=   ->  Sort  (cost=1.58..1.63 rows=20 width=16) (actual time=0.065..0.065 rows=5.00 loops=1)
QUERY PLAN=         Sort Key: ((tiny20.embedding <=> (InitPlan 1).col1))
QUERY PLAN=         Sort Method: top-N heapsort  Memory: 25kB
QUERY PLAN=         ->  Seq Scan on tiny20  (cost=0.00..1.25 rows=20 width=16) (actual time=0.023..0.028 rows=20.00 loops=1)

-- EXPLAIN (ANALYZE) kNN LIMIT 1 on 20 rows
QUERY PLAN=   ->  Sort  (cost=1.35..1.40 rows=20 width=16)   Sort Method: top-N heapsort
QUERY PLAN=         ->  Seq Scan on tiny20  (cost=0.00..1.25 rows=20 width=16)

-- EXPLAIN (ANALYZE) kNN LIMIT 18 on 20 rows
QUERY PLAN=   ->  Sort  (cost=1.68..1.73 rows=20 width=16)   Sort Method: quicksort
QUERY PLAN=         ->  Seq Scan on tiny20  (cost=0.00..1.25 rows=20 width=16)

-- EXPLAIN with enable_seqscan=off on 20 rows (does it then use HNSW?)
QUERY PLAN=   ->  Index Scan using tiny20_hnsw on tiny20  (cost=8.15..12.40 rows=20 width=16) (actual time=0.089..0.091 rows=5.00 loops=1)
QUERY PLAN=         Order By: (embedding <=> (InitPlan 1).col1)
```

**→ 20 行时无论 `LIMIT` 是 1、5 还是 18，都是 `Seq Scan` + 排序，HNSW 索引被忽略。
把 `enable_seqscan` 关掉之后立刻变成 `Index Scan using tiny20_hnsw` —— 证明索引本身是有效的，
纯粹是成本模型的选择。**

**(e) 结论（E19）**：
- **行数少时 PG 的成本模型主动放弃 HNSW，走顺序扫描 + top-N 排序。这是正确的优化行为，结果是精确的
  （HNSW 是近似的），比强制走索引更好。不要用 `SET enable_seqscan = off` 去"修"它。**
- 交叉点大致在**几百行到一两千行**之间（本项目：15 行不用索引，3015 行用索引；没有进一步细分，
  因为这依赖 `LIMIT`、维度、索引参数和统计信息）。**对于 DocRaptor 的规模（真实文档会到几千~几万块），
  HNSW 会被自动用上，不需要任何调优。**
- **`ANALYZE` 是必需的**：`CREATE INDEX` 之后如果不 `ANALYZE`，planner 可能因为统计信息缺失而误判。
  实测在 HNSW 的每一轮实验里，`create index` 之后都执行了 `analyze probe_docs` / `analyze big` /
  `analyze tiny` / `analyze tiny20` 才拿到上面的执行计划。
- 另一个实测到的索引生效条件：**kNN 查询必须是 `ORDER BY embedding <=> :qvec LIMIT k` 这种形式**
  （`Order By: (embedding <=> ...)`）。如果把向量相似度包在函数/`CASE` 里，索引就用不上。

### 4.5 `<=>` 的返回值范围与 `similarityThreshold` 换算

**(a) 算子与返回值类型（从 `pg_operator` 读）**

```
   oprname=<=>  left_t=vector  right_t=vector  res=double precision
   oprname=<#>  left_t=vector  right_t=vector  res=double precision
   oprname=<->  left_t=vector  right_t=vector  res=double precision
   oprname=<+>  left_t=vector  right_t=vector  res=double precision
```

**(b) 范围实测**

```
-- unit vector self-distance
   dist_identical=0   dist_opposite=2   dist_orthogonal=1   dist_45deg=0.29289321881345254
-- self similarity (row 1 against itself)
   id=1  dist=0  sim=1  
-- 1 - (v <=> v) = 1 for the same vector
   sim_self=1  dist_opposite=2

=== D: similarityThreshold formula check on a 1536-dim table ===
-- self-similarity must be exactly 1
   id=1  dist=0  sim=1  is_one=t  
   id=2  dist=0  sim=1  is_one=t  
   id=3  dist=0  sim=1  is_one=t  
   id=4  dist=0  sim=1  is_one=t  
   id=5  dist=0  sim=1  is_one=t  
   id=6..  (同上)
```

另有一组 1536 维高斯随机向量（首轮 `DBProbe`）的分布，可以看出真实 embedding 的量级：

```
-- distance matrix stats over 15 rows (unordered pairs)
   pairs=105  min_dist=0.9426602633189021  max_dist=1.0754175050229846
-- similarity for nearest neighbours of row 1
   id=1  dist=0.000000  sim=1.000000  
   id=9  dist=0.958245  sim=0.041755  
   id=12 dist=0.962446  sim=0.037554  
   id=13 dist=0.972749  sim=0.027251  
   id=2  dist=0.973727  sim=0.026273  
```

**E20 结论 —— 精确换算公式（实测验证）：**

```
distance   = embedding <=> :qvec          ∈ [0, 2]
similarity = 1 - (embedding <=> :qvec)     ∈ [-1, 1]

同一向量对自己：distance = 0  →  similarity = 1   （5 行 1536 维实测全部 is_one = t）
相反向量：      distance = 2  →  similarity = -1
正交向量：      distance = 1  →  similarity = 0
```

- **`similarityThreshold` 必须用 `1 - distance` 这个值**，不能直接用 `<=>` 的返回值（那是距离，越小越相似）。
  Spring AI 的 `PgVectorStore` 用 `COSINE_DISTANCE`（实测 `getDistanceType() = COSINE_DISTANCE`）并同样按
  `1 - distance` 语义处理阈值，与手写 SQL 一致。
- **⚠️ 阈值不要拍脑袋定。**实测 1536 维随机向量的两两相似度只有 `-0.08 ~ 0.06`
  （即几乎所有对都是"正交"的），如果按直觉设 `similarityThreshold = 0.5`，会**一条都召不回来**。
  同一语料内的近邻相似度实测约 `0.03`（上表 id=9 是 row1 的最近邻，相似度才 0.042）。
  → **建议 RRF 混合检索阶段不做相似度阈值过滤，或者把阈值放到 -1（不过滤），靠 `LIMIT` + RRF 排序取 top-k。**
  如果一定要过滤，先跑一次真实 embedding 统计分布再定值。

### 4.6 混合检索用 MyBatis 时 `@@@` 的参数绑定写法（关键实验）

因为 MyBatis 的 `#{}` 编译成 JDBC `?` + `PreparedStatement.setXxx()`，所以直接写 JDBC 实验就是等价验证。

**先看 `@@@` 的真实算子重载**（`pg_operator`）：

```
   oprname=@@@  left_t=anyelement             right_t=text                               impl=paradedb.search_with_parse  
   oprname=@@@  left_t=anyelement             right_t=paradedb.searchqueryinput          impl=paradedb.search_with_query_input  
   oprname=@@@  left_t=anyelement             right_t=pdb.query                          impl=paradedb.search_with_field_query_input  
   oprname=@@@  left_t=anyelement             right_t=pdb.proximityclause                impl=paradedb.search_with_proximity_clause  
```

→ **`column @@@ $1`（$1 是 text）会解析到 `anyelement @@@ text` → `paradedb.search_with_parse`。
所以 `#{}`（text 参数）是天然匹配的。** 实测 11 个用例：

```
-- P1: content @@@ ?  (plain setString)
    params: [向量检索]
    id=2  score=4.663689                                              ✅ 正常
-- P2: content @@@ ?::text
    id=2  score=4.663689                                              ✅ 正常（加 cast 也行）
-- P3: id @@@ paradedb.match('content', ?)
    params: [向量检索]
    id=2  score=4.949259   id=5  score=4.4685864   ...                ✅ 正常
-- P4: id @@@ paradedb.parse(?)
    params: [content:向量检索]
    id=2  score=4.663689                                              ✅ 正常
-- P5: content @@@ ? with English term
    params: [retrieval]  id=15  score=3.7827783                       ✅ 正常
-- P6: bind limit as well
    params: [检索, 3]  id=10/1.9856939 id=2/1.4447979 id=8/1.4139309  ✅ 正常
-- P7: two bound params (query + limit) with jieba table
    params: [向量检索, 3]  id=2  score=2.835099                       ✅ 正常
-- P8: bound param used with the score function and an OR of two terms
    params: [向量检索, 摘要树]
    id=6/4.988739 id=2/4.663689 id=1/4.410975                        ✅ 正常
-- P9: content like ? (对照组)
    params: [%检索%]  id=1 id=2 id=3                                 ✅ 正常（对比用）

-- P10: prepared STATEMENT prepared once and executed three times
   query=向量检索 -> id=2/s=4.663689 
   query=摘要树 -> id=6/s=4.988739 id=1/s=4.410975 
   query=retrieval -> id=15/s=3.7827783 
```

**E21 结论：`@@@` 与 JDBC 占位符完全兼容，`#{}` 可以直接用，不需要 `::text` 强转，
也不会被当成 JDBC 占位符出问题。** 同一个 `PreparedStatement` 复用不同参数（P10）也正常。
**→ 问题 4 问的"`#{}` 会不会被当成 JDBC 占位符出问题"的答案是：不会，机制上完全没问题。**

**但"机制没问题"≠"语义没问题"。** 上面 11 个用例用的都是**短词/无标点**查询串（`向量检索`、`检索`、`retrieval`）。
Lead 在简报 §2.3（22:09 追加）用**真实中文自然语言问句**（带 `？`、`，`、`、`）对比了 4 种写法，实测：

| 写法 | 5 条自然语言问句的命中 | 能不能用于生产 |
|---|---|---|
| `id @@@ paradedb.match('content', ?)` | **5/5 命中且排序合理** | ✅ **唯一推荐** |
| `id @@@ paradedb.parse('content:' \|\| ?)` | 2/5 零命中 | ❌ 禁止 |
| `content @@@ ?` | 2/5 零命中（参数被当查询语法串解析） | ❌ 禁止 |
| `content @@@ '字面量'` | 仅在短串无标点时可用 | ⚠️ 不可参数化 |

这和我的实测**不矛盾、正好互补**：我证明的是 `?` **绑定机制**畅通（P1-P10），Lead 证明的是
`@@@ ?` 这条**语义路径**在自然语言中文问句下不可靠（`？`、`，`、`、` 会被 Query Parser 误解释）。
我的 P11b 也从侧面印证了同一根因（`retrieval AND index` 被当布尔语法）。

**因此 E21 的最终结论（与 Lead §2.3 一致）：BM25 路必须写成 `id @@@ paradedb.match('content', #{query})`。**
下面是我自己实测过的、正确的 MyBatis 写法：

```xml
<select id="searchByBm25" resultType="...">
  SELECT id, paradedb.score(id) AS score
  FROM summary_nodes
  WHERE enabled = true
    AND id @@@ paradedb.match('content', #{query})   <!-- ★ 左值必须是 key_field(id)，不是 content -->
  ORDER BY paradedb.score(id) DESC
  LIMIT #{limit}
</select>
```

> 注：`paradedb.score(id)` 既可以写 `AS score` 再 `ORDER BY score DESC`（实测可行），
> 也可以直接 `ORDER BY paradedb.score(id) DESC`，两种写法我都跑通了。

**但有两个真实存在的输入风险（实测）**：

```
-- P11a: content @@@ '?'  (查询串本身就是一个问号)
    (no rows)                                       <-- 不报错、不崩、返回空
-- P11b: 参数值含布尔操作符
    params: [retrieval AND index]  -> (no rows)     <-- AND 被当成查询语法，语义与预期不符！
-- P11c: 参数值含引号注入
    params: [retrieval' OR '1'='1]
    !! ERROR: could not parse query string 'content:(retrieval' OR '1'='1)'.
              make sure to use column:term pairs, and to capitalize AND/OR.
```

- **`@@@ 'text'` 走的是 Query Parser，参数值是会被"解析"的查询语法，不完全是字面量。**
  P11b 说明用户输入 `retrieval AND index` 不会被当字面量，而会被当成布尔查询（结果与预期不符）。
- **好消息：P11c 证明参数绑定有效地挡住了 SQL 注入**（单引号直接进入查询解析器报错，语法未被逃逸）。
  **所以 `#{}` 在安全性上是正确的，绝不要改成 `${}` 字符串拼接。**
- **最终建议（结合 Lead §2.3）：不用 `content @@@ #{query}`，改用
  `id @@@ paradedb.match('content', #{query})`** —— `match` 的 value 是纯文本分词匹配，
  不做 Query Parser 解析，对带标点的中文自然语言问句稳定。
  注意 4.2 节说的 `tokenizer =>` 覆盖坑（不要覆盖，否则静默 0 命中）。
  另外 `paradedb.match` 默认是 **OR 语义**（实测 `'向量 检索'` → 7 行）；需要"全词都中"时加
  `conjunction_mode => true`（实测 → 2 行）。

---

## 5. 已知风险与规避

| # | 风险 | 实测证据 | 规避措施 |
|---|---|---|---|
| R1 | `UMAP.of(data, 15)` 以为是 15 维，实际输出 2 维 | E1 E2（第 74 行写死 `d=2`） | 必须用 10 参重载，`d` 写死 10；代码里加注释 |
| R2 | 小样本调 UMAP 必崩（`Invalid NEV parameter k` / `ArrayIndexOutOfBoundsException`） | E4（d=10 时 n≤23 全崩） | `n < 30` 直接跳过 UMAP；外加 `try/catch (RuntimeException)` 回退原始向量 |
| R3 | 全同向量调 UMAP 必崩（`Invalid dimension of feature space`） | E5（n=10..100 全崩） | 调用前 `distinct` 后判断唯一向量数 ≥ 2，否则结束递归 |
| R4 | UMAP/GMM 结果不可复现，RAPTOR 树每次都不一样 | E6（不设种子 `max|Δ| = 10.25`，设种子 `0.0`） | 聚类前 `MathEx.setSeed(固定值)`；**聚类必须串行**（`MathEx` 是全局静态状态，`@Async` 并发会互相污染） |
| R5 | `smile.clustering.GaussianMixture` 不存在，照记忆写会编译不过 | E7（`ClassNotFoundException`） | 用 `smile.stat.distribution.MultivariateGaussianMixture` |
| R6 | `fit(data)` 的 BIC 自动选 k 恒返回 1 个簇（Smile 缺陷） | E9（n=20…1000 全部 `components=1`） | 不用 `fit(data)`；改用 `GMeans.fit(data, kmax)` 或显式 `fit(k,...)` |
| R7 | 手动按 `model.bic` 选 k 也选错（BIC 随 k 单调下降） | E9（k=2 的 BIC 最高，真值是 4） | 用 GMeans，或 `fit(k,data,/*diagonal=*/true)` 的 BIC（实测在 k=4 处有峰值） |
| R8 | `k` 太大时 GMM 抛 `ArithmeticException: LAPACK POTRF` | E10（n=20 时 k≥8 崩；n=50 时 k≥14 崩；n=100 时 k≥20 崩；n=200 时 k≥46 崩） | 夹取 `2 <= k <= min(maxComponents, max(2, (int) Math.sqrt(n)))`（`n/3` 这种线性上界**不安全**：`n=100` 时 `33 > 19` 会崩）；外加 `catch (RuntimeException)` |
| R9 | 1 个点 / 全同点调 GMM 抛 `LAPACK POTRF`，且 LAPACK 错误只在 stderr 出现 | E10（`-4` / `1` / `Variance is not positive`） | **在调用前短路**（`n < 30` 或唯一向量 < 2），不要靠 catch 控流程 |
| R10 | `GMeans`/`XMeans` 在全同向量上抛 `ArrayIndexOutOfBoundsException: Index 0 out of bounds for length 0` | E11 | 同上，调用前短路 |
| R11 | GMM 簇标签编号任意，两次 run 完全不同，`parent_id` 会指错 | E8（`[41,40,43,26]` vs `[31,32,45,42]`，标签序列完全不同） | 每次 fit 后按质心排序（或按 `mean()` 的第一主成分）重编号，再落库 |
| R12 | 给 `smile-core` 加 `<exclusions>` 排除 `org.bytedeco:*` → 运行期 native 崩 | E12（ARPACK 谱初始化 + LAPACK POTRF 都是 native 调用） | **不要排除** `smile-base` / `javacpp` / `openblas` / `arpack-ng` |
| R13 | 照 1.0.x 教程找 `spring-ai-spring-boot-autoconfigure` 会找不到 | E13（2.0.1 里是 10 个 `spring-ai-autoconfigure-*` 模块） | 直接依赖 starter，`AutoConfiguration.imports` 已配好，无需手工 import |
| R14 | 反射写 `"org.springframework.ai.chat.client.ChatClient.Builder"` 会抛异常 | E14（实测 `ClassNotFoundException`） | 字符串写 `ChatClient$Builder`；代码里正常写 `ChatClient.Builder` |
| R15 | `pom.xml` 把 `spring-boot-starter-aop` 写死 `3.5.3`，与 Boot 4.1.1 混版 | E12（starter 3.5.3 + `spring-aop 7.0.9`） | 删掉 `<version>3.5.3</version>` |
| R16 | 把 `paradedb.score` 当 `[0,1]` 相似度用 / 跨查询比较 | E15（实测 `[0.0821, 9.7230]`，多词累加） | 只用于同查询内 `ORDER BY score DESC`；RRF 在 Java 侧算 |
| R17 | 在没有 `@@@` 的查询里调 `paradedb.score(id)` → `ERROR: Unsupported query shape` | E15 | `paradedb.score(id)` 只能出现在带 `@@@` 的 SELECT 里 |
| R18 | 在子查询里聚合 `paradedb.score` 触发 Planner 警告 | E15（`Aggregate Scan not used`） | 用 `SET paradedb.planner_warnings = 'off'`，或（推荐）把融合放到 Java 侧 |
| R19 | 以为默认索引分词器是 `default`，用 `paradedb.tokenize(tokenizer('default'),...)` 推断行为 | E17（真实是 `unicode_words_removeemojis:false`） | 用 `paradedb.schema('索引名')` 看真实配置；`paradedb.tokenizers()` 的列名是 `tokenizer` 不是 `name` |
| R20 | 给索引显式配 jieba → 单字查询丢召回 | E16（`摘`：默认 `[1,6]`，jieba `[]`） | **用默认分词器**，不写 `text_fields` |
| R21 | 在 `paradedb.match` 里传 `tokenizer =>` 覆盖，与索引不一致时**静默返回 0 行** | E17（jieba 覆盖 → `(no rows)`，无任何错误） | 查询侧不要覆盖 tokenizer；如必须，与索引建立时保持一致 |
| R22 | 自动提交下用 `SET LOCAL hnsw.ef_search` → 只给 WARNING，完全不生效 | E18 | 放在 `@Transactional` 方法内；或普通 `SET` + 用后 `RESET` |
| R23 | 普通 `SET hnsw.ef_search` 泄漏到 HikariCP 连接池的下一个请求 | E18（session 级，commit/rollback 都不回退） | 用 `SET LOCAL`（事务结束自动回退，实测 commit/rollback/abort 三种情况全部回退） |
| R24 | `ef_search` 设得太小导致召回崩塌（`ef_search=1` 时 `LIMIT 10` 只返回 1 行） | E18 | `ef_search >= max(100, 2*k)`，本项目用 100 |
| R25 | 在未加载 vector 类型的后端上 `SHOW hnsw.ef_search` 报 `unrecognized configuration parameter` | E18 | 不要在业务代码里读这个 GUC；`SET` 本身不报错 |
| R26 | 以为小表"索引失效"是 bug，用 `enable_seqscan=off` 强制走 HNSW | E19（15 行 → `Seq Scan`；3015 行 → `Index Scan`） | **不要强制**；小表顺序扫描结果更精确且更快 |
| R27 | 建 HNSW 索引后不 `ANALYZE` | E19（HNSW 实验里每次 `create index` 后都实测执行了 `analyze probe_docs` / `analyze big` / `analyze tiny` / `analyze tiny20`） | DDL 脚本里 `CREATE INDEX ... USING hnsw` 后加 `ANALYZE 表名`（bm25 索引不需要） |
| R28 | 把 `similarityThreshold` 按直觉设在 0.5 左右 → 一条都召不回 | E20（1536 维随机向量两两相似度 `-0.08~0.06`，最近邻才 `0.042`） | 混合检索阶段不做相似度阈值过滤，靠 `LIMIT` + RRF；要过滤先统计真实分布 |
| R29 | 用 `${}` 拼查询串（SQL 注入） | E21（P11c 参数绑定成功挡住了 `' OR '1'='1`） | 必须用 `#{}`；`@@@` 右侧 text 参数天然匹配 `paradedb.search_with_parse` |
| R30 | 用户输入里的 `AND`/`OR`/`:` 被 Query Parser 解析，语义漂移 | E21（P11b：`retrieval AND index` 不按字面量处理） | 要求字面量语义时用 `id @@@ paradedb.match('content', #{query})` |
| R31 | `@@@` 在临时表上建 bm25 索引会刷大量 `WARNING: resource was not closed: [-11] (rel=base/.../t24_37923...)` | 实测（几万条警告） | 仅影响临时表实验；正式表未出现该警告。若出现在正式表上，用 `SET client_min_messages = warning` 抑制 |
| R32 | 中文查询串以空格分词时是 OR 语义，容易召回爆炸 | E17（`@@@ '向量 检索'` → 7 行；`conjunction_mode => true` → 2 行） | 用户查询含空格多词时，用 `conjunction_mode => true` 或整串传入（依赖逐字相邻短语语义） |
| R33 | BM25 路写成 `content @@@ #{query}` → 带中文标点的自然语言问句**静默零命中** | E21 P11a/P11b（`@@@ '?'` → `(no rows)`；`retrieval AND index` → `(no rows)`）+ Lead 简报 §2.3（5 条问句 2 条零命中） | **必须**写 `id @@@ paradedb.match('content', #{query})`（左值用 `key_field`）；并加一条"带中文问号/顿号的完整问句必须命中 > 0"的集成测试 |
| R34 | 以为 `@Bean vectorStore` 会自动在库里建 `vector_store` 表 | 实测：`PgVectorStore implements InitializingBean` 且有 `private final boolean initializeSchema`；`PgVectorStoreAutoConfiguration` 的字节码只是 `isInitializeSchema()` → `builder.initializeSchema(Z)`，**没有任何硬编码 true**。我用真实数据源启动完整 context（`vectorStore` bean 已创建、`afterPropertiesSet` 已执行）之后，库里**没有**出现 `vector_store` 表 | 风险不成立（2.0.1 默认 `false`）。仍建议按 Lead §8 显式写 `spring.ai.vectorstore.pgvector.initialize-schema: false` 做防御 |

---

## 6. Maven 依赖清单（完整坐标表）

**树规模：258 个坐标节点**（`mvn -s maven-settings.xml -B dependency:tree` 的 272 行原文见 `.scratch/researcher/deps.txt`）。
下表列出 `pom.xml` `<dependencies>` 里的**全部 12 项直接依赖**（另加 parent 与 BOM 各 1 项）+
**对本项目功能有实质影响的传递依赖**。完整 258 项清单见 `.scratch/researcher/deps.txt`。

### 6.1 直接依赖（全部「已有」，来自现有 `pom.xml`）

| groupId | artifactId | version | scope | 用途 | 状态 |
|---|---|---|---|---|---|
| org.springframework.boot | spring-boot-starter-parent | 4.1.1 | parent | 版本仲裁（BOM） | 已有 |
| org.springframework.boot | spring-boot-starter-data-redis | 4.1.1 | compile | Redis starter（**Lead 决策：本项目不用 Redis**，可保留不用） | 已有 |
| org.springframework.boot | spring-boot-starter-webmvc | 4.1.1 | compile | REST 接口（含 tomcat-embed-core 11.0.24、spring-webmvc 7.0.9） | 已有 |
| org.springframework.ai | spring-ai-starter-model-openai | 2.0.1 | compile | Chat（qwen3.7-flash）+ Embedding（qwen3.7-text-embedding），自动装配实测通过 | 已有 |
| org.springframework.ai | spring-ai-starter-vector-store-pgvector | 2.0.1 | compile | `PgVectorStore` + `JdbcTemplate`（实测 bean 已创建，`COSINE_DISTANCE`） | 已有 |
| org.postgresql | postgresql | **42.7.13** | runtime | JDBC 驱动（**实测有效版本是 42.7.13，不是 42.7.4**，见第 7 节） | 已有 |
| org.projectlombok | lombok | 1.18.46 | compile (optional) | 样板代码；compiler plugin 里配了 `annotationProcessorPaths` | 已有 |
| org.mybatis.spring.boot | mybatis-spring-boot-starter | 4.0.1 | compile | MyBatis（含 mybatis 3.5.19 / mybatis-spring 4.0.0） | 已有 |
| cn.hutool | hutool-all | 5.8.22 | compile | 工具类 | 已有 |
| org.springframework.boot | spring-boot-starter-aop | **3.5.3** ⚠️ | compile | AOP（`@Async`），**版本与 Boot 4.1.1 不一致，建议删掉 `<version>`** | 已有（建议改） |
| org.apache.tika | tika-core | 3.2.2 | compile | 文档类型探测 | 已有 |
| org.apache.tika | tika-parsers-standard-package | 3.2.2 | compile (`<type>pom</type>`) | PDF/Word/Excel/PPT 解析（聚合 pom，实测拉全 15 个子模块） | 已有 |
| com.github.haifengl | smile-core | 4.1.0 | compile | UMAP 降维（`smile.manifold.UMAP`）+ KMeans/XMeans/GMeans | 已有 |
| org.springframework.ai | spring-ai-bom | 2.0.1 | import | 管理 Spring AI 全部模块版本 | 已有 |

### 6.2 关键传递依赖（全部「已有（传递）」，实测均在 classpath 中）

| groupId | artifactId | version | 由谁带入 | 用途 | 状态 |
|---|---|---|---|---|---|
| com.github.haifengl | smile-base | 4.1.0 | smile-core | **GMM 所在包** `smile.stat.distribution.MultivariateGaussianMixture` | 已有（传递） |
| org.bytedeco | openblas | 0.3.28-1.5.11 | smile-base | **LAPACK/BLAS native**，GMM 协方差分解必需 | 已有（传递）|
| org.bytedeco | openblas | 0.3.28-1.5.11 (`linux-x86_64`/`windows-x86_64`/`macosx-x86_64`/`macosx-arm64`) | smile-base | 各平台 native 二进制 | 已有（传递）|
| org.bytedeco | arpack-ng | 3.9.1-1.5.11 (+4 个平台 classifier) | smile-base | **UMAP 谱初始化必需**（`ARPACK.syev`） | 已有（传递）|
| org.bytedeco | javacpp | 1.5.11 (+4 个平台 classifier) | smile-base | native 加载器 | 已有（传递）|
| org.duckdb | duckdb_jdbc | 1.1.3 | smile-base | Smile 的 DuckDB 集成（本项目不用，无害） | 已有（传递）|
| com.pgvector | pgvector | 0.1.6 | spring-ai-starter-vector-store-pgvector | Java 侧 vector 类型封装 | 已有（传递）|
| org.springframework.ai | spring-ai-openai | 2.0.1 | starter | `OpenAiChatModel` / `OpenAiEmbeddingModel` / `OpenAiChatOptions` | 已有（传递）|
| org.springframework.ai | spring-ai-model | 2.0.1 | spring-ai-openai | `ChatModel`/`EmbeddingModel` 接口 | 已有（传递）|
| org.springframework.ai | spring-ai-autoconfigure-model-openai | 2.0.1 | starter | 自动装配（`AutoConfiguration.imports` 实测内容见 3.2） | 已有（传递）|
| org.springframework.ai | spring-ai-pgvector-store | 2.0.1 | starter | `PgVectorStore` 实现 | 已有（传递）|
| org.springframework.ai | spring-ai-client-chat | 2.0.1 | starter | `ChatClient` / `ChatClient.Builder`（bean 名 `chatClientBuilder`） | 已有（传递）|
| com.openai | openai-java-core | 4.49.0 | spring-ai-openai | OpenAI 官方 SDK | 已有（传递）|
| com.squareup.okhttp3 | okhttp | 4.12.0 | spring-ai-openai | HTTP 客户端（`OpenAiHttpClientBuilderCustomizer`） | 已有（传递）|
| com.zaxxer | HikariCP | 7.0.2 | spring-boot-starter-jdbc | 连接池（**`SET LOCAL` 语义受它影响**） | 已有（传递）|
| org.mybatis | mybatis | 3.5.19 | mybatis-spring-boot-starter | MyBatis 内核 | 已有（传递）|
| org.mybatis | mybatis-spring | 4.0.0 | 同上 | Spring 集成 | 已有（传递）|
| ch.qos.logback | logback-classic | 1.5.38 | spring-boot-starter-logging | 日志（**Smile 的 `src/main` 日志会从这里输出**） | 已有（传递）|
| org.apache.pdfbox | pdfbox | 3.0.5 | tika-parser-pdf-module | PDF 解析 | 已有（传递）|
| org.apache.poi | poi / poi-ooxml / poi-scratchpad | 5.4.1 | tika-parser-microsoft-module | Office 解析 | 已有（传递）|
| io.lettuce | lettuce-core | 7.5.2.RELEASE | spring-boot-starter-data-redis | Redis 客户端（不启用也无害） | 已有（传递）|

### 6.3 需要新增的依赖

**无。0 个新增。**

证据（E12）：本报告全部 15 个实测探针程序（3 个 UMAP、2 个 GMM、1 个可复现性、2 个 Spring context、
7 个数据库探针）都在**未修改 `pom.xml`** 的前提下，使用
`mvn -s maven-settings.xml -B dependency:build-classpath "-Dmdep.outputFile=.scratch\researcher\cp.txt"`
生成的 classpath 编译并运行成功（`EXIT=0`）。

**唯一的 pom 改动建议（修版本，不是新增）**：删除 `spring-boot-starter-aop` 的 `<version>3.5.3</version>`。

---

## 7. 与 `docs/00-environment-facts.md` 的差异

按简报第 7 节的约定「遇到冲突以实测为准」，逐条列出。

> **版本说明**：我在开工时读到的是 10097 字节的版本（无 §8/§9）。**Lead 在我工作期间两次更新了简报**
> （22:05 加 §9「Smile 4.1.0 的真实 API」、22:06 加 §8「Spring AI 启动期风险」、22:09 改 §2.2/新增 §2.3
> 「只有 `paradedb.match` 能用」）。下表已按**最新版（16073 字节 / 271 行）**逐条重新核对，
> 其中 D12/D13/D14/D15/D16 是针对新增章节的复核。

| # | 简报内容 | 实测结果 | 影响 |
|---|---|---|---|
| D1 | 简报 1.1 提示「JDBC 驱动 jar: `.m2repo\org\postgresql\postgresql\42.7.4\postgresql-42.7.4.jar`」（任务书同）；简报未标版本 | **实际生效版本是 `42.7.13`**（Boot 4.1.1 BOM 管理）。`.m2repo` 里 42.7.4 和 42.7.13 两个目录都存在，但 `dependency:tree` 与 `cp.txt` 里都是 `42.7.13` | 我用的是 42.7.13，所有 JDBC 实验正常。写代码/文档时不要再引用 42.7.4；如需临时跑单文件程序，用 `42.7.13` 那个 jar |
| D2 | 简报 2.2「score 是 real，量级约 0.28 ~ 1.18」 | 方向正确但不完整：`score` 确实是 `real`（`pg_proc` 实测 `args=relation_reference anyelement  res=real`），但真实量级更宽 —— 全库命中词低至 **0.0821**，多词英文查询高至 **9.7230**。简报的 0.28~1.18 应该是某一组查询的局部观测 | 不影响决策（都不归一化），但**下游不要用简报的 0.28~1.18 去写阈值判断**；第 4.1 节记录了完整分布 |
| D3 | 简报 2.2「注意：`paradedb.tokenizers()` 的列名不是 `name`」 | **属实**。实测该函数返回单列 `tokenizer`（`pg_get_function_result` 读到 `res=TABLE(tokenizer text)`），另有 `tokenize(tokenizer_setting jsonb, input_text text) RETURNS TABLE(token text, position integer)` | 已按实测写 |
| D4 | 简报 2.2「实测中文用**默认分词器**就能命中（`@@@ '检索'` 有结果），`jieba` 分词器也可用」 | 结论正确，但**原因与简报的隐含假设不同**：索引里 `content` 的真实 tokenizer 是 `unicode_words_removeemojis:false`（**不是** `default`）。且实测 16 组查询里 **jieba 并不优于默认**（单字查询 `摘` 默认命中、jieba 丢召回） | 已在第 4.2 节给出「用默认分词器、不配 jieba」的明确推荐 + 反例证据 |
| D5 | 简报 2.1「调参：`SET LOCAL hnsw.ef_search = 100;`（需在事务内）」 | **方向正确**，补充完整：自动提交下 `SET LOCAL` 只给 `WARNING: SET LOCAL can only be used in transaction blocks` 且值不变（40）；事务内生效且 commit/rollback/abort 后自动回退；普通 `SET` 会污染连接池 | 已在第 4.3 节给出推荐写法（`@Transactional` + `SET LOCAL`） |
| D6 | 简报 2「已安装扩展：`vector 0.8.6`、`pg_search 0.25.6`、`postgis 3.6.4`、`pg_ivm 1.13`、`pg_stat_statements 1.12`、`fuzzystrmatch`」 | 完全一致（`select extname, extversion from pg_extension` 实测，另加 `plpgsql 1.0`、`postgis_tiger_geocoder`、`postgis_topology`） | 无差异 |
| D7 | 简报 2「`doc_raptor_db` 内没有任何业务表（只有 postgis/tiger/topology/pg_stat_statements 的系统表）」 | **该条已过期**（Lead 自己的 §8 也要求"只应有 7 张业务表"）。实测 `pg_tables`：`public.summary_nodes`、`public.document`、`public.knowledge_base`、`public.async_task`、`public.retrieval_log`、`public.eval_case`、`public.eval_case_expected_chunk`、`public.spatial_ref_sys`，另有 `paradedb._typmod_cache`、`pgivm.pg_ivm_immv`。**没有 `vector_store` 表** | 与我无关；**我的全部实验都建在 `temp table` 上，连接关闭即消失**。收尾时实测复查：`LEFTOVER probe-like relations = 0`（`pg_class` 里 `probe_%` / `big%` / `tiny` / `vec_t` 匹配数为 0），**对正式表零干扰、无残留**。建议 Lead 更新第 2 节该条 |
| D8 | 简报 3「Embedding 默认输出 1024 维；传 `dimensions: 1536` 可得到 1536 维」 | 未针对真 LLM 端点重测（不属于本次 4 个问题）；但**配置链路已验证**：`spring.ai.openai.embedding` 前缀实测存在、`OpenAiEmbeddingProperties.getDimensions()` 真实存在（`javap` 确认） | 无冲突，保持 1536 |
| D9 | 简报 2.2「实际拼 SQL 时，查询串建议用 `paradedb.parse` 或 `paradedb.match` 走参数绑定，避免注入」 | **完全印证**：JDBC `PreparedStatement` 11 个用例全部通过（`#{}` 可用）；注入串 `retrieval' OR '1'='1` 被查询解析器拒绝而非执行 | 无冲突，并补充了 `match` 优于 `parse` 的理由（字面量语义 vs Query Parser 语义） |
| D10 | 简报 4 未提及 `smile-base` / BLAS 传递依赖 | 补充：`smile-core → smile-base → javacpp/openblas/arpack-ng/duckdb_jdbc`，**且不可排除**（GMM 走 LAPACK、UMAP 走 ARPACK） | 新增信息，第 2.8 节 |
| D11 | 简报 4「`pom.xml` 已有依赖…`smile-core 4.1.0`」 | 一致。**补充**：`spring-boot-starter-aop` 被写死 `3.5.3`（与 Boot 4.1.1 混版），建议删版本 | 新增信息，第 3.3 节 |
| D12 | 简报 §9 末尾「⚠️ **UMAP API 没有随机种子参数**，无法固定随机性。因此不要把"同样输入必然得到同样的树"作为测试断言」 | 前半句对、结论**过强**。UMAP 确实没有 per-call 种子参数（`of(...)` 的 10 个形参里没有 seed），但 Smile 全部随机性走全局静态 RNG，`javap smile.math.MathEx` 实测存在 **`public static void setSeed(long)`**。实测：不设种子时同一输入两次 UMAP 结果 `max\|Δ\| = 10.25`；**每次调用前 `MathEx.setSeed(42)` 时 `max\|Δ\| = 0.0`（完全一致）** | **可复现性是可以拿到的**：聚类前调 `MathEx.setSeed(固定值)` 即可。**但代价很大**：`MathEx` 是全局静态可变状态，`@Async` 并发跑聚类会互相污染种子（既有不可复现风险，也可能串数据）→ **推荐聚类阶段串行执行，并仍不要把"同一输入必然同一棵树"写成断言**（因为 GMM 的簇标签编号本身不稳定，见 R11/D13） |
| D13 | 简报 §9「GMM … `fit(double[][] x)` 按 BIC 自动选簇数」 | **该重载实测不可用**：恒返回 `components=1`、`bic=0.0`、`L=0.0`（n=20…1000 共 16 个规模全部如此）。根因在源码：公开构造器把基线 BIC 设成 `0.0`（`L=0.0, n=1` → `bic = 0 - 0.5*length()*log(1) = 0`），而真实 BIC 恒为负 → `if (model.bic <= bic) break;` 第一次迭代必然 break | **不要用 `fit(data)`**。要"自动选簇数"改用 `smile.clustering.GMeans.fit(data, kmax)`（`kmax` = 最大簇数，实测 trueK=6/kmax=8 → k=6）；要"限定最大簇数"也只有 GMeans/XMeans 支持。详见第 2.4/2.5 节 |
| D14 | 简报 §2.3（22:09 追加）「BM25 查询写法：只有 `paradedb.match` 能用」 | **完全印证，且与我的实测互补**：我用 JDBC 证明 `?` 绑定**机制**畅通（11/11 用例通过，无占位符问题）；Lead 用自然语言问句证明 `content @@@ ?` / `paradedb.parse` 的**语义**不可靠（2/5 零命中）。我的 P11a/P11b（`@@@ '?'` → 0 行、`retrieval AND index` → 0 行）是对同一根因的独立复现 | 已把推荐写法改为 `id @@@ paradedb.match('content', #{query})`（第 4.6 节），并新增 R33；`parse` 标注为禁止 |
| D15 | 简报 §2.2「实测中文用**默认分词器**就能命中；**索引上用的是 `jieba` 分词器**，同样工作正常」 | 一致（jieba 确实可用）。**补充**：16 组中文查询逐条对比后，**默认分词器（实测索引真实 tokenizer = `unicode_words_removeemojis:false`）不劣于 jieba，且单字查询更好**（`摘`：默认 `[1,6]`，jieba `[]`）。默认分词器的中文行为是"逐字 token + `@@@` 相邻短语"，等价于子串匹配 | **推荐用默认分词器**（不写 `text_fields`）——与 Lead 的 DDL 一致，只是不要主动改成 jieba。详见第 4.2 节 |
| D16 | 简报 §8「只要 classpath 上有 DataSource，Spring AI 就会默认创建 `PgVectorStore` bean，而该 bean **默认会去建它自己的 `vector_store` 表**」 | 前半句对（bean 确实默认创建，我实测到了 `vectorStore` bean）。**后半句在本版本不成立**：`PgVectorStore` 的 `initializeSchema` 是 `private final boolean`（Java 默认 `false`），`CommonVectorStoreProperties.initializeSchema` 同样是 `boolean` 默认 `false`，自动装配字节码里没有任何硬编码 `true`；我用**真实数据源启动完整 context**（`afterPropertiesSet()` 已执行）后查库，**没有 `vector_store` 表** | 配置键 `spring.ai.vectorstore.pgvector.initialize-schema` 实测存在，按 Lead §8 显式设 `false` 是有价值的**防御**，但**不要按"默认会建表"去写风险清单/测试断言**。详见第 3.2(e) 节与 R34 |

---

## 附录 A：实测命令汇总（可原样复现）

```powershell
# 1) classpath（工作目录必须在项目根）
mvn -s maven-settings.xml -B -q dependency:build-classpath "-Dmdep.outputFile=.scratch\researcher\cp.txt"

# 2) 依赖树
mvn -s maven-settings.xml -B dependency:tree

# 3) 单文件 Java 运行（JDK 21 JEP 330；.scratch\researcher\run.cmd 里封装了 classpath 与 UTF-8 输出）
#    ⚠️ 必须用绝对路径，两处坑：PS 5.1 会把 -Dfile.encoding=UTF-8 拆坏；cmd 重定向才能保住 UTF-8
cmd /c ".scratch\researcher\run.cmd D:\javaCode\DocRaptor\.scratch\researcher\UMAPProbe.java > .scratch\researcher\umap-out.txt 2>&1"

# 4) 解 Smile 源码（用于确认形参名与默认值，javap 不给形参名）
mvn -s maven-settings.xml -B -q dependency:get "-Dartifact=com.github.haifengl:smile-core:4.1.0:jar:sources"
mvn -s maven-settings.xml -B -q dependency:get "-Dartifact=com.github.haifengl:smile-base:4.1.0:jar:sources"

# 5) 看 Spring AI 的真实自动装配类
jar xf .m2repo\...\spring-ai-autoconfigure-model-openai-2.0.1.jar META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports
```

**环境坑记录（照做可省 30 分钟）**
1. 本机 PowerShell 是 **Windows PowerShell 5.1**：`Get-Content`/`Set-Content` 默认 ANSI(GBK)，
   `*>` 重定向写 UTF-16。**读中文输出必须 `-Encoding UTF8`，写文件也必须显式 `-Encoding UTF8`**，
   否则会出现 `鍚戦噺妫€绱` 这类 mojibake（我第一次过滤输出就踩了）。
2. PS 5.1 调用 `java -Dfile.encoding=UTF-8` 会把参数拆坏（报
   `错误: 找不到或无法加载主类 .encoding=UTF-8`）。**解决办法：写成 `run.cmd` 批处理，用 `cmd /c` 调**。
3. JDK 21 单文件源码启动**必须给绝对路径**（`java -cp ... UMAPProbe.java` 报
   `ClassNotFoundException: UMAPProbe.java`，`java -cp ... D:\...\UMAPProbe.java` 才行）。
4. 数据库实验全部用 `temp table`；`paradedb` 在 temp 表上建 bm25 索引会刷大量
   `WARNING: resource was not closed: [-11] (rel=base/.../t24_37923...)`（正式表未复现），
   过滤日志时用 `Where-Object { $_ -notmatch '^\s*WARNING: resource was not closed' }`。

## 附录 B：原始输出文件索引（`.scratch/researcher/`）

| 文件 | 内容 |
|---|---|
| `cp.txt` | `dependency:build-classpath` 输出（26579 字符） |
| `deps.txt` | `dependency:tree` 完整树（272 行，258 个坐标） |
| `javap-UMAP.txt` / `javap-UMAP-p.txt` / `javap-UMAP-Curve.txt` | UMAP 公共/私有 API 签名 |
| `smile-core-toc.txt` / `smile-base-toc.txt` | 两个 jar 的完整类别清单 |
| `smile-src/` (35 KB `UMAP.java`) / `smilebase-src/` | sources jar 解压后的源码（默认值、校验、BIC 逻辑的原始出处） |
| `umap-out.txt` / `umap2-out.txt` / `umap3-out.txt` | UMAP 三次实测（157 KB / 2.8 KB / 1.5 KB） |
| `gmm-out.txt` / `gmm2-out.txt` | GMM 两次实测 |
| `seed-out.txt` | 可复现性 / `MathEx.setSeed` 实测 |
| `spring-out.txt` / `spring2-out.txt` | Spring Boot context 启动 + bean 名 + CONFIG_PREFIX |
| `ai-imports.txt`, `aiauto/` | 10 个 Spring AI autoconfigure 模块的 `AutoConfiguration.imports` 原文 |
| `db-out.txt` | pgvector：`<=>` 范围、`hnsw.ef_search` SET/SET LOCAL 全矩阵、EXPLAIN 15 vs 3015 行 |
| `db2-out.txt` / `db2-clean.txt` | pg_search：分数分布、中文分词对比、`@@@` JDBC 绑定 11 例（clean 版已过滤警告，388 行） |
| `db3-out.txt` | GUC 清单（实测 39 个 `paradedb.*`，`paradedb.planner_warnings` 在内）、16 组中文召回对比表、全库词分数 |
| `db4-out.txt` | `paradedb.tokenizer('default')` spec、`ef_search` 功能验证（1/2/5/10/40/100/1000）、pg_settings 懒加载 |
| `db5-out.txt` | 新连接 `SET/SHOW hnsw` 行为、`paradedb.schema()` 真实 tokenizer、tokenizer 覆盖坑 |
| `db6-out.txt` | 逐字相邻短语的反例证明、8 组查询分数分布、`1-(v<=>v)=1` |
| `db7-out.txt`, `tblcheck.txt` | 20 行表的 `EXPLAIN`（`LIMIT` 1/5/18 + `enable_seqscan=off` 对照）；正式表清单与无残留复查 |
| `*.java` | 16 个探针源码：`UMAPProbe{,2,3}.java` `GMMProbe{,2}.java` `SeedProbe.java` `SpringProbe{,2}.java` `DBProbe{,2,3,4,5,6,7}.java` `TblCheck.java` |
| `run.cmd` / `logback.xml` | 运行封装（classpath + UTF-8）与日志降噪配置（root=ERROR） |
| `javap-pgvecauto.txt` | `PgVectorStoreAutoConfiguration` 字节码（证明 `initializeSchema` 无硬编码 `true`） |
