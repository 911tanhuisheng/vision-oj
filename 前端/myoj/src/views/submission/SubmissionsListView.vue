<script setup lang="ts">
import {computed, nextTick, onBeforeUnmount, ref, watch} from 'vue'
import {Message} from '@arco-design/web-vue'
import {RouterLink, useRoute, useRouter} from 'vue-router'
import {getBackendErrorMessage} from '@/api/httpError'
import {isResultSuccess} from '@/api/result'
import {mapApiError} from '@/api/mapApiError'
import {ApiError, QuestionSubmitControllerService, Service} from '@generated'
import {useAuthStore} from '@/stores/auth'

type SubmitRow = {
  /** 数据库提交主键，仅管理员全站列表返回 */
  id?: string
  /** 当前用户在该题（或「全部」页下为账号全局）的第几次提交，从 1 起 */
  submitNo?: number
  /** 与后端 VO 一致：账号全局次序（列表与 status 接口均返回） */
  globalSubmitNo?: number
  /** 竞赛提交时带上竞赛 id；练习提交无此字段 */
  contestId?: string
  questionId?: string
  questionTitle?: string
  userId?: string
  language?: string
  status?: number
  judgeInfo?: string
  createTime?: string
}

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const loading = ref(false)
const rows = ref<SubmitRow[]>([])
const total = ref(0)
const current = ref(1)
const pageSize = ref(15)

/** 题目 id -> 标题（按需拉取缓存） */
const questionTitleCache = ref<Record<string, string>>({})

/** 筛选：提交次序（账号下按时间全局第几次），与 URL query `order` 同步 */
const filterSubmitNo = ref('')

/** 刚提交代码后由 sessionStorage 注入，仅用于高亮与轮询，不写入筛选框、不自动筛题目 */
const pendingPollQuestionId = ref('')
/** 本题第几次，用于 GET /status/my */
const pendingPollSubmitNo = ref('')
/** 全站次序，与列表行 submitNo 一致，用于匹配行并就地更新状态 */
const pendingGlobalSubmitNo = ref('')

const PENDING_SUBMIT_SESSION_KEY = 'oj:pendingSubmitHighlight'

/** 旧链接：URL 中仍带 snowflake `submitId` 时，用数据库主键轮询（兼容书签） */
const highlightDbSubmitId = ref('')

/** 判题结束后短暂脉冲高亮对应行（与 submitNo 对齐） */
const flashAfterJudgeGlobalNo = ref('')

/** 0 等待 1 判题中 2 成功 3 失败 */
const STATUS_WAITING = 0
const STATUS_RUNNING = 1
const STATUS_DONE_OK = 2
const STATUS_DONE_FAIL = 3

/** 轻量 status 接口轮询间隔（要能看见 0→1→2 需快于判题耗时） */
const STATUS_POLL_MS = 400
/** 列表全量同步间隔（补全题目名等，非主状态来源） */
const LIST_SYNC_MS = 1600
const POLL_TIMEOUT_MS = 120_000

/** 轮询期间用 status 接口刷新的实时状态（写回高亮行，避免列表 1s 才跳一次） */
const pendingLiveStatus = ref<number | null>(null)
const pendingLiveJudgeInfo = ref<string | undefined>(undefined)

/** 防止 watch 因 router.replace 二次触发导致轮询被意外中断 */
let pollingActive = false

let submitPollAbort: AbortController | null = null

function delay(ms: number) {
  return new Promise<void>((resolve) => {
    setTimeout(resolve, ms)
  })
}

/** 管理员：全站记录（须在 applyCurrentPage 之上声明，避免引用顺序问题） */
const adminScope = ref(false)
const adminFilterUserId = ref('')
const adminFilterQuestionId = ref('')

const totalPages = computed(() => Math.max(1, Math.ceil(total.value / pageSize.value)))

const showAdminPanel = computed(() => auth.isAdmin === true)

/**
 * 刚交卷进入列表时：数据按时间升序分页，新提交几乎总在最后一页。
 * 若始终把 current=1，表格里没有对应「全此次序」的行，轮询永远无法就地更新 —— 必须先翻到正确页。
 */
function applyCurrentPageForPendingOrDefault() {
  const ord = filterSubmitNo.value.trim()
  if (ord.length > 0 && /^\d+$/.test(ord)) {
    current.value = 1
    return
  }
  if (showAdminPanel.value && adminScope.value) {
    current.value = 1
    return
  }
  const g = pendingGlobalSubmitNo.value
  if (g && /^\d+$/.test(g)) {
    current.value = Math.max(1, Math.ceil(Number(g) / pageSize.value))
    return
  }
  current.value = 1
}

function parseJudgeInfo(raw?: string): { message?: string; time?: number; memory?: number; detail?: string } {
  if (!raw || raw === '{}') return {}
  try {
    const o = JSON.parse(raw) as { message?: string; time?: number; memory?: number }
    if (o && typeof o === 'object' && typeof o.message === 'string') {
      o.message = normalizeOjMessage(o.message)
    }
    return o && typeof o === 'object' ? o : {}
  } catch {
    return {}
  }
}

function normalizeOjMessage(message?: string): string | undefined {
  const msg = (message || '').trim()
  if (!msg) return undefined
  if (msg === '成功') return 'Accepted'
  if (msg === '答案错误') return 'Wrong Answer'
  if (msg === '编译错误') return 'Compile Error'
  if (msg === '超时') return 'Time Limit Exceeded'
  if (msg === '内存溢出' || msg === '内存超限') return 'Memory Limit Exceeded'
  if (msg === '运行错误') return 'Runtime Error'
  if (msg === '输出溢出' || msg === '输出超限') return 'Output Limit Exceeded'
  if (msg === '危险操作') return 'Dangerous Operation'
  if (msg === '展示错误' || msg === '格式错误') return 'Presentation Error'
  if (msg === '系统错误') return 'System Error'
  return msg
}

