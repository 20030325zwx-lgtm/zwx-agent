<template>
  <main class="knowledge-admin">
    <header class="page-header">
      <div>
        <span class="eyebrow"><Database :size="13" /> KNOWLEDGE BASE</span>
        <h1>知识库</h1>
        <p>管理资料、版本与检索切片</p>
      </div>
      <div class="header-actions">
        <label class="agent-select">
          <span>{{ currentAgent.icon }}</span>
          <span class="agent-copy"><small>资料归属</small><select v-model="agentKey" :disabled="uploading" @change="switchAgent">
            <option v-for="agent in KNOWLEDGE_AGENTS" :key="agent.key" :value="agent.key">{{ agent.name }}</option>
          </select></span>
          <ChevronDown :size="14" />
        </label>
        <button class="icon-button" type="button" title="刷新" aria-label="刷新" :disabled="loading" @click="loadDocuments(true)"><RefreshCw :size="16" :class="{ spinning: loading }" /></button>
        <label class="manual-review-toggle" :class="{ checked: manualReview }" title="勾选后文档索引完成不自动发布，需审批通过才生效，并会推送 n8n 待审案件">
          <input v-model="manualReview" type="checkbox" :disabled="uploading" />需人工审核
        </label>
        <button class="primary-button" type="button" :disabled="uploading" @click="startUpload"><Upload :size="16" />{{ uploading ? '上传中' : '上传资料' }}</button>
        <p v-if="flashMessage" class="upload-flash" role="status">{{ flashMessage }}</p>
        <input ref="fileInput" class="visually-hidden" type="file" accept=".md,.txt,.pdf,.doc,.docx,.xls,.xlsx,.ppt,.pptx" @change="onFileSelected" />
      </div>
    </header>

    <section class="overview" aria-label="知识库统计">
      <div class="stat"><span class="stat-icon blue"><Database :size="17" /></span><span><small>文档</small><strong>{{ documents.length }}</strong></span></div>
      <div class="stat"><span class="stat-icon green"><CircleCheck :size="17" /></span><span><small>切片完成</small><strong>{{ completedDocuments }}</strong></span></div>
      <div class="stat"><span class="stat-icon amber"><Clock3 :size="17" /></span><span><small>处理中</small><strong>{{ processingDocuments }}</strong></span></div>
      <div class="stat"><span class="stat-icon amber"><Undo2 :size="17" /></span><span><small>待审批</small><strong>{{ pendingReviewDocuments }}</strong></span></div>
      <div class="stat"><span class="stat-icon violet"><Layers :size="17" /></span><span><small>切片总数</small><strong>{{ totalChunks }}</strong></span></div>
    </section>

    <p v-if="error" class="error-banner" role="alert"><CircleAlert :size="15" />{{ error }}<button type="button" @click="loadDocuments(true)">重试</button></p>

    <section class="browser-layout">
      <aside class="folder-panel" aria-label="文件夹分类">
        <div class="folder-heading"><div><strong>文件夹</strong><span>{{ documents.length }} 份资料</span></div>
          <button class="subtle-icon" type="button" title="新建分类" aria-label="新建分类" @click="beginCategory"><FolderPlus :size="16" /></button>
        </div>
        <form v-if="creatingCategory" class="category-form" @submit.prevent="createCategory">
          <input ref="categoryInput" v-model.trim="categoryNameDraft" maxlength="40" placeholder="分类名称" @keydown.esc.prevent="creatingCategory = false" />
          <button type="submit" :disabled="!categoryNameDraft || savingCategory" title="创建分类" aria-label="创建分类"><Check :size="15" /></button>
          <button type="button" title="取消" aria-label="取消" @click="creatingCategory = false"><X :size="15" /></button>
        </form>
        <nav class="folder-tree">
          <button class="folder-node root-node" :class="{ selected: selectedCategoryId === 'ALL' }" type="button" @click="selectedCategoryId = 'ALL'">
            <span class="folder-label"><Database :size="16" />全部资料</span><span class="folder-count">{{ documents.length }}</span>
          </button>
          <button v-for="category in categories" :key="category.id" class="folder-node" :class="{ selected: selectedCategoryId === category.id }" type="button" @click="selectedCategoryId = category.id">
            <span class="folder-label"><Folder :size="16" />{{ category.name }}</span><span class="folder-count">{{ categoryCount(category.id) }}</span>
          </button>
        </nav>
        <div class="folder-foot"><span class="legend-dot ready"></span>切片完成<span class="legend-dot busy"></span>处理中<span class="legend-dot failed"></span>失败</div>
      </aside>

      <section class="files-panel" aria-label="文件列表">
        <div class="files-toolbar">
          <div class="files-title"><h2>{{ selectedCategoryName }}</h2><span>{{ visibleGroups.length }} 个文件</span></div>
          <div class="file-controls">
            <label class="search-field"><Search :size="15" /><input v-model.trim="query" type="search" placeholder="搜索文件名" /></label>
            <select v-model="statusFilter" aria-label="按状态筛选"><option value="ALL">全部状态</option><option value="DONE">切片完成</option><option value="PROCESSING">处理中</option><option value="FAILED">失败或待处理</option><option value="PENDING_REVIEW">待审批</option></select>
          </div>
        </div>
        <div class="table-scroll">
          <table class="file-table">
            <thead><tr><th>文件</th><th>切片状态</th><th>版本</th><th>更新时间</th><th class="actions-heading">操作</th></tr></thead>
            <tbody>
              <template v-for="group in visibleGroups" :key="group.key">
                <tr class="file-row" :class="{ selected: activeDocumentId === group.latest.id }">
                  <td><div class="file-cell">
                    <span class="file-icon" :class="fileTone(group.latest.filename)"><FileText :size="17" /></span>
                    <span class="file-copy"><strong :title="group.latest.filename">{{ group.latest.filename }}</strong><span class="category-field"><Folder :size="11" /><select :value="group.latest.categoryId" :aria-label="`移动 ${group.latest.filename} 到分类`" @click.stop @change.stop="moveDocument(group.latest, $event.target.value)"><option v-for="category in categories" :key="category.id" :value="category.id">{{ category.name }}</option></select></span></span>
                    <button v-if="group.versions.length > 1" class="history-button" type="button" :aria-expanded="expandedGroups.has(group.key)" :title="expandedGroups.has(group.key) ? '收起历史版本' : '查看历史版本'" @click="toggleHistory(group.key)"><History :size="14" /><span>{{ group.versions.length }}</span><ChevronDown :size="13" /></button>
                  </div></td>
                  <td><button class="chunk-status" :class="statusClass(group.latest)" type="button" :disabled="!group.latest.chunkCount" :title="group.latest.chunkCount ? '检查切片' : '暂无可检查切片'" @click="openDrawer(group.latest, 'chunks')">
                    <CircleCheck v-if="isComplete(group.latest)" :size="14" /><LoaderCircle v-else-if="isProcessing(group.latest)" :size="14" class="spinning" /><CircleAlert v-else :size="14" />
                    <span>{{ group.latest.chunkCount || 0 }} 切片 · {{ statusLabel(group.latest) }}</span>
                  </button></td>
                  <td><span class="version-pill">v{{ group.latest.versionNo || 1 }}</span><span v-if="group.latest.lifecycleStatus === 'ACTIVE'" class="active-pill">生效中</span><span v-else-if="reviewPill(group.latest)" :class="reviewPill(group.latest).cls">{{ reviewPill(group.latest).text }}</span></td>
                  <td class="date-cell">{{ formatDate(group.latest.completedAt || group.latest.createdAt) }}</td>
                  <td><div class="row-actions">
                    <button type="button" title="预览文件" aria-label="预览文件" @click="openDrawer(group.latest, 'preview')"><Eye :size="15" /></button>
                    <button type="button" title="下载文件" aria-label="下载文件" @click="downloadDocument(group.latest)"><Download :size="15" /></button>
                    <button type="button" title="替换并创建新版本" aria-label="替换并创建新版本" :disabled="uploading" @click="replaceDocument(group.latest)"><Replace :size="15" /></button>
                    <button type="button" title="检查切片" aria-label="检查切片" @click="openDrawer(group.latest, 'chunks')"><ScanSearch :size="15" /></button>
                    <button type="button" title="重新解析" aria-label="重新解析" :disabled="busyDocumentId === group.latest.id" @click="reindex(group.latest)"><RefreshCw :size="15" :class="{ spinning: busyDocumentId === group.latest.id }" /></button>
                    <template v-if="canReview && group.latest.lifecycleStatus === 'READY'">
                      <button type="button" class="publish-action" title="发布此版本" aria-label="发布此版本" :disabled="busyDocumentId === group.latest.id" @click="publish(group.latest)"><Send :size="14" /></button>
                      <button type="button" class="reject-action" title="退回并填写审核意见" aria-label="退回此版本" :disabled="busyDocumentId === group.latest.id" @click="requestReject(group.latest)"><Undo2 :size="14" /></button>
                    </template>
                    <button v-if="canReview" type="button" title="审批记录" aria-label="查看审批记录" @click="openDrawer(group.latest, 'review')"><ScrollText :size="15" /></button>
                    <button type="button" class="danger-action" title="删除文件（含全部版本）" aria-label="删除文件（含全部版本）" :disabled="deleting" @click="requestDeleteGroup(group)"><Trash2 :size="15" /></button>
                  </div></td>
                </tr>
                <tr v-for="version in expandedGroups.has(group.key) ? group.visibleVersions.slice(1) : []" :key="version.id" class="version-row" :class="{ selected: activeDocumentId === version.id }">
                  <td><div class="file-cell version-file-cell"><span class="version-branch"></span><span class="file-icon small-icon" :class="fileTone(version.filename)"><FileText :size="15" /></span><span class="file-copy"><strong>{{ version.filename }}</strong><span class="category-field"><Folder :size="11" /><select :value="version.categoryId" :aria-label="`移动历史版本到分类`" @click.stop @change.stop="moveDocument(version, $event.target.value)"><option v-for="category in categories" :key="category.id" :value="category.id">{{ category.name }}</option></select></span></span></div></td>
                  <td><button class="chunk-status" :class="statusClass(version)" type="button" :disabled="!version.chunkCount" title="检查该版本切片" @click="openDrawer(version, 'chunks')"><CircleCheck v-if="isComplete(version)" :size="14" /><LoaderCircle v-else-if="isProcessing(version)" :size="14" class="spinning" /><CircleAlert v-else :size="14" /><span>{{ version.chunkCount || 0 }} 切片 · {{ statusLabel(version) }}</span></button></td>
                  <td><span class="version-pill">v{{ version.versionNo || 1 }}</span><span v-if="version.lifecycleStatus === 'ACTIVE'" class="active-pill">生效中</span><span v-else-if="reviewPill(version)" :class="reviewPill(version).cls">{{ reviewPill(version).text }}</span><span v-else class="archived-pill">历史</span></td>
                  <td class="date-cell">{{ formatDate(version.completedAt || version.createdAt) }}</td>
                  <td><div class="row-actions">
                    <button type="button" title="预览历史版本" aria-label="预览历史版本" @click="openDrawer(version, 'preview')"><Eye :size="15" /></button>
                    <button type="button" title="下载历史版本" aria-label="下载历史版本" @click="downloadDocument(version)"><Download :size="15" /></button>
                    <button type="button" title="基于此版本上传新版本" aria-label="基于此版本上传新版本" :disabled="uploading" @click="replaceDocument(version)"><Replace :size="15" /></button>
                    <button type="button" title="检查历史版本切片" aria-label="检查历史版本切片" @click="openDrawer(version, 'chunks')"><ScanSearch :size="15" /></button>
                    <button type="button" title="重新解析历史版本" aria-label="重新解析历史版本" :disabled="busyDocumentId === version.id" @click="reindex(version)"><RefreshCw :size="15" :class="{ spinning: busyDocumentId === version.id }" /></button>
                    <template v-if="canReview && version.lifecycleStatus === 'READY'">
                      <button type="button" class="publish-action" title="发布此历史版本" aria-label="发布此历史版本" :disabled="busyDocumentId === version.id" @click="publish(version)"><Send :size="14" /></button>
                      <button type="button" class="reject-action" title="退回并填写审核意见" aria-label="退回此历史版本" :disabled="busyDocumentId === version.id" @click="requestReject(version)"><Undo2 :size="14" /></button>
                    </template>
                    <button v-if="canReview" type="button" title="审批记录" aria-label="查看审批记录" @click="openDrawer(version, 'review')"><ScrollText :size="15" /></button>
                    <button type="button" class="danger-action" title="删除此版本" aria-label="删除此版本" :disabled="deleting" @click="requestDeleteVersion(version)"><Trash2 :size="15" /></button>
                  </div></td>
                </tr>
              </template>
              <tr v-if="loading && !documents.length"><td colspan="5" class="table-state">正在读取资料…</td></tr>
              <tr v-else-if="!visibleGroups.length"><td colspan="5" class="table-state empty-state"><FolderOpen :size="25" /><strong>{{ documents.length ? '没有匹配的文件' : '此分类还没有文件' }}</strong><span>{{ documents.length ? '调整搜索词或状态筛选。' : '上传一份资料，系统会自动解析并生成检索切片。' }}</span><button v-if="!documents.length" class="primary-button" type="button" @click="startUpload"><Upload :size="15" />上传资料</button></td></tr>
            </tbody>
          </table>
        </div>
        <footer class="files-foot"><span>显示 {{ visibleGroups.length }} / {{ selectedCategoryId === 'ALL' ? logicalFileCount : categoryLogicalCount }} 个文件</span><span v-if="uploading">资料正在入库并生成切片…</span></footer>
      </section>
    </section>

    <Transition name="drawer">
      <section v-if="drawerOpen" class="inspect-drawer" role="dialog" aria-modal="true" :aria-label="drawerMode === 'chunks' ? '切片检查' : drawerMode === 'review' ? '审批记录' : '文件预览'">
        <header class="drawer-header">
          <div class="drawer-title"><button type="button" class="close-drawer" title="关闭" aria-label="关闭" @click="closeDrawer"><X :size="19" /></button>
            <div><span class="eyebrow">{{ drawerMode === 'chunks' ? 'CHUNK INSPECTOR' : drawerMode === 'review' ? 'AUDIT TRAIL' : 'DOCUMENT PREVIEW' }}</span><h2>{{ drawerDocument?.filename || '文档' }}</h2><p>v{{ drawerDocument?.versionNo || 1 }} · {{ categoryName(drawerDocument?.categoryId) }}<span v-if="drawerMode === 'chunks'"> · {{ drawerDetail?.chunks?.length || 0 }} 个切片</span><span v-else-if="drawerMode === 'review'"> · {{ reviewLog.length }} 条记录</span></p></div>
          </div>
          <div class="drawer-actions"><button type="button" class="secondary-button" @click="downloadDocument(drawerDocument)"><Download :size="15" />下载</button><button v-if="drawerMode !== 'review'" type="button" class="icon-button" title="重新加载文档" aria-label="重新加载文档" @click="loadDrawerDetail"><RefreshCw :size="15" /></button></div>
        </header>
        <div v-if="drawerError" class="drawer-error"><CircleAlert :size="15" />{{ drawerError }}</div>
        <div class="drawer-body" :class="{ 'chunks-mode': drawerMode !== 'preview' }">
          <aside v-if="drawerMode !== 'preview'" class="chunks-pane">
            <template v-if="drawerMode === 'chunks'">
              <div class="pane-heading"><div><strong>实际切片</strong><small>完整内容 · {{ drawerDetail?.chunks?.length || 0 }} 段</small></div><span class="pane-total">{{ drawerDetail?.chunks?.length || 0 }}</span></div>
              <div v-if="drawerLoading" class="pane-state">正在读取切片和原文…</div>
              <div v-else-if="!drawerDetail?.chunks?.length" class="pane-state"><Layers :size="25" /><strong>当前版本没有可检查的切片</strong><span>切片完成后可在这里逐段与原文比对。</span></div>
              <div v-else class="chunk-stack">
                <article v-for="chunk in drawerDetail.chunks" :key="chunk.id" class="chunk-card" :class="{ focused: focusedChunkId === chunk.id }" @click="focusedChunkId = chunk.id">
                  <header><strong>切片 {{ chunk.chunkIndex }}</strong><span>{{ chunk.content.length.toLocaleString() }} 字符</span></header>
                  <pre>{{ chunk.content }}</pre>
                  <footer><span :title="chunk.id">{{ chunk.id }}</span><button type="button" title="复制切片内容" aria-label="复制切片内容" @click.stop="copyChunk(chunk)"><Copy :size="14" /></button></footer>
                </article>
              </div>
            </template>
            <template v-else>
              <div class="pane-heading"><div><strong>审批时间线</strong><small>追加式审计 · 提交/发布/退回全记录</small></div><span class="pane-total">{{ reviewLog.length }}</span></div>
              <div v-if="reviewLoading" class="pane-state">正在读取审批记录…</div>
              <div v-else-if="!reviewLog.length" class="pane-state"><ScrollText :size="25" /><strong>没有审批记录</strong><span>该版本未经人工审批流程（例如自动发布），或记录已被清理。</span></div>
              <div v-else class="review-timeline">
                <article v-for="entry in reviewLog" :key="entry.id" class="review-entry" :class="reviewEntryTone(entry.action)">
                  <header><span class="review-action">{{ reviewActionLabel(entry.action) }}</span><span class="review-meta">{{ reviewActorLabel(entry) }} · {{ reviewChannelLabel(entry.channel) }}</span></header>
                  <p v-if="entry.note">{{ entry.note }}</p>
                  <footer><span>{{ formatDateTime(entry.createdAt) }}</span><span v-if="entry.n8nExecutionId" title="关联 n8n 执行">n8n #{{ entry.n8nExecutionId }}</span></footer>
                </article>
              </div>
            </template>
          </aside>
          <section class="source-pane">
            <div class="pane-heading"><div><strong>原文件预览</strong><small>{{ drawerDocument?.filename }}</small></div><span v-if="drawerMode === 'chunks'" class="source-hint">与左侧切片并排比对</span></div>
            <div class="source-preview"><KnowledgeDocumentViewer v-if="drawerDocument" :key="drawerDocument.id" :agent-key="agentKey" :document-id="drawerDocument.id" :filename="drawerDocument.filename" /></div>
          </section>
        </div>
      </section>
    </Transition>

    <Transition name="drawer">
      <div v-if="pendingReject" class="reject-mask" @click.self="pendingReject = null">
        <div class="reject-dialog" role="dialog" aria-modal="true" aria-label="退回文档">
          <header><strong>退回“{{ pendingReject.filename }}”v{{ pendingReject.versionNo || 1 }}</strong><button type="button" class="icon-button" title="关闭" aria-label="关闭" @click="pendingReject = null"><X :size="17" /></button></header>
          <p>退回后该版本标记为 REJECTED，不再参与检索。请填写审核意见（必填，会进入审批时间线）：</p>
          <textarea v-model.trim="rejectNote" maxlength="500" rows="3" placeholder="例如：切片内容不完整，请补充相关章节后重新上传"></textarea>
          <footer>
            <button type="button" class="secondary-button" @click="pendingReject = null">取消</button>
            <button type="button" class="danger-action" :disabled="!rejectNote || rejecting" @click="confirmReject"><Undo2 :size="14" />{{ rejecting ? '退回中' : '确认退回' }}</button>
          </footer>
        </div>
      </div>
    </Transition>

    <ConfirmDialog
      :open="!!pendingDelete"
      title="删除知识文件"
      :message="deleteMessage"
      confirm-text="删除"
      @confirm="confirmDelete"
      @cancel="pendingDelete = null"
    />
  </main>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { useHead } from '@vueuse/head'
