<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { Message } from '@arco-design/web-vue'
import { ApiError, ContestControllerService } from '@generated'
import { isResultSuccess } from '@/api/result'
import { getBackendErrorMessage } from '@/api/httpError'
import { mapApiError } from '@/api/mapApiError'
import { useAuthStore } from '@/stores/auth'

type QuestionBrief = {
  questionId: string
  questionTitle?: string
  fullScore?: number
  sortOrder?: number
}

type ContestDetail = {
  id: string
  title: string
  description?: string
  phase?: string
  startTime?: string
  endTime?: string
  /** 登录用户是否已报名；未登录时后端为 null */
  meRegistered?: boolean | null
  questions?: QuestionBrief[]
}

type RankCell = {
  questionId: string
  score?: number
  time?: number
  memory?: number
  message?: string | null
  penaltyMinutes?: number
  attempts?: number
  totalSubmissions?: number
  cellLabel?: string | null
}

type RankRow = {
  rankOrder?: number
  userId: string
  userName?: string
  solvedCount?: number
  totalPenalty?: number
  totalScore?: number
  totalTime?: number
  totalMemory?: number
  cells?: RankCell[]
}

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

const contestId = computed(() => String(route.params.contestId || '').trim())

const questionCount = computed(() => detail.value?.questions?.length ?? 0)

/** 已结束：保留榜单浏览，禁止报名与从本场进入做题 */
const contestEnded = computed(() => detail.value?.phase === '已结束')

/** 未开始：不可开赛题入口（最后一小时内展示开赛倒计时） */
const contestNotStarted = computed(() => detail.value?.phase === '未开始')

/** 距离开赛的剩余毫秒（仅 phase=未开始时由定时器维护） */
const msUntilStart = ref(0)
let untilStartTimer: ReturnType<typeof setInterval> | null = null

/** 最后一小时内展示大屏开赛倒计时 */
const START_GATE_MS = 60 * 60 * 1000

const showPremiereCountdown = computed(
  () =>
    contestNotStarted.value &&
    msUntilStart.value > 0 &&
    msUntilStart.value <= START_GATE_MS,
)

const premiereCountdownParts = computed(() => {
  const ms = msUntilStart.value
  const totalSec = Math.max(0, Math.ceil(ms / 1000))
  const mm = Math.floor(totalSec / 60)
  const ss = totalSec % 60
  return { mm, ss }
})

function parseContestInstant(v: unknown): number | null {
  if (v == null) return null
  if (typeof v === 'number' && Number.isFinite(v)) return v
  if (typeof v === 'string') {
    const trimmed = v.trim()
    if (!trimmed) return null
    const parsed = Date.parse(trimmed)
    if (!Number.isNaN(parsed)) return parsed
    const n = Number(trimmed)
    if (Number.isFinite(n)) return n
  }
  return null
}

function tickUntilStart() {
  const t = parseContestInstant(detail.value?.startTime)
  if (t == null) {
    msUntilStart.value = 0
    return
  }
  msUntilStart.value = Math.max(0, t - Date.now())
}

function stopUntilStartTimer() {
  if (untilStartTimer != null) {
    clearInterval(untilStartTimer)
    untilStartTimer = null
  }
}

function syncUntilStartTimer() {
  stopUntilStartTimer()
  if (detail.value?.phase !== '未开始') {
    msUntilStart.value = 0
    return
  }
  tickUntilStart()
  untilStartTimer = setInterval(() => {
    const prev = msUntilStart.value
    tickUntilStart()
    if (prev > 0 && msUntilStart.value <= 0) {
      void loadDetail().then(() => {
        void loadRank(false)
        startRankPoll()
      })
    }
  }, 1000)
}

function formatDateTime(iso?: string): string {
  if (!iso) return '—'
  const t = Date.parse(iso)
  if (Number.isNaN(t)) return String(iso)
  return new Intl.DateTimeFormat('zh-CN', {
    year: 'numeric',
    month: 'numeric',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  }).format(new Date(t))
}

function phaseTone(phase?: string): 'live' | 'soon' | 'past' {
  if (phase === '进行中') return 'live'
  if (phase === '未开始') return 'soon'
  return 'past'
}

