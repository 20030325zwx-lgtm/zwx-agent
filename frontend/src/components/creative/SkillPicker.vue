<template>
  <div ref="root" class="sp" :class="{ 'is-open': open }">
    <button
      type="button"
      class="sp-trigger"
      :disabled="disabled"
      :aria-expanded="open ? 'true' : 'false'"
      aria-haspopup="dialog"
      aria-label="选择技能"
      @click="toggle"
    >
      <Sparkles :size="14" :stroke-width="1.9" />
      <span class="sp-trigger-label" :class="{ 'is-placeholder': !selected.length }">{{ triggerLabel }}</span>
      <ChevronDown :size="14" :stroke-width="2" class="sp-arrow" />
    </button>

    <div v-if="open" class="sp-pop" role="dialog" aria-label="选择技能">
      <div class="sp-search">
        <Search :size="14" :stroke-width="1.9" />
        <input
          ref="searchInput"
          v-model.trim="query"
          type="text"
          placeholder="搜索技能名称"
          aria-label="搜索技能名称"
        />
        <button v-if="query" type="button" class="sp-search-clear" aria-label="清空搜索" @click="query = ''">
          <X :size="12" />
        </button>
      </div>

      <div class="sp-body">
        <nav class="sp-cats" aria-label="技能分类">
          <button
            v-for="entry in categoryEntries"
            :key="entry.value || 'all'"
            type="button"
            class="sp-cat"
            :class="{ active: category === entry.value }"
            @click="category = entry.value"
          >
            <span>{{ entry.label }}</span>
            <em>{{ entry.count }}</em>
          </button>
        </nav>

        <ul class="sp-list" role="listbox" aria-multiselectable="true" aria-label="技能列表">
          <li
            v-for="skill in filteredSkills"
            :key="skill.slug"
            role="option"
            :aria-selected="isChecked(skill) ? 'true' : 'false'"
            class="sp-item"
            :class="{ selected: isChecked(skill), disabled: skill.compat !== 'READY' }"
            @click="toggleSkill(skill)"
          >
            <span class="sp-check" :class="{ on: isChecked(skill) }">
              <Check v-if="isChecked(skill)" :size="11" :stroke-width="3" />
            </span>
            <span class="sp-copy">
              <strong>{{ skill.name }}</strong>
              <small>{{ skill.summary || skill.slug }}</small>
            </span>
            <em v-if="skill.compat !== 'READY'" class="sp-badge">不可用</em>
          </li>
          <li v-if="!filteredSkills.length" class="sp-empty">没有匹配的技能</li>
        </ul>
      </div>

      <footer class="sp-foot">
        <span>已选 {{ selected.length }} 项</span>
        <div class="sp-foot-actions">
          <button type="button" class="sp-link" :disabled="!selected.length" @click="clear">清空</button>
          <button type="button" class="sp-done" @click="open = false">完成</button>
        </div>
      </footer>
    </div>
  </div>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { Check, ChevronDown, Search, Sparkles, X } from 'lucide-vue-next'
import { skillCategoryLabel } from './skillCategories'

const props = defineProps({
  // 已选技能 slug 数组
  modelValue: { type: Array, default: () => [] },
  // 技能目录：[{ slug, name, summary, category, compat }]
  skills: { type: Array, default: () => [] },
  disabled: { type: Boolean, default: false }
})
const emit = defineEmits(['update:modelValue'])

const root = ref(null)
const searchInput = ref(null)
const open = ref(false)
const query = ref('')
const category = ref('')

const selected = computed(() => (Array.isArray(props.modelValue) ? props.modelValue.filter(Boolean) : []))

const categoryEntries = computed(() => {
  const counts = new Map()
  for (const skill of props.skills) {
    const key = skill.category || 'GENERAL'
    counts.set(key, (counts.get(key) || 0) + 1)
  }
  return [
    { value: '', label: '全部', count: props.skills.length },
    ...[...counts.entries()]
      .sort((a, b) => skillCategoryLabel(a[0]).localeCompare(skillCategoryLabel(b[0]), 'zh-Hans-CN'))
      .map(([value, count]) => ({ value, label: skillCategoryLabel(value), count }))
  ]
})

const filteredSkills = computed(() => {
  const lowered = query.value.toLowerCase()
  return props.skills.filter(skill => {
    if (category.value && (skill.category || 'GENERAL') !== category.value) return false
    if (!lowered) return true
    return `${skill.name || ''} ${skill.summary || ''} ${skill.slug || ''}`.toLowerCase().includes(lowered)
  })
})

