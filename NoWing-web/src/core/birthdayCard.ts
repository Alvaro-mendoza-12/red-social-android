export type BirthdayCard = { version: 1; kind: 'birthday'; message: string; theme: 'confetti' | 'sunrise' | 'garden' }
export const defaultBirthdayCard: BirthdayCard = { version: 1, kind: 'birthday', message: '¡Feliz cumpleaños! 🎂 Que este nuevo año te regale mucha alegría, salud y momentos inolvidables. ¡Disfruta tu día!', theme: 'confetti' }
export function birthdayCard(value: string): BirthdayCard | null {
  if (value.length > 5000) return null
  try {
    const card = JSON.parse(value)
    return card?.version === 1 && card.kind === 'birthday' && typeof card.message === 'string' && card.message.trim() && [...card.message].length <= 600 &&
      ['confetti', 'sunrise', 'garden'].includes(card.theme) && ![...card.message].some(char => { const c = char.codePointAt(0)!; return c <= 8 || c === 11 || c === 12 || (c >= 14 && c <= 31) })
      ? { version: 1, kind: 'birthday', message: card.message, theme: card.theme } : null
  } catch { return null }
}
export function birthdayLabel(value: string): string {
  if (!/^\d{4}-\d{2}-\d{2}$/.test(value)) return ''
  const date = new Date(value + 'T12:00:00Z')
  return Number.isNaN(date.getTime()) || date.toISOString().slice(0, 10) !== value ? '' : new Intl.DateTimeFormat('es', { day: 'numeric', month: 'long', timeZone: 'UTC' }).format(date)
}
