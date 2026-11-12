<script setup lang="ts">
import { computed, nextTick, onBeforeUnmount, provide, ref, watch, watchEffect } from 'vue'
import { Message } from '@arco-design/web-vue'
import { storeToRefs } from 'pinia'
import { useAuthStore } from '@/stores/auth'
import { useStudyAiCopilotStore } from '@/stores/studyAiCopilot'
import { useBanCountdownTick } from '@/composables/useBanCountdownTick'
import { formatBanCountdown, parseBanUntilMs } from '@/utils/formatBanCountdown'
import ModerationBanNotice from '@/components/moderation/ModerationBanNotice.vue'
import { useStudyAiCopilotStream } from '@/composables/useStudyAiCopilotStream'
import type { BailianTurn } from '@/api/bailianAssist'
import AiTabs from './AiTabs.vue'
import AiMessage from './AiMessage.vue'
import AiInput from './AiInput.vue'
import type { QuickChip } from './AiInput.vue'
import { AI_COPILOT_ACTIVE_TAB_KEY, type AiCopilotTabId } from './keys'
import AiHeroIcon from './AiHeroIcon.vue'
import AiModelPicker from './AiModelPicker.vue'
import { useAiModelsStore } from '@/stores/aiModels'

const open = defineModel<boolean>('open', { default: false })

const props = withDefaults(
  defineProps<{
    questionId: string
    questionTitle: string
    questionContent: string
    language: string
    questionType: string
    judgeContext: string
    sampleCases: string
    runResult: string
    getCode: () => string
  }>(),
  {
    questionId: '',
    questionTitle: '',
    questionContent: '',
    language: 'cpp',
    questionType: 'TEXT',
    judgeContext: '',
    sampleCases: '',
    runResult: '',
  },
)

const emit = defineEmits<{
  'insert-code': [code: string]
}>()

const quickActions = computed<QuickChip[]>(() => {
  const common: QuickChip[] = [
    { icon: '💡', label: '一级提示', text: '请只给我一级提示：指出解题方向和需要观察的规律，不要给算法步骤或代码。' },
    { icon: '🧭', label: '进阶提示', text: '请给我二级提示：列出关键算法步骤和伪代码，但不要给可直接提交的完整程序。' },
    { icon: '🔍', label: '分析代码', text: '请结合当前代码逐点分析最可能的问题，标明原因和最小修改方向，不要重写整题。' },
    { icon: '⏱', label: '复杂度检查', text: '请根据题目约束和当前代码分析时间、空间复杂度，并判断是否可能 TLE 或 MLE。' },
    { icon: '🧪', label: '边界用例', text: '请列出容易 WA 的边界情况，并给少量可手算的自测输入与预期现象。' },
  ]
  if (props.runResult.trim()) {
    common.splice(3, 0, {
      label: '分析运行结果',
      icon: '🩺',
      text: '请分析最近一次运行结果：判断属于 CE、RE、WA、TLE 还是 MLE，并对照公开样例给出排查顺序和最小修改建议。',
    })
  }
  if (props.questionType.startsWith('IMAGE_')) {
    common.splice(2, 0, {
      label: '图像题协议',
      icon: '🖼️',
      text: '请说明这道图像目标计数题在 OJ 中如何读取图片、加载模型、统计类别并按要求输出 JSON，只给步骤和关键片段。',
    })
  }
  return common
})

const hotThree = computed(() => quickActions.value.slice(0, 3))

const auth = useAuthStore()
const store = useStudyAiCopilotStore()
const aiModels = useAiModelsStore()
const { messages, draft, sending, streamingAssistantId, streamingPlainText } = storeToRefs(store)

const { nowMs: aiBanNowMs } = useBanCountdownTick()
const aiAssistBanned = computed(() => auth.isAiAssistBanned)
const aiBanCountdown = computed(() =>
  aiAssistBanned.value
    ? formatBanCountdown(parseBanUntilMs(auth.aiBanUntil ?? auth.banUntil), aiBanNowMs.value) || '计算中…'
    : '',
)
const inputDisabled = computed(() => sending.value || aiAssistBanned.value)

