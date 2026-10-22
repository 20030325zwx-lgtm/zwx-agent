<template>
  <section v-if="events.length" class="run-timeline" aria-label="执行过程">
    <header class="timeline-head">
      <div>
        <span class="timeline-kicker">执行过程</span>
        <strong>{{ events.length }} 条语义事件</strong>
      </div>
      <button type="button" class="toggle" :aria-expanded="expanded" @click="expanded = !expanded">
        {{ expanded ? '收起' : '展开' }}
      </button>
    </header>
    <div v-show="expanded" class="timeline-list">
      <article v-for="(event, index) in events" :key="`${event.phase}-${index}-${event.summary}`" class="timeline-item" :class="event.phase">
        <span class="timeline-dot" aria-hidden="true"></span>
        <div class="timeline-copy">
          <div class="timeline-meta">
            <span class="phase-badge">{{ phaseLabel(event.phase) }}</span>
            <span v-if="event.agent" class="agent">{{ event.agent }}</span>
          </div>
          <p>{{ event.summary }}</p>
        </div>
      </article>
    </div>
  </section>
</template>

<script setup>
import { computed, ref, watch } from 'vue'

const props = defineProps({
  run: { type: Object, default: null }
})

const events = computed(() => props.run?.events || [])
const running = computed(() => props.run?.status === 'RUNNING')
const expanded = ref(false)

watch(() => props.run?.status, value => { expanded.value = value === 'RUNNING' }, { immediate: true })
watch(() => props.run?.id, () => { expanded.value = running.value })

const phaseLabel = phase => ({ plan: '规划', work: '执行', verify: '验收' }[phase] || phase || '过程')
</script>

<style scoped>
.run-timeline { display: grid; gap: 10px; }

.timeline-head { display: flex; align-items: flex-end; justify-content: space-between; gap: 12px; }
.timeline-head > div { display: grid; gap: 3px; }
.timeline-kicker { color: var(--sk-label-3); font-size: 10.5px; font-weight: 650; letter-spacing: 0.08em; }
.timeline-head strong { color: var(--sk-label); font-size: 13.5px; }

.toggle {
  border: 1px solid var(--sk-separator);
  border-radius: 999px;
  padding: 4px 11px;
  background: #fff;
  color: var(--sk-label-2);
  font-size: 11px;
  cursor: pointer;
}
.toggle:hover { border-color: var(--zwx-primary-ring); color: var(--zwx-primary); }

.timeline-list {
  display: grid;
  max-height: 320px;
  overflow-y: auto;
  border: 1px solid var(--sk-separator);
  border-radius: 14px;
  padding: 6px 14px;
  background: rgba(255, 255, 255, 0.78);
}

.timeline-item { position: relative; display: flex; gap: 11px; padding: 10px 0; }
.timeline-item + .timeline-item { border-top: 1px dashed var(--sk-separator); }

.timeline-dot {
  width: 8px;
  height: 8px;
  flex: 0 0 8px;
  margin-top: 8px;
  border-radius: 50%;
  background: var(--zwx-primary);
  box-shadow: 0 0 0 3px var(--zwx-primary-soft);
}

.timeline-item.verify .timeline-dot { background: #16a34a; box-shadow: 0 0 0 3px rgba(34, 197, 94, 0.13); }
.timeline-item.plan .timeline-dot { background: #6366f1; box-shadow: 0 0 0 3px rgba(99, 102, 241, 0.12); }

.timeline-copy { min-width: 0; }
.timeline-meta { display: flex; align-items: center; gap: 7px; margin-bottom: 3px; }
.phase-badge { border-radius: 5px; padding: 1px 6px; background: var(--sk-fill); color: var(--sk-label-2); font-size: 9.5px; font-weight: 650; }
.agent { color: var(--sk-label-3); font-size: 10.5px; }
.timeline-copy p { margin: 0; color: var(--sk-label); font-size: 12px; line-height: 1.6; word-break: break-word; }

@media (max-width: 720px) {
  .timeline-list { max-height: 260px; padding: 4px 11px; }
}
</style>
