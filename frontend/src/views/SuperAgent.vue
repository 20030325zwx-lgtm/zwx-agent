<template>
  <div class="super-layout">
    <ConversationSidebar title="超级智能体" mark="✦" theme="travel" :conversations="conversations" :active-id="chatId" :loading="historyLoading" :open="sidebarOpen" @create="createConversation" @select="selectConversation" @delete="requestDelete" @close="sidebarOpen = false" />
    <main class="super-main">
      <header class="super-header">
        <div class="header-start">
          <button class="history-toggle" type="button" aria-label="打开历史会话" @click="sidebarOpen = true">☰</button>
          <HomeButton />
          <button class="back-button" type="button" @click="router.push('/mcp-settings')">MCP 管理</button>
        </div>
        <div class="title">
          <span class="super-mark" aria-hidden="true"><AsterAvatar /></span>
          <strong>超级智能体</strong>
          <em class="kind-pill">Agent</em>
        </div>
        <div class="header-end">
          <span class="status" :class="connectionStatus">{{ statusText }}</span>
        </div>
      </header>

      <div class="workspace-main" :class="{ 'inspector-collapsed': !inspectorOpen, 'no-inspector': !activeRun }">
        <div class="workspace-column">
          <section class="workspace-canvas" aria-label="任务工作台">
          <template v-if="activeRun">
            <RunHeader :run="activeRun" :goal="activeGoal" :has-conversation="messages.length > 0" :refreshing="refreshingRun"
              @resume="resumeActiveRun" @cancel="stopGeneration" @open-conversation="conversationOpen = true"
              @refresh="refreshActiveRun" @export="exportRun" />
            <div v-if="activeRun.canDecideConfirmation && activeRun.pendingConfirmations?.length"
              class="confirmation-banner" role="status">
              <span class="confirmation-text">
                ⏸ 任务已暂停：等待确认
                <strong>{{ activeRun.pendingConfirmations.map(item => item.tool).join('、') }}</strong>
              </span>
              <button type="button" class="confirmation-action" @click="confirmationDialogOpen = true">查看并确认</button>
            </div>
            <RunPhases :run="activeRun" :answer="activeAnswer" :simple="simpleMode" />
            <div v-if="!simpleMode" class="workspace-section">
              <PlanBoard :run="activeRun" />
            </div>
            <div class="workspace-section">
              <RunTimeline :run="activeRun" />
            </div>
            <div class="workspace-section">
              <DeliverableView :answer="activeAnswer" :files="activeMessageFiles" :rag-trace="activeRagTrace"
                :verification="activeRun.verification" :status="activeRun.status" :can-resume="activeRun.canResume"
                agent-key="super" @open-conversation="conversationOpen = true" @resume="resumeActiveRun" />
            </div>
            <button v-if="messages.length" type="button" class="conversation-toggle" :aria-expanded="conversationOpen" @click="conversationOpen = !conversationOpen">
              <MessageSquare :size="14" />
              <span>会话记录 · {{ messages.length }} 条</span>
              <ChevronDown :size="14" :class="{ rotated: conversationOpen }" />
            </button>
            <div v-if="conversationOpen && messages.length" ref="conversationDrawerRef" class="conversation-drawer">
              <ChatRoom :messages="messages" :connection-status="connectionStatus" ai-type="super" hide-input
                knowledge-search-available attachments-enabled attachment-accept="image/jpeg,image/png,image/gif,image/webp"
                @edit-message="cancelActiveStream" @resend-message="resendMessage"
                @continue-message="continueMessage" @stop-generation="stopGeneration" />
            </div>
          </template>

          <template v-else-if="messages.length">
            <div class="legacy-chat">
              <span class="legacy-kicker">历史对话 · 该轮没有可回放的运行记录</span>
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
              :placeholder="activeRun ? '继续补充要求，或描述下一步…' : '描述目标、约束或需要交付的结果…'"
              @send-message="sendMessage" @stop-generation="stopGeneration" />
          </footer>
        </div>

        <RunInspector v-if="activeRun && inspectorOpen" :sources="activeSources" :files="workspaceFiles" :run="activeRun"
          :conversation-id="chatId"
          @resume="resumeActiveRun" @cancel="stopGeneration" @open-conversation="conversationOpen = true"
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
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useHead } from '@vueuse/head'
import { BookOpen, ChevronDown, Database, Globe2, MessageSquare, PanelRightOpen, Workflow } from 'lucide-vue-next'
import AgentCompanion from '../components/AgentCompanion.vue'
import AsterAvatar from '../components/AsterAvatar.vue'
import ChatRoom from '../components/ChatRoom.vue'
import ConversationSidebar from '../components/ConversationSidebar.vue'
import ConfirmDialog from '../components/ConfirmDialog.vue'
import HomeButton from '../components/HomeButton.vue'
import DeliverableView from '../components/workspace/DeliverableView.vue'
import PlanBoard from '../components/workspace/PlanBoard.vue'
import RunHeader from '../components/workspace/RunHeader.vue'
import RunInspector from '../components/workspace/RunInspector.vue'
import RunPhases from '../components/workspace/RunPhases.vue'
import RunTimeline from '../components/workspace/RunTimeline.vue'
import TaskComposer from '../components/workspace/TaskComposer.vue'
import { chatWithManus, resumeManusRun, reconnectManusRun, cancelManusRun, createManusConversation, decideManusConfirmation, deleteManusConversation, getManusConversationMessages, getManusFileUrl, getManusImageUrl, getManusRun, listManusConversations, listManusRuns, listManusWebSources, listWorkspaceFiles, uploadManusImage } from '../api'

