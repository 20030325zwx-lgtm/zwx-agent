<template>
  <main class="mcp-page">
    <header class="page-header">
      <button class="back-button" type="button" aria-label="返回超级智能体" @click="router.push('/super-agent')"><ArrowLeft :size="17" />超级智能体</button>
      <div><span class="eyebrow">MCP CONNECTIONS</span><h1>MCP 管理</h1><p>已启用的服务会在下一次超级智能体执行时加载。</p></div>
      <button class="primary-button" type="button" @click="startCreate"><Plus :size="16" />添加服务</button>
    </header>

    <section class="mcp-shell">
      <form v-if="editing" class="editor" @submit.prevent="save">
        <div class="editor-heading"><strong>{{ editing.id ? '编辑 MCP 服务' : '添加 MCP 服务' }}</strong><button type="button" aria-label="关闭编辑器" @click="editing = null"><X :size="17" /></button></div>
        <label>名称<input v-model.trim="editing.name" required maxlength="80" placeholder="例如：图片搜索" /></label>
        <label>SSE 服务地址<input v-model.trim="editing.endpoint" required type="url" placeholder="http://127.0.0.1:8127" /></label>
        <label class="switch"><input v-model="editing.enabled" type="checkbox" /><span aria-hidden="true"></span><em>{{ editing.enabled ? '保存后启用' : '保存后停用' }}</em></label>
        <p v-if="formError" class="error"><AlertCircle :size="15" />{{ formError }}</p>
        <div class="editor-actions"><button type="button" @click="editing = null">取消</button><button class="primary-button" :disabled="saving" type="submit">{{ saving ? '保存中...' : '保存' }}</button></div>
      </form>

      <div v-if="loading" class="state">正在读取 MCP 服务...</div>
      <div v-else-if="!servers.length" class="empty"><Server :size="24" /><strong>尚未配置 MCP 服务</strong><span>添加远程 SSE 服务后，超级智能体可在任务中调用其工具。</span></div>
      <section v-else class="server-list">
        <article v-for="server in servers" :key="server.id" class="server-row">
          <div class="server-icon"><Server :size="19" /></div>
          <div class="server-copy"><div><strong>{{ server.name }}</strong><span :class="server.enabled ? 'enabled' : 'disabled'">{{ server.enabled ? '已启用' : '已停用' }}</span></div><code>{{ server.endpoint }}</code><small v-if="testResults[server.id]">{{ testResults[server.id].message }}<template v-if="testResults[server.id].tools?.length">：{{ testResults[server.id].tools.join('、') }}</template></small></div>
          <div class="server-actions">
            <button type="button" :disabled="testing === server.id" @click="testServer(server)" title="测试连接"><PlugZap :size="17" /></button>
            <button type="button" @click="startEdit(server)" title="编辑服务"><Pencil :size="17" /></button>
            <button class="danger" type="button" @click="remove(server)" title="删除服务"><Trash2 :size="17" /></button>
          </div>
        </article>
      </section>
    </section>
  </main>
</template>

<script setup>
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { AlertCircle, ArrowLeft, Pencil, PlugZap, Plus, Server, Trash2, X } from 'lucide-vue-next'
import { createMcpServer, deleteMcpServer, listMcpServers, testMcpServer, updateMcpServer } from '../api'

const router = useRouter()
const servers = ref([]); const loading = ref(true); const saving = ref(false); const testing = ref(null); const editing = ref(null); const formError = ref(''); const testResults = ref({})
const load = async () => { loading.value = true; try { servers.value = await listMcpServers() } finally { loading.value = false } }
const startCreate = () => { formError.value = ''; editing.value = { name: '', endpoint: '', enabled: true } }
const startEdit = server => { formError.value = ''; editing.value = { ...server } }
const save = async () => {
  saving.value = true; formError.value = ''
  try { if (editing.value.id) await updateMcpServer(editing.value.id, editing.value); else await createMcpServer(editing.value); editing.value = null; await load() }
  catch (cause) { formError.value = cause?.response?.data?.message || '保存失败，请检查名称和服务地址。' }
  finally { saving.value = false }
}
const testServer = async server => { testing.value = server.id; try { testResults.value[server.id] = await testMcpServer(server.id) } catch { testResults.value[server.id] = { connected: false, message: '测试请求失败', tools: [] } } finally { testing.value = null } }
const remove = async server => { if (!window.confirm(`删除 MCP 服务“${server.name}”吗？`)) return; await deleteMcpServer(server.id); delete testResults.value[server.id]; await load() }
onMounted(load)
</script>

