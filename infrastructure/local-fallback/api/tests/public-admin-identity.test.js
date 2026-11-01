const assert = require('node:assert/strict')
const test = require('node:test')
const { adminAccessFromStoredRow } = require('../src/admin-moderation-routes')
const { enrichVideo, mapVideoComment, normalizeProfileForPublic, profilePatchFromBody, fallbackUserProfileRows } = require('../src/data-routes')

test('an editable display name cannot grant the public admin identity', () => {
  for (const name of ['Novvin', 'Alvaro', 'Administrador']) {
    assert.equal(adminAccessFromStoredRow({ display_name: name, username: name }).level, 'none')
    assert.equal(normalizeProfileForPublic({ id: 'ordinary-author', display_name: name }).is_admin, false)
  }
})

test('public identity and permissions evaluate the same stored role and its revocation', () => {
  assert.equal(adminAccessFromStoredRow({ is_admin: true, admin_access_level: 'write' }).level, 'write')
  assert.equal(adminAccessFromStoredRow({ is_admin: true, admin_access_level: 'none' }).level, 'none')
  assert.equal(adminAccessFromStoredRow({ is_admin: false, admin_is_owner: true }).level, 'full')
})

test('a profile edit cannot assign administrative or catalog badges', () => {
  const patch = profilePatchFromBody({ display_name: 'Novvin', is_admin: true, isAdmin: true,
    admin_access_level: 'full', admin_is_owner: true, badges: [{ name: 'admin', is_admin_badge: true }] })
  assert.deepEqual(patch, { display_name: 'Novvin', full_name: 'Novvin' })
})

test('video and comment identities come from their author profile', () => {
  const author = normalizeProfileForPublic({ id: 'role-author', display_name: 'María', is_admin: true, is_verified: true })
  const profiles = new Map([[author.id, author]])
  const video = enrichVideo({ id: 'v', user_id: author.id }, profiles, new Map(), new Set())
  const comment = mapVideoComment({ id: 'c', video_id: 'v', user_id: author.id }, profiles)
  assert.equal(video.is_admin, true)
  assert.equal(video.is_verified, true)
  assert.equal(comment.is_admin, true)
  assert.equal(comment.is_verified, true)
})

test('video metadata and a reserved-looking name cannot impersonate an administrator', () => {
  const raw = { id: 'ordinary', user_id: 'unprivileged', user_name: 'Novvin', is_admin: true, is_verified: true }
  const profiles = new Map([['unprivileged', { id: 'unprivileged', is_admin: false, is_verified: false }]])
  for (const profileMap of [profiles, new Map()]) {
    assert.equal(enrichVideo(raw, profileMap, new Map(), new Set()).is_admin, false)
    assert.equal(enrichVideo(raw, profileMap, new Map(), new Set()).is_verified, false)
    assert.equal(mapVideoComment(raw, profileMap).is_admin, false)
  }
})

test('batch profile reads use moderation columns, never editable overrides, and omit internal role data', async () => {
  let reads = 0
  const pool = { query: async (sql) => {
    reads++
    assert.match(sql, /LEFT JOIN LATERAL[\s\S]*FROM fallback_profile_moderation/)
    return { rows: [
      { id: 'public-role-a101', is_admin: true, display_name: 'Novvin', __admin_email: 'regular@example.test',
        __admin_flag: false, __admin_level: 'none', __admin_owner: false },
      { id: 'public-role-b101', display_name: 'María', __admin_email: 'member@example.test',
        __admin_flag: true, __admin_level: 'write', __admin_owner: false },
    ] }
  } }
  const profiles = await fallbackUserProfileRows(pool, 10, ['public-role-a101', 'public-role-b101'])
  assert.equal(reads, 1)
  assert.equal(profiles[0].is_admin, false)
  assert.equal(profiles[1].is_admin, true)
  assert.ok(profiles.every(profile => !Object.keys(profile).some(key => key.startsWith('__admin'))))
})
