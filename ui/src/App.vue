<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import { storeToRefs } from 'pinia'
import { useTaskStore } from '@/stores/task'
import TaskProgressDrawer from '@/components/TaskProgressDrawer.vue'

const route = useRoute()
const taskStore = useTaskStore()
const { runningCount } = storeToRefs(taskStore)

const menus = [
  { path: '/knowledge-bases', label: '知识库管理' },
  { path: '/documents', label: '文档管理' },
  { path: '/chunks', label: '分块预览' },
  { path: '/raptor', label: 'RAPTOR 树查看' },
  { path: '/retrieval', label: '召回测试' },
  { path: '/eval', label: '召回率评估' },
  { path: '/tasks', label: '任务中心' },
]

const activeMenu = computed(() => route.path)
const pageTitle = computed(() =>
  typeof route.meta.title === 'string' ? route.meta.title : 'DocRaptor 管理面板',
)
</script>

<template>
  <el-container class="app-shell">
    <el-aside width="232px" class="app-aside">
      <div class="brand">
        <div class="brand__name">DocRaptor</div>
        <div class="brand__desc">RAPTOR 检索增强管理面板</div>
      </div>
      <el-menu :default-active="activeMenu" router class="app-menu">
        <el-menu-item v-for="menu in menus" :key="menu.path" :index="menu.path">
          {{ menu.label }}
        </el-menu-item>
      </el-menu>
    </el-aside>

    <el-container>
      <el-header class="app-header">
        <span class="page-title">{{ pageTitle }}</span>
        <div class="header-actions">
          <el-badge :value="runningCount" :hidden="runningCount === 0" type="primary">
            <el-button @click="taskStore.openDrawer()">任务进度</el-button>
          </el-badge>
        </div>
      </el-header>
      <el-main class="app-main">
        <router-view />
      </el-main>
    </el-container>
  </el-container>

  <TaskProgressDrawer />
</template>

<style>
html,
body,
#app {
  height: 100%;
  margin: 0;
}

body {
  font-family: 'Helvetica Neue', Helvetica, 'PingFang SC', 'Microsoft YaHei', Arial, sans-serif;
  background-color: #f5f7fa;
  color: #303133;
}
</style>

<style scoped>
.app-shell {
  height: 100vh;
}

.app-aside {
  background-color: #ffffff;
  border-right: 1px solid var(--el-border-color-light);
  display: flex;
  flex-direction: column;
}

.brand {
  padding: 18px 20px 14px;
  border-bottom: 1px solid var(--el-border-color-lighter);
}

.brand__name {
  font-size: 18px;
  font-weight: 700;
  color: var(--el-color-primary);
}

.brand__desc {
  margin-top: 4px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.app-menu {
  border-right: none;
  flex: 1;
}

.app-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  background-color: #ffffff;
  border-bottom: 1px solid var(--el-border-color-light);
}

.page-title {
  font-size: 16px;
  font-weight: 600;
}

.app-main {
  padding: 16px 20px 32px;
  overflow: auto;
}
</style>
