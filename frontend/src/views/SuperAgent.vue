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
          attachments-enabled attachment-accept="image/jpeg,image/png,image/gif,image/webp"
          @send-message="sendMessage" @edit-message="cancelActiveStream" @resend-message="resendMessage"
          @continue-message="continueMessage" @stop-generation="stopGeneration" />
        <div v-if="messagesLoading" class="chat-loading">正在恢复历史消息...</div>
      </section>
    </main>
    <ConfirmDialog
      :open="!!pendingDelete"
      title="删除会话"
      :message="deleteMessage"
      confirm-text="删除"
      @confirm="confirmDelete"
      @cancel="pendingDelete = null"
    />
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useHead } from '@vueuse/head'
import ChatRoom from '../components/ChatRoom.vue'
import ConversationSidebar from '../components/ConversationSidebar.vue'
import ConfirmDialog from '../components/ConfirmDialog.vue'
import HomeButton from '../components/HomeButton.vue'
import { chatWithManus, resumeManusRun, reconnectManusRun, cancelManusRun, createManusConversation, deleteManusConversation, getManusConversationMessages, getManusFileUrl, getManusImageUrl, uploadManusImage, listManusConversations } from '../api'

useHead({ title: '超级智能体 - Aster', meta: [{ name: 'description', content: 'Aster 的自主智能体，可规划任务、调用工具并多步执行。' }] })
const router = useRouter()
const messages = ref([])
const chatId = ref('')
const conversations = ref([])
const connectionStatus = ref('disconnected')
const historyLoading = ref(false)
const messagesLoading = ref(false)
const sidebarOpen = ref(false)
const pendingDelete = ref(null)
let eventSource = null
let activeTurnStart = -1
let reconnectAttempts = 0
const statusText = computed(() => connectionStatus.value === 'connecting' ? '正在执行' : connectionStatus.value === 'error' ? '连接异常' : '就绪')
const addMessage = (content, isUser, imageObjectKey = null) => {
  const message = { content, isUser, time: Date.now(), imageKeys: imageObjectKey ? [imageObjectKey] : [], imageUrls: [] }
  messages.value.push(message)
  if (imageObjectKey && chatId.value) {
    getManusImageUrl(chatId.value, imageObjectKey)
      .then(url => { message.imageUrls = [url] })
      .catch(error => console.error('Unable to load uploaded image:', error))
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
const parseRagTrace = value => { try { const trace = JSON.parse(value || 'null'); if (!trace?.traceId) return null; trace.candidates?.forEach(candidate => { candidate.judgment = trace.judgments?.[candidate.documentId] || null }); return trace } catch { return null } }
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
  if (!chatId.value) return
  const message = typeof payload === 'string' ? payload : payload.message
  const knowledgeSearch = typeof payload === 'string' ? false : payload.knowledgeSearch === true
  const files = typeof payload === 'string' ? [] : payload.files || []
  let imageObjectKey = null
  if (files.length) {
    try {
      imageObjectKey = (await uploadManusImage(chatId.value, files[0])).objectKey
    } catch (error) {
      console.error('Unable to upload image:', error)
      addMessage('图片上传失败，请检查文件格式和大小后重试。', false)
      return
    }
  }
  cancelActiveStream()
  activeTurnStart = messages.value.length
  addMessage(message, true, imageObjectKey)
  messages.value.push({ content: '', isUser: false, time: Date.now(), activities: [], collapsibleActivities: true })
  const answerIndex = messages.value.length - 1
  const requestId = crypto.randomUUID ? crypto.randomUUID() : `req-${Date.now()}-${Math.random().toString(36).slice(2)}`
  connectionStatus.value = 'connecting'
  reconnectAttempts = 0
  eventSource = chatWithManus(chatId.value, message, knowledgeSearch, requestId, imageObjectKey)
  attachManusStream(eventSource, answerIndex)
}
const attachManusStream = (stream, answerIndex) => {
  stream.onopen = () => { reconnectAttempts = 0 }
  stream.addEventListener('run', event => {
    const answer = messages.value[answerIndex]
    if (answer) answer.runId = event.data
  })
  stream.addEventListener('rag-trace', event => {
    try { messages.value[answerIndex].ragTrace = JSON.parse(event.data) }
    catch (error) { console.error('RAG retrieval trace unavailable:', error) }
  })
  stream.addEventListener('activity', event => {
    const answer = messages.value[answerIndex]
    if (!answer) return
    answer.activities.push(parseActivity(event.data || ''))
  })
  stream.addEventListener('interrupted', () => {
    const answer = messages.value[answerIndex]
    if (answer) answer.status = 'INTERRUPTED'
  })
  stream.onmessage = event => {
    if (event.data === '[DONE]') { finishStream(); return }
    if (event.data && messages.value[answerIndex]) messages.value[answerIndex].content += event.data
  }
  stream.onerror = () => handleManusStreamError(answerIndex)
}
const handleManusStreamError = answerIndex => {
  const answer = messages.value[answerIndex]
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
  if (answer && !answer.content) answer.content = '连接异常，正在恢复历史消息...'
  setTimeout(() => { finishStream().catch(error => console.error(error)) }, 1500)
}
const finishStream = async () => {
  connectionStatus.value = 'disconnected'
  eventSource?.close()
  eventSource = null
  activeTurnStart = -1
  reconnectAttempts = 0
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
  reconnectAttempts = 0
  connectionStatus.value = 'disconnected'
}
const stopGeneration = async () => {
  const answer = messages.value[messages.value.length - 1]
  const runId = answer?.runId
  eventSource?.close()
  eventSource = null
  activeTurnStart = -1
  reconnectAttempts = 0
  connectionStatus.value = 'disconnected'
  if (answer) {
    answer.status = 'INTERRUPTED'
    if (!answer.content) answer.content = '已停止生成。'
  }
  if (runId && chatId.value) {
    try { await cancelManusRun(chatId.value, runId) } catch (error) { console.error('Unable to cancel manus run:', error) }
  }
  setTimeout(async () => {
    if (!chatId.value) return
    try {
      messages.value = (await getManusConversationMessages(chatId.value)).map(mapMessage)
      hydrateFileUrls(chatId.value)
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
  eventSource = resumeManusRun(chatId.value, message.runId)
  const answerIndex = messages.value.indexOf(answer)
  const originalError = eventSource.onerror
  attachManusStream(eventSource, answerIndex >= 0 ? answerIndex : index)
  eventSource.onerror = error => {
    if (!answer.content) answer.content = previousContent
    if (originalError) originalError(error)
  }
}
const resendMessage = ({ content }) => sendMessage(content)
onMounted(async () => {
  await refreshConversations()
  if (conversations.value.length) await selectConversation(conversations.value[0])
  else await createConversation()
})
onBeforeUnmount(cancelActiveStream)
</script>

<style scoped>
.super-layout { display: flex; height: 100vh; overflow: hidden; background: linear-gradient(135deg, #e5efff, #f5f9ff 44%, #eaf3ff); }
.super-main { display: flex; min-width: 0; min-height: 0; flex: 1; flex-direction: column; overflow: hidden; }

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

.header-end { display: flex; width: auto; justify-content: flex-end; }

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
  border-radius: 14px;
  background: rgba(255, 255, 255, 0.93);
  box-shadow: 0 16px 40px rgba(45, 76, 132, 0.12);
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
