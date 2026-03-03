import { useCallback, useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { StatusView } from '../../components/StatusView'
import { supabase } from '../../core/supabase'
import { friendlyError, textOf, type GenericRow } from '../../core/utils'
import { useAuth } from '../auth/AuthProvider'

type SettingsModel = {
  displayName: string
  username: string
  bio: string
  isPrivate: boolean
  profileVisibility: string
  privacyAccepted: boolean
}

function toBoolean(value: unknown): boolean {
  if (typeof value === 'boolean') return value
  if (typeof value === 'number') return value === 1
  if (typeof value === 'string') return value.toLowerCase() === 'true'
  return false
}

export function AccountSettingsPage() {
  const { user } = useAuth()
  const [model, setModel] = useState<SettingsModel>({
    displayName: '',
    username: '',
    bio: '',
    isPrivate: false,
    profileVisibility: 'everyone',
    privacyAccepted: false,
  })
  const [hasIsPrivate, setHasIsPrivate] = useState(false)
  const [hasProfileVisibility, setHasProfileVisibility] = useState(false)
  const [hasPrivacyAcceptance, setHasPrivacyAcceptance] = useState(false)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [info, setInfo] = useState<string | null>(null)
  const [savingProfile, setSavingProfile] = useState(false)
  const [savingPrivacy, setSavingPrivacy] = useState(false)
  const [savingPassword, setSavingPassword] = useState(false)
  const [newPassword, setNewPassword] = useState('')
  const [confirmPassword, setConfirmPassword] = useState('')

  const loadSettings = useCallback(async () => {
    if (!supabase || !user) {
      setLoading(false)
      return
    }
    setLoading(true)
    setError(null)
    setInfo(null)
    try {
      const { data, error: profileError } = await supabase
        .from('profiles')
        .select('*')
        .eq('id', user.id)
        .limit(1)
      if (profileError) throw profileError
      const row = ((data ?? []) as GenericRow[])[0] ?? {}
      setHasIsPrivate(Object.prototype.hasOwnProperty.call(row, 'is_private'))
      setHasProfileVisibility(Object.prototype.hasOwnProperty.call(row, 'profile_visibility'))
      setHasPrivacyAcceptance(Object.prototype.hasOwnProperty.call(row, 'privacy_policy_accepted'))
      setModel({
        displayName: textOf(row, ['display_name', 'displayName', 'full_name'], user.email ?? ''),
        username: textOf(row, ['username'], ''),
        bio: textOf(row, ['bio'], ''),
        isPrivate: toBoolean(row.is_private),
        profileVisibility: textOf(row, ['profile_visibility'], 'everyone'),
        privacyAccepted: toBoolean(row.privacy_policy_accepted),
      })
    } catch (caught) {
      setError(friendlyError(caught, 'No se pudo cargar ajustes'))
    } finally {
      setLoading(false)
    }
  }, [user])

  useEffect(() => {
    void loadSettings()
  }, [loadSettings])

  async function saveProfileBasics() {
    if (!supabase || !user || savingProfile) return
    setSavingProfile(true)
    setError(null)
    setInfo(null)
    try {
      const payload: Record<string, unknown> = {
        display_name: model.displayName.trim() || user.email || 'Usuario',
        username: model.username.trim() || null,
        bio: model.bio.trim() || null,
        updated_at: new Date().toISOString(),
      }
      const { error: updateError } = await supabase.from('profiles').update(payload).eq('id', user.id)
      if (updateError) throw updateError
      setInfo('Perfil actualizado.')
    } catch (caught) {
      setError(friendlyError(caught, 'No se pudo guardar perfil'))
    } finally {
      setSavingProfile(false)
    }
  }

  async function savePrivacySettings() {
    if (!supabase || !user || savingPrivacy) return
    setSavingPrivacy(true)
    setError(null)
    setInfo(null)
    try {
      const payload: Record<string, unknown> = {
        updated_at: new Date().toISOString(),
      }
      if (hasIsPrivate) payload.is_private = model.isPrivate
      if (hasProfileVisibility) payload.profile_visibility = model.profileVisibility
      if (hasPrivacyAcceptance && model.privacyAccepted) {
        payload.privacy_policy_accepted = true
        payload.privacy_policy_accepted_at = new Date().toISOString()
      }

      const keys = Object.keys(payload)
      if (keys.length <= 1) {
        setInfo('Tu backend no expone columnas de privacidad para editar desde web.')
        return
      }

      const { error: updateError } = await supabase.from('profiles').update(payload).eq('id', user.id)
      if (updateError) throw updateError
      setInfo('Privacidad actualizada.')
    } catch (caught) {
      setError(friendlyError(caught, 'No se pudo guardar privacidad'))
    } finally {
      setSavingPrivacy(false)
    }
  }

  async function updatePassword() {
    if (!supabase || savingPassword) return
    const password = newPassword.trim()
    if (password.length < 8) {
      setError('La nueva contraseña debe tener al menos 8 caracteres.')
      return
    }
    if (password !== confirmPassword.trim()) {
      setError('La confirmación no coincide con la contraseña nueva.')
      return
    }

    setSavingPassword(true)
    setError(null)
    setInfo(null)
    try {
      const { error: passwordError } = await supabase.auth.updateUser({ password })
      if (passwordError) throw passwordError
      setNewPassword('')
      setConfirmPassword('')
      setInfo('Contraseña actualizada.')
    } catch (caught) {
      setError(friendlyError(caught, 'No se pudo actualizar contraseña'))
    } finally {
      setSavingPassword(false)
    }
  }

  if (!supabase) {
    return <StatusView title="Supabase no configurado" description="Configura .env para usar ajustes." />
  }

  if (loading) {
    return <StatusView title="Cargando ajustes" description="Espera un momento..." />
  }

  return (
    <section className="section-stack">
      <div className="row-between">
        <Link to="/app/profile" className="inline-link">
          ← Volver a perfil
        </Link>
        <h2>Ajustes de cuenta</h2>
      </div>

      {error ? <StatusView title="Ajustes no disponibles" description={error} /> : null}
      {info ? <p>{info}</p> : null}

      <article className="surface-card section-stack">
        <h3>Perfil</h3>
        <div className="composer">
          <input
            className="text-input"
            placeholder="Nombre visible"
            value={model.displayName}
            onChange={(event) =>
              setModel((current) => ({ ...current, displayName: event.target.value }))
            }
          />
          <input
            className="text-input tiny-input"
            placeholder="Usuario"
            value={model.username}
            onChange={(event) => setModel((current) => ({ ...current, username: event.target.value }))}
          />
          <button className="ghost-btn" type="button" disabled={savingProfile} onClick={() => void saveProfileBasics()}>
            {savingProfile ? 'Guardando...' : 'Guardar perfil'}
          </button>
        </div>
        <textarea
          className="text-input textarea-input"
          rows={3}
          placeholder="Biografía"
          value={model.bio}
          onChange={(event) => setModel((current) => ({ ...current, bio: event.target.value }))}
        />
      </article>

      <article className="surface-card section-stack">
        <h3>Privacidad</h3>
        <div className="composer">
          <label className="checkbox-inline">
            <input
              type="checkbox"
              checked={model.isPrivate}
              disabled={!hasIsPrivate}
              onChange={(event) => setModel((current) => ({ ...current, isPrivate: event.target.checked }))}
            />
            Cuenta privada
          </label>
          <select
            className="text-input tiny-input"
            value={model.profileVisibility}
            disabled={!hasProfileVisibility}
            onChange={(event) =>
              setModel((current) => ({ ...current, profileVisibility: event.target.value }))
            }
          >
            <option value="everyone">Todos</option>
            <option value="followers">Seguidores</option>
            <option value="friends">Amigos</option>
            <option value="nobody">Nadie</option>
          </select>
          <button className="ghost-btn" type="button" disabled={savingPrivacy} onClick={() => void savePrivacySettings()}>
            {savingPrivacy ? 'Guardando...' : 'Guardar privacidad'}
          </button>
        </div>
        {hasPrivacyAcceptance ? (
          <label className="checkbox-inline">
            <input
              type="checkbox"
              checked={model.privacyAccepted}
              onChange={(event) =>
                setModel((current) => ({ ...current, privacyAccepted: event.target.checked }))
              }
            />
            Política de privacidad aceptada
          </label>
        ) : null}
      </article>

      <article className="surface-card section-stack">
        <h3>Seguridad</h3>
        <div className="composer">
          <input
            className="text-input"
            type="password"
            autoComplete="new-password"
            placeholder="Nueva contraseña"
            value={newPassword}
            onChange={(event) => setNewPassword(event.target.value)}
          />
          <input
            className="text-input"
            type="password"
            autoComplete="new-password"
            placeholder="Confirmar contraseña"
            value={confirmPassword}
            onChange={(event) => setConfirmPassword(event.target.value)}
          />
          <button className="primary-btn" type="button" disabled={savingPassword} onClick={() => void updatePassword()}>
            {savingPassword ? 'Actualizando...' : 'Cambiar contraseña'}
          </button>
        </div>
      </article>
    </section>
  )
}
