import { fallbackApiJson } from './auth/fallbackAuth'
export type SystemAnnouncement = {
  id: string; kind: 'info' | 'maintenance' | 'update'; title: string; body: string
  startsAt?: string | null; endsAt?: string | null; expiresAt: string
  versionCode?: number | null; revokedAt?: string | null
}
export const officialUpdateUrl = 'https://play.google.com/store/apps/details?id=conectate.com'
export function activeAnnouncement(notice: SystemAnnouncement, now = Date.now()): boolean {
  return Boolean(notice && typeof notice.id === 'string' && ['info', 'maintenance', 'update'].includes(notice.kind) &&
    typeof notice.title === 'string' && typeof notice.body === 'string' && !notice.revokedAt && Date.parse(notice.expiresAt) > now)
}
export function announcementSchedule(notice: SystemAnnouncement): string {
  if (notice.kind !== 'maintenance' || !notice.startsAt || !notice.endsAt) return ''
  const formatter = new Intl.DateTimeFormat('es', { dateStyle: 'medium', timeStyle: 'short' })
  const start = new Date(notice.startsAt), end = new Date(notice.endsAt)
  if (!Number.isFinite(start.getTime()) || !Number.isFinite(end.getTime())) return ''
  return `${formatter.format(start)} — ${formatter.format(end)} · ${Intl.DateTimeFormat().resolvedOptions().timeZone}`
}
export async function loadSystemAnnouncements(admin = false, signal?: AbortSignal): Promise<SystemAnnouncement[]> {
  const response = await fallbackApiJson<{ items: SystemAnnouncement[] }>(admin ? '/v1/admin/system/announcements' : '/v1/system/announcements', { auth: admin, signal })
  return Array.isArray(response.items) ? response.items : []
}
export async function publishSystemAnnouncement(notice: SystemAnnouncement) {
  return fallbackApiJson('/v1/admin/system/announcements', { method: 'POST', body: JSON.stringify(notice) })
}
export async function revokeSystemAnnouncement(id: string) {
  return fallbackApiJson(`/v1/admin/system/announcements/${encodeURIComponent(id)}`, { method: 'DELETE' })
}
