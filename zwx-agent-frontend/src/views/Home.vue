<template>
  <main class="catalog-page">
    <header class="topbar">
      <button class="brand" type="button" aria-label="ZWX Agent 首页" @click="clearFilter"><span class="brand-mark">Z</span><span>ZWX Agent</span></button>
      <div class="topbar-actions"><span class="topbar-note">AI 工作空间</span><button class="skill-entry" type="button" @click="router.push({ name: 'SkillSettings' })"><Settings2 :size="15" />Skill 配置</button><button class="skill-entry" type="button" @click="router.push({ name: 'McpSettings' })"><PlugZap :size="15" />MCP 管理</button><AgentSettingsMenu agent-key="love" default-theme="blue" trigger-label="本机服务设置" show-trigger-label /></div>
    </header>

    <section class="catalog-shell" aria-label="智能体目录">
      <div class="catalog-intro">
        <div>
          <span class="eyebrow">YOUR AI WORKSPACE</span>
          <h1>让每次对话，<br />都有可靠的下一步。</h1>
          <p>真正的智能体能自主规划并调用工具；对话问答提供专注的深度沟通与资料检索。</p>
        </div>
        <div class="catalog-stat"><strong>{{ agents.length }}</strong><span>已就绪智能体</span></div>
      </div>
      <div class="catalog-tools">
        <label class="search-box">
          <svg class="search-glyph" viewBox="0 0 20 20" aria-hidden="true"><circle cx="8.5" cy="8.5" r="5.75" fill="none" stroke="currentColor" stroke-width="1.8" /><path d="M13 13 L17.5 17.5" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" /></svg>
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
}
</script>

<style scoped>
.catalog-page {
  min-height: 100vh;
  background:
    radial-gradient(60rem 36rem at 85% -12%, rgba(0, 122, 255, 0.07), transparent 62%),
    radial-gradient(48rem 32rem at -10% 30%, rgba(255, 45, 85, 0.05), transparent 60%),
    var(--sk-bg);
  color: var(--sk-label);
}

/* ── 顶栏：毛玻璃悬浮条 ─────────────────────────────── */
.topbar {
  position: sticky;
  z-index: 10;
  top: 0;
  display: flex;
  height: 64px;
  align-items: center;
  justify-content: space-between;
  padding: 0 32px;
  border-bottom: 1px solid var(--sk-separator);
  background: var(--sk-material);
  backdrop-filter: var(--sk-blur);
  -webkit-backdrop-filter: var(--sk-blur);
  font-size: 14px;
}

.brand {
  display: flex;
  align-items: center;
  gap: 10px;
  border: 0;
  background: transparent;
  color: var(--sk-label);
  font-size: 15px;
  font-weight: 700;
  letter-spacing: -0.01em;
}

