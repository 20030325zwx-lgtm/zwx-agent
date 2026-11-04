<template>
  <div class="page">
    <header class="head">
      <div>
        <p class="kicker">评测中台 · 独立服务</p>
        <h1>评测台</h1>
        <p class="sub">换模型、改 Prompt、动知识库之前，先拿一份能对比的报告。</p>
      </div>
      <div class="row-actions">
        <a class="btn ghost" href="/">返回 Aster</a>
      </div>
    </header>

    <p v-if="!token" class="error">未检测到登录状态：请先在 Aster 主站登录后再访问评测台。</p>

    <div class="tabs">
      <button class="tab" :class="{ active: tab === 'experiments' }" @click="tab = 'experiments'">实验</button>
      <button class="tab" :class="{ active: tab === 'datasets' }" @click="tab = 'datasets'">数据集</button>
      <button class="tab" :class="{ active: tab === 'prompts' }" @click="tab = 'prompts'">Prompt 资产</button>
    </div>

    <ExperimentPanel v-if="tab === 'experiments' && token" />
    <DatasetPanel v-else-if="tab === 'datasets' && token" />
    <PromptPanel v-else-if="tab === 'prompts' && token" />

    <p v-if="toastText" class="toast">{{ toastText }}</p>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { hasToken } from './api'
import { toastText } from './toast'
import ExperimentPanel from './components/ExperimentPanel.vue'
import DatasetPanel from './components/DatasetPanel.vue'
import PromptPanel from './components/PromptPanel.vue'

const token = ref(hasToken())
const tab = ref('experiments')
</script>
