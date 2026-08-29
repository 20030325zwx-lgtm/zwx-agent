<template>
  <main class="catalog-page">
    <header class="topbar">
      <button class="brand" type="button" aria-label="ZWX Agent 首页" @click="clearFilter"><span class="brand-mark">Z</span><span>ZWX Agent</span></button>
      <div class="topbar-actions"><span class="topbar-note">AI 工作空间</span><button class="skill-entry" type="button" @click="router.push({ name: 'SkillSettings' })"><Settings2 :size="16" />Skill 配置</button><button class="skill-entry" type="button" @click="router.push({ name: 'McpSettings' })"><PlugZap :size="16" />MCP 管理</button><AgentSettingsMenu agent-key="love" default-theme="rose" trigger-label="本机服务设置" show-trigger-label /></div>
    </header>

    <section class="catalog-shell" aria-label="智能体目录">
      <div class="catalog-intro">
        <div>
          <span class="eyebrow">YOUR AI WORKSPACE</span>
          <h1>让每次对话，都有可靠的下一步。</h1>
          <p>真正的智能体能自主规划并调用工具；对话问答提供专注的深度沟通与资料检索。</p>
        </div>
        <div class="catalog-stat"><strong>{{ agents.length }}</strong><span>已就绪智能体</span></div>
      </div>
      <div class="catalog-tools">
        <label class="search-box">
          <span aria-hidden="true">⌕</span>
          <input v-model="query" type="search" placeholder="搜索智能体" />
        </label>
      </div>

      <section class="agent-section" aria-labelledby="agent-heading">
        <div class="section-heading">
          <div class="heading-copy"><span class="group-badge agent-badge">Agent</span><h2 id="agent-heading">自主智能体</h2></div>
          <span class="group-note">可自主规划任务、调用工具并多步执行 · {{ agentAgents.length }}</span>
        </div>
        <div v-if="agentAgents.length" class="agent-grid">
          <button v-for="agent in agentAgents" :key="agent.name" type="button" class="agent-item agent-kind"
            @click="navigateTo(agent.path)">
            <span class="agent-icon" :class="agent.iconClass" aria-hidden="true">{{ agent.icon }}</span>
            <span class="agent-copy">
              <strong>{{ agent.name }}</strong>
              <small>{{ agent.description }}</small>
              <em class="kind-tag kind-tag-agent">Agent</em>
            </span>
            <span class="open-agent" aria-hidden="true">›</span>
          </button>
        </div>
        <p v-else-if="query" class="empty-result">没有匹配的自主智能体</p>
      </section>

      <section class="agent-section qa-section" aria-labelledby="qa-heading">
        <div class="section-heading">
          <div class="heading-copy"><span class="group-badge qa-badge">Chat</span><h2 id="qa-heading">对话问答</h2></div>
          <span class="group-note">专注单轮对话、资料检索与方案生成 · {{ qaAgents.length }}</span>
        </div>
        <div v-if="qaAgents.length" class="agent-grid">
          <button v-for="agent in qaAgents" :key="agent.name" type="button" class="agent-item"
            @click="navigateTo(agent.path)">
            <span class="agent-icon" :class="agent.iconClass" aria-hidden="true">{{ agent.icon }}</span>
            <span class="agent-copy">
              <strong>{{ agent.name }}</strong>
              <small>{{ agent.description }}</small>
              <em class="kind-tag kind-tag-qa">问答</em>
            </span>
            <span class="open-agent" aria-hidden="true">›</span>
          </button>
        </div>
        <p v-else-if="query" class="empty-result">没有匹配的对话问答</p>
      </section>
    </section>
  </main>
</template>

<script setup>
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useHead } from '@vueuse/head'
import { PlugZap, Settings2 } from 'lucide-vue-next'
import AgentSettingsMenu from '../components/AgentSettingsMenu.vue'
import { AGENT_LIST } from '../config/agents'

useHead({
  title: 'ZWX Agent - 智能体目录',
  meta: [
    { name: 'description', content: 'ZWX Agent 智能体目录，区分自主智能体与对话问答能力。' },
    { name: 'keywords', content: 'ZWX Agent,情感分析大师,超级智能体,旅游规划专家,AI 智能体' }
  ]
})

const router = useRouter()
const query = ref('')
const agents = AGENT_LIST