.brand-mark {
  display: grid;
  width: 30px;
  height: 30px;
  place-items: center;
  border-radius: 9px;
  background: linear-gradient(160deg, #3f9bff, var(--zwx-primary) 58%, var(--zwx-primary-dark));
  color: #fff;
  font-size: 14px;
  font-weight: 800;
  box-shadow: 0 4px 12px var(--zwx-primary-ring), inset 0 1px 0 rgba(255, 255, 255, 0.4);
}

.topbar-note { color: var(--sk-label-2); font-size: 13px; }

.topbar-actions { display: flex; align-items: center; gap: 10px; }

/* iOS「tinted」按钮 */
.skill-entry {
  display: flex;
  align-items: center;
  gap: 6px;
  height: 34px;
  border: 0;
  border-radius: 10px;
  padding: 0 12px;
  background: var(--zwx-primary-soft);
  color: var(--zwx-primary);
  font-size: 12px;
  font-weight: 600;
}

.skill-entry:hover { filter: brightness(0.97); transform: translateY(-1px); }

.catalog-shell { max-width: 1200px; margin: 0 auto; padding: 64px 32px 88px; }

/* ── Hero ───────────────────────────────────────────── */
.catalog-intro {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 32px;
  margin-bottom: 36px;
}

.eyebrow {
  display: block;
  color: var(--zwx-primary);
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.12em;
}

.catalog-intro h1 {
  max-width: 640px;
  margin: 12px 0 14px;
  font-size: 44px;
  font-weight: 800;
  line-height: 1.12;
  letter-spacing: -0.025em;
}

.catalog-intro p {
  margin: 0;
  max-width: 520px;
  color: var(--sk-label-2);
  font-size: 15px;
  line-height: 1.7;
}

.catalog-stat {
  display: grid;
  min-width: 132px;
  gap: 2px;
  border-radius: var(--sk-radius-lg);
  padding: 18px 22px;
  background: var(--sk-surface);
  box-shadow: var(--sk-shadow-card);
}

.catalog-stat strong { color: var(--zwx-primary); font-size: 30px; font-weight: 800; letter-spacing: -0.02em; }
.catalog-stat span { color: var(--sk-label-2); font-size: 12px; }

/* ── 搜索：iOS 搜索框 ──────────────────────────────── */
.catalog-tools { display: flex; gap: 12px; margin-bottom: 40px; }

.search-box {
  display: flex;
  min-height: 52px;
  flex: 1;
  align-items: center;
  gap: 12px;
  padding: 0 18px;
  border-radius: 15px;
  background: var(--sk-surface);
  color: var(--sk-label-3);
  box-shadow: var(--sk-shadow-card);
}

.search-box:focus-within { box-shadow: 0 0 0 3px var(--zwx-primary-ring), var(--sk-shadow-card); }

.search-glyph { width: 19px; height: 19px; flex: 0 0 19px; }

.search-box input {
  width: 100%;
  border: 0;
  outline: 0;
  background: transparent;
  color: var(--sk-label);
  font: inherit;
  font-size: 16px;
}

.search-box input::placeholder { color: var(--sk-label-3); }

/* ── 分组标题 ───────────────────────────────────────── */
.section-heading { display: flex; align-items: center; justify-content: space-between; margin: 0 0 16px; }
.heading-copy { display: flex; align-items: center; gap: 10px; }
.section-heading h2 { margin: 0; font-size: 20px; font-weight: 700; letter-spacing: -0.015em; }

.group-badge {
  display: grid;
  min-width: 48px;
  height: 24px;
  place-items: center;
  border-radius: 999px;
  padding: 0 9px;
  font-size: 10px;
  font-weight: 700;
  letter-spacing: 0.04em;
}

.group-badge.agent-badge { background: var(--zwx-primary-soft); color: var(--zwx-primary); }
.group-badge.qa-badge { background: rgba(52, 199, 89, 0.14); color: #1f9d4d; }
.group-note { color: var(--sk-label-2); font-size: 12px; }
.qa-section { margin-top: 44px; }

/* ── 智能体卡片 ─────────────────────────────────────── */
.agent-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 16px; }

.agent-item {
  display: flex;
  min-width: 0;
  min-height: 122px;
  align-items: center;
  gap: 16px;
  border: 1px solid var(--sk-separator);
  border-radius: 20px;
  padding: 20px;
  background: var(--sk-surface);
  color: inherit;
  box-shadow: var(--sk-shadow-card);
  text-align: left;
}

.agent-item:hover {
  border-color: transparent;
  box-shadow: var(--sk-shadow-raised);
  transform: translateY(-3px);
}

.agent-item:active { transform: translateY(-1px) scale(0.995); }

/* SF 风格大圆角图标 */
.agent-icon {
  display: grid;
  width: 56px;
  height: 56px;
  flex: 0 0 56px;
  place-items: center;
  border-radius: 17px;
  color: #fff;
  font-size: 27px;
  font-weight: 500;
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.32), 0 6px 14px rgba(0, 0, 0, 0.14);
}

.emotion-icon { background: linear-gradient(160deg, #ff7a95, #ff2d55 70%); }
.super-icon { background: linear-gradient(160deg, #3f9bff, #007aff 70%); }
.travel-icon { background: linear-gradient(160deg, #4cd471, #30b350 70%); }
.test-icon { background: linear-gradient(160deg, #98a2b3, #64748b 70%); }

.agent-copy { display: grid; min-width: 0; gap: 5px; }
.agent-copy strong { font-size: 16px; font-weight: 700; letter-spacing: -0.01em; }
.agent-copy small { overflow: hidden; color: var(--sk-label-2); font-size: 13px; line-height: 1.5; text-overflow: ellipsis; white-space: nowrap; }

.kind-tag {
  width: max-content;
  margin-top: 2px;
  border: 0;
  border-radius: 6px;
  font-size: 10px;
  font-style: normal;
  font-weight: 650;
  padding: 3px 7px;
}

.kind-tag-agent { background: var(--zwx-primary-soft); color: var(--zwx-primary); }
.kind-tag-qa { background: rgba(52, 199, 89, 0.14); color: #1f9d4d; }

.open-agent {
  display: grid;
  width: 30px;
  height: 30px;
  margin-left: auto;
  place-items: center;
  border-radius: 50%;
  background: var(--sk-fill);
  color: var(--sk-label-2);
  font-size: 24px;
  line-height: 1;
  transition: background-color 0.18s ease, color 0.18s ease, transform 0.18s ease;
}

.agent-item:hover .open-agent { background: var(--zwx-primary); color: #fff; transform: translateX(2px); }

.empty-result { margin: 0; color: var(--sk-label-2); font-size: 14px; }

@media (max-width: 720px) {
  .topbar { height: 58px; padding: 0 16px; }
  .topbar-note { display: none; }
  .catalog-shell { padding: 36px 18px 48px; }
  .catalog-intro { display: block; margin-bottom: 28px; }
  .catalog-intro h1 { font-size: 31px; }
  .catalog-intro br { display: none; }
  .catalog-stat { display: none; }
  .search-box { min-height: 48px; }
  .agent-grid { grid-template-columns: 1fr; }
  .agent-copy small { white-space: normal; }
  .agent-item { min-height: 110px; padding: 17px; }
  .section-heading { align-items: flex-start; gap: 8px; }
  .group-note { font-size: 11px; }
}
</style>
