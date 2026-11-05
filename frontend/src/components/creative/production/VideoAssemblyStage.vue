<template>
  <section class="cut-stage">
    <!-- 草稿片段条：顺序即拼接顺序；缺片镜头如实提示 -->
    <div class="stage-card">
      <header class="stage-card-head">
        <div>
          <h2>成片</h2>
          <p class="stage-sub">按镜头顺序拼接已选视频并导出 MP4；顺序与源镜头排序分别保存。</p>
        </div>
        <div class="head-actions">
          <button type="button" class="cr-ghost" :disabled="syncing" @click="rebuild">
            {{ syncing ? '同步中…' : '从镜头重新同步' }}
          </button>
          <button type="button" class="cr-primary" :disabled="!draft?.items?.length || exporting"
                  @click="exportOpen = true">
            导出 MP4
          </button>
        </div>
      </header>

      <p v-if="missing.length" class="missing-note">
        缺片 {{ missing.length }} 个镜头（{{ missing.map(item => `镜${String(item.shotNumber).padStart(2, '0')}`).join('、') }}）——这些镜头不会进入成片；完成选片后点「从镜头重新同步」。
      </p>

      <p v-if="!draft?.items?.length && !missing.length" class="cr-empty">
        还没有可用片段。先在「视频」阶段完成生成与选片，再回来重建草稿。
      </p>
      <div v-else class="clip-strip">
        <article v-for="(item, index) in draft.items" :key="item.assetId + '-' + index" class="clip"
                 :class="{ current: playingIndex === index }">
          <video v-if="clipUrl(item)" :src="clipUrl(item)" muted preload="metadata"></video>
          <span v-else class="clip-placeholder">{{ item.displayName || '视频' }}</span>
          <span class="clip-no">{{ index + 1 }}</span>
          <footer class="clip-meta">
            <span>镜{{ String(item.shotNumber || 0).padStart(2, '0') }} · {{ formatMs(item.durationMs) }}</span>
            <span class="clip-tools">
              <button type="button" :disabled="index === 0 || dirty" aria-label="上移片段" @click="move(index, -1)">↑</button>
              <button type="button" :disabled="index === draft.items.length - 1 || dirty" aria-label="下移片段" @click="move(index, 1)">↓</button>
              <button type="button" :disabled="dirty" aria-label="移除片段" @click="remove(index)">移除</button>
            </span>
          </footer>
        </article>
      </div>
      <p v-if="dirty" class="dirty-note">片段顺序有未保存修改。</p>
      <p v-if="stageError" class="cr-error">{{ stageError }}</p>
    </div>

    <!-- 导出确认：范围、顺序、画幅；导出为本地转码，不调用生成模型 -->
    <div v-if="exportOpen && draft" class="cr-dialog-backdrop" @click.self="exportOpen = false">
      <div class="cr-dialog export-dialog" role="dialog" aria-modal="true" aria-label="导出确认">
        <h2>导出确认</h2>
        <dl class="export-meta">
          <div><dt>片段</dt><dd>{{ draft.items.length }} 段，合计约 {{ formatMs(totalMs) }}</dd></div>
          <div><dt>顺序</dt><dd>{{ draft.items.map(item => `镜${String(item.shotNumber || 0).padStart(2, '0')}`).join(' → ') }}</dd></div>
          <div><dt>输出</dt><dd>
            <select v-model="outputSize" class="cr-select">
              <option value="1280x720">1280×720（16:9）</option>
              <option value="1920x1080">1920×1080（16:9）</option>
              <option value="720x1280">720×1280（9:16）</option>
              <option value="1080x1920">1080×1920（9:16）</option>
              <option value="720x720">720×720（1:1）</option>
            </select>
            保比例补边，不拉伸；保留原声，无音轨补静音
          </dd></div>
          <div><dt>费用</dt><dd>导出为服务端本地转码，不调用生成模型</dd></div>
        </dl>
        <p class="export-hint">导出期间可继续编辑项目，不影响在途成片；完成后可播放与下载。</p>
        <div class="cr-dialog-actions">
          <button type="button" class="cr-secondary" @click="exportOpen = false">取消</button>
          <button type="button" class="cr-primary" :disabled="exporting" @click="exportNow">
            {{ exporting ? '提交中…' : '开始导出' }}
          </button>
        </div>
      </div>
    </div>

    <!-- 导出任务与历史 -->
    <div class="stage-card">
      <header class="stage-card-head">
        <h2>导出任务与历史</h2>
        <span class="cr-field-help">服务端执行；刷新/重启可恢复，下载链接过期只重新签名</span>
      </header>
      <p v-if="!runs.length" class="cr-empty">还没有导出记录。</p>
      <div v-else class="run-list">
        <article v-for="run in runs" :key="run.id" class="run-row">
          <div class="run-info">
            <strong>{{ runStatus(run) }}</strong>
            <span>{{ (run.items || []).length }} 段 · {{ run.outputSize || '1280x720' }} · {{ formatMs(run.outputDurationMs) }}</span>
            <span class="run-time">{{ formatTime(run.createdAt) }}</span>
            <em v-if="run.error" class="run-error">{{ run.error }}</em>
          </div>
          <div class="run-actions">
            <template v-if="run.status === 'COMPLETED'">
              <video v-if="runUrl(run)" :key="run.id" :src="runUrl(run)" controls preload="metadata" class="run-player"></video>
              <a v-if="runUrl(run)" class="cr-secondary" :href="runUrl(run)" target="_blank" rel="noopener">下载</a>
            </template>
            <button v-if="run.running" type="button" class="cr-ghost" @click="cancel(run)">取消</button>
            <button v-if="run.status === 'FAILED'" type="button" class="cr-secondary" @click="retry(run)">重试</button>
          </div>
        </article>
      </div>
    </div>
  </section>
