<template>
  <div class="wf-page">
    <div class="wf-inner">
      <header class="wf-head">
        <div>
          <router-link to="/workflows" class="back-link">← 返回工作流列表</router-link>
          <h1>{{ definition?.name || '工作流编辑' }}</h1>
          <p>{{ definition?.description || '节点画布：自定义节点与连线，保存新版本后按图执行' }}</p>
        </div>
      </header>

      <div v-if="loadError" class="wf-empty">{{ loadError }}</div>

      <section v-else class="wf-section">
        <div class="wf-def-head">
          <div>
            <h2>{{ definition?.name }}</h2>
            <p>{{ definition?.description }}</p>
          </div>
          <span class="wf-version">v{{ definition?.version }} · {{ graphNodes.length }} 个节点</span>
        </div>

        <div class="canvas-toolbar">
          <div class="palette">
            <button
              v-for="nodeType in nodeTypes"
              :key="nodeType.type"
              type="button"
              class="palette-item"
              :title="nodeType.hint"
              @click="addNode(nodeType.type)"
            >
              <span class="palette-dot" :style="{ background: nodeType.color }" />
              {{ nodeType.label }}
            </button>
          </div>
          <div class="toolbar-right">
            <button type="button" class="ghost-btn" :disabled="savingVersion" @click="saveVersion()">
              {{ savingVersion ? '保存中…' : '保存新版本' }}
            </button>
            <input
              v-model.trim="keyword"
              class="wf-input compact"
              placeholder="运行输入（关键词）"
              maxlength="64"
              @keydown.enter="start()"
            />
            <button type="button" class="wf-run" :disabled="starting || !keyword" @click="start()">
              {{ starting ? '运行中…' : '运行工作流' }}
            </button>
          </div>
        </div>

        <div class="canvas-wrap">
          <VueFlow
            :nodes="flowNodes"
            :edges="flowEdges"
            :nodes-draggable="true"
            :nodes-connectable="true"
            :elements-selectable="true"
            :min-zoom="0.4"
            :max-zoom="1.6"
            :default-viewport="{ x: 20, y: 20, zoom: 0.95 }"
            fit-view-on-init
            @connect="onConnect"
            @node-click="onNodeClick"
            @nodes-change="onNodesChange"
            @edges-change="onEdgesChange"
          >
            <Background pattern-color="#c7ccdb" :gap="20" />
            <Controls />
            <template #node-default="nodeProps">
              <div class="wf-node" :class="[`t-${nodeProps.data.nodeType}`, nodeProps.data.statusClass, { 'is-start': nodeProps.data.nodeType === 'start' }]">
                <Handle v-if="nodeProps.data.nodeType !== 'start'" type="target" :position="Position.Left" class="wf-handle" />
                <span class="node-type-dot" :style="{ background: nodeColor(nodeProps.data.nodeType) }" />
                <div class="node-main">
                  <span class="node-name">{{ nodeProps.data.label }}</span>
                  <span class="node-type">{{ nodeTypeLabel(nodeProps.data.nodeType) }}</span>
                </div>
                <span v-if="nodeProps.data.statusText" class="node-status">{{ nodeProps.data.statusText }}</span>
                <Handle type="source" :position="Position.Right" class="wf-handle" />
              </div>
            </template>
          </VueFlow>
        </div>

        <div v-if="selectedNode" class="node-inspector">
          <div class="inspector-head">
            <strong>节点设置：{{ selectedNode.data.label }}</strong>
            <div class="inspector-actions">
              <button type="button" class="ghost-btn danger" @click="removeSelectedNode">删除节点</button>
            </div>
          </div>
          <label class="inspector-row">
            <span>名称</span>
            <input v-model.trim="selectedNode.data.label" class="wf-input compact" maxlength="24" />
          </label>
          <label v-for="(value, key) in selectedNode.data.params" :key="key" class="inspector-row align-top">
            <span class="param-key">{{ key }}</span>
            <textarea
              v-if="LONG_PARAMS.has(key)"
              v-model="selectedNode.data.params[key]"
              class="wf-input compact textarea"
              rows="3"
              :placeholder="paramPlaceholder(key)"
            />
            <input v-else v-model="selectedNode.data.params[key]" class="wf-input compact" :placeholder="paramPlaceholder(key)" />
          </label>
          <p class="inspector-hint">
            参数支持引用：<code v-text="'{{input.keyword}}'"></code> 引用运行输入，
            <code v-text="'{{节点key.output}}'"></code> 引用上游输出。
          </p>
        </div>
      </section>

      <section class="wf-section">
        <div class="section-head">
          <h2>运行记录</h2>
          <button v-if="runs.length" type="button" class="refresh" @click="refresh">{{ pollLabel }}</button>
        </div>
        <p v-if="loading" class="section-hint">加载中…</p>
        <p v-else-if="!filteredRuns.length" class="section-hint">该工作流还没有运行记录，从上方发起第一次运行</p>
        <div v-else class="run-list">
          <button
            v-for="run in filteredRuns"
            :key="run.id"
            type="button"
            class="run-item"
            :class="{ selected: run.id === selectedId }"
            @click="selectRun(run.id)"
          >
            <span class="status-dot" :class="statusClass(run.status)" />
            <span class="run-main">
              <span class="run-title">{{ run.input?.keyword || run.workflowName }}</span>
              <span class="run-meta">{{ run.workflowName }} · v{{ run.definitionVersion }} · {{ formatTime(run.createdAt) }}</span>
            </span>
            <span class="run-status" :class="statusClass(run.status)">{{ statusLabel(run.status) }}</span>
          </button>
        </div>
      </section>

      <section v-if="detail" class="wf-section">
        <div class="section-head">
          <h2>运行详情</h2>
          <span class="run-status big" :class="statusClass(detail.status)">{{ statusLabel(detail.status) }}</span>
        </div>
        <div class="detail-steps">
          <div v-for="step in detail.steps" :key="step.key" class="detail-step-wrap">
            <button type="button" class="detail-step" @click="toggleOutput(step.key)">
              <span class="status-dot" :class="stepClass(step.status)" />
              <span class="step-main">
                <span class="step-name">{{ step.name }}</span>
                <span class="step-message">{{ step.message || statusLabel(step.status) }}</span>
              </span>
              <span class="step-status">{{ stepStatusText(step.status) }}</span>
            </button>
            <div v-if="expandedOutput === step.key && step.output" class="step-output">
              <pre>{{ step.output }}</pre>
              <div class="output-actions">
                <button type="button" class="ghost-btn mini" @click="copyOutput(step.output)">复制</button>
              </div>
            </div>
          </div>
        </div>
        <p v-if="detail.error" class="detail-error">{{ detail.error }}</p>
        <div v-for="artifact in detail.artifacts" :key="artifact.name" class="artifact">
          <img v-if="artifact.url && artifact.type !== 'file'" :src="artifact.url" alt="工作流产物" class="artifact-image" />
          <a v-if="artifact.url" class="download-btn" :href="artifact.url" :download="artifact.name" target="_blank" rel="noopener">
            下载 {{ artifact.name }}
          </a>
        </div>
      </section>
    </div>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { message } from 'ant-design-vue'
