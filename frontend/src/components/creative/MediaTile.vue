<template>
  <figure class="mt" :class="{ selectable, selected }" :style="tileStyle" @click="$emit('open')">
    <button
      v-if="selectable"
      type="button"
      class="mt-check"
      role="checkbox"
      :aria-checked="selected"
      :aria-label="`选择 ${asset.displayName}`"
      @click.stop="$emit('toggle-select')"
    >
      <Check v-if="selected" :size="12" :stroke-width="3" />
    </button>
    <button type="button" class="mt-star" :aria-pressed="asset.starred"
            :aria-label="asset.starred ? '取消收藏' : '收藏'"
            @click.stop="$emit('star', $event)">
      <Star :size="14" :class="{ filled: asset.starred }" />
    </button>
    <div class="mt-media">
      <video v-if="isVideo && previewUrl" :src="previewUrl" muted preload="metadata"
             @mouseenter="hoverVideo" @mouseleave="pauseVideo" ref="videoEl"></video>
      <img v-else-if="previewUrl" :src="previewUrl" :alt="asset.displayName" loading="lazy" />
      <span v-else class="mt-placeholder">{{ placeholder }}</span>
      <em v-if="isVideo && durationText" class="mt-duration">{{ durationText }}</em>
    </div>
    <figcaption class="mt-caption">
      <span class="mt-name" :title="asset.displayName">{{ asset.displayName }}</span>
      <span class="mt-meta">{{ metaText }}</span>
    </figcaption>
  </figure>
</template>

<script setup>
import { computed, ref } from 'vue'
import { Check, Star } from 'lucide-vue-next'
import { formatBytes } from '../../views/creative/shared'

/**
 * MediaTile（redesign §5.3）：卡片只保留预览、短标题与必要类型/时长；
 * 收藏为 hover 显现的图标动作；勾选框单独控制多选，不与打开预览冲突。
 * 原比例模式下由父级注入 gridRow span（保持 DOM 顺序与键盘顺序一致）。
 */
const props = defineProps({
  asset: { type: Object, required: true },
  previewUrl: { type: String, default: '' },
  placeholder: { type: String, default: '加载中…' },
  selectable: { type: Boolean, default: false },
  selected: { type: Boolean, default: false },
  // 原比例模式：父级计算好 row span；网格模式不设，统一 4:3 舞台
  rowSpan: { type: Number, default: 0 }
})
defineEmits(['open', 'toggle-select', 'star'])

const videoEl = ref(null)
const isVideo = computed(() => props.asset.mediaType === 'VIDEO')

const durationText = computed(() => {
  const ms = props.asset.durationMs
  if (!ms || ms <= 0) return ''
  const total = Math.round(ms / 1000)
  return `${Math.floor(total / 60)}:${String(total % 60).padStart(2, '0')}`
})

const metaText = computed(() => {
  const parts = []
  if (props.asset.width && props.asset.height) parts.push(`${props.asset.width}×${props.asset.height}`)
  if (props.asset.byteSize) parts.push(formatBytes(props.asset.byteSize))
  return parts.join(' · ')
})

const tileStyle = computed(() => (props.rowSpan > 0 ? { gridRow: `span ${props.rowSpan}` } : {}))

// 文档 §5.4：列表预览 hover 稳定后才播放，移出停止；最多一个实例（由 hover 本身保证）
function hoverVideo() {
  videoEl.value?.play?.().catch(() => { /* 自动播放被拒绝时保持静帧 */ })
}
function pauseVideo() {
  const video = videoEl.value
  if (video) { video.pause(); video.currentTime = 0 }
}
</script>

<style scoped>
.mt {
  position: relative;
  margin: 0;
  display: flex;
  flex-direction: column;
  border: 1px solid var(--wb-border);
  border-radius: var(--cr-radius-media, 14px);
  background: var(--wb-surface);
  overflow: hidden;
  cursor: zoom-in;
  transition: border-color var(--cr-dur-fast, 140ms) var(--cr-ease, ease);
  min-width: 0;
}
.mt:hover { border-color: var(--wb-border-strong); }
.mt.selected { border-color: var(--cr-accent, var(--wb-brand)); box-shadow: 0 0 0 2px var(--cr-accent-soft, var(--wb-brand-soft)); }

.mt-media {
  position: relative;
  flex: 1;
  min-height: 0;
  aspect-ratio: 4 / 3; /* 整齐网格模式的基础高度；原比例模式下 figure 被行 span 定高，flex:1 接管 */
  background: var(--cr-media-stage, var(--wb-media-bg));
  overflow: hidden;
}
.mt-media img,
.mt-media video {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  object-fit: contain;
  display: block;
}
.mt-placeholder {
  position: absolute;
  inset: 0;
  display: grid;
  place-items: center;
  color: var(--wb-muted);
  font-size: var(--wb-fs-caption);
}
.mt-duration {
  position: absolute;
  right: 6px;
  bottom: 6px;
  padding: 1px 6px;
  border-radius: 6px;
  background: rgba(10, 10, 12, 0.72);
  color: #fff;
  font-size: 11px;
  font-style: normal;
  font-variant-numeric: tabular-nums;
}

.mt-check {
  position: absolute;
  top: 8px;
  left: 8px;
  z-index: 2;
  display: grid;
  place-items: center;
  width: 22px;
  height: 22px;
  border: 1.5px solid rgba(255, 255, 255, 0.85);
  border-radius: 7px;
  background: rgba(20, 20, 24, 0.45);
  color: #fff;
  cursor: pointer;
  opacity: 0;
  transition: opacity var(--cr-dur-fast, 140ms) var(--cr-ease, ease);
}
.mt.selected .mt-check,
.mt:hover .mt-check,
.mt:focus-within .mt-check { opacity: 1; }
.mt-check[aria-checked='true'] { background: var(--cr-accent, var(--wb-brand)); border-color: var(--cr-accent, var(--wb-brand)); }

.mt-star {
  position: absolute;
  top: 8px;
  right: 8px;
  z-index: 2;
  display: grid;
  place-items: center;
  width: 26px;
  height: 26px;
  border: 0;
  border-radius: 8px;
  background: rgba(20, 20, 24, 0.45);
  color: #fff;
  cursor: pointer;
  opacity: 0;
  transition: opacity var(--cr-dur-fast, 140ms) var(--cr-ease, ease);
}
.mt:hover .mt-star,
.mt-star[aria-pressed='true'],
.mt:focus-within .mt-star { opacity: 1; }
.mt-star .filled { fill: #f5c542; color: #f5c542; }

.mt-caption {
  display: flex;
  flex-direction: column;
  gap: 1px;
  padding: 8px 11px 9px;
}
.mt-name {
  font-size: var(--wb-fs-compact);
  font-weight: 550;
  color: var(--wb-text);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.mt-meta {
  font-size: var(--wb-fs-caption);
  color: var(--wb-muted);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

@media (hover: none) {
  .mt-check, .mt-star { opacity: 1; }
}
</style>
