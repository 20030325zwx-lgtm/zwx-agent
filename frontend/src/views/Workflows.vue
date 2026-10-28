<template>
  <div class="wf-page">
    <div class="wf-inner">
      <header class="wf-head">
        <div>
          <h1>工作流</h1>
          <p>把多步任务固化为可复用、可观测的自动化流程</p>
        </div>
      </header>

      <div v-if="!definitions.length && !loading" class="wf-empty">暂无可用工作流</div>

      <section v-for="definition in definitions" :key="definition.key" class="wf-section">
        <div class="wf-def">
          <div class="wf-def-head">
            <div>
              <h2>{{ definition.name }}</h2>
              <p>{{ definition.description }}</p>
            </div>
            <span class="wf-version">v{{ definition.version }}</span>
          </div>
          <div class="wf-steps">
            <span v-for="(step, index) in definition.steps" :key="step.key" class="wf-step">
              <span class="step-index">{{ index + 1 }}</span> {{ step.name }}
            </span>
          </div>
          <div class="wf-start">
            <input
              v-model.trim="keyword"
              class="wf-input"
              :placeholder="`输入商品关键词，例如：蓝牙耳机`"
              maxlength="64"
              @keydown.enter="start(definition.key)"
            />
            <button type="button" class="wf-run" :disabled="starting || !keyword" @click="start(definition.key)">
              {{ starting ? '运行中…' : '运行工作流' }}
            </button>
          </div>
        </div>
      </section>

      <section class="wf-section">
        <div class="section-head">
          <h2>运行记录</h2>
          <button v-if="runs.length" type="button" class="refresh" @click="refresh">{{ pollLabel }}</button>
        </div>
        <p v-if="loading" class="section-hint">加载中…</p>
        <p v-else-if="!runs.length" class="section-hint">还没有运行记录，从上方发起第一次运行</p>
        <div v-else class="run-list">
          <button
            v-for="run in runs"
            :key="run.id"
            type="button"
            class="run-item"
            :class="{ selected: run.id === selectedId }"
            @click="selectRun(run.id)"
          >
            <span class="status-dot" :class="statusClass(run.status)" />
            <span class="run-main">
              <span class="run-title">{{ run.input?.keyword || run.workflowName }}</span>
              <span class="run-meta">{{ run.workflowName }} · {{ formatTime(run.createdAt) }}</span>
            </span>
            <span class="run-status" :class="statusClass(run.status)">{{ statusLabel(run.status) }}</span>
          </button>
        </div>
      </section>

      <section v-if="detail" class="wf-section">
        <div class="section-head">
          <h2>运行详情</h2>
          <span class="run-status big" :class="statusClass(detail.status)">{{ statusLabel(detail.status) }}</span>
        </div>
        <div class="detail-steps">
          <div v-for="step in detail.steps" :key="step.key" class="detail-step">
            <span class="status-dot" :class="stepClass(step.status)" />
            <span class="step-main">
              <span class="step-name">{{ step.name }}</span>
              <span class="step-message">{{ step.message || statusLabel(step.status) }}</span>
            </span>
            <span class="step-status">{{ stepStatusText(step.status) }}</span>
          </div>
        </div>
        <p v-if="detail.error" class="detail-error">{{ detail.error }}</p>
        <div v-for="artifact in detail.artifacts" :key="artifact.name" class="artifact">
          <img v-if="artifact.url" :src="artifact.url" alt="工作流产物" class="artifact-image" />
        </div>
      </section>
    </div>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { message } from 'ant-design-vue'
import { getWorkflowRun, listWorkflows, startWorkflow } from '../api'

const definitions = ref([])
const runs = ref([])
const detail = ref(null)
const selectedId = ref(null)
const keyword = ref('')
const loading = ref(true)
const starting = ref(false)
let pollTimer = null

const pollLabel = computed(() => (runs.value.some(run => run.status === 'RUNNING') ? '自动刷新中' : '刷新'))

