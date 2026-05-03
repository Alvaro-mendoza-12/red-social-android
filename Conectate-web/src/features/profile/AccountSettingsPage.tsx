import { useCallback, useEffect, useMemo, useState } from 'react'
import { QrCodeTile } from '../../components/QrCodeTile'
import { Link } from 'react-router-dom'
import { StatusView } from '../../components/StatusView'
import { maskSensitiveToken } from '../../core/auth/recoverySession'
import { supabase } from '../../core/supabase'
import { useWebPreferences } from '../../core/webPreferences'
import { friendlyError, textOf, type GenericRow } from '../../core/utils'
import { useAuth } from '../../core/auth/AuthProvider'

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
  const { user, session } = useAuth()
  const [webPreferences, updateWebPreferences] = useWebPreferences()
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
  const [sendingAccessLink, setSendingAccessLink] = useState(false)
  const [newPassword, setNewPassword] = useState('')
  const [confirmPassword, setConfirmPassword] = useState('')

  const currentEmail = user?.email?.trim() ?? ''
  const profileQrValue = useMemo(
    () => (user?.id ? `conectate://profile/${user.id}` : ''),
    [user?.id],
  )
  const sessionFingerprint = useMemo(() => {
    const token = session?.access_token?.trim() ?? ''
    return token ? maskSensitiveToken(token) : 'Sin token activo'
  }, [session?.access_token])

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

  async function sendAccessLinkToEmail() {
    if (!supabase || !currentEmail || sendingAccessLink) return
    setSendingAccessLink(true)
    setError(null)
    setInfo(null)
    try {
      const { error: otpError } = await supabase.auth.signInWithOtp({
        email: currentEmail,
        options: {
          shouldCreateUser: false,
          emailRedirectTo: `${window.location.origin}/login`,
        },
      })
      if (otpError) throw otpError
      setInfo('Enviamos un acceso por correo. Puedes usar el código o abrir el enlace desde el mensaje recibido.')
    } catch (caught) {
      setError(friendlyError(caught, 'No se pudo enviar el acceso por correo'))
    } finally {
      setSendingAccessLink(false)
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
      <article className="surface-card settings-hero-card">
        <div className="row-between settings-hero-head">
          <div>
            <Link to="/app/profile" className="inline-link">
              ← Volver a perfil
            </Link>
            <h2>Ajustes de cuenta</h2>
            <p>
              Perfil, privacidad, seguridad y preferencias web en un solo panel, más alineado con la
              experiencia móvil.
            </p>
          </div>
          <div className="settings-hero-meta">
            <span className="folder-tag">Web profesional</span>
            <small>Sesión: {sessionFingerprint}</small>
          </div>
        </div>
      </article>

      {error ? <StatusView title="Ajustes no disponibles" description={error} /> : null}
      {info ? <p>{info}</p> : null}

      <div className="settings-grid">
        <article className="surface-card section-stack">
          <div>
            <h3>Perfil</h3>
            <p>Datos visibles para tu comunidad.</p>
          </div>
          <div className="composer settings-stack">
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
            <textarea
              className="text-input textarea-input"
              rows={4}
              placeholder="Biografía"
              value={model.bio}
              onChange={(event) => setModel((current) => ({ ...current, bio: event.target.value }))}
            />
            <button className="ghost-btn" type="button" disabled={savingProfile} onClick={() => void saveProfileBasics()}>
              {savingProfile ? 'Guardando...' : 'Guardar perfil'}
            </button>
          </div>
        </article>

        <article className="surface-card section-stack">
          <div>
            <h3>Privacidad</h3>
            <p>Define quién te ve y qué señales compartes.</p>
          </div>
          <div className="composer settings-stack">
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
            <button className="ghost-btn" type="button" disabled={savingPrivacy} onClick={() => void savePrivacySettings()}>
              {savingPrivacy ? 'Guardando...' : 'Guardar privacidad'}
            </button>
          </div>
        </article>
      </div>

      <div className="settings-grid">
        <article className="surface-card section-stack">
          <div>
            <h3>Seguridad</h3>
            <p>Cambia tu contraseña y protege el acceso a la cuenta.</p>
          </div>
          <div className="composer settings-stack">
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

        <article className="surface-card section-stack">
          <div>
            <h3>Acceso y QR</h3>
            <p>
              Mantén el acceso por correo, pero suma también el QR seguro de Conectate Web y el QR
              de perfil para compartirte rápido.
            </p>
          </div>
          <div className="settings-access-card">
            <div className="surface-subcard">
              <strong>Correo activo</strong>
              <p>{currentEmail || 'Correo no disponible'}</p>
            </div>
            <div className="surface-subcard">
              <strong>Entrada por código o enlace</strong>
              <p>Enviaremos acceso al correo actual para abrir otra sesión o validar con OTP.</p>
            </div>
            <div className="surface-subcard">
              <strong>Conectate Web por QR</strong>
              <p>Genera un código temporal y apruébalo desde tu móvil como en WhatsApp Web.</p>
            </div>
            <div className="row-actions wrap">
              <button className="ghost-btn" type="button" disabled={sendingAccessLink || !currentEmail} onClick={() => void sendAccessLinkToEmail()}>
                {sendingAccessLink ? 'Enviando...' : 'Enviar acceso al correo'}
              </button>
              <Link className="ghost-btn" to={`/login?mode=code${currentEmail ? `&email=${encodeURIComponent(currentEmail)}` : ''}`}>
                Abrir login por código
              </Link>
              <Link className="ghost-btn" to="/login?mode=recovery">
                Vincular con enlace
              </Link>
              <Link className="ghost-btn" to="/login?mode=qr">
                Abrir login por QR
              </Link>
            </div>
            {profileQrValue ? (
              <QrCodeTile
                value={profileQrValue}
                title="Mi código de perfil"
                description="Cuando alguien lo escanea desde Conectate móvil, abre tu perfil directamente."
                compact
              />
            ) : null}
          </div>
        </article>
      </div>

      <article className="surface-card section-stack">
        <div>
          <h3>Preferencias web</h3>
          <p>Pequeños detalles que hacen que escritorio se sienta más pulido y ligero.</p>
        </div>
        <div className="settings-toggle-grid">
          <label className="settings-toggle-card">
            <div>
              <strong>Inbox compacta</strong>
              <p>Reduce el ancho visual de la lista de conversaciones en escritorio.</p>
            </div>
            <input
              type="checkbox"
              checked={webPreferences.compactInbox}
              onChange={(event) => updateWebPreferences({ compactInbox: event.target.checked })}
            />
          </label>
          <label className="settings-toggle-card">
            <div>
              <strong>Ahorro de datos</strong>
              <p>Evita precargas pesadas en multimedia del feed y prioriza una carga más sobria.</p>
            </div>
            <input
              type="checkbox"
              checked={webPreferences.lowDataMode}
              onChange={(event) => updateWebPreferences({ lowDataMode: event.target.checked })}
            />
          </label>
        </div>
      </article>
    </section>
  )
}
