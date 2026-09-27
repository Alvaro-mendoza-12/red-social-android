import { useCallback, useEffect, useRef, useState, type FormEvent } from 'react'
import { Link } from 'react-router-dom'
import { fallbackApiJson } from '../../core/auth/fallbackAuth'
import { useAuth } from '../../core/auth/AuthProvider'
import { uploadFallbackFile } from '../../core/fallbackData'
import './games.css'

type Game = { id: string; name: string; imageUrl?: string; platforms: string[]; ranks: string[]; roles: string[]; rankSchemas?: { ranks: { key: string; displayName: string }[] }[]; roleDefinitions?: { key: string; displayName: string }[] }
type UserGame = { id: string; gameId: string; gameName: string; platform: string; rank: string; role: string }
type Player = { userId: string; displayName: string; avatarUrl?: string; gamerBio: string; regionCode: string; playStyle: string; compatibilityScore?: number; connected: boolean; games: UserGame[] }
type Profile = Player & { version: number; languages: string[]; voicePreference: boolean; discoveryLevel: number; discoverable: boolean; adultConfirmed: boolean; timezone: string; availability: unknown[]; photos: string[]; avatarMediaType?: string; gameIdVisibility?: string; photosVisibility?: string; availabilityVisibility?: string }
type Connection = { id: string; status: string; direction: string; otherUserId: string; otherName: string; message: string }
type Match = { id: string; status: string; score: number; player?: Player }
type Session = { id: string; status: string; match?: Match }
type Availability = { dayOfWeek: number; startMinute: number; endMinute: number; timezone: string }
type Preferences = { version: number; rank_tolerance?: number; voice_required?: boolean; discovery_level?: number; rankTolerance?: number; voiceRequired?: boolean; discoveryLevel?: number }
type Tab = 'discover' | 'profile' | 'connections' | 'matches'
const root = '/v1/data/playmatch'
const request = <T,>(path: string, method = 'GET', body?: unknown) => fallbackApiJson<T>(`${root}${path}`, body === undefined ? { method } : { method, body: JSON.stringify(body) })
const errorText = (error: unknown) => {
  const code = error instanceof Error ? error.message : ''
  const messages: Record<string, string> = {
    EXPECTED_VERSION_REQUIRED: 'El perfil cambió en otro dispositivo. Actualiza la página e inténtalo de nuevo.',
    PROFILE_VERSION_CONFLICT: 'El perfil cambió en otro dispositivo. Pulsa Actualizar antes de guardar.',
    ADULT_CONFIRMATION_REQUIRED: 'Confirma que eres mayor de edad en Mi perfil para buscar partida.',
    PROFILE_NOT_DISCOVERABLE: 'Activa la visibilidad de tu perfil para buscar partida.',
    ADD_GAME_REQUIRED: 'Agrega al menos un juego a tu perfil para buscar partida.',
    RATE_LIMITED: 'Has hecho muchas solicitudes. Espera unos minutos e inténtalo de nuevo.',
    GAME_NOT_FOUND: 'Este juego ya no está disponible en el catálogo.',
    PLATFORM_NOT_SUPPORTED: 'Selecciona una plataforma compatible con este juego.',
  }
  return messages[code] || (code && !/^[A-Z][A-Z_]+$/.test(code) ? code : 'No se pudo completar la acción. Inténtalo de nuevo.')
}

