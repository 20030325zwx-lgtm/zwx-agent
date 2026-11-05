<template>
  <div class="cr-page">
    <div class="cr-scroll">
      <div class="cr-inner">
        <!-- 首屏（参考幻映创作页语言）：标题 + 胶囊分段 + 白色大输入卡 + 快捷起点 -->
        <header class="home-head">
          <div class="home-head-text">
            <h1 class="home-title">和 Aster 聊聊创作想法</h1>
            <p class="home-sub">从一个画面、一个角色或一句话开始。</p>
          </div>
          <div class="home-head-actions">
            <router-link to="/creative/tasks" class="home-history">
              <History :size="15" /> 生成记录
            </router-link>
            <button type="button" class="cr-secondary" @click="openCreate">
              <Plus :size="15" /> 新建作品
            </button>
          </div>
        </header>

        <div class="home-seg" role="tablist" aria-label="创作类型">
          <button
            v-for="option in kindOptions"
            :key="option.value"
            type="button"
            role="tab"
            class="home-seg-item"
            :class="{ active: composeKind === option.value }"
            :aria-selected="composeKind === option.value"
            :disabled="!option.enabled"
            @click="composeKind = option.value"
          >
            <component :is="option.icon" :size="15" />
            {{ option.label }}
            <em v-if="!option.enabled">规划中</em>
          </button>
        </div>

        <!-- 输入卡：准备阶段叫「进入创作台」，真正提交生成才叫「生成」 -->
        <section class="home-compose">
          <textarea
            v-model.trim="idea"
            class="cr-textarea home-idea"
            rows="3"
            maxlength="500"
            placeholder="输入你的镜头、画面或故事。也可以添加参考图开始创作"
            @keydown.ctrl.enter="startFromComposer"
            @keydown.meta.enter="startFromComposer"
          />
          <div class="home-tools">
            <button type="button" class="home-tool" @click="goAssets">
              <Plus :size="14" /> 参考素材
            </button>
            <DropdownSelect v-model="composeSkill" class="home-tool-select" size="sm"
                            :options="skillOptions" placeholder="技能" aria-label="技能" />
            <template v-if="composeKind !== 'NOVEL'">
              <DropdownSelect v-model="modelRowId" class="home-tool-select home-tool-model" size="sm"
                              :options="modelOptions" placeholder="模型" aria-label="模型" />
              <DropdownSelect v-if="composeKind === 'IMAGE'" v-model="composeSize" class="home-tool-select"
                              size="sm" :options="sizeOptions" aria-label="尺寸" />
              <div v-else class="home-tool-duration">
                <Clock :size="14" />
                <input v-model="composeSeconds" class="home-tool-input" maxlength="2" inputmode="numeric"
                       aria-label="时长（秒）" />
                <span>秒</span>
              </div>
            </template>
            <div class="home-tools-right">
              <button type="button" class="home-submit" :disabled="!idea || starting" @click="startFromComposer">
                <Spinner v-if="starting" />
                <ArrowUp v-else :size="15" />
                {{ starting ? '创建中…' : '进入创作台' }}
              </button>
            </div>
          </div>
          <div v-if="firstFrameId" class="home-firstframe">
            <ImagePlay :size="14" />
            <span>已选首帧素材</span>
            <button type="button" class="home-firstframe-clear" aria-label="移除首帧" @click="clearFirstFrame">
              <X :size="12" />
            </button>
          </div>
          <div v-if="refImageIds" class="home-firstframe">
            <Images :size="14" />
            <span>已选参考素材</span>
            <button type="button" class="home-firstframe-clear" aria-label="移除参考素材" @click="clearRefImage">
              <X :size="12" />
            </button>
          </div>
          <p v-if="startError" class="cr-error">{{ startError }}</p>
        </section>

        <!-- 快捷起点：一行四条，无卡片边框（参考页语言） -->
        <nav class="home-starts" aria-label="创作起点">
          <button type="button" class="home-start" @click="focusComposer">
            <span class="home-start-icon"><PenLine :size="17" /></span>
            <span class="home-start-text">
              <strong>写一句想法</strong>
              <em>从空白输入台开始</em>
            </span>
          </button>
          <router-link to="/creative/assets" class="home-start">
            <span class="home-start-icon"><Images :size="17" /></span>
            <span class="home-start-text">
              <strong>从素材开始</strong>
              <em>挑选已有图片或视频</em>
            </span>
          </router-link>
          <router-link to="/creative/skills" class="home-start">
            <span class="home-start-icon"><Sparkles :size="17" /></span>
            <span class="home-start-text">
              <strong>使用技能</strong>
              <em>浏览创作方法目录</em>
            </span>
          </router-link>
          <a v-if="recentProject" class="home-start" :href="`/creative/projects/${recentProject.id}`"
             @click.prevent="goProject(recentProject)">
            <span class="home-start-icon"><History :size="17" /></span>
            <span class="home-start-text">
              <strong>继续最近创作</strong>
              <em>{{ recentProject.name }}</em>
            </span>
          </a>
        </nav>

        <div v-if="bindingChecked && !imageBinding" class="cr-banner">
          <AlertCircle :size="18" />
          <div class="cr-banner-body">
            <strong>还没有配置「图片生成」模型</strong>
            <p>图片创作只使用图片生成绑定，不会回退到对话模型。视频与小说不受影响；配置后回到这里继续。</p>
          </div>
          <router-link to="/settings/model" class="cr-banner-link">去配置</router-link>
        </div>

        <section>
          <div class="cr-section-head">
            <h2>最近创作</h2>
            <router-link to="/creative/library">我的创作</router-link>
          </div>
          <AsterLoader v-if="loading" size="18" />
          <p v-else-if="loadError" class="cr-error">{{ loadError }}</p>
          <div v-else-if="!projects.length" class="cr-empty home-empty">
            <strong>还没有作品</strong>
            <p>在上面写一句想法就能开始；也可以从已有素材或工作流起步。</p>
            <div class="home-empty-actions">
              <button type="button" class="cr-primary" @click="openCreate">新建作品</button>
              <button type="button" class="cr-secondary" @click="openWorkflowCreate">从工作流创建</button>
            </div>
          </div>
          <div v-else class="home-works">
            <ProjectCard v-for="project in projects" :key="project.id" :project="project" />
          </div>
        </section>

        <section>
          <div class="cr-section-head">
            <h2>生成活动</h2>
            <router-link to="/creative/tasks">全部生成记录</router-link>
          </div>
          <div class="home-pending">
            <div class="home-pending-counts">
              <span class="home-count">
                <em>{{ jobCounts.active }}</em> 生成中
              </span>
              <span class="home-count" :class="{ 'is-warn': failedCount > 0 }">
                <em>{{ failedCount }}</em> 最近失败
              </span>
              <span class="home-count" :class="{ 'is-warn': uncertainCount > 0 }">
                <em>{{ uncertainCount }}</em> 结果待确认
              </span>
              <span class="home-count" :class="{ 'is-warn': pendingSelection > 0 }">
                <em>{{ pendingSelection }}</em> 待选片
              </span>
            </div>
            <p v-if="!pendingJobs.length" class="cr-hint">
              没有需要处理的任务。生成中或失败的镜头会出现在这里。
            </p>
            <ul v-else class="home-jobs">
              <li v-for="job in pendingJobs" :key="job.jobId" class="home-job">
                <span class="cr-status" :class="statusMeta(job.status).tone">{{ statusMeta(job.status).label }}</span>
                <span class="home-job-text">
                  <strong>{{ job.projectName || projectName(job.projectId) }}</strong>
                  · {{ job.prompt ? truncate(job.prompt, 40) : '生成任务' }}
                </span>
                <span class="home-job-time">{{ formatTime(job.createdAt) }}</span>
                <router-link class="home-job-link" :to="jobLink(job)">打开</router-link>
              </li>
            </ul>
          </div>
        </section>

        <p class="home-more">
          <button type="button" class="home-link" @click="openWorkflowCreate">
            <Workflow :size="14" /> 从工作流创建
          </button>
          <router-link to="/creative/skills" class="home-link">
            <Sparkles :size="14" /> 技能库
          </router-link>
        </p>
      </div>
    </div>

    <div v-if="createOpen" class="cr-dialog-backdrop" @click.self="createOpen = false">
      <div class="cr-dialog" role="dialog" aria-modal="true" aria-labelledby="create-project-title">
        <h2 id="create-project-title">新建作品</h2>
        <div class="cr-field">
          <span class="cr-field-label"><span>创作类型</span></span>
          <div class="kind-options" role="radiogroup" aria-label="创作类型">
            <button
              v-for="option in kindOptions"
              :key="option.value"
              type="button"
              class="kind-option"
              :class="{ selected: form.kind === option.value }"
              :disabled="!option.enabled"
              :aria-checked="form.kind === option.value"
              role="radio"
              @click="form.kind = option.value"
            >
              <strong>{{ option.label }}</strong>
              <span>{{ option.enabled ? '可使用' : '规划中' }}</span>
            </button>
          </div>
        </div>
        <label class="cr-field">
          <span class="cr-field-label"><span>作品名称</span><span class="count">{{ form.name.length }}/80</span></span>
          <input v-model.trim="form.name" class="cr-input" maxlength="80" placeholder="如：秋季新品视觉" />
        </label>
        <label class="cr-field">
          <span class="cr-field-label"><span>描述（可选）</span></span>
          <textarea v-model.trim="form.description" class="cr-textarea" maxlength="500" rows="3"
                    placeholder="记录用途、风格或参考方向" />
        </label>
        <p v-if="createError" class="cr-error">{{ createError }}</p>
        <div class="cr-dialog-actions">
          <button type="button" class="cr-secondary" @click="createOpen = false">取消</button>
          <button type="button" class="cr-primary" :disabled="creating || !form.name" @click="create">
            {{ creating ? '创建中…' : '创建并进入' }}
          </button>
        </div>
      </div>
    </div>

    <div v-if="wfOpen" class="cr-dialog-backdrop" @click.self="wfOpen = false">
      <div class="cr-dialog" role="dialog" aria-modal="true" aria-labelledby="wf-create-title">
        <h2 id="wf-create-title">发起工作流创作</h2>
        <p v-if="wfLoading" class="cr-hint">加载工作流与项目…</p>
        <template v-else>
          <label class="cr-field">
            <span class="cr-field-label"><span>工作流</span></span>
            <DropdownSelect v-model="wfForm.workflowKey" block :options="workflowOptions"
                            placeholder="选择工作流" aria-label="工作流"
                            @change="loadWorkflowInputs" />
            <span v-if="selectedWorkflow?.description" class="cr-field-help">{{ selectedWorkflow.description }}</span>
          </label>
          <label class="cr-field">
            <span class="cr-field-label"><span>目标作品</span><span class="count">产物与任务归入该作品</span></span>
            <DropdownSelect v-model="wfForm.projectId" block :options="activeProjectOptions"
                            placeholder="选择作品" aria-label="目标作品" />
          </label>
          <div v-for="field in wfForm.fields" :key="field.key" class="cr-field">
            <span class="cr-field-label">
              <span>{{ field.label }}<em v-if="field.required" class="req-star">*</em></span>
            </span>

            <AssetParamField
              v-if="field.kind === 'asset' || field.kind === 'assets'"
              :model-value="wfForm.input[field.key] || ''"
              :multiple="field.kind === 'assets'"
              @update:model-value="value => wfForm.input[field.key] = value"
            />
            <input v-else v-model.trim="wfForm.input[field.key]" class="cr-input" maxlength="4000"
                   :placeholder="field.placeholder || field.label" />
          </div>
          <p v-if="wfError" class="cr-error">{{ wfError }}</p>
        </template>
        <div class="cr-dialog-actions">
          <button type="button" class="cr-secondary" @click="wfOpen = false">取消</button>
          <button type="button" class="cr-primary"
                  :disabled="wfStarting || !wfForm.workflowKey || !wfForm.projectId
                    || wfForm.fields.some(field => field.required && !(wfForm.input[field.key] || '').trim())"
                  @click="startWorkflowRun">
            <Spinner v-if="wfStarting" /> {{ wfStarting ? '发起中…' : '发起运行' }}
          </button>
        </div>
      </div>
    </div>

    <p v-if="toast.text" class="cr-toast" :class="toast.tone">{{ toast.text }}</p>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref, watch } from 'vue'
