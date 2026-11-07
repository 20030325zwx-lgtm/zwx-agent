<template>
  <div class="ps-shell" :class="{ 'canvas-mode': activeKey === 'canvas' }">
    <header class="ps-bar">
      <router-link to="/creative/works" class="ps-back" aria-label="返回作品">
        <ArrowLeft :size="16" />
      </router-link>
      <div class="ps-title">
        <h1 class="ps-name">{{ project?.name || '作品' }}</h1>
        <span v-if="project" class="cr-kind-badge">{{ kindLabel(project.kind) }}</span>
        <span v-if="project" class="cr-status" :class="project.status === 'ACTIVE' ? 'success' : 'muted'">
          {{ project.status === 'ACTIVE' ? '进行中' : '已归档' }}
        </span>
      </div>
      <!-- 画布模式把项目导航并进标题栏：少一条通栏分隔线，画布多拿 42px 竖向空间 -->
      <nav v-if="activeKey === 'canvas'" class="ps-tabs" aria-label="项目导航">
        <router-link
          v-for="item in navItems"
          :key="item.key"
          :to="`/creative/projects/${projectId}/${item.key}`"
          class="ps-tab"
          :class="{ active: activeKey === item.key }"
        >
          <component :is="item.icon" :size="14" :stroke-width="1.8" />
          <span>{{ item.label }}</span>
          <em v-if="item.key === 'storyboard' && story.progress.value.pendingSelection" class="ps-tab-badge">
            {{ story.progress.value.pendingSelection }}
          </em>
        </router-link>
      </nav>
      <div v-if="isVideo && activeKey !== 'canvas'" class="ps-progress" aria-label="制作进度">
        <span class="ps-metric">镜头 <em>{{ story.progress.value.total }}</em></span>
        <span class="ps-metric">已选片 <em>{{ story.progress.value.selected }}</em></span>
        <router-link v-if="story.progress.value.pendingSelection" class="ps-metric is-warn"
                     :to="`/creative/projects/${projectId}/storyboard?filter=pending`">
          待选片 <em>{{ story.progress.value.pendingSelection }}</em>
        </router-link>
        <span v-if="story.progress.value.blocked" class="ps-metric is-warn">
          阻塞 <em>{{ story.progress.value.blocked }}</em>
        </span>
      </div>
      <div class="ps-actions">
        <button type="button" class="cr-ghost" :disabled="refreshing" @click="refreshAll">
          <RefreshCw :size="14" /> {{ refreshing ? '刷新中…' : '刷新' }}
        </button>
      </div>
    </header>

    <p v-if="pageError" class="ps-error cr-error">{{ pageError }}</p>

    <div class="ps-body">
      <nav class="ps-rail" aria-label="项目导航">
        <router-link
          v-for="item in navItems"
          :key="item.key"
          :to="`/creative/projects/${projectId}/${item.key}`"
          class="ps-nav-item"
          :class="{ active: activeKey === item.key }"
        >
          <component :is="item.icon" :size="17" :stroke-width="1.8" />
          <span class="ps-nav-label">{{ item.label }}</span>
          <em v-if="item.key === 'storyboard' && story.progress.value.pendingSelection" class="ps-nav-badge">
            {{ story.progress.value.pendingSelection }}
          </em>
        </router-link>
        <template v-if="toolItems.length">
          <div class="ps-rail-divider" role="separator" />
          <router-link
            v-for="item in toolItems"
            :key="item.key"
            :to="`/creative/projects/${projectId}/${item.key}`"
            class="ps-nav-item ps-nav-tool"
            :class="{ active: activeKey === item.key }"
          >
            <component :is="item.icon" :size="17" :stroke-width="1.8" />
            <span class="ps-nav-label">{{ item.label }}</span>
          </router-link>
        </template>
      </nav>

      <main class="ps-main">
        <p v-if="!project && !pageError" class="cr-hint">加载中…</p>
        <router-view v-else-if="project" :key="projectId" />
      </main>
    </div>

    <!-- 任务详情：快速生成与任务记录共用，固定在 Shell，避免每个 tab 各做一份 -->
    <div v-if="detailJob" class="detail-backdrop" @click.self="closeJob">
      <aside class="detail-panel" role="dialog" aria-modal="true" aria-label="任务详情">
        <header class="detail-head">
          <div>
            <h2>任务详情</h2>
            <p class="cr-field-help">{{ detailJob.jobId }}</p>
          </div>
          <button type="button" class="cr-ghost" @click="closeJob">关闭</button>
        </header>
        <dl class="detail-list">
          <div><dt>状态</dt><dd><span class="cr-status" :class="statusMeta(detailJob.status).tone">{{ statusMeta(detailJob.status).label }}</span></dd></div>
          <div><dt>提示词</dt><dd class="detail-prompt">{{ detailJob.prompt || '—' }}</dd></div>
          <div><dt>模型</dt><dd>{{ detailJob.modelName || '—' }}</dd></div>
          <div><dt>来源</dt><dd>{{ sourceLabel(detailJob) }}</dd></div>
          <div><dt>尺寸</dt><dd>{{ detailJob.parameters?.size || '—' }}</dd></div>
          <div><dt>创建时间</dt><dd>{{ formatTime(detailJob.createdAt) }}</dd></div>
          <div><dt>更新时间</dt><dd>{{ formatTime(detailJob.updatedAt) }}</dd></div>
          <div v-if="detailJob.providerTaskId"><dt>供应商任务</dt><dd class="mono">{{ detailJob.providerTaskId }}</dd></div>
          <div v-if="detailJob.error"><dt>错误</dt><dd class="cr-error">{{ detailJob.error }}</dd></div>
          <div v-if="detailJob.blockedReason"><dt>说明</dt><dd>{{ blockedText(detailJob.blockedReason) }}</dd></div>
        </dl>
        <ReferenceAssetStrip v-if="detailJob.referenceAssetIds?.length"
                             :asset-ids="detailJob.referenceAssetIds"
                             :label="detailJob.operation === 'image_to_video' ? '首帧素材' : '参考素材'"
                             :evidence="detailJob.providerTaskId
                               ? '供应商已受理，素材已随请求发送'
                               : '已保存在任务输入快照中'" />
        <div v-if="detailJob.outputs?.length" class="detail-outputs">
          <h3>产物</h3>
          <div v-for="output in detailJob.outputs" :key="output.assetId" class="detail-output">
            <img v-if="urls[output.assetId]" :src="urls[output.assetId]" :alt="`产物 ${output.index + 1}`" />
            <div class="detail-output-meta">
              <span>{{ output.state === 'DONE' ? '已保存' : output.state }}</span>
              <span>{{ formatBytes(output.byteSize) }}</span>
            </div>
            <button v-if="urls[output.assetId]" type="button" class="cr-secondary"
                    @click="downloadBlob(urls[output.assetId], `creative-${output.assetId}.png`)">下载原图</button>
          </div>
        </div>
        <div class="detail-actions">
          <HoldToConfirm v-if="['QUEUED','RUNNING'].includes(detailJob.status)" label="取消任务"
                         :disabled="jobsStore.busyJob.value === detailJob.jobId"
                         @confirm="jobsStore.cancel(detailJob)" />
          <button v-if="detailJob.status === 'INGEST_FAILED'" type="button" class="cr-primary"
                  :disabled="jobsStore.busyJob.value === detailJob.jobId"
                  @click="jobsStore.resumeIngest(detailJob)">
            <Spinner v-if="jobsStore.busyJob.value === detailJob.jobId" /> 重新保存
          </button>
          <HoldToConfirm v-if="retryNeedsRiskAcknowledgement(detailJob)" label="确认后重试（可能二次计费）"
                         :disabled="jobsStore.busyJob.value === detailJob.jobId"
                         @confirm="jobsStore.retry(detailJob, true)" />
          <button v-else-if="['FAILED','CANCELLED'].includes(detailJob.status)" type="button"
                  class="cr-secondary" :disabled="jobsStore.busyJob.value === detailJob.jobId"
                  @click="jobsStore.retry(detailJob)">重试</button>
        </div>
      </aside>
    </div>

    <p v-if="toast.text" class="cr-toast" :class="toast.tone">{{ toast.text }}</p>
  </div>
