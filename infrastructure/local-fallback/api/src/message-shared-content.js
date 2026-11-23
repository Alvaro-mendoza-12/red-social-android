'use strict'

const contract = require('./contracts/message-shared-content.schema.json')
const { reference, preview, messageFields } = contract.$defs
const sourceKinds = new Set(reference.properties.kind.enum)
const sourceId = new RegExp(reference.properties.id.pattern)
const scopeKinds = { chats: 'chat', groups: 'group', communities: 'community' }
const SHARED_MESSAGE_TYPE = 'shared_content'
const SHARED_MESSAGE_LABEL = 'Contenido compartido'

function isSharedMessageType(value) {
  return typeof value === 'string' && value.trim().toLowerCase() === SHARED_MESSAGE_TYPE
}

function includesSharedMessageFields(value) {
  return Object.hasOwn(value || {}, 'shared_content') || Object.hasOwn(value || {}, 'sharedContent') || Object.hasOwn(value || {}, 'sharedNote') ||
    isSharedMessageType(value?.content_type) || isSharedMessageType(value?.contentType)
}

function parseSharedReference(value) {
  if (!value || typeof value !== 'object' || Array.isArray(value) ||
      Object.keys(value).length !== 2 || !Object.hasOwn(value, 'kind') || !Object.hasOwn(value, 'id') ||
      !sourceKinds.has(value.kind) || typeof value.id !== 'string' || !sourceId.test(value.id)) return null
  return { kind: value.kind, id: value.id.toLowerCase() }
}

function parseSharedMessage(body, requestedId) {
  const hasReference = Object.hasOwn(body || {}, 'sharedContent')
  const hasInternalField = Object.hasOwn(body || {}, 'shared_content')
  if (!includesSharedMessageFields(body)) return null
  const reference = parseSharedReference(body?.sharedContent)
  const note = body?.sharedNote ?? body?.content
  if (!hasReference || !reference || !requestedId || hasInternalField || Object.hasOwn(body, 'content_type') ||
      (Object.hasOwn(body, 'sharedNote') && (typeof body.sharedNote !== 'string' || Object.hasOwn(body, 'content'))) ||
      (body.contentType != null && !isSharedMessageType(body.contentType)) ||
      (note != null && (typeof note !== 'string' || note.length > messageFields.properties.content.maxLength)) ||
      ['mediaUrl', 'thumbnailUrl', 'thumbnail_url', 'mediaSize', 'pollId', 'replyToId',
        'isForwarded', 'is_forwarded', 'forwardedFromMessageId', 'forwarded_from_message_id',
        'forwardedFromUserId', 'forwarded_from_user_id'].some(key => body[key])) {
    throw Object.assign(new Error('SHARED_CONTENT_INVALID'), { status: 400 })
  }
  return { reference, note: (note || '').trim() }
}

function withoutStoredReference(row) {
  if (!row || (!Object.hasOwn(row, 'shared_content') && !Object.hasOwn(row, 'sharedContent'))) return row
  const { shared_content, sharedContent, ...visible } = row
  return visible
}

function postSharePreview(post, textOf, boolOf) {
  const id = textOf(post, ['id'], '')
  if (!sourceId.test(id)) return null
  return {
    reference: { kind: 'post', id },
    title: 'Publicación de Novvin',
    summary: boolOf(post, ['is_sensitive', 'isSensitive'], false) ? 'Contenido sensible' :
      textOf(post, ['content', 'text'], '').slice(0, preview.properties.summary.maxLength),
  }
}

function registerSharedMessageRead(app, { authRequired, validUuid, getCurrentUser, loadMessage, resolveSource }) {
  app.get('/v1/data/messages/shared-content/capabilities', authRequired, (_req, res) => {
    res.set('Cache-Control', 'private, no-store').json({ version: 1, kinds: [...sourceKinds] })
  })
  app.get('/v1/data/messages/:collection/:scopeId/messages/:messageId/shared-content', authRequired, async (req, res, next) => {
    res.set('Cache-Control', 'private, no-store')
    const unavailable = () => res.status(404).json({ error: 'SHARED_CONTENT_NOT_AVAILABLE' })
    try {
      const collection = req.params.collection
      const kind = Object.hasOwn(scopeKinds, collection) ? scopeKinds[collection] : null
      const scopeId = String(req.params.scopeId || '').toLowerCase()
      const messageId = validUuid(req.params.messageId)
      if (!kind || !sourceId.test(scopeId) || !messageId) return unavailable()
      const user = await getCurrentUser(req)
      const message = user && await loadMessage(user, kind, scopeId, messageId)
      const reference = isSharedMessageType(message?.content_type) ? parseSharedReference(message.shared_content) : null
      const preview = reference && await resolveSource(user, reference)
      if (!preview) return unavailable()
      return res.json(preview)
    } catch (error) { return next(error) }
  })
}

module.exports = { SHARED_MESSAGE_TYPE, SHARED_MESSAGE_LABEL, isSharedMessageType, parseSharedMessage,
  includesSharedMessageFields, postSharePreview, registerSharedMessageRead, withoutStoredReference }