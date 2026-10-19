import { textOf, type GenericRow } from './utils'

/** Keep orphaned replies visible and bound malformed or cyclic legacy parent chains. */
export function commentThreads(items: GenericRow[]) {
  const byId = new Map(items.map(item => [textOf(item, ['id']), item]))
  const groups = new Map<string, { root: GenericRow; replies: GenericRow[] }>()
  for (const item of byId.values()) {
    let root = item
    const visited = new Set<string>()
    while (true) {
      const id = textOf(root, ['id']), parent = textOf(root, ['parent_comment_id', 'parent_id', 'parentId'])
      if (!parent || !byId.has(parent)) break
      if (visited.has(parent) || parent === id) { root = item; break }
      visited.add(id); root = byId.get(parent)!
    }
    // Cyclic records become independent roots rather than disappearing.
    const key = textOf(root, ['id'])
    if (!groups.has(key)) groups.set(key, { root, replies: [] })
    if (root !== item) groups.get(key)!.replies.push(item)
  }
  return Array.from(groups.values())
}
