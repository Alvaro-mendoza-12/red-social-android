import {registerOutboxPreview} from '../../core/media/outboxPreviewRegistry'
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { ChatAttachmentMedia } from '../../components/ChatAttachmentMedia'
import {attachmentKind} from '../../core/attachmentKind'
import { readBoundedStorageBlob } from '../../core/media/boundedStorageRead'
import { invalidateStorageReadIdentity, updateStorageReadIdentity, getStorageReadIdentity } from '../../core/media/storageReadSession'

jest.mock('../../core/runtimeBackend', () => ({ rewriteRuntimeStorageUrl: (url: string) => url, runtimeStoragePreviewUrl: (url: string) => url }))
jest.mock('../../components/DocumentPreview', () => ({ DocumentPreview: () => <section aria-label="Vista previa del documento" /> }))
jest.mock('../../core/media/browserStreaming', () => ({ cachedBrowserStreamingSource: jest.fn(() => undefined), browserStreamingSource: jest.fn(async () => '/__novvin_media/aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'), subscribeBrowserStreamingRecovery: jest.fn(() => () => {}) }))
jest.mock('../../core/media/storageReadClient', () => ({
  prepareStorageReadAccessToken: jest.fn(async () => 'synthetic.test.signature'),
  trustedStorageBackend: (url: string) => url.startsWith('https://backend.test/storage/v1/object/') ? 'https://backend.test' : '',
}))
jest.mock('../../core/media/boundedStorageRead', () => ({
  readBoundedStorageBlob: jest.fn(), STORAGE_PREVIEW_MAX_BYTES: 16 * 1024 * 1024, STORAGE_SHORT_MEDIA_MAX_BYTES: 32 * 1024 * 1024,
}))

const privateUrl = 'https://backend.test/storage/v1/object/public/chat-media/a/file.png'
const readBlob = readBoundedStorageBlob as jest.MockedFunction<typeof readBoundedStorageBlob>

test('images reserve the same frame while awaiting and after receiving their bytes', async () => {
  let finish!: (value: Blob) => void
  readBlob.mockImplementation(() => new Promise(resolve => { finish = resolve }))
  const { container } = render(<ChatAttachmentMedia url={privateUrl} kind="image" title="Foto estable" inlineDownload={false}/>)
  await waitFor(() => expect(readBlob).toHaveBeenCalledTimes(1))
  const frame = container.querySelector('.chat-attachment-image-frame')
  expect(frame).toContainElement(screen.getByText('Preparando archivo'))
  await act(async () => finish(new Blob(['photo'], { type: 'image/png' })))
  expect(frame).toContainElement(await screen.findByAltText('Foto estable'))
  expect(container.querySelector('.chat-attachment-image-frame')).toBe(frame)
})

test('scrolling away releases a pending image read so visible messages can load', async () => {
  const callbacks: IntersectionObserverCallback[] = []
  const previous = globalThis.IntersectionObserver
  Object.defineProperty(globalThis, 'IntersectionObserver', { configurable: true, value: class {
    constructor(callback: IntersectionObserverCallback) { callbacks.push(callback) }
    observe() {} disconnect() {} unobserve() {} takeRecords() { return [] }
  } })
  try {
    readBlob.mockImplementation(() => new Promise(() => {}))
    render(<ChatAttachmentMedia url={privateUrl} kind="image" title="Foto lejana" inlineDownload={false}/>)
    act(() => callbacks[0]([{ isIntersecting: true } as IntersectionObserverEntry], {} as IntersectionObserver))
    await waitFor(() => expect(readBlob).toHaveBeenCalledTimes(1))
    const signal = readBlob.mock.calls[0][0].signal
    act(() => callbacks[0]([{ isIntersecting: false } as IntersectionObserverEntry], {} as IntersectionObserver))
    expect(signal.aborted).toBe(true)
  } finally { Object.defineProperty(globalThis, 'IntersectionObserver', { configurable: true, value: previous }) }
})

test('returning to a cached photo renders it immediately with the same source and no new read', async () => {
  readBlob.mockResolvedValue(new Blob(['original-image'], { type: 'image/png' }))
  const first = render(<ChatAttachmentMedia url={privateUrl} kind="image" title="Foto precargada"/>)
  const url = (await screen.findByAltText('Foto precargada')).getAttribute('src')
  first.unmount()
  const count = readBlob.mock.calls.length
  render(<ChatAttachmentMedia url={privateUrl} kind="image" title="Foto precargada"/>)
  expect(screen.getByAltText('Foto precargada')).toHaveAttribute('src', url)
  expect(readBlob).toHaveBeenCalledTimes(count)
})

