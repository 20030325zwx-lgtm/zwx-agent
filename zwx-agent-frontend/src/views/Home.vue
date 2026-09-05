<template>
  <main class="catalog-page">
    <div class="bg-glow" aria-hidden="true"></div>

    <!-- 悬浮胶囊导航 -->
    <header class="topbar">
      <div class="topbar-inner">
        <button class="brand" type="button" aria-label="ZWX Agent 首页" @click="clearFilter">
          <span class="brand-mark">Z</span><span class="brand-name">ZWX Agent</span>
        </button>
        <div class="topbar-actions">
          <span class="topbar-note">AI 工作空间</span>
          <button class="skill-entry" type="button" @click="router.push({ name: 'SkillSettings' })"><Settings2 :size="14" />Skill</button>
          <button class="skill-entry" type="button" @click="router.push({ name: 'McpSettings' })"><PlugZap :size="14" />MCP</button>
          <button class="skill-entry" type="button" @click="router.push({ name: 'KnowledgeAdmin' })"><Database :size="14" />知识库</button>
          <AgentSettingsMenu agent-key="love" default-theme="blue" trigger-label="本机服务设置" show-trigger-label />
        </div>
      </div>
    </header>

    <!-- Hero：居中大标题 + 主搜索 -->
    <section class="hero">
      <span class="hero-badge"><i aria-hidden="true"></i>YOUR AI WORKSPACE</span>
      <h1>让每次对话，都有<br /><em class="gradient-text">可靠的下一步</em>。</h1>
      <p class="hero-sub">自主智能体规划任务、调用工具并多步执行；对话问答提供专注的深度沟通与资料检索。</p>

      <label class="search-box">
        <svg class="search-glyph" viewBox="0 0 20 20" aria-hidden="true"><circle cx="8.5" cy="8.5" r="5.75" fill="none" stroke="currentColor" stroke-width="1.8" /><path d="M13 13 L17.5 17.5" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" /></svg>
        <input v-model="query" type="search" placeholder="搜索智能体，如「旅游」「超级智能体」..." />
        <button v-if="query" class="search-clear" type="button" aria-label="清除搜索" @click="query = ''">×</button>
      </label>

      <div class="hero-meta">
        <span class="meta-pill"><strong>{{ agents.length }}</strong>个智能体已就绪</span>
        <span class="meta-pill meta-pill-dot">支持多步任务执行</span>
        <span class="meta-pill meta-pill-dot">私有知识库检索</span>
      </div>
    </section>

    <!-- 目录 -->
    <section class="catalog-shell" aria-label="智能体目录">
      <section v-if="agentAgents.length" class="agent-section" aria-labelledby="agent-heading">
        <div class="section-heading">
          <div class="heading-copy"><span class="group-badge agent-badge">Agent</span><h2 id="agent-heading">自主智能体</h2></div>
          <span class="heading-rule" aria-hidden="true"></span>
          <span class="group-note">可自主规划任务、调用工具并多步执行 · {{ agentAgents.length }}</span>
        </div>
        <div class="featured-grid">
          <button v-for="agent in agentAgents" :key="agent.name" type="button" class="agent-item featured"
            @click="navigateTo(agent.path)">
            <span class="featured-glow" :class="agent.iconClass" aria-hidden="true"></span>
            <span class="agent-icon" :class="agent.iconClass" aria-hidden="true">{{ agent.icon }}</span>
            <span class="agent-copy">
              <span class="agent-title-row">
                <strong>{{ agent.name }}</strong>
                <em class="kind-tag kind-tag-agent">Agent</em>
              </span>
              <small>{{ agent.description }}</small>
              <span class="featured-cta">开始任务<span aria-hidden="true">›</span></span>
            </span>
            <span class="open-agent" aria-hidden="true"><ChevronRight :size="17" /></span>
          </button>
        </div>
      </section>
      <p v-else-if="query" class="empty-result">没有匹配的自主智能体</p>

      <section v-if="qaAgents.length" class="agent-section qa-section" aria-labelledby="qa-heading">
        <div class="section-heading">
          <div class="heading-copy"><span class="group-badge qa-badge">Chat</span><h2 id="qa-heading">对话问答</h2></div>
          <span class="heading-rule" aria-hidden="true"></span>
          <span class="group-note">专注单轮对话、资料检索与方案生成 · {{ qaAgents.length }}</span>
        </div>
        <div class="agent-grid">
          <button v-for="agent in qaAgents" :key="agent.name" type="button" class="agent-item"
            @click="navigateTo(agent.path)">
            <span class="agent-icon" :class="agent.iconClass" aria-hidden="true">{{ agent.icon }}</span>
            <span class="agent-copy">
              <span class="agent-title-row">
                <strong>{{ agent.name }}</strong>
                <em class="kind-tag kind-tag-qa">{{ agent.category }}</em>
              </span>
              <small>{{ agent.description }}</small>
            </span>
            <span class="open-agent" aria-hidden="true"><ChevronRight :size="17" /></span>
          </button>
        </div>
      </section>
      <p v-else-if="query" class="empty-result">没有匹配的对话问答</p>

      <div v-if="query && !filteredAgents.length" class="empty-state">
        <span class="empty-glyph">⌕</span>
        <strong>没有找到「{{ query }}」相关的智能体</strong>
        <span>换个关键词试试，或者清除搜索查看全部。</span>
        <button type="button" @click="clearFilter">查看全部智能体</button>
      </div>
    </section>

    <footer class="catalog-footer">ZWX Agent · 让每次对话都有可靠的下一步</footer>
  </main>
