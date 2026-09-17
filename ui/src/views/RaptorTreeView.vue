<script setup lang="ts">
/**
 * RAPTOR 树查看：嵌套树 + 每层节点数 / 树深度 / 是否唯一根。
 * 契约 5.2 GET /api/raptor/trees/{documentId}、5.3 GET /api/raptor/trees/{documentId}/stats
 */
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { listAllKnowledgeBases } from '@/api/knowledgeBase'
import { listAllDocuments } from '@/api/document'
import { getRaptorTree, getRaptorTreeStats } from '@/api/raptor'
import { errorCodeOf } from '@/api/request'
import RaptorBuildDialog from '@/components/RaptorBuildDialog.vue'
import {
  ERROR_CODE,
  NODE_TYPE,
  NODE_TYPE_LABEL,
  NODE_TYPE_TAG,
  STEP_STATUS_LABEL,
  STEP_STATUS_TAG,
  labelOf,
  levelShortLabel,
  tagTypeOf,
} from '@/constants'
import { formatDuration, formatNumber, formatTime, truncate } from '@/utils/format'
import type { DocumentItem, KnowledgeBase, RaptorTreeData, RaptorTreeNode, RaptorTreeStats } from '@/types'

/** 渲染用节点：把展示文案在 script 里算好，模板只读字段（避免模板内类型断言） */
interface DisplayNode {
  nodeId: string
  label: string
  level: number
  nodeType: string
  /** 是否摘要节点（模板里不硬编码枚举字符串） */
  isSummary: boolean
  coverage: string
  charCount: number
  childCount: number
  clusterLabel: number | null
  clusterSize: number | null
  hasEmbedding: boolean
  summary: string
  children: DisplayNode[]
}

const route = useRoute()

const loading = ref(false)
const knowledgeBases = ref<KnowledgeBase[]>([])
const documents = ref<DocumentItem[]>([])
const selectedDocumentId = ref<string>(typeof route.query.documentId === 'string' ? route.query.documentId : '')

const tree = ref<RaptorTreeData | null>(null)
const stats = ref<RaptorTreeStats | null>(null)
/** 后端返回 40400 表示该文档尚未建树 */
const notBuilt = ref(false)

const includeLeaves = ref(true)
const withContent = ref(false)
const buildVisible = ref(false)

const selectedDocument = computed(
  () => documents.value.find((doc) => doc.id === selectedDocumentId.value) ?? null,
)

function toDisplayNode(node: RaptorTreeNode): DisplayNode {
  return {
    nodeId: node.nodeId,
    label: `${levelShortLabel(node.level)} · ${labelOf(NODE_TYPE_LABEL, node.nodeType)}`,
    level: node.level,
    nodeType: node.nodeType,
    isSummary: node.nodeType === NODE_TYPE.SUMMARY,
    coverage: `覆盖块 #${node.startChunkIndex} ~ #${node.endChunkIndex}`,
    charCount: node.charCount,
    childCount: node.childCount,
    clusterLabel: node.clusterLabel,
    clusterSize: node.clusterSize,
    hasEmbedding: node.hasEmbedding,
    summary: node.summary ?? node.content ?? '',
    children: (node.children ?? []).map(toDisplayNode),
  }
}

const displayTree = computed<DisplayNode[]>(() => {
  const root = tree.value?.root
  return root ? [toDisplayNode(root)] : []
})

/** 每层节点数（含叶子层） */
const levelRows = computed(() => {
  const counts = stats.value?.levelCounts ?? []
  return [...counts]
    .sort((a, b) => a.level - b.level)
    .map((item) => ({ ...item, label: levelShortLabel(item.level) }))
})

async function loadKnowledgeBasesAndDocuments(): Promise<void> {
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
  const documentId = selectedDocumentId.value
  if (documentId === '') {
    tree.value = null
    stats.value = null
    notBuilt.value = false
    return
  }
  loading.value = true
  try {
    // 未建树时后端返回 code=40400 且 data=null，这里静默处理并渲染友好空态
    const result = await getRaptorTree(
      documentId,
      { format: 'nested', withContent: withContent.value, includeLeaves: includeLeaves.value },
      true,
    )
    tree.value = result
    notBuilt.value = !result
  } catch (error) {
    const code = errorCodeOf(error)
    tree.value = null
    notBuilt.value = code === ERROR_CODE.RESOURCE_NOT_FOUND
    if (code !== ERROR_CODE.RESOURCE_NOT_FOUND && code !== ERROR_CODE.DOCUMENT_NOT_FOUND) {
      ElMessage.error('树结构加载失败')
    }
  } finally {
    loading.value = false
  }

  try {
    stats.value = await getRaptorTreeStats(documentId, true)
  } catch {
    stats.value = null
  }
}

