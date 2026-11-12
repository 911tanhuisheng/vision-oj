import axios, { type AxiosResponse, type InternalAxiosRequestConfig } from 'axios'
import { getActivePinia } from 'pinia'
import {
  ACCOUNT_DISABLED_CODE,
  ACCOUNT_SESSION_REVOKED_CODE,
  COMMENT_PROFANITY_BANNED_CODE,
  AI_ASSIST_PROFANITY_BANNED_CODE,
  LOGIN_ELSEWHERE_CODE,
  isAccessTokenExpiredCode,
} from '@/api/result'
import { useAuthStore } from '@/stores/auth'

export type RetryableAxiosConfig = InternalAxiosRequestConfig & {
  /** 已用新 access 重试过，避免死循环 */
  __myojAuthRetried?: boolean
  /** 换票请求本身跳过拦截 */
  __myojSkipAuthRefresh?: boolean
}

function isAuthBypassUrl(url?: string): boolean {
  if (!url) return false
  return /\/user\/(login|refresh|register|password\/reset(?:\/code)?)(\/|\?|$)/i.test(url)
}

function readBodyCode(response: AxiosResponse): number | undefined {
  const data = response.data
  if (!data || typeof data !== 'object' || !('code' in data)) return undefined
  const code = (data as { code?: unknown }).code
  return typeof code === 'number' ? code : undefined
}

/**
 * access 失效需换票：HTTP 401，或 Spring Result 体里 code=401（HTTP 常为 200）。
 * 排除账号禁用类业务码，避免误 logout。
 */
function needsAccessTokenRefresh(response: AxiosResponse): boolean {
  const code = readBodyCode(response)
  if (
    code === ACCOUNT_DISABLED_CODE ||
    code === ACCOUNT_SESSION_REVOKED_CODE ||
    code === COMMENT_PROFANITY_BANNED_CODE ||
    code === AI_ASSIST_PROFANITY_BANNED_CODE ||
    code === LOGIN_ELSEWHERE_CODE
  ) {
    return false
  }
  if (response.status === 401) return true
  return isAccessTokenExpiredCode(code)
}

function handleAccountBusinessCodes(response: AxiosResponse): void {
  const code = readBodyCode(response)
  if (
    code !== ACCOUNT_SESSION_REVOKED_CODE &&
    code !== ACCOUNT_DISABLED_CODE &&
    code !== COMMENT_PROFANITY_BANNED_CODE &&
    code !== AI_ASSIST_PROFANITY_BANNED_CODE &&
    code !== LOGIN_ELSEWHERE_CODE
  ) {
    return
  }
  const pinia = getActivePinia()
  if (!pinia) return
  const auth = useAuthStore(pinia)
  if (!auth.isLoggedIn) return

  if (code === ACCOUNT_SESSION_REVOKED_CODE) {
    auth.logout()
    window.dispatchEvent(new CustomEvent('myoj:account-revoked'))
    return
  }
  if (code === LOGIN_ELSEWHERE_CODE) {
    auth.logout()
    window.dispatchEvent(new CustomEvent('myoj:login-elsewhere'))
    return
  }
  if (code === COMMENT_PROFANITY_BANNED_CODE || code === AI_ASSIST_PROFANITY_BANNED_CODE) {
    void auth.syncSessionStatus()
    return
  }
  auth.setAccountStatus(0)
  void auth.syncSessionStatus()
  window.dispatchEvent(new CustomEvent('myoj:account-disabled'))
}

function dispatchRefreshFailed(response: AxiosResponse): void {
  const pinia = getActivePinia()
  if (!pinia) return
  const auth = useAuthStore(pinia)
  const code = readBodyCode(response)

  auth.logout()
  if (code === LOGIN_ELSEWHERE_CODE) {
    window.dispatchEvent(new CustomEvent('myoj:login-elsewhere'))
  } else if (code === ACCOUNT_SESSION_REVOKED_CODE || auth.isAccountDisabled) {
    window.dispatchEvent(new CustomEvent('myoj:account-revoked'))
  } else {
    window.dispatchEvent(new CustomEvent('myoj:session-expired'))
  }
}

async function tryRefreshAndRetry(response: AxiosResponse): Promise<AxiosResponse> {
  const cfg = response.config as RetryableAxiosConfig
  if (cfg.__myojSkipAuthRefresh || cfg.__myojAuthRetried || isAuthBypassUrl(cfg.url)) {
    return response
  }
  if (!needsAccessTokenRefresh(response)) {
    return response
  }

  const pinia = getActivePinia()
  if (!pinia) return response
  const auth = useAuthStore(pinia)
  if (!auth.isLoggedIn) return response

  if (!auth.refreshToken?.trim()) {
    dispatchRefreshFailed(response)
    return response
  }

  const ok = await auth.refreshSession()
  if (!ok) {
    dispatchRefreshFailed(response)
    return response
  }

  cfg.__myojAuthRetried = true
  const access = auth.accessToken ?? auth.token
  if (access) {
    cfg.headers = cfg.headers ?? {}
    cfg.headers.Authorization = `Bearer ${access}`
  }
  return axios.request(cfg)
}

/**
 * access 过期时自动 refresh 并重试原请求（用户无感知）。
 * 业务 code 40303/40304 走账号禁用流程，不当作 token 过期。
 */
export function setupAuthInterceptor(): void {
  const onResponse = async (response: AxiosResponse) => {
    handleAccountBusinessCodes(response)
    return tryRefreshAndRetry(response)
  }

  axios.interceptors.response.use(onResponse, async (error) => {
    if (axios.isAxiosError(error) && error.response) {
      return onResponse(error.response)
    }
    return Promise.reject(error)
  })
}
