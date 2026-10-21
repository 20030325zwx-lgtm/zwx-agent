<template>
  <main class="model-page">
    <div class="page-body">
      <div class="content-heading">
        <div>
          <span class="eyebrow">MODEL CONFIGURATION</span>
          <h1>模型设置</h1>
          <p>配置个人或系统默认的对话与视觉模型，未配置时自动使用部署环境变量。</p>
        </div>
        <div class="header-side">
          <span v-if="canManageSystem" class="admin-badge">管理员</span>
          <span class="header-note">密钥加密保存，仅显示末位</span>
        </div>
      </div>
      <aside class="model-rail">
        <div class="rail-heading">
          <span>模型</span>
          <small>{{ models.length }}</small>
        </div>
        <button v-for="model in models" :key="model.key" class="rail-item"
          :class="{ active: activeKey === model.key }" type="button" @click="activeKey = model.key">
          <span class="rail-icon"><Bot :size="17" /></span>
          <span class="rail-copy">
            <strong>{{ model.label }}</strong>
            <small>{{ providerLabel(model.effective.provider) }} · {{ model.effective.modelName }}</small>
          </span>
          <span class="source-dot" :class="sourceClass(model.effective.source)" :title="sourceLabel(model.effective.source)"></span>
        </button>
        <div class="rail-note">
          <strong>生效顺序</strong>
          <div class="precedence-line"><span>个人配置</span><i>›</i><span>系统默认</span><i>›</i><span>环境变量</span></div>
        </div>
      </aside>

      <section class="model-panel">
        <div v-if="loading" class="state">正在读取模型配置...</div>
        <div v-else-if="loadError" class="state error-state"><AlertCircle :size="18" />{{ loadError }}</div>
        <template v-else-if="activeModel">
          <header class="panel-header">
            <span class="panel-icon"><Bot :size="21" /></span>
            <div class="panel-copy">
              <div class="panel-title">
                <h2>{{ activeModel.label }}</h2>
                <span class="source-badge" :class="sourceClass(activeModel.effective.source)">{{ sourceLabel(activeModel.effective.source) }}</span>
              </div>
              <p>{{ activeModel.description }}</p>
            </div>
          </header>

          <div class="effective">
            <div class="effective-cell">
              <span>当前模型</span>
              <strong>{{ providerLabel(activeModel.effective.provider) }} · {{ activeModel.effective.modelName }}</strong>
            </div>
            <div class="effective-cell">
              <span>服务地址</span>
              <code>{{ activeModel.effective.baseUrl || '默认地址' }}</code>
            </div>
            <div class="effective-cell">
              <span>API Key</span>
              <strong class="mono">{{ activeModel.effective.hasSecret ? activeModel.effective.secretHint : '未配置' }}</strong>
            </div>
            <div class="effective-cell">
              <span>来源</span>
              <strong>{{ sourceLabel(activeModel.effective.source) }}</strong>
            </div>
          </div>

          <div v-if="canManageSystem" class="scope-tabs" role="tablist" aria-label="配置作用域">
            <button type="button" role="tab" :aria-selected="activeScope === 'USER'" :class="{ active: activeScope === 'USER' }" @click="activeScope = 'USER'">我的配置</button>
            <button type="button" role="tab" :aria-selected="activeScope === 'SYSTEM'" :class="{ active: activeScope === 'SYSTEM' }" @click="activeScope = 'SYSTEM'">系统默认</button>
          </div>

          <form v-if="activeModel.overridable && activeScope === 'USER'" class="editor" @submit.prevent="saveUser(activeModel)">
            <div class="editor-heading">
              <div><strong>我的配置</strong><span>仅影响当前账号；留空字段会继续继承下级配置。</span></div>
              <button class="link-button" type="button" :disabled="!activeModel.userOverride || busy" @click="resetUser(activeModel)">恢复继承</button>
            </div>
            <div class="field-grid">
              <label>供应商
                <select v-model="forms[activeModel.key].provider">
                  <option value="dashscope">DashScope</option>
                  <option value="openai-compatible">OpenAI 兼容接口</option>
                </select>
              </label>
              <label>模型名称
                <input v-model.trim="forms[activeModel.key].modelName" maxlength="128" :placeholder="activeModel.effective.modelName || '例如 qwen-plus'" />
              </label>
              <label class="wide">服务地址
                <input v-model.trim="forms[activeModel.key].baseUrl" type="url" maxlength="512" :placeholder="activeModel.effective.baseUrl || '留空使用供应商默认地址'" />
              </label>
              <label class="wide">API Key
                <input v-model="forms[activeModel.key].apiKey" type="password" autocomplete="new-password" maxlength="1024"
                  :placeholder="activeModel.userOverride?.hasSecret ? `已保存 ${activeModel.userOverride.secretHint}，留空保持不变` : '留空则继承上级配置'" />
              </label>
            </div>
            <label v-if="activeModel.userOverride?.hasSecret" class="check-row">
              <input v-model="forms[activeModel.key].clearApiKey" type="checkbox" />清除已保存的 API Key
            </label>
            <p v-if="formErrors[activeModel.key]" class="form-error"><AlertCircle :size="14" />{{ formErrors[activeModel.key] }}</p>
            <p v-if="testResults[activeModel.key]" class="test-result" :class="testResults[activeModel.key].ok ? 'ok' : 'fail'">
              <CheckCircle2 v-if="testResults[activeModel.key].ok" :size="14" />
              <AlertCircle v-else :size="14" />
              {{ testResults[activeModel.key].message }} · {{ testResults[activeModel.key].latencyMs }}ms
            </p>
            <div class="editor-actions">
              <button type="button" class="ghost-button" :disabled="busy" @click="test(activeModel.key, 'USER')">测试当前生效</button>
              <button type="submit" class="primary-button" :disabled="busy">{{ busy ? '处理中...' : '保存我的配置' }}</button>
            </div>
          </form>

          <form v-else-if="activeModel.overridable && canManageSystem" class="editor system-editor" @submit.prevent="saveSystem(activeModel)">
            <div class="editor-heading">
              <div><strong>系统默认</strong><span>对未单独配置的用户生效；保存后立即影响新请求。</span></div>
              <button class="link-button" type="button" :disabled="!activeModel.systemDefault || busy" @click="resetSystem(activeModel)">清除系统默认</button>
            </div>
            <div class="field-grid">
              <label>供应商
                <select v-model="systemForms[activeModel.key].provider">
                  <option value="dashscope">DashScope</option>
                  <option value="openai-compatible">OpenAI 兼容接口</option>
                </select>
              </label>
              <label>模型名称
                <input v-model.trim="systemForms[activeModel.key].modelName" maxlength="128" :placeholder="activeModel.effective.modelName" />
              </label>
              <label class="wide">服务地址
                <input v-model.trim="systemForms[activeModel.key].baseUrl" type="url" maxlength="512" :placeholder="activeModel.effective.baseUrl || '留空使用环境变量或供应商默认地址'" />
              </label>
              <label class="wide">API Key
                <input v-model="systemForms[activeModel.key].apiKey" type="password" autocomplete="new-password" maxlength="1024"
                  :placeholder="activeModel.systemDefault?.hasSecret ? `已保存 ${activeModel.systemDefault.secretHint}，留空保持不变` : '留空则继承环境变量'" />
              </label>
            </div>
            <label v-if="activeModel.systemDefault?.hasSecret" class="check-row">
              <input v-model="systemForms[activeModel.key].clearApiKey" type="checkbox" />清除已保存的 API Key
            </label>
            <p v-if="systemErrors[activeModel.key]" class="form-error"><AlertCircle :size="14" />{{ systemErrors[activeModel.key] }}</p>
            <p v-if="systemResults[activeModel.key]" class="test-result" :class="systemResults[activeModel.key].ok ? 'ok' : 'fail'">
              <CheckCircle2 v-if="systemResults[activeModel.key].ok" :size="14" />
              <AlertCircle v-else :size="14" />
              {{ systemResults[activeModel.key].message }} · {{ systemResults[activeModel.key].latencyMs }}ms
            </p>
            <div class="editor-actions">
              <button type="button" class="ghost-button" :disabled="busy" @click="test(activeModel.key, 'SYSTEM')">测试系统默认</button>
              <button type="submit" class="primary-button" :disabled="busy">{{ busy ? '处理中...' : '保存系统默认' }}</button>
            </div>
          </form>

          <p v-if="!activeModel.overridable" class="locked-note"><LockKeyhole :size="14" />该模型由部署环境管理，避免向量维度与索引不一致。</p>
        </template>
      </section>
    </div>
  </main>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { AlertCircle, Bot, CheckCircle2, LockKeyhole } from 'lucide-vue-next'
