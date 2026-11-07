<template>
  <!-- 制作蓝图节点：只读展示五阶段编排（画风/故事/镜头/成片），只有执行端口 -->
  <article v-if="type === 'pipeline_stage'" class="pipeline-card" :class="[{ active: selected }, 'stage-' + data.kind]"
           :aria-label="data.label">
    <Handle v-if="data.kind !== 'STYLE'" id="flow-in" type="target" :position="Position.Left" class="port port-flow" :style="{ top: '50%' }" aria-label="执行输入" />
    <Handle id="flow-out" type="source" :position="Position.Right" class="port port-flow" :style="{ top: '50%' }" aria-label="执行输出" />
    <header class="pl-head">
      <span class="pl-mark"><component :is="plIcon" :size="14" /></span>
      <small>{{ plStageName }}</small>
      <span v-if="plStatus" class="pl-status" :class="plStatus.tone">{{ plStatus.label }}</span>
    </header>
    <h3 class="pl-title">{{ data.label }}</h3>

    <div v-if="data.kind === 'STYLE'" class="pl-style">
      <div class="pl-swatch" :style="{ background: swatchGradient }" />
      <p class="pl-note">{{ data.text || '未指定画风，生成时保持自由发挥' }}</p>
    </div>

    <p v-else-if="data.kind === 'STORY'" class="pl-story">{{ data.text || '还没有写故事' }}</p>

    <template v-else-if="data.kind === 'SHOT'">
      <div v-if="plPreviewUrl" class="pl-media">
        <video :src="plPreviewUrl" muted preload="metadata" class="nodrag nopan" @mouseenter="$event.target.play().catch(() => {})" @mouseleave="$event.target.pause()"></video>
      </div>
      <p v-else class="pl-story pl-prompt">{{ data.text || '还没有提示词' }}</p>
      <div class="pl-meta">
        <span v-if="data.durationSeconds">目标 {{ data.durationSeconds }}s</span>
        <span v-if="data.assetId">已选片段</span>
      </div>
    </template>

    <template v-else>
      <div v-if="plPreviewUrl" class="pl-media">
        <video :src="plPreviewUrl" controls preload="metadata" class="nodrag nopan"></video>
      </div>
      <p v-else class="pl-story">完成选片并导出后，成片会在这里播放</p>
      <div class="pl-meta">
        <span v-if="data.clipCount !== undefined">{{ data.clipCount }} 个片段</span>
        <span v-if="data.durationSeconds">{{ data.durationSeconds }}s</span>
      </div>
    </template>
    <footer class="pl-foot">{{ data.footer || '' }}</footer>
  </article>

  <article v-else class="graph-card" :class="[{ active: selected }, 'kind-' + type]" :aria-label="data.label">
    <header class="card-head">
      <span class="type-mark"><component :is="typeIcon" :size="16" /></span>
      <div><small>{{ typeLabel }}</small><strong>{{ data.label || typeLabel }}</strong></div>
      <span v-if="data.status && data.status !== 'IDLE'" class="state-badge" :class="{ failed: data.status === 'FAILED' }">{{ statusLabel }}</span>
    </header>
    <div class="execution-row"><span v-if="type !== 'start'">等待全部上级</span><span v-else>流程起点</span><span>执行</span></div>
    <Handle v-if="type !== 'start'" id="flow-in" type="target" :position="Position.Left" class="port port-flow" :style="{ top: '76px' }" :aria-label="data.label + ' 执行输入'" />
    <Handle id="flow-out" type="source" :position="Position.Right" class="port port-flow" :style="{ top: '76px' }" :aria-label="data.label + ' 执行输出'" />
    <template v-if="type !== 'start'">
    <div class="port-row">
      <span v-if="generation">提示词输入</span><span v-else>{{ type === 'text_input' ? '文本内容' : '项目素材' }}</span>
      <span v-if="type !== 'asset_input'">提示词输出</span><span v-else>素材输出</span>
    </div>
    <Handle v-if="generation" id="text-in" type="target" :position="Position.Left" class="port port-text" :style="{ top: '106px' }" :aria-label="data.label + ' 提示词输入'" />
    <Handle v-if="type !== 'asset_input'" id="text-out" type="source" :position="Position.Right" class="port port-text" :style="{ top: '106px' }" :aria-label="data.label + ' 提示词输出'" />
    <Handle v-else id="media-out" type="source" :position="Position.Right" class="port port-media" :style="{ top: '106px' }" :aria-label="data.label + ' 素材输出'" />
    <div v-if="generation" class="port-row media-row"><span>{{ type === 'video_generate' ? '首帧输入' : '参考图输入' }}</span><span>{{ type === 'video_generate' ? '视频结果' : '图片输出' }}</span></div>
    <Handle v-if="generation" id="image-in" type="target" :position="Position.Left" class="port port-media" :style="{ top: '136px' }" :aria-label="data.label + ' 图片输入'" />
    <Handle v-if="generation && type !== 'video_generate'" id="media-out" type="source" :position="Position.Right" class="port port-media" :style="{ top: '136px' }" :aria-label="data.label + ' 图片输出'" />
    </template>
    <p class="card-prompt">{{ summary }}</p>
    <div v-if="generation || type === 'asset_input'" class="card-preview">
      <img v-if="previewUrl && mediaType !== 'VIDEO'" :src="previewUrl" alt="节点素材预览" />
      <video v-else-if="previewUrl && mediaType === 'VIDEO'" :src="previewUrl" controls preload="metadata" class="nodrag nopan" />
      <template v-else><component :is="type === 'video_generate' ? Clapperboard : Image" :size="25" :stroke-width="1.3" /><span>{{ data.selectedAssetId ? '结果已保存' : type === 'asset_input' ? '选择或上传参考素材' : '生成结果将在这里展示' }}</span></template>
    </div>
    <footer>
      <span v-if="generation">{{ presentation?.modelName(data.modelRowId) || '尚未选择模型' }}</span>
      <span v-else>{{ type === 'start' ? '不调用模型 · 不产生素材' : type === 'asset_input' ? (data.assetIds || []).length + ' 个素材' : '连接到下一节点的提示词输入' }}</span>
      <span v-if="data.candidates?.length" class="candidate-count">{{ data.candidates.length }} 个候选</span>
    </footer>
  </article>
