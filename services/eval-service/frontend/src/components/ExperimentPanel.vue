<template>
  <section>
    <div class="toolbar">
      <select v-model="compareLeft" class="select">
        <option value="">对比 A…</option>
        <option v-for="item in finished" :key="item.id" :value="item.id">{{ label(item) }}</option>
      </select>
      <span class="muted">vs</span>
      <select v-model="compareRight" class="select">
        <option value="">对比 B…</option>
        <option v-for="item in finished" :key="item.id" :value="item.id">{{ label(item) }}</option>
      </select>
      <button class="btn" :disabled="!compareLeft || !compareRight || compareLeft === compareRight || comparing"
              @click="runCompare">{{ comparing ? '对比中…' : '对比' }}</button>
      <span style="flex:1"></span>
      <button class="btn" :disabled="loading" @click="load">{{ loading ? '刷新中…' : '刷新' }}</button>
      <button class="btn primary" @click="createOpen = true">新建实验</button>
    </div>

    <p v-if="error" class="error">{{ error }}</p>

    <CompareReport v-if="comparison" :comparison="comparison" :labels="compareLabels" style="margin-bottom:14px" />

    <p v-if="!loading && !experiments.length" class="empty card">
      还没有实验。先到「数据集」导入用例并发布版本，再回来建实验。
    </p>
    <div v-else class="card">
      <table>
        <thead>
          <tr><th>状态</th><th>数据集</th><th>被测对象</th><th>通过率</th><th>成本</th><th>critical</th><th>时间</th><th></th></tr>
        </thead>
        <tbody>
          <tr v-for="item in experiments" :key="item.id">
            <td><span class="badge" :class="statusTone(item.status)">{{ statusLabel(item.status) }}</span></td>
            <td>
              {{ datasetName(datasets, item.datasetId) }}
              <span class="mono">v{{ item.datasetVersionNo }}</span>
              <span v-if="item.resumeOf" class="badge muted" style="margin-left:4px">续跑</span>
            </td>
            <td class="note" :title="item.target">{{ item.target || '—' }}</td>
            <td>{{ passRate(item.totals) }}</td>
            <td class="mono">{{ money(item.totals?.totalCost) }}</td>
            <td>
              <span v-if="item.totals?.criticalTotal" class="mono"
                    :class="{ danger: item.totals.criticalFailed > 0 }">
                {{ item.totals.criticalFailed }}/{{ item.totals.criticalTotal }}
              </span>
              <span v-else class="muted">—</span>
            </td>
            <td class="mono">{{ formatTime(item.createdAt) }}</td>
            <td><button class="btn ghost" @click="openId = item.id">详情</button></td>
          </tr>
        </tbody>
      </table>
    </div>

    <CreateExperimentDialog v-if="createOpen" :datasets="datasets" :prompts="prompts"
                            @close="createOpen = false" @created="onCreated" />
    <ExperimentDrawer v-if="openId" :experiment-id="openId" :datasets="datasets" @close="onCloseDrawer" />
  </section>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { api } from '../api'
import { showToast } from '../toast'
import {
  formatTime, money, passRate, statusLabel, statusTone, datasetName
} from '../format'
import CompareReport from './CompareReport.vue'
import CreateExperimentDialog from './CreateExperimentDialog.vue'
import ExperimentDrawer from './ExperimentDrawer.vue'

const experiments = ref([])
const datasets = ref([])
const prompts = ref([])
const loading = ref(false)
const error = ref('')
const createOpen = ref(false)
const openId = ref('')
const comparison = ref(null)
const comparing = ref(false)
const compareLeft = ref('')
const compareRight = ref('')

const finished = computed(() => experiments.value.filter((item) => ['succeeded', 'failed'].includes(item.status)))

function label(item) {
  return `${datasetName(datasets.value, item.datasetId)} v${item.datasetVersionNo} · ${item.target || item.id.slice(0, 8)}`
}

const compareLabels = computed(() => {
  const left = experiments.value.find((item) => item.id === compareLeft.value)
  const right = experiments.value.find((item) => item.id === compareRight.value)
  return {
    left: left ? label(left) : 'A',
    right: right ? label(right) : 'B'
  }
})

async function load() {
  loading.value = true
  error.value = ''
  try {
    const [list, ds, ps] = await Promise.all([
      api.get('/experiments?limit=40'),
      api.get('/datasets'),
      api.get('/prompts')
    ])
    experiments.value = list.items || []
    datasets.value = ds || []
    prompts.value = ps || []
  } catch (exception) {
    error.value = exception.message
  } finally {
    loading.value = false
  }
}

async function runCompare() {
  comparing.value = true
  try {
    comparison.value = await api.get(`/experiments/compare?left=${compareLeft.value}&right=${compareRight.value}`)
  } catch (exception) {
    showToast(`对比失败：${exception.message}`)
  } finally {
    comparing.value = false
  }
}

async function onCreated() {
  createOpen.value = false
  await load()
  showToast('实验已入队')
}

async function onCloseDrawer() {
  openId.value = ''
  await load()
}

onMounted(load)
</script>
