'use strict'
// Mutates a newly registered synthetic account in a dedicated loopback database.
// Run against an isolated stack only. Media HTTPS may be mapped by a QA transport preload.
const assert = require('node:assert/strict')
const crypto = require('node:crypto')
const fs = require('node:fs')
const path = require('node:path')
const sharp = require('sharp')
const { Client } = require('pg')

const base = process.env.QA_API_URL || 'http://127.0.0.1:55731'
const database = new URL(process.env.QA_DATABASE_URL || '')
for (const url of [new URL(base), database]) {
  assert(['127.0.0.1', 'localhost', '[::1]'].includes(url.hostname), 'Only loopback QA services are allowed')
}
assert(/^novvin_.*(?:qa|124|test)/.test(database.pathname.slice(1)), 'A dedicated QA database is required')
const out = path.resolve(process.env.QA_SHARING_OUTPUT || 'build/public-sharing-qa')
const db = new Client({ connectionString: database.href })
const pass = []
const check = async (name, action) => { await action(); pass.push(name); console.log('PASS ' + name) }
async function request(route, token, body, method = body ? 'POST' : 'GET', headers = {}) {
  const response = await fetch(base + route, {
    method, headers: { ...(token ? { Authorization: 'Bearer ' + token } : {}), ...(body && !Buffer.isBuffer(body) ? { 'Content-Type': 'application/json' } : {}), ...headers },
    body: Buffer.isBuffer(body) ? body : body ? JSON.stringify(body) : undefined, signal: AbortSignal.timeout(15_000),
  })
  return response
}
async function json(route, token, body, method) {
  const response = await request(route, token, body, method), data = await response.json()
  assert(response.ok, route + ': ' + response.status + ' ' + (data.error || ''))
  return data
}
async function expectHidden(route) {
  for (const tail of ['share', 'share.jpg']) {
    const response = await request(route + '/' + tail)
    assert.equal(response.status, 404); assert.equal(response.headers.get('cache-control'), 'no-store')
    assert.equal(await response.text(), 'Contenido no disponible.')
  }
}
async function jpeg(route, filename, pixel) {
  const response = await request(route)
  assert.equal(response.status, 200); assert.match(response.headers.get('content-type'), /image\/jpeg/)
  assert.equal(response.headers.get('cache-control'), 'no-store')
  const bytes = Buffer.from(await response.arrayBuffer()), metadata = await sharp(bytes).metadata()
  assert.equal(metadata.width, 1200); assert.equal(metadata.height, 630)
  if (pixel) {
    const rgb = await sharp(bytes).extract({ left: pixel.x, top: pixel.y, width: 1, height: 1 }).raw().toBuffer()
    for (let channel = 0; channel < 3; channel++) assert(Math.abs(rgb[channel] - pixel.rgb[channel]) < 16, 'Expected the actual uploaded raster in the card')
  }
  fs.writeFileSync(path.join(out, filename), bytes)
}
;(async () => {
  fs.mkdirSync(out, { recursive: true }); await db.connect()
  const suffix = crypto.randomBytes(6).toString('hex'), email = 'share.' + suffix + '@example.invalid'
  const account = await json('/v1/auth/register', '', { email, password: 'Qa9!' + crypto.randomBytes(20).toString('hex'), displayName: 'Perfil de prueba', username: 'share_' + suffix, birthDate: '2000-01-14' })
  const token = account.accessToken
  assert(token, 'Registration must provide a session')
  const local = (await db.query('SELECT id::text FROM fallback_users WHERE email=$1', [email])).rows[0].id
  const owner = account.user.id
  const raster = await sharp({ create: { width: 320, height: 320, channels: 3, background: '#cb6633' } }).png().toBuffer()
  const urls = {}
  await check('real authenticated upload and anonymous public raster delivery', async () => {
    for (const bucket of ['avatars', 'post-media']) {
      const object = owner + '/share-qa-' + suffix + '.png'
      const response = await request('/storage/v1/object/' + bucket + '/' + object, token, raster, 'POST', { 'Content-Type': 'image/png', 'x-upsert': 'false' })
      assert(response.ok, 'Upload failed: ' + response.status + ' ' + await response.text())
      urls[bucket] = 'https://novvinapp.com/storage/v1/object/public/' + bucket + '/' + object
      const publicResponse = await request('/storage/v1/object/public/' + bucket + '/' + object)
      assert.equal(publicResponse.status, 200); assert.match(publicResponse.headers.get('content-type'), /image\/png/)
    }
  })
  await db.query("UPDATE fallback_profile_overrides SET profile=profile || $2::jsonb WHERE user_id=$1::uuid", [local, JSON.stringify({ avatar_url: urls.avatars, bio: 'Biografía pública de prueba', is_private: false, profile_visibility: 'everyone', break_mode: 'off' })])
  await check('public profile metadata and actual avatar card', async () => {
    const response = await request('/p/' + owner), html = await response.text()
    assert.equal(response.status, 200); assert(html.includes('Perfil de prueba')); assert(html.includes('Biografía pública de prueba'))
    assert(html.includes('card.jpg?v=5')); assert(!html.includes(email)); assert(!html.includes('conecta y comparte'))
    await jpeg('/p/' + owner + '/card.jpg', 'profile.jpg', { x: 234, y: 325, rgb: [203, 102, 51] })
  })
  const created = []
  for (const config of [{ visibility: 'public', mediaUrl: urls['post-media'], mediaType: 'image' }, { visibility: 'private' }, { visibility: 'public', isSensitive: true }]) {
    created.push((await json('/v1/data/feed/posts', token, { content: 'Contenido compartido ' + suffix, ...config })).post.id)
  }
  const post = created[0], route = '/v1/public/posts/' + post
  await check('public post HTML identifies the exact object; card embeds its uploaded photo', async () => {
    const response = await request(route + '/share'), html = await response.text()
    assert.equal(response.status, 200); assert(html.includes('Contenido compartido ' + suffix)); assert(html.includes('https://novvinapp.com/app/feed/post/' + post))
    assert(!html.includes('/novvin-share.png')); assert.equal(response.headers.get('x-ratelimit-limit'), '60')
    await jpeg(route + '/share.jpg', 'post.jpg', { x: 300, y: 300, rgb: [203, 102, 51] })
  })
  await check('private and sensitive posts deny both HTML and direct card requests', async () => {
    for (const id of created.slice(1)) await expectHidden('/v1/public/posts/' + id)
  })
  await check('changing a public post to private revokes its HTML and card', async () => {
    await json('/v1/data/feed/posts/' + post, token, { visibility: 'private' }, 'PATCH'); await expectHidden(route)
    await json('/v1/data/feed/posts/' + post, token, { visibility: 'public' }, 'PATCH')
  })
  const patchProfile = value => db.query('UPDATE fallback_profile_overrides SET profile=profile || $2::jsonb WHERE user_id=$1::uuid', [local, JSON.stringify(value)])
  await check('private, hidden and restricted creators revoke their public content previews', async () => {
    for (const config of [{ is_private: true }, { break_mode: 'hidden' }, { profile_visibility: 'followers' }]) {
      await patchProfile(config); await expectHidden(route)
      await patchProfile({ is_private: false, break_mode: 'off', profile_visibility: 'everyone' })
    }
  })
  await check('suspension denies profile and content HTML and cards', async () => {
    await db.query('INSERT INTO fallback_profile_moderation(user_id,is_suspended) VALUES($1,TRUE)', [owner])
    try {
      await expectHidden(route)
      for (const tail of ['', '/card.jpg']) assert.equal((await request('/p/' + owner + tail)).status, 404)
    } finally { await db.query('DELETE FROM fallback_profile_moderation WHERE user_id=$1', [owner]) }
  })
  const video = crypto.randomUUID()
  // A synthetic metadata row exercises the actual public reader; no video encode is asserted.
  await db.query('INSERT INTO fallback_videos(id,user_id,title,description,video_url,thumbnail_url,is_public) VALUES($1,$2,$3,$4,$5,$6,TRUE)', [video, owner, 'Video público de prueba', 'Descripción específica', 'https://novvinapp.com/storage/v1/object/public/videos/qa.mp4', urls['post-media']])
  await check('video metadata and thumbnail identify the video; privacy revokes both routes', async () => {
    const response = await request('/v1/public/videos/' + video + '/share'), html = await response.text()
    assert.equal(response.status, 200); assert(html.includes('Video público de prueba')); assert(html.includes('/app/reels/' + video))
    await jpeg('/v1/public/videos/' + video + '/share.jpg', 'video.jpg', { x: 300, y: 300, rgb: [203, 102, 51] })
    await db.query('UPDATE fallback_videos SET is_public=FALSE WHERE id=$1', [video]); await expectHidden('/v1/public/videos/' + video)
  })
  await check('deleting a post revokes its metadata and direct card', async () => {
    await json('/v1/data/feed/posts/' + post, token, undefined, 'DELETE'); await expectHidden(route)
  })
  fs.writeFileSync(path.join(out, 'http-postgres-results.json'), JSON.stringify({ pass, environment: 'Isolated loopback API/PostgreSQL; owned HTTPS raster fetch mapped to local storage by QA preload', fixtures: { owner, post, video }, physicalWhatsAppTest: false }, null, 2))
})().catch(error => { console.error(error.message); process.exitCode = 1 }).finally(() => db.end())
