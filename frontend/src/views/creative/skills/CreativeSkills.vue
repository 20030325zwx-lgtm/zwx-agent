<template>
  <div class="cr-page">
    <div class="cr-scroll">
      <div class="cr-inner">
        <header class="cr-head">
          <div>
            <p class="cr-kicker">技能</p>
            <h1 class="cr-title">技能库</h1>
            <p class="cr-sub">找到适合这次创作的方法。技能是可选增强——不选技能也能直接生成。</p>
          </div>
        </header>

        <div class="sk-filters">
          <input
            v-model.trim="keyword"
            class="cr-input sk-search"
            type="search"
            maxlength="60"
            placeholder="搜索技能名称、场景或说明"
            aria-label="搜索技能"
          />
          <DropdownSelect v-model="category" class="sk-cat" size="sm" :options="categoryOptions"
                          aria-label="分类" />
          <DropdownSelect v-model="stage" class="sk-stage" size="sm" :options="stageOptions"
                          aria-label="创作阶段" />
          <label class="sk-available">
            <input v-model="onlyReady" type="checkbox" />
            当前可用
          </label>
        </div>

        <p v-if="loading" class="cr-hint">加载中…</p>
        <p v-else-if="error" class="cr-error">{{ error }} <button type="button" class="sk-retry" @click="load">重试</button></p>
        <div v-else-if="!filtered.length" class="cr-empty sk-empty">
          <strong>{{ keyword || category || stage || onlyReady ? '没有匹配的技能' : '还没有可用技能' }}</strong>
          <p>{{ keyword || category || stage || onlyReady ? '换个关键词或清除筛选。' : '技能目录由服务端发布；稍后再来看看。' }}</p>
          <button v-if="keyword || category || stage || onlyReady" type="button" class="cr-secondary" @click="clearFilters">
            清除筛选
          </button>
        </div>
        <div v-else class="sk-grid">
          <article v-for="skill in filtered" :key="skill.slug" class="sk-card"
                   :class="{ 'is-missing': skill.compat !== 'READY' }">
            <div class="sk-card-head">
              <h3>{{ skill.name }}</h3>
              <span class="cr-status" :class="skill.compat === 'READY' ? 'success' : 'muted'">
                {{ skill.compat === 'READY' ? '可用' : '缺能力' }}
              </span>
            </div>
            <p class="sk-summary">{{ skill.summary }}</p>
            <dl class="sk-io">
              <div><dt>输入</dt><dd>{{ inputLabel(skill.inputTypes) }}</dd></div>
              <div><dt>输出</dt><dd>{{ outputLabel(skill) }}</dd></div>
            </dl>
            <p v-if="skill.compat !== 'READY'" class="sk-missing">
              {{ (skill.missing || []).join('；') || '暂不可用' }}
            </p>
            <div class="sk-card-foot">
              <span class="sk-version">v{{ skill.version }} · {{ categoryLabel(skill.category) }}</span>
              <div class="sk-actions">
                <button type="button" class="cr-ghost" @click="openDetail(skill.slug)">详情</button>
                <button type="button" class="cr-primary" :disabled="skill.compat !== 'READY'"
                        :title="skill.compat !== 'READY' ? (skill.missing || []).join('；') : '在创作台使用'"
                        @click="useSkill(skill)">
                  使用
                </button>
              </div>
            </div>
          </article>
        </div>
      </div>
    </div>

    <SkillDetailDrawer v-if="detailSlug" :slug="detailSlug" @close="closeDetail" @use="useSkillFromDetail" />
    <p v-if="toast.text" class="cr-toast" :class="toast.tone">{{ toast.text }}</p>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { listCreativeSkills } from '../../../api'
import DropdownSelect from '../../../components/ui/DropdownSelect.vue'
import SkillDetailDrawer from '../../../components/creative/SkillDetailDrawer.vue'

const CATEGORY_LABEL = {
  DIRECTING: '导演与分镜',
  SHORT_FORM: '短片',
  COMMERCE: '电商',
  GENERAL: '通用'
}
const STAGE_LABEL = {
  PROMPT: '提示词',
  STORYBOARD: '分镜',
  GENERATE: '生成'
}

const router = useRouter()
const skills = ref([])
const loading = ref(true)
const error = ref('')
const keyword = ref('')
const category = ref('')
const stage = ref('')
const onlyReady = ref(false)
const detailSlug = ref('')
const toast = reactive({ text: '', tone: '' })

const categoryOptions = computed(() => [
  { value: '', label: '全部分类' },
  ...Object.entries(CATEGORY_LABEL).map(([value, label]) => ({ value, label }))
])
const stageOptions = computed(() => [
  { value: '', label: '全部阶段' },
  ...Object.entries(STAGE_LABEL).map(([value, label]) => ({ value, label }))
])

