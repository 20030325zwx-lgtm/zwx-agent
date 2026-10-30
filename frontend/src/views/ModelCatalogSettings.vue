<template>
  <div class="catalog-page">
      <header class="page-head">
        <div>
          <h1>模型设置</h1>
          <p>添加供应商与模型，测试连通后绑定到用途；运行中的任务使用开始时冻结的版本。</p>
        </div>
      </header>

      <AAlert v-if="loadError" type="error" :message="loadError" show-icon closable class="page-alert" @close="loadError = ''" />

      <div class="catalog-layout">
        <aside class="provider-rail">
          <button type="button" class="new-provider" @click="startCreate">
            <Plus :size="15" /> 新建供应商
          </button>
          <p v-if="!catalog.providers.length" class="rail-empty">还没有供应商，点击上方新建</p>
          <button
            v-for="provider in catalog.providers"
            :key="provider.id"
            type="button"
            class="provider-card"
            :class="{ active: provider.id === selectedId, 'is-disabled': !provider.enabled }"
            @click="selectProvider(provider.id)"
          >
            <span class="provider-name">{{ provider.name }}</span>
            <span class="provider-meta">
              <span class="protocol-tag" :class="provider.protocol">{{ protocolLabel(provider.protocol) }}</span>
              <span class="test-dot" :class="testDotClass(provider)" :title="testTitle(provider)" />
            </span>
          </button>
        </aside>

        <section class="detail-pane">
          <div v-if="!selected && !isNew" class="detail-empty">
            <Server :size="22" />
            <p>选择或新建一个供应商开始配置</p>
          </div>

          <template v-else>
            <div class="detail-card">
              <div class="card-head">
                <h2>{{ isNew ? '新建供应商' : '供应商信息' }}</h2>
                <APopconfirm
                  v-if="!isNew"
                  title="删除该供应商？被绑定时会被阻止。"
                  @confirm="removeProvider"
                >
                  <AButton danger size="small" :loading="busy">删除</AButton>
                </APopconfirm>
              </div>
              <div class="form-grid">
                <label class="field">
                  <span>名称</span>
                  <AInput v-model:value="form.name" :maxlength="64" placeholder="例如 DashScope / OpenRouter" />
                </label>
                <label class="field">
                  <span>协议</span>
                  <ASelect v-model:value="form.protocol" :options="protocolOptions" />
                </label>
                <label class="field span-2">
                  <span>服务地址 {{ form.protocol === 'dashscope' ? '（留空使用官方地址）' : '' }}</span>
                  <AInput v-model:value="form.baseUrl" placeholder="https://api.example.com/v1" />
                </label>
                <label class="field span-2">
                  <span>API Key {{ selected?.hasApiKey ? `（已保存 ${selected.apiKeyHint || ''}）` : '' }}</span>
                  <AInputPassword
                    v-model:value="form.apiKey"
                    :placeholder="apiKeyPlaceholder"
                    autocomplete="new-password"
                  />
                  <ACheckbox
                    v-if="selected?.hasApiKey || form.apiKey"
                    v-model:checked="form.clearApiKey"
                    class="clear-key"
                  >保存时清除已存 Key</ACheckbox>
                </label>
                <div class="field">
                  <span>启用</span>
                  <ASwitch v-model:checked="form.enabled" />
                </div>
              </div>
              <div class="form-actions">
                <AButton type="primary" :loading="busy" @click="saveProvider">
                  {{ isNew ? '创建' : '保存修改' }}
                </AButton>
                <AButton
                  v-if="!isNew"
                  :loading="testing"
                  :disabled="!enabledModels.length"
                  :title="enabledModels.length ? '' : '请先添加模型'"
                  @click="testDraft"
                >测试（用当前表单草稿）</AButton>
              </div>
              <p v-if="testResult" class="test-result" :class="testResult.ok ? 'ok' : 'fail'">
                <CheckCircle2 v-if="testResult.ok" :size="14" />
                <AlertCircle v-else :size="14" />
                {{ testResult.message }} · {{ testResult.latencyMs }}ms
              </p>
            </div>

            <div v-if="!isNew" class="detail-card">
              <div class="card-head">
                <h2>模型列表</h2>
                <div class="head-actions">
                  <AButton
                    v-if="selected?.protocol === 'openai-compatible'"
                    size="small"
                    :loading="fetchingModels"
                    title="调用中转站 /v1/models，新模型默认停用"
                    @click="fetchModels"
                  >从中转站拉取模型列表</AButton>
                  <span class="card-hint">能力声明仅表示配置意图，未验证不自动标支持</span>
                </div>
              </div>
              <div v-for="model in selected.models" :key="model.id" class="model-row">
                <span class="model-id">{{ model.modelId }}</span>
                <span class="model-name">{{ model.displayName }}</span>
                <ASelect
                  :value="model.capabilities"
                  mode="multiple"
                  size="small"
                  class="model-caps-edit"
                  :options="capabilityOptions"
                  :max-tag-count="2"
                  placeholder="能力"
                  @change="value => updateCaps(model, value)"
                />
                <ASwitch
                  :checked="model.enabled"
                  size="small"
                  @change="value => toggleModel(model, value)"
                />
                <APopconfirm title="删除该模型？" @confirm="removeModel(model)">
                  <button type="button" class="model-delete"><Trash2 :size="14" /></button>
                </APopconfirm>
              </div>
              <div class="model-add">
                <AInput v-model:value="newModel.modelId" placeholder="模型 ID，如 qwen-plus" class="add-id" />
                <AInput v-model:value="newModel.displayName" placeholder="显示名（可选）" class="add-name" />
                <ASelect
                  v-model:value="newModel.capabilities"
                  mode="multiple"
                  placeholder="能力"
                  class="add-caps"
                  :options="capabilityOptions"
                />
                <AButton size="small" :loading="busy" @click="addModel">添加</AButton>
              </div>
            </div>

            <div v-if="!isNew" class="detail-card">
              <div class="card-head">
                <h2>用途绑定</h2>
                <span class="card-hint">未绑定时回退到旧配置或环境变量</span>
              </div>
              <div v-for="purpose in purposeRows" :key="purpose.key" class="binding-row">
                <span class="binding-label">{{ purpose.label }}</span>
                <span class="binding-current" :class="{ unset: !purpose.current }">
                  {{ purpose.current ? `${purpose.current.providerName} / ${purpose.current.modelName}` : '未绑定（回退旧配置）' }}
                </span>
                <ASelect
                  :value="purpose.current?.modelRowId ?? undefined"
                  class="binding-select"
                  placeholder="选择模型"
                  :options="bindingOptions"
                  @change="value => bind(purpose.key, value)"
                />
                <AButton size="small" :disabled="!purpose.current" @click="unbind(purpose.key)">清除</AButton>
              </div>
              <p v-if="!bindingOptions.length" class="card-hint binding-empty">还没有可绑定的模型：先在供应商下添加并启用模型</p>
            </div>
          </template>
        </section>
      </div>
    </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { Alert, Button, Checkbox, Input, message, Popconfirm, Select, Switch } from 'ant-design-vue'

