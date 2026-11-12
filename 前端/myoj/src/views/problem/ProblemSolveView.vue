<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { Message } from '@arco-design/web-vue'
import { useRoute, useRouter } from 'vue-router'
import { getBackendErrorMessage } from '@/api/httpError'
import { isResultSuccess } from '@/api/result'
import { mapApiError } from '@/api/mapApiError'
import { AUTH_STORAGE_KEY } from '@/config/auth-storage'
import {
  ApiError,
  CodeValidateService,
  ContestControllerService,
  QuestionSubmitControllerService,
  Service,
  type QuestionSubmitAddRequest,
} from '@generated'
import { useAuthStore } from '@/stores/auth'
import StudyAiCopilot from '@/components/study/StudyAiCopilot.vue'
import monaco, { type LoadedMonaco } from '@/lib/monacoEditor'
import gsap from 'gsap'
import { buildCppCompletions, buildJavaCompletions, buildPythonCompletions } from './solveEditorCompletions'

type ProblemDetail = {
  id: string
  title: string
  content: string
  questionType?: string
  imageUrl?: string
  visionModelKey?: string
  countTolerance?: number
  tags?: string[]
  userNickname?: string
  submitNum?: number
  acceptedNum?: number
  updateTime?: string
  judgeConfig?: {
    timeLimit?: number
    memoryLimit?: number
    stackLimit?: number
  } | string
  sampleJudgeCase?: Array<{ input?: string; output?: string }> | string
  judgeCase?: Array<{ input?: string; output?: string }> | string
}

type SubmitHistoryItem = {
  id: string
  time: string
  language: string
  status: 'Pending' | 'Accepted' | 'Wrong Answer' | 'Compile Error' | 'Submitted'
  codeSize: number
}

/** POST /question/run 返回（与后端 QuestionRunVO 对齐） */
type QuestionRunCase = {
  index: number
  input: string
  expectedOutput: string
  actualOutput: string
  passed: boolean
}

type QuestionRunResult = {
  sandboxCode?: number | null
  sandboxMessage?: string | null
  judgeInfo?: { message?: string | null; time?: number | null; memory?: number | null } | null
  terminalError: boolean
  cases: QuestionRunCase[]
  allSamplePassed: boolean
  /** 沙箱输出条数与样例组数不一致等（与 hints 可能重复，后端兼容字段） */
  warning?: string | null
  hints?: string[] | null
  /** 后端启发式：疑似常量输出或未读 stdin */
  suspectedShortcut?: boolean
}

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const loading = ref(false)
const submitting = ref(false)
const running = ref(false)
const runResult = ref<QuestionRunResult | null>(null)
/** 运行结果面板展开；每次有新运行结果时自动展开 */
const runResultPanelExpanded = ref(true)
const code = ref('// 请在此输入代码\n')
const language = ref('cpp')
const EDITOR_THEME_KEY = 'myoj.solve.editor.theme'
const editorTheme = ref<'dark-pro' | 'light-pro' | 'ocean-pro'>('dark-pro')
const detail = ref<ProblemDetail | null>(null)
const activeTab = ref<'statement' | 'meta'>('statement')

const screenWidth = ref(typeof window !== 'undefined' ? window.innerWidth : 1440)
/** 左侧题面默认约 45%，与右侧编辑器约 55% */
const leftPaneWidth = ref(45)
const isDragging = ref(false)
const statementRef = ref<HTMLElement | null>(null)
const editorRef = ref<HTMLElement | null>(null)
const monacoReady = ref(false)
const monacoLoadFailed = ref(false)
let monacoInstance: import('monaco-editor').editor.IStandaloneCodeEditor | null = null
let monacoApi: LoadedMonaco | null = null
let isSyncingFromEditor = false
const completionDisposables: import('monaco-editor').IDisposable[] = []

/** Monaco 语法标红（与后端 /code_validate/syntax 对应） */
const SYNTAX_MARKER_OWNER = 'myoj-syntax-validate'
let syntaxValidateTimer: number | null = null
let syntaxValidateSeq = 0
/** 整行浅红/黄底（deltaDecorations 的 id） */
let syntaxDecorationIds: string[] = []

type SyntaxIssueItem = {
  line: number
  column: number
  message: string
  severity: 'ERROR' | 'WARNING'
}

/** 下方「问题」面板列表，与编辑器红线同步 */
const syntaxIssues = ref<SyntaxIssueItem[]>([])

const isDesktop = computed(() => screenWidth.value >= 1100)
/** 平板：上下分栏，编辑器在上（见 content-grid--tablet-editor-first） */
const isTabletLayout = computed(() => screenWidth.value >= 768 && screenWidth.value < 1100)
/** 窄屏：单栏 + 顶栏 Tab 切换「题目 / 代码」 */
const isMobileLayout = computed(() => screenWidth.value < 768)

const mobileWorkspaceTab = ref<'problem' | 'editor'>('editor')
const expandedSampleIdx = ref(0)
const showMinimap = ref(true)
const showWhitespace = ref(false)
const pageReady = ref(false)
const studyCopilotOpen = ref(false)

function onCopilotInsertCode(snippet: string) {
  const text = snippet ?? ''
  if (!text) return
  const ed = monacoInstance
  if (!ed) {
    code.value += (code.value.endsWith('\n') ? '' : '\n') + text
    return
  }
  const sel = ed.getSelection()
  const model = ed.getModel()
  if (!model || !sel) {
    code.value += (code.value.endsWith('\n') ? '' : '\n') + text
    return
  }
  ed.executeEdits('study-ai-copilot', [{ range: sel, text, forceMoveMarkers: true }])
  isSyncingFromEditor = true
  code.value = ed.getValue()
  void nextTick(() => {
    isSyncingFromEditor = false
  })
}

const LANGUAGE_OPTIONS = [
  { label: 'C++', value: 'cpp' },
  { label: 'Java', value: 'java' },
  { label: 'Python', value: 'python' },
]
const isImageQuestion = computed(() => detail.value?.questionType?.startsWith('IMAGE_') === true)
const availableLanguageOptions = computed(() =>
  isImageQuestion.value ? LANGUAGE_OPTIONS.filter((item) => item.value === 'python') : LANGUAGE_OPTIONS,
)
const visionModelPath = computed(() => detail.value?.visionModelKey === 'YOLO_HELMET'
  ? '/models/hard-hat-best.pt'
  : '/models/yolov8n.pt')
const visionModelLabel = computed(() => ({
  YOLO_GENERAL: 'YOLOv8n 通用检测',
  YOLO_HELMET: '校园安全帽检测',
  EASYOCR_ZH_EN: 'EasyOCR 中英文识别',
  OPENCV_ANALYSIS: 'OpenCV 图像属性分析',
}[detail.value?.visionModelKey || 'YOLO_GENERAL'] || '图像识别模型'))

const IMAGE_PYTHON_TEMPLATE = `from ultralytics import YOLO
import json

def solve(image_path: str) -> dict[str, int]:
    # TODO: 使用 /models/yolov8n.pt 识别 image_path，统计每个类别的数量
    # 可用库：ultralytics、cv2、Pillow、numpy
    return {}

if __name__ == "__main__":
    # 每个用例的 stdin 包含一个沙箱内只读图片路径
    image_path = input().strip()
    print(json.dumps(solve(image_path), sort_keys=True))
`

function imagePythonTemplate(): string {
  const type = detail.value?.questionType
  if (type === 'IMAGE_OCR') return `import easyocr\n\nimage_path = input().strip()\nreader = easyocr.Reader(["ch_sim", "en"], gpu=False)\nlines = reader.readtext(image_path, detail=0, paragraph=True)\nprint(" ".join(lines))\n`
  if (type === 'IMAGE_ANALYSIS') return `from PIL import Image\nimport numpy as np\nimport json\n\nimage_path = input().strip()\nimage = Image.open(image_path).convert("RGB")\ngray = np.asarray(image.convert("L"), dtype=np.float32)\ngx = abs(np.diff(gray, axis=1)).mean() if image.width > 1 else 0\ngy = abs(np.diff(gray, axis=0)).mean() if image.height > 1 else 0\nresult = {"width": image.width, "height": image.height, "mode": "RGB", "mean_gray": round(float(gray.mean()), 2), "edge_strength": round(float(gx + gy), 2)}\nprint(json.dumps(result, sort_keys=True))\n`
  if (type === 'IMAGE_CLASSIFICATION') return `from ultralytics import YOLO\n\nimage_path = input().strip()\nresult = YOLO("${visionModelPath.value}").predict(image_path, verbose=False)[0]\nif result.boxes is None or len(result.boxes) == 0:\n    print("unknown")\nelse:\n    best = int(result.boxes.conf.argmax())\n    print(result.names[int(result.boxes.cls[best])])\n`
  if (type === 'IMAGE_OBJECT_DETECTION') return `from ultralytics import YOLO\nfrom PIL import Image\nimport json\n\nimage_path = input().strip()\nw, h = Image.open(image_path).size\nr = YOLO("${visionModelPath.value}").predict(image_path, verbose=False)[0]\nboxes = []\nfor xyxy, cls in zip(r.boxes.xyxy.tolist(), r.boxes.cls.tolist()):\n    x1, y1, x2, y2 = xyxy\n    boxes.append({"label": r.names[int(cls)], "x1": round(x1/w, 6), "y1": round(y1/h, 6), "x2": round(x2/w, 6), "y2": round(y2/h, 6)})\nprint(json.dumps(boxes))\n`
  return IMAGE_PYTHON_TEMPLATE.replace('/models/yolov8n.pt', visionModelPath.value)
}

function filterLanguageOption(inputValue: string, option: { label?: string }) {
  const q = inputValue.trim().toLowerCase()
  const label = String(option.label ?? '').toLowerCase()
  return !q || label.includes(q)
}

const splitStyle = computed(() => {
  if (isMobileLayout.value) {
    return { gridTemplateColumns: '1fr' }
  }
  if (isTabletLayout.value) {
    return {
      gridTemplateColumns: '1fr',
      gridTemplateRows: 'minmax(300px, 44vh) auto',
      gap: '14px',
    }
  }
  return {
    gridTemplateColumns: `minmax(360px, ${leftPaneWidth.value}%) 8px minmax(360px, ${100 - leftPaneWidth.value}%)`,
  }
})

const passRate = computed(() => {
  const submit = detail.value?.submitNum ?? 0
  const accepted = detail.value?.acceptedNum ?? 0
  if (submit <= 0) return '0%'
  return `${Math.round((accepted / submit) * 100)}%`
})

/** 本题最近一次「计入比赛」的会话记忆（避免刷新丢 ?contestId= 导致提交变练习） */
const PROBLEM_CONTEST_STORAGE = 'myoj:solveContest:'
/** 清除比赛绑定后强制重算 contestIdForSubmit（storage 非响应式） */
const contestStorageRev = ref(0)

/** 已拉取赛况前勿带 contestId 提交，避免结束前误判 */
const contestGateReady = ref(true)
/** 与 rawContestId 对应的赛场阶段（用于结束后清空比赛提交） */
const contestMetaPhase = ref<string | null>(null)
/** 同一场已结束提示只弹一次，避免 strip 与路由重入重复 toast */
const strippedEndedContestIds = new Set<string>()

/** 赛场倒计时（来自 /contest/get，非题目评测限时） */
const contestDetailEndIso = ref<string | null>(null)
const contestDetailStartIso = ref<string | null>(null)
const contestDetailName = ref<string | null>(null)
const contestRemainingMs = ref(0)
let contestCountdownTimer: ReturnType<typeof setInterval> | null = null

function stopContestCountdownTimer() {
  if (contestCountdownTimer != null) {
    clearInterval(contestCountdownTimer)
    contestCountdownTimer = null
  }
}

/** 后端 Date 可能为 ISO 字符串或毫秒数 */
function normalizeContestInstant(v: unknown): string | null {
  if (v == null) return null
  if (typeof v === 'number' && Number.isFinite(v)) return new Date(v).toISOString()
  if (typeof v === 'string') {
    const trimmed = v.trim()
    if (!trimmed) return null
    const parsed = Date.parse(trimmed)
    if (!Number.isNaN(parsed)) return new Date(parsed).toISOString()
    const asNum = Number(trimmed)
    if (Number.isFinite(asNum)) return new Date(asNum).toISOString()
  }
  return null
}

function instantToMs(iso: string | null): number {
  if (!iso) return NaN
  const n = Date.parse(iso)
  return Number.isNaN(n) ? NaN : n
}

function tickContestRemaining() {
  const iso = contestDetailEndIso.value
  if (!iso) {
    contestRemainingMs.value = 0
    return
  }
  const end = instantToMs(iso)
  if (Number.isNaN(end)) {
    contestRemainingMs.value = 0
    return
  }
  contestRemainingMs.value = Math.max(0, end - Date.now())
}

function startContestCountdownTimer() {
  stopContestCountdownTimer()
  tickContestRemaining()
  contestCountdownTimer = setInterval(() => {
    const prev = contestRemainingMs.value
    tickContestRemaining()
    const next = contestRemainingMs.value
    if (prev > 0 && next <= 0 && contestDetailEndIso.value) {
      void syncContestClosedGate()
    }
  }, 1000)
}

const contestCountdownParts = computed(() => {
  const ms = contestRemainingMs.value
  const totalSec = Math.floor(ms / 1000)
  const h = Math.floor(totalSec / 3600)
  const m = Math.floor((totalSec % 3600) / 60)
  const s = totalSec % 60
  return { h, m, s }
})

const contestCountdownTone = computed(() => {
  const ms = contestRemainingMs.value
  if (ms <= 0) return 'ended'
  if (ms <= 5 * 60 * 1000) return 'critical'
  if (ms <= 60 * 60 * 1000) return 'soon'
  return 'calm'
})

const contestProgressPct = computed(() => {
  const start = instantToMs(contestDetailStartIso.value)
  const end = instantToMs(contestDetailEndIso.value)
  if (Number.isNaN(start) || Number.isNaN(end) || end <= start) return 0
  const now = Date.now()
  const p = ((now - start) / (end - start)) * 100
  return Math.min(100, Math.max(0, p))
})

/** 赛场剩余时长占比（用于环形可视化） */
const contestRemainingPct = computed(() => {
  const start = instantToMs(contestDetailStartIso.value)
  const end = instantToMs(contestDetailEndIso.value)
  if (Number.isNaN(start) || Number.isNaN(end) || end <= start) return 100
  const total = end - start
  const left = Math.max(0, contestRemainingMs.value)
  return Math.min(100, Math.max(0, (left / total) * 100))
})

const contestRingCircumference = 2 * Math.PI * 44

function rawContestIdFromRouteOrStorage(): string {
  void contestStorageRev.value
  const qid = String(route.params.id || '').trim()
  if (!/^\d+$/.test(qid)) return ''
  const v = route.query.contestId
  const fromQuery = ((Array.isArray(v) ? v[0] : v) || '').trim()
  if (/^\d+$/.test(fromQuery)) return fromQuery
  if (typeof sessionStorage === 'undefined') return ''
  const stored = (sessionStorage.getItem(PROBLEM_CONTEST_STORAGE + qid) || '').trim()
  return /^\d+$/.test(stored) ? stored : ''
}

watch(
  () =>
    [
      String(route.params.id || '').trim(),
      ((Array.isArray(route.query.contestId) ? route.query.contestId[0] : route.query.contestId) || '').trim(),
    ] as const,
  ([qid, cid]) => {
    if (typeof sessionStorage === 'undefined' || !/^\d+$/.test(qid)) return
    if (cid && /^\d+$/.test(cid)) {
      sessionStorage.setItem(PROBLEM_CONTEST_STORAGE + qid, cid)
    }
  },
  { immediate: true },
)

