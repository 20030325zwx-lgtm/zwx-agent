<template>
  <section class="composer">
    <textarea
      ref="inputRef"
      v-model="draft"
      class="composer-input"
      :placeholder="placeholder"
      rows="2"
      @keydown.enter.exact.prevent="submit"
    />
    <div class="composer-bar">
      <div class="composer-left">
        <ASelect
          v-if="projects.length"
          :value="projectId ?? undefined"
          class="composer-project"
          :placeholder="projectPlaceholder"
          allow-clear
          size="small"
          :options="projectOptions"
          @change="projectId = $event ?? null"
        />
        <span v-if="modelLabel" class="composer-model" :title="`默认模型：${modelLabel}`">
          <Sparkles :size="13" :stroke-width="2" /> {{ modelLabel }}
        </span>
      </div>
      <AButton
        type="primary"
        class="composer-send"
        :loading="sending"
        :disabled="!draft.trim()"
        @click="submit"
      >
        开始任务
        <template #icon><ArrowUp :size="15" :stroke-width="2.4" /></template>
      </AButton>
    </div>
  </section>
</template>

<script setup>
import { computed, ref } from 'vue'
import { Button as AButton, Select as ASelect } from 'ant-design-vue'
import { ArrowUp, Sparkles } from 'lucide-vue-next'

const props = defineProps({
  projects: { type: Array, default: () => [] },
  modelLabel: { type: String, default: '' },
  sending: { type: Boolean, default: false },
  placeholder: { type: String, default: '描述你想完成的事：目标、背景、约束或期望的交付…' }
})
const emit = defineEmits(['start'])

const DRAFT_KEY = 'aster.workbench.draft'
const inputRef = ref(null)
const draft = ref('')
const projectId = ref(null)

const projectOptions = computed(() =>
  props.projects.map(project => ({ value: project.id, label: project.name }))
)
const projectPlaceholder = computed(() => (props.projects.length ? '关联项目（可选）' : '暂无项目'))

function submit() {
  const text = draft.value.trim()
  if (!text || props.sending) return
  emit('start', { text, projectId: projectId.value })
  draft.value = ''
  try { sessionStorage.removeItem(DRAFT_KEY) } catch (error) { /* ignore */ }
}

function setText(text) {
  draft.value = text
  inputRef.value?.focus()
}

function saveDraft() {
  try {
    if (draft.value.trim()) sessionStorage.setItem(DRAFT_KEY, draft.value)
  } catch (error) { /* ignore */ }
}

function popDraft() {
  try {
    const value = sessionStorage.getItem(DRAFT_KEY)
    if (value) draft.value = value
    return value || ''
  } catch (error) {
    return ''
  }
}

defineExpose({ setText, focus: () => inputRef.value?.focus(), popDraft, saveDraft })
</script>

<style scoped>
.composer {
  display: flex;
  flex-direction: column;
  padding: 16px 16px 12px;
  border: 1px solid var(--wb-border);
  border-radius: var(--wb-radius-lg);
  background: var(--wb-surface);
  box-shadow: var(--wb-shadow-md);
  transition: border-color 0.15s, box-shadow 0.15s;
}

.composer:focus-within {
  border-color: var(--wb-brand);
  box-shadow: 0 0 0 3px var(--wb-brand-soft), var(--wb-shadow-md);
}

.composer-input {
  min-height: 56px;
  max-height: 200px;
  resize: none;
  border: 0;
  outline: none;
  background: transparent;
  color: var(--wb-text);
  font-family: inherit;
  font-size: 15px;
  line-height: 1.6;
}

.composer-input::placeholder {
  color: var(--wb-muted);
}

.composer-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-top: 8px;
}

.composer-left {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
}

.composer-project {
  min-width: 180px;
}

.composer-model {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 3px 10px;
  border-radius: 999px;
  background: var(--wb-brand-soft);
  color: var(--wb-brand);
  font-size: 12px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
  max-width: 220px;
}

.composer-send {
  border-radius: 10px;
  font-weight: 500;
}

.composer-send:disabled {
  background: var(--wb-fill);
  color: var(--wb-muted);
  box-shadow: none;
}
</style>
