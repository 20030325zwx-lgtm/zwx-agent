<template>
  <div class="chat-container" :class="[`chat-${aiType}`, { 'chat-no-input': hideInput }]">
    <div class="chat-messages" ref="messagesContainer">
      <section v-if="!messages.length" class="empty-state">
        <div class="empty-brand">
          <AsterAvatar v-if="aiType === 'super' || aiType === 'default'" />
          <template v-else>{{ agent.icon }}</template>
        </div>
        <h2>{{ emptyTitle }}</h2>
        <p>{{ emptyDescription }}</p>
        <div class="suggestion-list">
          <button v-for="prompt in quickPrompts" :key="prompt" type="button" @click="usePrompt(prompt)">{{ prompt }}</button>
        </div>
      </section>

      <div v-for="(msg, index) in messages" :key="index" class="message-wrapper" :class="{ user: msg.isUser }">
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
            <AgentTrace v-if="msg.activities?.length || msg.toolCalls?.length || msg.startedAt" :message="msg" :index="index"
              :working="isTurnWorking(index)" :now="nowTick" />
            <template v-if="msg.content">
              <div v-html="renderMarkdown(msg.content)"></div>
              <span v-if="connectionStatus === 'connecting' && index === messages.length - 1" class="streaming-pulse" aria-label="正在生成"><i></i><i></i><i></i></span>
            </template>
            <div v-else-if="connectionStatus === 'connecting' && index === messages.length - 1 && !msg.startedAt" class="thinking-state">
              <span class="thinking-dots" aria-hidden="true"><i></i><i></i><i></i></span>{{ msg.thinking || '正在思考...' }}
            </div>
          </div>
          <DeliverableCard v-if="!msg.isUser && msg.deliverables?.length" :files="msg.deliverables"
            :conversation-id="conversationId" />
          <RunSummaryCard v-if="!msg.isUser && msg.runSummary" :summary="msg.runSummary" />
          <ChangeSummaryCard v-if="!msg.isUser && msg.changeSummary" :summary="msg.changeSummary"
            @open="path => emit('open-code', { tab: 'diff', path })" />
          <div v-if="!msg.isUser && citedChunks(msg).length" class="citation-preview" aria-label="引用资料">
            <div class="citation-heading"><BookOpen :size="15" /><span>引用资料</span><small>{{ citedChunks(msg).length }} 个切片</small></div>
            <button v-for="citation in citedChunks(msg)" :key="citation.key" type="button" class="citation-card" @click="openCitation(citation)">
              <span class="citation-file">{{ citation.filename }}<em>#{{ citation.chunkIndex }}</em></span>
              <span class="citation-excerpt">{{ citation.regionLabel || citation.excerpt || (citation.chunkId ? '点击预览切片原文' : '未找到可预览的切片') }}</span>
              <ChevronRight :size="15" />
            </button>
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
          <details v-if="msg.ragTrace" class="rag-inspector">
            <summary>查看检索过程 <span>{{ ragStatusLabel(msg.ragTrace.status) }} · {{ msg.ragTrace.totalMillis }} ms</span></summary>
            <div class="rag-inspector-body">
              <p class="rag-query">查询：{{ msg.ragTrace.query }}</p>
              <div class="rag-stats">
                <span>向量 {{ msg.ragTrace.vectorCandidateCount }}</span>
                <span>词法 {{ msg.ragTrace.lexicalCandidateCount }}</span>
                <span>融合 {{ msg.ragTrace.fusedCandidateCount }}</span>
                <span>最终 {{ msg.ragTrace.selectedCount }}</span>
                <span>阶段：向量 {{ msg.ragTrace.vectorMillis }} ms / 词法 {{ msg.ragTrace.lexicalMillis }} ms / 重排 {{ msg.ragTrace.rerankMillis }} ms</span>
              </div>
              <p v-if="!msg.ragTrace.candidates?.length" class="rag-empty">本轮没有可展示的候选片段。</p>
              <article v-for="candidate in msg.ragTrace.candidates" :key="candidate.documentId" class="rag-candidate">
                <header><strong>{{ candidate.filename }}<span v-if="candidate.pageNumber"> · 第 {{ candidate.pageNumber }} 页</span><span v-if="candidate.chunkIndex"> · 切片 {{ candidate.chunkIndex }}</span></strong><em v-if="candidate.selected">已送入回答</em></header>
                <div class="rag-ranks">
                  <span>向量 {{ candidate.vectorRank ? `#${candidate.vectorRank} · ${candidate.vectorScore?.toFixed(3)}` : '未命中' }}</span>
                  <span>词法 {{ candidate.lexicalRank ? `#${candidate.lexicalRank} · ${candidate.lexicalScore} 词` : '未命中' }}</span>
                  <span>RRF {{ candidate.fusedRank ? `#${candidate.fusedRank} · ${candidate.fusionScore?.toFixed(4)}` : '未融合' }}</span>
                  <span>重排 {{ candidate.rerankRank ? `#${candidate.rerankRank} · ${candidate.rerankScore?.toFixed(3)}` : '未参与' }}</span>
                </div>
                <p>{{ candidate.excerpt }}</p>
                <div class="rag-judgment" :aria-label="`人工相关性判断：${candidate.filename}`">
                  <span>这段资料相关吗？</span>
                  <button v-for="choice in relevanceChoices" :key="choice.value" type="button" :disabled="candidate.judgmentSaving" :class="{ selected: candidate.judgment === choice.value }" @click="judgeCandidate(msg, candidate, choice.value)">{{ choice.label }}</button>
                </div>
              </article>
            </div>
          </details>
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
          <div v-if="!msg.isUser && msg.content" class="ai-message-actions">
            <button type="button" title="复制回答" aria-label="复制回答" @click="copyMessage(msg.content, msg)">⧉ 复制</button>
            <template v-if="conversationId && msg.runId">
              <button type="button" class="feedback-button" :class="{ active: msg.feedback === 'up', saving: msg.feedbackSaving }"
                      :disabled="!!msg.feedback || msg.feedbackSaving"
                      title="回答有帮助（用于沉淀优质样本）" @click="sendFeedback(msg, 'up')">👍</button>
              <button type="button" class="feedback-button" :class="{ active: msg.feedback === 'down', saving: msg.feedbackSaving }"
                      :disabled="!!msg.feedback || msg.feedbackSaving"
                      title="回答有问题（进入 BadCase 库，可回流评测集）" @click="sendFeedback(msg, 'down')">👎</button>
            </template>
          </div>
          <div v-if="msg.feedback === 'down' && msg.feedbackTagsOpen" class="feedback-tags">
            <span class="feedback-tags-hint">哪里有问题？（可跳过）</span>
            <button v-for="tag in FEEDBACK_TAGS" :key="tag" type="button" class="feedback-tag"
                    :class="{ active: (msg.feedbackTags || []).includes(tag) }"
                    :disabled="msg.tagsSaving" @click="toggleFeedbackTag(msg, tag)">{{ tag }}</button>
          </div>
          <div v-if="!msg.isUser && msg.status === 'INTERRUPTED' && connectionStatus !== 'connecting'" class="continue-row">
            <span class="continue-hint">回答被中断，已保留部分内容</span>
            <button type="button" class="continue-button" @click="continueMessage(msg, index)">继续生成 ▸</button>
          </div>
          <div v-if="msg.isUser && editingIndex !== index" class="message-actions" aria-label="消息操作">
            <button type="button" title="复制消息" aria-label="复制消息" @click="copyMessage(msg.content, msg)">⧉</button>
            <button type="button" title="编辑消息" aria-label="编辑消息" @click="editMessage(msg.content, index)">✎</button>
            <button type="button" title="重新发送" aria-label="重新发送" @click="resendMessage(msg, index)">↻</button>
          </div>
        </article>
      </div>
    </div>

    <footer v-if="!hideInput" class="chat-input-container">
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
          <button v-if="webSearchAvailable" class="tool-button web-search-toggle" :class="{ active: webSearch }" type="button" :aria-pressed="webSearch" :title="webSearch ? '本轮允许联网搜索' : '本轮不联网搜索'" aria-label="联网搜索" @click="webSearch = !webSearch"><Globe2 :size="15" /><span class="pill-label">联网搜索</span></button>
          <button v-if="knowledgeSearchAvailable" class="tool-button knowledge-search-toggle" :class="{ active: knowledgeSearch }" type="button" :aria-pressed="knowledgeSearch" :title="knowledgeSearch ? '本轮先检索知识库' : '本轮不检索知识库'" aria-label="检索知识库" @click="knowledgeSearch = !knowledgeSearch"><BookOpenCheck :size="15" /><span class="pill-label">知识库</span></button>
          <span class="input-hint">{{ connectionStatus === 'connecting' ? '正在生成，可随时停止' : 'Enter 发送' }}</span>
          <button v-if="connectionStatus === 'connecting'" class="stop-button" type="button" title="停止生成" aria-label="停止生成" @click="emit('stop-generation')">
            <Square :size="15" fill="currentColor" />
          </button>
          <button v-else class="send-button" type="button" :disabled="!inputMessage.trim() && !selectedImages.length" aria-label="发送" @click="sendMessage">↑</button>
        </div>
      </div>
    </footer>

    <CitationPreview v-if="citationPreview" :preview="citationPreview" :loading="citationLoading"
      :error="citationError" :agent-key="aiType" @close="closeCitation" />
  </div>
