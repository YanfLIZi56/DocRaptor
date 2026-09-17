import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { getAsyncTask } from '@/api/task'
import { isApiError } from '@/api/request'
import {
  ERROR_CODE,
  TASK_POLL_INTERVAL_MS,
  TASK_POLL_MAX_FAILURES,
  isTaskTerminal,
} from '@/constants'
import type { AsyncTask } from '@/types'

/** 被前端跟踪的异步任务（进度抽屉里的一项） */
export interface TrackedTask {
  taskId: string
  title: string
  /** 最近一次轮询拿到的任务快照；首次轮询返回前为 null */
  task: AsyncTask | null
  /** 已到达终态（SUCCESS / PARTIAL_SUCCESS / FAILED / CANCELED）或放弃轮询 */
  finished: boolean
  /** 连续轮询失败次数 */
  failures: number
  /** 放弃轮询时的原因 */
  failureMessage: string | null
}

export interface TrackTaskOptions {
  /** 进度抽屉里显示的任务标题，如「导入 raptor-paper.pdf」 */
  title?: string
  /** 是否自动打开进度抽屉，默认 true */
  openDrawer?: boolean
  /** 终态回调（四种终态都会触发），用于刷新列表等后续动作 */
  onFinish?: (task: AsyncTask) => void
}

/**
 * 异步任务进度 store（契约 1.3 / 7.1）：
 * 触发长任务拿到 taskId 后调用 track()，内部每 1000ms 轮询一次
 * GET /api/async-tasks/{taskId}，进入终态立即停止轮询。
 */
export const useTaskStore = defineStore('asyncTask', () => {
  const tracked = ref<TrackedTask[]>([])
  const drawerVisible = ref(false)

  /** taskId → setInterval 句柄（非响应式，避免深度代理定时器） */
  const timers = new Map<string, ReturnType<typeof setInterval>>()
  /** taskId → 终态回调 */
  const listeners = new Map<string, (task: AsyncTask) => void>()

  const runningCount = computed(() => tracked.value.filter((item) => !item.finished).length)
  const hasRunning = computed(() => runningCount.value > 0)

  function findEntry(taskId: string): TrackedTask | undefined {
    return tracked.value.find((item) => item.taskId === taskId)
  }

  function stopPolling(taskId: string): void {
    const timer = timers.get(taskId)
    if (timer !== undefined) {
      clearInterval(timer)
      timers.delete(taskId)
    }
  }

  function finish(taskId: string, entry: TrackedTask, task: AsyncTask | null): void {
    stopPolling(taskId)
    entry.finished = true
    const listener = listeners.get(taskId)
    listeners.delete(taskId)
    if (task) listener?.(task)
  }

  async function poll(taskId: string): Promise<void> {
    const entry = findEntry(taskId)
    if (!entry || entry.finished) {
      stopPolling(taskId)
      return
    }
    try {
      const task = await getAsyncTask(taskId)
      // 轮询期间条目可能被清掉
      const current = findEntry(taskId)
      if (!current || current.finished) return
      current.task = task
      current.failures = 0
      current.failureMessage = null
      if (isTaskTerminal(task.status)) {
        finish(taskId, current, task)
      }
    } catch (error) {
      const current = findEntry(taskId)
      if (!current || current.finished) return
      current.failures += 1
      // 任务不存在：继续轮询没有意义
      if (isApiError(error) && error.code === ERROR_CODE.TASK_NOT_FOUND) {
        current.failureMessage = error.message
        finish(taskId, current, null)
        return
      }
      if (current.failures >= TASK_POLL_MAX_FAILURES) {
        current.failureMessage = isApiError(error) ? error.message : '任务进度查询失败，已停止轮询'
        finish(taskId, current, null)
      }
    }
  }

  /** 开始跟踪一个 taskId 并立即轮询一次，随后每 1000ms 轮询一次 */
  function track(taskId: string, options: TrackTaskOptions = {}): TrackedTask {
    let entry = findEntry(taskId)
    if (!entry) {
      entry = {
        taskId,
        title: options.title ?? '异步任务',
        task: null,
        finished: false,
        failures: 0,
        failureMessage: null,
      }
      tracked.value.unshift(entry)
    } else {
      entry.finished = false
      entry.failures = 0
      entry.failureMessage = null
      if (options.title) entry.title = options.title
    }
    if (options.onFinish) listeners.set(taskId, options.onFinish)
    if (options.openDrawer ?? true) drawerVisible.value = true

    stopPolling(taskId)
    void poll(taskId)
    timers.set(
      taskId,
      setInterval(() => {
        void poll(taskId)
      }, TASK_POLL_INTERVAL_MS),
    )
    return entry
  }

  /** 停止跟踪并移除记录 */
  function remove(taskId: string): void {
    stopPolling(taskId)
    listeners.delete(taskId)
    tracked.value = tracked.value.filter((item) => item.taskId !== taskId)
  }

  /** 清掉所有已完成的记录（进行中的保留） */
  function clearFinished(): void {
    tracked.value = tracked.value.filter((item) => !item.finished)
  }

  /** 打开 / 关闭进度抽屉 */
  function openDrawer(): void {
    drawerVisible.value = true
  }

  function closeDrawer(): void {
    drawerVisible.value = false
  }

  /** 组件卸载 / 页面离开时的兜底清理 */
  function stopAll(): void {
    for (const taskId of Array.from(timers.keys())) stopPolling(taskId)
  }

  return {
    tracked,
    drawerVisible,
    runningCount,
    hasRunning,
    track,
    remove,
    clearFinished,
    openDrawer,
    closeDrawer,
    stopAll,
  }
})
