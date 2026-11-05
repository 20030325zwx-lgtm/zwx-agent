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

    <!-- 生成活动（redesign §3.1）：全局顶部入口，执行中显示数量，异常显示可操作提示 -->
    <button
      type="button"
      class="wb-activity"
      :class="{ 'has-attention': attentionCount > 0 }"
      :title="activityTitle"
      @click="activityOpen = true"
    >
      <Activity :size="17" :stroke-width="1.9" />
      <span class="wb-nav-label">生成活动</span>
      <em v-if="activeCount > 0" class="wb-activity-count">{{ activeCount }}</em>
      <em v-else-if="attentionCount > 0" class="wb-activity-dot" aria-label="有待处理事件" />
    </button>

    <ActivityDrawer v-if="activityOpen" @close="activityOpen = false" />

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
      <router-link to="/settings/model" class="wb-nav-item" title="模型设置" aria-label="模型设置">
        <Settings :size="19" :stroke-width="1.9" />
        <span class="wb-nav-label">模型设置</span>
      </router-link>
      <router-link
        v-if="isAdmin"
        to="/users"
        class="wb-nav-item"
        title="用户管理"
        aria-label="用户管理"
      >
        <Users :size="19" :stroke-width="1.9" />
        <span class="wb-nav-label">用户管理</span>
      </router-link>
      <button
        ref="userTrigger"
        type="button"
        class="wb-nav-item wb-nav-user"
        :title="userName"
        :aria-expanded="userMenuOpen"
        aria-haspopup="menu"
        aria-label="账号菜单"
        @click.stop="toggleUserMenu"
      >
        <span class="wb-nav-user-badge">{{ userInitial }}</span>
        <span class="wb-nav-label">{{ userName }}</span>
      </button>
    </div>

    <!-- 账号菜单：个人信息 + 退出登录；固定定位，避免被导航栏 overflow 裁剪 -->
    <Teleport to="body">
      <div
        v-if="userMenuOpen"
        ref="userMenu"
        class="wb-user-popover"
        role="menu"
        :style="userMenuStyle"
      >
        <p class="wb-user-section">个人信息</p>
        <div class="wb-user-card">
          <span class="wb-nav-user-badge large">{{ userInitial }}</span>
          <div class="wb-user-copy">
            <strong>{{ userName }}</strong>
            <small>{{ roleLabel }}</small>
          </div>
        </div>
        <dl class="wb-user-meta">
          <div><dt>账号</dt><dd>{{ userName }}</dd></div>
          <div><dt>角色</dt><dd>{{ roleLabel }}</dd></div>
        </dl>
        <button class="wb-user-logout" type="button" role="menuitem" @click="handleLogout">
          <LogOut :size="15" :stroke-width="1.9" />
          <span>退出登录</span>
        </button>
      </div>
    </Teleport>
  </nav>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  Compass, MessagesSquare, LibraryBig, Settings, Sun, Moon, MonitorCog, LogOut,
  PanelLeftClose, PanelLeftOpen, Workflow, Palette, FolderOpen, Images, Sparkles,
  Activity, Users
} from 'lucide-vue-next'
import AsterMark from '../AsterMark.vue'
import ActivityDrawer from '../creative/ActivityDrawer.vue'
import { resolvedTheme, themeMode, setThemeMode } from '../../design/apply-tokens'
import { useCreativeActivity } from '../../views/creative/activity'
import { getCurrentUser, logout } from '../../api/auth'

const route = useRoute()
const router = useRouter()
const { activeCount, attentionCount } = useCreativeActivity()
const activityOpen = ref(false)
const activityTitle = computed(() => (
  attentionCount.value > 0
    ? `生成活动：${attentionCount.value} 项需要处理`
    : activeCount.value > 0 ? `生成活动：${activeCount.value} 项进行中` : '生成活动'
))

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
onMounted(() => {
  window.addEventListener('resize', handleViewportChange)
  document.addEventListener('click', closeUserMenu)
  document.addEventListener('keydown', handleKeydown)
})
onBeforeUnmount(() => {
  window.removeEventListener('resize', handleViewportChange)
  document.removeEventListener('click', closeUserMenu)
  document.removeEventListener('keydown', handleKeydown)
})