test('late attachment metadata does not remove an already rendered image source', async () => {
  readBlob.mockResolvedValue(new Blob(['photo'], { type: 'image/png' }))
  const { rerender } = render(<ChatAttachmentMedia url={privateUrl} kind="image" title="Foto estable" size={0}/>)
  const image = await screen.findByAltText('Foto estable')
  const source = image.getAttribute('src')
  await act(async () => { rerender(<ChatAttachmentMedia url={privateUrl} kind="image" title="Foto estable" size={12345}/>) })
  await waitFor(() => expect(image).toHaveAttribute('src', source))
  expect(readBlob).toHaveBeenCalledTimes(1)
  expect(image).toHaveAttribute('loading', 'eager')
})

test('a large original image still loads its bounded thumbnail', async () => {
  readBlob.mockResolvedValue(new Blob(['small-preview'], { type: 'image/webp' }))
  render(<ChatAttachmentMedia url={privateUrl} kind="image" title="Foto grande" size={30 * 1024 * 1024}/>)
  expect(await screen.findByAltText('Foto grande')).toBeInTheDocument()
  expect(screen.queryByText('Archivo demasiado grande para la vista web')).toBeNull()
})
const createObjectURL = jest.fn((_blob: Blob) => 'blob:private-preview')
const revokeObjectURL = jest.fn()

beforeEach(() => {
  readBlob.mockReset(); createObjectURL.mockClear(); revokeObjectURL.mockClear()
  Object.assign(URL, { createObjectURL, revokeObjectURL })
  updateStorageReadIdentity('account-a', 'fallback')
})
afterEach(() => { act(() => invalidateStorageReadIdentity()) })

test('keeps private URLs out of DOM before an authorized read completes', async () => {
  readBlob.mockImplementation(() => new Promise(() => {}))
  const { container } = render(<ChatAttachmentMedia url={privateUrl} kind="image" title="Foto" />)
  await waitFor(() => expect(readBlob).toHaveBeenCalledTimes(1))
  expect(container.querySelector('img,a,video,audio,iframe')).toBeNull()
  expect(screen.getByText('Preparando archivo')).toBeInTheDocument()
})

test('retains authorized image on remount and revokes it on account invalidation', async () => {
  readBlob.mockResolvedValue(new Blob([new Uint8Array([1, 2])], { type: 'image/png' }))
  const { unmount } = render(<ChatAttachmentMedia url={privateUrl} kind="image" title="Foto" />)
  expect((await screen.findByAltText('Foto')).getAttribute('src')).toBe('blob:private-preview')
  unmount(); expect(revokeObjectURL).not.toHaveBeenCalled()
  act(() => invalidateStorageReadIdentity()); expect(revokeObjectURL).toHaveBeenCalledWith('blob:private-preview')
})

test('shows retry without public fallback after authorization denial', async () => {
  readBlob.mockRejectedValue(new Error('MEDIA_READ_404'))
  const { container } = render(<ChatAttachmentMedia url={privateUrl} kind="image" title="Foto" />)
  expect(await screen.findByText('No se pudo abrir el archivo')).toBeInTheDocument()
  expect(container.querySelector('a,img')).toBeNull(); expect(screen.getByRole('button', { name: 'Volver a cargar' })).toBeInTheDocument()
})

test('clears decoded images and revokes Blob synchronously on logout', async () => {
  readBlob.mockResolvedValue(new Blob([new Uint8Array([1])], { type: 'image/png' }))
  const { container } = render(<ChatAttachmentMedia url={privateUrl} kind="image" title="Foto" />)
  await screen.findByAltText('Foto')
  act(() => invalidateStorageReadIdentity())
  expect(revokeObjectURL).toHaveBeenCalledWith('blob:private-preview')
  expect(container.querySelector('img,a')).toBeNull()
  expect(screen.getByText('Inicia sesión para abrir este archivo.')).toBeInTheDocument()
})

test('discards late result from account A after switching to B', async () => {
  let complete!: (blob: Blob) => void
  readBlob.mockImplementationOnce(() => new Promise((resolve) => { complete = resolve }))
  readBlob.mockImplementation(() => new Promise(() => {}))
  render(<ChatAttachmentMedia url={privateUrl} kind="image" title="Foto" />)
  await waitFor(() => expect(readBlob).toHaveBeenCalledTimes(1))
  act(() => updateStorageReadIdentity('account-b', 'fallback'))
  await act(async () => complete(new Blob([new Uint8Array([1])], { type: 'image/png' })))
  expect(screen.queryByAltText('Foto')).toBeNull(); expect(createObjectURL).not.toHaveBeenCalled()
})

test('large videos stream through an authenticated opaque handle without downloading a whole Blob', async () => {
  const { container } = render(<ChatAttachmentMedia url={privateUrl} kind="video" title="Video" size={33 * 1024 * 1024} />)
  await waitFor(() => expect(container.querySelector('video')?.getAttribute('src')).toBe('/__novvin_media/aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'))
  expect(readBlob).not.toHaveBeenCalled(); expect(container.querySelector('a')).toBeNull()
})

