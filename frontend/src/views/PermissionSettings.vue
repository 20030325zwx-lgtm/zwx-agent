<template>
  <main class="permission-page">
    <header class="permission-header">
      <div class="permission-header-inner">
        <div>
          <span class="eyebrow">PERMISSION MODES</span>
          <h1>权限模式</h1>
          <p>决定智能体能自主执行哪些操作：只读分析、文件产出、命令执行与高危操作分级放行。</p>
        </div>
        <span class="sync-note"><ShieldCheck :size="15" />默认模式</span>
      </div>
    </header>

    <section class="permission-shell">
      <div v-if="loading" class="state">正在读取权限设置...</div>
      <div v-else-if="error" class="state error">{{ error }} <button type="button" @click="load">重试</button></div>
      <template v-else>
        <div class="mode-grid">
          <button v-for="option in modeOptions" :key="option.value" type="button" class="mode-card"
            :class="{ active: mode === option.value }" :disabled="saving" @click="selectMode(option.value)">
            <span class="mode-title">{{ option.label }}<small>{{ option.value }}</small></span>
            <span class="mode-hint">{{ option.hint }}</span>
          </button>
        </div>

        <div class="rule-section">
          <div class="section-title">
            <h2>工具规则</h2>
            <small>规则优先于模式：DENY 优先于 ALLOW；支持 <code>mcp:*</code> 前缀通配。</small>
          </div>
          <div class="rule-add">
            <input v-model="newPattern" type="text" maxlength="128" placeholder="工具名或模式，如 executeTerminalCommand、mcp:*" @keydown.enter="addRule" />
            <select v-model="newEffect">
              <option value="ALLOW">允许</option>
              <option value="DENY">拒绝</option>
            </select>
            <button type="button" :disabled="saving || !newPattern.trim()" @click="addRule">添加规则</button>
          </div>
          <div v-if="!rules.length" class="state compact">暂无规则，全部按权限模式默认判定。</div>
          <ul v-else class="rule-list">
            <li v-for="rule in rules" :key="rule.id" class="rule-row">
              <code>{{ rule.toolPattern }}</code>
              <span class="effect" :class="rule.effect.toLowerCase()">{{ rule.effect === 'ALLOW' ? '允许' : '拒绝' }}</span>
              <button type="button" class="danger" @click="removeRule(rule)">删除</button>
            </li>
          </ul>
        </div>
      </template>
    </section>
  </main>
</template>

<script setup>
import { ref } from 'vue'
import { ShieldCheck } from 'lucide-vue-next'
import { addPermissionRule, deletePermissionRule, getPermissionSettings, savePermissionMode } from '../api'

const modeOptions = [
  { value: 'PLAN', label: '只读规划', hint: '仅允许只读工具，写操作直接拒绝，适合调研与分析。' },
  { value: 'BUILD', label: '严格', hint: '文件写入与执行操作都需要你确认。' },
  { value: 'EDIT', label: '编辑（默认）', hint: '文件产出免确认，执行类工具仍需确认，与原有行为一致。' },
  { value: 'GUARDED', label: '守护', hint: '常规操作免确认，仅终端、Git 提交、外部库等高危操作逐次确认。' },
  { value: 'YOLO', label: '全自动', hint: '全部放行，适合隔离环境下的无人值守与评测。' }
]

const loading = ref(true)
const saving = ref(false)
const error = ref('')
const mode = ref('EDIT')
const rules = ref([])
const newPattern = ref('')
const newEffect = ref('DENY')

const load = async () => {
  loading.value = true
  error.value = ''
  try {
    const settings = await getPermissionSettings()
    mode.value = settings.mode || 'EDIT'
    rules.value = settings.rules || []
  } catch (cause) {
    error.value = cause?.response?.data?.message || '暂时无法读取权限设置。'
  } finally {
    loading.value = false
  }
}

const applySettings = settings => {
  if (!settings) return
  mode.value = settings.mode || mode.value
  rules.value = settings.rules || []
}

const selectMode = async value => {
  if (saving.value || value === mode.value) return
  saving.value = true
  error.value = ''
  try {
    applySettings(await savePermissionMode(value))
    localStorage.setItem('zwx-permission-mode', value)
  } catch (cause) {
    error.value = cause?.response?.data?.message || '保存失败。'
  } finally {
    saving.value = false
  }
}

const addRule = async () => {
  if (!newPattern.value.trim()) return
  saving.value = true
  error.value = ''
  try {
    applySettings(await addPermissionRule(newPattern.value.trim(), newEffect.value))
    newPattern.value = ''
  } catch (cause) {
    error.value = cause?.response?.data?.message || '添加规则失败。'
  } finally {
    saving.value = false
  }
}

