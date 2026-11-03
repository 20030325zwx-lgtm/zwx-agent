<template>
  <div class="st-body">
    <section class="st-results" aria-label="生成结果">
      <div v-if="project && !['IMAGE', 'VIDEO'].includes(project.kind)" class="cr-banner">
        <AlertCircle :size="18" />
        <div class="cr-banner-body">
          <strong>{{ kindLabel(project.kind) }}创作尚未开放</strong>
          <p>项目与草稿已保存；该类型的生成链路上线后可直接继续使用，无需重建项目。</p>
        </div>
      </div>
      <div v-else-if="isVideoProject && !videoBinding" class="cr-banner">
        <AlertCircle :size="18" />
        <div class="cr-banner-body">
          <strong>还没有配置视频生成模型</strong>
          <p>可以先输入并保存草稿；提交生成前需要在模型设置绑定「视频生成」用途。</p>
        </div>
        <router-link to="/settings/model" class="cr-banner-link">去配置</router-link>
      </div>
      <div v-else-if="!isVideoProject && !imageBinding" class="cr-banner">
        <AlertCircle :size="18" />
        <div class="cr-banner-body">
          <strong>还没有配置图片生成模型</strong>
          <p>可以先输入并保存草稿；提交生成前需要完成配置。</p>
        </div>
        <router-link to="/settings/model" class="cr-banner-link">去配置</router-link>
      </div>

      <p v-if="!jobs.length && !jobsStore.loading.value" class="cr-empty">
        还没有生成记录。在下方输入想法，点击「{{ isVideoProject ? '生成视频' : '生成图片' }}」；结果会在这里出现。
      </p>

      <article v-for="job in jobs" :key="job.jobId" class="job-card">
        <div class="job-card-head">
          <span class="cr-status" :class="statusMeta(job.status).tone">{{ statusMeta(job.status).label }}</span>
          <span class="job-card-time">{{ formatTime(job.createdAt) }}</span>
        </div>
        <p class="job-card-hint">{{ statusMeta(job.status).hint }}</p>
        <p v-if="job.prompt" class="job-card-prompt">「{{ job.prompt }}」</p>

        <div v-if="jobOutputs(job).length" class="job-images">
          <motion.figure v-for="output in jobOutputs(job)" :key="output.assetId" class="job-image"
                         :initial="motionInitial" :animate="{ opacity: 1, y: 0 }"
                         :transition="{ duration: 0.18, ease: [0.22, 1, 0.36, 1] }">
            <video v-if="isVideoJob(job) && videoUrlOf(output.assetId)"
                   :src="videoUrlOf(output.assetId)" controls preload="metadata"></video>
            <img v-else-if="!isVideoJob(job) && urls[output.assetId]"
                 :src="urls[output.assetId]" :alt="`生成结果 ${output.index + 1}`" />
            <div v-else class="job-image-placeholder">{{ isVideoJob(job) ? '视频加载中…' : '图片加载中…' }}</div>
            <figcaption class="job-image-actions">
              <span class="job-image-meta">{{ output.meta }}</span>
              <button v-if="isVideoJob(job) && videoUrlOf(output.assetId)" type="button" class="cr-ghost"
                      @click="downloadBlob(videoUrlOf(output.assetId), downloadName(job, output))">下载</button>
              <button v-else-if="!isVideoJob(job) && urls[output.assetId]" type="button" class="cr-ghost"
                      @click="downloadBlob(urls[output.assetId], downloadName(job, output))">下载</button>
            </figcaption>
          </motion.figure>
        </div>

        <p v-if="job.status === 'INGEST_FAILED'" class="cr-error job-card-error">
          产物可能已经在供应商生成，但保存到素材库失败。恢复只会重新下载，不会再次生成。
        </p>
        <p v-else-if="job.status === 'RECONCILING'" class="cr-error job-card-error">
          提交结果未确认，供应商可能已经产生了费用。不会自动重发，请先查询状态。
        </p>
        <p v-else-if="job.error && job.status === 'FAILED'" class="cr-error job-card-error">{{ job.error }}</p>
        <p v-else-if="job.blockedReason" class="cr-hint job-card-error">{{ blockedText(job.blockedReason) }}</p>

        <div class="job-card-actions">
          <HoldToConfirm v-if="job.status === 'QUEUED'" label="取消排队" :disabled="busyJob === job.jobId"
                         @confirm="jobsStore.cancel(job)" />
          <HoldToConfirm v-else-if="job.status === 'RUNNING'" label="请求取消" :disabled="busyJob === job.jobId"
                         @confirm="jobsStore.cancel(job)" />
          <button v-else-if="job.status === 'INGEST_FAILED'" type="button" class="cr-primary"
                  :disabled="busyJob === job.jobId" @click="jobsStore.resumeIngest(job)">
            <Spinner v-if="busyJob === job.jobId" /> 重新保存
          </button>
          <HoldToConfirm v-else-if="retryNeedsRiskAcknowledgement(job)"
                         label="确认后重试（可能二次计费）"
                         :disabled="busyJob === job.jobId" @confirm="jobsStore.retry(job, true)" />
          <button v-else-if="job.status === 'FAILED' || job.status === 'CANCELLED'" type="button"
                  class="cr-secondary" :disabled="busyJob === job.jobId" @click="jobsStore.retry(job)">重新生成</button>
          <HoldToConfirm v-if="job.status === 'SUCCEEDED' && job.operation === 'image_edit'"
                         label="再做一版（同参考）" :disabled="busyJob === job.jobId"
                         @confirm="redoEdit(job)" />
          <button v-else-if="job.status === 'SUCCEEDED'" type="button" class="cr-secondary"
                  @click="continueFrom(job)">继续创作</button>
          <button type="button" class="cr-ghost" @click="openJob(job.jobId)">任务详情</button>
        </div>
      </article>
    </section>

    <aside class="st-panel" aria-label="创作参数">
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
            <span class="cr-field-help">{{ suggestion.length }}/{{ PROMPT_MAX }}</span>
          </div>
          <p class="suggestion-text">{{ suggestion }}</p>
          <div class="suggestion-actions">
            <button type="button" class="cr-primary" :disabled="suggestion.length > PROMPT_MAX"
                    @click="adoptSuggestion">采用</button>
            <button type="button" class="cr-ghost" @click="suggestion = ''">放弃</button>
          </div>
          <p v-if="suggestion.length > PROMPT_MAX" class="cr-error">
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

      <!-- 技能方案（skill 设计 §5.3）：方案生成不计费，确认后才提交媒体任务 -->
      <div class="panel-block skill-block">
        <div class="model-line">
          <span class="cr-field-help">使用技能（可选）</span>
          <router-link to="/creative/skills" class="inline-link">技能库</router-link>
        </div>
        <DropdownSelect v-model="skillSlug" block :options="skillOptions"
                        placeholder="选择技能生成方案" aria-label="选择技能" />
        <button type="button" class="cr-secondary" :disabled="!skillSlug || skillBusy || !idea.trim()"
                @click="generatePlan">
          <Spinner v-if="skillBusy" /> <Sparkles v-else :size="14" />
          {{ skillBusy ? '生成方案中…' : '生成方案' }}
        </button>
        <span class="cr-field-help">技能按当前想法产出可编辑方案；确认后才会生成，原文保留。</span>

        <p v-if="skillError" class="cr-error">{{ skillError }}</p>

        <div v-if="skillRun && skillRun.planStatus === 'PENDING_CONFIRM'" class="suggestion skill-plan">
          <div class="suggestion-head">
            <strong>技能方案</strong>
            <span class="cr-field-help">{{ skillRun.skillName }}</span>
          </div>
          <p v-if="planSummary" class="suggestion-text">{{ planSummary }}</p>
          <textarea v-model="planPrompt" class="cr-textarea" rows="4" maxlength="8000"
                    aria-label="方案提示词（可编辑）" />
          <p v-for="warning in planWarnings" :key="warning" class="cr-field-help skill-warning">注意：{{ warning }}</p>
          <p v-for="item in planMissing" :key="item" class="cr-field-help skill-warning">待补：{{ item }}</p>
          <div class="suggestion-actions">
            <button v-if="hasShots" type="button" class="cr-primary"
                    :disabled="skillConfirming" @click="confirmShots"
                    title="每个镜头独立任务，失败可单独重试">
              <Spinner v-if="skillConfirming" /> {{ skillConfirming ? '提交中…' : `确认并逐镜生成（${shotCount} 镜）` }}
            </button>
            <button v-else-if="skillRun.mediaOperation" type="button" class="cr-primary"
                    :disabled="skillConfirming || !planPrompt.trim()" @click="confirmPlan">
              <Spinner v-if="skillConfirming" /> {{ skillConfirming ? '提交中…' : '确认并生成' }}
            </button>
            <button v-else type="button" class="cr-primary" :disabled="!planPrompt.trim()"
                    @click="adoptPlanText">采用到输入台</button>
            <button type="button" class="cr-ghost" @click="dismissPlan">放弃</button>
          </div>
          <p class="cr-field-help">原提示词始终保留；方案确认后按现有任务语义执行（可原位重试与恢复）。</p>
        </div>

        <div v-else-if="skillRun && skillRun.planStatus === 'FAILED'" class="skill-plan-failed">
          <p class="cr-error">方案生成失败：{{ skillRun.failReason || '未知原因' }}</p>
          <button type="button" class="cr-secondary" :disabled="skillBusy" @click="retryPlan">
            <Spinner v-if="skillBusy" /> 重试方案
          </button>
        </div>

        <div v-else-if="skillRun && skillRun.planStatus === 'CONFIRMED'">
          <p class="cr-field-help">
            方案已确认{{ skillRun.job ? `，任务${statusMeta(skillRun.job.status).label}` : '' }}；结果见生成记录。
          </p>
          <template v-if="shotJobItems.length">
            <ul class="shot-jobs">
              <li v-for="item in shotJobItems" :key="item.jobId">
                <span class="cr-status" :class="statusMeta(item.status).tone">{{ statusMeta(item.status).label }}</span>
                <span class="shot-job-title">{{ item.shotTitle }}</span>
                <button type="button" class="inline-link" @click="openJob(item.jobId)">详情</button>
              </li>
            </ul>
            <button type="button" class="cr-ghost" :disabled="shotRefreshing" @click="refreshRun">
              <Spinner v-if="shotRefreshing" /> 刷新镜头状态
            </button>
          </template>
        </div>
      </div>

      <div class="panel-block">
        <template v-if="isVideoProject">
          <label class="cr-field">
            <span class="cr-field-label"><span>生成方式</span></span>
            <DropdownSelect v-model="videoOperation" block :options="videoOperationOptions"
                            aria-label="生成方式" @change="markDirty" />
          </label>
          <label v-if="videoOperation === 'image_to_video'" class="cr-field">
            <span class="cr-field-label"><span>首帧图片</span></span>
            <AssetParamField v-model="videoRefAssetId" media-type="IMAGE"
                             :default-project-id="projectId" @update:model-value="markDirty" />
            <span class="cr-field-help">从素材库选择或上传一张图片，作为视频首帧。</span>
          </label>
          <label class="cr-field">
            <span class="cr-field-label"><span>时长（秒）</span></span>
            <input v-model="videoSeconds" class="cr-input" type="text" placeholder="如 5" @input="markDirty" />
          </label>
          <p class="cr-field-help">输出 1 条视频；实测 5 秒约 5 分钟生成，供应商计费金额未知。</p>
          <p class="cr-field-help">分镜级生产在「分镜」页完成；这里用于单条快速试片。</p>
        </template>
        <template v-else>
          <label class="cr-field">
            <span class="cr-field-label"><span>尺寸</span></span>
            <DropdownSelect v-model="size" block :options="sizeOptions"
                            aria-label="图片尺寸" @change="markDirty" />
          </label>
          <label v-if="imageProtocol === 'dashscope'" class="panel-switch">
            <ToggleSwitch v-model="watermark" @update:model-value="markDirty" />
            <span>添加 AI 生成水印</span>
          </label>
          <p class="cr-field-help">输出 1 张；供应商计费，当前无法估算金额。</p>
        </template>
      </div>

      <div class="panel-block wf-launch-block">
        <div class="model-line">
          <span class="cr-field-help">多步创作（如淘宝服饰海报：试穿 + 文案 + 排版）</span>
        </div>
        <button type="button" class="cr-secondary" @click="openWorkflowCreate">
          <Workflow :size="15" /> 用工作流创作
        </button>
      </div>
    </aside>

    <!-- 底部输入台（redesign §5.5）：稳定常驻，结果是主角；提交语义不变 -->
    <footer class="st-composer" aria-label="创作输入台">
      <div class="composer-main">
        <label class="composer-idea">
          <span class="cr-field-help">想法</span>
          <textarea v-model="idea" class="cr-textarea composer-textarea" rows="2" maxlength="8000"
                    placeholder="描述主体、场景、风格…（可先在右侧优化）"
                    @input="markDirty" />
        </label>
        <div v-if="isVideoProject && videoOperation === 'image_to_video' && videoRefAssetId"
             class="composer-ref" :title="`首帧素材 ${videoRefAssetId.slice(0, 8)}…`">
          <ImageIcon :size="14" />
          <span>首帧已选</span>
        </div>
        <div class="composer-side">
          <div class="model-line">
            <span class="cr-field-help">{{ isVideoProject ? '视频模型' : '图片模型' }}</span>
            <span class="model-name">{{ activeBindingName }}</span>
          </div>
          <div class="save-line">
            <span>{{ saveStateText }}</span>
            <button v-if="conflict" type="button" class="inline-link" @click="resolveConflict">另存为新草稿</button>
          </div>
          <p v-if="submitError" class="cr-error">{{ submitError }}</p>
          <button type="button" class="cr-primary generate-btn"
                  :disabled="!canGenerate || generating" @click="generate">
            <Spinner v-if="generating" />
            <ImagePlus v-else :size="16" /> {{ generating ? '提交中…' : (isVideoProject ? '生成视频' : '生成图片') }}
          </button>
          <p v-if="project && project.status !== 'ACTIVE'" class="cr-field-help">项目已归档，不能发起新任务。</p>
        </div>
      </div>
    </footer>

    <!-- 用工作流创作：产物归入本项目，发起后跳到工作流分区 -->
    <div v-if="wfCreateOpen" class="cr-dialog-backdrop" @click.self="wfCreateOpen = false">
      <div class="cr-dialog" role="dialog" aria-modal="true" aria-labelledby="wf-project-create-title">
        <h2 id="wf-project-create-title">用工作流创作 · {{ project?.name }}</h2>
        <p v-if="wfCreate.loading" class="cr-hint">加载工作流…</p>
        <template v-else>
          <label class="cr-field">
            <span class="cr-field-label"><span>工作流模板</span></span>
            <DropdownSelect v-model="wfCreate.workflowKey" block :options="workflowOptions"
                            placeholder="选择工作流" aria-label="工作流模板"
                            @change="loadWorkflowInputs" />
            <span v-if="wfFormHint" class="cr-field-help">{{ wfFormHint }}</span>
          </label>
          <div v-for="field in wfCreate.fields" :key="field.key" class="cr-field">
            <span class="cr-field-label">
              <span>{{ field.label }}<em v-if="field.required" class="req-star">*</em></span>
            </span>
            <AssetParamField
              v-if="field.kind === 'asset' || field.kind === 'assets'"
              :model-value="wfCreate.input[field.key] || ''"
              :multiple="field.kind === 'assets'"
              :default-project-id="projectId"
              @update:model-value="value => wfCreate.input[field.key] = value"
            />
            <input v-else v-model.trim="wfCreate.input[field.key]" class="cr-input" maxlength="4000"
                   :placeholder="field.placeholder || field.label" />
          </div>
          <p v-if="wfCreate.error" class="cr-error">{{ wfCreate.error }}</p>
        </template>
        <div class="cr-dialog-actions">
          <button type="button" class="cr-secondary" @click="wfCreateOpen = false">取消</button>
          <button type="button" class="cr-primary"
                  :disabled="wfCreate.starting || !wfCreate.workflowKey
                    || wfCreate.fields.some(field => field.required && !(wfCreate.input[field.key] || '').trim())"
                  @click="startProjectWorkflow">
            <Spinner v-if="wfCreate.starting" /> {{ wfCreate.starting ? '发起中…' : '发起运行' }}
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, inject, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { AlertCircle, Image as ImageIcon, ImagePlus, Sparkles, Workflow } from 'lucide-vue-next'
import { motion, useReducedMotion } from 'motion-v'
import {
  confirmCreativeSkillRun, confirmCreativeSkillShots, createCreativeDraft, createCreativeJob,
  createCreativeSkillRun, getCreativeHealth, getCreativeSkillRun, getWorkflowDefinition, listCreativeDrafts,
  listCreativeSkills, optimizeCreativePrompt, retryCreativeSkillPlan, startWorkflow, updateCreativeDraft
} from '../../../api'
import { formatBytes, formatTime, kindLabel, retryNeedsRiskAcknowledgement, statusMeta } from '../shared'
import {
  PROMPT_MAX, blockedText, downloadName, isVideoJob, jobOutputs as doneOutputs
} from './helpers'
import { PROJECT_CTX, seedStudio, studioSeed, useWorkflowList } from './composables'
import { buildRunForm } from '../workflowInputs'
import HoldToConfirm from '../../../components/ui/HoldToConfirm.vue'
import Spinner from '../../../components/ui/Spinner.vue'
import ToggleSwitch from '../../../components/ui/ToggleSwitch.vue'
import AssetParamField from '../../../components/workflow/AssetParamField.vue'
import DropdownSelect from '../../../components/ui/DropdownSelect.vue'

