import { useEffect, useRef, type RefObject } from 'react'

/** Focus once per opened conversation, without stealing focus on background refreshes. */
export function useChatEntryFocus(input: RefObject<HTMLInputElement | null>, conversation: string, ready: boolean, request = 0) {
  const focusedConversation = useRef('')
  useEffect(() => {
    const entry = `${conversation}/${request}`
    if (!ready || !conversation || focusedConversation.current === entry) return
    const frame = window.requestAnimationFrame(() => {
      const element = input.current
      if (!element || element.disabled) return
      element.focus({ preventScroll: true })
      focusedConversation.current = entry
    })
    return () => window.cancelAnimationFrame(frame)
  }, [input, conversation, ready, request])
}
