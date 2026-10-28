import { createRouter, createWebHistory } from 'vue-router'
import { getToken } from '../api/auth'
import WorkbenchShell from '../components/workbench/WorkbenchShell.vue'

// 全部板块共用 WorkbenchShell 常驻外壳：左侧导航固定，内容区按路由切换
const routes = [
  {
    path: '/login',
    name: 'Login',
    component: () => import('../views/Login.vue'),
    meta: { title: '登录 - Aster', public: true }
  },
  {
    path: '/',
    component: WorkbenchShell,
    children: [
      {
        path: '',
        redirect: '/workbench'
      },
      {
        path: 'home',
        redirect: '/workbench'
      },
      {
        path: 'workbench',
        name: 'Workbench',
        component: () => import('../views/Workbench.vue'),
        meta: {
          title: '工作台 - Aster',
          description: 'Aster AI 工作台：从这里开始任务、继续工作。'
        }
      },
      {
        path: 'super-agent',
        name: 'SuperAgent',
        component: () => import('../views/SuperAgent.vue'),
        meta: {
          title: '超级智能体 - Aster',
          description: '超级智能体是 Aster 的通用任务协作助手。'
        }
      },
      {
        path: 'workflows',
        name: 'Workflows',
        component: () => import('../views/Workflows.vue'),
        meta: {
          title: '工作流 - Aster',
          description: '把多步任务固化为可复用、可观测的自动化流程。'
        }
      },
      {
        path: 'knowledge-admin',
        name: 'KnowledgeAdmin',
        component: () => import('../views/KnowledgeAdmin.vue'),
        meta: {
          title: '知识库管理 - Aster',
          description: '查看各智能体的私有知识文档、版本状态与实际向量切片。'
        }
      },
      {
        // 设置中心：统一分类外壳，内部页面逐步迁移为工作台样式
        path: 'settings',
        component: () => import('../components/workbench/SettingsLayout.vue'),
        children: [
          {
            path: '',
            redirect: '/settings/model'
          },
          {
            path: 'model',
            name: 'SettingsModel',
            component: () => import('../views/ModelCatalogSettings.vue'),
            meta: { title: '模型设置 - Aster', description: '管理模型供应商、模型与用途绑定。' }
          },
          {
            path: 'skill',
            name: 'SettingsSkill',
            component: () => import('../views/SkillSettings.vue'),
            meta: { title: 'Skill 配置 - Aster', description: '配置各个智能体可使用的内置 Skill。' }
          },
          {
            path: 'mcp',
            name: 'SettingsMcp',
            component: () => import('../views/McpSettings.vue'),
            meta: { title: 'MCP 管理 - Aster', description: '管理超级智能体可用的远程 MCP SSE 服务。' }
          },
          {
            path: 'memory',
            name: 'SettingsMemory',
            component: () => import('../views/MemorySettings.vue'),
            meta: {
              title: '记忆管理 - Aster',
              description: '查看、修正与删除智能体跨会话记住的关于你的事实。'
            }
          },
          {
            path: 'permission',
            name: 'SettingsPermission',
            component: () => import('../views/PermissionSettings.vue'),
            meta: {
              title: '权限模式 - Aster',
              description: '配置默认权限模式与工具 allow/deny 规则。'
            }
          }
        ]
      },
      {
        path: 'model-settings',
        redirect: '/settings/model'
      },
      {
        path: 'model-catalog',
        redirect: '/settings/model'
      },
      {
        path: 'skill-settings',
        redirect: '/settings/skill'
      },
      {
        path: 'mcp-settings',
        redirect: '/settings/mcp'
      },
      {
        path: 'memory-settings',
        redirect: '/settings/memory'
      },
      {
        path: 'permission-settings',
        redirect: '/settings/permission'
      }
    ]
  }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

// 全局导航守卫：未登录跳转登录页；设置文档标题
router.beforeEach((to, from, next) => {
  if (to.meta.title) {
    document.title = to.meta.title
  }
  if (!to.meta.public && !getToken()) {
    next({ path: '/login', query: to.fullPath && to.fullPath !== '/' ? { redirect: to.fullPath } : {} })
    return
  }
  next()
})

export default router
