<template>
  <div v-if="open" class="code-overlay" @click.self="emit('close')">
    <section class="code-drawer" role="dialog" aria-modal="true" aria-label="项目代码">
      <header class="code-head">
        <div class="head-main">
          <span class="head-icon"><Code2 :size="16" /></span>
          <div class="head-copy">
            <strong>{{ projectName || '项目代码' }}</strong>
            <span class="head-sub">{{ modeLabel }}<em v-if="branch"> · {{ branch }}</em></span>
          </div>
        </div>
        <div class="head-tabs" role="tablist">
          <button type="button" role="tab" :aria-selected="tab === 'files'" :class="{ active: tab === 'files' }" @click="switchTab('files')">文件</button>
          <button type="button" role="tab" :aria-selected="tab === 'diff'" :class="{ active: tab === 'diff' }" @click="switchTab('diff')">
            改动<em v-if="diffFiles.length" class="tab-count">{{ diffFiles.length }}</em>
          </button>
        </div>
        <div class="head-actions">
          <button type="button" class="icon-button" title="刷新" aria-label="刷新" @click="refreshAll">
            <RotateCw :size="15" :class="{ spin: refreshing }" />
          </button>
          <button type="button" class="icon-button" title="关闭" aria-label="关闭" @click="emit('close')">
            <X :size="16" />
          </button>
        </div>
      </header>

      <div class="code-body">
        <aside class="tree-pane" aria-label="文件列表">
          <template v-if="tab === 'files'">
            <div class="tree-head">
              <span>文件</span>
              <span v-if="loadingDirs.size" class="tree-state">读取中…</span>
            </div>
            <div class="tree-scroll">
              <p v-if="treeError" class="tree-message error">{{ treeError }}</p>
              <button v-for="row in treeRows" :key="row.key" type="button" class="tree-row"
                :class="{ active: row.path === activePath, dim: row.untracked }"
                :style="{ paddingLeft: `${10 + row.depth * 12}px` }"
                :disabled="row.loading"
                @click="onTreeRow(row)">
                <ChevronRight v-if="row.type === 'directory'" :size="12" class="tree-chevron" :class="{ open: expanded.has(row.path) }" />
                <Folder v-if="row.type === 'directory'" :size="14" />
                <FileCode2 v-else :size="14" />
                <span class="tree-name">{{ row.name }}</span>
              </button>
              <p v-if="!treeRows.length && !treeError && !treeLoading" class="tree-message">目录为空</p>
            </div>
          </template>
          <template v-else>
            <div class="tree-head">
              <span>本次改动</span>
              <span v-if="diffFiles.length" class="tree-state">{{ diffSummary }}</span>
            </div>
            <div class="tree-scroll">
              <p v-if="diffError" class="tree-message error">{{ diffError }}</p>
              <button v-for="file in diffFiles" :key="file.path" type="button" class="diff-row"
                :class="{ active: file.path === activeDiffPath }" :title="file.path"
                @click="activeDiffPath = file.path">
                <span class="diff-status" :class="`status-${file.status}`">{{ statusLabel(file.status) }}</span>
                <span class="diff-name">{{ file.path }}</span>
                <span class="diff-stats"><em>+{{ file.additions }}</em><i>-{{ file.deletions }}</i></span>
              </button>
              <p v-if="!diffFiles.length && !diffLoading && !diffError" class="tree-message">没有未提交的改动</p>
              <p v-if="mode === 'RUN'" class="tree-message hint">显示本次运行 checkout 相对 base 的差异</p>
            </div>
          </template>
        </aside>

        <main class="editor-pane">
          <template v-if="tab === 'files'">
            <div v-if="!activePath" class="pane-empty">
              <FileCode2 :size="26" />
              <strong>选择左侧文件查看</strong>
              <p>可以直接编辑并保存；智能体的改动会出现在「改动」页。</p>
            </div>
            <template v-else>
              <div class="file-bar">
                <span class="file-path" :title="activePath">{{ activePath }}</span>
                <span class="file-meta">{{ sizeLabel }}</span>
                <span v-if="fileMeta?.modifiedAt" class="file-meta">{{ new Date(fileMeta.modifiedAt).toLocaleTimeString('zh-CN', { hour12: false }) }}</span>
                <template v-if="!editing">
                  <button type="button" class="bar-button" :disabled="loadingFile" @click="startEdit">
                    <Pencil :size="13" /> 编辑
                  </button>
                </template>
                <template v-else>
                  <span v-if="dirty" class="dirty-dot">未保存</span>
                  <button type="button" class="bar-button" :disabled="saving" @click="cancelEdit">取消</button>
                  <button type="button" class="bar-button primary" :disabled="saving || !dirty" @click="saveFile">
                    <Loader2 v-if="saving" :size="13" class="spin" />
                    <Save v-else :size="13" />
                    保存
                  </button>
                </template>
              </div>
              <p v-if="fileError" class="pane-error">{{ fileError }}</p>
              <div v-else-if="loadingFile" class="pane-empty">正在读取文件…</div>
              <textarea v-else-if="editing" v-model="draft" class="code-input" spellcheck="false" wrap="off"
                aria-label="编辑文件内容"></textarea>
              <div v-else class="code-scroll">
                <div class="code-gutter" aria-hidden="true">
                  <span v-for="line in lineCount" :key="line">{{ line }}</span>
                </div>
                <pre class="code-view"><code class="hljs" v-html="highlighted"></code></pre>
              </div>
            </template>
          </template>
          <template v-else>
            <div v-if="diffLoading" class="pane-empty">正在读取改动…</div>
            <p v-else-if="diffError" class="pane-error">{{ diffError }}</p>
            <div v-else-if="!activeDiffPatch" class="pane-empty">
              <template v-if="selectedDiffFile?.status === 'untracked'">
                <FileCode2 :size="26" />
                <strong>未跟踪文件，暂无 diff</strong>
                <p>在「文件」页打开查看内容；纳入版本管理后就会显示差异。</p>
              </template>
              <template v-else>
                <GitCompare :size="26" />
                <strong>{{ diffFiles.length ? '选择左侧改动文件' : '当前没有待查看的改动' }}</strong>
                <p>{{ diffFiles.length ? '显示 unified diff；空白行不计入统计。' : '智能体或你在项目里修改文件后，这里会显示差异。' }}</p>
              </template>
            </div>
            <div v-else class="diff-scroll">
              <div class="diff-file-head">
                <strong>{{ activeDiffPath }}</strong>
                <span>{{ diffFiles.find(file => file.path === activeDiffPath)?.additions }} 行新增 ·
                  {{ diffFiles.find(file => file.path === activeDiffPath)?.deletions }} 行删除</span>
              </div>
              <pre class="diff-view"><code><span v-for="(line, index) in activeDiffLines" :key="index"
                :class="['diff-line', diffLineClass(line)]">{{ line || ' ' }}</span></code></pre>
            </div>
          </template>
        </main>
      </div>
    </section>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import {
  ChevronRight, Code2, FileCode2, Folder, GitCompare, Loader2, Pencil, RotateCw, Save, X
} from 'lucide-vue-next'
import hljs from 'highlight.js/lib/core'
import java from 'highlight.js/lib/languages/java'
import javascript from 'highlight.js/lib/languages/javascript'
import typescript from 'highlight.js/lib/languages/typescript'
import xml from 'highlight.js/lib/languages/xml'
import json from 'highlight.js/lib/languages/json'
import css from 'highlight.js/lib/languages/css'
import sql from 'highlight.js/lib/languages/sql'
import bash from 'highlight.js/lib/languages/bash'
import python from 'highlight.js/lib/languages/python'
import yaml from 'highlight.js/lib/languages/yaml'
import markdown from 'highlight.js/lib/languages/markdown'
import {
  getProjectCodeDiff, getProjectCodeFile, getProjectCodeTree, saveProjectCodeFile
} from '../../api'

