<template>
  <div class="canvas-page">
    <header class="canvas-toolbar">
      <div class="canvas-title">
        <button class="canvas-icon" type="button" :title="inspectorOpen ? '收起节点编辑器' : '展开节点编辑器'" @click="inspectorOpen = !inspectorOpen"><PanelLeft :size="16" /></button>
        <span class="canvas-kicker">创作画布</span>
        <span class="canvas-save" :class="saveState"><i />{{ saveLabel }}</span>
      </div>
      <div class="canvas-actions">
        <button class="canvas-icon" type="button" title="撤销" :disabled="!undoStack.length" @click="undo"><Undo2 :size="16" /></button>
        <button class="canvas-icon" type="button" title="适应画布" @click="fitView"><Scan :size="16" /></button>
        <span class="canvas-divider" />
        <select v-model="runScope" class="run-scope" aria-label="运行范围"><option value="node">当前节点</option><option value="up_to">执行到此节点</option><option value="from_here">从此继续</option><option value="all">整条流程</option></select>
        <button v-if="activeCanvasRun && ['RUNNING', 'WAITING_EXTERNAL', 'WAITING_SELECTION'].includes(activeCanvasRun.run?.status)"
                class="canvas-tool" type="button" :disabled="busyNode" @click="cancelRun">停止</button>
        <button class="canvas-run" type="button" :title="runBlockedReason" :disabled="busyNode || !!runBlockedReason || (runScope === 'all' ? !nodes.some(isGenerationNode) : !canRun)" @click="runCanvas(runScope)">
          <Spinner v-if="busyNode" /><Play v-else :size="14" />{{ busyNode ? '提交中' : runScopeLabel }}
        </button>
      </div>
    </header>

    <div v-if="pageError" class="canvas-error"><AlertCircle :size="16" />{{ pageError }}</div>
    <div class="canvas-workspace">
      <aside class="canvas-inspector" v-if="selectedNode && inspectorOpen" aria-label="节点编辑器">
        <div class="inspector-heading">
          <div class="inspector-type"><span class="inspector-dot" :class="`kind-${selectedNode.type}`" />{{ typeLabel(selectedNode.type) }}</div>
          <div class="inspector-heading-actions"><button v-if="selectedNode.type !== 'start'" class="canvas-icon" type="button" title="删除节点" @click="removeSelected"><Trash2 :size="14" /></button><button class="canvas-icon" type="button" title="收起编辑器" @click="inspectorOpen = false"><X :size="14" /></button></div>
        </div>
        <label class="inspector-field">
          <span>节点名称</span>
          <input v-model="selectedNode.data.label" maxlength="48" @input="markChanged" />
        </label>

        <template v-if="selectedNode.type === 'start'">
          <p class="inspector-note">开始节点只标记流程起点，不调用模型、不产生素材。将执行输出连接到后续节点。每个节点必须等待全部上级成功完成。</p>
          <button class="canvas-tool" type="button" @click="runScope = 'all'">选择运行整条流程</button>
        </template>
        <template v-else-if="selectedNode.type === 'text_input'">
          <label class="inspector-field"><span>文本内容</span>
            <textarea v-model="selectedNode.data.text" rows="8" placeholder="写下场景、角色或需要传给下游的内容" @input="markChanged" />
          </label>
        </template>

        <template v-else-if="selectedNode.type === 'asset_input'">
          <div class="inspector-section-head"><span>项目素材</span><button class="inline-action" type="button" @click="loadAssets">刷新</button></div>
          <div v-if="assetLoading" class="inspector-empty">正在加载素材</div>
          <div v-else-if="!assets.length" class="inspector-empty">这个项目还没有素材</div>
          <button v-for="asset in assets" :key="asset.id" class="asset-option" :class="{ chosen: selectedNode.data.assetIds?.includes(asset.id) }"
                  type="button" @click="toggleAsset(asset.id)">
            <span class="asset-option-icon"><Image v-if="asset.mediaType === 'IMAGE'" :size="15" /><Film v-else :size="15" /></span>
            <span class="asset-option-name">{{ asset.displayName }}</span><Check v-if="selectedNode.data.assetIds?.includes(asset.id)" :size="15" />
          </button>
          <label class="upload-control"><Upload :size="15" />上传素材<input type="file" accept="image/*,video/mp4,video/quicktime,video/webm" @change="uploadAsset" /></label>
          <p class="inspector-note">视频可上传并保存；当前生成接口只支持图片参考输入。</p>
        </template>

        <template v-else>
          <section v-if="incomingBindings.length" class="upstream-section" aria-label="上游输入">
            <div class="inspector-section-head"><span>已连接的输入</span><span>{{ incomingBindings.length }} 条连接</span></div>
            <div v-for="binding in incomingBindings" :key="binding.edge.id" class="upstream-item">
              <header><span :class="'input-dot ' + binding.kind" /><strong>{{ binding.source.data.label }}</strong><small>{{ binding.kind === 'flow' ? '执行顺序' : binding.kind === 'text' ? '提示词' : '图片' }}</small><button type="button" :aria-label="'断开 ' + binding.source.data.label" @click="disconnect(binding.edge.id)"><X :size="12" /></button></header>
              <p v-if="binding.kind === 'text'">{{ binding.text || '上游尚未填写内容' }}</p>
              <p v-else-if="binding.kind === 'flow'">仅控制执行顺序，不传递提示词或素材</p>
              <p v-else>{{ binding.assets.length ? binding.assets.map(assetName).join('、') : '上游生成并选定结果后，将自动读取' }}</p>
            </div>
          </section>
          <label class="inspector-field"><span>生成模型</span>
            <DropdownSelect v-model="selectedNode.data.modelRowId" block :options="modelOptionsFor(selectedNode.type)"
                            :placeholder="modelOptionsFor(selectedNode.type).length ? '选择模型' : '未配置模型'" />
          </label>
          <label class="inspector-field"><span>提示词</span>
            <textarea v-model="selectedNode.data.prompt" rows="8" maxlength="500" placeholder="描述画面主体、动作、环境、镜头与风格" @input="markChanged" />
            <small>{{ (selectedNode.data.prompt || '').length }}/500</small>
          </label>
          <p v-if="selectedNode.data.skillSlug" class="inspector-note">起步技能：{{ selectedNode.data.skillSlug }}。技能只作为创作上下文保留，不会自动触发计费生成。</p>
          <div class="inspector-actions">
            <button class="optimize-button" type="button" :disabled="optimizing || !selectedNode.data.prompt?.trim()" @click="optimizePrompt">
              <Sparkles :size="14" />{{ optimizing ? '优化中' : '优化提示词' }}
            </button>
            <button v-if="optimizedPrompt" class="inline-action" type="button" @click="adoptPrompt">采用建议</button>
          </div>
          <p v-if="optimizedPrompt" class="optimized-preview">{{ optimizedPrompt }}</p>
          <details v-if="incomingBindings.some(binding => binding.kind === 'text')" class="effective-prompt"><summary>查看实际发送的提示词 · {{ effectiveNodePrompt.length }}/500</summary><p>{{ effectiveNodePrompt }}</p></details>
          <div v-if="selectedNode.type !== 'video_generate'" class="inspector-row">
            <label class="inspector-field"><span>画幅</span><select v-model="selectedNode.data.size" @change="markChanged"><option>1024*1024</option><option>1280*720</option><option>720*1280</option><option>1152*768</option><option>768*1152</option></select></label>
          </div>
          <label v-else class="inspector-field"><span>时长（秒）</span><input v-model.number="selectedNode.data.seconds" type="number" min="1" max="30" @change="markChanged" /></label>
          <div class="inspector-section-head"><span>输入素材</span><span class="input-count">{{ connectedAssetIds.length + (selectedNode.data.referenceAssetIds?.length || 0) }}</span></div>
          <div class="reference-list">
            <div v-for="assetId in allReferenceIds" :key="assetId" class="reference-row">
              <span>{{ assetName(assetId) }}</span><button v-if="!connectedAssetIds.includes(assetId)" type="button" class="remove-reference" aria-label="移除参考素材" @click="removeReference(assetId)"><X :size="13" /></button>
            </div>
            <p v-if="!allReferenceIds.length" class="inspector-empty">连接素材节点，或添加项目参考素材。</p>
          </div>
          <label class="upload-control"><Upload :size="14" />上传参考图片<input type="file" accept="image/*" @change="uploadAsset" /></label>
          <button class="inline-action add-reference" type="button" @click="showAssetPicker = !showAssetPicker"><Plus :size="14" />从项目素材选择</button>
          <div v-if="showAssetPicker" class="asset-picker">
            <button v-for="asset in compatibleAssets" :key="asset.id" type="button" @click="addReference(asset.id)">
              <span>{{ asset.displayName }}</span><Plus :size="14" />
            </button>
            <p v-if="!compatibleAssets.length" class="inspector-empty">暂无可用图片素材</p>
          </div>
          <div class="inspector-run">
            <button class="canvas-tool run-to-here" type="button" :disabled="busyNode || !canRun || !!upToBlocked"
                    :title="upToBlocked || (canRun ? '自动跳过已成功的上级节点' : '请先完善本节点的提示词与模型')" @click="runCanvas('up_to')">
              <Play :size="14" />{{ upToLabel }}
            </button>
            <p class="inspector-note">自动跳过已成功的上级节点，从缺失处执行到此节点。</p>
          </div>
        </template>
        <div v-if="selectedNode.data.status && selectedNode.data.status !== 'IDLE'" class="node-job-state" :class="statusTone(selectedNode.data.status)">
          <span>{{ statusLabel(selectedNode.data.status) }}</span><span v-if="selectedNode.data.error">{{ selectedNode.data.error }}</span>
          <div v-if="nodeOutputs.length" class="node-output-list">
            <button v-for="output in nodeOutputs" :key="output.assetId" type="button"
                    :class="{ selected: selectedNode.data.selectedAssetId === output.assetId }"
                    @click="selectOutput(output.assetId)">
              <img v-if="output.mediaType === 'IMAGE' && assetUrl(output.assetId)" :src="assetUrl(output.assetId)" alt="生成结果" />
              <span>{{ selectedNode.data.selectedAssetId === output.assetId ? '已选为下游输入' : '选为下游输入' }}</span>
            </button>
          </div>
        </div>
        <p v-if="actionError" class="inspector-error">{{ actionError }}</p>
        <p v-if="runBlockedReason" class="inspector-note" role="status">{{ runBlockedReason }}</p>
      </aside>

      <main class="canvas-stage" ref="stageRef" aria-label="无限创作画布">
        <VueFlow v-model:nodes="nodes" v-model:edges="edges" :node-types="nodeTypes" :min-zoom="0.25" :max-zoom="1.7"
                 :default-viewport="viewport" :fit-view-on-init="false" :delete-key-code="null" :apply-default="false" :connect-on-click="true"
                 :default-edge-options="{ type: 'default', interactionWidth: 24, style: { strokeWidth: 2 } }"
                 @node-click="selectNode" @pane-click="inspectorOpen = false" @connect="connectNodes" @edge-double-click="({ edge }) => disconnect(edge.id)"
                 @nodes-change="nodesChanged" @edges-change="edgesChanged" @move-end="viewportChanged">
          <Background pattern-color="#cdd2df" :gap="22" :size="1" />
          <Controls position="bottom-right" :show-fit-view="false" :show-interactive="false" />
        </VueFlow>
        <div class="canvas-guide"><span class="guide-dot" />蓝色控制执行 · 紫色传提示词 · 绿色传图片 · 下游等待全部上级完成 · 双击或选中按 Delete 删除连线</div>
        <div class="node-palette" role="toolbar" aria-label="添加节点">
          <span>添加节点</span>
          <button type="button" title="添加素材节点" @click="addNode('asset_input')"><ImagePlus :size="17" />素材</button>
          <button type="button" title="添加文本节点" @click="addNode('text_input')"><FileText :size="17" />文本</button>
          <i />
          <button type="button" title="添加图片生成节点" @click="addNode('image_generate')"><Image :size="17" />生图</button>
          <button type="button" title="添加图片编辑节点" @click="addNode('image_edit')"><WandSparkles :size="17" />编辑</button>
          <button type="button" title="添加视频生成节点" @click="addNode('video_generate')"><Clapperboard :size="17" />视频</button>
        </div>
        <div v-if="actionError" class="canvas-notice" role="alert"><AlertCircle :size="14" />{{ actionError }}<button type="button" aria-label="关闭提示" @click="actionError = ''"><X :size="13" /></button></div>
        <div v-if="canvasNotice" class="canvas-notice info" role="status"><span class="guide-dot" />{{ canvasNotice }}<button type="button" aria-label="关闭提示" @click="canvasNotice = ''"><X :size="13" /></button></div>
        <div v-if="!nodes.length" class="canvas-empty"><button type="button" @click="addNode(project?.kind === 'VIDEO' ? 'video_generate' : 'image_generate')"><Plus :size="17" />添加生成节点</button></div>
      </main>
    </div>
  </div>
