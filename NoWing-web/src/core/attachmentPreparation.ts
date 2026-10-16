import { zip, type Zippable } from 'fflate'

class AttachmentPreparationError extends Error {}
export function attachmentPreparationError(error: unknown) { return error instanceof AttachmentPreparationError ? error.message : 'No se pudieron preparar los archivos. Inténtalo de nuevo.' }

const MAX_BYTES = 128 * 1024 * 1024
const MAX_FILES = 500
type Entry = { name: string; isDirectory: boolean; isFile: boolean; file?: (ok: (file: File) => void, fail: (error: Error) => void) => void; createReader?: () => { readEntries: (ok: (entries: Entry[]) => void, fail: (error: Error) => void) => void } }
function safeName(name: string) {
  if (!name || name === '.' || name === '..' || name.includes('\\') || name.includes('/') || name.split('').some(char => char.charCodeAt(0) < 32)) throw new AttachmentPreparationError('La carpeta contiene un nombre de archivo no válido.')
  return name
}
export async function compressFiles(files: Array<{ path: string; file: File | null }>, name: string) {
  if (files.length > MAX_FILES || files.reduce((sum, value) => sum + (value.file?.size || 0), 0) > MAX_BYTES) throw new AttachmentPreparationError('Selecciona hasta 500 archivos y 128 MB por carpeta.')
  const entries: Zippable = Object.create(null)
  for (const item of files) {
    item.path.replace(/\/$/,'').split('/').forEach(safeName)
    if (Object.prototype.hasOwnProperty.call(entries, item.path)) throw new AttachmentPreparationError('Hay archivos con el mismo nombre en la carpeta.')
    entries[item.path] = [item.file ? new Uint8Array(await item.file.arrayBuffer()) : new Uint8Array(), { level: /\.(zip|gz|7z|rar|docx|xlsx|pptx|jpe?g|png|webp|gif|mp4|mp3|m4a|pdf)$/i.test(item.path) ? 0 : 6 }]
  }
  const packed = await new Promise<Uint8Array>((resolve, reject) => zip(entries, { level: 6 }, (error, data) => error ? reject(error) : resolve(data)))
  if (packed.length > MAX_BYTES) throw new AttachmentPreparationError('La carpeta comprimida supera los 128 MB. Divídela en carpetas más pequeñas.')
  return new File([packed as Uint8Array<ArrayBuffer>], `${safeName(name).replace(/\.zip$/i, '')}.zip`, { type: 'application/zip' })
}
export async function prepareAttachmentFiles(files: File[]) {
  return Promise.all(files.map(async file => {
    if (file.size > MAX_BYTES) throw new AttachmentPreparationError('El archivo supera los 128 MB. Divídelo antes de enviarlo.')
    if (/\.zip$/i.test(file.name) && file.type !== 'application/zip') return new File([file], file.name, {type:'application/zip',lastModified:file.lastModified})
    const directExtension = /\.(pdf|zip|docx|xlsx|pptx|txt|csv|jpe?g|png|webp|gif|mp4|webm|mp3|m4a|aac|wav|ogg|oga|opus|flac)$/i.test(file.name)
    const directMediaType = /^(image\/(jpeg|png|webp|gif)|video\/(mp4|webm)|audio\/(mpeg|mp4|aac|wav|x-wav|ogg|opus|webm|flac|x-flac)|application\/ogg)$/.test(file.type || '')
    // Keep unsupported documents intact inside a ZIP accepted by the message API.
    if (!directExtension && !directMediaType) return compressFiles([{path:file.name,file}], file.name)
    if (file.size < 8 * 1024 * 1024 || !/\.(txt|csv|json|xml|log|md|html|doc|xls|ppt)$/i.test(file.name)) return file
    const packed = await compressFiles([{ path: file.name, file }], file.name)
    return packed.size < file.size ? packed : file
  }))
}
/** Snapshot the granted drop entries synchronously; browsers invalidate DataTransfer after the event. */
export function droppedAttachments(transfer: DataTransfer): Promise<File[]> {
  const roots = Array.from(transfer.items || []).filter(item => item.kind === 'file').map(item => item.webkitGetAsEntry?.() as Entry | null)
  const plain = Array.from(transfer.files || [])
  if (!roots.some(entry => entry?.isDirectory)) return prepareAttachmentFiles(plain)
  return (async () => {
    const output: File[] = []; let bytes = 0, count = 0
    for (const root of roots) {
      if (!root) continue
      const files: Array<{ path: string; file: File | null }> = []
      async function visit(entry: Entry, prefix: string, depth: number): Promise<void> {
        if (depth > 16 || ++count > MAX_FILES) throw new AttachmentPreparationError('La carpeta es demasiado grande o contiene demasiadas subcarpetas.')
        const path = prefix + safeName(entry.name)
        if (entry.isFile && entry.file) {
          const file = await new Promise<File>((resolve, reject) => entry.file!(resolve, reject)); bytes += file.size
          if (bytes > MAX_BYTES) throw new AttachmentPreparationError('Selecciona hasta 128 MB por envío de carpetas.')
          files.push({ path, file }); return
        }
        if (entry.isDirectory && entry.createReader) {
          files.push({ path: path + '/', file: null })
          const reader = entry.createReader()
          for (;;) {
            const batch = await new Promise<Entry[]>((resolve, reject) => reader.readEntries(resolve, reject))
            if (!batch.length) break
            for (const child of batch) await visit(child, path + '/', depth + 1)
          }
        }
      }
      await visit(root, '', 0)
      if (root.isDirectory) output.push(await compressFiles(files, root.name))
      else output.push(...await prepareAttachmentFiles(files.flatMap(item => item.file ? [item.file] : [])))
    }
    return output
  })()
}