hljs.registerLanguage('java', java)
hljs.registerLanguage('javascript', javascript)
hljs.registerLanguage('typescript', typescript)
hljs.registerLanguage('xml', xml)
hljs.registerLanguage('json', json)
hljs.registerLanguage('css', css)
hljs.registerLanguage('sql', sql)
hljs.registerLanguage('bash', bash)
hljs.registerLanguage('python', python)
hljs.registerLanguage('yaml', yaml)
hljs.registerLanguage('markdown', markdown)

const props = defineProps({
  open: { type: Boolean, default: false },
  conversationId: { type: String, default: '' },
  runId: { type: String, default: '' },
  projectName: { type: String, default: '' }
})
const emit = defineEmits(['close'])

const tab = ref('files')
const entriesByDir = ref({})
const expanded = ref(new Set())
const loadingDirs = ref(new Set())
const treeLoading = ref(false)
const treeError = ref('')
const activePath = ref('')
const fileContent = ref('')
const fileMeta = ref(null)
const loadingFile = ref(false)
const fileError = ref('')
const editing = ref(false)
const draft = ref('')
const saving = ref(false)
const refreshing = ref(false)
const diffFiles = ref([])
const diffPatch = ref('')
const diffMode = ref('')
const diffBranch = ref('')
const diffLoading = ref(false)
const diffError = ref('')
const activeDiffPath = ref('')

