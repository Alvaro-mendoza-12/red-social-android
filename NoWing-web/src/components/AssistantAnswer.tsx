import { Fragment, type ReactNode } from 'react'

// Format the assistant's text without interpreting HTML or running links/code.
function inline(text: string): ReactNode {
  return text.split(/(\*\*[^*\n]+\*\*|`[^`\n]+`)/g).map((part, index) =>
    part.startsWith('**') && part.endsWith('**') ? <strong key={index}>{part.slice(2, -2)}</strong>
      : part.startsWith('`') && part.endsWith('`') ? <code key={index}>{part.slice(1, -1)}</code>
        : <Fragment key={index}>{part}</Fragment>)
}

export function AssistantAnswer({ text }: { text: string }) {
  const lines = text.split('\n')
  const blocks: ReactNode[] = []
  for (let index = 0; index < lines.length;) {
    const line = lines[index]
    if (!line.trim()) { index++; continue }
    const key = index
    if (/^\s*```/.test(line)) {
      const code: string[] = []; index++
      while (index < lines.length && !/^\s*```/.test(lines[index])) code.push(lines[index++])
      if (index < lines.length) index++
      blocks.push(<pre key={key} tabIndex={0} aria-label="Código de la respuesta"><code>{code.join('\n')}</code></pre>)
      continue
    }
    const heading = line.match(/^#{1,6}\s+(.+)$/)
    if (heading) { blocks.push(<h4 key={key}>{inline(heading[1])}</h4>); index++; continue }
    const ordered = /^\s*\d+[.)]\s+/.test(line)
    const listPattern = ordered ? /^\s*\d+[.)]\s+(.+)$/ : /^\s*[-*]\s+(.+)$/
    if (listPattern.test(line)) {
      const items: ReactNode[] = []
      while (index < lines.length) {
        const match = lines[index].match(listPattern)
        if (!match) break
        items.push(<li key={index++}>{inline(match[1])}</li>)
      }
      blocks.push(ordered ? <ol key={key}>{items}</ol> : <ul key={key}>{items}</ul>)
      continue
    }
    const paragraph = [line]; index++
    while (index < lines.length && lines[index].trim() && !/^\s*(?:```|#{1,6}\s|[-*]\s|\d+[.)]\s)/.test(lines[index])) paragraph.push(lines[index++])
    blocks.push(<p key={key}>{inline(paragraph.join('\n'))}</p>)
  }
  return <div className="assistant-answer">{blocks}</div>
}
