<template>
  <div class="cr-page production-page">
    <div class="tab-scroll">
      <div class="tab-inner production-inner">
        <!-- 阶段栏：进度与导航，不是禁止返回的向导；完成度按实际数据计算 -->
        <nav class="stage-bar" aria-label="制作阶段">
          <template v-for="(stage, index) in stages" :key="stage.key">
            <span v-if="index" class="stage-link" aria-hidden="true" />
            <button type="button" class="stage-item" :class="{ current: stage.key === activeStage, done: doneStages.has(stage.key) }"
                    @click="goStage(stage.key)">
              <span class="stage-dot">{{ doneStages.has(stage.key) ? '✓' : index + 1 }}</span>
              <span class="stage-name">{{ stage.label }}</span>
            </button>
          </template>
          <button type="button" class="stage-hint" :title="missingHint" @click="goStage(missingStage)">
            缺什么：{{ missingHint }}
          </button>
        </nav>

        <div class="summary-row">
          <span>{{ story.episodes.value[0]?.title || '当前短片' }}</span>
          <span>·</span>
          <span>{{ story.shots.value.length }} 个镜头</span>
          <span>·</span>
          <span>目标 {{ targetSeconds ? `${targetSeconds} 秒` : '未指定' }}</span>
          <span>·</span>
          <span>已选 {{ story.progress.value.selected }}/{{ story.shots.value.length }}</span>
        </div>

        <p v-if="story.error.value" class="cr-error">{{ story.error.value }}</p>

        <div class="stage-body">
          <p v-if="!story.workspace.value && story.loading.value" class="cr-hint">正在加载项目…</p>
          <template v-else>
            <StyleStage v-if="activeStage === 'style'" next-label="写故事" @saved="onStyleSaved" @next="goStage('story')" />
            <StoryStage v-else-if="activeStage === 'story'" />
            <ShotBoardStage v-else-if="activeStage === 'shots'" focus="edit" />
            <ShotBoardStage v-else-if="activeStage === 'video'" focus="generate" />
            <div v-else class="stage-card cut-empty">
              <h2>成片</h2>
              <p>按镜头顺序拼接已选视频并导出 MP4 —— 将在下一期提供。</p>
              <p>当前已选 {{ story.progress.value.selected }}/{{ story.shots.value.length }} 个镜头；缺片镜头会阻止完整导出。</p>
            </div>
          </template>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, inject, onMounted, ref, watch } from 'vue'
import { PROJECT_CTX } from './composables'
import { getProjectSettings } from '../../../api'
import StyleStage from '../../../components/creative/production/StyleStage.vue'
import StoryStage from '../../../components/creative/production/StoryStage.vue'
import ShotBoardStage from '../../../components/creative/production/ShotBoardStage.vue'

/**
 * P0 制作视图（2026-11-05）：画风 → 故事 → 分镜 → 视频 → 成片。
 * 深链接 ?stage=；刷新/回访按当前数据计算完成度，恢复到第一个未完成阶段。
 * 「制作」与「画布」共享同一份镜头/候选/素材引用（需求 §6.1）。
 */
const ctx = inject(PROJECT_CTX)
const projectId = ctx.projectId
const project = ctx.project
const story = ctx.story

const STAGES = [
  { key: 'style', label: '画风' },
  { key: 'story', label: '故事' },
  { key: 'shots', label: '分镜' },
  { key: 'video', label: '视频' },
  { key: 'cut', label: '成片' }
]
const stages = STAGES
const targetSeconds = ref(0)

const activeStage = computed(() => {
  const stage = ctx.route?.query?.stage
  return STAGES.some(item => item.key === stage) ? stage : initialStage.value
})
const initialStage = ref('style')

const doneStages = computed(() => {
  const shots = story.shots.value
  const done = new Set()
  if (videoStyle.value) done.add('style')
  const episode = story.episodes.value[0]
  if (episode && [episode.script, episode.synopsis, episode.goal].some(text => (text || '').trim())) done.add('story')
  if (shots.length) done.add('shots')
  if (shots.length && story.progress.value.selected >= shots.length) done.add('video')
  return done
})

const videoStyle = ref('')
watch(() => project.value?.id, loadSettings, { immediate: true })

