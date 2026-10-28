<template>
  <aside class="conversation-sidebar" :class="{ open }">
    <header class="sidebar-header">
      <div class="brand">
        <span class="brand-mark" :class="theme">{{ mark }}</span>
        <strong>{{ title }}</strong>
      </div>
      <button class="icon-button close-button" type="button" title="关闭侧栏" aria-label="关闭侧栏" @click="$emit('close')">
        <X :size="18" />
      </button>
    </header>

    <button class="new-task-button" type="button" @click="$emit('create')">
      <SquarePen :size="18" stroke-width="1.8" />
      <span>新建任务</span>
    </button>

    <div class="sidebar-divider"></div>

    <div class="list-toolbar">
      <h2>任务列表</h2>
      <div class="toolbar-actions">
        <button class="icon-button" type="button" title="搜索任务" aria-label="搜索任务" @click="searchOpen = !searchOpen">
          <Search :size="17" />
        </button>
        <button class="icon-button" type="button" title="新建工作区" aria-label="新建工作区" @click="openWorkspaceForm">
          <FolderPlus :size="17" />
        </button>
      </div>
    </div>

    <div v-if="searchOpen" class="search-row">
      <Search :size="15" />
      <input ref="searchInput" v-model.trim="searchText" type="text" placeholder="搜索任务" aria-label="搜索任务">
      <button v-if="searchText" class="icon-button clear-search" type="button" title="清空搜索" aria-label="清空搜索" @click="searchText = ''"><X :size="14" /></button>
    </div>

    <div class="conversation-list" aria-label="工作区与任务">
      <p v-if="loading" class="history-state">正在加载...</p>
      <template v-else>
        <section v-for="group in visibleGroups" :key="group.id || 'unbound'" class="workspace-group">
          <button class="workspace-row" :class="{ selected: group.id === selectedProjectId }" type="button"
            :title="group.directoryName || group.name" @click="$emit('select-project', group.id)">
            <Folder :size="17" stroke-width="1.65" />
            <span class="workspace-name">{{ group.name }}</span>
            <span v-if="group.items.length" class="task-count">{{ group.items.length }}</span>
          </button>

          <div class="workspace-tasks">
            <div v-for="conversation in group.items" :key="conversation.id" class="conversation-row">
              <button class="conversation-item" :class="{ active: conversation.id === activeId }" type="button"
                :title="conversation.title" @click="$emit('select', conversation)">
                <span class="active-dot" aria-hidden="true"></span>
                <span class="conversation-title">{{ conversation.title }}</span>
                <time>{{ formatUpdatedAt(conversation.updatedAt) }}</time>
              </button>
              <button class="icon-button delete-button" type="button" title="删除任务" aria-label="删除任务" @click.stop="$emit('delete', conversation)">
                <Trash2 :size="14" />
              </button>
            </div>
          </div>
        </section>

        <p v-if="!visibleGroups.length" class="history-state">没有匹配的任务</p>
      </template>
    </div>
  </aside>

  <Teleport to="body">
    <div v-if="workspaceFormOpen" class="dialog-backdrop" @click.self="closeWorkspaceForm">
      <form class="workspace-dialog" role="dialog" aria-modal="true" aria-labelledby="workspace-dialog-title" @submit.prevent="submitWorkspace">
        <div class="dialog-header">
          <div>
            <h2 id="workspace-dialog-title">{{ dialogTitle }}</h2>
            <p>{{ dialogDescription }}</p>
          </div>
          <button class="icon-button dialog-close" type="button" title="关闭" aria-label="关闭" @click="closeWorkspaceForm"><X :size="18" /></button>
        </div>

        <div v-if="dialogMode === 'menu'" class="workspace-options">
          <button type="button" @click="startSelectWorkspace">
            <FolderOpen :size="20" /><span><strong>选择文件夹</strong><small>将已有项目登记为工作区</small></span><ChevronRight :size="17" />
          </button>
          <button type="button" @click="startCreateWorkspace">
            <FolderPlus :size="20" /><span><strong>新建文件夹</strong><small>在默认工作区目录下创建</small></span><ChevronRight :size="17" />
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
            <button type="submit" class="primary-button" :disabled="workspaceCreating">{{ workspaceCreating ? '创建中...' : '创建工作区' }}</button>
          </div>
        </template>

        <template v-else>
          <button v-if="isDesktop" type="button" class="primary-button native-picker" @click="pickNativeDirectory">
            {{ workspaceCreating ? '授权中…' : '选择本机目录…' }}
          </button>
          <div class="path-bar">
            <button class="icon-button" type="button" title="上一级" aria-label="上一级" :disabled="directoryView?.parentPath == null || directoryLoading" @click="browse(directoryView.parentPath)"><ArrowUp :size="16" /></button>
            <span :title="directoryView?.displayPath">{{ directoryView?.displayPath || '正在读取...' }}</span>
          </div>
          <div class="directory-list" aria-label="可选择的文件夹">
            <p v-if="directoryLoading" class="directory-state">正在读取文件夹...</p>
            <div v-for="directory in directoryView?.directories || []" v-else :key="directory.path" class="directory-row" :class="{ selected: selectedDirectory?.path === directory.path }">
              <button type="button" class="directory-select" @dblclick="browse(directory.path)" @click="selectedDirectory = directory"><Folder :size="17" /><span>{{ directory.name }}</span></button>
              <button type="button" class="open-directory" title="进入文件夹" aria-label="进入文件夹" @click="browse(directory.path)"><ChevronRight :size="16" /></button>
            </div>
            <p v-if="!directoryLoading && directoryView && !directoryView.directories.length" class="directory-state">此文件夹中没有子文件夹</p>
          </div>
          <p class="selection-hint">{{ selectedDirectory ? `已选择：${selectedDirectory.name}` : directoryView?.selectable ? '可选择当前文件夹，或选择下方子文件夹' : '请选择一个子文件夹' }}</p>
          <p v-if="workspaceError" class="form-error" role="alert">{{ workspaceError }}</p>
          <div class="dialog-actions">
            <button type="button" class="secondary-button" @click="dialogMode = 'menu'">返回</button>
            <button type="button" class="primary-button" :disabled="workspaceCreating || directoryLoading || (!selectedDirectory && !directoryView?.selectable)" @click="chooseSelectedWorkspace">
              {{ workspaceCreating ? '添加中...' : '添加工作区' }}
            </button>
          </div>
        </template>
      </form>
    </div>
  </Teleport>