function statusLabel(status?: number): string {
  if (status === STATUS_WAITING) return '等待判题'
  if (status === STATUS_RUNNING) return '判题中'
  if (status === STATUS_DONE_OK) return '已完成'
  if (status === STATUS_DONE_FAIL) return '已完成'
  return '-'
}

function matchPendingRow(row: SubmitRow): boolean {
  const g = pendingGlobalSubmitNo.value
  if (!g) return false
  return String(row.submitNo ?? '') === g || String(row.globalSubmitNo ?? '') === g
}

/** 高亮行优先展示 status 接口的最新状态 */
function displayStatus(row: SubmitRow): number | undefined {
  if (matchPendingRow(row) && pendingLiveStatus.value != null) {
    return pendingLiveStatus.value
  }
  return row.status
}

function judgeInfoForRow(row: SubmitRow): string | undefined {
  if (matchPendingRow(row) && pendingLiveJudgeInfo.value != null) {
    return pendingLiveJudgeInfo.value
  }
  return row.judgeInfo
}

function statusLabelForRow(row: SubmitRow): string {
  return statusLabel(displayStatus(row))
}

function resultLabel(row: SubmitRow): string {
  const st = displayStatus(row)
  const ji = parseJudgeInfo(judgeInfoForRow(row))
  if (ji.message) return ji.message
  if (st === STATUS_WAITING) return '等待判题'
  if (st === STATUS_RUNNING) return '判题中'
  if (st === STATUS_DONE_OK) return '通过'
  if (st === STATUS_DONE_FAIL) return '未通过'
  return '-'
}

function resultTone(row: SubmitRow): 'ac' | 'wa' | 'pend' | 'fail' | 'neutral' {
  const msg = (parseJudgeInfo(judgeInfoForRow(row)).message || '').toLowerCase()
  const st = displayStatus(row)
  if (st === STATUS_WAITING || st === STATUS_RUNNING) return 'pend'
  if (msg.includes('accept')) return 'ac'
  if (st === STATUS_DONE_OK && !parseJudgeInfo(judgeInfoForRow(row)).message) return 'ac'
  if (msg.includes('compile')) return 'fail'
  if (st === STATUS_DONE_FAIL || msg.includes('wrong') || msg.includes('error') || msg.includes('limit'))
    return 'wa'
  if (st === STATUS_DONE_OK) return 'ac'
  return 'neutral'
}

function formatTimeMem(row: SubmitRow): string {
  const ji = parseJudgeInfo(row.judgeInfo)
  const parts: string[] = []
  if (ji.time != null && ji.time !== undefined) parts.push(`${ji.time} ms`)
  if (ji.memory != null && ji.memory !== undefined) parts.push(`${ji.memory} KB`)
  return parts.length ? parts.join(' / ') : '—'
}

