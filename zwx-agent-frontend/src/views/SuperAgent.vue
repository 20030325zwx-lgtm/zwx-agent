<template>
  <main class="super-agent-page">
    <header class="super-header">
      <button class="back-button" type="button" @click="goBack"><span aria-hidden="true">‹</span> 智能体目录</button>
      <div class="agent-title"><span class="agent-symbol" aria-hidden="true">✦</span><div><strong>超级智能体</strong><small>通用任务协作</small></div></div>
      <span class="status" :class="connectionStatus">{{ statusText }}</span>
    </header>
    <section class="super-chat"><ChatRoom :messages="messages" :connection-status="connectionStatus" ai-type="super" @send-message="sendMessage" @edit-message="cancelActiveStream" @resend-message="resendMessage" /></section>
  </main>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useHead } from '@vueuse/head'
import ChatRoom from '../components/ChatRoom.vue'
import { chatWithManus } from '../api'

useHead({ title: '超级智能体 - ZWX Agent', meta: [{ name: 'description', content: 'ZWX Agent 的通用任务协作助手。' }] })
const router = useRouter()
const messages = ref([])
const connectionStatus = ref('disconnected')
let eventSource = null
let activeTurnStart = -1
const statusText = computed(() => connectionStatus.value === 'connecting' ? '正在回复' : connectionStatus.value === 'error' ? '连接异常' : '在线')
const addMessage = (content, isUser) => messages.value.push({ content, isUser, time: Date.now() })
const sendMessage = message => {
  cancelActiveStream()
  activeTurnStart = messages.value.length
  addMessage(message, true)
  addMessage('', false)
  const answerIndex = messages.value.length - 1
  connectionStatus.value = 'connecting'
  eventSource = chatWithManus(message)
  eventSource.onmessage = event => {
    if (event.data === '[DONE]') { connectionStatus.value = 'disconnected'; eventSource?.close(); eventSource = null; activeTurnStart = -1; return }
    if (event.data && messages.value[answerIndex]) messages.value[answerIndex].content += event.data
  }
  eventSource.onerror = () => {
    cancelActiveStream()
  }
}
const cancelActiveStream = () => {
  if (!eventSource && connectionStatus.value !== 'connecting') return
  eventSource?.close()
  eventSource = null
  if (activeTurnStart >= 0) messages.value.splice(activeTurnStart)
  activeTurnStart = -1
  connectionStatus.value = 'disconnected'
}
const resendMessage = ({ content }) => sendMessage(content)
const goBack = () => router.push('/')
onMounted(() => addMessage('你好，我是超级智能体。告诉我你的目标，我会帮你把它拆解为清晰、可执行的下一步。', false))
onBeforeUnmount(cancelActiveStream)
</script>

<style scoped>
.super-agent-page { display: flex; height: 100vh; flex-direction: column; background: #f7f8fa; }
.super-header { display: grid; height: 72px; flex: 0 0 72px; grid-template-columns: 1fr auto 1fr; align-items: center; padding: 0 28px; border-bottom: 1px solid var(--zwx-divider); background: rgba(255,255,255,.96); }
.back-button { justify-self: start; border: 0; background: transparent; color: var(--zwx-muted); font-size: 13px; }.back-button:hover { color: var(--zwx-primary); }.back-button span { font-size: 25px; line-height: 0; vertical-align: -2px; }
.agent-title { display: flex; align-items: center; gap: 9px; }.agent-symbol { display: grid; width: 34px; height: 34px; place-items: center; border-radius: 10px; background: #eaf3ff; color: var(--zwx-primary); font-size: 17px; }.agent-title div { display: grid; gap: 2px; }.agent-title strong { font-size: 14px; }.agent-title small { color: var(--zwx-muted); font-size: 11px; }.status { justify-self: end; color: #13a37f; font-size: 12px; }.status.connecting { color: var(--zwx-primary); }.status.error { color: #d14343; }
.super-chat { min-height: 0; flex: 1; margin: 18px; border: 1px solid var(--zwx-divider); border-radius: var(--zwx-radius-md); background: #fff; box-shadow: var(--zwx-shadow); overflow: hidden; }
@media (max-width: 720px) { .super-header { height: 62px; flex-basis: 62px; padding: 0 14px; }.back-button { font-size: 0; }.back-button span { font-size: 29px; }.super-chat { margin: 0; border: 0; border-radius: 0; box-shadow: none; } }
</style>
