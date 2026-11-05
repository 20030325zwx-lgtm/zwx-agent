<template>
  <div class="tab-scroll">
    <div class="tab-inner">
      <header class="sb-head">
        <div>
          <h2 class="sb-title">分镜</h2>
          <p class="cr-field-help">
            生成成功不会自动选中：先比较候选，再人工选定；「待选片」和「阻塞」决定这一集还差什么。
          </p>
        </div>
        <div class="sb-head-actions">
          <button type="button" class="cr-secondary" @click="openAddShot">
            <Plus :size="14" /> 加镜头
          </button>
          <button type="button" class="cr-primary" :disabled="!selectedShots.size || busy"
                  @click="createBatch">
            批量生成（{{ selectedShots.size }}）
          </button>
          <button type="button" class="cr-ghost" :disabled="story.loading.value" @click="story.reload()">
            <RefreshCw :size="14" /> 刷新
          </button>
        </div>
      </header>

      <p v-if="story.error.value" class="cr-error">{{ story.error.value }}</p>
      <p v-if="error" class="cr-error">{{ error }}</p>

      <!-- 生产批次：进度贴近列表，停止只停未提交项，重试只针对失败项 -->
      <section v-if="batchViews.length" class="batch-panel">
        <header class="batch-panel-head">
          <strong>生产批次</strong>
          <span class="cr-field-help">停止只停未提交镜头；在途任务不会被取消</span>
        </header>
        <div v-for="view in batchViews" :key="view.batch.id" class="batch">
          <div class="batch-head">
            <span class="batch-name">{{ view.batch.scopeLabel || '批次' }}</span>
            <span class="batch-status" :class="'batch-' + view.batch.status">
              {{ BATCH_STATUS_LABEL[view.batch.status] || view.batch.status }}
            </span>
            <span class="batch-progress">
              成功 {{ view.progress.done }}/{{ view.progress.total }}
              <em v-if="view.progress.failed">· 失败 {{ view.progress.failed }}</em>
              <em v-if="view.progress.skipped">· 已停止 {{ view.progress.skipped }}</em>
              · 完成 {{ view.progress.donePercent }}%
            </span>
            <span class="batch-actions">
              <button v-if="['RUNNING','STOPPING'].includes(view.batch.status)" type="button" class="cr-ghost"
                      @click="stopBatch(view.batch)">停止</button>
              <button v-if="view.progress.failed > 0" type="button" class="cr-ghost"
                      @click="retryBatch(view.batch)">重试失败项（{{ view.progress.failed }}）</button>
            </span>
          </div>
          <div class="batch-items">
            <span v-for="item in view.items" :key="item.item.id" class="chip" :class="'item-' + item.item.status"
                  :title="item.item.error || ''">
              {{ item.shotLabel }}·{{ BATCH_ITEM_STATUS_LABEL[item.item.status] || item.item.status }}
              <em v-if="item.item.error" class="chip-error">{{ item.item.error }}</em>
            </span>
          </div>
        </div>
      </section>

      <div class="sb-toolbar">
        <div class="sb-filters" role="radiogroup" aria-label="分镜筛选">
          <button v-for="option in filters" :key="option.value" type="button"
                  class="sb-chip" :class="{ active: filter === option.value }"
                  @click="setFilter(option.value)">
            {{ option.label }}
            <em v-if="option.count !== undefined">{{ option.count }}</em>
          </button>
        </div>
        <div class="sb-tools">
          <DropdownSelect v-model="sceneFilter" class="sb-scene" size="sm" :options="sceneOptions"
                          placeholder="全部场次" aria-label="按场次筛选" />
          <div class="sb-view-toggle" role="radiogroup" aria-label="视图切换">
            <button type="button" :class="{ on: viewMode === 'card' }" aria-label="卡片视图"
                    @click="viewMode = 'card'">卡片</button>
            <button type="button" :class="{ on: viewMode === 'table' }" aria-label="表格视图"
                    @click="viewMode = 'table'">表格</button>
          </div>
          <button type="button" class="cr-ghost" @click="toggleSelectAll">
            {{ allSelected ? '取消全选' : '全选可生成' }}
          </button>
        </div>
      </div>

      <p v-if="!story.loading.value && !shots.length" class="cr-empty">
        还没有镜头。先在「剧情」页建场次，再回到这里「加镜头」并写提示词。
      </p>
      <p v-else-if="!visibleShots.length" class="cr-empty">当前筛选下没有镜头。</p>
      <div v-else-if="viewMode === 'table'" class="sb-table-wrap">
        <table class="sb-table">
          <thead>
            <tr>
              <th class="col-check"><input type="checkbox" :checked="allSelected" aria-label="全选可生成"
                                           @change="toggleSelectAll" /></th>
              <th class="col-no">序号</th>
              <th class="col-preview">预览</th>
              <th class="col-seconds">时长</th>
              <th class="col-action">画面与动作</th>
              <th class="col-lines">台词摘要</th>
              <th class="col-assets">候选素材</th>
              <th class="col-status">状态</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="view in cardViews" :key="view.shot.id" class="sb-row"
                :class="{ checked: selectedShots.has(view.shot.id) }" @click="openInspector(view.shot)">
              <td class="col-check" @click.stop>
                <input v-if="selectableShots.some(item => item.id === view.shot.id)" type="checkbox"
                       :checked="selectedShots.has(view.shot.id)" :aria-label="`选择 ${itemLabel(view.shot)}`"
                       @change="toggleSelect(view.shot.id)" />
              </td>
              <td class="col-no">{{ sceneLabelOf(view.shot) }}·{{ String(view.shot.shotNumber).padStart(2, '0') }}</td>
              <td class="col-preview">
                <video v-if="view.assetId && previewUrl(view)" :src="previewUrl(view)" muted preload="metadata"
                       :aria-label="`${itemLabel(view.shot)} 预览`"></video>
                <span v-else class="cell-placeholder">{{ view.assetId ? '加载中…' : '未生成' }}</span>
              </td>
              <td class="col-seconds">{{ view.shot.durationTargetSeconds }}s</td>
              <td class="col-action">
                <strong>{{ view.shot.purpose || '—' }}</strong>
                <p>{{ view.shot.action || view.shot.camera || '' }}</p>
              </td>
              <td class="col-lines">{{ view.shot.dialogue || '—' }}</td>
              <td class="col-assets">{{ assetSummary(view) }}</td>
              <td class="col-status"><span class="cell-status">{{ SHOT_STATUS_LABEL[view.shot.status] || view.shot.status }}</span></td>
            </tr>
          </tbody>
        </table>
      </div>
      <div v-else class="sb-grid">
        <ShotCard
          v-for="view in cardViews"
          :key="view.shot.id"
          :shot="view.shot"
          :scene-label="sceneLabelOf(view.shot)"
          :candidates="view.candidates"
          :checked="selectedShots.has(view.shot.id)"
          :preview-url="previewUrl(view)"
          :preview-kind="view.assetId ? 'video' : ''"
          @open="openInspector"
          @toggle-check="toggleSelect"
        />
      </div>
    </div>

    <ShotInspector v-if="inspectorShot" :project-id="projectId" :shot="inspectorShot"
                   :characters="story.characters.value" :locations="story.locations.value"
                   @close="inspectorShot = null"
                   @changed="onShotChanged"
                   @inserted="onShotInserted" />

    <div v-if="addShotOpen" class="cr-dialog-backdrop" @click.self="addShotOpen = false">
      <div class="cr-dialog" role="dialog" aria-modal="true" aria-labelledby="add-shot-title">
        <h2 id="add-shot-title">加镜头</h2>
        <label class="cr-field">
          <span class="cr-field-label"><span>所属场次</span></span>
          <DropdownSelect v-model="addShotScene" block :options="sceneOptions.slice(1)"
                          placeholder="选择场次" aria-label="所属场次" />
        </label>
        <p v-if="!scenes.length" class="cr-hint">还没有场次，请先在「剧情」页新建场次。</p>
        <div class="cr-dialog-actions">
          <button type="button" class="cr-secondary" @click="addShotOpen = false">取消</button>
          <button type="button" class="cr-primary" :disabled="!addShotScene || busy" @click="addShot">
            创建镜头
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, inject, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { Plus, RefreshCw } from 'lucide-vue-next'
import {
  createStoryBatch, createStoryShot, getStoryBatch, listStoryBatches, retryStoryBatch, stopStoryBatch
} from '../../../api'
import { PROJECT_CTX } from './composables'
import { BATCH_ITEM_STATUS_LABEL, BATCH_STATUS_LABEL, SHOT_STATUS_LABEL } from './helpers'
import ShotCard from '../../../components/creative/project/ShotCard.vue'
import DropdownSelect from '../../../components/ui/DropdownSelect.vue'
import ShotInspector from '../story/ShotInspector.vue'

