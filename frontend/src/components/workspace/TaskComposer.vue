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
        <button v-if="attachmentsEnabled" class="tool-button" type="button" data-tooltip="上传图片（可多选）" aria-label="上传图片，可多选" @click="fileInput?.click()">
          <Paperclip :size="17" />
        </button>
        <button v-if="webSearchAvailable" class="tool-button" :class="{ active: webSearch }" type="button"
          :data-tooltip="webSearch ? '联网搜索：已开启，点击关闭' : '联网搜索：已关闭，点击开启'"
          :aria-label="webSearch ? '联网搜索已开启，点击关闭' : '联网搜索已关闭，点击开启'"
          :aria-pressed="webSearch" @click="webSearch = !webSearch">
          <Globe2 :size="17" />
        </button>
        <button v-if="knowledgeSearchAvailable" class="tool-button" :class="{ active: knowledgeSearch }" type="button"
          :data-tooltip="knowledgeSearch ? '知识库检索：已开启，点击关闭' : '知识库检索：已关闭，点击开启'"
          :aria-label="knowledgeSearch ? '知识库检索已开启，点击关闭' : '知识库检索已关闭，点击开启'"
          :aria-pressed="knowledgeSearch" @click="knowledgeSearch = !knowledgeSearch">
          <BookOpenCheck :size="17" />
        </button>
        <div v-if="permissionModes.length" ref="modeMenuRef" class="chip-anchor">
          <button type="button" class="chip permission-chip" :class="[`mode-${permissionMode.toLowerCase()}`, { open: modeMenuOpen }]"
            :title="modeHint" :aria-expanded="modeMenuOpen" aria-haspopup="listbox" @click="toggleModeMenu">
            <component :is="modeIcon(permissionMode)" :size="14" />
            <span>{{ currentModeLabel }}</span>
            <ChevronUp :size="12" class="chip-chevron" :class="{ open: modeMenuOpen }" />
          </button>
          <div v-if="modeMenuOpen" class="menu-panel mode-panel" role="listbox" aria-label="权限模式">
            <button v-for="mode in permissionModes" :key="mode.value" type="button" class="menu-row"
              :class="{ selected: mode.value === permissionMode }" role="option" :aria-selected="mode.value === permissionMode"
              @click="chooseMode(mode.value)">
              <span class="menu-icon"><component :is="modeIcon(mode.value)" :size="16" /></span>
              <span class="menu-copy"><strong>{{ mode.label }}</strong><small>{{ mode.hint }}</small></span>
              <Check v-if="mode.value === permissionMode" :size="15" class="menu-check" />
            </button>
          </div>
        </div>
        <span class="composer-hint">{{ connectionStatus === 'connecting' ? '任务执行中，可随时停止' : 'Enter 发送 · Shift+Enter 换行' }}</span>
        <div v-if="modelOptions.length" ref="modelMenuRef" class="chip-anchor model-anchor">
          <button type="button" class="chip model-chip" :class="{ open: modelMenuOpen }"
            :title="`当前模型：${currentModelLabel}`" :aria-expanded="modelMenuOpen" aria-haspopup="listbox" @click="toggleModelMenu">
            <Sparkles :size="14" />
            <span class="model-label">{{ currentModelLabel }}</span>
            <ChevronUp :size="12" class="chip-chevron" :class="{ open: modelMenuOpen }" />
          </button>
          <div v-if="modelMenuOpen" class="menu-panel model-panel" role="listbox" aria-label="选择模型">
            <p class="menu-heading">选择模型</p>
            <button v-for="option in modelOptions" :key="option.id" type="button" class="menu-row"
              :class="{ selected: option.id === activeModelId }" role="option" :aria-selected="option.id === activeModelId"
              @click="chooseModel(option)">
              <span class="menu-icon"><Cpu :size="16" /></span>
              <span class="menu-copy"><strong>{{ option.label }}</strong><small>{{ option.description }}</small></span>
              <Check v-if="option.id === activeModelId" :size="15" class="menu-check" />
            </button>
          </div>
        </div>
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
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import {
  ArrowUp, BookOpenCheck, Check, ChevronUp, Cpu, Globe2, Paperclip,
  Shield, ShieldAlert, ShieldCheck, Sparkles, Square, Zap
} from 'lucide-vue-next'

const MODE_STORAGE_KEY = 'zwx-permission-mode'

const props = defineProps({
  connectionStatus: { type: String, default: 'disconnected' },
  attachmentsEnabled: { type: Boolean, default: false },
  attachmentAccept: { type: String, default: 'image/jpeg,image/png,image/gif,image/webp' },
  webSearchAvailable: { type: Boolean, default: false },
  knowledgeSearchAvailable: { type: Boolean, default: false },
  permissionModes: { type: Array, default: () => [] },
  defaultPermissionMode: { type: String, default: 'EDIT' },
  modelOptions: { type: Array, default: () => [] },
  modelRowId: { type: Number, default: null },
  placeholder: { type: String, default: '描述目标、约束或需要交付的结果…' },
  disabled: { type: Boolean, default: false }
})
const emit = defineEmits(['send-message', 'stop-generation', 'model-change'])

