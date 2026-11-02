<template>
  <div class="cr-page">
    <div class="cr-scroll">
      <div class="assets-page">
        <header class="assets-head">
          <div>
            <p class="cr-kicker">AI 创作</p>
            <h1 class="cr-title">素材库</h1>
            <p class="cr-sub">生成结果与上传文件，仅本人可见。按项目、类型和来源筛选。</p>
          </div>
          <router-link to="/creative" class="cr-secondary">返回创作</router-link>
        </header>

        <div class="assets-layout">
          <!-- 筛选侧栏：类型 / 来源 / 项目 / 收藏（UI 规范 §8.6） -->
          <aside class="assets-filters" aria-label="素材筛选">
            <section class="filter-group">
              <h2>来源</h2>
              <button
                v-for="option in originOptions"
                :key="option.value"
                type="button"
                class="filter-item"
                :class="{ active: origin === option.value }"
                @click="origin = option.value; reload()"
              >
                {{ option.label }}
                <em v-if="counts[option.value]">{{ counts[option.value] }}</em>
              </button>
            </section>

            <section class="filter-group">
              <h2>类型</h2>
              <select v-model="mediaType" class="cr-select" @change="reload">
                <option value="">全部类型</option>
                <option value="IMAGE">图片</option>
                <option value="VIDEO">视频</option>
              </select>
            </section>

            <section class="filter-group">
              <h2>项目</h2>
              <select v-model="projectId" class="cr-select" @change="reload">
                <option value="">全部项目</option>
                <option v-for="project in projects" :key="project.id" :value="project.id">{{ project.name }}</option>
              </select>
            </section>

            <label class="filter-check">
              <input v-model="starred" type="checkbox" @change="reload" />
              <span>只看收藏</span>
            </label>
          </aside>

          <section class="assets-main">
            <div class="assets-toolbar">
              <span class="assets-count">
                {{ loading ? '加载中…' : `${assets.length} 个素材` }}
                <template v-if="origin || mediaType || projectId || starred"> · 已筛选</template>
              </span>
              <button v-if="hasFilters" type="button" class="cr-ghost" @click="clearFilters">清除筛选</button>
            </div>

            <p v-if="error" class="cr-error">{{ error }}</p>
            <div v-if="!loading && !assets.length" class="cr-empty">
              {{ hasFilters ? '当前筛选下没有素材。' : '还没有素材。生成结果会自动保存到这里；也可以在项目内上传文件。' }}
              <button v-if="hasFilters" type="button" class="cr-ghost" @click="clearFilters">清除筛选</button>
            </div>

            <div v-else class="asset-grid">
              <AssetCard
                v-for="asset in assets"
                :key="asset.id"
                :asset="asset"
                :preview-url="previewUrls[asset.id] || ''"
                :preview-kind="asset.mediaType === 'VIDEO' ? 'video' : 'image'"
                @open="openPreview(asset)"
              >
                <template #actions>
                  <button type="button" class="cr-ghost" :aria-pressed="asset.starred"
                          :title="asset.starred ? '取消收藏' : '收藏'"
                          @click="toggleStar(asset, $event)">{{ asset.starred ? '已收藏' : '收藏' }}</button>
                  <button type="button" class="cr-ghost" @click="downloadAsset(asset)">下载</button>
                  <button v-if="asset.jobId" type="button" class="cr-ghost" @click="continueFrom(asset)">继续创作</button>
                  <HoldToConfirm label="删除" @confirm="remove(asset)" />
                </template>
              </AssetCard>
            </div>

            <div v-if="nextCursor" class="load-more">
              <button type="button" class="cr-secondary" :disabled="loading" @click="loadMore">
                {{ loading ? '加载中…' : '加载更多' }}
              </button>
            </div>
          </section>
        </div>
      </div>
    </div>

    <AssetPreview v-if="previewAsset" :asset="previewAsset" :src="previewSrc" @close="previewAsset = null">
      <template #actions>
        <button type="button" class="cr-ghost" :aria-pressed="previewAsset.starred"
                @click="toggleStar(previewAsset, $event); previewAsset = { ...previewAsset, starred: !previewAsset.starred }">
          {{ previewAsset.starred ? '已收藏' : '收藏' }}
        </button>
        <button v-if="previewAsset.jobId" type="button" class="cr-ghost" @click="continueFrom(previewAsset)">继续创作</button>
        <HoldToConfirm label="删除" @confirm="remove(previewAsset); previewAsset = null" />
      </template>
    </AssetPreview>
    <p v-if="toast.text" class="cr-toast" :class="toast.tone">{{ toast.text }}</p>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import {
  deleteCreativeAsset, getCreativeJob, listCreativeAssets, listCreativeProjects, updateCreativeAsset
} from '../../api'
import { downloadBlob, formatBytes, formatTime, starBurst, useAssetUrls, useVideoUrls } from './shared'
import AssetCard from '../../components/creative/AssetCard.vue'
import AssetPreview from './AssetPreview.vue'
import HoldToConfirm from '../../components/ui/HoldToConfirm.vue'

