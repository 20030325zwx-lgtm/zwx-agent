<template>
  <div class="travel-layout">
    <ConversationSidebar title="旅游规划专家" mark="⌖" theme="travel" :conversations="conversations" :active-id="chatId" :loading="historyLoading" :open="sidebarOpen" @create="createConversation" @select="selectConversation" @delete="removeConversation" @close="sidebarOpen = false" />
    <main class="travel-main">
      <header class="travel-header">
        <div class="header-start"><button class="history-toggle" type="button" aria-label="打开历史会话" @click="sidebarOpen = true">☰</button><button class="back-button" type="button" @click="router.push('/')">智能体目录</button></div>
        <div class="title"><span>⌖</span><strong>旅游规划专家</strong></div>
        <AgentSettingsMenu agent-key="travel" default-theme="emerald" />
      </header>
      <section class="travel-chat" :class="{ loading: messagesLoading }">
        <ChatRoom :messages="messages" :connection-status="connectionStatus" ai-type="travel" attachments-enabled attachment-accept=".md,.txt,.pdf,.doc,.docx,.xls,.xlsx,.ppt,.pptx" web-search-available @send-message="sendMessage" @edit-message="cancelActiveStream" @resend-message="resendMessage" @view-execution="openExecution" />
        <div v-if="messagesLoading" class="chat-loading">正在恢复历史消息...</div>
      </section>
    </main>
    <aside v-if="executionPanel.open" class="execution-panel" aria-label="执行详情">
      <header><div><span>执行详情</span><strong>运行记录</strong></div><button type="button" aria-label="关闭执行详情" @click="executionPanel.open = false">×</button></header>
      <p v-if="executionPanel.loading" class="execution-state">正在读取执行记录...</p>
      <p v-else-if="executionPanel.error" class="execution-state error">{{ executionPanel.error }}</p>
      <ol v-else class="execution-list">
        <li v-for="event in executionPanel.events" :key="event.sequence">
          <div><span>{{ event.sequence }}</span><strong>{{ event.summary }}</strong></div>
          <small>{{ event.phase }}</small>
          <pre v-if="Object.keys(event.detail || {}).length">{{ formatDetail(event.detail) }}</pre>
        </li>
      </ol>
    </aside>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useHead } from '@vueuse/head'
import ChatRoom from '../components/ChatRoom.vue'
import AgentSettingsMenu from '../components/AgentSettingsMenu.vue'
import ConversationSidebar from '../components/ConversationSidebar.vue'
import { chatWithTravelPlanner, createTravelConversation, deleteTravelAssistantReply, deleteTravelConversation, getTravelConversationMessages, getTravelExecutionEvents, listTravelConversations, uploadAgentKnowledgeDocument } from '../api'

