import { useCallback, useEffect, useMemo, useState } from 'react'
import { QrCodeTile } from '../../components/QrCodeTile'
import { Link } from 'react-router-dom'
import { StatusView } from '../../components/StatusView'
import { maskSensitiveToken } from '../../core/auth/recoverySession'
import { supabase } from '../../core/supabase'
import { useWebPreferences } from '../../core/webPreferences'
import { friendlyError, textOf, type GenericRow } from '../../core/utils'
import { useAuth } from '../../core/auth/AuthProvider'
import { changeFallbackPassword } from '../../core/auth/fallbackAuth'
import { loadFallbackProfileMe, updateFallbackProfileMe, uploadFallbackFile } from '../../core/fallbackData'

type SettingsModel = {
  displayName: string
  username: string
  bio: string
  avatarUrl: string
  coverUrl: string
  interestsText: string
  linkedGamesText: string
  gameLogoUrlsText: string
  favoriteMusicUrl: string
  favoriteBook: string
  favoriteSport: string
  career: string
  availabilitySchedule: string
  lastSeenVisibility: string
  onlineStatusVisibility: string
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

function csvFromArray(value: unknown): string {
  if (!Array.isArray(value)) return ''
  return value.map((entry) => String(entry).trim()).filter(Boolean).join(', ')
}

function csvToArray(value: string): string[] {
  return value
    .split(',')
    .map((entry) => entry.trim())
    .filter(Boolean)
}

function gameLogoTextFromJson(value: unknown): string {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return ''
  return Object.entries(value as Record<string, unknown>)
    .map(([game, url]) => `${game}=${String(url ?? '').trim()}`)
    .join('\n')
}

function gameLogoJsonFromText(value: string): Record<string, string> {
  return Object.fromEntries(
    value
      .split('\n')
      .map((entry) => entry.trim())
      .filter(Boolean)
      .map((entry) => {
        const separator = entry.indexOf('=')
        if (separator === -1) return [entry, ''] as const
        return [entry.slice(0, separator).trim(), entry.slice(separator + 1).trim()] as const
      })
      .filter(([game]) => Boolean(game)),
  )
}

function isMissingColumnError(error: unknown): boolean {
  const message = error instanceof Error ? error.message.toLowerCase() : String(error ?? '').toLowerCase()
  return message.includes('column') || message.includes('42703')
}

export function AccountSettingsPage() {
  const { user, session, isFallbackSession } = useAuth()
  const [webPreferences, updateWebPreferences] = useWebPreferences()
  const [model, setModel] = useState<SettingsModel>({
    displayName: '',
    username: '',
    bio: '',
    avatarUrl: '',
    coverUrl: '',
    interestsText: '',
    linkedGamesText: '',
    gameLogoUrlsText: '',
    favoriteMusicUrl: '',
    favoriteBook: '',
    favoriteSport: '',
    career: '',
    availabilitySchedule: '',
    lastSeenVisibility: 'everyone',
    onlineStatusVisibility: 'everyone',
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
  const [uploadingAsset, setUploadingAsset] = useState<'avatar' | 'cover' | null>(null)
  const [sendingAccessLink, setSendingAccessLink] = useState(false)
  const [currentPassword, setCurrentPassword] = useState('')
  const [newPassword, setNewPassword] = useState('')
  const [confirmPassword, setConfirmPassword] = useState('')

  const currentEmail = user?.email?.trim() ?? ''
  const profileQrValue = useMemo(
    () => (user?.id ? `conectate://profile/${user.id}` : ''),
    [user?.id],
  )
  const sessionFingerprint = useMemo(() => {
    const token = session?.access_token?.trim() ?? ''
    if (isFallbackSession) return 'Servidor local'
    return token ? maskSensitiveToken(token) : 'Sin token activo'
  }, [isFallbackSession, session?.access_token])
  const profileCompletion = useMemo(() => {
    const checks = [
      model.displayName,
      model.username,
      model.bio,
      model.avatarUrl,
      model.coverUrl,
      model.interestsText,
      model.linkedGamesText,
      model.favoriteMusicUrl,
      model.favoriteBook,
      model.favoriteSport,
      model.career,
      model.availabilitySchedule,
    ]
    const filled = checks.filter((entry) => entry.trim().length > 0).length
    return Math.round((filled / checks.length) * 100)
  }, [
    model.availabilitySchedule,
    model.avatarUrl,
    model.bio,
    model.career,
    model.coverUrl,
    model.displayName,
    model.favoriteBook,
    model.favoriteMusicUrl,
    model.favoriteSport,
    model.interestsText,
    model.linkedGamesText,
    model.username,
  ])
  const privacySummary = useMemo(() => {
    if (hasIsPrivate && model.isPrivate) return 'Cuenta privada'
    if (hasProfileVisibility) {
      if (model.profileVisibility === 'followers') return 'Visible a seguidores'
      if (model.profileVisibility === 'friends') return 'Visible a amigos'
      if (model.profileVisibility === 'nobody') return 'Oculta'
    }
    return 'Visible para todos'
  }, [hasIsPrivate, hasProfileVisibility, model.isPrivate, model.profileVisibility])
  const settingsSummaryCards = useMemo(
    () => [
      {
        label: 'Perfil',
        value: `${profileCompletion}%`,
        hint: profileCompletion >= 80 ? 'Listo para verse solido en web.' : 'Todavia puedes completar mas campos.',
      },
      {
        label: 'Privacidad',
        value: privacySummary,
        hint: `Ultimo acceso: ${model.lastSeenVisibility}`,
      },
      {
        label: 'Acceso',
        value: currentEmail || 'Sin correo',
        hint: 'Correo y QR seguros comparten el mismo flujo de sesion.',
      },
      {
        label: 'Web',
        value: webPreferences.themeMode,
        hint: `${webPreferences.language.toUpperCase()} · ${webPreferences.textScale}`,
      },
    ],
    [
      currentEmail,
      model.lastSeenVisibility,
      privacySummary,
      profileCompletion,
      webPreferences.language,
      webPreferences.textScale,
      webPreferences.themeMode,
    ],
  )

  const loadSettings = useCallback(async () => {
    if (isFallbackSession) {
      setLoading(true)
      setError(null)
      try {
        const response = await loadFallbackProfileMe()
        const profile = response.profile ?? {}
        const metadata = (user?.user_metadata ?? {}) as Record<string, unknown>
        setModel((current) => ({
          ...current,
          displayName: textOf(profile, ['display_name', 'displayName', 'full_name'], textOf(metadata, ['display_name', 'full_name'], user?.email ?? '')),
          username: textOf(profile, ['username'], ''),
          bio: textOf(profile, ['bio'], ''),
          avatarUrl: textOf(profile, ['avatar_url', 'avatarUrl', 'photo_url', 'profile_image'], textOf(metadata, ['avatar_url'], '')),
          coverUrl: textOf(profile, ['cover_url', 'coverUrl', 'banner_url'], ''),
          lastSeenVisibility: textOf(profile, ['last_seen_visibility', 'lastSeenVisibility'], current.lastSeenVisibility),
          onlineStatusVisibility: textOf(profile, ['online_status_visibility', 'onlineStatusVisibility'], current.onlineStatusVisibility),
          isPrivate: toBoolean(profile.is_private ?? profile.isPrivate),
          profileVisibility: textOf(profile, ['profile_visibility', 'profileVisibility'], current.profileVisibility),
        }))
        setHasIsPrivate(true)
        setHasProfileVisibility(true)
        setHasPrivacyAcceptance(false)
      } catch (caught) {
        setError(friendlyError(caught, 'No se pudo cargar ajustes locales'))
      } finally {
        setLoading(false)
      }
      return
    }
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
        avatarUrl: textOf(row, ['avatar_url', 'photo_url', 'profile_image'], ''),
        coverUrl: textOf(row, ['cover_url', 'banner_url'], ''),
        interestsText: csvFromArray(row.interests),
        linkedGamesText: csvFromArray(row.linked_games),
        gameLogoUrlsText: gameLogoTextFromJson(row.game_logo_urls),
        favoriteMusicUrl: textOf(row, ['favorite_music_url'], ''),
        favoriteBook: textOf(row, ['favorite_book'], ''),
        favoriteSport: textOf(row, ['favorite_sport'], ''),
        career: textOf(row, ['career'], ''),
        availabilitySchedule: textOf(row, ['availability_schedule'], ''),
        lastSeenVisibility: textOf(row, ['last_seen_visibility'], 'everyone'),
        onlineStatusVisibility: textOf(row, ['online_status_visibility'], 'everyone'),
        isPrivate: toBoolean(row.is_private),
        profileVisibility: textOf(row, ['profile_visibility'], 'everyone'),
        privacyAccepted: toBoolean(row.privacy_policy_accepted),
      })
    } catch (caught) {
      setError(friendlyError(caught, 'No se pudo cargar ajustes'))
    } finally {
      setLoading(false)
    }
  }, [isFallbackSession, user])

  useEffect(() => {
    void loadSettings()
  }, [loadSettings])

  async function saveProfileBasics() {
    if (isFallbackSession) {
      if (savingProfile) return
      setSavingProfile(true)
      setError(null)
      setInfo(null)
      try {
        const response = await updateFallbackProfileMe({
          displayName: model.displayName.trim() || user?.email || 'Usuario',
          bio: model.bio.trim() || null,
          avatarUrl: model.avatarUrl.trim() || null,
          coverUrl: model.coverUrl.trim() || null,
          isPrivate: model.isPrivate,
          profileVisibility: model.profileVisibility,
          lastSeenVisibility: model.lastSeenVisibility,
          onlineStatusVisibility: model.onlineStatusVisibility,
        })
        const profile = response.profile ?? {}
        setModel((current) => ({
          ...current,
          displayName: textOf(profile, ['display_name', 'displayName', 'full_name'], current.displayName),
          bio: textOf(profile, ['bio'], current.bio),
          avatarUrl: textOf(profile, ['avatar_url', 'avatarUrl'], current.avatarUrl),
          coverUrl: textOf(profile, ['cover_url', 'coverUrl'], current.coverUrl),
        }))
        setInfo('Perfil actualizado en Ubuntu.')
      } catch (caught) {
        setError(friendlyError(caught, 'No se pudo guardar perfil local'))
      } finally {
        setSavingProfile(false)
      }
      return
    }
    if (!supabase || !user || savingProfile) return
    setSavingProfile(true)
    setError(null)
    setInfo(null)
    try {
      const payload: Record<string, unknown> = {
        display_name: model.displayName.trim() || user.email || 'Usuario',
        username: model.username.trim() || null,
        bio: model.bio.trim() || null,
        avatar_url: model.avatarUrl.trim() || null,
        cover_url: model.coverUrl.trim() || null,
        interests: csvToArray(model.interestsText),
        linked_games: csvToArray(model.linkedGamesText),
        game_logo_urls: gameLogoJsonFromText(model.gameLogoUrlsText),
        favorite_music_url: model.favoriteMusicUrl.trim() || null,
        favorite_book: model.favoriteBook.trim() || null,
        favorite_sport: model.favoriteSport.trim() || null,
        career: model.career.trim() || null,
        availability_schedule: model.availabilitySchedule.trim() || null,
        last_seen_visibility: model.lastSeenVisibility,
        online_status_visibility: model.onlineStatusVisibility,
        updated_at: new Date().toISOString(),
      }
      const { error: updateError } = await supabase.from('profiles').update(payload).eq('id', user.id)
      if (updateError && !isMissingColumnError(updateError)) throw updateError
      if (updateError && isMissingColumnError(updateError)) {
        const legacyPayload = {
          display_name: payload.display_name,
          username: payload.username,
          bio: payload.bio,
          updated_at: payload.updated_at,
        }
        const { error: legacyError } = await supabase.from('profiles').update(legacyPayload).eq('id', user.id)
        if (legacyError) throw legacyError
        setInfo('Perfil básico guardado. Ejecuta el SQL final para habilitar banner/intereses/gustos en web.')
        return
      }
      setInfo('Perfil actualizado.')
    } catch (caught) {
      setError(friendlyError(caught, 'No se pudo guardar perfil'))
    } finally {
      setSavingProfile(false)
    }
  }

  async function savePrivacySettings() {
    if (isFallbackSession) {
      await saveProfileBasics()
      return
    }
    if (!supabase || !user || savingPrivacy) return
    setSavingPrivacy(true)
    setError(null)
    setInfo(null)
    try {
      const payload: Record<string, unknown> = {
        updated_at: new Date().toISOString(),
        last_seen_visibility: model.lastSeenVisibility,
        online_status_visibility: model.onlineStatusVisibility,
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
      if (updateError && !isMissingColumnError(updateError)) throw updateError
      if (updateError && isMissingColumnError(updateError)) {
        setInfo('Privacidad básica disponible. Ejecuta el SQL final para habilitar visibilidad granular de último acceso/online.')
        return
      }
      setInfo('Privacidad actualizada.')
    } catch (caught) {
      setError(friendlyError(caught, 'No se pudo guardar privacidad'))
    } finally {
      setSavingPrivacy(false)
    }
  }

  async function updatePassword() {
    if (savingPassword) return
    const password = newPassword.trim()
    if (password.length < 8) {
      setError('La nueva contraseña debe tener al menos 8 caracteres.')
      return
    }
    if (password !== confirmPassword.trim()) {
      setError('La confirmación no coincide con la contraseña nueva.')
      return
    }
    if (isFallbackSession && !currentPassword.trim()) {
      setError('Escribe tu contraseña actual para confirmar el cambio.')
      return
    }

    setSavingPassword(true)
    setError(null)
    setInfo(null)
    try {
      if (isFallbackSession) {
        await changeFallbackPassword(currentPassword.trim(), password)
      } else {
        if (!supabase) return
        const { error: passwordError } = await supabase.auth.updateUser({ password })
        if (passwordError) throw passwordError
      }
      setCurrentPassword('')
      setNewPassword('')
      setConfirmPassword('')
      setInfo('Contraseña actualizada.')
    } catch (caught) {
      setError(friendlyError(caught, 'No se pudo actualizar contraseña'))
    } finally {
      setSavingPassword(false)
    }
  }

  async function uploadProfileAsset(kind: 'avatar' | 'cover', file: File | undefined) {
    if (!file || !isFallbackSession || uploadingAsset) return
    setUploadingAsset(kind)
    setError(null)
    setInfo(null)
    try {
      const extension = file.name.split('.').pop()?.toLowerCase().replace(/[^a-z0-9]/g, '') || 'jpg'
      const userId = user?.id?.trim() || 'me'
      const path = `${userId}/${crypto.randomUUID()}_${kind}.${extension}`
      const uploaded = await uploadFallbackFile('avatars', file, path)
      setModel((current) =>
        kind === 'avatar'
          ? { ...current, avatarUrl: uploaded.publicUrl }
          : { ...current, coverUrl: uploaded.publicUrl },
      )
      setInfo(kind === 'avatar' ? 'Avatar subido. Guarda el perfil para aplicarlo.' : 'Banner subido. Guarda el perfil para aplicarlo.')
    } catch (caught) {
      setError(friendlyError(caught, kind === 'avatar' ? 'No se pudo subir avatar' : 'No se pudo subir banner'))
    } finally {
      setUploadingAsset(null)
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

  if (!supabase && !isFallbackSession) {
    return <StatusView title="Supabase no configurado" description="Configura .env para usar ajustes." />
  }

  if (loading) {
    return <StatusView title="Cargando ajustes" description="Espera un momento..." />
  }

  return (
    <section className="section-stack settings-page-shell">
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
      {info ? <article className="surface-card"><p>{info}</p></article> : null}

      <article className="surface-card settings-summary-card">
        <div className="row-between settings-summary-head">
          <div>
            <h3>Resumen rapido</h3>
            <p>Todo lo delicado de la cuenta queda agrupado antes de entrar a formularios largos.</p>
          </div>
          <span className="folder-tag">Cuenta viva</span>
        </div>
        <div className="settings-summary-grid">
          {settingsSummaryCards.map((card) => (
            <div key={card.label} className="settings-summary-item">
              <strong>{card.value}</strong>
              <span>{card.label}</span>
              <p>{card.hint}</p>
            </div>
          ))}
        </div>
        <div className="row-actions wrap settings-anchor-row">
          <a className="ghost-btn" href="#perfil-ajustes">Perfil</a>
          <a className="ghost-btn" href="#privacidad-ajustes">Privacidad</a>
          <a className="ghost-btn" href="#seguridad-ajustes">Seguridad</a>
          <a className="ghost-btn" href="#acceso-qr-ajustes">Acceso y QR</a>
          <a className="ghost-btn" href="#preferencias-web-ajustes">Preferencias web</a>
        </div>
      </article>

      <div className="settings-grid">
        <article id="perfil-ajustes" className="surface-card section-stack">
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
            <input
              className="text-input"
              placeholder="Avatar URL"
              value={model.avatarUrl}
              onChange={(event) => setModel((current) => ({ ...current, avatarUrl: event.target.value }))}
            />
            {isFallbackSession ? (
              <input
                className="text-input"
                type="file"
                accept="image/png,image/jpeg,image/webp"
                disabled={uploadingAsset !== null}
                onChange={(event) => void uploadProfileAsset('avatar', event.currentTarget.files?.[0])}
              />
            ) : null}
            <input
              className="text-input"
              placeholder="Banner / cover URL"
              value={model.coverUrl}
              onChange={(event) => setModel((current) => ({ ...current, coverUrl: event.target.value }))}
            />
            {isFallbackSession ? (
              <input
                className="text-input"
                type="file"
                accept="image/png,image/jpeg,image/webp"
                disabled={uploadingAsset !== null}
                onChange={(event) => void uploadProfileAsset('cover', event.currentTarget.files?.[0])}
              />
            ) : null}
            <input
              className="text-input"
              placeholder="Intereses separados por coma: anime, juegos, música"
              value={model.interestsText}
              onChange={(event) => setModel((current) => ({ ...current, interestsText: event.target.value }))}
            />
            <input
              className="text-input"
              placeholder="Juegos separados por coma"
              value={model.linkedGamesText}
              onChange={(event) => setModel((current) => ({ ...current, linkedGamesText: event.target.value }))}
            />
            <textarea
              className="text-input textarea-input"
              rows={3}
              placeholder="Logos de juegos: Minecraft=https://..."
              value={model.gameLogoUrlsText}
              onChange={(event) => setModel((current) => ({ ...current, gameLogoUrlsText: event.target.value }))}
            />
            <div className="settings-mini-grid">
              <input
                className="text-input"
                placeholder="Música favorita URL"
                value={model.favoriteMusicUrl}
                onChange={(event) => setModel((current) => ({ ...current, favoriteMusicUrl: event.target.value }))}
              />
              <input
                className="text-input"
                placeholder="Libro favorito"
                value={model.favoriteBook}
                onChange={(event) => setModel((current) => ({ ...current, favoriteBook: event.target.value }))}
              />
              <input
                className="text-input"
                placeholder="Deporte favorito"
                value={model.favoriteSport}
                onChange={(event) => setModel((current) => ({ ...current, favoriteSport: event.target.value }))}
              />
              <input
                className="text-input"
                placeholder="Carrera / profesión"
                value={model.career}
                onChange={(event) => setModel((current) => ({ ...current, career: event.target.value }))}
              />
            </div>
            <textarea
              className="text-input textarea-input"
              rows={2}
              placeholder="Horario disponible"
              value={model.availabilitySchedule}
              onChange={(event) => setModel((current) => ({ ...current, availabilitySchedule: event.target.value }))}
            />
            <button className="ghost-btn" type="button" disabled={savingProfile} onClick={() => void saveProfileBasics()}>
              {savingProfile ? 'Guardando...' : 'Guardar perfil'}
            </button>
          </div>
        </article>

        <article id="privacidad-ajustes" className="surface-card section-stack">
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
            <select
              className="text-input tiny-input"
              value={model.lastSeenVisibility}
              onChange={(event) =>
                setModel((current) => ({ ...current, lastSeenVisibility: event.target.value }))
              }
            >
              <option value="everyone">Último acceso: todos</option>
              <option value="contacts">Último acceso: contactos</option>
              <option value="nobody">Último acceso: nadie</option>
            </select>
            <select
              className="text-input tiny-input"
              value={model.onlineStatusVisibility}
              onChange={(event) =>
                setModel((current) => ({ ...current, onlineStatusVisibility: event.target.value }))
              }
            >
              <option value="everyone">Online: todos</option>
              <option value="contacts">Online: contactos</option>
              <option value="nobody">Online: nadie</option>
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
        <article id="seguridad-ajustes" className="surface-card section-stack">
          <div>
            <h3>Seguridad</h3>
            <p>
              {isFallbackSession
                ? 'Actualiza la contraseña que usarás en el servidor local.'
                : 'Cambia tu contraseña y protege el acceso a la cuenta.'}
            </p>
          </div>
          <div className="composer settings-stack">
            {isFallbackSession ? (
              <input
                className="text-input"
                type="password"
                autoComplete="current-password"
                placeholder="Contraseña actual"
                value={currentPassword}
                onChange={(event) => setCurrentPassword(event.target.value)}
              />
            ) : null}
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

        <article id="acceso-qr-ajustes" className="surface-card section-stack">
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
              <button className="ghost-btn" type="button" disabled={isFallbackSession || sendingAccessLink || !currentEmail} onClick={() => void sendAccessLinkToEmail()}>
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

      <article id="preferencias-web-ajustes" className="surface-card section-stack">
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
          <label className="settings-toggle-card vertical">
            <div>
              <strong>Apariencia</strong>
              <p>Tema claro/oscuro/sistema como en móvil.</p>
            </div>
            <select
              className="text-input tiny-input"
              value={webPreferences.themeMode}
              onChange={(event) =>
                updateWebPreferences({ themeMode: event.target.value as typeof webPreferences.themeMode })
              }
            >
              <option value="system">Sistema</option>
              <option value="light">Claro</option>
              <option value="dark">Oscuro</option>
            </select>
          </label>
          <label className="settings-toggle-card vertical">
            <div>
              <strong>Escala de texto</strong>
              <p>Accesibilidad rápida para escritorio y PWA.</p>
            </div>
            <select
              className="text-input tiny-input"
              value={webPreferences.textScale}
              onChange={(event) =>
                updateWebPreferences({ textScale: event.target.value as typeof webPreferences.textScale })
              }
            >
              <option value="compact">Compacta</option>
              <option value="normal">Normal</option>
              <option value="large">Grande</option>
            </select>
          </label>
          <label className="settings-toggle-card vertical">
            <div>
              <strong>Idioma</strong>
              <p>Base para localización web y preferencias de cuenta.</p>
            </div>
            <select
              className="text-input tiny-input"
              value={webPreferences.language}
              onChange={(event) => updateWebPreferences({ language: event.target.value })}
            >
              <option value="es">Español</option>
              <option value="en">English</option>
              <option value="pt">Português</option>
            </select>
          </label>
        </div>
      </article>
    </section>
  )
}
