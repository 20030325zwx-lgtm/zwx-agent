<template>
  <figure class="ac">
    <button type="button" class="ac-media" :aria-label="`预览 ${asset.displayName}`" @click="$emit('open')">
      <video v-if="previewKind === 'video' && previewUrl" :src="previewUrl" muted preload="metadata"></video>
      <img v-else-if="previewUrl" :src="previewUrl" :alt="asset.displayName" />
      <span v-else class="ac-placeholder">{{ placeholder }}</span>
      <slot name="overlay" />
    </button>
    <figcaption class="ac-body">
      <div class="ac-title">
        <span class="ac-name" :title="asset.displayName">{{ asset.displayName }}</span>
        <em class="ac-origin" :class="asset.origin === 'UPLOAD' ? 'up' : 'gen'">
          {{ asset.origin === 'UPLOAD' ? '上传' : '生成' }}
        </em>
      </div>
      <div class="ac-meta">
        <span>{{ formatBytes(asset.byteSize) }}</span>
        <template v-if="asset.width && asset.height"><span>·</span><span>{{ asset.width }}×{{ asset.height }}</span></template>
        <span>·</span><span>{{ formatTime(asset.createdAt) }}</span>
      </div>
      <div class="ac-actions"><slot name="actions" /></div>
    </figcaption>
  </figure>
</template>

<script setup>
import { formatBytes, formatTime } from '../../views/creative/shared'

defineProps({
  asset: { type: Object, required: true },
  // 媒体地址由父级统一解析：缩略图走 blob，视频走签名播放地址
  previewUrl: { type: String, default: '' },
  previewKind: { type: String, default: '' },
  // 回收站里的素材不取媒体地址，用中性占位而不是一直「加载中」
  placeholder: { type: String, default: '加载中…' }
})
defineEmits(['open'])
</script>

<style scoped>
.ac {
  margin: 0;
  height: 100%;
  display: flex;
  flex-direction: column;
  border: 1px solid var(--wb-border);
  border-radius: var(--wb-radius);
  background: var(--wb-surface);
  overflow: hidden;
  transition: border-color 0.15s;
}

.ac:hover { border-color: var(--wb-border-strong); }

/* 固定媒体舞台：图片与视频都占同一块 4:3，网格不会被素材比例拉成锯齿 */
.ac-media {
  position: relative;
  display: block;
  width: 100%;
  aspect-ratio: 4 / 3;
  padding: 0;
  border: 0;
  background: var(--wb-media-bg);
  cursor: zoom-in;
  overflow: hidden;
}

.ac-media img { width: 100%; height: 100%; object-fit: cover; display: block; }
.ac-media video { width: 100%; height: 100%; object-fit: contain; display: block; }

.ac-placeholder {
  position: absolute;
  inset: 0;
  display: grid;
  place-items: center;
  color: var(--wb-muted);
  font-size: var(--wb-fs-caption);
  background: var(--wb-fill);
}

.ac-body {
  flex: 1;
  display: flex;
  flex-direction: column;
  gap: 3px;
  padding: 9px 11px 10px;
}

.ac-title { display: flex; align-items: center; gap: 6px; min-width: 0; }

.ac-name {
  min-width: 0;
  flex: 1;
  font-size: var(--wb-fs-compact);
  font-weight: 550;
  color: var(--wb-text);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.ac-origin {
  flex: 0 0 auto;
  font-style: normal;
  font-size: var(--wb-fs-caption);
  padding: 0 6px;
  border-radius: 999px;
  line-height: 18px;
}
.ac-origin.gen { background: var(--wb-brand-soft); color: var(--wb-brand); }
.ac-origin.up { background: var(--wb-fill); color: var(--wb-text-2); }

.ac-meta {
  display: flex;
  gap: 4px;
  font-size: var(--wb-fs-caption);
  color: var(--wb-muted);
  white-space: nowrap;
  overflow: hidden;
}

/* 操作固定在卡片底部，一行放不下就裁掉而不是把卡片撑高 */
.ac-actions {
  margin-top: auto;
  padding-top: 4px;
  display: flex;
  align-items: center;
  gap: 2px;
  flex-wrap: nowrap;
  overflow: hidden;
}

.ac-actions :deep(.cr-ghost),
.ac-actions :deep(button) {
  flex: 0 0 auto;
  padding: 2px 7px;
  font-size: var(--wb-fs-caption);
  white-space: nowrap;
}

.ac-actions :deep(.danger) { color: var(--wb-danger); }
</style>
