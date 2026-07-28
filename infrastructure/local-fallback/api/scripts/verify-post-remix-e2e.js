const assert = require('node:assert/strict')
const crypto = require('node:crypto')

const baseUrl = String(process.env.E2E_API_URL || 'http://127.0.0.1:3001').replace(/\/+$/, '')
const suffix = crypto.randomUUID()
const password = `PostRemix-${suffix}!`
const accounts = []
let originalId = ''
let remixId = ''

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
  const email = `post-remix-${label}-${suffix}@novvin.invalid`
  const result = await jsonRequest('/v1/auth/register', {
    method: 'POST',
    body: JSON.stringify({ email, password, displayName: `Remix ${label}` }),
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

async function cleanup(owner, remixer) {
  if (remixer && remixId) {
    await authorized(remixer, `/v1/data/feed/posts/${remixId}`, { method: 'DELETE' })
  }
  if (owner && originalId) {
    await authorized(owner, `/v1/data/feed/posts/${originalId}`, { method: 'DELETE' })
  }
  for (const account of accounts) {
    const removed = await authorized(account, '/rest/v1/rpc/delete_own_account', {
      method: 'POST',
      body: '{}',
    })
    assert.ok(removed.response.ok, `Self cleanup failed: ${JSON.stringify(removed.body)}`)
  }
}

async function run() {
  let owner
  let remixer
  try {
    owner = await register('owner')
    remixer = await register('remixer')
    const stranger = await register('stranger')

    const created = await authorized(owner, '/v1/data/feed/posts', {
      method: 'POST',
      body: JSON.stringify({
        content: 'Publicación original B20',
        visibility: 'public',
        remixAllowed: false,
      }),
    })
    assert.equal(created.response.status, 201, JSON.stringify(created.body))
    originalId = String(created.body.post.id)
    assert.equal(created.body.post.remix_allowed, false)

    const deniedByDefault = await authorized(remixer, `/v1/data/feed/posts/${originalId}/remix`, {
      method: 'POST',
      body: JSON.stringify({ content: 'No debe crearse' }),
    })
    assert.equal(deniedByDefault.response.status, 409, JSON.stringify(deniedByDefault.body))
    assert.equal(deniedByDefault.body.error, 'REMIX_NOT_ALLOWED')

    const enabled = await authorized(owner, `/v1/data/feed/posts/${originalId}/remix-permission`, {
      method: 'PATCH',
      body: JSON.stringify({ allowed: true, audience: 'followers' }),
    })
    assert.equal(enabled.response.status, 200, JSON.stringify(enabled.body))
    assert.equal(enabled.body.post.remix_allowed, true)
    assert.equal(enabled.body.post.remix_audience, 'followers')

    const strangerDenied = await authorized(stranger, `/v1/data/feed/posts/${originalId}/remix`, {
      method: 'POST',
      body: JSON.stringify({ content: 'No soy seguidor' }),
    })
    assert.equal(strangerDenied.response.status, 403, JSON.stringify(strangerDenied.body))
    assert.equal(strangerDenied.body.error, 'REMIX_FOLLOWERS_ONLY')

    const followed = await authorized(remixer, `/v1/data/profiles/${owner.id}/follow`, {
      method: 'POST',
      body: '{}',
    })
    assert.equal(followed.response.status, 200, JSON.stringify(followed.body))

    const remixed = await authorized(remixer, `/v1/data/feed/posts/${originalId}/remix`, {
      method: 'POST',
      body: JSON.stringify({ content: 'Mi lectura con atribución', visibility: 'public' }),
    })
    assert.equal(remixed.response.status, 201, JSON.stringify(remixed.body))
    remixId = String(remixed.body.post.id)
    assert.equal(remixed.body.post.remix_source_post_id, originalId.toLowerCase())
    assert.equal(remixed.body.post.remix_root_post_id, originalId.toLowerCase())
    assert.equal(remixed.body.post.remix_source_user_id, owner.id)
    assert.equal(remixed.body.post.remix_depth, 1)

    const foreignPermission = await authorized(remixer, `/v1/data/feed/posts/${originalId}/remix-permission`, {
      method: 'PATCH',
      body: JSON.stringify({ allowed: false, audience: 'public' }),
    })
    assert.equal(foreignPermission.response.status, 403, JSON.stringify(foreignPermission.body))

    const revoked = await authorized(owner, `/v1/data/feed/posts/${originalId}/remix-permission`, {
      method: 'PATCH',
      body: JSON.stringify({ allowed: false, audience: 'followers' }),
    })
    assert.equal(revoked.response.status, 200, JSON.stringify(revoked.body))
    assert.equal(revoked.body.post.remix_allowed, false)
    assert.ok(revoked.body.post.remix_revoked_at)

    const deniedAfterRevocation = await authorized(remixer, `/v1/data/feed/posts/${originalId}/remix`, {
      method: 'POST',
      body: JSON.stringify({ content: 'No debe crearse después de revocar' }),
    })
    assert.equal(deniedAfterRevocation.response.status, 409, JSON.stringify(deniedAfterRevocation.body))

    const existingDerivative = await authorized(stranger, `/v1/data/feed/posts/${remixId}`)
    assert.equal(existingDerivative.response.status, 200, JSON.stringify(existingDerivative.body))
    assert.equal(existingDerivative.body.post.remix_source_user_id, owner.id)

    const deletedOriginal = await authorized(owner, `/v1/data/feed/posts/${originalId}`, { method: 'DELETE' })
    assert.equal(deletedOriginal.response.status, 200, JSON.stringify(deletedOriginal.body))
    originalId = ''

    const derivativeAfterDelete = await authorized(stranger, `/v1/data/feed/posts/${remixId}`)
    assert.equal(derivativeAfterDelete.response.status, 200, JSON.stringify(derivativeAfterDelete.body))
    assert.equal(derivativeAfterDelete.body.post.remix_source_user_id, owner.id)
    assert.equal(derivativeAfterDelete.body.post.remix_source_user_name, 'Remix owner')

    console.log(JSON.stringify({
      ok: true,
      permissionPerPost: true,
      followerAudienceEnforced: true,
      attributionChainPersisted: true,
      revocationBlocksOnlyFutureRemixes: true,
      derivativeSurvivesOriginalDeletion: true,
      foreignPermissionMutationBlocked: true,
    }))
  } finally {
    await cleanup(owner, remixer)
  }
}

run().catch((error) => {
  console.error(error)
  process.exitCode = 1
})
