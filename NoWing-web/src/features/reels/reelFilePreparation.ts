const videoReadTimeout = 12000

export function safeName(name: string): string {
  return name.toLowerCase().replace(/[^a-z0-9._-]/g, '_')
}

export function formatSize(bytes: number): string {
  return Number.isFinite(bytes) && bytes > 0 ? `${(bytes / (1024 * 1024)).toFixed(2)} MB` : '0 MB'
}

export function fileExtension(file: File, fallback: string): string {
  const index = file.name.lastIndexOf('.')
  return index > 0 && index < file.name.length - 1 ? file.name.slice(index).toLowerCase() : fallback
}

export function formatSecondsClock(totalSeconds: number): string {
  const seconds = Number.isFinite(totalSeconds) ? Math.max(0, Math.floor(totalSeconds)) : 0
  return `${String(Math.floor(seconds / 60)).padStart(2, '0')}:${String(seconds % 60).padStart(2, '0')}`
}

export function toRoundedDuration(value: number): number {
  return Number.isFinite(value) && value > 0 ? Math.max(1, Math.round(value)) : 0
}

function readVideo<T>(
  file: File,
  preload: 'auto' | 'metadata',
  listen: (video: HTMLVideoElement, resolve: (value: T) => void, reject: (error: unknown) => void) => void,
): Promise<T> {
  return new Promise((resolve, reject) => {
    if (typeof document === 'undefined') {
      reject(new Error('No se puede preparar el video en este entorno.'))
      return
    }
    const video = document.createElement('video')
    const url = URL.createObjectURL(file)
    let settled = false
    const timeout = setTimeout(() => fail(new Error('El video tardó demasiado en cargarse.')), videoReadTimeout)
    function cleanup() {
      if (settled) return false
      settled = true
      clearTimeout(timeout)
      video.onerror = video.onloadedmetadata = video.onloadeddata = video.onseeked = null
      video.removeAttribute('src')
      URL.revokeObjectURL(url)
      return true
    }
    function fail(error: unknown) {
      if (cleanup()) reject(error)
    }
    video.preload = preload
    video.muted = true
    video.playsInline = true
    video.onerror = () => fail(new Error('No se pudo leer el video.'))
    try {
      listen(video, value => { if (cleanup()) resolve(value) }, fail)
      video.src = url
      video.load()
    } catch (error) {
      fail(error)
    }
  })
}

export async function readVideoDurationSeconds(file: File): Promise<number> {
  try {
    return await readVideo<number>(file, 'metadata', (video, resolve) => {
      video.onloadedmetadata = () => resolve(toRoundedDuration(video.duration))
    })
  } catch {
    return 0
  }
}

function captureFrame<T>(
  file: File,
  second: number,
  output: (canvas: HTMLCanvasElement) => Promise<T> | T,
  targetWidth?: number,
): Promise<T> {
  return readVideo(file, 'auto', (video, resolve, reject) => {
    let waitingForSeek = false
    let rendered = false
    function draw() {
      if (waitingForSeek || rendered || video.readyState < 2) return
      rendered = true
      try {
        const sourceWidth = video.videoWidth || targetWidth || 720
        const sourceHeight = video.videoHeight || (targetWidth ? Math.round(targetWidth * 1.6) : 1280)
        const canvas = document.createElement('canvas')
        canvas.width = targetWidth ? Math.max(80, targetWidth) : sourceWidth
        canvas.height = targetWidth ? Math.max(80, Math.round(sourceHeight * canvas.width / sourceWidth)) : sourceHeight
        const context = canvas.getContext('2d')
        if (!context) throw new Error('No se pudo renderizar el fotograma.')
        context.drawImage(video, 0, 0, canvas.width, canvas.height)
        Promise.resolve(output(canvas)).then(resolve, reject)
      } catch (error) {
        reject(error)
      }
    }
    video.onloadedmetadata = () => {
      const duration = video.duration
      const requested = Number.isFinite(second) ? Math.max(0, second) : 0
      const position = Number.isFinite(duration) && duration > 0
        ? Math.min(requested, Math.max(0, duration - 0.05))
        : requested
      waitingForSeek = video.currentTime !== position
      try {
        if (waitingForSeek) video.currentTime = position
        else draw()
      } catch (error) {
        reject(error)
      }
    }
    video.onloadeddata = draw
    video.onseeked = () => {
      waitingForSeek = false
      draw()
    }
  })
}

export function captureVideoFrameAsFile(file: File, second: number): Promise<File> {
  return captureFrame(file, second, canvas => new Promise<File>((resolve, reject) => {
    canvas.toBlob(blob => {
      if (!blob) {
        reject(new Error('No se pudo generar portada desde el video.'))
        return
      }
      const extensionAt = file.name.lastIndexOf('.')
      const base = extensionAt > 0 ? file.name.slice(0, extensionAt) : file.name
      resolve(new File([blob], `${safeName(base) || 'cover'}_frame.jpg`, { type: 'image/jpeg' }))
    }, 'image/jpeg', 0.92)
  }))
}

export function captureVideoFrameDataUrl(file: File, second: number, targetWidth = 220, quality = 0.72): Promise<string> {
  return captureFrame(file, second, canvas => canvas.toDataURL('image/jpeg', quality), targetWidth)
}
