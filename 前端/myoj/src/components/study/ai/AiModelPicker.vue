<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { Message } from '@arco-design/web-vue'
import { RouterLink } from 'vue-router'
import { useAiModelsStore } from '@/stores/aiModels'

defineProps<{ disabled?: boolean }>()
const emit = defineEmits<{ change: [] }>()
const models = useAiModelsStore()
const draftModel = ref('')
const options = computed(() => models.catalog.providers.find((p) => p.code === models.selectedProvider)?.models || [])

onMounted(() => { void models.load().catch(() => Message.error('模型列表加载失败，请稍后重试')) })
watch(() => models.selectedModel, (value) => { draftModel.value = value }, { immediate: true })

function changeProvider(event: Event) {
  const code = (event.target as HTMLSelectElement).value
  const preset = models.saved.find((item) => item.providerCode === code)
  models.select(code, preset?.modelId || '')
  emit('change')
}

function changeModel() {
  const value = draftModel.value.trim()
  if (!/^[A-Za-z0-9_./:-]{1,100}$/.test(value)) {
    Message.warning('请输入有效的模型名称，最多 100 字符')
    draftModel.value = models.selectedModel
    return
  }
  if (value === models.selectedModel) return
  models.select(models.selectedProvider, value)
  emit('change')
}
</script>

<template>
  <div class="ai-model-picker">
    <span class="ai-model-picker__label">当前模型</span>
    <select :value="models.selectedProvider" :disabled="disabled || models.loading" aria-label="选择 AI 平台" @change="changeProvider">
      <option value="SITE_DEFAULT">站点默认 · {{ models.catalog.siteDefaultModel || 'AI' }}{{ models.catalog.siteDefaultAvailable ? '' : '（未配置）' }}</option>
      <option v-for="item in models.saved" :key="item.providerCode" :value="item.providerCode">
        {{ models.catalog.providers.find((p) => p.code === item.providerCode)?.name || item.providerCode }}
      </option>
    </select>
    <template v-if="models.selectedProvider !== 'SITE_DEFAULT'">
      <input v-model="draftModel" :list="`ai-models-${models.selectedProvider}`" :disabled="disabled" aria-label="模型名称" placeholder="模型名称" @change="changeModel" @keydown.enter="changeModel" />
      <datalist :id="`ai-models-${models.selectedProvider}`"><option v-for="name in options" :key="name" :value="name" /></datalist>
    </template>
    <RouterLink :to="{ name: 'account-settings', query: { tab: 'ai' } }">管理 API Key</RouterLink>
  </div>
</template>

<style scoped>
.ai-model-picker { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; padding: 9px 11px; border: 1px solid rgba(20, 184, 166, .2); border-radius: 12px; background: rgba(20, 184, 166, .07); color: var(--app-text, #172b34); font-size: 12px; }
.ai-model-picker__label { font-weight: 700; color: var(--app-text-muted, #567); }
.ai-model-picker select, .ai-model-picker input { min-height: 29px; max-width: 165px; padding: 3px 8px; border: 1px solid rgba(20, 184, 166, .25); border-radius: 8px; background: var(--app-surface-strong, #fff); color: var(--app-text, #172b34); font: inherit; }
.ai-model-picker a { margin-left: auto; color: #0f8c7a; font-weight: 700; text-decoration: none; }
.ai-model-picker a:hover { text-decoration: underline; }
:global(html[data-theme='dark']) .ai-model-picker a { color: #5eead4; }
</style>
