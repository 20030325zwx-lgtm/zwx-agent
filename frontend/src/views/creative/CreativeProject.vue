<template>
  <div class="cr-page studio">
    <header class="studio-bar">
      <div class="studio-bar-left">
        <router-link to="/creative" class="studio-back" aria-label="返回创作首页">
          <ArrowLeft :size="16" />
        </router-link>
        <div class="studio-title-wrap">
          <h1 class="studio-title">{{ project?.name || '创作台' }}</h1>
          <span v-if="project" class="cr-kind-badge">{{ kindLabel(project.kind) }}创作</span>
          <span v-if="project" class="cr-status" :class="project.status === 'ACTIVE' ? 'success' : 'muted'">
            {{ project.status === 'ACTIVE' ? '进行中' : '已归档' }}
          </span>
        </div>
      </div>
      <nav class="studio-tabs" aria-label="创作台视图">
        <button type="button" :class="{ active: tab === 'studio' }" @click="tab = 'studio'">创作</button>
        <button type="button" :class="{ active: tab === 'tasks' }" @click="tab = 'tasks'">
          任务记录<span v-if="activeCount" class="tab-badge">{{ activeCount }}</span>
        </button>
        <button type="button" :class="{ active: tab === 'assets' }" @click="tab = 'assets'">素材库</button>
      </nav>
      <div class="studio-bar-right">
        <router-link to="/creative/assets" class="cr-ghost">全部素材</router-link>
      </div>
    </header>

    <div v-if="pageError" class="studio-error"><span class="cr-error">{{ pageError }}</span></div>

    <!-- 创作视图 -->
    <div v-if="tab === 'studio'" class="studio-body">
      <section class="studio-results" aria-label="生成结果">
        <div v-if="project && project.kind !== 'IMAGE'" class="cr-banner studio-banner">
          <AlertCircle :size="18" />
          <div class="cr-banner-body">
            <strong>{{ kindLabel(project.kind) }}创作尚未开放</strong>
            <p>项目与草稿已保存；该类型的生成链路上线后可直接继续使用，无需重建项目。</p>
          </div>
        </div>
        <div v-else-if="!imageBinding" class="cr-banner studio-banner">
          <AlertCircle :size="18" />
          <div class="cr-banner-body">
            <strong>还没有配置图片生成模型</strong>
            <p>可以先输入并保存草稿；提交生成前需要完成配置。</p>
          </div>
          <router-link to="/settings/model" class="cr-banner-link">去配置</router-link>
        </div>

        <p v-if="cleanJobList.length === 0 && !jobsLoading" class="cr-empty studio-empty">
          还没有生成记录。在右侧输入想法，点击「生成图片」。
        </p>

        <article v-for="job in cleanJobList" :key="job.jobId" class="job-card">
          <div class="job-card-head">
            <span class="cr-status" :class="statusMeta(job.status).tone">{{ statusMeta(job.status).label }}</span>
            <span class="job-card-time">{{ formatTime(job.createdAt) }}</span>
          </div>
          <p class="job-card-hint">{{ statusMeta(job.status).hint }}</p>
          <p v-if="job.prompt" class="job-card-prompt">「{{ job.prompt }}」</p>

          <div v-if="jobImages(job).length" class="job-images">
            <figure v-for="output in jobImages(job)" :key="output.assetId" class="job-image">
              <img v-if="urls[output.assetId]" :src="urls[output.assetId]" :alt="`生成结果 ${output.index + 1}`" />
              <div v-else class="job-image-placeholder">图片加载中…</div>
              <figcaption class="job-image-actions">
                <span class="job-image-meta">{{ output.meta }}</span>
                <button v-if="urls[output.assetId]" type="button" class="cr-ghost"
                        @click="downloadBlob(urls[output.assetId], downloadName(job, output))">下载</button>
              </figcaption>
            </figure>
          </div>

          <p v-if="job.status === 'INGEST_FAILED'" class="cr-error job-card-error">
            图片可能已经在供应商生成，但保存到素材库失败。恢复只会重新下载，不会再次生成。
          </p>
          <p v-else-if="job.status === 'RECONCILING'" class="cr-error job-card-error">
            提交结果未确认，供应商可能已经产生了费用。不会自动重发，请先查询状态。
          </p>
          <p v-else-if="job.error && job.status === 'FAILED'" class="cr-error job-card-error">{{ job.error }}</p>
          <p v-else-if="job.blockedReason" class="cr-hint job-card-error">{{ blockedText(job.blockedReason) }}</p>

          <div class="job-card-actions">
            <button v-if="job.status === 'QUEUED'" type="button" class="cr-secondary"
                    :disabled="busyJob === job.jobId" @click="cancel(job)">取消排队</button>
            <button v-else-if="job.status === 'RUNNING'" type="button" class="cr-secondary"
                    :disabled="busyJob === job.jobId" @click="cancel(job)">请求取消</button>
            <button v-else-if="job.status === 'INGEST_FAILED'" type="button" class="cr-primary"
                    :disabled="busyJob === job.jobId" @click="resumeIngest(job)">重新保存</button>
            <button v-else-if="job.status === 'FAILED' || job.status === 'CANCELLED'" type="button"
                    class="cr-secondary" :disabled="busyJob === job.jobId" @click="retry(job)">重新生成</button>
            <button v-else-if="job.status === 'RECONCILING'" type="button" class="cr-secondary"
                    :disabled="busyJob === job.jobId" @click="retry(job)">确认后重试</button>
            <button v-if="job.status === 'SUCCEEDED'" type="button" class="cr-secondary"
                    @click="continueFrom(job)">继续创作</button>
            <button type="button" class="cr-ghost" @click="openJob(job.jobId)">任务详情</button>
          </div>
        </article>
      </section>

      <aside class="studio-panel" aria-label="创作参数">
        <div class="panel-block">
          <label class="cr-field">
            <span class="cr-field-label">
              <span>你的想法</span>
              <span class="count">{{ idea.length }}/{{ PROMPT_MAX }}</span>
            </span>
            <textarea v-model="idea" class="cr-textarea" rows="4" maxlength="8000"
                      placeholder="描述主体、场景、风格，例如：雨后的城市街角，暖色霓虹，电影感"
                      @input="markDirty" />
          </label>

          <div class="panel-optimize">
            <button type="button" class="cr-secondary" :disabled="optimizing || !canOptimize" @click="optimize">
              <Sparkles :size="14" /> {{ optimizing ? '优化中…' : '优化提示词' }}
            </button>
            <span class="cr-field-help">只改写文本，不会触发生成</span>
          </div>

          <div v-if="optimizeError" class="cr-error">{{ optimizeError }}</div>

          <div v-if="suggestion" class="suggestion">
            <div class="suggestion-head">
              <strong>优化建议</strong>
              <span class="cr-field-help">{{ suggestionLength }}/{{ PROMPT_MAX }}</span>
            </div>
            <p class="suggestion-text">{{ suggestion }}</p>
            <div class="suggestion-actions">
              <button type="button" class="cr-primary" :disabled="suggestionLength > PROMPT_MAX"
                      @click="adoptSuggestion">采用</button>
              <button type="button" class="cr-ghost" @click="suggestion = ''">放弃</button>
            </div>
            <p v-if="suggestionLength > PROMPT_MAX" class="cr-error">
              优化结果超过模型上限 {{ PROMPT_MAX }} 字，请缩短后重试或直接使用原文。
            </p>
          </div>

          <label class="cr-field final-field">
            <span class="cr-field-label">
              <span>最终提示词</span>
              <span class="count">{{ effective.length }}/{{ PROMPT_MAX }}</span>
            </span>
            <textarea v-model="effective" class="cr-textarea" rows="4" maxlength="8000"
                      @input="finalSynced = false; markDirty()" />
            <span class="cr-field-help">
              <template v-if="finalSynced">与想法一致；提交时使用这段文本。</template>
              <template v-else>已单独修改。<button type="button" class="inline-link"
                  @click="restoreOriginal">恢复与想法一致</button></template>
            </span>
          </label>
        </div>

        <div class="panel-block">
          <label class="cr-field">
            <span class="cr-field-label"><span>尺寸</span></span>
            <select v-model="size" class="cr-select" @change="markDirty">
              <option value="1024*1024">1024 × 1024（方形）</option>
              <option value="1280*720">1280 × 720（横版）</option>
              <option value="720*1280">720 × 1280（竖版）</option>
              <option value="1152*768">1152 × 768（横版）</option>
              <option value="768*1152">768 × 1152（竖版）</option>
            </select>
          </label>
          <label class="panel-switch">
            <input v-model="watermark" type="checkbox" @change="markDirty" />
            <span>添加 AI 生成水印</span>
          </label>
          <p class="cr-field-help">输出 1 张；供应商计费，当前无法估算金额。</p>
        </div>

        <div class="panel-block">
          <div class="model-line">
            <span class="cr-field-help">图片模型</span>
            <span class="model-name">{{ imageBinding ? `${imageBinding.providerName} / ${imageBinding.modelName}` : '未配置' }}</span>
          </div>
          <div class="save-line">
            <span>{{ saveStateText }}</span>
            <button v-if="conflict" type="button" class="inline-link" @click="resolveConflict">另存为新草稿</button>
          </div>
          <p v-if="submitError" class="cr-error">{{ submitError }}</p>
          <button type="button" class="cr-primary generate-btn"
                  :disabled="!canGenerate || generating" @click="generate">
            <ImagePlus :size="16" /> {{ generating ? '提交中…' : '生成图片' }}
          </button>
          <p v-if="project && project.status !== 'ACTIVE'" class="cr-field-help">项目已归档，不能发起新任务。</p>
        </div>
      </aside>
    </div>

    <!-- 任务记录 -->
    <div v-else-if="tab === 'tasks'" class="cr-scroll studio-sub">
      <div class="cr-inner">
        <div class="cr-section-head">
          <h2>任务记录</h2>
          <span class="cr-field-help">{{ jobs.length }} 条</span>
        </div>
        <p v-if="!jobs.length" class="cr-empty">还没有任务。</p>
        <table v-else class="job-table">
          <thead>
            <tr><th>状态</th><th>提示词</th><th>创建时间</th><th>更新时间</th><th>操作</th></tr>
          </thead>
          <tbody>
            <tr v-for="job in jobs" :key="job.jobId">
              <td><span class="cr-status" :class="statusMeta(job.status).tone">{{ statusMeta(job.status).label }}</span></td>
              <td class="job-table-prompt">{{ job.prompt || '—' }}</td>
              <td>{{ formatTime(job.createdAt) }}</td>
              <td>{{ formatTime(job.updatedAt) }}</td>
              <td><button type="button" class="cr-ghost" @click="openJob(job.jobId)">详情</button></td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>

    <!-- 素材库 -->
    <div v-else class="cr-scroll studio-sub">
      <div class="cr-inner">
        <div class="cr-section-head">
          <h2>项目素材</h2>
          <label class="cr-secondary upload-label">
            <Upload :size="14" /> 上传图片
            <input type="file" accept="image/png,image/jpeg,image/webp" hidden @change="upload" />
          </label>
        </div>
        <p v-if="assetError" class="cr-error">{{ assetError }}</p>
        <p v-if="!assets.length" class="cr-empty">还没有素材。生成结果会自动进入这里；也可以上传 PNG/JPEG/WebP。</p>
        <div v-else class="asset-grid">
          <figure v-for="asset in assets" :key="asset.id" class="asset-card">
            <img v-if="urls[asset.id]" :src="urls[asset.id]" :alt="asset.displayName" />
            <div v-else class="asset-placeholder">加载中…</div>
            <figcaption>
              <span class="asset-name">{{ asset.displayName }}</span>
              <span class="asset-meta">{{ formatBytes(asset.byteSize) }} · {{ formatTime(asset.createdAt) }}</span>
              <span class="asset-actions">
                <button type="button" class="cr-ghost" :aria-pressed="asset.starred"
                        @click="toggleStar(asset)">{{ asset.starred ? '已收藏' : '收藏' }}</button>
                <button type="button" class="cr-ghost" @click="downloadAsset(asset)">下载</button>
                <button v-if="asset.jobId" type="button" class="cr-ghost" @click="continueFromAsset(asset)">继续创作</button>
                <button type="button" class="cr-ghost danger" @click="removeAsset(asset)">删除</button>
              </span>
            </figcaption>
          </figure>
        </div>
      </div>
    </div>

    <!-- 任务详情抽屉 -->
    <div v-if="detailJob" class="detail-backdrop" @click.self="closeJob">
      <aside class="detail-panel" role="dialog" aria-modal="true" aria-label="任务详情">
        <header class="detail-head">
          <div>
            <h2>任务详情</h2>
            <p class="cr-field-help">{{ detailJob.jobId }}</p>
          </div>
          <button type="button" class="cr-ghost" @click="closeJob">关闭</button>
        </header>
        <dl class="detail-list">
          <div><dt>状态</dt><dd><span class="cr-status" :class="statusMeta(detailJob.status).tone">{{ statusMeta(detailJob.status).label }}</span></dd></div>
          <div><dt>提示词</dt><dd class="detail-prompt">{{ detailJob.prompt || '—' }}</dd></div>
          <div><dt>模型</dt><dd>{{ detailJob.modelName || '—' }}</dd></div>
          <div><dt>尺寸</dt><dd>{{ detailJob.parameters?.size || '—' }}</dd></div>
          <div><dt>创建时间</dt><dd>{{ formatTime(detailJob.createdAt) }}</dd></div>
          <div><dt>更新时间</dt><dd>{{ formatTime(detailJob.updatedAt) }}</dd></div>
          <div v-if="detailJob.providerTaskId"><dt>供应商任务</dt><dd class="mono">{{ detailJob.providerTaskId }}</dd></div>
          <div v-if="detailJob.error"><dt>错误</dt><dd class="cr-error">{{ detailJob.error }}</dd></div>
          <div v-if="detailJob.blockedReason"><dt>说明</dt><dd>{{ blockedText(detailJob.blockedReason) }}</dd></div>
        </dl>
        <div v-if="detailJob.outputs?.length" class="detail-outputs">
          <h3>产物</h3>
          <div v-for="output in detailJob.outputs" :key="output.assetId" class="detail-output">
            <img v-if="urls[output.assetId]" :src="urls[output.assetId]" :alt="`产物 ${output.index + 1}`" />
            <div class="detail-output-meta">
              <span>{{ output.state === 'DONE' ? '已保存' : output.state }}</span>
              <span>{{ formatBytes(output.byteSize) }}</span>
            </div>
            <button v-if="urls[output.assetId]" type="button" class="cr-secondary"
                    @click="downloadBlob(urls[output.assetId], `creative-${output.assetId}.png`)">下载原图</button>
          </div>
        </div>
        <div class="detail-actions">
          <button v-if="['QUEUED','RUNNING'].includes(detailJob.status)" type="button" class="cr-secondary"
                  :disabled="busyJob === detailJob.jobId" @click="cancel(detailJob)">取消任务</button>
          <button v-if="detailJob.status === 'INGEST_FAILED'" type="button" class="cr-primary"
                  :disabled="busyJob === detailJob.jobId" @click="resumeIngest(detailJob)">重新保存</button>
          <button v-if="['FAILED','CANCELLED','RECONCILING'].includes(detailJob.status)" type="button"
                  class="cr-secondary" :disabled="busyJob === detailJob.jobId" @click="retry(detailJob)">重试</button>
        </div>
      </aside>
    </div>

    <p v-if="toast.text" class="cr-toast" :class="toast.tone">{{ toast.text }}</p>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { AlertCircle, ArrowLeft, ImagePlus, Sparkles, Upload } from 'lucide-vue-next'
