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
        redirect: '/creative'
      },
      {
        path: 'home',
        redirect: '/creative'
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
        path: 'schedules',
        name: 'AgentSchedules',
        component: () => import('../views/schedules/Schedules.vue'),
        meta: {
          title: '定时任务 - Aster',
          description: '到点拉起智能体，按指令无人值守自主干活。'
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
        // 我的创作（redesign §3.1/§5.2）：项目 + 作品双视图；旧 /creative/works 永久重定向，历史链接不失效
        path: 'creative/library',
        name: 'CreativeLibrary',
        component: () => import('../views/creative/ProjectLibrary.vue'),
        meta: {
          title: '我的创作 - Aster',
          description: '项目与作品：按类型筛选、搜索、找回创作上下文。'
        }
      },
      {
        path: 'creative/works',
        redirect: '/creative/library'
      },
      {
        // 技能库（skill 设计 §5.1）：面向创作者的技能目录，管理配置仍在 /settings/skill
        path: 'creative/skills',
        name: 'CreativeSkills',
        component: () => import('../views/creative/skills/CreativeSkills.vue'),
        meta: {
          title: '技能库 - Aster',
          description: '找到适合这次创作的方法：浏览、了解输入输出，在创作台使用。'
        }
      },
      {
        // 项目工作区：Shell 持有项目、任务与剧情状态，各分区是子路由（可直接深链）
        path: 'creative/projects/:projectId',
        component: () => import('../views/creative/project/ProjectShell.vue'),
        meta: {
          title: '创作台 - Aster',
          description: '项目内创作：剧情、分镜、候选选片、任务与素材。'
        },
        children: [
          {
            path: 'canvas',
            name: 'CreativeProjectCanvas',
            component: () => import('../views/creative/project/ProjectCanvasTab.vue')
          },
          {
            // P0 制作视图：五阶段引导（画风/故事/分镜/视频/成片），VIDEO 项目默认入口
            path: 'production',
            name: 'CreativeProjectProduction',
            component: () => import('../views/creative/project/ProjectProductionTab.vue')
          },
          {
            path: '',
            name: 'CreativeProject',
            component: () => import('../views/creative/project/ProjectEntry.vue')
          },
          {
            path: 'studio',
            name: 'CreativeProjectStudio',
            redirect: to => ({ path: `/creative/projects/${to.params.projectId}/canvas`, query: to.query })
          },
          {
            path: 'story',
            name: 'CreativeProjectStory',
            component: () => import('../views/creative/project/ProjectStoryTab.vue')
          },
          {
            path: 'storyboard',
            name: 'CreativeProjectStoryboard',
            component: () => import('../views/creative/project/ProjectStoryboardTab.vue')
          },
          {
            path: 'settings',
            name: 'CreativeProjectSettings',
            component: () => import('../views/creative/project/ProjectSettingsTab.vue')
          },
          {
            path: 'chapters',
            name: 'CreativeProjectChapters',
            component: () => import('../views/creative/project/ProjectChaptersTab.vue')
          },
          {
            path: 'workflow',
            name: 'CreativeProjectWorkflow',
            component: () => import('../views/creative/project/ProjectWorkflowTab.vue')
          },
          {
            path: 'tasks',
            name: 'CreativeProjectTasks',
            component: () => import('../views/creative/project/ProjectTasksTab.vue')
          },
          {
            path: 'assets',
            name: 'CreativeProjectAssets',
            component: () => import('../views/creative/project/ProjectAssetsTab.vue')
          }
        ]
      },
      {
        // 「全部生成记录」深层入口（redesign §3.1）：活动抽屉直达；历史链接不失效
        path: 'creative/tasks',
        name: 'CreativeTasks',
        component: () => import('../views/creative/CreativeTasks.vue'),
        meta: { title: '生成记录 - Aster', description: '全部生成任务的状态、失败原因与恢复动作。' }
      },
      {
        path: 'creative/tasks/:jobId',
        name: 'CreativeTaskDetail',
        component: () => import('../views/creative/CreativeTasks.vue'),
        meta: { title: '生成记录详情 - Aster', description: '生成任务详情与可用操作。' }
      },
      {
        path: 'creative/assets',
        name: 'CreativeAssets',
        component: () => import('../views/creative/CreativeAssets.vue'),
        meta: { title: '素材库 - Aster', description: '跨项目的创作素材资源台：浏览、筛选与引用。' }
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
        // 管理员用户管理：入口只在全局导航对 ADMIN 展示，接口侧同样做管理员校验
        path: 'users',
        name: 'UserManagement',
        component: () => import('../views/UserManagement.vue'),
        meta: {
          title: '用户管理 - Aster',
          description: '管理员添加与删除本租户账号。'
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
