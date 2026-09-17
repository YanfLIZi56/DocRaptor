import { del, get, post, put } from './request'
import { PAGE_DEFAULT, PAGE_SIZE_MAX } from '@/constants'
import type {
  KnowledgeBase,
  KnowledgeBaseCreateRequest,
  KnowledgeBaseDeleteResult,
  KnowledgeBaseQuery,
  KnowledgeBaseUpdateRequest,
  PageResult,
} from '@/types'

/** 模块1：知识库管理（契约 4.1 ~ 4.5） */

const BASE = '/knowledge-bases'

/** 2. 新建知识库（可配置 chunkSize / chunkOverlap / chunkStrategy） */
export function createKnowledgeBase(data: KnowledgeBaseCreateRequest): Promise<KnowledgeBase> {
  return post<KnowledgeBase>(BASE, data)
}

/** 3. 知识库列表（分页 + keyword 模糊匹配） */
export function listKnowledgeBases(query: KnowledgeBaseQuery = {}): Promise<PageResult<KnowledgeBase>> {
  return get<PageResult<KnowledgeBase>>(BASE, query)
}

/** 4. 知识库详情 */
export function getKnowledgeBase(id: string): Promise<KnowledgeBase> {
  return get<KnowledgeBase>(`${BASE}/${id}`)
}

/** 5. 重命名 / 改描述（分块参数不可改，传入会被后端忽略） */
export function updateKnowledgeBase(
  id: string,
  data: KnowledgeBaseUpdateRequest,
): Promise<KnowledgeBase> {
  return put<KnowledgeBase>(`${BASE}/${id}`, data)
}

/** 6. 删除知识库（必须显式 confirm=true，级联删文档与节点） */
export function deleteKnowledgeBase(id: string): Promise<KnowledgeBaseDeleteResult> {
  return del<KnowledgeBaseDeleteResult>(`${BASE}/${id}`, { confirm: true })
}

/**
 * 一次性拉取全部知识库（供下拉框使用）。
 * 契约 pageSize 上限 200，超出部分不在本方法处理范围内（本项目知识库数量远小于 200）。
 */
export async function listAllKnowledgeBases(): Promise<KnowledgeBase[]> {
  const result = await listKnowledgeBases({ page: PAGE_DEFAULT, pageSize: PAGE_SIZE_MAX })
  return result.list ?? []
}