const router = useRouter()
const { urls, thumbs, urlFor, thumbFor } = useAssetUrls()
const { videoUrls, videoUrlFor } = useVideoUrls()

const assets = ref([])
const projects = ref([])
const projectId = ref('')
const mediaType = ref('')
const origin = ref('')
const starred = ref(false)
const originOptions = [
  { value: '', label: '全部来源' },
  { value: 'UPLOAD', label: '原始素材' },
  { value: 'GENERATED', label: '生成结果' }
]
const previewAsset = ref(null)
const previewSrc = ref('')
const nextCursor = ref(null)
const loading = ref(false)
const error = ref('')
const toast = ref({ text: '', tone: '' })

// 侧栏计数只反映当前已加载页，避免伪造全量统计
const counts = reactive({ '': 0, UPLOAD: 0, GENERATED: 0 })
const hasFilters = computed(() => Boolean(origin.value || mediaType.value || projectId.value || starred.value))

// 卡片预览地址集中解析：图片优先缩略图、视频用签名地址
const previewUrls = computed(() => {
  const map = {}
  assets.value.forEach(asset => {
    if (asset.mediaType === 'VIDEO') map[asset.id] = videoUrls[asset.id]?.url || ''
    else map[asset.id] = thumbs[asset.id] || urls[asset.id] || ''
  })
  return map
})

function showToast(text, tone = '') {
  toast.value = { text, tone }
  window.clearTimeout(showToast.timer)
  showToast.timer = window.setTimeout(() => { toast.value = { text: '', tone: '' } }, 3200)
}

function recount() {
  counts[''] = assets.value.length
  counts.UPLOAD = assets.value.filter(asset => asset.origin === 'UPLOAD').length
  counts.GENERATED = assets.value.filter(asset => asset.origin !== 'UPLOAD').length
}

async function load(cursor = null, append = false) {
  loading.value = true
  error.value = ''
  try {
    const data = await listCreativeAssets({
      projectId: projectId.value || undefined,
      mediaType: mediaType.value || undefined,
      origin: origin.value || undefined,
      starred: starred.value ? true : undefined,
      cursor: cursor || undefined,
      limit: 60
    })
    assets.value = append ? [...assets.value, ...(data.items || [])] : (data.items || [])
    nextCursor.value = data.nextCursor
    assets.value.forEach(asset => {
      if (asset.mediaType === 'VIDEO') videoUrlFor(asset.id)
      else thumbFor(asset.id)
    })
    recount()
  } catch (exception) {
    error.value = exception.response?.data?.error || `素材加载失败：${exception.message || exception}`
  } finally {
    loading.value = false
  }
}

function reload() { load(null, false) }
function loadMore() { if (nextCursor.value) load(nextCursor.value, true) }

function clearFilters() {
  origin.value = ''
  mediaType.value = ''
  projectId.value = ''
  starred.value = false
  reload()
}

async function toggleStar(asset, event) {
  // 事件对象的 currentTarget 在 await 之后会被置空，必须在同步阶段先捕获
  const burstTarget = event?.currentTarget
  try {
    const updated = await updateCreativeAsset(asset.id, { starred: !asset.starred })
    const index = assets.value.findIndex(item => item.id === asset.id)
    if (index >= 0) assets.value[index] = updated
    if (updated.starred) starBurst(burstTarget)
    if (starred.value) assets.value = assets.value.filter(item => item.starred)
    recount()
  } catch (exception) {
    showToast('收藏失败', 'error')
  }
}

