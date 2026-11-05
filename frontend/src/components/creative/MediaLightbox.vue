<template>
  <div class="lb-root" role="dialog" aria-modal="true" :aria-label="`大预览：${current?.displayName || ''}`"
       @keydown="onKeydown">
    <motion.div class="lb-backdrop" :initial="motionInitial" :animate="{ opacity: 1 }"
                :transition="{ duration: 0.14, ease: [0.22, 1, 0.36, 1] }" @click="close" />

    <div v-if="compareOpen" class="lb-compare">
      <AssetCompare :items="compareItems" @close="compareOpen = false">
        <template #actions="{ asset }">
          <button type="button" class="cr-primary" :disabled="!asset" @click="adoptCompare(asset)">
            选定这张
          </button>
        </template>
      </AssetCompare>
    </div>

    <section class="lb-stage-wrap">
      <div class="lb-stage" @click.self="toggleZoom">
        <motion.video v-if="isVideo && src" v-show="!compareOpen" :key="current.id" :src="src"
                      :initial="motionInitial" :animate="{ opacity: 1 }"
                      :transition="{ duration: 0.16, ease: [0.22, 1, 0.36, 1] }"
                      controls preload="metadata" playsinline></motion.video>
        <motion.img v-else-if="src" v-show="!compareOpen" :key="current.id" :src="src" :alt="current.displayName"
                    :initial="motionInitial" :animate="{ opacity: 1 }"
                    :transition="{ duration: 0.16, ease: [0.22, 1, 0.36, 1] }"
                    :class="{ zoomed }" @click="toggleZoom" />
        <AsterLoader v-else size="20" />

        <button v-if="hasPrev" type="button" class="lb-nav lb-prev" aria-label="上一张" @click="step(-1)">
          <ChevronLeft :size="20" />
        </button>
        <button v-if="hasNext" type="button" class="lb-nav lb-next" aria-label="下一张" @click="step(1)">
          <ChevronRight :size="20" />
        </button>
        <button type="button" class="lb-close" aria-label="关闭预览" @click="close">
          <X :size="18" />
        </button>
      </div>

      <aside class="lb-rail" aria-label="素材信息">
        <header class="lb-rail-head">
          <h2 :title="current?.displayName">{{ current?.displayName }}</h2>
          <p class="lb-origin">{{ originLabel }} · {{ formatTime(current?.createdAt) }}</p>
        </header>

        <div class="lb-actions">
          <button v-if="jobPrompt" type="button" class="cr-primary" @click="emitAction('use-prompt')">
            沿用提示词
          </button>
          <button v-if="!isVideo" type="button" class="cr-secondary" @click="emitAction('first-frame')">
            用作首帧
          </button>
          <button v-if="isVideo" type="button" class="cr-secondary" :disabled="true"
                  title="视频不能作为图片参考或首帧">作为图片参考</button>
          <button type="button" class="cr-ghost" @click="emitAction('download')">下载</button>
          <button type="button" class="cr-ghost" :aria-pressed="current?.starred" @click="$emit('star', current)">
            {{ current?.starred ? '已收藏' : '收藏' }}
          </button>
        </div>
        <p class="lb-hint">视频不能进入图片槽位；不支持的动作已禁用（redesign §5.4）。</p>

        <section v-if="versionAssets.length" class="lb-versions">
          <h3>同批版本（{{ versionAssets.length }}）</h3>
          <div class="lb-version-strip">
            <button v-for="item in versionAssets" :key="item.asset.id" type="button"
                    class="lb-version-thumb" :class="{ active: item.asset.id === current.id }"
                    :title="item.asset.displayName" @click="focusVersion(item)">
              <img v-if="item.thumb" :src="item.thumb" :alt="item.asset.displayName" />
              <span v-else>…</span>
            </button>
          </div>
          <button v-if="canCompare" type="button" class="cr-secondary lb-compare-btn" @click="openCompare">
            对比选定
          </button>
        </section>

        <section class="lb-info">
          <h3>创作信息</h3>
          <dl>
            <div><dt>类型</dt><dd>{{ isVideo ? '视频' : '图片' }}</dd></div>
            <div v-if="jobPrompt"><dt>提示词</dt><dd class="lb-prompt" :title="jobPrompt">{{ jobPrompt }}</dd></div>
            <div v-if="projectName"><dt>项目</dt><dd>{{ projectName }}</dd></div>
          </dl>
          <button type="button" class="lb-fold" :aria-expanded="fileInfoOpen" @click="fileInfoOpen = !fileInfoOpen">
            {{ fileInfoOpen ? '收起文件信息' : '文件信息' }}
            <ChevronDown :size="13" :class="{ open: fileInfoOpen }" />
          </button>
          <dl v-if="fileInfoOpen" class="lb-file-info">
            <div v-if="current?.width && current?.height"><dt>尺寸</dt><dd>{{ current.width }}×{{ current.height }}</dd></div>
            <div><dt>格式</dt><dd>{{ current?.mimeType || '—' }}</dd></div>
            <div><dt>大小</dt><dd>{{ formatBytes(current?.byteSize) || '—' }}</dd></div>
            <div v-if="current?.durationMs"><dt>时长</dt><dd>{{ Math.round(current.durationMs / 1000) }} 秒</dd></div>
          </dl>
        </section>

        <slot name="rail-extra" :asset="current" />
      </aside>
    </section>
    <p v-if="toast.text" class="cr-toast" :class="toast.tone">{{ toast.text }}</p>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import AsterLoader from '../ui/AsterLoader.vue'