useHead({ title: '超级智能体 - Aster', meta: [{ name: 'description', content: 'Aster 的自主智能体，可规划任务、调用工具并多步执行。' }] })
const router = useRouter()
const route = useRoute()
const messages = ref([])
const chatId = ref('')
const conversations = ref([])
const connectionStatus = ref('disconnected')
const historyLoading = ref(false)
const messagesLoading = ref(false)
const sidebarOpen = ref(false)
const pendingDelete = ref(null)
const companionState = ref('idle')
const companionCaption = ref('')
const activeRun = ref(null)
const activeAnswerIndex = ref(-1)
const conversationOpen = ref(false)
const workspaceFiles = ref([])
const composerRef = ref(null)
const conversationDrawerRef = ref(null)
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
      return { label: event.agent ? `${event.agent} · ${event.summary}` : event.summary, detail: event.summary, phase: event.phase || 'work', agent: event.agent || '' }
    }
  } catch { /* 旧格式纯文本兜底 */ }
  return { label: activityLabel(data), detail: data, phase: 'work', agent: '' }
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
  collapsibleActivities: message.role === 'ASSISTANT',
  status: message.status,
  runId: message.executionRunId,
  files: mapFiles(chatId.value, message.fileAttachments),
  imageKeys: parseImageKeys(message.imageObjectKeys),
  imageUrls: [],
  ragTrace: parseRagTrace(message.ragRetrievalTrace)
})

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
const activeGoal = computed(() => {
  const run = activeRun.value
  if (!run) return ''
  if (run.messageIndex >= 0) {
    for (let index = run.messageIndex; index >= 0; index--) {
      if (messages.value[index]?.isUser) return messages.value[index].content
    }
  }
  if (run.input) return run.input
  const lastUser = [...messages.value].reverse().find(message => message.isUser)
  return lastUser?.content || ''
})
const simpleMode = computed(() => {
  const run = activeRun.value
  if (!run || run.status !== 'COMPLETED') return false
  if ((run.plan || []).length > 1) return false
  return !activeMessageFiles.value.length && !activeSources.value.length
})

