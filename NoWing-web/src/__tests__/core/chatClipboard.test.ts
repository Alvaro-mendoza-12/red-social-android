import { clipboardFilesFrom, clipboardMayContainImage, clipboardHasStickerHint, readClipboardImages, pastedAsSticker, canPasteIntoChat, isStickerImage, isCopiedStickerImage } from '../../core/chatClipboard'

test('accepts file data and items-only clipboard payloads without duplicates', () => {
  const file = new File(['sticker'], 'sticker.webp', { type: 'image/webp' })
  const data = { files: [file], items: [{ kind: 'file', getAsFile: () => file }] } as unknown as DataTransfer
  expect(clipboardFilesFrom(data)).toEqual([file])
  expect(clipboardFilesFrom({ ...data, files: [] } as unknown as DataTransfer)).toEqual([file])
  expect(clipboardFilesFrom(null)).toEqual([])
})
test('does not treat links, plain text, or empty blobs as sticker files', () => {
  expect(clipboardFilesFrom({ files: [], items: [{kind:'string',getAsFile:()=>null}] } as unknown as DataTransfer)).toEqual([])
  expect(clipboardFilesFrom({ files: [new File([], 'empty.png')], items: [] } as unknown as DataTransfer)).toEqual([])
  expect(clipboardFilesFrom({ files: [new File(['pdf'],'notes.pdf',{type:'application/pdf'})], items: [] } as unknown as DataTransfer).map(file => file.name)).toEqual(['notes.pdf'])
})
test('extracts only embedded image bytes from rich clipboard HTML', () => {
  const dataUrl = 'data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+jOZ0AAAAASUVORK5CYII='
  const data = { files: [], items: [], types: ['text/html'], getData: (type: string) => type === 'text/html' ? `<img src="${dataUrl}">` : '' } as unknown as DataTransfer
  const files = clipboardFilesFrom(data)
  expect(files).toHaveLength(1)
  expect(files[0]).toMatchObject({ type: 'image/png', size: expect.any(Number) })
  const remote = { ...data, getData: (type: string) => type === 'text/html' ? '<img src="https://example.com/sticker.webp">' : '' } as unknown as DataTransfer
  expect(clipboardFilesFrom(remote)).toEqual([])
})
test('uses async image clipboard data only when paste advertises image content', async () => {
  const getType = jest.fn(async () => new Blob(['sticker'], { type: 'image/webp' }))
  const clipboard = { read: async () => [{ types: ['image/webp'], getType }] } as unknown as Clipboard
  const data = { files: [], items: [], types: ['image/webp'], getData: () => '' } as unknown as DataTransfer
  const files = await readClipboardImages(data, clipboard)
  expect(getType).toHaveBeenCalledWith('image/webp')
  expect(files).toHaveLength(1)
  expect(files[0]).toMatchObject({ type: 'image/webp', name: 'clipboard-image.webp' })
  const textOnly = { files: [], items: [], types: ['text/plain'], getData: () => 'hola' } as unknown as DataTransfer
  expect(clipboardMayContainImage(textOnly)).toBe(false)
  expect(await readClipboardImages(textOnly, clipboard)).toEqual([])
})
test('recognizes sticker formats and explicit tray intent, leaving ordinary photos alone', () => {
  for (const mime of ['image/webp','image/gif']) expect(pastedAsSticker(new File(['a'],'sticker',{type:mime}),false)).toBe(true)
  const png = new File(['a'],'image.png',{type:'image/png'})
  expect(pastedAsSticker(png,false)).toBe(false)
  expect(pastedAsSticker(png,true)).toBe(true)
  expect(isStickerImage(new File(['<svg/>'],'x.svg',{type:'image/svg+xml'}))).toBe(false)
})
test('preserves text paste in unrelated inputs and dialogs', () => {
  const input=document.createElement('input');document.body.append(input)
  expect(canPasteIntoChat(input)).toBe(false)
  input.className='chat-input-field';expect(canPasteIntoChat(input)).toBe(true)
  const tray=document.createElement('section');tray.className='stickers-tray';const search=document.createElement('input');tray.append(search)
  expect(canPasteIntoChat(search)).toBe(true)
  const dialog=document.createElement('div');dialog.setAttribute('role','dialog');dialog.append(input)
  expect(canPasteIntoChat(input)).toBe(false)
  expect(canPasteIntoChat(document.body)).toBe(true)
  input.remove()
})

test('keeps opaque square clipboard PNGs as stickers without auto-sending large photos', async () => {
  const previous = globalThis.createImageBitmap
  const close = jest.fn()
  globalThis.createImageBitmap = jest.fn(async () => ({ width: 512, height: 512, close })) as unknown as typeof createImageBitmap
  try {
    const png = new File(['png'], 'image.png', { type: 'image/png' })
    expect(await isCopiedStickerImage(png)).toBe(true)
    expect(close).toHaveBeenCalledTimes(1)
    globalThis.createImageBitmap = jest.fn(async () => ({ width: 1920, height: 1080, close })) as unknown as typeof createImageBitmap
    expect(await isCopiedStickerImage(png)).toBe(false)
    expect(await isCopiedStickerImage(png, true)).toBe(true)
  } finally { globalThis.createImageBitmap = previous }
})

test('reads sticker semantics from clipboard metadata even when its file became PNG', () => {
  const data = { types: ['text/html'], getData: () => '<img alt="Sticker" src="blob:https://web.whatsapp.com/example">' } as unknown as DataTransfer
  expect(clipboardHasStickerHint(data)).toBe(true)
  expect(clipboardHasStickerHint({ ...data, getData: () => '<img alt="Foto" src="https://example.com/photo.jpg">' } as unknown as DataTransfer)).toBe(false)
})
