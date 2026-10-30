<template>
  <div class="cr-page">
    <div class="cr-scroll">
      <div class="cr-inner">
        <header class="cr-head">
          <div>
            <p class="cr-kicker">AI 创作</p>
            <h1 class="cr-title">图片创作</h1>
            <p class="cr-sub">输入想法生成图片。任务与结果保存在服务端，刷新、离开页面或重启服务后仍能找回。</p>
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
            <h2>最近项目</h2>
            <router-link to="/creative/tasks">任务记录</router-link>
          </div>
          <p v-if="loading" class="cr-hint">加载中…</p>
          <p v-else-if="loadError" class="cr-error">{{ loadError }}</p>
          <p v-else-if="!projects.length" class="cr-empty">
            还没有创作项目。新建项目后输入提示词即可生成第一张图片。
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
                <span class="cr-status" :class="project.status === 'ACTIVE' ? 'success' : 'muted'">
                  {{ project.status === 'ACTIVE' ? '进行中' : '已归档' }}
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

    <p v-if="toast.text" class="cr-toast" :class="toast.tone">{{ toast.text }}</p>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { AlertCircle, Palette, Plus } from 'lucide-vue-next'
import { createCreativeProject, listCreativeJobs, listCreativeProjects, listModelCatalog } from '../../api'
import { formatTime, statusMeta } from './shared'

const router = useRouter()
const projects = ref([])
const recentJobs = ref([])
const loading = ref(true)
const loadError = ref('')
const createOpen = ref(false)
const creating = ref(false)
const createError = ref('')
const form = reactive({ name: '', description: '' })
const imageBinding = ref(null)
const bindingChecked = ref(false)
const toast = reactive({ text: '', tone: '' })

function showToast(text, tone = '') {
  toast.text = text
  toast.tone = tone
  window.clearTimeout(showToast.timer)
  showToast.timer = window.setTimeout(() => { toast.text = '' }, 3200)
}

function openCreate() {
  form.name = ''
  form.description = ''
  createError.value = ''
  createOpen.value = true
}

async function create() {
  if (!form.name) return
  creating.value = true
  createError.value = ''
  try {
    const project = await createCreativeProject({ name: form.name, description: form.description || null })
    createOpen.value = false
    router.push(`/creative/projects/${project.id}`)
  } catch (error) {
    createError.value = error.response?.data?.error || '创建失败，请稍后重试'
  } finally {
    creating.value = false
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
