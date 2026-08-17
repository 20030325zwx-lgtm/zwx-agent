<template>
  <div class="chat-container" :class="`chat-${aiType}`">
    <div class="chat-messages" ref="messagesContainer">
      <section v-if="!messages.length" class="empty-state">
        <div class="empty-brand">♡</div>
        <h2>{{ emptyTitle }}</h2>
        <p>{{ emptyDescription }}</p>
        <div class="suggestion-list">
          <button v-for="prompt in quickPrompts" :key="prompt" type="button" @click="usePrompt(prompt)">{{ prompt }}</button>
        </div>
      </section>

      <div v-for="(msg, index) in messages" :key="index" class="message-wrapper" :class="{ user: msg.isUser }">
        <div v-if="!msg.isUser" class="avatar ai-avatar"><AiAvatarFallback :type="aiType" /></div>
        <article class="message" :class="msg.isUser ? 'user-message' : 'ai-message'">
          <div v-if="msg.imageUrls?.length" class="message-images">
            <img v-for="url in msg.imageUrls" :key="url" :src="url" alt="用户上传的图片" />
          </div>
          <details v-if="msg.visionAnalysis" class="vision-analysis">
            <summary>图片识别与资料检索</summary>
            <p>{{ msg.visionAnalysis.summary }}</p>
            <div v-if="msg.visionAnalysis.relationshipSignals?.length" class="vision-signals">
              <span v-for="signal in msg.visionAnalysis.relationshipSignals" :key="signal">{{ signal }}</span>
            </div>
            <small v-if="!msg.visionAnalysis.available">本次图片未用于知识库检索。</small>
            <small v-else-if="msg.visionAnalysis.uncertainItems?.length">待确认：{{ msg.visionAnalysis.uncertainItems.join('；') }}</small>
          </details>
          <div v-if="msg.isUser" class="message-content">
            <textarea v-if="editingIndex === index" v-model="editingContent" class="inline-edit-input" rows="1" aria-label="编辑消息"
              @keydown.enter.exact.prevent="submitEdit(msg, index)" @keydown.esc.prevent="cancelEdit"></textarea>
            <template v-else>{{ msg.content }}</template>
          </div>
          <div v-else class="message-content markdown-content">
            <template v-if="msg.content">
              <div v-html="renderMarkdown(msg.content)"></div>
              <span v-if="connectionStatus === 'connecting' && index === messages.length - 1" class="streaming-pulse" aria-label="正在生成"><i></i><i></i><i></i></span>
            </template>
            <div v-else-if="connectionStatus === 'connecting' && index === messages.length - 1" class="thinking-state">
              <span class="thinking-dots" aria-hidden="true"><i></i><i></i><i></i></span>{{ msg.thinking || '正在思考...' }}
            </div>
          </div>
          <div v-if="msg.activities?.length" class="activity-trace" aria-label="AI 执行状态">
            <span>执行过程</span>
            <button v-for="(activity, activityIndex) in msg.activities" :key="`${activity.label || activity}-${activityIndex}`" type="button" :class="{ active: connectionStatus === 'connecting' && index === messages.length - 1 && activityIndex === msg.activities.length - 1 }" @click="openActivity(activity, msg)">{{ activity.label || activity }}</button>
          </div>
          <div v-if="msg.references?.length" class="message-references">
            <span>参考资料</span>
            <span v-for="reference in msg.references" :key="reference.objectKey">{{ reference.filename }}{{ reference.section ? ` · 第${reference.section}节` : '' }}</span>
          </div>
          <div v-if="msg.trace" class="trace-entry">
            <button type="button" class="trace-button" aria-label="查看本次回答的调用流程">i</button>
            <div class="trace-popover" role="tooltip">
              <strong>本次调用流程</strong>
              <span>1. 接收问题：{{ msg.trace.query }}</span>
              <span>2. 向量检索：Top {{ msg.trace.topK }}，阈值 {{ msg.trace.similarityThreshold }}</span>
              <span>3. {{ msg.trace.decision }}</span>
              <span v-for="candidate in msg.trace.candidates" :key="candidate.objectKey">{{ candidate.filename }}{{ candidate.section ? ` · 第${candidate.section}节` : '' }}（相似度 {{ candidate.score?.toFixed(3) }}）</span>
              <span>4. {{ msg.trace.model }} 结合系统提示词、会话上下文与召回片段流式生成。</span>
            </div>
          </div>
          <time class="message-time">{{ formatTime(msg.time) }}</time>
          <div v-if="msg.isUser && editingIndex !== index" class="message-actions" aria-label="消息操作">
            <button type="button" title="复制消息" aria-label="复制消息" @click="copyMessage(msg.content)">⧉</button>
            <button type="button" title="编辑消息" aria-label="编辑消息" @click="editMessage(msg.content, index)">✎</button>
            <button type="button" title="重新发送" aria-label="重新发送" @click="emit('resend-message', { content: msg.content, id: msg.id, index, imageKeys: msg.imageKeys || [] })">↻</button>
          </div>
        </article>
        <div v-if="msg.isUser" class="avatar user-avatar">我</div>
      </div>
    </div>

    <footer class="chat-input-container">
      <div class="chat-input">
        <input ref="fileInput" class="file-input" type="file" accept="image/jpeg,image/png,image/gif" @change="selectImages" />
        <div v-if="selectedImages.length" class="selected-images">
          <div v-for="attachment in selectedImages" :key="attachment.preview" class="selected-image">
            <img :src="attachment.preview" alt="待发送图片" />
            <button type="button" title="移除图片" @click="removeImage(attachment)">×</button>
          </div>
        </div>
        <textarea v-model="inputMessage" class="input-box"
          :placeholder="inputPlaceholder" @keydown.enter.exact.prevent="sendMessage" @paste="pasteImages"></textarea>
        <div class="input-actions">
          <button v-if="attachmentsEnabled" class="image-button" type="button" title="添加图片" @click="fileInput?.click()">＋</button>
          <span class="input-hint">Enter 发送</span>
          <button class="send-button" type="button" :disabled="!inputMessage.trim() && !selectedImages.length" @click="sendMessage">发送 ↑</button>
        </div>
      </div>
    </footer>
  </div>
