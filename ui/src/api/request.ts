import axios, { type AxiosError, type AxiosRequestConfig } from 'axios'
import { ElMessage } from 'element-plus'
import type { ApiResponse } from '@/types'

declare module 'axios' {
  export interface AxiosRequestConfig {
    /** 内部开关：true 时请求失败不弹 ElMessage（轮询、可选资源的 40400 等预期内失败用） */
    silent?: boolean
  }
}

/**
 * 业务错误：契约规定「所有接口都返回 HTTP 200，成败只看 code」，
 * 因此把非 0 的 code 包装成异常抛出，调用方可用 `code` 做分支（如 40400 未建树 → 友好空态）。
 */
export class ApiError extends Error {
  readonly code: number
  readonly data: unknown

  constructor(code: number, message: string, data: unknown = null) {
    super(message)
    this.name = 'ApiError'
    this.code = code
    this.data = data
  }
}

export function isApiError(error: unknown): error is ApiError {
  return error instanceof ApiError
}

/** 取错误码（非 ApiError 返回 undefined） */
export function errorCodeOf(error: unknown): number | undefined {
  return isApiError(error) ? error.code : undefined
}

/**
 * 注意：这里**不设置全局 Content-Type**。
 * axios 会为普通对象自动带上 application/json，为 FormData 自动带上 multipart/form-data（含 boundary）。
 */
export const http = axios.create({
  baseURL: '/api',
  // 同步评估 / 建树触发可能偏慢，给一个宽松的超时
  timeout: 120_000,
})

function notify(message: string): void {
  ElMessage.error(message)
}

http.interceptors.response.use(
  (response) => {
    const body = response.data as ApiResponse<unknown> | null | undefined
    if (body === null || body === undefined || typeof body !== 'object' || typeof body.code !== 'number') {
      const message = '响应格式异常：缺少 {code, message, data} 统一包装体'
      if (!response.config.silent) notify(message)
      return Promise.reject(new ApiError(-1, message, response.data))
    }
    if (body.code === 0) {
      // 统一解包：调用方拿到的直接是 data
      return body.data as never
    }
    const message = body.message || `请求失败（code=${body.code}）`
    if (!response.config.silent) notify(message)
    return Promise.reject(new ApiError(body.code, message, body.data))
  },
  (error: unknown) => {
    const axiosError = error as AxiosError<ApiResponse<unknown>> & { silent?: boolean }
    const silent = axiosError.config?.silent === true
    const wrapper = axiosError.response?.data
    // 契约唯一例外：multipart 超限由容器在进 Controller 前拦截，返回 HTTP 413 + 包装体 code=41301
    if (wrapper && typeof wrapper === 'object' && typeof wrapper.code === 'number') {
      const message = wrapper.message || `请求失败（code=${wrapper.code}）`
      if (!silent) notify(message)
      return Promise.reject(new ApiError(wrapper.code, message, wrapper.data))
    }
    const status = axiosError.response?.status
    let message: string
    if (axiosError.code === 'ECONNABORTED' || axiosError.code === 'ETIMEDOUT') {
      message = '请求超时，请稍后重试'
    } else if (status !== undefined) {
      message = `请求失败：HTTP ${status}`
    } else {
      message = '网络异常：无法连接后端服务（请确认后端已在 localhost:8080 启动）'
    }
    if (!silent) notify(message)
    return Promise.reject(new ApiError(status ?? -1, message, null))
  },
)

export interface RequestOptions extends AxiosRequestConfig {
  /** 失败时不弹提示 */
  silent?: boolean
}

function send<T>(config: AxiosRequestConfig): Promise<T> {
  // 拦截器已把 {code,message,data} 解包成 data，故此处直接断言为 T
  return http.request(config) as unknown as Promise<T>
}

/**
 * 清洗 query 参数：剔除 null / undefined / 空串。
 * 契约里「不传某参数 = 不限该维度」，而 axios 对 null 的序列化行为（`?key=` 还是直接省略）
 * 会直接影响后端 Spring 的参数绑定（如 enabled= / knowledgeBaseId= 收到空串会变成非法值），
 * 因此这里显式剔除，保证「筛选条件为空 ⇒ 该参数根本不出现」。
 */
function cleanParams(params?: object): Record<string, unknown> | undefined {
  if (!params) return undefined
  const cleaned: Record<string, unknown> = {}
  for (const [key, value] of Object.entries(params)) {
    if (value === null || value === undefined || value === '') continue
    cleaned[key] = value
  }
  return cleaned
}

export function get<T>(url: string, params?: object, options: RequestOptions = {}): Promise<T> {
  return send<T>({ url, method: 'GET', params: cleanParams(params), ...options })
}

export function post<T>(url: string, data?: unknown, options: RequestOptions = {}): Promise<T> {
  return send<T>({ url, method: 'POST', data, ...options })
}

export function put<T>(url: string, data?: unknown, options: RequestOptions = {}): Promise<T> {
  return send<T>({ url, method: 'PUT', data, ...options })
}

export function del<T>(url: string, params?: object, options: RequestOptions = {}): Promise<T> {
  return send<T>({ url, method: 'DELETE', params: cleanParams(params), ...options })
}
