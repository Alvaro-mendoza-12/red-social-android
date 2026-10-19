import { useEffect, useRef, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { QrCode } from 'lucide-react'
import { collectionInvitationPath } from '../core/collectionInvitation'
import { friendlyError } from '../core/utils'

export function CollectionQrReader() {
  const [open, setOpen] = useState(false)
  return <><button className="icon-btn" type="button" aria-label="Escanear QR de grupo o comunidad" title="Escanear invitación" onClick={() => setOpen(true)}><QrCode size={22} /></button>{open ? <ReaderDialog onClose={() => setOpen(false)} /> : null}</>
}
function ReaderDialog({ onClose }: { onClose: () => void }) {
  const navigate = useNavigate(), video = useRef<HTMLVideoElement>(null), fileInput = useRef<HTMLInputElement>(null)
  const [camera, setCamera] = useState(false), [busy, setBusy] = useState(false), [error, setError] = useState(''), [torch, setTorch] = useState(false), [hasTorch, setHasTorch] = useState(false)
  const stream = useRef<MediaStream | null>(null), active = useRef(true)
  const onFound = useRef((raw: string) => { try { navigate(collectionInvitationPath(raw, window.location.origin)); onClose() } catch (caught) { setError(friendlyError(caught, 'No se pudo leer la invitación.')) } })
  useEffect(() => { active.current = true; return () => { active.current = false } }, [])
  useEffect(() => {
    if (!camera) return
    let cancelled = false, timer: ReturnType<typeof setTimeout> | undefined
    let local: MediaStream | null = null
    setBusy(true); setError('')
    void (async () => {
      try {
        const jsQR = (await import('jsqr')).default
        local = await navigator.mediaDevices.getUserMedia({ audio: false, video: { facingMode: { ideal: 'environment' }, width: { ideal: 720 }, height: { ideal: 720 } } })
        if (cancelled) { local.getTracks().forEach(track => track.stop()); return }
        stream.current = local
        setHasTorch(Boolean((local.getVideoTracks()[0]?.getCapabilities() as MediaTrackCapabilities & { torch?: boolean })?.torch))
        if (!video.current) return
        video.current.srcObject = local; await video.current.play(); setBusy(false)
        const canvas = document.createElement('canvas'), context = canvas.getContext('2d', { willReadFrequently: true })
        const read = () => {
          if (cancelled || !context) return
          const element = video.current
          if (element?.videoWidth && !document.hidden) {
            const scale = Math.min(1, 720 / Math.max(element.videoWidth, element.videoHeight))
            canvas.width = Math.round(element.videoWidth * scale); canvas.height = Math.round(element.videoHeight * scale)
            context.drawImage(element, 0, 0, canvas.width, canvas.height)
            const pixels = context.getImageData(0, 0, canvas.width, canvas.height)
            const code = jsQR(pixels.data, pixels.width, pixels.height, { inversionAttempts: 'dontInvert' })
            if (code) { setCamera(false); onFound.current(code.data); return }
          }
          timer = setTimeout(read, 240)
        }
        read()
      } catch { if (!cancelled) { setBusy(false); setCamera(false); setError('No se pudo abrir la cámara. Permite su acceso o elige una imagen del QR.') } }
    })()
    const hide = () => { if (document.hidden) setCamera(false) }
    document.addEventListener('visibilitychange', hide)
    return () => { cancelled = true; clearTimeout(timer); local?.getTracks().forEach(track => track.stop()); stream.current = null; document.removeEventListener('visibilitychange', hide) }
  }, [camera])
  async function readImage(file: File) {
    setCamera(false); setBusy(true); setError('')
    let bitmap: ImageBitmap | null = null
    try {
      if (!file.type.startsWith('image/') || file.size > 20 * 1024 * 1024) throw new Error('Elige una imagen de menos de 20 MB.')
      bitmap = await createImageBitmap(file)
      if (bitmap.width * bitmap.height > 64_000_000) throw new Error('La imagen es demasiado grande. Recorta el área del QR e inténtalo de nuevo.')
      const scale = Math.min(1, 1800 / Math.max(bitmap.width, bitmap.height)), canvas = document.createElement('canvas')
      canvas.width = Math.max(1, Math.round(bitmap.width * scale)); canvas.height = Math.max(1, Math.round(bitmap.height * scale))
      const context = canvas.getContext('2d', { willReadFrequently: true })
      if (!context) throw new Error('No se pudo abrir la imagen.')
      context.drawImage(bitmap, 0, 0, canvas.width, canvas.height)
      const pixels = context.getImageData(0, 0, canvas.width, canvas.height), jsQR = (await import('jsqr')).default
      const code = jsQR(pixels.data, pixels.width, pixels.height)
      if (!code) throw new Error('No encontramos un QR legible. Usa la imagen completa y nítida.')
      if (active.current) onFound.current(code.data)
    } catch (caught) { if (active.current) setError(friendlyError(caught, 'No se pudo leer ese QR.')) }
    finally { bitmap?.close(); if (active.current) setBusy(false) }
  }
  async function toggleTorch() {
    try { await stream.current?.getVideoTracks()[0]?.applyConstraints({ advanced: [{ torch: !torch } as MediaTrackConstraintSet] }); setTorch(value => !value) }
    catch { setError('Esta cámara no permite controlar la linterna desde el navegador.') }
  }
  return <div className="app-dialog-backdrop" onMouseDown={onClose}><section className="surface-card collection-info" role="dialog" aria-modal="true" aria-label="Escanear invitación" onMouseDown={event => event.stopPropagation()}>
    <header className="row-between"><h2>Escanear invitación</h2><button className="icon-btn" type="button" aria-label="Cerrar lector QR" onClick={onClose}>×</button></header>
    <p>Usa la cámara o una imagen guardada del código. La invitación se abrirá antes de unirte.</p>
    {camera ? <video ref={video} autoPlay muted playsInline aria-label="Vista de la cámara" style={{ width: '100%', maxHeight: 360, objectFit: 'contain', borderRadius: 16, background: '#000' }} /> : null}
    <div className="row-actions"><button className="primary-btn" type="button" onClick={() => { setTorch(false); setCamera(value => !value) }}>{camera ? 'Detener cámara' : 'Usar cámara'}</button>
      <button className="ghost-btn" type="button" disabled={busy} onClick={() => fileInput.current?.click()}>Elegir imagen</button>
      {camera && hasTorch ? <button className="ghost-btn" type="button" onClick={() => void toggleTorch()}>{torch ? 'Apagar linterna' : 'Encender linterna'}</button> : null}</div>
    <input type="file" ref={fileInput} accept="image/*" hidden onChange={event => { const file = event.target.files?.[0]; event.target.value = ''; if (file) void readImage(file) }} />
    {busy ? <p role="status">Preparando lector…</p> : null}{error ? <p role="alert" className="error-text">{error}</p> : null}
  </section></div>
}
