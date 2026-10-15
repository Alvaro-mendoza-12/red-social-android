import { act, render, waitFor } from '@testing-library/react'
import { StreamingAudio, StreamingImage, StreamingVideo } from '../../components/StreamingMedia'
import { browserStreamingSource } from '../../core/media/browserStreaming'
import { invalidateStorageReadIdentity, updateStorageReadIdentity } from '../../core/media/storageReadSession'

jest.mock('../../core/runtimeBackend', () => ({ rewriteRuntimeStorageUrl: (url: string) => url }))
jest.mock('../../core/media/storageReadClient', () => ({ trustedStorageBackend: (url: string) => url.startsWith('https://private.test/') ? 'https://private.test' : '' }))
jest.mock('../../core/media/browserStreaming', () => ({ browserStreamingSource: jest.fn() }))
const stream = browserStreamingSource as jest.MockedFunction<typeof browserStreamingSource>
const media = 'https://private.test/storage/v1/object/public/videos/clip.mp4'
beforeEach(() => {
  stream.mockImplementation(async (url, identity) => `/__novvin_media/${identity?.accountId}/${url.endsWith('.png') ? 'poster' : 'video'}`)
  updateStorageReadIdentity('account-a', 'fallback')
})
afterEach(() => { act(() => invalidateStorageReadIdentity()); stream.mockReset() })

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
