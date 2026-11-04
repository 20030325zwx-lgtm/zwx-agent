<template>
  <section>
    <div class="toolbar">
      <input v-model="form.name" class="input" placeholder="数据集名称">
      <select v-model="form.evalType" class="select">
        <option value="intent">意图路由</option>
        <option value="retrieval">检索命中</option>
        <option value="prompt">单轮直调</option>
        <option value="task">全链路任务</option>
      </select>
      <input v-model="form.description" class="input" placeholder="描述（可选）">
      <button class="btn primary" :disabled="busy || !form.name" @click="createDataset">创建数据集</button>
      <span style="flex:1"></span>
      <button class="btn" :disabled="loading" @click="load">{{ loading ? '刷新中…' : '刷新' }}</button>
    </div>

    <p v-if="error" class="error">{{ error }}</p>

    <p v-if="!loading && !datasets.length" class="empty card">
      还没有数据集。创建后可以导入 JSON、单条录入，或从 BadCase 回流。
    </p>
    <div v-else class="card">
      <table>
        <thead>
          <tr><th>名称</th><th>类型</th><th>最新版本</th><th>草稿</th><th>已发布</th><th>创建时间</th><th></th></tr>
        </thead>
        <tbody>
          <tr v-for="item in datasets" :key="item.id">
            <td>{{ item.name }}</td>
            <td>{{ typeLabel(item.eval_type) }}</td>
            <td>{{ item.latest_version > 0 ? `v${item.latest_version}` : '未发布' }}</td>
            <td>{{ item.draft_cases }}</td>
            <td>{{ item.published_cases }}</td>
            <td class="mono">{{ formatTime(item.created_at) }}</td>
            <td><button class="btn ghost" @click="open(item.id)">详情</button></td>
          </tr>
        </tbody>
      </table>
    </div>

    <div v-if="detail" class="drawer-mask" @click.self="close">
      <aside class="drawer">
        <div class="drawer-head">
          <div>
            <h2>{{ detail.name }}</h2>
            <p class="muted">{{ typeLabel(detail.eval_type) }} · {{ detail.description || '无描述' }}</p>
          </div>
          <button class="btn ghost" @click="close">关闭</button>
        </div>

        <div class="section">
          <h3>发布版本</h3>
          <p v-if="!detail.versions?.length" class="muted">尚未发布；实验只能引用已发布版本。</p>
          <table v-else>
            <thead><tr><th>版本</th><th>用例</th><th>发布时间</th><th></th></tr></thead>
            <tbody>
              <tr v-for="version in detail.versions" :key="version.version_no">
                <td class="mono">v{{ version.version_no }}</td>
                <td>{{ version.case_count }}</td>
                <td class="mono">{{ formatTime(version.published_at) }}</td>
                <td class="row-actions">
                  <button class="btn ghost" @click="loadCases(version.version_no)">查看</button>
                  <button class="btn ghost" @click="forkVersion(version.version_no)">fork 回草稿</button>
                </td>
              </tr>
            </tbody>
          </table>
        </div>

        <div v-if="trend.length" class="section">
          <h3>历史趋势</h3>
          <table>
            <thead><tr><th>时间</th><th>被测对象</th><th>通过率</th><th>judge</th><th>成本</th></tr></thead>
            <tbody>
              <tr v-for="point in trend" :key="point.id">
                <td class="mono">{{ formatTime(point.createdAt) }}</td>
                <td class="note">{{ point.target }}</td>
                <td>{{ point.passRate == null ? '—' : Math.round(point.passRate * 1000) / 10 + '%' }}</td>
                <td class="mono">{{ point.judgeAvg ?? '—' }}</td>
                <td class="mono">{{ money(point.totalCost) }}</td>
              </tr>
            </tbody>
          </table>
        </div>

        <div class="section">
          <h3>导入用例到草稿</h3>
          <p class="muted">支持数组或 {cases:[...]}，兼容 task-eval-v1 / knowledge-intent-eval-v1 字段。</p>
          <textarea v-model="importText" class="textarea code" rows="6"
                    style="width:100%"
                    placeholder='[{"id":"intent-001","question":"你好","expectedIntent":"闲聊问答"}]'></textarea>
          <div class="row-actions" style="margin-top:8px">
            <button class="btn primary" :disabled="busy || !importText.trim()" @click="importCases">导入</button>
            <button class="btn" :disabled="busy" @click="publish">发布为新版本</button>
          </div>
        </div>

        <div class="section">
          <div class="toolbar" style="margin-bottom:8px">
            <h3 style="margin:0">用例（{{ casesVersion === 0 ? '草稿' : `v${casesVersion}` }}）</h3>
            <span style="flex:1"></span>
            <button v-if="casesVersion !== 0" class="btn ghost" @click="loadCases(0)">回到草稿</button>
            <button v-if="casesVersion === 0" class="btn" @click="startAdd">新增用例</button>
          </div>

          <div v-if="editor.open" class="editor">
            <div class="inline-row">
              <input v-model="editor.externalId" class="input" style="flex:1" placeholder="用例 ID（留空自动生成）"
                     :disabled="editor.mode === 'edit'">
              <input v-model="editor.tags" class="input" style="width:150px" placeholder="标签，逗号分隔">
              <select v-model="editor.severity" class="select" style="width:110px">
                <option value="normal">normal</option>
                <option value="critical">critical</option>
              </select>
            </div>
            <textarea v-model="editor.payloadText" class="textarea code" rows="6" style="width:100%;margin-top:6px"
                      placeholder='{"question":"…","expectedIntent":"…"}'></textarea>
            <div class="row-actions" style="margin-top:6px">
              <button class="btn primary" :disabled="busy" @click="saveCase">
                {{ editor.mode === 'edit' ? '保存' : '添加到草稿' }}
              </button>
              <button class="btn ghost" @click="editor.open = false">取消</button>
            </div>
          </div>

          <p v-if="!cases.length" class="muted">草稿为空。</p>
          <table v-else>
            <thead><tr><th>ID</th><th>内容</th><th>标签</th><th></th></tr></thead>
            <tbody>
              <tr v-for="row in cases" :key="row.external_id">
                <td class="mono">
                  {{ row.external_id }}
                  <span v-if="row.severity === 'critical'" class="badge danger" style="margin-left:4px">critical</span>
                </td>
                <td class="note" :title="payloadText(row)">{{ caseSummary(payloadOf(row)) }}</td>
                <td class="muted">{{ tagText(row) || '—' }}</td>
                <td v-if="casesVersion === 0" class="row-actions">
                  <button class="btn ghost" @click="startEdit(row)">编辑</button>
                  <button class="btn ghost" @click="removeCase(row.external_id)">删除</button>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
      </aside>
    </div>
  </section>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { api } from '../api'
