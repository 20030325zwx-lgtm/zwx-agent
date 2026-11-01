<template>
  <div class="sp-backdrop" role="dialog" aria-modal="true" @click.self="$emit('close')">
    <div class="sp-panel">
      <header class="sp-head">
        <strong>作品设定与连续性</strong>
        <button type="button" class="ap-close" aria-label="关闭" @click="$emit('close')">×</button>
      </header>

      <div class="sp-body">
        <section class="sp-section">
          <h4>角色（跨集复用；当场服装/伤势/持有物记到连续性）</h4>
          <div v-for="character in characters" :key="character.id" class="sp-card">
            <div class="sp-card-head">
              <input v-model="character.name" class="cr-input sp-name" maxlength="100" />
              <button type="button" class="cr-ghost" :disabled="busy" @click="saveCharacter(character)">保存</button>
              <HoldToConfirm label="删除" @confirm="removeCharacter(character)" />
            </div>
            <textarea v-model="character.appearance" class="cr-textarea" rows="2"
                      placeholder="固定外观：发型/服装基线/显著特征"></textarea>
            <textarea v-model="character.personality" class="cr-textarea" rows="2"
                      placeholder="性格与动机"></textarea>
          </div>
          <div class="sp-card sp-new">
            <input v-model="newCharacter.name" class="cr-input" maxlength="100" placeholder="角色名 *" />
            <textarea v-model="newCharacter.appearance" class="cr-textarea" rows="2" placeholder="固定外观"></textarea>
            <textarea v-model="newCharacter.personality" class="cr-textarea" rows="2" placeholder="性格与动机"></textarea>
            <button type="button" class="cr-secondary" :disabled="busy || !newCharacter.name.trim()"
                    @click="addCharacter">+ 添加角色</button>
          </div>
        </section>

        <section class="sp-section">
          <h4>地点（「旧车站」是地点，「雨夜对峙」是场次）</h4>
          <div v-for="location in locations" :key="location.id" class="sp-card">
            <div class="sp-card-head">
              <input v-model="location.name" class="cr-input sp-name" maxlength="100" />
              <button type="button" class="cr-ghost" :disabled="busy" @click="saveLocation(location)">保存</button>
              <HoldToConfirm label="删除" @confirm="removeLocation(location)" />
            </div>
            <textarea v-model="location.description" class="cr-textarea" rows="2"
                      placeholder="空间布局、标志物、美术基调"></textarea>
          </div>
          <div class="sp-card sp-new">
            <input v-model="newLocation.name" class="cr-input" maxlength="100" placeholder="地点名 *" />
            <textarea v-model="newLocation.description" class="cr-textarea" rows="2" placeholder="描述"></textarea>
            <button type="button" class="cr-secondary" :disabled="busy || !newLocation.name.trim()"
                    @click="addLocation">+ 添加地点</button>
          </div>
        </section>

        <section class="sp-section">
          <h4>连续性记录（事实 / 状态 / 伏笔；优化与分镜会读取这里）</h4>
          <div v-for="note in notes" :key="note.id" class="sp-note">
            <span class="sw-chip" :class="'note-' + note.kind">{{ kindLabel(note.kind) }}</span>
            <input v-model="note.content" class="cr-input sp-note-input" />
            <input v-if="note.kind === 'FORESHADOW'" v-model="note.foreshadowStatus" class="cr-input sp-note-fs"
                   maxlength="16" title="PLANTED/REINFORCED/PENDING/PAYOFF" />
            <input v-model="note.sourceLabel" class="cr-input sp-note-src" placeholder="来源" />
            <button type="button" class="cr-ghost" :disabled="busy" @click="saveNote(note)">存</button>
            <HoldToConfirm label="删" @confirm="removeNote(note)" />
          </div>
          <div class="sp-card sp-new sp-note-new">
            <select v-model="newNote.kind" class="cr-select">
              <option value="FACT">事实</option>
              <option value="STATE">状态</option>
              <option value="FORESHADOW">伏笔</option>
            </select>
            <input v-model="newNote.content" class="cr-input" placeholder="内容 *" />
            <input v-model="newNote.sourceLabel" class="cr-input" placeholder="来源（如：第 1 集 / 第 2 场）" />
            <button type="button" class="cr-secondary" :disabled="busy || !newNote.content.trim()"
                    @click="addNote">+ 添加记录</button>
          </div>
          <p class="cr-field-help">AI 优化会读取这些记录作为「已确认事实」；记录改动不会自动改写已生成内容。</p>
        </section>

        <p v-if="error" class="cr-error">{{ error }}</p>
      </div>
    </div>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import {
  createStoryCharacter, createStoryLocation, createStoryNote, deleteStoryCharacter,
  deleteStoryLocation, deleteStoryNote, getStoryWorkspace, updateStoryCharacter,
  updateStoryLocation, updateStoryNote
} from '../../../api'
import HoldToConfirm from '../../../components/ui/HoldToConfirm.vue'

