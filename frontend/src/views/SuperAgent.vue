<template>
  <div class="super-layout">
    <ConversationSidebar v-if="!sidebarCollapsed || sidebarOpen"
      :conversations="conversations" :projects="projects" :active-id="chatId" :loading="historyLoading"
      :workspace-creating="workspaceCreating" :workspace-error="workspaceError" :open="sidebarOpen"
      :directory-view="directoryView" :directory-loading="directoryLoading"
      @create="createConversation" @create-in="createConversationIn"
      @create-workspace="createWorkspace" @browse-workspaces="browseWorkspaces"
      @choose-workspace="chooseWorkspace" @select="selectConversation" @delete="requestDelete"
      @close="sidebarOpen = false" @collapse="toggleSidebar" />
    <div v-if="sidebarOpen" class="sidebar-scrim" @click="sidebarOpen = false"></div>
    <main class="super-main">
      <header class="super-header">
        <div class="header-start">
          <button class="header-icon" type="button"
            :title="isNarrow ? '打开任务列表' : sidebarCollapsed ? '展开任务栏（⌘B）' : '收起任务栏（⌘B）'"
            :aria-label="isNarrow ? '打开任务列表' : '切换任务栏'"
            @click="toggleSidebar">
            <Menu v-if="isNarrow" :size="18" />
            <PanelLeftOpen v-else-if="sidebarCollapsed" :size="18" />
            <PanelLeftClose v-else :size="18" />
          </button>
          <div class="header-title">
            <strong>{{ activeConversationTitle }}</strong>
            <span class="header-sub">{{ activeWorkspaceName }}</span>
          </div>
        </div>
        <div class="header-end">
          <button class="header-mcp" type="button" title="MCP 管理" @click="router.push('/mcp-settings')">
            <Plug :size="14" />
            <span>MCP</span>
          </button>
          <span class="status" :class="connectionStatus">{{ statusText }}</span>
        </div>
      </header>

      <div class="workspace-main" :class="{ 'inspector-collapsed': !inspectorOpen, 'no-inspector': !activeRun }">
        <div class="workspace-column">
          <section class="workspace-canvas" aria-label="任务工作台">
            <template v-if="activeRun || messages.length">
              <div v-if="activeRun?.id" class="canvas-actions" :class="{ 'with-banner': activeRun?.awaitingPlan || (!!activeRun?.canDecideConfirmation && activeRun.pendingConfirmations?.length) }">
                <button type="button" :disabled="refreshingRun" title="刷新任务状态" aria-label="刷新任务状态" @click="refreshActiveRun">
                  <RotateCw :size="14" :class="{ spin: refreshingRun }" />
                </button>
                <button type="button" title="导出任务结果为 Markdown" aria-label="导出任务结果" @click="exportRun">
                  <Download :size="14" />
                </button>
              </div>
              <div v-if="activeRun?.awaitingPlan" class="plan-gate-banner" role="status">
                <span class="plan-gate-text">
                  📋 已生成 <strong>{{ activeRun.plan?.length || 0 }}</strong> 步执行计划，等待你确认后开始执行
                </span>
                <span class="plan-gate-actions">
                  <button type="button" class="plan-gate-secondary" @click="inspectorOpen = true">查看计划</button>
                  <button type="button" class="plan-gate-primary" @click="resumeActiveRun">开始执行</button>
                </span>
              </div>
              <div v-if="activeRun?.canDecideConfirmation && activeRun.pendingConfirmations?.length"
                class="confirmation-banner" role="status">
                <span class="confirmation-text">
                  ⏸ 任务已暂停：等待确认
                  <strong>{{ activeRun.pendingConfirmations.map(item => item.tool).join('、') }}</strong>
                </span>
                <span class="confirmation-mode">{{ activeRun.permissionMode || defaultPermissionMode }} 模式</span>
                <button type="button" class="confirmation-action" @click="confirmationDialogOpen = true">查看并确认</button>
              </div>
              <div class="canvas-conversation">
                <ChatRoom :messages="messages" :connection-status="connectionStatus" ai-type="super" hide-input
                  knowledge-search-available attachments-enabled attachment-accept="image/jpeg,image/png,image/gif,image/webp"
                  @edit-message="cancelActiveStream" @resend-message="resendMessage"
                  @continue-message="continueMessage" @stop-generation="stopGeneration" />
              </div>
            </template>

            <template v-else>
              <div class="workspace-empty">
                <span class="empty-avatar"><AsterAvatar /></span>
                <span class="empty-kicker">Aster Workspace</span>
                <h1>把目标交给星枢，先看计划，再看交付。</h1>
                <p>描述你要完成的事情，星枢会拆解子任务、调用工具执行，并把结果、引用和文件整理在同一个任务现场。</p>
                <div class="template-grid">
                  <button v-for="template in taskTemplates" :key="template.title" type="button" class="template-card" @click="useTemplate(template.prompt)">
                    <span class="template-icon"><component :is="template.icon" :size="17" /></span>
                    <strong>{{ template.title }}</strong>
                    <span>{{ template.desc }}</span>
                  </button>
                </div>
              </div>
            </template>

            <div v-if="messagesLoading" class="workspace-loading">正在恢复任务现场...</div>
          </section>

          <footer class="workspace-composer-shell">
            <TaskComposer ref="composerRef" :connection-status="connectionStatus" attachments-enabled
              attachment-accept="image/jpeg,image/png,image/gif,image/webp" knowledge-search-available web-search-available
              :permission-modes="permissionModes" :default-permission-mode="defaultPermissionMode"
              :placeholder="activeRun ? '继续补充要求，或描述下一步…' : '描述目标、约束或需要交付的结果…'"
              @send-message="sendMessage" @stop-generation="stopGeneration" />
          </footer>
        </div>

        <RunInspector v-if="activeRun && inspectorOpen" :sources="activeSources" :files="workspaceFiles" :run="activeRun"
          :conversation-id="chatId"
          @resume="resumeActiveRun" @cancel="stopGeneration"
          @collapse="inspectorOpen = false" />
        <button v-else-if="activeRun" class="inspector-rail" type="button" title="展开工作区面板" aria-label="展开工作区面板" @click="inspectorOpen = true">
          <PanelRightOpen :size="16" />
          <span>工作区</span>
        </button>
      </div>
      <AgentCompanion v-if="!activeRun || inspectorOpen" :state="companionState" :caption="companionCaption" />
    </main>
    <ConfirmDialog
      :open="!!pendingDelete"
      title="删除会话"
      :message="deleteMessage"
      confirm-text="删除"
      @confirm="confirmDelete"
      @cancel="pendingDelete = null"
    />
    <ConfirmDialog
      :open="confirmationDialogOpen"
      :danger="false"
      title="工具执行确认"
      :message="confirmationDialogMessage"
      confirm-text="允许执行"
      cancel-text="拒绝"
      @confirm="decidePendingConfirmation(true)"
      @cancel="decidePendingConfirmation(false)"
    />
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useHead } from '@vueuse/head'
import {
  BookOpen, Database, Download, Globe2, Menu, PanelLeftClose, PanelLeftOpen,
  PanelRightOpen, Plug, RotateCw, Workflow
} from 'lucide-vue-next'
import AgentCompanion from '../components/AgentCompanion.vue'
import AsterAvatar from '../components/AsterAvatar.vue'
import ChatRoom from '../components/ChatRoom.vue'
import ConversationSidebar from '../components/ConversationSidebar.vue'
import ConfirmDialog from '../components/ConfirmDialog.vue'
import RunInspector from '../components/workspace/RunInspector.vue'
import TaskComposer from '../components/workspace/TaskComposer.vue'
import { browseProjectDirectories, chatWithManus, resumeManusRun, reconnectManusRun, cancelManusRun, createManusConversation, createProject, decideManusConfirmation, deleteManusConversation, getManusConversationMessages, getManusFileUrl, getManusImageUrl, getManusRun, getPermissionSettings, listManusConversations, listManusRuns, listManusWebSources, listProjects, listWorkspaceFiles, selectProjectDirectory, uploadManusImage } from '../api'

