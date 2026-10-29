<template>
  <aside class="conversation-sidebar" :class="{ open }">
    <header class="sidebar-head">
      <div class="head-title">
        <strong>任务</strong>
        <span v-if="conversations.length" class="head-count">{{ conversations.length }}</span>
      </div>
      <button class="icon-button collapse-button" type="button" title="收起任务栏（⌘B）" aria-label="收起任务栏" @click="$emit('collapse')">
        <PanelLeftClose :size="17" />
      </button>
      <button class="icon-button close-button" type="button" title="关闭" aria-label="关闭" @click="$emit('close')">
        <X :size="17" />
      </button>
    </header>

    <button class="new-task-button" type="button" @click="$emit('create')">
      <SquarePen :size="16" stroke-width="1.9" />
      <span>新建任务</span>
    </button>

    <div class="search-box" :class="{ active: searchFocused }">
      <Search :size="14" />
      <input ref="searchInput" v-model.trim="searchText" type="text" placeholder="搜索任务"
        aria-label="搜索任务" @focus="searchFocused = true" @blur="searchFocused = false" @keydown.esc="searchText = ''">
      <button v-if="searchText" class="clear-search" type="button" title="清空搜索" aria-label="清空搜索" @click="searchText = ''">
        <X :size="13" />
      </button>
      <kbd v-else class="search-hint">{{ shortcutLabel }}</kbd>
    </div>

    <nav class="task-scroll" aria-label="工作区与任务">
      <p v-if="loading" class="list-state">正在加载任务…</p>
      <template v-else>
        <section v-for="group in visibleGroups" :key="group.id || 'unbound'" class="task-group">
          <div class="group-head">
            <button class="group-toggle" type="button" :title="group.directoryName || group.name" @click="toggleGroup(group.id)">
              <ChevronRight :size="12" class="chevron" :class="{ open: !isGroupCollapsed(group.id) }" />
              <Folder v-if="group.id" :size="14" />
              <Inbox v-else :size="14" />
              <span class="group-name">{{ group.name }}</span>
              <span class="group-count">{{ group.items.length }}</span>
            </button>
            <button class="group-action" type="button" :title="`在「${group.name}」新建任务`" aria-label="在此工作区新建任务"
              @click="$emit('create-in', group.id)">
              <Plus :size="14" />
            </button>
          </div>

          <div v-if="!isGroupCollapsed(group.id)" class="group-items">
            <div v-for="conversation in group.items" :key="conversation.id" class="task-row" :class="{ active: conversation.id === activeId }">
              <button class="task-item" type="button" :title="conversation.title" @click="$emit('select', conversation)">
                <span class="task-title">{{ conversation.title }}</span>
                <time class="task-time">{{ formatUpdatedAt(conversation.updatedAt) }}</time>
              </button>
              <button class="task-delete" type="button" title="删除任务" aria-label="删除任务" @click.stop="$emit('delete', conversation)">
                <Trash2 :size="13" />
              </button>
            </div>
            <p v-if="!group.items.length" class="group-empty">暂无任务</p>
          </div>
        </section>

        <button v-if="!visibleGroups.length && searchText" class="list-state as-button" type="button" @click="searchText = ''">
          没有匹配的任务
        </button>
        <div v-else-if="!groups.length" class="empty-block">
          <span class="empty-icon"><SquarePen :size="18" /></span>
          <strong>还没有任务</strong>
          <p>描述一个目标，星枢会拆解计划并交付结果。</p>
          <button type="button" class="empty-cta" @click="$emit('create')">新建第一个任务</button>
        </div>

        <button class="add-workspace" type="button" @click="openWorkspaceForm">
          <FolderPlus :size="14" stroke-width="1.9" />
          <span>添加工作区</span>
        </button>
      </template>
    </nav>
  </aside>

  <Teleport to="body">
    <div v-if="workspaceFormOpen" class="dialog-backdrop" @click.self="closeWorkspaceForm">
      <form class="workspace-dialog" role="dialog" aria-modal="true" aria-labelledby="workspace-dialog-title" @submit.prevent="submitWorkspace">
        <div class="dialog-header">
          <div>
            <h2 id="workspace-dialog-title">{{ dialogTitle }}</h2>
            <p>{{ dialogDescription }}</p>
          </div>
          <button class="icon-button dialog-close" type="button" title="关闭" aria-label="关闭" @click="closeWorkspaceForm"><X :size="17" /></button>
        </div>

        <div v-if="dialogMode === 'menu'" class="workspace-options">
          <button type="button" @click="startSelectWorkspace">
            <FolderOpen :size="19" /><span><strong>选择文件夹</strong><small>将已有项目登记为工作区</small></span><ChevronRight :size="16" />
          </button>
          <button type="button" @click="startCreateWorkspace">
            <FolderPlus :size="19" /><span><strong>新建文件夹</strong><small>在默认工作区目录下创建</small></span><ChevronRight :size="16" />
          </button>
        </div>

        <template v-else-if="dialogMode === 'create'">
          <label class="form-field">
            <span>名称</span>
            <input ref="nameInput" v-model.trim="workspaceName" maxlength="80" autocomplete="off" placeholder="例如：Aster 官网" required>
          </label>
          <label class="form-field">
            <span>文件夹</span>
            <input v-model.trim="directoryName" maxlength="128" autocomplete="off" placeholder="例如：aster-website" required>
          </label>
          <p v-if="workspaceError" class="form-error" role="alert">{{ workspaceError }}</p>
          <div class="dialog-actions">
            <button type="button" class="secondary-button" @click="dialogMode = 'menu'">返回</button>
            <button type="submit" class="primary-button" :disabled="workspaceCreating">{{ workspaceCreating ? '创建中…' : '创建工作区' }}</button>
          </div>
        </template>

        <template v-else>
          <button v-if="isDesktop" type="button" class="primary-button native-picker" @click="pickNativeDirectory">
            {{ workspaceCreating ? '授权中…' : '选择本机目录…' }}
          </button>
          <div class="path-bar">
            <button class="icon-button" type="button" title="上一级" aria-label="上一级" :disabled="directoryView?.parentPath == null || directoryLoading" @click="browse(directoryView.parentPath)"><ArrowUp :size="15" /></button>
            <span :title="directoryView?.displayPath">{{ directoryView?.displayPath || '正在读取…' }}</span>
          </div>
          <div class="directory-list" aria-label="可选择的文件夹">
            <p v-if="directoryLoading" class="directory-state">正在读取文件夹…</p>
            <div v-for="directory in directoryView?.directories || []" v-else :key="directory.path" class="directory-row" :class="{ selected: selectedDirectory?.path === directory.path }">
              <button type="button" class="directory-select" @dblclick="browse(directory.path)" @click="selectedDirectory = directory"><Folder :size="16" /><span>{{ directory.name }}</span></button>
              <button type="button" class="open-directory" title="进入文件夹" aria-label="进入文件夹" @click="browse(directory.path)"><ChevronRight :size="15" /></button>
            </div>
            <p v-if="!directoryLoading && directoryView && !directoryView.directories.length" class="directory-state">此文件夹中没有子文件夹</p>
          </div>
          <p class="selection-hint">{{ selectedDirectory ? `已选择：${selectedDirectory.name}` : directoryView?.selectable ? '可选择当前文件夹，或选择下方子文件夹' : '请选择一个子文件夹' }}</p>
          <p v-if="workspaceError" class="form-error" role="alert">{{ workspaceError }}</p>
          <div class="dialog-actions">
            <button type="button" class="secondary-button" @click="dialogMode = 'menu'">返回</button>
            <button type="button" class="primary-button" :disabled="workspaceCreating || directoryLoading || (!selectedDirectory && !directoryView?.selectable)" @click="chooseSelectedWorkspace">
              {{ workspaceCreating ? '添加中…' : '添加工作区' }}
            </button>
          </div>
        </template>
      </form>
    </div>
  </Teleport>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import {
  ArrowUp, ChevronRight, Folder, FolderOpen, FolderPlus, Inbox, PanelLeftClose,
  Plus, Search, SquarePen, Trash2, X
} from 'lucide-vue-next'
import { chooseDesktopDirectory, isDesktopLocal } from '../api/auth'

