<script setup lang="ts">
/**
 * 任务中心：异步任务列表与进度（契约 7.2）。
 * 长任务（导入 / 建树 / 评估）触发后可在「任务进度」抽屉实时跟踪，也可在本页查看历史。
 */
import { onMounted, onUnmounted, reactive, ref, watch } from 'vue'
import { listAllKnowledgeBases } from '@/api/knowledgeBase'
import { listAllDocuments } from '@/api/document'
import { listAsyncTasks } from '@/api/task'
import { useTaskStore } from '@/stores/task'
import {
  PAGE_DEFAULT,
  PAGE_SIZE_DEFAULT,
  PAGE_SIZE_OPTIONS,
  TASK_STAGE_LABEL,
  TASK_STAGE_PROGRESS_RANGE,
  TASK_STATUS_LABEL,
  TASK_STATUS_OPTIONS,
  TASK_STATUS_TAG,
  TASK_TYPE_LABEL,
  TASK_TYPE_OPTIONS,
  labelOf,
  progressStatusOf,
  tagTypeOf,
} from '@/constants'
import { formatJson, formatTime } from '@/utils/format'
import { asRow } from '@/utils/row'
import type { AsyncTask, DocumentItem, KnowledgeBase, TaskStatus, TaskType } from '@/types'

const taskStore = useTaskStore()

const loading = ref(false)
const tasks = ref<AsyncTask[]>([])
const total = ref(0)
const page = ref<number>(PAGE_DEFAULT)
const pageSize = ref<number>(PAGE_SIZE_DEFAULT)
const autoRefresh = ref(true)

const knowledgeBases = ref<KnowledgeBase[]>([])
const documents = ref<DocumentItem[]>([])

const filters = reactive({
  knowledgeBaseId: '',
  documentId: '',
  status: '' as '' | TaskStatus,
  taskType: '' as '' | TaskType,
})

const detailVisible = ref(false)
const detail = ref<AsyncTask | null>(null)

let timer: ReturnType<typeof setInterval> | undefined

async function loadOptions(): Promise<void> {
  try {
    knowledgeBases.value = await listAllKnowledgeBases()
  } catch {
    knowledgeBases.value = []
  }
  try {
    documents.value = await listAllDocuments(null)
  } catch {
    documents.value = []
  }
}