const permissionModes = [
  { value: 'PLAN', label: '只读规划', hint: '仅允许只读工具，写操作直接拒绝' },
  { value: 'BUILD', label: '严格', hint: '文件写入与执行操作都需要确认' },
  { value: 'EDIT', label: '编辑', hint: '文件产出免确认，执行类工具需确认' },
  { value: 'GUARDED', label: '守护', hint: '常规操作免确认，高危操作需确认' },
  { value: 'YOLO', label: '全自动', hint: '全部放行，仅建议在隔离环境使用' }
]
const defaultPermissionMode = ref('EDIT')

useHead({ title: '超级智能体 - Aster', meta: [{ name: 'description', content: 'Aster 的自主智能体，可规划任务、调用工具并多步执行。' }] })
const router = useRouter()
const route = useRoute()
const messages = ref([])
const chatId = ref('')
const conversations = ref([])
const projects = ref([])
const selectedProjectId = ref(null)
const workspaceCreating = ref(false)
const workspaceError = ref('')
const directoryView = ref(null)
const directoryLoading = ref(false)
const connectionStatus = ref('disconnected')
const historyLoading = ref(false)
const messagesLoading = ref(false)
const sidebarOpen = ref(false)
const SIDEBAR_KEY = 'aster.super.sidebar.collapsed'
const sidebarCollapsed = ref(localStorage.getItem(SIDEBAR_KEY) === 'true')
watch(sidebarCollapsed, value => {
  try { localStorage.setItem(SIDEBAR_KEY, value ? 'true' : 'false') } catch (error) { console.error(error) }
})
const narrowMedia = window.matchMedia('(max-width: 720px)')
const isNarrow = ref(narrowMedia.matches)
const onNarrowChange = event => { isNarrow.value = event.matches }
const toggleSidebar = () => {
  if (isNarrow.value) sidebarOpen.value = !sidebarOpen.value
  else sidebarCollapsed.value = !sidebarCollapsed.value
}
const pendingDelete = ref(null)
const companionState = ref('idle')
const companionCaption = ref('')
const activeRun = ref(null)
const activeAnswerIndex = ref(-1)
const workspaceFiles = ref([])
const composerRef = ref(null)
const refreshingRun = ref(false)
const confirmationDialogOpen = ref(false)
const decidingConfirmation = ref(false)
let runPollTimer = null
const INSPECTOR_STORAGE_KEY = 'aster_super_agent_inspector_open'
const inspectorOpen = ref(localStorage.getItem(INSPECTOR_STORAGE_KEY) !== 'false')
watch(inspectorOpen, value => {
  try { localStorage.setItem(INSPECTOR_STORAGE_KEY, value ? 'true' : 'false') } catch (error) { console.error(error) }
})
let companionIdleTimer = null
let eventSource = null
let activeTurnStart = -1
let reconnectAttempts = 0

const taskTemplates = [
  { title: '拆解目标', desc: '把复杂目标拆成可执行计划并逐步完成', icon: Workflow, prompt: '帮我把这个目标拆成执行计划：' },
  { title: '调研写报告', desc: '联网检索资料，汇总成带引用的报告', icon: Globe2, prompt: '调研并整理一份关于以下主题的报告：' },
  { title: '分析数据', desc: '查询数据库或文件，输出关键结论', icon: Database, prompt: '分析这份数据并给出关键结论：' },
  { title: '知识库问答', desc: '优先依据私有资料回答并给出引用', icon: BookOpen, prompt: '请依据知识库回答：' }
]

const statusText = computed(() => connectionStatus.value === 'connecting'
  ? '正在执行'
  : connectionStatus.value === 'error' ? '连接异常' : '就绪')

const activeConversation = computed(() => conversations.value.find(item => item.id === chatId.value) || null)
const activeConversationTitle = computed(() => activeConversation.value?.title || '新任务')
const activeWorkspaceName = computed(() => {
  const project = projects.value.find(item => item.id === activeConversation.value?.projectId)
  return project?.name || '未绑定工作区'
})

const clearCompanionIdle = () => {
  if (companionIdleTimer) { clearTimeout(companionIdleTimer); companionIdleTimer = null }
}
const setCompanion = (state, caption = '') => {
  clearCompanionIdle()
  companionState.value = state
  companionCaption.value = caption
}
const settleCompanion = (state, caption, delay = 2600) => {
  setCompanion(state, caption)
  companionIdleTimer = setTimeout(() => {
    companionState.value = 'idle'
    companionCaption.value = ''
    companionIdleTimer = null
  }, delay)
}
const addMessage = (content, isUser, imageObjectKeys = []) => {
  const keys = Array.isArray(imageObjectKeys) ? [...imageObjectKeys] : (imageObjectKeys ? [imageObjectKeys] : [])
  const message = { content, isUser, time: Date.now(), imageKeys: keys, imageUrls: [] }
  messages.value.push(message)
  if (keys.length && chatId.value) {
    keys.forEach((key, index) => {
      getManusImageUrl(chatId.value, key)
        .then(url => { message.imageUrls[index] = url })
        .catch(error => console.error('Unable to load uploaded image:', error))
    })
  }
  return message
}
const toolLabels = { searchWeb: '搜索网页', generatePDF: '生成 PDF', writeFile: '写入文件', readFile: '读取文件', downloadResource: '下载资源' }
const activityLabel = detail => {
  const tool = detail.match(/工具\s+([A-Za-z0-9_]+)\s+返回/)?.[1]
  if (tool) return `已${toolLabels[tool] || `执行 ${tool}`}`
  return detail.startsWith('执行结束') ? '已达到步骤上限' : '正在处理任务'
}
const parseActivity = data => {
  try {
    const event = JSON.parse(data)
    if (event && typeof event === 'object' && event.summary) {
      return { phase: event.phase || 'work', agent: event.agent || '', summary: event.summary, detail: event.summary }
    }
  } catch { /* 旧格式纯文本兜底 */ }
  return { phase: 'work', agent: '', summary: activityLabel(data), detail: data }
}
const mapFiles = (conversationId, fileAttachments) => {
  try {
    return JSON.parse(fileAttachments || '[]').map(file => ({ ...file, url: '' }))
  } catch { return [] }
}
const hydrateFileUrls = conversationId => {
  messages.value.forEach(message => {
    ;(message.files || []).forEach((file, index) => {
      if (file.url || !file.path) return
      getManusFileUrl(conversationId, file.path)
        .then(url => { message.files[index] = { ...file, url } })
        .catch(() => { message.files[index] = { ...file, broken: true } })
    })
    ;(message.imageKeys || []).forEach((key, index) => {
      if (message.imageUrls[index]) return
      getManusImageUrl(conversationId, key)
        .then(url => { message.imageUrls[index] = url })
        .catch(error => console.error('Unable to restore message image:', error))
    })
  })
}
const parseImageKeys = value => {
  try { return JSON.parse(value || '[]') } catch { return [] }
}
const parseRagTrace = value => {
  try {
    const trace = JSON.parse(value || 'null')
    if (!trace?.traceId) return null
    trace.candidates?.forEach(candidate => { candidate.judgment = trace.judgments?.[candidate.documentId] || null })
    return trace
  } catch { return null }
}
const mapMessage = message => ({
  id: message.id,
  content: message.content,
  isUser: message.role === 'USER',
  time: new Date(message.createdAt).getTime(),
  activities: [],
  status: message.status,
  runId: message.executionRunId,
  files: mapFiles(chatId.value, message.fileAttachments),
  imageKeys: parseImageKeys(message.imageObjectKeys),
  imageUrls: [],
  ragTrace: parseRagTrace(message.ragRetrievalTrace)
})

