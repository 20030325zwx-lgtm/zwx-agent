<template>
  <div ref="menuRoot" class="agent-settings">
    <button class="settings-trigger" type="button" :aria-label="triggerLabel" :aria-expanded="open" @click="open = !open">
      <Settings2 :size="17" /><span v-if="showTriggerLabel">{{ triggerLabel }}</span>
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
  { key: 'blue', label: '海蓝', primary: '#007aff', dark: '#0062cc', soft: '#e9f2ff', ring: 'rgba(0, 122, 255, .22)' },
  { key: 'emerald', label: '翡翠绿', primary: '#2aa254', dark: '#1f8744', soft: '#e8f8ee', ring: 'rgba(48, 209, 88, .24)' },
  { key: 'rose', label: '玫瑰红', primary: '#e0315f', dark: '#c2244e', soft: '#ffeaf0', ring: 'rgba(255, 55, 95, .22)' },
  { key: 'violet', label: '紫罗兰', primary: '#8250df', dark: '#6639ba', soft: '#f1ebff', ring: 'rgba(175, 82, 222, .22)' }
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
.agent-settings { position: relative; display: flex; width: 190px; justify-content: flex-end; }

.settings-trigger {
  display: grid;
  width: 32px;
  height: 32px;
  place-items: center;
  border: 0;
  border-radius: 10px;
  background: var(--sk-fill);
  color: var(--sk-label-2);
}

.settings-trigger:has(span) { display: flex; width: auto; gap: 7px; padding: 0 12px; font-size: 12px; font-weight: 600; }

.settings-trigger:hover, .settings-trigger[aria-expanded="true"] { background: var(--zwx-primary-soft); color: var(--zwx-primary); }

/* macOS NSPopover：毛玻璃浮层 */
.settings-menu {
  position: absolute;
  z-index: 20;
  top: 42px;
  right: 0;
  width: 340px;
  max-height: min(680px, calc(100vh - 90px));
  overflow-y: auto;
  border: 1px solid var(--sk-separator);
  border-radius: 16px;
  padding: 10px;
  background: var(--sk-material-strong);
  backdrop-filter: var(--sk-blur);
  -webkit-backdrop-filter: var(--sk-blur);
  box-shadow: var(--sk-shadow-pop);
  transform-origin: top right;
  animation: menu-in 0.18s cubic-bezier(0.32, 0.72, 0, 1);
}

@keyframes menu-in {
  from { opacity: 0; transform: scale(0.96) translateY(-4px); }
  to { opacity: 1; transform: scale(1) translateY(0); }
}

.menu-section {
  display: grid;
  gap: 9px;
  padding: 8px 8px 13px;
  border-bottom: 1px solid var(--sk-separator);
  color: var(--sk-label-2);
  font-size: 12px;
}

.theme-options { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 6px; }

.theme-option {
  display: flex;
  min-width: 0;
  height: 33px;
  align-items: center;
  gap: 8px;
  border: 0;
  border-radius: 9px;
  padding: 0 9px;
  background: var(--sk-fill);
  color: var(--sk-label-2);
  font-size: 12px;
  text-align: left;
}

.theme-option i { display: block; width: 14px; height: 14px; flex: 0 0 14px; border-radius: 50%; box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.4); }

.theme-option.active { background: var(--zwx-primary-soft); color: var(--zwx-primary); font-weight: 650; }

.theme-option.active i { box-shadow: 0 0 0 2px #fff, 0 0 0 3.5px currentColor; }

.knowledge-link {
  display: flex;
  width: 100%;
  align-items: center;
  gap: 8px;
  margin-top: 6px;
  border: 0;
  border-radius: 9px;
  padding: 10px 9px;
  background: transparent;
  color: var(--sk-label);
  font-size: 13px;
  text-align: left;
}

.knowledge-link span { flex: 1; }
.knowledge-link :deep(svg) { color: var(--zwx-primary); }
.knowledge-link :deep(svg:last-child) { color: var(--sk-label-3); }

.knowledge-link:hover { background: var(--sk-fill); }

.desktop-api-settings {
  display: grid;
  gap: 9px;
  margin-top: 6px;
  border-top: 1px solid var(--sk-separator);
  padding: 13px 8px 4px;
  color: var(--sk-label-2);
  font-size: 12px;
}

.desktop-api-settings strong { color: var(--sk-label); font-size: 12px; }

.desktop-api-settings label { display: grid; gap: 5px; }

.desktop-api-settings input, .desktop-api-settings select {
  height: 32px;
  border: 0;
  border-radius: 8px;
  padding: 0 9px;
  background: var(--sk-fill);
  color: var(--sk-label);
  font-size: 12px;
  outline: none;
}

.desktop-api-settings input:focus, .desktop-api-settings select:focus { background: var(--sk-surface); box-shadow: 0 0 0 3px var(--zwx-primary-ring); }

.desktop-api-settings p { margin: 2px 0 0; color: var(--sk-label-3); font-size: 11px; line-height: 1.55; }

.settings-error { color: var(--sk-red) !important; }

.save-desktop-settings {
  height: 34px;
  margin-top: 3px;
  border: 0;
  border-radius: 9px;
  background: var(--zwx-primary);
  color: #fff;
  font-size: 12px;
  font-weight: 650;
}

.save-desktop-settings:hover:not(:disabled) { filter: brightness(1.07); }

.save-desktop-settings:active:not(:disabled) { transform: scale(0.98); }
</style>