const mode = computed(() => diffMode.value || (props.runId ? 'RUN' : 'PROJECT'))
const branch = computed(() => diffBranch.value)
const modeLabel = computed(() => (mode.value === 'RUN' ? '运行工作区' : '项目目录'))
const dirty = computed(() => editing.value && draft.value !== fileContent.value)
const sizeLabel = computed(() => {
  const size = fileMeta.value?.size
  if (size == null) return ''
  return size < 1024 ? `${size} B` : `${(size / 1024).toFixed(1)} KB`
})
const diffSummary = computed(() => {
  const add = diffFiles.value.reduce((sum, file) => sum + file.additions, 0)
  const del = diffFiles.value.reduce((sum, file) => sum + file.deletions, 0)
  return `+${add} / -${del}`
})

const treeRows = computed(() => {
  const rows = []
  const walk = (dir, depth) => {
    const entries = entriesByDir.value[dir]
    if (!entries) {
      if (depth > 0) rows.push({ key: `${dir}:loading`, name: '读取中…', depth, type: 'loading', loading: true })
      return
    }
    for (const entry of entries) {
      rows.push({ ...entry, depth, key: entry.path })
      if (entry.type === 'directory' && expanded.value.has(entry.path)) walk(entry.path, depth + 1)
    }
  }
  walk('', 0)
  return rows
})

const LANGUAGE_BY_EXT = {
  java: 'java', js: 'javascript', mjs: 'javascript', cjs: 'javascript', jsx: 'javascript',
  ts: 'typescript', tsx: 'typescript', vue: 'xml', html: 'xml', htm: 'xml', xml: 'xml', svg: 'xml',
  json: 'json', css: 'css', scss: 'css', less: 'css', sql: 'sql',
  sh: 'bash', bash: 'bash', zsh: 'bash', py: 'python', yml: 'yaml', yaml: 'yaml', md: 'markdown'
}
const languageFor = path => {
  const ext = (path || '').split('.').pop()?.toLowerCase() || ''
  return LANGUAGE_BY_EXT[ext] || ''
}
const highlighted = computed(() => {
  if (!fileContent.value) return ''
  const language = languageFor(activePath.value)
  if (!language) return escapeHtml(fileContent.value)
  try {
    return hljs.highlight(fileContent.value, { language, ignoreIllegals: true }).value
  } catch {
    return escapeHtml(fileContent.value)
  }
})
const lineCount = computed(() => (fileContent.value ? fileContent.value.split('\n').length : 0))