import {
  cancelCreativeJob, createCreativeDraft, createCreativeJob, deleteCreativeAsset,
  getCreativeJob, getCreativeProject, listCreativeAssets, listCreativeDrafts, listCreativeJobs,
  listModelCatalog, optimizeCreativePrompt, resumeCreativeJobIngest, retryCreativeJob,
  updateCreativeAsset, updateCreativeDraft, uploadCreativeAsset
} from '../../api'
import { useAssetUrls, downloadBlob, formatBytes, formatTime, kindLabel, statusMeta } from './shared'

const PROMPT_MAX = 500
const route = useRoute()
const router = useRouter()
const { urls, urlFor } = useAssetUrls()

const projectId = computed(() => route.params.projectId)
const project = ref(null)
const imageBinding = ref(null)
const pageError = ref('')
const tab = ref('studio')

const idea = ref('')
const effective = ref('')
const finalSynced = ref(true)
const size = ref('1024*1024')
const watermark = ref(false)

const draft = ref(null)
const saveState = ref('idle') // idle | saving | saved | error
const conflict = ref(false)
let saveTimer = null
let savePromise = null
let lastSaved = { original: '', effective: '' }

const optimizing = ref(false)
const optimizeError = ref('')
const suggestion = ref('')

const jobs = ref([])
const jobsLoading = ref(false)
const details = ref({})
const generating = ref(false)
const submitError = ref('')
let pendingKey = null
let pollTimer = null

