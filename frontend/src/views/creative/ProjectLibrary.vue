<template>
  <div class="cr-page">
    <div class="cr-scroll">
      <div class="cr-inner">
        <header class="cr-head">
          <div>
            <p class="cr-kicker">我的创作</p>
            <h1 class="cr-title">我的创作</h1>
            <p class="cr-sub">项目是持续创作的容器，作品是值得回看的产出。两个视图都能找回上下文。</p>
          </div>
          <button type="button" class="cr-primary" @click="openCreate">
            <Plus :size="15" /> 新建作品
          </button>
        </header>

        <!-- 项目 / 作品双视图（redesign §5.2）：作品视图基于现有成功产物聚合，不新增媒体存储 -->
        <div class="lib-scope" role="tablist" aria-label="视图切换">
          <button type="button" role="tab" class="lib-scope-btn" :class="{ active: scope === 'projects' }"
                  :aria-selected="scope === 'projects'" @click="setScope('projects')">
            项目
          </button>
          <button type="button" role="tab" class="lib-scope-btn" :class="{ active: scope === 'works' }"
                  :aria-selected="scope === 'works'" @click="setScope('works')">
            作品
          </button>
        </div>

        <div v-if="scope === 'projects'" class="lib-filters">
          <div class="lib-kinds" role="radiogroup" aria-label="作品类型">
            <button
              v-for="option in kindFilters"
              :key="option.value"
              type="button"
              class="lib-chip"
              :class="{ selected: kind === option.value }"
              role="radio"
              :aria-checked="kind === option.value"
              @click="setKind(option.value)"
            >
              {{ option.label }}
            </button>
          </div>
          <div class="lib-tools">
            <input
              v-model.trim="keyword"
              class="cr-input lib-search"
              type="search"
              maxlength="80"
              placeholder="搜索作品名称或描述"
              @input="scheduleReload"
            />
            <DropdownSelect v-model="sort" class="lib-sort" size="sm" :options="sortOptions"
                            aria-label="排序方式" @change="reload" />
            <div class="lib-view" role="radiogroup" aria-label="视图">
              <button type="button" class="lib-view-btn" :class="{ active: view === 'grid' }"
                      role="radio" :aria-checked="view === 'grid'" title="封面网格" @click="setView('grid')">
                <LayoutGrid :size="15" />
              </button>
              <button type="button" class="lib-view-btn" :class="{ active: view === 'list' }"
                      role="radio" :aria-checked="view === 'list'" title="紧凑列表" @click="setView('list')">
                <List :size="15" />
              </button>
            </div>
          </div>
        </div>

        <template v-if="scope === 'projects'">
          <AsterLoader v-if="loading && !items.length" size="18" />
          <p v-else-if="error" class="cr-error">{{ error }}</p>
          <div v-else-if="!items.length" class="cr-empty lib-empty">
            <strong>{{ emptyTitle }}</strong>
            <p>{{ emptyHint }}</p>
            <div class="lib-empty-actions">
              <button type="button" class="cr-primary" @click="openCreate">新建作品</button>
              <router-link to="/creative" class="cr-secondary">回到创作首页</router-link>
            </div>
          </div>
          <template v-else>
            <div v-if="view === 'grid'" class="lib-grid">
              <ProjectCard v-for="project in items" :key="project.id" :project="project" />
            </div>
            <div v-else class="lib-list">
              <ProjectRow v-for="project in items" :key="project.id" :project="project" />
            </div>
            <div class="lib-foot">
              <button v-if="nextCursor" type="button" class="cr-secondary" :disabled="loadingMore"
                      @click="loadMore">
                <Spinner v-if="loadingMore" /> {{ loadingMore ? '加载中…' : '加载更多' }}
              </button>
              <span v-else class="lib-count">共 {{ items.length }} 个项目</span>
            </div>
          </template>
        </template>

        <!-- 作品视图：成功产出的聚合投影；点击回原项目任务，上下文不丢（redesign §5.2） -->
        <template v-else>
          <AsterLoader v-if="worksLoading && !works.length" size="18" />
          <p v-else-if="worksError" class="cr-error">{{ worksError }}</p>
          <div v-else-if="!works.length" class="cr-empty lib-empty">
            <strong>还没有作品</strong>
            <p>生成成功的图片与视频会出现在这里；项目仍在「项目」视图。</p>
            <router-link to="/creative" class="cr-primary">去创作</router-link>
          </div>
          <template v-else>
            <div class="lib-grid">
              <article v-for="work in works" :key="work.jobId" class="work-card" tabindex="0"
                       role="link" :aria-label="`打开作品：${work.title}`"
                       @click="openWork(work)" @keydown.enter="openWork(work)">
                <div class="work-thumb">
                  <video v-if="work.mediaType === 'VIDEO' && workVideo(work)"
                         :src="workVideo(work)" preload="metadata" muted></video>
                  <img v-else-if="workThumb(work)" :src="workThumb(work)" :alt="work.title" loading="lazy" />
                  <div v-else class="work-thumb-placeholder">{{ work.mediaType === 'VIDEO' ? '视频' : '图片' }}</div>
                </div>
                <div class="work-meta">
                  <strong class="work-title">{{ work.title }}</strong>
                  <span class="work-sub">{{ work.projectName }} · {{ work.outputCount }} 个版本 · {{ formatTime(work.createdAt) }}</span>
                </div>
              </article>
            </div>
            <div class="lib-foot">
              <span class="lib-count">共 {{ works.length }} 组产出（按任务聚合）</span>
            </div>
          </template>
        </template>
      </div>
    </div>

    <div v-if="createOpen" class="cr-dialog-backdrop" @click.self="createOpen = false">
      <div class="cr-dialog" role="dialog" aria-modal="true" aria-labelledby="lib-create-title">
        <h2 id="lib-create-title">新建作品</h2>
        <div class="cr-field">
          <span class="cr-field-label"><span>创作类型</span></span>
          <div class="lib-kind-options" role="radiogroup" aria-label="创作类型">
            <button
              v-for="option in kindOptions"
              :key="option.value"
              type="button"
              class="lib-kind-option"
              :class="{ selected: form.kind === option.value }"
              :disabled="!option.enabled"
              role="radio"
              :aria-checked="form.kind === option.value"
              @click="form.kind = option.value"
            >
              <strong>{{ option.label }}</strong>
              <span>{{ option.enabled ? '可使用' : '规划中' }}</span>
            </button>
          </div>
        </div>
        <label class="cr-field">
          <span class="cr-field-label"><span>作品名称</span><span class="count">{{ form.name.length }}/80</span></span>
          <input v-model.trim="form.name" class="cr-input" maxlength="80" placeholder="如：雨夜车站" />
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
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import AsterLoader from '../../components/ui/AsterLoader.vue'
import { useRouter } from 'vue-router'
import { LayoutGrid, List, Plus } from 'lucide-vue-next'
import { createCreativeProject, listCreativeJobs, listCreativeProjects } from '../../api'
import { formatTime, useAssetUrls, useVideoUrls } from './shared'
import ProjectCard from '../../components/creative/ProjectCard.vue'
import ProjectRow from '../../components/creative/ProjectRow.vue'
import DropdownSelect from '../../components/ui/DropdownSelect.vue'
import Spinner from '../../components/ui/Spinner.vue'

