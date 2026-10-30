<template>
  <div class="wb-home">
    <div class="wb-home-inner">
      <header class="home-head">
        <div>
          <h1 class="home-title">服务概览</h1>
          <p class="home-date">{{ dateLabel }}</p>
        </div>
        <router-link to="/super-agent" class="new-task">
          <Plus :size="15" /> 新建任务
        </router-link>
      </header>

      <div class="stat-row">
        <router-link to="/super-agent" class="stat-card">
          <span class="stat-value">{{ stats.workspaces }}</span>
          <span class="stat-label">工作区</span>
        </router-link>
        <router-link to="/super-agent" class="stat-card">
          <span class="stat-value">{{ stats.conversations }}</span>
          <span class="stat-label">会话</span>
        </router-link>
        <div class="stat-card">
          <span class="stat-value is-running" :class="{ pulse: stats.running > 0 }">{{ stats.running }}</span>
          <span class="stat-label">进行中</span>
        </div>
        <div class="stat-card">
          <span class="stat-value is-done">{{ stats.completed }}</span>
          <span class="stat-label">已完成</span>
        </div>
        <div class="stat-card">
          <span class="stat-value is-waiting">{{ stats.pending }}</span>
          <span class="stat-label">待处理</span>
        </div>
        <router-link to="/workflows" class="stat-card">
          <span class="stat-value">{{ stats.workflows }}</span>
          <span class="stat-label">工作流</span>
        </router-link>
        <router-link to="/knowledge-admin" class="stat-card">
          <span class="stat-value">{{ stats.documents }}</span>
          <span class="stat-label">知识文档</span>
        </router-link>
      </div>

      <div class="home-grid">
        <div class="home-main">
          <section class="home-section">
            <div class="section-head">
              <h2>任务动态</h2>
              <router-link to="/super-agent" class="section-more">
                运行历史 <ChevronRight :size="14" />
              </router-link>
            </div>

            <p v-if="loading" class="section-hint">加载中…</p>
            <p v-else-if="!runs.length" class="section-empty">
              <Activity :size="18" /> 暂无任务运行，去「任务」页发起第一个任务
            </p>

            <template v-else>
              <template v-for="group in runGroups" :key="group.key">
                <p v-if="group.items.length" class="group-title">
                  <span class="group-dot" :class="group.dotClass" /> {{ group.label }}
                  <span class="group-count">{{ group.items.length }}</span>
                </p>
                <router-link
                  v-for="run in group.items"
                  :key="run.id"
                  class="run-card"
                  :to="{ path: '/super-agent', query: { conversationId: run.conversationId, runId: run.id } }"
                >
                  <span class="run-dot" :class="group.dotClass" />
                  <span class="run-main">
                    <span class="run-title">{{ runTitle(run) }}</span>
                    <span class="run-meta">{{ run.conversationTitle || '未命名会话' }} · {{ formatTime(run.updatedAt) }}</span>
                  </span>
                  <span v-if="group.key === 'pending'" class="run-flag">可恢复</span>
                </router-link>
              </template>
            </template>
          </section>

          <section class="home-section">
            <div class="section-head">
              <h2>工作流</h2>
              <router-link to="/workflows" class="section-more">
                全部工作流 <ChevronRight :size="14" />
              </router-link>
            </div>

            <p v-if="loading" class="section-hint">加载中…</p>
            <p v-else-if="!workflowCards.length" class="section-empty">
              <Workflow :size="18" /> 还没有工作流<br />
              <span class="empty-sub">在工作流页新建后即可一键运行</span>
            </p>

            <div v-else class="wf-list">
              <router-link
                v-for="workflow in workflowCards"
                :key="workflow.key"
                class="wf-item"
                :to="`/workflows/${workflow.key}`"
              >
                <span class="wf-main">
                  <span class="wf-name">{{ workflow.name }}</span>
                  <span class="wf-desc">{{ workflow.description || '暂无描述' }}</span>
                </span>
                <span class="wf-meta">
                  <span>v{{ workflow.version }} · {{ workflow.steps.length }} 节点</span>
                  <span v-if="latestRunOf(workflow.key)" class="wf-run">
                    <span class="wf-dot" :class="runDotClass(latestRunOf(workflow.key).status)" />
                    {{ runStatusLabel(latestRunOf(workflow.key).status) }} · {{ formatTime(latestRunOf(workflow.key).createdAt) }}
                  </span>
                  <span v-else class="wf-run is-idle">未运行</span>
                </span>
              </router-link>
            </div>
          </section>

          <section class="home-section">
            <div class="section-head">
              <h2>AI 创作</h2>
              <router-link to="/creative" class="section-more">
                进入创作 <ChevronRight :size="14" />
              </router-link>
            </div>
            <p v-if="!creativeJobs.length" class="section-hint">
              图片、视频、小说与漫画创作；当前开放图片，任务与素材保存在服务端。
            </p>
            <div v-else class="creative-list">
              <router-link
                v-for="job in creativeJobs"
                :key="job.jobId"
                :to="`/creative/tasks/${job.jobId}`"
                class="creative-item"
              >
                <span class="creative-status" :class="creativeTone(job.status)">{{ creativeLabel(job.status) }}</span>
                <span class="creative-time">{{ formatTime(job.createdAt) }}</span>
              </router-link>
            </div>
            <div class="creative-actions">
              <router-link to="/creative" class="creative-action">创作台</router-link>
              <router-link to="/creative/assets" class="creative-action">素材库</router-link>
            </div>
          </section>
        </div>

        <div class="home-side">
          <section class="home-section">
            <div class="section-head">
              <h2>工作区</h2>
              <span class="section-count">{{ projects.length }}</span>
            </div>
            <p v-if="loading" class="section-hint">加载中…</p>
            <p v-else-if="!projects.length" class="section-empty">
              <FolderOpen :size="18" /> 尚未创建工作区<br />
              <span class="empty-sub">在任务页绑定本地目录即可创建</span>
            </p>
            <div v-else class="project-list">
              <router-link
                v-for="project in projects"
                :key="project.id"
                class="project-card"
                to="/super-agent"
              >
                <span class="project-name">{{ project.name }}</span>
                <span class="project-meta">{{ project.directoryName || '本地目录' }} · {{ formatDate(project.createdAt) }}</span>
              </router-link>
            </div>
          </section>

          <section class="home-section">
            <div class="section-head">
              <h2>知识库</h2>
              <router-link to="/knowledge-admin" class="section-more">
                管理 <ChevronRight :size="14" />
              </router-link>
            </div>

            <p v-if="loading" class="section-hint">加载中…</p>
            <p v-else-if="!knowledgeStats.total" class="section-empty">
              <Database :size="18" /> 知识库还没有资料<br />
              <span class="empty-sub">上传文档并切片后可被检索引用</span>
            </p>

            <template v-else>
              <div class="kb-stats">
                <div class="kb-stat">
                  <span class="kb-value">{{ knowledgeStats.active }}</span>
                  <span class="kb-label">已发布</span>
                </div>
                <div class="kb-stat">
                  <span class="kb-value is-waiting">{{ knowledgeStats.pending }}</span>
                  <span class="kb-label">待审批</span>
                </div>
                <div class="kb-stat">
                  <span class="kb-value is-running">{{ knowledgeStats.processing }}</span>
                  <span class="kb-label">处理中</span>
                </div>
                <div class="kb-stat">
                  <span class="kb-value is-fail">{{ knowledgeStats.failed }}</span>
                  <span class="kb-label">失败</span>
                </div>
              </div>
              <div class="kb-list">
                <router-link
                  v-for="document in recentDocuments"
                  :key="document.id"
                  class="kb-item"
                  to="/knowledge-admin"
                >
                  <span class="kb-name">{{ document.filename }}</span>
                  <span class="kb-meta">{{ knowledgeStatusLabel(document) }} · {{ formatTime(document.createdAt) }}</span>
                </router-link>
              </div>
            </template>
          </section>

          <section class="home-section">
            <div class="section-head">
              <h2>服务状态</h2>
            </div>
            <div class="service-row">
              <span class="service-label">对话模型</span>
              <span v-if="modelLabel" class="service-value">{{ modelLabel }}</span>
              <router-link v-else to="/settings/model" class="service-action">去配置 →</router-link>
            </div>
            <div class="service-row">
              <span class="service-label">模型供应商</span>
              <router-link to="/settings/model" class="service-action">管理 →</router-link>
            </div>
          </section>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { Activity, ChevronRight, Database, FolderOpen, Plus, Workflow } from 'lucide-vue-next'
