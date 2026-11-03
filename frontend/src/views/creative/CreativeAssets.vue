<template>
  <div class="cr-page">
    <div class="cr-scroll">
      <div class="assets-page">
        <header class="assets-head">
          <div>
            <p class="cr-kicker">AI 创作</p>
            <h1 class="cr-title">素材库</h1>
            <p class="cr-sub">生成结果与上传文件，仅本人可见。单击看大图，勾选后批量处理。</p>
          </div>
          <button type="button" class="cr-primary" @click="openUpload">
            <Upload :size="15" /> 上传素材
          </button>
        </header>

        <div class="assets-toolbar">
          <div class="assets-tabs" role="tablist" aria-label="素材视图">
            <button type="button" class="assets-tab" :class="{ active: !trashed }"
                    role="tab" :aria-selected="!trashed" @click="switchTrashed(false)">全部素材</button>
            <button type="button" class="assets-tab" :class="{ active: trashed }"
                    role="tab" :aria-selected="trashed" @click="switchTrashed(true)">回收站</button>
          </div>
          <input
            v-model.trim="keyword"
            class="cr-input assets-search"
            type="search"
            maxlength="60"
            placeholder="搜索素材名称"
            aria-label="搜索素材"
          />
          <div class="assets-kind" role="radiogroup" aria-label="类型">
            <button v-for="option in typeChips" :key="option.value" type="button" class="assets-chip"
                    :class="{ active: mediaType === option.value }" role="radio"
                    :aria-checked="mediaType === option.value"
                    @click="setMediaType(option.value)">{{ option.label }}</button>
          </div>
          <div class="assets-view" role="radiogroup" aria-label="布局">
            <button type="button" class="assets-view-btn" :class="{ active: viewMode === 'original' }"
                    role="radio" :aria-checked="viewMode === 'original'" title="原比例" @click="setView('original')">
              <Proportions :size="15" />
            </button>
            <button type="button" class="assets-view-btn" :class="{ active: viewMode === 'grid' }"
                    role="radio" :aria-checked="viewMode === 'grid'" title="整齐网格" @click="setView('grid')">
              <LayoutGrid :size="15" />
            </button>
            <button type="button" class="assets-view-btn" :class="{ active: density === 'compact' }"
                    :title="density === 'compact' ? '宽松密度' : '紧凑密度'" @click="toggleDensity">
              <Minimize2 v-if="density === 'compact'" :size="15" />
              <Maximize2 v-else :size="15" />
            </button>
          </div>
          <button type="button" class="cr-ghost assets-filter-toggle" :aria-expanded="filtersOpen"
                  @click="filtersOpen = !filtersOpen">
            <SlidersHorizontal :size="14" /> 筛选
          </button>
        </div>

        <div class="assets-layout" :class="{ 'filters-open': filtersOpen }">
          <!-- 按需筛选抽屉（redesign §5.3）：项目/来源/业务分类/收藏放可收起面板 -->
          <aside v-if="filtersOpen" class="assets-filters" aria-label="素材筛选">
            <section class="filter-group">
              <h2>来源</h2>
              <button v-for="option in originOptions" :key="option.value" type="button"
                      class="filter-item" :class="{ active: origin === option.value }"
                      @click="origin = option.value; requestSeq++; load()">
                {{ option.label }}
              </button>
            </section>
            <section class="filter-group">
              <h2>业务分类</h2>
              <button v-for="option in businessOptions" :key="option.value" type="button"
                      class="filter-item" :class="{ active: businessType === option.value }"
                      @click="businessType = option.value; requestSeq++; load()">
                {{ option.label }}
              </button>
            </section>
            <section class="filter-group">
              <h2>项目</h2>
              <DropdownSelect v-model="projectId" block :options="projectOptions"
                              aria-label="素材项目" @change="requestSeq++; load()" />
            </section>
            <label v-if="!trashed" class="filter-check">
              <input v-model="starred" type="checkbox" @change="requestSeq++; load()" />
              <span>只看收藏</span>
            </label>
            <button v-if="hasFilters" type="button" class="cr-ghost" @click="clearFilters">清除筛选</button>
          </aside>

          <section class="assets-main" ref="containerEl">
            <div class="assets-status">
              <span class="assets-count">
                {{ loading && !assets.length ? '加载中…' : `${assets.length} 个素材` }}
                <template v-if="hasFilters"> · 已筛选</template>
              </span>
            </div>

            <p v-if="error" class="cr-error">{{ error }}</p>
            <div v-if="!loading && !assets.length" class="cr-empty">
              {{ trashed ? '回收站是空的。删除的素材会先到这里，可恢复。'
                : (hasFilters || keyword ? '当前筛选下没有素材。' : '还没有素材。生成结果会自动保存到这里。') }}
              <button v-if="hasFilters || keyword" type="button" class="cr-ghost" @click="clearFilters">清除筛选</button>
            </div>

            <!-- 原比例：grid 行 span 按 aspect-ratio 计算，DOM 顺序=键盘顺序（不用 CSS columns） -->
            <div v-else class="asset-grid" :class="viewMode === 'original' ? 'is-original' : 'is-grid'"
                 :style="gridStyle">
              <MediaTile
                v-for="asset in assets"
                :key="asset.id"
                :asset="asset"
                :preview-url="previewUrls[asset.id] || ''"
                :placeholder="trashed ? '已删除' : '加载中…'"
                :selectable="!trashed"
                :selected="selectedIds.has(asset.id)"
                :row-span="viewMode === 'original' ? rowSpanFor(asset) : 0"
                @open="trashed ? null : openPreview(asset)"
                @toggle-select="toggleSelect(asset)"
                @star="toggleStar(asset, $event)"
              />
            </div>

            <div v-if="nextCursor" class="load-more">
              <button type="button" class="cr-secondary" :disabled="loading" @click="loadMore">
                {{ loading ? '加载中…' : '加载更多' }}
              </button>
              <span v-if="selectedAcrossPages" class="cr-hint">已选内容包含未在当前视图中的素材，操作前请确认范围。</span>
            </div>
          </section>
        </div>
      </div>
    </div>

    <!-- 多选工具条（redesign §5.3）：底部上移出现，不遮最后一排 -->
    <transition name="bar">
      <div v-if="selectionCount" class="selection-bar" role="toolbar" aria-label="批量操作">
        <strong>已选 {{ selectionCount }} 项</strong>
        <button type="button" class="cr-secondary" :disabled="selectionCount !== 1 || trashed"
                :title="selectionCount !== 1 ? '引用目前支持单张素材' : ''" @click="referenceSelected">
          引用到创作台
        </button>
        <button type="button" class="cr-secondary" :disabled="busyBatch" @click="downloadSelected">下载</button>
        <HoldToConfirm v-if="!trashed" label="移至回收站" :disabled="busyBatch" @confirm="trashSelected" />
        <button v-else type="button" class="cr-primary" :disabled="busyBatch" @click="restoreSelected">
          恢复
        </button>
        <button type="button" class="cr-ghost" @click="clearSelection">取消</button>
      </div>
    </transition>

    <MediaLightbox
      v-if="previewIndex >= 0 && previewAssets.length"
      :assets="previewAssets"
      :index="previewIndex"
      @update:index="previewIndex = $event"
      @close="closePreview"
      @star="toggleStar($event, null)"
      @action="previewAction"
    >
      <template #rail-extra="{ asset }">
        <section v-if="asset && !trashed" class="lb-info">
          <h3>业务分类</h3>
          <DropdownSelect :model-value="asset.businessType || ''" block size="sm"
                          :options="businessSelectOptions" aria-label="业务分类"
                          @update:model-value="value => classify(asset, value)" />
        </section>
      </template>
    </MediaLightbox>

    <!-- 上传面板（redesign §5.3）：投放区 + 逐文件进度 + 失败独立重试；关闭不伪装取消 -->
    <div v-if="uploadOpen" class="up-backdrop" @click.self="closeUpload">
      <div class="up-dialog" role="dialog" aria-modal="true" aria-labelledby="upload-title">
        <h2 id="upload-title">上传素材</h2>
        <label class="cr-field">
          <span class="cr-field-label"><span>归入项目<em class="req-star">*</em></span></span>
          <DropdownSelect v-model="uploadProjectId" block :options="uploadProjectOptions"
                          placeholder="选择上传目标项目" aria-label="上传目标项目" />
          <span class="cr-field-help">上传素材归属项目；生成素材仍按原任务归档。上传完成不会自动调用模型。</span>
        </label>

        <div class="up-drop" :class="{ over: dropOver }" role="button" tabindex="0"
             aria-label="拖入或点击选择文件"
             @click="fileInput?.click()" @keydown.enter="fileInput?.click()"
             @dragover.prevent="dropOver = true" @dragleave.prevent="dropOver = false"
             @drop.prevent="onDrop">
          <Upload :size="22" />
          <strong>拖入图片或视频，或点击选择</strong>
          <span>图片 ≤20MB；视频 ≤500MB；可多选</span>
        </div>
        <input ref="fileInput" type="file" multiple accept="image/*,video/*" hidden @change="onPickFiles" />

        <ul v-if="uploadQueue.length" class="up-list">
          <li v-for="item in uploadQueue" :key="item.key">
            <span class="up-name" :title="item.file.name">{{ item.file.name }}</span>
            <span class="up-size">{{ formatBytes(item.file.size) }}</span>
            <span class="up-state" :class="item.status">
              {{ item.status === 'uploading' ? `上传中 ${item.progress}%`
                : item.status === 'done' ? '完成' : item.status === 'error' ? '失败' : '等待' }}
            </span>
            <span v-if="item.status === 'error'" class="up-error" :title="item.error">{{ item.error }}</span>
            <button v-if="item.status === 'error'" type="button" class="cr-ghost" @click="retryItem(item)">重试</button>
          </li>
        </ul>

        <div class="cr-dialog-actions">
          <button type="button" class="cr-secondary" @click="closeUpload">关闭</button>
        </div>
        <p class="cr-field-help">关闭面板不会取消已开始的上传；失败项可稍后在此重试。</p>
      </div>
    </div>

    <p v-if="toast.text" class="cr-toast" :class="toast.tone">{{ toast.text }}</p>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import {
  LayoutGrid, Maximize2, Minimize2, Proportions, SlidersHorizontal, Upload
} from 'lucide-vue-next'
import {
  deleteCreativeAsset, getCreativeJob, listCreativeAssets, listCreativeProjects, restoreCreativeAsset,
  updateCreativeAsset, uploadCreativeAsset
} from '../../api'
import { downloadBlob, formatBytes, starBurst, useAssetUrls, useVideoUrls } from './shared'
import MediaTile from '../../components/creative/MediaTile.vue'
import MediaLightbox from '../../components/creative/MediaLightbox.vue'
import DropdownSelect from '../../components/ui/DropdownSelect.vue'
import HoldToConfirm from '../../components/ui/HoldToConfirm.vue'

