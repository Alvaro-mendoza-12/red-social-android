import { useCallback, useEffect, useState } from 'react'

export type WebPreferences = {
  compactInbox: boolean
  lowDataMode: boolean
  themeMode: 'system' | 'light' | 'dark'
  textScale: 'compact' | 'normal' | 'large'
  language: string
}

const storageKey = 'conectate-web-preferences'
const changeEventName = 'conectate-web-preferences-changed'

const defaultPreferences: WebPreferences = {
  compactInbox: false,
  lowDataMode: false,
  themeMode: 'system',
  textScale: 'normal',
  language: 'es',
}

export function readWebPreferences(): WebPreferences {
  if (typeof window === 'undefined') return defaultPreferences
  try {
    const raw = window.localStorage.getItem(storageKey)
    if (!raw) return defaultPreferences
    const parsed = JSON.parse(raw) as Partial<WebPreferences>
    return {
      compactInbox: Boolean(parsed.compactInbox),
      lowDataMode: Boolean(parsed.lowDataMode),
      themeMode:
        parsed.themeMode === 'light' || parsed.themeMode === 'dark' || parsed.themeMode === 'system'
          ? parsed.themeMode
          : 'system',
      textScale:
        parsed.textScale === 'compact' || parsed.textScale === 'large' || parsed.textScale === 'normal'
          ? parsed.textScale
          : 'normal',
      language: typeof parsed.language === 'string' && parsed.language.trim() ? parsed.language.trim() : 'es',
    }
  } catch {
    return defaultPreferences
  }
}

export function writeWebPreferences(nextPreferences: WebPreferences) {
  if (typeof window === 'undefined') return
  window.localStorage.setItem(storageKey, JSON.stringify(nextPreferences))
  window.dispatchEvent(new Event(changeEventName))
}

export function useWebPreferences() {
  const [preferences, setPreferences] = useState<WebPreferences>(() => readWebPreferences())

  useEffect(() => {
    const sync = () => setPreferences(readWebPreferences())
    window.addEventListener(changeEventName, sync)
    window.addEventListener('storage', sync)
    return () => {
      window.removeEventListener(changeEventName, sync)
      window.removeEventListener('storage', sync)
    }
  }, [])

  const updatePreferences = useCallback((patch: Partial<WebPreferences>) => {
    const nextPreferences = {
      ...readWebPreferences(),
      ...patch,
    }
    writeWebPreferences(nextPreferences)
    setPreferences(nextPreferences)
  }, [])

  return [preferences, updatePreferences] as const
}
