<template>
  <div class="asset-field">
    <div v-if="isTemplate" class="asset-template">
      <code class="asset-template-value">{{ modelValue }}</code>
      <button type="button" class="asset-mini-btn" @click="switchToManual">改为手动选择/上传</button>
    </div>
    <template v-else>
      <div v-if="!multiple && modelValue" class="asset-selected">
        <img v-if="selectedPreview" :src="selectedPreview" alt="已引用素材缩略图" />
        <div v-else class="asset-selected-placeholder">图</div>
        <div class="asset-selected-copy">
          <strong>{{ selectedAsset?.displayName || '已引用素材' }}</strong>
          <span v-if="selectedLoading">正在确认素材…</span>
          <span v-else-if="selectedAsset?.status === 'READY'">已引用 · {{ selectedAsset.width && selectedAsset.height
            ? `${selectedAsset.width} × ${selectedAsset.height}` : formatSize(selectedAsset.byteSize) }}</span>
          <span v-else class="asset-error">素材信息不可用，请重新选择</span>
        </div>
        <button type="button" class="asset-remove" aria-label="移除已引用素材" @click="clearSingle">×</button>
      </div>
      <div class="asset-row">
        <DropdownSelect v-model="projectId" class="asset-project" size="sm" block
                        :options="projectOptions" :disabled="projectsLoading"
                        placeholder="选择项目" aria-label="素材所在项目" />
        <DropdownSelect v-model="selectedId" class="asset-select" size="sm" block
                        :options="assetOptions"
                        :placeholder="assetsLoading ? '素材加载中…' : (modelValue ? '更换素材' : '选择素材')"
                        :aria-label="multiple ? '追加素材' : '选择素材'"
                        :disabled="assetsLoading || !projectId" />
      </div>
      <div class="asset-row">
        <button type="button" class="asset-mini-btn" :disabled="uploading" @click="fileInput?.click()">
          {{ uploading ? '上传中…' : (mediaType === 'VIDEO' ? '上传视频' : '上传图片') }}
        </button>
        <span v-if="uploadError" class="asset-error">{{ uploadError }}</span>
        <span v-else-if="multiple && pickedIds.length" class="asset-count">已选 {{ pickedIds.length }} 个</span>
      </div>
      <div v-if="multiple && pickedIds.length" class="asset-chips">
        <span v-for="id in pickedIds" :key="id" class="asset-chip">
          {{ nameOf(id) || '已选素材' }}
          <button type="button" :aria-label="`移除 ${nameOf(id) || '已选素材'}`" @click="removeId(id)">×</button>
        </span>
      </div>
      <input ref="fileInput" type="file" :accept="acceptTypes" hidden @change="upload" />
    </template>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import DropdownSelect from '../ui/DropdownSelect.vue'
import {
  getCreativeAsset, getCreativeAssetThumbnail, listCreativeAssets, listCreativeProjects, uploadCreativeAsset
} from '../../api'

const props = defineProps({
  modelValue: { type: String, default: '' },
  multiple: { type: Boolean, default: false },
  defaultProjectId: { type: String, default: '' },
  // 媒体类型过滤（V1-A）：IMAGE（默认，兼容旧用法）/ VIDEO
  mediaType: { type: String, default: 'IMAGE' }
})
const emit = defineEmits(['update:modelValue'])

const acceptTypes = computed(() => props.mediaType === 'VIDEO'
  ? 'video/mp4,video/quicktime,video/webm'
  : 'image/png,image/jpeg,image/webp')

const projects = ref([])
const projectId = ref('')
const projectsLoading = ref(false)
const assets = ref([])
const assetsLoading = ref(false)
const selectedId = ref('')
const pickedIds = ref([])
const uploading = ref(false)
const uploadError = ref('')
const fileInput = ref(null)
const selectedAsset = ref(null)
const selectedPreview = ref('')
const selectedLoading = ref(false)
let selectedRequest = 0

