import { SafeAvatar } from './SafeAvatar'
import { matchingMentions, type MentionCandidate } from '../core/mentionSuggestions'

export function MentionSuggestions({ text, candidates, actorId, onSelect }: {
  text: string; candidates: MentionCandidate[]; actorId?: string; onSelect: (username: string) => void
}) {
  const matches = matchingMentions(text, candidates, actorId)
  if (!matches.length) return null
  return <div className="mention-suggestions" aria-label="Personas que puedes mencionar">
    {matches.map(person => <button key={person.id} type="button" className="mention-candidate"
      onClick={() => onSelect(person.username)}>
      <SafeAvatar url={person.avatarUrl} name={person.name} size="sm" />
      <span><strong>{person.name}</strong><small>@{person.username}</small></span>
    </button>)}
  </div>
}
