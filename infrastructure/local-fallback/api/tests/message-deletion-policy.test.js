'use strict'
const test = require('node:test'), assert = require('node:assert/strict')
const policy = require('../src/message-deletion-policy')
const { normalizeMessageRow, previewFromMessage } = require('../src/data-routes')
const { buildData } = require('../src/push-notifications')
const scope = { kind: 'chat', scopeId: 'chat', messageId: 'message' }
const old = { id: 'message', chat_id: 'chat', sender_id: 'sender', content: 'old-private-text', text: 'old-private-text',
  media_url: 'https://example.test/private-media', thumbnail_url: 'https://example.test/private-thumbnail',
  metadata: { quote: 'old-private-text' }, reply_to: { content: 'old-private-text' }, poll_id: 'poll',
  reactions: { emoji: ['sender'] }, created_at: '2026-10-10T01:00:00Z', updated_at: '2026-10-11T01:00:00Z' }

test('all message and preview projections redact every deleted payload alias', () => {
  for (const deleted of [{ deleted_for_all: true }, { deletedForAll: 'true' }, { status: 'deleted' }]) {
    const row = policy.redactDeletedMessage({ ...old, ...deleted })
    assert.equal(JSON.stringify(row).includes('old-private'), false)
    assert.equal(JSON.stringify(row).includes('private-media'), false)
    assert.equal(row.deleted_for_all, true)
    assert.equal(row.id, old.id)
    assert.equal(row.created_at, old.created_at)
    assert.equal(row.content, '')
    assert.equal(row.media_url, null)
    assert.equal(previewFromMessage({ ...old, ...deleted }), 'Mensaje eliminado')
    assert.equal(normalizeMessageRow({ ...old, ...deleted }).content, '')
  }
})

test('tombstones dominate imports, repeated sends and equal timestamps in either merge order', () => {
  const deleted = { ...old, content: '', deleted_for_all: true }
  for (const rows of [[old, deleted], [deleted, old], [old, deleted, { ...old, deleted_for_all: false }]]) {
    const merged = policy.mergeMessageRows(rows)
    assert.equal(merged.length, 1)
    assert.equal(merged[0].deleted_for_all, true)
    assert.equal(merged[0].content, '')
    assert.equal(merged[0].metadata, undefined)
  }
})

test('deletion push allowlist is silent and recipient comes from the notification', () => {
  const data = buildData({ type: 'message_deleted', target_type: 'community', target_id: 'community',
    recipient_id: 'account', content: old.content, actor_name: old.content }, {
    message_id: 'message', community_id: 'community', channel_id: 'channel',
    message_created_at: old.created_at, deleted_at: old.updated_at,
    recipient_id: 'other-account', media_url: old.media_url, body: old.content, content: old.content,
  })
  assert.equal(data.recipient_id, 'account')
  assert.equal(data.chat_id, 'community')
  assert.equal(data.target_type, 'community')
  assert.equal(data.message_created_at, '2026-10-10T01:00:00.000Z')
  assert.equal(data.deleted_for_all, 'true')
  for (const key of ['body','content','sender_name','media_url','thumbnail_url','notification']) assert.equal(data[key], undefined)
  assert.equal(JSON.stringify(data).includes('old-private'), false)
})

test('message copy alerts and scoped payloads cannot bypass dispatch validation', () => {
  for (const type of ['message','new_message','direct_message','group_message','community_message']) {
    assert.deepEqual(policy.notificationMessageScope({ type, target_type: 'chat', target_id: 'chat' }, { message_id: 'message' }), scope)
  }
  assert.deepEqual(policy.notificationMessageScope({ type: 'activity', target_type: 'chat', target_id: 'chat' }, { message_id: 'message' }), scope)
  assert.equal(policy.notificationMessageScope({ type: 'message', target_id: 'chat' }, {}), null)
  assert.equal(policy.notificationMessageScope({ type: 'message', target_id: 'chat' }, { chat_id: 'other',message_id: 'message' }), null)
})

function fakePool(current) {
  const statements = []
  const client = { query: async sql => {
    statements.push(sql)
    if (sql.includes('FROM fallback_messages')) return { rows: current ? [current] : [] }
    if (sql.includes('FROM fallback_compat_rows')) return { rows: [] }
    if (sql.includes('FROM fallback_users')) return { rows: [{ id: 'sender' }] }
    return { rows: [] }
  }, release() { statements.push('RELEASE') } }
  return { pool: { connect: async () => client }, client, statements }
}

