import { get, post } from './request'
import type {
  PageResult,
  RetrievalLog,
  RetrievalLogQuery,
  RetrievalMode,
  RetrievalRequest,
  RetrievalResponse,
} from '@/types'

/** 模块3：检索与召回测试（契约 6.1 ~ 6.2） */

/** 纯向量检索（mode 被后端强制为 VECTOR） */
export function vectorSearch(data: RetrievalRequest): Promise<RetrievalResponse> {
  return post<RetrievalResponse>('/retrieval/vector', data)
}

/** 纯 BM25 检索（忽略 hybridRatio / rrfK / similarityThreshold） */
export function bm25Search(data: RetrievalRequest): Promise<RetrievalResponse> {
  return post<RetrievalResponse>('/retrieval/bm25', data)
}

/** 混合检索（RRF 融合） */
export function hybridSearch(data: RetrievalRequest): Promise<RetrievalResponse> {
  return post<RetrievalResponse>('/retrieval/hybrid', data)
}

/** 按模式分发到三个路径不同的接口（请求体结构完全一致） */
export function searchByMode(mode: RetrievalMode, data: RetrievalRequest): Promise<RetrievalResponse> {
  switch (mode) {
    case 'VECTOR':
      return vectorSearch(data)
    case 'BM25':
      return bm25Search(data)
    case 'HYBRID':
      return hybridSearch(data)
  }
}

/** 检索日志查询（自动落库的 SEARCH / EVAL 记录） */
export function listRetrievalLogs(query: RetrievalLogQuery = {}): Promise<PageResult<RetrievalLog>> {
  return get<PageResult<RetrievalLog>>('/retrieval/logs', query)
}
