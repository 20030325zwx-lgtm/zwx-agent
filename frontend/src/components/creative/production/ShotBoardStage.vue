<template>
  <section class="board-stage">
    <header class="board-head">
      <h2>{{ focus === 'generate' ? '视频：生成与选片' : '分镜：镜头编辑' }}</h2>
      <p class="board-sub">
        {{ focus === 'generate'
          ? '勾选已就绪的镜头批量生成；单个镜头点开卡片进入检查器生成、对比候选并选片。'
          : '点开镜头编辑提示词与剧情字段；生成与候选在检查器里完成。' }}
      </p>
      <div class="board-link">
        <span>这里的镜头与画布共享项目画风和素材库；画布是自由编排模式，镜头暂不自动同步。</span>
        <button type="button" class="cr-ghost" @click="openCanvas">在画布中编排</button>
      </div>
    </header>
    <ProjectStoryboardTab />
  </section>
</template>

<script setup>
import { inject } from 'vue'
import { PROJECT_CTX } from '../../../views/creative/project/composables'
import ProjectStoryboardTab from '../../../views/creative/project/ProjectStoryboardTab.vue'

const ctx = inject(PROJECT_CTX)
function openCanvas() {
  ctx.router?.push({ path: `/creative/projects/${ctx.projectId.value}/canvas` })
}

/**
 * P0 分镜/视频阶段复用现有镜头看板（同一份数据、同一套生成与候选底座）：
 * 阶段栏只是导航与进度，不做互斥向导；两个视角的差异在后续版本细化。
 */
defineProps({
  focus: { type: String, default: 'edit' }
})
</script>

<style scoped>
.board-stage { display: grid; gap: 10px; }
.board-head h2 { margin: 0; font-size: 16px; font-weight: 600; color: var(--cr-text, #202124); }
.board-sub { margin: 4px 0 0; color: var(--cr-text-2, #62646a); font-size: 12px; }
.board-link { display: flex; align-items: center; gap: 8px; margin-top: 6px; flex-wrap: wrap; }
.board-link span { color: var(--cr-text-2, #62646a); font-size: 11px; }
.board-link .cr-ghost { height: 26px; padding: 0 8px; font-size: 11px; }
</style>
