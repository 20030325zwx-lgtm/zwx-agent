<template>
  <Teleport to="body">
    <div class="citation-dialog-backdrop" @click.self="emit('close')">
      <section class="citation-dialog" role="dialog" aria-modal="true" aria-label="引用内容预览">
        <header class="citation-head">
          <div class="citation-title">
            <span class="citation-kicker">引用内容</span>
            <strong>
              {{ preview.filename || '未知文档' }}
              <em v-if="preview.chunkIndex !== null && preview.chunkIndex !== undefined">#{{ preview.chunkIndex }}</em>
            </strong>
            <span v-if="regionLabel" class="region-label">{{ regionLabel }}</span>
            <span v-if="preview.legacy" class="legacy-badge">旧索引</span>
          </div>
          <button type="button" class="close-button" aria-label="关闭引用预览" @click="emit('close')">
            <X :size="18" />
          </button>
        </header>

        <div class="citation-body">
          <p v-if="loading" class="citation-state">正在加载引用内容...</p>
          <p v-else-if="error" class="citation-state error">{{ error }}</p>

          <template v-else>
            <div v-if="hasTable" class="table-shell">
              <div class="table-meta">
                <strong>{{ table.caption || '表格' }}</strong>
                <span v-if="preview.pageNumber">第 {{ preview.pageNumber }} 页</span>
                <span v-if="preview.sectionPath">{{ preview.sectionPath }}</span>
              </div>
              <div class="table-scroll">
                <table class="citation-table">
                  <thead>
                    <tr>
                      <th v-for="(header, index) in table.headers" :key="`h-${index}`">{{ header }}</th>
                    </tr>
                  </thead>
                  <tbody>
                    <tr v-for="(row, rowIndex) in table.rows" :key="`r-${rowIndex}`" :class="{ highlight: highlightRows.has(rowIndex) }">
                      <td v-for="(cell, cellIndex) in row" :key="`c-${rowIndex}-${cellIndex}`">{{ cell }}</td>
                    </tr>
                  </tbody>
                </table>
              </div>
              <p v-if="highlightRows.size" class="highlight-hint">已高亮本次引用涉及的表格行</p>
            </div>

            <pre v-else class="citation-text">{{ displayText }}</pre>

            <div v-if="preview.sectionPath || preview.pageNumber" class="region-meta">
              <span v-if="preview.sectionPath">章节：{{ preview.sectionPath }}</span>
              <span v-if="preview.pageNumber">页码：第 {{ preview.pageNumber }} 页</span>
            </div>
          </template>
        </div>

        <footer class="citation-foot">
          <span>文档 {{ preview.documentId || '未记录' }}</span>
          <span>
            <template v-if="preview.pageNumber">第 {{ preview.pageNumber }} 页 · </template>
            <template v-if="preview.chunkIndex !== null && preview.chunkIndex !== undefined">切片 #{{ preview.chunkIndex }}</template>
            <template v-else>来源块 {{ preview.blockId || '未记录' }}</template>
          </span>
        </footer>
      </section>
    </div>
  </Teleport>
</template>

<script setup>
import { computed } from 'vue'
import { X } from 'lucide-vue-next'

const props = defineProps({
  preview: { type: Object, required: true },
  loading: { type: Boolean, default: false },
  error: { type: String, default: '' }
})
const emit = defineEmits(['close'])

const table = computed(() => props.preview?.table || null)
const hasTable = computed(() => !props.loading && !props.error
  && Array.isArray(table.value?.rows) && table.value.rows.length > 0)
const highlightRows = computed(() => new Set(
  Array.isArray(table.value?.highlightRows) ? table.value.highlightRows.map(Number) : []
))
const regionLabel = computed(() => props.preview?.regionLabel || '')
const displayText = computed(() => props.preview?.displayText || props.preview?.content || '')
</script>

<style scoped>
.citation-dialog-backdrop {
  position: fixed;
  inset: 0;
  z-index: 1300;
  display: grid;
  place-items: center;
  padding: 24px;
  background: rgba(16, 24, 40, 0.4);
  backdrop-filter: blur(8px);
  -webkit-backdrop-filter: blur(8px);
}