const triggerLabel = computed(() => {
  if (!selected.value.length) return '技能'
  if (selected.value.length === 1) {
    const hit = props.skills.find(skill => skill.slug === selected.value[0])
    return hit?.name || selected.value[0]
  }
  return `${selected.value.length} 个技能`
})

const isChecked = skill => selected.value.includes(skill.slug)

// 输入搜索时回到「全部」：按名称查找通常跨分类，避免在某个分类里搜不到别的分类技能
watch(query, value => {
  if (value) category.value = ''
})

function toggleSkill(skill) {
  if (skill.compat !== 'READY') return
  const next = isChecked(skill)
    ? selected.value.filter(slug => slug !== skill.slug)
    : [...selected.value, skill.slug]
  emit('update:modelValue', next)
}

function clear() {
  emit('update:modelValue', [])
}

function toggle() {
  if (props.disabled) return
  open.value = !open.value
  if (open.value) {
    query.value = ''
    nextTick(() => searchInput.value?.focus())
  }
}

function onDocumentClick(event) {
  if (open.value && root.value && !root.value.contains(event.target)) open.value = false
}

function onKeydown(event) {
  if (event.key === 'Escape' && open.value) open.value = false
}

onMounted(() => {
  document.addEventListener('mousedown', onDocumentClick)
  document.addEventListener('keydown', onKeydown)
})
onBeforeUnmount(() => {
  document.removeEventListener('mousedown', onDocumentClick)
  document.removeEventListener('keydown', onKeydown)
})
</script>

<style scoped>
.sp {
  position: relative;
  display: inline-block;
  min-width: 0;
}

.sp-trigger {
  display: flex;
  align-items: center;
  gap: 6px;
  width: 100%;
  height: 32px;
  padding: 0 12px;
  border: 1px solid var(--cr-border, var(--wb-border));
  border-radius: 999px;
  background: transparent;
  color: var(--cr-text-2, var(--wb-text-2));
  font-family: inherit;
  font-size: 12.5px;
  text-align: left;
  cursor: pointer;
  transition: border-color 0.16s, color 0.16s, background 0.16s;
}

.sp-trigger:hover:not(:disabled),
.sp.is-open .sp-trigger {
  border-color: var(--cr-text-2, var(--wb-border-strong));
  color: var(--cr-text, var(--wb-text));
}

.sp-trigger:disabled {
  opacity: 0.55;
  cursor: not-allowed;
}

