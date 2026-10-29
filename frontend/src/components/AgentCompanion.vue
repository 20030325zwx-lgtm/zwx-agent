<template>
  <div class="agent-companion" :class="`state-${state}`">
    <Transition name="bubble">
      <div v-if="bubbleVisible" class="companion-bubble" role="status" aria-live="polite">
        <strong>{{ stateLabel }}</strong>
        <span v-if="caption && caption !== stateLabel">{{ caption }}</span>
      </div>
    </Transition>
    <button
      class="companion-figure"
      type="button"
      :aria-label="`${name} 状态：${stateLabel}`"
      :aria-expanded="pinned"
      :title="`${name} · ${stateLabel}`"
      @click="pinned = !pinned"
      @mouseenter="hovered = true"
      @mouseleave="hovered = false"
      @focus="hovered = true"
      @blur="hovered = false"
    >
      <svg viewBox="0 0 120 150" aria-hidden="true">
        <defs>
          <linearGradient id="ac-body" x1="0" y1="0" x2="0" y2="1">
            <stop offset="0" stop-color="#7abaff" />
            <stop offset="0.55" stop-color="#3b8cff" />
            <stop offset="1" stop-color="#1f6ef0" />
          </linearGradient>
          <radialGradient id="ac-visor" cx="0.5" cy="0.22" r="0.95">
            <stop offset="0" stop-color="#21365c" />
            <stop offset="1" stop-color="#081120" />
          </radialGradient>
          <radialGradient id="ac-halo" cx="0.5" cy="0.5" r="0.5">
            <stop offset="0" stop-color="#6cb4ff" stop-opacity="0.42" />
            <stop offset="1" stop-color="#6cb4ff" stop-opacity="0" />
          </radialGradient>
        </defs>

        <circle class="halo" cx="60" cy="66" r="48" fill="url(#ac-halo)" />
        <g class="sparkles" fill="#ffd66b">
          <path d="M0 -5 L1.2 -1.2 L5 0 L1.2 1.2 L0 5 L-1.2 1.2 L-5 0 L-1.2 -1.2 Z" transform="translate(20 32)" />
          <path d="M0 -4 L1 -1 L4 0 L1 1 L0 4 L-1 1 L-4 0 L-1 -1 Z" transform="translate(100 46)" />
          <path d="M0 -3.4 L0.8 -0.8 L3.4 0 L0.8 0.8 L0 3.4 L-0.8 0.8 L-3.4 0 L-0.8 -0.8 Z" transform="translate(93 18)" />
        </g>
        <g class="orbit">
          <circle cx="60" cy="22" r="3" fill="#7cc4ff" />
          <circle cx="98" cy="84" r="2.6" fill="#ffd66b" />
          <circle cx="22" cy="84" r="2.2" fill="#9ff5d0" />
        </g>
        <circle class="work-ring" cx="60" cy="66" r="45" fill="none" stroke="#7cc4ff" stroke-width="1.6" stroke-dasharray="5 7" stroke-linecap="round" />
        <ellipse class="ground-shadow" cx="60" cy="141" rx="27" ry="4.5" fill="#2b5ea8" opacity="0.16" />

        <g class="float">
          <g class="tilt">
            <line x1="60" y1="38" x2="60" y2="20" stroke="#9cc7ff" stroke-width="2" stroke-linecap="round" />
            <path class="star" d="M60 6 L62.4 14.1 L70.5 16.5 L62.4 18.9 L60 27 L57.6 18.9 L49.5 16.5 L57.6 14.1 Z" fill="#ffd66b" />
            <rect class="body" x="24" y="38" width="72" height="66" rx="33" fill="url(#ac-body)" />
            <ellipse cx="60" cy="48" rx="24" ry="10" fill="#ffffff" opacity="0.18" />
            <ellipse class="visor" cx="60" cy="67" rx="25" ry="20" fill="url(#ac-visor)" />
            <g class="eyes">
              <rect class="eye" x="48" y="60" width="7" height="12" rx="3.5" fill="#8ef0ff" />
              <rect class="eye" x="65" y="60" width="7" height="12" rx="3.5" fill="#8ef0ff" />
            </g>
            <g class="happy-eyes" fill="none" stroke="#8ef0ff" stroke-width="2.6" stroke-linecap="round">
              <path d="M47 68 Q51 62 55 68" />
              <path d="M65 68 Q69 62 73 68" />
            </g>
            <g class="error-eyes" stroke="#ff9aa2" stroke-width="2.4" stroke-linecap="round">
              <path d="M48 62 L55 70 M55 62 L48 70" />
              <path d="M65 62 L72 70 M72 62 L65 70" />
            </g>
            <rect class="mouth" x="56" y="79" width="8" height="3.2" rx="1.6" fill="#8ef0ff" opacity="0.85" />
            <circle cx="42" cy="78" r="4" fill="#ff8fbd" opacity="0.35" />
            <circle cx="78" cy="78" r="4" fill="#ff8fbd" opacity="0.35" />
            <g class="equalizer" fill="#dffaff">
              <rect x="53" y="90" width="3" height="7" rx="1.5" />
              <rect x="58.5" y="88" width="3" height="11" rx="1.5" />
              <rect x="64" y="90" width="3" height="7" rx="1.5" />
            </g>
          </g>
        </g>
      </svg>
    </button>
  </div>
