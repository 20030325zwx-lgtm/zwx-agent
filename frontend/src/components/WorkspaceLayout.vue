<template>
  <div class="workspace-page">
    <div class="bg-glow" aria-hidden="true"></div>

    <header class="topbar">
      <div class="topbar-inner">
        <button class="brand" type="button" aria-label="Aster 首页" @click="navigate({ name: 'Home' })">
          <span class="brand-mark">A</span><span class="brand-name">Aster</span>
        </button>

        <div class="topbar-actions">
          <nav ref="navRef" class="top-nav" aria-label="工作空间导航">
            <span class="nav-indicator" :style="indicatorStyle" aria-hidden="true"></span>
            <button v-for="item in navItems" :key="item.name" class="nav-item" :class="{ active: isActive(item) }"
              type="button" :aria-current="isActive(item) ? 'page' : undefined" @click="navigate(item)">
              <component :is="item.icon" :size="14" /><span>{{ item.label }}</span>
            </button>
          </nav>
          <span class="topbar-divider" aria-hidden="true"></span>
          <AgentSettingsMenu agent-key="super" default-theme="blue" trigger-label="本机服务设置" />
          <div ref="userMenuRoot" class="user-menu">
            <button class="user-trigger" type="button" :aria-expanded="userMenuOpen" aria-label="账号菜单" @click="userMenuOpen = !userMenuOpen">
              <span class="user-avatar">{{ userInitial }}</span>
            </button>
            <div v-if="userMenuOpen" class="user-popover" role="menu">
              <div class="user-info">
                <span class="user-avatar large">{{ userInitial }}</span>
                <div class="user-copy"><strong>{{ currentUser?.username || '未登录' }}</strong><small>{{ roleLabel }}</small></div>
              </div>
              <button class="logout-item" type="button" role="menuitem" @click="handleLogout"><LogOut :size="15" />退出登录</button>
            </div>
          </div>
        </div>
      </div>
    </header>

    <div class="workspace-content">
      <RouterView v-slot="{ Component, route: currentRoute }">
        <Transition :name="transitionName" mode="out-in">
          <component :is="Component" :key="currentRoute.name || currentRoute.path" />
        </Transition>
      </RouterView>
    </div>
  </div>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Brain, Cpu, Database, LogOut, PlugZap, Settings2, Sparkles } from 'lucide-vue-next'
import AgentSettingsMenu from './AgentSettingsMenu.vue'
import { getCurrentUser, logout } from '../api/auth'

const router = useRouter()
const route = useRoute()
const navRef = ref(null)
const userMenuRoot = ref(null)
const userMenuOpen = ref(false)
const indicator = reactive({ left: 0, width: 0, opacity: 0 })
const transitionName = ref('slide-left')

const navItems = [
  { name: 'Home', label: '智能体', icon: Sparkles },
  { name: 'ModelSettings', label: '模型', icon: Cpu },
  { name: 'SkillSettings', label: 'Skill', icon: Settings2 },
  { name: 'McpSettings', label: 'MCP', icon: PlugZap },
  { name: 'KnowledgeAdmin', label: '知识库', icon: Database },
  { name: 'MemorySettings', label: '记忆', icon: Brain }
]

const activeIndex = computed(() => Math.max(0, navItems.findIndex(item => isActive(item))))
const indicatorStyle = computed(() => ({
  width: `${indicator.width}px`,
  opacity: indicator.opacity,
  transform: `translateX(${indicator.left}px)`
}))

const isActive = item => route.name === item.name
const currentUser = computed(() => getCurrentUser())
const userInitial = computed(() => (currentUser.value?.username || 'U').trim().slice(0, 1).toUpperCase())
const roleLabel = computed(() => ({ ADMIN: '管理员', USER: '成员' })[currentUser.value?.role] || currentUser.value?.role || '成员')

const updateIndicator = () => {
  const nav = navRef.value
  if (!nav) return
  const activeButton = nav.querySelector(`.nav-item:nth-of-type(${activeIndex.value + 1})`)
  if (!activeButton) return
  const navRect = nav.getBoundingClientRect()
  const buttonRect = activeButton.getBoundingClientRect()
  indicator.left = buttonRect.left - navRect.left
  indicator.width = buttonRect.width
  indicator.opacity = 1
}