const ctx = inject(PROJECT_CTX)
const { projectId, project, imageBinding, videoBinding, imageProtocol, jobsStore, showToast, openJob, urls, videoUrls, router } = ctx

// 结果就绪交叉淡入（redesign §7.2，180ms）；减弱动态效果时直接出现
const reduceMotion = useReducedMotion()
const motionInitial = computed(() => (reduceMotion.value ? false : { opacity: 0, y: 8 }))

const { workflows, ensureLoaded } = useWorkflowList()

const idea = ref('')
const effective = ref('')
const finalSynced = ref(true)
const size = ref('1024*1024')
const watermark = ref(false)
const videoOperation = ref('text_to_video')
const videoSeconds = ref('5')
const videoRefAssetId = ref('')

const draft = ref(null)
const saveState = ref('idle')
const conflict = ref(false)
let saveTimer = null
let savePromise = null
let lastSaved = { original: '', effective: '' }

const optimizing = ref(false)
const optimizeError = ref('')
const suggestion = ref('')
const generating = ref(false)
const submitError = ref('')
let pendingKey = null

const jobs = computed(() => jobsStore.jobs.value)
const busyJob = computed(() => jobsStore.busyJob.value)
const isVideoProject = computed(() => project.value?.kind === 'VIDEO')
const canOptimize = computed(() => (idea.value.trim() || effective.value.trim()).length > 0)