const detailJob = ref(null)
const busyJob = ref('')
const toast = ref({ text: '', tone: '' })

const assets = ref([])
const assetError = ref('')

const canOptimize = computed(() => (idea.value.trim() || effective.value.trim()).length > 0)
const canGenerate = computed(() => project.value?.status === 'ACTIVE'
  && project.value?.kind === 'IMAGE'
  && !!imageBinding.value && (idea.value.trim() || effective.value.trim()).length > 0)
const activeCount = computed(() => jobs.value.filter(job => !['SUCCEEDED', 'FAILED', 'CANCELLED'].includes(job.status)).length)
const cleanJobList = computed(() => jobs.value)
const suggestionLength = computed(() => (suggestion.value || '').length)
const saveStateText = computed(() => {
  if (conflict.value) return '草稿冲突：其他页签已修改'
  if (saveState.value === 'saving') return '草稿保存中…'
  if (saveState.value === 'saved') return '草稿已保存'
  if (saveState.value === 'error') return '草稿保存失败'
  return draft.value ? '草稿已保存' : '输入后自动保存草稿'
})

function showToast(text, tone = '') {
  toast.value = { text, tone }
  window.clearTimeout(showToast.timer)
  showToast.timer = window.setTimeout(() => { toast.value = { text: '', tone: '' } }, 3600)
}

