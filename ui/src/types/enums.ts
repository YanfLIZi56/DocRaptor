/**
 * 枚举取值类型定义 —— 唯一来源：docs/03-api-contract.md 第 1.4 节「枚举字典」。
 * 中文标签映射见 `src/constants/index.ts`，不要在页面里硬编码字符串。
 */

/** 节点类型：文本块 / 摘要节点 */
export type NodeType = 'LEAF' | 'SUMMARY'

/** 分块策略：定长滑窗 / 段落聚合 / 递归分隔符 */
export type ChunkStrategy = 'FIXED_SIZE' | 'PARAGRAPH' | 'RECURSIVE'

/** 文件类型：按扩展名映射（.md / .markdown → MARKDOWN） */
export type FileType = 'PDF' | 'DOCX' | 'MARKDOWN' | 'TXT'

/** 4 个流水线步骤（解析/分块/向量化/建树）的状态 */
export type StepStatus = 'PENDING' | 'RUNNING' | 'SUCCESS' | 'FAILED' | 'SKIPPED'

/** 异步任务类型 */
export type TaskType =
  | 'DOC_IMPORT'
  | 'DOC_PARSE'
  | 'DOC_CHUNK'
  | 'DOC_EMBED'
  | 'RAPTOR_BUILD'
  | 'EVAL_RUN'

/** 异步任务状态 */
export type TaskStatus =
  | 'PENDING'
  | 'RUNNING'
  | 'SUCCESS'
  | 'PARTIAL_SUCCESS'
  | 'FAILED'
  | 'CANCELED'

/** 异步任务当前阶段 */
export type TaskStage = 'PARSE' | 'CHUNK' | 'EMBED' | 'TREE_BUILD' | 'EVAL' | 'DONE'

/** 检索模式：纯向量 / 纯 BM25 / RRF 混合 */
export type RetrievalMode = 'VECTOR' | 'BM25' | 'HYBRID'

/** 检索范围：仅叶子 / 全部层级(折叠树) / 指定层级 */
export type RetrievalScope = 'LEAF_ONLY' | 'ALL_LEVELS' | 'SPECIFIED_LEVEL'

/** 检索日志类型 */
export type LogType = 'SEARCH' | 'EVAL'

/**
 * 检索日志的 mode 取值比 retrievalMode 更宽（多一个 EVAL，用于评估落库的日志）。
 * 见契约 6.2 的 `mode` 查询参数说明。
 */
export type RetrievalLogMode = RetrievalMode | 'EVAL'

/** 建树时 GMM 协方差类型覆盖值（契约 5.1） */
export type GmmCovarianceType = 'full' | 'tied' | 'diagonal' | 'spherical'
