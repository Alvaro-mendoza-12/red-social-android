const assert = require('assert/strict')
const fs = require('fs')
const path = require('path')
const { spawnSync } = require('child_process')

const apiRoot = path.resolve(__dirname, '..')
const repositoryRoot = path.resolve(apiRoot, '..', '..', '..')

function expectStartupRejected({ cwd, entry, env, message }) {
  const result = spawnSync(process.execPath, [entry], {
    cwd,
    env: { ...process.env, ...env },
    encoding: 'utf8',
    timeout: 10_000,
  })
  const output = `${result.stdout || ''}\n${result.stderr || ''}`
  assert.notEqual(result.status, 0, `startup unexpectedly succeeded:\n${output}`)
  assert.match(output, message)
}

const commonApiEnv = {
  NODE_ENV: 'production',
  DATABASE_URL: 'postgres://unused:unused@127.0.0.1:1/unused',
  JWT_SECRET: 'production-test-jwt-secret-at-least-32-characters',
  PASSWORD_RESET_DELIVERY: 'disabled',
}

const apiServerSource = fs.readFileSync(path.join(apiRoot, 'src', 'server.js'), 'utf8')
const clientAuthSessionSource = fs.readFileSync(
  path.join(apiRoot, 'src', 'client-auth-session.js'),
  'utf8',
)
const webSessionSecuritySource = fs.readFileSync(
  path.join(apiRoot, 'src', 'web-session-security.js'),
  'utf8',
)
const apiDockerfile = fs.readFileSync(path.join(apiRoot, 'Dockerfile'), 'utf8')
const apiStartupScript = fs.readFileSync(path.join(apiRoot, 'scripts', 'start-with-antivirus.sh'), 'utf8')
const composeSource = fs.readFileSync(
  path.join(repositoryRoot, 'infrastructure', 'local-fallback', 'docker-compose.yml'),
  'utf8',
)

function composeServiceSection(serviceName) {
  const marker = `  ${serviceName}:`
  const start = composeSource.indexOf(marker)
  assert.notEqual(start, -1, `compose service missing: ${serviceName}`)
  const remainder = composeSource.slice(start + marker.length)
  const nextService = remainder.search(/^  [a-zA-Z0-9_-]+:/m)
  return nextService === -1
    ? composeSource.slice(start)
    : composeSource.slice(start, start + marker.length + nextService)
}

