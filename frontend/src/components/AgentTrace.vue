<template>
  <section class="agent-trace" aria-label="执行过程">
    <header class="trace-status">
      <button type="button" class="trace-toggle" :class="tone" :aria-expanded="expanded ? 'true' : 'false'"
        @click="expanded = !expanded">
        <span v-if="working" class="status-live" aria-hidden="true"></span>
        <span>{{ statusLabel || (toolCalls.length ? `执行了 ${toolCalls.length} 次操作` : `执行了 ${steps.length} 步`) }}</span>
        <span v-if="working && !entries.length" class="trace-count">· 正在思考…</span>
        <span v-else-if="toolCalls.length && statusLabel" class="trace-count">{{ toolCalls.length }} 次操作</span>
        <span v-else-if="steps.length && statusLabel" class="trace-count">{{ steps.length }} 步</span>
        <ChevronDown :size="13" class="trace-chevron" :class="{ rotated: expanded }" />
      </button>
    </header>
    <div v-if="message.thinking?.text" class="think-live" aria-live="polite">
      <span class="think-agent">{{ message.thinking.agent || '思考中' }}<span class="think-ellipsis">正在输出…</span></span>
      <span class="think-text">{{ message.thinking.text }}</span>
    </div>
    <div v-if="expanded && entries.length" class="trace-body">
      <nav v-if="workers.length" class="chain-tabs" aria-label="调用链范围">
        <button type="button" :class="{ active: selectedWorkerKey === 'all' }" @click="selectedWorkerKey = 'all'">全部</button>
        <button v-for="worker in workers" :key="worker.key" type="button"
          :class="{ active: selectedWorkerKey === worker.key }" @click="selectedWorkerKey = worker.key">
          {{ worker.label }}
        </button>
      </nav>
      <ol class="chain-entries" aria-label="执行调用链">
        <li v-for="entry in visibleEntries" :key="entry.key" class="chain-entry">
          <template v-if="entry.type === 'tool'">
            <button type="button" class="tool-heading" :aria-expanded="expandedToolId === entry.call.id"
              @click="expandedToolId = expandedToolId === entry.call.id ? null : entry.call.id">
              <Terminal v-if="entry.call.tool_name === 'executeTerminalCommand' || entry.call.tool_name === 'runProjectTask'" :size="14" />
              <Wrench v-else :size="14" />
              <span v-if="selectedWorkerKey === 'all' && entry.workerLabel" class="entry-agent">{{ entry.workerLabel }}</span>
              <strong>{{ toolCallLabel(entry.call.tool_name) }}</strong>
              <span v-if="toolCallArguments(entry.call)" class="tool-preview">{{ toolCallArguments(entry.call).split('\n')[0] }}</span>
              <span class="tool-state" :class="entry.call.status?.toLowerCase()">{{ toolCallStatus(entry.call) }}</span>
              <ChevronDown :size="13" class="tool-chevron" :class="{ rotated: expandedToolId === entry.call.id }" />
            </button>
            <div v-if="expandedToolId === entry.call.id" class="tool-detail">
              <div v-if="toolCallArguments(entry.call)"><span>参数</span><pre>{{ toolCallArguments(entry.call) }}</pre></div>
              <div v-if="toolCallResult(entry.call)"><span>返回摘要</span><pre>{{ toolCallResult(entry.call) }}</pre></div>
              <span v-if="!toolCallArguments(entry.call) && !toolCallResult(entry.call)" class="tool-empty">暂无可展示的参数或结果</span>
            </div>
          </template>
          <div v-else class="event-line">
            <component :is="iconOf(entry.activity)" :size="14" aria-hidden="true" />
            <span v-if="selectedWorkerKey === 'all'" class="entry-agent">{{ entry.workerLabel || agentOf(entry.activity) }}</span>
            <span class="event-summary">{{ entry.activity.summary }}</span>
          </div>
        </li>
      </ol>
    </div>
  </section>
</template>

<script setup>
// 智能体时间线：状态行 + 步骤条目。执行中默认展开并每秒刷新用时；
// 结束/历史消息默认折叠为摘要行（点击展开），与 ZCode 执行视图行为一致。
import { computed, ref } from 'vue'
import { ChevronDown, CircleDot, ListChecks, Search, ShieldCheck, Terminal, Wrench } from 'lucide-vue-next'
import { toolCallArguments, toolCallLabel, toolCallResult, toolCallStatus } from '../utils/toolCallDisplay.mjs'

const props = defineProps({
  message: { type: Object, required: true },
  index: { type: Number, default: -1 },
  working: { type: Boolean, default: false },
  now: { type: Number, default: 0 }
})

const toolCalls = computed(() => props.message.toolCalls || [])
const steps = computed(() => (props.message.activities || []).filter(activity =>
  !toolCalls.value.length || activity.phase !== 'work' || !activity.summary?.includes('→')))
