import { isStandaloneVoiceRoom, voiceBackPath } from '../../../core/voiceDirectory'
import { roomSocketReady } from '../../../features/voice/voiceMembership'

test('voice directories exclude community channels by metadata and retain ordinary room titles', () => {
  expect(isStandaloneVoiceRoom({ id: 'regular', name: 'Comunidad de amigos' })).toBe(true)
  for (const field of ['community_id','communityId','community_channel_id','communityChannelId']) expect(isStandaloneVoiceRoom({ [field]: 'community-room' })).toBe(false)
  expect(isStandaloneVoiceRoom({ community_id: ' ' })).toBe(true)
})

test('leaving the voice lobby never navigates back into a prior room', () => {
  expect(voiceBackPath('/app/voice')).toBe('/app/messages')
  expect(voiceBackPath('/app/voice/')).toBe('/app/messages')
  expect(voiceBackPath('/app/voice/room')).toBe('/app/voice')
  expect(voiceBackPath('/app/messages')).toBeNull()
})

test('membership is required for outgoing room events and is invalidated by transport reconnection', () => {
  expect(roomSocketReady({ connected: true, id: 'socket-1' }, '')).toBe(false)
  expect(roomSocketReady({ connected: true, id: 'socket-1' }, 'socket-1')).toBe(true)
  expect(roomSocketReady({ connected: false, id: 'socket-1' }, 'socket-1')).toBe(false)
  expect(roomSocketReady({ connected: true, id: 'socket-2' }, 'socket-1')).toBe(false)
  expect(roomSocketReady(null, 'socket-1')).toBe(false)
})
