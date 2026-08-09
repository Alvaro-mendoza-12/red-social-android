function liveKitHttpUrl(value) {
  const raw = String(value || '').trim().replace(/\/+$/, '')
  if (!raw) return ''
  if (raw.startsWith('wss://')) return `https://${raw.slice(6)}`
  if (raw.startsWith('ws://')) return `http://${raw.slice(5)}`
  if (raw.startsWith('https://') || raw.startsWith('http://')) return raw
  return ''
}

async function defaultClientFactory(configuration) {
  const { RoomServiceClient } = await import('livekit-server-sdk')
  return new RoomServiceClient(
    liveKitHttpUrl(configuration?.url),
    String(configuration?.apiKey || '').trim(),
    String(configuration?.apiSecret || '').trim(),
  )
}

function requireAdminConfiguration(configuration) {
  const host = liveKitHttpUrl(configuration?.url)
  const apiKey = String(configuration?.apiKey || '').trim()
  const apiSecret = String(configuration?.apiSecret || '').trim()
  if (!host || !apiKey || !apiSecret) {
    const error = new Error('LIVE_SFU_NOT_CONFIGURED')
    error.code = 'LIVE_SFU_NOT_CONFIGURED'
    throw error
  }
}

async function removeLiveKitParticipant({
  configuration,
  roomName,
  participantIdentity,
  clientFactory = defaultClientFactory,
}) {
  requireAdminConfiguration(configuration)
  const room = String(roomName || '').trim()
  const identity = String(participantIdentity || '').trim()
  if (!room || !identity) throw new Error('LIVE_PARTICIPANT_INVALID')
  const client = await clientFactory(configuration)
  await client.removeParticipant(room, identity)
}

function isAudioTrack(track) {
  const type = String(track?.type ?? '').trim().toLowerCase()
  const source = String(track?.source ?? '').trim().toLowerCase()
  return type === 'audio' || type === '0' || source === 'microphone' || source === '2'
}

async function muteLiveKitParticipantAudio({
  configuration,
  roomName,
  participantIdentity,
  clientFactory = defaultClientFactory,
}) {
  requireAdminConfiguration(configuration)
  const room = String(roomName || '').trim()
  const identity = String(participantIdentity || '').trim()
  if (!room || !identity) throw new Error('LIVE_PARTICIPANT_INVALID')
  const client = await clientFactory(configuration)
  const participant = await client.getParticipant(room, identity)
  const tracks = Array.isArray(participant?.tracks) ? participant.tracks : []
  const audioTracks = tracks.filter(isAudioTrack)
  for (const track of audioTracks) {
    const sid = String(track?.sid || '').trim()
    if (sid) await client.mutePublishedTrack(room, identity, sid, true)
  }
  return { mutedTracks: audioTracks.filter((track) => String(track?.sid || '').trim()).length }
}

module.exports = {
  isAudioTrack,
  liveKitHttpUrl,
  muteLiveKitParticipantAudio,
  removeLiveKitParticipant,
}
