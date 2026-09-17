import { get, post, put } from './request'
import { PAGE_DEFAULT, PAGE_SIZE_MAX } from '@/constants'
import type {
  DocumentEnabledResult,
  DocumentItem,
  DocumentQuery,
  DocumentUploadOptions,
  DocumentUploadResult,
  PageResult,
} from '@/types'

/** 模块1：文档管理（契约 4.6 ~ 4.8） */

const BASE = '/documents'

/**
 * 上传导入（multipart/form-data，契约 4.6）。
 * 单文件 ≤10MB，扩展名 ∈ {pdf, docx, md, markdown, txt}；返回 taskId 后由前端轮询进度。
 */
export function uploadDocument(file: File, options: DocumentUploadOptions): Promise<DocumentUploadResult> {
  const form = new FormData()
  form.append('file', file)
  form.append('knowledgeBaseId', options.knowledgeBaseId)
  form.append('buildTree', String(options.buildTree ?? true))
  form.append('maxLevel', String(options.maxLevel ?? 3))
  // FormData 交给浏览器/axios 自行补齐 Content-Type 与 boundary，不要手动设置
  return post<DocumentUploadResult>(`${BASE}/upload`, form, { timeout: 300_000 })
}

/** 文档列表（可按 knowledgeBaseId / enabled / keyword / treeStatus 筛选） */
export function listDocuments(query: DocumentQuery = {}): Promise<PageResult<DocumentItem>> {
  return get<PageResult<DocumentItem>>(BASE, query)
}

/** 文档详情 */
export function getDocument(id: string): Promise<DocumentItem> {
  return get<DocumentItem>(`${BASE}/${id}`)
}

/** 启用 / 禁用文档（唯一可写业务字段；没有删除文档的接口） */
export function updateDocumentEnabled(id: string, enabled: boolean): Promise<DocumentEnabledResult> {
  return put<DocumentEnabledResult>(`${BASE}/${id}/enabled`, { enabled })
}

/** 一次性拉取文档（供下拉框使用） */
export async function listAllDocuments(knowledgeBaseId?: string | null): Promise<DocumentItem[]> {
  const result = await listDocuments({
    knowledgeBaseId: knowledgeBaseId ?? null,
    page: PAGE_DEFAULT,
    pageSize: PAGE_SIZE_MAX,
  })
  return result.list ?? []
}
