<template>
  <ol class="run-phases" :class="{ degraded: simple }" aria-label="任务阶段">
    <li v-for="(stage, index) in stages" :key="stage.key" class="phase" :class="stage.state">
      <span class="phase-dot" aria-hidden="true">
        <Check v-if="stage.state === 'done'" :size="11" />
        <span v-else-if="stage.state === 'active'" class="pulse"></span>
      </span>
      <span class="phase-label">{{ stage.label }}</span>
      <span v-if="index < stages.length - 1" class="phase-line" aria-hidden="true"></span>
    </li>
    <li v-if="verificationLabel" class="verification" :class="verificationTone">{{ verificationLabel }}</li>
  </ol>
</template>

<script setup>
import { computed } from 'vue'
import { Check } from 'lucide-vue-next'

const props = defineProps({
  run: { type: Object, default: null },
  answer: { type: String, default: '' },
  simple: { type: Boolean, default: false }
})

const plan = computed(() => props.run?.plan || [])
const events = computed(() => props.run?.events || [])
const taskResults = computed(() => props.run?.taskResults || [])
const running = computed(() => props.run?.status === 'RUNNING')
const verification = computed(() => props.run?.verification || {})
const answer = computed(() => props.answer || '')

const stages = computed(() => {
  const planDone = plan.value.length > 0
  const workStarted = events.value.some(event => event.phase === 'work')
  const workFinished = planDone
    && (taskResults.value.length >= plan.value.length || !!verification.value.status)
  const verifyDone = !!verification.value.status
  const deliverDone = !!answer.value

  return [
    { key: 'plan', label: '规划', state: planDone ? 'done' : running.value ? 'active' : 'pending' },
    { key: 'work', label: '执行', state: workStarted ? (workFinished ? 'done' : 'active') : planDone && running.value ? 'active' : 'pending' },
    { key: 'verify', label: '验收', state: verifyDone ? 'done' : workFinished && running.value ? 'active' : 'pending' },
    { key: 'deliver', label: '交付', state: deliverDone ? 'done' : verifyDone && running.value ? 'active' : 'pending' }
  ]
})

const verificationLabel = computed(() => {
  const status = verification.value.status
  if (!props.run || !status) return ''
  if (status === 'pass') return '已验收'
  if (status === 'unverified') return '未验收'
  if (status === 'revise') return '已要求返工'
  return status
})

const verificationTone = computed(() => ({
  pass: 'tone-pass',
  unverified: 'tone-warn',
  revise: 'tone-warn'
}[verification.value.status] || 'tone-neutral'))
</script>

<style scoped>
.run-phases {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 4px;
  margin: 0;
  padding: 0;
  list-style: none;
}

.phase { position: relative; display: inline-flex; align-items: center; gap: 6px; color: var(--sk-label-3); font-size: 11.5px; }

.phase-dot {
  display: grid;
  width: 18px;
  height: 18px;
  place-items: center;
  border: 1.5px solid var(--sk-separator-strong, #d8e1f0);
  border-radius: 50%;
  background: #fff;
  color: #fff;
}

.phase.done { color: var(--zwx-primary); }
.phase.done .phase-dot { border-color: var(--zwx-primary); background: var(--zwx-primary); }
.phase.active { color: var(--sk-label); font-weight: 650; }
.phase.active .phase-dot { border-color: var(--zwx-primary); background: var(--zwx-primary-soft); }

.pulse { width: 6px; height: 6px; border-radius: 50%; background: var(--zwx-primary); animation: phase-pulse 1.2s ease-in-out infinite; }

.phase-line { width: 26px; height: 1.5px; margin: 0 4px; background: var(--sk-separator); }
.phase.done .phase-line { background: color-mix(in srgb, var(--zwx-primary) 42%, transparent); }

.verification {
  margin-left: auto;
  border-radius: 999px;
  padding: 3px 9px;
  font-size: 10.5px;
  font-weight: 650;
}

.tone-pass { background: rgba(34, 197, 94, 0.12); color: #15803d; }
.tone-warn { background: rgba(245, 158, 11, 0.14); color: #b45309; }
.tone-neutral { background: var(--sk-fill); color: var(--sk-label-2); }

.degraded .phase-line { width: 18px; }

@keyframes phase-pulse { 0%, 100% { opacity: 0.35; transform: scale(0.8); } 50% { opacity: 1; transform: scale(1); } }

@media (prefers-reduced-motion: reduce) { .pulse { animation: none; opacity: 1; } }

@media (max-width: 720px) {
  .phase-line { width: 12px; }
  .verification { margin-left: 0; }
}
</style>
