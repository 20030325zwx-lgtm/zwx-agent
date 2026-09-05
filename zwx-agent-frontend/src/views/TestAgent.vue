<template>
  <div class="test-layout">
    <ConversationSidebar title="功能测试助手" mark="✓" theme="test" :conversations="conversations" :active-id="chatId" :loading="loading" :open="sidebarOpen" @create="create" @select="select" @delete="remove" @close="sidebarOpen=false" />
    <main><header><button @click="sidebarOpen=true">☰</button><strong>功能测试助手</strong><AgentSettingsMenu agent-key="test" default-theme="blue" /></header><ChatRoom :messages="messages" :connection-status="status" ai-type="test" attachments-enabled attachment-accept=".md,.txt,.pdf,.doc,.docx,.xls,.xlsx,.ppt,.pptx" web-search-available @send-message="send" @edit-message="cancel" @resend-message="resend" /></main>
  </div>
</template>
<script setup>
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import ChatRoom from '../components/ChatRoom.vue'
import AgentSettingsMenu from '../components/AgentSettingsMenu.vue'
import ConversationSidebar from '../components/ConversationSidebar.vue'
import { chatWithTestAgent, createTestConversation, deleteTestAssistantReply, deleteTestConversation, getTestConversationMessages, listTestConversations, uploadAgentKnowledgeDocument } from '../api'
const router = useRouter(); const messages = ref([]); const conversations = ref([]); const chatId = ref(''); const status = ref('disconnected'); const loading = ref(false); const sidebarOpen = ref(false)
let stream = null; let start = -1; let retrying = false
const refresh = async () => { loading.value = true; try { conversations.value = await listTestConversations() } finally { loading.value = false } }
const create = async () => { if (status.value === 'connecting') return; const item = await createTestConversation(); await refresh(); chatId.value = item.id; messages.value = []; sidebarOpen.value = false }
const select = async item => { if (status.value === 'connecting') return; chatId.value = item.id; sidebarOpen.value = false; messages.value = (await getTestConversationMessages(item.id)).map(message => ({ id: message.id, content: message.content, isUser: message.role === 'USER', time: new Date(message.createdAt).getTime(), activities: [] })) }
const remove = async item => { await deleteTestConversation(item.id); await refresh(); if (chatId.value === item.id) conversations.value.length ? await select(conversations.value[0]) : await create() }
const send = async (payload, retryId = null, retryIndex = -1) => { const content = typeof payload === 'string' ? payload : payload.message; const webSearch = typeof payload === 'string' ? false : payload.webSearch; const files = typeof payload === 'string' ? [] : payload.files || []; if (!chatId.value) return; if (files.length) await Promise.all(files.map(file => uploadAgentKnowledgeDocument('test', file))); cancel(); start = retryId ? retryIndex + 1 : messages.value.length; retrying = !!retryId; if (!retryId) messages.value.push({ content, isUser: true, time: Date.now(), activities: [] }); const answerIndex = retryId ? retryIndex + 1 : messages.value.length; messages.value.splice(answerIndex, 0, { content: '', isUser: false, time: Date.now(), activities: [] }); status.value = 'connecting'; const requestId = crypto.randomUUID ? crypto.randomUUID() : `req-${Date.now()}-${Math.random().toString(36).slice(2)}`; stream = chatWithTestAgent(chatId.value, content, webSearch, retryId, requestId); stream.onmessage = async event => { if (event.data === '[DONE]') { status.value = 'disconnected'; stream?.close(); stream = null; start = -1; await refresh(); return }; messages.value[answerIndex].content += event.data }; stream.onerror = () => { stream?.close(); stream = null; start = -1; retrying = false; status.value = 'error'; if (!messages.value[answerIndex].content) messages.value[answerIndex].content = '连接中断，正在恢复已生成的内容...'; setTimeout(async () => { status.value = 'disconnected'; try { await refresh() } catch (error) { console.error(error) } }, 1500) } }
const cancel = () => { stream?.close(); stream = null; start = -1; retrying = false; status.value = 'disconnected' }
const resend = async ({ content, id, index }) => { if (id && await deleteTestAssistantReply(chatId.value, id)) { messages.value.splice(index + 1, 1); send(content, id, index) } }
onMounted(async () => { await refresh(); conversations.value.length ? await select(conversations.value[0]) : await create() }); onBeforeUnmount(cancel)
</script>
<style scoped>
.test-layout { display: flex; height: 100vh; overflow: hidden; background: var(--sk-bg) }
.test-layout main { display: flex; min-width: 0; flex: 1; flex-direction: column }
.test-layout header { display: flex; height: 60px; flex: 0 0 60px; align-items: center; justify-content: space-between; padding: 0 24px; border-bottom: 1px solid var(--sk-separator); background: var(--sk-material); backdrop-filter: var(--sk-blur); -webkit-backdrop-filter: var(--sk-blur) }
.test-layout header button { border: 0; background: var(--sk-fill); color: var(--sk-label); border-radius: 9px; width: 32px; height: 32px; font-size: 16px }
.test-layout header button:hover { background: var(--sk-fill-strong) }
.test-layout header strong { font-size: 15px; font-weight: 700; letter-spacing: -0.01em }
@media (max-width: 720px) { .test-layout header { padding: 0 16px } }
</style>