function formatDateTime(raw?: string): string {
  if (!raw) return '-'
  const d = new Date(raw)
  if (Number.isNaN(d.getTime())) return raw
  return d
    .toLocaleString('zh-CN', {
      year: 'numeric',
      month: '2-digit',
      day: '2-digit',
      hour: '2-digit',
      minute: '2-digit',
      second: '2-digit',
      hour12: false,
    })
    .replace(/\//g, '-')
}

function syncQueryFromFilter() {
  const q = { ...route.query } as Record<string, string | string[] | undefined>
  delete q.problemId
  const ord = filterSubmitNo.value.trim()
  if (ord && /^\d+$/.test(ord)) {
    q.order = ord
  } else {
    delete q.order
  }
  void router.replace({ path: route.path, query: q })
}

function applyFilter() {
  current.value = 1
  syncQueryFromFilter()
  void fetchList()
}

function onPrevPage() {
  if (current.value <= 1) return
  current.value -= 1
  void fetchList()
}

function onNextPage() {
  if (current.value >= totalPages.value) return
  current.value += 1
  void fetchList()
}

function onAdminApply() {
  current.value = 1
  void fetchList()
}

async function hydrateQuestionTitles(ids: (string | undefined)[]) {
  const unique = [...new Set(ids.map((id) => String(id || '').trim()).filter((id) => /^\d+$/.test(id)))]
  const missing = unique.filter((id) => !questionTitleCache.value[id])
  if (missing.length === 0) return
  await Promise.all(
    missing.map(async (id) => {
      try {
        const data = await Service.getPublicQuestionDetail(id)
        if (isResultSuccess(data.code) && data.data?.title) {
          questionTitleCache.value = { ...questionTitleCache.value, [id]: data.data.title }
        }
      } catch {
        /* 忽略单题标题失败 */
      }
    }),
  )
}

async function fetchList(opts?: { silent?: boolean }) {
  const silent = opts?.silent === true
  const token = auth.token?.trim()
  if (!token) {
    Message.warning('请先登录后查看提交记录')
    return
  }

  if (!silent) loading.value = true
  try {
    let data
    if (showAdminPanel.value && adminScope.value) {
      const uid = adminFilterUserId.value.trim()
      const qid = adminFilterQuestionId.value.trim()
      const ord = filterSubmitNo.value.trim()
      let userIdFilter: string | undefined
      let questionIdFilter: string | undefined
      let submitNoFilter: number | undefined
      if (/^[1-9]\d*$/.test(uid)) userIdFilter = uid
      if (/^[1-9]\d*$/.test(qid)) questionIdFilter = qid
      if (userIdFilter != null && questionIdFilter != null && /^\d+$/.test(ord)) {
        submitNoFilter = Number(ord)
      }
      data = await QuestionSubmitControllerService.pageAllQuestionSubmitsForAdmin(
        current.value,
        pageSize.value,
        userIdFilter,
        questionIdFilter,
        submitNoFilter,
      )
    } else {
      const ord = filterSubmitNo.value.trim()
      const hasOrder = /^\d+$/.test(ord)
      data = await QuestionSubmitControllerService.pageMyAllQuestionSubmits(
        hasOrder ? Number(ord) : undefined,
        current.value,
        pageSize.value,
      )
    }
    if (!isResultSuccess(data.code)) {
      Message.error(data.message || '获取提交记录失败')
      return
    }
    const page = data.data
    rows.value = (page?.records as SubmitRow[] | undefined) ?? []
    total.value = page?.total ?? 0
    current.value = page?.current ?? current.value
    pageSize.value = page?.size ?? pageSize.value

    // 列表定时同步也更新当前提交的实时状态，作为轻量 status 接口的兜底。
    const pendingRow = rows.value.find((row) => matchPendingRow(row))
    if (pendingRow) {
      if (pendingRow.status != null) pendingLiveStatus.value = pendingRow.status
      if (pendingRow.judgeInfo != null) pendingLiveJudgeInfo.value = pendingRow.judgeInfo
    }

    await hydrateQuestionTitles(rows.value.filter((r) => !r.questionTitle).map((r) => r.questionId))
  } catch (error) {
    if (error instanceof ApiError && error.status === 401) {
      Message.error('登录已失效，请重新登录')
      return
    }
    Message.error(mapApiError(error, getBackendErrorMessage(error, '获取提交记录失败')))
  } finally {
    if (!silent) loading.value = false
  }
}

async function ensurePendingSubmitRowOnPage(): Promise<void> {
  const g = pendingGlobalSubmitNo.value
  if (!g || !/^\d+$/.test(g)) return
  if (showAdminPanel.value && adminScope.value) return
  const target = Math.max(1, Math.ceil(Number(g) / pageSize.value))
  if (current.value !== target) {
    current.value = target
    await fetchList({ silent: true })
  }
}

/** 判题结束后静默重新拉取列表，必要时翻到包含该条的页并滚动、短时强调该行 */
async function refreshAfterJudgeTerminal(savedGlobalNo: string, savedDbSubmitId: string) {
  const ordFilter = filterSubmitNo.value.trim()
  const filtered = ordFilter.length > 0 && /^\d+$/.test(ordFilter)
  const onMyList = !(showAdminPanel.value && adminScope.value)

  if (!filtered && onMyList && savedGlobalNo && /^\d+$/.test(savedGlobalNo)) {
    const sn = Number(savedGlobalNo)
    flashAfterJudgeGlobalNo.value = savedGlobalNo
    window.setTimeout(() => {
      flashAfterJudgeGlobalNo.value = ''
    }, 2800)
    current.value = Math.max(1, Math.ceil(sn / pageSize.value))
  }

  await fetchList({ silent: true })

  await nextTick()
  if (savedGlobalNo && /^\d+$/.test(savedGlobalNo)) {
    const hit = rows.value.find(
      (r) =>
        String(r.submitNo ?? '') === savedGlobalNo ||
        String(r.globalSubmitNo ?? '') === savedGlobalNo,
    )
    if (hit) {
      const key = rowDomKey(hit)
      document
        .querySelector<HTMLElement>(`.submit-table [data-submit-row="${key}"]`)
        ?.scrollIntoView({ behavior: 'smooth', block: 'center' })
      return
    }
  }
  if (savedDbSubmitId && /^\d+$/.test(savedDbSubmitId)) {
    document
      .querySelector<HTMLElement>(`.submit-table [data-submit-row="${savedDbSubmitId}"]`)
      ?.scrollIntoView({ behavior: 'smooth', block: 'center' })
  }
}

function isRowFlash(row: SubmitRow): boolean {
  if (flashAfterJudgeGlobalNo.value === '') return false
  const k = flashAfterJudgeGlobalNo.value
  return String(row.submitNo ?? '') === k || String(row.globalSubmitNo ?? '') === k
}

function consumePendingSubmitFromSession() {
  if (typeof sessionStorage === 'undefined') return
  const raw = sessionStorage.getItem(PENDING_SUBMIT_SESSION_KEY)
  if (!raw) return
  sessionStorage.removeItem(PENDING_SUBMIT_SESSION_KEY)
  try {
    const o = JSON.parse(raw) as { questionId?: string; submitNo?: string; globalSubmitNo?: string; t?: number }
    const qid = String(o.questionId || '').trim()
    const sn = String(o.submitNo || '').trim()
    const gsn = String(o.globalSubmitNo || '').trim()
    const t = typeof o.t === 'number' ? o.t : 0
    if (!/^\d+$/.test(qid) || !/^\d+$/.test(sn) || !/^\d+$/.test(gsn)) return
    if (Date.now() - t > 10 * 60 * 1000) return
    pendingPollQuestionId.value = qid
    pendingPollSubmitNo.value = sn
    pendingGlobalSubmitNo.value = gsn
    pendingLiveStatus.value = STATUS_WAITING
    pendingLiveJudgeInfo.value = '{}'
  } catch {
    /* ignore */
  }
}

function initFromRouteQuery() {
  const ord = route.query.order
  const ord0 = Array.isArray(ord) ? ord[0] : ord
  if (ord0 && /^\d+$/.test(String(ord0))) {
    filterSubmitNo.value = String(ord0)
  } else {
    filterSubmitNo.value = ''
  }

  const sid = route.query.submitId
  const ss = Array.isArray(sid) ? sid[0] : sid
  if (ss && /^\d+$/.test(String(ss))) {
    highlightDbSubmitId.value = String(ss)
  } else {
    highlightDbSubmitId.value = ''
  }

  const fromSubmit = route.query.fromSubmit
  const fs = Array.isArray(fromSubmit) ? fromSubmit[0] : fromSubmit
  const hasLegacyProblemId = route.query.problemId != null
  if (fs === '1' || fs === 'true' || hasLegacyProblemId) {
    nextTick(() => {
      const q = { ...route.query } as Record<string, string | string[] | undefined>
      delete q.fromSubmit
      delete q.problemId
      void router.replace({ path: route.path, query: q })
    })
  }
}

function clearLegacySubmitIdFromUrl() {
  const q = { ...route.query } as Record<string, string | string[] | undefined>
  delete q.submitId
  void router.replace({ path: route.path, query: q })
}

function clearPendingPoll() {
  pendingPollQuestionId.value = ''
  pendingPollSubmitNo.value = ''
  pendingGlobalSubmitNo.value = ''
  pendingLiveStatus.value = null
  pendingLiveJudgeInfo.value = undefined
}

function applyStatusSnapshot(vo: {
  status?: number
  judgeInfo?: string
  language?: string
  createTime?: string
  questionId?: string
  submitNo?: number
  globalSubmitNo?: number
}) {
  if (vo.status != null) pendingLiveStatus.value = vo.status
  if (vo.judgeInfo != null) pendingLiveJudgeInfo.value = vo.judgeInfo

  const g = pendingGlobalSubmitNo.value
  if (!g) return
  const idx = rows.value.findIndex((r) => matchPendingRow(r))
  const patch: SubmitRow = {
    questionId: vo.questionId != null ? String(vo.questionId) : pendingPollQuestionId.value,
    submitNo: vo.submitNo ?? Number(g),
    globalSubmitNo: vo.globalSubmitNo ?? Number(g),
    status: vo.status,
    judgeInfo: vo.judgeInfo,
    language: vo.language,
    createTime: vo.createTime,
  }
  if (idx >= 0) {
    rows.value[idx] = { ...rows.value[idx], ...patch }
  } else {
    rows.value = [...rows.value, patch]
  }
}

async function fetchSubmitStatusSnapshot(): Promise<number | null> {
  const qid = pendingPollQuestionId.value
  const sn = pendingPollSubmitNo.value
  if (!/^\d+$/.test(qid) || !/^\d+$/.test(sn)) return null
  try {
    const parsed = await QuestionSubmitControllerService.getSubmitStatusBySubmitNo(
      qid,
      Number(sn),
    )
    if (!isResultSuccess(parsed.code) || !parsed.data) return pendingLiveStatus.value
    applyStatusSnapshot(parsed.data)
    return parsed.data.status ?? null
  } catch (e) {
    if (e instanceof ApiError && e.status === 401) return null
    return pendingLiveStatus.value
  }
}

function rowDomKey(row: SubmitRow): string {
  if (row.id) return row.id
  const q = row.questionId ?? ''
  const n = row.submitNo ?? ''
  return `${q}-${n}`
}

function isRowHighlighted(row: SubmitRow): boolean {
  if (pendingGlobalSubmitNo.value) {
    return String(row.submitNo ?? '') === pendingGlobalSubmitNo.value
  }
  if (highlightDbSubmitId.value && row.id) {
    return row.id === highlightDbSubmitId.value
  }
  return false
}

const showJudgePollingBanner = computed(
  () =>
    Boolean(
      pendingPollQuestionId.value && pendingPollSubmitNo.value && pendingGlobalSubmitNo.value,
    ),
)

const judgePollBannerText = computed(() => {
  const st = pendingLiveStatus.value
  if (st === STATUS_RUNNING) return '正在判题中，请稍候…'
  if (st === STATUS_WAITING) return '已提交，等待进入判题队列…'
  if (st === STATUS_DONE_OK || st === STATUS_DONE_FAIL) return '判题已完成，正在同步结果…'
  return '正在跟进本次提交的评测状态…'
})

function isRowJudging(row: SubmitRow): boolean {
  return matchPendingRow(row) && displayStatus(row) === STATUS_RUNNING
}

/** 高亮行滚入视口，并对该条后台轮询直到判题结束 */
function afterListLoadedForSubmit() {
  const hasPending = Boolean(
    pendingPollQuestionId.value && pendingPollSubmitNo.value && pendingGlobalSubmitNo.value,
  )
  if (!hasPending && !highlightDbSubmitId.value) return

  nextTick(() => {
    const key = highlightDbSubmitId.value
      ? highlightDbSubmitId.value
      : hasPending
        ? `${pendingPollQuestionId.value}-${pendingGlobalSubmitNo.value}`
        : ''
    if (!key) return
    const el = document.querySelector<HTMLElement>(`.submit-table [data-submit-row="${key}"]`)
    el?.scrollIntoView({ behavior: 'smooth', block: 'center' })
  })

  void pollSubmitUntilTerminal()
}

/**
 * 判题跟进：高频轮询 GET /status/my 更新高亮行（能看见 0→1→2）；
 * 列表接口较低频同步，用于翻页与补全题目标题。
 */
async function pollSubmitUntilTerminal() {
  // 防止重复启动轮询
  if (pollingActive) {
    submitPollAbort?.abort()
  }
  pollingActive = true
  submitPollAbort?.abort()
  submitPollAbort = new AbortController()
  const signal = submitPollAbort.signal
  const token = auth.token?.trim()
  if (!token) {
    pollingActive = false
    return
  }

  const savedGlobal = pendingGlobalSubmitNo.value
  const savedPerQ = pendingPollSubmitNo.value
  const savedQid = pendingPollQuestionId.value
  const legacyDbId = highlightDbSubmitId.value

  const pendingChainOk =
    Boolean(savedGlobal && /^\d+$/.test(savedGlobal)) &&
    Boolean(savedPerQ && /^\d+$/.test(savedPerQ)) &&
    Boolean(savedQid && /^\d+$/.test(savedQid))

  if (!pendingChainOk && !legacyDbId) {
    clearLegacySubmitIdFromUrl()
    clearPendingPoll()
    pollingActive = false
    return
  }

  await ensurePendingSubmitRowOnPage()
  await fetchList({ silent: true })
  await fetchSubmitStatusSnapshot()

  const start = Date.now()
  let lastListSync = Date.now()

  while (Date.now() - start < POLL_TIMEOUT_MS) {
    if (signal.aborted) return

    if (pendingChainOk) {
      const st = await fetchSubmitStatusSnapshot()
      if (st === STATUS_DONE_OK || st === STATUS_DONE_FAIL) {
        const row = rows.value.find((r) => matchPendingRow(r))
        const msg = parseJudgeInfo(judgeInfoForRow(row ?? {})).message
        if (st === STATUS_DONE_OK) {
          Message.success(msg ? `判题完成：${msg}` : '判题通过')
        } else {
          Message.warning(msg ? `判题完成：${msg}` : '判题未通过')
        }
        clearLegacySubmitIdFromUrl()
        clearPendingPoll()
        highlightDbSubmitId.value = ''
        pollingActive = false
        await fetchList({ silent: true })
        await refreshAfterJudgeTerminal(savedGlobal, '')
        return
      }
    }

    if (Date.now() - lastListSync >= LIST_SYNC_MS) {
      await fetchList({ silent: true })
      await fetchSubmitStatusSnapshot()
      if (!rows.value.some((r) => matchPendingRow(r))) {
        await ensurePendingSubmitRowOnPage()
        await fetchList({ silent: true })
        await fetchSubmitStatusSnapshot()
      }
      lastListSync = Date.now()
    }

    if (legacyDbId) {
      try {
        if (signal.aborted) return
        const parsed = await QuestionSubmitControllerService.getSubmitStatus(legacyDbId)
        if (signal.aborted) return
        if (isResultSuccess(parsed.code)) {
          const st = parsed.data?.status
          const ji = parsed.data?.judgeInfo
          if (st === STATUS_DONE_OK || st === STATUS_DONE_FAIL) {
            const msg = parseJudgeInfo(ji).message
            if (st === STATUS_DONE_OK) {
              Message.success(msg ? `判题完成：${msg}` : '判题通过')
            } else {
              Message.warning(msg ? `判题完成：${msg}` : '判题未通过')
            }
            const snapG = pendingGlobalSubmitNo.value || savedGlobal
            clearLegacySubmitIdFromUrl()
            clearPendingPoll()
            highlightDbSubmitId.value = ''
            pollingActive = false
            await refreshAfterJudgeTerminal(snapG, legacyDbId)
            return
          }
        }
      } catch (e) {
        if (e instanceof ApiError && e.status === 401) return
        if (signal.aborted) return
      }
    }

    await delay(STATUS_POLL_MS)
  }

  Message.warning('判题等待较久，已为你同步列表')
  const endGlobal = pendingGlobalSubmitNo.value || savedGlobal
  const endDb = highlightDbSubmitId.value || legacyDbId
  clearLegacySubmitIdFromUrl()
  clearPendingPoll()
  highlightDbSubmitId.value = ''
  pollingActive = false
  await refreshAfterJudgeTerminal(endGlobal, endDb)
}

async function loadListAndHandleSubmitHighlight() {
  await fetchList()
  if (
    highlightDbSubmitId.value &&
    showAdminPanel.value &&
    adminScope.value &&
    !rows.value.some((r) => r.id === highlightDbSubmitId.value)
  ) {
    await fetchList()
  }
  afterListLoadedForSubmit()
}

watch(
  () => route.fullPath,
  () => {
    if (route.name !== 'submissions') return
    consumePendingSubmitFromSession()
    initFromRouteQuery()
    // 如果已有轮询在进行中（通常因 router.replace 去除 fromSubmit 导致二次触发），
    // 跳过重复的列表拉取与轮询启动，避免当前轮询被意外中断
    if (pollingActive) return
    applyCurrentPageForPendingOrDefault()
    // 提前标记为 active，防止 router.replace 二次触发 watch 时重复初始化
    const hasPending = Boolean(
      pendingPollQuestionId.value && pendingPollSubmitNo.value && pendingGlobalSubmitNo.value,
    )
    if (hasPending || highlightDbSubmitId.value) {
      pollingActive = true
    }
    void loadListAndHandleSubmitHighlight()
  },
  { immediate: true },
)

watch(adminScope, () => {
  current.value = 1
  void fetchList()
})

onBeforeUnmount(() => {
  pollingActive = false
  submitPollAbort?.abort()
  submitPollAbort = null
  clearPendingPoll()
})
</script>

<template>
  <div class="submissions-page">
    <section class="hero panel">
      <div>
        <p class="eyebrow">Submission Log</p>
        <h1>提交记录</h1>
        <p class="lead">
          查看评测状态、运行时间与内存占用，风格参考常见 OJ 提交列表。
        </p>
      </div>
      <div class="hero-meta">
        <span class="meta-chip">共 {{ total }} 条</span>
        <span class="meta-chip">每页 {{ pageSize }} 条</span>
        <span class="meta-chip">{{ loading ? '加载中' : '已同步' }}</span>
      </div>
    </section>

    <section class="panel table-shell">
      <div
        v-if="showJudgePollingBanner"
        class="judge-poll-banner"
        :class="{ 'judge-poll-banner--running': pendingLiveStatus === STATUS_RUNNING }"
        role="status"
      >
        <span class="judge-poll-banner__dot" aria-hidden="true" />
        {{ judgePollBannerText }}
        <span v-if="pendingLiveStatus != null" class="judge-poll-banner__phase">
          （{{ statusLabel(pendingLiveStatus) }}）
        </span>
      </div>
      <div class="filter-row">
        <a-input
          v-model="filterSubmitNo"
          allow-clear
          placeholder="次序 1、2、3…（本人在全站的第几次提交，留空表示全部）"
          :disabled="showAdminPanel && adminScope"
        />
        <a-button type="primary" class="query-btn query-btn--primary" :loading="loading" @click="applyFilter">
          筛选
        </a-button>
      </div>

      <div v-if="showAdminPanel" class="admin-row">
        <label class="admin-toggle">
          <input v-model="adminScope" type="checkbox" />
          <span>管理员：查看全站提交</span>
        </label>
        <template v-if="adminScope">
          <a-input v-model="adminFilterUserId" allow-clear placeholder="用户 ID（可选）" class="admin-input" />
          <a-input v-model="adminFilterQuestionId" allow-clear placeholder="题目 ID（可选）" class="admin-input" />
          <a-input
            v-model="filterSubmitNo"
            allow-clear
            placeholder="次序（须同时填用户+题目）"
            class="admin-input"
          />
          <a-button type="outline" :loading="loading" @click="onAdminApply">应用</a-button>
        </template>
      </div>

      <div class="table-wrap">
        <table class="submit-table">
          <thead>
            <tr>
              <th>次序</th>
              <th>题目</th>
              <th v-if="showAdminPanel && adminScope">用户</th>
              <th>语言</th>
              <th>状态</th>
              <th>结果</th>
              <th>时间 / 内存</th>
              <th>提交时间</th>
            </tr>
          </thead>
          <tbody>
            <tr v-if="loading && rows.length === 0">
              <td :colspan="showAdminPanel && adminScope ? 8 : 7" class="empty-cell">加载中...</td>
            </tr>
            <tr v-else-if="rows.length === 0">
              <td :colspan="showAdminPanel && adminScope ? 8 : 7" class="empty-cell">暂无提交记录</td>
            </tr>
            <tr
              v-for="(row, i) in rows"
              v-else
              :key="rowDomKey(row) || i"
              :data-submit-row="rowDomKey(row)"
              :class="{
                'row--highlight': isRowHighlighted(row),
                'row--judging': isRowJudging(row),
                'row--result-flash': isRowFlash(row),
              }"
            >
              <td class="mono">
                <span>第 {{ row.submitNo ?? '-' }} 次</span>
                <span v-if="showAdminPanel && adminScope && row.id" class="muted id-hint">#{{ row.id }}</span>
              </td>
              <td class="title-cell">
                <template v-if="row.questionId && /^\d+$/.test(row.questionId)">
                  <RouterLink :to="{ name: 'problem-solve', params: { id: row.questionId } }" class="problem-link">
                    {{ row.questionTitle || questionTitleCache[row.questionId] || `题目 #${row.questionId}` }}
                  </RouterLink>
                  <span v-if="row.contestId" class="contest-chip" title="该提交计入对应竞赛榜单">比赛</span>
                  <span class="muted id-hint">#{{ row.questionId }}</span>
                </template>
                <span v-else class="muted">-</span>
              </td>
              <td v-if="showAdminPanel && adminScope" class="mono muted">{{ row.userId || '-' }}</td>
              <td>{{ row.language || '-' }}</td>
              <td>
                <span
                  class="status-pill"
                  :class="{ 'status-pill--running': displayStatus(row) === STATUS_RUNNING }"
                >
                  {{ statusLabelForRow(row) }}
                </span>
              </td>
              <td>
                <span class="result-pill" :data-tone="resultTone(row)">{{ resultLabel(row) }}</span>
                <small v-if="parseJudgeInfo(judgeInfoForRow(row)).detail" class="judge-detail">
                  {{ parseJudgeInfo(judgeInfoForRow(row)).detail }}
                </small>
              </td>
              <td class="mono muted">{{ formatTimeMem(row) }}</td>
              <td class="muted">{{ formatDateTime(row.createTime) }}</td>
            </tr>
          </tbody>
        </table>
      </div>

      <div class="pager-row">
        <a-button :disabled="current <= 1 || loading" @click="onPrevPage">上一页</a-button>
        <span>第 {{ current }} / {{ totalPages }} 页</span>
        <a-button :disabled="current >= totalPages || loading" @click="onNextPage">下一页</a-button>
      </div>
    </section>
  </div>