</template>

<script setup>
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useHead } from '@vueuse/head'
import { ChevronRight, Database, PlugZap, Settings2 } from 'lucide-vue-next'
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
  position: relative;
  min-height: 100vh;
  overflow-x: clip;
  background: var(--sk-bg);
  color: var(--sk-label);
}

/* ── 背景光斑 ───────────────────────────────────────── */
.bg-glow {
  position: absolute;
  inset: 0;
  pointer-events: none;
  background:
    radial-gradient(46rem 30rem at 50% -14rem, rgba(0, 122, 255, 0.13), transparent 68%),
    radial-gradient(34rem 24rem at 8% 22%, rgba(255, 45, 85, 0.06), transparent 65%),
    radial-gradient(36rem 26rem at 96% 30%, rgba(52, 199, 89, 0.06), transparent 65%);
}

/* ── 悬浮胶囊导航 ───────────────────────────────────── */
.topbar {
  position: sticky;
  z-index: 10;
  top: 14px;
  padding: 0 20px;
}

.topbar-inner {
  display: flex;
  max-width: 1080px;
  height: 58px;
  margin: 0 auto;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  border: 1px solid rgba(255, 255, 255, 0.65);
  border-radius: 999px;
  padding: 0 10px 0 18px;
  background: var(--sk-material);
  backdrop-filter: var(--sk-blur);
  -webkit-backdrop-filter: var(--sk-blur);
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.04), 0 12px 32px rgba(0, 0, 0, 0.08);
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
  width: 32px;
  height: 32px;
  place-items: center;
  border-radius: 10px;
  background: linear-gradient(160deg, #3f9bff, var(--zwx-primary) 58%, var(--zwx-primary-dark));
  color: #fff;
  font-size: 15px;
  font-weight: 800;
  box-shadow: 0 4px 12px var(--zwx-primary-ring), inset 0 1px 0 rgba(255, 255, 255, 0.4);
}

.topbar-note { color: var(--sk-label-2); font-size: 13px; }

.topbar-actions { display: flex; align-items: center; gap: 8px; }

.skill-entry {
  display: flex;
  align-items: center;
  gap: 6px;
  height: 34px;
  border: 0;
  border-radius: 999px;
  padding: 0 13px;
  background: var(--sk-fill);
  color: var(--sk-label-2);
  font-size: 12px;
  font-weight: 600;
}

.skill-entry:hover { background: var(--sk-fill-strong); color: var(--sk-label); transform: translateY(-1px); }

/* ── Hero ───────────────────────────────────────────── */
.hero {
  position: relative;
  display: flex;
  max-width: 760px;
  margin: 0 auto;
  padding: 84px 24px 24px;
  flex-direction: column;
  align-items: center;
  text-align: center;
}

.hero-badge {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  border: 1px solid var(--sk-separator);
  border-radius: 999px;
  padding: 6px 14px;
  background: var(--sk-surface);
  color: var(--sk-label-2);
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.12em;
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.04);
}