const filteredAgents = computed(() => {
  const keyword = query.value.trim().toLowerCase()
  if (!keyword) return agents
  return agents.filter(agent =>
    `${agent.name}${agent.description}${agent.category}`.toLowerCase().includes(keyword)
  )
})
const agentAgents = computed(() => filteredAgents.value.filter(agent => agent.kind === 'agent'))
const qaAgents = computed(() => filteredAgents.value.filter(agent => agent.kind !== 'agent'))

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
.topbar-note { color: var(--zwx-muted); font-size: 13px; }.topbar-actions { display:flex; align-items:center; gap:16px; }.skill-entry { display:flex; align-items:center; gap:7px; height:33px; border:1px solid var(--zwx-divider); border-radius:7px; padding:0 11px; background:#fff; color:#475467; font-size:12px; }.skill-entry:hover { border-color:var(--zwx-primary); background:var(--zwx-primary-soft); color:var(--zwx-primary); }.catalog-shell { max-width: 1240px; margin: 0 auto; padding: 56px 36px 76px; }.catalog-intro { display: flex; align-items: end; justify-content: space-between; gap: 32px; margin-bottom: 36px; }.eyebrow { display: block; color: var(--zwx-primary); font-size: 11px; font-weight: 750; letter-spacing: .08em; }.catalog-intro h1 { max-width: 680px; margin: 9px 0 11px; font-size: 38px; line-height: 1.18; letter-spacing: 0; }.catalog-intro p { margin: 0; color: var(--zwx-muted); font-size: 15px; line-height: 1.7; }.catalog-stat { display: grid; min-width: 135px; gap: 2px; border-left: 1px solid var(--zwx-divider); padding-left: 24px; }.catalog-stat strong { color: var(--zwx-primary); font-size: 28px; }.catalog-stat span { color: var(--zwx-muted); font-size: 12px; }.catalog-tools { display: flex; gap: 12px; }.search-box { display: flex; min-height: 54px; flex: 1; align-items: center; gap: 14px; padding: 0 18px; border: 1px solid var(--zwx-divider); border-radius: var(--zwx-radius-md); background: var(--zwx-surface); color: #777; box-shadow: 0 2px 5px rgba(15,23,42,.02); }.search-box:focus-within { border-color: var(--zwx-primary); box-shadow: 0 0 0 3px rgba(0,111,238,.12); }
.search-box span { font-size: 28px; line-height: 1; transform: rotate(-20deg); }
.search-box input { width: 100%; border: 0; outline: 0; color: #171717; font: inherit; font-size: 17px; }
.search-box input::placeholder { color: #aaa; }
.section-heading { display: flex; align-items: center; justify-content: space-between; margin: 0 0 16px; }.heading-copy { display: flex; align-items: center; gap: 10px; }.section-heading h2 { margin: 0; font-size: 17px; font-weight: 750; }.group-badge { display: grid; min-width: 46px; height: 22px; place-items: center; border-radius: 999px; padding: 0 8px; font-size: 10px; font-weight: 750; letter-spacing: .03em; }.group-badge.agent-badge { background: #eaf3ff; color: #006fee; }.group-badge.qa-badge { background: #f0f4f3; color: #0f9f6e; }.group-note { color: var(--zwx-muted); font-size: 12px; }.qa-section { margin-top: 40px; }.agent-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 16px; }.agent-item { display: flex; min-width: 0; min-height: 126px; align-items: center; gap: 16px; border: 1px solid var(--zwx-divider); border-radius: var(--zwx-radius-md); padding: 20px; background: var(--zwx-surface); color: inherit; box-shadow: 0 2px 5px rgba(15,23,42,.025); text-align: left; }.agent-item:hover { border-color: #adcef5; box-shadow: var(--zwx-shadow); transform: translateY(-2px); }.agent-item.agent-kind { border-color: #d7e4fb; background: linear-gradient(180deg, #fbfdff, #fff); }.agent-item.agent-kind:hover { border-color: #8fb8f2; }.agent-icon { display: grid; width: 54px; height: 54px; flex: 0 0 54px; place-items: center; border-radius: 16px; color: #fff; font-size: 27px; font-weight: 500; }.emotion-icon { background: #e5486d; }.super-icon { background: var(--zwx-primary); }
.travel-icon { background: #0f9f6e; }.test-icon { background: #6b7280; }.agent-copy { display: grid; min-width: 0; gap: 5px; }
.agent-copy strong { font-size: 16px; font-weight: 750; }.agent-copy small { overflow: hidden; color: var(--zwx-muted); font-size: 13px; line-height: 1.5; text-overflow: ellipsis; white-space: nowrap; }.kind-tag { width: max-content; margin-top: 2px; border: 1px solid; border-radius: 5px; font-size: 10px; font-style: normal; padding: 3px 7px; }.kind-tag-agent { border-color: #cfe0fa; background: #eaf3ff; color: #246bb2; }.kind-tag-qa { border-color: #d8e6e1; background: #f0f7f4; color: #2c7a5c; }.open-agent { display: grid; width: 32px; height: 32px; margin-left: auto; place-items: center; border-radius: 50%; background: #eef5ff; color: var(--zwx-primary); font-size: 26px; line-height: 1; }
.empty-result { margin: 0; color: #888; font-size: 14px; }
@media (max-width: 720px) { .topbar { height: 60px; padding: 0 18px; }.topbar-note { display: none; }.catalog-shell { padding: 36px 18px 48px; }.catalog-intro { display: block; margin-bottom: 28px; }.catalog-intro h1 { font-size: 30px; }.catalog-stat { display: none; }.search-box { min-height: 50px; }.agent-grid { grid-template-columns: 1fr; }.agent-copy small { white-space: normal; }.agent-item { min-height: 112px; padding: 17px; }.section-heading { align-items: flex-start; gap: 8px; }.group-note { font-size: 11px; } }
</style>
