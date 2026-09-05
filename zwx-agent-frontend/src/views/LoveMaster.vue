<template>
  <div class="love-master-layout">
    <ConversationSidebar
      :conversations="conversations"
      :active-id="chatId"
      :loading="historyLoading"
      :open="sidebarOpen"
      @create="createConversation"
      @select="selectConversation"
      @delete="removeConversation"
      @close="sidebarOpen = false"
    />

    <main class="love-master-main">
      <header class="love-header">
        <div class="header-start">
          <button class="history-toggle" type="button" aria-label="打开历史会话" @click="sidebarOpen = true">☰</button>
          <button class="back-button" type="button" @click="goBack">返回首页</button>
        </div>
        <h1>情感分析大师</h1>
        <AgentSettingsMenu agent-key="love" default-theme="rose" />
      </header>

      <section class="chat-area" :class="{ loading: messagesLoading }">
        <ChatRoom
          class="chat-surface"
          :messages="messages"
          :connection-status="connectionStatus"
          ai-type="love"
          attachments-enabled
          web-search-available
          @send-message="sendMessage"
          @edit-message="cancelActiveStream"
          @resend-message="resendMessage"
          @continue-message="({ message, index }) => continueMessage(message, index)"
        />
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
import AgentSettingsMenu from '../components/AgentSettingsMenu.vue'
import ConversationSidebar from '../components/ConversationSidebar.vue'
import {
  chatWithLoveApp,
  createLoveConversation,
  deleteLoveAssistantReply,
  deleteLoveConversation,
  getLoveImageUrl,
  getLoveConversationMessages,
  listLoveConversations,
  uploadLoveImage,
  getLoveKnowledgeReferences
} from '../api'

useHead({
  title: '情感分析大师 - ZWX Agent',
  meta: [
    { name: 'description', content: '情感分析大师通过对话、图片和知识库协助分析关系与沟通问题。' },
    { name: 'keywords', content: '情感分析大师,情感顾问,关系分析,AI聊天,会话历史' }
  ]
})

const router = useRouter()
const messages = ref([])
const chatId = ref('')
const connectionStatus = ref('disconnected')
const conversations = ref([])
const historyLoading = ref(false)
const messagesLoading = ref(false)
const sidebarOpen = ref(false)
let eventSource = null
let activeTurnStart = -1
let activeRetry = false

const addMessage = (content, isUser, imageKeys = []) => {
  const message = {
    content,
    isUser,
    imageUrls: [],
    imageKeys,
    time: Date.now(),
    references: [],
    trace: null,
    thinking: '',
    activities: []
  }
  messages.value.push(message)
  resolveImageUrls(chatId.value, imageKeys).then(urls => { message.imageUrls = urls })
}

const resolveImageUrls = (conversationId, imageKeys) =>
  Promise.all((imageKeys || []).map(key => getLoveImageUrl(conversationId, key).catch(() => '')))

const refreshConversations = async () => {
  historyLoading.value = true
  try {
    conversations.value = await listLoveConversations()
  } finally {
    historyLoading.value = false
  }
}

const createConversation = async () => {
  if (connectionStatus.value === 'connecting') return
  const conversation = await createLoveConversation()
  await refreshConversations()
  chatId.value = conversation.id
  messages.value = []
  sidebarOpen.value = false
}

const selectConversation = async (conversation) => {
  if (connectionStatus.value === 'connecting' || conversation.id === chatId.value) {
    sidebarOpen.value = false
    return
  }

  messagesLoading.value = true
  chatId.value = conversation.id
  sidebarOpen.value = false
  try {
    const history = await getLoveConversationMessages(conversation.id)
    messages.value = history.length
      ? history.map(message => ({
          content: message.content,
          id: message.id,
          imageUrls: [],
          imageKeys: message.imageObjectKeys || [],
          isUser: message.role === 'USER',
          time: new Date(message.createdAt).getTime(),
          references: message.knowledgeReferences || [],
          trace: message.ragTrace || null,
          activities: [],
          visionAnalysis: message.visionAnalysis || null,
          status: message.status || 'COMPLETED'
        }))
      : []
    await Promise.all(messages.value.map(async message => {
      if (message.imageKeys?.length) message.imageUrls = await resolveImageUrls(conversation.id, message.imageKeys)
    }))
  } finally {
    messagesLoading.value = false
  }
}

const removeConversation = async (conversation) => {
  if (!window.confirm(`删除“${conversation.title}”及其全部消息吗？`)) return
  await deleteLoveConversation(conversation.id)
  const wasActive = chatId.value === conversation.id
  await refreshConversations()

  if (wasActive) {
    if (conversations.value.length) {
      await selectConversation(conversations.value[0])
    } else {
      await createConversation()
    }
  }
}

