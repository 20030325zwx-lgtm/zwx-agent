<template>
  <div class="wb-home">
    <div class="wb-home-inner">
        <header class="home-head">
          <div>
            <h1 class="home-greeting">{{ greeting }}，{{ userName }}</h1>
            <p class="home-date">{{ dateLabel }} · 今天想完成什么？</p>
          </div>
        </header>

        <WorkbenchComposer
          ref="composerRef"
          class="home-composer"
          :projects="projects"
          :model-label="modelLabel"
          :sending="sending"
          @start="handleStart"
        />

        <div class="home-examples">
          <button
            v-for="example in examples"
            :key="example"
            type="button"
            class="example-chip"
            @click="composerRef?.setText(example)"
          >{{ example }}</button>
        </div>

        <div class="home-grid">
          <section class="home-section">
            <div class="section-head">
              <h2>继续工作</h2>
              <router-link v-if="conversations.length" to="/super-agent" class="section-more">
                全部会话 <ChevronRight :size="14" />
              </router-link>
            </div>
            <p v-if="loading" class="section-hint">加载中…</p>
            <p v-else-if="!conversations.length" class="section-empty">
              <MessagesSquare :size="18" /> 还没有会话，从上方输入框开始第一个任务
            </p>
            <div v-else class="conversation-list">
              <router-link
                v-for="conversation in conversations"
                :key="conversation.id"
                class="conversation-card"
                :to="{ path: '/super-agent', query: { conversationId: conversation.id } }"
              >
                <div class="conversation-title">{{ conversationTitle(conversation) }}</div>
                <div class="conversation-meta">
                  <span>{{ formatTime(conversation.updatedAt || conversation.createdAt) }}</span>
                  <span v-if="conversation.projectName" class="conversation-project">{{ conversation.projectName }}</span>
                </div>
              </router-link>
            </div>
          </section>

          <section class="home-section">
            <div class="section-head">
              <h2>最近运行</h2>
              <router-link v-if="runs.length" to="/super-agent" class="section-more">
                运行历史 <ChevronRight :size="14" />
              </router-link>
            </div>
            <p v-if="loading" class="section-hint">加载中…</p>
            <p v-else-if="!runs.length" class="section-empty">
              <Activity :size="18" /> 暂无任务运行记录
            </p>
            <div v-else class="run-list">
              <router-link
                v-for="run in runs"
                :key="`${run.conversationId}:${run.id}`"
                class="run-card"
                :to="{ path: '/super-agent', query: { conversationId: run.conversationId, runId: run.id } }"
              >
                <span class="run-dot" :class="statusClass(run.status)" />
                <span class="run-title">{{ runTitle(run) }}</span>
                <span class="run-status">{{ statusLabel(run.status) }}</span>
              </router-link>
            </div>
          </section>
        </div>
      </div>
    </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import { Activity, ChevronRight, MessagesSquare } from 'lucide-vue-next'
import WorkbenchComposer from '../components/workbench/WorkbenchComposer.vue'
import { getCurrentUser } from '../api/auth'
import {
  createManusConversation,
  listManusConversations,
  listModelSettings,
  listProjects,
  listRecentManusRuns
} from '../api'

const router = useRouter()

const projects = ref([])
const conversations = ref([])
const runs = ref([])
const modelLabel = ref('')
const loading = ref(true)
const sending = ref(false)
const composerRef = ref(null)

const userName = computed(() => getCurrentUser()?.username || '')
const greeting = computed(() => {
  const hour = new Date().getHours()
  if (hour < 5) return '夜深了'
  if (hour < 12) return '早上好'
  if (hour < 18) return '下午好'
  return '晚上好'
})
const dateLabel = computed(() => {
  const now = new Date()
  const week = ['日', '一', '二', '三', '四', '五', '六'][now.getDay()]
  return `${now.getMonth() + 1}月${now.getDate()}日 星期${week}`
})

// 示例只填充输入框，不自动发起调用
const examples = [
  '把这份季度数据整理成一份带图表建议的分析报告',
  '调研一个技术选型，输出对比结论与推荐',
  '审查当前项目的安全问题并给出修复清单'
]

const STATUS_LABEL = {
  RUNNING: '进行中',
  COMPLETED: '已完成',
  FAILED: '失败',
  INTERRUPTED: '已中断',
  AWAITING_CONFIRMATION: '待确认'
}
const STATUS_CLASS = {
  RUNNING: 'is-running',
  COMPLETED: 'is-done',
  FAILED: 'is-failed'
}

function statusLabel(status) { return STATUS_LABEL[status] || '待处理' }
function statusClass(status) { return STATUS_CLASS[status] || 'is-waiting' }

