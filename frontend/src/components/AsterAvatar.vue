<template>
  <span class="aster-avatar" aria-hidden="true">
    <svg viewBox="0 0 72 72">
      <defs>
        <linearGradient id="aa-body" x1="0" y1="0" x2="0" y2="1">
          <stop offset="0" stop-color="#8fc6ff" />
          <stop offset="0.55" stop-color="#4f9bff" />
          <stop offset="1" stop-color="#2f7ff2" />
        </linearGradient>
        <radialGradient id="aa-visor" cx="0.5" cy="0.2" r="0.95">
          <stop offset="0" stop-color="#22375e" />
          <stop offset="1" stop-color="#081120" />
        </radialGradient>
        <radialGradient id="aa-shine" cx="0.5" cy="0.5" r="0.5">
          <stop offset="0" stop-color="#ffffff" stop-opacity="0.2" />
          <stop offset="1" stop-color="#ffffff" stop-opacity="0" />
        </radialGradient>
      </defs>

      <circle cx="36" cy="38" r="29" fill="url(#aa-shine)" />
      <g class="aa-float">
        <path class="aa-star" d="M36 5 L37.8 11.2 L44 13 L37.8 14.8 L36 21 L34.2 14.8 L28 13 L34.2 11.2 Z" fill="#ffd66b" />
        <line x1="36" y1="20" x2="36" y2="24" stroke="#cbe4ff" stroke-width="2" stroke-linecap="round" />
        <rect x="11" y="22" width="50" height="44" rx="22" fill="url(#aa-body)" />
        <ellipse cx="36" cy="29" rx="18" ry="7" fill="#ffffff" opacity="0.18" />
        <rect x="17" y="31" width="38" height="27" rx="13.5" fill="url(#aa-visor)" />
        <g class="aa-eyes">
          <rect class="aa-eye" x="26" y="38" width="6" height="10" rx="3" fill="#8ef0ff" />
          <rect class="aa-eye" x="40" y="38" width="6" height="10" rx="3" fill="#8ef0ff" />
        </g>
        <path class="aa-smile" d="M32 52 Q36 55.5 40 52" fill="none" stroke="#8ef0ff" stroke-width="2.2" stroke-linecap="round" />
        <circle cx="21" cy="50" r="3" fill="#ff8fbd" opacity="0.35" />
        <circle cx="51" cy="50" r="3" fill="#ff8fbd" opacity="0.35" />
      </g>
    </svg>
  </span>
</template>

<style scoped>
.aster-avatar {
  display: block;
  width: 100%;
  height: 100%;
  overflow: hidden;
  border-radius: inherit;
  background: linear-gradient(160deg, #3f9bff, #007aff 70%);
}

.aster-avatar svg { display: block; width: 100%; height: 100%; }

.aa-float { animation: aa-float 4.6s ease-in-out infinite; }

.aa-eye {
  animation: aa-blink 5.6s ease-in-out infinite;
  transform-box: fill-box;
  transform-origin: center;
}

.aa-star {
  animation: aa-twinkle 3s ease-in-out infinite;
  transform-box: fill-box;
  transform-origin: center;
}

@keyframes aa-float {
  0%, 100% { transform: translateY(0); }
  50% { transform: translateY(-1.6px); }
}

@keyframes aa-blink {
  0%, 92%, 100% { transform: scaleY(1); }
  95%, 97% { transform: scaleY(0.12); }
}

@keyframes aa-twinkle {
  0%, 100% { opacity: 0.85; transform: scale(1); }
  50% { opacity: 1; transform: scale(1.18); }
}

@media (prefers-reduced-motion: reduce) {
  .aa-float, .aa-eye, .aa-star { animation: none !important; }
}
</style>
