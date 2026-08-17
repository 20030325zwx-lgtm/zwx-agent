<template>
  <div ref="menuRoot" class="agent-settings">
    <button class="settings-trigger" type="button" aria-label="智能体设置" :aria-expanded="open" @click="open = !open">
      <Settings2 :size="18" />
    </button>
    <div v-if="open" class="settings-menu" role="menu">
      <div class="menu-section">
        <span>主题颜色</span>
        <div class="theme-options">
          <button v-for="theme in themes" :key="theme.key" class="theme-option" :class="{ active: activeTheme === theme.key }" type="button" :aria-pressed="activeTheme === theme.key" :aria-label="`切换为${theme.label}`" @click="selectTheme(theme.key)">
            <i :style="{ background: theme.primary }"></i><span>{{ theme.label }}</span>
          </button>
        </div>
      </div>
      <button class="knowledge-link" type="button" role="menuitem" @click="openKnowledge">
        <Database :size="16" />
        <span>当前智能体知识库</span>
        <ChevronRight :size="15" />
      </button>
      <div v-if="desktopApi" class="desktop-api-settings">
        <label for="desktop-api-url">桌面端服务地址</label>
        <div><input id="desktop-api-url" v-model="desktopApiUrl" type="url" placeholder="https://example.com/api" @keydown.enter.prevent="saveDesktopApiUrl" /><button type="button" @click="saveDesktopApiUrl">保存</button></div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ChevronRight, Database, Settings2 } from 'lucide-vue-next'

const props = defineProps({ agentKey: { type: String, required: true }, defaultTheme: { type: String, default: 'blue' } })
const router = useRouter()
const open = ref(false)
const menuRoot = ref(null)
const themes = [
  { key: 'blue', label: '海蓝', primary: '#006fee', dark: '#005bc4', soft: '#eaf3ff', ring: 'rgba(0,111,238,.12)' },
  { key: 'emerald', label: '翡翠绿', primary: '#0f9f6e', dark: '#087f5b', soft: '#ecfdf5', ring: 'rgba(15,159,110,.14)' },
  { key: 'rose', label: '玫瑰红', primary: '#d65070', dark: '#bd3d5a', soft: '#fff1f4', ring: 'rgba(214,80,112,.14)' },
  { key: 'violet', label: '紫罗兰', primary: '#7c5ce0', dark: '#6245c4', soft: '#f3efff', ring: 'rgba(124,92,224,.14)' }
]
const storageKey = `zwx-agent-theme:${props.agentKey}`
const activeTheme = ref(localStorage.getItem(storageKey) || props.defaultTheme)
const desktopApi = window.zwxDesktop
const desktopApiUrl = ref(desktopApi?.apiBaseUrl || '')
const applyTheme = key => {
  const theme = themes.find(item => item.key === key) || themes[0]
  const style = document.documentElement.style
  style.setProperty('--zwx-primary', theme.primary)
  style.setProperty('--zwx-primary-dark', theme.dark)
  style.setProperty('--zwx-primary-soft', theme.soft)
  style.setProperty('--zwx-primary-ring', theme.ring)
}
const selectTheme = key => { activeTheme.value = key; localStorage.setItem(storageKey, key); applyTheme(key) }
const openKnowledge = () => router.push({ name: 'KnowledgeAdmin', query: { agentKey: props.agentKey } })
const saveDesktopApiUrl = async () => {
  const value = desktopApiUrl.value.trim().replace(/\/$/, '')
  if (!/^https?:\/\/[^\s]+\/api$/i.test(value)) return
  await desktopApi.setApiBaseUrl(value)
  window.location.reload()
}
const closeOnOutsideClick = event => { if (!menuRoot.value?.contains(event.target)) open.value = false }
onMounted(() => { applyTheme(activeTheme.value); document.addEventListener('click', closeOnOutsideClick) })
onBeforeUnmount(() => document.removeEventListener('click', closeOnOutsideClick))
</script>

<style scoped>
.agent-settings { position:relative; display:flex; width:190px; justify-content:flex-end; }.settings-trigger { display:grid; width:32px; height:32px; place-items:center; border:1px solid #e4e7ec; border-radius:7px; background:#fff; color:#667085; }.settings-trigger:hover,.settings-trigger[aria-expanded="true"] { border-color:var(--zwx-primary); background:var(--zwx-primary-soft); color:var(--zwx-primary); }.settings-menu { position:absolute; z-index:20; top:40px; right:0; width:256px; border:1px solid #e5e7eb; border-radius:8px; padding:8px; background:#fff; box-shadow:0 14px 30px rgba(15,23,42,.13); }.menu-section { display:grid; gap:9px; padding:6px 7px 11px; border-bottom:1px solid #eef0f2; color:#667085; font-size:12px; }.theme-options { display:grid; grid-template-columns:repeat(2,minmax(0,1fr)); gap:6px; }.theme-option { display:flex; min-width:0; height:31px; align-items:center; gap:7px; border:1px solid transparent; border-radius:5px; padding:0 7px; background:transparent; color:#667085; font-size:12px; text-align:left; }.theme-option i { display:block; width:14px; height:14px; flex:0 0 14px; border-radius:50%; }.theme-option.active { border-color:var(--zwx-primary); background:var(--zwx-primary-soft); color:var(--zwx-primary); font-weight:650; }.theme-option.active i { box-shadow:0 0 0 2px #fff,0 0 0 3px currentColor; }.knowledge-link { display:flex; width:100%; align-items:center; gap:8px; margin-top:5px; border:0; border-radius:5px; padding:9px 7px; background:transparent; color:#475467; font-size:13px; text-align:left; }.knowledge-link span { flex:1; }.knowledge-link:hover { background:#f5f7fa; color:var(--zwx-primary); }.desktop-api-settings { display:grid; gap:6px; margin-top:5px; border-top:1px solid #eef0f2; padding:10px 7px 3px; color:#667085; font-size:12px; }.desktop-api-settings > div { display:flex; gap:5px; }.desktop-api-settings input { min-width:0; flex:1; height:29px; border:1px solid #d9dee5; border-radius:5px; padding:0 7px; outline:0; font:inherit; font-size:11px; }.desktop-api-settings input:focus { border-color:var(--zwx-primary); }.desktop-api-settings button { border:0; border-radius:5px; padding:0 8px; background:var(--zwx-primary); color:#fff; font-size:11px; }
</style>
