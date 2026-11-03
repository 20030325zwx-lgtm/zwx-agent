<template>
  <router-link :to="`/creative/projects/${project.id}`" class="pc-card">
    <div class="pc-cover">
      <img v-if="thumbUrl" :src="thumbUrl" alt="" loading="lazy" />
      <video v-else-if="videoUrl" :src="videoUrl" preload="metadata" muted playsinline />
      <span v-else class="pc-placeholder">
        <component :is="kindIcon" :size="22" :stroke-width="1.7" />
        <em>还没有产物</em>
      </span>
      <span class="pc-kind">{{ kindLabel(project.kind) }}</span>
      <span v-if="project.status === 'ARCHIVED'" class="pc-archived">已归档</span>
    </div>
    <div class="pc-body">
      <strong class="pc-name">{{ project.name }}</strong>
      <p class="pc-desc">{{ project.description || '暂无描述' }}</p>
      <span class="pc-time">更新于 {{ formatTime(project.updatedAt) }}</span>
    </div>
  </router-link>
</template>

<script setup>
import { computed, watch } from 'vue'
import { BookOpen, Film, Palette, PanelsTopLeft } from 'lucide-vue-next'
import { formatTime, kindLabel, useAssetUrls, useVideoUrls } from '../../views/creative/shared'

const props = defineProps({
  project: { type: Object, required: true }
})

const { thumbFor, thumbs } = useAssetUrls()
const { videoUrls, videoUrlFor } = useVideoUrls()

const KINDS = {
  IMAGE: Palette,
  VIDEO: Film,
  NOVEL: BookOpen,
  COMIC: PanelsTopLeft
}
const kindIcon = computed(() => KINDS[props.project.kind] || Palette)

// 封面是即时计算的（后端返回 coverAssetId/coverMediaType）：图片走缩略图，视频走短时效播放地址。
// 缩略图缺失时 useAssetUrls 会回退原图，仍失败则显示类型占位，不伪造截图。
const thumbUrl = computed(() =>
  props.project.coverMediaType === 'IMAGE' ? thumbs[props.project.coverAssetId] || '' : '')
const videoUrl = computed(() =>
  props.project.coverMediaType === 'VIDEO' ? videoUrls[props.project.coverAssetId]?.url || '' : '')

watch(() => [props.project.coverAssetId, props.project.coverMediaType], ([assetId, mediaType]) => {
  if (!assetId) return
  if (mediaType === 'VIDEO') videoUrlFor(assetId)
  else thumbFor(assetId)
}, { immediate: true })
</script>

<style scoped>
.pc-card {
  display: flex;
  flex-direction: column;
  border: 1px solid var(--cr-border, var(--wb-border));
  border-radius: var(--cr-radius-media, 14px);
  background: var(--cr-surface, var(--wb-surface));
  color: var(--cr-text, var(--wb-text));
  text-decoration: none;
  overflow: hidden;
  box-shadow: 0 1px 2px rgba(20, 18, 24, 0.03);
  transition: border-color var(--cr-dur-fast, 140ms) var(--cr-ease, ease);
}

.pc-card:hover {
  border-color: color-mix(in srgb, var(--cr-text-2, #62646A) 26%, var(--cr-border, #E4E3DF));
}

.pc-cover {
  position: relative;
  aspect-ratio: 4 / 3;
  background: var(--cr-media-stage, var(--wb-media-bg));
  display: grid;
  place-items: center;
  overflow: hidden;
}

.pc-cover img,
.pc-cover video {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}

.pc-placeholder {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
  color: var(--wb-muted);
}

.pc-placeholder em {
  font-size: 11px;
  font-style: normal;
}

.pc-kind,
.pc-archived {
  position: absolute;
  top: 8px;
  padding: 2px 8px;
  border-radius: 999px;
  font-size: 11px;
  line-height: 1.5;
  background: color-mix(in srgb, var(--wb-surface) 88%, transparent);
  border: 1px solid var(--wb-border);
  color: var(--wb-text-2);
}

.pc-kind { left: 8px; }
.pc-archived { right: 8px; color: var(--wb-muted); }

.pc-body {
  display: flex;
  flex-direction: column;
  gap: 4px;
  padding: 12px 14px 14px;
}

.pc-name {
  font-size: 14px;
  font-weight: 600;
  color: var(--cr-text, var(--wb-text));
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.pc-desc {
  margin: 0;
  font-size: 12.5px;
  color: var(--cr-text-2, var(--wb-text-2));
  line-height: 1.5;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
  min-height: 18px;
}

.pc-time {
  font-size: 11.5px;
  color: var(--wb-muted);
}
</style>
