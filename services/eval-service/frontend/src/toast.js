import { ref } from 'vue'

/** 全局轻量提示：面板组件只需 import showToast，不必层层传递。 */
export const toastText = ref('')
let timer = null

export function showToast(text) {
  toastText.value = String(text)
  if (timer) clearTimeout(timer)
  timer = setTimeout(() => {
    toastText.value = ''
    timer = null
  }, 3600)
}
