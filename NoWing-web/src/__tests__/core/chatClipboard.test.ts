import { normalizeClipboardFiles, clipboardFilesFrom, clipboardMayContainImage, clipboardHasStickerHint, readClipboardImages, pastedAsSticker, canPasteIntoChat, isStickerImage, isCopiedStickerImage, isEmptyClipboardPaste } from '../../core/chatClipboard'

beforeAll(() => {
 for (const [method, read] of [['arrayBuffer', 'readAsArrayBuffer'], ['text', 'readAsText']] as const) {
  Object.defineProperty(Blob.prototype, method, {configurable:true,value:function(this: Blob) {
   return new Promise((resolve,reject) => {const reader=new FileReader();reader.onload=()=>resolve(reader.result);reader.onerror=reject;reader[read](this)})
  }})
 }
})

test('prefers original animated sticker bytes over a clipboard PNG preview', async () => {
 const png = new File(['preview'], 'image.png', {type:'image/png'})
 const data = {files:[png],items:[],types:['Files','text/html'],getData:()=>'<img alt="Sticker">'} as unknown as DataTransfer
 const getType = jest.fn(async (type:string)=>new Blob([type==='image/webp'?'RIFF0000WEBPanimated':'preview'],{type}))
 const clipboard = {read:async()=>[{types:['image/png','image/webp'],getType}]} as unknown as Clipboard
 const files = await readClipboardImages(data,clipboard)
 expect(files).toHaveLength(1)
 expect(files[0].type).toBe('image/webp')
 expect(getType).toHaveBeenCalledTimes(2)
 expect(getType).toHaveBeenCalledWith('image/webp')
 expect(files[0].size).toBe(20)
})

test('a denied clipboard permission keeps the paste-event image available', async () => {
 const png = new File(['preview'],'image.png',{type:'image/png'})
 const data = {files:[png],items:[],types:['Files','text/html'],getData:()=>'<img alt="Sticker">'} as unknown as DataTransfer
 const clipboard = {read:async()=>{throw new Error('denied')}} as unknown as Clipboard
 expect(await readClipboardImages(data,clipboard)).toEqual([png])
})

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
test('does not import an unidentifiable async image from an older system clipboard', async () => {
  const getType = jest.fn(async () => new Blob(['sticker'], { type: 'image/webp' }))
  const clipboard = { read: async () => [{ types: ['image/webp'], getType }] } as unknown as Clipboard
  const data = { files: [], items: [], types: ['image/webp'], getData: () => '' } as unknown as DataTransfer
  const files = await readClipboardImages(data, clipboard)
  expect(getType).not.toHaveBeenCalled()
  expect(files).toEqual([])
  const textOnly = { files: [], items: [], types: ['text/plain'], getData: () => 'hola' } as unknown as DataTransfer
  expect(clipboardMayContainImage(textOnly)).toBe(false)
  expect(await readClipboardImages(textOnly, clipboard)).toEqual([])
})

test('keeps the event image when the asynchronous clipboard changed after paste', async () => {
 const png=new File(['current'],'image.png',{type:'image/png'})
 const data={files:[png],items:[],types:['Files','text/html'],getData:()=>'<img alt="Sticker">'} as unknown as DataTransfer
 const read=jest.fn(async()=>[{types:['image/png','web image/gif'],getType:async(type:string)=>new Blob([type==='image/png'?'previous':'GIF89a-old'],{type})}])
 expect(await readClipboardImages(data,{read} as unknown as Clipboard)).toEqual([png])
 expect(read).toHaveBeenCalledTimes(1)
})

test('HTML-only sticker paste never recovers an unrelated previous photograph', async () => {
 const data={files:[],items:[],types:['text/html'],getData:(type:string)=>type==='text/html'?'<img alt="Sticker" src="blob:https://web.whatsapp.com/new">':''} as unknown as DataTransfer
 const getType=jest.fn(async()=>new Blob(['old photo'],{type:'image/png'}))
 expect(await readClipboardImages(data,{read:async()=>[{types:['image/png'],getType}]} as unknown as Clipboard)).toEqual([])
 expect(getType).not.toHaveBeenCalled()
})

test('keeps an original GIF embedded beside the same paste event PNG', async () => {
 const png=new File(['preview'],'image.png',{type:'image/png'})
 const gif='R0lGODlh'
 const data={files:[png],items:[],types:['Files','text/html'],getData:(type:string)=>type==='text/html'?`<img alt="sticker" src="data:image/gif;base64,${gif}">`:''} as unknown as DataTransfer
 const read=jest.fn()
 const files=await readClipboardImages(data,{read} as unknown as Clipboard)
 expect(files).toHaveLength(1)
 expect(files[0].type).toBe('image/gif')
 expect(await files[0].text()).toBe('GIF89a')
 expect(read).not.toHaveBeenCalled()
})

