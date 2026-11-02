import { ref, watchEffect } from 'vue'
import { THEMES } from './tokens'

const STORAGE_KEY = 'aster-theme-mode'
const VALID = ['light', 'dark', 'system']
const MOTION_KEY = 'aster-reduce-motion'

// 应用内「减少动态效果」（redesign §7.4）：关闭空间飞行、回弹、自动预览，保留即时状态淡入。
// 三态存储：null=跟随系统，'true'/'false'=用户显式选择（覆盖系统偏好）。
function readReduceMotion() {
  try {
    const stored = localStorage.getItem(MOTION_KEY)
    if (stored === 'true') return true
    if (stored === 'false') return false
  } catch (error) { /* 隐私模式忽略 */ }
  return typeof matchMedia === 'function' && matchMedia('(prefers-reduced-motion: reduce)').matches
}

export const reduceMotion = ref(readReduceMotion())

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
  root.dataset.crMotion = reduceMotion.value ? 'reduced' : 'full'
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

export function setReduceMotion(value) {
  reduceMotion.value = Boolean(value)
  try { localStorage.setItem(MOTION_KEY, String(Boolean(value))) } catch (error) { /* 忽略 */ }
}