// ── 智能体时间线：封存本轮实时轨迹，历史重载后回填到对应回答消息 ──
const sealActiveAnswer = () => {
  const answer = messages.value[activeAnswerIndex.value]
  if (answer && !answer.isUser && answer.startedAt && !answer.finishedAt) answer.finishedAt = Date.now()
}
const captureLiveTrace = () => {
  const answer = messages.value[activeAnswerIndex.value]
  if (!answer || answer.isUser) return null
  return {
    runId: answer.runId || null,
    status: answer.status || null,
    activities: answer.activities?.length ? answer.activities : null,
    startedAt: answer.startedAt || null,
    finishedAt: answer.finishedAt || null
  }
}
const restoreLiveTrace = trace => {
  if (!trace) return
  const answers = [...messages.value].reverse().filter(message => !message.isUser)
  const target = answers[0]
  if (!target) return
  if (trace.activities?.length) target.activities = trace.activities
  if (trace.startedAt && !target.startedAt) target.startedAt = trace.startedAt
  if (trace.finishedAt) target.finishedAt = trace.finishedAt
  if (trace.status) target.status = trace.status
}

const parseTokenUsage = value => {
  if (!value) return null
  if (typeof value === 'object') return value
  try {
    return JSON.parse(value)
  } catch {
    return null
  }
}

const parsePendingConfirmations = value => {
  if (!value) return []
  try {
    const node = typeof value === 'string' ? JSON.parse(value) : value
    return Array.isArray(node?.confirmations) ? node.confirmations : []
  } catch {
    return []
  }
}

const normalizeRun = (run, messageIndex = -1) => ({
  id: run?.id || '',
  status: run?.status || 'RUNNING',
  lastNode: run?.lastNode || '',
  nextNode: run?.nextNode || '',
  input: run?.input || '',
  plan: Array.isArray(run?.plan) ? run.plan : [],
  taskResults: Array.isArray(run?.taskResults) ? run.taskResults : [],
  events: Array.isArray(run?.events) ? run.events : [],
  verification: run?.verification || { status: '', feedback: '' },
  canResume: run?.canResume === true && run?.status !== 'RUNNING',
  canCancel: run?.canCancel === true,
  canDecideConfirmation: run?.canDecideConfirmation === true,
  awaitingPlan: run?.awaitingPlan === true,
  pendingConfirmations: parsePendingConfirmations(run?.pendingConfirmation),
  resumeCount: run?.resumeCount || 0,
  userMessageId: run?.userMessageId ?? null,
  assistantMessageId: run?.assistantMessageId ?? null,
  errorMessage: run?.errorMessage || '',
  tokenUsage: parseTokenUsage(run?.tokenUsage),
  webSearch: run?.webSearch !== false,
  createdAt: run?.createdAt || null,
  updatedAt: run?.updatedAt || null,
  messageIndex
})

