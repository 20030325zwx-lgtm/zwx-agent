<template>
  <div class="sched-page">
    <div class="sched-inner">
      <header class="sched-head">
        <div>
          <h1>定时任务</h1>
          <p>到点拉起智能体，按你的指令自主干活；产出与运行记录都在，等你回来看</p>
        </div>
        <button type="button" class="new-btn" @click="openCreate"><Plus :size="15" /> 新建任务</button>
      </header>

      <div v-if="notice" class="page-notice" :class="noticeTone" role="status">{{ notice }}</div>

      <!-- 待确认置顶：无人值守遇到高危操作会挂起，这里一键去批准 -->
      <section v-if="waitingFires.length" class="waiting-strip" aria-label="等待确认">
        <AlertTriangle :size="16" />
        <div class="waiting-body">
          <strong>{{ waitingFires.length }} 次运行在等你确认</strong>
          <span>{{ waitingFires.map(f => f.taskName).join('、') }}——运行因高危操作暂停，批准后继续</span>
        </div>
        <button type="button" class="confirm-btn" @click="goConfirm(waitingFires[0])">去批准</button>
      </section>

      <AsterLoader v-if="loading" size="18" />
      <p v-else-if="!tasks.length && !loading" class="sched-empty">
        还没有定时任务。点「新建任务」用一句话告诉智能体到点替你做什么。
      </p>
      <div v-else class="task-list">
        <article v-for="item in tasks" :key="item.task.id" class="task-card" :class="{ paused: !item.task.enabled }">
          <header class="task-head">
            <div class="task-title">
              <strong>{{ item.task.name }}</strong>
              <span class="perm-badge" :class="item.task.permissionMode.toLowerCase()">{{ item.task.permissionMode }}</span>
              <span v-if="!item.task.enabled" class="paused-badge">已暂停</span>
            </div>
            <div class="task-tools">
              <button type="button" class="tool-btn" title="立即运行一次" :disabled="busyId === item.task.id" @click="runNow(item)">
                <Play :size="14" /> 运行
              </button>
              <button type="button" class="tool-btn" @click="toggle(item)">
                <component :is="item.task.enabled ? Pause : Play" :size="14" />
                {{ item.task.enabled ? '暂停' : '启用' }}
              </button>
              <button type="button" class="tool-btn" @click="toggleHistory(item)">
                <History :size="14" /> {{ historyOpenId === item.task.id ? '收起' : '历史' }}
              </button>
              <button type="button" class="tool-btn danger" title="删除任务" @click="remove(item)"><Trash2 :size="14" /></button>
            </div>
          </header>
          <p class="task-instruction">{{ item.task.instruction }}</p>
          <footer class="task-foot">
            <span class="meta"><Clock :size="13" /> {{ item.scheduleText }}</span>
            <span class="meta"><Target :size="13" /> 下次 {{ formatTime(item.task.nextRunAt) || '—' }}</span>
            <span v-if="item.lastFire" class="meta" :class="'phase-' + item.lastFire.phase">
              {{ phaseLabel(item.lastFire.phase) }} · {{ formatTime(item.lastFire.startedAt) }}
            </span>
          </footer>

          <div v-if="historyOpenId === item.task.id" class="fire-history">
            <p v-if="!fires.length" class="fire-empty">还没有运行记录。</p>
            <template v-else>
              <div v-for="fire in fires" :key="fire.id" class="fire-row">
                <span class="fire-phase" :class="'phase-' + fire.phase">{{ phaseLabel(fire.phase) }}</span>
                <span class="fire-time">{{ formatTime(fire.startedAt) }}</span>
                <span class="fire-trigger">{{ fire.triggerKind === 'MANUAL' ? '手动' : '定时' }}</span>
                <span v-if="fire.error" class="fire-error" :title="fire.error">{{ fire.error }}</span>
                <a v-if="fire.conversationId" class="fire-link"
                   :href="`/super-agent?conversationId=${fire.conversationId}`" target="_blank" rel="noopener">
                  查看运行 <ExternalLink :size="12" />
                </a>
              </div>
            </template>
          </div>
        </article>
      </div>
    </div>

    <!-- 新建对话框 -->
    <div v-if="createOpen" class="dialog-backdrop" @click.self="createOpen = false">
      <div class="dialog" role="dialog" aria-modal="true" aria-label="新建定时任务">
        <h2>新建定时任务</h2>
        <label class="field">
          <span>任务名称</span>
          <input v-model="form.name" maxlength="64" placeholder="例如：每天早报整理" />
        </label>
        <label class="field">
          <span>给智能体的指令（到点它会带着这句话自主干活）</span>
          <textarea v-model="form.instruction" rows="4" maxlength="4000"
                    placeholder="例如：检查昨天的视频生成任务，把失败的原因整理成一份简短摘要"></textarea>
        </label>
        <div class="field-row">
          <label class="field">
            <span>节奏</span>
            <select v-model="form.scheduleKind" class="cr-select">
              <option value="DAILY">每天</option>
              <option value="WEEKLY">每周</option>
              <option value="ONCE">仅一次</option>
            </select>
          </label>
          <label v-if="form.scheduleKind === 'WEEKLY'" class="field">
            <span>星期</span>
            <select v-model.number="form.weeklyDow" class="cr-select">
              <option :value="1">周一</option><option :value="2">周二</option><option :value="3">周三</option>
              <option :value="4">周四</option><option :value="5">周五</option><option :value="6">周六</option>
              <option :value="7">周日</option>
            </select>
          </label>
          <label v-if="form.scheduleKind !== 'ONCE'" class="field">
            <span>时间</span>
            <input v-model="form.runTime" type="time" />
          </label>
          <label v-if="form.scheduleKind === 'ONCE'" class="field">
            <span>运行时间</span>
            <input v-model="form.onceAt" type="datetime-local" />
          </label>
        </div>
        <div class="field-row">
          <label class="field">
            <span>权限档</span>
            <select v-model="form.permissionMode" class="cr-select">
              <option value="GUARDED">GUARDED（推荐）——创作免确认，高危挂起等人</option>
              <option value="YOLO">YOLO——全部自动，慎用</option>
              <option value="EDIT">EDIT——文件产出免确认</option>
            </select>
          </label>
          <label class="field checkbox-field">
            <span>会话连续</span>
            <label class="checkbox-line">
              <input v-model="form.sessionContinuity" type="checkbox" />
              记得之前干过什么（推荐）
            </label>
          </label>
        </div>
        <p class="dialog-hint">
          无人值守运行会在 GUARDED 档下自动完成创作类操作；遇到终端、Git 提交等高危操作会挂起等你确认。
          智能体使用的生成工具按模型正常计费。
        </p>
        <p v-if="formError" class="dialog-error">{{ formError }}</p>
        <footer class="dialog-actions">
          <button type="button" class="new-btn ghost" @click="createOpen = false">取消</button>
          <button type="button" class="new-btn" :disabled="creating" @click="create">
            {{ creating ? '创建中…' : '创建任务' }}
          </button>
        </footer>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { AlertTriangle, Clock, ExternalLink, History, Pause, Play, Plus, Target, Trash2 } from 'lucide-vue-next'
