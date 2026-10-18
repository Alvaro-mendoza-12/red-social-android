import { Zip, ZipDeflate, ZipPassThrough } from 'fflate'
import { validateMessageVideo } from './messageVideoPolicy'

class AttachmentPreparationError extends Error {}
export function attachmentPreparationError(error: unknown) { return error instanceof AttachmentPreparationError ? error.message : 'No se pudieron preparar los archivos. Inténtalo de nuevo.' }

export const MAX_MESSAGE_UPLOAD_BYTES = 500 * 1024 * 1024
export const FOLDER_PART_SOURCE_BYTES = 450 * 1024 * 1024
const MAX_FILES = 5000
const MAX_FOLDER_ENTRIES = 100000
export type PreparationProgress = (message: string) => void
const CHUNK_BYTES = 1024 * 1024
type Entry = { name: string; isDirectory: boolean; isFile: boolean; file?: (ok: (file: File) => void, fail: (error: Error) => void) => void; createReader?: () => { readEntries: (ok: (entries: Entry[]) => void, fail: (error: Error) => void) => void } }
const sizeLabel = (bytes: number) => bytes < 1024 * 1024 ? `${Math.ceil(bytes / 1024).toLocaleString('es')} KB` : `${(bytes / (1024 * 1024)).toLocaleString('es', { maximumFractionDigits: 1 })} MB`
function folderSummary(entries: Array<{ path: string; file: File | null }>) {
  return `${entries.filter(item => item.file).length.toLocaleString('es')} archivos · ${sizeLabel(entries.reduce((sum, item) => sum + (item.file?.size || 0), 0))} originales`
}
function archiveReady(files: File[], entries: Array<{ path: string; file: File | null }>, onProgress?: PreparationProgress) {
  onProgress?.(`${folderSummary(entries)} → ${files.length} ZIP${files.length === 1 ? '' : 's'} · ${sizeLabel(files.reduce((sum, file) => sum + file.size, 0))} · Listo para enviar`)
}
function safeName(name: string) {
  if (!name || name === '.' || name === '..' || name.includes('\\') || name.includes('/') || name.split('').some(char => char.charCodeAt(0) < 32)) throw new AttachmentPreparationError('La carpeta contiene un nombre de archivo no válido.')
  return name
}

