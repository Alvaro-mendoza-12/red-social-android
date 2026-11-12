import { useState, type FormEvent } from 'react'
import {
  completeMigratedPasswordReset,
  confirmFallbackPasswordReset,
  isFallbackAuthConfigured,
  requestFallbackPasswordReset,
} from '../../core/auth/fallbackAuth'
import { authErrorMessage } from '../../core/auth/authErrorMessage'
import { AuthErrorText } from '../../components/AuthErrorText'

interface PasswordResetProps {
  email: string
  migrationToken?: string
  displayName?: string
  onCancel?: () => void
  onSuccess?: () => void
}

export function PasswordResetPage({
  email,
  migrationToken,
  displayName,
  onCancel,
  onSuccess,
}: PasswordResetProps) {
  const [step, setStep] = useState<'request' | 'confirm' | 'success'>('request')
  const [newPassword, setNewPassword] = useState('')
  const [confirmPassword, setConfirmPassword] = useState('')
  const [resetToken, setResetToken] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [info, setInfo] = useState<string | null>(null)
  const [loading, setLoading] = useState(false)
  const [showPassword, setShowPassword] = useState(false)

  async function handleRequestReset() {
    if (loading) return

    setLoading(true)
    setError(null)
    setInfo(null)

    try {
      if (migrationToken) {
        setResetToken(migrationToken)
        setStep('confirm')
        setInfo('🔐 Completa tu nueva contraseña para migrar tu cuenta')
        return
      }

      if (!isFallbackAuthConfigured) {
        throw new Error('Servidor Ubuntu no configurado.')
      }

      const reset = await requestFallbackPasswordReset(email)
      if (reset.resetToken) {
        setResetToken(reset.resetToken)
        setStep('confirm')
        setInfo('Cambio de contraseña preparado automáticamente en Ubuntu.')
        return
      }

      setInfo(
        reset.delivery === 'console'
          ? 'Ubuntu preparó el cambio. Continúa creando tu nueva contraseña.'
          : 'Ubuntu preparó el cambio. Continúa creando tu nueva contraseña.',
      )
    } catch (caught) {
      setError(authErrorMessage(caught, 'No se pudo iniciar la migración'))
    } finally {
      setLoading(false)
    }
  }

  async function handleConfirmReset(event: FormEvent<HTMLFormElement>) {
    event.preventDefault()
    if (loading || !resetToken) return

    const nextPassword = newPassword
    if (nextPassword.length < 8) {
      setError('🔒 Mínimo 8 caracteres para tu contraseña')
      return
    }
    if (nextPassword !== confirmPassword) {
      setError('❌ Las contraseñas no coinciden')
      return
    }

    setLoading(true)
    setError(null)
    setInfo(null)

    try {
      if (migrationToken) {
        await completeMigratedPasswordReset(resetToken, nextPassword)
      } else if (isFallbackAuthConfigured) {
        await confirmFallbackPasswordReset(resetToken, nextPassword)
      } else {
        throw new Error('No se pudo confirmar la migración.')
      }

      setStep('success')
      setNewPassword('')
      setConfirmPassword('')
      setResetToken('')

      setTimeout(() => {
        onSuccess?.()
      }, 2000)
    } catch (caught) {
      setError(authErrorMessage(caught, '❌ No se pudo cambiar la contraseña'))
    } finally {
      setLoading(false)
    }
  }

  if (step === 'success') {
    return (
      <div className="password-reset-modal">
        <div className="password-reset-success">
          <div className="success-icon">✅</div>
          <h3>¡Contraseña actualizada!</h3>
          <p>Tu cuenta ha sido migrada exitosamente. Redirigiendo...</p>
        </div>
      </div>
    )
  }

  return (
    <div className="password-reset-modal password-reset-container">
      <div className="password-reset-card">
        <div className="password-reset-header">
          <h2>🔐 Cambiar Contraseña</h2>
          <p className="reset-subtitle">
            {displayName ? `Bienvenido, ${displayName}` : 'Migra tu cuenta a Ubuntu'}
          </p>
        </div>

        {error && <div className="status-error status-alert" role="alert"><AuthErrorText message={error} /></div>}
        {info && <div className="status-info status-alert">{info}</div>}

        {step === 'request' ? (
          <div className="password-reset-step">
            <p className="reset-description">
              Tu cuenta fue creada en Supabase pero ahora estamos en Ubuntu. Crea una nueva contraseña para
              acceder.
            </p>
            <button
              type="button"
              onClick={handleRequestReset}
              disabled={loading}
              className="btn btn-primary btn-lg"
            >
              {loading ? '⏳ Procesando...' : '→ Continuar'}
            </button>
          </div>
        ) : (
          <form onSubmit={handleConfirmReset} className="password-reset-form">
            <div className="form-group form-group-stacked">
              <label htmlFor="new-password">Nueva Contraseña</label>
              <div className="password-input-wrapper">
                <input
                  id="new-password"
                  type={showPassword ? 'text' : 'password'}
                  value={newPassword}
                  onChange={(e) => setNewPassword(e.target.value)}
                  placeholder="Mínimo 8 caracteres"
                  className="input input-lg"
                  disabled={loading}
                  required
                />
                <button
                  type="button"
                  onClick={() => setShowPassword(!showPassword)}
                  className="password-toggle"
                  aria-label={showPassword ? 'Ocultar contraseña' : 'Mostrar contraseña'}
                  aria-pressed={showPassword}
                >
                  {showPassword ? '👁️' : '👁️‍🗨️'}
                </button>
              </div>
            </div>

            <div className="form-group form-group-stacked">
              <label htmlFor="confirm-password">Confirmar Contraseña</label>
              <input
                id="confirm-password"
                type={showPassword ? 'text' : 'password'}
                value={confirmPassword}
                onChange={(e) => setConfirmPassword(e.target.value)}
                placeholder="Repite tu contraseña"
                className="input input-lg"
                disabled={loading}
                required
              />
            </div>

            <div className="password-strength-indicator">
              <div className="strength-bar">
                <div
                  className={`strength-fill strength-${
                    newPassword.length >= 16
                      ? 'strong'
                      : newPassword.length >= 8
                        ? 'medium'
                        : 'weak'
                  }`}
                  style={{ width: `${Math.min((newPassword.length / 20) * 100, 100)}%` }}
                />
              </div>
              <small className="strength-text">
                {newPassword.length === 0
                  ? 'Escribe tu contraseña'
                  : newPassword.length >= 16
                    ? '✅ Contraseña fuerte'
                    : newPassword.length >= 8
                      ? '⚠️ Contraseña aceptable'
                      : `❌ Muy corta (${newPassword.length}/8)`}
              </small>
            </div>

            <button type="submit" disabled={loading} className="btn btn-primary btn-lg">
              {loading ? '⏳ Guardando...' : '✓ Guardar Contraseña'}
            </button>
          </form>
        )}

        <div className="password-reset-footer">
          {onCancel && (
            <button type="button" onClick={onCancel} className="btn btn-ghost btn-sm">
              Cancelar
            </button>
          )}
        </div>
      </div>
    </div>
  )
}