import AsterLoader from '../../components/ui/AsterLoader.vue'
import { useRouter } from 'vue-router'
import {
  AlertCircle, ArrowUp, BookOpen, Clapperboard, Clock, History, Image as ImageIcon, ImagePlay,
  Images, PanelsTopLeft, PenLine, Plus, Sparkles, Workflow, X
} from 'lucide-vue-next'
import {
  createCreativeDraft, createCreativeProject, getCreativeHealth, getCreativeSummary, getWorkflowDefinition,
  listCreativeProjects, listCreativeSkills, listModelCatalog, listWorkflows, startWorkflow
} from '../../api'
import { formatTime, statusMeta } from './shared'
import ProjectCard from '../../components/creative/ProjectCard.vue'
import DropdownSelect from '../../components/ui/DropdownSelect.vue'
import Spinner from '../../components/ui/Spinner.vue'
import AssetParamField from '../../components/workflow/AssetParamField.vue'
import { buildRunForm } from './workflowInputs'

const router = useRouter()
const projects = ref([])
const summary = ref(null)
const health = ref(null)
const loading = ref(true)
const loadError = ref('')
const idea = ref('')
const composeKind = ref('IMAGE')
const composeSkill = ref('')
const firstFrameId = ref('')
// 图片参考素材（image_edit），来自素材库「引用到创作台」
const refImageIds = ref('')
const skills = ref([])
const starting = ref(false)
const startError = ref('')
// 本地草稿（redesign §5.1）：输入先本地即时保存，刷新可恢复；提交时才创建项目
const COMPOSER_KEY = 'aster.creative.composer'
const recentProject = computed(() => projects.value[0] || null)
const skillOptions = computed(() => [
  { value: '', label: '可选技能' },
  ...skills.value.filter(skill => skill.compat === 'READY')
    .map(skill => ({ value: skill.slug, label: skill.name }))
])

