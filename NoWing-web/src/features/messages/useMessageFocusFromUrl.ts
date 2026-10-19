import { useEffect, useRef } from 'react'
import { useLocation } from 'react-router-dom'

export function useMessageFocusFromUrl(ready: boolean, focus: (id: string) => void) {
  const location = useLocation(), last = useRef('')
  useEffect(() => {
    const id = new URLSearchParams(location.search).get('focusMessageId') || ''
    const key = `${location.pathname}:${id}`
    if (!ready || !/^[0-9a-f-]{36}$/i.test(id) || last.current === key) return
    last.current = key
    focus(id)
  }, [ready, location.pathname, location.search, focus])
}
