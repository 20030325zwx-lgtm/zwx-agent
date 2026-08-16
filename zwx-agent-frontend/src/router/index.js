import { createRouter, createWebHistory } from 'vue-router'

const routes = [
  {
    path: '/',
    name: 'Home',
    component: () => import('../views/Home.vue'),
    meta: {
      title: 'ZWX Agent - 智能体目录',
      description: 'ZWX Agent 提供情感分析大师和超级智能体服务。'
    }
  },
  {
    path: '/love-master',
    name: 'LoveMaster',
    component: () => import('../views/LoveMaster.vue'),
    meta: {
      title: '情感分析大师 - ZWX Agent',
      description: '情感分析大师协助分析关系与沟通问题，提供可追溯的知识库引用。'
    }
  },
  {
    path: '/super-agent',
    name: 'SuperAgent',
    component: () => import('../views/SuperAgent.vue'),
    meta: {
      title: '超级智能体 - ZWX Agent',
      description: '超级智能体是 ZWX Agent 的通用任务协作助手。'
    }
  },
  {
    path: '/travel-planner',
    name: 'TravelPlanner',
    component: () => import('../views/TravelPlanner.vue'),
    meta: { title: '旅游规划专家 - ZWX Agent', description: '旅游规划专家提供行程建议并按需查询实时信息。' }
  },
  {
    path: '/knowledge-admin',
    name: 'KnowledgeAdmin',
    component: () => import('../views/KnowledgeAdmin.vue'),
    meta: {
      title: '知识库管理 - ZWX Agent',
      description: '查看情感分析大师的内置文档和实际向量切片。'
    }
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

// 全局导航守卫，设置文档标题
router.beforeEach((to, from, next) => {
  // 设置页面标题
  if (to.meta.title) {
    document.title = to.meta.title
  }
  next()
})

export default router
