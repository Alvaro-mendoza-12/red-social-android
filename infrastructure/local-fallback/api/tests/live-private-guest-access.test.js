'use strict'
const { test } = require('node:test')
const assert = require('node:assert/strict')
const { canViewStream } = require('../src/live-routes')

const stream = { id: 'live-fixture', host_user_id: 'host', visibility: 'private' }
const identity = { ids: new Set(['guest']), values: ['guest'], displayUserId: 'guest' }
function database({ invitation, blocked = false, banned = false } = {}) {
  return {
    query: async sql => {
      if (sql.includes('fallback_live_bans')) return { rows: banned ? [{}] : [] }
      if (sql.includes('fallback_user_blocks')) return { rows: blocked ? [{ blocker_id: 'guest', blocked_id: 'host' }] : [] }
      if (sql.includes('fallback_live_guest_invitations')) return { rows: invitation ? [{ status: invitation }] : [] }
      throw new Error('Unexpected query in access policy')
    },
  }
}

test('acceptance grants a private LIVE guest access while pending and canceled invitations do not', async () => {
  for (const invitation of [undefined, 'pending', 'accepted', 'rejected', 'canceled', 'expired']) {
    assert.equal(await canViewStream(database({ invitation }), stream, identity), invitation === 'accepted', invitation)
  }
})
test('an accepted private LIVE invitation preserves blocks and bans', async () => {
  assert.equal(await canViewStream(database({ invitation: 'accepted', blocked: true }), stream, identity), false)
  assert.equal(await canViewStream(database({ invitation: 'accepted', banned: true }), stream, identity), false)
})
test('a host retains access to their own private LIVE', async () => {
  assert.equal(await canViewStream(database(), stream, { ...identity, ids: new Set(['host']) }), true)
})

test('an accepted private invitation can use either identity alias of the account', async () => {
  const guest = { ids:new Set(['guest-local','guest-public']),values:['guest-local','guest-public'],displayUserId:'guest-public' }
  const db = database()
  const query = db.query
  db.query = async (sql,params) => {
    if (sql.includes('fallback_live_guest_invitations')) {
      assert.deepEqual(params[1],guest.values)
      return { rows:[{status:'accepted',invitee_user_id:'guest-local'}] }
    }
    return query(sql,params)
  }
  assert.equal(await canViewStream(db,stream,guest),true)
})