const filtered = computed(() => {
  const lowered = keyword.value.toLowerCase()
  return skills.value.filter(skill => {
    if (category.value && skill.category !== category.value) return false
    if (stage.value && skill.stage !== stage.value) return false
    if (onlyReady.value && skill.compat !== 'READY') return false
    if (lowered) {
      const haystack = `${skill.name} ${skill.summary} ${skill.slug}`.toLowerCase()
      if (!haystack.includes(lowered)) return false
    }
    return true
  })
})

function categoryLabel(value) {
  return CATEGORY_LABEL[value] || '通用'
}

function inputLabel(value) {
  const map = { TEXT: '文本', 'TEXT+IMAGE': '文本 + 图片' }
  return map[value] || value || '文本'
}

function outputLabel(skill) {
  if (skill.outputTypes === 'VIDEO') return '短视频'
  const caps = skill.requiredCapabilities || []
  if (caps.includes('IMAGE_TO_VIDEO') || caps.includes('TEXT_TO_VIDEO')) return '方案 + 短视频'
  return '方案 / 提示词'
}

function showToast(text, tone = '') {
  toast.text = text
  toast.tone = tone
  window.clearTimeout(showToast.timer)
  showToast.timer = window.setTimeout(() => { toast.text = '' }, 3200)
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    const data = await listCreativeSkills()
    skills.value = data.items || []
  } catch (exception) {
    error.value = exception.response?.data?.error || '技能目录加载失败，请确认后端服务可用'
  } finally {
    loading.value = false
  }
}

function clearFilters() {
  keyword.value = ''
  category.value = ''
  stage.value = ''
  onlyReady.value = false
}

function openDetail(slug) {
  detailSlug.value = slug
}

function closeDetail() {
  detailSlug.value = ''
}

/**
 * 「使用」入口（skill 设计 §4 建议导航）：技能在项目创作台运行；
 * 这里把选择交给首页输入台接管——用户在输入台补齐想法并进入创作台。
 * 通过 sessionStorage 交接，不把提示词放 URL（redesign §8 状态分层）。
 */
function useSkill(skill) {
  if (skill.compat !== 'READY') return
  try { sessionStorage.setItem('aster.creative.handoff', JSON.stringify({ skill: skill.slug })) } catch (e) { /* 忽略 */ }
  router.push('/creative')
}

function useSkillFromDetail(skill) {
  closeDetail()
  useSkill(skill)
}

onMounted(load)
</script>

<style scoped src="../creative.css"></style>


<style scoped>
.sk-filters {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.sk-search { width: 240px; }
.sk-cat { width: 130px; }
.sk-stage { width: 120px; }

.sk-available {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  font-size: 12.5px;
  color: var(--wb-text-2);
  cursor: pointer;
  user-select: none;
}

.sk-retry {
  border: 0;
  background: transparent;
  padding: 0;
  color: var(--wb-brand);
  font-size: 12.5px;
  cursor: pointer;
}

.sk-empty { align-items: flex-start; gap: 6px; }
.sk-empty p { margin: 0; }

.sk-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(300px, 1fr));
  gap: 14px;
}

.sk-card {
  display: flex;
  flex-direction: column;
  gap: 8px;
  padding: 15px 16px;
  border: 1px solid var(--wb-border);
  border-radius: var(--cr-radius-media, 14px);
  background: var(--wb-surface);
  transition: border-color var(--cr-dur-fast, 140ms) var(--cr-ease, ease);
}

.sk-card:hover { border-color: var(--wb-border-strong); }
.sk-card.is-missing { opacity: 0.82; }

.sk-card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}

.sk-card-head h3 {
  margin: 0;
  font-size: 15px;
  font-weight: 620;
  color: var(--cr-text, var(--wb-text));
}

.sk-summary {
  margin: 0;
  font-size: 13px;
  line-height: 1.6;
  color: var(--wb-text-2);
  display: -webkit-box;
  -webkit-line-clamp: 3;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.sk-io {
  display: flex;
  gap: 16px;
  margin: 0;
  font-size: 12px;
}

.sk-io div { display: flex; gap: 6px; align-items: baseline; }
.sk-io dt { color: var(--wb-muted); }
.sk-io dd { margin: 0; color: var(--wb-text-2); }

.sk-missing {
  margin: 0;
  font-size: 12px;
  color: var(--wb-warning);
}

.sk-card-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-top: auto;
  padding-top: 4px;
}

.sk-version { font-size: 11.5px; color: var(--wb-muted); }

.sk-actions { display: flex; gap: 8px; }

@media (max-width: 720px) {
  .sk-search { flex: 1; width: auto; }
  .sk-grid { grid-template-columns: 1fr; }
}
</style>