const projectOptions = computed(() => projects.value.map(project => ({ value: project.id, label: project.name })))
const assetOptions = computed(() => {
  const visible = [...assets.value]
  if (selectedAsset.value && !visible.some(asset => asset.id === selectedAsset.value.id)) {
    visible.unshift(selectedAsset.value)
  }
  return visible.map(asset => ({
    value: asset.id,
    label: `${asset.displayName || asset.id.slice(0, 8)}（${formatSize(asset.byteSize)}）`
  }))
})

const isTemplate = computed(() => (props.modelValue || '').includes('{{'))
// 多选时把当前值拆成 ID 列表；单选直接是 ID
const parsedIds = computed(() => {
  const value = props.modelValue || ''
  if (!value || value.includes('{{')) return []
  return value.split(/[,，\s]+/).map(item => item.trim()).filter(Boolean)
})

watch(parsedIds, ids => {
  if (props.multiple) pickedIds.value = [...ids]
}, { immediate: true })

// 单选：外部值变化时回显（如父级预填/上传后刷新）
watch(() => props.modelValue, value => {
  if (!props.multiple && !(value || '').includes('{{')) selectedId.value = value || ''
}, { immediate: true })

watch(() => props.modelValue, async value => {
  const requestId = ++selectedRequest
  if (props.multiple || !value || value.includes('{{')) {
    selectedAsset.value = null
    selectedLoading.value = false
    releaseSelectedPreview()
    return
  }
  selectedLoading.value = true
  try {
    const [asset, thumbnail] = await Promise.all([
      getCreativeAsset(value),
      getCreativeAssetThumbnail(value).catch(() => null)
    ])
    if (requestId !== selectedRequest) return
    selectedAsset.value = asset
    releaseSelectedPreview()
    if (thumbnail) selectedPreview.value = URL.createObjectURL(thumbnail)
  } catch (error) {
    if (requestId === selectedRequest) selectedAsset.value = null
  } finally {
    if (requestId === selectedRequest) selectedLoading.value = false
  }
}, { immediate: true })

function switchToManual() {
  emit('update:modelValue', props.multiple ? '' : '')
}

function emitSingle(id) {
  emit('update:modelValue', id || '')
}

function clearSingle() {
  selectedId.value = ''
  emitSingle('')
}

function releaseSelectedPreview() {
  if (selectedPreview.value) URL.revokeObjectURL(selectedPreview.value)
  selectedPreview.value = ''
}

function emitMultiple() {
  emit('update:modelValue', pickedIds.value.join(','))
}

watch(selectedId, id => {
  if (!id) return
  if (props.multiple) {
    if (!pickedIds.value.includes(id)) pickedIds.value = [...pickedIds.value, id]
    emitMultiple()
    selectedId.value = ''
  } else {
    emitSingle(id)
  }
})

function removeId(id) {
  pickedIds.value = pickedIds.value.filter(item => item !== id)
  emitMultiple()
}

function nameOf(id) {
  const asset = assets.value.find(item => item.id === id)
  return asset ? (asset.displayName || '') : ''
}

function formatSize(bytes) {
  if (!bytes && bytes !== 0) return ''
  if (bytes > 1024 * 1024) return `${(bytes / 1024 / 1024).toFixed(1)} MB`
  return `${Math.max(1, Math.round(bytes / 1024))} KB`
}

async function upload(event) {
  const file = event.target.files?.[0]
  event.target.value = ''
  if (!file) return
  uploading.value = true
  uploadError.value = ''
  try {
    const asset = await uploadCreativeAsset(projectId.value, file)
    assets.value = [asset, ...assets.value]
    if (props.multiple) {
      pickedIds.value = [...pickedIds.value, asset.id]
      emitMultiple()
    } else {
      selectedId.value = asset.id
      emitSingle(asset.id)
    }
  } catch (error) {
    uploadError.value = error?.response?.data?.error || '上传失败，请重试'
  } finally {
    uploading.value = false
  }
}

