<template>
  <div class="story-workspace">
    <header class="sw-head">
      <div class="sw-head-title">
        <strong>剧情制作</strong>
        <span class="cr-field-help">项目 → 剧集 → 场次 → 镜头；生成成功进入「待选片」，人工选定后才计入可用镜头</span>
      </div>
      <div class="sw-head-actions">
        <button type="button" class="cr-ghost" @click="$emit('open-settings')">
          <SettingsIcon :size="14" /> 设定与连续性
        </button>
        <button type="button" class="cr-secondary" @click="reload"><RefreshCw :size="14" /> 刷新</button>
        <button type="button" class="cr-primary" :disabled="!selectedShots.size || busy"
                @click="createBatch">
          批量生成（{{ selectedShots.size }}）
        </button>
        <button type="button" class="cr-primary" @click="openEpisodeCreate()"><Plus :size="14" /> 新剧集</button>
      </div>
    </header>

    <p v-if="error" class="cr-error">{{ error }}</p>
    <p v-if="!loading && !episodes.length" class="cr-empty">
      还没有剧集。从「新剧集」开始：先写本集目标与梗概，再拆场次和分镜。
    </p>

    <section v-for="episode in episodes" :key="episode.id" class="sw-episode">
      <header class="sw-episode-head">
        <div class="sw-episode-title">
          <strong>第 {{ episode.episodeNumber }} 集</strong>
          <span class="sw-episode-name">{{ episode.title }}</span>
          <span class="cr-status" :class="episode.status === 'CONFIRMED' ? 'success' : 'muted'">
            {{ episode.status === 'CONFIRMED' ? '已确认' : '草稿' }}
          </span>
        </div>
        <div class="sw-episode-actions">
          <button type="button" class="cr-ghost" @click="openEpisodeEdit(episode)">编辑</button>
          <button type="button" class="cr-ghost" :disabled="optimizing" @click="optimizeStory(episode)">
            {{ optimizing && optimizeMode === 'VIDEO_STORY' ? '优化中…' : '优化剧情' }}
          </button>
          <button v-if="episode.status !== 'CONFIRMED'" type="button" class="cr-ghost"
                  @click="confirmEpisode(episode)">确认</button>
          <button type="button" class="cr-ghost" @click="move(episode, -1)" :disabled="episode.episodeNumber <= 1">↑</button>
          <button type="button" class="cr-ghost" @click="move(episode, 1)"
                  :disabled="episode.episodeNumber >= episodes.length">↓</button>
          <HoldToConfirm label="删除" @confirm="removeEpisode(episode)" />
        </div>
      </header>

      <div v-if="episode.goal || episode.synopsis" class="sw-episode-body">
        <p v-if="episode.goal" class="sw-line"><span class="sw-label">本集目标</span>{{ episode.goal }}</p>
        <p v-if="episode.synopsis" class="sw-line"><span class="sw-label">梗概</span>{{ episode.synopsis }}</p>
        <p v-if="episode.script" class="sw-line"><span class="sw-label">剧本</span><span class="sw-script">{{ episode.script }}</span></p>
      </div>

      <div class="sw-scenes">
        <article v-for="scene in scenesOf(episode.id)" :key="scene.id" class="sw-scene">
          <header class="sw-scene-head">
            <strong>第 {{ scene.sceneNumber }} 场</strong>
            <span>{{ scene.title }}</span>
            <span v-if="scene.locationText" class="sw-chip">{{ scene.locationText }}</span>
            <span v-if="scene.timeOfDay" class="sw-chip">{{ scene.timeOfDay }}</span>
            <span class="cr-status" :class="scene.status === 'CONFIRMED' ? 'success' : 'muted'">
              {{ scene.status === 'CONFIRMED' ? '已确认' : '草稿' }}
            </span>
            <span class="sw-scene-actions">
              <button type="button" class="cr-ghost" @click="openSceneEdit(scene)">编辑</button>
              <button type="button" class="cr-ghost" :disabled="optimizing" @click="optimizeStoryboard(scene)">
                {{ optimizing && optimizeMode === 'VIDEO_STORYBOARD' ? '拆镜中…' : '细化分镜' }}
              </button>
              <button v-if="scene.status !== 'CONFIRMED'" type="button" class="cr-ghost"
                      @click="confirmScene(scene)">确认</button>
              <HoldToConfirm label="删除" @confirm="removeScene(scene)" />
            </span>
          </header>
          <p v-if="scene.goal" class="sw-line"><span class="sw-label">目标</span>{{ scene.goal }}</p>
          <p v-if="scene.conflict" class="sw-line"><span class="sw-label">冲突</span>{{ scene.conflict }}</p>
          <p v-if="scene.entryState || scene.exitState" class="sw-line">
            <span class="sw-label">进出状态</span>场前：{{ scene.entryState || '—' }} → 场后：{{ scene.exitState || '—' }}
          </p>

          <table class="sw-shots">
            <thead>
              <tr><th></th><th>镜号</th><th>叙事目的</th><th>动作/表演</th><th>运镜</th><th>状态</th><th>候选</th><th></th></tr>
            </thead>
            <tbody>
              <tr v-for="shot in shotsOf(scene.id)" :key="shot.id" class="sw-shot-row">
                <td>
                  <input v-if="['DRAFT','READY'].includes(shot.status) && shot.promptText"
                         type="checkbox" :checked="selectedShots.has(shot.id)"
                         @change="toggleSelect(shot.id)" />
                </td>
                <td>{{ shot.shotNumber }}</td>
                <td class="sw-td-text">{{ shot.purpose || '—' }}</td>
                <td class="sw-td-text">{{ shot.action || '—' }}</td>
                <td class="sw-td-text">{{ shot.camera || '—' }}</td>
                <td><span class="sw-chip" :class="'shot-' + shot.status">{{ shotStatusLabel(shot.status) }}</span></td>
                <td>{{ candidateCounts[shot.id] || 0 }}</td>
                <td>
                  <button type="button" class="cr-ghost" @click="openShot(shot)">详情 / 生成 / 选片</button>
                </td>
              </tr>
            </tbody>
          </table>
          <button type="button" class="cr-ghost sw-add-shot" @click="openShotCreate(scene)">+ 加镜头</button>
        </article>
        <button type="button" class="cr-secondary sw-add-scene" @click="openSceneCreate(episode)">+ 新场次</button>
      </div>
    </section>

    <!-- 剧情/分镜优化结果：原文对比 + 采纳写回（分镜草稿为建议文本，复制后人工分镜） -->
    <div v-if="optimizeResult" class="cr-dialog-backdrop" @click.self="optimizeResult = null">
      <div class="cr-dialog" role="dialog" aria-modal="true">
        <h3>{{ optimizeMode === 'VIDEO_STORY' ? '剧情优化建议' : '分镜细化草稿' }}</h3>
        <div class="sd-diff" style="display:grid;grid-template-columns:1fr 1fr;gap:10px;">
          <div><span class="sw-label">原文</span><p style="white-space:pre-wrap;font-size:12.5px;">{{ optimizeOriginal }}</p></div>
          <div><span class="sw-label">建议稿（可编辑）</span>
            <textarea v-model="optimizeResult.optimizedPrompt" class="cr-textarea" rows="8"></textarea></div>
        </div>
        <ul v-if="optimizeResult.changes?.length" class="sw-opt-list"><li v-for="item in optimizeResult.changes" :key="item">改：{{ item }}</li></ul>
        <ul v-if="optimizeResult.suggestions?.length" class="sw-opt-list sw-sugg"><li v-for="item in optimizeResult.suggestions" :key="item">议：{{ item }}</li></ul>
        <ul v-if="optimizeResult.missing?.length" class="sw-opt-list sw-miss"><li v-for="item in optimizeResult.missing" :key="item">缺：{{ item }}</li></ul>
        <div class="sw-dialog-actions">
          <button v-if="optimizeMode === 'VIDEO_STORYBOARD'" type="button" class="cr-ghost"
                  @click="copyOptimized">复制草稿</button>
          <button type="button" class="cr-ghost" @click="optimizeResult = null">放弃</button>
          <button v-if="optimizeMode === 'VIDEO_STORY'" type="button" class="cr-primary"
                  @click="adoptStory">采纳到剧本</button>
        </div>
      </div>
    </div>

    <!-- 剧集弹窗 -->
    <div v-if="episodeDialog" class="cr-dialog-backdrop" @click.self="episodeDialog = null">
      <div class="cr-dialog" role="dialog" aria-modal="true">
        <h3 :id="'episode-dialog-title'">{{ episodeDialog.mode === 'create' ? '新剧集' : '编辑剧集' }}</h3>
        <label class="cr-field"><span class="cr-field-label"><span>标题 *</span></span>
          <input v-model="episodeForm.title" class="cr-input" maxlength="100" /></label>
        <label class="cr-field"><span class="cr-field-label"><span>本集目标</span></span>
          <textarea v-model="episodeForm.goal" class="cr-textarea" rows="2"></textarea></label>
        <label class="cr-field"><span class="cr-field-label"><span>梗概</span></span>
          <textarea v-model="episodeForm.synopsis" class="cr-textarea" rows="3"></textarea></label>
        <label class="cr-field"><span class="cr-field-label"><span>剧本</span></span>
          <textarea v-model="episodeForm.script" class="cr-textarea" rows="6"></textarea></label>
        <div class="sw-dialog-actions">
          <button type="button" class="cr-ghost" @click="episodeDialog = null">取消</button>
          <button type="button" class="cr-primary" :disabled="busy" @click="submitEpisode">保存</button>
        </div>
      </div>
    </div>

    <!-- 场次弹窗 -->
    <div v-if="sceneDialog" class="cr-dialog-backdrop" @click.self="sceneDialog = null">
      <div class="cr-dialog" role="dialog" aria-modal="true">
        <h3>{{ sceneDialog.mode === 'create' ? '新场次' : '编辑场次' }}</h3>
        <label class="cr-field"><span class="cr-field-label"><span>标题</span></span>
          <input v-model="sceneForm.title" class="cr-input" maxlength="100" /></label>
        <div class="sw-form-row">
          <label class="cr-field"><span class="cr-field-label"><span>地点</span></span>
            <input v-model="sceneForm.locationText" class="cr-input" maxlength="100" /></label>
          <label class="cr-field"><span class="cr-field-label"><span>时间/天气</span></span>
            <input v-model="sceneForm.timeOfDay" class="cr-input" maxlength="32" /></label>
        </div>
        <label class="cr-field"><span class="cr-field-label"><span>出场人物</span></span>
          <textarea v-model="sceneForm.charactersText" class="cr-textarea" rows="2"></textarea></label>
        <label class="cr-field"><span class="cr-field-label"><span>场次目标</span></span>
          <textarea v-model="sceneForm.goal" class="cr-textarea" rows="2"></textarea></label>
        <label class="cr-field"><span class="cr-field-label"><span>冲突</span></span>
          <textarea v-model="sceneForm.conflict" class="cr-textarea" rows="2"></textarea></label>
        <div class="sw-form-row">
          <label class="cr-field"><span class="cr-field-label"><span>场前状态</span></span>
            <textarea v-model="sceneForm.entryState" class="cr-textarea" rows="2"></textarea></label>
          <label class="cr-field"><span class="cr-field-label"><span>场后状态</span></span>
            <textarea v-model="sceneForm.exitState" class="cr-textarea" rows="2"></textarea></label>
        </div>
        <div class="sw-dialog-actions">
          <button type="button" class="cr-ghost" @click="sceneDialog = null">取消</button>
          <button type="button" class="cr-primary" :disabled="busy" @click="submitScene">保存</button>
        </div>
      </div>
    </div>

    <!-- 批次进度（P3）：分计数显示，停止=停止未提交项，重试仅针对失败项 -->
    <section v-if="batches.length" class="sw-batches">
      <h4>生产批次</h4>
      <div v-for="view in batchViews" :key="view.batch.id" class="sw-batch">
        <header class="sw-batch-head">
          <strong>{{ view.batch.scopeLabel || '批次' }}</strong>
          <span class="sw-chip" :class="'batch-' + view.batch.status">{{ batchStatusLabel(view.batch.status) }}</span>
          <span class="cr-field-help">
            共 {{ view.progress.total }} · 待提交 {{ view.progress.pending }} · 在途 {{ view.progress.inflight }}
            · 成功 {{ view.progress.done }} · 失败 {{ view.progress.failed }} · 已停止 {{ view.progress.skipped }}
            （完成 {{ view.progress.donePercent }}%）
          </span>
          <span class="sw-scene-actions">
            <button v-if="['RUNNING','STOPPING'].includes(view.batch.status)" type="button" class="cr-ghost"
                    @click="stopBatch(view.batch)">停止</button>
            <button v-if="view.progress.failed > 0" type="button" class="cr-ghost"
                    @click="retryBatch(view.batch)">重试失败项（{{ view.progress.failed }}）</button>
          </span>
        </header>
        <div class="sw-batch-items">
          <span v-for="item in view.items" :key="item.item.id" class="sw-chip"
                :class="'item-' + item.item.status">
            {{ item.shotLabel }}·{{ itemStatusLabel(item.item.status) }}
          </span>
        </div>
      </div>
    </section>

    <ShotDrawer v-if="drawerShot" :project-id="projectId" :shot="drawerShot"
                :characters="characters" :locations="locations"
                @close="drawerShot = null" @changed="reload" />
    <StorySettingsPanel v-if="settingsOpen" :project-id="projectId"
                        @close="settingsOpen = false" />
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { Plus, RefreshCw, Settings as SettingsIcon } from 'lucide-vue-next'
import {
  createStoryBatch, createStoryEpisode, createStoryScene, createStoryShot, confirmStoryEpisode,
  confirmStoryScene, deleteStoryEpisode, deleteStoryScene, getStoryBatch, getStoryWorkspace,
  listStoryBatches, moveStoryEpisode, optimizeCreativePrompt, retryStoryBatch, stopStoryBatch,
  updateStoryEpisode, updateStoryScene
} from '../../../api'
import HoldToConfirm from '../../../components/ui/HoldToConfirm.vue'
import ShotDrawer from './ShotDrawer.vue'
import StorySettingsPanel from './StorySettingsPanel.vue'

