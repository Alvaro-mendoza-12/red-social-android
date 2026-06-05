import { useEffect, useMemo, useState, type FormEvent } from 'react'
import { QrCodeTile } from '../../components/QrCodeTile'
import { Link, useNavigate, useSearchParams } from 'react-router-dom'
import { StatusView } from '../../components/StatusView'
import { PasswordResetPage } from './PasswordResetPage'
import {
  completeMigratedPasswordReset,
  confirmFallbackPasswordReset,
  isFallbackAuthConfigured,
  isMigratedAccountPasswordSetupRequired,
  isMigratedPasswordResetRequired,
  persistFallbackSession,
  requestFallbackPasswordReset,
} from '../../core/auth/fallbackAuth'
import { signInWithResolvedEmail } from '../../core/auth/sessionBootstrap'
import {
  consumeApprovedWebLoginPairing,
  createWebLoginPairing,
  readWebLoginPairingStatus,
  toPairingError,
  type WebLoginPairing,
} from '../../core/auth/webLoginPairing'
import { supabase } from '../../core/supabase'
import { friendlyError } from '../../core/utils'
import { useWebPreferences } from '../../core/webPreferences'

type LoginMode = 'password' | 'qr'

function normalizeEmail(value: string): string {
  return value.trim().toLowerCase()
}

function isUbuntuInvalidCredentials(error: unknown): boolean {
  if (!(error instanceof Error)) return false
  return /inv[aá]lid/i.test(error.message) && /ubuntu/i.test(error.message)
}

function normalizeMode(value: string | null): LoginMode {
  if (value === 'qr') return 'qr'
  return 'password'
}

function normalizeNextPath(value: string | null): string {
  const clean = value?.trim()
  if (!clean || !clean.startsWith('/') || clean.startsWith('//')) return '/app/messages'
  return clean
}

