<template>
  <main class="catalog-page">
    <header class="topbar">
      <button class="brand-mark" type="button" aria-label="ZWX Agent 首页" @click="clearFilter">Z</button>
      <span>ZWX Agent</span>
    </header>

    <section class="catalog-shell" aria-label="智能体目录">
      <div class="catalog-tools">
        <label class="search-box">
          <span aria-hidden="true">⌕</span>
          <input v-model="query" type="search" placeholder="搜索智能体" />
        </label>
      </div>

      <nav class="filters" aria-label="智能体分类">
        <button v-for="category in categories" :key="category" type="button"
          :class="{ active: activeCategory === category }" @click="activeCategory = category">
          {{ category }}
        </button>
      </nav>

      <section class="agent-section">
        <h1>智能体</h1>
        <div class="agent-grid">
          <button v-for="agent in filteredAgents" :key="agent.name" type="button" class="agent-item"
            @click="navigateTo(agent.path)">
            <span class="agent-icon" :class="agent.iconClass" aria-hidden="true">{{ agent.icon }}</span>
            <span class="agent-copy">
              <strong>{{ agent.name }}</strong>
              <small>{{ agent.description }}</small>
              <em>{{ agent.category }}</em>
            </span>
            <span class="open-agent" aria-hidden="true">→</span>
          </button>
          <p v-if="!filteredAgents.length" class="empty-result">没有匹配的智能体</p>
        </div>
      </section>
    </section>
  </main>
</template>

<script setup>
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useHead } from '@vueuse/head'

useHead({
  title: 'ZWX Agent - 智能体目录',
  meta: [
    { name: 'description', content: 'ZWX Agent 智能体目录，提供情感分析与通用任务协作能力。' },
    { name: 'keywords', content: 'ZWX Agent,情感分析大师,超级智能体,AI 智能体' }
  ]
})

const router = useRouter()
const query = ref('')
const activeCategory = ref('精选')
const categories = ['精选', '情感关系', '效率协作']
const agents = [
  {
    name: '情感分析大师',
    description: '基于对话、图片与知识库，梳理关系信号并给出建议',
    category: '情感关系',
    path: '/love-master',
    icon: '♡',
    iconClass: 'emotion-icon'
  },
  {
    name: '超级智能体',
    description: '面向多步骤任务的通用 AI 协作助手',
    category: '效率协作',
    path: '/super-agent',
    icon: '✦',
    iconClass: 'super-icon'
  }
]

const filteredAgents = computed(() => {
  const keyword = query.value.trim().toLowerCase()
  return agents.filter(agent =>
    (activeCategory.value === '精选' || agent.category === activeCategory.value) &&
    (!keyword || `${agent.name}${agent.description}${agent.category}`.toLowerCase().includes(keyword))
  )
})

const navigateTo = path => router.push(path)
const clearFilter = () => {
  query.value = ''
  activeCategory.value = '精选'
}
</script>

<style scoped>
.catalog-page { min-height: 100vh; background: #fff; color: #191919; }
.topbar { display: flex; height: 74px; align-items: center; gap: 12px; padding: 0 36px; border-bottom: 1px solid #ededed; font-size: 15px; font-weight: 650; }
.brand-mark { display: grid; width: 28px; height: 28px; place-items: center; border: 2px solid #141414; border-radius: 8px; background: #fff; color: #141414; font-size: 14px; font-weight: 800; }
.catalog-shell { max-width: 1440px; margin: 0 auto; padding: 44px 36px 72px; }
.catalog-tools { display: flex; gap: 12px; }
.search-box { display: flex; min-height: 58px; flex: 1; align-items: center; gap: 14px; padding: 0 20px; border: 1px solid #e3e3e3; border-radius: 10px; color: #777; }
.search-box span { font-size: 28px; line-height: 1; transform: rotate(-20deg); }
.search-box input { width: 100%; border: 0; outline: 0; color: #171717; font: inherit; font-size: 17px; }
.search-box input::placeholder { color: #aaa; }
.filters { display: flex; flex-wrap: wrap; gap: 10px; margin: 30px 0 38px; }
.filters button { border: 0; border-radius: 8px; background: #f4f4f4; color: #777; padding: 10px 16px; font-size: 15px; }
.filters button.active { background: #191919; color: #fff; }
.agent-section h1 { margin: 0 0 24px; font-size: 18px; font-weight: 650; }
.agent-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); column-gap: 52px; row-gap: 8px; }
.agent-item { display: flex; min-width: 0; align-items: center; gap: 16px; padding: 18px 12px; border: 0; border-bottom: 1px solid #efefef; background: transparent; color: inherit; text-align: left; }
.agent-item:hover { background: #fafafa; }
.agent-icon { display: grid; width: 56px; height: 56px; flex: 0 0 56px; place-items: center; border-radius: 50%; color: #fff; font-size: 29px; font-weight: 500; }
.emotion-icon { background: #da4a68; }
.super-icon { background: #286ee6; }
.agent-copy { display: grid; min-width: 0; gap: 5px; }
.agent-copy strong { font-size: 17px; font-weight: 650; }
.agent-copy small { overflow: hidden; color: #8c8c8c; font-size: 13px; line-height: 1.4; text-overflow: ellipsis; white-space: nowrap; }
.agent-copy em { width: max-content; border: 1px solid #e7e7e7; border-radius: 4px; color: #9a9a9a; font-size: 11px; font-style: normal; padding: 2px 5px; }
.open-agent { display: grid; width: 34px; height: 34px; margin-left: auto; place-items: center; border-radius: 50%; background: #f2f2f2; font-size: 20px; }
.empty-result { color: #888; font-size: 14px; }
@media (max-width: 720px) { .topbar { height: 60px; padding: 0 18px; } .catalog-shell { padding: 28px 18px 48px; } .search-box { min-height: 50px; } .agent-grid { grid-template-columns: 1fr; } .agent-copy small { white-space: normal; } }
</style>