</template>

<script setup>
import { computed, ref, onMounted, onBeforeUnmount, nextTick, watch } from 'vue'
import DOMPurify from 'dompurify'
import { marked } from 'marked'
import AgentTrace from './AgentTrace.vue'
import CitationPreview from './CitationPreview.vue'
import ChangeSummaryCard from './workspace/ChangeSummaryCard.vue'
import DeliverableCard from './workspace/DeliverableCard.vue'
import RunSummaryCard from './workspace/RunSummaryCard.vue'
import AsterAvatar from './AsterAvatar.vue'
import { BookOpen, BookOpenCheck, ChevronRight, FileText, Globe2, Paperclip, Square } from 'lucide-vue-next'
import { getAgent } from '../config/agents'
import { getAgentKnowledgeChunk, judgeRagCandidate as saveRagJudgment, createUserFeedback, updateUserFeedbackTags, reportSignal } from '../api'

const props = defineProps({
  messages: { type: Array, default: () => [] },
  connectionStatus: { type: String, default: 'disconnected' },
  aiType: { type: String, default: 'default' },
  attachmentsEnabled: { type: Boolean, default: false },
  attachmentAccept: { type: String, default: 'image/jpeg,image/png,image/gif' },
  webSearchAvailable: { type: Boolean, default: false },
  knowledgeSearchAvailable: { type: Boolean, default: false },
  hideInput: { type: Boolean, default: false },
  // D1 数据中台：会话 ID，用于回答 👍/👎 反馈（无会话上下文的用法可不传，按钮自动隐藏）
  conversationId: { type: String, default: '' }
})
const emit = defineEmits(['send-message', 'edit-message', 'resend-message', 'continue-message', 'stop-generation', 'open-code', 'answer-feedback'])
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
const relevanceChoices = [{ value: 'RELEVANT', label: '相关' }, { value: 'PARTIAL', label: '部分相关' }, { value: 'IRRELEVANT', label: '不相关' }]

