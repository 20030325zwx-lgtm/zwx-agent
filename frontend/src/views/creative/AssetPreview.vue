<template>
  <div class="ap-backdrop" role="dialog" aria-modal="true" :aria-label="`素材预览：${asset.displayName}`"
       @click.self="emit('close')">
    <div class="ap-panel">
      <header class="ap-head">
        <div class="ap-title">
          <strong>{{ asset.displayName }}</strong>
          <span class="ap-origin" :class="asset.origin === 'UPLOAD' ? 'up' : 'gen'">
            {{ asset.origin === 'UPLOAD' ? '原始素材' : '生成结果' }}
          </span>
          <span v-if="businessLabel" class="ap-kind">{{ businessLabel }}</span>
        </div>
        <button type="button" class="ap-close" aria-label="关闭预览" @click="emit('close')">×</button>
      </header>
      <div class="ap-body">
        <video v-if="isVideo && src" :src="src" controls preload="metadata" class="ap-video"></video>
        <img v-else-if="src" :src="src" :alt="asset.displayName" />
        <p v-else class="ap-hint">{{ isVideo ? '视频加载中…' : '图片加载中…' }}</p>
      </div>

      <!-- 使用位置：素材是资产，不是文件——这里回答"它现在被谁用着" -->
      <section class="ap-usage">
        <div class="ap-usage-head">
          <h3>使用位置</h3>
          <span class="cr-field-help">{{ usageSummary }}</span>
        </div>
        <p v-if="usageLoading" class="cr-hint">查询中…</p>
        <p v-else-if="usageError" class="cr-hint">{{ usageError }}</p>
        <ul v-else-if="usage.shots.length || usage.characterRefs.length || usage.locationRefs.length" class="ap-usage-list">
          <li v-for="item in usage.shots" :key="item.candidateId">
            <span class="ap-usage-kind">镜头</span>
            <router-link :to="`/creative/projects/${item.projectId}/storyboard`">
              第 {{ item.episodeNumber }} 集 · 第 {{ item.sceneNumber }} 场 · 镜 {{ item.shotNumber }}
            </router-link>
            <em>{{ shotStateLabel(item.status) }}</em>
          </li>
          <li v-for="item in usage.characterRefs" :key="'c-' + item.entityId">
            <span class="ap-usage-kind">角色</span>
            <router-link :to="`/creative/projects/${item.projectId}/settings`">{{ item.name || '未命名角色' }}</router-link>
            <em>参考图</em>
          </li>
          <li v-for="item in usage.locationRefs" :key="'l-' + item.entityId">
            <span class="ap-usage-kind">地点</span>
            <router-link :to="`/creative/projects/${item.projectId}/settings`">{{ item.name || '未命名地点' }}</router-link>
            <em>参考图</em>
          </li>
        </ul>
        <p v-else class="cr-hint">还没有被镜头或角色/地点引用。归档到项目后，生成时会被冻结引用。</p>
      </section>

      <footer class="ap-foot">
        <span class="ap-meta">
          {{ formatBytes(asset.byteSize) }}
          <template v-if="asset.width && asset.height"> · {{ asset.width }}×{{ asset.height }}</template>
          <template v-if="isVideo && formatDuration(asset.durationMs)"> · {{ formatDuration(asset.durationMs) }}</template>
          · {{ formatTime(asset.createdAt) }}
        </span>
        <div class="ap-actions">
          <label class="ap-classify">
            <span>分类</span>
            <DropdownSelect :model-value="asset.businessType || ''" size="sm" :options="businessOptions"
                            aria-label="素材分类" @change="onClassify" />
          </label>
          <slot name="actions" />
          <a v-if="src" class="cr-ghost" :href="src"
             :download="asset.displayName || (isVideo ? 'video.mp4' : 'asset.png')"
             target="_blank" rel="noopener">下载</a>
        </div>
      </footer>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { getCreativeAssetUsage } from '../../api'
import { formatBytes, formatDuration, formatTime } from './shared'
import DropdownSelect from '../../components/ui/DropdownSelect.vue'

const props = defineProps({
  asset: { type: Object, required: true },
  src: { type: String, default: '' }
})
const emit = defineEmits(['close', 'classify'])

const isVideo = computed(() => props.asset?.mediaType === 'VIDEO')
const BUSINESS_LABEL = { CHARACTER: '角色', LOCATION: '地点', PROP: '道具', SHOT: '镜头', OTHER: '其他' }
const businessOptions = [
  { value: '', label: '未归类' },
  { value: 'CHARACTER', label: '角色' },
  { value: 'LOCATION', label: '地点' },
  { value: 'PROP', label: '道具' },
  { value: 'SHOT', label: '镜头' },
  { value: 'OTHER', label: '其他' }
]
const businessLabel = computed(() => BUSINESS_LABEL[props.asset?.businessType] || '未归类')