function blockedText(reason) {
  return {
    SUBMISSION_UNCERTAIN: '提交结果未确认：不会自动重发，可稍后再查询。',
    SCHEDULER_ERROR: '调度出现异常，已标记为需要关注。'
  }[reason] || reason
}

function jobImages(job) {
  return (job.outputs || []).filter(output => output.state === 'DONE').map(output => ({
    ...output,
    meta: `${formatBytes(output.byteSize)}`
  }))
}

function downloadName(job, output) {
  return `creative-${job.jobId.slice(0, 8)}-${output.index + 1}.png`
}

async function loadProject() {
  pageError.value = ''
  try {
    project.value = await getCreativeProject(projectId.value)
  } catch (error) {
    pageError.value = error.response?.status === 404
      ? '创作项目不存在或无权访问'
      : (error.response?.data?.error || `项目加载失败：${error.message || '未知错误'}`)
    return
  }
  await Promise.all([loadCatalog(), loadDrafts(), loadJobs(), loadAssets()])
}

async function loadCatalog() {
  try {
    const catalog = await listModelCatalog()
    imageBinding.value = catalog.userBindings?.image_generation
      || catalog.systemBindings?.image_generation || null
  } catch (error) {
    imageBinding.value = null
  }
}

async function loadDrafts() {
  try {
    const drafts = await listCreativeDrafts(projectId.value, 5)
    const latest = drafts[0]
    if (!latest) return
    draft.value = latest
    idea.value = latest.originalPrompt || ''
    effective.value = latest.effectivePrompt || latest.originalPrompt || ''
    finalSynced.value = (latest.effectivePrompt || '') === (latest.originalPrompt || '')
    lastSaved = { original: idea.value.trim(), effective: effective.value.trim() }
    saveState.value = 'saved'
  } catch (error) {
    saveState.value = 'error'
  }
}

