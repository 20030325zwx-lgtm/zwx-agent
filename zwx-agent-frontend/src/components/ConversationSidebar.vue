<template>
  <aside class="conversation-sidebar" :class="{ open }">
    <div class="sidebar-top">
      <div class="brand"><span class="brand-mark">AI</span><span>情感分析大师</span></div>
      <button class="close-button" type="button" aria-label="关闭历史会话" @click="$emit('close')">×</button>
    </div>
    <button class="new-conversation" type="button" @click="$emit('create')"><span aria-hidden="true">＋</span>新对话</button>
    <div class="history-heading">历史会话</div>
    <div class="conversation-list" aria-label="历史会话">
      <p v-if="loading" class="history-state">正在加载...</p>
      <p v-else-if="!conversations.length" class="history-state">还没有历史对话</p>
      <div v-for="conversation in conversations" :key="conversation.id" class="conversation-row">
        <button class="conversation-item" :class="{ active: conversation.id === activeId }" type="button" :title="conversation.title" @click="$emit('select', conversation)">
          <span class="conversation-title">{{ conversation.title }}</span>
          <span class="conversation-time">{{ formatUpdatedAt(conversation.updatedAt) }}</span>
        </button>
        <button class="delete-button" type="button" title="删除会话" aria-label="删除会话" @click.stop="$emit('delete', conversation)">×</button>
      </div>
    </div>
  </aside>
</template>

<script setup>
defineProps({
  conversations: { type: Array, default: () => [] },
  activeId: { type: String, default: '' },
  loading: { type: Boolean, default: false },
  open: { type: Boolean, default: false }
})

defineEmits(['create', 'select', 'delete', 'close'])

const formatUpdatedAt = (value) => {
  const date = new Date(value)
  const today = new Date()
  return date.toDateString() === today.toDateString()
    ? date.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit' })
    : date.toLocaleDateString('zh-CN', { month: 'numeric', day: 'numeric' })
}
</script>

<style scoped>
.conversation-sidebar { width: 278px; flex: 0 0 278px; display: flex; flex-direction: column; min-height: 100vh; padding: 18px 12px; border-right: 1px solid #e7e4e5; background: #fffdfd; }
.sidebar-top { display: flex; align-items: center; justify-content: space-between; padding: 0 8px 18px; }
.brand { display: flex; align-items: center; gap: 10px; color: #252124; font-size: 18px; font-weight: 700; }
.brand-mark { display: grid; width: 30px; height: 30px; place-items: center; border: 1px solid #f3b5c4; border-radius: 7px; background: #fff3f6; color: #bc3f62; font-size: 11px; }
.new-conversation { display: flex; width: 100%; height: 42px; align-items: center; justify-content: center; gap: 7px; border: 1px solid #e9a8b9; border-radius: 7px; background: #fff7f8; color: #a72d4f; font-size: 14px; font-weight: 600; }
.new-conversation:hover { background: #ffedf1; }
.new-conversation span { font-size: 20px; font-weight: 400; }
.history-heading { margin: 24px 8px 8px; color: #8d8588; font-size: 12px; }
.conversation-list { overflow-y: auto; padding-bottom: 12px; }
.conversation-row { position: relative; display: flex; align-items: center; margin: 2px 0; }
.conversation-item { display: grid; width: 100%; min-width: 0; grid-template-columns: minmax(0, 1fr) auto; align-items: center; gap: 8px; border: 0; border-radius: 6px; padding: 10px 30px 10px 10px; background: transparent; color: #463e41; text-align: left; }
.conversation-item:hover, .conversation-item.active { background: #f8ecef; color: #7f2440; }
.conversation-title { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: 14px; }
.conversation-time { color: #a39a9d; font-size: 11px; }
.delete-button, .close-button { display: grid; place-items: center; border: 0; background: transparent; color: #9d9598; }
.delete-button { position: absolute; right: 7px; width: 24px; height: 24px; opacity: 0; font-size: 20px; }
.conversation-row:hover .delete-button, .conversation-item.active + .delete-button { opacity: 1; }
.delete-button:hover, .close-button:hover { color: #b1284e; }
.close-button { display: none; width: 30px; height: 30px; font-size: 24px; }
.history-state { margin: 18px 10px; color: #a39a9d; font-size: 13px; }
@media (max-width: 720px) { .conversation-sidebar { position: fixed; z-index: 30; inset: 0 auto 0 0; width: min(82vw, 300px); transform: translateX(-100%); transition: transform .2s ease; box-shadow: 10px 0 24px rgba(46, 28, 34, .12); } .conversation-sidebar.open { transform: translateX(0); } .close-button { display: grid; } }
</style>
