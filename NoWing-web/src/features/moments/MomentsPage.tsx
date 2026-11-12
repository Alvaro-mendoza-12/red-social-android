import { StreamingImage, StreamingVideo } from '../../components/StreamingMedia'
import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { Link } from 'react-router-dom'
import { Plus } from 'lucide-react'
import { StatusView } from '../../components/StatusView'
import {
  removeRealtimeChannel,
  reportSupabaseServiceRestrictionIfPresent,
  subscribeRealtimeChannel,
  supabase,
} from '../../core/supabase'
import { friendlyError } from '../../core/utils'
import { useAuth } from '../../core/auth/AuthProvider'
import { fetchMomentsFeed, removeMoment, type MomentItem } from './momentsData'
import { MomentComposer } from './MomentComposer'
import { discardMomentDraft, hasMomentDraft, useMomentDraft } from './momentDraft'
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
  const { draft, identity, available } = useMomentDraft(user?.id)
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

  useEffect(() => { if (draft.status === 'published') void loadMoments(true) }, [draft.status, loadMoments])

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
    <section className="section-stack moments-page">
      <header className="section-header row-between moments-header">
        <p>Comparte un momento con tu gente</p>
        <button className="primary-btn moments-create-button" type="button" disabled={!available} onClick={() => { if (draft.status === 'published') discardMomentDraft(identity); setShowComposer(true) }}><Plus aria-hidden="true" />{draft.status === 'publishing' ? 'Ver publicación en curso' : hasMomentDraft(draft) ? 'Continuar borrador' : 'Crear'}</button>
      </header>

      {!showComposer && hasMomentDraft(draft) ? <p role="status">{draft.status === 'publishing' ? 'Tu momento se está publicando. No vuelvas a enviarlo.' : 'Tienes un borrador sin publicar en esta pestaña.'}</p> : null}

      {showComposer && user ? <MomentComposer key={user.id} onClose={() => setShowComposer(false)} onPublished={() => setShowComposer(false)} /> : null}

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
              <article key={moment.id} className={`moment-card${moment.viewedByMe ? ' viewed' : ''}`}>
                <Link className="moment-card-preview" to={`/app/moments/view/${moment.id}`} aria-label={`Ver momento de ${mine ? 'tu perfil' : moment.ownerName}`}>
                {moment.mediaType === 'video' ? (
                  <StreamingVideo muted preload="metadata" src={moment.mediaUrl} className="video-frame" />
                ) : (
                  <StreamingImage src={moment.mediaUrl} alt={moment.caption || 'Momento'} className="story-media" />
                )}
                <strong>{mine ? 'Tu momento' : moment.ownerName}</strong>
                </Link>
                {moment.caption ? <p className="moment-card-caption">{moment.caption}</p> : null}
                <div className="row-actions">
                  {mine ? <span className="folder-tag">{moment.viewsCount} vistas</span> : null}
                  {mine ? (
                    <span className="folder-tag">Expira en: {expiresInLabel(moment.expiresAt)}</span>
                  ) : !moment.viewedByMe ? (
                    <span className="folder-tag">Nueva</span>
                  ) : null}
                  {mine ? (
                    <button className="ghost-btn" type="button" onClick={() => void deleteMoment(moment.id)}>
                      Eliminar
                    </button>
                  ) : null}
                </div>
                {mine && moment.viewerPreview.length > 0 ? (
                  <p>Vieron: {moment.viewerPreview.join(' · ')}</p>
                ) : null}
              </article>
            )
          })}
        </div>
      )}
    </section>
  )
}
