<template>
  <div class="cr-page">
    <div class="cr-scroll">
      <div class="cr-inner">
        <header class="cr-head home-head">
          <div>
            <p class="cr-kicker">AI 创作</p>
            <h1 class="cr-title">想做点什么？</h1>
            <p class="cr-sub">写一句想法，选一种创作类型，进入作品工作区继续细化。作品、任务与素材都在服务端，离开页面也能找回。</p>
          </div>
          <button type="button" class="cr-secondary" @click="openCreate">
            <Plus :size="15" /> 新建作品
          </button>
        </header>

        <section class="home-compose">
          <textarea
            v-model.trim="idea"
            class="cr-textarea home-idea"
            rows="3"
            maxlength="500"
            placeholder="例如：雨夜车站，一个女孩把怀表交给陌生人，然后列车进站"
            @keydown.ctrl.enter="startFromIdea"
            @keydown.meta.enter="startFromIdea"
          />
          <div class="home-compose-foot">
            <div class="home-kind" role="radiogroup" aria-label="创作类型">
              <button
                v-for="option in kindOptions"
                :key="option.value"
                type="button"
                class="home-kind-chip"
                :class="{ selected: composeKind === option.value }"
                :disabled="!option.enabled"
                role="radio"
                :aria-checked="composeKind === option.value"
                @click="composeKind = option.value"
              >
                {{ option.label }}
                <em v-if="!option.enabled">规划中</em>
              </button>
            </div>
            <div class="home-compose-actions">
              <span class="home-compose-hint">创建作品不会计费，生成时才会调用模型</span>
              <button type="button" class="cr-primary" :disabled="!idea || starting" @click="startFromIdea">
                <Spinner v-if="starting" /> {{ starting ? '创建中…' : '开始创作' }}
              </button>
            </div>
          </div>
          <p v-if="startError" class="cr-error">{{ startError }}</p>
        </section>

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
            <h2>最近作品</h2>
            <router-link to="/creative/works">全部作品</router-link>
          </div>
          <p v-if="loading" class="cr-hint">加载中…</p>
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
            <h2>待处理</h2>
            <router-link to="/creative/tasks">任务记录</router-link>
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
            </div>
            <p v-if="!pendingJobs.length" class="cr-hint">
              没有需要处理的任务。生成中或失败的镜头会出现在这里。
            </p>
            <ul v-else class="home-jobs">
              <li v-for="job in pendingJobs" :key="job.jobId" class="home-job">
                <span class="cr-status" :class="statusMeta(job.status).tone">{{ statusMeta(job.status).label }}</span>
                <span class="home-job-text">
                  <strong>{{ projectName(job.projectId) }}</strong>
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
          <router-link to="/creative/assets" class="home-link">
            <Images :size="14" /> 素材库
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
            <select v-model="wfForm.workflowKey" class="cr-input" @change="loadWorkflowInputs(wfForm.workflowKey)">
              <option value="" disabled>选择工作流</option>
              <option v-for="wf in workflows" :key="wf.key" :value="wf.key" @click="loadWorkflowInputs(wf.key)">
                {{ wf.name || wf.key }}（{{ (wf.steps || []).length }} 节点）
              </option>
            </select>
            <span v-if="selectedWorkflow?.description" class="cr-field-help">{{ selectedWorkflow.description }}</span>
          </label>
          <label class="cr-field">
            <span class="cr-field-label"><span>目标作品</span><span class="count">产物与任务归入该作品</span></span>
            <select v-model="wfForm.projectId" class="cr-input">
              <option :value="null" disabled>选择作品</option>
              <option v-for="project in activeProjects" :key="project.id" :value="project.id">
                {{ project.name }}
              </option>
            </select>
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
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { AlertCircle, Images, Plus, Workflow } from 'lucide-vue-next'
import {
  createCreativeDraft, createCreativeProject, getCreativeHealth, getWorkflowDefinition,
  listCreativeJobs, listCreativeProjects, listModelCatalog, listWorkflows, startWorkflow
} from '../../api'
import { formatTime, statusMeta } from './shared'
import ProjectCard from '../../components/creative/ProjectCard.vue'
import Spinner from '../../components/ui/Spinner.vue'
import AssetParamField from '../../components/workflow/AssetParamField.vue'
import { buildRunForm } from './workflowInputs'

