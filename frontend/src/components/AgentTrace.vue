<template>
  <section class="agent-trace" aria-label="执行过程">
    <header class="trace-status">
      <button type="button" class="trace-toggle" :class="tone" :aria-expanded="expanded ? 'true' : 'false'"
        @click="expanded = !expanded">
        <span v-if="working" class="status-live" aria-hidden="true"></span>
        <span>{{ statusLabel || `执行了 ${steps.length} 步` }}</span>
        <span v-if="steps.length" class="trace-count">{{ steps.length }} 步</span>
        <ChevronDown :size="13" class="trace-chevron" :class="{ rotated: expanded }" />
      </button>
    </header>
    <ol v-if="steps.length && expanded" class="trace-steps">
      <li v-for="(activity, activityIndex) in steps" :key="`step-${activityIndex}`" class="trace-step">
        <button type="button" class="step-line" :class="{ 'has-detail': detailOf(activity) }"
          :aria-expanded="expandedStepKey === activityIndex ? 'true' : 'false'"
          @click="toggleStep(activityIndex)">
          <component :is="iconOf(activity)" :size="14" class="step-icon" aria-hidden="true" />
          <span class="step-agent">{{ agentOf(activity) }}</span>
          <span class="step-dot" aria-hidden="true">·</span>
          <span class="step-summary">{{ activity.summary }}</span>
          <span v-if="durationOf(activityIndex)" class="step-duration">持续了 {{ durationOf(activityIndex) }}</span>
        </button>
        <pre v-if="expandedStepKey === activityIndex && detailOf(activity)" class="step-detail">{{ activity.detail }}</pre>
      </li>
    </ol>
  </section>
</template>

<script setup>
// 智能体时间线：状态行 + 步骤条目。执行中默认展开并每秒刷新用时；
// 结束/历史消息默认折叠为摘要行（点击展开），与 ZCode 执行视图行为一致。
import { computed, ref } from 'vue'
import { ChevronDown, CircleDot, ListChecks, Search, ShieldCheck } from 'lucide-vue-next'

const props = defineProps({
  message: { type: Object, required: true },
  index: { type: Number, default: -1 },
  working: { type: Boolean, default: false },
  now: { type: Number, default: 0 }
})

const steps = computed(() => props.message.activities || [])
const expanded = ref(props.working)
const expandedStepKey = ref(-1)

const toggleStep = activityIndex => {
  expandedStepKey.value = expandedStepKey.value === activityIndex ? -1 : activityIndex
}

const stepPhaseIcons = { plan: ListChecks, work: Search, verify: ShieldCheck }
const stepPhaseAgents = { plan: '规划', work: '执行', verify: '校验' }
const iconOf = activity => stepPhaseIcons[activity?.phase] || CircleDot
const agentOf = activity => activity?.agent || stepPhaseAgents[activity?.phase] || '步骤'
const detailOf = activity => (activity?.detail && activity.detail !== activity.summary ? activity.detail : '')

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
  if (!duration) return ''
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

const durationOf = activityIndex => {
  const at = steps.value[activityIndex]?.at
  if (!at) return ''
  const previousAt = activityIndex > 0 ? steps.value[activityIndex - 1]?.at : props.message.startedAt
  if (!previousAt) return ''
  return formatDuration(at - previousAt)
}
</script>

<style scoped>
.agent-trace { display: grid; gap: 2px; margin-bottom: 12px; }

.trace-status { display: grid; }

.trace-toggle {
  display: flex;
  width: max-content;
  max-width: 100%;
  align-items: center;
  gap: 8px;
  border: 0;
  border-radius: 8px;
  margin-left: -6px;
  padding: 3px 8px 3px 6px;
  background: transparent;
  color: var(--sk-label-2);
  font-size: 12.5px;
  font-weight: 650;
  text-align: left;
  cursor: pointer;
}

.trace-toggle:hover { background: var(--sk-fill); }
.trace-count { color: var(--sk-label-3); font-weight: 500; }
.trace-toggle.working { color: var(--zwx-primary); }
.trace-toggle.warn { color: #b45309; }
.trace-toggle.error { color: #b91c1c; }

.trace-chevron { color: var(--sk-label-3); transition: transform 0.15s ease; }
.trace-chevron.rotated { transform: rotate(180deg); }

.status-live {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: var(--zwx-primary);
  animation: trace-blink 1.05s ease-in-out infinite;
}

.trace-steps { display: grid; gap: 2px; margin: 0; padding: 0; list-style: none; }

.step-line {
  display: flex;
  align-items: center;
  gap: 6px;
  width: calc(100% + 12px);
  margin-left: -6px;
  border: 0;
  border-radius: 8px;
  padding: 3px 6px;
  background: transparent;
  color: var(--sk-label-3);
  font-size: 12.5px;
  line-height: 1.55;
  text-align: left;
  cursor: default;
}

.step-line.has-detail { cursor: pointer; }
.step-line:hover { background: var(--sk-fill); color: var(--sk-label-2); }
.step-icon { flex: 0 0 auto; }
.step-agent { flex: 0 0 auto; font-weight: 650; }
.step-dot { flex: 0 0 auto; }
.step-summary { min-width: 0; }
.step-duration { flex: 0 0 auto; }

.step-detail {
  max-height: 240px;
  overflow: auto;
  margin: 2px 0 5px;
  padding: 8px 10px;
  border-radius: 9px;
  background: var(--sk-fill);
  color: var(--sk-label-2);
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
</style>