// 一级导航（redesign §3.1）：创作 / 我的创作 / 素材库 / 技能 4 项；
// 任务页退为「全部生成记录」深层入口（活动抽屉可直达），系统模块收进「更多」。
const primaryItems = [
  { to: '/creative', label: '创作', icon: Palette, match: ['/creative', '/creative/projects'] },
  { to: '/creative/library', label: '我的创作', icon: FolderOpen, match: ['/creative/library', '/creative/works'] },
  { to: '/creative/assets', label: '素材库', icon: Images, match: ['/creative/assets'] },
  { to: '/creative/skills', label: '技能', icon: Sparkles, match: ['/creative/skills'] }
]
const secondaryItems = [
  { to: '/workflows', label: '自动化', icon: Workflow, match: ['/workflows'] },
  { to: '/knowledge-admin', label: '知识库', icon: LibraryBig, match: ['/knowledge-admin'] },
  { to: '/super-agent', label: '智能体', icon: MessagesSquare, match: ['/super-agent'] },
  // 评测中台（/eval/）与数据中台（/data/）是独立服务页面，不进入主站菜单，仅支持路由直达
  { to: '/workbench', label: '服务概览', icon: Compass, match: ['/workbench'] }
]
const items = [...primaryItems, ...secondaryItems]

const user = computed(() => getCurrentUser() || {})
const userName = computed(() => user.value?.username || '本机用户')
const userInitial = computed(() => (userName.value || 'A').slice(0, 1).toUpperCase())
const isAdmin = computed(() => user.value?.role === 'ADMIN')
const roleLabel = computed(() => ({
  ADMIN: '管理员',
  REVIEWER: '审核员',
  WORKFLOW: '工作流账号',
  USER: '成员'
}[user.value?.role] || '成员'))

// 账号菜单：fixed 定位到头像上方，折叠态也不会被导航栏裁剪
const userTrigger = ref(null)
const userMenu = ref(null)
const userMenuOpen = ref(false)
const userMenuStyle = reactive({ left: '8px', bottom: '8px' })

function toggleUserMenu() {
  if (userMenuOpen.value) {
    userMenuOpen.value = false
    return
  }
  const rect = userTrigger.value?.getBoundingClientRect()
  if (!rect) return
  const width = 240
  userMenuStyle.left = `${Math.max(8, Math.min(rect.left, window.innerWidth - width - 8))}px`
  userMenuStyle.bottom = `${Math.max(8, window.innerHeight - rect.top + 8)}px`
  userMenuOpen.value = true
}

function closeUserMenu(event) {
  if (!userMenuOpen.value) return
  const target = event.target
  if (userTrigger.value?.contains(target) || userMenu.value?.contains(target)) return
  userMenuOpen.value = false
}

function handleKeydown(event) {
  if (event.key === 'Escape') userMenuOpen.value = false
}

function handleLogout() {
  userMenuOpen.value = false
  logout()
  router.replace({ name: 'Login' })
}

watch(() => route.fullPath, () => { userMenuOpen.value = false })

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
    for (const path of item.match || []) {
      if ((route.path === path || route.path.startsWith(`${path}/`)) && path.length > best.length) {
        best = path
      }
    }
  }
  return best
})

function isActive(item) {
  return (item.match || []).includes(activePath.value)
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
  margin: 10px 0 10px 10px;
  padding: 12px 9px 10px;
  gap: 6px;
  background: var(--wb-rail);
  border: 1px solid var(--wb-border);
  border-radius: 22px;
  box-shadow: var(--wb-shadow-sm);
  transition: width 0.26s cubic-bezier(0.22, 1, 0.36, 1), flex-basis 0.26s cubic-bezier(0.22, 1, 0.36, 1);
  overflow: hidden;
}

.wb-nav.collapsed {
  width: 64px;
  flex-basis: 64px;
  border-radius: 20px;
}

