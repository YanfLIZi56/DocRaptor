<script setup lang="ts">
/**
 * 召回测试（核心页面）：
 * - 三种模式：纯向量 / 纯 BM25 / 混合（RRF），分别调 /api/retrieval/vector|bm25|hybrid
 * - 可调参数：topK / similarityThreshold / hybridRatio / rrfK（默认 60）
 * - 检索范围：仅叶子 / 全部层级(折叠树) / 指定层级（选后者必须填 levels）
 * - 结果展示：内容、层级、节点类型、来源文档、各路原始排名与分数（向量分、BM25 分、RRF 融合分）
 * 契约 6.1、6.2
 */
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { listAllDocuments } from '@/api/document'
import { listAllKnowledgeBases } from '@/api/knowledgeBase'
import { listRetrievalLogs, searchByMode } from '@/api/retrieval'
import {
  LOG_TYPE_LABEL,
  LOG_TYPE_OPTIONS,
  NODE_TYPE,
  NODE_TYPE_LABEL,
  NODE_TYPE_TAG,
  PAGE_DEFAULT,
  PAGE_SIZE_DEFAULT,
  PAGE_SIZE_OPTIONS,
  RETRIEVAL_LIMITS,
  RETRIEVAL_LOG_MODE_LABEL,
  RETRIEVAL_LOG_MODE_OPTIONS,
  RETRIEVAL_MODE_HINT,
  RETRIEVAL_MODE_LABEL,
  RETRIEVAL_MODE_OPTIONS,
  RETRIEVAL_SCOPE,
  RETRIEVAL_SCOPE_HINT,
  RETRIEVAL_SCOPE_LABEL,
  labelOf,
  levelShortLabel,
  tagTypeOf,
} from '@/constants'
import { formatDuration, formatScore, formatTime, shortId } from '@/utils/format'
import type {
  DocumentItem,
  KnowledgeBase,
  RetrievalHit,
  RetrievalLog,
  RetrievalLogMode,
  RetrievalMode,
  RetrievalRequest,
  RetrievalResponse,
  RetrievalScope,
} from '@/types'

const route = useRoute()
const activeTab = ref<'search' | 'logs'>('search')

// ---------------------------------------------------------------- 检索条件
const knowledgeBases = ref<KnowledgeBase[]>([])
const documents = ref<DocumentItem[]>([])

const form = reactive({
  knowledgeBaseId: typeof route.query.knowledgeBaseId === 'string' ? route.query.knowledgeBaseId : '',
  query: '',
  mode: 'HYBRID' as RetrievalMode,
  topK: RETRIEVAL_LIMITS.topK.default as number,
  similarityThreshold: RETRIEVAL_LIMITS.similarityThreshold.default as number,
  hybridRatio: RETRIEVAL_LIMITS.hybridRatio.default as number,
  bm25Weight: RETRIEVAL_LIMITS.bm25Weight.default as number,
  rrfK: RETRIEVAL_LIMITS.rrfK.default as number,
  scope: 'ALL_LEVELS' as RetrievalScope,
  levels: [] as number[],
  documentIds: [] as string[],
  withContent: true,
  withScoreBreakdown: true,
})

const searching = ref(false)
const result = ref<RetrievalResponse | null>(null)

const levelOptions = computed(() => {
  const options: { label: string; value: number }[] = []
  for (let level = RETRIEVAL_LIMITS.level.min; level <= RETRIEVAL_LIMITS.level.max; level += 1) {
    options.push({ label: levelShortLabel(level), value: level })
  }
  return options
})

const isHybrid = computed(() => form.mode === 'HYBRID')
const isSpecifiedLevel = computed(() => form.scope === RETRIEVAL_SCOPE.SPECIFIED_LEVEL)

/** 契约 8.7：scope=SPECIFIED_LEVEL 且 levels 为空时前端先拦住，不发请求 */
function validate(): boolean {
  if (form.knowledgeBaseId === '') {
    ElMessage.warning('请选择知识库')
    return false
  }
  if (form.query.trim() === '') {
    ElMessage.warning('请输入查询内容')
    return false
  }
  if (form.query.length > 2000) {
    ElMessage.warning('查询内容最长 2000 字符')
    return false
  }
  if (isSpecifiedLevel.value && form.levels.length === 0) {
    ElMessage.warning('检索范围为「指定层级」时必须至少选择一个层级（否则后端返回 40011）')
    return false
  }
  return true
}