const detailLoading = ref(false)
const rankLoading = ref(false)
const detail = ref<ContestDetail | null>(null)
const rankRows = ref<RankRow[]>([])
const rankError = ref('')
const joinLoading = ref(false)

const RANK_POLL_MS = 12_000
let rankPollTimer: ReturnType<typeof setInterval> | null = null

function stopRankPoll() {
  if (rankPollTimer != null) {
    clearInterval(rankPollTimer)
    rankPollTimer = null
  }
}

function startRankPoll() {
  stopRankPoll()
  if (detail.value?.phase !== '进行中') return
  rankPollTimer = setInterval(() => {
    void loadRank(true)
  }, RANK_POLL_MS)
}

const columns = computed(() => {
  const qs = detail.value?.questions ?? []
  const base = [
    { title: '排名', dataIndex: 'rankOrder', width: 64, fixed: 'left' as const },
    { title: '选手', dataIndex: 'userName', width: 140, ellipsis: true, tooltip: true },
    { title: '通过', dataIndex: 'solvedCount', width: 56, tooltip: '通过的题目数量' },
    { title: '罚时', dataIndex: 'totalPenalty', width: 72, tooltip: '总罚时（分钟），越少越好' },
  ]
  const dyn = qs.map((q, i) => ({
    title: `第 ${i + 1} 题`,
    dataIndex: `q_${q.questionId}`,
    width: 108,
    ellipsis: true,
    tooltip: '已过：提交次数/本题罚时；未过：-尝试次数',
  }))
  return [...base, ...dyn]
})

const tableData = computed(() => {
  const qs = detail.value?.questions ?? []
  return rankRows.value.map((row) => {
    const flat: Record<string, string | number | undefined | null> = {
      userId: row.userId,
      rankOrder: row.rankOrder,
      userName: row.userName,
      solvedCount: row.solvedCount ?? 0,
      totalPenalty: row.totalPenalty ?? 0,
    }
    for (const q of qs) {
      const cell = cellForRow(row, String(q.questionId))
      flat[`q_${q.questionId}`] = cell?.cellLabel?.trim() ? cell.cellLabel : formatCell(cell)
    }
    return flat
  })
})

function cellForRow(row: RankRow, qid: string): RankCell | undefined {
  return row.cells?.find((c) => String(c.questionId) === qid)
}

function formatCell(c: RankCell | undefined): string {
  if (!c) return '—'
  const sc = c.score ?? 0
  const msg = (c.message || '').trim()
  if (msg === 'Accepted' || msg === '成功') {
    return `${sc} / ${c.time ?? 0}ms`
  }
  if (sc > 0) return String(sc)
  if (msg) return msg.length > 12 ? `${msg.slice(0, 10)}…` : msg
  return '—'
}

async function loadDetail() {
  const id = contestId.value
  if (!/^\d+$/.test(id)) {
    Message.error('比赛信息无效')
    return
  }
  detailLoading.value = true
  try {
    const res = await ContestControllerService.getContest(contestId.value)
    if (!isResultSuccess(res.code)) {
      Message.error(res.message || '加载失败')
      detail.value = null
      return
    }
    detail.value = (res.data as unknown as ContestDetail) ?? null
  } catch (e) {
    Message.error(mapApiError(e, getBackendErrorMessage(e, '加载失败')))
    detail.value = null
  } finally {
    detailLoading.value = false
  }
}

async function loadRank(silent = false) {
  const id = contestId.value
  rankError.value = ''
  if (!/^\d+$/.test(id)) return
  if (!silent) rankLoading.value = true
  try {
    const res = await ContestControllerService.rank(contestId.value)
    if (!isResultSuccess(res.code)) {
      rankRows.value = []
      rankError.value = res.message || '暂时无法加载榜单'
      return
    }
    rankRows.value = Array.isArray(res.data) ? (res.data as unknown as RankRow[]) : []
  } catch (e) {
    rankRows.value = []
    rankError.value = mapApiError(e, getBackendErrorMessage(e, '暂时无法加载榜单'))
  } finally {
    if (!silent) rankLoading.value = false
  }
}

