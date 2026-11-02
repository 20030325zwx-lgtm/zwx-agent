<template>
  <nav class="wb-nav" :class="{ collapsed }" aria-label="全局导航">
    <router-link to="/creative" class="wb-nav-brand" title="Aster 创作">
      <div class="wb-nav-logo" aria-hidden="true"><AsterMark /></div>
      <span class="wb-nav-wordmark">Aster</span>
    </router-link>

    <button type="button" class="wb-collapse" :title="collapsed ? '展开导航' : '收起导航'" @click="toggle">
      <PanelLeftClose v-if="!collapsed" :size="17" :stroke-width="1.8" />
      <PanelLeftOpen v-else :size="17" :stroke-width="1.8" />
      <span class="wb-nav-label">收起导航</span>
    </button>

    <div class="wb-nav-items">
      <router-link
        v-for="item in primaryItems"
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

      <p class="wb-nav-group-label">更多</p>
      <router-link
        v-for="item in secondaryItems"
        :key="item.to"
        :to="item.to"
        class="wb-nav-item wb-nav-item-quiet"
        :class="{ active: isActive(item) }"
        :title="item.label"
        :aria-label="item.label"
      >
        <component :is="item.icon" :size="18" :stroke-width="1.9" />
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
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import {
  Compass, MessagesSquare, LibraryBig, Settings, Sun, Moon, MonitorCog,
  PanelLeftClose, PanelLeftOpen, Workflow, Palette, FolderOpen, Images, ListChecks
} from 'lucide-vue-next'
import AsterMark from '../AsterMark.vue'
import { resolvedTheme, themeMode, setThemeMode } from '../../design/apply-tokens'
import { getCurrentUser } from '../../api/auth'

const route = useRoute()

const COLLAPSE_KEY = 'aster.nav.collapsed'
const NARROW_BREAKPOINT = 900
const storedCollapsed = localStorage.getItem(COLLAPSE_KEY) === 'true'
const collapsed = ref(window.innerWidth < NARROW_BREAKPOINT ? true : storedCollapsed)
watch(collapsed, value => {
  try { localStorage.setItem(COLLAPSE_KEY, String(value)) } catch (error) { /* ignore */ }
})
function toggle() { collapsed.value = !collapsed.value }
// 窄屏自动收起导航，避免内容区被固定侧栏挤压；回到宽屏恢复用户偏好
function handleViewportChange() {
  if (window.innerWidth < NARROW_BREAKPOINT) collapsed.value = true
  else collapsed.value = storedCollapsed
}
onMounted(() => window.addEventListener('resize', handleViewportChange))
onBeforeUnmount(() => window.removeEventListener('resize', handleViewportChange))

// 一级导航只挂已上线能力，不做空占位入口：创作主线常驻，系统模块收进「更多」。
const primaryItems = [
  { to: '/creative', label: '创作', icon: Palette, match: ['/creative'] },
  { to: '/creative/works', label: '作品', icon: FolderOpen, match: ['/creative/works'] },
  { to: '/creative/assets', label: '素材', icon: Images, match: ['/creative/assets'] },
  { to: '/creative/tasks', label: '任务', icon: ListChecks, match: ['/creative/tasks'] }
]
const secondaryItems = [
  { to: '/workflows', label: '工作流', icon: Workflow, match: ['/workflows'] },
  { to: '/knowledge-admin', label: '知识库', icon: LibraryBig, match: ['/knowledge-admin'] },
  { to: '/super-agent', label: '智能体', icon: MessagesSquare, match: ['/super-agent'] },
  { to: '/workbench', label: '服务概览', icon: Compass, match: ['/workbench'] }
]
const items = [...primaryItems, ...secondaryItems]

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

// 最长前缀命中：/creative/works、/creative/assets 各自独立高亮，不重复点亮「创作」
const activePath = computed(() => {
  let best = ''
  for (const item of items) {
    for (const path of item.match) {
      if ((route.path === path || route.path.startsWith(`${path}/`)) && path.length > best.length) {
        best = path
      }
    }
  }
  return best
})

function isActive(item) {
  return item.match.includes(activePath.value)
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
  color: inherit;
  text-decoration: none;
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

/* 系统模块分组：视觉降权，只做分隔与弱化，不与创作主线争注意力 */
.wb-nav-group-label {
  margin: 12px 10px 2px;
  color: var(--wb-muted);
  font-size: 11px;
  letter-spacing: 0.04em;
  opacity: 0.75;
  white-space: nowrap;
}

.wb-nav.collapsed .wb-nav-group-label {
  margin: 10px 0 2px;
  text-align: center;
  font-size: 10px;
  overflow: hidden;
}

.wb-nav-item-quiet {
  height: 34px;
  font-size: 12.5px;
  opacity: 0.86;
}

.wb-nav-item-quiet:hover,
.wb-nav-item-quiet.active {
  opacity: 1;
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
