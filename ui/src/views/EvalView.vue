<script setup lang="ts">
/**
 * 召回率评估：
 * - 用例管理：录入（测试查询 + 期望命中的文本块 ID，可多选）、列表、增删改。契约 6.3 / 6.4
 * - 执行评估：同步或异步，展示 Recall@K / Hit Rate@K / MRR。契约 6.5
 */
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import { listAllKnowledgeBases } from '@/api/knowledgeBase'
import { listAllDocuments } from '@/api/document'
import { listAllChunksByDocument } from '@/api/chunk'
import { createEvalCase, deleteEvalCase, listEvalCases, runEvalAsync, runEvalSync, updateEvalCase } from '@/api/eval'
import { useTaskStore } from '@/stores/task'
import {
  EVAL_K_CANDIDATES,
  EVAL_K_DEFAULT,
  EVAL_SKIP_REASON_LABEL,
  PAGE_DEFAULT,
  PAGE_SIZE_DEFAULT,
  PAGE_SIZE_OPTIONS,
  RETRIEVAL_LIMITS,
  RETRIEVAL_MODE_HINT,
  RETRIEVAL_MODE_OPTIONS,
  RETRIEVAL_SCOPE,
  RETRIEVAL_SCOPE_LABEL,
  labelOf,
  levelShortLabel,
} from '@/constants'
import { formatNumber, formatPercent, formatScore, formatTime } from '@/utils/format'
import { asRow } from '@/utils/row'
import { isEvalRunResult } from '@/types'
import type {
  DocumentItem,
  EvalCase,
  EvalRunResult,
  KnowledgeBase,
  RetrievalMode,
  RetrievalScope,
} from '@/types'

const route = useRoute()
const taskStore = useTaskStore()

const knowledgeBases = ref<KnowledgeBase[]>([])
const knowledgeBaseId = ref<string>(typeof route.query.knowledgeBaseId === 'string' ? route.query.knowledgeBaseId : '')
const documents = ref<DocumentItem[]>([])

const loading = ref(false)
const cases = ref<EvalCase[]>([])
const total = ref(0)
const page = ref<number>(PAGE_DEFAULT)
const pageSize = ref<number>(PAGE_SIZE_DEFAULT)
const selectedCases = ref<EvalCase[]>([])

// ---------------------------------------------------------------- 用例编辑
const dialogVisible = ref(false)
const dialogRef = ref<FormInstance>()
const saving = ref(false)
const editingCaseId = ref<string>('')
const chunkOptions = ref<{ nodeId: string; label: string }[]>([])
const chunkLoading = ref(false)

const caseForm = reactive({
  name: '',
  queryText: '',
  remark: '',
  expectedDocumentId: '',
  expectedChunkIds: [] as string[],
})

const caseRules: FormRules = {
  name: [
    { required: true, message: '请输入用例名', trigger: 'blur' },
    { max: 128, message: '用例名最长 128 个字符', trigger: 'blur' },
  ],
  queryText: [{ required: true, message: '请输入测试查询原文', trigger: 'blur' }],
}

// ---------------------------------------------------------------- 评估执行
const running = ref(false)
const result = ref<EvalRunResult | null>(null)
const runForm = reactive({
  mode: 'HYBRID' as RetrievalMode,
  topK: RETRIEVAL_LIMITS.topK.default as number,
  kList: [...EVAL_K_DEFAULT] as number[],
  similarityThreshold: RETRIEVAL_LIMITS.similarityThreshold.default as number,
  hybridRatio: RETRIEVAL_LIMITS.hybridRatio.default as number,
  bm25Weight: RETRIEVAL_LIMITS.bm25Weight.default as number,
  rrfK: RETRIEVAL_LIMITS.rrfK.default as number,
  scope: 'ALL_LEVELS' as RetrievalScope,
  levels: [] as number[],
  async: false,
  caseScope: 'all' as 'all' | 'selected',
})

const isHybrid = computed(() => runForm.mode === 'HYBRID')
const isSpecifiedLevel = computed(() => runForm.scope === RETRIEVAL_SCOPE.SPECIFIED_LEVEL)
const kOptions = computed(() => EVAL_K_CANDIDATES.filter((k) => k <= runForm.topK))

