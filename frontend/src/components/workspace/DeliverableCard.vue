<template>
  <section class="deliverables" aria-label="生成的文件">
    <header class="deliverables-head">
      <FolderOutput :size="14" class="head-icon" />
      <strong>{{ files.length }} 个文件已生成</strong>
      <span class="head-hint">{{ desktop ? '可下载或保存到本机' : '可直接下载' }}</span>
      <button v-if="desktop && conversationId && files.length > 1" type="button" class="head-action"
        :disabled="bulkSaving" @click="saveAllToDirectory">
        <FolderDown :size="12" />
        {{ bulkSaving ? '保存中…' : '全部保存到文件夹…' }}
      </button>
    </header>
    <ul class="file-list">
      <li v-for="file in files" :key="file.path">
        <div class="file-row-wrap">
          <a class="file-row" :class="{ pending: !file.url }" :href="file.url || undefined"
            :download="file.url ? file.name : undefined" :target="file.url ? '_blank' : undefined"
            rel="noopener noreferrer" :title="file.path">
            <span class="file-icon"><component :is="iconOf(file.path)" :size="14" /></span>
            <span class="file-name">{{ file.name }}</span>
            <span class="file-meta">
              {{ formatSize(file.size) }}<template v-if="!file.url"> · 链接准备中</template>
            </span>
            <Download :size="13" class="download-icon" />
          </a>
          <button v-if="desktop && file.url" type="button" class="row-action" :disabled="savingPath === file.path"
            :title="`另存为…（保存到本机任意位置）`" @click="saveAs(file)">
            <component :is="savingPath === file.path ? Loader2 : FolderDown" :size="12"
              :class="{ spin: savingPath === file.path }" />
            另存为…
          </button>
        </div>
      </li>
    </ul>
    <p v-if="savedNote" class="saved-note">
      {{ savedNote }}
      <button type="button" class="reveal-link" @click="reveal">在访达中显示</button>
    </p>
    <p v-if="error" class="save-error" role="alert">{{ error }}</p>
  </section>
</template>

<script setup>
import { computed, ref } from 'vue'
import {
  Archive, Download, FileCode, FileText, FileType, FolderDown, FolderOutput,
  Image as ImageIcon, Loader2, Table2
} from 'lucide-vue-next'
import { exportManusFiles } from '../../api'
import {
  chooseExportDirectory, desktopSaveAvailable, revealInFolder, saveUrlWithDialog
} from '../../api/desktop'

const props = defineProps({
  files: { type: Array, default: () => [] },
  conversationId: { type: String, default: '' }
})

const desktop = desktopSaveAvailable()
const savingPath = ref('')
const bulkSaving = ref(false)
const savedNote = ref('')
const savedPaths = ref([])
const error = ref('')

const ICONS = [
  { test: ['xlsx', 'xls', 'csv', 'tsv'], icon: Table2 },
  { test: ['md', 'markdown', 'txt', 'log'], icon: FileText },
  { test: ['json', 'html', 'htm', 'xml', 'yaml', 'yml'], icon: FileCode },
  { test: ['pdf', 'docx', 'doc', 'pptx', 'ppt'], icon: FileType },
  { test: ['png', 'jpg', 'jpeg', 'gif', 'webp', 'svg'], icon: ImageIcon },
  { test: ['zip', 'tar', 'gz', '7z'], icon: Archive }
]
const extOf = path => String(path || '').split('.').pop().toLowerCase()
const iconOf = path => {
  const ext = extOf(path)
  const match = ICONS.find(item => item.test.includes(ext))
  return match ? match.icon : FileText
}
const files = computed(() => (props.files || []).map(file => ({
  path: file.path,
  name: String(file.path || '').split('/').pop(),
  size: file.size || 0,
  url: file.url || ''
})))

const formatSize = bytes => {
  const value = Number(bytes) || 0
  if (value < 1024) return `${value} B`
  if (value < 1024 * 1024) return `${(value / 1024).toFixed(1)} KB`
  return `${(value / 1024 / 1024).toFixed(1)} MB`
}

const reveal = () => {
  const path = savedPaths.value[0]
  if (path) revealInFolder(path)
}

const saveAs = async file => {
  if (!file.url || savingPath.value) return
  savingPath.value = file.path
  error.value = ''
  try {
    const savedPath = await saveUrlWithDialog(file.url, file.name)
    if (savedPath) {
      savedPaths.value = [savedPath]
      savedNote.value = `已保存：${savedPath}`
    }
  } catch (exception) {
    error.value = `保存失败：${exception?.message || exception}`
  } finally {
    savingPath.value = ''
  }
}