export function GamesPage() {
  const { isFallbackSession, user } = useAuth()
  const [tab, setTab] = useState<Tab>('discover')
  const [enabled, setEnabled] = useState<boolean | null>(null)
  const [profile, setProfile] = useState<Profile | null>(null)
  const [photosEnabled, setPhotosEnabled] = useState(false)
  const [avatarUrl, setAvatarUrl] = useState('')
  const [avatarMediaType, setAvatarMediaType] = useState<string | null>(null)
  const [photos, setPhotos] = useState<string[]>([])
  const [preferences, setPreferences] = useState<Preferences | null>(null)
  const [availability, setAvailability] = useState<Availability[]>([])
  const [catalog, setCatalog] = useState<Game[]>([])
  const [players, setPlayers] = useState<Player[]>([])
  const [connections, setConnections] = useState<Connection[]>([])
  const [matches, setMatches] = useState<Match[]>([])
  const [session, setSession] = useState<Session | null>(null)
  const [query, setQuery] = useState('')
  const [gameFilter, setGameFilter] = useState('')
  const [availableNow, setAvailableNow] = useState(false)
  const [selectedGame, setSelectedGame] = useState('')
  const [selectedPlatform, setSelectedPlatform] = useState('')
  const [catalogQuery, setCatalogQuery] = useState('')
  const [selectedRank, setSelectedRank] = useState('')
  const [selectedRole, setSelectedRole] = useState('')
  const [gameUserId, setGameUserId] = useState('')
  const [displayName, setDisplayName] = useState('')
  const [bio, setBio] = useState('')
  const [region, setRegion] = useState('')
  const [languages, setLanguages] = useState('')
  const [playStyle, setPlayStyle] = useState('casual')
  const [voicePreference, setVoicePreference] = useState(false)
  const [gameIdVisibility, setGameIdVisibility] = useState('connections')
  const [availabilityVisibility, setAvailabilityVisibility] = useState('connections')
  const [rankTolerance, setRankTolerance] = useState(2)
  const [voiceRequired, setVoiceRequired] = useState(false)
  const [discoveryLevel, setDiscoveryLevel] = useState(50)
  const [availableDay, setAvailableDay] = useState(1)
  const [availableStart, setAvailableStart] = useState('18:00')
  const [availableEnd, setAvailableEnd] = useState('22:00')
  const [adultConfirmed, setAdultConfirmed] = useState(false)
  const [discoverable, setDiscoverable] = useState(false)
  const [loading, setLoading] = useState(true)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [notice, setNotice] = useState('')
  const mediaDraftRef = useRef(false)
  const pendingConnectionIds = new Set(connections.filter((item) => item.status === 'PENDING' || item.status === 'ACCEPTED').map((item) => item.otherUserId))

  const loadProfile = useCallback(async (preserveDraft = false) => {
    const data = await request<{ profile: Profile; preferences?: Preferences }>('/profile')
    setProfile(data.profile)
    if (!mediaDraftRef.current && !preserveDraft) {
      setAvatarUrl(data.profile.avatarUrl || '')
      setAvatarMediaType(data.profile.avatarMediaType || null)
      setPhotos(data.profile.photos || [])
    }
    setPreferences(data.preferences || null)
    setAvailability(data.profile.availability as Availability[])
    if (!preserveDraft) {
      setDisplayName(data.profile.displayName)
      setBio(data.profile.gamerBio)
      setRegion(data.profile.regionCode)
      setLanguages(data.profile.languages.join(', '))
      setPlayStyle(data.profile.playStyle)
      setVoicePreference(data.profile.voicePreference)
      setGameIdVisibility(data.profile.gameIdVisibility || 'connections')
      setAvailabilityVisibility(data.profile.availabilityVisibility || 'connections')
      setRankTolerance(data.preferences?.rankTolerance ?? data.preferences?.rank_tolerance ?? 2)
      setVoiceRequired(data.preferences?.voiceRequired ?? data.preferences?.voice_required ?? false)
      setDiscoveryLevel(data.preferences?.discoveryLevel ?? data.preferences?.discovery_level ?? 50)
      setAdultConfirmed(data.profile.adultConfirmed)
      setDiscoverable(data.profile.discoverable)
    }
  }, [])
  const loadConnections = useCallback(async () => {
    const [connectionsData, matchesData] = await Promise.all([
      request<{ items: Connection[] }>('/connections'),
      request<{ items: Match[] }>('/matches'),
    ])
    setConnections(connectionsData.items)
    setMatches(matchesData.items)
  }, [])
  const loadPlayers = useCallback(async (search: string, gameId: string, now: boolean) => {
    const params = new URLSearchParams({ limit: '20' })
    if (search.trim()) params.set('q', search.trim())
    if (gameId) params.set('gameId', gameId)
    if (now) params.set('availableNow', 'true')
    const data = await request<{ items: Player[] }>(`/players?${params}`)
    setPlayers(data.items)
  }, [])

  useEffect(() => {
    if (!isFallbackSession) { setLoading(false); return }
    let active = true
    request<{ capabilities: { enabled: boolean; photosEnabled: boolean } }>('/capabilities').then(async (capabilities) => {
      if (!active) return
      setEnabled(capabilities.capabilities.enabled)
      setPhotosEnabled(capabilities.capabilities.photosEnabled)
      if (!capabilities.capabilities.enabled) return
      const games = await request<{ items: Game[] }>('/catalog?limit=50')
      if (!active) return
      setCatalog(games.items)
      await Promise.all([loadProfile(), loadPlayers('', '', false), loadConnections()])
    }).catch((caught) => { if (active) setError(errorText(caught)) })
      .finally(() => { if (active) setLoading(false) })
    return () => { active = false }
  }, [isFallbackSession, loadConnections, loadPlayers, loadProfile])

  useEffect(() => {
    if (!session || !['SEARCHING', 'MATCH_FOUND'].includes(session.status)) return
    const timer = window.setInterval(() => {
      void request<{ session: Session }>(`/matchmaking/sessions/${encodeURIComponent(session.id)}`)
        .then((result) => setSession(result.session))
        .catch((caught) => setError(errorText(caught)))
    }, 5000)
    return () => window.clearInterval(timer)
  }, [session])

  async function act(action: () => Promise<void>, success: string) {
    if (busy) return
    setBusy(true); setError(''); setNotice('')
    try { await action(); setNotice(success) } catch (caught) { setError(errorText(caught)) } finally { setBusy(false) }
  }
  function submitSearch(event: FormEvent) {
    event.preventDefault()
    void act(() => loadPlayers(query, gameFilter, availableNow), 'Resultados actualizados.')
  }
  function saveProfile(event: FormEvent) {
    event.preventDefault()
    if (!profile) return
    void act(async () => {
      await request('/profile', 'PATCH', {
        expectedVersion: profile.version, gamerDisplayName: displayName.trim(), gamerAvatarUrl: avatarUrl || null,
        gamerAvatarMediaType: avatarMediaType, gamerBio: bio.trim(), regionCode: region.trim(),
        languages: languages.split(',').map((item) => item.trim()).filter(Boolean).slice(0, 8), playStyle, voicePreference,
        discoveryLevel: profile.discoveryLevel, discoverable, adultConfirmed,
        gameIdVisibility, photosVisibility: profile.photosVisibility || 'connections',
        availabilityVisibility, photos, timezone: profile.timezone,
      })
      mediaDraftRef.current = false
      await loadProfile()
    }, 'Perfil de jugador guardado.')
  }
  function addGame(event: FormEvent) {
    event.preventDefault()
    if (!selectedGame || !selectedPlatform) { setError('Elige un juego y una plataforma.'); return }
    void act(async () => {
      await request('/games', 'POST', { gameId: selectedGame, platform: selectedPlatform, gameUserId: gameUserId.trim(), rank: selectedRank, level: '', role: selectedRole, visibility: 'connections', isPrimary: !profile?.games.length })
      await loadProfile(true)
      setGameUserId('')
    }, 'Juego agregado a tu perfil.')
  }
  function savePreferences(event: FormEvent) {
    event.preventDefault()
    if (!profile) return
    void act(async () => {
      await request('/preferences', 'PUT', {
        expectedVersion: preferences?.version || 0,
        gameIds: [...new Set(profile.games.map((item) => item.gameId))],
        platforms: [...new Set(profile.games.map((item) => item.platform))],
        rankTolerance, roles: [...new Set(profile.games.map((item) => item.role).filter(Boolean))],
        styles: [playStyle], languages: languages.split(',').map((item) => item.trim()).filter(Boolean),
        regions: region.trim() ? [region.trim()] : [], voiceRequired, discoveryLevel,
      })
      await loadProfile(true)
    }, 'Preferencias de búsqueda guardadas.')
  }
  function saveAvailability(entries: Availability[]) {
    void act(async () => {
      await request('/availability', 'PUT', { entries })
      await loadProfile(true)
    }, 'Horarios guardados.')
  }
  function uploadPhoto(file: File | undefined, kind: 'avatar' | 'photo') {
    if (!file) return
    if (!['image/jpeg', 'image/png', 'image/webp'].includes(file.type)) { setError('Usa una imagen JPG, PNG o WEBP.'); return }
    if (file.size > 10 * 1024 * 1024) { setError('La imagen debe pesar menos de 10 MB.'); return }
    if (kind === 'photo' && photos.length >= 4) { setError('Puedes agregar hasta cuatro capturas.'); return }
    void act(async () => {
      const extension = file.type === 'image/png' ? 'png' : file.type === 'image/webp' ? 'webp' : 'jpg'
      const path = `${user?.id || 'me'}/${crypto.randomUUID()}_gamer_${kind}.${extension}`
      const uploaded = await uploadFallbackFile('avatars', file, path)
      mediaDraftRef.current = true
      if (kind === 'avatar') { setAvatarUrl(uploaded.publicUrl); setAvatarMediaType('image') }
      else setPhotos((current) => [...current, uploaded.publicUrl])
    }, 'Imagen subida. Pulsa Guardar perfil para aplicarla.')
  }
  const minutesOf = (time: string) => {
    const [hours, minutes] = time.split(':').map(Number)
    return hours * 60 + minutes
  }
  const timeOf = (minutes: number) => `${String(Math.floor(minutes / 60)).padStart(2, '0')}:${String(minutes % 60).padStart(2, '0')}`
  const weekdays = ['Domingo', 'Lunes', 'Martes', 'Miércoles', 'Jueves', 'Viernes', 'Sábado']
  const game = catalog.find((item) => item.id === selectedGame)
  const rankOptions = game?.rankSchemas?.flatMap((schema) => schema.ranks.map((rank) => [rank.key, rank.displayName] as const)) || game?.ranks.map((rank) => [rank, rank] as const) || []
  const roleOptions = game?.roleDefinitions?.map((role) => [role.key, role.displayName] as const) || game?.roles.map((role) => [role, role] as const) || []

  if (!isFallbackSession) return <section className="surface-card games-state"><h2>Juegos</h2><p>Inicia sesión con el servidor Novvin para usar el perfil de juegos y buscar jugadores.</p></section>
  if (loading) return <section className="surface-card games-state" role="status">Cargando juegos...</section>
  if (enabled === null) return <section className="surface-card games-state" role="alert"><h2>No se pudieron cargar los juegos</h2><p>{error || 'Revisa tu conexión e inténtalo de nuevo.'}</p><button className="ghost-btn" type="button" onClick={() => window.location.reload()}>Reintentar</button></section>
  if (enabled === false) return <section className="surface-card games-state"><h2>Juegos no disponible</h2><p>El servidor aún no habilitó PlayMatch para esta cuenta. Tus otros espacios siguen disponibles.</p><Link className="ghost-btn" to="/app/social">Ir a Social</Link></section>
  return <div className="games-page">
    <div className="games-intro surface-card"><div><span className="games-eyebrow">PLAYMATCH</span><h2>Tu espacio para jugar en compañía</h2><p>Encuentra personas por juego y plataforma. Decide qué datos compartes desde tu perfil.</p></div><button className="ghost-btn" type="button" onClick={() => { void act(async () => { await Promise.all([loadProfile(true), loadPlayers(query, gameFilter, availableNow), loadConnections()]) }, 'Información actualizada.') }} disabled={busy}>Actualizar</button></div>
    <nav className="games-tabs" aria-label="Secciones de juegos">
      {([['discover', 'Descubrir'], ['profile', 'Mi perfil'], ['connections', 'Conexiones'], ['matches', 'Partidas']] as const).map(([id, label]) => <button key={id} type="button" className={tab === id ? 'games-tab active' : 'games-tab'} aria-pressed={tab === id} onClick={() => { setTab(id); setError(''); setNotice('') }}>{label}</button>)}
    </nav>
    {error && <p className="games-alert error" role="alert">{error}</p>}{notice && <p className="games-alert" role="status">{notice}</p>}
    {tab === 'discover' && <section className="games-section">
      <form className="games-filters surface-card" onSubmit={submitSearch}>
        <label>Buscar jugador<input className="text-input" value={query} onChange={(event) => setQuery(event.target.value)} placeholder="Nombre o juego" /></label>
        <label>Juego<select className="text-input" value={gameFilter} onChange={(event) => setGameFilter(event.target.value)}><option value="">Todos los juegos</option>{catalog.map((item) => <option key={item.id} value={item.id}>{item.name}</option>)}</select></label>
        <label className="games-check"><input type="checkbox" checked={availableNow} onChange={(event) => setAvailableNow(event.target.checked)} /> Disponible ahora</label>
        <button className="primary-btn" type="submit" disabled={busy}>Buscar</button>
      </form>
      <div className="games-grid">{players.map((player) => <article className="surface-card games-player" key={player.userId}>
        <div className="games-player-head">{player.avatarUrl ? <img src={player.avatarUrl} alt="" /> : <span className="games-avatar">{player.displayName.slice(0, 1).toUpperCase()}</span>}<div><h3>{player.displayName}</h3><small>{player.regionCode || 'Región sin especificar'} · {player.playStyle}</small></div></div>
        <p>{player.gamerBio || 'Este jugador aún no escribió una presentación.'}</p><div className="games-tags">{player.games.slice(0, 4).map((item) => <span key={item.id}>{item.gameName} · {item.platform}</span>)}</div>
        <div className="games-player-actions"><span>{player.compatibilityScore ?? 0}% compatible</span><div className="games-row-actions"><Link className="ghost-btn" to={`/app/games/player/${encodeURIComponent(player.userId)}`}>Ver perfil</Link><button className="ghost-btn" type="button" disabled={busy || player.connected || pendingConnectionIds.has(player.userId)} onClick={() => void act(async () => { await request('/connections', 'POST', { playerId: player.userId, message: '', idempotencyKey: crypto.randomUUID() }); await loadConnections() }, 'Solicitud de conexión enviada.')}>{player.connected ? 'Conectados' : pendingConnectionIds.has(player.userId) ? 'Solicitud enviada' : 'Conectar'}</button></div></div>
      </article>)}</div>{players.length === 0 && <p className="games-empty surface-card">No hay jugadores para estos filtros. Prueba otro juego o amplía la búsqueda.</p>}
    </section>}
    {tab === 'profile' && <div className="games-profile-grid">
      <form className="surface-card games-form" onSubmit={saveProfile}><h3>Perfil de jugador</h3><label>Foto de perfil (JPG, PNG o WEBP)<input className="text-input" type="file" accept="image/jpeg,image/png,image/webp" disabled={busy} onChange={(event) => { uploadPhoto(event.target.files?.[0], 'avatar'); event.target.value = '' }} /></label>{avatarUrl && <img className="games-profile-avatar" src={avatarUrl} alt="Vista previa de tu foto de jugador" />}<label>Nombre de jugador<input className="text-input" required maxLength={40} value={displayName} onChange={(event) => setDisplayName(event.target.value)} /></label><label>Presentación<textarea className="text-input" maxLength={500} value={bio} onChange={(event) => setBio(event.target.value)} /></label><label>Región<input className="text-input" maxLength={40} value={region} onChange={(event) => setRegion(event.target.value)} /></label><label>Idiomas, separados por coma<input className="text-input" value={languages} onChange={(event) => setLanguages(event.target.value)} placeholder="Español, inglés" /></label><label>Estilo de juego<select className="text-input" value={playStyle} onChange={(event) => setPlayStyle(event.target.value)}><option value="casual">Casual</option><option value="competitive">Competitivo</option><option value="mixed">Ambos</option></select></label><label className="games-check"><input type="checkbox" checked={voicePreference} onChange={(event) => setVoicePreference(event.target.checked)} /> Me gusta usar voz</label><label className="games-check"><input type="checkbox" checked={adultConfirmed} onChange={(event) => setAdultConfirmed(event.target.checked)} /> Confirmo que soy mayor de edad</label><label className="games-check"><input type="checkbox" checked={discoverable} disabled={!adultConfirmed} onChange={(event) => setDiscoverable(event.target.checked)} /> Mostrar mi perfil en la búsqueda</label><label>Visibilidad del ID de juego<select className="text-input" value={gameIdVisibility} onChange={(event) => setGameIdVisibility(event.target.value)}><option value="private">Solo yo</option><option value="connections">Conexiones</option><option value="public">Todos</option></select></label><label>Visibilidad de horarios<select className="text-input" value={availabilityVisibility} onChange={(event) => setAvailabilityVisibility(event.target.value)}><option value="private">Solo yo</option><option value="connections">Conexiones</option><option value="public">Todos</option></select></label>{photosEnabled && <div className="games-form"><label>Capturas de juego (máximo 4)<input className="text-input" type="file" accept="image/jpeg,image/png,image/webp" disabled={busy || photos.length >= 4} onChange={(event) => { uploadPhoto(event.target.files?.[0], 'photo'); event.target.value = '' }} /></label><div className="games-photo-grid">{photos.map((url, index) => <div className="games-photo" key={`${url}-${index}`}><img src={url} alt={`Captura de juego ${index + 1}`} /><button className="ghost-btn" type="button" disabled={busy} onClick={() => { mediaDraftRef.current = true; setPhotos((current) => current.filter((_, position) => position !== index)) }}>Quitar</button></div>)}</div></div>}<button className="primary-btn" disabled={busy || !profile} type="submit">Guardar perfil</button></form>
      <section className="surface-card games-form"><h3>Mis juegos</h3><form onSubmit={(event) => { event.preventDefault(); void act(async () => { const result = await request<{ items: Game[] }>(`/catalog?q=${encodeURIComponent(catalogQuery.trim())}&limit=50`); setCatalog(result.items) }, 'Catálogo actualizado.') }} className="games-catalog-search"><label>Buscar en el catálogo<input className="text-input" value={catalogQuery} onChange={(event) => setCatalogQuery(event.target.value)} placeholder="Nombre del juego" /></label><button className="ghost-btn" disabled={busy} type="submit">Buscar</button></form><form onSubmit={addGame} className="games-form"><label>Juego<select className="text-input" value={selectedGame} onChange={(event) => { setSelectedGame(event.target.value); setSelectedPlatform(''); setSelectedRank(''); setSelectedRole('') }}><option value="">Elige un juego</option>{catalog.map((item) => <option key={item.id} value={item.id}>{item.name}</option>)}</select></label><label>Plataforma<select className="text-input" value={selectedPlatform} onChange={(event) => setSelectedPlatform(event.target.value)}><option value="">Elige una plataforma</option>{game?.platforms.map((platform) => <option key={platform} value={platform}>{platform}</option>)}</select></label>{rankOptions.length > 0 && <label>Rango<select className="text-input" value={selectedRank} onChange={(event) => setSelectedRank(event.target.value)}><option value="">Sin rango</option>{rankOptions.map(([value, label]) => <option key={value} value={value}>{label}</option>)}</select></label>}{roleOptions.length > 0 && <label>Rol<select className="text-input" value={selectedRole} onChange={(event) => setSelectedRole(event.target.value)}><option value="">Sin rol</option>{roleOptions.map(([value, label]) => <option key={value} value={value}>{label}</option>)}</select></label>}<label>ID del juego (opcional)<input className="text-input" maxLength={80} value={gameUserId} onChange={(event) => setGameUserId(event.target.value)} /></label><button className="primary-btn" type="submit" disabled={busy}>Agregar juego</button></form><div className="games-list">{profile?.games.map((item) => <div className="games-list-row" key={item.id}><span><strong>{item.gameName}</strong><small>{item.platform}{item.rank ? ` · ${item.rank}` : ''}{item.role ? ` · ${item.role}` : ''}</small></span><button className="ghost-btn" type="button" disabled={busy} onClick={() => void act(async () => { await request(`/games/${encodeURIComponent(item.id)}`, 'DELETE'); await loadProfile(true) }, 'Juego eliminado.')}>Quitar</button></div>)}{!profile?.games.length && <p>Aún no agregaste juegos.</p>}</div></section>
      <form className="surface-card games-form" onSubmit={savePreferences}><h3>Preferencias de partida</h3><label>Tolerancia de rango: {rankTolerance}<input type="range" min="0" max="5" step="1" value={rankTolerance} onChange={(event) => setRankTolerance(Number(event.target.value))} /></label><label>Nivel de exploración: {discoveryLevel}%<input type="range" min="0" max="100" step="10" value={discoveryLevel} onChange={(event) => setDiscoveryLevel(Number(event.target.value))} /></label><label className="games-check"><input type="checkbox" checked={voiceRequired} onChange={(event) => setVoiceRequired(event.target.checked)} /> Solo jugadores con voz</label><button className="primary-btn" type="submit" disabled={busy || !profile}>Guardar preferencias</button></form>
      <section className="surface-card games-form"><h3>Disponibilidad semanal</h3><p>Comparte solo horarios aproximados; nunca tu ubicación exacta.</p><form className="games-availability-form" onSubmit={(event) => { event.preventDefault(); if (availability.length >= 28) { setError('Puedes guardar hasta 28 horarios.'); return } saveAvailability([...availability, { dayOfWeek: availableDay, startMinute: minutesOf(availableStart), endMinute: minutesOf(availableEnd), timezone: profile?.timezone || Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC' }]) }}><label>Día<select className="text-input" value={availableDay} onChange={(event) => setAvailableDay(Number(event.target.value))}>{weekdays.map((name, index) => <option key={name} value={index}>{name}</option>)}</select></label><label>Desde<input className="text-input" type="time" value={availableStart} onChange={(event) => setAvailableStart(event.target.value)} /></label><label>Hasta<input className="text-input" type="time" value={availableEnd} onChange={(event) => setAvailableEnd(event.target.value)} /></label><button className="primary-btn" type="submit" disabled={busy}>Agregar horario</button></form><div className="games-list">{availability.map((entry, index) => <div className="games-list-row" key={`${entry.dayOfWeek}-${entry.startMinute}-${index}`}><span><strong>{weekdays[entry.dayOfWeek]}</strong><small>{timeOf(entry.startMinute)}–{timeOf(entry.endMinute)} · {entry.timezone}</small></span><button className="ghost-btn" type="button" disabled={busy} onClick={() => saveAvailability(availability.filter((_, position) => position !== index))}>Quitar</button></div>)}{availability.length === 0 && <p>Sin horarios configurados.</p>}</div></section>
    </div>}
    {tab === 'connections' && <section className="surface-card games-form"><h3>Solicitudes y conexiones</h3>{connections.map((connection) => <div className="games-list-row" key={connection.id}><span><strong>{connection.otherName}</strong><small>{connection.status === 'PENDING' ? 'Pendiente' : connection.status === 'ACCEPTED' ? 'Conectados' : connection.status}</small></span>{connection.status === 'PENDING' && connection.direction === 'INCOMING' && <div className="games-row-actions"><button className="primary-btn" disabled={busy} onClick={() => void act(async () => { await request(`/connections/${encodeURIComponent(connection.id)}/decision`, 'POST', { decision: 'ACCEPT' }); await loadConnections() }, 'Solicitud aceptada.')}>Aceptar</button><button className="ghost-btn" disabled={busy} onClick={() => void act(async () => { await request(`/connections/${encodeURIComponent(connection.id)}/decision`, 'POST', { decision: 'REJECT' }); await loadConnections() }, 'Solicitud rechazada.')}>Rechazar</button></div>}</div>)}{connections.length === 0 && <p className="games-empty">Todavía no hay solicitudes. Descubre jugadores para empezar.</p>}</section>}
    {tab === 'matches' && <section className="surface-card games-form"><h3>Buscar partida</h3><p>La búsqueda usa tus juegos y preferencias. Tu perfil debe ser visible para otros jugadores.</p><div className="games-row-actions"><button className="primary-btn" disabled={busy || !profile?.discoverable || !profile.games.length || session?.status === 'SEARCHING'} onClick={() => void act(async () => { const data = await request<{ session: Session }>('/matchmaking/sessions', 'POST', { idempotencyKey: crypto.randomUUID(), gameIds: profile?.games.map((item) => item.gameId) || [] }); setSession(data.session) }, 'Búsqueda iniciada.')}>Buscar compañero</button>{session && ['SEARCHING', 'MATCH_FOUND'].includes(session.status) && <button className="ghost-btn" disabled={busy} onClick={() => void act(async () => { const data = await request<{ session: Session }>(`/matchmaking/sessions/${encodeURIComponent(session.id)}/cancel`, 'POST'); setSession(data.session) }, 'Búsqueda cancelada.')}>Cancelar</button>}</div>{!profile?.discoverable && <p className="games-hint">Activa “Mostrar mi perfil en la búsqueda” en Mi perfil.</p>}{session && <p role="status">Estado: {session.status === 'SEARCHING' ? 'Buscando jugadores...' : session.status === 'MATCH_FOUND' ? 'Se encontró una coincidencia' : session.status}</p>}{session?.match?.player && <p>Coincidencia con {session.match.player.displayName} · {session.match.score}%</p>}<h3>Coincidencias</h3>{matches.map((match) => <div className="games-list-row" key={match.id}><span><strong>{match.player?.displayName || 'Jugador'}</strong><small>{match.score}% · {match.status}</small></span>{match.status === 'PENDING' && <div className="games-row-actions"><button className="primary-btn" disabled={busy} onClick={() => void act(async () => { await request(`/matches/${encodeURIComponent(match.id)}/decision`, 'POST', { decision: 'ACCEPT' }); await loadConnections() }, 'Coincidencia aceptada.')}>Aceptar</button><button className="ghost-btn" disabled={busy} onClick={() => void act(async () => { await request(`/matches/${encodeURIComponent(match.id)}/decision`, 'POST', { decision: 'SKIP' }); await loadConnections() }, 'Coincidencia omitida.')}>Omitir</button></div>}</div>)}{matches.length === 0 && <p className="games-empty">Aún no hay coincidencias.</p>}</section>}
  </div>
}
