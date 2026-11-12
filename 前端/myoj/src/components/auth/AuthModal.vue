<script setup lang="ts">
import { ref, watch, onBeforeUnmount, computed, nextTick, inject } from 'vue'
import { Message } from '@arco-design/web-vue'
import axios from 'axios'
import { ApiError, OpenAPI, Service } from '@generated'
import { isResultSuccess } from '@/api/result'
import { getBackendErrorMessage } from '@/api/httpError'
import {
  validateRegisterPassword,
  validateRegisterUsername,
  validateOptionalEmail,
} from '@/utils/authValidation'
import { useAuthStore } from '@/stores/auth'
import router from '@/router'
import { registerAdminRoutes } from '@/router/admin-dynamic'
import { LAYOUT_THEME_KEY } from '@/config/theme-injection'

const props = defineProps<{
  visible: boolean
}>()

const emit = defineEmits<{
  'update:visible': [value: boolean]
}>()

const modalVisible = computed({
  get: () => props.visible,
  set: (v: boolean) => emit('update:visible', v),
})

const auth = useAuthStore()
const layoutTheme = inject(LAYOUT_THEME_KEY, null)
const isDarkShell = computed(() => layoutTheme?.value === 'dark')

/** 弹层内容错峰渐显（与 Arco 打开时机对齐） */
const contentReveal = ref(false)

const tab = ref<'login' | 'register' | 'recover'>('login')

const recoveryForm = ref({ email: '', code: '', newPassword: '', confirmPassword: '' })
const sendingRecoveryCode = ref(false)
const resettingPassword = ref(false)
const recoveryCountdown = ref(0)
let recoveryTimer: ReturnType<typeof setInterval> | null = null

function stopRecoveryTimer() {
  if (recoveryTimer) clearInterval(recoveryTimer)
  recoveryTimer = null
  recoveryCountdown.value = 0
}

function openRecovery() {
  recoveryForm.value = { email: registerForm.value.email, code: '', newPassword: '', confirmPassword: '' }
  tab.value = 'recover'
}

async function sendRecoveryCode() {
  const email = recoveryForm.value.email.trim()
  if (!email || validateOptionalEmail(email)) {
    Message.warning('请填写正确的注册邮箱')
    return
  }
  if (recoveryCountdown.value > 0 || sendingRecoveryCode.value) return
  sendingRecoveryCode.value = true
  try {
    const { data } = await axios.post<{ code: number; message: string }>(
      `${OpenAPI.BASE.replace(/\/+$/, '')}/user/password/reset/code`,
      { email },
    )
    if (!isResultSuccess(data.code)) throw new Error(data.message || '发送失败')
    Message.success(data.message || '如果邮箱已绑定账号，验证码将发送到该邮箱')
    recoveryCountdown.value = 60
    recoveryTimer = setInterval(() => {
      recoveryCountdown.value--
      if (recoveryCountdown.value <= 0) stopRecoveryTimer()
    }, 1000)
  } catch (e) {
    Message.error(mapError(e, '发送失败，请稍后再试'))
  } finally {
    sendingRecoveryCode.value = false
  }
}

async function resetForgottenPassword() {
  const { email, code, newPassword, confirmPassword } = recoveryForm.value
  if (!email.trim() || validateOptionalEmail(email)) return void Message.warning('请填写正确的注册邮箱')
  if (!/^\d{6}$/.test(code.trim())) return void Message.warning('请输入邮件中的6位验证码')
  const passwordError = validateRegisterPassword(newPassword)
  if (passwordError) return void Message.warning(passwordError)
  if (newPassword !== confirmPassword) return void Message.warning('两次输入的密码不一致')
  resettingPassword.value = true
  try {
    const { data } = await axios.post<{ code: number; message: string }>(
      `${OpenAPI.BASE.replace(/\/+$/, '')}/user/password/reset`,
      { email: email.trim(), code: code.trim(), newPassword },
    )
    if (!isResultSuccess(data.code)) throw new Error(data.message || '重置失败')
    Message.success('密码已重置，请使用新密码登录')
    recoveryForm.value = { email: '', code: '', newPassword: '', confirmPassword: '' }
    stopRecoveryTimer()
    tab.value = 'login'
    loginForm.value.password = ''
  } catch (e) {
    Message.error(mapError(e, '重置失败，请稍后再试'))
  } finally {
    resettingPassword.value = false
  }
}

const loginForm = ref({
  username: '',
  password: '',
  code: '',
})

const registerForm = ref({
  username: '',
  password: '',
  confirmPassword: '',
  email: '',
})

const captchaUrl = ref('')
let captchaObjectUrl: string | null = null
/** 图形验证码图片请求中（已通过滑动后） */
const captchaImageLoading = ref(false)
const captchaLoadFailed = ref(false)

/** 右滑通过后才请求 / 展示图形验证码 */
const captchaUnlocked = ref(false)
const slideOffset = ref(0)
const slideTrackRef = ref<HTMLElement | null>(null)
const slideDragging = ref(false)

const tabHint = computed(() =>
  tab.value === 'login' ? '欢迎回到码跃 OJ，继续你的刷题之旅' : tab.value === 'recover' ? '找回密码，重新进入码跃' : '几分钟即可加入社区与比赛',
)

/** 登录 / 注册面板下方一句引导，减少误操作 */
const panelLead = computed(() =>
  tab.value === 'login' ? '先滑块验证，再填写账号与图形码' : tab.value === 'recover' ? '使用注册时绑定的邮箱接收验证码，设置新密码' : '仅用于创建账号，注册后请用同一用户名登录',
)

function revokeCaptcha() {
  if (captchaObjectUrl) {
    URL.revokeObjectURL(captchaObjectUrl)
    captchaObjectUrl = null
  }
  captchaUrl.value = ''
}

function mapError(e: unknown, fallback: string): string {
  if (e instanceof ApiError) {
    const body = e.body
    if (body && typeof body === 'object' && 'message' in body) {
      const m = (body as { message?: unknown }).message
      if (m != null && String(m).trim()) return String(m)
    }
    if (typeof body === 'string' && body.trim()) {
      try {
        const p = JSON.parse(body) as { message?: string }
        if (p.message) return p.message
      } catch {
        return body
      }
    }
    return e.message || fallback
  }
  if (axios.isAxiosError(e)) {
    return getBackendErrorMessage(e, fallback)
  }
  return e instanceof Error ? e.message : fallback
}

function resetSlideCaptcha() {
  captchaUnlocked.value = false
  slideOffset.value = 0
  slideDragging.value = false
  captchaImageLoading.value = false
  captchaLoadFailed.value = false
  revokeCaptcha()
  loginForm.value.code = ''
}

