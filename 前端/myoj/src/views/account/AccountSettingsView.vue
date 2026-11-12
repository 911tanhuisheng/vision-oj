<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import axios from 'axios'
import { Message, Modal } from '@arco-design/web-vue'
import { ApiError, OpenAPI, Service } from '@generated'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { isResultSuccess, isResultUnauthorized } from '@/api/result'
import { getBackendErrorMessage, isAxiosUnauthorizedResponse } from '@/api/httpError'
import { resolveMediaUrl } from '@/utils/resolveMediaUrl'
import chinaDivisionOptions from '@pansy/china-division'
import AdminUsersPanel from './AdminUsersPanel.vue'
import AiModelSettingsPanel from './AiModelSettingsPanel.vue'

type ProfileForm = {
  username: string
  phone: string
  gender: 'unknown' | 'male' | 'female'
  birthday: string
  location: string
  profile: string
  avatar: string
}

type ApiResult<T> = {
  code?: number
  message?: string
  data?: T
}

const auth = useAuthStore()
const router = useRouter()
const route = useRoute()
const saving = ref(false)
const uploading = ref(false)
const profileLoadErrorNotified = ref(false)
const profileLoading = ref(false)
const profileReady = ref(false)
const passwordModalVisible = ref(false)
const passwordSaving = ref(false)
const emailModalVisible = ref(false)
const emailSending = ref(false)
const emailSaving = ref(false)
const boundEmail = ref('')
const emailForm = ref({ email: '', password: '', code: '' })
const emailCountdown = ref(0)
let emailTimer: ReturnType<typeof setInterval> | null = null
const closeAccountModalVisible = ref(false)
const closeAccountSaving = ref(false)
const closeAccountPassword = ref('')
const passwordForm = ref({
  oldPassword: '',
  newPassword: '',
  confirmPassword: '',
})
const form = ref<ProfileForm>({
  username: auth.username || '',
  phone: '',
  gender: 'unknown',
  birthday: '',
  location: '',
  profile: '',
  avatar: auth.avatar || '',
})

type TabKey = 'base' | 'security' | 'ai' | 'users'

/** 刷新页面后仍停留在上次选中的标签（含「用户管理」） */
const ACCOUNT_SETTINGS_TAB_KEY = 'myoj.account.settings.tab'

function isTabKey(v: string | null): v is TabKey {
  return v === 'base' || v === 'security' || v === 'ai' || v === 'users'
}

function readInitialAccountTab(): TabKey {
  try {
    const raw = sessionStorage.getItem(ACCOUNT_SETTINGS_TAB_KEY)
    if (!raw || !isTabKey(raw)) return 'base'
    if (raw === 'users' && !auth.isAdmin) return 'base'
    return raw
  } catch {
    return 'base'
  }
}

function persistAccountTab(tab: TabKey) {
  try {
    sessionStorage.setItem(ACCOUNT_SETTINGS_TAB_KEY, tab)
  } catch {
    /* ignore */
  }
}

const tabs = computed(() => {
  const list: { key: TabKey; label: string }[] = [
    { key: 'base', label: '基本信息' },
    { key: 'security', label: '账户安全' },
    { key: 'ai', label: 'AI 模型' },
  ]
  if (auth.isAdmin) {
    list.push({ key: 'users', label: '用户管理' })
  }
  return list
})

const activeTab = ref<TabKey>(route.query.tab === 'ai' ? 'ai' : readInitialAccountTab())
watch(() => route.query.tab, (tab) => { if (tab === 'ai') activeTab.value = 'ai' })

watch(
  () => auth.isAdmin,
  (adm) => {
    if (!adm && activeTab.value === 'users') {
      activeTab.value = 'base'
      persistAccountTab('base')
    }
  },
)
const locationPath = ref<string[]>([])
const locationOptions = chinaDivisionOptions as Array<{ label: string; value: string; children?: unknown[] }>
const countryText = ref('')

const avatarPreview = computed(() => resolveMediaUrl(form.value.avatar))
// 个人网站：后端为数组（可新增/删除）
const websiteList = ref<string[]>([''])
const websiteErrors = ref<string[]>([''])

// 主题：避免 scoped 下 :global 选择器失效，直接用 class 控制
const themeMode = ref<'light' | 'dark'>((document.documentElement.getAttribute('data-theme') as 'light' | 'dark') || 'light')
const isLight = computed(() => themeMode.value === 'light')
/** 与页面明暗主题一致的遮罩 */
const passwordMaskStyle = computed(() =>
  isLight.value
    ? { background: 'rgba(15, 23, 42, 0.42)' }
    : { background: 'rgba(2, 6, 23, 0.62)' },
)
let themeObserver: MutationObserver | null = null

/**
 * 展示用唯一号（不直接暴露数据库主键）。
 * 规则：取登录返回的 userId（字符串）做一个短码显示，保证稳定可复现。
 */
const publicUid = computed(() => {
  const raw = (auth.userId || '').trim()
  if (!raw) return '-'
  // 取末尾 8 位作为短码（雪花 ID 末尾变化足够区分；同时不暴露完整主键）
  const tail = raw.slice(-8)
  return `UID-${tail}`
})

