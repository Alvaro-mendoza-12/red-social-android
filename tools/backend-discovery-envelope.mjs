#!/usr/bin/env node

import {
  createPrivateKey,
  createPublicKey,
  createSign,
  createVerify,
  generateKeyPairSync,
} from 'node:crypto'
import fs from 'node:fs'
import path from 'node:path'

const VERSION = 'novvin1'
const MIN_TTL_SECONDS = 60
const MAX_TTL_SECONDS = 7 * 24 * 60 * 60

function fail(message) {
  process.stderr.write(`[backend-discovery] ${message}\n`)
  process.exit(1)
}

function parseArgs(argv) {
  const [command, ...rest] = argv
  const values = new Map()
  for (let index = 0; index < rest.length; index += 1) {
    const token = rest[index]
    if (!token.startsWith('--')) fail(`Argumento inesperado: ${token}`)
    const name = token.slice(2)
    const value = rest[index + 1]
    if (!value || value.startsWith('--')) fail(`Falta el valor de --${name}`)
    values.set(name, value)
    index += 1
  }
  return { command, values }
}

function required(values, name) {
  const value = String(values.get(name) || '').trim()
  if (!value) fail(`Falta --${name}`)
  return value
}

function canonicalBackendUrl(input) {
  let parsed
  try {
    parsed = new URL(String(input || '').trim())
  } catch {
    fail('La URL del backend no es válida.')
  }
  if (parsed.protocol !== 'https:') fail('La URL firmada debe usar HTTPS.')
  if (parsed.username || parsed.password) fail('La URL no puede contener credenciales.')
  if (parsed.search || parsed.hash) fail('La URL no puede contener query ni fragmento.')
  if (parsed.pathname && parsed.pathname !== '/') fail('La URL base no puede contener una ruta.')
  const tailscaleFunnelPort = parsed.port === '8443' && parsed.hostname.toLowerCase().endsWith('.ts.net')
  if (parsed.port && parsed.port !== '443' && !tailscaleFunnelPort) {
    fail('La URL de producción sólo puede usar HTTPS 443 o Tailscale Funnel 8443.')
  }
  return `https://${parsed.hostname.toLowerCase()}${tailscaleFunnelPort ? ':8443' : ''}`
}

function base64UrlEncode(value) {
  return Buffer.from(value).toString('base64url')
}

function base64UrlDecode(value) {
  const encoded = String(value || '')
  if (!encoded || !/^[A-Za-z0-9_-]+$/.test(encoded) || encoded.length % 4 === 1) {
    fail('El envelope contiene Base64URL inválido.')
  }
  try {
    const decoded = Buffer.from(encoded, 'base64url')
    if (decoded.toString('base64url') !== encoded) {
      fail('El envelope contiene Base64URL no canónico.')
    }
    return decoded
  } catch {
    fail('El envelope contiene Base64URL inválido.')
  }
}

function signatureInput(expiresAt, url) {
  return `${VERSION}\n${expiresAt}\n${url}`
}

function parseEnvelope(envelope) {
  const parts = String(envelope || '').trim().split(':')
  if (parts.length !== 4 || parts[0] !== VERSION) fail('Envelope de descubrimiento inválido.')
  const expiresAt = Number(parts[1])
  if (!Number.isSafeInteger(expiresAt) || expiresAt <= 0) fail('Expiración inválida.')
  const url = canonicalBackendUrl(base64UrlDecode(parts[2]).toString('utf8'))
  const signature = base64UrlDecode(parts[3])
  if (signature.length < 64 || signature.length > 80) fail('Firma ECDSA inválida.')
  return { expiresAt, url, signature }
}

function readKey(filePath) {
  try {
    return fs.readFileSync(path.resolve(filePath))
  } catch (error) {
    fail(`No se pudo leer la clave: ${error instanceof Error ? error.message : String(error)}`)
  }
}