const activeAnswer = computed(() => messages.value[activeAnswerIndex.value]?.content || '')
const activeRagTrace = computed(() => messages.value[activeAnswerIndex.value]?.ragTrace || null)
const activeMessageFiles = computed(() => messages.value[activeAnswerIndex.value]?.files || [])
const activeSources = computed(() => {
  const candidates = activeRagTrace.value?.candidates || []
  const selected = candidates.filter(candidate => candidate.selected)
  return selected.length ? selected : candidates
})
const refreshConversations = async () => {
  historyLoading.value = true
  try { conversations.value = await listManusConversations() }
  finally { historyLoading.value = false }
}
const refreshProjects = async () => { projects.value = await listProjects() }
const loadRunsForConversation = async (conversationId, preferredRunId = null) => {
  if (!conversationId) {
    activeRun.value = null
    activeAnswerIndex.value = -1
    return
  }
  try {
    const runs = await listManusRuns(conversationId)
    if (chatId.value !== conversationId) return
    if (!runs.length) {
      activeRun.value = null
      activeAnswerIndex.value = -1
      return
    }
    const chosenId = preferredRunId && runs.some(run => run.id === preferredRunId) ? preferredRunId : runs[0].id
    const detail = await getManusRun(conversationId, chosenId)
    if (chatId.value !== conversationId) return
    const index = messages.value.findIndex(message => !message.isUser
      && ((detail.assistantMessageId != null && String(message.id) === String(detail.assistantMessageId))
        || message.runId === detail.id))
    if (index >= 0) {
      const answer = messages.value[index]
      if (answer) {
        // 历史消息没有实时轨迹：用 run 的语义事件与起止时间补出时间线
        if (!answer.activities.length && detail.events?.length) {
          answer.activities = detail.events.map(event => ({
            phase: event.phase || 'work', agent: event.agent || '', summary: event.summary || '', detail: event.summary || ''
          }))
        }
        if (!answer.startedAt && detail.createdAt) {
          answer.startedAt = new Date(detail.createdAt).getTime()
          answer.finishedAt = detail.updatedAt ? new Date(detail.updatedAt).getTime() : null
        }
      }
    }
    activeRun.value = normalizeRun(detail, index)
    activeAnswerIndex.value = index
  } catch (error) {
    console.error('Unable to load run workspace:', error)
  }
}
const refreshWorkspaceFiles = async conversationId => {
  if (!conversationId) { workspaceFiles.value = []; return }
  try {
    const entries = await listWorkspaceFiles('super', conversationId)
    if (chatId.value !== conversationId) return
    const files = entries.map(entry => ({ ...entry, url: '' }))
    workspaceFiles.value = files
    await Promise.all(files.map(async (file, index) => {
      try {
        const url = await getManusFileUrl(conversationId, file.path)
        if (chatId.value === conversationId) workspaceFiles.value[index] = { ...files[index], url }
      } catch (error) {
        console.error('Unable to load workspace file:', error)
      }
    }))
  } catch (error) {
    console.error('Unable to list workspace files:', error)
  }
}
const stopRunPolling = () => {
  if (runPollTimer) { clearInterval(runPollTimer); runPollTimer = null }
}
const recoverFinishedRun = async (conversationId, run) => {
  eventSource?.close()
  eventSource = null
  connectionStatus.value = 'disconnected'
  activeTurnStart = -1
  reconnectAttempts = 0
  sealActiveAnswer()
  const liveTrace = captureLiveTrace()
  const history = await getManusConversationMessages(conversationId)
  if (chatId.value !== conversationId) return
  messages.value = history.map(mapMessage)
  restoreLiveTrace(liveTrace)
  hydrateFileUrls(conversationId)
  let index = run.assistantMessageId != null
    ? messages.value.findIndex(message => String(message.id) === String(run.assistantMessageId))
    : -1
  if (index < 0) index = messages.value.findIndex(message => !message.isUser && message.runId === run.id)
  activeAnswerIndex.value = index
  activeRun.value = { ...activeRun.value, messageIndex: index }
  await refreshWorkspaceFiles(conversationId)
  if (run.status === 'COMPLETED') settleCompanion('done', '任务完成')
  else settleCompanion('error', run.status === 'INTERRUPTED' ? '任务已中断' : '任务执行失败', 3200)
  if (index < 0) setTimeout(() => loadRunsForConversation(conversationId), 1200)
}
const refreshActiveRun = async () => {
  const run = activeRun.value
  const conversationId = chatId.value
  if (!run?.id || !conversationId || refreshingRun.value) return
  refreshingRun.value = true
  try {
    const detail = await getManusRun(conversationId, run.id)
    if (chatId.value !== conversationId || activeRun.value?.id !== run.id) return
    const messageIndex = activeAnswerIndex.value >= 0
      ? activeAnswerIndex.value
      : messages.value.findIndex(message => detail.assistantMessageId != null && String(message.id) === String(detail.assistantMessageId))
    const previousStatus = activeRun.value.status
    const updated = normalizeRun(detail, messageIndex)
    if (run.webSearch !== undefined) updated.webSearch = run.webSearch
    if ((detail.events?.length || 0) < (run.events?.length || 0)) updated.events = run.events
    if ((detail.taskResults?.length || 0) < (run.taskResults?.length || 0)) updated.taskResults = run.taskResults
    if ((detail.plan?.length || 0) < (run.plan?.length || 0)) updated.plan = run.plan
    if (!detail.verification?.status && run.verification?.status) updated.verification = run.verification
    if (!updated.tokenUsage && run.tokenUsage) updated.tokenUsage = run.tokenUsage
    activeRun.value = updated
    if (previousStatus === 'RUNNING' && ['COMPLETED', 'INTERRUPTED', 'FAILED'].includes(updated.status)) {
      await recoverFinishedRun(conversationId, updated)
    }
  } catch (error) {
    console.error('Unable to refresh run workspace:', error)
  } finally {
    refreshingRun.value = false
  }
}
const scheduleRunPolling = () => {
  stopRunPolling()
  if (activeRun.value?.id && activeRun.value.status === 'RUNNING') {
    runPollTimer = setInterval(() => { refreshActiveRun() }, 5000)
  }
}
watch(() => `${activeRun.value?.id || ''}|${activeRun.value?.status || ''}`, scheduleRunPolling)

const createConversation = async () => {
  if (connectionStatus.value === 'connecting') return
  const conversation = await createManusConversation(selectedProjectId.value)
  await refreshConversations()
  chatId.value = conversation.id
  messages.value = []
  activeRun.value = null
  activeAnswerIndex.value = -1
  workspaceFiles.value = []
  sidebarOpen.value = false
  setCompanion('idle')
}
// 从工作区分组的新建按钮进入：先绑定工作区，再开新任务
const createConversationIn = async projectId => {
  if (connectionStatus.value === 'connecting') return
  selectedProjectId.value = projectId || null
  await createConversation()
}
const createWorkspace = async payload => {
  if (workspaceCreating.value) return
  workspaceCreating.value = true
  workspaceError.value = ''
  try {
    const project = await createProject(payload)
    await refreshProjects()
    selectedProjectId.value = project.id
    await createConversation()
  } catch (error) {
    workspaceError.value = error.response?.data?.error || error.message || '工作区创建失败'
  } finally {
    workspaceCreating.value = false
  }
}
const browseWorkspaces = async path => {
  directoryLoading.value = true
  workspaceError.value = ''
  try { directoryView.value = await browseProjectDirectories(path) }
  catch (error) { workspaceError.value = error.response?.data?.error || error.message || '文件夹读取失败' }
  finally { directoryLoading.value = false }
}
const chooseWorkspace = async path => {
  if (workspaceCreating.value) return
  workspaceCreating.value = true
  workspaceError.value = ''
  try {
    const project = await selectProjectDirectory({ path })
    await refreshProjects()
    selectedProjectId.value = project.id
    await createConversation()
  } catch (error) {
    workspaceError.value = error.response?.data?.error || error.message || '工作区选择失败'
  } finally { workspaceCreating.value = false }
}
const selectConversation = async conversation => {
  if (connectionStatus.value === 'connecting') return
  if (conversation.id === chatId.value) {
    selectedProjectId.value = conversation.projectId || null
    sidebarOpen.value = false
    return
  }
  messagesLoading.value = true
  chatId.value = conversation.id
  selectedProjectId.value = conversation.projectId || null
  sidebarOpen.value = false
  setCompanion('idle')
  activeRun.value = null
  activeAnswerIndex.value = -1
  workspaceFiles.value = []
  try {
    messages.value = (await getManusConversationMessages(conversation.id)).map(mapMessage)
    hydrateFileUrls(conversation.id)
    await loadRunsForConversation(conversation.id)
    await refreshWorkspaceFiles(conversation.id)
  } finally {
    messagesLoading.value = false
  }
}
const deleteMessage = computed(() => pendingDelete.value
  ? `删除“${pendingDelete.value.title}”及其全部消息吗？删除后无法恢复。`
  : '')
