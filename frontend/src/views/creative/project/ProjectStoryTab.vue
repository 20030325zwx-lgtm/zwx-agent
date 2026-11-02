<template>
  <div class="tab-scroll">
    <div class="tab-inner">
      <header class="st-head">
        <div>
          <h2 class="st-title">剧情</h2>
          <p class="cr-field-help">
            剧集 → 场次：先把本集目标、冲突和进出状态写清楚，再到场次里拆镜头。镜头生成与选片在「分镜」页完成。
          </p>
        </div>
        <div class="st-head-actions">
          <router-link :to="`/creative/projects/${projectId}/settings`" class="cr-ghost">
            <Users :size="14" /> 设定与连续性
          </router-link>
          <button type="button" class="cr-ghost" :disabled="story.loading.value" @click="story.reload()">
            <RefreshCw :size="14" /> 刷新
          </button>
          <button type="button" class="cr-primary" @click="openEpisodeCreate">
            <Plus :size="14" /> 新剧集
          </button>
        </div>
      </header>

      <p v-if="story.error.value" class="cr-error">{{ story.error.value }}</p>
      <p v-if="!story.loading.value && !episodes.length" class="cr-empty">
        还没有剧集。从「新剧集」开始：先写本集目标与梗概，再拆场次和分镜。
      </p>

      <section v-for="episode in episodes" :key="episode.id" class="ep-card">
        <header class="ep-head">
          <div class="ep-title">
            <strong>第 {{ episode.episodeNumber }} 集</strong>
            <span class="ep-name">{{ episode.title }}</span>
            <span class="cr-status" :class="episode.status === 'CONFIRMED' ? 'success' : 'muted'">
              {{ episode.status === 'CONFIRMED' ? '已确认' : '草稿' }}
            </span>
          </div>
          <div class="ep-actions">
            <button type="button" class="cr-ghost" @click="openEpisodeEdit(episode)">编辑</button>
            <button type="button" class="cr-ghost" :disabled="optimizing"
                    @click="optimizeStory(episode)">
              {{ optimizing === 'VIDEO_STORY' ? '优化中…' : '优化剧情' }}
            </button>
            <button v-if="episode.status !== 'CONFIRMED'" type="button" class="cr-ghost"
                    @click="confirmEpisode(episode)">确认</button>
            <button type="button" class="cr-ghost" :disabled="episode.episodeNumber <= 1"
                    @click="move(episode, -1)">↑</button>
            <button type="button" class="cr-ghost" :disabled="episode.episodeNumber >= episodes.length"
                    @click="move(episode, 1)">↓</button>
            <HoldToConfirm label="删除" @confirm="removeEpisode(episode)" />
          </div>
        </header>

        <div v-if="episode.goal || episode.synopsis || episode.script" class="ep-body">
          <p v-if="episode.goal" class="line"><span class="label">本集目标</span>{{ episode.goal }}</p>
          <p v-if="episode.synopsis" class="line"><span class="label">梗概</span>{{ episode.synopsis }}</p>
          <p v-if="episode.script" class="line"><span class="label">剧本</span><span class="script">{{ episode.script }}</span></p>
        </div>

        <div class="scenes">
          <article v-for="scene in scenesOf(episode.id)" :key="scene.id" class="scene">
            <header class="scene-head">
              <strong>第 {{ scene.sceneNumber }} 场</strong>
              <span class="scene-title">{{ scene.title }}</span>
              <span v-if="scene.locationText" class="chip">{{ scene.locationText }}</span>
              <span v-if="scene.timeOfDay" class="chip">{{ scene.timeOfDay }}</span>
              <span class="cr-status" :class="scene.status === 'CONFIRMED' ? 'success' : 'muted'">
                {{ scene.status === 'CONFIRMED' ? '已确认' : '草稿' }}
              </span>
              <span class="scene-actions">
                <router-link class="cr-ghost"
                             :to="`/creative/projects/${projectId}/storyboard?scene=${scene.id}`">
                  {{ shotsOf(scene.id).length }} 镜头 · 去分镜
                </router-link>
                <button type="button" class="cr-ghost" @click="openSceneEdit(scene)">编辑</button>
                <button type="button" class="cr-ghost" :disabled="optimizing"
                        @click="optimizeStoryboard(scene)">
                  {{ optimizing === 'VIDEO_STORYBOARD' ? '拆镜中…' : '细化分镜' }}
                </button>
                <button v-if="scene.status !== 'CONFIRMED'" type="button" class="cr-ghost"
                        @click="confirmScene(scene)">确认</button>
                <HoldToConfirm label="删除" @confirm="removeScene(scene)" />
              </span>
            </header>
            <p v-if="scene.goal" class="line"><span class="label">目标</span>{{ scene.goal }}</p>
            <p v-if="scene.conflict" class="line"><span class="label">冲突</span>{{ scene.conflict }}</p>
            <p v-if="scene.entryState || scene.exitState" class="line">
              <span class="label">进出状态</span>场前：{{ scene.entryState || '—' }} → 场后：{{ scene.exitState || '—' }}
            </p>
            <div v-if="shotsOf(scene.id).length" class="scene-shots">
              <span v-for="shot in shotsOf(scene.id)" :key="shot.id" class="chip" :class="'shot-' + shot.status">
                {{ shot.shotNumber }}·{{ SHOT_STATUS_LABEL[shot.status] || shot.status }}
              </span>
            </div>
          </article>
          <button type="button" class="cr-secondary scene-add" @click="openSceneCreate(episode)">+ 新场次</button>
        </div>
      </section>
    </div>

    <!-- 剧情/分镜优化结果：原文对比 + 采纳写回（分镜草稿为建议文本，复制后人工拆镜） -->
    <div v-if="optimizeResult" class="cr-dialog-backdrop" @click.self="optimizeResult = null">
      <div class="cr-dialog" role="dialog" aria-modal="true">
        <h3>{{ optimizeMode === 'VIDEO_STORY' ? '剧情优化建议' : '分镜细化草稿' }}</h3>
        <div class="diff">
          <div><span class="label">原文</span><p class="diff-text">{{ optimizeOriginal }}</p></div>
          <div><span class="label">建议稿（可编辑）</span>
            <textarea v-model="optimizeResult.optimizedPrompt" class="cr-textarea" rows="8"></textarea></div>
        </div>
        <ul v-if="optimizeResult.changes?.length" class="opt-list"><li v-for="item in optimizeResult.changes" :key="item">改：{{ item }}</li></ul>
        <ul v-if="optimizeResult.suggestions?.length" class="opt-list sugg"><li v-for="item in optimizeResult.suggestions" :key="item">议：{{ item }}</li></ul>
        <ul v-if="optimizeResult.missing?.length" class="opt-list miss"><li v-for="item in optimizeResult.missing" :key="item">缺：{{ item }}</li></ul>
        <div class="dialog-actions">
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
        <h3>{{ episodeDialog.mode === 'create' ? '新剧集' : '编辑剧集' }}</h3>
        <label class="cr-field"><span class="cr-field-label"><span>标题 *</span></span>
          <input v-model="episodeForm.title" class="cr-input" maxlength="100" /></label>
        <label class="cr-field"><span class="cr-field-label"><span>本集目标</span></span>
          <textarea v-model="episodeForm.goal" class="cr-textarea" rows="2"></textarea></label>
        <label class="cr-field"><span class="cr-field-label"><span>梗概</span></span>
          <textarea v-model="episodeForm.synopsis" class="cr-textarea" rows="3"></textarea></label>
        <label class="cr-field"><span class="cr-field-label"><span>剧本</span></span>
          <textarea v-model="episodeForm.script" class="cr-textarea" rows="6"></textarea></label>
        <div class="dialog-actions">
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
        <div class="form-row">
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
        <div class="form-row">
          <label class="cr-field"><span class="cr-field-label"><span>场前状态</span></span>
            <textarea v-model="sceneForm.entryState" class="cr-textarea" rows="2"></textarea></label>
          <label class="cr-field"><span class="cr-field-label"><span>场后状态</span></span>
            <textarea v-model="sceneForm.exitState" class="cr-textarea" rows="2"></textarea></label>
        </div>
        <div class="dialog-actions">
          <button type="button" class="cr-ghost" @click="sceneDialog = null">取消</button>
          <button type="button" class="cr-primary" :disabled="busy" @click="submitScene">保存</button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, inject, onMounted, reactive, ref } from 'vue'
