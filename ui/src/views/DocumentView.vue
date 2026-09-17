<script setup lang="ts">
/**
 * 文档管理：上传导入（PDF/DOCX/Markdown/TXT）、列表、按知识库筛选、启用/禁用切换、
 * 展示解析/分块/向量化/建树四步状态。契约 4.6 ~ 4.8
 */
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, type UploadFile, type UploadInstance } from 'element-plus'
import { listAllKnowledgeBases } from '@/api/knowledgeBase'
import { listDocuments, updateDocumentEnabled, uploadDocument } from '@/api/document'
import { useTaskStore } from '@/stores/task'
import DocumentStageTags from '@/components/DocumentStageTags.vue'
import RaptorBuildDialog from '@/components/RaptorBuildDialog.vue'
import {
  FILE_ACCEPT,
  FILE_TYPE_LABEL,
  FILE_TYPE_TAG,
  PAGE_DEFAULT,
  PAGE_SIZE_DEFAULT,
  PAGE_SIZE_OPTIONS,
  RAPTOR_LIMITS,
  STEP_STATUS,
  STEP_STATUS_LABEL,
  STEP_STATUS_OPTIONS,
  STEP_STATUS_TAG,
  UPLOAD_MAX_BYTES,
  labelOf,
  tagTypeOf,
} from '@/constants'
import { formatBytes, formatJson, formatNumber, formatTime } from '@/utils/format'
import { asRow } from '@/utils/row'
import type { DocumentItem, KnowledgeBase, StepStatus } from '@/types'

const route = useRoute()
const router = useRouter()
const taskStore = useTaskStore()

const loading = ref(false)
const documents = ref<DocumentItem[]>([])
const knowledgeBases = ref<KnowledgeBase[]>([])
const total = ref(0)
const page = ref<number>(PAGE_DEFAULT)
const pageSize = ref<number>(PAGE_SIZE_DEFAULT)

const filters = reactive({
  knowledgeBaseId: typeof route.query.knowledgeBaseId === 'string' ? route.query.knowledgeBaseId : '',
  enabled: '' as '' | 'true' | 'false',
  treeStatus: '' as '' | StepStatus,
  keyword: '',
})

// ---------------------------------------------------------------- 上传导入
const uploadVisible = ref(false)
const uploadRef = ref<UploadInstance>()
const uploading = ref(false)
const selectedFile = ref<File | null>(null)
const uploadForm = reactive({
  knowledgeBaseId: '',
  buildTree: true,
  maxLevel: RAPTOR_LIMITS.maxLevel.default as number,
})

// ---------------------------------------------------------------- 详情
const detailVisible = ref(false)
const detail = ref<DocumentItem | null>(null)

// ---------------------------------------------------------------- 建树
const buildVisible = ref(false)
const buildTarget = ref<DocumentItem | null>(null)

const buildDisabledReason = computed(() => '文档尚未完成向量化（embedStatus != SUCCESS），无法建树')

function search(): void {
  page.value = PAGE_DEFAULT
  void load()
}

function resetFilters(): void {
  filters.knowledgeBaseId = ''
  filters.enabled = ''
  filters.treeStatus = ''
  filters.keyword = ''
  search()
}

async function loadKnowledgeBases(): Promise<void> {
  try {
    knowledgeBases.value = await listAllKnowledgeBases()
  } catch {
    knowledgeBases.value = []
  }
}

