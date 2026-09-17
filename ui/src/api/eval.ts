import { del, get, post, put } from './request'
import type {
  AsyncTaskRef,
  EvalCase,
  EvalCaseCreateRequest,
  EvalCaseDeleteResult,
  EvalCaseQuery,
  EvalCaseUpdateRequest,
  EvalRunRequest,
  EvalRunResult,
  PageResult,
} from '@/types'

/** 模块3：召回率评估（契约 6.3 ~ 6.5） */

const CASES = '/eval/cases'
const RUN = '/eval/run'

/** 新建评估用例（测试查询 + 期望命中的文本块 ID 数组） */
export function createEvalCase(data: EvalCaseCreateRequest): Promise<EvalCase> {
  return post<EvalCase>(CASES, data)
}

/** 评估用例列表（expectedChunks 一并返回，便于直接编辑） */
export function listEvalCases(query: EvalCaseQuery = {}): Promise<PageResult<EvalCase>> {
  return get<PageResult<EvalCase>>(CASES, query)
}

/** 修改评估用例（expectedChunkIds 传数组表示整体替换，传 null 表示不改） */
export function updateEvalCase(id: string, data: EvalCaseUpdateRequest): Promise<EvalCase> {
  return put<EvalCase>(`${CASES}/${id}`, data)
}

/** 删除评估用例 */
export function deleteEvalCase(id: string): Promise<EvalCaseDeleteResult> {
  return del<EvalCaseDeleteResult>(`${CASES}/${id}`)
}

/** 同步执行评估：直接返回 Recall@K / Hit Rate@K / MRR */
export function runEvalSync(data: EvalRunRequest): Promise<EvalRunResult> {
  return post<EvalRunResult>(RUN, { ...data, async: false }, { timeout: 600_000 })
}

/** 异步执行评估：返回 taskId，进度查 /api/async-tasks/{taskId}，结果在任务 result 中 */
export function runEvalAsync(data: EvalRunRequest): Promise<AsyncTaskRef> {
  return post<AsyncTaskRef>(RUN, { ...data, async: true })
}
