import { useCallback, useEffect, useMemo, useRef, useState, type FormEvent } from 'react'
import {
  Eye,
  EyeOff,
  LocateFixed,
  MapPin,
  MessageCircle,
  Radar,
  RefreshCw,
  Send,
  ShieldCheck,
  UserRoundSearch,
  X,
} from 'lucide-react'
import { Link } from 'react-router-dom'
import { SafeAvatar } from '../../components/SafeAvatar'
import { StatusView } from '../../components/StatusView'
import { useAuth } from '../../core/auth/AuthProvider'
import {
  cancelFallbackRandomChatMatch,
  disableFallbackProximity,
  enableFallbackProximity,
  endFallbackRandomSession,
  loadFallbackNearbyProfiles,
  loadFallbackRandomChatStatus,
  loadFallbackRandomMessages,
  loadFallbackRandomSession,
  matchFallbackRandomChat,
  sendFallbackRandomMessage,
  type FallbackNearbyProfile,
  type FallbackRandomMessage,
  type FallbackRandomSession,
} from '../../core/fallbackData'
import { subscribeUbuntuRealtime, subscribeUbuntuRealtimeMany } from '../../core/ubuntuRealtime'
import { friendlyError } from '../../core/utils'

type Coordinates = { latitude: number; longitude: number }
type MatchStatus = 'idle' | 'waiting' | 'matched'

function currentCoordinates(): Promise<Coordinates> {
  if (!navigator.geolocation) return Promise.reject(new Error('Este navegador no ofrece ubicación.'))
  return new Promise((resolve, reject) => {
    navigator.geolocation.getCurrentPosition(
      (position) => resolve({
        latitude: position.coords.latitude,
        longitude: position.coords.longitude,
      }),
      (error) => {
        const message = error.code === error.PERMISSION_DENIED
          ? 'Permite la ubicación para usar personas cerca.'
          : 'No se pudo obtener tu ubicación. Intenta de nuevo en un lugar con mejor señal.'
        reject(new Error(message))
      },
      { enableHighAccuracy: false, maximumAge: 5 * 60_000, timeout: 15_000 },
    )
  })
}

function radarPosition(index: number): { left: string; top: string } {
  const angle = (index * 137.508) * Math.PI / 180
  const radius = 18 + (index % 4) * 14
  return {
    left: `${50 + Math.cos(angle) * radius}%`,
    top: `${50 + Math.sin(angle) * radius}%`,
  }
}

