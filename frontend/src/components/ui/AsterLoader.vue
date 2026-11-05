<template>
  <span class="aster-loader" :style="{ '--al-size': size + 'px' }" role="status" aria-label="加载中">
    <svg class="aster-loader-svg" viewBox="0 0 32 32" fill="currentColor" aria-hidden="true" focusable="false">
      <g transform="translate(-7.5 2.3) scale(1.3)">
        <g stroke="currentColor" stroke-opacity=".35" stroke-width=".85" fill="none" stroke-linecap="round">
          <path d="M8.5 6.5 7.6 15.4 13.4 18.2 14.8 11.2Z" />
          <path d="M14.8 11.2 20.2 8.6 24.6 5.8 28.6 2.9" />
        </g>
        <circle class="al-star" style="--i: 0" cx="8.5" cy="6.5" r="2.1" />
        <circle class="al-star" style="--i: 1" cx="7.6" cy="15.4" r="2" />
        <circle class="al-star" style="--i: 2" cx="13.4" cy="18.2" r="1.9" />
        <circle class="al-star" style="--i: 3" cx="14.8" cy="11.2" r="1.6" />
        <circle class="al-star" style="--i: 4" cx="20.2" cy="8.6" r="2.1" />
        <circle class="al-star" style="--i: 5" cx="24.6" cy="5.8" r="1.9" />
        <circle class="al-star" style="--i: 6" cx="28.6" cy="2.9" r="2" />
        <circle class="al-star" style="--i: 6" cx="26.7" cy="4.6" r=".75" opacity=".55" />
      </g>
    </svg>
    <span v-if="label" class="aster-loader-label">{{ label }}</span>
  </span>
</template>

<script setup>
/** 北斗七星加载动效：七颗星依次亮起，用于页面/区块级加载占位。 */
defineProps({
  size: { type: Number, default: 20 },
  label: { type: String, default: '' }
})
</script>

<style scoped>
.aster-loader {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  color: var(--aster-star-color, currentColor);
}
.aster-loader-svg {
  width: var(--al-size, 20px);
  height: var(--al-size, 20px);
  flex: 0 0 auto;
}
.aster-loader-label {
  font-size: 12px;
  color: var(--wb-muted, #81899a);
}
.al-star {
  opacity: .22;
  animation: al-twinkle 1.9s ease-in-out infinite;
  animation-delay: calc(var(--i) * .13s);
}
@keyframes al-twinkle {
  0%, 72%, 100% { opacity: .22; }
  14% { opacity: 1; }
  30% { opacity: .55; }
}
@media (prefers-reduced-motion: reduce) {
  .al-star { animation: none; opacity: .85; }
}
</style>
