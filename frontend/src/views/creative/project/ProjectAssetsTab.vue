<template>
  <div class="tab-scroll">
    <div class="tab-inner">
      <div class="cr-section-head">
        <h2>项目素材</h2>
        <label class="cr-secondary upload-label">
          <Upload :size="14" /> 上传图片
          <input type="file" accept="image/png,image/jpeg,image/webp" hidden @change="upload" />
        </label>
      </div>
      <p v-if="error" class="cr-error">{{ error }}</p>
      <p v-if="!assets.length" class="cr-empty">
        还没有素材。生成结果会自动进入这里；也可以上传 PNG/JPEG/WebP。
      </p>
      <div class="origin-filter" role="radiogroup" aria-label="素材来源">
        <button v-for="option in originOptions" :key="option.value" type="button"
                class="origin-chip" :class="{ active: origin === option.value }"
                @click="origin = option.value">{{ option.label }}</button>
      </div>
      <p v-if="assets.length && !filtered.length" class="cr-empty">这个分类下还没有素材。</p>
      <div v-else-if="filtered.length" class="asset-grid">
        <AssetCard
          v-for="asset in filtered"
          :key="asset.id"
          :asset="asset"
          :preview-url="previewUrl(asset)"
          :preview-kind="asset.mediaType === 'VIDEO' ? 'video' : 'image'"
          @open="openPreview(asset)"
        >
          <template #overlay>
            <span v-if="compareIds.includes(asset.id)" class="compare-flag">已选对比</span>
          </template>
          <template #actions>
            <button type="button" class="cr-ghost" :aria-pressed="asset.starred"
                    @click="toggleStar(asset, $event)">{{ asset.starred ? '已收藏' : '收藏' }}</button>
            <button type="button" class="cr-ghost" @click="downloadAsset(asset)">下载</button>
            <button type="button" class="cr-ghost" :class="{ active: compareIds.includes(asset.id) }"
                    @click="toggleCompare(asset)">{{ compareIds.includes(asset.id) ? '已选对比' : '对比' }}</button>
            <button v-if="asset.jobId" type="button" class="cr-ghost" @click="continueFromAsset(asset)">继续创作</button>
            <HoldToConfirm label="删除" @confirm="removeAsset(asset)" />
          </template>
        </AssetCard>
      </div>

      <!-- 对比选定浮条（创作闭环：候选对比 → 人工选定 → 合成） -->
      <div v-if="compareIds.length" class="compare-bar">
        <span class="compare-count">已选 {{ compareIds.length }}/2 张</span>
        <button type="button" class="cr-primary" :disabled="compareIds.length < 2" @click="openCompare">
          对比选定
        </button>
        <button type="button" class="cr-ghost" @click="compareIds = []">清空</button>
      </div>
    </div>

    <AssetCompare v-if="compareOpen" :items="compareItems" @close="compareOpen = false">
      <template #actions="{ asset }">
        <button type="button" class="cr-primary" :disabled="!asset" @click="openPosterForm(asset)">
          用此图合成海报
        </button>
      </template>
    </AssetCompare>

    <!-- 用选定图合成海报 -->
    <div v-if="posterForm.open" class="cr-dialog-backdrop" @click.self="posterForm.open = false">
      <div class="cr-dialog" role="dialog" aria-modal="true" aria-labelledby="poster-form-title">
        <h2 id="poster-form-title">用选定图合成海报 · {{ posterForm.asset?.displayName }}</h2>
        <p class="cr-hint">视觉理解提炼卖点 → 生成文案 → 确定性合成海报（零生图计费），产物归入本项目素材库。</p>
        <label class="cr-field">
          <span class="cr-field-label"><span>卖点方向 / 关键词</span></span>
          <input v-model.trim="posterForm.keyword" class="cr-input" maxlength="200"
                 placeholder="如：加绒保暖 不起球（可留空，由商品理解自动提炼）" />
        </label>
        <p v-if="posterForm.error" class="cr-error">{{ posterForm.error }}</p>
        <div class="cr-dialog-actions">
          <button type="button" class="cr-secondary" @click="posterForm.open = false">取消</button>
          <button type="button" class="cr-primary" :disabled="posterForm.starting" @click="startPoster">
            <Spinner v-if="posterForm.starting" /> {{ posterForm.starting ? '发起中…' : '发起合成' }}
          </button>
        </div>
      </div>
    </div>

    <AssetPreview v-if="previewAsset" :asset="previewAsset" :src="previewSrc" @close="previewAsset = null">
      <template #actions>
        <button type="button" class="cr-ghost" :aria-pressed="previewAsset.starred"
                @click="toggleStar(previewAsset, $event); previewAsset = { ...previewAsset, starred: !previewAsset.starred }">
          {{ previewAsset.starred ? '已收藏' : '收藏' }}
        </button>
        <button v-if="previewAsset.jobId" type="button" class="cr-ghost" @click="continueFromAsset(previewAsset)">继续创作</button>
        <HoldToConfirm label="删除" @confirm="removeAsset(previewAsset); previewAsset = null" />
      </template>
    </AssetPreview>
  </div>
