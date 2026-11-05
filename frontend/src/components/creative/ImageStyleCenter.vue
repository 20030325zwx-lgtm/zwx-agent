<template>
  <div class="style-center">
    <button ref="triggerRef" type="button" class="style-trigger" :class="{ open }" :aria-expanded="open"
            aria-haspopup="dialog" @click="toggle">
      <span class="style-swatch" :style="swatchStyle(current)" />
      <span class="style-trigger-label">{{ current ? current.label : '选择风格' }}</span>
      <span v-if="current" class="style-trigger-meta">{{ current.category }}</span>
      <ChevronDown :size="12" class="style-chevron" />
    </button>

    <Teleport to="body">
      <div v-if="open" class="sc-backdrop" @click="close" />
      <div v-if="open" class="sc-panel" :style="panelStyle" role="dialog" aria-modal="false"
           aria-label="风格中心" @keydown.esc="close">
        <header class="sc-head">
          <div class="sc-title"><strong>风格中心</strong><small>系统风格 · 收藏 · 最近使用</small></div>
          <button class="sc-icon" type="button" aria-label="关闭" @click="close"><X :size="14" /></button>
        </header>

        <label class="sc-search">
          <Search :size="13" />
          <input v-model="query" type="search" placeholder="搜索风格、题材或媒介" />
          <button v-if="query" type="button" class="sc-clear" aria-label="清空搜索" @click="query = ''"><X :size="11" /></button>
        </label>

        <nav class="sc-tabs" aria-label="风格分类">
          <button v-for="tab in tabs" :key="tab.key" type="button" :class="{ on: tab.key === activeTab }"
                  @click="activeTab = tab.key">
            {{ tab.label }}<em v-if="tab.count !== null">{{ tab.count }}</em>
          </button>
        </nav>

        <div class="sc-body">
          <p v-if="!shown.length" class="sc-empty">
            {{ query ? '没有匹配的风格，换个关键词试试' : '这里还没有内容' }}
          </p>
          <div v-else class="sc-grid">
            <article v-for="style in shown" :key="style.code" class="sc-card"
                     :class="{ active: style.code === modelValue, locked: isLocked(style) }">
              <div class="sc-cover" :style="swatchStyle(style)">
                <img v-if="hasCover(style)" class="sc-cover-img" :src="style.cover" alt="" loading="lazy"
                     @error="markCoverBroken(style.code)" />
                <span class="sc-cover-cat">{{ style.category }}</span>
                <button type="button" class="sc-star" :class="{ on: favorites.includes(style.code) }"
                        :aria-label="favorites.includes(style.code) ? '取消收藏' : '收藏风格'"
                        @click.stop="toggleFavorite(style.code)">
                  <Star :size="12" :fill="favorites.includes(style.code) ? 'currentColor' : 'none'" />
                </button>
              </div>
              <div class="sc-meta">
                <div class="sc-name"><strong>{{ style.label }}</strong><span v-if="style.code === modelValue">当前</span></div>
                <p class="sc-desc">{{ style.hint }}</p>
                <div class="sc-tags"><span v-for="tag in style.tags" :key="tag">{{ tag }}</span></div>
              </div>
              <button type="button" class="sc-apply" :disabled="isLocked(style)" @click="apply(style)">
                {{ style.code === modelValue ? '已应用' : '应用' }}
              </button>
            </article>
          </div>
        </div>

        <footer class="sc-foot">
          <span>{{ total }} 种系统风格</span>
          <span v-if="hiddenReferenceOnly" class="sc-foot-note">接入参考图后可用「跟随原片」</span>
          <span v-else>风格决定镜头、光线与质感，不改变你描述的内容</span>
        </footer>
      </div>
    </Teleport>
  </div>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { ChevronDown, Search, Star, X } from 'lucide-vue-next'

/**
 * 风格中心（2026-11-05）：把后端风格目录做成分组可搜索的面板，
 * 参照成熟图片/视频平台的「题材 × 媒介」预设做法：卡片带色带、标签、专业描述。
 * 收藏与最近使用只存本地（localStorage），不上服务端，换设备不跟随。
 */
const props = defineProps({
  modelValue: { type: String, default: '' },
  styles: { type: Array, default: () => [] },
  hasReference: { type: Boolean, default: false }
})
const emit = defineEmits(['update:modelValue'])

