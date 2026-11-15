import { StreamingImage } from '../../components/StreamingMedia'
import { SafeAvatar } from '../../components/SafeAvatar'
import { friendlyError } from '../../core/utils'
import { useCallback, useEffect, useRef, useState, type FormEvent } from 'react'
import { Link } from 'react-router-dom'
import { ArrowRight, Bookmark, CalendarDays, Gamepad2, Inbox, LayoutGrid, MessageCircle, Search, Send, UserRound, UsersRound } from 'lucide-react'
import { fallbackApiJson } from '../../core/auth/fallbackAuth'
import { useAuth } from '../../core/auth/AuthProvider'
import { uploadFallbackFile } from '../../core/fallbackData'
import './games.css'
import { emptyPlayerFilters, matchReadiness, readSavedGames, useGamePlayers, writeSavedGames, type PlayerFilters, type SavedGame } from './playMatchWorkspace'
import { GameCatalog } from './GameCatalog'

type Game = { id: string; name: string; imageUrl?: string; iconUrl?: string; coverUrl?: string; platforms: string[]; ranks: string[]; roles: string[]; rankSchemas?: { ranks: { key: string; displayName: string }[] }[]; roleDefinitions?: { key: string; displayName: string }[] }
type UserGame = { id: string; gameId: string; gameName: string; platform: string; rank: string; role: string }
type Player = { userId: string; displayName: string; avatarUrl?: string; gamerBio: string; regionCode: string; playStyle: string; compatibilityScore?: number; connected: boolean; games: UserGame[] }
type Profile = Player & { version: number; languages: string[]; voicePreference: boolean; discoveryLevel: number; discoverable: boolean; adultConfirmed: boolean; timezone: string; availability: unknown[]; photos: string[]; avatarMediaType?: string; gameIdVisibility?: string; photosVisibility?: string; availabilityVisibility?: string }
type Connection = { id: string; status: string; direction: string; otherUserId: string; otherName: string; message: string }
type Match = { id: string; status: string; score: number; player?: Player }
type Session = { id: string; status: string; match?: Match }
type Availability = { dayOfWeek: number; startMinute: number; endMinute: number; timezone: string }
type Preferences = { version: number; rank_tolerance?: number; voice_required?: boolean; discovery_level?: number; rankTolerance?: number; voiceRequired?: boolean; discoveryLevel?: number }
type Tab = 'home' | 'catalog' | 'discover' | 'matches' | 'connections' | 'profile'
type Library = 'all' | 'owned' | 'saved'
const tabs = [
  { id: 'home', label: 'Inicio', icon: LayoutGrid },
  { id: 'catalog', label: 'Catálogo', icon: Gamepad2 },
  { id: 'discover', label: 'Jugadores', icon: Search },
  { id: 'matches', label: 'Partidas', icon: CalendarDays },
  { id: 'connections', label: 'Actividad', icon: UsersRound },
] as const
const root = '/v1/data/playmatch'
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
  return messages[code] || friendlyError(error, 'No se pudo completar la acción. Inténtalo de nuevo.')
}

export function GamesPage() {
  const { isFallbackSession, user } = useAuth()
  if (!isFallbackSession || !user) {
    return <section className="surface-card games-state"><h2>Juegos</h2><p>Inicia sesión con Novvin para abrir PlayMatch.</p></section>
  }
  return <GamesWorkspace key={user.id} accountId={user.id} />
}

