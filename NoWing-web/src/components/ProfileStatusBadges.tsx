import { useEffect, useState } from 'react'
import { ProfileIdentityMarks } from './ProfileIdentityMarks'
import { useAuth } from '../core/auth/AuthProvider'
import { publicProfileSummaries } from '../core/publicProfileSummaries'
import { profileIdentity, type ProfileIdentity } from '../ui/novvin/profileIdentity'

const cache = new Map<string, { at: number; value: ProfileIdentity }>()
const pending = new Map<string, Map<string, Array<(value: ProfileIdentity | null) => void>>>()
function read(owner: string, id: string): Promise<ProfileIdentity | null> {
  const key = owner + ':' + id, hit = cache.get(key)
  if (hit && Date.now() - hit.at < 120000) return Promise.resolve(hit.value)
  return new Promise(resolve => {
    const batch = pending.get(owner) || new Map<string, Array<(value: ProfileIdentity | null) => void>>()
    const listeners = batch.get(id) || []; listeners.push(resolve); batch.set(id, listeners)
    if (pending.has(owner)) return
    pending.set(owner, batch)
    window.setTimeout(() => {
      pending.delete(owner)
      void publicProfileSummaries([...batch.keys()]).then(rows => {
        batch.forEach((callbacks, profileId) => {
          const row = rows.get(profileId), value = row ? profileIdentity(row) : null
          if (value) { if (cache.size >= 1000) cache.delete(cache.keys().next().value!); cache.set(owner + ':' + profileId, { at: Date.now(), value }) }
          callbacks.forEach(callback => callback(value))
        })
      }).catch(() => batch.forEach(callbacks => callbacks.forEach(callback => callback(null))))
    }, 0)
  })
}
/** Display only. Authorization always remains with the server. */
export function ProfileStatusBadges({ profileId, initialIdentity }: { profileId: string; initialIdentity?: ProfileIdentity }) {
  const { user } = useAuth(), owner = user?.id || '', key = owner + ':' + profileId
  const [loaded, setLoaded] = useState<{ key: string; value: ProfileIdentity } | null>(null)
  useEffect(() => {
    if (!owner || !profileId) return
    let active = true
    void read(owner, profileId).then(value => { if (active && value) setLoaded({ key, value }) })
    return () => { active = false }
  }, [owner, profileId, key])
  const value = loaded?.key === key ? loaded.value : cache.get(key)?.value || initialIdentity
  return value ? <ProfileIdentityMarks identity={value}/> : null
}
