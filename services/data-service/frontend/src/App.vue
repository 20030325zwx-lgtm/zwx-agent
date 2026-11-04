<template>
  <div class="page">
    <header class="head">
      <div>
        <p class="kicker">数据中台 · 独立服务</p>
        <h1>反馈 / BadCase</h1>
        <p class="sub">一次点击入仓，归类找根因，一键回流评测集形成回归。</p>
      </div>
      <div class="row-actions">
        <a class="btn ghost" href="/">返回 Aster</a>
        <button class="btn" :disabled="loading" @click="load()">{{ loading ? '刷新中…' : '刷新' }}</button>
      </div>
    </header>

    <p v-if="!token" class="error">未检测到登录状态：请先在 Aster 主站登录后再访问数据中台。</p>

    <div class="toolbar" style="margin-bottom:14px">
      <button class="tab" :class="{ active: view === 'feedback' }" @click="view = 'feedback'">反馈 / BadCase</button>
      <button class="tab" :class="{ active: view === 'trace' }" @click="view = 'trace'">运行轨迹</button>
    </div>

    <template v-if="view === 'feedback'">
    <div class="metrics">
      <div class="metric">
        <h3>反馈总量</h3>
        <strong>{{ stats.total ?? 0 }}</strong>
        <p>👍 {{ counts.rating_up || 0 }} · 👎 {{ counts.rating_down || 0 }}</p>
      </div>
      <div class="metric">
        <h3>回流转化</h3>
        <strong>{{ stats.convertedRate == null ? '—' : Math.round(stats.convertedRate * 100) + '%' }}</strong>
        <p>{{ stats.converted ?? 0 }} 条已进入评测集</p>
      </div>
      <div class="metric">
        <h3>待处理</h3>
        <strong>{{ counts.open ?? 0 }}</strong>
        <p>根因未归类 {{ unclassified }} 条</p>
      </div>
    </div>

    <div class="toolbar">
      <button v-for="option in filters" :key="option.value" class="tab"
              :class="{ active: status === option.value }" @click="status = option.value; load()">
        {{ option.label }}
      </button>
      <select v-model="rootCause" class="select" @change="load()">
        <option value="">全部根因</option>
        <option v-for="item in ROOT_CAUSES" :key="item[0]" :value="item[0]">{{ item[1] }}</option>
        <option value="unclassified">未归类</option>
      </select>
      <span style="flex:1"></span>
      <span class="muted">{{ items.length }} 条</span>
    </div>

    <p v-if="error" class="error">{{ error }}</p>
    <p v-if="!loading && !items.length" class="empty card">还没有反馈。去智能体对话里对回答点 👍/👎。</p>

    <div v-else class="card">
      <table>
        <thead><tr><th>评价</th><th>问题</th><th>标签 / 根因</th><th>状态</th><th>时间</th><th>操作</th></tr></thead>
        <tbody>
          <tr v-for="item in items" :key="item.id">
            <td>
              <span class="badge" :class="item.rating === 'up' ? 'success' : 'danger'">
                {{ item.rating === 'up' ? '👍 好评' : '👎 BadCase' }}
              </span>
            </td>
            <td class="note" :title="item.refs?.question || ''">{{ item.refs?.question || '（无问题快照）' }}</td>
            <td>
              <div class="chips">
                <span v-if="item.rootCause" class="chip judge">{{ rootCauseLabel(item.rootCause) }}</span>
                <span v-for="tag in item.tags || []" :key="tag" class="chip">{{ tag }}</span>
                <span v-if="!item.rootCause && !(item.tags || []).length" class="muted">—</span>
              </div>
            </td>
            <td><span class="badge" :class="statusTone(item.status)">{{ statusLabel(item.status) }}</span></td>
            <td class="mono">{{ formatTime(item.createdAt) }}</td>
            <td class="row-actions">
              <button class="btn ghost" @click="open(item)">详情</button>
              <button v-if="item.status !== 'converted'" class="btn" :disabled="busy" @click="convert(item)">
                {{ item.rating === 'up' ? '转 golden' : '转回归' }}
              </button>
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <p v-if="nextCursor" style="text-align:center;margin-top:14px">
      <button class="btn" :disabled="loading" @click="load(nextCursor, true)">加载更多</button>
    </p>

    <div class="card" style="margin-top:16px">
      <div class="card-head">
        <h3>根因分布</h3>
        <span class="muted">回答「这个月最多的问题该改哪一层」</span>
      </div>
      <table>
        <thead><tr><th>根因</th><th>好评</th><th>差评</th><th>已回流</th></tr></thead>
        <tbody>
          <tr v-for="row in rootCauseRows" :key="row.key">
            <td>{{ rootCauseLabel(row.key) }}</td>
            <td>{{ row.up }}</td>
            <td>{{ row.down }}</td>
            <td>{{ row.converted }}</td>
          </tr>
        </tbody>
      </table>
    </div>

    <div class="card" style="margin-top:16px">
      <div class="card-head">
        <h3>隐式信号（近 7 天）</h3>
        <span class="muted">采纳 / 编辑 / 重生成 / 复制 / 导出 —— 比点赞密集的质量真值</span>
      </div>
      <div class="signal-row">
        <span v-for="(value, key) in signalByEvent" :key="key" class="signal">
          <strong>{{ value.total }}</strong>
          <em>{{ signalLabel(key) }}</em>
        </span>
        <span v-if="!Object.keys(signalByEvent).length" class="muted">还没有信号上报</span>
      </div>
    </div>
    </template>

    <RunTracePanel v-else />

    <div v-if="selected" class="drawer-mask" @click.self="selected = null">
      <aside class="drawer">
        <div class="drawer-head">
          <div>
            <h2>{{ selected.refs?.question || '反馈详情' }}</h2>
            <p class="muted">
              <span class="badge" :class="selected.rating === 'up' ? 'success' : 'danger'">
                {{ selected.rating === 'up' ? '👍 好评' : '👎 BadCase' }}
              </span>
              · <span class="badge" :class="statusTone(selected.status)">{{ statusLabel(selected.status) }}</span>
              · {{ formatTime(selected.createdAt) }}
            </p>
          </div>
          <button class="btn ghost" @click="selected = null">关闭</button>
        </div>

        <div class="section">
          <h3>回答</h3>
          <p class="answer">{{ selected.refs?.answer || '（无回答快照）' }}</p>
        </div>

        <div class="section">
          <h3>归类</h3>
          <div class="inline-row">
            <select v-model="analysis.rootCause" class="select" style="flex:1">
              <option value="">未归类</option>
              <option v-for="item in ROOT_CAUSES" :key="item[0]" :value="item[0]">{{ item[1] }}</option>
            </select>
            <input v-model="analysis.assignee" class="input" style="width:130px" placeholder="负责人">
          </div>
          <textarea v-model="analysis.resolution" class="textarea" rows="3" style="width:100%;margin-top:6px"
                    placeholder="处理结论（回流时自动带进用例 notes）"></textarea>
          <div class="row-actions" style="margin-top:6px">
            <button class="btn primary" :disabled="busy" @click="saveAnalysis">保存归类</button>
          </div>
        </div>

        <div class="section">
          <h3>动作</h3>
          <div class="row-actions" style="flex-wrap:wrap">
            <button v-if="selected.status !== 'converted'" class="btn primary" :disabled="busy" @click="convert(selected)">
              {{ selected.rating === 'up' ? '转 golden 用例' : '转回归用例' }}
            </button>
            <button v-if="selected.status === 'open'" class="btn" :disabled="busy"
                    @click="setStatus(selected, 'acked')">已知悉</button>
            <button v-if="selected.status === 'open'" class="btn ghost" :disabled="busy"
                    @click="setStatus(selected, 'ignored')">忽略</button>
            <span v-if="selected.status === 'converted'" class="muted mono">
              已回流 → {{ String(selected.convertedCaseId || '').slice(0, 8) }}
            </span>
          </div>
        </div>
      </aside>
    </div>

    <p v-if="toast" class="toast">{{ toast }}</p>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import RunTracePanel from './components/RunTracePanel.vue'