const inputRef = ref(null)
const fileInput = ref(null)
const inputMessage = ref('')
const selectedImages = ref([])
const webSearch = ref(false)
const knowledgeSearch = ref(false)
const permissionMode = ref(
  localStorage.getItem(MODE_STORAGE_KEY)
  || props.defaultPermissionMode
  || 'EDIT'
)

const currentModeLabel = computed(() => {
  const mode = props.permissionModes.find(item => item.value === permissionMode.value)
  return mode ? mode.label : '权限'
})
const modeHint = computed(() => {
  const mode = props.permissionModes.find(item => item.value === permissionMode.value)
  return mode ? `${mode.label}：${mode.hint}` : '权限模式'
})

const MODE_ICONS = {
  PLAN: BookOpenCheck,
  BUILD: ShieldAlert,
  EDIT: ShieldCheck,
  GUARDED: Shield,
  YOLO: Zap
}
const modeIcon = value => MODE_ICONS[value] || ShieldCheck

const modeMenuOpen = ref(false)
const modelMenuOpen = ref(false)
const modeMenuRef = ref(null)
const modelMenuRef = ref(null)
const toggleModeMenu = () => { modeMenuOpen.value = !modeMenuOpen.value; modelMenuOpen.value = false }
const toggleModelMenu = () => { modelMenuOpen.value = !modelMenuOpen.value; modeMenuOpen.value = false }
const chooseMode = value => {
  permissionMode.value = value
  rememberMode()
  modeMenuOpen.value = false
}
const activeModelId = computed(() => {
  const requested = props.modelRowId
  if (requested && props.modelOptions.some(option => option.id === requested)) return requested
  return props.modelOptions[0]?.id ?? null
})
const currentModelOption = computed(() => props.modelOptions.find(option => option.id === activeModelId.value) || null)
const currentModelLabel = computed(() => currentModelOption.value?.label || '默认模型')
const chooseModel = option => {
  modelMenuOpen.value = false
  if (option.id !== activeModelId.value) emit('model-change', option.id)
}

const onDocumentPointerDown = event => {
  if (modeMenuOpen.value && !modeMenuRef.value?.contains(event.target)) modeMenuOpen.value = false
  if (modelMenuOpen.value && !modelMenuRef.value?.contains(event.target)) modelMenuOpen.value = false
}
const onDocumentKeydown = event => {
  if (event.key !== 'Escape') return
  modeMenuOpen.value = false
  modelMenuOpen.value = false
}
onMounted(() => {
  document.addEventListener('pointerdown', onDocumentPointerDown)
  document.addEventListener('keydown', onDocumentKeydown)
})

const rememberMode = () => localStorage.setItem(MODE_STORAGE_KEY, permissionMode.value)

// 用户尚未手动选择过模式时，跟随服务端默认值（设置页修改后生效）。
watch(() => props.defaultPermissionMode, value => {
  if (value && !localStorage.getItem(MODE_STORAGE_KEY)
    && props.permissionModes.some(mode => mode.value === value)) {
    permissionMode.value = value
  }
})

const canSend = computed(() => !props.disabled
  && (inputMessage.value.trim().length > 0 || selectedImages.value.length > 0))

const send = () => {
  if (props.connectionStatus === 'connecting' || !canSend.value) return
  const payload = { message: inputMessage.value.trim(), files: selectedImages.value.map(item => item.file) }
  if (props.webSearchAvailable) payload.webSearch = webSearch.value
  if (props.knowledgeSearchAvailable) payload.knowledgeSearch = knowledgeSearch.value
  if (props.permissionModes.length) payload.permissionMode = permissionMode.value
  if (activeModelId.value) payload.modelRowId = activeModelId.value
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
onBeforeUnmount(() => {
  document.removeEventListener('pointerdown', onDocumentPointerDown)
  document.removeEventListener('keydown', onDocumentKeydown)
  clearSelectedImages()
})

defineExpose({ fill, submit, focus })
</script>

<style scoped>
.task-composer { width: 100%; }

.composer-card {
  border: 1px solid var(--wb-border);
  border-radius: var(--wb-radius-lg);
  background: color-mix(in srgb, var(--wb-surface) 96%, transparent);
  box-shadow: var(--wb-shadow-md);
  backdrop-filter: var(--sk-blur);
  -webkit-backdrop-filter: var(--sk-blur);
  transition: border-color 0.18s ease, box-shadow 0.18s ease;
}

.composer-card:focus-within {
  border-color: var(--wb-brand);
  box-shadow: 0 0 0 3px var(--wb-brand-soft), var(--wb-shadow-md);
}

.composer-card.busy { border-color: color-mix(in srgb, var(--wb-brand) 28%, transparent); }

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
  color: var(--wb-text);
  font: inherit;
  font-size: 15px;
  line-height: 1.6;
}

.composer-input::placeholder { color: var(--wb-muted); }
.composer-input:disabled { opacity: 0.6; }