</template>

<style scoped>
.submissions-page {
  position: relative;
  isolation: isolate;
  width: min(1620px, calc(100vw - 30px));
  margin: 0 auto;
  padding: 28px 12px 52px;
}

.submissions-page::before,
.submissions-page::after {
  content: '';
  position: absolute;
  z-index: -1;
  pointer-events: none;
  filter: blur(40px);
  border-radius: 999px;
  opacity: 0.42;
  animation: floatGlow 10s ease-in-out infinite;
}

.submissions-page::before {
  width: 320px;
  height: 320px;
  left: -82px;
  top: 36px;
  background: radial-gradient(circle, color-mix(in srgb, var(--app-accent) 70%, transparent), transparent 72%);
}

.submissions-page::after {
  width: 300px;
  height: 300px;
  right: -60px;
  top: 120px;
  background: radial-gradient(circle, color-mix(in srgb, #7c3aed 55%, transparent), transparent 70%);
  animation-delay: -4s;
}

@keyframes floatGlow {
  0%,
  100% {
    transform: translate(0, 0) scale(1);
  }
  50% {
    transform: translate(12px, -8px) scale(1.05);
  }
}

.panel {
  background: var(--app-panel-bg, color-mix(in srgb, var(--color-bg-2) 88%, transparent));
  border: 1px solid var(--color-border-2);
  border-radius: 16px;
  box-shadow: var(--app-panel-shadow, 0 18px 50px rgba(0, 0, 0, 0.06));
  backdrop-filter: blur(12px);
}

.hero {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-start;
  justify-content: space-between;
  gap: 20px;
  padding: 26px 28px;
  margin-bottom: 20px;
}

.eyebrow {
  font-size: 12px;
  letter-spacing: 0.12em;
  text-transform: uppercase;
  color: var(--color-text-3);
  margin: 0 0 8px;
}

.hero h1 {
  margin: 0 0 10px;
  font-size: clamp(26px, 3vw, 34px);
  font-weight: 700;
  letter-spacing: -0.02em;
}

.lead {
  margin: 0;
  max-width: 640px;
  color: var(--color-text-2);
  line-height: 1.65;
}

.hero-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
}

.meta-chip {
  display: inline-flex;
  align-items: center;
  padding: 6px 12px;
  border-radius: 999px;
  font-size: 12px;
  background: color-mix(in srgb, var(--app-accent) 12%, transparent);
  border: 1px solid color-mix(in srgb, var(--app-accent) 28%, transparent);
  color: var(--color-text-2);
}

.table-shell {
  padding: 22px 24px 28px;
}

.judge-poll-banner {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 14px;
  padding: 10px 14px;
  border-radius: 10px;
  font-size: 13px;
  line-height: 1.45;
  color: var(--color-text-2);
  background: color-mix(in srgb, var(--app-accent) 10%, var(--color-fill-2));
  border: 1px solid color-mix(in srgb, var(--app-accent) 22%, var(--color-border-2));
}

.judge-poll-banner--running {
  border-color: color-mix(in srgb, rgb(var(--primary-6)) 35%, var(--color-border-2));
  background: color-mix(in srgb, rgb(var(--primary-6)) 14%, var(--color-fill-2));
}

.judge-poll-banner__phase {
  margin-left: 4px;
  font-weight: 600;
  color: rgb(var(--primary-6));
}

.judge-poll-banner__dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--app-accent);
  flex-shrink: 0;
  animation: judgePollPulse 1.2s ease-in-out infinite;
}