const VIEWS = ['feedback', 'trace']
const view = ref(VIEWS[0])

const API = '/api/ai/feedback'
const ROOT_CAUSES = [
  ['knowledge_gap', '知识库缺失'],
  ['retrieval_miss', '检索没召回'],
  ['prompt_weak', '提示词弱'],
  ['tool_args', '工具提参错'],
  ['intent_misroute', '意图误判'],
  ['refusal_fail', '该拒未拒'],
  ['latency', '太慢'],
  ['other', '其他']
]
const SIGNAL_LABELS = {
  adopt: '采纳', edit: '编辑', regenerate: '重生成', copy: '复制', export: '导出'
}

const token = ref(localStorage.getItem('zwx_auth_token') || '')
const loading = ref(false)
const busy = ref(false)
const error = ref('')
const toast = ref('')
const items = ref([])
const counts = ref({})
const stats = ref({})
const status = ref('')
const rootCause = ref('')
const nextCursor = ref(null)
const selected = ref(null)
const signals = ref({})
const analysis = reactive({ rootCause: '', assignee: '', resolution: '' })

const STATUS = {
  open: ['待处理', 'warning'], acked: ['已知悉', 'info'],
  converted: ['已回流', 'success'], ignored: ['已忽略', 'muted']
}

function statusLabel(value) { return STATUS[value]?.[0] || value }
function statusTone(value) { return STATUS[value]?.[1] || 'muted' }
function rootCauseLabel(value) {
  if (!value) return '未归类'
  if (value === 'unclassified') return '未归类'
  return ROOT_CAUSES.find((item) => item[0] === value)?.[1] || value
}
function signalLabel(value) { return SIGNAL_LABELS[value] || value }
function formatTime(value) { return value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '—' }
const unclassified = computed(() =>
  (stats.value.byRootCause || [])
    .filter((row) => row.root_cause === 'unclassified')
    .reduce((sum, row) => sum + Number(row.total), 0))