test('does not inline unexpected HTML as an image', async () => {
  readBlob.mockResolvedValue(new Blob(['<h1>test</h1>'], { type: 'text/html' }))
  const { container } = render(<ChatAttachmentMedia url={privateUrl} kind="image" title="Foto" />)
  await screen.findByText('No se pudo abrir el archivo'); expect(container.querySelector('img,a')).toBeNull(); expect(createObjectURL).not.toHaveBeenCalled()
})

test('untrusted schemes never create media tags or links', () => {
  const { container } = render(<ChatAttachmentMedia url="javascript:alert(1)" kind="file" title="Archivo" />)
  expect(container.querySelector('a,img,iframe')).toBeNull(); expect(readBlob).not.toHaveBeenCalled()
})

test('external HTTPS media receives no app read credential', () => {
  const { container } = render(<ChatAttachmentMedia url="https://public.test/image.png" kind="image" title="Pública" />)
  expect(container.querySelector('img')?.getAttribute('src')).toBe('https://public.test/image.png'); expect(readBlob).not.toHaveBeenCalled()
})

test('unknown documents use a download Blob with inert MIME', async () => {
  readBlob.mockResolvedValue(new Blob(['<h1>test</h1>'], { type: 'text/html' }))
  const { container } = render(<ChatAttachmentMedia url={privateUrl} kind="file" title="document.html" />)
  expect(readBlob).not.toHaveBeenCalled()
  fireEvent.click(screen.getByRole('button',{name:'Abrir documento'}))
  await screen.findByRole('dialog',{name:'Ver documento'})
  expect(container.querySelector('a')?.getAttribute('download')).toBe('document.html')
  expect(createObjectURL.mock.calls[0][0].type).toBe('application/octet-stream')
})

test.each([
  ['file', 'https://public.test/file.m4v', 'video'],
  ['file', 'https://public.test/file.oga?name=1', 'audio'],
  ['audio', 'https://public.test/file.webm', 'audio'],
])('preserves media classification %s %s', (contentType, url, expected) => {
  expect(attachmentKind(contentType, url)).toBe(expected)
})

test('document previews obey the actual bounded storage reader limit',async()=>{
  readBlob.mockImplementation(async options=>{if(options.maxBytes>32*1024*1024)throw new Error('MEDIA_INVALID_LIMIT');return new Blob(['hello'],{type:'text/plain'})})
  render(<ChatAttachmentMedia url={privateUrl.replace('.png','.txt')} kind="file" title="notes.txt" />)
  fireEvent.click(screen.getByRole('button',{name:'Abrir documento'}));await screen.findByRole('dialog',{name:'Ver documento'})
  expect(readBlob).toHaveBeenCalledWith(expect.objectContaining({maxBytes:32*1024*1024}))
})
test('large documents retain a download action when inline preview is too large',async()=>{
  render(<ChatAttachmentMedia url={privateUrl.replace('.png','.pdf')} kind="pdf" title="large.pdf" size={33*1024*1024} />)
  fireEvent.click(screen.getByRole('button',{name:'Abrir documento'}));await screen.findByText('Archivo demasiado grande para la vista web')
  expect(screen.getByRole('button',{name:'Descargar original'})).toBeInTheDocument();expect(readBlob).not.toHaveBeenCalled()
})

test('an owned pending image renders locally and is cleared immediately when the identity changes',async()=>{
 const local='blob:http://localhost/owned-pending';registerOutboxPreview(local,getStorageReadIdentity())
 const {container}=render(<ChatAttachmentMedia url={local} kind="image" title="Foto pendiente"/>);expect(await screen.findByAltText('Foto pendiente')).toHaveAttribute('src',local);expect(readBlob).not.toHaveBeenCalled()
 act(()=>invalidateStorageReadIdentity());expect(container.querySelector('img')).toBeNull()
})
test('a pending blob from an unregistered account is never rendered',()=>{
 const {container}=render(<ChatAttachmentMedia url="blob:http://localhost/another-account" kind="image" title="Privada"/>);expect(container.querySelector('img')).toBeNull()
})

test('a full read queue retries without showing a broken sticker', async () => {
  readBlob.mockRejectedValueOnce(new Error('MEDIA_READ_QUEUE_FULL')).mockResolvedValue(new Blob(['animated'], { type: 'image/webp' }))
  render(<ChatAttachmentMedia url={privateUrl} kind="image" title="Sticker" preserveAnimation imageLink={false} allowRetry={false}/>)
  await screen.findByAltText('Sticker')
  expect(screen.queryByText('No se pudo abrir el archivo')).toBeNull()
  expect(readBlob).toHaveBeenCalledTimes(2)
})

test('animated PNG stickers use their original authorized bytes', async () => {
  readBlob.mockResolvedValue(new Blob(['animated-png'], { type: 'image/apng' }))
  render(<ChatAttachmentMedia url={privateUrl} kind="image" title="Sticker animado" preserveAnimation imageLink={false}/>)
  await screen.findByAltText('Sticker animado')
  expect(createObjectURL.mock.calls[0][0].type).toBe('image/apng')
})
