<template>
  <div class="wf-page">
    <div class="wf-inner">
      <header class="wf-head">
        <div>
          <h1>工作流</h1>
          <p>已制定的工作流：运行、查看记录或进入画布编辑</p>
        </div>
        <button type="button" class="new-btn" @click="openCreate">
          <Plus :size="15" /> 新建工作流
        </button>
      </header>

      <p v-if="loading" class="section-hint">加载中…</p>
      <div v-else class="wf-grid">
        <div v-for="definition in definitions" :key="definition.key" class="wf-card">
          <div class="card-head">
            <router-link :to="`/workflows/${definition.key}`" class="card-title">{{ definition.name }}</router-link>
            <div class="card-tools">
              <span class="wf-version">v{{ definition.version }}</span>
              <button type="button" class="menu-btn" :aria-label="`${definition.name} 操作`" @click.stop="toggleMenu(definition.key)">
                <MoreHorizontal :size="16" />
              </button>
              <div v-if="menuOpenKey === definition.key" class="card-menu" @click.stop>
                <button type="button" @click="openRun(definition)"><Play :size="14" /> 运行</button>
                <button type="button" @click="openHistory(definition)"><History :size="14" /> 运行记录</button>
                <button type="button" class="danger" @click="openDelete(definition)"><Trash2 :size="14" /> 删除</button>
              </div>
            </div>
          </div>
          <router-link :to="`/workflows/${definition.key}`" class="card-body">
            <p class="card-desc">{{ definition.description || '暂无描述' }}</p>
            <div class="card-foot">
              <span class="node-chips">
                <span v-for="step in definition.steps.slice(0, 4)" :key="step.key" class="node-chip">{{ step.name }}</span>
                <span v-if="definition.steps.length > 4" class="node-chip">+{{ definition.steps.length - 4 }}</span>
              </span>
              <span class="card-count">{{ definition.steps.length }} 节点</span>
            </div>
          </router-link>
          <div v-if="latestRun(definition.key)" class="card-run">
            <span class="status-dot" :class="statusClass(latestRun(definition.key).status)" />
            <span class="card-run-text">最近运行：{{ statusLabel(latestRun(definition.key).status) }} · {{ formatTime(latestRun(definition.key).createdAt) }}</span>
          </div>
        </div>
        <button type="button" class="wf-card add-card" @click="openCreate">
          <Plus :size="20" />
          <span>新建工作流</span>
        </button>
      </div>
    </div>

    <!-- 新建工作流 -->
    <div v-if="createOpen" class="dialog-backdrop" @click.self="createOpen = false">
      <div class="dialog" role="dialog" aria-modal="true">
        <h2>新建工作流</h2>
        <label class="dialog-row">
          <span>名称</span>
          <input v-model.trim="form.name" class="dialog-input" maxlength="64" placeholder="如：每周调研汇总" />
        </label>
        <label class="dialog-row">
          <span>标识</span>
          <input v-model.trim="form.key" class="dialog-input mono" maxlength="49" placeholder="小写字母/数字/连字符" />
        </label>
        <label class="dialog-row">
          <span>描述</span>
          <input v-model.trim="form.description" class="dialog-input" maxlength="200" placeholder="这个工作流做什么" />
        </label>
        <p v-if="formError" class="dialog-error">{{ formError }}</p>
        <div class="dialog-actions">
          <button type="button" class="ghost-btn" @click="createOpen = false">取消</button>
          <button type="button" class="primary-btn" :disabled="creating" @click="createWorkflowAction">
            {{ creating ? '创建中…' : '创建工作流' }}
          </button>
        </div>
      </div>
    </div>

    <!-- 运行 -->
    <div v-if="runDialog.open" class="dialog-backdrop" @click.self="closeRun">
      <div class="dialog wide" role="dialog" aria-modal="true">
        <h2>运行：{{ runDialog.definition?.name }}</h2>
        <div class="run-row">
          <input
            v-model.trim="runDialog.keyword"
            class="dialog-input"
            placeholder="运行输入（关键词），如：蓝牙耳机"
            maxlength="64"
            :disabled="runDialog.running"
            @keydown.enter="startRun"
          />
          <button type="button" class="primary-btn" :disabled="runDialog.running || !runDialog.keyword" @click="startRun">
            {{ runDialog.running ? '运行中…' : '运行' }}
          </button>
        </div>
        <div v-if="runDialog.run" class="run-state">
          <div class="run-state-head">
            <span class="status-dot" :class="statusClass(runDialog.run.status)" />
            <strong>{{ statusLabel(runDialog.run.status) }}</strong>
            <span class="run-state-meta">v{{ runDialog.run.definitionVersion }} · {{ formatTime(runDialog.run.createdAt) }}</span>
            <router-link :to="`/workflows/${runDialog.definition.key}`" class="link" @click="closeRun">在画布中查看 →</router-link>
          </div>
          <div class="mini-steps">
            <div v-for="step in runDialog.run.steps" :key="step.key" class="mini-step">
              <span class="status-dot" :class="stepClass(step.status)" />
              <span class="mini-name">{{ step.name }}</span>
              <span class="mini-meta">{{ step.message || stepStatusText(step.status) }}</span>
            </div>
          </div>
          <p v-if="runDialog.run.error" class="dialog-error">{{ runDialog.run.error }}</p>
          <div v-for="artifact in runDialog.run.artifacts" :key="artifact.name" class="run-artifact">
            <img v-if="artifact.url" :src="artifact.url" alt="工作流产物" class="artifact-image" />
          </div>
        </div>
        <p v-else-if="!runDialog.running" class="dialog-hint">输入运行参数后开始执行；运行期间会实时刷新步骤状态。</p>
        <div class="dialog-actions">
          <button type="button" class="ghost-btn" @click="closeRun">关闭</button>
        </div>
      </div>
    </div>

    <!-- 运行记录 -->
    <div v-if="historyDialog.open" class="dialog-backdrop" @click.self="closeHistory">
      <div class="dialog wide" role="dialog" aria-modal="true">
        <h2>运行记录：{{ historyDialog.definition?.name }}</h2>
        <p v-if="!historyDialog.runs.length" class="dialog-hint">该工作流还没有运行记录</p>
        <div v-else class="history-list">
          <div v-for="run in historyDialog.runs" :key="run.id" class="history-item">
            <button type="button" class="history-row" @click="toggleHistoryRun(run.id)">
              <span class="status-dot" :class="statusClass(run.status)" />
              <span class="history-main">
                <span class="history-title">{{ run.input?.keyword || '运行' }}</span>
                <span class="history-meta">v{{ run.definitionVersion }} · {{ formatTime(run.createdAt) }}</span>
              </span>
              <span class="run-status" :class="statusClass(run.status)">{{ statusLabel(run.status) }}</span>
            </button>
            <div v-if="historyDialog.expandedId === run.id" class="history-detail">
              <p v-if="historyDialog.loadingId === run.id" class="dialog-hint">加载中…</p>
              <template v-else-if="historyDialog.detail">
                <div v-for="step in historyDialog.detail.steps" :key="step.key" class="mini-step">
                  <span class="status-dot" :class="stepClass(step.status)" />
                  <span class="mini-name">{{ step.name }}</span>
                  <span class="mini-meta">{{ step.message || stepStatusText(step.status) }}</span>
                </div>
                <p v-if="historyDialog.detail.error" class="dialog-error">{{ historyDialog.detail.error }}</p>
                <div v-for="artifact in historyDialog.detail.artifacts" :key="artifact.name" class="run-artifact">
                  <img v-if="artifact.url" :src="artifact.url" alt="工作流产物" class="artifact-image" />
                </div>
              </template>
            </div>
          </div>
        </div>
        <div class="dialog-actions">
          <button type="button" class="ghost-btn" @click="closeHistory">关闭</button>
        </div>
      </div>
    </div>

    <!-- 删除确认 -->
    <div v-if="deleteDialog.open" class="dialog-backdrop" @click.self="deleteDialog.open = false">
      <div class="dialog" role="dialog" aria-modal="true">
        <h2>删除工作流</h2>
        <p class="dialog-hint">
          将删除「{{ deleteDialog.definition?.name }}」的全部定义版本与运行记录，此操作不可恢复。
        </p>
        <p v-if="deleteDialog.error" class="dialog-error">{{ deleteDialog.error }}</p>
        <div class="dialog-actions">
          <button type="button" class="ghost-btn" @click="deleteDialog.open = false">取消</button>
          <button type="button" class="primary-btn danger" :disabled="deleteDialog.busy" @click="confirmDelete">
            {{ deleteDialog.busy ? '删除中…' : '确认删除' }}
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import { History, MoreHorizontal, Play, Plus, Trash2 } from 'lucide-vue-next'
import { createWorkflow, deleteWorkflow, getWorkflowRun, listWorkflows, startWorkflow } from '../api'

