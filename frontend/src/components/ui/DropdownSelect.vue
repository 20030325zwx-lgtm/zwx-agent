<template>
  <div ref="root" class="ds" :class="{ 'is-block': block, 'is-open': open, [`ds-${size}`]: true, 'ds-pill': variant === 'pill' }">
    <button
      type="button"
      class="ds-trigger"
      role="combobox"
      :aria-expanded="open ? 'true' : 'false'"
      :aria-label="ariaLabel || placeholder"
      :disabled="disabled"
      @click="toggle"
      @keydown="onKeydown"
    >
      <span class="ds-label" :class="{ 'is-placeholder': !selectedLabel }">{{ selectedLabel || placeholder }}</span>
      <ChevronDown :size="14" :stroke-width="2" class="ds-arrow" />
    </button>

    <ul v-if="open" class="ds-menu" role="listbox" :aria-label="ariaLabel || placeholder">
      <li
        v-for="(option, index) in options"
        :key="String(option.value)"
        role="option"
        :aria-selected="isSelected(option) ? 'true' : 'false'"
        class="ds-option"
        :class="{ 'is-active': index === activeIndex, 'is-selected': isSelected(option), 'is-disabled': option.disabled }"
        @mousemove="activeIndex = index"
        @click="choose(option)"
      >
        <span class="ds-option-main">
          <span class="ds-option-label">{{ option.label }}</span>
          <small v-if="option.hint" class="ds-option-hint">{{ option.hint }}</small>
        </span>
        <Check v-if="isSelected(option)" :size="14" />
      </li>
      <li v-if="!options.length" class="ds-empty">没有可选项</li>
    </ul>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { Check, ChevronDown } from 'lucide-vue-next'

const props = defineProps({
  modelValue: { type: [String, Number, Boolean], default: '' },
  // [{ value, label, disabled? }]
  options: { type: Array, default: () => [] },
  placeholder: { type: String, default: '请选择' },
  ariaLabel: { type: String, default: '' },
  disabled: { type: Boolean, default: false },
  block: { type: Boolean, default: false },
  size: { type: String, default: 'md' },
  // default：表单式；pill：创作台工具条上的胶囊按钮
  variant: { type: String, default: 'default' }
})
const emit = defineEmits(['update:modelValue', 'change'])

const root = ref(null)
const open = ref(false)
const activeIndex = ref(-1)

// 原生 select 的 value 是字符串；这里把 null/undefined 统一按空串比较
const normalize = value => (value === null || value === undefined ? '' : String(value))
const isSelected = option => normalize(option.value) === normalize(props.modelValue)
const selectedOption = computed(() => props.options.find(option => isSelected(option)) || null)
const selectedLabel = computed(() => (selectedOption.value ? selectedOption.value.label : ''))

function toggle() {
  if (props.disabled) return
  open.value = !open.value
  if (open.value) {
    activeIndex.value = Math.max(0, props.options.findIndex(option => isSelected(option)))
  }
}

function close() {
  open.value = false
}

function choose(option) {
  if (option.disabled) return
  close()
  emit('update:modelValue', option.value)
  emit('change', option.value)
}

function onKeydown(event) {
  if (props.disabled) return
  if (event.key === 'Escape') {
    close()
    return
  }
  if (!open.value) {
    if (event.key === 'Enter' || event.key === ' ' || event.key === 'ArrowDown') {
      event.preventDefault()
      toggle()
    }
    return
  }
  if (event.key === 'ArrowDown') {
    event.preventDefault()
    activeIndex.value = Math.min(props.options.length - 1, activeIndex.value + 1)
    return
  }
  if (event.key === 'ArrowUp') {
    event.preventDefault()
    activeIndex.value = Math.max(0, activeIndex.value - 1)
    return
  }
  if (event.key === 'Enter' || event.key === ' ') {
    event.preventDefault()
    const option = props.options[activeIndex.value]
    if (option) choose(option)
  }
}

function onDocumentClick(event) {
  if (open.value && root.value && !root.value.contains(event.target)) close()
}

onMounted(() => document.addEventListener('mousedown', onDocumentClick))
onBeforeUnmount(() => document.removeEventListener('mousedown', onDocumentClick))
</script>