function restoreDraft() {
  try {
    const raw = localStorage.getItem(COMPOSER_KEY)
    if (!raw) return
    const draft = JSON.parse(raw)
    if (typeof draft.idea === 'string') idea.value = draft.idea
    if (draft.kind && kindOptions.some(option => option.value === draft.kind && option.enabled)) {
      composeKind.value = draft.kind
    }
    if (typeof draft.skill === 'string') composeSkill.value = draft.skill
    if (typeof draft.firstFrame === 'string' && draft.firstFrame) firstFrameId.value = draft.firstFrame
    if (typeof draft.refImage === 'string' && draft.refImage) refImageIds.value = draft.refImage
    if (draft.modelRowId) modelRowId.value = draft.modelRowId
    if (typeof draft.size === 'string' && draft.size) composeSize.value = draft.size
    if (typeof draft.seconds === 'string' && draft.seconds) composeSeconds.value = draft.seconds
  } catch (error) { /* 草稿损坏按空处理 */ }
}

function persistDraft() {
  try {
    localStorage.setItem(COMPOSER_KEY, JSON.stringify({
      idea: idea.value,
      kind: composeKind.value,
      skill: composeSkill.value,
      firstFrame: firstFrameId.value,
      refImage: refImageIds.value,
      modelRowId: modelRowId.value,
      size: composeSize.value,
      seconds: composeSeconds.value
    }))
  } catch (error) { /* 隐私模式忽略 */ }
}