const usage = reactive({ shots: [], characterRefs: [], locationRefs: [] })
const usageLoading = ref(false)
const usageError = ref('')

const usageSummary = computed(() => {
  const total = usage.shots.length + usage.characterRefs.length + usage.locationRefs.length
  return total ? `${total} 处引用` : '暂无引用'
})

function shotStateLabel(status) {
  return { SELECTED: '已采用', CANDIDATE: '待选片', GENERATING: '生成中', FAILED: '失败' }[status] || status
}

async function loadUsage() {
  if (!props.asset?.id) return
  usageLoading.value = true
  usageError.value = ''
  try {
    const data = await getCreativeAssetUsage(props.asset.id)
    usage.shots = data.shots || []
    usage.characterRefs = data.characterRefs || []
    usage.locationRefs = data.locationRefs || []
  } catch (exception) {
    usageError.value = exception.response?.data?.error || '使用位置查询失败'
  } finally {
    usageLoading.value = false
  }
}

function onClassify(value) {
  emit('classify', value || null)
}

onMounted(loadUsage)
watch(() => props.asset?.id, loadUsage)
</script>

<style scoped>
.ap-backdrop {
  position: fixed;
  inset: 0;
  z-index: 90;
  display: flex;
  align-items: center;
  justify-content: center;
  background: rgba(18, 21, 30, 0.66);
  padding: 32px;
}
.ap-panel {
  display: flex;
  flex-direction: column;
  gap: 10px;
  width: min(880px, 100%);
  max-height: 100%;
  padding: 16px 18px;
  border-radius: var(--wb-radius-lg);
  background: var(--wb-surface);
  color: var(--wb-text);
  box-shadow: var(--wb-shadow-lg);
}
.ap-head { display: flex; align-items: center; justify-content: space-between; gap: 10px; }
.ap-title { display: flex; align-items: center; gap: 8px; min-width: 0; }
.ap-title strong { font-size: var(--wb-fs-panel); color: var(--wb-text); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.ap-origin, .ap-kind { padding: 0 8px; border-radius: 999px; font-size: var(--wb-fs-caption); line-height: 18px; flex-shrink: 0; }
.ap-origin.gen { background: var(--wb-brand-soft); color: var(--wb-brand); }
.ap-origin.up { background: var(--wb-fill); color: var(--wb-text-2); }
.ap-kind { border: 1px solid var(--wb-border); color: var(--wb-text-2); }
.ap-close { border: 0; background: transparent; color: var(--wb-muted); font-size: 22px; line-height: 1; cursor: pointer; }
.ap-close:hover { color: var(--wb-text); }
.ap-body {
  flex: 1;
  min-height: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: var(--wb-radius);
  background: var(--wb-media-bg);
  overflow: auto;
}
.ap-body img { max-width: 100%; max-height: 52vh; object-fit: contain; }
.ap-body video.ap-video { max-width: 100%; max-height: 52vh; border-radius: var(--wb-radius-sm); background: var(--wb-media-bg); }
.ap-hint { color: var(--wb-muted); font-size: var(--wb-fs-caption); }

.ap-usage { border-top: 1px solid var(--wb-border); padding-top: 8px; }
.ap-usage-head { display: flex; align-items: baseline; justify-content: space-between; gap: 10px; }
.ap-usage-head h3 { margin: 0; font-size: var(--wb-fs-compact); font-weight: 600; }
.ap-usage-list { list-style: none; margin: 6px 0 0; padding: 0; display: flex; flex-direction: column; gap: 4px; max-height: 132px; overflow-y: auto; }
.ap-usage-list li { display: flex; align-items: center; gap: 8px; font-size: var(--wb-fs-caption); }
.ap-usage-kind { flex: 0 0 auto; padding: 0 6px; border-radius: 999px; background: var(--wb-fill); color: var(--wb-text-2); }
.ap-usage-list a { color: var(--wb-brand); text-decoration: none; }
.ap-usage-list em { margin-left: auto; font-style: normal; color: var(--wb-muted); }

.ap-foot { display: flex; align-items: center; justify-content: space-between; gap: 12px; flex-wrap: wrap; }
.ap-meta { color: var(--wb-muted); font-size: var(--wb-fs-caption); }
.ap-actions { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.ap-classify { display: flex; align-items: center; gap: 6px; font-size: var(--wb-fs-caption); color: var(--wb-muted); }
.ap-classify .cr-select { height: 28px; padding: 0 6px; }
</style>