function openBuild(): void {
  if (!selectedDocumentId.value) {
    ElMessage.warning('请先选择文档')
    return
  }
  buildVisible.value = true
}

watch(selectedDocumentId, () => {
  void load()
})

watch([includeLeaves, withContent], () => {
  void load()
})

onMounted(async () => {
  await loadKnowledgeBasesAndDocuments()
  await load()
})
</script>

<template>
  <div class="page">
    <el-card shadow="never">
      <div class="toolbar">
        <div class="toolbar__filters">
          <el-select
            v-model="selectedDocumentId"
            placeholder="选择文档查看其 RAPTOR 树"
            clearable
            filterable
            style="width: 360px"
          >
            <el-option
              v-for="doc in documents"
              :key="doc.id"
              :label="`${doc.fileName}（${doc.knowledgeBaseName}）`"
              :value="doc.id"
            />
          </el-select>
          <el-checkbox v-model="includeLeaves">包含叶子文本块</el-checkbox>
          <el-checkbox v-model="withContent">返回完整内容</el-checkbox>
          <el-button @click="load">刷新</el-button>
        </div>
        <el-button type="primary" :disabled="!selectedDocumentId" @click="openBuild">
          {{ stats ? '重建树' : '构建树' }}
        </el-button>
      </div>
    </el-card>

    <el-empty v-if="!selectedDocumentId" description="请选择文档：将展示该文档的 RAPTOR 摘要树、每层节点数、树深度与唯一根判定" />

    <template v-else>
      <el-card v-loading="loading" shadow="never">
        <template #header>
          <div class="card-header">
            <span>树统计</span>
            <el-text v-if="selectedDocument" type="info" size="small">
              {{ selectedDocument.fileName }}（{{ selectedDocument.id }}）
            </el-text>
          </div>
        </template>

        <el-empty v-if="notBuilt" description="该文档尚未构建 RAPTOR 树（code=40400）">
          <el-button type="primary" @click="openBuild">立即构建</el-button>
        </el-empty>

        <template v-else-if="stats">
          <el-alert
            v-if="!stats.hasUniqueRoot"
            type="error"
            :closable="false"
            show-icon
            title="建树未收敛：存在多个根节点（hasUniqueRoot=false）。建议用更大的 maxLevel + 勾选「强制重建」重新建树。"
            class="mb"
          />
          <el-alert
            v-if="stats.degradedSummaryCount > 0"
            type="warning"
            :closable="false"
            show-icon
            :title="`有 ${stats.degradedSummaryCount} 个摘要节点因 LLM 摘要失败降级为原文拼接（degradedSummaryCount）`"
            class="mb"
          />

          <el-descriptions :column="4" border size="small">
            <el-descriptions-item label="建树状态">
              <el-tag size="small" :type="tagTypeOf(STEP_STATUS_TAG, stats.treeStatus)">
                {{ labelOf(STEP_STATUS_LABEL, stats.treeStatus) }}
              </el-tag>
            </el-descriptions-item>
            <el-descriptions-item label="树深度 actualDepth">{{ stats.actualDepth }}</el-descriptions-item>
            <el-descriptions-item label="深度上限 maxLevel">{{ stats.maxLevel }}</el-descriptions-item>
            <el-descriptions-item label="根节点数 rootCount">{{ stats.rootCount }}</el-descriptions-item>
            <el-descriptions-item label="是否有唯一根">
              <el-tag size="small" :type="stats.hasUniqueRoot ? 'success' : 'danger'">
                {{ stats.hasUniqueRoot ? '是' : '否' }}
              </el-tag>
            </el-descriptions-item>
            <el-descriptions-item label="平均簇大小">{{ stats.avgClusterSize }}</el-descriptions-item>
            <el-descriptions-item label="降级摘要数">{{ stats.degradedSummaryCount }}</el-descriptions-item>
            <el-descriptions-item label="强制根节点">
              {{ stats.forcedRoot ? '是' : '否' }}
            </el-descriptions-item>
            <el-descriptions-item label="节点总数">
              {{ formatNumber(tree?.nodeCount) }}
            </el-descriptions-item>
            <el-descriptions-item label="摘要节点数">
              {{ formatNumber(tree?.summaryNodeCount) }}
            </el-descriptions-item>
            <el-descriptions-item label="叶子节点数">
              {{ formatNumber(tree?.leafNodeCount) }}
            </el-descriptions-item>
            <el-descriptions-item label="建树耗时">{{ formatDuration(stats.buildDurationMs) }}</el-descriptions-item>
            <el-descriptions-item label="建树时间" :span="4">{{ formatTime(stats.builtAt) }}</el-descriptions-item>
          </el-descriptions>

          <div class="level-counts">
            <div class="level-counts__title">每层节点数</div>
            <el-table :data="levelRows" size="small" border style="max-width: 420px">
              <template #empty>
                <el-empty description="暂无层级统计" :image-size="60" />
              </template>
              <el-table-column prop="label" label="层级" />
              <el-table-column label="节点数" align="right">
                <template #default="{ row }">{{ formatNumber(row.count) }}</template>
              </el-table-column>
            </el-table>
          </div>
        </template>

        <el-empty v-else description="暂无树统计信息" />
      </el-card>

      <el-card v-loading="loading" shadow="never">
        <template #header>
          <div class="card-header">
            <span>树结构</span>
            <el-text v-if="tree" type="info" size="small">
              根节点 {{ tree.rootNodeId ?? '—' }} ｜ actualDepth {{ tree.actualDepth }} ｜ maxLevel {{ tree.maxLevel }}
            </el-text>
          </div>
        </template>

        <el-empty v-if="displayTree.length === 0" description="暂无树结构数据" />
        <el-tree
          v-else
          :data="displayTree"
          node-key="nodeId"
          default-expand-all
          :expand-on-click-node="false"
          :props="{ children: 'children', label: 'label' }"
        >
          <template #default="{ data }">
            <div class="tree-node">
              <div class="tree-node__head">
                <el-tag size="small" :type="tagTypeOf(NODE_TYPE_TAG, data.nodeType)">
                  {{ data.label }}
                </el-tag>
                <el-tag size="small" type="info">L{{ data.level }}</el-tag>
                <span class="tree-node__meta">
                  {{ data.coverage }} ｜ {{ formatNumber(data.charCount) }} 字符 ｜
                  {{ data.childCount }} 个子节点
                  <template v-if="data.clusterLabel !== null">｜ 簇 #{{ data.clusterLabel }}</template>
                  <template v-if="data.clusterSize !== null">（{{ data.clusterSize }} 块）</template>
                </span>
                <el-tag v-if="data.isSummary && !data.hasEmbedding" size="small" type="danger">
                  无向量
                </el-tag>
              </div>
              <div v-if="data.summary" class="tree-node__summary">
                {{ truncate(data.summary, 220) }}
              </div>
              <div class="tree-node__id mono">{{ data.nodeId }}</div>
            </div>
          </template>
        </el-tree>
      </el-card>
    </template>

    <RaptorBuildDialog
      v-model="buildVisible"
      :document-id="selectedDocumentId"
      :document-name="selectedDocument?.fileName"
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
  gap: 10px;
  flex-wrap: wrap;
}

.card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.mb {
  margin-bottom: 12px;
}

.level-counts {
  margin-top: 16px;
}

.level-counts__title {
  font-weight: 600;
  margin-bottom: 8px;
}

.tree-node {
  padding: 4px 0;
  white-space: normal;
}

.tree-node__head {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
}

.tree-node__meta {
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.tree-node__summary {
  margin-top: 2px;
  color: var(--el-text-color-regular);
  font-size: 12px;
  line-height: 1.5;
}

.tree-node__id {
  color: var(--el-text-color-placeholder);
  font-size: 11px;
}

.mono {
  font-family: Consolas, Monaco, monospace;
}

:deep(.el-tree-node__content) {
  height: auto;
  padding: 2px 0;
  align-items: flex-start;
}
</style>