const props = defineProps({
  projectId: { type: String, required: true }
})
const emit = defineEmits(['close'])

const characters = ref([])
const locations = ref([])
const notes = ref([])
const busy = ref(false)
const error = ref('')
const newCharacter = reactive({ name: '', appearance: '', personality: '' })
const newLocation = reactive({ name: '', description: '' })
const newNote = reactive({ kind: 'FACT', content: '', sourceLabel: '' })

const kindLabel = kind => ({ FACT: '事实', STATE: '状态', FORESHADOW: '伏笔' }[kind] || kind)

async function reload() {
  try {
    const data = await getStoryWorkspace(props.projectId)
    characters.value = data.characters || []
    locations.value = data.locations || []
    notes.value = data.notes || []
  } catch (exception) {
    error.value = exception.response?.data?.error || '设定加载失败'
  }
}

async function run(action) {
  busy.value = true
  error.value = ''
  try {
    await action()
    await reload()
  } catch (exception) {
    error.value = exception.response?.data?.error || '操作失败'
  } finally {
    busy.value = false
  }
}

const addCharacter = () => run(async () => {
  await createStoryCharacter(props.projectId, {
    name: newCharacter.name, appearance: newCharacter.appearance, personality: newCharacter.personality
  })
  Object.assign(newCharacter, { name: '', appearance: '', personality: '' })
})
const saveCharacter = character => run(() => updateStoryCharacter(props.projectId, character.id, {
  name: character.name, appearance: character.appearance, personality: character.personality
}))
const removeCharacter = character => run(() => deleteStoryCharacter(props.projectId, character.id))
const addLocation = () => run(async () => {
  await createStoryLocation(props.projectId, { name: newLocation.name, description: newLocation.description })
  Object.assign(newLocation, { name: '', description: '' })
})
const saveLocation = location => run(() => updateStoryLocation(props.projectId, location.id, {
  name: location.name, description: location.description
}))
const removeLocation = location => run(() => deleteStoryLocation(props.projectId, location.id))
const addNote = () => run(async () => {
  await createStoryNote(props.projectId, { ...newNote })
  Object.assign(newNote, { kind: newNote.kind, content: '', sourceLabel: '' })
})
const saveNote = note => run(() => updateStoryNote(props.projectId, note.id, {
  kind: note.kind, content: note.content, sourceLabel: note.sourceLabel, foreshadowStatus: note.foreshadowStatus
}))
const removeNote = note => run(() => deleteStoryNote(props.projectId, note.id))

onMounted(reload)
</script>

<style scoped src="../creative.css"></style>
<style scoped>
.sp-backdrop { position: fixed; inset: 0; z-index: 85; display: flex; justify-content: center;
  background: rgba(18, 21, 30, 0.6); padding: 24px; }
.sp-panel { display: flex; flex-direction: column; width: min(760px, 100%); max-height: 100%;
  border-radius: 14px; background: var(--wb-surface); padding: 14px 18px; overflow: auto; }
.sp-head { display: flex; align-items: center; justify-content: space-between; }
.ap-close { border: 0; background: transparent; color: var(--wb-muted); font-size: 22px; cursor: pointer; }
.sp-section h4 { margin: 14px 0 6px; font-size: 12.5px; color: var(--wb-muted); }
.sp-card { border: 1px solid var(--wb-border); border-radius: 10px; padding: 8px 10px; margin-bottom: 8px;
  display: flex; flex-direction: column; gap: 6px; }
.sp-new { background: var(--wb-fill); }
.sp-card-head { display: flex; gap: 6px; align-items: center; }
.sp-name { flex: 1; }
.sp-note { display: flex; gap: 6px; align-items: center; margin-bottom: 6px; flex-wrap: wrap; }
.sp-note-input { flex: 1; min-width: 200px; }
.sp-note-fs { width: 120px; }
.sp-note-src { width: 160px; }
.sp-note-new { display: flex; gap: 6px; flex-wrap: wrap; align-items: center; }
.note-FACT { color: #15803d; }
.note-STATE { color: #2563eb; }
.note-FORESHADOW { color: #b45309; }
</style>
