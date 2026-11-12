import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import { aiModelsApi, type AiCatalog, type AiSavedConfig } from '@/api/aiModels'
import { useAuthStore } from '@/stores/auth'

export const useAiModelsStore = defineStore('aiModels', () => {
  const catalog = ref<AiCatalog>({ siteDefaultAvailable: false, siteDefaultModel: '', providers: [] })
  const saved = ref<AiSavedConfig[]>([])
  const selectedProvider = ref('SITE_DEFAULT')
  const selectedModel = ref('')
  const loading = ref(false)
  const loadedFor = ref('')

  const currentLabel = computed(() => {
    if (selectedProvider.value === 'SITE_DEFAULT') return `站点默认 · ${catalog.value.siteDefaultModel || 'AI'}`
    const provider = catalog.value.providers.find((item) => item.code === selectedProvider.value)
    return `${provider?.name || selectedProvider.value} · ${selectedModel.value}`
  })

  function storageKey(userId: string) { return `myoj.ai.model-selection.${userId}` }

  async function load(force = false) {
    const userId = useAuthStore().userId || ''
    if (!userId) {
      saved.value = []
      selectedProvider.value = 'SITE_DEFAULT'
      selectedModel.value = ''
      loadedFor.value = ''
      return
    }
    if (!force && loadedFor.value === userId) return
    if (loadedFor.value && loadedFor.value !== userId) {
      saved.value = []
      selectedProvider.value = 'SITE_DEFAULT'
      selectedModel.value = ''
      loadedFor.value = ''
    }
    loading.value = true
    try {
      const [nextCatalog, nextSaved] = await Promise.all([aiModelsApi.catalog(), aiModelsApi.mine()])
      catalog.value = nextCatalog
      saved.value = nextSaved
      const raw = localStorage.getItem(storageKey(userId))
      let choice: { providerCode?: string; modelId?: string } | null = null
      try { choice = raw ? JSON.parse(raw) as { providerCode?: string; modelId?: string } : null } catch { /* 损坏的旧设置使用默认模型 */ }
      const available = nextSaved.find((item) => item.providerCode === choice?.providerCode)
      selectedProvider.value = available ? available.providerCode : 'SITE_DEFAULT'
      selectedModel.value = available ? (choice?.modelId || available.modelId) : ''
      loadedFor.value = userId
    } catch (error) {
      saved.value = []
      selectedProvider.value = 'SITE_DEFAULT'
      selectedModel.value = ''
      throw error
    } finally {
      loading.value = false
    }
  }

  function select(providerCode: string, modelId = '') {
    if (providerCode !== 'SITE_DEFAULT' && !saved.value.some((item) => item.providerCode === providerCode)) return
    selectedProvider.value = providerCode
    selectedModel.value = providerCode === 'SITE_DEFAULT' ? '' : modelId.trim()
    const userId = useAuthStore().userId || ''
    if (userId) localStorage.setItem(storageKey(userId), JSON.stringify({ providerCode: selectedProvider.value, modelId: selectedModel.value }))
  }

  async function refresh() { await load(true) }

  return { catalog, saved, selectedProvider, selectedModel, loading, currentLabel, load, refresh, select }
})
