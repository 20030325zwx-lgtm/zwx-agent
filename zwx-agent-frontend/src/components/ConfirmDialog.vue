<template>
  <Teleport to="body">
    <Transition name="confirm-fade">
      <div v-if="open" class="confirm-backdrop" @click.self="cancel">
        <section ref="card" class="confirm-card" role="dialog" aria-modal="true" :aria-label="title" tabindex="-1" @keydown.esc.prevent="cancel" @keydown.enter.prevent="handleEnter">
          <div class="confirm-icon" :class="{ danger }" aria-hidden="true">!</div>
          <div class="confirm-body">
            <h3>{{ title }}</h3>
            <p>{{ message }}</p>
          </div>
          <div class="confirm-actions">
            <button type="button" class="confirm-button secondary" @click="cancel">{{ cancelText }}</button>
            <button type="button" class="confirm-button" :class="{ danger }" @click="confirm">{{ confirmText }}</button>
          </div>
        </section>
      </div>
    </Transition>
  </Teleport>
</template>

<script setup>
import { nextTick, ref, watch } from 'vue'

const props = defineProps({
  open: { type: Boolean, default: false },
  title: { type: String, default: '确认操作' },
  message: { type: String, default: '' },
  confirmText: { type: String, default: '确定' },
  cancelText: { type: String, default: '取消' },
  danger: { type: Boolean, default: true }
})
const emit = defineEmits(['confirm', 'cancel'])
const card = ref(null)

const cancel = () => emit('cancel')
const confirm = () => emit('confirm')
const handleEnter = event => {
  if (event.target === card.value) confirm()
}

watch(() => props.open, async open => {
  if (!open) return
  await nextTick()
  card.value?.focus()
})
</script>

<style scoped>
.confirm-backdrop {
  position: fixed;
  inset: 0;
  z-index: 1200;
  display: grid;
  place-items: center;
  padding: 24px;
  background: rgba(16, 24, 40, 0.36);
  backdrop-filter: blur(8px);
}
.confirm-card {
  display: grid;
  grid-template-columns: 42px minmax(0, 1fr);
  gap: 14px 16px;
  width: min(460px, 100%);
  padding: 22px;
  border: 1px solid rgba(255, 255, 255, 0.72);
  border-radius: 20px;
  background: #fff;
  box-shadow: 0 28px 70px rgba(15, 23, 42, 0.24);
  outline: none;
}
.confirm-icon {
  display: grid;
  width: 42px;
  height: 42px;
  place-items: center;
  border-radius: 13px;
  background: #eef4ff;
  color: #2563eb;
  font-size: 20px;
  font-weight: 800;
}
.confirm-icon.danger { background: #fff1f0; color: #d9382c; }
.confirm-body h3 { margin: 1px 0 8px; font-size: 17px; color: #101828; }
.confirm-body p { margin: 0; color: #667085; font-size: 13.5px; line-height: 1.65; }
.confirm-actions {
  grid-column: 1 / -1;
  display: flex;
  justify-content: flex-end;
  gap: 10px;
  margin-top: 4px;
}
.confirm-button {
  min-width: 78px;
  padding: 9px 18px;
  border: 0;
  border-radius: 10px;
  background: #1f6feb;
  color: #fff;
  font-size: 13.5px;
  font-weight: 650;
  cursor: pointer;
  transition: filter .18s ease, background .18s ease;
}
.confirm-button:hover { filter: brightness(.95); }
.confirm-button.secondary { background: #f2f4f7; color: #344054; }
.confirm-button.danger { background: #d9382c; }
.confirm-fade-enter-active, .confirm-fade-leave-active { transition: opacity .18s ease; }
.confirm-fade-enter-from, .confirm-fade-leave-to { opacity: 0; }
@media (max-width: 520px) {
  .confirm-card { grid-template-columns: 1fr; padding: 20px; }
  .confirm-icon { display: none; }
  .confirm-actions { display: grid; grid-template-columns: 1fr 1fr; }
}
</style>