const saveAllToDirectory = async () => {
  if (bulkSaving.value || !props.conversationId) return
  bulkSaving.value = true
  error.value = ''
  try {
    const directory = await chooseExportDirectory()
    if (!directory) return
    const paths = files.value.filter(file => file.url).map(file => file.path)
    if (!paths.length) {
      error.value = '文件链接尚未准备好，请稍后再试'
      return
    }
    const saved = await exportManusFiles(props.conversationId, paths, directory, false)
    savedPaths.value = (saved || []).map(item => item.to)
    if (savedPaths.value.length) {
      savedNote.value = `已保存 ${savedPaths.value.length} 个文件到 ${directory}`
    }
  } catch (exception) {
    error.value = `保存失败：${exception?.response?.data?.message || exception?.response?.data || exception?.message || exception}`
  } finally {
    bulkSaving.value = false
  }
}
</script>

<style scoped>
/* 与执行摘要/改动卡片同语言：浅灰面板 + 弱化元信息 */
.deliverables {
  display: grid;
  gap: 6px;
  margin-top: 12px;
  border: 1px solid var(--wb-border);
  border-radius: 12px;
  padding: 10px 12px;
  background: color-mix(in srgb, var(--wb-fill) 40%, transparent);
}

.deliverables-head { display: flex; align-items: center; gap: 7px; }
.head-icon { color: var(--wb-muted); }
.deliverables-head strong { color: var(--wb-text); font-size: 12.5px; font-weight: 600; }
.head-hint { color: var(--wb-muted); font-size: 11.5px; }

.head-action {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  margin-left: auto;
  border: 1px solid var(--wb-border);
  border-radius: 999px;
  padding: 2px 9px;
  background: var(--wb-surface);
  color: var(--wb-text-2);
  font-size: 11px;
  font-weight: 550;
  cursor: pointer;
}

.head-action:hover:not(:disabled) { border-color: var(--wb-border-strong); color: var(--wb-text); }
.head-action:disabled { opacity: 0.6; cursor: default; }

.file-list { display: grid; gap: 1px; margin: 0; padding: 0; list-style: none; }

.file-row-wrap { display: flex; align-items: center; gap: 4px; }

.file-row {
  display: flex;
  min-width: 0;
  flex: 1;
  align-items: center;
  gap: 8px;
  border-radius: 7px;
  padding: 4px 6px;
  color: var(--wb-text-2);
  font-size: 12px;
  text-decoration: none;
  transition: background 0.14s ease;
}

.file-row:hover { background: var(--wb-surface); }
.file-row.pending { opacity: 0.6; cursor: default; }

.file-icon { display: grid; flex: 0 0 auto; width: 18px; place-items: center; color: var(--wb-muted); }
.file-name {
  overflow: hidden;
  min-width: 0;
  flex: 1;
  color: var(--wb-text-2);
  font-family: ui-monospace, "SF Mono", SFMono-Regular, Menlo, monospace;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.file-meta { flex: 0 0 auto; color: var(--wb-muted); font-size: 11px; font-variant-numeric: tabular-nums; }
.download-icon { flex: 0 0 auto; color: var(--wb-muted); }
.file-row:hover .download-icon { color: var(--wb-text); }

.row-action {
  display: inline-flex;
  flex: 0 0 auto;
  align-items: center;
  gap: 3px;
  border: 0;
  border-radius: 6px;
  padding: 3px 6px;
  background: transparent;
  color: var(--wb-muted);
  font-size: 11px;
  cursor: pointer;
  opacity: 0.75;
}

.row-action:hover:not(:disabled) { background: var(--wb-surface); color: var(--wb-text); }
.row-action:disabled { cursor: default; opacity: 0.5; }

.saved-note { margin: 2px 0 0; color: var(--wb-success); font-size: 11.5px; word-break: break-all; }
.saved-note .reveal-link {
  margin-left: 6px;
  border: 0;
  padding: 0;
  background: transparent;
  color: var(--wb-brand);
  font-size: 11.5px;
  cursor: pointer;
  text-decoration: underline;
}

.save-error { margin: 2px 0 0; color: var(--wb-danger); font-size: 11.5px; }

.spin { animation: spin 0.9s linear infinite; }
@keyframes spin { to { transform: rotate(360deg); } }
@media (prefers-reduced-motion: reduce) { .spin { animation: none; } }
</style>