import { Plus, RefreshCw, Users } from 'lucide-vue-next'
import {
  confirmStoryEpisode, confirmStoryScene, createStoryEpisode, createStoryScene, deleteStoryEpisode,
  deleteStoryScene, moveStoryEpisode, optimizeCreativePrompt, updateStoryEpisode, updateStoryScene
} from '../../../api'
import { PROJECT_CTX } from './composables'
import { SHOT_STATUS_LABEL } from './helpers'
import HoldToConfirm from '../../../components/ui/HoldToConfirm.vue'

const { projectId, story } = inject(PROJECT_CTX)

const episodes = computed(() => story.episodes.value)
const loading = computed(() => story.loading.value)
const busy = ref(false)
const optimizing = ref('')
const optimizeMode = ref('')
const optimizeResult = ref(null)
const optimizeOriginal = ref('')
const optimizeTarget = ref(null)
const episodeDialog = ref(null)
const sceneDialog = ref(null)
const error = ref('')

const episodeForm = reactive({ mode: 'create', id: null, title: '', goal: '', synopsis: '', script: '' })
const sceneForm = reactive({
  mode: 'create', episodeId: null, id: null, title: '', locationText: '', timeOfDay: '',
  charactersText: '', goal: '', conflict: '', entryState: '', exitState: ''
})

