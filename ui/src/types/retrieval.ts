import type { LogType, NodeType, RetrievalLogMode, RetrievalMode, RetrievalScope } from './enums'
import type { PageQuery } from './common'

/** 检索请求体（契约 6.1，三个检索接口结构完全一致） */
export interface RetrievalRequest {
  knowledgeBaseId: string
  /** 查询原文，1~2000 字符 */
  query: string
  /** 返回条数，[1, 100]，默认 10 */
  topK?: number
  /** 仅作用于向量路的硬过滤阈值，[0.0, 1.0]，默认 0.0 */
  similarityThreshold?: number
  /** 仅 HYBRID：向量路权重 w_vector，[0.0, 1.0]，默认 0.5 */
  hybridRatio?: number
  /** 仅 HYBRID：BM25 路整体缩放系数，[0.0, 10.0]，默认 1.0 */
  bm25Weight?: number
  /** 仅 HYBRID：RRF 平滑常数 k，[1, 1000]，默认 60 */
  rrfK?: number
  /** 默认 ALL_LEVELS */
  scope?: RetrievalScope
  /** scope=SPECIFIED_LEVEL 时必填；元素 ∈ [0,10] */
  levels?: number[] | null
  /** 限定文档范围；null 表示不限 */
  documentIds?: string[] | null
  /** false 时 content 截断到 200 字符，默认 true */
  withContent?: boolean
  /** 是否返回 scoreBreakdown 各路明细，默认 true */
  withScoreBreakdown?: boolean
}

/** 响应中的实际生效参数（契约 6.1 `data.params`；6.2 日志的 params 额外含 scope） */
export interface RetrievalParams {
  topK?: number
  similarityThreshold?: number
  hybridRatio?: number
  bm25Weight?: number
  rrfK?: number
  scope?: RetrievalScope
  levels?: number[] | null
  documentIds?: string[] | null
}

/** 各路原始排名与分数明细（契约 6.1 `scoreBreakdown`） */
export interface ScoreBreakdown {
  /** 向量路原始分数，未召回为 null */
  vectorRawScore: number | null
  /** 向量路原始排名（1 起），未召回为 null */
  vectorRank: number | null
  /** w_r × 1/(rrfK + rank)；VECTOR/BM25 模式下为 null */
  vectorWeightedScore: number | null
  bm25RawScore: number | null
  bm25Rank: number | null
  bm25WeightedScore: number | null
  rrfK: number | null
}

/** 单条检索命中（契约 6.1 `hits[]`） */
export interface RetrievalHit {
  finalRank: number
  finalScore: number
  nodeId: string
  nodeType: NodeType
  level: number
  documentId: string
  documentName: string
  documentEnabled: boolean
  /** SUMMARY 节点为 null */
  chunkIndex: number | null
  startChunkIndex: number
  endChunkIndex: number
  charCount: number
  content: string | null
  /** 非空表示被折叠树策略折叠掉（当前实现中折叠条目不会出现在 hits 里，仅作调试字段） */
  collapsedByNodeId: string | null
  scoreBreakdown: ScoreBreakdown | null
}

/** 检索耗时明细（契约 6.1 `costBreakdown`；BM25 模式 embeddingMs 为 0） */
export interface RetrievalCostBreakdown {
  embeddingMs: number
  vectorMs: number
  bm25Ms: number
  fusionMs: number
  totalMs: number
}

/** 检索响应（契约 6.1，三个接口结构一致） */
export interface RetrievalResponse {
  query: string
  mode: RetrievalMode
  knowledgeBaseId: string
  scope: RetrievalScope
  params: RetrievalParams
  costMs: number
  costBreakdown: RetrievalCostBreakdown
  totalHits: number
  /** 被"折叠树"策略折叠掉的条数 */
  collapsedCount: number
  /** 被 similarityThreshold 过滤掉的候选条数（仅向量路） */
  truncatedByThreshold: number
  hits: RetrievalHit[]
}

/** 检索日志（契约 6.2 `data.list[]`） */
export interface RetrievalLog {
  id: string
  /** 知识库删除后置为 null 但日志保留 */
  knowledgeBaseId: string | null
  logType: LogType
  queryText: string
  mode: RetrievalLogMode
  params: RetrievalParams
  resultCount: number
  resultNodeIds: string[]
  latencyMs: number
  latencyBreakdown: Pick<RetrievalCostBreakdown, 'vectorMs' | 'bm25Ms' | 'fusionMs'>
  success: boolean
  errorMessage: string | null
  createdAt: number
}

/** 检索日志查询入参（契约 6.2） */
export interface RetrievalLogQuery extends PageQuery {
  knowledgeBaseId?: string | null
  mode?: RetrievalLogMode | null
  logType?: LogType | null
  queryKeyword?: string | null
  /** 起始时间（毫秒时间戳，含） */
  startTime?: number | null
  /** 结束时间（毫秒时间戳，含） */
  endTime?: number | null
}
