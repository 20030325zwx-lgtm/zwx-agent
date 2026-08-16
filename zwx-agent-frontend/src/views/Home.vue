<template>
  <main class="catalog-page">
    <header class="topbar">
      <button class="brand" type="button" aria-label="ZWX Agent 首页" @click="clearFilter"><span class="brand-mark">Z</span><span>ZWX Agent</span></button>
      <span class="topbar-note">AI 工作空间</span>
    </header>

    <section class="catalog-shell" aria-label="智能体目录">
      <div class="catalog-intro">
        <div>
          <span class="eyebrow">YOUR AI WORKSPACE</span>
          <h1>让每次对话，都有可靠的下一步。</h1>
          <p>选择一个专注的智能体，将想法、资料和任务转化为清晰行动。</p>
        </div>
        <div class="catalog-stat"><strong>{{ agents.length }}</strong><span>已就绪智能体</span></div>
      </div>
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
        <div class="section-heading"><h2>探索智能体</h2><span>{{ filteredAgents.length }} 个结果</span></div>
        <div class="agent-grid">
          <button v-for="agent in filteredAgents" :key="agent.name" type="button" class="agent-item"
            @click="navigateTo(agent.path)">
            <span class="agent-icon" :class="agent.iconClass" aria-hidden="true">{{ agent.icon }}</span>
            <span class="agent-copy">
              <strong>{{ agent.name }}</strong>
              <small>{{ agent.description }}</small>
              <em>{{ agent.category }}</em>
            </span>
            <span class="open-agent" aria-hidden="true">›</span>
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
const categories = ['精选', '情感关系', '旅行规划', '效率协作']
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
  },
  {
    name: '旅游规划专家',
    description: '基于偏好、私有资料与联网搜索，生成可执行的旅行方案',
    category: '旅行规划',
    path: '/travel-planner',
    icon: '⌖',
    iconClass: 'travel-icon'
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
.catalog-page { min-height: 100vh; background: #f7f8fa; color: var(--zwx-foreground); }
.topbar { display: flex; height: 70px; align-items: center; justify-content: space-between; padding: 0 36px; border-bottom: 1px solid var(--zwx-divider); background: rgba(255,255,255,.94); font-size: 14px; }
.brand { display: flex; align-items: center; gap: 10px; border: 0; background: transparent; color: #111827; font-size: 15px; font-weight: 750; }
.brand-mark { display: grid; width: 29px; height: 29px; place-items: center; border-radius: 9px; background: var(--zwx-primary); color: #fff; font-size: 14px; font-weight: 800; box-shadow: 0 4px 10px rgba(0,111,238,.25); }
.topbar-note { color: var(--zwx-muted); font-size: 13px; }.catalog-shell { max-width: 1240px; margin: 0 auto; padding: 56px 36px 76px; }.catalog-intro { display: flex; align-items: end; justify-content: space-between; gap: 32px; margin-bottom: 36px; }.eyebrow { display: block; color: var(--zwx-primary); font-size: 11px; font-weight: 750; letter-spacing: .08em; }.catalog-intro h1 { max-width: 680px; margin: 9px 0 11px; font-size: 38px; line-height: 1.18; letter-spacing: 0; }.catalog-intro p { margin: 0; color: var(--zwx-muted); font-size: 15px; line-height: 1.7; }.catalog-stat { display: grid; min-width: 135px; gap: 2px; border-left: 1px solid var(--zwx-divider); padding-left: 24px; }.catalog-stat strong { color: var(--zwx-primary); font-size: 28px; }.catalog-stat span { color: var(--zwx-muted); font-size: 12px; }.catalog-tools { display: flex; gap: 12px; }.search-box { display: flex; min-height: 54px; flex: 1; align-items: center; gap: 14px; padding: 0 18px; border: 1px solid var(--zwx-divider); border-radius: var(--zwx-radius-md); background: var(--zwx-surface); color: #777; box-shadow: 0 2px 5px rgba(15,23,42,.02); }.search-box:focus-within { border-color: var(--zwx-primary); box-shadow: 0 0 0 3px rgba(0,111,238,.12); }
.search-box span { font-size: 28px; line-height: 1; transform: rotate(-20deg); }
.search-box input { width: 100%; border: 0; outline: 0; color: #171717; font: inherit; font-size: 17px; }
.search-box input::placeholder { color: #aaa; }
.filters { display: flex; flex-wrap: wrap; gap: 8px; margin: 22px 0 30px; }.filters button { border: 1px solid transparent; border-radius: 999px; background: transparent; color: var(--zwx-muted); padding: 8px 14px; font-size: 14px; }.filters button:hover { background: #e9eef7; color: #254263; }.filters button.active { border-color: #cbdcf4; background: #eaf3ff; color: #005bc4; font-weight: 650; }.section-heading { display: flex; align-items: center; justify-content: space-between; margin: 0 0 16px; }.section-heading h2 { margin: 0; font-size: 17px; font-weight: 750; }.section-heading span { color: var(--zwx-muted); font-size: 12px; }.agent-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 16px; }.agent-item { display: flex; min-width: 0; min-height: 126px; align-items: center; gap: 16px; border: 1px solid var(--zwx-divider); border-radius: var(--zwx-radius-md); padding: 20px; background: var(--zwx-surface); color: inherit; box-shadow: 0 2px 5px rgba(15,23,42,.025); text-align: left; }.agent-item:hover { border-color: #adcef5; box-shadow: var(--zwx-shadow); transform: translateY(-2px); }.agent-icon { display: grid; width: 54px; height: 54px; flex: 0 0 54px; place-items: center; border-radius: 16px; color: #fff; font-size: 27px; font-weight: 500; }.emotion-icon { background: #e5486d; }.super-icon { background: var(--zwx-primary); }
.travel-icon { background: #0f9f6e; }.agent-copy { display: grid; min-width: 0; gap: 5px; }
.agent-copy strong { font-size: 16px; font-weight: 750; }.agent-copy small { overflow: hidden; color: var(--zwx-muted); font-size: 13px; line-height: 1.5; text-overflow: ellipsis; white-space: nowrap; }.agent-copy em { width: max-content; margin-top: 2px; border: 1px solid #dce7f7; border-radius: 5px; color: #3973ad; font-size: 10px; font-style: normal; padding: 3px 6px; }.open-agent { display: grid; width: 32px; height: 32px; margin-left: auto; place-items: center; border-radius: 50%; background: #eef5ff; color: var(--zwx-primary); font-size: 26px; line-height: 1; }
.empty-result { color: #888; font-size: 14px; }
@media (max-width: 720px) { .topbar { height: 60px; padding: 0 18px; }.topbar-note { display: none; }.catalog-shell { padding: 36px 18px 48px; }.catalog-intro { display: block; margin-bottom: 28px; }.catalog-intro h1 { font-size: 30px; }.catalog-stat { display: none; }.search-box { min-height: 50px; }.agent-grid { grid-template-columns: 1fr; }.agent-copy small { white-space: normal; }.agent-item { min-height: 112px; padding: 17px; } }
</style>
