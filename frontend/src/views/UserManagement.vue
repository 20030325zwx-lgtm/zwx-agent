<template>
  <main class="users-page">
    <div class="users-inner">
      <header class="users-head">
        <div>
          <h1>用户管理</h1>
          <p>管理员可添加或删除本租户账号。删除只移除账号本身，该账号的历史会话与运行记录保留。</p>
        </div>
        <span v-if="isAdmin" class="users-count">{{ users.length }} 个账号</span>
      </header>

      <div v-if="!isAdmin" class="state">
        <ShieldAlert :size="20" />
        <span>仅管理员可访问用户管理</span>
        <small>如需开通账号，请联系管理员。</small>
      </div>

      <template v-else>
        <section class="users-card">
          <h2>添加用户</h2>
          <form class="add-form" @submit.prevent="submit">
            <label class="field">
              <span>用户名</span>
              <input
                v-model.trim="form.username"
                type="text"
                autocomplete="off"
                placeholder="3-64 位字母、数字、_ . -"
                :disabled="saving"
              />
            </label>
            <label class="field">
              <span>密码</span>
              <input
                v-model="form.password"
                type="password"
                autocomplete="new-password"
                placeholder="6-128 位"
                :disabled="saving"
              />
            </label>
            <label class="field field-role">
              <span>角色</span>
              <select v-model="form.role" :disabled="saving">
                <option value="USER">成员</option>
                <option value="ADMIN">管理员</option>
              </select>
            </label>
            <button class="primary" type="submit" :disabled="saving || !canSubmit">
              {{ saving ? '添加中…' : '添加用户' }}
            </button>
          </form>
          <p v-if="notice" class="notice" :class="noticeKind">{{ notice }}</p>
        </section>

        <section class="users-card">
          <h2>账号列表</h2>
          <div v-if="loading" class="state">正在读取账号…</div>
          <div v-else-if="loadError" class="state error">
            {{ loadError }}
            <button type="button" @click="load">重试</button>
          </div>
          <ul v-else class="user-list">
            <li v-for="account in users" :key="account.id" class="user-row">
              <span class="avatar">{{ initial(account.username) }}</span>
              <div class="user-copy">
                <strong>
                  {{ account.username }}
                  <em v-if="account.username === currentUsername">（当前账号）</em>
                </strong>
                <small>{{ roleLabel(account.role) }} · {{ statusLabel(account.status) }}</small>
              </div>
              <button
                type="button"
                class="danger"
                :disabled="account.username === currentUsername || deletingId === account.id"
                :title="account.username === currentUsername ? '不能删除当前登录账号' : '删除账号'"
                @click="remove(account)"
              >
                {{ deletingId === account.id ? '删除中…' : '删除' }}
              </button>
            </li>
          </ul>
        </section>
      </template>
    </div>
  </main>
</template>

<script setup>
import { computed, reactive, ref } from 'vue'
import { ShieldAlert } from 'lucide-vue-next'
import { createUser, deleteUser, getCurrentUser, listUsers } from '../api/auth'

const current = getCurrentUser() || {}
const currentUsername = current.username || ''
const isAdmin = current.role === 'ADMIN'

const users = ref([])
const loading = ref(false)
const loadError = ref('')
const saving = ref(false)
const deletingId = ref('')
const notice = ref('')
const noticeKind = ref('ok')
const form = reactive({ username: '', password: '', role: 'USER' })

const canSubmit = computed(() => form.username.trim().length >= 3 && form.password.length >= 6)

const initial = username => (username || '?').slice(0, 1).toUpperCase()
const roleLabel = role => ({ ADMIN: '管理员', USER: '成员', REVIEWER: '审核员', WORKFLOW: '工作流账号' }[role] || role)
const statusLabel = status => ({ ACTIVE: '正常', DISABLED: '已停用' }[status] || status || '—')

const describeError = (cause, fallback) =>
  cause?.response?.data?.detail || cause?.response?.data?.error || cause?.response?.data?.message || fallback

const setNotice = (text, kind = 'ok') => {
  notice.value = text
  noticeKind.value = kind
}

const load = async () => {
  if (!isAdmin) return
  loading.value = true
  loadError.value = ''
  try {
    users.value = await listUsers()
  } catch (cause) {
    loadError.value = describeError(cause, '暂时无法读取账号列表。')
  } finally {
    loading.value = false
  }
}

const submit = async () => {
  if (saving.value || !canSubmit.value) return
  saving.value = true
  setNotice('')
  try {
    const created = await createUser({
      username: form.username.trim(),
      password: form.password,
      role: form.role
    })
    setNotice(`已添加 ${created.username}（${roleLabel(created.role)}）`)
    form.username = ''
    form.password = ''
    form.role = 'USER'
    await load()
  } catch (cause) {
    setNotice(describeError(cause, '添加失败，请检查用户名或密码格式。'), 'error')
  } finally {
    saving.value = false
  }
}

const remove = async account => {
  if (deletingId.value) return
  const confirmed = window.confirm(`删除账号「${account.username}」？该账号将无法再登录。`)
  if (!confirmed) return
  deletingId.value = account.id
  setNotice('')
  try {
    await deleteUser(account.id)
    setNotice(`已删除 ${account.username}`)
    await load()
  } catch (cause) {
    setNotice(describeError(cause, '删除失败，请稍后重试。'), 'error')
  } finally {
    deletingId.value = ''
  }
}

