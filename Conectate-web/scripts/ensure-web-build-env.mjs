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
const requiredKeys = ['VITE_SUPABASE_URL', 'VITE_SUPABASE_ANON_KEY']
const missingKeys = requiredKeys.filter((key) => isPlaceholder(resolveValue(key, envFileValues)))

if (missingKeys.length > 0) {
  console.error('[ensure-web-build-env] Faltan variables reales para compilar Conectate-web.')
  console.error(
    `[ensure-web-build-env] Define estas variables en el entorno o en ${path.relative(process.cwd(), envPath)}: ${missingKeys.join(', ')}`,
  )
  process.exit(1)
}

console.log('[ensure-web-build-env] Entorno web valido para build.')
