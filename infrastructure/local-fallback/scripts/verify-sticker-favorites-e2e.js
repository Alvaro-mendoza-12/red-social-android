const assert = require('node:assert/strict')
const { randomUUID } = require('node:crypto')

const baseUrl = String(process.argv[2] || process.env.API_URL || 'http://127.0.0.1:3080').replace(/\/+$/, '')
let token
let objectPath

async function request(path, body, expected = 200) {
  const response = await fetch(`${baseUrl}${path}`, {
    method: 'POST', signal: AbortSignal.timeout(15000),
    headers: { 'content-type': 'application/json', ...(token ? { authorization: `Bearer ${token}` } : {}) },
    body: JSON.stringify(body),
  })
  const payload = await response.json()
  assert.equal(response.status, expected, `${path}: ${response.status} ${JSON.stringify(payload)}`)
  return payload
}

async function main() {
  try {
    const account = await request('/v1/auth/register', {
      email: `sticker-check.${randomUUID()}@example.local`,
      password: `Check-${randomUUID()}!`, displayName: 'Verificación de stickers',
    }, 201)
    token = account.accessToken
    assert.ok(token)
    const uploadPath = `sticker-check/${randomUUID()}.png`
    const upload = await fetch(`${baseUrl}/storage/v1/object/chat-media/${uploadPath}`, {
      method: 'POST', signal: AbortSignal.timeout(15000),
      headers: { authorization: `Bearer ${token}`, 'content-type': 'image/png' },
      body: Buffer.from('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aD1cAAAAASUVORK5CYII=', 'base64'),
    })
    assert.equal(upload.status, 200)
    const uploaded = await upload.json()
    objectPath = uploaded.path
    assert.ok(objectPath)
    const stickerId = `message_${randomUUID()}`
    const favorite = {
      p_sticker_id: stickerId, p_favorite: true,
      p_media_url: `${baseUrl}/storage/v1/object/public/chat-media/${objectPath}`,
      p_title: 'Prueba de favorito', p_is_animated: false,
    }
    const rpc = '/rest/v1/rpc/secure_toggle_sticker_favorite'
    for (let i = 0; i < 2; i++) assert.equal((await request(rpc, favorite)).is_favorite, true)
    const saved = await request('/rest/v1/rpc/secure_search_stickers', { p_query: '', p_limit: 250 })
    const matches = saved.filter(item => item.id === stickerId)
    assert.equal(matches.length, 1)
    assert.equal(matches[0].is_favorite, true)
    console.log('PASS: repetir añadir conserva un único favorito registrado en el servidor')
    for (let i = 0; i < 2; i++) {
      assert.equal((await request(rpc, { p_sticker_id: stickerId, p_favorite: false })).is_favorite, false)
    }
    const removed = await request('/rest/v1/rpc/secure_search_stickers', { p_query: '', p_limit: 250 })
    assert.equal(removed.find(item => item.id === stickerId)?.is_favorite, false)
    console.log('PASS: repetir quitar conserva el estado eliminado al volver a consultar')
  } finally {
    if (token) {
      // Account deletion cleans its storage and private catalog in one transaction.
      await request('/rest/v1/rpc/delete_own_account', {})
      console.log('CLEANUP: cuenta y archivo sintéticos eliminados')
    }
  }
}

main().catch(error => { console.error(error.message); process.exitCode = 1 })