</template>

<script setup>
import { computed, onMounted, provide, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  ArrowLeft, BookOpen, Clapperboard, FileText, Images, LayoutGrid, ListChecks, RefreshCw, Users, Workflow
} from 'lucide-vue-next'
import { getCreativeProject, getCreativeJob, getProjectSettings, listModelCatalog } from '../../../api'
import { downloadBlob, formatBytes, formatTime, kindLabel, retryNeedsRiskAcknowledgement, statusMeta, useAssetUrls, useVideoUrls } from '../shared'
import { blockedText, isVideoJob, sourceLabel } from './helpers'
import { PROJECT_CTX, useProjectJobs, useStoryWorkspace } from './composables'
import HoldToConfirm from '../../../components/ui/HoldToConfirm.vue'
import Spinner from '../../../components/ui/Spinner.vue'
import ReferenceAssetStrip from '../../../components/creative/ReferenceAssetStrip.vue'

const TAB_KEYS = new Set(['production', 'canvas', 'studio', 'story', 'storyboard', 'settings', 'chapters', 'workflow', 'tasks', 'assets'])

const route = useRoute()
const router = useRouter()
const { urls, urlFor } = useAssetUrls()
const { videoUrls, videoUrlFor } = useVideoUrls()

const projectId = computed(() => route.params.projectId)
const project = ref(null)
const pageError = ref('')
const imageBinding = ref(null)
const videoBinding = ref(null)
const imageProtocol = computed(() => imageBinding.value?.protocol || 'dashscope')
const detailJob = ref(null)
const refreshing = ref(false)
const toast = reactive({ text: '', tone: '' })