function normalizeWebsiteItem(raw: unknown): string {
  let s = String(raw ?? '').trim()
  if (!s) return ''
  // 去掉首尾多余引号（"..." / '...'），兼容后端历史脏数据
  while ((s.startsWith('"') && s.endsWith('"')) || (s.startsWith("'") && s.endsWith("'"))) {
    if (s.length <= 1) break
    s = s.slice(1, -1).trim()
  }
  // 去掉转义引号
  s = s.replace(/\\"/g, '"').trim()
  while ((s.startsWith('"') && s.endsWith('"')) || (s.startsWith("'") && s.endsWith("'"))) {
    if (s.length <= 1) break
    s = s.slice(1, -1).trim()
  }
  return s
}

function normalizeWebsiteList(list: unknown[]): string[] {
  return list.map((s) => normalizeWebsiteItem(s)).filter(Boolean)
}

function normalizeWebsiteInputAt(index: number) {
  websiteList.value[index] = normalizeWebsiteItem(websiteList.value[index])
  websiteErrors.value[index] = ''
}

function applyWebsitePayload(raw: unknown) {
  if (Array.isArray(raw)) {
    const arr = normalizeWebsiteList(raw)
    websiteList.value = arr.length ? arr : ['']
    websiteErrors.value = websiteList.value.map(() => '')
    return
  }
  const t = String(raw ?? '').trim()
  if (!t) {
    websiteList.value = ['']
    websiteErrors.value = ['']
    return
  }
  // 优先按 JSON 数组解析
  if (t.startsWith('[') && t.endsWith(']')) {
    try {
      const parsed = JSON.parse(t) as unknown
      if (Array.isArray(parsed)) {
        const arr = normalizeWebsiteList(parsed)
        websiteList.value = arr.length ? arr : ['']
        websiteErrors.value = websiteList.value.map(() => '')
        return
      }
    } catch {
      // fallback to csv
    }
  }
  // 兼容老字符串格式：逗号分隔
  const arr = normalizeWebsiteList(t.split(','))
  websiteList.value = arr.length ? arr : ['']
  websiteErrors.value = websiteList.value.map(() => '')
}

function onLocationChange(
  values: string[] | string,
  selectedOptions?: Array<{ label?: string; value?: string }>,
) {
  const arr = Array.isArray(values) ? values : values ? [values] : []
  locationPath.value = arr
  const labels = (selectedOptions || [])
    .map((opt) => String(opt?.label ?? '').trim())
    .filter(Boolean)
  form.value.location = labels.join(' / ')
}

type DivisionNode = { label: string; value: string; children?: DivisionNode[] }

function labelsFromCodes(codes: string[]): string[] {
  const out: string[] = []
  let current = locationOptions as DivisionNode[]
  for (const code of codes) {
    const hit = current.find((n) => String(n.value) === String(code))
    if (!hit) break
    out.push(String(hit.label))
    current = hit.children || []
  }
  return out
}

/** 级联只返回叶子 code、或路径与树不完全匹配时，从整棵树反查「根 → 该节点」的中文路径 */
function findLabelsPathByLeafCode(leafCode: string): string[] {
  const target = String(leafCode).trim()
  if (!target) return []

  function dfs(nodes: DivisionNode[], acc: string[]): string[] | null {
    for (const n of nodes) {
      const next = [...acc, String(n.label)]
      if (String(n.value) === target) return next
      const children = n.children || []
      const hit = dfs(children, next)
      if (hit) return hit
    }
    return null
  }

  const path = dfs(locationOptions as DivisionNode[], [])
  return path || []
}

/** 优先按层级路径解析；失败则按最后一个 code 在树中反查完整中文路径 */
function resolveLocationLabels(codes: string[]): string[] {
  const trimmed = codes.map((c) => String(c).trim()).filter(Boolean)
  if (!trimmed.length) return []

  const byWalk = labelsFromCodes(trimmed)
  if (byWalk.length === trimmed.length) return byWalk

  const leaf = trimmed[trimmed.length - 1] || ''
  if (!leaf) return byWalk
  const byTree = findLabelsPathByLeafCode(leaf)
  if (byTree.length) return byTree

  return byWalk
}

/** 根据中文地区路径反查级联编码（省/市/区） */
function findCodesByLabelsPath(labels: string[]): string[] {
  const cleaned = labels.map((x) => String(x).trim()).filter(Boolean)
  if (!cleaned.length) return []
  const codes: string[] = []
  let current = locationOptions as DivisionNode[]
  for (const label of cleaned) {
    const hit = current.find((n) => String(n.label).trim() === label)
    if (!hit) break
    codes.push(String(hit.value))
    current = hit.children || []
  }
  return codes
}

function addWebsite() {
  websiteList.value.push('')
  websiteErrors.value.push('')
}

function removeWebsiteAt(i: number) {
  if (websiteList.value.length <= 1) {
    websiteList.value[0] = ''
    websiteErrors.value[0] = ''
    return
  }
  websiteList.value.splice(i, 1)
  websiteErrors.value.splice(i, 1)
}

function validateWebsite(urlText: string): string {
  const text = normalizeWebsiteItem(urlText)
  if (!text) return ''
  let parsed: URL
  try {
    parsed = new URL(text)
  } catch {
    return '请输入完整网址，例如 https://example.com'
  }
  if (!['http:', 'https:'].includes(parsed.protocol)) {
    return '仅支持 http 或 https 开头的网址'
  }
  if (!parsed.hostname) {
    return '网址缺少域名，请检查后重试'
  }
  return ''
}

function validateWebsiteList(websites: string[]): { ok: boolean; firstInvalidIndex: number; message: string } {
  websiteErrors.value = websites.map((item) => validateWebsite(item))
  const firstInvalidIndex = websiteErrors.value.findIndex(Boolean)
  if (firstInvalidIndex >= 0) {
    return {
      ok: false,
      firstInvalidIndex,
      message: websiteErrors.value[firstInvalidIndex] || '个人网站格式不正确',
    }
  }
  return { ok: true, firstInvalidIndex: -1, message: '' }
}

/** 与后端 Location 一致：单对象，province/city/district 为地区文字 */
function parseLocationTextParts(locationText: string): string[] {
  return locationText
    .split('/')
    .map((s) => s.trim())
    .filter(Boolean)
}

function buildLocationCodesPayload(
  codes: string[],
  locationText: string,
): { province: string; city: string; district: string } | undefined {
  const labels = codes.length ? resolveLocationLabels(codes) : []
  const parts = labels.length ? labels : parseLocationTextParts(locationText)
  // 只允许地区中文名，避免把数字编码（如 130304）传到后端
  const normalized = parts
    .slice(0, 3)
    .map((x) => String(x).trim())
    .map((x) => (/^\d+$/.test(x) ? '' : x))
  while (normalized.length < 3) normalized.push('')
  const [province = '', city = '', district = ''] = normalized
  if (!province && !city && !district) return undefined
  return { province, city, district }
}

/** 解析为级联 value 编码数组，供 a-cascader 使用；纯文字时无法反查编码则返回 [] */
function parseLocationCodesFromResponse(raw: unknown): string[] {
  if (raw == null) return []
  // 新后端：单个 Location 对象
  if (typeof raw === 'object' && !Array.isArray(raw)) {
    const obj = raw as Record<string, unknown>
    const province = String(obj.province ?? '').trim()
    const city = String(obj.city ?? '').trim()
    const district = String(obj.district ?? '').trim()
    const maybeCodes = [province, city, district].filter(Boolean)
    if (maybeCodes.length && maybeCodes.every((x) => /^\d+$/.test(x))) {
      return maybeCodes
    }
    return findCodesByLabelsPath(maybeCodes)
  }
  if (!Array.isArray(raw) || raw.length === 0) return []
  const first = raw[0]
  // 兼容旧格式：["省code", "市code", "区code"]
  if (typeof first === 'string' || typeof first === 'number') {
    return raw.map((x) => String(x).trim()).filter(Boolean)
  }
  // 兼容历史：[{ province, city, district }]
  if (first && typeof first === 'object') {
    const obj = first as Record<string, unknown>
    const province = String(obj.province ?? '').trim()
    const city = String(obj.city ?? '').trim()
    const district = String(obj.district ?? '').trim()
    const maybeCodes = [province, city, district].filter(Boolean)
    if (maybeCodes.length && maybeCodes.every((x) => /^\d+$/.test(x))) {
      return maybeCodes
    }
    return findCodesByLabelsPath(maybeCodes)
  }
  return []
}

const userRoleText = computed(() => {
  const r = auth.userRole?.trim()
  if (!r) return 'user'
  if (/^(admin|管理员)$/i.test(r)) return 'admin'
  if (/^(user|普通用户)$/i.test(r)) return 'user'
  return r
})

const panelSubText = computed(() => {
  if (activeTab.value === 'base') return '更新昵称、头像与公开资料信息。'
  if (activeTab.value === 'security') return '管理登录状态与安全设置。'
  if (activeTab.value === 'ai') return '连接自己的大模型，管理 API Key 和模型名称。'
  return '分页查看全站用户账号状态、签到与积分、最近登录 IP 与异常标记（仅管理员）。'
})

watch(
  activeTab,
  (k) => {
    persistAccountTab(k)
  },
  { immediate: true },
)

async function getAuthHeaders() {
  const tokenResolver = OpenAPI.TOKEN
  const token = typeof tokenResolver === 'function' ? await tokenResolver({} as never) : tokenResolver
  return token ? { Authorization: `Bearer ${token}` } : undefined
}

/** 后端文案里像「未登录 / token 失效」的情况，统一引导重新登录 */
function looksLikeSessionMessage(msg: string): boolean {
  return /(token|jwt|登录|令牌|鉴权|未授权).*(过期|失效|无效)|请先登录|未授权|已过期|无效或已过期/i.test(msg)
}

function sessionExpiredAndGoLogin() {
  auth.logout()
  Message.error('登录已过期，请重新登录')
  void router.push({ path: '/', query: { openAuth: '1' } })
}

async function ensureBearerToken(): Promise<boolean> {
  const h = await getAuthHeaders()
  if (!h?.Authorization) {
    Message.warning('请先登录后再操作')
    void router.push({ path: '/', query: { openAuth: '1' } })
    return false
  }
  return true
}

async function onUploadAvatar(ev: Event) {
  const input = ev.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file) return
  if (!file.type.startsWith('image/')) {
    Message.warning('请上传图片文件')
    return
  }
  if (file.size > 5 * 1024 * 1024) {
    Message.warning('图片不能超过 5MB')
    return
  }
  if (!(await ensureBearerToken())) return
  uploading.value = true
  try {
    const base = OpenAPI.BASE.replace(/\/+$/, '')
    const fd = new FormData()
    fd.append('image', file)
    // 后端 data 可能是字符串 URL，也可能是 { avatar } / { url } 结构
    const { data } = await axios.post<ApiResult<string | { avatar?: string; url?: string }>>(`${base}/file/upload`, fd, {
      withCredentials: OpenAPI.WITH_CREDENTIALS,
      headers: await getAuthHeaders(),
    })
    if (isResultUnauthorized(data?.code)) {
      sessionExpiredAndGoLogin()
      return
    }
    if (!isResultSuccess(data?.code) || !data?.data) {
      const msg = data?.message?.trim() || ''
      if (msg && looksLikeSessionMessage(msg)) {
        sessionExpiredAndGoLogin()
        return
      }
      throw new Error(msg || '上传失败')
    }
    const raw = data.data
    const avatarUrl =
      typeof raw === 'string'
        ? raw
        : String((raw as { avatar?: string; url?: string }).avatar || (raw as { avatar?: string; url?: string }).url || '')
    if (!avatarUrl) throw new Error('后端未返回头像地址')
    form.value.avatar = avatarUrl
    Message.success('头像上传成功，请点击“保存更改”使其生效')
  } catch (e) {
    if (isAxiosUnauthorizedResponse(e)) {
      sessionExpiredAndGoLogin()
      return
    }
    Message.error(getBackendErrorMessage(e, '头像上传失败'))
  } finally {
    uploading.value = false
    input.value = ''
  }
}

async function onSave() {
  if (!profileReady.value) {
    Message.warning('账户资料尚未加载成功，请重试后再保存')
    return
  }
  if (!(await ensureBearerToken())) return
  const check = validateWebsiteList(websiteList.value)
  if (!check.ok) {
    Message.warning(`第 ${check.firstInvalidIndex + 1} 个个人网站不合法：${check.message}`)
    return
  }
  const phoneTrim = form.value.phone.trim()
  if (phoneTrim && !/^1[3-9]\d{9}$/.test(phoneTrim)) {
    Message.warning('手机号需为 11 位数字，且以 1 开头（3–9 第二位）')
    return
  }
  saving.value = true
  try {
    const websites = normalizeWebsiteList(websiteList.value)
    const locationPayload = buildLocationCodesPayload(locationPath.value, form.value.location)
    const result = await Service.updateMy({
      nickname: form.value.username.trim(),
      phone: phoneTrim,
      avatar: form.value.avatar || '',
      introduction: form.value.profile.trim(),
      locationCodes: locationPayload || { province: '', city: '', district: '' },
      website: websites,
      sex: form.value.gender === 'female' ? 2 : form.value.gender === 'male' ? 1 : 0,
      country: form.value.location.trim(),
      birthday: form.value.birthday || '',
    })
    if (isResultUnauthorized(result.code)) {
      sessionExpiredAndGoLogin()
      return
    }
    if (!isResultSuccess(result.code)) {
      const msg = result.message?.trim() || ''
      if (msg && looksLikeSessionMessage(msg)) {
        sessionExpiredAndGoLogin()
        return
      }
      throw new Error(msg || '保存失败')
    }
    auth.setAvatar(form.value.avatar)
    await fetchProfileByLoginId()
    Message.success('保存成功')
  } catch (e) {
    if (e instanceof ApiError) {
      const b = e.body as { code?: number; message?: string } | undefined
      const msg = b?.message?.trim() || ''
      if (msg && looksLikeSessionMessage(msg)) {
        sessionExpiredAndGoLogin()
        return
      }
      if (b?.code === 401 || b?.code === 403 || e.status === 401 || e.status === 403) {
        sessionExpiredAndGoLogin()
        return
      }
      Message.error(msg || e.message || '保存失败')
      return
    }
    if (isAxiosUnauthorizedResponse(e)) {
      sessionExpiredAndGoLogin()
      return
    }
    Message.error(getBackendErrorMessage(e, '保存失败'))
  } finally {
    saving.value = false
  }
}

