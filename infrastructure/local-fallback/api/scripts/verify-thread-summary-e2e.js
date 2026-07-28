const assert = require('node:assert/strict')
const crypto = require('node:crypto')

const baseUrl = String(process.env.E2E_API_URL || 'http://127.0.0.1:3001').replace(/\/+$/, '')
const suffix = crypto.randomUUID()
const password = `ThreadSummary-${suffix}!`
const accounts = []
const postIds = []

async function jsonRequest(path, options = {}) {
  const response = await fetch(`${baseUrl}${path}`, {
    ...options,
    headers: {
      accept: 'application/json',
      'content-type': 'application/json',
      ...(options.headers || {}),
    },
  })
  const text = await response.text()
  let body = {}
  try {
    body = text ? JSON.parse(text) : {}
  } catch {
    body = { message: text }
  }
  return { response, body }
}

async function register(label) {
  const result = await jsonRequest('/v1/auth/register', {
    method: 'POST',
    body: JSON.stringify({
      email: `thread-summary-${label}-${suffix}@novvin.invalid`,
      password,
      displayName: `Thread ${label}`,
    }),
  })
  assert.equal(result.response.status, 201, JSON.stringify(result.body))
  const account = {
    id: String(result.body.user.id).toLowerCase(),
    token: String(result.body.accessToken),
  }
  accounts.push(account)
  return account
}

async function authorized(account, path, options = {}) {
  return jsonRequest(path, {
    ...options,
    headers: {
      authorization: `Bearer ${account.token}`,
      ...(options.headers || {}),
    },
  })
}

async function createPost(owner, content, visibility) {
  const result = await authorized(owner, '/v1/data/feed/posts', {
    method: 'POST',
    body: JSON.stringify({ content, visibility }),
  })
  assert.equal(result.response.status, 201, JSON.stringify(result.body))
  const postId = String(result.body.post.id)
  postIds.push(postId)
  return postId
}

async function comment(account, postId, content) {
  const result = await authorized(account, `/v1/data/feed/posts/${postId}/comments`, {
    method: 'POST',
    body: JSON.stringify({ content }),
  })
  assert.equal(result.response.status, 201, JSON.stringify(result.body))
  return {
    id: String(result.body.comment.id),
    content,
  }
}

async function cleanup(owner) {
  for (const postId of postIds) {
    await authorized(owner, `/v1/data/feed/posts/${postId}`, { method: 'DELETE' })
  }
  for (const account of accounts) {
    const removed = await authorized(account, '/rest/v1/rpc/delete_own_account', {
      method: 'POST',
      body: '{}',
    })
    assert.ok(removed.response.ok, `Self cleanup failed: ${JSON.stringify(removed.body)}`)
  }
}

async function summarize(account, postId) {
  return authorized(account, `/v1/data/feed/posts/${postId}/thread-summary`, {
    method: 'POST',
    body: JSON.stringify({ maxBullets: 4 }),
  })
}

async function run() {
  let owner
  try {
    owner = await register('owner')
    const participant = await register('participant')
    const stranger = await register('stranger')

    const publicPostId = await createPost(owner, 'Organización de la actividad comunitaria', 'public')
    const publicComments = [
      await comment(owner, publicPostId, 'Propongo iniciar la actividad a las ocho para aprovechar la mañana.'),
      await comment(participant, publicPostId, 'Prefiero comenzar a las nueve porque el transporte llega tarde.'),
      await comment(owner, publicPostId, 'Podemos grabar la sesión y compartir el material con quienes falten.'),
      await comment(participant, publicPostId, 'También necesitamos una lista final de acuerdos y responsables.'),
    ]
    const publicIds = new Set(publicComments.map((item) => item.id.toLowerCase()))
    const publicTextById = new Map(
      publicComments.map((item) => [item.id.toLowerCase(), item.content]),
    )

    const publicSummary = await summarize(stranger, publicPostId)
    assert.equal(publicSummary.response.status, 200, JSON.stringify(publicSummary.body))
    assert.match(publicSummary.response.headers.get('cache-control') || '', /no-store/i)
    assert.equal(publicSummary.body.summary.kind, 'extractive')
    assert.equal(publicSummary.body.summary.model, 'novvin-thread-extractive-v1')
    assert.equal(publicSummary.body.summary.commentCount, 4)
    assert.match(publicSummary.body.summary.notice, /matices/i)
    assert.ok(publicSummary.body.summary.bullets.length >= 2)
    for (const bullet of publicSummary.body.summary.bullets) {
      assert.ok(bullet.sources.length >= 1)
      for (const source of bullet.sources) {
        const id = String(source.commentId).toLowerCase()
        assert.ok(publicIds.has(id), JSON.stringify(source))
        assert.ok(publicTextById.get(id).includes(bullet.text) || bullet.text.includes(publicTextById.get(id)))
        assert.match(source.deepLink, new RegExp(`^novvin://post/${publicPostId}`))
      }
    }

    const privatePostId = await createPost(owner, 'Conversación privada del propietario', 'private')
    await comment(owner, privatePostId, 'Primer comentario privado para la persona propietaria.')
    await comment(owner, privatePostId, 'Segundo comentario privado con otro punto de vista.')
    await comment(owner, privatePostId, 'Tercer comentario privado para completar el resumen.')

    const hiddenDetail = await authorized(stranger, `/v1/data/feed/posts/${privatePostId}`)
    assert.equal(hiddenDetail.response.status, 404, JSON.stringify(hiddenDetail.body))
    const hiddenSummary = await summarize(stranger, privatePostId)
    assert.equal(hiddenSummary.response.status, 404, JSON.stringify(hiddenSummary.body))
    const ownerSummary = await summarize(owner, privatePostId)
    assert.equal(ownerSummary.response.status, 200, JSON.stringify(ownerSummary.body))

    console.log(JSON.stringify({
      ok: true,
      extractiveOnly: true,
      sourceLinksVerified: true,
      privatePostBlockedForStranger: true,
      ownerExplicitRequestAllowed: true,
      responseNotCached: true,
      stableModelIdentity: publicSummary.body.summary.model,
    }))
  } finally {
    if (owner) await cleanup(owner)
  }
}

run().catch((error) => {
  console.error(error)
  process.exitCode = 1
})
