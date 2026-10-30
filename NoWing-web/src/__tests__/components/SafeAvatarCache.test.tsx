import { act, render, screen, waitFor } from '@testing-library/react'
import { CachedProfileImage, SafeAvatar } from '../../components/SafeAvatar'

test('contact covers request a bounded 640px preview and reuse the same account-scoped decoded source after reopening',async()=>{
 const first=render(<CachedProfileImage src="https://owned.test/cover.jpg" mediaType="" previewWidth={640} alt="Portada QA" onSourceUnavailable={()=>{}}/>)
 await waitFor(()=>expect(screen.getByAltText('Portada QA').getAttribute('src')).toMatch(/^blob:/))
 expect(readBoundedStorageBlob).toHaveBeenCalledWith(expect.objectContaining({url:'https://owned.test/cover.jpg?width=640'}))
 const source=screen.getByAltText('Portada QA').getAttribute('src'),calls=(readBoundedStorageBlob as jest.Mock).mock.calls.length
 first.unmount()
 render(<CachedProfileImage src="https://owned.test/cover.jpg" mediaType="" previewWidth={640} alt="Portada QA" onSourceUnavailable={()=>{}}/>)
 expect(screen.getByAltText('Portada QA')).toHaveAttribute('src',source)
 expect(readBoundedStorageBlob).toHaveBeenCalledTimes(calls)
 act(()=>updateStorageReadIdentity('cover-other-account','fallback'))
 expect(screen.getByAltText('Portada QA')).not.toHaveAttribute('src',source)
 await waitFor(()=>expect(readBoundedStorageBlob).toHaveBeenCalledTimes(calls+1))
})
import { readBoundedStorageBlob } from '../../core/media/boundedStorageRead'
import { invalidateStorageReadIdentity, updateStorageReadIdentity } from '../../core/media/storageReadSession'

jest.mock('../../core/runtimeBackend', () => ({ rewriteRuntimeStorageUrl: (url: string) => url, runtimeStoragePreviewUrl: (url: string, width = 1080) => `${url}?width=${width}` }))
jest.mock('../../core/media/storageReadClient', () => ({ trustedStorageBackend: () => 'https://owned.test', prepareStorageReadAccessToken: jest.fn(async () => 'test-token') }))
jest.mock('../../core/media/boundedStorageRead', () => ({ ...jest.requireActual('../../core/media/boundedStorageRead'), readBoundedStorageBlob: jest.fn() }))
jest.mock('../../core/media/browserStreaming', () => ({ browserStreamingSource: async (url: string) => url, cachedBrowserStreamingSource: () => undefined, subscribeBrowserStreamingRecovery: () => () => {} }))

beforeAll(() => {
  let sequence = 0
  Object.defineProperty(URL, 'createObjectURL', { configurable: true, value: jest.fn(() => `blob:avatar-${++sequence}`) })
  Object.defineProperty(URL, 'revokeObjectURL', { configurable: true, value: jest.fn() })
  Object.defineProperty(globalThis, 'IntersectionObserver', { configurable: true, value: undefined })
})
beforeEach(() => {
  jest.clearAllMocks()
  updateStorageReadIdentity('account-a', 'fallback')
  ;(readBoundedStorageBlob as jest.Mock).mockResolvedValue(new Blob(['avatar'], { type: 'image/png' }))
})
afterEach(() => act(() => invalidateStorageReadIdentity()))

test('a previously loaded avatar returns on the first render without another transfer', async () => {
  const first = render(<SafeAvatar url="https://owned.test/photo.png" name="Amigo" />)
  await waitFor(() => expect(screen.getByRole('img', { name: 'Amigo' }).getAttribute('src')).toMatch(/^blob:/))
  const delivered = screen.getByRole('img', { name: 'Amigo' }).getAttribute('src')
  first.unmount()
  render(<SafeAvatar url="https://owned.test/photo.png" name="Amigo" />)
  expect(screen.getByRole('img', { name: 'Amigo' })).toHaveAttribute('src', delivered)
  expect(readBoundedStorageBlob).toHaveBeenCalledTimes(1)
  expect(readBoundedStorageBlob).toHaveBeenCalledWith(expect.objectContaining({ url: 'https://owned.test/photo.png?width=160' }))
})

test('simultaneous avatars share a transfer and account changes discard their decoded source', async () => {
  render(<><SafeAvatar url="https://owned.test/photo.png" name="Uno"/><SafeAvatar url="https://owned.test/photo.png" name="Dos"/></>)
  await waitFor(() => expect(screen.getByRole('img', { name: 'Uno' }).getAttribute('src')).toMatch(/^blob:/))
  const previous = screen.getByRole('img', { name: 'Uno' }).getAttribute('src')
  expect(screen.getByRole('img', { name: 'Dos' })).toHaveAttribute('src', previous)
  expect(readBoundedStorageBlob).toHaveBeenCalledTimes(1)
  act(() => updateStorageReadIdentity('account-b', 'fallback'))
  expect(screen.getByRole('img', { name: 'Uno' })).not.toHaveAttribute('src', previous)
  await waitFor(() => expect(readBoundedStorageBlob).toHaveBeenCalledTimes(2))
  expect(URL.revokeObjectURL).toHaveBeenCalledWith(previous)
})

test('animated avatar bytes retain their original source instead of a static thumbnail transform', async () => {
  ;(readBoundedStorageBlob as jest.Mock).mockResolvedValue(new Blob(['animated'], { type: 'image/gif' }))
  render(<SafeAvatar url="https://owned.test/animated.gif" name="Animado" />)
  await waitFor(() => expect(screen.getByRole('img', { name: 'Animado' }).getAttribute('src')).toMatch(/^blob:/))
  expect(readBoundedStorageBlob).toHaveBeenCalledWith(expect.objectContaining({ url: 'https://owned.test/animated.gif' }))
})

test('failure before a streaming source is assigned reaches accessible initials', async () => {
  const streaming = jest.requireMock('../../core/media/browserStreaming')
  streaming.browserStreamingSource = jest.fn(async () => { throw Error('offline') })
  ;(readBoundedStorageBlob as jest.Mock).mockRejectedValue(Error('offline'))
  jest.useFakeTimers()
  render(<SafeAvatar url="https://owned.test/missing.png" name="Mendoza" />)
  for (let step = 0; step < 8; step++) {
    await act(async () => { await jest.advanceTimersByTimeAsync(3100) })
  }
  expect(screen.getByLabelText('Mendoza')).toHaveTextContent('M')
  expect(screen.queryByRole('img', { name: 'Mendoza' })).toBeNull()
  jest.useRealTimers()
})