import { Background } from '@vue-flow/background'
import { Controls } from '@vue-flow/controls'
import { Handle, MarkerType, Position, VueFlow } from '@vue-flow/core'
import '@vue-flow/core/dist/style.css'
import '@vue-flow/core/dist/theme-default.css'
import {
  getWorkflowDefinition,
  getWorkflowRun,
  listWorkflows,
  saveWorkflowVersion,
  startWorkflow
} from '../api'

const NODE_TYPES = [
  { type: 'search_image', label: '图片搜索', color: '#2E6BE6', hint: '通过图片搜索获取参考图；参数 keyword' },
  { type: 'image_understand', label: '图片理解', color: '#7C5CF0', hint: '理解图片内容；参数 image / question' },
  { type: 'llm_text', label: '模型对话', color: '#0F9D8A', hint: '与对话模型交互；参数 prompt（含引用）' },
  { type: 'web_search', label: '联网搜索', color: '#C77B1E', hint: '搜索网页信息；参数 query' },
  { type: 'image_generate', label: '图片生成', color: '#A34FA8', hint: '调用生图模型（DashScope 文生图）；参数 prompt / size / model' },
  { type: 'http_request', label: 'REST 请求', color: '#3D7A3D', hint: '调用外部 API；参数 method / url / headers / body' },
  { type: 'db_query', label: '数据库查询', color: '#8A6D3B', hint: '只读 SELECT/WITH 查询；参数 sql' },
  { type: 'text_template', label: '文本模板', color: '#5B6B8C', hint: '拼接/封装上游结果；参数 template（含引用）' },
  { type: 'file_export', label: '文件导出', color: '#B0562A', hint: '把文本保存为可下载文件；参数 filename / content' },
  { type: 'poster_render', label: '海报合成', color: '#C2455E', hint: '合成海报 PNG；参数 image / copy' }
]
const PARAM_DEFAULTS = {
  search_image: { keyword: '{{input.keyword}}' },
  image_understand: { image: '{{search_image.output}}', question: '描述这张图片的内容与卖点' },
  llm_text: { prompt: '根据以下信息生成内容：{{input.keyword}}' },
  web_search: { query: '{{input.keyword}}' },
  image_generate: { prompt: '为「{{input.keyword}}」生成一张简洁的商品宣传图，纯色背景', size: '1024*1024', model: 'wanx2.1-t2i-turbo' },
  http_request: { method: 'GET', url: 'https://', headers: '', body: '' },
  db_query: { sql: 'SELECT 1' },
  text_template: { template: '{{start.output}}' },
  file_export: { filename: 'output.md', content: '{{llm_text.output}}' },
  poster_render: { image: '{{search_image.output}}', copy: '{{llm_text.output}}' }
}
const LONG_PARAMS = new Set(['prompt', 'body', 'sql', 'template', 'content', 'question'])