const selectedDiffFile = computed(() => diffFiles.value.find(file => file.path === activeDiffPath.value) || null)
const activeDiffPatch = computed(() => {
  if (!activeDiffPath.value) return ''
  const chunks = diffPatch.value.split(/^diff --git /m)
  const match = chunks.find(chunk => chunk.includes(` b/${activeDiffPath.value}`) || chunk.includes(`a/${activeDiffPath.value} b/${activeDiffPath.value}`))
  if (!match) return ''
  return `diff --git ${match}`
})
const activeDiffLines = computed(() => {
  const lines = activeDiffPatch.value.split('\n')
  if (lines.length <= 4000) return lines
  return [...lines.slice(0, 4000), '…（改动过大，已截断显示）']
})
const diffLineClass = line => {
  if (line.startsWith('+++') || line.startsWith('---') || line.startsWith('@@') || line.startsWith('diff --git') || line.startsWith('index ')) return 'meta'
  if (line.startsWith('+')) return 'add'
  if (line.startsWith('-')) return 'del'
  return ''
}
const statusLabel = status => ({ added: 'A', deleted: 'D', renamed: 'R', untracked: 'U' }[status] || 'M')

const escapeHtml = value => value.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')

const loadDir = async (path, force = false) => {
  if (!props.conversationId) return
  if (!force && entriesByDir.value[path]) return
  const next = new Set(loadingDirs.value)
  next.add(path)
  loadingDirs.value = next
  if (path === '') treeLoading.value = true
  try {
    const result = await getProjectCodeTree(props.conversationId, path, props.runId)
    entriesByDir.value = { ...entriesByDir.value, [path]: result.entries || [] }
    treeError.value = ''
  } catch (error) {
    treeError.value = error.response?.data?.error || error.message || '目录读取失败'
  } finally {
    const done = new Set(loadingDirs.value)
    done.delete(path)
    loadingDirs.value = done
    if (path === '') treeLoading.value = false
  }
}

const onTreeRow = async row => {
  if (row.loading) return
  if (row.type === 'directory') {
    const next = new Set(expanded.value)
    if (next.has(row.path)) next.delete(row.path)
    else {
      next.add(row.path)
      if (!entriesByDir.value[row.path]) loadDir(row.path)
    }
    expanded.value = next
    return
  }
  await openFile(row.path)
}

const openFile = async path => {
  if (editing.value && dirty.value && !window.confirm('有未保存的修改，确定放弃并打开其他文件吗？')) return
  loadingFile.value = true
  fileError.value = ''
  editing.value = false
  try {
    const file = await getProjectCodeFile(props.conversationId, path, props.runId)
    activePath.value = file.path
    fileContent.value = file.content || ''
    fileMeta.value = file
  } catch (error) {
    activePath.value = path
    fileContent.value = ''
    fileMeta.value = null
    fileError.value = error.response?.data?.error || error.message || '文件读取失败'
  } finally {
    loadingFile.value = false
  }
}

const startEdit = () => {
  draft.value = fileContent.value
  editing.value = true
  fileError.value = ''
}
const cancelEdit = () => {
  editing.value = false
  draft.value = ''
}
const saveFile = async () => {
  if (!activePath.value || !dirty.value || saving.value) return
  saving.value = true
  try {
    const saved = await saveProjectCodeFile(props.conversationId, activePath.value, draft.value,
      fileMeta.value?.modifiedAt, props.runId)
    fileContent.value = saved.content || ''
    fileMeta.value = saved
    editing.value = false
    draft.value = ''
    fileError.value = ''
    await loadDiff()
  } catch (error) {
    fileError.value = error.response?.data?.error || error.message || '保存失败'
  } finally {
    saving.value = false
  }
}

const loadDiff = async () => {
  diffLoading.value = true
  diffError.value = ''
  try {
    const diff = await getProjectCodeDiff(props.conversationId, props.runId)
    diffFiles.value = diff.files || []
    diffPatch.value = diff.patch || ''
    diffMode.value = diff.mode || ''
    diffBranch.value = diff.branch || ''
    if (!diffFiles.value.some(file => file.path === activeDiffPath.value)) {
      activeDiffPath.value = diffFiles.value[0]?.path || ''
    }
  } catch (error) {
    diffError.value = error.response?.data?.error || error.message || '改动读取失败'
  } finally {
    diffLoading.value = false
  }
}