const router = useRouter()
const { urls, thumbs, urlFor, thumbFor } = useAssetUrls()
const { videoUrls, videoUrlFor } = useVideoUrls()

const assets = ref([])
const projects = ref([])
const projectId = ref('')
const mediaType = ref('')
const origin = ref('')
const businessType = ref('')
const trashed = ref(false)
const starred = ref(false)
const keyword = ref('')
const typeChips = [
  { value: '', label: '全部' },
  { value: 'IMAGE', label: '图片' },
  { value: 'VIDEO', label: '视频' }
]
const projectOptions = computed(() => [
  { value: '', label: '全部项目' },
  ...projects.value.map(project => ({ value: project.id, label: project.name }))
])
const businessOptions = [
  { value: '', label: '全部' },
  { value: 'CHARACTER', label: '角色' },
  { value: 'LOCATION', label: '地点' },
  { value: 'PROP', label: '道具' },
  { value: 'SHOT', label: '镜头' },
  { value: 'OTHER', label: '其他' }
]
const businessSelectOptions = [
  { value: '', label: '未分类' },
  ...businessOptions.filter(option => option.value).map(option => ({ value: option.value, label: option.label }))
]
const originOptions = [
  { value: '', label: '全部来源' },
  { value: 'UPLOAD', label: '原始素材' },
  { value: 'GENERATED', label: '生成结果' }
]