</template>

<script setup>
import { computed, inject, markRaw, nextTick, onBeforeUnmount, onMounted, provide, reactive, ref, watch } from 'vue'
import { VueFlow, applyNodeChanges, applyEdgeChanges, addEdge, useVueFlow } from '@vue-flow/core'
import { Background } from '@vue-flow/background'
import '@vue-flow/core/dist/style.css'
import '@vue-flow/core/dist/theme-default.css'
import '@vue-flow/controls/dist/style.css'
import { connectionKind, connectionError, effectivePrompt, normalizeConnection, isGenerationNode, ensureStart, dependencyIssue, siblingConnection } from './canvasGraph'
import { Controls } from '@vue-flow/controls'
import { AlertCircle, Check, Clapperboard, FileText, Film, Image, ImagePlus, Play, Plus, PanelLeft, Scan, Sparkles, Trash2, Undo2, Upload, WandSparkles, X } from 'lucide-vue-next'
import DropdownSelect from '../../../components/ui/DropdownSelect.vue'
import Spinner from '../../../components/ui/Spinner.vue'
import CanvasGraphNode from './CanvasGraphNode.vue'
import { PROJECT_CTX } from './composables'
import {
  cancelCreativeCanvasRun, getCreativeCanvas, getCreativeJob, getCreativeAssetBlob, getCreativeAssetPlaybackUrl, getCreativeCanvasRun,
  listCreativeAssets, listCreativeCanvasRuns, listModelCatalog, optimizeCreativePrompt, saveCreativeCanvas,
  selectCreativeCanvasCandidate, startCreativeCanvasRun, uploadCreativeAsset, validateCreativeCanvasRun
} from '../../../api'

