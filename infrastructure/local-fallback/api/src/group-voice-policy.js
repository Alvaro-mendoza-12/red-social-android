'use strict'
const crypto = require('node:crypto')

function problem(code, status = 400) { return Object.assign(new Error(code), { code, status }) }
function id(value) { return typeof value === 'string' ? value.trim().toLowerCase() : '' }
function normalizedIds(values) {
  if (!Array.isArray(values)) throw problem('GROUP_VOICE_RECIPIENTS_REQUIRED')
  const ids = values.map(id)
  if (ids.some(value => !value || value.length > 200)) throw problem('GROUP_VOICE_RECIPIENT_INVALID')
  return [...new Set(ids)].sort()
}

/** Both the permission check and the displayed count use this authoritative roster. */
function voiceInvitees(members, creatorIds) {
  const owners = new Set([...creatorIds].map(id).filter(Boolean))
  const result = new Map()
  for (const member of members || []) {
    const userId = id(member.id || member.userId || member.user_id)
    const role = id(member.role)
    if (!userId || owners.has(userId) || member.is_member === false ||
        ['former_member', 'left', 'removed', 'banned'].includes(role)) continue
    if (!result.has(userId)) result.set(userId, { ...member, id: userId })
  }
  return [...result.values()].sort((a, b) => a.id.localeCompare(b.id))
}

function selectVoiceInvitees({ members, creatorIds, mode, recipientIds }) {
  if (!['all', 'selected'].includes(mode)) throw problem('GROUP_VOICE_MODE_INVALID')
  const invitees = voiceInvitees(members, creatorIds)
  const owners = new Set([...creatorIds].map(id).filter(Boolean))
  const selected = normalizedIds(recipientIds).filter(value => !owners.has(value))
  const allowed = new Map(invitees.map(member => [member.id, member]))
  if (selected.some(value => !allowed.has(value))) throw problem('GROUP_VOICE_MEMBERS_CHANGED', 409)
  if (mode === 'all' && (selected.length !== invitees.length || invitees.some(member => !selected.includes(member.id)))) {
    throw problem('GROUP_VOICE_MEMBERS_CHANGED', 409)
  }
  if (!selected.length) throw problem('GROUP_VOICE_INVITEES_REQUIRED')
  return selected.map(value => allowed.get(value))
}

/** Stable across request reordering; an existing room must check this before any new invitation. */
function groupVoiceRequestHash({ groupId, mode, recipientIds }) {
  return crypto.createHash('sha256').update(JSON.stringify({ groupId: id(groupId), mode,
    recipients: normalizedIds(recipientIds) })).digest('hex')
}

module.exports = { voiceInvitees, selectVoiceInvitees, groupVoiceRequestHash }
