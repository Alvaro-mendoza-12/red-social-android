import type { User } from '@supabase/supabase-js'

type FallbackUser = {
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

const fallbackApiUrl = import.meta.env.VITE_FALLBACK_API_URL?.trim().replace(/\/+$/, '') ?? ''
const storageKey = 'conectate:fallback-session'
const eventName = 'conectate:fallback-auth-changed'

export const isFallbackAuthConfigured = fallbackApiUrl.length > 0

function normalizeEmail(value: string): string {
  return value.trim().toLowerCase()
}

function fallbackErrorMessage(error: unknown): string {
  if (error instanceof Error && error.message.trim()) return error.message.trim()
  return String(error ?? 'No se pudo usar el servidor de respaldo.')
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
    throw new Error('El servidor de respaldo no está configurado para esta web.')
  }

  const normalizedPath = path.startsWith('/') ? path : `/${path}`
  const headers = new Headers(init?.headers)
  headers.set('ngrok-skip-browser-warning', 'true')
  const isFormDataBody = typeof FormData !== 'undefined' && init?.body instanceof FormData
  if (!headers.has('Content-Type') && init?.body && !isFormDataBody) {
    headers.set('Content-Type', 'application/json')
  }

  if (init?.auth !== false) {
    const token = readFallbackSession()?.accessToken?.trim()
    if (!token) throw new Error('No hay una sesión de respaldo activa.')
    headers.set('Authorization', `Bearer ${token}`)
  }

  const response = await fetch(`${fallbackApiUrl}${normalizedPath}`, {
    ...init,
    headers,
  })
  const payload = await parseFallbackResponse(response)
  if (!response.ok) {
    const code = String(payload.error || payload.message || '').trim()
    throw new Error(code || 'No se pudo consultar el servidor de respaldo.')
  }
  return payload as T
}

function notifyFallbackAuthChanged() {
  if (typeof window === 'undefined') return
  window.dispatchEvent(new Event(eventName))
}

function persistFallbackSession(payload: {
  accessToken?: string
  tokenType?: string
  expiresIn?: string | number
  user?: FallbackUser
}): User {
  const accessToken = String(payload.accessToken || '').trim()
  const user = payload.user
  if (!accessToken || !user?.id || !user.email) {
    throw new Error('El servidor de respaldo devolvió una sesión incompleta.')
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
    throw new Error('No se pudo preparar la sesión local de respaldo.')
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
    throw new Error('El servidor de respaldo no está configurado para esta web.')
  }

  const response = await fetch(`${fallbackApiUrl}/v1/auth/login`, {
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
    if (code === 'INVALID_CREDENTIALS') {
      throw new Error('Correo o contraseña inválidos en el servidor de respaldo.')
    }
    if (code === 'EMAIL_AND_PASSWORD_REQUIRED') {
      throw new Error('Escribe el correo y la contraseña para entrar con respaldo.')
    }
    throw new Error(code || 'No se pudo iniciar sesión con el servidor de respaldo.')
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

export function toFallbackFriendlyError(error: unknown): Error {
  return new Error(fallbackErrorMessage(error))
}
