<script setup lang="ts">
/** 文档四步流水线状态（解析 / 分块 / 向量化 / 建树）的紧凑展示，文档列表与详情抽屉共用 */
import { STEP_STATUS_LABEL, STEP_STATUS_TAG, labelOf, tagTypeOf } from '@/constants'
import type { StepStatus } from '@/types'

const props = defineProps<{
  parseStatus: StepStatus
  chunkStatus: StepStatus
  embedStatus: StepStatus
  treeStatus: StepStatus
}>()

const steps = [
  { label: '解析', key: 'parseStatus' },
  { label: '分块', key: 'chunkStatus' },
  { label: '向量化', key: 'embedStatus' },
  { label: '建树', key: 'treeStatus' },
] as const
</script>

<template>
  <div class="stage-tags">
    <el-tag
      v-for="step in steps"
      :key="step.key"
      :type="tagTypeOf(STEP_STATUS_TAG, props[step.key])"
      size="small"
      effect="light"
    >
      {{ step.label }}：{{ labelOf(STEP_STATUS_LABEL, props[step.key]) }}
    </el-tag>
  </div>
</template>

<style scoped>
.stage-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
}
</style>