const requestDelete = conversation => {
  if (connectionStatus.value === 'connecting') return
  pendingDelete.value = conversation
}
const confirmDelete = async () => {
  const conversation = pendingDelete.value
  pendingDelete.value = null
  if (!conversation) return
  await deleteManusConversation(conversation.id)
  const wasActive = chatId.value === conversation.id
  await refreshConversations()
  if (wasActive) conversations.value.length ? await selectConversation(conversations.value[0]) : await createConversation()
}
const sendMessage = async payload => {
  if (!chatId.value || connectionStatus.value === 'connecting') return
  const message = typeof payload === 'string' ? payload : payload.message
  const knowledgeSearch = typeof payload === 'string' ? false : payload.knowledgeSearch === true
  const webSearch = typeof payload === 'string' ? false : payload.webSearch === true
  const files = typeof payload === 'string' ? [] : payload.files || []
  const permissionMode = typeof payload === 'string' ? null : payload.permissionMode || null
  const imageObjectKeys = []
  if (files.length) {
    setCompanion('working', files.length > 1 ? `正在上传 ${files.length} 张图片` : '正在上传图片')
    try {
      for (const file of files) {
        const uploaded = await uploadManusImage(chatId.value, file)
        if (uploaded?.objectKey) imageObjectKeys.push(uploaded.objectKey)
      }
    } catch (error) {
      console.error('Unable to upload image:', error)
      settleCompanion('error', '图片上传失败，请重试', 3200)
      addMessage('图片上传失败，请检查文件格式和大小后重试。', false)
      return
    }
  }
  cancelActiveStream()
  activeTurnStart = messages.value.length
  addMessage(message, true, imageObjectKeys)
  messages.value.push({ content: '', isUser: false, time: Date.now(), activities: [], startedAt: Date.now() })
  const answerIndex = messages.value.length - 1
  activeAnswerIndex.value = answerIndex
  activeRun.value = normalizeRun({ status: 'RUNNING', canCancel: true, input: message, createdAt: new Date().toISOString() }, answerIndex)
  activeRun.value.webSearch = webSearch
  activeRun.value.permissionMode = permissionMode || defaultPermissionMode.value
  workspaceFiles.value = []
  const requestId = crypto.randomUUID ? crypto.randomUUID() : `req-${Date.now()}-${Math.random().toString(36).slice(2)}`
  connectionStatus.value = 'connecting'
  reconnectAttempts = 0
  const thinkingSteps = [knowledgeSearch ? '检索知识库' : '', webSearch ? '联网搜索' : ''].filter(Boolean).join('与')
  setCompanion('thinking', thinkingSteps ? `正在${thinkingSteps}并规划任务` : '正在规划任务')
  eventSource = chatWithManus(chatId.value, message, knowledgeSearch, webSearch, requestId, imageObjectKeys, permissionMode)
  attachManusStream(eventSource, answerIndex)
}
const attachManusStream = (stream, answerIndex) => {
  stream.onopen = () => { reconnectAttempts = 0 }
  stream.addEventListener('run', event => {
    const answer = messages.value[answerIndex]
    if (answer) answer.runId = event.data
    if (activeRun.value) {
      activeRun.value.id = event.data
      activeRun.value.canCancel = true
    }
    if (!answer?.content) setCompanion('thinking', '任务已创建，正在规划步骤')
  })
  stream.addEventListener('plan', event => {
    try {
      const plan = JSON.parse(event.data)
      if (Array.isArray(plan) && activeRun.value) {
        activeRun.value.plan = plan
        if (plan.length) activeRun.value.status = 'RUNNING'
      }
    } catch (error) {
      console.error('Unable to parse run plan:', error)
    }
  })
  stream.addEventListener('plan-proposed', event => {
    try {
      const plan = JSON.parse(event.data)
      if (Array.isArray(plan) && activeRun.value) {
        activeRun.value.plan = plan
        activeRun.value.awaitingPlan = plan.length > 0
        activeRun.value.status = 'INTERRUPTED'
        activeRun.value.canCancel = false
        activeRun.value.canResume = true
      }
      setCompanion('thinking', '计划已生成，等待你确认后执行')
    } catch (error) {
      console.error('Unable to parse plan proposal:', error)
    }
  })
  stream.addEventListener('rag-trace', event => {
    try { messages.value[answerIndex].ragTrace = JSON.parse(event.data) }
    catch (error) { console.error('RAG retrieval trace unavailable:', error) }
    if (!messages.value[answerIndex]?.content) setCompanion('working', '已检索知识库，正在整理依据')
  })
  stream.addEventListener('usage', event => {
    try {
      if (activeRun.value) activeRun.value.tokenUsage = parseTokenUsage(event.data)
    } catch (error) {
      console.error('Unable to parse run token usage:', error)
    }
  })
  stream.addEventListener('activity', event => {
    const answer = messages.value[answerIndex]
    if (!answer) return
    const activity = parseActivity(event.data || '')
    answer.activities.push({ phase: activity.phase, agent: activity.agent, summary: activity.summary, detail: activity.detail, at: Date.now() })
    if (activeRun.value) activeRun.value.events.push({ phase: activity.phase, agent: activity.agent, summary: activity.summary })
    if (!answer.content) setCompanion('working', activity.summary)
  })
  stream.addEventListener('interrupted', () => {
    const answer = messages.value[answerIndex]
    if (answer) answer.status = 'INTERRUPTED'
    if (activeRun.value) {
      activeRun.value.status = 'INTERRUPTED'
      activeRun.value.canCancel = false
      activeRun.value.canResume = true
    }
    settleCompanion('idle', '任务已中断', 1800)
  })
  stream.addEventListener('confirmation-required', event => {
    try {
      const payload = JSON.parse(event.data)
      if (activeRun.value) {
        activeRun.value.pendingConfirmations = payload.confirmations || []
        activeRun.value.canDecideConfirmation = true
        if (payload.runId) activeRun.value.id = payload.runId
        activeRun.value.status = 'INTERRUPTED'
        activeRun.value.canCancel = false
        activeRun.value.canResume = true
      }
      const answer = messages.value[answerIndex]
      if (answer) answer.status = 'INTERRUPTED'
      confirmationDialogOpen.value = true
      settleCompanion('idle', '任务已暂停：等待工具确认', 2600)
    } catch (error) {
      console.error('Unable to parse tool confirmation request:', error)
    }
  })
  stream.onmessage = event => {
    if (event.data === '[DONE]') { finishStream(); return }
    if (event.data && messages.value[answerIndex]) {
      if (!messages.value[answerIndex].content) setCompanion('speaking', '正在组织回答')
      messages.value[answerIndex].content += event.data
    }
  }
  stream.onerror = error => handleManusStreamError(answerIndex, error)
}
const handleManusStreamError = (answerIndex, error) => {
  const answer = messages.value[answerIndex]
  // 配额拒绝（429）：不重连，直接把服务端原因展示给用户
  if (error?.status === 429) {
    eventSource?.close()
    eventSource = null
    activeTurnStart = -1
    connectionStatus.value = 'error'
    const reason = error.message || '触发用量配额限制，请稍后再试'
    if (answer && !answer.content) answer.content = reason
    if (answer) answer.status = 'FAILED'
    sealActiveAnswer()
    if (activeRun.value) {
      activeRun.value.status = 'FAILED'
      activeRun.value.canCancel = false
      activeRun.value.canResume = false
    }
    settleCompanion('error', reason, 4000)
    return
  }
  const lastEventId = eventSource?.lastEventId || null
  eventSource?.close()
  eventSource = null
  if (answer?.runId && reconnectAttempts < 3) {
    reconnectAttempts += 1
    connectionStatus.value = 'connecting'
    const delay = 400 * reconnectAttempts
    setTimeout(() => {
      if (!chatId.value || !messages.value[answerIndex]) return
      eventSource = reconnectManusRun(chatId.value, answer.runId, lastEventId)
      attachManusStream(eventSource, answerIndex)
    }, delay)
    return
  }
  activeTurnStart = -1
  connectionStatus.value = 'error'
  setCompanion('error', '连接异常，正在恢复历史记录')
  if (activeRun.value) {
    activeRun.value.status = 'FAILED'
    activeRun.value.canCancel = false
    activeRun.value.canResume = !!answer?.runId
  }
  if (answer && !answer.content) answer.content = '连接异常，正在恢复历史消息...'
  if (answer) answer.status = 'FAILED'
  setTimeout(() => { finishStream().catch(error => console.error(error)) }, 1500)
}
const finishStream = async () => {
  if (companionState.value === 'error') settleCompanion('error', '连接异常，已恢复历史记录', 3200)
  else settleCompanion('done', '任务完成')
  if (activeRun.value) {
    if (!['FAILED', 'INTERRUPTED'].includes(activeRun.value.status)) activeRun.value.status = 'COMPLETED'
    activeRun.value.canCancel = false
    activeRun.value.canResume = false
  }
  connectionStatus.value = 'disconnected'
  eventSource?.close()
  eventSource = null
  activeTurnStart = -1
  reconnectAttempts = 0
  sealActiveAnswer()
  const liveTrace = captureLiveTrace()
  const conversationId = chatId.value
  await refreshConversations()
  if (!conversationId || chatId.value !== conversationId) return
  const history = await getManusConversationMessages(conversationId)
  if (chatId.value !== conversationId) return
  messages.value = history.map(mapMessage)
  restoreLiveTrace(liveTrace)
  hydrateFileUrls(conversationId)
  await loadRunsForConversation(conversationId)
  await refreshWorkspaceFiles(conversationId)
}
const cancelActiveStream = () => {
  eventSource?.close()
  eventSource = null
  if (activeTurnStart >= 0) messages.value.splice(activeTurnStart)
  activeTurnStart = -1
  reconnectAttempts = 0
  connectionStatus.value = 'disconnected'
  setCompanion('idle')
}
const stopGeneration = async () => {
  const answer = messages.value[messages.value.length - 1]
  const runId = answer?.runId || activeRun.value?.id
  eventSource?.close()
  eventSource = null
  activeTurnStart = -1
  reconnectAttempts = 0
  connectionStatus.value = 'disconnected'
  settleCompanion('idle', '已停止生成', 2200)
  if (answer) {
    answer.status = 'INTERRUPTED'
    if (!answer.content) answer.content = '已停止生成。'
  }
  sealActiveAnswer()
  const liveTrace = captureLiveTrace()
  if (activeRun.value) {
    activeRun.value.status = 'INTERRUPTED'
    activeRun.value.canCancel = false
    activeRun.value.canResume = !!runId
  }
  if (runId && chatId.value) {
    try { await cancelManusRun(chatId.value, runId) } catch (error) { console.error('Unable to cancel manus run:', error) }
  }
  setTimeout(async () => {
    if (!chatId.value) return
    try {
      messages.value = (await getManusConversationMessages(chatId.value)).map(mapMessage)
      restoreLiveTrace(liveTrace)
      hydrateFileUrls(chatId.value)
      await loadRunsForConversation(chatId.value)
      await refreshWorkspaceFiles(chatId.value)
    } catch (error) { console.error('Unable to reload stopped run:', error) }
  }, 900)
}
const continueMessage = ({ message, index }) => {
  if (!chatId.value || connectionStatus.value === 'connecting') return
  if (!message?.runId) {
    sendMessage('请继续完成上一次未完成的回答。')
    return
  }
  const answer = messages.value[index]
  if (!answer) return
  const previousContent = answer.content
  answer.content = ''
  answer.activities = []
  answer.status = null
  answer.ragTrace = null
  answer.startedAt = Date.now()
  answer.finishedAt = null
  connectionStatus.value = 'connecting'
  reconnectAttempts = 0
  if (activeRun.value) {
    activeRun.value.status = 'RUNNING'
    activeRun.value.canCancel = true
    activeRun.value.canResume = false
  }
  setCompanion('thinking', '正在继续未完成的任务')
  eventSource = resumeManusRun(chatId.value, message.runId, activeRun.value?.webSearch !== false)
  const answerIndex = messages.value.indexOf(answer)
  const originalError = eventSource.onerror
  attachManusStream(eventSource, answerIndex >= 0 ? answerIndex : index)
  eventSource.onerror = error => {
    if (!answer.content) answer.content = previousContent
    if (originalError) originalError(error)
  }
}
const resumeActiveRun = () => {
  const run = activeRun.value
  if (!run?.id || connectionStatus.value === 'connecting') return
  let index = run.messageIndex
  if (index < 0) index = messages.value.findIndex(message => message.runId === run.id)
  const message = messages.value[index]
  if (!message) return
  if (!message.runId) message.runId = run.id
  activeAnswerIndex.value = index
  continueMessage({ message, index })
}
const confirmationDialogMessage = computed(() => {
  const items = activeRun.value?.pendingConfirmations || []
  if (!items.length) return '智能体请求执行有副作用的操作，等待你的确认。'
  return '智能体请求执行以下有副作用的操作：'
    + items.map(item => ` ${item.tool}${item.argumentsSummary ? `（${item.argumentsSummary}）` : ''}`).join('；')
    + '。允许后任务将从原计划继续执行；拒绝后智能体会改用其他方式或说明受限。'
})
const decidePendingConfirmation = async approved => {
  const run = activeRun.value
  const runId = run?.id
  if (!runId || !chatId.value || decidingConfirmation.value) return
  decidingConfirmation.value = true
  try {
    await decideManusConfirmation(chatId.value, runId, null, approved ? 'APPROVED' : 'DENIED')
    confirmationDialogOpen.value = false
    if (run) {
      run.pendingConfirmations = []
      run.canDecideConfirmation = false
    }
    if (approved) {
      if (run) {
        run.status = 'RUNNING'
        run.canCancel = true
        run.canResume = false
      }
      setCompanion('thinking', '已确认，正在继续执行任务')
      resumeActiveRun()
    } else {
      settleCompanion('idle', '已拒绝该工具，任务保持暂停', 2400)
    }
  } catch (error) {
    console.error('Unable to record confirmation decision:', error)
    confirmationDialogOpen.value = false
  } finally {
    decidingConfirmation.value = false
  }
}
const useTemplate = prompt => composerRef.value?.submit(prompt)
const resendMessage = ({ content }) => sendMessage(content)
// 任务结果导出：把计划、事件、回答、引用与联网参考组装为 Markdown 下载（纯客户端，不落服务端）
const exportRun = async () => {
  const run = activeRun.value
  if (!run?.id || !chatId.value) return
  try {
    const [detail, webSources] = await Promise.all([
      getManusRun(chatId.value, run.id),
      listManusWebSources(chatId.value, run.id).catch(() => [])
    ])
    const lines = []
    lines.push(`# 任务结果：${(detail.input || '').slice(0, 80)}`)
    lines.push('')
    lines.push(`- Run ID：${detail.id}`)
    lines.push(`- 状态：${detail.status} · 验收：${detail.verification?.status || '—'}`)
    lines.push(`- 时间：${detail.createdAt || '—'} ~ ${detail.updatedAt || '—'}`)
    if (detail.plan?.length) {
      lines.push('')
      lines.push('## 计划')
      detail.plan.forEach(step => lines.push(`- [${step.roleLabel || step.role}] ${step.title}`))
    }
    if (detail.events?.length) {
      lines.push('')
      lines.push('## 执行过程')
      detail.events.forEach(event => lines.push(`- (${event.phase || ''}/${event.agent || ''}) ${event.summary || ''}`))
    }
    lines.push('')
    lines.push('## 最终回答')
    lines.push(activeAnswer.value || '（无回答）')
    const ragSources = (activeRagTrace.value?.candidates || []).filter(candidate => candidate.selected)
    if (ragSources.length) {
      lines.push('')
      lines.push('## 知识库引用')
      ragSources.forEach(source => lines.push(`- ${source.filename || ''}#${source.chunkIndex ?? ''}`))
    }
    if (webSources.length) {
      lines.push('')
      lines.push('## 联网参考')
      webSources.forEach(source => lines.push(`- [${source.title || source.url}](${source.url})`))
    }
    const blob = new Blob([lines.join('\n')], { type: 'text/markdown;charset=utf-8' })
    const url = URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.download = `aster-run-${(run.id || 'result').slice(0, 8)}.md`
    link.click()
    URL.revokeObjectURL(url)
  } catch (error) {
    console.error('Unable to export run:', error)
  }
}
const onGlobalShortcut = event => {
  if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === 'b') {
    event.preventDefault()
    toggleSidebar()
  } else if (event.key === 'Escape' && sidebarOpen.value) {
    sidebarOpen.value = false
  }
}
onMounted(async () => {
  narrowMedia.addEventListener('change', onNarrowChange)
  window.addEventListener('keydown', onGlobalShortcut)
  await Promise.all([refreshProjects(), refreshConversations()])
  // 权限模式默认值：读取用户设置；失败时保持 EDIT，不阻塞工作台。
  getPermissionSettings()
    .then(settings => { if (settings?.mode) defaultPermissionMode.value = settings.mode })
    .catch(error => console.error('Unable to load permission settings:', error))
  // run 深链：/super-agent?conversationId=…&runId=…（首页任务流/分享入口）
  const queryRunId = typeof route.query.runId === 'string' ? route.query.runId : ''
  const queryConversationId = typeof route.query.conversationId === 'string' ? route.query.conversationId : ''
  const linkedConversation = queryConversationId
    ? conversations.value.find(conversation => conversation.id === queryConversationId) : null
  if (linkedConversation) {
    await selectConversation(linkedConversation)
    if (queryRunId) await loadRunsForConversation(queryConversationId, queryRunId)
  } else if (conversations.value.length) await selectConversation(conversations.value[0])
  else await createConversation()
  if (!messages.value.length && !activeRun.value) {
    setCompanion('listening', '你好，我是星枢，有什么可以帮你？')
    companionIdleTimer = setTimeout(() => {
      companionState.value = 'idle'
      companionCaption.value = ''
      companionIdleTimer = null
    }, 4200)
  }
})
onBeforeUnmount(() => {
  narrowMedia.removeEventListener('change', onNarrowChange)
  window.removeEventListener('keydown', onGlobalShortcut)
  stopRunPolling(); clearCompanionIdle(); cancelActiveStream()
})
</script>

