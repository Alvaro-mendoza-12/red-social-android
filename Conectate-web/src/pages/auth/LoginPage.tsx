import { useEffect, useMemo, useState, type FormEvent } from 'react'
import { QrCodeTile } from '../../components/QrCodeTile'
import { Link, useNavigate, useSearchParams } from 'react-router-dom'
import { StatusView } from '../../components/StatusView'
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

function normalizeEmail(value: string): string {
  return value.trim().toLowerCase()
}

function normalizeMode(value: string | null): LoginMode {
  if (value === 'code') return 'code'
  if (value === 'recovery') return 'recovery'
  if (value === 'qr') return 'qr'
  return 'password'
}

function getModeTitle(mode: LoginMode): string {
  if (mode === 'password') return 'Acceso clásico'
  if (mode === 'code') return 'Código temporal'
  if (mode === 'recovery') return 'Vincular sesión'
  return 'Aprobación desde la app'
}

function getModeDescription(mode: LoginMode): string {
  if (mode === 'password') {
    return 'Ideal si quieres entrar manualmente desde este navegador.'
  }
  if (mode === 'code') {
    return 'Recibe un acceso temporal por correo sin depender de tu contraseña.'
  }
  if (mode === 'recovery') {
    return 'Importa una sesión ya emitida desde un flujo de recuperación o desde otro dispositivo.'
  }
  return 'Genera un QR temporal y apruébalo desde la app Android para abrir esta sesión web.'
}

function getErrorBannerTitle(message: string): string {
  const normalized = message.toLowerCase()
  if (normalized.includes('temporalmente no disponible')) {
    return 'Servicio temporalmente no disponible'
  }
  if (normalized.includes('no coinciden') || normalized.includes('no fue confirmado')) {
    return 'No pudimos validar tu acceso'
  }
  if (normalized.includes('no pudimos conectarnos')) {
    return 'Conexión no disponible'
  }
  return 'No pudimos completar el acceso'
}

export function LoginPage() {
  const navigate = useNavigate()
  const client = supabase
  const [searchParams, setSearchParams] = useSearchParams()
  const mode = useMemo<LoginMode>(() => normalizeMode(searchParams.get('mode')), [searchParams])
  const initialEmail = useMemo(() => searchParams.get('email')?.trim() ?? '', [searchParams])
  const [email, setEmail] = useState(initialEmail)
  const [password, setPassword] = useState('')
  const [codeEmail, setCodeEmail] = useState(initialEmail)
  const [accessCode, setAccessCode] = useState('')
  const [recoveryPayload, setRecoveryPayload] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [info, setInfo] = useState<string | null>(null)
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
          setQrPairing(next)
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
          navigate('/app/messages', { replace: true })
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
  }, [client, consumingQr, mode, navigate, qrPairing])

  async function handlePasswordSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (!client) return
    setLoading(true)
    setError(null)
    setInfo(null)
    try {
      await signInWithResolvedEmail(client, email, password)
      navigate('/app/messages', { replace: true })
    } catch (caught) {
      setError(friendlyError(caught, 'No se pudo iniciar sesión'))
    } finally {
      setLoading(false)
    }
  }

  async function sendAccessCode() {
    if (!client || sendingCode) return
    const normalizedEmail = normalizeEmail(codeEmail)
    if (!normalizedEmail) {
      setError('Ingresa tu correo para recibir el acceso por código o enlace.')
      return
    }

    setSendingCode(true)
    setError(null)
    setInfo(null)
    try {
      const { error: otpError } = await client.auth.signInWithOtp({
        email: normalizedEmail,
        options: {
          shouldCreateUser: false,
          emailRedirectTo: `${window.location.origin}/login`,
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
    if (!client || verifyingCode) return
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
      const { error: verifyError } = await client.auth.verifyOtp({
        email: normalizedEmail,
        token: normalizedCode,
        type: 'email',
      })
      if (verifyError) throw verifyError
      navigate('/app/messages', { replace: true })
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
      navigate('/app/messages', { replace: true })
    } catch (caught) {
      setError(friendlyError(caught, 'No se pudo importar la sesión'))
    } finally {
      setImportingRecovery(false)
    }
  }

  if (!client) {
    return (
      <StatusView
        title="Supabase no configurado"
        description="Copia .env.example a .env y completa tus claves."
      />
    )
  }

  return (
    <section className="auth-shell auth-shell-rich auth-shell-login">
      <div className="auth-login-stage">
        <div className="auth-card auth-login-card">
          <div className="section-stack auth-head-copy">
            <div>
              <p className="brand-tag auth-panel-tag">Acceso</p>
              <h1>Entrar a tu red social</h1>
              <p>
                Elige el método que te sirva hoy: contraseña, código, QR aprobado desde la app o
                una sesión de recuperación.
              </p>
            </div>
            <div className="chip-row auth-mode-row auth-mode-grid">
              <button
                type="button"
                className={mode === 'password' ? 'chip-btn active' : 'chip-btn'}
                onClick={() => changeMode('password')}
              >
                Contraseña
              </button>
              <button
                type="button"
                className={mode === 'code' ? 'chip-btn active' : 'chip-btn'}
                onClick={() => changeMode('code')}
              >
                Código
              </button>
              <button
                type="button"
                className={mode === 'recovery' ? 'chip-btn active' : 'chip-btn'}
                onClick={() => changeMode('recovery')}
              >
                Vincular sesión
              </button>
              <button
                type="button"
                className={mode === 'qr' ? 'chip-btn active' : 'chip-btn'}
                onClick={() => changeMode('qr')}
              >
                QR
              </button>
            </div>
          </div>

          <div className="surface-subcard auth-mode-note">
            <strong>{getModeTitle(mode)}</strong>
            <p>{getModeDescription(mode)}</p>
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
                  onChange={(event) => setEmail(event.target.value)}
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
              <button className="primary-btn" disabled={verifyingCode} type="submit">
                {verifyingCode ? 'Validando...' : 'Entrar con código'}
              </button>
              <small className="auth-foot">
                Si tu proveedor envía enlace mágico en lugar de OTP, basta con abrir el correo y tocar
                el enlace.
              </small>
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

          {error ? (
            <div className="auth-banner auth-banner-error" role="alert">
              <strong>{getErrorBannerTitle(error)}</strong>
              <p>{error}</p>
            </div>
          ) : null}
          {info ? (
            <div className="auth-banner auth-banner-info">
              <strong>Estado de acceso</strong>
              <p>{info}</p>
            </div>
          ) : null}

          <p className="auth-foot">
            ¿No tienes cuenta? <Link to="/register">Regístrate</Link>
          </p>
        </div>
      </div>
    </section>
  )
}
