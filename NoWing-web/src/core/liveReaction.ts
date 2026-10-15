export function liveReactionEmoji(payload: { emoji?: string; kind?: string }) {
  const value = payload.emoji?.trim()
  return value && ['❤️','❤','💜','💗','👏','🔥','😮'].includes(value) ? value : '❤️'
}
