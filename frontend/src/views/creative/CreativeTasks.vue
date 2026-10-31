<template>
  <div class="cr-page">
    <div class="cr-scroll">
      <div class="cr-inner">
        <header class="cr-head">
          <div>
            <p class="cr-kicker">AI 创作</p>
            <h1 class="cr-title">任务记录</h1>
            <p class="cr-sub">全部创作任务（当前开放图片生成）。状态来自服务端，刷新或重启后可继续查看。</p>
          </div>
          <router-link to="/creative" class="cr-secondary">返回创作</router-link>
        </header>

        <div class="task-filters">
          <label class="filter-field">
            <span>状态</span>
            <select v-model="status" class="cr-select">
              <option value="">全部</option>
              <option v-for="key in statusKeys" :key="key" :value="key">{{ statusMeta(key).label }}</option>
            </select>
          </label>
          <button type="button" class="cr-secondary" :disabled="loading" @click="reload">
            {{ loading ? '刷新中…' : '刷新' }}
          </button>
          <span class="cr-field-help">共 {{ filtered.length }} 条</span>
        </div>

        <p v-if="error" class="cr-error">{{ error }}</p>
        <p v-if="!loading && !jobs.length" class="cr-empty">还没有任务。去创作台发起第一次创作。</p>

        <table v-else class="task-table">
          <thead>
            <tr><th>状态</th><th>提示词</th><th>项目</th><th>创建时间</th><th>更新时间</th><th>操作</th></tr>
          </thead>
          <tbody>
            <tr v-for="job in filtered" :key="job.jobId">
              <td><span class="cr-status" :class="statusMeta(job.status).tone">{{ statusMeta(job.status).label }}</span></td>
              <td class="task-prompt">{{ job.prompt || '—' }}</td>
              <td><router-link class="task-project" :to="`/creative/projects/${job.projectId}`">打开项目</router-link></td>
              <td>{{ formatTime(job.createdAt) }}</td>
              <td>{{ formatTime(job.updatedAt) }}</td>
              <td><button type="button" class="cr-ghost" @click="openDetail(job.jobId)">详情</button></td>
            </tr>
          </tbody>
        </table>

        <div v-if="nextCursor" class="load-more">
          <button type="button" class="cr-secondary" :disabled="loading" @click="loadMore">加载更多</button>
        </div>
      </div>
    </div>

    <div v-if="detail" class="detail-backdrop" @click.self="closeDetail">
      <aside class="detail-panel" role="dialog" aria-modal="true" aria-label="任务详情">
        <header class="detail-head">
          <div>
            <h2>任务详情</h2>
            <p class="cr-field-help">{{ detail.jobId }}</p>
          </div>
          <button type="button" class="cr-ghost" @click="closeDetail">关闭</button>
        </header>
        <dl class="detail-list">
          <div><dt>状态</dt><dd><span class="cr-status" :class="statusMeta(detail.status).tone">{{ statusMeta(detail.status).label }}</span></dd></div>
          <div><dt>提示词</dt><dd class="detail-prompt">{{ detail.prompt || '—' }}</dd></div>
          <div><dt>模型</dt><dd>{{ detail.modelName || '—' }}</dd></div>
          <div><dt>尺寸</dt><dd>{{ detail.parameters?.size || '—' }}</dd></div>
          <div><dt>创建时间</dt><dd>{{ formatTime(detail.createdAt) }}</dd></div>
          <div v-if="detail.providerTaskId"><dt>供应商任务</dt><dd class="mono">{{ detail.providerTaskId }}</dd></div>
          <div v-if="detail.error"><dt>错误</dt><dd class="cr-error">{{ detail.error }}</dd></div>
        </dl>
        <div class="detail-actions">
          <HoldToConfirm v-if="['QUEUED','RUNNING'].includes(detail.status)" label="取消任务"
                         :disabled="busy" @confirm="cancel" />
          <button v-if="detail.status === 'INGEST_FAILED'" type="button" class="cr-primary"
                  :disabled="busy" @click="resume">
            <Spinner v-if="busy" /> 重新保存
          </button>
          <HoldToConfirm v-if="detail.status === 'RECONCILING'" label="确认后重试（可能二次计费）"
                         :disabled="busy" @confirm="retry" />
          <button v-else-if="['FAILED','CANCELLED'].includes(detail.status)" type="button"
                  class="cr-secondary" :disabled="busy" @click="retry">重试</button>
          <button type="button" class="cr-ghost" @click="goProject">进入项目</button>
        </div>
      </aside>
    </div>

    <p v-if="toast.text" class="cr-toast" :class="toast.tone">{{ toast.text }}</p>
  </div>
</template>

<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  cancelCreativeJob, getCreativeJob, listCreativeJobs, resumeCreativeJobIngest, retryCreativeJob
} from '../../api'
import { formatTime, statusMeta } from './shared'
import HoldToConfirm from '../../components/ui/HoldToConfirm.vue'
import Spinner from '../../components/ui/Spinner.vue'

const route = useRoute()
const router = useRouter()
const jobs = ref([])
const status = ref('')
const nextCursor = ref(null)
const loading = ref(false)
const error = ref('')
const detail = ref(null)
const busy = ref(false)
const toast = ref({ text: '', tone: '' })

