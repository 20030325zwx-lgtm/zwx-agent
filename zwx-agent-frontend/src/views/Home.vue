<template>
  <div class="catalog-page">
    <!-- Hero：居中大标题 + 主搜索 -->
    <section class="hero" ref="heroRef" @pointermove="handleHeroPointer" @pointerleave="resetHeroPointer">
      <div class="hero-aurora" aria-hidden="true">
        <span class="aurora aurora-a"></span>
        <span class="aurora aurora-b"></span>
        <span class="aurora aurora-c"></span>
      </div>
      <div class="hero-floaters" aria-hidden="true">
        <span class="floater floater-heart" style="--depth: 1.4">♡</span>
        <span class="floater floater-spark" style="--depth: 2.2">✦</span>
        <span class="floater floater-pin" style="--depth: 1.8">⌖</span>
        <span class="floater floater-check" style="--depth: 2.6">✓</span>
      </div>

      <span class="hero-badge"><i aria-hidden="true"></i>YOUR AI WORKSPACE<span class="badge-live">LIVE</span></span>
      <h1>
        <span>让每次对话，都有</span>
        <span class="rotating-line">
          <span class="word-stack">
            <em v-for="(phrase, index) in phrases" :key="phrase" class="gradient-text"
              :class="{ active: index === activePhrase }">{{ phrase }}</em>
          </span>
        </span>
      </h1>
      <p class="hero-sub">自主智能体规划任务、调用工具并多步执行；对话问答提供专注的深度沟通与资料检索。</p>

      <label class="search-box">
        <svg class="search-glyph" viewBox="0 0 20 20" aria-hidden="true"><circle cx="8.5" cy="8.5" r="5.75" fill="none" stroke="currentColor" stroke-width="1.8" /><path d="M13 13 L17.5 17.5" stroke="currentColor" stroke-width="1.8" stroke-linecap="round" /></svg>
        <input ref="searchInput" v-model="query" type="search" placeholder="搜索智能体，如「旅游」「超级智能体」..." />
        <kbd v-if="!query" class="search-shortcut">{{ shortcutLabel }}</kbd>
        <button v-if="query" class="search-clear" type="button" aria-label="清除搜索" @click="query = ''">×</button>
      </label>

      <div class="hero-meta">
        <span class="meta-pill meta-pill-count"><strong>{{ agents.length }}</strong>个智能体已就绪</span>
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
          <button v-for="(agent, index) in agentAgents" :key="agent.name" type="button" class="agent-item featured"
            :style="{ '--i': index }" @pointermove="trackSpotlight" @click="navigateTo(agent.path)">
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
            <span v-if="agent.kind === 'agent'" class="featured-visual" aria-hidden="true">
              <span class="flow-node"><i></i>规划</span>
              <span class="flow-link"></span>
              <span class="flow-node"><i></i>执行</span>
              <span class="flow-link"></span>
              <span class="flow-node"><i></i>验收</span>
              <span class="flow-link"></span>
              <span class="flow-node"><i></i>交付</span>
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
          <button v-for="(agent, index) in qaAgents" :key="agent.name" type="button" class="agent-item"
            :style="{ '--i': index }" @pointermove="trackSpotlight" @click="navigateTo(agent.path)">
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
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useHead } from '@vueuse/head'
import { ChevronRight } from 'lucide-vue-next'
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
const searchInput = ref(null)
const heroRef = ref(null)
const phrases = ['可靠的下一步。', '清晰的执行计划。', '更聪明的协作。']
const shortcutLabel = /Mac|iPhone|iPad/.test(navigator.platform) ? '⌘K' : 'Ctrl K'
const activePhrase = ref(0)
let phraseTimer = null
let pointerFrame = 0

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

const trackSpotlight = event => {
  const element = event.currentTarget
  const rect = element.getBoundingClientRect()
  element.style.setProperty('--mx', `${event.clientX - rect.left}px`)
  element.style.setProperty('--my', `${event.clientY - rect.top}px`)
}

const handleHeroPointer = event => {
  if (!heroRef.value) return
  cancelAnimationFrame(pointerFrame)
  pointerFrame = requestAnimationFrame(() => {
    const rect = heroRef.value.getBoundingClientRect()
    const x = (event.clientX - rect.left) / rect.width - 0.5
    const y = (event.clientY - rect.top) / rect.height - 0.5
    heroRef.value.style.setProperty('--px', x.toFixed(3))
    heroRef.value.style.setProperty('--py', y.toFixed(3))
  })
}

