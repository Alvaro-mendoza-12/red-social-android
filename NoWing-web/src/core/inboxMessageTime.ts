/** Preserve PostgreSQL microseconds when reconciling HTTP and realtime messages. */
export function inboxMessageTime(value: unknown): number {
  const text = String(value || '')
  const milliseconds = Date.parse(text)
  if (!Number.isFinite(milliseconds)) return 0
  const fraction = /\.(\d+)(?:Z|[+-]\d\d(?::?\d\d)?)$/i.exec(text)?.[1] || ''
  return milliseconds * 1000 + Number(fraction.padEnd(6, '0').slice(3, 6) || '0')
}
