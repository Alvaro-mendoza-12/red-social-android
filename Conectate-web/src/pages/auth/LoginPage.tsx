import { useEffect, useMemo, useState, type FormEvent } from 'react'
import { QrCodeTile } from '../../components/QrCodeTile'
import { Link, useNavigate, useSearchParams } from 'react-router-dom'
import { StatusView } from '../../components/StatusView'
import {
  confirmFallbackPasswordReset,
  completeMigratedPasswordReset,
  isFallbackAuthConfigured,
  isMigratedPasswordResetRequired,
  requestFallbackPasswordReset,
} from '../../core/auth/fallbackAuth'
import { parseRecoverySessionPayload } from '../../core/auth/recoverySession'
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

type LoginMode = 'password' | 'code' | 'recovery' | 'qr'

const loginHighlights = [
  {
    title: 'Mensajes y cuentas',
    description: 'Retoma chats, favoritos, archivados y cambios rápidos desde el dispositivo que tengas a mano.',
  },
  {
    title: 'Videos y comunidad',
    description: 'Feed, reels, perfil, salas y monetización se sienten como partes de una misma red.',
  },
  {
    title: 'Entrada flexible',
    description: 'Usa contraseña, código al correo, QR aprobado desde tu teléfono o una sesión recuperada.',
  },
]

function normalizeEmail(value: string): string {
  return value.trim().toLowerCase()
}

