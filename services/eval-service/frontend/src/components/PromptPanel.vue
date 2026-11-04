<template>
  <section>
    <div class="toolbar">
      <input v-model="form.assetKey" class="input" placeholder="assetKey（小写，如 task-planner）">
      <input v-model="form.name" class="input" placeholder="显示名">
      <input v-model="form.description" class="input" placeholder="用途说明（可选）">
      <button class="btn primary" :disabled="busy || !form.assetKey" @click="create">创建资产</button>
      <span style="flex:1"></span>
      <button class="btn" :disabled="loading" @click="load">{{ loading ? '刷新中…' : '刷新' }}</button>
    </div>

    <p v-if="error" class="error">{{ error }}</p>

    <p v-if="!loading && !prompts.length" class="empty card">
      还没有 Prompt 资产。建一个 key，发布 v1，之后实验就能引用 <span class="mono">key@v1</span>。
    </p>
    <div v-else class="card">
      <table>
        <thead><tr><th>assetKey</th><th>名称</th><th>最新版本</th><th>版本数</th><th>创建时间</th><th></th></tr></thead>
        <tbody>
          <tr v-for="item in prompts" :key="item.id">
            <td class="mono">{{ item.asset_key }}</td>
            <td>{{ item.name }}</td>
            <td>{{ item.latest_version ? `v${item.latest_version}` : '未发布' }}</td>
            <td>{{ item.version_count }}</td>
            <td class="mono">{{ formatTime(item.created_at) }}</td>
            <td><button class="btn ghost" @click="open(item.id)">详情</button></td>
          </tr>
        </tbody>
      </table>
    </div>

    <div v-if="detail" class="drawer-mask" @click.self="close">
      <aside class="drawer">
        <div class="drawer-head">
          <div>
            <h2 class="mono">{{ detail.asset_key }}</h2>
            <p class="muted">{{ detail.name }} · {{ detail.description || '无描述' }}</p>
          </div>
          <button class="btn ghost" @click="close">关闭</button>
        </div>

        <div class="section">
          <h3>已发布版本</h3>
          <p v-if="!detail.versions?.length" class="muted">还没有版本。</p>
          <table v-else>
            <thead><tr><th>版本</th><th>system 字数</th><th>发布时间</th></tr></thead>
            <tbody>
              <tr v-for="version in detail.versions" :key="version.version_no">
                <td class="mono">v{{ version.version_no }}</td>
                <td>{{ version.system_chars }}</td>
                <td class="mono">{{ formatTime(version.published_at) }}</td>
              </tr>
            </tbody>
          </table>
        </div>

        <div class="section">
          <h3>发布新版本</h3>
          <p class="muted">版本发布后不可变；改内容等于发新版本，历史实验仍指向旧版本。</p>
          <textarea v-model="versionForm.system" class="textarea code" rows="8" style="width:100%"
                    placeholder="system prompt 正文"></textarea>
          <input v-model="versionForm.params" class="input" style="width:100%;margin-top:6px"
                 placeholder="占位参数（逗号分隔，可选）">
          <input v-model="versionForm.notes" class="input" style="width:100%;margin-top:6px"
                 placeholder="这版改了什么（写进版本记录）">
          <div class="row-actions" style="margin-top:8px">
            <button class="btn primary" :disabled="busy || !versionForm.system.trim()" @click="publish">
              发布为 v{{ (detail.versions?.[0]?.version_no || 0) + 1 }}
            </button>
          </div>
        </div>
      </aside>
    </div>
  </section>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { api } from '../api'
import { showToast } from '../toast'
import { formatTime } from '../format'

const prompts = ref([])
const loading = ref(false)
const busy = ref(false)
const error = ref('')
const form = reactive({ assetKey: '', name: '', description: '' })
const detail = ref(null)
const versionForm = reactive({ system: '', params: '', notes: '' })

async function load() {
  loading.value = true
  error.value = ''
  try {
    prompts.value = await api.get('/prompts')
  } catch (exception) {
    error.value = exception.message
  } finally {
    loading.value = false
  }
}

async function create() {
  busy.value = true
  try {
    await api.post('/prompts', { ...form })
    form.assetKey = ''
    form.name = ''
    form.description = ''
    await load()
    showToast('Prompt 资产已创建')
  } catch (exception) {
    showToast(`创建失败：${exception.message}`)
  } finally {
    busy.value = false
  }
}

async function open(id) {
  try {
    detail.value = await api.get(`/prompts/${id}`)
    versionForm.system = ''
    versionForm.params = ''
    versionForm.notes = ''
  } catch (exception) {
    showToast(exception.message)
  }
}

function close() {
  detail.value = null
}

async function publish() {
  busy.value = true
  try {
    const params = versionForm.params.split(',').map((item) => item.trim()).filter(Boolean)
    const result = await api.post(`/prompts/${detail.value.id}/versions`, {
      system: versionForm.system,
      params,
      notes: versionForm.notes
    })
    showToast(`已发布 v${result.versionNo}`)
    versionForm.system = ''
    versionForm.notes = ''
    detail.value = await api.get(`/prompts/${detail.value.id}`)
    await load()
  } catch (exception) {
    showToast(`发布失败：${exception.message}`)
  } finally {
    busy.value = false
  }
}

onMounted(load)
</script>