const route = useRoute()
const workflowKey = computed(() => String(route.params.workflowKey || ''))
const definition = ref(null)
const loadError = ref('')
const runs = ref([])
const detail = ref(null)
const selectedId = ref(null)
const keyword = ref('')
const loading = ref(true)
const starting = ref(false)
const savingVersion = ref(false)
const flowNodes = ref([])
const flowEdges = ref([])
const selectedNode = ref(null)
const expandedOutput = ref('')
let pollTimer = null
let graphDirty = false
let nodeCounter = 0

const nodeTypes = NODE_TYPES

const graphNodes = computed(() => flowNodes.value)
const loadedGraphs = ref(false)
const filteredRuns = computed(() => runs.value.filter(run => run.workflowKey === workflowKey.value))
const pollLabel = computed(() => (filteredRuns.value.some(run => run.status === 'RUNNING') ? '自动刷新中' : '刷新'))

function nodeTypeLabel(type) {
  return NODE_TYPES.find(item => item.type === type)?.label || type
}
function nodeColor(type) {
  return NODE_TYPES.find(item => item.type === type)?.color || '#8A90A6'
}
function paramPlaceholder(key) {
  return {
    keyword: '如：蓝牙耳机 或 {{input.keyword}}',
    image: '图片 URL 或 {{节点key.output}}',
    question: '问题描述',
    prompt: '提示词，可含引用',
    query: '搜索词',
    copy: '文案 JSON 或 {{节点key.output}}',
    method: 'GET / POST',
    url: 'https://api.example.com/path',
    headers: 'JSON，如 {"X-Token":"abc"}',
    body: 'JSON 请求体',
    sql: 'SELECT ...（只读，最多 200 行）',
    template: '可含引用，如 报告：{{llm_text.output}}',
    filename: '输出文件名，如 report.md',
    content: '文件内容，通常引用上游输出',
    size: '1024*1024',
    model: 'wanx2.1-t2i-turbo'
  }[key] || ''
}
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

// ---------------------------------------------------------------- 画布数据

function layeredPositions(graph) {
  const indegree = new Map()
  const outgoing = new Map()
  graph.nodes.forEach(node => { indegree.set(node.key, 0); outgoing.set(node.key, []) })
  graph.edges.forEach(edge => {
    outgoing.get(edge.from)?.push(edge.to)
    indegree.set(edge.to, (indegree.get(edge.to) || 0) + 1)
  })
  const level = new Map()
  const queue = graph.nodes.filter(node => (indegree.get(node.key) || 0) === 0).map(node => node.key)
  queue.forEach(key => level.set(key, 0))
  const byKey = new Map(graph.nodes.map(node => [node.key, node]))
  while (queue.length) {
    const key = queue.shift()
    for (const next of outgoing.get(key) || []) {
      level.set(next, Math.max(level.get(next) || 0, (level.get(key) || 0) + 1))
      queue.push(next)
    }
  }
  const rows = new Map()
  return graph.nodes.map(node => {
    const l = level.get(node.key) || 0
    const row = rows.get(l) || 0
    rows.set(l, row + 1)
    return { node, x: 40 + l * 260, y: 40 + row * 110 }
  })
}

