<template>
  <div class="tab-scroll">
    <div class="tab-inner novel-pane">
      <div class="cr-section-head">
        <h2>章节</h2>
        <div class="novel-head-actions">
          <button type="button" class="cr-ghost" :disabled="loading" @click="loadChapters">刷新</button>
          <button type="button" class="cr-secondary" @click="toggleSettings">作品设定</button>
          <button type="button" class="cr-primary" :disabled="project?.status !== 'ACTIVE'"
                  @click="openCreate">写新章节</button>
        </div>
      </div>

      <section v-if="settingsOpen" class="novel-settings">
        <div class="novel-settings-head">
          <strong>作品设定</strong>
          <span class="cr-field-help">人物 / 世界观 / 文风会注入每一章的生成上下文</span>
        </div>
        <label class="cr-field">
          <span class="cr-field-label"><span>人物设定</span></span>
          <textarea v-model="settings.characters" class="cr-textarea" rows="3"
                    placeholder="主角与主要角色的身份、性格、目标、关系" />
        </label>
        <label class="cr-field">
          <span class="cr-field-label"><span>世界观</span></span>
          <textarea v-model="settings.worldview" class="cr-textarea" rows="3"
                    placeholder="时代背景、力量体系、重要地点与规则" />
        </label>
        <label class="cr-field">
          <span class="cr-field-label"><span>文风</span></span>
          <textarea v-model="settings.style" class="cr-textarea" rows="2"
                    placeholder="如：第三人称，紧凑节奏，画面感强，少用对话" />
        </label>
        <div class="novel-settings-actions">
          <button type="button" class="cr-primary" :disabled="settingsSaving" @click="saveSettings">
            {{ settingsSaving ? '保存中…' : '保存设定' }}
          </button>
        </div>
      </section>

      <p v-if="loading" class="cr-hint">加载中…</p>
      <p v-else-if="error" class="cr-error">{{ error }}</p>
      <p v-else-if="!chapters.length" class="cr-empty">
        还没有章节。点击「写新章节」：填一句剧情走向（或让 AI 建议），工作流会读取作品设定与既往章节生成正文。
      </p>

      <article v-for="chapter in chapters" :key="chapter.id" class="novel-chapter-card">
        <header class="novel-chapter-head" @click="toggleChapter(chapter)">
          <span class="novel-ch-no">第{{ chapter.chapterNumber }}章</span>
          <strong class="novel-ch-title">{{ chapter.title }}</strong>
          <span class="cr-status" :class="chapter.status === 'CONFIRMED' ? 'success' : 'muted'">
            {{ chapter.status === 'CONFIRMED' ? '已确认' : '草稿' }}
          </span>
          <span class="novel-ch-meta">{{ chapter.wordCount }} 字 · {{ formatTime(chapter.updatedAt) }}</span>
          <button type="button" class="cr-ghost" @click.stop="toggleChapter(chapter)">
            {{ expandedId === chapter.id ? '收起' : '展开' }}
          </button>
        </header>
        <p v-if="chapter.plotDirection" class="novel-ch-direction">剧情走向：{{ chapter.plotDirection }}</p>
        <div v-if="expandedId === chapter.id" class="novel-ch-editor">
          <label class="cr-field">
            <span class="cr-field-label"><span>章节标题</span></span>
            <input v-model="editing.title" class="cr-input" maxlength="200" />
          </label>
          <label class="cr-field">
            <span class="cr-field-label"><span>正文</span><span class="count">{{ editing.content.length }} 字</span></span>
            <textarea v-model="editing.content" class="cr-textarea novel-content" rows="16" />
          </label>
          <label class="cr-field">
            <span class="cr-field-label"><span>剧情概要</span></span>
            <textarea v-model="editing.summary" class="cr-textarea" rows="3"
                      placeholder="确认后这段概要会进入后续章节的生成上下文" />
          </label>
          <div class="novel-ch-actions">
            <button type="button" class="cr-secondary" :disabled="busy" @click="saveChapter(chapter)">
              <Spinner v-if="busy" /> 保存修改
            </button>
            <HoldToConfirm v-if="chapter.status !== 'CONFIRMED'" tone="primary" label="按住确认入库"
                           aria-label="确认入库（概要进入后续章节上下文，且不可再删除）"
                           :disabled="busy || !editing.content.trim()" @confirm="confirmChapter(chapter)" />
            <button v-if="chapter.status !== 'CONFIRMED'" type="button" class="cr-ghost danger"
                    :disabled="busy" @click="removeChapter(chapter)">删除草稿</button>
          </div>
        </div>
      </article>
    </div>

    <!-- 写新章节：剧情走向可手输，也可让 AI 基于设定与近几章概要建议 -->
    <div v-if="createOpen" class="cr-dialog-backdrop" @click.self="createOpen = false">
      <div class="cr-dialog" role="dialog" aria-modal="true" aria-labelledby="novel-create-title">
        <h2 id="novel-create-title">写新章节 · {{ project?.name }}</h2>
        <label class="cr-field">
          <span class="cr-field-label"><span>章节标题（可留空，自动按序号命名）</span></span>
          <input v-model.trim="create.title" class="cr-input" maxlength="200" placeholder="如：夜探藏经阁" />
        </label>
        <label class="cr-field">
          <span class="cr-field-label"><span>本章剧情走向</span></span>
          <textarea v-model="create.plotDirection" class="cr-textarea" rows="4" maxlength="4000"
                    placeholder="用一两句话描述本章要发生什么：冲突、人物行动、结尾钩子" />
        </label>
        <div class="novel-suggest-row">
          <button type="button" class="cr-secondary" :disabled="suggesting" @click="suggestPlot">
            <Spinner v-if="suggesting" />
            <Sparkles v-else :size="14" /> {{ suggesting ? '思考中…' : 'AI 建议剧情走向' }}
          </button>
          <span class="cr-field-help">基于作品设定、章节目录与近几章概要生成 3 组候选</span>
        </div>
        <div v-if="suggestions.length" class="novel-suggestion-list">
          <button v-for="(item, index) in suggestions" :key="index" type="button"
                  class="novel-suggestion-card" @click="adoptSuggestion(item)">
            <strong>{{ item.title }}</strong>
            <p>{{ item.direction }}</p>
            <span class="novel-suggestion-adopt">点击采纳</span>
          </button>
        </div>
        <label class="cr-field">
          <span class="cr-field-label"><span>工作流模板</span></span>
          <DropdownSelect v-model="create.workflowKey" block :options="workflowOptions"
                          placeholder="选择工作流模板" aria-label="工作流模板" />
          <span class="cr-field-help">运行会读取作品设定与既往章节，生成正文与概要后存为草稿</span>
        </label>
        <p v-if="create.error" class="cr-error">{{ create.error }}</p>
        <div class="cr-dialog-actions">
          <button type="button" class="cr-secondary" @click="createOpen = false">取消</button>
          <button type="button" class="cr-primary"
                  :disabled="create.starting || !create.plotDirection.trim() || !create.workflowKey"
                  @click="startChapter">
            <Spinner v-if="create.starting" /> {{ create.starting ? '发起中…' : '发起生成' }}
          </button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, inject, onMounted, reactive, ref } from 'vue'
