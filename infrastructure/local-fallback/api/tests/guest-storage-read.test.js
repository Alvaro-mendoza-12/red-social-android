'use strict'
const test = require('node:test')
const assert = require('node:assert/strict')
const jwt = require('jsonwebtoken')
const { verifiedGuestStorageIdentity } = require('../src/guest-storage-read')
const { createStorageObjectReadGuard } = require('../src/storage-read-access')

const SECRET = 'isolated-guest-storage-unit-test-secret'
const ISSUER = 'conectate-local-fallback'
const ORIGIN = 'https://api.example.invalid'
const NOW = Date.parse('2026-10-13T00:00:00Z')
const GUEST = 'guest_12345678-1234-4321-8765-123456789abc'
const AUTHOR = '11111111-1111-4111-8111-111111111111'
const CONTENT = '22222222-2222-4222-8222-222222222222'
function token(extra = {}, options = {}) {
  return jwt.sign({ sub: GUEST, role: 'guest', iat: Math.floor(NOW / 1000), ...extra }, SECRET,
    { issuer: ISSUER, audience: 'novvin-guests', expiresIn: '1h', ...options })
}
function identity(value, extra = {}) {
  return verifiedGuestStorageIdentity({ headers: { authorization: 'Bearer ' + value }, ...extra },
    { secret: SECRET, issuer: ISSUER, now: NOW })
}
test('signed guest audience is recognized as a public-read identity', () => {
  assert.deepEqual(identity(token()), { id: GUEST })
})
test('member audience cannot be treated as a guest', () => {
  assert.equal(identity(token({}, { audience: 'conectate-clients' })), null)
})
test('wrong role and malformed subject cannot become guest identities', () => {
  assert.equal(identity(token({ role: 'member' })), null)
  assert.equal(identity(token({ sub: AUTHOR })), null)
})
test('expired guest and tampered signature are rejected', () => {
  assert.equal(identity(token({ iat: Math.floor(NOW / 1000) - 7200 })), null)
  const parts = token().split('.')
  parts[1] = Buffer.from(JSON.stringify({ sub: GUEST, role: 'guest', exp: Math.floor(NOW / 1000) + 9999 })).toString('base64url')
  assert.equal(identity(parts.join('.')), null)
})
test('missing expiration and a different issuer are rejected', () => {
  const noExpiry = jwt.sign({ sub: GUEST, role: 'guest' }, SECRET, { issuer: ISSUER, audience: 'novvin-guests' })
  assert.equal(identity(noExpiry), null)
  assert.equal(identity(token({}, { issuer: 'other-service' })), null)
})

function fixture(options = {}) {
  const object = { bucket_id: 'posts', name: AUTHOR + '/photo.png', owner_id: AUTHOR,
    public_url: ORIGIN + '/storage/v1/object/public/posts/' + AUTHOR + '/photo.png', created_at: new Date(NOW - 86400000).toISOString() }
  const post = { id: CONTENT, user_id: AUTHOR, media_url: object.public_url, visibility: 'public', is_sensitive: false, ...options.post }
  const headers = {}
  let released = 0, memberAuthentication = 0
  const db = { async query(sql) {
    if (/^(BEGIN|COMMIT|ROLLBACK|SET LOCAL)/.test(sql)) return { rows: [] }
    if (sql.includes('public.fallback_storage_private_bindings')) return { rows: [] }
    if (sql.includes('fallback_storage_private_history')) return { rows: options.privateHistory ? [{ exists: 1 }] : [] }
    if (sql.includes('public.fallback_storage_content_bindings')) return { rows: [{ source_schema: 'public', source_table: 'fallback_posts', record_id: CONTENT, access_kind: 'post' }] }
    if (sql.includes('FROM public."fallback_posts"')) return { rows: options.missing ? [] : [{ row: post }] }
    if (sql.includes('fallback_storage_content_history')) return { rows: [] }
    if (sql.includes('FROM fallback_users')) return { rows: options.unknownCreator ? [] : [{ id: AUTHOR, supabase_user_id: null, profile: options.profile || {}, override_user_id: AUTHOR }] }
    if (sql.includes('fallback_deleted_identities')) return { rows: [] }
    if (sql.includes('fallback_profile_moderation')) return { rows: options.suspended ? [{ is_suspended: true }] : [] }
    if (sql.includes('fallback_user_blocks') || sql.includes('fallback_follows')) return { rows: [] }
    if (sql.includes('to_regclass')) return { rows: [{ relation: null }] }
    throw Error('Unexpected SQL: ' + sql)
  }, release() { released += 1 } }
  const guard = createStorageObjectReadGuard({ pool: { connect: async () => db }, origins: [ORIGIN], now: () => NOW,
    guestJwtSecret: SECRET, guestJwtIssuer: ISSUER,
    authRequired: async (_req, res) => { memberAuthentication += 1; res.status(401).json({ error: 'AUTH_REQUIRED' }) },
  })
  const response = { setHeader(k, v) { headers[k] = v }, status() { return this }, json() { return this } }
  return { headers, counts: () => ({ released, memberAuthentication }),
    read: (options = {}) => guard({ method: 'GET', headers: options.anonymous ? {} : { authorization: 'Bearer ' + (options.token || token()) }, query: options.query || {} },
      response, { ...object, ...options.object }) }
}
test('guest reads a current public photo without acquiring member privileges', async () => {
  const f = fixture()
  assert.equal((await f.read()).scope, 'guest-content-post')
  assert.equal(f.headers['Cache-Control'], 'private, no-store')
  assert.deepEqual(f.counts(), { released: 1, memberAuthentication: 0 })
})
test('anonymous public URL still requires credentials', async () => {
  const f = fixture()
  assert.equal((await f.read({ anonymous: true })).allowed, false)
  assert.equal(f.counts().memberAuthentication, 1)
})
test('guest cannot read a publication shared from private-message storage', async () => {
  await assert.rejects(fixture({ privateHistory: true }).read(), { status: 404 })
})
test('guest cannot read a private or followers-only publication', async () => {
  for (const visibility of ['private', 'followers']) await assert.rejects(fixture({ post: { visibility } }).read(), { status: 404 })
})
test('guest cannot read a sensitive publication', async () => {
  await assert.rejects(fixture({ post: { is_sensitive: true } }).read(), { status: 404 })
})
test('changed and removed current publications invalidate guest access', async () => {
  await assert.rejects(fixture({ post: { media_url: ORIGIN + '/storage/v1/object/public/posts/another.png' } }).read(), { status: 404 })
  await assert.rejects(fixture({ missing: true }).read(), { status: 404 })
  await assert.rejects(fixture({ post: { is_deleted: true } }).read(), { status: 404 })
})
test('private, hidden, suspended and unknown creators are not public guest grants', async () => {
  for (const options of [{ profile: { is_private: true } }, { profile: { break_mode: 'hidden' } }, { suspended: true }, { unknownCreator: true }])
    await assert.rejects(fixture(options).read(), { status: 404 })
})
test('publication cannot authorize somebody else upload', async () => {
  await assert.rejects(fixture().read({ object: { owner_id: 'unrelated-owner' } }), { status: 404 })
})
test('guest cannot redeem a view-once lease or report evidence', async () => {
  await assert.rejects(fixture().read({ query: { lease: 'one-use-token' } }), { status: 404 })
  await assert.rejects(fixture().read({ object: { bucket_id: 'report-evidence' } }), { status: 404 })
})