const AAlert = Alert
const AButton = Button
const ACheckbox = Checkbox
const AInput = Input
const AInputPassword = Input.Password
const APopconfirm = Popconfirm
const ASelect = Select
const ASwitch = Switch
import { AlertCircle, CheckCircle2, Plus, Server, Trash2 } from 'lucide-vue-next'
import {
  addProviderModel,
  clearModelBinding,
  createModelProvider,
  deleteModelProvider,
  deleteProviderModel,
  fetchProviderModels,
  listModelCatalog,
  setModelBinding,
  testModelProvider,
  updateModelProvider,
  updateProviderModel
} from '../api'

const catalog = reactive({ providers: [], userBindings: {}, systemBindings: {}, revision: 0 })
const selectedId = ref(null)
const isNew = ref(false)
const busy = ref(false)
const testing = ref(false)
const fetchingModels = ref(false)
const loadError = ref('')
const testResult = ref(null)
const form = reactive({ name: '', protocol: 'openai-compatible', baseUrl: '', apiKey: '', enabled: true, clearApiKey: false })
const newModel = reactive({ modelId: '', displayName: '', capabilities: [] })

const selected = computed(() => catalog.providers.find(provider => provider.id === selectedId.value) || null)
const enabledModels = computed(() => (selected.value?.models || []).filter(model => model.enabled))
// 输入框保存后会被清空，脱敏 Key 只体现在标签里容易被误读成“没填”，所以占位符也要带上已存状态
const apiKeyPlaceholder = computed(() => selected.value?.hasApiKey
  ? `已保存 ${selected.value.apiKeyHint || ''}，留空则保持不变`
  : '尚未保存 API Key，请在此粘贴')

