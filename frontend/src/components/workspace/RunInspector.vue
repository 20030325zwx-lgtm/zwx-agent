<template>
  <aside class="run-inspector" aria-label="任务上下文">
    <div class="inspector-tabs" role="tablist">
      <button v-for="tab in tabs" :key="tab.key" type="button" role="tab" :aria-selected="activeTab === tab.key"
        :class="{ active: activeTab === tab.key }" @click="activeTab = tab.key">
        {{ tab.label }}<em v-if="tab.count">{{ tab.count }}</em>
      </button>
      <button class="collapse-button" type="button" title="收起工作区面板" aria-label="收起工作区面板" @click="emit('collapse')">
        <PanelRightClose :size="14" />
      </button>
    </div>

    <div v-if="activeTab === 'sources'" class="tab-panel">
      <template v-if="sources.length">
        <article v-for="source in sources" :key="source.documentId || `${source.filename}-${source.chunkIndex}`" class="source-card">
          <header>
            <BookOpen :size="13" />
            <strong>{{ source.filename }}</strong>
            <em v-if="source.chunkIndex !== null && source.chunkIndex !== undefined">#{{ source.chunkIndex }}</em>
          </header>
          <p>{{ source.excerpt || '暂无切片摘录' }}</p>
          <small v-if="source.pageNumber">第 {{ source.pageNumber }} 页</small>
        </article>
        <button type="button" class="link-button" @click="emit('open-conversation')">在对话中查看完整引用</button>
      </template>
      <div v-else class="panel-empty">
        <BookOpen :size="18" />
        <span>本轮未使用知识库引用</span>
        <small>开启“检索知识库”后，召回切片会显示在这里。</small>
      </div>
    </div>

    <div v-else-if="activeTab === 'files'" class="tab-panel">
      <template v-if="files.length">
        <a v-for="file in files" :key="file.path" class="file-card" :href="file.url || undefined" target="_blank" rel="noopener noreferrer">
          <FileText :size="15" />
          <div>
            <strong>{{ fileName(file.path) }}</strong>
            <small>{{ formatSize(file.size) }}<template v-if="file.modifiedAt"> · {{ formatDate(file.modifiedAt) }}</template></small>
          </div>
          <Download :size="14" class="download" />
        </a>
      </template>
      <div v-else class="panel-empty">
        <FolderOpen :size="18" />
        <span>工作区还没有文件</span>
        <small>生成 PDF、写入文件后会自动出现在这里。</small>
      </div>
    </div>

    <div v-else-if="activeTab === 'usage'" class="tab-panel token-usage">
      <template v-if="usage">
        <dl class="usage-total">
          <div><dt>输入</dt><dd>{{ formatNumber(usage.promptTokens) }}</dd></div>
          <div><dt>输出</dt><dd>{{ formatNumber(usage.completionTokens) }}</dd></div>
          <div><dt>调用</dt><dd>{{ usage.calls || 0 }} 次</dd></div>
          <div><dt>知识上下文</dt><dd>{{ formatNumber(usage.ragContextChars) }} 字</dd></div>
        </dl>
        <article v-for="phase in usage.phases" :key="phase.phase" class="phase-card">
          <header>
            <strong>{{ phase.phase }}</strong>
            <em>{{ promptShare(phase) }}%</em>
          </header>
          <div class="phase-bar"><span :style="{ width: `${phaseShare(phase)}%` }" /></div>
          <small>
            输入 {{ formatNumber(phase.promptTokens) }} · 输出 {{ formatNumber(phase.completionTokens) }} · {{ phase.calls }} 次
          </small>
          <p>
            <span>工具结果 {{ formatNumber(phase.avgToolResultChars) }} 字（峰值 {{ formatNumber(phase.maxToolResultChars) }}）</span>
            <span>工具 {{ phase.maxToolCount }} 个 / {{ formatNumber(phase.avgToolSchemaChars) }} 字</span>
            <span>正文 {{ formatNumber(phase.avgMessageChars) }} 字 × {{ phase.avgMessageCount }} 条 · 系统词 {{ formatNumber(phase.avgSystemChars) }} 字</span>
          </p>
        </article>
      </template>
      <div v-else class="panel-empty">
        <Gauge :size="18" />
        <span>本轮还没有用量统计</span>
        <small>任务结束后，这里会显示每个阶段的输入/输出 Token 和提示词构成。</small>
      </div>
    </div>

    <div v-else-if="activeTab === 'tools'" class="tab-panel">
      <template v-if="toolCalls.length">
        <article v-for="(call, index) in toolCalls" :key="index" class="tool-call-card">
          <header>
            <Wrench :size="13" />
            <strong>{{ call.tool_name }}</strong>
            <em class="status-pill" :class="String(call.status || '').toLowerCase()">{{ call.status }}</em>
          </header>
          <p v-if="call.arguments" class="tool-args">{{ call.arguments }}</p>
          <small>{{ formatTime(call.created_at) }}<template v-if="call.result_preview"> · {{ call.result_preview }}</template></small>
        </article>
      </template>
      <div v-else class="panel-empty">
        <Wrench :size="18" />
        <span>本 run 还没有工具调用记录</span>
        <small>工具开始执行后，这里会显示幂等账本中的调用审计（工具、参数摘要、状态）。</small>
      </div>
    </div>

    <div v-else class="tab-panel run-meta">
      <template v-if="run">
        <dl>
          <div><dt>状态</dt><dd>{{ run.status || '—' }}</dd></div>
          <div><dt>完成节点</dt><dd>{{ run.lastNode || '—' }}</dd></div>
          <div><dt>下一节点</dt><dd>{{ run.nextNode || '—' }}</dd></div>
          <div><dt>恢复次数</dt><dd>{{ run.resumeCount || 0 }}</dd></div>
          <div><dt>验收</dt><dd>{{ verificationLabel }}</dd></div>
          <div class="run-id"><dt>Run ID</dt><dd :title="run.id">{{ run.id || '—' }}</dd></div>
        </dl>
        <p v-if="run.verification?.feedback" class="feedback">{{ run.verification.feedback }}</p>
        <p v-if="run.errorMessage" class="feedback error">{{ run.errorMessage }}</p>
        <div class="meta-actions">
          <button v-if="run.canResume" type="button" class="primary" @click="emit('resume')">继续任务</button>
          <button v-if="run.canCancel" type="button" class="danger" @click="emit('cancel')">停止任务</button>
        </div>
      </template>
      <div v-else class="panel-empty">
        <Activity :size="18" />
        <span>还没有运行记录</span>
        <small>发送任务后，这里会显示运行状态和恢复入口。</small>
      </div>
    </div>
  </aside>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import { Activity, BookOpen, Download, FileText, FolderOpen, Gauge, PanelRightClose, Wrench } from 'lucide-vue-next'
