<template>
  <div class="skd-root" role="dialog" aria-modal="true" :aria-label="detail ? `技能详情：${detail.name}` : '技能详情'"
       @keydown.esc.stop="close">
    <div class="skd-backdrop" @click="close" />
    <aside class="skd-panel">
      <p v-if="loading" class="cr-hint skd-loading">加载中…</p>
      <p v-else-if="error" class="cr-error skd-loading">
        {{ error }}
        <button type="button" class="cr-secondary" @click="load">重试</button>
      </p>
      <template v-else-if="detail">
        <header class="skd-head">
          <div class="skd-head-text">
            <p class="skd-kicker">技能 · v{{ detail.version }} · {{ detail.sourceType === 'BUILTIN' ? '官方' : detail.sourceType }}</p>
            <h2>{{ detail.name }}</h2>
            <p class="skd-summary">{{ detail.summary }}</p>
            <div class="skd-chips">
              <span class="cr-status" :class="detail.compat === 'READY' ? 'success' : 'muted'">
                {{ detail.compat === 'READY' ? '当前可用' : '缺能力' }}
              </span>
              <span v-if="detail.compat !== 'READY'" class="skd-missing-chip">
                {{ (detail.missing || []).join('；') }}
              </span>
            </div>
          </div>
          <button ref="closeBtn" type="button" class="skd-close" aria-label="关闭" @click="close">
            <X :size="16" />
          </button>
        </header>

        <div class="skd-body">
          <section>
            <h3>输入与输出</h3>
            <dl class="skd-io">
              <div><dt>输入</dt><dd>{{ inputLabel }}</dd></div>
              <div><dt>输出</dt><dd>{{ outputLabel }}</dd></div>
              <div v-if="detail.mediaOperation"><dt>媒体操作</dt><dd>{{ detail.mediaOperation === 'image_to_video' ? '首帧图生视频' : '文生视频' }}</dd></div>
            </dl>
            <p class="skd-note">方案生成只使用文本信息，不调用视觉分析；首帧图片在确认生成时由系统单独传给模型。</p>
          </section>

          <section>
            <h3>使用说明</h3>
            <pre class="skd-body-text">{{ detail.body }}</pre>
          </section>

          <section>
            <h3>版本与来源</h3>
            <dl class="skd-io">
              <div><dt>版本</dt><dd>v{{ detail.version }}</dd></div>
              <div><dt>来源</dt><dd>{{ sourceLabel }}</dd></div>
              <div><dt>授权</dt><dd>本项目自行编写（{{ detail.licenseStatus === 'OWN' ? '自有版权' : detail.licenseStatus }}）</dd></div>
            </dl>
          </section>
        </div>

        <footer class="skd-foot">
          <button type="button" class="cr-primary" :disabled="detail.compat !== 'READY'"
                  :title="detail.compat !== 'READY' ? (detail.missing || []).join('；') : ''"
                  @click="$emit('use', detail)">
            {{ detail.compat === 'READY' ? '在创作台使用' : '暂不可用' }}
          </button>
          <p class="skd-foot-note">使用技能不会自动生成：方案确认后才提交生成任务。</p>
        </footer>
      </template>
    </aside>
  </div>
</template>

<script setup>
import { computed, nextTick, onMounted, ref } from 'vue'
import { X } from 'lucide-vue-next'
import { getCreativeSkill } from '../../api'

const props = defineProps({ slug: { type: String, required: true } })
const emit = defineEmits(['close', 'use'])

const loading = ref(true)
const error = ref('')
const detail = ref(null)
const closeBtn = ref(null)

const inputLabel = computed(() => {
  const map = { TEXT: '文本', 'TEXT+IMAGE': '文本 + 首帧图片' }
  return map[detail.value?.inputTypes] || detail.value?.inputTypes || '文本'
})

const outputLabel = computed(() => {
  const value = detail.value?.outputTypes
  if (value === 'VIDEO') return '短视频'
  return '方案 / 提示词文本'
})