const protocolOptions = [
  { value: 'openai-compatible', label: 'OpenAI 兼容接口' },
  { value: 'dashscope', label: 'DashScope（百炼）' }
]
const capabilityOptions = [
  { value: 'tools', label: '工具调用' },
  { value: 'vision', label: '视觉' },
  { value: 'embedding', label: 'Embedding' },
  { value: 'image_generation', label: '图片生成' }
]
const PURPOSES = [
  { key: 'chat', label: '对话模型' },
  { key: 'vision', label: '视觉模型' },
  { key: 'image_generation', label: '图片生成' }
]

const purposeRows = computed(() => PURPOSES.map(({ key, label }) => ({
  key,
  label,
  current: catalog.userBindings[key] || null
})))

const bindingOptions = computed(() => catalog.providers.flatMap(provider =>
  provider.models.filter(model => model.enabled && provider.enabled).map(model => ({
    value: model.id,
    label: `${provider.name} / ${model.displayName || model.modelId}`
  }))
))

function protocolLabel(protocol) {
  return protocol === 'dashscope' ? 'DashScope' : 'OpenAI 兼容'
}
function capLabel(cap) {
  return { tools: '工具', vision: '视觉', embedding: '向量', image_generation: '生图' }[cap] || cap
}
function testDotClass(provider) {
  if (provider.lastTestOk === true) return 'ok'
  if (provider.lastTestOk === false) return 'fail'
  return 'unknown'
}
function testTitle(provider) {
  if (provider.lastTestOk === true) return `最近测试通过：${provider.lastTestMessage || ''}`
  if (provider.lastTestOk === false) return `最近测试失败：${provider.lastTestMessage || ''}`
  return '尚未测试'
}

function applyCatalog(data) {
  catalog.providers = data.providers || []
  catalog.userBindings = data.userBindings || {}
  catalog.systemBindings = data.systemBindings || {}
  catalog.revision = data.revision || 0
}

function resetForm() {
  Object.assign(form, { name: '', protocol: 'openai-compatible', baseUrl: '', apiKey: '', enabled: true, clearApiKey: false })
  testResult.value = null
}

function startCreate() {
  isNew.value = true
  selectedId.value = null
  resetForm()
}

function selectProvider(providerId) {
  isNew.value = false
  selectedId.value = providerId
  testResult.value = null
  const provider = selected.value
  Object.assign(form, {
    name: provider.name,
    protocol: provider.protocol,
    baseUrl: provider.baseUrl || '',
    apiKey: '',
    enabled: provider.enabled,
    clearApiKey: false
  })
}

async function refresh(keepSelection = true) {
  try {
    const data = await listModelCatalog()
    applyCatalog(data)
    if (keepSelection && selectedId.value && !selected.value) selectedId.value = null
    loadError.value = ''
  } catch (error) {
    loadError.value = extractError(error, '目录加载失败')
  }
}

async function saveProvider() {
  busy.value = true
  try {
    const payload = {
      name: form.name,
      protocol: form.protocol,
      baseUrl: form.baseUrl || null,
      enabled: form.enabled,
      apiKey: form.apiKey || null,
      clearApiKey: form.clearApiKey
    }
    if (isNew.value) {
      const provider = await createModelProvider(payload)
      isNew.value = false
      await refresh()
      selectedId.value = provider.id
      selectProvider(provider.id)
      message.success('供应商已创建')
    } else {
      const updated = await updateModelProvider(selectedId.value, payload)
      await refresh()
      selectProvider(selectedId.value)
      message.success(updated.hasApiKey
        ? `已保存（API Key ${updated.apiKeyHint || ''}）`
        : '已保存（未设置 API Key）')
    }
  } catch (error) {
    message.error(extractError(error, '保存失败'))
  } finally {
    busy.value = false
  }
}

async function testDraft() {
  testing.value = true
  testResult.value = null
  try {
    // 用当前表单草稿测试：不落库即可验证连通，密钥不回显
      testResult.value = await testModelProvider(selectedId.value, {
      baseUrl: form.baseUrl || null,
      apiKey: form.apiKey || null,
      clearApiKey: form.clearApiKey
    })
    await refresh()
    testResult.value = { ...testResult.value }
  } catch (error) {
    testResult.value = { ok: false, message: extractError(error, '测试失败'), latencyMs: 0 }
  } finally {
    testing.value = false
  }
}