<style scoped>
.super-layout { display: flex; height: 100%; overflow: hidden; background: var(--wb-bg); background-image: var(--wb-bg-accent); }
.super-main { position: relative; display: flex; min-width: 0; min-height: 0; flex: 1; flex-direction: column; overflow: hidden; }

.sidebar-scrim { display: none; }

.super-header {
  display: flex;
  height: 60px;
  flex: 0 0 60px;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 0 18px 0 12px;
  background: transparent;
}

.header-start { display: flex; min-width: 0; align-items: center; gap: 6px; }

.header-icon {
  display: grid;
  width: 30px;
  height: 30px;
  flex: 0 0 30px;
  place-items: center;
  border: 0;
  border-radius: var(--wb-radius-sm);
  background: transparent;
  color: var(--wb-muted);
  cursor: pointer;
}

.header-icon:hover { background: var(--wb-fill); color: var(--wb-text); }

.header-title { display: flex; min-width: 0; flex-direction: column; gap: 1px; }
.header-title strong { overflow: hidden; max-width: 46vw; color: var(--wb-text); font-size: 13.5px; font-weight: 650; letter-spacing: -0.01em; text-overflow: ellipsis; white-space: nowrap; }
.header-sub { color: var(--wb-muted); font-size: 10.5px; }

.header-end { display: flex; flex: 0 0 auto; align-items: center; gap: 12px; }