function commandGenerate(values) {
  const privateOut = path.resolve(required(values, 'private-out'))
  const publicOut = values.get('public-out') ? path.resolve(values.get('public-out')) : ''
  if (fs.existsSync(privateOut)) fail('La clave privada ya existe; no se sobrescribió.')
  if (publicOut && fs.existsSync(publicOut)) fail('La clave pública ya existe; no se sobrescribió.')

  const { privateKey, publicKey } = generateKeyPairSync('ec', {
    namedCurve: 'prime256v1',
  })
  const privatePem = privateKey.export({ type: 'pkcs8', format: 'pem' })
  const publicPem = publicKey.export({ type: 'spki', format: 'pem' })
  const publicDer = publicKey.export({ type: 'spki', format: 'der' })

  fs.mkdirSync(path.dirname(privateOut), { recursive: true })
  fs.writeFileSync(privateOut, privatePem, { mode: 0o600, flag: 'wx' })
  if (publicOut) {
    fs.mkdirSync(path.dirname(publicOut), { recursive: true })
    fs.writeFileSync(publicOut, publicPem, { mode: 0o644, flag: 'wx' })
  }
  process.stdout.write(`${publicDer.toString('base64')}\n`)
}

function commandSign(values) {
  const privateKey = createPrivateKey(readKey(required(values, 'private-key')))
  const url = canonicalBackendUrl(required(values, 'url'))
  const ttl = Number(values.get('ttl-seconds') || 24 * 60 * 60)
  if (!Number.isSafeInteger(ttl) || ttl < MIN_TTL_SECONDS || ttl > MAX_TTL_SECONDS) {
    fail(`--ttl-seconds debe estar entre ${MIN_TTL_SECONDS} y ${MAX_TTL_SECONDS}.`)
  }
  const now = Math.floor(Date.now() / 1000)
  const expiresAt = now + ttl
  const signer = createSign('SHA256')
  signer.update(signatureInput(expiresAt, url), 'utf8')
  signer.end()
  const signature = signer.sign(privateKey)
  const envelope = `${VERSION}:${expiresAt}:${base64UrlEncode(url)}:${base64UrlEncode(signature)}`
  process.stdout.write(`${envelope}\n`)
}

function commandPublic(values) {
  const privateKey = createPrivateKey(readKey(required(values, 'private-key')))
  const publicDer = createPublicKey(privateKey).export({ type: 'spki', format: 'der' })
  process.stdout.write(`${publicDer.toString('base64')}\n`)
}

function commandVerify(values) {
  const publicKeyBase64 = required(values, 'public-key-base64')
  let publicKey
  try {
    publicKey = createPublicKey({
      key: Buffer.from(publicKeyBase64, 'base64'),
      format: 'der',
      type: 'spki',
    })
  } catch {
    fail('La clave pública SPKI Base64 no es válida.')
  }
  const parsed = parseEnvelope(required(values, 'envelope'))
  if (parsed.expiresAt <= Math.floor(Date.now() / 1000)) fail('El envelope está expirado.')
  if (parsed.expiresAt > Math.floor(Date.now() / 1000) + MAX_TTL_SECONDS + 300) {
    fail('El envelope excede la vigencia máxima permitida.')
  }
  const verifier = createVerify('SHA256')
  verifier.update(signatureInput(parsed.expiresAt, parsed.url), 'utf8')
  verifier.end()
  if (!verifier.verify(publicKey, parsed.signature)) fail('La firma del endpoint no es válida.')
  process.stdout.write(`${parsed.url}\n`)
}

const { command, values } = parseArgs(process.argv.slice(2))
if (command === 'generate') commandGenerate(values)
else if (command === 'sign') commandSign(values)
else if (command === 'public') commandPublic(values)
else if (command === 'verify') commandVerify(values)
else {
  fail(
    'Uso: generate --private-out FILE [--public-out FILE] | public --private-key FILE | sign --private-key FILE --url URL [--ttl-seconds N] | verify --public-key-base64 B64 --envelope VALUE',
  )
}