useHead({ title: '旅游规划专家 - ZWX Agent' })
const router = useRouter()
const messages = ref([])
const chatId = ref('')
const conversations = ref([])
const connectionStatus = ref('disconnected')
const historyLoading = ref(false)
const messagesLoading = ref(false)
const sidebarOpen = ref(false)
const executionPanel = ref({ open: false, loading: false, error: '', events: [] })
let eventSource = null
let activeTurnStart = -1
let activeRetry = false
const addMessage = (content, isUser, time = Date.now()) => messages.value.push({ content, isUser, time, activities: [] })
const refreshConversations = async () => {
  historyLoading.value = true
  try { conversations.value = await listTravelConversations() } finally { historyLoading.value = false }
}
const createConversation = async () => {
  if (connectionStatus.value === 'connecting') return
  const conversation = await createTravelConversation()
  await refreshConversations()
  chatId.value = conversation.id
  messages.value = []
  sidebarOpen.value = false
}
const selectConversation = async conversation => {
  if (connectionStatus.value === 'connecting' || conversation.id === chatId.value) { sidebarOpen.value = false; return }
  messagesLoading.value = true
  chatId.value = conversation.id
  sidebarOpen.value = false
  try {
    const history = await getTravelConversationMessages(conversation.id)
    messages.value = history.map(message => ({ id: message.id, content: message.content, isUser: message.role === 'USER', time: new Date(message.createdAt).getTime(), activities: message.executionRunId ? [{ label: '查看执行过程', runId: message.executionRunId }] : [] }))
  } finally { messagesLoading.value = false }
}
const removeConversation = async conversation => {
  if (!window.confirm(`删除“${conversation.title}”及其全部消息吗？`)) return
  await deleteTravelConversation(conversation.id)
  const wasActive = chatId.value === conversation.id
  await refreshConversations()
  if (wasActive) conversations.value.length ? await selectConversation(conversations.value[0]) : await createConversation()
}
const sendMessage = async (payload, retryUserMessageId = null, retryIndex = -1) => {
  const message = typeof payload === 'string' ? payload : payload.message
  const webSearch = typeof payload === 'string' ? false : payload.webSearch
  const files = typeof payload === 'string' ? [] : payload.files || []
  if (!chatId.value) return
  if (files.length) await Promise.all(files.map(file => uploadAgentKnowledgeDocument('travel', file)))
  cancelActiveStream()
  activeTurnStart = retryUserMessageId ? retryIndex + 1 : messages.value.length
  activeRetry = Boolean(retryUserMessageId)
  if (!retryUserMessageId) addMessage(message, true)
  const answerIndex = retryUserMessageId ? retryIndex + 1 : messages.value.length
  messages.value.splice(answerIndex, 0, { content: '', isUser: false, time: Date.now(), activities: [] })
  connectionStatus.value = 'connecting'
  eventSource = chatWithTravelPlanner(chatId.value, message, webSearch, retryUserMessageId, (crypto.randomUUID ? crypto.randomUUID() : `req-${Date.now()}-${Math.random().toString(36).slice(2)}`))
  eventSource.addEventListener('references', event => {
    try { messages.value[answerIndex].references = JSON.parse(event.data) }
    catch (error) { console.error('Knowledge references unavailable:', error) }
  })
  eventSource.addEventListener('thinking', event => {
    const answer = messages.value[answerIndex]
    if (!answer) return
    answer.thinking = event.data || '正在思考...'
  })
  eventSource.addEventListener('activity', event => {
    const answer = messages.value[answerIndex]
    if (!answer) return
    try {
      const activity = JSON.parse(event.data)
      if (!answer.activities.some(item => item.runId === activity.runId && item.sequence === activity.sequence)) {
        answer.activities.push({ label: activity.summary, runId: activity.runId, sequence: activity.sequence })
      }
    } catch (error) { console.error('Execution activity unavailable:', error) }
  })
  eventSource.onmessage = async event => {
    if (event.data === '[DONE]') {
      connectionStatus.value = 'disconnected'; eventSource?.close(); eventSource = null; activeTurnStart = -1; activeRetry = false; await refreshConversations(); return
    }
    if (messages.value[answerIndex]) messages.value[answerIndex].content += event.data
  }
  eventSource.onerror = () => {
    // 保留已生成内容，稍后从服务端恢复持久化结果
    eventSource?.close(); eventSource = null; activeTurnStart = -1; activeRetry = false
    connectionStatus.value = 'error'
    if (messages.value[answerIndex] && !messages.value[answerIndex].content) {
      messages.value[answerIndex].content = '连接中断，正在恢复已生成的内容...'
    }
    setTimeout(async () => {
      connectionStatus.value = 'disconnected'
      try { await refreshConversations() } catch (error) { console.error('Conversation refresh failed:', error) }
    }, 1500)
  }
}
const cancelActiveStream = () => {
  if (!eventSource && connectionStatus.value !== 'connecting') return
  eventSource?.close()
  eventSource = null
  // 保留已生成内容，不再删除本轮消息
  activeTurnStart = -1
  activeRetry = false
  connectionStatus.value = 'disconnected'
}
const resendMessage = async ({ content, id, index }) => {
  if (!id || !chatId.value) return
  cancelActiveStream()
  if (await deleteTravelAssistantReply(chatId.value, id)) {
    if (messages.value[index + 1]?.isUser === false) messages.value.splice(index + 1, 1)
    sendMessage(content, id, index)
  }
}
const openExecution = async ({ runId }) => {
  if (!runId || !chatId.value) return
  executionPanel.value = { open: true, loading: true, error: '', events: [] }
  try {
    executionPanel.value.events = await getTravelExecutionEvents(chatId.value, runId)
  } catch (error) {
    console.error('Execution detail unavailable:', error)
    executionPanel.value.error = '无法读取本次执行记录。'
  } finally { executionPanel.value.loading = false }
}
const formatDetail = detail => JSON.stringify(detail, null, 2)
onMounted(async () => {
  await refreshConversations()
  if (conversations.value.length) await selectConversation(conversations.value[0])
  else await createConversation()
})
onBeforeUnmount(cancelActiveStream)
</script>