.wb-nav-brand {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 2px 4px 8px;
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
  border-radius: 13px;
  background: var(--wb-mark-bg);
  color: var(--wb-mark-fg);
  box-shadow: inset 0 0 0 1px var(--wb-mark-ring);
  user-select: none;
  transition: transform 0.24s cubic-bezier(0.22, 1, 0.36, 1);
}

.wb-nav-brand:hover .wb-nav-logo {
  transform: translateY(-1px) scale(1.03);
}

.wb-nav-logo svg { width: 24px; height: 24px; }

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
  margin: 0 0 4px;
  padding: 8px 10px;
  border: 0;
  border-radius: 12px;
  background: transparent;
  color: var(--wb-muted);
  font-size: 12.5px;
  white-space: nowrap;
  cursor: pointer;
  transition: background 0.18s, color 0.18s;
}

.wb-collapse:hover {
  background: var(--wb-fill);
  color: var(--wb-text);
}

/* 生成活动入口（redesign §3.1）：执行中数量 / 待处理提示点 */
.wb-activity {
  position: relative;
  display: flex;
  align-items: center;
  gap: 10px;
  margin: 0;
  padding: 8px 10px;
  border: 0;
  border-radius: 12px;
  background: transparent;
  color: var(--wb-muted);
  font-size: 12.5px;
  white-space: nowrap;
  cursor: pointer;
  transition: background 0.18s, color 0.18s;
}

.wb-activity:hover { background: var(--wb-fill); color: var(--wb-text); }

.wb-activity.has-attention { color: var(--wb-warning); }

.wb-activity-count {
  font-style: normal;
  margin-left: auto;
  min-width: 18px;
  height: 18px;
  display: grid;
  place-items: center;
  padding: 0 5px;
  border-radius: 9px;
  background: var(--wb-brand-soft);
  color: var(--wb-brand);
  font-size: 11px;
  font-weight: 620;
}

.wb-activity.has-attention .wb-activity-count { background: rgba(161, 92, 0, 0.14); color: var(--wb-warning); }

.wb-activity-dot {
  font-style: normal;
  margin-left: auto;
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--wb-warning);
}

.wb-nav.collapsed .wb-activity { justify-content: center; padding: 8px 0; }
.wb-nav.collapsed .wb-activity .wb-activity-count,
.wb-nav.collapsed .wb-activity .wb-activity-dot { position: absolute; top: 4px; right: 5px; margin: 0; }

.wb-nav.collapsed .wb-collapse {
  justify-content: center;
  padding: 8px 0;
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
  letter-spacing: 0.06em;
  opacity: 0.7;
  white-space: nowrap;
}

.wb-nav.collapsed .wb-nav-group-label {
  margin: 10px 0 2px;
  text-align: center;
  font-size: 10px;
  overflow: hidden;
}

.wb-nav-item-quiet {
  height: 36px;
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
  height: 40px;
  padding: 0 10px;
  border: 0;
  border-radius: 13px;
  background: transparent;
  color: var(--wb-muted);
  font-size: 13px;
  white-space: nowrap;
  cursor: pointer;
  transition: color 0.18s, background 0.18s, transform 0.22s cubic-bezier(0.22, 1, 0.36, 1);
}

.wb-nav-item > svg {
  flex-shrink: 0;
  transition: transform 0.22s cubic-bezier(0.22, 1, 0.36, 1);
}

.wb-nav.collapsed .wb-nav-item {
  justify-content: center;
  padding: 0;
}

.wb-nav-item:hover {
  background: var(--wb-fill);
  color: var(--wb-text);
}

.wb-nav-item:hover > svg {
  transform: scale(1.08);
}

.wb-nav-item:active > svg {
  transform: scale(0.96);
}

.wb-nav-item.active {
  background: var(--wb-brand-soft);
  color: var(--wb-brand);
  font-weight: 600;
}

.wb-nav-item.active > svg {
  transform: scale(1.04);
}

.wb-nav-label {
  overflow: hidden;
  text-overflow: ellipsis;
  opacity: 1;
  transition: opacity 0.16s;
}

