<template>
  <div class="ai-avatar-fallback" :class="type">
    <Heart v-if="type === 'love'" :size="16" :stroke-width="2" />
    <AsterAvatar v-else />
  </div>
</template>

<script setup>
import { Heart } from 'lucide-vue-next'
import AsterAvatar from './AsterAvatar.vue'

defineProps({
  type: {
    type: String,
    default: 'default'
  }
})
</script>

<style scoped>
.ai-avatar-fallback {
  width: 100%;
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 17px;
  border-radius: 32%;
}

.love {
  background: color-mix(in srgb, var(--wb-danger) 12%, transparent);
  color: var(--wb-danger);
}

.default, .super {
  background: var(--wb-mark-bg);
}
</style>