const ctx = inject(PROJECT_CTX)
const projectId = ctx.projectId
const project = ctx.project
const nodes = ref([])
const edges = ref([])
const selectedId = ref('')
const revision = ref(0)
const viewport = ref({ x: 0, y: 0, zoom: 1 })
const saveState = ref('saved')
const saveLabel = computed(() => ({ saved: '已保存', saving: '保存中', error: '保存失败', conflict: '版本冲突' }[saveState.value]))
const pageError = ref('')
const actionError = ref('')
const canvasNotice = ref('')
const assets = ref([])
const assetLoading = ref(false)
const models = ref([])
const bindings = ref({})
const optimizing = ref(false)
const optimizedPrompt = ref('')
const busyNode = ref(false)
const showAssetPicker = ref(false)
const undoStack = ref([])
const activeCanvasRun = ref(null)
const objectUrls = reactive(new Map())
const stageRef = ref(null)
const inspectorOpen = ref(true)
const runScope = ref('node')
const runScopeLabel = computed(() => ({ node: '生成当前节点', up_to: '执行到此节点', from_here: '从此继续', all: '运行整图' })[runScope.value])
provide('canvasPresentation', { assetUrl, assetName, assetType: id => assets.value.find(item => item.id === id)?.mediaType,
  prompt: id => effectivePrompt(id, nodes.value, edges.value),
  modelName: id => models.value.find(model => Number(model.id) === Number(id))?.label })
let saveTimer = null
let pollTimer = null
let saving = false
let dirtyAgain = false
let savePromise = null
let hydrating = false
const { setViewport } = useVueFlow()
const nodeTypes = Object.fromEntries(['start', 'asset_input', 'text_input', 'image_generate', 'image_edit', 'video_generate'].map(type => [type, markRaw(CanvasGraphNode)]))

const selectedNode = computed(() => nodes.value.find(node => node.id === selectedId.value) || null)
const modelOptionsFor = type => {
  const capability = type === 'video_generate' ? 'video_generation' : 'image_generation'
  return models.value.filter(model => model.capabilities?.includes(capability))
    .map(model => ({ value: Number(model.id), label: `${model.provider} / ${model.label}` }))
}
const incomingBindings = computed(() => edges.value.filter(edge => edge.target === selectedId.value).map(edge => {
  const source = nodes.value.find(node => node.id === edge.source)
  const kind = connectionKind(edge, nodes.value)
  return { edge, source, kind, text: kind === 'text' ? effectivePrompt(source?.id, nodes.value, edges.value) : '',
    assets: kind === 'image' ? source?.type === 'asset_input' ? source.data.assetIds || [] : source?.data.selectedAssetId ? [source.data.selectedAssetId] : [] : [] }
}).filter(binding => binding.source))
const effectiveNodePrompt = computed(() => effectivePrompt(selectedId.value, nodes.value, edges.value))
const connectedAssetIds = computed(() => [...new Set(incomingBindings.value.filter(binding => binding.kind === 'image').flatMap(({ source: node }) => {
  if (node.type === 'asset_input') return node.data.assetIds || []
  if (node.data.selectedAssetId) return [node.data.selectedAssetId]
  return []
}))])
const allReferenceIds = computed(() => [...new Set([...(selectedNode.value?.data.referenceAssetIds || []), ...connectedAssetIds.value])])
const compatibleAssets = computed(() => assets.value.filter(asset => asset.mediaType === 'IMAGE'
  && !allReferenceIds.value.includes(asset.id)))
const nodeOutputs = computed(() => selectedNode.value?.data.candidates?.length
  ? selectedNode.value.data.candidates
  : (selectedNode.value?.data.outputAssetIds || []).map(assetId => ({ assetId, mediaType: 'IMAGE' })))
const canRun = computed(() => selectedNode.value && isGenerationNode(selectedNode.value)
  && !!effectiveNodePrompt.value.trim() && !!selectedNode.value.data.modelRowId)
const runBlockedReason = computed(() => dependencyIssue(selectedId.value, nodes.value, edges.value, runScope.value))
const upToBlocked = computed(() => selectedNode.value && isGenerationNode(selectedNode.value)
  ? dependencyIssue(selectedId.value, nodes.value, edges.value, 'up_to') : '')
const upToLabel = computed(() => activeCanvasRun.value && ['SUCCEEDED', 'FAILED', 'CANCELLED'].includes(activeCanvasRun.value.run?.status)
  && selectedNode.value?.data.status !== 'SUCCEEDED' ? '继续执行到此节点' : '执行到此节点')



