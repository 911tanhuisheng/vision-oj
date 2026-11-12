<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { Message, Modal } from '@arco-design/web-vue'
import { ContestAdminControllerService, ContestControllerService } from '@generated'
import { mapApiError } from '@/api/mapApiError'
import { getBackendErrorMessage } from '@/api/httpError'
import { isResultSuccess } from '@/api/result'

type ContestRow = {
  id: string
  title: string
  startTime?: string
  endTime?: string
  phase?: string
  questionCount?: number
}

type PageData<T> = {
  records: T[]
  total: number
  current: number
  size: number
}

type QRow = {
  questionId: string
  fullScore: number
  sortOrder: string
}

type ContestDetail = {
  id: string
  title?: string
  description?: string
  startTime?: string
  endTime?: string
  questions?: Array<{
    questionId: string
    fullScore?: number
    sortOrder?: number
  }>
}

const router = useRouter()

const loading = ref(false)
const saving = ref(false)
const deleting = ref(false)
const rows = ref<ContestRow[]>([])
const total = ref(0)
const current = ref(1)
const pageSize = ref(10)

const queryTitle = ref('')
const selectedIds = ref<string[]>([])

const modalOpen = ref(false)
const modalMode = ref<'add' | 'edit'>('add')
const editingId = ref('')
const formTitle = ref('')
const formDesc = ref('')
/** Arco DatePicker：毫秒时间戳 */
const formStartMs = ref<number | undefined>()
const formEndMs = ref<number | undefined>()
const formQuestions = ref<QRow[]>([{ questionId: '', fullScore: 100, sortOrder: '' }])

const totalPages = computed(() => Math.max(1, Math.ceil(total.value / pageSize.value)))
const allSelected = computed(() => rows.value.length > 0 && selectedIds.value.length === rows.value.length)
const hasSelection = computed(() => selectedIds.value.length > 0)

function phaseClass(phase?: string): string {
  if (phase === '进行中') return 'phase--live'
  if (phase === '未开始') return 'phase--soon'
  return 'phase--past'
}

function buildPagePayload() {
  const payload: Record<string, unknown> = {
    current: current.value,
    pageSize: pageSize.value,
  }
  if (queryTitle.value.trim()) payload.title = queryTitle.value.trim()
  return payload
}

async function fetchList() {
  loading.value = true
  try {
    const data = await ContestAdminControllerService.page(buildPagePayload())
    if (!isResultSuccess(data.code)) {
      Message.error(data.message || '加载赛事失败')
      return
    }
    const page = data.data
    rows.value =
      page?.records?.map((r) => ({
        id: String(r.id ?? ''),
        title: r.title ?? '',
        startTime: r.startTime,
        endTime: r.endTime,
        phase: r.phase,
        questionCount: r.questionCount,
      })) ?? []
    selectedIds.value = []
    total.value = page?.total ?? 0
    current.value = page?.current ?? current.value
    pageSize.value = page?.size ?? pageSize.value
  } catch (e) {
    Message.error(mapApiError(e, '加载赛事失败'))
  } finally {
    loading.value = false
  }
}

function resetForm() {
  formTitle.value = ''
  formDesc.value = ''
  formStartMs.value = undefined
  formEndMs.value = undefined
  formQuestions.value = [{ questionId: '', fullScore: 100, sortOrder: '' }]
}

function openCreate() {
  modalMode.value = 'add'
  editingId.value = ''
  resetForm()
  modalOpen.value = true
}

function addQuestionRow() {
  formQuestions.value.push({ questionId: '', fullScore: 100, sortOrder: '' })
}

function removeQuestionRow(i: number) {
  if (formQuestions.value.length <= 1) {
    formQuestions.value = [{ questionId: '', fullScore: 100, sortOrder: '' }]
    return
  }
  formQuestions.value.splice(i, 1)
}

async function openEdit(row: ContestRow) {
  modalMode.value = 'edit'
  editingId.value = row.id
  resetForm()
  modalOpen.value = true
  try {
    const data = await ContestControllerService.getContest(row.id)
    if (!isResultSuccess(data.code) || !data.data) {
      Message.error(data.message || '加载赛事详情失败')
      modalOpen.value = false
      return
    }
    const d = data.data as unknown as ContestDetail
    formTitle.value = d.title ?? ''
    formDesc.value = d.description ?? ''
    if (d.startTime) formStartMs.value = Date.parse(d.startTime)
    if (d.endTime) formEndMs.value = Date.parse(d.endTime)
    const qs = d.questions
    if (qs && qs.length) {
      formQuestions.value = qs.map((q) => ({
        questionId: String(q.questionId ?? ''),
        fullScore: q.fullScore ?? 100,
        sortOrder: q.sortOrder != null ? String(q.sortOrder) : '',
      }))
    }
  } catch (e) {
    Message.error(mapApiError(e, getBackendErrorMessage(e, '加载赛事详情失败')))
    modalOpen.value = false
  }
}

