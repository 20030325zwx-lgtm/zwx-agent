<template>
  <div class="task-composer">
    <div class="composer-card" :class="{ busy: connectionStatus === 'connecting' }">
      <div v-if="selectedImages.length" class="selected-images">
        <div v-for="attachment in selectedImages" :key="attachment.preview || attachment.file.name" class="selected-image">
          <img v-if="attachment.preview" :src="attachment.preview" alt="待发送图片" />
          <span v-else class="selected-file-name">{{ attachment.file.name }}</span>
          <button type="button" title="移除图片" aria-label="移除图片" @click="removeImage(attachment)">×</button>
        </div>
      </div>
      <div class="composer-input-row">
        <textarea
          ref="inputRef"
          v-model="inputMessage"
          class="composer-input"
          rows="1"
          :placeholder="placeholder"
          :disabled="disabled"
          @keydown.enter.exact.prevent="send"
          @paste="pasteImages"
        ></textarea>
      </div>
      <div class="composer-actions">
        <input ref="fileInput" class="file-input" type="file" :accept="attachmentAccept" multiple @change="selectImages" />
        <button v-if="attachmentsEnabled" class="tool-button" type="button" title="上传图片" aria-label="上传图片" @click="fileInput?.click()">
          <Paperclip :size="17" />
        </button>
        <button v-if="webSearchAvailable" class="tool-button" :class="{ active: webSearch }" type="button"
          :aria-pressed="webSearch" :title="webSearch ? '本轮允许联网搜索' : '本轮不联网搜索'" @click="webSearch = !webSearch">
          <Globe2 :size="17" />
        </button>
        <button v-if="knowledgeSearchAvailable" class="tool-button" :class="{ active: knowledgeSearch }" type="button"
          :aria-pressed="knowledgeSearch" :title="knowledgeSearch ? '本轮先检索知识库' : '本轮不检索知识库'" @click="knowledgeSearch = !knowledgeSearch">
          <BookOpenCheck :size="17" />
        </button>
        <span class="composer-hint">{{ connectionStatus === 'connecting' ? '任务执行中，可随时停止' : 'Enter 发送 · Shift+Enter 换行' }}</span>
        <button v-if="connectionStatus === 'connecting'" class="stop-button" type="button" title="停止生成" aria-label="停止生成" @click="emit('stop-generation')">
          <Square :size="14" fill="currentColor" />
        </button>
        <button v-else class="send-button" type="button" :disabled="!canSend" aria-label="发送任务" @click="send">
          <ArrowUp :size="18" />
        </button>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, ref } from 'vue'
import { ArrowUp, BookOpenCheck, Globe2, Paperclip, Square } from 'lucide-vue-next'

const props = defineProps({
  connectionStatus: { type: String, default: 'disconnected' },
  attachmentsEnabled: { type: Boolean, default: false },
  attachmentAccept: { type: String, default: 'image/jpeg,image/png,image/gif,image/webp' },
  webSearchAvailable: { type: Boolean, default: false },
  knowledgeSearchAvailable: { type: Boolean, default: false },
  placeholder: { type: String, default: '描述目标、约束或需要交付的结果…' },
  disabled: { type: Boolean, default: false }
})
const emit = defineEmits(['send-message', 'stop-generation'])

const inputRef = ref(null)
const fileInput = ref(null)
const inputMessage = ref('')
const selectedImages = ref([])
const webSearch = ref(false)
const knowledgeSearch = ref(false)

const canSend = computed(() => !props.disabled
  && (inputMessage.value.trim().length > 0 || selectedImages.value.length > 0))

const send = () => {
  if (props.connectionStatus === 'connecting' || !canSend.value) return
  const payload = { message: inputMessage.value.trim(), files: selectedImages.value.map(item => item.file) }
  if (props.webSearchAvailable) payload.webSearch = webSearch.value
  if (props.knowledgeSearchAvailable) payload.knowledgeSearch = knowledgeSearch.value
  emit('send-message', payload)
  inputMessage.value = ''
  clearSelectedImages()
  if (fileInput.value) fileInput.value.value = ''
}
const fill = text => {
  inputMessage.value = text
  nextTick(() => inputRef.value?.focus())
}
const submit = text => {
  if (text) inputMessage.value = text
  nextTick(send)
}
const focus = () => nextTick(() => inputRef.value?.focus())
const selectImages = event => {
  addImages(Array.from(event.target.files || []))
  if (fileInput.value) fileInput.value.value = ''
}
const pasteImages = event => {
  if (!props.attachmentsEnabled) return
  const files = Array.from(event.clipboardData?.files || []).filter(file => file.type.startsWith('image/'))
  if (!files.length) return
  event.preventDefault()
  addImages(files)
}
const addImages = files => {
  selectedImages.value.push(...files.map(file => ({
    file,
    preview: file.type.startsWith('image/') ? URL.createObjectURL(file) : ''
  })))
}
const removeImage = attachment => {
  if (attachment.preview) URL.revokeObjectURL(attachment.preview)
  selectedImages.value = selectedImages.value.filter(item => item !== attachment)
}
const clearSelectedImages = () => {
  selectedImages.value.forEach(item => { if (item.preview) URL.revokeObjectURL(item.preview) })
  selectedImages.value = []
}
onBeforeUnmount(clearSelectedImages)

