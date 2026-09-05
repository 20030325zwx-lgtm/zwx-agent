<template>
  <div class="chat-container" :class="`chat-${aiType}`">
    <div class="chat-messages" ref="messagesContainer">
      <section v-if="!messages.length" class="empty-state">
        <div class="empty-brand">{{ agent.icon }}</div>
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
          <details v-if="msg.collapsibleActivities && msg.activities?.length" class="activity-trace activity-trace-collapsible" aria-label="AI 执行过程">
            <summary>{{ activitySummary(msg.activities) }}</summary>
            <div class="activity-details">
              <details v-for="(activity, activityIndex) in msg.activities" :key="`${activity.label || activity}-${activityIndex}`">
                <summary>{{ activity.label || `步骤 ${activityIndex + 1}` }}</summary>
                <pre>{{ activity.detail || activity }}</pre>
              </details>
            </div>
          </details>
          <div v-else-if="msg.activities?.length" class="activity-trace" aria-label="AI 执行状态">
            <span>执行过程</span>
            <button v-for="(activity, activityIndex) in msg.activities" :key="`${activity.label || activity}-${activityIndex}`" type="button" :class="{ active: connectionStatus === 'connecting' && index === messages.length - 1 && activityIndex === msg.activities.length - 1 }" @click="openActivity(activity, msg)">{{ activity.label || activity }}</button>
          </div>
          <div v-if="msg.files?.length" class="message-files" aria-label="生成的文件">
            <a v-for="file in msg.files" :key="file.path" :href="file.url || undefined" target="_blank" rel="noopener noreferrer">
              <FileText :size="16" />
              <span>{{ file.name }}</span>
              <small v-if="file.type === 'pdf'">PDF</small>
            </a>
          </div>
          <div v-if="msg.references?.length" class="message-references">
            <span>参考资料</span>
            <details v-for="reference in msg.references" :key="`${reference.objectKey}-${reference.chunkIndex || reference.section || reference.filename}`" class="reference-card">
              <summary>{{ reference.filename }}{{ reference.section ? ` · 第${reference.section}节` : reference.chunkIndex ? ` · 切片 ${reference.chunkIndex}` : '' }}</summary>
              <p>{{ reference.excerpt || '该历史引用未保存切片摘录。' }}</p>
            </details>
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
          <div v-if="!msg.isUser && msg.status === 'INTERRUPTED' && connectionStatus !== 'connecting'" class="continue-row">
            <span class="continue-hint">回答被中断，已保留部分内容</span>
            <button type="button" class="continue-button" @click="emit('continue-message', { message: msg, index })">继续生成 ▸</button>
          </div>
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
        <input ref="fileInput" class="file-input" type="file" :accept="attachmentAccept" multiple @change="selectImages" />
        <div v-if="selectedImages.length" class="selected-images">
          <div v-for="attachment in selectedImages" :key="attachment.preview" class="selected-image">
            <img v-if="attachment.preview" :src="attachment.preview" alt="待发送图片" />
            <span v-else class="selected-file-name">{{ attachment.file.name }}</span>
            <button type="button" title="移除图片" @click="removeImage(attachment)">×</button>
          </div>
        </div>
        <textarea v-model="inputMessage" class="input-box"
          :placeholder="inputPlaceholder" @keydown.enter.exact.prevent="sendMessage" @paste="pasteImages"></textarea>
        <div class="input-actions">
          <button v-if="attachmentsEnabled" class="tool-button" type="button" title="上传图片" aria-label="上传图片" @click="fileInput?.click()"><Paperclip :size="18" /></button>
          <button v-if="webSearchAvailable" class="tool-button web-search-toggle" :class="{ active: webSearch }" type="button" :aria-pressed="webSearch" :title="webSearch ? '本轮允许联网搜索' : '本轮不联网搜索'" aria-label="联网搜索" @click="webSearch = !webSearch"><Globe2 :size="18" /></button>
          <button v-if="knowledgeSearchAvailable" class="tool-button knowledge-search-toggle" :class="{ active: knowledgeSearch }" type="button" :aria-pressed="knowledgeSearch" :title="knowledgeSearch ? '本轮先检索知识库' : '本轮不检索知识库'" aria-label="检索知识库" @click="knowledgeSearch = !knowledgeSearch"><Database :size="18" /></button>
          <span class="input-hint">Enter 发送</span>
          <button class="send-button" type="button" :disabled="!inputMessage.trim() && !selectedImages.length" aria-label="发送" @click="sendMessage">↑</button>
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
import { Database, FileText, Globe2, Paperclip } from 'lucide-vue-next'
import { getAgent } from '../config/agents'

