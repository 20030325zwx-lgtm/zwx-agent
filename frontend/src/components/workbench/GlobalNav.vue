<template>
  <nav class="wb-nav" :class="{ collapsed }" aria-label="全局导航">
    <div class="wb-nav-brand">
      <div class="wb-nav-logo" aria-hidden="true"><AsterMark /></div>
      <span class="wb-nav-wordmark">Aster</span>
    </div>

    <button type="button" class="wb-collapse" :title="collapsed ? '展开导航' : '收起导航'" @click="toggle">
      <PanelLeftClose v-if="!collapsed" :size="17" :stroke-width="1.8" />
      <PanelLeftOpen v-else :size="17" :stroke-width="1.8" />
      <span class="wb-nav-label">收起导航</span>
    </button>

    <div class="wb-nav-items">
      <router-link
        v-for="item in items"
        :key="item.to"
        :to="item.to"
        class="wb-nav-item"
        :class="{ active: isActive(item) }"
        :title="item.label"
        :aria-label="item.label"
      >
        <component :is="item.icon" :size="19" :stroke-width="1.9" />
        <span class="wb-nav-label">{{ item.label }}</span>
      </router-link>
    </div>

    <div class="wb-nav-bottom">
      <button
        class="wb-nav-item"
        type="button"
        :title="themeTitle"
        @click="cycleTheme"
      >
        <component :is="themeIcon" :size="18" :stroke-width="1.9" />
        <span class="wb-nav-label">{{ themeShort }}</span>
      </button>
      <router-link to="/settings/model" class="wb-nav-item" title="设置" aria-label="设置">
        <Settings :size="19" :stroke-width="1.9" />
        <span class="wb-nav-label">设置</span>
      </router-link>
      <div class="wb-nav-user" :title="userName">
        <span class="wb-nav-user-badge">{{ userInitial }}</span>
        <span class="wb-nav-label">{{ userName }}</span>
      </div>
    </div>
  </nav>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import {
  Compass, MessagesSquare, LibraryBig, Settings, Sun, Moon, MonitorCog,
  PanelLeftClose, PanelLeftOpen, Workflow
} from 'lucide-vue-next'
import AsterMark from '../AsterMark.vue'
import { resolvedTheme, themeMode, setThemeMode } from '../../design/apply-tokens'
import { getCurrentUser } from '../../api/auth'

const route = useRoute()

const COLLAPSE_KEY = 'aster.nav.collapsed'
const collapsed = ref(localStorage.getItem(COLLAPSE_KEY) === 'true')
watch(collapsed, value => {
  try { localStorage.setItem(COLLAPSE_KEY, String(value)) } catch (error) { /* ignore */ }
})
function toggle() { collapsed.value = !collapsed.value }

// 只挂已上线能力，不做空占位入口
const items = [
  { to: '/workbench', label: '工作台', icon: Compass, match: ['/workbench'] },
  { to: '/super-agent', label: '任务', icon: MessagesSquare, match: ['/super-agent'] },
  { to: '/workflows', label: '工作流', icon: Workflow, match: ['/workflows'] },
  { to: '/knowledge-admin', label: '知识库', icon: LibraryBig, match: ['/knowledge-admin'] }
]

const user = computed(() => getCurrentUser() || {})
const userName = computed(() => user.value?.username || '本机用户')
const userInitial = computed(() => (userName.value || 'A').slice(0, 1).toUpperCase())

const themeIcon = computed(() => {
  if (themeMode.value === 'system') return MonitorCog
  return resolvedTheme.value === 'dark' ? Moon : Sun
})
const themeShort = computed(() => ({ light: '浅色', dark: '深色', system: '主题' }[themeMode.value]))
const themeTitle = computed(() => {
  const label = { light: '浅色', dark: '深色', system: '跟随系统' }[themeMode.value]
  return `主题：${label}（点击切换）`
})

function isActive(item) {
  return item.match.some(path => route.path === path || route.path.startsWith(`${path}/`))
}

function cycleTheme() {
  setThemeMode(resolvedTheme.value === 'dark' ? 'light' : 'dark')
}
</script>

