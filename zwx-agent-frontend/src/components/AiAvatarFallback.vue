<template>
  <div class="ai-avatar-fallback" :class="type">
    <span v-if="type === 'love'">❤️</span>
    <span v-else>🤖</span>
  </div>
</template>

<script setup>
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
  background: linear-gradient(145deg, #ff7a95, #ff2d55);
}

.default, .super {
  background: linear-gradient(145deg, #4da2ff, #007aff);
}
</style>
