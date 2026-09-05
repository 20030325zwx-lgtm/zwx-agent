<template>
  <main class="knowledge-admin">
    <header class="admin-header">
      <div>
        <span class="eyebrow">{{ currentAgent.name }}</span>
        <h1>知识库管理</h1>
      </div>
      <div class="admin-actions">
        <label>资料归属
          <select v-model="agentKey" :disabled="uploading" @change="loadPrivateDocuments">
            <option v-for="agent in KNOWLEDGE_AGENTS" :key="agent.key" :value="agent.key">{{ agent.name }}</option>
          </select>
        </label>
        <input ref="knowledgeFileInput" class="knowledge-file-input" type="file" accept=".md,.txt,.pdf,text/plain,text/markdown,application/pdf" @change="uploadPrivateDocument" />
        <button type="button" class="upload-button" :disabled="uploading" @click="knowledgeFileInput?.click()">{{ uploading ? '上传中...' : '上传资料' }}</button>
        <AgentSettingsMenu :agent-key="agentKey" default-theme="rose" />
      </div>
    </header>

    <p v-if="error" class="workspace-notice">{{ error }} <button type="button" @click="loadPrivateDocuments">重新加载</button></p>
    <section class="knowledge-workspace" :style="{ '--chunk-panel-width': `${chunkPanelWidth}px` }">
      <aside class="document-panel">
        <div class="private-heading">
          <div><span>私有资料</span><small>{{ privateDocuments.length }} 个 · 当前智能体</small></div>
          <span class="scope-status">{{ currentAgent.category }}</span>
        </div>
        <div class="private-document-list">
          <div v-if="loadingPrivateDocuments" class="private-state">正在读取资料...</div>
          <button v-for="document in filteredDocuments" :key="document.id" type="button" class="private-document-row" :class="{ active: document.id === selectedDocumentId }" @click="selectDocument(document.id)">
            <strong :title="document.filename">{{ document.filename }}</strong>
            <span>{{ document.chunkCount }} 个切片</span>
            <em :class="`status-${document.status.toLowerCase()}`">{{ document.status === 'READY' ? '已入库' : document.status === 'FAILED' ? '失败' : document.status === 'INDEXING' ? '切片中' : '等待中' }}</em>
          </button>
          <p v-if="!loadingPrivateDocuments && !filteredDocuments.length" class="private-state">上传 Markdown 或文本资料后，仅当前租户和智能体可检索。</p>
        </div>
        <label class="document-search">
          <svg viewBox="0 0 20 20" aria-hidden="true"><circle cx="8.5" cy="8.5" r="5.75" fill="none" stroke="currentColor" stroke-width="1.8" /><path d="M13 13 L17.5 17.5" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" /></svg>
          <input v-model.trim="documentQuery" type="search" placeholder="搜索文档名称" />
        </label>
      </aside>

      <aside class="chunk-panel">
        <div class="panel-heading"><span>实际切片</span><small>{{ detail?.chunks.length || 0 }} 个</small></div>
        <div class="chunk-list">
          <div v-if="loadingDetail" class="panel-state">正在读取切片...</div>
          <button v-for="chunk in detail?.chunks" :key="chunk.id" type="button" class="chunk-row"
            :class="{ active: chunk.id === selectedChunkId }" @click="selectedChunkId = chunk.id">
            <span>切片 {{ chunk.chunkIndex }}<small v-if="chunk.section"> · 第 {{ chunk.section }} 节</small></span>
            <p>{{ excerpt(chunk.content) }}</p>
          </button>
          <p v-if="detail && !detail.chunks.length" class="panel-state">该文档尚未写入向量库。</p>
        </div>
      </aside>

      <div class="chunk-resizer" role="separator" aria-orientation="vertical" aria-label="调整切片列宽度" @pointerdown="startResize"></div>

      <section class="preview-panel">
        <div class="preview-heading">
          <div><span class="eyebrow">原始文档预览</span><h2>{{ detail?.filename || '选择一个文档' }}</h2></div>
          <code v-if="detail">{{ detail.objectKey }}</code>
        </div>
        <div class="preview-body">
          <pre v-if="detail?.sourceContent" class="source-preview">{{ detail.sourceContent }}</pre>
          <div v-else-if="detail" class="unavailable-preview">该文档已在向量库中，但原始文件不在当前项目内，暂不能预览。</div>
          <div v-else class="unavailable-preview">选择左侧文档以查看其原文和切片。</div>
        </div>
      </section>
    </section>
  </main>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { useHead } from '@vueuse/head'
