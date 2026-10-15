export type MentionCandidate = { id: string; name: string; username: string; avatarUrl?: string }

export function mentionQuery(text: string): string | null {
  const match = text.match(/(?:^|\s)@([\p{L}\p{N}_.-]*)$/u)
  return match ? match[1].toLowerCase() : null
}

export function matchingMentions(text: string, candidates: MentionCandidate[], actorId?: string): MentionCandidate[] {
  const query = mentionQuery(text)
  if (query === null) return []
  return candidates.filter(candidate => candidate.id !== actorId && candidate.username &&
    `${candidate.username} ${candidate.name}`.toLowerCase().includes(query)).slice(0, 8)
}

export function insertMention(text: string, username: string): string {
  return text.replace(/(^|\s)@[\p{L}\p{N}_.-]*$/u, `$1@${username} `)
}