export function LoginPage() {
  const navigate = useNavigate()
  const client = supabase
  const [webPreferences] = useWebPreferences()
  const [searchParams, setSearchParams] = useSearchParams()
  const mode = useMemo<LoginMode>(() => normalizeMode(searchParams.get('mode')), [searchParams])
  const nextPath = useMemo(() => normalizeNextPath(searchParams.get('next')), [searchParams])
  const initialEmail = useMemo(() => searchParams.get('email')?.trim() ?? '', [searchParams])
  const [email, setEmail] = useState(initialEmail)
  const [password, setPassword] = useState('')
  const [migrationSetupEmail, setMigrationSetupEmail] = useState('')
  const [migrationSetupToken, setMigrationSetupToken] = useState('')
  const [showMigrationToken, setShowMigrationToken] = useState(false)
  const [legacyResetEmail, setLegacyResetEmail] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [info, setInfo] = useState<string | null>(null)
  const [migratedPasswordReset, setMigratedPasswordReset] = useState<{
    email: string
    migrationToken: string
    displayName: string
  } | null>(null)
  const [migratedNewPassword, setMigratedNewPassword] = useState('')
  const [migratedConfirmPassword, setMigratedConfirmPassword] = useState('')
  const [fallbackResetPassword, setFallbackResetPassword] = useState('')
  const [fallbackResetConfirmPassword, setFallbackResetConfirmPassword] = useState('')
  const [activatingMigratedPassword, setActivatingMigratedPassword] = useState(false)
  const [loading, setLoading] = useState(false)
  const [requestingMigrationReset, setRequestingMigrationReset] = useState(false)
  const [confirmingMigrationReset, setConfirmingMigrationReset] = useState(false)
  const [qrPairing, setQrPairing] = useState<WebLoginPairing | null>(null)
  const [creatingQr, setCreatingQr] = useState(false)
  const [consumingQr, setConsumingQr] = useState(false)
  const [qrRefreshKey, setQrRefreshKey] = useState(0)

  function changeMode(nextMode: LoginMode) {
    const nextParams = new URLSearchParams(searchParams)
    nextParams.set('mode', nextMode)
    const preferredEmail = normalizeEmail(email)
    if (preferredEmail) {
      nextParams.set('email', preferredEmail)
    } else {
      nextParams.delete('email')
    }
    setSearchParams(nextParams, { replace: true })
    setError(null)
    setInfo(null)
    setMigratedPasswordReset(null)
    setMigrationSetupEmail('')
    setLegacyResetEmail('')
    setShowMigrationToken(false)
  }

  useEffect(() => {
    if (mode !== 'qr' || (!client && !isFallbackAuthConfigured)) return
    const supabaseClient = client
    let cancelled = false

    async function bootstrapPairing() {
      setCreatingQr(true)
      setError(null)
      setInfo(null)
      try {
        const pairing = await createWebLoginPairing(supabaseClient)
        if (!cancelled) {
          setQrPairing(pairing)
        }
      } catch (caught) {
        if (!cancelled) {
          setQrPairing(null)
          setError(toPairingError(caught, 'No se pudo generar el QR de Conectate Web.'))
        }
      } finally {
        if (!cancelled) {
          setCreatingQr(false)
        }
      }
    }

    void bootstrapPairing()
    return () => {
      cancelled = true
    }
  }, [client, mode, qrRefreshKey])

  useEffect(() => {
    const activePairing = qrPairing
    if (mode !== 'qr' || !activePairing?.pairId || !activePairing.nonce || activePairing.status !== 'pending') {
      return
    }
    const supabaseClient = client

    let cancelled = false
    const pollId = window.setInterval(() => {
      void (async () => {
        try {
          const next = await readWebLoginPairingStatus(
            supabaseClient,
            activePairing.pairId,
            activePairing.nonce ?? '',
          )
          if (cancelled) return
          setQrPairing((current) => ({
            ...next,
            nonce: next.nonce ?? current?.nonce ?? activePairing.nonce,
          }))
          if (next.status === 'rejected') {
            setInfo('La aprobación fue rechazada desde el móvil.')
          }
          if (next.status === 'expired') {
            setInfo('El QR expiró. Genera uno nuevo para continuar.')
          }
        } catch (caught) {
          if (!cancelled) {
            setError(toPairingError(caught, 'No se pudo revisar el estado del QR.'))
          }
        }
      })()
    }, 2500)

    return () => {
      cancelled = true
      window.clearInterval(pollId)
    }
  }, [client, mode, qrPairing])

  useEffect(() => {
    const activePairing = qrPairing
    if (mode !== 'qr' || !activePairing?.pairId || !activePairing.nonce || activePairing.status !== 'approved' || consumingQr) {
      return
    }
    const supabaseClient = client
    const pairing = activePairing

    let cancelled = false

    async function consumePairing() {
      setConsumingQr(true)
      setError(null)
      setInfo('Aprobado desde el móvil. Terminando el inicio de sesión...')
      try {
        const consumed = await consumeApprovedWebLoginPairing(
          supabaseClient,
          pairing.pairId,
          pairing.nonce ?? '',
        )
        if (isFallbackAuthConfigured) {
          persistFallbackSession(consumed)
          if (!cancelled) {
            navigate(nextPath, { replace: true })
          }
          return
        }
        const accessToken = consumed.accessToken?.trim()
        const refreshToken = consumed.refreshToken?.trim()
        if (!accessToken || !refreshToken) {
          throw new Error('La sesión aprobada llegó incompleta.')
        }
        if (!supabaseClient) {
          throw new Error('No hay cliente Supabase activo para completar este QR.')
        }
        const { error: sessionError } = await supabaseClient.auth.setSession({
          access_token: accessToken,
          refresh_token: refreshToken,
        })
        if (sessionError) throw sessionError
        if (!cancelled) {
          navigate(nextPath, { replace: true })
        }
      } catch (caught) {
        if (!cancelled) {
          setError(toPairingError(caught, 'No se pudo completar el acceso por QR.'))
        }
      } finally {
        if (!cancelled) {
          setConsumingQr(false)
        }
      }
    }

    void consumePairing()
    return () => {
      cancelled = true
    }
  }, [client, consumingQr, mode, navigate, nextPath, qrPairing])

  async function handlePasswordSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    setLoading(true)
    setError(null)
    setInfo(null)
    setLegacyResetEmail('')
    try {
      await signInWithResolvedEmail(client, email, password)
      setMigratedPasswordReset(null)
      navigate(nextPath, { replace: true })
    } catch (caught) {
      if (isMigratedAccountPasswordSetupRequired(caught)) {
        const migratedEmail = normalizeEmail(caught.email || email)
        setMigrationSetupEmail(migratedEmail)
        setMigrationSetupToken('')
        setFallbackResetPassword('')
        setFallbackResetConfirmPassword('')
        setMigratedPasswordReset(null)
        const nextParams = new URLSearchParams(searchParams)
        nextParams.set('mode', 'password')
        if (migratedEmail) {
          nextParams.set('email', migratedEmail)
        }
        setSearchParams(nextParams, { replace: true })
        setInfo(
          'Esta cuenta viene de Supabase y necesita crear una nueva contraseña en Ubuntu para continuar.',
        )
        return
      }
      if (isMigratedPasswordResetRequired(caught)) {
        setMigratedPasswordReset({
          email: caught.email,
          migrationToken: caught.migrationToken,
          displayName: caught.displayName,
        })
        setMigratedNewPassword('')
        setMigratedConfirmPassword('')
        setError(null)
        setInfo('Cuenta migrada desde Supabase. Crea tu contraseña local para continuar en el servidor Ubuntu.')
        return
      }
      if (isUbuntuInvalidCredentials(caught)) {
        const normalized = normalizeEmail(email)
        if (normalized) {
          setLegacyResetEmail(normalized)
        }
      }
      setError(friendlyError(caught, 'No se pudo iniciar sesión'))
    } finally {
      setLoading(false)
    }
  }

  async function handleMigratedPasswordSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (!migratedPasswordReset || activatingMigratedPassword) return

    const nextPassword = migratedNewPassword.trim()
    if (nextPassword.length < 8) {
      setError('La nueva contraseña debe tener al menos 8 caracteres.')
      return
    }
    if (nextPassword !== migratedConfirmPassword.trim()) {
      setError('La confirmación no coincide con la contraseña nueva.')
      return
    }

    setActivatingMigratedPassword(true)
    setError(null)
    setInfo(null)
    try {
      await completeMigratedPasswordReset(migratedPasswordReset.migrationToken, nextPassword)
      setMigratedPasswordReset(null)
      setMigratedNewPassword('')
      setMigratedConfirmPassword('')
      navigate(nextPath, { replace: true })
    } catch (caught) {
      setError(friendlyError(caught, 'No se pudo activar la contraseña local'))
    } finally {
      setActivatingMigratedPassword(false)
    }
  }

  async function requestMigrationPasswordResetForEmail(targetEmail: string) {
    if (requestingMigrationReset) return
    const normalizedEmail = normalizeEmail(targetEmail)
    if (!normalizedEmail) {
      setError('Ingresa el correo de la cuenta migrada.')
      return
    }

    setRequestingMigrationReset(true)
    setError(null)
    setInfo(null)
    try {
      if (isFallbackAuthConfigured) {
        const reset = await requestFallbackPasswordReset(normalizedEmail)
        if (reset.resetToken) {
          setMigrationSetupToken(reset.resetToken)
          setShowMigrationToken(false)
          setInfo('Ubuntu generó el código de migración. Lo pegamos automáticamente; crea tu nueva contraseña.')
          return
        }
        setShowMigrationToken(true)
        setInfo(
          reset.delivery === 'console'
            ? 'Ubuntu generó el código, pero está configurado para mostrarlo en logs. En el servidor ejecuta docker compose logs --tail=80 api y pega aquí el token.'
            : 'Ubuntu recibió la solicitud de recuperación. Si no llega correo, cambia PASSWORD_RESET_DELIVERY=response en el .env del servidor.',
        )
        return
      }

      throw new Error('La migración de contraseña necesita el servidor Ubuntu configurado.')
    } catch (caught) {
      setError(friendlyError(caught, 'No se pudo iniciar el cambio de contraseña migrada'))
    } finally {
      setRequestingMigrationReset(false)
    }
  }

  async function requestMigrationPasswordReset() {
    return requestMigrationPasswordResetForEmail(migrationSetupEmail || email)
  }

  async function beginLegacyPasswordReset() {
    const normalized = normalizeEmail(legacyResetEmail || email)
    if (!normalized) return
    setMigrationSetupEmail(normalized)
    setMigrationSetupToken('')
    setShowMigrationToken(false)
    setFallbackResetPassword('')
    setFallbackResetConfirmPassword('')
    setMigratedPasswordReset(null)
    setError(null)
    setInfo(
      'Si tu cuenta fue creada antes de migrar a Ubuntu, necesitas definir una nueva contraseña local una sola vez.',
    )
    await requestMigrationPasswordResetForEmail(normalized)
  }

  async function confirmMigrationPasswordReset(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (confirmingMigrationReset) return
    const normalizedCode = migrationSetupToken.trim()
    if (!normalizedCode) {
      setError('Pega el código de migración antes de continuar.')
      return
    }

    setConfirmingMigrationReset(true)
    setError(null)
    setInfo(null)
    try {
      if (isFallbackAuthConfigured) {
        const nextPassword = fallbackResetPassword.trim()
        if (nextPassword.length < 8) {
          setError('La nueva contraseña debe tener al menos 8 caracteres.')
          return
        }
        if (nextPassword !== fallbackResetConfirmPassword.trim()) {
          setError('La confirmación no coincide con la contraseña nueva.')
          return
        }
        await confirmFallbackPasswordReset(normalizedCode, nextPassword)
        navigate(nextPath, { replace: true })
        return
      }

      throw new Error('La migración de contraseña necesita el servidor Ubuntu configurado.')
    } catch (caught) {
      setError(friendlyError(caught, 'No se pudo cambiar la contraseña migrada'))
    } finally {
      setConfirmingMigrationReset(false)
    }
  }

  if (!client && !isFallbackAuthConfigured) {
    return (
      <StatusView
        title="Backend no configurado"
        description="Configura VITE_FALLBACK_API_URL para usar tu servidor Ubuntu."
      />
    )
  }

  return (
    <section
      className={[
        'auth-shell auth-shell-rich auth-shell-centered',
        `accent-${webPreferences.accentTheme}`,
      ].join(' ')}
    >
      <div className="auth-bg-scene" aria-hidden="true">
        <span className="auth-bg-card card-one">Chats</span>
        <span className="auth-bg-card card-two">Perfil</span>
        <span className="auth-bg-card card-three">QR</span>
        <span className="auth-bg-line line-one" />
        <span className="auth-bg-line line-two" />
      </div>

      <div className="auth-layout-grid auth-layout-single">
        <div className="auth-card auth-card-wide auth-panel-card auth-login-card">
          <div className="section-stack auth-head-copy">
            <div>
              <div className="auth-brand-lockup">
                <img src="/conectate-logo.svg" alt="Conectate" className="auth-logo-img" />
                <div>
                  <p className="brand-tag auth-panel-tag">Conectate</p>
                  <span>Red social privada</span>
                </div>
              </div>
              <h1>Entrar a Conectate</h1>
              <p>
                Accede con tu contraseña o aprueba un QR desde tu móvil.
              </p>
            </div>
            <div className="chip-row auth-mode-row auth-mode-grid auth-mode-grid-two">
              <button
                type="button"
                className={mode === 'password' ? 'chip-btn active' : 'chip-btn'}
                aria-pressed={mode === 'password'}
                onClick={() => changeMode('password')}
              >
                Contraseña
              </button>
              <button
                type="button"
                className={mode === 'qr' ? 'chip-btn active' : 'chip-btn'}
                aria-pressed={mode === 'qr'}
                onClick={() => changeMode('qr')}
              >
                Iniciar sesión con QR
              </button>
            </div>
          </div>

          <div className="surface-subcard auth-mode-note">
            <strong>
              {mode === 'password'
                ? 'Acceso clásico'
                : 'Aprobación desde el móvil'}
            </strong>
            <p>
              {mode === 'password'
                ? 'Ideal si quieres entrar manualmente desde este navegador.'
                : 'Genera un QR temporal y apruébalo desde la app Android para abrir esta sesión web.'}
            </p>
          </div>

          {mode === 'password' ? (
            <form className="section-stack" onSubmit={handlePasswordSubmit}>
              <label>
                Correo
                <input
                  type="email"
                  required
                  value={email}
                  autoComplete="email"
                  onChange={(event) => {
                    setEmail(event.target.value)
                    setMigratedPasswordReset(null)
                    setLegacyResetEmail('')
                  }}
                />
              </label>
              <label>
                Contraseña
                <input
                  type="password"
                  required
                  value={password}
                  autoComplete="current-password"
                  onChange={(event) => setPassword(event.target.value)}
                />
              </label>
              <button className="primary-btn" disabled={loading} type="submit">
                {loading ? 'Entrando...' : 'Iniciar sesión'}
              </button>
            </form>
          ) : null}

          {mode === 'password' && legacyResetEmail && !migrationSetupEmail && !migratedPasswordReset ? (
            <div className="surface-subcard legacy-reset-callout">
              <strong>¿Cuenta antigua? Cambia tu contraseña</strong>
              <p>
                Si creaste tu cuenta antes de migrar a Ubuntu, tu contraseña vieja no se puede usar aquí.
                Cambia la contraseña y podrás entrar con normalidad.
              </p>
              <button
                className="primary-btn"
                type="button"
                disabled={requestingMigrationReset}
                onClick={() => void beginLegacyPasswordReset()}
              >
                {requestingMigrationReset ? 'Preparando...' : 'Cambiar contraseña'}
              </button>
            </div>
          ) : null}

          {mode === 'password' && migrationSetupEmail ? (
            <form className="surface-subcard section-stack migrated-password-card" onSubmit={confirmMigrationPasswordReset}>
              <div>
                <strong>Cambia tu contraseña para Ubuntu</strong>
                <p>
                  Por la migración desde Supabase, esta cuenta necesita una nueva contraseña local.
                  Después de cambiarla, entrarás con normalidad en web y móvil.
                </p>
                <small>{migrationSetupEmail}</small>
              </div>
              <button
                className="ghost-btn"
                type="button"
                disabled={requestingMigrationReset}
                onClick={() => void requestMigrationPasswordReset()}
              >
                {requestingMigrationReset ? 'Generando código...' : 'Cambiar contraseña'}
              </button>
              {showMigrationToken || !migrationSetupToken ? (
                <label>
                  Código de migración
                  <input
                    type="text"
                    required
                    value={migrationSetupToken}
                    autoComplete="one-time-code"
                    onChange={(event) => setMigrationSetupToken(event.target.value)}
                  />
                </label>
              ) : (
                <div className="migration-token-ready">
                  <div>
                    <strong>Código listo</strong>
                    <p>Ya lo guardamos. Ahora crea tu nueva contraseña.</p>
                  </div>
                  <button className="mini-action-btn" type="button" onClick={() => setShowMigrationToken(true)}>
                    Ver código
                  </button>
                </div>
              )}
              <label>
                Nueva contraseña
                <input
                  type="password"
                  required
                  minLength={8}
                  value={fallbackResetPassword}
                  autoComplete="new-password"
                  onChange={(event) => setFallbackResetPassword(event.target.value)}
                />
              </label>
              <label>
                Repetir nueva contraseña
                <input
                  type="password"
                  required
                  minLength={8}
                  value={fallbackResetConfirmPassword}
                  autoComplete="new-password"
                  onChange={(event) => setFallbackResetConfirmPassword(event.target.value)}
                />
              </label>
              <button className="primary-btn" disabled={confirmingMigrationReset} type="submit">
                {confirmingMigrationReset ? 'Cambiando...' : 'Guardar contraseña y entrar'}
              </button>
            </form>
          ) : null}

          {mode === 'password' && migratedPasswordReset ? (
            <form className="surface-subcard section-stack migrated-password-card" onSubmit={handleMigratedPasswordSubmit}>
              <div>
                <strong>Actualización por migración</strong>
                <p>
                  Verificamos tu contraseña anterior de Supabase. Ahora crea la contraseña que usará
                  tu cuenta en el servidor Ubuntu.
                </p>
                <small>{migratedPasswordReset.email}</small>
              </div>
              <label>
                Nueva contraseña local
                <input
                  type="password"
                  required
                  value={migratedNewPassword}
                  autoComplete="new-password"
                  onChange={(event) => setMigratedNewPassword(event.target.value)}
                />
              </label>
              <label>
                Confirmar contraseña local
                <input
                  type="password"
                  required
                  value={migratedConfirmPassword}
                  autoComplete="new-password"
                  onChange={(event) => setMigratedConfirmPassword(event.target.value)}
                />
              </label>
              <button className="primary-btn" disabled={activatingMigratedPassword} type="submit">
                {activatingMigratedPassword ? 'Activando...' : 'Crear contraseña y entrar'}
              </button>
            </form>
          ) : null}

          {mode === 'qr' ? (
            <div className="section-stack auth-qr-layout">
              <div className="auth-qr-hero">
                {creatingQr ? (
                  <StatusView
                    title="Generando QR"
                    description="Preparando un código temporal y seguro para Conectate Web..."
                  />
                ) : qrPairing?.qrUrl ? (
                  <QrCodeTile
                    value={qrPairing.qrUrl}
                    title="Escanéalo desde el móvil"
                    description="Abre Conectate en tu teléfono, entra a Perfil y aprueba el acceso web."
                  />
                ) : (
                  <StatusView
                    title="QR no disponible"
                    description="El servidor Ubuntu no pudo preparar el código todavía. Revisa que Ngrok y la API estén encendidos."
                  />
                )}
              </div>

              <div className="surface-subcard auth-qr-copy">
                <strong>Cómo funciona</strong>
                <p>1. La web genera un QR temporal.</p>
                <p>2. Lo escaneas desde Perfil en Conectate móvil.</p>
                <p>3. El móvil aprueba o rechaza la sesión.</p>
                <p>4. Si apruebas, esta ventana entra automáticamente.</p>
              </div>

              <div className="surface-subcard auth-qr-copy">
                <strong>Estado actual</strong>
                <p>
                  {consumingQr
                    ? 'Aprobado. Importando la sesión web...'
                    : qrPairing?.status === 'approved'
                      ? 'Aprobado desde el móvil.'
                      : qrPairing?.status === 'rejected'
                        ? 'Rechazado desde el móvil.'
                        : qrPairing?.status === 'expired'
                          ? 'Expirado. Necesitas generar otro QR.'
                          : 'Esperando aprobación desde el móvil.'}
                </p>
                <p>{qrPairing?.deviceLabel || 'Este navegador'}</p>
              </div>

              <div className="auth-inline-actions">
                <button
                  className="ghost-btn"
                  type="button"
                  disabled={creatingQr || consumingQr}
                  onClick={() => {
                    setQrPairing(null)
                    setQrRefreshKey((current) => current + 1)
                  }}
                >
                  {creatingQr ? 'Generando...' : 'Generar otro QR'}
                </button>
              </div>
            </div>
          ) : null}

          {error ? <p className="error-text">{error}</p> : null}
          {info ? <p>{info}</p> : null}

          <p className="auth-foot">
            ¿No tienes cuenta? <Link to="/register">Regístrate</Link>
          </p>
        </div>
      </div>
    </section>
  )
}
