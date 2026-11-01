<template>
  <div class="sd-backdrop" role="dialog" aria-modal="true" @click.self="$emit('close')">
    <div class="sd-panel">
      <header class="sd-head">
        <div>
          <strong>第 {{ shot.shotNumber }} 镜</strong>
          <span class="sw-chip" :class="'shot-' + shot.status">{{ statusLabel }}</span>
          <span class="cr-field-help">版本 v{{ shot.version }}</span>
        </div>
        <button type="button" class="ap-close" aria-label="关闭" @click="$emit('close')">×</button>
      </header>

      <div class="sd-body">
        <section class="sd-fields">
          <h4>叙事目的</h4>
          <textarea v-model="form.purpose" class="cr-textarea" rows="2" maxlength="20000"
                    placeholder="观众此刻应理解什么，发生什么变化"></textarea>

          <h4>动作 / 表演</h4>
          <textarea v-model="form.action" class="cr-textarea" rows="3" maxlength="20000"
                    placeholder="动作部位、方向、速度、幅度；用可见动作表达情绪"></textarea>

          <div class="sd-row">
            <label class="cr-field"><span class="cr-field-label"><span>景别 / 机位 / 运镜（单镜头一种主运镜）</span></span>
              <input v-model="form.camera" class="cr-input" maxlength="200" /></label>
            <label class="cr-field"><span class="cr-field-label"><span>目标时长（秒）</span></span>
              <input v-model.number="form.durationTargetSeconds" class="cr-input" type="number" min="1" max="60" /></label>
          </div>

          <h4>对白 / 声音意图</h4>
          <textarea v-model="form.dialogue" class="cr-textarea" rows="2" maxlength="20000"
                    placeholder="台词、说话人、停顿；能力未验证时保留为后期制作要求"></textarea>

          <h4>衔接 / 连续性</h4>
          <textarea v-model="form.continuity" class="cr-textarea" rows="2" maxlength="20000"
                    placeholder="上一镜头结束与下一镜头开始的承接；服装/道具/天气"></textarea>

          <h4>提交提示词（用于生成）</h4>
          <textarea v-model="form.promptText" class="cr-textarea" rows="5" maxlength="500"
                    placeholder="可直接提交给视频模型的文本；可用下方「优化提示词」基于镜头字段改写"></textarea>

          <div class="sd-actions">
            <button type="button" class="cr-secondary" :disabled="busy || !dirty" @click="save">
              <Spinner v-if="busy" /> {{ dirty ? '保存修改' : '已保存' }}
            </button>
            <button type="button" class="cr-secondary" :disabled="optimizing" @click="optimize">
              <Sparkles :size="14" /> {{ optimizing ? '优化中…' : '优化提示词（结合镜头上下文）' }}
            </button>
            <button type="button" class="cr-primary" :disabled="busy || !canGenerate" @click="generate">
              <Spinner v-if="generating" /> 生成视频（4 秒）
            </button>
          </div>
          <p class="cr-field-help">生成费用高：默认 4 秒、当前绑定模型最低档；每次点击都会真实计费。</p>
          <p v-if="error" class="cr-error">{{ error }}</p>

          <!-- 优化结果：原文 / 新稿对比 + 可编辑后采纳 -->
          <div v-if="suggestion" class="sd-suggestion">
            <div class="sd-suggestion-head"><strong>优化建议</strong>
              <span class="cr-field-help">不会自动触发生成；可编辑后采纳</span></div>
            <div class="sd-diff">
              <div><span class="sw-label">原文</span><p>{{ originalText }}</p></div>
              <div><span class="sw-label">新稿</span>
                <textarea v-model="suggestion" class="cr-textarea" rows="4"></textarea></div>
            </div>
            <ul v-if="optimizeMeta.changes?.length" class="sd-list"><li v-for="item in optimizeMeta.changes" :key="item">改：{{ item }}</li></ul>
            <ul v-if="optimizeMeta.suggestions?.length" class="sd-list sd-sugg"><li v-for="item in optimizeMeta.suggestions" :key="item">议：{{ item }}</li></ul>
            <ul v-if="optimizeMeta.missing?.length" class="sd-list sd-miss"><li v-for="item in optimizeMeta.missing" :key="item">缺：{{ item }}</li></ul>
            <div class="sd-actions">
              <button type="button" class="cr-primary" @click="adopt">采纳到提示词</button>
              <button type="button" class="cr-ghost" @click="suggestion = ''">放弃</button>
            </div>
          </div>
        </section>

        <section class="sd-candidates">
          <h4>候选（生成成功不自动选定）</h4>
          <div v-if="!candidates.length" class="cr-empty">还没有候选；生成或上传视频后在这里对比与选片。</div>
          <div v-for="view in candidates" :key="view.candidate.id" class="sd-candidate">
            <video v-if="videoUrl(view.candidate)" :src="videoUrl(view.candidate)" controls preload="metadata"></video>
            <div v-else-if="view.candidate.status === 'GENERATING'" class="sd-candidate-loading">
              生成中（{{ view.jobStatus || '任务进行中' }}）…
            </div>
            <div v-else-if="view.candidate.status === 'FAILED'" class="sd-candidate-loading sd-failed">
              生成失败{{ view.jobError ? '：' + view.jobError : '' }}
            </div>
            <div v-else class="sd-candidate-loading">视频加载中…</div>
            <div class="sd-candidate-meta">
              <span class="sw-chip" :class="'cand-' + view.candidate.status">{{ candidateLabel(view.candidate) }}</span>
              <span class="cr-field-help">{{ view.candidate.source === 'UPLOAD' ? '上传' : '生成' }} ·
                {{ view.candidate.promptText ? '提示词已冻结' : '' }}</span>
              <span class="sd-candidate-actions">
                <button v-if="view.candidate.status === 'CANDIDATE'" type="button" class="cr-primary"
                        @click="select(view)">选定</button>
                <button v-if="view.candidate.status === 'SELECTED'" type="button" class="cr-ghost"
                        @click="unselect(view)">取消选定</button>
                <button v-if="view.candidate.status !== 'SELECTED'" type="button" class="cr-ghost"
                        @click="removeCandidate(view)">删除</button>
              </span>
            </div>
          </div>
          <label class="sd-upload">
            <span class="cr-ghost">+ 上传/选择已有视频作为候选</span>
            <select class="cr-select" @change="addUpload($event)">
              <option value="" disabled selected>选择本人视频素材</option>
              <option v-for="asset in videoAssets" :key="asset.id" :value="asset.id">
                {{ asset.displayName || asset.id.slice(0, 8) }}
              </option>
            </select>
          </label>
        </section>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { Sparkles } from 'lucide-vue-next'
