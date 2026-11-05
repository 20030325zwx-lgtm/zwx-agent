<template>
  <div class="act-drawer" role="dialog" aria-modal="true" aria-label="生成活动" @keydown.esc.stop="close">
    <div class="act-backdrop" @click="close" />
    <aside class="act-panel">
      <header class="act-head">
        <div>
          <h2>生成活动</h2>
          <p class="act-sub">进行中与需要处理的事件；完整历史在生成记录。</p>
        </div>
        <button ref="closeBtn" type="button" class="act-close" aria-label="关闭" @click="close">
          <X :size="16" />
        </button>
      </header>

      <div class="act-body">
        <AsterLoader v-if="loading" size="18" />
        <template v-else>
          <section v-if="attentionJobs.length">
            <h3 class="act-group">需要处理</h3>
            <ul class="act-list">
              <li v-for="job in attentionJobs" :key="job.jobId">
                <span class="cr-status" :class="statusMeta(job.status).tone">{{ statusMeta(job.status).label }}</span>
                <button type="button" class="act-item" @click="open(job)">
                  <strong>{{ job.projectName || '未归档任务' }}</strong>
                  <span>{{ job.prompt ? truncate(job.prompt, 36) : '查看详情' }}</span>
                </button>
                <span class="act-time">{{ formatTime(job.createdAt) }}</span>
              </li>
            </ul>
          </section>
          <section v-if="runningJobs.length">
            <h3 class="act-group">进行中</h3>
            <ul class="act-list">
              <li v-for="job in runningJobs" :key="job.jobId">
                <span class="cr-status" :class="statusMeta(job.status).tone">{{ statusMeta(job.status).label }}</span>
                <button type="button" class="act-item" @click="open(job)">
                  <strong>{{ job.projectName || '未归档任务' }}</strong>
                  <span>{{ job.prompt ? truncate(job.prompt, 36) : '生成任务' }}</span>
                </button>
                <span class="act-time">{{ formatTime(job.createdAt) }}</span>
              </li>
            </ul>
          </section>
          <p v-if="!attentionJobs.length && !runningJobs.length" class="cr-hint act-clear">
            没有进行中的生成，也没有待处理事件。
          </p>
        </template>
      </div>

      <footer class="act-foot">
        <router-link to="/creative/tasks" class="act-all" @click="close">全部生成记录</router-link>
      </footer>
    </aside>
  </div>
</template>

<script setup>
import { nextTick, onMounted, ref } from 'vue'
import AsterLoader from '../ui/AsterLoader.vue'
import { useRouter } from 'vue-router'
import { X } from 'lucide-vue-next'
import { listCreativeJobs } from '../../api'
import { ACTIVE_STATUSES, formatTime, statusMeta } from '../../views/creative/shared'

const emit = defineEmits(['close'])
const router = useRouter()

const loading = ref(true)
const jobs = ref([])
const closeBtn = ref(null)

const runningJobs = ref([])
const attentionJobs = ref([])

const ATTENTION = new Set(['FAILED', 'INGEST_FAILED', 'RECONCILING'])

function truncate(text, max) {
  const value = String(text || '')
  return value.length > max ? `${value.slice(0, max)}…` : value
}

async function load() {
  loading.value = true
  try {
    const data = await listCreativeJobs({ limit: 20 })
    const items = data.items || []
    runningJobs.value = items.filter(job => ACTIVE_STATUSES.has(job.status) && !ATTENTION.has(job.status)).slice(0, 6)
    attentionJobs.value = items.filter(job => ATTENTION.has(job.status)).slice(0, 6)
  } catch (error) {
    runningJobs.value = []
    attentionJobs.value = []
  } finally {
    loading.value = false
  }
}

function open(job) {
  close()
  router.push(job.projectId
    ? `/creative/projects/${job.projectId}?tab=tasks&jobId=${job.jobId}`
    : '/creative/tasks')
}

function close() {
  emit('close')
}

onMounted(async () => {
  await load()
  await nextTick()
  closeBtn.value?.focus()
})
</script>

<style scoped src="../../views/creative/creative.css"></style>

<style scoped>
.act-drawer { position: fixed; inset: 0; z-index: 80; }
.act-backdrop { position: absolute; inset: 0; background: rgba(12, 12, 14, 0.4); animation: act-fade var(--cr-dur-fast, 140ms) var(--cr-ease, ease); }
@keyframes act-fade { from { opacity: 0; } to { opacity: 1; } }

.act-panel {
  position: absolute;
  top: 0;
  right: 0;
  bottom: 0;
  width: min(380px, 92vw);
  display: flex;
  flex-direction: column;
  background: var(--cr-surface, var(--wb-surface));
  border-left: 1px solid var(--cr-border, var(--wb-border));
  box-shadow: var(--wb-shadow-lg);
  animation: act-slide var(--cr-dur-normal, 220ms) var(--cr-ease, ease);
}
@keyframes act-slide { from { transform: translateX(16px); opacity: 0; } to { transform: none; opacity: 1; } }

.act-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  padding: 18px 18px 12px;
  border-bottom: 1px solid var(--cr-border, var(--wb-border));
}
.act-head h2 { margin: 0; font-size: 17px; color: var(--cr-text, var(--wb-text)); }
.act-sub { margin: 3px 0 0; font-size: 12px; color: var(--cr-text-2, var(--wb-text-2)); }

.act-close {
  display: grid;
  place-items: center;
  width: 30px;
  height: 30px;
  flex: 0 0 30px;
  border: 0;
  border-radius: var(--cr-radius-ctl, 10px);
  background: transparent;
  color: var(--cr-text-2, var(--wb-text-2));
  cursor: pointer;
}
.act-close:hover { background: var(--wb-fill); color: var(--cr-text, var(--wb-text)); }

.act-body { flex: 1; overflow-y: auto; padding: 12px 18px; }
.act-group {
  margin: 12px 0 6px;
  font-size: 12px;
  font-weight: 600;
  letter-spacing: 0.03em;
  color: var(--cr-text-2, var(--wb-text-2));
}
.act-list { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 6px; }
.act-list li { display: flex; align-items: center; gap: 8px; }
.act-item {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 1px;
  padding: 7px 10px;
  border: 1px solid var(--cr-border, var(--wb-border));
  border-radius: var(--cr-radius-ctl, 10px);
  background: transparent;
  text-align: left;
  cursor: pointer;
}
.act-item:hover { background: var(--wb-fill); }
.act-item strong { font-size: 12.5px; color: var(--cr-text, var(--wb-text)); font-weight: 600; }
.act-item span { font-size: 11.5px; color: var(--cr-text-2, var(--wb-text-2)); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; max-width: 100%; }
.act-time { flex: 0 0 auto; font-size: 11px; color: var(--wb-muted); }
.act-clear { padding: 18px 0; }

.act-foot {
  padding: 12px 18px;
  border-top: 1px solid var(--cr-border, var(--wb-border));
}
.act-all { font-size: 13px; color: var(--cr-accent, var(--wb-brand)); text-decoration: none; font-weight: 550; }
</style>