test('queued realtime/push dispatch after deletion is suppressed before invoking provider', async () => {
  for (const current of [null, { ...old, deleted_for_all: true }]) {
    const { pool, statements } = fakePool(current)
    const result = await policy.withCurrentMessage(pool, scope, () => assert.fail('provider must not be called'))
    assert.equal(result.suppressed, true)
    assert.ok(statements.some(sql => sql.includes('FOR SHARE')))
    assert.ok(statements.includes('COMMIT'))
    assert.equal(statements.at(-1), 'RELEASE')
  }
})

test('current row is authoritative and connection is released on provider failure', async () => {
  const { pool, client, statements } = fakePool({ ...old, content: 'current' })
  await assert.rejects(policy.withCurrentMessage(pool, scope, (current, db) => {
    assert.equal(current.content, 'current'); assert.equal(db, client); throw Error('synthetic-provider-failure')
  }, { actorId: 'sender' }), /synthetic-provider-failure/)
  assert.ok(statements.includes('ROLLBACK'))
  assert.equal(statements.at(-1), 'RELEASE')
})

test('a false deletion cancellation is suppressed until a persisted tombstone exists', async () => {
  const { pool } = fakePool(old)
  assert.equal((await policy.withCurrentMessage(pool, scope, () => assert.fail('false cancellation'), { allowDeleted: true })).suppressed, true)
})

test('public notification routes reserve deletion events at every supported wrapper', () => {
  const { isServerManagedNotification } = require('../src/notification-event-policy')
  for (const row of [{ type: 'message_deleted' }, { extra_data: { type: 'message_deleted' } },
    { extraData: { notification_type: 'message_deleted' } }, { data: '{"type":"message_deleted"}' }]) {
    assert.equal(isServerManagedNotification(row), true)
  }
})

test('stored notification filter preserves live/other types and checks 100 scopes in two bounded queries', async () => {
  const deleted={id:'deleted-notice',type:'message',target_type:'chat',target_id:'chat',content:old.content,
    data:{message_id:'deleted-message',chat_id:'forged-extra-chat'}}
  const live=Array.from({length:100},(_,index)=>({id:'live-'+index,type:'message',target_type:'chat',target_id:'chat',content:'live',
    extra_data:{message_id:'live-message-'+index}}))
  const other={id:'follow',type:'follow',target_type:'profile',target_id:'profile',content:'follow stays'}
  const absent={id:'missing-message',type:'message',target_type:'chat',target_id:'chat',content:'missing stays',data:{message_id:'absent'}}
  const queries=[]
  const filtered=await policy.filterDeletedMessageNotifications({query:async(sql,params)=>{
    queries.push({sql,params})
    return {rows:sql.includes('information_schema')?[]:[{kind:'chat',scope_id:'chat',message_id:'deleted-message'}]}
  }},[deleted,...live,other,absent])
  assert.equal(queries.length,2)
  assert.equal(JSON.parse(queries[1].params[0]).length,102)
  assert.equal(filtered.length,102)
  assert.ok(filtered.includes(other));assert.ok(filtered.includes(absent));assert.ok(live.every(row=>filtered.includes(row)))
  assert.equal(filtered.includes(deleted),false)
  assert.ok(queries[1].sql.includes('fallback_compat_rows'))
  assert.ok(queries[1].sql.includes('fallback_room_messages'))
})

test('deleted placeholders permit own hide and owner recall patches, but cannot restore payload',()=>{
  const identity={ids:new Set(['actor'])},row={deleted_for_all:true,deleted_by:['previous']}
  assert.equal(policy.isRecallPatch({deleted_for_all:true}),true)
  assert.equal(policy.isRecallPatch({deleted_for_all:false,content:'old'}),false)
  assert.equal(policy.isRecallPatch({deleted_for_all:true,media_url:'old'}),false)
  assert.equal(policy.isHidePatch(row,{deleted_by:['actor']},identity),true)
  assert.equal(policy.isHidePatch(row,{deleted_by:['actor','previous']},identity),true)
  assert.equal(policy.isHidePatch(row,{deleted_by:['actor','other']},identity),false)
  assert.equal(policy.isHidePatch(row,{deleted_by:['actor'],content:'old'},identity),false)
})