/** Read bounded slices: never allocate every source file in the folder at once. */
export async function compressFiles(files: Array<{ path: string; file: File | null }>, name: string, onProgress?: PreparationProgress, useWorker = true): Promise<File> {
  if (files.length > MAX_FILES) throw new AttachmentPreparationError('Selecciona hasta 5000 archivos por carpeta.')
  const paths = new Set<string>()
  for (const item of files) {
    item.path.replace(/\/$/, '').split('/').forEach(safeName)
    if (paths.has(item.path)) throw new AttachmentPreparationError('Hay archivos con el mismo nombre en la carpeta.')
    paths.add(item.path)
    if ((item.file?.size || 0) > MAX_MESSAGE_UPLOAD_BYTES) throw new AttachmentPreparationError(`${folderSummary(files)}. «${item.path}» ocupa ${sizeLabel(item.file!.size)} y supera los 500 MB permitidos por archivo, también dentro de un ZIP. Divide ese archivo o exclúyelo para enviar la carpeta.`)
  }
  if (useWorker && typeof Worker !== 'undefined') {
    return await new Promise<File>((resolve, reject) => {
      const worker = new Worker(new URL('./folderZip.worker.ts', import.meta.url), { type: 'module' })
      worker.onmessage = (event: MessageEvent<{ progress?: string; file?: File; error?: string }>) => {
        if (event.data.progress) onProgress?.(event.data.progress)
        if (event.data.file) { worker.terminate(); resolve(event.data.file) }
        if (event.data.error) { worker.terminate(); reject(new AttachmentPreparationError(event.data.error)) }
      }
      worker.onerror = () => { worker.terminate(); reject(new AttachmentPreparationError('No se pudo comprimir la carpeta. Vuelve a seleccionarla para reintentar.')) }
      worker.postMessage({ files, name })
    })
  }
  const parts: Uint8Array<ArrayBuffer>[] = []
  let bytes = 0, failure: Error | null = null
  const archive = new Zip((error, data) => {
    if (error) { failure = error; return }
    bytes += data.length
    if (bytes > MAX_MESSAGE_UPLOAD_BYTES) { failure = new AttachmentPreparationError('El ZIP supera los 500 MB permitidos por archivo.'); return }
    parts.push(new Uint8Array(data))
  })
  try {
    let processed = 0, sourceBytes = 0, lastProgress = 0
    const totalBytes = files.reduce((sum, item) => sum + (item.file?.size || 0), 0)
    const progress = (path: string, force = false) => {
      if (!force && Date.now() - lastProgress < 150) return
      lastProgress = Date.now()
      const percent = totalBytes ? Math.floor(sourceBytes / totalBytes * 100) : Math.floor(processed / Math.max(1, files.length) * 100)
      onProgress?.(`Comprimiendo ${name}: ${percent}% · ${processed} de ${files.length} elementos · ${sizeLabel(sourceBytes)} de ${sizeLabel(totalBytes)} · ${path}`)
    }
    for (const item of files) {
      progress(item.path, processed === 0)
      const entry = !item.file || /\.(zip|gz|7z|rar|docx|xlsx|pptx|jpe?g|png|webp|gif|mp4|mp3|m4a|pdf)$/i.test(item.path)
        ? new ZipPassThrough(item.path) : new ZipDeflate(item.path, { level: 1 })
      archive.add(entry)
      if (!item.file || !item.file.size) entry.push(new Uint8Array(), true)
      else for (let offset = 0; offset < item.file.size; offset += CHUNK_BYTES) {
        const end = Math.min(item.file.size, offset + CHUNK_BYTES)
        entry.push(new Uint8Array(await item.file.slice(offset, end).arrayBuffer()), end === item.file.size)
        sourceBytes += end - offset
        progress(item.path)
        if (failure) throw failure
        // The worker can stream continuously. The compatibility path yields to UI.
        if (useWorker) await new Promise<void>(resolve => setTimeout(resolve, 0))
      }
      processed++
      if (failure) throw failure
    }
    archive.end()
    if (failure) throw failure
    progress('ZIP preparado', true)
    return new File(parts, `${safeName(name).replace(/\.zip$/i, '')}.zip`, { type: 'application/zip' })
  } catch (error) { archive.terminate(); throw error }
}

/** Each part is an ordinary ZIP, independently readable on Android and desktop. */
export function folderArchiveParts(files: Array<{ path: string; file: File | null }>) {
  const result: typeof files[] = []; let part: typeof files = [], bytes = 0
  for (const item of files) {
    const size = item.file?.size || 0
    if (size > MAX_MESSAGE_UPLOAD_BYTES) throw new AttachmentPreparationError(`${folderSummary(files)}. «${item.path}» ocupa ${sizeLabel(size)} y supera los 500 MB permitidos por archivo, también dentro de un ZIP. Divide ese archivo o exclúyelo para enviar la carpeta.`)
    if (part.length && (bytes + size > FOLDER_PART_SOURCE_BYTES || part.length >= MAX_FILES)) { result.push(part); part = []; bytes = 0 }
    part.push(item); bytes += size
  }
  if (part.length) result.push(part)
  return result
}

export async function prepareSelectedFolder(files: File[], onProgress?: PreparationProgress) {
  if (files.length > MAX_FOLDER_ENTRIES) throw new AttachmentPreparationError('La carpeta supera los 100 000 elementos. Divide la carpeta o adjunta un ZIP ya creado.')
  onProgress?.(`Preparando carpeta: ${files.length} archivos`)
  const entries=files.map(file=>({path:file.webkitRelativePath,file}))
  const root=entries[0]?.path.split('/')[0] || 'Carpeta'
  if(entries.some(item=>!item.path||item.path.split('/')[0]!==root||item.path.split('/').length>33)) throw new AttachmentPreparationError('Selecciona una carpeta con hasta 32 niveles de subcarpetas.')
  const parts=folderArchiveParts(entries),output:File[]=[]
  for(let i=0;i<parts.length;i++) {
    const label=root+(parts.length>1?`-parte-${i+1}-de-${parts.length}`:'')
    output.push(await compressFiles(parts[i],label,onProgress))
  }
  archiveReady(output, entries, onProgress)
  return output
}

