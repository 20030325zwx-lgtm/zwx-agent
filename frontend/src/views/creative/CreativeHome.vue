<template>
  <div class="cr-page">
    <div class="cr-scroll">
      <div class="cr-inner">
        <header class="cr-head">
          <div>
            <p class="cr-kicker">AI 创作</p>
            <h1 class="cr-title">AI 创作</h1>
            <p class="cr-sub">输入想法，创作图片、视频、小说与漫画。任务与结果保存在服务端，刷新、离开页面或重启服务后仍能找回。</p>
            <div class="kind-strip" aria-label="创作类型">
              <span class="kind-chip is-open">图片创作 · 已开放</span>
              <span class="kind-chip is-open kind-chip-action" role="button" tabindex="0"
                    @click="openWorkflowCreate" @keydown.enter="openWorkflowCreate">工作流创作 · 已开放</span>
              <span class="kind-chip is-open">小说创作 · 已开放</span>
              <span class="kind-chip">视频创作 · 规划中</span>
              <span class="kind-chip">漫画创作 · 规划中</span>
            </div>
          </div>
          <button type="button" class="cr-primary" @click="openCreate">
            <Plus :size="15" /> 新建项目
          </button>
        </header>

        <div v-if="bindingChecked && !imageBinding" class="cr-banner">
          <AlertCircle :size="18" />
          <div class="cr-banner-body">
            <strong>还没有配置「图片生成」模型</strong>
            <p>创作模块只使用图片生成绑定，不会回退到对话模型。配置后回到这里继续，已输入的草稿会保留。</p>
          </div>
          <router-link to="/settings/model" class="cr-banner-link">去配置</router-link>
        </div>

        <section>
          <div class="cr-section-head">
            <h2>工作流创作</h2>
            <router-link to="/workflows">打开画布</router-link>
          </div>
          <div class="wf-entry">
            <span class="wf-entry-icon"><Workflow :size="18" /></span>
            <div class="wf-entry-body">
              <strong>用多步工作流创作</strong>
              <p>把搜图、理解、文案、生图、合成串成一条流程（如商品海报），运行产物自动归入创作项目的素材库与任务列表。</p>
            </div>
            <button type="button" class="cr-secondary" @click="openWorkflowCreate">
              <Workflow :size="14" /> 发起工作流
            </button>
          </div>
        </section>

        <section>
          <div class="cr-section-head">
            <h2>最近项目</h2>
            <router-link to="/creative/tasks">任务记录</router-link>
          </div>
          <p v-if="loading" class="cr-hint">加载中…</p>
          <p v-else-if="loadError" class="cr-error">{{ loadError }}</p>
          <p v-else-if="!projects.length" class="cr-empty">
            还没有创作项目。新建项目后即可开始创作（当前开放图片创作）。
          </p>
          <div v-else class="cr-grid">
            <router-link
              v-for="project in projects"
              :key="project.id"
              :to="`/creative/projects/${project.id}`"
              class="cr-card"
            >
              <div class="home-card-top">
                <span class="home-card-icon"><Palette :size="17" /></span>
                <span class="status-pair">
                  <span class="cr-kind-badge">{{ kindLabel(project.kind) }}</span>
                  <span class="cr-status" :class="project.status === 'ACTIVE' ? 'success' : 'muted'">
                    {{ project.status === 'ACTIVE' ? '进行中' : '已归档' }}
                  </span>
                </span>
              </div>
              <strong class="cr-card-name">{{ project.name }}</strong>
              <p class="cr-card-desc">{{ project.description || '暂无描述' }}</p>
              <span class="cr-card-time">更新于 {{ formatTime(project.updatedAt) }}</span>
            </router-link>
            <button type="button" class="cr-card cr-add" @click="openCreate">
              <Plus :size="20" />
              <span>新建项目</span>
            </button>
          </div>
        </section>

        <section>
          <div class="cr-section-head">
            <h2>最近任务</h2>
            <router-link to="/creative/assets">素材库</router-link>
          </div>
          <p v-if="!recentJobs.length" class="cr-hint">还没有生成任务。</p>
          <ul v-else class="home-jobs">
            <li v-for="job in recentJobs" :key="job.jobId" class="home-job">
              <span class="cr-status" :class="statusMeta(job.status).tone">{{ statusMeta(job.status).label }}</span>
              <span class="home-job-text">{{ statusMeta(job.status).hint }}</span>
              <span class="home-job-time">{{ formatTime(job.createdAt) }}</span>
              <router-link class="home-job-link" :to="`/creative/tasks/${job.jobId}`">查看</router-link>
            </li>
          </ul>
        </section>
      </div>
    </div>

    <div v-if="createOpen" class="cr-dialog-backdrop" @click.self="createOpen = false">
      <div class="cr-dialog" role="dialog" aria-modal="true" aria-labelledby="create-project-title">
        <h2 id="create-project-title">新建创作项目</h2>
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
          <span class="cr-field-help">视频、小说与漫画会复用同一套项目、草稿与素材数据，能力上线后无需迁移项目。</span>
        </div>
        <label class="cr-field">
          <span class="cr-field-label"><span>项目名称</span><span class="count">{{ form.name.length }}/80</span></span>
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
            <span class="cr-field-label"><span>目标创作项目</span><span class="count">产物与任务归入该项目</span></span>
            <select v-model="wfForm.projectId" class="cr-input">
              <option :value="null" disabled>选择项目</option>
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
import { AlertCircle, Palette, Plus, Workflow } from 'lucide-vue-next'
import {
  createCreativeProject, getWorkflowDefinition, listCreativeJobs, listCreativeProjects,
  listModelCatalog, listWorkflows, startWorkflow
} from '../../api'
import { formatTime, kindLabel, statusMeta } from './shared'
import Spinner from '../../components/ui/Spinner.vue'
import AssetParamField from '../../components/workflow/AssetParamField.vue'
import { buildRunForm } from './workflowInputs'

