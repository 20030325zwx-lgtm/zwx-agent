<template>
  <nav class="wb-nav" aria-label="全局导航">
    <div class="wb-nav-logo" title="Aster 工作台">✦</div>

    <div class="wb-nav-items">
      <router-link
        v-for="item in items"
        :key="item.to"
        :to="item.to"
        class="wb-nav-item"
        :class="{ active: isActive(item) }"
      >
        <component :is="item.icon" :size="19" :stroke-width="1.9" />
        <span class="wb-nav-tip">{{ item.label }}</span>
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
        <span class="wb-nav-tip">{{ themeTitle }}</span>
      </button>
      <router-link to="/model-settings" class="wb-nav-item" title="设置">
        <Settings :size="19" :stroke-width="1.9" />
        <span class="wb-nav-tip">设置</span>
      </router-link>
      <div class="wb-nav-user" :title="userName">
        {{ userInitial }}
      </div>
    </div>
  </nav>
</template>

<script setup>
import { computed } from 'vue'
import { useRoute } from 'vue-router'
import { Compass, MessagesSquare, LibraryBig, Settings, Sun, Moon, MonitorCog } from 'lucide-vue-next'
import { resolvedTheme, themeMode, setThemeMode } from '../../design/apply-tokens'
import { getCurrentUser } from '../../api/auth'

const route = useRoute()

// 只挂已上线能力，不做空占位入口
const items = [
  { to: '/workbench', label: '工作台', icon: Compass, match: ['/workbench'] },
  { to: '/super-agent', label: '任务', icon: MessagesSquare, match: ['/super-agent'] },
  { to: '/knowledge-admin', label: '知识库', icon: LibraryBig, match: ['/knowledge-admin'] }
]

const user = computed(() => getCurrentUser() || {})
const userName = computed(() => user.value?.username || '本机用户')
const userInitial = computed(() => (userName.value || 'A').slice(0, 1).toUpperCase())

const themeIcon = computed(() => {
  if (themeMode.value === 'system') return MonitorCog
  return resolvedTheme.value === 'dark' ? Moon : Sun
})
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
  align-items: center;
  width: 60px;
  flex: 0 0 60px;
  padding: 14px 0 12px;
  gap: 10px;
  background: var(--wb-rail);
  border-right: 1px solid var(--wb-border);
}

.wb-nav-logo {
  display: grid;
  place-items: center;
  width: 36px;
  height: 36px;
  margin-bottom: 8px;
  border-radius: 11px;
  background: var(--wb-brand-soft);
  color: var(--wb-brand);
  font-size: 17px;
  user-select: none;
}

.wb-nav-items {
  display: flex;
  flex: 1;
  flex-direction: column;
  align-items: center;
  gap: 6px;
  padding-top: 6px;
}

.wb-nav-bottom {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
}

.wb-nav-item {
  position: relative;
  display: grid;
  place-items: center;
  width: 38px;
  height: 38px;
  border: 0;
  border-radius: 11px;
  background: transparent;
  color: var(--wb-muted);
  transition: color 0.15s, background 0.15s;
}

.wb-nav-item:hover {
  background: var(--wb-fill);
  color: var(--wb-text);
}

.wb-nav-item.active {
  background: var(--wb-brand-soft);
  color: var(--wb-brand);
}

.wb-nav-item.active::before {
  content: '';
  position: absolute;
  left: -9px;
  top: 9px;
  bottom: 9px;
  width: 3px;
  border-radius: 3px;
  background: var(--wb-brand);
}

.wb-nav-tip {
  position: absolute;
  left: calc(100% + 10px);
  top: 50%;
  transform: translateY(-50%);
  padding: 5px 9px;
  border-radius: 8px;
  background: var(--wb-raise);
  border: 1px solid var(--wb-border);
  box-shadow: var(--wb-shadow-md);
  color: var(--wb-text);
  font-size: 12px;
  white-space: nowrap;
  opacity: 0;
  pointer-events: none;
  transition: opacity 0.12s;
}

.wb-nav-item:hover .wb-nav-tip,
.wb-nav-item:focus-visible .wb-nav-tip {
  opacity: 1;
}

.wb-nav-user {
  display: grid;
  place-items: center;
  width: 32px;
  height: 32px;
  border-radius: 50%;
  background: linear-gradient(135deg, var(--wb-brand), #c86dd7);
  color: var(--wb-brand-contrast);
  font-size: 13px;
  font-weight: 600;
  user-select: none;
}
</style>
