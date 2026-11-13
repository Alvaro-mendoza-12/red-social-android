'use strict'
const { test } = require('node:test')
const assert = require('node:assert/strict')
const { invitableLiveAccount, liveAccountIdentity, hasLiveHostBlock } = require('../src/live-invitation-access')

const hostIdentity = { ids: new Set(['host-local','host-public']), values: ['host-local','host-public'] }
const target = { id: 'guest-local', supabase_user_id: 'guest-public' }
function fixture({ users = [target], blocks = [], suspended = null, banned = false } = {}) {
  const queries = []
  const db = { query: async (sql,params) => {
    queries.push({ sql,params })
    if (sql.includes('fallback_profile_moderation')) return { rows: suspended ? [suspended] : [] }
    if (sql.includes('fallback_user_blocks')) return { rows: blocks }
    if (sql.includes('FROM fallback_users')) return { rows: users.filter(user => [user.id,user.supabase_user_id].includes(params[0])) }
    throw Error('Unexpected account policy query')
  } }
  return { db, queries, resolve: id => invitableLiveAccount(db, {
    hostIdentity, liveId: 'live-fixture', targetId: id,
    isBanned: async (_db,liveId,identity) => {
      assert.equal(liveId,'live-fixture')
      assert.deepEqual(identity.values,['guest-local','guest-public'])
      return banned
    },
  }) }
}

test('local and historical IDs resolve to one canonical live invitee', async () => {
  const f = fixture()
  for (const id of ['guest-local',' GUEST-PUBLIC ']) {
    const identity = await f.resolve(id)
    assert.equal(identity.displayUserId,'guest-public')
    assert.deepEqual(identity.values,['guest-local','guest-public'])
  }
})

test('unknown, ambiguous and oversized identities are not invitation targets', async () => {
  const f = fixture()
  assert.equal(await f.resolve('missing'),null)
  assert.equal(await f.resolve('x'.repeat(161)),null)
  assert.equal(await liveAccountIdentity(fixture({users:[target,{id:'other',supabase_user_id:'guest-public'}]}).db,'guest-public'),null)
})

test('another alias of the host cannot invite the host to their own LIVE', async () => {
  assert.equal(await fixture({users:[{id:'host-local',supabase_user_id:'host-public'}]}).resolve('host-public'),null)
})

test('both block directions and every local/public identity combination reject the invite', async () => {
  for (const host of ['host-local','host-public']) for (const guest of ['guest-local','guest-public']) {
    for (const reverse of [false,true]) {
      const blocks = [{ blocker_id:reverse ? guest : host, blocked_id:reverse ? host : guest }]
      assert.equal(await fixture({ blocks }).resolve(guest),null)
    }
  }
})

test('suspension and LIVE bans reject targets without revealing their moderation record', async () => {
  assert.equal(await fixture({suspended:{is_suspended:true}}).resolve('guest-local'),null)
  assert.equal(await fixture({banned:true}).resolve('guest-local'),null)
  const expired = {is_suspended:true,suspended_until:'2000-01-01T00:00:00Z'}
  assert.equal((await fixture({suspended:expired}).resolve('guest-local')).displayUserId,'guest-public')
})

test('private LIVE blocking resolves the hosts historical alias in either direction', async () => {
  const guest = { ids:new Set(['guest-local','guest-public']) }
  for (const reverse of [false,true]) {
    const f = fixture({users:[{id:'host-local',supabase_user_id:'host-public'}],blocks:[{
      blocker_id:reverse ? 'host-local' : 'guest-local',blocked_id:reverse ? 'guest-local' : 'host-local',
    }]})
    assert.equal(await hasLiveHostBlock(f.db,'host-public',guest),true)
  }
})

test('unrelated blocks allow the existing host and missing identities fail closed', async () => {
  const guest = { ids:new Set(['guest-local','guest-public']) }
  const blocks=[{blocker_id:'guest-local',blocked_id:'someone-else'}]
  assert.equal(await hasLiveHostBlock(fixture({users:[{id:'host-local',supabase_user_id:'host-public'}],blocks}).db,'host-public',guest),false)
  assert.equal(await hasLiveHostBlock(fixture({users:[],blocks}).db,'missing-host',guest),true)
})
