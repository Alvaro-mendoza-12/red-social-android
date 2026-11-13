import { useEffect, useMemo, useRef, useState, type FormEvent } from 'react'
import { Link, useNavigate, useSearchParams } from 'react-router-dom'
import { StatusView } from '../../components/StatusView'
import { fallbackApiJson, isFallbackAuthConfigured } from '../../core/auth/fallbackAuth'
import { signUpAndBootstrapProfile } from '../../core/auth/sessionBootstrap'
import { supabase } from '../../core/supabase'
import { friendlyError } from '../../core/utils'
import { useWebPreferences } from '../../core/webPreferences'
import { authReturnPath, safeAuthDestination } from '../../core/auth/loginReturnPath'

const MINIMUM_REGISTRATION_AGE = 13
const turnstileScriptSrc = 'https://challenges.cloudflare.com/turnstile/v0/api.js?render=explicit'

type TurnstileApi = {
  render: (
    container: HTMLElement,
    options: {
      sitekey: string
      callback?: (token: string) => void
      'expired-callback'?: () => void
      'error-callback'?: () => void
    },
  ) => string
  remove?: (widgetId: string) => void
}

function daysInUtcMonth(year: number, monthOneBased: number): number {
  return new Date(Date.UTC(year, monthOneBased, 0)).getUTCDate()
}

function isBirthDateMinimumAge(rawValue: string, minAge = MINIMUM_REGISTRATION_AGE): boolean {
  const match = /^(\d{4})-(\d{2})-(\d{2})$/.exec(rawValue.trim())
  if (!match) return false
  const year = Number(match[1])
  const month = Number(match[2])
  const day = Number(match[3])
  if (year < 1900 || month < 1 || month > 12 || day < 1) return false
  const date = new Date(Date.UTC(year, month - 1, day))
  if (date.getUTCFullYear() !== year || date.getUTCMonth() !== month - 1 || date.getUTCDate() !== day) {
    return false
  }
  const now = new Date()
  const todayYear = now.getUTCFullYear()
  const todayMonth = now.getUTCMonth() + 1
  const todayDay = now.getUTCDate()
  const todayUtc = Date.UTC(todayYear, todayMonth - 1, todayDay)
  if (date.getTime() > todayUtc) return false
  const cutoffYear = todayYear - minAge
  const cutoffDay = Math.min(todayDay, daysInUtcMonth(cutoffYear, todayMonth))
  return date.getTime() <= Date.UTC(cutoffYear, todayMonth - 1, cutoffDay)
}

function maxEligibleBirthDate(): string {
  const now = new Date()
  const year = now.getUTCFullYear() - MINIMUM_REGISTRATION_AGE
  const month = String(now.getUTCMonth() + 1).padStart(2, '0')
  const maxDay = Math.min(now.getUTCDate(), daysInUtcMonth(year, now.getUTCMonth() + 1))
  return `${year}-${month}-${String(maxDay).padStart(2, '0')}`
}