const activeTab = ref<AiCopilotTabId>('chat')
provide(AI_COPILOT_ACTIVE_TAB_KEY, activeTab)

const listRef = ref<HTMLElement | null>(null)

const panelWidth = ref(440)
let dragRaf: number | null = null
let pendingWidth: number | null = null

function clampWidth(w: number) {
  const max = typeof window !== 'undefined' ? window.innerWidth - 24 : 560
  return Math.min(Math.max(280, w), max)
}

function initPanelWidth() {
  if (typeof window === 'undefined') return
  panelWidth.value = clampWidth(Math.min(440, window.innerWidth - 14))
}

function onResizeHandlePointerDown(e: PointerEvent) {
  e.preventDefault()
  e.stopPropagation()
  ;(e.target as HTMLElement).setPointerCapture?.(e.pointerId)
  const startX = e.clientX
  const startW = panelWidth.value

  const onMove = (ev: PointerEvent) => {
    const dx = startX - ev.clientX
    pendingWidth = clampWidth(startW + dx)
    if (dragRaf != null) return
    dragRaf = requestAnimationFrame(() => {
      dragRaf = null
      if (pendingWidth != null) panelWidth.value = pendingWidth
    })
  }
  const onUp = (ev: PointerEvent) => {
    ;(ev.target as HTMLElement).releasePointerCapture?.(e.pointerId)
    window.removeEventListener('pointermove', onMove)
    window.removeEventListener('pointerup', onUp)
    if (dragRaf != null) cancelAnimationFrame(dragRaf)
    dragRaf = null
    pendingWidth = null
  }
  window.addEventListener('pointermove', onMove)
  window.addEventListener('pointerup', onUp)
}

const stream = useStudyAiCopilotStream((userText: string, history: BailianTurn[]) => ({
  userMessage: userText,
  questionId: props.questionId || undefined,
  questionTitle: props.questionTitle || undefined,
  questionContent: (props.questionContent || '').slice(0, 12000),
  language: props.language || undefined,
  questionType: props.questionType || undefined,
  judgeContext: (props.judgeContext || '').slice(0, 3000) || undefined,
  sampleCases: (props.sampleCases || '').slice(0, 6000) || undefined,
  runResult: (props.runResult || '').slice(0, 10000) || undefined,
  code: props.getCode?.() ?? '',
  providerCode: aiModels.selectedProvider,
  modelId: aiModels.selectedModel || undefined,
  history,
}))

const runDiagnosis = computed(() => {
  if (!props.runResult.trim()) return null
  try {
    const result = JSON.parse(props.runResult) as {
      terminalError?: boolean
      allSamplePassed?: boolean
      suspectedShortcut?: boolean
      sandboxMessage?: string
      judgeInfo?: { message?: string }
      cases?: Array<{ index?: number; expectedOutput?: string; actualOutput?: string; passed?: boolean }>
    }
    if (result.terminalError) {
      const verdict = result.judgeInfo?.message || result.sandboxMessage || '运行失败'
      return { title: verdict, evidence: '依据：本次沙箱运行状态。', next: '先查看编译或运行错误，再让助手结合当前代码定位原因。' }
    }
    const failed = result.cases?.find(item => item.passed === false)
    if (failed) {
      return {
        title: `公开样例 ${failed.index || 1} 未通过`,
        evidence: `依据：样例预期 ${String(failed.expectedOutput ?? '').slice(0, 100)}；当前输出 ${String(failed.actualOutput ?? '').slice(0, 100)}。`,
        next: '先核对输入读取和输出格式，再检查计算逻辑。仅凭输出差异不能确定具体代码错误。',
      }
    }
    if (result.allSamplePassed) {
      return {
        title: '公开样例已通过',
        evidence: '依据：本次公开样例的运行与输出比对。',
        next: result.suspectedShortcut ? '代码可能只输出固定值；请确认它会读取输入，并提交以验证隐藏用例。' : '继续提交代码验证隐藏用例；样例通过不代表整题通过。',
      }
    }
  } catch { /* 旧版非 JSON 运行摘要继续交给聊天上下文处理。 */ }
  return null
})

