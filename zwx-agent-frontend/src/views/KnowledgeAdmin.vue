<template>
  <main class="knowledge-admin">
    <header class="admin-header">
      <div class="header-inner">
        <div class="header-title">
          <span class="title-kicker"><Database :size="12" /> KNOWLEDGE BASE</span>
          <h1>知识库</h1>
          <span class="header-sub">管理私有资料、检索切片与发布状态</span>
        </div>
        <div class="header-actions">
          <label class="agent-switcher">
            <span class="agent-switcher-icon">{{ currentAgent.icon }}</span>
            <span class="agent-switcher-copy">
              <small>资料归属</small>
              <select v-model="agentKey" :disabled="uploading" @change="switchAgent">
                <option v-for="agent in KNOWLEDGE_AGENTS" :key="agent.key" :value="agent.key">{{ agent.name }}</option>
              </select>
            </span>
            <ChevronDown :size="14" class="agent-chevron" />
          </label>
          <button class="icon-button" type="button" title="刷新文档列表" aria-label="刷新文档列表" :disabled="loadingPrivateDocuments" @click="loadPrivateDocuments({ preserveSelection: true })">
            <RefreshCw :size="15" :class="{ spinning: loadingPrivateDocuments }" />
          </button>
          <input ref="knowledgeFileInput" class="knowledge-file-input" type="file" accept=".md,.txt,.pdf,.doc,.docx,.xls,.xlsx,.ppt,.pptx" @change="uploadPrivateDocument" />
          <button v-if="canPublish" type="button" class="publish-button" :disabled="publishing" @click="publishSelected">{{ publishing ? '发布中...' : '发布待发布版本' }}</button>
          <button type="button" class="upload-button" :disabled="uploading" :title="`上传到：${targetUploadCategoryName}`" @click="knowledgeFileInput?.click()"><Upload :size="15" />{{ uploading ? '上传中...' : '上传资料' }}</button>
        </div>
      </div>
    </header>

    <section class="knowledge-overview" aria-label="知识库概览">
      <article class="stat-card stat-total">
        <span class="stat-icon"><Database :size="18" /></span>
        <div class="stat-copy"><small>文档总数</small><strong>{{ overview.total }}</strong></div>
        <span class="stat-foot">当前智能体</span>
      </article>
      <article class="stat-card stat-active">
        <span class="stat-icon"><CircleCheck :size="18" /></span>
        <div class="stat-copy"><small>生效中</small><strong>{{ overview.active }}</strong></div>
        <span class="stat-foot">可被问答检索</span>
      </article>
      <article class="stat-card stat-pending">
        <span class="stat-icon"><Clock3 :size="18" /></span>
        <div class="stat-copy"><small>待发布</small><strong>{{ overview.pending }}</strong></div>
        <span class="stat-foot">索引完成待生效</span>
      </article>
      <article class="stat-card stat-chunks">
        <span class="stat-icon"><Layers :size="18" /></span>
        <div class="stat-copy"><small>向量切片</small><strong>{{ overview.chunks }}</strong></div>
        <span class="stat-foot">全部文档合计</span>
      </article>
    </section>

    <transition name="notice">
      <p v-if="error" class="workspace-notice">{{ error }} <button type="button" @click="loadPrivateDocuments({ preserveSelection: true })">重新加载</button></p>
    </transition>

    <section class="knowledge-workspace" :style="{ '--chunk-panel-width': `${chunkPanelWidth}px` }">
      <aside class="panel document-panel">
        <div class="panel-heading">
          <div class="panel-title">
            <span>私有资料</span>
            <small>{{ privateDocuments.length }} 个文档 · 仅当前智能体可检索</small>
          </div>
          <span class="scope-status">{{ currentAgent.category }}</span>
        </div>

        <div class="document-toolbar">
          <div class="category-bar">
            <div class="category-chips" role="group" aria-label="文档分类">
              <button type="button" :class="{ active: selectedCategoryId === 'ALL' }" @click="selectedCategoryId = 'ALL'">
                全部<em>{{ privateDocuments.length }}</em>
              </button>
              <button v-for="category in categories" :key="category.id" type="button" :class="{ active: selectedCategoryId === category.id }" @click="selectedCategoryId = category.id">
                {{ category.name }}<em>{{ categoryCount(category.id) }}</em>
              </button>
            </div>
            <button class="category-add" type="button" title="新建分类" aria-label="新建分类" @click="startCreateCategory"><FolderPlus :size="13" />新建</button>
          </div>
          <div v-if="creatingCategory" class="category-create">
            <input ref="categoryInput" v-model.trim="categoryDraft" maxlength="40" placeholder="输入新分类名称" @keydown.enter.prevent="submitCategory" @keydown.esc.prevent="cancelCreateCategory" />
            <button type="button" class="category-confirm" :disabled="!categoryDraft || savingCategory" @click="submitCategory">{{ savingCategory ? '创建中' : '创建' }}</button>
            <button type="button" class="category-cancel" @click="cancelCreateCategory">取消</button>
          </div>
          <label class="document-search">
            <Search :size="15" />
            <input v-model.trim="documentQuery" type="search" placeholder="搜索文档名称" />
          </label>
          <div class="filter-chips" role="group" aria-label="按状态筛选文档">
            <button v-for="option in filterOptions" :key="option.value" type="button" :class="{ active: lifecycleFilter === option.value }" @click="lifecycleFilter = option.value">
              {{ option.label }}<em>{{ option.count }}</em>
            </button>
          </div>
        </div>

        <div class="document-list">
          <div v-if="loadingPrivateDocuments && !privateDocuments.length" class="panel-state">正在读取资料...</div>
          <button v-for="document in filteredDocuments" :key="document.id" type="button" class="document-row" :class="{ active: document.id === selectedDocumentId }" @click="selectDocument(document.id)">
            <span class="doc-icon" :class="documentTone(document.filename)">
              <FileText :size="16" />
              <em>{{ fileExtension(document.filename) }}</em>
            </span>
            <span class="doc-main">
              <strong :title="document.filename">{{ document.filename }}</strong>
              <span class="document-meta">
                <em>{{ document.chunkCount }} 个切片</em>
                <i v-if="document.versionNo != null" class="version-chip">v{{ document.versionNo }}</i>
                <i :class="`lc-${lifecycleClass(document)}`">{{ lifecycleLabel(document) }}</i>
                <i class="category-chip">{{ categoryName(document.categoryId) }}</i>
              </span>
            </span>
            <span class="doc-status-dot" :class="`lc-${lifecycleClass(document)}`" aria-hidden="true"></span>
          </button>
          <div v-if="!loadingPrivateDocuments && privateDocuments.length && !filteredDocuments.length" class="panel-empty compact">
            <FolderOpen :size="22" />
            <strong>没有匹配的文档</strong>
            <span>调整搜索词或状态筛选后再试。</span>
            <button type="button" class="text-action" @click="resetFilters">清除筛选</button>
          </div>
          <div v-if="!loadingPrivateDocuments && !privateDocuments.length" class="panel-empty">
            <span class="empty-upload-icon"><Upload :size="22" /></span>
            <strong>还没有资料</strong>
            <span>上传 Markdown、TXT、PDF 或 Office 文档后，将自动切片并向量化入库。</span>
            <button type="button" class="empty-cta" @click="knowledgeFileInput?.click()">上传第一份资料</button>
          </div>
        </div>

        <div class="list-foot">
          <span>显示 {{ filteredDocuments.length }} / {{ privateDocuments.length }}</span>
          <span v-if="overview.processing">{{ overview.processing }} 个处理中</span>
        </div>
      </aside>

      <aside class="panel chunk-panel">
        <div class="panel-heading">
          <div class="panel-title">
            <span>实际切片</span>
            <small>{{ detail?.filename || '选择文档后查看' }}</small>
          </div>
          <span class="panel-count">{{ detail?.chunks.length || 0 }} 个</span>
        </div>
        <div class="chunk-list">
          <div v-if="loadingDetail" class="panel-state">正在读取切片...</div>
          <button v-for="chunk in detail?.chunks" :key="chunk.id" type="button" class="chunk-row"
            :class="{ active: chunk.id === selectedChunkId }" @click="selectedChunkId = chunk.id">
            <span class="chunk-top">
              <span class="chunk-index">切片 {{ chunk.chunkIndex }}</span>
              <span v-if="chunk.section" class="section-chip">第 {{ chunk.section }} 节</span>
            </span>
            <p>{{ chunk.id === selectedChunkId ? chunk.content : excerpt(chunk.content) }}</p>
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

      <section class="panel preview-panel">
        <div class="preview-heading">
          <div class="panel-title">
            <span>原始文档预览</span>
            <strong>{{ detail?.filename || '选择一个文档' }}</strong>
          </div>
          <div v-if="detail" class="preview-badges">
            <span class="preview-badge">{{ detail.chunks.length }} 切片</span>
            <span v-if="selectedDocument?.versionNo != null" class="preview-badge">v{{ selectedDocument.versionNo }}</span>
            <span v-if="selectedDocument" class="preview-badge" :class="`lc-${lifecycleClass(selectedDocument)}`">{{ lifecycleLabel(selectedDocument) }}</span>
            <label v-if="selectedDocument" class="category-move" title="移动文档到其他分类">
              <FolderTree :size="13" />
              <select :value="selectedDocument.categoryId" @change="moveSelectedCategory($event.target.value)">
                <option v-for="category in categories" :key="category.id" :value="category.id">{{ category.name }}</option>
              </select>
            </label>
            <button v-if="selectedDocument" class="reindex-button" type="button" :disabled="reindexing" title="重新解析并重建向量切片" @click="reindexSelected">
              <RefreshCw :size="13" :class="{ spinning: reindexing }" />{{ reindexing ? '提交中...' : '重新解析' }}
            </button>
          </div>
        </div>
        <div v-if="detail?.objectKey" class="preview-path">
          <span>对象存储路径</span>
          <code>{{ detail.objectKey }}</code>
        </div>
        <div class="preview-body">
          <KnowledgeDocumentViewer v-if="selectedDocumentId" :agent-key="agentKey" :document-id="selectedDocumentId" :filename="selectedDocument?.filename || detail?.filename || ''" />
          <div v-else class="preview-placeholder">
            <BookOpen :size="24" />
            <strong>选择左侧文档开始浏览</strong>
            <span>这里会直接渲染 PDF、Word、Excel 和 PPT 原文。</span>
          </div>
        </div>
      </section>
    </section>
  </main>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { useHead } from '@vueuse/head'