.composer-actions { display: flex; align-items: center; gap: 6px; padding: 4px 12px 11px; }

.tool-button {
  position: relative;
  display: grid;
  width: 30px;
  height: 30px;
  place-items: center;
  border: 0;
  border-radius: 9px;
  background: transparent;
  color: var(--wb-text-2);
  cursor: pointer;
}

.tool-button:hover { background: var(--wb-fill); color: var(--wb-text); }
.tool-button.active { background: var(--wb-brand-soft); color: var(--wb-brand); }

.chip-anchor { position: relative; display: inline-flex; }

.chip {
  display: inline-flex;
  height: 30px;
  align-items: center;
  gap: 5px;
  border: 1px solid transparent;
  border-radius: 8px;
  padding: 0 7px;
  background: transparent;
  color: var(--wb-text-2);
  font-size: 11.5px;
  font-weight: 550;
  cursor: pointer;
  white-space: nowrap;
}

.chip:hover, .chip.open { background: var(--wb-fill); color: var(--wb-text); }
.chip-chevron { color: var(--wb-muted); transition: transform 0.16s ease; }
.chip-chevron.open { transform: rotate(180deg); }

.permission-chip.mode-plan { color: var(--wb-brand); }
.permission-chip.mode-build { color: var(--wb-warning); }
.permission-chip.mode-guarded { color: var(--wb-info); }
.permission-chip.mode-yolo { color: var(--wb-danger); }

.model-chip { max-width: 190px; }
.model-label { overflow: hidden; text-overflow: ellipsis; }

.menu-panel {
  position: absolute;
  z-index: 40;
  bottom: calc(100% + 8px);
  left: 0;
  width: 306px;
  max-width: calc(100vw - 48px);
  border: 1px solid var(--wb-border);
  border-radius: var(--wb-radius);
  padding: 6px;
  background: var(--wb-surface);
  box-shadow: var(--wb-shadow-lg);
}

.model-panel { left: auto; right: 0; max-height: 320px; overflow-y: auto; }
.menu-heading { margin: 4px 8px 6px; color: var(--wb-muted); font-size: 10.5px; font-weight: 700; letter-spacing: 0.06em; }

.menu-row {
  display: grid;
  width: 100%;
  grid-template-columns: 30px minmax(0, 1fr) 18px;
  align-items: center;
  gap: 9px;
  border: 0;
  border-radius: 8px;
  padding: 8px;
  background: transparent;
  text-align: left;
  cursor: pointer;
}

.menu-row:hover { background: var(--wb-fill); }
.menu-row.selected { background: var(--wb-brand-soft); }

.menu-icon {
  display: grid;
  width: 30px;
  height: 30px;
  place-items: center;
  border-radius: 8px;
  background: var(--wb-fill);
  color: var(--wb-text-2);
}

.menu-row.selected .menu-icon { background: var(--wb-surface); color: var(--wb-brand); }
.menu-copy { display: flex; min-width: 0; flex-direction: column; gap: 2px; }
.menu-copy strong { color: var(--wb-text); font-size: 12.5px; font-weight: 650; }
.menu-copy small { color: var(--wb-text-2); font-size: 11px; line-height: 1.45; }
.menu-check { color: var(--wb-brand); }

.tool-button::after {
  position: absolute;
  z-index: 10;
  bottom: calc(100% + 8px);
  left: 50%;
  border-radius: 7px;
  padding: 4px 8px;
  background: rgba(23, 33, 58, 0.94);
  color: #fff;
  content: attr(data-tooltip);
  font-size: 10.5px;
  font-weight: 550;
  line-height: 1.4;
  white-space: nowrap;
  opacity: 0;
  pointer-events: none;
  transform: translateX(-50%) translateY(3px);
  transition: opacity 0.14s ease, transform 0.14s ease;
  box-shadow: 0 4px 12px rgba(15, 23, 42, 0.2);
}

.tool-button:hover::after, .tool-button:focus-visible::after {
  opacity: 1;
  transform: translateX(-50%) translateY(0);
}

.composer-hint { margin-left: auto; color: var(--wb-muted); font-size: 11.5px; white-space: nowrap; }

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
  background: linear-gradient(180deg, color-mix(in srgb, var(--wb-brand) 86%, #ffffff), var(--wb-brand));
  color: var(--wb-brand-contrast);
  box-shadow: 0 4px 10px var(--wb-brand-soft);
}

.send-button:hover:not(:disabled) { filter: brightness(1.06); }
.send-button:active:not(:disabled) { transform: scale(0.94); }
.send-button:disabled { background: var(--wb-fill); color: var(--wb-muted); box-shadow: none; cursor: default; }

.stop-button {
  border: 1px solid color-mix(in srgb, var(--wb-danger) 30%, transparent);
  background: color-mix(in srgb, var(--wb-danger) 10%, transparent);
  color: var(--wb-danger);
}

.stop-button:hover { background: color-mix(in srgb, var(--wb-danger) 16%, transparent); }

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
  background: var(--wb-fill);
  color: var(--wb-text-2);
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