const VIEW_KEY = 'aster.assets.view'
const DENSITY_KEY = 'aster.assets.density'
const viewMode = ref(localStorage.getItem(VIEW_KEY) === 'original' ? 'original' : 'grid')
const density = ref(localStorage.getItem(DENSITY_KEY) === 'compact' ? 'compact' : 'comfortable')
const filtersOpen = ref(false)
const containerEl = ref(null)
const containerWidth = ref(0)
let resizeObserver = null

const nextCursor = ref(null)
const loading = ref(false)
const error = ref('')
const toast = ref({ text: '', tone: '' })
const requestSeq = ref(0) // 搜索/筛选防抖后取消过期响应：序号不匹配的结果直接丢弃

const previewIndex = ref(-1)
const selectedIds = reactive(new Set())
const busyBatch = ref(false)

// 上传
const uploadOpen = ref(false)
const uploadProjectId = ref('')
const uploadQueue = ref([])
const dropOver = ref(false)
const fileInput = ref(null)
let uploadKey = 0

const uploadProjectOptions = computed(() => projects.value
  .filter(project => project.status === 'ACTIVE')
  .map(project => ({ value: project.id, label: project.name })))
const hasFilters = computed(() => Boolean(origin.value || mediaType.value || businessType.value
  || projectId.value || starred.value || keyword.value))
