<template>
  <section v-if="steps.length" class="plan-board" aria-label="任务计划">
    <header class="board-head">
      <div>
        <span class="board-kicker">执行计划</span>
        <strong>{{ doneCount }}/{{ steps.length }} 个子任务完成</strong>
      </div>
      <span class="board-roles">{{ roleSummary }}</span>
    </header>
    <div class="plan-grid">
      <article v-for="step in steps" :key="step.index" class="plan-card" :class="step.state">
        <header>
          <span class="step-index">{{ String(step.index + 1).padStart(2, '0') }}</span>
          <span class="role-chip">{{ step.roleLabel }}</span>
          <span class="step-state" aria-hidden="true">
            <Check v-if="step.state === 'done'" :size="13" />
            <Loader2 v-else-if="step.state === 'running'" :size="13" class="spin" />
            <span v-else class="pending-dot"></span>
          </span>
        </header>
        <h3>{{ step.title }}</h3>
        <p v-if="step.detail" class="step-detail">{{ step.detail }}</p>
        <p v-if="step.result" class="step-result"><span>产出</span>{{ step.result }}</p>
        <p v-else-if="step.state === 'running'" class="step-running">正在执行…</p>
      </article>
    </div>
  </section>
</template>

<script setup>
import { computed } from 'vue'
import { Check, Loader2 } from 'lucide-vue-next'

const props = defineProps({
  run: { type: Object, default: null }
})

const plan = computed(() => props.run?.plan || [])
const results = computed(() => props.run?.taskResults || [])
const events = computed(() => props.run?.events || [])
const running = computed(() => props.run?.status === 'RUNNING')
const doneCount = computed(() => steps.value.filter(step => step.state === 'done').length)

const startedIndex = summary => {
  const match = /开始执行（(\d+)\s*\/\s*(\d+)）/.exec(summary || '')
  return match ? Number(match[1]) - 1 : -1
}
const maxStartedIndex = computed(() => events.value.reduce((max, event) => {
  if (event.phase !== 'work') return max
  return Math.max(max, startedIndex(event.summary))
}, -1))

const steps = computed(() => plan.value.map((step, index) => {
  const result = results.value[index] || ''
  const started = maxStartedIndex.value >= index
  const earlierThanCurrent = maxStartedIndex.value > index
  let state = 'pending'
  if (result) state = 'done'
  else if (started && running.value) state = 'running'
  else if (earlierThanCurrent) state = 'done'
  else if (!running.value && props.run?.status === 'COMPLETED') state = 'done'
  return { ...step, index, result: compact(result), state }
}))

const compact = value => {
  const text = (value || '').replace(/\s+/g, ' ').trim()
  return text.length > 180 ? `${text.slice(0, 180)}…` : text
}

const roleSummary = computed(() => {
  const roles = [...new Set(steps.value.map(step => step.roleLabel).filter(Boolean))]
  return roles.join(' · ')
})
</script>

<style scoped>
.plan-board { display: grid; gap: 12px; }

.board-head { display: flex; align-items: flex-end; justify-content: space-between; gap: 12px; }
.board-head > div { display: grid; gap: 3px; }
.board-kicker { color: var(--sk-label-3); font-size: 10.5px; font-weight: 650; letter-spacing: 0.08em; }
.board-head strong { color: var(--sk-label); font-size: 14px; }
.board-roles { color: var(--sk-label-3); font-size: 11px; }

.plan-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(220px, 1fr)); gap: 10px; }

.plan-card {
  display: flex;
  min-width: 0;
  flex-direction: column;
  gap: 7px;
  border: 1px solid var(--sk-separator);
  border-radius: 14px;
  padding: 12px 13px;
  background: #fff;
  box-shadow: 0 4px 14px rgba(37, 73, 133, 0.05);
  transition: border-color 0.2s ease, box-shadow 0.2s ease, transform 0.2s ease;
}

.plan-card.running {
  border-color: var(--zwx-primary-ring);
  box-shadow: 0 6px 20px rgba(37, 99, 235, 0.13);
}

.plan-card.done { background: linear-gradient(180deg, #fff, #fbfdff); }
.plan-card.pending { opacity: 0.82; }

.plan-card header { display: flex; align-items: center; gap: 7px; }
.step-index { color: var(--sk-label-3); font: 10px/1 ui-monospace, "SF Mono", Menlo, monospace; }
.role-chip { border-radius: 999px; padding: 2px 7px; background: var(--zwx-primary-soft); color: var(--zwx-primary); font-size: 10px; font-weight: 650; }
.step-state { display: grid; margin-left: auto; place-items: center; color: var(--zwx-primary); }
.pending-dot { width: 6px; height: 6px; border-radius: 50%; background: var(--sk-separator-strong, #d8e1f0); }
.plan-card.done .step-state { color: #16a34a; }
.spin { animation: plan-spin 1s linear infinite; }

.plan-card h3 { margin: 0; color: var(--sk-label); font-size: 13.5px; font-weight: 700; line-height: 1.45; }
.step-detail { display: -webkit-box; overflow: hidden; margin: 0; color: var(--sk-label-2); font-size: 11.5px; line-height: 1.55; -webkit-box-orient: vertical; -webkit-line-clamp: 2; }
.step-result { display: -webkit-box; overflow: hidden; margin: 2px 0 0; border-top: 1px dashed var(--sk-separator); padding-top: 7px; color: var(--sk-label-2); font-size: 11.5px; line-height: 1.55; -webkit-box-orient: vertical; -webkit-line-clamp: 3; }
.step-result span { margin-right: 5px; color: #15803d; font-weight: 650; }
.step-running { margin: 2px 0 0; color: var(--zwx-primary); font-size: 11.5px; }

@keyframes plan-spin { to { transform: rotate(360deg); } }

@media (prefers-reduced-motion: reduce) { .spin { animation: none; } }

@media (max-width: 720px) {
  .board-head { align-items: flex-start; flex-direction: column; gap: 4px; }
  .plan-grid { grid-template-columns: 1fr; }
}
</style>