import { ChevronDown, ChevronLeft, ChevronRight, X } from 'lucide-vue-next'
import { motion, useReducedMotion } from 'motion-v'
import { getCreativeAssetThumbnail, getCreativeJob, listCreativeProjects } from '../../api'
import { formatBytes, formatTime, useAssetUrls, useVideoUrls } from '../../views/creative/shared'
import AssetCompare from './AssetCompare.vue'

/**
 * 大预览（redesign §5.4）：深色媒体舞台 + 信息栏；左右切换相邻结果；
 * 图像缩放/原尺寸；视频点击才播放、不自动发声；关闭由父级恢复滚动位置。
 * 主动作随类型变化：图片=沿用提示词/用作首帧；视频=沿用提示词（图片参考禁用）。
 */
const props = defineProps({
  assets: { type: Array, required: true },
  index: { type: Number, required: true }
})
const emit = defineEmits(['update:index', 'close', 'star', 'action'])

const { urlFor } = useAssetUrls()
const { videoUrlFor } = useVideoUrls()

// 动效（redesign §7.2）：媒体交叉淡入 160ms，减弱动态效果时直接出现
const reduceMotion = useReducedMotion()
const motionInitial = computed(() => (reduceMotion.value ? false : { opacity: 0 }))

const src = ref('')
const zoomed = ref(false)
const jobPrompt = ref('')
const versionAssets = ref([]) // [{ asset, thumb }]
const compareOpen = ref(false)
const fileInfoOpen = ref(false)
const projectName = ref('')
const toast = ref({ text: '', tone: '' })

const current = computed(() => props.assets[props.index] || null)
const isVideo = computed(() => current.value?.mediaType === 'VIDEO')
const hasPrev = computed(() => props.index > 0)
const hasNext = computed(() => props.index < props.assets.length - 1)
const originLabel = computed(() => {
  const origin = current.value?.origin
  return origin === 'UPLOAD' ? '上传' : origin === 'DERIVED' ? '渲染产物' : '生成'
})

const compareItems = computed(() => versionAssets.value
  .filter(item => item.asset.mediaType !== 'VIDEO' && item.src)
  .map(item => ({ asset: item.asset, src: item.src })))
const canCompare = computed(() => compareItems.value.length >= 2)

