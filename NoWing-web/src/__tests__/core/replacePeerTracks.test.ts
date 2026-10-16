import { replacePeerTracks } from '../../core/replacePeerTracks'
function peer(kind = 'video', reject = false) {
  const previous = { kind } as MediaStreamTrack
  const sender = { track: previous, replaceTrack: jest.fn(async (track: MediaStreamTrack | null) => { if (reject && track !== previous) throw new Error('Replace failed');sender.track = track as MediaStreamTrack }) }
  const transceiver = { sender, receiver: { track: { kind } }, direction: 'sendrecv' }
  const connection = { getTransceivers: () => [transceiver], addTrack: jest.fn(), removeTrack: jest.fn() }
  return { connection: connection as unknown as RTCPeerConnection, sender, previous, transceiver }
}
const stream = {} as MediaStream, next = { kind: 'video' } as MediaStreamTrack
test('reuses the negotiated sender when changing camera or sharing a screen', async () => {
  const { connection, sender } = peer()
  await replacePeerTracks([connection], 'video', next, stream)
  expect(sender.track).toBe(next);expect(connection.addTrack).not.toHaveBeenCalled()
})
test('rolls every peer back if one rejects the new device', async () => {
  const one = peer(), two = peer('video', true)
  await expect(replacePeerTracks([one.connection,two.connection], 'video', next, stream)).rejects.toThrow('Replace failed')
  expect(one.sender.track).toBe(one.previous);expect(two.sender.track).toBe(two.previous)
})
test('can turn video off and reuse a video receiver whose sender track is null', async () => {
  const { connection, sender, transceiver } = peer()
  await replacePeerTracks([connection], 'video', null, stream);expect(sender.track).toBeNull()
  expect(transceiver.direction).toBe('recvonly')
  await replacePeerTracks([connection], 'video', next, stream);expect(sender.track).toBe(next)
  expect(transceiver.direction).toBe('sendrecv')
  expect(connection.addTrack).not.toHaveBeenCalled()
})
test('removes a newly added sender when another peer fails', async () => {
  const sender = {} as RTCRtpSender, removeTrack = jest.fn()
  const fresh = { getTransceivers: () => [], addTrack: jest.fn(() => sender), removeTrack } as unknown as RTCPeerConnection
  await expect(replacePeerTracks([fresh,peer('video',true).connection], 'video', next, stream)).rejects.toThrow()
  expect(removeTrack).toHaveBeenCalledWith(sender)
})
