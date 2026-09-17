/** 展示层格式化工具（时间戳 / 字节 / 分数等） */

const UNKNOWN = '—'

function pad2(value: number): string {
  return String(value).padStart(2, '0')
}

/** 毫秒级 Unix 时间戳 → `YYYY-MM-DD HH:mm:ss`（契约 1.1：所有时间字段都是毫秒时间戳） */
export function formatTime(value: number | null | undefined): string {
  if (value === null || value === undefined || value <= 0) return UNKNOWN
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return UNKNOWN
  return (
    `${date.getFullYear()}-${pad2(date.getMonth() + 1)}-${pad2(date.getDate())} ` +
    `${pad2(date.getHours())}:${pad2(date.getMinutes())}:${pad2(date.getSeconds())}`
  )
}

/** 仅日期部分 */
export function formatDate(value: number | null | undefined): string {
  const full = formatTime(value)
  return full === UNKNOWN ? UNKNOWN : full.slice(0, 10)
}

/** 字节数 → 人类可读 */
export function formatBytes(value: number | null | undefined): string {
  if (value === null || value === undefined || value < 0) return UNKNOWN
  if (value < 1024) return `${value} B`
  const kb = value / 1024
  if (kb < 1024) return `${kb.toFixed(1)} KB`
  const mb = kb / 1024
  if (mb < 1024) return `${mb.toFixed(2)} MB`
  return `${(mb / 1024).toFixed(2)} GB`
}

/** 整数千分位 */
export function formatNumber(value: number | null | undefined): string {
  if (value === null || value === undefined) return UNKNOWN
  return value.toLocaleString('zh-CN')
}

/** 小数保留（默认 4 位，用于各路原始分/融合分） */
export function formatScore(value: number | null | undefined, digits = 4): string {
  if (value === null || value === undefined || Number.isNaN(value)) return UNKNOWN
  return value.toFixed(digits)
}

/** [0,1] 的比例 → 百分比文本（0.5 → 50.00%） */
export function formatPercent(value: number | null | undefined, digits = 2): string {
  if (value === null || value === undefined || Number.isNaN(value)) return UNKNOWN
  return `${(value * 100).toFixed(digits)}%`
}

/** 毫秒耗时 → 人类可读 */
export function formatDuration(value: number | null | undefined): string {
  if (value === null || value === undefined || value < 0) return UNKNOWN
  if (value < 1000) return `${value} ms`
  const seconds = value / 1000
  if (seconds < 60) return `${seconds.toFixed(1)} s`
  const minutes = Math.floor(seconds / 60)
  const rest = Math.round(seconds - minutes * 60)
  return `${minutes} min ${rest} s`
}

/** UUID 短显（列表里省宽度用，全量 ID 通过 tooltip 展示） */
export function shortId(value: string | null | undefined, length = 8): string {
  if (value === null || value === undefined || value === '') return UNKNOWN
  return value.length <= length ? value : `${value.slice(0, length)}…`
}

/** 文本截断 */
export function truncate(value: string | null | undefined, max = 120): string {
  if (value === null || value === undefined || value === '') return UNKNOWN
  return value.length <= max ? value : `${value.slice(0, max)}…`
}

/** 对象 → 缩进 JSON（详情抽屉里展示 metadata / payload / result 用） */
export function formatJson(value: unknown): string {
  if (value === null || value === undefined) return UNKNOWN
  try {
    return JSON.stringify(value, null, 2)
  } catch {
    return String(value)
  }
}

/** 剪贴板复制 */
export async function copyText(value: string | null | undefined): Promise<boolean> {
  if (!value) return false
  try {
    await navigator.clipboard.writeText(value)
    return true
  } catch {
    return false
  }
}
