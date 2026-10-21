<template>
  <RouterLink to="/" class="home-button" aria-label="返回智能体主页">
    <span class="home-icon"><House :size="14" /></span>
    <span class="home-label">返回主页</span>
  </RouterLink>
</template>

<script setup>
import { House } from 'lucide-vue-next'
</script>

<style scoped>
.home-button {
  display: inline-flex;
  height: 34px;
  flex: 0 0 auto;
  align-items: center;
  gap: 7px;
  border: 1px solid var(--sk-separator);
  border-radius: 999px;
  padding: 0 13px 0 6px;
  background: var(--sk-surface);
  color: var(--sk-label-2);
  font-size: 12px;
  font-weight: 600;
  text-decoration: none;
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.04);
  transition: border-color 0.16s ease, background-color 0.16s ease, color 0.16s ease, transform 0.16s ease;
}

.home-button:hover {
  border-color: var(--zwx-primary-ring);
  background: var(--zwx-primary-soft);
  color: var(--zwx-primary);
  transform: translateX(-1px);
}

.home-icon {
  display: grid;
  width: 24px;
  height: 24px;
  place-items: center;
  border-radius: 50%;
  background: var(--sk-fill);
  color: var(--zwx-primary);
}

.home-button:hover .home-icon { background: var(--sk-surface); }

@media (max-width: 720px) {
  .home-button { padding-right: 9px; }
  .home-label { display: none; }
}
</style>
