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
      </div>

      <div class="home-grid">
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
            <div class="service-row">
              <span class="service-label">知识库</span>
              <router-link to="/knowledge-admin" class="service-action">管理 →</router-link>
            </div>
          </section>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { Activity, ChevronRight, FolderOpen, Plus } from 'lucide-vue-next'
import { message } from 'ant-design-vue'
import { listManusRunOverviews, listManusConversations, listModelSettings, listProjects } from '../api'

const projects = ref([])
const conversations = ref([])
const runs = ref([])
const modelLabel = ref('')
const loading = ref(true)

const stats = computed(() => {
  const count = status => runs.value.filter(run => status.includes(run.status)).length
  return {
    workspaces: projects.value.length,
    conversations: conversations.value.length,
    running: count(['RUNNING']),
    completed: count(['COMPLETED']),
    pending: count(['INTERRUPTED', 'FAILED'])
  }
})

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

onMounted(async () => {
  const [runResult, conversationResult, projectResult, modelResult] = await Promise.allSettled([
    listManusRunOverviews(30),
    listManusConversations(),
    listProjects(),
    listModelSettings()
  ])
  runs.value = runResult.status === 'fulfilled' ? runResult.value || [] : []
  conversations.value = conversationResult.status === 'fulfilled' ? conversationResult.value || [] : []
  projects.value = projectResult.status === 'fulfilled' ? projectResult.value || [] : []
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
  grid-template-columns: repeat(5, 1fr);
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
</style>
