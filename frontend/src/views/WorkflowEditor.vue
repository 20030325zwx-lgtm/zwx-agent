<template>
  <div class="wf-page">
    <header class="wf-topbar">
      <div class="topbar-left">
        <router-link to="/workflows" class="back-link">← 工作流</router-link>
        <h1>{{ definition?.name || '工作流编辑' }}</h1>
        <span class="wf-version">v{{ definition?.version }} · {{ graphNodes.length }} 个节点</span>
        <span v-if="graphDirty" class="dirty-badge" title="画布有未保存的修改；运行使用已保存版本的快照">未保存</span>
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
          <Spinner v-if="starting" /> {{ starting ? '发起中…' : '运行' }}
        </button>
        <button type="button" class="ghost-btn" :disabled="!canUndo" title="撤销 (Ctrl+Z)" @click="undo">
          <Undo2 :size="14" /> 撤销
        </button>
        <button type="button" class="ghost-btn" :disabled="!canRedo" title="重做 (Ctrl+Shift+Z)" @click="redo">
          <Redo2 :size="14" /> 重做
        </button>
        <button type="button" class="ghost-btn" @click="autoLayout">自动布局</button>
        <button type="button" class="ghost-btn" :disabled="savingVersion" @click="saveVersion()">
          <Spinner v-if="savingVersion" /> {{ savingVersion ? '保存中…' : '保存新版本' }}
        </button>
        <button
          type="button"
          class="ghost-btn"
          :class="{ active: libraryOpen }"
          @click="libraryOpen = !libraryOpen"
        >
          节点库
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

    <div v-else class="wf-body">
      <!-- 左侧：节点库（可收起），点击添加到画布；也可从端口拖到空白处就地添加 -->
      <aside v-show="libraryOpen" class="wf-library" aria-label="节点库">
        <div class="library-head">
          <strong>节点库</strong>
          <input v-model.trim="librarySearch" class="wf-input compact library-search" placeholder="搜索节点" />
        </div>
        <div v-for="group in filteredLibrary" :key="group.name" class="library-group">
          <p class="library-group-name">{{ group.name }}</p>
          <button
            v-for="nodeType in group.items"
            :key="nodeType.type"
            type="button"
            class="library-item"
            :title="nodeType.hint"
            @click="addNode(nodeType.type, null)"
          >
            <span class="palette-dot" :style="{ background: nodeType.color }" />
            <span class="library-item-label">{{ nodeType.label }}</span>
          </button>
        </div>
        <p v-if="!filteredLibrary.some(group => group.items.length)" class="library-hint">没有匹配的节点</p>
        <p class="library-hint">从节点端口拖到空白处可就地添加并自动连线。</p>
      </aside>

      <div class="canvas-area">
        <div v-if="connectDrag.dragging" class="drag-hint">
          拖到空白处松手添加节点 · 拖到节点端口直接连线（类型匹配时自动完成参数绑定）
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
              <span
                v-else-if="isTerminalProducer(nodeProps.id)"
                class="node-status node-output-tag"
                title="该节点的产物会自动归入创作项目素材库，不需要下游连线"
              >产物入库</span>
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
            v-for="nodeType in addMenuTypes"
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
      </div>

      <!-- 右侧：所选节点的参数面板（按需出现） -->
      <aside v-if="selectedNode" class="node-inspector" aria-label="节点设置">
        <div class="inspector-head">
          <strong>节点设置：{{ selectedNode.data.label }}</strong>
          <div class="inspector-actions">
            <button type="button" class="ghost-btn" @click="openAddMenuForNode(selectedNode.id)">添加下游节点</button>
            <button type="button" class="ghost-btn danger" @click="removeSelectedNode">删除节点</button>
            <button type="button" class="panel-close" aria-label="关闭节点设置" @click="clearSelection">×</button>
          </div>
        </div>
        <div class="inspector-section">
          <div class="inspector-section-head">
            <strong>运行输入字段</strong>
            <button type="button" class="ghost-btn mini" @click="addRunInput">添加字段</button>
          </div>
          <p class="inspector-hint">
            运行者只会看到这些业务字段，不会接触模板语法；在参数里点下方引用 chips 即可取值。
          </p>
          <div v-for="(field, index) in editingInputs" :key="index" class="run-input-row">
            <input v-model.trim="field.key" class="wf-input compact ri-key" placeholder="key" maxlength="32" />
            <input v-model.trim="field.label" class="wf-input compact ri-label" placeholder="显示名称" maxlength="20" />
            <select v-model="field.type" class="wf-input compact ri-type">
              <option value="text">文本</option>
              <option value="asset">单张素材</option>
              <option value="assets">多张素材</option>
            </select>
            <label class="ri-required"><input v-model="field.required" type="checkbox" /> 必填</label>
            <input v-model.trim="field.placeholder" class="wf-input compact" placeholder="占位提示（可选）" maxlength="60" />
            <button type="button" class="ghost-btn mini" @click="editingInputs.splice(index, 1)">删除</button>
          </div>
        </div>

        <label class="inspector-row">
          <span>名称</span>
          <input v-model.trim="selectedNode.data.label" class="wf-input compact" maxlength="24" @input="scheduleCommit" />
        </label>
        <div v-if="runFormFields.length" class="ref-chips" aria-label="插入运行输入引用">
          <button v-for="field in runFormFields" :key="'chip-' + field.key" type="button" class="ref-chip"
                  :title="`把 {{input.${field.key}}} 插入到最近编辑的参数`"
                  @click="insertInputRef(field.key)">
            + {{ field.label }}
          </button>
        </div>
        <div v-for="(value, key) in selectedNode.data.params" :key="key" class="inspector-row align-top">
          <span class="param-key">{{ key }}</span>
          <AssetParamField
            v-if="isAssetParam(key)"
            :model-value="String(selectedNode.data.params[key] || '')"
            :multiple="key === 'imageAssetIds'"
            @update:model-value="value => { selectedNode.data.params[key] = value; scheduleCommit() }"
          />
          <textarea
            v-else-if="LONG_PARAM_KEYS.has(key)"
            v-model="selectedNode.data.params[key]"
            class="wf-input compact textarea"
            rows="3"
            :placeholder="paramPlaceholder(key)"
            @focus="lastFocusParam = key"
            @input="scheduleCommit"
          />
          <input v-else v-model="selectedNode.data.params[key]" class="wf-input compact"
                 :placeholder="paramPlaceholder(key)" @focus="lastFocusParam = key" @input="scheduleCommit" />
        </div>
        <p class="inspector-help">{{ nodeHelp(selectedNode.data.nodeType) }}</p>
        <p class="inspector-hint">
          参数支持引用：<code v-text="'{{input.keyword}}'"></code> 引用运行输入，
          <code v-text="'{{节点key.output}}'"></code> 引用上游输出。
          撤销只作用于画布编辑，不影响已发起的运行与已产生的素材。
        </p>
      </aside>
    </div>

    <!-- 底部：运行与结果区（可收起） -->
    <aside v-if="runPanelOpen" class="run-panel" aria-label="运行记录">
      <div class="run-panel-head">
        <strong>运行记录</strong>
        <span class="run-panel-note">运行使用发起时的定义快照，此后编辑不影响进行中的运行</span>
        <button v-if="filteredRuns.length" type="button" class="refresh" @click="refresh">{{ pollLabel }}</button>
        <button type="button" class="panel-close" aria-label="关闭运行记录" @click="runPanelOpen = false">×</button>
      </div>
      <div class="run-panel-body">
        <div class="run-list-col">
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
        </div>

        <div v-if="detail" class="run-detail">
          <div class="run-detail-head">
            <strong>运行详情 <span class="run-meta">定义快照 v{{ detail.definitionVersion }}</span></strong>
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
              <button v-if="canRerun" type="button" class="rerun-btn" title="以此步为起点重跑：祖先产物复用，不重复计费"
                      @click="rerunFromStep(step.key)">从此步重跑</button>
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
        <div v-if="runErrors.length" class="run-errors">
          <p class="run-errors-title">运行前检查未通过（{{ runErrors.length }}）：</p>
          <button v-for="err in runErrors" :key="err.key + err.message" type="button" class="run-error-item"
                  @click="locateNode(err.key)">
            <strong>{{ err.name }}</strong>
            <span>{{ err.message }}</span>
            <em>定位</em>
          </button>
        </div>
        <template v-else>
          <div v-for="field in runFormFields" :key="field.key" class="run-dialog-field">
            <span class="run-dialog-key">{{ field.label }}<em v-if="field.required" class="req-star">*</em></span>
            <AssetParamField
              v-if="field.kind === 'asset' || field.kind === 'assets'"
              :model-value="runDialog.values[field.key] || ''"
              :multiple="field.kind === 'assets'"
              @update:model-value="value => runDialog.values[field.key] = value"
            />
            <input v-else v-model.trim="runDialog.values[field.key]" class="wf-input compact" maxlength="4000"
                   :placeholder="field.placeholder || field.label" />
          </div>
          <p v-if="missingRequiredFields.length" class="run-dialog-error">
            请填写：{{ missingRequiredFields.map(field => field.label).join('、') }}
          </p>
          <label class="run-dialog-field">
            <span>目标创作项目（产物归入该项目）</span>
            <select v-model="runDialog.projectId" class="wf-input compact">
              <option value="" disabled>选择项目</option>
              <option v-for="project in runDialogProjects" :key="project.id" :value="project.id">
                {{ project.name }}
              </option>
            </select>
          </label>
        </template>
        <p v-if="runDialog.error" class="run-dialog-error">{{ runDialog.error }}</p>
        <div class="run-dialog-actions">
          <button type="button" class="ghost-btn" @click="runDialog.open = false">取消</button>
          <button type="button" class="wf-run" :disabled="starting || !canStartRun" @click="submitRunDialog()">
            <Spinner v-if="starting" /> {{ starting ? '发起中…' : '发起运行' }}
          </button>
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
import { History, Redo2, Undo2 } from 'lucide-vue-next'
import '@vue-flow/core/dist/style.css'
import '@vue-flow/core/dist/theme-default.css'
import AssetParamField from '../components/workflow/AssetParamField.vue'
import { buildRunForm } from './creative/workflowInputs'
import Spinner from '../components/ui/Spinner.vue'
import {
  LONG_PARAM_KEYS, NODE_CATALOG, NODE_GROUPS, bindingExpression, inferEdgeLabel,
  matchBinding, nodeColor, nodeTypeLabel, validateGraphForRun
} from './workflow/nodeCatalog'
import {
  getCreativeAssetBlob,
  getWorkflowDefinition,
  getWorkflowRun,
  listCreativeProjects,
  listWorkflows,
  rerunWorkflowFrom,
  saveWorkflowVersion,
  startWorkflow
} from '../api'

