const assert = require('assert/strict')
const fs = require('fs')
const path = require('path')

const repositoryRoot = path.resolve(__dirname, '../../../..')
const migrationPath = path.join(repositoryRoot, 'sql/migrations/20260802_coin_purchase_ledger_v5.sql')
const migration = fs.readFileSync(migrationPath, 'utf8')
const config = fs.readFileSync(path.join(repositoryRoot, 'infrastructure/local-fallback/config.example'), 'utf8')

assert.match(migration, /CREATE TABLE IF NOT EXISTS public\.billing_coin_products/)
assert.match(migration, /CREATE TABLE IF NOT EXISTS public\.billing_purchases/)
assert.match(migration, /billing_purchases_token_hash_uidx/)
assert.match(migration, /purchase_token_hash ~ '\^\[0-9a-f\]\{64\}\$'/)
assert.match(migration, /CREATE OR REPLACE FUNCTION public\.billing_capabilities\(\)/)
assert.match(migration, /v_enabled AND v_count BETWEEN 6 AND 8/)
assert.match(migration, /CREATE OR REPLACE FUNCTION public\.credit_verified_google_play_purchase/)
assert.match(migration, /pg_advisory_xact_lock\(hashtextextended\(p_purchase_token_hash, 0\)\)/)
assert.match(migration, /REVOKE ALL ON FUNCTION public\.credit_verified_google_play_purchase[\s\S]+GRANT EXECUTE[\s\S]+TO service_role/)

const packageJson = config.match(/GOOGLE_PLAY_COIN_PACKAGES_JSON=(\{[^\n]+\})/)
assert.ok(packageJson, 'config.example must publish a Google Play package catalog')
const configuredPackages = JSON.parse(packageJson[1])
for (const [productId, coins] of Object.entries(configuredPackages)) {
  assert.match(migration, new RegExp(`'${productId}', ${coins},`))
}
assert.equal(Object.keys(configuredPackages).length, 8)

console.log('coin purchase ledger contract tests passed')