import {
  deleteShotCandidate, generateShotVideo, getStoryShot, listCreativeAssets, listShotCandidates,
  optimizeCreativePrompt, selectShotCandidate, unselectShotCandidate, updateStoryShot,
  uploadShotCandidate
} from '../../../api'
import { useVideoUrls } from '../shared'
import Spinner from '../../../components/ui/Spinner.vue'

const props = defineProps({
  projectId: { type: String, required: true },
  shot: { type: Object, required: true },
  characters: { type: Array, default: () => [] },
  locations: { type: Array, default: () => [] }
})
const emit = defineEmits(['close', 'changed'])

const { videoUrls, videoUrlFor } = useVideoUrls()
const form = reactive({
  purpose: props.shot.purpose || '',
  action: props.shot.action || '',
  camera: props.shot.camera || '',
  dialogue: props.shot.dialogue || '',
  continuity: props.shot.continuity || '',
  durationTargetSeconds: props.shot.durationTargetSeconds || 4,
  promptText: props.shot.promptText || ''
})
const version = ref(props.shot.version)
const status = ref(props.shot.status)
const candidates = ref([])
const videoAssets = ref([])
const busy = ref(false)
const generating = ref(false)
const optimizing = ref(false)
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
  || form.durationTargetSeconds !== saved.durationTargetSeconds)
const canGenerate = computed(() =>
  ['DRAFT', 'READY'].includes(status.value) && form.promptText.trim().length > 0)
const statusLabel = computed(() => ({
  DRAFT: '草稿', READY: '可生成', GENERATING: '生成中',
  PENDING_SELECTION: '待选片', SELECTED: '已选定'
}[status.value] || status.value))