function statusLabel(status) {
  return { RUNNING: '运行中', SUCCEEDED: '成功', FAILED: '失败' }[status] || status
}
function statusClass(status) {
  return { RUNNING: 'is-running', SUCCEEDED: 'is-done', FAILED: 'is-failed' }[status] || ''
}
function stepClass(status) {
  return { RUNNING: 'is-running', SUCCEEDED: 'is-done', SKIPPED: 'is-skipped', FAILED: 'is-failed' }[status] || 'is-pending'
}
function stepStatusText(status) {
  return { PENDING: '待执行', RUNNING: '执行中', SUCCEEDED: '完成', SKIPPED: '已跳过', FAILED: '失败' }[status] || status
}
function formatTime(value) {
  if (!value) return ''
  const date = new Date(value)
  const minutes = Math.floor((Date.now() - date.getTime()) / 60000)
  if (minutes < 1) return '刚刚'
  if (minutes < 60) return `${minutes} 分钟前`
  return `${date.getMonth() + 1}月${date.getDate()}日 ${String(date.getHours()).padStart(2, '0')}:${String(date.getMinutes()).padStart(2, '0')}`
}

async function refresh() {
  try {
    const data = await listWorkflows(20)
    definitions.value = data.definitions || []
    runs.value = data.runs || []
    loading.value = false
    schedulePoll()
  } catch (error) {
    loading.value = false
    message.error(error?.response?.data?.error || '工作流数据加载失败')
  }
}

function schedulePoll() {
  if (pollTimer) clearTimeout(pollTimer)
  if (!runs.value.some(run => run.status === 'RUNNING')) return
  pollTimer = setTimeout(async () => {
    try {
      const data = await listWorkflows(20)
      runs.value = data.runs || []
      if (selectedId.value) await loadDetail(selectedId.value, { silent: true })
    } catch (error) { /* 下轮重试 */ }
    schedulePoll()
  }, 2500)
}

async function loadDetail(runId, { silent } = {}) {
  try {
    detail.value = await getWorkflowRun(runId)
  } catch (error) {
    if (!silent) message.error(error?.response?.data?.error || '运行详情加载失败')
  }
}

async function selectRun(runId) {
  selectedId.value = runId
  await loadDetail(runId)
}

async function start(workflowKey) {
  if (!keyword.value || starting.value) return
  starting.value = true
  try {
    const run = await startWorkflow(workflowKey, keyword.value)
    selectedId.value = run.id
    detail.value = run
    await refresh()
    message.success('工作流已开始运行')
  } catch (error) {
    message.error(error?.response?.data?.error || '发起运行失败')
  } finally {
    starting.value = false
  }
}

onMounted(refresh)
onBeforeUnmount(() => { if (pollTimer) clearTimeout(pollTimer) })
</script>

<style scoped>
.wf-page {
  flex: 1;
  overflow-y: auto;
}

.wf-inner {
  max-width: 1040px;
  margin: 0 auto;
  padding: 28px 32px 56px;
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.wf-head h1 {
  font-size: 22px;
  font-weight: 650;
  letter-spacing: -0.02em;
  color: var(--wb-text);
}

.wf-head p {
  margin-top: 4px;
  font-size: 13px;
  color: var(--wb-text-2);
}

.wf-empty,
.section-hint {
  padding: 18px 4px;
  color: var(--wb-muted);
  font-size: 13px;
}

.wf-section {
  padding: 18px 20px;
  border: 1px solid var(--wb-border);
  border-radius: var(--wb-radius-lg);
  background: var(--wb-surface);
}

.wf-def-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
}

.wf-def-head h2 {
  font-size: 16px;
  font-weight: 600;
  color: var(--wb-text);
}

.wf-def-head p {
  margin-top: 4px;
  font-size: 12.5px;
  color: var(--wb-text-2);
}

.wf-version {
  padding: 2px 10px;
  border-radius: 999px;
  background: var(--wb-fill);
  color: var(--wb-text-2);
  font-size: 12px;
}

.wf-steps {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 14px;
}