const selectionCount = computed(() => selectedIds.size)
const selectedAcrossPages = computed(() => [...selectedIds]
  .some(id => !assets.value.some(asset => asset.id === id)))

// 卡片预览地址集中解析：图片优先缩略图、视频用签名地址
const previewUrls = computed(() => {
  const map = {}
  assets.value.forEach(asset => {
    if (asset.mediaType === 'VIDEO') map[asset.id] = videoUrls[asset.id]?.url || ''
    else map[asset.id] = thumbs[asset.id] || urls[asset.id] || ''
  })
  return map
})

const previewAssets = computed(() => (trashed.value ? [] : assets.value))

function showToast(text, tone = '') {
  toast.value = { text, tone }
  window.clearTimeout(showToast.timer)
  showToast.timer = window.setTimeout(() => { toast.value = { text: '', tone: '' } }, 3200)
}

// ---------------------------------------------------------------- 原比例布局（redesign §5.3）
const UNIT = 10
const GAP = 12
const CAPTION = 48

const columns = computed(() => {
  const width = containerWidth.value || 920
  const min = density.value === 'compact' ? 170 : 232
  return Math.max(2, Math.floor(width / min))
})

const gridStyle = computed(() => {
  if (viewMode.value !== 'original') {
    return { gridTemplateColumns: `repeat(auto-fill, minmax(${density.value === 'compact' ? 170 : 224}px, 1fr))` }
  }
  return {
    gridTemplateColumns: `repeat(${columns.value}, minmax(0, 1fr))`,
    gridAutoRows: `${UNIT}px`
  }
})

function rowSpanFor(asset) {
  const ratio = asset.width && asset.height ? asset.height / asset.width : 3 / 4
  const target = (containerWidth.value ? (containerWidth.value - GAP * (columns.value - 1)) / columns.value : 220)
    * Math.min(ratio, 1.6) + CAPTION
  return Math.max(4, Math.ceil((target + GAP) / (UNIT + GAP)))
}

function setView(next) {
  viewMode.value = next
  try { localStorage.setItem(VIEW_KEY, next) } catch (error) { /* 忽略 */ }
}

function toggleDensity() {
  density.value = density.value === 'compact' ? 'comfortable' : 'compact'
  try { localStorage.setItem(DENSITY_KEY, density.value) } catch (error) { /* 忽略 */ }
}

function setMediaType(value) {
  mediaType.value = value
  requestSeq.value++
  load()
}

// ---------------------------------------------------------------- 数据