// ---------------------------------------------------------------- 技能方案（skill 设计 §5.3/§6.1）
const skills = ref([])
const skillSlug = ref('')
const skillRun = ref(null)
const skillBusy = ref(false)
const skillConfirming = ref(false)
const skillError = ref('')
const planPrompt = ref('')

const skillOptions = computed(() => [
  { value: '', label: '选择技能（可选）' },
  ...skills.value.filter(skill => skill.compat === 'READY')
    .map(skill => ({ value: skill.slug, label: skill.name }))
])
const selectedSkill = computed(() => skills.value.find(skill => skill.slug === skillSlug.value) || null)
const planSummary = computed(() => skillRun.value?.plan?.summary || '')
const planWarnings = computed(() => skillRun.value?.plan?.warnings || [])
const planMissing = computed(() => skillRun.value?.plan?.missing || [])
// 多镜头方案（P1 MVP）：shots ≥2 时走逐镜生成，每镜独立任务
const hasShots = computed(() => (skillRun.value?.plan?.shots || []).length >= 2
  && skillRun.value?.planStatus === 'PENDING_CONFIRM')
const shotCount = computed(() => (skillRun.value?.plan?.shots || []).length)
const shotJobItems = computed(() => skillRun.value?.shotJobs?.items || [])
const shotRefreshing = ref(false)

