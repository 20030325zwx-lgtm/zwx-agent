<template>
  <div class="cr-page">
    <div class="cr-scroll">
      <div class="cr-inner">
        <header class="cr-head">
          <div>
            <p class="cr-kicker">AI 创作</p>
            <h1 class="cr-title">素材库</h1>
            <p class="cr-sub">跨项目的个人素材：生成结果与上传文件（当前为图片）。仅本人可见。</p>
          </div>
          <router-link to="/creative" class="cr-secondary">返回创作</router-link>
        </header>

        <div class="asset-filters">
          <label class="filter-field">
            <span>项目</span>
            <select v-model="projectId" class="cr-select" @change="reload">
              <option value="">全部项目</option>
              <option v-for="project in projects" :key="project.id" :value="project.id">{{ project.name }}</option>
            </select>
          </label>
          <label class="filter-field">
            <span>类型</span>
            <select v-model="mediaType" class="cr-select" @change="reload">
              <option value="">全部</option>
              <option value="IMAGE">图片</option>
            </select>
          </label>
          <label class="star-filter">
            <input v-model="starred" type="checkbox" @change="reload" />
            <span>只看收藏</span>
          </label>
          <div class="origin-filter" role="radiogroup" aria-label="素材来源">
            <button v-for="option in originOptions" :key="option.value" type="button"
                    class="origin-chip" :class="{ active: origin === option.value }"
                    @click="origin = option.value; reload()">{{ option.label }}</button>
          </div>
        </div>

        <p v-if="error" class="cr-error">{{ error }}</p>
        <p v-if="!loading && !assets.length" class="cr-empty">
          还没有素材。生成结果会自动保存到这里；也可以在项目内上传文件。
        </p>

        <div v-else class="asset-grid">
          <figure v-for="asset in assets" :key="asset.id" class="asset-card">
            <button type="button" class="asset-thumb" :aria-label="`预览 ${asset.displayName}`"
                    @click="openPreview(asset)">
              <img v-if="thumbs[asset.id] || urls[asset.id]" :src="thumbs[asset.id] || urls[asset.id]" :alt="asset.displayName" />
              <span v-else class="asset-placeholder">加载中…</span>
            </button>
            <figcaption>
              <span class="asset-name">
                {{ asset.displayName }}
                <em class="asset-origin" :class="asset.origin === 'GENERATED' ? 'gen' : 'up'">
                  {{ asset.origin === 'GENERATED' ? '生成' : '上传' }}
                </em>
              </span>
              <span class="asset-meta">
                {{ formatBytes(asset.byteSize) }}
                <template v-if="asset.width && asset.height"> · {{ asset.width }}×{{ asset.height }}</template>
                · {{ formatTime(asset.createdAt) }}
              </span>
              <span class="asset-actions">
                <button type="button" class="cr-ghost" :aria-pressed="asset.starred" @click="toggleStar(asset)">
                  {{ asset.starred ? '已收藏' : '收藏' }}
                </button>
                <button type="button" class="cr-ghost" @click="downloadAsset(asset)">下载</button>
                <button v-if="asset.jobId" type="button" class="cr-ghost" @click="continueFrom(asset)">继续创作</button>
                <button type="button" class="cr-ghost danger" @click="remove(asset)">删除</button>
              </span>
            </figcaption>
          </figure>
        </div>

        <div v-if="nextCursor" class="load-more">
          <button type="button" class="cr-secondary" :disabled="loading" @click="loadMore">加载更多</button>
        </div>
      </div>
    </div>
    <AssetPreview v-if="previewAsset" :asset="previewAsset" :src="previewSrc" @close="previewAsset = null">
      <template #actions>
        <button type="button" class="cr-ghost" :aria-pressed="previewAsset.starred" @click="toggleStar(previewAsset); previewAsset = { ...previewAsset, starred: !previewAsset.starred }">
          {{ previewAsset.starred ? '已收藏' : '收藏' }}
        </button>
        <button v-if="previewAsset.jobId" type="button" class="cr-ghost" @click="continueFrom(previewAsset)">继续创作</button>
        <button type="button" class="cr-ghost danger" @click="remove(previewAsset); previewAsset = null">删除</button>
      </template>
    </AssetPreview>
    <p v-if="toast.text" class="cr-toast" :class="toast.tone">{{ toast.text }}</p>
  </div>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import {
  deleteCreativeAsset, getCreativeJob, listCreativeAssets, listCreativeProjects, updateCreativeAsset
} from '../../api'
import { useAssetUrls, downloadBlob, formatBytes, formatTime } from './shared'
import AssetPreview from './AssetPreview.vue'

const router = useRouter()
const { urls, thumbs, urlFor, thumbFor } = useAssetUrls()

