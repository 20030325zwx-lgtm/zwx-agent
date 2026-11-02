<template>
  <article
    class="shot-card"
    :class="{ checked, selected: adopted }"
    tabindex="0"
    @click="$emit('open', shot)"
    @keydown.enter.prevent="$emit('open', shot)"
  >
    <div class="shot-cover">
      <video v-if="previewKind === 'video' && previewUrl" :src="previewUrl" preload="metadata" muted playsinline></video>
      <img v-else-if="previewUrl" :src="previewUrl" :alt="`镜头 ${shot.shotNumber}`" />
      <span v-else class="shot-empty">
        {{ candidateTotal ? `候选 ${candidateTotal}` : '未生成' }}
      </span>
      <label v-if="selectable" class="shot-check" @click.stop>
        <input type="checkbox" :checked="checked" @change="$emit('toggle-check', shot.id)" />
      </label>
      <span v-if="adopted" class="shot-adopted">已采用</span>
      <span v-else-if="pendingTotal" class="shot-pending">待选 {{ pendingTotal }}</span>
    </div>

    <div class="shot-body">
      <div class="shot-line">
        <span class="shot-no">{{ sceneLabel }} · {{ String(shot.shotNumber).padStart(2, '0') }}</span>
        <span class="shot-status" :class="tone">{{ statusLabel }}</span>
      </div>
      <p class="shot-intent">{{ shot.purpose || shot.action || '还没写叙事目的' }}</p>
      <div class="shot-meta">
        <span v-if="shot.camera">{{ shot.camera }}</span>
        <span v-if="shot.durationTargetSeconds">{{ shot.durationTargetSeconds }}s</span>
        <span v-if="blockReason" class="is-block">{{ blockReason }}</span>
      </div>
      <div class="shot-actions">
        <button type="button" class="cr-ghost" @click.stop="$emit('open', shot)">
          {{ adopted ? '查看选片' : (candidateTotal ? '对比候选' : '编辑 / 生成') }}
        </button>
      </div>
    </div>
  </article>
</template>

<script setup>
import { computed } from 'vue'
import { SHOT_STATUS_LABEL } from '../../../views/creative/project/helpers'

const props = defineProps({
  shot: { type: Object, required: true },
  sceneLabel: { type: String, default: '' },
  candidates: { type: Array, default: () => [] },
  checked: { type: Boolean, default: false },
  // 媒体地址由父级统一解析：候选视频需要签名 URL，逐卡自取会重复请求并放大渲染开销
  previewUrl: { type: String, default: '' },
  previewKind: { type: String, default: '' }
})
defineEmits(['open', 'toggle-check'])

// 候选在 workspace 中是扁平记录（status/assetId 在顶层），与检查器的 CandidateView 不同
const adopted = computed(() => props.shot.status === 'SELECTED'
  || props.candidates.some(item => item.status === 'SELECTED'))
const candidateTotal = computed(() => props.candidates.length)
const pendingTotal = computed(() => props.candidates.filter(item => item.status === 'CANDIDATE').length)
const statusLabel = computed(() => SHOT_STATUS_LABEL[props.shot.status] || props.shot.status)
const tone = computed(() => ({
  SELECTED: 'is-done',
  PENDING_SELECTION: 'is-pending',
  GENERATING: 'is-running',
  READY: 'is-ready',
  DRAFT: 'is-draft'
}[props.shot.status] || 'is-draft'))
const blockReason = computed(() => {
  if (!String(props.shot.promptText || '').trim()) return '缺提示词'
  const usable = props.candidates.some(item => ['CANDIDATE', 'SELECTED'].includes(item.status))
  const failed = props.candidates.some(item => item.status === 'FAILED')
  return !usable && failed ? '上次失败' : ''
})
const selectable = computed(() => ['DRAFT', 'READY'].includes(props.shot.status)
  && Boolean(String(props.shot.promptText || '').trim()))
</script>

<style scoped>
.shot-card {
  display: flex;
  flex-direction: column;
  border: 1px solid var(--wb-border);
  border-radius: var(--wb-radius);
  background: var(--wb-surface);
  overflow: hidden;
  cursor: pointer;
  transition: border-color 0.15s;
}

.shot-card:hover { border-color: var(--wb-border-strong); }
.shot-card.checked { border-color: var(--wb-brand); box-shadow: 0 0 0 1px var(--wb-brand); }
.shot-card.selected { border-color: color-mix(in srgb, var(--wb-success) 50%, var(--wb-border)); }

.shot-cover {
  position: relative;
  aspect-ratio: 16 / 9;
  background: var(--wb-media-bg);
  display: grid;
  place-items: center;
  overflow: hidden;
}

.shot-cover video,
.shot-cover img { width: 100%; height: 100%; object-fit: contain; display: block; }

.shot-empty { color: var(--wb-muted); font-size: var(--wb-fs-caption); }

.shot-check {
  position: absolute;
  top: 6px;
  left: 6px;
  display: grid;
  place-items: center;
  width: 22px;
  height: 22px;
  border-radius: var(--wb-radius-sm);
  background: color-mix(in srgb, var(--wb-surface) 86%, transparent);
  border: 1px solid var(--wb-border);
}

.shot-check input {
  appearance: none;
  width: 14px;
  height: 14px;
  margin: 0;
  border: 1px solid var(--wb-border-strong);
  border-radius: 3px;
  background: var(--wb-surface);
  cursor: pointer;
}

.shot-check input:checked {
  background: var(--wb-brand);
  border-color: var(--wb-brand);
}

.shot-adopted,
.shot-pending {
  position: absolute;
  right: 6px;
  bottom: 6px;
  padding: 1px 8px;
  border-radius: 999px;
  font-size: var(--wb-fs-caption);
  background: color-mix(in srgb, var(--wb-surface) 90%, transparent);
  border: 1px solid var(--wb-border);
}

.shot-adopted { color: var(--wb-success); }
.shot-pending { color: var(--wb-warning); }

.shot-body { display: flex; flex-direction: column; gap: 4px; padding: 10px 12px 12px; }

.shot-line { display: flex; align-items: center; justify-content: space-between; gap: 8px; }
.shot-no { font-size: var(--wb-fs-compact); font-weight: 600; color: var(--wb-text); }

.shot-status {
  font-size: var(--wb-fs-caption);
  padding: 1px 8px;
  border-radius: 999px;
  border: 1px solid var(--wb-border);
  color: var(--wb-text-2);
}
.shot-status.is-done { color: var(--wb-success); border-color: color-mix(in srgb, var(--wb-success) 40%, transparent); }
.shot-status.is-pending { color: var(--wb-warning); border-color: color-mix(in srgb, var(--wb-warning) 40%, transparent); }
.shot-status.is-running { color: var(--wb-info); border-color: color-mix(in srgb, var(--wb-info) 40%, transparent); }

.shot-intent {
  margin: 0;
  font-size: var(--wb-fs-compact);
  line-height: 1.5;
  color: var(--wb-text-2);
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
  min-height: 40px;
}

.shot-meta { display: flex; gap: 8px; flex-wrap: wrap; font-size: var(--wb-fs-caption); color: var(--wb-muted); }
.shot-meta .is-block { color: var(--wb-warning); }

.shot-actions { display: flex; justify-content: flex-end; margin-top: 2px; }
</style>