async function addModel() {
  if (!newModel.modelId.trim()) {
    message.warning('请填写模型 ID')
    return
  }
  busy.value = true
  try {
    const updated = await addProviderModel(selectedId.value, {
      modelId: newModel.modelId,
      displayName: newModel.displayName || null,
      capabilities: newModel.capabilities
    })
    replaceProvider(updated)
    Object.assign(newModel, { modelId: '', displayName: '', capabilities: [] })
  } catch (error) {
    message.error(extractError(error, '添加失败'))
  } finally {
    busy.value = false
  }
}

async function toggleModel(model, value) {
  try {
    const updated = await updateProviderModel(selectedId.value, model.id, {
      modelId: model.modelId,
      displayName: model.displayName,
      capabilities: model.capabilities,
      enabled: value
    })
    replaceProvider(updated)
  } catch (error) {
    message.error(extractError(error, '更新失败'))
  }
}

async function updateCaps(model, capabilities) {
  try {
    const updated = await updateProviderModel(selectedId.value, model.id, {
      modelId: model.modelId,
      displayName: model.displayName,
      capabilities: capabilities || [],
      enabled: model.enabled
    })
    replaceProvider(updated)
  } catch (error) {
    message.error(extractError(error, '能力更新失败'))
  }
}

async function fetchModels() {
  fetchingModels.value = true
  try {
    const result = await fetchProviderModels(selectedId.value)
    message.success(`中转站返回 ${result.total} 个模型，新增 ${result.added} 个（默认停用，请启用并勾选能力）`)
    await refresh()
    if (!selected.value) selectedId.value = null
  } catch (error) {
    message.error(extractError(error, '拉取模型列表失败'))
  } finally {
    fetchingModels.value = false
  }
}

async function removeModel(model) {
  try {
    await deleteProviderModel(selectedId.value, model.id)
    await refresh()
  } catch (error) {
    message.error(extractError(error, '删除失败'))
  }
}

async function removeProvider() {
  try {
    await deleteModelProvider(selectedId.value)
    selectedId.value = null
    await refresh()
    message.success('已删除')
  } catch (error) {
    message.error(extractError(error, '删除失败'))
  }
}

async function bind(purpose, modelRowId) {
  if (!modelRowId) return
  const owner = catalog.providers.find(provider =>
    provider.models.some(model => model.id === modelRowId))
  if (!owner) return
  try {
    const data = await setModelBinding(purpose, { scope: 'USER', providerId: owner.id, modelRowId })
    applyCatalog(data)
  } catch (error) {
    message.error(extractError(error, '绑定失败'))
  }
}

async function unbind(purpose) {
  try {
    const data = await clearModelBinding(purpose, 'USER')
    applyCatalog(data)
  } catch (error) {
    message.error(extractError(error, '清除失败'))
  }
}

function replaceProvider(updated) {
  const index = catalog.providers.findIndex(provider => provider.id === updated.id)
  if (index >= 0) catalog.providers[index] = updated
}

function extractError(error, fallback) {
  return error?.response?.data?.error || error?.message || fallback
}

onMounted(() => refresh(false))
</script>

<style scoped>
.catalog-page {
  flex: 1;
  overflow-y: auto;
  padding: 28px 32px 48px;
}

.page-head h1 {
  font-size: 22px;
  font-weight: 650;
  letter-spacing: -0.02em;
  color: var(--wb-text);
}

.page-head p {
  margin-top: 4px;
  font-size: 13px;
  color: var(--wb-text-2);
}

.page-alert {
  margin-top: 14px;
}

.catalog-layout {
  display: flex;
  gap: 18px;
  margin-top: 20px;
  align-items: flex-start;
}

.provider-rail {
  display: flex;
  flex: 0 0 240px;
  flex-direction: column;
  gap: 8px;
}

.new-provider {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  padding: 9px;
  border: 1px dashed var(--wb-border-strong);
  border-radius: var(--wb-radius);
  background: transparent;
  color: var(--wb-brand);
  font-size: 13px;
  transition: border-color 0.15s, background 0.15s;
}

.new-provider:hover {
  border-color: var(--wb-brand);
  background: var(--wb-brand-soft);
}

.rail-empty {
  padding: 14px 4px;
  color: var(--wb-muted);
  font-size: 12.5px;
}