import { showToast } from '../toast'
import { caseSummary, formatTime, money, parseJson, typeLabel } from '../format'

const datasets = ref([])
const loading = ref(false)
const busy = ref(false)
const error = ref('')
const form = reactive({ name: '', evalType: 'intent', description: '' })

const detail = ref(null)
const cases = ref([])
const casesVersion = ref(0)
const importText = ref('')
const trend = ref([])
const editor = reactive({ open: false, mode: 'add', externalId: '', tags: '', severity: 'normal', payloadText: '' })

async function load() {
  loading.value = true
  error.value = ''
  try {
    datasets.value = await api.get('/datasets')
  } catch (exception) {
    error.value = exception.message
  } finally {
    loading.value = false
  }
}

async function createDataset() {
  busy.value = true
  try {
    await api.post('/datasets', { ...form })
    form.name = ''
    form.description = ''
    await load()
    showToast('数据集已创建')
  } catch (exception) {
    showToast(`创建失败：${exception.message}`)
  } finally {
    busy.value = false
  }
}

async function open(id) {
  try {
    detail.value = await api.get(`/datasets/${id}`)
    cases.value = []
    casesVersion.value = 0
    editor.open = false
    await Promise.all([loadCases(0), loadTrend()])
  } catch (exception) {
    showToast(exception.message)
  }
}

function close() {
  detail.value = null
  cases.value = []
  trend.value = []
  editor.open = false
}

async function loadCases(version) {
  try {
    cases.value = await api.get(`/datasets/${detail.value.id}/cases?version=${version}`)
    casesVersion.value = version
  } catch (exception) {
    showToast(`读取用例失败：${exception.message}`)
  }
}

