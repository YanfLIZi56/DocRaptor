import { get, post } from './request'
import type {
  RaptorBuildRequest,
  RaptorBuildResult,
  RaptorTreeData,
  RaptorTreeQuery,
  RaptorTreeStats,
} from '@/types'

/** 模块2：RAPTOR 树构建（契约 5.1 ~ 5.3） */

const BASE = '/raptor/trees'

/** 触发建树（异步，返回 taskId 后轮询进度） */
export function buildRaptorTree(data: RaptorBuildRequest): Promise<RaptorBuildResult> {
  return post<RaptorBuildResult>(BASE, data)
}

/**
 * 查询树结构（默认 format=nested）。
 * 注意：该文档尚未建树时后端返回 code=40400 且 data=null，
 * 因此调用方通常传 `silent: true`，自行渲染「未建树」空态。
 */
export function getRaptorTree(
  documentId: string,
  query: RaptorTreeQuery = {},
  silent = false,
): Promise<RaptorTreeData> {
  return get<RaptorTreeData>(`${BASE}/${documentId}`, query, { silent })
}

/** 树统计（各层节点数、深度、是否唯一根） */
export function getRaptorTreeStats(documentId: string, silent = false): Promise<RaptorTreeStats> {
  return get<RaptorTreeStats>(`${BASE}/${documentId}/stats`, undefined, { silent })
}