import { getAgentKnowledgeDocument, listAgentKnowledgeDocuments, uploadAgentKnowledgeDocument } from '../api'
import { KNOWLEDGE_AGENTS, getAgent } from '../config/agents'
import AgentSettingsMenu from '../components/AgentSettingsMenu.vue'

useHead({ title: '知识库管理 - ZWX Agent' })

const route = useRoute()
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
.knowledge-admin { height: 100vh; overflow: hidden; background: var(--sk-bg); color: var(--sk-label); }

.admin-header {
  display: flex;
  height: 88px;
  box-sizing: border-box;
  align-items: center;
  justify-content: space-between;
  padding: 18px 32px;
  border-bottom: 1px solid var(--sk-separator);
  background: var(--sk-material);
  backdrop-filter: var(--sk-blur);
  -webkit-backdrop-filter: var(--sk-blur);
}

.eyebrow { color: var(--sk-label-3); font-size: 11px; font-weight: 650; letter-spacing: 0.06em; text-transform: uppercase; }

.admin-header h1 { margin: 4px 0 0; font-size: 22px; font-weight: 800; letter-spacing: -0.02em; }

.admin-actions { display: flex; align-items: center; gap: 10px; }

.admin-actions label { display: flex; align-items: center; gap: 7px; color: var(--sk-label-2); font-size: 12px; font-weight: 550; }

.admin-actions select {
  height: 34px;
  border: 0;
  border-radius: 9px;
  background: var(--sk-fill);
  color: var(--sk-label);
  padding: 0 9px;
  font-size: 12px;
  outline: none;
}

.admin-actions select:focus { background: var(--sk-surface); box-shadow: 0 0 0 3px var(--zwx-primary-ring); }