</template>

<script setup>
import { computed, nextTick, ref, watch } from 'vue'
import { ArrowUp, ChevronRight, Folder, FolderOpen, FolderPlus, Search, SquarePen, Trash2, X } from 'lucide-vue-next'
import { chooseDesktopDirectory, isDesktopLocal } from '../api/auth'

const props = defineProps({
  conversations: { type: Array, default: () => [] },
  projects: { type: Array, default: () => [] },
  activeId: { type: String, default: '' },
  selectedProjectId: { type: String, default: null },
  loading: { type: Boolean, default: false },
  workspaceCreating: { type: Boolean, default: false },
  workspaceError: { type: String, default: '' },
  directoryView: { type: Object, default: null },
  directoryLoading: { type: Boolean, default: false },
  open: { type: Boolean, default: false },
  title: { type: String, default: '超级智能体' },
  mark: { type: String, default: 'AI' },
  theme: { type: String, default: 'travel' }
})

const emit = defineEmits(['create', 'create-workspace', 'browse-workspaces', 'choose-workspace', 'select-project', 'select', 'delete', 'close'])
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
const searchOpen = ref(false)
const searchText = ref('')
const searchInput = ref(null)
const nameInput = ref(null)
const dialogMode = ref('menu')
const selectedDirectory = ref(null)
const closeAfterCreate = ref(false)
const dialogTitle = computed(() => dialogMode.value === 'menu' ? '添加工作区' : dialogMode.value === 'create' ? '新建工作区' : '选择文件夹')
const dialogDescription = computed(() => dialogMode.value === 'menu'
  ? '选择已有项目，或创建一个新的项目文件夹'
  : dialogMode.value === 'create' ? '在默认工作区目录下创建文件夹' : '选择当前机器上允许访问的文件夹')

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

const openWorkspaceForm = async () => {
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
  const now = new Date()
  const diff = now.getTime() - date.getTime()
  if (diff >= 0 && diff < 60 * 60 * 1000) return `${Math.max(1, Math.floor(diff / 60000))}分`
  if (diff >= 0 && diff < 24 * 60 * 60 * 1000) return `${Math.floor(diff / 3600000)}时`
  if (diff >= 0 && diff < 7 * 24 * 60 * 60 * 1000) return `${Math.floor(diff / 86400000)}天`
  return date.toLocaleDateString('zh-CN', { month: 'numeric', day: 'numeric' })
}

watch(searchOpen, async open => {
  if (open) {
    await nextTick()
    searchInput.value?.focus()
  } else searchText.value = ''
})
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
.native-picker {
  width: 100%;
  margin-bottom: 10px;
}