<style scoped>
/* 使用工作台令牌，回退到编排器令牌，保证在创作页与工作流弹窗里都能用 */
.ds { position: relative; display: inline-block; min-width: 0; }
.ds.is-block { display: block; width: 100%; }

.ds-trigger {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  width: 100%;
  min-height: 32px;
  padding: 0 9px;
  border: 1px solid var(--wb-border, #d8dce8);
  border-radius: var(--wb-radius-sm, 8px);
  background: var(--wb-surface, #fff);
  color: var(--wb-text, #1f2430);
  font-size: var(--wb-fs-compact, 13px);
  font-family: inherit;
  text-align: left;
  cursor: pointer;
}

.ds-trigger:hover:not(:disabled) { border-color: var(--wb-border-strong, #c5cbd8); }
.ds-trigger:focus-visible { outline: 2px solid var(--wb-brand, #2563eb); outline-offset: 1px; }
.ds-trigger:disabled { opacity: 0.6; cursor: not-allowed; }

.ds-sm .ds-trigger { min-height: 28px; font-size: var(--wb-fs-caption, 12px); padding: 0 8px; }

/* 胶囊变体：与创作台「参考素材」等工具按钮同一形态 */
.ds-pill .ds-trigger {
  min-height: 32px;
  height: 32px;
  padding: 0 12px;
  border-radius: 999px;
  background: transparent;
  color: var(--cr-text-2, var(--wb-text-2, #5e626b));
  font-size: 12.5px;
  transition: border-color 0.16s, color 0.16s;
}

.ds-pill .ds-trigger:hover:not(:disabled),
.ds-pill.is-open .ds-trigger {
  border-color: var(--cr-text-2, var(--wb-border-strong, #cdcfd4));
  color: var(--cr-text, var(--wb-text, #1f2430));
}

.ds-pill .ds-label.is-placeholder { color: var(--cr-muted, var(--wb-muted, #8d919a)); }

.ds-pill .ds-menu {
  min-width: max(100%, 220px);
  max-width: 380px;
  border-radius: 12px;
  padding: 6px;
}

.ds-label {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.ds-label.is-placeholder { color: var(--wb-muted, #8a90a6); }

.ds-arrow {
  flex: 0 0 auto;
  color: var(--wb-muted, #8a90a6);
  transition: transform 0.15s ease;
}

.ds.is-open .ds-arrow { transform: rotate(180deg); }

.ds-menu {
  position: absolute;
  z-index: 120;
  top: calc(100% + 4px);
  left: 0;
  right: 0;
  /* 触发按钮很窄时（如"分类：镜头"）也要给选项留够宽度 */
  min-width: max(100%, 132px);
  max-width: 320px;
  max-height: 260px;
  overflow-y: auto;
  margin: 0;
  padding: 4px;
  list-style: none;
  border: 1px solid var(--wb-border, #d8dce8);
  border-radius: var(--wb-radius, 8px);
  background: var(--wb-raise, var(--wb-surface, #fff));
  box-shadow: var(--wb-shadow-md, 0 8px 24px rgb(0 0 0 / 12%));
}

.ds-option {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 6px 8px;
  border-radius: var(--wb-radius-sm, 6px);
  color: var(--wb-text, #1f2430);
  font-size: var(--wb-fs-compact, 13px);
  cursor: pointer;
}

.ds-option-main {
  display: flex;
  flex-direction: column;
  gap: 1px;
  min-width: 0;
}

.ds-option-hint {
  overflow: hidden;
  color: var(--wb-muted, #8a90a6);
  font-size: 10.5px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.ds-option.is-selected .ds-option-hint { color: inherit; opacity: 0.7; }

.ds-option-label {
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.ds-option.is-active { background: var(--wb-fill, #f0f1f3); }
.ds-option.is-selected { color: var(--wb-brand, #2563eb); font-weight: 550; }
.ds-option.is-selected svg { flex: 0 0 auto; }
.ds-option.is-disabled { opacity: 0.5; cursor: not-allowed; }

.ds-empty {
  padding: 8px;
  color: var(--wb-muted, #8a90a6);
  font-size: var(--wb-fs-caption, 12px);
  text-align: center;
}

@media (prefers-reduced-motion: reduce) {
  .ds-arrow { transition: none; }
}
</style>