async function joinContest() {
  if (contestNotStarted.value) {
    Message.warning('比赛尚未开始，暂不可报名，请于开赛后再点击报名')
    return
  }
  if (detail.value?.phase === '已结束') {
    Message.warning('比赛已结束，无法报名')
    return
  }
  const id = contestId.value
  if (!auth.token) {
    Message.warning('请先登录后再报名')
    void router.push({ path: '/', query: { openAuth: '1' } })
    return
  }
  joinLoading.value = true
  try {
    const res = await ContestControllerService.join({ contestId: id })
    if (!isResultSuccess(res.code)) {
      Message.error(res.message || '报名失败')
      return
    }
    Message.success('报名成功')
    await loadDetail()
    await loadRank(false)
    startRankPoll()
  } catch (e) {
    if (e instanceof ApiError && e.status === 401) {
      Message.warning('请先登录后再报名')
      void router.push({ path: '/', query: { openAuth: '1' } })
      return
    }
    Message.error(mapApiError(e, getBackendErrorMessage(e, '报名失败')))
  } finally {
    joinLoading.value = false
  }
}

function goSolve(qid: string) {
  if (contestNotStarted.value) {
    Message.warning('比赛尚未开始，暂无法从赛场进入做题。开赛后再点击题目进入即可。')
    return
  }
  if (contestEnded.value) {
    Message.warning('本场比赛已结束，无法从赛场进入做题。如需练习请从题库打开本题。')
    return
  }
  void router.push({
    name: 'problem-solve',
    params: { id: qid },
    query: { contestId: contestId.value },
  })
}

async function refreshAll() {
  await loadDetail()
  await loadRank(false)
  startRankPoll()
}

function goBackToContests() {
  void router.push({ name: 'contests' })
}

onMounted(() => {
  void refreshAll()
})

onBeforeUnmount(() => {
  stopRankPoll()
  stopUntilStartTimer()
})

watch(
  () => [detail.value?.phase, detail.value?.startTime],
  () => {
    syncUntilStartTimer()
  },
  { immediate: true },
)

watch(
  () => route.params.contestId,
  () => {
    stopRankPoll()
    stopUntilStartTimer()
    void refreshAll()
  },
)
</script>