</template>

<script setup>
import { computed, inject, onBeforeUnmount, onMounted, ref } from 'vue'
import { PROJECT_CTX } from '../../../views/creative/project/composables'
import {
  cancelFinalCut, exportFinalCut, getFinalCutDraft, listFinalCuts, rebuildFinalCutDraft,
  retryFinalCut, updateFinalCutItems
} from '../../../api'

/**
 * P2 最小成片：草稿（顺序/移除）→ 导出（幂等键防重复提交）→ 阶段进度 → 播放/下载/重试。
 * 导出为本地转码，不调用生成模型；产物走统一素材签名链路。
 */
const ctx = inject(PROJECT_CTX)
const projectId = ctx.projectId
const story = ctx.story

const draft = ref(null)
const missing = ref([])
const runs = ref([])
const syncing = ref(false)
const exporting = ref(false)
const exportOpen = ref(false)
const outputSize = ref('1280x720')
const dirty = ref(false)
const stageError = ref('')
const playingIndex = ref(-1)
let pollTimer = 0

const totalMs = computed(() => (draft.value?.items || [])
  .reduce((sum, item) => sum + (item.durationMs || 0), 0))

function formatMs(value) {
  const ms = Number(value) || 0
  const seconds = Math.round(ms / 100) / 10
  return `${seconds}s`
}
function runStatus(run) {
  return ({
    DRAFT: '草稿', PREPARING: '准备素材', PROCESSING: '处理中', UPLOADING: '上传结果',
    COMPLETED: '已导出', FAILED: '导出失败', CANCELLED: '已取消'
  })[run.status] || run.status
}
function clipUrl(item) { return item.assetId ? (ctx.videoUrls?.[item.assetId]?.url || '') : '' }
function runUrl(run) { return run.outputAssetId ? (ctx.videoUrls?.[run.outputAssetId]?.url || '') : '' }

onMounted(async () => {
  await refresh()
  schedulePoll()
})
onBeforeUnmount(() => window.clearTimeout(pollTimer))