// D1 数据中台：一次点击反馈，成功后按钮定格；点踩额外浮出可选标签（不点也不丢）
const FEEDBACK_TAGS = ['不准确', '不完整', '跑题', '格式差', '太慢']

const sendFeedback = async (msg, rating) => {
  if (!msg.runId || msg.feedback || msg.feedbackSaving || !props.conversationId) return
  msg.feedbackSaving = true
  try {
    const record = await createUserFeedback({ rating, runId: msg.runId, conversationId: props.conversationId })
    msg.feedback = rating
    msg.feedbackId = record?.id
    msg.feedbackTags = record?.tags || []
    if (rating === 'down' && msg.feedbackId) msg.feedbackTagsOpen = true
    emit('answer-feedback', { rating, runId: msg.runId, messageId: msg.id })
  } catch (exception) {
    console.warn('反馈提交失败', exception)
    window.alert(`反馈提交失败：${exception.response?.data?.error || '请稍后重试'}`)
  } finally {
    msg.feedbackSaving = false
  }
}

/** 点踩后的第二步：增删标签；失败只提示，不回滚已提交的反馈。 */
const toggleFeedbackTag = async (msg, tag) => {
  if (!msg.feedbackId || msg.tagsSaving) return
  const current = msg.feedbackTags || []
  const next = current.includes(tag) ? current.filter(item => item !== tag) : [...current, tag]
  msg.tagsSaving = true
  try {
    const updated = await updateUserFeedbackTags(msg.feedbackId, next)
    msg.feedbackTags = updated?.tags || next
  } catch (exception) {
    console.warn('标签保存失败', exception)
  } finally {
    msg.tagsSaving = false
  }
}

const ragStatusLabel = status => ({ SUCCESS: '已召回', NO_HIT: '无命中', TIMEOUT: '超时', OVERLOADED: '繁忙', FAILED: '失败' }[status] || status)
const judgeCandidate = async (message, candidate, judgment) => {
  if (!message.id || !candidate.documentId) return
  candidate.judgmentSaving = true
  try {
    await saveRagJudgment(message.id, props.aiType, candidate.documentId, judgment)
    candidate.judgment = judgment
    if (message.ragTrace) {
      message.ragTrace.judgments = { ...(message.ragTrace.judgments || {}), [candidate.documentId]: judgment }
    }
  } catch (error) {
    console.error('Unable to save RAG relevance judgment:', error)
  } finally {
    candidate.judgmentSaving = false
  }
}

