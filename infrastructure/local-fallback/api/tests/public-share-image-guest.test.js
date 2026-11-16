'use strict'
const test = require('node:test')
const assert = require('node:assert/strict')
const sharp = require('sharp')
const jwt = require('jsonwebtoken')
const { readPublicShareImage } = require('../src/public-share-image')
const { verifiedGuestStorageIdentity } = require('../src/guest-storage-read')

const origin = 'https://novvinapp.com'
const secret = 'qa171-public-share-guest-fixture-not-a-production-key'
const issuer = 'qa171-public-share'
const guest = { guestAccess: true, guestSecret: secret, guestIssuer: issuer }
function identity(input) {
  return verifiedGuestStorageIdentity({ headers: input.headers || {} }, { secret, issuer })
}

test('a protected public raster uses a short guest identity without a member session', async () => {
  const original = global.fetch
  const png = await sharp({ create: { width: 16, height: 16, channels: 3, background: '#2168b5' } }).png().toBuffer()
  let headers
  global.fetch = async (_url, input) => {
    headers = input.headers
    return identity(input)
      ? new Response(png, { headers: { 'content-type': 'image/png' } })
      : new Response('guest authorization required', { status: 401 })
  }
  try {
    const image = await readPublicShareImage(origin + '/storage/v1/object/public/avatars/owner/photo.png', origin, guest)
    assert.ok(image, 'The public card cannot load its raster without a guest identity')
    assert.equal((await sharp(image).metadata()).format, 'jpeg')
    const claims = jwt.verify(headers.authorization.replace(/^Bearer /, ''), secret, { issuer, audience: 'novvin-guests' })
    assert.equal(claims.role, 'guest')
    assert.match(claims.sub, /^guest_[0-9a-f-]{36}$/)
    assert.ok(claims.exp - claims.iat <= 60)
    assert.equal(headers.cookie, undefined)
    assert.equal(claims.session_id, undefined)
  } finally { global.fetch = original }
})

test('a video avatar asks storage for its bounded poster rather than decoding the MP4 as an image', async () => {
  const original = global.fetch
  const webp = await sharp({ create: { width: 32, height: 24, channels: 3, background: '#2168b5' } }).webp().toBuffer()
  global.fetch = async (raw, input) => {
    const url = new URL(raw)
    const poster = identity(input) && url.searchParams.get('poster') === '1' &&
      url.searchParams.get('width') === '384' && url.searchParams.get('quality') === '86'
    return poster
      ? new Response(webp, { headers: { 'content-type': 'image/webp' } })
      : new Response('mp4 fixture', { headers: { 'content-type': 'video/mp4' } })
  }
  try {
    const image = await readPublicShareImage(origin + '/storage/v1/object/public/avatars/owner/clip.mp4', origin,
      { ...guest, thumbnail: 'avatar' })
    assert.ok(image, 'An avatar video needs the image poster returned by storage')
    assert.equal((await sharp(image).metadata()).format, 'jpeg')
  } finally { global.fetch = original }
})

test('guest mode still refuses untrusted, private and signed locators before any network request', async () => {
  const original = global.fetch
  let requests = 0
  global.fetch = async () => { requests++; throw Error('must not request this locator') }
  try {
    for (const raw of ['https://attacker.test/image.png', origin + '/storage/v1/object/private/chat.png',
      origin + '/storage/v1/object/public/avatars/a.png?access_token=fixture',
      'https://member:fixture@novvinapp.com/storage/v1/object/public/avatars/a.png']) {
      assert.equal(await readPublicShareImage(raw, origin, { ...guest, thumbnail: 'avatar' }), null)
    }
    assert.equal(requests, 0)
  } finally { global.fetch = original }
})

test('a storage refusal remains a refusal even when the public share reader requests an image', async () => {
  const original = global.fetch
  let requests = 0
  global.fetch = async () => { requests++; return new Response('not public', { status: 403 }) }
  try {
    assert.equal(await readPublicShareImage(origin + '/storage/v1/object/public/avatars/owner/private.png', origin, guest), null)
    assert.equal(requests, 1)
  } finally { global.fetch = original }
})

test('an approved local transport does not depend on the container reaching its own public funnel', async () => {
  const original = global.fetch
  const png = await sharp({ create: { width: 16, height: 16, channels: 3, background: '#2168b5' } }).png().toBuffer()
  let destination
  global.fetch = async (raw, input) => {
    destination = new URL(raw)
    if (destination.origin !== 'http://127.0.0.1:3001') throw new TypeError('fixture public funnel connection refused')
    return identity(input)
      ? new Response(png, { headers: { 'content-type': 'image/png' } })
      : new Response('guest authorization required', { status: 401 })
  }
  try {
    const image = await readPublicShareImage(origin + '/storage/v1/object/public/avatars/owner/photo.png', origin,
      { ...guest, localStorageOrigin: 'http://127.0.0.1:3001' })
    assert.ok(image, 'The card reader must use its local storage endpoint after validating the public reference')
    assert.equal(destination.pathname, '/storage/v1/object/public/avatars/owner/photo.png')
  } finally { global.fetch = original }
})

test('a transport option cannot send guest credentials to another service or an admin path', async () => {
  const original = global.fetch
  const requested = []
  global.fetch = async raw => { requested.push(new URL(raw)); return new Response('denied', { status: 403 }) }
  try {
    for (const localStorageOrigin of ['http://169.254.169.254', 'https://attacker.test',
      'http://127.0.0.1:3001/admin', 'http://user:fixture@127.0.0.1:3001', 'http://localhost:3001',
      'http://edge:8081', 'http://edge.evil.test:8080', 'https://edge:8080']) {
      await readPublicShareImage(origin + '/storage/v1/object/public/avatars/owner/photo.png', origin,
        { ...guest, localStorageOrigin })
    }
    assert.ok(requested.every(url => url.origin === origin))
  } finally { global.fetch = original }
})

test('local delivery still respects the storage refusal for a non-public object', async () => {
  const original = global.fetch
  global.fetch = async () => new Response('not public', { status: 403 })
  try {
    assert.equal(await readPublicShareImage(origin + '/storage/v1/object/public/avatars/owner/private.png', origin,
      { ...guest, localStorageOrigin: 'http://127.0.0.1:3001' }), null)
  } finally { global.fetch = original }
})

test('accelerated storage is read through the fixed internal edge that serves the file body', async () => {
  const original = global.fetch
  const png = await sharp({ create: { width: 16, height: 16, channels: 3, background: '#2168b5' } }).png().toBuffer()
  global.fetch = async (raw, input) => {
    const url = new URL(raw)
    return url.origin === 'http://edge:8080' && identity(input)
      ? new Response(png, { headers: { 'content-type': 'image/png' } })
      : new Response('not delivered', { status: 403 })
  }
  try {
    assert.ok(await readPublicShareImage(origin + '/storage/v1/object/public/avatars/owner/photo.png', origin,
      { ...guest, localStorageOrigin: 'http://edge:8080' }))
  } finally { global.fetch = original }
})