function showToast(text, tone = '') {
  toast.text = text
  toast.tone = tone
  window.clearTimeout(showToast.timer)
  showToast.timer = window.setTimeout(() => { toast.text = ''; toast.tone = '' }, 3600)
}

const jobsStore = useProjectJobs(projectId, showToast)
const isVideo = computed(() => project.value?.kind === 'VIDEO')
// 项目画风：制作视图保存后全局生效（画布节点/镜头生成默认继承），这里只读提供
const projectStyle = ref('')
const projectTargetSeconds = ref(0)
const story = useStoryWorkspace(projectId, isVideo)

const activeKey = computed(() => {
  // 路径形如 /creative/projects/:id/<分区>，取最后一段判断当前分区
  const segment = route.path.split('/').filter(Boolean).pop()
  return TAB_KEYS.has(segment) ? segment : ''
})

// 项目里只表达"这部作品的内部结构"：全局的 作品/素材/任务 语义在这里是项目内的视角
const NAV = {
  IMAGE: [
    { key: 'canvas', label: '画布', icon: Workflow },
    { key: 'tasks', label: '任务', icon: ListChecks },
    { key: 'assets', label: '素材', icon: Images }
  ],
  NOVEL: [
    { key: 'chapters', label: '章节', icon: FileText },
    { key: 'workflow', label: '工作流', icon: Workflow },
    { key: 'tasks', label: '任务', icon: ListChecks },
    { key: 'assets', label: '素材', icon: Images }
  ],
  // P0 制作视图（2026-11-05）：剧情/分镜并入「制作」的阶段栏，设定/素材/任务降为工具入口。
  // 旧路由（story/storyboard/settings）保留供深链接，不再出现在导航里。
  VIDEO: [
    { key: 'production', label: '制作', icon: Clapperboard },
    { key: 'canvas', label: '画布', icon: Workflow },
  ]
}
// 工具入口：项目级资源与记录，不参与阶段推进
const NAV_TOOLS = [
  { key: 'settings', label: '设定', icon: Users },
  { key: 'assets', label: '素材', icon: Images },
  { key: 'tasks', label: '任务', icon: ListChecks },
]
const navItems = computed(() => NAV[project.value?.kind] || NAV.IMAGE)
const toolItems = computed(() => (project.value?.kind === 'VIDEO' ? NAV_TOOLS : []))

function defaultTab(kind) {
  if (kind === 'NOVEL') return 'chapters'
  return kind === 'VIDEO' ? 'production' : 'canvas'
}

