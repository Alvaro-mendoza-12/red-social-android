'use strict'

const { test } = require('node:test')
const assert = require('node:assert/strict')
const { agreementCommand, createAgreement, changeAgreement, agreementStatus } = require('../src/chat-agreements-domain')
const { directAgreementContext, registerChatAgreementRoutes, projectAgreement } = require('../src/chat-agreements-routes')

const alice = '11111111-1111-4111-8111-111111111111', bob = '22222222-2222-4222-8222-222222222222'
const agreementId = '33333333-3333-4333-8333-333333333333', sourceId = '44444444-4444-4444-8444-444444444444'
const operationId = '55555555-5555-4555-8555-555555555555', chatId = '66666666-6666-4666-8666-666666666666'
const members = [{ id: alice, name: 'Ana' }, { id: bob, name: 'Luis' }]
const proposal = () => createAgreement(agreementCommand({ id: agreementId, operationId, sourceMessageId: sourceId, text: 'Ensayar el sábado' }, true), alice, members, '2026-11-04T08:00:00Z')
const act = (previous, actor, action, extra = {}) => changeAgreement(previous,
  agreementCommand({ operationId, expectedVersion: previous.version, action, ...extra }, false), actor, '2026-11-04T09:00:00Z')

test('a proposal attributes authorship without accepting on either participant behalf', () => {
  const state = proposal()
  assert.equal(state.authorId, alice)
  assert.equal(agreementStatus(state), 'pending')
  assert.deepEqual(state.participants.map(member => member.response), ['pending', 'pending'])
})
test('each participant must explicitly confirm the current text', () => {
  const one = act(proposal(), alice, 'accept')
  assert.equal(agreementStatus(one), 'pending')
  assert.equal(one.participants[1].response, 'pending')
  const both = act(one, bob, 'accept')
  assert.equal(agreementStatus(both), 'accepted')
  assert.equal(both.version, 3)
})
test('editing invalidates all responses and opens a new proposal revision', () => {
  const both = act(act(proposal(), alice, 'accept'), bob, 'accept')
  const next = act(both, alice, 'revise', { text: 'Ensayar el domingo' })
  assert.equal(next.proposalRevision, 2)
  assert.equal(agreementStatus(next), 'pending')
  assert.ok(next.participants.every(member => member.response === 'pending' && member.respondedAt === null))
  assert.throws(() => changeAgreement(next, { expectedVersion: both.version, action: 'accept' }, bob, 'now'), /AGREEMENT_VERSION_CONFLICT/)
})
test('asking for changes and withdrawing remain visible instead of counting as acceptance', () => {
  assert.equal(agreementStatus(act(proposal(), bob, 'changes')), 'changes')
  const both = act(act(proposal(), alice, 'accept'), bob, 'accept')
  assert.equal(agreementStatus(act(both, bob, 'withdraw')), 'withdrawn')
})
test('only the author can revise or cancel, and cancellation cannot be reopened by an old confirmation', () => {
  assert.throws(() => act(proposal(), bob, 'revise', { text: 'Otro texto' }), /AGREEMENT_AUTHOR_REQUIRED/)
  assert.throws(() => act(proposal(), bob, 'cancel'), /AGREEMENT_AUTHOR_REQUIRED/)
  const cancelled = act(proposal(), alice, 'cancel')
  assert.equal(agreementStatus(cancelled), 'cancelled')
  assert.throws(() => act(cancelled, bob, 'accept'), /AGREEMENT_CANCELLED/)
})
test('malformed commands and forged actors or participant lists are rejected', () => {
  const valid = { operationId, expectedVersion: 1, action: 'accept' }
  for (const extra of [{ actorId: bob }, { participants: members }, { text: 'Other' }, { expectedVersion: 1.5 }, { operationId: 'bad' }]) {
    assert.throws(() => agreementCommand({ ...valid, ...extra }, false), /AGREEMENT_INVALID_COMMAND/)
  }
  assert.throws(() => act(proposal(), sourceId, 'accept'), /AGREEMENT_ACCESS_DENIED/)
  assert.throws(() => createAgreement({}, alice, [members[0], members[0]], 'now'), /AGREEMENT_CHAT_UNAVAILABLE/)
})
test('empty or excessively large text fails without silently truncating the agreement', () => {
  for (const text of ['', '  ', 'x'.repeat(1001), null]) {
    assert.throws(() => agreementCommand({ id: agreementId, operationId, sourceMessageId: sourceId, text }, true), /AGREEMENT_TEXT_REQUIRED/)
  }
})
test('an unchanged text or already current decision does not invent another revision', () => {
  const state = proposal(), accepted = act(state, alice, 'accept')
  assert.equal(act(state, alice, 'revise', { text: state.text }), state)
  assert.equal(act(accepted, alice, 'accept'), accepted)
})

