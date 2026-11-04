<template>
  <section class="deliverables" aria-label="生成的文件">
    <header class="deliverables-head">
      <FolderOutput :size="14" class="head-icon" />
      <strong>{{ files.length }} 个文件已生成</strong>
      <span class="head-hint">可直接下载</span>
    </header>
    <ul class="file-list">
      <li v-for="file in files" :key="file.path">
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
      </li>
    </ul>
  </section>
</template>

<script setup>
import { computed } from 'vue'
import {
  Archive, Download, FileCode, FileText, FileType, FolderOutput, Image as ImageIcon, Table2
} from 'lucide-vue-next'

const props = defineProps({
  files: { type: Array, default: () => [] }
})

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

.file-list { display: grid; gap: 1px; margin: 0; padding: 0; list-style: none; }

.file-row {
  display: flex;
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
</style>