import { BookOpen, ChevronDown, CircleCheck, Clock3, Database, FileText, FolderOpen, FolderPlus, FolderTree, Layers, RefreshCw, Search, Sparkles, Upload } from 'lucide-vue-next'
import { createAgentKnowledgeCategory, getAgentKnowledgeDocument, listAgentKnowledgeCategories, listAgentKnowledgeDocuments, moveAgentKnowledgeDocument, publishAgentKnowledgeDocument, reindexAgentKnowledgeDocument, uploadAgentKnowledgeDocument } from '../api'
import { KNOWLEDGE_AGENTS, getAgent } from '../config/agents'
import KnowledgeDocumentViewer from '../components/KnowledgeDocumentViewer.vue'

useHead({ title: '知识库管理 - ZWX Agent' })

const route = useRoute()
const requestedAgentKey = typeof route.query.agentKey === 'string' && KNOWLEDGE_AGENTS.some(agent => agent.key === route.query.agentKey) ? route.query.agentKey : 'super'
const detail = ref(null)
const selectedDocumentId = ref('')
const selectedChunkId = ref('')
const loadingDetail = ref(false)
const error = ref('')
const agentKey = ref(requestedAgentKey)
const currentAgent = computed(() => getAgent(agentKey.value))
const privateDocuments = ref([])
const categories = ref([])
const selectedCategoryId = ref('ALL')
const creatingCategory = ref(false)
const categoryDraft = ref('')
const savingCategory = ref(false)
const categoryInput = ref(null)
const loadingPrivateDocuments = ref(false)
const uploading = ref(false)
const publishing = ref(false)
const reindexing = ref(false)
const knowledgeFileInput = ref(null)
const documentQuery = ref('')
const lifecycleFilter = ref('ALL')
const chunkPanelWidth = ref(380)
const selectedDocument = computed(() => privateDocuments.value.find(document => document.id === selectedDocumentId.value))
const canPublish = computed(() => selectedDocument.value?.lifecycleStatus === 'READY')
const defaultCategory = computed(() => categories.value.find(category => category.name === '默认分类') || categories.value[0])
const targetUploadCategoryId = computed(() => selectedCategoryId.value === 'ALL' ? defaultCategory.value?.id : selectedCategoryId.value)
const targetUploadCategoryName = computed(() => categories.value.find(category => category.id === targetUploadCategoryId.value)?.name || '默认分类')
const categoryName = categoryId => categories.value.find(category => category.id === categoryId)?.name || '未分类'
const categoryCount = categoryId => privateDocuments.value.filter(document => document.categoryId === categoryId).length
let removeResizeListeners = () => {}
let privateRefreshTimer = null

