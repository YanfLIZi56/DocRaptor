/**
 * 枚举字典与中文标签映射 —— 唯一来源：docs/03-api-contract.md 第 1.4 节。
 * 页面里禁止硬编码枚举字符串，一律引用本文件的常量 / 标签映射 / options。
 */
import type {
  ChunkStrategy,
  FileType,
  GmmCovarianceType,
  LogType,
  NodeType,
  RetrievalLogMode,
  RetrievalMode,
  RetrievalScope,
  StepStatus,
  TaskStage,
  TaskStatus,
  TaskType,
} from '@/types'

/** Element Plus el-tag / el-progress 可接受的语义色 */
export type TagType = 'primary' | 'success' | 'info' | 'warning' | 'danger'

export interface SelectOption<T extends string | number = string> {
  label: string
  value: T
}

/** 由「枚举 → 中文标签」映射生成 el-select 的 options */
export function toOptions<T extends string>(map: Record<T, string>): SelectOption<T>[] {
  return (Object.keys(map) as T[]).map((value) => ({ label: map[value], value }))
}

/**
 * 安全取标签：后端返回了字典外的值时原样回显，避免出现空白单元格。
 * （noUncheckedIndexedAccess 下直接索引 Record 会得到 undefined，故统一走这里）
 */
export function labelOf<T extends string>(
  map: Record<T, string>,
  key: string | null | undefined,
  fallback = '—',
): string {
  if (key === null || key === undefined || key === '') return fallback
  return (map as Record<string, string | undefined>)[key] ?? key
}

/** 安全取 el-tag 语义色 */
export function tagTypeOf<T extends string>(
  map: Record<T, TagType>,
  key: string | null | undefined,
  fallback: TagType = 'info',
): TagType {
  if (key === null || key === undefined || key === '') return fallback
  return (map as Record<string, TagType | undefined>)[key] ?? fallback
}

// ---------------------------------------------------------------------------
// 枚举字典（取值必须与契约 1.4 完全一致）
// ---------------------------------------------------------------------------

export const NODE_TYPE = {
  LEAF: 'LEAF',
  SUMMARY: 'SUMMARY',
} as const satisfies Record<NodeType, NodeType>

export const NODE_TYPE_LABEL: Record<NodeType, string> = {
  LEAF: '文本块（叶子）',
  SUMMARY: '摘要节点',
}

export const NODE_TYPE_SHORT_LABEL: Record<NodeType, string> = {
  LEAF: 'LEAF',
  SUMMARY: 'SUMMARY',
}

export const NODE_TYPE_TAG: Record<NodeType, TagType> = {
  LEAF: 'info',
  SUMMARY: 'warning',
}

export const NODE_TYPE_OPTIONS = toOptions(NODE_TYPE_LABEL)

export const CHUNK_STRATEGY = {
  FIXED_SIZE: 'FIXED_SIZE',
  PARAGRAPH: 'PARAGRAPH',
  RECURSIVE: 'RECURSIVE',
} as const satisfies Record<ChunkStrategy, ChunkStrategy>

export const CHUNK_STRATEGY_LABEL: Record<ChunkStrategy, string> = {
  FIXED_SIZE: '定长滑窗（FIXED_SIZE）',
  PARAGRAPH: '段落聚合（PARAGRAPH）',
  RECURSIVE: '递归分隔符（RECURSIVE）',
}

/** 选中策略时给用户的一句话解释 */
export const CHUNK_STRATEGY_HINT: Record<ChunkStrategy, string> = {
  FIXED_SIZE: '按 chunkSize 固定长度切分，相邻块重叠 chunkOverlap 个字符，实现最简单、边界最可控。',
  PARAGRAPH: '先按段落（空行）聚合，累积到接近 chunkSize 再切，语义完整性更好。',
  RECURSIVE: '按「段落 → 换行 → 句号 → 空格」逐级递归分隔，优先保证不切断语义单元。',
}

export const CHUNK_STRATEGY_OPTIONS = toOptions(CHUNK_STRATEGY_LABEL)

export const FILE_TYPE = {
  PDF: 'PDF',
  DOCX: 'DOCX',
  MARKDOWN: 'MARKDOWN',
  TXT: 'TXT',
} as const satisfies Record<FileType, FileType>

export const FILE_TYPE_LABEL: Record<FileType, string> = {
  PDF: 'PDF',
  DOCX: 'Word (DOCX)',
  MARKDOWN: 'Markdown',
  TXT: '纯文本 (TXT)',
}

export const FILE_TYPE_TAG: Record<FileType, TagType> = {
  PDF: 'danger',
  DOCX: 'primary',
  MARKDOWN: 'success',
  TXT: 'info',
}

