import { useCallback, useEffect, useState } from 'react'
import { safeAccent, accentInk } from './accentColor'

export type WebPreferences = {
  compactInbox: boolean
  lowDataMode: boolean
  themeMode: 'system' | 'light' | 'dark'
  accentTheme: 'conectate' | 'ocean' | 'violet' | 'emerald' | 'midnight' | 'ruby' | 'cyan' | 'amber' | 'graphite' | 'custom'
  customAccent?: string
  textScale: 'compact' | 'normal' | 'large'
  language: string
}

const storageKey = 'conectate-web-preferences'
const changeEventName = 'conectate-web-preferences-changed'

const defaultPreferences: WebPreferences = {
  compactInbox: false,
  lowDataMode: false,
  themeMode: 'dark',
  accentTheme: 'conectate',
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
          : 'dark',
      accentTheme:
        parsed.accentTheme === 'conectate' ||
        parsed.accentTheme === 'ocean' ||
        parsed.accentTheme === 'violet' ||
        parsed.accentTheme === 'emerald' ||
        parsed.accentTheme === 'midnight' ||
        parsed.accentTheme === 'ruby' ||
        parsed.accentTheme === 'cyan' ||
        parsed.accentTheme === 'amber' ||
        parsed.accentTheme === 'graphite' || parsed.accentTheme === 'custom'
          ? parsed.accentTheme
          : 'conectate',
      customAccent: safeAccent(parsed.customAccent),
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
  applyWebPreferences(nextPreferences)
  window.dispatchEvent(new Event(changeEventName))
}

export function applyWebPreferences(preferences = readWebPreferences()) {
  if (typeof document === 'undefined') return
  const root = document.documentElement
  for (const name of Array.from(root.classList)) {
    if (/^(theme-|accent-|text-scale-)/.test(name)) root.classList.remove(name)
  }
  root.classList.add(`theme-${preferences.themeMode}`, `accent-${preferences.accentTheme}`, `text-scale-${preferences.textScale}`)
  root.style.setProperty('--custom-accent-light', accentInk(preferences.customAccent || '#8acfff', false))
  root.style.setProperty('--custom-accent-dark', accentInk(preferences.customAccent || '#8acfff', true))
}

export function useWebPreferences() {
  const [preferences, setPreferences] = useState<WebPreferences>(() => readWebPreferences())

  useEffect(() => {
    applyWebPreferences()
    const sync = () => { const next = readWebPreferences(); applyWebPreferences(next); setPreferences(next) }
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