const scenesOf = episodeId => story.scenes.value.filter(scene => scene.episodeId === episodeId)
const shotsOf = sceneId => story.shots.value.filter(shot => shot.sceneId === sceneId)

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
    const payload = {
      title: episodeForm.title, goal: episodeForm.goal,
      synopsis: episodeForm.synopsis, script: episodeForm.script
    }
    if (episodeForm.mode === 'create') await createStoryEpisode(projectId.value, payload)
    else await updateStoryEpisode(projectId.value, episodeForm.id, payload)
    episodeDialog.value = null
    await story.reload()
  } catch (exception) {
    error.value = exception.response?.data?.error || '保存失败'
  } finally {
    busy.value = false
  }
}

async function confirmEpisode(episode) {
  try {
    await confirmStoryEpisode(projectId.value, episode.id)
    await story.reload()
  } catch (exception) {
    error.value = exception.response?.data?.error || '确认失败'
  }
}

async function move(episode, direction) {
  try {
    await moveStoryEpisode(projectId.value, episode.id, direction)
    await story.reload()
  } catch (exception) {
    error.value = exception.response?.data?.error || '排序失败'
  }
}

async function removeEpisode(episode) {
  try {
    await deleteStoryEpisode(projectId.value, episode.id)
    await story.reload()
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
    if (sceneForm.mode === 'create') await createStoryScene(projectId.value, sceneForm.episodeId, payload)
    else await updateStoryScene(projectId.value, sceneForm.id, payload)
    sceneDialog.value = null
    await story.reload()
  } catch (exception) {
    error.value = exception.response?.data?.error || '保存失败'
  } finally {
    busy.value = false
  }
}

async function confirmScene(scene) {
  try {
    await confirmStoryScene(projectId.value, scene.id)
    await story.reload()
  } catch (exception) {
    error.value = exception.response?.data?.error || '确认失败'
  }
}

async function removeScene(scene) {
  try {
    await deleteStoryScene(projectId.value, scene.id)
    await story.reload()
  } catch (exception) {
    error.value = exception.response?.data?.error || '删除失败'
  }
}

// 优化剧情（采纳写回本集剧本）与细化分镜（草稿供人工拆镜）
async function optimizeStory(episode) {
  optimizing.value = 'VIDEO_STORY'
  optimizeMode.value = 'VIDEO_STORY'
  optimizeTarget.value = episode
  optimizeOriginal.value = episode.script || episode.synopsis || ''
  optimizeResult.value = null
  try {
    optimizeResult.value = await optimizeCreativePrompt({
      prompt: optimizeOriginal.value || episode.goal,
      mode: 'VIDEO_STORY', projectId: projectId.value, episodeId: episode.id
    })
  } catch (exception) {
    error.value = exception.response?.data?.error || '剧情优化失败'
  } finally {
    optimizing.value = ''
  }
}

