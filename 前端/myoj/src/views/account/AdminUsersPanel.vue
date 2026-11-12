<script setup lang="ts">
import { computed, h, onMounted, reactive, ref } from 'vue'
import { Checkbox, InputNumber, Message, Modal } from '@arco-design/web-vue'
import { useRouter } from 'vue-router'
import { ApiError, OpenAPI, Service, type UserAdminListItemVO } from '@generated'
import { useAuthStore } from '@/stores/auth'
import { isResultSuccess } from '@/api/result'
import { getBackendErrorMessage, isAxiosUnauthorizedResponse } from '@/api/httpError'
import { parseBanUntilMs } from '@/utils/formatBanCountdown'

const props = defineProps<{
  isLight: boolean
}>()

type AdminUserItem = UserAdminListItemVO & {
  lastLoginRegion?: string
  lastLoginDevice?: string
  lastLoginOs?: string
  lastLoginBrowser?: string
  lastLoginDeviceDetails?: string
}

type FieldItem = { key: string; label: string; value: string; mono?: boolean; hot?: boolean; empty?: boolean }

const auth = useAuthStore()
const router = useRouter()

const loading = ref(false)
const records = ref<AdminUserItem[]>([])
const total = ref(0)
const current = ref(1)
const pageSize = ref(10)
const statusBusyUserId = ref<string | null>(null)
/** 输入框中的关键词（未提交） */
const searchInput = ref('')
/** 已提交并参与查询的关键词 */
const searchKeyword = ref('')

const hasActiveSearch = computed(() => searchKeyword.value.trim().length > 0)
const pageAbnormalCount = computed(() => records.value.filter((r) => r.abnormal).length)
const pageDisabledCount = computed(() => records.value.filter((r) => r.status === 0).length)

function rowKey(row: AdminUserItem, idx: number): string {
  const id = row.id
  if (id !== undefined && id !== null && String(id).trim()) return String(id)
  const u = row.username?.trim()
  if (u) return u
  return `row-${idx}`
}

function displayText(v: string | null | undefined): string {
  const s = v == null ? '' : String(v).trim()
  return s || '—'
}

function formatAccountStatus(status: number | undefined): string {
  if (status === 0) return '已禁用'
  if (status === 1) return '正常'
  return status === undefined || status === null ? '—' : String(status)
}

function isCommentBanActive(row: AdminUserItem): boolean {
  const end = parseBanUntilMs(row.commentBanUntil ?? (row.banType === 'profanity' ? row.banUntil : null) ?? null)
  if (end == null) return row.banType === 'profanity'
  return end > Date.now()
}

function isAiAssistBanActive(row: AdminUserItem): boolean {
  const end = parseBanUntilMs(row.aiBanUntil ?? (row.banType === 'profanity_ai' ? row.banUntil : null) ?? null)
  if (end == null) return row.banType === 'profanity_ai'
  return end > Date.now()
}

function rowCommentBanReason(row: AdminUserItem): string {
  return row.commentBanReason?.trim() || (row.banType === 'profanity' ? row.banReason?.trim() : '') || ''
}

function rowAiBanReason(row: AdminUserItem): string {
  return row.aiBanReason?.trim() || (row.banType === 'profanity_ai' ? row.banReason?.trim() : '') || ''
}

function isAdminTimedDisable(row: AdminUserItem): boolean {
  return row.status === 0 && row.banType === 'admin' && !!row.banUntil
}

const disableForm = reactive({ permanent: false, hours: 24 })

function promptDisable(row: AdminUserItem) {
  if (isCurrentLoginRow(row)) {
    Message.warning('不能禁用当前登录账号')
    return
  }
  const name = String(row.username ?? row.nickname ?? row.id ?? '').trim() || '该用户'
  disableForm.permanent = false
  disableForm.hours = 24
  Modal.open({
    title: '禁用用户',
    width: 440,
    okText: '确认禁用',
    cancelText: '取消',
    content: () =>
      h('div', { class: 'admin-disable-modal' }, [
        h('p', { class: 'admin-disable-modal__desc' }, [
          `确定禁用「${name}」？禁用后将立即踢出在线会话；用户可重新登录，但无法做题、写博客、签到等。`,
        ]),
        h(
          Checkbox,
          {
            modelValue: disableForm.permanent,
            'onUpdate:modelValue': (v: boolean) => {
              disableForm.permanent = v
            },
          },
          { default: () => '永久禁用（直至手动启用）' },
        ),
        h('div', { class: 'admin-disable-modal__hours', style: { display: disableForm.permanent ? 'none' : 'flex' } },
          [
            h('span', { class: 'admin-disable-modal__label' }, '禁用时长：'),
            h(InputNumber, {
              modelValue: disableForm.hours,
              'onUpdate:modelValue': (v: number | undefined) => {
                disableForm.hours = typeof v === 'number' ? v : 24
              },
              min: 1,
              max: 8760,
              precision: 0,
              style: { width: '140px' },
            }),
            h('span', { class: 'admin-disable-modal__unit' }, '小时（到期自动恢复）'),
          ],
        ),
      ]),
    onBeforeOk: async () => {
      if (!disableForm.permanent && (!disableForm.hours || disableForm.hours < 1)) {
        Message.warning('请填写大于 0 的禁用小时数，或勾选永久禁用')
        return false
      }
      const banHours = disableForm.permanent ? 0 : Math.floor(disableForm.hours)
      return postStatus(row, 0, banHours)
    },
  })
}