import { Check, ChevronDown, CircleAlert, CircleCheck, Clock3, Copy, Database, Download, Eye, FileText, Folder, FolderOpen, FolderPlus, History, Layers, LoaderCircle, RefreshCw, Replace, ScanSearch, ScrollText, Search, Send, Trash2, Undo2, Upload, X } from 'lucide-vue-next'
import { createAgentKnowledgeCategory, deleteAgentKnowledgeDocument, getAgentKnowledgeDocument, getAgentKnowledgeDocumentFile, getAgentKnowledgeReviewLog, listAgentKnowledgeCategories, listAgentKnowledgeDocuments, moveAgentKnowledgeDocument, publishAgentKnowledgeDocument, reindexAgentKnowledgeDocument, rejectAgentKnowledgeDocument, uploadAgentKnowledgeDocument } from '../api'
import { getCurrentUser } from '../api/auth'
import { KNOWLEDGE_AGENTS, getAgent } from '../config/agents'
import KnowledgeDocumentViewer from '../components/KnowledgeDocumentViewer.vue'
import ConfirmDialog from '../components/ConfirmDialog.vue'

useHead({ title: '知识库管理 - Aster' })

const route = useRoute()
const requestedAgentKey = typeof route.query.agentKey === 'string' && KNOWLEDGE_AGENTS.some(agent => agent.key === route.query.agentKey) ? route.query.agentKey : 'super'
const agentKey = ref(requestedAgentKey)
const currentAgent = computed(() => getAgent(agentKey.value))
const categories = ref([])
const documents = ref([])
const selectedCategoryId = ref('ALL')
const query = ref('')
const statusFilter = ref('ALL')
const expandedGroups = ref(new Set())
const loading = ref(false)
const uploading = ref(false)
const manualReview = ref(false)
const flashMessage = ref('')
let flashTimer = null
const showFlash = message => {
  flashMessage.value = message
  if (flashTimer) window.clearTimeout(flashTimer)
  flashTimer = window.setTimeout(() => { flashMessage.value = '' }, 6000)
}
const savingCategory = ref(false)
const creatingCategory = ref(false)
const categoryNameDraft = ref('')
const categoryInput = ref(null)
const fileInput = ref(null)
const replacementTarget = ref(null)
const busyDocumentId = ref('')
const deleting = ref(false)
const pendingDelete = ref(null)
const activeDocumentId = ref('')
const error = ref('')
const drawerOpen = ref(false)
const drawerMode = ref('chunks')
const drawerDocument = ref(null)
const drawerDetail = ref(null)
const drawerLoading = ref(false)
const drawerError = ref('')
const focusedChunkId = ref('')
const canReview = computed(() => ['REVIEWER', 'ADMIN'].includes(getCurrentUser()?.role))
const pendingReject = ref(null)
const rejectNote = ref('')
const rejecting = ref(false)
const reviewLog = ref([])
const reviewLoading = ref(false)
const REVIEW_ACTION_LABELS = { SUBMIT: '提交审核', PUBLISH: '发布', REJECT: '退回', TIMEOUT: '审批超时', RESUME_FAIL: '流程收尾失败' }
const REVIEW_CHANNEL_LABELS = { API: '界面', N8N: '工作流', SYSTEM: '系统' }
let refreshTimer
let drawerRequestId = 0

