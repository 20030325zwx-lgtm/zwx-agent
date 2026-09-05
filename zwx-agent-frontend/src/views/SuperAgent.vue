<template>
  <div class="super-layout">
    <ConversationSidebar title="超级智能体" mark="✦" theme="travel" :conversations="conversations" :active-id="chatId" :loading="historyLoading" :open="sidebarOpen" @create="createConversation" @select="selectConversation" @delete="removeConversation" @close="sidebarOpen = false" />
    <main class="super-main">
      <header class="super-header">
        <div class="header-start">
          <button class="history-toggle" type="button" aria-label="打开历史会话" @click="sidebarOpen = true">☰</button>
          <button class="back-button" type="button" @click="goBack">智能体目录</button>
          <button class="back-button" type="button" @click="router.push('/mcp-settings')">MCP 管理</button>
        </div>
        <div class="title">
          <span class="super-mark" aria-hidden="true">✦</span>
          <strong>超级智能体</strong>
          <em class="kind-pill">Agent</em>
        </div>
        <div class="header-end">
          <span class="status" :class="connectionStatus">{{ statusText }}</span>
        </div>
      </header>

      <section class="super-chat" :class="{ loading: messagesLoading }">
        <ChatRoom :messages="messages" :connection-status="connectionStatus" ai-type="super" knowledge-search-available
          @send-message="sendMessage" @edit-message="cancelActiveStream" @resend-message="resendMessage" />
        <div v-if="messagesLoading" class="chat-loading">正在恢复历史消息...</div>
      </section>
    </main>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useHead } from '@vueuse/head'
import ChatRoom from '../components/ChatRoom.vue'
import ConversationSidebar from '../components/ConversationSidebar.vue'
import { chatWithManus, createManusConversation, deleteManusConversation, getManusConversationMessages, getManusFileUrl, listManusConversations } from '../api'

useHead({ title: '超级智能体 - ZWX Agent', meta: [{ name: 'description', content: 'ZWX Agent 的自主智能体，可规划任务、调用工具并多步执行。' }] })
const router = useRouter()
const messages = ref([])
const chatId = ref('')
const conversations = ref([])
const connectionStatus = ref('disconnected')
const historyLoading = ref(false)
const messagesLoading = ref(false)
const sidebarOpen = ref(false)
let eventSource = null
let activeTurnStart = -1
const statusText = computed(() => connectionStatus.value === 'connecting' ? '正在执行' : connectionStatus.value === 'error' ? '连接异常' : '就绪')
const addMessage = (content, isUser) => messages.value.push({ content, isUser, time: Date.now() })
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
      return { label: event.agent ? `${event.agent} · ${event.summary}` : event.summary, detail: event.summary }
    }
  } catch { /* 旧格式纯文本兜底 */ }
  return { label: activityLabel(data), detail: data }
}
const mapFiles = (conversationId, fileAttachments) => {
  try {
    return JSON.parse(fileAttachments || '[]').map(file => ({ ...file, url: '' }))
  } catch { return [] }
}
// 通过响应式代理回填文件地址：在 messages.value 上就地替换，确保模板重新渲染
const hydrateFileUrls = conversationId => {
  messages.value.forEach(message => {
    ;(message.files || []).forEach((file, index) => {
      if (file.url || !file.path) return
      getManusFileUrl(conversationId, file.path)
        .then(url => { message.files[index] = { ...file, url } })
        .catch(() => { message.files[index] = { ...file, broken: true } })
    })
  })
}
const mapMessage = message => ({
  id: message.id,
  content: message.content,
  isUser: message.role === 'USER',
  time: new Date(message.createdAt).getTime(),
  activities: [],
  collapsibleActivities: message.role === 'ASSISTANT',
  files: mapFiles(chatId.value, message.fileAttachments)
})
const refreshConversations = async () => {
  historyLoading.value = true
  try { conversations.value = await listManusConversations() }
  finally { historyLoading.value = false }
}
const createConversation = async () => {
  if (connectionStatus.value === 'connecting') return
  const conversation = await createManusConversation()
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
  try { messages.value = (await getManusConversationMessages(conversation.id)).map(mapMessage) }
  finally {
    messagesLoading.value = false
    hydrateFileUrls(conversation.id)
  }
}
const removeConversation = async conversation => {
  if (!window.confirm(`删除“${conversation.title}”及其全部消息吗？`)) return
  await deleteManusConversation(conversation.id)
  const wasActive = chatId.value === conversation.id
  await refreshConversations()
  if (wasActive) conversations.value.length ? await selectConversation(conversations.value[0]) : await createConversation()
}
const sendMessage = payload => {
  if (!chatId.value) return
  const message = typeof payload === 'string' ? payload : payload.message
  const knowledgeSearch = typeof payload === 'string' ? false : payload.knowledgeSearch === true
  cancelActiveStream()
  activeTurnStart = messages.value.length
  addMessage(message, true)
  messages.value.push({ content: '', isUser: false, time: Date.now(), activities: [], collapsibleActivities: true })
  const answerIndex = messages.value.length - 1
  connectionStatus.value = 'connecting'
  eventSource = chatWithManus(chatId.value, message, knowledgeSearch)
  eventSource.addEventListener('activity', event => {
    const answer = messages.value[answerIndex]
    if (!answer) return
    answer.activities.push(parseActivity(event.data || ''))
  })
  eventSource.onmessage = event => {
    if (event.data === '[DONE]') { finishStream(); return }
    if (event.data && messages.value[answerIndex]) messages.value[answerIndex].content += event.data
  }
  eventSource.onerror = () => {
    eventSource?.close()
    eventSource = null
    const answer = messages.value[answerIndex]
    if (answer && !answer.content) answer.content = '连接异常，暂时无法完成请求，请稍后重试。'
    activeTurnStart = -1
    connectionStatus.value = 'error'
  }
}
const finishStream = async () => {
  connectionStatus.value = 'disconnected'
  eventSource?.close()
  eventSource = null
  activeTurnStart = -1
  await refreshConversations()
  const history = await getManusConversationMessages(chatId.value)
  messages.value = history.map(mapMessage)
  hydrateFileUrls(chatId.value)
}
const cancelActiveStream = () => {
  eventSource?.close()
  eventSource = null
  if (activeTurnStart >= 0) messages.value.splice(activeTurnStart)
  activeTurnStart = -1
  connectionStatus.value = 'disconnected'
}
const resendMessage = ({ content }) => sendMessage(content)
const goBack = () => router.push('/')
onMounted(async () => {
  await refreshConversations()
  if (conversations.value.length) await selectConversation(conversations.value[0])
  else await createConversation()
})
onBeforeUnmount(cancelActiveStream)
</script>

