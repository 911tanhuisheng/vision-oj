<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { Message, Modal } from '@arco-design/web-vue'
import { OpenAPI, Service } from '@generated'
import type { QuestionQueryRequest } from '@generated'
import { useRouter } from 'vue-router'
import { getBackendErrorMessage } from '@/api/httpError'
import { isResultSuccess } from '@/api/result'
import { mapApiError } from '@/api/mapApiError'
import { AUTH_STORAGE_KEY } from '@/config/auth-storage'
import {
  PROBLEM_DIFFICULTIES,
  PROBLEM_KNOWLEDGE_TAGS,
  PROBLEM_TYPE_OPTIONS,
  difficultyFromTags,
  knowledgeFromTags,
  problemTypeLabel,
} from '@/config/problemTaxonomy'

type QuestionRow = {
  id: string
  title: string
  questionType?: string
  tags?: string[]
  userId: string
  userNickname?: string
  acceptedNum?: number
  submitNum?: number
  createTime?: string
  updateTime?: string
}

const router = useRouter()
const loading = ref(false)
const deleting = ref(false)
const rows = ref<QuestionRow[]>([])
const total = ref(0)
const current = ref(1)
const pageSize = ref(10)

const queryTitle = ref('')
const queryUserNickname = ref('')
const queryTags = ref('')
const queryType = ref('')
const queryDifficulty = ref('')
const queryKnowledge = ref('')
const selectedIds = ref<string[]>([])

const totalPages = computed(() => Math.max(1, Math.ceil(total.value / pageSize.value)))
const activeFilterCount = computed(() => {
  let count = 0
  if (queryTitle.value.trim()) count += 1
  if (queryUserNickname.value.trim()) count += 1
  if (queryTags.value.trim()) count += 1
  if (queryType.value) count += 1
  if (queryDifficulty.value) count += 1
  if (queryKnowledge.value) count += 1
  return count
})
const allSelected = computed(() => rows.value.length > 0 && selectedIds.value.length === rows.value.length)
const hasSelection = computed(() => selectedIds.value.length > 0)

function safeJsonParse<T>(raw: string | null, fallback: T): T {
  if (!raw) return fallback
  try {
    return JSON.parse(raw) as T
  } catch {
    return fallback
  }
}

function getToken(): string {
  const auth = safeJsonParse<{ token?: string }>(localStorage.getItem(AUTH_STORAGE_KEY), {})
  return auth.token?.trim() ?? ''
}

function buildQueryPayload(): QuestionQueryRequest {
  const payload: QuestionQueryRequest = {
    current: current.value,
    pageSize: pageSize.value,
  }
  if (queryTitle.value.trim()) payload.title = queryTitle.value.trim()
  if (queryUserNickname.value.trim()) payload.userNickname = queryUserNickname.value.trim()
  if (queryType.value) payload.questionType = queryType.value
  const selectedTags = queryTags.value
      .split(',')
      .map((t) => t.trim())
      .filter(Boolean)
  if (queryDifficulty.value) selectedTags.push(queryDifficulty.value)
  if (queryKnowledge.value) selectedTags.push(queryKnowledge.value)
  if (selectedTags.length) payload.tags = [...new Set(selectedTags)]
  return payload
}

async function fetchList() {
  loading.value = true
  try {
    const data = await Service.adminPageQuestions(buildQueryPayload())
    if (!isResultSuccess(data.code)) {
      Message.error(data.message || '查询题目失败')
      return
    }
    const page = data.data
    rows.value =
      page?.records?.map((r) => ({
        id: String(r.id ?? ''),
        title: r.title ?? '',
        questionType: r.questionType,
        tags: r.tags,
        userId: String(r.userId ?? ''),
        userNickname: r.userNickname,
        acceptedNum: r.acceptedNum,
        submitNum: r.submitNum,
        createTime: r.createTime,
        updateTime: r.updateTime,
      })) ?? []
    selectedIds.value = []
    total.value = page?.total ?? 0
    current.value = page?.current ?? current.value
    pageSize.value = page?.size ?? pageSize.value
  } catch (error) {
    Message.error(mapApiError(error, getBackendErrorMessage(error, '查询题目失败')))
  } finally {
    loading.value = false
  }
}

function onToggleSelectAll(checked: boolean) {
  selectedIds.value = checked ? rows.value.map((item) => item.id) : []
}

