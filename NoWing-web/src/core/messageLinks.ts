export type MessageLink = { start: number; value: string; href: string }

const linkPattern = /https?:\/\/[^\s<>]+|[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}|(?:[A-Z0-9](?:[A-Z0-9-]*[A-Z0-9])?\.)+[A-Z]{2,}(?::\d{1,5})?(?:\/[^\s<>]*)?/gi

/** Plain text only: never insert clipboard HTML or accept a non-web URI scheme. */
export function messageLinks(text: string): MessageLink[] {
  const links: MessageLink[] = []
  for (const match of text.matchAll(linkPattern)) {
    const start = match.index!
    if (start && /[@\w]/.test(text[start - 1]) || text.slice(0, start).endsWith('://')) continue
    let value = match[0].replace(/[.,;:!?]+$/, '')
    for (const [open, close] of [['(', ')'], ['[', ']'], ['{', '}']]) {
      while (value.endsWith(close) && value.split(close).length > value.split(open).length) value = value.slice(0, -1)
    }
    const email = /^[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}$/i.test(value)
    const href = email ? `mailto:${value}` : /^https?:\/\//i.test(value) ? value : `https://${value}`
    try {
      const parsed = new URL(href)
      if (parsed.username || parsed.password || !['https:', 'http:', 'mailto:'].includes(parsed.protocol)) continue
      links.push({ start, value, href })
    } catch { /* Leave malformed links as text. */ }
  }
  return links
}