const props = defineProps({
  projectId: { type: String, required: true }
})
defineEmits(['open-settings'])

const episodes = ref([])
const scenes = ref([])
const shots = ref([])
const candidateCounts = ref({})
const characters = ref([])
const locations = ref([])
const loading = ref(false)
const error = ref('')
const busy = ref(false)
const drawerShot = ref(null)
const settingsOpen = ref(false)
const episodeDialog = ref(null)
const sceneDialog = ref(null)
const selectedShots = reactive(new Set())
const batches = ref([])
const batchViews = ref([])
const optimizing = ref(false)
const optimizeMode = ref('')
const optimizeResult = ref(null)
const optimizeOriginal = ref('')
const optimizeTarget = ref(null)
let pollTimer = null

const episodeForm = reactive({ mode: 'create', id: null, title: '', goal: '', synopsis: '', script: '' })
const sceneForm = reactive({
  mode: 'create', episodeId: null, id: null, title: '', locationText: '', timeOfDay: '',
  charactersText: '', goal: '', conflict: '', entryState: '', exitState: ''
})

const scenesOf = episodeId => scenes.value.filter(scene => scene.episodeId === episodeId)
const shotsOf = sceneId => shots.value.filter(shot => shot.sceneId === sceneId)

const SHOT_STATUS = {
  DRAFT: '草稿', READY: '可生成', GENERATING: '生成中',
  PENDING_SELECTION: '待选片', SELECTED: '已选定'
}
const shotStatusLabel = status => SHOT_STATUS[status] || status