<style scoped>
.super-layout { display: flex; height: 100vh; overflow: hidden; background: var(--sk-bg); }
.super-main { display: flex; min-width: 0; min-height: 0; flex: 1; flex-direction: column; overflow: hidden; }

.super-header {
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

.header-start { display: flex; width: 190px; align-items: center; gap: 6px; }

.history-toggle {
  display: grid;
  width: 32px;
  height: 32px;
  place-items: center;
  border: 0;
  border-radius: 9px;
  background: transparent;
  color: var(--sk-label-2);
  font-size: 18px;
}

.history-toggle:hover { background: var(--sk-fill); color: var(--sk-label); }

.back-button { border: 0; background: transparent; color: var(--zwx-primary); font-size: 13px; }
.back-button:hover { opacity: 0.7; }

.title { display: flex; align-items: center; gap: 9px; font-size: 15px; }

.super-mark {
  display: grid;
  width: 30px;
  height: 30px;
  place-items: center;
  border-radius: 9px;
  background: linear-gradient(160deg, #3f9bff, var(--zwx-primary) 70%);
  color: #fff;
  font-size: 15px;
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.35), 0 3px 8px var(--zwx-primary-ring);
}

.title strong { font-weight: 700; letter-spacing: -0.01em; }

.kind-pill { border: 0; border-radius: 6px; padding: 3px 7px; background: var(--zwx-primary-soft); color: var(--zwx-primary); font-size: 10px; font-style: normal; font-weight: 700; }

.header-end { display: flex; width: 190px; justify-content: flex-end; }

.status { display: flex; align-items: center; gap: 6px; color: #1f9d4d; font-size: 12px; font-weight: 550; }
.status::before { content: ""; width: 7px; height: 7px; border-radius: 50%; background: currentColor; }
.status.connecting { color: var(--zwx-primary); }
.status.connecting::before { animation: status-blink 1.05s ease-in-out infinite; }
.status.error { color: var(--sk-red); }

.super-chat {
  position: relative;
  min-height: 0;
  flex: 1;
  margin: 16px 20px 20px;
  border: 1px solid var(--sk-separator);
  border-radius: 22px;
  background: var(--sk-surface);
  box-shadow: var(--sk-shadow-card);
  overflow: hidden;
}

.chat-loading {
  position: absolute;
  z-index: 3;
  inset: 0;
  display: grid;
  place-items: center;
  background: var(--sk-material);
  backdrop-filter: var(--sk-blur);
  -webkit-backdrop-filter: var(--sk-blur);
  color: var(--sk-label-2);
  font-size: 13px;
}

@keyframes status-blink { 0%, 70%, 100% { opacity: 0.3; } 35% { opacity: 1; } }

@media (prefers-reduced-motion: reduce) { .status.connecting::before { animation: none; opacity: 0.5; } }

@media (max-width: 720px) {
  .super-header { height: 58px; flex-basis: 58px; padding: 0 14px; }
  .header-start, .header-end { width: auto; }
  .super-chat { margin: 0; border: 0; border-radius: 0; box-shadow: none; }
}
</style>
