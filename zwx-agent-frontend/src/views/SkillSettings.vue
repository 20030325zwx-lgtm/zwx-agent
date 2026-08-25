<template>
  <main class="skills-page">
    <header class="skills-header">
      <button class="back-button" type="button" aria-label="返回智能体目录" @click="router.push('/')"><ArrowLeft :size="17" />智能体目录</button>
      <div><span class="eyebrow">CAPABILITY CONTROL</span><h1>内置 Skill 配置</h1><p>为每个智能体选择允许在对话中使用的能力。</p></div>
      <span class="sync-note"><Cloud :size="15" />服务端同步</span>
    </header>
    <section class="skills-shell">
      <div class="agent-tabs" role="tablist" aria-label="选择智能体">
        <button v-for="agent in configurableAgents" :key="agent.key" type="button" :class="{ active: agentKey === agent.key }" @click="selectAgent(agent.key)">{{ agent.name }}</button>
      </div>
      <div v-if="loading" class="state">正在读取 Skill 配置...</div>
      <section v-else class="skill-list" aria-live="polite">
        <div v-if="error || !skills.length" class="empty-state">
          <Sparkles :size="22" />
          <strong>暂无可用的 Skill 配置</strong>
          <span>{{ error || '当前智能体尚未注册可配置的 Skill。' }}</span>
          <button type="button" @click="load">重新加载</button>
        </div>
        <article v-for="skill in skills" :key="skill.id" class="skill-row">
          <div class="skill-icon"><Sparkles :size="18" /></div>
          <div class="skill-copy"><strong>{{ skill.name }}</strong><small>{{ skill.description }}</small><p>触发：{{ skill.trigger }}</p><code>{{ skill.id }}</code></div>
          <label class="switch"><input v-model="skill.enabled" type="checkbox" /><span aria-hidden="true"></span><em>{{ skill.enabled ? '已启用' : '已停用' }}</em></label>
        </article>
        <div v-if="skills.length" class="actions"><span v-if="saved">已保存，后续对话立即生效</span><button type="button" :disabled="saving" @click="save">{{ saving ? '保存中...' : '保存配置' }}</button></div>
      </section>
    </section>
  </main>
</template>

<script setup>
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ArrowLeft, Cloud, Sparkles } from 'lucide-vue-next'
import { getSkillCatalog, saveSkillConfiguration } from '../api'
import { AGENT_LIST } from '../config/agents'

const router = useRouter(); const route = useRoute()
const configurableAgents = AGENT_LIST.filter(agent => ['love', 'travel', 'test'].includes(agent.key))
const agentKey = ref(configurableAgents.some(agent => agent.key === route.query.agentKey) ? route.query.agentKey : 'love')
const skills = ref([]); const loading = ref(true); const saving = ref(false); const saved = ref(false); const error = ref('')
const load = async () => { loading.value = true; error.value = ''; saved.value = false; try { skills.value = await getSkillCatalog(agentKey.value) } catch (cause) { error.value = cause?.response?.data?.message || '暂时无法读取服务端配置，当前显示空列表。'; skills.value = [] } finally { loading.value = false } }
const selectAgent = key => { agentKey.value = key; load() }
const save = async () => { saving.value = true; error.value = ''; saved.value = false; try { skills.value = await saveSkillConfiguration(agentKey.value, skills.value.filter(skill => skill.enabled).map(skill => skill.id)); saved.value = true } catch (cause) { error.value = cause?.response?.data?.message || '保存 Skill 配置失败。' } finally { saving.value = false } }
load()
</script>