async function loadProject() {
  pageError.value = ''
  try {
    // 10 秒兜底：任何原因（后端重启窗口/代理抖动）导致的挂起都转为可重试错误，
    // 不让项目页无限空白卡死——「刷新」按钮随时可重试。
    const detail = await Promise.race([
      getCreativeProject(projectId.value),
      new Promise((resolve, reject) => setTimeout(
        () => reject(new Error('项目加载超时，请点击右上角「刷新」重试')), 10000))
    ])
    project.value = detail
  } catch (exception) {
    pageError.value = exception.response?.status === 404
      ? '创作项目不存在或无权访问'
      : (exception.response?.data?.error || exception.message || `项目加载失败：${exception.message || '未知错误'}`)
    return
  }
  await Promise.all([loadCatalog(), jobsStore.load()])
  if (isVideo.value) {
    await story.reload()
    try {
      const settings = await getProjectSettings(projectId.value)
      projectStyle.value = settings.videoStyle || ''
      projectTargetSeconds.value = Number(settings.videoTargetSeconds) || 0
    } catch { /* 设定读取失败不阻断 */ }
  }
}

async function loadCatalog() {
  try {
    const catalog = await listModelCatalog()
    imageBinding.value = catalog.userBindings?.image_generation
      || catalog.systemBindings?.image_generation || null
    videoBinding.value = catalog.userBindings?.video_generation
      || catalog.systemBindings?.video_generation || null
  } catch (exception) {
    imageBinding.value = null
    videoBinding.value = null
  }
}

async function refreshAll() {
  refreshing.value = true
  try {
    await loadProject()
  } finally {
    refreshing.value = false
  }
}

/**
 * 路由兼容：旧链接 `?tab=xxx&runId=` 全部重定向到子路由；
 * 直接打开 /projects/:id 时按项目类型落到默认分区。
 */
async function settleRoute() {
  const legacy = route.query.tab
  let key = TAB_KEYS.has(legacy) ? legacy : activeKey.value
  if (key === 'studio') key = 'canvas'
  if (!key) key = defaultTab(project.value?.kind)
  const query = {}
  if (route.query.runId) query.runId = route.query.runId
  if (route.query.jobId) query.jobId = route.query.jobId
  const target = `/creative/projects/${projectId.value}/${key}`
  if (route.path !== target || legacy) {
    router.replace({ path: target, query })
  }
}

function openJob(jobId) {
  if (!jobId) return
  if (jobsStore.details.value[jobId]) {
    detailJob.value = jobsStore.details.value[jobId]
    return
  }
  getCreativeJob(jobId)
    .then(job => { detailJob.value = job })
    .catch(() => showToast('任务详情加载失败', 'error'))
}

function closeJob() {
  detailJob.value = null
  if (route.query.jobId) {
    const query = { ...route.query }
    delete query.jobId
    router.replace({ path: route.path, query })
  }
}

provide(PROJECT_CTX, {
  projectId,
  project,
  imageBinding,
  videoBinding,
  imageProtocol,
  jobsStore,
  story,
  projectStyle,
  projectTargetSeconds,
  showToast,
  openJob,
  urls,
  videoUrls,
  videoUrlFor,
  route,
  router
})

// 路由切换时刷新项目画风：制作视图保存后，画布/检查器无需整页刷新即可继承
watch(() => route.path, () => {
  if (!isVideo.value) return
  getProjectSettings(projectId.value).then(settings => {
    projectStyle.value = settings.videoStyle || ''
    projectTargetSeconds.value = Number(settings.videoTargetSeconds) || 0
  }).catch(() => {})
})

// 产物预览地址在 Shell 预取：任务抽屉、快速生成结果卡与素材卡共用同一份缓存
watch(() => jobsStore.jobs.value, list => {
  list.forEach(job => {
    ;(job.outputs || []).forEach(output => {
      if (output.state !== 'DONE') return
      if (isVideoJob(job)) videoUrlFor(output.assetId)
      else urlFor(output.assetId)
    })
  })
}, { immediate: true })

