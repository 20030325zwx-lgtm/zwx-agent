<template>
  <main class="knowledge-admin">
    <header class="admin-header">
      <div class="header-inner">
        <div class="header-start">
          <button class="back-button" type="button" aria-label="返回智能体目录" @click="router.push('/')"><ArrowLeft :size="16" />智能体目录</button>
          <span class="header-divider" aria-hidden="true"></span>
          <div class="header-title">
            <h1>知识库管理</h1>
            <span class="header-sub">{{ currentAgent.name }} · {{ currentAgent.category }}</span>
          </div>
        </div>
        <div class="header-actions">
          <label class="agent-select">
            <span>资料归属</span>
            <select v-model="agentKey" :disabled="uploading" @change="loadPrivateDocuments">
              <option v-for="agent in KNOWLEDGE_AGENTS" :key="agent.key" :value="agent.key">{{ agent.name }}</option>
            </select>
          </label>
          <input ref="knowledgeFileInput" class="knowledge-file-input" type="file" accept=".md,.txt,.pdf,text/plain,text/markdown,application/pdf" @change="uploadPrivateDocument" />
          <button type="button" class="upload-button" :disabled="uploading" @click="knowledgeFileInput?.click()"><Plus :size="15" />{{ uploading ? '上传中...' : '上传资料' }}</button>
          <AgentSettingsMenu :agent-key="agentKey" default-theme="rose" />
        </div>
      </div>
    </header>

    <transition name="notice">
      <p v-if="error" class="workspace-notice">{{ error }} <button type="button" @click="loadPrivateDocuments">重新加载</button></p>
    </transition>

    <section class="knowledge-workspace" :style="{ '--chunk-panel-width': `${chunkPanelWidth}px` }">
      <!-- 文档列表 -->
      <aside class="panel document-panel">
        <div class="panel-heading">
          <div class="panel-title"><span>私有资料</span><small>{{ privateDocuments.length }} 个 · 仅当前智能体可检索</small></div>
          <span class="scope-status">{{ currentAgent.category }}</span>
        </div>
        <div class="document-list">
          <div v-if="loadingPrivateDocuments" class="panel-state">正在读取资料...</div>
          <button v-for="document in filteredDocuments" :key="document.id" type="button" class="document-row" :class="{ active: document.id === selectedDocumentId }" @click="selectDocument(document.id)">
            <strong :title="document.filename">{{ document.filename }}</strong>
            <span class="document-meta">
              <em>{{ document.chunkCount }} 个切片</em>
              <i :class="`status-${document.status.toLowerCase()}`">{{ document.status === 'READY' ? '已入库' : document.status === 'FAILED' ? '失败' : document.status === 'INDEXING' ? '切片中' : '等待中' }}</i>
            </span>
          </button>
          <div v-if="!loadingPrivateDocuments && !filteredDocuments.length" class="panel-empty">
            <FileText :size="22" />
            <strong>还没有资料</strong>
            <span>上传 Markdown、TXT 或 PDF 后，将自动切片并向量化入库。</span>
          </div>
        </div>
        <label class="document-search">
          <svg viewBox="0 0 20 20" aria-hidden="true"><circle cx="8.5" cy="8.5" r="5.75" fill="none" stroke="currentColor" stroke-width="1.8" /><path d="M13 13 L17.5 17.5" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" /></svg>
          <input v-model.trim="documentQuery" type="search" placeholder="搜索文档名称" />
        </label>
      </aside>

      <!-- 切片列表 -->
      <aside class="panel chunk-panel">
        <div class="panel-heading">
          <div class="panel-title"><span>实际切片</span><small>{{ detail?.chunks.length || 0 }} 个向量切片</small></div>
        </div>
        <div class="chunk-list">
          <div v-if="loadingDetail" class="panel-state">正在读取切片...</div>
          <button v-for="chunk in detail?.chunks" :key="chunk.id" type="button" class="chunk-row"
            :class="{ active: chunk.id === selectedChunkId }" @click="selectedChunkId = chunk.id">
            <span class="chunk-index">切片 {{ chunk.chunkIndex }}<small v-if="chunk.section"> · 第 {{ chunk.section }} 节</small></span>
            <p>{{ excerpt(chunk.content) }}</p>
          </button>
          <div v-if="detail && !detail.chunks.length" class="panel-empty">
            <Sparkles :size="22" />
            <strong>该文档尚未写入向量库</strong>
            <span>等待索引完成后再来查看。</span>
          </div>
          <div v-else-if="!detail && !loadingDetail" class="panel-empty">
            <Layers :size="22" />
            <strong>选择一个文档</strong>
            <span>左侧选择文档后，这里展示它的实际向量切片。</span>
          </div>
        </div>
      </aside>

      <div class="chunk-resizer" role="separator" aria-orientation="vertical" aria-label="调整切片列宽度" @pointerdown="startResize"><i></i></div>

      <!-- 原文预览 -->
      <section class="panel preview-panel">
        <div class="preview-heading">
          <div class="panel-title">
            <span>原始文档预览</span>
            <strong>{{ detail?.filename || '选择一个文档' }}</strong>
          </div>
          <code v-if="detail">{{ detail.objectKey }}</code>
        </div>
        <div class="preview-body">
          <pre v-if="detail?.sourceContent" class="source-preview">{{ detail.sourceContent }}</pre>
          <div v-else-if="detail" class="preview-placeholder">
            <FileWarning :size="24" />
            <strong>暂不能预览原文</strong>
            <span>该文档已在向量库中，但原始文件不在当前项目内。</span>
          </div>
          <div v-else class="preview-placeholder">
            <BookOpen :size="24" />
            <strong>选择左侧文档开始浏览</strong>
            <span>这里会展示文档原文与对应的切片位置。</span>
          </div>
        </div>
      </section>
    </section>
  </main>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useHead } from '@vueuse/head'