function GamesWorkspace({ accountId }: { accountId: string }) {
  const [tab, setTab] = useState<Tab>('home')
  const [library, setLibrary] = useState<Library>('all')
  const [catalogOpened, setCatalogOpened] = useState(false)
  const [savedGames, setSavedGames] = useState(() => readSavedGames(accountId))
  const request = useCallback(<T,>(path: string, method = 'GET', body?: unknown, signal?: AbortSignal) =>
    fallbackApiJson<T>(`${root}${path}`, {
      method, expectedAccountId: accountId, signal,
      ...(body === undefined ? {} : { body: JSON.stringify(body) }),
    }), [accountId])
  const [enabled, setEnabled] = useState<boolean | null>(null)
  const [profile, setProfile] = useState<Profile | null>(null)
  const [photosEnabled, setPhotosEnabled] = useState(false)
  const [avatarUrl, setAvatarUrl] = useState('')
  const [avatarMediaType, setAvatarMediaType] = useState<string | null>(null)
  const [photos, setPhotos] = useState<string[]>([])
  const [preferences, setPreferences] = useState<Preferences | null>(null)
  const [availability, setAvailability] = useState<Availability[]>([])
  const [catalog, setCatalog] = useState<Game[]>([])
  const [popularGames, setPopularGames] = useState<Game[]>([])
  const [popularLoading, setPopularLoading] = useState(true)
  const [connections, setConnections] = useState<Connection[]>([])
  const [activityLoading, setActivityLoading] = useState(true)
  const [activityError, setActivityError] = useState('')
  const [matches, setMatches] = useState<Match[]>([])
  const [acceptedMatches, setAcceptedMatches] = useState<string[]>([])
  const [session, setSession] = useState<Session | null>(null)
  const [filters, setFilters] = useState<PlayerFilters>(emptyPlayerFilters)
  const discovery = useGamePlayers<Player>(accountId)
  const [profileLoading, setProfileLoading] = useState(true)
  const [revision, setRevision] = useState(0)
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
  const mutation = useRef(false)
  const sessionWrite = useRef(false)
  const connectionsByPlayer = new Map(connections.map(item => [item.otherUserId, item]))

  useEffect(() => {
    if (tab === 'catalog' && library === 'all') setCatalogOpened(true)
  }, [tab, library])

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
      setPlayStyle(data.profile.playStyle.toLowerCase())
      setVoicePreference(data.profile.voicePreference)
      setGameIdVisibility(data.profile.gameIdVisibility || 'connections')
      setAvailabilityVisibility(data.profile.availabilityVisibility || 'connections')
      setRankTolerance(data.preferences?.rankTolerance ?? data.preferences?.rank_tolerance ?? 2)
      setVoiceRequired(data.preferences?.voiceRequired ?? data.preferences?.voice_required ?? false)
      setDiscoveryLevel(data.preferences?.discoveryLevel ?? data.preferences?.discovery_level ?? 50)
      setAdultConfirmed(data.profile.adultConfirmed)
      setDiscoverable(data.profile.discoverable)
    }
  }, [request])
  const loadConnections = useCallback(async () => {
    try {
      const [connectionsData, matchesData] = await Promise.all([
        request<{ items: Connection[] }>('/connections'),
        request<{ items: Match[] }>('/matches'),
      ])
      setConnections(connectionsData.items)
      setMatches(matchesData.items)
      setActivityError('')
    } catch (caught) {
      setActivityError(errorText(caught))
    }
  }, [request])
  useEffect(() => {
    let active = true
    setLoading(true)
    setProfileLoading(true)
    setError('')
    request<{ capabilities: { enabled: boolean; photosEnabled: boolean } }>('/capabilities').then(async ({ capabilities }) => {
      if (!active) return
      setEnabled(capabilities.enabled)
      setPhotosEnabled(capabilities.photosEnabled)
      setLoading(false)
      if (!capabilities.enabled) return
      void loadProfile().catch(caught => { if (active) setError(errorText(caught)) })
        .finally(() => { if (active) setProfileLoading(false) })
      void loadConnections()
        .finally(() => { if (active) setActivityLoading(false) })
      try {
        const games = await request<{ items: Game[] }>('/catalog?limit=12')
        if (active) {
          setPopularGames(games.items)
          setCatalog(current => [...new Map([...current, ...games.items].map(game => [game.id, game])).values()])
        }
      } finally { if (active) setPopularLoading(false) }
    }).catch(caught => { if (active) setError(errorText(caught)) })
      .finally(() => { if (active) setLoading(false) })
    return () => { active = false }
  }, [request, loadConnections, loadProfile, revision])

  const sessionId = session?.id
  const sessionStatus = session?.status
  useEffect(() => {
    if (!sessionId || !['SEARCHING', 'MATCH_FOUND'].includes(sessionStatus || '')) return
    const controller = new AbortController()
    let timer: number | undefined
    async function poll() {
      if (controller.signal.aborted) return
      try {
        if (!document.hidden && !sessionWrite.current) {
          const result = await request<{ session: Session }>(`/matchmaking/sessions/${encodeURIComponent(sessionId!)}`, 'GET', undefined, controller.signal)
          if (!controller.signal.aborted && !sessionWrite.current) {
            setSession(current => current && current.id === sessionId && current.status === sessionStatus ? result.session : current)
            if (result.session.status === 'ACCEPTED') await loadConnections()
          }
        }
      } catch (caught) {
        if (!controller.signal.aborted) setError(errorText(caught))
      } finally {
        if (!controller.signal.aborted) timer = window.setTimeout(poll, 5_000)
      }
    }
    timer = window.setTimeout(poll, 5_000)
    return () => { controller.abort(); window.clearTimeout(timer) }
  }, [request, loadConnections, sessionId, sessionStatus])

  async function act(action: () => Promise<void>, success: string) {
    if (mutation.current) return
    mutation.current = true
    setBusy(true)
    setError('')
    setNotice('')
    try { await action(); setNotice(success) } catch (caught) { setError(errorText(caught)) } finally {
      mutation.current = false
      setBusy(false)
    }
  }

  function selectSection(next: Tab) {
    setTab(next)
    setError('')
    setNotice('')
    if (next === 'discover' && !discovery.applied && !discovery.loading) void discovery.load(filters)
  }

  function discoverGame(game: SavedGame) {
    setCatalog(current => current.some(item => item.id === game.id) ? current : [...current, { ranks: [], roles: [], ...game }])
    const next = { ...emptyPlayerFilters, gameId: game.id }
    setFilters(next)
    setTab('discover')
    void discovery.load(next)
  }

  function toggleSave(game: SavedGame) {
    const next = savedGames.some(item => item.id === game.id)
      ? savedGames.filter(item => item.id !== game.id)
      : [{ id: game.id, name: game.name, platforms: game.platforms }, ...savedGames]
    if (writeSavedGames(accountId, next)) setSavedGames(next)
    else setError('Este navegador no permitió guardar el juego en el dispositivo.')
  }

  function submitSearch(event: FormEvent) {
    event.preventDefault()
    void discovery.load(filters)
  }

  async function refreshActivity() {
    if (busy || activityLoading) return
    setActivityLoading(true)
    try { await loadConnections() } finally { setActivityLoading(false) }
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
      const path = `${accountId}/${crypto.randomUUID()}_gamer_${kind}.${extension}`
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

  if (loading) return <section className="surface-card games-state" role="status">Cargando juegos...</section>
  if (enabled === null) return <section className="surface-card games-state" role="alert"><h2>No se pudieron cargar los juegos</h2><p>{error || 'Revisa tu conexión e inténtalo de nuevo.'}</p><button className="ghost-btn" type="button" onClick={() => setRevision(value => value + 1)}>Reintentar</button></section>
  if (enabled === false) return <section className="surface-card games-state"><h2>Juegos no disponible</h2><p>El servidor aún no habilitó PlayMatch para esta cuenta. Tus otros espacios siguen disponibles.</p><Link className="ghost-btn" to="/app/social">Ir a Social</Link></section>
  const pendingMatches = matches.filter(item => item.status === 'PENDING')
  const incoming = connections.filter(item => item.status === 'PENDING' && item.direction === 'INCOMING')
  const outgoing = connections.filter(item => item.status === 'PENDING' && item.direction === 'OUTGOING')
  const chats = connections.filter(item => item.status === 'ACCEPTED' && item.otherUserId)
  const pendingCount = incoming.length + pendingMatches.length
  const featured = popularGames[0]
  const featuredImage = featured && (featured.coverUrl || featured.imageUrl || featured.iconUrl)
  const readiness = matchReadiness(profile, profileLoading)
  const ownedCount = profile?.games.length || 0
  const platforms = [...new Set(catalog.flatMap(item => item.platforms))]
  const activeSession = Boolean(session && ['SEARCHING', 'MATCH_FOUND'].includes(session.status))
  const applied = discovery.applied
  const appliedFilters = applied ? [
    applied.query.trim(), catalog.find(item => item.id === applied.gameId)?.name,
    applied.platform, applied.region.trim(), applied.availableNow && 'Disponibles ahora',
  ].filter(Boolean) : []
  const matchGame = catalog.find(item => item.id === filters.gameId)?.name
  const matchCriteria = [matchGame, filters.platform].filter(Boolean)
  const profilesToReview = [...new Map([
    ...pendingMatches, ...(session?.match && activeSession ? [session.match] : []),
  ].map(item => [item.id, item])).values()]
  function openLibrary(next: Library) { setLibrary(next); selectSection('catalog') }
  function updateFilter<K extends keyof PlayerFilters>(key: K, value: PlayerFilters[K]) {
    setFilters(current => ({ ...current, [key]: value }))
  }
  function decideConnection(connection: Connection, decision: 'ACCEPT' | 'REJECT') {
    void act(async () => {
      await request(`/connections/${encodeURIComponent(connection.id)}/decision`, 'POST', { decision })
      await loadConnections()
    }, decision === 'ACCEPT' ? 'Solicitud aceptada.' : 'Solicitud rechazada.')
  }
  function decideMatch(match: Match, decision: 'ACCEPT' | 'SKIP') {
    void act(async () => {
      sessionWrite.current = true
      try {
        const result = await request<{ status: string }>(`/matches/${encodeURIComponent(match.id)}/decision`, 'POST', { decision })
        if (decision === 'ACCEPT') setAcceptedMatches(current => [...current, match.id])
        if (session?.match?.id === match.id && (decision === 'SKIP' || result.status === 'ACCEPTED')) {
          setSession(current => current?.id === session.id
            ? decision === 'SKIP' ? { ...current, status: 'SEARCHING', match: undefined } : { ...current, status: 'ACCEPTED' }
            : current)
        }
        await loadConnections()
      } finally { sessionWrite.current = false }
    }, decision === 'ACCEPT' ? 'Aceptación enviada.' : 'Coincidencia omitida.')
  }
  function startMatch() {
    if (!readiness.ready || activeSession || !profile) return
    void act(async () => {
      sessionWrite.current = true
      try {
        const result = await request<{ session: Session }>('/matchmaking/sessions', 'POST', {
          idempotencyKey: crypto.randomUUID(),
          gameIds: filters.gameId ? [filters.gameId] : [...new Set(profile.games.map(item => item.gameId))],
          platforms: filters.platform ? [filters.platform] : [],
        })
        setSession(result.session)
        if (result.session.status === 'MATCH_FOUND') await loadConnections()
      } finally { sessionWrite.current = false }
    }, 'Búsqueda iniciada.')
  }
  function cancelMatch() {
    if (!session || !activeSession) return
    void act(async () => {
      sessionWrite.current = true
      try {
        const result = await request<{ session: Session }>(`/matchmaking/sessions/${encodeURIComponent(session.id)}/cancel`, 'POST')
        setSession(result.session)
        await loadConnections()
      } finally { sessionWrite.current = false }
    }, 'Búsqueda cancelada.')
  }

  return <div className="games-page">
    <header className="games-page-header">
      <div className="games-page-identity"><Gamepad2 aria-hidden="true" /><h2>PlayMatch</h2></div>
      <button className="ghost-btn" type="button" onClick={() => selectSection('profile')}><UserRound size={18} aria-hidden="true" />Mi perfil gamer</button>
    </header>
    <nav className="games-tabs" aria-label="Secciones de juegos">
      {tabs.map(({ id, label, icon: Icon }) => <button key={id} type="button" className={`games-tab${tab === id ? ' active' : ''}`} aria-pressed={tab === id} onClick={() => selectSection(id)}>
        <Icon size={17} aria-hidden="true" />{label}
        {id === 'connections' && pendingCount > 0 && <span className="games-pending" aria-label={`${pendingCount} pendientes`}>{pendingCount}</span>}
      </button>)}
    </nav>
    {error && <p className="games-alert error" role="alert">{error}</p>}
    {notice && <p className="games-alert" role="status">{notice}</p>}
    {tab === 'home' && <>
      <section className="games-hero surface-card" aria-labelledby="playmatch-home-title">
        {featuredImage && <StreamingImage className="games-hero-art" src={featuredImage} alt="" fetchPriority="high" />}
        <div className="games-hero-copy">
          <h3 id="playmatch-home-title">Encuentra tu equipo</h3>
          <p>Explora por juego y plataforma, o abre una búsqueda para encontrar una partida.</p>
          <div className="games-row-actions">
            <button className="primary-btn" type="button" onClick={() => selectSection('matches')}>{activeSession ? 'Ver búsqueda activa' : 'Buscar partida'}<ArrowRight size={16} aria-hidden="true" /></button>
            <button className="games-hero-secondary" type="button" onClick={() => selectSection('discover')}>Ver jugadores</button>
          </div>
        </div>
      </section>
      <div className="games-home-grid">
        <button className="games-shortcut surface-card" type="button" onClick={() => openLibrary('owned')}>
          <Gamepad2 aria-hidden="true" /><span><strong>Mis juegos</strong><small>{profileLoading ? 'Cargando perfil…' : `${ownedCount} ${ownedCount === 1 ? 'configurado' : 'configurados'}`}</small></span><ArrowRight size={17} aria-hidden="true" />
        </button>
        <button className="games-shortcut surface-card" type="button" onClick={() => openLibrary('saved')}>
          <Bookmark aria-hidden="true" /><span><strong>Guardados</strong><small>{savedGames.length} en este dispositivo</small></span><ArrowRight size={17} aria-hidden="true" />
        </button>
        <button className="games-shortcut surface-card" type="button" onClick={() => selectSection('connections')}>
          <UsersRound aria-hidden="true" /><span><strong>Actividad</strong><small>{pendingCount ? `${pendingCount} por revisar` : 'Solicitudes y chats'}</small></span><ArrowRight size={17} aria-hidden="true" />
        </button>
      </div>
      <section className="games-popular section-stack" aria-labelledby="playmatch-popular">
        <div className="row-between"><h3 id="playmatch-popular">Juegos populares</h3><button className="ghost-btn" type="button" onClick={() => openLibrary('all')}>Ver catálogo</button></div>
        {popularGames.length ? <div className="games-popular-list">
          {popularGames.slice(0, 8).map(game => <button key={game.id} className="games-popular-card" type="button" onClick={() => discoverGame(game)}>
            {game.coverUrl || game.imageUrl || game.iconUrl ? <StreamingImage src={game.coverUrl || game.imageUrl || game.iconUrl} alt="" loading="lazy" /> : null}
            <strong>{game.name}</strong>
          </button>)}
        </div> : popularLoading ? <p className="surface-card games-empty" role="status">Cargando juegos populares…</p>
          : <p className="surface-card games-empty">Puedes buscar juegos directamente en el catálogo.</p>}
      </section>
    </>}
    {(tab === 'catalog' || catalogOpened) && <section className="games-section" hidden={tab !== 'catalog'}>
      <nav className="games-library-tabs" aria-label="Biblioteca de juegos">
        {([['all', 'Explorar'], ['owned', 'Mis juegos'], ['saved', 'Guardados']] as const).map(([id, label]) =>
          <button key={id} className="ghost-btn" type="button" aria-pressed={library === id} onClick={() => setLibrary(id)}>{label}</button>)}
      </nav>
      {catalogOpened && <div hidden={library !== 'all'}><GameCatalog disabled={busy} onSelect={discoverGame} savedIds={new Set(savedGames.map(game => game.id))} onSave={toggleSave} accountId={accountId} /></div>}
      {library === 'owned' && <section className="surface-card games-form">
        <div className="row-between"><h3>Mis juegos</h3><button type="button" className="ghost-btn" onClick={() => selectSection('profile')}>Editar juegos</button></div>
        {profileLoading ? <p role="status">Cargando tus juegos…</p> : !profile?.games.length ? <p>Añade un juego a tu perfil para buscar partida.</p> : profile.games.map(game => <div className="games-list-row" key={game.id}>
          <span><strong>{game.gameName}</strong><small>{game.platform}{game.rank ? ` · ${game.rank}` : ''}</small></span>
          <button className="ghost-btn" type="button" onClick={() => discoverGame({ id: game.gameId, name: game.gameName, platforms: [game.platform] })}>Ver jugadores</button>
        </div>)}
      </section>}
      {library === 'saved' && <section className="surface-card games-form">
        <h3>Juegos guardados</h3>
        {!savedGames.length ? <p>Guarda juegos desde el catálogo para volver a buscarlos aquí.</p> : savedGames.map(game => <div className="games-list-row" key={game.id}>
          <span><strong>{game.name}</strong><small>{game.platforms.join(' · ')}</small></span>
          <div className="games-row-actions"><button type="button" className="ghost-btn" onClick={() => discoverGame(game)}>Ver jugadores</button>
            <button type="button" className="ghost-btn" onClick={() => toggleSave(game)} aria-label={`Quitar ${game.name} de guardados`}>Quitar</button></div>
        </div>)}
      </section>}
    </section>}
    {tab === 'discover' && <section className="games-section">
      <div className="games-section-heading"><Search aria-hidden="true" /><div><h3>Jugadores</h3><p>Encuentra compañeros por juego, plataforma o disponibilidad.</p></div></div>
      <form className="games-filters surface-card" onSubmit={submitSearch}>
        <label className="games-filter-query">Buscar jugador<input className="text-input" type="search" value={filters.query} maxLength={100} onChange={event => updateFilter('query', event.target.value)} placeholder="Nombre o juego" /></label>
        <label>Juego<select className="text-input" value={filters.gameId} onChange={event => updateFilter('gameId', event.target.value)}><option value="">Todos</option>{catalog.map(item => <option key={item.id} value={item.id}>{item.name}</option>)}</select></label>
        <label>Plataforma<select className="text-input" value={filters.platform} onChange={event => updateFilter('platform', event.target.value)}><option value="">Todas</option>{platforms.map(item => <option key={item} value={item}>{item}</option>)}</select></label>
        <label>Región<input className="text-input" value={filters.region} maxLength={40} onChange={event => updateFilter('region', event.target.value)} placeholder="Ej. CO" /></label>
        <div className="games-filter-actions">
          <label className="games-check"><input type="checkbox" checked={filters.availableNow} onChange={event => updateFilter('availableNow', event.target.checked)} /> Disponibles ahora</label>
          <button className="ghost-btn" type="button" onClick={() => { setFilters(emptyPlayerFilters); void discovery.load(emptyPlayerFilters) }}>Limpiar filtros</button>
          <button className="primary-btn" type="submit">Buscar</button>
        </div>
      </form>
      <div className="games-results-bar">
        <h4>Resultados</h4>
        <small role="status">{discovery.loading ? 'Buscando…' : `${discovery.players.length} mostrados`}</small>
        {appliedFilters.length > 0 && <ul className="games-applied-filters" aria-label="Filtros aplicados">
          {appliedFilters.map((value, index) => <li key={index}>{value}</li>)}
        </ul>}
      </div>
      {discovery.error && <div className="games-alert error" role="alert">{discovery.error} <button className="ghost-btn" type="button" onClick={() => void discovery.load(discovery.applied || filters, discovery.cursor || undefined)}>Reintentar</button></div>}
      <div className="games-grid" aria-busy={discovery.loading}>{discovery.players.map(player => {
        const relation = connectionsByPlayer.get(player.userId)
        const connected = player.connected || relation?.status === 'ACCEPTED'
        return <article className="surface-card games-player" key={player.userId}>
          <div className="games-player-head"><SafeAvatar url={player.avatarUrl} name={player.displayName} size="lg" /><div><h3>{player.displayName}</h3><small>{[player.regionCode, player.playStyle].filter(Boolean).join(' · ')}</small></div></div>
          {player.gamerBio && <p>{player.gamerBio}</p>}
          <div className="games-tags">{player.games.slice(0, 4).map(item => <span key={item.id}>{item.gameName} · {item.platform}</span>)}</div>
          <div className="games-player-actions">
            {typeof player.compatibilityScore === 'number' && <span>{player.compatibilityScore}% compatible</span>}
            <div className="games-row-actions">
              <Link className="ghost-btn" to={`/app/games/player/${encodeURIComponent(player.userId)}`}>Ver perfil</Link>
              {connected ? <Link className="primary-btn" to={`/chat/${encodeURIComponent(player.userId)}`}>Abrir chat</Link>
              : <button className="primary-btn" type="button" disabled={busy || relation?.status === 'PENDING'} onClick={() => void act(async () => {
                await request('/connections', 'POST', { playerId: player.userId, message: '', idempotencyKey: crypto.randomUUID() }); await loadConnections()
              }, 'Solicitud enviada.')}>{relation?.status === 'PENDING' ? 'Pendiente' : 'Conectar'}</button>}
            </div>
          </div>
        </article>
      })}</div>
      {!discovery.loading && !discovery.error && !discovery.players.length && <p className="surface-card games-empty">No hay jugadores con estos filtros. Prueba otro juego o plataforma.</p>}
      {discovery.cursor && discovery.applied && <button className="ghost-btn" type="button" disabled={discovery.loading} onClick={() => void discovery.load(discovery.applied!, discovery.cursor!)}>Ver más jugadores</button>}
    </section>}
    {tab === 'profile' && <div className="games-profile-grid">
      <form className="surface-card games-form games-player-profile" onSubmit={saveProfile}><h3>Perfil de jugador</h3><fieldset className="games-profile-fieldset"><legend>Tu identidad</legend><label>Foto de perfil (JPG, PNG o WEBP)<input className="text-input" type="file" accept="image/jpeg,image/png,image/webp" disabled={busy} onChange={(event) => { uploadPhoto(event.target.files?.[0], 'avatar'); event.target.value = '' }} /></label>{avatarUrl && <SafeAvatar className="games-profile-avatar" url={avatarUrl} mediaType={avatarMediaType} name={displayName || "Tu perfil"} size="lg" />}<label>Nombre de jugador<input className="text-input" required maxLength={40} value={displayName} onChange={(event) => setDisplayName(event.target.value)} /></label><label>Presentación<textarea className="text-input" maxLength={500} value={bio} onChange={(event) => setBio(event.target.value)} /></label></fieldset><fieldset className="games-profile-fieldset"><legend>Cómo juegas</legend><label>Región<input className="text-input" maxLength={40} value={region} onChange={(event) => setRegion(event.target.value)} /></label><label>Idiomas, separados por coma<input className="text-input" value={languages} onChange={(event) => setLanguages(event.target.value)} placeholder="Español, inglés" /></label><label>Estilo de juego<select className="text-input" value={playStyle} onChange={(event) => setPlayStyle(event.target.value)}><option value="casual">Casual</option><option value="competitive">Competitivo</option><option value="mixed">Ambos</option></select></label><label className="games-check"><input type="checkbox" checked={voicePreference} onChange={(event) => setVoicePreference(event.target.checked)} /> Me gusta usar voz</label></fieldset><fieldset className="games-profile-fieldset"><legend>Privacidad y visibilidad</legend><label className="games-check"><input type="checkbox" checked={adultConfirmed} onChange={(event) => setAdultConfirmed(event.target.checked)} /> Confirmo que soy mayor de edad</label><label className="games-check"><input type="checkbox" checked={discoverable} disabled={!adultConfirmed} onChange={(event) => setDiscoverable(event.target.checked)} /> Mostrar mi perfil en la búsqueda</label><label>Visibilidad del ID de juego<select className="text-input" value={gameIdVisibility} onChange={(event) => setGameIdVisibility(event.target.value)}><option value="private">Solo yo</option><option value="connections">Conexiones</option><option value="public">Todos</option></select></label><label>Visibilidad de horarios<select className="text-input" value={availabilityVisibility} onChange={(event) => setAvailabilityVisibility(event.target.value)}><option value="private">Solo yo</option><option value="connections">Conexiones</option><option value="public">Todos</option></select></label></fieldset>{photosEnabled && <div className="games-form"><label>Capturas de juego (máximo 4)<input className="text-input" type="file" accept="image/jpeg,image/png,image/webp" disabled={busy || photos.length >= 4} onChange={(event) => { uploadPhoto(event.target.files?.[0], 'photo'); event.target.value = '' }} /></label><div className="games-photo-grid">{photos.map((url, index) => <div className="games-photo" key={`${url}-${index}`}><StreamingImage src={url} alt={`Captura de juego ${index + 1}`} /><button className="ghost-btn" type="button" disabled={busy} onClick={() => { mediaDraftRef.current = true; setPhotos((current) => current.filter((_, position) => position !== index)) }}>Quitar</button></div>)}</div></div>}<button className="primary-btn" disabled={busy || !profile} type="submit">Guardar perfil</button></form>
      <section className="surface-card games-form"><h3>Mis juegos</h3><form onSubmit={(event) => { event.preventDefault(); void act(async () => { const result = await request<{ items: Game[] }>(`/catalog?q=${encodeURIComponent(catalogQuery.trim())}&limit=50`); setCatalog(result.items) }, 'Catálogo actualizado.') }} className="games-catalog-search"><label>Buscar en el catálogo<input className="text-input" value={catalogQuery} onChange={(event) => setCatalogQuery(event.target.value)} placeholder="Nombre del juego" /></label><button className="ghost-btn" disabled={busy} type="submit">Buscar</button></form><form onSubmit={addGame} className="games-form"><label>Juego<select className="text-input" value={selectedGame} onChange={(event) => { setSelectedGame(event.target.value); setSelectedPlatform(''); setSelectedRank(''); setSelectedRole('') }}><option value="">Elige un juego</option>{catalog.map((item) => <option key={item.id} value={item.id}>{item.name}</option>)}</select></label><label>Plataforma<select className="text-input" value={selectedPlatform} onChange={(event) => setSelectedPlatform(event.target.value)}><option value="">Elige una plataforma</option>{game?.platforms.map((platform) => <option key={platform} value={platform}>{platform}</option>)}</select></label>{rankOptions.length > 0 && <label>Rango<select className="text-input" value={selectedRank} onChange={(event) => setSelectedRank(event.target.value)}><option value="">Sin rango</option>{rankOptions.map(([value, label]) => <option key={value} value={value}>{label}</option>)}</select></label>}{roleOptions.length > 0 && <label>Rol<select className="text-input" value={selectedRole} onChange={(event) => setSelectedRole(event.target.value)}><option value="">Sin rol</option>{roleOptions.map(([value, label]) => <option key={value} value={value}>{label}</option>)}</select></label>}<label>ID del juego (opcional)<input className="text-input" maxLength={80} value={gameUserId} onChange={(event) => setGameUserId(event.target.value)} /></label><button className="primary-btn" type="submit" disabled={busy}>Agregar juego</button></form><div className="games-list">{profile?.games.map((item) => <div className="games-list-row" key={item.id}><span><strong>{item.gameName}</strong><small>{item.platform}{item.rank ? ` · ${item.rank}` : ''}{item.role ? ` · ${item.role}` : ''}</small></span><button className="ghost-btn" type="button" disabled={busy} onClick={() => void act(async () => { await request(`/games/${encodeURIComponent(item.id)}`, 'DELETE'); await loadProfile(true) }, 'Juego eliminado.')}>Quitar</button></div>)}{!profile?.games.length && <p>Aún no agregaste juegos.</p>}</div></section>
      <form className="surface-card games-form" onSubmit={savePreferences}><h3>Preferencias de partida</h3><label>Tolerancia de rango: {rankTolerance}<input type="range" min="0" max="5" step="1" value={rankTolerance} onChange={(event) => setRankTolerance(Number(event.target.value))} /></label><label>Nivel de exploración: {discoveryLevel}%<input type="range" min="0" max="100" step="10" value={discoveryLevel} onChange={(event) => setDiscoveryLevel(Number(event.target.value))} /></label><label className="games-check"><input type="checkbox" checked={voiceRequired} onChange={(event) => setVoiceRequired(event.target.checked)} /> Solo jugadores con voz</label><button className="primary-btn" type="submit" disabled={busy || !profile}>Guardar preferencias</button></form>
      <section className="surface-card games-form"><h3>Disponibilidad semanal</h3><p>Comparte solo horarios aproximados; nunca tu ubicación exacta.</p><form className="games-availability-form" onSubmit={(event) => { event.preventDefault(); if (availability.length >= 28) { setError('Puedes guardar hasta 28 horarios.'); return } saveAvailability([...availability, { dayOfWeek: availableDay, startMinute: minutesOf(availableStart), endMinute: minutesOf(availableEnd), timezone: profile?.timezone || Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC' }]) }}><label>Día<select className="text-input" value={availableDay} onChange={(event) => setAvailableDay(Number(event.target.value))}>{weekdays.map((name, index) => <option key={name} value={index}>{name}</option>)}</select></label><label>Desde<input className="text-input" type="time" value={availableStart} onChange={(event) => setAvailableStart(event.target.value)} /></label><label>Hasta<input className="text-input" type="time" value={availableEnd} onChange={(event) => setAvailableEnd(event.target.value)} /></label><button className="primary-btn" type="submit" disabled={busy}>Agregar horario</button></form><div className="games-list">{availability.map((entry, index) => <div className="games-list-row" key={`${entry.dayOfWeek}-${entry.startMinute}-${index}`}><span><strong>{weekdays[entry.dayOfWeek]}</strong><small>{timeOf(entry.startMinute)}–{timeOf(entry.endMinute)} · {entry.timezone}</small></span><button className="ghost-btn" type="button" disabled={busy} onClick={() => saveAvailability(availability.filter((_, position) => position !== index))}>Quitar</button></div>)}{availability.length === 0 && <p>Sin horarios configurados.</p>}</div></section>
    </div>}
    {tab === 'connections' && <section className="games-section" aria-labelledby="games-activity-title">
      <div className="games-section-heading games-section-heading-actions">
        <Inbox aria-hidden="true" /><div><h3 id="games-activity-title">Actividad</h3><p>Solicitudes, coincidencias y conversaciones.</p></div>
        <button className="ghost-btn" type="button" disabled={busy || activityLoading} onClick={() => void refreshActivity()}>Actualizar</button>
      </div>
      {activityLoading && <p className="surface-card games-empty" role="status">Cargando actividad…</p>}
      {!activityLoading && activityError && <div className="games-alert error" role="alert">
        <strong>No se pudo cargar la actividad. </strong>{activityError}{' '}
        <button type="button" className="ghost-btn" disabled={busy} onClick={() => void refreshActivity()}>Reintentar</button>
      </div>}
      {!activityLoading && (!activityError || connections.length > 0 || matches.length > 0) && <div className="games-activity-grid">
      <section className="surface-card games-form games-activity-card">
        <h4><Inbox size={19} aria-hidden="true" />Solicitudes recibidas {incoming.length > 0 && <span className="games-count">{incoming.length}</span>}</h4>
        {!incoming.length && <p className="games-empty">Aún no tienes solicitudes recibidas.</p>}
        {incoming.map(connection => <div className="games-list-row" key={connection.id}>
          <div className="games-activity-person"><SafeAvatar name={connection.otherName} /><span><strong>{connection.otherName}</strong>{connection.message && <small>{connection.message}</small>}</span></div>
          <div className="games-row-actions"><button className="primary-btn" type="button" disabled={busy} onClick={() => decideConnection(connection, 'ACCEPT')}>Aceptar</button><button className="ghost-btn" type="button" disabled={busy} onClick={() => decideConnection(connection, 'REJECT')}>Rechazar</button></div>
        </div>)}
      </section>
      <section className="surface-card games-form games-activity-card">
        <h4><Send size={18} aria-hidden="true" />Solicitudes enviadas</h4>
        {!outgoing.length && <p className="games-empty">Aún no tienes solicitudes pendientes.</p>}
        {outgoing.map(connection => <div className="games-list-row" key={connection.id}><span><strong>{connection.otherName}</strong><small>Pendiente de respuesta</small></span></div>)}
      </section>
      <section className="surface-card games-form games-activity-card">
        <h4><MessageCircle size={18} aria-hidden="true" />Conversaciones de juego</h4>
        {!chats.length && <p className="games-empty">Al aceptar una solicitud, podrás abrir un chat aquí.</p>}
        {chats.map(connection => <div className="games-list-row" key={connection.id}><strong>{connection.otherName}</strong><Link className="ghost-btn" to={`/chat/${encodeURIComponent(connection.otherUserId)}`}>Abrir chat</Link></div>)}
      </section>
      {pendingMatches.length > 0 && <section className="surface-card games-form games-activity-card"><h4><CalendarDays size={18} aria-hidden="true" />Coincidencias por revisar <span className="games-count">{pendingMatches.length}</span></h4><button className="ghost-btn" type="button" onClick={() => selectSection('matches')}>Revisar partidas</button></section>}
      </div>}
    </section>}
    {tab === 'matches' && <section className="games-section">
      <article className="surface-card games-form games-match-setup">
        <div className="games-section-heading"><CalendarDays aria-hidden="true" /><div><h3>Buscar partida</h3><p>{activeSession ? 'Sesión activa' : 'Emparejamiento de PlayMatch'}</p></div></div>
        <p>{session?.status === 'SEARCHING' ? 'Estamos buscando compañeros compatibles.' : session?.status === 'MATCH_FOUND' ? 'Encontramos una coincidencia. Puedes revisar el perfil antes de decidir.' : readiness.message}</p>
        {session?.status === 'SEARCHING' && <progress aria-label="Búsqueda en curso" />}
        {readiness.ready && !activeSession && <><div className="games-match-criteria"><span>Se buscará con</span><strong>{matchCriteria.length ? matchCriteria.join(' · ') : 'Tus juegos registrados'}</strong></div><div className="games-row-actions"><button className="primary-btn" disabled={busy} type="button" onClick={startMatch}>Buscar compañero</button><button className="ghost-btn" type="button" onClick={() => selectSection('discover')}>Ajustar criterios</button></div></>}
        {!readiness.ready && !readiness.loading && !activeSession && <button className="primary-btn" type="button" onClick={() => selectSection('profile')}>Abrir perfil gamer</button>}
        {activeSession && <button className="ghost-btn" disabled={busy} type="button" onClick={cancelMatch}>Cancelar búsqueda</button>}
      </article>
      <section className="surface-card games-form games-activity-card">
        <h4><UsersRound size={19} aria-hidden="true" />Coincidencias por decidir {profilesToReview.length > 0 && <span className="games-count">{profilesToReview.length}</span>}</h4>
        {!profilesToReview.length && <p className="games-empty">Las coincidencias aparecerán cuando alguien cumpla tus criterios.</p>}
        {profilesToReview.map(match => <div className="games-list-row" key={match.id}>
          <div className="games-activity-person"><SafeAvatar url={match.player?.avatarUrl} name={match.player?.displayName || 'Jugador'} /><span><strong>{match.player?.displayName || 'Jugador'}</strong><small>{match.score}% compatible</small></span></div>
          <div className="games-row-actions">
            {match.player?.userId && <Link className="ghost-btn" to={`/app/games/player/${encodeURIComponent(match.player.userId)}`}>Ver perfil</Link>}
            {match.status === 'PENDING' && acceptedMatches.includes(match.id) && <small>Esperando confirmación</small>}
            {match.status === 'PENDING' && <>{!acceptedMatches.includes(match.id) && <button className="primary-btn" type="button" disabled={busy} onClick={() => decideMatch(match, 'ACCEPT')}>Aceptar</button>}
              <button className="ghost-btn" type="button" disabled={busy} onClick={() => decideMatch(match, 'SKIP')}>Omitir</button></>}
          </div>
        </div>)}
      </section>
    </section>}

  </div>
}
