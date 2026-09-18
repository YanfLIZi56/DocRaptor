# DocRaptor 测试报告

> 版本：v2.0 ｜ 更新：2026-09-18 ｜ 产出：Lead
> **本版为如实记录当前状态的版本**：仓库内**没有单元测试**（按用户决定删除），
> 因此本文档记录的是「曾经做过哪些测试验证」与「现在如何验证」，而不是"当前 `mvn test` 的结果"。

---

## 1. 当前状态（重要）

| 项 | 状态 |
|---|---|
| `src/test/` | **不存在**（用户决定：仓库保持干净，不带测试代码） |
| `mvn -s <settings> -B test` | 无测试可跑（只做编译） |
| 回归保护 | **无**。改代码后没有任何自动检查会提示你破坏了既有行为 |

> 历史说明：开发过程中曾存在 17 个测试类（含 `ApplicationContextSmokeTest`、两个 `DOCRAPTOR_IT`
> 门禁集成测试），但 **git 从未收录过 `src/test`**（`git ls-files src/test` 为空），
> 在本次聚类重构期间丢失，无法恢复。重构后期又新建过 5 个测试类（46 用例），
> 按用户决定一并删除，以保持仓库干净。

---

## 2. 开发过程中实际做过、并且真实的验证

以下都是**当时真实跑过**的，证据记录在 `docs/07-acceptance-report.md`：

### 2.1 后端整体验证（重构前，曾达到）

| 验证 | 当时结果 |
|---|---|
| `mvn -s maven-settings.xml -B test` | **81 用例，0 失败 0 错误**（8 个环境门禁用例默认跳过） |
| 打开 `DOCRAPTOR_IT=true` 后 | **80 用例全跑，0 失败 0 错误**（连真实 DB + 真实 LLM） |
| 前端 | `vue-tsc` 类型检查 0 错误；`vite build` 成功（1702 模块） |

### 2.2 端到端验收（重构前）

用 `docs/samples/raptor-guide.md`（6109 字符 / 5254 中文字）跑完整流水线，4 项验收标准全部通过：

| 验收标准 | 当时实测 |
|---|---|
| A 导入 5000+ 字文档 → 自动分块 / 向量化 / 建树 | ✔ 11 块 → 三层树，唯一根 |
| B 召回结果携带层级信息 | ✔ |
| C 三种检索模式结果有明显差异 | ✔ V∩B 仅 1/5~3/5 |
| D 混合检索 Recall@5 > 60% | ✔ **88.9%** |

### 2.3 聚类重构验证（本次改造，2026-09-18）

在《三体》313 块真实 embedding 上（与用户 VM 的 199465 字符 / 313 块完全一致的复现装置）：

| 指标 | 改造前（用户 VM） | 改造后 |
|---|---|---|
| L1 簇数 | **3** | **58**（minSize=1）/ 35（minSize=8） |
| 树深度 | 3 层 | **5 层** |
| L1 最大簇占叶子 | **64%** | **2.6% ~ 6%** |
| 聚类可复现性 | k 在 2~12 跳变 | **10/10 逐位相同** |

---

## 3. 现在如何验证（替代 `mvn test`）

因为没有单元测试，验证只能靠**真实运行**：

```powershell
# 1) 编译
mvn -s <你的 settings> -B clean compile

# 2) 启动（需 API_KEY / API_URL 环境变量；建树需要 LLM 配额）
mvn -s <你的 settings> -B spring-boot:run

# 3) 端到端验收（另开窗口）
node scripts/acceptance.mjs
```

`scripts/acceptance.mjs` 会：建知识库 → 上传 `docs/samples/raptor-guide.md` → 等异步流水线
→ 校验分块三要素 → 校验摘要树 → 三模式检索对比 → 建评估用例 → 跑 Recall@K / Hit Rate@K / MRR
→ 打印结论并返回退出码（0 = 全部通过）。

---

## 4. 已知未覆盖的部分（如实声明）

1. **无单元测试**：算法层（GMM / 聚类 / RRF / 指标 / 分块）没有任何自动断言。
   修改这些代码后请务必重跑端到端验收。
2. **无前端测试**，也没有做过浏览器端的真实前后端联调（代理与请求层已核对，但没点过按钮）。
3. **无性能/压力测试**。
4. **`mvn test` 不覆盖"应用能否启动"**——本项目踩过真实的坑：MyBatis 缺少 UUID TypeHandler 时
   测试全绿但应用起不来（现象：启动时抛 `Type handler was null on parameter mapping for property 'id'`，
   发生在构建 `SqlSessionFactory` 阶段）。修法见 `src/main/resources/mybatis/mybatis-config.xml` 里的注册。
5. **端到端可复现性只到 L1**：L1 完全可复现，但 L2 及以上会漂移，
   根因是 L2 的输入是 **LLM 生成的摘要文本的 embedding**，而摘要本身不确定（temperature=0.2）。
   解决方案（待定）：① `temperature=0` ② 按「簇成员集合 + prompt 哈希」缓存摘要。

---

## 5. 环境注意事项（踩过的坑）

- **Maven 本地仓库**：本项目开发环境曾因沙箱限制需要把 `localRepository` 指到工作区内。
  你的环境如果正常，直接 `mvn` 即可，不需要特殊 settings。
- **不要并发跑 `mvn compile/test` 和 `spring-boot:run`**：编译会重写 `target/classes`，
  导致运行中的服务被杀或测试大面积 `NoClassDefFoundError`（重跑即恢复）。串行执行。
- **改了源码后用 `clean`**：增量编译偶发留下陈旧类文件，导致 `ClassNotFoundException`。
