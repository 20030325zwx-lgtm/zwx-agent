import { ref, watchEffect } from 'vue'
import { THEMES } from './tokens'

const STORAGE_KEY = 'aster-theme-mode'
const VALID = ['light', 'dark', 'system']

function readStored() {
  try {
    const value = localStorage.getItem(STORAGE_KEY)
    return VALID.includes(value) ? value : 'system'
  } catch (error) {
    return 'system'
  }
}

function systemPrefersDark() {
  return typeof matchMedia === 'function' && matchMedia('(prefers-color-scheme: dark)').matches
}

// 'light' | 'dark' | 'system'（用户选择）；resolvedTheme 是实际生效值
export const themeMode = ref(readStored())
export const resolvedTheme = ref('light')

watchEffect(() => {
  const mode = themeMode.value
  const effective = mode === 'system' ? (systemPrefersDark() ? 'dark' : 'light') : mode
  resolvedTheme.value = effective

  const root = document.documentElement
  root.dataset.wbTheme = effective
  for (const [name, value] of Object.entries(THEMES[effective].css)) {
    root.style.setProperty(name, value)
  }
  try {
    localStorage.setItem(STORAGE_KEY, mode)
  } catch (error) { /* 隐私模式等场景忽略持久化失败 */ }
})

if (typeof matchMedia === 'function') {
  matchMedia('(prefers-color-scheme: dark)').addEventListener('change', () => {
    if (themeMode.value === 'system') themeMode.value = 'system' // 触发 watchEffect 重算
  })
}

export function setThemeMode(mode) {
  if (VALID.includes(mode)) themeMode.value = mode
}
