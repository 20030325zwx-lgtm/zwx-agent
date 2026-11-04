<template>
  <div class="page">
    <header class="head">
      <div>
        <p class="kicker">评测中台 · 独立服务</p>
        <h1>评测台</h1>
        <p class="sub">数据集版本化与实验对比：换模型、改 Prompt、动知识库之前，先拿对比报告。</p>
      </div>
      <div class="row-actions">
        <a class="btn ghost" href="/">返回 Aster</a>
        <button class="btn" :disabled="loading" @click="reload">{{ loading ? '刷新中…' : '刷新' }}</button>
      </div>
    </header>

    <p v-if="!token" class="error">未检测到登录状态：请先在 Aster 主站登录后再访问评测台。</p>

    <div class="tabs">
      <button class="tab" :class="{ active: tab === 'experiments' }" @click="switchTab('experiments')">
        实验 <em>{{ experiments.length }}</em>
      </button>
      <button class="tab" :class="{ active: tab === 'datasets' }" @click="switchTab('datasets')">
        数据集 <em>{{ datasets.length }}</em>
      </button>
    </div>

    <p v-if="error" class="error">{{ error }}</p>

    <!-- ============================ 实验 ============================ -->
    <section v-if="tab === 'experiments'">
      <div class="toolbar">
        <select v-model="compareLeft" class="select">
          <option value="">对比 A…</option>
          <option v-for="item in doneExperiments" :key="item.id" :value="item.id">{{ label(item) }}</option>
        </select>
        <span class="muted">vs</span>
        <select v-model="compareRight" class="select">
          <option value="">对比 B…</option>
          <option v-for="item in doneExperiments" :key="item.id" :value="item.id">{{ label(item) }}</option>
        </select>
        <button class="btn" :disabled="!compareLeft || !compareRight || comparing" @click="runCompare">
          {{ comparing ? '对比中…' : '对比' }}
        </button>
        <span style="flex:1"></span>
        <button class="btn primary" @click="openCreate">新建实验</button>
      </div>

      <div v-if="comparison" class="card" style="margin-bottom:14px">
        <table>
          <thead><tr><th>对比报告</th><th>A</th><th>B</th><th>差异</th><th>延迟 A/B</th></tr></thead>
          <tbody>
            <tr v-for="row in comparison.cases" :key="row.externalId">
              <td class="mono">{{ row.externalId }}</td>
              <td><span class="badge" :class="boolTone(row.leftPassed)">{{ boolLabel(row.leftPassed) }}</span></td>
              <td><span class="badge" :class="boolTone(row.rightPassed)">{{ boolLabel(row.rightPassed) }}</span></td>
              <td>{{ deltaLabel(row.delta) }}</td>
              <td class="mono">{{ row.leftLatencyMs ?? '—' }} / {{ row.rightLatencyMs ?? '—' }}</td>
            </tr>
          </tbody>
        </table>
      </div>

      <p v-if="!loading && !experiments.length" class="empty card">还没有实验。先到数据集页签导入并发布版本。</p>
      <div v-else class="card">
        <table>
          <thead><tr><th>状态</th><th>数据集</th><th>类型</th><th>通过</th><th>备注</th><th>创建时间</th><th></th></tr></thead>
          <tbody>
            <tr v-for="item in experiments" :key="item.id">
              <td><span class="badge" :class="statusTone(item.status)">{{ statusLabel(item.status) }}</span></td>
              <td>{{ datasetName(item.datasetId) }} <span class="mono">v{{ item.datasetVersionNo }}</span></td>
              <td>{{ typeLabel(item.evalType) }}</td>
              <td>{{ passRate(item) }}</td>
              <td class="note">{{ item.candidate?.notes || item.candidate?.evalModel?.modelName || '—' }}</td>
              <td>{{ formatTime(item.createdAt) }}</td>
              <td><button class="btn ghost" @click="openExperiment(item.id)">详情</button></td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>

    <!-- ============================ 数据集 ============================ -->
    <section v-else>
      <div class="toolbar">
        <input v-model="datasetForm.name" class="input" placeholder="数据集名称">
        <select v-model="datasetForm.evalType" class="select">
          <option value="intent">意图路由</option>
          <option value="retrieval">检索命中</option>
          <option value="prompt">单轮直调</option>
          <option value="task">全链路任务</option>
        </select>
        <input v-model="datasetForm.description" class="input" placeholder="描述（可选）">
        <button class="btn primary" :disabled="busy || !datasetForm.name" @click="createDataset">创建数据集</button>
      </div>

      <p v-if="!loading && !datasets.length" class="empty card">还没有数据集。创建后导入评测集 JSON。</p>
      <div v-else class="card">
        <table>
          <thead><tr><th>名称</th><th>类型</th><th>最新版本</th><th>草稿</th><th>已发布</th><th>创建时间</th><th></th></tr></thead>
          <tbody>
            <tr v-for="item in datasets" :key="item.id">
              <td>{{ item.name }}</td>
              <td>{{ typeLabel(item.eval_type) }}</td>
              <td>{{ item.latest_version > 0 ? `v${item.latest_version}` : '未发布' }}</td>
              <td>{{ item.draft_cases }}</td>
              <td>{{ item.published_cases }}</td>
              <td>{{ formatTime(item.created_at) }}</td>
              <td><button class="btn ghost" @click="openDataset(item.id)">详情</button></td>
            </tr>
          </tbody>
        </table>
      </div>
    </section>

    <!-- 数据集抽屉 -->
    <div v-if="datasetDetail" class="drawer-mask" @click.self="datasetDetail = null">
      <aside class="drawer">
        <div class="drawer-head">
          <div>
            <h2>{{ datasetDetail.name }}</h2>
            <p class="muted">{{ typeLabel(datasetDetail.eval_type) }} · {{ datasetDetail.description || '无描述' }}</p>
          </div>
          <button class="btn ghost" @click="datasetDetail = null">关闭</button>
        </div>
        <div class="section">
          <h3>已发布版本</h3>
          <p v-if="!datasetDetail.versions?.length" class="muted">尚未发布；实验只能引用已发布版本。</p>
          <table v-else>
            <thead><tr><th>版本</th><th>用例</th><th>发布时间</th><th></th></tr></thead>
            <tbody>
              <tr v-for="v in datasetDetail.versions" :key="v.version_no">
                <td class="mono">v{{ v.version_no }}</td>
                <td>{{ v.case_count }}</td>
                <td>{{ formatTime(v.published_at) }}</td>
                <td><button class="btn ghost" @click="loadCases(v.version_no)">查看</button></td>
              </tr>
            </tbody>
          </table>
        </div>
        <div class="section">
          <h3>导入用例到草稿</h3>
          <p class="muted">支持数组或 {cases:[...]}，兼容 task-eval-v1 / knowledge-intent-eval-v1 字段。</p>
          <textarea v-model="importText" class="textarea" rows="6"
                    style="width:100%;font-family:ui-monospace,Menlo,monospace"
                    placeholder='[{"id":"intent-001","question":"你好","expectedIntent":"闲聊问答"}]'></textarea>
          <div class="row-actions" style="margin-top:8px">
            <button class="btn primary" :disabled="busy || !importText.trim()" @click="importCases">导入</button>
            <button class="btn" :disabled="busy" @click="publishDataset">发布为新版本</button>
          </div>
        </div>
        <div v-if="cases.length" class="section">
          <h3>用例（{{ casesTitle }}）</h3>
          <table>
            <thead><tr><th>ID</th><th>内容</th></tr></thead>
            <tbody>
              <tr v-for="row in cases" :key="row.external_id || row.externalId">
                <td class="mono">{{ row.external_id || row.externalId }}</td>
                <td class="note">{{ caseSummary(row) }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </aside>
    </div>

    <!-- 实验抽屉 -->
    <div v-if="experimentDetail" class="drawer-mask" @click.self="closeExperiment">
      <aside class="drawer">
        <div class="drawer-head">
          <div>
            <h2>{{ datasetName(experimentDetail.datasetId) }}
              <span class="mono">v{{ experimentDetail.datasetVersionNo }}</span></h2>
            <p class="muted">
              <span class="badge" :class="statusTone(experimentDetail.status)">{{ statusLabel(experimentDetail.status) }}</span>
              · {{ typeLabel(experimentDetail.evalType) }} · {{ formatTime(experimentDetail.createdAt) }}
            </p>
          </div>
          <button class="btn ghost" @click="closeExperiment">关闭</button>
        </div>
        <div class="metrics">
          <div class="metric"><h3>通过率</h3><strong>{{ passRate(experimentDetail) }}</strong>
            <p>{{ totalsText(experimentDetail.totals) }}</p></div>
          <div class="metric"><h3>平均延迟</h3>
            <strong>{{ experimentDetail.totals?.avgLatencyMs != null ? experimentDetail.totals.avgLatencyMs + ' ms' : '—' }}</strong>
            <p>judge 均分 {{ experimentDetail.totals?.judgeAvg ?? '—' }}</p></div>
          <div class="metric"><h3>被测对象</h3>
            <strong style="font-size:13px">{{ experimentDetail.candidate?.notes || '—' }}</strong>
            <p class="mono">{{ experimentDetail.gitCommit || '未记录 commit' }}</p></div>
        </div>
        <div v-if="['queued','running'].includes(experimentDetail.status)" class="section">
          <p class="muted">实验进行中，自动刷新（最近事件：{{ latestEvent }})</p>
          <button class="btn" :disabled="busy" @click="cancelExperiment">取消实验</button>
        </div>
        <p v-if="experimentDetail.errorMessage" class="error">{{ experimentDetail.errorMessage }}</p>
        <div v-if="results.length" class="section">
          <h3>结果明细（{{ results.length }}）</h3>
          <table>
            <thead><tr><th>用例</th><th>结果</th><th>断言</th><th>延迟</th></tr></thead>
            <tbody>
              <tr v-for="row in results" :key="row.case_external_id">
                <td class="mono">{{ row.case_external_id }}</td>
                <td><span class="badge" :class="boolTone(row.passed)">{{ boolLabel(row.passed) }}</span></td>
                <td>
                  <div class="chips">
                    <span v-for="c in parseList(row.checks)" :key="c.name" class="chip"
                          :class="{ failed: c.passed === false }">{{ c.name }}</span>
                    <span v-if="parseJudge(row.judge)" class="chip judge">judge {{ parseJudge(row.judge).average }}</span>
                  </div>
                </td>
                <td class="mono">{{ row.latency_ms ?? '—' }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </aside>
    </div>

    <!-- 新建实验 -->
    <div v-if="createOpen" class="drawer-mask" @click.self="createOpen = false">
      <aside class="drawer" style="width:min(420px,100%)">
        <div class="drawer-head"><h2>新建实验</h2>
          <button class="btn ghost" @click="createOpen = false">关闭</button></div>
        <div class="section">
          <h3>数据集（仅已发布）</h3>
          <select v-model="expForm.datasetId" class="select" style="width:100%" @change="onDatasetPick">
            <option value="" disabled>选择数据集…</option>
            <option v-for="item in publishableDatasets" :key="item.id" :value="item.id">
              {{ item.name }}（{{ typeLabel(item.eval_type) }} · v{{ item.latest_version }}）
            </option>
          </select>
          <h3 style="margin-top:12px">版本</h3>
          <select v-model="expForm.versionNo" class="select" style="width:100%">
            <option v-if="!pickVersions.length" value="" disabled>先选数据集</option>
            <option v-for="v in pickVersions" :key="v.version_no" :value="v.version_no">
              v{{ v.version_no }}（{{ v.case_count }} 例）
            </option>
          </select>
          <h3 style="margin-top:12px">备注</h3>
          <input v-model="expForm.notes" class="input" style="width:100%" placeholder="如：更换 judge 模型 / 调整 prompt">
          <h3 style="margin-top:12px">git commit（可选）</h3>
          <input v-model="expForm.gitCommit" class="input" style="width:100%">
          <div class="row-actions" style="margin-top:12px">
            <button class="btn primary" :disabled="busy" @click="createExperiment">创建并入队</button>
          </div>
        </div>
      </aside>
    </div>

    <p v-if="toast" class="toast">{{ toast }}</p>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'

const API = '/api/ai/eval'
const token = ref(localStorage.getItem('zwx_auth_token') || '')
const tab = ref('experiments')
const loading = ref(false)
const busy = ref(false)
const error = ref('')
const toast = ref('')
const datasets = ref([])
const experiments = ref([])
const datasetDetail = ref(null)
const cases = ref([])
const casesTitle = ref('')
const importText = ref('')
const experimentDetail = ref(null)
const results = ref([])
const latestEvent = ref('')
const comparison = ref(null)
const comparing = ref(false)
const compareLeft = ref('')
const compareRight = ref('')
const createOpen = ref(false)
const datasetForm = ref({ name: '', evalType: 'intent', description: '' })
const expForm = ref({ datasetId: '', versionNo: '', notes: '', gitCommit: '' })
const pickVersions = ref([])

const TYPE_LABELS = { intent: '意图路由', retrieval: '检索命中', prompt: '单轮直调', task: '全链路任务' }
const STATUS = {
  queued: ['排队中', 'info'], running: ['执行中', 'warning'],
  succeeded: ['已完成', 'success'], failed: ['失败', 'danger'], cancelled: ['已取消', 'muted']
}

function typeLabel(v) { return TYPE_LABELS[v] || v }
function statusLabel(s) { return STATUS[s]?.[0] || s }
function statusTone(s) { return STATUS[s]?.[1] || 'muted' }
function boolLabel(p) { return p === null || p === undefined ? '跳过' : p ? '通过' : '未过' }
function boolTone(p) { return p === null || p === undefined ? 'muted' : p ? 'success' : 'danger' }
function deltaLabel(d) { return d === 'left_only' ? 'A 独有过' : d === 'right_only' ? 'B 独有过' : d === 'same' ? '一致' : '—' }
function datasetName(id) { return datasets.value.find(d => d.id === id)?.name || id?.slice(0, 8) || '—' }
function passRate(item) {
  const t = item.totals || {}
  return t.passRate == null ? '—' : Math.round(t.passRate * 100) + '%'
}
function totalsText(t) {
  return !t || t.total == null ? '—' : `${t.passed}/${t.total} 通过` + (t.skipped ? `（跳过 ${t.skipped}）` : '')
}
function label(item) { return `${datasetName(item.datasetId)} v${item.datasetVersionNo} · ${item.candidate?.notes || item.id.slice(0, 8)}` }
function formatTime(v) {
  if (!v) return '—'
  const d = new Date(v)
  return isNaN(d) ? String(v) : d.toLocaleString('zh-CN', { hour12: false })
}
function parseList(v) {
  if (Array.isArray(v)) return v
  try { return JSON.parse(v || '[]') } catch { return [] }
}
function parseJudge(v) {
  if (!v) return null
  if (typeof v === 'object') return v
  try { return JSON.parse(v) } catch { return null }
}
function caseSummary(row) {
  const p = typeof row.payload === 'string' ? (() => { try { return JSON.parse(row.payload) } catch { return {} } })() : (row.payload || {})
  return p.question || p.task || p.input || JSON.stringify(p).slice(0, 80)
}
function showToast(text) { toast.value = text; setTimeout(() => { toast.value = '' }, 3200) }

async function call(path, options = {}) {
  const response = await fetch(API + path, {
    ...options,
    headers: {
      'Content-Type': 'application/json',
      ...(token.value ? { Authorization: `Bearer ${token.value}` } : {}),
      ...(options.headers || {})
    }
  })
  const text = await response.text()
  const data = text ? JSON.parse(text) : {}
  if (!response.ok) throw new Error(data.error || `HTTP ${response.status}`)
  return data
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    const [ds, ex] = await Promise.all([call('/datasets'), call('/experiments?limit=30')])
    datasets.value = ds || []
    experiments.value = ex.items || []
  } catch (e) {
    error.value = e.message
  } finally {
    loading.value = false
  }
}
function reload() { load() }
function switchTab(t) { tab.value = t }