<template>
  <div class="detail-page oj-spotlight">
    <div class="detail-back">
      <button type="button" class="back-btn" @click="goBackToContests">
        <span class="back-arrow">‹</span>
        返回比赛列表
      </button>
    </div>

    <a-spin :loading="detailLoading" class="hero-spin">
      <header v-if="detail" class="hero">
        <div class="hero-main">
          <div class="hero-badges">
            <span class="phase-pill" :class="`phase-pill--${phaseTone(detail.phase)}`">{{ detail.phase }}</span>
            <span v-if="questionCount" class="hero-meta-chip">{{ questionCount }} 道题</span>
            <span v-if="detail.meRegistered === true" class="hero-meta-chip hero-meta-chip--registered">已报名</span>
          </div>
          <span class="hero-title-rule" aria-hidden="true" />
          <h1 class="hero-title">{{ detail.title }}</h1>
          <p class="hero-times">
            <span class="time-chip">
              <span class="time-label">开始</span>
              {{ formatDateTime(detail.startTime) }}
            </span>
            <span class="time-chip">
              <span class="time-label">结束</span>
              {{ formatDateTime(detail.endTime) }}
            </span>
          </p>
        </div>
        <div class="hero-actions">
          <a-button type="outline" size="large" :loading="rankLoading" @click="() => loadRank(false)">刷新榜单</a-button>
          <a-button
            type="primary"
            size="large"
            :loading="joinLoading"
            :disabled="detail.meRegistered === true || contestEnded || contestNotStarted"
            @click="joinContest"
          >
            {{
              contestEnded
                ? '比赛已结束'
                : contestNotStarted
                  ? '开赛后报名'
                  : detail.meRegistered === true
                    ? '已报名'
                    : '报名参赛'
            }}
          </a-button>
        </div>
      </header>
    </a-spin>

    <section
      v-if="detail && showPremiereCountdown"
      class="premiere-gate"
      aria-labelledby="premiere-gate-heading"
    >
      <div class="premiere-gate__grid" aria-hidden="true" />
      <div class="premiere-gate__orbit" aria-hidden="true" />
      <div class="premiere-gate__glow premiere-gate__glow--a" aria-hidden="true" />
      <div class="premiere-gate__glow premiere-gate__glow--b" aria-hidden="true" />

      <div class="premiere-gate__layout">
        <div class="premiere-gate__intro">
          <p id="premiere-gate-heading" class="premiere-gate__badge">
            <span class="premiere-gate__pulse" aria-hidden="true" />
            UPCOMING · 等待开赛
          </p>
          <h2 class="premiere-gate__title">距离开赛 · 最后一小时</h2>
          <p class="premiere-gate__lead">
            本场比赛尚未开始，<strong>暂不可报名</strong>，也<strong>不可从赛场进入题目</strong>。下方倒计时为距开赛时间的剩余时长；开赛即可报名并进入题目作答。
          </p>
        </div>

        <div class="premiere-gate__clock" role="timer" aria-live="polite">
          <div class="premiere-gate__digits">
            <div class="premiere-gate__digit-group">
              <span class="premiere-gate__digit">{{ String(premiereCountdownParts.mm).padStart(2, '0') }}</span>
              <span class="premiere-gate__digit-label">分</span>
            </div>
            <span class="premiere-gate__colon">:</span>
            <div class="premiere-gate__digit-group">
              <span class="premiere-gate__digit">{{ String(premiereCountdownParts.ss).padStart(2, '0') }}</span>
              <span class="premiere-gate__digit-label">秒</span>
            </div>
          </div>
          <p class="premiere-gate__fineprint">开赛后方可报名并使用赛场题目入口</p>
        </div>
      </div>
    </section>

    <a-alert
      v-if="detail && !detailLoading && contestNotStarted && !showPremiereCountdown && msUntilStart > START_GATE_MS"
      type="info"
      class="alert-block"
      title="比赛尚未开始"
    >
      距离开赛仍超过 1 小时，暂不可报名、不可从赛场进入题目。最后一小时内将在上方显示开赛倒计时。
    </a-alert>

    <a-alert v-if="rankError" type="warning" class="alert-block" :content="rankError" />

    <a-alert
      v-if="detail && contestEnded && !detailLoading"
      type="info"
      class="alert-block"
      title="本场比赛已结束"
    >
      您仍可查看最终榜单与赛题列表；题目作答请从<strong>题库</strong>进入同一道题（练习提交）。赛场入口在比赛结束后关闭。
    </a-alert>

    <section v-if="detail && !detailLoading" class="rules-card">
      <h2 class="rules-title">榜单说明</h2>
      <ul class="rules-list">
        <li>仅统计已报名的选手；比赛<strong>开始后方可报名</strong>，请点击右上角「报名参赛」。</li>
        <li>
          排名优先比较<strong>通过的题目数</strong>（每道赛题最多计 1 次通过；同一题多次 AC 不会变成「通过 2 题」），相同再比<strong>总罚时</strong>（分钟）少者优先；仍相同再比<strong>赛题总分</strong>高者优先，再相同按选手编号升序。列表顺序与「排名」列<strong>连续名次</strong>一致。
        </li>
        <li>
          表格中「2 / 125」表示第 2 次提交 AC、本题罚时 125 分钟；「-3」表示已交 3 次尚未通过；「—」表示未提交。<strong>未 AC 时本题罚时通常不计入总罚时</strong>，故可能出现「总罚时相同但一格为 -1、另一格为 —」：仅表示本题尝试情况不同；整榜先后仍由<strong>总分、用户编号</strong>等规则决定，排名列为连续 1、2、3…
        </li>
      </ul>
    </section>

    <div v-if="detail?.description" class="desc-box">{{ detail.description }}</div>

    <a-alert
      v-if="detail && !detailLoading && !questionCount"
      type="info"
      class="alert-block"
      title="本场比赛暂未开放题目"
    >
      题目上线后，您将可以在此进入作答。若长时间如此，请联系站点管理员。
    </a-alert>

    <a-alert
      v-if="detail?.questions?.length && detail && !detailLoading && detail.phase === '进行中'"
      type="warning"
      class="alert-block submit-hint-alert"
      title="成绩怎样才能出现在榜单上？"
    >
      比赛开始后请<strong>先报名</strong>，再点击下面的题目进入做题页。做题页顶部应显示绿色的「<strong>计入本场比赛 · 提交将上榜</strong>」；若显示灰色的「练习提交」，说明本次提交<strong>不会</strong>记入本场比赛（常见于从题库直接进入同一道题）。此时请返回本页重新点题目进入。
    </a-alert>

    <section v-if="detail?.questions?.length" class="problems-section">
      <h2 class="problems-title">
        比赛题目
        <span v-if="contestNotStarted" class="problems-title__muted">（开赛后方可从本场进入）</span>
      </h2>
      <div class="problem-pills">
        <button
          v-for="(q, idx) in detail.questions"
          :key="q.questionId"
          type="button"
          class="problem-pill"
          :class="{ 'problem-pill--disabled': contestEnded || contestNotStarted }"
          :disabled="contestEnded || contestNotStarted"
          @click="goSolve(String(q.questionId))"
        >
          <span class="problem-pill__idx">Q{{ idx + 1 }}</span>
          <span class="problem-pill__name">{{ q.questionTitle || '题目' }}</span>
        </button>
      </div>
    </section>

    <section class="table-section">
      <div class="table-head">
        <h2 class="table-title">实时榜单</h2>
        <span v-if="detail?.phase === '进行中'" class="table-sub">
          比赛进行中：约每 12 秒自动刷新榜单；评测完成后也会同步更新。
        </span>
        <span v-else class="table-sub">提交评测完成后，可点击「刷新榜单」查看最新排名</span>
      </div>
      <a-spin :loading="rankLoading" class="table-spin">
        <p v-if="detail && !rankLoading && !rankError && !tableData.length" class="table-empty-hint">
          榜单上还是空的。报名成功后，从上方题目入口进入做题并提交代码，评测结束后刷新即可看到你的成绩。
        </p>
        <div v-if="!rankError || tableData.length" class="table-shell">
          <a-table
            row-key="userId"
            :columns="columns"
            :data="tableData"
            :pagination="false"
            :scroll="{ x: Math.max(720, 480 + (detail?.questions?.length ?? 0) * 108) }"
            size="medium"
            :bordered="false"
          />
        </div>
      </a-spin>
    </section>
  </div>