</template>

<script setup>
import { computed, ref } from 'vue'

const props = defineProps({
  state: { type: String, default: 'idle' },
  caption: { type: String, default: '' },
  name: { type: String, default: '星枢' }
})

const hovered = ref(false)
const pinned = ref(false)
const stateLabels = {
  idle: '待机中',
  listening: '在听你说',
  thinking: '正在思考',
  working: '正在执行任务',
  speaking: '正在回答',
  done: '任务完成',
  error: '遇到问题'
}
const stateLabel = computed(() => stateLabels[props.state] || stateLabels.idle)
const bubbleVisible = computed(() => hovered.value || pinned.value || props.state !== 'idle' || !!props.caption)
</script>

<style scoped>
.agent-companion {
  position: absolute;
  z-index: 6;
  right: 22px;
  bottom: 118px;
  display: flex;
  align-items: flex-end;
  justify-content: flex-end;
  gap: 8px;
  pointer-events: none;
}

.agent-companion > * { pointer-events: auto; }

.companion-figure {
  width: 104px;
  border: 0;
  border-radius: 28px;
  padding: 0;
  background: transparent;
  cursor: pointer;
  transition: transform 0.2s ease, filter 0.2s ease;
}

.companion-figure:hover { transform: translateY(-2px); filter: brightness(1.03); }
.companion-figure:active { transform: translateY(0) scale(0.98); }
.companion-figure:focus-visible { outline: 3px solid var(--zwx-primary-ring); outline-offset: 2px; }
.companion-figure svg { display: block; width: 100%; height: auto; overflow: visible; }

.companion-bubble {
  position: relative;
  width: max-content;
  max-width: 216px;
  margin-bottom: 46px;
  border: 1px solid var(--wb-border);
  border-radius: 14px 14px 4px 14px;
  padding: 9px 12px;
  background: color-mix(in srgb, var(--wb-surface) 96%, transparent);
  box-shadow: var(--wb-shadow-lg);
  backdrop-filter: blur(10px);
  -webkit-backdrop-filter: blur(10px);
  pointer-events: none;
}

.companion-bubble::after {
  position: absolute;
  right: -5px;
  bottom: 10px;
  width: 9px;
  height: 9px;
  border-right: 1px solid var(--wb-border);
  border-bottom: 1px solid var(--wb-border);
  background: inherit;
  content: "";
  transform: rotate(-45deg);
}

.companion-bubble strong { display: block; color: var(--wb-text); font-size: 12.5px; font-weight: 700; }
.companion-bubble span { display: block; margin-top: 3px; color: var(--wb-text-2); font-size: 11.5px; line-height: 1.5; }

.bubble-enter-active, .bubble-leave-active { transition: opacity 0.18s ease, transform 0.18s ease; }
.bubble-enter-from, .bubble-leave-to { opacity: 0; transform: translateY(4px) scale(0.96); }

.float { animation: ac-float 4.2s ease-in-out infinite; }
.eye { animation: ac-blink 5.4s ease-in-out infinite; transform-box: fill-box; transform-origin: center; }
.star { animation: ac-star 2.6s ease-in-out infinite; transform-box: fill-box; transform-origin: center; }
.halo { animation: ac-halo 3.4s ease-in-out infinite; transform-box: fill-box; transform-origin: center; }
.eyes { transform-box: view-box; transform-origin: 60px 66px; transition: transform 0.25s ease; }
.happy-eyes, .error-eyes { opacity: 0; }
.orbit { opacity: 0; transform-box: view-box; transform-origin: 60px 66px; transition: opacity 0.25s ease; }
.work-ring { opacity: 0; transform-box: view-box; transform-origin: 60px 66px; }
.equalizer rect { opacity: 0.4; transform-box: fill-box; transform-origin: bottom center; }
.equalizer rect:nth-child(2) { opacity: 0.75; }
.tilt { transform-box: view-box; transform-origin: 60px 104px; }
.sparkles path { opacity: 0; transform-box: fill-box; transform-origin: center; }

