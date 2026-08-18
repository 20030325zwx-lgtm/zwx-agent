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
          <span aria-hidden="true">⌕</span>
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
.knowledge-admin { height: 100vh; overflow: hidden; background: #f7f7f7; color: #202020; }.admin-header { display: flex; height: 92px; box-sizing: border-box; align-items: center; justify-content: space-between; padding: 18px 32px; border-bottom: 1px solid #e5e5e5; background: #fff; }.eyebrow { color: #999; font-size: 12px; }.admin-header h1 { margin: 4px 0 0; font-size: 22px; font-weight: 650; }.admin-actions { display:flex; align-items:center; gap:8px; }.admin-actions label { display:flex; align-items:center; gap:7px; color:#777; font-size:12px; }.admin-actions select { height:31px; border:1px solid #dedede; border-radius:6px; background:#fff; color:#444; padding:0 7px; }.upload-button { height:31px; border:0; border-radius:6px; padding:0 11px; background:#d65070; color:#fff; font-size:12px; }.upload-button:disabled { background:#d8d8d8; }.knowledge-file-input { display:none; }.workspace-notice { position:absolute; z-index:3; top:102px; right:24px; margin:0; border:1px solid #ead8b7; border-radius:6px; padding:7px 9px; background:#fffaf0; color:#8a661d; font-size:12px; }.workspace-notice button { margin-left:6px; border:0; background:transparent; color:#76500d; font:inherit; text-decoration:underline; }.knowledge-workspace { display: grid; height: calc(100vh - 92px); min-height: 0; grid-template-columns: 278px var(--chunk-panel-width) 10px minmax(0, 1fr); }.document-panel, .chunk-panel, .preview-panel { min-height: 0; }.document-panel, .chunk-panel { display: flex; flex-direction: column; border-right: 1px solid #e4e4e4; background: #fff; }.document-panel { padding: 13px 10px 0; }.private-heading { display:flex; align-items:center; justify-content:space-between; padding:4px 8px 8px; color:#4b4b4b; font-size:13px; font-weight:650; }.private-heading div { display:grid; gap:3px; }.private-heading small { color:#999; font-size:11px; font-weight:400; }.scope-status { border-radius:4px; background:#fceef1; color:#b3415d; padding:3px 5px; font-size:10px; }.private-document-list { max-height:144px; flex:0 0 auto; overflow:auto; border-bottom:1px solid #ededed; margin-bottom:12px; }.private-document-row { padding:8px 9px; }.private-document-row strong { display:block; overflow:hidden; font-size:12px; text-overflow:ellipsis; white-space:nowrap; }.private-document-row span { margin-right:6px; color:#999; font-size:10px; }.private-document-row em { display:inline-block; margin-top:4px; border-radius:4px; padding:2px 4px; font-size:10px; font-style:normal; }.status-ready { background:#e8f4ec; color:#43865a; }.status-pending,.status-indexing { background:#fff4dc; color:#9d731a; }.status-failed { background:#fbeaea; color:#a35757; }.private-state { padding:10px 9px; color:#999; font-size:11px; line-height:1.45; }.chunk-panel { padding: 13px 10px 0; background: #fbfbfb; }.panel-heading { display: flex; flex: 0 0 auto; align-items: center; justify-content: space-between; padding: 4px 8px 12px; color: #4b4b4b; font-size: 13px; font-weight: 650; }.panel-heading small { color: #999; font-size: 12px; font-weight: 400; }.document-search { display: flex; height: 34px; flex: 0 0 auto; align-items: center; gap: 7px; margin: 0 4px 10px; border: 1px solid #e3e3e3; border-radius: 6px; padding: 0 9px; color: #9a9a9a; }.document-search span { font-size: 17px; line-height: 1; transform: rotate(-20deg); }.document-search input { min-width: 0; width: 100%; border: 0; outline: 0; background: transparent; color: #333; font: inherit; font-size: 12px; }.document-list, .chunk-list { min-height: 0; overflow-y: auto; padding-bottom: 14px; }.document-row, .chunk-row { display: block; width: 100%; border: 0; border-radius: 7px; background: transparent; color: inherit; text-align: left; }.document-row { position: relative; padding: 11px 10px; }.document-row:hover, .document-row.active, .chunk-row:hover, .chunk-row.active { background: #f0f0f0; }.document-row strong { display: block; overflow: hidden; font-size: 13px; font-weight: 600; text-overflow: ellipsis; white-space: nowrap; }.document-row span { display: block; margin-top: 5px; color: #929292; font-size: 11px; }.document-row em { display: inline-block; margin-top: 7px; border-radius: 4px; padding: 2px 5px; font-size: 10px; font-style: normal; }.indexed { background: #e8f4ec; color: #43865a; }.pending { background: #f4eeee; color: #a35757; }.chunk-row { padding: 10px; }.chunk-row > span { color: #555; font-size: 12px; font-weight: 600; }.chunk-row small { color: #999; font-weight: 400; }.chunk-row p { margin: 6px 0 0; color: #888; font-size: 12px; line-height: 1.5; }.panel-state { padding: 18px 10px; color: #999; font-size: 13px; }.chunk-resizer { position: relative; z-index: 2; width: 10px; margin-left: -5px; cursor: col-resize; touch-action: none; }.chunk-resizer::after { position: absolute; top: 0; bottom: 0; left: 4px; width: 2px; background: transparent; content: ''; }.chunk-resizer:hover::after { background: #d65070; }.preview-panel { display: flex; min-width: 0; flex-direction: column; padding: 28px 34px 0; background: #fff; }.preview-heading { display: flex; min-height: 54px; flex: 0 0 auto; align-items: flex-start; justify-content: space-between; gap: 20px; border-bottom: 1px solid #ececec; padding-bottom: 17px; }.preview-heading h2 { margin: 4px 0 0; font-size: 18px; font-weight: 650; }.preview-heading code { max-width: 48%; overflow: hidden; color: #999; font-size: 11px; text-overflow: ellipsis; white-space: nowrap; }.preview-body { min-height: 0; flex: 1; overflow: auto; }.source-preview { margin: 21px 0 28px; white-space: pre-wrap; color: #303030; font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 13px; line-height: 1.75; }.unavailable-preview { display: grid; height: 100%; place-items: center; color: #999; font-size: 14px; text-align: center; } @media (max-width: 900px) { .knowledge-admin { overflow-x: auto; }.knowledge-workspace { min-width: 890px; grid-template-columns: 240px var(--chunk-panel-width) 10px minmax(360px, 1fr); }.preview-panel { padding: 24px 24px 0; } } @media (max-width:640px) { .admin-header { height:auto; min-height:92px; align-items:flex-start; gap:12px; flex-direction:column; padding:14px 18px; }.admin-actions { width:100%; }.knowledge-workspace { height:calc(100vh - 137px); } }
.private-document-list { max-height:calc(100vh - 202px); flex:1; }.private-document-row { display:block; width:100%; border:0; border-radius:7px; background:transparent; color:inherit; text-align:left; }.private-document-row:hover,.private-document-row.active { background:#f0f0f0; }
</style>