const lifecycleValue = document => {
  if (['PENDING', 'INDEXING'].includes(document.status)) return document.status
  if (document.status === 'FAILED' && !document.chunkCount) return 'FAILED'
  if (!document.chunkCount) return 'EMPTY'
  return document.lifecycleStatus || document.status || 'INDEXING'
}
const overview = computed(() => {
  const documents = privateDocuments.value
  return {
    total: documents.length,
    active: documents.filter(document => lifecycleValue(document) === 'ACTIVE').length,
    pending: documents.filter(document => lifecycleValue(document) === 'READY').length,
    processing: documents.filter(document => ['PENDING', 'INDEXING'].includes(lifecycleValue(document))).length,
    chunks: documents.reduce((sum, document) => sum + (Number(document.chunkCount) || 0), 0)
  }
})
const filterOptions = computed(() => [
  { value: 'ALL', label: '全部', count: privateDocuments.value.length },
  { value: 'ACTIVE', label: '生效中', count: privateDocuments.value.filter(document => lifecycleValue(document) === 'ACTIVE').length },
  { value: 'READY', label: '待发布', count: privateDocuments.value.filter(document => lifecycleValue(document) === 'READY').length },
  { value: 'ARCHIVED', label: '已归档', count: privateDocuments.value.filter(document => lifecycleValue(document) === 'ARCHIVED').length }
])
const filteredDocuments = computed(() => {
  const keyword = documentQuery.value.toLocaleLowerCase('zh-CN')
  return privateDocuments.value.filter(document => {
    const matchesKeyword = document.filename.toLocaleLowerCase('zh-CN').includes(keyword)
    const matchesLifecycle = lifecycleFilter.value === 'ALL' || lifecycleValue(document) === lifecycleFilter.value
    const matchesCategory = selectedCategoryId.value === 'ALL' || document.categoryId === selectedCategoryId.value
    return matchesKeyword && matchesLifecycle && matchesCategory
  })
})

const excerpt = content => {
  const normalized = content.replace(/\s+/g, ' ').trim()
  return normalized.length > 88 ? `${normalized.slice(0, 88)}…` : normalized
}
const fileExtension = filename => (filename?.split('.').pop() || 'FILE').toUpperCase().slice(0, 4)
const documentTone = filename => {
  const extension = (filename?.split('.').pop() || '').toLowerCase()
  if (extension === 'pdf') return 'tone-pdf'
  if (['doc', 'docx'].includes(extension)) return 'tone-doc'
  if (['xls', 'xlsx'].includes(extension)) return 'tone-sheet'
  if (['ppt', 'pptx'].includes(extension)) return 'tone-slide'
  if (extension === 'md') return 'tone-markdown'
  return 'tone-text'
}
const lifecycleClass = document => lifecycleValue(document).toLowerCase()
const lifecycleLabel = document => ({
  ACTIVE: '生效中',
  READY: '待发布',
  ARCHIVED: '已归档',
  FAILED: '解析失败',
  EMPTY: '无切片',
  INDEXING: '处理中',
  PENDING: '等待中',
  CONFLICT: '冲突'
})[lifecycleValue(document)] || '等待中'
const resetFilters = () => { documentQuery.value = ''; lifecycleFilter.value = 'ALL'; selectedCategoryId.value = 'ALL' }