const { projectId, story, showToast, route, router, videoUrls, videoUrlFor } = inject(PROJECT_CTX)

const filter = ref('all')
const sceneFilter = ref('')
const selectedShots = reactive(new Set())
const inspectorShot = ref(null)
const busy = ref(false)
const error = ref('')
const batches = ref([])
const batchViews = ref([])
const addShotOpen = ref(false)
const addShotScene = ref('')
const viewMode = ref(localStorage.getItem('aster.storyboard.view') === 'table' ? 'table' : 'card')
let batchTimer = null
watch(viewMode, value => {
  try { localStorage.setItem('aster.storyboard.view', value) } catch { /* 无痕模式忽略 */ }
})
function itemLabel(shot) {
  return `${sceneLabelOf(shot)}·${String(shot.shotNumber).padStart(2, '0')}`
}
function assetSummary(view) {
  const candidates = view.candidates || []
  if (!candidates.length) return '—'
  const selected = candidates.filter(item => item.status === 'SELECTED').length
  return selected ? `已采用 ${selected} / 共 ${candidates.length}` : `共 ${candidates.length}`
}

const shots = computed(() => story.shots.value)
const scenes = computed(() => story.scenes.value)
const episodes = computed(() => story.episodes.value)
const progress = computed(() => story.progress.value)