function typeLabel(type) { return ({ start: '开始', asset_input: '素材输入', text_input: '文本输入', image_generate: '图片生成', image_edit: '图片编辑', video_generate: '视频生成' })[type] || '节点' }
function statusLabel(status) { return ({ PENDING: '等待运行', QUEUED: '排队中', SUBMITTING: '提交中', RUNNING: '生成中', WAITING_EXTERNAL: '等待模型结果', WAITING_SELECTION: '请选择一个候选结果', RECONCILING: '确认提交结果', FETCHING: '保存结果', INGEST_FAILED: '保存失败', SUCCEEDED: '已完成', SKIPPED: '已跳过', FAILED: '失败', CANCELLED: '已取消' })[status] || '待生成' }
function statusTone(status) { return status === 'SUCCEEDED' ? 'success' : ['FAILED', 'INGEST_FAILED'].includes(status) ? 'error' : ['RUNNING', 'SUBMITTING', 'FETCHING', 'WAITING_EXTERNAL', 'WAITING_SELECTION'].includes(status) ? 'working' : '' }
function assetName(id) { return assets.value.find(asset => asset.id === id)?.displayName || id }
function assetUrl(id) { return objectUrls.get(id) || '' }
async function loadAssetPreview(id) {
  if (!id || objectUrls.has(id)) return
  const asset = assets.value.find(item => item.id === id)
  try {
    if (asset?.mediaType === 'VIDEO') {
      const data = await getCreativeAssetPlaybackUrl(id)
      objectUrls.set(id, data.url)
    } else {
      const blob = await getCreativeAssetBlob(id)
      objectUrls.set(id, URL.createObjectURL(blob))
    }
  } catch { /* 预览失败不影响任务与素材引用 */ }
}
function graphSnapshot() {
  return {
    nodes: nodes.value.map(node => ({ id: node.id, type: node.type, position: node.position,
      data: { ...node.data, job: undefined, selected: undefined, dragging: undefined } })),
    edges: edges.value.map(edge => ({ id: edge.id, source: edge.source, target: edge.target,
      sourceHandle: edge.sourceHandle, targetHandle: edge.targetHandle, label: edge.label || '' }))
  }
}
function markChanged() {
  if (hydrating) return
  saveState.value = 'saving'
  window.clearTimeout(saveTimer)
  saveTimer = window.setTimeout(() => save().catch(() => {}), 450)
}
async function save() {
  window.clearTimeout(saveTimer)
  if (saving) {
    dirtyAgain = true
    return savePromise
  }
  saving = true
  savePromise = (async () => {
    do {
      dirtyAgain = false
      try {
        const result = await saveCreativeCanvas(projectId.value, { graph: graphSnapshot(), viewport: viewport.value, expectedRevision: revision.value })
        revision.value = result.revision
        saveState.value = 'saved'
      } catch (error) {
        saveState.value = error.response?.status === 409 ? 'conflict' : 'error'
        if (error.response?.status === 409) actionError.value = '画布在其他窗口更新了，请刷新页面再继续编辑。'
        throw error
      }
    } while (dirtyAgain)
  })()
  try { await savePromise }
  finally {
    saving = false
    savePromise = null
    if (dirtyAgain) { dirtyAgain = false; markChanged() }
  }
}
function nodesChanged(changes) {
  const structural = changes.some(change => ['add', 'remove', 'position', 'dimensions'].includes(change.type))
  nodes.value = applyNodeChanges(changes, nodes.value)
  if (structural) markChanged()
}
function edgesChanged(changes) { edges.value = applyEdgeChanges(changes, edges.value); markChanged() }
function viewportChanged(event) {
  const transform = event?.flowTransform
  if (transform && Number.isFinite(transform.zoom)) viewport.value = { x: transform.x, y: transform.y, zoom: transform.zoom }
  markChanged()
}
function selectNode(event) {
  selectedId.value = event.node.id
  inspectorOpen.value = true
  nodes.value.forEach(node => { node.selected = node.id === selectedId.value })
  optimizedPrompt.value = ''; actionError.value = ''
}
function newNode(type, position = null, data = {}) {
  const id = crypto.randomUUID()
  const defaults = {
    asset_input: { label: '参考素材', assetIds: [] },
    text_input: { label: '文本', text: '' },
    image_generate: { label: '生成图片', prompt: '', size: '1024*1024', referenceAssetIds: [], outputAssetIds: [] },
    image_edit: { label: '图片编辑', prompt: '', size: '1024*1024', referenceAssetIds: [], outputAssetIds: [] },
    video_generate: { label: '生成视频', prompt: '', seconds: 5, referenceAssetIds: [], outputAssetIds: [] }
  }[type]
  return { id, type, position: position || { x: 90 + (nodes.value.length % 3) * 330, y: 90 + Math.floor(nodes.value.length / 3) * 230 }, data: { ...defaults, modelRowId: modelOptionsFor(type)[0]?.value || null, ...data, status: 'IDLE' } }
}
async function addNode(type) {
  undoStack.value.push(JSON.stringify(graphSnapshot()))
  const anchor = selectedNode.value || nodes.value[nodes.value.length - 1]
  const node = newNode(type, anchor ? { x: anchor.position.x + 360, y: anchor.position.y } : { x: 0, y: 0 })
  nodes.value = [...nodes.value, node]
  selectNode({ node })
  markChanged()
  await nextTick()
  fitView()
}
function connectNodes(connection) {
  const normalized = normalizeConnection(connection, nodes.value)
  const error = connectionError(normalized, nodes.value, edges.value)
  if (error) { actionError.value = error; return }
  const source = nodes.value.find(node => node.id === normalized.source)
  if (connectionKind(normalized, nodes.value) === 'image' && source?.type === 'asset_input'
      && (source.data.assetIds || []).some(id => assets.value.find(asset => asset.id === id)?.mediaType === 'VIDEO')) {
    actionError.value = '当前生成接口只支持图片参考，请移除素材节点中的视频后再连接'; return
  }
  undoStack.value.push(JSON.stringify(graphSnapshot()))
  edges.value = addEdge({ ...normalized, type: 'default' }, edges.value)
  const target = nodes.value.find(node => node.id === normalized.target)
  const kind = connectionKind(normalized, nodes.value)
  const sibling = siblingConnection(normalized, nodes.value, edges.value)
  if (sibling) {
    const toVideo = kind === 'text' && target?.type === 'video_generate'
    if (!toVideo || window.confirm(`是否同时将「${source?.data.label || '上游节点'}」的结果图片作为首帧？`)) {
      edges.value = addEdge({ ...sibling, id: crypto.randomUUID(), type: 'default' }, edges.value)
      canvasNotice.value = toVideo ? '已同时将上游结果图片设为视频首帧，双击连线可断开'
        : '已同时连接提示词与图片素材，双击连线可单独断开'
    }
  }
  selectNode({ node: target })
  markChanged()
}
function disconnect(edgeId) {
  undoStack.value.push(JSON.stringify(graphSnapshot()))
  edges.value = edges.value.filter(edge => edge.id !== edgeId)
  markChanged()
}
function onCanvasKeydown(event) {
  if (!['Delete', 'Backspace'].includes(event.key)) return
  const active = document.activeElement
  if (active && (active.tagName === 'INPUT' || active.tagName === 'TEXTAREA' || active.isContentEditable)) return
  const selectedEdge = edges.value.find(edge => edge.selected)
  if (selectedEdge) { disconnect(selectedEdge.id); event.preventDefault(); return }
  if (selectedNode.value && selectedNode.value.type !== 'start') { removeSelected(); event.preventDefault() }
}
function removeSelected() {
  if (selectedNode.value?.type === 'start') return
  undoStack.value.push(JSON.stringify(graphSnapshot()))
  const id = selectedId.value
  nodes.value = nodes.value.filter(node => node.id !== id)
  edges.value = edges.value.filter(edge => edge.source !== id && edge.target !== id)
  selectedId.value = ''
  markChanged()
}
function undo() {
  const previous = undoStack.value.pop()
  if (!previous) return
  const graph = JSON.parse(previous)
  nodes.value = graph.nodes
  edges.value = graph.edges
  markChanged()
}
function fitView() {
  const stage = stageRef.value
  if (!stage || !nodes.value.length) return
  if (stage.clientWidth < 1000) inspectorOpen.value = false
  const inset = inspectorOpen.value && stage.clientWidth > 720 ? 310 : 36
  const minX = Math.min(...nodes.value.map(node => node.position.x))
  const minY = Math.min(...nodes.value.map(node => node.position.y))
  const maxX = Math.max(...nodes.value.map(node => node.position.x + 268))
  const maxY = Math.max(...nodes.value.map(node => node.position.y + 340))
  const width = Math.max(200, stage.clientWidth - inset - 60)
  const height = Math.max(180, stage.clientHeight - 140)
  const zoom = Math.min(1, width / (maxX - minX), height / (maxY - minY))
  const next = { x: inset + (width - (maxX - minX) * zoom) / 2 - minX * zoom + 20,
    y: 40 + (height - (maxY - minY) * zoom) / 2 - minY * zoom, zoom }
  setViewport(next)
  viewport.value = next
}
function toggleAsset(id) {
  const values = selectedNode.value.data.assetIds || []
  selectedNode.value.data.assetIds = values.includes(id) ? values.filter(value => value !== id) : [...values, id]
  loadAssetPreview(id)
  markChanged()
}
function addReference(id) {
  selectedNode.value.data.referenceAssetIds = [...new Set([...(selectedNode.value.data.referenceAssetIds || []), id])]
  loadAssetPreview(id)
  showAssetPicker.value = false
  markChanged()
}
function removeReference(id) {
  selectedNode.value.data.referenceAssetIds = (selectedNode.value.data.referenceAssetIds || []).filter(value => value !== id)
  markChanged()
}
async function selectOutput(id) {
  if (selectedNode.value.data.status === 'WAITING_SELECTION' && activeCanvasRun.value) {
    try {
      const result = await selectCreativeCanvasCandidate(projectId.value, activeCanvasRun.value.id, {
        nodeId: selectedNode.value.id, assetId: id
      })
      await applyCanvasRun(result)
      schedulePoll()
      return
    } catch (error) {
      actionError.value = error.response?.data?.error || '候选结果选择失败'
      return
    }
  }
  selectedNode.value.data.selectedAssetId = id
  selectedNode.value.data.selectedAssetName = assetName(id)
  markChanged()
}
async function optimizePrompt() {
  optimizing.value = true
  actionError.value = ''
  try {
    const type = selectedNode.value.type
    const result = await optimizeCreativePrompt({
      prompt: selectedNode.value.data.prompt,
      mode: type === 'video_generate' ? 'VIDEO_PROMPT' : 'IMAGE',
      projectId: projectId.value
    })
    optimizedPrompt.value = result.optimizedPrompt || ''
  } catch (error) { actionError.value = error.response?.data?.error || '提示词优化失败' }
  finally { optimizing.value = false }
}
function adoptPrompt() {
  selectedNode.value.data.prompt = optimizedPrompt.value
  optimizedPrompt.value = ''
  markChanged()
}