.conversation-sidebar {
  width: 304px;
  box-sizing: border-box;
  flex: 0 0 304px;
  display: flex;
  height: 100vh;
  min-height: 0;
  flex-direction: column;
  overflow: hidden;
  border-right: 1px solid #d9e4f3;
  background: linear-gradient(180deg, #f7faff 0%, #f0f5fc 100%);
  color: #26364f;
}
.sidebar-header { display: flex; height: 62px; flex: 0 0 62px; align-items: center; justify-content: space-between; padding: 0 18px; }
.brand { display: flex; min-width: 0; align-items: center; gap: 10px; }
.brand strong { overflow: hidden; color: #22324a; font-size: 16px; font-weight: 650; text-overflow: ellipsis; white-space: nowrap; }
.brand-mark { display: grid; width: 28px; height: 28px; flex: 0 0 28px; place-items: center; border-radius: 7px; background: #e4edfb; color: #3768c5; font-size: 10px; font-weight: 750; }
.brand-mark.travel { font-size: 14px; }
.icon-button { display: grid; width: 30px; height: 30px; place-items: center; border: 0; border-radius: 6px; background: transparent; color: #73829a; }
.icon-button:hover { background: #e5edf9; color: #2f527f; }
.close-button { display: none; }
.new-task-button { display: flex; height: 42px; flex: 0 0 42px; align-items: center; gap: 12px; margin: 2px 10px 10px; border: 0; border-radius: 7px; padding: 0 12px; background: transparent; color: #2a3b55; font-size: 14px; font-weight: 600; text-align: left; }
.new-task-button:hover { background: #e6eef9; }
.sidebar-divider { height: 1px; flex: 0 0 1px; background: #dce6f3; }
.list-toolbar { display: flex; height: 52px; flex: 0 0 52px; align-items: center; justify-content: space-between; padding: 0 12px 0 18px; }
.list-toolbar h2 { margin: 0; color: #74839a; font-size: 13px; font-weight: 700; }
.toolbar-actions { display: flex; gap: 2px; }
.search-row { display: flex; height: 34px; flex: 0 0 34px; align-items: center; gap: 7px; margin: -3px 12px 9px; border: 1px solid #cfdbec; border-radius: 7px; padding: 0 7px 0 9px; background: rgba(255, 255, 255, .82); color: #8190a6; }
.search-row:focus-within { border-color: #9eb6d7; box-shadow: 0 0 0 2px rgba(67, 116, 184, .08); }
.search-row input { min-width: 0; flex: 1; border: 0; outline: none; background: transparent; color: #2d2e32; font-size: 12px; }
.clear-search { width: 24px; height: 24px; }
.conversation-list { min-height: 0; flex: 1; overflow-y: auto; overscroll-behavior: contain; padding: 0 8px 20px; scrollbar-width: thin; scrollbar-color: #c2cfe1 transparent; }
.workspace-group { margin-bottom: 7px; }
.workspace-row { display: flex; width: 100%; height: 36px; min-width: 0; align-items: center; gap: 9px; border: 0; border-radius: 7px; padding: 0 9px; background: transparent; color: #66758b; text-align: left; }
.workspace-row:hover { background: #e7eef8; color: #304967; }
.workspace-row.selected { color: #36577f; }
.workspace-name { overflow: hidden; flex: 1; font-size: 13px; font-weight: 650; text-overflow: ellipsis; white-space: nowrap; }
.task-count { color: #91a0b4; font-size: 11px; font-variant-numeric: tabular-nums; }
.workspace-tasks { padding-left: 15px; }
.conversation-row { position: relative; display: flex; align-items: center; }
.conversation-item { display: grid; width: 100%; height: 36px; min-width: 0; grid-template-columns: 8px minmax(0, 1fr) auto; align-items: center; gap: 8px; border: 0; border-radius: 7px; padding: 0 28px 0 9px; background: transparent; color: #56657a; text-align: left; }
.conversation-item:hover { background: #e8eff9; color: #2d4563; }
.conversation-item.active { background: #dfe9f7; color: #233c5c; font-weight: 600; }
.active-dot { width: 5px; height: 5px; border-radius: 50%; background: transparent; }
.conversation-item.active .active-dot { background: #ff5964; }
.conversation-title { overflow: hidden; font-size: 13px; text-overflow: ellipsis; white-space: nowrap; }
.conversation-item time { color: #8e9db1; font-size: 11px; font-variant-numeric: tabular-nums; white-space: nowrap; }
.delete-button { position: absolute; right: 3px; width: 25px; height: 25px; opacity: 0; }
.conversation-row:hover .delete-button, .conversation-item.active + .delete-button { opacity: 1; }
.delete-button:hover { background: #f7dfe1; color: #c82c3a; }
.history-state { margin: 16px 12px; color: #929499; font-size: 12px; }
.dialog-backdrop { position: fixed; z-index: 100; inset: 0; display: grid; place-items: center; padding: 20px; background: rgba(21, 22, 25, .32); }
.workspace-dialog { width: min(400px, calc(100vw - 32px)); box-sizing: border-box; border: 1px solid #dedfe2; border-radius: 8px; padding: 20px; background: #fff; box-shadow: 0 18px 48px rgba(0, 0, 0, .18); }
.dialog-header { display: flex; align-items: flex-start; justify-content: space-between; margin-bottom: 20px; }
.dialog-header h2 { margin: 0 0 5px; color: #202125; font-size: 17px; font-weight: 700; }
.dialog-header p { margin: 0; color: #85878c; font-size: 12px; }
.dialog-close { margin: -5px -5px 0 0; }
.workspace-options { display: grid; gap: 8px; }
.workspace-options > button { display: grid; min-height: 64px; grid-template-columns: 24px minmax(0, 1fr) 18px; align-items: center; gap: 10px; border: 1px solid #d6e1ef; border-radius: 8px; padding: 10px 12px; background: #f8fbff; color: #49627f; text-align: left; }
.workspace-options > button:hover { border-color: #b8cce6; background: #eff5fd; color: #284e7b; }
.workspace-options span { display: flex; min-width: 0; flex-direction: column; gap: 3px; }
.workspace-options strong { color: #283d59; font-size: 13px; }.workspace-options small { color: #8391a4; font-size: 11px; }
.form-field { display: block; margin-bottom: 14px; }
.form-field span { display: block; margin-bottom: 6px; color: #55575c; font-size: 12px; font-weight: 600; }
.form-field input { width: 100%; box-sizing: border-box; height: 38px; border: 1px solid #d4d5d8; border-radius: 7px; padding: 0 10px; outline: none; color: #292a2e; font-size: 13px; }
.form-field input:focus { border-color: #8c8e93; box-shadow: 0 0 0 3px rgba(0, 0, 0, .05); }
.form-error { margin: -3px 0 12px; color: #c42e3b; font-size: 12px; }
.path-bar { display: flex; height: 38px; align-items: center; gap: 6px; border: 1px solid #d4dfed; border-radius: 7px; padding: 0 7px; background: #f7faff; }
.path-bar > span { overflow: hidden; min-width: 0; flex: 1; color: #52657e; font-size: 12px; text-overflow: ellipsis; white-space: nowrap; }
.path-bar .icon-button:disabled { opacity: .35; }
.directory-list { height: 250px; overflow-y: auto; margin-top: 10px; border: 1px solid #dbe4f0; border-radius: 7px; padding: 5px; background: #fbfdff; }
.directory-row { display: flex; align-items: center; border-radius: 6px; }
.directory-row:hover { background: #edf3fb; }.directory-row.selected { background: #dfeafb; }
.directory-select { display: flex; height: 34px; min-width: 0; flex: 1; align-items: center; gap: 8px; border: 0; padding: 0 7px; background: transparent; color: #52657e; text-align: left; }
.directory-select span { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.open-directory { display: grid; width: 30px; height: 30px; place-items: center; border: 0; border-radius: 5px; background: transparent; color: #8493a7; }
.open-directory:hover { background: #d7e4f5; color: #365a86; }
.directory-state { margin: 18px 10px; color: #8795a8; font-size: 12px; text-align: center; }
.selection-hint { overflow: hidden; margin: 8px 2px 0; color: #718197; font-size: 11px; text-overflow: ellipsis; white-space: nowrap; }
.dialog-actions { display: flex; justify-content: flex-end; gap: 8px; margin-top: 20px; }
.secondary-button, .primary-button { height: 36px; border-radius: 7px; padding: 0 14px; font-size: 13px; font-weight: 600; }
.secondary-button { border: 1px solid #d7d8db; background: #fff; color: #4d4f54; }
.secondary-button:hover { background: #f2f2f3; }
.primary-button { border: 1px solid #27282b; background: #27282b; color: #fff; }
.primary-button:hover { background: #111215; }
.primary-button:disabled { cursor: default; opacity: .5; }
@media (max-width: 720px) {
  .conversation-sidebar { position: fixed; z-index: 30; inset: 0 auto 0 0; width: min(86vw, 320px); transform: translateX(-100%); transition: transform .22s ease; box-shadow: 12px 0 30px rgba(0, 0, 0, .14); }
  .conversation-sidebar.open { transform: translateX(0); }
  .close-button { display: grid; }
}
</style>