function clearDraft() {
  try { localStorage.removeItem(COMPOSER_KEY) } catch (error) { /* 忽略 */ }
}

// 技能库/素材库「使用」「用作首帧」经 sessionStorage 交接（不把提示词放 URL）
function readHandoff() {
  try {
    const raw = sessionStorage.getItem('aster.creative.handoff')
    if (!raw) return
    sessionStorage.removeItem('aster.creative.handoff')
    const handoff = JSON.parse(raw)
    if (handoff.skill) composeSkill.value = handoff.skill
    if (typeof handoff.prompt === 'string' && handoff.prompt && !idea.value) idea.value = handoff.prompt
    if (handoff.kind) composeKind.value = handoff.kind
    // 从素材库带过来的首帧/参考素材
    if (handoff.firstFrame) {
      firstFrameId.value = handoff.firstFrame
      composeKind.value = 'VIDEO'
    }
    if (handoff.refImage) {
      refImageIds.value = handoff.refImage
      composeKind.value = 'IMAGE'
    }
    persistDraft()
  } catch (error) { /* 忽略 */ }
}

function clearFirstFrame() {
  firstFrameId.value = ''
  persistDraft()
}

function clearRefImage() {
  refImageIds.value = ''
  persistDraft()
}

function focusComposer() {
  document.querySelector('.home-idea')?.focus()
}

function goProject(project) {
  router.push(`/creative/projects/${project.id}`)
}

/** 「参考素材」：到素材库挑选后用「引用到创作台」带回输入台（真实路径，不做假按钮）。 */
function goAssets() {
  persistDraft()
  router.push('/creative/assets')
}
const createOpen = ref(false)
const creating = ref(false)
const createError = ref('')
const form = reactive({ name: '', description: '', kind: 'IMAGE' })
const kindOptions = [
  { value: 'IMAGE', label: '图片', enabled: true, icon: ImageIcon },
  { value: 'VIDEO', label: '视频', enabled: true, icon: Clapperboard },
  { value: 'NOVEL', label: '小说', enabled: true, icon: BookOpen },
  { value: 'COMIC', label: '漫画', enabled: false, icon: PanelsTopLeft }
]
const imageBinding = ref(null)
const bindingChecked = ref(false)

// 输入卡内联参数（参考创作页语言）：模型 / 尺寸 / 时长
const catalog = ref(null)
const modelRowId = ref(null)
const composeSize = ref('1024*1024')
const composeSeconds = ref('5')
const sizeOptions = [
  { value: '1024*1024', label: '1:1 方形' },
  { value: '1280*720', label: '16:9 横版' },
  { value: '720*1280', label: '9:16 竖版' },
  { value: '1152*768', label: '3:2 横版' },
  { value: '768*1152', label: '2:3 竖版' }
]
const modelOptions = computed(() => {
  if (!catalog.value || composeKind.value === 'NOVEL') return []
  const capability = composeKind.value === 'VIDEO' ? 'video_generation' : 'image_generation'
  const rows = []
  for (const provider of catalog.value.providers || []) {
    if (provider.enabled === false) continue
    for (const model of provider.models || []) {
      if (model.enabled === false) continue
      if (!(model.capabilities || []).includes(capability)) continue
      rows.push({ value: model.id, label: `${provider.name} / ${model.displayName || model.modelId}` })
    }
  }
  return rows
})