<style scoped>
.wb-nav {
  position: relative;
  z-index: 30;
  display: flex;
  flex-direction: column;
  width: 208px;
  flex: 0 0 208px;
  padding: 14px 10px 12px;
  gap: 8px;
  background: var(--wb-rail);
  border-right: 1px solid var(--wb-border);
  transition: width 0.18s ease, flex-basis 0.18s ease;
  overflow: hidden;
}

.wb-nav.collapsed {
  width: 64px;
  flex-basis: 64px;
}

.wb-nav-brand {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 2px 4px 10px;
  white-space: nowrap;
}

.wb-nav-logo {
  display: grid;
  place-items: center;
  width: 36px;
  height: 36px;
  flex: 0 0 36px;
  border-radius: 11px;
  background: var(--wb-mark-bg);
  color: var(--wb-mark-fg);
  box-shadow: inset 0 0 0 1px var(--wb-mark-ring);
  user-select: none;
}

.wb-nav-logo svg { width: 20px; height: 20px; }

.wb-nav-wordmark {
  color: var(--wb-text);
  font-size: 16px;
  font-weight: 650;
  letter-spacing: -0.01em;
}

.wb-nav.collapsed .wb-nav-brand {
  justify-content: center;
  padding-left: 0;
  padding-right: 0;
}

.wb-nav.collapsed .wb-nav-wordmark {
  display: none;
}

.wb-collapse {
  display: flex;
  align-items: center;
  gap: 10px;
  margin: 0 2px 6px;
  padding: 7px 10px;
  border: 0;
  border-radius: 9px;
  background: transparent;
  color: var(--wb-muted);
  font-size: 12.5px;
  white-space: nowrap;
  transition: background 0.15s, color 0.15s;
}

.wb-collapse:hover {
  background: var(--wb-fill);
  color: var(--wb-text);
}

.wb-nav.collapsed .wb-collapse {
  justify-content: center;
  padding: 7px 0;
}

.wb-nav-items {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 4px;
  padding-top: 2px;
  overflow-y: auto;
  overflow-x: hidden;
}

.wb-nav-bottom {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.wb-nav-item {
  position: relative;
  display: flex;
  align-items: center;
  gap: 10px;
  height: 38px;
  padding: 0 10px;
  border: 0;
  border-radius: 10px;
  background: transparent;
  color: var(--wb-muted);
  font-size: 13px;
  white-space: nowrap;
  transition: color 0.15s, background 0.15s;
}

.wb-nav.collapsed .wb-nav-item {
  justify-content: center;
  padding: 0;
}

.wb-nav-item:hover {
  background: var(--wb-fill);
  color: var(--wb-text);
}

.wb-nav-item.active {
  background: var(--wb-brand-soft);
  color: var(--wb-brand);
  font-weight: 550;
}

.wb-nav-item.active::before {
  content: '';
  position: absolute;
  left: -10px;
  top: 9px;
  bottom: 9px;
  width: 3px;
  border-radius: 3px;
  background: var(--wb-brand);
}

.wb-nav.collapsed .wb-nav-item.active::before {
  left: -8px;
}

.wb-nav-label {
  overflow: hidden;
  text-overflow: ellipsis;
  opacity: 1;
  transition: opacity 0.12s;
}

.wb-nav.collapsed .wb-nav-label {
  display: none;
}

.wb-nav-user {
  display: flex;
  align-items: center;
  gap: 10px;
  height: 38px;
  margin-top: 4px;
  padding: 0 10px;
  border-radius: 10px;
  background: var(--wb-fill);
  white-space: nowrap;
}

.wb-nav.collapsed .wb-nav-user {
  justify-content: center;
  padding: 0;
}

.wb-nav-user-badge {
  display: grid;
  place-items: center;
  width: 24px;
  height: 24px;
  flex: 0 0 24px;
  border-radius: 50%;
  background: var(--wb-brand-gradient, linear-gradient(135deg, #2e6be6, #22c3be));
  color: #fff;
  font-size: 12px;
  font-weight: 600;
}

.wb-nav.collapsed .wb-nav-user .wb-nav-label {
  display: none;
}

/* 折叠态悬浮提示：折叠时隐藏文字，悬停显示原生 title 兜底 */
.wb-nav.collapsed .wb-nav-item:hover {
  background: var(--wb-fill);
}
</style>