function accessFixture(options = {}) {
  const queries = []
  const db = { query: async (sql, params) => {
    queries.push({ sql, params })
    return { rows: sql.includes('fallback_direct_message_request_state')
      ? [{ state: { status: options.policy || 'open' } }]
      : (options.users || members.map(member => ({ id: member.id, display_name: member.name }))) }
  } }
  const dependencies = {
    getCurrentUser: async () => ({ id: alice }), userIdentity: () => ({ displayUserId: alice, ids: new Set([alice]) }),
    privateChatAccessContext: async () => ({ hasAccess: options.hasAccess !== false, chat: { participants: [alice, bob], is_group: options.group || false },
      participantRows: [{ user_id: alice, cleared_at: options.clearedAt }] }),
    lookupMessages: async () => new Map([[sourceId, options.source === undefined ? { id: sourceId, content: 'Contexto', created_at: '2026-11-04T08:00:00Z' } : options.source]]),
  }
  return { db, dependencies, queries }
}
test('a missing membership, group, pending invitation, rejection or block denies the module', async () => {
  for (const options of [{ hasAccess: false }, { group: true }, ...['new', 'blocked', 'pending_incoming', 'pending_outgoing', 'rejected'].map(policy => ({ policy }))]) {
    const fixture = accessFixture(options)
    await assert.rejects(directAgreementContext(fixture.db, {}, chatId, fixture.dependencies), /AGREEMENT_CHAT_UNAVAILABLE/)
  }
})
test('two canonical participants are required and incoming aliases do not create extra consent identities', async () => {
  const fixture = accessFixture({ users: [{ id: 'local', supabase_user_id: alice }, { id: bob }] })
  const context = await directAgreementContext(fixture.db, {}, chatId, fixture.dependencies)
  assert.deepEqual(context.participants.map(member => member.id), [alice, bob])
  const invalid = accessFixture({ users: [{ id: alice }, { id: bob }, { id: sourceId }] })
  await assert.rejects(directAgreementContext(invalid.db, {}, chatId, invalid.dependencies), /AGREEMENT_CHAT_UNAVAILABLE/)
})
test('deleted, view-once and pre-clear sources stay unavailable, without removing the explicitly shared agreement', async () => {
  for (const options of [{ source: null }, { source: { deleted_for_all: true } }, { source: { content_type: 'view_once_image' } }, { clearedAt: '2026-11-04T09:00:00Z' }]) {
    const fixture = accessFixture(options)
    const context = await directAgreementContext(fixture.db, {}, chatId, fixture.dependencies)
    assert.equal(await context.sourceVisible(fixture.db, sourceId), undefined)
    const visible = await projectAgreement(fixture.db, proposal(), context)
    assert.equal(visible.sourceAvailable, false)
    assert.equal(visible.text, 'Ensayar el sábado')
    assert.equal(visible.source, undefined)
  }
})
test('a backend without activation registers capability discovery only', () => {
  const handlers = new Map()
  const app = { get(path, ...middleware) { handlers.set(path, middleware.at(-1)) }, post() { assert.fail('Disabled writes registered') } }
  registerChatAgreementRoutes(app, { authRequired() {} })
  assert.equal(handlers.size, 1)
  const res = { set() { return this }, json(body) { this.body = body; return this } }
  handlers.values().next().value({}, res)
  assert.deepEqual(res.body, { supported: false, schema: 1 })
})
