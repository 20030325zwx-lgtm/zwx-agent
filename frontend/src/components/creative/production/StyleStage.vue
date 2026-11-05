<template>
  <section class="style-stage">
    <div class="stage-card">
      <header class="stage-card-head">
        <div>
          <h2>项目画风</h2>
          <p class="stage-sub">作为本项目视频与分镜生成的默认风格；镜头后续可以单独覆盖。</p>
        </div>
        <button v-if="dirty" type="button" class="cr-primary stage-action" :disabled="saving" @click="save">
          {{ saving ? '保存中…' : '保存画风' }}
        </button>
        <button v-else type="button" class="cr-primary stage-action" @click="$emit('next')">
          下一步：{{ nextLabel }}
        </button>
      </header>

      <div class="style-picker-row">
        <ImageStyleCenter v-model="form.videoStyle" :styles="styles" :has-reference="false" />
        <span class="picked-name">{{ pickedLabel || '未指定（生成时保持自由发挥）' }}</span>
      </div>
      <p class="stage-note">画风预览是示意图，实际效果由模型与提示词共同决定。</p>

      <div class="form-grid">
        <label class="cr-field">
          <span class="cr-field-label">画幅</span>
          <select v-model="form.videoAspect" class="cr-select">
            <option value="">暂不指定</option>
            <option value="16:9">16:9 横屏</option>
            <option value="9:16">9:16 竖屏</option>
            <option value="1:1">1:1 方形</option>
          </select>
        </label>
        <label class="cr-field">
          <span class="cr-field-label">目标总时长（秒）</span>
          <input v-model="form.videoTargetSeconds" class="cr-input" type="number" min="10" max="600" step="5"
                 placeholder="例如 30" />
        </label>
      </div>
      <p v-if="error" class="cr-error">{{ error }}</p>

      <footer class="impact-note">
        <p>更改默认画风只影响后续生成；已有候选保留并标记「较早设定」，由你决定是否重新生成。</p>
        <p v-if="shotCount">将影响 {{ shotCount }} 个镜头。</p>
      </footer>
    </div>
  </section>
</template>

<script setup>
import { computed, inject, onMounted, reactive, ref } from 'vue'
import { PROJECT_CTX } from '../../../views/creative/project/composables'
import { getProjectSettings, listCreativePromptImageStyles, updateProjectSettings } from '../../../api'
import ImageStyleCenter from '../ImageStyleCenter.vue'

/**
 * P0 画风阶段：项目默认风格 + 画幅/目标时长。值随项目设定持久化（服务端校验），
 * 收藏/最近使用由 ImageStyleCenter 本地保存，界面不承诺跨设备同步。
 */
const ctx = inject(PROJECT_CTX)
const projectId = ctx.projectId
const story = ctx.story

const styles = ref([])
const props = defineProps({
  nextLabel: { type: String, default: '写故事' }
})
const form = reactive({ videoStyle: '', videoAspect: '', videoTargetSeconds: '' })
const saved = reactive({ videoStyle: '', videoAspect: '', videoTargetSeconds: '' })
const saving = ref(false)
const error = ref('')
const emit = defineEmits(['saved', 'next'])

const dirty = computed(() => form.videoStyle !== saved.videoStyle
  || form.videoAspect !== saved.videoAspect
  || String(form.videoTargetSeconds) !== String(saved.videoTargetSeconds))
const pickedLabel = computed(() => styles.value.find(style => style.code === form.videoStyle)?.label || '')
const shotCount = computed(() => story.shots.value.length)

onMounted(async () => {
  listCreativePromptImageStyles().then(catalog => {
    styles.value = Array.isArray(catalog) ? catalog : []
  }).catch(() => {})
  try {
    const settings = await getProjectSettings(projectId.value)
    form.videoStyle = settings.videoStyle || ''
    form.videoAspect = settings.videoAspect || ''
    form.videoTargetSeconds = settings.videoTargetSeconds || ''
    saved.videoStyle = form.videoStyle
    saved.videoAspect = form.videoAspect
    saved.videoTargetSeconds = form.videoTargetSeconds
  } catch (exception) {
    error.value = exception.response?.data?.error || '项目设定加载失败'
  }
})

async function save() {
  saving.value = true
  error.value = ''
  try {
    const seconds = String(form.videoTargetSeconds).trim()
    await updateProjectSettings(projectId.value, {
      videoStyle: form.videoStyle,
      videoAspect: form.videoAspect,
      videoTargetSeconds: seconds === '' ? '' : String(Number(seconds))
    })
    saved.videoStyle = form.videoStyle
    saved.videoAspect = form.videoAspect
    saved.videoTargetSeconds = seconds === '' ? '' : String(Number(seconds))
    emit('saved', { videoStyle: saved.videoStyle, videoTargetSeconds: saved.videoTargetSeconds })
    ctx.showToast?.('画风已保存', 'success')
    emit('next')
  } catch (exception) {
    error.value = exception.response?.data?.error || '画风保存失败'
  } finally {
    saving.value = false
  }
}
</script>

<style scoped src="../../../views/creative/creative.css"></style>

<style scoped>
.style-stage { max-width: 860px; }
.stage-card { padding: 18px 20px; border: 1px solid var(--cr-border, #e4e3df); border-radius: var(--cr-radius-pop, 16px); background: var(--cr-surface, #fff); }
.stage-card-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 14px; margin-bottom: 16px; }
.stage-card-head h2 { margin: 0; font-size: 16px; font-weight: 600; color: var(--cr-text, #202124); }
.stage-sub { margin: 4px 0 0; color: var(--cr-text-2, #62646a); font-size: 12px; }
.stage-action { height: 36px; padding: 0 16px; }
.style-picker-row { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.picked-name { color: var(--cr-text-2, #62646a); font-size: 12px; }
.stage-note { margin: 8px 0 14px; color: var(--cr-text-2, #62646a); font-size: 11px; }
.form-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 14px; max-width: 480px; }
.impact-note { margin-top: 16px; padding-top: 12px; border-top: 1px solid var(--cr-border, #e4e3df); color: var(--cr-text-2, #62646a); font-size: 11px; line-height: 1.6; }
.impact-note p { margin: 0 0 4px; }
@media (prefers-reduced-motion: reduce) { * { transition: none !important; } }
</style>
