import { createRouter, createWebHistory } from 'vue-router'

const routes = [
  { path: '/login', name: 'login', component: () => import('../views/Login.vue'), meta: { public: true } },
  {
    path: '/',
    component: () => import('../views/Layout.vue'),
    redirect: '/dashboard',
    children: [
      { path: 'dashboard', name: 'dashboard', component: () => import('../views/Dashboard.vue'), meta: { title: '核验总览' } },
      { path: 'contracts', name: 'contracts', component: () => import('../views/Contracts.vue'), meta: { title: '资料接入契约库' } },
      { path: 'entities', name: 'entities', component: () => import('../views/Entities.vue'), meta: { title: '实体消歧' } },
      { path: 'drawings', name: 'drawings', component: () => import('../views/Drawings.vue'), meta: { title: '图号与修订' } },
      { path: 'events', name: 'events', component: () => import('../views/Events.vue'), meta: { title: '追加式实装事件' } },
      { path: 'rules', name: 'rules', component: () => import('../views/Rules.vue'), meta: { title: '替代规则与血缘' } },
      { path: 'snapshots', name: 'snapshots', component: () => import('../views/Snapshots.vue'), meta: { title: '配置快照与时点查询' } },
      { path: 'compatibility', name: 'compatibility', component: () => import('../views/Compatibility.vue'), meta: { title: '兼容事实与发布闸门' } },
      { path: 'reliability', name: 'reliability', component: () => import('../views/Reliability.vue'), meta: { title: '替代件可靠性观察' } },
      { path: 'impact', name: 'impact', component: () => import('../views/Impact.vue'), meta: { title: '影响分析' } },
      { path: 'quality', name: 'quality', component: () => import('../views/Quality.vue'), meta: { title: '质量事件' } }
    ]
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

router.beforeEach((to) => {
  const token = localStorage.getItem('mdm-token')
  if (!to.meta.public && !token) {
    return '/login'
  }
  if (to.path === '/login' && token) {
    return '/dashboard'
  }
  return true
})

export default router
