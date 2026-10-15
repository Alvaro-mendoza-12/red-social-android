import { StreamingImage } from '../../components/StreamingMedia'
import { useEffect, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { fallbackApiJson } from '../../core/auth/fallbackAuth'
import { useAuth } from '../../core/auth/AuthProvider'
import './games.css'

type Gamer = {
  userId: string
  displayName: string
  avatarUrl?: string
  gamerBio: string
  regionCode: string
  languages: string[]
  playStyle: string
  compatibilityScore?: number
  compatibilityReasons: { code: string; label: string; points: number }[]
  connected: boolean
  games: { id: string; gameName: string; platform: string; rank: string; role: string; gameUserId?: string }[]
  availability: { dayOfWeek: number; startMinute: number; endMinute: number; timezone: string }[]
}

export function PlayerDetailPage() {
  const { playerId } = useParams()
  const { isFallbackSession } = useAuth()
  const [player, setPlayer] = useState<Gamer | null>(null)
  const [loading, setLoading] = useState(true)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [notice, setNotice] = useState('')
  useEffect(() => {
    if (!playerId || !isFallbackSession) { setLoading(false); return }
    let active = true
    fallbackApiJson<{ player: Gamer }>(`/v1/data/playmatch/players/${encodeURIComponent(playerId)}`)
      .then((data) => { if (active) setPlayer(data.player) })
      .catch(() => { if (active) setError('No se pudo abrir este perfil. Puede que ya no esté visible.') })
      .finally(() => { if (active) setLoading(false) })
    return () => { active = false }
  }, [playerId, isFallbackSession])
  const days = ['Domingo', 'Lunes', 'Martes', 'Miércoles', 'Jueves', 'Viernes', 'Sábado']
  const time = (minute: number) => `${String(Math.floor(minute / 60)).padStart(2, '0')}:${String(minute % 60).padStart(2, '0')}`
  async function connect() {
    if (!player || busy) return
    setBusy(true); setError(''); setNotice('')
    try {
      await fallbackApiJson('/v1/data/playmatch/connections', { method: 'POST', body: JSON.stringify({ playerId: player.userId, message: '', idempotencyKey: crypto.randomUUID() }) })
      setNotice('Solicitud de conexión enviada.')
    } catch { setError('No se pudo enviar la solicitud. Revisa la conexión e inténtalo de nuevo.') }
    finally { setBusy(false) }
  }
  return <section className="games-page games-detail">
    <Link className="ghost-btn games-back" to="/app/games">← Volver a Juegos</Link>
    {loading && <div className="surface-card games-state" role="status">Cargando perfil...</div>}
    {error && <div className="games-alert error" role="alert">{error}</div>}
    {notice && <div className="games-alert" role="status">{notice}</div>}
    {!loading && player && <>
      <header className="surface-card games-detail-head"><div className="games-player-head">{player.avatarUrl ? <StreamingImage src={player.avatarUrl} alt="" /> : <span className="games-avatar">{player.displayName.slice(0, 1).toUpperCase()}</span>}<div><h2>{player.displayName}</h2><p>{player.regionCode || 'Región sin especificar'} · {player.playStyle}</p></div></div><div className="games-row-actions"><strong>{player.compatibilityScore ?? 0}% compatible</strong><button className="primary-btn" type="button" disabled={busy || player.connected || Boolean(notice)} onClick={() => void connect()}>{player.connected ? 'Conectados' : notice ? 'Solicitud enviada' : busy ? 'Enviando...' : 'Conectar'}</button></div></header>
      <div className="games-detail-grid"><section className="surface-card games-form"><h3>Acerca de</h3><p>{player.gamerBio || 'Este jugador aún no escribió una presentación.'}</p><p><strong>Idiomas:</strong> {player.languages.length ? player.languages.join(', ') : 'Sin especificar'}</p>{player.compatibilityReasons?.length > 0 && <div className="games-tags">{player.compatibilityReasons.map((reason) => <span key={reason.code}>{reason.label}</span>)}</div>}</section><section className="surface-card games-form"><h3>Juegos</h3>{player.games.length ? player.games.map((game) => <div className="games-list-row" key={game.id}><span><strong>{game.gameName}</strong><small>{[game.platform, game.rank, game.role].filter(Boolean).join(' · ')}</small>{game.gameUserId && <small>ID: {game.gameUserId}</small>}</span></div>) : <p>No hay juegos visibles.</p>}</section><section className="surface-card games-form"><h3>Disponibilidad</h3>{player.availability.length ? player.availability.map((entry, index) => <div className="games-list-row" key={`${entry.dayOfWeek}-${entry.startMinute}-${index}`}>{days[entry.dayOfWeek]} · {time(entry.startMinute)}–{time(entry.endMinute)} · {entry.timezone}</div>) : <p>Los horarios de este jugador son privados o aún no están configurados.</p>}</section></div>
    </>}
  </section>
}
