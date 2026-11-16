import type { GenericRow } from '../../core/utils'

export const POST_REACTIONS = [
  { type: 'LIKE', label: 'Me gusta', emoji: '👍' },
  { type: 'LOVE', label: 'Me encanta', emoji: '❤️' },
  { type: 'HAHA', label: 'Me divierte', emoji: '😆' },
  { type: 'WOW', label: 'Me asombra', emoji: '😮' },
  { type: 'SAD', label: 'Me entristece', emoji: '😢' },
  { type: 'ANGRY', label: 'Me enoja', emoji: '😠' },
  { type: 'CARE', label: 'Me importa', emoji: '🤗' },
  { type: 'FIRE', label: 'Fuego', emoji: '🔥' },
] as const
export type PostReaction = typeof POST_REACTIONS[number]['type']
export type PostReactionState = {
  myReaction: PostReaction | null
  reactionCounts: Partial<Record<PostReaction, number>>
  likedByMe: boolean
  likeCount: number
}
const count = (value: unknown) => typeof value === 'number' && Number.isFinite(value) ? Math.max(0, Math.floor(value)) : 0
export function readPostReactionState(row: GenericRow): PostReactionState {
  const selected = String(row.my_reaction || row.myReaction || '').toUpperCase()
  const myReaction = POST_REACTIONS.find(reaction => reaction.type === selected)?.type
    || (row.liked_by_me || row.likedByMe ? 'LIKE' : null)
  const source = row.reaction_counts || row.reactionCounts
  const reactionCounts: PostReactionState['reactionCounts'] = {}
  if (source && typeof source === 'object') for (const {type} of POST_REACTIONS) {
    const total = count((source as Record<string, unknown>)[type])
    if (total) reactionCounts[type] = total
  }
  const likeCount = count(Number(row.like_count ?? row.likeCount))
  if (!Object.keys(reactionCounts).length && likeCount) reactionCounts.LIKE = likeCount
  return { myReaction, reactionCounts, likedByMe: Boolean(myReaction), likeCount: Object.values(reactionCounts).reduce((sum, total) => sum + total, 0) }
}

/** One account has one reaction; changing its type does not add a second vote. */
export function withPostReaction<T extends PostReactionState>(post: T, selected: PostReaction | null): T {
  const previous = post.myReaction || (post.likedByMe ? 'LIKE' : null)
  const reactionCounts = { ...post.reactionCounts }
  if (!Object.keys(reactionCounts).length && post.likeCount) reactionCounts.LIKE = post.likeCount
  if (previous) reactionCounts[previous] = Math.max(0, (reactionCounts[previous] || 0) - 1)
  if (selected) reactionCounts[selected] = (reactionCounts[selected] || 0) + 1
  return { ...post, myReaction: selected, likedByMe: Boolean(selected), reactionCounts,
    likeCount: Object.values(reactionCounts).reduce((sum, total) => sum + total, 0) }
}
