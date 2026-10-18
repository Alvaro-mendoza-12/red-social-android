import { attachmentPreparationError, compressFiles } from './attachmentPreparation'

self.onmessage = (event: MessageEvent<{ files: Array<{ path: string; file: File | null }>; name: string }>) => {
  void compressFiles(event.data.files, event.data.name, progress => self.postMessage({ progress }), false)
    .then(file => self.postMessage({ file }))
    .catch(error => self.postMessage({ error: attachmentPreparationError(error) }))
}
