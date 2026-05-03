import { useCallback, useEffect, useMemo, useState } from 'react'
import { Link } from 'react-router-dom'
import { StatusView } from '../../components/StatusView'
import { supabase } from '../../core/supabase'
import { formatDateTime, friendlyError } from '../../core/utils'
import { useAuth } from '../../core/auth/AuthProvider'
import { fetchMomentsFeed, recordMomentView, type MomentItem } from './momentsData'

function inferMomentMediaType(file: File): 'image' | 'video' {
  if (file.type.startsWith('video/')) return 'video'
  return 'image'
}

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
  const [uploading, setUploading] = useState(false)
  const [caption, setCaption] = useState('')
  const [durationHours, setDurationHours] = useState(24)
  const [visibility, setVisibility] = useState('all')
  const [allowReplies, setAllowReplies] = useState(true)
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

  const loadMoments = useCallback(async () => {
    if (!supabase || !user) {
      setLoading(false)
      return
    }
    setLoading(true)
    setError(null)
    try {
      const feed = await fetchMomentsFeed(user.id)
      setItems(feed)
    } catch (caught) {
      setError(friendlyError(caught, 'No se pudieron cargar los momentos'))
    } finally {
      setLoading(false)
    }
  }, [user])

  useEffect(() => {
    void loadMoments()
  }, [loadMoments])

  useEffect(() => {
    const client = supabase
    if (!client || !user) return
    const channel = client
      .channel('moments-live')
      .on('postgres_changes', { event: '*', schema: 'public', table: 'moments' }, () => {
        void loadMoments()
      })
      .on('postgres_changes', { event: '*', schema: 'public', table: 'moment_views' }, () => {
        void loadMoments()
      })
      .subscribe()
    return () => {
      void client.removeChannel(channel)
    }
  }, [loadMoments, user])

  async function handlePublish(file: File | null) {
    if (!supabase || !user || !file || uploading) return
    setUploading(true)
    setError(null)
    try {
      const mediaType = inferMomentMediaType(file)
      const safeName = file.name.replace(/[^a-zA-Z0-9._-]/g, '_')
      const filePath = `${user.id}/${Date.now()}_${safeName}`
      const { error: uploadError } = await supabase.storage
        .from('moment-media')
        .upload(filePath, file, { contentType: file.type || undefined })
      if (uploadError) throw uploadError

      const { data } = supabase.storage.from('moment-media').getPublicUrl(filePath)
      const expiresAt = new Date(Date.now() + durationHours * 60 * 60 * 1000).toISOString()

      const fullPayload: Record<string, unknown> = {
        user_id: user.id,
        media_url: data.publicUrl,
        media_type: mediaType,
        caption: caption.trim() || null,
        duration_hours: durationHours,
        visibility,
        allow_replies: allowReplies,
        expires_at: expiresAt,
      }
      const { error: insertError } = await supabase.from('moments').insert(fullPayload)
      if (insertError) {
        const minimalPayload = {
          user_id: user.id,
          media_url: data.publicUrl,
          media_type: mediaType,
          expires_at: expiresAt,
        }
        const { error: minimalError } = await supabase.from('moments').insert(minimalPayload)
        if (minimalError) throw minimalError
      }

      setCaption('')
      await loadMoments()
    } catch (caught) {
      setError(friendlyError(caught, 'No se pudo publicar el momento'))
    } finally {
      setUploading(false)
    }
  }

  async function markViewed(moment: MomentItem) {
    if (!supabase || !user || moment.userId === user.id) return
    try {
      await recordMomentView(moment.id, user.id)
      await loadMoments()
    } catch (caught) {
      setError(friendlyError(caught, 'No se pudo registrar vista'))
    }
  }

  async function deleteMoment(momentId: string) {
    if (!supabase || !user || !momentId) return
    try {
      const { error: deleteError } = await supabase
        .from('moments')
        .delete()
        .eq('id', momentId)
        .eq('user_id', user.id)
      if (deleteError) throw deleteError
      await loadMoments()
    } catch (caught) {
      setError(friendlyError(caught, 'No se pudo eliminar el momento'))
    }
  }

  if (!supabase) {
    return <StatusView title="Supabase no configurado" description="Configura .env para usar momentos." />
  }

  return (
    <section className="section-stack">
      <header className="section-header">
        <h2>Momentos</h2>
        <p>Historias efímeras compartidas entre móvil y web.</p>
      </header>

      <article className="surface-card section-stack">
        <h3>Publicar momento</h3>
        <div className="composer">
          <input
            className="text-input"
            placeholder="Escribe un caption (opcional)..."
            value={caption}
            onChange={(event) => setCaption(event.target.value)}
          />
          <select
            className="text-input tiny-input"
            value={durationHours}
            onChange={(event) => setDurationHours(Number(event.target.value))}
          >
            <option value={6}>6 horas</option>
            <option value={12}>12 horas</option>
            <option value={24}>24 horas</option>
            <option value={48}>48 horas</option>
          </select>
          <select
            className="text-input tiny-input"
            value={visibility}
            onChange={(event) => setVisibility(event.target.value)}
          >
            <option value="all">Todos</option>
            <option value="friends">Amigos</option>
            <option value="followers">Seguidores</option>
          </select>
          <label className="checkbox-inline">
            <input
              type="checkbox"
              checked={allowReplies}
              onChange={(event) => setAllowReplies(event.target.checked)}
            />
            Permitir respuestas
          </label>
          <label className="primary-btn file-pick-btn">
            {uploading ? 'Publicando...' : 'Subir foto/video'}
            <input
              type="file"
              hidden
              accept="image/*,video/*"
              onChange={(event) => {
                const file = event.target.files?.[0] ?? null
                void handlePublish(file)
                event.currentTarget.value = ''
              }}
            />
          </label>
        </div>
      </article>

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
                  <video controls preload="metadata" src={moment.mediaUrl} className="video-frame" />
                ) : (
                  <img src={moment.mediaUrl} alt={moment.caption || 'Momento'} className="story-media" />
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
