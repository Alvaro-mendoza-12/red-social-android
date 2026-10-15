import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { ChatAttachmentMedia, attachmentKind } from '../../components/ChatAttachmentMedia'
import { readBoundedStorageBlob } from '../../core/media/boundedStorageRead'
import { invalidateStorageReadIdentity, updateStorageReadIdentity } from '../../core/media/storageReadSession'

jest.mock('../../core/runtimeBackend', () => ({ rewriteRuntimeStorageUrl: (url: string) => url, runtimeStoragePreviewUrl: (url: string) => url }))
jest.mock('../../components/DocumentPreview', () => ({ DocumentPreview: () => <section aria-label="Vista previa del documento" /> }))
jest.mock('../../core/media/browserStreaming', () => ({ browserStreamingSource: jest.fn(async () => '/__novvin_media/aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa') }))
jest.mock('../../core/media/storageReadClient', () => ({
  prepareStorageReadAccessToken: jest.fn(async () => 'synthetic.test.signature'),
  trustedStorageBackend: (url: string) => url.startsWith('https://backend.test/storage/v1/object/') ? 'https://backend.test' : '',
}))
jest.mock('../../core/media/boundedStorageRead', () => ({
  readBoundedStorageBlob: jest.fn(), STORAGE_PREVIEW_MAX_BYTES: 16 * 1024 * 1024, STORAGE_SHORT_MEDIA_MAX_BYTES: 32 * 1024 * 1024,
}))

const privateUrl = 'https://backend.test/storage/v1/object/public/chat-media/a/file.png'
const readBlob = readBoundedStorageBlob as jest.MockedFunction<typeof readBoundedStorageBlob>
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

test('delivers authorized Blob and revokes it when unmounted', async () => {
  readBlob.mockResolvedValue(new Blob([new Uint8Array([1, 2])], { type: 'image/png' }))
  const { unmount } = render(<ChatAttachmentMedia url={privateUrl} kind="image" title="Foto" />)
  expect((await screen.findByAltText('Foto')).getAttribute('src')).toBe('blob:private-preview')
  unmount(); expect(revokeObjectURL).toHaveBeenCalledWith('blob:private-preview')
})

test('shows retry without public fallback after authorization denial', async () => {
  readBlob.mockRejectedValue(new Error('MEDIA_READ_404'))
  const { container } = render(<ChatAttachmentMedia url={privateUrl} kind="image" title="Foto" />)
  expect(await screen.findByText('No se pudo abrir el archivo')).toBeInTheDocument()
  expect(container.querySelector('a,img')).toBeNull(); expect(screen.getByRole('button', { name: 'Reintentar' })).toBeInTheDocument()
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
