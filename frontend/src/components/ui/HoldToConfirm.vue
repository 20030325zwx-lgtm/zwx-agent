<template>
  <button
    type="button"
    class="hold-btn"
    :class="[`hold-${tone}`, { holding: progress > 0 }]"
    :disabled="disabled"
    :aria-label="ariaLabel || label"
    @pointerdown.prevent="start"
    @pointerup="cancel"
    @pointercancel="cancel"
    @pointerleave="cancel"
    @keydown.enter.prevent="fire"
    @keydown.space.prevent="fire"
    @contextmenu.prevent
  >
    <span class="hold-fill" :style="{ width: (progress * 100) + '%' }" />
    <span class="hold-label">{{ progress > 0 ? '松开取消…' : label }}</span>
  </button>
</template>

<script setup>
import { onBeforeUnmount, ref } from 'vue'

/**
 * 长按确认（微交互批次）：按住进度走满才触发 confirm——用于涉费/不可逆操作，
 * 替代 window.confirm 原生弹窗。键盘用户按 Enter/Space 直接确认（长按是鼠标防误触手段）。
 */
const props = defineProps({
  label: { type: String, required: true },
  ariaLabel: { type: String, default: '' },
  tone: { type: String, default: 'danger' }, // danger | primary
  duration: { type: Number, default: 1500 },
  disabled: { type: Boolean, default: false }
})
const emit = defineEmits(['confirm'])

const progress = ref(0)
let rafId = 0
let startedAt = 0

function start() {
  if (props.disabled || progress.value > 0) return
  startedAt = performance.now()
  const tick = now => {
    const value = Math.min(1, (now - startedAt) / props.duration)
    progress.value = value
    if (value >= 1) {
      fire()
      return
    }
    rafId = requestAnimationFrame(tick)
  }
  rafId = requestAnimationFrame(tick)
}

function cancel() {
  if (progress.value <= 0 || progress.value >= 1) return
  cancelAnimationFrame(rafId)
  progress.value = 0
}

function fire() {
  cancelAnimationFrame(rafId)
  progress.value = 0
  emit('confirm')
}

onBeforeUnmount(() => cancelAnimationFrame(rafId))
</script>

<style scoped>
.hold-btn {
  position: relative;
  overflow: hidden;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  padding: 6px 14px;
  border: 1px solid var(--wb-border, #d7dbe7);
  border-radius: 9px;
  background: var(--wb-surface, #fff);
  color: var(--wb-text-2, #5b6472);
  font-size: 12.5px;
  cursor: pointer;
  user-select: none;
  touch-action: none;
}
.hold-btn:disabled { opacity: 0.55; cursor: not-allowed; }
.hold-danger { border-color: rgba(214, 69, 69, 0.4); color: var(--wb-danger, #d64545); }
.hold-danger .hold-fill { background: rgba(214, 69, 69, 0.16); }
.hold-danger:hover { border-color: var(--wb-danger, #d64545); }
.hold-primary { border-color: rgba(79, 110, 247, 0.4); color: var(--wb-brand, #4f6ef7); }
.hold-primary .hold-fill { background: rgba(79, 110, 247, 0.14); }
.hold-primary:hover { border-color: var(--wb-brand, #4f6ef7); }
.hold-fill {
  position: absolute;
  left: 0;
  top: 0;
  bottom: 0;
  width: 0;
  transition: width 60ms linear;
  pointer-events: none;
}
.hold-label { position: relative; white-space: nowrap; }
.hold-btn.holding { border-style: solid; }
</style>
