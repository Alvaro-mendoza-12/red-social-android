const assert = require('node:assert/strict')
const crypto = require('node:crypto')

const baseUrl = String(process.env.E2E_API_URL || 'http://127.0.0.1:3001').replace(/\/+$/, '')
const suffix = crypto.randomUUID()
const password = `SemanticSearch-${suffix}!`
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
      email: `semantic-${label}-${suffix}@novvin.invalid`,
      password,
      displayName: `Semantic ${label}`,
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

async function createPost(owner, content, visibility) {
  const result = await authorized(owner, '/v1/data/feed/posts', {
    method: 'POST',
    body: JSON.stringify({ content, visibility }),
  })
  assert.equal(result.response.status, 201, JSON.stringify(result.body))
  const id = String(result.body.post.id)
  postIds.push(id)
  return id
}

async function run() {
  let owner
  try {
    owner = await register('owner')
    const seeker = await register('seeker')
    const publicPostId = await createPost(
      owner,
      'Recetas nutritivas para cocinar y cuidar el bienestar familiar',
      'public',
    )
    const privatePostId = await createPost(
      owner,
      'Alimentación saludable código privado que nunca debe indexarse',
      'private',
    )

    const search = await authorized(
      seeker,
      `/v1/data/search/semantic?q=${encodeURIComponent('alimentación saludable')}&limit=20`,
    )
    assert.equal(search.response.status, 200, JSON.stringify(search.body))
    assert.equal(search.body.model, 'novvin-semantic-local-v1')
    assert.equal(search.body.publicOnly, true)
    const resultIds = search.body.items.map((item) => item.id)
    assert.ok(resultIds.includes(publicPostId), JSON.stringify(search.body))
    assert.ok(!resultIds.includes(privatePostId), JSON.stringify(search.body))
    assert.equal(
      search.body.items.find((item) => item.id === publicPostId).reason,
      'semantic_concept',
    )

    const shortQuery = await authorized(seeker, '/v1/data/search/semantic?q=a')
    assert.equal(shortQuery.response.status, 400, JSON.stringify(shortQuery.body))
    assert.equal(shortQuery.body.error, 'SEMANTIC_QUERY_TOO_SHORT')

    console.log(JSON.stringify({
      ok: true,
      searchesByMeaning: true,
      publicOnly: true,
      privateContentExcludedBeforeRanking: true,
      stableModelIdentity: search.body.model,
    }))
  } finally {
    if (owner) await cleanup(owner)
  }
}

run().catch((error) => {
  console.error(error)
  process.exitCode = 1
})
