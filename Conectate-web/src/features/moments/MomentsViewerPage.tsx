import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { StatusView } from '../../components/StatusView'
import { supabase } from '../../core/supabase'
import { friendlyError } from '../../core/utils'
import { useAuth } from '../../core/auth/AuthProvider'
import { getOrCreateDirectChat, sendDirectMessage } from '../messages/directChat'
import { fetchMomentsFeed, recordMomentView, type MomentItem } from './momentsData'

function getStoryDurationMs(moment: MomentItem): number {
  if (moment.mediaType === 'video') return 8500
  return 5000
}

export function MomentsViewerPage() {
  const { user } = useAuth()
  const navigate = useNavigate()
  const { momentId = '' } = useParams()
  const [moments, setMoments] = useState<MomentItem[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [activeIndex, setActiveIndex] = useState(0)
  const [progress, setProgress] = useState(0)
  const [replyDraft, setReplyDraft] = useState('')
  const [sendingReply, setSendingReply] = useState(false)
  const [replyChatId, setReplyChatId] = useState<string | null>(null)
  const [isPressing, setIsPressing] = useState(false)
  const [replyFocused, setReplyFocused] = useState(false)
  const [tabHidden, setTabHidden] = useState(false)
  const [muted, setMuted] = useState(true)
  const [manualPause, setManualPause] = useState(false)
  const elapsedMsRef = useRef(0)
  const pointerStartRef = useRef<{ x: number; y: number; time: number; width: number } | null>(null)

  const activeMoment = moments[activeIndex] ?? null
  const shouldPause = isPressing || replyFocused || sendingReply || tabHidden || manualPause

  const loadMoments = useCallback(async () => {
    if (!supabase || !user) {
      setLoading(false)
      return
    }
    setLoading(true)
    setError(null)
    try {
      const feed = await fetchMomentsFeed(user.id)
      setMoments(feed)
      const selectedIndex = feed.findIndex((moment) => moment.id === momentId)
      setActiveIndex(selectedIndex >= 0 ? selectedIndex : 0)
    } catch (caught) {
      setError(friendlyError(caught, 'No se pudieron cargar historias'))
    } finally {
      setLoading(false)
    }
  }, [momentId, user])

  useEffect(() => {
    void loadMoments()
  }, [loadMoments])

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
      setActiveIndex(index)
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
    void recordMomentView(activeMoment.id, user.id).then(() => {
      setMoments((current) =>
        current.map((moment) =>
          moment.id === activeMoment.id
            ? { ...moment, viewedByMe: true, viewsCount: moment.viewsCount + 1 }
            : moment,
        ),
      )
    })
  }, [activeMoment, user])

  useEffect(() => {
    elapsedMsRef.current = 0
    setProgress(0)
    setReplyChatId(null)
    setMuted(true)
    setManualPause(false)
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
    if (!activeMoment) return
    if (shouldPause) return
    const durationMs = getStoryDurationMs(activeMoment)
    const startedAt = Date.now() - elapsedMsRef.current
    const interval = window.setInterval(() => {
      const elapsed = Date.now() - startedAt
      elapsedMsRef.current = elapsed
      const nextProgress = Math.min(100, (elapsed / durationMs) * 100)
      setProgress(nextProgress)
      if (elapsed >= durationMs) {
        window.clearInterval(interval)
        elapsedMsRef.current = 0
        goNext()
      }
    }, 100)
    return () => {
      window.clearInterval(interval)
    }
  }, [activeMoment, goNext, shouldPause])

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

  useEffect(() => {
    const nearby = [moments[activeIndex - 1], moments[activeIndex + 1]].filter(
      (entry): entry is MomentItem => Boolean(entry),
    )
    nearby.forEach((moment) => {
      if (!moment.mediaUrl) return
      if (moment.mediaType === 'video') {
        const preloader = document.createElement('video')
        preloader.preload = 'metadata'
        preloader.src = moment.mediaUrl
        return
      }
      const imagePreload = new Image()
      imagePreload.src = moment.mediaUrl
    })
  }, [activeIndex, moments])

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
    setError(null)
    try {
      const chatId = await getOrCreateDirectChat(user.id, activeMoment.userId)
      const message = `Respuesta a tu historia: ${replyDraft.trim()}`
      await sendDirectMessage(chatId, user.id, message)
      setReplyDraft('')
      setReplyChatId(chatId)
    } catch (caught) {
      setError(friendlyError(caught, 'No se pudo enviar respuesta a la historia'))
    } finally {
      setSendingReply(false)
    }
  }

  if (!supabase) {
    return <StatusView title="Supabase no configurado" description="Configura .env para usar momentos." />
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
            {activeMoment.mediaType === 'video' ? (
              <button className="ghost-btn" type="button" onClick={() => setMuted((current) => !current)}>
                {muted ? 'Audio OFF' : 'Audio ON'}
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
          {activeMoment.mediaType === 'video' ? (
            <video
              className="story-viewer-media"
              src={activeMoment.mediaUrl}
              autoPlay
              muted={muted}
              playsInline
              onEnded={() => goNext()}
            />
          ) : (
            <img className="story-viewer-media" src={activeMoment.mediaUrl} alt={activeMoment.caption || 'Historia'} />
          )}
        </article>
        <button className="story-nav-btn" type="button" onClick={() => goNext()}>
          ›
        </button>
      </div>

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