async function confirmShots() {
  if (!skillRun.value || skillConfirming.value) return
  skillConfirming.value = true
  skillError.value = ''
  try {
    skillRun.value = await confirmCreativeSkillShots(skillRun.value.runId)
    showToast('逐镜任务已受理，每镜独立执行', 'success')
    await jobsStore.load()
  } catch (exception) {
    skillError.value = exception.response?.data?.error || '逐镜确认失败，请稍后重试'
  } finally {
    skillConfirming.value = false
  }
}

async function refreshRun() {
  if (!skillRun.value || shotRefreshing.value) return
  shotRefreshing.value = true
  try {
    skillRun.value = await getCreativeSkillRun(skillRun.value.runId)
  } catch (exception) { /* 保留当前状态 */ } finally {
    shotRefreshing.value = false
  }
}

async function loadSkills() {
  try {
    const data = await listCreativeSkills()
    skills.value = data.items || []
  } catch (exception) { /* 技能列表失败不阻塞创作台 */ }
}

/** 技能库/首页经 query + sessionStorage 交接：进入创作台自动选中技能并带入想法。 */
function consumeHandoff() {
  const query = router.currentRoute.value.query
  const routeSkill = typeof query.skill === 'string' ? query.skill : ''
  let handoff = null
  try {
    const raw = sessionStorage.getItem('aster.creative.handoff')
    if (raw) {
      sessionStorage.removeItem('aster.creative.handoff')
      handoff = JSON.parse(raw)
    }
  } catch (exception) { /* 忽略 */ }
  const skill = routeSkill || handoff?.skill || ''
  if (skill) skillSlug.value = skill
  // 首帧素材直通（redesign §6 从素材继续创作）：来自素材库「用作首帧」
  if (handoff?.firstFrame) {
    videoOperation.value = 'image_to_video'
    videoRefAssetId.value = handoff.firstFrame
    markDirty()
  }
  if (handoff?.prompt && !idea.value.trim()) {
    idea.value = handoff.prompt
    effective.value = handoff.prompt
    finalSynced.value = true
    markDirty()
  }
  if (skill && idea.value.trim()) {
    // 想法与技能都已就绪：直接生成方案，少一次点击
    generatePlan()
  }
}

