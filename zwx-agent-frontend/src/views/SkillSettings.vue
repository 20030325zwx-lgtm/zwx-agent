<template>
  <main class="skills-page">
    <header class="skills-header">
      <button class="back-button" type="button" aria-label="返回智能体目录" @click="router.push('/')"><ArrowLeft :size="16" />智能体目录</button>
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
.skills-page { min-height: 100vh; background: var(--sk-bg); color: var(--sk-label); }

.skills-header {
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

.skills-header h1 { margin: 8px 0 5px; font-size: 30px; font-weight: 800; letter-spacing: -0.025em; }

.skills-header p { margin: 0; color: var(--sk-label-2); font-size: 14px; }

.sync-note { display: flex; align-items: center; justify-self: end; gap: 6px; color: #1f9d4d; font-size: 12px; font-weight: 550; }

.skills-shell { max-width: 900px; margin: 0 auto; padding: 32px 24px 64px; }

/* iOS 分段控件 */
.agent-tabs {
  display: flex;
  gap: 4px;
  width: max-content;
  max-width: 100%;
  margin-bottom: 24px;
  border-radius: 11px;
  padding: 3px;
  background: var(--sk-fill);
}

.agent-tabs button {
  border: 0;
  border-radius: 9px;
  padding: 8px 18px;
  background: transparent;
  color: var(--sk-label-2);
  font-size: 13px;
  font-weight: 550;
}

.agent-tabs button.active {
  background: var(--sk-surface);
  color: var(--sk-label);
  font-weight: 650;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.1), 0 1px 1px rgba(0, 0, 0, 0.05);
}

.state { padding: 40px 0; color: var(--sk-label-2); font-size: 14px; text-align: center; }

.skill-list { display: grid; gap: 12px; }

.skill-row {
  display: grid;
  grid-template-columns: 38px minmax(0, 1fr) auto;
  align-items: start;
  gap: 14px;
  border: 1px solid var(--sk-separator);
  border-radius: 16px;
  padding: 18px;
  background: var(--sk-surface);
  box-shadow: 0 1px 2px rgba(0, 0, 0, 0.03);
}

.skill-icon {
  display: grid;
  width: 38px;
  height: 38px;
  place-items: center;
  border-radius: 12px;
  background: var(--zwx-primary-soft);
  color: var(--zwx-primary);
}

.skill-copy { display: grid; min-width: 0; gap: 5px; }
.skill-copy strong { font-size: 15px; font-weight: 700; letter-spacing: -0.01em; }
.skill-copy small, .skill-copy p { margin: 0; color: var(--sk-label-2); font-size: 12px; line-height: 1.55; }
.skill-copy code { width: max-content; border-radius: 6px; padding: 2px 6px; background: var(--sk-fill); color: var(--sk-label-2); font-size: 11px; }

/* iOS 开关 */
.switch { display: flex; align-items: center; gap: 8px; color: var(--sk-label-2); font-size: 12px; }
.switch input { position: absolute; opacity: 0; }
.switch span {
  position: relative;
  width: 44px;
  height: 27px;
  flex: 0 0 44px;
  border-radius: 999px;
  background: var(--sk-fill-strong);
  transition: background-color 0.22s ease;
}
.switch span::after {
  content: "";
  position: absolute;
  top: 2.5px;
  left: 2.5px;
  width: 22px;
  height: 22px;
  border-radius: 50%;
  background: #fff;
  box-shadow: 0 2px 5px rgba(0, 0, 0, 0.2), 0 0 1px rgba(0, 0, 0, 0.15);
  transition: transform 0.22s cubic-bezier(0.32, 0.72, 0, 1);
}
.switch input:checked + span { background: var(--sk-green); }
.switch input:checked + span::after { transform: translateX(17px); }
.switch input:focus-visible + span { box-shadow: 0 0 0 3px var(--zwx-primary-ring); }

.actions { display: flex; align-items: center; justify-content: flex-end; gap: 14px; margin-top: 6px; }
.actions span { color: #1f9d4d; font-size: 13px; font-weight: 550; }

.actions button {
  height: 40px;
  border: 0;
  border-radius: 12px;
  padding: 0 20px;
  background: linear-gradient(180deg, #2590ff, var(--zwx-primary));
  color: #fff;
  font-size: 14px;
  font-weight: 600;
  box-shadow: 0 5px 14px var(--zwx-primary-ring);
}

.actions button:hover:not(:disabled) { filter: brightness(1.06); }
.actions button:active:not(:disabled) { transform: scale(0.98); }
.actions button:disabled { opacity: 0.5; }

.empty-state {
  display: grid;
  justify-items: center;
  gap: 8px;
  border: 1px dashed var(--sk-separator-strong);
  border-radius: 16px;
  padding: 40px 20px;
  background: var(--sk-surface);
  color: var(--sk-label-2);
  font-size: 13px;
}

.empty-state :deep(svg) { color: var(--sk-label-3); }
.empty-state strong { color: var(--sk-label); font-size: 15px; }

.empty-state button {
  height: 34px;
  margin-top: 6px;
  border: 0;
  border-radius: 10px;
  padding: 0 16px;
  background: var(--zwx-primary-soft);
  color: var(--zwx-primary);
  font-size: 13px;
  font-weight: 600;
}
</style>