.state-listening .eyes { transform: scale(1.12); }
.state-listening .tilt { animation: ac-tilt 1.8s ease-in-out infinite; }
.state-thinking .eyes { transform: translateY(-2px); }
.state-thinking .orbit { opacity: 1; animation: ac-orbit 2.8s linear infinite; }
.state-working .work-ring { opacity: 1; animation: ac-ring 2.4s linear infinite; }
.state-working .tilt { animation: ac-work 1s ease-in-out infinite; }
.state-working .equalizer rect, .state-speaking .equalizer rect { opacity: 1; animation: ac-eq 0.72s ease-in-out infinite; }
.state-speaking .equalizer rect:nth-child(2) { animation-delay: 0.12s; }
.state-speaking .equalizer rect:nth-child(3) { animation-delay: 0.24s; }
.state-speaking .mouth { animation: ac-talk 0.28s ease-in-out infinite; transform-box: fill-box; transform-origin: center; }
.state-speaking .halo { animation-duration: 1.8s; }
.state-done .eyes { opacity: 0; }
.state-done .happy-eyes { opacity: 1; }
.state-done .float { animation: ac-done 0.9s ease-out, ac-float 4.2s ease-in-out 0.9s infinite; }
.state-done .sparkles path { animation: ac-sparkle 1.3s ease-out infinite; }
.state-done .sparkles path:nth-child(2) { animation-delay: 0.18s; }
.state-done .sparkles path:nth-child(3) { animation-delay: 0.36s; }
.state-error .eyes { opacity: 0; }
.state-error .error-eyes { opacity: 1; }
.state-error .body { filter: saturate(0.45); }
.state-error .halo { opacity: 0.3; }

@keyframes ac-float {
  0%, 100% { transform: translateY(0); }
  50% { transform: translateY(-6px); }
}

@keyframes ac-blink {
  0%, 92%, 100% { transform: scaleY(1); }
  95%, 97% { transform: scaleY(0.12); }
}

@keyframes ac-star {
  0%, 100% { transform: scale(1); opacity: 0.9; }
  50% { transform: scale(1.25); opacity: 1; }
}

@keyframes ac-halo {
  0%, 100% { opacity: 0.45; transform: scale(1); }
  50% { opacity: 0.8; transform: scale(1.06); }
}

@keyframes ac-orbit { to { transform: rotate(360deg); } }
@keyframes ac-ring { to { transform: rotate(360deg); } }

@keyframes ac-tilt {
  0%, 100% { transform: rotate(0deg); }
  50% { transform: rotate(-4deg); }
}

@keyframes ac-work {
  0%, 100% { transform: translateY(0) rotate(0deg); }
  50% { transform: translateY(-2px) rotate(-1.5deg); }
}

@keyframes ac-talk {
  0%, 100% { transform: scaleY(0.6); }
  50% { transform: scaleY(1.6); }
}

@keyframes ac-eq {
  0%, 100% { transform: scaleY(0.4); }
  50% { transform: scaleY(1); }
}

@keyframes ac-sparkle {
  0%, 100% { opacity: 0; transform: scale(0.4); }
  40%, 60% { opacity: 1; transform: scale(1); }
}

@keyframes ac-done {
  0% { transform: translateY(0) scale(1); }
  35% { transform: translateY(-9px) scale(1.04); }
  70% { transform: translateY(0) scale(0.98); }
  100% { transform: translateY(0) scale(1); }
}

@media (max-width: 900px) {
  .agent-companion { right: 12px; bottom: 112px; }
  .companion-figure { width: 88px; }
  .companion-bubble { max-width: 180px; margin-bottom: 38px; }
}

@media (max-width: 720px) {
  .agent-companion { right: 10px; bottom: 148px; }
  .companion-figure { width: 74px; }
  .companion-bubble { max-width: 158px; margin-bottom: 32px; padding: 8px 10px; }
  .companion-bubble strong { font-size: 11.5px; }
  .companion-bubble span { font-size: 11px; }
}

@media (prefers-reduced-motion: reduce) {
  .float, .eye, .star, .halo, .orbit, .work-ring, .tilt, .mouth,
  .equalizer rect, .sparkles path { animation: none !important; }
  .state-listening .eyes { transform: none; }
  .state-thinking .eyes { transform: none; }
}
</style>
