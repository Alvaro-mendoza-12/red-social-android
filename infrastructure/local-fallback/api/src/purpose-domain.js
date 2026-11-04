'use strict'

const UUID = /^[a-f0-9]{8}-[a-f0-9]{4}-[1-5][a-f0-9]{3}-[89ab][a-f0-9]{3}-[a-f0-9]{12}$/i
const fail = (code, status = 400) => Object.assign(new Error(code), { status, code })
function id(value) {
  if (typeof value !== 'string' || !UUID.test(value)) throw fail('PURPOSE_INVALID_ID')
  return value.toLowerCase()
}
function text(value, limit, optional = false) {
  if (typeof value !== 'string') throw fail('PURPOSE_TEXT_REQUIRED')
  const result = value.trim().normalize('NFC')
  if ((!optional && !result) || [...result].length > limit || /[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f]/u.test(result)) throw fail('PURPOSE_TEXT_LIMIT')
  return result
}
function command(body, actions) {
  if (!body || !actions.includes(body.action) || !Number.isSafeInteger(body.expectedRevision) || body.expectedRevision < 0) throw fail('PURPOSE_INVALID_COMMAND')
  return { operationId: id(body.operationId), expectedRevision: body.expectedRevision, action: body.action }
}
function socialCommand(body) {
  const cmd = command(body, ['start', 'conclude', 'reopen', 'retract'])
  if (cmd.action === 'conclude') {
    if (!Array.isArray(body.contributionIds) || body.contributionIds.length > 5) throw fail('PURPOSE_REFERENCES_LIMIT')
    cmd.conclusion = text(body.conclusion, 800)
    cmd.contributionIds = [...new Set(body.contributionIds.map(id))]
  }
  return cmd
}
function changeSocial(previous, cmd, context, now) {
  if (!context.isAuthor) throw fail('PURPOSE_AUTHOR_REQUIRED', 403)
  if (cmd.action === 'start') {
    if (previous) throw fail('PURPOSE_ALREADY_STARTED', 409)
    return { postId: context.postId, authorId: context.actorId, status: 'open', conclusion: '', contributionIds: [], createdAt: now }
  }
  if (!previous) throw fail('PURPOSE_NOT_FOUND', 404)
  if (cmd.action === 'conclude') {
    if (cmd.contributionIds.some(reference => !context.contributionIds.has(reference))) throw fail('PURPOSE_REFERENCE_UNAVAILABLE', 409)
    return { ...previous, status: 'concluded', conclusion: cmd.conclusion, contributionIds: cmd.contributionIds }
  }
  return { ...previous, status: cmd.action === 'reopen' ? 'open' : 'withdrawn', conclusion: '', contributionIds: [] }
}
function gamingCommand(body) {
  const cmd = command(body, ['propose', 'replan', 'accept', 'decline', 'confirm', 'cancel', 'feedback'])
  if (['propose', 'replan'].includes(cmd.action)) {
    if (typeof body.gameId !== 'string' || !/^[a-zA-Z0-9_-]{1,80}$/.test(body.gameId)) throw fail('PURPOSE_GAME_REQUIRED')
    if (!Number.isInteger(body.durationMinutes) || body.durationMinutes < 15 || body.durationMinutes > 240) throw fail('PURPOSE_DURATION_INVALID')
    if (typeof body.startsAt !== 'string' || !/^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(?:\.\d{1,3})?Z$/.test(body.startsAt) || !Number.isFinite(Date.parse(body.startsAt))) throw fail('PURPOSE_DATE_INVALID')
    Object.assign(cmd, { gameId: body.gameId, objective: text(body.objective, 180), durationMinutes: body.durationMinutes, startsAt: new Date(body.startsAt).toISOString() })
  }
  if (cmd.action === 'feedback') {
    if (!['played', 'not_played'].includes(body.outcome)) throw fail('PURPOSE_OUTCOME_INVALID')
    cmd.outcome = body.outcome
  }
  return cmd
}
function gamingStatus(state, now) {
  const at = Date.parse(now), start = Date.parse(state.startsAt)
  if (state.cancelledAt) return 'cancelled'
  if (Object.keys(state.confirmed || {}).length === 2) return at >= start + state.durationMinutes * 60000 ? 'finished' : 'ready'
  if (at > start + 10 * 60000) return 'expired'
  return Object.keys(state.accepted || {}).length === 2 ? 'agreed' : 'pending'
}
function changeGaming(previous, cmd, context, now) {
  if (!context.participants.some(member => member.id === context.actorId)) throw fail('PURPOSE_ACCESS_DENIED', 403)
  if (['propose', 'replan'].includes(cmd.action)) {
    if (cmd.action === 'propose' && previous) throw fail('PURPOSE_ALREADY_STARTED', 409)
    if (cmd.action === 'replan' && (!previous || previous.authorId !== context.actorId)) throw fail('PURPOSE_AUTHOR_REQUIRED', 403)
    if (!context.gameIds.has(cmd.gameId)) throw fail('PURPOSE_GAME_UNAVAILABLE', 409)
    const delta = Date.parse(cmd.startsAt) - Date.parse(now)
    if (delta < 15 * 60000 || delta > 30 * 86400000) throw fail('PURPOSE_DATE_INVALID')
    return { ...(previous || {}), authorId: previous?.authorId || context.actorId, participants: context.participants,
      gameId: cmd.gameId, objective: cmd.objective, durationMinutes: cmd.durationMinutes, startsAt: cmd.startsAt,
      planRevision: (previous?.planRevision || 0) + 1, accepted: {}, confirmed: {}, outcomes: {}, cancelledAt: null, cancelledBy: null,
      createdAt: previous?.createdAt || now }
  }
  if (!previous) throw fail('PURPOSE_NOT_FOUND', 404)
  const state = structuredClone(previous), start = Date.parse(state.startsAt), at = Date.parse(now)
  if (cmd.action === 'feedback') {
    if (at < start) throw fail('PURPOSE_TOO_EARLY', 409)
    state.outcomes[context.actorId] = cmd.outcome
    return state
  }
  if (['cancel', 'decline'].includes(cmd.action)) return { ...state, cancelledAt: now, cancelledBy: context.actorId }
  if (state.cancelledAt || at > start + 10 * 60000) throw fail('PURPOSE_PLAN_EXPIRED', 409)
  if (cmd.action === 'accept') state.accepted[context.actorId] = state.planRevision
  if (cmd.action === 'confirm') {
    if (Object.keys(state.accepted).length !== 2 || at < start - 60 * 60000) throw fail('PURPOSE_RECONFIRM_UNAVAILABLE', 409)
    state.confirmed[context.actorId] = state.planRevision
  }
  return state
}
function guideCommand(body) {
  const cmd = command(body, ['offer', 'withdraw'])
  if (cmd.action === 'offer') {
    if (![2, 8, 24].includes(body.hours)) throw fail('PURPOSE_DURATION_INVALID')
    cmd.hours = body.hours
  }
  return cmd
}
function changeGuide(previous, cmd, context, now) {
  return { authorId: context.actorId, communityId: context.communityId, active: cmd.action === 'offer',
    expiresAt: new Date(Date.parse(now) + (cmd.hours || 0) * 3600000).toISOString(), createdAt: previous?.createdAt || now }
}
function startCommand(body) {
  const cmd = command(body, ['select', 'opened', 'report', 'reset'])
  if (cmd.action === 'select') {
    if (!['question', 'event', 'guide'].includes(body.targetKind)) throw fail('PURPOSE_INVALID_COMMAND')
    cmd.targetKind = body.targetKind; cmd.targetId = id(body.targetId)
  }
  if (cmd.action === 'report') {
    if (!['helped', 'participated', 'received_help'].includes(body.outcome)) throw fail('PURPOSE_OUTCOME_INVALID')
    cmd.outcome = body.outcome
  }
  return cmd
}
function changeStart(previous, cmd, context, now) {
  if (cmd.action === 'select') {
    if (!context.targets.has(`${cmd.targetKind}:${cmd.targetId}`)) throw fail('PURPOSE_REFERENCE_UNAVAILABLE', 409)
    return { authorId: context.actorId, communityId: context.communityId, targetKind: cmd.targetKind, targetId: cmd.targetId,
      selectedAt: now, opened: false, outcome: null, createdAt: previous?.createdAt || now }
  }
  if (cmd.action === 'reset') return { authorId: context.actorId, communityId: context.communityId, targetKind: null, targetId: null, outcome: null, opened: false, createdAt: previous?.createdAt || now }
  if (!previous?.targetId) throw fail('PURPOSE_NOT_FOUND', 404)
  if (cmd.action === 'opened' && !context.targets.has(`${previous.targetKind}:${previous.targetId}`)) throw fail('PURPOSE_REFERENCE_UNAVAILABLE', 409)
  if (cmd.action === 'report' && cmd.outcome !== ({question:'helped',event:'participated',guide:'received_help'})[previous.targetKind]) throw fail('PURPOSE_OUTCOME_INVALID')
  return { ...previous, opened: cmd.action === 'opened' || previous.opened, outcome: cmd.action === 'report' ? cmd.outcome : previous.outcome }
}

module.exports = { fail, id, text, socialCommand, changeSocial, gamingCommand, gamingStatus, changeGaming, guideCommand, changeGuide, startCommand, changeStart }
