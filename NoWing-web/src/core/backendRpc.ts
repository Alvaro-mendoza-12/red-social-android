import { fallbackApiJson, isFallbackAuthConfigured, readFallbackSession } from './auth/fallbackAuth'
import { getStorageReadIdentity } from './media/storageReadSession'
import { supabase } from './supabase'

/** Use the same authenticated compatibility routes as Android, without a second auth session. */
export async function backendRpc<T = unknown>(name: string, body: Record<string, unknown>) {
  try {
    if (!/^[a-z][a-z0-9_]+$/.test(name)) throw new Error('Operación no disponible')
    if (isFallbackAuthConfigured) {
      const owner = readFallbackSession()?.user.id
      if (!owner) throw new Error('Inicia sesión para continuar')
      const identity = getStorageReadIdentity()
      const data = await fallbackApiJson<T>(`/rest/v1/rpc/${name}`, { method: 'POST', body: JSON.stringify(body), expectedAccountId: owner, expectedIdentity: identity })
      return { data, error: null }
    }
    if (!supabase) throw new Error('Servicio no disponible')
    const result = await supabase.rpc(name, body)
    return { data: result.data as T, error: result.error }
  } catch (error) { return { data: null, error } }
}
