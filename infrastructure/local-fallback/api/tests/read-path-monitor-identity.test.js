'use strict'
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

test('diagnostic selection prefers real accounts over newly used disposable fixtures', () => {
  const source = fs.readFileSync(path.join(__dirname, '../scripts/verify-social-read-paths.js'), 'utf8')
  assert.ok(source.includes("ORDER BY CASE WHEN email LIKE '%@example.invalid' THEN 1 ELSE 0 END,"))
  assert.ok(source.includes('last_login_at DESC NULLS LAST, created_at ASC'))
  assert.ok(source.includes('process.env.SMOKE_USER_ID'))
})
test('monitor uses canonical API origin before a rotating legacy tunnel', () => {
  const source = fs.readFileSync(path.join(__dirname, '../../scripts/monitor-public-read-paths.sh'), 'utf8')
  assert.ok(source.includes('PUBLIC_MONITOR_BASE_URL'))
  assert.ok(source.indexOf('docker compose config --format json') < source.indexOf('tr -d'))
  assert.ok(source.includes('.get("PUBLIC_BASE_URL", "")'))
  assert.ok(source.includes('SMOKE_REQUIRE_CONTENT=true'))
})