import AsterLoader from '../../components/ui/AsterLoader.vue'
import {
  createAgentSchedule, deleteAgentSchedule, listAgentScheduleFires, listAgentSchedules,
  runAgentScheduleNow, setAgentScheduleEnabled
} from '../../api'

// 全局页没有 toast 体系：用页内通知条（3.2s 自动消失）
const notice = ref('')
const noticeTone = ref('success')
let noticeTimer = 0
function showToast(message, tone = 'success') {
  notice.value = message
  noticeTone.value = tone
  window.clearTimeout(noticeTimer)
  noticeTimer = window.setTimeout(() => { notice.value = '' }, 3200)
}
const tasks = ref([])
const loading = ref(true)
const createOpen = ref(false)
const creating = ref(false)
const formError = ref('')
const busyId = ref('')
const historyOpenId = ref('')
const fires = ref([])
const form = reactive({
  name: '', instruction: '', scheduleKind: 'DAILY', runTime: '09:00',
  weeklyDow: 1, onceAt: '', permissionMode: 'GUARDED', sessionContinuity: true
})

const waitingFires = computed(() => {
  const flat = []
  for (const item of tasks.value) {
    if (item.lastFire && item.lastFire.phase === 'WAITING_HUMAN') flat.push(item.lastFire)
  }
  return flat
})

