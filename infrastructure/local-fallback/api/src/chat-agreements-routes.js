'use strict'

const { agreementCommand, agreementId: id, agreementStatus, agreementFail: fail } = require('./chat-agreements-domain')
const { writeAgreement } = require('./chat-agreements-store')
const { agreementPageQuery, readAgreementPage } = require('./chat-agreements-pages')

/** Access is intentionally stricter than the ability to preview a room or a marker. */
async function directAgreementContext(db, req, chatId, dependencies) {
  const { getCurrentUser, userIdentity, privateChatAccessContext, lookupMessages } = dependencies
  const user = await getCurrentUser(db, req)
  if (!user) throw fail('AGREEMENT_ACCESS_DENIED', 403)
  const identity = userIdentity(user)
  const access = await privateChatAccessContext(db, chatId, identity)
  if (!access.chat || !access.hasAccess || ['true', '1'].includes(String(access.chat.is_group ?? access.chat.isGroup).toLowerCase())) {
    throw fail('AGREEMENT_CHAT_UNAVAILABLE', 403)
  }
  // This function also checks both directions of blocks and the invitation policy.
  const policy = (await db.query('SELECT fallback_direct_message_request_state($1,$2) AS state', [chatId, identity.displayUserId])).rows[0]?.state
  if (policy?.status !== 'open') throw fail('AGREEMENT_CHAT_UNAVAILABLE', 403)
  const rawIds = [...new Set([...(access.chat.participants || []), ...access.participantRows.map(row => row.user_id || row.profile_id)]
    .map(value => String(value || '').trim().toLowerCase()).filter(Boolean))]
  const users = (await db.query(`SELECT id::text,supabase_user_id::text,display_name,to_jsonb(fallback_users)->>'username' AS username FROM fallback_users
    WHERE lower(id::text)=ANY($1::text[]) OR lower(COALESCE(supabase_user_id::text,''))=ANY($1::text[])
    FOR KEY SHARE`, [rawIds])).rows
  const participants = users.map(member => ({ id: String(member.supabase_user_id || member.id).toLowerCase(),
    name: String(member.display_name || member.username || 'Usuario').slice(0, 80) }))
  const actorId = identity.displayUserId
  if (participants.length !== 2 || new Set(participants.map(member => member.id)).size !== 2 || !participants.some(member => member.id === actorId)) {
    throw fail('AGREEMENT_CHAT_UNAVAILABLE', 403)
  }
  const clearedAt = Math.max(0, ...access.participantRows.filter(row => identity.ids.has(String(row.user_id || row.profile_id || '').toLowerCase()))
    .map(row => Date.parse(row.cleared_at || row.clearedAt || '') || 0))
  const sourcesVisible = async (sourceDb, messageIds) => {
    const messages = await lookupMessages(sourceDb, 'chat', chatId, messageIds, identity)
    return new Map([...messages].filter(([, message]) => message && !message.deleted_for_all &&
      !String(message.content_type || '').startsWith('view_once_') &&
      (!clearedAt || Date.parse(message.created_at) > clearedAt)))
  }
  return { actorId, participants, sourcesVisible,
    sourceVisible: async (sourceDb, messageId) => (await sourcesVisible(sourceDb, [messageId])).get(messageId) }
}

async function projectAgreement(db, state, context, sources = null) {
  if (!state.participants.every(member => context.participants.some(current => current.id === member.id))) throw fail('AGREEMENT_ACCESS_DENIED', 403)
  const source = sources ? sources.get(state.sourceMessageId) : await context.sourceVisible(db, state.sourceMessageId)
  return { ...state, archived: Boolean(state.archived), status: agreementStatus(state), sourceAvailable: Boolean(source),
    participants: state.participants.map(member => ({ ...member, name: context.participants.find(current => current.id === member.id)?.name || 'Usuario' })) }
}