.judge-poll-banner--running .judge-poll-banner__dot {
  background: rgb(var(--primary-6));
}

@keyframes judgePollPulse {
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

.filter-row {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  margin-bottom: 16px;
  align-items: center;
}

.filter-row :deep(.arco-input-wrapper) {
  flex: 1;
  min-width: 200px;
  max-width: 360px;
}

.query-btn--primary {
  border-radius: 10px;
}

.admin-row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 12px;
  margin-bottom: 18px;
  padding: 12px 14px;
  border-radius: 12px;
  background: color-mix(in srgb, var(--color-fill-2) 80%, transparent);
  border: 1px dashed var(--color-border-2);
}

.admin-toggle {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  color: var(--color-text-2);
  cursor: pointer;
  user-select: none;
}

.admin-input {
  width: 160px;
}

.table-wrap {
  overflow-x: auto;
  border-radius: 12px;
  border: 1px solid var(--color-border-2);
  background: color-mix(in srgb, var(--color-bg-2) 95%, transparent);
}

.submit-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 14px;
  color: var(--color-text-1);
}

.submit-table th,
.submit-table td {
  padding: 12px 14px;
  text-align: left;
  border-bottom: 1px solid var(--color-border-1);
}

.submit-table thead th {
  font-weight: 600;
  color: var(--color-text-2);
  background: color-mix(in srgb, var(--color-fill-2) 65%, transparent);
  white-space: nowrap;
}