function formatCheckInDate(raw: string | null | undefined): string {
  if (raw == null || String(raw).trim() === '') return '—'
  const s = String(raw).trim()
  if (/^\d{4}-\d{2}-\d{2}$/.test(s)) return s
  const d = new Date(s)
  if (Number.isNaN(d.getTime())) return s
  const y = d.getFullYear()
  const m = String(d.getMonth() + 1).padStart(2, '0')
  const day = String(d.getDate()).padStart(2, '0')
  return `${y}-${m}-${day}`
}

function formatLoginTime(raw: string | null | undefined): string {
  if (raw == null || String(raw).trim() === '') return '—'
  const d = new Date(raw)
  if (Number.isNaN(d.getTime())) return String(raw)
  return d.toLocaleString('zh-CN', { hour12: false })
}

function roleLabel(role: string | undefined): string {
  const r = (role ?? '').trim()
  if (!r) return '—'
  if (/^(admin|管理员)$/i.test(r)) return '管理员'
  if (/^(user|普通用户)$/i.test(r)) return '用户'
  return r
}

function isAdminRole(role: string | undefined): boolean {
  return /^(admin|管理员)$/i.test((role ?? '').trim())
}

function buildFields(row: AdminUserItem): FieldItem[] {
  const fail = row.loginFailCount ?? 0
  const reason = row.abnormalReason?.trim()
  const banFields: FieldItem[] = [
    {
      key: 'banReason',
      label: '封禁原因',
      value: displayText(
        [rowCommentBanReason(row), rowAiBanReason(row)].filter(Boolean).join('；') || row.banReason,
      ),
      empty: !rowCommentBanReason(row) && !rowAiBanReason(row) && !row.banReason?.trim(),
    },
    {
      key: 'banType',
      label: '封禁类型',
      value:
        row.banType === 'profanity'
          ? '违禁评论限制（仅禁发评论）'
          : row.banType === 'profanity_ai'
            ? 'AI 助手违禁限制（仅禁学习助手）'
            : row.banType === 'admin'
            ? '管理员全站禁用'
            : displayText(row.banType),
      empty: !row.banType?.trim(),
    },
    {
      key: 'banUntil',
      label: '解禁时间',
      value: (() => {
        const parts: string[] = []
        if (isCommentBanActive(row)) parts.push(`评论：${formatBanUntil(row.commentBanUntil ?? row.banUntil)}`)
        if (isAiAssistBanActive(row)) parts.push(`AI：${formatBanUntil(row.aiBanUntil ?? row.banUntil)}`)
        return parts.length ? parts.join('；') : formatBanUntil(row.banUntil)
      })(),
      mono: true,
      empty: !isCommentBanActive(row) && !isAiAssistBanActive(row) && !row.banUntil,
    },
  ]
  const common: FieldItem[] = [
    { key: 'email', label: '邮箱', value: displayText(row.email), empty: !row.email?.trim() },
    { key: 'phone', label: '手机', value: displayText(row.phone), mono: !!row.phone?.trim(), empty: !row.phone?.trim() },
    { key: 'lastLoginIp', label: '最近登录 IP', value: displayText(row.lastLoginIp), mono: !!row.lastLoginIp?.trim(), empty: !row.lastLoginIp?.trim() },
    { key: 'lastLoginRegion', label: 'IP 归属地（估算）', value: displayText(row.lastLoginRegion), empty: !row.lastLoginRegion?.trim() },
    { key: 'lastLoginDevice', label: '最近登录设备', value: displayText(row.lastLoginDevice), empty: !row.lastLoginDevice?.trim() },
    { key: 'lastLoginOs', label: '操作系统', value: displayText(row.lastLoginOs), empty: !row.lastLoginOs?.trim() },
    { key: 'lastLoginBrowser', label: '浏览器', value: displayText(row.lastLoginBrowser), empty: !row.lastLoginBrowser?.trim() },
    { key: 'lastLoginDeviceDetails', label: '设备概况（浏览器提供）', value: displayText(row.lastLoginDeviceDetails), empty: !row.lastLoginDeviceDetails?.trim() },
    { key: 'lastLoginTime', label: '最近登录时间', value: formatLoginTime(row.lastLoginTime ?? undefined), empty: !row.lastLoginTime },
    {
      key: 'loginFailCount',
      label: '连续登录失败',
      value: String(fail),
      hot: fail >= 5,
      empty: fail === 0,
    },
    {
      key: 'points',
      label: '积分',
      value: row.points != null ? String(row.points) : '—',
      empty: row.points == null,
    },
    {
      key: 'checkInCount',
      label: '累计签到',
      value: row.checkInCount != null ? String(row.checkInCount) : '—',
      empty: row.checkInCount == null,
    },
    {
      key: 'lastCheckInDate',
      label: '最后签到日',
      value: formatCheckInDate(row.lastCheckInDate ?? undefined),
      mono: true,
      empty: !row.lastCheckInDate,
    },
    {
      key: 'abnormalReason',
      label: '异常说明',
      value: displayText(row.abnormalReason),
      empty: !reason,
    },
  ]
  if (row.abnormal || row.banType?.trim() || row.banReason?.trim()) {
    return [...banFields, ...common]
  }
  return [...common, ...banFields]
}

