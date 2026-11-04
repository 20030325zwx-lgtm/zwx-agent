<template>
  <section class="run-summary" aria-label="执行摘要">
    <button type="button" class="summary-head" :aria-expanded="expanded ? 'true' : 'false'" @click="expanded = !expanded">
      <ListChecks :size="14" class="head-icon" />
      <span class="head-title">执行摘要</span>
      <span class="chip">{{ summary.total }} 次工具调用</span>
      <span v-if="summary.modify" class="chip">{{ summary.modify }} 次修改</span>
      <span v-if="taskStats.total" class="chip" :class="taskStats.failed ? 'warn' : 'ok'">
        任务 {{ taskStats.passed }}/{{ taskStats.total }} 通过
      </span>
      <ChevronDown :size="13" class="chevron" :class="{ open: expanded }" />
    </button>

    <div v-if="expanded" class="summary-body">
      <div class="buckets">
        <span class="bucket">读取 <strong>{{ summary.read }}</strong></span>
        <span class="bucket">修改 <strong>{{ summary.modify }}</strong></span>
        <span class="bucket">运行 <strong>{{ summary.run }}</strong></span>
        <span v-if="summary.other" class="bucket">其他 <strong>{{ summary.other }}</strong></span>
      </div>

      <div v-if="tasks.length" class="task-list">
        <div v-for="task in tasks" :key="task.key" class="task-item" :class="{ fail: !task.ok }">
          <button type="button" class="task-row" :aria-expanded="openTask === task.key" @click="toggleTask(task.key)">
            <component :is="task.ok ? CheckCircle2 : XCircle" :size="14" class="task-state" />
            <strong class="task-name">{{ task.id }}</strong>
            <span class="task-meta">
              {{ task.ok ? '通过' : '失败' }}<template v-if="task.seconds"> · {{ task.seconds }}s</template><template v-if="task.exit !== ''"> · exit {{ task.exit }}</template>
            </span>
            <ChevronDown v-if="task.output" :size="12" class="task-chevron" :class="{ open: openTask === task.key }" />
          </button>
          <pre v-if="task.output && openTask === task.key" class="task-output">{{ task.output }}</pre>
        </div>
      </div>
      <p v-else-if="!summary.run" class="body-hint">本轮没有构建 / 测试 / lint 任务。</p>
    </div>
  </section>
</template>

<script setup>
import { computed, ref } from 'vue'
import { CheckCircle2, ChevronDown, ListChecks, XCircle } from 'lucide-vue-next'

const props = defineProps({
  summary: { type: Object, required: true }
})

const expanded = ref(false)
const openTask = ref('')
const tasks = computed(() => props.summary?.tasks || [])
const taskStats = computed(() => ({
  total: tasks.value.length,
  passed: tasks.value.filter(task => task.ok).length,
  failed: tasks.value.filter(task => !task.ok).length
}))
const toggleTask = key => { openTask.value = openTask.value === key ? '' : key }
</script>

<style scoped>
/* 与 AgentTrace 同语言：浅灰面板 + 弱化文字，DeepSeek 式轻卡片 */
.run-summary {
  display: grid;
  gap: 2px;
  margin-top: 12px;
}

.summary-head {
  display: flex;
  width: max-content;
  max-width: 100%;
  align-items: center;
  gap: 8px;
  border: 1px solid var(--wb-border);
  border-radius: 999px;
  margin-left: -2px;
  padding: 5px 12px 5px 10px;
  background: var(--wb-surface);
  color: var(--wb-text-2);
  font-size: 12.5px;
  font-weight: 500;
  text-align: left;
  cursor: pointer;
  transition: border-color 0.14s ease, background 0.14s ease;
}

.summary-head:hover { border-color: var(--wb-border-strong); background: var(--wb-surface-2); }

.head-icon { color: var(--wb-muted); }
.head-title { color: var(--wb-text); font-weight: 550; }

.chip {
  border-radius: 999px;
  padding: 1px 8px;
  background: color-mix(in srgb, var(--wb-fill) 70%, transparent);
  color: var(--wb-muted);
  font-size: 11.5px;
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
}

.chip.ok { background: color-mix(in srgb, var(--wb-success) 11%, transparent); color: var(--wb-success); }
.chip.warn { background: color-mix(in srgb, var(--wb-danger) 10%, transparent); color: var(--wb-danger); }

.chevron { color: var(--wb-muted); transition: transform 0.15s ease; }
.chevron.open { transform: rotate(180deg); }

.summary-body {
  display: grid;
  gap: 8px;
  margin-top: 6px;
  border-radius: 12px;
  padding: 10px 12px;
  background: color-mix(in srgb, var(--wb-fill) 55%, transparent);
}

.buckets { display: flex; flex-wrap: wrap; gap: 4px 16px; color: var(--wb-muted); font-size: 12px; }
.bucket strong { color: var(--wb-text-2); font-variant-numeric: tabular-nums; }

.task-list { display: grid; gap: 4px; }

.task-item { border-radius: 8px; }
.task-item.fail { background: color-mix(in srgb, var(--wb-danger) 6%, transparent); }

.task-row {
  display: flex;
  width: 100%;
  align-items: center;
  gap: 7px;
  border: 0;
  border-radius: 8px;
  padding: 4px 6px;
  background: transparent;
  color: var(--wb-muted);
  font-size: 12.5px;
  text-align: left;
  cursor: pointer;
}

.task-row:hover { background: var(--wb-surface); }
.task-item:not(.fail) .task-state { color: var(--wb-success); }
.task-item.fail .task-state { color: var(--wb-danger); }
.task-name { color: var(--wb-text-2); font-weight: 600; }
.task-meta { color: var(--wb-muted); font-size: 11.5px; font-variant-numeric: tabular-nums; }
.task-chevron { margin-left: auto; transition: transform 0.15s ease; }
.task-chevron.open { transform: rotate(180deg); }

.task-output {
  max-height: 220px;
  overflow: auto;
  margin: 2px 0 6px;
  border-radius: 9px;
  padding: 8px 10px;
  background: var(--wb-surface);
  color: var(--wb-text-2);
  font: 11px/1.55 ui-monospace, "SF Mono", SFMono-Regular, Menlo, monospace;
  white-space: pre-wrap;
  word-break: break-word;
}

.body-hint { margin: 0; color: var(--wb-muted); font-size: 12px; }

@media (prefers-reduced-motion: reduce) {
  .chevron, .task-chevron { transition: none; }
}
</style>