.upload-button {
  height: 34px;
  border: 0;
  border-radius: 10px;
  padding: 0 14px;
  background: linear-gradient(180deg, #2590ff, var(--zwx-primary));
  color: #fff;
  font-size: 12px;
  font-weight: 650;
  box-shadow: 0 4px 10px var(--zwx-primary-ring);
}

.upload-button:hover:not(:disabled) { filter: brightness(1.06); }
.upload-button:disabled { background: var(--sk-fill-strong); color: var(--sk-label-2); box-shadow: none; }

.knowledge-file-input { display: none; }

.workspace-notice {
  position: absolute;
  z-index: 3;
  top: 98px;
  right: 24px;
  margin: 0;
  border: 1px solid rgba(255, 149, 0, 0.3);
  border-radius: 12px;
  padding: 8px 11px;
  background: rgba(255, 149, 0, 0.1);
  backdrop-filter: var(--sk-blur);
  -webkit-backdrop-filter: var(--sk-blur);
  color: #8a5a00;
  font-size: 12px;
  box-shadow: var(--sk-shadow-card);
}

.workspace-notice button { margin-left: 6px; border: 0; background: transparent; color: inherit; font: inherit; font-weight: 650; text-decoration: underline; text-underline-offset: 2px; }

.knowledge-workspace { display: grid; height: calc(100vh - 88px); min-height: 0; grid-template-columns: 280px var(--chunk-panel-width) 10px minmax(0, 1fr); }

.document-panel, .chunk-panel, .preview-panel { min-height: 0; }

.document-panel, .chunk-panel { display: flex; flex-direction: column; border-right: 1px solid var(--sk-separator); background: var(--sk-surface); }

.document-panel { padding: 14px 10px 12px; }

.private-heading { display: flex; align-items: center; justify-content: space-between; padding: 4px 8px 10px; color: var(--sk-label); font-size: 13px; font-weight: 700; }
.private-heading div { display: grid; gap: 3px; }
.private-heading small { color: var(--sk-label-3); font-size: 11px; font-weight: 400; }

.scope-status { border-radius: 6px; background: var(--zwx-primary-soft); color: var(--zwx-primary); padding: 3px 7px; font-size: 10px; font-weight: 700; }

.private-document-list { min-height: 0; flex: 1; overflow-y: auto; overscroll-behavior: contain; display: grid; gap: 2px; align-content: start; padding-bottom: 10px; }

.private-state { margin: 18px 10px; color: var(--sk-label-3); font-size: 13px; }

.private-document-row {
  display: grid;
  gap: 3px;
  border: 0;
  border-radius: 10px;
  padding: 9px 10px;
  background: transparent;
  color: inherit;
  text-align: left;
}

.private-document-row strong { overflow: hidden; font-size: 13px; font-weight: 600; text-overflow: ellipsis; white-space: nowrap; }
.private-document-row span { color: var(--sk-label-3); font-size: 11px; }

.private-document-row em { width: max-content; border-radius: 5px; padding: 2px 6px; font-size: 10px; font-style: normal; font-weight: 700; background: var(--sk-fill); color: var(--sk-label-2); }
.private-document-row em.status-ready { background: rgba(52, 199, 89, 0.14); color: #1f9d4d; }
.private-document-row em.status-failed { background: rgba(255, 59, 48, 0.12); color: var(--sk-red); }
.private-document-row em.status-indexing, .private-document-row em.status-pending { background: rgba(255, 149, 0, 0.14); color: #8a5a00; }

.private-document-row:hover { background: var(--sk-fill); }

.private-document-row.active { background: var(--zwx-primary-soft); }

.document-search {
  display: flex;
  height: 38px;
  flex: 0 0 38px;
  align-items: center;
  gap: 8px;
  border-radius: 11px;
  padding: 0 11px;
  background: var(--sk-fill);
  color: var(--sk-label-3);
}

.document-search svg { width: 15px; height: 15px; flex: 0 0 15px; }

.document-search input { width: 100%; border: 0; outline: 0; background: transparent; color: var(--sk-label); font-size: 13px; }
.document-search input::placeholder { color: var(--sk-label-3); }

.panel-heading { display: flex; align-items: center; justify-content: space-between; padding: 16px 14px 8px; color: var(--sk-label); font-size: 13px; font-weight: 700; }
.panel-heading small { color: var(--sk-label-3); font-size: 11px; font-weight: 400; }

.chunk-list { min-height: 0; flex: 1; overflow-y: auto; overscroll-behavior: contain; display: grid; gap: 2px; align-content: start; padding: 0 10px 12px; }

.panel-state { margin: 18px 8px; color: var(--sk-label-3); font-size: 13px; }

.chunk-row { display: grid; gap: 4px; border: 0; border-radius: 10px; padding: 9px 10px; background: transparent; color: inherit; text-align: left; }
.chunk-row span { color: var(--sk-label); font-size: 12px; font-weight: 650; }
.chunk-row span small { color: var(--sk-label-3); font-size: 11px; font-weight: 400; }
.chunk-row p { margin: 0; color: var(--sk-label-2); font-size: 12px; line-height: 1.5; }

.chunk-row:hover { background: var(--sk-fill); }
.chunk-row.active { background: var(--zwx-primary-soft); }

.chunk-resizer { cursor: col-resize; background: transparent; transition: background-color 0.15s ease; }
.chunk-resizer:hover { background: var(--zwx-primary-ring); }

.preview-panel { display: flex; min-width: 0; flex-direction: column; background: var(--sk-bg); }

.preview-heading { display: flex; flex: 0 0 auto; align-items: flex-start; justify-content: space-between; gap: 14px; padding: 18px 24px 14px; }
.preview-heading h2 { margin: 3px 0 0; font-size: 17px; font-weight: 700; letter-spacing: -0.01em; }

.preview-heading code { max-width: 320px; overflow: hidden; border-radius: 8px; padding: 4px 8px; background: var(--sk-surface); color: var(--sk-label-2); font-size: 11px; text-overflow: ellipsis; white-space: nowrap; box-shadow: 0 1px 2px rgba(0, 0, 0, 0.04); }

.preview-body { min-height: 0; flex: 1; overflow: hidden; margin: 0 20px 20px; border: 1px solid var(--sk-separator); border-radius: 18px; background: var(--sk-surface); box-shadow: var(--sk-shadow-card); }

.source-preview { height: 100%; overflow: auto; margin: 0; padding: 20px 22px; color: var(--sk-label); font: 12.5px/1.7 ui-monospace, "SF Mono", SFMono-Regular, Menlo, monospace; white-space: pre-wrap; word-break: break-word; }

.unavailable-preview { display: grid; height: 100%; place-items: center; padding: 24px; color: var(--sk-label-3); font-size: 13px; text-align: center; }
</style>
