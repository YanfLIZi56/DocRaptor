import type { ChunkStrategy } from './enums'
import type { PageQuery } from './common'

/** 知识库对象（契约 4.1 响应 / 4.2 列表元素 / 4.3 详情） */
export interface KnowledgeBase {
  id: string
  name: string
  description: string | null
  chunkSize: number
  chunkOverlap: number
  chunkStrategy: ChunkStrategy
  /** 列表接口返回的实时统计 */
  documentCount: number
  nodeCount: number
  embeddingModel: string
  embeddingDimension: number
  createdAt: number
  updatedAt: number
}

/** 新建知识库入参（契约 4.1） */
export interface KnowledgeBaseCreateRequest {
  /** 1~128 字符，全局唯一 */
  name: string
  description?: string | null
  /** 分块目标字符数，[64, 8192]，默认 512 */
  chunkSize?: number
  /** 相邻块重叠字符数，[0, chunkSize)，默认 64 */
  chunkOverlap?: number
  chunkStrategy?: ChunkStrategy
}

/**
 * 重命名 / 改描述入参（契约 4.4）。
 * 传 null / 不传表示不改；description 传 "" 表示清空。
 * chunkSize / chunkOverlap / chunkStrategy 传入会被后端忽略（契约明示，不报错）。
 */
export interface KnowledgeBaseUpdateRequest {
  name?: string | null
  description?: string | null
}

/** 知识库列表查询入参（契约 4.2） */
export interface KnowledgeBaseQuery extends PageQuery {
  /** 按 name 模糊匹配（不区分大小写） */
  keyword?: string | null
}

/** 删除知识库的副作用统计（契约 4.5 响应） */
export interface KnowledgeBaseDeleteResult {
  id: string
  deletedDocuments: number
  deletedNodes: number
  deletedEvalCases: number
}
