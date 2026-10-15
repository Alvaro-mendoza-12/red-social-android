import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const currentFile = fileURLToPath(import.meta.url)
const scriptsDir = path.dirname(currentFile)
const projectRoot = path.resolve(scriptsDir, '..')
const envPath = path.join(projectRoot, '.env')
const sharedDiscoveryPublicKeyPath = path.resolve(
  projectRoot,
  '..',
  'config',
  'backend-discovery-public-key.txt',
)

function readEnvFile(filePath) {
  if (!fs.existsSync(filePath)) return {}
  const raw = fs.readFileSync(filePath, 'utf8')
  return Object.fromEntries(
    raw
      .split(/\r?\n/)
      .map((line) => line.trim())
      .filter((line) => line && !line.startsWith('#') && line.includes('='))
      .map((line) => {
        const separator = line.indexOf('=')
        const key = line.slice(0, separator).trim()
        const value = line.slice(separator + 1).trim().replace(/^['"]|['"]$/g, '')
        return [key, value]
      }),
  )
}

function resolveValue(key, fallbackEnv) {
  const raw = process.env[key] ?? fallbackEnv[key] ?? ''
  return String(raw).trim()
}

function isPlaceholder(value) {
  const normalized = value.toLowerCase()
  return (
    !normalized ||
    normalized.includes('your-project') ||
    normalized.includes('your-anon-key') ||
    normalized.includes('example')
  )
}

const envFileValues = { ...readEnvFile(envPath), ...readEnvFile(path.join(projectRoot, '.env.local')), ...readEnvFile(path.join(projectRoot, '.env.production')), ...readEnvFile(path.join(projectRoot, '.env.production.local')) }
const fallbackApiUrl = resolveValue('VITE_FALLBACK_API_URL', envFileValues)
let canonicalFallbackApiUrl = ''
try {
  const parsedFallbackApiUrl = new URL(fallbackApiUrl)
  if (
    parsedFallbackApiUrl.protocol === 'https:' &&
    !parsedFallbackApiUrl.username &&
    !parsedFallbackApiUrl.password &&
    !parsedFallbackApiUrl.search &&
    !parsedFallbackApiUrl.hash &&
    (!parsedFallbackApiUrl.pathname || parsedFallbackApiUrl.pathname === '/') &&
    (!parsedFallbackApiUrl.port || parsedFallbackApiUrl.port === '443' ||
      (parsedFallbackApiUrl.port === '8443' && parsedFallbackApiUrl.hostname.toLowerCase().endsWith('.ts.net')))
  ) {
    canonicalFallbackApiUrl = parsedFallbackApiUrl.origin
  }
} catch {
  canonicalFallbackApiUrl = ''
}
const hasFallbackBackend = !isPlaceholder(fallbackApiUrl) && canonicalFallbackApiUrl.length > 0
const sharedDiscoveryPublicKey = fs.existsSync(sharedDiscoveryPublicKeyPath)
  ? fs.readFileSync(sharedDiscoveryPublicKeyPath, 'utf8').trim()
  : ''
const discoveryPublicKey =
  resolveValue('VITE_BACKEND_DISCOVERY_PUBLIC_KEY', envFileValues) || sharedDiscoveryPublicKey
const hasValidDiscoveryPublicKey =
  !discoveryPublicKey ||
  (/^[A-Za-z0-9+/]+={0,2}$/.test(discoveryPublicKey) && discoveryPublicKey.length >= 80)
const ephemeralFallbackHost = (() => {
  try {
    const host = new URL(canonicalFallbackApiUrl).hostname.toLowerCase()
    return (
      host.endsWith('.trycloudflare.com') ||
      host.endsWith('.ngrok-free.app') ||
      host.endsWith('.ngrok.io')
    )
  } catch {
    return false
  }
})()
const supabaseKeys = ['VITE_SUPABASE_URL', 'VITE_SUPABASE_ANON_KEY']
const configuredSupabaseKeys = supabaseKeys.filter((key) =>
  !isPlaceholder(resolveValue(key, envFileValues)),
)

if (!hasFallbackBackend) {
  console.error('[ensure-web-build-env] Falta una VITE_FALLBACK_API_URL HTTPS para compilar NoWing-web con Ubuntu.')
  console.error(
    `[ensure-web-build-env] Define VITE_FALLBACK_API_URL en el entorno o en ${path.relative(process.cwd(), envPath)}. Supabase legacy ya no es un backend valido para este build.`,
  )
  process.exit(1)
}

if (configuredSupabaseKeys.length > 0) {
  console.warn(
    `[ensure-web-build-env] Variables Supabase legacy detectadas e ignoradas: ${configuredSupabaseKeys.join(', ')}.`,
  )
}

if (!hasValidDiscoveryPublicKey) {
  console.error(
    '[ensure-web-build-env] VITE_BACKEND_DISCOVERY_PUBLIC_KEY no es una clave SPKI Base64 valida.',
  )
  process.exit(1)
}

if (ephemeralFallbackHost && !discoveryPublicKey) {
  console.error(
    '[ensure-web-build-env] Un backend efimero requiere la clave publica de descubrimiento firmada.',
  )
  process.exit(1)
}

if (!discoveryPublicKey) {
  console.warn(
    '[ensure-web-build-env] Descubrimiento dinamico desactivado: solo se confiara en VITE_FALLBACK_API_URL.',
  )
}

console.log('[ensure-web-build-env] Entorno web valido para build con Ubuntu/Fallback API.')
