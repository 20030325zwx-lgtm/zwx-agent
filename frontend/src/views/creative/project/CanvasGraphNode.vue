<template>
  <article class="graph-card" :class="[{ active: selected }, 'kind-' + type]" :aria-label="data.label">
    <header class="card-head">
      <span class="type-mark"><component :is="typeIcon" :size="16" /></span>
      <div><small>{{ typeLabel }}</small><strong>{{ data.label || typeLabel }}</strong></div>
      <span v-if="data.status && data.status !== 'IDLE'" class="state-badge" :class="{ failed: data.status === 'FAILED' }">{{ statusLabel }}</span>
    </header>
    <div class="port-row">
      <span v-if="generation">提示词输入</span><span v-else>{{ type === 'text_input' ? '文本内容' : '项目素材' }}</span>
      <span v-if="type !== 'asset_input'">提示词输出</span><span v-else>素材输出</span>
    </div>
    <Handle v-if="generation" id="text-in" type="target" :position="Position.Left" class="port port-text" :style="{ top: '82px' }" :aria-label="data.label + ' 提示词输入'" />
    <Handle v-if="type !== 'asset_input'" id="text-out" type="source" :position="Position.Right" class="port port-text" :style="{ top: '82px' }" :aria-label="data.label + ' 提示词输出'" />
    <Handle v-else id="media-out" type="source" :position="Position.Right" class="port port-media" :style="{ top: '82px' }" :aria-label="data.label + ' 素材输出'" />
    <div v-if="generation" class="port-row media-row"><span>{{ type === 'video_generate' ? '首帧输入' : '参考图输入' }}</span><span>{{ type === 'video_generate' ? '视频结果' : '图片输出' }}</span></div>
    <Handle v-if="generation" id="image-in" type="target" :position="Position.Left" class="port port-media" :style="{ top: '112px' }" :aria-label="data.label + ' 图片输入'" />
    <Handle v-if="generation && type !== 'video_generate'" id="media-out" type="source" :position="Position.Right" class="port port-media" :style="{ top: '112px' }" :aria-label="data.label + ' 图片输出'" />
    <p class="card-prompt">{{ summary }}</p>
    <div v-if="generation || type === 'asset_input'" class="card-preview">
      <img v-if="previewUrl && mediaType !== 'VIDEO'" :src="previewUrl" alt="节点素材预览" />
      <video v-else-if="previewUrl && mediaType === 'VIDEO'" :src="previewUrl" controls preload="metadata" class="nodrag nopan" />
      <template v-else><component :is="type === 'video_generate' ? Clapperboard : Image" :size="25" :stroke-width="1.3" /><span>{{ data.selectedAssetId ? '结果已保存' : type === 'asset_input' ? '选择或上传参考素材' : '生成结果将在这里展示' }}</span></template>
    </div>
    <footer>
      <span v-if="generation">{{ presentation?.modelName(data.modelRowId) || '尚未选择模型' }}</span>
      <span v-else>{{ type === 'asset_input' ? (data.assetIds || []).length + ' 个素材' : '连接到下一节点的提示词输入' }}</span>
      <span v-if="data.candidates?.length" class="candidate-count">{{ data.candidates.length }} 个候选</span>
    </footer>
  </article>
</template>

<script setup>
import { computed, inject } from 'vue'
import { Handle, Position } from '@vue-flow/core'
import { Clapperboard, FileText, Image, ImagePlus, WandSparkles } from 'lucide-vue-next'
const props = defineProps({ id: String, type: String, data: Object, selected: Boolean })
const presentation = inject('canvasPresentation', null)
const generation = computed(() => ['image_generate', 'image_edit', 'video_generate'].includes(props.type))
const typeLabel = computed(() => ({ asset_input: '素材', text_input: '文本', image_generate: '图片生成', image_edit: '图片编辑', video_generate: '视频生成' })[props.type])
const typeIcon = computed(() => ({ asset_input: ImagePlus, text_input: FileText, image_generate: Image, image_edit: WandSparkles, video_generate: Clapperboard })[props.type])
const summary = computed(() => props.type === 'text_input' ? props.data.text || '写下要传给下游的内容…'
  : props.type === 'asset_input' ? (props.data.assetIds || []).map(id => presentation?.assetName(id) || id).join('、') || '图片、视频均可保存到节点'
  : presentation?.prompt(props.id) || '选中节点，在左侧填写提示词…')
const previewId = computed(() => props.data.selectedAssetId || props.data.assetIds?.[0])
const previewUrl = computed(() => presentation?.assetUrl(previewId.value) || '')
const mediaType = computed(() => props.type === 'video_generate' ? 'VIDEO' : presentation?.assetType(previewId.value))
const statusLabel = computed(() => ({ PENDING: '等待', QUEUED: '排队', RUNNING: '生成中', WAITING_EXTERNAL: '生成中', WAITING_SELECTION: '待选片', SUCCEEDED: '已完成', FAILED: '失败', SKIPPED: '跳过', CANCELLED: '已取消' })[props.data.status] || props.data.status)
</script>

<style scoped>
.graph-card { width: 268px; position: relative; box-sizing: border-box; border: 1px solid var(--wb-border, #e3e6ed); border-radius: 12px; background: var(--wb-surface, #fff); color: var(--wb-text, #232735); box-shadow: 0 3px 12px rgb(25 35 60 / 5%); }
.graph-card.active { border-color: #6674e8; box-shadow: 0 0 0 3px rgb(102 116 232 / 10%), 0 6px 20px rgb(25 35 60 / 7%); }
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