/** 优先 URL，其次本题记忆；只有通过这里带上 contestId，后端才会写入比赛榜 */
const contestIdForSubmit = computed(() => {
  void contestStorageRev.value
  if (!contestGateReady.value) return ''
  const cid = rawContestIdFromRouteOrStorage()
  if (!cid) return ''
  if (contestMetaPhase.value === '已结束') return ''
  return cid
})

watch(
  () => [contestDetailEndIso.value, contestIdForSubmit.value] as const,
  ([iso]) => {
    stopContestCountdownTimer()
    if (!iso || !contestIdForSubmit.value) {
      contestRemainingMs.value = 0
      return
    }
    startContestCountdownTimer()
  },
  { immediate: true },
)

function clearContestBindForProblem(silent = false) {
  const qid = String(route.params.id || '').trim()
  if (typeof sessionStorage !== 'undefined' && /^\d+$/.test(qid)) {
    sessionStorage.removeItem(PROBLEM_CONTEST_STORAGE + qid)
  }
  const q = { ...route.query } as Record<string, string | string[] | undefined>
  delete q.contestId
  void router.replace({ path: route.path, query: q })
  contestStorageRev.value++
  if (!silent) {
    Message.success('已切换为练习提交（不再计入比赛）')
  }
}

/** 比赛结束后移除 URL / session 中的赛场上下文，后续均为练习提交 */
function applyContestClosedStrip() {
  const cid = rawContestIdFromRouteOrStorage()
  if (!cid) return
  const firstNotice = !strippedEndedContestIds.has(cid)
  if (firstNotice) strippedEndedContestIds.add(cid)
  clearContestBindForProblem(true)
  if (firstNotice) {
    Message.info('本场比赛已结束，提交将作为练习（不计入比赛榜）')
  }
}

async function syncContestClosedGate() {
  const cid = rawContestIdFromRouteOrStorage()
  if (!cid) {
    contestMetaPhase.value = null
    contestGateReady.value = true
    contestDetailEndIso.value = null
    contestDetailStartIso.value = null
    contestDetailName.value = null
    contestRemainingMs.value = 0
    stopContestCountdownTimer()
    return
  }
  contestGateReady.value = false
  try {
    const data = await ContestControllerService.getContest(cid)
    if (!isResultSuccess(data.code) || !data.data) {
      contestMetaPhase.value = null
      contestDetailEndIso.value = null
      contestDetailStartIso.value = null
      contestDetailName.value = null
      return
    }
    const phase = data.data.phase ?? ''
    contestMetaPhase.value = phase
    if (phase === '已结束') {
      contestDetailEndIso.value = null
      contestDetailStartIso.value = null
      contestDetailName.value = null
      applyContestClosedStrip()
      return
    }
    const d = data.data
    contestDetailEndIso.value = normalizeContestInstant(d.endTime)
    contestDetailStartIso.value = normalizeContestInstant(d.startTime)
    contestDetailName.value = typeof d.title === 'string' ? d.title : null
  } catch {
    contestMetaPhase.value = null
    contestDetailEndIso.value = null
    contestDetailStartIso.value = null
    contestDetailName.value = null
  } finally {
    contestGateReady.value = true
  }
}

watch(
  () => [
    String(route.params.id || ''),
    route.query.contestId,
    contestStorageRev.value,
  ],
  () => {
    void syncContestClosedGate()
  },
  { immediate: true },
)

const contentLength = computed(() => (detail.value?.content || '').trim().length)
const userScopeKey = computed(() => {
  const uid = (auth.userId || '').trim()
  return uid ? `u:${uid}` : 'guest'
})

