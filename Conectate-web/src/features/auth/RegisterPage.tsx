import { useState, type FormEvent } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { StatusView } from '../../components/StatusView'
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
      const { error: signUpError } = await client.auth.signUp({
        email: email.trim(),
        password,
        options: {
          data: {
            display_name: displayName.trim(),
          },
        },
      })
      if (signUpError) throw signUpError
      navigate('/app/feed', { replace: true })
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
        <p>Usa el mismo backend Supabase del móvil.</p>
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