defineExpose({ fill, submit, focus })
</script>

<style scoped>
.task-composer { width: 100%; }

.composer-card {
  border: 1px solid var(--sk-separator);
  border-radius: 20px;
  background: rgba(255, 255, 255, 0.96);
  box-shadow: 0 12px 32px rgba(37, 73, 133, 0.12);
  backdrop-filter: var(--sk-blur);
  -webkit-backdrop-filter: var(--sk-blur);
  transition: border-color 0.18s ease, box-shadow 0.18s ease;
}

.composer-card:focus-within {
  border-color: var(--zwx-primary-ring);
  box-shadow: 0 0 0 3px var(--zwx-primary-ring), 0 12px 32px rgba(37, 73, 133, 0.14);
}

.composer-card.busy { border-color: rgba(37, 99, 235, 0.24); }

.composer-input-row { display: flex; padding: 14px 16px 4px; }

.composer-input {
  width: 100%;
  min-height: 44px;
  max-height: 150px;
  resize: none;
  overflow-y: auto;
  border: 0;
  outline: 0;
  background: transparent;
  color: var(--sk-label);
  font: inherit;
  font-size: 15px;
  line-height: 1.6;
}

.composer-input::placeholder { color: var(--sk-label-3); }
.composer-input:disabled { opacity: 0.6; }

.composer-actions { display: flex; align-items: center; gap: 6px; padding: 4px 12px 11px; }

.tool-button {
  display: grid;
  width: 30px;
  height: 30px;
  place-items: center;
  border: 0;
  border-radius: 9px;
  background: transparent;
  color: var(--sk-label-2);
  cursor: pointer;
}

.tool-button:hover { background: var(--sk-fill); color: var(--sk-label); }
.tool-button.active { background: var(--zwx-primary-soft); color: var(--zwx-primary); }

.composer-hint { margin-right: auto; color: var(--sk-label-3); font-size: 11.5px; }

.send-button, .stop-button {
  display: grid;
  width: 34px;
  height: 34px;
  place-items: center;
  border: 0;
  border-radius: 50%;
  padding: 0;
  cursor: pointer;
}

.send-button {
  background: linear-gradient(180deg, color-mix(in srgb, var(--zwx-primary) 88%, #ffffff), var(--zwx-primary));
  color: #fff;
  box-shadow: 0 4px 10px var(--zwx-primary-ring);
}

.send-button:hover:not(:disabled) { filter: brightness(1.06); }
.send-button:active:not(:disabled) { transform: scale(0.94); }
.send-button:disabled { background: var(--sk-fill-strong); color: var(--sk-label-3); box-shadow: none; cursor: default; }

.stop-button {
  border: 1px solid rgba(255, 59, 48, 0.28);
  background: rgba(255, 59, 48, 0.1);
  color: var(--sk-red);
}

.stop-button:hover { background: rgba(255, 59, 48, 0.16); }

.file-input { display: none; }

.selected-images {
  display: flex;
  gap: 7px;
  max-width: 100%;
  padding: 10px 14px 0;
  overflow-x: auto;
}

.selected-image { position: relative; width: 48px; height: 48px; flex: 0 0 48px; }
.selected-image img { width: 100%; height: 100%; border-radius: 9px; object-fit: cover; }

.selected-file-name {
  display: block;
  width: 100%;
  height: 100%;
  overflow: hidden;
  border-radius: 9px;
  padding: 5px;
  background: var(--sk-fill);
  color: var(--sk-label-2);
  font-size: 9px;
  text-overflow: ellipsis;
}

.selected-image button {
  position: absolute;
  top: -5px;
  right: -5px;
  display: grid;
  width: 17px;
  height: 17px;
  place-items: center;
  border: 1px solid #fff;
  border-radius: 50%;
  background: rgba(29, 29, 31, 0.85);
  color: #fff;
  line-height: 1;
  cursor: pointer;
}

@media (max-width: 720px) {
  .composer-input-row { padding: 12px 13px 2px; }
  .composer-actions { padding: 4px 10px 9px; }
  .composer-hint { display: none; }
}

@media (prefers-reduced-motion: reduce) {
  .composer-card, .send-button { transition: none; }
}
</style>
