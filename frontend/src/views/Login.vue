<template>
  <div class="lg">
    <!-- 左：品牌视觉（视频 + 标语）。窄屏隐藏，避免视频抢占首屏 -->
    <section class="lg-visual" aria-hidden="true">
      <video
        v-if="!reduceMotion"
        ref="videoEl"
        class="lg-video"
        :src="bgVideo"
        :poster="bgPoster"
        autoplay
        muted
        loop
        playsinline
        preload="metadata"
        @error="videoFailed = true"
      ></video>
      <div v-else class="lg-video lg-video-poster" :style="{ backgroundImage: `url(${bgPoster})` }"></div>
      <div class="lg-veil"></div>

      <div class="lg-brand">
        <span class="lg-mark"><AsterMark /></span>
        <span class="lg-wordmark">Aster</span>
      </div>

      <div class="lg-copy">
        <p class="lg-copy-kicker">ASTER CREATIVE STUDIO</p>
        <h2>让一个想法，<br />从文字走向画面。</h2>
        <p class="lg-copy-sub">图片、视频与故事创作，都在同一个创作台里完成。</p>
      </div>
    </section>

    <!-- 右：登录表单 -->
    <section class="lg-panel">
      <div class="lg-panel-head">
        <span class="lg-mark lg-mark-small"><AsterMark /></span>
        <span class="lg-wordmark lg-wordmark-dark">Aster</span>
      </div>

      <form class="lg-card" @submit.prevent="submit">
        <p class="lg-kicker">WELCOME BACK</p>
        <h1>进入创作现场</h1>
        <p class="lg-sub">继续编辑你的画布、素材与生成任务。</p>

        <div class="lg-tabs" role="tablist" aria-label="登录方式">
          <button type="button" role="tab" class="lg-tab" :class="{ active: true }" aria-selected="true">登录</button>
          <button type="button" role="tab" class="lg-tab" disabled
                  title="注册已关闭，请联系管理员开通账号">注册</button>
        </div>

        <label class="lg-field">
          <span class="lg-label">用户名</span>
          <span class="lg-input-wrap">
            <User :size="16" class="lg-input-icon" />
            <input
              v-model.trim="username"
              type="text"
              autocomplete="username"
              placeholder="请输入用户名"
              required
              :disabled="loading"
            />
          </span>
        </label>

        <label class="lg-field">
          <span class="lg-label">
            <span>密码</span>
            <button type="button" class="lg-forgot" disabled title="请联系管理员重置密码">忘记密码？</button>
          </span>
          <span class="lg-input-wrap">
            <Lock :size="16" class="lg-input-icon" />
            <input
              v-model="password"
              :type="passwordVisible ? 'text' : 'password'"
              autocomplete="current-password"
              placeholder="请输入密码"
              required
              :disabled="loading"
            />
            <button
              type="button"
              class="lg-eye"
              :aria-label="passwordVisible ? '隐藏密码' : '显示密码'"
              :aria-pressed="passwordVisible"
              @click="passwordVisible = !passwordVisible"
            >
              <EyeOff v-if="passwordVisible" :size="16" />
              <Eye v-else :size="16" />
            </button>
          </span>
        </label>

        <label class="lg-remember">
          <input v-model="remember" type="checkbox" :disabled="loading" @change="forgetWhenUnchecked" />
          <span>记住密码</span>
          <small>仅保存在本机浏览器，公共设备请勿勾选</small>
        </label>

        <p v-if="error" class="lg-error" role="alert">{{ error }}</p>

        <button class="lg-submit" type="submit" :disabled="loading">
          <Spinner v-if="loading" />
          <template v-else>登录 <ArrowRight :size="15" /></template>
        </button>
      </form>

      <p class="lg-foot">© 2026 Aster. All rights reserved.</p>
    </section>
  </div>
</template>

<script setup>
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ArrowRight, Eye, EyeOff, Lock, User } from 'lucide-vue-next'
import AsterMark from '../components/AsterMark.vue'
import Spinner from '../components/ui/Spinner.vue'
import { login } from '../api/auth'
import { reduceMotion } from '../design/apply-tokens'

