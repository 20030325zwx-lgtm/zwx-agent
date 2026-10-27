<template>
  <aside class="conversation-sidebar" :class="{ open }">
    <div class="sidebar-top">
      <div class="brand"><span class="brand-mark" :class="theme">{{ mark }}</span><span>{{ title }}</span></div>
      <button class="icon-button close-button" type="button" title="关闭侧栏" aria-label="关闭侧栏" @click="$emit('close')"><X :size="18" /></button>
    </div>
    <button class="new-conversation" type="button" @click="$emit('create')"><Plus :size="17" />新建任务</button>
    <div class="workspace-heading">
      <span>工作区</span>
      <button class="icon-button add-workspace" type="button" title="新建工作区" aria-label="新建工作区" @click="workspaceFormOpen = !workspaceFormOpen"><FolderPlus :size="16" /></button>
    </div>
    <form v-if="workspaceFormOpen" class="workspace-form" @submit.prevent="submitWorkspace">
      <label><span>工作区名称</span><input v-model.trim="workspaceName" maxlength="80" autocomplete="off" required></label>
      <label><span>文件夹名称</span><input v-model.trim="directoryName" maxlength="128" autocomplete="off" required></label>
      <p v-if="workspaceError" class="form-error" role="alert">{{ workspaceError }}</p>
      <div class="form-actions">
        <button type="button" class="text-button" @click="closeWorkspaceForm">取消</button>
        <button type="submit" class="submit-button" :disabled="workspaceCreating">{{ workspaceCreating ? '创建中...' : '创建' }}</button>
      </div>
    </form>
    <div class="conversation-list" aria-label="项目工作区与会话">
      <p v-if="loading" class="history-state">正在加载...</p>
      <template v-else>
        <section v-for="project in projectGroups" :key="project.id" class="workspace-group">
          <button class="workspace-row" :class="{ active: project.id === selectedProjectId }" type="button" @click="$emit('select-project', project.id)">
            <Folder :size="15" />
            <span class="workspace-copy"><strong>{{ project.name }}</strong><small>{{ project.directoryName }}</small></span>
            <span class="task-count">{{ project.items.length }}</span>
          </button>
          <ConversationRows :items="project.items" :active-id="activeId" @select="$emit('select', $event)" @delete="$emit('delete', $event)" />
        </section>
        <section class="workspace-group">
          <button class="workspace-row" :class="{ active: selectedProjectId === null }" type="button" @click="$emit('select-project', null)">
            <Folder :size="15" />
            <span class="workspace-copy"><strong>未绑定工作区</strong><small>历史任务</small></span>
            <span class="task-count">{{ unboundConversations.length }}</span>
          </button>
          <ConversationRows :items="unboundConversations" :active-id="activeId" @select="$emit('select', $event)" @delete="$emit('delete', $event)" />
        </section>
        <p v-if="!projects.length && !conversations.length" class="history-state">新建工作区后即可开始任务</p>
      </template>
    </div>
  </aside>
</template>

<script setup>
import { computed, defineComponent, h, ref, watch } from 'vue'
import { Folder, FolderPlus, Plus, Trash2, X } from 'lucide-vue-next'

const props = defineProps({
  conversations: { type: Array, default: () => [] }, projects: { type: Array, default: () => [] },
  activeId: { type: String, default: '' }, selectedProjectId: { type: String, default: null },
  loading: { type: Boolean, default: false }, workspaceCreating: { type: Boolean, default: false },
  workspaceError: { type: String, default: '' }, open: { type: Boolean, default: false },
  title: { type: String, default: '情感分析大师' }, mark: { type: String, default: 'AI' }, theme: { type: String, default: 'love' }
})
const emit = defineEmits(['create', 'create-workspace', 'select-project', 'select', 'delete', 'close'])
const workspaceFormOpen = ref(false)
const workspaceName = ref('')
const directoryName = ref('')
const projectGroups = computed(() => props.projects.map(project => ({ ...project, items: props.conversations.filter(item => item.projectId === project.id) })))
const unboundConversations = computed(() => props.conversations.filter(item => !item.projectId))
const formatUpdatedAt = value => {
  const date = new Date(value); const today = new Date()
  return date.toDateString() === today.toDateString() ? date.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' }) : date.toLocaleDateString('zh-CN', { month: 'numeric', day: 'numeric' })
}
const ConversationRows = defineComponent({
  props: { items: Array, activeId: String }, emits: ['select', 'delete'],
  setup(rowProps, { emit: rowEmit }) {
    return () => rowProps.items?.map(item => h('div', { class: 'conversation-row' }, [
      h('button', { class: ['conversation-item', { active: item.id === rowProps.activeId }], type: 'button', title: item.title, onClick: () => rowEmit('select', item) }, [
        h('span', { class: 'conversation-title' }, item.title), h('span', { class: 'conversation-time' }, formatUpdatedAt(item.updatedAt))
      ]),
      h('button', { class: 'icon-button delete-button', type: 'button', title: '删除会话', 'aria-label': '删除会话', onClick: event => { event.stopPropagation(); rowEmit('delete', item) } }, [h(Trash2, { size: 14 })])
    ]))
  }
})
const closeWorkspaceForm = () => { workspaceFormOpen.value = false; workspaceName.value = ''; directoryName.value = '' }
const submitWorkspace = () => {
  if (workspaceName.value && directoryName.value && !props.workspaceCreating) emit('create-workspace', { name: workspaceName.value, directoryName: directoryName.value })
}
watch(() => props.projects.length, (current, previous) => { if (current > previous) closeWorkspaceForm() })
</script>

