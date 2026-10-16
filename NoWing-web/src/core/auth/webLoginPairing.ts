import type { SupabaseClient } from '@supabase/supabase-js'
import { fallbackApiJson, isFallbackAuthConfigured, type FallbackUser } from './fallbackAuth'
import { friendlyError } from '../utils'

export type WebLoginPairingStatus = 'pending' | 'approved' | 'rejected' | 'consumed' | 'expired'

export type WebLoginPairing = {
  pairId: string
  nonce?: string
  status: WebLoginPairingStatus
  deviceLabel?: string | null
  approvedByUserId?: string | null
  approvedByEmail?: string | null
  expiresAt?: string | null
  approvedAt?: string | null
  rejectedAt?: string | null
  consumedAt?: string | null
  qrUrl?: string | null
  accessToken?: string | null
  refreshToken?: string | null
  tokenType?: string | null
  expiresIn?: string | number | null
  user?: FallbackUser | null
}

type PairingResponse = {
  error?: string
  message?: string
} & WebLoginPairing

async function invokePairing(
  client: SupabaseClient,
  body: Record<string, unknown>,
  fallbackError: string,
): Promise<WebLoginPairing> {
  const { data, error } = await client.functions.invoke<PairingResponse>('web-login-pairing', {
    body,
  })

  if (error) throw error
  if (!data) throw new Error(fallbackError)
  if (data.error) {
    throw new Error(data.message || data.error)
  }

  return data
}

export function describeCurrentDevice(): string {
  const ua = navigator.userAgent
  const browser = /edg/i.test(ua)
    ? 'Edge'
    : /chrome|crios/i.test(ua)
      ? 'Chrome'
      : /firefox|fxios/i.test(ua)
        ? 'Firefox'
        : /safari/i.test(ua) && !/chrome|crios|android/i.test(ua)
          ? 'Safari'
          : 'Navegador'
  const platform = /windows/i.test(ua)
    ? 'Windows'
    : /mac os x/i.test(ua)
      ? 'macOS'
      : /android/i.test(ua)
        ? 'Android'
        : /iphone|ipad|ios/i.test(ua)
          ? 'iPhone'
          : /linux/i.test(ua)
            ? 'Linux'
            : 'equipo actual'
  return `${browser} en ${platform}`
}

async function invokeFallbackPairing(body: Record<string, unknown>, fallbackError: string) {
  if (!isFallbackAuthConfigured) throw new Error(fallbackError)
  const data = await fallbackApiJson<PairingResponse>('/v1/auth/web-login-pairing', {
    method: 'POST',
    auth: false,
    webSession: String(body.action || '').trim().toLowerCase() === 'consume',
    body: JSON.stringify(body),
  })
  if (data.error) {
    throw new Error(data.message || data.error)
  }
  return data
}

export async function createWebLoginPairing(
  client: SupabaseClient | null | undefined,
  deviceLabel = describeCurrentDevice(),
) {
  if (isFallbackAuthConfigured) {
    return invokeFallbackPairing(
      {
        action: 'create',
        deviceLabel,
      },
      'No se pudo crear el QR de Novvin. Vuelve a intentarlo.',
    )
  }

  if (!client) {
    throw new Error('No hay backend configurado para crear el QR de Novvin Web.')
  }

  return invokePairing(
    client,
    {
      action: 'create',
      deviceLabel,
    },
    'No se pudo crear el QR de Novvin Web.',
  )
}

export async function readWebLoginPairingStatus(
  client: SupabaseClient | null | undefined,
  pairId: string,
  nonce: string,
) {
  if (isFallbackAuthConfigured) {
    return invokeFallbackPairing(
      {
        action: 'status',
        pairId,
        nonce,
      },
      'No se pudo comprobar el QR de Novvin. Vuelve a intentarlo.',
    )
  }

  if (!client) {
    throw new Error('No hay backend configurado para revisar el QR de Novvin Web.')
  }

  return invokePairing(
    client,
    {
      action: 'status',
      pairId,
      nonce,
    },
    'No se pudo verificar el estado del QR.',
  )
}

export async function consumeApprovedWebLoginPairing(
  client: SupabaseClient | null | undefined,
  pairId: string,
  nonce: string,
) {
  if (isFallbackAuthConfigured) {
    return invokeFallbackPairing(
      {
        action: 'consume',
        pairId,
        nonce,
      },
      'No se pudo completar el acceso. Vuelve a intentarlo.',
    )
  }

  if (!client) {
    throw new Error('No hay backend configurado para completar el QR de Novvin Web.')
  }

  return invokePairing(
    client,
    {
      action: 'consume',
      pairId,
      nonce,
    },
    'No se pudo completar el inicio por QR.',
  )
}

export function toPairingError(caught: unknown, fallback: string): string {
  return friendlyError(caught, fallback)
}
