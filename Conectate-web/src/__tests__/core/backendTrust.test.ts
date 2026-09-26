import { createSign, generateKeyPairSync, webcrypto } from 'node:crypto'
import {
  canonicalBackendBaseUrl,
  isExplicitlyTrustedBackend,
  verifyBackendDiscoveryEnvelope,
} from '../../core/backendTrust'

function base64Url(value: string | Uint8Array): string {
  return Buffer.from(value).toString('base64url')
}

function fixture(ttlSeconds = 600) {
  const { privateKey, publicKey } = generateKeyPairSync('ec', { namedCurve: 'prime256v1' })
  const url = 'https://dynamic-api.novvin.example'
  const now = 2_000_000_000
  const expiresAt = now + ttlSeconds
  const input = `novvin1\n${expiresAt}\n${url}`
  const signer = createSign('SHA256')
  signer.update(input)
  signer.end()
  const signature = signer.sign(privateKey)
  const envelope = `novvin1:${expiresAt}:${base64Url(url)}:${base64Url(signature)}`
  const publicKeyBase64 = publicKey.export({ type: 'spki', format: 'der' }).toString('base64')
  return { envelope, now, publicKeyBase64, url }
}

describe('backend trust policy', () => {
  test('accepts only canonical root origins as explicit backends', () => {
    expect(canonicalBackendBaseUrl('https://API.Novvin.Example/')).toBe(
      'https://api.novvin.example',
    )
    expect(canonicalBackendBaseUrl('https://api.novvin.example/path')).toBe('')
    expect(canonicalBackendBaseUrl('https://user:pass@api.novvin.example')).toBe('')
    expect(canonicalBackendBaseUrl('http://api.novvin.example')).toBe('')
    expect(canonicalBackendBaseUrl('https://Conectate.Tail9B267C.Ts.Net:8443/')).toBe(
      'https://conectate.tail9b267c.ts.net:8443',
    )
    expect(canonicalBackendBaseUrl('https://api.novvin.example:8443')).toBe('')
    expect(
      isExplicitlyTrustedBackend(
        'https://api.novvin.example/',
        'https://api.novvin.example',
      ),
    ).toBe(true)
    expect(
      isExplicitlyTrustedBackend(
        'https://attacker.example',
        'https://api.novvin.example',
      ),
    ).toBe(false)
  })

  test('verifies a signed, bounded and unexpired dynamic endpoint', async () => {
    const signed = fixture()
    await expect(
      verifyBackendDiscoveryEnvelope(signed.envelope, signed.publicKeyBase64, {
        nowEpochSeconds: signed.now,
        subtle: webcrypto.subtle as unknown as SubtleCrypto,
      }),
    ).resolves.toMatchObject({
      url: signed.url,
      expiresAtEpochSeconds: signed.now + 600,
    })
  })

  test('rejects tampering, expiration and excessive validity', async () => {
    const signed = fixture()
    const parts = signed.envelope.split(':')
    parts[2] = base64Url('https://attacker.example')
    await expect(
      verifyBackendDiscoveryEnvelope(parts.join(':'), signed.publicKeyBase64, {
        nowEpochSeconds: signed.now,
        subtle: webcrypto.subtle as unknown as SubtleCrypto,
      }),
    ).resolves.toBeNull()
    await expect(
      verifyBackendDiscoveryEnvelope(signed.envelope, signed.publicKeyBase64, {
        nowEpochSeconds: signed.now + 1_000,
        subtle: webcrypto.subtle as unknown as SubtleCrypto,
      }),
    ).resolves.toBeNull()

    const excessive = fixture(8 * 24 * 60 * 60)
    await expect(
      verifyBackendDiscoveryEnvelope(excessive.envelope, excessive.publicKeyBase64, {
        nowEpochSeconds: excessive.now,
        subtle: webcrypto.subtle as unknown as SubtleCrypto,
      }),
    ).resolves.toBeNull()
  })
})
