<template>
  <div class="settings-page">
    <header class="settings-head">
      <h1>设置</h1>
      <p>供应商、能力与个性化配置</p>
    </header>
    <div class="settings-body">
      <nav class="settings-catalog" aria-label="设置分类">
        <router-link
          v-for="category in categories"
          :key="category.to"
          :to="category.to"
          class="catalog-item"
          :class="{ active: isActive(category) }"
        >
          <component :is="category.icon" :size="16" :stroke-width="1.9" />
          <span>{{ category.label }}</span>
        </router-link>
      </nav>
      <section class="settings-content">
        <router-view />
      </section>
    </div>
  </div>
</template>

<script setup>
import { useRoute } from 'vue-router'
import { Bot, Blocks, Network, BrainCircuit, ShieldCheck } from 'lucide-vue-next'

const route = useRoute()

// 只列已上线能力，不做空占位入口
const categories = [
  { to: '/settings/model', label: '模型', icon: Bot, match: ['/settings/model'] },
  { to: '/settings/skill', label: 'Skill', icon: Blocks, match: ['/settings/skill'] },
  { to: '/settings/mcp', label: 'MCP', icon: Network, match: ['/settings/mcp'] },
  { to: '/settings/memory', label: '记忆', icon: BrainCircuit, match: ['/settings/memory'] },
  { to: '/settings/permission', label: '权限', icon: ShieldCheck, match: ['/settings/permission'] }
]

function isActive(category) {
  return category.match.some(path => route.path.startsWith(path))
}
</script>

<style scoped>
.settings-page {
  flex: 1;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.settings-head {
  padding: 24px 28px 14px;
}

.settings-head h1 {
  font-size: 20px;
  font-weight: 650;
  letter-spacing: -0.02em;
  color: var(--wb-text);
}

.settings-head p {
  margin-top: 3px;
  font-size: 12.5px;
  color: var(--wb-text-2);
}

.settings-body {
  display: flex;
  flex: 1;
  gap: 18px;
  padding: 0 28px 24px;
  min-height: 0;
}

.settings-catalog {
  display: flex;
  flex: 0 0 168px;
  flex-direction: column;
  gap: 4px;
  padding: 10px;
  border: 1px solid var(--wb-border);
  border-radius: var(--wb-radius-lg);
  background: var(--wb-surface);
  align-self: flex-start;
}

.catalog-item {
  display: flex;
  align-items: center;
  gap: 9px;
  padding: 9px 12px;
  border-radius: 9px;
  color: var(--wb-text-2);
  font-size: 13px;
  transition: background 0.15s, color 0.15s;
}

.catalog-item:hover {
  background: var(--wb-fill);
  color: var(--wb-text);
}

.catalog-item.active {
  background: var(--wb-brand-soft);
  color: var(--wb-brand);
  font-weight: 550;
}

.settings-content {
  flex: 1;
  min-width: 0;
  overflow-y: auto;
  border: 1px solid var(--wb-border);
  border-radius: var(--wb-radius-lg);
  background: var(--wb-surface);
}

@media (max-width: 900px) {
  .settings-body {
    flex-direction: column;
    overflow-y: auto;
  }
  .settings-catalog {
    flex: none;
    flex-direction: row;
    flex-wrap: wrap;
    width: 100%;
  }
}
</style>