import {
  listModelSettings,
  resetSystemModelSettings,
  resetUserModelSettings,
  saveSystemModelSettings,
  saveUserModelSettings,
  testModelSettings
} from '../api'

const models = ref([])
const canManageSystem = ref(false)
const loading = ref(true)
const busy = ref(false)
const loadError = ref('')
const activeKey = ref('chat')
const activeScope = ref('USER')
const forms = reactive({})
const systemForms = reactive({})
const formErrors = reactive({})
const systemErrors = reactive({})
const testResults = reactive({})
const systemResults = reactive({})

const activeModel = computed(() =>
  models.value.find(model => model.key === activeKey.value) || models.value[0] || null
)

const toForm = (override, effective) => ({
  provider: override?.provider || effective?.provider || 'dashscope',
  baseUrl: override?.baseUrl || effective?.baseUrl || '',
  modelName: override?.modelName || effective?.modelName || '',
  apiKey: '',
  clearApiKey: false
})

const initForms = () => {
  models.value.forEach(model => {
    forms[model.key] = toForm(model.userOverride, model.effective)
    systemForms[model.key] = toForm(model.systemDefault, model.effective)
    formErrors[model.key] = ''
    systemErrors[model.key] = ''
  })
  if (!models.value.some(model => model.key === activeKey.value)) {
    activeKey.value = models.value[0]?.key || 'chat'
  }
}