const FAVORITES_KEY = 'aster.style.favorites'
const RECENT_KEY = 'aster.style.recent'
const MAX_RECENT = 8

const open = ref(false)
const query = ref('')
const activeTab = ref('all')
const triggerRef = ref(null)
const panelRef = ref(null)
const panelStyle = ref({})
const favorites = ref(readCodes(FAVORITES_KEY))
const recent = ref(readCodes(RECENT_KEY))
// 预览图加载失败（离线/资源缺失）时回退到色带，不出现破图
const brokenCovers = ref(new Set())

const current = computed(() => props.styles.find(style => style.code === props.modelValue) || null)
const total = computed(() => props.styles.length)
// 依赖参考图的风格（跟随原片）在没接入参考图时直接不出现在列表里：
// 放一张灰掉的卡片会被误读成「选不了风格」，而不是「这条需要参考图」。
const hiddenReferenceOnly = computed(() => props.styles.some(style => style.needsReference && !props.hasReference))
const available = computed(() => props.styles.filter(style => !(style.needsReference && !props.hasReference)))

const tabs = computed(() => ([
  { key: 'all', label: '全部', count: available.value.length },
  { key: 'photo', label: '摄影', count: countGroup('photo') },
  { key: 'cg', label: '3D', count: countGroup('cg') },
  { key: 'illustration', label: '插画', count: countGroup('illustration') },
  { key: 'favorite', label: '收藏', count: favorites.value.filter(code => byCode(code)).length },
  { key: 'recent', label: '最近', count: null }
]))

const shown = computed(() => {
  const keyword = query.value.trim().toLowerCase()
  let list = available.value
  if (activeTab.value === 'favorite') list = list.filter(style => favorites.value.includes(style.code))
  else if (activeTab.value === 'recent') list = recent.value.map(byCode).filter(Boolean)
  else if (activeTab.value !== 'all') list = list.filter(style => style.group === activeTab.value)
  if (!keyword) return list
  return list.filter(style => [style.label, style.category, style.hint, ...(style.tags || [])]
    .filter(Boolean).join(' ').toLowerCase().includes(keyword))
})

function countGroup(group) {
  return available.value.filter(style => style.group === group).length
}
function byCode(code) {
  return available.value.find(style => style.code === code) || null
}
function readCodes(key) {
  try {
    const value = JSON.parse(localStorage.getItem(key) || '[]')
    return Array.isArray(value) ? value.filter(item => typeof item === 'string') : []
  } catch { return [] }
}
function persist(key, value) {
  try { localStorage.setItem(key, JSON.stringify(value)) } catch { /* 无痕模式忽略 */ }
}
function isLocked(style) {
  return !!style.needsReference && !props.hasReference
}
function swatchStyle(style) {
  const accent = style && Array.isArray(style.accent) ? style.accent : []
  if (accent.length < 2) return { background: 'var(--wb-bg, #f2f3f8)' }
  return { background: `linear-gradient(115deg, ${accent[0]}, ${accent[1]})` }
}
function hasCover(style) {
  return !!style.cover && !brokenCovers.value.has(style.code)
}
function markCoverBroken(code) {
  brokenCovers.value = new Set([...brokenCovers.value, code])
}
function toggleFavorite(code) {
  favorites.value = favorites.value.includes(code)
    ? favorites.value.filter(item => item !== code)
    : [...favorites.value, code]
  persist(FAVORITES_KEY, favorites.value)
}
function apply(style) {
  if (isLocked(style)) return
  emit('update:modelValue', style.code)
  recent.value = [style.code, ...recent.value.filter(item => item !== style.code)].slice(0, MAX_RECENT)
  persist(RECENT_KEY, recent.value)
  close()
}
function place() {
  const trigger = triggerRef.value
  if (!trigger) return
  const rect = trigger.getBoundingClientRect()
  const width = Math.min(480, window.innerWidth - 24)
  let left = rect.right + 10
  if (left + width > window.innerWidth - 12) left = Math.max(12, rect.left - width - 10)
  const height = Math.min(560, window.innerHeight - 24)
  const top = Math.max(12, Math.min(rect.top - 12, window.innerHeight - height - 12))
  panelStyle.value = { left: `${left}px`, top: `${top}px`, width: `${width}px`, maxHeight: `${height}px` }
}
function toggle() {
  open.value = !open.value
  if (!open.value) return
  activeTab.value = 'all'
  nextTick(() => {
    place()
    panelRef.value?.querySelector('input[type="search"]')?.focus()
  })
}
function close() { open.value = false }
function onKeydown(event) {
  if (event.key === 'Escape') close()
}
// 面板是固定定位，锚点跟随滚动重新定位；不能在滚动时关闭面板
// （滚轮滚到列表上下边缘会连锁滚动外层，曾导致面板直接消失）。
let repositionFrame = 0
function onWindowScroll() {
  if (repositionFrame) return
  repositionFrame = requestAnimationFrame(() => {
    repositionFrame = 0
    place()
  })
}
watch(open, value => {
  if (value) {
    window.addEventListener('scroll', onWindowScroll, true)
    window.addEventListener('resize', place)
    window.addEventListener('keydown', onKeydown)
  } else {
    window.removeEventListener('scroll', onWindowScroll, true)
    window.removeEventListener('resize', place)
    window.removeEventListener('keydown', onKeydown)
    if (repositionFrame) {
      cancelAnimationFrame(repositionFrame)
      repositionFrame = 0
    }
  }
})
onBeforeUnmount(() => {
  window.removeEventListener('scroll', onWindowScroll, true)
  window.removeEventListener('resize', place)
  window.removeEventListener('keydown', onKeydown)
})
</script>