const finishStream = async (message, aiMessageIndex, refreshReferences = true) => {
  connectionStatus.value = 'disconnected'
  eventSource?.close()
  eventSource = null
  activeTurnStart = -1
  activeRetry = false
  if (refreshReferences && message && aiMessageIndex !== undefined && messages.value[aiMessageIndex]) {
    try {
      messages.value[aiMessageIndex].references = await getLoveKnowledgeReferences(message)
    } catch (error) {
      console.error('Knowledge references unavailable:', error)
    }
  }
  await refreshConversations()
  const history = await getLoveConversationMessages(chatId.value)
  messages.value.forEach((item, index) => { item.id = history[index]?.id })
}

const sendMessage = async ({ message, files, webSearch = false }, retryUserMessageId = null, retryIndex = -1, retryImageKeys = []) => {
  if (!chatId.value) return
  cancelActiveStream()
  let imageKeys = []
  try {
    imageKeys = retryImageKeys.length ? retryImageKeys : await Promise.all(files.map(async file => (await uploadLoveImage(chatId.value, file)).objectKey))
  } catch (error) {
    connectionStatus.value = 'error'
    console.error('Image upload failed:', error)
    return
  }
  activeTurnStart = retryUserMessageId ? retryIndex + 1 : messages.value.length
  activeRetry = Boolean(retryUserMessageId)
  if (!retryUserMessageId) addMessage(message, true, imageKeys)
  const aiMessageIndex = retryUserMessageId ? retryIndex + 1 : messages.value.length
  messages.value.splice(aiMessageIndex, 0, { content: '', isUser: false, time: Date.now(), activities: [] })
  connectionStatus.value = 'connecting'
  eventSource = chatWithLoveApp(message, chatId.value, imageKeys, webSearch, retryUserMessageId, generateRequestId())

  eventSource.addEventListener('references', event => {
    try {
      messages.value[aiMessageIndex].references = JSON.parse(event.data)
    } catch (error) {
      console.error('Knowledge references unavailable:', error)
    }
  })

  eventSource.addEventListener('thinking', event => {
    const answer = messages.value[aiMessageIndex]
    if (!answer) return
    answer.thinking = event.data || '正在思考...'
    if (event.data && !answer.activities.includes(event.data)) answer.activities.push(event.data)
  })

  eventSource.addEventListener('trace', event => {
    try {
      messages.value[aiMessageIndex].trace = JSON.parse(event.data)
    } catch (error) {
      console.error('RAG trace unavailable:', error)
    }
  })

  eventSource.addEventListener('vision', event => {
    try {
      const userMessage = messages.value[aiMessageIndex - 1]
      if (userMessage) userMessage.visionAnalysis = JSON.parse(event.data)
    } catch (error) {
      console.error('Vision analysis unavailable:', error)
    }
  })

  eventSource.addEventListener('generation-error', event => {
    const answer = messages.value[aiMessageIndex]
    if (answer) answer.thinking = event.data
  })

  eventSource.onmessage = async (event) => {
    const data = event.data
    if (data && data !== '[DONE]' && aiMessageIndex < messages.value.length) {
      messages.value[aiMessageIndex].content += data
    }
    if (data === '[DONE]') {
      finishStream(message, aiMessageIndex, !imageKeys.length)
    }
  }

  eventSource.onerror = async (error) => {
    console.error('SSE Error:', error)
    // 保留已生成的部分内容，稍后从服务端恢复持久化的结果
    eventSource?.close()
    eventSource = null
    activeTurnStart = -1
    activeRetry = false
    connectionStatus.value = 'error'
    if (aiMessageIndex >= 0 && !messages.value[aiMessageIndex]?.content) {
      messages.value[aiMessageIndex].content = '连接中断，正在恢复已生成的内容...'
    }
    setTimeout(() => { restoreFromServer().catch(() => {}) }, 1500)
  }
}

const restoreFromServer = async () => {
  if (!chatId.value || connectionStatus.value === 'connecting') return
  connectionStatus.value = 'disconnected'
  const history = await getLoveConversationMessages(chatId.value)
  messages.value = history.map(message => ({
    content: message.content,
    id: message.id,
    imageUrls: [],
    imageKeys: message.imageObjectKeys || [],
    isUser: message.role === 'USER',
    time: new Date(message.createdAt).getTime(),
    references: message.knowledgeReferences || [],
    trace: message.ragTrace || null,
    activities: [],
    visionAnalysis: message.visionAnalysis || null,
    status: message.status || 'COMPLETED'
  }))
  await Promise.all(messages.value.map(async item => {
    if (item.imageKeys?.length) item.imageUrls = await resolveImageUrls(chatId.value, item.imageKeys)
  }))
  await refreshConversations()
}

