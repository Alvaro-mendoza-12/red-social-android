import { useCallback, useEffect, useRef, useState } from 'react'
import { friendlyError } from './utils'

export const COMMENT_AUDIO_MAX_BYTES = 10 * 1024 * 1024
export const COMMENT_AUDIO_MAX_MS = 120_000
type Phase = 'idle' | 'requesting' | 'recording' | 'finishing'
type Session = {
  owner: string; media?: MediaStream; recorder?: MediaRecorder; chunks: Blob[]; bytes: number
  timer?: ReturnType<typeof setTimeout>; ticker?: ReturnType<typeof setInterval>
}

function release(session: Session) {
  clearTimeout(session.timer); clearInterval(session.ticker)
  if (session.recorder) {
    session.recorder.onstop = session.recorder.ondataavailable = session.recorder.onerror = null
    try { if (session.recorder.state !== 'inactive') session.recorder.stop() } catch { /* Already ended. */ }
  }
  session.media?.getTracks().forEach(track => track.stop())
  session.chunks = []
}

/** A late permission result or recorder event can only affect the session that requested it. */
export function useCommentAudioRecorder(owner: string | undefined, onRecorded: (file: File) => void, onError: (message: string) => void) {
  const [status, setStatus] = useState<{ owner: string | undefined; phase: Phase }>({ owner, phase: 'idle' }), [seconds, setSeconds] = useState(0)
  const session = useRef<Session | null>(null), account = useRef(owner)
  const callbacks = useRef({ onRecorded, onError }); callbacks.current = { onRecorded, onError }; account.current = owner
  const phase = status.owner === owner ? status.phase : 'idle'
  const setPhase = useCallback((phase: Phase) => setStatus({ owner: account.current, phase }), [])

  const cancel = useCallback(() => {
    const current = session.current; session.current = null
    if (current) release(current)
    setPhase('idle'); setSeconds(0)
  }, [setPhase])
  useEffect(() => () => {
    const current = session.current; session.current = null
    if (current) release(current)
  }, [owner])

  const fail = useCallback((current: Session, message: string) => {
    const accepted = session.current === current && account.current === current.owner
    if (session.current === current) session.current = null
    release(current)
    if (accepted) { setPhase('idle'); callbacks.current.onError(message) }
  }, [setPhase])
  const finish = useCallback(() => {
    const current = session.current, recorder = current?.recorder
    if (!current || !recorder) return
    clearTimeout(current.timer); clearInterval(current.ticker)
    setPhase('finishing')
    try {
      // A second click while the stop event is queued must not call stop() again.
      if (recorder.state !== 'inactive') recorder.stop()
      current.media?.getTracks().forEach(track => track.stop())
    } catch { fail(current, 'No se pudo finalizar el audio. Inténtalo de nuevo.') }
  }, [fail, setPhase])
  useEffect(() => {
    const hide = () => {
      if (!document.hidden) return
      if (session.current?.recorder) finish()
      else cancel()
    }
    document.addEventListener('visibilitychange', hide)
    return () => document.removeEventListener('visibilitychange', hide)
  }, [cancel, finish])

  async function start() {
    if (session.current || !owner) return
    const current: Session = { owner, chunks: [], bytes: 0 }; session.current = current
    setPhase('requesting'); setSeconds(0)
    try {
      if (!navigator.mediaDevices?.getUserMedia || typeof MediaRecorder === 'undefined') {
        throw new Error('Tu navegador no permite grabar audio. Puedes adjuntar un archivo de audio.')
      }
      const media = await navigator.mediaDevices.getUserMedia({ audio: true })
      if (session.current !== current || account.current !== owner) {
        media.getTracks().forEach(track => track.stop()); return
      }
      current.media = media
      const mime = ['audio/webm;codecs=opus', 'audio/ogg;codecs=opus', 'audio/mp4'].find(type => MediaRecorder.isTypeSupported(type))
      const recorder = new MediaRecorder(media, mime ? { mimeType: mime } : undefined); current.recorder = recorder
      recorder.ondataavailable = event => {
        if (session.current !== current || !event.data.size) return
        current.bytes += event.data.size
        if (current.bytes > COMMENT_AUDIO_MAX_BYTES) { fail(current, 'El audio supera los 10 MB. Graba uno más corto.'); return }
        current.chunks.push(event.data)
      }
      recorder.onerror = () => fail(current, 'No se pudo grabar el audio. Inténtalo de nuevo.')
      recorder.onstop = () => {
        if (session.current !== current || account.current !== owner) { release(current); return }
        const type = recorder.mimeType || 'audio/webm', extension = type.includes('ogg') ? 'ogg' : type.includes('mp4') ? 'm4a' : 'webm'
        const blob = new Blob(current.chunks, { type })
        session.current = null; release(current); setPhase('idle')
        if (blob.size) callbacks.current.onRecorded(new File([blob], `comentario.${extension}`, { type }))
        else callbacks.current.onError('La grabación quedó vacía. Inténtalo de nuevo.')
      }
      recorder.start(250); setPhase('recording')
      const started = Date.now()
      current.ticker = setInterval(() => setSeconds(Math.min(120, Math.floor((Date.now() - started) / 1000))), 1000)
      current.timer = setTimeout(finish, COMMENT_AUDIO_MAX_MS)
    } catch (caught) { fail(current, friendlyError(caught, 'No se pudo acceder al micrófono.')) }
  }
  return { phase, seconds, start, finish, cancel, active: phase !== 'idle' }
}
