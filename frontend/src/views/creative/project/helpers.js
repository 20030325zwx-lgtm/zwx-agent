/** 项目页共用的展示映射：任务状态、来源、候选与镜头状态文案。 */

export const PROMPT_MAX = 500

export function isVideoJob(job) {
  return ['text_to_video', 'image_to_video'].includes(job?.operation)
}

export function blockedText(reason) {
  return {
    SUBMISSION_UNCERTAIN: '提交结果未确认：不会自动重发，可稍后再查询。',
    SCHEDULER_ERROR: '调度出现异常，已标记为需要关注。',
    UPSTREAM_CANCEL_UNSUPPORTED: '上游不支持取消，任务会继续生成；供应商可能仍产生费用。'
  }[reason] || reason
}

export function sourceLabel(job) {
  const base = { UI: '网页', AGENT: '智能体', WORKFLOW: '工作流', TEST: '测试' }[job.sourceType]
    || job.sourceType || '网页'
  if (job.sourceNodeId) return `${base} · 节点 ${job.sourceNodeId}`
  if (job.sourceRunId) return `${base} · 运行 ${String(job.sourceRunId).slice(0, 8)}`
  return base
}

/** 任务详情/结果卡的产物列表（只取已入库的）。 */
export function jobOutputs(job, formatBytes) {
  return (job.outputs || []).filter(output => output.state === 'DONE').map(output => ({
    ...output,
    meta: `${formatBytes(output.byteSize)}`
  }))
}

export function downloadName(job, output) {
  const extension = isVideoJob(job) ? 'mp4' : 'png'
  return `creative-${String(job.jobId).slice(0, 8)}-${output.index + 1}.${extension}`
}

export const SHOT_STATUS_LABEL = {
  DRAFT: '草稿',
  READY: '可生成',
  GENERATING: '生成中',
  PENDING_SELECTION: '待选片',
  SELECTED: '已选定'
}

export const CANDIDATE_STATUS_LABEL = {
  GENERATING: '生成中',
  CANDIDATE: '待选片',
  SELECTED: '已选定',
  FAILED: '失败'
}

export const BATCH_STATUS_LABEL = {
  RUNNING: '进行中',
  STOPPING: '停止中',
  STOPPED: '已停止',
  COMPLETED: '已完成'
}

export const BATCH_ITEM_STATUS_LABEL = {
  PENDING_SUBMIT: '待提交',
  SUBMITTED: '在途',
  DONE: '成功',
  FAILED: '失败',
  SKIPPED: '已停止'
}