</template>

<script setup>
import { computed, inject } from 'vue'
import { Handle, Position } from '@vue-flow/core'
import { Clapperboard, FileText, Film, Image, ImagePlus, Palette, ScrollText, WandSparkles, Play } from 'lucide-vue-next'
const props = defineProps({ id: String, type: String, data: Object, selected: Boolean })
const presentation = inject('canvasPresentation', null)
const generation = computed(() => ['image_generate', 'image_edit', 'video_generate'].includes(props.type))

// —— 制作蓝图节点（pipeline_stage）——
const plIcon = computed(() => ({ STYLE: Palette, STORY: ScrollText, SHOT: Film, FINAL_CUT: Clapperboard })[props.data?.kind] || Film)
const plStageName = computed(() => ({ STYLE: '阶段 1 · 画风', STORY: '阶段 2 · 故事', SHOT: '阶段 3/4 · 镜头', FINAL_CUT: '阶段 5 · 成片' })[props.data?.kind] || '制作蓝图')
const plStatus = computed(() => ({
  DRAFT: { label: '草稿', tone: 'idle' }, READY: { label: '待生成', tone: 'idle' },
  GENERATING: { label: '生成中', tone: 'working' }, PENDING_SELECTION: { label: '待选片', tone: 'working' },
  SELECTED: { label: '已选片', tone: 'done' }
})[props.data?.shotStatus] || null)
const swatchGradient = computed(() => {
  const [from, to] = props.data?.accent || ['#6654c7', '#9a80cc']
  return `linear-gradient(135deg, ${from}, ${to})`
})
const plPreviewUrl = computed(() => presentation?.assetUrl(props.data?.assetId) || '')
const typeLabel = computed(() => ({ start: '流程起点', asset_input: '素材', text_input: '文本', image_generate: '图片生成', image_edit: '图片编辑', video_generate: '视频生成' })[props.type])
const typeIcon = computed(() => ({ start: Play, asset_input: ImagePlus, text_input: FileText, image_generate: Image, image_edit: WandSparkles, video_generate: Clapperboard })[props.type])
const summary = computed(() => props.type === 'start' ? '从这里开始。下游节点将在全部上级完成后依次执行。' : props.type === 'text_input' ? props.data.text || '写下要传给下游的内容…'
  : props.type === 'asset_input' ? (props.data.assetIds || []).map(id => presentation?.assetName(id) || id).join('、') || '图片、视频均可保存到节点'
  : presentation?.prompt(props.id) || '选中节点，在左侧填写提示词…')