const router = useRouter()
const definitions = ref([])
const runs = ref([])
const loading = ref(true)
const createOpen = ref(false)
const creating = ref(false)
const formError = ref('')
const form = reactive({ name: '', key: '', description: '' })
const menuOpenKey = ref('')
const runDialog = reactive({ open: false, definition: null, keyword: '', running: false, run: null })
const historyDialog = reactive({ open: false, definition: null, runs: [], expandedId: '', detail: null, loadingId: '' })
const deleteDialog = reactive({ open: false, definition: null, busy: false, error: '' })
let pollTimer = null
let menuCloser = null

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
function latestRun(workflowKey) {
  return runs.value.find(run => run.workflowKey === workflowKey) || null
}

function toggleMenu(workflowKey) {
  menuOpenKey.value = menuOpenKey.value === workflowKey ? '' : workflowKey
  if (!menuCloser) {
    menuCloser = event => {
      if (!event.target.closest('.card-tools')) menuOpenKey.value = ''
    }
    document.addEventListener('click', menuCloser)
  }
}

function openCreate() {
  formError.value = ''
  form.name = ''
  form.key = `wf-${Date.now().toString(36)}`
  form.description = ''
  createOpen.value = true
}

async function createWorkflowAction() {
  if (creating.value) return
  formError.value = ''
  if (!form.name) { formError.value = '请填写名称'; return }
  if (!/^[a-z0-9][a-z0-9-]{1,48}$/.test(form.key)) { formError.value = '标识只能使用小写字母、数字与连字符（2-49 位）'; return }
  creating.value = true
  try {
    await createWorkflow({ key: form.key, name: form.name, description: form.description })
    createOpen.value = false
    message.success('工作流已创建')
    await router.push(`/workflows/${form.key}`)
  } catch (error) {
    formError.value = error?.response?.data?.error || '创建失败'
  } finally {
    creating.value = false
  }
}