const sceneOptions = computed(() => [
  { value: '', label: '全部场次' },
  ...scenes.value.map(scene => ({ value: scene.id, label: `第 ${scene.sceneNumber} 场 ${scene.title || ''}`.trim() }))
])
const filters = computed(() => [
  { value: 'all', label: '全部', count: shots.value.length },
  { value: 'pending', label: '待选片', count: progress.value.pendingSelection },
  { value: 'failed', label: '生成失败', count: progress.value.failed },
  { value: 'missing', label: '缺提示词', count: progress.value.missingPrompt }
])

const visibleShots = computed(() => shots.value.filter(shot => {
  if (sceneFilter.value && shot.sceneId !== sceneFilter.value) return false
  if (filter.value === 'pending') return shot.status === 'PENDING_SELECTION'
  if (filter.value === 'missing') return !String(shot.promptText || '').trim()
  if (filter.value === 'failed') {
    const candidates = story.candidatesOf(shot.id)
    const usable = candidates.some(item => ['CANDIDATE', 'SELECTED'].includes(item.status))
    const failed = candidates.some(item => item.status === 'FAILED')
    return !usable && failed
  }
  return true
}))

const selectableShots = computed(() => visibleShots.value.filter(shot =>
  ['DRAFT', 'READY'].includes(shot.status) && Boolean(String(shot.promptText || '').trim())))

/**
 * 镜头卡视图：候选与预览素材在父级统一推导。
 * 候选视频没有缩略图，需要签名播放地址；逐卡自取会重复请求，也把媒体钩子塞进 v-for 里。
 */
// 注意：workspace 里的候选是扁平记录（assetId/status 在顶层），
// 只有 /shots/{id}/candidates 返回带 job 状态的 CandidateView。
const cardViews = computed(() => visibleShots.value.map(shot => {
  const candidates = story.candidatesOf(shot.id)
  const usable = candidates.filter(item => item.assetId)
  const previewCandidate = usable.find(item => item.status === 'SELECTED') || usable[0] || null
  return { shot, candidates, assetId: previewCandidate?.assetId || '' }
}))

function previewUrl(view) {
  return view.assetId ? (videoUrls[view.assetId]?.url || '') : ''
}

watch(cardViews, views => {
  views.forEach(view => { if (view.assetId) videoUrlFor(view.assetId) })
}, { flush: 'post' })
const allSelected = computed(() => selectableShots.value.length > 0
  && selectableShots.value.every(shot => selectedShots.has(shot.id)))

function sceneLabelOf(shot) {
  const scene = scenes.value.find(item => item.id === shot.sceneId)
  return scene ? `第 ${scene.sceneNumber} 场` : '—'
}

function setFilter(value) {
  filter.value = value
  const query = { ...route.query }
  if (value === 'pending') query.filter = 'pending'
  else delete query.filter
  router.replace({ path: route.path, query })
}

function toggleSelect(shotId) {
  if (selectedShots.has(shotId)) selectedShots.delete(shotId)
  else selectedShots.add(shotId)
}

function toggleSelectAll() {
  if (allSelected.value) {
    selectedShots.clear()
    return
  }
  selectableShots.value.forEach(shot => selectedShots.add(shot.id))
}

function openInspector(shot) {
  inspectorShot.value = shot
}

async function onShotChanged() {
  await story.reload()
  if (inspectorShot.value) {
    inspectorShot.value = story.shots.value.find(shot => shot.id === inspectorShot.value.id) || inspectorShot.value
  }
  await loadBatches()
}

