<script setup lang="ts">
/**
 * 异步任务进度抽屉（可复用）：配合 stores/task.ts 使用。
 * 契约 1.3：progress（0~100）可直接绑定进度条，currentStage 展示当前阶段，
 * 终态（SUCCESS / PARTIAL_SUCCESS / FAILED / CANCELED）自动停止轮询。
 */
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { storeToRefs } from 'pinia'
import { useTaskStore } from '@/stores/task'
import {
  TASK_STAGE_LABEL,
  TASK_STAGE_PROGRESS_RANGE,
  TASK_STATUS_LABEL,
  TASK_STATUS_TAG,
  TASK_TYPE_LABEL,
  labelOf,
  progressStatusOf,
  tagTypeOf,
} from '@/constants'
import { formatDuration, formatPercent, formatScore, formatTime, shortId } from '@/utils/format'
import { isDocumentTaskResult, isEvalRunResult, isRaptorBuildTaskResult } from '@/types'
import type { AsyncTask } from '@/types'

const taskStore = useTaskStore()
const { tracked, drawerVisible } = storeToRefs(taskStore)
const router = useRouter()

const title = computed(() => `异步任务进度（进行中 ${taskStore.runningCount} / 共 ${tracked.value.length}）`)

function progressOf(task: AsyncTask | null): number {
  if (!task) return 0
  return Math.min(100, Math.max(0, Math.round(task.progress)))
}

function stageText(task: AsyncTask | null): string {
  if (!task) return '等待首次轮询…'
  const name = labelOf(TASK_STAGE_LABEL, task.currentStage)
  return `${name}（进度区间 ${labelOf(TASK_STAGE_PROGRESS_RANGE, task.currentStage)}）`
}

/** 终态结果摘要：按 taskType 的 result 结构渲染（契约 7.1） */
function resultSummary(task: AsyncTask | null): string {
  const result = task?.result
  if (!result) return ''
  if (isEvalRunResult(result)) {
    const last = result.metrics.length > 0 ? result.metrics[result.metrics.length - 1] : undefined
    return (
      `评估完成：${result.evaluatedCases} 个用例参与，` +
      `Recall@${last?.k ?? '-'} = ${formatPercent(last?.recall)}，MRR = ${formatScore(last?.mrr, 4)}`
    )
  }
  if (isRaptorBuildTaskResult(result)) {
    return (
      `建树完成：深度 ${result.actualDepth}，摘要节点 ${result.summaryNodeCount} 个，` +
      `根节点 ${shortId(result.rootNodeId)}，唯一根 ${result.hasUniqueRoot ? '是' : '否'}，` +
      `降级摘要 ${result.degradedSummaryCount} 个，耗时 ${formatDuration(result.durationMs)}`
    )
  }
  if (isDocumentTaskResult(result)) {
    return (
      `导入完成：解析 ${result.charCount} 字符，分块 ${result.chunkCount} 个，` +
      `向量化 ${result.embeddedCount} 个，耗时 ${formatDuration(result.durationMs)}`
    )
  }
  return ''
}

function goTaskCenter(): void {
  drawerVisible.value = false
  void router.push({ name: 'tasks' })
}
</script>

<template>
  <el-drawer v-model="drawerVisible" :title="title" size="620px" direction="rtl">
    <template #header>
      <div class="drawer-header">
        <span>{{ title }}</span>
        <el-button
          v-if="tracked.length > 0"
          link
          type="primary"
          @click="taskStore.clearFinished()"
        >
          清除已完成
        </el-button>
      </div>
    </template>

    <el-empty v-if="tracked.length === 0" description="暂无被跟踪的任务：上传文档 / 建树 / 评估触发后会自动出现在这里" />

    <div v-else class="task-list">
      <el-card v-for="item in tracked" :key="item.taskId" shadow="never" class="task-card">
        <template #header>
          <div class="task-card__header">
            <span class="task-card__title">{{ item.title }}</span>
            <el-tag v-if="item.task" :type="tagTypeOf(TASK_STATUS_TAG, item.task.status)" size="small">
              {{ labelOf(TASK_STATUS_LABEL, item.task.status) }}
            </el-tag>
            <el-tag v-else size="small" type="info">轮询中</el-tag>
          </div>
        </template>

        <el-progress
          :percentage="progressOf(item.task)"
          :status="progressStatusOf(item.task?.status)"
          :stroke-width="14"
          text-inside
        />

        <el-descriptions :column="1" size="small" class="task-meta">
          <el-descriptions-item label="任务 ID">
            <span class="mono">{{ item.taskId }}</span>
          </el-descriptions-item>
          <el-descriptions-item label="任务类型">
            {{ labelOf(TASK_TYPE_LABEL, item.task?.taskType) }}
          </el-descriptions-item>
          <el-descriptions-item label="当前阶段">{{ stageText(item.task) }}</el-descriptions-item>
          <el-descriptions-item label="阶段说明">
            {{ item.task?.progressMessage ?? '—' }}
          </el-descriptions-item>
          <el-descriptions-item label="关联文档">
            {{ item.task?.documentName ?? '—' }}
          </el-descriptions-item>
          <el-descriptions-item label="重试次数">{{ item.task?.retryCount ?? 0 }}</el-descriptions-item>
          <el-descriptions-item label="创建时间">{{ formatTime(item.task?.createdAt) }}</el-descriptions-item>
          <el-descriptions-item label="结束时间">{{ formatTime(item.task?.finishedAt) }}</el-descriptions-item>
        </el-descriptions>

        <el-alert
          v-if="item.task?.errorMessage"
          type="error"
          :closable="false"
          show-icon
          :title="item.task.errorMessage"
          class="task-alert"
        />
        <el-alert
          v-else-if="item.failureMessage"
          type="warning"
          :closable="false"
          show-icon
          :title="`已停止轮询：${item.failureMessage}`"
          class="task-alert"
        />
        <el-alert
          v-else-if="resultSummary(item.task)"
          type="success"
          :closable="false"
          show-icon
          :title="resultSummary(item.task)"
          class="task-alert"
        />

        <div class="task-card__footer">
          <el-button size="small" @click="goTaskCenter">去任务中心</el-button>
          <el-button size="small" type="danger" plain @click="taskStore.remove(item.taskId)">
            {{ item.finished ? '移除' : '停止跟踪' }}
          </el-button>
        </div>
      </el-card>
    </div>
  </el-drawer>
</template>

<style scoped>
.drawer-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  width: 100%;
}

.task-list {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.task-card__header {
  display: flex;
  align-items: center;
  gap: 8px;
}

.task-card__title {
  font-weight: 600;
  flex: 1;
}

.task-meta {
  margin-top: 12px;
}

.task-alert {
  margin-top: 12px;
}

.task-card__footer {
  margin-top: 12px;
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}

.mono {
  font-family: Consolas, Monaco, monospace;
  font-size: 12px;
}
</style>
