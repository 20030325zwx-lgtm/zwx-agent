<template>
  <div class="wf-page">
    <div class="wf-inner">
      <header class="wf-head">
        <div>
          <h1>工作流</h1>
          <p>已制定的工作流：点开卡片进入画布编辑，或新建一个</p>
        </div>
        <button type="button" class="new-btn" @click="openCreate">
          <Plus :size="15" /> 新建工作流
        </button>
      </header>

      <p v-if="loading" class="section-hint">加载中…</p>
      <div v-else class="wf-grid">
        <router-link
          v-for="definition in definitions"
          :key="definition.key"
          class="wf-card"
          :to="`/workflows/${definition.key}`"
        >
          <div class="card-head">
            <h2>{{ definition.name }}</h2>
            <span class="wf-version">v{{ definition.version }}</span>
          </div>
          <p class="card-desc">{{ definition.description || '暂无描述' }}</p>
          <div class="card-foot">
            <span class="node-chips">
              <span v-for="step in definition.steps.slice(0, 4)" :key="step.key" class="node-chip">{{ step.name }}</span>
              <span v-if="definition.steps.length > 4" class="node-chip">+{{ definition.steps.length - 4 }}</span>
            </span>
            <span class="card-count">{{ definition.steps.length }} 节点</span>
          </div>
        </router-link>
        <button type="button" class="wf-card add-card" @click="openCreate">
          <Plus :size="20" />
          <span>新建工作流</span>
        </button>
      </div>

      <section class="wf-section">
        <div class="section-head">
          <h2>运行记录</h2>
          <button v-if="runs.length" type="button" class="refresh" @click="refresh">刷新</button>
        </div>
        <p v-if="!loading && !runs.length" class="section-hint">还没有运行记录</p>
        <div v-else class="run-list">
          <router-link
            v-for="run in runs"
            :key="run.id"
            class="run-item"
            :to="`/workflows/${run.workflowKey}`"
          >
            <span class="status-dot" :class="statusClass(run.status)" />
            <span class="run-main">
              <span class="run-title">{{ run.input?.keyword || run.workflowName }}</span>
              <span class="run-meta">{{ run.workflowName }} · v{{ run.definitionVersion }} · {{ formatTime(run.createdAt) }}</span>
            </span>
            <span class="run-status" :class="statusClass(run.status)">{{ statusLabel(run.status) }}</span>
          </router-link>
        </div>
      </section>
    </div>

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
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { message } from 'ant-design-vue'
import { Plus } from 'lucide-vue-next'
import { createWorkflow, listWorkflows } from '../api'

const router = useRouter()
const definitions = ref([])
const runs = ref([])
const loading = ref(true)
const createOpen = ref(false)
const creating = ref(false)
const formError = ref('')
const form = reactive({ name: '', key: '', description: '' })