const PAGE_SIZE = 24
const router = useRouter()
const items = ref([])
const nextCursor = ref(null)
const kind = ref('')
const keyword = ref('')
const sort = ref('updated')

// 项目 / 作品双视图（redesign §5.2）：作品 = 成功任务产出的聚合投影
const SCOPE_KEY = 'aster.creative.library.scope'
const scope = ref(localStorage.getItem(SCOPE_KEY) === 'works' ? 'works' : 'projects')
const works = ref([])
const worksLoading = ref(false)
const worksError = ref('')
const worksLoaded = ref(false)
const assetUrls = useAssetUrls()
const videoUrls = useVideoUrls()

function setScope(next) {
  scope.value = next
  try { localStorage.setItem(SCOPE_KEY, next) } catch (error) { /* 忽略 */ }
  if (next === 'works') loadWorks()
}

async function loadWorks() {
  if (worksLoaded.value || worksLoading.value) return
  worksLoading.value = true
  worksError.value = ''
  try {
    const [jobsData] = await Promise.all([
      listCreativeJobs({ limit: 40 }),
      items.value.length ? Promise.resolve() : listCreativeProjects({ limit: 50 }).then(data => {
        items.value = data.items || []
      }).catch(() => null)
    ])
    const projectNameOf = id => items.value.find(project => project.id === id)?.name || '未命名项目'
    works.value = (jobsData.items || [])
      .filter(job => job.status === 'SUCCEEDED' && (job.outputCount || 0) > 0)
      .map(job => {
        const mediaType = String(job.operation || '').endsWith('video') ? 'VIDEO' : 'IMAGE'
        const item = {
          jobId: job.jobId,
          projectId: job.projectId,
          projectName: projectNameOf(job.projectId),
          title: job.prompt ? truncateTitle(job.prompt, 40) : '生成作品',
          outputCount: job.outputCount || 0,
          createdAt: job.createdAt,
          mediaType,
          firstAssetId: job.firstAssetId || ''
        }
        if (mediaType === 'VIDEO') videoUrls.videoUrlFor(item.firstAssetId)
        else assetUrls.thumbFor(item.firstAssetId)
        return item
      })
    worksLoaded.value = true
  } catch (exception) {
    worksError.value = exception.response?.data?.error || '作品加载失败，请确认后端服务可用'
  } finally {
    worksLoading.value = false
  }
}

