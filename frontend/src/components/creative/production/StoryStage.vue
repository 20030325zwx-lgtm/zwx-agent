<template>
  <section class="story-stage">
    <div class="editor-card">
      <header class="editor-head">
        <div>
          <h2>故事</h2>
          <p class="editor-sub">描述故事、人物、关键动作和结尾，也可以直接粘贴剧本。正文不受单镜头提示词长度限制。</p>
        </div>
        <span class="save-pill" :class="saveState">{{ saveLabel }}</span>
      </header>

      <textarea v-model="script" class="story-editor" rows="12"
                placeholder="写下故事，或粘贴完整剧本……" @input="markDirty" />

      <div class="editor-actions">
        <button type="button" class="cr-secondary" :disabled="optimizing || !script.trim()"
                @click="optimizeStory">
          <span v-if="optimizing">整理中…</span><span v-else>整理故事</span>
        </button>
        <button type="button" class="cr-primary" :disabled="drafting || !script.trim() || !episodeId"
                :title="drafting ? '正在生成分镜草稿，通常需要十几秒' : '按当前故事生成分镜草稿，确认前不改动已有镜头'"
                @click="generateDraft">
          <span v-if="drafting">拆镜生成中…</span><span v-else>生成分镜草稿</span>
        </button>
        <span class="action-hint">整理与拆镜都会调用文本模型，可能产生费用。</span>
      </div>
      <p v-if="stageError" class="cr-error">{{ stageError }}</p>
    </div>

    <details class="series-fold" @toggle="seriesOpen = $event.target.open">
      <summary>系列与场次（多集 / 多场管理）</summary>
      <ProjectStoryTab v-if="seriesOpen" />
    </details>

    <!-- 整理故事：建议稿与原文对比，采纳/放弃不覆盖原文 -->
    <div v-if="storySuggestion" class="cr-dialog-backdrop" @click.self="storySuggestion = null">
      <div class="cr-dialog suggest-dialog" role="dialog" aria-modal="true" aria-label="整理故事建议">
        <h3>整理建议</h3>
        <p v-if="suggestionMeta.changes?.length" class="suggest-meta">改动：{{ suggestionMeta.changes.join('；') }}</p>
        <div class="suggest-body">
          <div class="suggest-col">
            <h4>原文</h4>
            <p>{{ script || '（空）' }}</p>
          </div>
          <div class="suggest-col">
            <h4>建议稿（可编辑）</h4>
            <textarea v-model="storySuggestion" rows="10" />
          </div>
        </div>
        <p v-if="suggestionMeta.missing?.length" class="suggest-meta">待补：{{ suggestionMeta.missing.join('；') }}</p>
        <div class="cr-dialog-actions">
          <button type="button" class="cr-ghost" @click="storySuggestion = null">放弃</button>
          <button type="button" class="cr-primary" @click="adoptStory">采用到剧本</button>
        </div>
      </div>
    </div>

    <DraftReviewDialog v-if="draft" :draft="draft" :target-seconds="targetSeconds"
                       @close="draft = null" @discard="onDiscard" @adopted="onAdopted" />
  </section>
</template>

