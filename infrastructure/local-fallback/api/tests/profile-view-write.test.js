const test = require('node:test')
const assert = require('node:assert/strict')
const { _test: { prepareInsertRow } } = require('../src/postgrest-compat-routes')

const actor = '11111111-1111-4111-8111-111111111111'
const publicActor = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'
const target = '22222222-2222-4222-8222-222222222222'
const identity = { localUserId: actor, preferredUserId: publicActor,
  ids: new Set([actor, publicActor]), values: [actor, publicActor] }

function poolFor(preferences = {}) {
  return { async query(sql, params) {
    if (sql.includes('to_regclass')) return { rows: [{ oid: null }] }
    if (sql.includes('AS local_user_id')) {
      assert.equal(params[0], actor, 'nested reads must receive the local token subject')
      return { rows: [{ local_user_id: actor, public_user_id: publicActor, display_name: 'QA' }] }
    }
    if (sql.includes('FROM fallback_compat_rows')) return { rows: [
      { row: { id: publicActor, profile_view_history_enabled: preferences.viewer } },
      { row: { id: target, profile_view_history_enabled: preferences.target } },
    ] }
    if (sql.includes('FROM fallback_users') && sql.includes('ORDER BY created_at')) return { rows: [
      { id: actor, supabase_user_id: publicActor, display_name: 'QA' },
      { id: target, display_name: 'QA target' },
    ] }
    if (sql.includes('fallback_profile_moderation')) return { rows: [] }
    assert.fail('Unexpected profile visit query')
  } }
}

test('valid profile visits authenticate nested reads and use the public actor ID', async () => {
  const result = await prepareInsertRow(poolFor(), 'profile_views', identity,
    { profile_id: target, viewer_id: actor })
  assert.equal(result.profile_id, target)
  assert.equal(result.viewer_id, publicActor)
})

for (const disabled of ['viewer', 'target']) {
  test(`profile visits preserve the ${disabled} privacy opt-out`, async () => {
    await assert.rejects(prepareInsertRow(poolFor({ [disabled]: false }), 'profile_views', identity,
      { profile_id: target, viewer_id: actor }),
    error => error.status === 403 && error.message === 'PROFILE_VIEW_HISTORY_DISABLED')
  })
}

test('profile visits reject a forged viewer before querying profiles', async () => {
  await assert.rejects(prepareInsertRow({ query() { assert.fail('forged actor queried data') } },
    'profile_views', identity, { profile_id: target, viewer_id: target }),
  error => error.message === 'ACTOR_ID_MISMATCH')
})

test('profile visits reject an own profile', async () => {
  await assert.rejects(prepareInsertRow(poolFor(), 'profile_views', identity,
    { profile_id: publicActor, viewer_id: actor }),
  error => error.status === 400 && error.message === 'PROFILE_VIEW_TARGET_INVALID')
})
