import { updateFallbackSessionProfile } from '../../core/auth/fallbackAuth'
import './accountSettings.css'
import { StreamingImage } from '../../components/StreamingMedia'
import { SafeAvatar } from '../../components/SafeAvatar'
import { useCallback, useEffect, useMemo, useState } from 'react'
import { QrCodeTile } from '../../components/QrCodeTile'
import { ProfileCoverEffect } from '../../components/ProfileCoverEffect'
import { ProfileAppearanceControls } from '../../components/ProfileAppearanceControls'
import { Link, useLocation } from 'react-router-dom'
import { StatusView } from '../../components/StatusView'
import { supabase } from '../../core/supabase'
import { useWebPreferences } from '../../core/webPreferences'
import { friendlyError, textOf, type GenericRow } from '../../core/utils'
import { useAuth } from '../../core/auth/AuthProvider'
import { changeFallbackPassword } from '../../core/auth/fallbackAuth'
import {
  loadFallbackProfileMe,
  updateFallbackProfileMe,
  uploadFallbackFile,
} from '../../core/fallbackData'

type ProfileSectionId =
  | 'welcome'
  | 'music'
  | 'interests'
  | 'hobbies'
  | 'career'
  | 'games'
  | 'sports'
  | 'books'
  | 'availability'
type ProfileSectionVisibility = 'everyone' | 'followers' | 'only_me'

const profileSectionLabels: Record<ProfileSectionId, string> = {
  welcome: 'Bienvenida',
  music: 'Música de entrada',
  interests: 'Intereses',
  hobbies: 'Hobbies',
  career: 'Carrera o profesión',
  games: 'Videojuegos',
  sports: 'Deportes',
  books: 'Libros',
  availability: 'Disponibilidad',
}

const defaultSectionVisibility = (): Record<ProfileSectionId, ProfileSectionVisibility> =>
  Object.fromEntries(Object.keys(profileSectionLabels).map((key) => [key, 'everyone'])) as Record<
    ProfileSectionId,
    ProfileSectionVisibility
  >

function profileSectionVisibilityFrom(
  value: unknown,
): Record<ProfileSectionId, ProfileSectionVisibility> {
  const result = defaultSectionVisibility()
  if (!value || typeof value !== 'object' || Array.isArray(value)) return result
  for (const key of Object.keys(profileSectionLabels) as ProfileSectionId[]) {
    const raw = String((value as Record<string, unknown>)[key] ?? '')
      .trim()
      .toLowerCase()
    const normalized = raw === 'connections' ? 'followers' : raw
    if (normalized === 'everyone' || normalized === 'followers' || normalized === 'only_me')
      result[key] = normalized
  }
  return result
}

function profileSectionOrderFrom(value: unknown): ProfileSectionId[] {
  const defaults = Object.keys(profileSectionLabels) as ProfileSectionId[]
  const requested = Array.isArray(value)
    ? value
        .map((entry) => String(entry).trim().toLowerCase())
        .filter((entry): entry is ProfileSectionId => entry in profileSectionLabels)
    : []
  return [...new Set([...requested, ...defaults])]
}

type SettingsModel = {
  displayName: string
  username: string
  bio: string
  avatarUrl: string
  coverUrl: string
  profileEffect: string
  profileTheme: string
  profileWelcome: string
  interestsText: string
  hobbiesText: string
  linkedGamesText: string
  gameLogoUrlsText: string
  favoriteMusicUrl: string
  favoriteBook: string
  favoriteSport: string
  career: string
  availabilitySchedule: string
  profileSectionVisibility: Record<ProfileSectionId, ProfileSectionVisibility>
  profileSectionOrder: ProfileSectionId[]
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
  return value
    .map((entry) => String(entry).trim())
    .filter(Boolean)
    .join(', ')
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
  const message =
    error instanceof Error ? error.message.toLowerCase() : String(error ?? '').toLowerCase()
  return message.includes('column') || message.includes('42703')
}

