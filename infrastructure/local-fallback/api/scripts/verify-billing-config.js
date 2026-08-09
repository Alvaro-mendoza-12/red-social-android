const fs = require('fs')
const { _test } = require('../src/postgrest-compat-routes')

const configuration = _test.googlePlayConfiguration()
const filePath = String(process.env.GOOGLE_PLAY_SERVICE_ACCOUNT_FILE || '').trim()
const fileExists = Boolean(filePath && fs.existsSync(filePath))
const serviceAccountReady = Boolean(
  configuration.serviceAccount?.client_email && configuration.serviceAccount?.private_key,
)
const packageCount = Object.keys(configuration.packages || {}).length
const catalogReady = packageCount === 8
const billingFlagEnabled = ['1', 'true', 'yes', 'on'].includes(
  String(process.env.LIVE_COIN_PURCHASES_ENABLED || '').trim().toLowerCase(),
)
const report = {
  billingFlagEnabled,
  serviceAccountFileConfigured: Boolean(filePath),
  serviceAccountFileExists: fileExists,
  serviceAccountReady,
  packageName: configuration.packageName,
  packageCount,
  catalogReady,
  readyToEnable: serviceAccountReady && catalogReady && Boolean(configuration.packageName),
}

console.log(JSON.stringify(report, null, 2))
if (billingFlagEnabled && (!serviceAccountReady || !catalogReady || !configuration.packageName)) process.exitCode = 1
