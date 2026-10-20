<template>
  <div class="file-preview-shell">
    <div v-if="loading" class="preview-state">
      <span class="preview-spinner" aria-hidden="true"></span>
      <strong>正在加载原始文档...</strong>
      <span>首次打开大型 PDF 或 Office 文档需要稍等片刻。</span>
    </div>
    <div v-else-if="error" class="preview-state error">
      <FileWarning :size="26" />
      <strong>文档加载失败</strong>
      <span>{{ error }}</span>
      <button type="button" @click="load">重新加载</button>
    </div>
    <FileViewer v-else-if="file" class="file-viewer" :file="file" :name="filename" :filename="filename" :options="viewerOptions" />
    <div v-else class="preview-state">
      <BookOpen :size="26" />
      <strong>选择左侧文档开始浏览</strong>
      <span>这里会直接渲染原始文档，而不是解析后的纯文本。</span>
    </div>
  </div>
</template>

<script setup>
import { ref, watch } from 'vue'
import { BookOpen, FileWarning } from 'lucide-vue-next'
import { FileViewer } from '@file-viewer/vue3'
import officePreset from '@file-viewer/preset-office'
import litePreset from '@file-viewer/preset-lite'
import { getAgentKnowledgeDocumentFile } from '../api'

const props = defineProps({
  agentKey: { type: String, required: true },
  documentId: { type: String, default: '' },
  filename: { type: String, default: '' },
  contentType: { type: String, default: '' }
})

const viewerOptions = {
  preset: [officePreset, litePreset],
  rendererMode: 'replace',
  theme: 'light',
  locale: 'zh-CN',
  styleIsolation: 'shadow',
  ui: { density: 'compact' },
  toolbar: { position: 'bottom-right', download: true, print: true, exportHtml: false, zoom: true },
  search: { enabled: true },
  pdf: { toolbar: true, streaming: false },
  docx: { worker: true, progressive: true }
}

const file = ref(null)
const loading = ref(false)
const error = ref('')
let requestSequence = 0

const load = async () => {
  const documentId = props.documentId
  if (!documentId) {
    file.value = null
    error.value = ''
    loading.value = false
    return
  }
  const sequence = ++requestSequence
  loading.value = true
  error.value = ''
  file.value = null
  try {
    const blob = await getAgentKnowledgeDocumentFile(props.agentKey, documentId)
    if (sequence !== requestSequence) return
    const name = props.filename || 'document'
    file.value = new File([blob], name, { type: props.contentType || blob.type || 'application/octet-stream' })
  } catch (requestError) {
    if (sequence !== requestSequence) return
    error.value = requestError?.response?.status === 404
      ? '原始文件不存在或已被删除。'
      : '无法读取原始文件，请确认对象存储服务正常。'
    console.error(requestError)
  } finally {
    if (sequence === requestSequence) loading.value = false
  }
}

watch(() => [props.agentKey, props.documentId], load, { immediate: true })
</script>

<style scoped>
.file-preview-shell {
  display: flex;
  min-height: 0;
  height: 100%;
  flex: 1;
  flex-direction: column;
  background: #f7f8fb;
}

.file-viewer {
  width: 100%;
  height: 100%;
  min-height: 0;
  flex: 1;
}

.preview-state {
  display: grid;
  flex: 1;
  place-content: center;
  justify-items: center;
  gap: 7px;
  padding: 28px;
  color: var(--sk-label-2);
  font-size: 12px;
  text-align: center;
}

.preview-state strong { color: var(--sk-label); font-size: 14px; }
.preview-state.error { color: var(--sk-red); }
.preview-state button { margin-top: 4px; border: 0; border-radius: 9px; padding: 7px 14px; background: var(--zwx-primary-soft); color: var(--zwx-primary); font-size: 12px; font-weight: 650; }

.preview-spinner {
  width: 28px;
  height: 28px;
  border: 3px solid var(--zwx-primary-soft);
  border-top-color: var(--zwx-primary);
  border-radius: 50%;
  animation: preview-spin 0.8s linear infinite;
}

@keyframes preview-spin { to { transform: rotate(360deg); } }
</style>
