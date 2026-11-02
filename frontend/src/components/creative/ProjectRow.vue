<template>
  <router-link :to="`/creative/projects/${project.id}`" class="pr-row">
    <span class="pr-cover">
      <img v-if="thumbUrl" :src="thumbUrl" alt="" loading="lazy" />
      <video v-else-if="videoUrl" :src="videoUrl" preload="metadata" muted playsinline></video>
      <span v-else class="pr-placeholder">{{ kindLabel(project.kind) }}</span>
    </span>
    <span class="pr-main">
      <span class="pr-name">{{ project.name }}</span>
      <span class="pr-desc">{{ project.description || '暂无描述' }}</span>
    </span>
    <span class="pr-kind">{{ kindLabel(project.kind) }}</span>
    <span v-if="project.status === 'ARCHIVED'" class="pr-archived">已归档</span>
    <span class="pr-time">更新于 {{ formatTime(project.updatedAt) }}</span>
    <span class="pr-open">打开</span>
  </router-link>
</template>

<script setup>
import { computed, watch } from 'vue'
import { formatTime, kindLabel, useAssetUrls, useVideoUrls } from '../../views/creative/shared'

const props = defineProps({
  project: { type: Object, required: true }
})

const { thumbFor, thumbs } = useAssetUrls()
const { videoUrls, videoUrlFor } = useVideoUrls()

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
.pr-row {
  display: flex;
  align-items: center;
  gap: var(--wb-space-3);
  padding: 8px 12px;
  border-bottom: 1px solid var(--wb-border);
  color: var(--wb-text);
  text-decoration: none;
}

.pr-row:hover { background: var(--wb-fill); }

.pr-cover {
  flex: 0 0 88px;
  width: 88px;
  aspect-ratio: 16 / 9;
  border-radius: var(--wb-radius-sm);
  overflow: hidden;
  background: var(--wb-media-bg);
  display: grid;
  place-items: center;
}

.pr-cover img,
.pr-cover video { width: 100%; height: 100%; object-fit: cover; display: block; }

.pr-placeholder { font-size: var(--wb-fs-caption); color: var(--wb-muted); opacity: 0.7; }

.pr-main { flex: 1; min-width: 0; display: flex; flex-direction: column; gap: 2px; }

.pr-name {
  font-size: var(--wb-fs-compact);
  font-weight: 550;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.pr-desc {
  font-size: var(--wb-fs-caption);
  color: var(--wb-muted);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.pr-kind,
.pr-archived {
  flex: 0 0 auto;
  font-size: var(--wb-fs-caption);
  color: var(--wb-text-2);
  padding: 0 8px;
  border: 1px solid var(--wb-border);
  border-radius: 999px;
  line-height: 18px;
}

.pr-archived { color: var(--wb-muted); }

.pr-time { flex: 0 0 168px; font-size: var(--wb-fs-caption); color: var(--wb-muted); text-align: right; white-space: nowrap; }

.pr-open { flex: 0 0 auto; font-size: var(--wb-fs-caption); color: var(--wb-brand); }

@media (max-width: 900px) {
  .pr-time, .pr-desc { display: none; }
  .pr-cover { flex-basis: 64px; width: 64px; }
}
</style>
