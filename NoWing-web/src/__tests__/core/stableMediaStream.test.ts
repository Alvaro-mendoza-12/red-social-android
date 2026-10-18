import { syncMediaTracks } from '../../core/media/stableMediaStream'

test('keeps the stream and existing tracks while another publication changes', () => {
  const audio = { kind: 'audio' } as MediaStreamTrack, video = { kind: 'video' } as MediaStreamTrack
  let tracks = [audio]
  const stream = { getTracks: () => [...tracks], addTrack: jest.fn(track => tracks.push(track)), removeTrack: jest.fn(track => { tracks = tracks.filter(value => value !== track) }) } as unknown as MediaStream
  expect(syncMediaTracks(stream, [audio, video])).toBe(stream)
  expect(stream.getTracks()).toEqual([audio, video])
  syncMediaTracks(stream, [audio, video])
  expect(stream.addTrack).toHaveBeenCalledTimes(1)
  syncMediaTracks(stream, [audio])
  expect(stream.getTracks()).toEqual([audio])
  expect(stream.removeTrack).toHaveBeenCalledWith(video)
})