const load = async () => {
  loading.value = true
  loadError.value = ''
  try {
    const data = await listModelSettings()
    models.value = data.models || []
    canManageSystem.value = !!data.canManageSystem
    initForms()
  } catch (error) {
    loadError.value = error?.response?.data?.message || '读取模型配置失败。'
  } finally {
    loading.value = false
  }
}

const payloadOf = form => ({
  provider: form.provider,
  baseUrl: form.baseUrl || null,
  modelName: form.modelName || null,
  apiKey: form.apiKey?.trim() ? form.apiKey.trim() : null,
  clearApiKey: !!form.clearApiKey,
  enabled: true
})

const saveUser = async model => {
  busy.value = true
  formErrors[model.key] = ''
  try {
    await saveUserModelSettings(model.key, payloadOf(forms[model.key]))
    await load()
  } catch (error) {
    formErrors[model.key] = error?.response?.data?.message || '保存失败，请检查模型名称与服务地址。'
  } finally {
    busy.value = false
  }
}

const saveSystem = async model => {
  busy.value = true
  systemErrors[model.key] = ''
  try {
    await saveSystemModelSettings(model.key, payloadOf(systemForms[model.key]))
    await load()
  } catch (error) {
    systemErrors[model.key] = error?.response?.data?.message || '保存系统默认失败。'
  } finally {
    busy.value = false
  }
}

const resetUser = async model => {
  busy.value = true
  formErrors[model.key] = ''
  try {
    await resetUserModelSettings(model.key)
    await load()
  } catch (error) {
    formErrors[model.key] = error?.response?.data?.message || '恢复继承失败。'
  } finally {
    busy.value = false
  }
}

const resetSystem = async model => {
  busy.value = true
  systemErrors[model.key] = ''
  try {
    await resetSystemModelSettings(model.key)
    await load()
  } catch (error) {
    systemErrors[model.key] = error?.response?.data?.message || '清除系统默认失败。'
  } finally {
    busy.value = false
  }
}

const test = async (key, scope) => {
  busy.value = true
  const target = scope === 'SYSTEM' ? systemResults : testResults
  delete target[key]
  try {
    target[key] = await testModelSettings(key, scope)
  } catch (error) {
    target[key] = { ok: false, message: error?.response?.data?.message || '测试请求失败', latencyMs: 0 }
  } finally {
    busy.value = false
  }
}

const providerLabel = provider => provider === 'openai-compatible' ? 'OpenAI 兼容' : 'DashScope'
const sourceLabel = source => ({ USER: '个人配置', SYSTEM: '系统默认', ENVIRONMENT: '环境变量' })[source] || source
const sourceClass = source => (source || '').toLowerCase()

onMounted(load)
</script>

<style scoped>
.model-page { color: var(--sk-label); }

.content-heading {
  display: flex;
  grid-column: 1 / -1;
  align-items: flex-end;
  justify-content: space-between;
  gap: 20px;
  padding: 8px 2px 2px;
}

