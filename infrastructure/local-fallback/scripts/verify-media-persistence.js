const assert = require('node:assert/strict')
const crypto = require('node:crypto')
const fs = require('node:fs')
const path = require('node:path')

const apiUrl = String(process.env.API_URL || 'http://127.0.0.1:3080').replace(/\/+$/, '')
const imageFile = process.env.MEDIA_TEST_IMAGE
const videoFile = process.env.MEDIA_TEST_VIDEO
const runId = Date.now().toString()
const email = `module-media.${runId}@example.local`
const displayName = `Media E2E ${runId}`
const password = 'Conectate123!'

function requiredFile(rawPath, label) {
  assert.ok(rawPath, `${label} is required`)
  const resolved = path.resolve(rawPath)
  assert.ok(fs.statSync(resolved).isFile(), `${label} is not a file: ${resolved}`)
  return resolved
}

function sha256(buffer) {
  return crypto.createHash('sha256').update(buffer).digest('hex')
}

function canonicalMediaUrl(rawUrl) {
  const parsed = new URL(String(rawUrl || ''))
  parsed.search = ''
  parsed.hash = ''
  return parsed.toString()
}

function assertSameMediaObject(actual, expected, label) {
  assert.equal(canonicalMediaUrl(actual), canonicalMediaUrl(expected), label)
}

function encodedObjectPath(objectPath) {
  return String(objectPath || '')
    .split('/')
    .filter(Boolean)
    .map(encodeURIComponent)
    .join('/')
}

async function jsonRequest(route, { token, method = 'GET', body, expected = 200 } = {}) {
  const startedAt = Date.now()
  const response = await fetch(`${apiUrl}${route}`, {
    method,
    headers: {
      ...(token ? { authorization: `Bearer ${token}` } : {}),
      ...(body == null ? {} : { 'content-type': 'application/json' }),
    },
    body: body == null ? undefined : JSON.stringify(body),
  })
  const text = await response.text()
  const payload = text ? JSON.parse(text) : {}
  assert.equal(response.status, expected, `${method} ${route}: ${response.status} ${text}`)
  return { payload, elapsedMs: Date.now() - startedAt }
}

async function uploadObject({ token, bucket, objectPath, contentType, bytes }) {
  const route = `/storage/v1/object/${encodeURIComponent(bucket)}/${encodedObjectPath(objectPath)}`
  const startedAt = Date.now()
  const response = await fetch(`${apiUrl}${route}`, {
    method: 'PUT',
    headers: {
      authorization: `Bearer ${token}`,
      'content-type': contentType,
      'content-length': String(bytes.length),
    },
    body: bytes,
  })
  const text = await response.text()
  const payload = text ? JSON.parse(text) : {}
  assert.equal(response.status, 200, `PUT ${route}: ${response.status} ${text}`)
  assert.ok(payload.path, `Storage response does not contain path: ${text}`)
  assert.ok(payload.publicUrl, `Storage response does not contain publicUrl: ${text}`)
  return { ...payload, elapsedMs: Date.now() - startedAt }
}

async function assertPublicObject({ url, expectedBytes, expectedContentType, range = false }) {
  const response = await fetch(url, {
    headers: range ? { range: `bytes=0-${Math.min(expectedBytes.length - 1, 1023)}` } : {},
  })
  assert.ok(
    range ? [200, 206].includes(response.status) : response.status === 200,
    `GET ${url}: ${response.status}`,
  )
  assert.ok(
    String(response.headers.get('content-type') || '').toLowerCase().startsWith(expectedContentType),
    `Unexpected content type: ${response.headers.get('content-type')}`,
  )
  const downloaded = Buffer.from(await response.arrayBuffer())
  const expected = range ? expectedBytes.subarray(0, downloaded.length) : expectedBytes
  assert.equal(sha256(downloaded), sha256(expected), 'Downloaded bytes differ from uploaded bytes')
  return downloaded.length
}

async function deleteObject(token, bucket, objectPath) {
  const route = `/v1/storage/objects/${encodeURIComponent(bucket)}/${encodedObjectPath(objectPath)}`
  await jsonRequest(route, { token, method: 'DELETE' })
}