async function load(cursor = null, append = false) {
  loading.value = true
  error.value = ''
  const seq = ++requestSeq.value
  try {
    const data = await listCreativeAssets({
      projectId: projectId.value || undefined,
      mediaType: mediaType.value || undefined,
      origin: origin.value || undefined,
      businessType: businessType.value || undefined,
      trashed: trashed.value || undefined,
      starred: starred.value ? true : undefined,
      q: keyword.value || undefined,
      cursor: cursor || undefined,
      limit: 60
    })
    if (seq !== requestSeq.value) return // 过期响应：丢弃，防慢请求覆盖新结果
    assets.value = append ? [...assets.value, ...(data.items || [])] : (data.items || [])
    nextCursor.value = data.nextCursor
    if (!trashed.value) {
      assets.value.forEach(asset => {
        if (asset.mediaType === 'VIDEO') videoUrlFor(asset.id)
        else thumbFor(asset.id)
      })
    }
  } catch (exception) {
    if (seq === requestSeq.value) {
      error.value = exception.response?.data?.error || `素材加载失败：${exception.message || exception}`
    }
  } finally {
    if (seq === requestSeq.value) loading.value = false
  }
}

function reload() { load(null, false) }
function loadMore() { if (nextCursor.value) load(nextCursor.value, true) }

let searchTimer = null
// keyword 变化防抖（redesign §6：搜索防抖并取消过期响应）
watch(keyword, () => {
  window.clearTimeout(searchTimer)
  searchTimer = window.setTimeout(() => { requestSeq.value++; load() }, 320)
})

function clearFilters() {
  origin.value = ''
  mediaType.value = ''
  businessType.value = ''
  projectId.value = ''
  starred.value = false
  keyword.value = ''
  requestSeq.value++
  reload()
}

function switchTrashed(next) {
  trashed.value = next
  clearSelection()
  requestSeq.value++
  reload()
}

// ---------------------------------------------------------------- 选择与批量

function toggleSelect(asset) {
  if (selectedIds.has(asset.id)) selectedIds.delete(asset.id)
  else selectedIds.add(asset.id)
  // 触发 Set 响应式更新
  selectedIds.add('__noop__')
  selectedIds.delete('__noop__')
}

function clearSelection() {
  selectedIds.clear()
  selectedIds.add('__noop__')
  selectedIds.delete('__noop__')
}

async function trashSelected() {
  busyBatch.value = true
  try {
    for (const id of [...selectedIds]) {
      try { await deleteCreativeAsset(id) } catch (exception) { /* 单项失败不阻断其余 */ }
      selectedIds.delete(id)
    }
    showToast('所选素材已移至回收站，可随时恢复', 'success')
    requestSeq.value++
    await reload()
  } finally {
    busyBatch.value = false
  }
}

async function restoreSelected() {
  busyBatch.value = true
  try {
    for (const id of [...selectedIds]) {
      try { await restoreCreativeAsset(id) } catch (exception) { /* 单项失败不阻断 */ }
      selectedIds.delete(id)
    }
    showToast('已恢复到素材库', 'success')
    requestSeq.value++
    await reload()
  } finally {
    busyBatch.value = false
  }
}

async function downloadSelected() {
  busyBatch.value = true
  try {
    for (const id of selectedIds) {
      const asset = assets.value.find(item => item.id === id)
      if (!asset) continue
      const url = asset.mediaType === 'VIDEO' ? await videoUrlFor(id) : await urlFor(id)
      downloadBlob(url, `${asset.displayName || id}.${asset.mediaType === 'VIDEO' ? 'mp4' : 'png'}`)
    }
  } finally {
    busyBatch.value = false
  }
}

/** 引用到创作台（redesign §6 从素材继续创作）：选择素材→带入输入台→编辑→确认生成。 */
/** 引用到创作台（redesign §6 从素材继续创作）：图片作为参考素材（image_edit），
 *  视频素材目前不能作为生成参考，如实提示而不是假装可用。 */
async function referenceSelected() {
  if (selectionCount.value !== 1) return
  const asset = assets.value.find(item => item.id === [...selectedIds][0])
  if (!asset) return
  if (asset.mediaType !== 'IMAGE') {
    showToast('视频素材暂不能作为生成参考；可在视频项目里用作首帧', 'error')
    return
  }
  const handoff = { kind: 'IMAGE', refImage: asset.id }
  try {
    if (asset.sourceJobId) {
      const job = await getCreativeJob(asset.sourceJobId)
      if (job?.prompt) handoff.prompt = job.prompt
    }
  } catch (exception) { /* 提示词缺失可接受 */ }
  try { sessionStorage.setItem('aster.creative.handoff', JSON.stringify(handoff)) } catch (error) { /* 忽略 */ }
  router.push('/creative')
}