.content-heading h1 { margin: 4px 0 3px; font-size: 26px; font-weight: 800; letter-spacing: -0.025em; }
.content-heading p { margin: 0; color: var(--sk-label-2); font-size: 12px; }
.eyebrow { color: var(--zwx-primary); font-size: 10px; font-weight: 700; letter-spacing: 0.12em; }
.header-side { display: flex; flex-direction: column; align-items: flex-end; gap: 5px; }
.header-note { color: var(--sk-label-3); font-size: 11px; }
.admin-badge { border-radius: 999px; padding: 3px 9px; background: var(--zwx-primary-soft); color: var(--zwx-primary); font-size: 10px; font-weight: 700; }

.page-body {
  display: grid;
  grid-template-columns: 248px minmax(0, 1fr);
  gap: 24px;
  max-width: 1180px;
  margin: 0 auto;
  padding: 24px 32px 64px;
}

.model-rail {
  position: sticky;
  top: 92px;
  align-self: start;
  display: grid;
  gap: 6px;
  border: 1px solid var(--sk-separator);
  border-radius: 16px;
  padding: 12px;
  background: var(--sk-surface);
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.03);
}

.rail-heading { display: flex; align-items: center; justify-content: space-between; padding: 4px 8px 10px; color: var(--sk-label-2); font-size: 11px; font-weight: 700; letter-spacing: 0.06em; }
.rail-heading small { border-radius: 999px; padding: 1px 7px; background: var(--sk-fill); color: var(--sk-label-2); font-size: 10px; }

.rail-item {
  display: flex;
  min-width: 0;
  align-items: center;
  gap: 10px;
  border: 0;
  border-radius: 11px;
  padding: 10px;
  background: transparent;
  color: var(--sk-label);
  text-align: left;
}

