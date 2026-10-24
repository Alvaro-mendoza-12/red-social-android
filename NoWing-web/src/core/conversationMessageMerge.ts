type VersionedMessage = { id: string; updatedAt: string; status: string; deletedForAll: boolean; isEdited: boolean }
const stamp = (value: string) => {
  const millis = Date.parse(value) || 0
  const fraction = /\.(\d+)(?:Z|[+-]\d\d(?::?\d\d)?)$/i.exec(value)?.[1] || ''
  return millis * 1000 + Number(fraction.padEnd(6, '0').slice(3, 6) || '0')
}
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

/** Add an older page without dropping messages already loaded by realtime or a newer page. */
export function mergeOlderMessagePage<T extends VersionedMessage & { createdAt: string }>(
  current: T[],
  olderPage: T[],
): T[] {
  const byId = new Map(olderPage.map(message => [message.id, message]))
  for (const message of current) {
    byId.set(message.id, newerMessage(byId.get(message.id), message))
  }
  return [...byId.values()].sort((a, b) => {
    const timeDifference = stamp(a.createdAt) - stamp(b.createdAt)
    return timeDifference || a.id.localeCompare(b.id)
  })
}

/** A latest-page refresh replaces its covered interval, retaining older loaded history. */
export function mergeLatestMessagePage<T extends VersionedMessage & { createdAt: string }>(
  current: T[], incoming: T[], hasMore: boolean, idsAtRequestStart?: ReadonlySet<string>,
): T[] {
  const confirmed = incoming.filter(message => !pending(message.status))
  const oldest = confirmed[0]
  // An acknowledgement or realtime insert received while the GET was running
  // belongs to a newer state than its snapshot. Never briefly erase that row.
  const arrivedDuringRead = idsAtRequestStart ? current.filter(message => !idsAtRequestStart.has(message.id)) : []
  const before = hasMore && oldest ? current.filter(message => {
    const difference = stamp(message.createdAt) - stamp(oldest.createdAt)
    return difference < 0 || (difference === 0 && message.id < oldest.id)
  }) : []
  return mergeOlderMessagePage([...before, ...arrivedDuringRead], mergeMessageSnapshot(current, incoming))
}