const headingAnchors = computed(() => {
  const raw = detail.value?.content || ''
  const lines = raw.split('\n')
  const list: Array<{ id: string; title: string; start: number }> = []
  let cursor = 0
  for (const line of lines) {
    const m = line.match(/^(#{1,3})\s+(.+)$/)
    if (m) {
      list.push({
        id: `h-${list.length + 1}`,
        title: m[2]?.trim() || '小节',
        start: cursor,
      })
    }
    cursor += line.length + 1
  }
  return list.length ? list : [{ id: 'h-1', title: '题目描述', start: 0 }]
})

function normalizeSampleJudgeCases(raw: ProblemDetail | null): Array<{ id: string; input: string; output: string }> {
  if (!raw) return []
  let source: Array<{ input?: string; output?: string }> = []
  if (Array.isArray(raw.sampleJudgeCase)) {
    source = raw.sampleJudgeCase
  } else if (typeof raw.sampleJudgeCase === 'string') {
    const parsed = safeJsonParse<Array<{ input?: string; output?: string }>>(raw.sampleJudgeCase, [])
    source = Array.isArray(parsed) ? parsed : []
  } else if (Array.isArray(raw.judgeCase)) {
    source = raw.judgeCase
  } else if (typeof raw.judgeCase === 'string') {
    const parsed = safeJsonParse<Array<{ input?: string; output?: string }>>(raw.judgeCase, [])
    source = Array.isArray(parsed) ? parsed : []
  }
  return source
    .map((item, index) => ({
      id: `case-${index + 1}`,
      input:
        raw.questionType?.startsWith('IMAGE_')
          ? '[系统题图：运行时以只读路径提供]'
          : String(item?.input ?? '').trim(),
      output: String(item?.output ?? '').trim(),
    }))
    .filter((item) => item.input || item.output)
}

const displayJudgeCases = computed(() => normalizeSampleJudgeCases(detail.value))

watch(
  () => detail.value?.id,
  () => {
    expandedSampleIdx.value = 0
  },
)

watch(
  () => displayJudgeCases.value.length,
  (len) => {
    if (len <= 0) return
    if (expandedSampleIdx.value >= len) expandedSampleIdx.value = len - 1
  },
)

async function copyToClipboard(text: string, okMsg = '已复制到剪贴板') {
  try {
    await navigator.clipboard.writeText(text)
    Message.success(okMsg)
  } catch {
    Message.error('复制失败，请手动选择文本复制')
  }
}

function isSampleExpanded(idx: number) {
  const n = displayJudgeCases.value.length
  if (n <= 1) return true
  return expandedSampleIdx.value === idx
}

function toggleSampleAccordion(idx: number) {
  if (displayJudgeCases.value.length <= 1) return
  expandedSampleIdx.value = expandedSampleIdx.value === idx ? -1 : idx
}

const judgeConfigDisplay = computed(() => {
  const cfg = detail.value?.judgeConfig
  if (!cfg) return null
  let parsed: { timeLimit?: number; memoryLimit?: number; stackLimit?: number } = {}
  if (typeof cfg === 'string') {
    parsed = safeJsonParse<{ timeLimit?: number; memoryLimit?: number; stackLimit?: number }>(cfg, {})
  } else {
    parsed = cfg
  }
  return {
    timeLimit: isImageQuestion.value ? 60_000 : Number(parsed.timeLimit ?? 0) || 0,
    memoryLimit: isImageQuestion.value ? 1_572_864 : Number(parsed.memoryLimit ?? 0) || 0,
    stackLimit: Number(parsed.stackLimit ?? 0) || 0,
  }
})
const aiJudgeContext = computed(() => JSON.stringify({
  limits: judgeConfigDisplay.value,
  questionType: detail.value?.questionType ?? 'TEXT',
  countTolerance: detail.value?.countTolerance ?? 0,
  imageProtocol: isImageQuestion.value
    ? {
        stdin: '沙箱内只读图片路径',
        language: 'python',
        model: '/models/yolov8n.pt',
        libraries: ['ultralytics', 'cv2', 'Pillow', 'numpy'],
        output: '只输出目标类别到数量的 JSON 对象',
        network: false,
      }
    : undefined,
}))
const aiSampleCases = computed(() => JSON.stringify(displayJudgeCases.value.slice(0, 5)))
const aiRunResult = computed(() => runResult.value ? JSON.stringify(runResult.value) : '')
const statementHtml = computed(() => renderStatementToHtml(detail.value?.content || ''))
const editorThemeLabel = computed(() => {
  if (editorTheme.value === 'light-pro') return '浅色'
  if (editorTheme.value === 'ocean-pro') return '海洋'
  return '深色'
})

const draftKey = computed(() => {
  const id = String(route.params.id || '').trim()
  return `myoj.solve.draft:${userScopeKey.value}:${id}:${language.value}`
})

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

function escapeHtml(raw: string): string {
  return raw
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;')
    .replaceAll("'", '&#39;')
}

function renderStatementToHtml(raw: string): string {
  const safe = escapeHtml(raw || '')
  if (!safe.trim()) return '<p>暂无题面描述</p>'
  let html = safe
  html = html.replace(/^###\s+(.+)$/gm, '<h3>$1</h3>')
  html = html.replace(/^##\s+(.+)$/gm, '<h2>$1</h2>')
  html = html.replace(/^#\s+(.+)$/gm, '<h1>$1</h1>')
  html = html.replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>')
  html = html.replace(/`([^`]+)`/g, '<code>$1</code>')
  html = html.replace(/^\d+\.\s+(.+)$/gm, '<li>$1</li>')
  html = html.replace(/^(?:-|\*)\s+(.+)$/gm, '<li>$1</li>')
  html = html.replace(/(<li>[\s\S]*?<\/li>)/g, '<ul>$1</ul>')
  html = html.replace(/<\/ul>\s*<ul>/g, '')
  html = html
    .split(/\n{2,}/)
    .map((block) => {
      const trimmed = block.trim()
      if (!trimmed) return ''
      if (/^<(h1|h2|h3|ul)/.test(trimmed)) return trimmed
      return `<p>${trimmed.replace(/\n/g, '<br />')}</p>`
    })
    .join('')
  return html
}

function loadDraft() {
  if (typeof window === 'undefined') return
  const raw = window.localStorage.getItem(draftKey.value)
  if (!raw) {
    code.value = isImageQuestion.value ? imagePythonTemplate() : '// 请在此输入代码\n'
    return
  }
  code.value = raw
}

function safeJsonParse<T>(raw: string | null, fallback: T): T {
  if (!raw) return fallback
  try {
    return JSON.parse(raw) as T
  } catch {
    return fallback
  }
}

function getToken(): string {
  if (typeof window === 'undefined') return ''
  const auth = safeJsonParse<{ token?: string }>(window.localStorage.getItem(AUTH_STORAGE_KEY), {})
  return auth.token?.trim() ?? ''
}

function saveDraft() {
  if (typeof window === 'undefined') return
  window.localStorage.setItem(draftKey.value, code.value || '')
}

function applyCodeTemplate() {
  if (isImageQuestion.value) {
    language.value = 'python'
    code.value = imagePythonTemplate()
    Message.success('已应用图像识别 Python 模板')
    return
  }
  const templates: Record<string, string> = {
    cpp: `#include <bits/stdc++.h>
using namespace std;

int main() {
  ios::sync_with_stdio(false);
  cin.tie(nullptr);

  // TODO: 在这里实现你的算法

  return 0;
}
`,
    java: `import java.io.*;
import java.util.*;

public class Main {
  public static void main(String[] args) throws Exception {
    BufferedReader br = new BufferedReader(new InputStreamReader(System.in));
    StringTokenizer st;

    // TODO: 在这里实现你的算法
  }
}
`,
    python: `import sys

def solve():
    # TODO: 在这里实现你的算法
    pass

if __name__ == "__main__":
    solve()
`,
  }
  code.value = templates[language.value] ?? '// TODO: 在这里实现你的算法\n'
  Message.success('已应用代码模板')
}

function toggleEditorTheme() {
  const order: Array<'dark-pro' | 'light-pro' | 'ocean-pro'> = ['dark-pro', 'light-pro', 'ocean-pro']
  const idx = order.indexOf(editorTheme.value)
  const next = order[(idx + 1) % order.length] ?? 'dark-pro'
  editorTheme.value = next
  if (typeof window !== 'undefined') {
    window.localStorage.setItem(EDITOR_THEME_KEY, editorTheme.value)
  }
  Message.success(`编辑器主题：${editorThemeLabel.value}`)
}

function toMonacoLanguage(lang: string): string {
  if (lang === 'cpp') return 'cpp'
  if (lang === 'java') return 'java'
  if (lang === 'python') return 'python'
  return 'plaintext'
}

function toMonacoThemeName(theme: 'dark-pro' | 'light-pro' | 'ocean-pro'): string {
  if (theme === 'light-pro') return 'myoj-light-pro'
  if (theme === 'ocean-pro') return 'myoj-ocean-pro'
  return 'myoj-dark-pro'
}

function defineMonacoThemes(monaco: LoadedMonaco) {
  monaco.editor.defineTheme('myoj-dark-pro', {
    base: 'vs-dark',
    inherit: true,
    rules: [],
    colors: {
      /** One Dark Pro 系：深邃 slate，避免纯黑 */
      'editor.background': '#1e2127',
      'editor.foreground': '#abb2bf',
      'editorLineNumber.foreground': '#495162',
      'editorLineNumber.activeForeground': '#c8ccd4',
      'editorLineHighlightBackground': '#2c313c',
      'editorCursor.foreground': '#61afef',
      'editor.selectionBackground': '#3e445133',
      'editorWhitespace.foreground': '#3e4451',
      'editorIndentGuide.background': '#3e4451',
      'editorIndentGuide.activeBackground': '#c8ccd4',
      'minimap.background': '#1e2127',
    },
  })
  monaco.editor.defineTheme('myoj-light-pro', {
    base: 'vs',
    inherit: true,
    rules: [],
    colors: {
      /** GitHub Light 系 */
      'editor.background': '#ffffff',
      'editor.foreground': '#24292f',
      'editorLineNumber.foreground': '#959da5',
      'editorLineNumber.activeForeground': '#24292f',
      'editorLineHighlightBackground': '#f6f8fa',
      'editorCursor.foreground': '#0969da',
      'editor.selectionBackground': '#add6ff99',
      'editorWhitespace.foreground': '#d0d7de',
      'editorIndentGuide.background': '#d8dee4',
      'editorIndentGuide.activeBackground': '#afb8c1',
      'minimap.background': '#ffffff',
    },
  })
  monaco.editor.defineTheme('myoj-ocean-pro', {
    base: 'vs-dark',
    inherit: true,
    rules: [],
    colors: {
      'editor.background': '#06202d',
      'editor.foreground': '#d1f5ff',
      'editorLineNumber.foreground': '#67e8f9',
      'editorLineNumber.activeForeground': '#a5f3fc',
      'editorLineHighlightBackground': '#0b3a47',
      'editorCursor.foreground': '#22d3ee',
      'editor.selectionBackground': '#0e749066',
      'minimap.background': '#06202d',
    },
  })
}

function completionWordRange(
  model: import('monaco-editor').editor.ITextModel,
  position: import('monaco-editor').Position,
): import('monaco-editor').IRange {
  const w = model.getWordUntilPosition(position)
  return {
    startLineNumber: position.lineNumber,
    endLineNumber: position.lineNumber,
    startColumn: w.startColumn,
    endColumn: w.endColumn,
  }
}

function registerLanguageCompletions(loadedMonaco: LoadedMonaco) {
  completionDisposables.forEach((d) => d.dispose())
  completionDisposables.length = 0

  const cppProvider = loadedMonaco.languages.registerCompletionItemProvider('cpp', {
    triggerCharacters: ['.', ':', '#', '<', '_'],
    provideCompletionItems: async (model, position) => {
      const range = completionWordRange(model, position)
      const staticList = buildCppCompletions(loadedMonaco, range)
      return { suggestions: staticList }
    },
  })

  const javaProvider = loadedMonaco.languages.registerCompletionItemProvider('java', {
    triggerCharacters: ['.', '_'],
    provideCompletionItems: async (model, position) => {
      const range = completionWordRange(model, position)
      const staticList = buildJavaCompletions(loadedMonaco, range)
      return { suggestions: staticList }
    },
  })

  const pythonProvider = loadedMonaco.languages.registerCompletionItemProvider('python', {
    triggerCharacters: ['.', '_'],
    provideCompletionItems: async (model, position) => {
      const range = completionWordRange(model, position)
      const staticList = buildPythonCompletions(loadedMonaco, range)
      return { suggestions: staticList }
    },
  })

  completionDisposables.push(cppProvider, javaProvider, pythonProvider)
}

function clearSyntaxValidateTimer() {
  if (syntaxValidateTimer != null) {
    clearTimeout(syntaxValidateTimer)
    syntaxValidateTimer = null
  }
}

function clearSyntaxDecorationsOnly() {
  if (!monacoInstance || syntaxDecorationIds.length === 0) return
  syntaxDecorationIds = monacoInstance.deltaDecorations(syntaxDecorationIds, [])
}

function clearEditorSyntaxMarkers() {
  syntaxIssues.value = []
  clearSyntaxDecorationsOnly()
  if (!monacoApi || !monacoInstance) return
  const model = monacoInstance.getModel()
  if (model && !model.isDisposed()) {
    monacoApi.editor.setModelMarkers(model, SYNTAX_MARKER_OWNER, [])
  }
}

/** 点击问题条目跳到对应行列 */
function jumpToSyntaxIssue(line: number, column = 1) {
  if (!monacoInstance || monacoLoadFailed.value) return
  const model = monacoInstance.getModel()
  if (!model || model.isDisposed()) return
  const col = Math.max(1, Math.min(column, model.getLineMaxColumn(line)))
  monacoInstance.focus()
  monacoInstance.setPosition({ lineNumber: line, column: col })
  monacoInstance.revealLineInCenterIfOutsideViewport(line)
}

/**
 * 整行浅红/黄 + 行号区圆点；与 setModelMarkers 一起用
 */
function applySyntaxEditorDecorations(
  api: LoadedMonaco,
  editor: import('monaco-editor').editor.IStandaloneCodeEditor,
  model: import('monaco-editor').editor.ITextModel,
  diagnostics: Array<{
    startLineNumber?: number
    endLineNumber?: number
    startColumnNumber?: number
    endColumnNumber?: number
    message?: string
    severity?: string
  }>,
) {
  syntaxDecorationIds = editor.deltaDecorations(syntaxDecorationIds, [])
  if (!diagnostics.length) return

  const lineSeverity = new Map<number, 'ERROR' | 'WARNING'>()
  for (const d of diagnostics) {
    const startL = Number(d.startLineNumber ?? 1) || 1
    const endL = Math.max(startL, Number(d.endLineNumber ?? startL) || startL)
    const sev: 'ERROR' | 'WARNING' =
      String(d.severity ?? 'ERROR').toUpperCase() === 'WARNING' ? 'WARNING' : 'ERROR'
    for (let L = startL; L <= endL; L++) {
      const prev = lineSeverity.get(L)
      if (!prev || prev === 'WARNING') {
        lineSeverity.set(L, sev)
      }
    }
  }

  const decos: import('monaco-editor').editor.IModelDeltaDecoration[] = []
  for (const [line, sev] of lineSeverity) {
    const maxCol = Math.max(1, model.getLineMaxColumn(line))
    const isErr = sev === 'ERROR'
    decos.push({
      range: new api.Range(line, 1, line, maxCol),
      options: {
        isWholeLine: true,
        className: isErr ? 'myoj-syntax-line-err' : 'myoj-syntax-line-warn',
        glyphMarginClassName: isErr ? 'myoj-syntax-glyph-err' : 'myoj-syntax-glyph-warn',
        overviewRuler: {
          color: isErr ? 'rgba(248, 113, 113, 0.92)' : 'rgba(251, 191, 36, 0.92)',
          position: api.editor.OverviewRulerLane.Right,
        },
        minimap: {
          color: isErr ? 'rgba(248, 113, 113, 0.85)' : 'rgba(251, 191, 36, 0.85)',
          position: api.editor.MinimapPosition.Gutter,
        },
      },
    })
  }
  syntaxDecorationIds = editor.deltaDecorations(syntaxDecorationIds, decos)
}

function scheduleSyntaxValidation(api: LoadedMonaco) {
  if (typeof window === 'undefined' || monacoLoadFailed.value) return
  clearSyntaxValidateTimer()
  syntaxValidateTimer = window.setTimeout(() => {
    syntaxValidateTimer = null
    void runSyntaxValidation(api)
  }, 900)
}

async function runSyntaxValidation(api: LoadedMonaco) {
  const seq = ++syntaxValidateSeq
  const editor = monacoInstance
  if (!editor || monacoLoadFailed.value) return
  const model = editor.getModel()
  if (!model || model.isDisposed()) return
  const text = model.getValue()
  if (!text.trim()) {
    syntaxIssues.value = []
    syntaxDecorationIds = editor.deltaDecorations(syntaxDecorationIds, [])
    api.editor.setModelMarkers(model, SYNTAX_MARKER_OWNER, [])
    return
  }
  const token = getToken()
  if (!token) {
    syntaxIssues.value = []
    syntaxDecorationIds = editor.deltaDecorations(syntaxDecorationIds, [])
    api.editor.setModelMarkers(model, SYNTAX_MARKER_OWNER, [])
    return
  }
  try {
    const data = await CodeValidateService.syntax({ language: language.value, code: text })
    if (seq !== syntaxValidateSeq) return
    if (!isResultSuccess(data.code)) return
    const diagnostics = data.data?.diagnostics ?? []

    const markers: import('monaco-editor').editor.IMarkerData[] = diagnostics.map((d) => {
      const sev =
        String(d.severity ?? 'ERROR').toUpperCase() === 'WARNING'
          ? api.MarkerSeverity.Warning
          : api.MarkerSeverity.Error
      const startLine = Number(d.startLineNumber ?? 1) || 1
      const rawStartCol = Number(d.startColumnNumber ?? 1) || 1
      const endLine = Number(d.endLineNumber ?? startLine) || startLine
      let endCol = Number(d.endColumnNumber ?? rawStartCol + 1) || rawStartCol + 1
      let startCol = rawStartCol
      // 错误：整段波浪线铺满涉及行，便于一眼看出「哪一行错了」
      if (sev === api.MarkerSeverity.Error) {
        startCol = 1
        endCol = Math.max(model.getLineMaxColumn(endLine), 2)
      } else if (endLine === startLine && endCol <= startCol) {
        endCol = startCol + 1
      }
      return {
        severity: sev,
        startLineNumber: startLine,
        startColumn: startCol,
        endLineNumber: endLine,
        endColumn: endCol,
        message: String(d.message ?? '语法/编译错误'),
      }
    })

    const issues: SyntaxIssueItem[] = diagnostics.map((d) => {
      const line = Number(d.startLineNumber ?? 1) || 1
      const col = Number(d.startColumnNumber ?? 1) || 1
      const severity: 'ERROR' | 'WARNING' =
        String(d.severity ?? 'ERROR').toUpperCase() === 'WARNING' ? 'WARNING' : 'ERROR'
      return {
        line,
        column: col,
        message: String(d.message ?? '语法/编译错误'),
        severity,
      }
    })
    issues.sort((a, b) => a.line - b.line || a.column - b.column)

    if (seq !== syntaxValidateSeq || model.isDisposed()) return
    api.editor.setModelMarkers(model, SYNTAX_MARKER_OWNER, markers)
    applySyntaxEditorDecorations(api, editor, model, diagnostics)
    syntaxIssues.value = issues
    // 不在校验后自动移动光标/滚动：用户正在输入时跳到「第一个错误」（常为第 1 行）体验很差；
    // 需要定位时点下方「问题」列表，会走 jumpToSyntaxIssue。
  } catch (e) {
    if (e instanceof ApiError && e.status === 401) {
      if (seq === syntaxValidateSeq && !model.isDisposed()) {
        syntaxIssues.value = []
        syntaxDecorationIds = editor.deltaDecorations(syntaxDecorationIds, [])
        api.editor.setModelMarkers(model, SYNTAX_MARKER_OWNER, [])
      }
      return
    }
    if (seq === syntaxValidateSeq && !model.isDisposed()) {
      syntaxIssues.value = []
      syntaxDecorationIds = editor.deltaDecorations(syntaxDecorationIds, [])
      api.editor.setModelMarkers(model, SYNTAX_MARKER_OWNER, [])
    }
  }
}

function applyMonacoEditorOptions() {
  if (!monacoInstance) return
  monacoInstance.updateOptions({
    minimap: { enabled: showMinimap.value, scale: 0.85 },
    renderWhitespace: showWhitespace.value ? 'all' : 'none',
  })
}

async function initMonacoEditor() {
  if (typeof window === 'undefined' || !editorRef.value || monacoInstance) return
  try {
    monacoLoadFailed.value = false
    const loadedMonaco = monaco
    monacoApi = loadedMonaco
    defineMonacoThemes(loadedMonaco)
    registerLanguageCompletions(loadedMonaco)
    loadedMonaco.editor.setTheme(toMonacoThemeName(editorTheme.value))
    const editor = loadedMonaco.editor.create(editorRef.value, {
      value: code.value,
      language: toMonacoLanguage(language.value),
      theme: toMonacoThemeName(editorTheme.value),
      automaticLayout: true,
      minimap: { enabled: showMinimap.value, scale: 0.85, showSlider: 'mouseover' },
      fontSize: 14,
      lineHeight: 22,
      fontFamily: "'JetBrains Mono', 'Fira Code', 'Cascadia Code', 'Consolas', 'Courier New', monospace",
      fontLigatures: true,
      tabSize: 2,
      wordWrap: 'on',
      smoothScrolling: true,
      scrollBeyondLastLine: false,
      roundedSelection: true,
      renderLineHighlight: 'all',
      padding: { top: 8, bottom: 8 },
      lineNumbersMinChars: 3,
      glyphMargin: true,
      folding: true,
      bracketPairColorization: { enabled: true },
      matchBrackets: 'always',
      renderWhitespace: showWhitespace.value ? 'all' : 'none',
      autoClosingBrackets: 'always',
      autoClosingQuotes: 'always',
      quickSuggestions: {
        other: true,
        comments: false,
        strings: false,
      },
      suggestOnTriggerCharacters: true,
    })
    monacoInstance = editor
    monacoReady.value = true
    applyMonacoEditorOptions()
    void nextTick(() => scheduleSyntaxValidation(loadedMonaco))
    editor.onDidChangeModelContent(() => {
      isSyncingFromEditor = true
      code.value = editor.getValue()
      isSyncingFromEditor = false
      scheduleSyntaxValidation(loadedMonaco)
    })
  } catch (error) {
    monacoReady.value = false
    monacoLoadFailed.value = true
    monacoInstance = null
    monacoApi = null
    console.error('[solve] monaco init failed, fallback to textarea', error)
    Message.warning('高级编辑器加载失败，已切换为基础编辑器')
  }
}

async function loadDetail() {
  loading.value = true
  try {
    const id = String(route.params.id || '').trim()
    const data = await Service.getPublicQuestionDetail(id)
    if (!isResultSuccess(data.code)) {
      Message.error(data.message || '题目加载失败')
      return
    }
    detail.value = (data.data as unknown as ProblemDetail) ?? null
    if (detail.value?.questionType?.startsWith('IMAGE_')) {
      language.value = 'python'
    }
    loadDraft()
  } catch (error) {
    Message.error(mapApiError(error, getBackendErrorMessage(error, '题目加载失败')))
  } finally {
    loading.value = false
  }
}

function scrollToAnchor(start: number) {
  const el = statementRef.value
  const content = detail.value?.content || ''
  if (!el || !content.length) return
  const ratio = Math.max(0, Math.min(1, start / content.length))
  const target = ratio * (el.scrollHeight - el.clientHeight)
  el.scrollTo({ top: target, behavior: 'smooth' })
}

function beginResize() {
  if (!isDesktop.value) return
  isDragging.value = true
  document.body.style.userSelect = 'none'
  document.body.style.cursor = 'col-resize'
}

function onMouseMove(e: MouseEvent) {
  if (!isDragging.value || !isDesktop.value) return
  const container = document.querySelector<HTMLElement>('.content-grid')
  if (!container) return
  const rect = container.getBoundingClientRect()
  const ratio = ((e.clientX - rect.left) / rect.width) * 100
  leftPaneWidth.value = Math.min(68, Math.max(32, ratio))
}

function stopResize() {
  if (!isDragging.value) return
  isDragging.value = false
  document.body.style.userSelect = ''
  document.body.style.cursor = ''
}

function resetSplit() {
  leftPaneWidth.value = 45
}

function clearCode() {
  code.value = ''
  saveDraft()
  Message.success('已清空代码草稿')
}

/** 始终取编辑器最新内容（避免 Monaco 与 code 不同步时运行/提交发旧代码） */
function currentEditorCode(): string {
  if (monacoInstance && !monacoLoadFailed.value) {
    return monacoInstance.getValue()
  }
  return code.value
}

async function runCode() {
  if (running.value) return
  if (!currentEditorCode().trim()) {
    Message.warning('请先输入代码')
    return
  }
  const token = getToken()
  if (!token) {
    Message.warning('请先登录后再运行代码')
    router.push({ path: '/', query: { openAuth: '1' } })
    return
  }
  const questionId = String(route.params.id || '').trim()
  if (!/^\d+$/.test(questionId)) {
    Message.error('题目 ID 无效')
    return
  }
  running.value = true
  runResult.value = null
  try {
    const data = await Service.runQuestionSample({
      questionId,
      language: language.value,
      code: currentEditorCode(),
    })
    if (!isResultSuccess(data.code)) {
      Message.error(data.message || '运行失败')
      return
    }
    if (!data.data) {
      Message.error('未获取到运行结果')
      return
    }
    runResult.value = data.data as unknown as QuestionRunResult
    if (runResult.value.warning) {
      Message.warning(runResult.value.warning)
    }
    if (runResult.value.terminalError) {
      Message.warning(runResult.value.judgeInfo?.message || runResult.value.sandboxMessage || '运行未成功完成')
    } else if (runResult.value.allSamplePassed && runResult.value.suspectedShortcut) {
      Message.warning(
        '输出与公开样例文本一致，但代码可能未读入或未按题意计算；请以提交评测为准。',
      )
    } else if (runResult.value.allSamplePassed && !runResult.value.warning) {
      Message.success('样例全部通过（提交仍会评测全部隐藏用例）')
    } else if (!runResult.value.terminalError && !runResult.value.allSamplePassed) {
      Message.info('部分样例未通过，请查看下方对比')
    }
  } catch (e) {
    if (e instanceof ApiError && e.status === 401) {
      Message.error('登录已失效，请重新登录')
      router.push({ path: '/', query: { openAuth: '1' } })
      return
    }
    Message.error(mapApiError(e, getBackendErrorMessage(e, '运行请求失败')))
  } finally {
    running.value = false
  }
}

async function submitCode() {
  if (submitting.value) return
  if (!currentEditorCode().trim()) {
    Message.warning('请先输入代码')
    return
  }
  const token = getToken()
  if (!token) {
    Message.warning('请先登录后再提交代码')
    router.push({ path: '/', query: { openAuth: '1' } })
    return
  }
  const questionId = String(route.params.id || '').trim()
  if (!/^\d+$/.test(questionId)) {
    Message.error('题目 ID 无效，无法提交')
    return
  }
  submitting.value = true
  try {
    const submitBody: QuestionSubmitAddRequest = {
      language: language.value,
      code: currentEditorCode(),
      questionId,
    }
    if (contestIdForSubmit.value) {
      submitBody.contestId = contestIdForSubmit.value
    }
    const data = await QuestionSubmitControllerService.doQuestionSubmit(submitBody)
    if (!isResultSuccess(data.code)) {
      Message.error(data.message || '提交失败')
      return
    }
    const payload = data.data
    if (payload?.submitNo == null || payload.questionId == null || payload.globalSubmitNo == null) {
      Message.error('未获取到提交序号')
      return
    }

    // 与常见 OJ 一致：跳转提交列表但不改筛选条件；session 驱动轮询。globalSubmitNo 与「全部提交」列表行 submitNo 一致，便于自动刷新该行
    if (typeof sessionStorage !== 'undefined') {
      sessionStorage.setItem(
        'oj:pendingSubmitHighlight',
        JSON.stringify({
          questionId: String(payload.questionId),
          submitNo: String(payload.submitNo),
          globalSubmitNo: String(payload.globalSubmitNo),
          t: Date.now(),
        }),
      )
    }

    Message.success('提交成功，已前往提交记录')
    // 带一次性 query，避免从「提交记录」再交卷时 fullPath 不变导致列表页不刷新、session 不消费
    void router.push({ name: 'submissions', query: { fromSubmit: '1' } })
  } catch (error) {
    if (error instanceof ApiError && error.status === 401) {
      Message.error('登录已失效，请重新登录后提交')
      router.push({ path: '/', query: { openAuth: '1' } })
      return
    }
    Message.error(mapApiError(error, getBackendErrorMessage(error, '提交失败')))
  } finally {
    submitting.value = false
  }
}

function onResize() {
  screenWidth.value = window.innerWidth
}

onMounted(() => {
  if (typeof window !== 'undefined') {
    const cached = window.localStorage.getItem(EDITOR_THEME_KEY)
    if (cached === 'dark-pro' || cached === 'light-pro' || cached === 'ocean-pro') {
      editorTheme.value = cached
    }
  }
  loadDetail()
  initMonacoEditor()
  if (typeof window !== 'undefined') {
    window.addEventListener('mousemove', onMouseMove)
    window.addEventListener('mouseup', stopResize)
    window.addEventListener('resize', onResize)
  }
})

watch(
  () => detail.value?.id,
  async (id) => {
    if (!id) return
    await nextTick()
    initMonacoEditor()
  },
  { immediate: true },
)

onBeforeUnmount(() => {
  stopContestCountdownTimer()
  clearSyntaxValidateTimer()
  syntaxValidateSeq++
  clearEditorSyntaxMarkers()
  if (typeof window !== 'undefined') {
    window.removeEventListener('mousemove', onMouseMove)
    window.removeEventListener('mouseup', stopResize)
    window.removeEventListener('resize', onResize)
  }
  stopResize()
  monacoInstance?.dispose()
  completionDisposables.forEach((d) => d.dispose())
  completionDisposables.length = 0
  monacoInstance = null
  monacoApi = null
})

watch([code, language], saveDraft)
watch(language, () => {
  loadDraft()
})
watch([userScopeKey, () => route.params.id], () => {
  loadDraft()
  runResult.value = null
})

watch(runResult, (v) => {
  if (v) runResultPanelExpanded.value = true
})

function toggleRunResultPanel() {
  runResultPanelExpanded.value = !runResultPanelExpanded.value
}

const runResultStatusShort = computed(() => {
  const r = runResult.value
  if (!r) return ''
  if (r.terminalError) return r.judgeInfo?.message || '执行异常'
  if (r.allSamplePassed && r.suspectedShortcut) return '输出匹配样例（请核对）'
  if (r.allSamplePassed) return '样例全部通过'
  return '部分样例未通过'
})
watch(code, (next) => {
  if (!monacoInstance || isSyncingFromEditor) return
  if (monacoInstance.getValue() !== next) {
    monacoInstance.setValue(next)
  }
})
watch(language, (next) => {
  if (!monacoInstance || !monacoApi) return
  const model = monacoInstance.getModel()
  if (!model) return
  syntaxIssues.value = []
  syntaxDecorationIds = monacoInstance.deltaDecorations(syntaxDecorationIds, [])
  monacoApi.editor.setModelLanguage(model, toMonacoLanguage(next))
  monacoApi.editor.setModelMarkers(model, SYNTAX_MARKER_OWNER, [])
  scheduleSyntaxValidation(monacoApi)
})
watch(editorTheme, (next) => {
  monacoApi?.editor.setTheme(toMonacoThemeName(next))
})

watch([showMinimap, showWhitespace], () => {
  applyMonacoEditorOptions()
})

watch(studyCopilotOpen, async () => {
  await nextTick()
  monacoInstance?.layout()
})

watch([mobileWorkspaceTab, isTabletLayout, isMobileLayout, isDesktop], async () => {
  await nextTick()
  monacoInstance?.layout()
})

watch(
  () => detail.value?.id,
  async (id) => {
    pageReady.value = !!id
    if (!id) return
    await nextTick()
    await nextTick()
    if (typeof window === 'undefined') return
    const els = document.querySelectorAll('.solve-page .solve-stagger')
    if (!els.length) return
    gsap.fromTo(
      els,
      { opacity: 0, y: 14 },
      {
        opacity: 1,
        y: 0,
        duration: 0.38,
        stagger: 0.06,
        ease: 'power2.out',
        overwrite: 'auto',
      },
    )
  },
)
</script>

<template>
  <div class="solve-page" :class="{ 'solve-page--ready': pageReady }">
    <section class="topbar panel solve-stagger">
      <div class="topbar-left">
        <div class="title-wrap">
          <p class="kicker">Online Judge Workspace</p>
          <h1>{{ detail?.title || '作答页面' }}</h1>
        </div>
      </div>
      <div class="stat-chips">
        <span v-if="contestIdForSubmit" class="chip chip--contest">计入本场比赛 · 提交将上榜</span>
        <span v-else class="chip chip--practice">练习提交 · 不计入任何比赛</span>
        <button
          v-if="contestIdForSubmit"
          type="button"
          class="chip-link"
          @click="() => clearContestBindForProblem()"
        >
          改为练习提交
        </button>
        <span class="chip">解决次数 {{ detail?.acceptedNum ?? 0 }}</span>
        <span class="chip">提交次数 {{ detail?.submitNum ?? 0 }}</span>
        <span class="chip">内存限制 {{ Math.round((judgeConfigDisplay?.memoryLimit ?? 0) / 1024) }}MB</span>
        <span class="chip">评测限时 {{ Math.round((judgeConfigDisplay?.timeLimit ?? 0) / 1000) || 0 }}s</span>
      </div>
    </section>

    <section
      v-if="contestIdForSubmit && contestDetailEndIso"
      class="contest-clock panel"
      :class="`contest-clock--${contestCountdownTone}`"
      aria-label="赛场剩余时间"
    >
      <div class="contest-clock__grid" aria-hidden="true" />
      <div class="contest-clock__glow contest-clock__glow--a" aria-hidden="true" />
      <div class="contest-clock__glow contest-clock__glow--b" aria-hidden="true" />
      <div class="contest-clock__scan" aria-hidden="true" />

      <div class="contest-clock__layout">
        <div class="contest-clock__intro">
          <div class="contest-clock__head">
            <span class="contest-clock__badge">
              <span class="contest-clock__badge-dot" />
              LIVE ARENA
            </span>
            <span class="contest-clock__badge-cn">赛场倒计时</span>
          </div>
          <h2 class="contest-clock__title">{{ contestDetailName || '本场比赛' }}</h2>
          <p class="contest-clock__hint">
            剩余赛程可视化；顶部「评测限时」为单题程序运行上限，与本场比赛时长无关。
          </p>
          <div class="contest-clock__progress contest-clock__progress--bar" aria-hidden="true">
            <div class="contest-clock__progress-fill" :style="{ width: `${contestProgressPct}%` }" />
          </div>
          <p class="contest-clock__progress-label">
            赛程进度 <strong>{{ Math.round(contestProgressPct) }}%</strong>
          </p>
        </div>

        <div class="contest-clock__stage">
          <div class="contest-clock__ring-wrap" aria-hidden="true">
            <svg class="contest-clock__svg" viewBox="0 0 100 100">
              <circle class="contest-clock__ring-bg" cx="50" cy="50" r="44" fill="none" />
              <circle
                class="contest-clock__ring-arc"
                cx="50"
                cy="50"
                r="44"
                fill="none"
                :stroke-dasharray="String(contestRingCircumference)"
                :stroke-dashoffset="
                  contestRingCircumference * (1 - contestRemainingPct / 100)
                "
                transform="rotate(-90 50 50)"
              />
            </svg>
            <div class="contest-clock__ring-caption">
              <span class="contest-clock__ring-pct">{{ Math.round(contestRemainingPct) }}%</span>
              <span class="contest-clock__ring-sub">剩余</span>
            </div>
          </div>

          <div
            class="contest-clock__digits"
            role="timer"
            :aria-live="contestCountdownTone === 'critical' ? 'polite' : 'off'"
          >
            <div class="contest-clock__unit">
              <span class="contest-clock__unit-label">时</span>
              <span class="contest-clock__block">{{ String(contestCountdownParts.h).padStart(2, '0') }}</span>
            </div>
            <span class="contest-clock__sep">:</span>
            <div class="contest-clock__unit">
              <span class="contest-clock__unit-label">分</span>
              <span class="contest-clock__block">{{ String(contestCountdownParts.m).padStart(2, '0') }}</span>
            </div>
            <span class="contest-clock__sep">:</span>
            <div class="contest-clock__unit">
              <span class="contest-clock__unit-label">秒</span>
              <span class="contest-clock__block">{{ String(contestCountdownParts.s).padStart(2, '0') }}</span>
            </div>
          </div>
        </div>
      </div>
    </section>

    <nav
      v-if="detail && isMobileLayout"
      class="workspace-mobile-tabs solve-stagger"
      role="tablist"
      aria-label="题目与编辑器切换"
    >
      <button
        type="button"
        role="tab"
        class="workspace-mobile-tabs__btn"
        :class="{ 'workspace-mobile-tabs__btn--active': mobileWorkspaceTab === 'problem' }"
        :aria-selected="mobileWorkspaceTab === 'problem'"
        @click="mobileWorkspaceTab = 'problem'"
      >
        题目
      </button>
      <button
        type="button"
        role="tab"
        class="workspace-mobile-tabs__btn"
        :class="{ 'workspace-mobile-tabs__btn--active': mobileWorkspaceTab === 'editor' }"
        :aria-selected="mobileWorkspaceTab === 'editor'"
        @click="mobileWorkspaceTab = 'editor'"
      >
        代码
      </button>
    </nav>

    <section
      v-if="detail"
      class="content-grid"
      :class="{
        'content-grid--tablet-editor-first': isTabletLayout && !isMobileLayout,
        'content-grid--mobile': isMobileLayout,
      }"
      :style="splitStyle"
    >
      <article
        v-show="!isMobileLayout || mobileWorkspaceTab === 'problem'"
        class="problem-panel panel solve-stagger"
      >
        <div class="problem-tabs">
          <button class="tab-btn" :class="{ active: activeTab === 'statement' }" @click="activeTab = 'statement'">题面</button>
          <button class="tab-btn" :class="{ active: activeTab === 'meta' }" @click="activeTab = 'meta'">信息</button>
        </div>

        <div class="problem-body-scroll">
          <template v-if="activeTab === 'statement'">
            <div class="oj-card oj-card--statement">
              <div class="anchor-nav">
                <button
                  v-for="anchor in headingAnchors"
                  :key="anchor.id"
                  class="anchor-btn"
                  @click="scrollToAnchor(anchor.start)"
                >
                  {{ anchor.title }}
                </button>
              </div>
              <div class="tags">
                <span v-for="tag in detail.tags || []" :key="`${detail.id}-${tag}`" class="tag tag--pill">{{ tag }}</span>
              </div>
              <section v-if="detail.questionType?.startsWith('IMAGE_') && detail.imageUrl" class="vision-problem">
                <div class="vision-problem__head">
                  <strong>图像智能编程题</strong>
                  <span>{{ visionModelLabel }} · 判题参数 {{ detail.countTolerance ?? 0 }}</span>
                </div>
                <img :src="detail.imageUrl" alt="待识别题图" class="vision-problem__image" />
                <p>请严格按照题目约定输出结果，避免额外日志影响判题。</p>
                <p>评测时会通过 stdin 提供沙箱内题图路径；可使用 Ultralytics、EasyOCR、OpenCV、Pillow 和 NumPy<span v-if="detail.visionModelKey?.startsWith('YOLO') || !detail.visionModelKey">，本题模型位于 <code>{{ visionModelPath }}</code></span>。</p>
              </section>
              <div ref="statementRef" class="statement oj-statement" v-html="statementHtml"></div>
            </div>
            <section class="judge-cases oj-card oj-card--samples">
              <header class="judge-cases__head">
                <h3>输入输出样例</h3>
                <span v-if="displayJudgeCases.length">共 {{ displayJudgeCases.length }} 组</span>
                <span v-else>当前题目暂无样例</span>
              </header>
              <div v-if="displayJudgeCases.length" class="judge-cases__accordion">
                <article
                  v-for="(item, idx) in displayJudgeCases"
                  :key="item.id"
                  class="judge-case-card"
                  :class="{ 'judge-case-card--flat': displayJudgeCases.length <= 1 }"
                >
                  <button
                    v-if="displayJudgeCases.length > 1"
                    type="button"
                    class="judge-case-card__toggle"
                    :aria-expanded="isSampleExpanded(idx)"
                    @click="toggleSampleAccordion(idx)"
                  >
                    <span class="judge-case-card__toggle-title">样例 {{ idx + 1 }}</span>
                    <span class="judge-case-card__chev" aria-hidden="true">{{ isSampleExpanded(idx) ? '▾' : '▸' }}</span>
                  </button>
                  <p v-else class="judge-case-card__title">样例 {{ idx + 1 }}</p>
                  <div v-show="isSampleExpanded(idx)" class="judge-case-card__body">
                    <div class="judge-case-io judge-case-io--in">
                      <div class="judge-case-io__row">
                        <span class="judge-case-io__label">输入</span>
                        <button
                          type="button"
                          class="judge-case-io__copy btn-tap"
                          @click="copyToClipboard(item.input, '已复制输入')"
                        >
                          复制
                        </button>
                      </div>
                      <pre class="judge-case-io__pre">{{ item.input || '(空)' }}</pre>
                    </div>
                    <div class="judge-case-io judge-case-io--out">
                      <div class="judge-case-io__row">
                        <span class="judge-case-io__label">输出</span>
                        <button
                          type="button"
                          class="judge-case-io__copy btn-tap"
                          @click="copyToClipboard(item.output, '已复制输出')"
                        >
                          复制
                        </button>
                      </div>
                      <pre class="judge-case-io__pre">{{ item.output || '(空)' }}</pre>
                    </div>
                  </div>
                </article>
              </div>
              <div v-else class="judge-cases__empty">
                请在题目编辑页配置判题用例后重试。
              </div>
            </section>
            <section class="judge-config oj-card oj-card--limits">
              <header class="judge-config__head">
                <h3>判题配置</h3>
              </header>
              <div v-if="judgeConfigDisplay" class="judge-config__grid">
                <div class="judge-config__item judge-limit-card">
                  <span class="judge-limit-card__icon" aria-hidden="true">⏱️</span>
                  <span class="judge-limit-card__key">时间限制</span>
                  <p class="judge-limit-card__val">
                    <strong>{{ Math.round((judgeConfigDisplay.timeLimit || 0) / 100) / 10 }}</strong>
                    <span class="judge-limit-card__unit">s</span>
                  </p>
                  <span class="judge-limit-card__raw">{{ judgeConfigDisplay.timeLimit }} ms</span>
                </div>
                <div class="judge-config__item judge-limit-card">
                  <span class="judge-limit-card__icon" aria-hidden="true">💾</span>
                  <span class="judge-limit-card__key">内存限制</span>
                  <p class="judge-limit-card__val">
                    <strong>{{ Math.round((judgeConfigDisplay.memoryLimit || 0) / 1024) }}</strong>
                    <span class="judge-limit-card__unit">MB</span>
                  </p>
                </div>
                <div class="judge-config__item judge-limit-card">
                  <span class="judge-limit-card__icon" aria-hidden="true">📚</span>
                  <span class="judge-limit-card__key">栈限制</span>
                  <p class="judge-limit-card__val">
                    <strong>{{ Math.round((judgeConfigDisplay.stackLimit || 0) / 1024) }}</strong>
                    <span class="judge-limit-card__unit">MB</span>
                  </p>
                </div>
              </div>
              <div v-else class="judge-config__empty">当前题目未配置判题参数</div>
            </section>
          </template>
          <template v-else>
            <div class="meta-grid">
              <div class="meta-item">
                <span>题目编号</span>
                <strong>#{{ detail.id }}</strong>
              </div>
              <div class="meta-item">
                <span>出题人</span>
                <strong>{{ detail.userNickname || '-' }}</strong>
              </div>
              <div class="meta-item">
                <span>最近更新</span>
                <strong>{{ formatDateTime(detail.updateTime) }}</strong>
              </div>
              <div class="meta-item">
                <span>题面字数</span>
                <strong>{{ contentLength }}</strong>
              </div>
            </div>
          </template>
        </div>
      </article>

      <div v-if="isDesktop" class="splitter" @mousedown="beginResize" />

      <article
        v-show="!isMobileLayout || mobileWorkspaceTab === 'editor'"
        class="answer-panel panel answer-panel--stack solve-stagger"
        :class="`answer-panel--${editorTheme}`"
      >
        <div class="answer-panel__editor-section">
          <div class="editor-head">
            <h2 title="Ctrl+Space 打开补全；↑↓ 选择；Enter 或 Tab 插入。">
              代码编辑器
            </h2>
            <div class="editor-tools">
              <a-select
                v-model="language"
                allow-search
                :options="availableLanguageOptions"
                :disabled="isImageQuestion"
                :filter-option="filterLanguageOption"
                placeholder="语言"
                style="width: 156px"
              />
              <a-tooltip content="百炼学习助手（题意 / 思路 / 复杂度）">
                <a-button
                  class="ghost-btn editor-icon-btn btn-tap study-copilot-launch"
                  :class="{ 'editor-icon-btn--on': studyCopilotOpen }"
                  @click="studyCopilotOpen = true"
                >
                  ✦
                </a-button>
              </a-tooltip>
              <a-tooltip content="插入本题语言模板">
                <a-button class="ghost-btn editor-icon-btn btn-tap" @click="applyCodeTemplate">📋</a-button>
              </a-tooltip>
              <a-tooltip :content="`切换编辑器配色（当前：${editorThemeLabel}）`">
                <a-button class="ghost-btn editor-icon-btn btn-tap" @click="toggleEditorTheme">◐</a-button>
              </a-tooltip>
              <a-tooltip content="清空编辑器内容">
                <a-button class="ghost-btn editor-icon-btn btn-tap" @click="clearCode">⌧</a-button>
              </a-tooltip>
              <a-tooltip v-if="isDesktop" content="左右分栏恢复为默认比例">
                <a-button class="ghost-btn editor-icon-btn btn-tap" @click="resetSplit">⬌</a-button>
              </a-tooltip>
              <a-tooltip :content="showMinimap ? '隐藏 minimap' : '显示 minimap'">
                <a-button
                  class="ghost-btn editor-icon-btn btn-tap"
                  :class="{ 'editor-icon-btn--on': showMinimap }"
                  @click="showMinimap = !showMinimap"
                >
                  ⧉
                </a-button>
              </a-tooltip>
              <a-tooltip :content="showWhitespace ? '隐藏空白符' : '显示空白符（空格/Tab）'">
                <a-button
                  class="ghost-btn editor-icon-btn btn-tap"
                  :class="{ 'editor-icon-btn--on': showWhitespace }"
                  @click="showWhitespace = !showWhitespace"
                >
                  ··
                </a-button>
              </a-tooltip>
            </div>
          </div>
          <div v-if="!monacoLoadFailed" class="editor-hint-inline" aria-hidden="true">
            <kbd>Ctrl</kbd>+<kbd>Space</kbd> 补全 · 登录后约 1s 检查语法；错误行<strong>浅红底</strong>，下方「问题」列表可跳转
          </div>
          <div v-else class="editor-hint-inline editor-hint-inline--warn">Monaco 未加载，请刷新后使用补全。</div>
          <div v-show="!monacoLoadFailed" ref="editorRef" class="code-area" />
          <div
            v-if="!monacoLoadFailed && syntaxIssues.length > 0"
            class="syntax-issues-panel"
            role="region"
            aria-label="语法与编译问题"
          >
            <div class="syntax-issues-panel__bar">
              <span class="syntax-issues-panel__title">
                <span class="syntax-issues-panel__count">{{ syntaxIssues.length }}</span>
                个问题
              </span>
              <span class="syntax-issues-panel__hint">点击条目定位到对应行</span>
            </div>
            <ul class="syntax-issues-panel__list">
              <li
                v-for="(it, idx) in syntaxIssues"
                :key="`${it.line}-${it.column}-${idx}`"
                class="syntax-issues-panel__item btn-tap"
                :class="{ 'syntax-issues-panel__item--warn': it.severity === 'WARNING' }"
                role="button"
                tabindex="0"
                @click="jumpToSyntaxIssue(it.line, it.column)"
                @keydown.enter.prevent="jumpToSyntaxIssue(it.line, it.column)"
              >
                <span class="syntax-issues-panel__loc">
                  {{ it.severity === 'WARNING' ? '警告' : '错误' }} · L{{ it.line }}:{{ it.column }}
                </span>
                <span class="syntax-issues-panel__msg">{{ it.message }}</span>
              </li>
            </ul>
          </div>
          <a-textarea
            v-if="monacoLoadFailed"
            v-model="code"
            :auto-size="{ minRows: 20, maxRows: 30 }"
            class="code-area code-area--fallback"
          />
        </div>

        <div class="answer-panel__footer">
          <div class="submit-row">
            <a-button class="run-btn btn-tap" :loading="running" @click="runCode">运行</a-button>
            <a-button
              type="primary"
              class="submit-code-btn btn-tap"
              :loading="submitting"
              @click="submitCode"
            >
              {{ submitting ? '评测中…' : '提交代码' }}
            </a-button>
          </div>

          <section
            v-if="runResult"
            class="run-result-shell"
            :class="{ 'run-result-shell--collapsed': !runResultPanelExpanded }"
          >
            <div class="run-result-shell__bar">
              <button
                type="button"
                class="run-result-shell__lead btn-tap"
                :aria-expanded="runResultPanelExpanded"
                aria-controls="run-result-detail-panel"
                id="run-result-panel-toggle"
                @click="toggleRunResultPanel"
              >
                <span
                  class="run-result-shell__chevron"
                  :class="{ 'run-result-shell__chevron--collapsed': !runResultPanelExpanded }"
                  aria-hidden="true"
                >
                  <svg class="run-result-shell__chevron-svg" viewBox="0 0 16 16" fill="none">
                    <path
                      d="M4 6l4 4 4-4"
                      stroke="currentColor"
                      stroke-width="1.75"
                      stroke-linecap="round"
                      stroke-linejoin="round"
                    />
                  </svg>
                </span>
                <span class="run-result-shell__heading">运行结果</span>
                <span class="run-result-shell__pill">{{ runResultStatusShort }}</span>
              </button>
              <div class="run-result-shell__actions">
                <a-tooltip :content="runResultPanelExpanded ? '收起详情，腾出编辑区' : '展开查看完整对比'">
                  <button
                    type="button"
                    class="run-result-shell__toggle btn-tap"
                    :aria-expanded="runResultPanelExpanded"
                    aria-controls="run-result-detail-panel"
                    :aria-label="runResultPanelExpanded ? '收起运行结果详情' : '展开运行结果详情'"
                    @click.stop="toggleRunResultPanel"
                  >
                    <svg v-if="runResultPanelExpanded" class="run-result-shell__toggle-ico" viewBox="0 0 16 16" fill="none">
                      <path
                        d="M4 10l4-4 4 4"
                        stroke="currentColor"
                        stroke-width="1.75"
                        stroke-linecap="round"
                        stroke-linejoin="round"
                      />
                    </svg>
                    <svg v-else class="run-result-shell__toggle-ico" viewBox="0 0 16 16" fill="none">
                      <path
                        d="M4 6l4 4 4-4"
                        stroke="currentColor"
                        stroke-width="1.75"
                        stroke-linecap="round"
                        stroke-linejoin="round"
                      />
                    </svg>
                    <span class="run-result-shell__toggle-text">{{
                      runResultPanelExpanded ? '收起' : '展开'
                    }}</span>
                  </button>
                </a-tooltip>
              </div>
            </div>
            <transition name="run-result-collapse">
              <div
                v-show="runResultPanelExpanded"
                id="run-result-detail-panel"
                class="run-result run-result--nested run-result--slide"
                role="region"
                aria-label="运行结果详情"
              >
                <div class="run-result__head run-result__head--chips-only">
            <span
              v-if="runResult.terminalError"
              class="run-result__badge run-result__badge--err"
            >
              {{ runResult.judgeInfo?.message || '执行异常' }}
            </span>
            <span
              v-else-if="runResult.allSamplePassed && runResult.suspectedShortcut"
              class="run-result__badge run-result__badge--soft-warn"
              title="输出文本与公开样例一致，但代码可能未按题意读入或计算"
            >
              输出匹配样例（请核对逻辑）
            </span>
            <span
              v-else-if="runResult.allSamplePassed"
              class="run-result__badge run-result__badge--ok"
            >
              样例全部通过
            </span>
            <span v-else class="run-result__badge run-result__badge--warn">样例未全部通过</span>
            <span v-if="runResult.judgeInfo?.time != null" class="run-result__meta">
              用时 {{ runResult.judgeInfo.time }} ms
            </span>
            <span v-if="runResult.judgeInfo?.memory != null" class="run-result__meta">
              内存 {{ runResult.judgeInfo.memory }} KB
            </span>
          </div>
          <p v-if="runResult.warning" class="run-result__warn">
            {{ runResult.warning }}
          </p>
          <ul v-if="runResult.hints?.length" class="run-result__hints">
            <li v-for="(h, hi) in runResult.hints" :key="hi">{{ h }}</li>
          </ul>
          <p v-if="!runResult.terminalError" class="run-result__hint">
            说明：「运行」仅在<strong>公开样例</strong>上对比<strong>标准输出</strong>与<strong>预期输出</strong>（右侧已列出<strong>输入 / 预期 / 你的输出</strong>）；无法代替对算法正确性的证明。隐藏用例请以<strong>提交代码</strong>评测为准。
          </p>
          <p v-if="runResult.terminalError && runResult.sandboxMessage" class="run-result__msg">
            {{ runResult.sandboxMessage }}
          </p>
          <div v-if="!runResult.terminalError && runResult.cases?.length" class="run-cases">
            <div
              v-for="c in runResult.cases"
              :key="c.index"
              class="run-case"
              :class="{ 'run-case--fail': !c.passed }"
            >
              <div class="run-case__label">
                样例 {{ c.index }}
                <span class="run-case__tag">{{ c.passed ? '通过' : '未通过' }}</span>
              </div>
              <div class="run-case__grid">
                <div>
                  <span class="run-case__k">输入</span>
                  <pre class="run-case__pre">{{ c.input }}</pre>
                </div>
                <div>
                  <span class="run-case__k">预期输出</span>
                  <pre class="run-case__pre">{{ c.expectedOutput }}</pre>
                </div>
                <div>
                  <span class="run-case__k">你的输出</span>
                  <pre class="run-case__pre">{{ c.actualOutput }}</pre>
                </div>
              </div>
            </div>
          </div>
              </div>
            </transition>
          </section>
        </div>
      </article>
    </section>
    <div v-else class="empty-inline">{{ loading ? '题目加载中...' : '题目不存在或已删除' }}</div>

    <StudyAiCopilot
      v-if="detail"
      v-model:open="studyCopilotOpen"
      :question-id="String(detail.id ?? '')"
      :question-title="detail.title ?? ''"
      :question-content="detail.content ?? ''"
      :language="language"
      :question-type="detail.questionType ?? 'TEXT'"
      :judge-context="aiJudgeContext"
      :sample-cases="aiSampleCases"
      :run-result="aiRunResult"
      :get-code="currentEditorCode"
      @insert-code="onCopilotInsertCode"
    />
  </div>
</template>

<style scoped>
.solve-page {
  position: relative;
  isolation: isolate;
  width: min(1700px, calc(100vw - 30px));
  margin: 0 auto;
  padding: 26px 12px 50px;
  transition:
    color 0.3s ease,
    background-color 0.3s ease,
    border-color 0.3s ease;
}

.workspace-mobile-tabs {
  display: flex;
  gap: 8px;
  margin-top: 14px;
  padding: 6px;
  border-radius: 14px;
  border: 1px solid var(--app-border);
  background: color-mix(in srgb, var(--app-surface) 92%, #ffffff 8%);
  box-shadow: var(--app-card-shadow);
}

.workspace-mobile-tabs__btn {
  flex: 1;
  border: 0;
  border-radius: 10px;
  min-height: 42px;
  font-weight: 700;
  font-size: 14px;
  cursor: pointer;
  color: var(--app-text-subtle);
  background: transparent;
  transition:
    background 0.22s ease,
    color 0.22s ease,
    transform 0.15s ease;
}

.workspace-mobile-tabs__btn--active {
  color: #fff;
  background: linear-gradient(130deg, color-mix(in srgb, var(--app-accent) 82%, #2563eb), #1d4ed8);
  box-shadow: 0 8px 18px color-mix(in srgb, var(--app-accent) 22%, transparent);
}

.workspace-mobile-tabs__btn:focus-visible {
  outline: 2px solid var(--app-accent);
  outline-offset: 2px;
}

.solve-page::before,
.solve-page::after {
  content: '';
  position: absolute;
  z-index: -1;
  pointer-events: none;
  border-radius: 999px;
  filter: blur(48px);
  opacity: 0.38;
}

.solve-page::before {
  width: 300px;
  height: 300px;
  top: 50px;
  left: -80px;
  background: radial-gradient(circle, color-mix(in srgb, var(--app-accent) 70%, transparent), transparent 72%);
}

.solve-page::after {
  width: 320px;
  height: 320px;
  top: 220px;
  right: -90px;
  background: radial-gradient(circle, color-mix(in srgb, var(--app-success) 68%, transparent), transparent 72%);
}

.panel {
  border: 1px solid var(--app-border);
  background: linear-gradient(145deg, color-mix(in srgb, var(--app-surface) 94%, #ffffff 6%), var(--app-surface));
  box-shadow: var(--app-card-shadow);
  border-radius: 20px;
  backdrop-filter: blur(16px);
  -webkit-backdrop-filter: blur(16px);
  transition:
    border-color 0.22s ease,
    box-shadow 0.22s ease,
    transform 0.22s ease,
    background 0.3s ease,
    color 0.3s ease;
}

.panel:hover {
  border-color: color-mix(in srgb, var(--app-accent) 40%, var(--app-border));
  box-shadow:
    0 18px 38px rgba(15, 23, 42, 0.12),
    0 2px 10px rgba(15, 23, 42, 0.08);
}

.topbar {
  padding: 18px;
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 14px;
}

.topbar-left {
  display: flex;
  align-items: center;
  gap: 12px;
}

.back-btn,
.ghost-btn {
  border-radius: 12px;
  min-height: 36px;
}

.back-btn {
  border: 1px solid color-mix(in srgb, var(--app-accent) 40%, var(--app-border));
  background: color-mix(in srgb, var(--app-surface) 84%, #ffffff 16%);
}

.ghost-btn:hover {
  transform: translateY(-1px);
}

.ghost-btn:focus-visible {
  outline: 2px solid var(--app-accent);
  outline-offset: 2px;
}

.title-wrap h1 {
  margin: 0;
  font-size: 24px;
  line-height: 1.2;
}

.kicker {
  margin: 0 0 4px;
  color: var(--app-accent);
  font-size: 12px;
  letter-spacing: 0.12em;
  text-transform: uppercase;
  font-weight: 700;
}

.stat-chips {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
  align-items: center;
}

.chip {
  border: 1px solid var(--app-border);
  border-radius: 999px;
  padding: 6px 11px;
  color: var(--app-text-subtle);
  font-size: 12px;
  background: color-mix(in srgb, var(--app-surface) 88%, #ffffff 12%);
  font-weight: 600;
}

.chip--contest {
  border-color: color-mix(in srgb, var(--app-accent) 45%, var(--app-border));
  color: var(--app-accent);
  background: color-mix(in srgb, var(--app-accent) 12%, var(--app-surface));
}

.chip--practice {
  border-style: dashed;
  opacity: 0.92;
}

.chip-link {
  border: none;
  border-radius: 999px;
  padding: 6px 11px;
  font-size: 12px;
  font-weight: 700;
  cursor: pointer;
  background: transparent;
  color: var(--app-text-subtle);
  text-decoration: underline;
  text-underline-offset: 3px;
}

.chip-link:hover {
  color: var(--app-accent);
}

.contest-clock {
  position: relative;
  margin-top: 14px;
  padding: 22px 22px 20px;
  overflow: hidden;
  border-radius: var(--app-radius-lg);
  border: 1px solid color-mix(in srgb, var(--app-accent) 32%, var(--app-border));
  background:
    linear-gradient(
      155deg,
      color-mix(in srgb, var(--app-surface) 88%, transparent) 0%,
      color-mix(in srgb, var(--app-accent) 9%, var(--app-surface)) 42%,
      color-mix(in srgb, var(--app-accent-2) 5%, var(--app-surface)) 100%
    ),
    var(--app-surface);
  backdrop-filter: blur(14px);
  -webkit-backdrop-filter: blur(14px);
  box-shadow:
    var(--app-card-shadow),
    0 0 0 1px color-mix(in srgb, #ffffff 55%, transparent) inset,
    0 -24px 48px color-mix(in srgb, var(--app-accent) 12%, transparent);
}

.contest-clock__grid {
  pointer-events: none;
  position: absolute;
  inset: 0;
  opacity: 0.35;
  background-image:
    linear-gradient(color-mix(in srgb, var(--app-accent) 22%, transparent) 1px, transparent 1px),
    linear-gradient(90deg, color-mix(in srgb, var(--app-accent) 22%, transparent) 1px, transparent 1px);
  background-size: 28px 28px;
  mask-image: radial-gradient(ellipse 80% 70% at 70% 35%, #000 22%, transparent 72%);
  -webkit-mask-image: radial-gradient(ellipse 80% 70% at 70% 35%, #000 22%, transparent 72%);
}

.contest-clock__glow {
  pointer-events: none;
  position: absolute;
  border-radius: 50%;
  filter: blur(42px);
  animation: contest-clock-glow-drift 14s ease-in-out infinite alternate;
}

.contest-clock__glow--a {
  width: min(62vw, 420px);
  height: min(62vw, 420px);
  top: -45%;
  right: -8%;
  background: radial-gradient(
    closest-side,
    color-mix(in srgb, var(--app-accent) 42%, transparent),
    transparent 72%
  );
  opacity: 0.55;
}

.contest-clock__glow--b {
  width: min(48vw, 280px);
  height: min(48vw, 280px);
  bottom: -35%;
  left: -6%;
  background: radial-gradient(
    closest-side,
    color-mix(in srgb, var(--app-accent-2) 28%, transparent),
    transparent 70%
  );
  opacity: 0.4;
  animation-delay: -4s;
}

@keyframes contest-clock-glow-drift {
  from {
    transform: translate(0, 0) scale(1);
    opacity: 0.42;
  }
  to {
    transform: translate(-18px, 12px) scale(1.08);
    opacity: 0.62;
  }
}

.contest-clock__scan {
  pointer-events: none;
  position: absolute;
  inset: 0;
  background: linear-gradient(
    transparent 0%,
    color-mix(in srgb, var(--app-accent) 8%, transparent) 48%,
    transparent 100%
  );
  background-size: 100% 160%;
  animation: contest-clock-scan 7s linear infinite;
  opacity: 0.45;
}

@keyframes contest-clock-scan {
  from {
    background-position: 0 -80%;
  }
  to {
    background-position: 0 80%;
  }
}

.contest-clock__layout {
  position: relative;
  z-index: 1;
  display: grid;
  gap: 22px 28px;
  align-items: center;
}

@media (min-width: 880px) {
  .contest-clock__layout {
    grid-template-columns: minmax(0, 1fr) auto;
  }
}

.contest-clock__intro {
  min-width: 0;
}

.contest-clock__head {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px 12px;
  margin-bottom: 10px;
}

.contest-clock__badge {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  padding: 5px 12px 5px 10px;
  border-radius: 999px;
  font-size: 10px;
  font-weight: 800;
  letter-spacing: 0.14em;
  text-transform: uppercase;
  color: color-mix(in srgb, var(--app-accent) 92%, #022);
  background: linear-gradient(
    135deg,
    color-mix(in srgb, var(--app-accent) 18%, var(--app-surface)),
    color-mix(in srgb, var(--app-surface) 75%, #fff 25%)
  );
  border: 1px solid color-mix(in srgb, var(--app-accent) 42%, var(--app-border));
  box-shadow: 0 4px 14px color-mix(in srgb, var(--app-accent) 14%, transparent);
}

.contest-clock__badge-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: var(--app-accent);
  box-shadow: 0 0 12px color-mix(in srgb, var(--app-accent) 70%, transparent);
  animation: contest-clock-dot 1.8s ease-in-out infinite;
}

@keyframes contest-clock-dot {
  0%,
  100% {
    opacity: 1;
    transform: scale(1);
  }
  50% {
    opacity: 0.55;
    transform: scale(0.92);
  }
}

.contest-clock__badge-cn {
  font-size: 12px;
  font-weight: 700;
  letter-spacing: 0.02em;
  color: var(--app-text-subtle);
}

.contest-clock__title {
  margin: 0 0 10px;
  font-family: var(--app-font-body);
  font-size: clamp(1.15rem, 2.4vw, 1.45rem);
  font-weight: 800;
  letter-spacing: -0.03em;
  line-height: 1.25;
  color: var(--app-text);
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
}

.contest-clock__hint {
  margin: 0 0 14px;
  font-size: 13px;
  line-height: 1.65;
  color: var(--app-text-muted);
  max-width: 52ch;
}

.contest-clock__progress {
  height: 7px;
  border-radius: 999px;
  background: color-mix(in srgb, var(--app-border) 75%, transparent);
  overflow: hidden;
}

.contest-clock__progress--bar {
  margin-top: 0;
}

.contest-clock__progress-fill {
  height: 100%;
  border-radius: inherit;
  background: linear-gradient(
    90deg,
    color-mix(in srgb, var(--app-accent) 78%, #5eead4),
    var(--app-accent),
    color-mix(in srgb, var(--app-accent-2) 55%, var(--app-accent))
  );
  transition: width 0.95s cubic-bezier(0.22, 1, 0.36, 1);
  box-shadow: 0 0 18px color-mix(in srgb, var(--app-accent) 35%, transparent);
}

.contest-clock__progress-label {
  margin: 8px 0 0;
  font-size: 11px;
  font-weight: 600;
  letter-spacing: 0.06em;
  text-transform: uppercase;
  color: var(--app-text-subtle);
}

.contest-clock__progress-label strong {
  color: var(--app-accent);
  font-weight: 800;
}

.contest-clock__stage {
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 18px;
}

@media (min-width: 880px) {
  .contest-clock__stage {
    flex-direction: row;
    gap: 22px;
  }
}

.contest-clock__ring-wrap {
  position: relative;
  width: 132px;
  height: 132px;
  flex-shrink: 0;
}

.contest-clock__svg {
  width: 100%;
  height: 100%;
  filter: drop-shadow(0 8px 22px color-mix(in srgb, var(--app-accent) 28%, transparent));
}

.contest-clock__ring-bg {
  fill: none;
  stroke: color-mix(in srgb, var(--app-border) 78%, var(--app-accent) 8%);
  stroke-width: 5;
}

.contest-clock__ring-arc {
  fill: none;
  stroke: color-mix(in srgb, var(--app-accent) 88%, #22d3ee);
  stroke-width: 5;
  stroke-linecap: round;
  transition: stroke-dashoffset 0.85s cubic-bezier(0.22, 1, 0.36, 1), stroke 0.3s ease;
}

.contest-clock__ring-caption {
  position: absolute;
  inset: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  pointer-events: none;
}

.contest-clock__ring-pct {
  font-family: ui-monospace, 'Cascadia Code', 'SF Mono', Menlo, sans-serif;
  font-size: 22px;
  font-weight: 800;
  font-variant-numeric: tabular-nums;
  color: var(--app-text);
  line-height: 1.1;
}

.contest-clock__ring-sub {
  margin-top: 2px;
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.12em;
  color: var(--app-text-subtle);
}

.contest-clock__digits {
  display: flex;
  align-items: flex-end;
  gap: 8px;
  flex-wrap: wrap;
  justify-content: center;
}

.contest-clock__unit {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
}

.contest-clock__unit-label {
  font-size: 10px;
  font-weight: 800;
  color: var(--app-text-subtle);
  letter-spacing: 0.14em;
}

.contest-clock__block {
  min-width: 3.4ch;
  padding: 12px 14px;
  border-radius: 16px;
  font-family: ui-monospace, 'Cascadia Code', 'SF Mono', Menlo, Consolas, monospace;
  font-size: clamp(26px, 5vw, 34px);
  font-weight: 800;
  font-variant-numeric: tabular-nums;
  letter-spacing: 0.06em;
  line-height: 1;
  color: var(--app-text);
  background: linear-gradient(
    165deg,
    color-mix(in srgb, var(--app-surface-strong) 92%, #ffffff 8%) 0%,
    color-mix(in srgb, var(--app-surface) 78%, #ffffff 22%) 100%
  );
  border: 1px solid color-mix(in srgb, var(--app-accent) 22%, var(--app-border));
  box-shadow:
    inset 0 1px 0 rgba(255, 255, 255, 0.72),
    0 14px 28px rgba(15, 23, 42, 0.09),
    0 0 0 1px color-mix(in srgb, var(--app-accent) 12%, transparent);
}

.contest-clock__sep {
  font-size: clamp(24px, 4.5vw, 32px);
  font-weight: 800;
  color: color-mix(in srgb, var(--app-text-subtle) 65%, var(--app-accent) 35%);
  padding-bottom: 10px;
  opacity: 0.9;
  text-shadow: 0 0 22px color-mix(in srgb, var(--app-accent) 25%, transparent);
}

.contest-clock--soon .contest-clock__block {
  border-color: color-mix(in srgb, var(--app-accent) 48%, var(--app-border));
  box-shadow:
    inset 0 1px 0 rgba(255, 255, 255, 0.65),
    0 14px 30px color-mix(in srgb, var(--app-accent) 14%, transparent);
}

.contest-clock--critical .contest-clock__block {
  animation: contest-clock-pulse 1.15s ease-in-out infinite;
  border-color: color-mix(in srgb, #f97316 58%, var(--app-border));
  box-shadow:
    inset 0 1px 0 rgba(255, 255, 255, 0.52),
    0 0 0 1px color-mix(in srgb, #f97316 38%, transparent),
    0 14px 34px rgba(249, 115, 22, 0.22);
}

.contest-clock--critical .contest-clock__progress-fill {
  background: linear-gradient(90deg, #fdba74, #f97316, #ea580c);
  box-shadow: 0 0 16px rgba(249, 115, 22, 0.35);
}

.contest-clock--critical .contest-clock__ring-arc {
  stroke: color-mix(in srgb, #fb923c 75%, #f97316);
}

.contest-clock--critical .contest-clock__badge {
  color: #9a3412;
  border-color: color-mix(in srgb, #fdba74 55%, var(--app-border));
  background: linear-gradient(
    135deg,
    color-mix(in srgb, #ffedd5 92%, var(--app-surface)),
    color-mix(in srgb, #fed7aa 28%, var(--app-surface))
  );
}

@keyframes contest-clock-pulse {
  0%,
  100% {
    transform: translateY(0);
  }
  50% {
    transform: translateY(-2px);
  }
}

.contest-clock--ended .contest-clock__digits,
.contest-clock--ended .contest-clock__ring-wrap {
  opacity: 0.5;
  filter: grayscale(0.35);
}

.content-grid {
  margin-top: 14px;
  display: grid;
  gap: 10px;
  align-items: stretch;
  min-height: calc(100vh - 190px);
}

.content-grid--tablet-editor-first .problem-panel {
  order: 2;
}

.content-grid--tablet-editor-first .answer-panel {
  order: 1;
}

.content-grid--mobile .problem-panel,
.content-grid--mobile .answer-panel {
  max-height: none;
}

.splitter {
  position: relative;
  border-radius: 999px;
  cursor: col-resize;
  background: transparent;
  opacity: 1;
  transition: opacity 0.2s ease, box-shadow 0.2s ease;
}

.splitter::after {
  content: '';
  position: absolute;
  left: 50%;
  top: 8%;
  bottom: 8%;
  width: 1px;
  transform: translateX(-50%);
  border-radius: 999px;
  background: linear-gradient(
    180deg,
    transparent,
    color-mix(in srgb, var(--app-accent) 55%, var(--app-border)),
    transparent
  );
  box-shadow:
    0 0 0 1px color-mix(in srgb, var(--app-border) 70%, transparent),
    4px 0 14px color-mix(in srgb, var(--app-accent) 12%, transparent);
}

.splitter:hover::after {
  background: linear-gradient(
    180deg,
    transparent,
    color-mix(in srgb, var(--app-accent) 85%, #38bdf8),
    transparent
  );
  box-shadow:
    0 0 0 1px color-mix(in srgb, var(--app-accent) 35%, transparent),
    0 0 18px color-mix(in srgb, var(--app-accent) 22%, transparent);
}

.problem-panel,
.answer-panel {
  padding: 18px;
}

.problem-panel {
  display: flex;
  flex-direction: column;
  max-height: calc(100vh - 170px);
  overflow: hidden;
}

.oj-card {
  border-radius: 12px;
  border: 1px solid color-mix(in srgb, var(--app-border) 92%, var(--app-accent) 8%);
  padding: 14px 14px 16px;
  margin-bottom: 14px;
  background: color-mix(in srgb, var(--app-surface) 93%, #ffffff 7%);
  box-shadow:
    0 1px 0 color-mix(in srgb, #ffffff 55%, transparent) inset,
    0 10px 26px rgba(15, 23, 42, 0.06);
  transition:
    transform 0.22s ease,
    box-shadow 0.22s ease,
    border-color 0.22s ease;
}

.oj-card:hover {
  transform: translateY(-2px);
  border-color: color-mix(in srgb, var(--app-accent) 28%, var(--app-border));
  box-shadow:
    0 1px 0 color-mix(in srgb, #ffffff 55%, transparent) inset,
    0 16px 34px rgba(15, 23, 42, 0.09);
}

.tag--pill {
  font-weight: 600;
  border-radius: 999px;
  padding: 4px 12px;
  border: 1px solid color-mix(in srgb, var(--app-accent) 35%, var(--app-border));
  background: color-mix(in srgb, var(--app-accent) 10%, var(--app-surface));
  color: var(--app-text-main);
}

.problem-body-scroll {
  flex: 1;
  overflow-y: auto;
  overscroll-behavior: contain;
  padding-right: 4px;
}

.problem-body-scroll::-webkit-scrollbar {
  width: 8px;
}

.problem-body-scroll::-webkit-scrollbar-thumb {
  background: color-mix(in srgb, var(--app-accent) 40%, transparent);
  border-radius: 999px;
}

.problem-body-scroll::-webkit-scrollbar-track {
  background: color-mix(in srgb, var(--app-surface) 84%, transparent);
}

.problem-tabs {
  display: inline-flex;
  border: 1px solid var(--app-border);
  border-radius: 12px;
  padding: 4px;
  gap: 4px;
  margin-bottom: 12px;
}

.tab-btn {
  border: 0;
  background: transparent;
  color: var(--app-text-subtle);
  border-radius: 8px;
  min-height: 34px;
  padding: 0 14px;
  cursor: pointer;
  font-weight: 600;
  transition: background 0.2s ease, color 0.2s ease, transform 0.2s ease;
}

.tab-btn.active {
  background: linear-gradient(130deg, color-mix(in srgb, var(--app-accent) 76%, #2563eb), #1d4ed8);
  color: #fff;
}

.tab-btn:focus-visible {
  outline: 2px solid var(--app-accent);
  outline-offset: 2px;
}

.anchor-nav {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
  margin-bottom: 10px;
}

.anchor-btn {
  border: 1px solid var(--app-border);
  border-radius: 999px;
  background: color-mix(in srgb, var(--app-surface) 88%, #ffffff 12%);
  min-height: 30px;
  padding: 0 10px;
  cursor: pointer;
  color: var(--app-text-subtle);
  font-size: 12px;
  transition: border-color 0.2s ease, color 0.2s ease, transform 0.2s ease;
}

.anchor-btn:hover {
  border-color: color-mix(in srgb, var(--app-accent) 50%, var(--app-border));
  color: var(--app-text-main);
  transform: translateY(-1px);
}

.tags {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  margin-bottom: 12px;
}

.vision-problem {
  margin: 14px 0 18px;
  padding: 14px;
  border: 1px solid color-mix(in srgb, #0ea5e9 38%, var(--app-border));
  border-radius: 14px;
  background: color-mix(in srgb, #0ea5e9 7%, var(--app-surface));
}

.vision-problem__head {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 10px;
  color: var(--app-text-main);
}

.vision-problem__head span,
.vision-problem p {
  color: var(--app-text-muted);
  font-size: 12px;
}

.vision-problem__image {
  display: block;
  width: 100%;
  max-height: 420px;
  object-fit: contain;
  border-radius: 10px;
  background: #0f172a;
}

.tag {
  border: 1px solid var(--app-border);
  border-radius: 999px;
  padding: 3px 10px;
  font-size: 12px;
  background: color-mix(in srgb, var(--app-surface) 84%, #ffffff 16%);
}

.statement {
  min-height: 360px;
  max-height: calc(100vh - 330px);
  overflow: auto;
  line-height: 1.8;
  color: var(--app-text-main);
  border: 1px solid var(--app-border);
  border-radius: 12px;
  padding: 14px;
  background: color-mix(in srgb, var(--app-surface) 92%, #ffffff 8%);
}

.oj-card--statement .statement {
  margin-top: 8px;
  border-style: dashed;
  border-color: color-mix(in srgb, var(--app-border) 85%, transparent);
  background: color-mix(in srgb, var(--app-surface) 96%, #ffffff 4%);
}

.oj-statement :deep(h1),
.oj-statement :deep(h2),
.oj-statement :deep(h3) {
  margin: 18px 0 10px;
  color: var(--app-text-main);
  font-weight: 800;
  line-height: 1.3;
  padding-bottom: 8px;
  border-bottom: 1px solid color-mix(in srgb, var(--app-border) 70%, transparent);
}

.oj-statement :deep(h1) {
  font-size: 26px;
  border-bottom: 3px solid color-mix(in srgb, var(--app-accent) 72%, var(--app-border));
  padding-bottom: 10px;
}

.oj-statement :deep(h2) {
  font-size: 22px;
}

.oj-statement :deep(h3) {
  font-size: 18px;
}

.oj-statement :deep(p) {
  margin: 10px 0;
  font-size: 17px;
  line-height: 1.85;
  color: var(--app-text-main);
}

.oj-statement :deep(ul) {
  margin: 10px 0 14px 1.4em;
  padding: 0;
}

.oj-statement :deep(li) {
  margin: 8px 0;
  line-height: 1.8;
  color: var(--app-text-main);
}

.oj-statement :deep(code) {
  display: inline-block;
  padding: 1px 7px;
  border-radius: 8px;
  border: 1px solid var(--app-border);
  background: color-mix(in srgb, var(--app-surface) 80%, #ffffff 20%);
  font-family: 'JetBrains Mono', 'Cascadia Code', Consolas, monospace;
  font-size: 0.92em;
}

.statement::-webkit-scrollbar {
  width: 8px;
}

.statement::-webkit-scrollbar-thumb {
  background: color-mix(in srgb, var(--app-accent) 38%, transparent);
  border-radius: 999px;
}

.judge-cases {
  margin-top: 0;
}

.judge-cases__head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 12px;
  gap: 10px;
}

.judge-cases__head h3 {
  margin: 0;
  font-size: 15px;
  color: var(--app-text-main);
}

.judge-cases__head span {
  font-size: 12px;
  color: var(--app-text-subtle);
}

.judge-cases__accordion {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.judge-cases__list {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
}

.judge-cases__empty {
  border: 1px dashed var(--app-border);
  border-radius: 10px;
  padding: 10px;
  font-size: 12px;
  color: var(--app-text-subtle);
  background: color-mix(in srgb, var(--app-surface) 92%, #ffffff 8%);
}

.judge-case-card {
  border: 1px solid var(--app-border);
  border-radius: 12px;
  padding: 10px 12px;
  background: color-mix(in srgb, var(--app-surface) 88%, #ffffff 12%);
  transition: transform 0.2s ease, border-color 0.2s ease;
}

.judge-case-card--flat {
  padding-top: 12px;
}

.judge-case-card:hover {
  transform: translateY(-1px);
  border-color: color-mix(in srgb, var(--app-accent) 45%, var(--app-border));
}

.judge-case-card__toggle {
  display: flex;
  align-items: center;
  justify-content: space-between;
  width: 100%;
  gap: 10px;
  padding: 8px 4px 10px;
  margin: -4px -4px 4px;
  border: 0;
  border-radius: 8px;
  background: transparent;
  cursor: pointer;
  color: var(--app-text-main);
  font: inherit;
  font-weight: 700;
  font-size: 13px;
  text-align: left;
  transition: background 0.18s ease;
}

.judge-case-card__toggle:hover {
  background: color-mix(in srgb, var(--app-accent) 8%, var(--app-surface));
}

.judge-case-card__toggle:focus-visible {
  outline: 2px solid var(--app-accent);
  outline-offset: 2px;
}

.judge-case-card__chev {
  font-size: 12px;
  color: var(--app-text-subtle);
}

.judge-case-card__title {
  margin: 0 0 10px;
  font-size: 13px;
  font-weight: 700;
  color: var(--app-text-main);
}

.judge-case-card__body {
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.judge-case-io {
  border-radius: 10px;
  padding: 8px 10px;
  border: 1px solid transparent;
}

.judge-case-io--in {
  border-color: color-mix(in srgb, #3b82f6 42%, var(--app-border));
  background: color-mix(in srgb, #3b82f6 10%, var(--app-surface));
}

.judge-case-io--out {
  border-color: color-mix(in srgb, #22c55e 40%, var(--app-border));
  background: color-mix(in srgb, #22c55e 10%, var(--app-surface));
}

.judge-case-io__row {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 6px;
  gap: 8px;
}

.judge-case-io__label {
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.06em;
  text-transform: uppercase;
  color: var(--app-text-subtle);
}

.judge-case-io__copy {
  border: 1px solid color-mix(in srgb, var(--app-border) 90%, transparent);
  border-radius: 8px;
  padding: 2px 10px;
  font-size: 11px;
  font-weight: 600;
  cursor: pointer;
  background: color-mix(in srgb, var(--app-surface) 92%, #fff 8%);
  color: var(--app-text-main);
}

.judge-case-io__copy:hover {
  border-color: color-mix(in srgb, var(--app-accent) 45%, var(--app-border));
  color: var(--app-accent);
}

.judge-case-io__copy:focus-visible {
  outline: 2px solid var(--app-accent);
  outline-offset: 2px;
}

.judge-case-io__pre {
  margin: 0;
  white-space: pre-wrap;
  word-break: break-word;
  font-size: 12px;
  line-height: 1.55;
  font-family: 'JetBrains Mono', 'Cascadia Code', Consolas, monospace;
  color: var(--app-text-main);
}

.meta-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 10px;
}

.meta-item {
  border: 1px solid var(--app-border);
  border-radius: 14px;
  padding: 12px;
  background: color-mix(in srgb, var(--app-surface) 90%, #ffffff 10%);
}

.meta-item span {
  display: block;
  font-size: 12px;
  color: var(--app-text-subtle);
}

.meta-item strong {
  display: block;
  margin-top: 6px;
  color: var(--app-text-main);
  font-size: 15px;
}

.judge-config {
  margin-top: 0;
}

.judge-config__head h3 {
  margin: 0 0 12px;
  font-size: 15px;
  color: var(--app-text-main);
}

.judge-config__grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 10px;
}

.judge-config__item.judge-limit-card {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 4px;
  border: 1px solid var(--app-border);
  border-radius: 12px;
  padding: 12px 12px 14px;
  background: linear-gradient(
    165deg,
    color-mix(in srgb, var(--app-surface) 92%, #ffffff 8%),
    color-mix(in srgb, var(--app-surface) 88%, #ffffff 12%)
  );
  box-shadow: 0 1px 0 color-mix(in srgb, #ffffff 70%, transparent) inset;
  transition: transform 0.2s ease, border-color 0.2s ease;
}

.judge-limit-card:hover {
  transform: translateY(-2px);
  border-color: color-mix(in srgb, var(--app-accent) 35%, var(--app-border));
}

.judge-limit-card__icon {
  font-size: 18px;
  line-height: 1;
  margin-bottom: 2px;
}

.judge-limit-card__key {
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.05em;
  text-transform: uppercase;
  color: var(--app-text-subtle);
}

.judge-limit-card__val {
  margin: 4px 0 0;
  display: flex;
  align-items: baseline;
  gap: 4px;
}

.judge-limit-card__val strong {
  font-size: 22px;
  font-weight: 800;
  font-variant-numeric: tabular-nums;
  letter-spacing: -0.02em;
  color: var(--app-text-main);
  line-height: 1.1;
}

.judge-limit-card__unit {
  font-size: 12px;
  font-weight: 600;
  color: var(--app-text-muted);
}

.judge-limit-card__raw {
  font-size: 11px;
  color: var(--app-text-muted);
}

.judge-config__empty {
  border: 1px dashed var(--app-border);
  border-radius: 10px;
  padding: 10px;
  font-size: 12px;
  color: var(--app-text-subtle);
}

.answer-panel--stack {
  display: flex;
  flex-direction: column;
  min-height: 0;
  max-height: calc(100vh - 170px);
}

.answer-panel__editor-section {
  flex: 1 1 78%;
  min-height: 0;
  display: flex;
  flex-direction: column;
  gap: 0;
}

.answer-panel__footer {
  flex: 0 1 auto;
  /* 运行结果可能包含多组样例，需整块可滚动；勿压缩子项高度以免裁切 */
  max-height: min(52vh, 560px);
  overflow-x: hidden;
  overflow-y: auto;
  overscroll-behavior: contain;
  scrollbar-gutter: stable;
  display: flex;
  flex-direction: column;
  gap: 0;
  min-height: 0;
}

.answer-panel__footer::-webkit-scrollbar {
  width: 8px;
}

.answer-panel__footer::-webkit-scrollbar-thumb {
  background: color-mix(in srgb, var(--app-accent) 42%, transparent);
  border-radius: 999px;
}

.answer-panel__footer::-webkit-scrollbar-track {
  background: color-mix(in srgb, var(--app-surface) 82%, transparent);
}

.editor-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 10px;
  gap: 10px;
  padding-bottom: 10px;
  flex-shrink: 0;
  border-bottom: 1px dashed color-mix(in srgb, var(--app-border) 80%, transparent);
}

.editor-head h2 {
  margin: 0;
  font-size: 17px;
  font-weight: 800;
  letter-spacing: -0.02em;
}

.editor-hint-inline {
  margin: 0 0 8px;
  font-size: 11px;
  line-height: 1.5;
  color: var(--app-text-subtle);
}

.editor-hint-inline--warn {
  color: #b45309;
}

.editor-hint-inline kbd {
  display: inline-block;
  margin: 0 1px;
  padding: 0 4px;
  font-size: 10px;
  font-family: ui-monospace, 'JetBrains Mono', monospace;
  border-radius: 3px;
  border: 1px solid var(--app-border);
  background: color-mix(in srgb, var(--app-surface) 90%, #fff 10%);
  color: var(--app-text-main);
  vertical-align: baseline;
}

.editor-tools {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-wrap: wrap;
  justify-content: flex-end;
}

.editor-icon-btn {
  min-width: 38px;
  padding: 0 10px;
  font-size: 15px;
  line-height: 1;
}

.editor-icon-btn--on {
  border-color: color-mix(in srgb, var(--app-accent) 55%, var(--app-border)) !important;
  color: var(--app-accent) !important;
  background: color-mix(in srgb, var(--app-accent) 12%, var(--app-surface)) !important;
}

.code-area {
  flex: 1 1 auto;
  min-height: 320px;
  border-radius: 12px;
  overflow: hidden;
  border: 1px solid var(--app-border);
  transition: border-color 0.22s ease, box-shadow 0.22s ease;
}

.code-area :deep(.monaco-editor .margin) {
  border-right: 1px solid color-mix(in srgb, var(--app-border) 88%, transparent);
}

/* 语法错误：整行浅红 + 左侧竖条（与波浪线 markers 叠加） */
.code-area :deep(.myoj-syntax-line-err) {
  background-color: rgba(239, 68, 68, 0.14) !important;
  border-left: 3px solid rgba(239, 68, 68, 0.82) !important;
  box-sizing: border-box;
}

.code-area :deep(.myoj-syntax-line-warn) {
  background-color: rgba(245, 158, 11, 0.12) !important;
  border-left: 3px solid rgba(245, 158, 11, 0.65) !important;
  box-sizing: border-box;
}

.code-area :deep(.myoj-syntax-glyph-err),
.code-area :deep(.myoj-syntax-glyph-warn) {
  width: 10px !important;
  border-radius: 50%;
  margin-left: 2px;
  margin-top: 6px;
}

.code-area :deep(.myoj-syntax-glyph-err) {
  background: rgba(239, 68, 68, 0.95) !important;
}

.code-area :deep(.myoj-syntax-glyph-warn) {
  background: rgba(245, 158, 11, 0.9) !important;
}

.syntax-issues-panel {
  margin-top: 10px;
  border-radius: 10px;
  border: 1px solid color-mix(in srgb, #ef4444 35%, var(--app-border));
  background: color-mix(in srgb, rgba(239, 68, 68, 0.06) 100%, var(--app-surface));
  overflow: hidden;
  max-height: min(200px, 28vh);
  display: flex;
  flex-direction: column;
}

.syntax-issues-panel__bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 8px 12px;
  background: color-mix(in srgb, rgba(239, 68, 68, 0.1) 100%, transparent);
  border-bottom: 1px solid color-mix(in srgb, var(--app-border) 75%, transparent);
  flex-shrink: 0;
}

.syntax-issues-panel__title {
  font-size: 12px;
  font-weight: 700;
  color: var(--app-text-main);
}

.syntax-issues-panel__count {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 22px;
  padding: 0 6px;
  margin-right: 4px;
  border-radius: 999px;
  font-size: 11px;
  font-weight: 800;
  color: #fff;
  background: linear-gradient(135deg, #dc2626, #b91c1c);
}

.syntax-issues-panel__hint {
  font-size: 11px;
  color: var(--app-text-muted);
}

.syntax-issues-panel__list {
  margin: 0;
  padding: 6px 0;
  list-style: none;
  overflow-y: auto;
}

.syntax-issues-panel__item {
  padding: 8px 12px;
  cursor: pointer;
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 4px;
  border-left: 3px solid transparent;
  transition: background 0.15s ease;
}

.syntax-issues-panel__item:hover {
  background: color-mix(in srgb, var(--app-accent) 8%, var(--app-surface));
}

.syntax-issues-panel__item:focus-visible {
  outline: 2px solid var(--app-accent);
  outline-offset: -2px;
}

.syntax-issues-panel__item--warn {
  border-left-color: rgba(245, 158, 11, 0.75);
}

.syntax-issues-panel__item:not(.syntax-issues-panel__item--warn) {
  border-left-color: rgba(239, 68, 68, 0.65);
}

.syntax-issues-panel__loc {
  font-size: 11px;
  font-weight: 700;
  font-family: ui-monospace, 'JetBrains Mono', monospace;
  color: #b91c1c;
}

.syntax-issues-panel__item--warn .syntax-issues-panel__loc {
  color: #b45309;
}

.syntax-issues-panel__msg {
  font-size: 12px;
  line-height: 1.45;
  color: var(--app-text-main);
  word-break: break-word;
}

.code-area :deep(.monaco-editor),
.code-area :deep(.overflow-guard) {
  border-radius: 14px;
}

.code-area--fallback :deep(textarea) {
  min-height: 560px;
  font-family: 'JetBrains Mono', 'Cascadia Code', Consolas, monospace;
  font-size: 14px;
  line-height: 1.6;
}

.answer-panel--dark-pro .code-area {
  border: 1px solid #334155;
  box-shadow: inset 0 0 0 1px rgba(148, 163, 184, 0.12);
}

.answer-panel--light-pro .code-area {
  border: 1px solid #cbd5e1;
  box-shadow: inset 0 0 0 1px rgba(148, 163, 184, 0.2);
}

.answer-panel--ocean-pro .code-area {
  border: 1px solid #0e7490;
  box-shadow: inset 0 0 0 1px rgba(34, 211, 238, 0.22);
}

.code-area:focus-within {
  box-shadow: 0 0 0 3px rgba(59, 130, 246, 0.22);
}

.btn-tap {
  transition: transform 0.15s ease;
}

.btn-tap:active {
  transform: scale(0.97);
}

.submit-row {
  margin-top: 10px;
  display: flex;
  justify-content: flex-end;
  gap: 10px;
  padding-top: 12px;
  flex-shrink: 0;
  border-top: 1px dashed color-mix(in srgb, var(--app-border) 80%, transparent);
}

.submit-row :deep(.run-btn) {
  border-radius: 12px;
  min-height: 40px;
  padding: 0 20px;
  font-weight: 600;
  border: 1px solid color-mix(in srgb, var(--app-accent) 45%, var(--app-border));
  background: transparent;
  color: var(--app-text);
  transition:
    transform 0.15s ease,
    border-color 0.2s ease,
    background 0.25s ease,
    box-shadow 0.25s ease;
}

.submit-row :deep(.run-btn:hover) {
  border-color: var(--app-accent);
  color: #fff;
  background: linear-gradient(
    135deg,
    color-mix(in srgb, var(--app-accent) 78%, #2563eb),
    #1d4ed8
  );
  box-shadow: 0 10px 22px color-mix(in srgb, var(--app-accent) 22%, transparent);
  transform: translateY(-1px);
}

.submit-row :deep(.run-btn:focus-visible) {
  outline: 2px solid var(--app-accent);
  outline-offset: 2px;
}

.run-result-shell {
  flex-shrink: 0;
  align-self: stretch;
  margin-top: 12px;
  border-radius: 14px;
  border: 1px solid color-mix(in srgb, var(--app-border) 90%, transparent);
  background: linear-gradient(
    165deg,
    color-mix(in srgb, var(--app-surface) 96%, transparent) 0%,
    color-mix(in srgb, var(--app-surface) 90%, var(--app-border) 10%) 100%
  );
  overflow: hidden;
  box-shadow:
    0 0 0 1px color-mix(in srgb, var(--app-accent) 12%, transparent),
    0 12px 36px color-mix(in srgb, var(--app-border) 35%, transparent);
  transition:
    box-shadow 0.28s ease,
    border-color 0.28s ease;
}

.run-result-shell--collapsed {
  box-shadow:
    0 0 0 1px color-mix(in srgb, var(--app-accent) 18%, transparent),
    0 6px 22px color-mix(in srgb, var(--app-border) 28%, transparent);
}

.run-result-shell__bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 10px 12px;
  background: linear-gradient(
    180deg,
    color-mix(in srgb, var(--app-accent) 7%, transparent) 0%,
    color-mix(in srgb, var(--app-surface) 92%, transparent) 48%,
    color-mix(in srgb, var(--app-surface) 86%, var(--app-border) 14%) 100%
  );
  border-bottom: 1px solid color-mix(in srgb, var(--app-border) 55%, transparent);
  transition: border-color 0.22s ease;
}

.run-result-shell--collapsed .run-result-shell__bar {
  border-bottom: none;
}

.run-result-shell__lead {
  flex: 1;
  min-width: 0;
  display: inline-flex;
  align-items: center;
  gap: 8px;
  margin: 0;
  padding: 6px 8px;
  border: none;
  border-radius: 10px;
  background: transparent;
  cursor: pointer;
  font: inherit;
  color: var(--app-text);
  text-align: left;
  transition: background 0.18s ease;
}

.run-result-shell__lead:hover {
  background: color-mix(in srgb, var(--app-accent) 8%, transparent);
}

.run-result-shell__lead:focus-visible {
  outline: 2px solid var(--app-accent);
  outline-offset: 2px;
}

.run-result-shell__chevron {
  display: inline-flex;
  flex-shrink: 0;
  align-items: center;
  justify-content: center;
  width: 22px;
  height: 22px;
  color: color-mix(in srgb, var(--app-accent) 78%, var(--app-text) 22%);
  opacity: 0.92;
  transition: transform 0.26s cubic-bezier(0.22, 1, 0.36, 1);
}

.run-result-shell__chevron-svg {
  display: block;
  width: 16px;
  height: 16px;
}

.run-result-shell__chevron--collapsed {
  transform: rotate(-90deg);
}

.run-result-shell__heading {
  font-weight: 700;
  font-size: 14px;
  letter-spacing: 0.02em;
}

.run-result-shell__pill {
  flex: 0 1 auto;
  min-width: 0;
  font-size: 12px;
  font-weight: 600;
  padding: 3px 10px;
  border-radius: 999px;
  color: var(--app-text-muted);
  background: color-mix(in srgb, var(--app-border) 35%, transparent);
  max-width: min(42vw, 280px);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.run-result-shell__actions {
  flex-shrink: 0;
}

.run-result-shell__toggle {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  margin: 0;
  padding: 6px 12px 6px 10px;
  border: 1px solid color-mix(in srgb, var(--app-accent) 42%, var(--app-border) 40%);
  border-radius: 999px;
  background: linear-gradient(
    145deg,
    color-mix(in srgb, var(--app-accent) 14%, transparent),
    color-mix(in srgb, var(--app-surface) 82%, var(--app-border) 18%) 55%,
    color-mix(in srgb, var(--app-surface) 72%, var(--app-border) 28%)
  );
  color: color-mix(in srgb, var(--app-text) 92%, var(--app-accent) 8%);
  font-size: 12px;
  font-weight: 600;
  letter-spacing: 0.04em;
  cursor: pointer;
  box-shadow:
    inset 0 1px 0 color-mix(in srgb, var(--app-text) 10%, transparent),
    0 1px 0 color-mix(in srgb, var(--app-accent) 22%, transparent);
  transition:
    border-color 0.2s ease,
    box-shadow 0.22s ease,
    transform 0.18s ease,
    background 0.22s ease;
}

.run-result-shell__toggle:hover {
  border-color: color-mix(in srgb, var(--app-accent) 58%, transparent);
  box-shadow:
    inset 0 1px 0 color-mix(in srgb, var(--app-text) 12%, transparent),
    0 0 0 1px color-mix(in srgb, var(--app-accent) 22%, transparent),
    0 8px 20px color-mix(in srgb, var(--app-accent) 18%, transparent);
  transform: translateY(-1px);
}

.run-result-shell__toggle:active {
  transform: translateY(0);
}

.run-result-shell__toggle:focus-visible {
  outline: 2px solid color-mix(in srgb, var(--app-accent) 72%, var(--app-border) 28%);
  outline-offset: 2px;
}

.run-result-shell__toggle-ico {
  flex-shrink: 0;
  width: 14px;
  height: 14px;
  opacity: 0.88;
}

.run-result-shell__toggle-text {
  line-height: 1;
}

.run-result-collapse-enter-active,
.run-result-collapse-leave-active {
  transition:
    opacity 0.22s ease,
    transform 0.26s cubic-bezier(0.22, 1, 0.36, 1);
}

.run-result-collapse-enter-from,
.run-result-collapse-leave-to {
  opacity: 0;
  transform: translateY(-8px);
}

.run-result {
  margin-top: 12px;
  padding: 14px 16px;
  border-radius: 14px;
  border: 1px solid color-mix(in srgb, var(--app-border) 90%, transparent);
  background: color-mix(in srgb, var(--app-surface) 94%, transparent);
  overflow: auto;
}

.run-result--nested {
  margin-top: 0;
  border: none;
  border-radius: 0 0 13px 13px;
  border-top: 1px solid color-mix(in srgb, var(--app-border) 72%, transparent);
  background: color-mix(in srgb, var(--app-surface) 94%, transparent);
  /* 由 .answer-panel__footer 统一滚动，避免嵌套 overflow 裁切多组样例 */
  overflow: visible;
}

.run-result--slide {
  animation: run-result-slide-in 0.42s cubic-bezier(0.22, 1, 0.36, 1);
}

@keyframes run-result-slide-in {
  from {
    opacity: 0;
    transform: translateY(14px);
  }
  to {
    opacity: 1;
    transform: translateY(0);
  }
}

.run-result__head {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px 12px;
  margin-bottom: 10px;
}

.run-result__title {
  font-weight: 700;
  font-size: 14px;
  color: var(--app-text);
}

.run-result__badge {
  font-size: 12px;
  padding: 2px 10px;
  border-radius: 999px;
  font-weight: 600;
}

.run-result__badge--ok {
  background: rgba(34, 197, 94, 0.18);
  color: #15803d;
}

.run-result__badge--warn {
  background: rgba(245, 158, 11, 0.2);
  color: #b45309;
}

.run-result__badge--soft-warn {
  background: rgba(251, 191, 36, 0.22);
  color: #a16207;
}

.run-result__hints {
  margin: 0 0 12px;
  padding: 10px 12px 10px 28px;
  border-radius: 10px;
  font-size: 12px;
  line-height: 1.55;
  color: var(--app-text);
  background: rgba(251, 191, 36, 0.08);
  border: 1px solid rgba(251, 191, 36, 0.28);
}

.run-result__hints li {
  margin-bottom: 6px;
}

.run-result__hints li:last-child {
  margin-bottom: 0;
}

.run-result__badge--err {
  background: rgba(239, 68, 68, 0.16);
  color: #b91c1c;
}

.run-result__meta {
  font-size: 12px;
  color: var(--app-text-muted);
  font-family: ui-monospace, 'JetBrains Mono', monospace;
}

.run-result__msg {
  margin: 0 0 8px;
  font-size: 13px;
  color: var(--app-text-muted);
  white-space: pre-wrap;
  word-break: break-word;
}

.run-result__hint {
  margin: 0 0 10px;
  font-size: 12px;
  line-height: 1.55;
  color: var(--app-text-muted);
}

.run-result__warn {
  margin: 0 0 8px;
  padding: 8px 10px;
  border-radius: 8px;
  font-size: 12px;
  line-height: 1.5;
  color: #b45309;
  background: rgba(245, 158, 11, 0.14);
  border: 1px solid rgba(245, 158, 11, 0.35);
}

.run-cases {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.run-case {
  border-radius: 12px;
  padding: 12px;
  border: 1px solid color-mix(in srgb, var(--app-border) 85%, transparent);
  background: var(--app-surface-soft);
}

.run-case--fail {
  border-color: rgba(239, 68, 68, 0.35);
}

.run-case__label {
  display: flex;
  align-items: center;
  gap: 8px;
  font-weight: 600;
  font-size: 13px;
  margin-bottom: 10px;
  color: var(--app-text);
}

.run-case__tag {
  font-size: 11px;
  font-weight: 700;
  padding: 1px 8px;
  border-radius: 6px;
  background: rgba(34, 197, 94, 0.2);
  color: #15803d;
}

.run-case--fail .run-case__tag {
  background: rgba(239, 68, 68, 0.18);
  color: #b91c1c;
}

.run-case__grid {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 10px;
}

@media (max-width: 1100px) {
  .run-case__grid {
    grid-template-columns: 1fr;
  }
}

.run-case__k {
  display: block;
  font-size: 11px;
  text-transform: uppercase;
  letter-spacing: 0.06em;
  color: var(--app-text-subtle);
  margin-bottom: 4px;
}

.run-case__pre {
  margin: 0;
  padding: 8px 10px;
  border-radius: 8px;
  font-size: 12px;
  line-height: 1.45;
  font-family: ui-monospace, 'JetBrains Mono', 'Consolas', monospace;
  background: color-mix(in srgb, var(--app-bg) 70%, transparent);
  border: 1px solid color-mix(in srgb, var(--app-border) 70%, transparent);
  white-space: pre-wrap;
  word-break: break-word;
  max-height: 180px;
  overflow: auto;
}

.submit-row :deep(.submit-code-btn.arco-btn-primary) {
  position: relative;
  overflow: hidden;
  border-radius: 12px;
  min-height: 40px;
  padding: 0 22px;
  font-weight: 700;
  letter-spacing: 0.02em;
  border: 1px solid color-mix(in srgb, #6366f1 45%, #2563eb);
  background: linear-gradient(120deg, #312e81 0%, #4338ca 38%, #2563eb 72%, #38bdf8 100%);
  box-shadow:
    0 12px 28px rgba(49, 46, 129, 0.35),
    0 0 0 1px color-mix(in srgb, #ffffff 35%, transparent) inset;
  isolation: isolate;
}

.submit-row :deep(.submit-code-btn.arco-btn-primary)::after {
  content: '';
  position: absolute;
  inset: 0;
  background: linear-gradient(
    105deg,
    transparent 35%,
    color-mix(in srgb, #ffffff 40%, transparent) 50%,
    transparent 65%
  );
  transform: translateX(-120%);
  opacity: 0;
  pointer-events: none;
}

.submit-row :deep(.submit-code-btn.arco-btn-primary:not(:disabled):hover)::after {
  opacity: 1;
  animation: submit-shimmer 0.95s ease forwards;
}

@keyframes submit-shimmer {
  to {
    transform: translateX(120%);
  }
}

.submit-row :deep(.submit-code-btn.arco-btn-primary:hover) {
  transform: translateY(-1px);
  filter: brightness(1.05);
}

.submit-row :deep(.submit-code-btn.arco-btn-primary:focus-visible) {
  outline: 2px solid color-mix(in srgb, #a5b4fc 90%, #fff);
  outline-offset: 2px;
}


.empty-inline {
  margin-top: 12px;
  text-align: center;
  color: var(--app-text-subtle);
}

@media (max-width: 1100px) {
  .content-grid {
    grid-template-columns: 1fr !important;
  }

  .meta-grid {
    grid-template-columns: 1fr;
  }

  .judge-cases__list {
    grid-template-columns: 1fr;
  }

  .judge-config__grid {
    grid-template-columns: 1fr;
  }

  .topbar {
    flex-direction: column;
    align-items: flex-start;
  }

  .statement {
    max-height: 460px;
  }

  .code-area {
    min-height: 420px;
  }
}
</style>