/** 在当前镜头之后插入（P0）：新镜头进入编辑态。 */
async function onShotInserted(created) {
  await story.reload()
  inspectorShot.value = story.shots.value.find(shot => shot.id === created.id) || created
}

async function createBatch() {
  const episode = episodes.value.find(item => story.shots.value
    .some(shot => selectedShots.has(shot.id) && shot.sceneId
      && item.id === scenes.value.find(scene => scene.id === shot.sceneId)?.episodeId))
  busy.value = true
  error.value = ''
  try {
    await createStoryBatch(projectId.value, {
      shotIds: [...selectedShots],
      scopeLabel: episode ? `第 ${episode.episodeNumber} 集《${episode.title}》` : ''
    })
    selectedShots.clear()
    showToast('批次已创建，调度器会按并发逐个提交', 'success')
    await loadBatches()
  } catch (exception) {
    error.value = exception.response?.data?.error || '批次创建失败'
  } finally {
    busy.value = false
  }
}

async function loadBatches() {
  try {
    batches.value = await listStoryBatches(projectId.value)
    const views = await Promise.allSettled(
      batches.value.map(batch => getStoryBatch(projectId.value, batch.id)))
    batchViews.value = views.map((result, index) =>
      result.status === 'fulfilled' ? result.value : { batch: batches.value[index], items: [], progress: null })
      .filter(view => view.progress)
  } catch (exception) { /* 批次面板加载失败不阻断主区 */ }
  scheduleBatchPoll()
}

function scheduleBatchPoll() {
  window.clearTimeout(batchTimer)
  const running = batchViews.value.some(view => ['RUNNING', 'STOPPING'].includes(view.batch.status))
  if (!running) return
  batchTimer = window.setTimeout(async () => {
    if (document.visibilityState !== 'hidden') await loadBatches()
    else scheduleBatchPoll()
  }, 3000)
}

async function stopBatch(batch) {
  try {
    await stopStoryBatch(projectId.value, batch.id)
    await loadBatches()
    await story.reload()
  } catch (exception) {
    error.value = exception.response?.data?.error || '停止失败'
  }
}

async function retryBatch(batch) {
  try {
    await retryStoryBatch(projectId.value, batch.id)
    await loadBatches()
    await story.reload()
  } catch (exception) {
    error.value = exception.response?.data?.error || '重试失败'
  }
}

function openAddShot() {
  addShotScene.value = sceneFilter.value || scenes.value[0]?.id || ''
  addShotOpen.value = true
}

async function addShot() {
  if (!addShotScene.value) return
  busy.value = true
  try {
    const shot = await createStoryShot(projectId.value, addShotScene.value, {})
    addShotOpen.value = false
    await story.reload()
    inspectorShot.value = story.shots.value.find(item => item.id === shot.id) || shot
  } catch (exception) {
    error.value = exception.response?.data?.error || '创建镜头失败'
  } finally {
    busy.value = false
  }
}

watch(() => route.query.filter, value => {
  filter.value = value === 'pending' ? 'pending' : 'all'
})
watch(() => route.query.scene, value => { sceneFilter.value = value || '' })

onMounted(async () => {
  if (!story.workspace.value) await story.reload()
  filter.value = route.query.filter === 'pending' ? 'pending' : 'all'
  sceneFilter.value = route.query.scene || ''
  await loadBatches()
  cardViews.value.forEach(view => { if (view.assetId) videoUrlFor(view.assetId) })
})

onBeforeUnmount(() => window.clearTimeout(batchTimer))
</script>

<style scoped src="../creative.css"></style>

<style scoped>
.sb-head { display: flex; align-items: flex-start; justify-content: space-between; gap: var(--wb-space-3); flex-wrap: wrap; }
.sb-title { margin: 0; font-size: var(--wb-fs-section); line-height: var(--wb-lh-section); font-weight: 600; }
.sb-head-actions { display: flex; gap: var(--wb-space-2); align-items: center; flex-wrap: wrap; }