const expanded = ref(props.working)
const expandedToolId = ref(null)
const selectedWorkerKey = ref('all')

const stepPhaseIcons = { plan: ListChecks, work: Search, verify: ShieldCheck }
const stepPhaseAgents = { plan: '规划', work: '执行', verify: '校验' }
const iconOf = activity => stepPhaseIcons[activity?.phase] || CircleDot
const agentOf = activity => activity?.agent || stepPhaseAgents[activity?.phase] || '步骤'
const workerLabel = key => {
  if (!key) return ''
  const activity = (props.message.activities || []).find(item => item.workerKey === key)
  const index = /worker-(\d+)-/.exec(key)?.[1]
  return `${activity?.agent || '子智能体'}${index ? ` #${index}` : ''}`
}
const workers = computed(() => {
  const keys = [...new Set([
    ...(props.message.activities || []).map(item => item.workerKey),
    ...toolCalls.value.map(call => call.worker_key)
  ].filter(Boolean))]
  return keys.map(key => ({ key, label: workerLabel(key) }))
})
const entries = computed(() => {
  const events = steps.value.map((activity, index) => ({
    type: 'event', key: `event-${index}`, activity, workerKey: activity.workerKey || '',
    workerLabel: workerLabel(activity.workerKey), at: activity.at || 0, order: index
  }))
  const calls = toolCalls.value.map((call, index) => ({
    type: 'tool', key: `tool-${call.id}`, call, workerKey: call.worker_key || '',
    workerLabel: workerLabel(call.worker_key), at: new Date(call.created_at).getTime() || 0,
    order: events.length + index
  }))
  return [...events, ...calls].sort((a, b) => a.at - b.at || a.order - b.order)
})
const visibleEntries = computed(() => selectedWorkerKey.value === 'all' ? entries.value
  : entries.value.filter(entry => entry.workerKey === selectedWorkerKey.value))

const formatDuration = ms => {
  if (!Number.isFinite(ms) || ms <= 0) return ''
  const totalSeconds = Math.round(ms / 1000)
  if (totalSeconds < 60) return `${totalSeconds} 秒`
  return `${Math.floor(totalSeconds / 60)} 分 ${totalSeconds % 60} 秒`
}

const elapsedMs = computed(() => {
  if (!props.message.startedAt) return null
  const end = props.message.finishedAt || (props.working ? props.now : null)
  return end == null ? null : Math.max(0, end - props.message.startedAt)
})

const statusLabel = computed(() => {
  const duration = formatDuration(elapsedMs.value)
  if (!duration) return props.working ? '工作中' : ''
  if (props.working) return `工作中 ${duration}`
  if (props.message.status === 'INTERRUPTED') return `已中断 · 用时 ${duration}`
  if (props.message.status === 'FAILED') return `执行失败 · 用时 ${duration}`
  return `已完成 · 用时 ${duration}`
})

const tone = computed(() => {
  if (props.working) return 'working'
  if (props.message.status === 'INTERRUPTED') return 'warn'
  if (props.message.status === 'FAILED') return 'error'
  return ''
})

</script>

<style scoped>
.agent-trace { display: grid; gap: 2px; margin-bottom: 12px; }

.trace-status { display: grid; }

.trace-toggle {
  display: flex;
  width: fit-content;
  max-width: 100%;
  align-items: center;
  gap: 8px;
  border: 0;
  border-radius: 8px;
  margin-left: -6px;
  padding: 3px 8px 3px 6px;
  background: transparent;
  color: var(--wb-text-2);
  font-size: 12.5px;
  font-weight: 500;
  text-align: left;
  cursor: pointer;
  white-space: nowrap;
}

