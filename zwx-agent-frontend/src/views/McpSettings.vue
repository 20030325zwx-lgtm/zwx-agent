<template>
  <main class="mcp-page">
    <header class="page-header">
      <button class="back-button" type="button" aria-label="返回超级智能体" @click="router.push('/super-agent')"><ArrowLeft :size="16" />超级智能体</button>
      <div><span class="eyebrow">MCP CONNECTIONS</span><h1>MCP 管理</h1><p>已启用的服务会在下一次超级智能体执行时加载。</p></div>
      <button class="primary-button" type="button" @click="startCreate"><Plus :size="16" />添加服务</button>
    </header>

    <section class="mcp-shell">
      <form v-if="editing" class="editor" @submit.prevent="save">
        <div class="editor-heading"><strong>{{ editing.id ? '编辑 MCP 服务' : '添加 MCP 服务' }}</strong><button type="button" aria-label="关闭编辑器" @click="editing = null"><X :size="16" /></button></div>
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
            <button type="button" :disabled="testing === server.id" @click="testServer(server)" title="测试连接"><PlugZap :size="16" /></button>
            <button type="button" @click="startEdit(server)" title="编辑服务"><Pencil :size="16" /></button>
            <button class="danger" type="button" @click="remove(server)" title="删除服务"><Trash2 :size="16" /></button>
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
.mcp-page { min-height: 100vh; background: var(--sk-bg); color: var(--sk-label); }

.page-header {
  display: grid;
  grid-template-columns: 1fr minmax(0, 680px) 1fr;
  align-items: start;
  gap: 22px;
  padding: 28px 40px 26px;
  border-bottom: 1px solid var(--sk-separator);
  background: var(--sk-material);
  backdrop-filter: var(--sk-blur);
  -webkit-backdrop-filter: var(--sk-blur);
}

.back-button { display: flex; align-items: center; gap: 6px; justify-self: start; border: 0; background: transparent; color: var(--zwx-primary); font-size: 13px; }
.back-button:hover { opacity: 0.7; }

.eyebrow { color: var(--zwx-primary); font-size: 11px; font-weight: 700; letter-spacing: 0.1em; }

.page-header h1 { margin: 8px 0 5px; font-size: 30px; font-weight: 800; letter-spacing: -0.025em; }

.page-header p { margin: 0; color: var(--sk-label-2); font-size: 14px; }

.primary-button {
  display: flex;
  min-height: 38px;
  align-items: center;
  justify-content: center;
  gap: 6px;
  justify-self: end;
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

.mcp-shell { max-width: 900px; margin: 0 auto; padding: 32px 24px 64px; }

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
</style>
