import type { User } from '@supabase/supabase-js'
import {
  getRuntimeBackendUrl,
  refreshRuntimeBackend,
  subscribeRuntimeBackend,
} from '../runtimeBackend'
import { isHttpRetrySafeMethod } from '../httpRetryPolicy'
import {
  accessTokenExpiration,
  toPersistedFallbackSession,
  type PersistedFallbackSession,
} from './fallbackSessionPersistence'

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
  accessTokenExpiresAt: number | null
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

let fallbackApiUrl = getRuntimeBackendUrl()
const storageKey = 'conectate:fallback-session'
const eventName = 'conectate:fallback-auth-changed'
const webSessionHeader = 'X-Novvin-Web-Session'
const webSessionHeaderValue = 'web-v1'
let activeFallbackSession: FallbackSession | null = null
let storageInitialized = false
let legacySessionNeedsUpgrade = false
let refreshPromise: Promise<FallbackSession | null> | null = null

export let isFallbackAuthConfigured = fallbackApiUrl.length > 0

subscribeRuntimeBackend((url) => {
  fallbackApiUrl = url
  isFallbackAuthConfigured = url.length > 0
  if (activeFallbackSession?.accessToken) {
    void upgradeFallbackWebSession().catch(() => {
      // The current short-lived bearer remains valid while discovery retries.
    })
  }
})

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
  return 'No se pudo conectar con el servidor Ubuntu. Revisa el estado del servidor y tu conexión a Internet.'
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
  init?: RequestInit & { auth?: boolean; webSession?: boolean },
): Promise<T> {
  if (!isFallbackAuthConfigured) {
    throw new Error('El servidor Ubuntu no está configurado para esta web.')
  }

  const requiresAuth = init?.auth !== false
  const usesWebSession = init?.webSession === true
  const normalizedPath = path.startsWith('/') ? path : `/${path}`
  const headers = new Headers(init?.headers)
  headers.set('ngrok-skip-browser-warning', 'true')
  const isFormDataBody = typeof FormData !== 'undefined' && init?.body instanceof FormData
  if (!headers.has('Content-Type') && init?.body && !isFormDataBody) {
    headers.set('Content-Type', 'application/json')
  }

  if (requiresAuth) {
    const token = await ensureFallbackAccessToken()
    if (!token) throw new Error('No hay una sesión de Ubuntu activa.')
    headers.set('Authorization', `Bearer ${token}`)
  }
  if (usesWebSession) {
    headers.set(webSessionHeader, webSessionHeaderValue)
  }

  const requestInit: RequestInit & { auth?: boolean; webSession?: boolean } = { ...(init ?? {}) }
  delete requestInit.auth
  delete requestInit.webSession

  let response = await fetchFallback(normalizedPath, {
    ...requestInit,
    headers,
    credentials: requestInit.credentials ?? 'include',
  })
  let payload = await parseFallbackResponse(response)
  let code = String(payload.error || payload.message || '').trim()
  if (requiresAuth && response.status === 401 && code === 'INVALID_TOKEN') {
    const restored = await restoreFallbackSession()
    const refreshedToken = restored?.accessToken?.trim() || ''
    const replayableBody = !(
      typeof ReadableStream !== 'undefined' && requestInit.body instanceof ReadableStream
    )
    if (refreshedToken && replayableBody) {
      headers.set('Authorization', `Bearer ${refreshedToken}`)
      response = await fetchFallback(normalizedPath, {
        ...requestInit,
        headers,
        credentials: requestInit.credentials ?? 'include',
      })
      payload = await parseFallbackResponse(response)
      code = String(payload.error || payload.message || '').trim()
    }
  }
  if (!response.ok) {
    if (requiresAuth && (code === 'WEB_SESSION_REVOKED' || code === 'INVALID_TOKEN' || code === 'ACCOUNT_DELETED')) {
      clearFallbackSession()
    }
    if (code === 'WEB_SESSION_REVOKED') {
      throw new Error('Tu sesión web fue cerrada desde la APK móvil por seguridad.')
    }
    if (code === 'ACCOUNT_DELETED') {
      throw new Error('Esta cuenta fue eliminada y la sesión dejó de ser válida.')
    }
    throw new Error(code || 'No se pudo consultar el servidor Ubuntu.')
  }
  return payload as T
}

