<template>
  <main class="mcp-page">
    <header class="page-header">
      <div class="page-header-inner">
        <div><span class="eyebrow">MCP CONNECTIONS</span><h1>MCP 管理</h1><p>已启用的服务会在下一次超级智能体执行时加载。</p></div>
        <button class="primary-button" type="button" @click="startCreate"><Plus :size="16" />添加服务</button>
      </div>
    </header>

    <section class="mcp-shell">
      <form v-if="editing" class="editor" @submit.prevent="save">
        <div class="editor-heading"><strong>{{ editing.id ? '编辑 MCP 服务' : '添加 MCP 服务' }}</strong><button type="button" aria-label="关闭编辑器" @click="editing = null"><X :size="16" /></button></div>
        <label>名称<input v-model.trim="editing.name" required maxlength="80" placeholder="例如：图片搜索" /></label>
        <label>传输方式<input value="SSE（当前版本支持）" disabled /></label>
        <label>MCP 服务地址<input v-model.trim="editing.endpoint" required type="url" placeholder="https://mcp.example.com/sse" /></label>
        <label>认证方式<select v-model="editing.authType"><option value="NONE">无需认证</option><option value="BEARER">Bearer Token</option><option value="HEADER">自定义 Header</option></select></label>
        <label v-if="editing.authType === 'HEADER'">Header 名称<input v-model.trim="editing.authHeaderName" placeholder="例如：X-API-Key" /></label>
        <label v-if="editing.authType !== 'NONE'">{{ editing.hasSecret && !editing.authSecret ? '认证密钥（已保存，留空保持不变）' : '认证密钥' }}<input v-model="editing.authSecret" type="password" autocomplete="new-password" :placeholder="editing.hasSecret ? editing.secretHint : '输入 Token 或 Header 值'" /></label>
        <label v-if="editing.hasSecret && editing.authType !== 'NONE'" class="switch"><input v-model="editing.clearAuthSecret" type="checkbox" /><span aria-hidden="true"></span><em>清除已保存密钥</em></label>
        <div class="discovery">
          <div class="discovery-heading"><strong>工具发现与授权</strong><button type="button" :disabled="testing === 'editing' || saving" @click="saveAndTest"><PlugZap :size="15" />{{ testing === 'editing' ? '连接中...' : '保存并测试连接' }}</button></div>
          <p v-if="!editing.id" class="hint">先保存连接配置，再测试并发现服务端提供的工具。</p>
          <p v-else-if="!editing.foundTools?.length" class="hint">发现的工具不会自动开放给智能体；测试连接后选择允许调用的工具。</p>
          <label v-for="tool in editing.foundTools || []" :key="tool.name" class="tool-choice">
            <input v-model="editing.allowedTools" type="checkbox" :value="tool.name" />
            <span><strong>{{ tool.name }}</strong><small>{{ tool.description || '服务端未提供说明' }} · 参数：{{ (tool.parameters || []).join('、') || '无' }}<template v-if="tool.requiredParameters?.length"> · 必填：{{ tool.requiredParameters.join('、') }}</template></small><details><summary>查看输入 Schema</summary><pre>{{ tool.inputSchema }}</pre></details></span>
          </label>
          <p v-if="editing.discoveryMessage" class="hint">{{ editing.discoveryMessage }}</p>
        </div>
        <label class="switch"><input v-model="editing.enabled" type="checkbox" /><span aria-hidden="true"></span><em>{{ editing.enabled ? '保存后启用' : '保存后停用' }}</em></label>
        <p v-if="formError" class="error"><AlertCircle :size="15" />{{ formError }}</p>
        <div class="editor-actions"><button type="button" @click="editing = null">取消</button><button class="primary-button" :disabled="saving" type="submit">{{ saving ? '保存中...' : '保存' }}</button></div>
      </form>

      <div v-if="loading" class="state">正在读取 MCP 服务...</div>
      <div v-else-if="!servers.length" class="empty"><Server :size="24" /><strong>尚未配置 MCP 服务</strong><span>添加远程 SSE 服务后，超级智能体可在任务中调用其工具。</span></div>
      <section v-else class="server-list">
        <article v-for="server in servers" :key="server.id" class="server-row">
          <div class="server-icon"><Server :size="19" /></div>
          <div class="server-copy"><div><strong>{{ server.name }}</strong><span :class="server.enabled ? 'enabled' : 'disabled'">{{ server.enabled ? '已启用' : '已停用' }}</span><span class="auth-badge">{{ server.authType === 'NONE' ? '无认证' : server.authType === 'BEARER' ? 'Bearer' : 'Header' }}</span></div><code>{{ server.endpoint }}</code><small>{{ server.allowedTools === null ? '兼容旧配置：当前全部工具可用' : `已授权 ${server.allowedTools.length} 个工具` }}</small><small v-if="testResults[server.id]">{{ testResults[server.id].message }}<template v-if="testResults[server.id].tools?.length">：{{ testResults[server.id].tools.map(tool => tool.name).join('、') }}</template></small></div>
          <div class="server-actions">
            <button type="button" :disabled="testing === server.id" @click="testServer(server)" title="测试连接"><PlugZap :size="16" /></button>
            <button type="button" @click="startEdit(server)" title="编辑服务"><Pencil :size="16" /></button>
            <button class="danger" type="button" @click="requestDelete(server)" title="删除服务"><Trash2 :size="16" /></button>
          </div>
        </article>
      </section>
    </section>
    <ConfirmDialog :open="!!pendingDelete" title="删除 MCP 服务" :message="deleteMessage" confirm-text="删除"
      @confirm="confirmDelete" @cancel="pendingDelete = null" />
  </main>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { AlertCircle, Pencil, PlugZap, Plus, Server, Trash2, X } from 'lucide-vue-next'
