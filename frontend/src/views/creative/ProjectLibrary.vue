<template>
  <div class="cr-page">
    <div class="cr-scroll">
      <div class="cr-inner">
        <header class="cr-head">
          <div>
            <p class="cr-kicker">作品</p>
            <h1 class="cr-title">全部作品</h1>
            <p class="cr-sub">按类型筛选、搜索标题或描述；默认按最近更新排序，封面取自该作品最新的真实产物。</p>
          </div>
          <button type="button" class="cr-primary" @click="openCreate">
            <Plus :size="15" /> 新建作品
          </button>
        </header>

        <div class="lib-filters">
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
            <select v-model="sort" class="cr-input lib-sort" aria-label="排序方式" @change="reload">
              <option value="updated">最近更新</option>
              <option value="created">创建时间</option>
            </select>
          </div>
        </div>

        <p v-if="loading && !items.length" class="cr-hint">加载中…</p>
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
          <div class="lib-grid">
            <ProjectCard v-for="project in items" :key="project.id" :project="project" />
          </div>
          <div class="lib-foot">
            <button v-if="nextCursor" type="button" class="cr-secondary" :disabled="loadingMore"
                    @click="loadMore">
              <Spinner v-if="loadingMore" /> {{ loadingMore ? '加载中…' : '加载更多' }}
            </button>
            <span v-else class="lib-count">共 {{ items.length }} 个作品</span>
          </div>
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
import { useRouter } from 'vue-router'
import { Plus } from 'lucide-vue-next'
import { createCreativeProject, listCreativeProjects } from '../../api'
import ProjectCard from '../../components/creative/ProjectCard.vue'
import Spinner from '../../components/ui/Spinner.vue'

const PAGE_SIZE = 24
const router = useRouter()
const items = ref([])
const nextCursor = ref(null)
const kind = ref('')
const keyword = ref('')
const sort = ref('updated')
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

onMounted(reload)
onBeforeUnmount(() => window.clearTimeout(debounceTimer))
</script>

<style scoped src="./creative.css"></style>

<style scoped>
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
  padding: 5px 12px;
  border: 1px solid var(--wb-border);
  border-radius: 999px;
  background: transparent;
  color: var(--wb-text-2);
  font-size: 12.5px;
  cursor: pointer;
  transition: border-color 0.15s, color 0.15s, background 0.15s;
}

.lib-chip.selected {
  border-color: var(--wb-brand);
  background: var(--wb-brand-soft);
  color: var(--wb-brand);
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
  gap: 12px;
}

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