function normalizeMode(value: string | null): LoginMode {
  if (value === 'code') return 'code'
  if (value === 'recovery') return 'recovery'
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
  const [searchParams, setSearchParams] = useSearchParams()
  const mode = useMemo<LoginMode>(() => normalizeMode(searchParams.get('mode')), [searchParams])
  const nextPath = useMemo(() => normalizeNextPath(searchParams.get('next')), [searchParams])
  const initialEmail = useMemo(() => searchParams.get('email')?.trim() ?? '', [searchParams])
  const [email, setEmail] = useState(initialEmail)
  const [password, setPassword] = useState('')
  const [codeEmail, setCodeEmail] = useState(initialEmail)
  const [accessCode, setAccessCode] = useState('')
  const [recoveryPayload, setRecoveryPayload] = useState('')
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
  const [sendingCode, setSendingCode] = useState(false)
  const [verifyingCode, setVerifyingCode] = useState(false)
  const [importingRecovery, setImportingRecovery] = useState(false)
  const [qrPairing, setQrPairing] = useState<WebLoginPairing | null>(null)
  const [creatingQr, setCreatingQr] = useState(false)
  const [consumingQr, setConsumingQr] = useState(false)
  const [qrRefreshKey, setQrRefreshKey] = useState(0)

  function changeMode(nextMode: LoginMode) {
    const nextParams = new URLSearchParams(searchParams)
    nextParams.set('mode', nextMode)
    const preferredEmail = normalizeEmail(nextMode === 'password' ? email : codeEmail)
    if (preferredEmail) {
      nextParams.set('email', preferredEmail)
    } else {
      nextParams.delete('email')
    }
    setSearchParams(nextParams, { replace: true })
    setError(null)
    setInfo(null)
    setMigratedPasswordReset(null)
  }

  useEffect(() => {
    if (!client || mode !== 'qr') return
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
    if (!client || mode !== 'qr' || !activePairing?.pairId || !activePairing.nonce || activePairing.status !== 'pending') {
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
    if (!client || mode !== 'qr' || !activePairing?.pairId || !activePairing.nonce || activePairing.status !== 'approved' || consumingQr) {
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
        const accessToken = consumed.accessToken?.trim()
        const refreshToken = consumed.refreshToken?.trim()
        if (!accessToken || !refreshToken) {
          throw new Error('La sesión aprobada llegó incompleta.')
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
    try {
      await signInWithResolvedEmail(client, email, password)
      setMigratedPasswordReset(null)
      navigate(nextPath, { replace: true })
    } catch (caught) {
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

  async function sendAccessCode() {
    if (sendingCode) return
    const normalizedEmail = normalizeEmail(codeEmail)
    if (!normalizedEmail) {
      setError('Ingresa tu correo para recibir el acceso por código o enlace.')
      return
    }

    setSendingCode(true)
    setError(null)
    setInfo(null)
    try {
      if (!client) {
        const reset = await requestFallbackPasswordReset(normalizedEmail)
        if (reset.resetToken) {
          setAccessCode(reset.resetToken)
          setInfo('Servidor Ubuntu generó un token de recuperación. Lo pegamos automáticamente; crea tu nueva contraseña.')
          return
        }
        setInfo(
          reset.delivery === 'console'
            ? 'Servidor Ubuntu generó un token de recuperación. Revísalo con docker compose logs api y pégalo aquí.'
            : 'Servidor Ubuntu recibió la solicitud de recuperación.',
        )
        return
      }

      const { error: otpError } = await client.auth.signInWithOtp({
        email: normalizedEmail,
        options: {
          shouldCreateUser: false,
          emailRedirectTo: `${window.location.origin}/login?next=${encodeURIComponent(nextPath)}`,
        },
      })
      if (otpError) throw otpError
      setInfo('Revisa tu correo. Si tu proyecto envía OTP, escribe el código aquí; si envía enlace mágico, ábrelo desde el correo.')
    } catch (caught) {
      setError(friendlyError(caught, 'No se pudo enviar el acceso por código'))
    } finally {
      setSendingCode(false)
    }
  }

  async function verifyAccessCode(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (verifyingCode) return
    const normalizedEmail = normalizeEmail(codeEmail)
    const normalizedCode = accessCode.trim()
    if (!normalizedEmail || !normalizedCode) {
      setError('Escribe el correo y el código antes de continuar.')
      return
    }

    setVerifyingCode(true)
    setError(null)
    setInfo(null)
    try {
      if (!client) {
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

      const { error: verifyError } = await client.auth.verifyOtp({
        email: normalizedEmail,
        token: normalizedCode,
        type: 'email',
      })
      if (verifyError) throw verifyError
      navigate(nextPath, { replace: true })
    } catch (caught) {
      setError(friendlyError(caught, 'No se pudo validar el código'))
    } finally {
      setVerifyingCode(false)
    }
  }

  async function importRecoverySession(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (!client || importingRecovery) return
    const payload = parseRecoverySessionPayload(recoveryPayload)
    if (!payload) {
      setError('Pega un enlace de recuperación o un bloque con access_token y refresh_token.')
      return
    }

    setImportingRecovery(true)
    setError(null)
    setInfo(null)
    try {
      const { error: sessionError } = await client.auth.setSession({
        access_token: payload.accessToken,
        refresh_token: payload.refreshToken,
      })
      if (sessionError) throw sessionError
      navigate(nextPath, { replace: true })
    } catch (caught) {
      setError(friendlyError(caught, 'No se pudo importar la sesión'))
    } finally {
      setImportingRecovery(false)
    }
  }

  if (!client && !isFallbackAuthConfigured) {
    return (
      <StatusView
        title="Backend no configurado"
        description="Configura VITE_FALLBACK_API_URL para usar tu servidor Ubuntu, o VITE_SUPABASE_URL/VITE_SUPABASE_ANON_KEY si mantienes el puente anterior."
      />
    )
  }

  const fallbackOnlyMode = !client && isFallbackAuthConfigured

  return (
    <section className="auth-shell auth-shell-rich">
      <div className="auth-layout-grid">
        <aside className="auth-spotlight" aria-hidden="true">
          <div className="section-stack auth-spotlight-copy">
            <p className="brand-tag">Conectate</p>
            <div>
              <h1>Tu red social, lista para entrar</h1>
              <p>
                Continúa tus chats, videos, salas y perfil con una entrada clara, segura y rápida.
              </p>
            </div>
          </div>

          <div className="auth-highlight-grid">
            {loginHighlights.map((entry) => (
              <article key={entry.title} className="surface-subcard auth-highlight-card">
                <strong>{entry.title}</strong>
                <p>{entry.description}</p>
              </article>
            ))}
          </div>

          <article className="surface-subcard auth-spotlight-note">
            <strong>Continúa desde tu teléfono</strong>
            <p>
              Si ya aprobaste una sesión o recibiste una recuperación, puedes entrar por QR o
              vincular la sesión sin repetir pasos.
            </p>
          </article>
        </aside>

        <div className="auth-card auth-card-wide auth-panel-card">
          <div className="section-stack auth-head-copy">
            <div>
              <p className="brand-tag auth-panel-tag">Acceso</p>
              <h1>Entrar a tu red social</h1>
              <p>
                Elige cómo quieres entrar hoy: contraseña, código al correo, QR o sesión recuperada.
              </p>
            </div>
            <div className="chip-row auth-mode-row auth-mode-grid">
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
                className={mode === 'code' ? 'chip-btn active' : 'chip-btn'}
                aria-pressed={mode === 'code'}
                onClick={() => changeMode('code')}
              >
                Código
              </button>
              <button
                type="button"
                className={mode === 'recovery' ? 'chip-btn active' : 'chip-btn'}
                aria-pressed={mode === 'recovery'}
                onClick={() => changeMode('recovery')}
              >
                Vincular sesión
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
                : mode === 'code'
                  ? 'Código temporal'
                  : mode === 'recovery'
                    ? 'Importar sesión existente'
                    : 'Aprobación desde el móvil'}
            </strong>
            <p>
              {mode === 'password'
                ? 'Ideal si quieres entrar manualmente desde este navegador.'
                : mode === 'code'
                  ? 'Útil cuando prefieres un acceso temporal sin escribir contraseña.'
                  : mode === 'recovery'
                    ? 'Pega un bloque válido con tokens si vienes de un flujo de recuperación o migración.'
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

          {mode === 'code' ? (
            <form className="section-stack" onSubmit={verifyAccessCode}>
              <label>
                Correo de la cuenta
                <input
                  type="email"
                  required
                  value={codeEmail}
                  autoComplete="email"
                  onChange={(event) => setCodeEmail(event.target.value)}
                />
              </label>
              <div className="auth-inline-actions">
                <button
                  className="ghost-btn"
                  type="button"
                  disabled={sendingCode}
                  onClick={() => void sendAccessCode()}
                >
                  {sendingCode ? 'Enviando...' : 'Enviar código o enlace'}
                </button>
              </div>
              <label>
                Código de acceso
                <input
                  type="text"
                  inputMode="numeric"
                  autoComplete="one-time-code"
                  placeholder="Escribe aquí el código del correo"
                  value={accessCode}
                  onChange={(event) => setAccessCode(event.target.value)}
                />
              </label>
              <small className="auth-foot">
                {fallbackOnlyMode
                  ? 'En modo Ubuntu, este campo recibe el token de recuperación emitido por tu servidor.'
                  : 'Si tu proveedor envía enlace mágico en lugar de OTP, basta con abrir el correo y tocar el enlace.'}
              </small>
              {fallbackOnlyMode ? (
                <>
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
                    Confirmar contraseña
                    <input
                      type="password"
                      required
                      minLength={8}
                      value={fallbackResetConfirmPassword}
                      autoComplete="new-password"
                      onChange={(event) => setFallbackResetConfirmPassword(event.target.value)}
                    />
                  </label>
                </>
              ) : null}
              <button className="primary-btn" disabled={verifyingCode} type="submit">
                {verifyingCode ? 'Validando...' : fallbackOnlyMode ? 'Cambiar contraseña y entrar' : 'Entrar con código'}
              </button>
            </form>
          ) : null}

          {mode === 'recovery' ? (
            <form className="section-stack" onSubmit={importRecoverySession}>
              <label>
                Enlace o bloque de recuperación
                <textarea
                  className="text-input textarea-input"
                  rows={5}
                  placeholder="Pega aquí el enlace conectate://recovery o un JSON con access_token y refresh_token"
                  value={recoveryPayload}
                  onChange={(event) => setRecoveryPayload(event.target.value)}
                />
              </label>
              <button className="primary-btn" disabled={importingRecovery} type="submit">
                {importingRecovery ? 'Importando...' : 'Vincular e iniciar'}
              </button>
              <small className="auth-foot">
                Este modo sirve para importar una sesión ya emitida desde otro dispositivo o desde un
                flujo de recuperación compatible.
              </small>
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
                    description="Abre Conectate en tu teléfono, toca Escanear código y aprueba esta sesión."
                  />
                ) : (
                  <StatusView
                    title="QR no disponible"
                    description="Todavía no pudimos preparar el código. Vuelve a intentarlo."
                  />
                )}
              </div>

              <div className="surface-subcard auth-qr-copy">
                <strong>Cómo funciona</strong>
                <p>1. La web genera un QR temporal.</p>
                <p>2. Lo escaneas desde Conectate móvil.</p>
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
