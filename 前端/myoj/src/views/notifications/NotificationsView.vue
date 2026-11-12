<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { Message } from '@arco-design/web-vue'
import {
  fetchNotificationPage,
  fetchRecentAnnouncements,
  markAllNotificationsRead,
  markNotificationsRead,
  readAnnouncementsUpTo,
  type InAppNotificationItem,
  type SiteAnnouncementItem,
} from '@/api/inAppNotifications'
import { isResultSuccess } from '@/api/result'
import { getBackendErrorMessage } from '@/api/httpError'
import { useAuthStore } from '@/stores/auth'

const router = useRouter()
const auth = useAuthStore()

const annLoading = ref(false)
const announcements = ref<SiteAnnouncementItem[]>([])

const msgLoading = ref(false)
const messages = ref<InAppNotificationItem[]>([])
const msgTotal = ref(0)
const msgCurrent = ref(1)
const msgPageSize = ref(15)

const msgTotalPages = computed(() => Math.max(1, Math.ceil(msgTotal.value / msgPageSize.value)))

function formatTime(raw?: string): string {
  if (!raw) return ''
  const d = new Date(raw)
  if (Number.isNaN(d.getTime())) return raw
  return d.toLocaleString('zh-CN', { hour12: false })
}

function typeLabel(type?: number): string {
  if (type === 1) return '评论'
  if (type === 2) return '回复'
  if (type === 3) return '比赛'
  if (type === 4) return '开赛'
  if (type === 5) return '账号限制'
  if (type === 6) return '限制解除'
  return '通知'
}

async function loadAnnouncements() {
  annLoading.value = true
  try {
    const res = await fetchRecentAnnouncements(20)
    if (!isResultSuccess(res.code)) {
      announcements.value = []
      return
    }
    announcements.value = Array.isArray(res.data) ? res.data : []
  } catch {
    announcements.value = []
  } finally {
    annLoading.value = false
  }
}

async function loadMessages() {
  if (!auth.isLoggedIn) return
  msgLoading.value = true
  try {
    const res = await fetchNotificationPage(msgCurrent.value, msgPageSize.value)
    if (!isResultSuccess(res.code) || !res.data) {
      messages.value = []
      return
    }
    const p = res.data
    messages.value = p.records ?? []
    msgTotal.value = p.total ?? 0
    msgCurrent.value = p.current ?? msgCurrent.value
    msgPageSize.value = p.size ?? msgPageSize.value
  } catch (e) {
    Message.error(getBackendErrorMessage(e, '加载失败'))
  } finally {
    msgLoading.value = false
  }
}

async function onMarkAnnouncementsRead() {
  if (!announcements.value.length) return
  const ids = announcements.value.map((a) => String(a.id ?? '')).filter(Boolean)
  const maxId = ids.filter((id) => /^[1-9]\d*$/.test(id)).reduce((m, id) => {
    return id.length > m.length || (id.length === m.length && id > m) ? id : m
  }, '')
  if (!maxId) return
  try {
    const res = await readAnnouncementsUpTo(maxId)
    if (!isResultSuccess(res.code)) {
      Message.error(res.message || '操作失败')
      return
    }
    Message.success('已标记公告为已读')
    window.dispatchEvent(new CustomEvent('myoj:notifications-changed'))
  } catch (e) {
    Message.error(getBackendErrorMessage(e, '操作失败'))
  }
}

function openMessage(row: InAppNotificationItem) {
  const kind = row.linkKind?.toUpperCase()
  const ref = row.linkRef?.trim()
  if (kind === 'BLOG_POST' && ref) {
    void router.push({ name: 'blog-detail', params: { id: ref } })
  } else if (kind === 'CONTEST' && ref) {
    void router.push({ name: 'contest-rank-detail', params: { contestId: ref } })
  }
  if (!row.read && row.id) {
    void markNotificationsRead([String(row.id)]).then((res) => {
      if (isResultSuccess(res.code)) {
        row.read = true
        window.dispatchEvent(new CustomEvent('myoj:notifications-changed'))
      }
    })
  }
}

async function onMarkAllMessagesRead() {
  try {
    const res = await markAllNotificationsRead()
    if (!isResultSuccess(res.code)) {
      Message.error(res.message || '操作失败')
      return
    }
    messages.value = messages.value.map((m) => ({ ...m, read: true }))
    Message.success('已全部标为已读')
    window.dispatchEvent(new CustomEvent('myoj:notifications-changed'))
  } catch (e) {
    Message.error(getBackendErrorMessage(e, '操作失败'))
  }
}

function onPrevMsg() {
  if (msgCurrent.value <= 1) return
  msgCurrent.value -= 1
  void loadMessages()
}

function onNextMsg() {
  if (msgCurrent.value >= msgTotalPages.value) return
  msgCurrent.value += 1
  void loadMessages()
}

onMounted(() => {
  void loadAnnouncements()
  void loadMessages()
})

onBeforeUnmount(() => {
  /* 无定时器 */
})
</script>