async function fetchFallback(path: string, init: RequestInit): Promise<Response> {
  const normalizedPath = path.startsWith('/') ? path : `/${path}`
  const firstBaseUrl = fallbackApiUrl
  const retrySafe = isHttpRetrySafeMethod(init.method)
  const canRetryBody = !(
    typeof ReadableStream !== 'undefined' && init.body instanceof ReadableStream
  )
  const requestInit = { ...init, credentials: init.credentials ?? 'include' } satisfies RequestInit
  try {
    const response = await fetch(`${firstBaseUrl}${normalizedPath}`, requestInit)
    if (![502, 503, 504].includes(response.status) || !canRetryBody || !retrySafe) return response

    const refreshedUrl = await refreshRuntimeBackend(`${firstBaseUrl}${normalizedPath}`)
    return refreshedUrl
      ? await fetch(`${refreshedUrl}${normalizedPath}`, requestInit)
      : response
  } catch (error) {
    if (error instanceof DOMException && error.name === 'AbortError') throw error
    if (canRetryBody && retrySafe) {
      const refreshedUrl = await refreshRuntimeBackend(`${firstBaseUrl}${normalizedPath}`)
      if (refreshedUrl) {
        try {
          return await fetch(`${refreshedUrl}${normalizedPath}`, requestInit)
        } catch (retryError) {
          if (retryError instanceof DOMException && retryError.name === 'AbortError') throw retryError
        }
      }
    }
    console.error('Novvin Ubuntu backend is unreachable', error)
    throw new Error(fallbackConnectionErrorMessage())
  }
}

export class FallbackTwoFactorRequiredError extends Error {
  constructor(code: string) {
    super(code === 'TWO_FACTOR_LOCKED'
      ? 'Demasiados intentos. Espera 15 minutos antes de volver a intentar.'
      : code === 'TWO_FACTOR_INVALID'
        ? 'El código de verificación o recuperación no es válido.'
        : 'Escribe el código de tu aplicación de autenticación o un código de recuperación.')
    this.name = 'FallbackTwoFactorRequiredError'
  }
}

export class FallbackPasswordChangedNeedsTwoFactorError extends Error {
  constructor() {
    super('Contraseña actualizada. Inicia sesión con tu nueva contraseña y código de verificación.')
    this.name = 'FallbackPasswordChangedNeedsTwoFactorError'
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
    accessTokenExpiresAt: accessTokenExpiration(accessToken),
    user,
    savedAt: new Date().toISOString(),
  }

