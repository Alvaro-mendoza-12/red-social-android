import { useState, type FormEvent } from 'react'
import { Link, useNavigate, useSearchParams } from 'react-router-dom'
import { requestFallbackPasswordReset, verifyFallbackPasswordReset, confirmFallbackPasswordReset,
  FallbackPasswordChangedNeedsTwoFactorError } from '../../core/auth/fallbackAuth'

const draftKey = 'novvin:email-recovery-draft:v1'
type Step = 'email' | 'code' | 'password'
function readDraft(): { email: string; step: Step } {
  try {
    const value = JSON.parse(sessionStorage.getItem(draftKey) || '{}')
    if (typeof value.email === 'string' && Date.now() - Number(value.savedAt) < 15 * 60_000) {
      return { email: value.email, step: value.requested ? 'code' : 'email' }
    }
  } catch { /* Storage can be unavailable in a private browser. */ }
  return { email: '', step: 'email' }
}
function saveDraft(email: string) {
  try { sessionStorage.setItem(draftKey, JSON.stringify({ email, requested: true, savedAt: Date.now() })) } catch { /* Optional recovery of the screen. */ }
}
function resetError(error: unknown): string {
  const code = error instanceof Error ? error.message : ''
  if (/INVALID_OR_EXPIRED_EMAIL_CODE/.test(code)) return 'El código no es válido o venció. Revisa los seis dígitos o solicita otro.'
  if (/RATE_LIMIT|TOO_MANY|429/.test(code)) return 'Has hecho varios intentos. Espera unos minutos y vuelve a intentar.'
  if (/RESET_TOKEN|INVALID.*TOKEN/.test(code)) return 'La autorización venció. Verifica un nuevo código para continuar.'
  return 'No se pudo completar la acción. Revisa tu conexión e inténtalo de nuevo.'
}

export function ForgotPasswordPage() {
  const [params] = useSearchParams(), navigate = useNavigate()
  const [draft] = useState(readDraft)
  const [email, setEmail] = useState(params.get('email') || draft.email)
  const [step, setStep] = useState<Step>(params.get('email') && params.get('email') !== draft.email ? 'email' : draft.step)
  const [code, setCode] = useState(''), [grant, setGrant] = useState('')
  const [password, setPassword] = useState(''), [confirmation, setConfirmation] = useState('')
  const [busy, setBusy] = useState(false), [error, setError] = useState(''), [info, setInfo] = useState('')
  const normalizedEmail = email.trim().toLowerCase()
  async function request() {
    if (busy || !/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(normalizedEmail)) return
    setBusy(true); setError(''); setInfo('')
    try {
      await requestFallbackPasswordReset(normalizedEmail)
      setEmail(normalizedEmail); setStep('code'); setCode(''); setGrant(''); saveDraft(normalizedEmail)
      setInfo('Si el correo corresponde a una cuenta, recibirás un código de seis dígitos. Revisa también Spam. Puedes abrir tu correo y volver aquí.')
    } catch (caught) { setError(resetError(caught)) }
    finally { setBusy(false) }
  }
  async function submit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (busy) return
    if (step === 'email') return request()
    if (step === 'code' && !/^\d{6}$/.test(code)) { setError('Escribe los seis dígitos del correo.'); return }
    if (step === 'password') {
      if (password.length < 8 || new TextEncoder().encode(password).length > 72) { setError('Usa al menos 8 caracteres y un máximo de 72 bytes.'); return }
      if (password !== confirmation) { setError('Las contraseñas no coinciden.'); return }
    }
    setBusy(true); setError(''); setInfo('')
    try {
      if (step === 'code') {
        const verified = await verifyFallbackPasswordReset(normalizedEmail, code)
        if (!verified.resetToken) throw new Error('RESET_TOKEN_MISSING')
        setGrant(verified.resetToken); setCode(''); setStep('password')
      } else {
        await confirmFallbackPasswordReset(grant, password)
        try { sessionStorage.removeItem(draftKey) } catch { /* Optional draft. */ }
        navigate('/app/messages', { replace: true })
      }
    } catch (caught) {
      if (caught instanceof FallbackPasswordChangedNeedsTwoFactorError) {
        try { sessionStorage.removeItem(draftKey) } catch { /* Optional draft. */ }
        navigate(`/login?email=${encodeURIComponent(normalizedEmail)}&passwordChanged=1`, { replace: true })
      } else { setError(resetError(caught)) }
    } finally { setBusy(false) }
  }
  return <section className="auth-shell auth-shell-centered">
    <div className="auth-layout-grid auth-layout-single"><article className="auth-card auth-panel-card auth-login-card email-recovery-card">
      <img src="/novvin-logo.png" alt="Novvin" className="auth-logo-img" />
      <h1>Recupera tu cuenta</h1>
      <p>{step === 'email' ? 'Te enviaremos un código para cambiar tu contraseña.' : step === 'code' ? `Escribe el código enviado a ${normalizedEmail}.` : 'Código verificado. Elige tu nueva contraseña.'}</p>
      <p aria-label="Paso actual">Paso {step === 'email' ? 1 : step === 'code' ? 2 : 3} de 3</p>
      <form className="section-stack" onSubmit={submit}>
        {step === 'email' && <label>Correo de tu cuenta<input type="email" autoComplete="email" required value={email} onChange={event => setEmail(event.target.value)} disabled={busy} /></label>}
        {step === 'code' && <label>Código de seis dígitos<input autoFocus inputMode="numeric" autoComplete="one-time-code" pattern="[0-9]{6}" maxLength={6} required value={code} onChange={event => setCode(event.target.value.replace(/\D/g, '').slice(0, 6))} disabled={busy} /></label>}
        {step === 'password' && <><label>Nueva contraseña<input autoFocus type="password" autoComplete="new-password" minLength={8} required value={password} onChange={event => setPassword(event.target.value)} disabled={busy} /></label>
          <label>Repite la nueva contraseña<input type="password" autoComplete="new-password" required value={confirmation} onChange={event => setConfirmation(event.target.value)} disabled={busy} /></label></>}
        {error && <p role="alert" className="error-text">{error}</p>}
        {info && <p role="status">{info}</p>}
        <button className="primary-btn" type="submit" disabled={busy || (step === 'code' && code.length !== 6)}>{busy ? 'Procesando…' : step === 'email' ? 'Enviar código' : step === 'code' ? 'Verificar código' : 'Guardar contraseña y entrar'}</button>
      </form>
      {step === 'code' && <><button type="button" className="ghost-btn" disabled={busy} onClick={() => void request()}>Enviar otro código</button>
        <button type="button" className="ghost-btn" disabled={busy} onClick={() => { setStep('email'); setError(''); setInfo('') }}>Cambiar correo</button></>}
      {step === 'password' && <button type="button" className="ghost-btn" disabled={busy} onClick={() => { setGrant(''); setPassword(''); setConfirmation(''); setStep('code'); setError('') }}>Volver a verificar un código</button>}
      <Link to="/login">Volver a iniciar sesión</Link>
    </article></div>
  </section>
}