const resetHeroPointer = () => {
  if (!heroRef.value) return
  heroRef.value.style.setProperty('--px', '0')
  heroRef.value.style.setProperty('--py', '0')
}

const focusSearch = event => {
  if ((event.metaKey || event.ctrlKey) && event.key.toLowerCase() === 'k') {
    event.preventDefault()
    searchInput.value?.focus()
  }
}

const reduceMotion = () => window.matchMedia('(prefers-reduced-motion: reduce)').matches

onMounted(() => {
  document.addEventListener('keydown', focusSearch)
  if (!reduceMotion()) {
    phraseTimer = window.setInterval(() => {
      activePhrase.value = (activePhrase.value + 1) % phrases.length
    }, 3200)
  }
})

onBeforeUnmount(() => {
  document.removeEventListener('keydown', focusSearch)
  if (phraseTimer) window.clearInterval(phraseTimer)
  cancelAnimationFrame(pointerFrame)
})
</script>

<style scoped>
.catalog-page {
  position: relative;
  color: var(--sk-label);
}

/* ── Hero ───────────────────────────────────────────── */
.hero {
  position: relative;
  display: flex;
  max-width: 860px;
  margin: 0 auto;
  padding: 62px 24px 28px;
  flex-direction: column;
  align-items: center;
  text-align: center;
  --px: 0;
  --py: 0;
}

.hero-aurora { position: absolute; inset: -80px -160px 0; pointer-events: none; overflow: hidden; }

.aurora {
  position: absolute;
  display: block;
  border-radius: 50%;
  filter: blur(10px);
  opacity: 0.72;
  animation: aurora-drift 16s cubic-bezier(0.45, 0, 0.55, 1) infinite alternate;
}

.aurora-a {
  top: 8%;
  left: 13%;
  width: 260px;
  height: 260px;
  background: radial-gradient(circle at 36% 32%, rgba(91, 145, 255, 0.34), rgba(91, 145, 255, 0) 68%);
}

.aurora-b {
  top: 22%;
  right: 10%;
  width: 320px;
  height: 320px;
  background: radial-gradient(circle at 60% 40%, rgba(72, 210, 184, 0.22), rgba(72, 210, 184, 0) 70%);
  animation-delay: -5s;
  animation-duration: 19s;
}

.aurora-c {
  bottom: -14%;
  left: 40%;
  width: 220px;
  height: 220px;
  background: radial-gradient(circle at 50% 50%, rgba(255, 133, 192, 0.16), rgba(255, 133, 192, 0) 70%);
  animation-delay: -9s;
  animation-duration: 21s;
}

@keyframes aurora-drift {
  0% { transform: translate3d(-10px, -6px, 0) scale(0.96); }
  50% { transform: translate3d(14px, 10px, 0) scale(1.06); }
  100% { transform: translate3d(-4px, 16px, 0) scale(1); }
}

.hero-floaters { position: absolute; inset: 0; pointer-events: none; }

.floater {
  position: absolute;
  display: grid;
  width: 38px;
  height: 38px;
  place-items: center;
  border: 1px solid rgba(255, 255, 255, 0.7);
  border-radius: 12px;
  background: rgba(255, 255, 255, 0.66);
  color: var(--zwx-primary);
  font-size: 16px;
  box-shadow: 0 8px 20px rgba(50, 80, 140, 0.1);
  backdrop-filter: blur(8px);
  transform: translate3d(calc(var(--px) * var(--depth) * 12px), calc(var(--py) * var(--depth) * 10px), 0);
  transition: transform 0.22s ease-out;
  animation: floater-bob 6s ease-in-out infinite;
}

