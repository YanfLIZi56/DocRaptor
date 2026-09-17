/**
 * 表格行类型收敛工具。
 *
 * Element Plus 的 el-table 具名插槽把 `row` 标注为 `DefaultRow`（= `Record<PropertyKey, any>`）：
 * 它带索引签名，但不满足具体接口的「必填字段」约束，直接传给强类型处理函数会报 TS2345
 * （Type 'DefaultRow' is missing the following properties from type '...'）。
 *
 * 表格的 `:data` 本身已是强类型数组（如 `ref<DocumentItem[]>`），因此行对象在运行时就是目标类型，
 * 这里在进入处理函数时显式收敛一次断言，保证断言位置集中、可审计。
 */
export function asRow<T>(row: unknown): T {
  return row as T
}