async function reload() {
  loading.value = true
  error.value = ''
  try {
    const data = await getStoryWorkspace(props.projectId)
    episodes.value = data.episodes || []
    scenes.value = data.scenes || []
    shots.value = data.shots || []
    candidateCounts.value = data.candidateCounts || {}
    characters.value = data.characters || []
    locations.value = data.locations || []
  } catch (exception) {
    error.value = exception.response?.data?.error || `剧情加载失败：${exception.message || exception}`
  } finally {
    loading.value = false
  }
  loadBatches()
  schedulePoll()
}

function schedulePoll() {
  window.clearTimeout(pollTimer)
  const active = shots.value.some(shot => shot.status === 'GENERATING')
    || batchViews.value.some(view => ['RUNNING', 'STOPPING'].includes(view.batch.status))
  if (!active) return
  pollTimer = window.setTimeout(async () => {
    if (document.visibilityState !== 'hidden' && !drawerShot.value) await reload()
    else { await loadBatches(); schedulePoll() }
  }, 3000)
}

function openEpisodeCreate() {
  Object.assign(episodeForm, { mode: 'create', id: null, title: '', goal: '', synopsis: '', script: '' })
  episodeDialog.value = { mode: 'create' }
}
function openEpisodeEdit(episode) {
  Object.assign(episodeForm, {
    mode: 'edit', id: episode.id, title: episode.title, goal: episode.goal,
    synopsis: episode.synopsis, script: episode.script
  })
  episodeDialog.value = { mode: 'edit' }
}
async function submitEpisode() {
  busy.value = true
  try {
    const payload = { title: episodeForm.title, goal: episodeForm.goal, synopsis: episodeForm.synopsis, script: episodeForm.script }
    if (episodeForm.mode === 'create') await createStoryEpisode(props.projectId, payload)
    else await updateStoryEpisode(props.projectId, episodeForm.id, payload)
    episodeDialog.value = null
    await reload()
  } catch (exception) {
    error.value = exception.response?.data?.error || '保存失败'
  } finally {
    busy.value = false
  }
}
async function confirmEpisode(episode) {
  try {
    await confirmStoryEpisode(props.projectId, episode.id)
    await reload()
  } catch (exception) {
    error.value = exception.response?.data?.error || '确认失败'
  }
}
async function move(episode, direction) {
  try {
    episodes.value = await moveStoryEpisode(props.projectId, episode.id, direction)
  } catch (exception) {
    error.value = exception.response?.data?.error || '排序失败'
  }
}
async function removeEpisode(episode) {
  try {
    await deleteStoryEpisode(props.projectId, episode.id)
    await reload()
  } catch (exception) {
    error.value = exception.response?.data?.error || '删除失败（已确认剧集不可删除）'
  }
}

