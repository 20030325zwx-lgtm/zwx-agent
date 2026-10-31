<template>
  <div class="wf-page">
    <header class="wf-topbar">
      <div class="topbar-left">
        <router-link to="/workflows" class="back-link">← 工作流</router-link>
        <h1>{{ definition?.name || '工作流编辑' }}</h1>
        <span class="wf-version">v{{ definition?.version }} · {{ graphNodes.length }} 个节点</span>
        <p v-if="definition?.description" class="topbar-desc">{{ definition.description }}</p>
      </div>
      <div class="topbar-actions">
        <input
          v-model.trim="keyword"
          class="wf-input compact keyword-input"
          placeholder="运行输入（关键词）"
          maxlength="64"
          @keydown.enter="openRunDialog()"
        />
        <button type="button" class="wf-run" :disabled="starting" @click="openRunDialog()">
          {{ starting ? '运行中…' : '运行' }}
        </button>
        <button type="button" class="ghost-btn" @click="autoLayout">自动布局</button>
        <button type="button" class="ghost-btn" :disabled="savingVersion" @click="saveVersion()">
          {{ savingVersion ? '保存中…' : '保存新版本' }}
        </button>
        <button
          type="button"
          class="ghost-btn runs-toggle"
          :class="{ active: runPanelOpen }"
          @click="runPanelOpen = !runPanelOpen"
        >
          <History :size="14" />
          运行记录
          <span v-if="filteredRuns.length" class="run-badge">{{ filteredRuns.length }}</span>
        </button>
      </div>
    </header>

    <div v-if="loadError" class="wf-empty">{{ loadError }}</div>

    <div v-else class="canvas-area">
      <div v-if="connectDrag.dragging" class="drag-hint">
        拖到空白处松手添加节点 · 拖到节点端口直接连线
      </div>
      <VueFlow
        :nodes="flowNodes"
        :edges="flowEdges"
        :nodes-draggable="true"
        :nodes-connectable="true"
        :elements-selectable="true"
        :edges-updatable="true"
        :delete-key-code="null"
        :min-zoom="0.4"
        :max-zoom="1.6"
        :default-viewport="{ x: 20, y: 20, zoom: 0.95 }"
        fit-view-on-init
        @connect="onConnect"
        @connect-start="onConnectStart"
        @connect-end="onConnectEnd"
        @edge-update="onEdgeUpdate"
        @node-click="onNodeClick"
        @pane-click="clearSelection"
        @nodes-change="markDirtyOnChange"
        @edges-change="markDirtyOnChange"
      >
        <Background pattern-color="#c7ccdb" :gap="18" :size="1.4" />
        <Controls />
        <template #node-default="nodeProps">
          <div
            class="wf-node"
            :class="[`t-${nodeProps.data.nodeType}`, nodeProps.data.statusClass, { 'is-start': nodeProps.data.nodeType === 'start' }]"
            :style="{ '--node-color': nodeColor(nodeProps.data.nodeType) }"
          >
            <Handle v-if="nodeProps.data.nodeType !== 'start'" type="target" :position="Position.Left" class="wf-handle target" />
            <div class="node-main">
              <span class="node-type">{{ nodeTypeLabel(nodeProps.data.nodeType) }}</span>
              <span class="node-name">{{ nodeProps.data.label }}</span>
            </div>
            <span v-if="nodeProps.data.statusText" class="node-status">{{ nodeProps.data.statusText }}</span>
            <Handle type="source" :position="Position.Right" class="wf-handle source" />
          </div>
        </template>
      </VueFlow>

      <div
        v-if="addMenu.open"
        class="add-menu"
        :style="{ left: addMenu.x + 'px', top: addMenu.y + 'px' }"
        @click.stop
      >
        <p class="add-menu-title">
          {{ addMenuSourceName ? `在「${addMenuSourceName}」之后添加` : '添加节点' }}
          <span class="add-menu-sub">· 选择节点类型</span>
        </p>
        <button
          v-for="nodeType in nodeTypes"
          :key="nodeType.type"
          type="button"
          class="add-menu-item"
          @click="addNode(nodeType.type, addMenu.sourceId, { x: addMenu.flowX, y: addMenu.flowY })"
        >
          <span class="palette-dot" :style="{ background: nodeType.color }" />
          <span class="add-menu-label">{{ nodeType.label }}</span>
          <span class="add-menu-hint">{{ nodeType.hint }}</span>
        </button>
      </div>

      <div v-if="selectedNode" class="node-inspector">
        <div class="inspector-head">
          <strong>节点设置：{{ selectedNode.data.label }}</strong>
          <div class="inspector-actions">
            <button type="button" class="ghost-btn" @click="openAddMenuForNode(selectedNode.id)">添加下游节点</button>
            <button type="button" class="ghost-btn danger" @click="removeSelectedNode">删除节点</button>
            <button type="button" class="panel-close" aria-label="关闭节点设置" @click="clearSelection">×</button>
          </div>
        </div>
        <label class="inspector-row">
          <span>名称</span>
          <input v-model.trim="selectedNode.data.label" class="wf-input compact" maxlength="24" />
        </label>
        <div v-for="(value, key) in selectedNode.data.params" :key="key" class="inspector-row align-top">
          <span class="param-key">{{ key }}</span>
          <AssetParamField
            v-if="isAssetParam(key)"
            :model-value="String(selectedNode.data.params[key] || '')"
            :multiple="key === 'imageAssetIds'"
            @update:model-value="value => selectedNode.data.params[key] = value"
          />
          <textarea
            v-else-if="LONG_PARAMS.has(key)"
            v-model="selectedNode.data.params[key]"
            class="wf-input compact textarea"
            rows="3"
            :placeholder="paramPlaceholder(key)"
          />
          <input v-else v-model="selectedNode.data.params[key]" class="wf-input compact" :placeholder="paramPlaceholder(key)" />
        </div>
        <p class="inspector-help">{{ NODE_HELP[selectedNode.data.nodeType] || '' }}</p>
        <p class="inspector-hint">
          参数支持引用：<code v-text="'{{input.keyword}}'"></code> 引用运行输入，
          <code v-text="'{{节点key.output}}'"></code> 引用上游输出。
        </p>
      </div>

      <aside v-if="runPanelOpen" class="run-panel" aria-label="运行记录">
        <div class="run-panel-head">
          <strong>运行记录</strong>
          <button v-if="filteredRuns.length" type="button" class="refresh" @click="refresh">{{ pollLabel }}</button>
          <button type="button" class="panel-close" aria-label="关闭运行记录" @click="runPanelOpen = false">×</button>
        </div>
        <div class="run-panel-body">
          <p v-if="loading" class="section-hint">加载中…</p>
          <p v-else-if="!filteredRuns.length" class="section-hint">该工作流还没有运行记录，从右上角发起第一次运行</p>
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
                <span class="run-meta">v{{ run.definitionVersion }} · {{ formatTime(run.createdAt) }}</span>
              </span>
              <span class="run-status" :class="statusClass(run.status)">{{ statusLabel(run.status) }}</span>
            </button>
          </div>

          <div v-if="detail" class="run-detail">
            <div class="run-detail-head">
              <strong>运行详情</strong>
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
          </div>
        </div>
      </aside>

      <div v-if="runDialog.open" class="run-dialog-backdrop" @click.self="runDialog.open = false">
        <div class="run-dialog" role="dialog" aria-modal="true" aria-labelledby="run-dialog-title">
          <div class="run-dialog-head">
            <h2 id="run-dialog-title">发起运行：{{ definition?.name }}</h2>
            <button type="button" class="panel-close" aria-label="关闭运行对话框" @click="runDialog.open = false">×</button>
          </div>
          <label class="run-dialog-field">
            <span>关键词（运行时作为 input.keyword 引用）</span>
            <input v-model.trim="runDialog.keyword" class="wf-input compact" maxlength="200"
                   placeholder="如：加绒保暖 不起球 显瘦百搭" />
          </label>
          <div v-for="key in runDialog.assetKeys" :key="key" class="run-dialog-field">
            <span class="run-dialog-key">{{ key }}</span>
            <AssetParamField
              :model-value="runDialog.values[key] || ''"
              :multiple="key === 'imageAssetIds'"
              @update:model-value="value => runDialog.values[key] = value"
            />
          </div>
          <div v-for="key in runDialog.textKeys" :key="key" class="run-dialog-field">
            <span class="run-dialog-key">{{ key }}</span>
            <input v-model.trim="runDialog.values[key]" class="wf-input compact" :placeholder="`运行输入 ${key}`" />
          </div>
          <label class="run-dialog-field">
            <span>目标创作项目（产物归入该项目）</span>
            <select v-model="runDialog.projectId" class="wf-input compact">
              <option value="" disabled>选择项目</option>
              <option v-for="project in runDialogProjects" :key="project.id" :value="project.id">
                {{ project.name }}
              </option>
            </select>
          </label>
          <p v-if="runDialog.error" class="run-dialog-error">{{ runDialog.error }}</p>
          <div class="run-dialog-actions">
            <button type="button" class="ghost-btn" @click="runDialog.open = false">取消</button>
            <button type="button" class="wf-run" :disabled="starting || !canStartRun" @click="submitRunDialog()">
              {{ starting ? '发起中…' : '发起运行' }}
            </button>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { message } from 'ant-design-vue'