function syncDefaultModel() {
  // 已选模型对当前类型仍有效就保留（草稿/交接带入的选择不被覆盖）
  const rows = modelOptions.value
  if (modelRowId.value && rows.some(row => row.value === modelRowId.value)) return
  const capability = composeKind.value === 'VIDEO' ? 'video_generation' : 'image_generation'
  const binding = catalog.value?.userBindings?.[capability] || catalog.value?.systemBindings?.[capability]
  modelRowId.value = binding?.modelRowId || rows[0]?.value || null
}
watch(composeKind, syncDefaultModel)

// 草稿自动保存：所有被持久化的字段声明完成后再注册（watch 会立即读取源）
watch([idea, composeKind, composeSkill, firstFrameId, refImageIds, modelRowId, composeSize, composeSeconds], persistDraft)
const toast = reactive({ text: '', tone: '' })
const wfOpen = ref(false)
const wfLoading = ref(false)
const wfStarting = ref(false)
const wfError = ref('')
const workflows = ref([])
const wfForm = reactive({ workflowKey: '', projectId: null, fields: [], input: {} })
const activeProjects = computed(() => projects.value.filter(p => p.status === 'ACTIVE'))
const selectedWorkflow = computed(() => workflows.value.find(wf => wf.key === wfForm.workflowKey))
const workflowOptions = computed(() => workflows.value.map(wf => ({
  value: wf.key,
  label: `${wf.name || wf.key}（${(wf.steps || []).length} 节点）`
})))
const activeProjectOptions = computed(() => activeProjects.value.map(project => ({
  value: project.id, label: project.name
})))

// 待处理：跨项目聚合（进行中/失败/保存失败/待确认/待选片），不再只统计当前页任务
const jobCounts = computed(() => ({ active: summary.value?.active || 0 }))
const pendingJobs = computed(() => (summary.value?.items || []).slice(0, 5))
const failedCount = computed(() => (summary.value?.failed || 0) + (summary.value?.ingestFailed || 0))
const uncertainCount = computed(() => summary.value?.reconciling || 0)
const pendingSelection = computed(() => summary.value?.pendingSelection || 0)

function projectName(projectId) {
  return projects.value.find(project => project.id === projectId)?.name || '未归档任务'
}

function jobLink(job) {
  return `/creative/projects/${job.projectId}?tab=tasks&jobId=${job.jobId}`
}

function truncate(text, max) {
  const value = String(text || '')
  return value.length > max ? `${value.slice(0, max)}…` : value
}

function showToast(text, tone = '') {
  toast.text = text
  toast.tone = tone
  window.clearTimeout(showToast.timer)
  showToast.timer = window.setTimeout(() => { toast.text = '' }, 3200)
}

/**
 * 输入台起步（redesign §5.1）：创建项目不计费；提示词随草稿/交接带入创作台。
 * 选了技能时经 sessionStorage 交接给创作台（stage 6 消费），技能名可进 URL（非隐私）。
 */
async function startFromComposer() {
  if (!idea.value || starting.value) return
  starting.value = true
  startError.value = ''
  const text = idea.value
  const skill = composeSkill.value
  try {
    const project = await createCreativeProject({
      name: text.split('\n')[0].slice(0, 30) || '未命名作品',
      description: text.slice(0, 500),
      kind: composeKind.value
    })
    if (composeKind.value === 'IMAGE') {
      // 草稿只用于图片链路（视频/小说走各自的剧情与章节域）；失败不影响进入作品
      await createCreativeDraft(project.id, {
        operation: 'text_to_image', originalPrompt: text, effectivePrompt: text
      }).catch(() => null)
    }
    clearDraft()
    const handoff = { kind: composeKind.value }
    if (skill) handoff.skill = skill
    if (composeKind.value !== 'IMAGE') handoff.prompt = text
    if (firstFrameId.value) handoff.firstFrame = firstFrameId.value
    if (refImageIds.value) handoff.refImage = refImageIds.value
    if (modelRowId.value) handoff.modelRowId = modelRowId.value
    if (composeKind.value === 'IMAGE') handoff.size = composeSize.value
    if (composeKind.value === 'VIDEO') handoff.seconds = composeSeconds.value
    try { sessionStorage.setItem('aster.creative.handoff', JSON.stringify(handoff)) } catch (error) { /* 忽略 */ }
    router.push(`/creative/projects/${project.id}/canvas`)
  } catch (error) {
    startError.value = error.response?.data?.error || '创建失败，请稍后重试'
  } finally {
    starting.value = false
  }
}

function openCreate() {
  form.name = ''
  form.description = ''
  form.kind = composeKind.value
  createError.value = ''
  createOpen.value = true
}

async function create() {
  if (!form.name) return
  creating.value = true
  createError.value = ''
  try {
    const project = await createCreativeProject({
      name: form.name,
      description: form.description || null,
      kind: form.kind
    })
    createOpen.value = false
    router.push(`/creative/projects/${project.id}`)
  } catch (error) {
    createError.value = error.response?.data?.error || '创建失败，请稍后重试'
  } finally {
    creating.value = false
  }
}