function buildRequest(): RetrievalRequest {
  return {
    knowledgeBaseId: form.knowledgeBaseId,
    query: form.query,
    topK: form.topK,
    similarityThreshold: form.similarityThreshold,
    hybridRatio: form.hybridRatio,
    bm25Weight: form.bm25Weight,
    rrfK: form.rrfK,
    scope: form.scope,
    levels: isSpecifiedLevel.value ? [...form.levels] : null,
    documentIds: form.documentIds.length > 0 ? [...form.documentIds] : null,
    withContent: form.withContent,
    withScoreBreakdown: form.withScoreBreakdown,
  }
}

async function search(): Promise<void> {
  if (!validate()) return
  searching.value = true
  try {
    result.value = await searchByMode(form.mode, buildRequest())
    ElMessage.success(`检索完成：${result.value.totalHits} 条命中，耗时 ${result.value.costMs} ms`)
  } catch {
    result.value = null
  } finally {
    searching.value = false
  }
}

function resetForm(): void {
  form.query = ''
  form.mode = 'HYBRID'
  form.topK = RETRIEVAL_LIMITS.topK.default
  form.similarityThreshold = RETRIEVAL_LIMITS.similarityThreshold.default
  form.hybridRatio = RETRIEVAL_LIMITS.hybridRatio.default
  form.bm25Weight = RETRIEVAL_LIMITS.bm25Weight.default
  form.rrfK = RETRIEVAL_LIMITS.rrfK.default
  form.scope = 'ALL_LEVELS'
  form.levels = []
  form.documentIds = []
  result.value = null
}

/** SUMMARY 行用不同底色区分（契约 8.5） */
function rowClassName(data: { row: RetrievalHit }): string {
  return data.row.nodeType === NODE_TYPE.SUMMARY ? 'dr-summary-row' : ''
}

// ---------------------------------------------------------------- 检索日志
const logs = ref<RetrievalLog[]>([])
const logsLoading = ref(false)
const logsTotal = ref(0)
const logsPage = ref<number>(PAGE_DEFAULT)
const logsPageSize = ref<number>(PAGE_SIZE_DEFAULT)
const logRange = ref<'all' | '1h' | '24h' | '7d'>('all')
const logFilters = reactive({
  knowledgeBaseId: '',
  mode: '' as '' | RetrievalLogMode,
  logType: '' as '' | 'SEARCH' | 'EVAL',
  queryKeyword: '',
})

const LOG_RANGE_OPTIONS = [
  { label: '全部时间', value: 'all' },
  { label: '最近 1 小时', value: '1h' },
  { label: '最近 24 小时', value: '24h' },
  { label: '最近 7 天', value: '7d' },
] as const

function startTimeOf(range: 'all' | '1h' | '24h' | '7d'): number | null {
  const now = Date.now()
  switch (range) {
    case '1h':
      return now - 60 * 60 * 1000
    case '24h':
      return now - 24 * 60 * 60 * 1000
    case '7d':
      return now - 7 * 24 * 60 * 60 * 1000
    default:
      return null
  }
}

async function loadLogs(): Promise<void> {
  logsLoading.value = true
  try {
    const res = await listRetrievalLogs({
      knowledgeBaseId: logFilters.knowledgeBaseId === '' ? null : logFilters.knowledgeBaseId,
      mode: logFilters.mode === '' ? null : logFilters.mode,
      logType: logFilters.logType === '' ? null : logFilters.logType,
      queryKeyword: logFilters.queryKeyword.trim() === '' ? null : logFilters.queryKeyword.trim(),
      startTime: startTimeOf(logRange.value),
      page: logsPage.value,
      pageSize: logsPageSize.value,
    })
    logs.value = res.list ?? []
    logsTotal.value = res.total ?? 0
  } catch {
    logs.value = []
    logsTotal.value = 0
  } finally {
    logsLoading.value = false
  }
}

function searchLogs(): void {
  logsPage.value = PAGE_DEFAULT
  void loadLogs()
}