export function ProximityPage() {
  const { user, isFallbackSession } = useAuth()
  const [radiusKm, setRadiusKm] = useState(10)
  const [gender, setGender] = useState('any')
  const [lookingFor, setLookingFor] = useState('any')
  const [enabled, setEnabled] = useState(false)
  const [activating, setActivating] = useState(false)
  const [nearby, setNearby] = useState<FallbackNearbyProfile[]>([])
  const [loadingNearby, setLoadingNearby] = useState(false)
  const [matchStatus, setMatchStatus] = useState<MatchStatus>('idle')
  const [session, setSession] = useState<FallbackRandomSession | null>(null)
  const [messages, setMessages] = useState<FallbackRandomMessage[]>([])
  const [messageDraft, setMessageDraft] = useState('')
  const [sending, setSending] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [info, setInfo] = useState<string | null>(null)
  const coordinatesRef = useRef<Coordinates | null>(null)
  const messagesEndRef = useRef<HTMLDivElement | null>(null)

  const loadNearby = useCallback(async (silent = false) => {
    if (!isFallbackSession) return
    if (!silent) setLoadingNearby(true)
    try {
      const response = await loadFallbackNearbyProfiles(radiusKm)
      setNearby(response.items)
      setEnabled(true)
      setError(null)
    } catch (caught) {
      const message = friendlyError(caught, 'No se pudieron cargar personas cerca')
      if (!message.includes('PROXIMITY_NOT_ENABLED')) setError(message)
    } finally {
      setLoadingNearby(false)
    }
  }, [isFallbackSession, radiusKm])

  const loadSessionMessages = useCallback(async (sessionId: string) => {
    try {
      const response = await loadFallbackRandomMessages(sessionId)
      setMessages(response.items)
    } catch (caught) {
      setError(friendlyError(caught, 'No se pudo sincronizar el chat aleatorio'))
    }
  }, [])

  const refreshSession = useCallback(async (sessionId: string) => {
    try {
      const response = await loadFallbackRandomSession(sessionId)
      if (response.session.status !== 'active') {
        setSession(null)
        setMatchStatus('idle')
        setMessages([])
        return
      }
      setSession(response.session)
      setMatchStatus('matched')
    } catch (caught) {
      const message = friendlyError(caught, 'No se pudo actualizar la conversación')
      if (message.includes('SESSION_NOT_FOUND')) {
        setSession(null)
        setMatchStatus('idle')
        setMessages([])
      } else {
        setError(message)
      }
    }
  }, [])

  const refreshMatchStatus = useCallback(async () => {
    if (!isFallbackSession) return
    try {
      const response = await loadFallbackRandomChatStatus()
      setMatchStatus(response.status)
      if (response.session) {
        setSession(response.session)
        await loadSessionMessages(response.session.id)
      } else if (response.status !== 'matched') {
        setSession(null)
      }
    } catch (caught) {
      setError(friendlyError(caught, 'No se pudo consultar la búsqueda aleatoria'))
    }
  }, [isFallbackSession, loadSessionMessages])

  useEffect(() => {
    void loadNearby(true)
    void refreshMatchStatus()
  }, [loadNearby, refreshMatchStatus])

  useEffect(() => {
    if (matchStatus !== 'waiting') return
    const interval = window.setInterval(() => void refreshMatchStatus(), 4_000)
    return () => window.clearInterval(interval)
  }, [matchStatus, refreshMatchStatus])

  useEffect(() => {
    if (!user || !isFallbackSession) return
    return subscribeUbuntuRealtime({
      channel: `random-chat-user:${user.id.toLowerCase()}`,
      onEvent: () => void refreshMatchStatus(),
    })
  }, [isFallbackSession, refreshMatchStatus, user])

  useEffect(() => {
    if (!session?.id) return
    void loadSessionMessages(session.id)
    const unsubscribe = subscribeUbuntuRealtimeMany({
      channels: [`random-chat:${session.id}`],
      onEvent: () => {
        void refreshSession(session.id)
        void loadSessionMessages(session.id)
      },
    })
    const interval = window.setInterval(() => void loadSessionMessages(session.id), 5_000)
    return () => {
      unsubscribe()
      window.clearInterval(interval)
    }
  }, [loadSessionMessages, refreshSession, session?.id])

  useEffect(() => {
    messagesEndRef.current?.scrollIntoView({ behavior: 'smooth', block: 'nearest' })
  }, [messages])

  async function activate(): Promise<boolean> {
    if (activating) return false
    setActivating(true)
    setError(null)
    setInfo('Solicitando ubicación...')
    try {
      const coordinates = coordinatesRef.current ?? await currentCoordinates()
      coordinatesRef.current = coordinates
      await enableFallbackProximity({
        ...coordinates,
        radiusKm,
        gender,
        lookingFor,
        randomEnabled: matchStatus === 'waiting',
      })
      setEnabled(true)
      setInfo('Radar activo. Tu ubicación exacta nunca se muestra a otras personas.')
      await loadNearby()
      return true
    } catch (caught) {
      setError(friendlyError(caught, 'No se pudo activar personas cerca'))
      setInfo(null)
      return false
    } finally {
      setActivating(false)
    }
  }

  async function disable() {
    try {
      await disableFallbackProximity()
      setEnabled(false)
      setNearby([])
      setMatchStatus('idle')
      setSession(null)
      setMessages([])
      coordinatesRef.current = null
      setInfo('Ubicación y búsqueda aleatoria desactivadas.')
    } catch (caught) {
      setError(friendlyError(caught, 'No se pudo desactivar el radar'))
    }
  }

  async function startMatching() {
    setError(null)
    try {
      const response = await matchFallbackRandomChat({ gender, lookingFor })
      setMatchStatus(response.status)
      setSession(response.session ?? null)
      setInfo(response.status === 'waiting' ? 'Buscando una persona compatible en todo el mundo...' : 'Conexión anónima creada.')
      if (response.session) await loadSessionMessages(response.session.id)
    } catch (caught) {
      setError(friendlyError(caught, 'No se pudo iniciar la búsqueda aleatoria'))
    }
  }

  async function cancelMatching() {
    try {
      await cancelFallbackRandomChatMatch()
      setMatchStatus('idle')
      setInfo('Búsqueda cancelada.')
    } catch (caught) {
      setError(friendlyError(caught, 'No se pudo cancelar la búsqueda'))
    }
  }

  async function sendMessage(event: FormEvent) {
    event.preventDefault()
    const content = messageDraft.trim()
    if (!session || !content || sending) return
    const clientMessageId = crypto.randomUUID()
    const optimistic: FallbackRandomMessage = {
      id: `pending-${clientMessageId}`,
      client_message_id: clientMessageId,
      sender: 'me',
      content,
      created_at: new Date().toISOString(),
    }
    setMessages((current) => [...current, optimistic])
    setMessageDraft('')
    setSending(true)
    try {
      await sendFallbackRandomMessage(session.id, { content, clientMessageId })
      await loadSessionMessages(session.id)
    } catch (caught) {
      setMessages((current) => current.filter((message) => message.id !== optimistic.id))
      setMessageDraft(content)
      setError(friendlyError(caught, 'No se pudo enviar el mensaje'))
    } finally {
      setSending(false)
    }
  }

  async function endSession() {
    if (!session) return
    try {
      await endFallbackRandomSession(session.id)
    } catch (caught) {
      setError(friendlyError(caught, 'No se pudo cerrar la conversación'))
      return
    }
    setSession(null)
    setMessages([])
    setMatchStatus('idle')
    setInfo('Conversación finalizada. Puedes buscar otra persona cuando quieras.')
  }

  const peer = session?.peer
  const radarProfiles = useMemo(() => nearby.slice(0, 12), [nearby])

  if (!isFallbackSession) {
    return <StatusView title="Proximidad no disponible" description="Inicia sesión con el servidor de producción para usar el radar privado." />
  }

  return (
    <section className="section-stack proximity-page">
      <div className="row-between proximity-heading">
        <div>
          <span className="brand-tag"><Radar aria-hidden="true" /> Proximidad</span>
          <h2>Personas cerca</h2>
          <p>Distancias aproximadas, bloqueo respetado y ubicación exacta protegida.</p>
        </div>
        {enabled ? (
          <button className="ghost-btn danger-text" type="button" onClick={() => void disable()}><X aria-hidden="true" /> Desactivar</button>
        ) : null}
      </div>

      {error ? <StatusView title="No se pudo completar la operación" description={error} /> : null}
      {info ? <div className="offline-banner syncing">{info}</div> : null}

      <div className="proximity-layout">
        <section className="proximity-radar-panel">
          <div className={enabled ? 'proximity-radar active' : 'proximity-radar'} aria-label={`${nearby.length} personas dentro de ${radiusKm} kilómetros`}>
            <span className="radar-sweep" />
            <span className="radar-ring ring-one" />
            <span className="radar-ring ring-two" />
            <span className="radar-ring ring-three" />
            <span className="radar-center"><LocateFixed aria-hidden="true" /></span>
            {radarProfiles.map((profile, index) => (
              <Link className="radar-profile-dot" style={radarPosition(index)} key={profile.user_id} to={`/app/profile/${encodeURIComponent(profile.user_id)}`} title={`${profile.display_name}, ${profile.distance_km} km`}>
                <SafeAvatar url={profile.avatar_url} name={profile.display_name} size="sm" />
              </Link>
            ))}
          </div>
          <div className="proximity-radius-control">
            <div className="row-between"><strong>Radio</strong><span>{radiusKm} km</span></div>
            <input type="range" min={1} max={20} step={0.5} value={radiusKm} onChange={(event) => setRadiusKm(Number(event.target.value))} />
            <button className="primary-btn" type="button" onClick={() => void activate()} disabled={activating}>
              <MapPin aria-hidden="true" /> {activating ? 'Ubicando...' : enabled ? 'Aplicar radio' : 'Activar radar'}
            </button>
          </div>
        </section>

        <section className="proximity-results-panel section-stack">
          <div className="row-between">
            <h3>{nearby.length} cerca</h3>
            <button className="icon-btn" type="button" onClick={() => void loadNearby()} disabled={!enabled || loadingNearby} title="Actualizar" aria-label="Actualizar personas cerca"><RefreshCw aria-hidden="true" /></button>
          </div>
          {!enabled ? (
            <div className="proximity-private-note"><ShieldCheck aria-hidden="true" /><p>La ubicación solo se guarda temporalmente y las demás personas reciben una distancia aproximada.</p></div>
          ) : nearby.length === 0 ? (
            <p className="muted-copy">No hay perfiles visibles dentro del radio elegido.</p>
          ) : (
            <div className="nearby-profile-list">
              {nearby.map((profile) => (
                <article className="nearby-profile-row" key={profile.user_id}>
                  <SafeAvatar url={profile.avatar_url} name={profile.display_name} />
                  <div>
                    <strong>{profile.display_name}</strong>
                    <small>{profile.distance_km} km · {[profile.city, profile.country].filter(Boolean).join(', ') || 'Ubicación privada'}</small>
                    <div className="chip-row">{profile.interests.slice(0, 3).map((interest) => <span className="folder-tag" key={interest}>{interest}</span>)}</div>
                  </div>
                  <div className="nearby-row-actions">
                    <Link className="icon-btn" to={`/app/profile/${encodeURIComponent(profile.user_id)}`} title="Ver perfil" aria-label={`Ver perfil de ${profile.display_name}`}><Eye aria-hidden="true" /></Link>
                    <Link className="icon-btn" to={`/chat/${encodeURIComponent(profile.user_id)}`} title="Enviar mensaje" aria-label={`Enviar mensaje a ${profile.display_name}`}><MessageCircle aria-hidden="true" /></Link>
                  </div>
                </article>
              ))}
            </div>
          )}
        </section>
      </div>

      <section className="surface-card section-stack random-match-panel">
        <div className="row-between">
          <div><span className="brand-tag"><UserRoundSearch aria-hidden="true" /> Chat aleatorio</span><h3>Conexión anónima gradual</h3></div>
          <span className={`match-status ${matchStatus}`}>{matchStatus === 'waiting' ? 'Buscando' : matchStatus === 'matched' ? 'Conectado' : 'Disponible'}</span>
        </div>

        {!session ? (
          <div className="random-match-setup">
            <label>Me identifico como
              <select value={gender} onChange={(event) => setGender(event.target.value)}>
                <option value="any">Prefiero no indicarlo</option>
                <option value="man">Hombre</option>
                <option value="woman">Mujer</option>
              </select>
            </label>
            <label>Quiero conectar con
              <select value={lookingFor} onChange={(event) => setLookingFor(event.target.value)}>
                <option value="any">Cualquier persona</option>
                <option value="man">Hombres</option>
                <option value="woman">Mujeres</option>
              </select>
            </label>
            {matchStatus === 'waiting' ? (
              <button className="ghost-btn" type="button" onClick={() => void cancelMatching()}>Cancelar búsqueda</button>
            ) : (
              <button className="primary-btn" type="button" onClick={() => void startMatching()}><UserRoundSearch aria-hidden="true" /> Buscar persona</button>
            )}
          </div>
        ) : (
          <div className="random-chat-shell">
            <header className="random-peer-header">
              {peer?.avatar_url ? <SafeAvatar url={peer.avatar_url} name={peer.display_name} /> : <span className="anonymous-avatar"><EyeOff aria-hidden="true" /></span>}
              <div>
                <strong>{peer?.display_name || peer?.alias || 'Persona anónima'}</strong>
                <small>Nivel visible {session.peer_reveal_level}/3</small>
                <div className="chip-row">{[...(peer?.interests || []), ...(peer?.hobbies || [])].slice(0, 5).map((item) => <span className="folder-tag" key={item}>{item}</span>)}</div>
              </div>
              <div className="random-peer-actions">
                {peer?.user_id ? <Link className="ghost-btn" to={`/app/profile/${encodeURIComponent(peer.user_id)}`}>Ver perfil</Link> : null}
                <small className="muted-copy">Los niveles se desbloquean mientras conversan</small>
                <button className="icon-btn danger-text" type="button" onClick={() => void endSession()} title="Finalizar conversación" aria-label="Finalizar conversación"><X aria-hidden="true" /></button>
              </div>
            </header>
            <div className="random-message-list">
              {messages.length === 0 ? <p className="muted-copy">La conversación empieza anónima. Comparte solo lo que decidas.</p> : null}
              {messages.map((message) => <div className={message.sender === 'me' ? 'random-message mine' : 'random-message'} key={message.id}><p>{message.content}</p></div>)}
              <div ref={messagesEndRef} />
            </div>
            <form className="random-message-composer" onSubmit={sendMessage}>
              <input maxLength={1000} value={messageDraft} onChange={(event) => setMessageDraft(event.target.value)} placeholder="Escribe un mensaje..." />
              <button className="icon-btn" type="submit" disabled={!messageDraft.trim() || sending} title="Enviar" aria-label="Enviar mensaje"><Send aria-hidden="true" /></button>
            </form>
          </div>
        )}
      </section>
    </section>
  )
}
