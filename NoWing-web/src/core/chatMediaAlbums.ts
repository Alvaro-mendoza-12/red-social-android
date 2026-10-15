export type AlbumMessage = {
  id: string; senderId: string; createdAt: string; contentType: string; mediaUrl: string | null
  content: string; replyToId: string | null; deletedForAll: boolean; deletedBy: string[]
  status?: string
}

/** Group consecutive photos only; text, stickers, deletion and reply boundaries remain separate. */
export function chatMediaAlbums<T extends AlbumMessage>(messages: readonly T[], viewerId: string) {
  const index = new Map<string, { leader: string; items: T[] }>()
  let run: T[] = []
  const finish = () => {
    if (run.length > 1) {
      const album = { leader: run[0].id, items: run }
      run.forEach(message => index.set(message.id, album))
    }
    run = []
  }
  for (const message of messages) {
    const eligible = Boolean(message.mediaUrl) && (message.contentType === 'image' || message.contentType.startsWith('image/')) && !['pending', 'failed'].includes(message.status || '') && !message.deletedForAll && !message.deletedBy.includes(viewerId)
    if (!eligible) { finish(); continue }
    const previous = run.at(-1)
    if (previous) {
      const gap = Date.parse(message.createdAt) - Date.parse(previous.createdAt)
      if (previous.senderId !== message.senderId || previous.replyToId !== message.replyToId || !Number.isFinite(gap) || gap < 0 || gap > 60_000 || previous.createdAt.slice(0, 10) !== message.createdAt.slice(0, 10) || run.length >= 20) finish()
    }
    run.push(message)
  }
  finish()
  return index
}
