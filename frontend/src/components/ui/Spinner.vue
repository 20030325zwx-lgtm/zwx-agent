<template>
  <svg class="ui-spinner" :style="{ width: size + 'px', height: size + 'px' }" viewBox="0 0 16 16" fill="currentColor" aria-hidden="true" focusable="false">
    <path d="M8 1.2C8.5 5.6 10.4 7.5 14.8 8 10.4 8.5 8.5 10.4 8 14.8 7.5 10.4 5.6 8.5 1.2 8 5.6 7.5 7.5 5.6 8 1.2Z" />
  </svg>
</template>

<script setup>
/** 按钮内联加载：单星闪烁，与产品北斗标识同一语言。 */
defineProps({
  size: { type: Number, default: 13 }
})
</script>

<style scoped>
.ui-spinner {
  display: inline-block;
  color: currentColor;
  vertical-align: -2px;
  flex: 0 0 auto;
  animation: ui-twinkle 1.1s ease-in-out infinite;
}
@keyframes ui-twinkle {
  0%, 100% { opacity: .35; transform: scale(.9); }
  50% { opacity: 1; transform: scale(1); }
}
@media (prefers-reduced-motion: reduce) {
  .ui-spinner { animation: none; opacity: .9; }
}
</style>