function askRunDiagnosis() {
  if (inputDisabled.value) return
  activeTab.value = 'chat'
  void stream.sendUserMessage('请根据刚才的运行诊断、题面、公开样例和当前代码，说明最可能的问题、每项判断的依据，以及我下一步该检查什么。信息不足时明确说不确定，不要给完整可提交答案。', listRef.value)
}

function onModelChange() {
  stream.stopGeneration()
  store.clearChat()
  Message.info('已切换模型，开始新的对话')
}

function onSend() {
  if (aiAssistBanned.value) return
  void stream.sendUserMessage(draft.value, listRef.value)
}

const hasMessages = computed(() => messages.value.length > 0)

const welcomeLine = ref('')
const welcomeFull = '我会按提示层级引导你，也能分析最近一次运行结果。'
let welcomeTimer: ReturnType<typeof setInterval> | null = null

function clearWelcomeTimer() {
  if (welcomeTimer != null) {
    clearInterval(welcomeTimer)
    welcomeTimer = null
  }
}

function startWelcomeTypewriter() {
  clearWelcomeTimer()
  welcomeLine.value = ''
  let i = 0
  welcomeTimer = setInterval(() => {
    i += 1
    welcomeLine.value = welcomeFull.slice(0, i)
    if (i >= welcomeFull.length) clearWelcomeTimer()
  }, 36)
}

watch(
  () => [props.questionId, auth.userId, aiModels.selectedProvider, aiModels.selectedModel] as const,
  ([id, userId, providerCode, modelId]) => {
    if (id) store.bindSession(`${userId || 'guest'}:${id}:${providerCode}:${modelId}`)
  },
  { immediate: true },
)

watch(open, (v) => {
  if (v) {
    initPanelWidth()
    void stream.scrollToEnd(listRef.value)
    if (!hasMessages.value) startWelcomeTypewriter()
  } else {
    clearWelcomeTimer()
  }
})

watch(hasMessages, (h) => {
  if (h) clearWelcomeTimer()
})

watchEffect((onCleanup) => {
  if (!open.value) return
  const fn = (e: KeyboardEvent) => {
    if (e.key !== 'Escape') return
    if (sending.value) {
      e.preventDefault()
      stream.stopGeneration()
    } else {
      open.value = false
    }
  }
  window.addEventListener('keydown', fn)
  onCleanup(() => window.removeEventListener('keydown', fn))
})

function clearChat() {
  store.clearChat()
  if (open.value && !hasMessages.value) startWelcomeTypewriter()
}

function close() {
  open.value = false
}

function onInsertCode(code: string) {
  emit('insert-code', code)
}

onBeforeUnmount(() => {
  clearWelcomeTimer()
})
</script>

