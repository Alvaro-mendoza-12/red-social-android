import { useState, type FormEvent } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { StatusView } from '../../components/StatusView'
import { isFallbackAuthConfigured } from '../../core/auth/fallbackAuth'
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

  if (!client && !isFallbackAuthConfigured) {
    return (
      <StatusView
        title="Backend no configurado"
        description="Configura VITE_FALLBACK_API_URL para usar tu servidor Ubuntu, o VITE_SUPABASE_URL/VITE_SUPABASE_ANON_KEY si mantienes el puente anterior."
      />
    )
  }

  async function handleSubmit(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
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
    <section className="auth-shell">
      <form className="auth-card" onSubmit={handleSubmit}>
        <p className="brand-tag">Conectate-web</p>
        <h1>Crear cuenta</h1>
        <p>Usa tu servidor Ubuntu como backend principal.</p>
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
            minLength={8}
            value={password}
            autoComplete="new-password"
            onChange={(event) => setPassword(event.target.value)}
          />
        </label>
        {error ? <p className="error-text">{error}</p> : null}
        <button className="primary-btn" disabled={loading} type="submit">
          {loading ? 'Creando...' : 'Crear cuenta'}
        </button>
        <p className="auth-foot">
          ¿Ya tienes cuenta? <Link to="/login">Inicia sesión</Link>
        </p>
      </form>
    </section>
  )
}