function applyGraph(versionView) {
  const graph = versionView?.graph || { nodes: [], edges: [] }
  if (!graph.nodes.some(node => node.type === 'start')) {
    // 旧版本没有开始节点：编辑时自动补一个虚拟开始节点（保存后入库）
    graph.nodes.unshift({ key: 'start', type: 'start', name: '开始', params: {}, x: 40, y: 200 })
  }
  const positioned = layeredPositions(graph)
  flowNodes.value = positioned.map(({ node, x, y }) => ({
    id: node.key,
    position: (node.x != null && node.y != null) ? { x: node.x, y: node.y } : { x, y },
    type: 'default',
    data: {
      label: node.name,
      nodeType: node.type,
      params: { ...(node.params || {}) },
      statusText: '',
      statusClass: ''
    }
  }))
  flowEdges.value = graph.edges.map((edge, index) => ({
    id: `e-${edge.from}-${edge.to}-${index}`,
    source: edge.from,
    target: edge.to,
    type: 'default',
    animated: false,
    markerEnd: { type: MarkerType.ArrowClosed, color: '#8A90A6', width: 16, height: 16 }
  }))
  selectedNode.value = null
  graphDirty = false
}

async function loadDefinition() {
  try {
    const view = await getWorkflowDefinition(workflowKey.value)
    definition.value = view
    applyGraph(view)
    loadError.value = ''
  } catch (error) {
    loadError.value = error?.response?.data?.error || '画布加载失败'
    message.error(loadError.value)
  }
}

function addNode(type) {
  nodeCounter += 1
  const key = `${type}_${Date.now().toString(36)}${nodeCounter}`
  flowNodes.value = [
    ...flowNodes.value,
    {
      id: key,
      position: { x: 60 + (flowNodes.value.length % 4) * 240, y: 60 + Math.floor(flowNodes.value.length / 4) * 120 },
      type: 'default',
      data: {
        label: nodeTypeLabel(type),
        nodeType: type,
        params: { ...(PARAM_DEFAULTS[type] || {}) },
        statusText: '',
        statusClass: ''
      }
    }
  ]
  const startNode = flowNodes.value.find(node => node.data.nodeType === 'start')
  if (startNode) {
    flowEdges.value = [
      ...flowEdges.value,
      {
        id: `e-${startNode.id}-${key}-${Date.now().toString(36)}`,
        source: startNode.id,
        target: key,
        type: 'default',
        animated: false,
        markerEnd: { type: MarkerType.ArrowClosed, color: '#8A90A6', width: 16, height: 16 }
      }
    ]
  }
  graphDirty = true
}

function onConnect(connection) {
  if (!connection?.source || !connection?.target || connection.source === connection.target) return
  const exists = flowEdges.value.some(edge => edge.source === connection.source && edge.target === connection.target)
  if (exists) return
  flowEdges.value = [
    ...flowEdges.value,
    {
      id: `e-${connection.source}-${connection.target}-${Date.now().toString(36)}`,
      source: connection.source,
      target: connection.target,
      type: 'default',
      animated: false,
      markerEnd: { type: MarkerType.ArrowClosed, color: '#8A90A6', width: 16, height: 16 }
    }
  ]
  graphDirty = true
}

function onNodesChange(changes) {
  const removed = changes?.filter(change => change.type === 'remove') || []
  if (removed.length) {
    const startIds = flowNodes.value.filter(node => node.data.nodeType === 'start').map(node => node.id)
    if (removed.some(change => startIds.includes(change.id))) {
      // 开始节点被键盘删除：立即恢复
      message.warning('开始节点不能删除')
      applyGraph(definition.value?.graph || { nodes: [], edges: [] })
      return
    }
    graphDirty = true
    selectedNode.value = null
  }
}
function onEdgesChange(changes) {
  if (changes?.some(change => change.type === 'remove')) graphDirty = true
}

function onNodeClick({ node }) {
  selectedNode.value = node
}

function toggleOutput(key) {
  expandedOutput.value = expandedOutput.value === key ? '' : key
}