/** 媒体操作从技能能力推导；挂在运行对象上，避免用户随后切换下拉造成误判。 */
function withMediaOperation(run) {
  if (!run) return run
  const caps = skills.value.find(skill => skill.slug === run.slug)?.requiredCapabilities || []
  run.mediaOperation = caps.includes('IMAGE_TO_VIDEO') ? 'image_to_video'
    : caps.includes('TEXT_TO_VIDEO') ? 'text_to_video' : null
  return run
}

async function generatePlan() {
  const prompt = idea.value.trim()
  if (!skillSlug.value || !prompt || skillBusy.value) return
  skillBusy.value = true
  skillError.value = ''
  try {
    const caps = selectedSkill.value?.requiredCapabilities || []
    const bindings = caps.includes('IMAGE_TO_VIDEO') && videoRefAssetId.value
      ? [{ assetId: videoRefAssetId.value, role: 'FIRST_FRAME' }]
      : []
    skillRun.value = withMediaOperation(await createCreativeSkillRun(projectId.value, {
      slug: skillSlug.value, prompt, bindings
    }))
    planPrompt.value = skillRun.value?.plan?.prompt || ''
  } catch (exception) {
    skillError.value = exception.response?.data?.error || '方案生成失败，可重试或直接使用原文'
  } finally {
    skillBusy.value = false
  }
}

async function retryPlan() {
  if (!skillRun.value || skillBusy.value) return
  skillBusy.value = true
  skillError.value = ''
  try {
    skillRun.value = withMediaOperation(await retryCreativeSkillPlan(skillRun.value.runId))
    planPrompt.value = skillRun.value?.plan?.prompt || ''
  } catch (exception) {
    skillError.value = exception.response?.data?.error || '方案重试失败'
  } finally {
    skillBusy.value = false
  }
}

/** 确认执行：服务端按 run 派生幂等键创建媒体任务，双击/重传不会重复计费。 */
async function confirmPlan() {
  if (!skillRun.value || skillConfirming.value) return
  skillConfirming.value = true
  skillError.value = ''
  try {
    skillRun.value = await confirmCreativeSkillRun(skillRun.value.runId, {
      effectivePrompt: planPrompt.value.trim(),
      parameters: isVideoProject.value ? { seconds: videoSeconds.value } : {}
    })
    showToast(skillRun.value?.job ? '任务已受理，正在生成' : '方案已确认', 'success')
    await jobsStore.load()
  } catch (exception) {
    skillError.value = exception.response?.data?.error || '确认失败，请稍后重试'
  } finally {
    skillConfirming.value = false
  }
}

/** 纯方案技能（如剧本转分镜）：把方案文本带入输入台，由用户决定下一步。 */
function adoptPlanText() {
  idea.value = planPrompt.value
  effective.value = planPrompt.value
  finalSynced.value = true
  markDirty()
  dismissPlan()
  showToast('方案已带入输入台，原文仍保留', 'success')
}