/**
 * 题目 id 为雪花 Long，严禁 Number(id) —— 会丢精度导致后端查不到题。
 * 请求体里保持字符串，由后端解析为 Long。
 */
function buildQuestionsPayload(): Array<{ questionId: string; fullScore?: number; sortOrder?: number }> {
  const out: Array<{ questionId: string; fullScore?: number; sortOrder?: number }> = []
  formQuestions.value.forEach((r, idx) => {
    const qid = r.questionId.trim()
    if (!qid || !/^\d+$/.test(qid)) return
    const item: { questionId: string; fullScore?: number; sortOrder?: number } = {
      questionId: qid,
      fullScore: Number.isFinite(r.fullScore) && r.fullScore > 0 ? Math.floor(r.fullScore) : 100,
    }
    const so = r.sortOrder.trim()
    if (so && /^\d+$/.test(so)) item.sortOrder = Number(so)
    else item.sortOrder = idx + 1
    out.push(item)
  })
  return out
}

/** @returns 是否关闭弹窗 */
async function submitModal(): Promise<boolean> {
  const title = formTitle.value.trim()
  if (!title) {
    Message.warning('请填写赛事标题')
    return false
  }
  if (formStartMs.value == null || formEndMs.value == null) {
    Message.warning('请选择开始与结束时间')
    return false
  }
  if (formEndMs.value <= formStartMs.value) {
    Message.warning('结束时间必须晚于开始时间')
    return false
  }
  const questions = buildQuestionsPayload()

  saving.value = true
  try {
    if (modalMode.value === 'add') {
      const data = await ContestAdminControllerService.add({
        title,
        description: formDesc.value.trim() || undefined,
        startTime: new Date(formStartMs.value).toISOString(),
        endTime: new Date(formEndMs.value).toISOString(),
        questions: questions.length ? questions : undefined,
      })
      if (!isResultSuccess(data.code)) {
        Message.error(data.message || '保存失败')
        return false
      }
    } else {
      const data = await ContestAdminControllerService.update({
        id: editingId.value,
        title,
        description: formDesc.value.trim() || undefined,
        startTime: new Date(formStartMs.value).toISOString(),
        endTime: new Date(formEndMs.value).toISOString(),
        questions: questions.length ? questions : undefined,
      })
      if (!isResultSuccess(data.code)) {
        Message.error(data.message || '保存失败')
        return false
      }
    }
    Message.success(modalMode.value === 'add' ? '创建成功' : '保存成功')
    await fetchList()
    return true
  } catch (e) {
    Message.error(mapApiError(e, '保存失败'))
    return false
  } finally {
    saving.value = false
  }
}

function onToggleSelectAll(checked: boolean) {
  selectedIds.value = checked ? rows.value.map((r) => r.id) : []
}

function toggleRowSelection(id: string, checked: boolean) {
  if (checked) {
    if (!selectedIds.value.includes(id)) selectedIds.value.push(id)
  } else {
    selectedIds.value = selectedIds.value.filter((x) => x !== id)
  }
}

async function requestDelete(ids: string[], tip: string) {
  deleting.value = true
  try {
    const data = await ContestAdminControllerService.deleteBatch({
      ids,
    })
    if (!isResultSuccess(data.code)) {
      Message.error(data.message || '删除失败')
      return
    }
    Message.success(tip)
    await fetchList()
  } catch (e) {
    Message.error(mapApiError(e, '删除失败'))
  } finally {
    deleting.value = false
  }
}

function onDeleteOne(item: ContestRow) {
  Modal.warning({
    title: '确认删除',
    content: `确定删除赛事「${item.title}」吗？将一并移除报名与榜单缓存，不可恢复。`,
    hideCancel: false,
    onOk: () => requestDelete([item.id], '删除成功'),
  })
}

