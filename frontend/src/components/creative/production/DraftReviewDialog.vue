<template>
  <div class="cr-dialog-backdrop" @click.self="$emit('close')">
    <div class="cr-dialog draft-dialog" role="dialog" aria-modal="true" aria-label="分镜草稿评审">
      <header class="draft-head">
        <div>
          <h3>分镜草稿 <span class="draft-source">来自「{{ draft.episodeTitle }}」</span></h3>
          <p class="draft-meta">
            {{ rows.length }} 个镜头 · 合计约 {{ totalSeconds }} 秒
            <template v-if="targetSeconds"> · 目标 {{ targetSeconds }} 秒（{{ deviationText }}）</template>
          </p>
        </div>
        <button type="button" class="cr-ghost" aria-label="关闭" @click="$emit('close')">×</button>
      </header>

      <!-- 解析失败：保留原始输出，可复制/重试；已有镜头不受影响 -->
      <template v-if="draft.status === 'PARSE_FAILED'">
        <p class="parse-error">{{ draft.error || '分镜格式未能解析，尚未修改已有镜头。' }}</p>
        <pre class="raw-text">{{ draft.rawText || '（模型没有返回内容）' }}</pre>
        <div class="draft-actions">
          <button type="button" class="cr-ghost" @click="copyRaw">复制原文</button>
          <button type="button" class="cr-ghost" @click="$emit('discard')">放弃</button>
        </div>
        <p class="parse-hint">可复制原文手动拆镜，或回到故事阶段重新生成。</p>
      </template>

      <template v-else>
        <div class="rows">
          <p v-if="error" class="error-line" style="grid-column:1/-1">{{ error }}</p>
          <article v-for="(row, index) in rows" :key="row.rowId" class="row-card">
            <header class="row-head">
              <span class="row-no">镜 {{ index + 1 }}</span>
              <label class="row-seconds">
                <input v-model.number="row.durationTargetSeconds" type="number" min="1" max="60" /> 秒
              </label>
              <span class="row-tools">
                <button type="button" class="row-btn" :disabled="index === 0" aria-label="上移" @click="move(index, -1)">↑</button>
                <button type="button" class="row-btn" :disabled="index === rows.length - 1" aria-label="下移" @click="move(index, 1)">↓</button>
                <button type="button" class="row-btn danger" aria-label="删除该行" @click="remove(index)">删除</button>
              </span>
            </header>
            <div class="row-grid">
              <label class="row-field"><span>画面动作</span>
                <textarea v-model="row.action" rows="2" />
              </label>
              <label class="row-field"><span>运镜</span>
                <input v-model="row.camera" />
              </label>
              <label class="row-field"><span>台词 / 旁白</span>
                <input v-model="row.dialogue" placeholder="没有可留空" />
              </label>
              <label class="row-field wide"><span>剧情目的</span>
                <input v-model="row.purpose" />
              </label>
              <label class="row-field wide"><span>生成提示词</span>
                <textarea v-model="row.promptText" rows="2" />
              </label>
            </div>
          </article>
          <p v-if="!rows.length" class="cr-empty">没有镜头行；可以手动添加，或放弃后重新生成。</p>
        </div>

        <p v-if="error" class="error-line">{{ error }}</p>

        <p v-if="error" class="error-line">{{ error }}</p>
        <div class="adopt-row">
          <label class="adopt-mode">
            <input v-model="mode" type="radio" value="APPEND" /> 追加为新的镜头
          </label>
          <label class="adopt-mode" :title="existingCount ? `将替换 ${existingCount} 个未生成且无候选的镜头` : '当前没有可替换镜头'">
            <input v-model="mode" type="radio" value="REPLACE_UNGENERATED" /> 替换未生成的镜头
          </label>
          <span class="adopt-note">
            {{ mode === 'REPLACE_UNGENERATED'
              ? `将删除 ${existingCount} 个未生成且无候选的镜头；已有候选的镜头保留`
              : `当前已有 ${existingCount} 个镜头，将追加在其后` }}
          </span>
        </div>

        <div class="draft-actions">
          <button type="button" class="cr-ghost" @click="addRow">+ 添加镜头</button>
          <span class="spacer" />
          <button type="button" class="cr-ghost" @click="$emit('discard')">放弃</button>
          <button type="button" class="cr-primary" :disabled="adopting || !rows.length" @click="adopt">
            {{ adopting ? '写入中…' : `确认创建 ${rows.length} 个镜头` }}
          </button>
        </div>
      </template>
    </div>
  </div>