import { Sparkles } from 'lucide-vue-next'
import {
  confirmNovelChapter, deleteNovelChapter, getNovelSettings, listNovelChapters, startWorkflow,
  suggestNovelPlot, updateNovelChapter, updateNovelSettings
} from '../../../api'
import { formatTime } from '../shared'
import { PROJECT_CTX, useWorkflowList } from './composables'
import DropdownSelect from '../../../components/ui/DropdownSelect.vue'
import HoldToConfirm from '../../../components/ui/HoldToConfirm.vue'
import Spinner from '../../../components/ui/Spinner.vue'

const { projectId, project, showToast, router } = inject(PROJECT_CTX)
const { workflows, ensureLoaded } = useWorkflowList()

const chapters = ref([])
const loading = ref(false)
const error = ref('')
const expandedId = ref('')
const busy = ref(false)
const settingsOpen = ref(false)
const settingsSaving = ref(false)
const settings = reactive({ characters: '', worldview: '', style: '' })
const editing = reactive({ title: '', content: '', summary: '' })

const createOpen = ref(false)
const suggesting = ref(false)
const suggestions = ref([])
const create = reactive({ starting: false, error: '', workflowKey: 'novel-chapter', title: '', plotDirection: '' })

const workflowOptions = computed(() => workflows.value.map(wf => ({
  value: wf.key, label: wf.name || wf.key
})))

async function loadChapters() {
  loading.value = true
  error.value = ''
  try {
    chapters.value = await listNovelChapters(projectId.value)
  } catch (exception) {
    error.value = exception.response?.data?.error || '章节加载失败'
  } finally {
    loading.value = false
  }
}

async function loadSettings() {
  try {
    const data = await getNovelSettings(projectId.value)
    settings.characters = data.characters || ''
    settings.worldview = data.worldview || ''
    settings.style = data.style || ''
  } catch (exception) { /* 设定加载失败不阻塞章节 */ }
}

function toggleSettings() {
  settingsOpen.value = !settingsOpen.value
  if (settingsOpen.value) loadSettings()
}

async function saveSettings() {
  settingsSaving.value = true
  try {
    await updateNovelSettings(projectId.value, { ...settings })
    showToast('作品设定已保存，后续章节生成将注入', 'success')
  } catch (exception) {
    showToast(exception.response?.data?.error || '设定保存失败', 'error')
  } finally {
    settingsSaving.value = false
  }
}

function toggleChapter(chapter) {
  if (expandedId.value === chapter.id) {
    expandedId.value = ''
    return
  }
  expandedId.value = chapter.id
  editing.title = chapter.title
  editing.content = chapter.content || ''
  editing.summary = chapter.summary || ''
}

async function saveChapter(chapter) {
  busy.value = true
  try {
    const updated = await updateNovelChapter(projectId.value, chapter.id, {
      title: editing.title, content: editing.content, summary: editing.summary
    })
    Object.assign(chapter, updated)
    showToast('章节已保存', 'success')
  } catch (exception) {
    showToast(exception.response?.data?.error || '章节保存失败', 'error')
  } finally {
    busy.value = false
  }
}