function truncateTitle(text, max) {
  const value = String(text || '').replace(/\s+/g, ' ').trim()
  return value.length > max ? `${value.slice(0, max)}…` : value
}

function workThumb(work) {
  return assetUrls.thumbs[work.firstAssetId] || ''
}

function workVideo(work) {
  return videoUrls.videoUrls[work.firstAssetId]?.url || ''
}

function openWork(work) {
  router.push(`/creative/projects/${work.projectId}?tab=tasks&jobId=${work.jobId}`)
}
const VIEW_KEY = 'aster.creative.works.view'
const view = ref(localStorage.getItem(VIEW_KEY) === 'list' ? 'list' : 'grid')

function setView(next) {
  view.value = next
  try { localStorage.setItem(VIEW_KEY, next) } catch (error) { /* 隐私模式下忽略 */ }
}
const loading = ref(true)
const loadingMore = ref(false)
const error = ref('')
const createOpen = ref(false)
const creating = ref(false)
const createError = ref('')
const form = reactive({ name: '', kind: 'IMAGE' })

const kindFilters = [
  { value: '', label: '全部' },
  { value: 'IMAGE', label: '图片' },
  { value: 'VIDEO', label: '视频' },
  { value: 'NOVEL', label: '小说' }
]
const sortOptions = [
  { value: 'updated', label: '最近更新' },
  { value: 'created', label: '创建时间' }
]
const kindOptions = [
  { value: 'IMAGE', label: '图片', enabled: true },
  { value: 'VIDEO', label: '视频', enabled: true },
  { value: 'NOVEL', label: '小说', enabled: true },
  { value: 'COMIC', label: '漫画', enabled: false }
]

const emptyTitle = computed(() => (keyword.value || kind.value ? '没有匹配的作品' : '还没有作品'))
const emptyHint = computed(() => (keyword.value || kind.value
  ? '换个关键词或切回「全部」；也可以直接新建一个作品。'
  : '写一句想法就能开始，作品、任务与素材都会保存在服务端。'))

let debounceTimer = null
function scheduleReload() {
  window.clearTimeout(debounceTimer)
  debounceTimer = window.setTimeout(reload, 320)
}

function setKind(value) {
  kind.value = value
  reload()
}

function params(cursor) {
  const query = { limit: PAGE_SIZE, sort: sort.value }
  if (kind.value) query.kind = kind.value
  if (keyword.value) query.q = keyword.value
  if (cursor) query.cursor = cursor
  return query
}

async function reload() {
  loading.value = true
  error.value = ''
  try {
    const data = await listCreativeProjects(params(null))
    items.value = data.items || []
    nextCursor.value = data.nextCursor || null
  } catch (exception) {
    error.value = exception.response?.data?.error || '作品加载失败，请确认后端服务可用'
    items.value = []
    nextCursor.value = null
  } finally {
    loading.value = false
  }
}

async function loadMore() {
  if (!nextCursor.value || loadingMore.value) return
  loadingMore.value = true
  try {
    const data = await listCreativeProjects(params(nextCursor.value))
    items.value = [...items.value, ...(data.items || [])]
    nextCursor.value = data.nextCursor || null
  } catch (exception) {
    error.value = exception.response?.data?.error || '加载更多失败，请稍后重试'
  } finally {
    loadingMore.value = false
  }
}

function openCreate() {
  form.name = ''
  form.kind = kind.value || 'IMAGE'
  createError.value = ''
  createOpen.value = true
}

async function create() {
  if (!form.name) return
  creating.value = true
  createError.value = ''
  try {
    const project = await createCreativeProject({ name: form.name, kind: form.kind })
    createOpen.value = false
    router.push(`/creative/projects/${project.id}`)
  } catch (exception) {
    createError.value = exception.response?.data?.error || '创建失败，请稍后重试'
  } finally {
    creating.value = false
  }
}

onMounted(() => {
  reload()
  if (scope.value === 'works') loadWorks()
})
onBeforeUnmount(() => window.clearTimeout(debounceTimer))
</script>

<style scoped src="./creative.css"></style>

<style scoped>
/* 项目 / 作品切换（redesign §5.2）：分段控件，选中底片平移 */
.lib-scope {
  display: inline-flex;
  gap: 2px;
  padding: 4px;
  border: 0;
  border-radius: 999px;
  background: var(--cr-surface-2, var(--wb-fill));
  width: fit-content;
}