const categoryCount = categoryId => documents.value.filter(document => document.categoryId === categoryId).length
const categoryName = categoryId => categories.value.find(category => category.id === categoryId)?.name || '默认分类'
const selectedCategoryName = computed(() => selectedCategoryId.value === 'ALL' ? '全部资料' : categoryName(selectedCategoryId.value))
const isProcessing = document => ['PENDING', 'INDEXING'].includes(document?.status)
const isComplete = document => Number(document?.chunkCount) > 0 && !isProcessing(document) && document?.status !== 'FAILED' && !['FAILED', 'EMPTY'].includes(document?.lifecycleStatus)
const isFailed = document => document?.status === 'FAILED' || document?.lifecycleStatus === 'FAILED' || (!isProcessing(document) && !Number(document?.chunkCount))
const statusClass = document => isComplete(document) ? 'complete' : isProcessing(document) ? 'processing' : 'failed'
const statusLabel = document => isComplete(document) ? '已完成' : isProcessing(document) ? '处理中' : document?.status === 'FAILED' ? '失败' : '待切片'
const logicalKeyOf = document => document.logicalKey || document.filename?.toLocaleLowerCase('zh-CN') || document.id
const fileGroups = computed(() => {
  const groups = new Map()
  for (const document of documents.value) {
    const key = logicalKeyOf(document)
    if (!groups.has(key)) groups.set(key, { key, versions: [] })
    groups.get(key).versions.push(document)
  }
  return [...groups.values()].map(group => {
    group.versions.sort((a, b) => (b.versionNo || 1) - (a.versionNo || 1) || String(b.createdAt).localeCompare(String(a.createdAt)))
    return { ...group, latest: group.versions[0] }
  })
})
const logicalFileCount = computed(() => fileGroups.value.length)
const categoryLogicalCount = computed(() => fileGroups.value.filter(group => group.versions.some(version => version.categoryId === selectedCategoryId.value)).length)
const visibleGroups = computed(() => fileGroups.value.flatMap(group => {
  const matchingVersions = group.versions.filter(version => {
    const matchesQuery = !query.value || version.filename.toLocaleLowerCase('zh-CN').includes(query.value.toLocaleLowerCase('zh-CN'))
    const matchesStatus = statusFilter.value === 'ALL' || (statusFilter.value === 'DONE' && isComplete(version)) || (statusFilter.value === 'PROCESSING' && isProcessing(version)) || (statusFilter.value === 'FAILED' && isFailed(version)) || (statusFilter.value === 'PENDING_REVIEW' && version.lifecycleStatus === 'READY')
    return matchesQuery && matchesStatus && (selectedCategoryId.value === 'ALL' || version.categoryId === selectedCategoryId.value)
  })
  return matchingVersions.length ? [{ ...group, latest: matchingVersions[0], visibleVersions: matchingVersions }] : []
}))
const deleteMessage = computed(() => {
  const target = pendingDelete.value
  if (!target) return ''
  const count = target.documents.length
  if (count > 1) return `删除“${target.label}”及其 ${count} 个版本、全部切片和来源块？删除后无法恢复。`
  const versionNo = target.documents[0]?.versionNo || 1
  return `删除“${target.label}”v${versionNo} 及其切片、来源块？删除后无法恢复。`
})
const completedDocuments = computed(() => documents.value.filter(isComplete).length)
const processingDocuments = computed(() => documents.value.filter(isProcessing).length)
const pendingReviewDocuments = computed(() => documents.value.filter(document => document.lifecycleStatus === 'READY').length)
// 待审超过 3 天视为超期，提示审批人优先处理
const REVIEW_OVERDUE_MS = 3 * 24 * 60 * 60 * 1000
const isReviewOverdue = document => document?.lifecycleStatus === 'READY' && !!document?.createdAt
        && (Date.now() - new Date(document.createdAt).getTime()) > REVIEW_OVERDUE_MS