async function confirmChapter(chapter) {
  busy.value = true
  try {
    const updated = await confirmNovelChapter(projectId.value, chapter.id)
    Object.assign(chapter, updated)
    showToast(`第${chapter.chapterNumber}章已确认入库`, 'success')
  } catch (exception) {
    showToast(exception.response?.data?.error || '确认失败', 'error')
  } finally {
    busy.value = false
  }
}

async function removeChapter(chapter) {
  if (!window.confirm(`删除第${chapter.chapterNumber}章草稿「${chapter.title}」？`)) return
  busy.value = true
  try {
    await deleteNovelChapter(projectId.value, chapter.id)
    if (expandedId.value === chapter.id) expandedId.value = ''
    await loadChapters()
    showToast('草稿已删除', 'success')
  } catch (exception) {
    showToast(exception.response?.data?.error || '删除失败', 'error')
  } finally {
    busy.value = false
  }
}

async function openCreate() {
  createOpen.value = true
  create.error = ''
  create.title = ''
  create.plotDirection = ''
  create.workflowKey = 'novel-chapter'
  suggestions.value = []
  await ensureLoaded()
  if (!workflows.value.some(wf => wf.key === 'novel-chapter')) {
    create.workflowKey = workflows.value[0]?.key || ''
  }
}

async function suggestPlot() {
  suggesting.value = true
  suggestions.value = []
  try {
    const data = await suggestNovelPlot(projectId.value, create.plotDirection.trim())
    suggestions.value = data.items || []
    if (!suggestions.value.length) create.error = 'AI 未返回有效建议，请重试'
  } catch (exception) {
    create.error = exception.response?.data?.error || '剧情建议生成失败，请稍后重试'
  } finally {
    suggesting.value = false
  }
}

function adoptSuggestion(item) {
  create.title = item.title || ''
  create.plotDirection = item.direction || ''
}

async function startChapter() {
  if (create.starting || !create.plotDirection.trim() || !create.workflowKey) return
  create.starting = true
  create.error = ''
  try {
    const input = { plotDirection: create.plotDirection.trim() }
    if (create.title) input.title = create.title
    const run = await startWorkflow(create.workflowKey, '', projectId.value, input)
    createOpen.value = false
    showToast('章节生成已发起，运行完成后回到「章节」查看草稿', 'success')
    router.push(`/creative/projects/${projectId.value}/workflow?runId=${run.id}`)
  } catch (exception) {
    create.error = exception.response?.data?.error || '发起失败，请稍后重试'
  } finally {
    create.starting = false
  }
}

onMounted(loadChapters)
</script>

<style scoped src="../creative.css"></style>

<style scoped>
.novel-pane { max-width: 960px; }
.novel-head-actions { display: flex; gap: 8px; align-items: center; }
.novel-settings {
  border: 1px solid var(--wb-border);
  border-radius: var(--wb-radius);
  padding: 14px 16px;
  display: grid;
  gap: 10px;
  background: var(--wb-surface-2);
}
.novel-settings-head { display: flex; align-items: baseline; gap: 10px; }
.novel-settings-actions { display: flex; justify-content: flex-end; }
.novel-chapter-card {
  border: 1px solid var(--wb-border);
  border-radius: var(--wb-radius);
  padding: 12px 16px;
  background: var(--wb-surface);
}
.novel-chapter-head { display: flex; align-items: center; gap: 10px; cursor: pointer; flex-wrap: wrap; }
.novel-ch-no { color: var(--wb-brand); font-weight: 600; font-size: var(--wb-fs-compact); white-space: nowrap; }
.novel-ch-title { font-size: var(--wb-fs-panel); }
.novel-ch-meta { color: var(--wb-muted); font-size: var(--wb-fs-caption); margin-left: auto; }
.novel-ch-direction {
  color: var(--wb-muted);
  font-size: var(--wb-fs-caption);
  margin: 8px 0 0;
  overflow: hidden;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
}
.novel-ch-editor { margin-top: 12px; display: grid; gap: 10px; }
.novel-content { line-height: 1.8; }
.novel-ch-actions { display: flex; gap: 8px; }
.novel-suggest-row { display: flex; align-items: center; gap: 10px; margin-bottom: 10px; flex-wrap: wrap; }
.novel-suggestion-list { display: grid; gap: 8px; margin-bottom: 10px; max-height: 38vh; overflow-y: auto; padding-right: 2px; }
.novel-suggestion-card {
  text-align: left;
  border: 1px solid var(--wb-border);
  border-radius: var(--wb-radius);
  padding: 10px 12px;
  background: transparent;
  cursor: pointer;
  color: inherit;
  display: grid;
  gap: 4px;
}
.novel-suggestion-card:hover { border-color: var(--wb-brand); }
.novel-suggestion-card p { margin: 0; font-size: var(--wb-fs-caption); color: var(--wb-muted); line-height: 1.6; }
.novel-suggestion-adopt { font-size: var(--wb-fs-caption); color: var(--wb-brand); }
@media (max-width: 720px) {
  .novel-ch-meta { margin-left: 0; }
}
</style>