async function loadJobs() {
  jobsLoading.value = true
  try {
    const data = await listCreativeJobs({ projectId: projectId.value, limit: 20 })
    jobs.value = data.items || []
    await refreshDetails()
  } catch (error) {
    pageError.value = error.response?.data?.error || '任务记录加载失败'
  } finally {
    jobsLoading.value = false
  }
  schedulePoll()
}

async function refreshDetails() {
  const targets = jobs.value
  const results = await Promise.allSettled(targets.map(job => getCreativeJob(job.jobId)))
  const next = { ...details.value }
  results.forEach((result, index) => {
    if (result.status === 'fulfilled') next[targets[index].jobId] = result.value
  })
  details.value = next
  // 预取所有产物图片（鉴权接口 → blob）
  Object.values(next).forEach(job => {
    (job.outputs || []).forEach(output => {
      if (output.state === 'DONE') urlFor(output.assetId)
    })
  })
  if (detailJob.value) detailJob.value = next[detailJob.value.jobId] || detailJob.value
  mergeJobsWithDetails()
}

function mergeJobsWithDetails() {
  jobs.value = jobs.value.map(job => ({ ...job, ...(details.value[job.jobId] || {}) }))
}

function schedulePoll() {
  window.clearTimeout(pollTimer)
  const active = jobs.value.some(job => !['SUCCEEDED', 'FAILED', 'CANCELLED'].includes(job.status))
  if (!active) return
  pollTimer = window.setTimeout(async () => {
    if (document.visibilityState === 'hidden') {
      schedulePoll()
      return
    }
    try {
      const data = await listCreativeJobs({ projectId: projectId.value, limit: 20 })
      jobs.value = (data.items || []).map(job => ({ ...job, ...(details.value[job.jobId] || {}) }))
      await refreshDetails()
    } catch (error) { /* 下一次轮询重试 */ }
    schedulePoll()
  }, 2500)
}

async function loadAssets() {
  try {
    const data = await listCreativeAssets({ projectId: projectId.value, limit: 60 })
    assets.value = data.items || []
    assets.value.forEach(asset => urlFor(asset.id))
  } catch (error) {
    assetError.value = '素材加载失败'
  }
}

function markDirty() {
  if (finalSynced.value) effective.value = idea.value
  saveState.value = 'idle'
  window.clearTimeout(saveTimer)
  saveTimer = window.setTimeout(saveDraft, 1200)
}

function restoreOriginal() {
  effective.value = idea.value
  finalSynced.value = true
  markDirty()
}

function persistDraft(originalPrompt, effectivePrompt) {
  if (!draft.value) {
    return createCreativeDraft(projectId.value, {
      operation: 'text_to_image', originalPrompt, effectivePrompt
    })
  }
  return updateCreativeDraft(draft.value.id, {
    operation: 'text_to_image', originalPrompt, effectivePrompt, expectedVersion: draft.value.version
  })
}

async function saveDraft() {
  const originalPrompt = idea.value.trim()
  const effectivePrompt = (effective.value || idea.value).trim()
  if (!originalPrompt && !effectivePrompt) return
  if (draft.value && lastSaved.original === originalPrompt && lastSaved.effective === effectivePrompt) return
  // 同一时刻只允许一个保存请求，避免自动保存与“立即生成”各发一次导致 409
  if (savePromise) {
    await savePromise.catch(() => {})
    return saveDraft()
  }
  saveState.value = 'saving'
  savePromise = persistDraft(originalPrompt, effectivePrompt)
  try {
    draft.value = await savePromise
    lastSaved = { original: originalPrompt, effective: effectivePrompt }
    saveState.value = 'saved'
    conflict.value = false
  } catch (error) {
    if (error.response?.status === 409) {
      conflict.value = true
    }
    saveState.value = 'error'
  } finally {
    savePromise = null
  }
}

async function resolveConflict() {
  try {
    draft.value = await createCreativeDraft(projectId.value, {
      operation: 'text_to_image',
      originalPrompt: idea.value.trim(),
      effectivePrompt: (effective.value || idea.value).trim()
    })
    conflict.value = false
    lastSaved = { original: idea.value.trim(), effective: (effective.value || idea.value).trim() }
    saveState.value = 'saved'
    showToast('已另存为新草稿', 'success')
  } catch (error) {
    showToast('另存失败，请重试', 'error')
  }
}

async function optimize() {
  optimizing.value = true
  optimizeError.value = ''
  suggestion.value = ''
  try {
    const result = await optimizeCreativePrompt({ prompt: (effective.value || idea.value).trim() })
    suggestion.value = result.optimizedPrompt
  } catch (error) {
    optimizeError.value = error.response?.data?.error || '提示词优化失败，可直接使用原文生成'
  } finally {
    optimizing.value = false
  }
}