function dismissPlan() {
  skillRun.value = null
  planPrompt.value = ''
}
const activeBindingName = computed(() => {
  const binding = isVideoProject.value ? videoBinding.value : imageBinding.value
  return binding ? `${binding.providerName} / ${binding.modelName}` : '未配置'
})
const canGenerate = computed(() => project.value?.status === 'ACTIVE'
  && (isVideoProject.value
    ? !!videoBinding.value
    : project.value?.kind === 'IMAGE' && !!imageBinding.value)
  && (idea.value.trim() || effective.value.trim()).length > 0
  && (!isVideoProject.value || videoOperation.value !== 'image_to_video' || !!videoRefAssetId.value))
const saveStateText = computed(() => {
  if (conflict.value) return '草稿冲突：其他页签已修改'
  if (saveState.value === 'saving') return '草稿保存中…'
  if (saveState.value === 'saved') return '草稿已保存'
  if (saveState.value === 'error') return '草稿保存失败'
  return draft.value ? '草稿已保存' : '输入后自动保存草稿'
})

function jobOutputs(job) {
  return doneOutputs(job, formatBytes)
}

function videoUrlOf(assetId) {
  return videoUrls[assetId]?.url || ''
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
  } catch (exception) {
    saveState.value = 'error'
  }
}

function applySeed() {
  if (!studioSeed.nonce) return
  if (studioSeed.idea) {
    idea.value = studioSeed.idea
    effective.value = studioSeed.effective || studioSeed.idea
    finalSynced.value = true
  }
  if (studioSeed.size) size.value = studioSeed.size
  if (typeof studioSeed.watermark === 'boolean') watermark.value = studioSeed.watermark
  markDirty()
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
  // 同一时刻只允许一个保存请求，避免自动保存与「立即生成」各发一次导致 409
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
  } catch (exception) {
    if (exception.response?.status === 409) conflict.value = true
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
  } catch (exception) {
    showToast('另存失败，请重试', 'error')
  }
}

async function optimize() {
  optimizing.value = true
  optimizeError.value = ''
  suggestion.value = ''
  try {
    // 同一输入按项目类型分流策略：图片项目走文生图改写，视频项目走视频提示词策略
    const result = await optimizeCreativePrompt(isVideoProject.value
      ? { prompt: (effective.value || idea.value).trim(), mode: 'VIDEO_PROMPT', projectId: projectId.value }
      : { prompt: (effective.value || idea.value).trim() })
    suggestion.value = result.optimizedPrompt
  } catch (exception) {
    optimizeError.value = exception.response?.data?.error || '提示词优化失败，可直接使用原文生成'
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
    const payload = isVideoProject.value
      ? {
          projectId: projectId.value,
          operation: videoOperation.value,
          originalPrompt: idea.value.trim(),
          effectivePrompt: prompt,
          parameters: { seconds: videoSeconds.value },
          referenceAssetIds: videoOperation.value === 'image_to_video'
            ? [videoRefAssetId.value].filter(Boolean)
            : []
        }
      : {
          projectId: projectId.value,
          operation: 'text_to_image',
          originalPrompt: idea.value.trim(),
          effectivePrompt: prompt,
          parameters: imageProtocol.value === 'dashscope'
            ? { size: size.value, watermark: watermark.value }
            : { size: size.value }
        }
    const result = await createCreativeJob(pendingKey, payload)
    pendingKey = null
    showToast(result.reused ? '已复用原任务，未重复提交' : '任务已受理，正在生成', 'success')
    await jobsStore.load()
  } catch (exception) {
    const status = exception.response?.status
    if (!status) {
      submitError.value = '网络中断，任务可能已提交。等待后可在任务记录中确认，不要立刻重复提交。'
    } else {
      pendingKey = null
      submitError.value = exception.response?.data?.error || '提交失败，请稍后重试'
    }
  } finally {
    generating.value = false
  }
}

async function redoEdit(job) {
  const busy = job.jobId
  jobsStore.busyJob.value = busy
  try {
    await createCreativeJob('redo-' + job.jobId + '-' + Date.now(), {
      projectId: job.projectId,
      operation: 'image_edit',
      originalPrompt: job.prompt,
      effectivePrompt: job.prompt,
      parameters: job.parameters || {},
      referenceAssetIds: job.referenceAssetIds || [],
      parentJobId: job.jobId
    })
    showToast('已受理，正在生成', 'success')
    await jobsStore.load()
  } catch (exception) {
    showToast(exception.response?.data?.error || '提交失败', 'error')
  } finally {
    jobsStore.busyJob.value = ''
  }
}

function continueFrom(job) {
  const detail = jobsStore.details.value[job.jobId] || job
  idea.value = detail.prompt || ''
  effective.value = detail.prompt || ''
  finalSynced.value = true
  if (detail.parameters?.size) size.value = detail.parameters.size
  if (typeof detail.parameters?.watermark === 'boolean') watermark.value = detail.parameters.watermark
  if (isVideoJob(detail)) {
    videoOperation.value = detail.operation || 'text_to_video'
    videoSeconds.value = detail.parameters?.seconds || '5'
    videoRefAssetId.value = detail.referenceAssetIds?.[0] || ''
  }
  markDirty()
  showToast('已带入原提示词，可修改后再次生成', 'success')
}

// ---------------------------------------------------------------- 项目内工作流创作

