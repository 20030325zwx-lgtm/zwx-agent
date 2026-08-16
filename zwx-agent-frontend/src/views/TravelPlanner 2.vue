<template>
  <main class="travel-page">
    <header class="travel-header">
      <button class="back-button" type="button" @click="router.push('/')">‹ 智能体目录</button>
      <div class="title"><span>⌖</span><div><strong>旅游规划专家</strong><small>行程、地点与实时信息</small></div></div>
      <span :class="connectionStatus" class="status">{{ statusText }}</span>
    </header>
    <section class="travel-chat"><ChatRoom :messages="messages" :connection-status="connectionStatus" ai-type="travel" @send-message="sendMessage" /></section>
  </main>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useHead } from '@vueuse/head'
import ChatRoom from '../components/ChatRoom.vue'
import { chatWithTravelPlanner } from '../api'

useHead({ title: '旅游规划专家 - ZWX Agent' })
const router = useRouter()
const messages = ref([])
const connectionStatus = ref('disconnected')
let eventSource = null
const statusText = computed(() => connectionStatus.value === 'connecting' ? '正在规划' : connectionStatus.value === 'error' ? '连接异常' : '在线')
const addMessage = (content, isUser) => messages.value.push({ content, isUser, time: Date.now() })
const sendMessage = message => {
  addMessage(message, true); eventSource?.close(); addMessage('', false)
  const answerIndex = messages.value.length - 1
  connectionStatus.value = 'connecting'; eventSource = chatWithTravelPlanner(message)
  eventSource.onmessage = event => {
    if (event.data === '[DONE]') { connectionStatus.value = 'disconnected'; eventSource?.close(); eventSource = null; return }
    if (messages.value[answerIndex]) messages.value[answerIndex].content += event.data
  }
  eventSource.onerror = () => { connectionStatus.value = 'error'; if (!messages.value[answerIndex].content) messages.value[answerIndex].content = '暂时无法完成规划，请稍后重试。'; eventSource?.close(); eventSource = null }
}
onMounted(() => addMessage('你好，我是旅游规划专家。告诉我出发地、目的地、日期、人数、预算和偏好，我会为你安排旅行。', false))
onBeforeUnmount(() => eventSource?.close())
</script>

<style scoped>
.travel-page { display:flex; height:100vh; flex-direction:column; background:#f7f8fa; }.travel-header { display:grid; height:72px; flex:0 0 72px; grid-template-columns:1fr auto 1fr; align-items:center; padding:0 28px; border-bottom:1px solid var(--zwx-divider); background:#fff; }.back-button { justify-self:start; border:0; background:transparent; color:var(--zwx-muted); font-size:13px; }.title { display:flex; align-items:center; gap:9px; }.title > span { display:grid; width:34px; height:34px; place-items:center; border-radius:10px; background:#ecfdf5; color:#0f9f6e; font-size:18px; }.title div { display:grid; gap:2px; }.title strong { font-size:14px; }.title small,.status { color:var(--zwx-muted); font-size:11px; }.status { justify-self:end; color:#13a37f; }.status.connecting { color:#0f9f6e; }.status.error { color:#d14343; }.travel-chat { min-height:0; flex:1; margin:18px; border:1px solid var(--zwx-divider); border-radius:var(--zwx-radius-md); background:#fff; box-shadow:var(--zwx-shadow); overflow:hidden; } @media(max-width:720px){.travel-header{height:62px;flex-basis:62px;padding:0 14px}.travel-chat{margin:0;border:0;border-radius:0;box-shadow:none}}
</style>