.rail-item:hover { background: var(--sk-fill); }
.rail-item.active { background: var(--zwx-primary-soft); color: var(--zwx-primary); }
.rail-icon { display: grid; width: 32px; height: 32px; flex: 0 0 32px; place-items: center; border-radius: 10px; background: var(--sk-fill); color: var(--sk-label-2); }
.rail-item.active .rail-icon { background: rgba(255, 255, 255, 0.7); color: var(--zwx-primary); }
.rail-copy { display: grid; min-width: 0; gap: 2px; }
.rail-copy strong { font-size: 13px; font-weight: 700; }
.rail-copy small { overflow: hidden; color: var(--sk-label-3); font-size: 10px; text-overflow: ellipsis; white-space: nowrap; }
.source-dot { width: 7px; height: 7px; flex: 0 0 7px; margin-left: auto; border-radius: 50%; background: var(--sk-label-3); }
.source-dot.user { background: #34c759; }
.source-dot.system { background: var(--zwx-primary); }
.source-dot.environment { background: var(--sk-label-3); }

.rail-note { display: grid; gap: 7px; margin-top: 6px; border-top: 1px solid var(--sk-separator); padding: 12px 8px 4px; color: var(--sk-label-3); font-size: 10px; }
.rail-note strong { color: var(--sk-label-2); font-size: 10px; font-weight: 700; }
.precedence-line { display: flex; flex-wrap: wrap; align-items: center; gap: 4px; }
.precedence-line span { border-radius: 5px; padding: 2px 5px; background: var(--sk-fill); color: var(--sk-label-2); }
.precedence-line i { font-style: normal; }

.model-panel { min-width: 0; border: 1px solid var(--sk-separator); border-radius: 18px; padding: 22px; background: var(--sk-surface); box-shadow: var(--sk-shadow-card); }

.panel-header { display: flex; gap: 12px; }
.panel-icon { display: grid; width: 44px; height: 44px; flex: 0 0 44px; place-items: center; border-radius: 13px; background: var(--zwx-primary-soft); color: var(--zwx-primary); }
.panel-copy { min-width: 0; }
.panel-title { display: flex; align-items: center; gap: 9px; }
.panel-title h2 { margin: 0; font-size: 18px; font-weight: 750; letter-spacing: -0.01em; }
.panel-copy p { margin: 5px 0 0; color: var(--sk-label-2); font-size: 12px; }
.source-badge { border-radius: 6px; padding: 2px 7px; font-size: 10px; font-weight: 700; }
.source-badge.user { background: rgba(52, 199, 89, 0.14); color: #1f9d4d; }
.source-badge.system { background: var(--zwx-primary-soft); color: var(--zwx-primary); }
.source-badge.environment { background: var(--sk-fill); color: var(--sk-label-2); }

.effective { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 1px; margin-top: 18px; overflow: hidden; border: 1px solid var(--sk-separator); border-radius: 13px; background: var(--sk-separator); }
.effective-cell { display: grid; min-width: 0; gap: 4px; padding: 12px 14px; background: var(--sk-fill); }
.effective-cell span { color: var(--sk-label-3); font-size: 10px; font-weight: 650; letter-spacing: 0.04em; }
.effective-cell strong { font-size: 13px; font-weight: 700; }
.effective-cell code { overflow: hidden; color: var(--sk-label-2); font-size: 11px; text-overflow: ellipsis; white-space: nowrap; }
.effective-cell .mono { font-family: ui-monospace, SFMono-Regular, Menlo, monospace; font-size: 12px; }

.scope-tabs { display: flex; gap: 4px; margin-top: 20px; border-bottom: 1px solid var(--sk-separator); }
.scope-tabs button { border: 0; border-bottom: 2px solid transparent; padding: 9px 12px; background: transparent; color: var(--sk-label-2); font-size: 12px; font-weight: 650; }
.scope-tabs button.active { border-bottom-color: var(--zwx-primary); color: var(--zwx-primary); }

.editor { display: grid; gap: 13px; margin-top: 18px; }
.editor-heading { display: flex; align-items: flex-start; justify-content: space-between; gap: 14px; }
.editor-heading strong { display: block; font-size: 13px; font-weight: 700; }
.editor-heading span { display: block; margin-top: 3px; color: var(--sk-label-3); font-size: 11px; }
.link-button { border: 0; background: transparent; color: var(--zwx-primary); font-size: 12px; white-space: nowrap; }
.link-button:disabled { color: var(--sk-label-3); cursor: default; }

.field-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 11px; }
.field-grid label { display: grid; gap: 5px; color: var(--sk-label-2); font-size: 11px; font-weight: 650; }
.field-grid label.wide { grid-column: 1 / -1; }
.field-grid input, .field-grid select { height: 38px; border: 0; border-radius: 10px; padding: 0 11px; background: var(--sk-fill); color: var(--sk-label); font-size: 13px; outline: none; }
.field-grid input:focus, .field-grid select:focus { background: var(--sk-surface); box-shadow: 0 0 0 3px var(--zwx-primary-ring); }

.check-row { display: flex; align-items: center; gap: 7px; color: var(--sk-label-2); font-size: 11px; }
.form-error, .test-result { display: flex; align-items: center; gap: 6px; margin: 0; font-size: 12px; }
.form-error { color: var(--sk-red); }
.test-result.ok { color: #1f9d4d; }
.test-result.fail { color: var(--sk-red); }

.editor-actions { display: flex; justify-content: flex-end; gap: 9px; border-top: 1px solid var(--sk-separator); padding-top: 14px; }
.ghost-button, .primary-button { height: 36px; border: 0; border-radius: 10px; padding: 0 15px; font-size: 12px; font-weight: 650; }
.ghost-button { background: var(--sk-fill); color: var(--sk-label); }
.ghost-button:hover:not(:disabled) { background: var(--sk-fill-strong); }
.primary-button { background: linear-gradient(180deg, #2590ff, var(--zwx-primary)); color: #fff; box-shadow: 0 5px 14px var(--zwx-primary-ring); }
.primary-button:hover:not(:disabled) { filter: brightness(1.06); }
.ghost-button:disabled, .primary-button:disabled { opacity: 0.5; }

.system-editor { border: 1px solid var(--zwx-primary-ring); border-radius: 14px; padding: 16px; background: var(--zwx-primary-soft); }
.system-editor .field-grid input, .system-editor .field-grid select { background: var(--sk-surface); }
.system-editor .editor-actions { border-top-color: var(--zwx-primary-ring); }
.locked-note { display: flex; align-items: center; gap: 6px; margin: 18px 0 0; color: var(--sk-label-3); font-size: 12px; }

.state { padding: 64px 0; color: var(--sk-label-2); font-size: 13px; text-align: center; }
.error-state { display: flex; align-items: center; justify-content: center; gap: 7px; color: var(--sk-red); }

@media (max-width: 860px) {
  .page-body { grid-template-columns: 1fr; padding: 18px; }
  .model-rail { position: static; grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .rail-heading, .rail-note { grid-column: 1 / -1; }
  .model-panel { padding: 18px; }
  .field-grid, .effective { grid-template-columns: 1fr; }
}
</style>