const statusKeys = ['QUEUED', 'SUBMITTING', 'RUNNING', 'CANCEL_REQUESTED', 'RECONCILING', 'FETCHING', 'INGEST_FAILED', 'SUCCEEDED', 'FAILED', 'CANCELLED']
const filtered = computed(() => status.value ? jobs.value.filter(job => job.status === status.value) : jobs.value)

function showToast(text, tone = '') {
  toast.value = { text, tone }
  window.clearTimeout(showToast.timer)
  showToast.timer = window.setTimeout(() => { toast.value = { text: '', tone: '' } }, 3200)
}

async function load(cursor = null, append = false) {
  loading.value = true
  error.value = ''
  try {
    const data = await listCreativeJobs({ limit: 30, cursor: cursor || undefined })
    jobs.value = append ? [...jobs.value, ...(data.items || [])] : (data.items || [])
    nextCursor.value = data.nextCursor
  } catch (exception) {
    error.value = exception.response?.data?.error || '任务加载失败，请确认后端服务可用'
  } finally {
    loading.value = false
  }
}

function reload() { load(null, false) }
function loadMore() { if (nextCursor.value) load(nextCursor.value, true) }

async function openDetail(jobId) {
  try {
    detail.value = await getCreativeJob(jobId)
    if (route.params.jobId !== jobId) router.replace(`/creative/tasks/${jobId}`)
  } catch (exception) {
    showToast(exception.response?.data?.error || '任务详情加载失败', 'error')
  }
}

function closeDetail() {
  detail.value = null
  if (route.params.jobId) router.replace('/creative/tasks')
}

async function cancel() {
  // 长按确认已在前端按钮上完成（取消后供应商可能仍完成生成并产生费用）
  busy.value = true
  try {
    detail.value = await cancelCreativeJob(detail.value.jobId)
    await reload()
    showToast('已提交取消请求', 'success')
  } catch (exception) {
    showToast(exception.response?.data?.error || '取消失败', 'error')
  } finally {
    busy.value = false
  }
}

async function resume() {
  busy.value = true
  try {
    detail.value = await resumeCreativeJobIngest(detail.value.jobId)
    await reload()
    showToast('已请求重新保存，不会重新生成', 'success')
  } catch (exception) {
    showToast(exception.response?.data?.error || '恢复失败', 'error')
  } finally {
    busy.value = false
  }
}

async function retry() {
  // RECONCILING 的长按确认已在前端按钮上完成（结果未确认，重试可能二次计费）
  busy.value = true
  try {
    await retryCreativeJob(detail.value.jobId,
      { acknowledgePossibleDuplicateCharge: detail.value.status === 'RECONCILING' })
    await reload()
    showToast('已创建新的生成任务', 'success')
  } catch (exception) {
    showToast(exception.response?.data?.error || '重试失败', 'error')
  } finally {
    busy.value = false
  }
}

function goProject() {
  if (detail.value?.projectId) router.push(`/creative/projects/${detail.value.projectId}`)
}

watch(() => route.params.jobId, jobId => {
  if (jobId) openDetail(jobId)
  else detail.value = null
})

onMounted(async () => {
  await load()
  if (route.params.jobId) openDetail(route.params.jobId)
})
</script>

<style scoped src="./creative.css"></style>

<style scoped>
.task-filters {
  display: flex;
  align-items: flex-end;
  gap: 12px;
  flex-wrap: wrap;
}
.filter-field { display: flex; flex-direction: column; gap: 5px; font-size: 12px; color: var(--wb-muted); }
.filter-field .cr-select { min-width: 150px; }

.task-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 12.5px;
  background: var(--wb-surface);
  border: 1px solid var(--wb-border);
  border-radius: var(--wb-radius-lg);
  overflow: hidden;
}
.task-table th, .task-table td { padding: 10px 12px; text-align: left; border-bottom: 1px solid var(--wb-border); }
.task-table th { color: var(--wb-muted); font-weight: 500; background: var(--wb-fill); }
.task-table tr:last-child td { border-bottom: none; }
.task-prompt { max-width: 380px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.task-project { color: var(--wb-brand); text-decoration: none; }
.load-more { display: flex; justify-content: center; padding: 8px 0 24px; }

.detail-backdrop {
  position: fixed;
  inset: 0;
  z-index: 45;
  background: rgb(0 0 0 / 32%);
  display: flex;
  justify-content: flex-end;
}
.detail-panel {
  width: min(420px, 100%);
  height: 100%;
  overflow-y: auto;
  background: var(--wb-surface);
  border-left: 1px solid var(--wb-border);
  padding: 18px;
  display: flex;
  flex-direction: column;
  gap: 16px;
  box-shadow: -16px 0 48px rgb(0 0 0 / 18%);
}
.detail-head { display: flex; align-items: flex-start; justify-content: space-between; }
.detail-head h2 { margin: 0; font-size: 16px; }
.detail-list { margin: 0; display: flex; flex-direction: column; gap: 10px; font-size: 12.5px; }
.detail-list > div { display: grid; grid-template-columns: 88px 1fr; gap: 10px; }
.detail-list dt { color: var(--wb-muted); }
.detail-list dd { margin: 0; word-break: break-word; }
.detail-prompt { line-height: 1.6; }
.mono { font-family: var(--wb-font-mono); font-size: 11.5px; }
.detail-actions { display: flex; flex-wrap: wrap; gap: 8px; }
</style>