async function copyOutput(text) {
  try {
    await navigator.clipboard.writeText(text)
    message.success('已复制')
  } catch (error) {
    message.warning('复制失败，请手动选择文本')
  }
}

function removeSelectedNode() {
  if (!selectedNode.value) return
  if (selectedNode.value.data.nodeType === 'start') {
    message.warning('开始节点不能删除')
    return
  }
  const id = selectedNode.value.id
  flowNodes.value = flowNodes.value.filter(node => node.id !== id)
  flowEdges.value = flowEdges.value.filter(edge => edge.source !== id && edge.target !== id)
  selectedNode.value = null
  graphDirty = true
}

async function saveVersion() {
  if (savingVersion.value) return
  savingVersion.value = true
  try {
    const graph = {
      nodes: flowNodes.value.map(node => ({
        key: node.id,
        type: node.data.nodeType,
        name: node.data.label,
        params: node.data.params,
        x: Math.round(node.position.x),
        y: Math.round(node.position.y)
      })),
      edges: flowEdges.value.map(edge => ({ from: edge.source, to: edge.target }))
    }
    await saveWorkflowVersion(workflowKey.value, { graph })
    graphDirty = false
    message.success('已保存为新版本')
    await refresh()
    await loadDefinition()
  } catch (error) {
    message.error(error?.response?.data?.error || '保存版本失败')
  } finally {
    savingVersion.value = false
  }
}

// ---------------------------------------------------------------- 运行

function applyRunStatus(run) {
  if (!run) return
  const statusByKey = new Map((run.steps || []).map(step => [step.key, step]))
  flowNodes.value = flowNodes.value.map(node => {
    const step = statusByKey.get(node.id)
    return {
      ...node,
      data: {
        ...node.data,
        statusText: step ? stepStatusText(step.status) : '',
        statusClass: step ? stepClass(step.status) : ''
      }
    }
  })
}

async function refresh() {
  try {
    const data = await listWorkflows(20)
    runs.value = data.runs || []
    loading.value = false
    schedulePoll()
  } catch (error) {
    loading.value = false
    message.error(error?.response?.data?.error || '工作流数据加载失败')
  }
}

function schedulePoll() {
  if (pollTimer) clearTimeout(pollTimer)
  if (!runs.value.some(run => run.status === 'RUNNING')) return
  pollTimer = setTimeout(async () => {
    try {
      const data = await listWorkflows(20)
      runs.value = data.runs || []
      if (selectedId.value) await loadDetail(selectedId.value, { silent: true })
    } catch (error) { /* 下轮重试 */ }
    schedulePoll()
  }, 2500)
}

async function loadDetail(runId, { silent } = {}) {
  try {
    detail.value = await getWorkflowRun(runId)
    applyRunStatus(detail.value)
  } catch (error) {
    if (!silent) message.error(error?.response?.data?.error || '运行详情加载失败')
  }
}

async function selectRun(runId) {
  selectedId.value = runId
  await loadDetail(runId)
}

async function start() {
  if (!keyword.value || starting.value) return
  starting.value = true
  try {
    if (graphDirty) {
      message.warning('画布有未保存的修改，先保存为版本再运行')
      return
    }
    const run = await startWorkflow(workflowKey.value, keyword.value)
    selectedId.value = run.id
    detail.value = run
    applyRunStatus(run)
    await refresh()
    message.success('工作流已开始运行')
  } catch (error) {
    message.error(error?.response?.data?.error || '发起运行失败')
  } finally {
    starting.value = false
  }
}

onMounted(async () => {
  await refresh()
  await loadDefinition()
})
onBeforeUnmount(() => { if (pollTimer) clearTimeout(pollTimer) })
</script>

