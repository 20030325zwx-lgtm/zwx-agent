<template>
  <div class="drawer-mask" @click.self="$emit('close')">
    <aside class="drawer" style="width:min(560px,100%)">
      <div class="drawer-head">
        <div>
          <h2>新建实验</h2>
          <p class="muted">数据集版本 × 被测对象。被测对象是这次唯一要变的量。</p>
        </div>
        <button class="btn ghost" @click="$emit('close')">关闭</button>
      </div>

      <div class="section">
        <h3>1 · 评测集</h3>
        <select v-model="form.datasetId" class="select" style="width:100%">
          <option value="" disabled>选择已发布的数据集…</option>
          <option v-for="item in publishable" :key="item.id" :value="item.id">
            {{ item.name }}（{{ typeLabel(item.eval_type) }}）
          </option>
        </select>
        <div class="inline-row" style="margin-top:8px">
          <select v-model.number="form.versionNo" class="select" style="flex:1">
            <option v-if="!versions.length" :value="0" disabled>先选数据集</option>
            <option v-for="v in versions" :key="v.version_no" :value="v.version_no">
              v{{ v.version_no }}（{{ v.case_count }} 例）
            </option>
          </select>
          <span class="badge muted">{{ evalTypeLabel }}</span>
        </div>
      </div>

      <div class="section">
        <h3>2 · 被测对象</h3>
        <div class="picks">
          <button class="pick" :class="{ active: form.kind === 'live' }" @click="form.kind = 'live'">
            <strong>当前线上配置</strong>
            <span>走主站内部执行，测「现在线上这版」</span>
          </button>
          <button class="pick" :class="{ active: form.kind === 'model', disabled: !isPrompt }"
                  :disabled="!isPrompt" @click="form.kind = 'model'">
            <strong>指定模型</strong>
            <span>{{ isPrompt ? '同一段提示词，换模型跑' : '仅单轮直调数据集可用' }}</span>
          </button>
          <button class="pick" :class="{ active: form.kind === 'prompt', disabled: !isPrompt }"
                  :disabled="!isPrompt" @click="form.kind = 'prompt'">
            <strong>Prompt 资产版本</strong>
            <span>{{ isPrompt ? '指定 key@version，可再叠加模型' : '仅单轮直调数据集可用' }}</span>
          </button>
        </div>

        <div v-if="form.kind === 'model' || form.kind === 'prompt'" class="inline-row" style="margin-top:10px">
          <select v-model="form.modelName" class="select" style="flex:1">
            <option value="qwen-turbo">qwen-turbo</option>
            <option value="qwen-plus">qwen-plus</option>
            <option value="qwen-max">qwen-max</option>
          </select>
          <span class="muted">温度固定 0.1，保证可复现</span>
        </div>

        <div v-if="form.kind === 'prompt'" class="inline-row" style="margin-top:8px">
          <select v-model="form.assetId" class="select" style="flex:1" @change="loadAsset">
            <option value="" disabled>选择 Prompt 资产…</option>
            <option v-for="asset in prompts" :key="asset.id" :value="asset.id">
              {{ asset.asset_key }}（{{ asset.latest_version ? `v${asset.latest_version}` : '未发布' }}）
            </option>
          </select>
          <select v-model.number="form.promptVersion" class="select" style="width:130px">
            <option v-if="!assetVersions.length" :value="0" disabled>版本</option>
            <option v-for="v in assetVersions" :key="v.version_no" :value="v.version_no">v{{ v.version_no }}</option>
          </select>
        </div>
      </div>

      <div class="section">
        <h3>3 · 预算与并发</h3>
        <div class="grid-3">
          <label class="field">
            <span>最多用例</span>
            <input v-model.number="form.maxCases" class="input" type="number" min="0" placeholder="全部">
          </label>
          <label class="field">
            <span>成本上限（元）</span>
            <input v-model.number="form.maxCost" class="input" type="number" min="0" step="0.01" placeholder="不限">
          </label>
          <label class="field">
            <span>judge 抽样</span>
            <select v-model.number="form.judgeSampleRate" class="select">
              <option :value="1">100%</option>
              <option :value="0.5">50%</option>
              <option :value="0.2">20%</option>
              <option :value="0">只判失败样本</option>
            </select>
          </label>
        </div>
        <label class="field" style="margin-top:8px">
          <span>并发（越大越快，也越吃配额）</span>
          <input v-model.number="form.concurrency" class="input" type="number" min="1" max="8">
        </label>
        <input v-model="form.notes" class="input" style="width:100%;margin-top:8px"
               placeholder="备注：这次改了什么（会写进报告，方便以后回看）">
      </div>

      <div class="summary">
        <p class="summary-main">{{ summary }}</p>
        <p class="muted">失败样本始终全量 judge；通过样本按抽样比例运行。</p>
        <button class="btn primary" :disabled="submitting || !ready" @click="submit">
          {{ submitting ? '入队中…' : '创建并入队' }}
        </button>
      </div>
    </aside>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { api } from '../api'
