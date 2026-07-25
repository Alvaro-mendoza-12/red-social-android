const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

const apiUrl = String(process.env.API_URL || 'http://127.0.0.1:3080').replace(/\/+$/, '')
const runId = Date.now().toString()
const password = 'Conectate123!'
const mediaTestImage = process.env.MEDIA_TEST_IMAGE

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
      email: `module-social.${label}.${runId}@example.local`,
      password,
      displayName: `Social ${label}`,
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

async function uploadOwnedImage(token, bucket, label) {
  assert.ok(mediaTestImage, 'MEDIA_TEST_IMAGE is required')
  const bytes = fs.readFileSync(path.resolve(mediaTestImage))
  const objectPath = `social-module/${runId}-${label}.png`
  const response = await fetch(`${apiUrl}/storage/v1/object/${bucket}/${encodedObjectPath(objectPath)}`, {
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
  assert.equal(response.status, 200, `PUT ${bucket}: ${response.status} ${text}`)
  assert.ok(payload.path)
  assert.ok(payload.publicUrl)
  return payload
}

function createWaveBytes() {
  const sampleData = Buffer.alloc(800, 0)
  const bytes = Buffer.alloc(44 + sampleData.length)
  bytes.write('RIFF', 0)
  bytes.writeUInt32LE(bytes.length - 8, 4)
  bytes.write('WAVE', 8)
  bytes.write('fmt ', 12)
  bytes.writeUInt32LE(16, 16)
  bytes.writeUInt16LE(1, 20)
  bytes.writeUInt16LE(1, 22)
  bytes.writeUInt32LE(8000, 24)
  bytes.writeUInt32LE(8000, 28)
  bytes.writeUInt16LE(1, 32)
  bytes.writeUInt16LE(8, 34)
  bytes.write('data', 36)
  bytes.writeUInt32LE(sampleData.length, 40)
  sampleData.copy(bytes, 44)
  return bytes
}

async function uploadOwnedAudio(token, bucket, label) {
  const bytes = createWaveBytes()
  const objectPath = `social-module/${runId}-${label}.wav`
  const response = await fetch(`${apiUrl}/storage/v1/object/${bucket}/${encodedObjectPath(objectPath)}`, {
    method: 'PUT',
    headers: {
      authorization: `Bearer ${token}`,
      'content-type': 'audio/wav',
      'content-length': String(bytes.length),
    },
    body: bytes,
  })
  const text = await response.text()
  const payload = text ? JSON.parse(text) : {}
  assert.equal(response.status, 200, `PUT ${bucket}: ${response.status} ${text}`)
  assert.ok(payload.path)
  assert.ok(payload.publicUrl)
  return payload
}

async function deleteOwnedImage(token, bucket, storedObject) {
  await request(`/v1/storage/objects/${bucket}/${encodedObjectPath(storedObject.path)}`, {
    token,
    method: 'DELETE',
  })
}

async function main() {
  const owner = await register('owner')
  const viewer = await register('viewer')
  const commentMedia = await uploadOwnedAudio(viewer.token, 'post-comments-media', 'comment')
  const momentMedia = await uploadOwnedImage(owner.token, 'moment-media', 'moment')

  const firstPost = await request('/v1/data/feed/posts', {
    token: owner.token,
    method: 'POST',
    expected: 201,
    body: { content: `Primera ${runId}`, visibility: 'public' },
  })
  const secondPost = await request('/v1/data/feed/posts', {
    token: owner.token,
    method: 'POST',
    expected: 201,
    body: { content: `Segunda ${runId}`, visibility: 'public' },
  })
  const feed = await request('/v1/data/feed?limit=100', { token: viewer.token })
  const firstIndex = feed.items.findIndex((item) => item.id === firstPost.post.id)
  const secondIndex = feed.items.findIndex((item) => item.id === secondPost.post.id)
  assert.ok(secondIndex >= 0 && firstIndex >= 0 && secondIndex < firstIndex)

  await request(`/v1/data/feed/posts/${secondPost.post.id}/comments`, {
    token: viewer.token,
    method: 'POST',
    expected: 403,
    body: {
      content: '[Audio falso]',
      mediaUrl: `${apiUrl}/storage/v1/object/public/post-comments-media/test-${runId}.wav`,
      mediaType: 'audio',
    },
  })
  const audioComment = await request(`/v1/data/feed/posts/${secondPost.post.id}/comments`, {
    token: viewer.token,
    method: 'POST',
    expected: 201,
    body: {
      content: '[Audio]',
      mediaUrl: commentMedia.publicUrl,
      mediaType: 'audio',
    },
  })
  const commentId = audioComment.comment.id
  assert.equal(audioComment.comment.media_type, 'audio')
  await request(`/v1/data/feed/comments/${commentId}`, {
    token: viewer.token,
    method: 'PATCH',
    body: { content: 'Sticker editado' },
  })
  await request(`/v1/data/feed/comments/${commentId}`, {
    token: owner.token,
    method: 'PATCH',
    expected: 403,
    body: { content: 'No autorizado' },
  })
  await request(`/v1/data/feed/comments/${commentId}/reaction`, {
    token: owner.token,
    method: 'PUT',
    body: { reactionType: 'LIKE' },
  })
  await request(`/v1/data/feed/comments/${commentId}/reaction`, {
    token: viewer.token,
    method: 'PUT',
    body: { reactionType: 'DISLIKE' },
  })
  let detail = await request(`/v1/data/feed/posts/${secondPost.post.id}`, { token: viewer.token })
  let enrichedComment = detail.comments.find((item) => item.id === commentId)
  assert.equal(enrichedComment.reaction_counts.LIKE, 1)
  assert.equal(enrichedComment.reaction_counts.DISLIKE, 1)
  assert.equal(enrichedComment.my_reaction, 'DISLIKE')
  await request(`/v1/data/feed/comments/${commentId}/reaction`, { token: viewer.token, method: 'DELETE' })
  detail = await request(`/v1/data/feed/posts/${secondPost.post.id}`, { token: viewer.token })
  enrichedComment = detail.comments.find((item) => item.id === commentId)
  assert.equal(enrichedComment.reaction_counts.DISLIKE, 0)

  await request('/v1/data/moments', {
    token: owner.token,
    method: 'POST',
    expected: 403,
    body: {
      mediaUrl: `${apiUrl}/storage/v1/object/public/moment-media/test-${runId}.jpg`,
      mediaType: 'image',
    },
  })
  const moment = await request('/v1/data/moments', {
    token: owner.token,
    method: 'POST',
    expected: 201,
    body: {
      mediaUrl: momentMedia.publicUrl,
      mediaType: 'image',
      caption: 'Texto visible',
      durationHours: 24,
      visibility: 'all',
    },
  })
  const momentId = moment.moment.id
  const selfView = await request(`/v1/data/moments/${momentId}/view`, { token: owner.token, method: 'POST' })
  assert.equal(selfView.self_view_ignored, true)
  let viewers = await request(`/v1/data/moments/${momentId}/viewers`, { token: owner.token })
  assert.equal(viewers.items.length, 0)
  await request(`/v1/data/moments/${momentId}/view`, { token: viewer.token, method: 'POST' })
  viewers = await request(`/v1/data/moments/${momentId}/viewers`, { token: owner.token })
  assert.equal(viewers.items.length, 1)
  assert.equal(viewers.items[0].user_id, viewer.id)

  const notifications = await request('/v1/data/notifications', { token: owner.token })
  const notification = notifications.items[0]
  if (notification) {
    await request(`/v1/data/notifications/${notification.id}`, { token: owner.token, method: 'DELETE' })
  }
  await request('/v1/data/notifications', { token: owner.token, method: 'DELETE' })
  const emptyNotifications = await request('/v1/data/notifications', { token: owner.token })
  assert.equal(emptyNotifications.items.length, 0)

  await request(`/v1/data/moments/${momentId}`, { token: owner.token, method: 'DELETE' })
  await request(`/v1/data/feed/posts/${firstPost.post.id}`, { token: owner.token, method: 'DELETE' })
  await request(`/v1/data/feed/posts/${secondPost.post.id}`, { token: owner.token, method: 'DELETE' })
  await deleteOwnedImage(viewer.token, 'post-comments-media', commentMedia)
  await deleteOwnedImage(owner.token, 'moment-media', momentMedia)

  process.stdout.write(JSON.stringify({
    ok: true,
    newestPostsFirst: true,
    audioComments: true,
    forgedCommentMediaRejected: true,
    commentEditAuthorization: true,
    commentLikeDislike: true,
    selfMomentViewIgnored: true,
    forgedMomentMediaRejected: true,
    momentViewerRecorded: true,
    notificationsDeleteOneAndAll: true,
  }))
}

main().catch((error) => {
  console.error(error)
  process.exit(1)
})