.trace-toggle:hover { background: var(--wb-fill); }
.trace-count { color: var(--wb-muted); font-weight: 450; }
.trace-toggle.working { color: var(--wb-brand); }
.trace-toggle.warn { color: #b45309; }
.trace-toggle.error { color: #b91c1c; }

.trace-chevron { color: var(--wb-muted); transition: transform 0.15s ease; }
.trace-chevron.rotated { transform: rotate(180deg); }

/* worker 思考流式活区：与「已深度思考」面板同风格，文本只显示滚动尾部 */
.think-live {
  display: grid;
  gap: 3px;
  margin: 4px 0 2px;
  padding: 7px 10px;
  border-radius: 10px;
  background: color-mix(in srgb, var(--wb-fill) 55%, transparent);
}
.think-agent { display: flex; align-items: center; gap: 6px; color: var(--wb-muted); font-size: 11px; font-weight: 600; }
.think-ellipsis { font-weight: 450; color: var(--wb-brand); animation: trace-blink 1.05s ease-in-out infinite; }
.think-text {
  max-height: 110px;
  overflow-y: auto;
  color: var(--wb-text-2);
  font-size: 11.5px;
  line-height: 1.55;
  white-space: pre-wrap;
  word-break: break-word;
}
.trace-body { margin-top: 5px; border-left: 1px solid var(--wb-border); padding-left: 12px; }
.chain-tabs { display: flex; gap: 3px; max-width: 100%; overflow-x: auto; padding: 0 0 8px; }
.chain-tabs button { flex: 0 0 auto; border: 0; border-radius: 6px; padding: 4px 8px; background: transparent; color: var(--wb-muted); font-size: 11px; cursor: pointer; }
.chain-tabs button:hover, .chain-tabs button.active { background: var(--wb-fill); color: var(--wb-text); }
.chain-entries { display: grid; gap: 2px; margin: 0; padding: 0; list-style: none; }
.chain-entry { min-width: 0; }
.event-line { display: flex; align-items: center; gap: 7px; min-height: 30px; padding: 4px 7px; color: var(--wb-text-2); font-size: 12px; line-height: 1.45; }
.event-line svg { flex: 0 0 auto; color: var(--wb-muted); }
.entry-agent { flex: 0 0 auto; color: var(--wb-muted); font-size: 11px; }
.event-summary { min-width: 0; overflow-wrap: anywhere; }
.tool-calls { display: grid; gap: 6px; margin: 5px 0; padding: 0; list-style: none; }
.tool-call { border: 1px solid var(--wb-border); border-radius: 8px; overflow: hidden; background: var(--wb-surface); }
.tool-heading { display: flex; align-items: center; gap: 8px; width: 100%; min-height: 34px; border: 0; padding: 5px 10px; background: transparent; color: var(--wb-text-2); font-size: 12px; text-align: left; cursor: pointer; }
.tool-heading:hover { background: var(--wb-fill); }
.tool-heading strong { min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-weight: 600; }
.tool-preview { min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; color: var(--wb-muted); font: 11px ui-monospace, SFMono-Regular, Menlo, monospace; }
.tool-state { margin-left: auto; flex: 0 0 auto; color: var(--wb-muted); }
.tool-state.pending { color: var(--wb-brand); }
.tool-state.failed { color: #b91c1c; }
.tool-state.unknown { color: #b45309; }
.tool-chevron { flex: 0 0 auto; transition: transform .15s ease; }
.tool-chevron.rotated { transform: rotate(180deg); }
.tool-detail { display: grid; gap: 8px; border-top: 1px solid var(--wb-border); padding: 9px 11px; font-size: 11px; color: var(--wb-muted); }
.tool-detail pre { max-height: 220px; overflow: auto; margin: 3px 0 0; color: var(--wb-text-2); font: 11px/1.5 ui-monospace, SFMono-Regular, Menlo, monospace; white-space: pre-wrap; overflow-wrap: anywhere; }
.tool-empty { color: var(--wb-muted); }

.status-live {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: var(--wb-brand);
  animation: trace-blink 1.05s ease-in-out infinite;
}

/* DeepSeek「已深度思考」式：展开后是一块浅灰面板，步骤行不再悬浮散排 */
.trace-steps {
  display: grid;
  gap: 1px;
  margin: 4px 0 2px;
  padding: 7px 10px;
  list-style: none;
  border-radius: 12px;
  background: color-mix(in srgb, var(--wb-fill) 55%, transparent);
}

.step-line {
  display: flex;
  align-items: center;
  gap: 6px;
  width: 100%;
  border: 0;
  border-radius: 7px;
  padding: 3px 6px;
  background: transparent;
  color: var(--wb-muted);
  font-size: 12.5px;
  line-height: 1.55;
  text-align: left;
  cursor: default;
}

.step-line.has-detail { cursor: pointer; }
.step-line:hover { background: var(--wb-surface); color: var(--wb-text-2); }
.step-icon { flex: 0 0 auto; }
.step-agent { flex: 0 0 auto; font-weight: 600; }
.step-dot { flex: 0 0 auto; }
.step-summary { min-width: 0; }
.step-duration { flex: 0 0 auto; }

.step-detail {
  max-height: 240px;
  overflow: auto;
  margin: 2px 0 5px;
  padding: 8px 10px;
  border-radius: 9px;
  background: var(--wb-surface);
  color: var(--wb-text-2);
  font: 11px/1.55 ui-monospace, "SF Mono", SFMono-Regular, Menlo, monospace;
  white-space: pre-wrap;
  word-break: break-word;
}

@keyframes trace-blink {
  0%, 70%, 100% { opacity: 0.3; }
  35% { opacity: 1; }
}

@media (prefers-reduced-motion: reduce) {
  .status-live { animation: none; opacity: 0.7; }
}

@media (max-width: 720px) {
  .tool-heading { flex-wrap: wrap; row-gap: 3px; }
  .tool-heading strong { flex: 0 1 auto; }
  .tool-preview { order: 5; flex: 0 0 100%; padding-left: 22px; white-space: normal; overflow-wrap: anywhere; }
}
</style>