const loadCategories = async () => {
  try {
    categories.value = await listAgentKnowledgeCategories(agentKey.value)
  } catch (requestError) {
    error.value = '无法读取知识库分类。'
    console.error(requestError)
  }
}
const switchAgent = async () => {
  selectedCategoryId.value = 'ALL'
  lifecycleFilter.value = 'ALL'
  documentQuery.value = ''
  await loadCategories()
  await loadPrivateDocuments()
  if (privateDocuments.value.length) await selectDocument(privateDocuments.value[0].id)
}
const startCreateCategory = async () => {
  creatingCategory.value = true
  categoryDraft.value = ''
  await nextTick()
  categoryInput.value?.focus()
}
const cancelCreateCategory = () => {
  creatingCategory.value = false
  categoryDraft.value = ''
}
const submitCategory = async () => {
  const name = categoryDraft.value.trim()
  if (!name || savingCategory.value) return
  savingCategory.value = true
  try {
    const created = await createAgentKnowledgeCategory(agentKey.value, name)
    await loadCategories()
    selectedCategoryId.value = created.id
    await loadPrivateDocuments()
    creatingCategory.value = false
    categoryDraft.value = ''
  } catch (requestError) {
    error.value = requestError?.response?.status === 409 ? '分类已存在，请换一个名称。' : '创建分类失败，名称可能已存在。'
    console.error(requestError)
  } finally {
    savingCategory.value = false
  }
}
const moveSelectedCategory = async categoryId => {
  const document = selectedDocument.value
  if (!document || document.categoryId === categoryId) return
  try {
    await moveAgentKnowledgeDocument(document.id, agentKey.value, categoryId)
    selectedCategoryId.value = categoryId
    await loadPrivateDocuments({ preserveSelection: true })
  } catch (requestError) {
    error.value = '移动文档分类失败，请稍后重试。'
    console.error(requestError)
  }
}