const props = defineProps({
  conversations: { type: Array, default: () => [] },
  projects: { type: Array, default: () => [] },
  activeId: { type: String, default: '' },
  loading: { type: Boolean, default: false },
  workspaceCreating: { type: Boolean, default: false },
  workspaceError: { type: String, default: '' },
  directoryView: { type: Object, default: null },
  directoryLoading: { type: Boolean, default: false },
  open: { type: Boolean, default: false }
})

const emit = defineEmits([
  'create', 'create-in', 'create-workspace', 'browse-workspaces', 'choose-workspace',
  'select', 'delete', 'close', 'collapse'
])

// 桌面端：系统目录选择器取得用户授权后直接绑定
const isDesktop = isDesktopLocal()
const pickNativeDirectory = async () => {
  if (props.workspaceCreating) return
  const path = await chooseDesktopDirectory()
  if (path) emit('choose-workspace', path)
}

const workspaceFormOpen = ref(false)
const workspaceName = ref('')
const directoryName = ref('')
const searchText = ref('')
const searchFocused = ref(false)
const searchInput = ref(null)
const nameInput = ref(null)
const dialogMode = ref('menu')
const selectedDirectory = ref(null)
const closeAfterCreate = ref(false)

const dialogTitle = computed(() => dialogMode.value === 'menu' ? '添加工作区' : dialogMode.value === 'create' ? '新建工作区' : '选择文件夹')
const dialogDescription = computed(() => dialogMode.value === 'menu'
  ? '选择已有项目，或创建一个新的项目文件夹'
  : dialogMode.value === 'create' ? '在默认工作区目录下创建文件夹' : '选择当前机器上允许访问的文件夹')

