import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { loadOwnProfileVisits, type ProfileVisit } from '../core/profileVisits'
import { buildProfileQrPayload } from '../core/socialLinks'
import { formatDateTime, friendlyError } from '../core/utils'
import { QrCodeTile } from './QrCodeTile'
import { SafeAvatar } from './SafeAvatar'
import { subscribeUbuntuRealtime } from '../core/ubuntuRealtime'
import { useAuth } from '../core/auth/AuthProvider'
export function ProfileTools(props: { profileId: string; name: string; showButtons?: boolean }) {
  return <ProfileToolsContent key={props.profileId} {...props} />
}
function ProfileToolsContent({
  profileId,
  name,
  showButtons = true,
}: {
  profileId: string
  name: string
  showButtons?: boolean
}) {
  const { user } = useAuth()
  const [mode, setMode] = useState<'qr' | 'visits' | null>(null),
    [items, setItems] = useState<ProfileVisit[]>([]),
    [enabled, setEnabled] = useState(true),
    [loading, setLoading] = useState(false),
    [error, setError] = useState(''),
    [attempt, setAttempt] = useState(0)
  useEffect(() => {
    if (mode !== 'visits') return
    let active = true
    void loadOwnProfileVisits(profileId)
      .then((result) => {
        if (active) {
          setItems(result.items)
          setEnabled(result.enabled)
        }
      })
      .catch((e) => {
        if (active) setError(friendlyError(e, 'No se pudieron cargar las visitas.'))
      })
      .finally(() => {
        if (active) setLoading(false)
      })
    return () => {
      active = false
    }
  }, [mode, profileId, attempt])
  useEffect(() => {
    if (mode !== 'visits' || !user?.id) return
    const refresh = () => { if (!document.hidden) setAttempt(value => value + 1) }
    const stop = subscribeUbuntuRealtime({ channel: `user:${user.id}`, onEvent: event => { if (event.payload.event === 'profile_views_changed') refresh() } })
    const timer = setInterval(refresh, 15000)
    window.addEventListener('focus', refresh)
    return () => { stop(); clearInterval(timer); window.removeEventListener('focus', refresh) }
  }, [mode, user?.id])
  useEffect(() => {
    const visits = () => { setLoading(true); setError(''); setItems([]); setMode('visits') },
      qr = () => setMode('qr')
    window.addEventListener('novvin:profile-visits', visits)
    window.addEventListener('novvin:profile-qr', qr)
    return () => {
      window.removeEventListener('novvin:profile-visits', visits)
      window.removeEventListener('novvin:profile-qr', qr)
    }
  }, [])
  return (
    <>
      {showButtons ? (
        <div className="row-actions wrap">
          <button className="ghost-btn" type="button" onClick={() => setMode('qr')}>
            Mi QR
          </button>
          <button className="ghost-btn" type="button" onClick={() => { setLoading(true); setError(''); setItems([]); setMode('visits') }}>
            Visitas recientes
          </button>
        </div>
      ) : null}
      {mode ? (
        <div className="app-dialog-backdrop" role="presentation" onMouseDown={() => setMode(null)}>
          <section
            className="app-dialog collection-info"
            role="dialog"
            aria-modal="true"
            aria-label={mode === 'qr' ? 'Mi QR' : 'Visitas recientes'}
            onMouseDown={(event) => event.stopPropagation()}
          >
            <header className="row-between">
              <h2>{mode === 'qr' ? 'Mi QR' : 'Visitas recientes'}</h2>
              <button
                type="button"
                className="icon-btn"
                aria-label="Cerrar"
                onClick={() => setMode(null)}
              >
                ×
              </button>
            </header>
            {mode === 'qr' ? (
              <QrCodeTile
                value={buildProfileQrPayload(profileId)}
                title={name}
                description="Escanea este QR para abrir mi perfil en Novvin."
              />
            ) : (
              <>
                {loading ? (
                  <p>Cargando visitas…</p>
                ) : error ? (
                  <>
                    <p role="alert">{error}</p>
                    <button
                      className="ghost-btn"
                      type="button"
                      onClick={() => { setLoading(true); setError(''); setItems([]); setAttempt((n) => n + 1) }}
                    >
                      Reintentar
                    </button>
                  </>
                ) : !enabled ? (
                  <p>
                    El historial de visitas está desactivado. Puedes cambiarlo en{' '}
                    <Link to="/app/settings">Ajustes</Link>.
                  </p>
                ) : items.length === 0 ? (
                  <p>No hay visitas recientes para mostrar.</p>
                ) : (
                  items.map((item) => (
                    <Link
                      className="collection-member"
                      to={`/app/profile/${encodeURIComponent(item.id)}`}
                      key={item.id}
                    >
                      <SafeAvatar url={item.avatar} name={item.name} />
                      <span>
                        <strong>{item.name}</strong>
                        <small>{formatDateTime(item.time)}</small>
                      </span>
                    </Link>
                  ))
                )}
                <p className="muted-line">
                  Se muestran las visitas permitidas por las preferencias de privacidad de ambas
                  personas.
                </p>
              </>
            )}
          </section>
        </div>
      ) : null}
    </>
  )
}