</template>

<script setup>
import { computed, ref, onMounted, nextTick, watch } from 'vue'
import DOMPurify from 'dompurify'
import { marked } from 'marked'
import AiAvatarFallback from './AiAvatarFallback.vue'
import { getAgent } from '../config/agents'

const props = defineProps({
  messages: { type: Array, default: () => [] },
  connectionStatus: { type: String, default: 'disconnected' },
  aiType: { type: String, default: 'default' },
  attachmentsEnabled: { type: Boolean, default: false }
})
const emit = defineEmits(['send-message', 'view-execution', 'edit-message', 'resend-message'])
const agent = computed(() => getAgent(props.aiType))
const quickPrompts = computed(() => agent.value.chat.quickPrompts)
const emptyTitle = computed(() => agent.value.chat.emptyTitle)
const emptyDescription = computed(() => agent.value.chat.emptyDescription)
const inputPlaceholder = computed(() => agent.value.chat.inputPlaceholder)
const inputMessage = ref('')
const messagesContainer = ref(null)
const fileInput = ref(null)
const selectedImages = ref([])
const editingIndex = ref(-1)
const editingContent = ref('')

const renderMarkdown = content => DOMPurify.sanitize(marked.parse(content || '', {
  breaks: true,
  gfm: true
}))

const sendMessage = () => {
  if (!inputMessage.value.trim() && !selectedImages.value.length) return
  const payload = { message: inputMessage.value.trim(), files: selectedImages.value.map(item => item.file) }
  emit('send-message', props.attachmentsEnabled ? payload : payload.message)
  inputMessage.value = ''
  clearSelectedImages()
  if (fileInput.value) fileInput.value.value = ''
}
const openActivity = (activity, message) => {
  if (activity?.runId) emit('view-execution', { runId: activity.runId, message })
}
const copyMessage = async content => {
  try { await navigator.clipboard.writeText(content || '') }
  catch (error) { console.error('Copy message failed:', error) }
}
const editMessage = (content, index) => {
  editingContent.value = content || ''
  editingIndex.value = index
  emit('edit-message', { content: editingContent.value })
  nextTick(() => document.querySelector('.inline-edit-input')?.focus())
}
const submitEdit = (message, index) => {
  const content = editingContent.value.trim()
  if (!content) return
  editingIndex.value = -1
  emit('resend-message', { content, id: message.id, index, imageKeys: message.imageKeys || [] })
}
const cancelEdit = () => { editingIndex.value = -1; editingContent.value = '' }
const usePrompt = prompt => { inputMessage.value = prompt; nextTick(() => sendMessage()) }
const selectImages = event => { addImages(Array.from(event.target.files || [])); if (fileInput.value) fileInput.value.value = '' }
const pasteImages = event => {
  if (!props.attachmentsEnabled) return
  const files = Array.from(event.clipboardData?.files || []).filter(file => file.type.startsWith('image/'))
  if (!files.length) return
  event.preventDefault(); addImages(files)
}
const addImages = files => selectedImages.value.push(...files.map(file => ({ file, preview: URL.createObjectURL(file) })))
const removeImage = attachment => { URL.revokeObjectURL(attachment.preview); selectedImages.value = selectedImages.value.filter(item => item !== attachment) }
const clearSelectedImages = () => { selectedImages.value.forEach(item => URL.revokeObjectURL(item.preview)); selectedImages.value = [] }
const formatTime = timestamp => new Date(timestamp).toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })
const scrollToBottom = async () => { await nextTick(); if (messagesContainer.value) messagesContainer.value.scrollTop = messagesContainer.value.scrollHeight }
watch(() => props.messages.length, scrollToBottom)
watch(() => props.messages.map(message => message.content).join(''), scrollToBottom)
watch(() => props.messages.map(message => (message.activities || []).join('|')).join(''), scrollToBottom)
onMounted(scrollToBottom)
</script>

