import {
  normalizeSignalingParticipant,
  normalizeSignalingParticipants,
  updateRemoteVoiceState,
  voiceBoolean,
} from '../../../features/voice/voiceSignalingProtocol'

describe('voice signaling protocol compatibility', () => {
  it('does not mistake string false, absence or silence for a muted microphone', () => {
    expect(voiceBoolean('false')).toBe(false)
    expect(voiceBoolean(undefined)).toBeUndefined()
    expect(updateRemoteVoiceState({ isMuted: true, isSpeaking: false }, { isSpeaking: true })).toEqual({ isMuted: false, isSpeaking: true })
    expect(updateRemoteVoiceState({ isMuted: false, isSpeaking: true }, { isSpeaking: false })).toEqual({ isMuted: false, isSpeaking: false })
  })
  it('an explicit mute immediately clears the speaking ring and partial offers preserve state', () => {
    expect(updateRemoteVoiceState({ isMuted: false, isSpeaking: true }, { isMuted: true })).toEqual({ isMuted: true, isSpeaking: false })
    expect(updateRemoteVoiceState({ isMuted: false, isSpeaking: true }, {})).toEqual({ isMuted: false, isSpeaking: true })
    expect(updateRemoteVoiceState({ isMuted: null, isSpeaking: false }, {})).toEqual({ isMuted: null, isSpeaking: false })
  })
  it('normalizes the object payload emitted by the shared signaling server', () => {
    expect(normalizeSignalingParticipant({
      socketId: 'web-1',
      userId: 'user-1',
      name: 'Ana',
      avatarUrl: '/avatars/ana.png',
      isMuted: true,
    })).toEqual({
      socketId: 'web-1',
      userId: 'user-1',
      name: 'Ana',
      avatarUrl: '/avatars/ana.png',
      isMuted: true,
    })
  })

  it('accepts legacy socket-id arrays used by older APK clients', () => {
    expect(normalizeSignalingParticipants(['android-1', 'web-2'])).toEqual([
      { socketId: 'android-1', userId: 'android-1' },
      { socketId: 'web-2', userId: 'web-2' },
    ])
  })

  it('accepts snake-case participant rows and drops malformed entries', () => {
    expect(normalizeSignalingParticipants([
      { socket_id: 'android-3', user_id: 'user-3', user_name: 'Luis', is_muted: false },
      null,
      {},
      { socketId: 'android-3', userId: 'user-3', name: 'Luis actualizado' },
    ])).toEqual([
      { socketId: 'android-3', userId: 'user-3', name: 'Luis actualizado' },
    ])
  })
})