.submit-table tbody tr:hover {
  background: color-mix(in srgb, var(--app-accent) 6%, transparent);
}

.submit-table tbody tr.row--highlight {
  outline: 2px solid rgb(var(--primary-6));
  outline-offset: -2px;
  background: color-mix(in srgb, rgb(var(--primary-6)) 12%, transparent);
}

.submit-table tbody tr.row--highlight:hover {
  background: color-mix(in srgb, rgb(var(--primary-6)) 16%, transparent);
}

.submit-table tbody tr.row--judging {
  background: color-mix(in srgb, rgb(var(--primary-6)) 18%, transparent);
}

.submit-table tbody tr.row--judging .status-pill--running {
  color: rgb(var(--primary-6));
  border: 1px solid color-mix(in srgb, rgb(var(--primary-6)) 45%, transparent);
  animation: statusRunningPulse 1.1s ease-in-out infinite;
}

@keyframes statusRunningPulse {
  0%,
  100% {
    opacity: 0.75;
  }
  50% {
    opacity: 1;
  }
}

.submit-table tbody tr.row--result-flash {
  animation: judgeRowFlash 2.6s ease-out 1;
}

@keyframes judgeRowFlash {
  0% {
    background: color-mix(in srgb, rgb(var(--primary-6)) 26%, transparent);
    box-shadow: inset 0 0 0 2px rgb(var(--primary-6));
  }
  45% {
    background: color-mix(in srgb, rgb(var(--primary-6)) 12%, transparent);
  }
  100% {
    background: transparent;
    box-shadow: none;
  }
}