const removeRule = async rule => {
  saving.value = true
  error.value = ''
  try {
    applySettings(await deletePermissionRule(rule.id))
  } catch (cause) {
    error.value = cause?.response?.data?.message || '删除规则失败。'
  } finally {
    saving.value = false
  }
}

load()
</script>

<style scoped>
.permission-page { color: var(--sk-label); }
.permission-header { padding: 22px 24px 4px; }
.permission-header-inner { max-width: 900px; margin: 0 auto; display: flex; align-items: flex-start; justify-content: space-between; gap: 20px; }
.eyebrow { color: var(--zwx-primary); font-size: 10.5px; font-weight: 700; letter-spacing: 0.12em; }
.permission-header h1 { margin: 2px 0 4px; font-size: 21px; }
.permission-header p { margin: 0; color: var(--sk-label-2); font-size: 12.5px; }
.sync-note { display: inline-flex; align-items: center; gap: 5px; border: 1px solid var(--sk-separator); border-radius: 999px; padding: 5px 11px; color: var(--sk-label-2); font-size: 11px; }

.permission-shell { max-width: 900px; margin: 14px auto 40px; display: grid; gap: 18px; padding: 0 24px; }
.mode-grid { display: grid; grid-template-columns: repeat(auto-fit, minmax(160px, 1fr)); gap: 10px; }
.mode-card { display: grid; gap: 6px; border: 1px solid var(--sk-separator); border-radius: 14px; padding: 13px 14px; background: #fff; color: var(--sk-label); text-align: left; cursor: pointer; transition: border-color 0.16s ease, box-shadow 0.16s ease; }
.mode-card:hover { border-color: var(--zwx-primary-ring); }
.mode-card.active { border-color: var(--zwx-primary); box-shadow: 0 0 0 3px var(--zwx-primary-ring); }
.mode-card:disabled { cursor: default; opacity: 0.7; }
.mode-title { display: flex; align-items: baseline; justify-content: space-between; gap: 8px; font-size: 13px; font-weight: 650; }
.mode-title small { color: var(--sk-label-3); font-size: 9.5px; font-weight: 600; letter-spacing: 0.06em; }
.mode-hint { color: var(--sk-label-2); font-size: 11px; line-height: 1.5; }

.rule-section { display: grid; gap: 10px; }
.section-title { display: flex; align-items: baseline; justify-content: space-between; gap: 12px; }
.section-title h2 { margin: 0; font-size: 14px; }
.section-title small { color: var(--sk-label-3); font-size: 10.5px; }
.rule-add { display: flex; gap: 8px; }
.rule-add input { flex: 1; border: 1px solid var(--sk-separator); border-radius: 10px; padding: 9px 12px; font-size: 12.5px; }
.rule-add select { border: 1px solid var(--sk-separator); border-radius: 10px; padding: 0 10px; background: #fff; font-size: 12px; }
.rule-add button, .rule-row button { border: 1px solid var(--sk-separator); border-radius: 10px; padding: 8px 14px; background: #fff; color: var(--sk-label); font-size: 12px; cursor: pointer; }
.rule-add button:disabled { opacity: 0.5; cursor: default; }
.rule-add button:not(:disabled):hover, .rule-row button:hover { border-color: var(--zwx-primary-ring); color: var(--zwx-primary); }

.state { display: grid; justify-items: center; gap: 6px; border: 1px dashed var(--sk-separator); border-radius: 14px; padding: 26px 16px; color: var(--sk-label-2); font-size: 12.5px; text-align: center; }
.state.compact { padding: 16px; }
.state.error { border-color: rgba(239, 68, 68, 0.4); color: #b91c1c; }
.state button { border: 1px solid var(--sk-separator); border-radius: 9px; padding: 5px 12px; background: #fff; cursor: pointer; }

.rule-list { display: grid; gap: 8px; margin: 0; padding: 0; list-style: none; }
.rule-row { display: flex; align-items: center; gap: 10px; border: 1px solid var(--sk-separator); border-radius: 12px; padding: 9px 13px; background: #fff; }
.rule-row code { flex: 1; font-size: 12px; word-break: break-all; }
.effect { border-radius: 999px; padding: 2px 9px; font-size: 10.5px; font-weight: 650; }
.effect.allow { background: rgba(22, 163, 74, 0.12); color: #15803d; }
.effect.deny { background: rgba(220, 38, 38, 0.1); color: #b91c1c; }
.rule-row .danger { color: #b91c1c; }

@media (max-width: 720px) {
  .permission-shell { padding: 0 14px; }
  .rule-add { flex-wrap: wrap; }
}
</style>
