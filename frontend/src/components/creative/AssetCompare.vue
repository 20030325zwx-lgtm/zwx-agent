<template>
  <div class="ac-backdrop" @click.self="$emit('close')">
    <div class="ac-panel" role="dialog" aria-modal="true" aria-label="素材对比选定">
      <header class="ac-head">
        <h2>对比选定 <span class="ac-sub">点击图片选定用于后续创作的一张</span></h2>
        <button type="button" class="ac-close" aria-label="关闭对比" @click="$emit('close')">×</button>
      </header>
      <div class="ac-body">
        <figure
          v-for="item in items"
          :key="item.asset.id"
          class="ac-card"
          :class="{ selected: selectedId === item.asset.id }"
          @click="selectedId = item.asset.id"
        >
          <img :src="item.src" :alt="item.asset.displayName" />
          <figcaption>
            <strong>{{ item.asset.displayName }}</strong>
            <span class="ac-meta">{{ meta(item.asset) }}</span>
            <em v-if="selectedId === item.asset.id" class="ac-picked">✓ 已选定</em>
            <em v-else class="ac-pick-hint">点击选定</em>
          </figcaption>
        </figure>
      </div>
      <footer class="ac-actions">
        <slot name="actions" :asset="selectedAsset" />
        <button type="button" class="ac-ghost" @click="$emit('close')">关闭</button>
      </footer>
    </div>
  </div>
</template>

<script setup>
import { computed, ref, watch } from 'vue'

/**
 * 双候选对比（Infinite Canvas 评审第一批 B1）：
 * 两张素材并排对比，人工点选其中一张；选定结果经 actions slot 交给使用方继续创作。
 */
const props = defineProps({
  items: { type: Array, required: true } // [{ asset, src }]
})
defineEmits(['close'])

const selectedId = ref('')
watch(() => props.items, () => { selectedId.value = '' }, { immediate: true })

const selectedAsset = computed(() =>
  props.items.find(item => item.asset.id === selectedId.value)?.asset || null)

function meta(asset) {
  const size = asset.width && asset.height ? `${asset.width}×${asset.height}` : ''
  const bytes = asset.byteSize ? `${Math.max(1, Math.round(asset.byteSize / 1024))} KB` : ''
  return [size, bytes, asset.origin === 'GENERATED' ? '生成' : asset.origin === 'DERIVED' ? '渲染产物' : '上传']
    .filter(Boolean).join(' · ')
}
</script>

<style scoped>
.ac-backdrop { position: fixed; inset: 0; z-index: 90; display: flex; align-items: center; justify-content: center; background: rgba(20, 24, 36, 0.5); }
.ac-panel { display: flex; flex-direction: column; gap: 12px; width: min(920px, calc(100vw - 48px)); max-height: calc(100vh - 64px); padding: 16px 18px; border-radius: 14px; background: var(--wb-surface, #fff); box-shadow: 0 18px 48px rgba(20, 24, 36, 0.28); }
.ac-head { display: flex; align-items: center; justify-content: space-between; }
.ac-head h2 { font-size: 15px; font-weight: 600; color: var(--wb-text, #1f2430); }
.ac-sub { margin-left: 8px; color: var(--wb-muted, #8a90a6); font-size: 12px; font-weight: 400; }
.ac-close { border: 0; background: transparent; font-size: 20px; line-height: 1; color: var(--wb-muted, #8a90a6); }
.ac-close:hover { color: var(--wb-text, #1f2430); }
.ac-body { display: grid; grid-template-columns: 1fr 1fr; gap: 14px; overflow-y: auto; min-height: 0; }
.ac-card { margin: 0; display: flex; flex-direction: column; gap: 8px; padding: 10px; border: 2px solid var(--wb-border, #e3e6ef); border-radius: 12px; cursor: pointer; }
.ac-card:hover { border-color: var(--wb-brand, #4f6ef7); }
.ac-card.selected { border-color: var(--wb-brand, #4f6ef7); box-shadow: 0 0 0 3px var(--wb-brand-soft, rgba(79, 110, 247, 0.14)); }
.ac-card img { width: 100%; max-height: 52vh; object-fit: contain; border-radius: 8px; background: var(--wb-surface-2, #f5f6fa); }
.ac-card figcaption { display: flex; flex-direction: column; gap: 4px; }
.ac-card strong { color: var(--wb-text, #1f2430); font-size: 13px; }
.ac-meta { color: var(--wb-muted, #8a90a6); font-size: 12px; }
.ac-picked { color: var(--wb-success, #2e9e5b); font-size: 12px; font-style: normal; font-weight: 600; }
.ac-pick-hint { color: var(--wb-muted, #8a90a6); font-size: 12px; font-style: normal; }
.ac-actions { display: flex; justify-content: flex-end; align-items: center; gap: 10px; border-top: 1px solid var(--wb-border, #e3e6ef); padding-top: 12px; }
.ac-ghost { padding: 7px 14px; border: 1px solid var(--wb-border, #e3e6ef); border-radius: 9px; background: transparent; color: var(--wb-text-2, #5b6472); font-size: 12.5px; }
.ac-ghost:hover { border-color: var(--wb-brand, #4f6ef7); color: var(--wb-brand, #4f6ef7); }
</style>
