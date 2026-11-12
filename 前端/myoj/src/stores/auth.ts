import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import axios from 'axios'
import type { LoginVO, ResultLoginVO } from '@generated'
import { OpenAPI } from '@generated'
import { refreshTokensWithApi } from '@/api/authRefresh'
import { isResultSuccess } from '@/api/result'
import { AUTH_STORAGE_KEY } from '@/config/auth-storage'
import { resolveMediaUrl } from '@/utils/resolveMediaUrl'
import { parseBanUntilMs } from '@/utils/formatBanCountdown'
import { isJwtExpired } from '@/utils/jwtPayload'

export interface AuthPayload {
  /** 短期 access，请求头 Bearer 使用 */
  token: string
  accessToken?: string
  /** 长期 refresh，仅用于 /user/refresh */
  refreshToken?: string
  id?: string
  username?: string
  userRole?: string
  avatar?: string
  /** 0-禁用 1-正常 */
  accountStatus?: number
  banType?: string
  banReason?: string
  banUntil?: string
  commentBanUntil?: string
  commentBanReason?: string
  aiBanUntil?: string
  aiBanReason?: string
}

/** 与后端约定：角色字段包含 ADMIN 或中文「管理员」即视为管理员 */
export function isAdminRole(role: string | undefined | null): boolean {
  if (!role) return false
  const r = role.trim()
  if (/管理员|admin|ADMIN/i.test(r)) return true
  return false
}

function loadFromStorage(): AuthPayload | null {
  try {
    const raw = localStorage.getItem(AUTH_STORAGE_KEY)
    if (!raw) return null
    return JSON.parse(raw) as AuthPayload
  } catch {
    return null
  }
}

/** 从登录/存储载荷解析 access（兼容仅 token 的旧数据） */
export function resolveAccessToken(payload: {
  accessToken?: string | null
  token?: string | null
}): string {
  return (payload.accessToken ?? payload.token ?? '').trim()
}