const router = useRouter()

// 仅开发模式（vite dev）才预填用户名：生产构建一律留空，
// 防止 VITE_DEFAULT_ADMIN_* 被内联进公开 JS 包（参见 docs/bugs/bug-2026-11-03-login-secret-bundle.txt）。
// 密码永远不预填；勾选「记住密码」后由本机浏览器自行保存。
const devPrefill = import.meta.env.DEV
const defaultAdminUsername = devPrefill ? (import.meta.env.VITE_DEFAULT_ADMIN_USERNAME || 'admin') : ''

// 记住密码：只做 Base64 编码存放，属于本机便利功能而非加密，
// 浏览器本地存储可被同源脚本读取——公共设备上不要勾选。
const REMEMBER_KEY = 'aster.login.remembered'

const readRemembered = () => {
  try {
    const raw = localStorage.getItem(REMEMBER_KEY)
    if (!raw || !raw.startsWith('v1:')) return null
    const binary = atob(raw.slice(3))
    const bytes = Uint8Array.from(binary, char => char.charCodeAt(0))
    const saved = JSON.parse(new TextDecoder().decode(bytes))
    return typeof saved?.username === 'string' ? saved : null
  } catch (error) {
    return null
  }
}

const rememberCredentials = (name, secret) => {
  try {
    const bytes = new TextEncoder().encode(JSON.stringify({ username: name, password: secret }))
    let binary = ''
    bytes.forEach(byte => { binary += String.fromCharCode(byte) })
    localStorage.setItem(REMEMBER_KEY, `v1:${btoa(binary)}`)
  } catch (error) {
    // 隐私模式等场景写入失败时静默降级为不记住
  }
}

const remembered = readRemembered()
const username = ref(remembered?.username || defaultAdminUsername)
const password = ref(remembered?.password || '')
const remember = ref(!!remembered)

// 取消勾选即刻清除本机保存的密码，避免「以为没记住、实际还留着」
const forgetWhenUnchecked = () => {
  if (!remember.value) localStorage.removeItem(REMEMBER_KEY)
}
const passwordVisible = ref(false)
const error = ref('')
const loading = ref(false)

const videoEl = ref(null)
const videoFailed = ref(false)

// 登录页品牌视频（Pexels 免费许可，见 docs/bugs 外的 assets 说明）
const bgVideo = '/media/login-bg.mp4'
const bgPoster = '/media/login-poster.jpg'

const submit = async () => {
  if (loading.value) return
  loading.value = true
  error.value = ''
  try {
    await login(username.value, password.value)
    if (remember.value) rememberCredentials(username.value, password.value)
    else localStorage.removeItem(REMEMBER_KEY)
    const redirect = router.currentRoute.value.query.redirect
    await router.replace(typeof redirect === 'string' && redirect.startsWith('/') ? redirect : '/')
  } catch (requestError) {
    error.value = requestError.response?.data?.error || requestError.response?.data?.message
      || '请求失败，请稍后再试'
  } finally {
    loading.value = false
  }
}

// 页面切走时暂停解码，避免后台空耗
function pause() { videoEl.value?.pause() }
function play() { videoEl.value?.play?.().catch(() => { /* 自动播放被拒绝时保留海报 */ }) }

onMounted(() => {
  document.addEventListener('visibilitychange', () => {
    if (document.visibilityState === 'hidden') pause()
    else if (!reduceMotion.value) play()
  })
})
onBeforeUnmount(() => {
  pause()
})
</script>

<style scoped>
.lg {
  display: grid;
  grid-template-columns: minmax(0, 1.08fr) minmax(440px, 0.92fr);
  min-height: 100vh;
  background: #f6f5f2;
}

/* ---------------------------------------------------------------- 左侧视觉 */

.lg-visual {
  position: relative;
  overflow: hidden;
  background: #101113;
  min-height: 100vh;
}

