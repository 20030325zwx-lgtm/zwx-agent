<template>
  <section class="deliverable" aria-label="交付结果">
    <header class="deliverable-head">
      <div>
        <span class="deliverable-kicker">交付结果</span>
        <strong>{{ headline }}</strong>
      </div>
      <div class="head-badges">
        <span v-if="verification.status === 'unverified'" class="badge warn">未验收</span>
        <span v-else-if="verification.status === 'pass'" class="badge pass">已验收</span>
        <span v-if="files.length" class="badge files">{{ files.length }} 个文件</span>
      </div>
    </header>

    <div v-if="answer" class="deliverable-paper">
      <div class="markdown-body" v-html="renderedAnswer"></div>
    </div>
    <div v-else-if="status === 'RUNNING'" class="deliverable-placeholder">
      <span class="typing"><i></i><i></i><i></i></span>
      正在生成交付内容…
    </div>
    <div v-else class="deliverable-placeholder">本轮还没有生成回答。</div>

    <div v-if="files.length" class="artifact-list">
      <a v-for="file in files" :key="file.path" class="artifact" :href="file.url || undefined" target="_blank" rel="noopener noreferrer">
        <FileText :size="16" />
        <span>{{ file.name }}</span>
        <small>{{ file.type === 'pdf' ? 'PDF' : '文件' }}</small>
      </a>
    </div>

    <div v-if="citations.length" class="citation-strip">
      <div class="citation-title"><BookOpen :size="14" /><span>引用资料 · {{ citations.length }} 个切片</span></div>
      <article v-for="citation in citations" :key="citation.documentId || `${citation.filename}-${citation.chunkIndex}`" class="citation">
        <strong>{{ citation.filename }}<em v-if="citation.chunkIndex !== null && citation.chunkIndex !== undefined">#{{ citation.chunkIndex }}</em></strong>
        <p>{{ citation.excerpt }}</p>
      </article>
      <button type="button" class="link-button" @click="emit('open-conversation')">在对话中查看完整引用</button>
    </div>

    <div v-if="canResume" class="resume-card">
      <div>
        <strong>任务未完成</strong>
        <span>已保留最近检查点，可以从上次停止的节点继续。</span>
      </div>
      <button type="button" @click="emit('resume')">继续任务</button>
    </div>
  </section>
</template>

<script setup>
import { computed } from 'vue'
import DOMPurify from 'dompurify'
import { marked } from 'marked'
import { BookOpen, FileText } from 'lucide-vue-next'

const props = defineProps({
  answer: { type: String, default: '' },
  files: { type: Array, default: () => [] },
  ragTrace: { type: Object, default: null },
  verification: { type: Object, default: () => ({ status: '', feedback: '' }) },
  status: { type: String, default: '' },
  canResume: { type: Boolean, default: false }
})
const emit = defineEmits(['open-conversation', 'resume'])

