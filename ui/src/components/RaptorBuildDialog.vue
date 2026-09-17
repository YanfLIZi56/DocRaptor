<script setup lang="ts">
/**
 * RAPTOR 建树参数弹窗（可复用）—— 契约 5.1 POST /api/raptor/trees。
 * 触发后拿到 taskId，交给 stores/task.ts 按 1000ms 轮询进度。
 */
import { computed, reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { buildRaptorTree } from '@/api/raptor'
import { errorCodeOf } from '@/api/request'
import { useTaskStore } from '@/stores/task'
import { ERROR_CODE, GMM_COVARIANCE_LABEL, RAPTOR_LIMITS, toOptions } from '@/constants'
import type { GmmCovarianceType, RaptorBuildRequest } from '@/types'

const props = defineProps<{
  modelValue: boolean
  documentId: string
  documentName?: string
}>()

const emit = defineEmits<{
  (e: 'update:modelValue', value: boolean): void
  /** 成功触发建树（已拿到 taskId） */
  (e: 'submitted', taskId: string): void
  /** 任务到达终态 */
  (e: 'finished', taskId: string): void
}>()

const taskStore = useTaskStore()
const submitting = ref(false)

const visible = computed({
  get: () => props.modelValue,
  set: (value: boolean) => emit('update:modelValue', value),
})

const covarianceOptions = toOptions(GMM_COVARIANCE_LABEL)

const form = reactive({
  maxLevel: RAPTOR_LIMITS.maxLevel.default as number,
  forceRebuild: false,
  umapNNeighbors: RAPTOR_LIMITS.umapNNeighbors.default as number,
  umapMinDist: RAPTOR_LIMITS.umapMinDist.default as number,
  gmmMaxClusters: RAPTOR_LIMITS.gmmMaxClusters.default as number,
  gmmCovarianceType: null as GmmCovarianceType | null,
  summaryPrompt: '',
})

function reset(): void {
  form.maxLevel = RAPTOR_LIMITS.maxLevel.default
  form.forceRebuild = false
  form.umapNNeighbors = RAPTOR_LIMITS.umapNNeighbors.default
  form.umapMinDist = RAPTOR_LIMITS.umapMinDist.default
  form.gmmMaxClusters = RAPTOR_LIMITS.gmmMaxClusters.default
  form.gmmCovarianceType = null
  form.summaryPrompt = ''
}

watch(
  () => props.modelValue,
  (open) => {
    if (open) reset()
  },
)

async function submit(): Promise<void> {
  if (!props.documentId) {
    ElMessage.warning('请先选择目标文档')
    return
  }
  submitting.value = true
  try {
    const payload: RaptorBuildRequest = {
      documentId: props.documentId,
      maxLevel: form.maxLevel,
      forceRebuild: form.forceRebuild,
      umapNNeighbors: form.umapNNeighbors,
      umapMinDist: form.umapMinDist,
      gmmMaxClusters: form.gmmMaxClusters,
      gmmCovarianceType: form.gmmCovarianceType,
      summaryPrompt: form.summaryPrompt.trim() === '' ? null : form.summaryPrompt,
    }
    const result = await buildRaptorTree(payload)
    visible.value = false
    emit('submitted', result.taskId)
    taskStore.track(result.taskId, {
      title: `构建 RAPTOR 树：${props.documentName ?? props.documentId}`,
      onFinish: () => emit('finished', result.taskId),
    })
  } catch (error) {
    if (errorCodeOf(error) === ERROR_CODE.TREE_ALREADY_EXISTS) {
      ElMessage.warning('该文档已存在构建完成的 RAPTOR 树；确认要重建请勾选「强制重建」')
    }
    // 其余错误码（40905 未向量化 / 40902 已有任务 / 40001 参数越界）已由 request.ts 统一提示
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <el-dialog v-model="visible" title="构建 RAPTOR 树" width="620px" :close-on-click-modal="false">
    <el-alert
      type="info"
      :closable="false"
      show-icon
      title="叶子文本块与向量不会重建；勾选「强制重建」会先删除已有 SUMMARY 节点再重新聚类生成。"
      class="tip"
    />

    <el-form label-width="150px" label-position="right">
      <el-form-item label="目标文档">
        <el-text type="primary">{{ documentName ?? documentId }}</el-text>
      </el-form-item>

      <el-form-item label="树深上限 maxLevel">
        <el-input-number
          v-model="form.maxLevel"
          :min="RAPTOR_LIMITS.maxLevel.min"
          :max="RAPTOR_LIMITS.maxLevel.max"
          :step="RAPTOR_LIMITS.maxLevel.step"
        />
        <span class="hint">默认 3（L3）</span>
      </el-form-item>

      <el-form-item label="强制重建">
        <el-switch v-model="form.forceRebuild" />
        <span class="hint">不勾选时若树已存在，后端返回 40903</span>
      </el-form-item>

      <el-divider content-position="left">聚类参数覆盖（不填用服务端默认值）</el-divider>

      <el-form-item label="UMAP n_neighbors">
        <el-input-number
          v-model="form.umapNNeighbors"
          :min="RAPTOR_LIMITS.umapNNeighbors.min"
          :max="RAPTOR_LIMITS.umapNNeighbors.max"
          :step="RAPTOR_LIMITS.umapNNeighbors.step"
        />
      </el-form-item>

      <el-form-item label="UMAP min_dist">
        <el-input-number
          v-model="form.umapMinDist"
          :min="RAPTOR_LIMITS.umapMinDist.min"
          :max="RAPTOR_LIMITS.umapMinDist.max"
          :step="RAPTOR_LIMITS.umapMinDist.step"
          :precision="2"
        />
      </el-form-item>

      <el-form-item label="GMM 簇数上限">
        <el-input-number
          v-model="form.gmmMaxClusters"
          :min="RAPTOR_LIMITS.gmmMaxClusters.min"
          :max="RAPTOR_LIMITS.gmmMaxClusters.max"
          :step="RAPTOR_LIMITS.gmmMaxClusters.step"
        />
      </el-form-item>

      <el-form-item label="GMM 协方差类型">
        <el-select v-model="form.gmmCovarianceType" placeholder="使用服务端默认" clearable class="wide">
          <el-option
            v-for="option in covarianceOptions"
            :key="option.value"
            :label="option.label"
            :value="option.value"
          />
        </el-select>
      </el-form-item>

      <el-form-item label="摘要 Prompt 覆盖">
        <el-input
          v-model="form.summaryPrompt"
          type="textarea"
          :rows="3"
          placeholder="调试用；留空使用配置的正式模板（平台仍会追加硬约束句）"
        />
      </el-form-item>
    </el-form>

    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :loading="submitting" @click="submit">开始建树</el-button>
    </template>
  </el-dialog>
</template>

<style scoped>
.tip {
  margin-bottom: 16px;
}

.hint {
  margin-left: 10px;
  color: var(--el-text-color-secondary);
  font-size: 12px;
}

.wide {
  width: 100%;
}
</style>