.provider-card {
  display: flex;
  flex-direction: column;
  gap: 7px;
  padding: 12px 14px;
  border: 1px solid var(--wb-border);
  border-radius: var(--wb-radius);
  background: var(--wb-surface);
  text-align: left;
  transition: border-color 0.15s, box-shadow 0.15s;
}

.provider-card:hover {
  border-color: var(--wb-border-strong);
}

.provider-card.active {
  border-color: var(--wb-brand);
  box-shadow: 0 0 0 3px var(--wb-brand-soft);
}

.provider-card.is-disabled .provider-name {
  color: var(--wb-muted);
}

.provider-name {
  color: var(--wb-text);
  font-size: 13.5px;
  font-weight: 550;
}

.provider-meta {
  display: flex;
  align-items: center;
  justify-content: space-between;
}

.protocol-tag {
  padding: 1px 8px;
  border-radius: 999px;
  background: var(--wb-fill);
  color: var(--wb-text-2);
  font-size: 11px;
}

.test-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
}

.test-dot.ok { background: var(--wb-success); }
.test-dot.fail { background: var(--wb-danger); }
.test-dot.unknown { background: var(--wb-border-strong); }

.detail-pane {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 14px;
  min-width: 0;
}

.detail-empty {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 10px;
  padding: 80px 0;
  color: var(--wb-muted);
  font-size: 13px;
}

.detail-card {
  padding: 18px 20px;
  border: 1px solid var(--wb-border);
  border-radius: var(--wb-radius-lg);
  background: var(--wb-surface);
}

.card-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 14px;
}

.card-head h2 {
  font-size: 15px;
  font-weight: 600;
  color: var(--wb-text);
}

.card-hint {
  color: var(--wb-muted);
  font-size: 12px;
}

.head-actions {
  display: flex;
  align-items: center;
  gap: 10px;
}

.model-caps-edit {
  flex: 0 0 190px;
}

.form-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px 16px;
}

.field {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 6px;
}

.field.span-2 { grid-column: span 2; }

.field > span {
  color: var(--wb-text-2);
  font-size: 12.5px;
}

.field :deep(.ant-input),
.field :deep(.ant-select),
.field :deep(.ant-input-password) {
  width: 100%;
}

.clear-key {
  margin-top: 2px;
  font-size: 12px;
}

.form-actions {
  display: flex;
  gap: 10px;
  margin-top: 16px;
}

.test-result {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-top: 12px;
  font-size: 12.5px;
}

.test-result.ok { color: var(--wb-success); }
.test-result.fail { color: var(--wb-danger); }

.model-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 9px 4px;
  border-bottom: 1px solid var(--wb-border);
}

.model-row:last-of-type {
  border-bottom: 0;
}

.model-id {
  color: var(--wb-text);
  font-family: var(--wb-font-mono, monospace);
  font-size: 12.5px;
}

.model-name {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  color: var(--wb-text-2);
  font-size: 12.5px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.model-caps {
  display: flex;
  gap: 6px;
}

.cap-tag {
  padding: 1px 8px;
  border-radius: 999px;
  background: var(--wb-brand-soft);
  color: var(--wb-brand);
  font-size: 11px;
}

.model-delete {
  display: grid;
  place-items: center;
  width: 26px;
  height: 26px;
  border: 0;
  border-radius: 7px;
  background: transparent;
  color: var(--wb-muted);
}

.model-delete:hover {
  background: var(--wb-fill);
  color: var(--wb-danger);
}

.model-add {
  display: flex;
  gap: 8px;
  margin-top: 12px;
  padding-top: 12px;
  border-top: 1px dashed var(--wb-border);
}

.add-id { flex: 1.2; }
.add-name { flex: 1; }
.add-caps { flex: 1; min-width: 140px; }

.binding-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 8px 0;
}

.binding-row + .binding-row {
  border-top: 1px solid var(--wb-border);
}

.binding-label {
  flex: 0 0 72px;
  color: var(--wb-text);
  font-size: 13px;
}

.binding-current {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  color: var(--wb-text-2);
  font-size: 12.5px;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.binding-current.unset {
  color: var(--wb-muted);
}

.binding-select {
  flex: 0 0 260px;
}

.binding-empty {
  margin-top: 10px;
}

@media (max-width: 900px) {
  .catalog-layout { flex-direction: column; }
  .provider-rail { flex: none; width: 100%; flex-direction: row; flex-wrap: wrap; }
  .new-provider { flex: 1 1 100%; }
  .provider-card { flex: 1 1 200px; }
}
</style>
