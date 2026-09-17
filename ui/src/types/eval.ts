import type { RetrievalMode, RetrievalScope } from './enums'
import type { PageQuery } from './common'
import type { RetrievalParams } from './retrieval'

/** 期望命中的块（契约 6.3 响应 `expectedChunks[]`） */
export interface ExpectedChunk {
  /** summary_nodes.id，必须是该知识库下的 LEAF 节点 */
  nodeId: string
  documentId: string
  chunkIndex: number
  relevance: number
}

/** 评估用例（契约 6.3 / 6.4 响应对象） */
export interface EvalCase {
  id: string
  knowledgeBaseId: string
  name: string
  queryText: string
  remark: string | null
  /** TODO(contract): 契约提供了 enabled 字段与 ?enabled= 过滤，但没有开放修改 enabled 的接口
   *  （6.4 的 PUT 请求体字段"同 6.3"，而 6.3 无 enabled）。前端按只读展示。 */
  enabled: boolean
  expectedChunks: ExpectedChunk[]
  lastRecallAtK: number | null
  lastMrr: number | null
  lastEvaluatedAt: number | null
  createdAt: number
}

/** 新建评估用例入参（契约 6.3） */
export interface EvalCaseCreateRequest {
  knowledgeBaseId: string
  /** 1~128 字符 */
  name: string
  queryText: string
  /** 至少 1 个，必须是该知识库下的 LEAF 节点 ID */
  expectedChunkIds: string[]
  remark?: string | null
}

/** 修改评估用例入参（契约 6.4；knowledgeBaseId 不可改，传入忽略） */
export interface EvalCaseUpdateRequest {
  name?: string | null
  queryText?: string | null
  /** null 表示不改；传数组表示整体替换 */
  expectedChunkIds?: string[] | null
  remark?: string | null
}

/** 评估用例查询入参（契约 6.4） */
export interface EvalCaseQuery extends PageQuery {
  knowledgeBaseId?: string | null
  enabled?: boolean | null
}

/** 删除评估用例响应（契约 6.4） */
export interface EvalCaseDeleteResult {
  id: string
  deleted: boolean
}

/** 评估执行入参（契约 6.5） */
export interface EvalRunRequest {
  knowledgeBaseId: string
  /** null 表示跑该知识库下全部 enabled=true 的用例 */
  caseIds?: string[] | null
  /** 实际召回深度，[1, 100]，默认 10 */
  topK?: number
  /** 要计算的 K 列表，默认 [1,3,5,10]；元素必须 ≤ topK */
  kList?: number[]
  mode?: RetrievalMode
  similarityThreshold?: number
  hybridRatio?: number
  bm25Weight?: number
  rrfK?: number
  scope?: RetrievalScope
  levels?: number[] | null
  /** false：同步返回指标；true：返回 taskId，结果在任务 result 中 */
  async?: boolean
}

/** 数据集级指标（契约 6.5 `metrics[]`） */
export interface EvalMetric {
  k: number
  recall: number
  hitRate: number
  /** 数据集级 MRR（对 k=topK 口径计算，与 k 无关） */
  mrr: number
}

/** 单用例单 K 的指标（契约 6.5 `perQuery[].metrics[]`） */
export interface EvalPerQueryMetric {
  k: number
  recall: number
  hitRate: number
  reciprocalRank: number
}

/** 被跳过的用例（契约 6.5 `skippedCases[]`） */
export interface EvalSkippedCase {
  caseId: string
  name: string
  /** 如 EXPECTED_EMPTY */
  reason: string
}

/** 单用例评估明细（契约 6.5 `perQuery[]`） */
export interface EvalPerQuery {
  caseId: string
  name: string
  query: string
  expectedChunkIds: string[]
  retrievedNodeIds: string[]
  hitNodeIds: string[]
  missedNodeIds: string[]
  firstHitRank: number | null
  metrics: EvalPerQueryMetric[]
  latencyMs: number
  retrievalLogId: string | null
}

/** 同步评估结果（契约 6.5；异步模式下作为 async_task.result） */
export interface EvalRunResult {
  knowledgeBaseId: string
  mode: RetrievalMode
  topK: number
  params: RetrievalParams
  evaluatedCases: number
  skippedCases: EvalSkippedCase[]
  /** kList 中被裁剪（> topK）的 K 值 */
  truncatedKList: number[]
  metrics: EvalMetric[]
  avgLatencyMs: number
  retrievalLogIds: string[]
  perQuery: EvalPerQuery[]
}
