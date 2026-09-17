import { get } from './request'
import type { ChunkItem, ChunkQuery, PageResult } from '@/types'

/** 模块1：分块列表查询（契约 4.9） */

const BASE = '/chunks'

/**
 * 分块列表（固定 nodeType=LEAF，按 documentId, chunkIndex 升序）。
 * 每块必须展示：documentId（来源文档 ID）、chunkIndex（块序号）、charCount（字符数）。
 */
export function listChunks(query: ChunkQuery = {}): Promise<PageResult<ChunkItem>> {
  return get<PageResult<ChunkItem>>(BASE, query)
}

/**
 * 拉取某文档的全部文本块（用于评估用例的「期望块」多选）。
 * 契约 pageSize 上限 200，超过部分需分页累加，这里做一次自动翻页兜底。
 */
export async function listAllChunksByDocument(
  documentId: string,
  options: { withContent?: boolean; maxPages?: number } = {},
): Promise<ChunkItem[]> {
  const pageSize = 200
  const maxPages = options.maxPages ?? 10
  const all: ChunkItem[] = []
  for (let page = 1; page <= maxPages; page += 1) {
    const result = await listChunks({
      documentId,
      page,
      pageSize,
      withContent: options.withContent ?? false,
    })
    const list = result.list ?? []
    all.push(...list)
    if (list.length < pageSize || all.length >= result.total) break
  }
  return all
}