const shortcutLabel = /Mac|iPhone|iPad/.test(navigator.platform) ? '⌘K' : 'Ctrl K'

// 工作区分组：保持项目注册顺序，未绑定任务的散会话固定放在最后
const groups = computed(() => {
  const projectGroups = props.projects.map(project => ({
    ...project,
    items: props.conversations.filter(item => item.projectId === project.id)
  }))
  const unbound = props.conversations.filter(item => !item.projectId)
  if (unbound.length) projectGroups.push({
    id: null,
    name: '未绑定工作区',
    directoryName: '',
    items: unbound
  })
  return projectGroups
})

const visibleGroups = computed(() => {
  const keyword = searchText.value.toLocaleLowerCase('zh-CN')
  if (!keyword) return groups.value
  return groups.value
    .map(group => ({
      ...group,
      items: group.items.filter(item => item.title.toLocaleLowerCase('zh-CN').includes(keyword))
    }))
    .filter(group => group.name.toLocaleLowerCase('zh-CN').includes(keyword) || group.items.length)
})

// 分组折叠状态本地记忆，键为项目 id，未绑定用 unbound
const GROUPS_KEY = 'aster.tasks.collapsedGroups'
const collapsedGroups = ref(loadCollapsedGroups())
function loadCollapsedGroups() {
  try {
    const parsed = JSON.parse(localStorage.getItem(GROUPS_KEY) || '[]')
    return new Set(Array.isArray(parsed) ? parsed : [])
  } catch { return new Set() }
}
const groupKey = id => id || 'unbound'
const isGroupCollapsed = id => collapsedGroups.value.has(groupKey(id))
function toggleGroup(id) {
  const next = new Set(collapsedGroups.value)
  const key = groupKey(id)
  if (next.has(key)) next.delete(key)
  else next.add(key)
  collapsedGroups.value = next
  try { localStorage.setItem(GROUPS_KEY, JSON.stringify([...next])) } catch { /* ignore */ }
}