<style scoped>
.travel-layout { display: flex; height: 100vh; overflow: hidden; background: var(--sk-bg); }
.travel-main { display: flex; min-width: 0; min-height: 0; flex: 1; flex-direction: column; overflow: hidden; }

.travel-header {
  display: flex;
  height: 60px;
  flex: 0 0 60px;
  align-items: center;
  justify-content: space-between;
  padding: 0 24px;
  border-bottom: 1px solid var(--sk-separator);
  background: var(--sk-material);
  backdrop-filter: var(--sk-blur);
  -webkit-backdrop-filter: var(--sk-blur);
}

.header-start { display: flex; width: 190px; align-items: center; gap: 12px; }

.history-toggle, .back-button { border: 0; background: transparent; color: var(--zwx-primary); font-size: 13px; }

.history-toggle { display: none; width: 32px; height: 32px; border-radius: 9px; background: var(--sk-fill); color: var(--sk-label); font-size: 18px; }

.title { display: flex; align-items: center; gap: 9px; font-size: 15px; }

.title span {
  display: grid;
  width: 30px;
  height: 30px;
  place-items: center;
  border-radius: 9px;
  background: linear-gradient(160deg, #4cd471, var(--zwx-primary) 70%);
  color: #fff;
  font-size: 15px;
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.35), 0 3px 8px rgba(48, 179, 80, 0.3);
}

.title strong { font-weight: 700; letter-spacing: -0.01em; }

.travel-chat { position: relative; min-height: 0; flex: 1; overflow: hidden; }
.travel-chat.loading :deep(.chat-container) { opacity: 0.45; pointer-events: none; }

.chat-loading {
  position: absolute;
  inset: 0;
  display: grid;
  place-items: center;
  background: var(--sk-material);
  backdrop-filter: var(--sk-blur);
  -webkit-backdrop-filter: var(--sk-blur);
  color: var(--sk-label-2);
  font-size: 13px;
}

/* 执行详情面板 */
.execution-panel {
  display: flex;
  width: min(430px, 92vw);
  height: 100vh;
  min-height: 0;
  flex: 0 0 min(430px, 92vw);
  flex-direction: column;
  overflow: hidden;
  border-left: 1px solid var(--sk-separator);
  background: var(--sk-material-strong);
  backdrop-filter: var(--sk-blur);
  -webkit-backdrop-filter: var(--sk-blur);
}

.execution-panel header { flex: 0 0 auto; display: flex; align-items: center; justify-content: space-between; padding: 20px; border-bottom: 1px solid var(--sk-separator); }
.execution-panel header div { display: grid; gap: 4px; }
.execution-panel header span, .execution-panel small { color: var(--sk-label-3); font-size: 12px; }
.execution-panel header strong { font-size: 16px; font-weight: 700; letter-spacing: -0.01em; }
.execution-panel header button { display: grid; width: 30px; height: 30px; place-items: center; border: 0; border-radius: 9px; background: var(--sk-fill); color: var(--sk-label-2); font-size: 20px; }
.execution-panel header button:hover { background: var(--sk-fill-strong); color: var(--sk-label); }

.execution-state { padding: 22px; color: var(--sk-label-2); font-size: 13px; }
.execution-state.error { color: var(--sk-red); }

.execution-list { min-height: 0; flex: 1; overflow-y: auto; overscroll-behavior: contain; display: grid; gap: 0; margin: 0; padding: 10px 16px 18px; list-style: none; align-content: start; }

.execution-list li { border-bottom: 1px solid var(--sk-separator); padding: 13px 4px; }
.execution-list li > div { display: flex; align-items: center; gap: 9px; }

.execution-list li > div > span {
  display: grid;
  width: 22px;
  height: 22px;
  flex: 0 0 22px;
  place-items: center;
  border-radius: 50%;
  background: var(--zwx-primary-soft);
  color: var(--zwx-primary);
  font-size: 11px;
  font-weight: 700;
}

.execution-list li strong { font-size: 13px; font-weight: 600; }

.execution-list pre {
  overflow-x: auto;
  margin: 9px 0 0;
  padding: 9px;
  border-radius: 9px;
  background: var(--sk-fill);
  color: var(--sk-label-2);
  font: 11px/1.55 ui-monospace, "SF Mono", SFMono-Regular, Menlo, monospace;
  white-space: pre-wrap;
  word-break: break-word;
}
</style>