import { ArrowLeft, BookOpen, FileText, FileWarning, Layers, Plus, Sparkles } from 'lucide-vue-next'
import { getAgentKnowledgeDocument, listAgentKnowledgeDocuments, uploadAgentKnowledgeDocument } from '../api'
import { KNOWLEDGE_AGENTS, getAgent } from '../config/agents'
import AgentSettingsMenu from '../components/AgentSettingsMenu.vue'

useHead({ title: '知识库管理 - ZWX Agent' })

const route = useRoute()
const router = useRouter()
const requestedAgentKey = typeof route.query.agentKey === 'string' && KNOWLEDGE_AGENTS.some(agent => agent.key === route.query.agentKey) ? route.query.agentKey : 'love'
const detail = ref(null)
const selectedDocumentId = ref('')
const selectedChunkId = ref('')
const loadingDetail = ref(false)
const error = ref('')
const agentKey = ref(requestedAgentKey)
const currentAgent = computed(() => getAgent(agentKey.value))
const privateDocuments = ref([])
const loadingPrivateDocuments = ref(false)
const uploading = ref(false)
const knowledgeFileInput = ref(null)
const documentQuery = ref('')
const chunkPanelWidth = ref(380)
let removeResizeListeners = () => {}
let privateRefreshTimer = null

const filteredDocuments = computed(() => {
  const keyword = documentQuery.value.toLocaleLowerCase('zh-CN')
  return privateDocuments.value.filter(document => document.filename.toLocaleLowerCase('zh-CN').includes(keyword))
})

const excerpt = content => content.replace(/\s+/g, ' ').slice(0, 88)
const loadPrivateDocuments = async () => {
  loadingPrivateDocuments.value = true
  detail.value = null
  selectedDocumentId.value = ''
  try {
    privateDocuments.value = await listAgentKnowledgeDocuments(agentKey.value)
    error.value = ''
  } catch (requestError) {
    error.value = '无法读取当前智能体的私有资料。'
    console.error(requestError)
  } finally {
    loadingPrivateDocuments.value = false
  }
}
const uploadPrivateDocument = async event => {
  const [file] = event.target.files
  event.target.value = ''
  if (!file) return
  if (!/\.(md|txt|pdf)$/i.test(file.name)) { error.value = '仅支持 .md、.txt 和 .pdf 文件。'; return }
  uploading.value = true
  error.value = ''
  try {
    await uploadAgentKnowledgeDocument(agentKey.value, file)
    await loadPrivateDocuments()
  } catch (requestError) {
    error.value = '上传失败，请确认文件、OSS 配置和后端服务。'
    console.error(requestError)
  } finally {
    uploading.value = false
  }
}
const selectDocument = async documentId => {
  selectedDocumentId.value = documentId
  selectedChunkId.value = ''
  loadingDetail.value = true
  try {
    detail.value = await getAgentKnowledgeDocument(agentKey.value, documentId)
  } catch (requestError) {
    error.value = '无法读取该文档的切片数据。'
    console.error(requestError)
  } finally {
    loadingDetail.value = false
  }
}