<style scoped>
.style-center { position: relative; }
.style-trigger { height: 26px; max-width: 100%; display: inline-flex; align-items: center; gap: 6px; padding: 0 7px 0 5px; border: 1px solid var(--wb-border, #e3e6ef); border-radius: 6px; background: var(--wb-bg, #f7f8fc); color: var(--wb-text, #1f2430); font-size: 11px; cursor: pointer; }
.style-trigger:hover,.style-trigger.open { border-color: #6466d5; }
.style-swatch { width: 14px; height: 14px; border-radius: 4px; flex: none; box-shadow: inset 0 0 0 1px rgb(0 0 0 / 8%); }
.style-trigger-label { white-space: nowrap; }
.style-trigger-meta { color: var(--wb-muted, #81899a); font-size: 10px; white-space: nowrap; }
.style-chevron { color: var(--wb-muted, #81899a); flex: none; }

.sc-backdrop { position: fixed; inset: 0; z-index: 90; background: transparent; }
.sc-panel { position: fixed; z-index: 91; display: flex; flex-direction: column; gap: 10px; padding: 14px; box-sizing: border-box; border: 1px solid var(--wb-border, #e3e6ef); border-radius: 14px; background: var(--wb-surface, #fff); box-shadow: 0 18px 48px rgb(20 24 36 / 18%); overflow: hidden; }
.sc-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 10px; }
.sc-title { display: grid; gap: 2px; }
.sc-title strong { font-size: 13px; font-weight: 650; color: var(--wb-text, #1f2430); }
.sc-title small { color: var(--wb-muted, #81899a); font-size: 10px; }
.sc-icon { width: 24px; height: 24px; display: inline-flex; align-items: center; justify-content: center; border: 0; border-radius: 6px; background: transparent; color: var(--wb-muted, #81899a); cursor: pointer; }
.sc-icon:hover { background: var(--wb-bg, #f2f3f8); color: var(--wb-text, #1f2430); }
.sc-search { display: flex; align-items: center; gap: 7px; padding: 7px 9px; border: 1px solid var(--wb-border, #e3e6ef); border-radius: 8px; background: var(--wb-bg, #f7f8fc); color: var(--wb-muted, #81899a); }
.sc-search input { flex: 1; min-width: 0; border: 0; background: transparent; color: var(--wb-text, #1f2430); font: inherit; font-size: 11px; outline: none; }
.sc-clear { width: 16px; height: 16px; display: inline-flex; align-items: center; justify-content: center; border: 0; border-radius: 50%; background: var(--wb-border, #e3e6ef); color: var(--wb-muted, #81899a); cursor: pointer; }
.sc-tabs { display: flex; flex-wrap: wrap; gap: 4px; }
.sc-tabs button { display: inline-flex; align-items: center; gap: 4px; padding: 5px 9px; border: 0; border-radius: 999px; background: transparent; color: var(--wb-muted, #81899a); font-size: 11px; cursor: pointer; }
.sc-tabs button:hover { background: var(--wb-bg, #f2f3f8); }
.sc-tabs button.on { background: #6466d5; color: #fff; }
.sc-tabs em { font-style: normal; font-size: 9px; opacity: .75; }
.sc-body { flex: 1; min-height: 0; overflow: auto; overscroll-behavior: contain; margin: 0 -4px; padding: 0 4px; }
.sc-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 8px; }
.sc-card { position: relative; display: grid; grid-template-rows: 88px 1fr auto; border: 1px solid var(--wb-border, #e3e6ef); border-radius: 10px; background: var(--wb-surface, #fff); overflow: hidden; }
.sc-card:hover { border-color: #b9b8ea; box-shadow: 0 4px 14px rgb(27 37 64 / 8%); }
.sc-card.active { border-color: #6466d5; box-shadow: 0 0 0 2px rgb(100 102 213 / 16%); }
.sc-card.locked { opacity: .62; }
.sc-cover { position: relative; display: flex; align-items: flex-end; padding: 7px 8px; overflow: hidden; }
.sc-cover-img { position: absolute; inset: 0; width: 100%; height: 100%; object-fit: cover; }
.sc-cover::after { content: ''; position: absolute; inset: 0; background: radial-gradient(120% 90% at 15% 10%, rgb(255 255 255 / 28%), transparent 60%), linear-gradient(0deg, rgb(0 0 0 / 22%), transparent 62%); }
.sc-cover-cat { position: relative; z-index: 1; padding: 2px 6px; border-radius: 999px; background: rgb(255 255 255 / 82%); color: #33353f; font-size: 9px; font-weight: 600; }
.sc-star { position: absolute; top: 6px; right: 6px; z-index: 2; width: 20px; height: 20px; display: inline-flex; align-items: center; justify-content: center; border: 0; border-radius: 50%; background: rgb(255 255 255 / 74%); color: #575a66; cursor: pointer; }
.sc-star.on { color: #d99b1f; }
.sc-meta { display: grid; gap: 3px; padding: 8px 9px 5px; }
.sc-name { display: flex; align-items: center; justify-content: space-between; gap: 6px; }
.sc-name strong { font-size: 11px; font-weight: 650; color: var(--wb-text, #1f2430); }
.sc-name span { flex: none; padding: 1px 5px; border-radius: 999px; background: rgb(100 102 213 / 12%); color: #6466d5; font-size: 9px; }
.sc-desc { margin: 0; color: var(--wb-muted, #81899a); font-size: 10px; line-height: 1.5; display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; }
.sc-tags { display: flex; flex-wrap: wrap; gap: 3px; margin-top: 2px; }
.sc-tags span { padding: 1px 5px; border-radius: 4px; background: var(--wb-bg, #f2f3f8); color: var(--wb-text-2, #5c6478); font-size: 9px; }
.sc-lock { margin: 2px 0 0; color: #b4693a; font-size: 9px; }
.sc-apply { margin: 0 9px 8px; height: 24px; border: 1px solid var(--wb-border, #e3e6ef); border-radius: 6px; background: var(--wb-bg, #f7f8fc); color: var(--wb-text, #1f2430); font-size: 10px; cursor: pointer; }
.sc-card.active .sc-apply { border-color: #6466d5; background: #6466d5; color: #fff; }
.sc-apply:disabled { cursor: not-allowed; }
.sc-empty { margin: 18px 0; text-align: center; color: var(--wb-muted, #81899a); font-size: 11px; }
.sc-foot { display: flex; justify-content: space-between; gap: 10px; padding-top: 8px; border-top: 1px solid var(--wb-border, #e3e6ef); color: var(--wb-muted, #81899a); font-size: 9px; }
.sc-foot-note { color: #b4693a; }
@media (max-width: 640px) {
  .sc-panel { left: 12px !important; width: calc(100vw - 24px) !important; }
  .sc-grid { grid-template-columns: 1fr; }
}
@media (prefers-reduced-motion: reduce) { * { transition: none !important; } }
</style>
