/** Keep the previous sender tracks if one peer rejects a device/source change. */
export async function replacePeerTracks(connections: Iterable<RTCPeerConnection>, kind: 'audio' | 'video', track: MediaStreamTrack | null, stream: MediaStream) {
  const changes: Array<{ connection: RTCPeerConnection; sender: RTCRtpSender; previous: MediaStreamTrack | null; added: boolean; transceiver?: RTCRtpTransceiver; direction?: RTCRtpTransceiverDirection }> = []
  const results = await Promise.allSettled(Array.from(connections, async connection => {
    const transceiver = connection.getTransceivers().find(value => value.sender.track?.kind === kind || value.receiver.track?.kind === kind), sender = transceiver?.sender
    if (sender) {
      changes.push({ connection, sender, previous: sender.track, added: false, transceiver, direction: transceiver?.direction })
      // Renegotiate direction so the receiving track mutes on stop and can resume later.
      if (transceiver && track && transceiver.direction === 'recvonly') transceiver.direction = 'sendrecv'
      else if (transceiver && track && transceiver.direction === 'inactive') transceiver.direction = 'sendonly'
      else if (transceiver && !track && transceiver.direction === 'sendrecv') transceiver.direction = 'recvonly'
      else if (transceiver && !track && transceiver.direction === 'sendonly') transceiver.direction = 'inactive'
      await sender.replaceTrack(track)
    } else if (track) {
      changes.push({ connection, sender: connection.addTrack(track, stream), previous: null, added: true })
    }
  }))
  const failure = results.find(result => result.status === 'rejected')
  if (!failure || failure.status !== 'rejected') return
  await Promise.allSettled(changes.map(async change => {
    if (change.added) change.connection.removeTrack(change.sender)
    else {
      if (change.transceiver && change.direction) change.transceiver.direction = change.direction
      await change.sender.replaceTrack(change.previous)
    }
  }))
  throw failure.reason
}