</template>

<script setup>
import { computed, ref } from 'vue'
import { adoptStoryboardDraft, discardStoryboardDraft } from '../../../api'

/**
 * 草稿评审（P0）：编辑只发生在这份本地副本上，确认才写入；
 * 采纳是服务端一次有效操作（重复点击返回 409）；故事版本变化时提示重新确认。
 */
const props = defineProps({
  draft: { type: Object, required: true },
  targetSeconds: { type: Number, default: 0 },
  existingShots: { type: Number, default: 0 }
})
const emit = defineEmits(['close', 'adopted', 'discard'])

const rows = ref(props.draft.rows.map(row => ({ ...row })))
const mode = ref('APPEND')
const adopting = ref(false)
const error = ref('')
const rawCopied = ref(false)
const existingCount = computed(() => props.existingShots)

const totalSeconds = computed(() => rows.value.reduce((sum, row) => sum + (Number(row.durationTargetSeconds) || 0), 0))
const deviation = computed(() => totalSeconds.value - (props.targetSeconds || 0))
const deviationText = computed(() => {
  if (!props.targetSeconds || deviation.value === 0) return '一致'
  return `${deviation.value > 0 ? '超出' : '少于'} ${Math.abs(deviation.value)} 秒`
})
function move(index, direction) {
  const target = index + direction
  if (target < 0 || target >= rows.value.length) return
  const [row] = rows.value.splice(index, 1)
  rows.value.splice(target, 0, row)
}
function remove(index) {
  rows.value.splice(index, 1)
}
function addRow() {
  rows.value.push({
    rowId: `${Date.now()}-${Math.random().toString(36).slice(2, 8)}`,
    orderNo: rows.value.length + 1,
    durationTargetSeconds: 4,
    purpose: '', action: '', camera: '', dialogue: '', promptText: ''
  })
}
async function copyRaw() {
  try {
    await navigator.clipboard.writeText(props.draft.rawText || '')
    rawCopied.value = true
  } catch { /* 剪贴板不可用静默 */ }
}
async function adopt() {
  if (adopting.value) return
  adopting.value = true
  error.value = ''
  try {
    const result = await adoptStoryboardDraft(props.draft.projectId, props.draft.id, {
      mode: mode.value,
      sceneId: props.draft.sceneId,
      expectedSourceVersion: props.draft.sourceEpisodeVersion,
      force: false
    })
    emit('adopted', result)
  } catch (exception) {
    const message = exception.response?.data?.error || ''
    if (message.includes('故事') && message.includes('修改')) {
      if (window.confirm('故事在草稿生成后已被修改。仍要基于当前草稿写入镜头吗？')) {
        try {
          const result = await adoptStoryboardDraft(props.draft.projectId, props.draft.id, {
            mode: mode.value,
            sceneId: props.draft.sceneId,
            expectedSourceVersion: props.draft.sourceEpisodeVersion,
            force: true
          })
          emit('adopted', result)
        } catch (retryException) {
          error.value = retryException.response?.data?.error || '写入失败'
        }
      }
    } else if (message.includes('已采纳')) {
      error.value = '这份草稿已经写入过镜头，请刷新镜头列表查看结果'
    } else {
      error.value = message || '写入失败'
    }
  } finally {
    adopting.value = false
  }
}
</script>

