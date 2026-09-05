<template>
  <div class="login-page">
    <form class="login-card" @submit.prevent="submit">
      <h1>ZWX Agent</h1>
      <p class="login-subtitle">登录后继续使用智能体服务</p>

      <label>
        用户名
        <input v-model.trim="username" type="text" autocomplete="username" placeholder="3-64 位字母、数字、_ . -" required />
      </label>
      <label>
        密码
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
.login-page { display: grid; place-items: center; min-height: 100vh; background: #f5f6f8; padding: 24px; }
.login-card { width: 100%; max-width: 360px; display: flex; flex-direction: column; gap: 16px; padding: 32px; border: 1px solid #e5e7eb; border-radius: 16px; background: #fff; box-shadow: 0 10px 30px rgba(15, 23, 42, .06); }
.login-card h1 { margin: 0; font-size: 22px; text-align: center; }
.login-subtitle { margin: 0; color: #6b7280; font-size: 13px; text-align: center; }
.login-card label { display: flex; flex-direction: column; gap: 6px; font-size: 13px; color: #374151; }
.login-card input { height: 40px; padding: 0 12px; border: 1px solid #d1d5db; border-radius: 8px; font-size: 14px; outline: none; }
.login-card input:focus { border-color: #006fee; box-shadow: 0 0 0 3px rgba(0, 111, 238, .12); }
.login-error { margin: 0; color: #c33232; font-size: 13px; }
.login-submit { height: 42px; border: 0; border-radius: 8px; background: #006fee; color: #fff; font-size: 15px; cursor: pointer; }
.login-submit:disabled { opacity: .6; cursor: default; }
.login-switch { border: 0; background: transparent; color: #006fee; font-size: 13px; cursor: pointer; }
</style>
