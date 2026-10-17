export type NormalizedSignalingParticipant = {
  socketId: string
  userId: string
  name?: string
  avatarUrl?: string
  isMuted?: boolean
  isCameraSharing?: boolean
  isScreenSharing?: boolean
}

function text(value: unknown): string {
  return typeof value === 'string' || typeof value === 'number' ? String(value).trim() : ''
}

export function normalizeSignalingParticipant(value: unknown): NormalizedSignalingParticipant | null {
  if (typeof value === 'string' || typeof value === 'number') {
    const socketId = text(value)
    return socketId ? { socketId, userId: socketId } : null
  }
  if (!value || typeof value !== 'object' || Array.isArray(value)) return null

  const record = value as Record<string, unknown>
  const socketId = text(record.socketId ?? record.socket_id ?? record.id)
  const userId = text(record.userId ?? record.user_id) || socketId
  if (!socketId || !userId) return null

  const name = text(record.name ?? record.userName ?? record.user_name)
  const avatarUrl = text(record.avatarUrl ?? record.avatar_url ?? record.userAvatarUrl ?? record.user_avatar_url)
  const mutedValue = record.isMuted ?? record.is_muted

  return {
    socketId,
    userId,
    ...(name ? { name } : {}),
    ...(avatarUrl ? { avatarUrl } : {}),
    ...(mutedValue !== undefined
      ? { isMuted: mutedValue === true || String(mutedValue).toLowerCase() === 'true' }
      : {}),
    ...(typeof record.isCameraSharing === 'boolean' ? { isCameraSharing: record.isCameraSharing } : {}),
    ...(typeof record.isScreenSharing === 'boolean' ? { isScreenSharing: record.isScreenSharing } : {}),
  }
}

export function normalizeSignalingParticipants(value: unknown): NormalizedSignalingParticipant[] {
  if (!Array.isArray(value)) return []
  const bySocketId = new Map<string, NormalizedSignalingParticipant>()
  value.forEach((entry) => {
    const participant = normalizeSignalingParticipant(entry)
    if (participant) bySocketId.set(participant.socketId, participant)
  })
  return [...bySocketId.values()]
}