const openWorkspaceForm = () => {
  dialogMode.value = 'menu'
  workspaceFormOpen.value = true
}
const closeWorkspaceForm = () => {
  if (props.workspaceCreating) return
  workspaceFormOpen.value = false
  workspaceName.value = ''
  directoryName.value = ''
  selectedDirectory.value = null
}
const startCreateWorkspace = async () => { dialogMode.value = 'create'; await nextTick(); nameInput.value?.focus() }
const startSelectWorkspace = () => { dialogMode.value = 'select'; selectedDirectory.value = null; emit('browse-workspaces', null) }
const browse = path => { selectedDirectory.value = null; emit('browse-workspaces', path) }
const chooseSelectedWorkspace = () => emit('choose-workspace', selectedDirectory.value?.path || props.directoryView?.currentPath)
const submitWorkspace = () => {
  if (!workspaceName.value || !directoryName.value || props.workspaceCreating) return
  emit('create-workspace', { name: workspaceName.value, directoryName: directoryName.value })
}

const formatUpdatedAt = value => {
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return ''
  const diff = Date.now() - date.getTime()
  if (diff >= 0 && diff < 60 * 1000) return '刚刚'
  if (diff >= 0 && diff < 60 * 60 * 1000) return `${Math.floor(diff / 60000)} 分钟`
  if (diff >= 0 && diff < 24 * 60 * 60 * 1000) return `${Math.floor(diff / 3600000)} 小时`
  if (diff >= 0 && diff < 48 * 60 * 60 * 1000) return '昨天'
  if (date.getFullYear() === new Date().getFullYear()) return `${date.getMonth() + 1}月${date.getDate()}日`
  return `${date.getFullYear()}/${date.getMonth() + 1}/${date.getDate()}`
}

// ⌘K / Ctrl+K 聚焦搜索，与 Codex 的会话检索习惯保持一致
const onGlobalKeydown = event => {
  if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === 'k') {
    event.preventDefault()
    searchInput.value?.focus()
    searchInput.value?.select()
  }
}
onMounted(() => window.addEventListener('keydown', onGlobalKeydown))
onBeforeUnmount(() => window.removeEventListener('keydown', onGlobalKeydown))

watch(() => props.projects.length, (current, previous) => {
  if (current > previous) {
    if (props.workspaceCreating) closeAfterCreate.value = true
    else closeWorkspaceForm()
  }
})
watch(() => props.workspaceCreating, creating => {
  if (!creating && closeAfterCreate.value) {
    closeAfterCreate.value = false
    closeWorkspaceForm()
  }
})
</script>

<style scoped>
.conversation-sidebar {
  display: flex;
  width: 284px;
  flex: 0 0 284px;
  height: 100%;
  min-height: 0;
  flex-direction: column;
  overflow: hidden;
  border-right: 1px solid var(--wb-border);
  background: var(--wb-surface);
  color: var(--wb-text);
}

.sidebar-head {
  display: flex;
  height: 60px;
  flex: 0 0 60px;
  align-items: center;
  justify-content: space-between;
  padding: 0 10px 0 16px;
}

.head-title { display: flex; min-width: 0; align-items: center; gap: 7px; }
.head-title strong { color: var(--wb-text); font-size: 13.5px; font-weight: 700; letter-spacing: 0.01em; }
.head-count {
  min-width: 18px;
  border-radius: 999px;
  padding: 1px 6px;
  background: var(--wb-fill);
  color: var(--wb-muted);
  font-size: 10.5px;
  font-variant-numeric: tabular-nums;
  text-align: center;
}

.icon-button {
  display: grid;
  width: 28px;
  height: 28px;
  place-items: center;
  border: 0;
  border-radius: var(--wb-radius-sm);
  background: transparent;
  color: var(--wb-muted);
  cursor: pointer;
}
.icon-button:hover { background: var(--wb-fill-hover); color: var(--wb-text); }
.icon-button:disabled { opacity: 0.35; cursor: default; }
.close-button { display: none; }