function openPasswordModal() {
  passwordForm.value = { oldPassword: '', newPassword: '', confirmPassword: '' }
  passwordModalVisible.value = true
}

function stopEmailTimer() {
  if (emailTimer) clearInterval(emailTimer)
  emailTimer = null
  emailCountdown.value = 0
}

function openEmailModal() {
  emailForm.value = { email: '', password: '', code: '' }
  emailModalVisible.value = true
}

async function onSendEmailCode() {
  const email = emailForm.value.email.trim()
  if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)) return void Message.warning('请输入正确的新邮箱')
  if (!emailForm.value.password) return void Message.warning('请输入当前登录密码')
  if (emailCountdown.value > 0 || emailSending.value) return
  if (!(await ensureBearerToken())) return
  emailSending.value = true
  try {
    const { data } = await axios.post<ApiResult<null>>(
      `${OpenAPI.BASE.replace(/\/+$/, '')}/user/account/email/code`,
      { email, password: emailForm.value.password },
      { headers: await getAuthHeaders() },
    )
    if (!isResultSuccess(data.code)) throw new Error(data.message || '发送失败')
    Message.success(data.message || '验证码已发送')
    emailCountdown.value = 60
    emailTimer = setInterval(() => {
      emailCountdown.value--
      if (emailCountdown.value <= 0) stopEmailTimer()
    }, 1000)
  } catch (e) {
    Message.error(getBackendErrorMessage(e, '发送失败，请稍后再试'))
  } finally {
    emailSending.value = false
  }
}

async function onBeforeBindEmail(): Promise<boolean> {
  const { email, password, code } = emailForm.value
  if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email.trim()) || !password || !/^\d{6}$/.test(code.trim())) {
    Message.warning('请填写新邮箱、当前密码和6位邮件验证码')
    return false
  }
  if (!(await ensureBearerToken())) return false
  emailSaving.value = true
  try {
    const { data } = await axios.post<ApiResult<null>>(
      `${OpenAPI.BASE.replace(/\/+$/, '')}/user/account/email/bind`,
      { email: email.trim(), password, code: code.trim() },
      { headers: await getAuthHeaders() },
    )
    if (!isResultSuccess(data.code)) throw new Error(data.message || '绑定失败')
    boundEmail.value = email.trim()
    emailForm.value = { email: '', password: '', code: '' }
    stopEmailTimer()
    Message.success(data.message || '邮箱绑定成功')
    return true
  } catch (e) {
    Message.error(getBackendErrorMessage(e, '邮箱绑定失败'))
    return false
  } finally {
    emailSaving.value = false
  }
}

function openCloseAccountModal() {
  if (auth.isAdmin) {
    Message.warning('管理员账号无法自助注销，请先移交权限')
    return
  }
  closeAccountPassword.value = ''
  closeAccountModalVisible.value = true
}

async function onBeforeCloseAccount(): Promise<boolean> {
  const pwd = closeAccountPassword.value.trim()
  if (!pwd) {
    Message.warning('请输入登录密码以确认注销')
    return false
  }
  closeAccountSaving.value = true
  try {
    if (!(await ensureBearerToken())) return false
    const result = await Service.closeMyAccount({ password: pwd })
    if (isResultUnauthorized(result.code)) {
      sessionExpiredAndGoLogin()
      return false
    }
    if (!isResultSuccess(result.code)) {
      const msg = result.message?.trim() || ''
      if (msg && looksLikeSessionMessage(msg)) {
        sessionExpiredAndGoLogin()
        return false
      }
      Message.error(msg || '注销失败')
      return false
    }
    Message.success(result.message?.trim() || '账号已注销')
    closeAccountPassword.value = ''
    auth.logout()
    void router.push('/')
    return true
  } catch (e) {
    if (e instanceof ApiError) {
      const b = e.body as { code?: number; message?: string } | undefined
      const msg = b?.message?.trim() || ''
      if (msg && looksLikeSessionMessage(msg)) {
        sessionExpiredAndGoLogin()
        return false
      }
      if (b?.code === 401 || b?.code === 403 || e.status === 401 || e.status === 403) {
        sessionExpiredAndGoLogin()
        return false
      }
      Message.error(msg || e.message || '注销失败')
      return false
    }
    if (isAxiosUnauthorizedResponse(e)) {
      sessionExpiredAndGoLogin()
      return false
    }
    Message.error(getBackendErrorMessage(e, '注销失败'))
    return false
  } finally {
    closeAccountSaving.value = false
  }
}

/** 使用 @before-ok：校验失败返回 false，弹窗不关闭，避免「先关窗再提示」的糟糕体验 */
async function onBeforeSavePassword(): Promise<boolean> {
  const oldPwd = passwordForm.value.oldPassword.trim()
  const newPwd = passwordForm.value.newPassword.trim()
  const confirmPwd = passwordForm.value.confirmPassword.trim()
  if (!oldPwd || !newPwd || !confirmPwd) {
    Message.warning('请完整填写旧密码和新密码')
    return false
  }
  if (newPwd.length < 6) {
    Message.warning('新密码至少 6 位')
    return false
  }
  if (!/^(?=.*[A-Za-z])(?=.*\d)[A-Za-z\d]{6,20}$/.test(newPwd)) {
    Message.warning('新密码须 6～20 位，且同时包含字母与数字')
    return false
  }
  if (newPwd === oldPwd) {
    Message.warning('新密码不能与旧密码相同')
    return false
  }
  if (newPwd !== confirmPwd) {
    Message.warning('两次输入的新密码不一致')
    return false
  }
  passwordSaving.value = true
  try {
    if (!(await ensureBearerToken())) return false
    const result = await Service.updatePassword({
      oldPassword: oldPwd,
      newPassword: newPwd,
      confirmNewPassword: confirmPwd,
    })
    if (isResultUnauthorized(result.code)) {
      sessionExpiredAndGoLogin()
      return false
    }
    if (!isResultSuccess(result.code)) {
      const msg = result.message?.trim() || ''
      if (msg && looksLikeSessionMessage(msg)) {
        sessionExpiredAndGoLogin()
        return false
      }
      Message.error(msg || '修改密码失败')
      return false
    }
    Message.success('密码修改成功，请使用新密码重新登录')
    passwordForm.value = { oldPassword: '', newPassword: '', confirmPassword: '' }
    auth.logout()
    void router.push({ path: '/', query: { openAuth: '1' } })
    return true
  } catch (e) {
    if (e instanceof ApiError) {
      const b = e.body as { code?: number; message?: string } | undefined
      const msg = b?.message?.trim() || ''
      if (msg && looksLikeSessionMessage(msg)) {
        sessionExpiredAndGoLogin()
        return false
      }
      if (b?.code === 401 || b?.code === 403 || e.status === 401 || e.status === 403) {
        sessionExpiredAndGoLogin()
        return false
      }
      Message.error(msg || e.message || '修改密码失败')
      return false
    }
    if (isAxiosUnauthorizedResponse(e)) {
      sessionExpiredAndGoLogin()
      return false
    }
    Message.error(getBackendErrorMessage(e, '网络异常，请稍后再试'))
    return false
  } finally {
    passwordSaving.value = false
  }
}

function toDateInputValue(v: unknown): string {
  if (v == null) return ''
  const s = String(v).trim()
  if (!s) return ''
  // 已是 yyyy-mm-dd 直接用
  if (/^\d{4}-\d{2}-\d{2}$/.test(s)) return s
  const d = new Date(s)
  if (Number.isNaN(d.getTime())) return ''
  // 必须用本地日期，不能用 toISOString()（UTC），否则东八区会「少一天」
  const y = d.getFullYear()
  const m = String(d.getMonth() + 1).padStart(2, '0')
  const day = String(d.getDate()).padStart(2, '0')
  return `${y}-${m}-${day}`
}

async function fetchProfileByLoginId() {
  if (!auth.userId?.trim()) return
  if (!(await ensureBearerToken())) return
  profileLoading.value = true
  try {
    const base = OpenAPI.BASE.replace(/\/+$/, '')
    // 路径必须使用字符串 userId：Service.get(number) 会破坏超过 JS 安全整数的雪花 id，导致 403 被误判成「过期」
    const { data } = await axios.get<ApiResult<Record<string, unknown>>>(`${base}/user/get/${auth.userId}`, {
      withCredentials: OpenAPI.WITH_CREDENTIALS,
      headers: await getAuthHeaders(),
    })
    if (data?.code === 401) {
      sessionExpiredAndGoLogin()
      return
    }
    if (data?.code === 403) {
      Message.error(data?.message?.trim() || '无权限')
      return
    }
    if (!isResultSuccess(data?.code) || !data?.data) {
      const msg = data?.message?.trim() || ''
      if (msg && looksLikeSessionMessage(msg)) {
        sessionExpiredAndGoLogin()
      } else {
        profileLoadErrorNotified.value = true
        Message.warning(msg || '资料加载失败，请重试')
      }
      return
    }
    const me = data.data
    const nickname = me.nickname != null ? String(me.nickname).trim() : ''
    const loginName = me.username != null ? String(me.username).trim() : ''
    form.value.username = nickname || loginName || auth.displayName
    auth.setUsername(nickname || loginName || auth.displayName)
    boundEmail.value = me.email != null ? String(me.email).trim() : ''
    const phoneRaw = me.phone != null ? String(me.phone).trim() : ''
    form.value.phone = phoneRaw
    // 头像：接口字段与侧栏表单一致；并写回 auth，顶栏 AppLayout 使用 auth.avatarUrl 才能同步
    const rawAvatar = me.avatar
    const avatarFromApi =
      rawAvatar == null || (typeof rawAvatar === 'string' && rawAvatar.trim() === '') ? '' : String(rawAvatar).trim()
    form.value.avatar = avatarFromApi
    auth.setAvatar(form.value.avatar || null)
    const intro = me.introduction != null ? String(me.introduction) : ''
    form.value.profile = intro

    const locationCodesRaw = (me as Record<string, unknown>).locationCodes
    const codes = parseLocationCodesFromResponse(locationCodesRaw)
    form.value.location = ''
    if (codes.length) {
      locationPath.value = codes
      const labels = resolveLocationLabels(codes)
      if (labels.length) {
        form.value.location = labels.join(' / ')
      }
    } else {
      locationPath.value = []
      form.value.location = ''
    }

    const country = me.country != null ? String(me.country).trim() : ''
    countryText.value = country
    if (country && !form.value.location) form.value.location = country

    const w = (me as Record<string, unknown>).website
    applyWebsitePayload(w)

    const b = toDateInputValue(me.birthday)
    form.value.birthday = b

    const sexRaw = me.sex != null ? String(me.sex).trim() : ''
    if (sexRaw === '1') form.value.gender = 'male'
    else if (sexRaw === '2') form.value.gender = 'female'
    else form.value.gender = 'unknown'
    profileReady.value = true
    profileLoadErrorNotified.value = false
  } catch (e) {
    if (!profileLoadErrorNotified.value) {
      profileLoadErrorNotified.value = true
      Message.warning(getBackendErrorMessage(e, '网络异常，请稍后再试'))
    }
  } finally {
    profileLoading.value = false
  }
}

