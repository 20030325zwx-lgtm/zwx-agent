<template>
  <div class="asset-field">
    <div v-if="isTemplate" class="asset-template">
      <code class="asset-template-value">{{ modelValue }}</code>
      <button type="button" class="asset-mini-btn" @click="switchToManual">改为手动选择/上传</button>
    </div>
    <template v-else>
      <div class="asset-row">
        <select v-model="projectId" class="wf-input compact asset-project" aria-label="素材所在项目"
                :disabled="projectsLoading">
          <option v-for="project in projects" :key="project.id" :value="project.id">
            {{ project.name }}
          </option>
        </select>
        <select v-model="selectedId" class="wf-input compact asset-select" :aria-label="multiple ? '追加素材' : '选择素材'"
                :disabled="assetsLoading || !projectId">
          <option value="" disabled>{{ assetsLoading ? '素材加载中…' : '选择素材' }}</option>
          <option v-for="asset in assets" :key="asset.id" :value="asset.id">
            {{ asset.displayName || asset.id.slice(0, 8) }}（{{ formatSize(asset.byteSize) }}）
          </option>
        </select>
      </div>
      <div class="asset-row">
        <button type="button" class="asset-mini-btn" :disabled="uploading" @click="fileInput?.click()">
          {{ uploading ? '上传中…' : '上传图片' }}
        </button>
        <span v-if="uploadError" class="asset-error">{{ uploadError }}</span>
        <span v-else-if="multiple && pickedIds.length" class="asset-count">已选 {{ pickedIds.length }} 张</span>
      </div>
      <div v-if="multiple && pickedIds.length" class="asset-chips">
        <span v-for="id in pickedIds" :key="id" class="asset-chip">
          {{ nameOf(id) || id.slice(0, 8) }}
          <button type="button" :aria-label="`移除 ${nameOf(id) || id.slice(0, 8)}`" @click="removeId(id)">×</button>
        </span>
      </div>
      <input ref="fileInput" type="file" accept="image/png,image/jpeg,image/webp" hidden @change="upload" />
    </template>
  </div>
</template>

<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { listCreativeAssets, listCreativeProjects, uploadCreativeAsset } from '../../api'

const props = defineProps({
  modelValue: { type: String, default: '' },
  multiple: { type: Boolean, default: false }
})
const emit = defineEmits(['update:modelValue'])

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

const isTemplate = computed(() => (props.modelValue || '').includes('{{'))
// 多选时把当前值拆成 ID 列表；单选直接是 ID
const parsedIds = computed(() => {
  const value = props.modelValue || ''
  if (!value || value.includes('{{')) return []
  return value.split(/[,,\s]+/).map(item => item.trim()).filter(Boolean)
})

watch(parsedIds, ids => {
  if (props.multiple) pickedIds.value = [...ids]
}, { immediate: true })

// 单选：外部值变化时回显（如父级预填/上传后刷新）
watch(() => props.modelValue, value => {
  if (!props.multiple && value && !value.includes('{{')) {
    selectedId.value = value
  }
}, { immediate: true })

function switchToManual() {
  emit('update:modelValue', props.multiple ? '' : '')
}

function emitSingle(id) {
  emit('update:modelValue', id || '')
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
    const data = await listCreativeAssets({ projectId: projectId.value, mediaType: 'IMAGE', limit: 30 })
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
    projectId.value = projects.value[0]?.id || ''
  } catch (error) {
    /* 保持空列表，选择器显示禁用 */
  } finally {
    projectsLoading.value = false
  }
})
</script>

<style scoped>
.asset-field { display: flex; flex-direction: column; gap: 6px; width: 100%; }
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
