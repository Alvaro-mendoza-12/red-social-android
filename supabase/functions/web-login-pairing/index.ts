import { serve } from 'https://deno.land/std@0.168.0/http/server.ts'

/**
 * Retired compatibility endpoint.
 *
 * Secure QR pairing is owned by the Ubuntu API at
 * POST /v1/auth/web-login-pairing. Keeping a deterministic tombstone here
 * prevents an old Supabase deployment from accepting credentials or silently
 * attempting to reach a private LAN address.
 */

const corsHeaders = {
  'Access-Control-Allow-Origin': '*',
  'Access-Control-Allow-Headers': 'authorization, x-client-info, apikey, content-type',
  'Access-Control-Allow-Methods': 'POST, OPTIONS',
  'Cache-Control': 'no-store',
  'Content-Type': 'application/json',
}

serve((request) => {
  if (request.method === 'OPTIONS') {
    return new Response(null, { status: 204, headers: corsHeaders })
  }

  return new Response(
    JSON.stringify({
      error: 'UBUNTU_BACKEND_REQUIRED',
      message: 'El inicio de sesión por QR se procesa únicamente en el backend Ubuntu de NOVVIN.',
    }),
    { status: 410, headers: corsHeaders },
  )
})