onMounted(() => {
  // 进入页面时：仍然使用登录时写入的本地信息作为初始值
  form.value.username = auth.displayName
  form.value.avatar = auth.avatar || ''
  void fetchProfileByLoginId()
  // website 由后端回显填充；这里先给一个空行
  websiteList.value = ['']
  websiteErrors.value = ['']

  themeObserver = new MutationObserver(() => {
    const v = document.documentElement.getAttribute('data-theme')
    themeMode.value = (v === 'dark' ? 'dark' : 'light') as 'light' | 'dark'
  })
  themeObserver.observe(document.documentElement, { attributes: true, attributeFilter: ['data-theme'] })
})

onBeforeUnmount(() => {
  stopEmailTimer()
  themeObserver?.disconnect()
  themeObserver = null
})
</script>

<template>
  <div class="account-page" :class="{ 'is-light': isLight }">
    <div class="account-page-bg" aria-hidden="true" />
    <div class="account-page-inner" :class="{ 'account-page-inner--users': activeTab === 'users' }">
      <aside class="profile-side">
        <div class="profile-card">
          <div class="profile-avatar-wrap">
            <div class="profile-avatar-ring">
              <img v-if="avatarPreview" :src="avatarPreview" alt="头像" class="profile-avatar-img" />
              <div v-else class="profile-avatar-fallback">
                {{ (auth.displayName || 'U').slice(0, 1).toUpperCase() }}
              </div>
            </div>
            <label class="upload-btn" :class="{ disabled: uploading }">
              {{ uploading ? '上传中...' : '更换头像' }}
              <input type="file" accept="image/*" @change="onUploadAvatar" />
            </label>
          </div>
          <p class="profile-name">{{ form.username || auth.displayName }}</p>
          <p class="profile-role">{{ userRoleText }}</p>
          <p class="profile-id">UID: {{ publicUid }}</p>
        </div>

        <nav class="profile-nav" aria-label="账户设置导航">
          <button
            v-for="t in tabs"
            :key="t.key"
            type="button"
            class="profile-nav-item"
            :class="{ active: activeTab === t.key }"
            @click="activeTab = t.key"
          >
            {{ t.label }}
          </button>
        </nav>
      </aside>

      <section class="profile-main">
        <div class="panel">
          <header class="panel-header">
            <div class="panel-header-main">
              <div>
                <h2 class="panel-title">账户设置</h2>
                <p class="panel-sub">
                  {{ panelSubText }}
                </p>
              </div>
              <button
                v-if="activeTab === 'base'"
                type="button"
                class="save-btn save-btn--header"
                :disabled="!profileReady || saving || uploading"
                @click="onSave"
              >
                {{ saving ? '保存中...' : profileLoading ? '资料加载中...' : '保存更改' }}
              </button>
            </div>
            <p v-if="activeTab === 'base' && profileLoadErrorNotified && !profileLoading" class="profile-load-error">
              资料加载失败，暂时无法保存。
              <button type="button" @click="fetchProfileByLoginId">重新加载</button>
            </p>
          </header>

          <template v-if="activeTab === 'base'">
          <div class="section">
            <h3 class="section-title">基本信息</h3>
            <div class="panel-grid two">
              <label class="field">
                <span>昵称</span>
                <input v-model="form.username" type="text" maxlength="32" placeholder="给自己起个好记的名字" />
              </label>
              <label class="field">
                <span>手机号</span>
                <input
                  v-model="form.phone"
                  type="tel"
                  inputmode="numeric"
                  maxlength="11"
                  autocomplete="tel"
                  placeholder="11 位中国大陆手机号，可留空"
                />
              </label>
              <label class="field field--full">
                <span>所在地</span>
                <a-cascader
                  v-model="locationPath"
                  :options="locationOptions"
                  path-mode
                  allow-search
                  :check-strictly="false"
                  placeholder="选择省 / 市 / 区"
                  class="location-cascader"
                  @change="onLocationChange"
                />
                <div v-if="countryText" class="location-meta">
                  <small class="field-tip">国家/地区</small>
                  <span class="location-badge">{{ countryText }}</span>
                </div>
              </label>
            </div>

            <div class="panel-grid two">
              <label class="field">
                <span>生日</span>
                <input v-model="form.birthday" type="date" />
              </label>
              <div class="field">
                <span>性别</span>
                <div class="radio-row">
                  <button type="button" class="radio-btn" :class="{ active: form.gender === 'unknown' }" @click="form.gender = 'unknown'">未设置</button>
                  <button
                    type="button"
                    class="radio-btn"
                    :class="{ active: form.gender === 'male' }"
                    @click="form.gender = 'male'"
                  >
                    男
                  </button>
                  <button
                    type="button"
                    class="radio-btn"
                    :class="{ active: form.gender === 'female' }"
                    @click="form.gender = 'female'"
                  >
                    女
                  </button>
                </div>
              </div>
            </div>

            <label class="field">
              <span>个人介绍</span>
              <textarea v-model="form.profile" maxlength="200" placeholder="一句话介绍你自己（最多 200 字）" />
            </label>
          </div>

          <div class="section">
            <h3 class="section-title">链接</h3>
            <label class="field">
              <span>个人网站</span>
              <div class="website-list">
                <div v-for="(w, idx) in websiteList" :key="idx" class="website-row">
                  <input
                    v-model="websiteList[idx]"
                    type="url"
                    placeholder="https://example.com"
                    class="website-input"
                    :class="{ 'is-invalid': !!websiteErrors[idx] }"
                    @blur="normalizeWebsiteInputAt(idx)"
                  />
                  <small v-if="websiteErrors[idx]" class="website-error">{{ websiteErrors[idx] }}</small>
                  <button
                    type="button"
                    class="website-del-btn"
                    :disabled="websiteList.length === 1"
                    aria-label="删除网站"
                    title="删除"
                    @click="removeWebsiteAt(idx)"
                  >
                    删除
                  </button>
                </div>
                <button type="button" class="website-add-btn" @click="addWebsite">+ 添加一个链接</button>
              </div>
            </label>
          </div>

          <div class="form-actions">
            <div class="form-actions-hint">修改会在保存后生效。</div>
            <div class="form-actions-right">
              <span class="form-actions-status">{{ saving ? '正在保存…' : '' }}</span>
            </div>
          </div>
          </template>

          <template v-else-if="activeTab === 'security'">
            <div class="section">
              <h3 class="section-title">账户安全</h3>
              <div class="security-cards">
                <div class="security-card security-card--danger-zone">
                  <div class="security-card-title">账号注销</div>
                  <div class="security-card-desc">
                    当你确定不再使用本账号时，可执行注销：账号将不可用且<strong class="security-desc-strong">不可恢复</strong>，相关唯一信息会释放，之后可用相同邮箱或手机号重新注册。操作前请自行备份需要保留的数据。
                  </div>
                  <div class="security-destructive-action">
                    <button
                      type="button"
                      class="security-outline-danger-btn security-outline-danger-btn--main"
                      :disabled="auth.isAdmin"
                      :title="auth.isAdmin ? '管理员账号无法自助注销' : undefined"
                      @click="openCloseAccountModal"
                    >
                      <span class="security-danger-btn-label">注销当前账号</span>
                      <span class="security-danger-btn-hint">需验证登录密码</span>
                    </button>
                  </div>
                  <p v-if="auth.isAdmin" class="security-muted security-muted--below-actions">
                    当前为管理员账号，系统不允许在此自助注销；需由其他管理员处理或先降级为普通用户。
                  </p>
                </div>
                <div class="security-card security-card--clickable" role="button" tabindex="0" @click="openPasswordModal" @keydown.enter="openPasswordModal" @keydown.space.prevent="openPasswordModal">
                  <div class="security-card-title">登录密码</div>
                  <div class="security-card-desc">验证旧密码后修改；成功后需用新密码重新登录。</div>
                  <div class="security-muted">点击进入修改密码</div>
                </div>
                <div class="security-card security-card--clickable" role="button" tabindex="0" @click="openEmailModal" @keydown.enter="openEmailModal" @keydown.space.prevent="openEmailModal">
                  <div class="security-card-title">绑定邮箱</div>
                  <div class="security-card-desc">{{ boundEmail ? `当前邮箱：${boundEmail}` : '尚未绑定邮箱，建议绑定后使用邮箱登录与找回密码。' }}</div>
                  <div class="security-muted">{{ boundEmail ? '点击更换邮箱' : '点击绑定邮箱' }} · 需验证当前密码和新邮箱</div>
                </div>
              </div>
            </div>
          </template>

          <AiModelSettingsPanel v-else-if="activeTab === 'ai'" />
          <AdminUsersPanel v-else-if="activeTab === 'users'" :is-light="isLight" />
        </div>
      </section>
    </div>
  </div>

  <a-modal
    v-model:visible="emailModalVisible"
    :title="boundEmail ? '更换绑定邮箱' : '绑定邮箱'"
    :modal-class="['password-modal-shell', isLight ? 'password-modal-shell--light' : 'password-modal-shell--dark']"
    :mask-closable="false"
    :mask-style="passwordMaskStyle"
    :ok-loading="emailSaving"
    ok-text="确认绑定"
    cancel-text="取消"
    :body-style="{ padding: '18px 20px 16px' }"
    @before-ok="onBeforeBindEmail"
  >
    <div class="password-modal" :class="{ 'password-modal--light': isLight }">
      <p class="password-modal-tip">验证码会发送至新邮箱，10 分钟内有效。绑定后可用邮箱登录和找回密码。</p>
      <a-input v-model="emailForm.email" placeholder="新邮箱（QQ 邮箱也可以）" autocomplete="email" />
      <a-input-password v-model="emailForm.password" placeholder="当前登录密码" autocomplete="current-password" />
      <div class="email-code-row">
        <a-input v-model="emailForm.code" :max-length="6" placeholder="6 位邮件验证码" inputmode="numeric" />
        <a-button :loading="emailSending" :disabled="emailCountdown > 0" @click="onSendEmailCode">{{ emailCountdown ? `${emailCountdown}s 后重发` : '获取验证码' }}</a-button>
      </div>
    </div>
  </a-modal>

  <a-modal
    v-model:visible="passwordModalVisible"
    title="修改密码"
    :modal-class="['password-modal-shell', isLight ? 'password-modal-shell--light' : 'password-modal-shell--dark']"
    :mask-closable="false"
    :mask-style="passwordMaskStyle"
    :ok-loading="passwordSaving"
    ok-text="确认修改"
    cancel-text="取消"
    :body-style="{ padding: '18px 20px 16px' }"
    @before-ok="onBeforeSavePassword"
  >
    <div class="password-modal" :class="{ 'password-modal--light': isLight }">
      <p class="password-modal-tip">为了账号安全，建议使用 6 位以上且包含字母数字的强密码。</p>
      <a-input-password
        v-model="passwordForm.oldPassword"
        placeholder="请输入旧密码"
        allow-clear
      />
      <a-input-password
        v-model="passwordForm.newPassword"
        placeholder="请输入新密码（至少 6 位）"
        allow-clear
      />
      <a-input-password
        v-model="passwordForm.confirmPassword"
        placeholder="请再次输入新密码"
        allow-clear
      />
    </div>
  </a-modal>

  <a-modal
    v-model:visible="closeAccountModalVisible"
    title="注销账号"
    :modal-class="['password-modal-shell', isLight ? 'password-modal-shell--light' : 'password-modal-shell--dark']"
    :mask-closable="false"
    :mask-style="passwordMaskStyle"
    :ok-loading="closeAccountSaving"
    ok-text="确认注销"
    cancel-text="取消"
    :ok-button-props="{ status: 'danger' }"
    :body-style="{ padding: '18px 20px 16px' }"
    @before-ok="onBeforeCloseAccount"
  >
    <div class="password-modal close-account-modal" :class="{ 'password-modal--light': isLight }">
      <p class="close-account-warn">
        注销后账号将不可用，资料会被标记删除且无法恢复；相同邮箱或手机号可再次注册（若未绑定则不受影响）。请再次输入<strong>当前登录密码</strong>确认。
      </p>
      <a-input-password
        v-model="closeAccountPassword"
        placeholder="请输入登录密码"
        allow-clear
      />
    </div>
  </a-modal>
