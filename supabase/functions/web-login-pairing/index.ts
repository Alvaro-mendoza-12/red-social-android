import { serve } from 'https://deno.land/std@0.168.0/http/server.ts'

type PairingStatus = 'pending' | 'approved' | 'rejected' | 'consumed' | 'expired'

type PairingRow = {
  id: string
  nonce: string
  status: PairingStatus
  device_label: string | null
  approved_by_user_id: string | null
  approved_by_email: string | null
  session_payload: { access_token?: string; refresh_token?: string } | null
  expires_at: string
  approved_at: string | null
  rejected_at: string | null
  consumed_at: string | null
}

const corsHeaders = {
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Headers': 'authorization, x-client-info, apikey, content-type',
  'Access-Control-Allow-Methods': 'POST, OPTIONS',
  'Content-Type': 'application/json',
}

function json(status: number, body: Record<string, unknown>) {
  return new Response(JSON.stringify(body), { status, headers: corsHeaders })
}

function cleanText(value: unknown, maxLength = 160): string | null {
  if (typeof value !== 'string') return null
  const cleaned = value.trim()
  if (!cleaned) return null
  return cleaned.slice(0, maxLength)
}

function buildQrUrl(pairId: string, nonce: string): string {
  return `conectate://web-login?pairId=${encodeURIComponent(pairId)}&nonce=${encodeURIComponent(nonce)}`
}

function isExpired(expiresAt: string): boolean {
  const expiry = Date.parse(expiresAt)
  if (Number.isNaN(expiry)) return true
  return expiry <= Date.now()
}

const fetchFromServer = async (endpoint: string, params: Record<string, unknown>) => {
  const url = `http://192.168.18.22/api/${endpoint}`
  const response = await fetch(url, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(params),
  })
  return response.json()
}

async function resolveAuthenticatedUser(req: Request) {
  const authorization = req.headers.get('Authorization')?.trim()
  if (!authorization) {
    return { error: 'AUTH_REQUIRED' as const }
  }

  const user = await fetchFromServer('auth/validate', { token: authorization })
  if (!user || user.error) {
    return { error: 'INVALID_AUTH' as const }
  }

  return { user }
}

async function loadPairingRow(serviceClient: ReturnType<typeof createClient>, pairId: string, nonce: string) {
  const { data, error } = await serviceClient
    .from('web_login_pairings')
    .select(
      'id, nonce, status, device_label, approved_by_user_id, approved_by_email, session_payload, expires_at, approved_at, rejected_at, consumed_at',
    )
    .eq('id', pairId)
    .eq('nonce', nonce)
    .maybeSingle<PairingRow>()

  if (error) throw error
  return data
}

async function expirePairingIfNeeded(
  serviceClient: ReturnType<typeof createClient>,
  row: PairingRow,
): Promise<PairingRow> {
  if (!isExpired(row.expires_at) || row.status === 'consumed' || row.status === 'rejected' || row.status === 'expired') {
    return row
  }

  const { data, error } = await serviceClient
    .from('web_login_pairings')
    .update({
      status: 'expired',
      session_payload: null,
    })
    .eq('id', row.id)
    .eq('nonce', row.nonce)
    .select(
      'id, nonce, status, device_label, approved_by_user_id, approved_by_email, session_payload, expires_at, approved_at, rejected_at, consumed_at',
    )
    .single<PairingRow>()

  if (error) throw error
  return data
}

function serializePairing(row: PairingRow) {
  return {
    pairId: row.id,
    status: row.status,
    deviceLabel: row.device_label,
    approvedByUserId: row.approved_by_user_id,
    approvedByEmail: row.approved_by_email,
    expiresAt: row.expires_at,
    approvedAt: row.approved_at,
    rejectedAt: row.rejected_at,
    consumedAt: row.consumed_at,
    qrUrl: buildQrUrl(row.id, row.nonce),
  }
}