<style scoped src="../../../views/creative/creative.css"></style>

<style scoped>
.draft-dialog { width: min(880px, calc(100vw - 48px)); max-height: calc(100vh - 64px); display: flex; flex-direction: column; }
.draft-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 10px; }
.draft-head h3 { margin: 0; font-size: 15px; font-weight: 600; }
.draft-source { margin-left: 8px; font-size: 12px; font-weight: 400; color: var(--cr-text-2, #62646a); }
.draft-meta { margin: 4px 0 0; color: var(--cr-text-2, #62646a); font-size: 12px; }
.rows { flex: 1; min-height: 0; overflow: auto; display: grid; gap: 10px; margin: 12px -4px; padding: 0 4px; }
.row-card { border: 1px solid var(--cr-border, #e4e3df); border-radius: 10px; padding: 10px 12px; }
.row-head { display: flex; align-items: center; gap: 10px; margin-bottom: 8px; }
.row-no { font-weight: 600; font-size: 12px; color: var(--cr-text, #202124); }
.row-seconds { display: inline-flex; align-items: center; gap: 4px; font-size: 11px; color: var(--cr-text-2, #62646a); }
.row-seconds input { width: 52px; padding: 3px 6px; border: 1px solid var(--cr-border, #e4e3df); border-radius: 6px; font: inherit; font-size: 12px; }
.row-tools { margin-left: auto; display: inline-flex; gap: 6px; }
.row-btn { border: 1px solid var(--cr-border, #e4e3df); background: transparent; border-radius: 6px; min-width: 26px; height: 26px; padding: 0 7px; font-size: 12px; color: var(--cr-text-2, #62646a); cursor: pointer; }
.row-btn:disabled { opacity: .4; cursor: default; }
.row-btn.danger { color: #c94c62; }
.row-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 8px; }
.row-field { display: grid; gap: 3px; font-size: 11px; color: var(--cr-text-2, #62646a); }
.row-field.wide { grid-column: 1 / -1; }
.row-field input, .row-field textarea { width: 100%; box-sizing: border-box; padding: 6px 8px; border: 1px solid var(--cr-border, #e4e3df); border-radius: 6px; background: var(--cr-page-bg, #f6f5f2); color: var(--cr-text, #202124); font: inherit; font-size: 12px; resize: vertical; }
.draft-notes { margin: 6px 0 0; color: var(--cr-text-2, #62646a); font-size: 11px; line-height: 1.6; }
.draft-notes p { margin: 0 0 4px; }
.adopt-row { display: flex; align-items: center; gap: 14px; flex-wrap: wrap; padding: 10px 0 0; border-top: 1px solid var(--cr-border, #e4e3df); margin-top: 10px; }
.adopt-mode { display: inline-flex; align-items: center; gap: 5px; font-size: 12px; color: var(--cr-text, #202124); }
.adopt-note { font-size: 11px; color: var(--cr-text-2, #62646a); }
.draft-actions { display: flex; align-items: center; gap: 10px; margin-top: 10px; }
.draft-actions .cr-primary, .draft-actions .cr-secondary { height: 36px; padding: 0 14px; }
.spacer { flex: 1; }
.parse-error { margin: 10px 0; color: #c94c62; font-size: 12px; }
.raw-text { max-height: 260px; overflow: auto; padding: 12px; border-radius: 8px; background: var(--cr-page-bg, #f6f5f2); font-size: 12px; line-height: 1.6; white-space: pre-wrap; }
.parse-hint { margin: 8px 0 0; color: var(--cr-text-2, #62646a); font-size: 11px; }
.error-line { margin: 8px 0 0; color: #c94c62; font-size: 12px; }
@media (max-width: 768px) { .row-grid { grid-template-columns: 1fr; } }
@media (prefers-reduced-motion: reduce) { * { transition: none !important; } }
</style>
