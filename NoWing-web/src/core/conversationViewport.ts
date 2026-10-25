export type ConversationViewportAnchor = { messageId: string; top: number } | null

/** Capture the first message intersecting the viewport before prepending history. */
export function captureConversationViewportAnchor(thread: HTMLElement): ConversationViewportAnchor {
  const viewport = thread.getBoundingClientRect()
  for (const node of Array.from(thread.querySelectorAll<HTMLElement>('[id^="message-"]'))) {
    const bounds = node.getBoundingClientRect()
    if (bounds.bottom > viewport.top && bounds.top < viewport.bottom) {
      return { messageId: node.id, top: bounds.top }
    }
  }
  return null
}

/** Correct only the layout shift still present after the browser's native scroll anchoring. */
export function restoreConversationViewportAnchor(
  thread: HTMLElement,
  anchor: ConversationViewportAnchor,
): void {
  if (!anchor) return
  const node = Array.from(thread.querySelectorAll<HTMLElement>('[id^="message-"]'))
    .find(candidate => candidate.id === anchor.messageId)
  if (!node) return
  const offset = node.getBoundingClientRect().top - anchor.top
  if (Math.abs(offset) > 0.5) thread.scrollTop += offset
}
