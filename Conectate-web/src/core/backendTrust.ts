const DISCOVERY_VERSION = 'novvin1'
const MAX_DISCOVERY_TTL_SECONDS = 7 * 24 * 60 * 60
const CLOCK_SKEW_SECONDS = 5 * 60

export type VerifiedBackendDiscovery = {
  url: string
  expiresAtEpochSeconds: number
  envelope: string
}

export function canonicalBackendBaseUrl(value: unknown, allowHttp = false): string {
  const raw = String(value ?? '').trim()
  if (!raw) return ''
  try {
    const parsed = new URL(raw)
    const validProtocol = parsed.protocol === 'https:' || (allowHttp && parsed.protocol === 'http:')
    if (!validProtocol || parsed.username || parsed.password || parsed.search || parsed.hash) return ''
    if (parsed.pathname && parsed.pathname !== '/') return ''
    const tailscaleFunnelPort = parsed.port === '8443' && parsed.hostname.toLowerCase().endsWith('.ts.net')
    if (parsed.protocol === 'https:' && parsed.port && parsed.port !== '443' && !tailscaleFunnelPort) return ''
    if (parsed.protocol === 'http:' && parsed.port && !allowHttp) return ''
    return parsed.origin.toLowerCase()
  } catch {
    return ''
  }
}

export function isExplicitlyTrustedBackend(
  candidate: unknown,
  configuredBackend: unknown,
  allowHttp = false,
): boolean {
  const canonicalCandidate = canonicalBackendBaseUrl(candidate, allowHttp)
  const canonicalConfigured = canonicalBackendBaseUrl(configuredBackend, allowHttp)
  return Boolean(canonicalCandidate && canonicalConfigured && canonicalCandidate === canonicalConfigured)
}

function decodeBase64(value: string, urlSafe: boolean): Uint8Array {
  const normalized = urlSafe ? value.replace(/-/g, '+').replace(/_/g, '/') : value
  if (!normalized || !/^[A-Za-z0-9+/]*={0,2}$/.test(normalized)) {
    throw new Error('Base64 inválido.')
  }
  const unpadded = normalized.replace(/=+$/, '')
  const padded = unpadded.padEnd(Math.ceil(unpadded.length / 4) * 4, '=')
  const decoded = atob(padded)
  const bytes = Uint8Array.from(decoded, (character) => character.charCodeAt(0))
  const canonical = btoa(String.fromCharCode(...bytes)).replace(/=+$/, '')
  if (canonical !== unpadded) throw new Error('Base64 no canónico.')
  return bytes
}

function decodeUtf8Base64Url(value: string): string {
  return new TextDecoder('utf-8', { fatal: true }).decode(decodeBase64(value, true))
}

function readDerLength(bytes: Uint8Array, offset: number): { length: number; next: number } {
  const first = bytes[offset]
  if (first === undefined) throw new Error('Firma DER truncada.')
  if ((first & 0x80) === 0) return { length: first, next: offset + 1 }
  const count = first & 0x7f
  if (count < 1 || count > 2 || offset + count >= bytes.length) {
    throw new Error('Longitud DER inválida.')
  }
  let length = 0
  for (let index = 0; index < count; index += 1) {
    length = (length << 8) | bytes[offset + 1 + index]!
  }
  if (length < 128) throw new Error('Longitud DER no canónica.')
  return { length, next: offset + 1 + count }
}

function readDerInteger(bytes: Uint8Array, offset: number): { value: Uint8Array; next: number } {
  if (bytes[offset] !== 0x02) throw new Error('Firma DER sin entero ECDSA.')
  const parsedLength = readDerLength(bytes, offset + 1)
  const end = parsedLength.next + parsedLength.length
  if (parsedLength.length < 1 || end > bytes.length) throw new Error('Entero DER truncado.')
  let value = bytes.slice(parsedLength.next, end)
  if ((value[0]! & 0x80) !== 0) throw new Error('Entero DER negativo.')
  if (value.length > 1 && value[0] === 0) value = value.slice(1)
  if (value.length > 32) throw new Error('Entero ECDSA fuera de rango.')
  const padded = new Uint8Array(32)
  padded.set(value, 32 - value.length)
  return { value: padded, next: end }
}

/** WebCrypto expects an IEEE-P1363 r||s signature, while OpenSSL emits ASN.1 DER. */
export function ecdsaDerToP1363(signature: Uint8Array): Uint8Array {
  if (signature[0] !== 0x30) throw new Error('Firma ECDSA no es una secuencia DER.')
  const sequenceLength = readDerLength(signature, 1)
  const sequenceEnd = sequenceLength.next + sequenceLength.length
  if (sequenceEnd !== signature.length) throw new Error('Firma DER con datos sobrantes o truncados.')
  const r = readDerInteger(signature, sequenceLength.next)
  const s = readDerInteger(signature, r.next)
  if (s.next !== sequenceEnd) throw new Error('Firma DER contiene campos inesperados.')
  const raw = new Uint8Array(64)
  raw.set(r.value, 0)
  raw.set(s.value, 32)
  return raw
}

function signatureInput(expiresAt: number, url: string): Uint8Array {
  return new TextEncoder().encode(`${DISCOVERY_VERSION}\n${expiresAt}\n${url}`)
}

function ownedArrayBuffer(bytes: Uint8Array): ArrayBuffer {
  const copy = new Uint8Array(bytes.byteLength)
  copy.set(bytes)
  return copy.buffer
}

export async function verifyBackendDiscoveryEnvelope(
  envelopeValue: unknown,
  publicKeySpkiBase64: unknown,
  options: {
    nowEpochSeconds?: number
    subtle?: SubtleCrypto
  } = {},
): Promise<VerifiedBackendDiscovery | null> {
  const envelope = String(envelopeValue ?? '').trim().replace(/^"|"$/g, '')
  const publicKeyBase64 = String(publicKeySpkiBase64 ?? '').trim()
  if (!envelope || !publicKeyBase64) return null
  const parts = envelope.split(':')
  if (parts.length !== 4 || parts[0] !== DISCOVERY_VERSION) return null
  const expiresAtEpochSeconds = Number(parts[1])
  const now = options.nowEpochSeconds ?? Math.floor(Date.now() / 1000)
  if (!Number.isSafeInteger(expiresAtEpochSeconds)) return null
  if (expiresAtEpochSeconds <= now - CLOCK_SKEW_SECONDS) return null
  if (expiresAtEpochSeconds > now + MAX_DISCOVERY_TTL_SECONDS + CLOCK_SKEW_SECONDS) return null

  try {
    const url = canonicalBackendBaseUrl(decodeUtf8Base64Url(parts[2]), false)
    if (!url) return null
    const publicKey = await (options.subtle ?? globalThis.crypto.subtle).importKey(
      'spki',
      ownedArrayBuffer(decodeBase64(publicKeyBase64, false)),
      { name: 'ECDSA', namedCurve: 'P-256' },
      false,
      ['verify'],
    )
    const verified = await (options.subtle ?? globalThis.crypto.subtle).verify(
      { name: 'ECDSA', hash: 'SHA-256' },
      publicKey,
      ownedArrayBuffer(ecdsaDerToP1363(decodeBase64(parts[3], true))),
      ownedArrayBuffer(signatureInput(expiresAtEpochSeconds, url)),
    )
    if (!verified) return null
    return { url, expiresAtEpochSeconds, envelope }
  } catch {
    return null
  }
}
