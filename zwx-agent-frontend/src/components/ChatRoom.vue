<template>
  <div class="chat-container">
    <div class="chat-messages" ref="messagesContainer">
      <section v-if="!messages.length" class="empty-state">
        <div class="empty-brand">♡</div>
        <h2>想从哪段关系开始分析？</h2>
        <p>可以描述经历、粘贴聊天记录，或上传截图。</p>
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
          <div v-if="msg.isUser" class="message-content">
            {{ msg.content }}<span v-if="connectionStatus === 'connecting' && index === messages.length - 1" class="typing-indicator">▋</span>
          </div>
          <div v-else class="message-content markdown-content">
            <div v-html="renderMarkdown(msg.content)"></div>
            <span v-if="connectionStatus === 'connecting' && index === messages.length - 1" class="typing-indicator">▋</span>
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
        <textarea v-model="inputMessage" class="input-box" :disabled="connectionStatus === 'connecting'"
          placeholder="描述你的困扰，或上传聊天截图..." @keydown.enter.exact.prevent="sendMessage" @paste="pasteImages"></textarea>
        <div class="input-actions">
          <button v-if="attachmentsEnabled" class="image-button" type="button" title="添加图片" :disabled="connectionStatus === 'connecting'" @click="fileInput?.click()">＋</button>
          <span class="input-hint">Enter 发送</span>
          <button class="send-button" type="button" :disabled="connectionStatus === 'connecting' || (!inputMessage.trim() && !selectedImages.length)" @click="sendMessage">发送 ↑</button>
        </div>
      </div>
    </footer>
  </div>
</template>

<script setup>
import { ref, onMounted, nextTick, watch } from 'vue'
import DOMPurify from 'dompurify'
import { marked } from 'marked'
import AiAvatarFallback from './AiAvatarFallback.vue'