const props = defineProps({
  messages: { type: Array, default: () => [] },
  connectionStatus: { type: String, default: 'disconnected' },
  aiType: { type: String, default: 'default' },
  attachmentsEnabled: { type: Boolean, default: false },
  attachmentAccept: { type: String, default: 'image/jpeg,image/png,image/gif' },
  webSearchAvailable: { type: Boolean, default: false },
  knowledgeSearchAvailable: { type: Boolean, default: false }
})
const emit = defineEmits(['send-message', 'view-execution', 'edit-message', 'resend-message', 'continue-message'])
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
const webSearch = ref(false)
const knowledgeSearch = ref(false)

const renderMarkdown = content => DOMPurify.sanitize(marked.parse(content || '', {
  breaks: true,
  gfm: true
}))

const sendMessage = () => {
  if (!inputMessage.value.trim() && !selectedImages.value.length) return
  const payload = { message: inputMessage.value.trim(), files: selectedImages.value.map(item => item.file) }
  if (props.webSearchAvailable) payload.webSearch = webSearch.value
  if (props.knowledgeSearchAvailable) payload.knowledgeSearch = knowledgeSearch.value
  emit('send-message', props.attachmentsEnabled || props.webSearchAvailable || props.knowledgeSearchAvailable ? payload : payload.message)
  inputMessage.value = ''
  clearSelectedImages()
  if (fileInput.value) fileInput.value.value = ''
}
const openActivity = (activity, message) => {
  if (activity?.runId) emit('view-execution', { runId: activity.runId, message })
}
const activitySummary = activities => activities.at(-1)?.label || `已完成 ${activities.length} 项操作`
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
const addImages = files => selectedImages.value.push(...files.map(file => ({ file, preview: file.type.startsWith('image/') ? URL.createObjectURL(file) : '' })))
const removeImage = attachment => { if (attachment.preview) URL.revokeObjectURL(attachment.preview); selectedImages.value = selectedImages.value.filter(item => item !== attachment) }
const clearSelectedImages = () => { selectedImages.value.forEach(item => { if (item.preview) URL.revokeObjectURL(item.preview) }); selectedImages.value = [] }
const formatTime = timestamp => new Date(timestamp).toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })
const scrollToBottom = async () => { await nextTick(); if (messagesContainer.value) messagesContainer.value.scrollTop = messagesContainer.value.scrollHeight }
watch(() => props.messages.length, scrollToBottom)
watch(() => props.messages.map(message => message.content).join(''), scrollToBottom)
watch(() => props.messages.map(message => (message.activities || []).join('|')).join(''), scrollToBottom)
onMounted(scrollToBottom)
</script>

<style scoped>
.chat-container {
  position: relative;
  display: flex;
  min-height: 0;
  height: 100%;
  flex-direction: column;
  background: transparent;
}

.chat-messages {
  position: absolute;
  inset: 0 0 150px;
  overflow-y: auto;
  padding: 40px max(28px, calc((100% - 880px) / 2));
}

/* ── 空状态 ─────────────────────────────────────────── */
.empty-state {
  display: flex;
  min-height: 100%;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  padding-bottom: 80px;
}

.empty-brand {
  display: grid;
  width: 62px;
  height: 62px;
  place-items: center;
  border-radius: 20px;
  background: var(--zwx-primary-soft);
  color: var(--zwx-primary);
  font-size: 32px;
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.6), var(--sk-shadow-card);
}

.empty-state h2 {
  margin: 22px 0 8px;
  font-size: 28px;
  font-weight: 800;
  letter-spacing: -0.02em;
}

.empty-state p { margin: 0; color: var(--sk-label-2); font-size: 15px; }

.suggestion-list { display: grid; gap: 10px; width: min(100%, 440px); margin-top: 30px; }

