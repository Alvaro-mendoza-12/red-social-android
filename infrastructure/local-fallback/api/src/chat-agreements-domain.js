'use strict'

const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i
const fail = (code, status = 400) => Object.assign(new Error(code), { status })
const id = value => typeof value === 'string' && UUID.test(value) ? value.toLowerCase() : null

function agreementCommand(body, creating) {
  const keys = creating ? ['operationId', 'id', 'sourceMessageId', 'text'] : ['operationId', 'expectedVersion', 'action', 'text']
  if (!body || typeof body !== 'object' || Array.isArray(body) || Object.keys(body).some(key => !keys.includes(key)) || !id(body.operationId)) {
    throw fail('AGREEMENT_INVALID_COMMAND')
  }
  if (creating) {
    if (!id(body.id) || !id(body.sourceMessageId)) throw fail('AGREEMENT_INVALID_COMMAND')
    return { operationId: id(body.operationId), id: id(body.id), sourceMessageId: id(body.sourceMessageId), text: agreementText(body.text) }
  }
  if (!Number.isSafeInteger(body.expectedVersion) || body.expectedVersion < 1 ||
      !['accept', 'changes', 'withdraw', 'revise', 'cancel', 'archive', 'restore'].includes(body.action) ||
      (body.action !== 'revise' && body.text !== undefined)) throw fail('AGREEMENT_INVALID_COMMAND')
  return { operationId: id(body.operationId), expectedVersion: body.expectedVersion, action: body.action,
    ...(body.action === 'revise' ? { text: agreementText(body.text) } : {}) }
}

function agreementText(value) {
  if (typeof value !== 'string' || !value.trim() || value.length > 1000) throw fail('AGREEMENT_TEXT_REQUIRED')
  return value.trim()
}

function createAgreement(command, actorId, participants, now) {
  const ids = participants.map(member => id(member.id))
  if (ids.length !== 2 || ids.some(value => !value) || new Set(ids).size !== 2 || !ids.includes(actorId)) {
    throw fail('AGREEMENT_CHAT_UNAVAILABLE', 403)
  }
  return { id: command.id, sourceMessageId: command.sourceMessageId, authorId: actorId, text: command.text,
    version: 1, proposalRevision: 1, cancelled: false, archived: false, createdAt: now, updatedAt: now,
    participants: ids.map(userId => ({ id: userId, response: 'pending', respondedAt: null })) }
}

function changeAgreement(previous, command, actorId, now) {
  if (!previous.participants.some(member => member.id === actorId)) throw fail('AGREEMENT_ACCESS_DENIED', 403)
  if (previous.version !== command.expectedVersion) throw fail('AGREEMENT_VERSION_CONFLICT', 409)
  if (['archive', 'restore'].includes(command.action)) {
    const archived = command.action === 'archive'
    if (Boolean(previous.archived) === archived) return previous
    if (previous.version >= 10_000) throw fail('AGREEMENT_REVISION_LIMIT', 409)
    return { ...previous, archived, version: previous.version + 1, updatedAt: now }
  }
  if (previous.cancelled) throw fail('AGREEMENT_CANCELLED', 409)
  if (previous.archived && ['accept', 'revise'].includes(command.action)) throw fail('AGREEMENT_ARCHIVED', 409)
  if (previous.version >= 10_000) throw fail('AGREEMENT_REVISION_LIMIT', 409)
  let next
  switch (command.action) {
    case 'revise':
      if (actorId !== previous.authorId) throw fail('AGREEMENT_AUTHOR_REQUIRED', 403)
      if (command.text === previous.text) return previous
      next = { ...previous, text: command.text, proposalRevision: previous.proposalRevision + 1,
        participants: previous.participants.map(member => ({ ...member, response: 'pending', respondedAt: null })) }
      break
    case 'cancel':
      if (actorId !== previous.authorId) throw fail('AGREEMENT_AUTHOR_REQUIRED', 403)
      next = { ...previous, cancelled: true }
      break
    default: {
      const response = { accept: 'accepted', changes: 'changes', withdraw: 'withdrawn' }[command.action]
      if (!response) throw fail('AGREEMENT_INVALID_COMMAND')
      if (previous.participants.find(member => member.id === actorId).response === response) return previous
      next = { ...previous, participants: previous.participants.map(member => member.id === actorId
        ? { ...member, response, respondedAt: now } : member) }
    }
  }
  return { ...next, version: previous.version + 1, updatedAt: now }
}

function agreementStatus(agreement) {
  if (agreement.cancelled) return 'cancelled'
  if (agreement.participants.some(member => member.response === 'withdrawn')) return 'withdrawn'
  if (agreement.participants.some(member => member.response === 'changes')) return 'changes'
  return agreement.participants.every(member => member.response === 'accepted') ? 'accepted' : 'pending'
}

module.exports = { agreementCommand, createAgreement, changeAgreement, agreementStatus, agreementId: id, agreementFail: fail }