async function openPreview(asset) {
  previewAsset.value = asset
  previewSrc.value = ''
  // 视频走签名播放 URL（支持 Range 拖动），图片仍走鉴权 blob
  previewSrc.value = asset.mediaType === 'VIDEO' ? await videoUrlFor(asset.id) : await urlFor(asset.id)
}

function downloadAsset(asset) {
  if (asset.mediaType === 'VIDEO') {
    videoUrlFor(asset.id).then(url => downloadBlob(url, `${asset.displayName || asset.id}.mp4`))
    return
  }
  urlFor(asset.id).then(url => downloadBlob(url, `${asset.displayName || asset.id}.png`))
}

async function continueFrom(asset) {
  try {
    const job = await getCreativeJob(asset.jobId)
    router.push(`/creative/projects/${job.projectId}?tab=studio`)
  } catch (exception) {
    showToast('原任务信息不可用', 'error')
  }
}

async function remove(asset) {
  try {
    await deleteCreativeAsset(asset.id)
    assets.value = assets.value.filter(item => item.id !== asset.id)
    recount()
    showToast('素材已删除', 'success')
  } catch (exception) {
    showToast(exception.response?.data?.error || '删除失败', 'error')
  }
}

onMounted(async () => {
  try {
    const data = await listCreativeProjects({ limit: 100 })
    projects.value = data.items || []
  } catch (exception) { /* 项目筛选不可用时仍可看全部素材 */ }
  await load()
})
</script>

<style scoped src="./creative.css"></style>

<style scoped>
.assets-page {
  max-width: 1440px;
  margin: 0 auto;
  padding: var(--wb-space-6) var(--wb-space-7) var(--wb-space-8);
  display: flex;
  flex-direction: column;
  gap: var(--wb-space-5);
}

.assets-head { display: flex; align-items: flex-start; justify-content: space-between; gap: var(--wb-space-4); }

.assets-layout {
  display: grid;
  grid-template-columns: 220px minmax(0, 1fr);
  gap: var(--wb-space-5);
  align-items: start;
}

.assets-filters {
  display: flex;
  flex-direction: column;
  gap: var(--wb-space-4);
  padding: var(--wb-space-3);
  border: 1px solid var(--wb-border);
  border-radius: var(--wb-radius);
  background: var(--wb-surface);
  position: sticky;
  top: 0;
}

.filter-group { display: flex; flex-direction: column; gap: 4px; }
.filter-group h2 {
  margin: 0 0 2px;
  font-size: var(--wb-fs-caption);
  font-weight: 500;
  color: var(--wb-muted);
}

.filter-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 6px 8px;
  border: 0;
  border-radius: var(--wb-radius-sm);
  background: transparent;
  color: var(--wb-text-2);
  font-size: var(--wb-fs-compact);
  text-align: left;
  cursor: pointer;
}

.filter-item:hover { background: var(--wb-fill); color: var(--wb-text); }
.filter-item.active { background: var(--wb-brand-soft); color: var(--wb-brand); font-weight: 550; }
.filter-item em { font-style: normal; font-size: var(--wb-fs-caption); color: var(--wb-muted); }

.filter-check {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: var(--wb-fs-compact);
  color: var(--wb-text-2);
  padding-top: var(--wb-space-2);
  border-top: 1px solid var(--wb-border);
}

.filter-check input { width: 14px; height: 14px; accent-color: var(--wb-brand); }

.assets-main { display: flex; flex-direction: column; gap: var(--wb-space-3); min-width: 0; }

.assets-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: var(--wb-space-3);
  min-height: 28px;
}

.assets-count { font-size: var(--wb-fs-compact); color: var(--wb-text-2); }

.asset-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(220px, 1fr));
  gap: var(--wb-space-3);
}

.load-more { display: flex; justify-content: center; padding: var(--wb-space-3) 0 var(--wb-space-6); }

@media (max-width: 900px) {
  .assets-page { padding: var(--wb-space-4); }
  .assets-layout { grid-template-columns: 1fr; }
  .assets-filters { position: static; }
}
</style>