function formatBanUntil(raw: string | null | undefined): string {
  if (raw == null || String(raw).trim() === '') return '—'
  const d = new Date(String(raw).replace(' ', 'T') + '+08:00')
  if (Number.isNaN(d.getTime())) return String(raw)
  return d.toLocaleString('zh-CN', { hour12: false })
}

function looksLikeSessionMessage(msg: string): boolean {
  return /(token|jwt|登录|令牌|鉴权|未授权).*(过期|失效|无效)|请先登录|未授权|已过期|无效或已过期/i.test(msg)
}

function sessionExpiredAndGoLogin() {
  auth.logout()
  Message.error('登录已过期，请重新登录')
  void router.push({ path: '/', query: { openAuth: '1' } })
}

async function getAuthHeaders() {
  const tokenResolver = OpenAPI.TOKEN
  const token = typeof tokenResolver === 'function' ? await tokenResolver({} as never) : tokenResolver
  return token ? { Authorization: `Bearer ${token}` } : undefined
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

async function fetchPage() {
  if (!auth.isAdmin) return
  if (!(await ensureBearerToken())) return
  loading.value = true
  try {
    const kw = searchKeyword.value.trim()
    const result = await Service.adminPageUsers(current.value, pageSize.value, kw || undefined)
    if (result.code === 401) {
      sessionExpiredAndGoLogin()
      return
    }
    if (result.code === 403) {
      Message.error(result.message?.trim() || '无权限访问')
      return
    }
    if (!isResultSuccess(result.code) || !result.data) {
      const msg = result.message?.trim() || ''
      if (msg && looksLikeSessionMessage(msg)) {
        sessionExpiredAndGoLogin()
        return
      }
      throw new Error(msg || '加载失败')
    }
    const payload = result.data
    records.value = Array.isArray(payload.records) ? (payload.records as AdminUserItem[]) : []
    total.value = typeof payload.total === 'number' ? payload.total : 0
    if (typeof payload.current === 'number' && payload.current > 0) {
      current.value = payload.current
    }
    if (typeof payload.size === 'number' && payload.size > 0) {
      pageSize.value = payload.size
    }
  } catch (e) {
    if (e instanceof ApiError) {
      const b = e.body as { code?: number; message?: string } | undefined
      if (b?.code === 401 || e.status === 401) {
        sessionExpiredAndGoLogin()
        return
      }
      if (b?.code === 403 || e.status === 403) {
        Message.error(b?.message?.trim() || '无权限访问')
        return
      }
      Message.error(getBackendErrorMessage(e, '加载用户列表失败'))
      return
    }
    if (isAxiosUnauthorizedResponse(e)) {
      sessionExpiredAndGoLogin()
      return
    }
    Message.error(getBackendErrorMessage(e, '加载用户列表失败'))
  } finally {
    loading.value = false
  }
}

function onPageChange(page: number) {
  current.value = page
  void fetchPage()
}

function onPageSizeChange(size: number) {
  pageSize.value = size
  current.value = 1
  void fetchPage()
}

function applySearch() {
  searchKeyword.value = searchInput.value.trim()
  current.value = 1
  void fetchPage()
}

function clearSearch() {
  searchInput.value = ''
  searchKeyword.value = ''
  current.value = 1
  void fetchPage()
}

function onSearchInputClear() {
  if (!searchKeyword.value.trim()) return
  clearSearch()
}

function isCurrentLoginRow(row: AdminUserItem): boolean {
  const rid = row.id
  if (rid === undefined || rid === null || String(rid).trim() === '') return false
  return String(rid) === String(auth.userId ?? '').trim()
}

function promptEnable(row: AdminUserItem) {
  const name = String(row.username ?? row.nickname ?? row.id ?? '').trim() || '该用户'
  Modal.confirm({
    title: '确认启用',
    content: `确定恢复「${name}」为正常状态？`,
    okText: '启用',
    cancelText: '取消',
    async onOk() {
      const ok = await postStatus(row, 1)
      if (!ok) return Promise.reject(new Error('keep-modal'))
    },
  })
}

function promptClearCommentBan(row: AdminUserItem) {
  const name = String(row.username ?? row.nickname ?? row.id ?? '').trim() || '该用户'
  const reason = rowCommentBanReason(row)
  Modal.confirm({
    title: '解除评论限制',
    content: reason
      ? `确定解除「${name}」的评论限制？\n限制原因：${reason}`
      : `确定解除「${name}」的评论限制？`,
    okText: '解除',
    cancelText: '取消',
    async onOk() {
      const ok = await postClearCommentBan(row)
      if (!ok) return Promise.reject(new Error('keep-modal'))
    },
  })
}

function promptClearAiAssistBan(row: AdminUserItem) {
  const name = String(row.username ?? row.nickname ?? row.id ?? '').trim() || '该用户'
  const reason = rowAiBanReason(row)
  Modal.confirm({
    title: '解除 AI 助手限制',
    content: reason
      ? `确定解除「${name}」的 AI 学习助手限制？\n限制原因：${reason}`
      : `确定解除「${name}」的 AI 学习助手限制？`,
    okText: '解除',
    cancelText: '取消',
    async onOk() {
      const ok = await postClearAiAssistBan(row)
      if (!ok) return Promise.reject(new Error('keep-modal'))
    },
  })
}

function parseAdminUserId(row: AdminUserItem): string | null {
  const uid = row.id
  if (uid === undefined || uid === null) return null
  // 雪花 ID 可能超过 JavaScript 安全整数范围，不能用 Number 转换。
  if (typeof uid === 'number' && !Number.isSafeInteger(uid)) return null
  const id = String(uid).trim()
  return /^[1-9]\d*$/.test(id) ? id : null
}

async function postClearAiAssistBan(row: AdminUserItem): Promise<boolean> {
  const userId = parseAdminUserId(row)
  if (userId == null) {
    Message.error('缺少用户 id')
    return false
  }
  const idKey = String(userId)
  statusBusyUserId.value = idKey
  try {
    if (!(await ensureBearerToken())) return false
    const data = await Service.adminClearProfanityAiAssistBan({ userId })
    if (data?.code === 401) {
      sessionExpiredAndGoLogin()
      return false
    }
    if (data?.code === 403) {
      Message.error(data?.message?.trim() || '无权限')
      return false
    }
    if (!isResultSuccess(data?.code)) {
      const msg = data?.message?.trim() || ''
      if (msg && looksLikeSessionMessage(msg)) {
        sessionExpiredAndGoLogin()
        return false
      }
      Message.error(msg || '操作失败')
      return false
    }
    Message.success(data?.message?.trim() || '已解除 AI 助手限制')
    await fetchPage()
    return true
  } catch (e) {
    if (e instanceof ApiError) {
      const b = e.body as { code?: number; message?: string } | undefined
      if (b?.code === 401 || e.status === 401) {
        sessionExpiredAndGoLogin()
        return false
      }
    }
    if (isAxiosUnauthorizedResponse(e)) {
      sessionExpiredAndGoLogin()
      return false
    }
    Message.error(getBackendErrorMessage(e, '操作失败'))
    return false
  } finally {
    statusBusyUserId.value = null
  }
}

async function postClearCommentBan(row: AdminUserItem): Promise<boolean> {
  const userId = parseAdminUserId(row)
  if (userId == null) {
    Message.error('缺少用户 id')
    return false
  }
  const idKey = String(userId)
  statusBusyUserId.value = idKey
  try {
    if (!(await ensureBearerToken())) return false
    const data = await Service.adminClearProfanityCommentBan({ userId })
    if (data?.code === 401) {
      sessionExpiredAndGoLogin()
      return false
    }
    if (data?.code === 403) {
      Message.error(data?.message?.trim() || '无权限')
      return false
    }
    if (!isResultSuccess(data?.code)) {
      const msg = data?.message?.trim() || ''
      if (msg && looksLikeSessionMessage(msg)) {
        sessionExpiredAndGoLogin()
        return false
      }
      Message.error(msg || '操作失败')
      return false
    }
    Message.success(data?.message?.trim() || '已解除评论限制')
    await fetchPage()
    return true
  } catch (e) {
    if (e instanceof ApiError) {
      const b = e.body as { code?: number; message?: string } | undefined
      if (b?.code === 401 || e.status === 401) {
        sessionExpiredAndGoLogin()
        return false
      }
    }
    if (isAxiosUnauthorizedResponse(e)) {
      sessionExpiredAndGoLogin()
      return false
    }
    Message.error(getBackendErrorMessage(e, '操作失败'))
    return false
  } finally {
    statusBusyUserId.value = null
  }
}

async function postStatus(row: AdminUserItem, status: 0 | 1, banHours?: number): Promise<boolean> {
  const userId = parseAdminUserId(row)
  if (userId == null) {
    Message.error('缺少用户 id')
    return false
  }
  if (status === 0 && isCurrentLoginRow(row)) {
    Message.warning('不能禁用当前登录账号')
    return false
  }
  const idKey = String(userId)
  statusBusyUserId.value = idKey
  try {
    if (!(await ensureBearerToken())) return false
    const data = await Service.adminSetUserStatus({
      userId,
      status,
      ...(status === 0 && banHours !== undefined ? { banHours } : {}),
    })
    if (data?.code === 401) {
      sessionExpiredAndGoLogin()
      return false
    }
    if (data?.code === 403) {
      Message.error(data?.message?.trim() || '无权限')
      return false
    }
    if (!isResultSuccess(data?.code)) {
      const msg = data?.message?.trim() || ''
      if (msg && looksLikeSessionMessage(msg)) {
        sessionExpiredAndGoLogin()
        return false
      }
      Message.error(msg || '操作失败')
      return false
    }
    Message.success(data?.message?.trim() || (status === 0 ? '已禁用' : '已启用'))
    await fetchPage()
    return true
  } catch (e) {
    if (e instanceof ApiError) {
      const b = e.body as { code?: number; message?: string } | undefined
      if (b?.code === 401 || e.status === 401) {
        sessionExpiredAndGoLogin()
        return false
      }
    }
    if (isAxiosUnauthorizedResponse(e)) {
      sessionExpiredAndGoLogin()
      return false
    }
    Message.error(getBackendErrorMessage(e, '操作失败'))
    return false
  } finally {
    statusBusyUserId.value = null
  }
}

onMounted(() => {
  void fetchPage()
})
</script>

<template>
  <div class="admin-users-panel" :class="{ 'is-light': props.isLight }">
    <div class="admin-users-toolbar">
      <div class="admin-users-heading">
        <h3 class="admin-users-title">用户列表</h3>
        <p class="admin-users-hint">
          异常判定：管理员禁用、违禁评论限制，或连续登录失败 ≥ 5 次。封禁原因与解禁时间见卡片字段。
        </p>
      </div>
      <a-button type="outline" size="medium" :loading="loading" class="admin-refresh-btn" @click="fetchPage">
        刷新
      </a-button>
    </div>

    <div class="admin-users-search">
      <a-input-search
        v-model="searchInput"
        class="admin-users-search-input"
        placeholder="按用户名搜索"
        allow-clear
        search-button
        :loading="loading"
        @search="applySearch"
        @press-enter="applySearch"
        @clear="onSearchInputClear"
      />
      <p v-if="hasActiveSearch" class="admin-users-search-meta">
        当前关键词：<strong>{{ searchKeyword }}</strong>
        <button type="button" class="admin-users-search-reset" @click="clearSearch">清除筛选</button>
      </p>
    </div>

    <div class="admin-users-stats" aria-label="用户概览">
      <div class="stat-card">
        <span class="stat-label">{{ hasActiveSearch ? '匹配用户' : '全站用户' }}</span>
        <strong class="stat-value">{{ total }}</strong>
      </div>
      <div class="stat-card stat-card--warn">
        <span class="stat-label">本页异常</span>
        <strong class="stat-value">{{ pageAbnormalCount }}</strong>
      </div>
      <div class="stat-card stat-card--muted">
        <span class="stat-label">本页已禁用</span>
        <strong class="stat-value">{{ pageDisabledCount }}</strong>
      </div>
    </div>

    <a-spin :loading="loading" class="admin-users-spin">
      <p v-if="!loading && records.length === 0" class="admin-users-empty">
        {{ hasActiveSearch ? `未找到与「${searchKeyword}」匹配的用户` : '暂无用户数据' }}
      </p>

      <ul v-else class="user-card-list">
        <li
          v-for="(row, idx) in records"
          :key="rowKey(row, idx)"
          class="user-card"
          :class="{
            'user-card--abnormal': row.abnormal,
            'user-card--disabled': row.status === 0,
            'user-card--comment-ban': isCommentBanActive(row),
            'user-card--ai-ban': isAiAssistBanActive(row),
          }"
        >
          <header class="user-card-head">
            <div class="user-card-identity">
              <span class="user-card-name">{{ displayText(row.username) }}</span>
              <span v-if="row.nickname?.trim()" class="user-card-nick">{{ row.nickname }}</span>
            </div>

            <p
              v-if="isCommentBanActive(row) || isAiAssistBanActive(row)"
              class="user-card-ban-reason"
            >
              <template v-if="isCommentBanActive(row) && rowCommentBanReason(row)">
                评论限制：{{ rowCommentBanReason(row) }}
              </template>
              <template v-if="isAiAssistBanActive(row) && rowAiBanReason(row)">
                <span v-if="isCommentBanActive(row) && rowCommentBanReason(row)"> · </span>
                AI 限制：{{ rowAiBanReason(row) }}
              </template>
            </p>

            <div class="user-card-badges">
              <span v-if="row.userRole" class="au-badge" :class="isAdminRole(row.userRole) ? 'au-badge--role-admin' : 'au-badge--role-user'">
                {{ roleLabel(row.userRole) }}
              </span>
              <span v-if="row.status === 1 && !isCommentBanActive(row) && !isAiAssistBanActive(row)" class="au-badge au-badge--ok">账号正常</span>
              <span v-else-if="isCommentBanActive(row)" class="au-badge au-badge--warn">评论受限</span>
              <span v-else-if="isAiAssistBanActive(row)" class="au-badge au-badge--warn">AI 助手受限</span>
              <span v-else-if="isAdminTimedDisable(row)" class="au-badge au-badge--warn">限时禁用</span>
              <span v-else-if="row.status === 0" class="au-badge au-badge--danger">已禁用</span>
              <span v-else class="au-badge au-badge--muted">{{ formatAccountStatus(row.status) }}</span>
              <span class="au-badge" :class="row.abnormal ? 'au-badge--warn' : 'au-badge--health'">
                {{ row.abnormal ? '健康异常' : '健康正常' }}
              </span>
            </div>

            <div class="user-card-actions">
              <a-button
                v-if="isCommentBanActive(row)"
                size="small"
                type="outline"
                :disabled="statusBusyUserId === String(row.id ?? '')"
                @click="promptClearCommentBan(row)"
              >
                {{ statusBusyUserId === String(row.id ?? '') ? '处理中' : '解除评论限制' }}
              </a-button>
              <a-button
                v-if="isAiAssistBanActive(row)"
                size="small"
                type="outline"
                :disabled="statusBusyUserId === String(row.id ?? '')"
                @click="promptClearAiAssistBan(row)"
              >
                {{ statusBusyUserId === String(row.id ?? '') ? '处理中' : '解除 AI 限制' }}
              </a-button>
              <a-button
                v-if="row.status !== 0"
                size="small"
                status="danger"
                :disabled="statusBusyUserId === String(row.id ?? '') || isCurrentLoginRow(row)"
                @click="promptDisable(row)"
              >
                {{ statusBusyUserId === String(row.id ?? '') ? '处理中' : '禁用' }}
              </a-button>
              <a-button
                v-else
                size="small"
                type="primary"
                :disabled="statusBusyUserId === String(row.id ?? '')"
                @click="promptEnable(row)"
              >
                {{ statusBusyUserId === String(row.id ?? '') ? '处理中' : '启用' }}
              </a-button>
            </div>
          </header>

          <div class="user-card-fields">
            <div
              v-for="field in buildFields(row)"
              :key="field.key"
              class="field-item"
              :class="{
                'field-item--full':
                  field.key === 'abnormalReason' || field.key === 'banReason',
              }"
            >
              <p class="field-label">{{ field.label }}</p>
              <p
                class="field-value"
                :class="{
                  'field-value--empty': field.empty,
                  'field-value--mono': field.mono,
                  'field-value--hot': field.hot,
                }"
              >
                {{ field.value }}
              </p>
            </div>
          </div>
        </li>
      </ul>
    </a-spin>

    <div v-if="total > 0" class="admin-users-footer">
      <a-pagination
        :total="total"
        :current="current"
        :page-size="pageSize"
        show-total
        show-page-size
        :page-size-options="[8, 10, 20]"
        @change="onPageChange"
        @page-size-change="onPageSizeChange"
      />
    </div>
  </div>
