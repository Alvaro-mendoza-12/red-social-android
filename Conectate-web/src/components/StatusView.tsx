import type { ReactNode } from 'react'

type StatusViewProps = {
  title: string
  description: string
  action?: ReactNode
}

export function StatusView({ title, description, action }: StatusViewProps) {
  return (
    <div className="status-shell">
      <article className="status-card">
        <h2>{title}</h2>
        <p>{description}</p>
        {action ? <div className="status-action">{action}</div> : null}
      </article>
    </div>
  )
}