// R03（评审 2026-10-31）：与编辑器/项目页共用同一运行表单推导（声明式 inputs 优先，扫描兜底）
async function loadWorkflowInputs(workflowKey) {
  wfForm.fields = []
  wfForm.input = {}
  if (!workflowKey) return
  try {
    const definition = await getWorkflowDefinition(workflowKey)
    wfForm.fields = buildRunForm(definition)
  } catch (error) {
    wfError.value = '工作流定义加载失败，无法校验运行输入'
  }
}

function openWorkflowCreate() {
  wfOpen.value = true
  wfError.value = ''
  wfForm.workflowKey = ''
  wfForm.projectId = activeProjects.value[0]?.id || null
  wfForm.fields = []
  wfForm.input = {}
  wfLoading.value = true
  Promise.allSettled([listWorkflows(30), projects.value.length ? Promise.resolve() : listCreativeProjects({ limit: 50 })])
    .then(([wfResult, projectResult]) => {
      if (wfResult.status === 'fulfilled') workflows.value = wfResult.value.definitions || []
      else wfError.value = '工作流列表加载失败，请确认后端服务可用'
      if (projectResult.status === 'fulfilled' && projectResult.value?.items) {
        projects.value = projectResult.value.items
        if (!wfForm.projectId) wfForm.projectId = activeProjects.value[0]?.id || null
      }
      if (!workflows.value.length) wfError.value = '还没有可用的工作流。先到「工作流」页创建或使用商品海报模板。'
    })
    .finally(() => { wfLoading.value = false })
}

async function startWorkflowRun() {
  const missingRequired = wfForm.fields.some(field => field.required && !(wfForm.input[field.key] || '').trim())
  if (!wfForm.workflowKey || !wfForm.projectId || missingRequired) return
  wfStarting.value = true
  wfError.value = ''
  try {
    const input = {}
    for (const field of wfForm.fields) {
      const value = (wfForm.input[field.key] || '').trim()
      if (value) input[field.key] = value
    }
    const run = await startWorkflow(wfForm.workflowKey, input.keyword || '', wfForm.projectId, input)
    wfOpen.value = false
    showToast('工作流已发起，产物将归入所选作品')
    router.push({ path: `/creative/projects/${wfForm.projectId}`, query: { tab: 'workflow', runId: run.id } })
  } catch (error) {
    wfError.value = error.response?.data?.error || '发起失败，请稍后重试'
  } finally {
    wfStarting.value = false
  }
}

async function load() {
  loading.value = true
  loadError.value = ''
  const [projectResult, summaryResult, catalogResult, healthResult, skillResult] = await Promise.allSettled([
    listCreativeProjects({ limit: 6 }),
    getCreativeSummary(),
    listModelCatalog(),
    getCreativeHealth(),
    listCreativeSkills()
  ])
  if (projectResult.status === 'fulfilled') {
    projects.value = projectResult.value.items || []
  } else {
    loadError.value = '作品加载失败，请确认后端服务可用'
  }
  if (summaryResult.status === 'fulfilled') summary.value = summaryResult.value
  if (catalogResult.status === 'fulfilled') {
    catalog.value = catalogResult.value
    imageBinding.value = catalogResult.value.userBindings?.image_generation
      || catalogResult.value.systemBindings?.image_generation || null
    syncDefaultModel()
  }
  if (healthResult.status === 'fulfilled') health.value = healthResult.value
  if (skillResult.status === 'fulfilled') skills.value = skillResult.value.items || []
  restoreDraft()
  readHandoff()
  bindingChecked.value = true
  loading.value = false
}

onMounted(load)
</script>

<style scoped src="./creative.css"></style>

<style scoped>
/* ================= 创作首页视觉语言（参考幻映创作页） ================= */

.cr-page { background: var(--cr-page-bg, var(--wb-bg)); }
.cr-inner { gap: var(--wb-space-7); }

.cr-section-head h2 { font-size: 18px; letter-spacing: -0.01em; }

.home-head {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: var(--wb-space-4);
  padding-top: var(--wb-space-6);
}

.home-title {
  margin: 0;
  font-size: clamp(26px, 2.4vw, 32px);
  line-height: 1.25;
  font-weight: 680;
  letter-spacing: -0.02em;
  color: var(--cr-text, var(--wb-text));
}

.home-sub {
  margin: 8px 0 0;
  font-size: 14px;
  line-height: 1.6;
  color: var(--cr-text-2, var(--wb-text-2));
}

.home-head-actions { display: flex; align-items: center; gap: var(--wb-space-2); }

.home-history {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 7px 12px;
  border-radius: 999px;
  color: var(--cr-text-2, var(--wb-text-2));
  font-size: 13px;
  text-decoration: none;
  transition: background var(--cr-dur-fast, 140ms) var(--cr-ease, ease), color var(--cr-dur-fast, 140ms) var(--cr-ease, ease);
}
.home-history:hover { background: var(--cr-surface-2, var(--wb-fill)); color: var(--cr-text, var(--wb-text)); }

