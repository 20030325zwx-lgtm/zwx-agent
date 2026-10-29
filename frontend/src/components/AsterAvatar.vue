<template>
  <span class="aster-avatar" aria-hidden="true">
    <AsterMark />
  </span>
</template>

<script setup>
import AsterMark from './AsterMark.vue'
</script>

<style scoped>
.aster-avatar {
  display: grid;
  width: 100%;
  height: 100%;
  place-items: center;
  overflow: hidden;
  border-radius: inherit;
  background: var(--wb-mark-bg);
  color: var(--wb-mark-fg);
  box-shadow: inset 0 0 0 1px var(--wb-mark-ring);
}

.aster-avatar :deep(svg) {
  width: 56%;
  height: 56%;
}
</style>
