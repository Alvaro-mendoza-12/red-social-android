function seededRandom(seed: number): () => number {
  let state = seed >>> 0
  return () => {
    state += 0x6d2b79f5
    let value = state
    value = Math.imul(value ^ (value >>> 15), value | 1)
    value ^= value + Math.imul(value ^ (value >>> 7), value | 61)
    return ((value ^ (value >>> 14)) >>> 0) / 4294967296
  }
}

export function createShuffleSeed(): number {
  const values = new Uint32Array(1)
  if (globalThis.crypto?.getRandomValues) {
    globalThis.crypto.getRandomValues(values)
    return values[0] ?? Date.now()
  }
  return (Date.now() ^ Math.floor(Math.random() * 0xffffffff)) >>> 0
}

export function shuffleFeedItems<T>(items: readonly T[], seed: number): T[] {
  const shuffled = [...items]
  const random = seededRandom(seed)
  for (let index = shuffled.length - 1; index > 0; index -= 1) {
    const target = Math.floor(random() * (index + 1))
    const current = shuffled[index]
    shuffled[index] = shuffled[target] as T
    shuffled[target] = current as T
  }
  return shuffled
}

function normalizeAuthorId(value: string): string {
  return value.trim().toLowerCase()
}

export function filterFriendFeedItems<T extends { authorId: string }>(
  items: readonly T[],
  followingIds: readonly string[],
  currentUserId = '',
): T[] {
  const allowedAuthors = new Set(followingIds.map(normalizeAuthorId).filter(Boolean))
  const currentAuthor = normalizeAuthorId(currentUserId)
  if (currentAuthor) allowedAuthors.add(currentAuthor)

  return items.filter((item) => allowedAuthors.has(normalizeAuthorId(item.authorId)))
}
