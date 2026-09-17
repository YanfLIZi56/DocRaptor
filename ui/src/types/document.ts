import type { FileType, StepStatus, TaskStatus } from './enums'
import type { PageQuery } from './common'

/** 文档对象（契约 4.7 列表元素 / 4.8 详情） */
export interface DocumentItem {
  id: string
  knowledgeBaseId: string
  knowledgeBaseName: string
  fileName: string
  fileType: FileType
  fileSize: number
  charCount: number
  chunkCount: number
  /** 唯一可写业务字段：false 表示禁用（数据保留，不参与检索） */
  enabled: boolean
  parseStatus: StepStatus
  chunkStatus: StepStatus
  embedStatus: StepStatus
  treeStatus: StepStatus
  parseError: string | null
  metadata: Record<string, unknown> | null
  createdAt: number
  updatedAt: number
}

/** 文档列表查询入参（契约 4.7） */
export interface DocumentQuery extends PageQuery {
  /** 按知识库筛选；不传返回全部 */
  knowledgeBaseId?: string | null
  /** true 只看启用；false 只看禁用；不传全部 */
  enabled?: boolean | null
  /** 按 fileName 模糊匹配 */
  keyword?: string | null
  /** 按建树状态过滤（stepStatus 枚举） */
  treeStatus?: StepStatus | null
}

/** 文档上传入参（契约 4.6，multipart/form-data） */
export interface DocumentUploadOptions {
  knowledgeBaseId: string
  /** 导入完成后是否自动建树，默认 true */
  buildTree?: boolean
  /** 自动建树时的深度上限，[1, 10]，默认 3 */
  maxLevel?: number
}

/** 文档上传响应（契约 4.6） */
export interface DocumentUploadResult {
  documentId: string
  taskId: string
  fileName: string
  fileType: FileType
  fileSize: number
  /** 任务初始状态 */
  status: TaskStatus
}

/** 启用/禁用文档响应（契约 4.8） */
export interface DocumentEnabledResult {
  id: string
  enabled: boolean
  updatedAt: number
}