function schedulePoll() {
  window.clearTimeout(pollTimer)
  const running = runs.value.some(run => run.running)
  if (!running) return
  pollTimer = window.setTimeout(async () => {
    if (document.visibilityState !== 'hidden') await refreshRuns()
    schedulePoll()
  }, 3000)
}

async function refresh() {
  stageError.value = ''
  try {
    const result = await getFinalCutDraft(projectId.value)
    draft.value = result.cut
    missing.value = result.missing || []
    ;(draft.value.items || []).forEach(item => { if (item.assetId) ctx.videoUrlFor?.(item.assetId) })
    await refreshRuns()
  } catch (exception) {
    stageError.value = exception.response?.data?.error || '成片草稿加载失败'
  }
}

async function rebuild() {
  syncing.value = true
  stageError.value = ''
  dirty.value = false
  try {
    const result = await rebuildFinalCutDraft(projectId.value)
    draft.value = result.cut
    missing.value = result.missing || []
    ;(draft.value.items || []).forEach(item => { if (item.assetId) ctx.videoUrlFor?.(item.assetId) })
    await refreshRuns()
    ctx.showToast?.(`草稿已同步：${draft.value.items.length} 段`, 'success')
  } catch (exception) {
    stageError.value = exception.response?.data?.error || '同步失败'
  } finally {
    syncing.value = false
  }
}

function move(index, direction) {
  const items = [...draft.value.items]
  const target = index + direction
  if (target < 0 || target >= items.length) return
  const [row] = items.splice(index, 1)
  items.splice(target, 0, row)
  applyItems(items)
}
function remove(index) {
  const items = [...draft.value.items]
  items.splice(index, 1)
  applyItems(items)
}
async function applyItems(items) {
  dirty.value = true
  stageError.value = ''
  try {
    const result = await updateFinalCutItems(projectId.value, draft.value.id, {
      expectedItemsVersion: draft.value.itemsVersion,
      items: items.map(item => ({ shotId: item.shotId, candidateId: item.candidateId, assetId: item.assetId }))
    })
    draft.value = result
    dirty.value = false
  } catch (exception) {
    stageError.value = exception.response?.data?.error || '片段保存失败'
  }
}

async function exportNow() {
  exporting.value = true
  stageError.value = ''
  try {
    const run = await exportFinalCut(projectId.value, draft.value.id, {
      idempotencyKey: `fc-${draft.value.id}-${draft.value.itemsVersion}-${outputSize.value}`,
      outputSize: outputSize.value
    })
    exportOpen.value = false
    ctx.showToast?.('导出已提交，服务端处理中', 'success')
    await refreshRuns()
    schedulePoll()
    return run
  } catch (exception) {
    stageError.value = exception.response?.data?.error || '导出提交失败'
  } finally {
    exporting.value = false
  }
}

async function refreshRuns() {
  try {
    runs.value = await listFinalCuts(projectId.value, 20)
    runs.value.forEach(run => {
      if (run.status === 'COMPLETED' && run.outputAssetId) ctx.videoUrlFor?.(run.outputAssetId)
    })
  } catch { /* 历史加载失败不阻断草稿编辑 */ }
}

async function cancel(run) {
  try {
    await cancelFinalCut(projectId.value, run.id)
    await refreshRuns()
  } catch (exception) {
    stageError.value = exception.response?.data?.error || '取消失败'
  }
}
async function retry(run) {
  try {
    await retryFinalCut(projectId.value, run.id)
    await refreshRuns()
    schedulePoll()
  } catch (exception) {
    stageError.value = exception.response?.data?.error || '重试失败'
  }
}
</script>

