import { useState } from 'react'
import { ChevronDown } from 'lucide-react'
import { PublicationDialog } from './PublicationDialog'
import { POST_REACTIONS, type PostReaction } from '../features/feed/postReactions'
import './postReactionPicker.css'

export function PostReactionPicker({ selected, busy, multiple = true, onSelect }: {
  selected: PostReaction | null; busy: boolean; multiple?: boolean; onSelect: (reaction: PostReaction | null) => void
}) {
  const [open, setOpen] = useState(false)
  const current = POST_REACTIONS.find(reaction => reaction.type === selected) || POST_REACTIONS[0]
  return <div className="post-reaction-action">
    <button className={`ghost-btn${selected ? ' active-action' : ''}`} type="button" aria-pressed={Boolean(selected)}
      disabled={busy} onClick={() => onSelect(selected ? null : 'LIKE')}>
      <span aria-hidden="true">{current.emoji}</span>{current.label}
    </button>
    {multiple ? <button className="ghost-btn post-reaction-more" type="button" aria-label="Más reacciones"
      disabled={busy} aria-haspopup="dialog" onClick={() => setOpen(true)}><ChevronDown size={16} aria-hidden="true" /></button> : null}
    {open ? <PublicationDialog title="Reaccionar a la publicación" onClose={() => setOpen(false)}>
      <div className="post-reaction-choices">{POST_REACTIONS.map(reaction => <button key={reaction.type}
        className="post-reaction-choice" data-reaction={reaction.type} type="button" aria-pressed={selected === reaction.type}
        onClick={() => { setOpen(false); onSelect(selected === reaction.type ? null : reaction.type) }}>
        <span aria-hidden="true">{reaction.emoji}</span><span>{reaction.label}</span>
      </button>)}</div>
    </PublicationDialog> : null}
  </div>
}