<style scoped>
.chat-container { position: relative; display: flex; min-height: 0; height: 100%; flex-direction: column; background: #fff; }
.chat-messages { position: absolute; inset: 0 0 148px; overflow-y: auto; padding: 42px max(28px, calc((100% - 880px) / 2)); }
.empty-state { display: flex; min-height: 100%; flex-direction: column; align-items: center; justify-content: center; padding-bottom: 80px; }
.empty-brand { display: grid; width: 50px; height: 50px; place-items: center; border-radius: 17px; background: #fff1f4; color: #d64c6b; font-size: 29px; }
.empty-state h2 { margin: 20px 0 8px; font-size: 28px; letter-spacing: 0; }
.empty-state p { margin: 0; color: #919191; font-size: 15px; }
.suggestion-list { display: grid; gap: 10px; width: min(100%, 460px); margin-top: 28px; }
.suggestion-list button { border: 1px solid #e9e9e9; border-radius: 10px; padding: 13px 16px; background: #fff; color: #363636; font-size: 14px; text-align: left; }
.suggestion-list button:hover { border-color: #e3b4c0; background: #fff8f9; }
.message-wrapper { display: flex; align-items: flex-start; gap: 12px; margin-bottom: 28px; }
.message-wrapper.user { justify-content: flex-end; margin-bottom: 54px; }
.avatar { display: grid; width: 34px; height: 34px; flex: 0 0 34px; place-items: center; border-radius: 11px; overflow: hidden; }
.ai-avatar { background: #fff0f4; color: #d65070; }
.user-avatar { background: #252525; color: #fff; font-size: 13px; }
.message { min-width: 0; max-width: min(690px, calc(100% - 50px)); color: #282828; }
.ai-message { padding: 3px 2px; }
.user-message { position:relative; border-radius: 14px 4px 14px 14px; padding: 12px 15px; background: #f3f4f5; }.user-message::after { position:absolute; z-index:0; top:100%; right:0; width:170px; height:50px; content:""; }
.message-content { white-space: pre-wrap; font-size: 16px; line-height: 1.75; }
.inline-edit-input { display:block; width:100%; min-width:260px; resize:none; overflow:hidden; border:0; border-bottom:1px solid var(--zwx-primary); outline:0; padding:0 0 4px; background:transparent; color:inherit; font:inherit; font-size:inherit; line-height:inherit; }
.markdown-content { white-space: normal; }.markdown-content :deep(p) { margin: 0 0 15px; }.markdown-content :deep(p:last-child) { margin-bottom: 0; }.markdown-content :deep(h1), .markdown-content :deep(h2), .markdown-content :deep(h3), .markdown-content :deep(h4) { margin: 22px 0 10px; color: #202020; font-weight: 650; line-height: 1.4; }.markdown-content :deep(h1) { font-size: 22px; }.markdown-content :deep(h2) { font-size: 19px; }.markdown-content :deep(h3), .markdown-content :deep(h4) { font-size: 17px; }.markdown-content :deep(ul), .markdown-content :deep(ol) { margin: 0 0 15px; padding-left: 1.45em; }.markdown-content :deep(li) { margin: 5px 0; }.markdown-content :deep(hr) { height: 1px; margin: 22px 0; border: 0; background: #e7e7e7; }.markdown-content :deep(strong) { color: #202020; font-weight: 700; }.markdown-content :deep(blockquote) { margin: 14px 0; border-left: 3px solid #e5b2bf; padding: 3px 0 3px 13px; color: #676767; }.markdown-content :deep(code) { border-radius: 4px; padding: 2px 4px; background: #f3f3f3; font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: .88em; }.markdown-content :deep(pre) { overflow-x: auto; margin: 14px 0; border-radius: 8px; padding: 12px; background: #272727; color: #f5f5f5; }.markdown-content :deep(pre code) { padding: 0; background: transparent; color: inherit; }.markdown-content :deep(a) { color: #b74461; text-decoration: underline; text-underline-offset: 2px; }
.message-images { display: flex; flex-wrap: wrap; gap: 8px; margin-bottom: 10px; }
.message-images img { display: block; max-width: min(300px, 100%); max-height: 280px; border-radius: 8px; object-fit: cover; }
.vision-analysis { max-width: 500px; margin-top: 9px; border: 1px solid #dbe8f9; border-radius: 8px; padding: 8px 10px; background: #f8fbff; color: #4b6480; font-size: 12px; line-height: 1.55; }.vision-analysis summary { cursor: pointer; color: #246bb2; font-weight: 650; }.vision-analysis p { margin: 8px 0 5px; }.vision-analysis small { display: block; margin-top: 7px; color: #7b8794; }.vision-signals { display: flex; flex-wrap: wrap; gap: 5px; }.vision-signals span { border-radius: 4px; padding: 2px 5px; background: #eaf3ff; color: #3973ad; }
.message-time { display: block; margin-top: 7px; color: #aaa; font-size: 11px; }
.user-message .message-time { text-align: right; }
.message-actions { position:absolute; z-index:1; top:calc(100% + 10px); right:0; display:flex; align-items:center; gap:12px; height:30px; padding:0 2px; opacity:0; pointer-events:none; transform:translateY(-3px); transition:opacity .15s ease,transform .15s ease; }.message-wrapper.user:hover .message-actions,.message-actions:focus-within { opacity:1; pointer-events:auto; transform:translateY(0); }.message-actions button { display:grid; width:26px; height:30px; place-items:center; border:0; border-radius:4px; background:transparent; color:#7d8288; font-size:19px; line-height:1; }.message-actions button:hover { background:#edf0f2; color:#34383d; }
.typing-indicator { display: inline-block; margin-left: 3px; color: #d65070; animation: blink 1s step-end infinite; }
.thinking-state { display: flex; align-items: center; gap: 9px; min-height: 34px; color: #8d5663; font-size: 14px; }.thinking-dots { display: inline-flex; gap: 4px; }.thinking-dots i { display: block; width: 6px; height: 6px; border-radius: 50%; background: currentColor; animation: thinking-pulse 1.1s ease-in-out infinite; }.thinking-dots i:nth-child(2) { animation-delay: .16s; }.thinking-dots i:nth-child(3) { animation-delay: .32s; }.activity-trace { display:flex; flex-wrap:wrap; gap:6px; margin-top:12px; color:#657080; font-size:12px; }.activity-trace > span:first-child { color:#8b95a1; }.activity-trace button { border:1px solid #dfe7e4; border-radius:5px; padding:3px 6px; background:#f8fbfa; color:inherit; font:inherit; text-align:left; }.activity-trace button:hover { border-color:#8fcdb6; background:#effbf5; color:#16794d; }.activity-trace button.active { border-color:#8fcdb6; background:#effbf5; color:#16794d; }
.message-references { display: flex; flex-wrap: wrap; gap: 6px; margin-top: 13px; color: #8d5663; font-size: 12px; }
.message-references span:not(:first-child) { border: 1px solid #efd9de; border-radius: 5px; padding: 3px 6px; background: #fff8f9; }
.trace-entry { position: relative; display: inline-flex; margin-top: 10px; }
.trace-button { width: 20px; height: 20px; border: 1px solid #dedede; border-radius: 50%; background: #fff; color: #777; font-size: 12px; font-weight: 700; line-height: 1; }
.trace-popover { position: absolute; z-index: 4; bottom: 28px; left: 0; display: none; width: min(460px, calc(100vw - 88px)); padding: 12px; border: 1px solid #e2e2e2; border-radius: 8px; background: #fff; box-shadow: 0 12px 30px rgba(0,0,0,.12); color: #4a4a4a; font-size: 12px; line-height: 1.6; }
.trace-popover strong, .trace-popover span { display: block; }.trace-entry:hover .trace-popover, .trace-entry:focus-within .trace-popover { display: block; }
.chat-input-container { position: absolute; right: 0; bottom: 0; left: 0; padding: 14px max(24px, calc((100% - 880px) / 2)); background: linear-gradient(180deg, rgba(255,255,255,0), #fff 24%); }
.chat-input { position: relative; border: 1px solid #e5e7eb; border-radius: 16px; background: #fff; box-shadow: 0 8px 30px rgba(15,23,42,.07); }.chat-input:focus-within { border-color: var(--zwx-primary); box-shadow: 0 0 0 3px rgba(0,111,238,.12), 0 8px 30px rgba(15,23,42,.07); }
.file-input { display: none; }.input-box { display: block; width: 100%; min-height: 78px; max-height: 132px; resize: none; border: 0; outline: 0; padding: 15px 17px 6px; color: #222; font: inherit; font-size: 15px; line-height: 1.55; }.input-box::placeholder { color: #aaa; }
.input-actions { display: flex; height: 43px; align-items: center; gap: 12px; padding: 0 10px 9px 12px; }.image-button { width: 30px; height: 30px; border: 0; border-radius: 7px; background: transparent; color: #555; font-size: 22px; }.image-button:hover { background: #f2f2f2; }.input-hint { margin-right: auto; color: #aaa; font-size: 12px; }.send-button { height: 32px; border: 0; border-radius: 8px; padding: 0 13px; background: var(--zwx-primary); color: #fff; font-size: 13px; font-weight: 650; }.send-button:hover:not(:disabled) { background: var(--zwx-primary-dark); }.send-button:disabled { background: #e9e9e9; color: #aaa; }
.streaming-pulse { display:inline-flex; align-items:center; gap:3px; margin-left:7px; color:#13a37f; vertical-align:middle; }.streaming-pulse i { display:block; width:5px; height:5px; border-radius:50%; background:currentColor; animation:streaming-pulse 1.05s ease-in-out infinite; }.streaming-pulse i:nth-child(2) { animation-delay:.14s; }.streaming-pulse i:nth-child(3) { animation-delay:.28s; }.chat-love .empty-brand, .chat-love .streaming-pulse { color: #d65070; }.chat-love .empty-brand { background: #fff1f4; }.chat-love .send-button { background: #d65070; }.chat-love .send-button:hover:not(:disabled) { background: #bd3d5a; }.chat-love .chat-input:focus-within { border-color: #d65070; box-shadow: 0 0 0 3px rgba(214,80,112,.12), 0 8px 30px rgba(15,23,42,.07); }
.selected-images { position: absolute; z-index: 2; right: 10px; bottom: calc(100% + 8px); display: flex; gap: 7px; max-width: min(420px, 100%); padding: 6px; border: 1px solid #e9e9e9; border-radius: 8px; background: #fff; box-shadow: 0 6px 18px rgba(0,0,0,.1); overflow-x: auto; }.selected-image { position: relative; width: 46px; height: 46px; flex: 0 0 46px; }.selected-image img { width: 100%; height: 100%; border-radius: 6px; object-fit: cover; }.selected-image button { position: absolute; top: -5px; right: -5px; display: grid; width: 17px; height: 17px; place-items: center; border: 1px solid #fff; border-radius: 50%; background: #333; color: #fff; line-height: 1; }
@keyframes thinking-pulse { 0%, 70%, 100% { opacity: .25; transform: translateY(0); } 35% { opacity: 1; transform: translateY(-3px); } } @keyframes streaming-pulse { 0%, 70%, 100% { opacity:.24; transform:translateY(0) scale(.82); } 35% { opacity:1; transform:translateY(-3px) scale(1); } }
@media (prefers-reduced-motion: reduce) { .streaming-pulse i,.thinking-dots i { animation:none; opacity:.75; } }
@media (max-width: 720px) { .chat-messages { inset-bottom: 138px; padding: 25px 16px; }.chat-input-container { padding: 10px 12px; }.empty-state { padding-bottom: 40px; }.empty-state h2 { font-size: 23px; }.message-content { font-size: 15px; }.input-hint { display: none; } }
</style>