<template>
  <div class="notif-page">
    <header class="notif-hero">
      <h1>通知中心</h1>
      <p class="notif-lead">社区评论、比赛报名与开赛提醒、全站公告会出现在这里。</p>
    </header>

    <section class="notif-panel">
      <div class="notif-panel__head">
        <h2>全站公告</h2>
        <a-button v-if="announcements.length" type="outline" size="small" @click="onMarkAnnouncementsRead">
          将以上公告标为已读
        </a-button>
      </div>
      <a-spin :loading="annLoading">
        <p v-if="!annLoading && !announcements.length" class="notif-empty">暂无公告</p>
        <ul v-else class="ann-list">
          <li v-for="a in announcements" :key="a.id" class="ann-item">
            <p class="ann-title">{{ a.title }}</p>
            <p class="ann-meta">{{ formatTime(a.publishedAt) }}</p>
            <pre class="ann-body">{{ a.content }}</pre>
          </li>
        </ul>
      </a-spin>
    </section>

    <section v-if="auth.isLoggedIn" class="notif-panel">
      <div class="notif-panel__head">
        <h2>我的消息</h2>
        <a-button type="outline" size="small" :disabled="!messages.length" @click="onMarkAllMessagesRead">
          全部标为已读
        </a-button>
      </div>
      <a-spin :loading="msgLoading">
        <p v-if="!msgLoading && !messages.length" class="notif-empty">暂无消息</p>
        <ul v-else class="msg-list">
          <li
            v-for="m in messages"
            :key="m.id"
            class="msg-item"
            :class="{ 'msg-item--unread': !m.read }"
            role="button"
            tabindex="0"
            @click="openMessage(m)"
            @keydown.enter="openMessage(m)"
          >
            <span class="msg-type">{{ typeLabel(m.type) }}</span>
            <div class="msg-main">
              <p class="msg-title">{{ m.title }}</p>
              <p v-if="m.body" class="msg-body">{{ m.body }}</p>
              <p class="msg-time">{{ formatTime(m.createTime) }}</p>
            </div>
            <span v-if="m.linkKind" class="msg-go">查看 →</span>
          </li>
        </ul>
        <div v-if="msgTotal > msgPageSize" class="msg-pager">
          <a-button type="outline" size="small" :disabled="msgCurrent <= 1" @click="onPrevMsg">上一页</a-button>
          <span class="msg-pager-meta">{{ msgCurrent }} / {{ msgTotalPages }} · 共 {{ msgTotal }} 条</span>
          <a-button type="outline" size="small" :disabled="msgCurrent >= msgTotalPages" @click="onNextMsg">
            下一页
          </a-button>
        </div>
      </a-spin>
    </section>

    <p v-else class="notif-login-hint">登录后可查看个人消息与标为已读。</p>
  </div>
</template>

<style scoped>
.notif-page {
  max-width: 720px;
  margin: 0 auto;
  padding: 24px 20px 64px;
}

.notif-hero h1 {
  margin: 0 0 8px;
  font-size: 1.75rem;
  color: var(--color-text-1);
}

.notif-lead {
  margin: 0;
  color: var(--app-text-muted);
  font-size: 14px;
  line-height: 1.6;
}

.notif-panel {
  margin-top: 28px;
  padding: 20px;
  border-radius: 16px;
  border: 1px solid var(--color-border-2);
  background: var(--color-bg-2);
}

.notif-panel__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 16px;
}

.notif-panel__head h2 {
  margin: 0;
  font-size: 1.1rem;
  color: var(--color-text-1);
}

.notif-empty {
  margin: 0;
  color: var(--app-text-muted);
  font-size: 14px;
}

.ann-list,
.msg-list {
  list-style: none;
  margin: 0;
  padding: 0;
}

.ann-item {
  padding: 14px 0;
  border-bottom: 1px solid var(--color-border-1);
}

.ann-item:last-child {
  border-bottom: none;
}

.ann-title {
  margin: 0 0 4px;
  font-weight: 700;
  color: var(--color-text-1);
}

.ann-meta {
  margin: 0 0 8px;
  font-size: 12px;
  color: var(--app-text-subtle);
}

.ann-body {
  margin: 0;
  white-space: pre-wrap;
  font-family: inherit;
  font-size: 14px;
  line-height: 1.55;
  color: var(--app-text-muted);
}

.msg-item {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  padding: 14px 10px;
  border-radius: 12px;
  cursor: pointer;
  border: 1px solid transparent;
}

.msg-item:hover {
  background: var(--color-fill-2);
}

.msg-item--unread {
  border-color: color-mix(in srgb, var(--app-accent) 35%, var(--color-border-2));
  background: color-mix(in srgb, var(--app-accent) 6%, var(--color-bg-2));
}

.msg-type {
  flex-shrink: 0;
  font-size: 11px;
  font-weight: 800;
  padding: 4px 8px;
  border-radius: 999px;
  background: var(--color-fill-3);
  color: var(--app-text-muted);
  border: 1px solid var(--color-border-2);
}

.msg-main {
  flex: 1;
  min-width: 0;
}

.msg-title {
  margin: 0 0 4px;
  font-weight: 600;
  font-size: 14px;
  color: var(--color-text-1);
}

