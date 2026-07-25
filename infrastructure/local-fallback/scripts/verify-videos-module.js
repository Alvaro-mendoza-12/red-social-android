const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const WebSocket = require('../../../Conectate-web/node_modules/ws')

const apiUrl = String(process.env.API_URL || 'http://127.0.0.1:3080').replace(/\/+$/, '')
const runId = Date.now().toString()
const password = 'Conectate123!'
const mediaTestImage = process.env.MEDIA_TEST_IMAGE
const mediaTestVideo = process.env.MEDIA_TEST_VIDEO

async function request(path, { token, method = 'GET', body, expected = 200 } = {}) {
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

async function register(label) {
  const auth = await request('/v1/auth/register', {
    method: 'POST',
    expected: 201,
    body: {
      email: `module-videos.${label}.${runId}@example.local`,
      password,
      displayName: `Videos ${label}`,
    },
  })
  return { id: auth.user.id, token: auth.accessToken }
}

function encodedObjectPath(objectPath) {
  return String(objectPath || '')
    .split('/')
    .filter(Boolean)
    .map(encodeURIComponent)
    .join('/')
}

async function uploadOwnedVideo(token, label) {
  assert.ok(mediaTestVideo, 'MEDIA_TEST_VIDEO is required')
  const bytes = fs.readFileSync(path.resolve(mediaTestVideo))
  const objectPath = `video-module/${runId}-${label}.mp4`
  const response = await fetch(`${apiUrl}/storage/v1/object/videos/${encodedObjectPath(objectPath)}`, {
    method: 'PUT',
    headers: {
      authorization: `Bearer ${token}`,
      'content-type': 'video/mp4',
      'content-length': String(bytes.length),
    },
    body: bytes,
  })
  const text = await response.text()
  const payload = text ? JSON.parse(text) : {}
  assert.equal(response.status, 200, `PUT video ${label}: ${response.status} ${text}`)
  assert.ok(payload.path)
  assert.ok(payload.publicUrl)
  return payload
}

async function uploadOwnedSticker(token) {
  assert.ok(mediaTestImage, 'MEDIA_TEST_IMAGE is required')
  const bytes = fs.readFileSync(path.resolve(mediaTestImage))
  const objectPath = `video-module/${runId}-sticker.png`
  const response = await fetch(`${apiUrl}/storage/v1/object/chat-media/${encodedObjectPath(objectPath)}`, {
    method: 'PUT',
    headers: {
      authorization: `Bearer ${token}`,
      'content-type': 'image/png',
      'content-length': String(bytes.length),
    },
    body: bytes,
  })
  const text = await response.text()
  const payload = text ? JSON.parse(text) : {}
  assert.equal(response.status, 200, `PUT sticker: ${response.status} ${text}`)
  assert.ok(payload.path)
  assert.ok(payload.publicUrl)
  return payload
}

async function deleteOwnedVideo(token, storedObject) {
  await request(`/v1/storage/objects/videos/${encodedObjectPath(storedObject.path)}`, {
    token,
    method: 'DELETE',
  })
}

async function deleteOwnedSticker(token, storedObject) {
  await request(`/v1/storage/objects/chat-media/${encodedObjectPath(storedObject.path)}`, {
    token,
    method: 'DELETE',
  })
}

function subscribe(token, channel) {
  const socket = new WebSocket(`${apiUrl.replace(/^http/, 'ws')}/realtime/?token=${encodeURIComponent(token)}`)
  return new Promise((resolve, reject) => {
    const timeout = setTimeout(() => reject(new Error(`Subscription timeout: ${channel}`)), 12_000)
    socket.on('open', () => socket.send(JSON.stringify({ type: 'subscribe', channel })))
    socket.on('message', (raw) => {
      const message = JSON.parse(raw.toString())
      if (message.type === 'subscribed' && message.channel === channel) {
        clearTimeout(timeout)
        resolve(socket)
      }
    })
    socket.on('error', reject)
  })
}

function waitForEvent(socket, channel, eventName) {
  return new Promise((resolve, reject) => {
    const timeout = setTimeout(() => reject(new Error(`Event timeout: ${eventName}`)), 10_000)
    const listener = (raw) => {
      const message = JSON.parse(raw.toString())
      if (message.type === 'event' && message.channel === channel && message.payload?.event === eventName) {
        clearTimeout(timeout)
        socket.off('message', listener)
        resolve(message.payload)
      }
    }
    socket.on('message', listener)
  })
}

async function main() {
  const owner = await register('owner')
  const viewer = await register('viewer')
  const publicVideoObject = await uploadOwnedVideo(owner.token, 'public')
  const privateVideoObject = await uploadOwnedVideo(owner.token, 'private')
  const personalizedVideoObject = await uploadOwnedVideo(owner.token, 'personalized')
  const stickerObject = await uploadOwnedSticker(owner.token)
  const personalizedInterest = `astrocodex${runId}`
  const created = await request('/v1/data/videos', {
    token: owner.token,
    method: 'POST',
    expected: 201,
    body: {
      title: `Video module ${runId}`,
      description: 'Descripción visible debajo del usuario',
      videoUrl: publicVideoObject.publicUrl,
      durationSeconds: 15,
      isPublic: true,
      tags: ['musica', 'nuevo'],
    },
  })
  const videoId = created.video.id
  assert.ok(videoId)
  assert.ok(created.video.created_at)
  assert.equal(created.video.description, 'Descripción visible debajo del usuario')

  const privateVideo = await request('/v1/data/videos', {
    token: owner.token,
    method: 'POST',
    expected: 201,
    body: {
      title: `Private video ${runId}`,
      videoUrl: privateVideoObject.publicUrl,
      durationSeconds: 10,
      isPublic: false,
    },
  })
  const personalizedVideo = await request('/v1/data/videos', {
    token: owner.token,
    method: 'POST',
    expected: 201,
    body: {
      title: `Exploración ${personalizedInterest}`,
      description: `Contenido dedicado a ${personalizedInterest}`,
      videoUrl: personalizedVideoObject.publicUrl,
      durationSeconds: 12,
      isPublic: true,
      tags: [personalizedInterest],
    },
  })
  await request(`/v1/data/videos/${privateVideo.video.id}/comments`, {
    token: viewer.token,
    expected: 404,
  })
  await request(`/v1/data/videos/${privateVideo.video.id}`, { token: viewer.token, expected: 404 })
  await request(`/v1/data/videos/${privateVideo.video.id}/favorite`, {
    token: viewer.token,
    method: 'POST',
    expected: 404,
  })
  await request(`/v1/data/videos/${privateVideo.video.id}`, { token: owner.token, method: 'DELETE' })

  await request(`/v1/data/videos/${videoId}/favorite`, { token: viewer.token, method: 'POST' })
  let detail = await request(`/v1/data/videos/${videoId}`, { token: viewer.token })
  assert.equal(detail.video.favorited_by_me, true)
  assert.equal(detail.video.shared_by_me, false)

  const channel = `video-comments:${videoId}`
  const socket = await subscribe(owner.token, channel)
  const createdEvent = waitForEvent(socket, channel, 'video_comment_created')
  const commentResponse = await request(`/v1/data/videos/${videoId}/comments`, {
    token: viewer.token,
    method: 'POST',
    expected: 201,
    body: { content: 'Comentario editable', contentType: 'text' },
  })
  const commentId = commentResponse.comment.id
  assert.equal((await createdEvent).commentId, commentId)

  await request(`/v1/data/videos/comments/${commentId}/reaction`, {
    token: owner.token,
    method: 'PUT',
    body: { reactionType: 'LOVE' },
  })
  await request(`/v1/data/videos/comments/${commentId}`, {
    token: viewer.token,
    method: 'PATCH',
    body: { content: 'Comentario editado' },
  })
  await request(`/v1/data/videos/comments/${commentId}`, {
    token: owner.token,
    method: 'PATCH',
    body: { content: 'No permitido' },
    expected: 403,
  })
  await request(`/v1/data/videos/comments/${commentId}/report`, {
    token: owner.token,
    method: 'POST',
    expected: 201,
    body: { reason: 'spam', details: 'prueba' },
  })
  await request(`/v1/data/videos/comments/${commentId}/report`, {
    token: owner.token,
    method: 'POST',
    expected: 409,
    body: { reason: 'spam' },
  })
  await request(`/v1/data/videos/${videoId}/comments`, {
    token: owner.token,
    method: 'POST',
    expected: 403,
    body: { content: 'Sticker falso', contentType: 'sticker', mediaUrl: `${apiUrl}/sticker-test.webp` },
  })
  const sticker = await request(`/v1/data/videos/${videoId}/comments`, {
    token: owner.token,
    method: 'POST',
    expected: 201,
    body: { content: 'Sticker', contentType: 'sticker', mediaUrl: stickerObject.publicUrl },
  })
  assert.equal(sticker.comment.content_type, 'sticker')

  const comments = await request(`/v1/data/videos/${videoId}/comments`, { token: owner.token })
  const edited = comments.items.find((item) => item.id === commentId)
  assert.equal(edited.content, 'Comentario editado')
  assert.equal(edited.reactions.LOVE, 1)
  assert.equal(edited.my_reaction, 'LOVE')

  await request(`/v1/data/videos/comments/${commentId}`, { token: owner.token, method: 'DELETE' })
  await request(`/v1/data/videos/${videoId}/favorite`, { token: viewer.token, method: 'DELETE' })
  detail = await request(`/v1/data/videos/${videoId}`, { token: viewer.token })
  assert.equal(detail.video.favorited_by_me, false)
  await request(`/v1/data/videos/${videoId}/view`, { token: viewer.token, method: 'POST' })
  await request('/v1/data/profile/me', {
    token: viewer.token,
    method: 'PATCH',
    body: { interests: [personalizedInterest] },
  })
  const recommendedA = await request('/v1/data/videos?recommended=true&limit=20', { token: viewer.token })
  const recommendedB = await request('/v1/data/videos?recommended=true&limit=20', { token: viewer.token })
  assert.deepEqual(
    recommendedA.items.map((item) => item.id),
    recommendedB.items.map((item) => item.id),
  )
  const personalizedIndex = recommendedA.items.findIndex((item) => item.id === personalizedVideo.video.id)
  const baselineIndex = recommendedA.items.findIndex((item) => item.id === videoId)
  assert.ok(personalizedIndex >= 0, 'personalized video must be present in recommendations')
  assert.ok(baselineIndex >= 0, 'baseline video must be present in recommendations')
  assert.ok(personalizedIndex < baselineIndex, 'profile interest must promote the matching video')
  await request(`/v1/data/videos/${personalizedVideo.video.id}`, { token: owner.token, method: 'DELETE' })
  await request(`/v1/data/videos/${videoId}`, { token: owner.token, method: 'DELETE' })
  await deleteOwnedVideo(owner.token, privateVideoObject)
  await deleteOwnedVideo(owner.token, publicVideoObject)
  await deleteOwnedVideo(owner.token, personalizedVideoObject)
  await deleteOwnedSticker(owner.token, stickerObject)
  socket.close()

  process.stdout.write(JSON.stringify({
    ok: true,
    favoriteIndependent: true,
    commentRealtime: true,
    reactions: true,
    editWindow: true,
    reports: true,
    stickers: true,
    forgedStickerReferenceRejected: true,
    recommendationsStable: true,
    profileInterestsAffectRecommendations: true,
    privateVideoProtected: true,
  }))
}

main().catch((error) => {
  console.error(error)
  process.exit(1)
})
