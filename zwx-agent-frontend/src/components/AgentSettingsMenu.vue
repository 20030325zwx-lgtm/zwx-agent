<template>
  <div ref="menuRoot" class="agent-settings">
    <button class="settings-trigger" type="button" :aria-label="triggerLabel" :aria-expanded="open" @click="open = !open">
      <Settings2 :size="18" /><span v-if="showTriggerLabel">{{ triggerLabel }}</span>
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
      <button class="knowledge-link" type="button" role="menuitem" @click="openSkills">
        <Sparkles :size="16" />
        <span>内置 Skill 配置</span>
        <ChevronRight :size="15" />
      </button>
      <div v-if="desktopApi" class="desktop-api-settings">
        <strong>桌面端服务配置</strong>
        <label>运行模式<select v-model="desktopSettings.backendMode"><option value="remote">连接已有服务</option><option value="local">启动本机服务</option></select></label>
        <label>API 服务地址<input v-model="desktopSettings.apiBaseUrl" type="url" placeholder="https://example.com/api" /></label>
        <label>本机后端端口<input v-model.number="desktopSettings.backendPort" type="number" min="1024" max="65535" /></label>
        <label>PostgreSQL JDBC 地址<input v-model="desktopSettings.postgresUrl" type="text" placeholder="jdbc:postgresql://127.0.0.1:5432/zwx_agent" /></label>
        <label>PostgreSQL 用户名<input v-model="desktopSettings.postgresUsername" type="text" /></label>
        <label>PostgreSQL 密码<input v-model="desktopSettings.secrets.postgresPassword" type="password" placeholder="*******" /></label>
        <label>DashScope API Key<input v-model="desktopSettings.secrets.dashscopeApiKey" type="password" placeholder="*******" /></label>
        <label>联网搜索 API Key<input v-model="desktopSettings.secrets.searchApiKey" type="password" placeholder="*******" /></label>
        <label>OSS Endpoint<input v-model="desktopSettings.ossEndpoint" type="url" placeholder="https://oss-cn-hangzhou.aliyuncs.com" /></label>
        <label>OSS Bucket<input v-model="desktopSettings.ossBucket" type="text" /></label>
        <label>OSS Access Key ID<input v-model="desktopSettings.secrets.ossAccessKeyId" type="password" placeholder="*******" /></label>
        <label>OSS Access Key Secret<input v-model="desktopSettings.secrets.ossAccessKeySecret" type="password" placeholder="*******" /></label>
        <label>Tika 服务地址<input v-model="desktopSettings.tikaBaseUrl" type="url" placeholder="http://127.0.0.1:9998" /></label>
        <p>切换为“启动本机服务”后，保存的本机配置才会用于启动后端。Bucket 与非密钥配置保存在本机；密钥保持加密且不会回显或因空输入被清除。</p>
        <p v-if="desktopSettings.backendMode === 'remote'">模型密钥由已连接服务的部署环境管理。</p>
        <p v-if="desktopSettingsError" class="settings-error">{{ desktopSettingsError }}</p>
        <button class="save-desktop-settings" type="button" @click="saveDesktopSettings">保存并重启</button>
      </div>
    </div>
  </div>
</template>

<script setup>
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ChevronRight, Database, Settings2, Sparkles } from 'lucide-vue-next'

const props = defineProps({
  agentKey: { type: String, required: true },
  defaultTheme: { type: String, default: 'blue' },
  triggerLabel: { type: String, default: '智能体设置' },
  showTriggerLabel: { type: Boolean, default: false }
})
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
const desktopSettings = ref(desktopApi?.settings || null)
const desktopSettingsError = ref('')
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
const openSkills = () => router.push({ name: 'SkillSettings', query: { agentKey: props.agentKey } })
const saveDesktopSettings = async () => {
  desktopSettingsError.value = ''
  try { await desktopApi.saveSettings(desktopSettings.value) }
  catch (error) { desktopSettingsError.value = error?.message || '保存配置失败。' }
}
const closeOnOutsideClick = event => { if (!menuRoot.value?.contains(event.target)) open.value = false }
onMounted(() => { applyTheme(activeTheme.value); document.addEventListener('click', closeOnOutsideClick) })
onBeforeUnmount(() => document.removeEventListener('click', closeOnOutsideClick))
</script>

<style scoped>
.agent-settings { position:relative; display:flex; width:190px; justify-content:flex-end; }.settings-trigger { display:grid; width:32px; height:32px; place-items:center; border:1px solid #e4e7ec; border-radius:7px; background:#fff; color:#667085; }.settings-trigger:has(span) { display:flex; width:auto; gap:7px; padding:0 10px; font-size:12px; }.settings-trigger:hover,.settings-trigger[aria-expanded="true"] { border-color:var(--zwx-primary); background:var(--zwx-primary-soft); color:var(--zwx-primary); }.settings-menu { position:absolute; z-index:20; top:40px; right:0; width:340px; max-height:min(680px,calc(100vh - 90px)); overflow-y:auto; border:1px solid #e5e7eb; border-radius:8px; padding:8px; background:#fff; box-shadow:0 14px 30px rgba(15,23,42,.13); }.menu-section { display:grid; gap:9px; padding:6px 7px 11px; border-bottom:1px solid #eef0f2; color:#667085; font-size:12px; }.theme-options { display:grid; grid-template-columns:repeat(2,minmax(0,1fr)); gap:6px; }.theme-option { display:flex; min-width:0; height:31px; align-items:center; gap:7px; border:1px solid transparent; border-radius:5px; padding:0 7px; background:transparent; color:#667085; font-size:12px; text-align:left; }.theme-option i { display:block; width:14px; height:14px; flex:0 0 14px; border-radius:50%; }.theme-option.active { border-color:var(--zwx-primary); background:var(--zwx-primary-soft); color:var(--zwx-primary); font-weight:650; }.theme-option.active i { box-shadow:0 0 0 2px #fff,0 0 0 3px currentColor; }.knowledge-link { display:flex; width:100%; align-items:center; gap:8px; margin-top:5px; border:0; border-radius:5px; padding:9px 7px; background:transparent; color:#475467; font-size:13px; text-align:left; }.knowledge-link span { flex:1; }.knowledge-link:hover { background:#f5f7fa; color:var(--zwx-primary); }.desktop-api-settings { display:grid; gap:8px; margin-top:5px; border-top:1px solid #eef0f2; padding:11px 7px 3px; color:#667085; font-size:12px; }.desktop-api-settings strong { color:#344054; font-size:13px; }.desktop-api-settings label { display:grid; gap:4px; color:#667085; }.desktop-api-settings input,.desktop-api-settings select { min-width:0; width:100%; height:30px; border:1px solid #d9dee5; border-radius:5px; padding:0 7px; outline:0; background:#fff; color:#344054; font:inherit; font-size:12px; }.desktop-api-settings input:focus,.desktop-api-settings select:focus { border-color:var(--zwx-primary); }.desktop-api-settings p { margin:0; color:#98a2b3; font-size:11px; line-height:1.45; }.desktop-api-settings .settings-error { color:#b42318; }.save-desktop-settings { height:31px; border:0; border-radius:5px; background:var(--zwx-primary); color:#fff; font-size:12px; font-weight:650; }
</style>