.suggestion-list button {
  border: 1px solid var(--sk-separator);
  border-radius: 14px;
  padding: 13px 16px;
  background: var(--sk-surface);
  color: var(--sk-label);
  font-size: 14px;
  text-align: left;
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.03);
}

.suggestion-list button:hover {
  border-color: transparent;
  box-shadow: var(--sk-shadow-card);
  transform: translateY(-1px);
  color: var(--zwx-primary);
}

/* ── 消息布局（iMessage 风格） ─────────────────────── */
.message-wrapper { display: flex; align-items: flex-start; gap: 12px; margin-bottom: 26px; }
.message-wrapper.user { justify-content: flex-end; margin-bottom: 46px; }

.avatar {
  display: grid;
  width: 34px;
  height: 34px;
  flex: 0 0 34px;
  place-items: center;
  border-radius: 12px;
  overflow: hidden;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.12);
}

.ai-avatar { background: var(--sk-surface); }

.user-avatar {
  background: linear-gradient(160deg, #aeb6c2, #7d8794);
  color: #fff;
  font-size: 13px;
  font-weight: 600;
}

.message { min-width: 0; max-width: min(690px, calc(100% - 50px)); color: var(--sk-label); }
.ai-message { padding: 3px 2px; }

/* 用户气泡：主题色渐变（iMessage 蓝，随主题联动） */
.user-message {
  position: relative;
  border-radius: 20px 20px 6px 20px;
  padding: 11px 16px;
  background: linear-gradient(180deg, color-mix(in srgb, var(--zwx-primary) 88%, #ffffff), var(--zwx-primary));
  color: #fff;
  box-shadow: 0 3px 10px var(--zwx-primary-ring);
}

.user-message::after {
  position: absolute;
  z-index: 0;
  top: 100%;
  right: 0;
  width: 170px;
  height: 50px;
  content: "";
}

.message-content { white-space: pre-wrap; font-size: 16px; line-height: 1.7; }

.inline-edit-input {
  display: block;
  width: 100%;
  min-width: 260px;
  resize: none;
  overflow: hidden;
  border: 0;
  border-bottom: 1px solid rgba(255, 255, 255, 0.7);
  outline: 0;
  padding: 0 0 4px;
  background: transparent;
  color: inherit;
  font: inherit;
  font-size: inherit;
  line-height: inherit;
}

.markdown-content { white-space: normal; }

.markdown-content :deep(p) { margin: 0 0 14px; }
.markdown-content :deep(p:last-child) { margin-bottom: 0; }
.markdown-content :deep(h1), .markdown-content :deep(h2), .markdown-content :deep(h3), .markdown-content :deep(h4) {
  margin: 22px 0 10px;
  color: var(--sk-label);
  font-weight: 700;
  line-height: 1.4;
  letter-spacing: -0.015em;
}
.markdown-content :deep(h1) { font-size: 22px; }
.markdown-content :deep(h2) { font-size: 19px; }
.markdown-content :deep(h3), .markdown-content :deep(h4) { font-size: 17px; }
.markdown-content :deep(ul), .markdown-content :deep(ol) { margin: 0 0 14px; padding-left: 1.45em; }
.markdown-content :deep(li) { margin: 5px 0; }
.markdown-content :deep(hr) { height: 1px; margin: 22px 0; border: 0; background: var(--sk-separator); }
.markdown-content :deep(strong) { font-weight: 700; }
.markdown-content :deep(blockquote) {
  margin: 14px 0;
  border-left: 3px solid var(--zwx-primary-ring);
  padding: 3px 0 3px 13px;
  color: var(--sk-label-2);
}
.markdown-content :deep(code) {
  border-radius: 5px;
  padding: 2px 5px;
  background: var(--sk-fill);
  font-family: ui-monospace, "SF Mono", SFMono-Regular, Menlo, monospace;
  font-size: 0.86em;
}
.markdown-content :deep(pre) {
  overflow-x: auto;
  margin: 14px 0;
  border-radius: 12px;
  padding: 14px;
  background: #1d1d26;
  color: #f2f2f7;
  font-size: 13px;
  box-shadow: var(--sk-shadow-card);
}
.markdown-content :deep(pre code) { padding: 0; background: transparent; color: inherit; }
.markdown-content :deep(a) { color: var(--zwx-primary); text-decoration: underline; text-underline-offset: 2px; }

.message-images { display: flex; flex-wrap: wrap; gap: 8px; margin-bottom: 10px; }
.message-images img { display: block; max-width: min(300px, 100%); max-height: 280px; border-radius: 14px; object-fit: cover; box-shadow: var(--sk-shadow-card); }

.vision-analysis {
  max-width: 500px;
  margin-top: 9px;
  border: 1px solid var(--sk-separator);
  border-radius: 12px;
  padding: 9px 11px;
  background: var(--sk-surface);
  color: var(--sk-label-2);
  font-size: 12px;
  line-height: 1.55;
}

.vision-analysis summary { cursor: pointer; color: var(--zwx-primary); font-weight: 650; }
.vision-analysis p { margin: 8px 0 5px; }
.vision-analysis small { display: block; margin-top: 7px; color: var(--sk-label-3); }
.vision-signals { display: flex; flex-wrap: wrap; gap: 5px; }
.vision-signals span { border-radius: 6px; padding: 2px 6px; background: var(--zwx-primary-soft); color: var(--zwx-primary); }

.message-time { display: block; margin-top: 7px; color: var(--sk-label-3); font-size: 11px; }
.user-message .message-time { text-align: right; }

.message-actions {
  position: absolute;
  z-index: 1;
  top: calc(100% + 10px);
  right: 0;
  display: flex;
  align-items: center;
  gap: 10px;
  height: 30px;
  padding: 0 2px;
  opacity: 0;
  pointer-events: none;
  transform: translateY(-3px);
  transition: opacity 0.15s ease, transform 0.15s ease;
}

.message-wrapper.user:hover .message-actions, .message-actions:focus-within { opacity: 1; pointer-events: auto; transform: translateY(0); }

.message-actions button {
  display: grid;
  width: 26px;
  height: 28px;
  place-items: center;
  border: 0;
  border-radius: 8px;
  background: transparent;
  color: var(--sk-label-3);
  font-size: 18px;
  line-height: 1;
}

.message-actions button:hover { background: var(--sk-fill); color: var(--sk-label); }

.continue-row { display: flex; margin-top: 6px; align-items: center; gap: 10px; }
.continue-hint { color: var(--sk-label-3); font-size: 12px; }

.continue-button {
  border: 0;
  border-radius: 999px;
  padding: 4px 13px;
  background: var(--zwx-primary-soft);
  color: var(--zwx-primary);
  font-size: 12px;
  font-weight: 600;
}

.continue-button:hover { filter: brightness(0.96); }

/* ── 执行过程 / 引用 / 文件 ─────────────────────────── */
.thinking-state { display: flex; align-items: center; gap: 9px; min-height: 34px; color: var(--sk-label-2); font-size: 14px; }

.thinking-dots { display: inline-flex; gap: 4px; color: var(--zwx-primary); }

.thinking-dots i {
  display: block;
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: currentColor;
  animation: thinking-pulse 1.1s ease-in-out infinite;
}

.thinking-dots i:nth-child(2) { animation-delay: 0.16s; }
.thinking-dots i:nth-child(3) { animation-delay: 0.32s; }

.activity-trace { display: flex; flex-wrap: wrap; gap: 6px; margin-top: 12px; color: var(--sk-label-2); font-size: 12px; }
.activity-trace > span:first-child { color: var(--sk-label-3); }

.activity-trace button {
  border: 0;
  border-radius: 999px;
  padding: 4px 10px;
  background: var(--sk-fill);
  color: inherit;
  font: inherit;
  text-align: left;
}

.activity-trace button:hover, .activity-trace button.active { background: var(--zwx-primary-soft); color: var(--zwx-primary); }

.activity-trace-collapsible { display: block; max-width: 100%; border: 0; color: var(--sk-label-2); }
.activity-trace-collapsible > summary { cursor: pointer; width: max-content; max-width: 100%; list-style: none; }
.activity-trace-collapsible > summary::-webkit-details-marker { display: none; }
.activity-trace-collapsible > summary::after { content: '⌄'; display: inline-block; margin-left: 7px; color: var(--sk-label-3); transition: transform 0.15s ease; }
.activity-trace-collapsible[open] > summary::after { transform: rotate(180deg); }

.activity-details { display: grid; gap: 7px; margin-top: 10px; }
.activity-details details { border-left: 2px solid var(--sk-separator); padding: 2px 0 2px 10px; }
.activity-details summary { cursor: pointer; color: var(--sk-label-2); }
.activity-details pre {
  max-height: 240px;
  overflow: auto;
  margin: 7px 0 2px;
  padding: 9px;
  border-radius: 9px;
  background: var(--sk-fill);
  color: var(--sk-label-2);
  font: 11px/1.55 ui-monospace, "SF Mono", SFMono-Regular, Menlo, monospace;
  white-space: pre-wrap;
  word-break: break-word;
}

.message-files { display: flex; flex-wrap: wrap; gap: 7px; margin-top: 12px; }

.message-files a {
  display: inline-flex;
  max-width: 100%;
  align-items: center;
  gap: 6px;
  border: 1px solid var(--sk-separator);
  border-radius: 11px;
  padding: 6px 9px;
  background: var(--sk-surface);
  color: var(--zwx-primary);
  font-size: 12px;
  text-decoration: none;
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.03);
}

.message-files a:hover { background: var(--zwx-primary-soft); border-color: transparent; }
.message-files span { overflow: hidden; max-width: 270px; text-overflow: ellipsis; white-space: nowrap; }
.message-files small { border-radius: 5px; padding: 1px 5px; background: var(--zwx-primary-soft); color: var(--zwx-primary); font-size: 10px; font-weight: 650; }

.message-references { display: flex; flex-wrap: wrap; align-items: flex-start; gap: 6px; margin-top: 13px; color: var(--sk-label-2); font-size: 12px; }
.message-references > span { padding: 4px 0; }

.reference-card {
  max-width: min(100%, 520px);
  border: 1px solid var(--sk-separator);
  border-radius: 11px;
  background: var(--sk-surface);
}

.reference-card summary { cursor: pointer; padding: 5px 8px; color: var(--sk-label-2); }
.reference-card p { margin: 0; border-top: 1px solid var(--sk-separator); padding: 7px 9px; color: var(--sk-label-2); line-height: 1.55; white-space: pre-wrap; }

.trace-entry { position: relative; display: inline-flex; margin-top: 10px; }

.trace-button {
  width: 20px;
  height: 20px;
  border: 0;
  border-radius: 50%;
  background: var(--sk-fill);
  color: var(--sk-label-2);
  font-size: 12px;
  font-style: italic;
  font-weight: 700;
  line-height: 1;
}

.trace-popover {
  position: absolute;
  z-index: 4;
  bottom: 28px;
  left: 0;
  display: none;
  width: min(460px, calc(100vw - 88px));
  padding: 13px;
  border: 1px solid var(--sk-separator);
  border-radius: 14px;
  background: var(--sk-material-strong);
  backdrop-filter: var(--sk-blur);
  -webkit-backdrop-filter: var(--sk-blur);
  box-shadow: var(--sk-shadow-pop);
  color: var(--sk-label-2);
  font-size: 12px;
  line-height: 1.6;
}

.trace-popover strong, .trace-popover span { display: block; }
.trace-popover strong { color: var(--sk-label); }
.trace-entry:hover .trace-popover, .trace-entry:focus-within .trace-popover { display: block; }

/* ── 输入区：Messages 风格输入条 ────────────────────── */
.chat-input-container {
  position: absolute;
  right: 0;
  bottom: 0;
  left: 0;
  padding: 14px max(24px, calc((100% - 880px) / 2)) 16px;
  background: linear-gradient(180deg, rgba(242, 242, 247, 0), var(--sk-bg) 32%);
}

.chat-input {
  position: relative;
  border: 1px solid var(--sk-separator);
  border-radius: 22px;
  background: var(--sk-surface);
  box-shadow: var(--sk-shadow-raised);
}

.chat-input:focus-within {
  border-color: var(--zwx-primary-ring);
  box-shadow: 0 0 0 3px var(--zwx-primary-ring), var(--sk-shadow-raised);
}

.file-input { display: none; }

.selected-file-name {
  display: block;
  max-width: 132px;
  overflow: hidden;
  padding: 7px 20px 7px 8px;
  color: var(--sk-label-2);
  font-size: 11px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.input-box {
  display: block;
  box-sizing: border-box;
  width: 100%;
  min-height: 76px;
  max-height: 132px;
  resize: none;
  border: 0;
  outline: 0;
  padding: 15px 17px 6px;
  background: transparent;
  color: var(--sk-label);
  font: inherit;
  font-size: 15px;
  line-height: 1.55;
}

.input-box::placeholder { color: var(--sk-label-3); }

.input-actions { display: flex; height: 44px; align-items: center; gap: 7px; padding: 0 10px 9px 12px; }

.tool-button {
  display: grid;
  width: 30px;
  height: 30px;
  place-items: center;
  border: 0;
  border-radius: 9px;
  background: transparent;
  color: var(--sk-label-2);
}

.tool-button:hover { background: var(--sk-fill); color: var(--sk-label); }

.web-search-toggle.active, .knowledge-search-toggle.active { background: var(--zwx-primary-soft); color: var(--zwx-primary); }

.input-hint { margin-right: auto; color: var(--sk-label-3); font-size: 12px; }

.send-button {
  display: grid;
  width: 34px;
  height: 34px;
  place-items: center;
  border: 0;
  border-radius: 50%;
  padding: 0;
  background: linear-gradient(180deg, color-mix(in srgb, var(--zwx-primary) 88%, #ffffff), var(--zwx-primary));
  color: #fff;
  font-size: 18px;
  font-weight: 700;
  line-height: 1;
  box-shadow: 0 3px 8px var(--zwx-primary-ring);
}

.send-button:hover:not(:disabled) { filter: brightness(1.06); }
.send-button:active:not(:disabled) { transform: scale(0.94); }
.send-button:disabled { background: var(--sk-fill-strong); color: var(--sk-label-3); box-shadow: none; }

.streaming-pulse { display: inline-flex; align-items: center; gap: 3px; margin-left: 7px; color: var(--zwx-primary); vertical-align: middle; }

.streaming-pulse i {
  display: block;
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: currentColor;
  animation: streaming-pulse 1.05s ease-in-out infinite;
}

.streaming-pulse i:nth-child(2) { animation-delay: 0.14s; }
.streaming-pulse i:nth-child(3) { animation-delay: 0.28s; }

.selected-images {
  position: absolute;
  z-index: 2;
  right: 10px;
  bottom: calc(100% + 8px);
  display: flex;
  gap: 7px;
  max-width: min(420px, 100%);
  padding: 7px;
  border: 1px solid var(--sk-separator);
  border-radius: 14px;
  background: var(--sk-material-strong);
  backdrop-filter: var(--sk-blur);
  -webkit-backdrop-filter: var(--sk-blur);
  box-shadow: var(--sk-shadow-pop);
  overflow-x: auto;
}

.selected-image { position: relative; width: 46px; height: 46px; flex: 0 0 46px; }
.selected-image img { width: 100%; height: 100%; border-radius: 9px; object-fit: cover; }

.selected-image button {
  position: absolute;
  top: -5px;
  right: -5px;
  display: grid;
  width: 17px;
  height: 17px;
  place-items: center;
  border: 1px solid #fff;
  border-radius: 50%;
  background: rgba(29, 29, 31, 0.85);
  color: #fff;
  line-height: 1;
}

@keyframes thinking-pulse {
  0%, 70%, 100% { opacity: 0.25; transform: translateY(0); }
  35% { opacity: 1; transform: translateY(-3px); }
}

@keyframes streaming-pulse {
  0%, 70%, 100% { opacity: 0.24; transform: translateY(0) scale(0.82); }
  35% { opacity: 1; transform: translateY(-3px) scale(1); }
}

@media (prefers-reduced-motion: reduce) {
  .streaming-pulse i, .thinking-dots i { animation: none; opacity: 0.75; }
}

@media (max-width: 720px) {
  .chat-messages { inset: 0 0 138px; padding: 25px 16px; }
  .chat-input-container { padding: 10px 12px 12px; }
  .empty-state { padding-bottom: 40px; }
  .empty-state h2 { font-size: 23px; }
  .message-content { font-size: 15px; }
  .input-hint { display: none; }
}
</style>
