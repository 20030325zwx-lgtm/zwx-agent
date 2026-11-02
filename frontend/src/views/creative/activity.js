import { onBeforeUnmount, onMounted, ref } from 'vue'
import { getCreativeSummary } from '../../api'
import { ACTIVE_STATUSES } from './shared'

/**
 * 生成活动（redesign §3.1）：全局顶部活动入口的数据源。
 * 只展示服务端事实的聚合计数（§6：不假造百分比），轮询间隔放宽，失败静默保留上次值。
 */
export function useCreativeActivity(pollMs = 30_000) {
  const summary = ref(null)
  const activeCount = ref(0)
  const attentionCount = ref(0)
  let timer = null
  let stopped = false

  async function refresh() {
    try {
      const data = await getCreativeSummary()
      summary.value = data
      activeCount.value = data.active || 0
      attentionCount.value = (data.failed || 0) + (data.ingestFailed || 0) + (data.reconciling || 0)
    } catch (error) {
      // 服务端不可达时保留旧计数，入口不变灰也不假报 0
    }
  }

  onMounted(() => {
    refresh()
    timer = window.setInterval(() => {
      if (!stopped && document.visibilityState === 'visible') refresh()
    }, pollMs)
  })
  onBeforeUnmount(() => {
    stopped = true
    window.clearInterval(timer)
  })

  return { summary, activeCount, attentionCount, refresh }
}

export { ACTIVE_STATUSES }
