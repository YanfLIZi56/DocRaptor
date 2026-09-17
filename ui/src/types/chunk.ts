import type { NodeType } from './enums'
import type { PageQuery } from './common'

/**
 * 文本块对象（契约 4.9）。
 * 需求明文要求每块必须可见：来源文档 ID（documentId）、块序号（chunkIndex）、字符数（charCount）。
 */
export interface ChunkItem {
  nodeId: string
  /** 来源文档 ID */
  documentId: string
  documentName: string
  knowledgeBaseId: string
  /** 该接口固定返回 LEAF */
  nodeType: NodeType
  /** 叶子固定为 0 */
  level: number
  /** 块序号 */
  chunkIndex: number
  startChunkIndex: number
  endChunkIndex: number
  /** 字符数 */
  charCount: number
  tokenCount: number | null
  /** 建树前为 null；建树后为所属 level=1 摘要节点 ID */
  parentId: string | null
  hasEmbedding: boolean
  embeddingDimension: number | null
  /** withEmbedding=true 时返回向量前 8 维预览 */
  embeddingPreview: number[] | null
  /** withContent=false 时截断到前 120 字符 */
  content: string | null
  createdAt: number
}

/** 分块列表查询入参（契约 4.9） */
export interface ChunkQuery extends PageQuery {
  /** 与 documentId 同时传时以 documentId 为准 */
  knowledgeBaseId?: string | null
  documentId?: string | null
  /** false 时 content 只返回前 120 字符，默认 true */
  withContent?: boolean
  /** true 时额外返回 embeddingDimension 与向量前 8 维预览，默认 false */
  withEmbedding?: boolean
}
