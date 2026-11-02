<template>
  <!-- 占位：Shell 拿到项目类型后会把路由替换成默认分区，避免直接渲染空内容 -->
  <div class="entry"><span class="cr-hint">正在打开项目…</span></div>
</template>

<style scoped>
.entry {
  flex: 1;
  display: grid;
  place-items: center;
  padding: var(--wb-space-8);
}
</style>