const refreshConversations = async () => {
  historyLoading.value = true
  try { conversations.value = await listManusConversations() }
  finally { historyLoading.value = false }
}
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
    activeRun.value = normalizeRun(detail, index)
    activeAnswerIndex.value = index
    if (index >= 0) conversationOpen.value = false
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
  const history = await getManusConversationMessages(conversationId)
  if (chatId.value !== conversationId) return
  messages.value = history.map(mapMessage)
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
  const conversation = await createManusConversation()
  await refreshConversations()
  chatId.value = conversation.id
  messages.value = []
  activeRun.value = null
  activeAnswerIndex.value = -1
  conversationOpen.value = false
  workspaceFiles.value = []
  sidebarOpen.value = false
  setCompanion('idle')
}
const selectConversation = async conversation => {
  if (connectionStatus.value === 'connecting' || conversation.id === chatId.value) { sidebarOpen.value = false; return }
  messagesLoading.value = true
  chatId.value = conversation.id
  sidebarOpen.value = false
  setCompanion('idle')
  activeRun.value = null
  activeAnswerIndex.value = -1
  conversationOpen.value = false
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
  messages.value.push({ content: '', isUser: false, time: Date.now(), activities: [], collapsibleActivities: true })
  const answerIndex = messages.value.length - 1
  activeAnswerIndex.value = answerIndex
  activeRun.value = normalizeRun({ status: 'RUNNING', canCancel: true, input: message, createdAt: new Date().toISOString() }, answerIndex)
  activeRun.value.webSearch = webSearch
  conversationOpen.value = false
  workspaceFiles.value = []
  const requestId = crypto.randomUUID ? crypto.randomUUID() : `req-${Date.now()}-${Math.random().toString(36).slice(2)}`
  connectionStatus.value = 'connecting'
  reconnectAttempts = 0
  const thinkingSteps = [knowledgeSearch ? '检索知识库' : '', webSearch ? '联网搜索' : ''].filter(Boolean).join('与')
  setCompanion('thinking', thinkingSteps ? `正在${thinkingSteps}并规划任务` : '正在规划任务')
  eventSource = chatWithManus(chatId.value, message, knowledgeSearch, webSearch, requestId, imageObjectKeys)
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
    answer.activities.push({ label: activity.label, detail: activity.detail })
    if (activeRun.value) activeRun.value.events.push({ phase: activity.phase, agent: activity.agent, summary: activity.detail || activity.label })
    if (!answer.content) setCompanion('working', activity.label)
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
  const conversationId = chatId.value
  await refreshConversations()
  if (!conversationId || chatId.value !== conversationId) return
  const history = await getManusConversationMessages(conversationId)
  if (chatId.value !== conversationId) return
  messages.value = history.map(mapMessage)
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
watch(conversationOpen, open => {
  if (!open) return
  nextTick(() => conversationDrawerRef.value?.scrollIntoView({ behavior: 'smooth', block: 'start' }))
})
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
onMounted(async () => {
  await refreshConversations()
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
onBeforeUnmount(() => { stopRunPolling(); clearCompanionIdle(); cancelActiveStream() })
</script>

<style scoped>
.super-layout { display: flex; height: 100vh; overflow: hidden; background: linear-gradient(135deg, #e5efff, #f5f9ff 44%, #eaf3ff); }
.super-main { position: relative; display: flex; min-width: 0; min-height: 0; flex: 1; flex-direction: column; overflow: hidden; }

.super-header {
  display: flex;
  height: 66px;
  flex: 0 0 66px;
  align-items: center;
  justify-content: space-between;
  padding: 0 24px;
  border-bottom: 1px solid var(--sk-separator);
  background: rgba(255, 255, 255, 0.8);
  backdrop-filter: var(--sk-blur);
  -webkit-backdrop-filter: var(--sk-blur);
}

.header-start { display: flex; width: auto; min-width: 0; align-items: center; gap: 6px; }

.history-toggle {
  display: grid;
  width: 32px;
  height: 32px;
  place-items: center;
  border: 0;
  border-radius: 8px;
  background: transparent;
  color: var(--sk-label-2);
  font-size: 18px;
  cursor: pointer;
}

.history-toggle:hover { background: var(--sk-fill); color: var(--sk-label); }

.back-button { border: 0; background: transparent; color: var(--zwx-primary); font-size: 13px; cursor: pointer; }
.back-button:hover { opacity: 0.7; }

.title { display: flex; align-items: center; gap: 9px; font-size: 15px; }

.super-mark {
  display: grid;
  width: 30px;
  height: 30px;
  place-items: center;
  overflow: hidden;
  border-radius: 9px;
  background: linear-gradient(160deg, #3f9bff, var(--zwx-primary) 70%);
  color: #fff;
  font-size: 15px;
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.35), 0 3px 8px var(--zwx-primary-ring);
}

.title strong { font-weight: 700; letter-spacing: -0.01em; }

.kind-pill { border: 0; border-radius: 6px; padding: 3px 7px; background: var(--zwx-primary-soft); color: var(--zwx-primary); font-size: 10px; font-style: normal; font-weight: 700; }

.header-end { display: flex; width: auto; justify-content: flex-end; }

.status { display: flex; align-items: center; gap: 6px; color: #1f9d4d; font-size: 12px; font-weight: 550; }
.status::before { content: ""; width: 7px; height: 7px; border-radius: 50%; background: currentColor; }
.status.connecting { color: var(--zwx-primary); }
.status.connecting::before { animation: status-blink 1.05s ease-in-out infinite; }
.status.error { color: var(--sk-red); }

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
  min-width: 0;
  min-height: 0;
  flex: 1;
  overflow-y: auto;
  border: 1px solid var(--sk-separator);
  border-radius: 16px;
  padding: 22px 24px 28px;
  background: linear-gradient(180deg, rgba(251, 253, 255, 0.96), rgba(255, 255, 255, 0.98));
  box-shadow: 0 16px 40px rgba(45, 76, 132, 0.1);
  scroll-behavior: smooth;
}

.workspace-section { margin-top: 18px; padding-top: 18px; border-top: 1px solid var(--sk-separator); }

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
  border: 1px solid var(--sk-separator);
  border-radius: 12px;
  padding: 14px 0 12px;
  background: rgba(255, 255, 255, 0.72);
  color: var(--sk-label-2);
  cursor: pointer;
  transition: border-color 0.16s ease, color 0.16s ease, background 0.16s ease;
}

.inspector-rail:hover { border-color: var(--zwx-primary-ring); background: rgba(255, 255, 255, 0.92); color: var(--zwx-primary); }
.inspector-rail span { writing-mode: vertical-rl; font-size: 10.5px; font-weight: 600; letter-spacing: 0.16em; }

.legacy-chat { display: flex; height: 100%; min-height: 520px; flex-direction: column; }
.legacy-kicker { flex: 0 0 auto; margin-bottom: 6px; color: var(--sk-label-3); font-size: 11px; }
.legacy-chat > :deep(.chat-container) { flex: 1; border-radius: 12px; overflow: hidden; }

.workspace-empty {
  display: flex;
  min-height: 100%;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding: 24px 16px 40px;
  text-align: center;
}

.empty-avatar { display: block; width: 72px; height: 72px; border-radius: 20px; box-shadow: 0 14px 30px rgba(37, 99, 235, 0.2); }
.empty-kicker { margin-top: 16px; color: var(--sk-label-3); font-size: 10.5px; font-weight: 700; letter-spacing: 0.14em; text-transform: uppercase; }
.workspace-empty h1 { margin: 9px 0 8px; color: var(--sk-label); font-size: clamp(24px, 3vw, 34px); font-weight: 800; line-height: 1.25; letter-spacing: -0.03em; }
.workspace-empty p { max-width: 600px; margin: 0; color: var(--sk-label-2); font-size: 13.5px; line-height: 1.7; }

.template-grid { display: grid; width: min(100%, 720px); grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 10px; margin-top: 28px; }

.template-card {
  display: flex;
  min-width: 0;
  flex-direction: column;
  align-items: flex-start;
  gap: 5px;
  border: 1px solid var(--sk-separator);
  border-radius: 13px;
  padding: 13px 13px 14px;
  background: #fff;
  color: var(--sk-label);
  text-align: left;
  cursor: pointer;
  transition: transform 0.18s ease, border-color 0.18s ease, box-shadow 0.18s ease;
}

.template-card:hover { border-color: var(--zwx-primary-ring); box-shadow: 0 8px 22px rgba(37, 99, 235, 0.1); transform: translateY(-2px); }
.template-icon { display: grid; width: 30px; height: 30px; place-items: center; margin-bottom: 3px; border-radius: 9px; background: var(--zwx-primary-soft); color: var(--zwx-primary); }
.template-card strong { font-size: 12.5px; font-weight: 700; }
.template-card > span:last-child { color: var(--sk-label-2); font-size: 10.5px; line-height: 1.5; }

.confirmation-banner {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-top: 12px;
  padding: 10px 14px;
  border: 1px solid #f5d48a;
  border-radius: 12px;
  background: #fff8e6;
  font-size: 12px;
  color: #7a5a10;
}
.confirmation-text strong { font-weight: 700; }
.confirmation-action {
  flex-shrink: 0;
  border: 1px solid #e2b93b;
  border-radius: 999px;
  padding: 5px 12px;
  background: #fff;
  color: #7a5a10;
  font-size: 11.5px;
  font-weight: 600;
  cursor: pointer;
}
.confirmation-action:hover { background: #fdf1cd; }

.conversation-toggle {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  margin-top: 18px;
  border: 1px solid var(--sk-separator);
  border-radius: 999px;
  padding: 6px 12px;
  background: #fff;
  color: var(--sk-label-2);
  font-size: 11.5px;
  cursor: pointer;
}

.conversation-toggle:hover { border-color: var(--zwx-primary-ring); color: var(--zwx-primary); }
.conversation-toggle svg:last-child { transition: transform 0.18s ease; }
.conversation-toggle svg.rotated { transform: rotate(180deg); }

.conversation-drawer {
  height: min(520px, 62vh);
  margin-top: 12px;
  overflow: hidden;
  border: 1px solid var(--sk-separator);
  border-radius: 14px;
  background: #fff;
}

.workspace-loading {
  position: absolute;
  z-index: 3;
  inset: 0;
  display: grid;
  place-items: center;
  border-radius: 16px;
  background: rgba(248, 251, 255, 0.82);
  backdrop-filter: blur(6px);
  -webkit-backdrop-filter: blur(6px);
  color: var(--sk-label-2);
  font-size: 13px;
}

@keyframes status-blink { 0%, 70%, 100% { opacity: 0.3; } 35% { opacity: 1; } }

@media (prefers-reduced-motion: reduce) {
  .status.connecting::before { animation: none; opacity: 0.5; }
  .workspace-main { transition: none; }
  .workspace-canvas { scroll-behavior: auto; }
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
  .super-header { height: 58px; flex-basis: 58px; padding: 0 14px; }
  .header-start, .header-end { width: auto; }
  .workspace-main { gap: 0; padding: 0; }
  .workspace-canvas { border: 0; border-radius: 0; padding: 18px 14px 24px; box-shadow: none; }
  .workspace-composer-shell { padding: 9px 10px 12px; }
  .workspace-empty { padding: 20px 6px 30px; }
  .empty-avatar { width: 62px; height: 62px; border-radius: 17px; }
  .template-grid { grid-template-columns: 1fr; }
  .conversation-drawer { height: min(480px, 58vh); }
}
</style>
