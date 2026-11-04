<template>
  <div v-if="comparison" class="card compare">
    <div class="compare-head">
      <div>
        <h3>对比报告</h3>
        <p class="muted">{{ leftTitle }} <span class="vs">vs</span> {{ rightTitle }}</p>
      </div>
      <div class="chips">
        <span class="chip">{{ summary.leftOnly }} 例 A 独有过</span>
        <span class="chip">{{ summary.rightOnly }} 例 B 独有过</span>
        <span class="chip">{{ summary.same }} 例一致</span>
      </div>
    </div>

    <div class="compare-grid">
      <div v-for="metric in metrics" :key="metric.label" class="compare-metric">
        <span>{{ metric.label }}</span>
        <div class="compare-values">
          <strong>{{ metric.left }}</strong>
          <em>→</em>
          <strong>{{ metric.right }}</strong>
        </div>
        <small :class="{ up: metric.better === 'right', down: metric.better === 'left' }">
          {{ metric.hint }}
        </small>
      </div>
    </div>

    <table>
      <thead>
        <tr><th>用例</th><th>A</th><th>B</th><th>差异</th><th>延迟 A / B</th><th>成本 A / B</th></tr>
      </thead>
      <tbody>
        <tr v-for="row in comparison.cases" :key="row.externalId">
          <td class="mono">{{ row.externalId }}</td>
          <td><span class="badge" :class="boolTone(row.leftPassed)">{{ boolLabel(row.leftPassed) }}</span></td>
          <td><span class="badge" :class="boolTone(row.rightPassed)">{{ boolLabel(row.rightPassed) }}</span></td>
          <td>
            <span v-if="row.delta === 'left_only'" class="badge danger">A 独有过</span>
            <span v-else-if="row.delta === 'right_only'" class="badge success">B 独有过</span>
            <span v-else-if="row.delta === 'same'" class="muted">一致</span>
            <span v-else class="muted">—</span>
          </td>
          <td class="mono">{{ row.leftLatencyMs ?? '—' }} / {{ row.rightLatencyMs ?? '—' }}</td>
          <td class="mono">{{ money(row.leftCost) }} / {{ money(row.rightCost) }}</td>
        </tr>
      </tbody>
    </table>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { boolLabel, boolTone, money, passRate } from '../format'

const props = defineProps({
  comparison: { type: Object, default: null },
  labels: { type: Object, default: () => ({ left: 'A', right: 'B' }) }
})

const leftTitle = computed(() => props.labels.left)
const rightTitle = computed(() => props.labels.right)
const summary = computed(() => props.comparison?.summary || { leftOnly: 0, rightOnly: 0, same: 0 })

function totalOf(side) {
  return props.comparison?.[side]?.totals || {}
}

const metrics = computed(() => {
  const left = totalOf('left')
  const right = totalOf('right')
  const rate = (totals) => (totals.passRate == null ? null : totals.passRate)
  const leftRate = rate(left)
  const rightRate = rate(right)
  return [
    {
      label: '通过率',
      left: passRate(left),
      right: passRate(right),
      hint: leftRate == null || rightRate == null ? '—'
        : rightRate > leftRate ? `B 高 ${Math.round((rightRate - leftRate) * 1000) / 10} 个点`
          : rightRate < leftRate ? `A 高 ${Math.round((leftRate - rightRate) * 1000) / 10} 个点` : '持平',
      better: rightRate == null || leftRate == null ? null : rightRate > leftRate ? 'right' : rightRate < leftRate ? 'left' : null
    },
    {
      label: 'judge 均分',
      left: left.judgeAvg ?? '—',
      right: right.judgeAvg ?? '—',
      hint: left.judgeAvg == null || right.judgeAvg == null ? '—'
        : `差 ${(right.judgeAvg - left.judgeAvg).toFixed(2)}`,
      better: left.judgeAvg == null || right.judgeAvg == null ? null : right.judgeAvg > left.judgeAvg ? 'right' : 'left'
    },
    {
      label: '总成本',
      left: money(left.totalCost),
      right: money(right.totalCost),
      hint: left.totalCost == null || right.totalCost == null ? '—'
        : right.totalCost < left.totalCost ? 'B 更省' : right.totalCost > left.totalCost ? 'A 更省' : '持平',
      better: left.totalCost == null || right.totalCost == null ? null
        : right.totalCost < left.totalCost ? 'right' : 'left'
    },
    {
      label: '平均延迟',
      left: left.avgLatencyMs == null ? '—' : `${left.avgLatencyMs} ms`,
      right: right.avgLatencyMs == null ? '—' : `${right.avgLatencyMs} ms`,
      hint: left.avgLatencyMs == null || right.avgLatencyMs == null ? '—'
        : `差 ${right.avgLatencyMs - left.avgLatencyMs} ms`,
      better: left.avgLatencyMs == null || right.avgLatencyMs == null ? null
        : right.avgLatencyMs < left.avgLatencyMs ? 'right' : 'left'
    }
  ]
})
</script>