async function createDataset() {
  busy.value = true
  try {
    await call('/datasets', { method: 'POST', body: JSON.stringify(datasetForm.value) })
    datasetForm.value = { name: '', evalType: datasetForm.value.evalType, description: '' }
    await load()
    showToast('数据集已创建')
  } catch (e) { showToast('创建失败：' + e.message) } finally { busy.value = false }
}
async function openDataset(id) {
  try { datasetDetail.value = await call(`/datasets/${id}`); cases.value = [] } catch (e) { showToast(e.message) }
}
async function importCases() {
  busy.value = true
  try {
    const parsed = JSON.parse(importText.value)
    const result = await call(`/datasets/${datasetDetail.value.id}/cases/import`, {
      method: 'POST', body: JSON.stringify({ cases: Array.isArray(parsed) ? parsed : parsed.cases })
    })
    showToast(`导入完成：新增 ${result.imported}，更新 ${result.updated}`)
    importText.value = ''
    datasetDetail.value = await call(`/datasets/${datasetDetail.value.id}`)
    await load()
  } catch (e) { showToast('导入失败：' + e.message) } finally { busy.value = false }
}
async function publishDataset() {
  busy.value = true
  try {
    const r = await call(`/datasets/${datasetDetail.value.id}/publish`, { method: 'POST', body: JSON.stringify({}) })
    showToast(`已发布 v${r.version}`)
    datasetDetail.value = await call(`/datasets/${datasetDetail.value.id}`)
    await load()
  } catch (e) { showToast('发布失败：' + e.message) } finally { busy.value = false }
}
async function loadCases(version) {
  try {
    cases.value = await call(`/datasets/${datasetDetail.value.id}/cases?version=${version}`)
    casesTitle.value = `v${version}`
  } catch (e) { showToast(e.message) }
}

