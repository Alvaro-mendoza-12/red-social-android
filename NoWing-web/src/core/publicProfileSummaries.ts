import { fallbackApiJson, isFallbackAuthConfigured, readFallbackSession } from './auth/fallbackAuth'
import { supabase } from './supabase'
import { textOf, type GenericRow } from './utils'

export async function publicProfileSummaries(ids: string[]) {
  const clean = [...new Set(ids)].filter(id => /^[0-9a-f-]{36}$/i.test(id)).slice(0, 100)
  if (!clean.length) return new Map<string, GenericRow>()
  const rows = isFallbackAuthConfigured
    ? await fallbackApiJson<GenericRow[]>(`/rest/v1/profiles?select=id,display_name,username,avatar_url,avatar_media_type,is_admin,is_verified&id=in.(${clean.join(',')})`, { expectedAccountId: readFallbackSession()?.user.id })
    : (await supabase?.from('profiles_public').select('id,display_name,username,avatar_url,avatar_media_type,is_admin,is_verified').in('id', clean))?.data || []
  return new Map(rows.map(row => [textOf(row, ['id']), row]))
}
