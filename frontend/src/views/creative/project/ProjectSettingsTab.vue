<template>
  <div class="tab-scroll">
    <div class="tab-inner">
      <header class="st-head">
        <div>
          <h2 class="st-title">设定与连续性</h2>
          <p class="cr-field-help">
            角色与地点跨集复用；事实/状态/伏笔会注入提示词优化和分镜生成。这里只维护记录，不会自动改写已生成内容。
          </p>
        </div>
        <button type="button" class="cr-ghost" @click="reloadKey += 1">
          <RefreshCw :size="14" /> 刷新
        </button>
      </header>
      <StorySettingsPanel :key="reloadKey" :project-id="projectId" embedded />
    </div>
  </div>
</template>

<script setup>
import { inject, ref } from 'vue'
import { RefreshCw } from 'lucide-vue-next'
import { PROJECT_CTX } from './composables'
import StorySettingsPanel from '../story/StorySettingsPanel.vue'

const { projectId } = inject(PROJECT_CTX)
const reloadKey = ref(0)
</script>

<style scoped src="../creative.css"></style>

<style scoped>
.st-head { display: flex; align-items: flex-start; justify-content: space-between; gap: var(--wb-space-3); flex-wrap: wrap; }
.st-title { margin: 0; font-size: var(--wb-fs-section); line-height: var(--wb-lh-section); font-weight: 600; }
</style>
