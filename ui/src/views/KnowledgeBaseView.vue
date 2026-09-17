<script setup lang="ts">
/** 知识库管理：新建（可配置 chunkSize/overlap/strategy）、列表、重命名/改描述、删除（二次确认）。契约 4.1 ~ 4.5 */
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox, type FormInstance, type FormRules } from 'element-plus'
import {
  createKnowledgeBase,
  deleteKnowledgeBase,
  listKnowledgeBases,
  updateKnowledgeBase,
} from '@/api/knowledgeBase'
import {
  CHUNK_STRATEGY_HINT,
  CHUNK_STRATEGY_LABEL,
  CHUNK_STRATEGY_OPTIONS,
  KB_LIMITS,
  PAGE_DEFAULT,
  PAGE_SIZE_DEFAULT,
  PAGE_SIZE_OPTIONS,
  labelOf,
} from '@/constants'
import { formatNumber, formatTime } from '@/utils/format'
import { asRow } from '@/utils/row'
import type { ChunkStrategy, KnowledgeBase } from '@/types'

const router = useRouter()

const loading = ref(false)
const list = ref<KnowledgeBase[]>([])
const total = ref(0)
const page = ref<number>(PAGE_DEFAULT)
const pageSize = ref<number>(PAGE_SIZE_DEFAULT)
const keyword = ref('')

// ---------------------------------------------------------------- 新建
const createVisible = ref(false)
const createRef = ref<FormInstance>()
const creating = ref(false)
const createForm = reactive({
  name: '',
  description: '',
  chunkSize: KB_LIMITS.chunkSize.default as number,
  chunkOverlap: KB_LIMITS.chunkOverlap.default as number,
  chunkStrategy: 'FIXED_SIZE' as ChunkStrategy,
})

const createRules: FormRules = {
  name: [
    { required: true, message: '请输入知识库名称', trigger: 'blur' },
    {
      max: KB_LIMITS.nameMaxLength,
      message: `名称最长 ${KB_LIMITS.nameMaxLength} 个字符`,
      trigger: 'blur',
    },
  ],
  description: [
    { max: KB_LIMITS.descriptionMaxLength, message: `描述最长 ${KB_LIMITS.descriptionMaxLength} 个字符`, trigger: 'blur' },
  ],
}

// ---------------------------------------------------------------- 编辑
const editVisible = ref(false)
const editRef = ref<FormInstance>()
const editing = ref(false)
const editForm = reactive({ id: '', name: '', description: '' })

const editRules: FormRules = {
  name: [
    { required: true, message: '请输入知识库名称', trigger: 'blur' },
    { max: KB_LIMITS.nameMaxLength, message: `名称最长 ${KB_LIMITS.nameMaxLength} 个字符`, trigger: 'blur' },
  ],
}

