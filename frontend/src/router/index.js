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
          description: '已制定的工作流列表：新建或编辑工作流。'
        }
      },
      {
        path: 'workflows/:workflowKey',
        name: 'WorkflowEditor',
        component: () => import('../views/WorkflowEditor.vue'),
        meta: {
          title: '工作流编辑 - Aster',
          description: '节点画布：自定义节点与连线，保存新版本后按图执行。'
        }
      },
      {
        path: 'creative',
        name: 'CreativeHome',
        component: () => import('../views/creative/CreativeHome.vue'),
        meta: {
          title: 'AI 创作 - Aster',
          description: '图片、视频、小说与漫画创作：任务与素材保存在服务端，可随时找回。'
        }
      },
      {
        path: 'creative/projects/:projectId',
        name: 'CreativeProject',
        component: () => import('../views/creative/CreativeProject.vue'),
        meta: {
          title: '创作台 - Aster',
          description: '项目内创作：提示词、参数、任务状态与素材。'
        }
      },
      {
        path: 'creative/tasks',
        name: 'CreativeTasks',
        component: () => import('../views/creative/CreativeTasks.vue'),
        meta: { title: '创作任务 - Aster', description: '查看全部图片生成任务与状态。' }
      },
      {
        path: 'creative/tasks/:jobId',
        name: 'CreativeTaskDetail',
        component: () => import('../views/creative/CreativeTasks.vue'),
        meta: { title: '任务详情 - Aster', description: '图片生成任务详情与可用操作。' }
      },
      {
        path: 'creative/assets',
        name: 'CreativeAssets',
        component: () => import('../views/creative/CreativeAssets.vue'),
        meta: { title: '创作素材 - Aster', description: '跨项目的个人图片素材库。' }
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