const startResize = event => {
  if (event.button !== 0) return
  removeResizeListeners()
  const startX = event.clientX
  const startWidth = chunkPanelWidth.value
  const resize = moveEvent => {
    chunkPanelWidth.value = Math.max(260, Math.min(680, startWidth + moveEvent.clientX - startX))
  }
  const stopResize = () => {
    window.removeEventListener('pointermove', resize)
    window.removeEventListener('pointerup', stopResize)
    removeResizeListeners = () => {}
  }
  window.addEventListener('pointermove', resize)
  window.addEventListener('pointerup', stopResize)
  removeResizeListeners = stopResize
}

onMounted(async () => {
  try {
    await loadPrivateDocuments()
    if (privateDocuments.value.length) await selectDocument(privateDocuments.value[0].id)
  } catch (requestError) {
    error.value = '无法连接知识库管理接口，请确认后端和 pgvector 服务正在运行。'
    console.error(requestError)
  } finally {
  }
  privateRefreshTimer = window.setInterval(() => {
    if (privateDocuments.value.some(document => document.status === 'PENDING' || document.status === 'INDEXING')) loadPrivateDocuments()
  }, 3000)
})

onBeforeUnmount(() => {
  removeResizeListeners()
  if (privateRefreshTimer) window.clearInterval(privateRefreshTimer)
})
</script>

<style scoped>
.knowledge-admin {
  position: relative;
  display: flex;
  height: 100vh;
  flex-direction: column;
  overflow: hidden;
  background:
    radial-gradient(50rem 30rem at 88% -16rem, rgba(0, 122, 255, 0.08), transparent 66%),
    var(--sk-bg);
  color: var(--sk-label);
}

/* ── 毛玻璃工具栏 ───────────────────────────────────── */
.admin-header {
  flex: 0 0 auto;
  border-bottom: 1px solid var(--sk-separator);
  background: var(--sk-material);
  backdrop-filter: var(--sk-blur);
  -webkit-backdrop-filter: var(--sk-blur);
}

.header-inner {
  display: flex;
  height: 66px;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding: 0 20px;
}

.header-start { display: flex; min-width: 0; align-items: center; gap: 14px; }

.back-button { display: flex; flex: 0 0 auto; align-items: center; gap: 6px; border: 0; border-radius: 10px; background: transparent; color: var(--zwx-primary); font-size: 13px; }
.back-button:hover { opacity: 0.7; }

.header-divider { width: 1px; height: 22px; background: var(--sk-separator-strong); }

.header-title { display: grid; min-width: 0; gap: 1px; }
.header-title h1 { font-size: 17px; font-weight: 800; letter-spacing: -0.02em; line-height: 1.2; }
.header-sub { overflow: hidden; color: var(--sk-label-3); font-size: 11px; text-overflow: ellipsis; white-space: nowrap; }

.header-actions { display: flex; flex: 0 0 auto; align-items: center; gap: 10px; }

.agent-select { display: flex; align-items: center; gap: 8px; color: var(--sk-label-2); font-size: 12px; font-weight: 550; }

.agent-select select {
  height: 36px;
  border: 0;
  border-radius: 10px;
  background: var(--sk-fill);
  color: var(--sk-label);
  padding: 0 10px;
  font-size: 12px;
  outline: none;
}

.agent-select select:focus { background: var(--sk-surface); box-shadow: 0 0 0 3px var(--zwx-primary-ring); }

.knowledge-file-input { display: none; }

