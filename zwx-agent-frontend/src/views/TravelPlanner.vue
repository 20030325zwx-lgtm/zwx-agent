<template>
  <div class="travel-layout">
    <ConversationSidebar title="旅游规划专家" mark="⌖" theme="travel" :conversations="conversations" :active-id="chatId" :loading="historyLoading" :open="sidebarOpen" @create="createConversation" @select="selectConversation" @delete="removeConversation" @close="sidebarOpen = false" />
    <main class="travel-main">
      <header class="travel-header">
        <div class="header-start"><button class="history-toggle" type="button" aria-label="打开历史会话" @click="sidebarOpen = true">☰</button><button class="back-button" type="button" @click="router.push('/')">智能体目录</button></div>
        <div class="title"><span>⌖</span><strong>旅游规划专家</strong></div>
        <span :class="connectionStatus" class="status">{{ statusText }}</span>
      </header>
      <section class="travel-chat" :class="{ loading: messagesLoading }">
        <ChatRoom :messages="messages" :connection-status="connectionStatus" ai-type="travel" @send-message="sendMessage" />
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
import { chatWithTravelPlanner, createTravelConversation, deleteTravelConversation, getTravelConversationMessages, listTravelConversations } from '../api'

useHead({ title: '旅游规划专家 - ZWX Agent' })
const router = useRouter()
const messages = ref([])
const chatId = ref('')
const conversations = ref([])
const connectionStatus = ref('disconnected')
const historyLoading = ref(false)
const messagesLoading = ref(false)
const sidebarOpen = ref(false)
let eventSource = null
const statusText = computed(() => connectionStatus.value === 'connecting' ? '正在规划' : connectionStatus.value === 'error' ? '连接异常' : '已保存')
const addMessage = (content, isUser, time = Date.now()) => messages.value.push({ content, isUser, time })
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
    messages.value = history.map(message => ({ content: message.content, isUser: message.role === 'USER', time: new Date(message.createdAt).getTime() }))
  } finally { messagesLoading.value = false }
}
const removeConversation = async conversation => {
  if (!window.confirm(`删除“${conversation.title}”及其全部消息吗？`)) return
  await deleteTravelConversation(conversation.id)
  const wasActive = chatId.value === conversation.id
  await refreshConversations()
  if (wasActive) conversations.value.length ? await selectConversation(conversations.value[0]) : await createConversation()
}
const sendMessage = message => {
  if (!chatId.value) return
  addMessage(message, true)
  eventSource?.close()
  const answerIndex = messages.value.length
  addMessage('', false)
  connectionStatus.value = 'connecting'
  eventSource = chatWithTravelPlanner(chatId.value, message)
  eventSource.onmessage = async event => {
    if (event.data === '[DONE]') {
      connectionStatus.value = 'disconnected'; eventSource?.close(); eventSource = null; await refreshConversations(); return
    }
    if (messages.value[answerIndex]) messages.value[answerIndex].content += event.data
  }
  eventSource.onerror = async () => {
    connectionStatus.value = 'error'
    if (!messages.value[answerIndex].content) messages.value[answerIndex].content = '暂时无法完成规划，请稍后重试。'
    eventSource?.close(); eventSource = null; await refreshConversations()
  }
}
onMounted(async () => {
  await refreshConversations()
  if (conversations.value.length) await selectConversation(conversations.value[0])
  else await createConversation()
})
onBeforeUnmount(() => eventSource?.close())
</script>

<style scoped>
.travel-layout { display:flex; min-height:100vh; background:#fff; }.travel-main { display:flex; min-width:0; min-height:100vh; flex:1; flex-direction:column; }.travel-header { display:flex; height:64px; flex:0 0 64px; align-items:center; justify-content:space-between; padding:0 28px; border-bottom:1px solid #ededed; background:#fff; }.header-start { display:flex; width:190px; align-items:center; gap:12px; }.history-toggle,.back-button { border:0; background:transparent; color:#6b7280; font-size:13px; }.history-toggle { display:none; font-size:19px; }.title { display:flex; align-items:center; gap:9px; font-size:15px; }.title span { display:grid; width:30px; height:30px; place-items:center; border-radius:8px; background:#ecfdf5; color:#0f9f6e; font-size:17px; }.status { width:190px; color:#13a37f; font-size:12px; text-align:right; }.status.connecting { color:#0f9f6e; }.status.error { color:#d14343; }.travel-chat { position:relative; min-height:0; flex:1; }.travel-chat.loading :deep(.chat-container) { opacity:.45; pointer-events:none; }.chat-loading { position:absolute; inset:0; display:grid; place-items:center; background:rgba(255,255,255,.68); color:#777; font-size:13px; } @media(max-width:720px){.history-toggle{display:block}.travel-header{padding:0 16px}.header-start,.status{width:auto}.back-button{display:none}}
</style>
