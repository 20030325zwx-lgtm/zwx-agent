<template>
  <section class="change-summary" aria-label="工作区改动">
    <header class="change-head">
      <FileDiff :size="14" class="head-icon" />
      <div class="head-copy">
        <strong>工作区改动 {{ summary.count }} 个文件</strong>
        <span class="head-meta">
          <em class="add">+{{ summary.additions }}</em>
          <i class="del">−{{ summary.deletions }}</i>
          <template v-if="summary.branch"> · 分支 {{ summary.branch }}</template>
        </span>
      </div>
      <button type="button" class="open-btn" @click="emit('open', '')">
        查看改动
        <ArrowRight :size="13" />
      </button>
    </header>

    <ul v-if="previewFiles.length" class="file-list">
      <li v-for="file in previewFiles" :key="file.path">
        <button type="button" class="file-row" :title="file.path" @click="emit('open', file.path)">
          <span class="status" :class="`status-${file.status}`">{{ statusLabel(file.status) }}</span>
          <span class="path">{{ file.path }}</span>
          <span class="file-stats">
            <em class="add">+{{ file.additions }}</em>
            <i class="del">−{{ file.deletions }}</i>
          </span>
        </button>
      </li>
      <li v-if="summary.count > previewFiles.length" class="more-row">
        还有 {{ summary.count - previewFiles.length }} 个文件…
      </li>
    </ul>
  </section>
</template>

<script setup>
import { computed } from 'vue'
import { ArrowRight, FileDiff } from 'lucide-vue-next'

const props = defineProps({
  summary: { type: Object, required: true }
})
const emit = defineEmits(['open'])

const previewFiles = computed(() => (props.summary?.files || []).slice(0, 3))
const statusLabel = status => ({ added: 'A', deleted: 'D', renamed: 'R', untracked: 'U' }[status] || 'M')
</script>

<style scoped>
.change-summary {
  display: grid;
  gap: 8px;
  margin-top: 12px;
  border: 1px solid var(--wb-border);
  border-radius: 12px;
  padding: 10px 12px;
  background: color-mix(in srgb, var(--wb-fill) 40%, transparent);
}

.change-head { display: flex; align-items: center; gap: 8px; }
.head-icon { flex: 0 0 auto; color: var(--wb-muted); }

.head-copy { display: flex; min-width: 0; flex: 1; flex-wrap: wrap; align-items: baseline; gap: 4px 8px; }
.head-copy strong { color: var(--wb-text); font-size: 12.5px; font-weight: 600; }
.head-meta { color: var(--wb-muted); font-size: 11.5px; font-variant-numeric: tabular-nums; }

.add { color: var(--wb-success); font-style: normal; }
.del { color: var(--wb-danger); font-style: normal; }

.open-btn {
  display: inline-flex;
  flex: 0 0 auto;
  align-items: center;
  gap: 3px;
  border: 1px solid var(--wb-border);
  border-radius: 999px;
  padding: 3px 10px;
  background: var(--wb-surface);
  color: var(--wb-text-2);
  font-size: 11.5px;
  font-weight: 550;
  cursor: pointer;
  transition: border-color 0.14s ease, color 0.14s ease;
}

.open-btn:hover { border-color: var(--wb-border-strong); color: var(--wb-text); }

.file-list { display: grid; gap: 1px; margin: 0; padding: 0; list-style: none; }

.file-row {
  display: flex;
  width: 100%;
  align-items: center;
  gap: 8px;
  border: 0;
  border-radius: 7px;
  padding: 3px 6px;
  background: transparent;
  color: var(--wb-text-2);
  font-size: 12px;
  font-family: ui-monospace, "SF Mono", SFMono-Regular, Menlo, monospace;
  text-align: left;
  cursor: pointer;
}

.file-row:hover { background: var(--wb-surface); }

.status {
  flex: 0 0 auto;
  width: 16px;
  border-radius: 4px;
  font-size: 10px;
  font-weight: 700;
  line-height: 16px;
  text-align: center;
  background: var(--wb-fill);
  color: var(--wb-muted);
}

.status-added, .status-untracked { background: color-mix(in srgb, var(--wb-success) 14%, transparent); color: var(--wb-success); }
.status-deleted { background: color-mix(in srgb, var(--wb-danger) 12%, transparent); color: var(--wb-danger); }
.status-renamed { background: color-mix(in srgb, var(--wb-info) 14%, transparent); color: var(--wb-info); }

.path {
  overflow: hidden;
  min-width: 0;
  flex: 1;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.file-stats { flex: 0 0 auto; font-size: 11px; font-variant-numeric: tabular-nums; }

.more-row { padding: 2px 6px; color: var(--wb-muted); font-size: 11.5px; }
</style>