export function RegisterPage() {
  const navigate = useNavigate()
  const [searchParams] = useSearchParams()
  const nextPath = safeAuthDestination(searchParams.get('next'))
  const client = supabase
  const [webPreferences] = useWebPreferences()
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [displayName, setDisplayName] = useState('')
  const [birthDate, setBirthDate] = useState('')
  const [website, setWebsite] = useState('')
  const [turnstileToken, setTurnstileToken] = useState('')
  const turnstileSiteKey = useMemo(
    () => String(import.meta.env.VITE_TURNSTILE_SITE_KEY ?? '').trim(),
    [],
  )
  const turnstileContainerRef = useRef<HTMLDivElement | null>(null)
  const [username, setUsername] = useState(''), [usernameEdited, setUsernameEdited] = useState(false)
  const [availability, setAvailability] = useState<{ username: string; available: boolean; suggestions: string[] } | null>(null)
  const [checking, setChecking] = useState(false), [usernameError, setUsernameError] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [loading, setLoading] = useState(false)
  const maxBirthDate = useMemo(() => maxEligibleBirthDate(), [])

  useEffect(() => {
    if (!turnstileSiteKey || !turnstileContainerRef.current) return
    let active = true
    let widgetId = ''
    const hostWindow = window as Window & { turnstile?: TurnstileApi }
    const mountWidget = () => {
      const api = hostWindow.turnstile
      if (!active || !api || !turnstileContainerRef.current || widgetId) return
      widgetId = api.render(turnstileContainerRef.current, {
        sitekey: turnstileSiteKey,
        callback: (token) => setTurnstileToken(token),
        'expired-callback': () => setTurnstileToken(''),
        'error-callback': () => setTurnstileToken(''),
      })
    }
    if (hostWindow.turnstile) {
      mountWidget()
    } else {
      const existing = document.querySelector<HTMLScriptElement>(`script[src="${turnstileScriptSrc}"]`)
      const script = existing ?? document.createElement('script')
      script.addEventListener('load', mountWidget)
      if (!existing) {
        script.src = turnstileScriptSrc
        script.async = true
        script.defer = true
        document.head.appendChild(script)
      }
      return () => {
        active = false
        script.removeEventListener('load', mountWidget)
        if (widgetId && hostWindow.turnstile?.remove) {
          hostWindow.turnstile.remove(widgetId)
        }
      }
    }
    return () => {
      active = false
      if (widgetId && hostWindow.turnstile?.remove) {
        hostWindow.turnstile.remove(widgetId)
      }
    }
  }, [turnstileSiteKey])

  useEffect(() => {
    if (!usernameEdited) setUsername(displayName.normalize('NFKD').replace(/[\u0300-\u036f]/g, '').toLowerCase().trim().replace(/\s+/g, '_').replace(/[^a-z0-9_.-]/g, '').slice(0, 24))
  }, [displayName, usernameEdited])
  useEffect(() => {
    setAvailability(null); setUsernameError('')
    if (!username) { setChecking(false); return }
    if (!/^[a-z0-9_.-]{3,24}$/.test(username)) { setChecking(false); setUsernameError('Usa entre 3 y 24 caracteres: letras, números, punto o guion.'); return }
    const abort = new AbortController()
    setChecking(true)
    const timer = window.setTimeout(() => {
      void fallbackApiJson<{ username: string; available: boolean; suggestions: string[] }>(`/v1/auth/username/availability?username=${encodeURIComponent(username)}&name=${encodeURIComponent(displayName)}`, { auth: false, signal: abort.signal })
        .then(value => { if (!abort.signal.aborted) setAvailability(value) })
        .catch(() => { if (!abort.signal.aborted) setUsernameError('No se pudo comprobar. Revisa tu conexión y vuelve a escribirlo.') })
        .finally(() => { if (!abort.signal.aborted) setChecking(false) })
    }, 300)
    return () => { window.clearTimeout(timer); abort.abort() }
  }, [username, displayName])

  if (!client && !isFallbackAuthConfigured) {
    return (
      <StatusView
        title="Backend no configurado"
        description="Configura VITE_FALLBACK_API_URL para usar tu servidor Ubuntu."
      />
    )
  }

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (loading || !availability?.available || availability.username !== username) return
    if (new TextEncoder().encode(password).length > 72) { setError('La contraseña supera el máximo de 72 bytes. Usa una más corta.'); return }
    if (!isBirthDateMinimumAge(birthDate)) {
      setError(`Debes tener al menos ${MINIMUM_REGISTRATION_AGE} años para crear una cuenta en Novvin.`)
      return
    }
    if (turnstileSiteKey && !turnstileToken) {
      setError('Completa la verificación de seguridad antes de crear tu cuenta.')
      return
    }
    setLoading(true)
    setError(null)
    try {
      await signUpAndBootstrapProfile(client, {
        email,
        password,
        displayName,
        username,
        birthDate,
        website,
        turnstileToken,
      })
      navigate(nextPath, { replace: true })
    } catch (caught) {
      setError(friendlyError(caught, 'No se pudo registrar la cuenta'))
    } finally {
      setLoading(false)
    }
  }

  return (
    <section
      className={[
        'auth-shell auth-shell-rich auth-shell-centered',
        `accent-${webPreferences.accentTheme}`,
      ].join(' ')}
    >
      <div className="auth-layout-grid auth-layout-single">
        <form className="auth-card auth-login-card" onSubmit={handleSubmit}>
          <div className="section-stack auth-head-copy">
            <div>
              <div className="auth-brand-lockup">
                <img src="/novvin-logo.png" alt="Logo de Novvin" className="auth-logo-img" />
                <div>
                  <p className="brand-tag auth-panel-tag">Novvin</p>
                  <span>Conecta, comparte y descubre</span>
                </div>
              </div>
              <h1>Crear cuenta</h1>
              <p>Crea tu perfil gratis y empieza a conectar con personas y comunidades.</p>
            </div>
          </div>

          <div className="section-stack">
            <label>
              Nombre visible
              <input
                type="text"
                required
                disabled={loading}
                value={displayName}
                onChange={(event) => setDisplayName(event.target.value)}
              />
            </label>
            <label>
              Identificador único
              <input type="text" value={username} autoComplete="username" required disabled={loading} maxLength={24} aria-describedby="username-availability" aria-invalid={Boolean(usernameError || availability && !availability.available)}
                onChange={event => { setUsernameEdited(true); setAvailability(null); setUsername(event.target.value.toLowerCase().replace(/^@/, '')) }} />
              <small>Será tu @ permanente. Tu nombre visible puede repetirse.</small>
            </label>
            <p id="username-availability" role="status" style={{ color: availability?.available && availability.username === username ? 'var(--app-green, #218c52)' : usernameError || availability ? '#e05b67' : undefined }}>
              {checking ? 'Comprobando disponibilidad…' : usernameError || (availability && availability.username === username ? availability.available ? '✓ Disponible. Será tu identificador permanente.' : '✕ Este identificador no está disponible. Elige otro.' : '')}
            </p>
            {availability && !availability.available && <div className="row-actions" aria-label="Identificadores sugeridos">{availability.suggestions.map(suggestion =>
              <button type="button" className="ghost-btn" key={suggestion} onClick={() => { setUsernameEdited(true); setAvailability(null); setUsername(suggestion) }}>@{suggestion}</button>)}</div>}
            <label>
              Correo
              <input
                type="email"
                disabled={loading}
                required
                value={email}
                autoComplete="email"
                onChange={(event) => setEmail(event.target.value)}
              />
            </label>
            <label>
              Contraseña
              <input
                type="password"
                disabled={loading}
                required
                minLength={8}
                value={password}
                autoComplete="new-password"
                onChange={(event) => setPassword(event.target.value)}
              />
            </label>
            <label>
              Fecha de nacimiento
              <input
                type="date"
                required
                disabled={loading}
                max={maxBirthDate}
                value={birthDate}
                onChange={(event) => setBirthDate(event.target.value)}
              />
              <small>Debes tener al menos {MINIMUM_REGISTRATION_AGE} años para crear tu cuenta.</small>
            </label>
            <div
              style={{ position: 'absolute', left: '-10000px', width: '1px', height: '1px', overflow: 'hidden' }}
              aria-hidden="true"
            >
              <input
                type="text"
                name="website"
                tabIndex={-1}
                autoComplete="off"
                value={website}
                onChange={(event) => setWebsite(event.target.value)}
              />
            </div>
            {turnstileSiteKey ? <div ref={turnstileContainerRef} className="turnstile-slot" /> : null}
            {error ? <p className="error-text" role="alert">{error}</p> : null}
            <button className="primary-btn" disabled={loading || checking || !availability?.available || availability.username !== username} type="submit">
              {loading ? 'Creando...' : 'Crear cuenta'}
            </button>
          </div>

          <p className="auth-foot">
            Consulta la <a href="/privacidad">política de privacidad</a> y la información sobre <a href="/cookies">cookies y almacenamiento</a> antes de crear tu cuenta.
          </p>
          <p className="auth-foot">
            ¿Ya tienes cuenta? <Link to={authReturnPath('/login', nextPath)}>Inicia sesión</Link>
          </p>
        </form>
      </div>
    </section>
  )
}