watch(() => route.params.projectId, async () => {
  detailJob.value = null
  jobsStore.reset()
  story.reset()
  await loadProject()
  await settleRoute()
})

watch(() => route.fullPath, () => {
  if (project.value) settleRoute()
  openJob(route.query.jobId)
})

onMounted(async () => {
  await loadProject()
  await settleRoute()
  openJob(route.query.jobId)
})
</script>

<style scoped src="../creative.css"></style>

<style scoped>
.ps-shell {
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  background: var(--wb-bg);
  /* 深浅主题文字色由工作台令牌决定，不能继承 body 的静态 --sk-label */
  color: var(--wb-text);
}

.ps-bar {
  height: 56px;
  flex: 0 0 56px;
  display: flex;
  align-items: center;
  gap: var(--wb-space-3);
  padding: 0 var(--wb-space-5);
  border-bottom: 1px solid var(--wb-border);
  background: var(--wb-surface);
}

.ps-back {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 30px;
  height: 30px;
  border-radius: var(--wb-radius-sm);
  color: var(--wb-text-2);
}

.ps-back:hover { background: var(--wb-fill); color: var(--wb-text); }

.ps-title { display: flex; align-items: center; gap: var(--wb-space-2); min-width: 0; }

.ps-name {
  margin: 0;
  font-size: var(--wb-fs-section);
  line-height: var(--wb-lh-section);
  font-weight: 600;
  max-width: 380px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.ps-progress { display: flex; align-items: center; gap: var(--wb-space-3); margin-left: var(--wb-space-4); }

.ps-metric {
  font-size: var(--wb-fs-caption);
  color: var(--wb-muted);
  text-decoration: none;
  white-space: nowrap;
}

.ps-metric em {
  font-style: normal;
  font-weight: 600;
  color: var(--wb-text);
  margin-left: 2px;
}

.ps-metric.is-warn em { color: var(--wb-warning); }

.ps-actions { margin-left: auto; display: flex; align-items: center; gap: var(--wb-space-2); }

.ps-error { padding: var(--wb-space-3) var(--wb-space-5) 0; }

.ps-body { flex: 1; min-height: 0; display: flex; }

.ps-rail {
  width: 200px;
  flex: 0 0 200px;
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: var(--wb-space-3) var(--wb-space-2);
  border-right: 1px solid var(--wb-border);
  background: var(--wb-surface);
  overflow-y: auto;
}

.ps-nav-item {
  position: relative;
  display: flex;
  align-items: center;
  gap: var(--wb-space-2);
  height: 36px;
  padding: 0 var(--wb-space-2);
  border-radius: var(--wb-radius-sm);
  color: var(--wb-text-2);
  font-size: var(--wb-fs-compact);
  text-decoration: none;
  white-space: nowrap;
}

.ps-nav-item:hover { background: var(--wb-fill); color: var(--wb-text); }

.ps-nav-item.active {
  background: var(--wb-brand-soft);
  color: var(--wb-brand);
  font-weight: 550;
}

.ps-nav-item.active::before {
  content: '';
  position: absolute;
  left: -6px;
  top: 8px;
  bottom: 8px;
  width: 2px;
  border-radius: 2px;
  background: var(--wb-brand);
}

.ps-nav-label { min-width: 0; overflow: hidden; text-overflow: ellipsis; }

.ps-nav-badge {
  margin-left: auto;
  font-style: normal;
  font-size: var(--wb-fs-caption);
  line-height: 16px;
  padding: 0 6px;
  border-radius: 999px;
  background: var(--wb-warning);
  color: #fff;
}

.ps-main { flex: 1; min-width: 0; min-height: 0; display: flex; flex-direction: column; overflow: hidden; }

.detail-backdrop {
  position: fixed;
  inset: 0;
  z-index: 45;
  background: rgb(0 0 0 / 32%);
  display: flex;
  justify-content: flex-end;
}

.detail-panel {
  width: min(440px, 100%);
  height: 100%;
  overflow-y: auto;
  background: var(--wb-surface);
  border-left: 1px solid var(--wb-border);
  padding: var(--wb-space-4) var(--wb-space-5);
  display: flex;
  flex-direction: column;
  gap: var(--wb-space-4);
  box-shadow: var(--wb-shadow-lg);
}

.detail-head { display: flex; align-items: flex-start; justify-content: space-between; }
.detail-head h2 { margin: 0; font-size: var(--wb-fs-section); }
.detail-list { margin: 0; display: flex; flex-direction: column; gap: 10px; font-size: var(--wb-fs-compact); }
.detail-list > div { display: grid; grid-template-columns: 88px 1fr; gap: 10px; }
.detail-list dt { color: var(--wb-muted); }
.detail-list dd { margin: 0; word-break: break-word; }
.detail-prompt { line-height: 1.6; }
.mono { font-family: var(--wb-font-mono, monospace); font-size: var(--wb-fs-caption); overflow-wrap: anywhere; }
.detail-outputs { display: flex; flex-direction: column; gap: 10px; }
.detail-outputs h3 { margin: 0; font-size: var(--wb-fs-panel); }
.detail-output { border: 1px solid var(--wb-border); border-radius: var(--wb-radius); padding: 8px; display: flex; flex-direction: column; gap: 8px; }
.detail-output img { width: 100%; height: auto; border-radius: 6px; display: block; }
.detail-output-meta { display: flex; justify-content: space-between; font-size: var(--wb-fs-caption); color: var(--wb-muted); }
.detail-actions { display: flex; gap: 8px; flex-wrap: wrap; }

.ps-rail-divider { height: 1px; margin: 6px 4px; background: var(--wb-border); flex: none; }
.ps-nav-tool { color: var(--wb-muted, #8a90a6); }
.ps-nav-tool.active { background: var(--wb-brand-soft); color: var(--wb-brand); font-weight: 550; }

@media (max-width: 1099px) {
  .ps-rail { width: 56px; flex-basis: 56px; align-items: center; }
  .ps-nav-label, .ps-nav-badge { display: none; }
  .ps-nav-item { justify-content: center; padding: 0; }
  .ps-progress { display: none; }
}
/* The canvas owns the center; project sections become pills in the title bar. */
.ps-shell.canvas-mode { min-width: 0; overflow: hidden; }
.canvas-mode .ps-body { flex-direction: column; overflow: hidden; }
.canvas-mode .ps-rail { display: none; }
.canvas-mode .ps-main { padding: 0; min-width: 0; min-height: 0; overflow: hidden; display: flex; }
.canvas-mode .ps-name { font-size: 14px; max-width: 320px; }
/* 画布模式顶栏融入画布：去通栏底色与分隔线，避免「左侧圆角浮层 vs 右侧直角通栏」的割裂；
   控件圆角统一到 --wb-radius（8px 系），状态胶囊不再单独用 999px */
.canvas-mode .ps-bar { background: transparent; border-bottom: 0; }
.canvas-mode .ps-back { border-radius: var(--wb-radius); }
.canvas-mode .ps-title .cr-status { border-radius: var(--wb-radius); }
.ps-tabs { display: flex; align-items: center; gap: 2px; margin-left: 4px; min-width: 0; overflow-x: auto; scrollbar-width: none; }
.ps-tabs::-webkit-scrollbar { display: none; }
.ps-tab { display: inline-flex; align-items: center; gap: 6px; height: 30px; padding: 0 10px; border-radius: var(--wb-radius); color: var(--wb-text-2); font-size: 12px; text-decoration: none; white-space: nowrap; }
.ps-tab:hover { background: var(--wb-fill); color: var(--wb-text); }
.ps-tab.active { background: var(--wb-brand-soft); color: var(--wb-brand); font-weight: 550; }
.ps-tab-badge { font-style: normal; font-size: 10px; line-height: 16px; padding: 0 5px; border-radius: 999px; background: var(--wb-warning); color: #fff; }

@media (max-width: 1099px) {
  /* 窄屏优先保住导航：隐去类型与状态标记，标签页可横向滚动 */
  .canvas-mode .cr-kind-badge,
  .canvas-mode .ps-title .cr-status { display: none; }
  .canvas-mode .ps-tabs { margin-left: 0; }
}

</style>
