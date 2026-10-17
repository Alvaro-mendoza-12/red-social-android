type VersionedMessage = { id: string; updatedAt: string; status: string; deletedForAll: boolean; isEdited: boolean }
const stamp = (value: string) => Date.parse(value) || 0
const pending = (status: string) => status === 'pending' || status === 'failed'

/** Receipts and slow snapshots must not restore an older message body. */
export function newerMessage<T extends VersionedMessage>(current: T | undefined, incoming: T): T {
  if (!current) return incoming
  if (current.deletedForAll && !incoming.deletedForAll) return current
  if (!pending(current.status) && pending(incoming.status)) return current
  if (pending(current.status) && !pending(incoming.status)) return incoming
  if (stamp(current.updatedAt) > stamp(incoming.updatedAt)) return current
  if (stamp(current.updatedAt) === stamp(incoming.updatedAt) && current.isEdited && !incoming.isEdited) return current
  return incoming
}

export function mergeMessageSnapshot<T extends VersionedMessage>(current: T[], incoming: T[]): T[] {
  const byId = new Map(current.map(message => [message.id, message]))
  // A missing row may have been hidden/cleared by this account. Do not resurrect it.
  return incoming.map(message => newerMessage(byId.get(message.id), message))
}