async function cancelRun() {
  if (!activeCanvasRun.value || busyNode.value) return
  busyNode.value = true
  try { await applyCanvasRun(await cancelCreativeCanvasRun(projectId.value, activeCanvasRun.value.id)) }
  catch (error) { actionError.value = error.response?.data?.error || '取消运行失败' }
  finally { busyNode.value = false }
}

async function runCanvas(scope) {
  if (busyNode.value || (scope !== 'all' && !canRun.value)) return
  const issue = dependencyIssue(selectedId.value, nodes.value, edges.value, scope)
  if (issue) { actionError.value = issue; return }
  busyNode.value = true
  actionError.value = ''
  try {
    await save()
    const payload = {
      scope,
      nodeId: scope === 'all' ? null : selectedNode.value.id,
      expectedRevision: revision.value,
      idempotencyKey: crypto.randomUUID()
    }
    const validation = await validateCreativeCanvasRun(projectId.value, payload)
    if (validation.warnings?.length && !window.confirm(validation.warnings.join('\n') + '\n\n确认继续运行？')) return
    const result = await startCreativeCanvasRun(projectId.value, payload)
    await applyCanvasRun(result)
    schedulePoll()
  } catch (error) {
    actionError.value = error.response?.data?.error || '画布运行提交失败'
  } finally { busyNode.value = false }
}

async function applyCanvasRun(result) {
  if (!result?.run) return
  activeCanvasRun.value = result
  const candidates = new Map((result.candidates || []).map(item => [item.nodeId, item.assets || []]))
  for (const step of result.run.steps || []) {
    const node = nodes.value.find(item => item.id === step.key)
    if (!node) continue
    node.data.status = candidates.has(step.key) ? 'WAITING_SELECTION' : step.status
    node.data.error = step.status === 'FAILED' ? step.message : ''
    node.data.canvasRunId = result.id
    node.data.candidates = candidates.get(step.key) || []
    if (step.output) {
      try {
        const output = JSON.parse(step.output)
        if (output.assetId) {
          node.data.outputAssetIds = [output.assetId]
          node.data.selectedAssetId = output.assetId
          await loadAssetPreview(output.assetId)
        }
      } catch { /* 非媒体输出不参与画布结果选择 */ }
    }
    for (const candidate of node.data.candidates) await loadAssetPreview(candidate.assetId)
  }
  markChanged()
}