function registerChatAgreementRoutes(app, dependencies) {
  const { pool, authRequired, enabled = false, publish = async () => {} } = dependencies
  app.get('/v1/data/messages/agreements/capabilities', authRequired, (_req, res) => {
    res.set('Cache-Control', 'no-store').json({ supported: enabled, schema: 1, ...(enabled ? { archive: true, pagination: true } : {}) })
  })
  if (!enabled) return
  const base = '/v1/data/messages/chats/:chatId/agreements'
  const target = req => {
    const chatId = id(req.params.chatId)
    if (!chatId) throw fail('AGREEMENT_INVALID_COMMAND')
    return chatId
  }
  const handle = fn => async (req, res, next) => {
    res.set('Cache-Control', 'no-store')
    try { await fn(req, res) } catch (error) {
      if (error.status && String(error.message).startsWith('AGREEMENT_')) res.status(error.status).json({ error: error.message })
      else next(error)
    }
  }
  app.get(base, authRequired, handle(async (req, res) => {
    const chatId = target(req)
    const query = agreementPageQuery(req.query, chatId)
    const context = await directAgreementContext(pool, req, chatId, dependencies)
    const page = await readAgreementPage(pool, chatId, query)
    const ids = [...new Set([...page.states, ...(page.focus ? [page.focus] : [])].map(state => state.sourceMessageId))]
    const sources = new Map()
    for (let offset = 0; offset < ids.length; offset += 50) {
      for (const [key, message] of await context.sourcesVisible(pool, ids.slice(offset, offset + 50))) sources.set(key, message)
    }
    const agreements = []
    for (const state of page.states) agreements.push(await projectAgreement(pool, state, context, sources))
    res.json({ agreements, focus: page.focus ? await projectAgreement(pool, page.focus, context, sources) : null,
      focusMissing: page.focusMissing, cursor: page.cursor, nextCursor: page.nextCursor, previousCursor: page.previousCursor,
      archived: page.archived, reset: page.reset })
  }))
  app.get(`${base}/:agreementId/source`, authRequired, handle(async (req, res) => {
    const chatId = target(req), agreementId = id(req.params.agreementId)
    if (!agreementId) throw fail('AGREEMENT_INVALID_COMMAND')
    const context = await directAgreementContext(pool, req, chatId, dependencies)
    const row = (await pool.query('SELECT state FROM experimental_chat_agreements WHERE chat_id=$1 AND id=$2::uuid', [chatId, agreementId])).rows[0]
    if (!row) throw fail('AGREEMENT_NOT_FOUND', 404)
    if (!row.state.participants.some(member => member.id === context.actorId)) throw fail('AGREEMENT_ACCESS_DENIED', 403)
    const source = await context.sourceVisible(pool, row.state.sourceMessageId)
    // The preview is read under current access and never copied into the agreement.
    res.json({ source: source ? { id: source.id, content: String(source.content || '').slice(0, 300) } : null })
  }))
  async function write(req, res, creating) {
    const chatId = target(req)
    const command = agreementCommand(req.body, creating)
    const agreementId = creating ? command.id : id(req.params.agreementId)
    if (!agreementId) throw fail('AGREEMENT_INVALID_COMMAND')
    const result = await writeAgreement(pool, { chatId, agreementId, command, creating,
      authorize: db => directAgreementContext(db, req, chatId, dependencies) })
    // Re-authorize the response after the commit. Do not convert a persisted write
    // into a second write just because response hydration or publication fails.
    const context = await directAgreementContext(pool, req, chatId, dependencies)
    const agreement = await projectAgreement(pool, result.state, context)
    for (const participant of context.participants) {
      void Promise.resolve().then(() => publish(`user:${participant.id}`, { event: 'chat_agreement', chatId, agreementId }))
        .catch(() => console.warn('[agreement] change publication failed'))
    }
    res.status(creating && !result.replayed ? 201 : 200).json({ agreement })
  }
  app.post(base, authRequired, handle((req, res) => write(req, res, true)))
  app.post(`${base}/:agreementId/actions`, authRequired, handle((req, res) => write(req, res, false)))
}

module.exports = { registerChatAgreementRoutes, directAgreementContext, projectAgreement }