<style scoped>
.conversation-sidebar { width: 280px; box-sizing: border-box; flex: 0 0 280px; display: flex; height: 100vh; min-height: 0; flex-direction: column; overflow: hidden; padding: 18px 13px 12px; border-right: 1px solid rgba(205, 219, 241, .92); background: rgba(246, 250, 255, .88); backdrop-filter: var(--sk-blur); }
.sidebar-top, .workspace-heading, .workspace-row, :deep(.conversation-row), .form-actions { display: flex; align-items: center; }
.sidebar-top { justify-content: space-between; padding: 4px 8px 16px; }
.brand { display: flex; align-items: center; gap: 9px; color: var(--sk-label); font-size: 15px; font-weight: 700; }
.brand-mark { display: grid; width: 28px; height: 28px; place-items: center; border-radius: 8px; background: var(--zwx-primary-soft); color: var(--zwx-primary); font-size: 10px; }
.brand-mark.travel { font-size: 15px; }
.icon-button { display: grid; place-items: center; border: 0; background: transparent; color: var(--sk-label-3); }
.new-conversation { display: flex; width: 100%; height: 40px; align-items: center; gap: 8px; border: 1px solid #cfe0ff; border-radius: 8px; padding: 0 12px; background: #eef5ff; color: #24406d; font-size: 14px; font-weight: 650; }
.new-conversation:hover { background: #e1edff; border-color: #b7d1ff; }
.workspace-heading { justify-content: space-between; margin: 20px 8px 7px; color: var(--sk-label-3); font-size: 11px; font-weight: 700; }
.add-workspace, .close-button { width: 28px; height: 28px; border-radius: 6px; }
.add-workspace:hover, .close-button:hover { background: var(--sk-fill); color: var(--sk-label); }
.workspace-form { margin: 2px 0 10px; padding: 10px; border: 1px solid #dbe5f3; border-radius: 8px; background: #fff; }
.workspace-form label { display: block; margin-bottom: 8px; }
.workspace-form label span { display: block; margin-bottom: 4px; color: var(--sk-label-3); font-size: 11px; }
.workspace-form input { width: 100%; box-sizing: border-box; height: 34px; border: 1px solid #ccd8e8; border-radius: 6px; padding: 0 9px; color: var(--sk-label); outline: none; }
.workspace-form input:focus { border-color: var(--zwx-primary); box-shadow: 0 0 0 3px var(--zwx-primary-ring); }
.form-error { margin: 4px 0 8px; color: var(--sk-red); font-size: 11px; }
.form-actions { justify-content: flex-end; gap: 6px; }
.text-button, .submit-button { height: 30px; border-radius: 6px; padding: 0 11px; font-size: 12px; }
.text-button { border: 0; background: transparent; color: var(--sk-label-2); }
.submit-button { border: 0; background: var(--zwx-primary); color: #fff; }
.submit-button:disabled { opacity: .55; }
.conversation-list { min-height: 0; flex: 1; overflow-y: auto; overscroll-behavior: contain; padding-bottom: 12px; }
.workspace-group { margin-bottom: 5px; }
.workspace-row { width: 100%; min-width: 0; gap: 8px; border: 0; border-radius: 7px; padding: 8px 9px; background: transparent; color: var(--sk-label-2); text-align: left; }
.workspace-row:hover, .workspace-row.active { background: rgba(218, 231, 251, .68); color: var(--sk-label); }
.workspace-copy { display: flex; min-width: 0; flex: 1; flex-direction: column; gap: 1px; }
.workspace-copy strong, .workspace-copy small { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.workspace-copy strong { font-size: 12px; font-weight: 700; }.workspace-copy small { color: var(--sk-label-3); font-size: 10px; }.task-count { min-width: 20px; color: var(--sk-label-3); font-size: 10px; text-align: right; }
:deep(.conversation-row) { position: relative; margin: 1px 0 1px 17px; }
:deep(.conversation-item) { display: grid; width: 100%; min-width: 0; grid-template-columns: minmax(0, 1fr) auto; align-items: center; gap: 8px; border: 0; border-radius: 7px; padding: 8px 28px 8px 10px; background: transparent; color: var(--sk-label-2); text-align: left; }
:deep(.conversation-item:hover) { background: var(--sk-fill); }:deep(.conversation-item.active) { background: #316fe8; color: #fff; }
:deep(.conversation-title) { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: 12px; }:deep(.conversation-time) { color: var(--sk-label-3); font-size: 10px; }:deep(.conversation-item.active .conversation-time) { color: rgba(255, 255, 255, .72); }
:deep(.delete-button) { position: absolute; right: 4px; width: 22px; height: 22px; border-radius: 5px; opacity: 0; }:deep(.conversation-row:hover .delete-button), :deep(.conversation-item.active + .delete-button) { opacity: 1; }:deep(.delete-button:hover) { background: rgba(255, 255, 255, .9); color: var(--sk-red); }
.history-state { margin: 18px 10px; color: var(--sk-label-3); font-size: 12px; }.close-button { display: none; }
@media (max-width: 720px) { .conversation-sidebar { position: fixed; z-index: 30; inset: 0 auto 0 0; width: min(86vw, 320px); transform: translateX(-100%); transition: transform .24s cubic-bezier(.32, .72, 0, 1); box-shadow: var(--sk-shadow-pop); background: rgba(246, 250, 255, .97); }.conversation-sidebar.open { transform: translateX(0); }.close-button { display: grid; } }
</style>
