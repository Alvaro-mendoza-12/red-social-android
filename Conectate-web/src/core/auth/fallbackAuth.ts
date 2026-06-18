import type { User } from '@supabase/supabase-js'

export type FallbackUser = {
  id: string
  email: string
  displayName?: string
  avatarUrl?: string | null
  supabaseUserId?: string | null
  syncSource?: string
  syncStatus?: string
  localPasswordSetAt?: string | null
  passwordChangedByUserAt?: string | null
  supabaseSyncedAt?: string | null
  accountCreatedAt?: string
  passwordChangeRequired?: boolean
  createdAt?: string
  lastLoginAt?: string | null
}

export type FallbackSession = {
  accessToken: string
  tokenType: string
  expiresIn: string | number
  user: FallbackUser
  savedAt: string
}

export class MigratedPasswordResetRequiredError extends Error {
  migrationToken: string
  email: string
  displayName: string

  constructor(payload: { migrationToken: string; email: string; displayName?: string }) {
    super('MIGRATED_ACCOUNT_PASSWORD_RESET_REQUIRED')
    this.name = 'MigratedPasswordResetRequiredError'
    this.migrationToken = payload.migrationToken
    this.email = payload.email
    this.displayName = payload.displayName ?? ''
  }
}

export class MigratedAccountPasswordSetupRequiredError extends Error {
  email: string
  migrationToken: string
  displayName: string

  constructor(payload: { email: string; migrationToken?: string; displayName?: string }) {
    super(
      'Por la migración a Ubuntu, esta cuenta necesita crear una nueva contraseña antes de entrar.',
    )
    this.name = 'MigratedAccountPasswordSetupRequiredError'
    this.email = payload.email
    this.migrationToken = payload.migrationToken ?? ''
    this.displayName = payload.displayName ?? ''
  }
}

const fallbackApiUrl = import.meta.env.VITE_FALLBACK_API_URL?.trim().replace(/\/+$/, '') ?? ''
const storageKey = 'conectate:fallback-session'
const eventName = 'conectate:fallback-auth-changed'

export const isFallbackAuthConfigured = fallbackApiUrl.length > 0

function normalizeEmail(value: string): string {
  return value.trim().toLowerCase()
}

function fallbackErrorMessage(error: unknown): string {
  if (error instanceof Error && error.message.trim()) {
    const message = error.message.trim()
    if (/failed to fetch|networkerror|load failed|fetch/i.test(message)) {
      return fallbackConnectionErrorMessage()
    }
    return message
  }
  return String(error ?? 'No se pudo usar el servidor Ubuntu.')
}

function fallbackConnectionErrorMessage(): string {
  return 'No se pudo conectar con el servidor Ubuntu. Revisa que la VM, Docker y ngrok estén encendidos, y que VITE_FALLBACK_API_URL apunte a tu URL pública actual.'
}

async function parseFallbackResponse(response: Response) {
  const text = await response.text()
  if (!text.trim()) return {}
  try {
    return JSON.parse(text) as Record<string, unknown>
  } catch {
    return { message: text }
  }
}

export async function fallbackApiJson<T>(
  path: string,
  init?: RequestInit & { auth?: boolean },
): Promise<T> {
  if (!isFallbackAuthConfigured) {
    throw new Error('El servidor Ubuntu no está configurado para esta web.')
  }

  const requiresAuth = init?.auth !== false
  const normalizedPath = path.startsWith('/') ? path : `/${path}`
  const headers = new Headers(init?.headers)
  headers.set('ngrok-skip-browser-warning', 'true')
  const isFormDataBody = typeof FormData !== 'undefined' && init?.body instanceof FormData
  if (!headers.has('Content-Type') && init?.body && !isFormDataBody) {
    headers.set('Content-Type', 'application/json')
  }

  if (requiresAuth) {
    const token = readFallbackSession()?.accessToken?.trim()
    if (!token) throw new Error('No hay una sesión de Ubuntu activa.')
    headers.set('Authorization', `Bearer ${token}`)
  }

  const response = await fetchFallback(normalizedPath, {
    ...init,
    headers,
  })
  const payload = await parseFallbackResponse(response)
  if (!response.ok) {
    const code = String(payload.error || payload.message || '').trim()
    if (requiresAuth && (code === 'WEB_SESSION_REVOKED' || code === 'INVALID_TOKEN')) {
      clearFallbackSession()
    }
    if (code === 'WEB_SESSION_REVOKED') {
      throw new Error('Tu sesión web fue cerrada desde la APK móvil por seguridad.')
    }
    throw new Error(code || 'No se pudo consultar el servidor Ubuntu.')
  }
  return payload as T
}