export const STEP_STATUS = {
  PENDING: 'PENDING',
  RUNNING: 'RUNNING',
  SUCCESS: 'SUCCESS',
  FAILED: 'FAILED',
  SKIPPED: 'SKIPPED',
} as const satisfies Record<StepStatus, StepStatus>

export const STEP_STATUS_LABEL: Record<StepStatus, string> = {
  PENDING: '待处理',
  RUNNING: '进行中',
  SUCCESS: '成功',
  FAILED: '失败',
  SKIPPED: '已跳过',
}

export const STEP_STATUS_TAG: Record<StepStatus, TagType> = {
  PENDING: 'info',
  RUNNING: 'primary',
  SUCCESS: 'success',
  FAILED: 'danger',
  SKIPPED: 'warning',
}

export const STEP_STATUS_OPTIONS = toOptions(STEP_STATUS_LABEL)

export const TASK_TYPE = {
  DOC_IMPORT: 'DOC_IMPORT',
  DOC_PARSE: 'DOC_PARSE',
  DOC_CHUNK: 'DOC_CHUNK',
  DOC_EMBED: 'DOC_EMBED',
  RAPTOR_BUILD: 'RAPTOR_BUILD',
  EVAL_RUN: 'EVAL_RUN',
} as const satisfies Record<TaskType, TaskType>

export const TASK_TYPE_LABEL: Record<TaskType, string> = {
  DOC_IMPORT: '文档导入',
  DOC_PARSE: '文档解析',
  DOC_CHUNK: '文本分块',
  DOC_EMBED: '向量化',
  RAPTOR_BUILD: 'RAPTOR 建树',
  EVAL_RUN: '召回率评估',
}

export const TASK_TYPE_OPTIONS = toOptions(TASK_TYPE_LABEL)

export const TASK_STATUS = {
  PENDING: 'PENDING',
  RUNNING: 'RUNNING',
  SUCCESS: 'SUCCESS',
  PARTIAL_SUCCESS: 'PARTIAL_SUCCESS',
  FAILED: 'FAILED',
  CANCELED: 'CANCELED',
} as const satisfies Record<TaskStatus, TaskStatus>

export const TASK_STATUS_LABEL: Record<TaskStatus, string> = {
  PENDING: '待执行',
  RUNNING: '执行中',
  SUCCESS: '成功',
  PARTIAL_SUCCESS: '部分成功',
  FAILED: '失败',
  CANCELED: '已取消',
}

export const TASK_STATUS_TAG: Record<TaskStatus, TagType> = {
  PENDING: 'info',
  RUNNING: 'primary',
  SUCCESS: 'success',
  PARTIAL_SUCCESS: 'warning',
  FAILED: 'danger',
  CANCELED: 'info',
}

export const TASK_STATUS_OPTIONS = toOptions(TASK_STATUS_LABEL)

/** 终态：前端必须停止轮询（契约 1.3） */
export const TASK_TERMINAL_STATUSES: TaskStatus[] = [
  TASK_STATUS.SUCCESS,
  TASK_STATUS.PARTIAL_SUCCESS,
  TASK_STATUS.FAILED,
  TASK_STATUS.CANCELED,
]

export function isTaskTerminal(status: string | null | undefined): boolean {
  return status !== null && status !== undefined && TASK_TERMINAL_STATUSES.includes(status as TaskStatus)
}

/** el-progress 的 status（注意：el-progress 只认 success/exception/warning，不接受 danger/primary） */
export type ProgressStatus = 'success' | 'exception' | 'warning'

/** 终态任务对应的进度条状态；进行中返回 undefined，保持默认主题色 */
export function progressStatusOf(status: string | null | undefined): ProgressStatus | undefined {
  switch (status) {
    case TASK_STATUS.SUCCESS:
      return 'success'
    case TASK_STATUS.FAILED:
      return 'exception'
    case TASK_STATUS.PARTIAL_SUCCESS:
    case TASK_STATUS.CANCELED:
      return 'warning'
    default:
      return undefined
  }
}

export const TASK_STAGE = {
  PARSE: 'PARSE',
  CHUNK: 'CHUNK',
  EMBED: 'EMBED',
  TREE_BUILD: 'TREE_BUILD',
  EVAL: 'EVAL',
  DONE: 'DONE',
} as const satisfies Record<TaskStage, TaskStage>

export const TASK_STAGE_LABEL: Record<TaskStage, string> = {
  PARSE: '解析',
  CHUNK: '分块',
  EMBED: '向量化',
  TREE_BUILD: '建树',
  EVAL: '评估',
  DONE: '完成',
}