async function refreshJob(node) {
  if (!node?.data.jobId) return
  try {
    const job = await getCreativeJob(node.data.jobId)
    node.data.job = job
    node.data.status = job.status
    node.data.outputAssetIds = (job.outputs || []).filter(output => output.state === 'DONE').map(output => output.assetId)
    const selected = node.data.selectedAssetId
    if (selected && !node.data.outputAssetIds.includes(selected)) node.data.selectedAssetId = ''
    if (!selected && node.data.outputAssetIds.length === 1) node.data.selectedAssetId = node.data.outputAssetIds[0]
    for (const id of node.data.outputAssetIds) await loadAssetPreview(id)
    markChanged()
  } catch { /* 运行记录仍可通过任务页查看 */ }
}
function schedulePoll() {
  window.clearTimeout(pollTimer)
  const active = nodes.value.filter(node => node.data.jobId && ['QUEUED', 'SUBMITTING', 'RUNNING', 'RECONCILING', 'FETCHING'].includes(node.data.status))
  const canvasActive = activeCanvasRun.value && ['RUNNING', 'WAITING_EXTERNAL'].includes(activeCanvasRun.value.run?.status)
  if (!active.length && !canvasActive) return
  pollTimer = window.setTimeout(async () => {
    if (document.visibilityState === 'visible') {
      await Promise.all(active.map(refreshJob))
      if (canvasActive) { try { await applyCanvasRun(await getCreativeCanvasRun(projectId.value, activeCanvasRun.value.id)) } catch { actionError.value = '运行状态暂时无法刷新，正在重试' } }
    }
    schedulePoll()
  }, 3000)
}
async function uploadAsset(event) {
  const target = selectedNode.value
  const file = event.target.files?.[0]
  if (!file) return
  if (target && isGenerationNode(target) && !file.type.startsWith('image/')) {
    actionError.value = '当前生成接口只支持图片参考；视频可保存到素材节点'
    event.target.value = ''
    return
  }
  try {
    const asset = await uploadCreativeAsset(projectId.value, file)
    assets.value.unshift(asset)
    if (target) {
      const key = target.type === 'asset_input' ? 'assetIds' : 'referenceAssetIds'
      target.data[key] = [...new Set([...(target.data[key] || []), asset.id])]
      await loadAssetPreview(asset.id)
      markChanged()
    }
    showAssetPicker.value = false
  } catch (error) { actionError.value = error.response?.data?.error || '素材上传失败' }
  finally { event.target.value = '' }
}
async function loadAssets() {
  assetLoading.value = true
  try { assets.value = (await listCreativeAssets({ projectId: projectId.value, limit: 100 })).items || [] }
  catch { actionError.value = '项目素材加载失败' }
  finally { assetLoading.value = false }
}
async function loadModels() {
  try {
    const catalog = await listModelCatalog()
    bindings.value = { ...catalog.systemBindings, ...catalog.userBindings }
    models.value = (catalog.providers || []).filter(provider => provider.enabled !== false).flatMap(provider =>
      (provider.models || []).filter(model => model.enabled !== false).map(model => ({
        id: model.id, label: model.displayName || model.modelId, provider: provider.name, capabilities: model.capabilities || []
      })))
    for (const node of nodes.value.filter(item => isGenerationNode(item) && !item.data.modelRowId)) {
      const cap = node.type === 'video_generate' ? 'video_generation' : 'image_generation'
      const binding = bindings.value[cap]
      node.data.modelRowId = binding?.modelRowId || modelOptionsFor(node.type)[0]?.value || null
    }
  } catch { /* 模型设置页面保留未配置的引导 */ }
}
function createInitialGraph(kind, handoff) {
  const type = kind === 'VIDEO' ? 'video_generate' : 'image_generate'
  const prompt = handoff?.prompt || project.value?.description || ''
  const modelRowId = handoff?.modelRowId ? Number(handoff.modelRowId) : undefined
  const data = { prompt, modelRowId, skillSlug: handoff?.skill || '' }
  if (kind === 'VIDEO' && handoff?.seconds) data.seconds = Number(handoff.seconds)
  if (kind === 'IMAGE' && handoff?.size) data.size = handoff.size
  const node = newNode(type, { x: 200, y: 140 }, data)
  const extras = []
  const ids = [...String(handoff?.firstFrame || '').split(',').filter(Boolean), ...String(handoff?.refImage || '').split(',').filter(Boolean)]
  if (ids.length) {
    const asset = newNode('asset_input', { x: -180, y: 160 }, { label: '参考素材', assetIds: ids })
    extras.push(asset)
    edges.value = [{ id: crypto.randomUUID(), source: asset.id, target: node.id, sourceHandle: 'media-out', targetHandle: 'image-in', label: kind === 'VIDEO' ? '首帧' : '参考图' }]
  }
  const graph = ensureStart({ nodes: [...extras, node], edges: edges.value })
  nodes.value = graph.nodes
  edges.value = graph.edges
  selectedId.value = node.id
}
async function loadCanvas() {
  hydrating = true
  let upgraded = false
  try {
    const data = await getCreativeCanvas(projectId.value)
    revision.value = data.revision
    viewport.value = data.viewport || { x: 0, y: 0, zoom: 1 }
    const savedNodes = data.graph?.nodes || []
    edges.value = (data.graph?.edges || []).map(edge => normalizeConnection(edge, savedNodes))
    if (savedNodes.length) {
      upgraded = !savedNodes.some(node => node.type === 'start')
      const graph = ensureStart({ nodes: savedNodes, edges: edges.value })
      nodes.value = graph.nodes
      edges.value = graph.edges
    }
    else {
      let handoff = null
      try { handoff = JSON.parse(sessionStorage.getItem('aster.creative.handoff') || 'null') } catch { handoff = null }
      if (handoff) sessionStorage.removeItem('aster.creative.handoff')
      createInitialGraph(project.value?.kind, handoff)
    }
    selectedId.value = nodes.value.find(node => ['image_generate', 'video_generate'].includes(node.type))?.id || nodes.value[0]?.id || ''
    if (revision.value) { await nextTick(); await setViewport(viewport.value) }
  } catch (error) { pageError.value = error.response?.data?.error || '画布加载失败' }
  finally { hydrating = false }
  await Promise.all([loadAssets(), loadModels()])
  for (const node of nodes.value) {
    for (const id of [...(node.data.assetIds || []), ...(node.data.referenceAssetIds || []), ...(node.data.outputAssetIds || []), node.data.selectedAssetId].filter(Boolean)) await loadAssetPreview(id)
    if (node.data.jobId) await refreshJob(node)
  }
  try {
    const runs = await listCreativeCanvasRuns(projectId.value, 10)
    const latest = runs.find(item => ['RUNNING', 'WAITING_EXTERNAL', 'WAITING_SELECTION'].includes(item.run?.status)) || runs[0]
    if (latest) await applyCanvasRun(latest)
  } catch { /* 旧数据库尚未迁移时仍允许编辑草稿 */ }
  if (!revision.value || upgraded) { await nextTick(); fitView(); markChanged() }
  nodes.value.forEach(node => { node.selected = node.id === selectedId.value })
  schedulePoll()
}
onMounted(() => { window.addEventListener('keydown', onCanvasKeydown); loadCanvas() })
onBeforeUnmount(() => {
  window.removeEventListener('keydown', onCanvasKeydown)
  window.clearTimeout(saveTimer)
  window.clearTimeout(pollTimer)
  objectUrls.forEach(url => { if (url.startsWith('blob:')) URL.revokeObjectURL(url) })
})
watch(() => nodes.value.filter(node => node.data.jobId).map(node => `${node.data.jobId}:${node.data.status}`).join('|'), schedulePoll)
watch(() => JSON.stringify(graphSnapshot()), markChanged)
watch(() => activeCanvasRun.value?.run?.status, schedulePoll)
</script>