const switchTab = value => {
  tab.value = value
  if (value === 'diff') loadDiff()
}

const refreshAll = async () => {
  refreshing.value = true
  try {
    await loadDir('', true)
    if (activePath.value && !editing.value) await openFile(activePath.value)
    await loadDiff()
  } finally {
    refreshing.value = false
  }
}

const resetState = () => {
  tab.value = 'files'
  entriesByDir.value = {}
  expanded.value = new Set()
  activePath.value = ''
  fileContent.value = ''
  fileMeta.value = null
  fileError.value = ''
  editing.value = false
  draft.value = ''
  diffFiles.value = []
  diffPatch.value = ''
  activeDiffPath.value = ''
  treeError.value = ''
}

watch(() => props.open, async open => {
  if (!open) return
  resetState()
  await Promise.all([loadDir(''), loadDiff()])
})

watch(() => props.runId, async () => {
  if (!props.open) return
  await Promise.all([loadDir('', true), loadDiff()])
})

const onKeydown = event => {
  if (event.key === 'Escape' && props.open) emit('close')
}
onMounted(() => document.addEventListener('keydown', onKeydown))
onBeforeUnmount(() => document.removeEventListener('keydown', onKeydown))
</script>

<style scoped>
.code-overlay {
  position: fixed;
  z-index: 70;
  inset: 0;
  display: flex;
  justify-content: flex-end;
  background: rgba(15, 17, 26, 0.42);
  backdrop-filter: blur(2px);
}

.code-drawer {
  display: flex;
  width: min(1180px, 96vw);
  height: 100%;
  flex-direction: column;
  border-left: 1px solid var(--wb-border);
  background: var(--wb-surface);
  box-shadow: var(--wb-shadow-lg);
}

.code-head {
  display: flex;
  height: 54px;
  flex: 0 0 54px;
  align-items: center;
  gap: 18px;
  padding: 0 14px 0 16px;
  border-bottom: 1px solid var(--wb-border);
}

.head-main { display: flex; min-width: 0; align-items: center; gap: 9px; }
.head-icon {
  display: grid;
  width: 30px;
  height: 30px;
  place-items: center;
  border-radius: var(--wb-radius-sm);
  background: var(--wb-brand-soft);
  color: var(--wb-brand);
}
.head-copy { display: flex; min-width: 0; flex-direction: column; gap: 1px; }
.head-copy strong { overflow: hidden; max-width: 340px; color: var(--wb-text); font-size: 13px; font-weight: 650; text-overflow: ellipsis; white-space: nowrap; }
.head-sub { color: var(--wb-muted); font-size: 10.5px; }
.head-sub em { font-style: normal; }

.head-tabs { display: flex; align-items: center; gap: 2px; margin-left: 8px; }
.head-tabs button {
  display: inline-flex;
  height: 28px;
  align-items: center;
  gap: 5px;
  border: 0;
  border-radius: 7px;
  padding: 0 10px;
  background: transparent;
  color: var(--wb-text-2);
  font-size: 12px;
  font-weight: 600;
  cursor: pointer;
}
.head-tabs button:hover { background: var(--wb-fill); color: var(--wb-text); }
.head-tabs button.active { background: var(--wb-brand-soft); color: var(--wb-brand); }
.tab-count {
  min-width: 16px;
  border-radius: 999px;
  padding: 0 5px;
  background: var(--wb-surface);
  color: var(--wb-brand);
  font-size: 10px;
  font-style: normal;
  font-variant-numeric: tabular-nums;
  text-align: center;
}

.head-actions { display: flex; align-items: center; gap: 2px; margin-left: auto; }
.icon-button {
  display: grid;
  width: 28px;
  height: 28px;
  place-items: center;
  border: 0;
  border-radius: 7px;
  background: transparent;
  color: var(--wb-muted);
  cursor: pointer;
}
.icon-button:hover { background: var(--wb-fill); color: var(--wb-text); }
.spin { animation: code-spin 0.9s linear infinite; }
@keyframes code-spin { to { transform: rotate(360deg); } }

.code-body { display: flex; min-height: 0; flex: 1; }

