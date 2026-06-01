import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const currentFile = fileURLToPath(import.meta.url)
const scriptsDir = path.dirname(currentFile)
const projectRoot = path.resolve(scriptsDir, '..')
const envPath = path.join(projectRoot, '.env')

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

const envFileValues = readEnvFile(envPath)
const fallbackApiUrl = resolveValue('VITE_FALLBACK_API_URL', envFileValues)
const hasFallbackBackend =
  !isPlaceholder(fallbackApiUrl) && /^https?:\/\//i.test(fallbackApiUrl)
const supabaseKeys = ['VITE_SUPABASE_URL', 'VITE_SUPABASE_ANON_KEY']
const missingSupabaseKeys = supabaseKeys.filter((key) =>
  isPlaceholder(resolveValue(key, envFileValues)),
)
const hasSupabaseBackend = missingSupabaseKeys.length === 0

if (!hasFallbackBackend && !hasSupabaseBackend) {
  console.error('[ensure-web-build-env] Faltan variables reales para compilar Conectate-web.')
  console.error(
    `[ensure-web-build-env] Define VITE_FALLBACK_API_URL para usar Ubuntu como backend principal, o define estas variables legacy de Supabase en el entorno o en ${path.relative(process.cwd(), envPath)}: ${missingSupabaseKeys.join(', ')}`,
  )
  process.exit(1)
}

if (hasFallbackBackend) {
  console.log('[ensure-web-build-env] Entorno web valido para build con Ubuntu/Fallback API.')
} else {
  console.log('[ensure-web-build-env] Entorno web valido para build con Supabase legacy.')
}