import { message } from 'ant-design-vue'
import {
  listAgentKnowledgeDocuments,
  listManusRunOverviews,
  listManusConversations,
  listModelSettings,
  listProjects,
  listWorkflows,
  listCreativeJobs
} from '../api'
import { KNOWLEDGE_AGENTS } from '../config/agents'

const projects = ref([])
const conversations = ref([])
const runs = ref([])
const workflows = ref([])
const workflowRuns = ref([])
const knowledgeDocs = ref([])
const modelLabel = ref('')
const creativeJobs = ref([])
const loading = ref(true)

// 知识库列表含历史版本，概览只统计未归档的文档
const liveDocuments = computed(() => knowledgeDocs.value.filter(doc => doc.lifecycleStatus !== 'ARCHIVED'))

const stats = computed(() => {
  const count = status => runs.value.filter(run => status.includes(run.status)).length
  return {
    workspaces: projects.value.length,
    conversations: conversations.value.length,
    running: count(['RUNNING']),
    completed: count(['COMPLETED']),
    pending: count(['INTERRUPTED', 'FAILED']),
    workflows: workflows.value.length,
    documents: liveDocuments.value.length
  }
})

const workflowCards = computed(() => workflows.value.slice(0, 4))

const knowledgeStats = computed(() => {
  const docs = liveDocuments.value
  return {
    total: docs.length,
    active: docs.filter(doc => doc.lifecycleStatus === 'ACTIVE').length,
    pending: docs.filter(doc => doc.lifecycleStatus === 'READY').length,
    processing: docs.filter(doc => ['PENDING', 'INDEXING'].includes(doc.status)).length,
    failed: docs.filter(doc => doc.status === 'FAILED' || doc.lifecycleStatus === 'REJECTED').length
  }
})