// ---------------------------------------------------------------- 预览动作

function openPreview(asset) {
  previewIndex.value = previewAssets.value.findIndex(item => item.id === asset.id)
}

function closePreview() {
  previewIndex.value = -1
}

async function previewAction({ type, asset }) {
  if (type === 'download') {
    const url = asset.mediaType === 'VIDEO' ? await videoUrlFor(asset.id) : await urlFor(asset.id)
    downloadBlob(url, `${asset.displayName || asset.id}.${asset.mediaType === 'VIDEO' ? 'mp4' : 'png'}`)
    return
  }
  if (type === 'use-prompt') {
    try {
      const job = asset.sourceJobId ? await getCreativeJob(asset.sourceJobId) : null
      const prompt = job?.prompt || ''
      try { sessionStorage.setItem('aster.creative.handoff', JSON.stringify({ prompt })) } catch (error) { /* 忽略 */ }
      router.push(asset.projectId
        ? `/creative/projects/${asset.projectId}?tab=studio`
        : '/creative')
      closePreview()
    } catch (exception) {
      showToast('原任务信息不可用，无法沿用提示词', 'error')
    }
    return
  }
  if (type === 'first-frame') {
    try {
      sessionStorage.setItem('aster.creative.handoff',
        JSON.stringify({ kind: 'VIDEO', firstFrame: asset.id }))
    } catch (error) { /* 忽略 */ }
    router.push('/creative')
    closePreview()
  }
}

async function classify(asset, value) {
  try {
    const updated = await updateCreativeAsset(asset.id, { businessType: value })
    const index = assets.value.findIndex(item => item.id === updated.id)
    if (index >= 0) assets.value[index] = updated
    showToast('分类已更新', 'success')
  } catch (exception) {
    showToast(exception.response?.data?.error || '分类更新失败', 'error')
  }
}

async function toggleStar(asset, event) {
  const burstTarget = event?.currentTarget
  try {
    const updated = await updateCreativeAsset(asset.id, { starred: !asset.starred })
    const index = assets.value.findIndex(item => item.id === asset.id)
    if (index >= 0) assets.value[index] = updated
    if (updated.starred) starBurst(burstTarget)
  } catch (exception) {
    showToast('收藏失败', 'error')
  }
}

// ---------------------------------------------------------------- 上传

function openUpload() {
  uploadOpen.value = true
  if (!uploadProjectId.value) uploadProjectId.value = uploadProjectOptions.value[0]?.value || ''
}

function closeUpload() {
  uploadOpen.value = false
  // 已开始的上传继续后台执行（顺序队列）；失败项保留待重试，不伪装取消
}

function onDrop(event) {
  dropOver.value = false
  enqueueFiles(event.dataTransfer?.files)
}

function onPickFiles(event) {
  enqueueFiles(event.target.files)
  event.target.value = ''
}

function enqueueFiles(fileList) {
  if (!fileList || !fileList.length) return
  for (const file of fileList) {
    uploadQueue.value.push({
      key: `f${++uploadKey}`,
      file,
      status: 'pending',
      progress: 0,
      error: ''
    })
  }
  processQueue()
}

async function processQueue() {
  const pending = uploadQueue.value.find(item => item.status === 'pending')
  if (!pending) return
  if (!uploadProjectId.value) {
    pending.status = 'error'
    pending.error = '请先选择目标项目'
    processQueue()
    return
  }
  pending.status = 'uploading'
  pending.progress = 0
  try {
    await uploadCreativeAsset(uploadProjectId.value, pending.file, event => {
      if (event.lengthComputable) pending.progress = Math.round(event.loaded / event.total * 100)
    })
    pending.status = 'done'
    if (!trashed.value && !hasFilters.value) requestSeq.value++; reload()
  } catch (exception) {
    pending.status = 'error'
    pending.error = exception.response?.data?.error || '上传失败'
  } finally {
    processQueue()
  }
}

function retryItem(item) {
  if (item.status !== 'error') return
  item.status = 'pending'
  item.progress = 0
  item.error = ''
  processQueue()
}

// ---------------------------------------------------------------- 生命周期