function statusLabel(status) {
  return { RUNNING: '运行中', SUCCEEDED: '成功', FAILED: '失败' }[status] || status
}
function statusClass(status) {
  return { RUNNING: 'is-running', SUCCEEDED: 'is-done', FAILED: 'is-failed' }[status] || ''
}
function formatTime(value) {
  if (!value) return ''
  const date = new Date(value)
  const minutes = Math.floor((Date.now() - date.getTime()) / 60000)
  if (minutes < 1) return '刚刚'
  if (minutes < 60) return `${minutes} 分钟前`
  return `${date.getMonth() + 1}月${date.getDate()}日 ${String(date.getHours()).padStart(2, '0')}:${String(date.getMinutes()).padStart(2, '0')}`
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

async function refresh() {
  try {
    const data = await listWorkflows(20)
    definitions.value = data.definitions || []
    runs.value = data.runs || []
    loading.value = false
  } catch (error) {
    loading.value = false
    message.error(error?.response?.data?.error || '工作流数据加载失败')
  }
}

onMounted(refresh)
</script>

<style scoped>
.wf-page { flex: 1; overflow-y: auto; }
.wf-inner { max-width: 1080px; margin: 0 auto; padding: 28px 32px 56px; display: flex; flex-direction: column; gap: 16px; }
.wf-head { display: flex; align-items: flex-end; justify-content: space-between; }
.wf-head h1 { font-size: 22px; font-weight: 650; letter-spacing: -0.02em; color: var(--wb-text); }
.wf-head p { margin-top: 4px; font-size: 13px; color: var(--wb-text-2); }
.new-btn { display: inline-flex; align-items: center; gap: 6px; padding: 8px 16px; border: 0; border-radius: 10px; background: var(--wb-brand); color: var(--wb-brand-contrast); font-size: 13px; font-weight: 500; }
.new-btn:hover { background: var(--wb-brand-hover); }
.wf-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(300px, 1fr)); gap: 12px; }
.wf-card { display: flex; flex-direction: column; gap: 10px; padding: 16px 18px; border: 1px solid var(--wb-border); border-radius: var(--wb-radius-lg); background: var(--wb-surface); text-align: left; transition: border-color 0.15s, box-shadow 0.15s; }
.wf-card:hover { border-color: var(--wb-brand); box-shadow: 0 0 0 3px var(--wb-brand-soft); }
.card-head { display: flex; align-items: center; justify-content: space-between; }
.card-head h2 { font-size: 15px; font-weight: 600; color: var(--wb-text); }
.wf-version { padding: 1px 9px; border-radius: 999px; background: var(--wb-fill); color: var(--wb-text-2); font-size: 11.5px; }
.card-desc { color: var(--wb-text-2); font-size: 12.5px; line-height: 1.6; min-height: 20px; }
.card-foot { display: flex; align-items: center; justify-content: space-between; gap: 8px; }
.node-chips { display: flex; gap: 5px; flex-wrap: wrap; }
.node-chip { padding: 1px 8px; border-radius: 999px; background: var(--wb-brand-soft); color: var(--wb-brand); font-size: 11px; }
.card-count { color: var(--wb-muted); font-size: 12px; white-space: nowrap; }
.add-card { align-items: center; justify-content: center; gap: 6px; border-style: dashed; color: var(--wb-muted); }
.add-card:hover { color: var(--wb-brand); }
.wf-section { padding: 18px 20px; border: 1px solid var(--wb-border); border-radius: var(--wb-radius-lg); background: var(--wb-surface); }
.section-head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 10px; }
.section-head h2 { font-size: 15px; font-weight: 600; color: var(--wb-text); }
.section-hint { padding: 14px 4px; color: var(--wb-muted); font-size: 13px; }
.refresh { border: 0; background: transparent; color: var(--wb-muted); font-size: 12.5px; }
.run-list { display: flex; flex-direction: column; gap: 2px; }
.run-item { display: flex; align-items: center; gap: 11px; padding: 10px; border-radius: var(--wb-radius); transition: background 0.15s; }
.run-item:hover { background: var(--wb-fill); }
.status-dot { width: 8px; height: 8px; border-radius: 50%; flex: 0 0 8px; background: var(--wb-border-strong); }
.status-dot.is-running { background: var(--wb-info); box-shadow: 0 0 0 3px rgba(37, 144, 255, 0.18); }
.status-dot.is-done { background: var(--wb-success); }
.status-dot.is-failed { background: var(--wb-danger); }
.run-main { display: flex; flex: 1; flex-direction: column; gap: 3px; min-width: 0; }
.run-title { color: var(--wb-text); font-size: 13px; font-weight: 500; }
.run-meta { color: var(--wb-muted); font-size: 12px; }
.run-status { flex: 0 0 auto; color: var(--wb-muted); font-size: 12px; }
.run-status.is-running { color: var(--wb-info); }
.run-status.is-done { color: var(--wb-success); }
.run-status.is-failed { color: var(--wb-danger); }
.dialog-backdrop { position: fixed; inset: 0; z-index: 60; display: grid; place-items: center; background: rgba(15, 17, 26, 0.45); backdrop-filter: blur(2px); }
.dialog { width: min(440px, calc(100vw - 48px)); padding: 22px; border: 1px solid var(--wb-border); border-radius: var(--wb-radius-lg); background: var(--wb-surface); box-shadow: var(--wb-shadow-lg); display: flex; flex-direction: column; gap: 12px; }
.dialog h2 { font-size: 16px; font-weight: 600; color: var(--wb-text); }
.dialog-row { display: flex; align-items: center; gap: 12px; }
.dialog-row > span { flex: 0 0 44px; color: var(--wb-text-2); font-size: 12.5px; }
.dialog-input { flex: 1; padding: 8px 12px; border: 1px solid var(--wb-border); border-radius: 9px; background: var(--wb-surface-2); color: var(--wb-text); font-size: 13px; outline: none; }
.dialog-input.mono { font-family: var(--wb-font-mono, monospace); font-size: 12.5px; }
.dialog-input:focus { border-color: var(--wb-brand); box-shadow: 0 0 0 3px var(--wb-brand-soft); }
.dialog-error { color: var(--wb-danger); font-size: 12.5px; }
.dialog-actions { display: flex; justify-content: flex-end; gap: 10px; margin-top: 4px; }
.ghost-btn { padding: 7px 14px; border: 1px solid var(--wb-border); border-radius: 9px; background: transparent; color: var(--wb-text-2); font-size: 12.5px; }
.primary-btn { padding: 7px 16px; border: 0; border-radius: 9px; background: var(--wb-brand); color: var(--wb-brand-contrast); font-size: 12.5px; font-weight: 500; }
.primary-btn:disabled { background: var(--wb-fill); color: var(--wb-muted); }
</style>
