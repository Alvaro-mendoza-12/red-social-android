const assert = require('node:assert/strict')

const apiUrl = String(process.env.API_URL || 'http://127.0.0.1:3080').replace(/\/+$/, '')
const runId = process.env.RUN_ID || Date.now().toString()
const password = 'Conectate123!'

async function request(path, { token, expected = 200, ...options } = {}) {
  const response = await fetch(`${apiUrl}${path}`, {
    ...options,
    headers: {
      'content-type': 'application/json',
      ...(token ? { authorization: `Bearer ${token}` } : {}),
      ...(options.headers || {}),
    },
  })
  const text = await response.text()
  const body = text ? JSON.parse(text) : {}
  assert.equal(response.status, expected, `${options.method || 'GET'} ${path}: ${response.status} ${text}`)
  return body
}

async function register(label) {
  const auth = await request('/v1/auth/register', {
    method: 'POST',
    expected: 201,
    body: JSON.stringify({
      email: `module1.${label}.${runId}@example.local`,
      password,
      displayName: `Module1 ${label}`,
    }),
  })
  return { token: auth.accessToken, id: auth.user.id }
}

async function main() {
  const owner = await register('owner')
  const admin = await register('admin')
  const member = await register('member')

  const directChat = await request('/v1/data/messages/chats', {
    token: owner.token,
    method: 'POST',
    expected: 201,
    body: JSON.stringify({ participantId: member.id }),
  })
  assert.ok(directChat.chat?.id)
  const directOriginal = await request(`/v1/data/messages/chats/${directChat.chat.id}/messages`, {
    token: owner.token,
    method: 'POST',
    expected: 201,
    body: JSON.stringify({ content: `Mensaje web ${runId}`, contentType: 'text' }),
  })
  const memberDirectChat = await request(`/v1/data/messages/chats/${directChat.chat.id}`, {
    token: member.token,
  })
  assert.ok(memberDirectChat.messages.some((message) => message.content === `Mensaje web ${runId}`))
  const directReply = await request(`/v1/data/messages/chats/${directChat.chat.id}/messages`, {
    token: member.token,
    method: 'POST',
    expected: 201,
    body: JSON.stringify({ content: 'Respuesta privada', contentType: 'text', replyToId: directOriginal.message.id }),
  })
  assert.equal(directReply.message.reply_to_id, directOriginal.message.id)
  await request(`/v1/data/messages/chats/${directChat.chat.id}/messages/${directOriginal.message.id}`, {
    token: owner.token,
    method: 'PATCH',
    body: JSON.stringify({ content: `Mensaje privado editado ${runId}` }),
  })
  await request(`/v1/data/messages/chats/${directChat.chat.id}/messages/${directOriginal.message.id}`, {
    token: member.token,
    method: 'PATCH',
    expected: 403,
    body: JSON.stringify({ content: 'Edición no autorizada' }),
  })
  await request(`/v1/data/messages/chats/${directChat.chat.id}/messages/${directOriginal.message.id}/delete`, {
    token: member.token,
    method: 'POST',
    body: JSON.stringify({ forEveryone: false }),
  })
  const directMemberAfterDelete = await request(`/v1/data/messages/chats/${directChat.chat.id}`, { token: member.token })
  const directOwnerAfterDelete = await request(`/v1/data/messages/chats/${directChat.chat.id}`, { token: owner.token })
  assert.ok(!directMemberAfterDelete.messages.some((message) => message.id === directOriginal.message.id))
  assert.ok(directOwnerAfterDelete.messages.some((message) => message.id === directOriginal.message.id))
  await request(`/v1/data/messages/chats/${directChat.chat.id}/messages/${directReply.message.id}/delete`, {
    token: member.token,
    method: 'POST',
    body: JSON.stringify({ forEveryone: true }),
  })
  const directAfterDeleteAll = await request(`/v1/data/messages/chats/${directChat.chat.id}`, { token: owner.token })
  assert.ok(!directAfterDeleteAll.messages.some((message) => message.id === directReply.message.id))

  await request('/v1/data/messages/translate', {
    method: 'POST',
    expected: 401,
    body: JSON.stringify({ text: 'Hola mundo', source: 'es', target: 'en' }),
  })
  await request('/v1/data/messages/translate', {
    token: owner.token,
    method: 'POST',
    expected: 400,
    body: JSON.stringify({ text: 'Hola mundo', source: 'es', target: 'de' }),
  })

  const translation = await request('/v1/data/messages/translate', {
    token: owner.token,
    method: 'POST',
    body: JSON.stringify({ text: 'Hola mundo', source: 'es', target: 'en' }),
  })
  assert.ok(translation.translatedText)
  assert.notEqual(translation.translatedText.toLowerCase(), 'hola mundo')

  const groupCreated = await request('/v1/data/messages/groups', {
    token: owner.token,
    method: 'POST',
    expected: 201,
    body: JSON.stringify({ name: `Module1 Group ${runId}`, description: 'Historial seguro' }),
  })
  const groupId = groupCreated.id
  assert.ok(groupId)

  await request(`/v1/data/messages/groups/${groupId}/messages`, {
    token: owner.token,
    method: 'POST',
    expected: 201,
    body: JSON.stringify({ content: 'Mensaje anterior a la invitación' }),
  })
  await request(`/v1/data/messages/groups/${groupId}/join`, {
    token: member.token,
    method: 'POST',
    expected: 404,
    body: '{}',
  })
  const invite = await request(`/v1/data/messages/groups/${groupId}/invites`, {
    token: owner.token,
    method: 'POST',
    expected: 201,
    body: JSON.stringify({ expiresInHours: 24, maxUses: 5 }),
  })
  assert.ok(invite.token)
  await request(`/v1/data/messages/groups/${groupId}/preview?token=${'x'.repeat(64)}`, {
    token: member.token,
    expected: 410,
  })
  const groupPreview = await request(
    `/v1/data/messages/groups/${groupId}/preview?token=${encodeURIComponent(invite.token)}`,
    { token: member.token },
  )
  assert.equal(groupPreview.kind, 'group')
  assert.equal(groupPreview.roomId, groupId)
  assert.equal(groupPreview.name, `Module1 Group ${runId}`)
  assert.equal(groupPreview.isMember, false)
  assert.equal(Object.hasOwn(groupPreview, 'messages'), false)
  await request(`/v1/data/messages/groups/${groupId}/join-invite`, {
    token: member.token,
    method: 'POST',
    body: JSON.stringify({ token: invite.token }),
  })
  const joinedGroupPreview = await request(
    `/v1/data/messages/groups/${groupId}/preview?token=${encodeURIComponent(invite.token)}`,
    { token: member.token },
  )
  assert.equal(joinedGroupPreview.isMember, true)
  assert.ok(joinedGroupPreview.participants.some((participant) => participant.id === member.id))
  const memberGroup = await request(`/v1/data/messages/groups/${groupId}`, { token: member.token })
  assert.ok(memberGroup.messages.some((message) => message.content === 'Mensaje anterior a la invitación'))
  const groupOriginal = await request(`/v1/data/messages/groups/${groupId}/messages`, {
    token: owner.token,
    method: 'POST',
    expected: 201,
    body: JSON.stringify({ content: 'Mensaje de grupo editable' }),
  })
  const groupReply = await request(`/v1/data/messages/groups/${groupId}/messages`, {
    token: member.token,
    method: 'POST',
    expected: 201,
    body: JSON.stringify({ content: 'Respuesta de grupo', replyToId: groupOriginal.message.id }),
  })
  assert.equal(groupReply.message.reply_to_id, groupOriginal.message.id)
  await request(`/v1/data/messages/groups/${groupId}/messages/${groupOriginal.message.id}`, {
    token: owner.token,
    method: 'PATCH',
    body: JSON.stringify({ content: 'Mensaje de grupo editado' }),
  })
  await request(`/v1/data/messages/groups/${groupId}/messages/${groupOriginal.message.id}`, {
    token: member.token,
    method: 'PATCH',
    expected: 403,
    body: JSON.stringify({ content: 'Edición no autorizada' }),
  })
  await request(`/v1/data/messages/groups/${groupId}/messages/${groupOriginal.message.id}/delete`, {
    token: member.token,
    method: 'POST',
    body: JSON.stringify({ forEveryone: false }),
  })
  const groupMemberAfterDelete = await request(`/v1/data/messages/groups/${groupId}`, { token: member.token })
  const groupOwnerAfterDelete = await request(`/v1/data/messages/groups/${groupId}`, { token: owner.token })
  assert.ok(!groupMemberAfterDelete.messages.some((message) => message.id === groupOriginal.message.id))
  assert.ok(groupOwnerAfterDelete.messages.some((message) => message.id === groupOriginal.message.id))
  await request(`/v1/data/messages/groups/${groupId}/messages/${groupReply.message.id}/delete`, {
    token: member.token,
    method: 'POST',
    body: JSON.stringify({ forEveryone: true }),
  })
  const groupAfterDeleteAll = await request(`/v1/data/messages/groups/${groupId}`, { token: owner.token })
  assert.ok(!groupAfterDeleteAll.messages.some((message) => message.id === groupReply.message.id))
  await request(`/v1/data/messages/groups/${groupId}/invites`, {
    token: member.token,
    method: 'POST',
    expected: 403,
    body: '{}',
  })

  const restUntil = new Date(Date.now() + 8 * 60 * 60 * 1000).toISOString()
  await request(`/v1/data/messages/groups/${groupId}/member-settings`, {
    token: member.token,
    method: 'PATCH',
    body: JSON.stringify({ restUntil }),
  })
  const restedGroup = await request(`/v1/data/messages/groups/${groupId}`, { token: member.token })
  assert.ok(restedGroup.participants.find((participant) => participant.id === member.id)?.restUntil)

  await request(`/v1/data/messages/groups/${groupId}/join`, {
    token: owner.token,
    method: 'POST',
    body: JSON.stringify({ userId: admin.id, role: 'admin' }),
  })
  await request(`/v1/data/messages/groups/${groupId}/update`, {
    token: admin.token,
    method: 'POST',
    body: JSON.stringify({ name: `Module1 Group ${runId}`, only_admins_can_talk: true }),
  })
  await request(`/v1/data/messages/groups/${groupId}/messages`, {
    token: member.token,
    method: 'POST',
    expected: 403,
    body: JSON.stringify({ content: 'No permitido' }),
  })
  await request(`/v1/data/messages/groups/${groupId}/messages`, {
    token: admin.token,
    method: 'POST',
    expected: 201,
    body: JSON.stringify({ content: 'Mensaje de administrador' }),
  })
  await request(`/v1/data/messages/groups/${groupId}`, { token: admin.token, method: 'DELETE', expected: 403 })

  const communityCreated = await request('/v1/data/messages/communities', {
    token: owner.token,
    method: 'POST',
    expected: 201,
    body: JSON.stringify({ name: `Module1 Community ${runId}`, description: 'Comunidad pública', isPublic: true }),
  })
  const communityId = communityCreated.id
  await request(`/v1/data/messages/communities/${communityId}/messages`, {
    token: owner.token,
    method: 'POST',
    expected: 201,
    body: JSON.stringify({ content: 'Historial para nuevos miembros' }),
  })
  const communityPreview = await request(
    `/v1/data/messages/communities/${communityId}/preview`,
    { token: member.token },
  )
  assert.equal(communityPreview.kind, 'community')
  assert.equal(communityPreview.roomId, communityId)
  assert.equal(communityPreview.name, `Module1 Community ${runId}`)
  assert.equal(communityPreview.isMember, false)
  assert.equal(Object.hasOwn(communityPreview, 'messages'), false)
  await request(`/v1/data/messages/communities/${communityId}/join`, {
    token: member.token,
    method: 'POST',
    body: '{}',
  })
  const memberCommunity = await request(`/v1/data/messages/communities/${communityId}`, { token: member.token })
  assert.ok(memberCommunity.messages.some((message) => message.content === 'Historial para nuevos miembros'))
  const communityOriginal = await request(`/v1/data/messages/communities/${communityId}/messages`, {
    token: owner.token,
    method: 'POST',
    expected: 201,
    body: JSON.stringify({ content: 'Mensaje de comunidad editable' }),
  })
  const communityReply = await request(`/v1/data/messages/communities/${communityId}/messages`, {
    token: owner.token,
    method: 'POST',
    expected: 201,
    body: JSON.stringify({ content: 'Respuesta de comunidad', replyToId: communityOriginal.message.id }),
  })
  assert.equal(communityReply.message.reply_to_id, communityOriginal.message.id)
  await request(`/v1/data/messages/communities/${communityId}/messages/${communityOriginal.message.id}`, {
    token: owner.token,
    method: 'PATCH',
    body: JSON.stringify({ content: 'Mensaje de comunidad editado' }),
  })
  await request(`/v1/data/messages/communities/${communityId}/messages/${communityOriginal.message.id}`, {
    token: member.token,
    method: 'PATCH',
    expected: 403,
    body: JSON.stringify({ content: 'Edición no autorizada' }),
  })
  await request(`/v1/data/messages/communities/${communityId}/messages/${communityOriginal.message.id}/delete`, {
    token: member.token,
    method: 'POST',
    body: JSON.stringify({ forEveryone: false }),
  })
  const communityMemberAfterDelete = await request(`/v1/data/messages/communities/${communityId}`, { token: member.token })
  const communityOwnerAfterDelete = await request(`/v1/data/messages/communities/${communityId}`, { token: owner.token })
  assert.ok(!communityMemberAfterDelete.messages.some((message) => message.id === communityOriginal.message.id))
  assert.ok(communityOwnerAfterDelete.messages.some((message) => message.id === communityOriginal.message.id))
  await request(`/v1/data/messages/communities/${communityId}/messages/${communityReply.message.id}/delete`, {
    token: owner.token,
    method: 'POST',
    body: JSON.stringify({ forEveryone: true }),
  })
  const communityAfterDeleteAll = await request(`/v1/data/messages/communities/${communityId}`, { token: member.token })
  assert.ok(!communityAfterDeleteAll.messages.some((message) => message.id === communityReply.message.id))
  await request(`/v1/data/messages/communities/${communityId}/update`, {
    token: member.token,
    method: 'POST',
    expected: 403,
    body: JSON.stringify({ daily_message_limit: 1 }),
  })
  await request(`/v1/data/messages/communities/${communityId}/update`, {
    token: owner.token,
    method: 'POST',
    body: JSON.stringify({ daily_message_limit: 1 }),
  })
  await request(`/v1/data/messages/communities/${communityId}/messages`, {
    token: member.token,
    method: 'POST',
    expected: 201,
    body: JSON.stringify({ content: 'Único mensaje diario' }),
  })
  const limited = await request(`/v1/data/messages/communities/${communityId}/messages`, {
    token: member.token,
    method: 'POST',
    expected: 429,
    body: JSON.stringify({ content: 'Debe ser rechazado' }),
  })
  assert.equal(limited.error, 'COMMUNITY_DAILY_MESSAGE_LIMIT_REACHED')
  await request(`/v1/data/messages/communities/${communityId}/messages`, {
    token: owner.token,
    method: 'POST',
    expected: 201,
    body: JSON.stringify({ content: 'Administradores no consumen el límite' }),
  })
  await request(`/v1/data/messages/communities/${communityId}`, { token: admin.token, method: 'DELETE', expected: 403 })

  await request(`/v1/data/messages/groups/${groupId}`, { token: owner.token, method: 'DELETE' })
  await request(`/v1/data/messages/communities/${communityId}`, { token: owner.token, method: 'DELETE' })
  console.log(JSON.stringify({
    ok: true,
    groupInvite: true,
    groupQrPreview: true,
    communityQrPreview: true,
    qrPreviewDoesNotExposeMessages: true,
    groupHistory: true,
    groupRest: true,
    onlyAdminsWrite: true,
    communityHistory: true,
    communityDailyLimit: true,
    destructiveDeleteOwnerOnly: true,
    translationSecurity: true,
    directChatFromProfile: true,
    directMessageActions: true,
    groupMessageActions: true,
    communityMessageActions: true,
    privateTranslation: translation.translatedText,
  }))
}

main().catch((error) => {
  console.error(error.stack || error.message)
  process.exitCode = 1
})
