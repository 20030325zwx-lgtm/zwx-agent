<template>
  <div class="tab-scroll">
    <div class="tab-inner">
      <div class="cr-section-head">
        <h2>任务记录</h2>
        <span class="cr-field-help">{{ jobs.length }} 条</span>
      </div>
      <p v-if="!jobs.length" class="cr-empty">还没有任务。生成或批量生产后，执行记录会出现在这里。</p>
      <table v-else class="job-table">
        <thead>
          <tr><th>状态</th><th>提示词</th><th>创建时间</th><th>更新时间</th><th>操作</th></tr>
        </thead>
        <tbody>
          <tr v-for="job in jobs" :key="job.jobId">
            <td><span class="cr-status" :class="statusMeta(job.status).tone">{{ statusMeta(job.status).label }}</span></td>
            <td class="job-table-prompt">{{ job.prompt || '—' }}</td>
            <td>{{ formatTime(job.createdAt) }}</td>
            <td>{{ formatTime(job.updatedAt) }}</td>
            <td><button type="button" class="cr-ghost" @click="openJob(job.jobId)">详情</button></td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>
</template>

<script setup>
import { computed, inject } from 'vue'
import { formatTime, statusMeta } from '../shared'
import { PROJECT_CTX } from './composables'

const { jobsStore, openJob } = inject(PROJECT_CTX)
const jobs = computed(() => jobsStore.jobs.value)
</script>

<style scoped src="../creative.css"></style>

<style scoped>
.job-table {
  width: 100%;
  border-collapse: collapse;
  font-size: var(--wb-fs-compact);
  background: var(--wb-surface);
  border: 1px solid var(--wb-border);
  border-radius: var(--wb-radius);
  overflow: hidden;
}
.job-table th, .job-table td { padding: 10px 12px; text-align: left; border-bottom: 1px solid var(--wb-border); }
.job-table th {
  color: var(--wb-muted);
  font-weight: 500;
  background: var(--wb-surface-2);
  font-size: var(--wb-fs-compact);
}
.job-table tr:last-child td { border-bottom: none; }
.job-table-prompt { max-width: 420px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
</style>