/** 契约 7.1 的 progress 区间映射，progress 已按此权重写库，可直接用于进度条 */
export const TASK_STAGE_PROGRESS_RANGE: Record<TaskStage, string> = {
  PARSE: '0 ~ 15',
  CHUNK: '15 ~ 35',
  EMBED: '35 ~ 70',
  TREE_BUILD: '70 ~ 100',
  EVAL: '0 ~ 100',
  DONE: '100',
}

export const TASK_STAGE_OPTIONS = toOptions(TASK_STAGE_LABEL)

export const RETRIEVAL_MODE = {
  VECTOR: 'VECTOR',
  BM25: 'BM25',
  HYBRID: 'HYBRID',
} as const satisfies Record<RetrievalMode, RetrievalMode>

export const RETRIEVAL_MODE_LABEL: Record<RetrievalMode, string> = {
  VECTOR: '纯向量',
  BM25: '纯 BM25',
  HYBRID: '混合（RRF）',
}

export const RETRIEVAL_MODE_HINT: Record<RetrievalMode, string> = {
  VECTOR: '只看语义相似度，走 pgvector 余弦距离；忽略 hybridRatio / rrfK，similarityThreshold 生效。',
  BM25: '只看关键词命中，走 ParadeDB BM25；忽略 similarityThreshold / hybridRatio / rrfK。',
  HYBRID: '向量路与 BM25 路各自排名后用 RRF 融合，hybridRatio 控制向量路权重。',
}

export const RETRIEVAL_MODE_OPTIONS = toOptions(RETRIEVAL_MODE_LABEL)

/** 检索日志的 mode 多一个 EVAL（契约 6.2） */
export const RETRIEVAL_LOG_MODE_LABEL: Record<RetrievalLogMode, string> = {
  VECTOR: '纯向量',
  BM25: '纯 BM25',
  HYBRID: '混合（RRF）',
  EVAL: '评估（EVAL）',
}

export const RETRIEVAL_LOG_MODE_OPTIONS = toOptions(RETRIEVAL_LOG_MODE_LABEL)

export const RETRIEVAL_SCOPE = {
  LEAF_ONLY: 'LEAF_ONLY',
  ALL_LEVELS: 'ALL_LEVELS',
  SPECIFIED_LEVEL: 'SPECIFIED_LEVEL',
} as const satisfies Record<RetrievalScope, RetrievalScope>

export const RETRIEVAL_SCOPE_LABEL: Record<RetrievalScope, string> = {
  LEAF_ONLY: '仅叶子',
  ALL_LEVELS: '全部层级（折叠树）',
  SPECIFIED_LEVEL: '指定层级',
}

export const RETRIEVAL_SCOPE_HINT: Record<RetrievalScope, string> = {
  LEAF_ONLY: '只返回 nodeType=LEAF 的文本块。',
  ALL_LEVELS: 'LEAF 与 SUMMARY 混合返回，同一条祖先链上只保留排名最高的一个节点（折叠树）。',
  SPECIFIED_LEVEL: '只返回指定 level 的节点，必须至少选择一个层级（levels）。',
}

export const RETRIEVAL_SCOPE_OPTIONS = toOptions(RETRIEVAL_SCOPE_LABEL)

export const LOG_TYPE = {
  SEARCH: 'SEARCH',
  EVAL: 'EVAL',
} as const satisfies Record<LogType, LogType>

export const LOG_TYPE_LABEL: Record<LogType, string> = {
  SEARCH: '检索',
  EVAL: '评估',
}

export const LOG_TYPE_OPTIONS = toOptions(LOG_TYPE_LABEL)

export const GMM_COVARIANCE_TYPE = {
  FULL: 'full',
  TIED: 'tied',
  DIAGONAL: 'diagonal',
  SPHERICAL: 'spherical',
} as const satisfies Record<Uppercase<GmmCovarianceType>, GmmCovarianceType>

export const GMM_COVARIANCE_LABEL: Record<GmmCovarianceType, string> = {
  full: 'full（各簇独立完整协方差）',
  tied: 'tied（所有簇共享协方差）',
  diagonal: 'diagonal（对角协方差）',
  spherical: 'spherical（球形，最省参数）',
}

export const GMM_COVARIANCE_OPTIONS = toOptions(GMM_COVARIANCE_LABEL)

/** 评估跳过原因（契约 6.5 skippedCases[].reason） */
export const EVAL_SKIP_REASON_LABEL: Record<string, string> = {
  EXPECTED_EMPTY: '期望块为空',
  DISABLED: '用例已禁用',
  DOCUMENT_DISABLED: '期望块所属文档已禁用',
}

// ---------------------------------------------------------------------------
// 错误码（契约第 3 节）—— 前端仅用于分支判断，提示文案一律用后端 message
// ---------------------------------------------------------------------------

