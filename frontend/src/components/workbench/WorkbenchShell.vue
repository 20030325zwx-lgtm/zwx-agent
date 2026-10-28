<template>
  <div class="wb-shell">
    <GlobalNav />
    <main class="wb-content">
      <router-view />
    </main>
  </div>
</template>

<script setup>
import GlobalNav from './GlobalNav.vue'
</script>

<style scoped>
.wb-shell {
  display: flex;
  height: 100vh;
  overflow: hidden;
  background: var(--wb-bg);
  background-image: var(--wb-bg-accent);
}

.wb-content {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-width: 0;
  min-height: 0;
  overflow: hidden;
}
</style>