.upload-button {
  display: flex;
  align-items: center;
  gap: 6px;
  height: 36px;
  border: 0;
  border-radius: 11px;
  padding: 0 14px;
  background: linear-gradient(180deg, #2590ff, var(--zwx-primary));
  color: #fff;
  font-size: 12px;
  font-weight: 650;
  box-shadow: 0 4px 10px var(--zwx-primary-ring);
}

.upload-button:hover:not(:disabled) { filter: brightness(1.06); }
.upload-button:active:not(:disabled) { transform: scale(0.98); }
.upload-button:disabled { background: var(--sk-fill-strong); color: var(--sk-label-2); box-shadow: none; }

/* ── 错误提示浮层 ───────────────────────────────────── */
.workspace-notice {
  position: absolute;
  z-index: 20;
  top: 78px;
  right: 20px;
  margin: 0;
  border: 1px solid rgba(255, 149, 0, 0.3);
  border-radius: 14px;
  padding: 9px 13px;
  background: var(--sk-material-strong);
  backdrop-filter: var(--sk-blur);
  -webkit-backdrop-filter: var(--sk-blur);
  color: #8a5a00;
  font-size: 12px;
  box-shadow: var(--sk-shadow-pop);
}

.workspace-notice button { margin-left: 6px; border: 0; background: transparent; color: inherit; font: inherit; font-weight: 650; text-decoration: underline; text-underline-offset: 2px; }

.notice-enter-active, .notice-leave-active { transition: opacity 0.2s ease, transform 0.2s ease; }
.notice-enter-from, .notice-leave-to { opacity: 0; transform: translateY(-6px); }

/* ── 三栏工作区：灰色画布上的圆角卡片 ──────────────── */
.knowledge-workspace {
  display: grid;
  min-height: 0;
  flex: 1;
  grid-template-columns: 288px var(--chunk-panel-width) 14px minmax(0, 1fr);
  padding: 14px 20px 20px;
}

.panel {
  display: flex;
  min-height: 0;
  flex-direction: column;
  overflow: hidden;
  border: 1px solid var(--sk-separator);
  border-radius: 18px;
  background: var(--sk-surface);
  box-shadow: var(--sk-shadow-card);
}

.panel-heading {
  display: flex;
  flex: 0 0 auto;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  border-bottom: 1px solid var(--sk-separator);
  padding: 14px 16px 12px;
}

.panel-title { display: grid; min-width: 0; gap: 2px; }
.panel-title span { font-size: 13px; font-weight: 700; letter-spacing: -0.01em; }
.panel-title small { overflow: hidden; color: var(--sk-label-3); font-size: 11px; text-overflow: ellipsis; white-space: nowrap; }
.panel-title strong { overflow: hidden; font-size: 15px; font-weight: 700; letter-spacing: -0.01em; text-overflow: ellipsis; white-space: nowrap; }

.scope-status { flex: 0 0 auto; border-radius: 7px; background: var(--zwx-primary-soft); color: var(--zwx-primary); padding: 4px 8px; font-size: 10px; font-weight: 700; }

.panel-state { padding: 24px 16px; color: var(--sk-label-3); font-size: 13px; }

.panel-empty {
  display: grid;
  justify-items: center;
  gap: 6px;
  margin: 18px 14px;
  border: 1px dashed var(--sk-separator-strong);
  border-radius: 14px;
  padding: 26px 16px;
  color: var(--sk-label-2);
  font-size: 12px;
  text-align: center;
}

.panel-empty :deep(svg) { color: var(--sk-label-3); }
.panel-empty strong { color: var(--sk-label); font-size: 13px; }

/* ── 文档列表 ───────────────────────────────────────── */
.document-list { min-height: 0; flex: 1; overflow-y: auto; overscroll-behavior: contain; display: grid; gap: 3px; align-content: start; padding: 10px; }

.document-row { display: grid; gap: 5px; border: 0; border-radius: 12px; padding: 10px 12px; background: transparent; color: inherit; text-align: left; }

.document-row strong { overflow: hidden; font-size: 13px; font-weight: 600; text-overflow: ellipsis; white-space: nowrap; }

.document-meta { display: flex; align-items: center; gap: 7px; }
.document-meta em { color: var(--sk-label-3); font-size: 11px; font-style: normal; }

.document-meta i { border-radius: 6px; padding: 2px 7px; font-size: 10px; font-style: normal; font-weight: 700; background: var(--sk-fill); color: var(--sk-label-2); }
.document-meta i.status-ready { background: rgba(52, 199, 89, 0.14); color: #1f9d4d; }
.document-meta i.status-failed { background: rgba(255, 59, 48, 0.12); color: var(--sk-red); }
.document-meta i.status-indexing, .document-meta i.status-pending { background: rgba(255, 149, 0, 0.14); color: #8a5a00; }

.document-row:hover { background: var(--sk-fill); }

.document-row.active { background: var(--zwx-primary-soft); }
.document-row.active em { color: var(--zwx-primary); }

.document-search { display: flex; height: 40px; flex: 0 0 40px; margin: 0 10px 12px; align-items: center; gap: 8px; border-radius: 12px; padding: 0 12px; background: var(--sk-fill); color: var(--sk-label-3); }

.document-search svg { width: 15px; height: 15px; flex: 0 0 15px; }

.document-search input { width: 100%; border: 0; outline: 0; background: transparent; color: var(--sk-label); font-size: 13px; }
.document-search input::placeholder { color: var(--sk-label-3); }

/* ── 切片列表 ───────────────────────────────────────── */
.chunk-list { min-height: 0; flex: 1; overflow-y: auto; overscroll-behavior: contain; display: grid; gap: 3px; align-content: start; padding: 10px; }

.chunk-row { display: grid; gap: 5px; border: 0; border-radius: 12px; padding: 10px 12px; background: transparent; color: inherit; text-align: left; }

.chunk-index { color: var(--sk-label); font-size: 12px; font-weight: 700; }
.chunk-index small { color: var(--sk-label-3); font-size: 11px; font-weight: 400; }

.chunk-row p { margin: 0; color: var(--sk-label-2); font-size: 12px; line-height: 1.55; }

.chunk-row:hover { background: var(--sk-fill); }
.chunk-row.active { background: var(--zwx-primary-soft); }
.chunk-row.active p { color: var(--sk-label); }

/* ── 拖拽分隔条 ─────────────────────────────────────── */
.chunk-resizer { position: relative; cursor: col-resize; }

.chunk-resizer i {
  position: absolute;
  top: 50%;
  left: 50%;
  width: 4px;
  height: 44px;
  border-radius: 999px;
  background: var(--sk-separator-strong);
  transform: translate(-50%, -50%);
  transition: background-color 0.15s ease, height 0.15s ease;
}

.chunk-resizer:hover i, .chunk-resizer:active i { height: 64px; background: var(--zwx-primary); }

/* ── 原文预览 ───────────────────────────────────────── */
.preview-heading { display: flex; flex: 0 0 auto; align-items: center; justify-content: space-between; gap: 14px; border-bottom: 1px solid var(--sk-separator); padding: 14px 18px 12px; }

.preview-heading code { max-width: 320px; overflow: hidden; border-radius: 8px; padding: 4px 9px; background: var(--sk-fill); color: var(--sk-label-2); font-size: 11px; text-overflow: ellipsis; white-space: nowrap; }

.preview-body { min-height: 0; flex: 1; display: flex; flex-direction: column; }

.source-preview { flex: 1; overflow: auto; margin: 0; padding: 18px 22px; color: var(--sk-label); font: 12.5px/1.75 ui-monospace, "SF Mono", SFMono-Regular, Menlo, monospace; white-space: pre-wrap; word-break: break-word; }

.preview-placeholder { display: grid; flex: 1; place-content: center; justify-items: center; gap: 7px; padding: 24px; color: var(--sk-label-2); font-size: 13px; text-align: center; }
.preview-placeholder :deep(svg) { color: var(--sk-label-3); }
.preview-placeholder strong { color: var(--sk-label); font-size: 15px; }

/* ── 响应式 ─────────────────────────────────────────── */
@media (max-width: 960px) {
  .knowledge-workspace { grid-template-columns: 232px var(--chunk-panel-width) 14px minmax(0, 1fr); }
  .agent-select span { display: none; }
}

@media (max-width: 720px) {
  .header-inner { height: auto; flex-wrap: wrap; padding: 10px 14px; }
  .back-button span { display: none; }
  .knowledge-workspace { display: flex; flex-direction: column; overflow-y: auto; padding: 12px 14px 16px; }
  .panel { flex: 0 0 auto; max-height: none; }
  .document-list, .chunk-list { max-height: 320px; }
  .chunk-resizer { display: none; }
  .source-preview, .preview-placeholder { min-height: 260px; }
}
</style>