import ConfirmDialog from '../components/ConfirmDialog.vue'
import { createMcpServer, deleteMcpServer, listMcpServers, testMcpServer, updateMcpServer } from '../api'

const servers = ref([]); const loading = ref(true); const saving = ref(false); const testing = ref(null); const editing = ref(null); const formError = ref(''); const testResults = ref({}); const pendingDelete = ref(null)
const load = async () => { loading.value = true; try { servers.value = await listMcpServers() } finally { loading.value = false } }
const startCreate = () => { formError.value = ''; editing.value = { name: '', endpoint: '', enabled: true, authType: 'NONE', authHeaderName: '', authSecret: '', clearAuthSecret: false, allowedTools: [], foundTools: [] } }
const startEdit = server => { formError.value = ''; editing.value = { ...server, authSecret: '', clearAuthSecret: false, allowedTools: server.allowedTools || [], foundTools: testResults.value[server.id]?.tools || [] } }
const save = async (keepOpen = false) => {
  saving.value = true; formError.value = ''
  try {
    if (editing.value.authType !== 'NONE' && !editing.value.hasSecret && !editing.value.authSecret) throw new Error('请填写认证密钥。')
    const saved = editing.value.id ? await updateMcpServer(editing.value.id, editing.value) : await createMcpServer(editing.value)
    if (!editing.value.id) { await load(); startEdit(saved) }
    else if (keepOpen) { editing.value = { ...editing.value, ...saved }; await load() }
    else { editing.value = null; await load() }
    return true
  }
  catch (cause) { formError.value = cause?.message || cause?.response?.data?.message || '保存失败，请检查名称和服务地址。'; return false }
  finally { saving.value = false }
}
const saveAndTest = async () => {
  if (await save(true) && editing.value?.id) await testEditing()
}
const testServer = async server => { testing.value = server.id; try { testResults.value[server.id] = await testMcpServer(server.id) } catch { testResults.value[server.id] = { connected: false, message: '测试请求失败', tools: [] } } finally { testing.value = null } }
const testEditing = async () => {
  testing.value = 'editing'
  try {
    const result = await testMcpServer(editing.value.id)
    editing.value.foundTools = result.tools || []
    editing.value.discoveryMessage = result.message
    if (result.connected) {
      const names = new Set(result.tools.map(tool => tool.name))
      editing.value.allowedTools = editing.value.allowedTools.filter(name => names.has(name))
    }
  } catch { editing.value.discoveryMessage = '测试请求失败，请确认地址和认证配置。' }
  finally { testing.value = null }
}
const deleteMessage = computed(() => pendingDelete.value
  ? `删除 MCP 服务“${pendingDelete.value.name}”吗？删除后超级智能体将无法再调用其工具。`
  : '')