async function fetchFallback(path: string, init: RequestInit): Promise<Response> {
  const normalizedPath = path.startsWith('/') ? path : `/${path}`
  try {
    return await fetch(`${fallbackApiUrl}${normalizedPath}`, init)
  } catch (error) {
    console.error('Conectate Ubuntu backend is unreachable', error)
    throw new Error(fallbackConnectionErrorMessage())
  }
}

function notifyFallbackAuthChanged() {
  if (typeof window === 'undefined') return
  window.dispatchEvent(new Event(eventName))
}

export function persistFallbackSession(payload: {
  accessToken?: string | null
  tokenType?: string | null
  expiresIn?: string | number | null
  user?: FallbackUser | null
}): User {
  const accessToken = String(payload.accessToken || '').trim()
  const user = payload.user
  if (!accessToken || !user?.id || !user.email) {
    throw new Error('El servidor Ubuntu devolvió una sesión incompleta.')
  }

  const session: FallbackSession = {
    accessToken,
    tokenType: String(payload.tokenType || 'Bearer'),
    expiresIn: String(payload.expiresIn || '7d'),
    user,
    savedAt: new Date().toISOString(),
  }

  storage()?.setItem(storageKey, JSON.stringify(session))
  notifyFallbackAuthChanged()

  const mappedUser = fallbackSessionToUser(session)
  if (!mappedUser) {
    throw new Error('No se pudo preparar la sesión local de Ubuntu.')
  }
  return mappedUser
}

function storage(): Storage | null {
  if (typeof window === 'undefined') return null
  try {
    return window.localStorage
  } catch {
    return null
  }
}

export function readFallbackSession(): FallbackSession | null {
  const raw = storage()?.getItem(storageKey)
  if (!raw) return null
  try {
    const parsed = JSON.parse(raw) as FallbackSession
    if (!parsed.accessToken?.trim() || !parsed.user?.id || !parsed.user?.email) return null
    return parsed
  } catch {
    return null
  }
}

export function clearFallbackSession() {
  storage()?.removeItem(storageKey)
  notifyFallbackAuthChanged()
}

export function onFallbackAuthChanged(callback: () => void) {
  if (typeof window === 'undefined') return () => {}
  window.addEventListener(eventName, callback)
  return () => window.removeEventListener(eventName, callback)
}

export function fallbackSessionToUser(session: FallbackSession | null): User | null {
  if (!session) return null
  const fallbackUser = session.user
  const userId = fallbackUser.supabaseUserId?.trim() || fallbackUser.id
  const createdAt = fallbackUser.accountCreatedAt || fallbackUser.createdAt || session.savedAt || new Date().toISOString()
  const displayName = fallbackUser.displayName?.trim() || fallbackUser.email.split('@')[0] || 'Usuario'

  return {
    id: userId,
    aud: 'authenticated',
    role: 'authenticated',
    email: fallbackUser.email,
    email_confirmed_at: createdAt,
    phone: '',
    confirmed_at: createdAt,
    last_sign_in_at: fallbackUser.lastLoginAt || session.savedAt,
    app_metadata: {
      provider: 'local-fallback',
      providers: ['local-fallback'],
    },
    user_metadata: {
      display_name: displayName,
      full_name: displayName,
      avatar_url: fallbackUser.avatarUrl ?? null,
      fallback_auth: true,
      local_fallback_id: fallbackUser.id,
      sync_status: fallbackUser.syncStatus ?? '',
      supabase_user_id: fallbackUser.supabaseUserId ?? '',
      account_created_at: fallbackUser.accountCreatedAt ?? fallbackUser.createdAt ?? '',
      local_password_set_at: fallbackUser.localPasswordSetAt ?? '',
      password_changed_by_user_at: fallbackUser.passwordChangedByUserAt ?? '',
      password_change_required: Boolean(fallbackUser.passwordChangeRequired),
    },
    identities: [],
    created_at: createdAt,
    updated_at: fallbackUser.supabaseSyncedAt || fallbackUser.localPasswordSetAt || createdAt,
    is_anonymous: false,
  } as User
}

