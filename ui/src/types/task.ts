import type { TaskStage, TaskStatus, TaskType } from './enums'
import type { PageQuery } from './common'
import type { EvalRunResult } from './eval'

/** DOC_IMPORT / DOC_PARSE / DOC_CHUNK / DOC_EMBED 任务结果（契约 7.1） */
export interface DocumentTaskResult {
  documentId: string
  charCount: number
  chunkCount: number
  embeddedCount: number
  durationMs: number
}

/** RAPTOR_BUILD 任务结果（契约 7.1） */
export interface RaptorBuildTaskResult {
  documentId: string
  rootNodeId: string
  maxLevel: number
  summaryNodeCount: number
  actualDepth: number
  hasUniqueRoot: boolean
  degradedSummaryCount: number
  durationMs: number
}

/** EVAL_RUN 任务结果与 6.5 同步模式响应完全一致 */
export type TaskResult = DocumentTaskResult | RaptorBuildTaskResult | EvalRunResult

/** 异步任务对象（契约 7.1；7.2 列表元素同此结构） */
export interface AsyncTask {
  taskId: string
  taskType: TaskType
  status: TaskStatus
  /** 0~100，可直接绑定进度条 */
  progress: number
  currentStage: TaskStage
  progressMessage: string | null
  knowledgeBaseId: string | null
  documentId: string | null
  documentName: string | null
  retryCount: number
  errorMessage: string | null
  payload: Record<string, unknown> | null
  result: TaskResult | null
  createdAt: number
  startedAt: number | null
  finishedAt: number | null
  heartbeatAt: number | null
}

/** 任务列表查询入参（契约 7.2） */
export interface TaskQuery extends PageQuery {
  documentId?: string | null
  knowledgeBaseId?: string | null
  status?: TaskStatus | null
  taskType?: TaskType | null
}

/** 异步触发接口的统一返回（契约 1.3） */
export interface AsyncTaskRef {
  taskId: string
  status: TaskStatus
}

/** 判断 EVAL_RUN 任务结果（唯一含 metrics 的结果类型） */
export function isEvalRunResult(result: TaskResult | null): result is EvalRunResult {
  return result !== null && 'metrics' in result && 'perQuery' in result
}

/** 判断 RAPTOR_BUILD 任务结果 */
export function isRaptorBuildTaskResult(result: TaskResult | null): result is RaptorBuildTaskResult {
  return result !== null && 'rootNodeId' in result && 'actualDepth' in result
}

/** 判断文档流水线任务结果 */
export function isDocumentTaskResult(result: TaskResult | null): result is DocumentTaskResult {
  return result !== null && 'chunkCount' in result && 'charCount' in result
}