export function AccountSettingsPage() {
  const location = useLocation()
  const [activeSection, setActiveSection] = useState('perfil')
  const { user, isFallbackSession } = useAuth()
  const [webPreferences, updateWebPreferences] = useWebPreferences()
  const [model, setModel] = useState<SettingsModel>({
    displayName: '',
    username: '',
    bio: '',
    avatarUrl: '',
    coverUrl: '',
    profileEffect: 'none',
    profileTheme: 'conectate',
    profileWelcome: '',
    interestsText: '',
    hobbiesText: '',
    linkedGamesText: '',
    gameLogoUrlsText: '',
    favoriteMusicUrl: '',
    favoriteBook: '',
    favoriteSport: '',
    career: '',
    availabilitySchedule: '',
    profileSectionVisibility: defaultSectionVisibility(),
    profileSectionOrder: profileSectionOrderFrom([]),
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
          displayName: textOf(
            profile,
            ['display_name', 'displayName', 'full_name'],
            textOf(metadata, ['display_name', 'full_name'], user?.email ?? ''),
          ),
          username: textOf(profile, ['username'], ''),
          bio: textOf(profile, ['bio'], ''),
          avatarUrl: textOf(
            profile,
            ['avatar_url', 'avatarUrl', 'photo_url', 'profile_image'],
            textOf(metadata, ['avatar_url'], ''),
          ),
          coverUrl: textOf(profile, ['cover_url', 'coverUrl', 'banner_url'], ''),
          profileEffect: textOf(profile, ['profile_effect', 'profileEffect'], 'none'),
          profileTheme: textOf(profile, ['profile_theme', 'profileTheme'], 'conectate'),
          profileWelcome: textOf(profile, ['profile_welcome', 'profileWelcome'], ''),
          interestsText: csvFromArray(profile.interests),
          hobbiesText: csvFromArray(profile.hobbies),
          linkedGamesText: csvFromArray(profile.linked_games),
          gameLogoUrlsText: gameLogoTextFromJson(profile.game_logo_urls),
          favoriteMusicUrl: textOf(profile, ['favorite_music_url'], ''),
          favoriteBook: textOf(profile, ['favorite_book'], ''),
          favoriteSport: textOf(profile, ['favorite_sport'], ''),
          career: textOf(profile, ['career'], ''),
          availabilitySchedule: textOf(profile, ['availability_schedule'], ''),
          profileSectionVisibility: profileSectionVisibilityFrom(
            profile.profile_section_visibility ?? profile.profileSectionVisibility,
          ),
          profileSectionOrder: profileSectionOrderFrom(
            profile.profile_section_order ?? profile.profileSectionOrder,
          ),
          lastSeenVisibility: textOf(
            profile,
            ['last_seen_visibility', 'lastSeenVisibility'],
            current.lastSeenVisibility,
          ),
          onlineStatusVisibility: textOf(
            profile,
            ['online_status_visibility', 'onlineStatusVisibility'],
            current.onlineStatusVisibility,
          ),
          isPrivate: toBoolean(profile.is_private ?? profile.isPrivate),
          profileVisibility: textOf(
            profile,
            ['profile_visibility', 'profileVisibility'],
            current.profileVisibility,
          ),
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
        profileEffect: textOf(row, ['profile_effect'], 'none'),
        profileTheme: textOf(row, ['profile_theme'], 'conectate'),
        profileWelcome: textOf(row, ['profile_welcome', 'profileWelcome'], ''),
        interestsText: csvFromArray(row.interests),
        hobbiesText: csvFromArray(row.hobbies),
        linkedGamesText: csvFromArray(row.linked_games),
        gameLogoUrlsText: gameLogoTextFromJson(row.game_logo_urls),
        favoriteMusicUrl: textOf(row, ['favorite_music_url'], ''),
        favoriteBook: textOf(row, ['favorite_book'], ''),
        favoriteSport: textOf(row, ['favorite_sport'], ''),
        career: textOf(row, ['career'], ''),
        availabilitySchedule: textOf(row, ['availability_schedule'], ''),
        profileSectionVisibility: profileSectionVisibilityFrom(
          row.profile_section_visibility ?? row.profileSectionVisibility,
        ),
        profileSectionOrder: profileSectionOrderFrom(
          row.profile_section_order ?? row.profileSectionOrder,
        ),
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

  useEffect(() => {
    if (!loading && location.hash === '#personalizar-perfil') document.getElementById('personalizar-perfil')?.scrollIntoView?.({ block: 'start' })
  }, [loading, location.hash])

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
          profileEffect: model.profileEffect,
          profileTheme: model.profileTheme,
          profileWelcome: model.profileWelcome.trim(),
          username: model.username.trim() || null,
          interests: csvToArray(model.interestsText),
          hobbies: csvToArray(model.hobbiesText),
          linkedGames: csvToArray(model.linkedGamesText),
          gameLogoUrls: gameLogoJsonFromText(model.gameLogoUrlsText),
          favoriteMusicUrl: model.favoriteMusicUrl.trim() || null,
          favoriteBook: model.favoriteBook.trim() || null,
          favoriteSport: model.favoriteSport.trim() || null,
          career: model.career.trim() || null,
          availabilitySchedule: model.availabilitySchedule.trim() || null,
          profileSectionVisibility: model.profileSectionVisibility,
          profileSectionOrder: model.profileSectionOrder,
          isPrivate: model.isPrivate,
          profileVisibility: model.profileVisibility,
          lastSeenVisibility: model.lastSeenVisibility,
          onlineStatusVisibility: model.onlineStatusVisibility,
        })
        const profile = response.profile ?? {}
        setModel((current) => ({
          ...current,
          displayName: textOf(
            profile,
            ['display_name', 'displayName', 'full_name'],
            current.displayName,
          ),
          bio: textOf(profile, ['bio'], current.bio),
          avatarUrl: textOf(profile, ['avatar_url', 'avatarUrl'], current.avatarUrl),
          coverUrl: textOf(profile, ['cover_url', 'coverUrl'], current.coverUrl),
          profileEffect: textOf(profile, ['profile_effect', 'profileEffect'], current.profileEffect),
          profileTheme: textOf(profile, ['profile_theme', 'profileTheme'], current.profileTheme),
          profileWelcome: textOf(profile, ['profile_welcome', 'profileWelcome'], current.profileWelcome),
        }))
        if (user?.id) updateFallbackSessionProfile(user.id, {
          displayName: textOf(profile, ['display_name', 'displayName'], model.displayName.trim()),
          username: textOf(profile, ['username'], model.username.trim()),
          avatarUrl: Object.hasOwn(profile, 'avatar_url') ? profile.avatar_url as string | null : model.avatarUrl.trim() || null,
        })
        setInfo('Perfil actualizado.')
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
        profile_effect: model.profileEffect,
        profile_theme: model.profileTheme,
        profile_welcome: model.profileWelcome.trim(),
        interests: csvToArray(model.interestsText),
        hobbies: csvToArray(model.hobbiesText),
        linked_games: csvToArray(model.linkedGamesText),
        game_logo_urls: gameLogoJsonFromText(model.gameLogoUrlsText),
        favorite_music_url: model.favoriteMusicUrl.trim() || null,
        favorite_book: model.favoriteBook.trim() || null,
        favorite_sport: model.favoriteSport.trim() || null,
        career: model.career.trim() || null,
        availability_schedule: model.availabilitySchedule.trim() || null,
        profile_section_visibility: model.profileSectionVisibility,
        profile_section_order: model.profileSectionOrder,
        last_seen_visibility: model.lastSeenVisibility,
        online_status_visibility: model.onlineStatusVisibility,
        updated_at: new Date().toISOString(),
      }
      const { error: updateError } = await supabase
        .from('profiles')
        .update(payload)
        .eq('id', user.id)
      if (updateError && !isMissingColumnError(updateError)) throw updateError
      if (updateError && isMissingColumnError(updateError)) {
        const legacyPayload = {
          display_name: payload.display_name,
          username: payload.username,
          bio: payload.bio,
          updated_at: payload.updated_at,
        }
        const { error: legacyError } = await supabase
          .from('profiles')
          .update(legacyPayload)
          .eq('id', user.id)
        if (legacyError) throw legacyError
        setInfo('Perfil básico actualizado. El servidor necesita habilitar las columnas de personalización para guardar el color y la animación.')
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

      const { error: updateError } = await supabase
        .from('profiles')
        .update(payload)
        .eq('id', user.id)
      if (updateError && !isMissingColumnError(updateError)) throw updateError
      if (updateError && isMissingColumnError(updateError)) {
        setInfo(
          'Privacidad básica disponible. Ejecuta el SQL final para habilitar visibilidad granular de último acceso/online.',
        )
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
        const { error: passwordError } = await supabase.auth.updateUser({
          password,
        })
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
      const extension =
        file.name
          .split('.')
          .pop()
          ?.toLowerCase()
          .replace(/[^a-z0-9]/g, '') || 'jpg'
      const userId = user?.id?.trim() || 'me'
      const path = `${userId}/${crypto.randomUUID()}_${kind}.${extension}`
      const uploaded = await uploadFallbackFile('avatars', file, path)
      setModel((current) =>
        kind === 'avatar'
          ? { ...current, avatarUrl: uploaded.publicUrl }
          : { ...current, coverUrl: uploaded.publicUrl },
      )
      setInfo(
        kind === 'avatar'
          ? 'Avatar subido. Guarda el perfil para aplicarlo.'
          : 'Banner subido. Guarda el perfil para aplicarlo.',
      )
    } catch (caught) {
      setError(
        friendlyError(
          caught,
          kind === 'avatar' ? 'No se pudo subir avatar' : 'No se pudo subir banner',
        ),
      )
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
      setInfo(
        'Enviamos un acceso por correo. Puedes usar el código o abrir el enlace desde el mensaje recibido.',
      )
    } catch (caught) {
      setError(friendlyError(caught, 'No se pudo enviar el acceso por correo'))
    } finally {
      setSendingAccessLink(false)
    }
  }

  if (!supabase && !isFallbackSession) {
    return (
      <StatusView title="Supabase no configurado" description="Configura .env para usar ajustes." />
    )
  }

  if (loading) {
    return <StatusView title="Cargando ajustes" description="Espera un momento…" />
  }

  return (
    <section className="section-stack settings-page-shell" data-section={activeSection}>
      <nav className="settings-section-nav" aria-label="Apartados de ajustes">
        {(['perfil', 'privacidad', 'seguridad', 'acceso-qr', 'preferencias-web'] as const).map((id, index) => <button type="button" key={id} aria-pressed={activeSection === id} onClick={() => setActiveSection(id)}>{['Perfil', 'Privacidad', 'Seguridad', 'Acceso y QR', 'Apariencia'][index]}</button>)}
      </nav>
      {error ? (
        <p className="error-text" role="alert">
          {error}
        </p>
      ) : null}
      {info ? <p role="status">{info}</p> : null}
      <div className="settings-grid">
        <article id="perfil-ajustes" className="surface-card section-stack settings-card-wide">
          <div>
            <h3>Perfil</h3>
            <p>Datos visibles para tu comunidad.</p>
          </div>
          <div className="composer settings-stack">
            <div className="settings-profile-preview">
              {model.coverUrl.trim() ? <div className="settings-profile-cover" style={{ position: 'relative' }}><StreamingImage className="settings-profile-cover" src={model.coverUrl.trim()} alt="Portada de tu perfil" />{model.profileEffect !== 'none' ? <ProfileCoverEffect effect={model.profileEffect} theme={model.profileTheme} overlay/> : null}</div> : <div className="settings-profile-cover"><ProfileCoverEffect effect={model.profileEffect} theme={model.profileTheme} /></div>}
              <div className="settings-profile-avatar">
                {model.avatarUrl.trim() ? (
                  <SafeAvatar url={model.avatarUrl.trim()} name={model.displayName || 'Tu perfil'} />
                ) : (
                  <span>
                    {(model.displayName.trim() || currentEmail || 'U').charAt(0).toUpperCase()}
                  </span>
                )}
              </div>
              <div className="settings-profile-meta">
                <strong>{model.displayName.trim() || 'Tu perfil'}</strong>
                <span>
                  {model.username.trim() ? `@${model.username.trim()}` : currentEmail || ''}
                </span>
              </div>
            </div>

            <section id="personalizar-perfil" className="settings-appearance-tools" aria-label="Personalizar perfil">
              <h4>Personalizar perfil</h4>
              <p>Elige una foto o un fondo animado. Los efectos se muestran en tu perfil cuando no hay foto de portada.</p>
              <ProfileAppearanceControls theme={model.profileTheme} effect={model.profileEffect} onTheme={profileTheme => setModel(current => ({ ...current, profileTheme }))} onEffect={profileEffect => setModel(current => ({ ...current, profileEffect }))} />
              <label>Mensaje de bienvenida <input className="text-input" maxLength={120} value={model.profileWelcome} onChange={event => setModel(current => ({ ...current, profileWelcome: event.target.value }))} placeholder="Una frase para quienes visitan tu perfil" /></label>
              {model.coverUrl ? <button className="ghost-btn" type="button" onClick={() => setModel(current => ({ ...current, coverUrl: '' }))}>Usar fondo en lugar de foto</button> : null}
              <p className="muted-line">La vista previa cambia al elegir. Guarda para mostrarlo también en el móvil.</p>
              <button className="primary-btn" type="button" disabled={savingProfile || uploadingAsset !== null} onClick={() => void saveProfileBasics()}>{savingProfile ? 'Guardando…' : 'Guardar personalización'}</button>
            </section>

            <div className="settings-form-grid">
              <label className="settings-field"><span>Nombre visible</span><input
                className="text-input"
                aria-label="Nombre visible"
                placeholder="Nombre visible"
                value={model.displayName}
                onChange={(event) =>
                  setModel((current) => ({
                    ...current,
                    displayName: event.target.value,
                  }))
                }
              /><small>Así te verán tus contactos y seguidores.</small></label>
              <label className="settings-field"><span>Nombre de usuario</span><input
                className="text-input tiny-input"
                aria-label="Nombre de usuario"
                placeholder="Usuario"
                value={model.username}
                onChange={(event) =>
                  setModel((current) => ({
                    ...current,
                    username: event.target.value,
                  }))
                }
              /><small>Tu identificador para que otras personas te encuentren.</small></label>
              <label className="settings-field settings-form-span-2"><span>Biografía</span><textarea
                className="text-input textarea-input"
                rows={4}
                aria-label="Biografía"
                placeholder="Biografía"
                value={model.bio}
                onChange={(event) =>
                  setModel((current) => ({
                    ...current,
                    bio: event.target.value,
                  }))
                }
              /><small>Cuéntales algo sobre ti.</small></label>
            </div>

            <div className="settings-form-grid">
              <details className="settings-advanced-media"><summary>Usar una imagen mediante un enlace</summary><div className="settings-mini-grid">
              <label className="settings-field"><span>Enlace de la foto de perfil</span><input
                className="text-input"
                aria-label="Enlace de la foto de perfil"
                placeholder="Avatar URL"
                value={model.avatarUrl}
                onChange={(event) =>
                  setModel((current) => ({
                    ...current,
                    avatarUrl: event.target.value,
                  }))
                }
              /><small>Pega el enlace HTTPS de tu imagen.</small></label>
              <label className="settings-field"><span>Enlace de la portada</span><input
                className="text-input"
                aria-label="Enlace de la portada"
                placeholder="Banner / cover URL"
                value={model.coverUrl}
                onChange={(event) =>
                  setModel((current) => ({
                    ...current,
                    coverUrl: event.target.value,
                  }))
                }
              /><small>Pega el enlace HTTPS de tu portada.</small></label>
              </div></details>
            </div>

            <div className="asset-upload-row">
              <label
                className={
                  isFallbackSession ? 'asset-upload-button' : 'asset-upload-button disabled'
                }
              >
                <span>
                  {uploadingAsset === 'avatar' ? 'Subiendo foto...' : 'Subir foto de perfil'}
                </span>
                <input
                  type="file"
                  accept="image/png,image/jpeg,image/webp"
                  disabled={!isFallbackSession || uploadingAsset !== null}
                  onChange={(event) =>
                    void uploadProfileAsset('avatar', event.currentTarget.files?.[0])
                  }
                />
              </label>
              <small>
                {isFallbackSession
                  ? 'JPG, PNG o WEBP. Después de subirla, guarda el perfil.'
                  : 'Inicia sesión para subir una imagen.'}
              </small>
            </div>
            <div className="asset-upload-row">
              <label
                className={
                  isFallbackSession
                    ? 'asset-upload-button secondary'
                    : 'asset-upload-button secondary disabled'
                }
              >
                <span>{uploadingAsset === 'cover' ? 'Subiendo portada...' : 'Subir portada'}</span>
                <input
                  type="file"
                  accept="image/png,image/jpeg,image/webp"
                  disabled={!isFallbackSession || uploadingAsset !== null}
                  onChange={(event) =>
                    void uploadProfileAsset('cover', event.currentTarget.files?.[0])
                  }
                />
              </label>
              <small>
                {isFallbackSession
                  ? 'La portada se guarda al actualizar el perfil.'
                  : 'Inicia sesión para cambiar esta imagen.'}
              </small>
            </div>
            <details className="settings-optional-details"><summary>Presentación e intereses</summary>
            <div className="settings-form-grid">
              <label className="settings-field"><span>Intereses</span><input
                className="text-input"
                aria-label="Intereses"
                placeholder="Intereses separados por coma: anime, juegos, música"
                value={model.interestsText}
                onChange={(event) =>
                  setModel((current) => ({
                    ...current,
                    interestsText: event.target.value,
                  }))
                }
              /><small>Separa con comas, por ejemplo: anime, juegos, música.</small></label>
              <label className="settings-field"><span>Pasatiempos</span><input
                className="text-input"
                aria-label="Pasatiempos"
                placeholder="Pasatiempos separados por coma"
                value={model.hobbiesText}
                onChange={(event) =>
                  setModel((current) => ({
                    ...current,
                    hobbiesText: event.target.value,
                  }))
                }
              /><small>Separa tus pasatiempos con comas.</small></label>
              <label className="settings-field"><span>Videojuegos</span><input
                className="text-input"
                aria-label="Videojuegos"
                placeholder="Juegos separados por coma"
                value={model.linkedGamesText}
                onChange={(event) =>
                  setModel((current) => ({
                    ...current,
                    linkedGamesText: event.target.value,
                  }))
                }
              /><small>Separa los nombres de tus juegos con comas.</small></label>
            </div>
            <label className="settings-field settings-form-span-2"><span>Logos de videojuegos</span><textarea
              className="text-input textarea-input"
              rows={3}
              aria-label="Logos de videojuegos"
                placeholder="Logos oficiales: Juego=https://cdn.cloudflare.steamstatic.com/..."
              value={model.gameLogoUrlsText}
              onChange={(event) =>
                setModel((current) => ({
                  ...current,
                  gameLogoUrlsText: event.target.value,
                }))
              }
            /><small>Un juego por línea: nombre=enlace de la imagen.</small></label>
            <div className="settings-mini-grid">
              <label className="settings-field"><span>Música favorita</span><input
                className="text-input"
                aria-label="Música favorita"
                placeholder="Música favorita URL"
                value={model.favoriteMusicUrl}
                onChange={(event) =>
                  setModel((current) => ({
                    ...current,
                    favoriteMusicUrl: event.target.value,
                  }))
                }
              /><small>Enlace de la canción que quieres compartir.</small></label>
              <label className="settings-field"><span>Libro favorito</span><input
                className="text-input"
                aria-label="Libro favorito"
                placeholder="Libro favorito"
                value={model.favoriteBook}
                onChange={(event) =>
                  setModel((current) => ({
                    ...current,
                    favoriteBook: event.target.value,
                  }))
                }
              /></label>
              <label className="settings-field"><span>Deporte favorito</span><input
                className="text-input"
                aria-label="Deporte favorito"
                placeholder="Deporte favorito"
                value={model.favoriteSport}
                onChange={(event) =>
                  setModel((current) => ({
                    ...current,
                    favoriteSport: event.target.value,
                  }))
                }
              /></label>
              <label className="settings-field"><span>Carrera o profesión</span><input
                className="text-input"
                aria-label="Carrera o profesión"
                placeholder="Carrera / profesión"
                value={model.career}
                onChange={(event) =>
                  setModel((current) => ({
                    ...current,
                    career: event.target.value,
                  }))
                }
              /></label>
            </div>
            <label className="settings-field"><span>Disponibilidad</span><textarea
              className="text-input textarea-input"
              rows={2}
              aria-label="Disponibilidad"
                placeholder="Horario disponible"
              value={model.availabilitySchedule}
              onChange={(event) =>
                setModel((current) => ({
                  ...current,
                  availabilitySchedule: event.target.value,
                }))
              }
            /><small>Indica cuándo puedes conversar o jugar.</small></label>
            </details><details className="settings-optional-details"><summary>Orden y visibilidad del perfil</summary>
            <div className="settings-mini-grid">
              {model.profileSectionOrder.map((section, sectionIndex) => (
                <div key={section} className="settings-stack">
                  <span>{profileSectionLabels[section]}</span>
                  <span className="row-actions">
                    <button
                      className="mini-action-btn"
                      type="button"
                      disabled={sectionIndex === 0}
                      onClick={() =>
                        setModel((current) => {
                          const order = [...current.profileSectionOrder]
                          ;[order[sectionIndex - 1], order[sectionIndex]] = [
                            order[sectionIndex],
                            order[sectionIndex - 1],
                          ]
                          return { ...current, profileSectionOrder: order }
                        })
                      }
                    >
                      Subir
                    </button>
                    <button
                      className="mini-action-btn"
                      type="button"
                      disabled={sectionIndex === model.profileSectionOrder.length - 1}
                      onClick={() =>
                        setModel((current) => {
                          const order = [...current.profileSectionOrder]
                          ;[order[sectionIndex], order[sectionIndex + 1]] = [
                            order[sectionIndex + 1],
                            order[sectionIndex],
                          ]
                          return { ...current, profileSectionOrder: order }
                        })
                      }
                    >
                      Bajar
                    </button>
                  </span>
                  <select
                    className="text-input tiny-input"
                    value={model.profileSectionVisibility[section]}
                    onChange={(event) =>
                      setModel((current) => ({
                        ...current,
                        profileSectionVisibility: {
                          ...current.profileSectionVisibility,
                          [section]: event.target.value as ProfileSectionVisibility,
                        },
                      }))
                    }
                  >
                    <option value="everyone">Todos</option>
                    <option value="followers">Seguidores</option>
                    <option value="only_me">Solo yo</option>
                  </select>
                </div>
              ))}
            </div>
            </details>
            <div className="settings-save-actions"><button
              className="primary-btn"
              type="button"
              disabled={savingProfile || uploadingAsset !== null}
              onClick={() => void saveProfileBasics()}
            >
              {savingProfile ? 'Guardando...' : 'Guardar perfil'}
            </button><small>Los cambios se aplican en tu cuenta de Novvin.</small></div>
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
                onChange={(event) =>
                  setModel((current) => ({
                    ...current,
                    isPrivate: event.target.checked,
                  }))
                }
              />
              Cuenta privada
            </label>
            <label className="settings-privacy-field"><span>Quién puede ver tu perfil</span>
            <select
              className="text-input tiny-input"
              value={model.profileVisibility}
              disabled={!hasProfileVisibility}
              onChange={(event) =>
                setModel((current) => ({
                  ...current,
                  profileVisibility: event.target.value,
                }))
              }
            >
              <option value="everyone">Todos</option>
              <option value="followers">Seguidores</option>
              <option value="friends">Amigos</option>
              <option value="nobody">Nadie</option>
            </select>
              <small>Elige la audiencia de la información de tu perfil.</small>
            </label>
            <label className="settings-privacy-field"><span>Último acceso</span>
            <select
              className="text-input tiny-input"
              value={model.lastSeenVisibility}
              onChange={(event) =>
                setModel((current) => ({
                  ...current,
                  lastSeenVisibility: event.target.value,
                }))
              }
            >
              <option value="everyone">Último acceso: todos</option>
              <option value="contacts">Último acceso: contactos</option>
              <option value="nobody">Último acceso: nadie</option>
            </select>
              <small>Controla quién ve cuándo estuviste conectado.</small>
            </label>
            <label className="settings-privacy-field"><span>Estado en línea</span>
            <select
              className="text-input tiny-input"
              value={model.onlineStatusVisibility}
              onChange={(event) =>
                setModel((current) => ({
                  ...current,
                  onlineStatusVisibility: event.target.value,
                }))
              }
            >
              <option value="everyone">Online: todos</option>
              <option value="contacts">Online: contactos</option>
              <option value="nobody">Online: nadie</option>
            </select>
              <small>Elige quién puede ver que estás usando Novvin.</small>
            </label>
            {hasPrivacyAcceptance ? (
              <label className="checkbox-inline">
                <input
                  type="checkbox"
                  checked={model.privacyAccepted}
                  onChange={(event) =>
                    setModel((current) => ({
                      ...current,
                      privacyAccepted: event.target.checked,
                    }))
                  }
                />
                Política de privacidad aceptada
              </label>
            ) : null}
            <button
              className="ghost-btn"
              type="button"
              disabled={savingPrivacy}
              onClick={() => void savePrivacySettings()}
            >
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
                ? 'Actualiza tu contraseña para proteger el acceso a tu cuenta.'
                : 'Cambia tu contraseña y protege el acceso a la cuenta.'}
            </p>
          </div>
          <div className="composer settings-stack">
            {isFallbackSession ? (
              <label className="settings-field"><span>Contraseña actual</span><input
                className="text-input"
                type="password"
                autoComplete="current-password"
                aria-label="Contraseña actual"
                placeholder="Contraseña actual"
                value={currentPassword}
                onChange={(event) => setCurrentPassword(event.target.value)}
              /></label>
            ) : null}
            <label className="settings-field"><span>Nueva contraseña</span><input
              className="text-input"
              type="password"
              autoComplete="new-password"
              aria-label="Nueva contraseña"
                placeholder="Nueva contraseña"
              value={newPassword}
              onChange={(event) => setNewPassword(event.target.value)}
            /><small>Usa al menos 8 caracteres.</small></label>
            <label className="settings-field"><span>Confirmar nueva contraseña</span><input
              className="text-input"
              type="password"
              autoComplete="new-password"
              aria-label="Confirmar nueva contraseña"
                placeholder="Confirmar contraseña"
              value={confirmPassword}
              onChange={(event) => setConfirmPassword(event.target.value)}
            /><small>Vuelve a escribir la nueva contraseña.</small></label>
            <button
              className="primary-btn"
              type="button"
              disabled={savingPassword}
              onClick={() => void updatePassword()}
            >
              {savingPassword ? 'Actualizando...' : 'Cambiar contraseña'}
            </button>
          </div>
        </article>

        <article id="acceso-qr-ajustes" className="surface-card section-stack">
          <div>
            <h3>Acceso y QR</h3>
            <p>
              Mantén el acceso por correo, pero suma también el QR seguro de Novvin Web y el QR de
              perfil para compartirte rápido.
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
              <strong>Novvin Web por QR</strong>
              <p>Genera un código temporal y apruébalo desde tu móvil como en WhatsApp Web.</p>
            </div>
            <div className="row-actions wrap">
              <button
                className="ghost-btn"
                type="button"
                disabled={isFallbackSession || sendingAccessLink || !currentEmail}
                onClick={() => void sendAccessLinkToEmail()}
              >
                {sendingAccessLink ? 'Enviando...' : 'Enviar acceso al correo'}
              </button>
              <Link
                className="ghost-btn"
                to={`/login?mode=code${currentEmail ? `&email=${encodeURIComponent(currentEmail)}` : ''}`}
              >
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
                description="Cuando alguien lo escanea desde Novvin móvil, abre tu perfil directamente."
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
              <strong>Lista de chats compacta</strong>
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
              <p>Modo claro/oscuro/sistema como en móvil.</p>
            </div>
            <select
              className="text-input tiny-input"
              value={webPreferences.themeMode}
              onChange={(event) =>
                updateWebPreferences({
                  themeMode: event.target.value as typeof webPreferences.themeMode,
                })
              }
            >
              <option value="system">Sistema</option>
              <option value="light">Claro</option>
              <option value="dark">Oscuro</option>
            </select>
          </label>
          <label className="settings-toggle-card vertical">
            <div>
              <strong>Color del tema</strong>
              <p>Cambia la paleta (gradientes y acentos) para personalizar tu espacio.</p>
            </div>
            <select
              className="text-input tiny-input"
              value={webPreferences.accentTheme}
              onChange={(event) =>
                updateWebPreferences({
                  accentTheme: event.target.value as typeof webPreferences.accentTheme,
                })
              }
            >
              <option value="conectate">Novvin</option>
              <option value="ocean">Océano</option>
              <option value="violet">Violeta</option>
              <option value="emerald">Esmeralda</option>
              <option value="midnight">Midnight</option>
              <option value="ruby">Rubí</option>
              <option value="cyan">Cian</option>
              <option value="amber">Ámbar</option>
              <option value="graphite">Grafito</option>
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
                updateWebPreferences({
                  textScale: event.target.value as typeof webPreferences.textScale,
                })
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
              <p>Elige el idioma que prefieres usar.</p>
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
