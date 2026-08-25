<template>
  <div class="super-layout">
    <main class="super-main">
      <header class="super-header">
        <div class="header-start">
          <button class="back-button" type="button" @click="goBack">智能体目录</button>
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

      <section class="super-chat">
        <ChatRoom :messages="messages" :connection-status="connectionStatus" ai-type="super"
          @send-message="sendMessage" @edit-message="cancelActiveStream" @resend-message="resendMessage" />
      </section>
    </main>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useHead } from '@vueuse/head'
import ChatRoom from '../components/ChatRoom.vue'
import { chatWithManus } from '../api'

useHead({ title: '超级智能体 - ZWX Agent', meta: [{ name: 'description', content: 'ZWX Agent 的自主智能体，可规划任务、调用工具并多步执行。' }] })
const router = useRouter()
const messages = ref([])
const connectionStatus = ref('disconnected')
let eventSource = null
let activeTurnStart = -1
const statusText = computed(() => connectionStatus.value === 'connecting' ? '正在执行' : connectionStatus.value === 'error' ? '连接异常' : '就绪')
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
    if (event.data === '[DONE]') { finishStream(); return }
    if (event.data && messages.value[answerIndex]) messages.value[answerIndex].content += event.data
  }
  eventSource.onerror = () => { cancelActiveStream() }
}
const finishStream = () => {
  connectionStatus.value = 'disconnected'
  eventSource?.close()
  eventSource = null
  activeTurnStart = -1
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
onMounted(() => addMessage('你好，我是超级智能体。告诉我你的目标，我会自主规划步骤、按需调用工具，并逐步推进直到完成。', false))
onBeforeUnmount(cancelActiveStream)
</script>

<style scoped>
.super-layout { display: flex; height: 100vh; overflow: hidden; background: #f7f8fa; }
.super-main { display: flex; min-width: 0; min-height: 0; flex: 1; flex-direction: column; overflow: hidden; }
.super-header { display: flex; height: 64px; flex: 0 0 64px; align-items: center; justify-content: space-between; padding: 0 28px; border-bottom: 1px solid var(--zwx-divider); background: rgba(255,255,255,.96); }
.header-start { display: flex; width: 180px; align-items: center; }
.back-button { border: 0; background: transparent; color: var(--zwx-muted); font-size: 13px; }.back-button:hover { color: var(--zwx-primary); }
.title { display: flex; align-items: center; gap: 9px; font-size: 15px; }.super-mark { display: grid; width: 30px; height: 30px; place-items: center; border-radius: 8px; background: var(--zwx-primary-soft); color: var(--zwx-primary); font-size: 17px; }.kind-pill { border: 1px solid #cfe0fa; border-radius: 5px; padding: 2px 6px; background: #eaf3ff; color: #246bb2; font-size: 10px; font-style: normal; }
.header-end { display: flex; width: 180px; justify-content: flex-end; }
.status { display: flex; align-items: center; gap: 6px; color: #13a37f; font-size: 12px; }.status::before { content: ""; width: 7px; height: 7px; border-radius: 50%; background: currentColor; }.status.connecting { color: var(--zwx-primary); }.status.connecting::before { animation: status-blink 1.05s ease-in-out infinite; }.status.error { color: #d14343; }
.super-chat { position: relative; min-height: 0; flex: 1; margin: 18px; border: 1px solid var(--zwx-divider); border-radius: var(--zwx-radius-md); background: #fff; box-shadow: var(--zwx-shadow); overflow: hidden; }
@keyframes status-blink { 0%, 70%, 100% { opacity: .3; } 35% { opacity: 1; } }
@media (prefers-reduced-motion: reduce) { .status.connecting::before { animation: none; opacity: .5; } }
@media (max-width: 720px) { .super-header { height: 62px; flex-basis: 62px; padding: 0 14px; }.header-start, .header-end { width: auto; }.super-chat { margin: 0; border: 0; border-radius: 0; box-shadow: none; } }
</style>