const rootCauseRows = computed(() => {
  const buckets = new Map()
  for (const row of stats.value.byRootCause || []) {
    const key = row.root_cause
    const current = buckets.get(key) || { key, up: 0, down: 0, converted: 0 }
    if (row.rating === 'up') current.up += Number(row.total)
    else current.down += Number(row.total)
    current.converted += Number(row.converted)
    buckets.set(key, current)
  }
  return [...buckets.values()].sort((left, right) =>
    (right.up + right.down) - (left.up + left.down))
})

const signalByEvent = computed(() => signals.value.byEvent || {})

const filters = computed(() => [
  { value: '', label: '全部' },
  { value: 'open', label: '待处理' },
  { value: 'converted', label: '已回流' },
  { value: 'ignored', label: '已忽略' }
])

function showToast(text) {
  toast.value = text
  setTimeout(() => { toast.value = '' }, 3400)
}

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

async function load(cursor = null, append = false) {
  if (!token.value) return
  loading.value = true
  error.value = ''
  try {
    const qs = new URLSearchParams({ limit: '30' })
    if (status.value) qs.set('status', status.value)
    if (rootCause.value) qs.set('rootCause', rootCause.value)
    if (cursor) qs.set('cursor', cursor)
    const [data, statsData, signalData] = await Promise.all([
      call('?' + qs.toString()),
      call('/stats'),
      call('/signals/summary?days=7').catch(() => ({}))
    ])
    items.value = append ? [...items.value, ...(data.items || [])] : (data.items || [])
    counts.value = data.counts || {}
    nextCursor.value = data.nextCursor
    stats.value = statsData || {}
    signals.value = signalData || {}
  } catch (exception) {
    error.value = exception.message
  } finally {
    loading.value = false
  }
}

function open(item) {
  selected.value = item
  analysis.rootCause = item.rootCause || ''
  analysis.assignee = item.assignee || ''
  analysis.resolution = item.resolution || ''
}

async function saveAnalysis() {
  busy.value = true
  try {
    const updated = await call(`/${selected.value.id}/analysis`, {
      method: 'PUT',
      body: JSON.stringify({
        rootCause: analysis.rootCause || null,
        assignee: analysis.assignee || null,
        resolution: analysis.resolution || null
      })
    })
    selected.value = updated
    await load()
    showToast('归类已保存')
  } catch (exception) {
    showToast('保存失败：' + exception.message)
  } finally {
    busy.value = false
  }
}

async function setStatus(item, next) {
  busy.value = true
  try {
    await call(`/${item.id}/status`, { method: 'POST', body: JSON.stringify({ status: next }) })
    await load()
    if (selected.value?.id === item.id) selected.value = null
  } catch (exception) {
    showToast('更新失败：' + exception.message)
  } finally {
    busy.value = false
  }
}

async function convert(item) {
  busy.value = true
  try {
    const result = await call(`/${item.id}/convert`, { method: 'POST', body: '{}' })
    showToast(`已回流到「线上回流」数据集（用例 ${result.caseExternalId}），到评测台发布后即可跑回归`)
    selected.value = null
    await load()
  } catch (exception) {
    showToast('回流失败：' + exception.message)
  } finally {
    busy.value = false
  }
}

onMounted(load)
</script>