load()
</script>

<style scoped>
.users-page {
  flex: 1;
  overflow-y: auto;
  color: var(--wb-text);
}

.users-inner {
  max-width: 860px;
  margin: 0 auto;
  padding: 26px 28px 40px;
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.users-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
}

.users-head h1 {
  font-size: 20px;
  font-weight: 650;
  letter-spacing: -0.02em;
}

.users-head p {
  margin-top: 5px;
  max-width: 560px;
  font-size: 12.5px;
  line-height: 1.6;
  color: var(--wb-text-2);
}

.users-count {
  flex-shrink: 0;
  padding: 5px 11px;
  border: 1px solid var(--wb-border);
  border-radius: 999px;
  color: var(--wb-text-2);
  font-size: 11.5px;
}

.users-card {
  padding: 18px;
  border: 1px solid var(--wb-border);
  border-radius: var(--wb-radius-lg);
  background: var(--wb-surface);
}

.users-card h2 {
  margin-bottom: 12px;
  font-size: 13.5px;
  font-weight: 600;
  color: var(--wb-text);
}

.add-form {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-end;
  gap: 10px;
}

.field {
  display: flex;
  flex-direction: column;
  gap: 5px;
  flex: 1 1 180px;
  min-width: 160px;
}

.field-role {
  flex: 0 0 120px;
  min-width: 120px;
}

.field span {
  color: var(--wb-muted);
  font-size: 11.5px;
}

.field input,
.field select {
  height: 36px;
  padding: 0 11px;
  border: 1px solid var(--wb-border);
  border-radius: 9px;
  background: var(--wb-surface-2);
  color: var(--wb-text);
  font-family: inherit;
  font-size: 13px;
  outline: none;
}

.field input:focus,
.field select:focus {
  border-color: var(--wb-brand);
  box-shadow: 0 0 0 3px var(--wb-brand-soft);
}

.field input:disabled,
.field select:disabled {
  opacity: 0.6;
}

.add-form button {
  height: 36px;
  padding: 0 16px;
}

.primary {
  border: 0;
  border-radius: 9px;
  background: var(--wb-brand);
  color: var(--wb-brand-contrast);
  font-family: inherit;
  font-size: 13px;
  font-weight: 550;
  cursor: pointer;
  transition: background 0.15s;
}

.primary:hover:not(:disabled) {
  background: var(--wb-brand-hover);
}

.primary:disabled {
  opacity: 0.55;
  cursor: default;
}

.notice {
  margin-top: 12px;
  padding: 8px 11px;
  border-radius: 9px;
  font-size: 12.5px;
}

.notice.ok {
  background: rgba(31, 154, 94, 0.1);
  color: var(--wb-success);
}

.notice.error {
  background: rgba(201, 54, 79, 0.09);
  color: var(--wb-danger);
}

.state {
  display: grid;
  justify-items: center;
  gap: 6px;
  padding: 28px 16px;
  border: 1px dashed var(--wb-border);
  border-radius: var(--wb-radius-lg);
  color: var(--wb-text-2);
  font-size: 12.5px;
  text-align: center;
}

.state small {
  color: var(--wb-muted);
  font-size: 11.5px;
}

.state.error {
  border-color: rgba(201, 54, 79, 0.4);
  color: var(--wb-danger);
}

.state button {
  padding: 5px 12px;
  border: 1px solid var(--wb-border);
  border-radius: 8px;
  background: var(--wb-surface);
  color: var(--wb-text);
  font-size: 12px;
  cursor: pointer;
}

.user-list {
  display: grid;
  gap: 8px;
  margin: 0;
  padding: 0;
  list-style: none;
}

.user-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 12px;
  border: 1px solid var(--wb-border);
  border-radius: 11px;
  background: var(--wb-surface-2);
}

.avatar {
  display: grid;
  place-items: center;
  width: 32px;
  height: 32px;
  flex: 0 0 32px;
  border-radius: 50%;
  background: var(--wb-brand-gradient, linear-gradient(135deg, #2e6be6, #22c3be));
  color: #fff;
  font-size: 13px;
  font-weight: 600;
}

.user-copy {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.user-copy strong {
  font-size: 13px;
  font-weight: 600;
}

.user-copy strong em {
  color: var(--wb-muted);
  font-size: 11px;
  font-style: normal;
  font-weight: 400;
}

.user-copy small {
  color: var(--wb-muted);
  font-size: 11.5px;
}

.danger {
  flex-shrink: 0;
  padding: 6px 12px;
  border: 1px solid var(--wb-border);
  border-radius: 8px;
  background: var(--wb-surface);
  color: var(--wb-danger);
  font-family: inherit;
  font-size: 12px;
  cursor: pointer;
  transition: background 0.15s, border-color 0.15s;
}

.danger:hover:not(:disabled) {
  border-color: var(--wb-danger);
  background: rgba(201, 54, 79, 0.08);
}

.danger:disabled {
  opacity: 0.45;
  cursor: not-allowed;
}

@media (max-width: 640px) {
  .users-inner {
    padding: 20px 16px 32px;
  }

  .field-role {
    flex: 1 1 120px;
  }
}
</style>