async function optimizeStoryboard(scene) {
  optimizing.value = 'VIDEO_STORYBOARD'
  optimizeMode.value = 'VIDEO_STORYBOARD'
  optimizeTarget.value = scene
  optimizeOriginal.value = [scene.goal, scene.conflict].filter(Boolean).join('\n冲突：')
  optimizeResult.value = null
  try {
    optimizeResult.value = await optimizeCreativePrompt({
      prompt: optimizeOriginal.value,
      mode: 'VIDEO_STORYBOARD', projectId: projectId.value, sceneId: scene.id
    })
  } catch (exception) {
    error.value = exception.response?.data?.error || '分镜细化失败'
  } finally {
    optimizing.value = ''
  }
}

async function adoptStory() {
  const episode = optimizeTarget.value
  try {
    await updateStoryEpisode(projectId.value, episode.id, {
      title: episode.title, goal: episode.goal, synopsis: episode.synopsis,
      script: optimizeResult.value.optimizedPrompt
    })
    optimizeResult.value = null
    await story.reload()
  } catch (exception) {
    error.value = exception.response?.data?.error || '写回失败'
  }
}

async function copyOptimized() {
  try {
    await navigator.clipboard.writeText(optimizeResult.value.optimizedPrompt)
  } catch (exception) { /* 剪贴板不可用时手动复制 */ }
}

onMounted(() => {
  if (!story.workspace.value) story.reload()
})
</script>

<style scoped src="../creative.css"></style>

<style scoped>
.st-head { display: flex; align-items: flex-start; justify-content: space-between; gap: var(--wb-space-3); flex-wrap: wrap; }
.st-title { margin: 0; font-size: var(--wb-fs-section); line-height: var(--wb-lh-section); font-weight: 600; }
.st-head-actions { display: flex; gap: var(--wb-space-2); align-items: center; flex-wrap: wrap; }

.ep-card {
  border: 1px solid var(--wb-border);
  border-radius: var(--wb-radius);
  background: var(--wb-surface);
  padding: var(--wb-space-3) var(--wb-space-4);
}
.ep-head { display: flex; align-items: center; justify-content: space-between; gap: 10px; flex-wrap: wrap; }
.ep-title { display: flex; align-items: center; gap: var(--wb-space-2); }
.ep-name { font-weight: 600; }
.ep-actions { display: flex; gap: 6px; align-items: center; flex-wrap: wrap; }
.ep-body { margin: 8px 0 4px; }
.line { margin: 3px 0; font-size: var(--wb-fs-compact); color: var(--wb-text-2); }
.label { display: inline-block; min-width: 64px; color: var(--wb-muted); }
.script { white-space: pre-wrap; }

.scenes { margin-top: var(--wb-space-3); display: flex; flex-direction: column; gap: var(--wb-space-3); }
.scene { border: 1px solid var(--wb-border); border-radius: var(--wb-radius-sm); padding: 10px 12px; }
.scene-head { display: flex; align-items: center; gap: var(--wb-space-2); flex-wrap: wrap; }
.scene-title { color: var(--wb-text); }
.scene-actions { margin-left: auto; display: flex; gap: 6px; align-items: center; flex-wrap: wrap; }
.scene-shots { display: flex; gap: 6px; flex-wrap: wrap; margin-top: 8px; }
.scene-add { align-self: flex-start; }

.chip {
  padding: 1px 8px;
  border-radius: 999px;
  background: var(--wb-fill);
  font-size: var(--wb-fs-caption);
  color: var(--wb-text-2);
}
.shot-GENERATING { color: var(--wb-info); }
.shot-PENDING_SELECTION { color: var(--wb-warning); }
.shot-SELECTED { color: var(--wb-success); }

.diff { display: grid; grid-template-columns: 1fr 1fr; gap: 10px; }
.diff-text { white-space: pre-wrap; font-size: var(--wb-fs-compact); margin: 4px 0 0; }
.opt-list { margin: 6px 0 0; padding-left: 16px; font-size: var(--wb-fs-caption); color: var(--wb-text-2); }
.sugg { color: var(--wb-info); }
.miss { color: var(--wb-warning); }
.dialog-actions { display: flex; justify-content: flex-end; gap: 8px; margin-top: 10px; }
.form-row { display: grid; grid-template-columns: 1fr 1fr; gap: 10px; }

@media (max-width: 900px) {
  .diff { grid-template-columns: 1fr; }
  .form-row { grid-template-columns: 1fr; }
}
</style>