const previewId = computed(() => props.data.selectedAssetId || props.data.assetIds?.[0])
const previewUrl = computed(() => presentation?.assetUrl(previewId.value) || '')
const mediaType = computed(() => props.type === 'video_generate' ? 'VIDEO' : presentation?.assetType(previewId.value))
const statusLabel = computed(() => ({ PENDING: '等待', QUEUED: '排队', RUNNING: '生成中', WAITING_EXTERNAL: '生成中', WAITING_SELECTION: '待选片', SUCCEEDED: '已完成', FAILED: '失败', SKIPPED: '跳过', CANCELLED: '已取消' })[props.data.status] || props.data.status)
</script>

<style scoped>
/* —— 制作蓝图节点 —— */
.pipeline-card { width: 236px; position: relative; box-sizing: border-box; border: 1px solid var(--wb-border, #e3e6ed); border-radius: 12px; background: var(--wb-surface, #fff); color: var(--wb-text, #232735); box-shadow: 0 3px 12px rgb(25 35 60 / 5%); overflow: hidden; }
.pipeline-card.active { border-color: #6674e8; box-shadow: 0 0 0 3px rgb(102 116 232 / 10%), 0 6px 20px rgb(25 35 60 / 7%); }
.pl-head { display: flex; align-items: center; gap: 7px; padding: 10px 12px 0; }
.pl-mark { display: grid; place-items: center; width: 24px; height: 24px; border-radius: 7px; background: #eef0ff; color: #6167c7; }
.stage-SHOT .pl-mark, .stage-FINAL_CUT .pl-mark { background: #fff2e7; color: #b9773e; }
.stage-STORY .pl-mark { background: #f0edf9; color: #8970ba; }
.pl-head small { flex: 1; font-size: 9px; color: var(--wb-muted, #7b8190); }
.pl-status { font-size: 9px; padding: 2px 7px; border-radius: 999px; white-space: nowrap; }
.pl-status.idle { background: var(--wb-bg, #f1f2f6); color: var(--wb-muted, #7b8190); }
.pl-status.working { background: #fdf1de; color: #a35914; }
.pl-status.done { background: #e8f4ec; color: #18794e; }
.pl-title { margin: 7px 12px 0; font-size: 13px; font-weight: 650; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.pl-style { padding: 0 12px 10px; }
.pl-swatch { height: 44px; border-radius: 8px; margin-top: 9px; }
.pl-note { margin: 8px 0 0; font-size: 10px; line-height: 1.55; color: var(--wb-text-2, #646c7c); display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; }
.pl-story { margin: 9px 12px 0; font-size: 10px; line-height: 1.65; color: var(--wb-text-2, #646c7c); display: -webkit-box; -webkit-line-clamp: 4; -webkit-box-orient: vertical; overflow: hidden; min-height: 40px; white-space: pre-wrap; }
.pl-prompt { -webkit-line-clamp: 3; }
.pl-media { margin: 9px 12px 0; height: 108px; border-radius: 8px; background: var(--cr-media-stage, #101113); overflow: hidden; }
.pl-media video { width: 100%; height: 100%; object-fit: contain; display: block; }
.pl-meta { display: flex; justify-content: space-between; gap: 8px; margin: 7px 12px 0; font-size: 9px; color: var(--wb-muted, #8d95a3); }
.pl-meta :only-child { margin-left: auto; }
.pl-foot { margin-top: 9px; padding: 7px 12px; border-top: 1px dashed var(--wb-border, #e8eaf0); color: var(--wb-muted, #9da5b3); font-size: 9px; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }

.graph-card { width: 268px; position: relative; box-sizing: border-box; border: 1px solid var(--wb-border, #e3e6ed); border-radius: 12px; background: var(--wb-surface, #fff); color: var(--wb-text, #232735); box-shadow: 0 3px 12px rgb(25 35 60 / 5%); }
.graph-card.active { border-color: #6674e8; box-shadow: 0 0 0 3px rgb(102 116 232 / 10%), 0 6px 20px rgb(25 35 60 / 7%); }
.graph-card.kind-start { width: 180px; }
.card-head { display: flex; align-items: center; gap: 9px; padding: 13px 14px; height: 62px; box-sizing: border-box; border-bottom: 1px solid var(--wb-border); }
.card-head div { flex: 1; min-width: 0; }
.card-head small { display: block; font-size: 9px; color: var(--wb-muted, #7b8190); margin-bottom: 2px; }
.card-head strong { display: block; font-size: 12px; font-weight: 600; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.type-mark { display: grid; place-items: center; width: 30px; height: 30px; border-radius: 8px; background: #eef0ff; color: #6167c7; }
.kind-video_generate .type-mark { background: #fff2e7; color: #b9773e; }
.kind-asset_input .type-mark { background: #eaf7f1; color: #3f9375; }
.kind-text_input .type-mark { background: #f0edf9; color: #8970ba; }
.state-badge { font-size: 9px; color: var(--wb-muted); white-space: nowrap; }
.state-badge.failed { color: #cd4b61; }
.port-row { height: 30px; padding: 0 14px; display: flex; justify-content: space-between; align-items: center; color: #8d80ad; font-size: 9px; }
.port-row:first-of-type { margin-top: 5px; }
.execution-row { height: 29px; padding: 0 14px; display: flex; align-items: center; justify-content: space-between; font-size: 9px; color: #6877bd; }
.port-flow { color: #6877bd; background: #6877bd; }
.kind-start .type-mark { background: #edf1fc; color: #6877bd; }
.media-row { color: #599285; }
.port.vue-flow__handle { width: 12px; height: 12px; min-width: 12px; min-height: 12px; border: 3px solid var(--wb-surface, #fff); border-radius: 50%; box-shadow: 0 0 0 1px currentColor; cursor: crosshair; z-index: 4; }
.port-text { color: #9a80cc; background: #9a80cc; }
.port-media { color: #56a793; background: #56a793; }
.port:hover { box-shadow: 0 0 0 5px rgb(102 116 232 / 15%); }
.card-prompt { margin: 5px 14px 12px; font-size: 11px; line-height: 1.65; color: var(--wb-text-2, #646c7c); display: -webkit-box; -webkit-line-clamp: 3; -webkit-box-orient: vertical; overflow: hidden; min-height: 36px; white-space: pre-wrap; }
.card-preview { margin: 0 12px 12px; height: 116px; border-radius: 7px; background: var(--wb-bg, #f6f7fa); border: 1px dashed var(--wb-border, #e4e7ef); display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 9px; color: var(--wb-muted, #9da5b3); font-size: 10px; overflow: hidden; }
.card-preview img,.card-preview video { width: 100%; height: 100%; object-fit: contain; }
footer { display: flex; align-items: center; justify-content: space-between; gap: 6px; padding: 10px 14px; border-top: 1px solid var(--wb-border); color: var(--wb-muted); font-size: 9px; }
footer span:first-child { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.candidate-count { color: #7967b1; white-space: nowrap; }
</style>
