/**
 * 通用响应结构 —— 契约第 1.1 / 1.2 节。
 * 注意：所有接口（除 multipart 超限的 413）都返回 HTTP 200，成败只看 `code`。
 */

/** 统一响应包装体 */
export interface ApiResponse<T = unknown> {
  /** 0 = 成功；非 0 见契约第 3 节错误码表 */
  code: number
  /** 成功固定 "success"，失败为可直接展示的中文描述 */
  message: string
  /** 业务数据，无数据时为 null */
  data: T
}

/** 分页响应体（`data` 的形状） */
export interface PageResult<T> {
  list: T[]
  total: number
  page: number
  pageSize: number
}

/** 分页入参（page 默认 1，pageSize 默认 20、上限 200） */
export interface PageQuery {
  page?: number
  pageSize?: number
}