.header-mcp {
  display: flex;
  height: 28px;
  align-items: center;
  gap: 5px;
  border: 1px solid var(--wb-border);
  border-radius: 999px;
  padding: 0 10px;
  background: var(--wb-surface);
  color: var(--wb-text-2);
  font-size: 11.5px;
  font-weight: 600;
  cursor: pointer;
}

.header-mcp:hover { border-color: var(--wb-brand); color: var(--wb-brand); }

.status { display: flex; align-items: center; gap: 6px; color: var(--wb-success); font-size: 12px; font-weight: 550; }
.status::before { content: ""; width: 7px; height: 7px; border-radius: 50%; background: currentColor; }
.status.connecting { color: var(--wb-brand); }
.status.connecting::before { animation: status-blink 1.05s ease-in-out infinite; }
.status.error { color: var(--wb-danger); }

.workspace-main {
  display: grid;
  min-height: 0;
  flex: 1;
  grid-template-columns: minmax(0, 1fr) 272px;
  gap: 18px;
  overflow: hidden;
  padding: 18px 20px;
  transition: grid-template-columns 0.22s ease;
}

.workspace-main.inspector-collapsed { grid-template-columns: minmax(0, 1fr) 44px; }
.workspace-main.no-inspector { grid-template-columns: minmax(0, 1fr); }

.workspace-column {
  position: relative;
  display: flex;
  min-width: 0;
  min-height: 0;
  flex-direction: column;
}

.workspace-canvas {
  position: relative;
  display: flex;
  min-width: 0;
  min-height: 0;
  flex: 1;
  flex-direction: column;
  overflow: hidden;
  border: 1px solid var(--wb-border);
  border-radius: var(--wb-radius-lg);
  padding: 0;
  background: var(--wb-surface);
  box-shadow: var(--wb-shadow-md);
}

/* 主画布即会话：提问与回答都在对话流里，不再挂头部标题 */
.canvas-conversation { flex: 1; min-height: 0; }
.canvas-conversation > :deep(.chat-container) { background: transparent; }
.canvas-conversation > :deep(.chat-messages) { padding-top: 18px; }

.canvas-actions {
  position: absolute;
  z-index: 5;
  top: 12px;
  right: 16px;
  display: flex;
  gap: 6px;
}