const route = useRoute()
const { screenToFlowCoordinate } = useVueFlow()
const workflowKey = computed(() => String(route.params.workflowKey || ''))
const definition = ref(null)
// 运行输入声明（R03 完整版）：运行者只见业务字段；编辑器负责声明与「插入引用」
const editingInputs = ref([])
const lastFocusParam = ref('')
const loadError = ref('')
const runs = ref([])
const detail = ref(null)
const selectedId = ref(null)
const runPanelOpen = ref(false)
const libraryOpen = ref(true)
const librarySearch = ref('')
const keyword = ref('')
const loading = ref(true)
const starting = ref(false)
const savingVersion = ref(false)
const flowNodes = ref([])
const flowEdges = ref([])
// 无下游连线的产物类节点（媒体生成/编辑、海报、文件导出）：产物自动入库，属有意的终点而非漏连
const PRODUCER_TYPES = new Set(['media_image_edit', 'media_image_generate', 'image_generate', 'poster_render', 'file_export'])
const terminalProducerIds = computed(() => {
  const hasOutgoing = new Set(flowEdges.value.map(edge => edge.source))
  return new Set(flowNodes.value
    .filter(node => PRODUCER_TYPES.has(node.data?.nodeType) && !hasOutgoing.has(node.id))
    .map(node => node.id))
})
function isTerminalProducer(nodeId) {
  return terminalProducerIds.value.has(nodeId)
}
const selectedNode = ref(null)
const connectDrag = reactive({ sourceId: '', justConnected: false, dragging: false })
const expandedOutput = ref('')
const addMenu = reactive({ open: false, x: 0, y: 0, sourceId: '', flowX: 0, flowY: 0 })
const addMenuSourceName = computed(() => {
  const node = flowNodes.value.find(item => item.id === addMenu.sourceId)
  return node?.data?.label || ''
})
const addMenuTypes = computed(() => NODE_GROUPS.flatMap(group => group.items))
const filteredLibrary = computed(() => {
  const q = librarySearch.value.toLowerCase()
  if (!q) return NODE_GROUPS
  return NODE_GROUPS
    .map(group => ({ name: group.name, items: group.items.filter(item => item.label.toLowerCase().includes(q)) }))
    .filter(group => group.items.length)
})