.wf-step {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  padding: 6px 12px;
  border: 1px solid var(--wb-border);
  border-radius: 999px;
  color: var(--wb-text-2);
  font-size: 12.5px;
}

.step-index {
  display: grid;
  place-items: center;
  width: 18px;
  height: 18px;
  border-radius: 50%;
  background: var(--wb-brand-soft);
  color: var(--wb-brand);
  font-size: 11px;
  font-weight: 600;
}

.wf-start {
  display: flex;
  gap: 10px;
  margin-top: 16px;
}

.wf-input {
  flex: 1;
  padding: 9px 13px;
  border: 1px solid var(--wb-border);
  border-radius: 10px;
  background: var(--wb-surface-2);
  color: var(--wb-text);
  font-size: 13px;
  outline: none;
  transition: border-color 0.15s, box-shadow 0.15s;
}

.wf-input:focus {
  border-color: var(--wb-brand);
  box-shadow: 0 0 0 3px var(--wb-brand-soft);
}

.wf-run {
  padding: 9px 20px;
  border: 0;
  border-radius: 10px;
  background: var(--wb-brand);
  color: var(--wb-brand-contrast);
  font-size: 13px;
  font-weight: 500;
  transition: background 0.15s;
}

.wf-run:hover:not(:disabled) {
  background: var(--wb-brand-hover);
}

.wf-run:disabled {
  background: var(--wb-fill);
  color: var(--wb-muted);
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

.refresh {
  border: 0;
  background: transparent;
  color: var(--wb-muted);
  font-size: 12.5px;
}

.run-list {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.run-item {
  display: flex;
  align-items: center;
  gap: 11px;
  padding: 10px;
  border: 0;
  border-radius: var(--wb-radius);
  background: transparent;
  text-align: left;
  transition: background 0.15s;
}

.run-item:hover,
.run-item.selected {
  background: var(--wb-fill);
}

.status-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  flex: 0 0 8px;
  background: var(--wb-border-strong);
}

.status-dot.is-running {
  background: var(--wb-info);
  box-shadow: 0 0 0 3px rgba(37, 144, 255, 0.18);
}

.status-dot.is-done { background: var(--wb-success); }
.status-dot.is-failed { background: var(--wb-danger); }
.status-dot.is-skipped { background: var(--wb-muted); }
.status-dot.is-pending { background: var(--wb-border-strong); }

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
}

.run-meta {
  color: var(--wb-muted);
  font-size: 12px;
}

.run-status {
  flex: 0 0 auto;
  color: var(--wb-muted);
  font-size: 12px;
}

.run-status.is-running { color: var(--wb-info); }
.run-status.is-done { color: var(--wb-success); }
.run-status.is-failed { color: var(--wb-danger); }

.run-status.big {
  padding: 3px 12px;
  border-radius: 999px;
  background: var(--wb-fill);
  font-weight: 550;
}

.detail-steps {
  display: flex;
  flex-direction: column;
}

.detail-step {
  display: flex;
  align-items: center;
  gap: 11px;
  padding: 10px 4px;
}

.detail-step + .detail-step {
  border-top: 1px solid var(--wb-border);
}

.step-main {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 3px;
  min-width: 0;
}

.step-name {
  color: var(--wb-text);
  font-size: 13px;
  font-weight: 500;
}

.step-message {
  color: var(--wb-muted);
  font-size: 12px;
}

.step-status {
  color: var(--wb-muted);
  font-size: 12px;
}

.detail-error {
  margin-top: 10px;
  padding: 10px 12px;
  border-radius: var(--wb-radius);
  background: rgba(220, 47, 76, 0.08);
  color: var(--wb-danger);
  font-size: 12.5px;
}

.artifact {
  margin-top: 14px;
  text-align: center;
}

.artifact-image {
  max-width: 380px;
  width: 100%;
  border: 1px solid var(--wb-border);
  border-radius: var(--wb-radius);
  box-shadow: var(--wb-shadow-md);
}
</style>