  activeFallbackSession = session
  legacySessionNeedsUpgrade = false
  persistFallbackSessionMetadata(session)
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

function isValidFallbackUser(user: FallbackUser | null | undefined): user is FallbackUser {
  return Boolean(user?.id && user.email)
}

function persistFallbackSessionMetadata(session: FallbackSession) {
  const persisted = toPersistedFallbackSession(session)
  storage()?.setItem(storageKey, JSON.stringify(persisted))
}

function initializeStoredFallbackSession() {
  if (storageInitialized) return
  storageInitialized = true
  const raw = storage()?.getItem(storageKey)
  if (!raw) return
  try {
    const parsed = JSON.parse(raw) as Partial<FallbackSession & PersistedFallbackSession<FallbackUser>>
    if (!isValidFallbackUser(parsed.user)) {
      storage()?.removeItem(storageKey)
      return
    }

    const legacyToken = String(parsed.accessToken || '').trim()
    if (!legacyToken) return
    activeFallbackSession = {
      accessToken: legacyToken,
      tokenType: String(parsed.tokenType || 'Bearer'),
      expiresIn: parsed.expiresIn || '7d',
      accessTokenExpiresAt: accessTokenExpiration(legacyToken),
      user: parsed.user,
      savedAt: String(parsed.savedAt || new Date().toISOString()),
    }
    legacySessionNeedsUpgrade = true
    persistFallbackSessionMetadata(activeFallbackSession)
  } catch {
    storage()?.removeItem(storageKey)
  }
}

function readFallbackSessionMetadata(): PersistedFallbackSession<FallbackUser> | null {
  initializeStoredFallbackSession()
  const raw = storage()?.getItem(storageKey)
  if (!raw) return null
  try {
    const parsed = JSON.parse(raw) as Partial<PersistedFallbackSession<FallbackUser>>
    if (!isValidFallbackUser(parsed.user)) return null
    return {
      schemaVersion: 2,
      tokenType: String(parsed.tokenType || 'Bearer'),
      expiresIn: parsed.expiresIn || '15m',
      user: parsed.user,
      savedAt: String(parsed.savedAt || new Date().toISOString()),
    }
  } catch {
    return null
  }
}

export function readFallbackSession(): FallbackSession | null {
  initializeStoredFallbackSession()
  return activeFallbackSession
}

export function hasFallbackSessionHint(): boolean {
  return Boolean(readFallbackSessionMetadata())
}

export function fallbackSessionNeedsRefresh(withinSeconds = 90): boolean {
  const session = readFallbackSession()
  if (!session) return hasFallbackSessionHint()
  if (!session.accessTokenExpiresAt) return legacySessionNeedsUpgrade
  return session.accessTokenExpiresAt <= Date.now() + Math.max(0, withinSeconds) * 1000
}

export function clearFallbackSession() {
  activeFallbackSession = null
  legacySessionNeedsUpgrade = false
  storage()?.removeItem(storageKey)
  notifyFallbackAuthChanged()
}

export function onFallbackAuthChanged(callback: () => void) {
  if (typeof window === 'undefined') return () => {}
  window.addEventListener(eventName, callback)
  const onStorage = (event: StorageEvent) => {
    if (event.key !== storageKey) return
    storageInitialized = false
    activeFallbackSession = null
    callback()
  }
  window.addEventListener('storage', onStorage)
  return () => {
    window.removeEventListener(eventName, callback)
    window.removeEventListener('storage', onStorage)
  }
}

async function requestFallbackWebSession(
  path: '/v1/auth/web-session/refresh' | '/v1/auth/web-session/upgrade',
  accessToken = '',
): Promise<FallbackSession | null> {
  if (!isFallbackAuthConfigured) return null
  const headers = new Headers({
    Accept: 'application/json',
    'Content-Type': 'application/json',
    'ngrok-skip-browser-warning': 'true',
    [webSessionHeader]: webSessionHeaderValue,
  })
  if (accessToken) headers.set('Authorization', `Bearer ${accessToken}`)

  const response = await fetchFallback(path, {
    method: 'POST',
    headers,
    credentials: 'include',
    body: JSON.stringify({ client: 'web' }),
  })
  const payload = await parseFallbackResponse(response)
  if (!response.ok) {
    if (response.status === 401 || response.status === 403) return null
    throw new Error(String(payload.error || payload.message || 'WEB_SESSION_RESTORE_FAILED'))
  }

  persistFallbackSession(payload as {
    accessToken?: string
    tokenType?: string
    expiresIn?: string | number
    user?: FallbackUser
  })
  return readFallbackSession()
}

export async function restoreFallbackSession(): Promise<FallbackSession | null> {
  if (refreshPromise) return refreshPromise
  refreshPromise = (async () => {
    try {
      const restored = await requestFallbackWebSession('/v1/auth/web-session/refresh')
      if (!restored) clearFallbackSession()
      return restored
    } finally {
      refreshPromise = null
    }
  })()
  return refreshPromise
}

export async function upgradeFallbackWebSession(): Promise<FallbackSession | null> {
  const token = readFallbackSession()?.accessToken?.trim() || ''
  if (!token) return null
  const upgraded = await requestFallbackWebSession('/v1/auth/web-session/upgrade', token)
  if (upgraded) legacySessionNeedsUpgrade = false
  return upgraded
}

export async function ensureFallbackAccessToken(): Promise<string> {
  const current = readFallbackSession()
  if (current && !fallbackSessionNeedsRefresh()) return current.accessToken

  if (current && legacySessionNeedsUpgrade) {
    void upgradeFallbackWebSession().catch(() => {})
    if (!current.accessTokenExpiresAt || current.accessTokenExpiresAt > Date.now()) {
      return current.accessToken
    }
  }

  const restored = await restoreFallbackSession()
  return restored?.accessToken?.trim() || ''
}

export async function signOutFallbackSession(): Promise<void> {
  if (!isFallbackAuthConfigured) return
  try {
    await fetchFallback('/v1/auth/web-session', {
      method: 'DELETE',
      credentials: 'include',
      headers: {
        Accept: 'application/json',
        'ngrok-skip-browser-warning': 'true',
        [webSessionHeader]: webSessionHeaderValue,
      },
    })
  } catch {
    // Local sign-out must still complete when the self-hosted server is offline.
  }
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

export async function signInWithFallbackAuth(email: string, password: string, secondFactorCode?: string): Promise<User> {
  if (!isFallbackAuthConfigured) {
    throw new Error('El servidor Ubuntu no está configurado para esta web.')
  }

  const response = await fetchFallback('/v1/auth/login', {
    method: 'POST',
    credentials: 'include',
    headers: {
      'Content-Type': 'application/json',
      'ngrok-skip-browser-warning': 'true',
      [webSessionHeader]: webSessionHeaderValue,
    },
    body: JSON.stringify({
      email: normalizeEmail(email),
      password,
      ...(/^[0-9]{6}$/.test(secondFactorCode?.trim() || '')
        ? { totpCode: secondFactorCode?.trim() }
        : secondFactorCode?.trim() ? { backupCode: secondFactorCode.trim() } : {}),
      client: 'web',
    }),
  })
  const payload = await parseFallbackResponse(response)

  if (!response.ok) {
    const code = String(payload.error || payload.message || '').trim()
    if (['TWO_FACTOR_REQUIRED', 'TWO_FACTOR_INVALID', 'TWO_FACTOR_LOCKED'].includes(code)) {
      throw new FallbackTwoFactorRequiredError(code)
    }
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
    credentials: 'include',
    headers: {
      'Content-Type': 'application/json',
      'ngrok-skip-browser-warning': 'true',
      [webSessionHeader]: webSessionHeaderValue,
    },
    body: JSON.stringify({
      email: normalizeEmail(params.email),
      password: params.password,
      displayName: params.displayName.trim(),
      client: 'web',
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
    requiresTwoFactor?: boolean
    accessToken?: string
    tokenType?: string
    expiresIn?: string | number
    user?: FallbackUser
  }>('/v1/auth/complete-migrated-password-reset', {
    method: 'POST',
    auth: false,
    webSession: true,
    body: JSON.stringify({
      migrationToken,
      newPassword,
      client: 'web',
    }),
  })

  if (payload.requiresTwoFactor) throw new FallbackPasswordChangedNeedsTwoFactorError()
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
    requiresTwoFactor?: boolean
    accessToken?: string
    tokenType?: string
    expiresIn?: string | number
    user?: FallbackUser
  }>('/v1/auth/password-reset/confirm', {
    method: 'POST',
    auth: false,
    webSession: true,
    body: JSON.stringify({
      resetToken,
      newPassword,
      client: 'web',
    }),
  })

  if (payload.requiresTwoFactor) throw new FallbackPasswordChangedNeedsTwoFactorError()
  return persistFallbackSession(payload)
}

export function toFallbackFriendlyError(error: unknown): Error {
  return new Error(fallbackErrorMessage(error))
}
