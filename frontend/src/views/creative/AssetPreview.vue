<template>
  <div class="ap-backdrop" role="dialog" aria-modal="true" :aria-label="`素材预览：${asset.displayName}`"
       @click.self="emit('close')">
    <div class="ap-panel">
      <header class="ap-head">
        <div class="ap-title">
          <strong>{{ asset.displayName }}</strong>
          <span class="ap-origin" :class="asset.origin === 'GENERATED' ? 'gen' : 'up'">
            {{ asset.origin === 'GENERATED' ? '生成结果' : '原始素材' }}
          </span>
        </div>
        <button type="button" class="ap-close" aria-label="关闭预览" @click="emit('close')">×</button>
      </header>
      <div class="ap-body">
        <img v-if="src" :src="src" :alt="asset.displayName" />
        <p v-else class="ap-hint">图片加载中…</p>
      </div>
      <footer class="ap-foot">
        <span class="ap-meta">
          {{ formatBytes(asset.byteSize) }}
          <template v-if="asset.width && asset.height"> · {{ asset.width }}×{{ asset.height }}</template>
          · {{ formatTime(asset.createdAt) }}
        </span>
        <div class="ap-actions">
          <slot name="actions" />
          <a v-if="src" class="cr-ghost" :href="src" :download="asset.displayName || 'asset.png'"
             target="_blank" rel="noopener">下载</a>
        </div>
      </footer>
    </div>
  </div>
</template>

<script setup>
import { formatBytes, formatTime } from './shared'

defineProps({
  asset: { type: Object, required: true },
  src: { type: String, default: '' }
})
const emit = defineEmits(['close'])
</script>

<style scoped>
.ap-backdrop {
  position: fixed;
  inset: 0;
  z-index: 90;
  display: flex;
  align-items: center;
  justify-content: center;
  background: rgba(18, 21, 30, 0.66);
  padding: 32px;
}
.ap-panel {
  display: flex;
  flex-direction: column;
  gap: 10px;
  width: min(860px, 100%);
  max-height: 100%;
  padding: 16px 18px;
  border-radius: 14px;
  background: var(--wb-surface);
  box-shadow: var(--wb-shadow-lg, 0 18px 50px rgba(0, 0, 0, 0.25));
}
.ap-head { display: flex; align-items: center; justify-content: space-between; gap: 10px; }
.ap-title { display: flex; align-items: center; gap: 8px; min-width: 0; }
.ap-title strong { font-size: 13.5px; color: var(--wb-text); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.ap-origin { padding: 1px 8px; border-radius: 999px; font-size: 11px; flex-shrink: 0; }
.ap-origin.gen { background: var(--wb-brand-soft); color: var(--wb-brand); }
.ap-origin.up { background: var(--wb-fill); color: var(--wb-text-2); }
.ap-close { border: 0; background: transparent; color: var(--wb-muted); font-size: 22px; line-height: 1; cursor: pointer; }
.ap-close:hover { color: var(--wb-text); }
.ap-body {
  flex: 1;
  min-height: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 10px;
  background: var(--wb-fill);
  overflow: auto;
}
.ap-body img { max-width: 100%; max-height: 64vh; object-fit: contain; }
.ap-hint { color: var(--wb-muted); font-size: 12.5px; }
.ap-foot { display: flex; align-items: center; justify-content: space-between; gap: 12px; flex-wrap: wrap; }
.ap-meta { color: var(--wb-muted); font-size: 12px; }
.ap-actions { display: flex; align-items: center; gap: 8px; }
</style>