const stripInlineCitations = content => (content || '')
  .replace(/\[([^\[\]\n]+?)#(\d+)\]/g, '')
  .replace(/[ \t]+(?=[。，；：！？、.,;:!?])/g, '')
  .replace(/[ \t]{2,}/g, ' ')

const renderedAnswer = computed(() => DOMPurify.sanitize(marked.parse(stripInlineCitations(props.answer), {
  breaks: true,
  gfm: true
})))

const citations = computed(() => {
  const candidates = props.ragTrace?.candidates || []
  const selected = candidates.filter(candidate => candidate.selected)
  return (selected.length ? selected : candidates).slice(0, 4)
})

const headline = computed(() => {
  if (props.status === 'RUNNING') return '正在整理结果'
  if (props.status === 'INTERRUPTED') return '已保留部分结果'
  if (props.status === 'FAILED') return '执行未完成'
  return '本轮任务结果'
})
</script>

<style scoped>
.deliverable { display: grid; gap: 12px; }

.deliverable-head { display: flex; align-items: flex-end; justify-content: space-between; gap: 12px; }
.deliverable-head > div:first-child { display: grid; gap: 3px; }
.deliverable-kicker { color: var(--sk-label-3); font-size: 10.5px; font-weight: 650; letter-spacing: 0.08em; }
.deliverable-head strong { color: var(--sk-label); font-size: 14px; }

.head-badges { display: flex; flex-wrap: wrap; gap: 6px; }
.badge { border-radius: 999px; padding: 3px 8px; font-size: 10px; font-weight: 650; }
.badge.pass { background: rgba(34, 197, 94, 0.12); color: #15803d; }
.badge.warn { background: rgba(245, 158, 11, 0.14); color: #b45309; }
.badge.files { background: var(--zwx-primary-soft); color: var(--zwx-primary); }

.deliverable-paper {
  border: 1px solid var(--sk-separator);
  border-radius: 16px;
  padding: 20px 22px;
  background: #fff;
  box-shadow: 0 8px 28px rgba(37, 73, 133, 0.07);
}

.markdown-body { color: var(--sk-label); font-size: 15px; line-height: 1.75; }
.markdown-body :deep(p) { margin: 0 0 13px; }
.markdown-body :deep(p:last-child) { margin-bottom: 0; }
.markdown-body :deep(h1), .markdown-body :deep(h2), .markdown-body :deep(h3) { margin: 20px 0 9px; font-weight: 700; line-height: 1.45; }
.markdown-body :deep(h1) { font-size: 21px; }
.markdown-body :deep(h2) { font-size: 18px; }
.markdown-body :deep(h3) { font-size: 16px; }
.markdown-body :deep(ul), .markdown-body :deep(ol) { margin: 0 0 13px; padding-left: 1.4em; }
.markdown-body :deep(li) { margin: 4px 0; }
.markdown-body :deep(code) { border-radius: 5px; padding: 2px 5px; background: var(--sk-fill); font: 0.86em ui-monospace, "SF Mono", Menlo, monospace; }
.markdown-body :deep(pre) { overflow-x: auto; margin: 13px 0; border-radius: 11px; padding: 13px; background: #1d1d26; color: #f2f2f7; font-size: 12.5px; }
.markdown-body :deep(pre code) { padding: 0; background: transparent; color: inherit; }
.markdown-body :deep(blockquote) { margin: 12px 0; border-left: 3px solid var(--zwx-primary-ring); padding: 2px 0 2px 12px; color: var(--sk-label-2); }
.markdown-body :deep(a) { color: var(--zwx-primary); }

.deliverable-placeholder { border: 1px dashed var(--sk-separator); border-radius: 14px; padding: 22px; color: var(--sk-label-3); font-size: 12.5px; text-align: center; }
.typing { display: inline-flex; gap: 4px; margin-right: 7px; vertical-align: middle; }
.typing i { width: 5px; height: 5px; border-radius: 50%; background: var(--zwx-primary); animation: typing 1.1s ease-in-out infinite; }
.typing i:nth-child(2) { animation-delay: 0.15s; }
.typing i:nth-child(3) { animation-delay: 0.3s; }
@keyframes typing { 0%, 100% { opacity: 0.25; transform: translateY(0); } 50% { opacity: 1; transform: translateY(-3px); } }

.artifact-list { display: flex; flex-wrap: wrap; gap: 8px; }
.artifact { display: inline-flex; max-width: 100%; align-items: center; gap: 7px; border: 1px solid var(--sk-separator); border-radius: 11px; padding: 7px 10px; background: #fff; color: var(--zwx-primary); font-size: 12px; text-decoration: none; }
.artifact:hover { border-color: var(--zwx-primary-ring); background: var(--zwx-primary-soft); }
.artifact span { overflow: hidden; max-width: 260px; text-overflow: ellipsis; white-space: nowrap; }
.artifact small { border-radius: 5px; padding: 1px 5px; background: var(--zwx-primary-soft); font-size: 9.5px; font-weight: 650; }

.citation-strip { display: grid; gap: 8px; border-top: 1px dashed var(--sk-separator); padding-top: 12px; }
.citation-title { display: flex; align-items: center; gap: 6px; color: var(--sk-label-2); font-size: 11.5px; }
.citation { border: 1px solid var(--sk-separator); border-radius: 11px; padding: 8px 10px; background: #fff; }
.citation strong { display: block; color: var(--zwx-primary); font-size: 11.5px; }
.citation strong em { color: var(--sk-label-3); font-style: normal; font-weight: 500; }
.citation p { display: -webkit-box; overflow: hidden; margin: 3px 0 0; color: var(--sk-label-2); font-size: 11px; line-height: 1.55; -webkit-box-orient: vertical; -webkit-line-clamp: 2; }
.link-button { justify-self: start; border: 0; padding: 0; background: transparent; color: var(--zwx-primary); font-size: 11.5px; cursor: pointer; }

.resume-card { display: flex; align-items: center; justify-content: space-between; gap: 14px; border: 1px solid rgba(245, 158, 11, 0.3); border-radius: 13px; padding: 12px 14px; background: rgba(255, 251, 235, 0.9); }
.resume-card > div { display: grid; gap: 2px; }
.resume-card strong { color: #92400e; font-size: 12.5px; }
.resume-card span { color: #a16207; font-size: 11.5px; }
.resume-card button { border: 0; border-radius: 999px; padding: 6px 12px; background: #f59e0b; color: #fff; font-size: 11.5px; font-weight: 650; cursor: pointer; }

@media (max-width: 720px) {
  .deliverable-paper { padding: 15px 16px; }
  .deliverable-head { align-items: flex-start; flex-direction: column; gap: 6px; }
  .resume-card { align-items: flex-start; flex-direction: column; }
}

@media (prefers-reduced-motion: reduce) { .typing i { animation: none; opacity: 0.7; } }
</style>