function toggleRowSelection(id: string, checked: boolean) {
  if (checked) {
    if (!selectedIds.value.includes(id)) selectedIds.value.push(id)
    return
  }
  selectedIds.value = selectedIds.value.filter((item) => item !== id)
}

function goEdit(item: QuestionRow) {
  router.push({ name: 'admin-problem-edit', params: { id: item.id } })
}

async function requestDelete(ids: string[], tip: string) {
  deleting.value = true
  try {
    const data = await Service.adminBatchDeleteQuestions({
      ids,
    })
    if (!isResultSuccess(data.code)) {
      Message.error(data.message || '删除失败')
      return
    }
    Message.success(tip)
    await fetchList()
  } catch (error) {
    Message.error(mapApiError(error, getBackendErrorMessage(error, '删除失败')))
  } finally {
    deleting.value = false
  }
}

function onDeleteOne(item: QuestionRow) {
  Modal.warning({
    title: '确认删除',
    content: `确定删除题目「${item.title}」吗？此操作不可恢复。`,
    hideCancel: false,
    onOk: () => requestDelete([item.id], '删除成功'),
  })
}

function onDeleteSelected() {
  if (!selectedIds.value.length) {
    Message.warning('请先勾选要删除的题目')
    return
  }
  Modal.warning({
    title: '确认批量删除',
    content: `确定删除已选择的 ${selectedIds.value.length} 道题目吗？此操作不可恢复。`,
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
  queryUserNickname.value = ''
  queryTags.value = ''
  queryType.value = ''
  queryDifficulty.value = ''
  queryKnowledge.value = ''
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
      second: '2-digit',
      hour12: false,
    })
    .replace(/\//g, '-')
}

function getRowNo(index: number): number {
  return (current.value - 1) * pageSize.value + index + 1
}

onMounted(fetchList)
</script>
<template>
  <div class="admin-page">
    <section class="admin-hero panel">
      <div>
        <p class="eyebrow">Problem Admin</p>
        <h1>题目管理</h1>
        <p class="lead">按题型、难度和知识点统一管理题目，让传统编程题与图像识别题各归其类。</p>
      </div>
      <div class="hero-actions">
        <a-button
          type="primary"
          size="large"
          class="hero-btn hero-btn--primary"
          @click="router.push({ name: 'admin-problem-new' })"
        >
          新增题目
        </a-button>
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
          <p class="table-eyebrow">筛选条件</p>
          <h2>题目列表</h2>
          <p class="table-meta">共 {{ total }} 条，当前第 {{ current }} / {{ totalPages }} 页</p>
        </div>
        <div class="meta-pills">
          <span class="meta-pill">{{ activeFilterCount }} 个筛选生效</span>
          <span class="meta-pill">{{ loading ? '加载中' : '数据已同步' }}</span>
        </div>
      </div>

      <div class="filter-row">
        <div class="classification-filter">
          <label>
            <span>题型</span>
            <a-select v-model="queryType" placeholder="全部题型" allow-clear>
              <a-option v-for="option in PROBLEM_TYPE_OPTIONS.slice(1)" :key="option.value" :value="option.value">{{ option.label }}</a-option>
            </a-select>
          </label>
          <label>
            <span>难度</span>
            <a-select v-model="queryDifficulty" placeholder="全部难度" allow-clear>
              <a-option v-for="option in PROBLEM_DIFFICULTIES" :key="option" :value="option">{{ option }}</a-option>
            </a-select>
          </label>
          <label>
            <span>知识点</span>
            <a-select v-model="queryKnowledge" placeholder="全部知识点" allow-clear allow-search>
              <a-option v-for="option in PROBLEM_KNOWLEDGE_TAGS" :key="option" :value="option">{{ option }}</a-option>
            </a-select>
          </label>
        </div>
        <div class="filter-main">
          <a-input v-model="queryTitle" allow-clear placeholder="按标题搜索" />
          <a-input v-model="queryTags" allow-clear placeholder="标签，逗号分隔，如：数组,图论" />
          <a-input v-model="queryUserNickname" allow-clear placeholder="出题人昵称" />
          <a-button type="primary" class="query-btn query-btn--primary" :loading="loading" @click="onSearch">查询</a-button>
          <a-button class="query-btn query-btn--ghost" @click="onReset">重置</a-button>
        </div>
        <div class="filter-sub">
          <div class="filter-stats">
            <span class="meta-pill">当前页 {{ rows.length }} 条</span>
            <span class="meta-pill">已选 {{ selectedIds.length }} 条</span>
            <span class="meta-pill">总计 {{ total }} 条</span>
          </div>
          <div class="filter-actions">
            <a-button class="query-btn query-btn--ghost" :disabled="!hasSelection" @click="selectedIds = []">清空选择</a-button>
            <a-button
              status="danger"
              class="query-btn hero-btn--danger"
              :disabled="!hasSelection || deleting"
              :loading="deleting"
              @click="onDeleteSelected"
            >
              批量删除
            </a-button>
          </div>
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
              <th>题型</th>
              <th>难度</th>
              <th>知识点 / 标签</th>
              <th>出题人</th>
              <th>通过/提交</th>
              <th>创建时间</th>
              <th>修改时间</th>
              <th>操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-if="loading">
              <td colspan="11" class="empty-cell">正在加载题目数据...</td>
            </tr>
            <tr v-if="!loading && rows.length === 0">
              <td colspan="11" class="empty-cell">暂无符合当前分类条件的题目</td>
            </tr>
            <tr v-for="(item, index) in rows" :key="item.id">
              <td>{{ getRowNo(index) }}</td>
              <td>
                <input
                  type="checkbox"
                  :checked="selectedIds.includes(item.id)"
                  @change="toggleRowSelection(item.id, ($event.target as HTMLInputElement).checked)"
                />
              </td>
              <td>{{ item.title }}</td>
              <td><span class="type-badge" :class="{ 'type-badge--vision': item.questionType?.startsWith('IMAGE_') }">{{ problemTypeLabel(item.questionType) }}</span></td>
              <td><span class="difficulty-badge" :class="`difficulty-badge--${difficultyFromTags(item.tags)}`">{{ difficultyFromTags(item.tags) }}</span></td>
              <td class="cell-muted">
                <div class="tag-cloud">
                  <span v-for="tag in knowledgeFromTags(item.tags)" :key="tag" class="knowledge-tag">{{ tag }}</span>
                  <span v-for="tag in (item.tags || []).filter((value) => !PROBLEM_DIFFICULTIES.includes(value as never) && !PROBLEM_KNOWLEDGE_TAGS.includes(value as never))" :key="`custom-${tag}`" class="custom-tag">{{ tag }}</span>
                  <span v-if="!(item.tags || []).length">-</span>
                </div>
              </td>
              <td>{{ item.userNickname || `用户#${item.userId}` }}</td>
              <td>{{ item.acceptedNum ?? 0 }} / {{ item.submitNum ?? 0 }}</td>
              <td class="cell-muted">{{ formatDateTime(item.createTime) }}</td>
              <td class="cell-muted">{{ formatDateTime(item.updateTime) }}</td>
              <td class="op-cell">
                <a-space>
                  <a-button size="mini" class="row-btn row-btn--edit" @click="goEdit(item)">编辑</a-button>
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
  </div>
</template>

<style scoped>
.admin-page {
  position: relative;
  isolation: isolate;
  width: min(1720px, calc(100vw - 28px));
  max-width: none;
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
  background: radial-gradient(circle, color-mix(in srgb, var(--app-success) 62%, transparent), transparent 74%);
  animation-delay: -2.4s;
}

.panel {
  border: 1px solid var(--app-border);
  background:
    linear-gradient(145deg, color-mix(in srgb, var(--app-surface) 94%, #ffffff 6%), var(--app-surface));
  box-shadow: var(--app-card-shadow);
  backdrop-filter: blur(18px);
  -webkit-backdrop-filter: blur(18px);
}

.admin-hero,
.table-shell,
.summary-card {
  border-radius: 28px;
}

.admin-hero {
  display: flex;
  justify-content: space-between;
  gap: 24px;
  padding: 32px 34px;
  align-items: end;
}

.eyebrow,
.table-eyebrow {
  margin: 0 0 10px;
  color: var(--app-accent);
  font-size: 12px;
  font-weight: 700;
  letter-spacing: 0.18em;
  text-transform: uppercase;
}

.admin-hero h1,
.table-head h2 {
  margin: 0;
  font-family: var(--app-font-display);
}

.admin-hero h1 {
  font-size: clamp(34px, 4vw, 54px);
  line-height: 1.05;
}

.lead {
  max-width: 980px;
  margin: 14px 0 0;
  color: var(--app-text-muted);
  line-height: 1.85;
}

.hero-actions {
  display: flex;
  gap: 12px;
  flex-wrap: wrap;
}

.hero-btn {
  min-width: 118px;
}

.summary-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 16px;
  margin-top: 18px;
}

.summary-card {
  padding: 22px;
}

.summary-card span {
  display: block;
  color: var(--app-text-subtle);
  font-size: 12px;
  letter-spacing: 0.08em;
  text-transform: uppercase;
}

.summary-card strong {
  display: block;
  margin-top: 8px;
  font-family: var(--app-font-display);
  font-size: 38px;
}

.summary-card small {
  display: block;
  margin-top: 8px;
  color: var(--app-text-muted);
}

.table-shell {
  margin-top: 20px;
  padding: 22px;
}

.table-head {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  align-items: end;
  padding: 8px 8px 16px;
}

.meta-pills {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}

.meta-pill {
  display: inline-flex;
  align-items: center;
  min-height: 34px;
  padding: 8px 12px;
  border-radius: 999px;
  border: 1px solid var(--app-border);
  color: var(--app-text-subtle);
  font-size: 12px;
  letter-spacing: 0.02em;
  background: color-mix(in srgb, var(--app-surface) 84%, #ffffff 16%);
}

.table-meta {
  color: var(--app-text-subtle);
  font-size: 14px;
}

.table-wrap {
  overflow-x: auto;
  border-radius: 24px;
  border: 1px solid var(--app-border);
  background: color-mix(in srgb, var(--app-surface) 92%, #ffffff 8%);
}

.filter-row {
  display: flex;
  flex-direction: column;
  gap: 10px;
  margin-bottom: 16px;
  border: 1px solid var(--app-border);
  border-radius: 18px;
  padding: 14px;
  background: color-mix(in srgb, var(--app-surface) 88%, #ffffff 12%);
}

.classification-filter {
  display: grid;
  grid-template-columns: repeat(3, minmax(180px, 1fr));
  gap: 12px;
  padding-bottom: 12px;
  border-bottom: 1px dashed var(--app-border);
}

.classification-filter label {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr);
  align-items: center;
  gap: 10px;
  color: var(--app-text-subtle);
  font-size: 12px;
  font-weight: 700;
}

.type-badge,
.difficulty-badge,
.knowledge-tag,
.custom-tag {
  display: inline-flex;
  align-items: center;
  border-radius: 999px;
  white-space: nowrap;
}

.type-badge { padding: 6px 10px; color: #2563eb; background: rgba(59, 130, 246, .1); border: 1px solid rgba(59, 130, 246, .22); font-size: 12px; font-weight: 700; }
.type-badge--vision { color: #7c3aed; background: rgba(139, 92, 246, .11); border-color: rgba(139, 92, 246, .24); }
.difficulty-badge { padding: 5px 9px; font-size: 12px; font-weight: 700; color: var(--app-text-muted); background: var(--app-hover); }
.difficulty-badge--入门, .difficulty-badge--简单 { color: #047857; background: rgba(16, 185, 129, .1); }
.difficulty-badge--中等 { color: #b45309; background: rgba(245, 158, 11, .12); }
.difficulty-badge--困难 { color: #be123c; background: rgba(244, 63, 94, .11); }
.tag-cloud { display: flex; flex-wrap: wrap; gap: 6px; min-width: 180px; }
.knowledge-tag, .custom-tag { padding: 4px 8px; font-size: 11px; border: 1px solid var(--app-border); background: color-mix(in srgb, var(--app-accent) 7%, var(--app-surface)); }
.custom-tag { border-style: dashed; color: var(--app-text-subtle); }

.filter-main {
  display: grid;
  grid-template-columns: minmax(280px, 1.35fr) minmax(320px, 1.5fr) minmax(220px, 0.95fr) auto auto;
  gap: 12px;
  align-items: center;
}

.filter-sub {
  display: flex;
  justify-content: space-between;
  gap: 10px;
  align-items: center;
  border-top: 1px dashed var(--app-border);
  padding-top: 10px;
}

.filter-stats {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}

.filter-actions {
  display: flex;
  gap: 10px;
}

.user-table {
  width: 100%;
  min-width: 1240px;
  border-collapse: collapse;
}

.user-table th,
.user-table td {
  padding: 16px 16px;
  text-align: left;
  border-bottom: 1px solid var(--app-border);
  vertical-align: middle;
}

.user-table th {
  position: sticky;
  top: 0;
  z-index: 2;
  background: color-mix(in srgb, var(--app-surface) 74%, #ffffff 26%);
  backdrop-filter: blur(14px);
  -webkit-backdrop-filter: blur(14px);
  color: var(--app-text-subtle);
  font-size: 12px;
  letter-spacing: 0.08em;
  text-transform: uppercase;
  box-shadow: inset 0 -1px 0 var(--app-border);
}

.user-table tbody tr {
  transition: background 0.22s ease, transform 0.22s ease;
}

.user-table tbody tr:hover {
  background: var(--app-hover);
  transform: translateY(-1px);
}

.pill {
  display: inline-flex;
  align-items: center;
  padding: 6px 10px;
  border-radius: 999px;
  background: rgba(255, 255, 255, 0.18);
  border: 1px solid var(--app-border);
  font-size: 12px;
  font-weight: 700;
}

.pill--ok {
  color: var(--app-success);
  border-color: rgba(18, 154, 116, 0.25);
  background: rgba(18, 154, 116, 0.08);
}

.pill--danger {
  color: var(--app-danger);
  border-color: rgba(214, 96, 77, 0.25);
  background: rgba(214, 96, 77, 0.08);
}

.cell-danger {
  color: var(--app-danger);
}

.cell-muted {
  color: var(--app-text-muted);
}

.check-wrap {
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.op-cell {
  white-space: nowrap;
  min-width: 126px;
}

.empty-cell {
  text-align: center !important;
  color: var(--app-text-subtle);
  padding: 28px !important;
}

.pager-row {
  margin-top: 14px;
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 10px;
  color: var(--app-text-subtle);
}

:deep(.arco-input-wrapper),
:deep(.arco-select-view),
:deep(.arco-btn) {
  min-height: 42px;
  border-radius: 12px;
}

:deep(.arco-input-wrapper) {
  background: color-mix(in srgb, var(--app-surface) 86%, #ffffff 14%);
  border-color: var(--app-border);
  transition: border-color 0.2s ease, box-shadow 0.2s ease, transform 0.2s ease;
}

:deep(.arco-input-wrapper:hover) {
  border-color: color-mix(in srgb, var(--app-accent) 40%, var(--app-border));
}

:deep(.arco-input-wrapper:focus-within) {
  border-color: color-mix(in srgb, var(--app-accent) 75%, #8b5cf6);
  box-shadow: 0 0 0 3px color-mix(in srgb, var(--app-accent) 14%, transparent);
  transform: translateY(-1px);
}

:deep(.arco-btn) {
  position: relative;
  overflow: hidden;
  transition: transform 0.2s ease, box-shadow 0.2s ease, border-color 0.2s ease, background 0.2s ease;
}

:deep(.arco-btn:hover) {
  transform: translateY(-1px);
}

:deep(.arco-btn-primary) {
  box-shadow: 0 10px 22px color-mix(in srgb, var(--app-accent) 22%, transparent);
}

:deep(.arco-btn:focus-visible) {
  outline: 2px solid color-mix(in srgb, var(--app-accent) 60%, #8b5cf6);
  outline-offset: 2px;
}

:deep(.arco-btn::after) {
  content: '';
  position: absolute;
  inset: 0;
  background: linear-gradient(120deg, transparent, rgba(255, 255, 255, 0.22), transparent);
  transform: translateX(-120%);
  transition: transform 0.48s ease;
  pointer-events: none;
}

:deep(.arco-btn:hover::after) {
  transform: translateX(120%);
}

:deep(.hero-btn--primary.arco-btn-primary) {
  border: 1px solid color-mix(in srgb, var(--app-accent) 56%, #60a5fa);
  background: linear-gradient(135deg, color-mix(in srgb, var(--app-accent) 74%, #2563eb), #1d4ed8);
  box-shadow:
    0 16px 30px color-mix(in srgb, var(--app-accent) 28%, transparent),
    inset 0 1px 0 rgba(255, 255, 255, 0.25);
}

:deep(.hero-btn--danger.arco-btn-status-danger) {
  border: 1px solid rgba(236, 72, 153, 0.42);
  background: linear-gradient(135deg, rgba(244, 114, 182, 0.92), rgba(225, 29, 72, 0.92));
  color: #fff;
  box-shadow:
    0 14px 28px rgba(225, 29, 72, 0.24),
    inset 0 1px 0 rgba(255, 255, 255, 0.24);
}

:deep(.query-btn--primary.arco-btn-primary) {
  border: 1px solid color-mix(in srgb, var(--app-accent) 58%, #60a5fa);
  background: linear-gradient(130deg, color-mix(in srgb, var(--app-accent) 70%, #2563eb), #1d4ed8);
}

:deep(.query-btn--ghost.arco-btn) {
  border: 1px solid color-mix(in srgb, var(--app-border) 80%, #cbd5e1);
  background: color-mix(in srgb, var(--app-surface) 86%, #ffffff 14%);
  color: var(--app-text-main);
}

:deep(.row-btn) {
  min-width: 54px;
  border-radius: 10px;
  font-weight: 600;
}

:deep(.row-btn--edit.arco-btn) {
  border: 1px solid color-mix(in srgb, #60a5fa 48%, var(--app-border));
  background: linear-gradient(130deg, rgba(96, 165, 250, 0.26), rgba(59, 130, 246, 0.18));
  color: color-mix(in srgb, #bfdbfe 52%, var(--app-text-main));
}

:deep(.row-btn--delete.arco-btn-status-danger) {
  border: 1px solid rgba(244, 114, 182, 0.35);
  background: linear-gradient(130deg, rgba(244, 114, 182, 0.2), rgba(225, 29, 72, 0.18));
  color: #fecdd3;
}

:deep(.pager-btn.arco-btn) {
  border: 1px solid color-mix(in srgb, var(--app-border) 78%, #cbd5e1);
  background: color-mix(in srgb, var(--app-surface) 84%, #ffffff 16%);
}

@media (prefers-color-scheme: light) {
  :deep(.hero-btn--danger.arco-btn-status-danger) {
    border-color: rgba(244, 63, 94, 0.28);
    background: linear-gradient(135deg, rgba(255, 241, 242, 0.98), rgba(255, 228, 230, 0.98));
    color: #be123c;
    box-shadow:
      0 10px 20px rgba(244, 63, 94, 0.12),
      inset 0 1px 0 rgba(255, 255, 255, 0.78);
  }

  :deep(.row-btn--delete.arco-btn-status-danger) {
    border-color: rgba(244, 63, 94, 0.24);
    background: linear-gradient(130deg, rgba(255, 241, 242, 0.96), rgba(255, 228, 230, 0.96));
    color: #be123c;
    box-shadow: 0 6px 14px rgba(244, 63, 94, 0.1);
  }

  :deep(.hero-btn--danger.arco-btn-status-danger:hover),
  :deep(.row-btn--delete.arco-btn-status-danger:hover) {
    border-color: rgba(225, 29, 72, 0.34);
    color: #9f1239;
  }

  .user-table th {
    background: rgba(255, 255, 255, 0.86);
  }
}

@keyframes floatGlow {
  0%,
  100% {
    transform: translate3d(0, 0, 0) scale(1);
  }
  50% {
    transform: translate3d(8px, -10px, 0) scale(1.06);
  }
}

@media (max-width: 900px) {
  .admin-hero,
  .table-head {
    flex-direction: column;
    align-items: flex-start;
  }

  .summary-grid {
    grid-template-columns: 1fr;
  }

  .filter-main {
    grid-template-columns: 1fr 1fr;
  }

  .classification-filter { grid-template-columns: 1fr; }

  .filter-sub {
    flex-direction: column;
    align-items: flex-start;
  }
}

@media (max-width: 640px) {
  .admin-page {
    width: 100%;
    padding: 20px 12px 44px;
  }

  .admin-hero,
  .table-shell,
  .summary-card {
    border-radius: 22px;
    padding: 20px;
  }

  .filter-main {
    grid-template-columns: 1fr;
  }

  .pager-row {
    justify-content: center;
  }
}

@media (prefers-reduced-motion: reduce) {
  .admin-page::before,
  .admin-page::after,
  .user-table tbody tr,
  :deep(.arco-btn),
  :deep(.arco-input-wrapper) {
    animation: none !important;
    transition: none !important;
    transform: none !important;
  }
}
</style>