</template>

<script setup>
import { computed, inject, onMounted, reactive, ref } from 'vue'
import { Upload } from 'lucide-vue-next'
import {
  deleteCreativeAsset, getCreativeJob, listCreativeAssets, startWorkflow, updateCreativeAsset,
  uploadCreativeAsset
} from '../../../api'
import { downloadBlob, formatBytes, formatTime, starBurst, useAssetUrls, useVideoUrls } from '../shared'
import { PROJECT_CTX, seedStudio } from './composables'
import AssetCard from '../../../components/creative/AssetCard.vue'
import AssetCompare from '../../../components/creative/AssetCompare.vue'
import AssetPreview from '../AssetPreview.vue'
import HoldToConfirm from '../../../components/ui/HoldToConfirm.vue'
import Spinner from '../../../components/ui/Spinner.vue'

const { projectId, showToast, router, urls } = inject(PROJECT_CTX)
const { thumbs, urlFor, thumbFor } = useAssetUrls()
const { videoUrls, videoUrlFor } = useVideoUrls()

const assets = ref([])
const error = ref('')
const origin = ref('')
const originOptions = [
  { value: '', label: '全部' },
  { value: 'UPLOAD', label: '原始素材' },
  { value: 'GENERATED', label: '生成结果' }
]
const filtered = computed(() => assets.value.filter(asset => !origin.value || asset.origin === origin.value))

const previewAsset = ref(null)
const previewSrc = ref('')
const compareIds = ref([])
const compareOpen = ref(false)
const compareItems = ref([])
const posterForm = reactive({ open: false, starting: false, error: '', keyword: '', asset: null })

async function load() {
  try {
    const data = await listCreativeAssets({ projectId: projectId.value, limit: 60 })
    assets.value = data.items || []
    assets.value.forEach(asset => {
      if (asset.mediaType === 'VIDEO') videoUrlFor(asset.id)
      else thumbFor(asset.id)
    })
  } catch (exception) {
    error.value = '素材加载失败'
  }
}

async function upload(event) {
  const file = event.target.files?.[0]
  event.target.value = ''
  if (!file) return
  error.value = ''
  try {
    const asset = await uploadCreativeAsset(projectId.value, file)
    assets.value = [asset, ...assets.value]
    urlFor(asset.id)
    showToast('素材已上传', 'success')
  } catch (exception) {
    error.value = exception.response?.data?.error || '上传失败'
  }
}

async function toggleStar(asset, event) {
  // 事件对象的 currentTarget 在 await 之后会被置空，必须在同步阶段先捕获
  const burstTarget = event?.currentTarget
  try {
    const updated = await updateCreativeAsset(asset.id, { starred: !asset.starred })
    const index = assets.value.findIndex(item => item.id === asset.id)
    if (index >= 0) assets.value[index] = updated
    if (updated.starred) starBurst(burstTarget)
  } catch (exception) {
    showToast('收藏失败', 'error')
  }
}

function downloadAsset(asset) {
  urlFor(asset.id).then(url => downloadBlob(url, `${asset.displayName || asset.id}.png`))
}

