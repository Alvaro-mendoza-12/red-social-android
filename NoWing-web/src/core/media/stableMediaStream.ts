/** Preserve the media element's source while publications arrive or change. */
export function syncMediaTracks(stream: MediaStream, tracks: MediaStreamTrack[]) {
  const wanted = new Set(tracks)
  for (const track of stream.getTracks()) if (!wanted.has(track)) stream.removeTrack(track)
  const attached = new Set(stream.getTracks())
  for (const track of tracks) if (!attached.has(track)) stream.addTrack(track)
  return stream
}
