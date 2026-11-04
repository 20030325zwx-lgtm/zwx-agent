<template>
  <section>
    <div class="toolbar">
      <span class="muted">最近的真实运行。挑一条，直接沉淀成回归用例——不用复制粘贴。</span>
      <span style="flex:1"></span>
      <button class="btn" :disabled="loading" @click="load">{{ loading ? '刷新中…' : '刷新' }}</button>
    </div>

    <p v-if="error" class="error">{{ error }}</p>
    <p v-if="!loading && !runs.length" class="empty card">还没有可浏览的运行。</p>

    <div v-else class="card">
      <table>
        <thead><tr><th>时间</th><th>问题</th><th>状态</th><th>token</th><th>会话</th><th></th></tr></thead>
        <tbody>
          <tr v-for="run in runs" :key="run.id">
            <td class="mono">{{ formatTime(run.updatedAt || run.createdAt) }}</td>
            <td class="note" :title="run.input">{{ run.input }}</td>
            <td>
              <span class="badge" :class="run.status === 'COMPLETED' ? 'success'
                : run.status === 'FAILED' ? 'danger' : 'muted'">{{ run.status }}</span>
            </td>
            <td class="mono">{{ (run.promptTokens || 0) + (run.completionTokens || 0) }}</td>
            <td class="note">{{ run.conversationTitle || run.conversationId }}</td>
            <td><button class="btn ghost" @click="open(run)">轨迹</button></td>
          </tr>
        </tbody>
      </table>
    </div>

    <div v-if="selected" class="drawer-mask" @click.self="selected = null">
      <aside class="drawer">
        <div class="drawer-head">
          <div>
            <h2>运行轨迹</h2>
            <p class="muted">
              <span class="mono">{{ selected.id }}</span>
              · <span class="badge" :class="selected.status === 'COMPLETED' ? 'success' : 'muted'">
                {{ selected.status }}</span>
            </p>
          </div>
          <button class="btn ghost" @click="selected = null">关闭</button>
        </div>

        <div class="section">
          <h3>问题</h3>
          <p class="answer">{{ selected.input }}</p>
        </div>

        <div class="section">
          <h3>回答</h3>
          <p class="answer">{{ trajectory?.checkpoint?.finalAnswer || trajectory?.checkpoint?.partialAnswer || '（无回答）' }}</p>
        </div>

        <div v-if="planSteps.length" class="section">
          <h3>计划（{{ planSteps.length }} 步）</h3>
          <ol class="plan-list">
            <li v-for="step in planSteps" :key="step.id || step.title">
              <strong>{{ step.title }}</strong>
              <span class="muted" v-if="step.detail"> · {{ step.detail }}</span>
              <span class="chip" v-if="step.dependsOn?.length">依赖 {{ step.dependsOn.join(',') }}</span>
            </li>
          </ol>
        </div>

        <div class="section" v-if="toolCalls.length">
          <h3>工具调用（{{ toolCalls.length }}）</h3>
          <table>
            <thead><tr><th>工具</th><th>状态</th><th>时间</th></tr></thead>
            <tbody>
              <tr v-for="(call, index) in toolCalls" :key="index">
                <td class="mono">{{ call.tool }}</td>
                <td>{{ call.status }}</td>
                <td class="mono">{{ formatTime(call.createdAt) }}</td>
              </tr>
            </tbody>
          </table>
        </div>

        <div class="section" v-if="llmCalls.length">
          <h3>模型调用（{{ llmCalls.length }}）</h3>
          <table>
            <thead><tr><th>阶段</th><th>模型</th><th>tokens</th><th>延迟</th></tr></thead>
            <tbody>
              <tr v-for="(call, index) in llmCalls" :key="index">
                <td>{{ call.phase }}</td>
                <td class="mono">{{ call.model }}</td>
                <td class="mono">{{ call.promptTokens }}/{{ call.completionTokens }}</td>
                <td class="mono">{{ call.latencyMs }} ms</td>
              </tr>
            </tbody>
          </table>
        </div>

        <div class="section">
          <h3>沉淀</h3>
          <div class="inline-row">
            <input v-model="tags" class="input" style="flex:1" placeholder="标签，逗号分隔（默认 trace）">
            <button class="btn primary" :disabled="busy" @click="toCase">
              {{ busy ? '处理中…' : '建回归用例' }}
            </button>
          </div>
          <p v-if="created" class="muted">
            已写入评测集草稿：<span class="mono">{{ created.caseExternalId }}</span>
            （数据集 {{ String(created.datasetId).slice(0, 8) }}），到评测台发布后即可跑回归。
          </p>
        </div>
      </aside>
    </div>
  </section>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'

const API = '/api/ai/feedback/runs'
const token = ref(localStorage.getItem('zwx_auth_token') || '')
const runs = ref([])
const loading = ref(false)
const busy = ref(false)
const error = ref('')
const selected = ref(null)
const trajectory = ref(null)
const tags = ref('trace')
const created = ref(null)

const planSteps = computed(() => {
  const plan = trajectory.value?.checkpoint?.plan
  return Array.isArray(plan) ? plan : []
})
const toolCalls = computed(() => trajectory.value?.toolCalls || [])
const llmCalls = computed(() => trajectory.value?.llmCalls || [])

function formatTime(value) {
  return value ? new Date(value).toLocaleString('zh-CN', { hour12: false }) : '—'
}

async function call(path, options = {}) {
  const response = await fetch(API + path, {
    ...options,
    headers: {
      'Content-Type': 'application/json',
      ...(token.value ? { Authorization: `Bearer ${token.value}` } : {})
    }
  })
  const text = await response.text()
  const data = text ? JSON.parse(text) : {}
  if (!response.ok) throw new Error(data.error || `HTTP ${response.status}`)
  return data
}

async function load() {
  if (!token.value) return
  loading.value = true
  error.value = ''
  try {
    runs.value = await call('?limit=30')
  } catch (exception) {
    error.value = exception.message
  } finally {
    loading.value = false
  }
}

async function open(run) {
  selected.value = run
  trajectory.value = null
  created.value = null
  try {
    trajectory.value = await call(`/${encodeURIComponent(run.id)}/trajectory`)
  } catch (exception) {
    error.value = exception.message
  }
}

async function toCase() {
  busy.value = true
  try {
    created.value = await call(`/${encodeURIComponent(selected.value.id)}/to-case`, {
      method: 'POST',
      body: JSON.stringify({
        tags: tags.value.split(',').map((tag) => tag.trim()).filter(Boolean)
      })
    })
  } catch (exception) {
    error.value = exception.message
  } finally {
    busy.value = false
  }
}

onMounted(load)
</script>