const sourceLabel = computed(() => {
  const type = detail.value?.sourceType
  if (type === 'BUILTIN') return '内置技能'
  return type || '未知'
})

async function load() {
  loading.value = true
  error.value = ''
  try {
    detail.value = await getCreativeSkill(props.slug)
  } catch (exception) {
    error.value = exception.response?.data?.error || '技能详情加载失败'
  } finally {
    loading.value = false
  }
}

function close() {
  emit('close')
}

onMounted(async () => {
  await load()
  await nextTick()
  closeBtn.value?.focus()
})
</script>

<style scoped src="../../views/creative/creative.css"></style>

<style scoped>
.skd-root { position: fixed; inset: 0; z-index: 70; }
.skd-backdrop { position: absolute; inset: 0; background: rgba(12, 12, 14, 0.4); animation: skd-fade var(--cr-dur-fast, 140ms) var(--cr-ease, ease); }
@keyframes skd-fade { from { opacity: 0; } to { opacity: 1; } }

.skd-panel {
  position: absolute;
  top: 0;
  right: 0;
  bottom: 0;
  width: min(560px, 94vw);
  display: flex;
  flex-direction: column;
  background: var(--cr-surface, var(--wb-surface));
  border-left: 1px solid var(--cr-border, var(--wb-border));
  box-shadow: var(--wb-shadow-lg);
  animation: skd-slide var(--cr-dur-normal, 220ms) var(--cr-ease, ease);
}
@keyframes skd-slide { from { transform: translateX(20px); opacity: 0; } to { transform: none; opacity: 1; } }

.skd-loading { padding: 24px; }

.skd-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  padding: 20px 22px 14px;
  border-bottom: 1px solid var(--cr-border, var(--wb-border));
}
.skd-head-text { min-width: 0; }
.skd-kicker { margin: 0 0 4px; font-size: 11.5px; color: var(--wb-muted); letter-spacing: 0.03em; }
.skd-head h2 { margin: 0 0 6px; font-size: 19px; color: var(--cr-text, var(--wb-text)); }
.skd-summary { margin: 0 0 10px; font-size: 13px; line-height: 1.6; color: var(--wb-text-2); }

.skd-chips { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.skd-missing-chip { font-size: 12px; color: var(--wb-warning); }

.skd-close {
  display: grid;
  place-items: center;
  width: 30px;
  height: 30px;
  flex: 0 0 30px;
  border: 0;
  border-radius: var(--cr-radius-ctl, 10px);
  background: transparent;
  color: var(--wb-text-2);
  cursor: pointer;
}
.skd-close:hover { background: var(--wb-fill); color: var(--wb-text); }

.skd-body { flex: 1; overflow-y: auto; padding: 6px 22px 16px; }
.skd-body h3 {
  margin: 18px 0 8px;
  font-size: 12.5px;
  font-weight: 620;
  letter-spacing: 0.03em;
  color: var(--wb-text-2);
}

.skd-io { display: flex; flex-direction: column; gap: 6px; margin: 0; font-size: 12.5px; }
.skd-io div { display: flex; gap: 8px; }
.skd-io dt { flex: 0 0 64px; color: var(--wb-muted); }
.skd-io dd { margin: 0; color: var(--wb-text-2); }

.skd-note { margin: 10px 0 0; font-size: 12px; line-height: 1.6; color: var(--wb-muted); }

.skd-body-text {
  margin: 0;
  padding: 12px 14px;
  border: 1px solid var(--cr-border, var(--wb-border));
  border-radius: var(--cr-radius-ctl, 10px);
  background: var(--wb-bg);
  color: var(--wb-text-2);
  font-family: inherit;
  font-size: 12.5px;
  line-height: 1.7;
  white-space: pre-wrap;
  word-break: break-word;
}

.skd-foot {
  padding: 14px 22px;
  border-top: 1px solid var(--cr-border, var(--wb-border));
}
.skd-foot-note { margin: 8px 0 0; font-size: 11.5px; color: var(--wb-muted); }
</style>
