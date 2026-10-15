import { useCallback, useEffect, useState, type FormEvent } from 'react'
import { Camera, MonitorUp, Plus, Radio, RefreshCw, Users, X } from 'lucide-react'
import { useNavigate, useSearchParams } from 'react-router-dom'
import { SafeAvatar } from '../../components/SafeAvatar'
import { StatusView } from '../../components/StatusView'
import { useAuth } from '../../core/auth/AuthProvider'
import {
  createFallbackLiveStream,
  loadFallbackLiveStreams,
  type FallbackLiveStream,
} from '../../core/fallbackData'
import { friendlyError, numberOf, textOf } from '../../core/utils'
import './live.css'

type LiveSource = 'camera' | 'screen'

export function LiveHubPage() {
  const navigate = useNavigate()
  const [searchParams] = useSearchParams()
  const { isFallbackSession } = useAuth()
  const [streams, setStreams] = useState<FallbackLiveStream[]>([])
  const [loading, setLoading] = useState(true)
  const [syncing, setSyncing] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [showCreate, setShowCreate] = useState(searchParams.get('create') === '1')
  const [title, setTitle] = useState('')
  const [hashtags, setHashtags] = useState('')
  const [source, setSource] = useState<LiveSource>('camera')
  const [creating, setCreating] = useState(false)

  const load = useCallback(async (silent = false) => {
    if (!isFallbackSession) {
      setLoading(false)
      return
    }
    if (silent) setSyncing(true)
    else setLoading(true)
    try {
      const response = await loadFallbackLiveStreams(60)
      setStreams(response.items.filter((stream) => Boolean(stream.is_active)))
      setError(null)
    } catch (caught) {
      setError(friendlyError(caught, 'No se pudieron cargar los directos'))
    } finally {
      setLoading(false)
      setSyncing(false)
    }
  }, [isFallbackSession])

  useEffect(() => {
    void load()
    const interval = window.setInterval(() => void load(true), 10_000)
    return () => window.clearInterval(interval)
  }, [load])

  async function createLive(event: FormEvent) {
    event.preventDefault()
    if (!title.trim() || creating) return
    setCreating(true)
    setError(null)
    try {
      const response = await createFallbackLiveStream({
        title: title.trim(),
        source,
        hashtags: hashtags.split(/[#,]/).map((tag) => tag.trim()).filter(Boolean).slice(0, 12),
      })
      navigate(`/app/live/${encodeURIComponent(response.stream.id)}?host=1&source=${source}`, { replace: true })
    } catch (caught) {
      setError(friendlyError(caught, 'No se pudo preparar el directo'))
    } finally {
      setCreating(false)
    }
  }

  if (!isFallbackSession) {
    return <StatusView title="Directos no disponibles" description="Inicia sesión con el servidor de producción para emitir o ver directos." />
  }
  if (loading) return <StatusView title="Cargando directos" description="Consultando emisiones activas..." />

  return (
    <section className="section-stack live-hub-page">
      <div className="row-between live-hub-actions">
        <div>
          <span className="brand-tag"><Radio aria-hidden="true" /> Directos</span>
          <h2>Ahora en vivo</h2>
        </div>
        <div className="row-actions">
          <button className="icon-btn" type="button" onClick={() => void load(true)} disabled={syncing} title="Actualizar" aria-label="Actualizar directos">
            <RefreshCw aria-hidden="true" />
          </button>
          <button className="primary-btn" type="button" onClick={() => setShowCreate(true)}>
            <Plus aria-hidden="true" /> Crear directo
          </button>
        </div>
      </div>

      {error ? <StatusView title="No se pudo completar la operación" description={error} /> : null}

      {streams.length === 0 ? (
        <StatusView title="No hay directos activos" description="Puedes iniciar una emisión con cámara o compartir tu pantalla." />
      ) : (
        <div className="live-discovery-grid">
          {streams.map((stream) => {
            const hostName = textOf(stream, ['host_name'], 'Creador')
            return (
              <button className="live-discovery-card" type="button" key={stream.id} onClick={() => navigate(`/app/live/${encodeURIComponent(stream.id)}`)}>
                <span className="live-card-stage">
                  <SafeAvatar url={textOf(stream, ['host_avatar_url'], '')} name={hostName} />
                  <span className="live-badge">EN VIVO</span>
                  <span className="live-viewer-count"><Users aria-hidden="true" /> {numberOf(stream, ['viewer_count'], 0)}</span>
                  <span className="live-source-badge">{textOf(stream, ['source'], 'camera') === 'screen' ? <MonitorUp aria-hidden="true" /> : <Camera aria-hidden="true" />}</span>
                </span>
                <span className="live-card-copy">
                  <strong>{textOf(stream, ['title'], `${hostName} en vivo`)}</strong>
                  <small>{hostName}</small>
                </span>
              </button>
            )
          })}
        </div>
      )}

      {showCreate ? (
        <div className="app-dialog-backdrop" role="presentation" onMouseDown={() => setShowCreate(false)}>
          <article className="surface-card section-stack live-create-dialog" role="dialog" aria-modal="true" aria-label="Crear directo" onMouseDown={(event) => event.stopPropagation()}>
            <div className="row-between">
              <h3>Preparar directo</h3>
              <button className="icon-btn" type="button" onClick={() => setShowCreate(false)} title="Cerrar" aria-label="Cerrar"><X aria-hidden="true" /></button>
            </div>
            <form className="section-stack" onSubmit={createLive}>
              <label>Título
                <input className="text-input" maxLength={80} required value={title} onChange={(event) => setTitle(event.target.value)} placeholder="¿Qué vas a transmitir?" />
              </label>
              <label>Hashtags
                <input className="text-input" value={hashtags} onChange={(event) => setHashtags(event.target.value)} placeholder="gaming, música, charla" />
              </label>
              <div className="segmented-control" aria-label="Fuente del directo">
                <button type="button" className={source === 'camera' ? 'active' : ''} aria-pressed={source === 'camera'} onClick={() => setSource('camera')}><Camera aria-hidden="true" /> Cámara</button>
                <button type="button" className={source === 'screen' ? 'active' : ''} aria-pressed={source === 'screen'} onClick={() => setSource('screen')}><MonitorUp aria-hidden="true" /> Pantalla</button>
              </div>
              <button className="primary-btn" type="submit" disabled={!title.trim() || creating}>
                <Radio aria-hidden="true" /> {creating ? 'Preparando...' : 'Continuar'}
              </button>
            </form>
          </article>
        </div>
      ) : null}
    </section>
  )
}
