import { useEffect, useRef, useState, type CSSProperties } from 'react'

type ExpandableTextProps = {
  text: string
  lines?: number
  className?: string
  collapsedLabel?: string
  expandedLabel?: string
  fallback?: string
}

export function ExpandableText({
  text,
  lines = 3,
  className = '',
  collapsedLabel = 'Ver más',
  expandedLabel = 'Ver menos',
  fallback = 'Sin texto',
}: ExpandableTextProps) {
  const paragraphRef = useRef<HTMLParagraphElement | null>(null)
  const [expandedForText, setExpandedForText] = useState<string | null>(null)
  const [isOverflowing, setIsOverflowing] = useState(false)
  const content = text.trim() || fallback
  const expanded = expandedForText === content

  useEffect(() => {
    const element = paragraphRef.current
    if (!element || expanded) return

    const measureOverflow = () => {
      setIsOverflowing(element.scrollHeight - element.clientHeight > 4)
    }

    measureOverflow()

    if (typeof ResizeObserver === 'undefined') return
    const observer = new ResizeObserver(measureOverflow)
    observer.observe(element)
    return () => observer.disconnect()
  }, [content, expanded, lines])

  const paragraphClassName = ['expandable-text', expanded ? 'expanded' : 'collapsed', className]
    .filter(Boolean)
    .join(' ')

  return (
    <div className="expandable-text-block">
      <p
        ref={paragraphRef}
        className={paragraphClassName}
        style={{ '--clamp-lines': String(lines) } as CSSProperties}
      >
        {content}
      </p>
      {isOverflowing || expanded ? (
        <button
          type="button"
          className="inline-link inline-text-toggle"
          onClick={() => setExpandedForText((current) => (current === content ? null : content))}
        >
          {expanded ? expandedLabel : collapsedLabel}
        </button>
      ) : null}
    </div>
  )
}
