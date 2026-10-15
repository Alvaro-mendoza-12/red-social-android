import { fallbackApiJson } from '../auth/fallbackAuth'
import { supabase } from '../supabase'
import { trustedStorageBackend } from './storageReadClient'
import { getStorageReadIdentity, requireStorageReadIdentity } from './storageReadSession'
import { validateViewOnceLease, viewOnceLimit, type ViewOnceKind } from './viewOnceLease'

export async function requestViewOnceMedia({ scope, messageId, deviceId, kind, useUbuntu }: { scope: 'dm' | 'group' | 'community'; messageId: string; deviceId: string; kind: ViewOnceKind; useUbuntu: boolean }) {
  const identity = getStorageReadIdentity()
  requireStorageReadIdentity(identity)
  const body = { p_scope_type: scope, p_message_id: messageId, p_device_id: deviceId, p_max_file_bytes: viewOnceLimit(kind) }
  let data: unknown
  if (useUbuntu) data = await fallbackApiJson('/rest/v1/rpc/secure_consume_view_once_message', { method: 'POST', body: JSON.stringify(body) })
  else {
    if (!supabase) throw new Error('VIEW_ONCE_UNAVAILABLE')
    const result = await supabase.rpc('secure_consume_view_once_message', body)
    if (result.error) throw result.error
    data = result.data
  }
  requireStorageReadIdentity(identity)
  const row = (Array.isArray(data) ? data[0] : data) as Record<string, unknown> | null
  const url = typeof row?.media_url === 'string' ? row.media_url : ''
  return validateViewOnceLease(data, trustedStorageBackend(url), identity, kind)
}
