import { documentContentType, messageDocumentTypes, normalizeDocumentBlob, normalizeDocumentFile } from '../../core/documentMime'
const bytes = (blob: Blob) => new Promise<string>((resolve, reject) => { const reader = new FileReader(); reader.onload = () => resolve(String(reader.result)); reader.onerror = reject; reader.readAsDataURL(blob) })

test.each(Object.entries(messageDocumentTypes))('normalizes %s metadata while preserving filename, bytes and modified date', async (extension, type) => {
 const original = new File([new Uint8Array([0, 255, 13, 10, 80, 75])], `Prueba Ã¡.${extension.toUpperCase()}`, {type:'application/octet-stream',lastModified:123})
 const result = normalizeDocumentFile(original)
 expect(result.type).toBe(type); expect(result.name).toBe(original.name); expect(result.lastModified).toBe(123)
 expect((await bytes(result)).split(',')[1]).toBe((await bytes(original)).split(',')[1])
})
test('repairs an old queued PowerPoint blob with a legacy browser association', async () => {
 const stored = new Blob(['original presentation bytes'], {type:'application/vnd.ms-powerpoint'})
 const result = normalizeDocumentBlob(stored, 'presentaciÃ³n.pptx')
 expect(result.type).toBe('application/vnd.openxmlformats-officedocument.presentationml.presentation')
 expect((await bytes(result)).split(',')[1]).toBe((await bytes(stored)).split(',')[1])
})
test('keeps unsupported files and already correct blobs intact', () => {
 const media = new File(['original'], 'image.png', {type:'image/png'})
 expect(normalizeDocumentFile(media)).toBe(media)
 expect(normalizeDocumentBlob(media,'data.unknown')).toBe(media)
 expect(documentContentType('archive.unknown')).toBeUndefined()
 expect(documentContentType('archive.__proto__')).toBeUndefined()
})