import { listManusToolCalls } from '../../api'

const props = defineProps({
  sources: { type: Array, default: () => [] },
  files: { type: Array, default: () => [] },
  run: { type: Object, default: null },
  conversationId: { type: String, default: '' }
})
const emit = defineEmits(['resume', 'cancel', 'open-conversation', 'collapse'])

const activeTab = ref('sources')
const toolCalls = ref([])
const toolCallsLoadedFor = ref('')
const toolCallsLoading = ref(false)
const tabs = computed(() => [
  { key: 'sources', label: '来源', count: props.sources.length },
  { key: 'files', label: '文件', count: props.files.length },
  { key: 'usage', label: '用量', count: props.run?.tokenUsage?.phases?.length || 0 },
  { key: 'tools', label: '工具', count: toolCalls.value.length },
  { key: 'run', label: '运行' }
])

const loadToolCalls = async () => {
  const runId = props.run?.id
  const conversationId = props.conversationId
  if (!runId || !conversationId || toolCallsLoading.value) return
  if (toolCallsLoadedFor.value === runId) return
  toolCallsLoading.value = true
  try {
    toolCalls.value = await listManusToolCalls(conversationId, runId)
    toolCallsLoadedFor.value = runId
  } catch (error) {
    console.error('Unable to load tool call audit:', error)
  } finally {
    toolCallsLoading.value = false
  }
}
watch(() => [activeTab.value, props.run?.id], ([tab]) => {
  if (tab === 'tools') loadToolCalls()
})
watch(() => props.run?.id, () => {
  toolCalls.value = []
  toolCallsLoadedFor.value = ''
})

const usage = computed(() => props.run?.tokenUsage || null)
const topPhaseTokens = computed(() => Math.max(1, ...(usage.value?.phases || []).map(phase => phase.promptTokens || 0)))
const totalPromptTokens = computed(() => (usage.value?.phases || [])
  .reduce((sum, phase) => sum + (phase.promptTokens || 0), 0) || usage.value?.promptTokens || 0)