import { showToast } from '../toast'
import { typeLabel } from '../format'

const props = defineProps({
  datasets: { type: Array, default: () => [] },
  prompts: { type: Array, default: () => [] }
})
const emit = defineEmits(['close', 'created'])

const form = reactive({
  datasetId: '',
  versionNo: 0,
  kind: 'live',
  modelName: 'qwen-plus',
  assetId: '',
  promptVersion: 0,
  concurrency: 1,
  maxCases: null,
  maxCost: null,
  judgeSampleRate: 1,
  notes: ''
})
const detail = ref(null)
const assetDetail = ref(null)
const submitting = ref(false)

const publishable = computed(() => props.datasets.filter((item) => item.latest_version > 0))
const versions = computed(() => detail.value?.versions || [])
const assetVersions = computed(() => assetDetail.value?.versions || [])
const selectedDataset = computed(() => props.datasets.find((item) => item.id === form.datasetId))
const isPrompt = computed(() => selectedDataset.value?.eval_type === 'prompt')
const evalTypeLabel = computed(() => typeLabel(selectedDataset.value?.eval_type))
const ready = computed(() => {
  if (!form.datasetId || !form.versionNo) return false
  if (form.kind === 'prompt') return Boolean(form.assetId && form.promptVersion)
  return true
})

const targetText = computed(() => {
  if (form.kind === 'live') return '当前线上配置'
  if (form.kind === 'model') return form.modelName
  const asset = props.prompts.find((item) => item.id === form.assetId)
  const key = asset?.asset_key || 'prompt'
  return `${key}@v${form.promptVersion || '?'} × ${form.modelName}`
})

const summary = computed(() => {
  const name = selectedDataset.value?.name || '未选数据集'
  const count = versions.value.find((v) => v.version_no === form.versionNo)?.case_count ?? '?'
  const cap = [
    form.maxCases ? `最多 ${form.maxCases} 例` : '',
    form.maxCost ? `≤ ¥${form.maxCost}` : ''
  ].filter(Boolean).join('，')
  return `${name} v${form.versionNo || '?'} · ${targetText.value} · ${count} 例${cap ? ` · ${cap}` : ''}`
})

watch(() => form.datasetId, async (id) => {
  detail.value = null
  form.versionNo = 0
  if (!id) return
  try {
    detail.value = await api.get(`/datasets/${id}`)
    form.versionNo = detail.value.versions?.[0]?.version_no || 0
    if (!isPrompt.value) form.kind = 'live'
  } catch (error) {
    showToast(`读取数据集失败：${error.message}`)
  }
})

watch(() => form.assetId, () => {
  form.promptVersion = 0
})

async function loadAsset() {
  if (!form.assetId) return
  try {
    assetDetail.value = await api.get(`/prompts/${form.assetId}`)
    form.promptVersion = assetDetail.value.versions?.[0]?.version_no || 0
  } catch (error) {
    showToast(`读取 Prompt 资产失败：${error.message}`)
  }
}

function buildTarget() {
  if (form.kind === 'live') return { kind: 'live' }
  const target = {
    kind: form.kind,
    model: { provider: 'dashscope', modelName: form.modelName }
  }
  if (form.kind === 'prompt') {
    const asset = props.prompts.find((item) => item.id === form.assetId)
    target.prompt = { assetKey: asset?.asset_key, versionNo: form.promptVersion }
  }
  return target
}

async function submit() {
  submitting.value = true
  try {
    await api.post('/experiments', {
      datasetId: form.datasetId,
      versionNo: form.versionNo,
      notes: form.notes,
      target: buildTarget(),
      concurrency: form.concurrency,
      budget: {
        maxCases: form.maxCases || 0,
        maxCost: form.maxCost || null,
        judgeSampleRate: form.judgeSampleRate
      }
    })
    emit('created')
  } catch (error) {
    showToast(`创建失败：${error.message}`)
  } finally {
    submitting.value = false
  }
}

onMounted(() => {
  if (publishable.value.length === 1) form.datasetId = publishable.value[0].id
})
</script>
