'use strict'

/** Moment timestamps cross an Android/JSON boundary; never use Date.toString(). */
function momentTimestamp(value) {
  if (value instanceof Date) return Number.isFinite(value.getTime()) ? value.toISOString() : ''
  if (typeof value !== 'string' || !value.trim()) return ''
  const normalized = value.trim().replace(' ', 'T')
    .replace(/([+-]\d{2})$/, '$1:00')
    .replace(/([+-]\d{2})(\d{2})$/, '$1:$2')
  // A missing timezone is ambiguous. Do not silently interpret it in the host's locale.
  if (!/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d{1,9})?(?:Z|[+-]\d{2}:\d{2})$/i.test(normalized)) return ''
  const datePart = normalized.slice(0, 10)
  const calendarDay = new Date(`${datePart}T00:00:00Z`)
  if (!Number.isFinite(calendarDay.getTime()) || calendarDay.toISOString().slice(0, 10) !== datePart) return ''
  const parsed = new Date(normalized)
  return Number.isFinite(parsed.getTime()) ? parsed.toISOString() : ''
}

module.exports = { momentTimestamp }
