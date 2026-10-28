import { useEffect, useRef, type RefObject } from 'react'

/** Focus once per opened conversation, without stealing focus on background refreshes. */
export function useChatEntryFocus(input: RefObject<HTMLInputElement | null>, conversation: string, ready: boolean) {
  const focusedConversation = useRef('')
  useEffect(() => {
    if (!ready || !conversation || focusedConversation.current === conversation) return
    const frame = window.requestAnimationFrame(() => {
      const element = input.current
      if (!element || element.disabled) return
      element.focus({ preventScroll: true })
      focusedConversation.current = conversation
    })
    return () => window.cancelAnimationFrame(frame)
  }, [input, conversation, ready])
}
