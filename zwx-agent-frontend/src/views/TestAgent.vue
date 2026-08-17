<template>
  <div class="test-layout">
    <ConversationSidebar title="功能测试助手" mark="✓" theme="test" :conversations="conversations" :active-id="chatId" :loading="loading" :open="sidebarOpen" @create="create" @select="select" @delete="remove" @close="sidebarOpen=false" />
    <main><header><button @click="sidebarOpen=true">☰</button><strong>功能测试助手</strong><button @click="router.push('/')">智能体目录</button></header><ChatRoom :messages="messages" :connection-status="status" ai-type="test" @send-message="send" @edit-message="cancel" @resend-message="resend" /></main>
  </div>
</template>
<script setup>
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import ChatRoom from '../components/ChatRoom.vue'
import ConversationSidebar from '../components/ConversationSidebar.vue'
import { chatWithTestAgent, createTestConversation, deleteTestAssistantReply, deleteTestConversation, getTestConversationMessages, listTestConversations } from '../api'
const router = useRouter(); const messages = ref([]); const conversations = ref([]); const chatId = ref(''); const status = ref('disconnected'); const loading = ref(false); const sidebarOpen = ref(false)
let stream = null; let start = -1; let retrying = false
const refresh = async () => { loading.value = true; try { conversations.value = await listTestConversations() } finally { loading.value = false } }
const create = async () => { if (status.value === 'connecting') return; const item = await createTestConversation(); await refresh(); chatId.value = item.id; messages.value = []; sidebarOpen.value = false }
const select = async item => { if (status.value === 'connecting') return; chatId.value = item.id; sidebarOpen.value = false; messages.value = (await getTestConversationMessages(item.id)).map(message => ({ id: message.id, content: message.content, isUser: message.role === 'USER', time: new Date(message.createdAt).getTime(), activities: [] })) }
const remove = async item => { await deleteTestConversation(item.id); await refresh(); if (chatId.value === item.id) conversations.value.length ? await select(conversations.value[0]) : await create() }
const send = (content, retryId = null, retryIndex = -1) => { if (!chatId.value) return; cancel(); start = retryId ? retryIndex + 1 : messages.value.length; retrying = !!retryId; if (!retryId) messages.value.push({ content, isUser: true, time: Date.now(), activities: [] }); const answerIndex = retryId ? retryIndex + 1 : messages.value.length; messages.value.splice(answerIndex, 0, { content: '', isUser: false, time: Date.now(), activities: [] }); status.value = 'connecting'; stream = chatWithTestAgent(chatId.value, content, retryId); stream.onmessage = async event => { if (event.data === '[DONE]') { status.value = 'disconnected'; stream?.close(); stream = null; start = -1; await refresh(); return }; messages.value[answerIndex].content += event.data }; stream.onerror = cancel }
const cancel = () => { stream?.close(); stream = null; if (start >= 0) messages.value.splice(start, retrying ? 1 : messages.value.length - start); start = -1; retrying = false; status.value = 'disconnected' }
const resend = async ({ content, id, index }) => { if (id && await deleteTestAssistantReply(chatId.value, id)) { messages.value.splice(index + 1, 1); send(content, id, index) } }
onMounted(async () => { await refresh(); conversations.value.length ? await select(conversations.value[0]) : await create() }); onBeforeUnmount(cancel)
</script>
<style scoped>
.test-layout{display:flex;height:100vh;overflow:hidden;background:#fff}.test-layout main{display:flex;min-width:0;flex:1;flex-direction:column}.test-layout header{display:flex;height:64px;align-items:center;justify-content:space-between;padding:0 28px;border-bottom:1px solid #e5e7eb}.test-layout header button{border:0;background:transparent;color:#64748b;font-size:13px}.test-layout header strong{font-size:15px}@media(max-width:720px){.test-layout header{padding:0 16px}}
</style>