const props = defineProps({
  messages: { type: Array, default: () => [] },
  connectionStatus: { type: String, default: 'disconnected' },
  aiType: { type: String, default: 'default' },
  attachmentsEnabled: { type: Boolean, default: false }
})
const emit = defineEmits(['send-message'])
const quickPrompts = ['帮我分析这段关系是否健康', '聊天总是冷场，该怎么改善？', '他/她的这句话是什么意思？']
const inputMessage = ref('')
const messagesContainer = ref(null)
const fileInput = ref(null)
const selectedImages = ref([])

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
.message-wrapper.user { justify-content: flex-end; }
.avatar { display: grid; width: 34px; height: 34px; flex: 0 0 34px; place-items: center; border-radius: 11px; overflow: hidden; }
.ai-avatar { background: #fff0f4; color: #d65070; }
.user-avatar { background: #252525; color: #fff; font-size: 13px; }
.message { min-width: 0; max-width: min(690px, calc(100% - 50px)); color: #282828; }
.ai-message { padding: 3px 2px; }
.user-message { border-radius: 14px 4px 14px 14px; padding: 12px 15px; background: #f3f4f5; }
.message-content { white-space: pre-wrap; font-size: 16px; line-height: 1.75; }
.markdown-content { white-space: normal; }.markdown-content :deep(p) { margin: 0 0 15px; }.markdown-content :deep(p:last-child) { margin-bottom: 0; }.markdown-content :deep(h1), .markdown-content :deep(h2), .markdown-content :deep(h3), .markdown-content :deep(h4) { margin: 22px 0 10px; color: #202020; font-weight: 650; line-height: 1.4; }.markdown-content :deep(h1) { font-size: 22px; }.markdown-content :deep(h2) { font-size: 19px; }.markdown-content :deep(h3), .markdown-content :deep(h4) { font-size: 17px; }.markdown-content :deep(ul), .markdown-content :deep(ol) { margin: 0 0 15px; padding-left: 1.45em; }.markdown-content :deep(li) { margin: 5px 0; }.markdown-content :deep(hr) { height: 1px; margin: 22px 0; border: 0; background: #e7e7e7; }.markdown-content :deep(strong) { color: #202020; font-weight: 700; }.markdown-content :deep(blockquote) { margin: 14px 0; border-left: 3px solid #e5b2bf; padding: 3px 0 3px 13px; color: #676767; }.markdown-content :deep(code) { border-radius: 4px; padding: 2px 4px; background: #f3f3f3; font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: .88em; }.markdown-content :deep(pre) { overflow-x: auto; margin: 14px 0; border-radius: 8px; padding: 12px; background: #272727; color: #f5f5f5; }.markdown-content :deep(pre code) { padding: 0; background: transparent; color: inherit; }.markdown-content :deep(a) { color: #b74461; text-decoration: underline; text-underline-offset: 2px; }
.message-images { display: flex; flex-wrap: wrap; gap: 8px; margin-bottom: 10px; }
.message-images img { display: block; max-width: min(300px, 100%); max-height: 280px; border-radius: 8px; object-fit: cover; }
.message-time { display: block; margin-top: 7px; color: #aaa; font-size: 11px; }
.user-message .message-time { text-align: right; }
.typing-indicator { display: inline-block; margin-left: 3px; color: #d65070; animation: blink 1s step-end infinite; }
.message-references { display: flex; flex-wrap: wrap; gap: 6px; margin-top: 13px; color: #8d5663; font-size: 12px; }
.message-references span:not(:first-child) { border: 1px solid #efd9de; border-radius: 5px; padding: 3px 6px; background: #fff8f9; }
.trace-entry { position: relative; display: inline-flex; margin-top: 10px; }
.trace-button { width: 20px; height: 20px; border: 1px solid #dedede; border-radius: 50%; background: #fff; color: #777; font-size: 12px; font-weight: 700; line-height: 1; }
.trace-popover { position: absolute; z-index: 4; bottom: 28px; left: 0; display: none; width: min(460px, calc(100vw - 88px)); padding: 12px; border: 1px solid #e2e2e2; border-radius: 8px; background: #fff; box-shadow: 0 12px 30px rgba(0,0,0,.12); color: #4a4a4a; font-size: 12px; line-height: 1.6; }
.trace-popover strong, .trace-popover span { display: block; }.trace-entry:hover .trace-popover, .trace-entry:focus-within .trace-popover { display: block; }
.chat-input-container { position: absolute; right: 0; bottom: 0; left: 0; padding: 14px max(24px, calc((100% - 880px) / 2)); background: linear-gradient(180deg, rgba(255,255,255,0), #fff 24%); }
.chat-input { position: relative; border: 1px solid #e5e5e5; border-radius: 18px; background: #fff; box-shadow: 0 8px 30px rgba(0,0,0,.06); }
.file-input { display: none; }.input-box { display: block; width: 100%; min-height: 78px; max-height: 132px; resize: none; border: 0; outline: 0; padding: 15px 17px 6px; color: #222; font: inherit; font-size: 15px; line-height: 1.55; }.input-box::placeholder { color: #aaa; }
.input-actions { display: flex; height: 43px; align-items: center; gap: 12px; padding: 0 10px 9px 12px; }.image-button { width: 30px; height: 30px; border: 0; border-radius: 7px; background: transparent; color: #555; font-size: 22px; }.image-button:hover { background: #f2f2f2; }.input-hint { margin-right: auto; color: #aaa; font-size: 12px; }.send-button { height: 30px; border: 0; border-radius: 8px; padding: 0 12px; background: #222; color: #fff; font-size: 13px; }.send-button:disabled { background: #e9e9e9; color: #aaa; }
.selected-images { position: absolute; z-index: 2; right: 10px; bottom: calc(100% + 8px); display: flex; gap: 7px; max-width: min(420px, 100%); padding: 6px; border: 1px solid #e9e9e9; border-radius: 8px; background: #fff; box-shadow: 0 6px 18px rgba(0,0,0,.1); overflow-x: auto; }.selected-image { position: relative; width: 46px; height: 46px; flex: 0 0 46px; }.selected-image img { width: 100%; height: 100%; border-radius: 6px; object-fit: cover; }.selected-image button { position: absolute; top: -5px; right: -5px; display: grid; width: 17px; height: 17px; place-items: center; border: 1px solid #fff; border-radius: 50%; background: #333; color: #fff; line-height: 1; }
@keyframes blink { 50% { opacity: 0; } }
@media (max-width: 720px) { .chat-messages { inset-bottom: 138px; padding: 25px 16px; }.chat-input-container { padding: 10px 12px; }.empty-state { padding-bottom: 40px; }.empty-state h2 { font-size: 23px; }.message-content { font-size: 15px; }.input-hint { display: none; } }
</style>