function showToast(text, tone = '') {
  toast.value = { text, tone }
  window.clearTimeout(showToast.timer)
  showToast.timer = window.setTimeout(() => { toast.value = { text: '', tone: '' } }, 2800)
}

function step(delta) {
  const next = props.index + delta
  if (next < 0 || next >= props.assets.length) return
  emit('update:index', next)
}

function toggleZoom() {
  if (isVideo.value) return
  zoomed.value = !zoomed.value
}

function emitAction(type) {
  if (current.value) emit('action', { type, asset: current.value })
}

function close() {
  emit('close')
}

function onKeydown(event) {
  if (compareOpen.value) return
  if (event.key === 'Escape') { event.stopPropagation(); close() }
  else if (event.key === 'ArrowLeft') step(-1)
  else if (event.key === 'ArrowRight') step(1)
}

function focusVersion(item) {
  const index = props.assets.findIndex(asset => asset.id === item.asset.id)
  if (index >= 0) emit('update:index', index)
  else showToast('该版本不在当前列表中，请在同项目素材里查看')
}

function openCompare() {
  if (!canCompare.value) return
  compareOpen.value = true
}

/** 对比选定：把选定版本回填为大预览当前项（保留选片状态，关闭比较不丢）。 */
function adoptCompare(asset) {
  compareOpen.value = false
  focusVersion({ asset })
  showToast('已选定该版本', 'success')
}

async function resolveSrc() {
  src.value = ''
  zoomed.value = false
  if (!current.value) return
  src.value = isVideo.value ? await videoUrlFor(current.value.id) : await urlFor(current.value.id)
}

async function resolveJob() {
  jobPrompt.value = ''
  versionAssets.value = []
  projectName.value = ''
  const asset = current.value
  if (!asset) return
  try {
    const [projects] = await Promise.all([listCreativeProjects({ limit: 100 }).catch(() => null)])
    const project = (projects?.items || []).find(item => item.id === asset.projectId)
    projectName.value = project?.name || ''
  } catch (exception) { /* 项目名缺失可接受 */ }
  if (!asset.sourceJobId) return
  try {
    const job = await getCreativeJob(asset.sourceJobId)
    jobPrompt.value = job.prompt || ''
    const outputIds = (job.outputs || []).map(output => output.assetId).filter(Boolean)
    versionAssets.value = await Promise.all(outputIds.map(async assetId => {
      const known = props.assets.find(item => item.id === assetId) || null
      const thumb = await getCreativeAssetThumbnail(assetId)
        .then(blob => (blob && blob.size ? URL.createObjectURL(blob) : ''))
        .catch(() => '')
      return { asset: known || { id: assetId, displayName: '同批版本', mediaType: 'IMAGE' }, thumb }
    }))
  } catch (exception) { /* 原任务不可达时没有版本区，如实留空 */ }
}

watch(() => current.value?.id, () => {
  resolveSrc()
  resolveJob()
}, { immediate: true })

onMounted(() => window.addEventListener('keydown', onKeydown))
onBeforeUnmount(() => window.removeEventListener('keydown', onKeydown))
</script>

<style scoped src="../../views/creative/creative.css"></style>

<style scoped>
.lb-root { position: fixed; inset: 0; z-index: 85; }
.lb-backdrop { position: absolute; inset: 0; background: rgba(8, 8, 10, 0.72); animation: lb-fade var(--cr-dur-fast, 140ms) var(--cr-ease, ease); }
@keyframes lb-fade { from { opacity: 0; } to { opacity: 1; } }

.lb-stage-wrap {
  position: absolute;
  inset: 0;
  display: grid;
  grid-template-columns: minmax(0, 1fr) 300px;
}

