<template>
  <main class="memory-page">
    <header class="memory-header">
      <div class="memory-header-inner">
        <div><span class="eyebrow">MEMORY GOVERNANCE</span><h1>记忆管理</h1><p>查看、修正与删除智能体跨会话记住的关于你的事实。</p></div>
        <span class="sync-note"><Brain :size="15" />用户自治</span>
      </div>
    </header>
    <section class="memory-shell">
      <div class="add-row">
        <input v-model="newFact" type="text" maxlength="500" placeholder="补充一条希望智能体记住的事实，例如：我偏好简洁的回答" @keydown.enter="add" />
        <button type="button" :disabled="saving || !newFact.trim()" @click="add">添加</button>
      </div>
      <div v-if="loading" class="state">正在读取记忆...</div>
      <div v-else-if="error" class="state error">{{ error }} <button type="button" @click="load">重试</button></div>
      <div v-else-if="!facts.length" class="state">
        <Brain :size="20" />
        <span>暂无长期记忆</span>
        <small>开启记忆提取后，跨会话事实会出现在这里；你也可以在上方手动补充。</small>
      </div>
      <ul v-else class="fact-list">
        <li v-for="fact in facts" :key="fact.id" class="fact-row">
          <template v-if="editingId === fact.id">
            <input v-model="editingText" type="text" maxlength="500" @keydown.enter="save(fact)" />
            <div class="row-actions">
              <button type="button" class="primary" :disabled="saving" @click="save(fact)">保存</button>
              <button type="button" @click="cancelEdit">取消</button>
            </div>
          </template>
          <template v-else>
            <div class="fact-copy">
              <p>{{ fact.fact }}</p>
              <small>{{ agentLabel(fact.agentKey) }} · 记录于 {{ formatTime(fact.createdAt) }} · 最近生效 {{ formatTime(fact.lastSeenAt) }}</small>
            </div>
            <div class="row-actions">
              <button type="button" @click="startEdit(fact)">编辑</button>
              <button type="button" class="danger" @click="remove(fact)">删除</button>
            </div>
          </template>
        </li>
      </ul>
    </section>
  </main>
</template>

<script setup>
import { ref } from 'vue'
import { Brain } from 'lucide-vue-next'
import { addMemoryFact, deleteMemoryFact, listMemoryFacts, updateMemoryFact } from '../api'

const facts = ref([])
const loading = ref(true)
const saving = ref(false)
const error = ref('')
const newFact = ref('')
const editingId = ref(null)
const editingText = ref('')

const load = async () => {
  loading.value = true
  error.value = ''
  try { facts.value = await listMemoryFacts() }
  catch (cause) { error.value = cause?.response?.data?.message || '暂时无法读取记忆列表。' }
  finally { loading.value = false }
}
const add = async () => {
  if (!newFact.value.trim()) return
  saving.value = true
  error.value = ''
  try {
    await addMemoryFact('super', newFact.value.trim())
    newFact.value = ''
    await load()
  } catch (cause) { error.value = cause?.response?.data?.message || '添加失败。' }
  finally { saving.value = false }
}
const startEdit = fact => { editingId.value = fact.id; editingText.value = fact.fact }
const cancelEdit = () => { editingId.value = null; editingText.value = '' }
const save = async fact => {
  if (!editingText.value.trim()) return
  saving.value = true
  try {
    await updateMemoryFact(fact.id, editingText.value.trim())
    cancelEdit()
    await load()
  } catch (cause) { error.value = cause?.response?.data?.message || '保存失败：内容可能与其他事实重复。' }
  finally { saving.value = false }
}
const remove = async fact => {
  if (!window.confirm(`删除这条记忆？“${fact.fact.slice(0, 60)}”`)) return
  try {
    await deleteMemoryFact(fact.id)
    await load()
  } catch (cause) { error.value = cause?.response?.data?.message || '删除失败。' }
}
const agentLabel = key => ({ super: '超级智能体' }[key] || key)
const formatTime = value => {
  if (!value) return '—'
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? '—' : date.toLocaleString('zh-CN', { month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit' })
}
load()
</script>

<style scoped>
.memory-page { color: var(--sk-label); }
.memory-header { padding: 22px 24px 4px; }
.memory-header-inner { max-width: 900px; margin: 0 auto; display: flex; align-items: flex-start; justify-content: space-between; gap: 20px; }
.eyebrow { color: var(--zwx-primary); font-size: 10.5px; font-weight: 700; letter-spacing: 0.12em; }
.memory-header h1 { margin: 2px 0 4px; font-size: 21px; }
.memory-header p { margin: 0; color: var(--sk-label-2); font-size: 12.5px; }
.sync-note { display: inline-flex; align-items: center; gap: 5px; border: 1px solid var(--sk-separator); border-radius: 999px; padding: 5px 11px; color: var(--sk-label-2); font-size: 11px; }

.memory-shell { max-width: 900px; margin: 14px auto 40px; display: grid; gap: 12px; padding: 0 24px; }
.add-row { display: flex; gap: 8px; }
.add-row input { flex: 1; border: 1px solid var(--sk-separator); border-radius: 10px; padding: 9px 12px; font-size: 12.5px; }
.add-row button, .fact-row button { border: 1px solid var(--sk-separator); border-radius: 10px; padding: 8px 14px; background: #fff; color: var(--sk-label); font-size: 12px; cursor: pointer; }
.add-row button:disabled { opacity: 0.5; cursor: default; }
.add-row button:not(:disabled):hover, .fact-row button:hover { border-color: var(--zwx-primary-ring); color: var(--zwx-primary); }

.state { display: grid; justify-items: center; gap: 6px; border: 1px dashed var(--sk-separator); border-radius: 14px; padding: 30px 16px; color: var(--sk-label-2); font-size: 12.5px; text-align: center; }
.state.error { border-color: rgba(239, 68, 68, 0.4); color: #b91c1c; }
.state button { border: 1px solid var(--sk-separator); border-radius: 9px; padding: 5px 12px; background: #fff; cursor: pointer; }

.fact-list { display: grid; gap: 8px; margin: 0; padding: 0; list-style: none; }
.fact-row { display: flex; align-items: center; gap: 12px; border: 1px solid var(--sk-separator); border-radius: 12px; padding: 10px 14px; background: #fff; }
.fact-row input { flex: 1; border: 1px solid var(--zwx-primary-ring); border-radius: 9px; padding: 7px 10px; font-size: 12.5px; }
.fact-copy { flex: 1; min-width: 0; }
.fact-copy p { margin: 0; font-size: 13px; line-height: 1.5; word-break: break-word; }
.fact-copy small { color: var(--sk-label-3); font-size: 10.5px; }
.row-actions { display: flex; flex-shrink: 0; gap: 6px; }
.row-actions .primary { border-color: var(--zwx-primary-ring); color: var(--zwx-primary); }
.row-actions .danger { color: #b91c1c; }
</style>
