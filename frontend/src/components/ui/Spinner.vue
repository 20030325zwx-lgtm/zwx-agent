<template>
  <span class="ui-spinner" :style="{ width: size + 'px', height: size + 'px' }" aria-hidden="true" />
</template>

<script setup>
/** 按钮内联转圈（微交互批次）：loading 时替代/前置于文字，宽度自适应不跳动。 */
defineProps({
  size: { type: Number, default: 13 }
})
</script>

<style scoped>
.ui-spinner {
  display: inline-block;
  border: 2px solid currentColor;
  border-top-color: transparent;
  border-radius: 50%;
  opacity: 0.85;
  animation: ui-spin 0.7s linear infinite;
  vertical-align: -2px;
  flex: 0 0 auto;
}
@keyframes ui-spin { to { transform: rotate(360deg); } }
</style>