export async function signInWithFallbackAuth(email: string, password: string): Promise<User> {
  if (!isFallbackAuthConfigured) {
    throw new Error('El servidor Ubuntu no está configurado para esta web.')
  }

  const response = await fetchFallback('/v1/auth/login', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'ngrok-skip-browser-warning': 'true',
    },
    body: JSON.stringify({
      email: normalizeEmail(email),
      password,
    }),
  })
  const payload = await parseFallbackResponse(response)

  if (!response.ok) {
    const code = String(payload.error || payload.message || '').trim()
    if (code === 'MIGRATED_ACCOUNT_PASSWORD_RESET_REQUIRED') {
      throw new MigratedPasswordResetRequiredError({
        migrationToken: String(payload.migrationToken || ''),
        email: String(payload.email || email),
        displayName: String(payload.displayName || ''),
      })
    }
    if (code === 'MIGRATED_ACCOUNT_PASSWORD_SETUP_REQUIRED') {
      throw new MigratedAccountPasswordSetupRequiredError({
        email: String(payload.email || email),
        migrationToken: String(payload.migrationToken || ''),
        displayName: String(payload.displayName || ''),
      })
    }
    if (code === 'INVALID_CREDENTIALS') {
      throw new Error(
        'Esta cuenta necesita actualizar su contraseña por la migración a Ubuntu. Pulsa “Cambiar contraseña” y crea una nueva contraseña para entrar normalmente.',
      )
    }
    if (code === 'EMAIL_AND_PASSWORD_REQUIRED') {
      throw new Error('Escribe el correo y la contraseña para entrar con Ubuntu.')
    }
    throw new Error(code || 'No se pudo iniciar sesión con el servidor Ubuntu.')
  }

  return persistFallbackSession(payload as {
    accessToken?: string
    tokenType?: string
    expiresIn?: string | number
    user?: FallbackUser
  })
}

export async function signUpWithFallbackAuth(params: {
  email: string
  password: string
  displayName: string
}): Promise<User> {
  if (!isFallbackAuthConfigured) {
    throw new Error('El servidor Ubuntu no está configurado para esta web.')
  }

  const response = await fetchFallback('/v1/auth/register', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'ngrok-skip-browser-warning': 'true',
    },
    body: JSON.stringify({
      email: normalizeEmail(params.email),
      password: params.password,
      displayName: params.displayName.trim(),
    }),
  })
  const payload = await parseFallbackResponse(response)

  if (!response.ok) {
    const code = String(payload.error || payload.message || '').trim()
    if (code === 'EMAIL_ALREADY_EXISTS') {
      throw new Error('Ese correo ya existe en el servidor Ubuntu.')
    }
    if (code === 'PASSWORD_TOO_SHORT') {
      throw new Error('La contraseña debe tener al menos 8 caracteres.')
    }
    throw new Error(code || 'No se pudo crear la cuenta en el servidor Ubuntu.')
  }

  return persistFallbackSession(payload as {
    accessToken?: string
    tokenType?: string
    expiresIn?: string | number
    user?: FallbackUser
  })
}

export function isMigratedPasswordResetRequired(error: unknown): error is MigratedPasswordResetRequiredError {
  return error instanceof MigratedPasswordResetRequiredError
}

export function isMigratedAccountPasswordSetupRequired(
  error: unknown,
): error is MigratedAccountPasswordSetupRequiredError {
  return error instanceof MigratedAccountPasswordSetupRequiredError
}

export async function completeMigratedPasswordReset(
  migrationToken: string,
  newPassword: string,
): Promise<User> {
  const payload = await fallbackApiJson<{
    accessToken?: string
    tokenType?: string
    expiresIn?: string | number
    user?: FallbackUser
  }>('/v1/auth/complete-migrated-password-reset', {
    method: 'POST',
    auth: false,
    body: JSON.stringify({
      migrationToken,
      newPassword,
    }),
  })

  return persistFallbackSession(payload)
}

export async function changeFallbackPassword(currentPassword: string, newPassword: string): Promise<User> {
  const payload = await fallbackApiJson<{
    accessToken?: string
    tokenType?: string
    expiresIn?: string | number
    user?: FallbackUser
  }>('/v1/auth/change-password', {
    method: 'POST',
    body: JSON.stringify({
      currentPassword,
      newPassword,
    }),
  })

  return persistFallbackSession(payload)
}

export async function requestFallbackPasswordReset(email: string): Promise<{
  ok: boolean
  delivery?: string
  resetToken?: string
  expiresInMinutes?: number
}> {
  return fallbackApiJson('/v1/auth/password-reset/request', {
    method: 'POST',
    auth: false,
    body: JSON.stringify({ email: normalizeEmail(email) }),
  })
}

export async function confirmFallbackPasswordReset(
  resetToken: string,
  newPassword: string,
): Promise<User> {
  const payload = await fallbackApiJson<{
    accessToken?: string
    tokenType?: string
    expiresIn?: string | number
    user?: FallbackUser
  }>('/v1/auth/password-reset/confirm', {
    method: 'POST',
    auth: false,
    body: JSON.stringify({
      resetToken,
      newPassword,
    }),
  })

  return persistFallbackSession(payload)
}

export function toFallbackFriendlyError(error: unknown): Error {
  return new Error(fallbackErrorMessage(error))
}