.new-task-button {
  display: flex;
  height: 36px;
  flex: 0 0 36px;
  align-items: center;
  gap: 9px;
  margin: 0 10px;
  border: 1px solid transparent;
  border-radius: var(--wb-radius-sm);
  padding: 0 10px;
  background: var(--wb-brand-soft);
  color: var(--wb-brand);
  font-size: 12.5px;
  font-weight: 650;
  text-align: left;
  cursor: pointer;
  transition: border-color 0.15s ease, background 0.15s ease;
}
.new-task-button:hover { border-color: var(--wb-brand); }
.new-task-button:active { transform: translateY(0.5px); }

.search-box {
  display: flex;
  height: 32px;
  flex: 0 0 32px;
  align-items: center;
  gap: 7px;
  margin: 10px 10px 4px;
  border: 1px solid transparent;
  border-radius: var(--wb-radius-sm);
  padding: 0 8px;
  background: var(--wb-fill);
  color: var(--wb-muted);
  transition: border-color 0.15s ease, background 0.15s ease;
}
.search-box.active { border-color: var(--wb-brand); background: var(--wb-surface); }
.search-box input { min-width: 0; flex: 1; border: 0; outline: none; background: transparent; color: var(--wb-text); font-size: 12px; }
.search-box input::placeholder { color: var(--wb-muted); }
.search-hint {
  border: 1px solid var(--wb-border);
  border-radius: 5px;
  padding: 0 4px;
  background: var(--wb-surface);
  color: var(--wb-muted);
  font: inherit;
  font-size: 10px;
  line-height: 16px;
}
.clear-search { display: grid; width: 18px; height: 18px; place-items: center; border: 0; border-radius: 4px; background: transparent; color: var(--wb-muted); cursor: pointer; }
.clear-search:hover { background: var(--wb-fill-hover); color: var(--wb-text); }

.task-scroll {
  min-height: 0;
  flex: 1;
  overflow-y: auto;
  overscroll-behavior: contain;
  padding: 6px 10px 16px;
  scrollbar-width: thin;
  scrollbar-color: var(--wb-scrollbar) transparent;
}

.task-group { margin-bottom: 10px; }

.group-head { display: flex; align-items: center; border-radius: var(--wb-radius-sm); }
.group-head:hover { background: var(--wb-fill); }
.group-toggle {
  display: flex;
  height: 30px;
  min-width: 0;
  flex: 1;
  align-items: center;
  gap: 6px;
  border: 0;
  border-radius: var(--wb-radius-sm);
  padding: 0 6px 0 4px;
  background: transparent;
  color: var(--wb-text-2);
  text-align: left;
  cursor: pointer;
}
.group-toggle:hover { color: var(--wb-text); }
.chevron { flex: 0 0 12px; color: var(--wb-muted); transition: transform 0.16s ease; }
.chevron.open { transform: rotate(90deg); }
.group-name { overflow: hidden; flex: 1; font-size: 12px; font-weight: 650; text-overflow: ellipsis; white-space: nowrap; }
.group-count { color: var(--wb-muted); font-size: 10.5px; font-variant-numeric: tabular-nums; }
.group-action {
  display: grid;
  width: 24px;
  height: 24px;
  flex: 0 0 24px;
  place-items: center;
  margin-right: 4px;
  border: 0;
  border-radius: 6px;
  background: transparent;
  color: var(--wb-muted);
  cursor: pointer;
  opacity: 0;
  transition: opacity 0.12s ease;
}
.group-head:hover .group-action, .group-action:focus-visible { opacity: 1; }
.group-action:hover { background: var(--wb-fill-hover); color: var(--wb-brand); }

.group-items { margin-top: 1px; }