function openSceneCreate(episode) {
  Object.assign(sceneForm, {
    mode: 'create', episodeId: episode.id, id: null, title: '', locationText: '', timeOfDay: '',
    charactersText: '', goal: '', conflict: '', entryState: '', exitState: ''
  })
  sceneDialog.value = { mode: 'create' }
}
function openSceneEdit(scene) {
  Object.assign(sceneForm, {
    mode: 'edit', episodeId: scene.episodeId, id: scene.id, title: scene.title,
    locationText: scene.locationText, timeOfDay: scene.timeOfDay, charactersText: scene.charactersText,
    goal: scene.goal, conflict: scene.conflict, entryState: scene.entryState, exitState: scene.exitState
  })
  sceneDialog.value = { mode: 'edit' }
}
async function submitScene() {
  busy.value = true
  try {
    const payload = {
      title: sceneForm.title, locationText: sceneForm.locationText, timeOfDay: sceneForm.timeOfDay,
      charactersText: sceneForm.charactersText, goal: sceneForm.goal, conflict: sceneForm.conflict,
      entryState: sceneForm.entryState, exitState: sceneForm.exitState
    }
    if (sceneForm.mode === 'create') await createStoryScene(props.projectId, sceneForm.episodeId, payload)
    else await updateStoryScene(props.projectId, sceneForm.id, payload)
    sceneDialog.value = null
    await reload()
  } catch (exception) {
    error.value = exception.response?.data?.error || '保存失败'
  } finally {
    busy.value = false
  }
}
async function confirmScene(scene) {
  try {
    await confirmStoryScene(props.projectId, scene.id)
    await reload()
  } catch (exception) {
    error.value = exception.response?.data?.error || '确认失败'
  }
}
async function removeScene(scene) {
  try {
    await deleteStoryScene(props.projectId, scene.id)
    await reload()
  } catch (exception) {
    error.value = exception.response?.data?.error || '删除失败'
  }
}