async function load(): Promise<void> {
  loading.value = true
  try {
    const result = await listKnowledgeBases({
      page: page.value,
      pageSize: pageSize.value,
      keyword: keyword.value.trim() === '' ? null : keyword.value.trim(),
    })
    list.value = result.list ?? []
    total.value = result.total ?? 0
  } catch {
    list.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

function search(): void {
  page.value = PAGE_DEFAULT
  void load()
}

function resetSearch(): void {
  keyword.value = ''
  page.value = PAGE_DEFAULT
  void load()
}

function openCreate(): void {
  createForm.name = ''
  createForm.description = ''
  createForm.chunkSize = KB_LIMITS.chunkSize.default
  createForm.chunkOverlap = KB_LIMITS.chunkOverlap.default
  createForm.chunkStrategy = 'FIXED_SIZE'
  createVisible.value = true
}

async function submitCreate(): Promise<void> {
  const form = createRef.value
  if (!form) return
  try {
    await form.validate()
  } catch {
    return
  }
  // 契约 40009：overlap 必须小于 chunkSize，前端先拦一道
  if (createForm.chunkOverlap >= createForm.chunkSize) {
    ElMessage.warning('重叠字符数必须小于分块目标字符数')
    return
  }
  creating.value = true
  try {
    await createKnowledgeBase({
      name: createForm.name.trim(),
      description: createForm.description.trim() === '' ? null : createForm.description,
      chunkSize: createForm.chunkSize,
      chunkOverlap: createForm.chunkOverlap,
      chunkStrategy: createForm.chunkStrategy,
    })
    ElMessage.success('知识库创建成功')
    createVisible.value = false
    page.value = PAGE_DEFAULT
    await load()
  } finally {
    creating.value = false
  }
}

function openEdit(raw: unknown): void {
  const row = asRow<KnowledgeBase>(raw)
  editForm.id = row.id
  editForm.name = row.name
  editForm.description = row.description ?? ''
  editVisible.value = true
}

async function submitEdit(): Promise<void> {
  const form = editRef.value
  if (!form) return
  try {
    await form.validate()
  } catch {
    return
  }
  editing.value = true
  try {
    // 契约 4.4：description 传空串表示清空；分块参数不可改
    await updateKnowledgeBase(editForm.id, {
      name: editForm.name.trim(),
      description: editForm.description,
    })
    ElMessage.success('已保存')
    editVisible.value = false
    await load()
  } finally {
    editing.value = false
  }
}

async function handleDelete(raw: unknown): Promise<void> {
  const row = asRow<KnowledgeBase>(raw)
  try {
    await ElMessageBox.confirm(
      `删除知识库「${row.name}」将级联删除其下 ${row.documentCount} 个文档、${row.nodeCount} 个节点及相关评估用例，且不可恢复。`,
      '危险操作确认',
      {
        type: 'warning',
        confirmButtonText: '确认删除',
        cancelButtonText: '取消',
        confirmButtonClass: 'el-button--danger',
      },
    )
  } catch {
    return
  }
  const result = await deleteKnowledgeBase(row.id)
  ElMessage.success(
    `已删除：文档 ${result.deletedDocuments} 个、节点 ${result.deletedNodes} 个、评估用例 ${result.deletedEvalCases} 个`,
  )
  await load()
}

function goDocuments(raw: unknown): void {
  const row = asRow<KnowledgeBase>(raw)
  void router.push({ name: 'documents', query: { knowledgeBaseId: row.id } })
}

function goRetrieval(raw: unknown): void {
  const row = asRow<KnowledgeBase>(raw)
  void router.push({ name: 'retrieval', query: { knowledgeBaseId: row.id } })
}

onMounted(() => {
  void load()
})
</script>

<template>
  <div class="page">
    <el-card shadow="never" class="toolbar">
      <div class="toolbar__row">
        <div class="toolbar__filters">
          <el-input
            v-model="keyword"
            placeholder="按名称模糊搜索"
            clearable
            style="width: 240px"
            @keyup.enter="search"
            @clear="search"
          />
          <el-button type="primary" @click="search">查询</el-button>
          <el-button @click="resetSearch">重置</el-button>
        </div>
        <el-button type="primary" @click="openCreate">新建知识库</el-button>
      </div>
    </el-card>

    <el-card shadow="never">
      <el-table v-loading="loading" :data="list" border stripe style="width: 100%">
        <template #empty>
          <el-empty description="暂无知识库，点击右上角「新建知识库」开始" />
        </template>
        <el-table-column prop="name" label="名称" min-width="180" show-overflow-tooltip />
        <el-table-column prop="description" label="描述" min-width="200" show-overflow-tooltip>
          <template #default="{ row }">{{ row.description ?? '—' }}</template>
        </el-table-column>
        <el-table-column label="分块参数" min-width="200">
          <template #default="{ row }">
            <div class="chunk-params">
              <el-tag size="small" type="info">chunkSize {{ row.chunkSize }}</el-tag>
              <el-tag size="small" type="info">overlap {{ row.chunkOverlap }}</el-tag>
              <el-tag size="small" type="primary">
                {{ labelOf(CHUNK_STRATEGY_LABEL, row.chunkStrategy) }}
              </el-tag>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="文档数" width="100" align="right">
          <template #default="{ row }">{{ formatNumber(row.documentCount) }}</template>
        </el-table-column>
        <el-table-column label="节点数" width="100" align="right">
          <template #default="{ row }">{{ formatNumber(row.nodeCount) }}</template>
        </el-table-column>
        <el-table-column label="向量模型" min-width="180">
          <template #default="{ row }">
            <div>{{ row.embeddingModel }}</div>
            <el-text size="small" type="info">维度 {{ row.embeddingDimension }}</el-text>
          </template>
        </el-table-column>
        <el-table-column label="创建时间" width="170">
          <template #default="{ row }">{{ formatTime(row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="300" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="goDocuments(row)">文档</el-button>
            <el-button link type="primary" @click="goRetrieval(row)">检索</el-button>
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
        @current-change="load"
      />
    </el-card>

    <!-- 新建 -->
    <el-dialog v-model="createVisible" title="新建知识库" width="620px" :close-on-click-modal="false">
      <el-form ref="createRef" :model="createForm" :rules="createRules" label-width="130px">
        <el-form-item label="名称" prop="name">
          <el-input v-model="createForm.name" maxlength="128" show-word-limit placeholder="如：RAPTOR 论文与实现" />
        </el-form-item>
        <el-form-item label="描述" prop="description">
          <el-input v-model="createForm.description" type="textarea" :rows="2" placeholder="可选" />
        </el-form-item>
        <el-divider content-position="left">分块参数（建库后固化，不可修改）</el-divider>
        <el-form-item label="chunkSize">
          <el-input-number
            v-model="createForm.chunkSize"
            :min="KB_LIMITS.chunkSize.min"
            :max="KB_LIMITS.chunkSize.max"
            :step="KB_LIMITS.chunkSize.step"
          />
          <span class="hint">分块目标字符数，范围 {{ KB_LIMITS.chunkSize.min }} ~ {{ KB_LIMITS.chunkSize.max }}</span>
        </el-form-item>
        <el-form-item label="chunkOverlap">
          <el-input-number
            v-model="createForm.chunkOverlap"
            :min="KB_LIMITS.chunkOverlap.min"
            :max="Math.max(KB_LIMITS.chunkOverlap.min, createForm.chunkSize - 1)"
            :step="KB_LIMITS.chunkOverlap.step"
          />
          <span class="hint">必须小于 chunkSize</span>
        </el-form-item>
        <el-form-item label="chunkStrategy">
          <el-select v-model="createForm.chunkStrategy" style="width: 100%">
            <el-option
              v-for="option in CHUNK_STRATEGY_OPTIONS"
              :key="option.value"
              :label="option.label"
              :value="option.value"
            />
          </el-select>
        </el-form-item>
        <el-alert
          :title="labelOf(CHUNK_STRATEGY_HINT, createForm.chunkStrategy)"
          type="info"
          :closable="false"
          show-icon
        />
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" :loading="creating" @click="submitCreate">创建</el-button>
      </template>
    </el-dialog>

    <!-- 重命名 / 改描述 -->
    <el-dialog v-model="editVisible" title="编辑知识库" width="560px" :close-on-click-modal="false">
      <el-form ref="editRef" :model="editForm" :rules="editRules" label-width="110px">
        <el-form-item label="名称" prop="name">
          <el-input v-model="editForm.name" maxlength="128" show-word-limit />
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="editForm.description" type="textarea" :rows="3" placeholder="清空描述请直接删除全部文字" />
        </el-form-item>
        <el-alert
          title="分块参数（chunkSize / chunkOverlap / chunkStrategy）在建库时固化，本接口不支持修改；需要新参数请新建知识库。"
          type="warning"
          :closable="false"
          show-icon
        />
      </el-form>
      <template #footer>
        <el-button @click="editVisible = false">取消</el-button>
        <el-button type="primary" :loading="editing" @click="submitEdit">保存</el-button>
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

.toolbar__row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
}

.toolbar__filters {
  display: flex;
  align-items: center;
  gap: 8px;
}

.chunk-params {
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
</style>