const wfCreateOpen = ref(false)
const wfCreate = reactive({ loading: false, starting: false, error: '', workflowKey: '', fields: [], input: {} })
const wfFormHint = computed(() => workflows.value.find(item => item.key === wfCreate.workflowKey)?.description || '')
// 视频模型支持的操作由服务端 health 给出（例如 lk888 gk-video 仅图生视频）；加载前保持两项，避免闪动
const videoOps = ref({ text_to_video: true, image_to_video: true })
const videoOperationOptions = computed(() => {
  const options = []
  if (videoOps.value.text_to_video !== false) options.push({ value: 'text_to_video', label: '文生视频' })
  if (videoOps.value.image_to_video !== false) options.push({ value: 'image_to_video', label: '图生视频（首帧）' })
  return options.length ? options : [{ value: 'text_to_video', label: '文生视频' }]
})
const sizeOptions = [
  { value: '1024*1024', label: '1024 × 1024（方形）' },
  { value: '1280*720', label: '1280 × 720（横版）' },
  { value: '720*1280', label: '720 × 1280（竖版）' },
  { value: '1152*768', label: '1152 × 768（横版）' },
  { value: '768*1152', label: '768 × 1152（竖版）' }
]
const workflowOptions = computed(() => workflows.value.map(wf => ({
  value: wf.key, label: `${wf.name || wf.key}（${(wf.steps || []).length} 节点）`
})))

async function openWorkflowCreate() {
  wfCreateOpen.value = true
  wfCreate.error = ''
  wfCreate.workflowKey = ''
  wfCreate.fields = []
  wfCreate.input = {}
  wfCreate.loading = true
  try {
    await ensureLoaded()
    if (!workflows.value.length) wfCreate.error = '还没有可用的工作流'
  } finally {
    wfCreate.loading = false
  }
}

async function loadWorkflowInputs() {
  wfCreate.fields = []
  wfCreate.input = {}
  if (!wfCreate.workflowKey) return
  try {
    const definition = await getWorkflowDefinition(wfCreate.workflowKey)
    wfCreate.fields = buildRunForm(definition)
  } catch (exception) {
    wfCreate.error = '工作流定义加载失败，无法校验运行输入'
  }
}

async function startProjectWorkflow() {
  const missingRequired = wfCreate.fields.some(field => field.required && !(wfCreate.input[field.key] || '').trim())
  if (wfCreate.starting || !wfCreate.workflowKey || missingRequired) return
  wfCreate.starting = true
  wfCreate.error = ''
  try {
    const input = {}
    for (const field of wfCreate.fields) {
      const value = (wfCreate.input[field.key] || '').trim()
      if (value) input[field.key] = value
    }
    const run = await startWorkflow(wfCreate.workflowKey, input.keyword || '', projectId.value, input)
    wfCreateOpen.value = false
    showToast('工作流已发起，产物将归入本项目', 'success')
    router.push(`/creative/projects/${projectId.value}/workflow?runId=${run.id}`)
  } catch (exception) {
    wfCreate.error = exception.response?.data?.error || '发起失败，请稍后重试'
  } finally {
    wfCreate.starting = false
  }
}

onMounted(async () => {
  loadSkills()
  loadVideoCapabilities()
  await loadDrafts()
  applySeed()
  consumeHandoff()
})

/** 按绑定模型能力收敛生成方式：不支持的操作不出现在下拉里，当前值不支持时自动切到可用项。 */
async function loadVideoCapabilities() {
  try {
    const health = await getCreativeHealth()
    const operations = health?.videoOperations
    if (!operations) return
    videoOps.value = operations
    if (operations[videoOperation.value] === false) {
      const fallback = Object.keys(operations).find(key => operations[key])
      if (fallback) {
        videoOperation.value = fallback
        markDirty()
      }
    }
  } catch (exception) { /* health 不可用时不改变默认选项 */ }
}

onBeforeUnmount(() => {
  window.clearTimeout(saveTimer)
  // 切分区时立即落盘，避免 1200ms 节流内的编辑丢失
  if (saveState.value === 'idle' && (idea.value.trim() || effective.value.trim())) saveDraft()
})
</script>

<style scoped src="../creative.css"></style>

<style scoped>
/* 沉浸式创作台（redesign §5.5）：中央结果舞台 + 底部输入台 + 按需参数侧栏 */
.st-body {
  flex: 1;
  min-height: 0;
  display: grid;
  grid-template-columns: minmax(0, 1fr) 340px;
  grid-template-rows: minmax(0, 1fr) auto;
}

.st-results {
  grid-area: 1 / 1 / 2 / 2;
  min-width: 0;
  overflow-y: auto;
  padding: var(--wb-space-4) var(--wb-space-5);
  display: flex;
  flex-direction: column;
  gap: var(--wb-space-3);
}

/* 底部输入台：稳定常驻；提示词与提交动作始终可见 */
.st-composer {
  grid-area: 2 / 1 / 3 / 2;
  border-top: 1px solid var(--wb-border);
  background: var(--cr-surface, var(--wb-surface));
  padding: var(--wb-space-3) var(--wb-space-5);
}

.composer-main {
  display: flex;
  align-items: flex-end;
  gap: var(--wb-space-3);
}