export async function prepareAttachmentFiles(files: File[], onProgress?: PreparationProgress) {
  if(files.some(file=>file.webkitRelativePath)) return prepareSelectedFolder(files,onProgress)
  if (files.length > MAX_FILES) throw new AttachmentPreparationError('Selecciona hasta 5000 archivos sueltos o adjunta la carpeta completa para dividirla en ZIPs.')
  const prepared: File[] = []
  for (const file of files) {
    if (file.size > MAX_MESSAGE_UPLOAD_BYTES) throw new AttachmentPreparationError('El archivo supera los 500 MB permitidos. Selecciona uno más pequeño.')
    if ((/^(image|audio)\//.test(file.type) || /\.(jpe?g|png|webp|gif|mp3|m4a|aac|wav|ogg|oga|opus|flac)$/i.test(file.name)) && file.size > 60 * 1024 * 1024) throw new AttachmentPreparationError('La imagen o el audio supera los 60 MB permitidos por mensaje.')
    if ((file.type || '').startsWith('video/') || /\.(mp4|webm)$/i.test(file.name)) {
      try { await validateMessageVideo(file) } catch (error) { throw new AttachmentPreparationError((error as Error).message) }
    }
    if (/\.zip$/i.test(file.name) && file.type !== 'application/zip') { prepared.push(new File([file], file.name, { type: 'application/zip', lastModified: file.lastModified })); continue }
    const directExtension = /\.(pdf|zip|docx|xlsx|pptx|txt|csv|jpe?g|png|webp|gif|mp4|webm|mp3|m4a|aac|wav|ogg|oga|opus|flac)$/i.test(file.name)
    const directMediaType = /^(image\/(jpeg|png|webp|gif)|video\/(mp4|webm)|audio\/(mpeg|mp4|aac|wav|x-wav|ogg|opus|webm|flac|x-flac)|application\/ogg)$/.test(file.type || '')
    if (!directExtension && !directMediaType) { prepared.push(await compressFiles([{path:file.name,file}], file.name)); continue }
    if (file.size < 8 * 1024 * 1024 || !/\.(txt|csv)$/i.test(file.name)) { prepared.push(file); continue }
    const packed = await compressFiles([{ path: file.name, file }], file.name)
    prepared.push(packed.size < file.size ? packed : file)
  }
  return prepared
}

/** Snapshot the granted drop entries synchronously; browsers invalidate DataTransfer after the event. */
export function droppedAttachments(transfer: DataTransfer, onProgress?: PreparationProgress): Promise<File[]> {
  const roots = Array.from(transfer.items || []).filter(item => item.kind === 'file').map(item => {
    const entry = item.webkitGetAsEntry?.() as Entry | null
    if (entry) return entry
    const file = item.getAsFile?.()
    return file ? { name: file.name, isDirectory: false, isFile: true, file: (accept: (value: File) => void) => accept(file) } satisfies Entry : null
  })
  const plain = Array.from(transfer.files || [])
  if (!roots.some(entry => entry?.isDirectory)) return prepareAttachmentFiles(plain,onProgress)
  return (async () => {
    const files: Array<{ path: string; file: File | null }> = []; let count = 0
    for (const root of roots) {
      if (!root) continue
      async function visit(entry: Entry, prefix: string, depth: number): Promise<void> {
        if (depth > 32) throw new AttachmentPreparationError('La carpeta supera los 32 niveles de subcarpetas. Puedes adjuntar un ZIP ya creado.')
        if (++count > MAX_FOLDER_ENTRIES) throw new AttachmentPreparationError('La carpeta supera los 100 000 elementos. Divide la carpeta o adjunta un ZIP ya creado.')
        if(count % 25 === 0) onProgress?.(`Leyendo carpeta: ${count} elementos encontrados`)
        const path = prefix + safeName(entry.name)
        if (entry.isFile && entry.file) {
          const file = await new Promise<File>((resolve, reject) => entry.file!(resolve, reject))
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
    }
    onProgress?.(`Carpeta leída: ${folderSummary(files)}. Preparando ZIP…`)
    const parts = folderArchiveParts(files), output: File[] = []
    const label = roots.length === 1 ? roots[0]!.name : 'Carpetas'
    for (let i = 0; i < parts.length; i++) output.push(await compressFiles(parts[i], label + (parts.length > 1 ? `-parte-${i + 1}-de-${parts.length}` : ''), onProgress))
    archiveReady(output, files, onProgress)
    return output
  })()
}