function adoptSuggestion() {
  effective.value = suggestion.value
  finalSynced.value = false
  suggestion.value = ''
  markDirty()
  showToast('已采用优化提示词，原文仍保留', 'success')
}

function newIdempotencyKey() {
  if (window.crypto?.randomUUID) return window.crypto.randomUUID()
  return `key-${Date.now()}-${Math.random().toString(16).slice(2)}`
}

async function generate() {
  if (!canGenerate.value || generating.value) return
  if (finalSynced.value) effective.value = idea.value
  const prompt = (effective.value || idea.value).trim()
  submitError.value = ''
  generating.value = true
  window.clearTimeout(saveTimer)
  // 同一意图复用幂等键：网络失败重试不会重复计费；4xx 明确拒绝后才换新键
  if (!pendingKey) pendingKey = newIdempotencyKey()
  try {
    await saveDraft()
    const result = await createCreativeJob(pendingKey, {
      projectId: projectId.value,
      operation: 'text_to_image',
      originalPrompt: idea.value.trim(),
      effectivePrompt: prompt,
      parameters: { size: size.value, watermark: watermark.value }
    })
    pendingKey = null
    showToast(result.reused ? '已复用原任务，未重复提交' : '任务已受理，正在生成', 'success')
    await loadJobs()
  } catch (error) {
    const status = error.response?.status
    if (!status) {
      submitError.value = '网络中断，任务可能已提交。等待后可在任务记录中确认，不要立刻重复提交。'
    } else {
      pendingKey = null
      submitError.value = error.response?.data?.error || '提交失败，请稍后重试'
    }
  } finally {
    generating.value = false
  }
}

async function cancel(job) {
  if (job.status === 'RUNNING' && !window.confirm('取消后供应商可能仍完成生成并产生费用，确定请求取消吗？')) return
  busyJob.value = job.jobId
  try {
    await cancelCreativeJob(job.jobId)
    showToast(job.status === 'RUNNING' ? '已请求取消，结果仍可能产生' : '任务已取消', 'success')
    await loadJobs()
  } catch (error) {
    showToast(error.response?.data?.error || '取消失败', 'error')
  } finally {
    busyJob.value = ''
  }
}

async function retryJob(job, acknowledge = false) {
  busyJob.value = job.jobId
  try {
    const result = await retryCreativeJob(job.jobId, { acknowledgePossibleDuplicateCharge: acknowledge })
    showToast('已创建新的生成任务', 'success')
    await loadJobs()
    return result
  } catch (error) {
    showToast(error.response?.data?.error || '重试失败', 'error')
    return null
  } finally {
    busyJob.value = ''
  }
}

function retry(job) {
  if (job.status === 'RECONCILING') {
    if (!window.confirm('原任务结果未确认，重试可能产生第二次费用。确认要继续吗？')) return
    return retryJob(job, true)
  }
  return retryJob(job, false)
}

async function resumeIngest(job) {
  busyJob.value = job.jobId
  try {
    await resumeCreativeJobIngest(job.jobId)
    showToast('已请求重新保存，不会重新生成图片', 'success')
    await loadJobs()
  } catch (error) {
    showToast(error.response?.data?.error || '恢复失败', 'error')
  } finally {
    busyJob.value = ''
  }
}

function continueFrom(job) {
  const detail = details.value[job.jobId] || job
  idea.value = detail.prompt || ''
  effective.value = detail.prompt || ''
  finalSynced.value = true
  if (detail.parameters?.size) size.value = detail.parameters.size
  if (typeof detail.parameters?.watermark === 'boolean') watermark.value = detail.parameters.watermark
  tab.value = 'studio'
  markDirty()
  showToast('已带入原提示词，可修改后再次生成', 'success')
}

function continueFromAsset(asset) {
  if (asset.jobId) {
    getCreativeJob(asset.jobId).then(job => continueFrom(job)).catch(() => {
      showToast('原任务信息不可用', 'error')
    })
  }
}

function openJob(jobId) {
  if (details.value[jobId]) {
    detailJob.value = details.value[jobId]
    return
  }
  getCreativeJob(jobId).then(job => { detailJob.value = job }).catch(() => showToast('任务详情加载失败', 'error'))
}

function closeJob() {
  detailJob.value = null
  if (route.params.jobId) router.replace(`/creative/projects/${projectId.value}`)
}

async function upload(event) {
  const file = event.target.files?.[0]
  event.target.value = ''
  if (!file) return
  assetError.value = ''
  try {
    const asset = await uploadCreativeAsset(projectId.value, file)
    assets.value = [asset, ...assets.value]
    urlFor(asset.id)
    showToast('素材已上传', 'success')
  } catch (error) {
    assetError.value = error.response?.data?.error || '上传失败'
  }
}

async function toggleStar(asset) {
  try {
    const updated = await updateCreativeAsset(asset.id, { starred: !asset.starred })
    const index = assets.value.findIndex(item => item.id === asset.id)
    if (index >= 0) assets.value[index] = updated
  } catch (error) {
    showToast('收藏失败', 'error')
  }
}