<script setup>
import { computed, inject, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { PROJECT_CTX } from '../../../views/creative/project/composables'
import ProjectStoryTab from '../../../views/creative/project/ProjectStoryTab.vue'
import DraftReviewDialog from './DraftReviewDialog.vue'
import {
  createStoryboardDraft, discardStoryboardDraft, ensureStoryShortfilm, getProjectSettings,
  optimizeCreativePrompt, updateStoryEpisode
} from '../../../api'

/**
 * P0 故事阶段：单编辑器 + 自动保存 + 整理故事 + 结构化拆镜入口。
 * 短片模式自动建立默认剧集/场次（后端幂等）；系列结构按需展开，不强制首次使用。
 */
const ctx = inject(PROJECT_CTX)
const projectId = ctx.projectId
const story = ctx.story

const episodeId = computed(() => story.episodes.value[0]?.id || null)
const script = ref('')
const saveState = ref('saved')
const saveTimer = ref(0)
const saving = ref(false)
const saveDirty = ref(false)
const stageError = ref('')
const optimizing = ref(false)
const storySuggestion = ref('')
const suggestionMeta = reactive({ changes: [], missing: [] })
const drafting = ref(false)
const draft = ref(null)
const targetSeconds = ref(0)
const seriesOpen = ref(false)

const saveLabel = computed(() => ({
  saved: '已保存', saving: '保存中…', error: '保存失败，可重试', conflict: '版本冲突'
}[saveState.value]))

onMounted(async () => {
  try {
    if (!story.episodes.value.length) {
      await ensureStoryShortfilm(projectId.value)
      await story.reload()
    }
    hydrateScript()
    const settings = await getProjectSettings(projectId.value)
    targetSeconds.value = Number(settings.videoTargetSeconds) || 0
  } catch (exception) {
    stageError.value = exception.response?.data?.error || '故事加载失败'
  }
})

function hydrateScript() {
  if (saveDirty.value) return // 未保存内容不被后台轮询覆盖
  script.value = story.episodes.value[0]?.script || ''
}

function markDirty() {
  saveDirty.value = true
  saveState.value = 'saving'
  window.clearTimeout(saveTimer.value)
  saveTimer.value = window.setTimeout(save, 800)
}

async function save() {
  if (!episodeId.value || !saveDirty.value) return
  if (saving.value) return
  saving.value = true
  try {
    const episode = story.episodes.value[0]
    await updateStoryEpisode(projectId.value, episodeId.value, {
      title: episode?.title || '当前短片',
      goal: episode?.goal || '',
      synopsis: episode?.synopsis || '',
      script: script.value
    })
    saveDirty.value = false
    saveState.value = 'saved'
  } catch (exception) {
    saveState.value = 'error'
    stageError.value = exception.response?.data?.error || '故事保存失败，内容保留在编辑器中'
  } finally {
    saving.value = false
  }
}

async function optimizeStory() {
  optimizing.value = true
  stageError.value = ''
  try {
    const result = await optimizeCreativePrompt({
      prompt: script.value,
      mode: 'VIDEO_STORY',
      projectId: projectId.value,
      episodeId: episodeId.value
    })
    storySuggestion.value = result.optimizedPrompt || ''
    suggestionMeta.changes = result.changes || []
    suggestionMeta.missing = result.missing || []
  } catch (exception) {
    stageError.value = exception.response?.data?.error || '整理失败'
  } finally {
    optimizing.value = false
  }
}

async function adoptStory() {
  const suggested = storySuggestion.value
  storySuggestion.value = ''
  script.value = suggested
  markDirty()
  await save()
  ctx.showToast?.('整理稿已写入剧本', 'success')
}

async function generateDraft() {
  await save()
  drafting.value = true
  stageError.value = ''
  try {
    draft.value = await createStoryboardDraft(projectId.value, {
      episodeId: episodeId.value,
      targetSeconds: targetSeconds.value || null
    })
  } catch (exception) {
    stageError.value = exception.response?.data?.error || '分镜草稿生成失败'
  } finally {
    drafting.value = false
  }
}

async function onDiscard() {
  if (draft.value) {
    discardStoryboardDraft(projectId.value, draft.value.id).catch(() => {})
  }
  draft.value = null
}

function onAdopted(result) {
  draft.value = null
  saveDirty.value = false
  ctx.showToast?.(`已创建 ${result.created} 个镜头`, 'success')
  story.reload()
  ctx.router?.push({ path: `/creative/projects/${projectId.value}/production`, query: { stage: 'shots' } })
}

onBeforeUnmount(() => window.clearTimeout(saveTimer.value))

defineExpose({ seriesOpen })
</script>

<style scoped src="../../../views/creative/creative.css"></style>

<style scoped>
.story-stage { display: grid; gap: 14px; max-width: 960px; }
.editor-card { padding: 18px 20px; border: 1px solid var(--cr-border, #e4e3df); border-radius: var(--cr-radius-pop, 16px); background: var(--cr-surface, #fff); }
.editor-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; }
.editor-head h2 { margin: 0; font-size: 16px; font-weight: 600; color: var(--cr-text, #202124); }
.editor-sub { margin: 4px 0 0; color: var(--cr-text-2, #62646a); font-size: 12px; }
.save-pill { flex: none; font-size: 11px; color: var(--cr-text-2, #62646a); }
.save-pill.saving { color: #b4693a; }
.save-pill.error { color: #c94c62; }
.story-editor { width: 100%; box-sizing: border-box; margin-top: 12px; min-height: 280px; padding: 12px; border: 1px solid var(--cr-border, #e4e3df); border-radius: var(--cr-radius-ctl, 10px); background: var(--cr-page-bg, #f6f5f2); color: var(--cr-text, #202124); font: inherit; font-size: 14px; line-height: 1.7; resize: vertical; }
.story-editor:focus { outline: none; border-color: var(--cr-accent, #6654c7); box-shadow: 0 0 0 2px var(--cr-accent-soft, rgb(102 84 199 / 14%)); }
.editor-actions { display: flex; align-items: center; gap: 10px; margin-top: 12px; flex-wrap: wrap; }
.editor-actions .cr-primary, .editor-actions .cr-secondary { height: 36px; padding: 0 14px; }
.action-hint { color: var(--cr-text-2, #62646a); font-size: 11px; }
.series-fold { border: 1px solid var(--cr-border, #e4e3df); border-radius: var(--cr-radius-pop, 16px); background: var(--cr-surface, #fff); padding: 10px 16px; }
.series-fold summary { cursor: pointer; color: var(--cr-text-2, #62646a); font-size: 12px; }
.suggest-dialog { width: min(760px, calc(100vw - 48px)); }
.suggest-dialog h3 { margin: 0 0 8px; font-size: 15px; }
.suggest-body { display: grid; grid-template-columns: 1fr 1fr; gap: 14px; }
.suggest-col h4 { margin: 0 0 6px; font-size: 12px; color: var(--cr-text-2, #62646a); }
.suggest-col p { margin: 0; max-height: 260px; overflow: auto; padding: 10px; border-radius: 8px; background: var(--cr-page-bg, #f6f5f2); font-size: 13px; line-height: 1.6; white-space: pre-wrap; }
.suggest-col textarea { width: 100%; box-sizing: border-box; padding: 10px; border: 1px solid var(--cr-border, #e4e3df); border-radius: 8px; font: inherit; font-size: 13px; line-height: 1.6; resize: vertical; }
.suggest-meta { margin: 8px 0; color: var(--cr-text-2, #62646a); font-size: 12px; }
@media (max-width: 768px) { .suggest-body { grid-template-columns: 1fr; } .form-grid { grid-template-columns: 1fr; } }
@media (prefers-reduced-motion: reduce) { * { transition: none !important; } }
</style>
