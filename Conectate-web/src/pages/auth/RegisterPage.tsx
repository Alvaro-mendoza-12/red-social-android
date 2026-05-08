import { useState, type FormEvent } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { StatusView } from '../../components/StatusView'
import { signUpAndBootstrapProfile } from '../../core/auth/sessionBootstrap'
import { supabase } from '../../core/supabase'
import { friendlyError } from '../../core/utils'

export function RegisterPage() {
  const navigate = useNavigate()
  const client = supabase
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [displayName, setDisplayName] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [loading, setLoading] = useState(false)

  if (!client) {
    return (
      <StatusView
        title="Supabase no configurado"
        description="Copia .env.example a .env y completa tus claves."
      />
    )
  }

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (!client) return
    setLoading(true)
    setError(null)
    try {
      await signUpAndBootstrapProfile(client, {
        email,
        password,
        displayName,
      })
      navigate('/app/messages', { replace: true })
    } catch (caught) {
      setError(friendlyError(caught, 'No se pudo registrar la cuenta'))
    } finally {
      setLoading(false)
    }
  }

  return (
    <section className="auth-shell auth-shell-rich auth-shell-login">
      <form className="auth-card auth-login-card" onSubmit={handleSubmit}>
        <div className="section-stack auth-head-copy">
          <div>
            <div className="auth-brand-lockup">
              <div className="auth-brand-mark">
                <img src="/conectate-logo.svg" alt="Conectate" className="auth-brand-logo" />
              </div>
              <div className="auth-brand-copy">
                <p className="brand-tag auth-panel-tag">Conectate Web</p>
                <strong>Conectate</strong>
                <span>Tu identidad social vive igual en web y móvil.</span>
              </div>
            </div>
            <h1>Crea tu cuenta en Conectate</h1>
            <p>Abre tu perfil, mensajes y comunidad con el mismo backend compartido.</p>
          </div>
        </div>
        <label>
          Nombre visible
          <input
            type="text"
            required
            value={displayName}
            onChange={(event) => setDisplayName(event.target.value)}
          />
        </label>
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
            minLength={6}
            value={password}
            autoComplete="new-password"
            onChange={(event) => setPassword(event.target.value)}
          />
        </label>
        {error ? (
          <div className="auth-banner auth-banner-error" role="alert">
            <strong>No pudimos crear tu cuenta</strong>
            <p>{error}</p>
          </div>
        ) : null}
        <button className="primary-btn" disabled={loading} type="submit">
          {loading ? 'Creando tu acceso...' : 'Crear cuenta en Conectate'}
        </button>
        <p className="auth-foot">
          ¿Ya tienes cuenta? <Link to="/login">Inicia sesión</Link>
        </p>
      </form>
    </section>
  )
}
