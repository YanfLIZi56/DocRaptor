<script setup lang="ts">
/**
 * 分块预览：查看某文档的全部文本块。
 * 需求明文要求每块必须显示「来源文档 ID（documentId）、块序号（chunkIndex）、字符数（charCount）」。
 * 契约 4.9 GET /api/chunks
 */
import { onMounted, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { listAllKnowledgeBases } from '@/api/knowledgeBase'
import { listAllDocuments } from '@/api/document'
import { listChunks } from '@/api/chunk'
import {
  NODE_TYPE_LABEL,
  NODE_TYPE_TAG,
  PAGE_DEFAULT,
  PAGE_SIZE_DEFAULT,
  PAGE_SIZE_OPTIONS,
  labelOf,
  tagTypeOf,
} from '@/constants'
import { formatNumber, formatTime, truncate } from '@/utils/format'
import type { ChunkItem, DocumentItem, KnowledgeBase } from '@/types'

const route = useRoute()

const loading = ref(false)
const chunks = ref<ChunkItem[]>([])
const total = ref(0)
const page = ref<number>(PAGE_DEFAULT)
const pageSize = ref<number>(PAGE_SIZE_DEFAULT)

const knowledgeBases = ref<KnowledgeBase[]>([])
const documents = ref<DocumentItem[]>([])

const filters = reactive({
  knowledgeBaseId: typeof route.query.knowledgeBaseId === 'string' ? route.query.knowledgeBaseId : '',
  documentId: typeof route.query.documentId === 'string' ? route.query.documentId : '',
  withContent: true,
  withEmbedding: false,
})

async function loadKnowledgeBases(): Promise<void> {
  try {
    knowledgeBases.value = await listAllKnowledgeBases()
  } catch {
    knowledgeBases.value = []
  }
}

async function loadDocuments(): Promise<void> {
  try {
    documents.value = await listAllDocuments(filters.knowledgeBaseId === '' ? null : filters.knowledgeBaseId)
  } catch {
    documents.value = []
  }
}

async function load(): Promise<void> {
  if (filters.knowledgeBaseId === '' && filters.documentId === '') {
    chunks.value = []
    total.value = 0
    return
  }
  loading.value = true
  try {
    const result = await listChunks({
      // 同时传时以 documentId 为准（契约 4.9）
      knowledgeBaseId: filters.knowledgeBaseId === '' ? null : filters.knowledgeBaseId,
      documentId: filters.documentId === '' ? null : filters.documentId,
      withContent: filters.withContent,
      withEmbedding: filters.withEmbedding,
      page: page.value,
      pageSize: pageSize.value,
    })
    chunks.value = result.list ?? []
    total.value = result.total ?? 0
  } catch {
    chunks.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

function search(): void {
  page.value = PAGE_DEFAULT
  void load()
}

// 切知识库时清掉不属于该库的文档选择
watch(
  () => filters.knowledgeBaseId,
  async () => {
    filters.documentId = ''
    await loadDocuments()
    chunks.value = []
    total.value = 0
  },
)

watch(
  () => filters.documentId,
  () => {
    search()
  },
)

watch([() => filters.withContent, () => filters.withEmbedding], () => {
  search()
})

onMounted(async () => {
  await loadKnowledgeBases()
  await loadDocuments()
  await load()
})
</script>

<template>
  <div class="page">
    <el-card shadow="never">
      <div class="toolbar">
        <div class="toolbar__filters">
          <el-select v-model="filters.knowledgeBaseId" placeholder="全部知识库" clearable style="width: 200px">
            <el-option v-for="kb in knowledgeBases" :key="kb.id" :label="kb.name" :value="kb.id" />
          </el-select>
          <el-select
            v-model="filters.documentId"
            placeholder="选择文档（必选其一）"
            clearable
            filterable
            style="width: 300px"
          >
            <el-option
              v-for="doc in documents"
              :key="doc.id"
              :label="doc.fileName"
              :value="doc.id"
            />
          </el-select>
          <el-checkbox v-model="filters.withContent">返回完整正文</el-checkbox>
          <el-checkbox v-model="filters.withEmbedding">返回向量预览</el-checkbox>
          <el-button @click="search">刷新</el-button>
        </div>
        <el-text type="info">
          共 {{ formatNumber(total) }} 个文本块（nodeType 固定为 LEAF，按 documentId、chunkIndex 升序）
        </el-text>
      </div>
    </el-card>

    <el-alert
      v-if="filters.knowledgeBaseId === '' && filters.documentId === ''"
      type="warning"
      :closable="false"
      show-icon
      title="请先选择知识库或文档：本页需要 documentId 或 knowledgeBaseId 才能查询分块。"
    />

    <el-card shadow="never">
      <el-table
        v-loading="loading"
        :data="chunks"
        border
        stripe
        row-key="nodeId"
        style="width: 100%"
      >
        <template #empty>
          <el-empty description="暂无文本块数据" />
        </template>

        <el-table-column type="expand">
          <template #default="{ row }">
            <div class="expand">
              <div class="expand__row">
                <b>节点 ID：</b><span class="mono">{{ row.nodeId }}</span>
              </div>
              <div class="expand__row">
                <b>父节点 ID：</b><span class="mono">{{ row.parentId ?? '—（尚未建树）' }}</span>
              </div>
              <div class="expand__row">
                <b>向量：</b>
                <span>
                  {{ row.hasEmbedding ? `已生成，维度 ${row.embeddingDimension ?? '—'}` : '未生成' }}
                  <template v-if="row.embeddingPreview">
                    ，前 8 维预览 [{{ row.embeddingPreview.join(', ') }}]
                  </template>
                </span>
              </div>
              <div class="expand__row">
                <b>完整内容：</b>
              </div>
              <pre class="content">{{ row.content ?? '（未返回正文，withContent=false）' }}</pre>
            </div>
          </template>
        </el-table-column>

        <el-table-column label="来源文档 ID" prop="documentId" width="320">
          <template #default="{ row }">
            <span class="mono">{{ row.documentId }}</span>
          </template>
        </el-table-column>
        <el-table-column label="块序号" prop="chunkIndex" width="90" align="center" sortable />
        <el-table-column label="字符数" prop="charCount" width="100" align="right" sortable>
          <template #default="{ row }">{{ formatNumber(row.charCount) }}</template>
        </el-table-column>
        <el-table-column label="文档名" prop="documentName" min-width="160" show-overflow-tooltip />
        <el-table-column label="层级" width="90" align="center">
          <template #default="{ row }">L{{ row.level }}</template>
        </el-table-column>
        <el-table-column label="节点类型" width="120" align="center">
          <template #default="{ row }">
            <el-tag size="small" :type="tagTypeOf(NODE_TYPE_TAG, row.nodeType)">
              {{ labelOf(NODE_TYPE_LABEL, row.nodeType) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="覆盖块范围" width="130" align="center">
          <template #default="{ row }">{{ row.startChunkIndex }} ~ {{ row.endChunkIndex }}</template>
        </el-table-column>
        <el-table-column label="有向量" width="90" align="center">
          <template #default="{ row }">
            <el-tag size="small" :type="row.hasEmbedding ? 'success' : 'info'">
              {{ row.hasEmbedding ? '是' : '否' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="内容" min-width="260">
          <template #default="{ row }">
            <span>{{ truncate(row.content, 80) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="创建时间" width="170">
          <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
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
  gap: 10px;
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

.expand {
  padding: 4px 12px 10px;
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.expand__row {
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