<style scoped>
.mcp-page { min-height:100vh; background:#f7f8fa; color:var(--zwx-foreground); }.page-header { display:grid; grid-template-columns:1fr minmax(0,680px) 1fr; align-items:start; gap:22px; padding:30px 40px 26px; border-bottom:1px solid var(--zwx-divider); background:#fff; }.back-button { display:flex; align-items:center; gap:7px; justify-self:start; border:0; background:transparent; color:#667085; font-size:13px; }.back-button:hover { color:var(--zwx-primary); }.eyebrow { color:var(--zwx-primary); font-size:11px; font-weight:750; letter-spacing:.08em; }.page-header h1 { margin:7px 0 5px; font-size:28px; }.page-header p { margin:0; color:var(--zwx-muted); font-size:14px; }.primary-button { display:flex; min-height:36px; align-items:center; justify-content:center; gap:6px; justify-self:end; border:0; border-radius:7px; padding:0 14px; background:var(--zwx-primary); color:#fff; font-size:13px; font-weight:650; }.primary-button:disabled { opacity:.6; }.mcp-shell { max-width:900px; margin:0 auto; padding:30px 24px 60px; }.editor { display:grid; gap:14px; margin-bottom:18px; border:1px solid var(--zwx-divider); border-radius:8px; padding:18px; background:#fff; }.editor-heading { display:flex; align-items:center; justify-content:space-between; }.editor-heading button,.server-actions button { display:grid; width:32px; height:32px; place-items:center; border:1px solid var(--zwx-divider); border-radius:6px; background:#fff; color:#596579; }.editor label:not(.switch) { display:grid; gap:6px; color:#475467; font-size:12px; font-weight:650; }.editor input:not([type=checkbox]) { height:36px; border:1px solid #d0d5dd; border-radius:6px; padding:0 10px; color:#1d2939; font-size:13px; }.switch { display:flex; align-items:center; gap:8px; color:#667085; font-size:12px; }.switch input { position:absolute; opacity:0; }.switch span { position:relative; width:38px; height:22px; border-radius:12px; background:#d0d5dd; }.switch span::after { position:absolute; top:3px; left:3px; width:16px; height:16px; border-radius:50%; background:#fff; content:''; transition:transform .18s ease; }.switch input:checked + span { background:var(--zwx-primary); }.switch input:checked + span::after { transform:translateX(16px); }.switch em { font-style:normal; }.editor-actions { display:flex; justify-content:flex-end; gap:8px; }.editor-actions>button:first-child { border:1px solid var(--zwx-divider); border-radius:7px; padding:0 14px; background:#fff; color:#475467; font-size:13px; }.error { display:flex; align-items:center; gap:5px; margin:0; color:#b42318; font-size:12px; }.server-list { display:grid; gap:12px; }.server-row { display:grid; grid-template-columns:38px minmax(0,1fr) auto; gap:14px; align-items:start; border:1px solid var(--zwx-divider); border-radius:8px; padding:18px; background:#fff; }.server-icon { display:grid; width:36px; height:36px; place-items:center; border-radius:8px; background:var(--zwx-primary-soft); color:var(--zwx-primary); }.server-copy { display:grid; min-width:0; gap:6px; }.server-copy>div { display:flex; align-items:center; gap:8px; }.server-copy strong { font-size:15px; }.server-copy code { overflow:hidden; color:#667085; font-size:12px; text-overflow:ellipsis; white-space:nowrap; }.server-copy small { color:#667085; font-size:12px; line-height:1.45; }.enabled,.disabled { border-radius:4px; padding:2px 5px; font-size:10px; }.enabled { background:#e7f6ef; color:#087f5b; }.disabled { background:#f2f4f7; color:#667085; }.server-actions { display:flex; gap:6px; }.server-actions button:hover { border-color:var(--zwx-primary); color:var(--zwx-primary); }.server-actions .danger:hover { border-color:#d14343; color:#d14343; }.state,.empty { display:grid; min-height:210px; place-items:center; align-content:center; gap:9px; color:#667085; text-align:center; }.empty { border:1px dashed #cbd5e1; border-radius:8px; background:#fff; }.empty svg { color:var(--zwx-primary); }.empty strong { color:#344054; font-size:14px; }.empty span { max-width:350px; font-size:12px; line-height:1.55; } @media(max-width:720px){.page-header{display:flex; flex-wrap:wrap; padding:22px 18px;}.page-header>div{order:3; flex-basis:100%;}.primary-button{margin-left:auto}.mcp-shell{padding:22px 16px 42px}.server-row{grid-template-columns:34px minmax(0,1fr);}.server-actions{grid-column:2;}.server-copy code{max-width:240px}}
</style>
