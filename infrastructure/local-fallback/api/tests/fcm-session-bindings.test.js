'use strict'
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const source = name => fs.readFileSync(path.join(__dirname, '../src', name), 'utf8')

test('FCM registrations bind to the authenticated native session and preserve a later rebind', () => {
  const routes = source('data-routes.js')
  assert.match(routes, /ADD COLUMN IF NOT EXISTS auth_session_id UUID/)
  assert.match(routes, /auth_session_id = EXCLUDED\.auth_session_id/)
  assert.match(routes, /auth_session_id IS NULL OR auth_session_id::text = \$3/)
  assert.match(routes, /req\.auth\?\.client !== 'web' \? validUuid\(req\.auth\?\.sid\) : null/)
})
test('logout removes session-bound tokens and push delivery excludes revoked or expired sessions', () => {
  assert.match(source('server.js'), /DELETE FROM fallback_fcm_tokens WHERE auth_session_id=\$1::uuid/)
  const push = source('push-notifications.js')
  assert.match(push, /session\.id=fcm\.auth_session_id AND session\.revoked_at IS NULL/)
  assert.match(push, /session\.expires_at>NOW\(\)/)
  assert.match(push, /lower\(session\.user_id::text\) IN \(SELECT id FROM recipient_ids/)
})
