import { useState, type FormEvent } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { StatusView } from '../../components/StatusView'
import { supabase } from '../../core/supabase'
import { friendlyError } from '../../core/utils'

export function LoginPage() {
  const navigate = useNavigate()
  const client = supabase
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
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
      const { error: signInError } = await client.auth.signInWithPassword({
        email: email.trim(),
        password,
      })
      if (signInError) throw signInError
      navigate('/app/feed', { replace: true })
    } catch (caught) {
      setError(friendlyError(caught, 'No se pudo iniciar sesión'))
    } finally {
      setLoading(false)
    }
  }

  return (
    <section className="auth-shell">
      <form className="auth-card" onSubmit={handleSubmit}>
        <p className="brand-tag">Conectate-web</p>
        <h1>Entrar</h1>
        <p>Versión web del app móvil, conectada por Supabase.</p>
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
        {error ? <p className="error-text">{error}</p> : null}
        <button className="primary-btn" disabled={loading} type="submit">
          {loading ? 'Entrando...' : 'Iniciar sesión'}
        </button>
        <p className="auth-foot">
          ¿No tienes cuenta? <Link to="/register">Regístrate</Link>
        </p>
      </form>
    </section>
  )
}