export const ERROR_CODE = {
  SUCCESS: 0,
  PARAM_INVALID: 40001,
  TOP_K_EXCEEDED: 40002,
  MODE_INVALID: 40003,
  SCOPE_INVALID: 40004,
  RATIO_OUT_OF_RANGE: 40005,
  RRF_K_OUT_OF_RANGE: 40006,
  FILE_TYPE_UNSUPPORTED: 40007,
  FILE_EMPTY: 40008,
  CHUNK_PARAM_INVALID: 40009,
  EXPECTED_CHUNK_INVALID: 40010,
  LEVELS_REQUIRED: 40011,
  PAYLOAD_TOO_LARGE: 41301,
  RESOURCE_NOT_FOUND: 40400,
  KNOWLEDGE_BASE_NOT_FOUND: 40401,
  DOCUMENT_NOT_FOUND: 40402,
  TASK_NOT_FOUND: 40403,
  EVAL_CASE_NOT_FOUND: 40404,
  NAME_DUPLICATED: 40901,
  CONFLICT_RUNNING_TASK: 40902,
  TREE_ALREADY_EXISTS: 40903,
  DOCUMENT_DISABLED: 40904,
  DOCUMENT_NOT_READY: 40905,
  EVAL_CASE_DISABLED: 40906,
  DOCUMENT_PARSE_FAILED: 50001,
  EMBEDDING_FAILED: 50002,
  LLM_FAILED: 50003,
  TREE_BUILD_FAILED: 50004,
  DB_ERROR: 50005,
  STORAGE_ERROR: 50006,
  INTERNAL_ERROR: 50099,
} as const

// ---------------------------------------------------------------------------
// 参数范围（契约 4.1 / 4.6 / 4.9 / 5.1 / 6.1 / 6.5 / 8.6）
// ---------------------------------------------------------------------------

export const PAGE_DEFAULT = 1
export const PAGE_SIZE_DEFAULT = 20
export const PAGE_SIZE_MAX = 200
export const PAGE_SIZE_OPTIONS = [10, 20, 50, 100, 200]

/** 上传单文件上限 10MB（契约 4.6 / 错误码 41301） */
export const UPLOAD_MAX_BYTES = 10 * 1024 * 1024
/** 扩展名白名单（契约 4.6） */
export const FILE_ACCEPT = '.pdf,.docx,.md,.markdown,.txt'

export const KB_LIMITS = {
  nameMaxLength: 128,
  descriptionMaxLength: 2000,
  chunkSize: { min: 64, max: 8192, step: 16, default: 512 },
  chunkOverlap: { min: 0, step: 8, default: 64 },
} as const

export const RAPTOR_LIMITS = {
  maxLevel: { min: 1, max: 10, step: 1, default: 3 },
  umapNNeighbors: { min: 2, max: 100, step: 1, default: 10 },
  umapMinDist: { min: 0, max: 1, step: 0.01, default: 0.1 },
  gmmMaxClusters: { min: 2, max: 64, step: 1, default: 8 },
} as const

export const RETRIEVAL_LIMITS = {
  topK: { min: 1, max: 100, step: 1, default: 10 },
  similarityThreshold: { min: 0, max: 1, step: 0.01, default: 0 },
  hybridRatio: { min: 0, max: 1, step: 0.05, default: 0.5 },
  bm25Weight: { min: 0, max: 10, step: 0.1, default: 1 },
  rrfK: { min: 1, max: 1000, step: 1, default: 60 },
  /** levels 元素范围（契约 6.1） */
  level: { min: 0, max: 10 },
} as const

/** 检索日志时间区间筛选用 */
export const DATETIME_FORMAT = 'YYYY-MM-DD HH:mm:ss'

/** 评估 kList 常用候选（元素必须 ≤ topK，超出会被后端裁剪） */
export const EVAL_K_CANDIDATES = [1, 3, 5, 10, 20, 50, 100]
export const EVAL_K_DEFAULT = [1, 3, 5, 10]

/** 异步任务轮询间隔（契约 1.3 建议 1000ms） */
export const TASK_POLL_INTERVAL_MS = 1000
/** 连续失败多少次后停止轮询并提示（避免后端挂掉时无限刷屏） */
export const TASK_POLL_MAX_FAILURES = 5

/** 层级中文标签：0 为叶子层 */
export function levelLabel(level: number | null | undefined, fallback = '—'): string {
  if (level === null || level === undefined) return fallback
  return level === 0 ? 'L0（叶子/文本块）' : `L${level}（摘要）`
}

/** 层级短标签 */
export function levelShortLabel(level: number | null | undefined): string {
  if (level === null || level === undefined) return '—'
  return level === 0 ? 'L0 叶子' : `L${level} 摘要`
}