.submit-table tbody tr:nth-child(even) {
  background: color-mix(in srgb, var(--color-fill-1) 72%, transparent);
}

.mono {
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
  font-size: 13px;
}

.muted {
  color: var(--color-text-3);
}

.title-cell {
  min-width: 160px;
}

.problem-link {
  color: rgb(var(--primary-6));
  text-decoration: none;
  font-weight: 500;
}

.problem-link:hover {
  text-decoration: underline;
}

.id-hint {
  display: inline-block;
  margin-left: 8px;
  font-size: 12px;
}

.contest-chip {
  display: inline-block;
  margin-left: 8px;
  padding: 2px 8px;
  border-radius: 6px;
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.04em;
  vertical-align: middle;
  color: rgb(var(--orange-6));
  background: color-mix(in srgb, rgb(var(--orange-6)) 14%, transparent);
  border: 1px solid color-mix(in srgb, rgb(var(--orange-6)) 28%, transparent);
}

.empty-cell {
  text-align: center;
  padding: 28px !important;
  color: var(--color-text-3);
}

.status-pill {
  display: inline-block;
  padding: 2px 8px;
  border-radius: 6px;
  font-size: 12px;
  background: color-mix(in srgb, var(--color-fill-3) 90%, transparent);
  color: var(--color-text-2);
  border: 1px solid color-mix(in srgb, var(--color-border-2) 80%, transparent);
}

