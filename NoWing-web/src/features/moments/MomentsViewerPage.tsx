import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { Volume2, VolumeX, Pause, Play, X } from 'lucide-react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { StatusView } from '../../components/StatusView'
import { friendlyError } from '../../core/utils'
import { useAuth } from '../../core/auth/AuthProvider'
import { getOrCreateDirectChat, sendDirectMessage } from '../messages/directChat'
import { fetchMomentsFeed, recordMomentView, type MomentItem } from './momentsData'
import { MomentPlayback } from './MomentPlayback'

export function MomentsViewerPage() {
  const { user } = useAuth()
  const navigate = useNavigate()
  const { momentId = '' } = useParams()
  const [moments, setMoments] = useState<MomentItem[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [feedOwner, setFeedOwner] = useState('')
  const [replyError, setReplyError] = useState('')
  const [progress, setProgress] = useState(0)
  const [replyDraft, setReplyDraft] = useState('')
  const [sendingReply, setSendingReply] = useState(false)
  const [replyChatId, setReplyChatId] = useState<string | null>(null)
  const [isPressing, setIsPressing] = useState(false)
  const [replyFocused, setReplyFocused] = useState(false)
  const [tabHidden, setTabHidden] = useState(false)
  const [muted, setMuted] = useState(true)
  const [manualPause, setManualPause] = useState(false)
  const request = useRef(0)
  const pointerStartRef = useRef<{ x: number; y: number; time: number; width: number } | null>(null)

  const activeIndex = Math.max(0, moments.findIndex(moment => moment.id === momentId))
  const activeMoment = feedOwner === user?.id ? moments[activeIndex] ?? null : null
  const shouldPause = isPressing || replyFocused || sendingReply || tabHidden || manualPause

  const loadMoments = useCallback(async () => {
    const sequence = ++request.current
    if (!user?.id) { setMoments([]); setFeedOwner(''); setLoading(false); return }
    setLoading(true); setError(null)
    try {
      const feed = await fetchMomentsFeed(user.id)
      if (sequence === request.current) { setMoments(feed); setFeedOwner(user.id) }
    } catch (caught) {
      if (sequence === request.current) setError(friendlyError(caught, 'No se pudieron cargar historias'))
    } finally { if (sequence === request.current) setLoading(false) }
  }, [user?.id])
  useEffect(() => { void loadMoments(); return () => { request.current++ } }, [loadMoments])

  const goToMoment = useCallback(
    (index: number) => {
      if (moments.length === 0) return
      if (index < 0) {
        navigate('/app/moments', { replace: true })
        return
      }
      if (index >= moments.length) {
        navigate('/app/moments', { replace: true })
        return
      }
      const nextMoment = moments[index]
      navigate(`/app/moments/view/${nextMoment.id}`, { replace: true })
    },
    [moments, navigate],
  )

  const goNext = useCallback(() => {
    goToMoment(activeIndex + 1)
  }, [activeIndex, goToMoment])

  const goPrev = useCallback(() => {
    goToMoment(activeIndex - 1)
  }, [activeIndex, goToMoment])

  useEffect(() => {
    if (!user || !activeMoment) return
    if (activeMoment.userId === user.id || activeMoment.viewedByMe) return
    const sequence = request.current
    void recordMomentView(activeMoment.id, user.id).then(() => {
      if (sequence !== request.current) return
      setMoments((current) =>
        current.map((moment) =>
          moment.id === activeMoment.id
            ? { ...moment, viewedByMe: true, viewsCount: moment.viewsCount + 1 }
            : moment,
        ),
      )
    }).catch(() => { /* View receipts may retry on a later visit; playback remains available. */ })
  }, [activeMoment, user])

  useEffect(() => {
    setProgress(0)
    setReplyChatId(null)
    setManualPause(false)
    setReplyDraft('')
    setReplyError('')
    setIsPressing(false)
    setReplyFocused(false)
  }, [activeMoment?.id])

  useEffect(() => {
    function syncVisibility() {
      setTabHidden(typeof document !== 'undefined' && document.hidden)
    }
    syncVisibility()
    document.addEventListener('visibilitychange', syncVisibility)
    return () => document.removeEventListener('visibilitychange', syncVisibility)
  }, [])

  useEffect(() => {
    function onKey(event: KeyboardEvent) {
      const target = event.target as HTMLElement | null
      const tag = target?.tagName.toLowerCase()
      const isTyping = Boolean(target?.isContentEditable || tag === 'input' || tag === 'textarea')
      if (isTyping && event.key !== 'Escape') return

      if (event.key === 'ArrowRight') {
        goNext()
      } else if (event.key === 'ArrowLeft') {
        goPrev()
      } else if (event.key === ' ' || event.key.toLowerCase() === 'k') {
        event.preventDefault()
        setManualPause((current) => !current)
      } else if (event.key.toLowerCase() === 'm') {
        setMuted((current) => !current)
      } else if (event.key === 'Escape') {
        navigate('/app/moments')
      }
    }
    window.addEventListener('keydown', onKey)
    return () => window.removeEventListener('keydown', onKey)
  }, [goNext, goPrev, navigate])

  const progressBars = useMemo(() => {
    return moments.map((moment, index) => {
      if (index < activeIndex) return 100
      if (index > activeIndex) return 0
      if (moment.id !== activeMoment?.id) return 0
      return progress
    })
  }, [activeIndex, activeMoment?.id, moments, progress])

  function handleStoryPointerDown(event: React.PointerEvent<HTMLElement>) {
    setIsPressing(true)
    const rect = event.currentTarget.getBoundingClientRect()
    pointerStartRef.current = {
      x: event.clientX,
      y: event.clientY,
      time: Date.now(),
      width: rect.width,
    }
  }

  function handleStoryPointerUp(event: React.PointerEvent<HTMLElement>) {
    setIsPressing(false)
    const start = pointerStartRef.current
    pointerStartRef.current = null
    if (!start) return
    const dx = event.clientX - start.x
    const dy = event.clientY - start.y
    const elapsed = Date.now() - start.time

    if (Math.abs(dx) >= 52 && Math.abs(dx) > Math.abs(dy)) {
      if (dx > 0) {
        goPrev()
      } else {
        goNext()
      }
      return
    }

    if (Math.abs(dx) <= 14 && Math.abs(dy) <= 14 && elapsed <= 260) {
      const relativeX = event.clientX - event.currentTarget.getBoundingClientRect().left
      if (relativeX < start.width / 2) {
        goPrev()
      } else {
        goNext()
      }
    }
  }

  function handleStoryPointerCancel() {
    setIsPressing(false)
    pointerStartRef.current = null
  }

  async function sendStoryReply() {
    if (!user || !activeMoment || !replyDraft.trim() || sendingReply) return
    if (activeMoment.userId === user.id) return
    setSendingReply(true)
    setReplyError('')
    try {
      const chatId = await getOrCreateDirectChat(user.id, activeMoment.userId)
      const message = `Respuesta a tu historia: ${replyDraft.trim()}`
      await sendDirectMessage(chatId, user.id, message)
      setReplyDraft('')
      setReplyChatId(chatId)
    } catch (caught) {
      setReplyError(friendlyError(caught, 'No se pudo enviar respuesta a la historia'))
    } finally {
      setSendingReply(false)
    }
  }

  if (loading) {
    return <StatusView title="Cargando historias" description="Espera un momento..." />
  }

  if (error) {
    return <StatusView title="No se pudo abrir historias" description={error} />
  }

  if (!activeMoment) {
    return <StatusView title="Sin historias disponibles" description="No hay historias activas para mostrar." />
  }

  return (
    <section className="story-viewer-shell">
      <header className="story-viewer-header">
        <div className="story-progress-row">
          {progressBars.map((value, index) => (
            <button
              key={`${moments[index]?.id ?? index}`}
              className="story-progress-track-btn"
              type="button"
              onClick={() => goToMoment(index)}
              aria-label={`Ir a historia ${index + 1}`}
            >
              <div className="story-progress-track">
                <div className="story-progress-fill" style={{ width: `${value}%` }} />
              </div>
            </button>
          ))}
        </div>
        <div className="row-between">
          <div>
            <strong>{activeMoment.ownerName}</strong>
            <p>{activeMoment.caption || 'Historia'}</p>
          </div>
          <div className="row-actions">
            <small className="story-state-pill">{shouldPause ? 'Pausado' : 'Reproduciendo'}</small>
            {activeMoment.mediaType === 'video' || activeMoment.music ? (
              <button className="story-audio-button" type="button" aria-label={muted ? 'Activar sonido del momento' : 'Silenciar momento'} onClick={() => setMuted(current => !current)}>
                {muted ? <VolumeX size={18}/> : <Volume2 size={18}/>} {muted ? 'Activar sonido' : 'Sonido activado'}
              </button>
            ) : null}
            <button className="ghost-btn" type="button" onClick={() => setManualPause((current) => !current)}>
              {manualPause ? 'Reanudar' : 'Pausar'}
            </button>
            <button className="ghost-btn" type="button" onClick={() => navigate('/app/moments')}>
              Cerrar
            </button>
          </div>
        </div>
      </header>

      <div className="story-viewer-content">
        <button className="story-nav-btn" type="button" onClick={() => goPrev()}>
          ‹
        </button>
        <article
          className="story-viewer-card"
          onPointerDown={handleStoryPointerDown}
          onPointerUp={handleStoryPointerUp}
          onPointerCancel={handleStoryPointerCancel}
          onPointerLeave={handleStoryPointerCancel}
        >
          <MomentPlayback key={`${user?.id}:${activeMoment.id}`} moment={activeMoment} paused={shouldPause} muted={muted} onProgress={setProgress} onComplete={goNext} />
        </article>
        <button className="story-nav-btn" type="button" onClick={() => goNext()}>
          ›
        </button>
      </div>

      {replyError ? <p role="alert" className="error-text">{replyError}</p> : null}
      {activeMoment.userId !== user?.id && activeMoment.allowReplies ? (
        <footer className="story-reply-bar">
          <input
            className="text-input"
            placeholder="Responder historia..."
            value={replyDraft}
            onChange={(event) => setReplyDraft(event.target.value)}
            onFocus={() => setReplyFocused(true)}
            onBlur={() => setReplyFocused(false)}
            onKeyDown={(event) => {
              if (event.key === 'Enter') {
                event.preventDefault()
                void sendStoryReply()
              }
            }}
          />
          <button className="primary-btn" type="button" disabled={sendingReply} onClick={() => void sendStoryReply()}>
            {sendingReply ? 'Enviando...' : 'Enviar'}
          </button>
          {replyChatId ? (
            <Link className="ghost-btn" to={`/app/messages/${replyChatId}`}>
              Abrir chat
            </Link>
          ) : null}
        </footer>
      ) : null}
    </section>
  )
}