.hero-badge i {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: linear-gradient(140deg, #4cd471, var(--sk-blue));
  box-shadow: 0 0 0 3px rgba(0, 122, 255, 0.12);
}

.hero h1 {
  margin: 26px 0 0;
  font-size: 54px;
  font-weight: 800;
  line-height: 1.12;
  letter-spacing: -0.03em;
}

.gradient-text {
  font-style: normal;
  background: linear-gradient(100deg, var(--sk-blue) 8%, #5856d6 52%, #ff2d55 96%);
  background-clip: text;
  -webkit-background-clip: text;
  -webkit-text-fill-color: transparent;
  color: transparent;
}

.hero-sub {
  margin: 18px 0 0;
  max-width: 560px;
  color: var(--sk-label-2);
  font-size: 16px;
  line-height: 1.7;
}

/* 主搜索：Raycast / Perplexity 式居中大搜索框 */
.search-box {
  display: flex;
  width: min(100%, 620px);
  height: 60px;
  margin-top: 34px;
  align-items: center;
  gap: 13px;
  border: 1px solid var(--sk-separator);
  border-radius: 19px;
  padding: 0 10px 0 20px;
  background: var(--sk-surface);
  color: var(--sk-label-3);
  box-shadow: var(--sk-shadow-raised);
  transition: box-shadow 0.2s ease, border-color 0.2s ease, transform 0.2s ease;
}

.search-box:focus-within {
  border-color: transparent;
  box-shadow: 0 0 0 4px var(--zwx-primary-ring), var(--sk-shadow-raised);
  transform: translateY(-1px);
}

.search-glyph { width: 21px; height: 21px; flex: 0 0 21px; }

.search-box input {
  flex: 1;
  min-width: 0;
  border: 0;
  outline: 0;
  background: transparent;
  color: var(--sk-label);
  font: inherit;
  font-size: 17px;
}

.search-box input::placeholder { color: var(--sk-label-3); }

.search-clear {
  display: grid;
  width: 40px;
  height: 40px;
  flex: 0 0 40px;
  place-items: center;
  border: 0;
  border-radius: 12px;
  background: var(--sk-fill);
  color: var(--sk-label-2);
  font-size: 19px;
  line-height: 1;
}

.search-clear:hover { background: var(--sk-fill-strong); color: var(--sk-label); }

.hero-meta { display: flex; flex-wrap: wrap; justify-content: center; gap: 9px; margin-top: 22px; }

.meta-pill {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  border: 1px solid var(--sk-separator);
  border-radius: 999px;
  padding: 6px 13px;
  background: var(--sk-surface);
  color: var(--sk-label-2);
  font-size: 12px;
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.03);
}

.meta-pill strong { color: var(--zwx-primary); font-size: 13px; font-weight: 800; }

.meta-pill-dot::before {
  content: "";
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: var(--sk-green);
}

/* ── 目录 ───────────────────────────────────────────── */
.catalog-shell {
  position: relative;
  max-width: 1080px;
  margin: 0 auto;
  padding: 56px 24px 40px;
}

.section-heading { display: flex; align-items: center; gap: 14px; margin: 0 0 18px; }
.heading-copy { display: flex; align-items: center; gap: 10px; flex: 0 0 auto; }
.section-heading h2 { margin: 0; font-size: 20px; font-weight: 750; letter-spacing: -0.015em; white-space: nowrap; }

.heading-rule { flex: 1; height: 1px; background: var(--sk-separator); }

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
.group-note { flex: 0 0 auto; color: var(--sk-label-3); font-size: 12px; }

.qa-section { margin-top: 44px; }

/* 特色卡：超级智能体大卡 */
.featured-grid { display: grid; gap: 16px; }

.agent-item {
  position: relative;
  display: flex;
  min-width: 0;
  align-items: center;
  gap: 18px;
  border: 1px solid var(--sk-separator);
  border-radius: 22px;
  padding: 24px 26px;
  background: var(--sk-surface);
  color: inherit;
  box-shadow: var(--sk-shadow-card);
  text-align: left;
  overflow: hidden;
  transition: border-color 0.2s ease, box-shadow 0.2s ease, transform 0.2s ease;
}

.agent-item:hover {
  border-color: transparent;
  box-shadow: var(--sk-shadow-raised);
  transform: translateY(-3px);
}

.agent-item:active { transform: translateY(-1px) scale(0.995); }

.agent-item.featured { min-height: 148px; }

/* 特色卡背后的柔光（按智能体色调） */
.featured-glow {
  position: absolute;
  inset: auto -30% -120px -10%;
  height: 220px;
  pointer-events: none;
  background: radial-gradient(closest-side, var(--zwx-primary-ring), transparent);
  opacity: 0;
  transition: opacity 0.25s ease;
}

.featured-glow.emotion-icon { background: radial-gradient(closest-side, rgba(255, 45, 85, 0.16), transparent); }
.featured-glow.super-icon { background: radial-gradient(closest-side, rgba(0, 122, 255, 0.18), transparent); }
.featured-glow.travel-icon { background: radial-gradient(closest-side, rgba(48, 179, 80, 0.16), transparent); }
.featured-glow.test-icon { background: radial-gradient(closest-side, rgba(100, 116, 139, 0.16), transparent); }

.agent-item.featured:hover .featured-glow { opacity: 1; }

/* 内容保持在柔光之上 */
.agent-icon, .agent-copy, .open-agent { position: relative; z-index: 1; }

/* SF 风格大圆角渐变图标 */
.agent-icon {
  display: grid;
  width: 62px;
  height: 62px;
  flex: 0 0 62px;
  place-items: center;
  border-radius: 19px;
  color: #fff;
  font-size: 29px;
  font-weight: 500;
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.32), 0 8px 18px rgba(0, 0, 0, 0.14);
  transition: transform 0.2s ease;
}

