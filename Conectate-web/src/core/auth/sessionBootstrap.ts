import type { Session, SupabaseClient, User } from '@supabase/supabase-js'
import { isSupabaseServiceRestrictionError } from '../supabase'
import { textOf, type GenericRow } from '../utils'
import { isFallbackAuthConfigured, signInWithFallbackAuth, signUpWithFallbackAuth } from './fallbackAuth'

function normalizeEmail(value: string): string {
  return value.trim().toLowerCase()
}

function metadataRow(user: User): GenericRow {
  const metadata = user.user_metadata
  return metadata && typeof metadata === 'object' ? (metadata as GenericRow) : {}
}

function metadataText(user: User, keys: string[], fallback = ''): string {
  return textOf(metadataRow(user), keys, fallback).trim()
}

function metadataBoolean(user: User, keys: string[]): boolean {
  const metadata = metadataRow(user)
  for (const key of keys) {
    const value = metadata[key]
    if (typeof value === 'boolean') return value
    if (typeof value === 'string') {
      const normalized = value.trim().toLowerCase()
      if (normalized === 'true') return true
      if (normalized === 'false') return false
    }
  }
  return false
}

function extractMissingProfilesColumn(message: string): string | null {
  const patterns = [
    /Could not find the '([a-zA-Z0-9_]+)' column of 'profiles'/i,
    /column\s+['"]?profiles\.([a-zA-Z0-9_]+)['"]?\s+does not exist/i,
    /column\s+['"]?([a-zA-Z0-9_]+)['"]?\s+does not exist/i,
  ]

  for (const pattern of patterns) {
    const match = pattern.exec(message)
    const column = match?.[1]?.trim().toLowerCase()
    if (column) return column
  }

  return null
}

function buildProfilePayload(user: User): Record<string, unknown> {
  const email = normalizeEmail(user.email ?? '')
  const displayName =
    metadataText(user, ['display_name', 'full_name'], '') ||
    email.split('@')[0] ||
    'Usuario'
  const payload: Record<string, unknown> = {
    id: user.id,
    email,
    display_name: displayName,
    full_name: metadataText(user, ['full_name', 'display_name'], displayName) || displayName,
  }

  const avatarUrl = metadataText(user, ['avatar_url', 'picture'], '')
  const country = metadataText(user, ['country'], '')
  const privacyAccepted = metadataBoolean(user, ['privacy_policy_accepted'])
  const privacyAcceptedAt = metadataText(user, ['privacy_policy_accepted_at'], '')

  if (avatarUrl) payload.avatar_url = avatarUrl
  if (country) payload.country = country
  if (privacyAccepted) payload.privacy_policy_accepted = true
  if (privacyAcceptedAt) payload.privacy_policy_accepted_at = privacyAcceptedAt

  return payload
}

export async function resolveLoginEmailAlias(
  client: SupabaseClient,
  email: string,
): Promise<string> {
  const normalized = normalizeEmail(email)
  if (!normalized) return normalized

  const { data, error } = await client.rpc('resolve_login_email', {
    p_email: normalized,
  })
  if (error) {
    return normalized
  }

  return typeof data === 'string' && data.trim().length > 0 ? normalizeEmail(data) : normalized
}

export async function ensureProfileRow(
  client: SupabaseClient,
  user: User | null,
): Promise<void> {
  if (!user) return

  let payload = buildProfilePayload(user)
  while (true) {
    const { error } = await client.from('profiles').upsert(payload, {
      onConflict: 'id',
      ignoreDuplicates: false,
    })

    if (!error) {
      return
    }

    const missingColumn = extractMissingProfilesColumn(String(error.message ?? ''))
    if (!missingColumn || !(missingColumn in payload)) {
      throw error
    }

    const nextPayload = { ...payload }
    delete nextPayload[missingColumn]
    payload = nextPayload
  }
}

export async function bootstrapSessionProfile(
  client: SupabaseClient,
  session: Session | null,
): Promise<User | null> {
  const activeUser = session?.user ?? null
  if (!activeUser) return null
  await ensureProfileRow(client, activeUser)
  return activeUser
}

export async function signInWithResolvedEmail(
  client: SupabaseClient | null,
  email: string,
  password: string,
): Promise<void> {
  const normalizedEmail = normalizeEmail(email)

  if (isFallbackAuthConfigured) {
    await signInWithFallbackAuth(normalizedEmail, password)
    return
  }

  if (!client) {
    throw new Error('El servidor Ubuntu no esta configurado para iniciar sesion.')
  }

  let response = await client.auth.signInWithPassword({
    email: normalizedEmail,
    password,
  })

  if (response.error) {
    const resolvedEmail = await resolveLoginEmailAlias(client, normalizedEmail)
    if (resolvedEmail && resolvedEmail !== normalizedEmail) {
      response = await client.auth.signInWithPassword({
        email: resolvedEmail,
        password,
      })
    }
  }

  if (response.error) {
    if (isFallbackAuthConfigured && isSupabaseServiceRestrictionError(response.error)) {
      await signInWithFallbackAuth(normalizedEmail, password)
      return
    }
    throw response.error
  }

  await ensureProfileRow(client, response.data.user ?? response.data.session?.user ?? null)
}

export async function signUpAndBootstrapProfile(
  client: SupabaseClient | null,
  params: {
    email: string
    password: string
    displayName: string
  },
): Promise<void> {
  const normalizedEmail = normalizeEmail(params.email)
  const cleanDisplayName = params.displayName.trim()

  if (isFallbackAuthConfigured) {
    await signUpWithFallbackAuth({
      email: normalizedEmail,
      password: params.password,
      displayName: cleanDisplayName,
    })
    return
  }

  if (!client) {
    throw new Error('El servidor Ubuntu no esta configurado para crear cuentas.')
  }

  const response = await client.auth.signUp({
    email: normalizedEmail,
    password: params.password,
    options: {
      data: {
        display_name: cleanDisplayName,
        full_name: cleanDisplayName,
      },
    },
  })

  if (response.error) {
    if (isFallbackAuthConfigured && isSupabaseServiceRestrictionError(response.error)) {
      await signUpWithFallbackAuth({
        email: normalizedEmail,
        password: params.password,
        displayName: cleanDisplayName,
      })
      return
    }
    throw response.error
  }

  await ensureProfileRow(client, response.data.session?.user ?? null)
}
