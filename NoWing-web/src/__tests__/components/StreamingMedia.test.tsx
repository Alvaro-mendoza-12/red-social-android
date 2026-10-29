import { act, fireEvent, render, waitFor } from '@testing-library/react'
import { StreamingAudio, StreamingImage, StreamingVideo } from '../../components/StreamingMedia'
import { browserStreamingSource, cachedBrowserStreamingSource } from '../../core/media/browserStreaming'
import { invalidateStorageReadIdentity, updateStorageReadIdentity } from '../../core/media/storageReadSession'

jest.mock('../../core/runtimeBackend', () => ({ rewriteRuntimeStorageUrl: (url: string) => url }))
jest.mock('../../core/media/storageReadClient', () => ({ trustedStorageBackend: (url: string) => url.startsWith('https://private.test/') ? 'https://private.test' : '' }))
jest.mock('../../core/media/browserStreaming', () => ({ cachedBrowserStreamingSource: jest.fn(() => undefined), browserStreamingSource: jest.fn(), subscribeBrowserStreamingRecovery: jest.fn(() => () => {}) }))
const stream = browserStreamingSource as jest.MockedFunction<typeof browserStreamingSource>
const media = 'https://private.test/storage/v1/object/public/videos/clip.mp4'
beforeEach(() => {
  stream.mockImplementation(async (url, identity) => `/__novvin_media/${identity?.accountId}/${url.endsWith('.png') ? 'poster' : 'video'}`)
  updateStorageReadIdentity('account-a', 'fallback')
})
afterEach(() => { act(() => invalidateStorageReadIdentity()); stream.mockReset() })

test('a remounted avatar has an authorized cached source on its first render', async () => {
  ;(cachedBrowserStreamingSource as jest.Mock).mockReturnValueOnce('/__novvin_media/cached-avatar')
  const { container } = render(<StreamingImage src={media} alt="Foto guardada"/>)
  expect(container.querySelector('img')).toHaveAttribute('src', '/__novvin_media/cached-avatar')
  await act(async () => { await Promise.resolve() })
})

test('video and its poster both use authenticated opaque handles', async () => {
  const {container}=render(<StreamingVideo src={media} poster={media+'.png'} controls />)
  await waitFor(()=>expect(container.querySelector('video')).toHaveAttribute('src','/__novvin_media/account-a/video'))
  expect(container.querySelector('video')).toHaveAttribute('poster','/__novvin_media/account-a/poster')
  expect(container.innerHTML).not.toContain('https://private.test')
})
test('switching accounts removes every previously resolved source', async () => {
  const {container}=render(<StreamingVideo src={media} poster={media+'.png'} />)
  await waitFor(()=>expect(container.querySelector('video')).toHaveAttribute('src','/__novvin_media/account-a/video'))
  act(()=>updateStorageReadIdentity('account-b','fallback'))
  await waitFor(()=>expect(container.querySelector('video')).toHaveAttribute('src','/__novvin_media/account-b/video'))
  expect(container.innerHTML).not.toContain('account-a')
})
test('signed-out elements never expose an authenticated storage URL', () => {
  invalidateStorageReadIdentity()
  const {container}=render(<><StreamingVideo src={media}/><StreamingImage src={media}/><StreamingAudio src={media}/></>)
  expect(container.innerHTML).not.toContain('https://private.test')
  expect(container.querySelectorAll('[src]')).toHaveLength(0)
  expect(stream).not.toHaveBeenCalled()
})
test('local pending attachment Blobs stay local', () => {
  const {container}=render(<StreamingVideo src="blob:pending-attachment" />)
  expect(container.querySelector('video')).toHaveAttribute('src','blob:pending-attachment')
  expect(stream).not.toHaveBeenCalled()
})

test('guest media uses its own opaque handle and is removed on sign-in', async () => {
  updateStorageReadIdentity('guest-session', 'guest')
  const {container}=render(<StreamingVideo src={media} />)
  await waitFor(()=>expect(container.querySelector('video')).toHaveAttribute('src','/__novvin_media/guest-session/video'))
  act(()=>updateStorageReadIdentity('account-b','fallback'))
  await waitFor(()=>expect(container.querySelector('video')).toHaveAttribute('src','/__novvin_media/account-b/video'))
  expect(container.innerHTML).not.toContain('guest-session')
})

test('a transient registration failure retries instead of leaving a source empty', async () => {
  stream.mockRejectedValueOnce(new Error('MEDIA_STREAMING_UNAVAILABLE'))
  const {container}=render(<StreamingImage src={media} />)
  await waitFor(()=>expect(container.querySelector('img')?.getAttribute('src')).toContain('/__novvin_media/account-a/video'), {timeout:2000})
  expect(stream).toHaveBeenCalledTimes(2)
})

test('native media errors recover with a fresh request before reporting permanent failure', async () => {
  const onError=jest.fn(),{container}=render(<StreamingVideo src={media} onError={onError}/>)
  await waitFor(()=>expect(container.querySelector('video')).toHaveAttribute('src','/__novvin_media/account-a/video'))
  fireEvent.error(container.querySelector('video')!)
  expect(onError).not.toHaveBeenCalled()
  await waitFor(()=>expect(container.querySelector('video')).toHaveAttribute('src','/__novvin_media/account-a/video?retry=1'), {timeout:2000})
})

test('changing accounts cancels a scheduled retry for the previous identity', async () => {
  stream.mockRejectedValueOnce(new Error('MEDIA_STREAMING_UNAVAILABLE'))
  const {container}=render(<StreamingImage src={media}/>)
  await act(async()=>{await Promise.resolve()})
  act(()=>updateStorageReadIdentity('account-b','fallback'))
  await waitFor(()=>expect(container.querySelector('img')).toHaveAttribute('src','/__novvin_media/account-b/video'))
  await act(async()=>{await new Promise(resolve=>setTimeout(resolve,400))})
  expect(stream).toHaveBeenCalledTimes(2)
})

test('permanent audio registration failure is reported after bounded retries', async () => {
  jest.useFakeTimers()
  try {
    stream.mockRejectedValue(new Error('NOT_FOUND'))
    const unavailable = jest.fn()
    const { unmount } = render(<StreamingAudio src={media} onSourceUnavailable={unavailable}/>)
    await act(async () => { await Promise.resolve() })
    for (const delay of [350,1200,3000]) await act(async () => { jest.advanceTimersByTime(delay); await Promise.resolve() })
    expect(unavailable).toHaveBeenCalledTimes(1)
    expect(stream).toHaveBeenCalledTimes(4)
    unmount()
  } finally { jest.useRealTimers() }
})

test('video registration failure is surfaced even when no native src was ever assigned', async () => {
  jest.useFakeTimers()
  try {
    stream.mockRejectedValue(new Error('NOT_FOUND'))
    const unavailable = jest.fn()
    const { container, unmount } = render(<StreamingVideo src={media} onSourceUnavailable={unavailable}/>)
    await act(async () => { await Promise.resolve() })
    for (const delay of [350,1200,3000]) await act(async () => { jest.advanceTimersByTime(delay); await Promise.resolve() })
    expect(container.querySelector('video')).not.toHaveAttribute('src')
    expect(unavailable).toHaveBeenCalledTimes(1)
    unmount()
  } finally { jest.useRealTimers() }
})