.floater-heart { top: 20%; left: 8%; color: #ff5b83; animation-delay: -1s; }
.floater-spark { top: 12%; right: 9%; color: #f5a623; animation-delay: -3s; }
.floater-pin { top: 54%; left: 4%; color: #2aa254; animation-delay: -4.5s; }
.floater-check { top: 50%; right: 4%; color: #64748b; animation-delay: -2s; }

@keyframes floater-bob {
  0%, 100% { margin-top: 0; }
  50% { margin-top: -10px; }
}

.hero-badge {
  display: inline-flex;
  align-items: center;
  gap: 7px;
  border: 1px solid var(--sk-separator);
  border-radius: 7px;
  padding: 6px 14px;
  background: rgba(228, 238, 255, 0.88);
  color: #3566c8;
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
  animation: live-pulse 2.4s ease-in-out infinite;
}

.badge-live {
  margin-left: 2px;
  border-radius: 4px;
  padding: 1px 5px;
  background: rgba(52, 199, 89, 0.12);
  color: #1f9d4d;
  font-size: 8px;
  letter-spacing: 0.1em;
}

@keyframes live-pulse {
  0%, 100% { box-shadow: 0 0 0 3px rgba(52, 199, 89, 0.12); }
  50% { box-shadow: 0 0 0 6px rgba(52, 199, 89, 0.03); }
}

.hero h1 {
  margin: 24px 0 0;
  font-size: 58px;
  font-weight: 800;
  line-height: 1.12;
  letter-spacing: -0.03em;
}

.hero h1 > span { display: block; }

.rotating-line { display: flex; align-items: baseline; justify-content: center; min-height: 1.12em; }

.word-stack { display: inline-grid; justify-items: center; }

.word-stack em {
  grid-area: 1 / 1;
  text-align: center;
  opacity: 0;
  transform: translateY(14px);
  filter: blur(4px);
  transition: opacity 0.34s ease, transform 0.34s ease, filter 0.34s ease;
}

.word-stack em.active { opacity: 1; transform: translateY(0); filter: blur(0); }

.gradient-text {
  font-style: normal;
  background: linear-gradient(100deg, #1d4ed8 8%, #3f8dff 30%, #6d5df5 52%, #1a4eb7 80%, #1d4ed8 100%);
  background-size: 220% 100%;
  background-clip: text;
  -webkit-background-clip: text;
  -webkit-text-fill-color: transparent;
  color: transparent;
  animation: gradient-flow 9s ease-in-out infinite;
}

@keyframes gradient-flow {
  0%, 100% { background-position: 0% 50%; }
  50% { background-position: 100% 50%; }
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
  width: min(100%, 700px);
  height: 68px;
  position: relative;
  margin-top: 34px;
  align-items: center;
  gap: 13px;
  border: 1px solid rgba(38, 58, 96, 0.22);
  border-radius: 14px;
  padding: 0 10px 0 20px;
  background: rgba(255, 255, 255, 0.9);
  color: var(--sk-label-3);
  box-shadow: 0 12px 28px rgba(41, 73, 133, 0.13), inset 0 1px 0 rgba(255, 255, 255, 0.9);
  transition: box-shadow 0.22s ease, border-color 0.22s ease, transform 0.22s ease, background-color 0.22s ease;
}

.search-box:hover { border-color: rgba(38, 58, 96, 0.34); box-shadow: 0 16px 34px rgba(41, 73, 133, 0.16); }

.search-box:focus-within {
  border-color: var(--zwx-primary);
  background: #fff;
  box-shadow: 0 0 0 4px var(--zwx-primary-ring), 0 18px 40px rgba(41, 73, 133, 0.18);
  transform: translateY(-2px);
}

.search-shortcut {
  flex: 0 0 auto;
  border: 1px solid var(--sk-separator-strong);
  border-radius: 6px;
  padding: 3px 7px;
  background: var(--sk-fill);
  color: var(--sk-label-3);
  font-family: inherit;
  font-size: 10px;
  font-weight: 650;
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
  max-width: 1120px;
  margin: 20px auto 48px;
  border: 1px solid rgba(255, 255, 255, 0.78);
  border-radius: 18px;
  padding: 42px 36px 40px;
  background: rgba(255, 255, 255, 0.74);
  box-shadow: 0 20px 56px rgba(53, 86, 145, 0.13);
  backdrop-filter: blur(18px);
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
  border-radius: 12px;
  padding: 22px 24px;
  background: var(--sk-surface);
  color: inherit;
  box-shadow: 0 8px 22px rgba(42, 72, 127, 0.07);
  text-align: left;
  overflow: hidden;
  transition: border-color 0.2s ease, box-shadow 0.2s ease, transform 0.2s ease;
}

.agent-item::before {
  content: "";
  position: absolute;
  inset: 0;
  background: radial-gradient(240px circle at var(--mx, 50%) var(--my, 50%), rgba(0, 122, 255, 0.1), transparent 64%);
  opacity: 0;
  pointer-events: none;
  transition: opacity 0.2s ease;
}

.agent-item:hover::before { opacity: 1; }

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

.agent-title-row { display: flex; flex-wrap: wrap; align-items: center; gap: 9px; min-width: 0; }

.agent-copy strong { font-size: 17px; font-weight: 750; letter-spacing: -0.01em; white-space: nowrap; }

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

.featured-visual {
  display: flex;
  align-items: center;
  gap: 5px;
  margin-left: auto;
  padding: 0 8px;
  opacity: 0.78;
  transition: opacity 0.2s ease;
}

.agent-item.featured:hover .featured-visual { opacity: 1; }

.flow-node {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  border: 1px solid rgba(0, 122, 255, 0.14);
  border-radius: 999px;
  padding: 5px 9px;
  background: rgba(255, 255, 255, 0.72);
  color: var(--sk-label-2);
  font-size: 10px;
  font-weight: 650;
  white-space: nowrap;
}

.flow-node i {
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: var(--zwx-primary);
  box-shadow: 0 0 0 3px rgba(0, 122, 255, 0.1);
}

.flow-link {
  width: 18px;
  height: 1px;
  background: linear-gradient(90deg, rgba(0, 122, 255, 0.12), rgba(0, 122, 255, 0.55), rgba(0, 122, 255, 0.12));
  background-size: 200% 100%;
}

@media (prefers-reduced-motion: no-preference) {
  .flow-link { animation: flow-link 2.4s linear infinite; }
  .flow-node i { animation: flow-node 2.4s ease-in-out infinite; }
  .flow-node:nth-of-type(3) i { animation-delay: 0.2s; }
  .flow-node:nth-of-type(5) i { animation-delay: 0.4s; }
  .flow-node:nth-of-type(7) i { animation-delay: 0.6s; }
}

@keyframes flow-link {
  from { background-position: 200% 0; }
  to { background-position: 0 0; }
}

@keyframes flow-node {
  0%, 100% { box-shadow: 0 0 0 3px rgba(0, 122, 255, 0.08); }
  50% { box-shadow: 0 0 0 5px rgba(0, 122, 255, 0.03); }
}

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
@media (prefers-reduced-motion: no-preference) {
  .agent-item {
    animation: card-rise 0.55s cubic-bezier(0.22, 0.8, 0.3, 1) both;
    animation-delay: calc(var(--i, 0) * 70ms);
  }

  .hero-badge, .hero h1, .hero-sub, .search-box, .hero-meta {
    animation: hero-in 0.65s cubic-bezier(0.22, 0.8, 0.3, 1) both;
  }

  .hero h1 { animation-delay: 0.06s; }
  .hero-sub { animation-delay: 0.12s; }
  .search-box { animation-delay: 0.18s; }
  .hero-meta { animation-delay: 0.24s; }
}

@keyframes hero-in {
  from { opacity: 0; transform: translateY(14px); }
  to { opacity: 1; transform: translateY(0); }
}

@keyframes card-rise {
  from { opacity: 0; transform: translateY(16px) scale(0.99); }
  to { opacity: 1; transform: translateY(0) scale(1); }
}

@media (prefers-reduced-motion: reduce) {
  .aurora, .floater, .hero-badge i, .gradient-text { animation: none; }
  .floater { transform: none; }
  .word-stack em { transition: none; }
}

@media (max-width: 900px) {
  .agent-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
}


@media (max-width: 720px) {
  .hero { padding-top: 54px; }
  .hero h1 { font-size: 36px; }
  .hero br { display: none; }
  .hero-sub { font-size: 15px; }
  .hero-floaters { display: none; }
  .featured-visual { display: none; }
  .search-box { height: 54px; }
  .search-box input { font-size: 16px; }
  .search-shortcut { display: none; }
  .catalog-shell { padding-top: 40px; }
  .agent-grid { grid-template-columns: 1fr; }
  .agent-copy small { -webkit-line-clamp: 3; }
  .section-heading { flex-wrap: wrap; gap: 8px; }
  .heading-rule { display: none; }
}
</style>