onMounted(async () => {
  resizeObserver = new ResizeObserver(entries => {
    for (const entry of entries) containerWidth.value = entry.contentRect.width
  })
  if (containerEl.value) resizeObserver.observe(containerEl.value)
  try {
    const data = await listCreativeProjects({ limit: 100 })
    projects.value = data.items || []
  } catch (exception) { /* 项目筛选不可用时仍可看全部素材 */ }
  await load()
})

onBeforeUnmount(() => {
  resizeObserver?.disconnect()
  window.clearInterval(searchTimer)
})
</script>

<style scoped src="./creative.css"></style>

<style scoped>
.assets-page {
  max-width: 1200px;
  margin: 0 auto;
  padding: 12px var(--wb-space-7) var(--wb-space-8);
  display: flex;
  flex-direction: column;
  gap: var(--wb-space-4);
}

.assets-head { display: flex; align-items: flex-start; justify-content: space-between; gap: var(--wb-space-4); }

/* 单行工具栏：标签页 + 搜索 + 类型 chips + 布局切换 + 筛选 */
.assets-toolbar {
  display: flex;
  align-items: center;
  gap: var(--wb-space-3);
  flex-wrap: wrap;
}

.assets-tabs { display: flex; gap: 4px; }
.assets-tab {
  padding: 6px 12px;
  border: 0;
  border-bottom: 2px solid transparent;
  background: transparent;
  color: var(--wb-text-2);
  font-size: var(--wb-fs-compact);
  cursor: pointer;
}
.assets-tab:hover { color: var(--wb-text); }
.assets-tab.active { color: var(--wb-brand); border-bottom-color: var(--wb-brand); font-weight: 550; }

.assets-search { width: 210px; }

.assets-kind { display: flex; gap: 4px; }
.assets-chip {
  padding: 6px 14px;
  border: 1px solid var(--cr-border, var(--wb-border));
  border-radius: 999px;
  background: transparent;
  color: var(--cr-text-2, var(--wb-text-2));
  font-size: 12.5px;
  cursor: pointer;
  transition: border-color var(--cr-dur-fast, 140ms) var(--cr-ease, ease), color var(--cr-dur-fast, 140ms) var(--cr-ease, ease), background var(--cr-dur-fast, 140ms) var(--cr-ease, ease);
}
.assets-chip.active {
  border-color: var(--cr-border, var(--wb-border));
  background: var(--cr-surface, #fff);
  color: var(--cr-text, var(--wb-text));
  font-weight: 600;
  box-shadow: 0 1px 2px rgba(20, 18, 24, 0.06);
}

.assets-view { display: flex; border: 1px solid var(--wb-border); border-radius: var(--wb-radius-sm); overflow: hidden; }
.assets-view-btn {
  display: grid;
  place-items: center;
  width: 30px;
  height: 30px;
  border: 0;
  background: transparent;
  color: var(--wb-muted);
  cursor: pointer;
}
.assets-view-btn:hover { background: var(--wb-fill); color: var(--wb-text); }
.assets-view-btn.active { background: var(--wb-brand-soft); color: var(--wb-brand); }

.assets-filter-toggle { margin-left: auto; }

.assets-layout {
  display: grid;
  grid-template-columns: minmax(0, 1fr);
  gap: var(--wb-space-5);
  align-items: start;
}
.assets-layout.filters-open { grid-template-columns: 220px minmax(0, 1fr); }

.assets-filters {
  display: flex;
  flex-direction: column;
  gap: var(--wb-space-4);
  padding: var(--wb-space-3);
  border: 1px solid var(--wb-border);
  border-radius: var(--wb-radius);
  background: var(--wb-surface);
  position: sticky;
  top: 0;
}

.filter-group { display: flex; flex-direction: column; gap: 4px; }
.filter-group h2 {
  margin: 0 0 2px;
  font-size: var(--wb-fs-caption);
  font-weight: 500;
  color: var(--wb-muted);
}

.filter-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 6px 8px;
  border: 0;
  border-radius: var(--wb-radius-sm);
  background: transparent;
  color: var(--wb-text-2);
  font-size: var(--wb-fs-compact);
  text-align: left;
  cursor: pointer;
}

.filter-item:hover { background: var(--wb-fill); color: var(--wb-text); }
.filter-item.active { background: var(--wb-brand-soft); color: var(--wb-brand); font-weight: 550; }