<template>
  <Teleport to="body">
    <Transition name="study-copilot-ctx">
      <div v-if="open" class="study-copilot-overlay" role="presentation" @click.self="close">
        <aside
          class="study-copilot tw-relative tw-flex tw-shrink-0 tw-flex-col tw-overflow-hidden tw-rounded-2xl tw-border tw-border-black/[0.08] tw-bg-[var(--app-surface-strong,rgba(255,248,238,0.96))] dark:tw-border-white/10"
          role="dialog"
          aria-modal="true"
          aria-labelledby="study-copilot-title"
          :style="{ width: `${panelWidth}px`, maxHeight: 'calc(100vh - 16px)' }"
          @click.stop
        >
          <button
            type="button"
            class="tw-absolute tw-left-0 tw-top-0 tw-z-20 tw-h-full tw-w-2 tw-cursor-ew-resize tw-border-0 tw-bg-transparent tw-p-0 tw-outline-none hover:tw-bg-teal-500/10"
            aria-label="拖动调整宽度"
            @pointerdown="onResizeHandlePointerDown"
          />

          <div class="tw-pointer-events-none tw-absolute tw-inset-0 tw-opacity-[0.06]" aria-hidden="true">
            <div
              class="tw-h-full tw-w-full tw-bg-[radial-gradient(ellipse_120%_80%_at_100%_0%,color-mix(in_srgb,var(--app-accent,#0f8c7a)_22%,transparent),transparent_55%)]"
            />
          </div>

          <header
            class="assistant-header tw-relative tw-z-10 tw-flex tw-items-start tw-justify-between tw-gap-3 tw-border-b tw-border-black/[0.06] tw-px-4 tw-pb-4 tw-pt-4 dark:tw-border-white/10"
          >
            <div class="tw-flex tw-min-w-0 tw-items-center tw-gap-3">
              <span
                class="assistant-logo tw-grid tw-h-11 tw-w-11 tw-shrink-0 tw-place-items-center tw-rounded-2xl tw-bg-gradient-to-br tw-from-teal-500 tw-to-cyan-600 tw-shadow-lg tw-shadow-teal-500/30"
                aria-hidden="true"
              >
                <AiHeroIcon name="sparkles" class="tw-text-white" />
              </span>
              <div class="tw-min-w-0">
                <h2 id="study-copilot-title" class="tw-m-0 tw-truncate tw-text-base tw-font-extrabold tw-tracking-tight tw-text-zinc-900 dark:tw-text-zinc-50">
                  智能做题助手
                </h2>
                <p class="tw-m-0 tw-mt-1 tw-text-xs tw-leading-snug tw-text-zinc-500 dark:tw-text-zinc-400">
                  分层提示 · 代码诊断 · 评测结果分析
                </p>
              </div>
            </div>
            <div class="tw-flex tw-shrink-0 tw-items-center tw-gap-2">
              <button
                type="button"
                class="tw-rounded-lg tw-border tw-border-black/[0.08] tw-bg-white/60 tw-px-2.5 tw-py-1.5 tw-text-xs tw-font-semibold tw-text-zinc-600 tw-backdrop-blur disabled:tw-opacity-40 dark:tw-border-white/12 dark:tw-bg-zinc-900/50 dark:tw-text-zinc-300"
                :disabled="!hasMessages"
                @click="clearChat"
              >
                清空
              </button>
              <button
                type="button"
                class="tw-grid tw-h-9 tw-w-9 tw-place-items-center tw-rounded-lg tw-border tw-border-black/[0.08] tw-bg-white/70 tw-text-zinc-500 tw-backdrop-blur hover:tw-border-rose-300 hover:tw-text-rose-600 dark:tw-border-white/12 dark:tw-bg-zinc-900/50 dark:tw-text-zinc-300"
                aria-label="关闭"
                @click="close"
              >
                ✕
              </button>
            </div>
          </header>

          <div class="context-strip tw-relative tw-z-10 tw-mx-4 tw-my-3">
            <span class="context-strip__dot" />
            <div><strong>OJ 上下文已同步</strong><small>题面 · 公开样例 · 当前代码<span v-if="props.runResult"> · 运行结果</span></small></div>
            <span class="context-strip__type">{{ props.questionType.startsWith('IMAGE_') ? '图像题' : '编程题' }}</span>
          </div>
          <div class="tw-relative tw-z-10 tw-mx-4 tw-mb-3"><AiModelPicker :disabled="sending" @change="onModelChange" /></div>
          <div v-if="runDiagnosis" class="tw-relative tw-z-10 tw-mx-4 tw-mb-3 tw-rounded-xl tw-border tw-border-teal-500/25 tw-bg-teal-500/[0.07] tw-p-3 tw-text-xs tw-leading-relaxed tw-text-zinc-700 dark:tw-text-zinc-200">
            <div class="tw-flex tw-items-center tw-justify-between tw-gap-2"><strong>本次运行诊断 · {{ runDiagnosis.title }}</strong><button type="button" class="tw-shrink-0 tw-font-semibold tw-text-teal-700 disabled:tw-opacity-40 dark:tw-text-teal-300" :disabled="inputDisabled" @click="askRunDiagnosis">让 AI 结合代码分析 →</button></div>
            <p class="tw-m-0 tw-mt-2">{{ runDiagnosis.evidence }}</p>
            <p class="tw-m-0 tw-mt-1 tw-text-zinc-500 dark:tw-text-zinc-400">下一步：{{ runDiagnosis.next }}</p>
          </div>
          <div class="tw-relative tw-z-10 tw-mx-4 tw-mb-2">
            <ModerationBanNotice
              v-if="aiAssistBanned"
              variant="ai"
              compact
              title="学习助手已锁定"
              description="检测到不当提问内容，本面板输入已暂停。"
              :countdown="aiBanCountdown"
              :reason="(auth.aiBanReason ?? auth.banReason ?? '').trim()"
              footer="到期后自动恢复；如需提前解除请联系管理员。"
            />
          </div>

          <div class="tw-relative tw-z-10 tw-px-4 tw-pb-2">
            <AiTabs />
          </div>

          <div class="tw-relative tw-z-10 tw-min-h-0 tw-flex-1 tw-px-4">
            <div v-show="activeTab === 'chat'" class="tw-flex tw-h-full tw-min-h-0 tw-flex-col tw-gap-2">
              <div
                ref="listRef"
                class="tw-min-h-[200px] tw-max-h-[min(52vh,520px)] tw-flex-1 tw-overflow-y-auto tw-rounded-xl tw-border tw-border-black/[0.05] tw-bg-white/30 tw-px-2 tw-py-2 tw-backdrop-blur-sm dark:tw-border-white/8 dark:tw-bg-zinc-950/25"
                style="contain: layout"
              >
                <div v-if="!hasMessages" class="tw-flex tw-flex-col tw-items-center tw-gap-4 tw-px-2 tw-py-7 tw-text-center">
                  <div class="empty-orb"><AiHeroIcon name="sparkles" size="lg" class="tw-text-teal-500 dark:tw-text-teal-300" /></div>
                  <div><p class="tw-m-0 tw-text-base tw-font-extrabold tw-text-zinc-800 dark:tw-text-zinc-100">你的 OJ 解题搭档</p><p class="tw-m-0 tw-mt-1 tw-text-[11px] tw-text-zinc-400">不给标程，带你一步步找到答案</p></div>
                  <p class="tw-m-0 tw-max-w-[280px] tw-text-xs tw-leading-relaxed tw-text-zinc-500 dark:tw-text-zinc-400">
                    {{ welcomeLine }}<span class="tw-inline-block tw-w-1 tw-animate-ai-type-caret">|</span>
                  </p>
                  <div class="tw-flex tw-flex-wrap tw-justify-center tw-gap-2">
                    <button
                      v-for="(h, i) in hotThree"
                      :key="i"
                      type="button"
                      class="tw-rounded-xl tw-border tw-border-teal-500/20 tw-bg-white/70 tw-px-3 tw-py-2 tw-text-[11px] tw-font-semibold tw-text-teal-900 tw-shadow-sm tw-transition hover:-tw-translate-y-0.5 hover:tw-border-teal-400 hover:tw-shadow-md disabled:tw-opacity-40 dark:tw-bg-zinc-900/60 dark:tw-text-teal-100"
                      :disabled="inputDisabled"
                      @click="stream.sendUserMessage(h.text, listRef)"
                    >
                      <span class="tw-mr-1">{{ h.icon }}</span>{{ h.label }}
                    </button>
                  </div>
                </div>
                <div v-else class="tw-flex tw-flex-col tw-gap-2.5">
                  <AiMessage
                    v-for="m in messages"
                    :key="m.id"
                    :message="m"
                    :is-streaming="sending && streamingAssistantId === m.id"
                    :streaming-plain-text="streamingPlainText"
                    @insert-code="onInsertCode"
                  />
                </div>
              </div>

              <div class="tw-pb-3 tw-pt-1">
                <AiInput :quick-actions="quickActions" :disabled="inputDisabled" @send="onSend" @stop="stream.stopGeneration()" />
              </div>
            </div>

            <div
              v-show="activeTab === 'templates'"
              class="tw-flex tw-h-full tw-min-h-[240px] tw-flex-col tw-gap-2 tw-overflow-y-auto tw-rounded-xl tw-border tw-border-black/[0.05] tw-bg-white/30 tw-p-3 tw-backdrop-blur-sm dark:tw-border-white/8 dark:tw-bg-zinc-950/25"
            >
              <p class="tw-m-0 tw-text-xs tw-font-semibold tw-text-zinc-600 dark:tw-text-zinc-300">快捷模板（点击填入并发送）</p>
              <div class="tw-flex tw-flex-col tw-gap-2">
                <button
                  v-for="(c, i) in quickActions"
                  :key="i"
                  type="button"
                  class="tw-rounded-xl tw-border tw-border-white/20 tw-bg-white/55 tw-px-3 tw-py-2 tw-text-left tw-text-xs tw-font-medium tw-text-zinc-800 tw-backdrop-blur-md tw-transition hover:tw-border-teal-400/45 hover:tw-bg-white/80 disabled:tw-opacity-40 dark:tw-border-white/10 dark:tw-bg-zinc-900/40 dark:tw-text-zinc-100"
                  :disabled="inputDisabled"
                  @click="stream.sendUserMessage(c.text, listRef); activeTab = 'chat'"
                >
                  <span class="tw-font-bold tw-text-teal-700 dark:tw-text-teal-300">{{ c.label }}</span>
                  <span class="tw-mt-1 tw-line-clamp-2 tw-block tw-text-[11px] tw-text-zinc-500 dark:tw-text-zinc-400">{{ c.text }}</span>
                </button>
              </div>
            </div>
          </div>
        </aside>
      </div>
    </Transition>
  </Teleport>
