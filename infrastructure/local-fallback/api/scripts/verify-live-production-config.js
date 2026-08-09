const assert = require('assert')
const fs = require('fs')
const jwt = require('jsonwebtoken')
const {
  issueLiveSfuToken,
  liveSfuConfiguration,
} = require('../src/live-routes')

const truthy = value => /^(1|true|yes|on)$/i.test(String(value || '').trim())
const livekit = liveSfuConfiguration()
const guestsBattlesEnabled = truthy(process.env.LIVE_GUESTS_BATTLES_ENABLED)
const billingEnabled = truthy(process.env.LIVE_COIN_PURCHASES_ENABLED)

const errors = []
if (!guestsBattlesEnabled) errors.push('LIVE_GUESTS_BATTLES_ENABLED no está habilitado')
if (!livekit.enabled) errors.push('LIVE_SFU_ENABLED no está habilitado')
if (!/^wss:\/\//i.test(livekit.url)) errors.push('LIVEKIT_URL debe comenzar con wss://')
if (!livekit.apiKey) errors.push('LIVEKIT_API_KEY está vacío')
if (!livekit.apiSecret) errors.push('LIVEKIT_API_SECRET está vacío')

let tokenValidated = false
if (errors.length === 0) {
  const now = Math.floor(Date.now() / 1000)
  const issued = issueLiveSfuToken({
    liveId: '11111111-1111-4111-8111-111111111111',
    userId: '22222222-2222-4222-8222-222222222222',
    displayName: 'config-check',
    role: 'guest',
    configuration: livekit,
    now,
  })
  const claims = jwt.verify(issued.token, livekit.apiSecret, { clockTimestamp: now })
  assert.strictEqual(claims.iss, livekit.apiKey)
  assert.strictEqual(claims.video.room, 'novvin-live-11111111-1111-4111-8111-111111111111')
  assert.strictEqual(claims.video.canPublish, true)
  assert.strictEqual(issued.expiresAt - claims.iat, 600)
  tokenValidated = true
}

const packageCount = (() => {
  try {
    const raw = JSON.parse(String(process.env.GOOGLE_PLAY_COIN_PACKAGES_JSON || '{}'))
    return Object.entries(raw).filter(([, coins]) => Number.isInteger(coins) && coins > 0).length
  } catch (_) {
    return 0
  }
})()
const serviceAccountFile = String(process.env.GOOGLE_PLAY_SERVICE_ACCOUNT_FILE || '').trim()
let serviceAccountJson = String(process.env.GOOGLE_PLAY_SERVICE_ACCOUNT_JSON || '').trim()
if (serviceAccountFile) {
  try {
    serviceAccountJson = fs.readFileSync(serviceAccountFile, 'utf8').trim()
  } catch (_) {
    serviceAccountJson = ''
  }
}
let serviceAccountReady = false
if (serviceAccountJson) {
  try {
    const account = JSON.parse(serviceAccountJson)
    serviceAccountReady = Boolean(account.client_email && account.private_key)
  } catch (_) {
    serviceAccountReady = false
  }
}

console.log(JSON.stringify({
  livekit: {
    guestsBattlesEnabled,
    sfuEnabled: livekit.enabled,
    urlConfigured: Boolean(livekit.url),
    apiKeyConfigured: Boolean(livekit.apiKey),
    apiSecretConfigured: Boolean(livekit.apiSecret),
    tokenValidated,
  },
  billing: {
    enabledFlag: billingEnabled,
    packageCount,
    serviceAccountReady,
    ready: billingEnabled && packageCount === 8 && serviceAccountReady,
  },
}, null, 2))

if (errors.length > 0) {
  console.error(`LiveKit configuration is not ready: ${errors.join('; ')}`)
  process.exitCode = 1
}