function toggleSelect(shotId) {
  if (selectedShots.has(shotId)) selectedShots.delete(shotId)
  else selectedShots.add(shotId)
}

async function createBatch() {
  const episode = episodes.value.find(item => shotsOf(item.id).some(shot => selectedShots.has(shot.id)))
  busy.value = true
  error.value = ''
  try {
    await createStoryBatch(props.projectId, {
      shotIds: [...selectedShots],
      scopeLabel: episode ? '第 ' + episode.episodeNumber + ' 集《' + episode.title + '》' : ''
    })
    selectedShots.clear()
    await loadBatches()
  } catch (exception) {
    error.value = exception.response?.data?.error || '批次创建失败'
  } finally {
    busy.value = false
  }
}

async function loadBatches() {
  try {
    batches.value = await listStoryBatches(props.projectId)
    const views = await Promise.allSettled(
      batches.value.map(batch => getStoryBatch(props.projectId, batch.id)))
    batchViews.value = views.map((result, index) =>
      result.status === 'fulfilled' ? result.value : { batch: batches.value[index], items: [], progress: null })
      .filter(view => view.progress)
  } catch (exception) { /* 批次面板加载失败不阻断主区 */ }
}

async function stopBatch(batch) {
  try { await stopStoryBatch(props.projectId, batch.id); await loadBatches() } catch (exception) {
    error.value = exception.response?.data?.error || '停止失败'
  }
}
async function retryBatch(batch) {
  try { await retryStoryBatch(props.projectId, batch.id); await loadBatches() } catch (exception) {
    error.value = exception.response?.data?.error || '重试失败'
  }
}