</template>

<style scoped>
.detail-page {
  max-width: 1100px;
  margin: 0 auto;
  padding: 16px 20px 48px;
}

.detail-back {
  margin-bottom: 12px;
}

.back-btn {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 8px 4px;
  border: none;
  background: none;
  font-size: 14px;
  font-weight: 600;
  color: var(--color-text-2);
  cursor: pointer;
  border-radius: 8px;
  transition: color 0.15s ease, background 0.15s ease;
}

.back-btn:hover {
  color: var(--app-accent);
  background: color-mix(in srgb, var(--app-accent) 10%, transparent);
}

.back-arrow {
  font-size: 20px;
  line-height: 1;
  opacity: 0.75;
}

.hero-spin {
  display: block;
  width: 100%;
}

.hero {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-start;
  justify-content: space-between;
  gap: 24px;
  padding: 28px 26px;
  margin-bottom: 20px;
  border-radius: 20px;
  background: linear-gradient(
    145deg,
    color-mix(in srgb, var(--app-accent) 16%, var(--color-bg-2)) 0%,
    color-mix(in srgb, var(--app-accent-2) 6%, var(--color-bg-2)) 52%,
    var(--color-bg-1) 100%
  );
  border: 1px solid color-mix(in srgb, var(--app-accent) 22%, var(--color-border-2));
  box-shadow:
    0 8px 32px color-mix(in srgb, var(--color-text-1) 6%, transparent),
    0 0 0 1px color-mix(in srgb, #ffffff 55%, transparent) inset;
}

.hero-main {
  flex: 1;
  min-width: 240px;
}

.hero-badges {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 12px;
}

.phase-pill {
  display: inline-block;
  padding: 4px 12px;
  border-radius: 999px;
  font-size: 12px;
  font-weight: 700;
  letter-spacing: 0.02em;
}

.phase-pill--live {
  background: color-mix(in srgb, var(--app-accent) 20%, transparent);
  color: var(--app-accent);
  border: 1px solid color-mix(in srgb, var(--app-accent) 35%, transparent);
}

.phase-pill--soon {
  background: color-mix(in srgb, rgb(var(--orange-6)) 16%, transparent);
  color: rgb(var(--orange-6));
}

.phase-pill--past {
  background: var(--color-fill-3);
  color: var(--color-text-3);
}

.hero-meta-chip {
  font-size: 12px;
  padding: 4px 10px;
  border-radius: 999px;
  background: var(--color-fill-2);
  color: var(--color-text-3);
  font-weight: 600;
}

.hero-meta-chip--registered {
  background: color-mix(in srgb, rgb(var(--green-6)) 18%, transparent);
  color: rgb(var(--green-6));
  border: 1px solid color-mix(in srgb, rgb(var(--green-6)) 35%, transparent);
}

.hero-title-rule {
  display: block;
  width: min(88px, 22vw);
  height: 2px;
  margin: 2px 0 12px;
  border-radius: 2px;
  background: linear-gradient(
    90deg,
    var(--app-accent),
    color-mix(in srgb, var(--app-accent-2) 55%, var(--app-accent))
  );
}

.hero-title {
  margin: 0 0 14px;
  font-family: var(--app-font-display);
  font-size: clamp(1.35rem, 3.5vw, 1.85rem);
  font-weight: 600;
  letter-spacing: -0.03em;
  color: var(--color-text-1);
  line-height: 1.28;
}

.hero-times {
  margin: 0;
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
}

.time-chip {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 8px 14px;
  border-radius: 12px;
  background: var(--color-bg-1);
  border: 1px solid var(--color-border-2);
  font-size: 13px;
  color: var(--color-text-2);
}

.time-label {
  font-size: 11px;
  font-weight: 700;
  text-transform: uppercase;
  letter-spacing: 0.06em;
  color: var(--color-text-4);
}

.hero-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  align-items: center;
}