const recentDocuments = computed(() => [...liveDocuments.value]
  .sort((left, right) => new Date(right.createdAt || 0) - new Date(left.createdAt || 0))
  .slice(0, 3))

const runGroups = computed(() => [
  { key: 'running', label: '进行中', dotClass: 'is-running', items: runs.value.filter(run => run.status === 'RUNNING') },
  { key: 'pending', label: '待处理', dotClass: 'is-waiting', items: runs.value.filter(run => ['INTERRUPTED', 'FAILED'].includes(run.status)) },
  { key: 'done', label: '最近完成', dotClass: 'is-done', items: runs.value.filter(run => run.status === 'COMPLETED').slice(0, 8) }
])

const dateLabel = computed(() => {
  const now = new Date()
  const week = ['日', '一', '二', '三', '四', '五', '六'][now.getDay()]
  return `${now.getMonth() + 1}月${now.getDate()}日 星期${week}`
})

function runTitle(run) {
  const text = (run.input || '').replace(/\s+/g, ' ').trim()
  return text ? (text.length > 60 ? `${text.slice(0, 60)}…` : text) : '任务运行'
}
function formatTime(value) {
  if (!value) return ''
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return ''
  const minutes = Math.floor((Date.now() - date.getTime()) / 60000)
  if (minutes < 1) return '刚刚'
  if (minutes < 60) return `${minutes} 分钟前`
  const hours = Math.floor(minutes / 60)
  if (hours < 24) return `${hours} 小时前`
  return `${date.getMonth() + 1}月${date.getDate()}日`
}
function formatDate(value) {
  if (!value) return ''
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? '' : `${date.getMonth() + 1}月${date.getDate()}日`
}
function latestRunOf(workflowKey) {
  return workflowRuns.value.find(run => run.workflowKey === workflowKey) || null
}
function runStatusLabel(status) {
  return { RUNNING: '运行中', SUCCEEDED: '成功', FAILED: '失败', INTERRUPTED: '已中断' }[status] || status
}
function runDotClass(status) {
  if (status === 'RUNNING') return 'is-running'
  if (status === 'FAILED' || status === 'INTERRUPTED') return 'is-fail'
  return 'is-done'
}
function creativeLabel(status) {
  return {
    QUEUED: '排队中', SUBMITTING: '提交中', RUNNING: '生成中', CANCEL_REQUESTED: '取消中',
    RECONCILING: '待确认', FETCHING: '保存中', INGEST_FAILED: '保存失败',
    SUCCEEDED: '已完成', FAILED: '失败', CANCELLED: '已取消'
  }[status] || status
}
function creativeTone(status) {
  if (status === 'SUCCEEDED') return 'is-done'
  if (status === 'FAILED' || status === 'INGEST_FAILED') return 'is-fail'
  if (['QUEUED', 'SUBMITTING', 'RUNNING', 'FETCHING', 'CANCEL_REQUESTED', 'RECONCILING'].includes(status)) return 'is-running'
  return 'is-idle'
}
function knowledgeStatusLabel(document) {
  if (!document) return ''
  if (['PENDING', 'INDEXING'].includes(document.status)) return '处理中'
  if (document.lifecycleStatus === 'READY') return '待审批'
  if (document.lifecycleStatus === 'REJECTED') return '已驳回'
  if (document.lifecycleStatus === 'ACTIVE') return '已发布'
  if (document.status === 'FAILED' || document.lifecycleStatus === 'FAILED') return '失败'
  return document.status === 'DONE' ? '已完成' : '待切片'
}