const router = useRouter()
const projects = ref([])
const recentJobs = ref([])
const health = ref(null)
const loading = ref(true)
const loadError = ref('')
const idea = ref('')
const composeKind = ref('IMAGE')
const starting = ref(false)
const startError = ref('')
const createOpen = ref(false)
const creating = ref(false)
const createError = ref('')
const form = reactive({ name: '', description: '', kind: 'IMAGE' })
const kindOptions = [
  { value: 'IMAGE', label: '图片', enabled: true },
  { value: 'VIDEO', label: '视频', enabled: true },
  { value: 'NOVEL', label: '小说', enabled: true },
  { value: 'COMIC', label: '漫画', enabled: false }
]
const imageBinding = ref(null)
const bindingChecked = ref(false)
const toast = reactive({ text: '', tone: '' })
const wfOpen = ref(false)
const wfLoading = ref(false)
const wfStarting = ref(false)
const wfError = ref('')
const workflows = ref([])
const wfForm = reactive({ workflowKey: '', projectId: null, fields: [], input: {} })
const activeProjects = computed(() => projects.value.filter(p => p.status === 'ACTIVE'))
const selectedWorkflow = computed(() => workflows.value.find(wf => wf.key === wfForm.workflowKey))

// 待处理：健康接口给在途总量，任务列表给可点开的条目（失败优先，其次结果待确认）
const jobCounts = computed(() => {
  const jobs = health.value?.jobs || {}
  return { active: jobs.active || 0 }
})
const failedJobs = computed(() => recentJobs.value
  .filter(job => job.status === 'FAILED' || job.status === 'INGEST_FAILED'))
const uncertainJobs = computed(() => recentJobs.value
  .filter(job => job.status === 'RECONCILING'))
const pendingJobs = computed(() => [...failedJobs.value, ...uncertainJobs.value].slice(0, 5))
const failedCount = computed(() => failedJobs.value.length)
const uncertainCount = computed(() => uncertainJobs.value.length)

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

/** 想法直接起项目：名称取首行前 30 字，完整想法进描述；图片项目顺带写入草稿，进入即可继续编辑。 */
async function startFromIdea() {
  if (!idea.value || starting.value) return
  starting.value = true
  startError.value = ''
  const text = idea.value
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
    router.push(`/creative/projects/${project.id}`)
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
  const [projectResult, jobResult, catalogResult, healthResult] = await Promise.allSettled([
    listCreativeProjects({ limit: 6 }),
    listCreativeJobs({ limit: 20 }),
    listModelCatalog(),
    getCreativeHealth()
  ])
  if (projectResult.status === 'fulfilled') {
    projects.value = projectResult.value.items || []
  } else {
    loadError.value = '作品加载失败，请确认后端服务可用'
  }
  if (jobResult.status === 'fulfilled') recentJobs.value = jobResult.value.items || []
  if (catalogResult.status === 'fulfilled') {
    imageBinding.value = catalogResult.value.userBindings?.image_generation
      || catalogResult.value.systemBindings?.image_generation || null
  }
  if (healthResult.status === 'fulfilled') health.value = healthResult.value
  bindingChecked.value = true
  loading.value = false
}

onMounted(load)
</script>

<style scoped src="./creative.css"></style>

<style scoped>
.home-head { align-items: flex-start; }

.home-compose {
  display: flex;
  flex-direction: column;
  gap: 10px;
  padding: 14px 16px 12px;
  border: 1px solid var(--wb-border);
  border-radius: var(--wb-radius-lg);
  background: var(--wb-surface);
}

.home-idea {
  min-height: 76px;
  border: 0;
  padding: 2px 0;
  background: transparent;
  font-size: 14px;
  line-height: 1.6;
  resize: vertical;
}

.home-idea:focus { outline: none; }

.home-compose-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
  border-top: 1px solid var(--wb-border);
  padding-top: 10px;
}

.home-kind {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
}

.home-kind-chip {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 4px 11px;
  border: 1px solid var(--wb-border);
  border-radius: 999px;
  background: transparent;
  color: var(--wb-text-2);
  font-size: 12px;
  cursor: pointer;
  transition: border-color 0.15s, color 0.15s, background 0.15s;
}

.home-kind-chip em {
  font-style: normal;
  font-size: 10.5px;
  color: var(--wb-muted);
}

.home-kind-chip.selected {
  border-color: var(--wb-brand);
  background: var(--wb-brand-soft);
  color: var(--wb-brand);
}

.home-kind-chip:disabled { opacity: 0.5; cursor: not-allowed; }

.home-compose-actions {
  display: flex;
  align-items: center;
  gap: 12px;
}

.home-compose-hint {
  font-size: 11.5px;
  color: var(--wb-muted);
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

@media (max-width: 720px) {
  .home-compose-foot { align-items: flex-start; }
  .home-compose-actions { width: 100%; justify-content: space-between; }
  .home-compose-hint { display: none; }
}
</style>