</template>

<style scoped>
.admin-users-panel {
  --au-teal: #34d399;
  --au-teal-dim: rgba(52, 211, 153, 0.16);
  --au-border: rgba(148, 163, 184, 0.28);
  --au-text: #f8fafc;
  --au-text-secondary: #e2e8f0;
  --au-muted: #94a3b8;
  --au-label: #7c8aa0;
  --au-surface: rgba(15, 23, 42, 0.55);
  --au-card: rgba(30, 41, 59, 0.72);
  --au-card-head: rgba(15, 23, 42, 0.65);
  --au-shadow: 0 10px 28px rgba(0, 0, 0, 0.28);
}

.admin-users-panel.is-light {
  --au-teal: #0d9488;
  --au-teal-dim: rgba(13, 148, 136, 0.1);
  --au-border: rgba(15, 23, 42, 0.12);
  --au-text: #0f172a;
  --au-text-secondary: #334155;
  --au-muted: #64748b;
  --au-label: #64748b;
  --au-surface: rgba(248, 250, 252, 0.9);
  --au-card: #ffffff;
  --au-card-head: rgba(248, 250, 252, 0.95);
  --au-shadow: 0 12px 32px rgba(15, 23, 42, 0.08);
}

.admin-users-toolbar {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 18px;
}