const requestDelete = server => { pendingDelete.value = server }
const confirmDelete = async () => {
  const server = pendingDelete.value
  pendingDelete.value = null
  if (!server) return
  await deleteMcpServer(server.id)
  delete testResults.value[server.id]
  await load()
}
onMounted(load)
</script>

<style scoped>
.mcp-page { color: var(--sk-label); }

.page-header { padding: 22px 24px 4px; }

.page-header-inner {
  display: flex;
  max-width: 900px;
  margin: 0 auto;
  align-items: flex-start;
  justify-content: space-between;
  gap: 20px;
}

.eyebrow { color: var(--zwx-primary); font-size: 10px; font-weight: 700; letter-spacing: 0.12em; }

.page-header h1 { margin: 6px 0 4px; font-size: 28px; font-weight: 800; letter-spacing: -0.025em; }

.page-header p { margin: 0; color: var(--sk-label-2); font-size: 13px; }

.primary-button {
  display: flex;
  min-height: 38px;
  align-items: center;
  justify-content: center;
  gap: 6px;
  flex: 0 0 auto;
  border: 0;
  border-radius: 11px;
  padding: 0 16px;
  background: linear-gradient(180deg, #2590ff, var(--zwx-primary));
  color: #fff;
  font-size: 13px;
  font-weight: 600;
  box-shadow: 0 5px 14px var(--zwx-primary-ring);
}

.primary-button:hover:not(:disabled) { filter: brightness(1.06); }
.primary-button:active:not(:disabled) { transform: scale(0.98); }
.primary-button:disabled { opacity: 0.5; }

.mcp-shell { max-width: 900px; margin: 0 auto; padding: 20px 24px 64px; }

/* 编辑器卡片 */
.editor { display: grid; gap: 14px; margin-bottom: 20px; border: 1px solid var(--sk-separator); border-radius: 18px; padding: 20px; background: var(--sk-surface); box-shadow: var(--sk-shadow-card); }

.editor-heading { display: flex; align-items: center; justify-content: space-between; }
.editor-heading strong { font-size: 15px; font-weight: 700; letter-spacing: -0.01em; }

.editor-heading button {
  display: grid;
  width: 30px;
  height: 30px;
  place-items: center;
  border: 0;
  border-radius: 9px;
  background: var(--sk-fill);
  color: var(--sk-label-2);
}

.editor-heading button:hover { background: var(--sk-fill-strong); color: var(--sk-label); }

.editor label:not(.switch) { display: grid; gap: 6px; color: var(--sk-label-2); font-size: 12px; font-weight: 650; }

.editor input:not([type=checkbox]) {
  height: 40px;
  border: 0;
  border-radius: 11px;
  padding: 0 12px;
  background: var(--sk-fill);
  color: var(--sk-label);
  font-size: 14px;
  outline: none;
}

.editor select { height: 40px; border: 0; border-radius: 11px; padding: 0 12px; background: var(--sk-fill); color: var(--sk-label); font-size: 14px; }
.discovery { display: grid; gap: 9px; border-top: 1px solid var(--sk-separator); padding-top: 14px; }
.discovery-heading { display: flex; align-items: center; justify-content: space-between; gap: 10px; }
.discovery-heading button { display: flex; align-items: center; gap: 6px; min-height: 34px; border: 0; border-radius: 9px; padding: 0 11px; background: var(--zwx-primary-soft); color: var(--zwx-primary); font-size: 12px; font-weight: 650; }
.discovery-heading button:disabled { opacity: .5; }
.hint { margin: 0; color: var(--sk-label-3); font-size: 12px; }
.tool-choice { display: flex; align-items: flex-start; gap: 9px; border: 1px solid var(--sk-separator); border-radius: 9px; padding: 10px; }
.tool-choice input { margin-top: 3px; accent-color: var(--zwx-primary); }
.tool-choice span { display: grid; gap: 4px; min-width: 0; }
.tool-choice strong { font-size: 12px; }
.tool-choice small { color: var(--sk-label-2); font-size: 11px; overflow-wrap: anywhere; }
.tool-choice details { color: var(--sk-label-2); font-size: 11px; }
.tool-choice pre { max-height: 180px; overflow: auto; border-radius: 6px; padding: 8px; background: var(--sk-fill); white-space: pre-wrap; overflow-wrap: anywhere; }

.editor input:not([type=checkbox]):focus { background: var(--sk-surface); box-shadow: 0 0 0 3px var(--zwx-primary-ring); }

/* iOS 开关 */
.switch { display: flex; align-items: center; gap: 8px; color: var(--sk-label-2); font-size: 12px; }
.switch input { position: absolute; opacity: 0; }
.switch span { position: relative; width: 44px; height: 27px; flex: 0 0 44px; border-radius: 999px; background: var(--sk-fill-strong); transition: background-color 0.22s ease; }
.switch span::after { content: ""; position: absolute; top: 2.5px; left: 2.5px; width: 22px; height: 22px; border-radius: 50%; background: #fff; box-shadow: 0 2px 5px rgba(0, 0, 0, 0.2), 0 0 1px rgba(0, 0, 0, 0.15); transition: transform 0.22s cubic-bezier(0.32, 0.72, 0, 1); }
.switch input:checked + span { background: var(--sk-green); }
.switch input:checked + span::after { transform: translateX(17px); }
.switch input:focus-visible + span { box-shadow: 0 0 0 3px var(--zwx-primary-ring); }

.error { display: flex; align-items: center; gap: 6px; margin: 0; color: var(--sk-red); font-size: 12px; }

.editor-actions { display: flex; justify-content: flex-end; gap: 10px; }
.editor-actions > button:first-child { height: 38px; border: 0; border-radius: 11px; padding: 0 16px; background: var(--sk-fill); color: var(--sk-label); font-size: 13px; font-weight: 550; }
.editor-actions > button:first-child:hover { background: var(--sk-fill-strong); }

.state { padding: 40px 0; color: var(--sk-label-2); font-size: 14px; text-align: center; }

.empty { display: grid; justify-items: center; gap: 8px; border: 1px dashed var(--sk-separator-strong); border-radius: 18px; padding: 44px 20px; background: var(--sk-surface); color: var(--sk-label-2); font-size: 13px; }
.empty :deep(svg) { color: var(--sk-label-3); }
.empty strong { color: var(--sk-label); font-size: 15px; }

.server-list { display: grid; gap: 12px; }

.server-row {
  display: grid;
  grid-template-columns: 38px minmax(0, 1fr) auto;
  align-items: center;
  gap: 14px;
  border: 1px solid var(--sk-separator);
  border-radius: 16px;
  padding: 16px 18px;
  background: var(--sk-surface);
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.03);
}

.server-icon { display: grid; width: 38px; height: 38px; place-items: center; border-radius: 12px; background: var(--zwx-primary-soft); color: var(--zwx-primary); }

.server-copy { display: grid; min-width: 0; gap: 4px; }
.server-copy div { display: flex; align-items: center; gap: 8px; }
.server-copy strong { font-size: 14px; font-weight: 700; }
.server-copy span { border-radius: 6px; padding: 2px 7px; font-size: 10px; font-weight: 700; }
.server-copy span.enabled { background: rgba(52, 199, 89, 0.14); color: #1f9d4d; }
.server-copy span.disabled { background: var(--sk-fill); color: var(--sk-label-2); }
.server-copy span.auth-badge { background: rgba(0, 122, 255, .1); color: var(--zwx-primary); }
.server-copy code { width: max-content; max-width: 100%; overflow: hidden; border-radius: 6px; padding: 2px 6px; background: var(--sk-fill); color: var(--sk-label-2); font-size: 11px; text-overflow: ellipsis; white-space: nowrap; }
.server-copy small { color: var(--sk-label-3); font-size: 11px; }

.server-actions { display: flex; gap: 7px; }

.server-actions button {
  display: grid;
  width: 32px;
  height: 32px;
  place-items: center;
  border: 0;
  border-radius: 10px;
  background: var(--sk-fill);
  color: var(--sk-label-2);
}

.server-actions button:hover:not(:disabled) { background: var(--zwx-primary-soft); color: var(--zwx-primary); }

.server-actions button.danger:hover { background: rgba(255, 59, 48, 0.12); color: var(--sk-red); }

.server-actions button:disabled { opacity: 0.5; }
@media (max-width: 720px) {
  .page-header { padding: 16px 14px 2px; }
  .page-header-inner { flex-direction: column; gap: 8px; }
  .page-header h1 { font-size: 24px; }
  .mcp-shell { padding: 14px 14px 48px; }
}
</style>