test('reads custom WebP representations only from the item matching the current PNG', async () => {
 const png=new File(['preview'],'image.png',{type:'image/png'})
 const data={files:[png],items:[],types:['Files','text/html'],getData:()=>'<img alt="Sticker">'} as unknown as DataTransfer
 const getType=jest.fn(async(type:string)=>new Blob([type==='image/png'?'preview':'RIFF0000WEBPanimation'],{type}))
 const files=await readClipboardImages(data,{read:async()=>[{types:['image/png','web image/webp'],getType}]} as unknown as Clipboard)
 expect(files[0].type).toBe('image/webp')
 expect(getType).toHaveBeenCalledWith('web image/webp')
 expect(await files[0].text()).toBe('RIFF0000WEBPanimation')
})

test('captures HTML and starts async read before yielding from the paste handler', async () => {
 let active=true
 const html='<img alt="Sticker" src="blob:https://web.whatsapp.com/current">'
 const data={files:[],items:[],types:['text/html'],getData:(type:string)=>active&&type==='text/html'?html:''} as unknown as DataTransfer
 const read=jest.fn(async()=>{expect(active).toBe(true);return [{types:['text/html','web image/gif'],getType:async(type:string)=>new Blob([type==='text/html'?html:'GIF89a-animation'],{type})}]})
 const pending=readClipboardImages(data,{read} as unknown as Clipboard)
 active=false
 const files=await pending
 expect(files[0].type).toBe('image/gif')
})

test('one unsupported original representation does not discard the matching fallback', async () => {
 const png=new File(['preview'],'image.png',{type:'image/png'})
 const data={files:[png],items:[],types:['Files','text/html'],getData:()=>'<img alt="Sticker">'} as unknown as DataTransfer
 const getType=async(type:string)=>{if(type==='image/gif')throw Error('unsupported');return new Blob([type==='image/png'?'preview':'RIFF0000WEBPanimation'],{type})}
 const files=await readClipboardImages(data,{read:async()=>[{types:['image/png','image/gif','web image/webp'],getType}]} as unknown as Clipboard)
 expect(files[0].type).toBe('image/webp')
})

test('keeps multiple event files together without replacing them with one async image', async () => {
 const files=[new File(['first'],'one.png',{type:'image/png'}),new File(['second'],'two.png',{type:'image/png'})]
 const data={files,items:[],types:['Files','text/html'],getData:()=>'<img alt="Sticker">'} as unknown as DataTransfer
 const read=jest.fn()
 expect(await readClipboardImages(data,{read} as unknown as Clipboard)).toEqual(files)
 expect(read).not.toHaveBeenCalled()
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

test('recognizes extensionless WhatsApp sticker files from their bytes', async () => {
  const file = new File(['image'], 'WhatsApp Sticker 2026-10-17 at 4.56.51 PM', { type: 'application/octet-stream' })
  const bytes = Uint8Array.from([82, 73, 70, 70, 20, 0, 0, 0, 87, 69, 66, 80, 86, 80, 56, 32])
  jest.spyOn(file, 'slice').mockReturnValue({ arrayBuffer: async () => bytes.buffer } as Blob)
  const [normalized] = await normalizeClipboardFiles([file])
  expect(normalized.type).toBe('image/webp')
  expect(normalized.name).toBe(`${file.name}.webp`)
  expect(pastedAsSticker(normalized, false)).toBe(true)
  const data = { files: [file], items: [], types: ['Files'] } as unknown as DataTransfer
  expect((await readClipboardImages(data, undefined))[0].type).toBe('image/webp')
})

test('does not rename an ordinary document with an unknown type', async () => {
  const document = new File(['text'], 'notas', { type: 'application/octet-stream' })
  expect(await normalizeClipboardFiles([document])).toEqual([document])
})

test('identifies Windows virtual-file paste without mistaking text or real files for empty data',()=>{
 expect(isEmptyClipboardPaste({types:[],files:[],items:[]} as unknown as DataTransfer)).toBe(true)
 expect(isEmptyClipboardPaste({types:['text/plain'],files:[],items:[]} as unknown as DataTransfer)).toBe(false)
 expect(isEmptyClipboardPaste({types:[],files:[new File(['gif'],'sticker')],items:[]} as unknown as DataTransfer)).toBe(false)
 expect(isEmptyClipboardPaste(null)).toBe(false)
})