<style scoped>
.skills-page { min-height:100vh; background:#f7f8fa; color:var(--zwx-foreground); }.skills-header { display:grid; grid-template-columns:1fr minmax(0,680px) 1fr; align-items:start; gap:22px; padding:30px 40px 26px; border-bottom:1px solid var(--zwx-divider); background:#fff; }.back-button { display:flex; align-items:center; gap:7px; justify-self:start; border:0; background:transparent; color:#667085; font-size:13px; }.back-button:hover { color:var(--zwx-primary); }.eyebrow { color:var(--zwx-primary); font-size:11px; font-weight:750; letter-spacing:.08em; }.skills-header h1 { margin:7px 0 5px; font-size:28px; }.skills-header p { margin:0; color:var(--zwx-muted); font-size:14px; }.sync-note { display:flex; align-items:center; justify-self:end; gap:6px; color:#087f5b; font-size:12px; }.skills-shell { max-width:900px; margin:0 auto; padding:30px 24px 60px; }.agent-tabs { display:flex; gap:8px; margin-bottom:20px; }.agent-tabs button { border:1px solid var(--zwx-divider); border-radius:7px; padding:9px 15px; background:#fff; color:#667085; font-size:13px; }.agent-tabs button.active { border-color:var(--zwx-primary); background:var(--zwx-primary-soft); color:var(--zwx-primary); font-weight:650; }.skill-list { display:grid; gap:12px; }.skill-row { display:grid; grid-template-columns:38px minmax(0,1fr) auto; align-items:start; gap:14px; border:1px solid var(--zwx-divider); border-radius:8px; padding:18px; background:#fff; }.skill-icon { display:grid; width:36px; height:36px; place-items:center; border-radius:8px; background:var(--zwx-primary-soft); color:var(--zwx-primary); }.skill-copy { display:grid; min-width:0; gap:5px; }.skill-copy strong { font-size:15px; }.skill-copy small,.skill-copy p { margin:0; color:var(--zwx-muted); font-size:12px; line-height:1.55; }.skill-copy code { width:max-content; border-radius:4px; padding:2px 5px; background:#f2f4f7; color:#667085; font-size:11px; }.switch { display:flex; align-items:center; gap:7px; color:#667085; font-size:12px; }.switch input { position:absolute; opacity:0; }.switch span { position:relative; display:block; width:38px; height:22px; border-radius:12px; background:#d0d5dd; }.switch span::after { position:absolute; top:3px; left:3px; width:16px; height:16px; border-radius:50%; background:#fff; content:''; transition:transform .18s ease; }.switch input:checked + span { background:var(--zwx-primary); }.switch input:checked + span::after { transform:translateX(16px); }.switch em { width:42px; font-style:normal; }.actions { display:flex; min-height:36px; align-items:center; justify-content:flex-end; gap:14px; margin-top:18px; }.actions span { margin-right:auto; color:#087f5b; font-size:12px; }.actions button { height:35px; border:0; border-radius:7px; padding:0 16px; background:var(--zwx-primary); color:#fff; font-size:13px; font-weight:650; }.actions button:disabled { opacity:.6; }.state { padding:40px; text-align:center; color:var(--zwx-muted); }.empty-state { display:grid; min-height:250px; place-items:center; align-content:center; gap:9px; border:1px dashed #cbd5e1; border-radius:8px; background:#fff; color:#667085; text-align:center; }.empty-state svg { color:var(--zwx-primary); }.empty-state strong { color:#344054; font-size:14px; }.empty-state span { max-width:380px; font-size:12px; line-height:1.55; }.empty-state button { margin-top:3px; border:1px solid var(--zwx-divider); border-radius:6px; padding:6px 10px; background:#fff; color:#475467; font-size:12px; }.empty-state button:hover { border-color:var(--zwx-primary); color:var(--zwx-primary); }@media(max-width:720px){.skills-header{display:flex; flex-wrap:wrap; padding:22px 18px;}.skills-header>div{order:3; flex-basis:100%;}.sync-note{margin-left:auto}.skills-shell{padding:22px 16px 42px}.agent-tabs{overflow-x:auto}.agent-tabs button{white-space:nowrap}.skill-row{grid-template-columns:34px minmax(0,1fr);}.switch{grid-column:2; justify-self:start; margin-top:4px}}
</style>