async function load(): Promise<void> {
  loading.value = true
  try {
    const result = await listDocuments({
      knowledgeBaseId: filters.knowledgeBaseId === '' ? null : filters.knowledgeBaseId,
      enabled: filters.enabled === '' ? null : filters.enabled === 'true',
      treeStatus: filters.treeStatus === '' ? null : filters.treeStatus,
      keyword: filters.keyword.trim() === '' ? null : filters.keyword.trim(),
      page: page.value,
      pageSize: pageSize.value,
    })
    documents.value = result.list ?? []
    total.value = result.total ?? 0
  } catch {
    documents.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

/** 扩展名白名单前置校验（契约 4.6 / 错误码 40007） */
const ALLOWED_EXTENSIONS = ['pdf', 'docx', 'md', 'markdown', 'txt']

function extensionOf(name: string): string {
  const index = name.lastIndexOf('.')
  return index < 0 ? '' : name.slice(index + 1).toLowerCase()
}

function openUpload(): void {
  selectedFile.value = null
  uploadRef.value?.clearFiles()
  uploadForm.knowledgeBaseId = filters.knowledgeBaseId || ''
  uploadForm.buildTree = true
  uploadForm.maxLevel = RAPTOR_LIMITS.maxLevel.default
  uploadVisible.value = true
}

function onFileChange(file: UploadFile): void {
  const raw = file.raw
  if (!raw) {
    selectedFile.value = null
    return
  }
  if (!ALLOWED_EXTENSIONS.includes(extensionOf(raw.name))) {
    ElMessage.error(`不支持的文件类型：.${extensionOf(raw.name)}，仅支持 pdf/docx/md/markdown/txt`)
    uploadRef.value?.clearFiles()
    selectedFile.value = null
    return
  }
  if (raw.size > UPLOAD_MAX_BYTES) {
    ElMessage.error(`上传文件超过大小限制（10MB），当前 ${formatBytes(raw.size)}`)
    uploadRef.value?.clearFiles()
    selectedFile.value = null
    return
  }
  if (raw.size === 0) {
    ElMessage.error('上传文件为空')
    uploadRef.value?.clearFiles()
    selectedFile.value = null
    return
  }
  selectedFile.value = raw
}

function onFileRemove(): void {
  selectedFile.value = null
}

async function submitUpload(): Promise<void> {
  if (uploadForm.knowledgeBaseId === '') {
    ElMessage.warning('请选择目标知识库')
    return
  }
  const file = selectedFile.value
  if (!file) {
    ElMessage.warning('请选择要导入的文件')
    return
  }
  uploading.value = true
  try {
    const result = await uploadDocument(file, {
      knowledgeBaseId: uploadForm.knowledgeBaseId,
      buildTree: uploadForm.buildTree,
      maxLevel: uploadForm.maxLevel,
    })
    ElMessage.success(`已提交导入任务：${result.fileName}`)
    uploadVisible.value = false
    // 契约 4.6：拿到 taskId 后按 1000ms 轮询进度
    taskStore.track(result.taskId, {
      title: `导入文档：${result.fileName}`,
      onFinish: () => {
        void load()
      },
    })
    await load()
  } finally {
    uploading.value = false
  }
}

function onToggleEnabled(raw: unknown, value: unknown): void {
  void toggleEnabled(asRow<DocumentItem>(raw), value === true)
}

async function toggleEnabled(row: DocumentItem, enabled: boolean): Promise<void> {
  try {
    const result = await updateDocumentEnabled(row.id, enabled)
    row.enabled = result.enabled
    ElMessage.success(
      result.enabled
        ? '已启用：该文档重新参与向量路与 BM25 路检索'
        : '已禁用：数据与向量完整保留，但不再参与任何检索（可随时恢复）',
    )
  } catch {
    // switch 使用 :model-value，失败时不会乐观更新，无需回滚
  }
}

function openDetail(raw: unknown): void {
  detail.value = asRow<DocumentItem>(raw)
  detailVisible.value = true
}

function openBuild(raw: unknown): void {
  buildTarget.value = asRow<DocumentItem>(raw)
  buildVisible.value = true
}

function goChunks(raw: unknown): void {
  const row = asRow<DocumentItem>(raw)
  void router.push({ name: 'chunks', query: { documentId: row.id, knowledgeBaseId: row.knowledgeBaseId } })
}

function goTree(raw: unknown): void {
  const row = asRow<DocumentItem>(raw)
  void router.push({ name: 'raptor', query: { documentId: row.id } })
}

function canBuild(raw: unknown): boolean {
  const row = asRow<DocumentItem>(raw)
  return row.embedStatus === STEP_STATUS.SUCCESS && row.treeStatus !== STEP_STATUS.RUNNING
}

onMounted(async () => {
  await loadKnowledgeBases()
  await load()
})
</script>

<template>
  <div class="page">
    <el-card shadow="never">
      <div class="toolbar">
        <div class="toolbar__filters">
          <el-select v-model="filters.knowledgeBaseId" placeholder="全部知识库" clearable style="width: 220px">
            <el-option v-for="kb in knowledgeBases" :key="kb.id" :label="kb.name" :value="kb.id" />
          </el-select>
          <el-select v-model="filters.enabled" placeholder="启用状态" clearable style="width: 130px">
            <el-option label="仅启用" value="true" />
            <el-option label="仅禁用" value="false" />
          </el-select>
          <el-select v-model="filters.treeStatus" placeholder="建树状态" clearable style="width: 140px">
            <el-option
              v-for="option in STEP_STATUS_OPTIONS"
              :key="option.value"
              :label="option.label"
              :value="option.value"
            />
          </el-select>
          <el-input
            v-model="filters.keyword"
            placeholder="按文件名搜索"
            clearable
            style="width: 200px"
            @keyup.enter="search"
            @clear="search"
          />
          <el-button type="primary" @click="search">查询</el-button>
          <el-button @click="resetFilters">重置</el-button>
        </div>
        <el-button type="primary" @click="openUpload">上传导入</el-button>
      </div>
    </el-card>

    <el-alert
      type="info"
      :closable="false"
      show-icon
      title="已导入文档的正文与文本块不可修改、不可删除（契约无此接口）；如需让文档退出检索，请使用「启用/禁用」开关，禁用后数据与向量完整保留、可随时恢复。"
    />

    <el-card shadow="never">
      <el-table v-loading="loading" :data="documents" border stripe style="width: 100%">
        <template #empty>
          <el-empty description="暂无文档，点击右上角「上传导入」上传 PDF / DOCX / Markdown / TXT" />
        </template>
        <el-table-column label="文件名" min-width="220" show-overflow-tooltip>
          <template #default="{ row }">
            <div class="file-cell">
              <el-tag size="small" :type="tagTypeOf(FILE_TYPE_TAG, row.fileType)">
                {{ row.fileType }}
              </el-tag>
              <span>{{ row.fileName }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="knowledgeBaseName" label="所属知识库" min-width="160" show-overflow-tooltip />
        <el-table-column label="大小" width="110" align="right">
          <template #default="{ row }">{{ formatBytes(row.fileSize) }}</template>
        </el-table-column>
        <el-table-column label="字符数" width="110" align="right">
          <template #default="{ row }">{{ formatNumber(row.charCount) }}</template>
        </el-table-column>
        <el-table-column label="块数" width="90" align="right">
          <template #default="{ row }">{{ formatNumber(row.chunkCount) }}</template>
        </el-table-column>
        <el-table-column label="流水线状态" min-width="290">
          <template #default="{ row }">
            <DocumentStageTags
              :parse-status="row.parseStatus"
              :chunk-status="row.chunkStatus"
              :embed-status="row.embedStatus"
              :tree-status="row.treeStatus"
            />
          </template>
        </el-table-column>
        <el-table-column label="启用" width="90" align="center">
          <template #default="{ row }">
            <el-switch :model-value="row.enabled" @change="onToggleEnabled(row, $event)" />
          </template>
        </el-table-column>
        <el-table-column label="创建时间" width="170">
          <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="290" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="goChunks(row)">分块预览</el-button>
            <el-button link type="primary" @click="goTree(row)">查看树</el-button>
            <el-tooltip :disabled="canBuild(row)" :content="buildDisabledReason" placement="top">
              <span>
                <el-button link type="primary" :disabled="!canBuild(row)" @click="openBuild(row)">
                  建树
                </el-button>
              </span>
            </el-tooltip>
            <el-button link type="primary" @click="openDetail(row)">详情</el-button>
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

    <!-- 上传导入 -->
    <el-dialog v-model="uploadVisible" title="上传导入文档" width="640px" :close-on-click-modal="false">
      <el-form label-width="130px">
        <el-form-item label="目标知识库" required>
          <el-select v-model="uploadForm.knowledgeBaseId" placeholder="请选择知识库" style="width: 100%">
            <el-option v-for="kb in knowledgeBases" :key="kb.id" :label="kb.name" :value="kb.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="文件" required>
          <el-upload
            ref="uploadRef"
            class="uploader"
            drag
            :auto-upload="false"
            :limit="1"
            :accept="FILE_ACCEPT"
            :on-change="onFileChange"
            :on-remove="onFileRemove"
          >
            <el-text>将文件拖到此处，或点击选择文件</el-text>
            <template #tip>
              <div class="hint">
                支持 PDF / DOCX / Markdown（.md/.markdown）/ TXT，单文件 ≤ 10MB，一次一个文件。
              </div>
            </template>
          </el-upload>
        </el-form-item>
        <el-form-item label="导入后自动建树">
          <el-switch v-model="uploadForm.buildTree" />
        </el-form-item>
        <el-form-item v-if="uploadForm.buildTree" label="树深上限">
          <el-input-number
            v-model="uploadForm.maxLevel"
            :min="RAPTOR_LIMITS.maxLevel.min"
            :max="RAPTOR_LIMITS.maxLevel.max"
            :step="RAPTOR_LIMITS.maxLevel.step"
          />
          <span class="hint">默认 3（L3）</span>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="uploadVisible = false">取消</el-button>
        <el-button type="primary" :loading="uploading" @click="submitUpload">开始导入</el-button>
      </template>
    </el-dialog>

    <!-- 文档详情 -->
    <el-drawer v-model="detailVisible" title="文档详情" size="640px">
      <el-empty v-if="!detail" description="未选择文档" />
      <el-descriptions v-else :column="1" border size="small">
        <el-descriptions-item label="文档 ID">
          <span class="mono">{{ detail.id }}</span>
        </el-descriptions-item>
        <el-descriptions-item label="文件名">{{ detail.fileName }}</el-descriptions-item>
        <el-descriptions-item label="文件类型">
          <el-tag size="small" :type="tagTypeOf(FILE_TYPE_TAG, detail.fileType)">
            {{ labelOf(FILE_TYPE_LABEL, detail.fileType) }}
          </el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="所属知识库">
          {{ detail.knowledgeBaseName }}
          <el-text size="small" type="info" class="mono">（{{ detail.knowledgeBaseId }}）</el-text>
        </el-descriptions-item>
        <el-descriptions-item label="文件大小">{{ formatBytes(detail.fileSize) }}</el-descriptions-item>
        <el-descriptions-item label="字符数 / 块数">
          {{ formatNumber(detail.charCount) }} / {{ formatNumber(detail.chunkCount) }}
        </el-descriptions-item>
        <el-descriptions-item label="是否启用">
          <el-tag :type="detail.enabled ? 'success' : 'info'" size="small">
            {{ detail.enabled ? '启用（参与检索）' : '已禁用（不参与检索，数据保留）' }}
          </el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="解析 / 分块 / 向量化 / 建树">
          <div class="stage-row">
            <el-tag size="small" :type="tagTypeOf(STEP_STATUS_TAG, detail.parseStatus)">
              解析：{{ labelOf(STEP_STATUS_LABEL, detail.parseStatus) }}
            </el-tag>
            <el-tag size="small" :type="tagTypeOf(STEP_STATUS_TAG, detail.chunkStatus)">
              分块：{{ labelOf(STEP_STATUS_LABEL, detail.chunkStatus) }}
            </el-tag>
            <el-tag size="small" :type="tagTypeOf(STEP_STATUS_TAG, detail.embedStatus)">
              向量化：{{ labelOf(STEP_STATUS_LABEL, detail.embedStatus) }}
            </el-tag>
            <el-tag size="small" :type="tagTypeOf(STEP_STATUS_TAG, detail.treeStatus)">
              建树：{{ labelOf(STEP_STATUS_LABEL, detail.treeStatus) }}
            </el-tag>
          </div>
        </el-descriptions-item>
        <el-descriptions-item label="解析错误">
          <el-text type="danger">{{ detail.parseError ?? '—' }}</el-text>
        </el-descriptions-item>
        <el-descriptions-item label="metadata">
          <pre class="json">{{ formatJson(detail.metadata) }}</pre>
        </el-descriptions-item>
        <el-descriptions-item label="创建时间">{{ formatTime(detail.createdAt) }}</el-descriptions-item>
        <el-descriptions-item label="更新时间">{{ formatTime(detail.updatedAt) }}</el-descriptions-item>
      </el-descriptions>
      <template #footer>
        <div v-if="detail">
          <el-button @click="goChunks(detail)">查看分块</el-button>
          <el-button @click="goTree(detail)">查看树</el-button>
        </div>
      </template>
    </el-drawer>

    <!-- 建树参数 -->
    <RaptorBuildDialog
      v-model="buildVisible"
      :document-id="buildTarget?.id ?? ''"
      :document-name="buildTarget?.fileName"
      @submitted="load"
      @finished="load"
    />
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
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
}

.toolbar__filters {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.file-cell {
  display: flex;
  align-items: center;
  gap: 6px;
}

.stage-row {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
}

.pager {
  margin-top: 14px;
  justify-content: flex-end;
}

.hint {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.mono {
  font-family: Consolas, Monaco, monospace;
  font-size: 12px;
}

.json {
  margin: 0;
  max-height: 220px;
  overflow: auto;
  font-size: 12px;
  font-family: Consolas, Monaco, monospace;
  white-space: pre-wrap;
  word-break: break-all;
}

.uploader {
  width: 100%;
}
</style>