const reviewPill = document => {
  if (document?.lifecycleStatus === 'READY') return { text: isReviewOverdue(document) ? '待审批·超期' : '待审批', cls: 'pending-pill' }
  if (document?.lifecycleStatus === 'REJECTED') return { text: '已退回', cls: 'rejected-pill' }
  return null
}
const totalChunks = computed(() => documents.value.reduce((sum, document) => sum + (Number(document.chunkCount) || 0), 0))

const fileTone = filename => {
  const extension = filename?.split('.').pop()?.toLowerCase()
  if (extension === 'pdf') return 'tone-pdf'
  if (['doc', 'docx'].includes(extension)) return 'tone-doc'
  if (['xls', 'xlsx'].includes(extension)) return 'tone-sheet'
  if (['ppt', 'pptx'].includes(extension)) return 'tone-slide'
  return 'tone-text'
}
const formatDate = value => value ? new Intl.DateTimeFormat('zh-CN', { year: 'numeric', month: '2-digit', day: '2-digit' }).format(new Date(value)) : '—'

const loadCategories = async () => { categories.value = await listAgentKnowledgeCategories(agentKey.value) }
const loadDocuments = async (preserveDrawer = false) => {
  loading.value = true
  try {
    const [nextCategories, nextDocuments] = await Promise.all([
      listAgentKnowledgeCategories(agentKey.value),
      listAgentKnowledgeDocuments(agentKey.value)
    ])
    categories.value = nextCategories
    documents.value = nextDocuments
    if (preserveDrawer && drawerOpen.value && drawerDocument.value) {
      drawerDocument.value = documents.value.find(document => document.id === drawerDocument.value.id) || drawerDocument.value
    }
    error.value = ''
  } catch (requestError) {
    error.value = '无法读取知识库资料，请检查后端连接后重试。'
    console.error(requestError)
  } finally {
    loading.value = false
  }
}
const switchAgent = async () => {
  selectedCategoryId.value = 'ALL'
  expandedGroups.value = new Set()
  activeDocumentId.value = ''
  closeDrawer()
  await loadDocuments()
}
const beginCategory = async () => {
  creatingCategory.value = true
  categoryNameDraft.value = ''
  await nextTick()
  categoryInput.value?.focus()
}
const createCategory = async () => {
  if (!categoryNameDraft.value || savingCategory.value) return
  savingCategory.value = true
  try {
    const category = await createAgentKnowledgeCategory(agentKey.value, categoryNameDraft.value)
    await loadCategories()
    selectedCategoryId.value = category.id
    creatingCategory.value = false
  } catch (requestError) {
    error.value = requestError?.response?.status === 409 ? '该分类已存在。' : '创建分类失败。'
  } finally {
    savingCategory.value = false
  }
}
const toggleHistory = key => {
  const next = new Set(expandedGroups.value)
  if (next.has(key)) next.delete(key)
  else next.add(key)
  expandedGroups.value = next
}
const moveDocument = async (document, categoryId) => {
  if (!document || !categoryId || document.categoryId === categoryId) return
  try {
    await moveAgentKnowledgeDocument(document.id, agentKey.value, categoryId)
    await loadDocuments(true)
  } catch (requestError) {
    error.value = '移动文件到分类失败。'
    console.error(requestError)
  }
}
const startUpload = () => { replacementTarget.value = null; fileInput.value?.click() }
const replaceDocument = document => { replacementTarget.value = document; fileInput.value?.click() }
const onFileSelected = async event => {
  const file = event.target.files?.[0]
  event.target.value = ''
  if (!file) return
  const target = replacementTarget.value
  replacementTarget.value = null
  if (target && !/\.(md|txt|pdf|doc|docx|xls|xlsx|ppt|pptx)$/i.test(file.name)) {
    error.value = '文件格式不受支持。'
    return
  }
  const selectedFile = target ? new File([file], target.filename, { type: file.type, lastModified: file.lastModified }) : file
  const categoryId = target?.categoryId || (selectedCategoryId.value === 'ALL' ? null : selectedCategoryId.value)
  uploading.value = true
  error.value = ''
  try {
    const created = await uploadAgentKnowledgeDocument(agentKey.value, selectedFile, categoryId, manualReview.value)
    showFlash(manualReview.value ? '已提交人工审核：索引完成后进入待审，审批通过才对检索生效' : '上传成功，索引完成后自动发布')
    await loadDocuments()
    const createdGroup = fileGroups.value.find(group => group.versions.some(version => version.id === created.id))
    if (createdGroup?.versions.length > 1) expandedGroups.value = new Set([...expandedGroups.value, createdGroup.key])
    activeDocumentId.value = created.id
  } catch (requestError) {
    error.value = '上传失败，请检查文件格式、存储配置和服务状态。'
    console.error(requestError)
  } finally {
    uploading.value = false
  }
}
const downloadDocument = async document => {
  if (!document) return
  try {
    const blob = await getAgentKnowledgeDocumentFile(agentKey.value, document.id)
    const url = URL.createObjectURL(blob)
    const anchor = window.document.createElement('a')
    anchor.href = url
    anchor.download = document.filename
    anchor.click()
    window.setTimeout(() => URL.revokeObjectURL(url), 1000)
  } catch (requestError) {
    error.value = '下载文件失败，请稍后重试。'
    console.error(requestError)
  }
}
const openDrawer = async (document, mode) => {
  drawerDocument.value = document
  drawerMode.value = mode
  drawerDetail.value = null
  drawerError.value = ''
  focusedChunkId.value = ''
  drawerOpen.value = true
  activeDocumentId.value = document.id
  if (mode === 'review') {
    reviewLog.value = []
    reviewLoading.value = true
    try {
      reviewLog.value = await getAgentKnowledgeReviewLog(document.id, agentKey.value)
    } catch (requestError) {
      drawerError.value = requestError?.response?.status === 403 ? '你未被分配为当前范围的审批人，无法查看审批记录。' : '读取审批记录失败，请重试。'
    } finally {
      reviewLoading.value = false
    }
    return
  }
  await loadDrawerDetail()
}
const loadDrawerDetail = async () => {
  if (!drawerDocument.value) return
  const requestId = ++drawerRequestId
  drawerLoading.value = true
  drawerError.value = ''
  try {
    const detail = await getAgentKnowledgeDocument(agentKey.value, drawerDocument.value.id)
    if (requestId === drawerRequestId) drawerDetail.value = detail
  } catch (requestError) {
    if (requestId === drawerRequestId) drawerError.value = '读取文档内容或切片失败，请重试。'
    console.error(requestError)
  } finally {
    if (requestId === drawerRequestId) drawerLoading.value = false
  }
}
const closeDrawer = () => { drawerOpen.value = false; drawerRequestId += 1 }
const copyChunk = async chunk => {
  try { await navigator.clipboard.writeText(chunk.content) } catch { drawerError.value = '复制失败，请检查浏览器剪贴板权限。' }
}
const reindex = async document => {
  busyDocumentId.value = document.id
  try {
    await reindexAgentKnowledgeDocument(document.id, agentKey.value)
    await loadDocuments(true)
  } catch (requestError) {
    error.value = '重新解析提交失败。'
    console.error(requestError)
  } finally {
    busyDocumentId.value = ''
  }
}
const publish = async document => {
  busyDocumentId.value = document.id
  try {
    await publishAgentKnowledgeDocument(document.id, agentKey.value)
    await loadDocuments(true)
    showFlash('已发布，该版本对检索生效')
  } catch (requestError) {
    error.value = requestError?.response?.status === 403
      ? '你未被分配为当前范围的审批人，无法发布。'
      : '发布失败，请确认该版本已完成切片。'
    console.error(requestError)
  } finally {
    busyDocumentId.value = ''
  }
}
const requestReject = document => { pendingReject.value = document; rejectNote.value = '' }
const confirmReject = async () => {
  const target = pendingReject.value
  if (!target || !rejectNote.value || rejecting.value) return
  rejecting.value = true
  try {
    await rejectAgentKnowledgeDocument(target.id, agentKey.value, rejectNote.value)
    pendingReject.value = null
    rejectNote.value = ''
    await loadDocuments(true)
    showFlash('已退回，审核意见进入审批时间线')
  } catch (requestError) {
    error.value = requestError?.response?.status === 403
      ? '你未被分配为当前范围的审批人，无法退回。'
      : '退回失败，请稍后重试。'
    console.error(requestError)
  } finally {
    rejecting.value = false
  }
}
const reviewActionLabel = action => REVIEW_ACTION_LABELS[action] || action
const reviewChannelLabel = channel => REVIEW_CHANNEL_LABELS[channel] || channel
const reviewActorLabel = entry => entry.actorUsername ? `@${entry.actorUsername}` : '系统'
const reviewEntryTone = action => ({ PUBLISH: 'tone-pass', REJECT: 'tone-reject', RESUME_FAIL: 'tone-reject' }[action] || 'tone-neutral')
const formatDateTime = value => value ? new Intl.DateTimeFormat('zh-CN', { month: '2-digit', day: '2-digit', hour: '2-digit', minute: '2-digit' }).format(new Date(value)) : '—'
const requestDeleteGroup = group => {
  if (group.versions.some(isProcessing)) {
    error.value = '该文件有版本正在处理中，请等待处理结束后再删除。'
    return
  }
  pendingDelete.value = { label: group.latest.filename, documents: [...group.versions] }
}
const requestDeleteVersion = document => {
  if (isProcessing(document)) {
    error.value = '该版本正在处理中，请等待处理结束后再删除。'
    return
  }
  pendingDelete.value = { label: document.filename, documents: [document] }
}
const confirmDelete = async () => {
  const target = pendingDelete.value
  if (!target) return
  pendingDelete.value = null
  deleting.value = true
  error.value = ''
  try {
    for (const document of target.documents) {
      await deleteAgentKnowledgeDocument(document.id, agentKey.value)
    }
    if (drawerDocument.value && target.documents.some(document => document.id === drawerDocument.value.id)) {
      closeDrawer()
    }
    await loadDocuments(true)
  } catch (requestError) {
    error.value = '删除失败，请稍后重试。'
    console.error(requestError)
  } finally {
    deleting.value = false
  }
}