.tree-pane {
  display: flex;
  width: 292px;
  flex: 0 0 292px;
  min-height: 0;
  flex-direction: column;
  border-right: 1px solid var(--wb-border);
  background: var(--wb-surface-2);
}
.tree-head {
  display: flex;
  height: 34px;
  flex: 0 0 34px;
  align-items: center;
  justify-content: space-between;
  padding: 0 12px;
  border-bottom: 1px solid var(--wb-border);
  color: var(--wb-muted);
  font-size: 10.5px;
  font-weight: 700;
  letter-spacing: 0.06em;
}
.tree-state { font-weight: 500; letter-spacing: 0; }
.tree-scroll { min-height: 0; flex: 1; overflow: auto; padding: 5px 6px 16px; }
.tree-row {
  display: flex;
  width: 100%;
  height: 27px;
  align-items: center;
  gap: 5px;
  border: 0;
  border-radius: 6px;
  padding-right: 8px;
  background: transparent;
  color: var(--wb-text-2);
  font-size: 12px;
  text-align: left;
  cursor: pointer;
}
.tree-row:hover { background: var(--wb-fill); color: var(--wb-text); }
.tree-row.active { background: var(--wb-brand-soft); color: var(--wb-brand); font-weight: 600; }
.tree-row:disabled { cursor: default; }
.tree-chevron { flex: 0 0 12px; color: var(--wb-muted); transition: transform 0.15s ease; }
.tree-chevron.open { transform: rotate(90deg); }
.tree-name { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }

.diff-row {
  display: grid;
  width: 100%;
  grid-template-columns: 18px minmax(0, 1fr) auto;
  align-items: center;
  gap: 6px;
  border: 0;
  border-radius: 6px;
  padding: 6px 8px;
  background: transparent;
  color: var(--wb-text-2);
  text-align: left;
  cursor: pointer;
}
.diff-row:hover { background: var(--wb-fill); }
.diff-row.active { background: var(--wb-brand-soft); }
.diff-status { font: 600 10px/1 ui-monospace, monospace; color: var(--wb-muted); }
.status-added { color: var(--wb-success); }
.status-deleted { color: var(--wb-danger); }
.status-untracked { color: var(--wb-info); }
.diff-name { overflow: hidden; font-size: 11.5px; text-overflow: ellipsis; white-space: nowrap; }
.diff-stats { display: flex; gap: 5px; font-size: 10px; font-variant-numeric: tabular-nums; }
.diff-stats em { color: var(--wb-success); font-style: normal; }
.diff-stats i { color: var(--wb-danger); font-style: normal; }
.tree-message { margin: 14px 8px; color: var(--wb-muted); font-size: 11.5px; line-height: 1.6; }
.tree-message.error { color: var(--wb-danger); }
.tree-message.hint { margin-top: 18px; font-size: 10.5px; }

.editor-pane { display: flex; min-width: 0; min-height: 0; flex: 1; flex-direction: column; }

.file-bar {
  display: flex;
  height: 38px;
  flex: 0 0 38px;
  align-items: center;
  gap: 10px;
  padding: 0 12px;
  border-bottom: 1px solid var(--wb-border);
}
.file-path { overflow: hidden; color: var(--wb-text); font-size: 12px; font-weight: 600; text-overflow: ellipsis; white-space: nowrap; }
.file-meta { color: var(--wb-muted); font-size: 10.5px; white-space: nowrap; }
.dirty-dot { margin-left: auto; color: var(--wb-warning); font-size: 10.5px; }
.bar-button {
  display: inline-flex;
  height: 26px;
  align-items: center;
  gap: 4px;
  border: 1px solid var(--wb-border);
  border-radius: 6px;
  padding: 0 9px;
  background: var(--wb-surface);
  color: var(--wb-text-2);
  font-size: 11.5px;
  font-weight: 600;
  cursor: pointer;
}
.bar-button:first-of-type { margin-left: auto; }
.bar-button:hover { border-color: var(--wb-brand); color: var(--wb-brand); }
.bar-button.primary { border-color: var(--wb-brand); background: var(--wb-brand); color: var(--wb-brand-contrast); }
.bar-button.primary:hover { background: var(--wb-brand-hover); }
.bar-button:disabled { opacity: 0.5; cursor: default; }

