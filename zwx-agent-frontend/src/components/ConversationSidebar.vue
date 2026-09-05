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
/* macOS 窗口侧栏：半透明材质 + 内嵌分组列表 */
.conversation-sidebar {
  width: 256px;
  box-sizing: border-box;
  flex: 0 0 256px;
  display: flex;
  height: 100vh;
  min-height: 0;
  flex-direction: column;
  overflow: hidden;
  padding: 16px 12px 10px;
  border-right: 1px solid var(--sk-separator);
  background: rgba(243, 243, 247, 0.82);
  backdrop-filter: var(--sk-blur);
  -webkit-backdrop-filter: var(--sk-blur);
}

.sidebar-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 4px 8px 16px;
}

.brand {
  display: flex;
  align-items: center;
  gap: 9px;
  color: var(--sk-label);
  font-size: 15px;
  font-weight: 700;
  letter-spacing: -0.01em;
}

.brand-mark {
  display: grid;
  width: 28px;
  height: 28px;
  place-items: center;
  border-radius: 9px;
  background: var(--zwx-primary-soft);
  color: var(--zwx-primary);
  font-size: 10px;
  font-weight: 700;
}

.brand-mark.travel { font-size: 15px; }

.new-conversation {
  display: flex;
  width: 100%;
  height: 40px;
  align-items: center;
  justify-content: flex-start;
  gap: 8px;
  border: 0;
  border-radius: 11px;
  padding: 0 12px;
  background: var(--sk-fill);
  color: var(--sk-label);
  font-size: 14px;
  font-weight: 600;
}

.new-conversation:hover { background: var(--sk-fill-strong); }

.new-conversation:active { transform: scale(0.98); }

.new-conversation span { color: var(--zwx-primary); font-size: 19px; font-weight: 500; }

.history-heading {
  margin: 24px 10px 8px;
  color: var(--sk-label-3);
  font-size: 11px;
  font-weight: 650;
  letter-spacing: 0.05em;
}

.conversation-list { min-height: 0; flex: 1; overflow-y: auto; overscroll-behavior: contain; padding-bottom: 12px; }

.conversation-row { position: relative; display: flex; align-items: center; margin: 2px 0; }

.conversation-item {
  display: grid;
  width: 100%;
  min-width: 0;
  grid-template-columns: minmax(0, 1fr) auto;
  align-items: center;
  gap: 8px;
  border: 0;
  border-radius: 9px;
  padding: 10px 30px 10px 11px;
  background: transparent;
  color: var(--sk-label-2);
  text-align: left;
}

.conversation-item:hover { background: var(--sk-fill); color: var(--sk-label); }

.conversation-item.active { background: var(--zwx-primary); color: #fff; }

.conversation-title { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: 13px; }

.conversation-item.active .conversation-time { color: rgba(255, 255, 255, 0.72); }
.conversation-time { color: var(--sk-label-3); font-size: 11px; }

.delete-button, .close-button {
  display: grid;
  place-items: center;
  border: 0;
  background: transparent;
  color: var(--sk-label-3);
}

.delete-button {
  position: absolute;
  right: 6px;
  width: 22px;
  height: 22px;
  border-radius: 50%;
  opacity: 0;
  font-size: 17px;
  transition: opacity 0.15s ease, color 0.15s ease, background-color 0.15s ease;
}

.conversation-row:hover .delete-button, .conversation-item.active + .delete-button { opacity: 1; }

.delete-button:hover { background: rgba(255, 255, 255, 0.85); color: var(--sk-red); }

.delete-button:hover, .close-button:hover { color: var(--sk-label); }

.close-button { display: none; width: 30px; height: 30px; font-size: 24px; }

.history-state { margin: 18px 10px; color: var(--sk-label-3); font-size: 13px; }

@media (max-width: 720px) {
  .conversation-sidebar {
    position: fixed;
    z-index: 30;
    inset: 0 auto 0 0;
    width: min(82vw, 300px);
    transform: translateX(-100%);
    transition: transform 0.24s cubic-bezier(0.32, 0.72, 0, 1);
    box-shadow: var(--sk-shadow-pop);
    background: rgba(246, 246, 248, 0.94);
  }

  .conversation-sidebar.open { transform: translateX(0); }

  .close-button { display: grid; }
}
</style>