.task-row { position: relative; display: flex; align-items: center; border-radius: var(--wb-radius-sm); }
.task-row:hover { background: var(--wb-fill); }
.task-row.active { background: var(--wb-brand-soft); }
.task-item {
  display: flex;
  height: 32px;
  min-width: 0;
  flex: 1;
  align-items: center;
  gap: 8px;
  border: 0;
  border-radius: var(--wb-radius-sm);
  padding: 0 8px 0 24px;
  background: transparent;
  color: var(--wb-text-2);
  text-align: left;
  cursor: pointer;
}
.task-row.active .task-item { color: var(--wb-text); font-weight: 600; }
.task-title { overflow: hidden; flex: 1; font-size: 12.5px; text-overflow: ellipsis; white-space: nowrap; }
.task-time { flex: 0 0 auto; color: var(--wb-muted); font-size: 10.5px; font-variant-numeric: tabular-nums; white-space: nowrap; transition: opacity 0.12s ease; }
.task-delete {
  position: absolute;
  right: 5px;
  display: grid;
  width: 24px;
  height: 24px;
  place-items: center;
  border: 0;
  border-radius: 6px;
  background: transparent;
  color: var(--wb-muted);
  cursor: pointer;
  opacity: 0;
  transition: opacity 0.12s ease;
}
.task-row:hover .task-time, .task-row:focus-within .task-time { opacity: 0; }
.task-row:hover .task-delete, .task-row:focus-within .task-delete { opacity: 1; }
.task-delete:hover { background: var(--wb-fill-hover); color: var(--wb-danger); }

.group-empty { margin: 2px 0 4px 24px; color: var(--wb-muted); font-size: 11.5px; }

.list-state { margin: 14px 8px; color: var(--wb-muted); font-size: 12px; }
.list-state.as-button {
  display: block;
  width: calc(100% - 16px);
  border: 1px dashed var(--wb-border-strong);
  border-radius: var(--wb-radius-sm);
  padding: 10px;
  background: transparent;
  text-align: center;
  cursor: pointer;
}
.list-state.as-button:hover { color: var(--wb-text-2); border-color: var(--wb-brand); }

.empty-block { display: flex; flex-direction: column; align-items: center; gap: 6px; padding: 34px 14px 22px; text-align: center; }
.empty-icon { display: grid; width: 40px; height: 40px; place-items: center; border-radius: 12px; background: var(--wb-fill); color: var(--wb-muted); }
.empty-block strong { margin-top: 4px; color: var(--wb-text); font-size: 12.5px; }
.empty-block p { margin: 0; color: var(--wb-muted); font-size: 11.5px; line-height: 1.6; }
.empty-cta {
  margin-top: 8px;
  border: 1px solid var(--wb-border);
  border-radius: var(--wb-radius-sm);
  padding: 6px 12px;
  background: var(--wb-surface);
  color: var(--wb-text-2);
  font-size: 12px;
  font-weight: 600;
  cursor: pointer;
}
.empty-cta:hover { border-color: var(--wb-brand); color: var(--wb-brand); }

.add-workspace {
  display: flex;
  width: 100%;
  height: 32px;
  align-items: center;
  gap: 7px;
  margin-top: 6px;
  border: 1px dashed var(--wb-border-strong);
  border-radius: var(--wb-radius-sm);
  padding: 0 10px;
  background: transparent;
  color: var(--wb-muted);
  font-size: 12px;
  text-align: left;
  cursor: pointer;
}
.add-workspace:hover { border-color: var(--wb-brand); color: var(--wb-brand); }