.lg-video {
  position: absolute;
  inset: 0;
  width: 100%;
  height: 100%;
  object-fit: cover;
}
.lg-video-poster { background-size: cover; background-position: center; }

/* 暗角与底部压暗：保证白色标语在浅色调视频上可读 */
.lg-veil {
  position: absolute;
  inset: 0;
  background:
    linear-gradient(180deg, rgba(9, 10, 14, 0.5), rgba(9, 10, 14, 0.05) 38%, rgba(9, 10, 14, 0.78)),
    radial-gradient(90% 70% at 78% 20%, rgba(102, 84, 199, 0.16), transparent 62%);
}

.lg-brand {
  position: absolute;
  top: 28px;
  left: 32px;
  display: flex;
  align-items: center;
  gap: 10px;
  color: #fff;
}
.lg-mark {
  display: grid;
  place-items: center;
  width: 34px;
  height: 34px;
  border-radius: 10px;
  background: rgba(255, 255, 255, 0.12);
  backdrop-filter: blur(8px);
  -webkit-backdrop-filter: blur(8px);
  box-shadow: inset 0 0 0 1px rgba(255, 255, 255, 0.18);
}
.lg-mark :deep(svg) { width: 19px; height: 19px; color: #fff; }
.lg-wordmark { font-size: 17px; font-weight: 650; letter-spacing: -0.01em; }

.lg-copy {
  position: absolute;
  left: 44px;
  right: 44px;
  bottom: 56px;
  color: #fff;
  max-width: 620px;
}
.lg-copy-kicker {
  margin: 0 0 14px;
  font-size: 11.5px;
  letter-spacing: 0.28em;
  color: rgba(255, 255, 255, 0.66);
}
.lg-copy h2 {
  margin: 0;
  font-size: clamp(30px, 3.4vw, 46px);
  line-height: 1.22;
  font-weight: 650;
  letter-spacing: -0.015em;
  text-shadow: 0 2px 24px rgba(0, 0, 0, 0.35);
}
.lg-copy-sub {
  margin: 16px 0 0;
  font-size: 14px;
  line-height: 1.7;
  color: rgba(255, 255, 255, 0.72);
}

/* ---------------------------------------------------------------- 右侧表单 */

.lg-panel {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  position: relative;
  padding: 32px clamp(24px, 5vw, 72px);
  background: #f6f5f2;
}

.lg-panel-head {
  position: absolute;
  top: 24px;
  left: clamp(24px, 5vw, 72px);
  right: clamp(24px, 5vw, 72px);
  display: flex;
  align-items: center;
  gap: 10px;
}
.lg-mark-small {
  width: 28px;
  height: 28px;
  border-radius: 8px;
  background: #202124;
  box-shadow: none;
}
.lg-mark-small :deep(svg) { width: 16px; height: 16px; }
.lg-wordmark-dark { font-size: 15px; color: #202124; }

.lg-back {
  margin-left: auto;
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 7px 14px;
  border: 1px solid #e4e3df;
  border-radius: 999px;
  background: #fff;
  color: #62646a;
  font-size: 12.5px;
  text-decoration: none;
  transition: border-color var(--cr-dur-fast, 140ms) var(--cr-ease, ease), color var(--cr-dur-fast, 140ms) var(--cr-ease, ease);
}
.lg-back:hover { border-color: #cdcbd4; color: #202124; }

.lg-card {
  width: min(440px, 100%);
  display: flex;
  flex-direction: column;
  gap: 14px;
  padding: 36px 36px 30px;
  border: 1px solid #ebe9e4;
  border-radius: 22px;
  background: #fff;
  box-shadow: 0 24px 60px rgba(26, 24, 32, 0.08);
}

.lg-kicker {
  margin: 0 0 -4px;
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.24em;
  color: #6654c7;
}

.lg-card h1 {
  margin: 0;
  font-size: 30px;
  font-weight: 680;
  letter-spacing: -0.02em;
  color: #202124;
}

.lg-sub {
  margin: 0;
  font-size: 13.5px;
  line-height: 1.6;
  color: #62646a;
}

.lg-tabs {
  display: flex;
  gap: 28px;
  margin: 8px 0 2px;
  border-bottom: 1px solid #eeede9;
}
.lg-tab {
  position: relative;
  padding: 0 2px 10px;
  border: 0;
  background: transparent;
  color: #8d919a;
  font-size: 13.5px;
  cursor: pointer;
}
.lg-tab.active { color: #202124; font-weight: 600; }
.lg-tab.active::after {
  content: '';
  position: absolute;
  left: 0;
  right: 0;
  bottom: -1px;
  height: 2px;
  border-radius: 2px;
  background: #202124;
}
.lg-tab:disabled { cursor: not-allowed; opacity: 0.55; }

.lg-field { display: flex; flex-direction: column; gap: 7px; }
.lg-label {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 12.5px;
  font-weight: 600;
  color: #52545a;
}

.lg-forgot {
  border: 0;
  background: transparent;
  padding: 0;
  color: #6654c7;
  font-size: 12px;
  cursor: not-allowed;
  opacity: 0.75;
}

.lg-input-wrap {
  position: relative;
  display: flex;
  align-items: center;
}
.lg-input-icon {
  position: absolute;
  left: 14px;
  color: #9a9da6;
  pointer-events: none;
}
.lg-input-wrap input {
  width: 100%;
  height: 46px;
  padding: 0 42px 0 42px;
  border: 1px solid transparent;
  border-radius: 12px;
  background: #f2f1ee;
  color: #202124;
  font-size: 14.5px;
  outline: none;
  transition: background var(--cr-dur-fast, 140ms) var(--cr-ease, ease), border-color var(--cr-dur-fast, 140ms) var(--cr-ease, ease);
}
.lg-input-wrap input::placeholder { color: #9a9da6; }
.lg-input-wrap input:focus {
  background: #fff;
  border-color: #6654c7;
  box-shadow: 0 0 0 3px rgba(102, 84, 199, 0.14);
}
.lg-input-wrap input:disabled { opacity: 0.6; }

.lg-eye {
  position: absolute;
  right: 8px;
  display: grid;
  place-items: center;
  width: 30px;
  height: 30px;
  border: 0;
  border-radius: 8px;
  background: transparent;
  color: #8d919a;
  cursor: pointer;
}
.lg-eye:hover { background: #e9e8e4; color: #202124; }

.lg-remember {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: -2px;
  color: #52545a;
  font-size: 12.5px;
  cursor: pointer;
  user-select: none;
}

.lg-remember input {
  width: 15px;
  height: 15px;
  accent-color: #6654c7;
  cursor: pointer;
}

.lg-remember small {
  margin-left: auto;
  color: #9a9da6;
  font-size: 11px;
}

.lg-error {
  margin: 0;
  padding: 9px 12px;
  border-radius: 10px;
  background: rgba(201, 54, 79, 0.08);
  color: #b02a42;
  font-size: 12.5px;
  line-height: 1.5;
}

.lg-submit {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  height: 46px;
  margin-top: 6px;
  border: 0;
  border-radius: 12px;
  background: #25252a;
  color: #fff;
  font-size: 14.5px;
  font-weight: 600;
  cursor: pointer;
  transition: transform var(--cr-dur-instant, 90ms) var(--cr-ease, ease), background var(--cr-dur-fast, 140ms) var(--cr-ease, ease);
}
.lg-submit:hover:not(:disabled) { background: #33333a; }
.lg-submit:active:not(:disabled) { transform: scale(0.985); }
.lg-submit:disabled { opacity: 0.6; cursor: progress; }

.lg-foot {
  position: absolute;
  bottom: 22px;
  margin: 0;
  font-size: 11.5px;
  color: #9a9da6;
}

@media (max-width: 960px) {
  .lg { grid-template-columns: 1fr; }
  .lg-visual { display: none; }
  .lg-panel { padding-top: 88px; }
  .lg-card { padding: 28px 22px 24px; }
  .lg-foot { position: static; margin-top: 26px; }
}
</style>