function videoUrl(candidate) {
  return videoUrls[candidate.assetId]?.url || videoUrls[candidate.assetId] || ''
}
function candidateLabel(candidate) {
  return { GENERATING: '生成中', CANDIDATE: '待选片', SELECTED: '已选定', FAILED: '失败' }[candidate.status] || candidate.status
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

async function save() {
  busy.value = true
  error.value = ''
  try {
    const updated = await updateStoryShot(props.projectId, props.shot.id, {
      expectedVersion: version.value,
      purpose: form.purpose, action: form.action, camera: form.camera,
      dialogue: form.dialogue, continuity: form.continuity,
      durationTargetSeconds: form.durationTargetSeconds, promptText: form.promptText
    })
    version.value = updated.version
    status.value = updated.status
    Object.assign(saved, { purpose: form.purpose, action: form.action, camera: form.camera,
      dialogue: form.dialogue, continuity: form.continuity,
      durationTargetSeconds: form.durationTargetSeconds, promptText: form.promptText })
    emit('changed')
  } catch (exception) {
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

async function generate() {
  if (dirty.value) await save()
  generating.value = true
  error.value = ''
  try {
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
async function addUpload(event) {
  const assetId = event.target.value
  event.target.value = ''
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
  await reloadCandidates()
  try {
    const data = await listCreativeAssets({ projectId: props.projectId, mediaType: 'VIDEO', limit: 50 })
    videoAssets.value = (data.items || []).filter(asset => asset.status === 'READY')
  } catch (exception) { /* 候选上传下拉不可用不阻断主流程 */ }
})
</script>

<style scoped src="../creative.css"></style>
<style scoped>
.sd-backdrop { position: fixed; inset: 0; z-index: 80; display: flex; justify-content: center;
  background: rgba(18, 21, 30, 0.6); padding: 24px; }
.sd-panel { display: flex; flex-direction: column; width: min(1040px, 100%); max-height: 100%;
  border-radius: 14px; background: var(--wb-surface); padding: 14px 18px; overflow: auto; }
.sd-head { display: flex; align-items: center; justify-content: space-between; gap: 10px; }
.sd-head > div { display: flex; align-items: center; gap: 8px; }
.ap-close { border: 0; background: transparent; color: var(--wb-muted); font-size: 22px; cursor: pointer; }
.sd-body { display: grid; grid-template-columns: minmax(0, 1.2fr) minmax(0, 1fr); gap: 18px; margin-top: 10px; }
@media (max-width: 900px) { .sd-body { grid-template-columns: 1fr; } }
.sd-fields h4, .sd-candidates h4 { margin: 10px 0 4px; font-size: 12.5px; color: var(--wb-muted); }
.sd-row { display: grid; grid-template-columns: 2fr 1fr; gap: 10px; }
.sd-actions { display: flex; gap: 8px; margin-top: 10px; flex-wrap: wrap; align-items: center; }
.sd-suggestion { margin-top: 12px; border: 1px solid var(--wb-border); border-radius: 10px; padding: 10px 12px; }
.sd-suggestion-head { display: flex; align-items: center; gap: 8px; }
.sd-diff { display: grid; grid-template-columns: 1fr 1fr; gap: 10px; margin-top: 8px; }
.sd-diff p { margin: 0; font-size: 12.5px; color: var(--wb-text-2); white-space: pre-wrap; }
.sd-list { margin: 6px 0 0; padding-left: 16px; font-size: 12px; color: var(--wb-text-2); }
.sd-sugg { color: #2563eb; }
.sd-miss { color: #b45309; }
.sd-candidates { border-left: 1px solid var(--wb-border); padding-left: 16px; }
@media (max-width: 900px) { .sd-candidates { border-left: 0; padding-left: 0; } }
.sd-candidate { margin-bottom: 12px; }
.sd-candidate video { display: block; width: 100%; aspect-ratio: 16 / 9; background: #000;
  border-radius: 8px; object-fit: contain; }
.sd-candidate-loading { display: flex; align-items: center; justify-content: center;
  aspect-ratio: 16 / 9; background: var(--wb-fill); border-radius: 8px; color: var(--wb-muted); font-size: 12.5px; }
.sd-failed { color: #b45309; }
.sd-candidate-meta { display: flex; align-items: center; gap: 8px; margin-top: 5px; flex-wrap: wrap; }
.sd-candidate-actions { margin-left: auto; display: flex; gap: 6px; }
.sd-upload { display: flex; flex-direction: column; gap: 4px; margin-top: 8px; }
</style>