let pollTimer = null
let suppressMenuCloseUntil = 0
let nodeCounter = 0

const graphDirty = ref(false)

const graphNodes = computed(() => flowNodes.value)
const filteredRuns = computed(() => runs.value.filter(run => run.workflowKey === workflowKey.value))
const pollLabel = computed(() => (filteredRuns.value.some(run => run.status === 'RUNNING') ? '自动刷新中' : '刷新'))

function nodeHelp(type) {
  return NODE_CATALOG[type]?.help || ''
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

// ---------------------------------------------------------------- 编辑历史（撤销/重做）

// 快照栈：每次提交编辑后的完整图状态（上限 50）。
// 撤销/重做只作用于画布与定义——运行记录、素材与已产生的费用是既成事实，不受影响。
const historyStack = ref([])
const futureStack = ref([])
let commitTimer = null
const canUndo = computed(() => historyStack.value.length > 1)
const canRedo = computed(() => futureStack.value.length > 0)

function snapshotGraph() {
  return JSON.stringify({ nodes: flowNodes.value, edges: flowEdges.value })
}

/** 提交一次编辑（带去重）；连续变更（拖动/输入）经 scheduleCommit 合并。 */
function commitHistory() {
  if (commitTimer) { clearTimeout(commitTimer); commitTimer = null }
  const snap = snapshotGraph()
  if (historyStack.value[historyStack.value.length - 1] === snap) return
  historyStack.value.push(snap)
  if (historyStack.value.length > 50) historyStack.value.shift()
  futureStack.value = []
}

function scheduleCommit() {
  if (commitTimer) clearTimeout(commitTimer)
  commitTimer = setTimeout(commitHistory, 600)
}

function restoreSnapshot(json) {
  try {
    const parsed = JSON.parse(json)
    flowNodes.value = parsed.nodes || []
    flowEdges.value = parsed.edges || []
    graphDirty.value = true
    if (selectedNode.value && !flowNodes.value.some(node => node.id === selectedNode.value.id)) {
      selectedNode.value = null
    } else if (selectedNode.value) {
      selectedNode.value = flowNodes.value.find(node => node.id === selectedNode.value.id) || null
    }
  } catch (error) { /* 快照损坏时忽略 */ }
}

function undo() {
  commitHistory()
  if (historyStack.value.length <= 1) return
  futureStack.value.push(historyStack.value.pop())
  restoreSnapshot(historyStack.value[historyStack.value.length - 1])
}

function redo() {
  commitHistory()
  if (!futureStack.value.length) return
  historyStack.value.push(futureStack.value.pop())
  restoreSnapshot(historyStack.value[historyStack.value.length - 1])
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
  labelShowBg: true,
  labelBgPadding: [4, 2],
  labelBgStyle: { fill: '#F7F8FA', fillOpacity: 0.92 },
  labelStyle: { fill: '#5B6472', fontSize: 10 },
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
  // 旧图兼容：绑定 label 从目标节点 params 的 {{source.output...}} 引用反推（手写表达式同样识别）
  const paramsByKey = new Map(graph.nodes.map(node => [node.key, node.params || {}]))
  flowEdges.value = graph.edges.map((edge, index) => ({
    id: `e-${edge.from}-${edge.to}-${index}`,
    source: edge.from,
    target: edge.to,
    label: inferEdgeLabel(paramsByKey.get(edge.to), edge.from) || undefined,
    ...EDGE_DEFAULTS
  }))
  selectedNode.value = null
  graphDirty.value = false
  historyStack.value = [snapshotGraph()]
  futureStack.value = []
}

async function loadDefinition() {
  try {
    const view = await getWorkflowDefinition(workflowKey.value)
    definition.value = view
    applyGraph(view)
    editingInputs.value = (view.inputs || []).map(field => ({ ...field }))
    loadError.value = ''
  } catch (error) {
    loadError.value = error?.response?.data?.error || '画布加载失败'
    message.error(loadError.value)
  }
}

function addNode(type, sourceId, position) {
  commitHistory()
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
        params: { ...(NODE_CATALOG[type]?.defaults || {}) },
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
  graphDirty.value = true
  commitHistory()
}

function autoLayout() {
  commitHistory()
  const edges = flowEdges.value.map(edge => ({ from: edge.source, to: edge.target }))
  const positioned = layeredPositions({ nodes: flowNodes.value.map(node => ({ key: node.id })), edges })
  const positionByKey = new Map(positioned.map(item => [item.node.key, { x: item.x, y: item.y }]))
  flowNodes.value = flowNodes.value.map(node => ({
    ...node,
    position: positionByKey.get(node.id) || node.position
  }))
  graphDirty.value = true
  commitHistory()
}

/**
 * 屏幕坐标 → 画布坐标。必须走 vue-flow 自己的转换：
 * 缩放/平移的 transform 挂在 .vue-flow__transformationpane 上，
 * 手动读取 .vue-flow__viewport 会拿到 none，新增节点就会落到错误位置。
 */
function clientToFlowPoint(clientX, clientY) {
  return screenToFlowCoordinate({ x: clientX, y: clientY })
}

/**
 * 连线即绑定（评审 A1）：类型兼容时自动把引用表达式写入目标节点参数，
 * 边显示绑定参数名；目标只有自由文本输入时仅作为执行依赖并提示；类型不兼容拒绝连线。
 */
function onConnect(connection) {
  if (!connection?.source || !connection?.target || connection.source === connection.target) return
  const exists = flowEdges.value.some(edge => edge.source === connection.source && edge.target === connection.target)
  if (exists) return
  const sourceNode = flowNodes.value.find(node => node.id === connection.source)
  const targetNode = flowNodes.value.find(node => node.id === connection.target)
  if (!sourceNode || !targetNode) return
  const match = matchBinding(sourceNode.data.nodeType, targetNode.data.nodeType)
  let label = ''
  if (match?.candidates) {
    const bound = bindConnection(match, connection.source, targetNode.data.params)
    if (bound) {
      label = bound.param
      message.success(`已绑定 ${targetNode.data.label}.${bound.param}`)
    } else {
      message.info(`已连接为执行依赖；${targetNode.data.label} 的同类输入已有绑定，未覆盖`)
    }
  } else if (match?.dependentOnly) {
    message.info(`已连接为执行依赖；可在 ${targetNode.data.label} 的参数中引用 {{${connection.source}.output}}`)
  } else {
    message.warning(`不能连接：${nodeTypeLabel(sourceNode.data.nodeType)} 的输出与 ${nodeTypeLabel(targetNode.data.nodeType)} 的输入类型不匹配`)
    return
  }
  flowEdges.value = [
    ...flowEdges.value,
    {
      id: `e-${connection.source}-${connection.target}-${Date.now().toString(36)}`,
      source: connection.source,
      target: connection.target,
      label: label || undefined,
      ...EDGE_DEFAULTS
    }
  ]
  connectDrag.justConnected = true
  setTimeout(() => { connectDrag.justConnected = false }, 50)
  graphDirty.value = true
  commitHistory()
}

/** 在第一个可用的兼容输入上写绑定：单值取空位；多值（imageAssetIds）追加去重并受上限约束。 */
function bindConnection(match, sourceKey, params) {
  const expression = bindingExpression(sourceKey, match.src)
  for (const input of match.candidates) {
    const current = String(params[input.param] ?? '')
    if (input.multiple) {
      const parts = current.split(',').map(part => part.trim()).filter(Boolean)
      if (parts.includes(expression)) return { param: input.param, expression }
      if (parts.length >= (input.max || 3)) continue
      parts.push(expression)
      params[input.param] = parts.join(',')
      return { param: input.param, expression }
    }
    if (!current.trim()) {
      params[input.param] = expression
      return { param: input.param, expression }
    }
  }
  return null
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
  const targetNode = flowNodes.value.find(node => node.id === connection.target)
  flowEdges.value = flowEdges.value.map(item => item.id === edge.id
    ? { ...item, source: connection.source, target: connection.target,
        label: inferEdgeLabel(targetNode?.data?.params, connection.source) || undefined }
    : item)
  graphDirty.value = true
  commitHistory()
}

function markDirtyOnChange(changes) {
  // dimensions/select 是初始化与选中事件，不代表用户编辑；否则刚加载的画布就会被当成"未保存"
  const userEdited = (changes || []).some(change =>
    change.type !== 'dimensions' && change.type !== 'select');
  if (!userEdited) return
  graphDirty.value = true
  const structural = (changes || []).some(change => change.type === 'add' || change.type === 'remove')
  if (structural) commitHistory()
  else scheduleCommit() // 拖动等连续变更：停顿后合并为一次历史
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
  commitHistory()
  const id = selectedNode.value.id
  flowNodes.value = flowNodes.value.filter(node => node.id !== id)
  flowEdges.value = flowEdges.value.filter(edge => edge.source !== id && edge.target !== id)
  selectedNode.value = null
  graphDirty.value = true
  commitHistory()
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
    await saveWorkflowVersion(workflowKey.value, { graph, inputs: editingInputs.value })
    graphDirty.value = false
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

// ---------------------------------------------------------------- 运行对话框

const runDialog = reactive({ open: false, projectId: '', values: {}, error: '' })
const runDialogProjects = ref([])
const runErrors = ref([])
const isAssetKey = key => /AssetIds?$/.test(key)

/**
 * 运行表单字段：声明式 inputs 优先（与创作首页/项目页共用 buildRunForm 渲染约定），
 * 未声明时回退扫描推导；编辑器内未保存的输入声明编辑也即时生效。
 */
const runFormFields = computed(() => {
  if (editingInputs.value.length) {
    return editingInputs.value.map(field => ({
      key: field.key,
      label: field.label || field.key,
      kind: field.type === 'asset' ? 'asset' : field.type === 'assets' ? 'assets' : 'text',
      required: !!field.required,
      placeholder: field.placeholder || ''
    }))
  }
  return buildRunForm({ graph: { nodes: flowNodes.value.map(node => ({ params: node.data?.params })) } })
})

const missingRequiredFields = computed(() => runFormFields.value.filter(field => {
  if (!field.required) return false
  return !String(runDialog.values[field.key] || '').trim()
}))

const canStartRun = computed(() => {
  if (runErrors.value.length) return false
  if (!runFormFields.value.length) return false
  return missingRequiredFields.value.length === 0
})

/** 运行前预检（评审 A1）：缺失输入/未知引用在提交前定位到具体节点；后端保存/运行时独立拒绝。 */
function openRunDialog() {
  if (graphDirty.value) {
    message.warning('画布有未保存的修改，先保存为版本再运行')
    return
  }
  runErrors.value = validateGraphForRun(flowNodes.value)
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

function locateNode(key) {
  const node = flowNodes.value.find(item => item.id === key)
  if (node) selectedNode.value = node
}

async function submitRunDialog() {
  if (!canStartRun.value || starting.value) return
  starting.value = true
  runDialog.error = ''
  try {
    const input = {}
    for (const field of runFormFields.value) {
      const value = String(runDialog.values[field.key] || '').trim()
      if (value) input[field.key] = value
    }
    const run = await startWorkflow(workflowKey.value, input.keyword || '', runDialog.projectId || null, input)
    runDialog.open = false
    keyword.value = input.keyword || ''
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

// ---------------------------------------------------------------- 输入字段编辑与引用插入

function addRunInput() {
  const base = `input_${Date.now().toString(36).slice(-4)}`
  const key = editingInputs.value.some(field => field.key === base) ? `${base}_2` : base
  editingInputs.value.push({ key, label: '新字段', type: 'text', required: false, placeholder: '' })
}

/** 点击 chip 把 {{input.key}} 追加到最近聚焦的参数框（语法由此降级为高级路径）。 */
function insertInputRef(key) {
  if (!selectedNode.value) {
    message.info('先在画布选中一个节点，再插入引用')
    return
  }
  const params = selectedNode.value.data.params
  const keys = Object.keys(params)
  const fallback = keys.find(name => LONG_PARAM_KEYS.has(name)) || keys[0]
  const target = (lastFocusParam.value && keys.includes(lastFocusParam.value))
    ? lastFocusParam.value : fallback
  if (!target) {
    message.info('该节点没有可插入的文本参数')
    return
  }
  params[target] = String(params[target] ?? '') + `{{input.${key}}}`
  scheduleCommit()
  message.success(`已插入到参数 ${target}`)
}

// ---------------------------------------------------------------- 从节点重跑（C 批）

const canRerun = computed(() => !!detail.value && ['SUCCEEDED', 'FAILED'].includes(detail.value.status))

async function rerunFromStep(nodeKey) {
  if (!detail.value) return
  try {
    const run = await rerunWorkflowFrom(detail.value.id, nodeKey)
    selectedId.value = run.id
    detail.value = await resolveArtifactUrls(run)
    applyRunStatus(detail.value)
    runPanelOpen.value = true
    await refresh()
    message.success('已发起重跑：上游产物复用，不重复调用已成功节点')
  } catch (error) {
    message.error(error?.response?.data?.error || '重跑发起失败')
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

function handleHistoryKeys(event) {
  if (!(event.ctrlKey || event.metaKey)) return
  const key = event.key.toLowerCase()
  if (key !== 'z' && key !== 'y') return
  const target = event.target
  if (target instanceof HTMLElement && ['INPUT', 'TEXTAREA', 'SELECT'].includes(target.tagName)) return
  event.preventDefault()
  if (key === 'y' || (key === 'z' && event.shiftKey)) redo()
  else undo()
}

onMounted(async () => {
  document.addEventListener('click', closeAddMenu)
  document.addEventListener('keydown', handleDeleteKey)
  document.addEventListener('keydown', handleHistoryKeys)
  document.addEventListener('keydown', event => { if (event.key === 'Escape') addMenu.open = false })
  await refresh()
  await loadDefinition()
})
onBeforeUnmount(() => {
  if (pollTimer) clearTimeout(pollTimer)
  if (commitTimer) clearTimeout(commitTimer)
  document.removeEventListener('click', closeAddMenu)
  document.removeEventListener('keydown', handleDeleteKey)
  document.removeEventListener('keydown', handleHistoryKeys)
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
.dirty-badge { padding: 2px 10px; border-radius: 999px; background: rgba(199, 123, 30, 0.14); color: #C77B1E; font-size: 12px; white-space: nowrap; }
.topbar-actions { display: flex; align-items: center; gap: 8px; flex: 0 0 auto; }
.keyword-input { width: 180px; flex: 0 0 auto; }
.runs-toggle { display: inline-flex; align-items: center; gap: 6px; }
.runs-toggle.active, .ghost-btn.active { border-color: var(--wb-brand); color: var(--wb-brand); }
.run-badge { padding: 0 6px; border-radius: 999px; background: var(--wb-brand-soft); color: var(--wb-brand); font-size: 11px; }

/* 主体三栏：左节点库 / 中画布 / 右参数面板 */
.wf-body { flex: 1; min-height: 0; display: flex; }
.wf-library { width: 224px; flex: 0 0 224px; display: flex; flex-direction: column; gap: 10px; overflow-y: auto; padding: 12px; border-right: 1px solid var(--wb-border); background: var(--wb-surface); }
.library-head { display: flex; flex-direction: column; gap: 8px; }
.library-head strong { color: var(--wb-text); font-size: 13px; font-weight: 600; }
.library-search { flex: 1; }
.library-group { display: flex; flex-direction: column; gap: 2px; }
.library-group-name { padding: 4px 6px; color: var(--wb-muted); font-size: 11px; font-weight: 600; letter-spacing: 0.05em; }
.library-item { display: flex; align-items: center; gap: 8px; padding: 7px 9px; border: 0; border-radius: 8px; background: transparent; color: var(--wb-text); font-size: 12.5px; text-align: left; }
.library-item:hover { background: var(--wb-fill); }
.library-item-label { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.library-hint { padding: 4px 6px; color: var(--wb-muted); font-size: 11.5px; line-height: 1.6; }
.canvas-area { position: relative; flex: 1; min-height: 0; background: var(--wb-surface-2); }
.canvas-area :deep(.vue-flow) { width: 100%; height: 100%; background: var(--wb-surface-2); }
.canvas-area :deep(.vue-flow__node) { z-index: 6; }
.canvas-area :deep(.vue-flow__edge) { z-index: 1; }
.canvas-area :deep(.vue-flow__node-default) { padding: 0; border: 0; background: transparent; width: auto; box-shadow: none; }
.palette-dot { width: 8px; height: 8px; border-radius: 50%; flex: 0 0 8px; }

.ghost-btn { display: inline-flex; align-items: center; gap: 5px; padding: 7px 14px; border: 1px solid var(--wb-border); border-radius: 9px; background: transparent; color: var(--wb-text-2); font-size: 12.5px; }
.ghost-btn:hover { border-color: var(--wb-brand); color: var(--wb-brand); }
.ghost-btn.danger:hover { border-color: var(--wb-danger); color: var(--wb-danger); }
.wf-input { flex: 1; padding: 9px 13px; border: 1px solid var(--wb-border); border-radius: 10px; background: var(--wb-surface-2); color: var(--wb-text); font-size: 13px; outline: none; }
.wf-input.compact { padding: 7px 11px; font-size: 12.5px; }
.wf-input:focus { border-color: var(--wb-brand); box-shadow: 0 0 0 3px var(--wb-brand-soft); }
.wf-run { padding: 9px 20px; border: 0; border-radius: 10px; background: var(--wb-brand); color: var(--wb-brand-contrast); font-size: 13px; font-weight: 500; }
.wf-run:disabled { background: var(--wb-fill); color: var(--wb-muted); }

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
.node-output-tag {
  background: var(--wb-brand-soft);
  color: var(--wb-brand);
  cursor: help;
}
.wf-node.is-start { border-color: var(--wb-brand); background: var(--wb-brand-soft); }
.wf-node.is-start .node-name { color: var(--wb-brand); }
.wf-node.is-running { border-color: var(--wb-info); box-shadow: 0 0 0 2px rgba(37, 144, 255, 0.18); }
.wf-node.is-done { border-color: var(--wb-success); }
.wf-node.is-failed { border-color: var(--wb-danger); box-shadow: 0 0 0 2px rgba(220, 47, 76, 0.18); }

.inspector-row.align-top { align-items: flex-start; }
.textarea { resize: vertical; min-height: 64px; font-family: var(--wb-font-mono, monospace); font-size: 12px; line-height: 1.5; }
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

/* 右侧参数面板：常规文档流内的一栏，选中节点时出现 */
.node-inspector { width: 360px; flex: 0 0 360px; overflow-y: auto; padding: 14px 16px; border-left: 1px solid var(--wb-border); background: var(--wb-raise); display: flex; flex-direction: column; gap: 10px; }
.inspector-head { display: flex; flex-direction: column; align-items: stretch; gap: 8px; color: var(--wb-text); font-size: 13px; }
.inspector-actions { display: flex; gap: 6px; }
.inspector-row { display: flex; align-items: center; gap: 12px; }
.inspector-row > span { flex: 0 0 120px; color: var(--wb-text-2); font-size: 12.5px; font-family: var(--wb-font-mono, monospace); }
.inspector-help { padding: 8px 10px; border-radius: 8px; background: var(--wb-brand-soft); color: var(--wb-brand); font-size: 12px; line-height: 1.6; }
.inspector-hint { color: var(--wb-muted); font-size: 12px; line-height: 1.6; }
.inspector-hint code { padding: 1px 6px; border-radius: 5px; background: var(--wb-fill); font-size: 11.5px; }

/* 运行输入字段编辑 + 引用插入 chips */
.inspector-section { display: flex; flex-direction: column; gap: 8px; padding: 10px 12px; border: 1px solid var(--wb-border); border-radius: 10px; background: var(--wb-fill); }
.inspector-section-head { display: flex; align-items: center; justify-content: space-between; }
.inspector-section-head strong { color: var(--wb-text); font-size: 12.5px; }
.run-input-row { display: flex; align-items: center; gap: 6px; flex-wrap: wrap; }
.run-input-row .ri-key { width: 92px; flex: 0 0 92px; font-family: var(--wb-font-mono, monospace); }
.run-input-row .ri-label { width: 88px; flex: 0 0 88px; }
.run-input-row .ri-type { width: 92px; flex: 0 0 92px; padding: 5px 7px; }
.run-input-row .ri-required { display: inline-flex; align-items: center; gap: 4px; color: var(--wb-text-2); font-size: 11.5px; white-space: nowrap; }
.run-input-row input:not(.ri-key):not(.ri-label):not([type="checkbox"]) { flex: 1; min-width: 90px; }
.run-input-row .wf-input.compact { padding: 5px 8px; font-size: 11.5px; }
.ref-chips { display: flex; flex-wrap: wrap; gap: 6px; }
.ref-chip { padding: 3px 10px; border: 1px dashed var(--wb-border-strong); border-radius: 999px; background: var(--wb-surface); color: var(--wb-text-2); font-size: 11.5px; }
.ref-chip:hover { border-color: var(--wb-brand); color: var(--wb-brand); }
.req-star { color: var(--wb-danger); font-style: normal; margin-left: 2px; }
.detail-step-wrap { position: relative; }
.rerun-btn { position: absolute; right: 6px; bottom: 6px; padding: 2px 8px; border: 1px solid var(--wb-border); border-radius: 7px; background: var(--wb-surface); color: var(--wb-text-2); font-size: 10.5px; }
.rerun-btn:hover { border-color: var(--wb-brand); color: var(--wb-brand); }

/* 底部运行与结果区：可收起，横向布局（左运行列表 + 右详情） */
.run-panel { flex: 0 0 300px; display: flex; flex-direction: column; border-top: 1px solid var(--wb-border); background: var(--wb-raise); overflow: hidden; }
.run-panel-head { display: flex; align-items: center; gap: 10px; padding: 10px 14px; border-bottom: 1px solid var(--wb-border); }
.run-panel-head strong { color: var(--wb-text); font-size: 13.5px; font-weight: 600; }
.run-panel-note { color: var(--wb-muted); font-size: 11.5px; }
.run-panel-head .refresh { margin-left: auto; }
.panel-close { padding: 0 2px; border: 0; background: transparent; color: var(--wb-muted); font-size: 20px; line-height: 1; }
.panel-close:hover { color: var(--wb-text); }
.run-panel-body { flex: 1; min-height: 0; display: flex; overflow: hidden; }
.run-list-col { width: 280px; flex: 0 0 280px; overflow-y: auto; padding: 8px 10px 14px; border-right: 1px solid var(--wb-border); }
.run-detail { flex: 1; min-width: 0; overflow-y: auto; padding: 10px 14px 14px; }
.run-detail { border-top: 0; }
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
.detail-step-wrap { border-bottom: 1px solid var(--wb-border); }
.detail-step-wrap:last-child { border-bottom: 0; }
.detail-step { width: 100%; border: 0; background: transparent; text-align: left; }
.detail-step { display: flex; align-items: center; gap: 11px; padding: 10px 4px; }
.detail-step + .detail-step { border-top: 1px solid var(--wb-border); }
.step-main { display: flex; flex: 1; flex-direction: column; gap: 3px; min-width: 0; }
.step-name { color: var(--wb-text); font-size: 13px; font-weight: 500; }
.step-message { color: var(--wb-muted); font-size: 12px; }
.step-status { color: var(--wb-muted); font-size: 12px; }
.step-output { padding: 4px 14px 12px 30px; }
.step-output pre { max-height: 220px; overflow: auto; padding: 10px 12px; border-radius: 9px; background: var(--wb-fill); color: var(--wb-text-2); font-size: 12px; line-height: 1.6; white-space: pre-wrap; word-break: break-all; }
.output-actions { display: flex; justify-content: flex-end; margin-top: 6px; }
.ghost-btn.mini { padding: 4px 10px; font-size: 11.5px; }
.detail-error { margin-top: 10px; padding: 10px 12px; border-radius: var(--wb-radius); background: rgba(220, 47, 76, 0.08); color: var(--wb-danger); font-size: 12.5px; }
.artifact { margin-top: 14px; text-align: center; }
.artifact-image { max-width: 380px; width: 100%; border: 1px solid var(--wb-border); border-radius: var(--wb-radius); box-shadow: var(--wb-shadow-md); }
.download-btn { display: inline-block; margin-top: 8px; padding: 6px 14px; border-radius: 9px; background: var(--wb-brand-soft); color: var(--wb-brand); font-size: 12.5px; }

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
.run-errors { display: flex; flex-direction: column; gap: 6px; }
.run-errors-title { color: var(--wb-danger); font-size: 12.5px; font-weight: 550; }
.run-error-item { display: flex; align-items: center; gap: 10px; padding: 8px 10px; border: 1px solid rgba(220, 47, 76, 0.35); border-radius: 9px; background: rgba(220, 47, 76, 0.05); text-align: left; }
.run-error-item strong { color: var(--wb-text); font-size: 12.5px; flex: 0 0 auto; }
.run-error-item span { flex: 1; min-width: 0; color: var(--wb-danger); font-size: 12px; }
.run-error-item em { flex: 0 0 auto; color: var(--wb-brand); font-size: 12px; font-style: normal; }
.run-error-item:hover { border-color: var(--wb-danger); }
.run-dialog-error { color: var(--wb-danger); font-size: 12.5px; }
.run-dialog-actions { display: flex; justify-content: flex-end; gap: 10px; margin-top: 4px; }
</style>