const navigate = item => {
  if (isActive(item)) {
    if (item.name === 'Home') window.scrollTo({ top: 0, behavior: 'smooth' })
    return
  }
  router.push({ name: item.name })
}

const routeIndex = name => Math.max(0, navItems.findIndex(item => item.name === name))

const closeUserMenu = event => { if (!userMenuRoot.value?.contains(event.target)) userMenuOpen.value = false }
const handleLogout = () => {
  userMenuOpen.value = false
  logout()
  router.replace({ name: 'Login' })
}

let resizeFrame = 0
const handleResize = () => {
  cancelAnimationFrame(resizeFrame)
  resizeFrame = requestAnimationFrame(updateIndicator)
}

watch(() => route.name, (to, from) => {
  transitionName.value = routeIndex(to) >= routeIndex(from) ? 'slide-left' : 'slide-right'
  nextTick(updateIndicator)
  window.scrollTo({ top: 0, behavior: 'auto' })
})

onMounted(() => {
  document.addEventListener('click', closeUserMenu)
  window.addEventListener('resize', handleResize)
  nextTick(updateIndicator)
})

onBeforeUnmount(() => {
  document.removeEventListener('click', closeUserMenu)
  window.removeEventListener('resize', handleResize)
  cancelAnimationFrame(resizeFrame)
})
</script>

<style scoped>
.workspace-page {
  position: relative;
  min-height: 100vh;
  overflow-x: clip;
  background:
    linear-gradient(120deg, rgba(213, 226, 255, 0.92), rgba(247, 251, 255, 0.82) 44%, rgba(222, 236, 255, 0.86));
  color: var(--sk-label);
}

.bg-glow {
  position: fixed;
  inset: 0;
  pointer-events: none;
  background:
    radial-gradient(48rem 32rem at 44% -10rem, rgba(62, 110, 245, 0.26), transparent 68%),
    radial-gradient(36rem 26rem at 100% 26%, rgba(111, 191, 255, 0.18), transparent 67%),
    linear-gradient(125deg, transparent 0 62%, rgba(255, 255, 255, 0.28) 62% 63%, transparent 63%);
}

.topbar { position: sticky; z-index: 40; top: 0; padding: 14px 20px 0; }

.topbar-inner {
  display: flex;
  max-width: 1200px;
  height: 58px;
  margin: 0 auto;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  border: 1px solid rgba(255, 255, 255, 0.65);
  border-radius: 14px;
  padding: 0 10px 0 18px;
  background: rgba(255, 255, 255, 0.76);
  backdrop-filter: var(--sk-blur);
  -webkit-backdrop-filter: var(--sk-blur);
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.04), 0 12px 32px rgba(0, 0, 0, 0.08);
}

.brand { display: flex; align-items: center; gap: 10px; border: 0; background: transparent; color: var(--sk-label); font-size: 15px; font-weight: 700; letter-spacing: -0.01em; }