const BATCH_STATUS = { RUNNING: '进行中', STOPPING: '停止中', STOPPED: '已停止', COMPLETED: '已完成' }
const ITEM_STATUS = { PENDING_SUBMIT: '待提交', SUBMITTED: '在途', DONE: '成功', FAILED: '失败', SKIPPED: '已停止' }
const batchStatusLabel = status => BATCH_STATUS[status] || status
const itemStatusLabel = status => ITEM_STATUS[status] || status

async function openShotCreate(scene) {
  try {
    const shot = await createStoryShot(props.projectId, scene.id, {})
    await reload()
    drawerShot.value = shots.value.find(item => item.id === shot.id) || shot
  } catch (exception) {
    error.value = exception.response?.data?.error || '创建镜头失败'
  }
}
function openShot(shot) {
  drawerShot.value = shot
}

// 优化剧情（VIDEO_STORY，采纳写回本集剧本）与细化分镜（VIDEO_STORYBOARD，草稿供人工分镜）
async function optimizeStory(episode) {
  optimizing.value = true
  optimizeMode.value = 'VIDEO_STORY'
  optimizeTarget.value = episode
  optimizeOriginal.value = episode.script || episode.synopsis || ''
  optimizeResult.value = null
  try {
    optimizeResult.value = await optimizeCreativePrompt({
      prompt: optimizeOriginal.value || episode.goal,
      mode: 'VIDEO_STORY', projectId: props.projectId, episodeId: episode.id
    })
  } catch (exception) {
    error.value = exception.response?.data?.error || '剧情优化失败'
  } finally {
    optimizing.value = false
  }
}
async function optimizeStoryboard(scene) {
  optimizing.value = true
  optimizeMode.value = 'VIDEO_STORYBOARD'
  optimizeTarget.value = scene
  optimizeOriginal.value = [scene.goal, scene.conflict].filter(Boolean).join('\n冲突：')
  optimizeResult.value = null
  try {
    optimizeResult.value = await optimizeCreativePrompt({
      prompt: optimizeOriginal.value,
      mode: 'VIDEO_STORYBOARD', projectId: props.projectId, sceneId: scene.id
    })
  } catch (exception) {
    error.value = exception.response?.data?.error || '分镜细化失败'
  } finally {
    optimizing.value = false
  }
}
async function adoptStory() {
  const episode = optimizeTarget.value
  try {
    await updateStoryEpisode(props.projectId, episode.id, {
      title: episode.title, goal: episode.goal, synopsis: episode.synopsis,
      script: optimizeResult.value.optimizedPrompt
    })
    optimizeResult.value = null
    await reload()
  } catch (exception) {
    error.value = exception.response?.data?.error || '写回失败'
  }
}
async function copyOptimized() {
  try { await navigator.clipboard.writeText(optimizeResult.value.optimizedPrompt) } catch (exception) { /* 剪贴板不可用时手动复制 */ }
}