</template>

<style scoped>
.study-copilot-overlay {
  position: fixed;
  inset: 0;
  z-index: 10050;
  background: rgba(0, 0, 0, 0.2);
  display: flex;
  justify-content: flex-end;
  align-items: stretch;
}
:global(html[data-theme='dark']) .study-copilot-overlay {
  background: rgba(0, 0, 0, 0.52);
}
.study-copilot {
  align-self: stretch;
  margin: 8px 8px 8px 0;
  min-height: min(480px, calc(100vh - 16px));
  font-family: var(--app-font-body, 'Segoe UI Variable', 'Microsoft YaHei UI', sans-serif);
  box-shadow:
    -10px 0 40px rgba(0, 0, 0, 0.1),
    -2px 0 0 color-mix(in srgb, var(--app-border, rgba(92, 68, 46, 0.12)) 65%, transparent),
    var(--app-card-shadow, 0 12px 32px rgba(70, 41, 14, 0.08));
}
.assistant-header { background: linear-gradient(135deg, rgba(20,184,166,.11), rgba(6,182,212,.04) 55%, transparent); }
.assistant-logo { position: relative; }
.assistant-logo::after { content: ''; position: absolute; inset: -4px; border: 1px solid rgba(20,184,166,.22); border-radius: 20px; }
.context-strip { display: flex; align-items: center; gap: 10px; padding: 9px 11px; border: 1px solid rgba(20,184,166,.16); border-radius: 13px; background: rgba(20,184,166,.055); }
.context-strip__dot { width: 8px; height: 8px; flex: 0 0 auto; border-radius: 50%; background: #10b981; box-shadow: 0 0 0 4px rgba(16,185,129,.12); }
.context-strip div { min-width: 0; display: grid; gap: 1px; }
.context-strip strong { font-size: 11px; color: var(--app-text-main); }
.context-strip small { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: 10px; color: var(--app-text-subtle); }
.context-strip__type { margin-left: auto; flex: 0 0 auto; padding: 4px 7px; border-radius: 999px; background: rgba(20,184,166,.12); color: #0f766e; font-size: 10px; font-weight: 800; }
.empty-orb { display: grid; place-items: center; width: 88px; height: 88px; border-radius: 28px; background: radial-gradient(circle at 32% 25%, #fff, rgba(20,184,166,.12)); box-shadow: 0 18px 45px rgba(13,148,136,.15), inset 0 0 0 1px rgba(20,184,166,.12); transform: rotate(-3deg); }
.study-copilot-ctx-enter-active,
.study-copilot-ctx-leave-active {
  transition: opacity 0.22s ease;
}
.study-copilot-ctx-enter-from,
.study-copilot-ctx-leave-to {
  opacity: 0;
}
.study-copilot-ctx-enter-active .study-copilot,
.study-copilot-ctx-leave-active .study-copilot {
  transition: transform 0.26s cubic-bezier(0.22, 1, 0.36, 1);
}
.study-copilot-ctx-enter-from .study-copilot,
.study-copilot-ctx-leave-to .study-copilot {
  transform: translateX(12px);
}
</style>