async function loadSettings() {
  if (!projectId.value) return
  try {
    const settings = await getProjectSettings(projectId.value)
    videoStyle.value = settings.videoStyle || ''
    targetSeconds.value = Number(settings.videoTargetSeconds) || 0
  } catch { /* 阶段栏完成度尽力而为 */ }
}

const missingStage = computed(() => STAGES.find(stage => !doneStages.value.has(stage.key))?.key || 'cut')
const missingHint = computed(() => {
  const shots = story.shots.value
  if (!videoStyle.value) return '未选项目画风'
  const episode = story.episodes.value[0]
  if (!episode || ![episode.script, episode.synopsis, episode.goal].some(text => (text || '').trim())) return '故事还没写'
  if (!shots.length) return '还没有镜头'
  if (story.progress.value.selected < shots.length) return `还有 ${shots.length - story.progress.value.selected} 个镜头待选片`
  return '可进入成片（下一期提供导出）'
})

function onStyleSaved(saved) {
  videoStyle.value = saved.videoStyle || ''
  targetSeconds.value = Number(saved.videoTargetSeconds) || 0
}

function goStage(key) {
  ctx.router?.push({ path: ctx.route.path, query: { ...ctx.route.query, stage: key } })
}

onMounted(() => {
  // 无显式 stage 时，回访恢复到第一个未完成阶段（需求 §3.1-4：继续项目）
  if (!ctx.route?.query?.stage) initialStage.value = missingStage.value
})

// 设置页改了画风回到制作视图时刷新完成度
watch(() => ctx.route?.fullPath, () => {
  if (activeStage.value === 'style') loadSettings()
})
</script>

<style scoped src="../creative.css"></style>

<style scoped>
.production-page { min-height: 100%; background: var(--wb-bg, #f7f7f8); }
.production-inner { display: grid; gap: 14px; padding-top: 18px; padding-bottom: 48px; }
.stage-bar { display: flex; align-items: center; gap: 6px; flex-wrap: wrap; padding: 10px 14px; border: 1px solid var(--cr-border, #e4e3df); border-radius: var(--cr-radius-pop, 16px); background: var(--cr-surface, #fff); }
.stage-item { display: inline-flex; align-items: center; gap: 7px; height: 32px; padding: 0 10px; border: 0; border-radius: var(--cr-radius-ctl, 10px); background: transparent; color: var(--cr-text-2, #62646a); font-size: 13px; cursor: pointer; }
.stage-item:hover { background: var(--wb-fill, #f0f1f3); }
.stage-item.current { background: var(--cr-accent-soft, rgb(102 84 199 / 12%)); color: var(--cr-accent, #6654c7); font-weight: 600; }
.stage-item.current .stage-dot { background: var(--cr-accent, #6654c7); color: #fff; border-color: var(--cr-accent, #6654c7); }
.stage-dot { display: inline-flex; align-items: center; justify-content: center; width: 20px; height: 20px; border-radius: 50%; border: 1px solid var(--cr-border, #e4e3df); font-size: 11px; }
.stage-item.done .stage-dot { background: var(--cr-accent-soft, rgb(102 84 199 / 12%)); border-color: transparent; color: var(--cr-accent, #6654c7); }
.stage-link { width: 16px; height: 1px; background: var(--cr-border, #e4e3df); flex: none; }
.stage-hint { margin-left: auto; border: 0; background: transparent; color: var(--cr-text-2, #62646a); font-size: 11px; cursor: pointer; }
.stage-hint:hover { color: var(--cr-accent, #6654c7); }
.summary-row { display: flex; align-items: center; gap: 8px; color: var(--cr-text-2, #62646a); font-size: 12px; flex-wrap: wrap; }
.stage-body { min-width: 0; }
.cut-empty { padding: 26px 22px; border: 1px dashed var(--cr-border, #e4e3df); border-radius: var(--cr-radius-pop, 16px); color: var(--cr-text-2, #62646a); font-size: 13px; line-height: 1.7; }
.cut-empty h2 { margin: 0 0 8px; font-size: 16px; color: var(--cr-text, #202124); }
.cut-empty p { margin: 0 0 6px; }
@media (max-width: 768px) { .stage-hint { display: none; } }
@media (prefers-reduced-motion: reduce) { * { transition: none !important; } }
</style>
