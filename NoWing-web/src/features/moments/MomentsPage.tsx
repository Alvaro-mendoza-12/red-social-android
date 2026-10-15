import { StreamingImage, StreamingVideo } from '../../components/StreamingMedia'
import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { Link } from 'react-router-dom'
import { Camera, Plus } from 'lucide-react'
import { StatusView } from '../../components/StatusView'
import {
  removeRealtimeChannel,
  reportSupabaseServiceRestrictionIfPresent,
  subscribeRealtimeChannel,
  supabase,
} from '../../core/supabase'
import { formatDateTime, friendlyError } from '../../core/utils'
import { useAuth } from '../../core/auth/AuthProvider'
import { fetchMomentsFeed, recordMomentView, removeMoment, type MomentItem } from './momentsData'
import { MomentComposer } from './MomentComposer'
import { isUbuntuPrimary } from '../../core/ubuntuBackend'
import { subscribeUbuntuRealtime } from '../../core/ubuntuRealtime'

function toMillis(value: string): number {
  if (!value) return 0
  const parsed = Date.parse(value)
  return Number.isNaN(parsed) ? 0 : parsed
}

function expiresInLabel(expiresAt: string): string {
  const remainingMs = toMillis(expiresAt) - Date.now()
  if (remainingMs <= 0) return 'Expirada'
  const hours = Math.floor(remainingMs / (60 * 60 * 1000))
  const minutes = Math.floor((remainingMs % (60 * 60 * 1000)) / (60 * 1000))
  if (hours > 0) return `${hours}h ${minutes}m`
  return `${Math.max(1, minutes)}m`
}