const apiComposeSection = composeServiceSection('api')
assert.match(apiDockerfile, /adduser[^\n]+-u 10001[^\n]+novvin/)
assert.match(apiDockerfile, /apk add[^\n]+su-exec/)
assert.match(apiStartupScript, /exec su-exec "\$runtime_user:\$runtime_group" node src\/server\.js/)
assert.match(apiStartupScript, /\.novvin-runtime-owner-v2/)
assert.match(apiStartupScript, /top_level_owner_mismatch="\$\(/)
assert.match(apiStartupScript, /find "\$storage_root" -mindepth 1 -maxdepth 1/)
assert.match(apiStartupScript, /\[ ! -f "\$runtime_owner_marker" \][\s\S]*?chown -R "\$runtime_user:\$runtime_group" "\$storage_root"/)
assert.match(apiStartupScript, /API_STORAGE_OWNERSHIP_REPAIR_FAILED/)
assert.match(apiStartupScript, /image_variant_root="\$storage_root\/_variants"/)
assert.match(apiStartupScript, /find "\$image_variant_root"[\s\S]*?! -user "\$runtime_user"[\s\S]*?chown -R "\$runtime_user:\$runtime_group" "\$image_variant_root"/)
assert.match(apiComposeSection, /read_only:\s*true/)
assert.match(apiComposeSection, /cap_drop:\s*\n\s*- ALL/)
assert.match(apiComposeSection, /no-new-privileges:true/)

const resourceBudgetContracts = {
  db: ['DB_MEMORY_LIMIT:-2g', 'DB_MEMORY_SWAP_LIMIT:-2g', 'DB_CPU_LIMIT:-2.0', 'DB_PIDS_LIMIT:-256'],
  'signaling-redis': [
    'SIGNALING_REDIS_MEMORY_LIMIT:-512m',
    'SIGNALING_REDIS_MEMORY_SWAP_LIMIT:-512m',
    'SIGNALING_REDIS_CPU_LIMIT:-0.50',
    'SIGNALING_REDIS_PIDS_LIMIT:-64',
  ],
  api: ['API_MEMORY_LIMIT:-4g', 'API_MEMORY_SWAP_LIMIT:-4g', 'API_CPU_LIMIT:-3.0', 'API_PIDS_LIMIT:-384'],
  'ai-chatbot': ['AI_MEMORY_LIMIT:-512m', 'AI_MEMORY_SWAP_LIMIT:-512m', 'AI_CPU_LIMIT:-0.75', 'pids_limit: 64'],
  signaling: ['SIGNALING_MEMORY_LIMIT:-256m', 'SIGNALING_MEMORY_SWAP_LIMIT:-256m', 'SIGNALING_CPU_LIMIT:-1.0', 'pids_limit: 128'],
  edge: ['EDGE_MEMORY_LIMIT:-256m', 'EDGE_MEMORY_SWAP_LIMIT:-256m', 'EDGE_CPU_LIMIT:-0.50', 'EDGE_PIDS_LIMIT:-64'],
  'realtime-gateway': ['REALTIME_MEMORY_LIMIT:-192m', 'REALTIME_MEMORY_SWAP_LIMIT:-192m', 'REALTIME_CPU_LIMIT:-0.75', 'pids_limit: 128'],
  coturn: ['TURN_MEMORY_LIMIT:-512m', 'TURN_MEMORY_SWAP_LIMIT:-512m', 'TURN_CPU_LIMIT:-1.0', 'TURN_PIDS_LIMIT:-128'],
}
for (const [serviceName, contracts] of Object.entries(resourceBudgetContracts)) {
  const serviceSection = composeServiceSection(serviceName)
  for (const contract of contracts) {
    assert.ok(
      serviceSection.includes(contract),
      `${serviceName} must retain its resource budget contract: ${contract}`,
    )
  }
}
for (const capability of [
  'anonymous_bottles_v1',
  'community_music_v1',
  'moment_music_v1',
  'image_moments_exact_6s_v1',
]) {
  assert.match(apiServerSource, new RegExp(`['\"]${capability}['\"]`))
}
assert.match(
  apiServerSource,
  /signStorageObjectReference:\s*signStoredObjectReference/,
  'data routes must receive the imported storage-signing implementation',
)
assert.match(
  apiServerSource,
  /async function authOptional[\s\S]*?req\.auth = await authenticateClientRequest\(req\)/,
  'optional compatibility auth must validate the server-side session when a token is present',
)
assert.match(
  clientAuthSessionSource,
  /if \(!\(await touchSession\(auth\)\)\)[\s\S]*WEB_SESSION_REVOKED/,
  'compatibility routes must reject revoked sessions',
)
assert.match(apiServerSource, /credentials:\s*true/)
assert.match(apiServerSource, /WEB_JWT_EXPIRES_IN/)
assert.match(apiServerSource, /revokeAllUserSessions/)
for (const requiredCookieAttribute of ['HttpOnly', 'Secure', 'SameSite=None', 'Partitioned']) {
  assert.match(webSessionSecuritySource, new RegExp(requiredCookieAttribute))
}
assert.doesNotMatch(
  webSessionSecuritySource,
  /Domain=/,
  'web session cookies must remain host-bound',
)
assert.match(
  apiServerSource,
  /constantTimeSecretEqual\(provided, adminSyncToken\)/,
  'the administrative synchronization secret must use constant-time comparison',
)
assert.doesNotMatch(
  apiServerSource,
  /createRateLimitMiddleware\(/,
  'sensitive API limits must not be isolated to one Node.js process',
)
assert.match(
  apiServerSource,
  /createDistributedRateLimitMiddleware\([\s\S]*?scope:\s*['"]auth:login['"]/,
  'login attempts must use the shared PostgreSQL limiter',
)
assert.match(
  apiServerSource,
  /CREATE TABLE IF NOT EXISTS fallback_rate_limit_windows/,
  'the shared limiter table must be initialized before the API starts listening',
)
assert.match(
  apiServerSource,
  /withSchemaInitializationLock\([\s\S]*?ensureAuthMetadataColumns\(client\)/,
  'startup DDL must run inside the cross-replica PostgreSQL lock',
)

expectStartupRejected({
  cwd: apiRoot,
  entry: 'src/server.js',
  env: { ...commonApiEnv, CORS_ORIGIN: '*' },
  message: /CORS_ORIGIN must be an explicit trusted HTTPS origin in production/,
})

expectStartupRejected({
  cwd: apiRoot,
  entry: 'src/server.js',
  env: { ...commonApiEnv, CORS_ORIGIN: 'http://app.novvin.invalid' },
  message: /CORS_ORIGIN must be an explicit trusted HTTPS origin in production/,
})

expectStartupRejected({
  cwd: apiRoot,
  entry: 'src/server.js',
  env: {
    ...commonApiEnv,
    CORS_ORIGIN: 'https://app.novvin.invalid',
    PASSWORD_RESET_DELIVERY: 'console',
  },
  message: /PASSWORD_RESET_DELIVERY cannot expose reset tokens in production/,
})

expectStartupRejected({
  cwd: apiRoot,
  entry: 'src/server.js',
  env: {
    ...commonApiEnv,
    CORS_ORIGIN: 'https://app.novvin.invalid',
    PASSWORD_RESET_DELIVERY: 'unknown-provider',
  },
  message: /PASSWORD_RESET_DELIVERY must be disabled, smtp, console or response/,
})

const realtimeSource = fs.readFileSync(
  path.join(repositoryRoot, 'infrastructure', 'local-fallback', 'realtime-gateway', 'src', 'server.js'),
  'utf8',
)
assert.match(realtimeSource, /if \(isProduction\) throw new Error\('JWT_SECRET must be at least 32 characters in production'\)/)

expectStartupRejected({
  cwd: path.join(repositoryRoot, 'signaling-server'),
  entry: 'index.js',
  env: {
    NODE_ENV: 'production',
    FALLBACK_API_URL: 'https://api.novvin.invalid',
    SIGNALING_ALLOWED_ORIGINS: '*',
  },
  message: /SIGNALING_ALLOWED_ORIGINS must list explicit trusted origins in production/,
})

console.log('production configuration fail-closed tests passed')