.lib-scope-btn {
  padding: 7px 20px;
  border: 0;
  border-radius: 999px;
  background: transparent;
  color: var(--cr-text-2, var(--wb-text-2));
  font-size: 13px;
  cursor: pointer;
  transition: background var(--cr-dur-fast, 140ms) var(--cr-ease, ease), color var(--cr-dur-fast, 140ms) var(--cr-ease, ease), box-shadow var(--cr-dur-fast, 140ms) var(--cr-ease, ease);
}

.lib-scope-btn.active {
  background: var(--cr-surface, #fff);
  color: var(--cr-text, var(--wb-text));
  font-weight: 600;
  box-shadow: 0 1px 3px rgba(20, 18, 24, 0.08), 0 1px 1px rgba(20, 18, 24, 0.04);
}

.work-card {
  display: flex;
  flex-direction: column;
  border: 1px solid var(--wb-border);
  border-radius: var(--cr-radius-media, 14px);
  background: var(--wb-surface);
  overflow: hidden;
  cursor: pointer;
  transition: border-color var(--cr-dur-fast, 140ms) var(--cr-ease, ease), transform var(--cr-dur-fast, 140ms) var(--cr-ease, ease);
}

.work-card:hover { border-color: var(--wb-border-strong); transform: translateY(-1px); }

.work-thumb {
  aspect-ratio: 4 / 3;
  background: var(--cr-media-stage, #101113);
  display: grid;
  place-items: center;
  overflow: hidden;
}

.work-thumb img,
.work-thumb video {
  width: 100%;
  height: 100%;
  object-fit: contain;
  display: block;
}

.work-thumb-placeholder { color: rgba(255, 255, 255, 0.5); font-size: 12px; }

.work-meta {
  display: flex;
  flex-direction: column;
  gap: 3px;
  padding: 10px 12px 12px;
}

.work-title {
  font-size: 13px;
  font-weight: 600;
  color: var(--wb-text);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.work-sub { font-size: 11.5px; color: var(--wb-muted); }

.lib-filters {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
}

.lib-kinds {
  display: flex;
  gap: 6px;
  flex-wrap: wrap;
}

.lib-chip {
  padding: 6px 14px;
  border: 1px solid var(--cr-border, var(--wb-border));
  border-radius: 999px;
  background: transparent;
  color: var(--cr-text-2, var(--wb-text-2));
  font-size: 12.5px;
  cursor: pointer;
  transition: border-color var(--cr-dur-fast, 140ms) var(--cr-ease, ease), color var(--cr-dur-fast, 140ms) var(--cr-ease, ease), background var(--cr-dur-fast, 140ms) var(--cr-ease, ease);
}

.lib-chip.selected {
  border-color: var(--cr-border, var(--wb-border));
  background: var(--cr-surface, #fff);
  color: var(--cr-text, var(--wb-text));
  font-weight: 600;
  box-shadow: 0 1px 2px rgba(20, 18, 24, 0.06);
}

.lib-tools {
  display: flex;
  gap: 8px;
  align-items: center;
}

.lib-search {
  width: 220px;
}

.lib-sort {
  width: 118px;
}

.lib-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(232px, 1fr));
  gap: var(--wb-space-4);
}

.lib-list {
  border: 1px solid var(--cr-border, var(--wb-border));
  border-radius: var(--cr-radius-media, 14px);
  background: var(--wb-surface);
  overflow: hidden;
}

.lib-view { display: flex; border: 1px solid var(--wb-border); border-radius: var(--wb-radius-sm); overflow: hidden; }
.lib-view-btn {
  display: grid;
  place-items: center;
  width: 30px;
  height: 30px;
  border: 0;
  background: transparent;
  color: var(--wb-muted);
  cursor: pointer;
}
.lib-view-btn:hover { background: var(--wb-fill); color: var(--wb-text); }
.lib-view-btn.active { background: var(--wb-brand-soft); color: var(--wb-brand); }

.lib-foot {
  display: flex;
  justify-content: center;
  padding: 4px 0 8px;
}

.lib-count {
  font-size: 12px;
  color: var(--wb-muted);
}

.lib-empty {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 6px;
}

.lib-empty p { margin: 0; }

.lib-empty-actions {
  display: flex;
  gap: 8px;
  margin-top: 4px;
  align-items: center;
}

.lib-kind-options {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 8px;
}

.lib-kind-option {
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

.lib-kind-option strong { font-size: 12.5px; }
.lib-kind-option span { font-size: 11px; color: var(--wb-muted); }
.lib-kind-option.selected { border-color: var(--wb-brand); box-shadow: 0 0 0 2px var(--wb-brand-soft); }
.lib-kind-option:disabled { opacity: 0.55; cursor: not-allowed; }

@media (max-width: 720px) {
  .lib-tools { width: 100%; }
  .lib-search { flex: 1; width: auto; }
}
</style>