onMounted(async () => {
  const [runResult, conversationResult, projectResult, modelResult, workflowResult, creativeResult,
    ...knowledgeResults] =
    await Promise.allSettled([
      listManusRunOverviews(30),
      listManusConversations(),
      listProjects(),
      listModelSettings(),
      listWorkflows(20),
      listCreativeJobs({ limit: 3 }),
      ...KNOWLEDGE_AGENTS.map(agent => listAgentKnowledgeDocuments(agent.key))
    ])
  runs.value = runResult.status === 'fulfilled' ? runResult.value || [] : []
  conversations.value = conversationResult.status === 'fulfilled' ? conversationResult.value || [] : []
  projects.value = projectResult.status === 'fulfilled' ? projectResult.value || [] : []
  workflows.value = workflowResult.status === 'fulfilled' ? workflowResult.value?.definitions || [] : []
  workflowRuns.value = workflowResult.status === 'fulfilled' ? workflowResult.value?.runs || [] : []
  creativeJobs.value = creativeResult.status === 'fulfilled' ? creativeResult.value?.items || [] : []
  knowledgeDocs.value = knowledgeResults.flatMap(result =>
    result.status === 'fulfilled' ? result.value || [] : [])
  if (modelResult.status === 'fulfilled') {
    const chat = (modelResult.value?.models || []).find(item => (item.key || '').toLowerCase() === 'chat')
    const effective = chat?.effective
    modelLabel.value = effective?.modelName
      ? `${effective.provider === 'dashscope' ? 'DashScope' : 'OpenAI 兼容'} · ${effective.modelName}`
      : ''
  }
  loading.value = false
  if (runResult.status === 'rejected' && conversationResult.status === 'rejected') {
    message.warning('概览数据加载失败，请确认后端服务可用')
  }
})
</script>

<style scoped>
.wb-home {
  flex: 1;
  overflow-y: auto;
}

.wb-home-inner {
  max-width: 1120px;
  margin: 0 auto;
  padding: 32px 32px 56px;
  display: flex;
  flex-direction: column;
  gap: 18px;
}

.home-head {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
}

.home-title {
  font-size: 24px;
  font-weight: 650;
  letter-spacing: -0.02em;
  color: var(--wb-text);
}

.home-date {
  margin-top: 5px;
  font-size: 13px;
  color: var(--wb-text-2);
}

