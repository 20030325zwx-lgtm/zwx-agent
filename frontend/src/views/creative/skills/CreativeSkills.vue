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
          <button type="button" class="cr-secondary" @click="importOpen = true">
            <Upload :size="15" /> 导入技能
          </button>
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
              <span class="sk-mine" v-if="skill.mine">私有</span>
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

    <!-- 导入技能（P0-C MVP）：粘贴 Markdown → 解析预检 → 保存私有草稿，不进入系统目录 -->
    <div v-if="importOpen" class="cr-dialog-backdrop" @click.self="importOpen = false">
      <div class="cr-dialog sk-import" role="dialog" aria-modal="true" aria-labelledby="sk-import-title">
        <h2 id="sk-import-title">导入技能（Markdown）</h2>
        <p class="cr-field-help">front-matter 需要至少 name 与 description；导入默认私有，外部内容授权需自行核实。</p>
        <template v-if="!importPreview">
          <label class="cr-field">
            <span class="cr-field-label"><span>技能 Markdown</span></span>
            <textarea v-model="importMarkdown" class="cr-textarea" rows="10"
                      placeholder="---&#10;name: 我的技能&#10;description: 一句话说明&#10;---&#10;正文…"
                      aria-label="技能 Markdown 内容" />
          </label>
          <p v-if="importError" class="cr-error">{{ importError }}</p>
          <div class="cr-dialog-actions">
            <button type="button" class="cr-secondary" @click="importOpen = false">取消</button>
            <button type="button" class="cr-primary" :disabled="importBusy || !importMarkdown.trim()"
                    @click="parseImport">
              <Spinner v-if="importBusy" /> 解析预览
            </button>
          </div>
        </template>
        <template v-else>
          <dl class="sk-import-meta">
            <div><dt>名称</dt><dd>{{ importPreview.name || '（缺失）' }}</dd></div>
            <div><dt>说明</dt><dd>{{ importPreview.description || '（缺失）' }}</dd></div>
            <div><dt>正文</dt><dd>{{ importPreview.bodyLength }} 字</dd></div>
          </dl>
          <ul v-if="importPreview.issues?.length" class="sk-import-issues">
            <li v-for="issue in importPreview.issues" :key="issue">{{ issue }}</li>
          </ul>
          <p v-else class="sk-import-ok">解析通过；保存后默认私有，授权状态为「待核实」。</p>
          <label class="cr-field">
            <span class="cr-field-label"><span>技能标识（可选，默认按名称生成）</span></span>
            <input v-model.trim="importSlug" class="cr-input" maxlength="60" placeholder="如 my-storyboard" />
          </label>
          <div class="cr-dialog-actions">
            <button type="button" class="cr-ghost" @click="importPreview = null">上一步</button>
            <button type="button" class="cr-primary" :disabled="importBusy
                    || importPreview.compat === 'PARSE_ISSUES'" @click="saveImport">
              <Spinner v-if="importBusy" /> 保存为私有技能
            </button>
          </div>
        </template>
      </div>
    </div>
    <p v-if="toast.text" class="cr-toast" :class="toast.tone">{{ toast.text }}</p>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { Upload } from 'lucide-vue-next'
import {
  confirmCreativeSkillImport, listCreativeSkills, previewCreativeSkillImport
} from '../../../api'
import DropdownSelect from '../../../components/ui/DropdownSelect.vue'
import Spinner from '../../../components/ui/Spinner.vue'
import SkillDetailDrawer from '../../../components/creative/SkillDetailDrawer.vue'
import { SKILL_CATEGORY_LABEL as CATEGORY_LABEL, skillCategoryLabel as categoryLabel } from '../../../components/creative/skillCategories'

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

// ---------------------------------------------------------------- 导入（P0-C MVP）
const importOpen = ref(false)
const importBusy = ref(false)
const importError = ref('')
const importMarkdown = ref('')
const importPreview = ref(null)
const importSlug = ref('')

async function parseImport() {
  importBusy.value = true
  importError.value = ''
  try {
    importPreview.value = await previewCreativeSkillImport({
      markdown: importMarkdown.value, sourceRef: ''
    })
  } catch (exception) {
    importError.value = exception.response?.data?.error || '解析失败，请检查 front-matter'
  } finally {
    importBusy.value = false
  }
}

async function saveImport() {
  if (!importPreview.value || importBusy.value) return
  importBusy.value = true
  try {
    const skill = await confirmCreativeSkillImport(importPreview.value.importId, {
      slug: importSlug.value || null
    })
    importOpen.value = false
    importPreview.value = null
    importMarkdown.value = ''
    importSlug.value = ''
    showToast(`已保存私有技能「${skill.name}」`, 'success')
    await load()
  } catch (exception) {
    importError.value = exception.response?.data?.error || '保存失败'
    showToast(importError.value, 'error')
  } finally {
    importBusy.value = false
  }
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
  padding: 16px;
  border: 1px solid var(--cr-border, var(--wb-border));
  border-radius: var(--cr-radius-media, 14px);
  background: var(--cr-surface, var(--wb-surface));
  box-shadow: 0 1px 2px rgba(20, 18, 24, 0.03);
  transition: border-color var(--cr-dur-fast, 140ms) var(--cr-ease, ease);
}

.sk-card:hover { border-color: color-mix(in srgb, var(--cr-text-2, #62646A) 26%, var(--cr-border, #E4E3DF)); }
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

.sk-mine {
  flex: 0 0 auto;
  font-style: normal;
  font-size: 10.5px;
  padding: 1px 6px;
  border-radius: 999px;
  background: var(--wb-fill);
  color: var(--wb-text-2);
}

.sk-import { width: min(560px, 94vw); }
.sk-import-meta { display: flex; flex-direction: column; gap: 5px; margin: 0; font-size: 12.5px; }
.sk-import-meta div { display: flex; gap: 8px; }
.sk-import-meta dt { flex: 0 0 44px; color: var(--wb-muted); }
.sk-import-meta dd { margin: 0; color: var(--wb-text-2); min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.sk-import-issues { margin: 0; padding-left: 18px; font-size: 12.5px; color: var(--wb-warning); }
.sk-import-ok { margin: 0; font-size: 12.5px; color: var(--wb-success); }

@media (max-width: 720px) {
  .sk-search { flex: 1; width: auto; }
  .sk-grid { grid-template-columns: 1fr; }
}
</style>