const continueMessage = (message, index) => {
  if (!chatId.value || !message?.id || connectionStatus.value === 'connecting') return
  cancelActiveStream()
  connectionStatus.value = 'connecting'
  eventSource = chatWithLoveApp('', chatId.value, [], false, null, generateRequestId(), message.id)

  eventSource.addEventListener('generation-error', event => {
    const answer = messages.value[index]
    if (answer) answer.thinking = event.data
  })

  eventSource.addEventListener('thinking', event => {
    const answer = messages.value[index]
    if (answer) answer.thinking = event.data || '正在思考...'
  })

  eventSource.addEventListener('trace', event => {
    try { messages.value[index].trace = JSON.parse(event.data) } catch (error) { console.error('RAG trace unavailable:', error) }
  })

  eventSource.addEventListener('references', event => {
    try { messages.value[index].references = JSON.parse(event.data) } catch (error) { console.error('Knowledge references unavailable:', error) }
  })

  eventSource.onmessage = async event => {
    const answer = messages.value[index]
    if (event.data === '[DONE]') {
      connectionStatus.value = 'disconnected'
      eventSource?.close()
      eventSource = null
      if (answer) { answer.thinking = ''; answer.status = 'COMPLETED' }
      setTimeout(() => { restoreFromServer().catch(() => {}) }, 300)
      return
    }
    if (!answer) return
    if (answer.status === 'INTERRUPTED') { answer.status = 'CONTINUING'; answer.content += '' }
    answer.content += event.data
  }

  eventSource.onerror = () => {
    eventSource?.close()
    eventSource = null
    connectionStatus.value = 'error'
    setTimeout(() => { restoreFromServer().catch(() => {}) }, 1500)
  }
}

const generateRequestId = () =>
  (crypto.randomUUID ? crypto.randomUUID() : `req-${Date.now()}-${Math.random().toString(36).slice(2)}`)

const cancelActiveStream = () => {
  if (!eventSource && connectionStatus.value !== 'connecting') return
  eventSource?.close()
  eventSource = null
  // 保留已生成内容，不再删除本轮消息；用户可通过重新生成继续
  activeTurnStart = -1
  activeRetry = false
  connectionStatus.value = 'disconnected'
}
const resendMessage = async ({ content, id, index, imageKeys }) => {
  if (!id || !chatId.value) return
  cancelActiveStream()
  if (await deleteLoveAssistantReply(chatId.value, id)) {
    if (messages.value[index + 1]?.isUser === false) messages.value.splice(index + 1, 1)
    sendMessage({ message: content, files: [] }, id, index, imageKeys)
  }
}

const goBack = () => router.push('/')

onMounted(async () => {
  await refreshConversations()
  if (conversations.value.length) {
    await selectConversation(conversations.value[0])
  } else {
    await createConversation()
  }
})

onBeforeUnmount(cancelActiveStream)
</script>

<style scoped>
.love-master-layout { display: flex; min-height: 100vh; background: #fff; }
.love-master-main { display: flex; min-width: 0; min-height: 100vh; flex: 1; flex-direction: column; background: #fff; }
.love-header { display: flex; height: 64px; flex: 0 0 64px; align-items: center; justify-content: space-between; padding: 0 28px; border-bottom: 1px solid #ededed; background: rgba(255,255,255,.96); color: #282828; }
.love-header h1 { margin: 0; font-size: 15px; font-weight: 650; }
.header-start { display: flex; width: 180px; align-items: center; gap: 12px; }
.back-button, .history-toggle { border: 0; background: transparent; color: #777; font-size: 13px; }
.back-button:hover, .history-toggle:hover { color: #222; }
.history-toggle { display: none; width: 32px; height: 32px; font-size: 19px; }
.header-status { width: 180px; color: #999; font-size: 12px; text-align: right; }
.header-status.connecting { color: #d65070; }.header-status.error { color: #c33232; }
.chat-area { position: relative; display: flex; min-height: 0; flex: 1; overflow: hidden; background: #fff; }
.chat-surface { width: 100%; }
.chat-area.loading { pointer-events: none; opacity: .55; }
.chat-loading { position: absolute; inset: 0; display: grid; place-items: center; background: rgba(255,255,255,.72); color: #777; font-size: 14px; }
@media (max-width: 720px) { .love-header { padding: 0 14px; } .header-start, .header-status { width: auto; } .history-toggle { display: grid; place-items: center; } .back-button { display: none; } }
</style>