function getSlideMaxOffset(): number {
  const track = slideTrackRef.value
  if (!track) return 0
  const handleW = 44
  const pad = 8
  return Math.max(0, track.clientWidth - handleW - pad * 2)
}

function onSlidePointerDown(e: PointerEvent) {
  if (captchaUnlocked.value) return
  const target = e.currentTarget as HTMLElement
  target.setPointerCapture(e.pointerId)
  slideDragging.value = true
  const startX = e.clientX
  const baseOffset = slideOffset.value

  const onMove = (ev: PointerEvent) => {
    const maxX = getSlideMaxOffset()
    if (maxX <= 0) return
    const dx = ev.clientX - startX
    slideOffset.value = Math.max(0, Math.min(maxX, baseOffset + dx))
  }

  const onUp = (ev: PointerEvent) => {
    try {
      target.releasePointerCapture(ev.pointerId)
    } catch {
      /* ignore */
    }
    document.removeEventListener('pointermove', onMove)
    document.removeEventListener('pointerup', onUp)
    document.removeEventListener('pointercancel', onUp)
    slideDragging.value = false

    const maxX = getSlideMaxOffset()
    if (maxX > 0 && slideOffset.value >= maxX * 0.92) {
      slideOffset.value = maxX
      captchaUnlocked.value = true
      void refreshCaptcha()
    } else {
      slideOffset.value = 0
    }
  }

  document.addEventListener('pointermove', onMove)
  document.addEventListener('pointerup', onUp)
  document.addEventListener('pointercancel', onUp)
}

async function refreshCaptcha() {
  revokeCaptcha()
  captchaImageLoading.value = true
  captchaLoadFailed.value = false
  try {
    const base = OpenAPI.BASE.replace(/\/+$/, '')
    const { data } = await axios.get(`${base}/captcha`, {
      responseType: 'blob',
      withCredentials: OpenAPI.WITH_CREDENTIALS,
    })
    if (!(data instanceof Blob) || data.size === 0) {
      captchaLoadFailed.value = true
      Message.error('验证码数据异常')
      return
    }
    captchaObjectUrl = URL.createObjectURL(data)
    captchaUrl.value = captchaObjectUrl
  } catch (e) {
    captchaLoadFailed.value = true
    Message.error(mapError(e, '请求太频繁，请稍后再试'))
  } finally {
    captchaImageLoading.value = false
  }
}

/** 登录失败时只换新图、清空验证码，不撤销已通过的人机滑动 */
function resetCaptchaAfterLoginFailure() {
  loginForm.value.code = ''
  void refreshCaptcha()
}

watch(
  () => props.visible,
  (v) => {
    if (v) {
      tab.value = 'login'
      contentReveal.value = false
      void nextTick(() => {
        resetSlideCaptcha()
        const reduce =
          typeof window !== 'undefined' && window.matchMedia('(prefers-reduced-motion: reduce)').matches
        if (reduce) {
          contentReveal.value = true
          return
        }
        requestAnimationFrame(() => {
          requestAnimationFrame(() => {
            contentReveal.value = true
          })
        })
      })
    } else {
      contentReveal.value = false
      stopRecoveryTimer()
    }
  },
)

watch(tab, (t) => {
  if (t === 'login' && props.visible) {
    void nextTick(() => resetSlideCaptcha())
  }
})

const loginLoading = ref(false)
const registerLoading = ref(false)

async function onLogin() {
  const { username, password, code } = loginForm.value
  const u = username.trim()

  if (!u || !password) {
    Message.warning('请先填写用户名或邮箱及密码')
    return
  }
  if (!captchaUnlocked.value) {
    Message.warning('请先向右滑动完成人机验证')
    return
  }
  if (captchaImageLoading.value) {
    Message.warning('验证码图片加载中，请稍候再点击登录')
    return
  }
  if (!captchaUrl.value) {
    Message.warning(
      captchaLoadFailed.value
        ? '验证码未加载成功，请点击右侧验证码区域重试'
        : '验证码尚未就绪，请稍候',
    )
    return
  }
  if (!code.trim()) {
    Message.warning('请输入图形验证码')
    return
  }
  loginLoading.value = true
  try {
    const deviceMemory = (navigator as Navigator & { deviceMemory?: number }).deviceMemory
    const deviceParts = [
      navigator.hardwareConcurrency ? `逻辑处理器 ${navigator.hardwareConcurrency} 核` : '',
      deviceMemory ? `内存约 ${deviceMemory} GB` : '',
      window.screen?.width && window.screen?.height
        ? `屏幕 ${window.screen.width}×${window.screen.height} CSS像素`
        : '',
    ].filter(Boolean)
    const payload: Parameters<typeof Service.login>[0] & { deviceDetails?: string } = {
      username: u,
      password,
      code: code.trim(),
      deviceDetails: deviceParts.join('；'),
    }
    const result = await Service.login(payload)
    auth.applyLoginResult(result, u)
    if (auth.isAdmin) {
      registerAdminRoutes(router)
    }
    if (auth.isAccountDisabled) {
      Message.warning('登录成功，但账号已被禁用，做题、博客等功能暂不可用')
    } else {
      Message.success('登录成功')
    }
    emit('update:visible', false)
    loginForm.value = { username: '', password: '', code: '' }
    resetSlideCaptcha()
  } catch (e: unknown) {
    Message.error(mapError(e, '登录失败'))
    void nextTick(() => resetCaptchaAfterLoginFailure())
  } finally {
    loginLoading.value = false
  }
}

async function onRegister() {
  const { username, password, confirmPassword, email } = registerForm.value
  const uErr = validateRegisterUsername(username)
  if (uErr) {
    Message.warning(uErr)
    return
  }
  const pErr = validateRegisterPassword(password)
  if (pErr) {
    Message.warning(pErr)
    return
  }
  if (password !== confirmPassword) {
    Message.warning('两次输入的密码不一致')
    return
  }
  const mailErr = validateOptionalEmail(email)
  if (mailErr) {
    Message.warning(mailErr)
    return
  }
  registerLoading.value = true
  try {
    const result = await Service.register({
      username: username.trim(),
      password,
      confirmPassword,
      email: email.trim() || undefined,
    })
    if (!isResultSuccess(result.code)) {
      throw new Error(result.message || '注册失败')
    }
    Message.success('注册成功，请登录')
    tab.value = 'login'
    registerForm.value = { username: '', password: '', confirmPassword: '', email: '' }
    void nextTick(() => resetSlideCaptcha())
  } catch (e: unknown) {
    Message.error(mapError(e, '注册失败'))
  } finally {
    registerLoading.value = false
  }
}

