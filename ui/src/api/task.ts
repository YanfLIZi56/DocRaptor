import { get } from './request'
import type { AsyncTask, PageResult, TaskQuery } from '@/types'

/** 模块4：异步任务进度查询（契约 7.1 ~ 7.2） */

const BASE = '/async-tasks'

/**
 * 任务详情 / 进度。
 * 轮询场景默认 `silent`（不弹错误提示），由调用方自行处理失败计数。
 */
export function getAsyncTask(taskId: string, silent = true): Promise<AsyncTask> {
  return get<AsyncTask>(`${BASE}/${taskId}`, undefined, { silent })
}

/** 任务列表（按文档 / 知识库 / 状态 / 类型筛选） */
export function listAsyncTasks(query: TaskQuery = {}): Promise<PageResult<AsyncTask>> {
  return get<PageResult<AsyncTask>>(BASE, query)
}
