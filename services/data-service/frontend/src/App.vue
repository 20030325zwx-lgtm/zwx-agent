<template>
  <div class="page">
    <header class="head">
      <div>
        <p class="kicker">数据中台 · 独立服务</p>
        <h1>反馈 / BadCase</h1>
        <p class="sub">用户一次点击的反馈在这里沉淀；负面反馈即 BadCase，一键回流评测集形成回归。</p>
      </div>
      <div class="row-actions">
        <a class="btn ghost" href="/">返回 Aster</a>
        <button class="btn" :disabled="loading" @click="load">{{ loading ? '刷新中…' : '刷新' }}</button>
      </div>
    </header>

    <p v-if="!token" class="error">未检测到登录状态：请先在 Aster 主站登录后再访问数据中台。</p>

    <div class="toolbar">
      <button v-for="option in filters" :key="option.value" class="tab"
              :class="{ active: status === option.value }" @click="status = option.value; load()">
        {{ option.label }} <em>{{ option.count }}</em>
      </button>
      <span style="flex:1"></span>
      <span class="muted">👍 {{ counts.rating_up || 0 }} · 👎 {{ counts.rating_down || 0 }}</span>
    </div>

    <p v-if="error" class="error">{{ error }}</p>
    <p v-if="!loading && !items.length" class="empty card">还没有反馈。去智能体对话里对回答点 👍/👎。</p>

    <div v-else class="card">
      <table>
        <thead><tr><th>评价</th><th>问题 / 回答</th><th>理由</th><th>状态</th><th>时间</th><th>操作</th></tr></thead>
        <tbody>
          <tr v-for="item in items" :key="item.id">
            <td><span class="badge" :class="item.rating === 'up' ? 'success' : 'danger'">
              {{ item.rating === 'up' ? '👍 好评' : '👎 BadCase' }}</span></td>
            <td class="note" :title="detail(item)">{{ item.refs?.question || '（无问题快照）' }}</td>
            <td class="note">{{ item.reason || '—' }}</td>
            <td><span class="badge" :class="statusTone(item.status)">{{ statusLabel(item.status) }}</span></td>
            <td>{{ formatTime(item.createdAt) }}</td>
            <td class="row-actions">
              <button v-if="item.status !== 'converted'" class="btn" :disabled="busy" @click="convert(item)">
                {{ item.rating === 'up' ? '转 golden 用例' : '转回归用例' }}
              </button>
              <button v-if="item.status === 'open'" class="btn ghost" :disabled="busy"
                      @click="setStatus(item, 'ignored')">忽略</button>
              <button v-if="item.status === 'open'" class="btn ghost" :disabled="busy"
                      @click="setStatus(item, 'acked')">已知悉</button>
              <span v-if="item.status === 'converted'" class="muted mono">→ 已回流</span>
            </td>
          </tr>
        </tbody>
      </table>
    </div>
    <p v-if="nextCursor" style="text-align:center;margin-top:14px">
      <button class="btn" :disabled="loading" @click="load(nextCursor, true)">加载更多</button>
    </p>

    <p v-if="toast" class="toast">{{ toast }}</p>
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'

const API = '/api/ai/feedback'
const token = ref(localStorage.getItem('zwx_auth_token') || '')
const loading = ref(false)
const busy = ref(false)
const error = ref('')
const toast = ref('')
const items = ref([])
const counts = ref({})
const status = ref('')
const nextCursor = ref(null)

const STATUS = {
  open: ['待处理', 'warning'], acked: ['已知悉', 'info'],
  converted: ['已回流', 'success'], ignored: ['已忽略', 'muted']
}
function statusLabel(s) { return STATUS[s]?.[0] || s }
function statusTone(s) { return STATUS[s]?.[1] || 'muted' }
function formatTime(v) { return v ? new Date(v).toLocaleString('zh-CN', { hour12: false }) : '—' }
function detail(item) {
  const a = item.refs?.answer
  return a ? `回答：${a}` : ''
}
const filters = computed(() => [
  { value: '', label: '全部', count: items.value.length },
  { value: 'open', label: '待处理', count: items.value.filter(i => i.status === 'open').length },
  { value: 'converted', label: '已回流', count: items.value.filter(i => i.status === 'converted').length },
  { value: 'ignored', label: '已忽略', count: items.value.filter(i => i.status === 'ignored').length }
])

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

async function load(cursor = null, append = false) {
  if (!token.value) return
  loading.value = true
  error.value = ''
  try {
    const qs = new URLSearchParams({ limit: '30' })
    if (status.value) qs.set('status', status.value)
    if (cursor) qs.set('cursor', cursor)
    const data = await call('?' + qs.toString())
    items.value = append ? [...items.value, ...(data.items || [])] : (data.items || [])
    counts.value = data.counts || {}
    nextCursor.value = data.nextCursor
  } catch (e) {
    error.value = e.message
  } finally {
    loading.value = false
  }
}

async function setStatus(item, next) {
  busy.value = true
  try {
    await call(`/${item.id}/status`, { method: 'POST', body: JSON.stringify({ status: next }) })
    await load()
  } catch (e) { showToast('更新失败：' + e.message) } finally { busy.value = false }
}

async function convert(item) {
  busy.value = true
  try {
    const result = await call(`/${item.id}/convert`, { method: 'POST', body: '{}' })
    showToast(`已回流到「线上回流」数据集（用例 ${result.caseExternalId}），到评测台发布后即可跑回归`)
    await load()
  } catch (e) { showToast('回流失败：' + e.message) } finally { busy.value = false }
}

onMounted(load)
</script>
