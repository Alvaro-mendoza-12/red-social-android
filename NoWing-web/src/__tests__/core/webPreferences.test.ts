import { applyWebPreferences, readWebPreferences, writeWebPreferences } from '../../core/webPreferences'

beforeEach(() => { localStorage.clear(); document.documentElement.className = '' })
test('appearance is applied globally, persists and is restored before navigation', () => {
  writeWebPreferences({ ...readWebPreferences(), themeMode: 'light', accentTheme: 'ocean' })
  expect(document.documentElement).toHaveClass('theme-light', 'accent-ocean')
  document.documentElement.className = ''
  applyWebPreferences()
  expect(document.documentElement).toHaveClass('theme-light', 'accent-ocean')
  writeWebPreferences({ ...readWebPreferences(), themeMode: 'dark', accentTheme: 'violet' })
  expect(document.documentElement).toHaveClass('theme-dark', 'accent-violet')
  expect(document.documentElement).not.toHaveClass('theme-light', 'accent-ocean')
})
test('system mode remains responsive to CSS prefers-color-scheme instead of freezing a resolved mode', () => {
  writeWebPreferences({ ...readWebPreferences(), themeMode: 'system' })
  expect(document.documentElement).toHaveClass('theme-system')
  expect(readWebPreferences().themeMode).toBe('system')
})
