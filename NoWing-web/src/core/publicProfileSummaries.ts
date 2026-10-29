import { fallbackApiJson, isFallbackAuthConfigured, readFallbackSession } from './auth/fallbackAuth'
import { supabase } from './supabase'
import { textOf, type GenericRow } from './utils'

export async function publicProfileSummaries(ids: string[]) {
  const clean = [...new Set(ids)].filter(id => /^[0-9a-f-]{36}$/i.test(id))
  if (!clean.length) return new Map<string, GenericRow>()
  const owner = isFallbackAuthConfigured ? readFallbackSession()?.user.id : undefined, result = new Map<string, GenericRow>()
  // Keep each request bounded without discarding identities from a long inbox.
  for (let offset = 0; offset < clean.length; offset += 100) {
    const batch = clean.slice(offset, offset + 100)
    const rows = isFallbackAuthConfigured
      ? await fallbackApiJson<GenericRow[]>(`/rest/v1/profiles?select=id,display_name,username,avatar_url,avatar_media_type,is_admin,is_verified,badges&id=in.(${batch.join(',')})`, { expectedAccountId: owner })
      : (await supabase?.from('profiles_public').select('id,display_name,username,avatar_url,avatar_media_type,is_admin,is_verified').in('id', batch))?.data || []
    for (const row of rows) result.set(textOf(row, ['id']), row)
  }
  return result
}
