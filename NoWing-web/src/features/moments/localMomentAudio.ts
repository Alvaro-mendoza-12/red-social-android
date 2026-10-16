export function localAudioDuration(file: File, signal?: AbortSignal): Promise<number> {
  return new Promise((resolve, reject) => {
    const element = document.createElement('audio'), source = URL.createObjectURL(file)
    let finished = false
    const cancel = () => finish(undefined, true)
    const finish = (duration?: number, aborted = false) => {
      if (finished) return
      finished = true; clearTimeout(timer); signal?.removeEventListener('abort', cancel)
      element.onloadedmetadata = element.onerror = null; element.removeAttribute('src'); element.load(); URL.revokeObjectURL(source)
      if (aborted) reject(new DOMException('Lectura cancelada', 'AbortError'))
      else if (duration) resolve(duration)
      else reject(new Error('No se pudo leer la duración del audio. Prueba con otro archivo.'))
    }
    const timer = window.setTimeout(() => finish(), 10000)
    if (signal?.aborted) { cancel(); return }
    signal?.addEventListener('abort', cancel, { once: true })
    element.preload = 'metadata'
    element.onloadedmetadata = () => finish(Number.isFinite(element.duration) && element.duration >= 6 ? Math.round(element.duration * 1000) : undefined)
    element.onerror = () => finish()
    element.src = source
  })
}