/* 开赛倒计时（最后一小时） */
.premiere-gate {
  position: relative;
  margin-bottom: 20px;
  padding: 22px 24px 24px;
  border-radius: 22px;
  overflow: hidden;
  border: 1px solid color-mix(in srgb, var(--app-accent) 38%, var(--color-border-2));
  background:
    linear-gradient(
      145deg,
      color-mix(in srgb, var(--app-accent) 16%, var(--color-bg-2)) 0%,
      var(--color-bg-2) 46%,
      color-mix(in srgb, var(--app-accent-2) 8%, var(--color-bg-1)) 100%
    );
  box-shadow:
    0 18px 44px color-mix(in srgb, var(--color-text-1) 8%, transparent),
    0 0 0 1px color-mix(in srgb, #ffffff 35%, transparent) inset,
    0 0 60px color-mix(in srgb, var(--app-accent) 14%, transparent);
}

.premiere-gate__grid {
  pointer-events: none;
  position: absolute;
  inset: 0;
  opacity: 0.4;
  background-image:
    linear-gradient(color-mix(in srgb, var(--app-accent) 24%, transparent) 1px, transparent 1px),
    linear-gradient(90deg, color-mix(in srgb, var(--app-accent) 24%, transparent) 1px, transparent 1px);
  background-size: 22px 22px;
  mask-image: radial-gradient(ellipse 85% 75% at 72% 28%, #000 18%, transparent 68%);
  -webkit-mask-image: radial-gradient(ellipse 85% 75% at 72% 28%, #000 18%, transparent 68%);
}

.premiere-gate__orbit {
  pointer-events: none;
  position: absolute;
  width: min(120vw, 520px);
  height: min(120vw, 520px);
  top: -58%;
  right: -18%;
  border: 1px solid color-mix(in srgb, var(--app-accent) 22%, transparent);
  border-radius: 50%;
  opacity: 0.28;
}

.premiere-gate__glow {
  pointer-events: none;
  position: absolute;
  border-radius: 50%;
  filter: blur(48px);
}

.premiere-gate__glow--a {
  width: min(70vw, 340px);
  height: min(70vw, 340px);
  top: -35%;
  right: -5%;
  background: radial-gradient(closest-side, color-mix(in srgb, var(--app-accent) 42%, transparent), transparent 72%);
  opacity: 0.55;
  animation: premiere-glow-move 12s ease-in-out infinite alternate;
}

.premiere-gate__glow--b {
  width: min(48vw, 220px);
  height: min(48vw, 220px);
  bottom: -40%;
  left: -8%;
  background: radial-gradient(closest-side, color-mix(in srgb, var(--app-accent-2) 28%, transparent), transparent 70%);
  opacity: 0.45;
  animation: premiere-glow-move 14s ease-in-out infinite alternate-reverse;
}

@keyframes premiere-glow-move {
  from {
    transform: translate(0, 0) scale(1);
    opacity: 0.38;
  }
  to {
    transform: translate(-14px, 10px) scale(1.06);
    opacity: 0.58;
  }
}

.premiere-gate__layout {
  position: relative;
  z-index: 1;
  display: grid;
  gap: 22px;
  align-items: center;
}

@media (min-width: 860px) {
  .premiere-gate__layout {
    grid-template-columns: minmax(0, 1fr) auto;
    gap: 32px;
  }
}

.premiere-gate__badge {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  margin: 0 0 10px;
  padding: 5px 12px 5px 10px;
  width: fit-content;
  border-radius: 999px;
  font-size: 10px;
  font-weight: 800;
  letter-spacing: 0.16em;
  text-transform: uppercase;
  color: color-mix(in srgb, var(--app-accent) 95%, #012);
  background: linear-gradient(
    135deg,
    color-mix(in srgb, var(--app-accent) 22%, var(--color-bg-1)),
    color-mix(in srgb, var(--color-bg-1) 88%, #fff 12%)
  );
  border: 1px solid color-mix(in srgb, var(--app-accent) 45%, var(--color-border-2));
  box-shadow: 0 4px 18px color-mix(in srgb, var(--app-accent) 18%, transparent);
}

.premiere-gate__pulse {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: var(--app-accent);
  box-shadow: 0 0 14px color-mix(in srgb, var(--app-accent) 75%, transparent);
  animation: premiere-pulse 1.6s ease-in-out infinite;
}

@keyframes premiere-pulse {
  0%,
  100% {
    opacity: 1;
    transform: scale(1);
  }
  50% {
    opacity: 0.55;
    transform: scale(0.9);
  }
}

.premiere-gate__title {
  margin: 0 0 10px;
  font-size: clamp(1.2rem, 2.8vw, 1.45rem);
  font-weight: 800;
  letter-spacing: -0.03em;
  color: var(--color-text-1);
  line-height: 1.25;
}

.premiere-gate__lead {
  margin: 0;
  max-width: 46ch;
  font-size: 14px;
  line-height: 1.7;
  color: var(--color-text-2);
}

.premiere-gate__lead strong {
  color: var(--color-text-1);
}

.premiere-gate__clock {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 10px 8px 6px;
}

.premiere-gate__digits {
  display: flex;
  align-items: flex-end;
  gap: 10px;
}

.premiere-gate__digit-group {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
}

.premiere-gate__digit {
  min-width: 3.4ch;
  padding: 14px 16px;
  border-radius: 18px;
  font-family: ui-monospace, 'Cascadia Code', 'SF Mono', Menlo, sans-serif;
  font-size: clamp(32px, 7vw, 44px);
  font-weight: 800;
  font-variant-numeric: tabular-nums;
  letter-spacing: 0.06em;
  line-height: 1;
  color: var(--color-text-1);
  background: linear-gradient(
    168deg,
    color-mix(in srgb, var(--color-bg-1) 92%, #fff 8%) 0%,
    color-mix(in srgb, var(--color-bg-2) 78%, #fff 22%) 100%
  );
  border: 1px solid color-mix(in srgb, var(--app-accent) 32%, var(--color-border-2));
  box-shadow:
    inset 0 1px 0 rgba(255, 255, 255, 0.65),
    0 14px 32px rgba(15, 23, 42, 0.1),
    0 0 28px color-mix(in srgb, var(--app-accent) 22%, transparent);
}

.premiere-gate__digit-label {
  font-size: 10px;
  font-weight: 800;
  letter-spacing: 0.16em;
  color: var(--color-text-3);
}

.premiere-gate__colon {
  font-size: clamp(28px, 6vw, 40px);
  font-weight: 800;
  color: color-mix(in srgb, var(--app-accent) 65%, var(--color-text-3));
  padding-bottom: 18px;
  text-shadow: 0 0 24px color-mix(in srgb, var(--app-accent) 35%, transparent);
}

.premiere-gate__fineprint {
  margin: 4px 0 0;
  font-size: 11px;
  font-weight: 600;
  letter-spacing: 0.05em;
  color: var(--color-text-3);
}

.alert-block {
  margin-bottom: 16px;
  border-radius: 12px;
}

.rules-card {
  padding: 20px 22px;
  margin-bottom: 18px;
  border-radius: 16px;
  background: var(--color-bg-2);
  border: 1px solid var(--color-border-2);
}

.rules-title {
  margin: 0 0 12px;
  font-size: 15px;
  font-weight: 700;
  color: var(--color-text-1);
}

.rules-list {
  margin: 0;
  padding-left: 1.2rem;
  color: var(--color-text-2);
  font-size: 14px;
  line-height: 1.75;
}

.rules-list li {
  margin-bottom: 6px;
}

.desc-box {
  margin-bottom: 18px;
  padding: 18px 20px;
  border-radius: 14px;
  background: var(--color-fill-1);
  border: 1px solid var(--color-border-2);
  color: var(--color-text-2);
  line-height: 1.65;
  white-space: pre-wrap;
  font-size: 14px;
}

.problems-section {
  margin-bottom: 24px;
}

.problems-title {
  margin: 0 0 14px;
  font-size: 15px;
  font-weight: 700;
  color: var(--color-text-1);
}

.problems-title__muted {
  font-weight: 600;
  font-size: 13px;
  color: var(--color-text-3);
}

.problem-pills {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
}

.problem-pill {
  display: inline-flex;
  align-items: center;
  gap: 10px;
  padding: 12px 16px;
  border-radius: 14px;
  border: 1px solid var(--color-border-2);
  background: var(--color-bg-2);
  cursor: pointer;
  text-align: left;
  transition:
    border-color 0.2s ease,
    box-shadow 0.2s ease,
    transform 0.15s ease;
  max-width: 100%;
}

.problem-pill:hover:not(:disabled) {
  border-color: color-mix(in srgb, var(--app-accent) 48%, var(--color-border-2));
  box-shadow: 0 6px 18px color-mix(in srgb, var(--app-accent) 12%, transparent);
  transform: translateY(-1px);
}

.problem-pill--disabled,
.problem-pill:disabled {
  cursor: not-allowed;
  opacity: 0.55;
  transform: none;
  box-shadow: none;
}

.problem-pill__idx {
  flex-shrink: 0;
  min-width: 36px;
  height: 36px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 10px;
  background: color-mix(in srgb, var(--app-accent) 16%, transparent);
  color: var(--app-accent);
  font-size: 13px;
  font-weight: 800;
}

.problem-pill__name {
  font-size: 14px;
  font-weight: 600;
  color: var(--color-text-1);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.table-section {
  margin-top: 8px;
}

.table-head {
  margin-bottom: 14px;
}

.table-title {
  margin: 0 0 4px;
  font-size: 15px;
  font-weight: 700;
  color: var(--color-text-1);
}

.table-sub {
  font-size: 13px;
  color: var(--color-text-3);
}

.table-spin {
  width: 100%;
}

.table-empty-hint {
  margin: 0 0 14px;
  padding: 16px 18px;
  border-radius: 12px;
  background: var(--color-fill-2);
  font-size: 14px;
  color: var(--color-text-2);
  line-height: 1.65;
}

.table-shell {
  border-radius: 16px;
  border: 1px solid color-mix(in srgb, var(--app-accent) 18%, var(--color-border-2));
  overflow: hidden;
  background: color-mix(in srgb, var(--color-bg-2) 94%, #fff 6%);
  box-shadow:
    0 6px 24px color-mix(in srgb, var(--color-text-1) 5%, transparent),
    0 0 0 1px color-mix(in srgb, #ffffff 45%, transparent) inset;
}

/* 浅色：自定义暖底表格；暗黑完全交给 body[arco-theme=dark] 下的 Arco 变量 */
:global(html[data-theme='light']) .table-shell :deep(.arco-table-th) {
  background: var(--color-fill-2) !important;
  font-weight: 700;
}

:global(html[data-theme='light']) .table-shell :deep(.arco-table-td) {
  background: var(--color-bg-2);
}

/* 暗黑：与首页首屏 / 功能入口高亮一致；表格细调见 @/styles/oj-spotlight-pages.css 与 dark-contest-leaderboard-hi.css */
</style>