onMounted(async () => {
  await loadDocuments()
  // 切片处理中 4 秒刷新；有待审文档时也保持 5 秒轻刷新，让审批人不用手动刷新就能看到新案件
  refreshTimer = window.setInterval(() => {
    if (documents.value.some(isProcessing) || pendingReviewDocuments.value > 0) loadDocuments(true)
  }, 5000)
})
onBeforeUnmount(() => { if (refreshTimer) window.clearInterval(refreshTimer) })
</script>

<style scoped>
.knowledge-admin { height: 100%; min-height: 520px; display: flex; flex-direction: column; gap: 16px; padding: 22px 26px 24px; overflow: hidden; color: var(--sk-label); background: var(--sk-bg); }
.page-header { display: flex; flex: 0 0 auto; align-items: center; justify-content: space-between; gap: 20px; }
.page-header h1 { margin: 5px 0 2px; font-size: 26px; line-height: 1.2; font-weight: 760; }
.page-header p { margin: 0; color: var(--sk-label-2); font-size: 12px; }
.eyebrow { display: flex; align-items: center; gap: 5px; color: var(--zwx-primary); font-size: 10px; line-height: 1.4; font-weight: 800; letter-spacing: 1px; }
.header-actions, .agent-select { display: flex; align-items: center; gap: 8px; }
.manual-review-toggle { display: inline-flex; align-items: center; gap: 5px; height: 36px; padding: 0 10px; border: 1px solid var(--sk-separator); border-radius: 999px; font-size: 11px; color: var(--sk-text-secondary, #5b6472); cursor: pointer; user-select: none; }
.manual-review-toggle input { accent-color: #6d5bd0; cursor: pointer; }
.manual-review-toggle.checked { border-color: #6d5bd0; color: #6d5bd0; font-weight: 600; }
.upload-flash { margin: 0; font-size: 11px; color: #2e7d32; max-width: 220px; line-height: 1.35; }
.agent-select { height: 40px; min-width: 178px; padding: 0 10px; border: 1px solid var(--sk-separator); border-radius: 7px; background: var(--sk-surface); }
.agent-select > span:first-child { display: grid; width: 26px; height: 26px; place-items: center; border-radius: 6px; background: var(--zwx-primary-soft); color: var(--zwx-primary); }
.agent-copy { display: grid; flex: 1; gap: 1px; }.agent-copy small { color: var(--sk-label-3); font-size: 9px; }.agent-copy select { max-width: 142px; border: 0; outline: 0; background: transparent; color: var(--sk-label); font-size: 12px; font-weight: 650; }
.primary-button, .secondary-button, .icon-button { display: inline-flex; height: 38px; flex: 0 0 auto; align-items: center; justify-content: center; gap: 7px; border: 1px solid var(--sk-separator); border-radius: 6px; padding: 0 12px; background: var(--sk-surface); color: var(--sk-label); font-size: 12px; font-weight: 650; }
.primary-button { border-color: var(--zwx-primary); background: var(--zwx-primary); color: #fff; }.primary-button:hover { filter: brightness(.96); }.primary-button:disabled, .secondary-button:disabled { opacity: .55; }
.icon-button { width: 38px; padding: 0; }.icon-button:hover, .secondary-button:hover { border-color: var(--zwx-primary-ring); color: var(--zwx-primary); }
.visually-hidden { position: absolute; width: 1px; height: 1px; overflow: hidden; clip: rect(0,0,0,0); white-space: nowrap; clip-path: inset(50%); }
.overview { display: grid; flex: 0 0 auto; grid-template-columns: repeat(5, minmax(0, 1fr)); gap: 10px; }
.stat { display: flex; min-height: 64px; align-items: center; gap: 11px; border: 1px solid var(--sk-separator); border-radius: 7px; padding: 10px 13px; background: var(--sk-surface); }
.stat-icon { display: grid; width: 34px; height: 34px; flex: 0 0 auto; place-items: center; border-radius: 7px; }.stat-icon.blue { background: #eaf2ff; color: #1677e8; }.stat-icon.green { background: #e8f7ef; color: #199956; }.stat-icon.amber { background: #fff2e3; color: #bf6900; }.stat-icon.violet { background: #f0edff; color: #7157d9; }
.stat > span:last-child { display: grid; gap: 1px; }.stat small { color: var(--sk-label-3); font-size: 10px; }.stat strong { font-size: 20px; line-height: 1.1; font-variant-numeric: tabular-nums; }
.error-banner { display: flex; flex: 0 0 auto; align-items: center; gap: 7px; margin: -4px 0 0; border: 1px solid #f2c7c2; border-radius: 6px; padding: 8px 10px; background: #fff7f6; color: #a63830; font-size: 12px; }.error-banner button { margin-left: auto; border: 0; background: transparent; color: inherit; font-weight: 700; }
.browser-layout { display: grid; min-height: 0; flex: 1; grid-template-columns: minmax(210px, 250px) minmax(0, 1fr); gap: 12px; }
.folder-panel, .files-panel { min-width: 0; min-height: 0; overflow: hidden; border: 1px solid var(--sk-separator); border-radius: 7px; background: var(--sk-surface); }
.folder-panel { display: flex; flex-direction: column; }
.folder-heading { display: flex; align-items: center; justify-content: space-between; min-height: 58px; border-bottom: 1px solid var(--sk-separator); padding: 0 14px; }.folder-heading > div { display: grid; gap: 2px; }.folder-heading strong { font-size: 13px; }.folder-heading span { color: var(--sk-label-3); font-size: 10px; }
.subtle-icon { display: grid; width: 30px; height: 30px; place-items: center; border: 1px solid var(--sk-separator); border-radius: 6px; background: transparent; color: var(--sk-label-2); }.subtle-icon:hover { border-color: var(--zwx-primary-ring); color: var(--zwx-primary); }
.category-form { display: flex; gap: 4px; padding: 8px 10px 3px; }.category-form input { min-width: 0; flex: 1; height: 30px; border: 1px solid var(--sk-separator-strong); border-radius: 5px; padding: 0 7px; outline-color: var(--zwx-primary); font-size: 11px; }.category-form button { display: grid; width: 30px; place-items: center; border: 1px solid var(--sk-separator); border-radius: 5px; background: var(--sk-surface); color: var(--sk-label-2); }
.folder-tree { display: grid; align-content: start; gap: 2px; overflow-y: auto; padding: 10px 8px; }.folder-node { display: flex; width: 100%; min-height: 36px; align-items: center; justify-content: space-between; gap: 8px; border: 1px solid transparent; border-radius: 5px; padding: 0 9px; background: transparent; color: var(--sk-label-2); text-align: left; font-size: 12px; }.folder-node:hover { background: var(--sk-fill); }.folder-node.selected { border-color: #d6e6ff; background: #edf5ff; color: #126ede; font-weight: 700; }.folder-label { display: flex; min-width: 0; align-items: center; gap: 8px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }.folder-count { min-width: 23px; border-radius: 10px; padding: 2px 6px; background: var(--sk-fill); color: var(--sk-label-3); text-align: center; font-size: 10px; font-weight: 700; }.folder-node.selected .folder-count { background: #fff; color: #126ede; }
.folder-foot { display: flex; flex: 0 0 auto; align-items: center; gap: 6px; margin-top: auto; border-top: 1px solid var(--sk-separator); padding: 11px 12px; color: var(--sk-label-3); font-size: 9px; }.legend-dot { width: 7px; height: 7px; border-radius: 50%; margin-left: 3px; }.legend-dot.ready { background: #22a565; }.legend-dot.busy { background: #e39825; }.legend-dot.failed { background: #d84b43; }
.files-panel { display: flex; flex-direction: column; }.files-toolbar { display: flex; min-height: 60px; flex: 0 0 auto; align-items: center; justify-content: space-between; gap: 14px; border-bottom: 1px solid var(--sk-separator); padding: 10px 14px; }.files-title { display: flex; min-width: 0; align-items: baseline; gap: 9px; }.files-title h2 { overflow: hidden; margin: 0; font-size: 15px; text-overflow: ellipsis; white-space: nowrap; }.files-title > span { flex: 0 0 auto; color: var(--sk-label-3); font-size: 10px; }.file-controls { display: flex; align-items: center; gap: 7px; }.search-field { display: flex; width: min(260px, 28vw); height: 34px; align-items: center; gap: 7px; border: 1px solid var(--sk-separator); border-radius: 5px; padding: 0 9px; color: var(--sk-label-3); }.search-field input { width: 100%; min-width: 0; border: 0; outline: 0; background: transparent; color: var(--sk-label); font-size: 11px; }.file-controls select { height: 34px; border: 1px solid var(--sk-separator); border-radius: 5px; padding: 0 8px; background: var(--sk-surface); color: var(--sk-label-2); font-size: 10px; }
.table-scroll { min-height: 0; flex: 1; overflow: auto; }.file-table { width: 100%; border-collapse: collapse; table-layout: fixed; }.file-table th { position: sticky; z-index: 1; top: 0; height: 34px; border-bottom: 1px solid var(--sk-separator); padding: 0 10px; background: #f8f9fb; color: var(--sk-label-3); text-align: left; font-size: 10px; font-weight: 650; }.file-table th:first-child, .file-table td:first-child { padding-left: 14px; }.file-table th:nth-child(1) { width: 31%; }.file-table th:nth-child(2) { width: 23%; }.file-table th:nth-child(3) { width: 13%; }.file-table th:nth-child(4) { width: 13%; }.file-table th:nth-child(5) { width: 20%; }.file-table td { height: 58px; border-bottom: 1px solid var(--sk-separator); padding: 5px 10px; }.file-row:hover, .version-row:hover { background: #fafbfd; }.file-row.selected, .version-row.selected { background: #eff6ff; }
.file-cell { display: flex; min-width: 0; align-items: center; gap: 9px; }.file-icon { display: grid; width: 34px; height: 36px; flex: 0 0 auto; place-items: center; border-radius: 6px; background: #edf3ff; color: #317be5; }.file-icon.tone-pdf { background: #fff0ef; color: #d64d44; }.file-icon.tone-doc { background: #eaf2ff; color: #326fc7; }.file-icon.tone-sheet { background: #eaf6ef; color: #258657; }.file-icon.tone-slide { background: #fff3e7; color: #c2752e; }.file-icon.tone-text { background: #f0efff; color: #7563ce; }.file-icon.small-icon { width: 29px; height: 30px; }.file-copy { display: grid; min-width: 0; gap: 3px; }.file-copy strong { overflow: hidden; color: var(--sk-label); font-size: 11px; font-weight: 680; text-overflow: ellipsis; white-space: nowrap; }.file-copy small { overflow: hidden; color: var(--sk-label-3); font-size: 9px; text-overflow: ellipsis; white-space: nowrap; }.history-button { display: inline-flex; height: 24px; flex: 0 0 auto; align-items: center; gap: 3px; border: 1px solid var(--sk-separator); border-radius: 4px; padding: 0 4px; background: var(--sk-surface); color: var(--sk-label-3); font-size: 9px; }.history-button[aria-expanded="true"] svg:last-child { transform: rotate(180deg); }
.chunk-status { display: inline-flex; align-items: center; gap: 5px; border: 0; border-radius: 4px; padding: 4px 6px; background: transparent; font-size: 10px; white-space: nowrap; }.chunk-status:disabled { cursor: default; }.chunk-status.complete { background: #edf8f1; color: #21834e; }.chunk-status.processing { background: #fff5e8; color: #a76312; }.chunk-status.failed { background: #fff0ef; color: #b33f37; }.version-pill, .active-pill, .archived-pill, .pending-pill { display: inline-block; margin-right: 4px; border-radius: 4px; padding: 3px 5px; background: #f0efff; color: #6e58d2; font-size: 9px; font-weight: 700; }.active-pill { background: #e8f7ee; color: #18894e; }.archived-pill { background: #f1f2f4; color: #707986; }.pending-pill { background: #fff5e8; color: #a76312; }.rejected-pill { background: #fff0ef; color: #b33f37; }.date-cell { color: var(--sk-label-3); font-size: 10px; white-space: nowrap; }.row-actions { display: flex; align-items: center; justify-content: flex-start; gap: 2px; }.row-actions button { display: grid; width: 27px; height: 27px; flex: 0 0 auto; place-items: center; border: 1px solid transparent; border-radius: 4px; background: transparent; color: var(--sk-label-2); }.row-actions button:hover:not(:disabled) { border-color: var(--zwx-primary-ring); background: var(--zwx-primary-soft); color: var(--zwx-primary); }.row-actions button:disabled { opacity: .4; }.row-actions button.publish-action { color: #198b51; }.row-actions button.reject-action { color: #a76312; }.row-actions button.reject-action:hover:not(:disabled) { border-color: rgba(167,99,18,.3); background: #fff5e8; color: #a76312; }.row-actions button.danger-action { color: #b33f37; }.row-actions button.danger-action:hover:not(:disabled) { border-color: rgba(255,59,48,.28); background: rgba(255,59,48,.08); color: #b33f37; }
.version-row td { height: 52px; background: #fcfcfd; }.version-file-cell { padding-left: 18px; }.version-branch { width: 12px; height: 23px; flex: 0 0 auto; border-left: 1px solid var(--sk-separator-strong); border-bottom: 1px solid var(--sk-separator-strong); border-radius: 0 0 0 5px; margin-top: -11px; }.files-foot { display: flex; flex: 0 0 auto; justify-content: space-between; border-top: 1px solid var(--sk-separator); padding: 8px 13px; color: var(--sk-label-3); font-size: 9px; }.table-state { height: 180px !important; color: var(--sk-label-3); text-align: center; font-size: 12px; }.empty-state { display: table-cell; }.empty-state > * { display: block; margin: 7px auto; }.empty-state strong { color: var(--sk-label); }.empty-state .primary-button { display: inline-flex; margin-top: 10px; }
.inspect-drawer { position: fixed; z-index: 1200; inset: 0; display: flex; flex-direction: column; background: var(--sk-bg); }.drawer-header { display: flex; min-height: 76px; flex: 0 0 auto; align-items: center; justify-content: space-between; gap: 18px; border-bottom: 1px solid var(--sk-separator); padding: 11px 20px; background: var(--sk-surface); }.drawer-title { display: flex; min-width: 0; align-items: center; gap: 14px; }.drawer-title > div { min-width: 0; }.drawer-title h2 { overflow: hidden; margin: 2px 0; font-size: 15px; text-overflow: ellipsis; white-space: nowrap; }.drawer-title p { margin: 0; color: var(--sk-label-3); font-size: 10px; }.close-drawer { display: grid; width: 34px; height: 34px; flex: 0 0 auto; place-items: center; border: 1px solid var(--sk-separator); border-radius: 5px; background: var(--sk-surface); color: var(--sk-label-2); }.drawer-actions { display: flex; align-items: center; gap: 7px; }.drawer-error { display: flex; flex: 0 0 auto; align-items: center; gap: 7px; padding: 8px 18px; background: #fff0ef; color: #a63830; font-size: 11px; }.drawer-body { display: grid; min-height: 0; flex: 1; grid-template-columns: minmax(0, 1fr); }.drawer-body.chunks-mode { grid-template-columns: minmax(320px, 42%) minmax(0, 58%); }.chunks-pane, .source-pane { display: flex; min-width: 0; min-height: 0; flex-direction: column; }.chunks-pane { border-right: 1px solid var(--sk-separator); background: #f7f8fa; }.source-pane { background: #eef0f2; }.pane-heading { display: flex; min-height: 52px; flex: 0 0 auto; align-items: center; justify-content: space-between; gap: 10px; border-bottom: 1px solid var(--sk-separator); padding: 8px 14px; background: var(--sk-surface); }.pane-heading > div { display: grid; min-width: 0; gap: 2px; }.pane-heading strong { font-size: 11px; }.pane-heading small { overflow: hidden; color: var(--sk-label-3); font-size: 9px; text-overflow: ellipsis; white-space: nowrap; }.pane-total { display: grid; min-width: 24px; height: 22px; place-items: center; border-radius: 4px; background: #edf3ff; color: var(--zwx-primary); font-size: 10px; font-weight: 700; }.source-hint { color: var(--sk-label-3); font-size: 9px; }.chunk-stack { display: grid; min-height: 0; flex: 1; align-content: start; gap: 8px; overflow: auto; padding: 10px; }.chunk-card { min-width: 0; border: 1px solid var(--sk-separator); border-radius: 6px; background: var(--sk-surface); }.chunk-card.focused { border-color: #8bbaff; box-shadow: 0 0 0 2px rgba(22,119,232,.08); }.chunk-card header, .chunk-card footer { display: flex; align-items: center; justify-content: space-between; gap: 8px; padding: 7px 9px; }.chunk-card header { border-bottom: 1px solid var(--sk-separator); }.chunk-card header strong { color: var(--sk-label); font-size: 10px; }.chunk-card header span { color: var(--sk-label-3); font-size: 9px; }.chunk-card pre { margin: 0; padding: 10px; color: var(--sk-label); font: 11px/1.65 inherit; white-space: pre-wrap; overflow-wrap: anywhere; }.chunk-card footer { border-top: 1px solid var(--sk-separator); color: var(--sk-label-3); font-size: 8px; }.chunk-card footer span { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }.chunk-card footer button { display: grid; width: 24px; height: 24px; flex: 0 0 auto; place-items: center; border: 0; border-radius: 4px; background: transparent; color: var(--sk-label-2); }.chunk-card footer button:hover { background: var(--sk-fill); color: var(--zwx-primary); }.source-preview { min-height: 0; flex: 1; overflow: hidden; }.pane-state { display: grid; flex: 1; place-content: center; justify-items: center; gap: 8px; padding: 22px; color: var(--sk-label-3); text-align: center; font-size: 11px; }.pane-state strong { color: var(--sk-label); font-size: 12px; }.drawer-enter-active, .drawer-leave-active { transition: opacity .18s ease, transform .18s ease; }.drawer-enter-from, .drawer-leave-to { opacity: 0; transform: translateY(8px); }
.spinning { animation: spin .9s linear infinite; }@keyframes spin { to { transform: rotate(360deg); } }
.review-timeline { display: grid; min-height: 0; flex: 1; align-content: start; gap: 8px; overflow: auto; padding: 10px; }.review-entry { min-width: 0; border: 1px solid var(--sk-separator); border-left-width: 3px; border-radius: 6px; background: var(--sk-surface); }.review-entry header { display: flex; align-items: center; justify-content: space-between; gap: 8px; padding: 7px 9px; }.review-action { display: inline-block; border-radius: 4px; padding: 3px 6px; font-size: 9px; font-weight: 700; }.review-entry.tone-pass { border-left-color: #18894e; }.review-entry.tone-pass .review-action { background: #e8f7ee; color: #18894e; }.review-entry.tone-reject { border-left-color: #b33f37; }.review-entry.tone-reject .review-action { background: #fff0ef; color: #b33f37; }.review-entry.tone-neutral { border-left-color: #a76312; }.review-entry.tone-neutral .review-action { background: #fff5e8; color: #a76312; }.review-meta { color: var(--sk-label-3); font-size: 9px; }.review-entry p { margin: 0; padding: 0 9px 7px; color: var(--sk-label); font-size: 11px; line-height: 1.6; }.review-entry footer { display: flex; align-items: center; justify-content: space-between; gap: 8px; padding: 0 9px 7px; color: var(--sk-label-3); font-size: 8px; }
.reject-mask { position: fixed; z-index: 1300; inset: 0; display: grid; place-items: center; background: rgba(18,20,26,.42); }.reject-dialog { display: grid; width: min(480px, calc(100vw - 40px)); gap: 10px; border: 1px solid var(--sk-separator); border-radius: 10px; padding: 16px 18px; background: var(--sk-surface); box-shadow: 0 18px 50px rgba(18,20,26,.22); }.reject-dialog header { display: flex; align-items: center; justify-content: space-between; gap: 10px; }.reject-dialog strong { font-size: 13px; }.reject-dialog p { margin: 0; color: var(--sk-label-2); font-size: 11px; line-height: 1.6; }.reject-dialog textarea { resize: vertical; border: 1px solid var(--sk-separator); border-radius: 6px; padding: 9px 10px; font: 12px/1.6 inherit; color: var(--sk-label); }.reject-dialog textarea:focus { outline: 2px solid var(--zwx-primary-ring); }.reject-dialog footer { display: flex; justify-content: flex-end; gap: 8px; }.reject-dialog footer button { display: inline-flex; align-items: center; gap: 5px; border: 1px solid var(--sk-separator); border-radius: 6px; padding: 7px 12px; font-size: 11px; background: var(--sk-surface); color: var(--sk-label); }.reject-dialog footer button.danger-action { border-color: rgba(179,63,55,.35); background: #fff0ef; color: #b33f37; font-weight: 600; }.reject-dialog footer button:disabled { opacity: .5; }
@media (max-width: 1100px) { .knowledge-admin { padding: 18px; }.browser-layout { grid-template-columns: 205px minmax(0,1fr); }.file-table th:nth-child(1) { width: 28%; }.file-table th:nth-child(2) { width: 25%; }.file-table th:nth-child(3) { width: 12%; }.file-table th:nth-child(4) { width: 12%; }.file-table th:nth-child(5) { width: 23%; }.row-actions button { width: 25px; }.file-cell { gap: 6px; } }
@media (max-width: 780px) { .knowledge-admin { height: auto; min-height: calc(100vh - 64px); overflow: visible; padding: 16px 12px; }.page-header { align-items: flex-start; }.page-header h1 { font-size: 22px; }.header-actions { flex-wrap: wrap; justify-content: flex-end; }.agent-select { min-width: 142px; }.overview { grid-template-columns: repeat(2,minmax(0,1fr)); gap: 7px; }.stat { min-height: 57px; padding: 8px; }.browser-layout { min-height: 680px; grid-template-columns: 1fr; grid-template-rows: auto minmax(480px,1fr); }.folder-panel { min-height: 0; }.folder-tree { display: flex; overflow-x: auto; padding: 7px; }.folder-node { width: auto; flex: 0 0 auto; }.folder-foot { display: none; }.files-toolbar { align-items: flex-start; flex-direction: column; }.file-controls { width: 100%; }.search-field { width: 100%; }.table-scroll { overflow-x: auto; }.file-table { min-width: 920px; }.drawer-body.chunks-mode { grid-template-columns: minmax(0,1fr); grid-template-rows: minmax(38%, 42vh) minmax(0,1fr); }.chunks-pane { border-right: 0; border-bottom: 1px solid var(--sk-separator); }.drawer-header { padding: 9px 11px; }.drawer-title { gap: 8px; }.drawer-actions .secondary-button { width: 36px; overflow: hidden; padding: 0 10px; font-size: 0; }.drawer-actions .secondary-button :deep(svg) { flex: 0 0 auto; }.drawer-title h2 { max-width: 52vw; font-size: 13px; } }
@media (max-width: 480px) { .page-header { gap: 8px; }.page-header p { max-width: 190px; }.header-actions { gap: 5px; }.agent-select { height: 36px; min-width: 125px; padding: 0 6px; }.agent-select > span:first-child { width: 22px; height: 22px; }.agent-copy select { max-width: 95px; font-size: 10px; }.header-actions > .icon-button, .header-actions > .primary-button { height: 36px; }.header-actions > .icon-button { width: 34px; }.header-actions > .primary-button { gap: 4px; padding: 0 8px; font-size: 10px; }.stat strong { font-size: 17px; }.drawer-title .eyebrow { font-size: 8px; }.drawer-title p { max-width: 58vw; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; } }
.category-field { display: inline-flex; width: max-content; max-width: 100%; align-items: center; gap: 3px; color: var(--sk-label-3); }.category-field select { min-width: 0; max-width: 140px; border: 0; outline: 0; background: transparent; color: inherit; font-size: 9px; }.category-field select:focus { color: var(--zwx-primary); }
.chunk-card pre { font-family: inherit; font-size: 11px; line-height: 1.65; }
</style>
