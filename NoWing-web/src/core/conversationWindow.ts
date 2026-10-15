/** Render recent messages first; the complete authorized history stays searchable. */
export function conversationWindow<T>(messages: readonly T[], limit: number, searching: boolean): readonly T[] {
  if (searching) return messages
  const count = Number.isFinite(limit) ? Math.max(1, Math.floor(limit)) : 80
  return messages.slice(-count)
}
