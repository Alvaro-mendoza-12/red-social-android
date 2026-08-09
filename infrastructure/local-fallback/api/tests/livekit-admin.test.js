const assert = require('assert')
const {
  isAudioTrack,
  liveKitHttpUrl,
  muteLiveKitParticipantAudio,
  removeLiveKitParticipant,
} = require('../src/livekit-admin')

const configuration = {
  url: 'wss://novvin.example.test',
  apiKey: 'test-key',
  apiSecret: 'test-secret',
}

async function run() {
  assert.strictEqual(liveKitHttpUrl('wss://novvin.example.test/'), 'https://novvin.example.test')
  assert.strictEqual(liveKitHttpUrl('ws://localhost:7880'), 'http://localhost:7880')
  assert.strictEqual(liveKitHttpUrl('ftp://invalid'), '')
  assert.strictEqual(isAudioTrack({ type: 0 }), true)
  assert.strictEqual(isAudioTrack({ source: 'MICROPHONE' }), true)
  assert.strictEqual(isAudioTrack({ type: 1, source: 1 }), false)

  const calls = []
  const clientFactory = async () => ({
    async removeParticipant(room, identity) { calls.push(['remove', room, identity]) },
    async getParticipant() {
      return { tracks: [{ sid: 'audio-1', type: 0 }, { sid: 'video-1', type: 1 }] }
    },
    async mutePublishedTrack(room, identity, sid, muted) {
      calls.push(['mute', room, identity, sid, muted])
    },
  })
  await removeLiveKitParticipant({
    configuration,
    roomName: 'room-1',
    participantIdentity: 'guest-1',
    clientFactory,
  })
  const result = await muteLiveKitParticipantAudio({
    configuration,
    roomName: 'room-1',
    participantIdentity: 'guest-1',
    clientFactory,
  })
  assert.strictEqual(result.mutedTracks, 1)
  assert.deepStrictEqual(calls, [
    ['remove', 'room-1', 'guest-1'],
    ['mute', 'room-1', 'guest-1', 'audio-1', true],
  ])
  console.log('livekit admin tests passed')
}

run().catch((error) => {
  console.error(error)
  process.exitCode = 1
})
