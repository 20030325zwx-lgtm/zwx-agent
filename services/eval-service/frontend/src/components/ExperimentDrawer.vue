<template>
  <div class="drawer-mask" @click.self="$emit('close')">
    <aside class="drawer">
      <div class="drawer-head">
        <div v-if="detail">
          <h2>{{ datasetName(datasets, detail.datasetId) }} <span class="mono">v{{ detail.datasetVersionNo }}</span></h2>
          <p class="muted">
            <span class="badge" :class="statusTone(detail.status)">{{ statusLabel(detail.status) }}</span>
            · {{ typeLabel(detail.evalType) }} · {{ formatTime(detail.createdAt) }}
          </p>
        </div>
        <div v-else><h2>实验详情</h2></div>
        <button class="btn ghost" @click="$emit('close')">关闭</button>
      </div>

      <p v-if="error" class="error">{{ error }}</p>

      <template v-if="detail">
        <div class="metrics">
          <div class="metric">
            <h3>通过率</h3>
            <strong>{{ passRate(detail.totals) }}</strong>
            <p>{{ totalsText }}</p>
          </div>
          <div class="metric">
            <h3>judge 均分</h3>
            <strong>{{ detail.totals?.judgeAvg ?? '—' }}</strong>
            <p>抽样 {{ detail.totals?.judgeCount ?? 0 }} 例</p>
          </div>
          <div class="metric">
            <h3>成本</h3>
            <strong>{{ money(detail.totals?.totalCost) }}</strong>
            <p>单例 {{ money(detail.totals?.avgCostPerCase) }}</p>
          </div>
          <div class="metric">
            <h3>平均延迟</h3>
            <strong>{{ detail.totals?.avgLatencyMs != null ? detail.totals.avgLatencyMs + ' ms' : '—' }}</strong>
            <p>critical 未过 {{ detail.totals?.criticalFailed ?? 0 }}/{{ detail.totals?.criticalTotal ?? 0 }}</p>
          </div>
        </div>

        <div class="section">
          <h3>被测对象</h3>
          <div class="kv">
            <span>对象</span><strong>{{ detail.target || '—' }}</strong>
          </div>
          <div class="kv" v-if="detail.budget">
            <span>预算</span>
            <em>{{ budgetText }}</em>
          </div>
          <div class="kv" v-if="detail.resumeOf">
            <span>续跑来源</span><em class="mono">{{ detail.resumeOf.slice(0, 8) }}</em>
          </div>
          <div class="kv" v-if="detail.candidate?.prompt?.systemSha256">
            <span>Prompt 快照</span>
            <em class="mono">{{ detail.candidate.prompt.assetKey }}@v{{ detail.candidate.prompt.versionNo }}
              · {{ detail.candidate.prompt.systemSha256 }}</em>
          </div>
        </div>

        <div class="section row-actions" style="flex-wrap:wrap">
          <button v-if="detail.status === 'queued' || detail.status === 'running'" class="btn"
                  :disabled="busy" @click="cancel">取消实验</button>
          <button v-if="['failed', 'cancelled', 'succeeded'].includes(detail.status)" class="btn"
                  :disabled="busy" @click="resume">续跑（跳过已完成）</button>
          <button class="btn" :disabled="busy" @click="exportCsv">导出 CSV</button>
          <span v-if="detail.status === 'running' || detail.status === 'queued'" class="muted">
            自动刷新中 · {{ latestEvent }}
          </span>
        </div>

        <p v-if="detail.errorMessage" class="error">{{ detail.errorCode }}：{{ detail.errorMessage }}</p>

        <div class="tabs" style="margin-top:4px">
          <button class="tab" :class="{ active: pane === 'results' }" @click="pane = 'results'">结果</button>
          <button class="tab" :class="{ active: pane === 'report' }" @click="pane = 'report'; loadReport()">报告</button>
        </div>

        <div v-if="pane === 'results'" class="section">
          <p v-if="!results.length" class="muted">还没有结果。队列执行中会逐例出现。</p>
          <table v-else>
            <thead><tr><th>用例</th><th>结果</th><th>断言</th><th>延迟</th><th>成本</th></tr></thead>
            <tbody>
              <tr v-for="row in results" :key="row.case_external_id">
                <td class="mono">
                  {{ row.case_external_id }}
                  <span v-if="row.severity === 'critical'" class="badge danger" style="margin-left:4px">critical</span>
                </td>
                <td><span class="badge" :class="boolTone(row.passed)">{{ boolLabel(row.passed) }}</span></td>
                <td>
                  <div class="chips">
                    <span v-for="check in parseJson(row.checks, [])" :key="check.name" class="chip"
                          :class="{ failed: check.passed === false }">{{ check.name }}</span>
                    <span v-if="judgeOf(row)" class="chip judge">judge {{ judgeOf(row).average }}</span>
                  </div>
                </td>
                <td class="mono">{{ row.latency_ms ?? '—' }}</td>
                <td class="mono">{{ money(row.cost_amount) }}</td>
              </tr>
            </tbody>
          </table>
        </div>

        <div v-if="pane === 'report'" class="section">
          <div class="toolbar" style="margin-bottom:8px">
            <select v-model="groupBy" class="select" @change="loadReport">
              <option value="tag">按标签</option>
              <option value="check">按断言</option>
              <option value="dimension">按 judge 维度</option>
            </select>
            <span class="muted">失败集中在哪一层，一眼看出来</span>
          </div>
          <table v-if="report">
            <thead>
              <tr v-if="groupBy === 'dimension'"><th>维度</th><th>样本</th><th>平均分</th></tr>
              <tr v-else><th>{{ groupBy === 'tag' ? '标签' : '断言' }}</th><th>用例</th><th>通过率</th>
                <th>{{ groupBy === 'tag' ? '成本' : '失败次数' }}</th></tr>
            </thead>
            <tbody>
              <template v-if="groupBy === 'dimension'">
                <tr v-for="group in report.groups" :key="group.key">
                  <td>{{ group.key }}</td>
                  <td>{{ group.samples }}</td>
                  <td class="mono">{{ group.average }}</td>
                </tr>
              </template>
              <template v-else>
                <tr v-for="group in report.groups" :key="group.key">
                  <td>{{ group.key }}</td>
                  <td>{{ group.passed }}/{{ group.total }}</td>
                  <td>{{ group.passRate == null ? '—' : Math.round(group.passRate * 1000) / 10 + '%' }}</td>
                  <td class="mono">{{ groupBy === 'tag' ? money(group.totalCost) : group.failedChecks }}</td>
                </tr>
              </template>
            </tbody>
          </table>
        </div>
      </template>
    </aside>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { api } from '../api'