async function loadTrend() {
  try {
    const data = await api.get(`/datasets/${detail.value.id}/trend?limit=10`)
    trend.value = data.points || []
  } catch {
    trend.value = []
  }
}

async function refreshDetail() {
  detail.value = await api.get(`/datasets/${detail.value.id}`)
  await load()
}

async function importCases() {
  busy.value = true
  try {
    const parsed = JSON.parse(importText.value)
    const body = Array.isArray(parsed) ? parsed : parsed.cases
    const result = await api.post(`/datasets/${detail.value.id}/cases/import`, { cases: body })
    showToast(`导入完成：新增 ${result.imported}，更新 ${result.updated}`)
    importText.value = ''
    await refreshDetail()
    await loadCases(0)
  } catch (exception) {
    showToast(`导入失败：${exception.message}`)
  } finally {
    busy.value = false
  }
}

async function publish() {
  busy.value = true
  try {
    const result = await api.post(`/datasets/${detail.value.id}/publish`)
    showToast(`已发布 v${result.version}`)
    await refreshDetail()
  } catch (exception) {
    showToast(`发布失败：${exception.message}`)
  } finally {
    busy.value = false
  }
}

async function forkVersion(version) {
  if (!window.confirm(`用 v${version} 覆盖当前草稿？`)) return
  busy.value = true
  try {
    const result = await api.post(`/datasets/${detail.value.id}/versions/${version}/fork?confirm=true`)
    showToast(`已 fork：导入 ${result.imported} 条（覆盖 ${result.replaced} 条）`)
    await refreshDetail()
    await loadCases(0)
  } catch (exception) {
    showToast(`fork 失败：${exception.message}`)
  } finally {
    busy.value = false
  }
}

function startAdd() {
  editor.open = true
  editor.mode = 'add'
  editor.externalId = ''
  editor.tags = ''
  editor.severity = 'normal'
  editor.payloadText = payloadExample(detail.value?.eval_type)
}

function startEdit(row) {
  editor.open = true
  editor.mode = 'edit'
  editor.externalId = row.external_id
  editor.tags = tagText(row)
  editor.severity = row.severity || 'normal'
  editor.payloadText = payloadText(row)
}

async function saveCase() {
  let payload
  try {
    payload = JSON.parse(editor.payloadText)
  } catch {
    showToast('payload 不是合法 JSON')
    return
  }
  busy.value = true
  try {
    const tags = editor.tags.split(',').map((tag) => tag.trim()).filter(Boolean)
    if (editor.mode === 'edit') {
      await api.put(`/datasets/${detail.value.id}/cases/${encodeURIComponent(editor.externalId)}`,
        { payload, tags, severity: editor.severity })
    } else {
      await api.post(`/datasets/${detail.value.id}/cases`,
        { externalId: editor.externalId || undefined, payload, tags, severity: editor.severity })
    }
    editor.open = false
    await refreshDetail()
    await loadCases(0)
    showToast('已保存')
  } catch (exception) {
    showToast(`保存失败：${exception.message}`)
  } finally {
    busy.value = false
  }
}

async function removeCase(externalId) {
  if (!window.confirm(`删除草稿用例 ${externalId}？`)) return
  try {
    await api.del(`/datasets/${detail.value.id}/cases/${encodeURIComponent(externalId)}`)
    await refreshDetail()
    await loadCases(0)
  } catch (exception) {
    showToast(`删除失败：${exception.message}`)
  }
}

function payloadOf(row) {
  return typeof row.payload === 'string' ? parseJson(row.payload, {}) : (row.payload || {})
}

function payloadText(row) {
  return JSON.stringify(payloadOf(row), null, 2)
}

function tagText(row) {
  if (Array.isArray(row.tags)) return row.tags.join(', ')
  return row.tags || ''
}

function payloadExample(type) {
  if (type === 'intent') return '{\n  "question": "…",\n  "expectedIntent": "…"\n}'
  if (type === 'retrieval') return '{\n  "question": "…",\n  "agentKey": "…",\n  "documentId": "…"\n}'
  if (type === 'task') return '{\n  "task": "…",\n  "knowledge": true,\n  "expect": {}\n}'
  return '{\n  "system": "…",\n  "input": "…",\n  "reference": "…",\n  "expect": {},\n  "judge": {"enabled": true, "threshold": 3.5}\n}'
}

onMounted(load)
</script>