.admin-users-title {
  margin: 0 0 8px;
  font-size: 16px;
  font-weight: 700;
  color: var(--au-text);
  display: flex;
  align-items: center;
  gap: 8px;
}

.admin-users-title::before {
  content: '';
  width: 3px;
  height: 16px;
  border-radius: 99px;
  background: linear-gradient(180deg, var(--au-teal), #6ee7b7);
}

.admin-users-hint {
  margin: 0;
  max-width: 40rem;
  font-size: 13px;
  line-height: 1.65;
  color: var(--au-muted);
}

.admin-refresh-btn {
  flex-shrink: 0;
}

.admin-users-search {
  margin-bottom: 16px;
}

.admin-users-search-input {
  max-width: 480px;
}

.admin-users-search-input :deep(.arco-input-wrapper) {
  background: var(--au-card);
  border-color: var(--au-border);
}

.admin-users-search-input :deep(.arco-input) {
  color: var(--au-text);
}

.admin-users-search-input :deep(.arco-input::placeholder) {
  color: var(--au-muted);
}

.admin-users-search-meta {
  margin: 10px 0 0;
  font-size: 13px;
  color: var(--au-muted);
}

.admin-users-search-meta strong {
  color: var(--au-text-secondary);
  font-weight: 650;
}

.admin-users-search-reset {
  margin-left: 10px;
  padding: 0;
  border: none;
  background: none;
  color: var(--au-teal);
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
}

.admin-users-search-reset:hover {
  text-decoration: underline;
}

.admin-users-stats {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 14px;
  margin-bottom: 18px;
}

.stat-card {
  padding: 16px 18px;
  border-radius: 14px;
  border: 1px solid var(--au-border);
  background: var(--au-card);
  box-shadow: var(--au-shadow);
}

.stat-card--warn {
  border-color: rgba(251, 146, 60, 0.45);
  background: linear-gradient(145deg, rgba(251, 146, 60, 0.14), var(--au-card));
}

.is-light .stat-card--warn {
  background: linear-gradient(145deg, rgba(255, 237, 213, 0.9), #fff);
}

.stat-label {
  display: block;
  font-size: 12px;
  font-weight: 600;
  letter-spacing: 0.04em;
  color: var(--au-label);
  margin-bottom: 6px;
}

.stat-value {
  font-size: 26px;
  font-weight: 750;
  color: var(--au-text);
  letter-spacing: -0.02em;
}

.admin-users-spin {
  display: block;
  width: 100%;
  min-height: 120px;
}

.admin-users-empty {
  margin: 0;
  padding: 48px 16px;
  text-align: center;
  font-size: 14px;
  color: var(--au-muted);
  border: 1px dashed var(--au-border);
  border-radius: 14px;
  background: var(--au-surface);
}

.user-card-list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.user-card {
  border-radius: 16px;
  border: 1px solid var(--au-border);
  background: var(--au-card);
  box-shadow: var(--au-shadow);
  overflow: hidden;
  transition: border-color 0.2s ease, box-shadow 0.2s ease;
}

.user-card--abnormal {
  border-color: rgba(251, 146, 60, 0.5);
  box-shadow:
    var(--au-shadow),
    inset 3px 0 0 0 rgba(251, 146, 60, 0.85);
}

.user-card--disabled {
  opacity: 0.92;
}

.user-card--comment-ban {
  border-color: rgba(251, 191, 36, 0.45);
}

.user-card-head {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 12px 16px;
  padding: 14px 18px;
  background: var(--au-card-head);
  border-bottom: 1px solid var(--au-border);
}

.user-card-identity {
  display: flex;
  flex-wrap: wrap;
  align-items: baseline;
  gap: 8px 12px;
  min-width: 0;
  flex: 1 1 160px;
}

.user-card-name {
  font-size: 16px;
  font-weight: 750;
  color: var(--au-text);
  word-break: break-all;
}

.user-card-nick {
  font-size: 13px;
  color: var(--au-text-secondary);
}

.user-card-ban-reason {
  margin: 0 0 10px;
  padding: 10px 12px;
  border-radius: 8px;
  font-size: 13px;
  line-height: 1.5;
  color: #fde68a;
  background: rgba(251, 191, 36, 0.12);
  border: 1px solid rgba(251, 191, 36, 0.35);
  flex: 1 1 100%;
}

.admin-users-panel.is-light .user-card-ban-reason {
  color: #92400e;
  background: rgba(251, 191, 36, 0.15);
  border-color: rgba(217, 119, 6, 0.35);
}

.user-card-badges {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  flex: 1 1 200px;
}

.au-badge {
  display: inline-flex;
  align-items: center;
  padding: 4px 10px;
  border-radius: 8px;
  font-size: 12px;
  font-weight: 650;
  line-height: 1.2;
  border: 1px solid transparent;
}

.au-badge--role-admin {
  color: #93c5fd;
  background: rgba(59, 130, 246, 0.2);
  border-color: rgba(96, 165, 250, 0.45);
}

.is-light .au-badge--role-admin {
  color: #1d4ed8;
  background: rgba(219, 234, 254, 0.95);
  border-color: rgba(59, 130, 246, 0.35);
}

.au-badge--role-user {
  color: #cbd5e1;
  background: rgba(100, 116, 139, 0.28);
  border-color: rgba(148, 163, 184, 0.4);
}

.is-light .au-badge--role-user {
  color: #475569;
  background: rgba(241, 245, 249, 0.95);
  border-color: rgba(148, 163, 184, 0.35);
}

.au-badge--ok {
  color: #6ee7b7;
  background: rgba(16, 185, 129, 0.18);
  border-color: rgba(52, 211, 153, 0.45);
}

.is-light .au-badge--ok {
  color: #047857;
  background: rgba(209, 250, 229, 0.95);
  border-color: rgba(16, 185, 129, 0.35);
}

.au-badge--danger {
  color: #fca5a5;
  background: rgba(239, 68, 68, 0.18);
  border-color: rgba(248, 113, 113, 0.45);
}

.is-light .au-badge--danger {
  color: #b91c1c;
  background: rgba(254, 226, 226, 0.95);
  border-color: rgba(239, 68, 68, 0.35);
}

.au-badge--health {
  color: #6ee7b7;
  background: rgba(16, 185, 129, 0.12);
  border-color: rgba(52, 211, 153, 0.35);
}

.is-light .au-badge--health {
  color: #047857;
  background: rgba(236, 253, 245, 0.95);
}

.au-badge--warn {
  color: #fdba74;
  background: rgba(249, 115, 22, 0.18);
  border-color: rgba(251, 146, 60, 0.5);
}

.is-light .au-badge--warn {
  color: #c2410c;
  background: rgba(255, 237, 213, 0.95);
  border-color: rgba(249, 115, 22, 0.35);
}

.au-badge--muted {
  color: var(--au-muted);
  background: rgba(100, 116, 139, 0.15);
  border-color: var(--au-border);
}

.user-card-actions {
  flex-shrink: 0;
  margin-left: auto;
}

.user-card-fields {
  margin: 0;
  padding: 16px 18px 18px;
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 16px 22px;
}

.field-item {
  margin: 0;
  min-width: 0;
}

.field-item--full {
  grid-column: 1 / -1;
}

.field-label {
  margin: 0 0 6px;
  font-size: 11px;
  font-weight: 650;
  letter-spacing: 0.06em;
  text-transform: uppercase;
  color: var(--au-label);
}

.field-value {
  margin: 0;
  font-size: 14px;
  line-height: 1.5;
  color: var(--au-text-secondary);
  word-break: break-word;
}

.field-value--empty {
  color: var(--au-muted);
}

.field-value--mono {
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
  font-size: 13px;
}

.field-value--hot {
  color: #fb923c;
  font-weight: 700;
}

.is-light .field-value--hot {
  color: #ea580c;
}

.admin-disable-modal__desc {
  margin: 0 0 12px;
  font-size: 14px;
  line-height: 1.55;
  color: var(--au-text-secondary, #cbd5e1);
}

.admin-disable-modal__hours {
  align-items: center;
  gap: 8px;
  margin-top: 8px;
}

.admin-disable-modal__label,
.admin-disable-modal__unit {
  font-size: 13px;
  color: var(--au-muted, #94a3b8);
}

.admin-users-footer {
  margin-top: 18px;
  display: flex;
  justify-content: flex-end;
}

.admin-users-panel :deep(.arco-pagination) {
  color: var(--au-text-secondary);
}

.admin-users-panel :deep(.arco-pagination-item) {
  background: var(--au-card);
  border-color: var(--au-border);
  color: var(--au-text-secondary);
}

.admin-users-panel :deep(.arco-pagination-item-active) {
  background: var(--au-teal-dim);
  border-color: rgba(52, 211, 153, 0.5);
  color: var(--au-teal);
}

.admin-users-panel :deep(.arco-select-view-single) {
  background: var(--au-card);
  border-color: var(--au-border);
  color: var(--au-text-secondary);
}

@media (max-width: 1100px) {
  .user-card-fields {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 720px) {
  .admin-users-stats {
    grid-template-columns: 1fr;
  }

  .admin-users-toolbar {
    flex-direction: column;
    align-items: stretch;
  }

  .user-card-fields {
    grid-template-columns: 1fr;
  }

  .user-card-actions {
    width: 100%;
    margin-left: 0;
  }
}
</style>
