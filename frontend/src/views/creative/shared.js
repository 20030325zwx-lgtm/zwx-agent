import { onBeforeUnmount, reactive } from 'vue'
import { getCreativeAssetBlob, getCreativeAssetThumbnail } from '../../api'

export const KIND_LABEL = {
  IMAGE: '图片',
  VIDEO: '视频',
  NOVEL: '小说',
  COMIC: '漫画'
}

export function kindLabel(kind) {
  return KIND_LABEL[kind] || '图片'
}

export const JOB_STATUS = {
  QUEUED: { label: '排队中', tone: 'muted', hint: '等待调度，不会产生不必要的重复请求' },
  SUBMITTING: { label: '提交中', tone: 'info', hint: '正在提交给供应商' },
  RUNNING: { label: '生成中', tone: 'info', hint: '供应商正在生成，可离开页面' },
  CANCEL_REQUESTED: { label: '取消中', tone: 'warning', hint: '已请求取消，结果仍可能产生' },
  RECONCILING: { label: '结果待确认', tone: 'warning', hint: '提交结果不确定，可能已产生供应商任务' },
  FETCHING: { label: '保存结果中', tone: 'info', hint: '图片已生成，正在保存到素材库' },
  INGEST_FAILED: { label: '保存失败', tone: 'danger', hint: '图片可能已生成，但保存到素材库失败' },
  SUCCEEDED: { label: '已完成', tone: 'success', hint: '结果已保存，可预览与下载' },
  FAILED: { label: '生成失败', tone: 'danger', hint: '未产生可用图片，可修改后重试' },
  CANCELLED: { label: '已取消', tone: 'muted', hint: '任务已取消' }
}

export const ACTIVE_STATUSES = new Set(['QUEUED', 'SUBMITTING', 'RUNNING', 'CANCEL_REQUESTED', 'RECONCILING', 'FETCHING', 'INGEST_FAILED'])

export function statusMeta(status) {
  return JOB_STATUS[status] || { label: status || '未知', tone: 'muted', hint: '' }
}

export function formatTime(value) {
  if (!value) return ''
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return ''
  const now = Date.now()
  const diff = now - date.getTime()
  if (diff < 60_000) return '刚刚'
  if (diff < 3_600_000) return `${Math.floor(diff / 60_000)} 分钟前`
  if (diff < 86_400_000) return `${Math.floor(diff / 3_600_000)} 小时前`
  const pad = n => String(n).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`
}

export function formatBytes(size) {
  if (!size && size !== 0) return ''
  if (size < 1024) return `${size} B`
  if (size < 1024 * 1024) return `${(size / 1024).toFixed(1)} KB`
  return `${(size / 1024 / 1024).toFixed(1)} MB`
}

/**
 * 素材内容是鉴权接口，不能直接放进 img.src：统一用 blob URL 并缓存，
 * 组件卸载时释放。避免把 token 放进 URL。
 */
export function useAssetUrls() {
  const state = reactive({})
  const thumbs = reactive({})
  const pending = new Map()
  const created = new Set()

  async function urlFor(assetId) {
    if (!assetId) return ''
    if (state[assetId]) return state[assetId]
    if (pending.has(assetId)) return pending.get(assetId)
    const task = getCreativeAssetBlob(assetId)
      .then(blob => {
        const url = URL.createObjectURL(blob)
        created.add(url)
        state[assetId] = url
        return url
      })
      .catch(() => '')
      .finally(() => pending.delete(assetId))
    pending.set(assetId, task)
    return task
  }

  /** 列表优先取缩略图；缩略图缺失或失败时回退原图，保证不会出现空图。 */
  async function thumbFor(assetId) {
    if (!assetId) return ''
    if (thumbs[assetId]) return thumbs[assetId]
    const key = `thumb:${assetId}`
    if (pending.has(key)) return pending.get(key)
    const task = getCreativeAssetThumbnail(assetId)
      .then(blob => {
        if (!blob || blob.size === 0) throw new Error('empty thumbnail')
        const url = URL.createObjectURL(blob)
        created.add(url)
        thumbs[assetId] = url
        return url
      })
      .catch(() => urlFor(assetId))
      .finally(() => pending.delete(key))
    pending.set(key, task)
    return task
  }

  function release() {
    created.forEach(url => URL.revokeObjectURL(url))
    created.clear()
    Object.keys(state).forEach(key => delete state[key])
    Object.keys(thumbs).forEach(key => delete thumbs[key])
    pending.clear()
  }

  onBeforeUnmount(release)
  return { urls: state, thumbs, urlFor, thumbFor, release }
}

export function downloadBlob(url, filename) {
  if (!url) return
  const link = document.createElement('a')
  link.href = url
  link.download = filename || 'image.png'
  document.body.appendChild(link)
  link.click()
  link.remove()
}