.new-task {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 8px 16px;
  border-radius: 10px;
  background: var(--wb-brand);
  color: var(--wb-brand-contrast);
  font-size: 13px;
  font-weight: 500;
  transition: background 0.15s;
}

.new-task:hover {
  background: var(--wb-brand-hover);
}

.stat-row {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(132px, 1fr));
  gap: 12px;
}

.stat-card {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 16px 18px;
  border: 1px solid var(--wb-border);
  border-radius: var(--wb-radius-lg);
  background: var(--wb-surface);
  transition: border-color 0.15s, box-shadow 0.15s;
}

a.stat-card:hover {
  border-color: var(--wb-brand);
  box-shadow: 0 0 0 3px var(--wb-brand-soft);
}

.stat-value {
  font-size: 26px;
  font-weight: 650;
  letter-spacing: -0.02em;
  color: var(--wb-text);
  line-height: 1.1;
}

.stat-value.is-running { color: var(--wb-info); }
.stat-value.is-done { color: var(--wb-success); }
.stat-value.is-waiting { color: var(--wb-warning); }

.stat-value.pulse {
  animation: pulse-dot 1.6s ease-in-out infinite;
}

@keyframes pulse-dot {
  0%, 100% { opacity: 1; }
  50% { opacity: 0.55; }
}

.stat-label {
  font-size: 12.5px;
  color: var(--wb-muted);
}

.home-grid {
  display: grid;
  grid-template-columns: 3fr 2fr;
  gap: 16px;
  align-items: start;
}

.home-main,
.home-side {
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.home-section {
  padding: 18px 18px 16px;
  border: 1px solid var(--wb-border);
  border-radius: var(--wb-radius-lg);
  background: var(--wb-surface);
}

.section-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 10px;
}

.section-head h2 {
  font-size: 15px;
  font-weight: 600;
  color: var(--wb-text);
}

.section-count {
  padding: 1px 9px;
  border-radius: 999px;
  background: var(--wb-brand-soft);
  color: var(--wb-brand);
  font-size: 12px;
  font-weight: 550;
}

.section-more {
  display: inline-flex;
  align-items: center;
  gap: 2px;
  color: var(--wb-muted);
  font-size: 12.5px;
}

.section-more:hover {
  color: var(--wb-brand);
}

.section-hint,
.section-empty {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 6px;
  padding: 22px 4px;
  color: var(--wb-muted);
  font-size: 13px;
}

.empty-sub {
  font-size: 12px;
  opacity: 0.8;
}

.group-title {
  display: flex;
  align-items: center;
  gap: 7px;
  margin: 12px 0 6px;
  color: var(--wb-text-2);
  font-size: 12.5px;
  font-weight: 550;
}

.group-title:first-of-type {
  margin-top: 2px;
}

.group-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
}

.group-dot.is-running { background: var(--wb-info); }
.group-dot.is-waiting { background: var(--wb-warning); }
.group-dot.is-done { background: var(--wb-success); }

.group-count {
  color: var(--wb-muted);
  font-weight: 400;
}

.run-card {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 9px 8px;
  border-radius: var(--wb-radius);
  transition: background 0.15s;
}

.run-card:hover {
  background: var(--wb-fill);
}

.run-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  flex: 0 0 8px;
}

.run-dot.is-running {
  background: var(--wb-info);
  box-shadow: 0 0 0 3px rgba(37, 144, 255, 0.18);
}

.run-dot.is-waiting { background: var(--wb-warning); }
.run-dot.is-done { background: var(--wb-success); }

.run-main {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 3px;
  min-width: 0;
}