const levelOptions = computed(() => {
  const options: { label: string; value: number }[] = []
  for (let level = RETRIEVAL_LIMITS.level.min; level <= RETRIEVAL_LIMITS.level.max; level += 1) {
    options.push({ label: levelShortLabel(level), value: level })
  }
  return options
})

async function loadKnowledgeBases(): Promise<void> {
  try {
    knowledgeBases.value = await listAllKnowledgeBases()
  } catch {
    knowledgeBases.value = []
  }
}

async function loadDocuments(): Promise<void> {
  if (knowledgeBaseId.value === '') {
    documents.value = []
    return
  }
  try {
    documents.value = await listAllDocuments(knowledgeBaseId.value)
  } catch {
    documents.value = []
  }
}

async function loadCases(): Promise<void> {
  if (knowledgeBaseId.value === '') {
    cases.value = []
    total.value = 0
    return
  }
  loading.value = true
  try {
    const res = await listEvalCases({
      knowledgeBaseId: knowledgeBaseId.value,
      page: page.value,
      pageSize: pageSize.value,
    })
    cases.value = res.list ?? []
    total.value = res.total ?? 0
  } catch {
    cases.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

function search(): void {
  page.value = PAGE_DEFAULT
  void loadCases()
}

async function loadChunkOptions(documentId: string): Promise<void> {
  if (documentId === '') {
    chunkOptions.value = []
    return
  }
  chunkLoading.value = true
  try {
    const chunks = await listAllChunksByDocument(documentId, { withContent: false })
    chunkOptions.value = chunks.map((chunk) => ({
      nodeId: chunk.nodeId,
      label: `#${chunk.chunkIndex} ｜ ${chunk.charCount} 字符 ｜ ${chunk.nodeId.slice(0, 8)}`,
    }))
  } catch {
    chunkOptions.value = []
  } finally {
    chunkLoading.value = false
  }
}

function openCreate(): void {
  editingCaseId.value = ''
  caseForm.name = ''
  caseForm.queryText = ''
  caseForm.remark = ''
  caseForm.expectedDocumentId = ''
  caseForm.expectedChunkIds = []
  chunkOptions.value = []
  dialogVisible.value = true
}

async function openEdit(raw: unknown): Promise<void> {
  const row = asRow<EvalCase>(raw)
  editingCaseId.value = row.id
  caseForm.name = row.name
  caseForm.queryText = row.queryText
  caseForm.remark = row.remark ?? ''
  caseForm.expectedChunkIds = row.expectedChunks.map((item) => item.nodeId)
  const firstDocumentId = row.expectedChunks.length > 0 ? (row.expectedChunks[0]?.documentId ?? '') : ''
  caseForm.expectedDocumentId = firstDocumentId
  // 已选期望块先作为候选项展示，再叠加该文档的完整块列表
  const presets = row.expectedChunks.map((item) => ({
    nodeId: item.nodeId,
    label: `#${item.chunkIndex} ｜ 已选 ｜ ${item.nodeId.slice(0, 8)}`,
  }))
  chunkOptions.value = presets
  dialogVisible.value = true
  if (firstDocumentId !== '') {
    await loadChunkOptions(firstDocumentId)
    const merged = [...presets]
    for (const option of chunkOptions.value) {
      if (!merged.some((item) => item.nodeId === option.nodeId)) merged.push(option)
    }
    chunkOptions.value = merged
  }
}

async function submitCase(): Promise<void> {
  const form = dialogRef.value
  if (!form) return
  try {
    await form.validate()
  } catch {
    return
  }
  if (caseForm.expectedChunkIds.length === 0) {
    ElMessage.warning('请至少选择一个「期望命中的文本块」')
    return
  }
  saving.value = true
  try {
    if (editingCaseId.value === '') {
      await createEvalCase({
        knowledgeBaseId: knowledgeBaseId.value,
        name: caseForm.name.trim(),
        queryText: caseForm.queryText,
        expectedChunkIds: [...caseForm.expectedChunkIds],
        remark: caseForm.remark.trim() === '' ? null : caseForm.remark,
      })
      ElMessage.success('用例创建成功')
    } else {
      // 契约 6.4：expectedChunkIds 传数组表示整体替换
      await updateEvalCase(editingCaseId.value, {
        name: caseForm.name.trim(),
        queryText: caseForm.queryText,
        expectedChunkIds: [...caseForm.expectedChunkIds],
        remark: caseForm.remark,
      })
      ElMessage.success('用例已更新')
    }
    dialogVisible.value = false
    await loadCases()
  } finally {
    saving.value = false
  }
}

async function handleDelete(raw: unknown): Promise<void> {
  const row = asRow<EvalCase>(raw)
  try {
    await ElMessageBox.confirm(`确认删除评估用例「${row.name}」？`, '删除确认', { type: 'warning' })
  } catch {
    return
  }
  await deleteEvalCase(row.id)
  ElMessage.success('已删除')
  await loadCases()
}

function onSelectionChange(rows: EvalCase[]): void {
  selectedCases.value = rows
}

async function run(): Promise<void> {
  if (knowledgeBaseId.value === '') {
    ElMessage.warning('请选择知识库')
    return
  }
  if (isSpecifiedLevel.value && runForm.levels.length === 0) {
    ElMessage.warning('检索范围为「指定层级」时必须至少选择一个层级（否则后端返回 40011）')
    return
  }
  if (runForm.kList.length === 0) {
    ElMessage.warning('请至少选择一个 K 值')
    return
  }
  if (runForm.caseScope === 'selected' && selectedCases.value.length === 0) {
    ElMessage.warning('请先在用例列表勾选要评估的用例')
    return
  }

  const caseIds = runForm.caseScope === 'selected' ? selectedCases.value.map((item) => item.id) : null
  const payload = {
    knowledgeBaseId: knowledgeBaseId.value,
    caseIds,
    topK: runForm.topK,
    kList: [...runForm.kList],
    mode: runForm.mode,
    similarityThreshold: runForm.similarityThreshold,
    hybridRatio: runForm.hybridRatio,
    bm25Weight: runForm.bm25Weight,
    rrfK: runForm.rrfK,
    scope: runForm.scope,
    levels: isSpecifiedLevel.value ? [...runForm.levels] : null,
  }

  running.value = true
  try {
    if (runForm.async) {
      const asyncRef = await runEvalAsync({ ...payload, async: true })
      taskStore.track(asyncRef.taskId, {
        title: '召回率评估',
        onFinish: (task) => {
          if (isEvalRunResult(task.result)) {
            result.value = task.result
            void loadCases()
          }
        },
      })
      ElMessage.success('已提交异步评估任务，可在「任务进度」抽屉查看')
    } else {
      result.value = await runEvalSync({ ...payload, async: false })
      ElMessage.success(`评估完成：参与用例 ${result.value.evaluatedCases} 个`)
      await loadCases()
    }
  } catch {
    if (runForm.async) result.value = null
  } finally {
    running.value = false
  }
}

function onKnowledgeBaseChange(): void {
  cases.value = []
  total.value = 0
  selectedCases.value = []
  result.value = null
  void loadDocuments()
  void loadCases()
}

watch(
  () => caseForm.expectedDocumentId,
  (documentId) => {
    if (!dialogVisible.value) return
    void loadChunkOptions(documentId)
  },
)

// K 列表必须 ≤ topK（契约 6.5）
watch(
  () => runForm.topK,
  (topK) => {
    runForm.kList = runForm.kList.filter((k) => k <= topK)
    if (runForm.kList.length === 0) runForm.kList = [topK]
  },
)

onMounted(async () => {
  await loadKnowledgeBases()
  await loadDocuments()
  await loadCases()
})
</script>

<template>
  <div class="page">
    <el-card shadow="never">
      <div class="toolbar">
        <el-select
          v-model="knowledgeBaseId"
          placeholder="请选择知识库"
          style="width: 260px"
          @change="onKnowledgeBaseChange"
        >
          <el-option v-for="kb in knowledgeBases" :key="kb.id" :label="kb.name" :value="kb.id" />
        </el-select>
        <el-button type="primary" :disabled="knowledgeBaseId === ''" @click="openCreate">新建用例</el-button>
        <el-text type="info">
          共 {{ formatNumber(total) }} 个用例
          <template v-if="selectedCases.length > 0">（已勾选 {{ selectedCases.length }} 个）</template>
        </el-text>
      </div>
    </el-card>

    <el-alert
      v-if="knowledgeBaseId === ''"
      type="warning"
      :closable="false"
      show-icon
      title="请先选择知识库：评估用例与执行都需要 knowledgeBaseId。"
    />

    <el-card shadow="never">
      <template #header>
        <div class="card-header"><span>评估用例列表</span></div>
      </template>
      <el-table
        v-loading="loading"
        :data="cases"
        border
        stripe
        row-key="id"
        style="width: 100%"
        @selection-change="onSelectionChange"
      >
        <template #empty>
          <el-empty description="暂无评估用例，点击「新建用例」录入测试查询与期望命中的文本块" />
        </template>
        <el-table-column type="selection" width="46" />
        <el-table-column prop="name" label="用例名" min-width="160" show-overflow-tooltip />
        <el-table-column prop="queryText" label="测试查询" min-width="240" show-overflow-tooltip />
        <el-table-column label="期望块" min-width="230">
          <template #default="{ row }">
            <div class="chunk-tags">
              <el-tag v-for="item in row.expectedChunks" :key="item.nodeId" size="small" type="info">
                #{{ item.chunkIndex }}
              </el-tag>
              <el-text v-if="row.expectedChunks.length === 0" type="info">—</el-text>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="启用" width="90" align="center">
          <template #default="{ row }">
            <el-tag size="small" :type="row.enabled ? 'success' : 'info'">
              {{ row.enabled ? '启用' : '禁用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="上次 Recall@K" width="130" align="right">
          <template #default="{ row }">{{ formatPercent(row.lastRecallAtK) }}</template>
        </el-table-column>
        <el-table-column label="上次 MRR" width="110" align="right">
          <template #default="{ row }">{{ formatScore(row.lastMrr, 4) }}</template>
        </el-table-column>
        <el-table-column label="上次评估时间" width="170">
          <template #default="{ row }">{{ formatTime(row.lastEvaluatedAt) }}</template>
        </el-table-column>
        <el-table-column prop="remark" label="备注" min-width="140" show-overflow-tooltip>
          <template #default="{ row }">{{ row.remark ?? '—' }}</template>
        </el-table-column>
        <el-table-column label="操作" width="130" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
            <el-button link type="danger" @click="handleDelete(row)">删除</el-button>
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
        @current-change="loadCases"
      />
    </el-card>

    <!-- 执行评估 -->
    <el-card shadow="never">
      <template #header>
        <div class="card-header"><span>执行评估</span></div>
      </template>
      <el-form label-width="160px" label-position="right">
        <el-form-item label="参与用例">
          <el-radio-group v-model="runForm.caseScope">
            <el-radio-button value="all">全部启用的用例</el-radio-button>
            <el-radio-button value="selected">仅勾选的用例</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item label="检索模式">
          <el-radio-group v-model="runForm.mode">
            <el-radio-button v-for="option in RETRIEVAL_MODE_OPTIONS" :key="option.value" :value="option.value">
              {{ option.label }}
            </el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item label=" ">
          <el-alert :title="labelOf(RETRIEVAL_MODE_HINT, runForm.mode)" type="info" :closable="false" show-icon />
        </el-form-item>
        <el-form-item label="topK（召回深度）">
          <el-input-number
            v-model="runForm.topK"
            :min="RETRIEVAL_LIMITS.topK.min"
            :max="RETRIEVAL_LIMITS.topK.max"
            :step="RETRIEVAL_LIMITS.topK.step"
          />
        </el-form-item>
        <el-form-item label="kList（计算哪些 K）">
          <el-select v-model="runForm.kList" multiple style="width: 420px">
            <el-option v-for="k in kOptions" :key="k" :label="`K = ${k}`" :value="k" />
          </el-select>
          <span class="hint">元素必须 ≤ topK，超出的会被后端裁剪并在 truncatedKList 中说明</span>
        </el-form-item>
        <el-form-item label="检索范围">
          <el-radio-group v-model="runForm.scope">
            <el-radio-button
              v-for="option in [
                RETRIEVAL_SCOPE.LEAF_ONLY,
                RETRIEVAL_SCOPE.ALL_LEVELS,
                RETRIEVAL_SCOPE.SPECIFIED_LEVEL,
              ]"
              :key="option"
              :value="option"
            >
              {{ labelOf(RETRIEVAL_SCOPE_LABEL, option) }}
            </el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="isSpecifiedLevel" label="levels" required>
          <el-select v-model="runForm.levels" multiple style="width: 420px">
            <el-option
              v-for="option in levelOptions"
              :key="option.value"
              :label="option.label"
              :value="option.value"
            />
          </el-select>
        </el-form-item>
        <el-form-item v-if="isHybrid" label="hybridRatio">
          <el-slider
            v-model="runForm.hybridRatio"
            :min="RETRIEVAL_LIMITS.hybridRatio.min"
            :max="RETRIEVAL_LIMITS.hybridRatio.max"
            :step="RETRIEVAL_LIMITS.hybridRatio.step"
            show-input
            class="slider"
          />
        </el-form-item>
        <el-form-item v-if="isHybrid" label="rrfK">
          <el-slider
            v-model="runForm.rrfK"
            :min="RETRIEVAL_LIMITS.rrfK.min"
            :max="RETRIEVAL_LIMITS.rrfK.max"
            :step="RETRIEVAL_LIMITS.rrfK.step"
            show-input
            class="slider"
          />
        </el-form-item>
        <el-form-item label="similarityThreshold">
          <el-slider
            v-model="runForm.similarityThreshold"
            :min="RETRIEVAL_LIMITS.similarityThreshold.min"
            :max="RETRIEVAL_LIMITS.similarityThreshold.max"
            :step="RETRIEVAL_LIMITS.similarityThreshold.step"
            show-input
            class="slider"
          />
        </el-form-item>
        <el-form-item label="异步执行">
          <el-switch v-model="runForm.async" />
          <span class="hint">开启后立即返回 taskId，进度在「任务进度」抽屉中查看，结果在任务 result 里</span>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="running" :disabled="knowledgeBaseId === ''" @click="run">
            执行评估
          </el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <!-- 评估结果 -->
    <el-card v-if="result" shadow="never">
      <template #header>
        <div class="card-header">
          <span>评估结果（Recall@K / Hit Rate@K / MRR）</span>
          <el-text type="info" size="small">
            mode={{ result.mode }} ｜ topK={{ result.topK }} ｜ 参与用例 {{ result.evaluatedCases }} 个 ｜
            平均耗时 {{ result.avgLatencyMs }} ms
          </el-text>
        </div>
      </template>

      <el-alert
        v-if="result.truncatedKList.length > 0"
        type="warning"
        :closable="false"
        show-icon
        :title="`以下 K 超过 topK 已被裁剪：${result.truncatedKList.join(', ')}`"
        class="mb"
      />
      <el-alert
        v-if="result.skippedCases.length > 0"
        type="warning"
        :closable="false"
        show-icon
        class="mb"
      >
        <template #title>
          被跳过的用例（{{ result.skippedCases.length }} 个）：
          <span v-for="item in result.skippedCases" :key="item.caseId" class="skipped">
            {{ item.name }}（{{ labelOf(EVAL_SKIP_REASON_LABEL, item.reason) }}）
          </span>
        </template>
      </el-alert>

      <el-table :data="result.metrics" border stripe style="width: 100%; max-width: 720px">
        <template #empty>
          <el-empty description="没有可用于评估的指标" />
        </template>
        <el-table-column label="K" prop="k" width="90" align="center">
          <template #default="{ row }">K = {{ row.k }}</template>
        </el-table-column>
        <el-table-column label="Recall@K" align="right">
          <template #default="{ row }">
            <span class="metric">{{ formatPercent(row.recall) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="Hit Rate@K" align="right">
          <template #default="{ row }">
            <span class="metric">{{ formatPercent(row.hitRate) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="MRR" align="right">
          <template #default="{ row }">
            <span class="metric">{{ formatScore(row.mrr, 4) }}</span>
          </template>
        </el-table-column>
      </el-table>

      <div class="section-title">逐用例明细（未命中清单也在此）</div>
      <el-table :data="result.perQuery" border stripe style="width: 100%">
        <template #empty>
          <el-empty description="无用例明细" />
        </template>
        <el-table-column prop="name" label="用例名" min-width="150" show-overflow-tooltip />
        <el-table-column prop="query" label="查询" min-width="220" show-overflow-tooltip />
        <el-table-column label="期望块数" width="100" align="right">
          <template #default="{ row }">{{ row.expectedChunkIds.length }}</template>
        </el-table-column>
        <el-table-column label="命中块数" width="100" align="right">
          <template #default="{ row }">{{ row.hitNodeIds.length }}</template>
        </el-table-column>
        <el-table-column label="首个命中排名" width="120" align="center">
          <template #default="{ row }">{{ row.firstHitRank ?? '未命中' }}</template>
        </el-table-column>
        <el-table-column label="未命中块 ID" min-width="260">
          <template #default="{ row }">
            <div class="chunk-tags">
              <el-tag v-for="id in row.missedNodeIds" :key="id" size="small" type="danger">
                {{ id }}
              </el-tag>
              <el-text v-if="row.missedNodeIds.length === 0" type="success">全部命中</el-text>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="耗时" width="100" align="right">
          <template #default="{ row }">{{ row.latencyMs }} ms</template>
        </el-table-column>
        <el-table-column label="检索日志 ID" min-width="200">
          <template #default="{ row }">
            <span class="mono">{{ row.retrievalLogId ?? '—' }}</span>
          </template>
        </el-table-column>
      </el-table>

      <el-descriptions :column="1" border size="small" class="mt">
        <el-descriptions-item label="生效参数">
          <span class="mono">{{ JSON.stringify(result.params) }}</span>
        </el-descriptions-item>
        <el-descriptions-item label="检索日志 ID">
          <span class="mono">{{ result.retrievalLogIds.join(', ') || '—' }}</span>
        </el-descriptions-item>
      </el-descriptions>
    </el-card>

    <!-- 用例编辑弹窗 -->
    <el-dialog
      v-model="dialogVisible"
      :title="editingCaseId === '' ? '新建评估用例' : '编辑评估用例'"
      width="720px"
      :close-on-click-modal="false"
    >
      <el-form ref="dialogRef" :model="caseForm" :rules="caseRules" label-width="130px">
        <el-form-item label="用例名" prop="name">
          <el-input v-model="caseForm.name" maxlength="128" show-word-limit placeholder="如：RAPTOR 聚类原理" />
        </el-form-item>
        <el-form-item label="测试查询" prop="queryText">
          <el-input
            v-model="caseForm.queryText"
            type="textarea"
            :rows="2"
            placeholder="如：RAPTOR 如何用 UMAP 和 GMM 构建摘要树？"
          />
        </el-form-item>
        <el-form-item label="期望命中块所属文档">
          <el-select v-model="caseForm.expectedDocumentId" clearable filterable placeholder="选择文档后加载其文本块" style="width: 100%">
            <el-option v-for="doc in documents" :key="doc.id" :label="doc.fileName" :value="doc.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="期望命中的块 ID" required>
          <el-select
            v-model="caseForm.expectedChunkIds"
            v-loading="chunkLoading"
            multiple
            filterable
            placeholder="只能选择该知识库下的 LEAF 文本块"
            style="width: 100%"
          >
            <el-option v-for="option in chunkOptions" :key="option.nodeId" :label="option.label" :value="option.nodeId" />
          </el-select>
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="caseForm.remark" type="textarea" :rows="2" placeholder="可选" />
        </el-form-item>
        <el-alert
          type="info"
          :closable="false"
          show-icon
          title="命中判定为严格口径：必须命中期望的叶子块 ID 本身，命中其父摘要节点不算命中。"
        />
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submitCase">保存</el-button>
      </template>
    </el-dialog>
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
  gap: 12px;
  flex-wrap: wrap;
}

.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.chunk-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
}

.pager {
  margin-top: 14px;
  justify-content: flex-end;
}

.hint {
  margin-left: 10px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.slider {
  width: 420px;
}

.mb {
  margin-bottom: 12px;
}

.mt {
  margin-top: 14px;
}

.section-title {
  margin: 18px 0 10px;
  font-weight: 600;
}

.metric {
  font-family: Consolas, Monaco, monospace;
}

.mono {
  font-family: Consolas, Monaco, monospace;
  font-size: 12px;
}

.skipped {
  margin-right: 10px;
}
</style>