function phaseLabel(phase) {
  return ({ DONE: '已完成', FAILED: '失败', WAITING_HUMAN: '等待确认', WORKING: '运行中',
    BLOCKED: '受阻', STOPPED: '已停止', QUEUED: '排队中' })[phase] || phase || '—'
}
function formatTime(iso) {
  if (!iso) return ''
  const date = new Date(iso)
  return Number.isNaN(date.getTime()) ? '' : date.toLocaleString('zh-CN', { month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit' })
}
function goConfirm(fire) {
  window.open(`/super-agent?conversationId=${fire.conversationId}`, '_blank')
}

async function load() {
  loading.value = true
  try {
    tasks.value = (await listAgentSchedules()).tasks || []
  } catch (exception) {
    showToast?.(exception.response?.data?.error || '定时任务加载失败', 'error')
  } finally { loading.value = false }
}

function openCreate() {
  form.name = ''; form.instruction = ''; form.scheduleKind = 'DAILY'
  form.runTime = '09:00'; form.onceAt = ''; formError.value = ''
  createOpen.value = true
}

async function create() {
  formError.value = ''
  if (!form.name.trim() || !form.instruction.trim()) { formError.value = '名称与指令必填'; return }
  creating.value = true
  try {
    await createAgentSchedule({
      name: form.name.trim(),
      instruction: form.instruction.trim(),
      scheduleKind: form.scheduleKind,
      runTime: form.scheduleKind === 'ONCE' ? null : form.runTime,
      weeklyDow: form.scheduleKind === 'WEEKLY' ? form.weeklyDow : null,
      onceAt: form.scheduleKind === 'ONCE' ? (form.onceAt ? new Date(form.onceAt).toISOString() : null) : null,
      permissionMode: form.permissionMode,
      sessionContinuity: form.sessionContinuity
    })
    createOpen.value = false
    showToast?.('定时任务已创建', 'success')
    await load()
  } catch (exception) {
    formError.value = exception.response?.data?.error || '创建失败'
  } finally { creating.value = false }
}

async function toggle(item) {
  busyId.value = item.task.id
  try {
    await setAgentScheduleEnabled(item.task.id, !item.task.enabled)
    await load()
  } catch (exception) {
    showToast?.(exception.response?.data?.error || '操作失败', 'error')
  } finally { busyId.value = '' }
}

async function runNow(item) {
  busyId.value = item.task.id
  try {
    await runAgentScheduleNow(item.task.id)
    showToast?.('已触发，智能体开始运行', 'success')
    historyOpenId.value = item.task.id
    await load()
    await loadFires(item.task.id)
  } catch (exception) {
    showToast?.(exception.response?.data?.error || '触发失败', 'error')
  } finally { busyId.value = '' }
}

async function remove(item) {
  if (!window.confirm(`删除定时任务「${item.task.name}」？运行记录一并删除。`)) return
  try {
    await deleteAgentSchedule(item.task.id)
    await load()
  } catch (exception) {
    showToast?.(exception.response?.data?.error || '删除失败', 'error')
  }
}

async function toggleHistory(item) {
  if (historyOpenId.value === item.task.id) { historyOpenId.value = ''; return }
  historyOpenId.value = item.task.id
  await loadFires(item.task.id)
}
async function loadFires(taskId) {
  try { fires.value = (await listAgentScheduleFires(taskId, 20)).fires || [] }
  catch { fires.value = [] }
}

onMounted(load)
</script>

<style scoped>
.sched-page { min-height: 100%; background: var(--wb-bg, #f6f7fb); color: var(--wb-text, #232735); }
.sched-inner { max-width: 960px; margin: 0 auto; padding: 28px 20px 60px; }
.sched-head { display: flex; align-items: flex-end; justify-content: space-between; gap: 14px; margin-bottom: 20px; }
.sched-head h1 { margin: 0; font-size: 20px; }
.page-notice { display: flex; align-items: center; padding: 10px 14px; margin-bottom: 12px; border-radius: 9px; font-size: 12px; background: #e8f4ec; color: #18794e; }
.page-notice.error { background: #fdeef0; color: #c94c62; }
.sched-head p { margin: 5px 0 0; color: var(--wb-text-2, #62646a); font-size: 12px; }
.new-btn { display: inline-flex; align-items: center; gap: 6px; height: 34px; padding: 0 14px; border: 0; border-radius: 8px; background: #6466d5; color: #fff; font-size: 12px; font-weight: 600; cursor: pointer; }
.new-btn.ghost { background: transparent; border: 1px solid var(--wb-border, #e3e6ed); color: var(--wb-text, #232735); }
.new-btn:disabled { opacity: .5; cursor: default; }
.waiting-strip { display: flex; align-items: center; gap: 10px; padding: 12px 14px; margin-bottom: 14px; border: 1px solid #f0d9a8; border-radius: 10px; background: #fdf6e7; color: #8a5a12; }
.waiting-body { flex: 1; display: grid; gap: 2px; font-size: 12px; }
.confirm-btn { height: 30px; padding: 0 12px; border: 0; border-radius: 7px; background: #b4690e; color: #fff; font-size: 12px; cursor: pointer; }
.sched-empty { padding: 40px 0; text-align: center; color: var(--wb-muted, #81899a); font-size: 13px; }
.task-list { display: grid; gap: 12px; }
.task-card { padding: 15px 17px; border: 1px solid var(--wb-border, #e3e6ed); border-radius: 12px; background: var(--wb-surface, #fff); }
.task-card.paused { opacity: .68; }
.task-head { display: flex; align-items: center; justify-content: space-between; gap: 10px; }
.task-title { display: flex; align-items: center; gap: 8px; min-width: 0; }
.task-title strong { font-size: 13.5px; }
.perm-badge { padding: 2px 7px; border-radius: 6px; background: var(--wb-fill, #f0f1fa); color: #6466d5; font-size: 10px; font-weight: 600; }
.perm-badge.yolo { background: #fdeaea; color: #c0392b; }
.perm-badge.edit { background: #e8f4ec; color: #18794e; }
.paused-badge { padding: 2px 7px; border-radius: 6px; background: var(--wb-bg, #f1f2f6); color: var(--wb-muted, #81899a); font-size: 10px; }
.task-tools { display: flex; gap: 6px; }
.tool-btn { display: inline-flex; align-items: center; gap: 4px; height: 28px; padding: 0 9px; border: 1px solid var(--wb-border, #e3e6ed); border-radius: 7px; background: var(--wb-surface, #fff); color: var(--wb-text-2, #62646a); font-size: 11px; cursor: pointer; }
.tool-btn:hover { background: var(--wb-fill, #f0f1fa); }
.tool-btn.danger:hover { color: #c94c62; border-color: #f0c4cd; }
.task-instruction { margin: 9px 0 10px; color: var(--wb-text-2, #62646a); font-size: 12px; line-height: 1.6; display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; }
.task-foot { display: flex; flex-wrap: wrap; gap: 14px; color: var(--wb-muted, #81899a); font-size: 11px; }
.task-foot .meta { display: inline-flex; align-items: center; gap: 5px; }
.phase-DONE { color: #18794e; }
.phase-FAILED, .phase-BLOCKED { color: #c94c62; }
.phase-WAITING_HUMAN { color: #a35914; font-weight: 600; }
.fire-history { margin-top: 12px; border-top: 1px dashed var(--wb-border, #e8eaf0); padding-top: 10px; display: grid; gap: 6px; }
.fire-empty { color: var(--wb-muted, #81899a); font-size: 11px; margin: 0; }
.fire-row { display: flex; align-items: center; gap: 10px; font-size: 11px; color: var(--wb-text-2, #62646a); min-width: 0; }
.fire-phase { flex: none; width: 62px; }
.fire-time { flex: none; color: var(--wb-muted, #81899a); }
.fire-error { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; color: #c94c62; }
.fire-link { margin-left: auto; display: inline-flex; align-items: center; gap: 3px; color: #6466d5; text-decoration: none; white-space: nowrap; }
.dialog-backdrop { position: fixed; inset: 0; z-index: 60; display: grid; place-items: center; background: rgb(15 18 30 / 45%); }
.dialog { width: min(560px, calc(100vw - 40px)); max-height: calc(100vh - 80px); overflow: auto; padding: 20px 22px; border-radius: 14px; background: var(--wb-surface, #fff); box-shadow: 0 18px 50px rgb(15 20 40 / 25%); }
.dialog h2 { margin: 0 0 14px; font-size: 15px; }
.field { display: grid; gap: 6px; margin-bottom: 12px; font-size: 11px; color: var(--wb-text-2, #62646a); flex: 1; }
.field input, .field textarea, .field select { width: 100%; box-sizing: border-box; padding: 9px 10px; border: 1px solid var(--wb-border, #dfe2ea); border-radius: 8px; background: var(--wb-bg, #fbfbfd); color: var(--wb-text, #232735); font: inherit; font-size: 12.5px; resize: vertical; }
.field textarea { line-height: 1.6; }
.field-row { display: flex; gap: 12px; }
.checkbox-line { display: flex; align-items: center; gap: 6px; font-size: 12px; color: var(--wb-text, #232735); }
.dialog-hint { margin: 4px 0 10px; color: var(--wb-muted, #81899a); font-size: 11px; line-height: 1.6; }
.dialog-error { margin: 0 0 8px; color: #c94c62; font-size: 12px; }
.dialog-actions { display: flex; justify-content: flex-end; gap: 10px; }
@media (prefers-reduced-motion: reduce) { * { transition: none !important; } }
</style>