function conversationTitle(conversation) {
  return conversation.title || conversation.lastMessage || '未命名会话'
}
function runTitle(run) {
  return run.input || run.goal || run.summary || '任务运行'
}
function formatTime(value) {
  if (!value) return ''
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return ''
  const diff = Date.now() - date.getTime()
  const minutes = Math.floor(diff / 60000)
  if (minutes < 1) return '刚刚'
  if (minutes < 60) return `${minutes} 分钟前`
  const hours = Math.floor(minutes / 60)
  if (hours < 24) return `${hours} 小时前`
  return `${date.getMonth() + 1}月${date.getDate()}日`
}

async function handleStart({ text, projectId: linkedProjectId }) {
  sending.value = true
  try {
    const conversation = await createManusConversation(linkedProjectId || null)
    try { sessionStorage.setItem('aster.workbench.draft', text) } catch (error) { /* ignore */ }
    router.push({ path: '/super-agent', query: { conversationId: conversation.id } })
  } catch (error) {
    console.error(error)
    message.error('创建会话失败，请确认服务可用')
  } finally {
    sending.value = false
  }
}

onMounted(async () => {
  const [projectResult, conversationResult, runResult, modelResult] = await Promise.allSettled([
    listProjects(),
    listManusConversations(),
    listRecentManusRuns(6),
    listModelSettings()
  ])
  projects.value = projectResult.status === 'fulfilled' ? projectResult.value || [] : []
  conversations.value = (conversationResult.status === 'fulfilled' ? conversationResult.value : []).slice(0, 6)
  runs.value = (runResult.status === 'fulfilled' ? runResult.value : []).slice(0, 6)
  if (modelResult.status === 'fulfilled') {
    const chat = (modelResult.value?.models || []).find(item => item.key === 'CHAT')
    modelLabel.value = chat?.effective?.modelName || ''
  }
  loading.value = false
  if (conversationResult.status === 'rejected' && runResult.status === 'rejected') {
    message.warning('工作台数据加载失败，请确认后端服务可用')
  }
})
</script>

<style scoped>
.wb-home {
  flex: 1;
  overflow-y: auto;
}

.wb-home-inner {
  max-width: 1040px;
  margin: 0 auto;
  padding: 40px 32px 64px;
  display: flex;
  flex-direction: column;
  gap: 20px;
}

.home-head {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
}

.home-greeting {
  font-size: 26px;
  font-weight: 650;
  letter-spacing: -0.02em;
  color: var(--wb-text);
}

.home-date {
  margin-top: 6px;
  font-size: 14px;
  color: var(--wb-text-2);
}

.home-composer {
  margin-top: 4px;
}

.home-examples {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.example-chip {
  padding: 6px 12px;
  border: 1px solid var(--wb-border);
  border-radius: 999px;
  background: var(--wb-surface);
  color: var(--wb-text-2);
  font-size: 12.5px;
  transition: color 0.15s, border-color 0.15s, background 0.15s;
}

.example-chip:hover {
  border-color: var(--wb-brand);
  color: var(--wb-brand);
  background: var(--wb-brand-soft);
}

.home-grid {
  display: grid;
  grid-template-columns: 3fr 2fr;
  gap: 16px;
  align-items: start;
  margin-top: 12px;
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
  margin-bottom: 12px;
}

.section-head h2 {
  font-size: 15px;
  font-weight: 600;
  color: var(--wb-text);
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
  align-items: center;
  gap: 8px;
  padding: 22px 4px;
  color: var(--wb-muted);
  font-size: 13px;
}

.conversation-list {
  display: flex;
  flex-direction: column;
}

.conversation-card {
  display: flex;
  flex-direction: column;
  gap: 5px;
  padding: 11px 10px;
  border-radius: var(--wb-radius);
  transition: background 0.15s;
}

.conversation-card:hover {
  background: var(--wb-fill);
}

.conversation-card + .conversation-card {
  margin-top: 2px;
}

.conversation-title {
  color: var(--wb-text);
  font-size: 13.5px;
  font-weight: 500;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.conversation-meta {
  display: flex;
  gap: 10px;
  color: var(--wb-muted);
  font-size: 12px;
}

.conversation-project {
  color: var(--wb-brand);
  background: var(--wb-brand-soft);
  padding: 0 8px;
  border-radius: 999px;
}

.run-list {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.run-card {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px;
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
  background: var(--wb-muted);
  flex: 0 0 8px;
}

.run-dot.is-running {
  background: var(--wb-info);
  box-shadow: 0 0 0 3px rgba(37, 144, 255, 0.18);
}

.run-dot.is-done {
  background: var(--wb-success);
}

.run-dot.is-failed {
  background: var(--wb-danger);
}

.run-dot.is-waiting {
  background: var(--wb-warning);
}

.run-title {
  flex: 1;
  min-width: 0;
  color: var(--wb-text-2);
  font-size: 13px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.run-status {
  color: var(--wb-muted);
  font-size: 12px;
  flex: 0 0 auto;
}

@media (max-width: 900px) {
  .home-grid {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 640px) {
  .wb-home-inner {
    padding: 24px 16px 48px;
  }
}
</style>
