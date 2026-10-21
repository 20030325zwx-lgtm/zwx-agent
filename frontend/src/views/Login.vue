<template>
  <div class="login-page">
    <form class="login-card" @submit.prevent="submit">
      <div class="login-badge" aria-hidden="true">Z</div>
      <h1>ZWX Agent</h1>
      <p class="login-subtitle">登录后继续使用智能体服务</p>

      <label>
        <span>用户名</span>
        <input v-model.trim="username" type="text" autocomplete="username" placeholder="3-64 位字母、数字、_ . -" required />
      </label>
      <label>
        <span>密码</span>
        <input v-model="password" type="password" autocomplete="current-password" placeholder="至少 6 位" required />
      </label>

      <p v-if="error" class="login-error">{{ error }}</p>

      <button class="login-submit" type="submit" :disabled="loading">{{ loading ? '请稍候...' : mode === 'login' ? '登录' : '注册并登录' }}</button>

      <button class="login-switch" type="button" @click="toggleMode">
        {{ mode === 'login' ? '没有账号？注册新账号' : '已有账号？返回登录' }}
      </button>
    </form>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { login, register } from '../api/auth'

const router = useRouter()
const mode = ref('login')
const username = ref('')
const password = ref('')
const error = ref('')
const loading = ref(false)

const toggleMode = () => {
  mode.value = mode.value === 'login' ? 'register' : 'login'
  error.value = ''
}

const submit = async () => {
  if (loading.value) return
  loading.value = true
  error.value = ''
  try {
    if (mode.value === 'login') await login(username.value, password.value)
    else await register(username.value, password.value)
    const redirect = router.currentRoute.value.query.redirect
    await router.replace(typeof redirect === 'string' && redirect.startsWith('/') ? redirect : '/')
  } catch (requestError) {
    error.value = requestError.response?.data?.error || requestError.response?.data?.message || '请求失败，请稍后再试'
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.login-page {
  display: grid;
  place-items: center;
  min-height: 100vh;
  padding: 24px;
  background:
    radial-gradient(52rem 32rem at 12% -8%, rgba(0, 122, 255, 0.1), transparent 60%),
    radial-gradient(44rem 30rem at 108% 112%, rgba(88, 86, 214, 0.1), transparent 60%),
    var(--sk-bg);
}

.login-card {
  width: 100%;
  max-width: 368px;
  display: flex;
  flex-direction: column;
  gap: 15px;
  padding: 36px 32px 28px;
  border: 1px solid rgba(255, 255, 255, 0.6);
  border-radius: 24px;
  background: var(--sk-material-strong);
  backdrop-filter: var(--sk-blur);
  -webkit-backdrop-filter: var(--sk-blur);
  box-shadow: var(--sk-shadow-pop);
}

.login-badge {
  display: grid;
  width: 56px;
  height: 56px;
  margin: 0 auto;
  place-items: center;
  border-radius: 15px;
  background: linear-gradient(160deg, #3f9bff, var(--sk-blue) 58%, #0062cc);
  color: #fff;
  font-size: 24px;
  font-weight: 700;
  box-shadow: 0 8px 20px rgba(0, 122, 255, 0.35), inset 0 1px 0 rgba(255, 255, 255, 0.35);
}

.login-card h1 {
  margin: 4px 0 0;
  font-size: 22px;
  font-weight: 700;
  letter-spacing: -0.02em;
  text-align: center;
}

.login-subtitle {
  margin: -8px 0 8px;
  color: var(--sk-label-2);
  font-size: 13px;
  text-align: center;
}

.login-card label {
  display: flex;
  flex-direction: column;
  gap: 7px;
}

.login-card label span {
  color: var(--sk-label-2);
  font-size: 12px;
  font-weight: 600;
}

.login-card input {
  height: 44px;
  padding: 0 14px;
  border: 0;
  border-radius: 12px;
  background: var(--sk-fill);
  color: var(--sk-label);
  font-size: 15px;
  outline: none;
}

.login-card input::placeholder { color: var(--sk-label-3); }

.login-card input:focus {
  background: var(--sk-surface);
  box-shadow: 0 0 0 3px var(--zwx-primary-ring), 0 0 0 1px var(--zwx-primary) inset;
}

.login-error {
  margin: 0;
  color: var(--sk-red);
  font-size: 13px;
  text-align: center;
}

.login-submit {
  height: 46px;
  margin-top: 4px;
  border: 0;
  border-radius: 12px;
  background: linear-gradient(180deg, #2590ff, var(--zwx-primary));
  color: #fff;
  font-size: 15px;
  font-weight: 600;
  box-shadow: 0 6px 16px var(--zwx-primary-ring);
}

.login-submit:hover:not(:disabled) { filter: brightness(1.06); }

.login-submit:active:not(:disabled) { transform: scale(0.98); }

.login-submit:disabled { opacity: 0.5; }

.login-switch {
  border: 0;
  background: transparent;
  color: var(--zwx-primary);
  font-size: 13px;
}

.login-switch:hover { text-decoration: underline; text-underline-offset: 3px; }
</style>
