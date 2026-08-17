<template>
  <aside class="conversation-sidebar" :class="{ open }">
    <div class="sidebar-top">
      <div class="brand"><span class="brand-mark" :class="theme">{{ mark }}</span><span>{{ title }}</span></div>
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
  open: { type: Boolean, default: false },
  title: { type: String, default: '情感分析大师' },
  mark: { type: String, default: 'AI' },
  theme: { type: String, default: 'love' }
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
.conversation-sidebar { width: 252px; box-sizing:border-box; flex: 0 0 252px; display: flex; height: 100vh; min-height:0; flex-direction: column; overflow:hidden; padding: 14px 10px; border-right: 1px solid #eaeaea; background: #f8f8f8; }
.sidebar-top { display: flex; align-items: center; justify-content: space-between; padding: 6px 8px 17px; }.brand { display: flex; align-items: center; gap: 9px; color: #1f1f1f; font-size: 16px; font-weight: 650; }.brand-mark { display: grid; width: 27px; height: 27px; place-items: center; border-radius: 8px; background: #fceff2; color: #d65070; font-size: 10px; font-weight: 700; }.brand-mark.travel { background:#ecfdf5; color:#0f9f6e; font-size:16px; }
.new-conversation { display: flex; width: 100%; height: 40px; align-items: center; justify-content: flex-start; gap: 8px; border: 0; border-radius: 8px; padding: 0 12px; background: #ededed; color: #262626; font-size: 14px; font-weight: 600; }.new-conversation:hover { background: #e5e5e5; }.new-conversation span { font-size: 19px; font-weight: 400; }
.history-heading { margin: 26px 9px 9px; color: #989898; font-size: 12px; }.conversation-list { min-height:0; flex:1; overflow-y: auto; overscroll-behavior:contain; padding-bottom: 12px; }.conversation-row { position: relative; display: flex; align-items: center; margin: 2px 0; }.conversation-item { display: grid; width: 100%; min-width: 0; grid-template-columns: minmax(0, 1fr) auto; align-items: center; gap: 8px; border: 0; border-radius: 7px; padding: 10px 29px 10px 10px; background: transparent; color: #565656; text-align: left; }.conversation-item:hover, .conversation-item.active { background: #e9e9e9; color: #222; }.conversation-title { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: 13px; }.conversation-time { color: #a7a7a7; font-size: 11px; }.delete-button, .close-button { display: grid; place-items: center; border: 0; background: transparent; color: #999; }.delete-button { position: absolute; right: 5px; width: 24px; height: 24px; opacity: 0; font-size: 18px; }.conversation-row:hover .delete-button, .conversation-item.active + .delete-button { opacity: 1; }.delete-button:hover, .close-button:hover { color: #333; }.close-button { display: none; width: 30px; height: 30px; font-size: 24px; }.history-state { margin: 18px 10px; color: #a0a0a0; font-size: 13px; }
@media (max-width: 720px) { .conversation-sidebar { position: fixed; z-index: 30; inset: 0 auto 0 0; width: min(82vw, 300px); transform: translateX(-100%); transition: transform .2s ease; box-shadow: 10px 0 24px rgba(0,0,0,.1); }.conversation-sidebar.open { transform: translateX(0); }.close-button { display: grid; } }
</style>