import { Background } from '@vue-flow/background'
import { Controls } from '@vue-flow/controls'
import { Handle, MarkerType, Position, VueFlow, useVueFlow } from '@vue-flow/core'
import { History } from 'lucide-vue-next'
import '@vue-flow/core/dist/style.css'
import '@vue-flow/core/dist/theme-default.css'
import AssetParamField from '../components/workflow/AssetParamField.vue'
import { deriveRunInputs } from './creative/workflowInputs'
import {
  getCreativeAssetBlob,
  getWorkflowDefinition,
  getWorkflowRun,
  listCreativeProjects,
  listWorkflows,
  saveWorkflowVersion,
  startWorkflow
} from '../api'

const NODE_TYPES = [
  { type: 'search_image', label: '图片搜索', color: '#2E6BE6', hint: '通过图片搜索获取参考图；参数 keyword' },
  { type: 'image_understand', label: '图片理解', color: '#7C5CF0', hint: '理解图片内容；参数 image / question' },
  { type: 'llm_text', label: '模型对话', color: '#0F9D8A', hint: '与对话模型交互；参数 prompt（含引用）' },
  { type: 'web_search', label: '联网搜索', color: '#C77B1E', hint: '搜索网页信息；参数 query' },
  { type: 'image_generate', label: '图片生成（旧）', color: '#A34FA8', hint: '同步轮询旧链路；参数 prompt / size / model（保留兼容）' },
  { type: 'media_image_generate', label: '图片生成（创作）', color: '#8C3FA8', hint: '提交 AI 创作任务并持久等待；参数 prompt / size / projectId' },
  { type: 'media_image_edit', label: '图片编辑（参考图）', color: '#B03FA8', hint: '带 1–3 张参考素材生成（如模特试穿）；参数 prompt / imageAssetIds / size' },
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
  media_image_generate: { prompt: '为「{{input.keyword}}」生成一张简洁的商品宣传图，纯色背景', size: '1024*1024' },
  media_image_edit: { prompt: '让参考图中的模特穿上参考图中的上衣和裤子，保持模特脸型与姿态不变，白底商品图', imageAssetIds: '{{start.output.modelAssetId}},{{start.output.topAssetId}},{{start.output.pantsAssetId}}', size: '1024*1024' },
  http_request: { method: 'GET', url: 'https://', headers: '', body: '' },
  db_query: { sql: 'SELECT 1' },
  text_template: { template: '{{start.output}}' },
  file_export: { filename: 'output.md', content: '{{llm_text.output}}' },
  poster_render: { image: '{{search_image.output}}', copy: '{{llm_text.output}}' }
}
const LONG_PARAMS = new Set(['prompt', 'body', 'sql', 'template', 'content', 'question'])
const NODE_HELP = {
  start: '开始节点：代表一次运行的输入。运行输入（如关键词）用 {{input.keyword}} 引用；{{start.output}} 是全部输入。',
  search_image: '图片搜索：keyword 填搜索词（可直接写关键词或引用上游）。输出是图片 URL，可被图片理解/海报合成等节点引用。',
  image_understand: '图片理解：image 填图片 URL 或引用上游图片节点输出；question 填想问的问题。输出为图片描述文本（服务不可用时降级为提示文本）。',
  llm_text: '模型对话：prompt 写提示词，可嵌入 {{节点.output}} 与 {{input.字段}} 引用上游结果做二次编辑。输出为模型回复。使用“设置→模型”中绑定的对话模型。',
  web_search: '联网搜索：query 填搜索词。输出为搜索结果文本（需要配置搜索 API Key）。',
  image_generate: '图片生成（旧）：仅支持 DashScope 协议绑定，同步轮询约 120 秒；新流程建议改用「图片生成（创作）」——它走 AI 创作任务队列、可持久等待恢复，且能用模型设置里通过中转站接入的生图模型。',
  media_image_generate: '图片生成（创作）：提交到 AI 创作队列并持久等待，服务重启后可继续。输出为 JSON：jobId / assetId / mediaType，可用 {{节点.output.assetId}} 引用到图片理解或海报合成。支持 DashScope 与中转站（OpenAI 兼容）生图模型。按张计费。',
  media_image_edit: '图片编辑（参考图）：带 1–3 张本人素材作参考生成新图（中转站 OpenAI images/edits 协议）。imageAssetIds 填素材 ID 列表（逗号分隔或数组，支持 {{节点.output.assetId}} 引用），第一个通常是模特图。输出 JSON：jobId / assetId / mediaType。按张计费。',
  http_request: 'REST 请求：method 选 GET/POST 等，url 填地址，headers/body 填 JSON。输出为响应体（截断 4000 字）；非 2xx 视为失败。',
  db_query: '数据库查询：sql 只能写单条 SELECT/WITH（最多 200 行、10 秒超时）。输出为结果 JSON。',
  text_template: '文本模板：template 里可混合文字与引用，如“报告：{{llm_text.output}}”。输出为替换后的文本，不消耗模型。',
  file_export: '文件导出：filename 写文件名（md/txt/json/csv），content 通常引用上游文本。输出为可下载文件。',
  poster_render: '海报合成：image 引用商品图 URL，或 imageAssetId 引用创作素材；copy 引用文案 JSON（标题/副标题/卖点/配色）。输出为海报 PNG。'
}