const phaseShare = phase => Math.round(((phase.promptTokens || 0) / topPhaseTokens.value) * 100)
const promptShare = phase => totalPromptTokens.value
  ? Math.round(((phase.promptTokens || 0) / totalPromptTokens.value) * 100)
  : 0

const verificationLabel = computed(() => ({
  pass: '已验收',
  unverified: '未验收',
  revise: '已要求返工'
}[props.run?.verification?.status] || '—'))

const fileName = path => (path || '').split('/').pop()
const formatNumber = value => (Number.isFinite(value) ? value : 0).toLocaleString('zh-CN')
const formatSize = size => {
  if (!Number.isFinite(size) || size <= 0) return '0 B'
  if (size < 1024) return `${size} B`
  if (size < 1024 * 1024) return `${(size / 1024).toFixed(1)} KB`
  return `${(size / 1024 / 1024).toFixed(1)} MB`
}
const formatDate = value => {
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return ''
  return date.toLocaleDateString('zh-CN', { month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit' })
}
const formatTime = value => {
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) return ''
  return date.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit', second: '2-digit' })
}
</script>

<style scoped>
.run-inspector {
  display: flex;
  height: 100%;
  min-height: 0;
  flex-direction: column;
  gap: 10px;
  border-left: 1px solid var(--sk-separator);
  padding-left: 16px;
}

.inspector-tabs { display: flex; gap: 3px; border-radius: 10px; padding: 3px; background: var(--sk-fill); }
.inspector-tabs button {
  display: inline-flex;
  flex: 1;
  align-items: center;
  justify-content: center;
  gap: 4px;
  border: 0;
  border-radius: 8px;
  padding: 6px 5px;
  background: transparent;
  color: var(--sk-label-2);
  font-size: 11.5px;
  font-weight: 600;
  cursor: pointer;
}
.inspector-tabs button.active { background: #fff; color: var(--zwx-primary); box-shadow: 0 1px 3px rgba(37, 73, 133, 0.12); }
.inspector-tabs em { border-radius: 999px; padding: 0 5px; background: var(--zwx-primary-soft); color: var(--zwx-primary); font-size: 9px; font-style: normal; }
.inspector-tabs .collapse-button { flex: 0 0 28px; padding: 0; color: var(--sk-label-3); }
.inspector-tabs .collapse-button:hover { color: var(--zwx-primary); }

.tab-panel { display: grid; gap: 8px; min-height: 0; overflow-y: auto; padding-right: 2px; }

.source-card, .file-card { border: 1px solid var(--sk-separator); border-radius: 11px; padding: 9px 10px; background: #fff; text-decoration: none; }
.source-card header { display: flex; align-items: center; gap: 5px; color: var(--zwx-primary); }
.source-card strong { overflow: hidden; font-size: 11.5px; text-overflow: ellipsis; white-space: nowrap; }
.source-card em { color: var(--sk-label-3); font-size: 10px; font-style: normal; }
.source-card p { display: -webkit-box; overflow: hidden; margin: 5px 0 0; color: var(--sk-label-2); font-size: 11px; line-height: 1.55; -webkit-box-orient: vertical; -webkit-line-clamp: 3; }
.source-card small { display: block; margin-top: 4px; color: var(--sk-label-3); font-size: 10px; }

.file-card { display: flex; align-items: center; gap: 8px; color: var(--zwx-primary); }
.file-card:hover { border-color: var(--zwx-primary-ring); background: var(--zwx-primary-soft); }
.file-card > div { display: grid; min-width: 0; gap: 2px; }
.file-card strong { overflow: hidden; color: var(--sk-label); font-size: 11.5px; text-overflow: ellipsis; white-space: nowrap; }
.file-card small { color: var(--sk-label-3); font-size: 10px; }
.file-card .download { margin-left: auto; flex: 0 0 auto; }

.tool-call-card { border: 1px solid var(--sk-separator); border-radius: 11px; padding: 8px 10px; background: #fff; }
.tool-call-card header { display: flex; align-items: center; gap: 5px; color: var(--zwx-primary); }
.tool-call-card strong { overflow: hidden; color: var(--sk-label); font-size: 11px; text-overflow: ellipsis; white-space: nowrap; }
.tool-call-card .status-pill { flex: 0 0 auto; margin-left: auto; border-radius: 999px; padding: 1px 7px; background: var(--sk-fill); color: var(--sk-label-2); font-size: 9px; font-style: normal; }
.tool-call-card .status-pill.succeeded { background: rgba(52, 199, 89, 0.12); color: #1d7a3d; }
.tool-call-card .status-pill.failed, .tool-call-card .status-pill.unknown { background: rgba(239, 68, 68, 0.1); color: #b91c1c; }
.tool-call-card .tool-args { display: -webkit-box; overflow: hidden; margin: 5px 0 0; color: var(--sk-label-2); font: 9.5px ui-monospace, "SF Mono", Menlo, monospace; line-height: 1.5; word-break: break-all; -webkit-box-orient: vertical; -webkit-line-clamp: 3; }
.tool-call-card small { display: block; margin-top: 4px; overflow: hidden; color: var(--sk-label-3); font-size: 10px; text-overflow: ellipsis; white-space: nowrap; }

.link-button { justify-self: start; border: 0; padding: 2px 0; background: transparent; color: var(--zwx-primary); font-size: 11px; cursor: pointer; }

.panel-empty { display: grid; justify-items: center; gap: 5px; border: 1px dashed var(--sk-separator); border-radius: 12px; padding: 24px 12px; color: var(--sk-label-3); text-align: center; }
.panel-empty span { color: var(--sk-label-2); font-size: 11.5px; font-weight: 600; }
.panel-empty small { font-size: 10.5px; line-height: 1.5; }

.token-usage dl { display: grid; grid-template-columns: 1fr 1fr; gap: 0; margin: 0; border: 1px solid var(--sk-separator); border-radius: 11px; padding: 3px 10px; background: #fff; }
.token-usage dl > div { display: flex; justify-content: space-between; gap: 8px; padding: 6px 0; }
.token-usage dt { color: var(--sk-label-3); font-size: 10.5px; }
.token-usage dd { overflow: hidden; margin: 0; color: var(--sk-label); font-size: 10.5px; text-overflow: ellipsis; white-space: nowrap; }

.phase-card { border: 1px solid var(--sk-separator); border-radius: 11px; padding: 9px 10px; background: #fff; }
.phase-card header { display: flex; justify-content: space-between; gap: 8px; }
.phase-card strong { overflow: hidden; color: var(--sk-label); font-size: 11.5px; text-overflow: ellipsis; white-space: nowrap; }
.phase-card em { flex: 0 0 auto; color: var(--zwx-primary); font-size: 10.5px; font-style: normal; font-weight: 650; }
.phase-bar { height: 4px; border-radius: 999px; margin: 7px 0 6px; background: var(--sk-fill); overflow: hidden; }
.phase-bar span { display: block; height: 100%; border-radius: 999px; background: var(--zwx-primary); }
.phase-card small { display: block; color: var(--sk-label-2); font-size: 10.5px; }
.phase-card p { display: grid; gap: 2px; margin: 5px 0 0; color: var(--sk-label-3); font-size: 10px; line-height: 1.5; }

.run-meta dl { display: grid; gap: 0; margin: 0; border: 1px solid var(--sk-separator); border-radius: 11px; padding: 3px 10px; background: #fff; }
.run-meta dl > div { display: flex; justify-content: space-between; gap: 12px; padding: 6px 0; }
.run-meta dl > div + div { border-top: 1px dashed var(--sk-separator); }
.run-meta dt { color: var(--sk-label-3); font-size: 10.5px; }
.run-meta dd { overflow: hidden; margin: 0; color: var(--sk-label); font-size: 10.5px; text-overflow: ellipsis; white-space: nowrap; }
.run-meta .run-id dd { max-width: 150px; font: 9.5px ui-monospace, "SF Mono", Menlo, monospace; }

.feedback { margin: 0; border-radius: 9px; padding: 8px 10px; background: var(--sk-fill); color: var(--sk-label-2); font-size: 10.5px; line-height: 1.55; }
.feedback.error { background: rgba(239, 68, 68, 0.08); color: #b91c1c; }

.meta-actions { display: flex; gap: 7px; }
.meta-actions button { flex: 1; border: 0; border-radius: 9px; padding: 7px 10px; font-size: 11px; font-weight: 650; cursor: pointer; }
.meta-actions .primary { background: var(--zwx-primary); color: #fff; }
.meta-actions .danger { background: rgba(255, 59, 48, 0.08); color: var(--sk-red); }
</style>