const assets = ref([])
const projects = ref([])
const projectId = ref('')
const mediaType = ref('')
const origin = ref('')
const starred = ref(false)
const originOptions = [
  { value: '', label: '全部' },
  { value: 'UPLOAD', label: '原始素材' },
  { value: 'GENERATED', label: '生成结果' }
]
const previewAsset = ref(null)
const previewSrc = ref('')
const nextCursor = ref(null)
const loading = ref(false)
const error = ref('')
const toast = ref({ text: '', tone: '' })

function showToast(text, tone = '') {
  toast.value = { text, tone }
  window.clearTimeout(showToast.timer)
  showToast.timer = window.setTimeout(() => { toast.value = { text: '', tone: '' } }, 3200)
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
    assets.value.forEach(asset => thumbFor(asset.id))
  } catch (exception) {
    error.value = exception.response?.data?.error || `素材加载失败：${exception.message || exception}`
  } finally {
    loading.value = false
  }
}

function reload() { load(null, false) }
function loadMore() { if (nextCursor.value) load(nextCursor.value, true) }

async function toggleStar(asset) {
  try {
    const updated = await updateCreativeAsset(asset.id, { starred: !asset.starred })
    const index = assets.value.findIndex(item => item.id === asset.id)
    if (index >= 0) assets.value[index] = updated
    if (starred.value) assets.value = assets.value.filter(item => item.starred)
  } catch (exception) {
    showToast('收藏失败', 'error')
  }
}

async function openPreview(asset) {
  previewAsset.value = asset
  previewSrc.value = ''
  previewSrc.value = await urlFor(asset.id)
}

function downloadAsset(asset) {
  urlFor(asset.id).then(url => downloadBlob(url, `${asset.displayName || asset.id}.png`))
}

async function continueFrom(asset) {
  try {
    const job = await getCreativeJob(asset.jobId)
    router.push(`/creative/projects/${job.projectId}`)
  } catch (exception) {
    showToast('原任务信息不可用', 'error')
  }
}

async function remove(asset) {
  if (!window.confirm('删除后不可恢复（历史任务记录保留），确定删除这个素材吗？')) return
  try {
    await deleteCreativeAsset(asset.id)
    assets.value = assets.value.filter(item => item.id !== asset.id)
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
.asset-filters { display: flex; align-items: flex-end; gap: 12px; flex-wrap: wrap; }
.filter-field { display: flex; flex-direction: column; gap: 5px; font-size: 12px; color: var(--wb-muted); }
.filter-field .cr-select { min-width: 150px; }
.star-filter { display: flex; align-items: center; gap: 7px; font-size: 12.5px; color: var(--wb-text-2); padding-bottom: 9px; }

.origin-filter { display: flex; align-items: center; gap: 6px; margin-left: auto; }
.origin-chip {
  padding: 4px 12px;
  border-radius: 999px;
  border: 1px solid var(--wb-border);
  background: var(--wb-surface);
  color: var(--wb-muted);
  font-size: 12px;
  cursor: pointer;
}
.origin-chip.active {
  color: var(--wb-brand);
  border-color: color-mix(in srgb, var(--wb-brand) 40%, transparent);
  background: var(--wb-brand-soft);
}
.asset-thumb { display: block; width: 100%; padding: 0; border: 0; background: transparent; cursor: zoom-in; }
.asset-thumb img { display: block; width: 100%; }
.asset-origin { font-style: normal; font-size: 10.5px; padding: 1px 6px; border-radius: 999px; margin-left: 6px; vertical-align: 1px; }
.asset-origin.gen { background: var(--wb-brand-soft); color: var(--wb-brand); }
.asset-origin.up { background: var(--wb-fill); color: var(--wb-text-2); }
.asset-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(220px, 1fr));
  gap: 12px;
}
.asset-card {
  margin: 0;
  border: 1px solid var(--wb-border);
  border-radius: var(--wb-radius-lg);
  background: var(--wb-surface);
  overflow: hidden;
  display: flex;
  flex-direction: column;
}
.asset-card img { display: block; width: 100%; height: auto; min-height: 140px; max-height: 320px; object-fit: contain; background: var(--wb-fill); }
.asset-placeholder {
  aspect-ratio: 4 / 3;
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--wb-muted);
  font-size: 12px;
  background: var(--wb-fill);
}
.asset-card figcaption { display: flex; flex-direction: column; gap: 4px; padding: 9px 11px; }
.asset-name { font-size: 12.5px; font-weight: 550; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.asset-meta { font-size: 11px; color: var(--wb-muted); }
.asset-actions { display: flex; flex-wrap: wrap; gap: 2px; margin-top: 2px; }
.asset-actions .danger { color: var(--wb-danger); }
.load-more { display: flex; justify-content: center; padding: 8px 0 24px; }
</style>
