const assert = require('node:assert/strict')
const crypto = require('node:crypto')
const fs = require('node:fs')
const path = require('node:path')

const apiUrl = String(process.env.API_URL || 'http://127.0.0.1:3080').replace(/\/+$/, '')
const imageFile = path.resolve(String(process.env.MEDIA_TEST_IMAGE || ''))
const videoFile = path.resolve(String(process.env.MEDIA_TEST_VIDEO || ''))
const runId = Date.now().toString()
const password = 'Conectate123!'

const imageBuckets = [
  'avatars',
  'chat-media',
  'group-media',
  'community-media',
  'groups',
  'communities',
  'post-media',
  'moment-media',
  'thumbnails',
  'challenges',
  'report-evidence',
]

const videoBuckets = [
  'avatars',
  'chat-media',
  'group-media',
  'community-media',
  'groups',
  'communities',
  'post-media',
  'moment-media',
  'videos',
  'challenges',
]

const audioBuckets = [
  'chat-media',
  'group-media',
  'community-media',
  'groups',
  'communities',
  'challenges',
  'video-comments-media',
  'post-comments-media',
  'group-audio',
]

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

function sha256(buffer) {
  return crypto.createHash('sha256').update(buffer).digest('hex')
}

function encodedObjectPath(objectPath) {
  return String(objectPath || '')
    .split('/')
    .filter(Boolean)
    .map(encodeURIComponent)
    .join('/')
}

async function jsonRequest(route, { token, method = 'GET', body, expected = 200 } = {}) {
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
  return payload
}

async function upload(token, bucket, suffix, contentType, bytes, expected = 200) {
  const requestedPath = `bucket-matrix/${runId}-${suffix}`
  const startedAt = Date.now()
  const response = await fetch(
    `${apiUrl}/storage/v1/object/${encodeURIComponent(bucket)}/${encodedObjectPath(requestedPath)}`,
    {
      method: 'PUT',
      headers: {
        authorization: `Bearer ${token}`,
        'content-type': contentType,
        'content-length': String(bytes.length),
      },
      body: bytes,
    },
  )
  const text = await response.text()
  const payload = text ? JSON.parse(text) : {}
  assert.equal(response.status, expected, `PUT ${bucket}/${suffix}: ${response.status} ${text}`)
  return { payload, elapsedMs: Date.now() - startedAt }
}

async function verifyDownload(publicUrl, expectedBytes, contentType, range) {
  const response = await fetch(publicUrl, {
    headers: range ? { range: `bytes=0-${Math.min(expectedBytes.length - 1, 1023)}` } : {},
  })
  assert.ok(range ? [200, 206].includes(response.status) : response.status === 200)
  assert.ok(String(response.headers.get('content-type') || '').toLowerCase().startsWith(contentType))
  const actual = Buffer.from(await response.arrayBuffer())
  const expected = range ? expectedBytes.subarray(0, actual.length) : expectedBytes
  assert.equal(sha256(actual), sha256(expected))
}

async function deleteObject(token, bucket, objectPath) {
  await jsonRequest(`/v1/storage/objects/${encodeURIComponent(bucket)}/${encodedObjectPath(objectPath)}`, {
    token,
    method: 'DELETE',
  })
}

function assertCanonicalObjectUrl(bucket, objectPath, publicUrl) {
  const marker = `/storage/v1/object/public/${encodeURIComponent(bucket)}/`
  const parsed = new URL(publicUrl)
  assert.ok(parsed.pathname.startsWith(marker), `Unexpected public URL: ${publicUrl}`)
  assert.ok(!parsed.pathname.includes(`/${bucket}/${bucket}/`), `Duplicated bucket in URL: ${publicUrl}`)
  assert.ok(parsed.pathname.endsWith(encodedObjectPath(objectPath)), `Unexpected object path: ${publicUrl}`)
}

async function main() {
  assert.ok(fs.statSync(imageFile).isFile(), `MEDIA_TEST_IMAGE is not a file: ${imageFile}`)
  assert.ok(fs.statSync(videoFile).isFile(), `MEDIA_TEST_VIDEO is not a file: ${videoFile}`)
  const imageBytes = fs.readFileSync(imageFile)
  const videoBytes = fs.readFileSync(videoFile)
  const audioBytes = createWaveBytes()

  const registration = await jsonRequest('/v1/auth/register', {
    method: 'POST',
    expected: 201,
    body: {
      email: `module-media-matrix.${runId}@example.local`,
      password,
      displayName: `Media Matrix ${runId}`,
    },
  })
  const token = registration.accessToken
  const results = []

  for (const bucket of imageBuckets) {
    const uploaded = await upload(token, bucket, `${bucket}-image.png`, 'image/png', imageBytes)
    assert.ok(uploaded.payload.path)
    assert.ok(uploaded.payload.publicUrl)
    assertCanonicalObjectUrl(bucket, uploaded.payload.path, uploaded.payload.publicUrl)
    await verifyDownload(uploaded.payload.publicUrl, imageBytes, 'image/png', false)
    await deleteObject(token, bucket, uploaded.payload.path)
    results.push({ bucket, type: 'image', uploadMs: uploaded.elapsedMs })
  }

  for (const bucket of videoBuckets) {
    const uploaded = await upload(token, bucket, `${bucket}-video.mp4`, 'video/mp4', videoBytes)
    assert.ok(uploaded.payload.path)
    assert.ok(uploaded.payload.publicUrl)
    assertCanonicalObjectUrl(bucket, uploaded.payload.path, uploaded.payload.publicUrl)
    await verifyDownload(uploaded.payload.publicUrl, videoBytes, 'video/mp4', true)
    await deleteObject(token, bucket, uploaded.payload.path)
    results.push({ bucket, type: 'video', uploadMs: uploaded.elapsedMs })
  }

  for (const bucket of audioBuckets) {
    const uploaded = await upload(token, bucket, `${bucket}-audio.wav`, 'audio/wav', audioBytes)
    assert.ok(uploaded.payload.path)
    assert.ok(uploaded.payload.publicUrl)
    assertCanonicalObjectUrl(bucket, uploaded.payload.path, uploaded.payload.publicUrl)
    await verifyDownload(uploaded.payload.publicUrl, audioBytes, 'audio/wav', false)
    await deleteObject(token, bucket, uploaded.payload.path)
    results.push({ bucket, type: 'audio', uploadMs: uploaded.elapsedMs })
  }

  const mismatch = await upload(token, 'videos', 'declared-video.png', 'video/mp4', imageBytes, 415)
  assert.equal(mismatch.payload.error, 'UNSUPPORTED_OR_MISMATCHED_MEDIA_TYPE')

  process.stdout.write(JSON.stringify({
    ok: true,
    runId,
    testedUploads: results.length,
    imageBuckets: imageBuckets.length,
    videoBuckets: videoBuckets.length,
    audioBuckets: audioBuckets.length,
    mismatchedTypeRejected: true,
    sha256DownloadsMatched: true,
    duplicatedBucketUrls: false,
    results,
  }))
}

main().catch((error) => {
  console.error(error.stack || error.message)
  process.exit(1)
})