.run-title {
  color: var(--wb-text);
  font-size: 13px;
  font-weight: 500;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.run-meta {
  color: var(--wb-muted);
  font-size: 12px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.run-flag {
  flex: 0 0 auto;
  padding: 2px 9px;
  border-radius: 999px;
  background: var(--wb-brand-soft);
  color: var(--wb-brand);
  font-size: 11px;
}

.project-list {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.project-card {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 10px;
  border-radius: var(--wb-radius);
  transition: background 0.15s;
}

.project-card:hover {
  background: var(--wb-fill);
}

.project-name {
  color: var(--wb-text);
  font-size: 13.5px;
  font-weight: 500;
}

.project-meta {
  color: var(--wb-muted);
  font-size: 12px;
  font-family: var(--wb-font-mono, monospace);
}

.wf-list,
.kb-list {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.wf-item {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  padding: 10px 8px;
  border-radius: var(--wb-radius);
  transition: background 0.15s;
}

.wf-item:hover,
.kb-item:hover {
  background: var(--wb-fill);
}

.wf-main {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 3px;
  min-width: 0;
}

.wf-name {
  color: var(--wb-text);
  font-size: 13.5px;
  font-weight: 500;
}

.wf-desc {
  color: var(--wb-muted);
  font-size: 12px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.wf-meta {
  display: flex;
  flex: 0 0 auto;
  flex-direction: column;
  align-items: flex-end;
  gap: 3px;
  color: var(--wb-muted);
  font-size: 11.5px;
  white-space: nowrap;
}

.wf-run {
  display: inline-flex;
  align-items: center;
  gap: 5px;
}

.wf-run.is-idle {
  opacity: 0.7;
}

.wf-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
}

.wf-dot.is-running { background: var(--wb-info); }
.wf-dot.is-done { background: var(--wb-success); }
.wf-dot.is-fail { background: var(--wb-danger); }

.kb-stats {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 8px;
  margin-bottom: 10px;
}

.kb-stat {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 8px 10px;
  border-radius: var(--wb-radius);
  background: var(--wb-fill);
}

.kb-value {
  color: var(--wb-text);
  font-size: 17px;
  font-weight: 600;
  line-height: 1.2;
}

.kb-value.is-waiting { color: var(--wb-warning); }
.kb-value.is-running { color: var(--wb-info); }
.kb-value.is-fail { color: var(--wb-danger); }

.kb-label {
  color: var(--wb-muted);
  font-size: 11.5px;
}

.kb-item {
  display: flex;
  flex-direction: column;
  gap: 3px;
  padding: 8px;
  border-radius: var(--wb-radius);
  transition: background 0.15s;
}

.kb-name {
  color: var(--wb-text);
  font-size: 13px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.kb-meta {
  color: var(--wb-muted);
  font-size: 11.5px;
}

.service-row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 8px 0;
}

.service-row + .service-row {
  border-top: 1px solid var(--wb-border);
}

.service-label {
  color: var(--wb-text-2);
  font-size: 13px;
}

.service-value {
  color: var(--wb-text);
  font-size: 12.5px;
}

.service-action {
  color: var(--wb-brand);
  font-size: 12.5px;
}

@media (max-width: 900px) {
  .home-grid {
    grid-template-columns: 1fr;
  }
  .stat-row {
    grid-template-columns: repeat(2, 1fr);
  }
}

@media (max-width: 640px) {
  .wb-home-inner {
    padding: 20px 16px 40px;
  }
}

.creative-list { display: flex; flex-direction: column; gap: 6px; }
.creative-item {
  display: flex; align-items: center; justify-content: space-between;
  padding: 8px 10px; border: 1px solid var(--wb-border); border-radius: 8px;
  text-decoration: none; color: inherit; font-size: 12.5px;
}
.creative-item:hover { border-color: var(--wb-brand); }
.creative-status { font-size: 11.5px; padding: 1px 8px; border-radius: 999px; border: 1px solid var(--wb-border); color: var(--wb-text-2); }
.creative-status.is-done { color: var(--wb-success); }
.creative-status.is-fail { color: var(--wb-danger); }
.creative-status.is-running { color: var(--wb-info); }
.creative-status.is-idle { color: var(--wb-muted); }
.creative-time { color: var(--wb-muted); font-size: 11.5px; }
.creative-actions { display: flex; gap: 8px; margin-top: 10px; }
.creative-action {
  flex: 1; text-align: center; padding: 8px; border: 1px solid var(--wb-border);
  border-radius: 8px; text-decoration: none; color: var(--wb-text-2); font-size: 12.5px;
}
.creative-action:hover { border-color: var(--wb-brand); color: var(--wb-text); }
</style>