.lb-stage {
  position: relative;
  display: grid;
  place-items: center;
  overflow: hidden;
  background: var(--cr-media-stage, #101113);
}
.lb-stage img { max-width: calc(100% - 96px); max-height: calc(100% - 64px); object-fit: contain; cursor: zoom-in; transition: transform var(--cr-dur-normal, 220ms) var(--cr-ease, ease); }
.lb-stage img.zoomed { transform: scale(1.8); cursor: zoom-out; }
.lb-stage video { max-width: calc(100% - 96px); max-height: calc(100% - 64px); width: auto; height: auto; background: transparent; }
.lb-loading { color: rgba(255, 255, 255, 0.6); font-size: 13px; }

.lb-nav, .lb-close {
  position: absolute;
  z-index: 2;
  display: grid;
  place-items: center;
  width: 38px;
  height: 38px;
  border: 0;
  border-radius: 12px;
  background: rgba(24, 24, 28, 0.66);
  color: #fff;
  cursor: pointer;
  transition: background var(--cr-dur-fast, 140ms) var(--cr-ease, ease);
}
.lb-nav:hover, .lb-close:hover { background: rgba(24, 24, 28, 0.9); }
.lb-prev { left: 16px; top: 50%; transform: translateY(-50%); }
.lb-next { right: 316px; top: 50%; transform: translateY(-50%); }
.lb-close { right: 316px; top: 14px; }

.lb-rail {
  overflow-y: auto;
  background: var(--cr-surface, var(--wb-surface));
  border-left: 1px solid var(--cr-border, var(--wb-border));
  padding: 18px 18px 24px;
  display: flex;
  flex-direction: column;
  gap: 14px;
}
.lb-rail-head h2 { margin: 0; font-size: 15px; color: var(--cr-text, var(--wb-text)); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.lb-origin { margin: 3px 0 0; font-size: 12px; color: var(--wb-muted); }

.lb-actions { display: flex; flex-wrap: wrap; gap: 8px; }
.lb-hint { margin: 0; font-size: 11px; color: var(--wb-muted); line-height: 1.5; }

.lb-versions h3, .lb-info h3 {
  margin: 0 0 8px;
  font-size: 12px;
  font-weight: 620;
  letter-spacing: 0.03em;
  color: var(--wb-text-2);
}
.lb-version-strip { display: flex; gap: 6px; flex-wrap: wrap; }
.lb-version-thumb {
  width: 56px;
  height: 42px;
  padding: 0;
  border: 2px solid transparent;
  border-radius: 8px;
  overflow: hidden;
  background: var(--cr-media-stage, var(--wb-media-bg));
  cursor: pointer;
}
.lb-version-thumb.active { border-color: var(--cr-accent, var(--wb-brand)); }
.lb-version-thumb img { width: 100%; height: 100%; object-fit: contain; display: block; }
.lb-version-thumb span { display: grid; place-items: center; height: 100%; color: var(--wb-muted); font-size: 11px; }
.lb-compare-btn { margin-top: 8px; }

.lb-info dl { display: flex; flex-direction: column; gap: 5px; margin: 0; font-size: 12.5px; }
.lb-info div { display: flex; gap: 8px; }
.lb-info dt { flex: 0 0 44px; color: var(--wb-muted); }
.lb-info dd { margin: 0; color: var(--wb-text-2); min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.lb-prompt { white-space: normal; display: -webkit-box; -webkit-line-clamp: 3; -webkit-box-orient: vertical; }

.lb-fold {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  margin-top: 10px;
  padding: 0;
  border: 0;
  background: transparent;
  color: var(--wb-brand);
  font-size: 12px;
  cursor: pointer;
}
.lb-fold .open { transform: rotate(180deg); }
.lb-file-info { margin-top: 8px; }

.lb-compare { position: absolute; inset: 0; z-index: 3; }

@media (max-width: 900px) {
  .lb-stage-wrap { grid-template-columns: 1fr; }
  .lb-rail { position: absolute; left: 0; right: 0; bottom: 0; top: auto; max-height: 44vh; border-top: 1px solid var(--cr-border, var(--wb-border)); border-left: 0; }
  .lb-next, .lb-close { right: 16px; }
}
</style>
