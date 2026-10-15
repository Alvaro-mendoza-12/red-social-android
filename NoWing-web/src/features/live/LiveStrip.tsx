import { useCallback, useEffect, useState } from 'react'
import { Radio, Users } from 'lucide-react'
import { Link } from 'react-router-dom'
import { SafeAvatar } from '../../components/SafeAvatar'
import { useAuth } from '../../core/auth/AuthProvider'
import { loadFallbackLiveStreams, type FallbackLiveStream } from '../../core/fallbackData'
import { numberOf, textOf } from '../../core/utils'

export function LiveStrip({ compact = false }: { compact?: boolean }) {
  const { isFallbackSession } = useAuth()
  const [streams, setStreams] = useState<FallbackLiveStream[]>([])

  const load = useCallback(async () => {
    if (!isFallbackSession) return
    try {
      const response = await loadFallbackLiveStreams(compact ? 6 : 12)
      setStreams(response.items.filter((stream) => Boolean(stream.is_active)))
    } catch {
      // The surrounding feed remains usable when live discovery is unavailable.
    }
  }, [compact, isFallbackSession])

  useEffect(() => {
    const initialTimer = window.setTimeout(() => void load(), 0)
    if (!isFallbackSession) return
    const interval = window.setInterval(() => void load(), 12_000)
    return () => {
      window.clearTimeout(initialTimer)
      window.clearInterval(interval)
    }
  }, [isFallbackSession, load])

  if (!isFallbackSession || streams.length === 0) return null

  return (
    <section className={compact ? 'live-strip compact' : 'live-strip'} aria-label="Directos activos">
      <div className="live-strip-heading">
        <span><Radio aria-hidden="true" /> En vivo</span>
        <Link to="/app/live">Ver todos</Link>
      </div>
      <div className="live-strip-list">
        {streams.map((stream) => {
          const hostName = textOf(stream, ['host_name'], 'Creador')
          return (
            <Link className="live-strip-item" key={stream.id} to={`/app/live/${encodeURIComponent(stream.id)}`}>
              <span className="live-strip-avatar">
                <SafeAvatar url={textOf(stream, ['host_avatar_url'], '')} name={hostName} />
                <span className="live-dot" />
              </span>
              <span className="live-strip-copy">
                <strong>{textOf(stream, ['title'], `${hostName} en vivo`)}</strong>
                <small>{hostName}</small>
                <small><Users aria-hidden="true" /> {numberOf(stream, ['viewer_count'], 0)}</small>
              </span>
            </Link>
          )
        })}
      </div>
    </section>
  )
}

export function LiveFeedCard({ stream }: { stream: FallbackLiveStream }) {
  const hostName = textOf(stream, ['host_name'], 'Creador')
  return (
    <Link className="live-feed-card" to={`/app/live/${encodeURIComponent(stream.id)}`}>
      <span className="live-feed-media">
        <SafeAvatar url={textOf(stream, ['host_avatar_url'], '')} name={hostName} size="lg" />
        <span className="live-badge">EN VIVO</span>
      </span>
      <span className="live-feed-copy">
        <small><Radio aria-hidden="true" /> Directo recomendado</small>
        <strong>{textOf(stream, ['title'], `${hostName} en vivo`)}</strong>
        <span>{hostName}</span>
        <span><Users aria-hidden="true" /> {numberOf(stream, ['viewer_count'], 0)} viendo</span>
      </span>
    </Link>
  )
}