</template>

<style scoped>
/* 深色极简：克制的高对比与清晰层级 */
.account-page {
  --acc-teal: #00c2a0;
  --acc-teal-2: #2dd4bf;
  --acc-surface: rgba(17, 24, 39, 0.74);
  --acc-border: rgba(148, 163, 184, 0.16);
  --acc-text: #e5e7eb;
  --acc-muted: rgba(226, 232, 240, 0.65);
  --acc-input: rgba(2, 6, 23, 0.55);
  position: relative;
  margin: 0 calc(50% - 50vw);
  width: 100vw;
  max-width: 100vw;
  min-height: calc(100vh - 56px);
  box-sizing: border-box;
  isolation: isolate;
}

.account-page.is-light {
  --acc-surface: rgba(255, 255, 255, 0.78);
  --acc-border: rgba(15, 23, 42, 0.10);
  --acc-text: #0f172a;
  --acc-muted: rgba(15, 23, 42, 0.62);
  --acc-input: rgba(255, 255, 255, 0.9);
}

.account-page-bg {
  position: absolute;
  inset: 0;
  z-index: 0;
  background: radial-gradient(1200px 800px at 15% 0%, rgba(0, 194, 160, 0.12), transparent 55%),
    radial-gradient(1000px 700px at 90% 100%, rgba(56, 189, 248, 0.08), transparent 55%),
    linear-gradient(180deg, #070a12 0%, #0a1020 45%, #070a12 100%);
}

.account-page.is-light .account-page-bg {
  background: radial-gradient(1000px 700px at 10% 0%, rgba(0, 194, 160, 0.18), transparent 60%),
    radial-gradient(900px 650px at 95% 100%, rgba(56, 189, 248, 0.12), transparent 60%),
    linear-gradient(180deg, #f8fafc 0%, #eef2f7 55%, #f8fafc 100%);
}

.account-page-bg::after {
  content: '';
  position: absolute;
  inset: 0;
  background: radial-gradient(800px 320px at 50% 6%, rgba(255, 255, 255, 0.06), transparent 60%);
  pointer-events: none;
}

.account-page.is-light .account-page-bg::after {
  background: radial-gradient(900px 360px at 50% 0%, rgba(15, 23, 42, 0.06), transparent 62%);
}

.account-page-inner {
  position: relative;
  z-index: 1;
  max-width: 1180px;
  margin: 0 auto;
  padding: 32px 20px 48px;
  display: grid;
  grid-template-columns: 296px minmax(0, 1fr);
  gap: 28px;
  color: var(--acc-text);
  animation: account-in 0.45s cubic-bezier(0.34, 1.15, 0.64, 1) both;
}

.account-page-inner--users {
  max-width: 1320px;
}

.account-page-inner--users .profile-main .panel {
  padding: 24px 26px 26px;
}

@keyframes account-in {
  from {
    opacity: 0;
    transform: translateY(10px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}

.profile-side {
  position: sticky;
  top: 76px;
  align-self: start;
}

.profile-card {
  border-radius: 18px;
  border: 1px solid var(--acc-border);
  background: var(--acc-surface);
  backdrop-filter: blur(16px);
  -webkit-backdrop-filter: blur(16px);
  padding: 22px 18px 18px;
  text-align: center;
  box-shadow:
    0 0 0 1px rgba(255, 255, 255, 0.04) inset,
    0 20px 46px rgba(0, 0, 0, 0.42);
}

.account-page.is-light .profile-card {
  box-shadow:
    0 0 0 1px rgba(255, 255, 255, 0.9) inset,
    0 16px 40px rgba(15, 23, 42, 0.08);
}

.profile-avatar-wrap {
  position: relative;
  width: 112px;
  margin: 0 auto;
}

.profile-avatar-ring {
  width: 112px;
  height: 112px;
  border-radius: 50%;
  padding: 3px;
  background: linear-gradient(145deg, var(--acc-teal) 0%, #00a896 55%, #0d9488 100%);
  box-shadow:
    0 0 0 1px rgba(0, 194, 160, 0.35),
    0 0 32px rgba(0, 194, 160, 0.28);
}

.profile-avatar-img,
.profile-avatar-fallback {
  width: 100%;
  height: 100%;
  border-radius: 50%;
  object-fit: cover;
  display: block;
}

.profile-avatar-fallback {
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(160deg, #134e4a 0%, #0f766e 100%);
  color: #fff;
  font-size: 36px;
  font-weight: 800;
  letter-spacing: -0.02em;
}

.upload-btn {
  position: absolute;
  left: 50%;
  bottom: -8px;
  transform: translateX(-50%);
  padding: 5px 12px;
  border-radius: 999px;
  border: 1px solid rgba(0, 194, 160, 0.28);
  background: rgba(2, 6, 23, 0.78);
  color: var(--acc-teal);
  font-size: 12px;
  font-weight: 600;
  cursor: pointer;
  box-shadow: 0 8px 20px rgba(0, 0, 0, 0.35);
  transition:
    border-color 0.2s ease,
    box-shadow 0.2s ease,
    transform 0.15s ease;
}

.upload-btn:hover:not(.disabled) {
  border-color: rgba(0, 194, 160, 0.55);
  box-shadow: 0 0 18px rgba(0, 194, 160, 0.25);
  transform: translateX(-50%) translateY(-1px);
}

.upload-btn.disabled {
  opacity: 0.55;
  cursor: not-allowed;
}

.upload-btn input {
  display: none;
}

.account-page.is-light .upload-btn {
  background: rgba(255, 255, 255, 0.92);
  border-color: rgba(0, 194, 160, 0.32);
  box-shadow: 0 10px 26px rgba(15, 23, 42, 0.10);
}

.profile-name {
  margin: 16px 0 0;
  font-size: 20px;
  font-weight: 800;
  letter-spacing: 0.02em;
  color: #f8fafc;
}

.account-page.is-light .profile-name {
  color: #0f172a;
}

.profile-role {
  margin: 4px 0 0;
  font-size: 12px;
  font-weight: 600;
  letter-spacing: 0.06em;
  text-transform: uppercase;
  color: var(--acc-teal);
  opacity: 0.95;
}

.profile-id {
  margin: 10px 0 0;
  font-size: 11px;
  color: var(--acc-muted);
  font-family: ui-monospace, monospace;
}

.profile-nav {
  margin-top: 14px;
  border-radius: 16px;
  border: 1px solid var(--acc-border);
  background: var(--acc-surface);
  backdrop-filter: blur(14px);
  -webkit-backdrop-filter: blur(14px);
  padding: 8px;
  display: flex;
  flex-direction: column;
  gap: 4px;
  box-shadow: 0 16px 36px rgba(0, 0, 0, 0.28);
}

.profile-nav-item {
  border: none;
  background: transparent;
  text-align: left;
  padding: 11px 12px 11px 14px;
  border-radius: 10px;
  color: #cbd5e1;
  font-size: 13px;
  cursor: pointer;
  transition:
    background 0.18s ease,
    color 0.18s ease;
}

.account-page.is-light .profile-nav-item {
  color: rgba(15, 23, 42, 0.72);
}

.profile-nav-item:hover {
  background: rgba(0, 194, 160, 0.08);
  color: #f8fafc;
}

.profile-nav-item.active {
  color: var(--acc-teal);
  background: rgba(0, 194, 160, 0.12);
  font-weight: 700;
  box-shadow: inset 3px 0 0 0 var(--acc-teal);
}

.profile-main .panel {
  border-radius: 18px;
  border: 1px solid var(--acc-border);
  background: var(--acc-surface);
  backdrop-filter: blur(16px);
  -webkit-backdrop-filter: blur(16px);
  padding: 22px 24px 22px;
  box-shadow:
    0 0 0 1px rgba(255, 255, 255, 0.04) inset,
    0 24px 56px rgba(0, 0, 0, 0.38);
}

.account-page.is-light .profile-main .panel {
  box-shadow:
    0 0 0 1px rgba(255, 255, 255, 0.95) inset,
    0 18px 46px rgba(15, 23, 42, 0.10);
}

.panel-header {
  margin-bottom: 20px;
  padding-bottom: 16px;
  border-bottom: 1px solid rgba(148, 163, 184, 0.12);
}

.panel-header-main {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
}

.panel-title {
  margin: 0;
  font-size: 22px;
  font-weight: 800;
  letter-spacing: 0.015em;
  color: #f8fafc;
}

.account-page.is-light .panel-title {
  color: #0f172a;
}

.account-page.is-light .panel-sub {
  color: rgba(15, 23, 42, 0.68);
}

.panel-sub {
  margin: 8px 0 0;
  font-size: 13px;
  color: var(--acc-muted);
  line-height: 1.5;
}

.section {
  padding: 14px 0 2px;
}

.section + .section {
  border-top: 1px solid rgba(148, 163, 184, 0.12);
  margin-top: 8px;
}

.section-title {
  margin: 0 0 14px;
  font-size: 13px;
  font-weight: 700;
  color: rgba(226, 232, 240, 0.85);
  letter-spacing: 0.02em;
  position: relative;
  padding-left: 10px;
}

.section-title::before {
  content: '';
  position: absolute;
  left: 0;
  top: 50%;
  transform: translateY(-50%);
  width: 4px;
  height: 14px;
  border-radius: 99px;
  background: linear-gradient(180deg, var(--acc-teal) 0%, var(--acc-teal-2) 100%);
  box-shadow: 0 0 12px rgba(0, 194, 160, 0.35);
}

.account-page.is-light .section-title {
  color: rgba(15, 23, 42, 0.86);
}

.account-page.is-light .section + .section {
  border-top-color: rgba(15, 23, 42, 0.10);
}

.panel-grid.two {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 16px;
}

.panel-grid.two .field.field--full {
  grid-column: 1 / -1;
}

.field {
  display: block;
  margin-bottom: 16px;
}

.field > span {
  display: block;
  font-size: 13px;
  font-weight: 650;
  color: rgba(226, 232, 240, 0.72);
  margin-bottom: 8px;
}

.account-page.is-light .field > span {
  color: rgba(15, 23, 42, 0.78);
}

.field-tip {
  display: inline-block;
  margin-top: 6px;
  font-size: 12px;
  color: var(--acc-muted);
}

.location-meta {
  margin-top: 8px;
  display: flex;
  align-items: center;
  gap: 8px;
}

.location-badge {
  display: inline-flex;
  align-items: center;
  max-width: 100%;
  padding: 3px 10px;
  border-radius: 999px;
  border: 1px solid rgba(0, 194, 160, 0.28);
  background: rgba(0, 194, 160, 0.1);
  color: rgba(153, 246, 228, 0.95);
  font-size: 12px;
  font-weight: 700;
  white-space: nowrap;
  text-overflow: ellipsis;
  overflow: hidden;
}

.field input,
.field textarea {
  width: 100%;
  border: 1px solid rgba(148, 163, 184, 0.18);
  border-radius: 12px;
  padding: 12px 14px;
  font-size: 14px;
  background: var(--acc-input);
  color: var(--acc-text);
  transition:
    border-color 0.2s ease,
    box-shadow 0.2s ease,
    transform 0.12s ease;
}

.account-page.is-light .field input,
.account-page.is-light .field textarea {
  border-color: rgba(15, 23, 42, 0.16);
  box-shadow: 0 1px 0 rgba(15, 23, 42, 0.04);
}

.account-page.is-light .field input::placeholder,
.account-page.is-light .field textarea::placeholder {
  color: rgba(15, 23, 42, 0.42);
}

.account-page.is-light .field input:focus,
.account-page.is-light .field textarea:focus {
  border-color: rgba(0, 194, 160, 0.75);
  box-shadow:
    0 1px 0 rgba(15, 23, 42, 0.04),
    0 0 0 3px rgba(0, 194, 160, 0.18);
}

.field textarea {
  min-height: 108px;
  resize: vertical;
}

.field input::placeholder,
.field textarea::placeholder {
  color: #64748b;
}

.field input:focus,
.field textarea:focus {
  outline: none;
  border-color: rgba(0, 194, 160, 0.6);
  box-shadow: 0 0 0 3px rgba(0, 194, 160, 0.16);
  transform: translateY(-1px);
}

/* 原生 date：必须与明暗主题一致，否则明亮模式下会看不清文字/日历图标 */
.account-page:not(.is-light) .field input[type='date'] {
  color-scheme: dark;
}

.account-page.is-light .field input[type='date'] {
  color-scheme: light;
  color: #0f172a;
}

.account-page.is-light .field input[type='date']::-webkit-calendar-picker-indicator {
  cursor: pointer;
  opacity: 0.75;
}

.account-page.is-light .field input[type='date']:hover::-webkit-calendar-picker-indicator,
.account-page.is-light .field input[type='date']:focus::-webkit-calendar-picker-indicator {
  opacity: 1;
}

.location-cascader :deep(.arco-input-wrapper) {
  border: 1px solid rgba(148, 163, 184, 0.18);
  border-radius: 12px;
  background: rgba(2, 6, 23, 0.58) !important;
  transition:
    border-color 0.2s ease,
    box-shadow 0.2s ease;
}

.location-cascader :deep(.arco-input-wrapper:hover) {
  border-color: rgba(0, 194, 160, 0.42);
}

.location-cascader :deep(.arco-input-wrapper:focus-within) {
  border-color: rgba(0, 194, 160, 0.68);
  box-shadow: 0 0 0 3px rgba(0, 194, 160, 0.16);
}

.location-cascader :deep(.arco-input) {
  color: var(--acc-text);
}

.location-cascader :deep(.arco-input),
.location-cascader :deep(.arco-input[readonly]),
.location-cascader :deep(input) {
  background: transparent !important;
  color: var(--acc-text) !important;
}

.location-cascader :deep(.arco-input::placeholder),
.location-cascader :deep(input::placeholder) {
  color: #64748b !important;
}

/* Arco Cascader 实际显示层：覆盖默认白底 */
:deep(.location-cascader.arco-select-view-single),
:deep(.location-cascader.arco-cascader-view),
.location-cascader :deep(.arco-select-view-single),
.location-cascader :deep(.arco-cascader-view) {
  background: rgba(2, 6, 23, 0.58) !important;
  border: 1px solid rgba(148, 163, 184, 0.18) !important;
  border-radius: 12px !important;
}

:deep(.location-cascader.arco-select-view-single:hover),
:deep(.location-cascader.arco-cascader-view:hover),
.location-cascader :deep(.arco-select-view-single:hover),
.location-cascader :deep(.arco-cascader-view:hover) {
  border-color: rgba(0, 194, 160, 0.42) !important;
}

:deep(.location-cascader.arco-select-view-single:focus-within),
:deep(.location-cascader.arco-select-view-single.arco-select-view-focus),
:deep(.location-cascader.arco-cascader-view-focus),
.location-cascader :deep(.arco-select-view-single:focus-within),
.location-cascader :deep(.arco-select-view-single.arco-select-view-focus),
.location-cascader :deep(.arco-cascader-view-focus) {
  border-color: rgba(0, 194, 160, 0.68) !important;
  box-shadow: 0 0 0 3px rgba(0, 194, 160, 0.16) !important;
}

:deep(.location-cascader .arco-select-view-value),
:deep(.location-cascader .arco-select-view-input),
.location-cascader :deep(.arco-select-view-value),
.location-cascader :deep(.arco-select-view-input) {
  color: var(--acc-text) !important;
}

.account-page.is-light .location-cascader :deep(.arco-input-wrapper) {
  background: rgba(255, 255, 255, 0.92) !important;
  border-color: rgba(15, 23, 42, 0.16);
}

.account-page.is-light :deep(.location-cascader.arco-select-view-single),
.account-page.is-light :deep(.location-cascader.arco-cascader-view),
.account-page.is-light .location-cascader :deep(.arco-select-view-single),
.account-page.is-light .location-cascader :deep(.arco-cascader-view) {
  background: rgba(255, 255, 255, 0.92) !important;
  border-color: rgba(15, 23, 42, 0.16) !important;
}

.account-page.is-light .location-badge {
  color: #0f766e;
  background: rgba(16, 185, 129, 0.12);
  border-color: rgba(16, 185, 129, 0.3);
}

.radio-row {
  display: flex;
  gap: 12px;
}

.radio-btn {
  flex: 1;
  border: 1px solid rgba(148, 163, 184, 0.18);
  background: rgba(2, 6, 23, 0.45);
  border-radius: 12px;
  padding: 10px 14px;
  cursor: pointer;
  color: #cbd5e1;
  font-size: 14px;
  font-weight: 600;
  transition:
    border-color 0.2s ease,
    background 0.2s ease,
    color 0.2s ease,
    box-shadow 0.2s ease,
    transform 0.15s ease;
}

.account-page.is-light .radio-btn {
  color: #334155;
  background: linear-gradient(180deg, rgba(255, 255, 255, 0.96) 0%, rgba(248, 250, 252, 0.92) 100%);
  border-color: rgba(15, 23, 42, 0.14);
  box-shadow: 0 1px 2px rgba(15, 23, 42, 0.04);
}

.radio-btn:hover {
  border-color: rgba(0, 194, 160, 0.35);
}

.account-page.is-light .radio-btn:hover {
  color: #0f172a;
  border-color: rgba(13, 148, 136, 0.34);
  background: linear-gradient(180deg, #ffffff 0%, #f0fdfa 100%);
}

.radio-btn:focus-visible {
  outline: none;
  border-color: rgba(0, 194, 160, 0.6);
  box-shadow: 0 0 0 3px rgba(0, 194, 160, 0.14);
}

.account-page.is-light .radio-btn:focus-visible {
  border-color: rgba(13, 148, 136, 0.52);
  box-shadow: 0 0 0 3px rgba(20, 184, 166, 0.16);
}

.radio-btn.active {
  border-color: rgba(0, 194, 160, 0.7);
  background: rgba(0, 194, 160, 0.12);
  color: #99f6e4;
  box-shadow: 0 0 20px rgba(0, 194, 160, 0.12);
}

.account-page.is-light .radio-btn.active {
  color: #0f172a;
  border-color: rgba(13, 148, 136, 0.58);
  background: linear-gradient(180deg, rgba(204, 251, 241, 0.98) 0%, rgba(153, 246, 228, 0.92) 100%);
  box-shadow:
    0 6px 16px rgba(20, 184, 166, 0.16),
    0 0 0 1px rgba(255, 255, 255, 0.7) inset;
}

.account-page.is-light .radio-btn.active:hover {
  color: #022c22;
  border-color: rgba(15, 118, 110, 0.72);
  background: linear-gradient(180deg, #d6fff7 0%, #a7f3d0 100%);
}

.account-page.is-light .radio-btn:active {
  transform: translateY(1px);
}

.account-page.is-light .radio-btn.active:active {
  transform: translateY(1px) scale(0.995);
}

.form-actions {
  margin-top: 8px;
  padding-top: 18px;
  border-top: 1px solid rgba(148, 163, 184, 0.12);
  display: flex;
  justify-content: space-between;
  align-items: center;
  flex-wrap: wrap;
  gap: 12px;
}

.form-actions-hint {
  color: var(--acc-muted);
  font-size: 13px;
}

.account-page.is-light .form-actions-hint,
.account-page.is-light .form-actions-status {
  color: rgba(15, 23, 42, 0.60);
}

.form-actions-right {
  display: flex;
  align-items: center;
  gap: 12px;
  min-height: 40px;
}

.form-actions-status {
  color: var(--acc-muted);
  font-size: 13px;
}

.save-btn {
  border: none;
  border-radius: 12px;
  padding: 11px 26px;
  background: linear-gradient(135deg, var(--acc-teal) 0%, var(--acc-teal-2) 100%);
  color: #071018;
  font-size: 14px;
  font-weight: 800;
  letter-spacing: 0.04em;
  cursor: pointer;
  box-shadow:
    0 6px 22px rgba(0, 194, 160, 0.22),
    0 0 0 1px rgba(255, 255, 255, 0.12) inset;
  transition:
    transform 0.15s ease,
    filter 0.2s ease,
    box-shadow 0.2s ease,
    opacity 0.2s ease;
}

.save-btn--header {
  padding: 10px 18px;
  border-radius: 12px;
  font-weight: 800;
  white-space: nowrap;
}

.save-btn:hover:not(:disabled) {
  filter: brightness(1.06);
  transform: translateY(-2px);
  box-shadow:
    0 10px 30px rgba(0, 194, 160, 0.28),
    0 0 0 1px rgba(255, 255, 255, 0.16) inset;
}

.save-btn:disabled {
  opacity: 0.55;
  cursor: not-allowed;
  transform: none;
}

.website-list {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.website-row {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  align-items: center;
}

.website-input {
  flex: 1;
}

.website-input.is-invalid {
  border-color: rgba(248, 113, 113, 0.8) !important;
  box-shadow: 0 0 0 3px rgba(248, 113, 113, 0.14) !important;
}

.website-error {
  width: 100%;
  margin-top: -2px;
  font-size: 12px;
  color: #fca5a5;
}

.account-page.is-light .website-error {
  color: #dc2626;
}

.website-del-btn {
  height: 40px;
  padding: 0 12px;
  border-radius: 12px;
  border: 1px solid rgba(148, 163, 184, 0.18);
  background: rgba(2, 6, 23, 0.45);
  color: rgba(226, 232, 240, 0.82);
  cursor: pointer;
  font-size: 13px;
  font-weight: 650;
  transition:
    border-color 0.2s ease,
    background 0.2s ease,
    transform 0.15s ease;
}

.account-page.is-light .website-del-btn {
  background: rgba(255, 255, 255, 0.75);
  border-color: rgba(15, 23, 42, 0.12);
  color: rgba(15, 23, 42, 0.72);
}

.website-del-btn:disabled {
  opacity: 0.45;
  cursor: not-allowed;
}

.website-del-btn:hover:not(:disabled) {
  transform: translateY(-1px);
  border-color: rgba(248, 113, 113, 0.45);
}

.website-add-btn {
  align-self: flex-start;
  border: 1px solid rgba(0, 194, 160, 0.28);
  background: rgba(0, 194, 160, 0.08);
  color: rgba(153, 246, 228, 0.95);
  border-radius: 12px;
  padding: 9px 12px;
  cursor: pointer;
  font-weight: 700;
  transition:
    filter 0.2s ease,
    transform 0.15s ease,
    background 0.2s ease;
}

.website-add-btn:hover {
  filter: brightness(1.08);
  transform: translateY(-1px);
}

.security-cards {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 14px;
}

.security-card {
  border: 1px solid rgba(148, 163, 184, 0.16);
  border-radius: 14px;
  background: linear-gradient(180deg, rgba(2, 6, 23, 0.28) 0%, rgba(2, 6, 23, 0.2) 100%);
  padding: 16px;
  box-shadow: 0 10px 24px rgba(0, 0, 0, 0.12);
}

.security-card--clickable {
  cursor: pointer;
  transition:
    border-color 0.2s ease,
    transform 0.15s ease,
    box-shadow 0.2s ease;
}

.security-card--clickable:hover {
  border-color: rgba(0, 194, 160, 0.4);
  transform: translateY(-1px);
  box-shadow: 0 10px 24px rgba(0, 0, 0, 0.2);
}

.account-page.is-light .security-card {
  background: linear-gradient(180deg, rgba(255, 255, 255, 0.92) 0%, rgba(255, 255, 255, 0.76) 100%);
  border-color: rgba(15, 23, 42, 0.10);
  box-shadow: 0 10px 22px rgba(15, 23, 42, 0.07);
}

.security-card-title {
  font-weight: 800;
  font-size: 15px;
  color: rgba(226, 232, 240, 0.92);
}

.account-page.is-light .security-card-title {
  color: rgba(15, 23, 42, 0.86);
}

.security-card-desc {
  margin-top: 8px;
  font-size: 13px;
  color: var(--acc-muted);
  line-height: 1.5;
}

.security-card--danger-zone {
  border-color: rgba(248, 113, 113, 0.22);
  background: linear-gradient(165deg, rgba(248, 113, 113, 0.07) 0%, rgba(17, 24, 39, 0.35) 42%);
}

.account-page.is-light .security-card--danger-zone {
  border-color: rgba(220, 38, 38, 0.14);
  background: linear-gradient(165deg, rgba(254, 226, 226, 0.55) 0%, rgba(255, 255, 255, 0.88) 50%);
}

.security-desc-strong {
  color: rgba(254, 202, 202, 0.98);
  font-weight: 800;
}

.account-page.is-light .security-desc-strong {
  color: #b91c1c;
}

.security-destructive-action {
  margin-top: 16px;
  display: flex;
  justify-content: flex-start;
}

.security-outline-danger-btn {
  border-radius: 14px;
  border: 1px solid rgba(248, 113, 113, 0.5);
  background: rgba(248, 113, 113, 0.1);
  color: rgba(254, 202, 202, 0.98);
  padding: 10px 12px;
  font-weight: 800;
  cursor: pointer;
  transition:
    transform 0.15s ease,
    filter 0.2s ease,
    border-color 0.2s ease,
    background 0.2s ease,
    box-shadow 0.2s ease;
}

.security-outline-danger-btn--main {
  display: inline-flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 3px;
  min-width: min(100%, 280px);
  padding: 14px 18px;
  text-align: left;
  box-shadow: 0 8px 22px rgba(0, 0, 0, 0.12);
}

.account-page.is-light .security-outline-danger-btn--main {
  box-shadow: 0 8px 20px rgba(185, 28, 28, 0.08);
}

.security-danger-btn-label {
  font-size: 14px;
  letter-spacing: 0.02em;
}

.security-danger-btn-hint {
  font-size: 11px;
  font-weight: 650;
  opacity: 0.82;
}

.security-outline-danger-btn:hover:not(:disabled) {
  transform: translateY(-1px);
  filter: brightness(1.06);
  border-color: rgba(248, 113, 113, 0.65);
}

.security-outline-danger-btn:disabled {
  opacity: 0.45;
  cursor: not-allowed;
  box-shadow: none;
}

.account-page.is-light .security-outline-danger-btn {
  color: #b91c1c;
  border-color: rgba(220, 38, 38, 0.38);
  background: rgba(254, 226, 226, 0.45);
}

.account-page.is-light .security-outline-danger-btn:hover:not(:disabled) {
  border-color: rgba(220, 38, 38, 0.5);
  background: rgba(254, 226, 226, 0.65);
}

.security-muted--below-actions {
  margin-top: 12px;
  font-size: 12px;
  line-height: 1.55;
}

.close-account-warn {
  margin: -2px 0 6px;
  font-size: 13px;
  line-height: 1.55;
  color: var(--acc-muted);
}

.close-account-warn strong {
  color: var(--acc-text);
  font-weight: 800;
}

.security-muted {
  margin-top: 10px;
  font-size: 13px;
  color: var(--acc-muted);
}

.password-modal {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.email-code-row {
  display: flex;
  gap: 10px;
}

.email-code-row :deep(.arco-input-wrapper) {
  min-width: 0;
  flex: 1;
}

.email-code-row :deep(.arco-btn) {
  flex: none;
  min-width: 112px;
  border-radius: 11px;
}

.profile-load-error {
  margin: 8px 0 0;
  color: #dc2626;
  font-size: 13px;
}

.profile-load-error button {
  margin-left: 6px;
  padding: 0;
  border: 0;
  color: #0d9488;
  background: none;
  font: inherit;
  font-weight: 700;
  cursor: pointer;
  text-decoration: underline;
}

.password-modal-tip {
  margin: -2px 0 2px;
  padding: 8px 10px;
  border-radius: 10px;
  border: 1px solid rgba(45, 212, 191, 0.26);
  background: rgba(45, 212, 191, 0.08);
  color: rgba(153, 246, 228, 0.95);
  font-size: 12px;
  line-height: 1.5;
}

.password-modal :deep(.arco-input-wrapper),
.password-modal :deep(.arco-input-password) {
  border-radius: 12px;
  border-color: rgba(148, 163, 184, 0.2);
  background: rgba(2, 6, 23, 0.42);
  transition:
    border-color 0.2s ease,
    box-shadow 0.2s ease,
    transform 0.14s ease;
}

.password-modal :deep(.arco-input-wrapper:hover),
.password-modal :deep(.arco-input-password:hover) {
  border-color: rgba(45, 212, 191, 0.48);
}

.password-modal :deep(.arco-input-wrapper.arco-input-focus),
.password-modal :deep(.arco-input-password.arco-input-focus) {
  border-color: rgba(45, 212, 191, 0.7);
  box-shadow: 0 0 0 3px rgba(45, 212, 191, 0.18);
  transform: translateY(-1px);
}

.password-modal :deep(.arco-input),
.password-modal :deep(input) {
  color: #e2e8f0;
}

.account-page.is-light .password-modal-tip {
  color: #0f766e;
  background: rgba(16, 185, 129, 0.1);
  border-color: rgba(16, 185, 129, 0.28);
}

.password-modal--light .password-modal-tip {
  color: #0f766e;
  background: rgba(16, 185, 129, 0.1);
  border-color: rgba(16, 185, 129, 0.28);
}

.password-modal--light.password-modal :deep(.arco-input-wrapper),
.password-modal--light.password-modal :deep(.arco-input-password) {
  background: rgba(255, 255, 255, 0.96);
  border-color: rgba(15, 23, 42, 0.14);
}

.password-modal--light.password-modal :deep(.arco-input-wrapper:hover),
.password-modal--light.password-modal :deep(.arco-input-password:hover) {
  border-color: rgba(13, 148, 136, 0.45);
}

.password-modal--light.password-modal :deep(.arco-input-wrapper.arco-input-focus),
.password-modal--light.password-modal :deep(.arco-input-password.arco-input-focus) {
  border-color: rgba(13, 148, 136, 0.75);
  box-shadow: 0 0 0 3px rgba(20, 184, 166, 0.2);
}

.password-modal--light.password-modal :deep(.arco-input),
.password-modal--light.password-modal :deep(input) {
  color: #0f172a;
}

@media (max-width: 980px) {
  .account-page-inner {
    grid-template-columns: 1fr;
  }
  .profile-side {
    position: static;
  }
  .panel-grid.two {
    grid-template-columns: 1fr;
  }
  .security-cards {
    grid-template-columns: 1fr;
  }
  .security-destructive-action {
    justify-content: stretch;
  }
  .security-outline-danger-btn--main {
    width: 100%;
    max-width: none;
  }
}
</style>

<style>
.password-modal-shell.arco-modal {
  overflow: hidden;
  border-radius: 18px;
  animation: pwd-modal-in 0.28s cubic-bezier(0.34, 1.16, 0.64, 1);
}

/* 深色：与账户页深色玻璃风一致 */
.password-modal-shell--dark.arco-modal {
  border: 1px solid rgba(45, 212, 191, 0.24);
  background:
    radial-gradient(120% 85% at 12% 0%, rgba(45, 212, 191, 0.18), transparent 56%),
    linear-gradient(180deg, rgba(15, 23, 42, 0.98) 0%, rgba(2, 6, 23, 0.97) 100%);
  box-shadow:
    0 32px 70px rgba(0, 0, 0, 0.55),
    0 0 0 1px rgba(45, 212, 191, 0.14) inset;
}

.password-modal-shell--dark .arco-modal-header {
  border-bottom: 1px solid rgba(148, 163, 184, 0.2);
  background: linear-gradient(135deg, rgba(15, 118, 110, 0.22) 0%, rgba(2, 6, 23, 0) 68%);
}

.password-modal-shell--dark .arco-modal-title {
  color: #f1f5f9;
  font-size: 20px;
  font-weight: 800;
  letter-spacing: 0.02em;
}

.password-modal-shell--dark .arco-modal-body {
  background: transparent;
}

.password-modal-shell--dark .arco-modal-footer {
  border-top: 1px solid rgba(148, 163, 184, 0.18);
  background: rgba(2, 6, 23, 0.5);
}

.password-modal-shell--dark .arco-modal-close-icon {
  color: rgba(226, 232, 240, 0.88);
}

.password-modal-shell--dark .arco-btn-secondary {
  border-color: rgba(148, 163, 184, 0.32);
  background: rgba(15, 23, 42, 0.48);
  color: rgba(226, 232, 240, 0.9);
}

.password-modal-shell--dark .arco-btn-secondary:hover {
  border-color: rgba(45, 212, 191, 0.45);
  color: #f8fafc;
}

.password-modal-shell--dark .arco-btn-primary {
  border: none;
  color: #071018;
  background: linear-gradient(135deg, #14b8a6 0%, #2dd4bf 100%);
  box-shadow: 0 8px 26px rgba(20, 184, 166, 0.38);
}

.password-modal-shell--dark .arco-btn-primary:hover {
  filter: brightness(1.06);
  transform: translateY(-1px);
}

/* 浅色：与账户页浅色主题一致 */
.password-modal-shell--light.arco-modal {
  border: 1px solid rgba(15, 23, 42, 0.1);
  background:
    radial-gradient(120% 90% at 10% 0%, rgba(0, 194, 160, 0.14), transparent 58%),
    linear-gradient(180deg, rgba(255, 255, 255, 0.98) 0%, rgba(248, 250, 252, 0.98) 100%);
  box-shadow:
    0 28px 56px rgba(15, 23, 42, 0.12),
    0 0 0 1px rgba(255, 255, 255, 0.85) inset;
}

.password-modal-shell--light .arco-modal-header {
  border-bottom: 1px solid rgba(15, 23, 42, 0.08);
  background: linear-gradient(135deg, rgba(240, 253, 250, 0.95) 0%, rgba(255, 255, 255, 0) 65%);
}

.password-modal-shell--light .arco-modal-title {
  color: #0f172a;
  font-size: 20px;
  font-weight: 800;
  letter-spacing: 0.02em;
}

.password-modal-shell--light .arco-modal-body {
  background: transparent;
}

.password-modal-shell--light .arco-modal-footer {
  border-top: 1px solid rgba(15, 23, 42, 0.08);
  background: rgba(248, 250, 252, 0.92);
}

.password-modal-shell--light .arco-modal-close-icon {
  color: rgba(15, 23, 42, 0.55);
}

.password-modal-shell--light .arco-btn-secondary {
  border-color: rgba(15, 23, 42, 0.16);
  background: rgba(255, 255, 255, 0.92);
  color: rgba(15, 23, 42, 0.72);
}

.password-modal-shell--light .arco-btn-secondary:hover {
  border-color: rgba(13, 148, 136, 0.45);
  color: #0f172a;
}

.password-modal-shell--light .arco-btn-primary {
  border: none;
  color: #071018;
  background: linear-gradient(135deg, #0d9488 0%, #14b8a6 100%);
  box-shadow: 0 8px 22px rgba(13, 148, 136, 0.28);
}

.password-modal-shell--light .arco-btn-primary:hover {
  filter: brightness(1.05);
  transform: translateY(-1px);
}

@keyframes pwd-modal-in {
  from {
    opacity: 0;
    transform: translateY(10px) scale(0.98);
  }
  to {
    opacity: 1;
    transform: translateY(0) scale(1);
  }
}
</style>