async function removeAsset(asset) {
  try {
    await deleteCreativeAsset(asset.id)
    assets.value = assets.value.filter(item => item.id !== asset.id)
    showToast('素材已删除', 'success')
  } catch (exception) {
    showToast(exception.response?.data?.error || '删除失败', 'error')
  }
}

function previewUrl(asset) {
  return asset.mediaType === 'VIDEO'
    ? (videoUrls[asset.id]?.url || '')
    : (thumbs[asset.id] || urls[asset.id] || '')
}

async function openPreview(asset) {
  previewAsset.value = asset
  previewSrc.value = asset.mediaType === 'VIDEO' ? await videoUrlFor(asset.id) : await urlFor(asset.id)
}

/** 素材续用回到快速生成：经路由参数交接，尺寸/水印不在草稿里必须显式带过去。 */
async function continueFromAsset(asset) {
  if (!asset.jobId) return
  try {
    const job = await getCreativeJob(asset.jobId)
    seedStudio(job)
    router.push(`/creative/projects/${projectId.value}/studio`)
  } catch (exception) {
    showToast('原任务信息不可用', 'error')
  }
}

function toggleCompare(asset) {
  const index = compareIds.value.indexOf(asset.id)
  if (index >= 0) {
    compareIds.value.splice(index, 1)
    return
  }
  if (compareIds.value.length >= 2) {
    showToast('最多选择 2 张对比', 'error')
    return
  }
  compareIds.value.push(asset.id)
}

async function openCompare() {
  if (compareIds.value.length < 2) return
  const items = []
  for (const id of compareIds.value) {
    const asset = assets.value.find(item => item.id === id)
    if (!asset) continue
    items.push({ asset, src: await urlFor(asset.id) })
  }
  if (items.length < 2) {
    showToast('所选素材已变化，请重新选择', 'error')
    compareIds.value = []
    return
  }
  compareItems.value = items
  compareOpen.value = true
}

function openPosterForm(asset) {
  if (!asset) return
  posterForm.asset = asset
  posterForm.keyword = ''
  posterForm.error = ''
  posterForm.open = true
}

async function startPoster() {
  if (posterForm.starting || !posterForm.asset) return
  posterForm.starting = true
  posterForm.error = ''
  try {
    const input = { imageAssetId: posterForm.asset.id }
    if (posterForm.keyword) input.keyword = posterForm.keyword
    const run = await startWorkflow('selected-poster', posterForm.keyword, projectId.value, input)
    posterForm.open = false
    compareOpen.value = false
    compareIds.value = []
    showToast('海报合成已发起，产物将归入本项目', 'success')
    router.push(`/creative/projects/${projectId.value}/workflow?runId=${run.id}`)
  } catch (exception) {
    posterForm.error = exception.response?.data?.error || '发起失败，请稍后重试'
  } finally {
    posterForm.starting = false
  }
}

onMounted(load)
</script>

<style scoped src="../creative.css"></style>

<style scoped>
.upload-label { cursor: pointer; }
.origin-filter { display: flex; align-items: center; gap: 6px; }
.origin-chip {
  padding: 4px 12px;
  border-radius: 999px;
  border: 1px solid var(--wb-border);
  background: var(--wb-surface);
  color: var(--wb-muted);
  font-size: var(--wb-fs-caption);
  cursor: pointer;
}
.origin-chip.active {
  color: var(--wb-brand);
  border-color: color-mix(in srgb, var(--wb-brand) 40%, transparent);
  background: var(--wb-brand-soft);
}
.asset-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(220px, 1fr));
  gap: var(--wb-space-3);
}

.compare-flag {
  position: absolute;
  top: 6px;
  right: 6px;
  padding: 0 7px;
  border-radius: 999px;
  font-size: var(--wb-fs-caption);
  line-height: 18px;
  background: var(--wb-brand);
  color: var(--wb-brand-contrast);
}

.compare-bar {
  position: sticky;
  bottom: 10px;
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 14px;
  border: 1px solid var(--wb-border);
  border-radius: var(--wb-radius);
  background: var(--wb-raise);
  box-shadow: var(--wb-shadow-md);
}
.compare-count { color: var(--wb-text-2); font-size: var(--wb-fs-compact); }
</style>