const route = useRoute()
const { screenToFlowCoordinate } = useVueFlow()
const workflowKey = computed(() => String(route.params.workflowKey || ''))
const definition = ref(null)
const loadError = ref('')
const runs = ref([])
const detail = ref(null)
const selectedId = ref(null)
const runPanelOpen = ref(false)
const keyword = ref('')
const loading = ref(true)
const starting = ref(false)
const savingVersion = ref(false)
const flowNodes = ref([])
const flowEdges = ref([])
const selectedNode = ref(null)
const connectDrag = reactive({ sourceId: '', justConnected: false, dragging: false })
const expandedOutput = ref('')
const addMenu = reactive({ open: false, x: 0, y: 0, sourceId: '', flowX: 0, flowY: 0 })
const addMenuSourceName = computed(() => {
  const node = flowNodes.value.find(item => item.id === addMenu.sourceId)
  return node?.data?.label || ''
})


let pollTimer = null
let graphDirty = false
let suppressMenuCloseUntil = 0
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
  return { RUNNING: '运行中', WAITING_EXTERNAL: '等待图片生成', SUCCEEDED: '成功', FAILED: '失败' }[status] || status
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

const EDGE_DEFAULTS = {
  type: 'smoothstep',
  animated: false,
  updatable: true,
  style: { stroke: '#94A3B8', strokeWidth: 1.8 },
  markerEnd: { type: MarkerType.ArrowClosed, color: '#94A3B8', width: 17, height: 17 }
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
    ...EDGE_DEFAULTS
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

function addNode(type, sourceId, position) {
  const source = sourceId ? flowNodes.value.find(node => node.id === sourceId) : null
  const origin = position || {
    x: source ? source.position.x + 260 : 120,
    y: source ? source.position.y : 120
  }
  let x = origin.x
  let y = origin.y
  let guard = 0
  while (flowNodes.value.some(node => Math.abs(node.position.x - x) < 40 && Math.abs(node.position.y - y) < 40) && guard < 12) {
    y += 110
    guard += 1
  }
  nodeCounter += 1
  const key = `${type}_${Date.now().toString(36)}${nodeCounter}`
  flowNodes.value = [
    ...flowNodes.value,
    {
      id: key,
      position: { x, y },
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
  const connectSource = sourceId || flowNodes.value.find(node => node.data.nodeType === 'start')?.id || ''
  if (connectSource) {
    flowEdges.value = [
      ...flowEdges.value,
      {
        id: `e-${connectSource}-${key}-${Date.now().toString(36)}`,
        source: connectSource,
        target: key,
        ...EDGE_DEFAULTS
      }
    ]
  }
  addMenu.open = false
  graphDirty = true
}

function autoLayout() {
  const edges = flowEdges.value.map(edge => ({ from: edge.source, to: edge.target }))
  const positioned = layeredPositions({ nodes: flowNodes.value.map(node => ({ key: node.id })), edges })
  const positionByKey = new Map(positioned.map(item => [item.node.key, { x: item.x, y: item.y }]))
  flowNodes.value = flowNodes.value.map(node => ({
    ...node,
    position: positionByKey.get(node.id) || node.position
  }))
  graphDirty = true
}

/**
 * 屏幕坐标 → 画布坐标。必须走 vue-flow 自己的转换：
 * 缩放/平移的 transform 挂在 .vue-flow__transformationpane 上，
 * 手动读取 .vue-flow__viewport 会拿到 none，新增节点就会落到错误位置。
 */
function clientToFlowPoint(clientX, clientY) {
  return screenToFlowCoordinate({ x: clientX, y: clientY })
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
      ...EDGE_DEFAULTS
    }
  ]
  connectDrag.justConnected = true
  setTimeout(() => { connectDrag.justConnected = false }, 50)
  graphDirty = true
}

function onConnectStart(params) {
  connectDrag.sourceId = params?.nodeId || ''
  connectDrag.dragging = true
}

/** 从端口拖拽到空白画布松手：就地弹类型菜单，选中后在该位置建节点并连线 */
function onConnectEnd(event) {
  const sourceId = connectDrag.sourceId
  connectDrag.sourceId = ''
  if (connectDrag.justConnected) return
  connectDrag.dragging = false
  const native = event?.event || event
  const clientX = native?.clientX
  const clientY = native?.clientY
  if (typeof clientX !== 'number' || typeof clientY !== 'number') return
  const flowPoint = clientToFlowPoint(clientX, clientY)
  suppressMenuCloseUntil = Date.now() + 300
  addMenu.open = true
  addMenu.sourceId = sourceId
  addMenu.flowX = flowPoint.x
  addMenu.flowY = flowPoint.y
  addMenu.x = Math.min(clientX + 12, window.innerWidth - 340)
  addMenu.y = Math.min(clientY - 10, window.innerHeight - 430)
}

/** 拖拽已有连线的端点改接到其他节点（RuleGo 式重连） */
function onEdgeUpdate({ edge, connection }) {
  if (!edge || !connection?.source || !connection?.target) return
  if (connection.source === connection.target) return
  flowEdges.value = flowEdges.value.map(item => item.id === edge.id
    ? { ...item, source: connection.source, target: connection.target }
    : item)
  graphDirty = true
}

function markDirtyOnChange(changes) {
  // dimensions/select 是初始化与选中事件，不代表用户编辑；否则刚加载的画布就会被当成"未保存"
  const userEdited = (changes || []).some(change =>
    change.type !== 'dimensions' && change.type !== 'select');
  if (userEdited) graphDirty = true
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

function handleDeleteKey(event) {
  if (event.key !== 'Delete' && event.key !== 'Backspace') return
  const target = event.target
  if (target instanceof HTMLElement && ['INPUT', 'TEXTAREA', 'SELECT'].includes(target.tagName)) return
  if (!selectedNode.value) return
  event.preventDefault()
  removeSelectedNode()
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
    detail.value = await resolveArtifactUrls(await getWorkflowRun(runId))
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
    runPanelOpen.value = true
    applyRunStatus(run)
    await refresh()
    message.success('工作流已开始运行')
  } catch (error) {
    message.error(error?.response?.data?.error || '发起运行失败')
  } finally {
    starting.value = false
  }
}

// ---------------------------------------------------------------- 运行对话框

const runDialog = reactive({ open: false, keyword: '', projectId: '', values: {}, assetKeys: [], textKeys: [], error: '' })
const runDialogProjects = ref([])
const isAssetKey = key => /AssetIds?$/.test(key)

/** 从图参数里扫描运行输入键（与创作首页/项目页共用同一推导）。 */
const runInputKeys = computed(() => {
  const { assetKeys, textKeys } = deriveRunInputs({ nodes: graphNodes.value.map(node => ({ params: node.data?.params })) })
  return [...assetKeys, ...textKeys]
})

const canStartRun = computed(() => {
  if (!runDialog.keyword.trim()) return false
  if (runDialog.assetKeys.some(key => !(runDialog.values[key] || '').trim())) return false
  return true
})

function openRunDialog() {
  if (graphDirty) {
    message.warning('画布有未保存的修改，先保存为版本再运行')
    return
  }
  const assetKeys = []
  const textKeys = []
  for (const key of runInputKeys.value) {
    ;(isAssetKey(key) ? assetKeys : textKeys).push(key)
  }
  if (!assetKeys.includes('keyword')) textKeys.unshift('keyword')
  runDialog.keyword = keyword.value || ''
  runDialog.assetKeys = assetKeys
  runDialog.textKeys = textKeys.filter(key => key !== 'keyword')
  runDialog.values = {}
  runDialog.error = ''
  runDialog.open = true
  if (!runDialogProjects.value.length) {
    listCreativeProjects({ limit: 20 })
      .then(data => {
        runDialogProjects.value = (data.items || []).filter(project => project.status === 'ACTIVE')
        if (!runDialog.projectId) runDialog.projectId = runDialogProjects.value[0]?.id || ''
      })
      .catch(() => { /* 打开时保持空，提交时会报错提示 */ })
  }
}

async function submitRunDialog() {
  if (!canStartRun.value || starting.value) return
  starting.value = true
  runDialog.error = ''
  try {
    const input = {}
    for (const key of [...runDialog.assetKeys, ...runDialog.textKeys]) {
      const value = key === 'keyword' ? runDialog.keyword : (runDialog.values[key] || '').trim()
      if (value) input[key] = value
    }
    const run = await startWorkflow(workflowKey.value, runDialog.keyword, runDialog.projectId || null, input)
    runDialog.open = false
    keyword.value = runDialog.keyword
    selectedId.value = run.id
    detail.value = run
    runPanelOpen.value = true
    applyRunStatus(run)
    await refresh()
    message.success('工作流已开始运行')
  } catch (error) {
    runDialog.error = error?.response?.data?.error || '发起运行失败'
  } finally {
    starting.value = false
  }
}

// R05：已登记素材的产物（url=asset:ID）经鉴权 blob 渲染，本地/OSS 存储都可用
async function resolveArtifactUrls(detail) {
  for (const artifact of detail.artifacts || []) {
    if (artifact.url && artifact.url.startsWith('asset:')) {
      try {
        const blob = await getCreativeAssetBlob(artifact.url.slice(6))
        artifact.url = URL.createObjectURL(blob)
      } catch (error) {
        artifact.url = ''
      }
    }
  }
  return detail
}

function clearSelection() {
  selectedNode.value = null
}

function isAssetParam(key) {
  return isAssetKey(key)
}

function openAddMenuForNode(nodeId) {
  const node = flowNodes.value.find(item => item.id === nodeId)
  if (!node) return
  const el = document.querySelector(`.vue-flow__node[data-id="${nodeId}"]`)
  const rect = el?.getBoundingClientRect()
  addMenu.open = true
  addMenu.sourceId = nodeId
  addMenu.flowX = node.position.x + 250
  addMenu.flowY = node.position.y + 40
  addMenu.x = Math.min((rect?.right || 400) + 14, window.innerWidth - 340)
  addMenu.y = Math.min((rect?.top || 200) - 10, window.innerHeight - 430)
  suppressMenuCloseUntil = Date.now() + 200
}

function closeAddMenu() {
  if (Date.now() < suppressMenuCloseUntil) return
  addMenu.open = false
}

onMounted(async () => {
  document.addEventListener('click', closeAddMenu)
  document.addEventListener('keydown', handleDeleteKey)
  document.addEventListener('keydown', event => { if (event.key === 'Escape') addMenu.open = false })
  await refresh()
  await loadDefinition()
})
onBeforeUnmount(() => {
  if (pollTimer) clearTimeout(pollTimer)
  document.removeEventListener('click', closeAddMenu)
  document.removeEventListener('keydown', handleDeleteKey)
})
</script>

<style scoped>
.wf-page { position: relative; flex: 1; min-height: 0; display: flex; flex-direction: column; overflow: hidden; }
.wf-topbar { display: flex; align-items: center; justify-content: space-between; gap: 16px; padding: 12px 20px; border-bottom: 1px solid var(--wb-border); background: var(--wb-surface); }
.topbar-left { display: flex; align-items: center; gap: 10px; min-width: 0; }
.topbar-left h1 { font-size: 16px; font-weight: 650; letter-spacing: -0.01em; color: var(--wb-text); white-space: nowrap; }
.topbar-desc { margin-left: 4px; color: var(--wb-muted); font-size: 12px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.back-link { color: var(--wb-brand); font-size: 12.5px; white-space: nowrap; }
.wf-empty { flex: 1; padding: 40px 24px; color: var(--wb-muted); font-size: 13px; }
.section-hint { padding: 14px 4px; color: var(--wb-muted); font-size: 13px; }
.wf-version { padding: 2px 10px; border-radius: 999px; background: var(--wb-fill); color: var(--wb-text-2); font-size: 12px; white-space: nowrap; }
.topbar-actions { display: flex; align-items: center; gap: 8px; flex: 0 0 auto; }
.keyword-input { width: 180px; flex: 0 0 auto; }
.runs-toggle { display: inline-flex; align-items: center; gap: 6px; }
.runs-toggle.active { border-color: var(--wb-brand); color: var(--wb-brand); }
.run-badge { padding: 0 6px; border-radius: 999px; background: var(--wb-brand-soft); color: var(--wb-brand); font-size: 11px; }
.canvas-area { position: relative; flex: 1; min-height: 0; background: var(--wb-surface-2); }
.canvas-area :deep(.vue-flow) { width: 100%; height: 100%; }
.palette { display: flex; gap: 6px; flex-wrap: wrap; }
.palette-item { display: inline-flex; align-items: center; gap: 6px; padding: 5px 11px; border: 1px solid var(--wb-border); border-radius: 999px; background: var(--wb-surface); color: var(--wb-text-2); font-size: 12px; transition: border-color 0.15s; }
.palette-item:hover { border-color: var(--wb-brand); color: var(--wb-brand); }
.palette-dot { width: 8px; height: 8px; border-radius: 50%; }
.ghost-btn { padding: 7px 14px; border: 1px solid var(--wb-border); border-radius: 9px; background: transparent; color: var(--wb-text-2); font-size: 12.5px; }
.ghost-btn:hover { border-color: var(--wb-brand); color: var(--wb-brand); }
.ghost-btn.danger:hover { border-color: var(--wb-danger); color: var(--wb-danger); }
.wf-input { flex: 1; padding: 9px 13px; border: 1px solid var(--wb-border); border-radius: 10px; background: var(--wb-surface-2); color: var(--wb-text); font-size: 13px; outline: none; }
.wf-input.compact { padding: 7px 11px; font-size: 12.5px; }
.wf-input:focus { border-color: var(--wb-brand); box-shadow: 0 0 0 3px var(--wb-brand-soft); }
.wf-run { padding: 9px 20px; border: 0; border-radius: 10px; background: var(--wb-brand); color: var(--wb-brand-contrast); font-size: 13px; font-weight: 500; }
.wf-run:disabled { background: var(--wb-fill); color: var(--wb-muted); }
.canvas-area :deep(.vue-flow) { background: var(--wb-surface-2); }
.canvas-area :deep(.vue-flow__node) {
  z-index: 6;
}
.canvas-area :deep(.vue-flow__edge) {
  z-index: 1;
}
.canvas-area :deep(.vue-flow__node-default) {
  padding: 0;
  border: 0;
  background: transparent;
  width: auto;
  box-shadow: none;
}
.wf-handle {
  width: 12px;
  height: 12px;
  border: 2px solid var(--wb-surface);
  background: var(--wb-brand);
  transition: transform 0.12s, box-shadow 0.12s;
}
.wf-handle::before {
  content: '';
  position: absolute;
  inset: -7px;
  border-radius: 50%;
}
.wf-handle:hover {
  transform: scale(1.5);
  box-shadow: 0 0 0 4px var(--wb-brand-soft);
}
.wf-node .wf-handle.source { background: var(--node-color, var(--wb-brand)); }
.wf-node { display: flex; align-items: center; gap: 10px; min-width: 200px; padding: 9px 14px 9px 12px; border: 1px solid var(--wb-border); border-left: 3px solid var(--node-color, var(--wb-brand)); border-radius: 10px; background: var(--wb-surface); box-shadow: var(--wb-shadow-md); transition: box-shadow 0.15s, border-color 0.15s; }
.wf-node:hover { box-shadow: var(--wb-shadow-lg); }
.wf-node .node-main { display: flex; flex-direction: column; gap: 3px; }

.node-name { color: var(--wb-text); font-size: 13px; font-weight: 600; }
.node-type { color: var(--node-color, var(--wb-muted)); font-size: 10.5px; font-weight: 600; letter-spacing: 0.04em; text-transform: uppercase; }
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
.drag-hint { position: absolute; top: 12px; left: 50%; transform: translateX(-50%); z-index: 8; padding: 6px 14px; border-radius: 999px; background: var(--wb-brand); color: var(--wb-brand-contrast); font-size: 12px; box-shadow: var(--wb-shadow-md); pointer-events: none; }
.canvas-area :deep(.vue-flow__connection-path) { stroke: var(--wb-brand); stroke-width: 2; stroke-dasharray: 6 4; }
.canvas-area :deep(.vue-flow__edge-path) { stroke: #94A3B8; stroke-width: 1.8; }
.canvas-area :deep(.vue-flow__edge.selected .vue-flow__edge-path) { stroke: var(--wb-brand); }
.add-menu { animation: menu-in 0.14s ease-out; position: fixed; z-index: 70; width: 320px; max-height: 420px; overflow-y: auto; padding: 8px; border: 1px solid var(--wb-border); border-radius: 12px; background: var(--wb-raise); box-shadow: var(--wb-shadow-lg); }
.add-menu-title { padding: 4px 8px 8px; color: var(--wb-text); font-size: 12px; font-weight: 550; }
.add-menu-sub { color: var(--wb-muted); font-weight: 400; }
@keyframes menu-in { from { opacity: 0; transform: translateY(-4px) scale(0.98); } to { opacity: 1; transform: none; } }
.add-menu-item { display: flex; width: 100%; align-items: center; gap: 9px; padding: 8px 10px; border: 0; border-radius: 8px; background: transparent; color: var(--wb-text); font-size: 12.5px; text-align: left; }
.add-menu-item:hover { background: var(--wb-fill); }
.add-menu-label { flex: 0 0 auto; }
.add-menu-hint { flex: 1; min-width: 0; overflow: hidden; color: var(--wb-muted); font-size: 11px; text-overflow: ellipsis; white-space: nowrap; }
.wf-node.is-running { border-color: var(--wb-info); box-shadow: 0 0 0 2px rgba(37, 144, 255, 0.18); }
.wf-node.is-done { border-color: var(--wb-success); }
.wf-node.is-failed { border-color: var(--wb-danger); box-shadow: 0 0 0 2px rgba(220, 47, 76, 0.18); }
.node-inspector { position: absolute; left: 16px; bottom: 16px; z-index: 20; width: 360px; max-height: 62%; overflow-y: auto; padding: 14px 16px; border: 1px solid var(--wb-border); border-radius: var(--wb-radius-lg); background: var(--wb-raise); box-shadow: var(--wb-shadow-lg); display: flex; flex-direction: column; gap: 10px; }
.inspector-head { display: flex; flex-direction: column; align-items: stretch; gap: 8px; color: var(--wb-text); font-size: 13px; }
.inspector-actions { display: flex; gap: 6px; }
.inspector-row { display: flex; align-items: center; gap: 12px; }
.inspector-row > span { flex: 0 0 120px; color: var(--wb-text-2); font-size: 12.5px; font-family: var(--wb-font-mono, monospace); }
.inspector-help { padding: 8px 10px; border-radius: 8px; background: var(--wb-brand-soft); color: var(--wb-brand); font-size: 12px; line-height: 1.6; }
.inspector-hint { color: var(--wb-muted); font-size: 12px; line-height: 1.6; }
.inspector-hint code { padding: 1px 6px; border-radius: 5px; background: var(--wb-fill); font-size: 11.5px; }
.run-panel { position: absolute; top: 12px; right: 16px; bottom: 16px; z-index: 25; width: 360px; display: flex; flex-direction: column; border: 1px solid var(--wb-border); border-radius: var(--wb-radius-lg); background: var(--wb-raise); box-shadow: var(--wb-shadow-lg); overflow: hidden; }
.run-panel-head { display: flex; align-items: center; gap: 10px; padding: 12px 14px; border-bottom: 1px solid var(--wb-border); }
.run-panel-head strong { color: var(--wb-text); font-size: 13.5px; font-weight: 600; }
.run-panel-head .refresh { margin-left: auto; }
.panel-close { padding: 0 2px; border: 0; background: transparent; color: var(--wb-muted); font-size: 20px; line-height: 1; }
.panel-close:hover { color: var(--wb-text); }
.run-panel-body { flex: 1; min-height: 0; overflow-y: auto; padding: 8px 10px 14px; }
.run-detail { margin-top: 10px; padding-top: 10px; border-top: 1px solid var(--wb-border); }
.run-detail-head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 6px; }
.run-detail-head strong { color: var(--wb-text); font-size: 13px; font-weight: 600; }
.refresh { border: 0; background: transparent; color: var(--wb-muted); font-size: 12px; }
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

/* 运行对话框 */
.run-dialog-backdrop {
  position: absolute;
  inset: 0;
  z-index: 40;
  display: flex;
  align-items: center;
  justify-content: center;
  background: rgba(24, 28, 40, 0.42);
}
.run-dialog {
  display: flex;
  flex-direction: column;
  gap: 12px;
  width: min(480px, calc(100% - 48px));
  max-height: calc(100% - 64px);
  overflow-y: auto;
  padding: 18px 20px;
  border-radius: 14px;
  background: var(--wb-surface);
  box-shadow: var(--wb-shadow-lg);
}
.run-dialog-head { display: flex; align-items: center; justify-content: space-between; gap: 10px; }
.run-dialog-head h2 { font-size: 15px; font-weight: 600; color: var(--wb-text); }
.run-dialog-field { display: flex; flex-direction: column; gap: 6px; }
.run-dialog-field > span { color: var(--wb-text-2); font-size: 12.5px; }
.run-dialog-key { font-family: var(--wb-font-mono, monospace); font-size: 12px; color: var(--wb-text-2); }
.run-dialog-error { color: var(--wb-danger); font-size: 12.5px; }
.run-dialog-actions { display: flex; justify-content: flex-end; gap: 10px; margin-top: 4px; }
</style>
