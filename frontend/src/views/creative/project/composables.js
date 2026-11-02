/**
 * 项目页共享状态：任务（含轮询与动作）、剧情工作区、工作流列表，以及跨 tab 的表单交接。
 * 任务与剧情放在 Shell 层持有，避免每个 tab 各自轮询、各自拉一遍数据。
 */
import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue'
import {
  cancelCreativeJob, getCreativeJob, getStoryWorkspace, listCreativeJobs, listWorkflows,
  resumeCreativeJobIngest, retryCreativeJob
} from '../../../api'
import { storyProgress } from './progress'

export const PROJECT_CTX = Symbol('projectContext')

const ACTIVE_JOB_STATUSES = new Set(['QUEUED', 'SUBMITTING', 'RUNNING', 'CANCEL_REQUESTED', 'RECONCILING', 'FETCHING'])

export function isJobActive(status) {
  return ACTIVE_JOB_STATUSES.has(status) || status === 'INGEST_FAILED'
}

/** 素材「继续创作」到快速生成的表单交接（草稿接口不保存尺寸/水印，必须显式传递）。 */
export const studioSeed = reactive({ idea: '', effective: '', size: '', watermark: null, nonce: 0 })

export function seedStudio(job) {
  studioSeed.idea = job?.prompt || ''
  studioSeed.effective = job?.prompt || ''
  studioSeed.size = job?.parameters?.size || ''
  studioSeed.watermark = typeof job?.parameters?.watermark === 'boolean' ? job.parameters.watermark : null
  studioSeed.nonce += 1
}

export function useProjectJobs(projectId, showToast) {
  const jobs = ref([])
  const details = ref({})
  const loading = ref(false)
  const busyJob = ref('')
  const error = ref('')
  let pollTimer = null

  const activeCount = computed(() => jobs.value.filter(job => isJobActive(job.status)).length)

  function merge() {
    jobs.value = jobs.value.map(job => ({ ...job, ...(details.value[job.jobId] || {}) }))
  }

  async function refreshDetails() {
    const targets = jobs.value
    const results = await Promise.allSettled(targets.map(job => getCreativeJob(job.jobId)))
    const next = { ...details.value }
    results.forEach((result, index) => {
      if (result.status === 'fulfilled') next[targets[index].jobId] = result.value
    })
    details.value = next
    merge()
  }

  async function load() {
    if (!projectId.value) return
    loading.value = true
    error.value = ''
    try {
      const data = await listCreativeJobs({ projectId: projectId.value, limit: 20 })
      jobs.value = data.items || []
      await refreshDetails()
    } catch (exception) {
      error.value = exception.response?.data?.error || '任务记录加载失败'
    } finally {
      loading.value = false
    }
    schedulePoll()
  }

  function schedulePoll() {
    window.clearTimeout(pollTimer)
    if (!jobs.value.some(job => isJobActive(job.status))) return
    pollTimer = window.setTimeout(async () => {
      if (document.visibilityState === 'hidden') {
        schedulePoll()
        return
      }
      try {
        const data = await listCreativeJobs({ projectId: projectId.value, limit: 20 })
        jobs.value = (data.items || []).map(job => ({ ...job, ...(details.value[job.jobId] || {}) }))
        await refreshDetails()
      } catch (exception) { /* 下一次轮询重试 */ }
      schedulePoll()
    }, 2500)
  }

  function stop() {
    window.clearTimeout(pollTimer)
  }

  function reset() {
    stop()
    jobs.value = []
    details.value = {}
    error.value = ''
  }

  async function cancel(job) {
    busyJob.value = job.jobId
    try {
      await cancelCreativeJob(job.jobId)
      showToast(job.status === 'RUNNING' ? '已请求取消，结果仍可能产生' : '任务已取消', 'success')
      await load()
    } catch (exception) {
      showToast(exception.response?.data?.error || '取消失败', 'error')
    } finally {
      busyJob.value = ''
    }
  }

  async function retry(job, acknowledge = false) {
    busyJob.value = job.jobId
    try {
      await retryCreativeJob(job.jobId, { acknowledgePossibleDuplicateCharge: acknowledge })
      showToast('已创建新的生成任务', 'success')
      await load()
    } catch (exception) {
      showToast(exception.response?.data?.error || '重试失败', 'error')
    } finally {
      busyJob.value = ''
    }
  }

  async function resumeIngest(job) {
    busyJob.value = job.jobId
    try {
      await resumeCreativeJobIngest(job.jobId)
      showToast('已请求重新保存，不会重新生成图片', 'success')
      await load()
    } catch (exception) {
      showToast(exception.response?.data?.error || '恢复失败', 'error')
    } finally {
      busyJob.value = ''
    }
  }

  onBeforeUnmount(stop)

  return {
    jobs, details, loading, busyJob, error, activeCount,
    load, reset, cancel, retry, resumeIngest, refreshDetails
  }
}

