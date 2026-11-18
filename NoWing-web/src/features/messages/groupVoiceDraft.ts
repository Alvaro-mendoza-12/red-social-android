import type { StorageReadIdentity } from '../../core/media/storageReadSession'

export type GroupVoiceMember = { id: string; name: string; avatarUrl: string }
export type GroupVoiceRoster = { groupId: string; groupName: string; members: GroupVoiceMember[] }
export type GroupVoiceIntent = { requestId: string; mode: 'all' | 'selected'; recipientIds: string[] }
const key = (owner: StorageReadIdentity, group: string) => `novvin:group-voice:${owner.accountId}/${group}`

export function loadGroupVoiceIntent(owner: StorageReadIdentity, group: string): GroupVoiceIntent | null {
  const raw = localStorage.getItem(key(owner, group))
  if (raw === null) return null
  const item: unknown = JSON.parse(raw)
  if (!item || typeof item !== 'object') throw new Error('No se pudo leer la invitación pendiente.')
  const value = item as GroupVoiceIntent
  if (typeof value.requestId !== 'string' || !/^[0-9a-f-]{36}$/i.test(value.requestId) ||
      !['all', 'selected'].includes(value.mode) || !Array.isArray(value.recipientIds) ||
      !value.recipientIds.length || value.recipientIds.some(id => typeof id !== 'string' || !id.trim())) {
    throw new Error('Conservamos la invitación pendiente para evitar duplicarla. No se pudo leer.')
  }
  return value
}
export function saveGroupVoiceIntent(owner: StorageReadIdentity, group: string, intent: GroupVoiceIntent) {
  localStorage.setItem(key(owner, group), JSON.stringify(intent))
}
export function clearGroupVoiceIntent(owner: StorageReadIdentity, group: string, expected: string) {
  if (loadGroupVoiceIntent(owner, group)?.requestId === expected) localStorage.removeItem(key(owner, group))
}
export function groupVoiceRecipients(roster: GroupVoiceRoster, mode: 'all' | 'selected', selected: ReadonlySet<string>) {
  return [...new Set(roster.members.filter(member => mode === 'all' || selected.has(member.id)).map(member => member.id))].sort()
}
