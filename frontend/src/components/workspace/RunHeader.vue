<template>
  <header class="run-header">
    <div class="run-heading">
      <span class="run-kicker">
        当前任务
        <em v-if="run?.id">#{{ shortId }}</em>
      </span>
      <h1>{{ goal || '描述一个目标，我会拆成计划并执行' }}</h1>
      <div class="run-meta">
        <span class="status-chip" :class="tone"><i aria-hidden="true"></i>{{ statusLabel }}</span>
        <span v-if="run?.plan?.length" class="meta-chip">{{ doneCount }}/{{ run.plan.length }} 子任务</span>
        <span v-if="run?.createdAt" class="meta-chip">{{ formattedTime }}</span>
        <span v-if="elapsedLabel" class="meta-chip" :class="{ running: run?.status === 'RUNNING' }">{{ elapsedLabel }}</span>
        <span v-if="run?.verification?.status === 'unverified'" class="meta-chip warn">未验收</span>
        <span v-if="run?.resumeCount" class="meta-chip">已恢复 {{ run.resumeCount }} 次</span>
      </div>
    </div>
    <div class="run-actions">
      <button v-if="run?.id" type="button" class="action" :disabled="refreshing" title="刷新任务状态" @click="emit('refresh')">
        <RotateCw :size="13" :class="{ spin: refreshing }" />刷新
      </button>
      <button v-if="run?.canResume" type="button" class="action primary" @click="emit('resume')">
        <Play :size="14" />继续任务
      </button>
      <button v-if="run?.canCancel" type="button" class="action danger" @click="emit('cancel')">
        <Square :size="13" fill="currentColor" />停止
      </button>
      <button v-if="hasConversation" type="button" class="action" @click="emit('open-conversation')">
        <MessageSquare :size="14" />对话
      </button>
    </div>
  </header>
</template>

<script setup>
import { computed, onBeforeUnmount, ref } from 'vue'
import { MessageSquare, Play, RotateCw, Square } from 'lucide-vue-next'

const props = defineProps({
  run: { type: Object, default: null },
  goal: { type: String, default: '' },
  hasConversation: { type: Boolean, default: false },
  refreshing: { type: Boolean, default: false }
})
const emit = defineEmits(['resume', 'cancel', 'open-conversation', 'refresh'])

const statusLabel = computed(() => ({
  RUNNING: '执行中',
  COMPLETED: '已完成',
  INTERRUPTED: '已中断',
  FAILED: '执行失败'
}[props.run?.status] || '就绪'))

const tone = computed(() => ({
  RUNNING: 'running',
  COMPLETED: 'done',
  INTERRUPTED: 'warn',
  FAILED: 'error'
}[props.run?.status] || 'idle'))

const doneCount = computed(() => (props.run?.taskResults || []).length)
const shortId = computed(() => (props.run?.id || '').slice(0, 8))
const now = ref(Date.now())
const clock = setInterval(() => { now.value = Date.now() }, 1000)
onBeforeUnmount(() => clearInterval(clock))

const elapsedLabel = computed(() => {
  if (!props.run?.createdAt) return ''
  const start = new Date(props.run.createdAt).getTime()
  if (Number.isNaN(start)) return ''
  const end = props.run.status === 'RUNNING' ? now.value : new Date(props.run.updatedAt || Date.now()).getTime()
  const totalSeconds = Math.max(0, Math.floor((end - start) / 1000))
  const minutes = Math.floor(totalSeconds / 60)
  const seconds = totalSeconds % 60
  return props.run.status === 'RUNNING'
    ? `已运行 ${minutes}:${String(seconds).padStart(2, '0')}`
    : `用时 ${minutes}:${String(seconds).padStart(2, '0')}`
})

const formattedTime = computed(() => {
  if (!props.run?.createdAt) return ''
  const date = new Date(props.run.createdAt)
  if (Number.isNaN(date.getTime())) return ''
  return date.toLocaleString('zh-CN', { month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit' })
})
</script>

<style scoped>
.run-header { display: flex; align-items: flex-start; justify-content: space-between; gap: 18px; }

.run-heading { min-width: 0; }
.run-kicker { display: flex; align-items: center; gap: 7px; color: var(--sk-label-3); font-size: 10.5px; font-weight: 650; letter-spacing: 0.08em; }
.run-kicker em { color: var(--sk-label-3); font-style: normal; font-weight: 500; letter-spacing: 0; }

.run-heading h1 {
  display: -webkit-box;
  overflow: hidden;
  margin: 7px 0 0;
  color: var(--sk-label);
  font-size: clamp(20px, 2.4vw, 27px);
  font-weight: 800;
  line-height: 1.32;
  letter-spacing: -0.02em;
  -webkit-box-orient: vertical;
  -webkit-line-clamp: 2;
}

.run-meta { display: flex; flex-wrap: wrap; align-items: center; gap: 6px; margin-top: 10px; }

.status-chip, .meta-chip {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  border-radius: 999px;
  padding: 3px 9px;
  background: var(--sk-fill);
  color: var(--sk-label-2);
  font-size: 10.5px;
  font-weight: 600;
}

.status-chip i { width: 6px; height: 6px; border-radius: 50%; background: currentColor; }
.status-chip.running { background: var(--zwx-primary-soft); color: var(--zwx-primary); }
.status-chip.running i { animation: run-blink 1.1s ease-in-out infinite; }
.status-chip.done { background: rgba(34, 197, 94, 0.12); color: #15803d; }
.status-chip.warn { background: rgba(245, 158, 11, 0.14); color: #b45309; }
.status-chip.error { background: rgba(239, 68, 68, 0.12); color: #b91c1c; }
.meta-chip.warn { background: rgba(245, 158, 11, 0.14); color: #b45309; }
.meta-chip.running { background: var(--zwx-primary-soft); color: var(--zwx-primary); }

.run-actions { display: flex; flex: 0 0 auto; flex-wrap: wrap; gap: 7px; }

.action {
  display: inline-flex;
  height: 32px;
  align-items: center;
  gap: 5px;
  border: 1px solid var(--sk-separator);
  border-radius: 9px;
  padding: 0 11px;
  background: #fff;
  color: var(--sk-label-2);
  font-size: 11.5px;
  font-weight: 600;
  cursor: pointer;
  transition: border-color 0.16s ease, color 0.16s ease, background 0.16s ease;
}

.action:hover:not(:disabled) { border-color: var(--zwx-primary-ring); color: var(--zwx-primary); }
.action:disabled { opacity: 0.6; cursor: default; }
.action .spin { animation: action-spin 0.9s linear infinite; }
@keyframes action-spin { to { transform: rotate(360deg); } }
.action.primary { border-color: transparent; background: var(--zwx-primary); color: #fff; }
.action.primary:hover { filter: brightness(1.05); }
.action.danger { border-color: rgba(255, 59, 48, 0.24); background: rgba(255, 59, 48, 0.06); color: var(--sk-red); }

@keyframes run-blink { 0%, 100% { opacity: 0.35; } 50% { opacity: 1; } }

@media (prefers-reduced-motion: reduce) {
  .status-chip.running i, .action .spin { animation: none; opacity: 1; }
}

@media (max-width: 720px) {
  .run-header { flex-direction: column; gap: 10px; }
  .run-actions { width: 100%; }
}
</style>
