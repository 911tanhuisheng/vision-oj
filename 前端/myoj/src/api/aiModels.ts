import { OpenAPI } from '@generated'
import { isResultSuccess } from '@/api/result'

export type AiProvider = { code: string; name: string; models: string[] }
export type AiSavedConfig = { providerCode: string; modelId: string; keyMask: string }
export type AiCatalog = { siteDefaultAvailable: boolean; siteDefaultModel: string; providers: AiProvider[] }

async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  const token = typeof OpenAPI.TOKEN === 'function' ? await OpenAPI.TOKEN({} as never) : OpenAPI.TOKEN
  const res = await fetch(`${OpenAPI.BASE.replace(/\/+$/, '')}/ai/models${path}`, {
    ...init,
    cache: 'no-store',
    credentials: OpenAPI.WITH_CREDENTIALS ? 'include' : 'same-origin',
    headers: { 'Content-Type': 'application/json', ...(token ? { Authorization: `Bearer ${token}` } : {}), ...init.headers },
  })
  const body = await res.json().catch(() => null) as { code?: number; message?: string; data?: T } | null
  if (!res.ok || !body || !isResultSuccess(body.code)) {
    throw new Error(body?.message || `请求失败（HTTP ${res.status}）`)
  }
  return body.data as T
}

export const aiModelsApi = {
  catalog: () => request<AiCatalog>('/catalog'),
  mine: () => request<AiSavedConfig[]>('/mine'),
  save: (providerCode: string, modelId: string, apiKey: string) => request<void>(`/mine/${encodeURIComponent(providerCode)}`, {
    method: 'PUT', body: JSON.stringify({ modelId, apiKey }),
  }),
  test: (providerCode: string, modelId: string) => request<void>(`/mine/${encodeURIComponent(providerCode)}/test`, {
    method: 'POST', body: JSON.stringify({ modelId }),
  }),
  remove: (providerCode: string) => request<void>(`/mine/${encodeURIComponent(providerCode)}`, { method: 'DELETE' }),
}
