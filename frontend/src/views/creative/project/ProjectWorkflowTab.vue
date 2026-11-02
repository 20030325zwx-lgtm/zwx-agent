<template>
  <div class="tab-scroll">
    <div class="tab-inner">
      <div class="cr-section-head">
        <h2>工作流运行</h2>
        <button type="button" class="cr-ghost" :disabled="loading" @click="load">
          <RefreshCw :size="14" /> 刷新
        </button>
      </div>
      <p class="cr-hint">绑定本项目的运行；媒体产物会进入本项目素材与任务记录，来源标记为「工作流」。</p>
      <p v-if="loading" class="cr-hint">加载中…</p>
      <p v-else-if="error" class="cr-error">{{ error }}</p>
      <p v-else-if="!runs.length" class="cr-empty">
        还没有绑定本项目的工作流运行。到
        <router-link to="/creative">创作首页</router-link>
        的「从工作流创建」发起，或打开
        <router-link to="/workflows">工作流画布</router-link>
        编辑流程。
      </p>
      <article v-for="run in runs" :key="run.id" class="wf-run-card">
        <header class="wf-run-head">
          <span class="cr-status" :class="statusMeta(run.status).tone">{{ statusLabel(run.status) }}</span>
          <strong class="wf-run-name">{{ run.workflowName }}</strong>
          <span class="wf-run-meta">v{{ run.definitionVersion }}</span>
          <span class="wf-run-meta">{{ formatTime(run.createdAt) }}</span>
          <button type="button" class="cr-ghost" @click="toggleDetail(run)">
            {{ details[run.id] ? '收起' : '查看详情' }}
          </button>
          <router-link class="cr-ghost" :to="`/workflows/${run.workflowKey}`">打开画布</router-link>
        </header>
        <p v-if="run.error" class="cr-error">{{ run.error }}</p>
        <template v-if="details[run.id]">
          <ol class="wf-run-steps">
            <li v-for="step in details[run.id].steps" :key="step.key" class="wf-step" :class="step.status.toLowerCase()">
              <span class="wf-step-dot" />
              <span class="wf-step-name">{{ step.name }}</span>
              <span class="wf-step-status">{{ stepLabel(step.status) }}</span>
              <span v-if="step.message" class="wf-step-msg">{{ step.message }}</span>
            </li>
          </ol>
          <div v-if="artifacts(details[run.id]).length" class="wf-run-artifacts">
            <figure v-for="artifact in artifacts(details[run.id])" :key="artifact.objectKey" class="wf-artifact">
              <a v-if="artifact.url && artifact.type === 'image'" :href="artifact.url" target="_blank" rel="noopener">
                <img :src="artifact.url" :alt="artifact.name" />
              </a>
              <a v-else-if="artifact.url" :href="artifact.url" target="_blank" rel="noopener">{{ artifact.name }}</a>
              <span v-else>{{ artifact.name }}</span>
            </figure>
          </div>
        </template>
      </article>
    </div>
  </div>
</template>

<script setup>
import { inject, onBeforeUnmount, onMounted, ref } from 'vue'
import { RefreshCw } from 'lucide-vue-next'
import { getCreativeAssetBlob, getWorkflowRun, listWorkflowRuns } from '../../../api'
import { formatTime } from '../shared'
import { PROJECT_CTX } from './composables'

const { projectId, showToast, router, route } = inject(PROJECT_CTX)

const runs = ref([])
const details = ref({})
const loading = ref(false)
const error = ref('')
let pollTimer = null

function statusMeta(status) {
  switch (status) {
    case 'SUCCEEDED': return { label: '已完成', tone: 'success' }
    case 'FAILED': return { label: '失败', tone: 'error' }
    case 'WAITING_EXTERNAL': return { label: '等待生成', tone: 'warn' }
    case 'RUNNING': return { label: '进行中', tone: 'warn' }
    default: return { label: status || '未知', tone: 'muted' }
  }
}
const statusLabel = status => statusMeta(status).label

function stepLabel(status) {
  switch (status) {
    case 'SUCCEEDED': return '完成'
    case 'RUNNING': return '进行中'
    case 'SKIPPED': return '已跳过'
    case 'FAILED': return '失败'
    default: return '待执行'
  }
}

