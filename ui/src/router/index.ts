import { createRouter, createWebHistory } from 'vue-router'

/** 路由与左侧菜单一一对应；按需加载，避免首屏打包所有页面 */
const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    { path: '/', redirect: '/knowledge-bases' },
    {
      path: '/knowledge-bases',
      name: 'knowledge-bases',
      component: () => import('@/views/KnowledgeBaseView.vue'),
      meta: { title: '知识库管理' },
    },
    {
      path: '/documents',
      name: 'documents',
      component: () => import('@/views/DocumentView.vue'),
      meta: { title: '文档管理' },
    },
    {
      path: '/chunks',
      name: 'chunks',
      component: () => import('@/views/ChunkView.vue'),
      meta: { title: '分块预览' },
    },
    {
      path: '/raptor',
      name: 'raptor',
      component: () => import('@/views/RaptorTreeView.vue'),
      meta: { title: 'RAPTOR 树查看' },
    },
    {
      path: '/retrieval',
      name: 'retrieval',
      component: () => import('@/views/RetrievalTestView.vue'),
      meta: { title: '召回测试' },
    },
    {
      path: '/eval',
      name: 'eval',
      component: () => import('@/views/EvalView.vue'),
      meta: { title: '召回率评估' },
    },
    {
      path: '/tasks',
      name: 'tasks',
      component: () => import('@/views/TaskListView.vue'),
      meta: { title: '任务中心' },
    },
    { path: '/:pathMatch(.*)*', redirect: '/knowledge-bases' },
  ],
})

router.afterEach((to) => {
  const title = typeof to.meta.title === 'string' ? to.meta.title : ''
  document.title = title ? `${title} · DocRaptor` : 'DocRaptor 管理面板'
})

export default router