onMounted(reload)
onBeforeUnmount(() => window.clearTimeout(pollTimer))
</script>

<style scoped src="../creative.css"></style>
<style scoped>
.sw-batches { border: 1px solid var(--wb-border); border-radius: var(--wb-radius); padding: 10px 14px; background: var(--wb-surface); }
.sw-batches h4 { margin: 0 0 8px; font-size: 12.5px; color: var(--wb-muted); }
.sw-batch { padding: 6px 0; border-bottom: 1px solid var(--wb-border); }
.sw-batch:last-child { border-bottom: 0; }
.sw-batch-head { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.sw-batch-items { display: flex; gap: 6px; flex-wrap: wrap; margin-top: 6px; }
.item-DONE { color: #15803d; }
.item-FAILED { color: #b45309; }
.item-SUBMITTED { color: #2563eb; }
.batch-COMPLETED { color: #15803d; }
.batch-STOPPED { color: var(--wb-muted); }
.sw-opt-list { margin: 6px 0 0; padding-left: 16px; font-size: 12px; color: var(--wb-text-2); }
.sw-sugg { color: #2563eb; }
.sw-miss { color: #b45309; }
.story-workspace { display: flex; flex-direction: column; gap: 14px; }
.sw-head { display: flex; align-items: center; justify-content: space-between; gap: 12px; flex-wrap: wrap; }
.sw-head-title strong { font-size: 14px; }
.sw-head-actions { display: flex; gap: 8px; }
.sw-episode { border: 1px solid var(--wb-border); border-radius: var(--wb-radius); padding: 12px 14px; background: var(--wb-surface); }
.sw-episode-head { display: flex; align-items: center; justify-content: space-between; gap: 10px; flex-wrap: wrap; }
.sw-episode-title { display: flex; align-items: center; gap: 8px; }
.sw-episode-name { font-weight: 600; }
.sw-episode-actions { display: flex; gap: 6px; align-items: center; }
.sw-episode-body { margin: 8px 0 4px; }
.sw-line { margin: 3px 0; font-size: 12.5px; color: var(--wb-text-2); }
.sw-label { display: inline-block; min-width: 64px; color: var(--wb-muted); }
.sw-script { white-space: pre-wrap; }
.sw-scenes { margin-top: 10px; display: flex; flex-direction: column; gap: 10px; }
.sw-scene { border: 1px solid var(--wb-border); border-radius: 10px; padding: 10px 12px; }
.sw-scene-head { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.sw-scene-actions { margin-left: auto; display: flex; gap: 6px; }
.sw-chip { padding: 1px 8px; border-radius: 999px; background: var(--wb-fill); font-size: 11px; color: var(--wb-text-2); }
.shot-GENERATING { color: #2563eb; }
.shot-PENDING_SELECTION { color: #b45309; }
.shot-SELECTED { color: #15803d; }
.sw-shots { width: 100%; margin-top: 8px; border-collapse: collapse; font-size: 12.5px; }
.sw-shots th, .sw-shots td { text-align: left; padding: 5px 8px; border-bottom: 1px solid var(--wb-border); vertical-align: top; }
.sw-td-text { max-width: 260px; }
.sw-add-shot, .sw-add-scene { margin-top: 8px; }
.sw-form-row { display: grid; grid-template-columns: 1fr 1fr; gap: 10px; }
.sw-dialog-actions { display: flex; justify-content: flex-end; gap: 8px; margin-top: 10px; }
.cr-dialog { max-height: 82vh; overflow: auto; }
</style>