const doneExperiments = computed(() => experiments.value.filter(i => ['succeeded', 'failed'].includes(i.status)))
const publishableDatasets = computed(() => datasets.value.filter(i => i.latest_version > 0))

function openCreate() {
  expForm.value = { datasetId: '', versionNo: '', notes: '', gitCommit: '' }
  pickVersions.value = []
  createOpen.value = true
}
async function onDatasetPick() {
  const ds = datasets.value.find(d => d.id === expForm.value.datasetId)
  if (!ds) return
  const detail = await call(`/datasets/${ds.id}`)
  pickVersions.value = detail.versions || []
  expForm.value.versionNo = detail.versions?.[0]?.version_no ?? ''
}
async function createExperiment() {
  busy.value = true
  try {
    await call('/experiments', {
      method: 'POST',
      body: JSON.stringify({
        datasetId: expForm.value.datasetId,
        versionNo: Number(expForm.value.versionNo) || undefined,
        notes: expForm.value.notes,
        gitCommit: expForm.value.gitCommit
      })
    })
    createOpen.value = false
    await load()
    showToast('实验已入队')
  } catch (e) { showToast('创建失败：' + e.message) } finally { busy.value = false }
}

let pollTimer = null
async function openExperiment(id) {
  try {
    experimentDetail.value = await call(`/experiments/${id}`)
    results.value = await call(`/experiments/${id}/results`)
    schedulePoll()
  } catch (e) { showToast(e.message) }
}
function closeExperiment() {
  experimentDetail.value = null
  results.value = []
  stopPoll()
}
async function cancelExperiment() {
  busy.value = true
  try {
    await call(`/experiments/${experimentDetail.value.id}/cancel`, { method: 'POST', body: '{}' })
    await openExperiment(experimentDetail.value.id)
    await load()
  } catch (e) { showToast(e.message) } finally { busy.value = false }
}
function schedulePoll() {
  stopPoll()
  const d = experimentDetail.value
  if (!d || !['queued', 'running'].includes(d.status)) return
  pollTimer = setTimeout(async () => {
    if (document.visibilityState === 'hidden') { schedulePoll(); return }
    try {
      const fresh = await call(`/experiments/${d.id}`)
      const page = await call(`/experiments/${d.id}/events?afterSeq=0&limit=20`)
      const items = page.items || []
      latestEvent.value = items.length ? `${items[items.length - 1].type} #${items[items.length - 1].seq}` : ''
      if (experimentDetail.value?.id === d.id) {
        experimentDetail.value = fresh
        if (!['queued', 'running'].includes(fresh.status)) {
          results.value = await call(`/experiments/${d.id}/results`)
          await load()
          return
        }
      }
    } catch { /* 下一轮重试 */ }
    schedulePoll()
  }, 3000)
}
function stopPoll() { if (pollTimer) { clearTimeout(pollTimer); pollTimer = null } }

async function runCompare() {
  comparing.value = true
  try {
    comparison.value = await call(`/experiments/compare?left=${compareLeft.value}&right=${compareRight.value}`)
  } catch (e) { showToast('对比失败：' + e.message) } finally { comparing.value = false }
}

onMounted(load)
onBeforeUnmount(stopPoll)
</script>
