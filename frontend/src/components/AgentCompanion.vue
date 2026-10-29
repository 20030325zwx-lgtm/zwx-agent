<template>
  <Transition name="companion">
    <div v-if="visible" class="companion-status" :class="`state-${state}`" role="status" aria-live="polite">
      <span class="status-icon">
        <Loader2 v-if="busy" :size="14" class="spin" />
        <Check v-else-if="state === 'done'" :size="14" />
        <AlertCircle v-else-if="state === 'error'" :size="14" />
        <AsterMark v-else class="mark" />
      </span>
      <span class="status-copy">
        <strong>{{ stateLabel }}</strong>
        <span v-if="caption && caption !== stateLabel">{{ caption }}</span>
      </span>
    </div>
  </Transition>
</template>

<script setup>
import { computed } from 'vue'
import { AlertCircle, Check, Loader2 } from 'lucide-vue-next'
import AsterMark from './AsterMark.vue'

const props = defineProps({
  state: { type: String, default: 'idle' },
  caption: { type: String, default: '' },
  name: { type: String, default: '星枢' }
})

const stateLabels = {
  idle: '待机中',
  listening: '在听你说',
  thinking: '正在思考',
  working: '正在执行任务',
  speaking: '正在回答',
  done: '任务完成',
  error: '遇到问题'
}
const stateLabel = computed(() => stateLabels[props.state] || stateLabels.idle)
const busy = computed(() => ['thinking', 'working', 'speaking'].includes(props.state))
const visible = computed(() => props.state !== 'idle')
</script>

<style scoped>
.companion-status {
  position: absolute;
  z-index: 6;
  right: 22px;
  bottom: 118px;
  display: flex;
  max-width: 268px;
  align-items: center;
  gap: 9px;
  border: 1px solid var(--wb-border);
  border-radius: var(--wb-radius);
  padding: 8px 12px 8px 9px;
  background: color-mix(in srgb, var(--wb-surface) 94%, transparent);
  box-shadow: var(--wb-shadow-md);
  backdrop-filter: var(--sk-blur);
  -webkit-backdrop-filter: var(--sk-blur);
  pointer-events: none;
}

.status-icon {
  display: grid;
  width: 26px;
  height: 26px;
  flex: 0 0 26px;
  place-items: center;
  border-radius: 8px;
  background: var(--wb-mark-bg);
  color: var(--wb-mark-fg);
  box-shadow: inset 0 0 0 1px var(--wb-mark-ring);
}

.status-icon .mark { width: 14px; height: 14px; }

.state-thinking .status-icon,
.state-working .status-icon,
.state-speaking .status-icon {
  background: var(--wb-brand-soft);
  color: var(--wb-brand);
  box-shadow: none;
}

.state-done .status-icon {
  background: color-mix(in srgb, var(--wb-success) 14%, transparent);
  color: var(--wb-success);
  box-shadow: none;
}

.state-error .status-icon {
  background: color-mix(in srgb, var(--wb-danger) 12%, transparent);
  color: var(--wb-danger);
  box-shadow: none;
}

.status-copy { display: flex; min-width: 0; flex-direction: column; gap: 1px; }
.status-copy strong { color: var(--wb-text); font-size: 12px; font-weight: 650; line-height: 1.35; }
.status-copy span {
  overflow: hidden;
  color: var(--wb-text-2);
  font-size: 11px;
  line-height: 1.45;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
}

.spin { animation: companion-spin 0.9s linear infinite; }
@keyframes companion-spin { to { transform: rotate(360deg); } }

.companion-enter-active, .companion-leave-active { transition: opacity 0.16s ease, transform 0.16s ease; }
.companion-enter-from, .companion-leave-to { opacity: 0; transform: translateY(4px); }

@media (max-width: 900px) {
  .companion-status { right: 12px; bottom: 112px; max-width: 220px; }
}

@media (max-width: 720px) {
  .companion-status { right: 10px; bottom: 148px; max-width: 190px; padding: 7px 10px 7px 8px; }
  .status-copy strong { font-size: 11.5px; }
  .status-copy span { font-size: 10.5px; }
}

@media (prefers-reduced-motion: reduce) {
  .spin { animation: none; }
}
</style>