/**
 * 剧情工作区：一次拉全量（剧集/场次/镜头/候选），Shell 与剧情、分镜两个 tab 共用。
 * 只在有镜头生成中或批次运行时轮询，轮询随组件卸载停止。
 */
export function useStoryWorkspace(projectId, enabled) {
  const workspace = ref(null)
  const loading = ref(false)
  const error = ref('')
  let pollTimer = null

  const shots = computed(() => workspace.value?.shots || [])
  const episodes = computed(() => workspace.value?.episodes || [])
  const scenes = computed(() => workspace.value?.scenes || [])
  const characters = computed(() => workspace.value?.characters || [])
  const locations = computed(() => workspace.value?.locations || [])
  const candidatesByShot = computed(() => workspace.value?.candidatesByShot || {})
  const progress = computed(() => storyProgress(shots.value, candidatesByShot.value))

  async function reload() {
    if (!projectId.value) return
    loading.value = true
    error.value = ''
    try {
      workspace.value = await getStoryWorkspace(projectId.value)
    } catch (exception) {
      error.value = exception.response?.data?.error || `剧情加载失败：${exception.message || exception}`
    } finally {
      loading.value = false
    }
    schedulePoll()
  }

  function candidatesOf(shotId) {
    return candidatesByShot.value[shotId] || []
  }

  function schedulePoll() {
    window.clearTimeout(pollTimer)
    if (!enabled?.value) return
    const generatingShot = shots.value.some(shot => shot.status === 'GENERATING')
    const generatingCandidate = Object.values(candidatesByShot.value)
      .some(list => (list || []).some(item => item.status === 'GENERATING'))
    if (!generatingShot && !generatingCandidate) return
    pollTimer = window.setTimeout(async () => {
      if (document.visibilityState === 'hidden') {
        schedulePoll()
        return
      }
      await reload()
    }, 3000)
  }

  function reset() {
    window.clearTimeout(pollTimer)
    workspace.value = null
    error.value = ''
  }

  onBeforeUnmount(() => window.clearTimeout(pollTimer))

  return {
    workspace, loading, error, shots, episodes, scenes, characters, locations,
    candidatesByShot, progress, reload, reset, candidatesOf
  }
}

/** 工作流模板列表（项目内「用工作流创作」与「写新章节」共用，避免重复拉取）。 */
export function useWorkflowList() {
  const workflows = ref([])
  const loading = ref(false)
  async function ensureLoaded() {
    if (workflows.value.length) return workflows.value
    loading.value = true
    try {
      const data = await listWorkflows(30)
      workflows.value = data.definitions || []
    } catch (exception) {
      workflows.value = []
    } finally {
      loading.value = false
    }
    return workflows.value
  }
  return { workflows, loading, ensureLoaded }
}

/** 监听项目切换：各 tab 通过这个重置自己的局部状态（不再依赖 Shell 的 tab 变量）。 */
export function watchProjectChange(projectId, handler) {
  watch(projectId, () => handler(), { flush: 'pre' })
}