.filter-check {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: var(--wb-fs-compact);
  color: var(--wb-text-2);
  padding-top: var(--wb-space-2);
  border-top: 1px solid var(--wb-border);
}
.filter-check input { width: 14px; height: 14px; accent-color: var(--wb-brand); }

.assets-main { display: flex; flex-direction: column; gap: var(--wb-space-3); min-width: 0; }
.assets-status { display: flex; align-items: center; justify-content: space-between; min-height: 24px; }
.assets-count { font-size: var(--wb-fs-compact); color: var(--wb-text-2); }

/* 网格模式：统一比例；原比例模式：行 span 定高（style 由 gridStyle 注入） */
.asset-grid { display: grid; gap: var(--wb-space-3); align-items: start; }
.asset-grid.is-grid { grid-auto-rows: auto; }
.asset-grid.is-original { grid-auto-flow: row; }

.load-more {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
  padding: var(--wb-space-3) 0 var(--wb-space-6);
}

/* 批量工具条：底部上移 12px（redesign §7.2） */
.selection-bar {
  position: fixed;
  left: 50%;
  bottom: 20px;
  transform: translateX(-50%);
  z-index: 60;
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 14px;
  border: 1px solid var(--wb-border-strong);
  border-radius: var(--cr-radius-pop, 16px);
  background: var(--cr-surface, var(--wb-surface));
  box-shadow: var(--wb-shadow-lg);
}
.selection-bar strong { font-size: 13px; color: var(--wb-text); margin-right: 4px; }
.bar-enter-active { transition: transform var(--cr-dur-fast, 140ms) var(--cr-ease, ease), opacity var(--cr-dur-fast, 140ms) var(--cr-ease, ease); }
.bar-enter-from { transform: translate(-50%, 12px); opacity: 0; }
.bar-leave-active { transition: opacity var(--cr-dur-fast, 140ms) var(--cr-ease-exit, ease); }
.bar-leave-to { opacity: 0; }

/* 上传面板 */
.up-backdrop {
  position: fixed;
  inset: 0;
  z-index: 70;
  display: flex;
  align-items: center;
  justify-content: center;
  background: rgba(12, 12, 14, 0.45);
}
.up-dialog {
  width: min(520px, 94vw);
  max-height: 86vh;
  overflow-y: auto;
  display: flex;
  flex-direction: column;
  gap: var(--wb-space-3);
  padding: 20px;
  border-radius: var(--cr-radius-pop, 16px);
  background: var(--cr-surface, var(--wb-surface));
  box-shadow: var(--wb-shadow-lg);
}
.up-dialog h2 { margin: 0; font-size: 16px; }

.up-drop {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 4px;
  padding: 22px 16px;
  border: 1.5px dashed var(--wb-border-strong);
  border-radius: var(--cr-radius-media, 14px);
  background: var(--wb-fill);
  color: var(--wb-text-2);
  cursor: pointer;
  text-align: center;
  transition: border-color var(--cr-dur-fast, 140ms) var(--cr-ease, ease), background var(--cr-dur-fast, 140ms) var(--cr-ease, ease);
}
.up-drop.over, .up-drop:hover { border-color: var(--wb-brand); background: var(--wb-brand-soft); }
.up-drop strong { font-size: 13px; color: var(--wb-text); }
.up-drop span { font-size: 11.5px; color: var(--wb-muted); }

.up-list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 4px;
  max-height: 220px;
  overflow-y: auto;
}
.up-list li {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
  padding: 4px 2px;
  border-bottom: 1px dashed var(--wb-border);
}
.up-name { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; color: var(--wb-text); }
.up-size { color: var(--wb-muted); }
.up-state { font-variant-numeric: tabular-nums; }
.up-state.done { color: var(--wb-success); }
.up-state.error { color: var(--wb-danger); }
.up-state.uploading { color: var(--wb-info); }
.up-error { color: var(--wb-danger); max-width: 140px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }

@media (max-width: 900px) {
  .assets-page { padding: var(--wb-space-4); }
  .assets-search { flex: 1; width: auto; }
  .assets-layout.filters-open { grid-template-columns: 1fr; }
  .assets-filters { position: static; }
}
</style>
