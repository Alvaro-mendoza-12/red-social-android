'use strict'

// HTTPS interaction checks between isolated accounts. Results contain labels and timings,
// never credentials, personal objects or request/response bodies. No real email is sent.
const https = require('node:https')
const crypto = require('node:crypto')
const fs = require('node:fs')
const path = require('node:path')
const assert = require('node:assert/strict')
const { performance } = require('node:perf_hooks')

async function main() {
  const origin = new URL(process.env.MOBILE_AUDIT_ORIGIN || 'https://conectate.tail9b267c.ts.net')
  assert.equal(origin.protocol, 'https:')
  assert.equal(origin.pathname, '/')
  assert.equal(origin.username + origin.password + origin.search + origin.hash, '')
  const dns = await fetch(`https://cloudflare-dns.com/dns-query?name=${encodeURIComponent(origin.hostname)}&type=A`,
    { headers: { accept: 'application/dns-json' }, signal: AbortSignal.timeout(8_000) }).then(r => r.json())
  const address = (dns.Answer || []).filter(r => r.type === 1).map(r => r.data).find(ip => {
    const p = ip.split('.').map(Number)
    return p.length === 4 && p.every(n => Number.isInteger(n) && n >= 0 && n <= 255) &&
      p[0] !== 0 && p[0] !== 10 && p[0] !== 127 && p[0] < 224 &&
      !(p[0] === 192 && p[1] === 168) && !(p[0] === 172 && p[1] >= 16 && p[1] <= 31) &&
      !(p[0] === 169 && p[1] === 254) && !(p[0] === 100 && p[1] >= 64 && p[1] <= 127)
  })
  assert(address, 'PUBLIC_DNS_REQUIRED')
  const agent = new https.Agent({ keepAlive: true, maxSockets: 2, lookup: (_host, opts, cb) => {
    if (opts.all) cb(null, [{ address, family: 4 }]); else cb(null, address, 4)
  } })
  const checks = []
  const accounts = []
  const ownedRooms = []
  const sections = []
  let cleanupOk = true
  async function request(label, route, { token, method = 'GET', body, expected = 200, bytes, contentType } = {}) {
    const payload = bytes || (body == null ? null : Buffer.from(JSON.stringify(body)))
    const started = performance.now()
    let response
    try {
      response = await new Promise((resolve, reject) => {
        const req = https.request(new URL(route, origin), { agent, timeout: 12_000, method, headers: {
          ...(token ? { authorization: `Bearer ${token}` } : {}),
          ...(payload ? { 'content-type': contentType || 'application/json', 'content-length': payload.length } : {}),
        } }, res => {
          let text = ''
          res.setEncoding('utf8')
          res.on('data', chunk => { text += chunk; if (text.length > 4 * 1024 * 1024) req.destroy(Error('RESPONSE_BOUND')) })
          res.on('end', () => {
            try { resolve({ status: res.statusCode, body: text ? JSON.parse(text) : {}, appMs: parseFloat(res.headers['x-response-time']) }) }
            catch { reject(Error('INVALID_JSON')) }
          })
          res.on('error', reject)
        })
        req.on('timeout', () => req.destroy(Error('REQUEST_TIMEOUT')))
        req.on('error', reject)
        req.end(payload)
      })
    } catch {
      checks.push({ label, ok: false, transportFailure: true, ms: Math.round(performance.now() - started) })
      throw Error('TRANSPORT_FAILURE')
    }
    checks.push({ label, status: response.status, expected, ok: response.status === expected,
      ms: Math.round(performance.now() - started), appMs: Number.isFinite(response.appMs) ? response.appMs : null })
    assert.equal(response.status, expected, 'STATUS_MISMATCH')
    return response.body
  }
  async function section(name, work) {
    try { await work(); sections.push({ name, ok: true }) }
    catch { sections.push({ name, ok: false, error: 'STATUS_OR_CONTRACT_FAILED' }) }
  }
  async function register(label) {
    const email = `mobile-interactions.${crypto.randomUUID()}@example.invalid`
    const password = `QA-${crypto.randomUUID()}-only`
    const auth = await request(`register-${label}`, '/v1/auth/register', {
      method: 'POST', body: { email, password, displayName: 'Auditoría temporal' }, expected: 201,
    })
    assert(auth.accessToken && auth.user?.id, 'REGISTRATION_CONTRACT')
    const account = { token: auth.accessToken, id: auth.user.id, email, password }
    accounts.push(account)
    return account
  }
  async function upload(account, bucket, bytes, extension, contentType) {
    const object = await request(`upload-${bucket}`, `/storage/v1/object/${bucket}/interaction-audit/${crypto.randomUUID()}.${extension}`,
      { token: account.token, method: 'PUT', bytes, contentType })
    assert(object.publicUrl, 'STORAGE_CONTRACT')
    return object.publicUrl
  }
  try {
    const owner = await register('owner')
    const member = await register('member')
    const outsider = await register('outsider')
    await section('login-and-permanent-identity', async () => {
      const login = await request('correct-password', '/v1/auth/login', {
        method: 'POST', body: { email: owner.email, password: owner.password },
      })
      assert(login.user?.id === owner.id, 'LOGIN_IDENTITY')
      await request('incorrect-password', '/v1/auth/login', {
        method: 'POST', body: { email: owner.email, password: `wrong-${crypto.randomUUID()}` }, expected: 401,
      })
      await request('permanent-username', '/v1/data/profile/me', {
        token: owner.token, method: 'PATCH', body: { username: `changed_${Date.now()}` }, expected: 409,
      })
      await request('editable-display-name', '/v1/data/profile/me', {
        token: owner.token, method: 'PATCH', body: { displayName: 'Auditoría temporal actualizada' },
      })
    })
    await section('direct-message-reply-edit-delete-and-auto-friend', async () => {
      const result = await request('create-direct-chat', '/v1/data/messages/chats', {
        token: owner.token, method: 'POST', body: { participantId: member.id }, expected: 201,
      })
      const base = `/v1/data/messages/chats/${result.chat.id}`
      const sent = await request('send-direct-message', `${base}/messages`, {
        token: owner.token, method: 'POST', body: { content: 'Mensaje sintético', contentType: 'text' }, expected: 201,
      })
      const friendship = await request('auto-friend-incoming', '/v1/data/friends', { token: member.token })
      const incoming = friendship.items.find(item => item.sender_id === owner.id)
      assert(incoming?.status === 'pending', 'AUTO_FRIEND_REQUEST')
      const outgoing = await request('auto-friend-outgoing', '/v1/data/friends?tab=outgoing', { token: owner.token })
      assert(outgoing.items.some(item => item.id === incoming.id && item.status === 'pending'), 'OUTGOING_REQUEST')
      const reply = await request('reply-direct-message', `${base}/messages`, {
        token: member.token, method: 'POST', body: { content: 'Respuesta sintética', replyToId: sent.message.id }, expected: 201,
      })
      assert.equal(reply.message.reply_to_id, sent.message.id)
      await request('reject-foreign-message-edit', `${base}/messages/${sent.message.id}`, {
        token: member.token, method: 'PATCH', body: { content: 'No permitido' }, expected: 403,
      })
      await request('edit-own-message', `${base}/messages/${sent.message.id}`, {
        token: owner.token, method: 'PATCH', body: { content: 'Mensaje sintético editado' },
      })
      await request('delete-message-for-self', `${base}/messages/${sent.message.id}/delete`, {
        token: member.token, method: 'POST', body: { forEveryone: false },
      })
      const hidden = await request('recipient-hidden-message', base, { token: member.token })
      const retained = await request('sender-retained-message', base, { token: owner.token })
      assert(!hidden.messages.some(m => m.id === sent.message.id) && retained.messages.some(m => m.id === sent.message.id), 'DELETE_SCOPE')
      await request('delete-message-for-everyone', `${base}/messages/${reply.message.id}/delete`, {
        token: member.token, method: 'POST', body: { forEveryone: true },
      })
      await request('cancel-auto-friend', `/v1/data/friends/${incoming.id}`, { token: owner.token, method: 'DELETE' })
    })
    for (const kind of ['groups', 'communities']) await section(`${kind}-history-reply-edit-permissions`, async () => {
      const created = await request(`create-${kind}`, `/v1/data/messages/${kind}`, {
        token: owner.token, method: 'POST', body: { name: `QA ${crypto.randomUUID()}`, description: 'Auditoría temporal', isPublic: false }, expected: 201,
      })
      const base = `/v1/data/messages/${kind}/${created.id}`
      ownedRooms.push({ base, token: owner.token })
      const message = await request(`${kind}-send`, `${base}/messages`, {
        token: owner.token, method: 'POST', body: { content: 'Historial sintético' }, expected: 201,
      })
      if (kind === 'groups') {
        const invite = await request(`${kind}-invite`, `${base}/invites`, {
          token: owner.token, method: 'POST', body: { expiresInHours: 1, maxUses: 1 }, expected: 201,
        })
        const preview = await request(`${kind}-private-invite-preview`, `${base}/preview?token=${encodeURIComponent(invite.token)}`, { token: member.token })
        assert(!Object.hasOwn(preview, 'messages') && preview.isMember === false, 'PREVIEW_PRIVACY')
        await request(`${kind}-join-invite`, `${base}/join-invite`, { token: member.token, method: 'POST', body: { token: invite.token } })
      } else {
        await request('community-owner-adds-member', `${base}/join`, { token: owner.token, method: 'POST', body: { userId: member.id } })
      }
      const history = await request(`${kind}-history`, base, { token: member.token })
      assert(history.messages.some(m => m.id === message.message.id), 'JOIN_HISTORY')
      await request(`${kind}-foreign-edit`, `${base}/messages/${message.message.id}`, {
        token: member.token, method: 'PATCH', body: { content: 'No permitido' }, expected: 403,
      })
      await request(`${kind}-nonowner-delete`, base, { token: member.token, method: 'DELETE', expected: 403 })
      await request(`${kind}-outsider-read`, base, { token: outsider.token, expected: 404 })
    })
    await section('stickers-deduplication-remove-and-cross-account-copy', async () => {
      const png = Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aD1cAAAAASUVORK5CYII=', 'base64')
      const url = await upload(owner, 'chat-media', png, 'png', 'image/png')
      const stickerId = `message_${crypto.randomUUID()}`
      const rpc = '/rest/v1/rpc/secure_toggle_sticker_favorite'
      const favorite = { p_sticker_id: stickerId, p_favorite: true, p_media_url: url, p_title: 'Sticker sintético', p_is_animated: false }
      for (let i = 0; i < 2; i++) await request(`favorite-sticker-${i}`, rpc, { token: owner.token, method: 'POST', body: favorite })
      const own = await request('sticker-list-owner', '/rest/v1/rpc/secure_search_stickers', { token: owner.token, method: 'POST', body: { p_query: '', p_limit: 250 } })
      assert.equal(own.filter(s => s.id === stickerId && s.is_favorite).length, 1)
      const other = await request('sticker-list-other-account', '/rest/v1/rpc/secure_search_stickers', { token: outsider.token, method: 'POST', body: { p_query: '', p_limit: 250 } })
      assert(!other.some(s => s.id === stickerId && s.is_favorite), 'FAVORITE_ACCOUNT_ISOLATION')
      const receiveChat = await request('sticker-create-recipient-chat', '/v1/data/messages/chats', {
        token: owner.token, method: 'POST', body: { participantId: outsider.id }, expected: 201,
      })
      await request('sticker-send-to-other-account', `/v1/data/messages/chats/${receiveChat.chat.id}/messages`, {
        token: owner.token, method: 'POST', body: { content: '[Sticker]', contentType: 'sticker', mediaUrl: url }, expected: 201,
      })
      await request('sticker-recipient-saves-once', rpc, { token: outsider.token, method: 'POST', body: favorite })
      const received = await request('sticker-recipient-own-favorites', '/rest/v1/rpc/secure_search_stickers', {
        token: outsider.token, method: 'POST', body: { p_query: '', p_limit: 250 },
      })
      const saved = received.find(s => s.id === stickerId && s.is_favorite)
      assert(saved?.media_url, 'RECIPIENT_STICKER_AVAILABLE')
      const forwardChat = await request('sticker-create-forward-chat', '/v1/data/messages/chats', {
        token: outsider.token, method: 'POST', body: { participantId: member.id }, expected: 201,
      })
      await request('sticker-forward-from-recipient-account', `/v1/data/messages/chats/${forwardChat.chat.id}/messages`, {
        token: outsider.token, method: 'POST', body: { content: '[Sticker]', contentType: 'sticker', mediaUrl: saved.media_url }, expected: 201,
      })
      await request('remove-sticker-favorite', rpc, { token: owner.token, method: 'POST', body: { p_sticker_id: stickerId, p_favorite: false } })
      const removed = await request('sticker-list-after-remove', '/rest/v1/rpc/secure_search_stickers', { token: owner.token, method: 'POST', body: { p_query: '', p_limit: 250 } })
      assert(!removed.some(s => s.id === stickerId && s.is_favorite), 'FAVORITE_REMOVED')
    })
    await section('video-publish-comment-search-and-private-visibility', async () => {
      const bytes = fs.readFileSync(path.resolve(__dirname, '../../../app/src/androidTest/assets/video-cache-smoke.mp4'))
      const url = await upload(owner, 'videos', bytes, 'mp4', 'video/mp4')
      const created = await request('publish-private-video', '/v1/data/videos', {
        token: owner.token, method: 'POST', body: { title: 'Auditoría temporal', videoUrl: url, durationSeconds: 3, isPublic: false }, expected: 201,
      })
      const id = created.video.id
      await request('private-video-rejects-other-account', `/v1/data/videos/${id}`, { token: outsider.token, expected: 404 })
      const phrase = `accent${crypto.randomUUID().slice(0, 8)}`
      const comment = await request('own-video-comment', `/v1/data/videos/${id}/comments`, {
        token: owner.token, method: 'POST', body: { content: `${phrase} canción`, contentType: 'text' }, expected: 201,
      })
      const search = await request('find-video-through-comment', `/v1/data/videos?q=${encodeURIComponent(`${phrase} cancion`)}&limit=5`, { token: owner.token })
      assert(search.items.some(v => v.id === id), 'COMMENT_ACCENT_SEARCH')
      const hidden = await request('comment-search-hides-private-video', `/v1/data/videos?q=${phrase}&limit=5`, { token: outsider.token })
      assert(!hidden.items.some(v => v.id === id), 'PRIVATE_SEARCH')
      await request('delete-video-comment', `/v1/data/videos/comments/${comment.comment.id}`, { token: owner.token, method: 'DELETE' })
      const after = await request('deleted-comment-not-searchable', `/v1/data/videos?q=${phrase}&limit=5`, { token: owner.token })
      assert(!after.items.some(v => v.id === id), 'DELETED_COMMENT_SEARCH')
      await request('delete-owned-video', `/v1/data/videos/${id}`, { token: owner.token, method: 'DELETE' })
    })
    await section('playmatch-versioning-idempotency-and-recipient-authorization', async () => {
      const current = await request('gaming-profile', '/v1/data/playmatch/profile', { token: owner.token })
      const body = { expectedVersion: current.profile.version, gamerDisplayName: 'QA gamer', timezone: 'America/Bogota', discoverable: false }
      await request('gaming-profile-update', '/v1/data/playmatch/profile', { token: owner.token, method: 'PATCH', body })
      await request('gaming-profile-reject-stale-version', '/v1/data/playmatch/profile', { token: owner.token, method: 'PATCH', body, expected: 409 })
      const connectionBody = { playerId: member.id, idempotencyKey: crypto.randomUUID(), message: 'Auditoría temporal' }
      const first = await request('gaming-connect', '/v1/data/playmatch/connections', { token: owner.token, method: 'POST', body: connectionBody, expected: 201 })
      const again = await request('gaming-connect-idempotent', '/v1/data/playmatch/connections', { token: owner.token, method: 'POST', body: connectionBody })
      assert.equal(first.connection.id, again.connection.id)
      const route = `/v1/data/playmatch/connections/${first.connection.id}/decision`
      await request('gaming-foreign-pending-decision', route, { token: outsider.token, method: 'POST', body: { decision: 'ACCEPT' }, expected: 403 })
      await request('gaming-accept-recipient', route, { token: member.token, method: 'POST', body: { decision: 'ACCEPT' } })
      await request('gaming-foreign-replay-decision', route, { token: outsider.token, method: 'POST', body: { decision: 'ACCEPT' }, expected: 403 })
      await request('gaming-recipient-replay-decision', route, { token: member.token, method: 'POST', body: { decision: 'ACCEPT' } })
    })
  } finally {
    for (const room of ownedRooms.reverse()) {
      try { await request('cleanup-owned-room', room.base, { token: room.token, method: 'DELETE' }) } catch { cleanupOk = false }
    }
    for (const account of accounts.reverse()) {
      try {
        await request('cleanup-isolated-account', '/rest/v1/rpc/delete_own_account', { token: account.token, method: 'POST', body: {} })
        await request('verify-revoked-account-token', '/v1/auth/me', { token: account.token, expected: 401 })
      } catch { cleanupOk = false }
    }
    agent.destroy()
    const times = checks.filter(c => c.ok && !c.label.startsWith('cleanup')).map(c => c.ms).sort((a, b) => a - b)
    const result = { date: new Date().toISOString(), origin: origin.origin, forcedPublicIpv4: true,
      scope: 'Isolated backend interactions; not full visual/device certification', sections, checks,
      cleanupVerified: cleanupOk && accounts.length > 0, actualEmailsSent: 0,
      p95Ms: times.length ? times[Math.ceil(times.length * .95) - 1] : null,
      ok: sections.length > 0 && sections.every(s => s.ok) && checks.every(c => c.ok) && cleanupOk }
    console.log(JSON.stringify(result, null, 2))
    if (!result.ok) process.exitCode = 1
  }
}

main().catch(() => { console.error('INTERACTION_AUDIT_FAILED_SEE_SANITIZED_RESULTS'); process.exitCode = 1 })