.batch-panel {
  border: 1px solid var(--wb-border);
  border-radius: var(--wb-radius);
  background: var(--wb-surface-2);
  padding: 10px 14px;
}
.batch-panel-head { display: flex; align-items: baseline; gap: 10px; margin-bottom: 6px; }
.batch { padding: 6px 0; border-bottom: 1px solid var(--wb-border); }
.batch:last-child { border-bottom: 0; }
.batch-head { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; font-size: var(--wb-fs-compact); }
.batch-name { font-weight: 550; }
.batch-status { font-size: var(--wb-fs-caption); color: var(--wb-text-2); }
.batch-COMPLETED { color: var(--wb-success); }
.batch-RUNNING { color: var(--wb-info); }
.batch-STOPPED { color: var(--wb-muted); }
.batch-progress { color: var(--wb-muted); font-size: var(--wb-fs-caption); }
.batch-progress em { font-style: normal; color: var(--wb-warning); }
.sb-view-toggle { display: inline-flex; border: 1px solid var(--wb-border); border-radius: 7px; overflow: hidden; }
.sb-view-toggle button { height: 26px; padding: 0 10px; border: 0; background: transparent; color: var(--wb-muted); font-size: 11px; cursor: pointer; }
.sb-view-toggle button.on { background: var(--wb-brand-soft, rgb(102 84 199 / 12%)); color: var(--wb-brand, #6466d5); }
.sb-table-wrap { border: 1px solid var(--wb-border); border-radius: 10px; background: var(--wb-surface); overflow: auto; max-height: calc(100vh - 320px); }
.sb-table { width: 100%; border-collapse: collapse; font-size: 12px; }
.sb-table th { position: sticky; top: 0; z-index: 1; background: var(--wb-surface-2, #f2f3f5); color: var(--wb-muted); font-weight: 500; text-align: left; padding: 8px 10px; border-bottom: 1px solid var(--wb-border); white-space: nowrap; }
.sb-table td { padding: 8px 10px; border-bottom: 1px solid var(--wb-border); vertical-align: top; }
.sb-row { cursor: pointer; }
.sb-row:hover { background: var(--wb-fill, #f5f6fa); }
.sb-row.checked { background: var(--wb-brand-soft, rgb(102 84 199 / 8%)); }
.col-check { width: 48px; text-align: center; }
.col-no { width: 96px; white-space: nowrap; }
.col-preview { width: 144px; }
.col-preview video { width: 144px; height: 81px; object-fit: cover; border-radius: 6px; background: var(--cr-media-stage, #101113); display: block; }
.col-seconds { width: 72px; white-space: nowrap; }
.col-action { min-width: 220px; }
.col-action strong { display: block; font-weight: 550; color: var(--wb-text); }
.col-action p { margin: 3px 0 0; color: var(--wb-muted); font-size: 11px; display: -webkit-box; -webkit-line-clamp: 3; -webkit-box-orient: vertical; overflow: hidden; }
.col-lines { width: 160px; color: var(--wb-text-2); max-width: 160px; overflow: hidden; text-overflow: ellipsis; display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; }
.col-assets { width: 144px; color: var(--wb-text-2); white-space: nowrap; }
.col-status { width: 104px; }
.cell-status { padding: 2px 8px; border-radius: 999px; background: var(--wb-fill); font-size: 11px; }
.cell-placeholder { display: flex; align-items: center; justify-content: center; width: 144px; height: 81px; border-radius: 6px; background: var(--cr-media-stage, #101113); color: var(--wb-muted); font-size: 11px; }
.batch-actions { margin-left: auto; display: flex; gap: 6px; }
.batch-items { display: flex; gap: 6px; flex-wrap: wrap; margin-top: 6px; }

.chip {
  padding: 1px 8px;
  border-radius: 999px;
  background: var(--wb-fill);
  font-size: var(--wb-fs-caption);
  color: var(--wb-text-2);
}
.item-DONE { color: var(--wb-success); }
.chip-error { margin-left: 6px; font-style: normal; color: var(--wb-danger, #c94c62); font-size: 10px; }
.chip.item-FAILED { background: color-mix(in srgb, var(--wb-danger, #c94c62) 8%, var(--wb-fill)); }
.item-FAILED { color: var(--wb-warning); }
.item-SUBMITTED { color: var(--wb-info); }

.sb-toolbar { display: flex; align-items: center; justify-content: space-between; gap: 10px; flex-wrap: wrap; }
.sb-filters { display: flex; gap: 6px; flex-wrap: wrap; }
.sb-chip {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 4px 11px;
  border: 1px solid var(--wb-border);
  border-radius: 999px;
  background: transparent;
  color: var(--wb-text-2);
  font-size: var(--wb-fs-caption);
  cursor: pointer;
}
.sb-chip em { font-style: normal; color: var(--wb-muted); }
.sb-chip.active { border-color: var(--wb-brand); background: var(--wb-brand-soft); color: var(--wb-brand); }
.sb-chip.active em { color: var(--wb-brand); }
.sb-tools { display: flex; align-items: center; gap: var(--wb-space-2); }
.sb-scene { width: 200px; }

.sb-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(260px, 1fr)); gap: var(--wb-space-3); }

@media (max-width: 720px) {
  .sb-tools { width: 100%; }
  .sb-scene { flex: 1; width: auto; }
}
</style>