.brand-mark {
  display: grid;
  width: 32px;
  height: 32px;
  place-items: center;
  border-radius: 8px;
  background: linear-gradient(145deg, #4f8df7, var(--zwx-primary) 58%, #2248bd);
  color: #fff;
  font-size: 15px;
  font-weight: 800;
  box-shadow: 0 4px 12px var(--zwx-primary-ring), inset 0 1px 0 rgba(255, 255, 255, 0.4);
}

.topbar-actions { display: flex; min-width: 0; align-items: center; gap: 8px; }

.top-nav { position: relative; display: flex; align-items: center; gap: 2px; border-radius: 11px; padding: 3px; background: rgba(120, 140, 170, 0.08); }

.nav-indicator {
  position: absolute;
  top: 3px;
  left: 0;
  height: 30px;
  border-radius: 8px;
  background: var(--sk-surface);
  box-shadow: 0 1px 3px rgba(30, 60, 120, 0.12);
  transition: transform 0.32s cubic-bezier(0.32, 0.72, 0, 1), width 0.32s cubic-bezier(0.32, 0.72, 0, 1), opacity 0.2s ease;
}

.nav-item {
  position: relative;
  z-index: 1;
  display: flex;
  height: 30px;
  align-items: center;
  gap: 5px;
  border: 0;
  border-radius: 8px;
  padding: 0 10px;
  background: transparent;
  color: var(--sk-label-2);
  font-size: 11px;
  font-weight: 600;
  white-space: nowrap;
  transition: color 0.18s ease, transform 0.18s ease;
}

.nav-item :deep(svg) { opacity: 0.72; transition: opacity 0.18s ease, transform 0.18s ease; }
.nav-item:hover { color: var(--sk-label); transform: translateY(-1px); }
.nav-item:hover :deep(svg) { opacity: 1; }
.nav-item.active { color: var(--zwx-primary); }
.nav-item.active :deep(svg) { opacity: 1; }

.topbar-divider { width: 1px; height: 22px; background: var(--sk-separator-strong); opacity: 0.6; }

.user-menu { position: relative; display: flex; }
.user-trigger { display: grid; place-items: center; border: 0; padding: 0; background: transparent; border-radius: 50%; }
.user-trigger:hover .user-avatar { filter: brightness(1.08); }
.user-avatar { display: grid; width: 34px; height: 34px; place-items: center; border-radius: 50%; background: linear-gradient(160deg, #98a2b3, #64748b); color: #fff; font-size: 14px; font-weight: 700; box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.35), 0 2px 6px rgba(0, 0, 0, 0.14); }
.user-avatar.large { width: 40px; height: 40px; flex: 0 0 40px; font-size: 16px; }

.user-popover { position: absolute; z-index: 30; top: calc(100% + 12px); right: 0; width: 240px; border: 1px solid var(--sk-separator); border-radius: 16px; padding: 8px; background: var(--sk-material-strong); backdrop-filter: var(--sk-blur); -webkit-backdrop-filter: var(--sk-blur); box-shadow: var(--sk-shadow-pop); transform-origin: top right; animation: user-menu-in 0.18s cubic-bezier(0.32, 0.72, 0, 1); }

@keyframes user-menu-in { from { opacity: 0; transform: scale(0.96) translateY(-4px); } to { opacity: 1; transform: scale(1) translateY(0); } }

.user-info { display: flex; align-items: center; gap: 11px; padding: 8px 8px 12px; border-bottom: 1px solid var(--sk-separator); }
.user-copy { display: grid; min-width: 0; gap: 2px; }
.user-copy strong { overflow: hidden; font-size: 14px; font-weight: 700; letter-spacing: -0.01em; text-overflow: ellipsis; white-space: nowrap; }
.user-copy small { color: var(--sk-label-3); font-size: 11px; }
.logout-item { display: flex; width: 100%; align-items: center; gap: 8px; margin-top: 6px; border: 0; border-radius: 10px; padding: 10px; background: transparent; color: var(--sk-red); font-size: 13px; font-weight: 600; text-align: left; }
.logout-item:hover { background: rgba(255, 59, 48, 0.1); }

.workspace-content { position: relative; }

.slide-left-enter-active, .slide-left-leave-active,
.slide-right-enter-active, .slide-right-leave-active {
  transition: opacity 0.16s ease, transform 0.22s cubic-bezier(0.32, 0.72, 0, 1), filter 0.22s ease;
}

.slide-left-enter-from { opacity: 0; transform: translateX(22px); filter: blur(2px); }
.slide-left-leave-to { opacity: 0; transform: translateX(-18px); filter: blur(2px); }
.slide-right-enter-from { opacity: 0; transform: translateX(-22px); filter: blur(2px); }
.slide-right-leave-to { opacity: 0; transform: translateX(18px); filter: blur(2px); }

@media (prefers-reduced-motion: reduce) {
  .nav-indicator { transition: none; }
  .slide-left-enter-active, .slide-left-leave-active,
  .slide-right-enter-active, .slide-right-leave-active { transition: none; }
}

@media (max-width: 820px) {
  .nav-item span { display: none; }
  .nav-item { width: 32px; justify-content: center; padding: 0; }
  .topbar-divider { display: none; }
}

@media (max-width: 720px) {
  .topbar { top: 0; padding: 10px 12px 0; }
  .topbar-inner { height: 54px; padding: 0 8px 0 14px; gap: 8px; }
  .brand-name { display: none; }
  .top-nav { gap: 0; padding: 2px; }
  .nav-indicator { top: 2px; height: 30px; }
}
</style>