/* 工作区弹窗沿用同一套设计令牌，深浅色主题一致 */
.dialog-backdrop { position: fixed; z-index: 100; inset: 0; display: grid; place-items: center; padding: 20px; background: rgba(15, 17, 26, 0.36); backdrop-filter: blur(2px); }
.workspace-dialog { width: min(400px, calc(100vw - 32px)); box-sizing: border-box; border: 1px solid var(--wb-border); border-radius: var(--wb-radius-lg); padding: 20px; background: var(--wb-surface); box-shadow: var(--wb-shadow-lg); color: var(--wb-text); }
.dialog-header { display: flex; align-items: flex-start; justify-content: space-between; margin-bottom: 18px; }
.dialog-header h2 { margin: 0 0 5px; font-size: 16px; font-weight: 700; }
.dialog-header p { margin: 0; color: var(--wb-muted); font-size: 12px; }
.dialog-close { margin: -4px -4px 0 0; }
.workspace-options { display: grid; gap: 8px; }
.workspace-options > button { display: grid; min-height: 62px; grid-template-columns: 24px minmax(0, 1fr) 18px; align-items: center; gap: 10px; border: 1px solid var(--wb-border); border-radius: var(--wb-radius); padding: 10px 12px; background: var(--wb-surface-2); color: var(--wb-text-2); text-align: left; cursor: pointer; }
.workspace-options > button:hover { border-color: var(--wb-brand); background: var(--wb-brand-soft); color: var(--wb-brand); }
.workspace-options span { display: flex; min-width: 0; flex-direction: column; gap: 3px; }
.workspace-options strong { color: var(--wb-text); font-size: 13px; }
.workspace-options small { color: var(--wb-muted); font-size: 11px; }
.form-field { display: block; margin-bottom: 14px; }
.form-field span { display: block; margin-bottom: 6px; color: var(--wb-text-2); font-size: 12px; font-weight: 600; }
.form-field input { width: 100%; box-sizing: border-box; height: 36px; border: 1px solid var(--wb-border-strong); border-radius: var(--wb-radius-sm); padding: 0 10px; outline: none; background: var(--wb-surface); color: var(--wb-text); font-size: 13px; }
.form-field input:focus { border-color: var(--wb-brand); box-shadow: 0 0 0 3px var(--wb-brand-soft); }
.form-error { margin: -3px 0 12px; color: var(--wb-danger); font-size: 12px; }
.path-bar { display: flex; height: 36px; align-items: center; gap: 6px; border: 1px solid var(--wb-border); border-radius: var(--wb-radius-sm); padding: 0 6px; background: var(--wb-surface-2); }
.path-bar > span { overflow: hidden; min-width: 0; flex: 1; color: var(--wb-text-2); font-size: 12px; text-overflow: ellipsis; white-space: nowrap; }
.directory-list { height: 250px; overflow-y: auto; margin-top: 10px; border: 1px solid var(--wb-border); border-radius: var(--wb-radius-sm); padding: 5px; background: var(--wb-surface-2); }
.directory-row { display: flex; align-items: center; border-radius: 6px; }
.directory-row:hover { background: var(--wb-fill-hover); }
.directory-row.selected { background: var(--wb-brand-soft); }
.directory-select { display: flex; height: 34px; min-width: 0; flex: 1; align-items: center; gap: 8px; border: 0; padding: 0 7px; background: transparent; color: var(--wb-text-2); text-align: left; cursor: pointer; }
.directory-select span { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.open-directory { display: grid; width: 28px; height: 28px; place-items: center; border: 0; border-radius: 5px; background: transparent; color: var(--wb-muted); cursor: pointer; }
.open-directory:hover { background: var(--wb-fill-hover); color: var(--wb-brand); }
.directory-state { margin: 18px 10px; color: var(--wb-muted); font-size: 12px; text-align: center; }
.selection-hint { overflow: hidden; margin: 8px 2px 0; color: var(--wb-muted); font-size: 11px; text-overflow: ellipsis; white-space: nowrap; }
.dialog-actions { display: flex; justify-content: flex-end; gap: 8px; margin-top: 20px; }
.secondary-button, .primary-button { height: 34px; border-radius: var(--wb-radius-sm); padding: 0 14px; font-size: 12.5px; font-weight: 600; cursor: pointer; }
.secondary-button { border: 1px solid var(--wb-border-strong); background: var(--wb-surface); color: var(--wb-text-2); }
.secondary-button:hover { background: var(--wb-fill); }
.primary-button { border: 1px solid var(--wb-brand); background: var(--wb-brand); color: var(--wb-brand-contrast); }
.primary-button:hover { background: var(--wb-brand-hover); border-color: var(--wb-brand-hover); }
.primary-button:disabled { cursor: default; opacity: 0.5; }
.native-picker { width: 100%; margin-bottom: 10px; }

@media (max-width: 720px) {
  .conversation-sidebar { position: fixed; z-index: 40; inset: 0 auto 0 0; width: min(84vw, 300px); box-shadow: var(--wb-shadow-lg); transform: translateX(-100%); transition: transform 0.2s ease; }
  .conversation-sidebar.open { transform: translateX(0); }
  .collapse-button { display: none; }
  .close-button { display: grid; }
}
</style>