<style scoped>
.wf-page { flex: 1; overflow-y: auto; }
.wf-inner { max-width: 1120px; margin: 0 auto; padding: 28px 32px 56px; display: flex; flex-direction: column; gap: 16px; }
.wf-head h1 { font-size: 22px; font-weight: 650; letter-spacing: -0.02em; color: var(--wb-text); }
.wf-head p { margin-top: 4px; font-size: 13px; color: var(--wb-text-2); }
.back-link { display: inline-block; margin-bottom: 6px; color: var(--wb-brand); font-size: 12.5px; }
.wf-empty, .section-hint { padding: 18px 4px; color: var(--wb-muted); font-size: 13px; }
.wf-section { padding: 18px 20px; border: 1px solid var(--wb-border); border-radius: var(--wb-radius-lg); background: var(--wb-surface); }
.wf-def-head { display: flex; align-items: flex-start; justify-content: space-between; }
.wf-def-head h2 { font-size: 16px; font-weight: 600; color: var(--wb-text); }
.wf-def-head p { margin-top: 4px; font-size: 12.5px; color: var(--wb-text-2); }
.wf-version { padding: 2px 10px; border-radius: 999px; background: var(--wb-fill); color: var(--wb-text-2); font-size: 12px; white-space: nowrap; }
.canvas-toolbar { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-top: 14px; flex-wrap: wrap; }
.palette { display: flex; gap: 6px; flex-wrap: wrap; }
.palette-item { display: inline-flex; align-items: center; gap: 6px; padding: 5px 11px; border: 1px solid var(--wb-border); border-radius: 999px; background: var(--wb-surface); color: var(--wb-text-2); font-size: 12px; transition: border-color 0.15s; }
.palette-item:hover { border-color: var(--wb-brand); color: var(--wb-brand); }
.palette-dot { width: 8px; height: 8px; border-radius: 50%; }
.toolbar-right { display: flex; align-items: center; gap: 8px; }
.ghost-btn { padding: 7px 14px; border: 1px solid var(--wb-border); border-radius: 9px; background: transparent; color: var(--wb-text-2); font-size: 12.5px; }
.ghost-btn:hover { border-color: var(--wb-brand); color: var(--wb-brand); }
.ghost-btn.danger:hover { border-color: var(--wb-danger); color: var(--wb-danger); }
.wf-input { flex: 1; padding: 9px 13px; border: 1px solid var(--wb-border); border-radius: 10px; background: var(--wb-surface-2); color: var(--wb-text); font-size: 13px; outline: none; }
.wf-input.compact { padding: 7px 11px; font-size: 12.5px; }
.wf-input:focus { border-color: var(--wb-brand); box-shadow: 0 0 0 3px var(--wb-brand-soft); }
.wf-run { padding: 9px 20px; border: 0; border-radius: 10px; background: var(--wb-brand); color: var(--wb-brand-contrast); font-size: 13px; font-weight: 500; }
.wf-run:disabled { background: var(--wb-fill); color: var(--wb-muted); }
.canvas-wrap { height: 440px; margin-top: 12px; border: 1px solid var(--wb-border); border-radius: var(--wb-radius); overflow: hidden; background: var(--wb-surface-2); }
.canvas-wrap :deep(.vue-flow) { background: var(--wb-surface-2); }
.canvas-wrap :deep(.vue-flow__node-default) {
  padding: 0;
  border: 0;
  background: transparent;
  width: auto;
  box-shadow: none;
}
.wf-handle {
  width: 9px;
  height: 9px;
  border: 2px solid var(--wb-surface);
  background: var(--wb-brand);
}
.wf-node { display: flex; align-items: center; gap: 10px; min-width: 190px; padding: 10px 12px; border: 1px solid var(--wb-border); border-radius: 11px; background: var(--wb-surface); box-shadow: var(--wb-shadow-md); }
.wf-node .node-main { display: flex; flex-direction: column; gap: 2px; }
.node-type-dot { width: 9px; height: 9px; border-radius: 50%; flex: 0 0 9px; }
.node-name { color: var(--wb-text); font-size: 13px; font-weight: 550; }
.node-type { color: var(--wb-muted); font-size: 11.5px; }
.node-status { margin-left: auto; padding: 1px 8px; border-radius: 999px; background: var(--wb-fill); color: var(--wb-text-2); font-size: 11px; }
.wf-node.is-start { border-color: var(--wb-brand); background: var(--wb-brand-soft); }
.wf-node.is-start .node-name { color: var(--wb-brand); }
.inspector-row.align-top { align-items: flex-start; }
.textarea { resize: vertical; min-height: 64px; font-family: var(--wb-font-mono, monospace); font-size: 12px; line-height: 1.5; }
.detail-step-wrap { border-bottom: 1px solid var(--wb-border); }
.detail-step-wrap:last-child { border-bottom: 0; }
.detail-step { width: 100%; border: 0; background: transparent; text-align: left; }
.step-output { padding: 4px 14px 12px 30px; }
.step-output pre { max-height: 220px; overflow: auto; padding: 10px 12px; border-radius: 9px; background: var(--wb-fill); color: var(--wb-text-2); font-size: 12px; line-height: 1.6; white-space: pre-wrap; word-break: break-all; }
.output-actions { display: flex; justify-content: flex-end; margin-top: 6px; }
.ghost-btn.mini { padding: 4px 10px; font-size: 11.5px; }
.download-btn { display: inline-block; margin-top: 8px; padding: 6px 14px; border-radius: 9px; background: var(--wb-brand-soft); color: var(--wb-brand); font-size: 12.5px; }
.wf-node.is-running { border-color: var(--wb-info); box-shadow: 0 0 0 2px rgba(37, 144, 255, 0.18); }
.wf-node.is-done { border-color: var(--wb-success); }
.wf-node.is-failed { border-color: var(--wb-danger); box-shadow: 0 0 0 2px rgba(220, 47, 76, 0.18); }
.node-inspector { margin-top: 12px; padding: 14px 16px; border: 1px dashed var(--wb-border-strong); border-radius: var(--wb-radius); background: var(--wb-surface-2); display: flex; flex-direction: column; gap: 10px; }
.inspector-head { display: flex; align-items: center; justify-content: space-between; color: var(--wb-text); font-size: 13px; }
.inspector-row { display: flex; align-items: center; gap: 12px; }
.inspector-row > span { flex: 0 0 120px; color: var(--wb-text-2); font-size: 12.5px; font-family: var(--wb-font-mono, monospace); }
.inspector-hint { color: var(--wb-muted); font-size: 12px; line-height: 1.6; }
.inspector-hint code { padding: 1px 6px; border-radius: 5px; background: var(--wb-fill); font-size: 11.5px; }
.section-head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 10px; }
.section-head h2 { font-size: 15px; font-weight: 600; color: var(--wb-text); }
.refresh { border: 0; background: transparent; color: var(--wb-muted); font-size: 12.5px; }
.run-list { display: flex; flex-direction: column; gap: 2px; }
.run-item { display: flex; align-items: center; gap: 11px; padding: 10px; border: 0; border-radius: var(--wb-radius); background: transparent; text-align: left; }
.run-item:hover, .run-item.selected { background: var(--wb-fill); }
.status-dot { width: 8px; height: 8px; border-radius: 50%; flex: 0 0 8px; background: var(--wb-border-strong); }
.status-dot.is-running { background: var(--wb-info); box-shadow: 0 0 0 3px rgba(37, 144, 255, 0.18); }
.status-dot.is-done { background: var(--wb-success); }
.status-dot.is-failed { background: var(--wb-danger); }
.status-dot.is-skipped { background: var(--wb-muted); }
.status-dot.is-pending { background: var(--wb-border-strong); }
.run-main { display: flex; flex: 1; flex-direction: column; gap: 3px; min-width: 0; }
.run-title { color: var(--wb-text); font-size: 13px; font-weight: 500; }
.run-meta { color: var(--wb-muted); font-size: 12px; }
.run-status { flex: 0 0 auto; color: var(--wb-muted); font-size: 12px; }
.run-status.is-running { color: var(--wb-info); }
.run-status.is-done { color: var(--wb-success); }
.run-status.is-failed { color: var(--wb-danger); }
.run-status.big { padding: 3px 12px; border-radius: 999px; background: var(--wb-fill); font-weight: 550; }
.detail-steps { display: flex; flex-direction: column; }
.detail-step { display: flex; align-items: center; gap: 11px; padding: 10px 4px; }
.detail-step + .detail-step { border-top: 1px solid var(--wb-border); }
.step-main { display: flex; flex: 1; flex-direction: column; gap: 3px; min-width: 0; }
.step-name { color: var(--wb-text); font-size: 13px; font-weight: 500; }
.step-message { color: var(--wb-muted); font-size: 12px; }
.step-status { color: var(--wb-muted); font-size: 12px; }
.detail-error { margin-top: 10px; padding: 10px 12px; border-radius: var(--wb-radius); background: rgba(220, 47, 76, 0.08); color: var(--wb-danger); font-size: 12.5px; }
.artifact { margin-top: 14px; text-align: center; }
.artifact-image { max-width: 380px; width: 100%; border: 1px solid var(--wb-border); border-radius: var(--wb-radius); box-shadow: var(--wb-shadow-md); }
</style>