function downloadAsset(asset) {
  downloadBlob(urls[asset.id], `${asset.displayName || asset.id}.png`)
}

async function removeAsset(asset) {
  if (!window.confirm('删除后不可恢复（历史任务记录保留），确定删除这个素材吗？')) return
  try {
    await deleteCreativeAsset(asset.id)
    assets.value = assets.value.filter(item => item.id !== asset.id)
    showToast('素材已删除', 'success')
  } catch (error) {
    showToast(error.response?.data?.error || '删除失败', 'error')
  }
}

watch(tab, value => {
  if (value === 'assets') loadAssets()
})

watch(() => route.params.projectId, () => {
  draft.value = null
  jobs.value = []
  details.value = {}
  assets.value = []
  loadProject()
})

onMounted(loadProject)
onBeforeUnmount(() => {
  window.clearTimeout(saveTimer)
  window.clearTimeout(pollTimer)
})
</script>

<style scoped src="./creative.css"></style>

<style scoped>
.studio { flex: 1; min-height: 0; display: flex; flex-direction: column; }

.studio-bar {
  display: flex;
  align-items: center;
  gap: 16px;
  padding: 10px 18px;
  border-bottom: 1px solid var(--wb-border);
  background: var(--wb-surface);
}

.studio-bar-left { display: flex; align-items: center; gap: 10px; min-width: 0; }
.studio-back {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 30px;
  height: 30px;
  border-radius: 8px;
  color: var(--wb-text-2);
}
.studio-back:hover { background: var(--wb-fill); color: var(--wb-text); }
.studio-title-wrap { display: flex; align-items: center; gap: 8px; min-width: 0; }
.cr-kind-badge {
  font-size: 11px;
  padding: 1px 7px;
  border-radius: 999px;
  border: 1px solid var(--wb-border);
  color: var(--wb-text-2);
  white-space: nowrap;
}
.studio-title {
  margin: 0;
  font-size: 15px;
  font-weight: 600;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  max-width: 320px;
}

.studio-tabs { display: flex; gap: 2px; margin-left: auto; }
.studio-tabs button {
  white-space: nowrap;
  border: none;
  background: transparent;
  color: var(--wb-text-2);
  font-size: 13px;
  padding: 6px 12px;
  border-radius: 8px;
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  gap: 6px;
}
.studio-tabs button:hover { background: var(--wb-fill); color: var(--wb-text); }
.studio-tabs button.active { background: var(--wb-brand-soft); color: var(--wb-brand); font-weight: 600; }
.tab-badge {
  font-size: 10.5px;
  padding: 0 6px;
  border-radius: 999px;
  background: var(--wb-brand);
  color: var(--wb-brand-contrast);
  line-height: 16px;
}
.studio-bar-right { display: flex; align-items: center; gap: 6px; }

.studio-error { padding: 12px 18px 0; }

.studio-body {
  flex: 1;
  min-height: 0;
  display: grid;
  grid-template-columns: minmax(0, 1fr) 360px;
}

.studio-results {
  min-width: 0;
  overflow-y: auto;
  padding: 18px;
  display: flex;
  flex-direction: column;
  gap: 14px;
  background: var(--wb-bg);
}

.studio-banner { align-items: center; }
.studio-empty { margin: 0; }

.job-card {
  border: 1px solid var(--wb-border);
  border-radius: var(--wb-radius-lg);
  background: var(--wb-surface);
  padding: 14px 16px;
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.job-card-head { display: flex; align-items: center; justify-content: space-between; }
.job-card-time { font-size: 11.5px; color: var(--wb-muted); }
.job-card-hint { margin: 0; font-size: 12.5px; color: var(--wb-text-2); }
.job-card-prompt {
  margin: 0;
  font-size: 13px;
  line-height: 1.6;
  color: var(--wb-text);
  display: -webkit-box;
  -webkit-line-clamp: 3;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.job-card-error { margin: 0; }
.job-card-actions { display: flex; flex-wrap: wrap; gap: 8px; align-items: center; }

.job-images {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
}
.job-image {
  margin: 0;
  width: min(320px, 100%);
  border: 1px solid var(--wb-border);
  border-radius: var(--wb-radius);
  overflow: hidden;
  background: var(--wb-fill);
}
.job-image img { display: block; width: 100%; height: auto; max-height: 360px; object-fit: contain; }
.job-image-placeholder {
  aspect-ratio: 1 / 1;
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--wb-muted);
  font-size: 12px;
}
.job-image-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 6px 10px;
  font-size: 11.5px;
  color: var(--wb-muted);
}

