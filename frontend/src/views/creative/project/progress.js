/**
 * 短剧项目进度口径（纯函数，便于复算与排查）。
 *
 * 失败不能用 shot.status 判断：生成失败后镜头会回到 READY，
 * 所以「失败」必须由候选状态推导（有 FAILED 且没有可用候选）。
 */
export function storyProgress(shots = [], candidatesByShot = {}) {
  let selected = 0
  let pendingSelection = 0
  let missingPrompt = 0
  let failed = 0
  for (const shot of shots) {
    const candidates = candidatesByShot[shot.id] || []
    if (shot.status === 'SELECTED') selected += 1
    if (shot.status === 'PENDING_SELECTION') pendingSelection += 1
    if (!String(shot.promptText || '').trim()) missingPrompt += 1
    const hasUsable = candidates.some(item => item.status === 'CANDIDATE' || item.status === 'SELECTED')
    const hasFailed = candidates.some(item => item.status === 'FAILED')
    if (!hasUsable && hasFailed) failed += 1
  }
  const total = shots.length
  return {
    total,
    selected,
    pendingSelection,
    missingPrompt,
    failed,
    blocked: missingPrompt + failed,
    donePercent: total ? Math.round((selected / total) * 100) : 0
  }
}

/** 镜头卡的阻塞原因：缺提示词优先，其次是上次生成失败。 */
export function shotBlockReason(shot, candidates = []) {
  if (!String(shot?.promptText || '').trim()) return '缺少提交提示词'
  const usable = candidates.some(item => item.status === 'CANDIDATE' || item.status === 'SELECTED')
  const failed = candidates.some(item => item.status === 'FAILED')
  if (!usable && failed) return '上次生成失败'
  return ''
}