.canvas-actions button {
  display: grid;
  width: 30px;
  height: 30px;
  place-items: center;
  border: 1px solid var(--wb-border);
  border-radius: var(--wb-radius-sm);
  background: var(--wb-surface);
  color: var(--wb-text-2);
  cursor: pointer;
}

.canvas-actions button:hover:not(:disabled) { border-color: var(--wb-brand); color: var(--wb-brand); }
.canvas-actions button:disabled { opacity: 0.6; cursor: default; }
.canvas-actions .spin { animation: canvas-spin 0.9s linear infinite; }
@keyframes canvas-spin { to { transform: rotate(360deg); } }
.canvas-actions.with-banner { top: 62px; }

.workspace-composer-shell {
  flex: 0 0 auto;
  padding: 12px 0 0;
}

.inspector-rail {
  display: flex;
  align-self: stretch;
  flex-direction: column;
  align-items: center;
  justify-content: flex-start;
  gap: 10px;
  border: 1px solid var(--wb-border);
  border-radius: var(--wb-radius);
  padding: 14px 0 12px;
  background: var(--wb-surface);
  color: var(--wb-text-2);
  cursor: pointer;
  transition: border-color 0.16s ease, color 0.16s ease, background 0.16s ease;
}

.inspector-rail:hover { border-color: var(--wb-brand); background: var(--wb-surface-2); color: var(--wb-brand); }
.inspector-rail span { writing-mode: vertical-rl; font-size: 10.5px; font-weight: 600; letter-spacing: 0.16em; }

.workspace-empty {
  display: flex;
  min-height: 100%;
  flex: 1;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 24px 16px 40px;
  text-align: center;
}

.empty-avatar { display: block; width: 72px; height: 72px; border-radius: 20px; box-shadow: 0 14px 30px rgba(37, 99, 235, 0.2); }
.empty-kicker { margin-top: 16px; color: var(--wb-muted); font-size: 10.5px; font-weight: 700; letter-spacing: 0.14em; text-transform: uppercase; }
.workspace-empty h1 { margin: 9px 0 8px; color: var(--wb-text); font-size: clamp(24px, 3vw, 34px); font-weight: 800; line-height: 1.25; letter-spacing: -0.03em; }
.workspace-empty p { max-width: 600px; margin: 0; color: var(--wb-text-2); font-size: 13.5px; line-height: 1.7; }

.template-grid { display: grid; width: min(100%, 720px); grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 10px; margin-top: 28px; }

.template-card {
  display: flex;
  min-width: 0;
  flex-direction: column;
  align-items: flex-start;
  gap: 5px;
  border: 1px solid var(--wb-border);
  border-radius: var(--wb-radius);
  padding: 13px 13px 14px;
  background: var(--wb-surface);
  color: var(--wb-text);
  text-align: left;
  cursor: pointer;
  transition: transform 0.18s ease, border-color 0.18s ease, box-shadow 0.18s ease;
}

.template-card:hover { border-color: var(--wb-brand); box-shadow: var(--wb-shadow-md); transform: translateY(-2px); }
.template-icon { display: grid; width: 30px; height: 30px; place-items: center; margin-bottom: 3px; border-radius: var(--wb-radius-sm); background: var(--wb-brand-soft); color: var(--wb-brand); }
.template-card strong { font-size: 12.5px; font-weight: 700; }
.template-card > span:last-child { color: var(--wb-text-2); font-size: 10.5px; line-height: 1.5; }

.confirmation-banner {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin: 12px 16px 0;
  padding: 10px 14px;
  border: 1px solid color-mix(in srgb, var(--wb-warning) 38%, transparent);
  border-radius: var(--wb-radius);
  background: color-mix(in srgb, var(--wb-warning) 12%, var(--wb-surface));
  font-size: 12px;
  color: var(--wb-warning);
}
.confirmation-text strong { font-weight: 700; }
.confirmation-mode {
  flex-shrink: 0;
  border-radius: 999px;
  padding: 2px 8px;
  background: var(--wb-surface);
  color: var(--wb-warning);
  font-size: 10.5px;
  font-weight: 600;
}
.confirmation-action {
  flex-shrink: 0;
  border: 1px solid color-mix(in srgb, var(--wb-warning) 55%, transparent);
  border-radius: 999px;
  padding: 5px 12px;
  background: var(--wb-surface);
  color: var(--wb-warning);
  font-size: 11.5px;
  font-weight: 600;
  cursor: pointer;
}
.confirmation-action:hover { background: var(--wb-fill); }

.plan-gate-banner {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin: 12px 16px 0;
  padding: 10px 14px;
  border: 1px solid var(--wb-brand);
  border-radius: var(--wb-radius);
  background: var(--wb-brand-soft);
  font-size: 12px;
  color: var(--wb-brand);
}

.plan-gate-actions { display: flex; flex: 0 0 auto; gap: 7px; }

.plan-gate-banner button { border-radius: 999px; padding: 5px 13px; font-size: 11.5px; font-weight: 600; cursor: pointer; }
.plan-gate-primary { border: 0; background: var(--wb-brand); color: var(--wb-brand-contrast); }
.plan-gate-primary:hover { filter: brightness(1.05); }
.plan-gate-secondary { border: 1px solid var(--wb-brand); background: var(--wb-surface); color: var(--wb-brand); }
.plan-gate-secondary:hover { background: var(--wb-fill); }

.workspace-loading {
  position: absolute;
  z-index: 3;
  inset: 0;
  display: grid;
  place-items: center;
  border-radius: var(--wb-radius-lg);
  background: color-mix(in srgb, var(--wb-surface) 84%, transparent);
  backdrop-filter: blur(6px);
  -webkit-backdrop-filter: blur(6px);
  color: var(--wb-text-2);
  font-size: 13px;
}

@keyframes status-blink { 0%, 70%, 100% { opacity: 0.3; } 35% { opacity: 1; } }

@media (prefers-reduced-motion: reduce) {
  .status.connecting::before { animation: none; opacity: 0.5; }
  .workspace-main { transition: none; }
  .template-card:hover { transform: none; }
}

@media (max-width: 1180px) {
  .workspace-main,
  .workspace-main.inspector-collapsed { grid-template-columns: minmax(0, 1fr); }
  .workspace-main :deep(.run-inspector), .inspector-rail { display: none; }
}

@media (max-width: 900px) {
  .template-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
}

@media (max-width: 720px) {
  .sidebar-scrim { position: fixed; z-index: 35; inset: 0; display: block; background: rgba(15, 17, 26, 0.36); }
  .super-header { height: 56px; flex-basis: 56px; padding: 0 12px; }
  .header-title strong { max-width: 44vw; }
  .workspace-main { gap: 0; padding: 0; }
  .workspace-canvas { border: 0; border-radius: 0; padding: 18px 14px 24px; box-shadow: none; }
  .workspace-composer-shell { padding: 9px 10px 12px; }
  .workspace-empty { padding: 20px 6px 30px; }
  .empty-avatar { width: 62px; height: 62px; border-radius: 17px; }
  .template-grid { grid-template-columns: 1fr; }
}
</style>