serve(async (req) => {
  if (req.method === 'OPTIONS') {
    return new Response('ok', { headers: corsHeaders })
  }

  try {
    const body = await req.json().catch(() => ({}))
    const action = cleanText(body?.action, 32)?.toLowerCase()
    if (!action) {
      return json(400, { error: 'ACTION_REQUIRED' })
    }

    const serviceClient = null // Reemplazar con la instancia de tu cliente si es necesario

    if (action === 'create') {
      const pairId = crypto.randomUUID()
      const nonce = crypto.randomUUID().replaceAll('-', '')
      const deviceLabel = cleanText(body?.deviceLabel, 120)
      const expiresAt = new Date(Date.now() + 5 * 60 * 1000).toISOString()

      const { error } = await serviceClient.from('web_login_pairings').insert({
        id: pairId,
        nonce,
        device_label: deviceLabel,
        expires_at: expiresAt,
        status: 'pending',
      })

      if (error) throw error

      return json(200, {
        pairId,
        nonce,
        status: 'pending',
        deviceLabel,
        expiresAt,
        qrUrl: buildQrUrl(pairId, nonce),
      })
    }

    const pairId = cleanText(body?.pairId, 80)
    const nonce = cleanText(body?.nonce, 120)
    if (!pairId || !nonce) {
      return json(400, { error: 'PAIR_ID_AND_NONCE_REQUIRED' })
    }

    const pairingRow = await loadPairingRow(serviceClient, pairId, nonce)
    if (!pairingRow) {
      return json(404, { error: 'PAIRING_NOT_FOUND' })
    }

    const normalizedRow = await expirePairingIfNeeded(serviceClient, pairingRow)

    if (action === 'status') {
      return json(200, serializePairing(normalizedRow))
    }

    if (action === 'approve') {
      if (normalizedRow.status !== 'pending') {
        return json(409, {
          error: 'PAIRING_NOT_PENDING',
          pairing: serializePairing(normalizedRow),
        })
      }

      const auth = await resolveAuthenticatedUser(req)
      if ('error' in auth) {
        return json(401, { error: auth.error })
      }

      const refreshToken = cleanText(body?.refreshToken, 4096)
      if (!refreshToken) {
        return json(400, { error: 'REFRESH_TOKEN_REQUIRED' })
      }

      const { data, error } = await serviceClient
        .from('web_login_pairings')
        .update({
          status: 'approved',
          approved_by_user_id: auth.user.id,
          approved_by_email: auth.user.email ?? null,
          approved_at: new Date().toISOString(),
          session_payload: {
            access_token: auth.accessToken,
            refresh_token: refreshToken,
          },
        })
        .eq('id', pairId)
        .eq('nonce', nonce)
        .eq('status', 'pending')
        .select(
          'id, nonce, status, device_label, approved_by_user_id, approved_by_email, session_payload, expires_at, approved_at, rejected_at, consumed_at',
        )
        .single<PairingRow>()

      if (error) throw error
      return json(200, serializePairing(data))
    }

    if (action === 'reject') {
      if (normalizedRow.status !== 'pending') {
        return json(409, {
          error: 'PAIRING_NOT_PENDING',
          pairing: serializePairing(normalizedRow),
        })
      }

      const auth = await resolveAuthenticatedUser(req)
      if ('error' in auth) {
        return json(401, { error: auth.error })
      }

      const { data, error } = await serviceClient
        .from('web_login_pairings')
        .update({
          status: 'rejected',
          rejected_at: new Date().toISOString(),
          approved_by_user_id: auth.user.id,
          approved_by_email: auth.user.email ?? null,
          session_payload: null,
        })
        .eq('id', pairId)
        .eq('nonce', nonce)
        .eq('status', 'pending')
        .select(
          'id, nonce, status, device_label, approved_by_user_id, approved_by_email, session_payload, expires_at, approved_at, rejected_at, consumed_at',
        )
        .single<PairingRow>()

      if (error) throw error
      return json(200, serializePairing(data))
    }

    if (action === 'consume') {
      if (normalizedRow.status !== 'approved') {
        return json(409, {
          error: 'PAIRING_NOT_APPROVED',
          pairing: serializePairing(normalizedRow),
        })
      }

      const { data, error } = await serviceClient
        .from('web_login_pairings')
        .update({
          status: 'consumed',
          consumed_at: new Date().toISOString(),
          session_payload: null,
        })
        .eq('id', pairId)
        .eq('nonce', nonce)
        .eq('status', 'approved')
        .select(
          'id, nonce, status, device_label, approved_by_user_id, approved_by_email, session_payload, expires_at, approved_at, rejected_at, consumed_at',
        )
        .single<PairingRow>()

      if (error) throw error

      const accessToken = cleanText(data.session_payload?.access_token, 4096)
      const refreshToken = cleanText(data.session_payload?.refresh_token, 4096)
      if (!accessToken || !refreshToken) {
        return json(409, {
          error: 'PAIRING_SESSION_MISSING',
          pairing: serializePairing(data),
        })
      }

      return json(200, {
        ...serializePairing(data),
        accessToken,
        refreshToken,
      })
    }

    return json(400, { error: 'UNSUPPORTED_ACTION' })
  } catch (error) {
    console.error('web-login-pairing failed', error)
    return json(500, {
      error: 'INTERNAL_ERROR',
      message: error instanceof Error ? error.message : 'Unknown error',
    })
  }
})
