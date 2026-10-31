<template>
  <button
    type="button"
    class="ui-toggle"
    role="switch"
    :aria-checked="modelValue"
    :disabled="disabled"
    @click="$emit('update:modelValue', !modelValue)"
  >
    <span class="tg-track" :class="{ on: modelValue }"><span class="tg-knob" /></span>
  </button>
</template>

<script setup>
/** 滑块开关（微交互批次）：替代语义为开/关的 checkbox（水印、模型启用等）。 */
defineProps({
  modelValue: { type: Boolean, default: false },
  disabled: { type: Boolean, default: false }
})
defineEmits(['update:modelValue'])
</script>

<style scoped>
.ui-toggle { border: 0; background: transparent; padding: 0; cursor: pointer; display: inline-flex; }
.ui-toggle:disabled { opacity: 0.5; cursor: not-allowed; }
.tg-track {
  display: inline-block;
  width: 36px;
  height: 20px;
  border-radius: 999px;
  background: var(--wb-border-strong, #b9bfd2);
  position: relative;
  transition: background 0.18s ease;
}
.tg-track.on { background: var(--wb-brand, #4f6ef7); }
.tg-knob {
  position: absolute;
  top: 2px;
  left: 2px;
  width: 16px;
  height: 16px;
  border-radius: 50%;
  background: #fff;
  box-shadow: 0 1px 3px rgb(0 0 0 / 25%);
  transition: transform 0.18s ease;
}
.tg-track.on .tg-knob { transform: translateX(16px); }
</style>