<style scoped>
.cut-stage { display: grid; gap: 14px; }
.stage-card { padding: 18px 20px; border: 1px solid var(--cr-border, #e4e3df); border-radius: var(--cr-radius-pop, 16px); background: var(--cr-surface, #fff); }
.stage-card-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 14px; }
.stage-card-head h2 { margin: 0; font-size: 16px; font-weight: 600; color: var(--cr-text, #202124); }
.stage-sub { margin: 4px 0 0; color: var(--cr-text-2, #62646a); font-size: 12px; }
.head-actions { display: flex; gap: 8px; }
.head-actions .cr-primary, .head-actions .cr-ghost { height: 34px; padding: 0 12px; }
.missing-note { margin: 12px 0 0; padding: 9px 12px; border-radius: 8px; background: color-mix(in srgb, #d6ad5c 14%, transparent); color: #a35914; font-size: 12px; }
.clip-strip { display: flex; gap: 10px; overflow-x: auto; margin-top: 14px; padding-bottom: 4px; }
.clip { position: relative; flex: none; width: 168px; border: 1px solid var(--cr-border, #e4e3df); border-radius: 10px; overflow: hidden; background: var(--cr-media-stage, #101113); }
.clip.current { border-color: var(--cr-accent, #6654c7); }
.clip video { width: 100%; height: 94px; object-fit: cover; display: block; background: var(--cr-media-stage, #101113); }
.clip-placeholder { display: flex; align-items: center; justify-content: center; height: 94px; color: var(--wb-muted, #81899a); font-size: 11px; }
.clip-no { position: absolute; top: 5px; left: 5px; width: 20px; height: 20px; display: inline-flex; align-items: center; justify-content: center; border-radius: 50%; background: rgb(0 0 0 / 55%); color: #fff; font-size: 10px; }
.clip-meta { display: flex; align-items: center; justify-content: space-between; gap: 6px; padding: 5px 7px; background: var(--cr-surface, #fff); color: var(--cr-text-2, #62646a); font-size: 10px; }
.clip-tools { display: inline-flex; gap: 3px; }
.clip-tools button { border: 1px solid var(--cr-border, #e4e3df); background: transparent; border-radius: 5px; min-width: 22px; height: 20px; padding: 0 4px; font-size: 10px; color: var(--cr-text-2, #62646a); cursor: pointer; }
.clip-tools button:disabled { opacity: .4; cursor: default; }
.dirty-note { margin: 8px 0 0; color: #b4693a; font-size: 11px; }
.export-dialog { width: min(560px, calc(100vw - 48px)); }
.export-dialog h2 { margin: 0 0 10px; font-size: 15px; }
.export-meta { margin: 0 0 10px; display: grid; gap: 8px; font-size: 12px; }
.export-meta div { display: grid; grid-template-columns: 48px 1fr; gap: 8px; }
.export-meta dt { color: var(--cr-text-2, #62646a); }
.export-meta dd { margin: 0; color: var(--cr-text, #202124); word-break: break-word; }
.export-hint { margin: 0 0 4px; color: var(--cr-text-2, #62646a); font-size: 11px; }
.run-list { display: grid; gap: 10px; margin-top: 12px; }
.run-row { display: flex; align-items: center; justify-content: space-between; gap: 12px; padding: 10px 12px; border: 1px solid var(--cr-border, #e4e3df); border-radius: 10px; }
.run-info { display: grid; gap: 3px; font-size: 12px; color: var(--cr-text-2, #62646a); min-width: 0; }
.run-info strong { color: var(--cr-text, #202124); font-size: 12px; }
.run-time { font-size: 10px; }
.run-error { font-style: normal; color: var(--wb-danger, #c94c62); font-size: 11px; word-break: break-word; }
.run-actions { display: flex; align-items: center; gap: 8px; flex: none; }
.run-player { width: 200px; max-height: 110px; border-radius: 8px; background: var(--cr-media-stage, #101113); }
.run-actions .cr-secondary, .run-actions .cr-ghost { height: 30px; padding: 0 10px; font-size: 11px; }
@media (prefers-reduced-motion: reduce) { * { transition: none !important; } }
</style>
