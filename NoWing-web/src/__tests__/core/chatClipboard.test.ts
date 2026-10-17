import { clipboardFilesFrom, pastedAsSticker, canPasteIntoChat, isStickerImage } from '../../core/chatClipboard'

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
  const dialog=document.createElement('div');dialog.setAttribute('role','dialog');dialog.append(input)
  expect(canPasteIntoChat(input)).toBe(false)
  expect(canPasteIntoChat(document.body)).toBe(true)
  input.remove()
})
