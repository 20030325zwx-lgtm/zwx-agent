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
        <span class="header-status" :class="connectionStatus">{{ statusText }}</span>
      </header>

      <section class="chat-area" :class="{ loading: messagesLoading }">
        <ChatRoom
          class="chat-surface"
          :messages="messages"
          :connection-status="connectionStatus"
          ai-type="love"
          attachments-enabled
          @send-message="sendMessage"
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
import ConversationSidebar from '../components/ConversationSidebar.vue'
import {
  chatWithLoveApp,
  createLoveConversation,
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

const statusText = computed(() => {
  if (connectionStatus.value === 'connecting') return '正在回复'
  if (connectionStatus.value === 'error') return '连接异常'
  return '已保存'
})

const addMessage = (content, isUser, imageKeys = []) => {
  messages.value.push({
    content,
    isUser,
    imageUrls: imageKeys.map(key => getLoveImageUrl(chatId.value, key)),
    time: Date.now(),
    references: [],
    trace: null,
    thinking: ''
  })
}

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
          imageUrls: (message.imageObjectKeys || []).map(key => getLoveImageUrl(conversation.id, key)),
          isUser: message.role === 'USER',
          time: new Date(message.createdAt).getTime(),
          references: message.knowledgeReferences || [],
          trace: message.ragTrace || null,
          visionAnalysis: message.visionAnalysis || null
        }))
      : []
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
  if (refreshReferences && message && aiMessageIndex !== undefined && messages.value[aiMessageIndex]) {
    try {
      messages.value[aiMessageIndex].references = await getLoveKnowledgeReferences(message)
    } catch (error) {
      console.error('Knowledge references unavailable:', error)
    }
  }
  await refreshConversations()
}

const sendMessage = async ({ message, files }) => {
  if (!chatId.value) return
  let imageKeys = []
  try {
    imageKeys = await Promise.all(files.map(async file => (await uploadLoveImage(chatId.value, file)).objectKey))
  } catch (error) {
    connectionStatus.value = 'error'
    console.error('Image upload failed:', error)
    return
  }
  addMessage(message, true, imageKeys)
  eventSource?.close()

  const aiMessageIndex = messages.value.length
  addMessage('', false)
  connectionStatus.value = 'connecting'
  eventSource = chatWithLoveApp(message, chatId.value, imageKeys)

  eventSource.addEventListener('references', event => {
    try {
      messages.value[aiMessageIndex].references = JSON.parse(event.data)
    } catch (error) {
      console.error('Knowledge references unavailable:', error)
    }
  })

  eventSource.addEventListener('thinking', event => {
    if (messages.value[aiMessageIndex]) messages.value[aiMessageIndex].thinking = event.data || '正在思考...'
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
    if (eventSource?.readyState === EventSource.CLOSED) {
      await finishStream(message, aiMessageIndex, !imageKeys.length)
      return
    }
    console.error('SSE Error:', error)
    if (aiMessageIndex < messages.value.length && !messages.value[aiMessageIndex].content) {
      messages.value[aiMessageIndex].content = imageKeys.length
        ? '图片暂时无法分析，请检查视觉模型和对象存储配置后重试。'
        : '暂时无法获取回复，请稍后重试。'
    }
    connectionStatus.value = 'error'
    eventSource?.close()
    eventSource = null
    await refreshConversations()
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

onBeforeUnmount(() => eventSource?.close())
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