function onDeleteSelected() {
  if (!selectedIds.value.length) {
    Message.warning('请先勾选要删除的赛事')
    return
  }
  Modal.warning({
    title: '确认批量删除',
    content: `确定删除已选择的 ${selectedIds.value.length} 场赛事吗？不可恢复。`,
    hideCancel: false,
    onOk: () => requestDelete([...selectedIds.value], '批量删除成功'),
  })
}

function onSearch() {
  current.value = 1
  fetchList()
}

function onReset() {
  queryTitle.value = ''
  current.value = 1
  fetchList()
}

function onPrevPage() {
  if (current.value <= 1) return
  current.value -= 1
  fetchList()
}

function onNextPage() {
  if (current.value >= totalPages.value) return
  current.value += 1
  fetchList()
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
      hour12: false,
    })
    .replace(/\//g, '-')
}

function getRowNo(index: number): number {
  return (current.value - 1) * pageSize.value + index + 1
}

function goPublicContest(row: ContestRow) {
  const { href } = router.resolve({ name: 'contest-rank-detail', params: { contestId: row.id } })
  window.open(href, '_blank')
}

onMounted(fetchList)
</script>

<template>
  <div class="admin-page">
    <section class="admin-hero panel">
      <div>
        <p class="eyebrow">Contest Admin</p>
        <h1>赛事管理</h1>
        <p class="lead">仅管理员可见：创建限时赛、绑定题库题目与满分，与题目管理配合完成完整办赛流程。</p>
      </div>
      <div class="hero-actions">
        <a-button type="primary" size="large" class="hero-btn hero-btn--primary" @click="openCreate">新建赛事</a-button>
        <a-button
          status="danger"
          class="hero-btn hero-btn--danger"
          :disabled="!hasSelection || deleting"
          :loading="deleting"
          @click="onDeleteSelected"
        >
          删除已选（{{ selectedIds.length }}）
        </a-button>
      </div>
    </section>

    <section class="table-shell panel">
      <div class="table-head">
        <div>
          <p class="table-eyebrow">筛选</p>
          <h2>赛事列表</h2>
          <p class="table-meta">共 {{ total }} 场 · 第 {{ current }} / {{ totalPages }} 页</p>
        </div>
        <div class="meta-pills">
          <span class="meta-pill">{{ loading ? '加载中' : '已同步' }}</span>
        </div>
      </div>

      <div class="filter-row">
        <div class="filter-main">
          <a-input v-model="queryTitle" allow-clear placeholder="按标题搜索" style="max-width: 280px" />
          <a-button type="primary" class="query-btn query-btn--primary" :loading="loading" @click="onSearch">查询</a-button>
          <a-button class="query-btn query-btn--ghost" @click="onReset">重置</a-button>
        </div>
      </div>

      <div class="table-wrap">
        <table class="user-table">
          <thead>
            <tr>
              <th>序号</th>
              <th>
                <label class="check-wrap">
                  <input type="checkbox" :checked="allSelected" @change="onToggleSelectAll(($event.target as HTMLInputElement).checked)" />
                  <span>全选</span>
                </label>
              </th>
              <th>标题</th>
              <th>状态</th>
              <th>开始</th>
              <th>结束</th>
              <th>赛题数</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-if="loading">
              <td colspan="8" class="empty-cell">正在加载…</td>
            </tr>
            <tr v-else-if="!rows.length">
              <td colspan="8" class="empty-cell">暂无赛事，点击「新建赛事」开始</td>
            </tr>
            <tr v-for="(item, index) in rows" v-else :key="item.id">
              <td>{{ getRowNo(index) }}</td>
              <td>
                <input
                  type="checkbox"
                  :checked="selectedIds.includes(item.id)"
                  @change="toggleRowSelection(item.id, ($event.target as HTMLInputElement).checked)"
                />
              </td>
              <td class="title-cell">{{ item.title }}</td>
              <td>
                <span class="phase-pill" :class="phaseClass(item.phase)">{{ item.phase ?? '—' }}</span>
              </td>
              <td class="cell-muted">{{ formatDateTime(item.startTime) }}</td>
              <td class="cell-muted">{{ formatDateTime(item.endTime) }}</td>
              <td>{{ item.questionCount ?? 0 }}</td>
              <td class="op-cell">
                <a-space>
                  <a-button size="mini" class="row-btn row-btn--ghost" @click="goPublicContest(item)">预览</a-button>
                  <a-button size="mini" class="row-btn row-btn--edit" @click="openEdit(item)">编辑</a-button>
                  <a-button size="mini" status="danger" class="row-btn row-btn--delete" @click="onDeleteOne(item)">删除</a-button>
                </a-space>
              </td>
            </tr>
          </tbody>
        </table>
      </div>

      <div class="pager-row">
        <a-button class="pager-btn" :disabled="current <= 1 || loading" @click="onPrevPage">上一页</a-button>
        <span>第 {{ current }} / {{ totalPages }} 页</span>
        <a-button class="pager-btn" :disabled="current >= totalPages || loading" @click="onNextPage">下一页</a-button>
      </div>
    </section>

    <a-modal
      v-model:visible="modalOpen"
      :title="modalMode === 'add' ? '新建赛事' : '编辑赛事'"
      width="720px"
      :ok-loading="saving"
      unmount-on-close
      @before-ok="submitModal"
    >
      <div class="modal-form">
        <label class="field">
          <span class="label">标题</span>
          <a-input v-model="formTitle" placeholder="例如：春季新手赛 · 练习场" allow-clear />
        </label>
        <label class="field">
          <span class="label">说明</span>
          <a-textarea v-model="formDesc" placeholder="面向用户的规则说明（可选）" :auto-size="{ minRows: 2, maxRows: 6 }" />
        </label>
        <div class="field field--row">
          <label class="field-inline">
            <span class="label">开始时间</span>
            <a-date-picker v-model="formStartMs" show-time style="width: 100%" placeholder="选择开始时间" />
          </label>
          <label class="field-inline">
            <span class="label">结束时间</span>
            <a-date-picker v-model="formEndMs" show-time style="width: 100%" placeholder="选择结束时间" />
          </label>
        </div>

        <div class="q-block">
          <div class="q-head">
            <span class="label">赛题绑定</span>
            <a-button type="outline" size="small" @click="addQuestionRow">添加一行</a-button>
          </div>
          <p class="q-hint">
            填写题库中的<strong>题目 ID</strong>（仅数字，建议整段复制）。雪花 ID 很长时<strong>不要用计算器或表格把它当成数字处理</strong>，否则会错位。得分默认 100；顺序留空则按行号 1、2、3…
          </p>
          <div v-for="(row, i) in formQuestions" :key="i" class="q-row">
            <a-input v-model="row.questionId" placeholder="题目 ID" class="q-inp" />
            <a-input-number v-model="row.fullScore" :min="1" :max="10000" placeholder="满分" class="q-num" />
            <a-input v-model="row.sortOrder" placeholder="顺序(可选)" class="q-inp q-inp--narrow" />
            <a-button size="mini" type="text" status="danger" @click="removeQuestionRow(i)">移除</a-button>
          </div>
        </div>
      </div>
    </a-modal>
  </div>