export const useAuthStore = defineStore('auth', () => {
  /** 短期 access token（与历史字段 token 同步） */
  const token = ref<string | null>(null)
  const refreshToken = ref<string | null>(null)
  const userId = ref<string | null>(null)
  const username = ref<string | null>(null)
  const userRole = ref<string | null>(null)
  const avatar = ref<string | null>(null)
  /** 0-禁用 1-正常；null 表示未知（旧会话默认按正常处理直到拉取到状态） */
  const accountStatus = ref<number | null>(null)
  const banType = ref<string | null>(null)
  const banReason = ref<string | null>(null)
  const banUntil = ref<string | null>(null)
  const commentBanUntil = ref<string | null>(null)
  const commentBanReason = ref<string | null>(null)
  const aiBanUntil = ref<string | null>(null)
  const aiBanReason = ref<string | null>(null)

  const accessToken = computed(() => token.value)

  function persistAuthPayload() {
    const access = token.value?.trim()
    if (!access) return
    localStorage.setItem(
      AUTH_STORAGE_KEY,
      JSON.stringify({
        token: access,
        accessToken: access,
        refreshToken: refreshToken.value?.trim() || undefined,
        id: userId.value ?? undefined,
        username: username.value ?? undefined,
        userRole: userRole.value ?? undefined,
        avatar: avatar.value ?? undefined,
        accountStatus: accountStatus.value ?? undefined,
        banType: banType.value ?? undefined,
        banReason: banReason.value ?? undefined,
        banUntil: banUntil.value ?? undefined,
        commentBanUntil: commentBanUntil.value ?? undefined,
        commentBanReason: commentBanReason.value ?? undefined,
        aiBanUntil: aiBanUntil.value ?? undefined,
        aiBanReason: aiBanReason.value ?? undefined,
      } satisfies AuthPayload),
    )
  }

  function applyTokens(access: string, refresh: string) {
    token.value = access
    refreshToken.value = refresh
    persistAuthPayload()
  }

  function persistFromLoginVO(data: LoginVO, usernameHint?: string) {
    const access = resolveAccessToken(data)
    const refresh = (data.refreshToken ?? '').trim()
    if (!access) throw new Error('登录失败：未返回 accessToken')
    if (!refresh) throw new Error('登录失败：未返回 refreshToken')
    applyTokens(access, refresh)

    const rawId = data.id as string | number | null | undefined
    if (rawId == null || rawId === '') {
      userId.value = null
    } else if (typeof rawId === 'string') {
      userId.value = rawId.trim() || null
    } else {
      if (import.meta.env.DEV && !Number.isSafeInteger(rawId)) {
        console.warn(
          '[auth] 登录返回的 user id 为 number 且超出安全整数，请确保接口将 id 以 JSON 字符串返回。',
        )
      }
      userId.value = String(rawId)
    }
    const fromApi = data.username?.trim()
    const fromForm = usernameHint?.trim()
    username.value = fromApi || fromForm || null
    userRole.value = data.userRole ?? null
    avatar.value = data.avatar?.trim() || null
    accountStatus.value = typeof data.status === 'number' ? data.status : 1
    persistAuthPayload()
  }

  function setAccountStatus(status: number | null | undefined) {
    if (status === undefined || status === null) {
      accountStatus.value = null
    } else {
      accountStatus.value = status
    }
    persistAuthPayload()
  }

  function applySessionStatus(payload: {
    status?: number
    banType?: string | null
    banReason?: string | null
    banUntil?: string | null
    commentBanned?: boolean
    commentBanUntil?: string | null
    commentBanReason?: string | null
    aiAssistBanned?: boolean
    aiBanUntil?: string | null
    aiBanReason?: string | null
  }) {
    if (typeof payload.status === 'number') {
      accountStatus.value = payload.status
    }
    banType.value = payload.banType?.trim() || null
    banReason.value = payload.banReason?.trim() || null
    banUntil.value = payload.banUntil?.trim() || null
    commentBanUntil.value = payload.commentBanUntil?.trim() || null
    commentBanReason.value = payload.commentBanReason?.trim() || null
    aiBanUntil.value = payload.aiBanUntil?.trim() || null
    aiBanReason.value = payload.aiBanReason?.trim() || null
    const commentEndMs = parseBanUntilMs(commentBanUntil.value)
    const aiEndMs = parseBanUntilMs(aiBanUntil.value)
    const legacyEndMs = parseBanUntilMs(banUntil.value)
    const commentBanned =
      payload.commentBanned === true ||
      (commentEndMs != null && commentEndMs > Date.now()) ||
      (banType.value === 'profanity' && legacyEndMs != null && legacyEndMs > Date.now())
    const aiAssistBanned =
      payload.aiAssistBanned === true ||
      (aiEndMs != null && aiEndMs > Date.now()) ||
      (banType.value === 'profanity_ai' && legacyEndMs != null && legacyEndMs > Date.now())
    if (!commentBanned) {
      commentBanUntil.value = null
      commentBanReason.value = null
    }
    if (!aiAssistBanned) {
      aiBanUntil.value = null
      aiBanReason.value = null
    }
    if (!commentBanned && !aiAssistBanned && accountStatus.value === 1) {
      banType.value = null
      banReason.value = null
      banUntil.value = null
    }
    persistAuthPayload()
  }

  function clearSession() {
    token.value = null
    refreshToken.value = null
    userId.value = null
    username.value = null
    userRole.value = null
    avatar.value = null
    accountStatus.value = null
    banType.value = null
    banReason.value = null
    banUntil.value = null
    commentBanUntil.value = null
    commentBanReason.value = null
    aiBanUntil.value = null
    aiBanReason.value = null
    localStorage.removeItem(AUTH_STORAGE_KEY)
  }

  function applyLoginResult(res: ResultLoginVO, loginUsernameHint?: string) {
    if (!isResultSuccess(res.code) || !res.data) {
      throw new Error(res.message || '登录失败')
    }
    persistFromLoginVO(res.data, loginUsernameHint)
    return res.data
  }

  /**
   * access 过期时由 axios 拦截器调用：用 refresh 换一对新令牌。
   * @returns 是否换票成功
   */
  async function refreshSession(): Promise<boolean> {
    const rt = refreshToken.value?.trim()
    if (!rt) return false
    const pair = await refreshTokensWithApi(rt)
    if (!pair) return false
    applyTokens(pair.accessToken!, pair.refreshToken!)
    return true
  }

  function hydrate() {
    const saved = loadFromStorage()
    if (!saved) return
    const access = resolveAccessToken(saved)
    if (access) token.value = access
    refreshToken.value = saved.refreshToken?.trim() || null
    userId.value = saved.id ?? null
    username.value = saved.username ?? null
    userRole.value = saved.userRole ?? null
    avatar.value = saved.avatar ?? null
    accountStatus.value =
      typeof saved.accountStatus === 'number' ? saved.accountStatus : null
    banType.value = saved.banType?.trim() || null
    banReason.value = saved.banReason?.trim() || null
    banUntil.value = saved.banUntil?.trim() || null
    commentBanUntil.value = saved.commentBanUntil?.trim() || null
    commentBanReason.value = saved.commentBanReason?.trim() || null
    aiBanUntil.value = saved.aiBanUntil?.trim() || null
    aiBanReason.value = saved.aiBanReason?.trim() || null
  }

  /**
   * 启动或 access 将过期时主动换票，避免带着过期 token 打一堆 401。
   * refresh 也过期时会 logout。
   */
  async function ensureFreshAccessToken(): Promise<boolean> {
    const access = token.value?.trim()
    if (!access) return false
    if (!isJwtExpired(access)) return true
    const rt = refreshToken.value?.trim()
    if (!rt) {
      logout()
      window.dispatchEvent(new CustomEvent('myoj:session-expired'))
      return false
    }
    const ok = await refreshSession()
    if (!ok) {
      logout()
      window.dispatchEvent(new CustomEvent('myoj:session-expired'))
      return false
    }
    return true
  }

  hydrate()

  const isLoggedIn = computed(() => !!token.value)
  const isAdmin = computed(() => isAdminRole(userRole.value))
  const isAccountDisabled = computed(() => accountStatus.value === 0)
  const isCommentBanned = computed(() => {
    const end = parseBanUntilMs(commentBanUntil.value)
    if (end != null && end > Date.now()) return true
    if (banType.value !== 'profanity') return false
    const legacy = parseBanUntilMs(banUntil.value)
    return legacy != null && legacy > Date.now()
  })
  const isAiAssistBanned = computed(() => {
    const end = parseBanUntilMs(aiBanUntil.value)
    if (end != null && end > Date.now()) return true
    if (banType.value !== 'profanity_ai') return false
    const legacy = parseBanUntilMs(banUntil.value)
    return legacy != null && legacy > Date.now()
  })
  /** @deprecated 使用 isCommentBanned；保留别名避免遗漏引用 */
  const isProfanityBan = computed(() => isCommentBanned.value)
  const isAccountActive = computed(
    () => !isLoggedIn.value || accountStatus.value === null || accountStatus.value === 1,
  )

  const displayName = computed(() => {
    const u = username.value?.trim()
    if (u) return u
    const id = userId.value
    if (id != null) return `用户 ${id}`
    return '已登录'
  })

  function logout() {
    clearSession()
  }

  function setAvatar(nextAvatar: string | null | undefined) {
    avatar.value = nextAvatar?.trim() || null
    persistAuthPayload()
  }

  function setUsername(nextUsername: string | null | undefined) {
    username.value = nextUsername?.trim() || null
    persistAuthPayload()
  }

  async function refreshProfileFromServer(): Promise<void> {
    if (!token.value || !userId.value?.trim()) return
    const bearer = token.value.trim()
    if (!bearer) return
    try {
      const base = OpenAPI.BASE.replace(/\/+$/, '')
      const { data } = await axios.get<{
        code?: number
        data?: Record<string, unknown>
      }>(`${base}/user/get/${userId.value}`, {
        withCredentials: OpenAPI.WITH_CREDENTIALS,
        headers: { Authorization: `Bearer ${bearer}` },
      })
      if (!isResultSuccess(data?.code) || !data?.data) return
      const me = data.data
      if (typeof me.status === 'number') {
        setAccountStatus(me.status)
      }
      const rawAvatar = me.avatar
      const avatarStr =
        rawAvatar == null || (typeof rawAvatar === 'string' && rawAvatar.trim() === '')
          ? null
          : String(rawAvatar).trim()
      setAvatar(avatarStr)
      const nick = me.nickname != null ? String(me.nickname).trim() : ''
      const loginName = me.username != null ? String(me.username).trim() : ''
      if (nick || loginName) setUsername(nick || loginName)
    } catch {
      /* 静默 */
    }
  }

  async function syncSessionStatus(): Promise<void> {
    if (!token.value) return
    const { fetchSessionStatus } = await import('@/api/sessionStatus')
    const payload = await fetchSessionStatus()
    if (payload) {
      applySessionStatus(payload)
    }
  }

  const avatarUrl = computed(() => resolveMediaUrl(avatar.value))

  return {
    token,
    accessToken,
    refreshToken,
    userId,
    username,
    userRole,
    avatar,
    accountStatus,
    banType,
    banReason,
    banUntil,
    commentBanUntil,
    commentBanReason,
    aiBanUntil,
    aiBanReason,
    avatarUrl,
    isLoggedIn,
    isAdmin,
    isAccountDisabled,
    isCommentBanned,
    isAiAssistBanned,
    isProfanityBan,
    isAccountActive,
    displayName,
    applyLoginResult,
    refreshSession,
    ensureFreshAccessToken,
    logout,
    setAvatar,
    setUsername,
    setAccountStatus,
    applySessionStatus,
    refreshProfileFromServer,
    syncSessionStatus,
    hydrate,
  }
})
