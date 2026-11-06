<template>
  <div class="si-backdrop" role="presentation" @click.self="$emit('close')">
    <aside class="si-panel" role="dialog" aria-modal="true" :aria-label="`第 ${shot.shotNumber} 镜检查器`">
      <header class="si-head">
        <div class="si-head-title">
          <strong class="si-title">第 {{ shot.shotNumber }} 镜</strong>
          <span class="si-status" :class="'is-' + status">{{ statusLabel }}</span>
          <span class="cr-field-help">v{{ version }}</span>
          <span class="si-save" :class="saveState">{{ saveLabel }}</span>
        </div>
        <div class="si-head-tools">
          <button type="button" class="si-tool" :disabled="moving" aria-label="上移镜头"
                  title="上移（顺序与候选跟随镜头）" @click="move(-1)">↑</button>
          <button type="button" class="si-tool" :disabled="moving" aria-label="下移镜头"
                  title="下移（顺序与候选跟随镜头）" @click="move(1)">↓</button>
          <button type="button" class="si-tool" :disabled="moving || !shot.sceneId" aria-label="在此镜头后插入新镜头"
                  title="在此镜头后插入新镜头" @click="insertAfter">＋</button>
          <button type="button" class="si-close" aria-label="关闭检查器" @click="$emit('close')">
            <X :size="16" />
          </button>
        </div>
      </header>

      <div class="si-body">
        <!-- 候选优先：媒体比字段更重要（UI 规范 §8.5） -->
        <section class="si-section">
          <div class="si-section-head">
            <h3>候选</h3>
            <span class="cr-field-help">生成成功不自动选定，人工选定后才计入成片</span>
          </div>
          <p v-if="!candidates.length" class="cr-empty si-empty">
            还没有候选。写清提示词后点「生成视频」，或用下方「选择已有视频」登记为本镜头候选。
          </p>
          <div v-else class="si-candidates">
            <figure v-for="view in candidates" :key="view.candidate.id" class="si-candidate"
                    :class="{ adopted: view.candidate.status === 'SELECTED' }">
              <div class="si-media">
                <video v-if="assetUrl(view.candidate)" :src="assetUrl(view.candidate)" controls preload="metadata"></video>
                <div v-else-if="view.candidate.status === 'GENERATING'" class="si-media-state">
                  生成中（{{ view.jobStatus || '任务进行中' }}）…
                </div>
                <div v-else-if="view.candidate.status === 'FAILED'" class="si-media-state is-failed">
                  <span>生成失败</span>
                  <em :title="view.jobErrorRaw || ''">{{ view.jobError || '未返回可用结果' }}</em>
                  <small v-if="view.jobErrorHint" class="si-error-hint">{{ view.jobErrorHint }}</small>
                  <button type="button" class="cr-secondary" :disabled="generating" @click="generate">
                    重新生成（计费）
                  </button>
                </div>
                <div v-else class="si-media-state">视频加载中…</div>
                <span v-if="view.candidate.status === 'SELECTED'" class="si-adopted">
                  <Check :size="12" /> 已采用
                </span>
              </div>
              <figcaption class="si-meta">
                <span class="si-meta-line">
                  <em :class="'cand-' + view.candidate.status">{{ candidateLabel(view.candidate) }}</em>
                  <span v-if="view.asset" class="cr-field-help">
                    {{ view.asset.width }}×{{ view.asset.height }} · {{ formatDuration(view.asset.durationMs) }}
                  </span>
                  <span class="cr-field-help">{{ view.candidate.source === 'UPLOAD' ? '上传' : '生成' }}
                    · {{ formatTime(view.candidate.createdAt) }}</span>
                  <span v-if="isOutdated(view.candidate)" class="si-outdated"
                        title="该候选基于当时的提示词/画风生成，与当前镜头设定不同">较早设定</span>
                </span>
                <span class="si-meta-actions">
                  <button v-if="view.candidate.status === 'CANDIDATE'" type="button" class="cr-primary"
                          @click="select(view)">选定</button>
                  <button v-if="view.candidate.status === 'SELECTED'" type="button" class="cr-ghost"
                          @click="unselect(view)">取消选定</button>
                  <button v-if="view.candidate.status !== 'SELECTED'" type="button" class="cr-ghost"
                          @click="removeCandidate(view)">删除</button>
                </span>
              </figcaption>
            </figure>
          </div>
          <label class="si-upload">
            <span class="cr-field-help">选择本人视频素材作为候选</span>
            <DropdownSelect :model-value="''" :options="videoAssetOptions" block
                            placeholder="选择已有视频…" aria-label="选择已有视频" @change="addUpload" />
          </label>
        </section>

        <section class="si-section">
          <div class="si-section-head">
            <h3>提示词</h3>
            <span class="cr-field-help">{{ form.promptText.length }}/500</span>
          </div>
          <textarea v-model="form.promptText" class="cr-textarea" rows="5" maxlength="500"
                    placeholder="可直接提交给视频模型的文本；可用「优化提示词」结合镜头字段改写"></textarea>
          <div class="si-actions">
            <button type="button" class="cr-secondary" :disabled="optimizing" @click="optimize">
              <Sparkles :size="14" /> {{ optimizing ? '优化中…' : '优化提示词' }}
            </button>
            <button type="button" class="cr-secondary" :disabled="busy || !dirty" @click="save">
              <Spinner v-if="busy" /> {{ dirty ? '保存修改' : '已保存' }}
            </button>
            <button type="button" class="cr-primary" :disabled="busy || !canGenerate" @click="generate">
              <Spinner v-if="generating" /> 生成视频（{{ form.durationTargetSeconds }} 秒）
            </button>
          </div>
          <p class="cr-field-help">生成费用高：每次点击都会真实计费，候选不自动选定。</p>
          <p v-if="error" class="cr-error">{{ error }}</p>

          <div v-if="suggestion" class="si-suggestion">
            <div class="si-section-head">
              <strong>优化建议</strong>
              <span class="cr-field-help">可编辑后采纳，不会自动触发生成</span>
            </div>
            <div class="si-diff">
              <div><span class="si-label">原文</span><p>{{ originalText }}</p></div>
              <div><span class="si-label">新稿</span>
                <textarea v-model="suggestion" class="cr-textarea" rows="4"></textarea></div>
            </div>
            <ul v-if="optimizeMeta.changes?.length" class="si-list"><li v-for="item in optimizeMeta.changes" :key="item">改：{{ item }}</li></ul>
            <ul v-if="optimizeMeta.suggestions?.length" class="si-list sugg"><li v-for="item in optimizeMeta.suggestions" :key="item">议：{{ item }}</li></ul>
            <ul v-if="optimizeMeta.missing?.length" class="si-list miss"><li v-for="item in optimizeMeta.missing" :key="item">缺：{{ item }}</li></ul>
            <div class="si-actions">
              <button type="button" class="cr-primary" @click="adopt">采纳到提示词</button>
              <button type="button" class="cr-ghost" @click="suggestion = ''">放弃</button>
            </div>
          </div>
        </section>

        <section class="si-section">
          <div class="si-section-head">
            <h3>模型参数</h3>
            <span class="cr-field-help">生成提交时按此执行</span>
          </div>
          <dl class="si-params">
            <div><dt>视频模型</dt><dd>{{ videoModelLabel }}</dd></div>
            <div><dt>时长</dt><dd>{{ form.durationTargetSeconds }} 秒（档位支持以模型能力为准）</dd></div>
          </dl>
        </section>

        <section class="si-section">
          <div class="si-section-head">
            <h3>首帧参考</h3>
            <span class="cr-field-help">有首帧走图生视频；模型不支持文生视频时必须设置</span>
          </div>
          <div v-if="frameAsset" class="si-frame">
            <span>{{ frameAsset.displayName || '首帧图片' }}</span>
            <button type="button" class="cr-ghost" @click="setImageFrame('')">移除</button>
          </div>
          <p v-else class="cr-field-help">未设置首帧；将按文生视频提交。</p>
          <div class="si-frame-actions">
            <DropdownSelect :model-value="frameAssetId" :options="imageAssetOptions" block
                            placeholder="从素材选择首帧…" aria-label="选择首帧图片" @change="setImageFrame" />
            <label class="cr-ghost si-upload-frame">
              上传图片
              <input type="file" accept="image/*" @change="uploadFrame" />
            </label>
          </div>
        </section>

        <section class="si-section">
          <div class="si-section-head"><h3>画风</h3><span class="cr-field-help">当前生效：{{ styleLabel }}</span></div>
          <DropdownSelect v-model="form.styleCode" :options="styleOptions" block
                          placeholder="继承项目默认" aria-label="镜头画风覆盖" />
        </section>

        <details class="si-advanced">
          <summary>高级剧情字段</summary>
          <div class="si-advanced-body">
            <label class="cr-field"><span class="cr-field-label"><span>剧情目的</span></span>
              <textarea v-model="form.purpose" class="cr-textarea" rows="2" maxlength="20000"
                        placeholder="观众此刻应理解什么，发生什么变化"></textarea>
            </label>
            <label class="cr-field"><span class="cr-field-label"><span>动作 / 表演</span></span>
              <textarea v-model="form.action" class="cr-textarea" rows="3" maxlength="20000"
                        placeholder="动作部位、方向、速度、幅度；用可见动作表达情绪"></textarea>
            </label>
            <div class="si-row">
              <label class="cr-field"><span class="cr-field-label"><span>景别 / 机位 / 运镜</span></span>
                <input v-model="form.camera" class="cr-input" maxlength="200" /></label>
              <label class="cr-field"><span class="cr-field-label"><span>目标时长（秒）</span></span>
                <input v-model.number="form.durationTargetSeconds" class="cr-input" type="number" min="1" max="60" /></label>
            </div>
            <label class="cr-field"><span class="cr-field-label"><span>对白 / 声音意图</span></span>
              <textarea v-model="form.dialogue" class="cr-textarea" rows="2" maxlength="20000"
                        placeholder="台词、说话人、停顿；能力未验证时保留为后期制作要求"></textarea>
            </label>
            <label class="cr-field"><span class="cr-field-label"><span>衔接 / 连续性</span></span>
              <textarea v-model="form.continuity" class="cr-textarea" rows="2" maxlength="20000"
                        placeholder="上一镜头结束与下一镜头开始的承接；服装/道具/天气"></textarea>
            </label>
          </div>
        </details>
      </div>
    </aside>
  </div>
