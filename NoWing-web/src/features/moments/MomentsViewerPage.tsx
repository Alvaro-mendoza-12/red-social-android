import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { Volume2, VolumeX, Pause, Play, X, ChevronLeft, ChevronRight, Send } from 'lucide-react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { StatusView } from '../../components/StatusView'
import { friendlyError } from '../../core/utils'
import { useAuth } from '../../core/auth/AuthProvider'
import { getOrCreateDirectChat, sendDirectMessage } from '../messages/directChat'
import { fetchMomentsFeed, recordMomentView, type MomentItem } from './momentsData'
import { MomentPlayback } from './MomentPlayback'
import './momentViewer.css'

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
  const replyRequest = useRef(0), replyBusy = useRef(false)
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
    replyRequest.current++
    replyBusy.current = false
    setSendingReply(false)
    setProgress(0)
    setReplyChatId(null)
    setManualPause(false)
    setReplyDraft('')
    setReplyError('')
    setIsPressing(false)
    setReplyFocused(false)
    return () => { replyRequest.current++ }
  }, [activeMoment?.id, user?.id])

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
      const target = event.target instanceof HTMLElement ? event.target : null
      const tag = target?.tagName.toLowerCase()
      const isTyping = Boolean(target?.isContentEditable || tag === 'input' || tag === 'textarea' || tag === 'select' || target?.closest('button,a'))
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
    if (!user || !activeMoment || !activeMoment.allowReplies || !replyDraft.trim() || replyBusy.current) return
    if (activeMoment.userId === user.id) return
    const sequence = ++replyRequest.current, message = `Respuesta a tu historia: ${replyDraft.trim()}`
    const submittedDraft = replyDraft
    replyBusy.current = true
    setSendingReply(true)
    setReplyError('')
    try {
      const chatId = await getOrCreateDirectChat(user.id, activeMoment.userId)
      if (sequence !== replyRequest.current) return
      await sendDirectMessage(chatId, user.id, message)
      if (sequence !== replyRequest.current) return
      setReplyDraft(current => current === submittedDraft ? '' : current)
      setReplyChatId(chatId)
    } catch (caught) {
      if (sequence === replyRequest.current) setReplyError(friendlyError(caught, 'No se pudo enviar respuesta a la historia'))
    } finally {
      if (sequence === replyRequest.current) { replyBusy.current = false; setSendingReply(false) }
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
            {activeMoment.mediaType === 'video' || activeMoment.music ? (
              <button className="story-audio-button" type="button" title={muted ? 'Activar sonido' : 'Silenciar'} aria-label={muted ? 'Activar sonido del momento' : 'Silenciar momento'} aria-pressed={!muted} onClick={() => setMuted(current => !current)}>
                {muted ? <VolumeX size={20}/> : <Volume2 size={20}/>}
              </button>
            ) : null}
            <button className="ghost-btn" type="button" aria-label={manualPause ? 'Reanudar momento' : 'Pausar momento'} title={manualPause ? 'Reanudar' : 'Pausar'} aria-pressed={manualPause} onClick={() => setManualPause((current) => !current)}>
              {manualPause ? <Play size={20}/> : <Pause size={20}/>}
            </button>
            <button className="ghost-btn" type="button" aria-label="Cerrar momentos" title="Cerrar" onClick={() => navigate('/app/moments')}>
              <X size={20}/>
            </button>
          </div>
        </div>
      </header>

      <div className="story-viewer-content">
        <button className="story-nav-btn" type="button" aria-label="Historia anterior" onClick={() => goPrev()}>
          <ChevronLeft aria-hidden="true"/>
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
        <button className="story-nav-btn" type="button" aria-label="Historia siguiente" onClick={() => goNext()}>
          <ChevronRight aria-hidden="true"/>
        </button>
      </div>

      {replyError ? <p role="alert" className="error-text">{replyError}</p> : null}
      {activeMoment.userId !== user?.id && activeMoment.allowReplies ? (
        <footer className="story-reply-bar">
          <input
            className="text-input"
            placeholder="Responder historia..."
            aria-label="Responder historia"
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
          <button className="primary-btn" type="button" aria-label={sendingReply ? 'Enviando respuesta' : 'Enviar respuesta'} disabled={sendingReply || !replyDraft.trim()} onClick={() => void sendStoryReply()}>
            <Send size={19} aria-hidden="true"/>
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
