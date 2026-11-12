<script setup lang="ts">
import { computed, inject, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { Message } from '@arco-design/web-vue'
import { ApiError, Service, type QuestionAddRequest, type QuestionUpdateRequest } from '@generated'
import { useRoute, useRouter } from 'vue-router'
import { getBackendErrorMessage } from '@/api/httpError'
import { isResultSuccess } from '@/api/result'
import { mapApiError } from '@/api/mapApiError'
import { LAYOUT_THEME_KEY } from '@/config/theme-injection'
import { AUTH_STORAGE_KEY } from '@/config/auth-storage'
import { API_BASE_URL } from '@/config/api'
import axios from 'axios'
import { PROBLEM_DIFFICULTIES, PROBLEM_KNOWLEDGE_TAGS } from '@/config/problemTaxonomy'
import AiModelPicker from '@/components/study/ai/AiModelPicker.vue'
import { useAiModelsStore } from '@/stores/aiModels'

const route = useRoute()
const router = useRouter()
const aiModels = useAiModelsStore()
const layoutTheme = inject(LAYOUT_THEME_KEY, null)
const themeLight = computed(() => (layoutTheme ? layoutTheme.value === 'light' : true))
const DRAFT_KEY = 'myoj.problem.editor.draft.v1'
const DRAFT_TTL_MS = 15 * 60 * 1000 // 15分钟自动清理这个草稿

const isEditMode = computed(() => Boolean(route.params.id))
const submitting = ref(false)
const preflightBusy = ref(false)
const preflightResult = ref<{ ready: boolean; checks: Array<{ name: string; passed: boolean; detail: string }> } | null>(null)
const preflightError = ref('')
const preflightSnapshot = ref('')
const visiblePreflight = computed(() => preflightSnapshot.value === JSON.stringify(buildProblemPayload()) ? preflightResult.value : null)
const imageUploading = ref(false)
const hiddenImagesUploading = ref(false)
const visionDetecting = ref(false)
const imageGenerating = ref(false)
const imageGeneratePrompt = ref('')
const imageGenerateError = ref('')
const imageSource = ref<'UPLOAD' | 'LOCAL_COMFYUI' | 'DASHSCOPE'>('LOCAL_COMFYUI')
const generationModels = ref<{ local: string[]; cloud: string[] }>({ local: [], cloud: ['qwen-image-2.1-turbo', 'qwen-image-2.1-pro'] })
const generationModel = ref('')
const generatedPreview = ref<{ url: string; prompt: string; source: 'LOCAL_COMFYUI' | 'DASHSCOPE'; modelId: string } | null>(null)
const hiddenGenerationBusy = ref(false)
const generatedCasesReady = ref(false)
const generationPending = ref(false)
const imageGenerationSource = ref('')
const imageGenerationModel = ref('')
const smartImportImageUrl = ref('')
const smartImportPreview = ref('')
const smartImportOcrText = ref('')
const smartImportUploading = ref(false)
const smartImportRunning = ref(false)
const aiGeneratePrompt = ref('')
const aiGenerating = ref(false)
const aiOperationError = ref('')
type VisionBox = {
  label: string
  confidence: number
  x1: number
  y1: number
  x2: number
  y2: number
}
type VisionAnnotation = {
  model?: string
  width?: number
  height?: number
  counts?: Record<string, number>
  boxes?: VisionBox[]
  inferenceMs?: number
  ocr_text?: string
  image_analysis?: Record<string, number | string>
}
type StructuredProblem = {
  title?: string
  content?: string
  answer?: string
  difficulty?: string
  tags?: string[]
  judgeCase?: Array<{ input?: string; output?: string }>
  judgeConfig?: { timeLimit?: number; memoryLimit?: number; stackLimit?: number }
  rawOcrText?: string
}
const COCO_LABEL_ZH: Record<string, string> = {
  head: '未戴安全帽头部', helmet: '安全帽',
  person: '行人', bicycle: '自行车', car: '汽车', motorcycle: '摩托车', airplane: '飞机',
  bus: '公交车', train: '火车', truck: '卡车', boat: '船', 'traffic light': '交通灯',
  'fire hydrant': '消防栓', 'stop sign': '停车标志', bench: '长椅', bird: '鸟', cat: '猫',
  dog: '狗', horse: '马', sheep: '羊', cow: '牛', backpack: '背包', umbrella: '雨伞',
  bottle: '瓶子', chair: '椅子', couch: '沙发', tv: '电视', laptop: '笔记本电脑',
  cell_phone: '手机', 'cell phone': '手机', book: '书', clock: '时钟',
}
const VISION_MODELS = [
  { value: 'YOLO_GENERAL', label: '通用目标检测', description: 'YOLOv8n · COCO 80 类', tasks: ['IMAGE_OBJECT_COUNT', 'IMAGE_CLASSIFICATION', 'IMAGE_OBJECT_DETECTION'] },
  { value: 'YOLO_HELMET', label: '校园安全检测', description: '自训练 · head / helmet / person', tasks: ['IMAGE_OBJECT_COUNT', 'IMAGE_CLASSIFICATION', 'IMAGE_OBJECT_DETECTION'] },
  { value: 'EASYOCR_ZH_EN', label: '中英文 OCR', description: 'EasyOCR · 文字识别', tasks: ['IMAGE_OCR'] },
  { value: 'OPENCV_ANALYSIS', label: '图像属性分析', description: 'OpenCV + Pillow', tasks: ['IMAGE_ANALYSIS'] },
] as const
const visionAnnotation = ref<VisionAnnotation | null>(null)
const tagInput = ref('')
const tags = ref<string[]>([])
const activeStep = ref<'basic' | 'content' | 'answer' | 'judge'>('basic')
const mdMode = ref<'edit' | 'split' | 'preview'>('split')
const answerZoom = ref(100)
let sectionObserver: IntersectionObserver | null = null
let draftCleanupTimer: number | null = null

function createInitialForm(): QuestionAddRequest {
  return {
    title: '',
    content: '',
    answer: '',
    questionType: 'TEXT',
    imageUrl: '',
    visionModelKey: 'YOLO_GENERAL',
    countTolerance: 0,
    tags: [],
    judgeCase: [
      { input: '1 2', output: '3', sample: true },
      { input: '3 5', output: '8', sample: false },
    ],
    judgeConfig: {
      timeLimit: 1000,
      memoryLimit: 262144,
      stackLimit: 65536,
    },
  }
}

const form = ref<QuestionAddRequest>(createInitialForm())
const availableVisionModels = computed(() => VISION_MODELS.filter(
  (item) => (item.tasks as readonly string[]).includes(form.value.questionType || ''),
))

const titleCount = computed(() => (form.value.title || '').trim().length)
const caseCount = computed(() => form.value.judgeCase?.length ?? 0)
const customTags = computed(() => tags.value.filter(
  (tag) => !(PROBLEM_DIFFICULTIES as readonly string[]).includes(tag)
    && !(PROBLEM_KNOWLEDGE_TAGS as readonly string[]).includes(tag),
))

const markdownPreviewHtml = computed(() => {
  const raw = form.value.content || ''
  const escaped = raw
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
  return escaped
    .replace(/^### (.+)$/gm, '<h3>$1</h3>')
    .replace(/^## (.+)$/gm, '<h2>$1</h2>')
    .replace(/^# (.+)$/gm, '<h1>$1</h1>')
    .replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>')
    .replace(/`([^`]+)`/g, '<code>$1</code>')
    .replace(/^- (.+)$/gm, '<li>$1</li>')
    .replace(/(<li>.*<\/li>)/gs, '<ul>$1</ul>')
    .replace(/\n{2,}/g, '</p><p>')
    .replace(/\n/g, '<br />')
    .replace(/^/, '<p>')
    .replace(/$/, '</p>')
})

function insertAtCursor(snippet: string) {
  const area = document.querySelector<HTMLTextAreaElement>('.md-editor__textarea textarea')
  if (!area) {
    form.value.content = `${form.value.content || ''}${snippet}`
    return
  }
  const start = area.selectionStart ?? (form.value.content || '').length
  const end = area.selectionEnd ?? start
  const value = form.value.content || ''
  form.value.content = `${value.slice(0, start)}${snippet}${value.slice(end)}`
  requestAnimationFrame(() => {
    area.focus()
    const pos = start + snippet.length
    area.setSelectionRange(pos, pos)
  })
}

function applyMdTemplate(type: 'h2' | 'bold' | 'code' | 'list' | 'sample') {
  const map = {
    h2: '\n## 小节标题\n',
    bold: '**重点内容**',
    code: '\n```cpp\nint main() {\n  return 0;\n}\n```\n',
    list: '\n- 要点一\n- 要点二\n- 要点三\n',
    sample: '\n### 输入格式\n\n### 输出格式\n\n### 样例输入\n\n### 样例输出\n\n### 提示\n',
  } as const
  insertAtCursor(map[type])
}

function addTag() {
  const next = tagInput.value.trim()
  if (!next) return
  if (tags.value.includes(next)) {
    Message.warning('标签已存在')
    return
  }
  tags.value.push(next)
  tagInput.value = ''
}

function removeTag(idx: number) {
  tags.value.splice(idx, 1)
}

function removeTagValue(value: string) {
  const idx = tags.value.indexOf(value)
  if (idx >= 0) removeTag(idx)
}

function selectDifficulty(value: string) {
  tags.value = tags.value.filter((tag) => !(PROBLEM_DIFFICULTIES as readonly string[]).includes(tag))
  if (value) tags.value.unshift(value)
}

function toggleKnowledgeTag(value: string) {
  const idx = tags.value.indexOf(value)
  if (idx >= 0) tags.value.splice(idx, 1)
  else tags.value.push(value)
}

function hasTag(value: string): boolean {
  return tags.value.includes(value)
}

function addJudgeCase() {
  if (!form.value.judgeCase) form.value.judgeCase = []
  form.value.judgeCase.push({ input: '', output: '', sample: false })
}

function removeJudgeCase(idx: number) {
  if (!form.value.judgeCase) return
  if (form.value.judgeCase.length <= 1) {
    Message.warning('至少保留一组测试用例')
    return
  }
  form.value.judgeCase.splice(idx, 1)
}

function validate(): boolean {
  if (!form.value.title?.trim()) return Message.warning('请输入题目标题'), false
  if (!form.value.content?.trim()) return Message.warning('请输入题目描述'), false
  if (!form.value.answer?.trim()) return Message.warning('请输入参考答案'), false
  if (!tags.value.some((tag) => (PROBLEM_DIFFICULTIES as readonly string[]).includes(tag))) {
    return Message.warning('请选择题目难度'), false
  }
  if (!tags.value.some((tag) => (PROBLEM_KNOWLEDGE_TAGS as readonly string[]).includes(tag))) {
    return Message.warning('请至少选择一个知识点'), false
  }
  if (form.value.questionType?.startsWith('IMAGE_') && !form.value.imageUrl?.trim()) {
    return Message.warning('请上传或填写图像题题图'), false
  }
  if (form.value.questionType?.startsWith('IMAGE_')) {
    const cases = form.value.judgeCase || []
    const uniqueImages = new Set(cases.map((item) => item.input?.trim()).filter(Boolean))
    if (!cases.some((item) => item.sample === true) || !cases.some((item) => item.sample !== true) || uniqueImages.size < 2) {
      return Message.warning('图像题至少需要 1 张公开样例和 1 张不同的隐藏评测图片'), false
    }
  }
  if (!form.value.judgeCase?.length) return Message.warning('请至少填写一组测试用例'), false

  for (const [idx, item] of form.value.judgeCase.entries()) {
    if (!item.input?.trim() || !item.output?.trim()) {
      Message.warning(`第 ${idx + 1} 组测试用例未填写完整`)
      return false
    }
  }
  return true
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
  const auth = safeJsonParse<{ token?: string; accessToken?: string }>(localStorage.getItem(AUTH_STORAGE_KEY), {})
  return (auth.accessToken || auth.token || '').trim()
}

async function uploadQuestionImage(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file) return
  if (!file.type.startsWith('image/')) return void Message.warning('请选择图片文件')
  if (file.size > 10 * 1024 * 1024) return void Message.warning('图片不能超过 10MB')
  imageUploading.value = true
  try {
    const data = new FormData()
    data.append('image', file)
    const token = getToken()
    const res = await axios.post<{ code?: number; message?: string; data?: string }>(
      `${API_BASE_URL}/file/question-image`,
      data,
      { headers: token ? { Authorization: `Bearer ${token}` } : undefined },
    )
    if (!isResultSuccess(res.data.code) || !res.data.data) {
      Message.error(res.data.message || '上传失败')
      return
    }
    form.value.imageUrl = res.data.data
    form.value.judgeCase = []
    generatedPreview.value = null
    generationPending.value = false
    generatedCasesReady.value = false
    imageGenerationSource.value = ''
    imageGenerationModel.value = ''
    visionAnnotation.value = null
    Message.success('题图上传成功')
  } catch (error) {
    Message.error(getBackendErrorMessage(error, '题图上传失败'))
  } finally {
    imageUploading.value = false
  }
}

async function uploadImageFile(file: File): Promise<string> {
  if (!file.type.startsWith('image/')) throw new Error(`${file.name} 不是图片文件`)
  if (file.size > 10 * 1024 * 1024) throw new Error(`${file.name} 超过 10MB`)
  const data = new FormData()
  data.append('image', file)
  const token = getToken()
  const res = await axios.post<{ code?: number; message?: string; data?: string }>(
    `${API_BASE_URL}/file/question-image`, data,
    { headers: token ? { Authorization: `Bearer ${token}` } : undefined },
  )
  if (!isResultSuccess(res.data.code) || !res.data.data) throw new Error(res.data.message || `${file.name} 上传失败`)
  return res.data.data
}

async function annotateImage(imageUrl: string): Promise<VisionAnnotation> {
  const token = getToken()
  const res = await axios.post<{ code?: number; message?: string; data?: VisionAnnotation }>(
    `${API_BASE_URL}/vision/annotate`,
    { imageUrl, confidence: 0.25, iou: 0.7, taskType: form.value.questionType, modelKey: form.value.visionModelKey || 'YOLO_GENERAL' },
    { headers: token ? { Authorization: `Bearer ${token}` } : undefined },
  )
  if (!isResultSuccess(res.data.code) || !res.data.data) throw new Error(res.data.message || '图片识别失败')
  return res.data.data
}

function annotationOutput(annotation: VisionAnnotation): string {
  const type = form.value.questionType
  const counts = annotation.counts || {}
  if (type === 'IMAGE_CLASSIFICATION') {
    return [...(annotation.boxes || [])].sort((a, b) => b.confidence - a.confidence)[0]?.label || 'unknown'
  }
  if (type === 'IMAGE_OBJECT_DETECTION') {
    return JSON.stringify((annotation.boxes || []).map(({ label, x1, y1, x2, y2 }) => ({ label, x1, y1, x2, y2 })))
  }
  if (type === 'IMAGE_OCR') return annotation.ocr_text?.trim() || '未识别到文字'
  if (type === 'IMAGE_ANALYSIS') return JSON.stringify(annotation.image_analysis || {})
  return JSON.stringify(Object.fromEntries(Object.entries(counts).sort(([a], [b]) => a.localeCompare(b))))
}

async function uploadHiddenImages(event: Event) {
  const input = event.target as HTMLInputElement
  const files = Array.from(input.files || [])
  input.value = ''
  if (!files.length) return
  hiddenImagesUploading.value = true
  let completed = 0
  try {
    if (!form.value.judgeCase) form.value.judgeCase = []
    for (const file of files) {
      const imageUrl = await uploadImageFile(file)
      const annotation = await annotateImage(imageUrl)
      form.value.judgeCase.push({ input: imageUrl, output: annotationOutput(annotation), sample: false })
      completed++
    }
    Message.success(`已生成 ${completed} 组隐藏评测用例，学生端不会公开`)
  } catch (error) {
    Message.error(getBackendErrorMessage(error, error instanceof Error ? error.message : '隐藏题图处理失败'))
  } finally {
    hiddenImagesUploading.value = false
  }
}

async function uploadTraditionalProblemPhoto(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  input.value = ''
  if (!file) return
  if (!file.type.startsWith('image/')) return void Message.warning('请选择题目照片或截图')
  if (file.size > 10 * 1024 * 1024) return void Message.warning('图片不能超过 10MB')
  smartImportUploading.value = true
  smartImportPreview.value = URL.createObjectURL(file)
  try {
    const data = new FormData()
    data.append('image', file)
    const token = getToken()
    const res = await axios.post<{ code?: number; message?: string; data?: string }>(
      `${API_BASE_URL}/file/question-image`, data,
      { headers: token ? { Authorization: `Bearer ${token}` } : undefined },
    )
    if (!isResultSuccess(res.data.code) || !res.data.data) return void Message.error(res.data.message || '题目图片上传失败')
    smartImportImageUrl.value = res.data.data
    Message.success('题目图片已上传，可以开始 AI 智能录题')
  } catch (error) {
    Message.error(getBackendErrorMessage(error, '题目图片上传失败'))
  } finally {
    smartImportUploading.value = false
  }
}

async function runSmartProblemImport() {
  if (!smartImportImageUrl.value) return void Message.warning('请先上传传统题目的照片或截图')
  smartImportRunning.value = true
  aiOperationError.value = ''
  try {
    const token = getToken()
    const headers = token ? { Authorization: `Bearer ${token}` } : undefined
    const ocr = await axios.post<{ code?: number; message?: string; data?: VisionAnnotation }>(
      `${API_BASE_URL}/vision/annotate`,
      { imageUrl: smartImportImageUrl.value, confidence: 0.25, iou: 0.7, taskType: 'IMAGE_OCR' },
      { headers },
    )
    if (!isResultSuccess(ocr.data.code) || !ocr.data.data?.ocr_text?.trim()) {
      return void Message.error(ocr.data.message || 'OCR 未识别到题目文字，请上传更清晰的图片')
    }
    smartImportOcrText.value = ocr.data.data.ocr_text.trim()
    const structured = await axios.post<{ code?: number; message?: string; data?: StructuredProblem }>(
      `${API_BASE_URL}/bailian_assist/structure-problem`,
      { ocrText: smartImportOcrText.value, providerCode: aiModels.selectedProvider, modelId: aiModels.selectedModel || undefined },
      { headers },
    )
    if (!isResultSuccess(structured.data.code) || !structured.data.data) {
      const message = structured.data.message || 'AI 题目结构化失败'
      aiOperationError.value = message
      return void Message.error(message)
    }
    applyStructuredProblem(structured.data.data)
    Message.success('AI 智能录题完成，请重点核对题意、约束和样例')
  } catch (error) {
    const message = getBackendErrorMessage(error, 'AI 智能录题失败')
    aiOperationError.value = message
    Message.error(message)
  } finally {
    smartImportRunning.value = false
  }
}

function normalizeOjStatement(content: string): string {
  return content
    .replace(/\$([^$\n]+)\$/g, (_, math: string) => math
      .replace(/\\leq?/g, '≤').replace(/\\geq?/g, '≥')
      .replace(/10\^9/g, '1,000,000,000').replace(/2\^\{31\}-1/g, '2,147,483,647'))
    .replace(/^```\r?\n([^\r\n]+)\r?\n```$/gm, '`$1`')
}

function applyStructuredProblem(data: StructuredProblem) {
  form.value.questionType = 'TEXT'
  form.value.imageUrl = ''
  form.value.countTolerance = 0
  if (data.title?.trim()) form.value.title = data.title.trim()
  if (data.content?.trim()) form.value.content = normalizeOjStatement(data.content.trim())
  if (data.answer?.trim()) form.value.answer = data.answer.trim()
  if (data.judgeCase?.length) form.value.judgeCase = data.judgeCase.map((item, index) => ({ input: item.input || '', output: item.output || '', sample: index === 0 }))
  if (data.judgeConfig) form.value.judgeConfig = { ...form.value.judgeConfig, ...data.judgeConfig }
  const nextTags = [...(data.tags || [])]
  if (data.difficulty && !nextTags.includes(data.difficulty)) nextTags.unshift(data.difficulty)
  tags.value = [...new Set(nextTags)].slice(0, 10)
}

async function runAiProblemGenerate() {
  const prompt = aiGeneratePrompt.value.trim()
  if (!prompt) return void Message.warning('请先填写命题要求')
  aiGenerating.value = true
  aiOperationError.value = ''
  try {
    const token = getToken()
    const response = await axios.post<{ code?: number; message?: string; data?: StructuredProblem }>(
      `${API_BASE_URL}/bailian_assist/structure-problem`,
      { ocrText: prompt, mode: 'GENERATE', providerCode: aiModels.selectedProvider, modelId: aiModels.selectedModel || undefined },
      { headers: token ? { Authorization: `Bearer ${token}` } : undefined },
    )
    if (!isResultSuccess(response.data.code) || !response.data.data) {
      const message = response.data.message || 'AI 命题失败'
      aiOperationError.value = message
      return void Message.error(message)
    }
    applyStructuredProblem(response.data.data)
    Message.success('AI 命题完成，请运行样例并人工复核后再发布')
  } catch (error) {
    const message = getBackendErrorMessage(error, 'AI 命题失败')
    aiOperationError.value = message
    Message.error(message)
  } finally {
    aiGenerating.value = false
  }
}

async function detectObjects() {
  const imageUrl = form.value.imageUrl?.trim()
  if (!imageUrl) return void Message.warning('请先上传题图')
  visionDetecting.value = true
  try {
    const token = getToken()
    const res = await axios.post<{ code?: number; message?: string; data?: VisionAnnotation }>(
      `${API_BASE_URL}/vision/annotate`,
      { imageUrl, confidence: 0.25, iou: 0.7, taskType: form.value.questionType, modelKey: form.value.visionModelKey || 'YOLO_GENERAL' },
      { headers: token ? { Authorization: `Bearer ${token}` } : undefined },
    )
    if (!isResultSuccess(res.data.code) || !res.data.data) {
      Message.error(res.data.message || 'YOLO 识别失败')
      return
    }
    visionAnnotation.value = res.data.data
    const counts = res.data.data.counts || {}
    const hiddenUrls = (form.value.judgeCase || [])
      .filter((item) => item.sample !== true && item.input?.trim() !== imageUrl)
      .map((item) => item.input?.trim() || '')
      .filter(Boolean)
    fillVisionProblem(res.data.data, imageUrl)
    // 模型或题型变化后重新计算全部隐藏标准答案，避免沿用旧模型输出。
    for (const hiddenUrl of hiddenUrls) {
      const hiddenAnnotation = await annotateImage(hiddenUrl)
      form.value.judgeCase?.push({ input: hiddenUrl, output: annotationOutput(hiddenAnnotation), sample: false })
    }
    if (form.value.questionType === 'IMAGE_OCR' && !res.data.data.ocr_text?.trim()) {
      Message.warning('OCR 未识别到文字，已生成题目框架；请更换清晰图片或人工填写标准文本')
    } else if (['IMAGE_OBJECT_COUNT', 'IMAGE_CLASSIFICATION', 'IMAGE_OBJECT_DETECTION'].includes(form.value.questionType || '') && !Object.keys(counts).length) {
      Message.warning('未检测到目标，已生成空目标计数题；建议降低置信度后再次识别或人工复核')
    } else {
      Message.success(`一键出题完成：已识别 ${Object.keys(counts).length} 个类别，并填充全部题目信息`)
    }
    return res.data.data
  } catch (error) {
    Message.error(getBackendErrorMessage(error, 'YOLO 识别服务不可用'))
  } finally {
    visionDetecting.value = false
  }
}

async function generateVisionProblemImage() {
  const prompt = imageGeneratePrompt.value.trim()
  if (!prompt) return void Message.warning('请先描述想生成的题图场景')
  if (!form.value.questionType?.startsWith('IMAGE_')) return void Message.warning('请先选择图像编程题类型')
  imageGenerating.value = true
  imageGenerateError.value = ''
  generatedCasesReady.value = false
  if (generatedPreview.value && form.value.imageUrl === generatedPreview.value.url) {
    form.value.imageUrl = ''
    form.value.judgeCase = []
    visionAnnotation.value = null
    generationPending.value = false
  }
  generatedPreview.value = null
  try {
    if (imageSource.value === 'UPLOAD') throw new Error('请先选择本地或云端生图')
    const source = imageSource.value
    const modelId = generationModel.value
    if (!modelId) throw new Error('请先选择生图模型')
    const url = await requestGeneratedImage(prompt, source, modelId)
    generatedPreview.value = { url, prompt, source, modelId }
    Message.success('预览图已生成，确认满意后再生成隐藏用例')
  } catch (error) {
    const message = getBackendErrorMessage(error, error instanceof Error ? error.message : 'AI 题图生成失败')
    imageGenerateError.value = message
    Message.error(message)
  } finally {
    imageGenerating.value = false
  }
}

function selectImageSource(source: 'UPLOAD' | 'LOCAL_COMFYUI' | 'DASHSCOPE') {
  if (imageGenerating.value || hiddenGenerationBusy.value) return
  if (imageSource.value === source) return
  if (form.value.imageUrl) {
    form.value.imageUrl = ''
    form.value.judgeCase = []
    visionAnnotation.value = null
    imageGenerationSource.value = ''
    imageGenerationModel.value = ''
    generationPending.value = false
    Message.info('已清空上一来源的题图和用例，请重新生成或上传')
  }
  imageSource.value = source
  generatedPreview.value = null
  generatedCasesReady.value = false
  imageGenerateError.value = ''
  generationModel.value = source === 'DASHSCOPE' ? 'qwen-image-2.1-turbo' : generationModels.value.local[0] || ''
}

function discardGeneratedPreview() {
  if (generatedPreview.value && form.value.imageUrl === generatedPreview.value.url) {
    form.value.imageUrl = ''
    form.value.judgeCase = []
    visionAnnotation.value = null
  }
  generatedPreview.value = null
  generationPending.value = false
  generatedCasesReady.value = false
  imageGenerateError.value = ''
}

async function loadGenerationModels() {
  try {
    const token = getToken()
    const res = await axios.get<{ code?: number; data?: { local?: string[]; cloud?: string[] } }>(
      `${API_BASE_URL}/vision/generation-models`, { headers: token ? { Authorization: `Bearer ${token}` } : undefined },
    )
    if (isResultSuccess(res.data.code) && res.data.data) {
      generationModels.value = { local: res.data.data.local || [], cloud: res.data.data.cloud || [] }
      const available = imageSource.value === 'DASHSCOPE' ? generationModels.value.cloud : generationModels.value.local
      if (!available.includes(generationModel.value)) generationModel.value = available[0] || ''
    }
  } catch { /* 模型目录不可用时，生成按钮会提示重新检查服务。 */ }
}

async function requestGeneratedImage(prompt: string, source: 'LOCAL_COMFYUI' | 'DASHSCOPE', modelId: string): Promise<string> {
  const token = getToken()
  const res = await axios.post<{ code?: number; message?: string; data?: { imageUrl?: string } }>(
    `${API_BASE_URL}/vision/generate-image`, { prompt, source, modelId, width: 512, height: 512, steps: 20 },
    { headers: token ? { Authorization: `Bearer ${token}` } : undefined, timeout: 200000 },
  )
  if (!isResultSuccess(res.data.code) || !res.data.data?.imageUrl) throw new Error(res.data.message || 'AI 未返回图片')
  return res.data.data.imageUrl
}

async function acceptGeneratedPreview() {
  const preview = generatedPreview.value
  if (!preview || hiddenGenerationBusy.value) return
  hiddenGenerationBusy.value = true
  imageGenerateError.value = ''
  generationPending.value = true
  generatedCasesReady.value = false
  try {
    form.value.imageUrl = preview.url
    visionAnnotation.value = null
    form.value.judgeCase = []
    const annotation = await detectObjects()
    if (!annotation) throw new Error('题图已生成但识别失败，请检查 YOLO/OCR 服务后重新识别')
    const detectedLabels = Object.keys(annotation.counts || {})
    const mainLabel = detectedLabels[0] || ''
    const plural = mainLabel === 'bus' ? 'buses' : mainLabel === 'person' ? 'people' : `${mainLabel}s`
    const hiddenPrompts = form.value.questionType === 'IMAGE_OBJECT_COUNT' && mainLabel
      ? [
          `A clear realistic photograph of exactly two separate ${plural}, full objects visible, simple background, no text`,
          `A clear realistic photograph of an empty outdoor scene with no ${plural}, no people, no text`,
        ]
      : [
          `${preview.prompt}, alternate scene with different objects and arrangement, clear full view`,
          `${preview.prompt}, distinct background and lighting, different object arrangement, clear full view`,
        ]
    // 隐藏题图使用变化场景，生成后仍以实际识别结果验题；数量并不由提示词保证。
    let hiddenCreated = 0
    for (const hiddenPrompt of hiddenPrompts) {
      const url = await requestGeneratedImage(hiddenPrompt, preview.source, preview.modelId)
      const annotation = await annotateImage(url)
      form.value.judgeCase?.push({ input: url, output: annotationOutput(annotation), sample: false })
      hiddenCreated++
    }
    generatedCasesReady.value = true
    generationPending.value = false
    imageGenerationSource.value = preview.source
    imageGenerationModel.value = preview.modelId
    generatedPreview.value = null
    Message.success(`AI 完整出题完成：1 个公开样例 + ${hiddenCreated} 个隐藏用例`)
  } catch (error) {
    // Retrying adoption starts from a clean sample and never accumulates partial hidden cases.
    form.value.judgeCase = []
    const message = getBackendErrorMessage(error, error instanceof Error ? error.message : 'AI 题图生成失败')
    imageGenerateError.value = message
    Message.error(message)
  } finally {
    hiddenGenerationBusy.value = false
  }
}

function visionSceneTitle(counts: Record<string, number>): string {
  const labels = Object.keys(counts).slice(0, 3).map((label) => COCO_LABEL_ZH[label] || label)
  if (!labels.length) return '图像目标计数'
  return `${labels.join('、')}目标统计`
}

function buildVisionStatement(counts: Record<string, number>): string {
  const detected = Object.keys(counts).map((label) => `\`${label}\``).join('、')
  return `## 题目描述

请编写一个 Python 程序，读取系统提供的题目图片，使用目标检测模型识别图片中的目标，并统计每个目标类别的数量。

本题图片中可能出现的目标类别包括：${detected}。最终结果以模型实际识别结果为准。

## 输入格式

标准输入包含一行字符串，表示沙箱内题目图片的**只读文件路径**。请使用该路径读取图片，不要访问网络。

## 输出格式

输出一个 JSON 对象：键为 YOLO 返回的英文目标类别，值为该类别的数量。

- 只输出一行 JSON，不要输出解释文字、日志或 Markdown。
- 建议使用 \`json.dumps(counts, sort_keys=True)\` 保证输出稳定。
- 数量为 0 的类别可以省略。

## 运行环境

- 语言：Python
- 模型文件：\`${form.value.visionModelKey === 'YOLO_HELMET' ? '/models/hard-hat-best.pt' : '/models/yolov8n.pt'}\`
- 可用库：\`ultralytics\`、\`cv2\`、\`Pillow\`、\`numpy\`
- 运行环境不提供外网访问

## 提示

加载模型后调用目标检测接口，遍历检测框的类别编号，通过结果对象的 \`names\` 映射为类别名称并累计数量。`
}

function buildVisionReferenceAnswer(): string {
  const modelPath = form.value.visionModelKey === 'YOLO_HELMET' ? '/models/hard-hat-best.pt' : '/models/yolov8n.pt'
  return `### 解题思路

1. 从标准输入读取沙箱图片路径。
2. 加载系统提供的 YOLOv8 模型。
3. 对图片执行目标检测并遍历类别编号。
4. 使用模型类别映射得到英文类别名称，累计各类别数量。
5. 将计数结果序列化为 JSON 输出。

\`\`\`python
import contextlib
import io
import json

with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
    from ultralytics import YOLO

def solve(image_path: str) -> dict[str, int]:
    with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
        model = YOLO("${modelPath}")
        result = model.predict(image_path, conf=0.25, iou=0.7, verbose=False)[0]
    counts: dict[str, int] = {}
    for class_id in result.boxes.cls.tolist():
        label = result.names[int(class_id)]
        counts[label] = counts.get(label, 0) + 1
    return counts

if __name__ == "__main__":
    image_path = input().strip()
    print(json.dumps(solve(image_path), sort_keys=True))
\`\`\`

时间主要消耗在模型推理；额外空间复杂度为 O(k)，其中 k 为识别到的类别数。`
}

function fillVisionProblem(annotation: VisionAnnotation, imageUrl: string) {
  const counts = annotation.counts || {}
  const orderedCounts = Object.fromEntries(Object.entries(counts).sort(([a], [b]) => a.localeCompare(b)))
  const type = form.value.questionType || 'IMAGE_OBJECT_COUNT'
  const dominant = [...(annotation.boxes || [])].sort((a, b) => b.confidence - a.confidence)[0]?.label || 'unknown'
  const boxes = (annotation.boxes || []).map(({ label, x1, y1, x2, y2 }) => ({ label, x1, y1, x2, y2 }))
  if (type === 'IMAGE_CLASSIFICATION') {
    form.value.title = `${COCO_LABEL_ZH[dominant] || dominant}图像分类`
    form.value.content = buildGenericVisionStatement('识别图片中的主要目标类别', '输出一个 YOLO 英文类别名称，例如 `car`。')
    form.value.answer = buildClassificationAnswer()
    form.value.countTolerance = 0
    form.value.judgeCase = [{ input: imageUrl, output: dominant, sample: true }]
  } else if (type === 'IMAGE_OBJECT_DETECTION') {
    form.value.title = `${visionSceneTitle(orderedCounts)}与位置检测`
    form.value.content = buildGenericVisionStatement('检测图片中所有目标的类别及位置', '输出 JSON 数组，每项包含 `label`、`x1`、`y1`、`x2`、`y2`；坐标归一化到 0～1。')
    form.value.answer = buildDetectionAnswer()
    form.value.countTolerance = 50
    form.value.judgeCase = [{ input: imageUrl, output: JSON.stringify(boxes), sample: true }]
  } else if (type === 'IMAGE_OCR') {
    form.value.title = '题图文字 OCR 识别'
    form.value.content = buildGenericVisionStatement('识别题目图片中的文字', '按阅读顺序输出识别到的纯文本，不要输出解释或 JSON。')
    form.value.answer = buildOcrAnswer()
    form.value.countTolerance = 2
    form.value.judgeCase = [{ input: imageUrl, output: annotation.ocr_text?.trim() || '未识别到文字', sample: true }]
  } else if (type === 'IMAGE_ANALYSIS') {
    form.value.title = '图像尺寸与灰度特征统计'
    form.value.content = buildGenericVisionStatement('读取图片并计算基础图像属性', '输出 JSON，字段为 `width`、`height`、`mode`、`mean_gray`、`edge_strength`。')
    form.value.answer = buildAnalysisAnswer()
    form.value.countTolerance = 2
    form.value.judgeCase = [{ input: imageUrl, output: JSON.stringify(annotation.image_analysis || {}), sample: true }]
  } else {
    form.value.title = visionSceneTitle(orderedCounts)
    form.value.content = buildVisionStatement(orderedCounts)
    form.value.answer = buildVisionReferenceAnswer()
    form.value.countTolerance = 0
    form.value.judgeCase = [{ input: imageUrl, output: JSON.stringify(orderedCounts), sample: true }]
  }
  form.value.judgeConfig = {
    timeLimit: 60_000,
    memoryLimit: 1_572_864,
    stackLimit: 65_536,
  }
  tags.value = tags.value.filter((tag) => !(PROBLEM_DIFFICULTIES as readonly string[]).includes(tag))
  for (const tag of ['中等', '图像识别', '模拟']) {
    if (!tags.value.includes(tag)) tags.value.push(tag)
  }
}

function buildGenericVisionStatement(task: string, output: string): string {
  return `## 题目描述\n\n请编写 Python 程序，${task}。\n\n## 输入格式\n\n标准输入包含沙箱内只读图片路径。\n\n## 输出格式\n\n${output}\n\n## 环境说明\n\n可使用 Pillow、OpenCV、NumPy、Ultralytics 和 EasyOCR；禁止访问网络。`
}

function buildClassificationAnswer(): string {
  const path = form.value.visionModelKey === 'YOLO_HELMET' ? '/models/hard-hat-best.pt' : '/models/yolov8n.pt'
  return `读取图片后使用 \`${path}\` 推理，选择置信度最高检测框对应的英文类别并输出。`
}

function buildDetectionAnswer(): string {
  return '使用 YOLOv8 获取 `boxes.xyxy` 与类别，分别除以图片宽高转换为归一化坐标，按 JSON 数组输出。判题使用类别匹配和 IoU。'
}

function buildOcrAnswer(): string {
  return '使用 EasyOCR 的中英文模型读取图片，按阅读顺序合并识别文本后输出。判题忽略空白和标点，并允许少量编辑距离。'
}

function buildAnalysisAnswer(): string {
  return '使用 Pillow 读取宽高与颜色模式，转灰度后通过 NumPy 计算平均灰度；相邻像素梯度绝对值均值之和作为边缘强度。'
}

function boxStyle(box: VisionBox) {
  return {
    left: `${box.x1 * 100}%`,
    top: `${box.y1 * 100}%`,
    width: `${Math.max(0, box.x2 - box.x1) * 100}%`,
    height: `${Math.max(0, box.y2 - box.y1) * 100}%`,
  }
}

async function loadQuestionForEdit() {
  if (!isEditMode.value) return
  const id = String(route.params.id || '').trim()
  if (!id) return
  try {
    const data = await Service.adminPageQuestions({
      current: 1,
      pageSize: 1,
      id,
    })
    if (!isResultSuccess(data.code)) {
      Message.error(data.message || '加载题目失败')
      return
    }
    const item = data.data?.records?.[0]
    if (!item) {
      Message.error('题目不存在或已删除')
      return
    }
    const formattedContent = normalizeOjStatement(item.content || '')
    form.value = {
      ...form.value,
      title: item.title || '',
      content: formattedContent,
      answer: item.answer || '',
      questionType: item.questionType || 'TEXT',
      imageUrl: item.imageUrl || '',
      visionModelKey: item.visionModelKey || 'YOLO_GENERAL',
      countTolerance: item.countTolerance ?? 0,
      judgeCase: item.judgeCase?.length ? item.judgeCase.map((c, index) => ({ ...c, sample: c.sample ?? index === 0 })) : form.value.judgeCase,
      judgeConfig: { ...form.value.judgeConfig, ...item.judgeConfig },
      tags: item.tags || [],
    }
    tags.value = Array.isArray(item.tags) ? item.tags : []
    const generation = item as typeof item & { generationSource?: string; generationModel?: string }
    imageGenerationSource.value = generation.generationSource || ''
    imageGenerationModel.value = generation.generationModel || ''
    imageSource.value = generation.generationSource === 'DASHSCOPE' ? 'DASHSCOPE' : generation.generationSource === 'LOCAL_COMFYUI' ? 'LOCAL_COMFYUI' : 'UPLOAD'
    generationModel.value = generation.generationModel || generationModel.value
    if (formattedContent !== (item.content || '')) Message.info('已整理题面中的公式和样例格式，请检查后保存修改')
  } catch (error) {
    Message.error(getBackendErrorMessage(error, '加载题目失败'))
  }
}

function buildProblemPayload(): QuestionAddRequest & { generationSource?: string; generationModel?: string } {
  return {
      ...form.value,
      generationSource: form.value.questionType?.startsWith('IMAGE_') ? (imageGenerationSource.value || 'UPLOAD') : undefined,
      generationModel: imageGenerationModel.value || undefined,
      title: form.value.title?.trim(),
      content: form.value.content?.trim(),
      answer: form.value.answer?.trim(),
      tags: [...tags.value],
      judgeCase: (form.value.judgeCase || []).map((c) => ({
        input: c.input?.trim() || '',
        output: c.output?.trim() || '',
        sample: c.sample === true,
      })),
  }
}

async function preflightProblem(payload = buildProblemPayload()): Promise<boolean> {
  preflightBusy.value = true
  preflightError.value = ''
  preflightResult.value = null
  const snapshot = JSON.stringify(payload)
  try {
    const token = getToken()
    const res = await axios.post<{
      code?: number
      message?: string
      data?: { ready: boolean; checks: Array<{ name: string; passed: boolean; detail: string }> }
    }>(`${API_BASE_URL}/question/admin/preflight`, payload,
      { headers: token ? { Authorization: `Bearer ${token}` } : undefined })
    if (!isResultSuccess(res.data.code) || !res.data.data) throw new Error(res.data.message || '验题服务未返回结果')
    if (JSON.stringify(buildProblemPayload()) !== snapshot) {
      preflightError.value = '验题期间题目已修改，请重新验题'
      return false
    }
    preflightResult.value = res.data.data
    preflightSnapshot.value = snapshot
    return res.data.data.ready
  } catch (error) {
    preflightError.value = getBackendErrorMessage(error, '验题失败，请检查判题服务后重试')
    return false
  } finally {
    preflightBusy.value = false
  }
}

async function runPreflight() {
  if (!validate()) return
  const ready = await preflightProblem()
  if (ready) Message.success('验题通过，请最后人工核对题图和标准答案')
}

async function submitProblem() {
  if (!validate() || submitting.value) return
  if (generatedPreview.value || generationPending.value) return void Message.warning('请先确认题图并完成隐藏用例生成')

  submitting.value = true
  try {
    const payload = buildProblemPayload()
    if (payload.questionType?.startsWith('IMAGE_') && !await preflightProblem(payload)) {
      Message.warning('验题未通过，已暂停发布；请查看下方检查结果')
      return
    }
    if (isEditMode.value) {
      const updateBody: QuestionUpdateRequest & { generationSource?: string; generationModel?: string } = {
        id: String(route.params.id || '').trim(),
        title: payload.title ?? '',
        content: payload.content ?? '',
        answer: payload.answer ?? '',
        questionType: payload.questionType || 'TEXT',
        imageUrl: payload.imageUrl || '',
        visionModelKey: payload.visionModelKey || 'YOLO_GENERAL',
        generationSource: (payload as QuestionAddRequest & { generationSource?: string }).generationSource,
        generationModel: (payload as QuestionAddRequest & { generationModel?: string }).generationModel,
        countTolerance: payload.countTolerance ?? 0,
        tags: payload.tags ?? [],
        judgeCase: payload.judgeCase ?? [],
        judgeConfig: payload.judgeConfig ?? { timeLimit: 1000, memoryLimit: 262144, stackLimit: 65536 },
      }
      const res = await Service.adminUpdateQuestion(updateBody)
      if (!isResultSuccess(res.code)) {
        Message.error(res.message || '更新题目失败')
        return
      }
      Message.success('更新成功')
      router.push({ name: 'admin-problems' })
      return
    } else {
      const res = await Service.addQuestion(payload)
      if (!isResultSuccess(res.code)) {
        Message.error(res.message || '新增题目失败')
        return
      }
      if (typeof window !== 'undefined') {
        window.localStorage.removeItem(DRAFT_KEY)
      }
      Message.success(`新增成功，题目 ID：${res.data ?? '-'}`)
      router.push({ name: 'admin-problems' })
    }
  } catch (error) {
    const msg =
      error instanceof ApiError
        ? error.body?.message || (isEditMode.value ? '更新题目失败' : '新增题目失败')
        : mapApiError(error, getBackendErrorMessage(error, isEditMode.value ? '更新题目失败' : '新增题目失败'))
    Message.error(msg)
  } finally {
    submitting.value = false
  }
}

function onStepClick(step: 'basic' | 'content' | 'answer' | 'judge') {
  activeStep.value = step
}

function setAnswerZoom(next: number) {
  answerZoom.value = Math.max(85, Math.min(150, next))
}

function saveDraft() {
  if (typeof window === 'undefined') return
  const payload = {
    form: form.value,
    tags: tags.value,
    mdMode: mdMode.value,
    savedAt: Date.now(),
  }
  window.localStorage.setItem(DRAFT_KEY, JSON.stringify(payload))
}

function clearDraftAndResetState(showMessage = false) {
  if (typeof window !== 'undefined') {
    window.localStorage.removeItem(DRAFT_KEY)
  }
  form.value = createInitialForm()
  tags.value = []
  mdMode.value = 'split'
  answerZoom.value = 100
  if (showMessage) {
    Message.info('草稿超过 15 分钟未使用，已自动清除')
  }
}

function isDraftExpired(savedAt?: number): boolean {
  if (!savedAt) return true
  return Date.now() - savedAt > DRAFT_TTL_MS
}

onMounted(async () => {
  void loadGenerationModels()
  if (!isEditMode.value && typeof window !== 'undefined') {
    const raw = window.localStorage.getItem(DRAFT_KEY)
    if (raw) {
      try {
        const parsed = JSON.parse(raw) as {
          form?: QuestionAddRequest
          tags?: string[]
          mdMode?: 'edit' | 'split' | 'preview'
          savedAt?: number
        }
        const savedAt = typeof parsed.savedAt === 'number' ? parsed.savedAt : 0
        if (isDraftExpired(savedAt)) {
          clearDraftAndResetState(true)
        } else {
          if (parsed.form) {
            form.value = {
              ...form.value,
              ...parsed.form,
              judgeCase: parsed.form.judgeCase?.length ? parsed.form.judgeCase : form.value.judgeCase,
              judgeConfig: { ...form.value.judgeConfig, ...parsed.form.judgeConfig },
            }
          }
          tags.value = Array.isArray(parsed.tags) ? parsed.tags : []
          if (parsed.mdMode) mdMode.value = parsed.mdMode
        }
      } catch {
        clearDraftAndResetState()
      }
    }
  }
  if (isEditMode.value) {
    await loadQuestionForEdit()
  }

  if (typeof window !== 'undefined') {
    draftCleanupTimer = window.setInterval(() => {
      const raw = window.localStorage.getItem(DRAFT_KEY)
      if (!raw) return
      try {
        const parsed = JSON.parse(raw) as { savedAt?: number }
        if (isDraftExpired(parsed.savedAt)) {
          clearDraftAndResetState(true)
        }
      } catch {
        clearDraftAndResetState()
      }
    }, 60 * 1000)
  }

  const targets: Array<{ id: string; step: 'basic' | 'content' | 'answer' | 'judge' }> = [
    { id: 'section-basic', step: 'basic' },
    { id: 'section-content', step: 'content' },
    { id: 'section-answer', step: 'answer' },
    { id: 'section-judge', step: 'judge' },
  ]

  sectionObserver = new IntersectionObserver(
    (entries) => {
      const visible = entries
        .filter((entry) => entry.isIntersecting)
        .sort((a, b) => b.intersectionRatio - a.intersectionRatio)[0]
      if (!visible) return
      const matched = targets.find((t) => t.id === visible.target.id)
      if (matched) activeStep.value = matched.step
    },
    {
      root: null,
      threshold: [0.2, 0.35, 0.5, 0.65],
      rootMargin: '-12% 0px -50% 0px',
    },
  )

  for (const item of targets) {
    const el = document.getElementById(item.id)
    if (el) sectionObserver.observe(el)
  }
})

onBeforeUnmount(() => {
  sectionObserver?.disconnect()
  sectionObserver = null
  if (draftCleanupTimer !== null) {
    window.clearInterval(draftCleanupTimer)
    draftCleanupTimer = null
  }
})

watch([form, tags, mdMode], () => {
  if (!isEditMode.value) saveDraft()
}, { deep: true })

watch(
  () => form.value.questionType,
  (type) => {
    if (type?.startsWith('IMAGE_') && !tags.value.includes('图像识别')) tags.value.push('图像识别')
    if (!type?.startsWith('IMAGE_')) tags.value = tags.value.filter((tag) => tag !== '图像识别')
    if (type === 'IMAGE_OCR') form.value.visionModelKey = 'EASYOCR_ZH_EN'
    else if (type === 'IMAGE_ANALYSIS') form.value.visionModelKey = 'OPENCV_ANALYSIS'
    else if (type?.startsWith('IMAGE_') && !['YOLO_GENERAL', 'YOLO_HELMET'].includes(form.value.visionModelKey || '')) form.value.visionModelKey = 'YOLO_GENERAL'
  },
)
</script>

<template>
  <div class="editor-page" :class="{ 'editor-page--light': themeLight }">
    <section class="editor-topbar panel">
      <div class="editor-topbar__left">
        <p class="editor-topbar__kicker">教学管理 / 题库中心</p>
        <h2 class="editor-topbar__title">{{ isEditMode ? '编辑题目' : '创建题目' }}</h2>
      </div>
      <div class="editor-steps" aria-label="创建流程">
        <span class="editor-step editor-step--active">1. 基础信息</span>
        <span class="editor-step">2. 题面与答案</span>
        <span class="editor-step">3. 判题配置</span>
      </div>
    </section>

    <section class="editor-hero panel">
      <div>
        <p class="eyebrow">Problem Builder</p>
        <h1>{{ isEditMode ? '编辑题目' : '新增题目' }}</h1>
        <p class="lead">按照教学平台常见流程录入题目信息，便于课程作业、练习与在线评测统一管理。</p>
      </div>
      <div class="hero-badges">
        <span class="side-chip">标题 {{ titleCount }} 字</span>
        <span class="side-chip">用例 {{ caseCount }} 组</span>
      </div>
    </section>

    <section class="workspace-layout">
      <aside class="workspace-nav panel" aria-label="创建题目步骤导航">
        <p class="workspace-nav__title">出题步骤</p>
        <a href="#section-basic" class="workspace-nav__item" :class="{ 'is-active': activeStep === 'basic' }" @click="onStepClick('basic')">
          <span>01</span>
          <div>
            <strong>基本资料</strong>
            <small>标题与标签</small>
          </div>
        </a>
        <a href="#section-content" class="workspace-nav__item" :class="{ 'is-active': activeStep === 'content' }" @click="onStepClick('content')">
          <span>02</span>
          <div>
            <strong>题面编辑</strong>
            <small>Markdown 题目描述</small>
          </div>
        </a>
        <a href="#section-answer" class="workspace-nav__item" :class="{ 'is-active': activeStep === 'answer' }" @click="onStepClick('answer')">
          <span>03</span>
          <div>
            <strong>参考答案</strong>
            <small>思路与代码要点</small>
          </div>
        </a>
        <a href="#section-judge" class="workspace-nav__item" :class="{ 'is-active': activeStep === 'judge' }" @click="onStepClick('judge')">
          <span>04</span>
          <div>
            <strong>判题设置</strong>
            <small>配置与测试用例</small>
          </div>
        </a>
      </aside>

      <div class="workspace-grid">
      <article class="workspace-card panel">
        <p class="card-kicker">基础信息</p>
        <a-form layout="vertical">
          <div id="section-basic" class="form-section">
            <div class="form-section__head">
              <strong>题目基本资料</strong>
              <span>标题与标签将展示在题库列表</span>
            </div>
            <a-form-item label="题目类型" class="question-type-selector">
              <a-select v-model="form.questionType">
                <a-option value="TEXT">传统文本编程题</a-option>
                <a-option value="IMAGE_OBJECT_COUNT">图像目标计数题</a-option>
                <a-option value="IMAGE_CLASSIFICATION">图像分类题</a-option>
                <a-option value="IMAGE_OBJECT_DETECTION">目标检测框题</a-option>
                <a-option value="IMAGE_OCR">OCR 文字识别题</a-option>
                <a-option value="IMAGE_ANALYSIS">图像属性分析题</a-option>
              </a-select>
              <p class="question-type-selector__hint">
                <span v-if="form.questionType === 'TEXT'">选择传统文本题后，可使用拍照录题或 AI 一键命题。</span>
                <span v-else>图像题将使用下方的模型选择、题图上传和自动标注工具。</span>
              </p>
            </a-form-item>
            <Transition name="form-reveal">
            <section v-if="form.questionType === 'TEXT'" class="smart-import-card">
              <AiModelPicker :disabled="smartImportRunning || aiGenerating" />
              <div class="smart-import-card__intro">
                <span class="smart-import-card__icon">AI</span>
                <div>
                  <strong>传统题目 · AI 拍照录题</strong>
                  <p>上传试卷、书本或题目截图，自动完成 OCR、题目结构化、难度分类和表单填充。</p>
                </div>
              </div>
              <div class="smart-import-card__flow">
                <span>① 上传题图</span><i>→</i><span>② OCR 识别</span><i>→</i><span>③ AI 结构化</span><i>→</i><span>④ 自动填充</span>
              </div>
              <div class="smart-import-card__actions">
                <label class="smart-import-upload">
                  {{ smartImportUploading ? '上传中…' : smartImportImageUrl ? '重新上传题目' : '上传题目照片' }}
                  <input type="file" accept="image/*" :disabled="smartImportUploading || smartImportRunning" @change="uploadTraditionalProblemPhoto" />
                </label>
                <a-button type="primary" :loading="smartImportRunning" :disabled="!smartImportImageUrl" @click="runSmartProblemImport">
                  {{ smartImportRunning ? 'OCR 与 AI 正在处理…' : '✦ 开始 AI 智能录题' }}
                </a-button>
                <span v-if="smartImportImageUrl" class="smart-import-ready">✓ 图片已就绪</span>
              </div>
              <div class="smart-import-divider"><span>或者让 AI 直接命题</span></div>
              <div class="smart-generate-row">
                <a-textarea
                  v-model="aiGeneratePrompt"
                  :auto-size="{ minRows: 2, maxRows: 4 }"
                  placeholder="例如：生成一道适合大一学生的简单数组题，考查双指针，数据范围 1≤n≤10^5"
                />
                <a-button type="primary" status="success" :loading="aiGenerating" :disabled="!aiGeneratePrompt.trim()" @click="runAiProblemGenerate">
                  {{ aiGenerating ? 'AI 正在命题…' : '✦ AI 一键出题' }}
                </a-button>
              </div>
              <div v-if="smartImportPreview || smartImportOcrText" class="smart-import-card__result">
                <img v-if="smartImportPreview" :src="smartImportPreview" alt="传统题目图片预览" />
                <div>
                  <strong>{{ smartImportOcrText ? 'OCR 原文（可用于核对）' : '图片预览' }}</strong>
                  <p v-if="smartImportOcrText">{{ smartImportOcrText }}</p>
                  <p v-else>点击“开始 AI 智能录题”后，将自动识别并填写下方全部字段。</p>
                </div>
              </div>
              <div v-if="aiOperationError" class="smart-import-error" role="alert">
                <span>!</span>
                <div><strong>AI 功能暂时不可用</strong><p>{{ aiOperationError }}</p></div>
                <button type="button" aria-label="关闭错误提示" @click="aiOperationError = ''">×</button>
              </div>
              <p class="smart-import-warning">AI 可能误识别数字、符号和公式，发布前必须人工核对输入输出约束与样例。</p>
            </section>
            </Transition>
            <a-form-item label="题目标题">
            <a-input v-model="form.title" allow-clear placeholder="例如：两数之和" />
            </a-form-item>

            <div v-if="form.questionType?.startsWith('IMAGE_')" class="vision-fields">
              <div class="vision-model-picker">
                <div class="vision-model-picker__head">
                  <div><strong>选择识别模型</strong><p>系统会自动使用对应模型生成标准答案和参考代码。</p></div>
                  <span>{{ availableVisionModels.length }} 个可用</span>
                </div>
                <div class="vision-model-options">
                  <button
                    v-for="model in availableVisionModels"
                    :key="model.value"
                    type="button"
                    class="vision-model-card"
                    :class="{ active: form.visionModelKey === model.value }"
                    @click="form.visionModelKey = model.value"
                  >
                    <span class="vision-model-card__icon">{{ model.value === 'YOLO_HELMET' ? '⛑' : model.value.startsWith('YOLO') ? '◎' : model.value.startsWith('EASYOCR') ? '文' : '◐' }}</span>
                    <span><strong>{{ model.label }}</strong><small>{{ model.description }}</small></span>
                    <i>{{ form.visionModelKey === model.value ? '✓' : '→' }}</i>
                  </button>
                </div>
              </div>
              <a-form-item label="题图">
                <div class="vision-field-stack">
                <div class="vision-generator">
                  <div class="vision-generator__head">
                    <div><strong>题图来源</strong><p>生图模型负责生成素材；上方识别模型负责标注和判题依据。</p></div>
                  </div>
                  <div class="generation-source-options">
                    <button type="button" :disabled="imageGenerating || hiddenGenerationBusy" :class="{ active: imageSource === 'UPLOAD' }" @click="selectImageSource('UPLOAD')">上传图片</button>
                    <button type="button" :disabled="imageGenerating || hiddenGenerationBusy" :class="{ active: imageSource === 'LOCAL_COMFYUI' }" @click="selectImageSource('LOCAL_COMFYUI')">本地 GPU</button>
                    <button type="button" :disabled="imageGenerating || hiddenGenerationBusy" :class="{ active: imageSource === 'DASHSCOPE' }" @click="selectImageSource('DASHSCOPE')">百炼云端生图</button>
                  </div>
                  <div v-if="imageSource !== 'UPLOAD'" class="generation-model-row">
                    <label for="generation-model">生图模型</label>
                    <select id="generation-model" v-model="generationModel">
                      <option v-for="name in imageSource === 'DASHSCOPE' ? generationModels.cloud : generationModels.local" :key="name" :value="name">{{ name }}</option>
                    </select>
                    <small v-if="imageSource === 'LOCAL_COMFYUI' && !generationModels.local.length">未检测到允许使用的本地模型，请检查 ComfyUI</small>
                    <small v-if="imageSource === 'DASHSCOPE'">使用账号设置中的个人百炼 Key；云端生图可能按张计费。</small>
                  </div>
                  <div v-if="imageSource !== 'UPLOAD'" class="vision-generator__input">
                    <a-textarea v-model="imageGeneratePrompt" :auto-size="{ minRows: 2, maxRows: 4 }" placeholder="例如：a realistic street photo with one bus and three people, full objects, daylight" />
                    <a-button type="primary" status="success" :loading="imageGenerating" :disabled="!imageGeneratePrompt.trim() || !generationModel || hiddenGenerationBusy" @click="generateVisionProblemImage">
                      {{ imageGenerating ? '生成预览中…' : '生成一张预览图' }}
                    </a-button>
                  </div>
                  <div v-if="generatedPreview" class="generation-preview">
                    <img :src="generatedPreview.url" alt="待确认的生图预览" />
                    <div><strong>这张题图可以使用吗？</strong><p>确认后才会识别题图并生成两张隐藏图片。云端模式下这两张图片也可能产生费用。</p>
                      <a-button type="primary" :loading="hiddenGenerationBusy" @click="acceptGeneratedPreview">采用并补齐隐藏用例</a-button>
                      <a-button :disabled="hiddenGenerationBusy" @click="discardGeneratedPreview">放弃预览</a-button>
                    </div>
                  </div>
                  <p v-if="generatedCasesReady && imageGenerationSource" class="vision-generator__tip">已生成公开题图和两张隐藏用例；使用 {{ imageGenerationSource === 'DASHSCOPE' ? '百炼云端' : '本地 GPU' }} · {{ imageGenerationModel }}。发布前请验题并复核标注。</p>
                  <div v-if="imageGenerateError" class="smart-import-error" role="alert">
                    <span>!</span><div><strong>题图生成失败</strong><p>{{ imageGenerateError }}</p></div>
                    <button type="button" aria-label="关闭错误提示" @click="imageGenerateError = ''">×</button>
                  </div>
                  <p class="vision-generator__tip">AI 无法保证精确数量，标准答案以 YOLO/OCR 实际识别结果为准，发布前请人工复核。</p>
                </div>
                <div v-if="imageSource === 'UPLOAD'" class="vision-upload">
                  <a-input v-model="form.imageUrl" readonly allow-clear placeholder="请上传图片，系统将统一保存到 MinIO" />
                  <label class="vision-upload__button">
                    {{ imageUploading ? '上传中…' : '上传图片' }}
                    <input type="file" accept="image/*" :disabled="imageUploading" @change="uploadQuestionImage" />
                  </label>
                </div>
                <div v-if="form.imageUrl" class="vision-preview-shell">
                  <img :src="form.imageUrl" class="vision-preview" alt="图像题题图预览" />
                  <div class="vision-overlay" aria-label="YOLO 检测框">
                    <div
                      v-for="(box, boxIndex) in visionAnnotation?.boxes || []"
                      :key="`${box.label}-${boxIndex}`"
                      class="vision-box"
                      :style="boxStyle(box)"
                    >
                      <span>{{ box.label }} {{ Math.round(box.confidence * 100) }}%</span>
                    </div>
                  </div>
                </div>
                <div v-if="form.imageUrl" class="vision-ai-actions">
                  <label><span>统一评测参数：置信度 0.25 · IoU 0.70</span></label>
                  <a-button type="primary" :loading="visionDetecting" @click="detectObjects">
                    {{ visionDetecting ? '正在识别并生成题目…' : '✦ 一键识别并生成题目' }}
                  </a-button>
                </div>
                <div v-if="visionAnnotation" class="vision-result">
                  <div class="vision-result__meta">
                    <span>模型 {{ visionAnnotation.model }}</span>
                    <span>{{ visionAnnotation.boxes?.length || 0 }} 个目标</span>
                    <span>{{ visionAnnotation.inferenceMs ?? '-' }} ms</span>
                  </div>
                  <div class="vision-result__chips">
                    <span v-for="(count, label) in visionAnnotation.counts || {}" :key="label">
                      {{ label }} × {{ count }}
                    </span>
                    <span v-if="!Object.keys(visionAnnotation.counts || {}).length">未检测到目标</span>
                  </div>
                  <p v-if="visionAnnotation.ocr_text"><strong>OCR：</strong>{{ visionAnnotation.ocr_text }}</p>
                  <div v-if="visionAnnotation.image_analysis" class="vision-result__chips">
                    <span v-for="(value, key) in visionAnnotation.image_analysis" :key="key">{{ key }}: {{ value }}</span>
                  </div>
                  <div class="vision-autofill-list">
                    <span>✓ 题目标题</span><span>✓ 题型与分类</span><span>✓ 完整题面</span>
                    <span>✓ 参考答案</span><span>✓ 标准输出</span><span>✓ 评测限制</span>
                  </div>
                  <p>所有出题字段已自动生成。发布前请核对检测框和目标数量，必要时可直接修改。</p>
                </div>
                <div v-if="visionAnnotation" class="hidden-case-uploader">
                  <div>
                    <strong>隐藏评测图片</strong>
                    <p>至少再上传 1 张不同图片。系统会自动识别标准答案，学生端只知道图片数量，不会看到图片和输出。</p>
                  </div>
                  <label class="vision-upload__button hidden-case-uploader__button">
                    {{ hiddenImagesUploading ? '上传并识别中…' : '批量添加隐藏题图' }}
                    <input type="file" accept="image/*" multiple :disabled="hiddenImagesUploading" @change="uploadHiddenImages" />
                  </label>
                </div>
                </div>
              </a-form-item>
              <a-form-item :label="form.questionType === 'IMAGE_OBJECT_DETECTION' ? '检测框 IoU 阈值（百分比）' : form.questionType === 'IMAGE_OCR' ? '允许字符编辑距离' : '结果允许误差'">
                <a-input-number v-model="form.countTolerance" :min="0" :max="100" />
                <p class="vision-help">计数题表示数量误差；检测题表示 IoU 百分比；OCR 表示允许不同的字符数；属性题表示数值绝对误差。</p>
              </a-form-item>
            </div>

            <a-form-item label="OJ 分类">
              <div class="taxonomy-editor">
                <div class="taxonomy-group">
                  <div class="taxonomy-group__head"><strong>难度等级</strong><span>单选，便于学生按难度练习</span></div>
                  <div class="taxonomy-options">
                    <button
                      v-for="option in PROBLEM_DIFFICULTIES"
                      :key="option"
                      type="button"
                      class="taxonomy-chip taxonomy-chip--difficulty"
                      :class="{ active: hasTag(option) }"
                      @click="selectDifficulty(option)"
                    >{{ option }}</button>
                  </div>
                </div>
                <div class="taxonomy-group">
                  <div class="taxonomy-group__head"><strong>知识点</strong><span>可多选，建议选择 1～3 个核心知识点</span></div>
                  <div class="taxonomy-options">
                    <button
                      v-for="option in PROBLEM_KNOWLEDGE_TAGS"
                      :key="option"
                      type="button"
                      class="taxonomy-chip"
                      :class="{ active: hasTag(option) }"
                      @click="toggleKnowledgeTag(option)"
                    >{{ option }}</button>
                  </div>
                </div>
              </div>
            </a-form-item>

            <a-form-item label="自定义标签">
              <div class="tag-field">
                <div class="tag-editor">
                  <a-input
                    v-model="tagInput"
                    allow-clear
                    placeholder="预设分类没有时再添加，例如：蓝桥杯"
                    @press-enter="addTag"
                  />
                  <a-button type="outline" @click="addTag">添加</a-button>
                </div>
                <div class="tag-list">
                  <span v-for="tag in customTags" :key="tag" class="tag-pill">
                    <span class="tag-pill__dot" aria-hidden="true" />
                    <span class="tag-pill__text">{{ tag }}</span>
                    <button type="button" class="tag-pill__close" :aria-label="`删除标签 ${tag}`" @click="removeTagValue(tag)">
                      ×
                    </button>
                  </span>
                </div>
              </div>
            </a-form-item>
          </div>

          <div id="section-content" class="form-section">
            <div class="form-section__head">
              <strong>题面编辑</strong>
              <span>建议按「描述-输入-输出-样例-提示」结构填写</span>
            </div>
            <a-form-item label="题目描述（支持 Markdown）" class="editor-form-item editor-form-item--markdown">
            <div class="md-editor">
              <div class="md-editor__bar">
                <span class="md-editor__title">题面编辑区</span>
                <span class="md-editor__tips">支持 # 标题、```代码块、- 列表、**加粗**</span>
              </div>
              <div class="md-toolbar">
                <div class="md-toolbar__group">
                  <button type="button" class="md-tool-btn" @click="applyMdTemplate('h2')">H2</button>
                  <button type="button" class="md-tool-btn" @click="applyMdTemplate('bold')"><strong>B</strong></button>
                  <button type="button" class="md-tool-btn" @click="applyMdTemplate('code')">{ }</button>
                  <button type="button" class="md-tool-btn" @click="applyMdTemplate('list')">List</button>
                  <button type="button" class="md-tool-btn" @click="applyMdTemplate('sample')">样例模板</button>
                </div>
                <div class="md-toolbar__group">
                  <button type="button" class="md-view-btn" :class="{ active: mdMode === 'edit' }" @click="mdMode = 'edit'">编辑</button>
                  <button type="button" class="md-view-btn" :class="{ active: mdMode === 'split' }" @click="mdMode = 'split'">分栏</button>
                  <button type="button" class="md-view-btn" :class="{ active: mdMode === 'preview' }" @click="mdMode = 'preview'">预览</button>
                </div>
              </div>
              <div class="md-main" :class="`md-main--${mdMode}`">
                <a-textarea
                  v-show="mdMode !== 'preview'"
                  v-model="form.content"
                  :auto-size="{ minRows: 14, maxRows: 22 }"
                  class="md-editor__textarea"
                  placeholder="请按 OJ 常见格式填写：题目描述、输入格式、输出格式、样例、提示"
                />
                <div v-show="mdMode !== 'edit'" class="md-preview" v-html="markdownPreviewHtml" />
              </div>
            </div>
            </a-form-item>
          </div>

          <div id="section-answer" class="form-section">
            <div class="form-section__head">
              <strong>参考答案</strong>
              <span>用于教师复核和讲解，不对学生端直接暴露</span>
            </div>
            <a-form-item label="参考答案" class="editor-form-item">
            <div class="answer-editor" :style="{ '--answer-zoom': answerZoom / 100 }">
              <div class="answer-editor__bar">
                <span>建议填写解题思路和关键代码片段</span>
                <div class="answer-editor__tools">
                  <button type="button" class="answer-tool-btn" @click="setAnswerZoom(answerZoom - 5)">A-</button>
                  <span class="answer-tool-value">{{ answerZoom }}%</span>
                  <button type="button" class="answer-tool-btn" @click="setAnswerZoom(100)">默认</button>
                  <button type="button" class="answer-tool-btn" @click="setAnswerZoom(answerZoom + 5)">A+</button>
                </div>
              </div>
              <a-textarea
                v-model="form.answer"
                :auto-size="{ minRows: 7, maxRows: 16 }"
                class="answer-editor__textarea"
                placeholder="例如：先排序 + 双指针，时间复杂度 O(n log n)；你也可以拖动右下角拉伸编辑区"
              />
            </div>
            </a-form-item>
          </div>
        </a-form>
      </article>

      <aside id="section-judge" class="workspace-aside panel">
        <div class="aside-box aside-box--accent">
          <span>判题配置</span>
          <div class="config-grid">
            <label>
              <small>时间限制(ms)</small>
              <a-input-number v-model="form.judgeConfig!.timeLimit" :min="1" :step="100" hide-button />
            </label>
            <label>
              <small>内存限制(KB)</small>
              <a-input-number v-model="form.judgeConfig!.memoryLimit" :min="1" :step="1024" hide-button />
            </label>
            <label>
              <small>栈限制(KB)</small>
              <a-input-number v-model="form.judgeConfig!.stackLimit" :min="1" :step="1024" hide-button />
            </label>
          </div>
        </div>

        <div class="aside-box">
          <span>测试用例</span>
          <div class="case-list">
            <div v-for="(item, idx) in form.judgeCase" :key="idx" class="case-item">
              <div class="case-item__head">
                <strong>Case {{ idx + 1 }}</strong>
                <label class="case-visibility">
                  <input v-model="item.sample" type="checkbox" />
                  <span>{{ item.sample ? '公开样例' : '隐藏用例' }}</span>
                </label>
                <a-button type="text" status="danger" size="mini" @click="removeJudgeCase(idx)">删除</a-button>
              </div>
              <a-textarea v-model="item.input" :auto-size="{ minRows: 2, maxRows: 5 }" :placeholder="form.questionType?.startsWith('IMAGE_') ? '题图 URL（提交时自动转为沙箱路径）' : '输入'" />
              <a-textarea v-model="item.output" :auto-size="{ minRows: 2, maxRows: 5 }" :placeholder="form.questionType?.startsWith('IMAGE_') ? '识别生成的标准输出，可人工复核修改' : '输出'" />
            </div>
          </div>
          <a-button long type="outline" @click="addJudgeCase">新增一组用例</a-button>
        </div>
      </aside>
      </div>
    </section>

    <section v-if="visiblePreflight || preflightError" class="preflight-panel panel" aria-live="polite">
      <strong>{{ visiblePreflight?.ready ? '验题通过' : '验题需要处理' }}</strong>
      <p v-if="preflightError">{{ preflightError }}</p>
      <ul v-if="visiblePreflight"><li v-for="item in visiblePreflight.checks" :key="item.name" :class="item.passed ? 'preflight-ok' : 'preflight-fail'">{{ item.passed ? '✓' : '!' }} {{ item.name }}：{{ item.detail }}</li></ul>
    </section>
    <div class="action-row panel" role="group" aria-label="题目操作">
      <div class="action-row__glow" aria-hidden="true" />
      <a-button size="large" class="action-btn action-btn--ghost" :loading="preflightBusy" :disabled="submitting" @click="runPreflight">先验题</a-button>
      <a-button
        type="primary"
        size="large"
        class="action-btn action-btn--primary"
        :loading="submitting"
        :disabled="preflightBusy"
        :aria-label="isEditMode ? '保存修改并提交题目' : '发布题目'"
        @click="submitProblem"
      >
        <span class="action-btn__icon" aria-hidden="true">✓</span>
        {{ isEditMode ? '保存修改' : '发布题目' }}
      </a-button>
      <a-button
        size="large"
        class="action-btn action-btn--ghost"
        aria-label="返回题目列表"
        @click="router.push({ name: 'admin-problems' })"
      >
        <span class="action-btn__icon" aria-hidden="true">←</span>
        返回列表
      </a-button>
    </div>
  </div>
</template>

<style scoped>
.editor-page {
  max-width: var(--app-page-width);
  margin: 0 auto;
  padding: 28px 20px 56px;
  scroll-behavior: smooth;
}

.panel {
  border: 1px solid var(--app-border);
  background: var(--app-surface);
  box-shadow: var(--app-card-shadow);
  backdrop-filter: blur(18px);
  -webkit-backdrop-filter: blur(18px);
}

.editor-hero,
.editor-topbar,
.workspace-card,
.workspace-aside {
  border-radius: 28px;
}

.editor-topbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 16px;
  padding: 20px 24px;
  margin-bottom: 14px;
}

.editor-topbar__kicker {
  margin: 0;
  font-size: 12px;
  letter-spacing: 0.1em;
  color: var(--app-text-subtle);
}

.editor-topbar__title {
  margin: 8px 0 0;
  font-family: var(--app-font-display);
  font-size: 30px;
  line-height: 1.1;
}

.editor-steps {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.editor-step {
  padding: 7px 12px;
  border-radius: 999px;
  border: 1px solid var(--app-border);
  font-size: 12px;
  color: var(--app-text-muted);
  background: var(--app-surface-soft);
}

.editor-step--active {
  color: #fff;
  border-color: rgba(15, 140, 122, 0.36);
  background: linear-gradient(135deg, #0f8c7a 0%, #14b8a6 100%);
}

.editor-hero {
  display: flex;
  justify-content: space-between;
  gap: 20px;
  align-items: flex-end;
  padding: 28px;
  background:
    radial-gradient(ellipse at 8% 5%, rgba(15, 140, 122, 0.12), transparent 55%),
    radial-gradient(ellipse at 90% 92%, rgba(255, 138, 61, 0.1), transparent 50%),
    var(--app-surface);
}

.eyebrow,
.card-kicker {
  margin: 0 0 12px;
  color: var(--app-accent);
  font-size: 12px;
  font-weight: 700;
  letter-spacing: 0.18em;
  text-transform: uppercase;
}

.editor-hero h1,
.workspace-card h2,
.aside-box strong {
  margin: 0;
  font-family: var(--app-font-display);
}

.editor-hero h1 {
  font-size: clamp(34px, 4vw, 54px);
  line-height: 1.06;
}

.lead {
  margin: 14px 0 0;
  color: var(--app-text-muted);
  line-height: 1.75;
  max-width: 720px;
}

.hero-badges {
  display: grid;
  gap: 10px;
}

.side-chip {
  display: inline-flex;
  padding: 10px 14px;
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.18);
  border: 1px solid var(--app-border);
  color: var(--app-text-muted);
  font-size: 13px;
  font-weight: 700;
}

.workspace-layout {
  display: grid;
  grid-template-columns: minmax(0, 1fr);
  gap: 18px;
  margin-top: 18px;
}

.workspace-nav {
  position: static;
  padding: 16px 14px;
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 10px;
}

.workspace-nav__title {
  margin: 0;
  grid-column: 1 / -1;
  font-size: 12px;
  color: var(--app-text-subtle);
  letter-spacing: 0.08em;
  text-transform: uppercase;
}

.workspace-nav__item {
  display: flex;
  gap: 10px;
  align-items: flex-start;
  text-decoration: none;
  border: 1px solid var(--app-border);
  border-radius: 14px;
  padding: 10px;
  background: var(--app-surface-soft);
  transition:
    border-color 0.2s ease,
    transform 0.2s ease,
    box-shadow 0.2s ease;
}

.workspace-nav__item:hover {
  transform: translateY(-1px);
  border-color: rgba(15, 140, 122, 0.35);
  box-shadow: 0 8px 18px rgba(15, 23, 42, 0.08);
}

.workspace-nav__item > span {
  min-width: 28px;
  height: 28px;
  border-radius: 999px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-size: 11px;
  font-weight: 700;
  color: var(--app-accent);
  background: rgba(15, 140, 122, 0.12);
}

.workspace-nav__item strong {
  display: block;
  font-size: 13px;
  color: var(--app-text);
  line-height: 1.3;
}

.workspace-nav__item small {
  display: block;
  margin-top: 2px;
  font-size: 11px;
  color: var(--app-text-muted);
  line-height: 1.35;
}

.workspace-nav__item.is-active {
  border-color: rgba(15, 140, 122, 0.42);
  background: linear-gradient(180deg, rgba(15, 140, 122, 0.12), rgba(15, 140, 122, 0.04));
}

.workspace-grid {
  display: grid;
  grid-template-columns: minmax(0, 1.45fr) minmax(280px, 0.55fr);
  gap: 18px;
}

.workspace-card,
.workspace-aside {
  padding: 24px;
}

.workspace-card :deep(.arco-form-item-label-col) {
  margin-bottom: 8px !important;
  font-size: 12px;
  letter-spacing: 0.08em;
  color: var(--app-text-subtle);
  font-weight: 700;
}

.workspace-card :deep(.arco-input-wrapper),
.workspace-card :deep(.arco-textarea-wrapper),
.workspace-card :deep(.arco-input-number),
.workspace-aside :deep(.arco-textarea-wrapper),
.workspace-aside :deep(.arco-input-number) {
  border-radius: 14px;
  border: 1px solid var(--app-border);
  background: var(--app-surface-soft);
  transition:
    border-color 0.2s ease,
    box-shadow 0.2s ease,
    background 0.2s ease;
}

.workspace-card :deep(.arco-input-wrapper:hover),
.workspace-card :deep(.arco-textarea-wrapper:hover),
.workspace-card :deep(.arco-input-number:hover),
.workspace-aside :deep(.arco-textarea-wrapper:hover),
.workspace-aside :deep(.arco-input-number:hover) {
  border-color: rgba(15, 140, 122, 0.36);
}

.workspace-card :deep(.arco-input-wrapper.arco-input-focus),
.workspace-card :deep(.arco-textarea-wrapper-focus),
.workspace-card :deep(.arco-input-number.arco-input-focus),
.workspace-aside :deep(.arco-textarea-wrapper-focus),
.workspace-aside :deep(.arco-input-number.arco-input-focus) {
  border-color: rgba(15, 140, 122, 0.55);
  box-shadow: 0 0 0 3px rgba(15, 140, 122, 0.14);
  background: var(--app-surface);
}

.editor-form-item {
  margin-bottom: 20px;
}

.form-section {
  border: 1px solid var(--app-border);
  border-radius: 20px;
  padding: 16px 14px 4px;
  background: var(--app-surface-soft);
  margin-bottom: 14px;
  scroll-margin-top: 96px;
}

.form-section__head {
  margin-bottom: 10px;
}

.form-section__head strong {
  display: block;
  font-size: 15px;
  font-family: var(--app-font-display);
}

.form-section__head span {
  display: block;
  margin-top: 4px;
  font-size: 12px;
  color: var(--app-text-subtle);
}

.md-editor,
.answer-editor {
  border-radius: 16px;
  overflow: hidden;
  border: 1px solid var(--app-border);
  background: var(--app-surface-soft);
  box-shadow:
    0 14px 30px rgba(15, 23, 42, 0.08),
    inset 0 1px 0 rgba(255, 255, 255, 0.18);
}



.md-editor__bar,
.answer-editor__bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  padding: 12px 14px;
  border-bottom: 1px solid var(--app-border);
  background: rgba(15, 140, 122, 0.06);
}

.md-editor__title {
  font-size: 12px;
  font-weight: 700;
  color: var(--app-accent);
}

.md-editor__tips,
.answer-editor__bar {
  font-size: 12px;
  color: var(--app-text-muted);
}

.answer-editor {
  width: 100%;
  max-width: 100%;
}

.answer-editor__tools {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.answer-tool-btn {
  height: 26px;
  padding: 0 8px;
  border-radius: 8px;
  border: 1px solid var(--app-border);
  background: var(--app-surface);
  color: var(--app-text-muted);
  font-size: 12px;
  cursor: pointer;
  transition: all 0.2s ease;
}

.answer-tool-btn:hover {
  border-color: rgba(15, 140, 122, 0.35);
  color: var(--app-accent);
}

.answer-tool-value {
  min-width: 50px;
  text-align: center;
  font-size: 12px;
  color: var(--app-text-subtle);
}

.md-toolbar {
  display: flex;
  justify-content: space-between;
  gap: 10px;
  padding: 10px 12px;
  border-bottom: 1px solid var(--app-border);
  background: linear-gradient(180deg, rgba(15, 23, 42, 0.04), rgba(15, 23, 42, 0.02));
}

.md-toolbar__group {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.md-tool-btn,
.md-view-btn {
  height: 34px;
  padding: 0 12px;
  border-radius: 8px;
  border: 1px solid var(--app-border);
  background: var(--app-surface);
  color: var(--app-text-muted);
  font-size: 12px;
  cursor: pointer;
  transition: all 0.2s ease;
}

.md-tool-btn:hover,
.md-view-btn:hover {
  border-color: rgba(15, 140, 122, 0.38);
  color: var(--app-accent);
  box-shadow: 0 6px 14px rgba(15, 140, 122, 0.12);
}

.md-view-btn.active {
  color: #fff;
  border-color: rgba(15, 140, 122, 0.36);
  background: linear-gradient(135deg, #0f8c7a 0%, #14b8a6 100%);
}

.md-main {
  display: grid;
  grid-template-columns: minmax(0, 1.2fr) minmax(0, 0.8fr);
  min-height: 440px;
}

.md-main--split {
  grid-template-columns: minmax(440px, 1.55fr) minmax(320px, 1fr);
}

.md-main--edit {
  grid-template-columns: 1fr;
}

.md-main--preview {
  grid-template-columns: 1fr;
}

.md-editor__textarea :deep(.arco-textarea),
.answer-editor__textarea :deep(.arco-textarea) {
  font-family: ui-monospace, 'Cascadia Code', 'Consolas', monospace;
  font-size: 14px;
  line-height: 1.74;
}

.md-editor__textarea :deep(.arco-textarea-wrapper),
.answer-editor__textarea :deep(.arco-textarea-wrapper) {
  border: none;
  border-radius: 0;
  background: transparent;
  width: 100%;
}

.md-main > .md-editor__textarea {
  width: 100% !important;
  min-width: 440px;
}

.md-editor__textarea :deep(.arco-textarea-wrapper) {
  border-right: 1px solid var(--app-border);
  height: 100%;
}

.md-editor__textarea :deep(.arco-textarea) {
  min-height: 440px !important;
  padding: 18px 16px;
}

.answer-editor__textarea :deep(.arco-textarea) {
  width: 100% !important;
  min-height: calc(420px * var(--answer-zoom, 1)) !important;
  font-size: calc(14px * var(--answer-zoom, 1));
  resize: both;
  overflow: auto;
  min-width: 640px;
}

.md-preview {
  padding: 18px 16px 20px;
  overflow: auto;
  font-size: 14px;
  line-height: 1.7;
  color: var(--app-text);
  background:
    linear-gradient(180deg, rgba(15, 23, 42, 0.02), transparent 32%),
    var(--app-surface-soft);
}

.md-preview :deep(h1),
.md-preview :deep(h2),
.md-preview :deep(h3) {
  margin: 0 0 10px;
  font-family: var(--app-font-display);
}

.md-preview :deep(p) {
  margin: 0 0 10px;
}

.md-preview :deep(code) {
  display: inline-block;
  padding: 0 6px;
  border-radius: 6px;
  background: rgba(15, 140, 122, 0.12);
  font-family: ui-monospace, 'Cascadia Code', 'Consolas', monospace;
  font-size: 12px;
}

.md-preview :deep(ul) {
  margin: 0 0 10px 18px;
}

.tag-field {
  display: flex;
  flex-direction: column;
  align-items: stretch;
  width: 100%;
}

.taxonomy-editor {
  display: grid;
  gap: 14px;
  width: 100%;
  padding: 16px;
  border: 1px solid var(--app-border);
  border-radius: 16px;
  background: color-mix(in srgb, var(--app-surface) 90%, var(--app-accent) 3%);
}

.smart-import-card {
  display: grid;
  gap: 13px;
  margin: 4px 0 20px;
  padding: 18px;
  border: 1px solid color-mix(in srgb, #8b5cf6 30%, var(--app-border));
  border-radius: 18px;
  background: linear-gradient(135deg, color-mix(in srgb, #8b5cf6 9%, var(--app-surface)), color-mix(in srgb, #06b6d4 7%, var(--app-surface)));
  box-shadow: 0 14px 35px rgba(109, 40, 217, .08);
}
.question-type-selector { margin-bottom: 14px; }
.question-type-selector :deep(.arco-select-view) { min-height: 46px; border-radius: 13px; border-color: color-mix(in srgb, #8b5cf6 35%, var(--app-border)); background: linear-gradient(135deg, color-mix(in srgb, #8b5cf6 8%, var(--app-surface)), var(--app-surface)); }
.question-type-selector__hint { display: flex; align-items: center; min-height: 28px; margin: 7px 0 0; padding: 0 10px; border-left: 3px solid #8b5cf6; color: var(--app-text-subtle); font-size: 12px; line-height: 1.55; }
.form-reveal-enter-active, .form-reveal-leave-active { transition: opacity .22s ease, transform .22s ease; }
.form-reveal-enter-from, .form-reveal-leave-to { opacity: 0; transform: translateY(-8px); }
.smart-import-card__intro { display: flex; align-items: center; gap: 12px; }
.smart-import-card__intro p, .smart-import-warning { margin: 3px 0 0; color: var(--app-text-subtle); font-size: 12px; line-height: 1.6; }
.smart-import-card__icon { display: grid; place-items: center; width: 42px; height: 42px; flex: 0 0 auto; border-radius: 14px; background: linear-gradient(135deg, #8b5cf6, #0891b2); color: #fff; font-size: 12px; font-weight: 900; box-shadow: 0 8px 20px rgba(124, 58, 237, .24); }
.smart-import-card__flow { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; padding: 9px 11px; border-radius: 12px; background: color-mix(in srgb, var(--app-surface) 76%, transparent); color: var(--app-text-muted); font-size: 11px; font-weight: 700; }
.smart-import-card__flow i { color: #8b5cf6; font-style: normal; }
.smart-import-card__actions { display: flex; align-items: center; flex-wrap: wrap; gap: 10px; }
.smart-import-divider { display: flex; align-items: center; gap: 10px; color: var(--app-text-subtle); font-size: 11px; }
.smart-import-divider::before, .smart-import-divider::after { content: ''; height: 1px; flex: 1; background: var(--app-border); }
.smart-generate-row { display: grid; grid-template-columns: minmax(0, 1fr) auto; align-items: end; gap: 10px; }
.smart-import-upload { position: relative; display: inline-flex; align-items: center; min-height: 40px; padding: 0 15px; border: 1px solid color-mix(in srgb, #8b5cf6 38%, var(--app-border)); border-radius: 11px; background: var(--app-surface); color: #7c3aed; font-size: 12px; font-weight: 800; cursor: pointer; }
.smart-import-upload input { position: absolute; width: 1px; height: 1px; opacity: 0; }
.smart-import-ready { color: #059669; font-size: 11px; font-weight: 800; }
.smart-import-card__result { display: grid; grid-template-columns: 110px minmax(0, 1fr); gap: 12px; max-height: 190px; padding: 10px; border: 1px solid var(--app-border); border-radius: 13px; background: color-mix(in srgb, var(--app-surface) 88%, #fff 12%); overflow: hidden; }
.smart-import-card__result img { width: 110px; height: 120px; border-radius: 9px; object-fit: contain; background: #111827; }
.smart-import-card__result div { min-width: 0; overflow: auto; }
.smart-import-card__result p { margin: 6px 0 0; white-space: pre-wrap; color: var(--app-text-muted); font-size: 11px; line-height: 1.55; }
.smart-import-warning { color: #b45309; }
.smart-import-error { display: grid; grid-template-columns: 30px minmax(0, 1fr) auto; align-items: start; gap: 10px; padding: 11px 12px; border: 1px solid rgba(244, 63, 94, .25); border-radius: 12px; background: rgba(244, 63, 94, .08); color: #be123c; }
.smart-import-error > span { display: grid; place-items: center; width: 26px; height: 26px; border-radius: 50%; background: #e11d48; color: #fff; font-weight: 900; }
.smart-import-error strong { font-size: 12px; }
.smart-import-error p { margin: 3px 0 0; color: color-mix(in srgb, #be123c 72%, var(--app-text-muted)); font-size: 11px; line-height: 1.55; }
.smart-import-error button { border: 0; background: transparent; color: inherit; cursor: pointer; font-size: 18px; }

.taxonomy-group { display: grid; gap: 9px; }
.taxonomy-group + .taxonomy-group { padding-top: 13px; border-top: 1px dashed var(--app-border); }
.taxonomy-group__head { display: flex; align-items: baseline; justify-content: space-between; gap: 12px; }
.taxonomy-group__head strong { color: var(--app-text-main); font-size: 13px; }
.taxonomy-group__head span { color: var(--app-text-subtle); font-size: 11px; }
.taxonomy-options { display: flex; flex-wrap: wrap; gap: 8px; }
.taxonomy-chip {
  appearance: none;
  padding: 7px 11px;
  border: 1px solid var(--app-border);
  border-radius: 999px;
  background: color-mix(in srgb, var(--app-surface) 92%, #fff 8%);
  color: var(--app-text-muted);
  font: inherit;
  font-size: 12px;
  cursor: pointer;
  transition: .18s ease;
}
.taxonomy-chip:hover { transform: translateY(-1px); border-color: color-mix(in srgb, var(--app-accent) 50%, var(--app-border)); }
.taxonomy-chip.active {
  border-color: color-mix(in srgb, var(--app-accent) 65%, #38bdf8);
  background: linear-gradient(135deg, color-mix(in srgb, var(--app-accent) 16%, var(--app-surface)), color-mix(in srgb, #38bdf8 11%, var(--app-surface)));
  color: var(--app-accent);
  font-weight: 700;
  box-shadow: 0 5px 14px color-mix(in srgb, var(--app-accent) 12%, transparent);
}
.taxonomy-chip--difficulty.active::before { content: '✓ '; }

.vision-fields {
  width: 100%;
  padding: 18px;
  margin-bottom: 18px;
  border: 1px solid color-mix(in srgb, #0ea5e9 34%, var(--app-border));
  border-radius: 18px;
  background: color-mix(in srgb, #0ea5e9 7%, var(--app-surface));
  overflow: hidden;
}
.vision-fields :deep(.arco-form-item-content),
.vision-fields :deep(.arco-form-item-content-flex) {
  display: block;
  width: 100%;
  min-width: 0;
}
.vision-model-picker {
  margin-bottom: 18px;
  padding: 16px;
  border: 1px solid color-mix(in srgb, var(--app-accent) 24%, var(--app-border));
  border-radius: 15px;
  background: color-mix(in srgb, var(--app-surface) 92%, #0ea5e9);
}
.vision-model-picker__head { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; margin-bottom: 12px; }
.vision-model-picker__head strong { color: var(--app-text); font-size: 15px; }
.vision-model-picker__head p { margin: 4px 0 0; color: var(--app-text-muted); font-size: 12px; }
.vision-model-picker__head > span { padding: 4px 9px; border-radius: 999px; color: var(--app-accent); background: color-mix(in srgb, var(--app-accent) 12%, transparent); font-size: 12px; }
.vision-model-options { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 10px; }
.vision-model-card {
  display: grid; grid-template-columns: 38px minmax(0, 1fr) 20px; align-items: center; gap: 10px;
  width: 100%; padding: 12px; border: 1px solid var(--app-border); border-radius: 12px;
  color: var(--app-text); background: var(--app-surface); text-align: left; cursor: pointer; transition: .18s ease;
}
.vision-model-card:hover { transform: translateY(-1px); border-color: color-mix(in srgb, var(--app-accent) 55%, var(--app-border)); }
.vision-model-card.active { border-color: var(--app-accent); background: color-mix(in srgb, var(--app-accent) 9%, var(--app-surface)); box-shadow: 0 7px 18px color-mix(in srgb, var(--app-accent) 10%, transparent); }
.vision-model-card__icon { display: grid; place-items: center; width: 36px; height: 36px; border-radius: 10px; color: #fff; background: linear-gradient(135deg, #0ea5e9, #10b981); font-weight: 800; }
.vision-model-card strong, .vision-model-card small { display: block; }
.vision-model-card small { margin-top: 3px; color: var(--app-text-muted); font-size: 11px; }
.vision-model-card i { color: var(--app-accent); font-style: normal; font-weight: 800; }
@media (max-width: 720px) { .vision-model-options { grid-template-columns: 1fr; } }
.vision-field-stack {
  display: grid;
  gap: 14px;
  width: 100%;
  min-width: 0;
}
.vision-generator {
  width: 100%; box-sizing: border-box; padding: 18px; border: 1px solid rgba(16, 185, 129, .28);
  border-radius: 16px; background: linear-gradient(135deg, color-mix(in srgb, #10b981 10%, var(--app-surface)), color-mix(in srgb, #3b82f6 8%, var(--app-surface)));
}
.vision-generator__head { display: flex; justify-content: space-between; gap: 16px; align-items: flex-start; margin-bottom: 12px; }
.vision-generator__head > div { min-width: 0; }
.vision-generator__head strong { color: var(--app-text); font-size: 15px; }
.vision-generator__head p, .vision-generator__tip { margin: 4px 0 0; color: var(--app-text-muted); line-height: 1.65; }
.vision-generator__head > span { flex: none; padding: 4px 9px; border-radius: 999px; color: #047857; background: rgba(16, 185, 129, .14); font-size: 12px; }
.vision-generator__input { display: grid; grid-template-columns: minmax(0, 1fr) auto; align-items: end; gap: 10px; }
.generation-source-options { display: flex; flex-wrap: wrap; gap: 8px; margin: 12px 0; }
.generation-source-options button { border: 1px solid var(--app-border); border-radius: 10px; padding: 8px 12px; background: var(--app-surface); color: var(--app-text); cursor: pointer; }
.generation-source-options button.active { border-color: var(--app-accent); background: color-mix(in srgb, var(--app-accent) 14%, var(--app-surface)); color: var(--app-text); font-weight: 700; }
.generation-source-options button:disabled { cursor: wait; opacity: .65; }
.generation-model-row { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; margin-bottom: 12px; color: var(--app-text); }
.generation-model-row select { min-width: 210px; max-width: 100%; padding: 8px; border: 1px solid var(--app-border); border-radius: 9px; background: var(--app-surface); color: var(--app-text); }
.generation-model-row small { color: var(--app-text-muted); }
.generation-preview { display: grid; grid-template-columns: minmax(120px, 210px) 1fr; gap: 14px; align-items: center; margin-top: 14px; padding: 12px; border: 1px solid rgba(15,157,139,.28); border-radius: 12px; background: var(--app-surface); }
.generation-preview img { display: block; width: 100%; max-height: 210px; object-fit: contain; border-radius: 8px; }
.generation-preview p { color: var(--app-text-muted); line-height: 1.6; }
.generation-preview button { margin: 4px 8px 4px 0; }
@media (max-width: 600px) { .generation-preview { grid-template-columns: 1fr; } }
.vision-generator__tip { font-size: 12px; }
.vision-source-divider { display: flex; align-items: center; gap: 10px; margin: 2px 0; color: var(--app-text-muted); font-size: 12px; }
.vision-source-divider::before, .vision-source-divider::after { content: ''; flex: 1; height: 1px; background: var(--color-border-2); }
@media (max-width: 720px) { .vision-generator__input { grid-template-columns: 1fr; } .vision-generator__head { flex-direction: column; } }

.vision-upload {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 10px;
  align-items: center;
  width: 100%;
}

.vision-upload__button {
  flex: 0 0 auto;
  padding: 7px 14px;
  border-radius: 8px;
  color: #fff;
  background: #0284c7;
  cursor: pointer;
}

.vision-upload__button input {
  display: none;
}
.hidden-case-uploader {
  display: flex; align-items: center; justify-content: space-between; gap: 16px;
  padding: 14px 16px; border: 1px dashed color-mix(in srgb, #8b5cf6 48%, var(--app-border));
  border-radius: 14px; background: color-mix(in srgb, #8b5cf6 7%, var(--app-surface));
}
.hidden-case-uploader strong { color: var(--app-text); }
.hidden-case-uploader p { margin: 4px 0 0; color: var(--app-text-muted); font-size: 12px; line-height: 1.55; }
.hidden-case-uploader__button { white-space: nowrap; background: linear-gradient(135deg, #7c3aed, #2563eb); }
.case-visibility { display: inline-flex; align-items: center; gap: 5px; margin-left: auto; color: var(--app-text-muted); font-size: 12px; cursor: pointer; }
.case-visibility input { accent-color: var(--app-accent); }

.vision-preview {
  display: block;
  max-width: 100%;
  max-height: 320px;
  border-radius: 10px;
  object-fit: contain;
}

.vision-preview-shell {
  position: relative;
  display: inline-block;
  justify-self: center;
  width: auto;
  max-width: 100%;
  box-sizing: border-box;
  border: 1px solid var(--app-border);
  border-radius: 14px;
  background: rgba(15, 23, 42, .88);
  line-height: 0;
  overflow: hidden;
}

.vision-overlay {
  position: absolute;
  inset: 0;
  pointer-events: none;
}

.vision-box {
  position: absolute;
  border: 2px solid #22d3ee;
  border-radius: 3px;
  box-shadow: 0 0 0 1px rgba(15, 23, 42, 0.55);
}

.vision-box span {
  position: absolute;
  left: -2px;
  top: -22px;
  padding: 2px 5px;
  border-radius: 4px 4px 4px 0;
  background: #0891b2;
  color: #fff;
  font-size: 10px;
  line-height: 16px;
  white-space: nowrap;
}

.vision-ai-actions {
  display: flex;
  align-items: end;
  justify-content: space-between;
  gap: 14px;
  margin-top: 12px;
}

.vision-ai-actions label {
  flex: 1;
  display: grid;
  gap: 5px;
  color: var(--app-text-muted);
  font-size: 12px;
}

.vision-result {
  width: 100%;
  box-sizing: border-box;
  padding: 15px;
  border-radius: 14px;
  background: color-mix(in srgb, #10b981 10%, var(--app-surface));
  border: 1px solid color-mix(in srgb, #10b981 35%, var(--app-border));
}

.vision-result__meta,
.vision-result__chips {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}

.vision-result__meta span,
.vision-result__chips span {
  padding: 3px 8px;
  border-radius: 999px;
  background: color-mix(in srgb, #10b981 15%, var(--app-surface));
  color: var(--app-text);
  font-size: 11px;
}

.vision-result__chips {
  margin-top: 8px;
}

.vision-result p {
  margin: 9px 0 0;
  color: var(--app-text-muted);
  font-size: 12px;
  line-height: 1.5;
}

.vision-autofill-list {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 7px;
  margin-top: 10px;
}

.vision-autofill-list span {
  padding: 7px 8px;
  border: 1px solid rgba(16, 185, 129, .18);
  border-radius: 9px;
  background: rgba(16, 185, 129, .07);
  color: #047857;
  font-size: 11px;
  font-weight: 700;
  text-align: center;
}

@media (max-width: 720px) {
  .vision-fields { padding: 12px; }
  .vision-generator { padding: 14px; }
  .vision-upload { grid-template-columns: 1fr; }
  .vision-upload__button { text-align: center; }
  .vision-ai-actions { align-items: stretch; flex-direction: column; }
  .vision-autofill-list { grid-template-columns: repeat(2, minmax(0, 1fr)); }
}

.vision-help {
  margin: 8px 0 0;
  color: var(--app-text-muted);
  font-size: 12px;
}

.tag-editor {
  display: flex;
  align-items: stretch;
  gap: 10px;
  width: 100%;
}

.tag-editor :deep(.arco-input-wrapper) {
  flex: 1 1 auto;
  min-width: 0;
}

.tag-editor :deep(.arco-input) {
  width: 100%;
}

.tag-editor :deep(.arco-btn) {
  flex: 0 0 88px;
  min-width: 88px;
  white-space: nowrap;
}

.tag-list {
  margin-top: 12px;
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
  align-items: center;
  width: 100%;
}

.tag-pill {
  --tag-bg-a: rgba(15, 140, 122, 0.16);
  --tag-bg-b: rgba(59, 130, 246, 0.1);
  display: inline-flex;
  align-items: center;
  gap: 8px;
  max-width: 100%;
  padding: 6px 8px 6px 10px;
  border-radius: 999px;
  border: 1px solid rgba(15, 140, 122, 0.26);
  background: linear-gradient(135deg, var(--tag-bg-a), var(--tag-bg-b));
  box-shadow:
    0 6px 14px rgba(15, 140, 122, 0.12),
    inset 0 1px 0 rgba(255, 255, 255, 0.22);
}

.tag-pill__dot {
  width: 6px;
  height: 6px;
  border-radius: 999px;
  background: #14b8a6;
  box-shadow: 0 0 8px rgba(20, 184, 166, 0.4);
}

.tag-pill__text {
  font-size: 12px;
  font-weight: 700;
  color: var(--app-text);
  max-width: 220px;
  white-space: nowrap;
  text-overflow: ellipsis;
  overflow: hidden;
}

.tag-pill__close {
  width: 20px;
  height: 20px;
  border: none;
  border-radius: 999px;
  background: rgba(15, 23, 42, 0.1);
  color: #334155;
  font-size: 14px;
  line-height: 1;
  cursor: pointer;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  transition: all 0.18s ease;
}

.tag-pill__close:hover {
  background: rgba(15, 140, 122, 0.2);
  color: #0f766e;
}

.tag-pill__close:focus-visible {
  outline: none;
  box-shadow: 0 0 0 2px rgba(20, 184, 166, 0.3);
}

.workspace-aside {
  display: grid;
  gap: 14px;
  align-content: start;
  scroll-margin-top: 96px;
}

.aside-box {
  padding: 20px;
  border-radius: 22px;
  background: var(--app-surface-soft);
  border: 1px solid var(--app-border);
}

.aside-box span {
  display: block;
  color: var(--app-text-subtle);
  font-size: 12px;
  letter-spacing: 0.08em;
  text-transform: uppercase;
}

.aside-box--accent {
  background: linear-gradient(180deg, rgba(15, 140, 122, 0.14) 0%, rgba(255, 138, 61, 0.08) 100%);
}

.config-grid {
  margin-top: 12px;
  display: grid;
  gap: 12px;
}

.config-grid label small {
  display: block;
  margin-bottom: 6px;
  color: var(--app-text-muted);
}

.case-list {
  margin: 10px 0 14px;
  display: grid;
  gap: 10px;
}

.case-item {
  border: 1px solid var(--app-border);
  border-radius: 14px;
  background: var(--app-surface);
  padding: 10px;
  display: grid;
  gap: 8px;
}

.case-item__head {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.case-item__head strong {
  font-size: 13px;
}

.action-row {
  display: flex;
  position: relative;
  overflow: hidden;
  gap: clamp(20px, 8vw, 88px);
  flex-direction: row;
  flex-wrap: wrap;
  justify-content: center;
  align-items: center;
  margin-top: 18px;
  padding: 14px 16px;
  border-radius: 20px;
  background:
    linear-gradient(120deg, rgba(15, 140, 122, 0.12), rgba(59, 130, 246, 0.08) 42%, rgba(255, 138, 61, 0.1)),
    var(--app-surface);
}
.preflight-panel { margin-top: 16px; padding: 16px 20px; color: var(--app-text); }
.preflight-panel p { margin: 8px 0 0; color: #b45309; }
.preflight-panel ul { margin: 10px 0 0; padding-left: 0; list-style: none; display: grid; gap: 7px; font-size: 13px; line-height: 1.5; }
.preflight-ok { color: #0f8c7a; }.preflight-fail { color: #b45309; }

.action-row__glow {
  position: absolute;
  width: 260px;
  height: 260px;
  right: -90px;
  top: -120px;
  border-radius: 999px;
  background: radial-gradient(circle, rgba(56, 189, 248, 0.24), transparent 70%);
  pointer-events: none;
  animation: actionGlowMove 5.5s ease-in-out infinite;
}

.action-row::after {
  content: '';
  position: absolute;
  width: 220px;
  height: 220px;
  left: -80px;
  bottom: -130px;
  border-radius: 999px;
  background: radial-gradient(circle, rgba(20, 184, 166, 0.2), transparent 72%);
  pointer-events: none;
  animation: actionGlowMove 6.2s ease-in-out infinite reverse;
}

.action-btn {
  min-width: 186px;
  border-radius: 14px;
  font-weight: 700;
  height: 46px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  transition:
    transform 0.2s ease,
    box-shadow 0.2s ease,
    border-color 0.2s ease;
}

.action-btn:hover {
  transform: translateY(-1px);
}

.action-btn--primary {
  border: none;
  background: linear-gradient(135deg, #0f8c7a 0%, #14b8a6 55%, #38bdf8 100%);
  box-shadow:
    0 10px 24px rgba(15, 140, 122, 0.28),
    inset 0 1px 0 rgba(255, 255, 255, 0.32);
}

.action-btn--ghost {
  background: rgba(255, 255, 255, 0.56);
  border: 1px solid rgba(100, 116, 139, 0.35);
  color: #334155;
}

.action-row :deep(.arco-btn + .arco-btn) {
  margin-left: 0;
}

.action-btn--ghost:hover {
  border-color: rgba(15, 140, 122, 0.34);
  box-shadow: 0 8px 20px rgba(15, 140, 122, 0.12);
  background: rgba(255, 255, 255, 0.76);
}

.action-btn__icon {
  width: 18px;
  height: 18px;
  border-radius: 999px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  font-size: 12px;
  font-weight: 800;
  background: rgba(255, 255, 255, 0.26);
}

.action-btn--ghost .action-btn__icon {
  background: rgba(51, 65, 85, 0.12);
}

.action-btn:focus-visible {
  outline: none;
  box-shadow:
    0 0 0 3px rgba(20, 184, 166, 0.28),
    0 8px 20px rgba(15, 140, 122, 0.14);
}

.action-btn:active {
  transform: translateY(0) scale(0.98);
}

@keyframes actionGlowMove {
  0%,
  100% {
    transform: translate3d(0, 0, 0) scale(1);
    opacity: 0.9;
  }
  50% {
    transform: translate3d(-10px, 8px, 0) scale(1.06);
    opacity: 1;
  }
}

.editor-page:not(.editor-page--light) .editor-hero {
  background:
    radial-gradient(ellipse at 8% 5%, rgba(45, 212, 191, 0.14), transparent 55%),
    radial-gradient(ellipse at 90% 92%, rgba(56, 189, 248, 0.1), transparent 50%),
    linear-gradient(180deg, rgba(15, 23, 42, 0.94), rgba(2, 6, 23, 0.94));
  border-color: rgba(83, 219, 194, 0.22);
}

.editor-page:not(.editor-page--light) .editor-topbar {
  background: linear-gradient(180deg, rgba(15, 23, 42, 0.94), rgba(2, 6, 23, 0.9));
  border-color: rgba(100, 116, 139, 0.28);
}

.editor-page:not(.editor-page--light) .editor-step {
  background: rgba(30, 41, 59, 0.72);
  border-color: rgba(100, 116, 139, 0.3);
}

.editor-page:not(.editor-page--light) .panel {
  border-color: rgba(100, 116, 139, 0.26);
  background: rgba(15, 23, 42, 0.8);
  box-shadow:
    0 22px 48px rgba(0, 0, 0, 0.35),
    inset 0 1px 0 rgba(255, 255, 255, 0.05);
}

.editor-page:not(.editor-page--light) .side-chip {
  background: rgba(30, 41, 59, 0.7);
  border-color: rgba(100, 116, 139, 0.3);
}

.editor-page:not(.editor-page--light) .workspace-card :deep(.arco-input-wrapper),
.editor-page:not(.editor-page--light) .workspace-card :deep(.arco-textarea-wrapper),
.editor-page:not(.editor-page--light) .workspace-card :deep(.arco-input-number),
.editor-page:not(.editor-page--light) .workspace-aside :deep(.arco-textarea-wrapper),
.editor-page:not(.editor-page--light) .workspace-aside :deep(.arco-input-number) {
  background: rgba(15, 23, 42, 0.9);
  border-color: rgba(100, 116, 139, 0.32);
}

.editor-page:not(.editor-page--light) .workspace-card :deep(.arco-input),
.editor-page:not(.editor-page--light) .workspace-card :deep(.arco-textarea),
.editor-page:not(.editor-page--light) .workspace-card :deep(.arco-input-number-input),
.editor-page:not(.editor-page--light) .workspace-aside :deep(.arco-textarea),
.editor-page:not(.editor-page--light) .workspace-aside :deep(.arco-input-number-input) {
  color: #e2e8f0;
}

.editor-page:not(.editor-page--light) .workspace-card :deep(.arco-input::placeholder),
.editor-page:not(.editor-page--light) .workspace-card :deep(.arco-textarea::placeholder),
.editor-page:not(.editor-page--light) .workspace-aside :deep(.arco-textarea::placeholder) {
  color: #64748b;
}

.editor-page:not(.editor-page--light) .md-editor,
.editor-page:not(.editor-page--light) .answer-editor {
  background: rgba(15, 23, 42, 0.88);
  border-color: rgba(71, 85, 105, 0.44);
  box-shadow:
    0 18px 34px rgba(0, 0, 0, 0.32),
    inset 0 1px 0 rgba(255, 255, 255, 0.05);
}

.editor-page:not(.editor-page--light) .md-editor__bar,
.editor-page:not(.editor-page--light) .answer-editor__bar {
  background: rgba(15, 118, 110, 0.14);
  border-bottom-color: rgba(71, 85, 105, 0.44);
}

.editor-page:not(.editor-page--light) .md-toolbar {
  background: rgba(15, 23, 42, 0.72);
  border-color: rgba(71, 85, 105, 0.44);
}

.editor-page:not(.editor-page--light) .md-tool-btn,
.editor-page:not(.editor-page--light) .md-view-btn {
  background: rgba(30, 41, 59, 0.8);
  border-color: rgba(100, 116, 139, 0.34);
  color: #cbd5e1;
}

.editor-page:not(.editor-page--light) .answer-tool-btn {
  background: rgba(30, 41, 59, 0.82);
  border-color: rgba(100, 116, 139, 0.34);
  color: #cbd5e1;
}

.editor-page:not(.editor-page--light) .answer-tool-value {
  color: #94a3b8;
}

.editor-page:not(.editor-page--light) .md-preview {
  background: rgba(2, 6, 23, 0.35);
  color: #e2e8f0;
}

.editor-page:not(.editor-page--light) .md-preview :deep(code) {
  background: rgba(20, 184, 166, 0.2);
}

.editor-page:not(.editor-page--light) .aside-box {
  background: rgba(30, 41, 59, 0.66);
  border-color: rgba(100, 116, 139, 0.28);
}

.editor-page:not(.editor-page--light) .form-section {
  background: rgba(15, 23, 42, 0.78);
  border-color: rgba(71, 85, 105, 0.4);
}

.editor-page:not(.editor-page--light) .workspace-nav__item {
  background: rgba(30, 41, 59, 0.58);
  border-color: rgba(100, 116, 139, 0.3);
}

.editor-page:not(.editor-page--light) .workspace-nav__item strong {
  color: #e2e8f0;
}

.editor-page:not(.editor-page--light) .workspace-nav__item small {
  color: #94a3b8;
}

.editor-page:not(.editor-page--light) .workspace-nav__item.is-active {
  background: linear-gradient(180deg, rgba(20, 184, 166, 0.18), rgba(20, 184, 166, 0.06));
  border-color: rgba(45, 212, 191, 0.42);
}

.editor-page:not(.editor-page--light) .tag-pill {
  border-color: rgba(45, 212, 191, 0.34);
  box-shadow:
    0 8px 16px rgba(0, 0, 0, 0.28),
    inset 0 1px 0 rgba(255, 255, 255, 0.08);
}

.editor-page:not(.editor-page--light) .tag-pill__text {
  color: #e2e8f0;
}

.editor-page:not(.editor-page--light) .tag-pill__close {
  background: rgba(15, 23, 42, 0.55);
  color: #cbd5e1;
}

.editor-page:not(.editor-page--light) .tag-pill__close:hover {
  background: rgba(45, 212, 191, 0.18);
  color: #5eead4;
}

.editor-page:not(.editor-page--light) .action-row {
  background:
    linear-gradient(120deg, rgba(15, 118, 110, 0.22), rgba(30, 64, 175, 0.18) 42%, rgba(14, 116, 144, 0.18)),
    rgba(15, 23, 42, 0.82);
  border-color: rgba(100, 116, 139, 0.28);
}

.editor-page:not(.editor-page--light) .action-btn--ghost {
  background: rgba(30, 41, 59, 0.82);
  border-color: rgba(100, 116, 139, 0.34);
  color: #e2e8f0;
}

.editor-page:not(.editor-page--light) .action-btn--ghost:hover {
  background: rgba(30, 41, 59, 0.95);
}

.editor-page:not(.editor-page--light) .action-btn__icon {
  background: rgba(226, 232, 240, 0.18);
}

@media (max-width: 960px) {
  .editor-topbar,
  .editor-hero {
    grid-template-columns: 1fr;
    display: grid;
  }

  .workspace-layout {
    grid-template-columns: 1fr;
  }

  .workspace-nav {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .workspace-grid {
    grid-template-columns: 1fr;
  }
}

@media (max-width: 640px) {
  .editor-page {
    padding: 20px 14px 44px;
  }

  .editor-topbar,
  .editor-hero,
  .workspace-card,
  .workspace-aside {
    border-radius: 22px;
    padding: 20px;
  }

  .editor-topbar__title {
    font-size: 24px;
  }

  .tag-editor {
    flex-direction: column;
  }

  .md-main {
    grid-template-columns: 1fr;
    min-height: 340px;
  }

  .md-main > .md-editor__textarea {
    min-width: 100%;
  }

  .md-editor__textarea :deep(.arco-textarea-wrapper) {
    border-right: none;
    border-bottom: 1px solid var(--app-border);
  }

  .md-editor__textarea :deep(.arco-textarea) {
    min-height: 320px !important;
  }

  .answer-editor__textarea :deep(.arco-textarea) {
    min-width: 100%;
  }

  .action-row :deep(.arco-btn) {
    width: 100%;
    min-width: 0;
  }

  .workspace-nav {
    grid-template-columns: 1fr;
  }
}
</style>