function artifacts(detail) {
  return (detail.artifacts || []).filter(artifact => artifact.url || artifact.name)
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    runs.value = (await listWorkflowRuns({ projectId: projectId.value, limit: 20 })) || []
    // 从创作首页/快速生成跳转过来时自动展开那次运行
    const focusRunId = route.query.runId
    if (focusRunId && runs.value.some(run => run.id === focusRunId)) {
      await toggleDetail(runs.value.find(run => run.id === focusRunId))
      const query = { ...route.query }
      delete query.runId
      router.replace({ path: route.path, query })
    }
  } catch (exception) {
    error.value = exception.response?.data?.error || '工作流运行加载失败'
  } finally {
    loading.value = false
  }
  schedulePoll()
}

async function toggleDetail(run) {
  if (details.value[run.id]) {
    const next = { ...details.value }
    delete next[run.id]
    details.value = next
    return
  }
  try {
    const detail = await getWorkflowRun(run.id)
    for (const artifact of detail.artifacts || []) {
      if (artifact.url && artifact.url.startsWith('asset:')) {
        try {
          const blob = await getCreativeAssetBlob(artifact.url.slice(6))
          artifact.url = URL.createObjectURL(blob)
        } catch (exception) { artifact.url = '' }
      }
    }
    details.value = { ...details.value, [run.id]: detail }
  } catch (exception) {
    showToast(exception.response?.data?.error || '运行详情加载失败', 'error')
  }
}

function schedulePoll() {
  window.clearTimeout(pollTimer)
  const active = runs.value.some(run => ['RUNNING', 'WAITING_EXTERNAL'].includes(run.status))
  if (!active) return
  pollTimer = window.setTimeout(async () => {
    if (document.visibilityState !== 'hidden') {
      try {
        runs.value = (await listWorkflowRuns({ projectId: projectId.value, limit: 20 })) || []
        for (const run of runs.value) {
          if (details.value[run.id] && !['RUNNING', 'WAITING_EXTERNAL'].includes(run.status)) {
            try {
              details.value = { ...details.value, [run.id]: await getWorkflowRun(run.id) }
            } catch (exception) { /* 保持旧详情，下一次轮询重试 */ }
          }
        }
      } catch (exception) { /* 下一次轮询重试 */ }
    }
    schedulePoll()
  }, 3000)
}

onMounted(load)
onBeforeUnmount(() => window.clearTimeout(pollTimer))
</script>

<style scoped src="../creative.css"></style>

<style scoped>
.wf-run-card {
  border: 1px solid var(--wb-border);
  border-radius: var(--wb-radius);
  background: var(--wb-surface);
  padding: 12px 14px;
  margin-bottom: 10px;
}
.wf-run-head {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  font-size: var(--wb-fs-compact);
}
.wf-run-name { font-size: var(--wb-fs-compact); }
.wf-run-meta { color: var(--wb-muted); font-size: var(--wb-fs-caption); }
.wf-run-head .cr-ghost { margin-left: auto; }
.wf-run-steps {
  list-style: none;
  margin: 10px 0 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 6px;
}
.wf-step { display: flex; align-items: center; gap: 8px; font-size: var(--wb-fs-caption); color: var(--wb-text-2); }
.wf-step-dot { width: 8px; height: 8px; border-radius: 999px; background: var(--wb-border); flex-shrink: 0; }
.wf-step.succeeded .wf-step-dot { background: var(--wb-success); }
.wf-step.running .wf-step-dot { background: var(--wb-brand); }
.wf-step.failed .wf-step-dot { background: var(--wb-danger); }
.wf-step.skipped .wf-step-dot { background: var(--wb-muted); }
.wf-step-name { font-weight: 550; color: var(--wb-text); }
.wf-step-status { color: var(--wb-muted); font-size: var(--wb-fs-caption); }
.wf-step-msg { min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; color: var(--wb-muted); }
.wf-run-artifacts { display: flex; flex-wrap: wrap; gap: 10px; margin-top: 10px; }
.wf-artifact img {
  max-width: 180px;
  max-height: 180px;
  border-radius: var(--wb-radius-sm);
  border: 1px solid var(--wb-border);
  display: block;
}
.wf-artifact a { color: var(--wb-brand); font-size: var(--wb-fs-caption); }
</style>