function openRun(definition) {
  menuOpenKey.value = ''
  runDialog.open = true
  runDialog.definition = definition
  runDialog.keyword = ''
  runDialog.running = false
  runDialog.run = null
}

function closeRun() {
  runDialog.open = false
  if (pollTimer) { clearTimeout(pollTimer); pollTimer = null }
}

async function startRun() {
  if (runDialog.running || !runDialog.keyword || !runDialog.definition) return
  runDialog.running = true
  try {
    runDialog.run = await startWorkflow(runDialog.definition.key, runDialog.keyword)
    message.success('工作流已开始运行')
    pollRun()
  } catch (error) {
    message.error(error?.response?.data?.error || '发起运行失败')
    runDialog.running = false
  }
}

function pollRun() {
  if (!runDialog.run?.id) return
  pollTimer = setTimeout(async () => {
    try {
      runDialog.run = await getWorkflowRun(runDialog.run.id)
      if (runDialog.run.status === 'RUNNING') pollRun()
      else { runDialog.running = false; await refreshQuiet() }
    } catch (error) {
      runDialog.running = false
    }
  }, 2000)
}

function openHistory(definition) {
  menuOpenKey.value = ''
  historyDialog.open = true
  historyDialog.definition = definition
  historyDialog.runs = runs.value.filter(run => run.workflowKey === definition.key)
  historyDialog.expandedId = ''
  historyDialog.detail = null
}

function closeHistory() { historyDialog.open = false }

async function toggleHistoryRun(runId) {
  if (historyDialog.expandedId === runId) { historyDialog.expandedId = ''; return }
  historyDialog.expandedId = runId
  historyDialog.detail = null
  historyDialog.loadingId = runId
  try {
    const detail = await getWorkflowRun(runId)
    if (historyDialog.expandedId === runId) historyDialog.detail = detail
  } catch (error) {
    message.error(error?.response?.data?.error || '运行详情加载失败')
  } finally {
    historyDialog.loadingId = ''
  }
}

function openDelete(definition) {
  menuOpenKey.value = ''
  deleteDialog.open = true
  deleteDialog.definition = definition
  deleteDialog.busy = false
  deleteDialog.error = ''
}

async function confirmDelete() {
  if (deleteDialog.busy || !deleteDialog.definition) return
  deleteDialog.busy = true
  try {
    await deleteWorkflow(deleteDialog.definition.key)
    deleteDialog.open = false
    message.success('已删除')
    await refresh()
  } catch (error) {
    deleteDialog.error = error?.response?.data?.error || '删除失败'
  } finally {
    deleteDialog.busy = false
  }
}