const router = useRouter()
const projects = ref([])
const recentJobs = ref([])
const loading = ref(true)
const loadError = ref('')
const createOpen = ref(false)
const creating = ref(false)
const createError = ref('')
const form = reactive({ name: '', description: '', kind: 'IMAGE' })
const kindOptions = [
  { value: 'IMAGE', label: '图片创作', enabled: true },
  { value: 'VIDEO', label: '视频创作', enabled: false },
  { value: 'NOVEL', label: '小说创作', enabled: true },
  { value: 'COMIC', label: '漫画创作', enabled: false }
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

function showToast(text, tone = '') {
  toast.text = text
  toast.tone = tone
  window.clearTimeout(showToast.timer)
  showToast.timer = window.setTimeout(() => { toast.text = '' }, 3200)
}

function openCreate() {
  form.name = ''
  form.description = ''
  form.kind = 'IMAGE'
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
    showToast('工作流已发起，产物将归入所选项目')
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
  const [projectResult, jobResult, catalogResult] = await Promise.allSettled([
    listCreativeProjects({ limit: 12 }),
    listCreativeJobs({ limit: 6 }),
    listModelCatalog()
  ])
  if (projectResult.status === 'fulfilled') {
    projects.value = projectResult.value.items || []
  } else {
    loadError.value = '项目加载失败，请确认后端服务可用'
  }
  if (jobResult.status === 'fulfilled') recentJobs.value = jobResult.value.items || []
  if (catalogResult.status === 'fulfilled') {
    imageBinding.value = catalogResult.value.userBindings?.image_generation
      || catalogResult.value.systemBindings?.image_generation || null
  }
  bindingChecked.value = true
  loading.value = false
}

onMounted(load)
</script>

<style scoped src="./creative.css"></style>

<style scoped>
.kind-strip {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-top: 10px;
}
.kind-chip {
  font-size: 11.5px;
  padding: 3px 9px;
  border-radius: 999px;
  border: 1px solid var(--wb-border);
  color: var(--wb-muted);
}
.kind-chip.is-open {
  color: var(--wb-brand);
  border-color: color-mix(in srgb, var(--wb-brand) 40%, transparent);
  background: var(--wb-brand-soft);
}
.kind-chip-action { cursor: pointer; }
.wf-entry {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 14px 16px;
  border: 1px solid var(--wb-border);
  border-radius: var(--wb-radius-lg);
  background: var(--wb-surface);
}
.wf-entry-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 36px;
  height: 36px;
  border-radius: 10px;
  background: var(--wb-brand-soft);
  color: var(--wb-brand);
  flex-shrink: 0;
}
.wf-entry-body { flex: 1; min-width: 0; }
.wf-entry-body strong { font-size: 13px; }
.wf-entry-body p {
  margin: 3px 0 0;
  font-size: 12px;
  color: var(--wb-muted);
  line-height: 1.5;
}
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
.status-pair { display: flex; align-items: center; gap: 6px; }
.cr-kind-badge {
  font-size: 11px;
  padding: 1px 7px;
  border-radius: 999px;
  border: 1px solid var(--wb-border);
  color: var(--wb-text-2);
}

.home-card-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.home-card-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 30px;
  height: 30px;
  border-radius: 8px;
  background: var(--wb-brand-soft);
  color: var(--wb-brand);
}

.home-jobs {
  list-style: none;
  margin: 0;
  padding: 0;
  border: 1px solid var(--wb-border);
  border-radius: var(--wb-radius-lg);
  background: var(--wb-surface);
  overflow: hidden;
}

.home-job {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 11px 14px;
  border-bottom: 1px solid var(--wb-border);
  font-size: 12.5px;
}

.home-job:last-child { border-bottom: none; }

.home-job-text {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--wb-text-2);
}

.home-job-time { color: var(--wb-muted); font-size: 11.5px; }

.home-job-link {
  color: var(--wb-brand);
  text-decoration: none;
  font-weight: 550;
}
</style>
