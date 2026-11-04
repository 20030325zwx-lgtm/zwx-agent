export const TYPE_LABELS = { intent: '意图路由', retrieval: '检索命中', prompt: '单轮直调', task: '全链路任务' }

export const STATUS = {
  queued: ['排队中', 'info'],
  running: ['执行中', 'warning'],
  succeeded: ['已完成', 'success'],
  failed: ['失败', 'danger'],
  cancelled: ['已取消', 'muted']
}

export function typeLabel(value) {
  return TYPE_LABELS[value] || value || '—'
}

export function statusLabel(status) {
  return STATUS[status]?.[0] || status
}

export function statusTone(status) {
  return STATUS[status]?.[1] || 'muted'
}

export function boolLabel(passed) {
  return passed === null || passed === undefined ? '跳过' : passed ? '通过' : '未过'
}

export function boolTone(passed) {
  return passed === null || passed === undefined ? 'muted' : passed ? 'success' : 'danger'
}

export function formatTime(value) {
  if (!value) return '—'
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? String(value) : date.toLocaleString('zh-CN', { hour12: false })
}

export function passRate(totals) {
  if (!totals || totals.passRate === null || totals.passRate === undefined) return '—'
  return `${Math.round(totals.passRate * 1000) / 10}%`
}

export function money(value) {
  if (value === null || value === undefined || value === '') return '—'
  const number = Number(value)
  if (Number.isNaN(number)) return '—'
  if (number === 0) return '¥0'
  return number < 0.01 ? `¥${number.toFixed(4)}` : `¥${number.toFixed(3)}`
}

export function numberText(value, suffix = '') {
  return value === null || value === undefined ? '—' : `${value}${suffix}`
}

export function parseJson(value, fallback) {
  if (value === null || value === undefined) return fallback
  if (typeof value === 'object') return value
  try {
    return JSON.parse(value)
  } catch {
    return fallback
  }
}

export function datasetName(datasets, id) {
  return datasets.find((item) => item.id === id)?.name || String(id || '').slice(0, 8)
}

export function caseSummary(payload) {
  if (!payload) return ''
  return payload.question || payload.task || payload.input || JSON.stringify(payload).slice(0, 90)
}