// ---------------------------------------------------------------- 初始化
async function loadKnowledgeBasesAndDocuments(): Promise<void> {
  try {
    knowledgeBases.value = await listAllKnowledgeBases()
  } catch {
    knowledgeBases.value = []
  }
  await loadDocuments()
}

async function loadDocuments(): Promise<void> {
  try {
    documents.value = await listAllDocuments(form.knowledgeBaseId === '' ? null : form.knowledgeBaseId)
  } catch {
    documents.value = []
  }
}

watch(
  () => form.knowledgeBaseId,
  async () => {
    form.documentIds = []
    await loadDocuments()
  },
)

watch(activeTab, (tab) => {
  if (tab === 'logs') void loadLogs()
})

onMounted(() => {
  void loadKnowledgeBasesAndDocuments()
})
</script>

<template>
  <div class="page">
    <el-tabs v-model="activeTab">
      <!-- ============================ 召回测试 ============================ -->
      <el-tab-pane label="召回测试" name="search">
        <el-card shadow="never">
          <el-form label-width="150px" label-position="right">
            <el-form-item label="知识库" required>
              <el-select v-model="form.knowledgeBaseId" placeholder="请选择知识库" style="width: 420px">
                <el-option v-for="kb in knowledgeBases" :key="kb.id" :label="kb.name" :value="kb.id" />
              </el-select>
            </el-form-item>

            <el-form-item label="查询内容" required>
              <el-input
                v-model="form.query"
                type="textarea"
                :rows="2"
                maxlength="2000"
                show-word-limit
                placeholder="如：RAPTOR 如何用 UMAP 和 GMM 构建摘要树？"
              />
            </el-form-item>

            <el-form-item label="检索模式">
              <el-radio-group v-model="form.mode">
                <el-radio-button
                  v-for="option in RETRIEVAL_MODE_OPTIONS"
                  :key="option.value"
                  :value="option.value"
                >
                  {{ option.label }}
                </el-radio-button>
              </el-radio-group>
            </el-form-item>
            <el-form-item label=" ">
              <el-alert :title="labelOf(RETRIEVAL_MODE_HINT, form.mode)" type="info" :closable="false" show-icon />
            </el-form-item>

            <el-divider content-position="left">可调参数</el-divider>

            <el-form-item label="topK（返回条数）">
              <el-input-number
                v-model="form.topK"
                :min="RETRIEVAL_LIMITS.topK.min"
                :max="RETRIEVAL_LIMITS.topK.max"
                :step="RETRIEVAL_LIMITS.topK.step"
              />
              <span class="hint">范围 1 ~ 100，超过 100 后端返回 40002</span>
            </el-form-item>

            <el-form-item label="similarityThreshold">
              <el-slider
                v-model="form.similarityThreshold"
                :min="RETRIEVAL_LIMITS.similarityThreshold.min"
                :max="RETRIEVAL_LIMITS.similarityThreshold.max"
                :step="RETRIEVAL_LIMITS.similarityThreshold.step"
                show-input
                class="slider"
              />
              <span class="hint">仅向量路硬过滤：向量原始分低于该值的候选被丢弃</span>
            </el-form-item>

            <template v-if="isHybrid">
              <el-form-item label="hybridRatio">
                <el-slider
                  v-model="form.hybridRatio"
                  :min="RETRIEVAL_LIMITS.hybridRatio.min"
                  :max="RETRIEVAL_LIMITS.hybridRatio.max"
                  :step="RETRIEVAL_LIMITS.hybridRatio.step"
                  show-input
                  class="slider"
                />
                <span class="hint">向量路权重 w_vector；BM25 权重 = (1 - hybridRatio) × bm25Weight</span>
              </el-form-item>

              <el-form-item label="bm25Weight">
                <el-slider
                  v-model="form.bm25Weight"
                  :min="RETRIEVAL_LIMITS.bm25Weight.min"
                  :max="RETRIEVAL_LIMITS.bm25Weight.max"
                  :step="RETRIEVAL_LIMITS.bm25Weight.step"
                  show-input
                  class="slider"
                />
                <span class="hint">BM25 路整体缩放系数，范围 0 ~ 10</span>
              </el-form-item>

              <el-form-item label="rrfK">
                <el-slider
                  v-model="form.rrfK"
                  :min="RETRIEVAL_LIMITS.rrfK.min"
                  :max="RETRIEVAL_LIMITS.rrfK.max"
                  :step="RETRIEVAL_LIMITS.rrfK.step"
                  show-input
                  class="slider"
                />
                <span class="hint">RRF 平滑常数 k，默认 60，范围 1 ~ 1000</span>
              </el-form-item>
            </template>

            <el-divider content-position="left">检索范围</el-divider>

            <el-form-item label="scope">
              <el-radio-group v-model="form.scope">
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
            <el-form-item label=" ">
              <el-alert :title="labelOf(RETRIEVAL_SCOPE_HINT, form.scope)" type="info" :closable="false" show-icon />
            </el-form-item>

            <el-form-item v-if="isSpecifiedLevel" label="levels" required>
              <el-select v-model="form.levels" multiple placeholder="选择层级（0 为叶子层）" style="width: 420px">
                <el-option
                  v-for="option in levelOptions"
                  :key="option.value"
                  :label="option.label"
                  :value="option.value"
                />
              </el-select>
              <span class="hint">层级 0 ~ 10，未选择时前端直接拦截（后端会报 40011）</span>
            </el-form-item>

            <el-form-item label="限定文档">
              <el-select
                v-model="form.documentIds"
                multiple
                clearable
                filterable
                placeholder="不选表示不限文档"
                style="width: 420px"
              >
                <el-option
                  v-for="doc in documents"
                  :key="doc.id"
                  :label="`${doc.fileName}${doc.enabled ? '' : '（已禁用）'}`"
                  :value="doc.id"
                />
              </el-select>
              <span class="hint">指定的文档全部被禁用时后端返回 40904</span>
            </el-form-item>

            <el-form-item label="返回选项">
              <el-checkbox v-model="form.withContent">返回完整内容</el-checkbox>
              <el-checkbox v-model="form.withScoreBreakdown">返回 scoreBreakdown 明细</el-checkbox>
            </el-form-item>

            <el-form-item>
              <el-button type="primary" :loading="searching" @click="search">开始检索</el-button>
              <el-button @click="resetForm">重置</el-button>
            </el-form-item>
          </el-form>
        </el-card>

        <el-card v-if="result" shadow="never" class="result-card">
          <template #header>
            <div class="card-header">
              <span>检索结果</span>
              <el-text type="info" size="small">
                mode={{ result.mode }} ｜ scope={{ result.scope }} ｜ 耗时 {{ result.costMs }} ms
              </el-text>
            </div>
          </template>

          <el-descriptions :column="4" border size="small" class="mb">
            <el-descriptions-item label="命中条数">{{ result.totalHits }}</el-descriptions-item>
            <el-descriptions-item label="折叠树丢弃条数">{{ result.collapsedCount }}</el-descriptions-item>
            <el-descriptions-item label="阈值过滤条数">{{ result.truncatedByThreshold }}</el-descriptions-item>
            <el-descriptions-item label="总耗时">{{ formatDuration(result.costBreakdown.totalMs) }}</el-descriptions-item>
            <el-descriptions-item label="向量化">
              {{ formatDuration(result.costBreakdown.embeddingMs) }}
            </el-descriptions-item>
            <el-descriptions-item label="向量检索">
              {{ formatDuration(result.costBreakdown.vectorMs) }}
            </el-descriptions-item>
            <el-descriptions-item label="BM25 检索">
              {{ formatDuration(result.costBreakdown.bm25Ms) }}
            </el-descriptions-item>
            <el-descriptions-item label="RRF 融合">
              {{ formatDuration(result.costBreakdown.fusionMs) }}
            </el-descriptions-item>
          </el-descriptions>

          <el-alert
            v-if="result.totalHits === 0"
            type="warning"
            :closable="false"
            show-icon
            title="本次检索没有任何命中：可尝试降低 similarityThreshold、切换检索模式或放宽检索范围。"
            class="mb"
          />

          <el-table
            :data="result.hits"
            border
            stripe
            row-key="nodeId"
            :row-class-name="rowClassName"
            style="width: 100%"
          >
            <template #empty>
              <el-empty description="暂无命中结果" />
            </template>

            <el-table-column type="expand">
              <template #default="{ row }">
                <div class="expand">
                  <div><b>节点 ID：</b><span class="mono">{{ row.nodeId }}</span></div>
                  <div><b>来源文档：</b>{{ row.documentName }} <span class="mono">（{{ row.documentId }}）</span></div>
                  <div>
                    <b>文档状态：</b>
                    <el-tag size="small" :type="row.documentEnabled ? 'success' : 'info'">
                      {{ row.documentEnabled ? '启用' : '已禁用' }}
                    </el-tag>
                  </div>
                  <div><b>覆盖块范围：</b>#{{ row.startChunkIndex }} ~ #{{ row.endChunkIndex }}（{{ row.charCount }} 字符）</div>
                  <div><b>被折叠指向：</b>{{ row.collapsedByNodeId ?? '—' }}</div>
                  <div><b>完整内容：</b></div>
                  <pre class="content">{{ row.content ?? '（未返回正文，withContent=false）' }}</pre>
                </div>
              </template>
            </el-table-column>

            <el-table-column label="最终排名" width="90" align="center" prop="finalRank" />
            <el-table-column label="最终分数" width="120" align="right">
              <template #default="{ row }">
                <span class="score">{{ formatScore(row.finalScore, 6) }}</span>
              </template>
            </el-table-column>
            <el-table-column label="层级" width="90" align="center">
              <template #default="{ row }">{{ levelShortLabel(row.level) }}</template>
            </el-table-column>
            <el-table-column label="节点类型" width="120" align="center">
              <template #default="{ row }">
                <el-tag size="small" :type="tagTypeOf(NODE_TYPE_TAG, row.nodeType)">
                  {{ labelOf(NODE_TYPE_LABEL, row.nodeType) }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="来源文档" min-width="200" show-overflow-tooltip>
              <template #default="{ row }">
                <div>{{ row.documentName }}</div>
                <el-text size="small" type="info" class="mono">{{ shortId(row.documentId, 13) }}</el-text>
              </template>
            </el-table-column>
            <el-table-column label="向量原始排名" width="120" align="center">
              <template #default="{ row }">
                {{ row.scoreBreakdown?.vectorRank ?? '—' }}
              </template>
            </el-table-column>
            <el-table-column label="向量原始分" width="120" align="right">
              <template #default="{ row }">{{ formatScore(row.scoreBreakdown?.vectorRawScore, 4) }}</template>
            </el-table-column>
            <el-table-column label="BM25 原始排名" width="130" align="center">
              <template #default="{ row }">
                {{ row.scoreBreakdown?.bm25Rank ?? '—' }}
              </template>
            </el-table-column>
            <el-table-column label="BM25 原始分" width="120" align="right">
              <template #default="{ row }">{{ formatScore(row.scoreBreakdown?.bm25RawScore, 4) }}</template>
            </el-table-column>
            <el-table-column label="向量路贡献" width="120" align="right">
              <template #default="{ row }">
                {{ formatScore(row.scoreBreakdown?.vectorWeightedScore, 6) }}
              </template>
            </el-table-column>
            <el-table-column label="BM25 路贡献" width="120" align="right">
              <template #default="{ row }">
                {{ formatScore(row.scoreBreakdown?.bm25WeightedScore, 6) }}
              </template>
            </el-table-column>
          </el-table>

          <el-descriptions :column="1" border size="small" class="params">
            <el-descriptions-item label="实际生效参数">
              <span class="mono">{{ JSON.stringify(result.params) }}</span>
            </el-descriptions-item>
          </el-descriptions>
        </el-card>

        <el-empty v-else description="填写查询条件后点击「开始检索」，这里会展示命中内容、层级、来源文档与各路排名分数" />
      </el-tab-pane>

      <!-- ============================ 检索日志 ============================ -->
      <el-tab-pane label="检索日志" name="logs">
        <el-card shadow="never">
          <div class="toolbar">
            <el-select v-model="logFilters.knowledgeBaseId" placeholder="全部知识库" clearable style="width: 200px">
              <el-option v-for="kb in knowledgeBases" :key="kb.id" :label="kb.name" :value="kb.id" />
            </el-select>
            <el-select v-model="logFilters.mode" placeholder="全部模式" clearable style="width: 160px">
              <el-option
                v-for="option in RETRIEVAL_LOG_MODE_OPTIONS"
                :key="option.value"
                :label="option.label"
                :value="option.value"
              />
            </el-select>
            <el-select v-model="logFilters.logType" placeholder="全部类型" clearable style="width: 140px">
              <el-option
                v-for="option in LOG_TYPE_OPTIONS"
                :key="option.value"
                :label="option.label"
                :value="option.value"
              />
            </el-select>
            <el-select v-model="logRange" style="width: 150px">
              <el-option
                v-for="option in LOG_RANGE_OPTIONS"
                :key="option.value"
                :label="option.label"
                :value="option.value"
              />
            </el-select>
            <el-input
              v-model="logFilters.queryKeyword"
              placeholder="按查询原文模糊匹配"
              clearable
              style="width: 220px"
              @keyup.enter="searchLogs"
            />
            <el-button type="primary" @click="searchLogs">查询</el-button>
            <el-button @click="loadLogs">刷新</el-button>
          </div>

          <el-table v-loading="logsLoading" :data="logs" border stripe style="width: 100%; margin-top: 12px">
            <template #empty>
              <el-empty description="暂无检索日志" />
            </template>
            <el-table-column label="时间" width="170">
              <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
            </el-table-column>
            <el-table-column label="类型" width="90" align="center">
              <template #default="{ row }">
                <el-tag size="small" :type="row.logType === 'EVAL' ? 'warning' : 'primary'">
                  {{ labelOf(LOG_TYPE_LABEL, row.logType) }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="模式" width="130" align="center">
              <template #default="{ row }">{{ labelOf(RETRIEVAL_LOG_MODE_LABEL, row.mode) }}</template>
            </el-table-column>
            <el-table-column prop="queryText" label="查询原文" min-width="240" show-overflow-tooltip />
            <el-table-column label="命中数" width="90" align="right" prop="resultCount" />
            <el-table-column label="耗时" width="100" align="right">
              <template #default="{ row }">{{ formatDuration(row.latencyMs) }}</template>
            </el-table-column>
            <el-table-column label="参数" min-width="220">
              <template #default="{ row }">
                <span class="mono small">{{ JSON.stringify(row.params) }}</span>
              </template>
            </el-table-column>
            <el-table-column label="状态" width="110" align="center">
              <template #default="{ row }">
                <el-tag size="small" :type="row.success ? 'success' : 'danger'">
                  {{ row.success ? '成功' : '失败' }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="错误" min-width="180" show-overflow-tooltip>
              <template #default="{ row }">{{ row.errorMessage ?? '—' }}</template>
            </el-table-column>
          </el-table>

          <el-pagination
            v-model:current-page="logsPage"
            v-model:page-size="logsPageSize"
            class="pager"
            background
            layout="total, sizes, prev, pager, next, jumper"
            :total="logsTotal"
            :page-sizes="PAGE_SIZE_OPTIONS"
            @size-change="searchLogs"
            @current-change="loadLogs"
          />
        </el-card>
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<style scoped>
.page {
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.result-card {
  margin-top: 14px;
}

.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.toolbar {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.slider {
  width: 420px;
}

.hint {
  margin-left: 10px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.mb {
  margin-bottom: 12px;
}

.params {
  margin-top: 12px;
}

.pager {
  margin-top: 14px;
  justify-content: flex-end;
}

.mono {
  font-family: Consolas, Monaco, monospace;
  font-size: 12px;
}

.small {
  font-size: 11px;
}

.score {
  font-family: Consolas, Monaco, monospace;
}

.expand {
  padding: 4px 12px 10px;
  display: flex;
  flex-direction: column;
  gap: 6px;
  font-size: 13px;
}

.content {
  margin: 0;
  max-height: 260px;
  overflow: auto;
  padding: 10px;
  background-color: #f7f8fa;
  border-radius: 4px;
  white-space: pre-wrap;
  word-break: break-word;
  font-size: 12px;
  line-height: 1.6;
}
</style>

<style>
/* SUMMARY 行与 LEAF 行用不同底色区分（契约 8.5） */
.el-table .dr-summary-row td.el-table__cell {
  background-color: #fdf6ec;
}
</style>