.result-pill {
  display: inline-block;
  padding: 3px 10px;
  border-radius: 8px;
  font-size: 13px;
  font-weight: 500;
}

.result-pill[data-tone='ac'] {
  background: color-mix(in srgb, #16a34a 18%, transparent);
  color: #166534;
}

html[data-theme='dark'] .result-pill[data-tone='ac'] {
  color: #86efac;
}

.result-pill[data-tone='wa'],
.result-pill[data-tone='fail'] {
  background: color-mix(in srgb, #dc2626 16%, transparent);
  color: #991b1b;
}

html[data-theme='dark'] .result-pill[data-tone='wa'],
html[data-theme='dark'] .result-pill[data-tone='fail'] {
  color: #fca5a5;
}

.result-pill[data-tone='pend'] {
  background: color-mix(in srgb, #ca8a04 18%, transparent);
  color: #854d0e;
}

html[data-theme='dark'] .result-pill[data-tone='pend'] {
  color: #fde047;
}

.result-pill[data-tone='neutral'] {
  background: color-mix(in srgb, var(--color-fill-3) 90%, transparent);
  color: var(--color-text-2);
}

.judge-detail {
  display: block;
  max-width: 300px;
  margin-top: 5px;
  color: var(--app-text-muted);
  font-size: 11px;
  line-height: 1.4;
  white-space: normal;
}

.pager-row {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 16px;
  margin-top: 20px;
  color: var(--color-text-2);
  font-size: 14px;
}

html[data-theme='dark'] .panel {
  background: linear-gradient(180deg, rgba(16, 24, 39, 0.88), rgba(10, 16, 30, 0.9));
  border-color: rgba(94, 112, 143, 0.35);
  box-shadow: 0 18px 46px rgba(0, 0, 0, 0.42);
}

html[data-theme='dark'] .hero h1 {
  color: #e9f0ff;
}

html[data-theme='dark'] .lead,
html[data-theme='dark'] .pager-row,
html[data-theme='dark'] .admin-toggle {
  color: #b6c4de;
}

html[data-theme='dark'] .meta-chip {
  color: #c4d4f2;
  border-color: rgba(63, 148, 255, 0.45);
  background: rgba(41, 98, 255, 0.18);
}

html[data-theme='dark'] .table-wrap {
  background: rgba(8, 16, 28, 0.86);
  border-color: rgba(94, 112, 143, 0.38);
}

html[data-theme='dark'] .submit-table {
  color: #d6e3ff;
}

html[data-theme='dark'] .submit-table thead th {
  color: #cfe1ff;
  background: rgba(34, 55, 84, 0.82);
  border-bottom-color: rgba(96, 121, 161, 0.45);
}

html[data-theme='dark'] .submit-table td {
  border-bottom-color: rgba(73, 93, 123, 0.38);
}

html[data-theme='dark'] .submit-table tbody tr:nth-child(odd) {
  background: rgba(13, 25, 43, 0.58);
}

html[data-theme='dark'] .submit-table tbody tr:nth-child(even) {
  background: rgba(17, 31, 52, 0.54);
}

html[data-theme='dark'] .submit-table tbody tr:hover {
  background: rgba(28, 57, 97, 0.6);
}

html[data-theme='dark'] .muted,
html[data-theme='dark'] .empty-cell,
html[data-theme='dark'] .id-hint {
  color: #90a5c8;
}

html[data-theme='dark'] .problem-link {
  color: #7dc2ff;
}

html[data-theme='dark'] .status-pill {
  color: #d0dcf4;
  background: rgba(76, 94, 128, 0.3);
  border-color: rgba(118, 136, 168, 0.45);
}

html[data-theme='dark'] .admin-row {
  background: rgba(20, 34, 56, 0.6);
  border-color: rgba(112, 132, 170, 0.4);
}

html[data-theme='dark'] .filter-row :deep(.arco-input-wrapper),
html[data-theme='dark'] .admin-row :deep(.arco-input-wrapper) {
  background: rgba(18, 30, 48, 0.92);
  border-color: rgba(105, 128, 166, 0.48);
}

html[data-theme='dark'] .filter-row :deep(.arco-input),
html[data-theme='dark'] .admin-row :deep(.arco-input) {
  color: #d4e2ff;
}

html[data-theme='dark'] .filter-row :deep(.arco-input::placeholder),
html[data-theme='dark'] .admin-row :deep(.arco-input::placeholder) {
  color: #7f93b8;
}
</style>