.featured .agent-icon {
  width: 72px;
  height: 72px;
  flex-basis: 72px;
  border-radius: 22px;
  font-size: 34px;
}

.agent-item:hover .agent-icon { transform: scale(1.06) rotate(-2deg); }

.emotion-icon { background: linear-gradient(160deg, #ff7a95, #ff2d55 70%); }
.super-icon { background: linear-gradient(160deg, #3f9bff, #007aff 70%); }
.travel-icon { background: linear-gradient(160deg, #4cd471, #30b350 70%); }
.test-icon { background: linear-gradient(160deg, #98a2b3, #64748b 70%); }

.agent-copy { display: grid; min-width: 0; gap: 7px; }

.agent-title-row { display: flex; align-items: center; gap: 9px; min-width: 0; }

.agent-copy strong { font-size: 17px; font-weight: 750; letter-spacing: -0.01em; }

.featured .agent-copy strong { font-size: 21px; }

.kind-tag {
  border: 0;
  border-radius: 999px;
  padding: 3px 9px;
  font-size: 10px;
  font-style: normal;
  font-weight: 700;
  flex: 0 0 auto;
}

.kind-tag-agent { background: var(--zwx-primary-soft); color: var(--zwx-primary); }
.kind-tag-qa { background: var(--sk-fill); color: var(--sk-label-2); }

.agent-copy small {
  overflow: hidden;
  color: var(--sk-label-2);
  font-size: 14px;
  line-height: 1.55;
  text-overflow: ellipsis;
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
}

.featured .agent-copy small { font-size: 15px; }

.featured-cta {
  display: inline-flex;
  width: max-content;
  align-items: center;
  gap: 5px;
  margin-top: 3px;
  color: var(--zwx-primary);
  font-size: 13px;
  font-weight: 650;
}

.featured-cta span { font-size: 17px; line-height: 1; transition: transform 0.2s ease; }

.agent-item.featured:hover .featured-cta span { transform: translateX(3px); }

/* 问答卡片三列网格 */
.agent-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 16px; }

.agent-grid .agent-item { min-height: 132px; padding: 20px; }

.open-agent {
  display: grid;
  width: 32px;
  height: 32px;
  margin-left: auto;
  flex: 0 0 32px;
  place-items: center;
  border-radius: 50%;
  background: var(--sk-fill);
  color: var(--sk-label-2);
  transition: background-color 0.2s ease, color 0.2s ease, transform 0.2s ease;
}

.agent-item:hover .open-agent { background: var(--zwx-primary); color: #fff; transform: translateX(2px); }

.empty-result { margin: 0; color: var(--sk-label-2); font-size: 14px; }

/* 搜索无结果 */
.empty-state {
  display: grid;
  justify-items: center;
  gap: 8px;
  border: 1px dashed var(--sk-separator-strong);
  border-radius: 24px;
  padding: 52px 24px;
  background: var(--sk-surface);
  color: var(--sk-label-2);
  font-size: 14px;
  text-align: center;
}

.empty-glyph { font-size: 30px; color: var(--sk-label-3); }
.empty-state strong { color: var(--sk-label); font-size: 17px; }

.empty-state button {
  height: 38px;
  margin-top: 8px;
  border: 0;
  border-radius: 11px;
  padding: 0 18px;
  background: var(--zwx-primary-soft);
  color: var(--zwx-primary);
  font-size: 13px;
  font-weight: 650;
}

.empty-state button:hover { filter: brightness(0.97); }

/* ── 页脚 ───────────────────────────────────────────── */
.catalog-footer {
  padding: 8px 24px 44px;
  color: var(--sk-label-3);
  font-size: 12px;
  text-align: center;
}

/* ── 响应式 ─────────────────────────────────────────── */
@media (max-width: 900px) {
  .agent-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
}

@media (max-width: 720px) {
  .topbar { top: 10px; padding: 0 12px; }
  .topbar-inner { height: 54px; padding: 0 8px 0 14px; }
  .topbar-note, .brand-name { display: none; }
  .hero { padding-top: 56px; }
  .hero h1 { font-size: 36px; }
  .hero br { display: none; }
  .hero-sub { font-size: 15px; }
  .search-box { height: 54px; }
  .search-box input { font-size: 16px; }
  .catalog-shell { padding-top: 40px; }
  .agent-grid { grid-template-columns: 1fr; }
  .agent-copy small { -webkit-line-clamp: 3; }
  .section-heading { flex-wrap: wrap; gap: 8px; }
  .heading-rule { display: none; }
}
</style>