<style scoped>
.canvas-page { flex: 1; min-height: 0; display: flex; flex-direction: column; background: var(--wb-bg); color: var(--wb-text); }
.canvas-toolbar { height: 52px; min-height: 52px; display: flex; align-items: center; justify-content: space-between; gap: 12px; padding: 0 14px; border-bottom: 1px solid var(--wb-border); background: var(--wb-surface); }
.canvas-title,.canvas-actions { display: flex; align-items: center; gap: 8px; min-width: 0; }
.canvas-kicker { font-size: 13px; font-weight: 650; white-space: nowrap; }
.canvas-save { color: var(--wb-text-muted); font-size: 11px; white-space: nowrap; }
.canvas-save.error,.canvas-save.conflict { color: var(--wb-danger); }
.canvas-actions { overflow-x: auto; }
.canvas-tool,.canvas-icon,.canvas-run { height: 32px; border: 1px solid var(--wb-border); background: var(--wb-surface); color: var(--wb-text); display: inline-flex; align-items: center; justify-content: center; gap: 6px; padding: 0 9px; border-radius: 6px; font-size: 12px; white-space: nowrap; cursor: pointer; }
.canvas-icon { width: 32px; padding: 0; }
.canvas-tool:hover,.canvas-icon:hover { background: var(--wb-surface-hover); }
.canvas-icon:disabled { opacity: .4; cursor: default; }
.canvas-run { color: #fff; background: #2563eb; border-color: #2563eb; padding: 0 12px; font-weight: 600; }
.canvas-run:disabled { opacity: .55; cursor: not-allowed; }
.canvas-divider { height: 20px; border-left: 1px solid var(--wb-border); margin: 0 3px; }
.canvas-workspace { display: flex; flex: 1; min-height: 0; }
.canvas-inspector { width: 328px; min-width: 300px; max-width: 360px; overflow: auto; padding: 14px; border-right: 1px solid var(--wb-border); background: var(--wb-surface); }
.inspector-heading { display: flex; justify-content: space-between; align-items: center; margin-bottom: 12px; }
.inspector-type { display: flex; align-items: center; gap: 8px; font-size: 12px; font-weight: 650; }
.inspector-dot,.node-status-dot { width: 8px; height: 8px; border-radius: 50%; background: #89909a; }
.kind-image_generate { background: #2563eb; }.kind-video_generate { background: #be5d2a; }.kind-asset_input { background: #15947d; }.kind-text_input { background: #6a7482; }
.inspector-field { display: grid; gap: 6px; margin: 0 0 13px; color: var(--wb-text-muted); font-size: 11px; }
.inspector-field input,.inspector-field textarea,.inspector-field select { width: 100%; box-sizing: border-box; padding: 9px 10px; border: 1px solid var(--wb-border); border-radius: 6px; background: var(--wb-bg); color: var(--wb-text); font: inherit; font-size: 12px; resize: vertical; }
.inspector-field textarea { min-height: 76px; line-height: 1.55; }
.inspector-field small { justify-self: end; }
.inspector-actions,.inspector-section-head { display: flex; align-items: center; justify-content: space-between; gap: 8px; margin: 4px 0 13px; }
.optimize-button,.inline-action { display: inline-flex; align-items: center; gap: 5px; padding: 0; border: 0; background: transparent; color: #2563eb; font-size: 11px; cursor: pointer; }
.optimize-button:disabled { opacity: .5; cursor: default; }
.inspector-section-head { border-top: 1px solid var(--wb-border); padding-top: 11px; color: var(--wb-text); font-size: 11px; font-weight: 600; }
.input-count { color: var(--wb-text-muted); font-weight: 400; }
.inspector-empty,.inspector-note { color: var(--wb-text-muted); font-size: 11px; line-height: 1.5; }
.asset-option { width: 100%; min-height: 36px; padding: 6px 8px; display: flex; align-items: center; gap: 8px; border: 1px solid transparent; border-radius: 5px; background: transparent; color: var(--wb-text); text-align: left; cursor: pointer; }
.asset-option:hover,.asset-option.chosen { background: var(--wb-bg); border-color: var(--wb-border); }
.asset-option-icon { color: var(--wb-text-muted); }.asset-option-name { flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: 11px; }
.upload-control { position: relative; display: inline-flex; align-items: center; gap: 6px; margin-top: 10px; color: #2563eb; font-size: 11px; cursor: pointer; }.upload-control input { position: absolute; inset: 0; opacity: 0; width: 100%; cursor: pointer; }
.reference-list { display: grid; gap: 5px; }
.reference-row { min-width: 0; display: flex; justify-content: space-between; align-items: center; gap: 7px; padding: 5px 7px; background: var(--wb-bg); border-radius: 5px; font-size: 11px; }
.reference-row span { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }.remove-reference { border: 0; background: transparent; color: var(--wb-text-muted); cursor: pointer; }
.add-reference { margin-top: 9px; }.asset-picker { margin-top: 7px; max-height: 140px; overflow: auto; border: 1px solid var(--wb-border); border-radius: 6px; }.asset-picker button { width: 100%; display: flex; justify-content: space-between; padding: 8px; border: 0; border-bottom: 1px solid var(--wb-border); background: var(--wb-surface); color: var(--wb-text); text-align: left; font-size: 11px; cursor: pointer; }
.optimized-preview { max-height: 120px; overflow: auto; padding: 9px; background: var(--wb-bg); color: var(--wb-text); border-radius: 5px; font-size: 11px; line-height: 1.5; }
.inspector-error { color: var(--wb-danger); font-size: 11px; line-height: 1.5; }
.node-job-state { display: grid; gap: 6px; margin-top: 16px; padding: 10px; border-radius: 6px; background: var(--wb-bg); color: var(--wb-text-muted); font-size: 11px; }
.node-job-state.success { color: #18794e; }.node-job-state.error { color: var(--wb-danger); }.node-job-state.working { color: #a35914; }
.node-output-list { display: grid; grid-template-columns: repeat(2,minmax(0,1fr)); gap: 6px; margin-top: 5px; }.node-output-list button { min-width: 0; display: grid; gap: 4px; padding: 5px; border: 1px solid var(--wb-border); border-radius: 5px; background: var(--wb-surface); color: var(--wb-text-muted); font-size: 10px; cursor: pointer; }.node-output-list button.selected { border-color: #2563eb; color: #2563eb; }.node-output-list img { width: 100%; aspect-ratio: 1.4; object-fit: contain; background: #111; }
.canvas-stage { flex: 1; min-width: 0; min-height: 0; position: relative; }.canvas-stage :deep(.vue-flow) { background: var(--wb-bg); }.canvas-stage :deep(.vue-flow__edge-path) { stroke: #9098a5; stroke-width: 1.6; }.canvas-stage :deep(.vue-flow__edge.selected .vue-flow__edge-path) { stroke: #2563eb; stroke-width: 2.2; }.canvas-stage :deep(.vue-flow__controls) { border: 1px solid var(--wb-border); box-shadow: 0 2px 8px rgb(20 25 34 / 8%); }.canvas-stage :deep(.vue-flow__controls-button) { background: var(--wb-surface); color: var(--wb-text); border-color: var(--wb-border); }
.canvas-empty { position: absolute; inset: 0; display: grid; place-items: center; pointer-events: none; }.canvas-empty button { pointer-events: auto; display: inline-flex; align-items: center; gap: 7px; padding: 10px 13px; border: 1px solid var(--wb-border); border-radius: 6px; background: var(--wb-surface); color: #2563eb; cursor: pointer; }
.canvas-error { display: flex; gap: 7px; align-items: center; padding: 8px 14px; color: var(--wb-danger); font-size: 11px; border-bottom: 1px solid var(--wb-border); }

/* Center-first canvas. The inspector floats over the canvas instead of taking a second column. */
.canvas-page { width: 100%; height: 100%; min-width: 0; overflow: hidden; --wb-text-muted: var(--wb-muted, #81899a); }
.canvas-toolbar { height: 50px; min-height: 50px; padding: 0 18px; }
.canvas-save { display: inline-flex; align-items: center; gap: 5px; margin-left: 6px; font-size: 10px; }
.canvas-save i { width: 5px; height: 5px; border-radius: 50%; background: #71a68c; }
.canvas-save.saving i { background: #d6ad5c; }
.canvas-actions { overflow: visible; }
.canvas-tool,.canvas-icon { border-color: transparent; background: transparent; }
.canvas-run { background: #6466d5; border-color: #6466d5; border-radius: 7px; height: 31px; font-size: 11px; }
.canvas-kicker { font-size: 12px; }
.run-scope { background: var(--wb-surface); color: var(--wb-text-2); border: 1px solid var(--wb-border); border-radius: 7px; padding: 7px 8px; font-size: 11px; }
.canvas-workspace { position: relative; overflow: hidden; }
.canvas-stage { position: absolute; inset: 0; width: 100%; height: 100%; }
.canvas-stage :deep(.vue-flow) { width: 100%; height: 100%; background: var(--wb-bg, #f6f7fb); }
.canvas-stage :deep(.vue-flow__connection-path) { stroke: #7879cf; stroke-width: 2; }
.canvas-stage :deep(.vue-flow__edge-text) { font-size: 10px; fill: var(--wb-text-2); }
.canvas-stage :deep(.vue-flow__edge-textbg) { fill: var(--wb-surface); }
.canvas-stage :deep(.vue-flow__controls) { bottom: 24px; right: 20px; border-radius: 8px; overflow: hidden; }
.canvas-inspector { position: absolute; top: 16px; left: 16px; z-index: 8; width: 276px; min-width: 0; max-width: none; max-height: calc(100% - 100px); box-sizing: border-box; border: 1px solid var(--wb-border); border-radius: 12px; padding: 16px; box-shadow: 0 6px 22px rgb(27 37 64 / 5%); }
.inspector-heading { margin-bottom: 18px; }
.inspector-heading-actions { display: flex; }
.inspector-heading-actions button { width: 25px; height: 25px; }
.inspector-field { gap: 7px; margin-bottom: 15px; }
.inspector-field textarea { min-height: 112px; max-height: 210px; font-size: 12px; line-height: 1.65; border-radius: 7px; }
.inspector-field input { padding: 8px 9px; }
.inspector-field small { font-size: 9px; }
.inspector-actions { margin: -4px 0 16px; }
.upstream-section { margin-bottom: 16px; }
.upstream-section .inspector-section-head { border: 0; padding: 0; margin: 0 0 8px; font-size: 10px; }
.upstream-item { background: var(--wb-bg); border: 1px solid var(--wb-border); border-radius: 7px; padding: 9px; margin-top: 6px; }
.upstream-item header { display: flex; align-items: center; gap: 6px; font-size: 10px; }
.upstream-item strong { flex: 1; }
.upstream-item small { color: var(--wb-muted); font-size: 9px; }
.upstream-item button,.canvas-notice button { padding: 0; border: 0; color: var(--wb-muted); background: transparent; cursor: pointer; }
.upstream-item p { margin: 7px 0 0; line-height: 1.55; font-size: 10px; color: var(--wb-text-2); max-height: 90px; overflow: auto; white-space: pre-wrap; }
.input-dot { width: 6px; height: 6px; border-radius: 50%; background: #56a793; }
.input-dot.text { background: #9a80cc; }
.input-dot.flow { background: #6877bd; }
.effective-prompt { font-size: 10px; color: var(--wb-muted); margin: 10px 0 16px; }
.effective-prompt summary { cursor: pointer; }
.effective-prompt p { white-space: pre-wrap; max-height: 140px; overflow: auto; line-height: 1.65; }
.canvas-guide { position: absolute; top: 18px; left: 50%; transform: translateX(-25%); display: flex; align-items: center; gap: 7px; font-size: 10px; color: var(--wb-muted); pointer-events: none; white-space: nowrap; }
.guide-dot { width: 5px; height: 5px; border-radius: 50%; background: #a7aec1; }
.node-palette { position: absolute; bottom: 24px; left: 50%; transform: translateX(-50%); display: flex; align-items: center; gap: 4px; padding: 7px 10px; border: 1px solid var(--wb-border); border-radius: 12px; box-shadow: 0 4px 16px rgb(24 32 60 / 7%); background: var(--wb-surface); z-index: 6; white-space: nowrap; }
.node-palette > span { font-size: 10px; color: var(--wb-muted); margin: 0 8px 0 2px; }
.node-palette button { display: inline-flex; align-items: center; gap: 6px; padding: 9px 10px; border: 0; border-radius: 7px; background: transparent; color: var(--wb-text-2); font-size: 11px; cursor: pointer; }
.node-palette button:hover { background: var(--wb-fill, #f0f1fa); color: #6466d5; }
.node-palette i { height: 20px; width: 1px; background: var(--wb-border); margin: 0 5px; }
.canvas-notice { position: absolute; left: 50%; bottom: 94px; transform: translateX(-35%); z-index: 10; display: flex; align-items: center; gap: 8px; padding: 10px 14px; max-width: 440px; border: 1px solid var(--wb-border); border-radius: 8px; background: var(--wb-surface); font-size: 11px; color: var(--wb-danger, #c94c62); box-shadow: 0 4px 14px rgb(24 32 60 / 8%); }
.canvas-notice.info { color: var(--wb-text-2); }
.inspector-run { margin: 2px 0 6px; display: grid; gap: 7px; }
.inspector-run .run-to-here { width: 100%; color: #6466d5; border-color: var(--wb-border); background: var(--wb-surface); font-weight: 600; }
.inspector-run .run-to-here:disabled { opacity: .45; cursor: not-allowed; }
@media (max-width: 900px) {
  .canvas-inspector { width: 260px; max-height: calc(100% - 110px); }
  .canvas-guide { display: none; }
  .node-palette { left: 50%; bottom: 16px; padding: 5px; gap: 0; }
  .node-palette > span { display: none; }
  .node-palette button { padding: 8px; }
  .canvas-kicker,.canvas-divider { display: none; }
  .canvas-toolbar { padding: 0 8px; }
}
@media (prefers-reduced-motion: reduce) { * { transition: none !important; } }
</style>