async function loadAssets() {
  if (!projectId.value) { assets.value = []; return }
  assetsLoading.value = true
  try {
    const data = await listCreativeAssets({ projectId: projectId.value, mediaType: props.mediaType, limit: 30 })
    assets.value = (data.items || []).filter(asset => asset.status === 'READY')
  } catch (error) {
    assets.value = []
  } finally {
    assetsLoading.value = false
  }
}

watch(projectId, loadAssets)

onMounted(async () => {
  projectsLoading.value = true
  try {
    const data = await listCreativeProjects({ limit: 20 })
    projects.value = (data.items || []).filter(project => project.status === 'ACTIVE')
    projectId.value = (props.defaultProjectId && projects.value.some(p => p.id === props.defaultProjectId)
      ? props.defaultProjectId
      : projects.value[0]?.id) || ''
  } catch (error) {
    /* 保持空列表，选择器显示禁用 */
  } finally {
    projectsLoading.value = false
  }
})

onBeforeUnmount(releaseSelectedPreview)
</script>

<style scoped>
.asset-field { display: flex; flex-direction: column; gap: 6px; width: 100%; }
.asset-selected {
  display: grid;
  grid-template-columns: 48px minmax(0, 1fr) 24px;
  gap: 9px;
  align-items: center;
  padding: 8px;
  border: 1px solid var(--wb-border-strong, #c5cbd8);
  border-radius: var(--wb-radius-sm, 8px);
  background: var(--wb-fill, #f5f5f4);
}
.asset-selected img { width: 48px; height: 48px; object-fit: cover; border-radius: 6px; background: #e5e7eb; }
.asset-selected-placeholder { display: grid; place-items: center; width: 48px; height: 48px; border-radius: 6px; background: #e5e7eb; color: #737373; font-size: 12px; }
.asset-selected-copy { min-width: 0; display: flex; flex-direction: column; gap: 3px; }
.asset-selected-copy strong { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: 12.5px; }
.asset-selected-copy span { color: var(--wb-muted, #737373); font-size: 11.5px; }
.asset-remove { border: 0; background: transparent; color: var(--wb-muted, #737373); font-size: 18px; cursor: pointer; }
.asset-row { display: flex; gap: 6px; align-items: center; flex-wrap: wrap; }
.asset-project, .asset-select {
  min-width: 0;
  padding: 6px 8px;
  border: 1px solid var(--wf-border, #d8dce8);
  border-radius: 8px;
  background: #fff;
  color: var(--wf-text, #1f2430);
  font-size: 12.5px;
  height: auto;
}
.asset-project { flex: 0 0 110px; }
.asset-select { flex: 1; }
.asset-mini-btn {
  padding: 4px 10px;
  border: 1px solid var(--wf-border, #d8dce8);
  border-radius: 8px;
  background: #fff;
  color: var(--wf-brand, #2e6be6);
  font-size: 12px;
  cursor: pointer;
}
.asset-mini-btn:disabled { opacity: 0.6; cursor: not-allowed; }
.asset-template { display: flex; flex-direction: column; gap: 6px; align-items: flex-start; }
.asset-template-value {
  display: block;
  width: 100%;
  padding: 6px 8px;
  border-radius: 8px;
  background: var(--wf-fill, #f2f4f9);
  font-size: 12px;
  word-break: break-all;
}
.asset-error { color: #d64545; font-size: 12px; }
.asset-count { color: var(--wf-muted, #8a90a3); font-size: 12px; }
.asset-chips { display: flex; flex-wrap: wrap; gap: 6px; }
.asset-chip {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 2px 8px;
  border-radius: 999px;
  background: var(--wf-fill, #f2f4f9);
  font-size: 12px;
}
.asset-chip button { border: 0; background: transparent; cursor: pointer; color: #8a90a3; }
</style>