onBeforeUnmount(() => {
  revokeCaptcha()
  stopRecoveryTimer()
})
</script>

<template>
  <a-modal
    v-model:visible="modalVisible"
    hide-title
    :closable="false"
    :footer="false"
    :mask-closable="true"
    :unmount-on-close="true"
    :modal-class="['auth-modal-shell', { 'auth-modal-shell--dark': isDarkShell }]"
    width="520px"
    :body-style="{ padding: '0' }"
  >
    <div
      class="auth-card"
      :class="{ 'auth-card--dark': isDarkShell, 'auth-card--revealed': contentReveal }"
    >
      <div class="auth-hero">
        <div class="auth-hero-gradient" aria-hidden="true" />
        <div class="auth-hero-noise" aria-hidden="true" />
        <button type="button" class="auth-close" aria-label="关闭" @click="modalVisible = false">
          <span aria-hidden="true">×</span>
        </button>
        <div class="auth-hero-inner">
          <div class="auth-logo-mark auth-anim auth-anim--1" aria-hidden="true">&lt;/&gt;</div>
          <h2 class="auth-hero-title auth-anim auth-anim--2">码跃 OJ</h2>
          <div class="auth-hero-sub-wrap auth-anim auth-anim--sub">
            <Transition name="auth-tab-hint" mode="out-in">
              <p :key="tab" class="auth-hero-sub">{{ tabHint }}</p>
            </Transition>
          </div>
        </div>
      </div>

      <div class="auth-body">
        <div v-if="tab !== 'recover'" class="auth-body-block auth-body-block--tabs">
          <div class="segmented" :class="{ 'is-register': tab === 'register' }" role="tablist">
            <div class="segmented-pill" aria-hidden="true" />
            <button
              type="button"
              role="tab"
              class="seg-btn"
              :class="{ active: tab === 'login' }"
              :aria-selected="tab === 'login'"
              @click="tab = 'login'"
            >
              登录
            </button>
            <button
              type="button"
              role="tab"
              class="seg-btn"
              :class="{ active: tab === 'register' }"
              :aria-selected="tab === 'register'"
              @click="tab = 'register'"
            >
              注册
            </button>
          </div>
        </div>

        <div class="auth-body-block auth-body-block--lead">
          <p class="auth-panel-lead">{{ panelLead }}</p>
        </div>

        <div class="auth-body-block auth-body-block--panel">
        <Transition name="auth-panel" mode="out-in">
          <div v-if="tab === 'login'" key="login" class="auth-panel">
            <a-form layout="vertical" class="auth-form">
              <a-form-item label="用户名 / QQ 邮箱" hide-asterisk class="auth-field">
                <a-input
                  v-model="loginForm.username"
                  allow-clear
                  size="large"
                  class="auth-input"
                  placeholder="用户名或注册时绑定的邮箱"
                  autocomplete="username"
                >
                  <template #prefix>
                    <span class="input-ico" aria-hidden="true">
                      <svg class="input-ico__svg" viewBox="0 0 24 24" aria-hidden="true">
                        <circle cx="12" cy="8.5" r="3.25" fill="none" stroke="currentColor" stroke-width="1.75" />
                        <path
                          fill="none"
                          stroke="currentColor"
                          stroke-width="1.75"
                          stroke-linecap="round"
                          d="M6.5 19.25v-.75a4.5 4.5 0 0 1 4.5-4.5h2a4.5 4.5 0 0 1 4.5 4.5v.75"
                        />
                      </svg>
                    </span>
                  </template>
                </a-input>
              </a-form-item>
              <a-form-item label="密码" hide-asterisk class="auth-field">
                <a-input-password v-model="loginForm.password" size="large" class="auth-input" placeholder="密码">
                  <template #prefix>
                    <span class="input-ico" aria-hidden="true">
                      <svg class="input-ico__svg" viewBox="0 0 24 24" aria-hidden="true">
                        <path
                          fill="none"
                          stroke="currentColor"
                          stroke-width="1.75"
                          stroke-linecap="round"
                          stroke-linejoin="round"
                          d="M8 11V8a4 4 0 0 1 8 0v3"
                        />
                        <rect
                          x="5"
                          y="11"
                          width="14"
                          height="10"
                          rx="2.25"
                          fill="none"
                          stroke="currentColor"
                          stroke-width="1.75"
                        />
                      </svg>
                    </span>
                  </template>
                </a-input-password>
              </a-form-item>
              <div class="auth-recovery-link-row">
                <button type="button" class="auth-recovery-link" @click="openRecovery">忘记密码？</button>
              </div>
              <a-form-item label="验证码" hide-asterisk class="auth-field captcha-form-item">
                <div
                  v-if="!captchaUnlocked"
                  ref="slideTrackRef"
                  class="slide-unlock"
                >
                  <div class="slide-unlock-bg">
                    <span class="slide-unlock-text">向右滑到底，加载图形验证码</span>
                  </div>
                  <div
                    class="slide-unlock-progress"
                    :style="{ width: `${slideOffset + 44 + 8}px` }"
                  />
                  <button
                    type="button"
                    class="slide-unlock-handle"
                    :class="{ dragging: slideDragging }"
                    :style="{ transform: `translateX(${slideOffset}px)` }"
                    aria-label="向右滑动"
                    @pointerdown="onSlidePointerDown"
                  >
                    <span class="slide-unlock-arrows" aria-hidden="true">››</span>
                  </button>
                </div>
                <template v-else>
                  <div class="captcha-unlocked">
                    <div
                      class="captcha-status"
                      :class="{
                        'captcha-status--loading': captchaImageLoading,
                        'captcha-status--error': !captchaImageLoading && !captchaUrl && captchaLoadFailed,
                        'captcha-status--ok': !captchaImageLoading && captchaUrl,
                        'captcha-status--wait': !captchaImageLoading && !captchaUrl && !captchaLoadFailed,
                      }"
                    >
                      <template v-if="captchaImageLoading">
                        <span class="captcha-status-pulse" aria-hidden="true" />
                        正在加载图形验证码…
                      </template>
                      <template v-else-if="!captchaUrl && captchaLoadFailed">
                        加载失败，请点击右侧区域重试
                      </template>
                      <template v-else-if="captchaUrl">
                        <span class="captcha-status-ok-ico" aria-hidden="true">✓</span>
                        人机验证已通过，请输入图中字符
                      </template>
                      <template v-else>正在准备验证码…</template>
                    </div>
                    <div class="captcha-row">
                      <a-input
                        v-model="loginForm.code"
                        allow-clear
                        size="large"
                        class="auth-input captcha-input"
                        placeholder="图中字符"
                        :disabled="captchaImageLoading || !captchaUrl"
                      />
                      <button
                        type="button"
                        class="captcha-tile"
                        :class="{ 'captcha-tile--loading': captchaImageLoading }"
                        title="点击刷新验证码"
                        @click="refreshCaptcha"
                      >
                        <img v-if="captchaUrl" :src="captchaUrl" alt="验证码" class="captcha-img" />
                        <span v-else-if="captchaImageLoading" class="captcha-placeholder">加载中…</span>
                        <span v-else class="captcha-placeholder">点击重试</span>
                      </button>
                    </div>
                    <p class="captcha-hint">看不清可点击图片刷新</p>
                  </div>
                </template>
              </a-form-item>
              <a-button
                type="primary"
                size="large"
                long
                class="auth-submit"
                :loading="loginLoading"
                @click="onLogin"
              >
                进入码跃
              </a-button>
              <p class="auth-legal">
                登录时会记录 IP、估算归属地及浏览器提供的设备概况，用于账号管理。
                注册或登录即代表您同意
                <RouterLink to="/legal/terms" class="auth-legal-link" @click="modalVisible = false">《服务协议》</RouterLink>
                和
                <RouterLink to="/legal/privacy" class="auth-legal-link" @click="modalVisible = false"
                  >《隐私政策》</RouterLink
                >
              </p>
            </a-form>
          </div>

          <div v-else-if="tab === 'recover'" key="recover" class="auth-panel">
            <div class="recovery-heading">
              <span class="recovery-heading__icon" aria-hidden="true">✉</span>
              <div>
                <strong>找回账号密码</strong>
                <p>验证码将发送到账号绑定的邮箱，10 分钟内有效。</p>
              </div>
            </div>
            <a-form layout="vertical" class="auth-form">
              <a-form-item label="注册邮箱" hide-asterisk class="auth-field">
                <a-input v-model="recoveryForm.email" type="email" size="large" class="auth-input" placeholder="请输入注册时绑定的邮箱" autocomplete="email" />
              </a-form-item>
              <a-form-item label="邮件验证码" hide-asterisk class="auth-field">
                <div class="recovery-code-row">
                  <a-input v-model="recoveryForm.code" :max-length="6" size="large" class="auth-input" placeholder="6 位数字验证码" inputmode="numeric" />
                  <a-button class="recovery-code-button" :loading="sendingRecoveryCode" :disabled="recoveryCountdown > 0" @click="sendRecoveryCode">
                    {{ recoveryCountdown > 0 ? `${recoveryCountdown}s 后重发` : '获取验证码' }}
                  </a-button>
                </div>
              </a-form-item>
              <a-form-item label="新密码" hide-asterisk class="auth-field">
                <a-input-password v-model="recoveryForm.newPassword" size="large" class="auth-input" placeholder="6～20 位字母和数字" autocomplete="new-password" />
              </a-form-item>
              <a-form-item label="确认新密码" hide-asterisk class="auth-field">
                <a-input-password v-model="recoveryForm.confirmPassword" size="large" class="auth-input" placeholder="再输入一次新密码" autocomplete="new-password" />
              </a-form-item>
              <a-button type="primary" size="large" long class="auth-submit" :loading="resettingPassword" @click="resetForgottenPassword">重置密码</a-button>
              <button type="button" class="recovery-back" @click="tab = 'login'">← 返回登录</button>
              <p class="recovery-help">账号没有绑定邮箱？请联系管理员核实身份后处理。</p>
            </a-form>
          </div>

          <div v-else key="register" class="auth-panel">
            <a-form layout="vertical" class="auth-form">
              <a-form-item label="用户名" hide-asterisk class="auth-field">
                <a-input
                  v-model="registerForm.username"
                  allow-clear
                  size="large"
                  class="auth-input"
                  placeholder="4～16 位字母、数字或下划线"
                >
                  <template #prefix>
                    <span class="input-ico" aria-hidden="true">
                      <svg class="input-ico__svg" viewBox="0 0 24 24" aria-hidden="true">
                        <circle cx="12" cy="8.5" r="3.25" fill="none" stroke="currentColor" stroke-width="1.75" />
                        <path
                          fill="none"
                          stroke="currentColor"
                          stroke-width="1.75"
                          stroke-linecap="round"
                          d="M6.5 19.25v-.75a4.5 4.5 0 0 1 4.5-4.5h2a4.5 4.5 0 0 1 4.5 4.5v.75"
                        />
                      </svg>
                    </span>
                  </template>
                </a-input>
              </a-form-item>
              <a-form-item label="邮箱（选填）" hide-asterisk class="auth-field">
                <a-input
                  v-model="registerForm.email"
                  allow-clear
                  size="large"
                  class="auth-input"
                  placeholder="建议填写，忘记密码时可用邮箱找回"
                >
                  <template #prefix>
                    <span class="input-ico" aria-hidden="true">
                      <svg class="input-ico__svg" viewBox="0 0 24 24" aria-hidden="true">
                        <rect
                          x="3"
                          y="5.5"
                          width="18"
                          height="13"
                          rx="2"
                          fill="none"
                          stroke="currentColor"
                          stroke-width="1.75"
                        />
                        <path
                          fill="none"
                          stroke="currentColor"
                          stroke-width="1.75"
                          stroke-linecap="round"
                          d="M3 7.5 12 13l9-5.5"
                        />
                      </svg>
                    </span>
                  </template>
                </a-input>
              </a-form-item>
              <a-form-item label="密码" hide-asterisk class="auth-field">
                <a-input-password
                  v-model="registerForm.password"
                  size="large"
                  class="auth-input"
                  placeholder="6～20 位，含字母与数字"
                >
                  <template #prefix>
                    <span class="input-ico" aria-hidden="true">
                      <svg class="input-ico__svg" viewBox="0 0 24 24" aria-hidden="true">
                        <path
                          fill="none"
                          stroke="currentColor"
                          stroke-width="1.75"
                          stroke-linecap="round"
                          stroke-linejoin="round"
                          d="M8 11V8a4 4 0 0 1 8 0v3"
                        />
                        <rect
                          x="5"
                          y="11"
                          width="14"
                          height="10"
                          rx="2.25"
                          fill="none"
                          stroke="currentColor"
                          stroke-width="1.75"
                        />
                      </svg>
                    </span>
                  </template>
                </a-input-password>
              </a-form-item>
              <a-form-item label="确认密码" hide-asterisk class="auth-field">
                <a-input-password
                  v-model="registerForm.confirmPassword"
                  size="large"
                  class="auth-input"
                  placeholder="再输入一次"
                >
                  <template #prefix>
                    <span class="input-ico" aria-hidden="true">
                      <svg class="input-ico__svg" viewBox="0 0 24 24" aria-hidden="true">
                        <path
                          fill="none"
                          stroke="currentColor"
                          stroke-width="1.75"
                          stroke-linecap="round"
                          stroke-linejoin="round"
                          d="M8 11V8a4 4 0 0 1 8 0v3"
                        />
                        <rect
                          x="5"
                          y="11"
                          width="14"
                          height="10"
                          rx="2.25"
                          fill="none"
                          stroke="currentColor"
                          stroke-width="1.75"
                        />
                      </svg>
                    </span>
                  </template>
                </a-input-password>
              </a-form-item>
              <a-button
                type="primary"
                size="large"
                long
                class="auth-submit auth-submit--secondary"
                :loading="registerLoading"
                @click="onRegister"
              >
                创建账号
              </a-button>
              <p class="auth-legal">
                注册或登录即代表您同意
                <RouterLink to="/legal/terms" class="auth-legal-link" @click="modalVisible = false">《服务协议》</RouterLink>
                和
                <RouterLink to="/legal/privacy" class="auth-legal-link" @click="modalVisible = false"
                  >《隐私政策》</RouterLink
                >
              </p>
            </a-form>
          </div>
        </Transition>
        </div>
      </div>
    </div>
  </a-modal>