/* 胶囊分段：容器浅底，选中项白底浮起 */
.home-seg {
  display: flex;
  align-items: center;
  gap: 2px;
  width: fit-content;
  margin: var(--wb-space-5) auto var(--wb-space-4);
  padding: 4px;
  border-radius: 999px;
  background: var(--cr-surface-2, var(--wb-fill));
}

.home-seg-item {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  padding: 8px 22px;
  border: 0;
  border-radius: 999px;
  background: transparent;
  color: var(--cr-text-2, var(--wb-text-2));
  font-size: 13.5px;
  cursor: pointer;
  transition: background var(--cr-dur-fast, 140ms) var(--cr-ease, ease), color var(--cr-dur-fast, 140ms) var(--cr-ease, ease), box-shadow var(--cr-dur-fast, 140ms) var(--cr-ease, ease);
}
.home-seg-item:hover { color: var(--cr-text, var(--wb-text)); }
.home-seg-item.active {
  background: var(--cr-surface, #fff);
  color: var(--cr-text, var(--wb-text));
  font-weight: 600;
  box-shadow: 0 1px 3px rgba(20, 18, 24, 0.08), 0 1px 1px rgba(20, 18, 24, 0.04);
}
.home-seg-item em { font-style: normal; font-size: 10.5px; color: var(--cr-text-2, var(--wb-muted)); }
.home-seg-item:disabled { opacity: 0.45; cursor: not-allowed; }

/* 输入卡：白色大圆角 + 极轻阴影，工具栏内联 */
.home-compose {
  display: flex;
  flex-direction: column;
  gap: 10px;
  width: min(880px, 100%);
  margin: 0 auto;
  padding: 18px 18px 14px;
  border: 1px solid var(--cr-border, var(--wb-border));
  border-radius: var(--cr-radius-composer, 20px);
  background: var(--cr-surface, var(--wb-surface));
  box-shadow: 0 10px 30px rgba(20, 18, 24, 0.05), 0 1px 2px rgba(20, 18, 24, 0.04);
}

.home-idea {
  min-height: 108px;
  border: 0;
  padding: 4px 2px;
  background: transparent;
  color: var(--cr-text, var(--wb-text));
  font-size: 15px;
  line-height: 1.7;
  resize: none;
}
.home-idea:focus { outline: none; }
.home-idea::placeholder { color: color-mix(in srgb, var(--cr-text-2, #62646A) 62%, transparent); }

.home-tools {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  padding-top: 10px;
  border-top: 1px solid var(--cr-border, var(--wb-border));
}

.home-tool {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  height: 32px;
  padding: 0 12px;
  border: 1px solid var(--cr-border, var(--wb-border));
  border-radius: 999px;
  background: transparent;
  color: var(--cr-text-2, var(--wb-text-2));
  font-size: 12.5px;
  cursor: pointer;
  transition: border-color var(--cr-dur-fast, 140ms) var(--cr-ease, ease), color var(--cr-dur-fast, 140ms) var(--cr-ease, ease);
}
.home-tool:hover { border-color: var(--cr-text-2, var(--wb-border-strong)); color: var(--cr-text, var(--wb-text)); }

.home-tool-select { width: 132px; }
.home-tool-model { width: 200px; }

.home-tool-duration {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  height: 32px;
  padding: 0 12px;
  border: 1px solid var(--cr-border, var(--wb-border));
  border-radius: 999px;
  color: var(--cr-text-2, var(--wb-text-2));
  font-size: 12.5px;
}
.home-tool-input {
  width: 26px;
  border: 0;
  background: transparent;
  color: var(--cr-text, var(--wb-text));
  font-size: 12.5px;
  text-align: center;
  outline: none;
}

.home-tools-right { display: flex; align-items: center; gap: 10px; margin-left: auto; }

.home-submit {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 7px;
  height: 38px;
  padding: 0 20px;
  border: 0;
  border-radius: 12px;
  background: var(--cr-btn-bg, #25252A);
  color: var(--cr-btn-fg, #fff);
  font-size: 13.5px;
  font-weight: 600;
  cursor: pointer;
  transition: opacity var(--cr-dur-fast, 140ms) var(--cr-ease, ease), transform var(--cr-dur-instant, 90ms) var(--cr-ease, ease);
}
.home-submit:hover:not(:disabled) { opacity: 0.92; }
.home-submit:active:not(:disabled) { transform: scale(0.985); }
.home-submit:disabled { opacity: 0.42; cursor: not-allowed; }

/* 首帧交接 chip：素材库「用作首帧」进入后的可见状态 */
.home-firstframe {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 4px 6px 4px 10px;
  border: 1px solid var(--cr-border, var(--wb-border));
  border-radius: 999px;
  background: var(--cr-accent-soft, var(--wb-brand-soft));
  color: var(--cr-accent, var(--wb-brand));
  font-size: 12px;
  width: fit-content;
}
.home-firstframe-clear {
  display: grid;
  place-items: center;
  width: 18px;
  height: 18px;
  border: 0;
  border-radius: 50%;
  background: transparent;
  color: inherit;
  cursor: pointer;
}
.home-firstframe-clear:hover { background: rgba(0, 0, 0, 0.08); }

/* 快捷起点：一行四条，无卡片边框 */
.home-starts {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
  gap: 6px;
  width: min(880px, 100%);
  margin: var(--wb-space-4) auto 0;
}

.home-start {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 14px;
  border: 0;
  border-radius: var(--cr-radius-ctl, 10px);
  background: transparent;
  color: inherit;
  text-decoration: none;
  text-align: left;
  cursor: pointer;
  transition: background var(--cr-dur-fast, 140ms) var(--cr-ease, ease);
}
.home-start:hover { background: var(--cr-surface-2, var(--wb-fill)); }

.home-start-icon {
  display: grid;
  place-items: center;
  width: 36px;
  height: 36px;
  flex: 0 0 36px;
  border-radius: 10px;
  background: var(--cr-surface, var(--wb-surface));
  border: 1px solid var(--cr-border, var(--wb-border));
  color: var(--cr-text-2, var(--wb-text-2));
}

.home-start-text { display: flex; flex-direction: column; gap: 2px; min-width: 0; }
.home-start-text strong { font-size: 13.5px; font-weight: 600; color: var(--cr-text, var(--wb-text)); }
.home-start-text em {
  font-style: normal;
  font-size: 12px;
  color: var(--cr-text-2, var(--wb-text-2));
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.home-works {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(232px, 1fr));
  gap: 12px;
}

.home-empty {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 6px;
}

.home-empty p { margin: 0; }

.home-empty-actions { display: flex; gap: 8px; margin-top: 4px; }

.home-pending {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 13px 15px;
  border: 1px solid var(--wb-border);
  border-radius: var(--wb-radius-lg);
  background: var(--wb-surface);
}

.home-pending-counts {
  display: flex;
  gap: 18px;
  flex-wrap: wrap;
  font-size: 12.5px;
  color: var(--wb-text-2);
}

.home-count em {
  font-style: normal;
  font-size: 15px;
  font-weight: 620;
  color: var(--wb-text);
  margin-right: 3px;
}

.home-count.is-warn em { color: var(--wb-warning); }

.home-jobs {
  list-style: none;
  margin: 0;
  padding: 0;
  border-top: 1px solid var(--wb-border);
}

.home-job {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 9px 0;
  border-bottom: 1px solid var(--wb-border);
  font-size: 12.5px;
}

.home-job:last-child { border-bottom: none; padding-bottom: 0; }

.home-job-text {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--wb-text-2);
}

.home-job-text strong { color: var(--wb-text); font-weight: 560; }

.home-job-time { color: var(--wb-muted); font-size: 11.5px; }

.home-job-link {
  color: var(--wb-brand);
  text-decoration: none;
  font-weight: 550;
}

.home-more {
  display: flex;
  gap: 16px;
  margin: 4px 0 0;
}

.home-link {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  border: 0;
  background: transparent;
  padding: 0;
  color: var(--wb-muted);
  font-size: 12px;
  text-decoration: none;
  cursor: pointer;
}

.home-link:hover { color: var(--wb-brand); }

.kind-options {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 8px;
}

.kind-option {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 2px;
  padding: 8px 10px;
  border: 1px solid var(--wb-border);
  border-radius: var(--wb-radius);
  background: var(--wb-bg);
  color: var(--wb-text);
  cursor: pointer;
  text-align: left;
}

.kind-option strong { font-size: 12.5px; }
.kind-option span { font-size: 11px; color: var(--wb-muted); }
.kind-option.selected { border-color: var(--wb-brand); box-shadow: 0 0 0 2px var(--wb-brand-soft); }
.kind-option:disabled { opacity: 0.55; cursor: not-allowed; }

@media (max-width: 640px) {
  .home-head { flex-direction: column; align-items: flex-start; }
  .home-seg { width: 100%; overflow-x: auto; justify-content: flex-start; }
  .home-seg-item { padding: 8px 14px; white-space: nowrap; }
  .home-compose { padding: 14px 14px 12px; }
  .home-tool-select { width: 120px; }
  .home-tool-model { width: 170px; }
  .home-tools-right { margin-left: 0; width: 100%; }
  .home-submit { width: 100%; }
  .home-starts { grid-template-columns: 1fr; }
}

@media (max-width: 720px) {
  .home-compose-foot { align-items: flex-start; }
  .home-compose-actions { width: 100%; justify-content: space-between; }
  .home-compose-hint { display: none; }
}
</style>