.wb-nav.collapsed .wb-nav-label {
  display: none;
}

.wb-nav-user {
  display: flex;
  align-items: center;
  gap: 10px;
  height: 40px;
  width: 100%;
  margin-top: 4px;
  padding: 0 10px;
  border: 0;
  border-radius: 13px;
  background: var(--wb-fill);
  color: var(--wb-text-2);
  font-family: inherit;
  font-size: 13px;
  text-align: left;
  cursor: pointer;
  white-space: nowrap;
  transition: background 0.18s, color 0.18s;
}

.wb-nav-user:hover {
  background: var(--wb-fill-hover);
  color: var(--wb-text);
}

.wb-nav.collapsed .wb-nav-user {
  justify-content: center;
  padding: 0;
}

.wb-nav-user-badge {
  display: grid;
  place-items: center;
  width: 26px;
  height: 26px;
  flex: 0 0 26px;
  border-radius: 50%;
  background: var(--wb-brand-gradient, linear-gradient(135deg, #2e6be6, #22c3be));
  color: #fff;
  font-size: 12px;
  font-weight: 600;
  transition: transform 0.22s cubic-bezier(0.22, 1, 0.36, 1);
}

.wb-nav-user:hover .wb-nav-user-badge {
  transform: scale(1.06);
}

.wb-nav.collapsed .wb-nav-user .wb-nav-label {
  display: none;
}

/* 账号菜单（Teleport 到 body，样式沿用 wb 令牌） */
.wb-user-popover {
  position: fixed;
  z-index: 120;
  width: 240px;
  padding: 12px;
  border: 1px solid var(--wb-border);
  border-radius: 12px;
  background: var(--wb-surface);
  box-shadow: var(--wb-shadow-md);
}

.wb-user-section {
  margin: 0 2px 8px;
  color: var(--wb-muted);
  font-size: 11px;
  letter-spacing: 0.04em;
}

.wb-user-card {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px;
  border-radius: 10px;
  background: var(--wb-surface-2);
}

.wb-nav-user-badge.large {
  width: 34px;
  height: 34px;
  flex: 0 0 34px;
  font-size: 14px;
}

.wb-user-copy {
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.wb-user-copy strong {
  overflow: hidden;
  color: var(--wb-text);
  font-size: 13px;
  font-weight: 600;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.wb-user-copy small {
  color: var(--wb-muted);
  font-size: 11.5px;
}

.wb-user-meta {
  display: grid;
  gap: 6px;
  margin: 10px 2px 12px;
}

.wb-user-meta div {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  font-size: 12px;
}

.wb-user-meta dt {
  color: var(--wb-muted);
}

.wb-user-meta dd {
  overflow: hidden;
  margin: 0;
  color: var(--wb-text-2);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.wb-user-logout {
  display: flex;
  width: 100%;
  align-items: center;
  gap: 8px;
  padding: 9px 10px;
  border: 0;
  border-radius: 9px;
  background: transparent;
  color: var(--wb-danger);
  font-family: inherit;
  font-size: 12.5px;
  font-weight: 600;
  text-align: left;
  cursor: pointer;
  transition: background 0.15s;
}

.wb-user-logout:hover {
  background: rgba(226, 61, 90, 0.1);
}

/* 折叠态悬浮提示：折叠时隐藏文字，悬停显示原生 title 兜底 */
.wb-nav.collapsed .wb-nav-item:hover {
  background: var(--wb-fill);
}

/* 减少动态效果：保留状态变化，去掉缩放与位移 */
@media (prefers-reduced-motion: reduce) {
  .wb-nav,
  .wb-nav-item,
  .wb-nav-item > svg,
  .wb-nav-logo,
  .wb-nav-user-badge {
    transition: none;
  }

  .wb-nav-brand:hover .wb-nav-logo,
  .wb-nav-item:hover > svg,
  .wb-nav-item:active > svg,
  .wb-nav-item.active > svg,
  .wb-nav-user:hover .wb-nav-user-badge {
    transform: none;
  }
}
</style>