const citationPreview = ref(null)
const citationLoading = ref(false)
const citationError = ref('')
const citationCache = new Map()
const citationPattern = /\[([^\[\]\n]+?)#(\d+)\]/g
const plainExcerpt = value => (value || '')
  .replace(/<[^>]*>/g, ' ')
  .replace(/\s+/g, ' ')
  .trim()

const citedChunks = message => {
  if (!message?.content || !message.ragTrace?.candidates?.length) return []
  const seen = new Set()
  const result = []
  citationPattern.lastIndex = 0
  let match
  while ((match = citationPattern.exec(message.content))) {
    const filename = match[1].trim()
    const normalizedFilename = filename.toLowerCase() === 'null' ? '' : filename
    const chunkIndex = Number(match[2])
    const key = `${normalizedFilename || 'unknown'}#${chunkIndex}`
    if (seen.has(key)) continue
    seen.add(key)
    const matches = message.ragTrace.candidates.filter(candidate =>
      Number(candidate.chunkIndex) === chunkIndex
      && (!normalizedFilename
        || candidate.filename === normalizedFilename
        || String(candidate.filename || '').endsWith(normalizedFilename)))
    const candidate = matches.find(item => item.selected) || matches[0]
    result.push({
      key,
      filename: candidate?.filename || normalizedFilename || '未知文档',
      chunkIndex,
      chunkId: candidate?.chunkId || candidate?.documentId || null,
      sourceDocumentId: candidate?.sourceDocumentId || null,
      blockId: candidate?.blockId || null,
      sourceType: candidate?.sourceType || null,
      pageNumber: candidate?.pageNumber || null,
      regionLabel: candidate?.regionLabel || null,
      excerpt: plainExcerpt(candidate?.excerpt || '')
    })
  }
  return result
}
const openCitation = async citation => {
  citationError.value = ''
  citationLoading.value = false
  if (!citation.chunkId) {
    citationPreview.value = { ...citation, content: '', documentId: null }
    citationError.value = '本次回答没有保存对应切片，无法预览原文。'
    return
  }
  const cacheKey = `${citation.chunkId}|${citation.sourceDocumentId || ''}|${citation.blockId || ''}`
  if (citationCache.has(cacheKey)) {
    citationPreview.value = citationCache.get(cacheKey)
    return
  }
  citationPreview.value = { ...citation, content: '', documentId: null }
  citationLoading.value = true
  try {
    const chunk = await getAgentKnowledgeChunk(citation.chunkId, props.aiType, {
      documentId: citation.sourceDocumentId || undefined,
      blockId: citation.blockId || undefined
    })
    const preview = { ...citation, ...chunk }
    citationCache.set(cacheKey, preview)
    citationPreview.value = preview
  } catch (error) {
    citationError.value = error?.response?.status === 404
      ? '切片已被删除或重建，无法预览原文。'
      : '切片加载失败，请稍后重试。'
  } finally {
    citationLoading.value = false
  }
}
const closeCitation = () => {
  citationPreview.value = null
  citationError.value = ''
  citationLoading.value = false
}
const handleCitationKeydown = event => { if (event.key === 'Escape') closeCitation() }

const stripInlineCitations = content => (content || '')
  .replace(/\[([^\[\]\n]+?)#(\d+)\]/g, '')
  .replace(/[ \t]+(?=[。，；：！？、.,;:!?])/g, '')
  .replace(/[ \t]{2,}/g, ' ')

const renderMarkdown = content => DOMPurify.sanitize(marked.parse(stripInlineCitations(content), {
  breaks: true,
  gfm: true
}))

// ── 智能体时间线：渲染在 AgentTrace.vue，这里只负责秒级刷新驱动与是否执行中的判定 ──
const nowTick = ref(Date.now())
let tickTimer = null

const isTurnWorking = index => (props.connectionStatus === 'connecting' && index === props.messages.length - 1)
  || props.messages[index]?.status === 'RUNNING'
const sendMessage = () => {
  if (props.connectionStatus === 'connecting') return
  if (!inputMessage.value.trim() && !selectedImages.value.length) return
  const payload = { message: inputMessage.value.trim(), files: selectedImages.value.map(item => item.file) }
  if (props.webSearchAvailable) payload.webSearch = webSearch.value
  if (props.knowledgeSearchAvailable) payload.knowledgeSearch = knowledgeSearch.value
  emit('send-message', props.attachmentsEnabled || props.webSearchAvailable || props.knowledgeSearchAvailable ? payload : payload.message)
  inputMessage.value = ''
  clearSelectedImages()
  if (fileInput.value) fileInput.value.value = ''
}
const copyMessage = async (content, msg) => {
  try {
    await navigator.clipboard.writeText(stripInlineCitations(content))
    // 隐式信号：复制是最廉价的质量肯定，失败不影响复制本身
    reportSignal('copy', msg?.runId ? 'run' : 'conversation',
      msg?.runId || props.conversationId, { messageId: msg?.id })
  } catch (error) { console.error('Copy message failed:', error) }
}
const resendMessage = (msg, index) => {
  reportSignal('regenerate', 'conversation', props.conversationId,
    { messageId: msg.id, mode: 'resend' })
  emit('resend-message', { content: msg.content, id: msg.id, index, imageKeys: msg.imageKeys || [] })
}
const continueMessage = (msg, index) => {
  reportSignal('regenerate', 'conversation', props.conversationId,
    { runId: msg.runId, messageId: msg.id, mode: 'continue' })
  emit('continue-message', { message: msg, index })
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
watch(() => props.messages.map(message => (message.activities || []).length).join('|'), scrollToBottom)
watch(() => props.connectionStatus, status => {
  if (status === 'connecting') {
    nowTick.value = Date.now()
    if (!tickTimer) tickTimer = setInterval(() => { nowTick.value = Date.now() }, 1000)
  } else if (tickTimer) {
    clearInterval(tickTimer)
    tickTimer = null
  }
}, { immediate: true })
onMounted(() => {
  scrollToBottom()
  window.addEventListener('keydown', handleCitationKeydown)
})
onBeforeUnmount(() => {
  window.removeEventListener('keydown', handleCitationKeydown)
  if (tickTimer) { clearInterval(tickTimer); tickTimer = null }
})
</script>

<style scoped>
.chat-container {
  position: relative;
  display: flex;
  min-height: 0;
  height: 100%;
  flex-direction: column;
  /* DeepSeek 风格：页面本身就是画布，容器不再自带渐变底 */
  background: transparent;
}

.chat-messages {
  position: absolute;
  inset: 0 0 150px;
  overflow-y: auto;
  padding: 26px max(28px, calc((100% - 760px) / 2)) 32px;
}

.chat-no-input .chat-messages { inset: 0; padding-bottom: 24px; }

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
  width: 60px;
  height: 60px;
  place-items: center;
  overflow: hidden;
  border-radius: 16px;
  background: color-mix(in srgb, var(--wb-brand) 10%, var(--wb-surface));
  color: var(--wb-brand);
  font-size: 32px;
}

.empty-state h2 {
  margin: 20px 0 8px;
  font-size: 26px;
  font-weight: 700;
  letter-spacing: -0.02em;
}

.empty-state p { margin: 0; color: var(--wb-text-2); font-size: 15px; }

.suggestion-list { display: grid; gap: 10px; width: min(100%, 440px); margin-top: 30px; }

.suggestion-list button {
  border: 1px solid var(--wb-border);
  border-radius: 9px;
  padding: 13px 16px;
  background: var(--wb-surface);
  color: var(--wb-text);
  font-size: 14px;
  text-align: left;
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.03);
}

.suggestion-list button:hover {
  border-color: var(--wb-border-strong);
  background: var(--wb-surface-2);
}

/* ── 消息布局（DeepSeek 风格：回答全宽正文、提问浅蓝气泡；不展示头像） ─────────── */
.message-wrapper { display: flex; align-items: flex-start; margin-bottom: 30px; }
.message-wrapper.user { justify-content: flex-end; margin-bottom: 40px; }

.message { min-width: 0; max-width: 100%; color: var(--wb-text); }
.ai-message { padding: 2px 0; }

/* 用户气泡：品牌色浅底 + 深色文字（随主题联动，深色主题下为暗蓝底） */
.user-message {
  position: relative;
  max-width: min(78%, 560px);
  border-radius: 18px;
  padding: 11px 16px;
  background: color-mix(in srgb, var(--wb-brand) 11%, var(--wb-surface));
  color: var(--wb-text);
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
  border-bottom: 1px solid color-mix(in srgb, var(--wb-text) 26%, transparent);
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
  color: var(--wb-text);
  font-weight: 700;
  line-height: 1.4;
  letter-spacing: -0.015em;
}
.markdown-content :deep(h1) { font-size: 22px; }
.markdown-content :deep(h2) { font-size: 19px; }
.markdown-content :deep(h3), .markdown-content :deep(h4) { font-size: 17px; }
.markdown-content :deep(ul), .markdown-content :deep(ol) { margin: 0 0 14px; padding-left: 1.45em; }
.markdown-content :deep(li) { margin: 5px 0; }
.markdown-content :deep(hr) { height: 1px; margin: 22px 0; border: 0; background: var(--wb-border); }
.markdown-content :deep(strong) { font-weight: 700; }
.markdown-content :deep(blockquote) {
  margin: 14px 0;
  border-left: 3px solid var(--wb-brand-soft);
  padding: 3px 0 3px 13px;
  color: var(--wb-text-2);
}
.markdown-content :deep(code) {
  border-radius: 5px;
  padding: 2px 5px;
  background: var(--wb-fill);
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
  box-shadow: var(--wb-shadow-md);
}
.markdown-content :deep(pre code) { padding: 0; background: transparent; color: inherit; }
.markdown-content :deep(a) { color: var(--wb-brand); text-decoration: underline; text-underline-offset: 2px; }

.message-images { display: flex; flex-wrap: wrap; gap: 8px; margin-bottom: 10px; }
.message-images img { display: block; max-width: min(300px, 100%); max-height: 280px; border-radius: 14px; object-fit: cover; box-shadow: var(--wb-shadow-md); }

.vision-analysis {
  max-width: 500px;
  margin-top: 9px;
  border: 1px solid var(--wb-border);
  border-radius: 12px;
  padding: 9px 11px;
  background: var(--wb-surface);
  color: var(--wb-text-2);
  font-size: 12px;
  line-height: 1.55;
}

.vision-analysis summary { cursor: pointer; color: var(--wb-brand); font-weight: 650; }
.vision-analysis p { margin: 8px 0 5px; }
.vision-analysis small { display: block; margin-top: 7px; color: var(--wb-muted); }
.vision-signals { display: flex; flex-wrap: wrap; gap: 5px; }
.vision-signals span { border-radius: 6px; padding: 2px 6px; background: var(--wb-brand-soft); color: var(--wb-brand); }

.message-time { display: block; margin-top: 7px; color: var(--wb-muted); font-size: 11px; }
.user-message .message-time { text-align: right; }

.ai-message-actions { display: flex; margin-top: 5px; opacity: 0; transition: opacity 0.15s ease; }
.message-wrapper:hover .ai-message-actions, .ai-message-actions:focus-within { opacity: 1; }

.ai-message-actions button {
  border: 0;
  border-radius: 8px;
  padding: 4px 9px;
  background: transparent;
  color: var(--wb-muted);
  font-size: 11.5px;
  cursor: pointer;
}

.ai-message-actions button:hover { background: var(--wb-fill); color: var(--wb-text); }
.feedback-button { filter: grayscale(1); opacity: 0.65; }
.feedback-button:hover { filter: none; opacity: 1; }
.feedback-button.active { filter: none; opacity: 1; background: var(--cr-accent-soft, var(--wb-brand-soft)); }
.feedback-tags { display: flex; flex-wrap: wrap; align-items: center; gap: 6px; margin: 6px 0 0 2px; }
.feedback-tags-hint { font-size: 11px; color: var(--wb-muted, #87878f); }
.feedback-tag {
  border: 1px solid var(--wb-border, #e4e3df); background: transparent;
  color: var(--wb-text-2, #5f5f67); border-radius: 999px; padding: 2px 9px;
  font-size: 11px; cursor: pointer;
}
.feedback-tag:hover { background: var(--wb-fill, #f1f0ec); }
.feedback-tag.active { background: var(--cr-accent-soft, #efeafd); border-color: transparent; color: var(--wb-brand, #6654c7); }
.feedback-tag:disabled { opacity: .5; cursor: default; }

@media (hover: none) {
  .ai-message-actions { opacity: 1; }
}

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
  color: var(--wb-muted);
  font-size: 18px;
  line-height: 1;
}

.message-actions button:hover { background: var(--wb-fill); color: var(--wb-text); }

.continue-row { display: flex; margin-top: 6px; align-items: center; gap: 10px; }
.continue-hint { color: var(--wb-muted); font-size: 12px; }

.continue-button {
  border: 0;
  border-radius: 999px;
  padding: 4px 13px;
  background: var(--wb-brand-soft);
  color: var(--wb-brand);
  font-size: 12px;
  font-weight: 600;
}

.continue-button:hover { filter: brightness(0.96); }

/* ── 执行过程 / 引用 / 文件 ─────────────────────────── */
.thinking-state { display: flex; align-items: center; gap: 9px; min-height: 34px; color: var(--wb-text-2); font-size: 14px; }

.thinking-dots { display: inline-flex; gap: 4px; color: var(--wb-brand); }

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

.message-files { display: flex; flex-wrap: wrap; gap: 7px; margin-top: 12px; }

.message-files a {
  display: inline-flex;
  max-width: 100%;
  align-items: center;
  gap: 6px;
  border: 1px solid var(--wb-border);
  border-radius: 11px;
  padding: 6px 9px;
  background: var(--wb-surface);
  color: var(--wb-brand);
  font-size: 12px;
  text-decoration: none;
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.03);
}

.message-files a:hover { background: var(--wb-brand-soft); border-color: transparent; }
.message-files span { overflow: hidden; max-width: 270px; text-overflow: ellipsis; white-space: nowrap; }
.message-files small { border-radius: 5px; padding: 1px 5px; background: var(--wb-brand-soft); color: var(--wb-brand); font-size: 10px; font-weight: 650; }

.message-references { display: flex; flex-wrap: wrap; align-items: flex-start; gap: 6px; margin-top: 13px; color: var(--wb-text-2); font-size: 12px; }
.message-references > span { padding: 4px 0; }

.reference-card {
  max-width: min(100%, 520px);
  border: 1px solid var(--wb-border);
  border-radius: 11px;
  background: var(--wb-surface);
}

.reference-card summary { cursor: pointer; padding: 5px 8px; color: var(--wb-text-2); }
.reference-card p { margin: 0; border-top: 1px solid var(--wb-border); padding: 7px 9px; color: var(--wb-text-2); line-height: 1.55; white-space: pre-wrap; }

.citation-preview { display: grid; gap: 8px; max-width: 760px; margin-top: 14px; padding-top: 12px; border-top: 1px dashed var(--wb-border); }
.citation-heading { display: flex; align-items: center; gap: 7px; color: var(--wb-text-2); font-size: 12px; }
.citation-heading small { color: var(--wb-muted); }
.citation-card {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 18px;
  gap: 2px 10px;
  align-items: center;
  width: 100%;
  padding: 10px 12px;
  border: 1px solid var(--wb-border);
  border-radius: 12px;
  background: var(--wb-surface);
  color: var(--wb-text-2);
  text-align: left;
  cursor: pointer;
  transition: border-color .16s ease, background .16s ease;
}
.citation-card:hover { border-color: var(--wb-brand); background: var(--wb-brand-soft); }
.citation-file { display: flex; align-items: center; gap: 5px; color: var(--wb-brand); font-size: 12.5px; font-weight: 650; }
.citation-file em { font-style: normal; color: var(--wb-muted); font-weight: 500; }
.citation-excerpt { grid-column: 1; overflow: hidden; color: var(--wb-muted); font-size: 12px; text-overflow: ellipsis; white-space: nowrap; }
.citation-card > svg { grid-column: 2; grid-row: 1 / span 2; color: var(--wb-muted); }

.citation-modal-backdrop {
  position: fixed;
  inset: 0;
  z-index: 1300;
  display: grid;
  place-items: center;
  padding: 24px;
  background: rgba(16, 24, 40, 0.38);
  backdrop-filter: blur(8px);
}
.citation-modal {
  display: flex;
  flex-direction: column;
  width: min(820px, 100%);
  max-height: min(78vh, 760px);
  overflow: hidden;
  border-radius: 18px;
  background: #fff;
  box-shadow: 0 28px 80px rgba(15, 23, 42, 0.28);
}
.citation-modal header { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; padding: 18px 20px 14px; border-bottom: 1px solid var(--wb-border); }
.citation-kicker { display: block; margin-bottom: 4px; color: var(--wb-muted); font-size: 11px; letter-spacing: .08em; }
.citation-modal header strong { color: var(--wb-text); font-size: 15px; }
.citation-modal header strong em { margin-left: 4px; color: var(--wb-brand); font-style: normal; }
.citation-modal header button { display: grid; width: 30px; height: 30px; place-items: center; border: 0; border-radius: 9px; background: var(--wb-fill); color: var(--wb-text-2); cursor: pointer; }
.citation-content { flex: 1; overflow: auto; margin: 0; padding: 18px 20px; background: #fbfcfe; color: var(--wb-text-2); font: 13px/1.75 ui-monospace, "SF Mono", SFMono-Regular, Menlo, monospace; white-space: pre-wrap; word-break: break-word; }
.citation-state { margin: 0; padding: 44px 20px; color: var(--wb-muted); text-align: center; }
.citation-state.error { color: #d9382c; }
.citation-modal footer { display: flex; justify-content: space-between; gap: 12px; padding: 10px 20px; border-top: 1px solid var(--wb-border); color: var(--wb-muted); font-size: 11px; }
@media (max-width: 720px) {
  .citation-excerpt { white-space: normal; }
  .citation-modal-backdrop { padding: 12px; }
  .citation-modal { max-height: 86vh; }
}

.trace-entry { position: relative; display: inline-flex; margin-top: 10px; }
.rag-inspector { margin-top: 12px; border-top: 1px solid var(--wb-border); color: var(--wb-text-2); font-size: 12px; }
.rag-inspector > summary { display: flex; width: fit-content; max-width: 100%; align-items: center; gap: 9px; padding: 8px 0; cursor: pointer; color: var(--wb-brand); }
.rag-inspector > summary span { color: var(--wb-muted); }
.rag-inspector-body { display: grid; gap: 9px; max-width: 760px; padding: 2px 0 8px; }
.rag-query, .rag-empty { margin: 0; }
.rag-stats, .rag-ranks, .rag-judgment { display: flex; flex-wrap: wrap; align-items: center; gap: 6px 12px; }
.rag-stats { color: var(--wb-muted); }
.rag-candidate { padding: 10px 0; border-top: 1px solid var(--wb-border); }
.rag-candidate > header { display: flex; flex-wrap: wrap; justify-content: space-between; gap: 6px; }
.rag-candidate > header strong { color: var(--wb-text); font-weight: 600; }
.rag-candidate > header em { color: var(--wb-brand); font-style: normal; }
.rag-candidate > p { margin: 8px 0; white-space: normal; }
.rag-judgment > span { color: var(--wb-muted); }
.rag-judgment button { border: 1px solid var(--wb-border); border-radius: 6px; padding: 4px 8px; background: transparent; color: var(--wb-text-2); cursor: pointer; }
.rag-judgment button.selected { border-color: var(--wb-brand); color: var(--wb-brand); background: var(--wb-brand-soft); }

.trace-button {
  width: 20px;
  height: 20px;
  border: 0;
  border-radius: 50%;
  background: var(--wb-fill);
  color: var(--wb-text-2);
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
  border: 1px solid var(--wb-border);
  border-radius: 14px;
  background: var(--wb-surface);
  backdrop-filter: var(--sk-blur);
  -webkit-backdrop-filter: var(--sk-blur);
  box-shadow: var(--wb-shadow-lg);
  color: var(--wb-text-2);
  font-size: 12px;
  line-height: 1.6;
}

.trace-popover strong, .trace-popover span { display: block; }
.trace-popover strong { color: var(--wb-text); }
.trace-entry:hover .trace-popover, .trace-entry:focus-within .trace-popover { display: block; }

/* ── 输入区：DeepSeek 风格大圆角输入台 ──────────────── */
.chat-input-container {
  position: absolute;
  right: 0;
  bottom: 0;
  left: 0;
  padding: 14px max(24px, calc((100% - 760px) / 2)) 18px;
  background: linear-gradient(180deg, rgba(242, 242, 247, 0), var(--wb-bg) 36%);
}

.chat-input {
  position: relative;
  border: 1px solid var(--wb-border);
  border-radius: 24px;
  background: var(--wb-surface);
  box-shadow: 0 2px 10px rgba(15, 23, 42, 0.04);
}

.chat-input:focus-within {
  border-color: var(--wb-border-strong);
  box-shadow: 0 0 0 3px var(--wb-brand-soft);
}

.file-input { display: none; }

.selected-file-name {
  display: block;
  max-width: 132px;
  overflow: hidden;
  padding: 7px 20px 7px 8px;
  color: var(--wb-text-2);
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
  color: var(--wb-text);
  font: inherit;
  font-size: 15px;
  line-height: 1.55;
}

.input-box::placeholder { color: var(--wb-muted); }

.input-actions { display: flex; height: 44px; align-items: center; gap: 7px; padding: 0 10px 9px 12px; }

.tool-button {
  display: grid;
  width: 30px;
  height: 30px;
  place-items: center;
  border: 0;
  border-radius: 9px;
  background: transparent;
  color: var(--wb-text-2);
}

.tool-button:hover { background: var(--wb-fill); color: var(--wb-text); }

/* DeepSeek 式带文字 pill 开关：联网搜索 / 知识库 */
.web-search-toggle, .knowledge-search-toggle {
  display: inline-flex;
  width: auto;
  gap: 5px;
  border: 1px solid var(--wb-border);
  padding: 0 12px;
}

.web-search-toggle.active, .knowledge-search-toggle.active {
  border-color: color-mix(in srgb, var(--wb-brand) 34%, transparent);
  background: var(--wb-brand-soft);
  color: var(--wb-brand);
}

.pill-label { font-size: 12px; font-weight: 550; white-space: nowrap; }

.input-hint { margin-right: auto; color: var(--wb-muted); font-size: 12px; }

.send-button {
  display: grid;
  width: 34px;
  height: 34px;
  place-items: center;
  border: 0;
  border-radius: 50%;
  padding: 0;
  background: var(--wb-text);
  color: var(--wb-surface);
  font-size: 18px;
  font-weight: 700;
  line-height: 1;
}

.send-button:hover:not(:disabled) { opacity: 0.88; }
.send-button:active:not(:disabled) { transform: scale(0.94); }
.send-button:disabled { background: var(--wb-fill-hover); color: var(--wb-muted); box-shadow: none; }

.stop-button {
  display: grid;
  width: 34px;
  height: 34px;
  place-items: center;
  border: 1px solid rgba(255, 59, 48, 0.28);
  border-radius: 50%;
  padding: 0;
  background: color-mix(in srgb, var(--wb-danger) 10%, transparent);
  color: var(--wb-danger);
  box-shadow: 0 0 0 0 rgba(255, 59, 48, 0.28);
  animation: stop-pulse 1.8s ease-out infinite;
}

.stop-button:hover { background: color-mix(in srgb, var(--wb-danger) 16%, transparent); }
.stop-button:active { transform: scale(0.92); }

@keyframes stop-pulse {
  0% { box-shadow: 0 0 0 0 rgba(255, 59, 48, 0.26); }
  70%, 100% { box-shadow: 0 0 0 7px rgba(255, 59, 48, 0); }
}

@media (prefers-reduced-motion: reduce) {
  .stop-button { animation: none; }
}

.streaming-pulse { display: inline-flex; align-items: center; gap: 3px; margin-left: 7px; color: var(--wb-brand); vertical-align: middle; }

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
  border: 1px solid var(--wb-border);
  border-radius: 14px;
  background: var(--wb-surface);
  backdrop-filter: var(--sk-blur);
  -webkit-backdrop-filter: var(--sk-blur);
  box-shadow: var(--wb-shadow-lg);
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