.studio-panel {
  border-left: 1px solid var(--wb-border);
  background: var(--wb-surface);
  overflow-y: auto;
  padding: 16px;
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.panel-block { display: flex; flex-direction: column; gap: 12px; }
.panel-optimize { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.final-field { margin-top: 4px; }
.final-field .cr-field-help { margin-top: 2px; }
.inline-link {
  border: none;
  background: none;
  padding: 0;
  color: var(--wb-brand);
  font-size: inherit;
  cursor: pointer;
  text-decoration: underline;
}

.suggestion {
  border: 1px solid var(--wb-border);
  border-radius: var(--wb-radius);
  padding: 10px 12px;
  background: var(--wb-fill);
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.suggestion-head { display: flex; justify-content: space-between; align-items: baseline; }
.suggestion-head strong { font-size: 12.5px; }
.suggestion-text { margin: 0; font-size: 12.5px; line-height: 1.62; color: var(--wb-text); }
.suggestion-actions { display: flex; gap: 8px; }

.panel-switch { display: flex; align-items: center; gap: 8px; font-size: 12.5px; color: var(--wb-text-2); }
.model-line, .save-line {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 10px;
  font-size: 12.5px;
}
.model-name { font-weight: 550; color: var(--wb-text); text-align: right; }
.save-line { color: var(--wb-muted); }
.generate-btn { justify-content: center; padding: 10px 14px; }

.studio-sub { flex: 1; min-height: 0; }

.job-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 12.5px;
  background: var(--wb-surface);
  border: 1px solid var(--wb-border);
  border-radius: var(--wb-radius-lg);
  overflow: hidden;
}
.job-table th, .job-table td { padding: 10px 12px; text-align: left; border-bottom: 1px solid var(--wb-border); }
.job-table th { color: var(--wb-muted); font-weight: 500; background: var(--wb-fill); }
.job-table tr:last-child td { border-bottom: none; }
.job-table-prompt { max-width: 420px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }

.upload-label { cursor: pointer; }

.asset-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(220px, 1fr));
  gap: 12px;
}
.asset-card {
  margin: 0;
  border: 1px solid var(--wb-border);
  border-radius: var(--wb-radius-lg);
  background: var(--wb-surface);
  overflow: hidden;
  display: flex;
  flex-direction: column;
}
.asset-card img { display: block; width: 100%; height: auto; min-height: 140px; max-height: 320px; object-fit: contain; background: var(--wb-fill); }
.asset-placeholder {
  aspect-ratio: 4 / 3;
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--wb-muted);
  font-size: 12px;
  background: var(--wb-fill);
}
.asset-card figcaption { display: flex; flex-direction: column; gap: 4px; padding: 9px 11px; }
.asset-name { font-size: 12.5px; font-weight: 550; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.asset-meta { font-size: 11px; color: var(--wb-muted); }
.asset-actions { display: flex; flex-wrap: wrap; gap: 2px; margin-top: 2px; }
.asset-actions .danger { color: var(--wb-danger); }

.detail-backdrop {
  position: fixed;
  inset: 0;
  z-index: 45;
  background: rgb(0 0 0 / 32%);
  display: flex;
  justify-content: flex-end;
}
.detail-panel {
  width: min(440px, 100%);
  height: 100%;
  overflow-y: auto;
  background: var(--wb-surface);
  border-left: 1px solid var(--wb-border);
  padding: 18px;
  display: flex;
  flex-direction: column;
  gap: 16px;
  box-shadow: -16px 0 48px rgb(0 0 0 / 18%);
}
.detail-head { display: flex; align-items: flex-start; justify-content: space-between; }
.detail-head h2 { margin: 0; font-size: 16px; }
.detail-list { margin: 0; display: flex; flex-direction: column; gap: 10px; font-size: 12.5px; }
.detail-list > div { display: grid; grid-template-columns: 88px 1fr; gap: 10px; }
.detail-list dt { color: var(--wb-muted); }
.detail-list dd { margin: 0; word-break: break-word; }
.detail-prompt { line-height: 1.6; }
.mono { font-family: var(--wb-font-mono); font-size: 11.5px; overflow-wrap: anywhere; }
.detail-outputs { display: flex; flex-direction: column; gap: 10px; }
.detail-outputs h3 { margin: 0; font-size: 13px; }
.detail-output { border: 1px solid var(--wb-border); border-radius: var(--wb-radius); padding: 8px; display: flex; flex-direction: column; gap: 8px; }
.detail-output img { width: 100%; height: auto; border-radius: 6px; display: block; }
.detail-output-meta { display: flex; justify-content: space-between; font-size: 11.5px; color: var(--wb-muted); }
.detail-actions { display: flex; gap: 8px; flex-wrap: wrap; }

@media (max-width: 1080px) {
  .studio-body { grid-template-columns: minmax(0, 1fr) 320px; }
}

@media (max-width: 900px) {
  .studio-body { display: flex; flex-direction: column; overflow-y: auto; }
  .studio-bar-right { display: none; }
  .cr-kind-badge {
  font-size: 11px;
  padding: 1px 7px;
  border-radius: 999px;
  border: 1px solid var(--wb-border);
  color: var(--wb-text-2);
  white-space: nowrap;
}
.studio-title { max-width: 150px; }
  .studio-results { overflow: visible; }
  .studio-panel { border-left: none; border-top: 1px solid var(--wb-border); overflow: visible; }
  .studio-tabs { margin-left: 0; }
  .studio-bar { flex-wrap: wrap; }
}
</style>
