export const MAX_MESSAGE_VIDEO_SECONDS = 600

export function assertMessageVideoDuration(duration: number) {
  if (!Number.isFinite(duration) || duration <= 0) throw new Error('No se pudo leer la duración del video. Elige otro archivo.')
  if (duration > MAX_MESSAGE_VIDEO_SECONDS + 0.25) throw new Error('Los videos de mensajes pueden durar hasta 10 minutos.')
}

export function validateMessageVideo(file: File): Promise<void> {
  return new Promise((resolve, reject) => {
    const video = document.createElement('video'), url = URL.createObjectURL(file)
    const finish = (error?: Error) => {
      clearTimeout(timer); video.onloadedmetadata = video.onerror = null
      video.removeAttribute('src'); video.load(); URL.revokeObjectURL(url)
      if (error) reject(error); else resolve()
    }
    const timer = setTimeout(() => finish(new Error('No se pudo leer la duración del video. Elige otro archivo.')), 15000)
    video.preload = 'metadata'
    video.onloadedmetadata = () => { try { assertMessageVideoDuration(video.duration); finish() } catch (error) { finish(error as Error) } }
    video.onerror = () => finish(new Error('No se pudo abrir el video. Elige un archivo MP4 o WebM compatible.'))
    video.src = url
  })
}