.sp-trigger:focus-visible {
  outline: 2px solid var(--wb-brand, #2563eb);
  outline-offset: 1px;
}

.sp-trigger-label {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.sp-trigger-label.is-placeholder {
  color: var(--cr-muted, var(--wb-muted));
}

.sp-arrow {
  flex-shrink: 0;
  color: var(--cr-muted, var(--wb-muted));
  transition: transform 0.18s ease;
}

.sp.is-open .sp-arrow {
  transform: rotate(180deg);
}

.sp-pop {
  position: absolute;
  z-index: 130;
  left: 0;
  bottom: calc(100% + 8px);
  display: flex;
  flex-direction: column;
  width: 440px;
  max-width: min(440px, calc(100vw - 48px));
  border: 1px solid var(--wb-border);
  border-radius: 16px;
  background: var(--wb-surface, #fff);
  box-shadow: var(--wb-shadow-lg, 0 18px 48px rgba(0, 0, 0, 0.18));
  overflow: hidden;
}

.sp-search {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 10px 10px 4px;
  padding: 0 10px;
  height: 34px;
  border: 1px solid var(--wb-border);
  border-radius: 10px;
  background: var(--wb-surface-2, var(--wb-fill));
  color: var(--wb-muted);
}

.sp-search input {
  flex: 1;
  min-width: 0;
  border: 0;
  background: transparent;
  color: var(--wb-text);
  font-family: inherit;
  font-size: 12.5px;
  outline: none;
}

.sp-search input::placeholder {
  color: var(--wb-muted);
}

.sp-search-clear {
  display: grid;
  place-items: center;
  width: 20px;
  height: 20px;
  border: 0;
  border-radius: 6px;
  background: transparent;
  color: var(--wb-muted);
  cursor: pointer;
}

.sp-search-clear:hover {
  background: var(--wb-fill-hover, var(--wb-fill));
  color: var(--wb-text);
}

.sp-body {
  display: grid;
  grid-template-columns: 132px minmax(0, 1fr);
  height: 268px;
  min-height: 0;
}

.sp-cats {
  display: flex;
  min-height: 0;
  flex-direction: column;
  gap: 2px;
  padding: 6px 6px 8px 10px;
  border-right: 1px solid var(--wb-border);
  overflow-y: auto;
}

.sp-cat {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 7px 9px;
  border: 0;
  border-radius: 9px;
  background: transparent;
  color: var(--wb-text-2);
  font-family: inherit;
  font-size: 12.5px;
  text-align: left;
  cursor: pointer;
  transition: background 0.14s, color 0.14s;
}

.sp-cat em {
  color: var(--wb-muted);
  font-size: 10.5px;
  font-style: normal;
}

.sp-cat:hover {
  background: var(--wb-fill);
  color: var(--wb-text);
}

.sp-cat.active {
  background: var(--wb-brand-soft);
  color: var(--wb-brand);
  font-weight: 600;
}

.sp-cat.active em {
  color: var(--wb-brand);
  opacity: 0.75;
}

.sp-list {
  min-height: 0;
  margin: 0;
  padding: 6px 8px 8px 6px;
  list-style: none;
  overflow-y: auto;
  overscroll-behavior: contain;
}

.sp-item {
  display: flex;
  align-items: flex-start;
  gap: 9px;
  padding: 8px 9px;
  border-radius: 10px;
  cursor: pointer;
  transition: background 0.14s;
}

.sp-item:hover {
  background: var(--wb-fill);
}

.sp-item.selected {
  background: var(--wb-brand-soft);
}

.sp-item.disabled {
  opacity: 0.45;
  cursor: not-allowed;
}

.sp-check {
  display: grid;
  place-items: center;
  width: 16px;
  height: 16px;
  flex: 0 0 16px;
  margin-top: 1px;
  border: 1.5px solid var(--wb-border-strong, #c5cbd8);
  border-radius: 5px;
  background: var(--wb-surface, #fff);
  color: #fff;
  transition: background 0.14s, border-color 0.14s;
}

.sp-check.on {
  border-color: var(--wb-brand, #2e6be6);
  background: var(--wb-brand, #2e6be6);
}

.sp-copy {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.sp-copy strong {
  color: var(--wb-text);
  font-size: 12.5px;
  font-weight: 600;
}

.sp-item.selected .sp-copy strong {
  color: var(--wb-brand);
}

.sp-copy small {
  overflow: hidden;
  color: var(--wb-muted);
  font-size: 11px;
  line-height: 1.45;
  text-overflow: ellipsis;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
}

.sp-badge {
  flex-shrink: 0;
  padding: 2px 6px;
  border-radius: 999px;
  background: var(--wb-fill);
  color: var(--wb-muted);
  font-size: 10px;
  font-style: normal;
}

.sp-empty {
  padding: 22px 10px;
  color: var(--wb-muted);
  font-size: 12px;
  text-align: center;
}

.sp-foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  padding: 9px 12px;
  border-top: 1px solid var(--wb-border);
  color: var(--wb-muted);
  font-size: 11.5px;
}

.sp-foot-actions {
  display: flex;
  align-items: center;
  gap: 6px;
}

.sp-link {
  border: 0;
  background: transparent;
  color: var(--wb-text-2);
  font-family: inherit;
  font-size: 11.5px;
  cursor: pointer;
  padding: 5px 8px;
  border-radius: 8px;
}

.sp-link:hover:not(:disabled) {
  background: var(--wb-fill);
  color: var(--wb-text);
}

.sp-link:disabled {
  opacity: 0.4;
  cursor: default;
}

.sp-done {
  padding: 5px 12px;
  border: 0;
  border-radius: 8px;
  background: var(--wb-brand, #2e6be6);
  color: #fff;
  font-family: inherit;
  font-size: 11.5px;
  font-weight: 600;
  cursor: pointer;
}

@media (max-width: 560px) {
  .sp-pop {
    width: calc(100vw - 32px);
  }

  .sp-body {
    grid-template-columns: 108px minmax(0, 1fr);
  }
}

@media (prefers-reduced-motion: reduce) {
  .sp-arrow,
  .sp-trigger,
  .sp-cat,
  .sp-item,
  .sp-check {
    transition: none;
  }
}
</style>
