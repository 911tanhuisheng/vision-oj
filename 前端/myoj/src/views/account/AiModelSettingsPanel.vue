<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { Message, Modal } from '@arco-design/web-vue'
import { aiModelsApi } from '@/api/aiModels'
import { useAiModelsStore } from '@/stores/aiModels'

const models = useAiModelsStore()
const providerCode = ref('DASHSCOPE')
const modelId = ref('')
const apiKey = ref('')
const showKey = ref(false)
const busy = ref(false)
const errorMessage = ref('')
const provider = computed(() => models.catalog.providers.find((item) => item.code === providerCode.value))
const configured = computed(() => models.saved.find((item) => item.providerCode === providerCode.value))

onMounted(() => { void models.load(true).then(syncModel).catch(() => Message.error('模型配置加载失败')) })
function syncModel() { modelId.value = configured.value?.modelId || provider.value?.models[0] || '' }
function onProviderChange(event: Event) { providerCode.value = (event.target as HTMLSelectElement).value; apiKey.value = ''; errorMessage.value = ''; syncModel() }

async function save() {
  if (!modelId.value.trim()) return void Message.warning('请先选择或填写模型名称')
  if (!configured.value && !apiKey.value.trim()) return void Message.warning('首次配置请填写 API Key')
  busy.value = true
  errorMessage.value = ''
  try {
    await aiModelsApi.save(providerCode.value, modelId.value.trim(), apiKey.value.trim())
    apiKey.value = ''
    await models.refresh()
    models.select(providerCode.value, modelId.value.trim())
    Message.success('模型配置已加密保存')
  } catch (error) { errorMessage.value = error instanceof Error ? error.message : '保存失败，请稍后重试' }
  finally { busy.value = false }
}

async function test() {
  busy.value = true
  errorMessage.value = ''
  try {
    await aiModelsApi.test(providerCode.value, modelId.value.trim())
    Message.success('连接成功，这个模型可以使用')
  } catch (error) { errorMessage.value = error instanceof Error ? error.message : '连接失败，请稍后重试' }
  finally { busy.value = false }
}

function remove() {
  Modal.confirm({ title: '删除个人模型配置？', content: '删除后该平台的 API Key 无法恢复。', onOk: async () => {
    try {
      await aiModelsApi.remove(providerCode.value)
      await models.refresh()
      syncModel()
      Message.success('已删除')
    } catch (error) { Message.error(error instanceof Error ? error.message : '删除失败') }
  } })
}
</script>

<template>
  <div class="ai-settings">
    <div class="ai-settings__intro"><strong>使用你自己的大模型</strong><span>密钥仅在保存时发送给服务器并加密存储。模型调用可能产生平台费用。</span></div>
    <div class="ai-settings__grid">
      <label>模型平台<select :value="providerCode" @change="onProviderChange"><option v-for="item in models.catalog.providers" :key="item.code" :value="item.code">{{ item.name }}</option></select></label>
      <label>模型名称<input v-model="modelId" :list="`account-models-${providerCode}`" maxlength="100" placeholder="选择或填写模型 ID" /><datalist :id="`account-models-${providerCode}`"><option v-for="name in provider?.models || []" :key="name" :value="name" /></datalist></label>
      <label class="ai-settings__key">API Key <span v-if="configured" class="ai-settings__saved">已保存 {{ configured.keyMask }}</span><input v-model="apiKey" :type="showKey ? 'text' : 'password'" autocomplete="off" spellcheck="false" :placeholder="configured ? '留空则保留原密钥' : '输入平台提供的 API Key'" /><button type="button" class="ai-settings__peek" @click="showKey = !showKey">{{ showKey ? '隐藏' : '显示' }}</button></label>
    </div>
    <div class="ai-settings__actions"><button type="button" class="ai-settings__primary" :disabled="busy" @click="save">保存配置</button><button type="button" :disabled="busy || !configured" @click="test">测试连接</button><button v-if="configured" type="button" class="ai-settings__danger" :disabled="busy" @click="remove">删除配置</button></div>
    <p v-if="errorMessage" class="ai-settings__error" role="alert">{{ errorMessage }}</p>
    <p class="ai-settings__hint">做题助手和教师 AI 出题页都可选择已保存的平台。未选择时仍使用站点默认模型；个人模型失败不会自动消耗站点额度。</p>
  </div>
</template>

<style scoped>
.ai-settings { color: var(--app-text, #1f2937); }
.ai-settings__intro { display: flex; flex-direction: column; gap: 7px; padding: 17px; border-radius: 16px; background: linear-gradient(125deg, rgba(20,184,166,.2), rgba(99,102,241,.12)); border: 1px solid rgba(20,184,166,.22); }
.ai-settings__intro strong { font-size: 17px; }.ai-settings__intro span,.ai-settings__hint { color: var(--app-text-muted, #64748b); font-size: 13px; line-height: 1.7; }
.ai-settings__grid { display: grid; grid-template-columns: 1fr 1fr; gap: 16px; margin-top: 22px; }
.ai-settings__grid label { position: relative; display: flex; flex-direction: column; gap: 8px; font-weight: 700; font-size: 13px; }
.ai-settings__grid input,.ai-settings__grid select { min-height: 43px; width: 100%; padding: 8px 12px; border: 1px solid rgba(100,116,139,.25); border-radius: 11px; background: var(--app-surface-strong, #fff); color: var(--app-text, #1f2937); font: inherit; font-weight: 500; box-sizing: border-box; }
.ai-settings__grid input:focus,.ai-settings__grid select:focus { outline: 2px solid rgba(20,184,166,.35); }
.ai-settings__key { grid-column: 1 / -1; }.ai-settings__key input { padding-right: 64px; }.ai-settings__saved { color: #0f8c7a; font-weight: 600; }.ai-settings__peek { position: absolute; right: 9px; bottom: 7px; border: 0; background: transparent; color: #0f8c7a; cursor: pointer; }
.ai-settings__actions { display: flex; gap: 10px; flex-wrap: wrap; margin-top: 22px; }.ai-settings__actions button { padding: 9px 16px; border: 1px solid rgba(100,116,139,.25); border-radius: 10px; background: var(--app-surface-strong, #fff); color: var(--app-text, #1f2937); cursor: pointer; font-weight: 700; }.ai-settings__actions button:disabled { opacity: .45; cursor: not-allowed; }.ai-settings__actions .ai-settings__primary { color: #fff; background: linear-gradient(120deg,#0f9d8b,#377fc5); border: 0; }.ai-settings__actions .ai-settings__danger { color: #c2413a; }
.ai-settings__error { margin: 14px 0 0; padding: 10px 13px; border: 1px solid rgba(220,80,67,.25); border-radius: 10px; background: rgba(220,80,67,.08); color: #b43f36; font-size: 13px; line-height: 1.6; overflow-wrap: anywhere; }
.ai-settings__hint { margin-top: 19px; }
@media(max-width:650px) { .ai-settings__grid { grid-template-columns: 1fr; } }
</style>
