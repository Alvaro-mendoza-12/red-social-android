const assert = require('node:assert/strict')

const apiUrl = String(process.env.API_URL || 'http://127.0.0.1:3080').replace(/\/+$/, '')
const runId = String(process.env.RUN_ID || Date.now())
const password = 'NovvinSaved123!'
let accessToken = ''

async function request(path, { token = accessToken, method = 'GET', body, expected = 200 } = {}) {
  const response = await fetch(`${apiUrl}${path}`, {
    method,
    headers: {
      ...(token ? { authorization: `Bearer ${token}` } : {}),
      ...(body != null ? { 'content-type': 'application/json' } : {}),
    },
    body: body == null ? undefined : JSON.stringify(body),
  })
  const text = await response.text()
  const payload = text ? JSON.parse(text) : {}
  assert.equal(response.status, expected, `${method} ${path}: ${response.status} ${text}`)
  return payload
}

async function cleanup() {
  if (!accessToken) return
  await request('/rest/v1/rpc/delete_own_account', {
    method: 'POST',
    body: {},
  }).catch((error) => {
    console.error(`SAVED_COLLECTIONS_CLEANUP_FAILED=${error.message}`)
  })
}

async function main() {
  const auth = await request('/v1/auth/register', {
    token: '',
    method: 'POST',
    expected: 201,
    body: {
      email: `module-saved.${runId}@example.local`,
      password,
      displayName: `Biblioteca ${runId}`,
    },
  })
  accessToken = auth.accessToken

  const initial = await request('/v1/data/saved-collections')
  assert.equal(initial.collections.length, 1)
  assert.equal(initial.collections[0].name, 'Guardados')
  assert.equal(initial.collections[0].is_default, true)

  const post = await request('/v1/data/feed/posts', {
    method: 'POST',
    expected: 201,
    body: {
      content: `Astronomía práctica ${runId}`,
      visibility: 'public',
    },
  })
  const postId = post.post.id

  const created = await request('/v1/data/saved-collections', {
    method: 'POST',
    expected: 201,
    body: { name: 'Ciencia y tecnología' },
  })
  const collectionId = created.collection.id
  await request(`/v1/data/saved-collections/${collectionId}/items`, {
    method: 'POST',
    expected: 201,
    body: {
      postId,
      tags: ['Ciencia', 'ciencia', 'WebRTC'],
      note: 'Leer antes de la reunión',
    },
  })

  const byTag = await request('/v1/data/saved-collections?q=webrtc')
  assert.equal(byTag.items.length, 1)
  assert.equal(byTag.items[0].post_id, postId)
  assert.deepEqual(byTag.items[0].tags, ['ciencia', 'webrtc'])
  assert.equal(byTag.items[0].post.available, true)

  const byContent = await request(`/v1/data/saved-collections?q=${encodeURIComponent(runId)}`)
  assert.equal(byContent.items.length, 1)
  assert.equal(
    byContent.collections.find((item) => item.id === collectionId).item_count,
    1,
  )

  await request(`/v1/data/saved-collections/${collectionId}/items/${postId}`, {
    method: 'DELETE',
  })
  const removed = await request('/v1/data/saved-collections')
  assert.equal(removed.items.length, 0)

  await request(`/v1/data/saved-collections/${initial.collections[0].id}/items`, {
    method: 'POST',
    expected: 201,
    body: { postId, tags: ['pendiente'] },
  })
  await request(`/v1/data/feed/posts/${postId}`, { method: 'DELETE' })
  const afterPostDelete = await request('/v1/data/saved-collections')
  assert.equal(afterPostDelete.items.length, 0)

  console.log(`SAVED_COLLECTIONS_OK=${runId}`)
}

main()
  .catch((error) => {
    console.error(error)
    process.exitCode = 1
  })
  .finally(cleanup)