async function load(): Promise<void> {
  loading.value = true
  try {
    const res = await listAsyncTasks({
      knowledgeBaseId: filters.knowledgeBaseId === '' ? null : filters.knowledgeBaseId,
      documentId: filters.documentId === '' ? null : filters.documentId,
      status: filters.status === '' ? null : filters.status,
      taskType: filters.taskType === '' ? null : filters.taskType,
      page: page.value,
      pageSize: pageSize.value,
    })
    tasks.value = res.list ?? []
    total.value = res.total ?? 0
  } catch {
    tasks.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

function search(): void {
  page.value = PAGE_DEFAULT
  void load()
}

function resetFilters(): void {
  filters.knowledgeBaseId = ''
  filters.documentId = ''
  filters.status = ''
  filters.taskType = ''
  search()
}

function openDetail(raw: unknown): void {
  detail.value = asRow<AsyncTask>(raw)
  detailVisible.value = true
}

function trackTask(raw: unknown): void {
  const row = asRow<AsyncTask>(raw)
  taskStore.track(row.taskId, {
    title: `${labelOf(TASK_TYPE_LABEL, row.taskType)}：${row.documentName ?? row.taskId}`,
  })
}

function stageText(raw: unknown): string {
  const row = asRow<AsyncTask>(raw)
  return `${labelOf(TASK_STAGE_LABEL, row.currentStage)}（${labelOf(TASK_STAGE_PROGRESS_RANGE, row.currentStage)}）`
}

watch(
  () => filters.knowledgeBaseId,
  () => {
    filters.documentId = ''
  },
)

watch(autoRefresh, (enabled) => {
  if (enabled) {
    timer = setInterval(() => {
      void load()
    }, 5000)
  } else if (timer !== undefined) {
    clearInterval(timer)
    timer = undefined
  }
})

onMounted(async () => {
  await loadOptions()
  await load()
  if (autoRefresh.value) {
    timer = setInterval(() => {
      void load()
    }, 5000)
  }
})

onUnmounted(() => {
  if (timer !== undefined) clearInterval(timer)
})
</script>

<template>
  <div class="page">
    <el-card shadow="never">
      <div class="toolbar">
        <el-select v-model="filters.knowledgeBaseId" placeholder="全部知识库" clearable style="width: 200px">
          <el-option v-for="kb in knowledgeBases" :key="kb.id" :label="kb.name" :value="kb.id" />
        </el-select>
        <el-select v-model="filters.documentId" placeholder="全部文档" clearable filterable style="width: 240px">
          <el-option v-for="doc in documents" :key="doc.id" :label="doc.fileName" :value="doc.id" />
        </el-select>
        <el-select v-model="filters.status" placeholder="全部状态" clearable style="width: 150px">
          <el-option v-for="option in TASK_STATUS_OPTIONS" :key="option.value" :label="option.label" :value="option.value" />
        </el-select>
        <el-select v-model="filters.taskType" placeholder="全部类型" clearable style="width: 160px">
          <el-option v-for="option in TASK_TYPE_OPTIONS" :key="option.value" :label="option.label" :value="option.value" />
        </el-select>
        <el-button type="primary" @click="search">查询</el-button>
        <el-button @click="resetFilters">重置</el-button>
        <el-checkbox v-model="autoRefresh">自动刷新（5s）</el-checkbox>
        <el-button @click="load">立即刷新</el-button>
      </div>
    </el-card>

    <el-card shadow="never">
      <el-table v-loading="loading" :data="tasks" border stripe row-key="taskId" style="width: 100%">
        <template #empty>
          <el-empty description="暂无异步任务：上传文档、建树或执行评估后这里会出现记录" />
        </template>
        <el-table-column label="任务 ID" width="330">
          <template #default="{ row }">
            <span class="mono">{{ row.taskId }}</span>
          </template>
        </el-table-column>
        <el-table-column label="类型" width="130">
          <template #default="{ row }">{{ labelOf(TASK_TYPE_LABEL, row.taskType) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="110" align="center">
          <template #default="{ row }">
            <el-tag size="small" :type="tagTypeOf(TASK_STATUS_TAG, row.status)">
              {{ labelOf(TASK_STATUS_LABEL, row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="进度" min-width="220">
          <template #default="{ row }">
            <el-progress
              :percentage="Math.min(100, Math.max(0, row.progress))"
              :status="progressStatusOf(row.status)"
              :stroke-width="14"
              text-inside
            />
          </template>
        </el-table-column>
        <el-table-column label="当前阶段" min-width="180">
          <template #default="{ row }">{{ stageText(row) }}</template>
        </el-table-column>
        <el-table-column label="阶段说明" min-width="200" show-overflow-tooltip>
          <template #default="{ row }">{{ row.progressMessage ?? '—' }}</template>
        </el-table-column>
        <el-table-column label="文档" min-width="180" show-overflow-tooltip>
          <template #default="{ row }">{{ row.documentName ?? '—' }}</template>
        </el-table-column>
        <el-table-column label="重试" width="80" align="center" prop="retryCount" />
        <el-table-column label="创建时间" width="170">
          <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="结束时间" width="170">
          <template #default="{ row }">{{ formatTime(row.finishedAt) }}</template>
        </el-table-column>
        <el-table-column label="错误" min-width="200" show-overflow-tooltip>
          <template #default="{ row }">
            <el-text v-if="row.errorMessage" type="danger">{{ row.errorMessage }}</el-text>
            <span v-else>—</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="140" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openDetail(row)">详情</el-button>
            <el-button link type="primary" @click="trackTask(row)">跟踪</el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-pagination
        v-model:current-page="page"
        v-model:page-size="pageSize"
        class="pager"
        background
        layout="total, sizes, prev, pager, next, jumper"
        :total="total"
        :page-sizes="PAGE_SIZE_OPTIONS"
        @size-change="search"
        @current-change="load"
      />
    </el-card>

    <el-drawer v-model="detailVisible" title="任务详情" size="680px">
      <el-empty v-if="!detail" description="未选择任务" />
      <el-descriptions v-else :column="1" border size="small">
        <el-descriptions-item label="任务 ID"><span class="mono">{{ detail.taskId }}</span></el-descriptions-item>
        <el-descriptions-item label="任务类型">{{ labelOf(TASK_TYPE_LABEL, detail.taskType) }}</el-descriptions-item>
        <el-descriptions-item label="状态">{{ labelOf(TASK_STATUS_LABEL, detail.status) }}</el-descriptions-item>
        <el-descriptions-item label="进度">
          <el-progress
            :percentage="Math.min(100, Math.max(0, detail.progress))"
            :status="progressStatusOf(detail.status)"
          />
        </el-descriptions-item>
        <el-descriptions-item label="当前阶段">{{ stageText(detail) }}</el-descriptions-item>
        <el-descriptions-item label="阶段说明">{{ detail.progressMessage ?? '—' }}</el-descriptions-item>
        <el-descriptions-item label="知识库 ID">
          <span class="mono">{{ detail.knowledgeBaseId ?? '—' }}</span>
        </el-descriptions-item>
        <el-descriptions-item label="文档">
          {{ detail.documentName ?? '—' }}
          <span class="mono">{{ detail.documentId ?? '' }}</span>
        </el-descriptions-item>
        <el-descriptions-item label="重试次数">{{ detail.retryCount }}</el-descriptions-item>
        <el-descriptions-item label="错误信息">
          <el-text v-if="detail.errorMessage" type="danger">{{ detail.errorMessage }}</el-text>
          <span v-else>—</span>
        </el-descriptions-item>
        <el-descriptions-item label="创建 / 开始 / 结束">
          {{ formatTime(detail.createdAt) }} → {{ formatTime(detail.startedAt) }} → {{ formatTime(detail.finishedAt) }}
        </el-descriptions-item>
        <el-descriptions-item label="心跳时间">{{ formatTime(detail.heartbeatAt) }}</el-descriptions-item>
        <el-descriptions-item label="payload">
          <pre class="json">{{ formatJson(detail.payload) }}</pre>
        </el-descriptions-item>
        <el-descriptions-item label="result">
          <pre class="json">{{ formatJson(detail.result) }}</pre>
        </el-descriptions-item>
      </el-descriptions>
    </el-drawer>
  </div>
</template>

<style scoped>
.page {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.toolbar {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.pager {
  margin-top: 14px;
  justify-content: flex-end;
}

.mono {
  font-family: Consolas, Monaco, monospace;
  font-size: 12px;
}

.json {
  margin: 0;
  max-height: 240px;
  overflow: auto;
  font-size: 12px;
  font-family: Consolas, Monaco, monospace;
  white-space: pre-wrap;
  word-break: break-all;
}
</style>