</template>

<style scoped>
.auth-card {
  position: relative;
  border-radius: 22px;
  overflow: hidden;
  background: #fff;
  opacity: 0;
  transform: scale(0.94) translateY(12px);
  filter: saturate(0.96);
  transition:
    opacity 0.5s cubic-bezier(0.16, 1, 0.3, 1),
    transform 0.55s cubic-bezier(0.16, 1, 0.3, 1),
    filter 0.45s ease;
}

.auth-card--revealed {
  opacity: 1;
  transform: scale(1) translateY(0);
  filter: saturate(1);
}

.auth-hero {
  position: relative;
  padding: 32px 28px 28px;
  color: #fff;
  overflow: hidden;
}

.auth-close {
  position: absolute;
  top: 14px;
  right: 14px;
  z-index: 2;
  width: 36px;
  height: 36px;
  display: flex;
  align-items: center;
  justify-content: center;
  border: none;
  border-radius: 10px;
  background: rgba(255, 255, 255, 0.2);
  color: #fff;
  font-size: 22px;
  line-height: 1;
  cursor: pointer;
  transition:
    background 0.2s ease,
    transform 0.15s ease;
}

.auth-close:hover {
  background: rgba(255, 255, 255, 0.32);
}

.auth-close:active {
  transform: scale(0.94);
}