const loadPrivateDocuments = async ({ preserveSelection = false } = {}) => {
  const previous = selectedDocumentId.value
  loadingPrivateDocuments.value = true
  if (!preserveSelection) {
    detail.value = null
    selectedDocumentId.value = ''
  }
  try {
    privateDocuments.value = await listAgentKnowledgeDocuments(agentKey.value)
    error.value = ''
    if (preserveSelection) {
      if (previous && privateDocuments.value.some(document => document.id === previous)) {
        await selectDocument(previous)
      } else {
        selectedDocumentId.value = ''
        detail.value = null
      }
    }
  } catch (requestError) {
    error.value = '无法读取当前智能体的私有资料。'
    console.error(requestError)
  } finally {
    loadingPrivateDocuments.value = false
  }
}
const reindexSelected = async () => {
  const document = selectedDocument.value
  if (!document || reindexing.value) return
  reindexing.value = true
  error.value = ''
  try {
    await reindexAgentKnowledgeDocument(document.id, agentKey.value)
    window.setTimeout(() => loadPrivateDocuments({ preserveSelection: true }), 1200)
    window.setTimeout(() => {
      loadPrivateDocuments({ preserveSelection: true })
      reindexing.value = false
    }, 4000)
  } catch (requestError) {
    error.value = '提交重新解析失败，请稍后重试。'
    console.error(requestError)
    reindexing.value = false
  }
}
const publishSelected = async () => {
  const document = selectedDocument.value
  if (!document || document.lifecycleStatus !== 'READY' || publishing.value) return
  publishing.value = true
  try {
    await publishAgentKnowledgeDocument(document.id, agentKey.value)
    await loadPrivateDocuments()
    await selectDocument(document.id)
  } catch (requestError) {
    error.value = '发布文档失败，请检查文档状态后重试。'
    console.error(requestError)
  } finally {
    publishing.value = false
  }
}
const uploadPrivateDocument = async event => {
  const [file] = event.target.files
  event.target.value = ''
  if (!file) return
  if (!/\.(md|txt|pdf|doc|docx|xls|xlsx|ppt|pptx)$/i.test(file.name)) {
    error.value = '仅支持 Markdown、TXT、PDF、Word、Excel 和 PPT 文件。'
    return
  }
  uploading.value = true
  error.value = ''
  try {
    const uploaded = await uploadAgentKnowledgeDocument(agentKey.value, file, targetUploadCategoryId.value)
    if (uploaded?.categoryId) selectedCategoryId.value = uploaded.categoryId
    await loadPrivateDocuments()
    if (uploaded?.id) await selectDocument(uploaded.id)
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
    await loadCategories()
    await loadPrivateDocuments()
    if (privateDocuments.value.length) await selectDocument(privateDocuments.value[0].id)
  } catch (requestError) {
    error.value = '无法连接知识库管理接口，请确认后端和 pgvector 服务正在运行。'
    console.error(requestError)
  }
  privateRefreshTimer = window.setInterval(() => {
    if (privateDocuments.value.some(document => ['PENDING', 'INDEXING'].includes(lifecycleValue(document)))) loadPrivateDocuments({ preserveSelection: true })
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
  height: calc(100vh - 72px);
  min-height: 560px;
  flex-direction: column;
  overflow: hidden;
  background:
    radial-gradient(52rem 30rem at 90% -18rem, rgba(0, 122, 255, 0.12), transparent 66%),
    radial-gradient(38rem 26rem at -8% 12%, rgba(99, 102, 241, 0.08), transparent 68%),
    var(--sk-bg);
  color: var(--sk-label);
}

/* ── 内容标题与操作区 ───────────────────────────────── */
.admin-header { flex: 0 0 auto; }

.header-inner {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 20px;
  padding: 18px 22px 12px;
}

.header-title { display: grid; min-width: 0; gap: 1px; }
.title-kicker { display: flex; align-items: center; gap: 4px; color: var(--zwx-primary); font-size: 10px; font-weight: 800; letter-spacing: 0.14em; }
.header-title h1 { margin: 4px 0 2px; font-size: 26px; font-weight: 800; letter-spacing: -0.025em; line-height: 1.15; }
.header-sub { color: var(--sk-label-2); font-size: 12px; }

.header-actions { display: flex; flex: 0 0 auto; align-items: center; gap: 8px; }

.agent-switcher {
  position: relative;
  display: flex;
  height: 44px;
  align-items: center;
  gap: 9px;
  border: 1px solid var(--sk-separator);
  border-radius: 12px;
  padding: 0 32px 0 9px;
  background: rgba(255, 255, 255, 0.62);
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.03);
  transition: background-color 0.18s ease, border-color 0.18s ease, box-shadow 0.18s ease;
}

.agent-switcher:hover { border-color: rgba(0, 122, 255, 0.28); background: rgba(255, 255, 255, 0.86); box-shadow: 0 6px 18px rgba(40, 80, 150, 0.08); }

.agent-switcher-icon {
  display: grid;
  width: 28px;
  height: 28px;
  flex: 0 0 28px;
  place-items: center;
  border-radius: 8px;
  background: var(--zwx-primary-soft);
  color: var(--zwx-primary);
  font-size: 16px;
  line-height: 1;
}

.agent-switcher-copy { display: grid; min-width: 0; gap: 1px; }
.agent-switcher-copy small { color: var(--sk-label-3); font-size: 9px; font-weight: 700; letter-spacing: 0.05em; }
.agent-switcher select {
  width: 118px;
  appearance: none;
  border: 0;
  padding: 0;
  background: transparent;
  color: var(--sk-label);
  font-size: 12px;
  font-weight: 650;
  outline: none;
  cursor: pointer;
}
.agent-switcher select:disabled { opacity: 0.6; cursor: default; }
.agent-chevron { position: absolute; right: 10px; color: var(--sk-label-3); pointer-events: none; }

.icon-button {
  display: grid;
  width: 36px;
  height: 36px;
  place-items: center;
  border: 1px solid var(--sk-separator);
  border-radius: 10px;
  background: var(--sk-surface);
  color: var(--sk-label-2);
}
.icon-button:hover:not(:disabled) { background: var(--sk-fill); color: var(--sk-label); }
.icon-button:disabled { opacity: 0.6; }
.spinning { animation: spin 0.9s linear infinite; }
@keyframes spin { to { transform: rotate(360deg); } }

.knowledge-file-input { display: none; }

.upload-button {
  display: flex;
  align-items: center;
  gap: 6px;
  height: 36px;
  border: 0;
  border-radius: 11px;
  padding: 0 15px;
  background: linear-gradient(180deg, #2590ff, var(--zwx-primary));
  color: #fff;
  font-size: 12px;
  font-weight: 650;
  box-shadow: 0 4px 10px var(--zwx-primary-ring);
}

.publish-button {
  height: 36px;
  border: 1px solid rgba(255, 149, 0, 0.32);
  border-radius: 10px;
  padding: 0 13px;
  background: #fff7e8;
  color: #a35a00;
  font-size: 12px;
  font-weight: 650;
  cursor: pointer;
}
.publish-button:hover:not(:disabled) { background: #ffefd1; }
.publish-button:disabled { opacity: 0.6; cursor: default; }

.upload-button:hover:not(:disabled) { filter: brightness(1.06); }
.upload-button:active:not(:disabled) { transform: scale(0.98); }
.upload-button:disabled { background: var(--sk-fill-strong); color: var(--sk-label-2); box-shadow: none; }

/* ── 概览指标 ───────────────────────────────────────── */
.knowledge-overview {
  display: grid;
  flex: 0 0 auto;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 12px;
  padding: 16px 22px 0;
}

.stat-card {
  display: flex;
  min-width: 0;
  align-items: center;
  gap: 12px;
  border: 1px solid var(--sk-separator);
  border-radius: 15px;
  padding: 12px 14px;
  background: var(--sk-surface);
  box-shadow: var(--sk-shadow-card);
}

.stat-icon { display: grid; width: 38px; height: 38px; flex: 0 0 38px; place-items: center; border-radius: 11px; }
.stat-copy { display: grid; min-width: 0; gap: 1px; }
.stat-copy small { color: var(--sk-label-3); font-size: 10px; font-weight: 600; letter-spacing: 0.02em; }
.stat-copy strong { font-size: 21px; font-weight: 800; letter-spacing: -0.02em; line-height: 1.15; }
.stat-foot { margin-left: auto; color: var(--sk-label-3); font-size: 10px; white-space: nowrap; }

.stat-total .stat-icon { background: rgba(0, 122, 255, 0.1); color: var(--zwx-primary); }
.stat-active .stat-icon { background: rgba(52, 199, 89, 0.12); color: #1f9d4d; }
.stat-pending .stat-icon { background: rgba(255, 149, 0, 0.14); color: #a35a00; }
.stat-chunks .stat-icon { background: rgba(99, 102, 241, 0.12); color: #4f46e5; }

/* ── 错误提示浮层 ───────────────────────────────────── */
.workspace-notice {
  position: absolute;
  z-index: 20;
  top: 84px;
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

/* ── 三栏工作区 ─────────────────────────────────────── */
.knowledge-workspace {
  display: grid;
  min-height: 0;
  flex: 1;
  gap: 14px;
  grid-template-columns: 310px minmax(250px, var(--chunk-panel-width)) 10px minmax(0, 1fr);
  padding: 14px 22px 20px;
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
.panel-count { flex: 0 0 auto; border-radius: 999px; background: var(--sk-fill); color: var(--sk-label-2); padding: 3px 9px; font-size: 10px; font-weight: 700; }

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
.panel-empty.compact { padding: 20px 14px; }
.panel-empty :deep(svg) { color: var(--sk-label-3); }
.panel-empty strong { color: var(--sk-label); font-size: 13px; }
.panel-empty .empty-upload-icon { display: grid; width: 46px; height: 46px; place-items: center; border-radius: 14px; background: var(--zwx-primary-soft); color: var(--zwx-primary); }
.empty-cta { margin-top: 6px; border: 0; border-radius: 9px; padding: 7px 13px; background: var(--zwx-primary); color: #fff; font-size: 11px; font-weight: 650; }
.text-action { margin-top: 4px; border: 0; background: transparent; color: var(--zwx-primary); font-size: 11px; font-weight: 650; }

/* ── 文档工具栏与筛选 ───────────────────────────────── */
.document-toolbar { display: grid; gap: 9px; flex: 0 0 auto; padding: 10px 12px 8px; }

.category-bar { display: flex; align-items: center; gap: 6px; }
.category-chips { display: flex; min-width: 0; flex: 1; gap: 4px; overflow-x: auto; padding-bottom: 2px; scrollbar-width: none; }
.category-chips::-webkit-scrollbar { display: none; }
.category-chips button { display: inline-flex; flex: 0 0 auto; align-items: center; gap: 5px; border: 1px solid transparent; border-radius: 8px; padding: 4px 8px; background: transparent; color: var(--sk-label-2); font-size: 11px; font-weight: 600; white-space: nowrap; }
.category-chips button:hover { background: var(--sk-fill); }
.category-chips button.active { border-color: var(--zwx-primary-ring); background: var(--zwx-primary-soft); color: var(--zwx-primary); }
.category-chips em { border-radius: 999px; padding: 0 5px; background: var(--sk-fill); color: var(--sk-label-3); font-size: 9px; font-style: normal; font-weight: 800; }
.category-chips button.active em { background: rgba(255, 255, 255, 0.85); color: var(--zwx-primary); }
.category-add { display: inline-flex; flex: 0 0 auto; align-items: center; gap: 3px; border: 1px dashed var(--sk-separator-strong); border-radius: 8px; padding: 4px 7px; background: transparent; color: var(--sk-label-2); font-size: 10px; font-weight: 650; }
.category-add:hover { border-color: var(--zwx-primary); color: var(--zwx-primary); background: var(--zwx-primary-soft); }
.category-create { display: flex; align-items: center; gap: 5px; }
.category-create input { min-width: 0; flex: 1; height: 32px; border: 1px solid var(--zwx-primary-ring); border-radius: 8px; outline: 0; padding: 0 9px; background: var(--sk-surface); color: var(--sk-label); font-size: 12px; }
.category-create input:focus { box-shadow: 0 0 0 3px var(--zwx-primary-ring); }
.category-confirm, .category-cancel { height: 30px; border: 0; border-radius: 8px; padding: 0 10px; font-size: 11px; font-weight: 650; }
.category-confirm { background: var(--zwx-primary); color: #fff; }
.category-confirm:disabled { opacity: 0.5; }
.category-cancel { background: var(--sk-fill); color: var(--sk-label-2); }

.document-search { display: flex; height: 36px; align-items: center; gap: 8px; border-radius: 10px; padding: 0 11px; background: var(--sk-fill); color: var(--sk-label-3); }
.document-search:focus-within { box-shadow: 0 0 0 3px var(--zwx-primary-ring); background: var(--sk-surface); }
.document-search input { width: 100%; border: 0; outline: 0; background: transparent; color: var(--sk-label); font-size: 12.5px; }
.document-search input::placeholder { color: var(--sk-label-3); }

.filter-chips { display: flex; flex-wrap: wrap; gap: 5px; }
.filter-chips button { display: inline-flex; align-items: center; gap: 5px; border: 0; border-radius: 999px; padding: 4px 9px; background: transparent; color: var(--sk-label-2); font-size: 11px; font-weight: 600; }
.filter-chips button:hover { background: var(--sk-fill); }
.filter-chips button.active { background: var(--zwx-primary-soft); color: var(--zwx-primary); }
.filter-chips em { border-radius: 999px; padding: 0 5px; background: rgba(255, 255, 255, 0.7); font-size: 9px; font-style: normal; font-weight: 800; }
.filter-chips button.active em { background: rgba(255, 255, 255, 0.9); }

/* ── 文档列表 ───────────────────────────────────────── */
.document-list { min-height: 0; flex: 1; overflow-y: auto; overscroll-behavior: contain; display: grid; gap: 3px; align-content: start; padding: 4px 10px 10px; }

.document-row {
  position: relative;
  display: grid;
  grid-template-columns: 38px minmax(0, 1fr) 8px;
  align-items: center;
  gap: 10px;
  border: 0;
  border-radius: 12px;
  padding: 9px 10px;
  background: transparent;
  color: inherit;
  text-align: left;
}

.doc-icon {
  display: grid;
  width: 38px;
  height: 40px;
  place-items: center;
  align-content: center;
  gap: 1px;
  border-radius: 9px;
  background: var(--sk-fill);
  color: var(--sk-label-2);
}
.doc-icon em { font-size: 8px; font-style: normal; font-weight: 800; letter-spacing: 0.02em; }
.doc-icon.tone-pdf { background: rgba(255, 59, 48, 0.1); color: #c9372c; }
.doc-icon.tone-doc { background: rgba(0, 122, 255, 0.1); color: var(--zwx-primary); }
.doc-icon.tone-sheet { background: rgba(52, 199, 89, 0.12); color: #1f9d4d; }
.doc-icon.tone-slide { background: rgba(255, 149, 0, 0.13); color: #a35a00; }
.doc-icon.tone-markdown { background: rgba(99, 102, 241, 0.11); color: #4f46e5; }

.doc-main { display: grid; min-width: 0; gap: 4px; }
.doc-main strong { overflow: hidden; font-size: 12.5px; font-weight: 650; text-overflow: ellipsis; white-space: nowrap; }

.document-meta { display: flex; flex-wrap: wrap; align-items: center; gap: 5px; }
.document-meta em { color: var(--sk-label-3); font-size: 10.5px; font-style: normal; }
.document-meta i { border-radius: 5px; padding: 1px 6px; font-size: 9px; font-style: normal; font-weight: 700; background: var(--sk-fill); color: var(--sk-label-2); }
.document-meta i.version-chip { background: rgba(99, 102, 241, 0.12); color: #4f46e5; }
.document-meta i.category-chip { background: rgba(0, 122, 255, 0.08); color: #3679ec; }
.document-meta i.lc-active { background: rgba(52, 199, 89, 0.14); color: #1f9d4d; }
.document-meta i.lc-ready { background: rgba(255, 149, 0, 0.14); color: #8a5a00; }
.document-meta i.lc-archived { background: rgba(142, 142, 147, 0.14); color: #6e6e73; }
.document-meta i.lc-failed, .document-meta i.lc-conflict, .document-meta i.lc-empty { background: rgba(255, 59, 48, 0.12); color: var(--sk-red); }
.document-meta i.lc-indexing, .document-meta i.lc-pending { background: rgba(255, 149, 0, 0.14); color: #8a5a00; }

.doc-status-dot { width: 7px; height: 7px; border-radius: 50%; background: var(--sk-separator-strong); }
.doc-status-dot.lc-active { background: var(--sk-green); box-shadow: 0 0 0 3px rgba(52, 199, 89, 0.12); }
.doc-status-dot.lc-ready, .doc-status-dot.lc-indexing, .doc-status-dot.lc-pending { background: #ff9500; box-shadow: 0 0 0 3px rgba(255, 149, 0, 0.12); }
.doc-status-dot.lc-failed, .doc-status-dot.lc-conflict, .doc-status-dot.lc-empty { background: var(--sk-red); }

.document-row:hover { background: var(--sk-fill); }
.document-row.active { background: var(--zwx-primary-soft); }
.document-row.active .doc-icon { background: var(--sk-surface); }
.document-row.active .doc-main strong { color: var(--zwx-primary); }

.list-foot { display: flex; flex: 0 0 auto; align-items: center; justify-content: space-between; gap: 8px; border-top: 1px solid var(--sk-separator); padding: 8px 14px; color: var(--sk-label-3); font-size: 10px; }

/* ── 切片列表 ───────────────────────────────────────── */
.chunk-list { min-height: 0; flex: 1; overflow-y: auto; overscroll-behavior: contain; display: grid; gap: 4px; align-content: start; padding: 8px 10px 10px; }

.chunk-row { display: grid; gap: 5px; border: 0; border-radius: 12px; padding: 9px 11px; background: transparent; color: inherit; text-align: left; }
.chunk-top { display: flex; flex-wrap: wrap; align-items: center; gap: 6px; }
.chunk-index { color: var(--sk-label); font-size: 11.5px; font-weight: 700; }
.section-chip { border-radius: 5px; padding: 1px 6px; background: var(--sk-fill); color: var(--sk-label-2); font-size: 9px; font-weight: 600; }
.chunk-row p { margin: 0; color: var(--sk-label-2); font-size: 11.5px; line-height: 1.55; }
.chunk-row:hover { background: var(--sk-fill); }
.chunk-row.active { background: var(--zwx-primary-soft); }
.chunk-row.active p { max-height: 260px; overflow: auto; color: var(--sk-label); white-space: pre-wrap; overflow-wrap: anywhere; }
.chunk-row.active .section-chip { background: var(--sk-surface); color: var(--zwx-primary); }

/* ── 拖拽分隔条 ─────────────────────────────────────── */
.chunk-resizer { position: relative; cursor: col-resize; }
.chunk-resizer i { position: absolute; top: 50%; left: 50%; width: 4px; height: 44px; border-radius: 999px; background: var(--sk-separator-strong); transform: translate(-50%, -50%); transition: background-color 0.15s ease, height 0.15s ease; }
.chunk-resizer:hover i, .chunk-resizer:active i { height: 64px; background: var(--zwx-primary); }

/* ── 原文预览 ───────────────────────────────────────── */
.preview-heading { display: flex; flex: 0 0 auto; align-items: center; justify-content: space-between; gap: 14px; border-bottom: 1px solid var(--sk-separator); padding: 14px 18px 12px; }
.preview-badges { display: flex; flex-wrap: wrap; justify-content: flex-end; gap: 5px; }
.preview-badge { border-radius: 6px; padding: 2px 7px; background: var(--sk-fill); color: var(--sk-label-2); font-size: 9.5px; font-weight: 700; }
.preview-badge.lc-active { background: rgba(52, 199, 89, 0.14); color: #1f9d4d; }
.preview-badge.lc-ready { background: rgba(255, 149, 0, 0.14); color: #8a5a00; }
.preview-badge.lc-archived { background: rgba(142, 142, 147, 0.14); color: #6e6e73; }
.preview-badge.lc-empty { background: rgba(255, 59, 48, 0.12); color: var(--sk-red); }

.category-move { display: inline-flex; align-items: center; gap: 4px; border-radius: 6px; padding: 1px 5px; background: var(--zwx-primary-soft); color: var(--zwx-primary); }
.category-move select { max-width: 110px; border: 0; outline: 0; background: transparent; color: inherit; font-size: 9.5px; font-weight: 700; }
.category-move select:focus { box-shadow: 0 0 0 2px var(--zwx-primary-ring); }

.reindex-button, .publish-inline-button { display: inline-flex; align-items: center; gap: 4px; border: 1px solid var(--zwx-primary-ring); border-radius: 6px; padding: 2px 8px; background: var(--sk-surface); color: var(--zwx-primary); font-size: 9.5px; font-weight: 700; }
.reindex-button:hover:not(:disabled) { background: var(--zwx-primary-soft); }
.reindex-button:disabled { opacity: 0.6; }

.preview-path { display: flex; flex: 0 0 auto; align-items: center; gap: 8px; border-bottom: 1px solid var(--sk-separator); padding: 8px 18px; color: var(--sk-label-3); font-size: 10px; }
.preview-path code { overflow: hidden; border-radius: 6px; padding: 2px 7px; background: var(--sk-fill); color: var(--sk-label-2); font-size: 10px; text-overflow: ellipsis; white-space: nowrap; }

.preview-body { min-height: 0; flex: 1; display: flex; flex-direction: column; }
.source-preview { flex: 1; overflow: auto; margin: 0; padding: 18px 22px; color: var(--sk-label); font: 12.5px/1.75 ui-monospace, "SF Mono", SFMono-Regular, Menlo, monospace; white-space: pre-wrap; word-break: break-word; }

.preview-placeholder { display: grid; flex: 1; place-content: center; justify-items: center; gap: 7px; padding: 24px; color: var(--sk-label-2); font-size: 13px; text-align: center; }
.preview-placeholder :deep(svg) { color: var(--sk-label-3); }
.preview-placeholder strong { color: var(--sk-label); font-size: 15px; }

/* ── 响应式 ─────────────────────────────────────────── */
@media (max-width: 1100px) {
  .stat-foot { display: none; }
}

@media (max-width: 960px) {
  .knowledge-overview { grid-template-columns: repeat(2, minmax(0, 1fr)); padding: 12px 16px 0; }
  .knowledge-workspace { grid-template-columns: 260px minmax(230px, var(--chunk-panel-width)) 10px minmax(0, 1fr); gap: 12px; padding: 12px 16px 16px; }
  .header-actions { gap: 6px; }
  .header-actions .icon-button { display: none; }
}

@media (max-width: 720px) {
  .knowledge-admin { height: auto; min-height: 720px; overflow: visible; }
  .header-inner { flex-wrap: wrap; align-items: flex-start; padding: 14px; }
  .header-actions { width: 100%; flex-wrap: wrap; }
  .agent-switcher { flex: 1 1 100%; }
  .header-actions .icon-button { display: flex; }
  .knowledge-overview { grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 8px; padding: 12px 14px 0; }
  .stat-card { padding: 10px; }
  .stat-copy strong { font-size: 18px; }
  .knowledge-workspace { display: flex; flex-direction: column; gap: 12px; overflow: visible; padding: 12px 14px 16px; }
  .panel { flex: 0 0 auto; max-height: none; }
  .document-list, .chunk-list { max-height: 320px; }
  .chunk-resizer { display: none; }
  .source-preview, .preview-placeholder { min-height: 260px; }
  .workspace-notice { top: 96px; right: 14px; left: 14px; }
}
</style>