import { showToast } from '../toast'
import {
  boolLabel, boolTone, datasetName, formatTime, money, parseJson, passRate,
  statusLabel, statusTone, typeLabel
} from '../format'

const props = defineProps({
  experimentId: { type: String, required: true },
  datasets: { type: Array, default: () => [] }
})
const emit = defineEmits(['close'])

const detail = ref(null)
const results = ref([])
const report = ref(null)
const groupBy = ref('tag')
const pane = ref('results')
const error = ref('')
const busy = ref(false)
const latestEvent = ref('')
let timer = null

const totalsText = computed(() => {
  const totals = detail.value?.totals
  if (!totals || totals.total == null) return '—'
  return `${totals.passed}/${totals.total} 通过` + (totals.skipped ? `（跳过 ${totals.skipped}）` : '')
})

const budgetText = computed(() => {
  const budget = detail.value?.budget || {}
  const parts = []
  if (budget.maxCases) parts.push(`最多 ${budget.maxCases} 例`)
  if (budget.maxCost) parts.push(`≤ ¥${budget.maxCost}`)
  parts.push(`judge ${Math.round((budget.judgeSampleRate ?? 1) * 100)}%`)
  return parts.join(' · ')
})

function judgeOf(row) {
  return parseJson(row.judge, null)
}

async function load() {
  try {
    detail.value = await api.get(`/experiments/${props.experimentId}`)
    results.value = await api.get(`/experiments/${props.experimentId}/results`)
    error.value = ''
    if (['queued', 'running'].includes(detail.value.status)) schedule()
  } catch (exception) {
    error.value = exception.message
  }
}

async function loadReport() {
  try {
    report.value = await api.get(`/experiments/${props.experimentId}/report?groupBy=${groupBy.value}`)
  } catch (exception) {
    showToast(`报告读取失败：${exception.message}`)
  }
}

function schedule() {
  stop()
  timer = setTimeout(async () => {
    if (document.visibilityState === 'hidden') return schedule()
    try {
      const fresh = await api.get(`/experiments/${props.experimentId}`)
      const page = await api.get(`/experiments/${props.experimentId}/events?afterSeq=0&limit=20`)
      const items = page.items || []
      latestEvent.value = items.length ? `${items[items.length - 1].type} #${items[items.length - 1].seq}` : ''
      detail.value = fresh
      if (!['queued', 'running'].includes(fresh.status)) {
        results.value = await api.get(`/experiments/${props.experimentId}/results`)
        return
      }
    } catch {
      // 下一轮重试
    }
    schedule()
  }, 3000)
}

function stop() {
  if (timer) {
    clearTimeout(timer)
    timer = null
  }
}

async function cancel() {
  busy.value = true
  try {
    await api.post(`/experiments/${props.experimentId}/cancel`)
    await load()
  } catch (exception) {
    showToast(`取消失败：${exception.message}`)
  } finally {
    busy.value = false
  }
}

async function resume() {
  busy.value = true
  try {
    const created = await api.post(`/experiments/${props.experimentId}/resume`)
    showToast(`已创建续跑实验 ${created.id.slice(0, 8)}`)
    emit('close')
  } catch (exception) {
    showToast(`续跑失败：${exception.message}`)
  } finally {
    busy.value = false
  }
}

async function exportCsv() {
  try {
    const csv = await api.csv(`/experiments/${props.experimentId}/export`)
    const blob = new Blob([csv], { type: 'text/csv;charset=utf-8' })
    const url = URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.download = `eval-${props.experimentId.slice(0, 8)}.csv`
    link.click()
    URL.revokeObjectURL(url)
  } catch (exception) {
    showToast(`导出失败：${exception.message}`)
  }
}

onMounted(load)
onBeforeUnmount(stop)
</script>