.auth-hero-gradient {
  position: absolute;
  inset: 0;
  background:
    radial-gradient(ellipse 90% 80% at 10% 0%, rgba(255, 255, 255, 0.18), transparent 50%),
    linear-gradient(135deg, #0c4f4a 0%, #0f766e 38%, #14b8a6 62%, #5eead4 100%);
  opacity: 1;
}

.auth-hero-gradient::after {
  content: '';
  position: absolute;
  inset: 0;
  background:
    radial-gradient(ellipse 80% 120% at 100% -20%, rgba(255, 255, 255, 0.38), transparent 55%),
    repeating-linear-gradient(
      -12deg,
      transparent,
      transparent 12px,
      rgba(255, 255, 255, 0.04) 12px,
      rgba(255, 255, 255, 0.04) 13px
    );
  pointer-events: none;
}

.auth-hero-noise {
  position: absolute;
  inset: 0;
  opacity: 0.07;
  background-image: url("data:image/svg+xml,%3Csvg viewBox='0 0 256 256' xmlns='http://www.w3.org/2000/svg'%3E%3Cfilter id='n'%3E%3CfeTurbulence type='fractalNoise' baseFrequency='0.85' numOctaves='4' stitchTiles='stitch'/%3E%3C/filter%3E%3Crect width='100%25' height='100%25' filter='url(%23n)'/%3E%3C/svg%3E");
  pointer-events: none;
  mix-blend-mode: overlay;
}

.auth-hero-inner {
  position: relative;
  z-index: 1;
}

.auth-anim {
  opacity: 0;
  transform: translateY(18px);
  transition:
    opacity 0.65s cubic-bezier(0.16, 1, 0.3, 1),
    transform 0.65s cubic-bezier(0.16, 1, 0.3, 1);
}

.auth-card--revealed .auth-anim--1 {
  opacity: 0.95;
  transform: translateY(0);
  transition-delay: 0.1s;
}

.auth-card--revealed .auth-anim--2 {
  opacity: 1;
  transform: translateY(0);
  transition-delay: 0.22s;
}

.auth-card--revealed .auth-anim--sub {
  opacity: 1;
  transform: translateY(0);
  transition-delay: 0.34s;
}

.auth-hero-sub-wrap {
  min-height: 2.4em;
}

.auth-logo-mark {
  font-size: 30px;
  line-height: 1;
  margin-bottom: 10px;
  filter: drop-shadow(0 2px 10px rgba(0, 0, 0, 0.18));
}

.auth-hero-title {
  margin: 0;
  font-size: clamp(24px, 4vw, 28px);
  font-family: var(--app-font-display);
  font-weight: 700;
  letter-spacing: 0.02em;
  text-shadow: 0 2px 14px rgba(0, 0, 0, 0.18);
}

.auth-hero-sub {
  margin: 12px 0 0;
  font-size: 14px;
  line-height: 1.55;
  max-width: 24em;
}

.auth-tab-hint-enter-active,
.auth-tab-hint-leave-active {
  transition:
    opacity 0.38s cubic-bezier(0.16, 1, 0.3, 1),
    transform 0.38s cubic-bezier(0.16, 1, 0.3, 1);
}

.auth-tab-hint-enter-from {
  opacity: 0;
  transform: translateY(8px);
}

.auth-tab-hint-leave-to {
  opacity: 0;
  transform: translateY(-6px);
}

.auth-body {
  padding: 22px 28px 34px;
  background: linear-gradient(180deg, #f7faf9 0%, #ffffff 42%, #ffffff 100%);
}

.auth-body-block {
  opacity: 0;
  transform: translateY(16px);
  transition:
    opacity 0.55s cubic-bezier(0.16, 1, 0.3, 1),
    transform 0.55s cubic-bezier(0.16, 1, 0.3, 1);
}

.auth-card--revealed .auth-body-block--tabs {
  opacity: 1;
  transform: translateY(0);
  transition-delay: 0.4s;
}

.auth-card--revealed .auth-body-block--lead {
  opacity: 1;
  transform: translateY(0);
  transition-delay: 0.5s;
}

.auth-card--revealed .auth-body-block--panel {
  opacity: 1;
  transform: translateY(0);
  transition-delay: 0.58s;
}

.auth-panel-lead {
  margin: 0 0 16px;
  font-size: 13px;
  line-height: 1.6;
  color: #64748b;
  letter-spacing: 0.02em;
}

.segmented {
  position: relative;
  display: flex;
  padding: 4px;
  margin-bottom: 22px;
  background: linear-gradient(180deg, #ecfdf5 0%, #f0fdfa 100%);
  border-radius: 14px;
  border: 1px solid rgba(13, 148, 136, 0.12);
}

.segmented-pill {
  position: absolute;
  top: 4px;
  left: 4px;
  width: calc(50% - 4px);
  height: calc(100% - 8px);
  background: #fff;
  border-radius: 11px;
  box-shadow: 0 4px 14px rgba(13, 148, 136, 0.18);
  transition: transform 0.32s cubic-bezier(0.34, 1.3, 0.64, 1);
  pointer-events: none;
}

.segmented.is-register .segmented-pill {
  transform: translateX(100%);
}

.seg-btn {
  position: relative;
  z-index: 1;
  flex: 1;
  padding: 11px 12px;
  border: none;
  background: transparent;
  font-size: 15px;
  font-weight: 600;
  color: #64748b;
  cursor: pointer;
  border-radius: 11px;
  transition: color 0.22s ease;
}

.seg-btn.active {
  color: #0f766e;
}

.seg-btn:focus-visible {
  outline: 2px solid #14b8a6;
  outline-offset: 2px;
}

.auth-panel {
  min-height: 1px;
}

.auth-form :deep(.arco-form-item) {
  margin-bottom: 22px;
}

.auth-form :deep(.arco-form-item-label-col) {
  font-size: 12px;
  font-weight: 600;
  letter-spacing: 0.05em;
  color: #64748b;
  margin-bottom: 10px !important;
}

/* 滑块轨道内全是 absolute，在 Arco 表单项 flex 里父级宽度会塌成 0，须拉满一行 */
.auth-form :deep(.captcha-form-item) {
  width: 100%;
}

.auth-form :deep(.captcha-form-item .arco-form-item-content-wrapper),
.auth-form :deep(.captcha-form-item .arco-form-item-content) {
  width: 100%;
  min-width: 0;
  max-width: 100%;
}

.auth-form :deep(.captcha-form-item .arco-form-item-content-flex) {
  flex: 1 1 auto;
  min-width: 0;
  width: 100%;
}

/* —— 输入框：更高、暖灰底、hover/focus 分层清晰 —— */
.auth-form .auth-field :deep(.arco-input-wrapper),
.auth-form .auth-field :deep(.arco-input-password) {
  min-height: 52px;
  border-radius: 16px !important;
  border: 1px solid rgba(15, 23, 42, 0.08) !important;
  background: #fafaf8 !important;
  box-shadow: 0 1px 2px rgba(15, 23, 42, 0.04) !important;
  transition:
    border-color 0.2s ease,
    box-shadow 0.2s ease,
    background 0.2s ease !important;
}

.auth-form .auth-field :deep(.arco-input),
.auth-form .auth-field :deep(.arco-input-password input) {
  font-size: 15px !important;
  font-weight: 500;
  color: #0f172a !important;
}

.auth-form .auth-field :deep(.arco-input::placeholder),
.auth-form .auth-field :deep(.arco-input-password input::placeholder) {
  color: #94a3b8 !important;
  font-weight: 400;
}

.auth-form .auth-field :deep(.arco-input-wrapper:hover),
.auth-form .auth-field :deep(.arco-input-password:hover) {
  background: #ffffff !important;
  border-color: rgba(13, 148, 136, 0.28) !important;
  box-shadow: 0 2px 10px rgba(15, 23, 42, 0.06) !important;
}

.auth-form .auth-field :deep(.arco-input-wrapper.arco-input-focus),
.auth-form .auth-field :deep(.arco-input-password.arco-input-focus) {
  background: #ffffff !important;
  border-color: rgba(13, 148, 136, 0.5) !important;
  box-shadow:
    inset 3px 0 0 0 #14b8a6,
    0 0 0 3px rgba(20, 184, 166, 0.12),
    0 4px 16px rgba(15, 23, 42, 0.06) !important;
}

.auth-form .auth-field :deep(.arco-input-prefix),
.auth-form .auth-field :deep(.arco-input-suffix) {
  padding-left: 6px;
  padding-right: 6px;
}

.auth-form .auth-field :deep(.arco-icon-hover::before) {
  border-radius: 8px;
}

.input-ico {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  margin-right: 4px;
  color: #64748b;
}

.input-ico__svg {
  width: 18px;
  height: 18px;
  display: block;
}

.captcha-row {
  display: flex;
  gap: 12px;
  align-items: stretch;
}

.captcha-input {
  flex: 1;
}

.captcha-tile {
  width: 118px;
  min-height: 52px;
  padding: 0;
  border: 1px dashed rgba(13, 148, 136, 0.4);
  border-radius: 16px;
  overflow: hidden;
  cursor: pointer;
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(180deg, #ffffff 0%, #f1f5f9 100%);
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.9);
  transition:
    border-color 0.2s ease,
    transform 0.18s ease,
    box-shadow 0.2s ease;
}

.auth-card--dark .captcha-tile {
  background: linear-gradient(180deg, rgba(51, 65, 85, 0.5) 0%, rgba(30, 41, 59, 0.75) 100%);
  border-color: rgba(83, 219, 194, 0.35);
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.05);
}

.captcha-tile:hover {
  border-color: #14b8a6;
  box-shadow: 0 4px 12px rgba(13, 148, 136, 0.15);
  transform: scale(1.02);
}

.captcha-tile:active {
  transform: scale(0.98);
}

.captcha-tile--loading {
  pointer-events: none;
  opacity: 0.85;
}

.captcha-img {
  width: 100%;
  height: 100%;
  min-height: 36px;
  object-fit: cover;
  display: block;
}

.captcha-placeholder {
  font-size: 12px;
  color: #94a3b8;
  padding: 8px;
}

.captcha-hint {
  margin: 10px 0 0;
  font-size: 12px;
  color: #94a3b8;
  line-height: 1.45;
}

.captcha-unlocked {
  width: 100%;
}

.captcha-status {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 10px;
  padding: 10px 12px;
  border-radius: 10px;
  font-size: 12px;
  line-height: 1.45;
  border: 1px solid transparent;
  transition:
    background 0.2s ease,
    border-color 0.2s ease;
}

.captcha-status--loading {
  background: rgba(241, 245, 249, 0.95);
  border-color: rgba(148, 163, 184, 0.35);
  color: #475569;
}

.captcha-status--wait {
  background: rgba(240, 253, 250, 0.65);
  border-color: rgba(45, 212, 191, 0.25);
  color: #0f766e;
}

.captcha-status--error {
  background: rgba(254, 242, 242, 0.85);
  border-color: rgba(248, 113, 113, 0.35);
  color: #b91c1c;
}

.captcha-status--ok {
  background: linear-gradient(135deg, rgba(236, 253, 245, 0.95) 0%, rgba(240, 253, 250, 0.85) 100%);
  border-color: rgba(45, 212, 191, 0.4);
  color: #0f766e;
  font-weight: 500;
}

.captcha-status-ok-ico {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 18px;
  height: 18px;
  border-radius: 999px;
  background: #14b8a6;
  color: #fff;
  font-size: 11px;
  font-weight: 700;
  flex-shrink: 0;
}

.captcha-status-pulse {
  width: 8px;
  height: 8px;
  border-radius: 999px;
  background: #14b8a6;
  flex-shrink: 0;
  animation: captcha-pulse 1s ease-in-out infinite;
}

@keyframes captcha-pulse {
  0%,
  100% {
    opacity: 0.45;
    transform: scale(0.92);
  }
  50% {
    opacity: 1;
    transform: scale(1);
  }
}

.slide-unlock {
  position: relative;
  /* 在 content-flex 里作 flex 子项：子节点全为 absolute 时须 grow，否则主轴宽度会塌成 0 */
  flex: 1 1 0;
  width: 100%;
  min-width: 0;
  border-radius: 16px;
  background: linear-gradient(180deg, #eef2f6 0%, #e2e8f0 100%);
  overflow: hidden;
  user-select: none;
  touch-action: none;
  border: 1px solid rgba(13, 148, 136, 0.22);
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.65);
  box-sizing: border-box;
}

.auth-card--dark .slide-unlock {
  background: linear-gradient(180deg, rgba(51, 65, 85, 0.5) 0%, rgba(30, 41, 59, 0.65) 100%);
  border-color: rgba(83, 219, 194, 0.22);
  box-shadow: inset 0 1px 0 rgba(255, 255, 255, 0.05);
}

.auth-card--dark .slide-unlock-text {
  color: #94a3b8;
}

/* 占住布局宽度/高度，否则仅含 absolute 子节点时 flex 下宽度为 0，滑块看不见 */
.slide-unlock::before {
  content: '';
  display: block;
  width: 100%;
  height: 52px;
  pointer-events: none;
}

.slide-unlock-bg {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 0 52px 0 56px;
}

.slide-unlock-text {
  font-size: 13px;
  color: #64748b;
  font-weight: 500;
}

.slide-unlock-progress {
  position: absolute;
  left: 0;
  top: 0;
  bottom: 0;
  max-width: 100%;
  background: linear-gradient(90deg, rgba(20, 184, 166, 0.2), rgba(20, 184, 166, 0.08));
  border-radius: 12px 0 0 12px;
  pointer-events: none;
  transition: width 0.08s linear;
}

.slide-unlock-handle {
  position: absolute;
  left: 8px;
  top: 50%;
  margin-top: -20px;
  width: 44px;
  height: 40px;
  border: 1px solid rgba(13, 148, 136, 0.45);
  border-radius: 12px;
  background: linear-gradient(180deg, #ffffff 0%, #f0fdfa 100%);
  box-shadow:
    0 2px 10px rgba(13, 148, 136, 0.2),
    inset 0 1px 0 rgba(255, 255, 255, 0.95);
  cursor: grab;
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 2;
  transition: transform 0.22s cubic-bezier(0.34, 1.2, 0.64, 1);
  color: #0f766e;
  font-weight: 700;
}

.auth-card--dark .slide-unlock-handle {
  background: linear-gradient(180deg, rgba(51, 65, 85, 0.95) 0%, rgba(30, 41, 59, 0.98) 100%);
  border-color: rgba(45, 212, 191, 0.45);
  color: #5eead4;
  box-shadow:
    0 2px 14px rgba(0, 0, 0, 0.35),
    inset 0 1px 0 rgba(255, 255, 255, 0.08);
}

.slide-unlock-handle.dragging {
  cursor: grabbing;
  transition: none;
  box-shadow: 0 4px 16px rgba(13, 148, 136, 0.3);
}

.slide-unlock-arrows {
  font-size: 18px;
  line-height: 1;
  letter-spacing: -2px;
}

.auth-submit {
  margin-top: 8px;
  height: 46px !important;
  border-radius: 12px !important;
  font-size: 15px !important;
  font-weight: 600 !important;
  background: linear-gradient(135deg, #0d9488 0%, #14b8a6 100%) !important;
  border: none !important;
  box-shadow: 0 8px 20px rgba(13, 148, 136, 0.35);
  transition:
    filter 0.2s ease,
    transform 0.15s ease,
    box-shadow 0.2s ease !important;
}

.auth-submit:hover:not(:disabled) {
  filter: brightness(1.06);
  box-shadow: 0 10px 28px rgba(13, 148, 136, 0.4) !important;
}

.auth-submit:active:not(:disabled) {
  transform: translateY(1px);
}

.auth-submit--secondary {
  background: linear-gradient(135deg, #0f766e 0%, #0d9488 55%, #2dd4bf 100%) !important;
}

.auth-recovery-link-row { display: flex; justify-content: flex-end; margin: -10px 0 18px; }
.auth-recovery-link, .recovery-back { border: 0; background: transparent; color: #0f766e; font: inherit; font-size: 13px; font-weight: 650; cursor: pointer; }
.auth-recovery-link:hover, .recovery-back:hover { text-decoration: underline; }
.auth-recovery-link:focus-visible, .recovery-back:focus-visible { outline: 2px solid #14b8a6; outline-offset: 3px; border-radius: 4px; }
.recovery-heading { display: flex; gap: 13px; padding: 16px; margin: 0 0 21px; border: 1px solid #bdece5; border-radius: 14px; background: linear-gradient(120deg, #edfdf9, #f5fbff); }
.recovery-heading__icon { display: grid; place-items: center; width: 38px; height: 38px; flex: none; border-radius: 11px; color: white; background: #0d9488; font-size: 21px; }
.recovery-heading strong { font-size: 15px; color: #134e4a; }
.recovery-heading p { margin: 5px 0 0; font-size: 12px; line-height: 1.5; color: #64748b; }
.recovery-code-row { display: flex; width: 100%; gap: 9px; }
.recovery-code-row > .auth-input { min-width: 0; flex: 1; }
.recovery-code-button { height: 46px; min-width: 116px; border-radius: 11px; color: #0f766e; border-color: #8bd9ce; }
.recovery-back { display: block; margin: 18px auto 0; }
.recovery-help { margin: 18px 0 0; text-align: center; font-size: 12px; line-height: 1.5; color: #64748b; }
.auth-card--dark .recovery-heading { background: #122c34; border-color: #24555a; }
.auth-card--dark .recovery-heading strong { color: #ccfbf1; }
.auth-card--dark .recovery-heading p, .auth-card--dark .recovery-help { color: #94a3b8; }
.auth-card--dark .auth-recovery-link, .auth-card--dark .recovery-back { color: #5eead4; }
.auth-card--dark .recovery-code-button { color: #5eead4; background: #122c34; border-color: #24555a; }

.auth-legal {
  margin: 12px 0 0;
  text-align: center;
  font-size: 12px;
  line-height: 1.6;
  color: #94a3b8;
}

.auth-legal-link {
  color: #0f766e;
  text-decoration: none;
  font-weight: 600;
  margin: 0 2px;
}

.auth-legal-link:hover {
  color: #0d9488;
  text-decoration: underline;
}

.auth-panel-enter-active,
.auth-panel-leave-active {
  transition:
    opacity 0.4s cubic-bezier(0.16, 1, 0.3, 1),
    transform 0.4s cubic-bezier(0.16, 1, 0.3, 1);
}

.auth-panel-enter-from {
  opacity: 0;
  transform: translateY(14px);
}

.auth-panel-leave-to {
  opacity: 0;
  transform: translateY(-10px);
}

/* —— 深色主题：与全站 data-theme=dark 一致 —— */
.auth-card--dark {
  background: linear-gradient(180deg, #111a22 0%, #0d141c 100%);
  box-shadow: 0 0 0 1px rgba(83, 219, 194, 0.08) inset;
}

.auth-card--dark .auth-hero-gradient {
  background:
    radial-gradient(ellipse 85% 75% at 8% 0%, rgba(255, 255, 255, 0.1), transparent 52%),
    linear-gradient(135deg, #042f2e 0%, #0f766e 36%, #115e59 68%, #134e4a 100%);
}

.auth-card--dark .auth-body {
  background: linear-gradient(180deg, rgba(17, 26, 34, 0.98) 0%, rgba(13, 20, 28, 0.99) 100%);
}

.auth-card--dark .auth-panel-lead {
  color: #94a3b8;
}

.auth-card--dark .segmented {
  background: linear-gradient(180deg, rgba(15, 118, 110, 0.14) 0%, rgba(15, 118, 110, 0.06) 100%);
  border-color: rgba(83, 219, 194, 0.2);
}

.auth-card--dark .segmented-pill {
  background: rgba(30, 41, 59, 0.95);
  box-shadow: 0 4px 18px rgba(0, 0, 0, 0.35);
}

.auth-card--dark .seg-btn {
  color: #94a3b8;
}

.auth-card--dark .seg-btn.active {
  color: #5eead4;
}

.auth-card--dark .auth-form :deep(.arco-form-item-label-col) {
  color: #e2e8f0;
}

.auth-card--dark .input-ico {
  color: #94a3b8;
}

.auth-card--dark .auth-form .auth-field :deep(.arco-input-wrapper),
.auth-card--dark .auth-form .auth-field :deep(.arco-input-password) {
  background: rgba(30, 41, 59, 0.55) !important;
  border: 1px solid rgba(148, 163, 184, 0.14) !important;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.2) !important;
  backdrop-filter: blur(10px);
  -webkit-backdrop-filter: blur(10px);
}

.auth-card--dark .auth-form .auth-field :deep(.arco-input),
.auth-card--dark .auth-form .auth-field :deep(.arco-input-password input) {
  color: #f1f5f9 !important;
  background: transparent !important;
}

.auth-card--dark .auth-form .auth-field :deep(.arco-input::placeholder),
.auth-card--dark .auth-form .auth-field :deep(.arco-input-password input::placeholder) {
  color: #64748b !important;
}

.auth-card--dark .auth-form .auth-field :deep(.arco-input-wrapper:hover),
.auth-card--dark .auth-form .auth-field :deep(.arco-input-password:hover) {
  background: rgba(51, 65, 85, 0.45) !important;
  border-color: rgba(94, 234, 212, 0.28) !important;
  box-shadow: 0 2px 12px rgba(0, 0, 0, 0.28) !important;
}

.auth-card--dark .auth-form .auth-field :deep(.arco-input-wrapper.arco-input-focus),
.auth-card--dark .auth-form .auth-field :deep(.arco-input-password.arco-input-focus) {
  border-color: rgba(45, 212, 191, 0.42) !important;
  box-shadow:
    inset 3px 0 0 0 #2dd4bf,
    0 0 0 3px rgba(45, 212, 191, 0.14),
    0 4px 18px rgba(0, 0, 0, 0.32) !important;
}

.auth-card--dark .auth-form .auth-field :deep(.arco-icon),
.auth-card--dark .auth-form .auth-field :deep(.arco-icon-hover) {
  color: #94a3b8 !important;
}

.auth-card--dark .auth-legal {
  color: #64748b;
}

.auth-card--dark .auth-legal-link {
  color: #5eead4;
}

@media (prefers-reduced-motion: reduce) {
  .auth-card {
    opacity: 1 !important;
    transform: none !important;
    filter: none !important;
    transition: none !important;
  }

  .auth-anim,
  .auth-body-block {
    opacity: 1 !important;
    transform: none !important;
    transition: none !important;
  }

  .auth-tab-hint-enter-active,
  .auth-tab-hint-leave-active,
  .auth-panel-enter-active,
  .auth-panel-leave-active {
    transition: none !important;
  }
}
</style>

<style>
.auth-modal-shell.arco-modal {
  border-radius: 22px;
  overflow: hidden;
  border: 1px solid rgba(13, 148, 136, 0.18);
  box-shadow:
    0 32px 64px rgba(15, 118, 110, 0.16),
    0 16px 40px rgba(15, 23, 42, 0.1),
    0 0 0 1px rgba(255, 255, 255, 0.06) inset;
}

.auth-modal-shell--dark.arco-modal {
  border-color: rgba(83, 219, 194, 0.22);
  box-shadow:
    0 36px 72px rgba(0, 0, 0, 0.55),
    0 0 0 1px rgba(83, 219, 194, 0.1) inset;
}

.auth-modal-shell .arco-modal-body {
  padding: 0 !important;
}
</style>