export function MomentsPage() {
  const { user } = useAuth()
  const [items, setItems] = useState<MomentItem[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const request = useRef(0)
  const [showComposer, setShowComposer] = useState(false)
  const sortedMoments = useMemo(() => {
    const me = user?.id ?? ''
    const own = items
      .filter((moment) => moment.userId === me)
      .sort((a, b) => toMillis(b.createdAt) - toMillis(a.createdAt))
    const others = items
      .filter((moment) => moment.userId !== me)
      .sort((a, b) => {
        if (a.viewedByMe !== b.viewedByMe) return a.viewedByMe ? 1 : -1
        return toMillis(b.createdAt) - toMillis(a.createdAt)
      })
    return [...others, ...own]
  }, [items, user?.id])

  const loadMoments = useCallback(async (quiet = false) => {
    const sequence = ++request.current
    if (!user?.id) { setItems([]); setLoading(false); return }
    if (!quiet) setLoading(true)
    setError(null)
    try {
      const feed = await fetchMomentsFeed(user.id)
      if (request.current === sequence) setItems(feed)
    } catch (caught) {
      if (request.current !== sequence) return
      reportSupabaseServiceRestrictionIfPresent('moments-load', caught)
      setError(friendlyError(caught, 'No se pudieron cargar los momentos'))
    } finally { if (request.current === sequence) setLoading(false) }
  }, [user?.id])

  useEffect(() => {
    setItems([])
    setShowComposer(false)
    void loadMoments()
    return () => { request.current++ }
  }, [loadMoments])

  useEffect(() => {
    if (isUbuntuPrimary) {
      return subscribeUbuntuRealtime({
        channel: 'moments:live',
        onEvent: () => {
          void loadMoments(true)
        },
      })
    }
    const client = supabase
    if (!client || !user) return
    const channel = client
      .channel('moments-live')
      .on('postgres_changes', { event: '*', schema: 'public', table: 'moments' }, () => {
        void loadMoments(true)
      })
      .on('postgres_changes', { event: '*', schema: 'public', table: 'moment_views' }, () => {
        void loadMoments(true)
      })
    const subscribedChannel = subscribeRealtimeChannel(channel, { reason: 'moments-live' })
    return () => {
      void removeRealtimeChannel(subscribedChannel ?? channel)
    }
  }, [loadMoments, user])

  async function markViewed(moment: MomentItem) {
    if (!user || moment.userId === user.id) return
    try {
      await recordMomentView(moment.id, user.id)
      await loadMoments(true)
    } catch (caught) {
      setError(friendlyError(caught, 'No se pudo registrar vista'))
    }
  }

  async function deleteMoment(momentId: string) {
    if (!user || !momentId) return
    try {
      await removeMoment(momentId, user.id)
      await loadMoments(true)
    } catch (caught) {
      setError(friendlyError(caught, 'No se pudo eliminar el momento'))
    }
  }

  if (!isUbuntuPrimary && !supabase) {
    return <StatusView title="Backend no configurado" description="Configura VITE_FALLBACK_API_URL para momentos en Ubuntu." />
  }

  return (
    <section className="section-stack">
      <header className="section-header row-between moments-header">
        <p>Comparte un momento con tu gente</p>
        <button className="primary-btn moments-create-button" type="button" onClick={() => setShowComposer(true)}><Plus aria-hidden="true" />Crear</button>
      </header>

      <div className="moments-rail" aria-label="Momentos activos">
        <button className="moment-rail-item moment-rail-create" type="button" onClick={() => setShowComposer(true)}>
          <span><Camera aria-hidden="true" /></span><strong>Tu momento</strong>
        </button>
        {sortedMoments.map((moment) => (
          <Link key={moment.id} className={moment.viewedByMe ? 'moment-rail-item viewed' : 'moment-rail-item'} to={`/app/moments/view/${moment.id}`}>
            <span>{moment.mediaType === 'video' ? <StreamingVideo src={moment.mediaUrl} muted preload="metadata" /> : <StreamingImage src={moment.mediaUrl} alt="" />}</span>
            <strong>{moment.userId === user?.id ? 'Tu momento' : moment.ownerName}</strong>
          </Link>
        ))}
      </div>

      {showComposer && user ? <MomentComposer key={user.id} onClose={() => setShowComposer(false)} onPublished={() => { setShowComposer(false); void loadMoments(true) }} /> : null}

      {loading ? (
        <StatusView title="Cargando momentos" description="Espera un momento..." />
      ) : error ? (
        <StatusView title="No se pudieron cargar momentos" description={error} />
      ) : sortedMoments.length === 0 ? (
        <StatusView title="Sin momentos activos" description="Cuando haya historias, aparecerán aquí." />
      ) : (
        <div className="story-grid">
          {sortedMoments.map((moment) => {
            const mine = moment.userId === user?.id
            return (
              <article key={moment.id} className="surface-card section-stack">
                <div className="row-between">
                  <strong>{mine ? 'Tu momento' : moment.ownerName}</strong>
                  <small>Expira: {formatDateTime(moment.expiresAt)}</small>
                </div>
                {moment.mediaType === 'video' ? (
                  <StreamingVideo controls preload="metadata" src={moment.mediaUrl} className="video-frame" />
                ) : (
                  <StreamingImage src={moment.mediaUrl} alt={moment.caption || 'Momento'} className="story-media" />
                )}
                {moment.caption ? <p>{moment.caption}</p> : null}
                <div className="row-actions">
                  <span className="folder-tag">Visibilidad: {moment.visibility}</span>
                  <span className="folder-tag">{moment.allowReplies ? 'Respuestas ON' : 'Respuestas OFF'}</span>
                  <span className="folder-tag">Vistas: {moment.viewsCount}</span>
                  {mine ? (
                    <span className="folder-tag">Expira en: {expiresInLabel(moment.expiresAt)}</span>
                  ) : !moment.viewedByMe ? (
                    <span className="folder-tag">Nueva</span>
                  ) : null}
                  <Link className="ghost-btn" to={`/app/moments/view/${moment.id}`}>
                    Ver historia
                  </Link>
                  {!mine ? (
                    <button className="ghost-btn" type="button" onClick={() => void markViewed(moment)}>
                      {moment.viewedByMe ? 'Re-visto' : 'Marcar visto'}
                    </button>
                  ) : (
                    <button className="ghost-btn" type="button" onClick={() => void deleteMoment(moment.id)}>
                      Eliminar
                    </button>
                  )}
                </div>
                {mine && moment.viewerPreview.length > 0 ? (
                  <p>Vieron: {moment.viewerPreview.join(' · ')}</p>
                ) : null}
                <small>{formatDateTime(moment.createdAt)}</small>
              </article>
            )
          })}
        </div>
      )}
    </section>
  )
}