</template>

<style scoped>
.admin-page {
  position: relative;
  isolation: isolate;
  width: min(1720px, calc(100vw - 28px));
  margin: 0 auto;
  padding: 28px 12px 64px;
}

.admin-page::before,
.admin-page::after {
  content: '';
  position: absolute;
  z-index: -1;
  border-radius: 999px;
  filter: blur(38px);
  opacity: 0.45;
  pointer-events: none;
  animation: floatGlow 9s ease-in-out infinite;
}

.admin-page::before {
  width: 280px;
  height: 280px;
  top: 6px;
  left: -64px;
  background: radial-gradient(circle, color-mix(in srgb, var(--app-accent) 70%, transparent), transparent 72%);
}

.admin-page::after {
  width: 320px;
  height: 320px;
  right: -70px;
  top: 180px;
  background: radial-gradient(circle, color-mix(in srgb, #7c3aed 58%, transparent), transparent 74%);
  animation-delay: -2.4s;
}

@keyframes floatGlow {
  0%,
  100% {
    transform: translate(0, 0);
  }
  50% {
    transform: translate(10px, -8px);
  }
}

.panel {
  border: 1px solid var(--app-border);
  background: linear-gradient(145deg, color-mix(in srgb, var(--app-surface) 94%, #fff 6%), var(--app-surface));
  box-shadow: var(--app-card-shadow);
  backdrop-filter: blur(18px);
}

.admin-hero,
.table-shell {
  border-radius: 28px;
}

.admin-hero {
  display: flex;
  justify-content: space-between;
  gap: 24px;
  padding: 32px 34px;
  align-items: flex-end;
  flex-wrap: wrap;
  margin-bottom: 22px;
}

.eyebrow {
  font-size: 12px;
  letter-spacing: 0.14em;
  text-transform: uppercase;
  color: var(--color-text-3);
  margin: 0 0 8px;
}

.admin-hero h1 {
  margin: 0 0 10px;
  font-size: clamp(26px, 3vw, 32px);
  font-weight: 800;
  letter-spacing: -0.02em;
}

.lead {
  margin: 0;
  max-width: 560px;
  color: var(--color-text-2);
  line-height: 1.65;
  font-size: 15px;
}

.hero-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
}

.hero-btn {
  border-radius: 14px;
  font-weight: 600;
}

.table-shell {
  padding: 26px 28px 32px;
}

.table-head {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 16px;
  margin-bottom: 18px;
}

.table-eyebrow {
  margin: 0 0 6px;
  font-size: 12px;
  color: var(--color-text-3);
  letter-spacing: 0.08em;
}

.table-head h2 {
  margin: 0 0 6px;
  font-size: 1.2rem;
  font-weight: 800;
}

.table-meta {
  margin: 0;
  font-size: 13px;
  color: var(--color-text-3);
}

.meta-pills {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}

.meta-pill {
  font-size: 12px;
  padding: 6px 12px;
  border-radius: 999px;
  background: var(--color-fill-2);
  color: var(--color-text-3);
  font-weight: 600;
}

.filter-row {
  margin-bottom: 18px;
}

.filter-main {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  align-items: center;
}

.query-btn {
  border-radius: 12px;
}

.table-wrap {
  overflow-x: auto;
  border-radius: 16px;
  border: 1px solid var(--color-border-2);
}

.user-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 14px;
}

.user-table th,
.user-table td {
  padding: 12px 14px;
  text-align: left;
  border-bottom: 1px solid var(--color-border-2);
}

.user-table thead th {
  font-weight: 700;
  color: var(--color-text-2);
  background: color-mix(in srgb, var(--color-fill-2) 70%, transparent);
}

.title-cell {
  font-weight: 600;
  max-width: 280px;
}

.cell-muted {
  color: var(--color-text-3);
  font-size: 13px;
}

.phase-pill {
  display: inline-block;
  padding: 2px 10px;
  border-radius: 999px;
  font-size: 12px;
  font-weight: 700;
}

.phase--live {
  background: color-mix(in srgb, rgb(var(--arcoblue-6)) 18%, transparent);
  color: rgb(var(--arcoblue-6));
}

.phase--soon {
  background: color-mix(in srgb, rgb(var(--orange-6)) 16%, transparent);
  color: rgb(var(--orange-6));
}

.phase--past {
  background: var(--color-fill-3);
  color: var(--color-text-3);
}

.check-wrap {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  cursor: pointer;
  user-select: none;
}

.op-cell :deep(.arco-btn-size-mini) {
  border-radius: 8px;
}

.row-btn--edit {
  color: rgb(var(--arcoblue-6));
}

.empty-cell {
  text-align: center;
  padding: 36px !important;
  color: var(--color-text-3);
}

.pager-row {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 16px;
  margin-top: 22px;
  color: var(--color-text-2);
}

.pager-btn {
  border-radius: 12px;
}

.modal-form {
  display: flex;
  flex-direction: column;
  gap: 16px;
  padding: 4px 0 8px;
}

.field {
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.field--row {
  flex-direction: row;
  flex-wrap: wrap;
  gap: 16px;
}

.field-inline {
  flex: 1;
  min-width: 200px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.label {
  font-size: 13px;
  font-weight: 700;
  color: var(--color-text-2);
}

.q-block {
  padding: 16px;
  border-radius: 14px;
  border: 1px dashed var(--color-border-2);
  background: color-mix(in srgb, var(--color-fill-2) 55%, transparent);
}

.q-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 8px;
}

.q-hint {
  margin: 0 0 12px;
  font-size: 12px;
  color: var(--color-text-3);
  line-height: 1.5;
}

.q-row {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  align-items: center;
  margin-bottom: 10px;
}

.q-inp {
  flex: 1;
  min-width: 140px;
}

.q-inp--narrow {
  max-width: 120px;
  flex: 0 1 120px;
}

.q-num {
  width: 120px;
}

html[data-theme='dark'] .user-table thead th {
  background: rgba(34, 55, 84, 0.82);
}
</style>