async function refresh() {
  try {
    const data = await listWorkflows(30)
    definitions.value = data.definitions || []
    runs.value = data.runs || []
    loading.value = false
  } catch (error) {
    loading.value = false
    message.error(error?.response?.data?.error || '工作流数据加载失败')
  }
}

async function refreshQuiet() {
  try {
    const data = await listWorkflows(30)
    definitions.value = data.definitions || []
    runs.value = data.runs || []
  } catch (error) { /* 忽略 */ }
}

onMounted(refresh)
onBeforeUnmount(() => {
  if (pollTimer) clearTimeout(pollTimer)
  if (menuCloser) document.removeEventListener('click', menuCloser)
})
</script>

<style scoped>
.wf-page { flex: 1; overflow-y: auto; }
.wf-inner { max-width: 1080px; margin: 0 auto; padding: 28px 32px 56px; display: flex; flex-direction: column; gap: 16px; }
.wf-head { display: flex; align-items: flex-end; justify-content: space-between; }
.wf-head h1 { font-size: 22px; font-weight: 650; letter-spacing: -0.02em; color: var(--wb-text); }
.wf-head p { margin-top: 4px; font-size: 13px; color: var(--wb-text-2); }
.new-btn { display: inline-flex; align-items: center; gap: 6px; padding: 8px 16px; border: 0; border-radius: 10px; background: var(--wb-brand); color: var(--wb-brand-contrast); font-size: 13px; font-weight: 500; }
.new-btn:hover { background: var(--wb-brand-hover); }
.section-hint { padding: 14px 4px; color: var(--wb-muted); font-size: 13px; }
.wf-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(300px, 1fr)); gap: 12px; }
.wf-card { position: relative; display: flex; flex-direction: column; gap: 10px; padding: 16px 18px; border: 1px solid var(--wb-border); border-radius: var(--wb-radius-lg); background: var(--wb-surface); text-align: left; transition: border-color 0.15s, box-shadow 0.15s; }
.wf-card:hover { border-color: var(--wb-brand); box-shadow: 0 0 0 3px var(--wb-brand-soft); }
.card-head { display: flex; align-items: center; justify-content: space-between; }
.card-title { color: var(--wb-text); font-size: 15px; font-weight: 600; }
.card-tools { position: relative; display: flex; align-items: center; gap: 6px; }
.wf-version { padding: 1px 9px; border-radius: 999px; background: var(--wb-fill); color: var(--wb-text-2); font-size: 11.5px; }
.menu-btn { display: grid; place-items: center; width: 26px; height: 26px; border: 0; border-radius: 7px; background: transparent; color: var(--wb-muted); }
.menu-btn:hover { background: var(--wb-fill); color: var(--wb-text); }
.card-menu { position: absolute; right: 0; top: 30px; z-index: 20; display: flex; flex-direction: column; min-width: 132px; padding: 5px; border: 1px solid var(--wb-border); border-radius: 10px; background: var(--wb-raise); box-shadow: var(--wb-shadow-lg); }
.card-menu button { display: flex; align-items: center; gap: 8px; padding: 7px 10px; border: 0; border-radius: 7px; background: transparent; color: var(--wb-text-2); font-size: 12.5px; text-align: left; }
.card-menu button:hover { background: var(--wb-fill); color: var(--wb-text); }
.card-menu button.danger { color: var(--wb-danger); }
.card-menu button.danger:hover { background: rgba(220, 47, 76, 0.08); }
.card-body { display: flex; flex-direction: column; gap: 10px; }
.card-desc { color: var(--wb-text-2); font-size: 12.5px; line-height: 1.6; min-height: 20px; }
.card-foot { display: flex; align-items: center; justify-content: space-between; gap: 8px; }
.node-chips { display: flex; gap: 5px; flex-wrap: wrap; }
.node-chip { padding: 1px 8px; border-radius: 999px; background: var(--wb-brand-soft); color: var(--wb-brand); font-size: 11px; }
.card-count { color: var(--wb-muted); font-size: 12px; white-space: nowrap; }
.card-run { display: flex; align-items: center; gap: 8px; padding-top: 8px; border-top: 1px solid var(--wb-border); }
.card-run-text { color: var(--wb-muted); font-size: 12px; }
.add-card { align-items: center; justify-content: center; gap: 6px; border-style: dashed; color: var(--wb-muted); }
.add-card:hover { color: var(--wb-brand); }
.status-dot { width: 8px; height: 8px; border-radius: 50%; flex: 0 0 8px; background: var(--wb-border-strong); }
.status-dot.is-running { background: var(--wb-info); box-shadow: 0 0 0 3px rgba(37, 144, 255, 0.18); }
.status-dot.is-done { background: var(--wb-success); }
.status-dot.is-failed { background: var(--wb-danger); }
.status-dot.is-skipped { background: var(--wb-muted); }
.status-dot.is-pending { background: var(--wb-border-strong); }
.dialog-backdrop { position: fixed; inset: 0; z-index: 60; display: grid; place-items: center; background: rgba(15, 17, 26, 0.45); backdrop-filter: blur(2px); }
.dialog { width: min(460px, calc(100vw - 48px)); padding: 22px; border: 1px solid var(--wb-border); border-radius: var(--wb-radius-lg); background: var(--wb-surface); box-shadow: var(--wb-shadow-lg); display: flex; flex-direction: column; gap: 12px; max-height: 82vh; overflow-y: auto; }
.dialog.wide { width: min(640px, calc(100vw - 48px)); }
.dialog h2 { font-size: 16px; font-weight: 600; color: var(--wb-text); }
.dialog-row { display: flex; align-items: center; gap: 12px; }
.dialog-row > span { flex: 0 0 44px; color: var(--wb-text-2); font-size: 12.5px; }
.dialog-input { flex: 1; padding: 8px 12px; border: 1px solid var(--wb-border); border-radius: 9px; background: var(--wb-surface-2); color: var(--wb-text); font-size: 13px; outline: none; }
.dialog-input.mono { font-family: var(--wb-font-mono, monospace); font-size: 12.5px; }
.dialog-input:focus { border-color: var(--wb-brand); box-shadow: 0 0 0 3px var(--wb-brand-soft); }
.dialog-error { color: var(--wb-danger); font-size: 12.5px; }
.dialog-hint { color: var(--wb-muted); font-size: 12.5px; line-height: 1.6; }
.dialog-actions { display: flex; justify-content: flex-end; gap: 10px; margin-top: 4px; }
.ghost-btn { padding: 7px 14px; border: 1px solid var(--wb-border); border-radius: 9px; background: transparent; color: var(--wb-text-2); font-size: 12.5px; }
.primary-btn { padding: 7px 16px; border: 0; border-radius: 9px; background: var(--wb-brand); color: var(--wb-brand-contrast); font-size: 12.5px; font-weight: 500; }
.primary-btn.danger { background: var(--wb-danger); }
.primary-btn:disabled { background: var(--wb-fill); color: var(--wb-muted); }
.run-row { display: flex; gap: 10px; }
.run-state { display: flex; flex-direction: column; gap: 10px; padding: 12px; border: 1px solid var(--wb-border); border-radius: var(--wb-radius); background: var(--wb-surface-2); }
.run-state-head { display: flex; align-items: center; gap: 8px; color: var(--wb-text); font-size: 13px; }
.run-state-meta { color: var(--wb-muted); font-size: 12px; }
.link { margin-left: auto; color: var(--wb-brand); font-size: 12.5px; }
.mini-steps { display: flex; flex-direction: column; gap: 2px; }
.mini-step { display: flex; align-items: center; gap: 9px; padding: 5px 2px; }
.mini-name { color: var(--wb-text); font-size: 12.5px; }
.mini-meta { color: var(--wb-muted); font-size: 12px; margin-left: auto; }
.run-artifact { text-align: center; }
.artifact-image { max-width: 220px; width: 100%; border: 1px solid var(--wb-border); border-radius: var(--wb-radius); }
.history-list { display: flex; flex-direction: column; gap: 4px; }
.history-item { border: 1px solid var(--wb-border); border-radius: var(--wb-radius); background: var(--wb-surface-2); overflow: hidden; }
.history-row { display: flex; width: 100%; align-items: center; gap: 10px; padding: 10px 12px; border: 0; background: transparent; text-align: left; }
.history-row:hover { background: var(--wb-fill); }
.history-main { display: flex; flex: 1; flex-direction: column; gap: 2px; }
.history-title { color: var(--wb-text); font-size: 13px; font-weight: 500; }
.history-meta { color: var(--wb-muted); font-size: 12px; }
.history-detail { display: flex; flex-direction: column; gap: 6px; padding: 8px 14px 12px; border-top: 1px solid var(--wb-border); }
.run-status { flex: 0 0 auto; color: var(--wb-muted); font-size: 12px; }
.run-status.is-running { color: var(--wb-info); }
.run-status.is-done { color: var(--wb-success); }
.run-status.is-failed { color: var(--wb-danger); }
</style>
