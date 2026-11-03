<template>
  <div class="reference-strip">
    <div class="reference-head">
      <span>{{ label }}</span>
      <strong>{{ evidence }}</strong>
    </div>
    <div class="reference-items">
      <div v-for="item in items" :key="item.id" class="reference-item">
        <img v-if="item.preview" :src="item.preview" alt="任务输入素材" />
        <div v-else class="reference-placeholder">图</div>
        <span>{{ item.displayName || `素材 ${item.id.slice(0, 8)}` }}</span>
      </div>
    </div>
  </div>
</template>

<script setup>
import { onBeforeUnmount, ref, watch } from 'vue'
import { getCreativeAsset, getCreativeAssetThumbnail } from '../../api'

const props = defineProps({
  assetIds: { type: Array, default: () => [] },
  label: { type: String, default: '输入素材' },
  evidence: { type: String, default: '已固化到任务输入' }
})

const items = ref([])
let requestId = 0

function release() {
  for (const item of items.value) if (item.preview) URL.revokeObjectURL(item.preview)
  items.value = []
}

watch(() => (props.assetIds || []).join(','), async () => {
  const current = ++requestId
  release()
  const unique = [...new Set((props.assetIds || []).filter(Boolean))]
  const loaded = await Promise.all(unique.map(async id => {
    const [asset, thumbnail] = await Promise.all([
      getCreativeAsset(id).catch(() => ({ id, displayName: '' })),
      getCreativeAssetThumbnail(id).catch(() => null)
    ])
    return { ...asset, id, preview: thumbnail ? URL.createObjectURL(thumbnail) : '' }
  }))
  if (current !== requestId) {
    for (const item of loaded) if (item.preview) URL.revokeObjectURL(item.preview)
    return
  }
  items.value = loaded
}, { immediate: true })

onBeforeUnmount(() => { requestId += 1; release() })
</script>

<style scoped>
.reference-strip { display: flex; flex-direction: column; gap: 7px; padding: 9px; border: 1px solid var(--wb-border); border-radius: 8px; background: var(--wb-fill); }
.reference-head { display: flex; justify-content: space-between; gap: 12px; font-size: 12px; color: var(--wb-text-2); }
.reference-head strong { color: var(--wb-success, #15803d); font-weight: 550; text-align: right; }
.reference-items { display: flex; flex-wrap: wrap; gap: 8px; }
.reference-item { display: flex; align-items: center; gap: 7px; min-width: 0; font-size: 12px; color: var(--wb-text); }
.reference-item img, .reference-placeholder { width: 38px; height: 38px; flex: 0 0 auto; border-radius: 6px; object-fit: cover; background: var(--wb-surface); }
.reference-placeholder { display: grid; place-items: center; color: var(--wb-muted); }
.reference-item span { max-width: 190px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
</style>