.citation-dialog {
  display: flex;
  flex-direction: column;
  width: min(1040px, 100%);
  max-height: min(82vh, 820px);
  overflow: hidden;
  border-radius: 18px;
  background: #fff;
  box-shadow: 0 28px 80px rgba(15, 23, 42, 0.3);
}

.citation-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  padding: 18px 20px 14px;
  border-bottom: 1px solid var(--sk-separator);
}

.citation-title { display: grid; min-width: 0; gap: 5px; }
.citation-kicker { color: var(--sk-label-3); font-size: 10.5px; font-weight: 650; letter-spacing: 0.1em; }
.citation-title strong { overflow: hidden; color: var(--sk-label); font-size: 15px; text-overflow: ellipsis; white-space: nowrap; }
.citation-title strong em { margin-left: 4px; color: var(--zwx-primary); font-style: normal; }
.region-label { color: var(--zwx-primary); font-size: 12px; font-weight: 600; }
.legacy-badge { width: fit-content; border-radius: 999px; padding: 2px 7px; background: var(--sk-fill); color: var(--sk-label-2); font-size: 10px; }

.close-button {
  display: grid;
  width: 30px;
  height: 30px;
  flex: 0 0 30px;
  place-items: center;
  border: 0;
  border-radius: 9px;
  background: var(--sk-fill);
  color: var(--sk-label-2);
  cursor: pointer;
}
.close-button:hover { background: var(--sk-fill-strong); color: var(--sk-label); }

.citation-body { min-height: 0; flex: 1; overflow: auto; padding: 16px 20px; background: #fbfcfe; }
.citation-state { margin: 0; padding: 48px 16px; color: var(--sk-label-3); font-size: 13px; text-align: center; }
.citation-state.error { color: #d9382c; }

.table-shell { display: grid; gap: 9px; }
.table-meta { display: flex; flex-wrap: wrap; align-items: baseline; gap: 6px 12px; }
.table-meta strong { color: var(--sk-label); font-size: 13.5px; }
.table-meta span { color: var(--sk-label-3); font-size: 11px; }

.table-scroll {
  overflow: auto;
  border: 1px solid var(--sk-separator);
  border-radius: 12px;
  background: #fff;
}

.citation-table { width: 100%; border-collapse: separate; border-spacing: 0; font-size: 12px; line-height: 1.55; }
.citation-table th, .citation-table td { min-width: 96px; border-right: 1px solid var(--sk-separator); border-bottom: 1px solid var(--sk-separator); padding: 8px 10px; text-align: left; vertical-align: top; }
.citation-table th:last-child, .citation-table td:last-child { border-right: 0; }
.citation-table tbody tr:last-child td { border-bottom: 0; }
.citation-table thead th { position: sticky; top: 0; z-index: 1; background: #f4f8ff; color: var(--sk-label); font-weight: 700; white-space: nowrap; }
.citation-table tbody tr:nth-child(even) td { background: #fcfdff; }
.citation-table tbody tr.highlight td { background: #fff7d6; box-shadow: inset 3px 0 0 #f59e0b; }
.citation-table tbody tr.highlight td:first-child { font-weight: 650; }
.highlight-hint { margin: 0; color: #b45309; font-size: 11px; }

.citation-text {
  margin: 0;
  border: 1px solid var(--sk-separator);
  border-radius: 12px;
  padding: 16px;
  background: #fff;
  color: var(--sk-label-2);
  font: 13px/1.75 ui-monospace, "SF Mono", SFMono-Regular, Menlo, monospace;
  white-space: pre-wrap;
  word-break: break-word;
}

.region-meta { display: flex; flex-wrap: wrap; gap: 6px 16px; margin-top: 10px; color: var(--sk-label-3); font-size: 11px; }

.citation-foot {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  padding: 10px 20px;
  border-top: 1px solid var(--sk-separator);
  color: var(--sk-label-3);
  font-size: 11px;
}

@media (max-width: 720px) {
  .citation-dialog-backdrop { padding: 10px; }
  .citation-dialog { max-height: 90vh; }
  .citation-body { padding: 12px; }
  .citation-table th, .citation-table td { min-width: 82px; padding: 7px 8px; }
  .citation-foot { flex-direction: column; gap: 3px; }
}

@media (prefers-reduced-motion: reduce) {
  .citation-dialog-backdrop { backdrop-filter: none; -webkit-backdrop-filter: none; }
}
</style>