.msg-body {
  margin: 0 0 6px;
  font-size: 13px;
  color: var(--app-text-muted);
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.msg-time {
  margin: 0;
  font-size: 12px;
  color: var(--app-text-subtle);
}

.msg-go {
  flex-shrink: 0;
  font-size: 12px;
  color: var(--app-accent);
  align-self: center;
  font-weight: 600;
}

.msg-pager {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 12px;
  margin-top: 16px;
}

.msg-pager-meta {
  font-size: 13px;
  color: var(--app-text-muted);
}

.notif-login-hint {
  margin-top: 24px;
  text-align: center;
  color: var(--app-text-muted);
}
</style>

<!--
  暗黑高对比：与全站冷青底区分，面板略提亮 + 实色字阶（避免 Arco/变量叠在一起发灰）
-->
<style>
html[data-theme='dark'] .notif-page {
  color: #f2ebe3;
}

html[data-theme='dark'] .notif-page .notif-hero h1 {
  color: #fffaf4;
  font-weight: 800;
  letter-spacing: 0.02em;
}

html[data-theme='dark'] .notif-page .notif-lead {
  color: #e0d5c8;
  font-weight: 500;
  font-size: 15px;
}

html[data-theme='dark'] .notif-page .notif-panel {
  background: linear-gradient(165deg, #35485c 0%, #2a3849 52%, #243040 100%);
  border: 1px solid rgba(255, 255, 255, 0.18);
  box-shadow:
    inset 0 1px 0 rgba(255, 255, 255, 0.08),
    0 14px 42px rgba(0, 0, 0, 0.42);
}

html[data-theme='dark'] .notif-page .notif-panel__head h2 {
  color: #fffaf4;
  font-size: 1.2rem;
  font-weight: 800;
  letter-spacing: 0.03em;
}

html[data-theme='dark'] .notif-page .notif-empty,
html[data-theme='dark'] .notif-page .notif-login-hint {
  color: #d2c6b8;
  font-weight: 500;
}

html[data-theme='dark'] .notif-page .ann-item {
  border-bottom-color: rgba(255, 255, 255, 0.12);
}

html[data-theme='dark'] .notif-page .ann-title {
  color: #fffaf4;
  font-weight: 800;
}

html[data-theme='dark'] .notif-page .ann-meta {
  color: #c9bbaa;
  font-weight: 600;
}

html[data-theme='dark'] .notif-page .ann-body {
  color: #ebe3d9;
  font-weight: 500;
  line-height: 1.65;
}

html[data-theme='dark'] .notif-page .msg-item:hover {
  background: rgba(255, 255, 255, 0.1);
}

html[data-theme='dark'] .notif-page .msg-item:focus-visible {
  outline: 2px solid rgba(90, 228, 204, 0.75);
  outline-offset: 2px;
}

html[data-theme='dark'] .notif-page .msg-item--unread {
  border-color: rgba(90, 228, 204, 0.55);
  background: linear-gradient(90deg, rgba(90, 228, 204, 0.16) 0%, rgba(36, 48, 64, 0.65) 48%);
  box-shadow: inset 3px 0 0 #5ae4cc;
}

html[data-theme='dark'] .notif-page .msg-type {
  background: rgba(255, 255, 255, 0.14);
  color: #f6f0e9;
  border-color: rgba(255, 255, 255, 0.22);
  font-weight: 850;
}

html[data-theme='dark'] .notif-page .msg-title {
  color: #fffaf4;
  font-weight: 700;
}

html[data-theme='dark'] .notif-page .msg-body {
  color: #e4dbd1;
  font-weight: 500;
}

html[data-theme='dark'] .notif-page .msg-item--unread .msg-body {
  color: #f2ebe6;
}

html[data-theme='dark'] .notif-page .msg-time {
  color: #c4b8a9;
  font-weight: 600;
}

html[data-theme='dark'] .notif-page .msg-item--unread .msg-time {
  color: #d8cec3;
}

html[data-theme='dark'] .notif-page .msg-go {
  color: #8ff5e4;
  font-weight: 700;
}

html[data-theme='dark'] .notif-page .msg-pager-meta {
  color: #d2c6b8;
  font-weight: 600;
}

html[data-theme='dark'] .notif-page .notif-panel .arco-btn-outline {
  color: #f6efe6 !important;
  border-color: rgba(255, 255, 255, 0.28) !important;
  background: rgba(255, 255, 255, 0.08);
  font-weight: 600;
}

html[data-theme='dark'] .notif-page .notif-panel .arco-btn-outline:hover:not(.arco-btn-disabled) {
  border-color: rgba(90, 228, 204, 0.65) !important;
  color: #fffaf4 !important;
  background: rgba(255, 255, 255, 0.12);
}

html[data-theme='dark'] .notif-page .notif-panel .arco-btn-outline.arco-btn-disabled {
  color: #a89a8c !important;
  border-color: rgba(255, 255, 255, 0.12) !important;
  background: rgba(0, 0, 0, 0.12);
  opacity: 1;
}

html[data-theme='dark'] .notif-page .notif-panel .arco-spin-icon {
  color: #8ff5e4;
}
</style>