</template>

<script setup>
import { computed, inject, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { Check, Sparkles, X } from 'lucide-vue-next'
import {
  createStoryShot, deleteShotCandidate, generateShotVideo, getStoryShot, listCreativeAssets,
  listShotCandidates, moveStoryShot, optimizeCreativePrompt, preflightStoryGeneration,
  selectShotCandidate, unselectShotCandidate, updateStoryShot, uploadCreativeAsset, uploadShotCandidate
} from '../../../api'
import { formatDuration, formatTime, useVideoUrls } from '../shared'
import { PROJECT_CTX } from '../project/composables'
import { listCreativePromptImageStyles } from '../../../api'
import { CANDIDATE_STATUS_LABEL, SHOT_STATUS_LABEL } from '../project/helpers'
import DropdownSelect from '../../../components/ui/DropdownSelect.vue'
import Spinner from '../../../components/ui/Spinner.vue'

const props = defineProps({
  projectId: { type: String, required: true },
  shot: { type: Object, required: true },
  characters: { type: Array, default: () => [] },
  locations: { type: Array, default: () => [] }
})
// 模型参数区与「较早设定」标记需要目录与绑定：来自 Shell 的上下文，避免再发一遍目录请求
const ctx = inject(PROJECT_CTX)
let styleCatalogPromise = null
const styleCatalog = ref([])
styleCatalogPromise = styleCatalogPromise || listCreativePromptImageStyles()
  .then(list => { styleCatalog.value = Array.isArray(list) ? list : [] })
  .catch(() => {})
const emit = defineEmits(['close', 'changed', 'inserted'])

const { videoUrls, videoUrlFor } = useVideoUrls()
const form = reactive({
  purpose: props.shot.purpose || '',
  action: props.shot.action || '',
  camera: props.shot.camera || '',
  dialogue: props.shot.dialogue || '',
  continuity: props.shot.continuity || '',
  durationTargetSeconds: props.shot.durationTargetSeconds || 4,
  promptText: props.shot.promptText || '',
  styleCode: props.shot.styleCode || '',
  referenceAssetIds: [...(props.shot.referenceAssetIds || [])]
})
const version = ref(props.shot.version)
const status = ref(props.shot.status)
const candidates = ref([])
const videoAssets = ref([])
const busy = ref(false)
const generating = ref(false)
const optimizing = ref(false)
const moving = ref(false)
const error = ref('')
const suggestion = ref('')
const originalText = ref('')
const optimizeMeta = ref({})

// 基线取「已保存快照」而非 props（保存后 props.shot 不会回填，避免按钮永远显示可保存）
const saved = reactive({ ...JSON.parse(JSON.stringify(form)) })
const dirty = computed(() => form.purpose !== saved.purpose
  || form.action !== saved.action
  || form.camera !== saved.camera
  || form.dialogue !== saved.dialogue
  || form.continuity !== saved.continuity
  || form.promptText !== saved.promptText
  || form.durationTargetSeconds !== saved.durationTargetSeconds
  || form.styleCode !== saved.styleCode
  || JSON.stringify(form.referenceAssetIds) !== JSON.stringify(saved.referenceAssetIds))
const canGenerate = computed(() =>
  ['DRAFT', 'READY'].includes(status.value) && form.promptText.trim().length > 0)
const statusLabel = computed(() => SHOT_STATUS_LABEL[status.value] || status.value)
const saveLabel = computed(() => ({ saved: '已保存', saving: '保存中…', error: '保存失败' }[saveState.value] || ''))
const videoModelLabel = computed(() => {
  const binding = ctx?.videoBinding?.value
  if (!binding?.modelName) return '未绑定（到 设置 → 模型绑定 配置）'
  return `${binding.providerName || ''} / ${binding.modelName}`.replace(/^\s*\/\s*/, '')
})
const styleLabel = computed(() => {
  // 展示当前实际生效的风格：镜头覆盖 > 项目默认
  const code = effectiveStyleCode.value
  if (!code) return '未指定'
  return styleCatalog.value.find(style => style.code === code)?.label || code
})
const effectiveStyleCode = computed(() => form.styleCode || ctx?.projectStyle?.value || '')
const styleOptions = computed(() => ([
  { value: '', label: '继承项目默认' },
  ...styleCatalog.value.filter(style => style.code !== 'PHOTO_MATCH')
    .map(style => ({ value: style.code, label: `${style.label}（${style.category}）` }))
]))
const imageAssets = ref([])
const frameAssetId = computed(() => form.referenceAssetIds[0] || '')
const frameAsset = computed(() => imageAssets.value.find(asset => asset.id === frameAssetId.value) || null)
const imageAssetOptions = computed(() => imageAssets.value.map(asset => ({
  value: asset.id, label: asset.displayName || asset.id.slice(0, 8)
})))
async function uploadFrame(event) {
  const file = event.target.files?.[0]
  if (!file) return
  try {
    const asset = await uploadCreativeAsset(props.projectId, file)
    await setImageFrame(asset.id)
  } catch (exception) {
    error.value = exception.response?.data?.error || '首帧上传失败'
  } finally { event.target.value = '' }
}
async function setImageFrame(assetId) {
  form.referenceAssetIds = assetId ? [assetId] : []
  markDirtyAndSave()
}
function markDirtyAndSave() {
  saveState.value = 'saving'
  window.clearTimeout(saveTimer)
  saveTimer = window.setTimeout(save, 500)
}
// 候选基于当时的提示词/画风生成：与当前镜头提示词不同即视为较早设定（不阻止采用）
function isOutdated(candidate) {
  const generated = (candidate.promptText || '').trim()
  const current = (props.shot.promptText || '').trim()
  return Boolean(generated) && current && generated !== current
}

const candidateLabel = candidate => CANDIDATE_STATUS_LABEL[candidate.status] || candidate.status
const videoAssetOptions = computed(() => videoAssets.value.map(asset => ({
  value: asset.id, label: asset.displayName || asset.id.slice(0, 8)
})))

function assetUrl(candidate) {
  if (!candidate.assetId) return ''
  return videoUrls[candidate.assetId]?.url || ''
}

async function loadImageAssets() {
  try {
    const result = await listCreativeAssets({ projectId: props.projectId, mediaType: 'IMAGE', limit: 50 })
    imageAssets.value = (result.items || [])
  } catch { imageAssets.value = [] }
}

async function reloadCandidates() {
  try {
    candidates.value = await listShotCandidates(props.projectId, props.shot.id)
    candidates.value.forEach(view => {
      if (view.candidate.assetId) videoUrlFor(view.candidate.assetId)
    })
    // 上传候选/回写都会推进镜头版本：同步版本与状态，避免 expectedVersion 过期 409
    const shot = await getStoryShot(props.projectId, props.shot.id)
    version.value = shot.version
    status.value = shot.status
  } catch (exception) {
    error.value = exception.response?.data?.error || '候选加载失败'
  }
}

// 自动保存（P0 制作视图）：输入停止 800ms 后保存；失败在按钮区给出重试
let saveTimer = null
watch(form, () => {
  if (!dirty.value) return
  saveState.value = 'saving'
  window.clearTimeout(saveTimer)
  saveTimer = window.setTimeout(save, 800)
}, { deep: true })
const saveState = ref('saved')
onBeforeUnmount(() => window.clearTimeout(saveTimer))

async function move(direction) {
  if (moving.value) return
  moving.value = true
  error.value = ''
  try {
    await moveStoryShot(props.projectId, props.shot.id, direction)
    emit('changed')
  } catch (exception) {
    error.value = exception.response?.data?.error || '排序失败'
  } finally {
    moving.value = false
  }
}

async function insertAfter() {
  if (moving.value || !props.shot.sceneId) return
  moving.value = true
  error.value = ''
  try {
    const created = await createStoryShot(props.projectId, props.shot.sceneId, {}, props.shot.id)
    emit('changed')
    emit('inserted', created)
  } catch (exception) {
    error.value = exception.response?.data?.error || '插入失败'
  } finally {
    moving.value = false
  }
}

async function save() {
  if (busy.value) return
  busy.value = true
  error.value = ''
  try {
    const updated = await updateStoryShot(props.projectId, props.shot.id, {
      expectedVersion: version.value,
      purpose: form.purpose, action: form.action, camera: form.camera,
      dialogue: form.dialogue, continuity: form.continuity,
      durationTargetSeconds: form.durationTargetSeconds, promptText: form.promptText,
      styleCode: form.styleCode, referenceAssetIds: form.referenceAssetIds
    })
    version.value = updated.version
    status.value = updated.status
    saveState.value = 'saved'
    Object.assign(saved, {
      purpose: form.purpose, action: form.action, camera: form.camera,
      dialogue: form.dialogue, continuity: form.continuity,
      durationTargetSeconds: form.durationTargetSeconds, promptText: form.promptText,
      styleCode: form.styleCode, referenceAssetIds: [...form.referenceAssetIds]
    })
    emit('changed')
  } catch (exception) {
    saveState.value = 'error'
    error.value = exception.response?.data?.error || '保存失败（版本冲突时请刷新重试）'
  } finally {
    busy.value = false
  }
}

async function optimize() {
  optimizing.value = true
  error.value = ''
  try {
    const result = await optimizeCreativePrompt({
      prompt: form.promptText.trim() || [form.purpose, form.action, form.camera].filter(Boolean).join('；'),
      mode: 'VIDEO_PROMPT',
      projectId: props.projectId,
      shotId: props.shot.id
    })
    originalText.value = result.originalPrompt
    suggestion.value = result.optimizedPrompt
    optimizeMeta.value = result
  } catch (exception) {
    error.value = exception.response?.data?.error || '优化失败，可直接使用原文'
  } finally {
    optimizing.value = false
  }
}

async function adopt() {
  form.promptText = suggestion.value
  suggestion.value = ''
  await save()
}

async function preflightThisShot() {
  const result = await preflightStoryGeneration(props.projectId, [props.shot.id])
  return result.items?.[0] || { ok: false, reason: '预检不可用' }
}

async function generate() {
  if (dirty.value) await save()
  generating.value = true
  error.value = ''
  try {
    const item = await preflightThisShot()
    if (item.ok && item.adjusted) {
      ctx?.showToast?.(`时长按模型档位调整为 ${item.effectiveSeconds} 秒`, 'success')
    }
    if (!item.ok) {
      error.value = `无法提交：${item.reason || '预检未通过'}` + (item.operation === 'text_to_video'
        ? '（当前模型可能只支持图生视频，可在下方「首帧参考」设置一张首帧图）' : '')
      return
    }
    await generateShotVideo(props.projectId, props.shot.id, {
      expectedVersion: version.value,
      seconds: Math.min(form.durationTargetSeconds || 4, 60)
    })
    status.value = 'GENERATING'
    await reloadCandidates()
    emit('changed')
  } catch (exception) {
    error.value = exception.response?.data?.error || '生成提交失败'
  } finally {
    generating.value = false
  }
}

async function select(view) {
  try {
    await selectShotCandidate(props.projectId, props.shot.id, {
      candidateId: view.candidate.id, expectedVersion: version.value
    })
    await reloadCandidates()
    emit('changed')
  } catch (exception) {
    error.value = exception.response?.data?.error || '选片失败'
  }
}

async function unselect(view) {
  try {
    await unselectShotCandidate(props.projectId, props.shot.id, view.candidate.id)
    await reloadCandidates()
    emit('changed')
  } catch (exception) {
    error.value = exception.response?.data?.error || '取消选定失败'
  }
}

async function removeCandidate(view) {
  try {
    await deleteShotCandidate(props.projectId, props.shot.id, view.candidate.id)
    await reloadCandidates()
    emit('changed')
  } catch (exception) {
    error.value = exception.response?.data?.error || '删除失败'
  }
}

async function addUpload(assetId) {
  if (!assetId) return
  try {
    await uploadShotCandidate(props.projectId, props.shot.id, assetId)
    await reloadCandidates()
    emit('changed')
  } catch (exception) {
    error.value = exception.response?.data?.error || '上传候选失败'
  }
}

onMounted(async () => {
  loadImageAssets()
  await reloadCandidates()
  try {
    const data = await listCreativeAssets({ projectId: props.projectId, mediaType: 'VIDEO', limit: 50 })
    videoAssets.value = (data.items || []).filter(asset => asset.status === 'READY')
  } catch (exception) { /* 候选上传下拉不可用不阻断主流程 */ }
})
</script>

<style scoped src="../creative.css"></style>

<style scoped>
.si-backdrop {
  position: fixed;
  inset: 0;
  z-index: 80;
  background: rgb(0 0 0 / 32%);
  display: flex;
  justify-content: flex-end;
}

.si-panel {
  width: min(560px, 100%);
  height: 100%;
  overflow-y: auto;
  background: var(--wb-surface);
  border-left: 1px solid var(--wb-border);
  box-shadow: var(--wb-shadow-lg);
  display: flex;
  flex-direction: column;
}

.si-head {
  position: sticky;
  top: 0;
  z-index: 1;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  padding: 12px 18px;
  border-bottom: 1px solid var(--wb-border);
  background: var(--wb-surface);
}

.si-head-title { display: flex; align-items: center; gap: var(--wb-space-2); }
.si-title { font-size: var(--wb-fs-panel); font-weight: 600; }

.si-status {
  font-size: var(--wb-fs-caption);
  padding: 1px 8px;
  border-radius: 999px;
  border: 1px solid var(--wb-border);
  color: var(--wb-text-2);
}
.si-status.is-SELECTED { color: var(--wb-success); border-color: color-mix(in srgb, var(--wb-success) 40%, transparent); }
.si-status.is-PENDING_SELECTION { color: var(--wb-warning); border-color: color-mix(in srgb, var(--wb-warning) 40%, transparent); }
.si-error-hint { display: block; margin-top: 3px; color: var(--wb-muted); font-size: 10px; }
.si-outdated { padding: 1px 6px; border-radius: 999px; background: color-mix(in srgb, #d6ad5c 18%, transparent); color: #a35914; font-size: 10px; }
.si-params { margin: 0; display: grid; gap: 5px; font-size: 12px; }
.si-params div { display: grid; grid-template-columns: 72px 1fr; gap: 8px; }
.si-params dt { color: var(--wb-muted); }
.si-params dd { margin: 0; color: var(--wb-text); word-break: break-word; }
.si-advanced { border-top: 1px solid var(--wb-border); padding-top: 10px; margin-top: 4px; }
.si-advanced summary { cursor: pointer; color: var(--wb-muted); font-size: 12px; }
.si-advanced-body { display: grid; gap: 10px; margin-top: 10px; }
.si-status.is-GENERATING { color: var(--wb-info); border-color: color-mix(in srgb, var(--wb-info) 40%, transparent); }

.si-save { font-size: 11px; color: var(--wb-muted); }
.si-save.saving { color: #b4693a; }
.si-save.error { color: var(--wb-danger, #c94c62); }
.si-head-tools { display: inline-flex; align-items: center; gap: 4px; }
.si-tool {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 28px;
  height: 28px;
  padding: 0 6px;
  border: 1px solid var(--wb-border);
  border-radius: var(--wb-radius-sm);
  background: transparent;
  color: var(--wb-muted);
  font-size: 13px;
  cursor: pointer;
}
.si-tool:hover:not(:disabled) { background: var(--wb-fill); color: var(--wb-text); }
.si-tool:disabled { opacity: .4; cursor: default; }

.si-close {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  border: 0;
  border-radius: var(--wb-radius-sm);
  background: transparent;
  color: var(--wb-muted);
  cursor: pointer;
}
.si-close:hover { background: var(--wb-fill); color: var(--wb-text); }

.si-body { padding: 14px 18px 24px; display: flex; flex-direction: column; gap: var(--wb-space-5); }

.si-section { display: flex; flex-direction: column; gap: var(--wb-space-2); }
.si-section h3 { margin: 0; font-size: var(--wb-fs-panel); font-weight: 600; }
.si-section-head { display: flex; align-items: baseline; justify-content: space-between; gap: 10px; flex-wrap: wrap; }
.si-sub { margin: 6px 0 0; font-size: var(--wb-fs-caption); color: var(--wb-muted); font-weight: 500; }

.si-candidates { display: grid; grid-template-columns: 1fr 1fr; gap: var(--wb-space-2); }
.si-candidate { margin: 0; display: flex; flex-direction: column; gap: 4px; }

.si-media {
  position: relative;
  aspect-ratio: 16 / 9;
  background: var(--wb-media-bg);
  border: 1px solid var(--wb-border);
  border-radius: var(--wb-radius-sm);
  overflow: hidden;
  display: grid;
  place-items: center;
}
.si-media video { width: 100%; height: 100%; object-fit: contain; display: block; }
.si-candidate.adopted .si-media { border-color: var(--wb-success); box-shadow: 0 0 0 1px var(--wb-success); }

.si-media-state {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
  padding: 8px;
  color: var(--wb-muted);
  font-size: var(--wb-fs-caption);
  text-align: center;
}
.si-media-state.is-failed { color: var(--wb-danger); }
.si-media-state em { font-style: normal; color: var(--wb-muted); font-size: var(--wb-fs-caption); }

.si-adopted {
  position: absolute;
  right: 6px;
  bottom: 6px;
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 1px 8px;
  border-radius: 999px;
  font-size: var(--wb-fs-caption);
  color: var(--wb-success);
  background: color-mix(in srgb, var(--wb-surface) 90%, transparent);
  border: 1px solid color-mix(in srgb, var(--wb-success) 45%, transparent);
}

.si-meta { display: flex; flex-direction: column; gap: 2px; }
.si-meta-line { display: flex; align-items: baseline; gap: 6px; flex-wrap: wrap; font-size: var(--wb-fs-caption); }
.si-meta-line em { font-style: normal; color: var(--wb-text-2); }
.si-meta-line .cand-SELECTED { color: var(--wb-success); }
.si-meta-line .cand-CANDIDATE { color: var(--wb-warning); }
.si-meta-line .cand-FAILED { color: var(--wb-danger); }
.si-meta-actions { display: flex; gap: 4px; flex-wrap: wrap; }

.si-upload { display: flex; flex-direction: column; gap: 4px; }
.si-actions { display: flex; gap: var(--wb-space-2); flex-wrap: wrap; align-items: center; }
.si-row { display: grid; grid-template-columns: 2fr 1fr; gap: 10px; }
.si-empty { margin: 0; }

.si-suggestion {
  border: 1px solid var(--wb-border);
  border-radius: var(--wb-radius);
  padding: 10px 12px;
  display: flex;
  flex-direction: column;
  gap: var(--wb-space-2);
}
.si-diff { display: grid; grid-template-columns: 1fr 1fr; gap: 10px; }
.si-diff p { margin: 4px 0 0; font-size: var(--wb-fs-caption); color: var(--wb-text-2); white-space: pre-wrap; }
.si-label { font-size: var(--wb-fs-caption); color: var(--wb-muted); }
.si-list { margin: 4px 0 0; padding-left: 16px; font-size: var(--wb-fs-caption); color: var(--wb-text-2); }
.si-list.sugg { color: var(--wb-info); }
.si-list.miss { color: var(--wb-warning); }

/* 宽屏是右侧检查器：不遮挡主区、不阻断列表操作（UI 规范 §6.3） */
@media (min-width: 1100px) {
  .si-backdrop {
    background: transparent;
    pointer-events: none;
  }
  .si-panel {
    width: 420px;
    margin-left: auto;
    pointer-events: auto;
    box-shadow: var(--wb-shadow-md);
  }
}

/* 窄屏退化为覆盖式抽屉 */
@media (max-width: 1099px) {
  .si-panel { width: min(480px, 100%); }
}

@media (max-width: 720px) {
  .si-candidates, .si-diff, .si-row { grid-template-columns: 1fr; }
  .si-panel { width: 100%; }
}
</style>