async function main() {
  const imageBytes = fs.readFileSync(requiredFile(imageFile, 'MEDIA_TEST_IMAGE'))
  const videoBytes = fs.readFileSync(requiredFile(videoFile, 'MEDIA_TEST_VIDEO'))

  const registration = await jsonRequest('/v1/auth/register', {
    method: 'POST',
    expected: 201,
    body: { email, password, displayName },
  })
  const token = registration.payload.accessToken
  const userId = registration.payload.user.id
  assert.ok(token)
  assert.ok(userId)

  const avatar = await uploadObject({
    token,
    bucket: 'avatars',
    objectPath: `profile-e2e/${runId}-avatar.png`,
    contentType: 'image/png',
    bytes: imageBytes,
  })
  const cover = await uploadObject({
    token,
    bucket: 'avatars',
    objectPath: `profile-e2e/${runId}-cover.png`,
    contentType: 'image/png',
    bytes: imageBytes,
  })
  const thumbnail = await uploadObject({
    token,
    bucket: 'thumbnails',
    objectPath: `video-e2e/${runId}-thumbnail.png`,
    contentType: 'image/png',
    bytes: imageBytes,
  })

  const profileUpdate = await jsonRequest('/v1/data/profile/me', {
    token,
    method: 'PATCH',
    body: {
      avatarUrl: avatar.publicUrl,
      avatarMediaType: 'image',
      coverUrl: cover.publicUrl,
      profileWelcome: `Persistencia ${runId}`,
    },
  })
  assertSameMediaObject(profileUpdate.payload.profile.avatar_url, avatar.publicUrl, 'Updated avatar URL differs')
  assertSameMediaObject(profileUpdate.payload.profile.cover_url, cover.publicUrl, 'Updated cover URL differs')

  const ownProfile = await jsonRequest('/v1/data/profile/me', { token })
  assertSameMediaObject(ownProfile.payload.profile.avatar_url, avatar.publicUrl, 'Own avatar URL differs')
  assertSameMediaObject(ownProfile.payload.profile.cover_url, cover.publicUrl, 'Own cover URL differs')

  const aliases = [userId, email, email.split('@')[0], displayName]
  for (const alias of aliases) {
    const profile = await jsonRequest(`/v1/data/profiles/${encodeURIComponent(alias)}`, { token })
    assertSameMediaObject(profile.payload.profile.avatar_url, avatar.publicUrl, `Avatar missing for alias ${alias}`)
    assertSameMediaObject(profile.payload.profile.cover_url, cover.publicUrl, `Cover missing for alias ${alias}`)
  }

  await assertPublicObject({
    url: avatar.publicUrl,
    expectedBytes: imageBytes,
    expectedContentType: 'image/png',
  })
  await assertPublicObject({
    url: cover.publicUrl,
    expectedBytes: imageBytes,
    expectedContentType: 'image/png',
  })

  const video = await uploadObject({
    token,
    bucket: 'videos',
    objectPath: `video-e2e/${runId}.mp4`,
    contentType: 'video/mp4',
    bytes: videoBytes,
  })
  const rangedVideoBytes = await assertPublicObject({
    url: video.publicUrl,
    expectedBytes: videoBytes,
    expectedContentType: 'video/mp4',
    range: true,
  })

  const createdVideo = await jsonRequest('/v1/data/videos', {
    token,
    method: 'POST',
    expected: 201,
    body: {
      title: `Media E2E ${runId}`,
      description: 'Video real de verificacion de almacenamiento',
      videoUrl: video.publicUrl,
      thumbnailUrl: thumbnail.publicUrl,
      durationSeconds: 3,
      isPublic: true,
      tags: ['media-e2e'],
    },
  })
  const videoId = createdVideo.payload.video.id
  const videoDetail = await jsonRequest(`/v1/data/videos/${videoId}`, { token })
  assertSameMediaObject(videoDetail.payload.video.video_url, video.publicUrl, 'Video URL differs')
  assertSameMediaObject(videoDetail.payload.video.thumbnail_url, thumbnail.publicUrl, 'Thumbnail URL differs')

  await jsonRequest(`/v1/data/videos/${videoId}`, { token, method: 'DELETE' })
  await deleteObject(token, 'videos', video.path)
  await deleteObject(token, 'thumbnails', thumbnail.path)
  await deleteObject(token, 'avatars', cover.path)
  await deleteObject(token, 'avatars', avatar.path)

  process.stdout.write(JSON.stringify({
    ok: true,
    runId,
    email,
    profileAliasesVerified: aliases.length,
    imageBytes: imageBytes.length,
    videoBytes: videoBytes.length,
    rangedVideoBytes,
    avatarUploadMs: avatar.elapsedMs,
    coverUploadMs: cover.elapsedMs,
    profileSaveMs: profileUpdate.elapsedMs,
    videoUploadMs: video.elapsedMs,
    mediaBytesVerifiedBySha256: true,
    profileMediaPersisted: true,
    videoMetadataPersisted: true,
    storageObjectsDeleted: true,
  }))
}

main().catch((error) => {
  console.error(error.stack || error.message)
  process.exit(1)
})