.composer-idea {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.composer-textarea {
  min-height: 44px;
  max-height: 120px;
  resize: none;
}

.composer-ref {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  flex: 0 0 auto;
  padding: 5px 10px;
  border: 1px solid var(--wb-border);
  border-radius: 999px;
  background: var(--wb-brand-soft);
  color: var(--wb-brand);
  font-size: 12px;
}

.composer-side {
  flex: 0 0 262px;
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.composer-side .model-line span:first-child { white-space: nowrap; }
.composer-side .generate-btn { justify-content: center; padding: 9px 14px; }

.job-card {
  border: 1px solid var(--wb-border);
  border-radius: var(--wb-radius);
  background: var(--wb-surface);
  padding: var(--wb-space-3) var(--wb-space-4);
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.job-card-head { display: flex; align-items: center; justify-content: space-between; }
.job-card-time { font-size: var(--wb-fs-caption); color: var(--wb-muted); }
.job-card-hint { margin: 0; font-size: var(--wb-fs-compact); color: var(--wb-text-2); }
.job-card-prompt {
  margin: 0;
  font-size: var(--wb-fs-compact);
  line-height: 1.6;
  color: var(--wb-text);
  display: -webkit-box;
  -webkit-line-clamp: 3;
  -webkit-box-orient: vertical;
  overflow: hidden;
}
.job-card-error { margin: 0; }
.job-card-actions { display: flex; flex-wrap: wrap; gap: var(--wb-space-2); align-items: center; }

.job-image video {
  display: block;
  width: 100%;
  aspect-ratio: 16 / 9;
  height: auto;
  object-fit: contain;
  background: var(--wb-media-bg);
}
.job-image-placeholder {
  aspect-ratio: 16 / 9;
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--wb-muted);
  font-size: var(--wb-fs-caption);
}
.job-image-actions {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 6px 10px;
  font-size: var(--wb-fs-caption);
  color: var(--wb-muted);
}

.st-panel {
  grid-area: 1 / 2 / 3 / 3;
  border-left: 1px solid var(--wb-border);
  background: var(--wb-surface);
  overflow-y: auto;
  padding: var(--wb-space-4);
  display: flex;
  flex-direction: column;
  gap: var(--wb-space-4);
}
.panel-block { display: flex; flex-direction: column; gap: var(--wb-space-3); }
.panel-optimize { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.final-field { margin-top: 4px; }
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
  gap: var(--wb-space-2);
}
.suggestion-head { display: flex; justify-content: space-between; align-items: baseline; }
.suggestion-head strong { font-size: var(--wb-fs-compact); }
.suggestion-text { margin: 0; font-size: var(--wb-fs-compact); line-height: 1.62; color: var(--wb-text); }
.suggestion-actions { display: flex; gap: var(--wb-space-2); }

.skill-block { border-top: 1px solid var(--wb-border); padding-top: var(--wb-space-3); }
.skill-warning { color: var(--wb-warning); margin: 0; }
.skill-plan textarea { margin-top: 6px; }
.skill-plan-failed { display: flex; flex-direction: column; gap: 6px; align-items: flex-start; }
.skill-plan-failed .cr-error { margin: 0; }

.shot-jobs {
  list-style: none;
  margin: 6px 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 4px;
  font-size: 12px;
}
.shot-jobs li { display: flex; align-items: center; gap: 8px; }
.shot-job-title { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; color: var(--wb-text-2); }

.panel-switch { display: flex; align-items: center; gap: var(--wb-space-2); font-size: var(--wb-fs-compact); color: var(--wb-text-2); }
.model-line, .save-line {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  gap: 10px;
  font-size: var(--wb-fs-compact);
}
.model-name { font-weight: 550; color: var(--wb-text); text-align: right; }
.save-line { color: var(--wb-muted); }
.generate-btn { justify-content: center; padding: 10px 14px; }
.wf-launch-block { border-top: 1px solid var(--wb-border); padding-top: var(--wb-space-3); }

/* 轮次候选组（redesign §5.5）：结果就绪在原占位出现，深色媒体舞台 */
.job-images { display: flex; flex-wrap: wrap; gap: 12px; }
.job-image {
  margin: 0;
  width: min(360px, 100%);
  border: 1px solid var(--wb-border);
  border-radius: var(--cr-radius-media, 14px);
  overflow: hidden;
  background: var(--cr-media-stage, var(--wb-media-bg));
}
.job-image img { display: block; width: 100%; height: auto; max-height: 480px; object-fit: contain; }
.job-image:has(video) { width: min(560px, 100%); }

@media (max-width: 1080px) {
  .st-body { grid-template-columns: minmax(0, 1fr) 320px; }
  .composer-side { flex-basis: 200px; }
}

@media (max-width: 900px) {
  .st-body { display: flex; flex-direction: column; overflow-y: auto; }
  .st-results { overflow: visible; }
  .st-composer { order: -1; border-top: 0; border-bottom: 1px solid var(--wb-border); position: sticky; top: 0; z-index: 5; }
  .composer-main { flex-direction: column; align-items: stretch; }
  .composer-side { flex-basis: auto; }
  .st-panel { border-left: none; border-top: 1px solid var(--wb-border); overflow: visible; }
}
</style>