.pane-empty {
  display: flex;
  flex: 1;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 24px;
  color: var(--wb-muted);
  text-align: center;
}
.pane-empty strong { color: var(--wb-text); font-size: 13px; font-weight: 650; }
.pane-empty p { max-width: 380px; margin: 0; font-size: 11.5px; line-height: 1.6; }
.pane-error { margin: 16px; border: 1px solid color-mix(in srgb, var(--wb-danger) 30%, transparent); border-radius: var(--wb-radius-sm); padding: 10px 12px; background: color-mix(in srgb, var(--wb-danger) 8%, var(--wb-surface)); color: var(--wb-danger); font-size: 12px; }

.code-scroll {
  display: flex;
  min-height: 0;
  flex: 1;
  overflow: auto;
  background: var(--wb-surface);
  font: 12.5px/1.65 ui-monospace, "SF Mono", SFMono-Regular, Menlo, Consolas, monospace;
}
.code-gutter {
  position: sticky;
  left: 0;
  display: flex;
  flex: 0 0 auto;
  flex-direction: column;
  border-right: 1px solid var(--wb-border);
  padding: 10px 9px 24px 12px;
  background: var(--wb-surface-2);
  color: var(--wb-muted);
  text-align: right;
  user-select: none;
}
.code-view {
  margin: 0;
  padding: 10px 18px 24px;
  color: var(--wb-text);
  tab-size: 2;
  white-space: pre;
}
.code-view :deep(.hljs) { background: transparent; padding: 0; color: var(--wb-text); }
.code-view :deep(.hljs-keyword), .code-view :deep(.hljs-selector-tag) { color: var(--wb-brand); }
.code-view :deep(.hljs-string), .code-view :deep(.hljs-attr) { color: var(--wb-success); }
.code-view :deep(.hljs-comment) { color: var(--wb-muted); font-style: italic; }
.code-view :deep(.hljs-number), .code-view :deep(.hljs-literal) { color: var(--wb-warning); }
.code-view :deep(.hljs-title), .code-view :deep(.hljs-function) { color: var(--wb-info); }

.code-input {
  flex: 1;
  width: 100%;
  min-height: 0;
  resize: none;
  border: 0;
  outline: none;
  padding: 10px 18px 24px;
  background: var(--wb-surface);
  color: var(--wb-text);
  font: 12.5px/1.65 ui-monospace, "SF Mono", SFMono-Regular, Menlo, Consolas, monospace;
  tab-size: 2;
  white-space: pre;
  overflow: auto;
}

.diff-scroll { display: flex; min-height: 0; flex: 1; flex-direction: column; overflow: auto; }
.diff-file-head {
  display: flex;
  align-items: baseline;
  gap: 10px;
  padding: 8px 14px;
  border-bottom: 1px solid var(--wb-border);
  background: var(--wb-surface-2);
  color: var(--wb-text);
  font-size: 11.5px;
}
.diff-file-head span { color: var(--wb-muted); font-size: 10.5px; }
.diff-view {
  margin: 0;
  padding: 8px 0 24px;
  font: 12px/1.6 ui-monospace, "SF Mono", SFMono-Regular, Menlo, Consolas, monospace;
  white-space: pre;
  tab-size: 2;
}
.diff-line { display: block; padding: 0 14px; }
.diff-line.add { background: color-mix(in srgb, var(--wb-success) 10%, transparent); color: var(--wb-success); }
.diff-line.del { background: color-mix(in srgb, var(--wb-danger) 10%, transparent); color: var(--wb-danger); }
.diff-line.meta { color: var(--wb-muted); }

@media (max-width: 900px) {
  .tree-pane { width: 220px; flex-basis: 220px; }
  .head-copy strong { max-width: 180px; }
}

@media (max-width: 720px) {
  .code-drawer { width: 100vw; }
  .tree-pane { display: none; }
}
</style>
