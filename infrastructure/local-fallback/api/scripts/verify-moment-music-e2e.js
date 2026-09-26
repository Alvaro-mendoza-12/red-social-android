'use strict'

const assert = require('node:assert/strict')
const crypto = require('node:crypto')
const fs = require('node:fs')
const os = require('node:os')
const path = require('node:path')
const { spawnSync } = require('node:child_process')

const apiUrl = String(process.env.API_URL || 'http://127.0.0.1:3001').replace(/\/+$/, '')
const runId = Date.now().toString()
const password = 'Conectate123!'
const cleanupTokens = []
const uploadedObjects = []

async function request(route, { token, method = 'GET', body, expected = 200 } = {}) {
  const response = await fetch(`${apiUrl}${route}`, {
    method,
    headers: {
      ...(token ? { authorization: `Bearer ${token}` } : {}),
      ...(body != null ? { 'content-type': 'application/json' } : {}),
    },
    body: body == null ? undefined : JSON.stringify(body),
  })
  const text = await response.text()
  const payload = text ? JSON.parse(text) : {}
  assert.equal(response.status, expected, `${method} ${route}: ${response.status} ${text}`)
  return payload
}

async function register(label) {
  const result = await request('/v1/auth/register', {
    method: 'POST',
    expected: 201,
    body: {
      email: `moment-music.${label}.${runId}@example.local`,
      password,
      displayName: `Moment music ${label}`,
    },
  })
  cleanupTokens.push(result.accessToken)
  return { id: result.user.id, token: result.accessToken }
}

function encodedObjectPath(objectPath) {
  return String(objectPath || '').split('/').filter(Boolean).map(encodeURIComponent).join('/')
}

async function upload(token, bucket, objectPath, contentType, bytes) {
  const response = await fetch(`${apiUrl}/storage/v1/object/${bucket}/${encodedObjectPath(objectPath)}`, {
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
  assert.equal(response.status, 200, `PUT ${bucket}: ${response.status} ${text}`)
  uploadedObjects.push({ token, bucket, objectPath: payload.path })
  return payload
}

async function assertPublishedMomentMediaProtected(token, objectPath, contentType, bytes) {
  const route = `/v1/storage/objects/moment-media/${encodedObjectPath(objectPath)}`
  const deniedDelete = await request(route, { token, method: 'DELETE', expected: 409 })
  assert.equal(deniedDelete.error, 'STORAGE_OBJECT_IN_USE')
  const overwrite = await fetch(`${apiUrl}/storage/v1/object/moment-media/${encodedObjectPath(objectPath)}`, {
    method: 'PUT',
    headers: {
      authorization: `Bearer ${token}`,
      'content-type': contentType,
      'content-length': String(bytes.length),
    },
    body: bytes,
  })
  assert.equal(overwrite.status, 409, `published media overwrite returned ${overwrite.status}`)
  assert.equal((await overwrite.json()).error, 'STORAGE_OBJECT_IN_USE')
}

function audibleWaveBytes({ seconds = 7, sampleRate = 44_100, frequency = 440 } = {}) {
  const sampleCount = Math.round(seconds * sampleRate)
  const dataSize = sampleCount * 2
  const bytes = Buffer.alloc(44 + dataSize)
  bytes.write('RIFF', 0)
  bytes.writeUInt32LE(bytes.length - 8, 4)
  bytes.write('WAVE', 8)
  bytes.write('fmt ', 12)
  bytes.writeUInt32LE(16, 16)
  bytes.writeUInt16LE(1, 20)
  bytes.writeUInt16LE(1, 22)
  bytes.writeUInt32LE(sampleRate, 24)
  bytes.writeUInt32LE(sampleRate * 2, 28)
  bytes.writeUInt16LE(2, 32)
  bytes.writeUInt16LE(16, 34)
  bytes.write('data', 36)
  bytes.writeUInt32LE(dataSize, 40)
  for (let index = 0; index < sampleCount; index += 1) {
    const sample = Math.round(Math.sin(2 * Math.PI * frequency * index / sampleRate) * 12_000)
    bytes.writeInt16LE(sample, 44 + index * 2)
  }
  return bytes
}

function inspectRenderedAudio(filePath) {
  const probe = spawnSync('ffprobe', [
    '-v', 'error',
    '-select_streams', 'a:0',
    '-show_entries', 'stream=codec_name,profile,sample_rate,channels:format=duration',
    '-of', 'json',
    filePath,
  ], { encoding: 'utf8', shell: false })
  assert.equal(probe.status, 0, probe.stderr || 'ffprobe failed')
  const payload = JSON.parse(probe.stdout)
  const stream = payload.streams?.[0]
  assert.equal(stream?.codec_name, 'aac')
  assert.match(String(stream?.profile || ''), /LC/i)
  assert.equal(Number(stream?.sample_rate), 44_100)
  assert.equal(Number(stream?.channels), 2)
  const durationSeconds = Number(payload.format?.duration)
  assert.ok(durationSeconds >= 5.95 && durationSeconds <= 6.10, `duration=${durationSeconds}`)

  const decode = spawnSync('ffmpeg', [
    '-nostdin', '-hide_banner', '-i', filePath,
    '-af', 'volumedetect', '-f', 'null', '-',
  ], { encoding: 'utf8', shell: false })
  assert.equal(decode.status, 0, decode.stderr || 'ffmpeg decode failed')
  const volume = /max_volume:\s*(-?[0-9.]+) dB/i.exec(decode.stderr || '')
  assert.ok(volume && Number.isFinite(Number(volume[1])), 'rendered Moment soundtrack is silent')
  return { durationSeconds, maxVolumeDb: Number(volume[1]) }
}

async function deleteAccount(token) {
  if (!token) return
  await request('/rest/v1/rpc/delete_own_account', {
    token,
    method: 'POST',
    body: {},
  }).catch(() => {})
}

async function main() {
  const owner = await register('owner')
  const viewer = await register('viewer')
  const imageBytes = Buffer.from(
    'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII=',
    'base64',
  )
  const image = await upload(
    owner.token,
    'moment-media',
    `${owner.id}/moment-music-e2e/${runId}.png`,
    'image/png',
    imageBytes,
  )
  const sourceAudio = await upload(
    owner.token,
    'community-music',
    `${owner.id}/moment-music-e2e/${runId}.wav`,
    'audio/wav',
    audibleWaveBytes(),
  )

  const created = await request('/v1/data/moments', {
    token: owner.token,
    method: 'POST',
    expected: 201,
    body: {
      mediaUrl: image.publicUrl,
      mediaType: 'image',
      visibility: 'all',
      displayDurationMs: 999,
      musicId: `device:${crypto.randomUUID()}`,
      musicTitle: 'Tono E2E audible',
      musicArtist: 'Novvin QA',
      musicSource: 'novvin_moment_local',
      musicAudioUrl: sourceAudio.publicUrl,
      musicStartMs: 0,
      musicDurationMs: 6000,
      musicEndMs: 6000,
      musicVolume: 1,
    },
  })
  const moment = created.moment
  await assertPublishedMomentMediaProtected(owner.token, image.path, 'image/png', imageBytes)
  assert.equal(Number(moment.display_duration_ms), 6000)
  assert.equal(Number(moment.music_start_ms), 0)
  assert.equal(Number(moment.music_duration_ms), 6000)
  assert.equal(Number(moment.music_end_ms), 6000)
  assert.match(String(moment.music_audio_url), /\/moments\/rendered\/[0-9a-f-]{36}\.m4a/i)
  assert.notEqual(moment.music_audio_url, sourceAudio.publicUrl)

  const feed = await request('/v1/data/moments/feed', { token: viewer.token })
  const visible = feed.items.find((item) => item.id === moment.id)
  assert.ok(visible, 'viewer did not receive the published Moment')
  assert.equal(Number(visible.display_duration_ms), 6000)
  assert.equal(Number(visible.music_duration_ms), 6000)
  assert.equal(visible.music_audio_url, moment.music_audio_url)

  const audioResponse = await fetch(moment.music_audio_url)
  assert.equal(audioResponse.status, 200)
  assert.match(String(audioResponse.headers.get('content-type')), /audio\/(mp4|m4a)/i)
  const tempRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'novvin-moment-e2e-'))
  const renderedPath = path.join(tempRoot, 'rendered.m4a')
  try {
    fs.writeFileSync(renderedPath, Buffer.from(await audioResponse.arrayBuffer()))
    const inspection = inspectRenderedAudio(renderedPath)
    const sourceVideoPath = path.join(tempRoot, 'source-video.mp4')
    const makeVideo = spawnSync('ffmpeg', [
      '-nostdin', '-hide_banner', '-loglevel', 'error', '-y',
      '-f', 'lavfi', '-i', 'color=c=blue:s=320x240:r=24',
      '-t', '6', '-an', '-c:v', 'libx264', '-pix_fmt', 'yuv420p',
      '-movflags', '+faststart', sourceVideoPath,
    ], { encoding: 'utf8', shell: false })
    assert.equal(makeVideo.status, 0, makeVideo.stderr || 'video fixture failed')
    const sourceVideo = await upload(
      owner.token,
      'moment-media',
      `${owner.id}/moment-music-e2e/${runId}.mp4`,
      'video/mp4',
      fs.readFileSync(sourceVideoPath),
    )
    const videoCreated = await request('/v1/data/moments', {
      token: owner.token,
      method: 'POST',
      expected: 201,
      body: {
        mediaUrl: sourceVideo.publicUrl,
        mediaType: 'video',
        visibility: 'all',
        displayDurationMs: 6000,
        musicId: `device:${crypto.randomUUID()}`,
        musicTitle: 'Tono E2E para video',
        musicArtist: 'Novvin QA',
        musicSource: 'novvin_moment_local',
        musicAudioUrl: sourceAudio.publicUrl,
        musicStartMs: 0,
        musicDurationMs: 6000,
        musicEndMs: 6000,
      },
    })
    const videoMoment = videoCreated.moment
    await assertPublishedMomentMediaProtected(owner.token, sourceVideo.path, 'video/mp4', fs.readFileSync(sourceVideoPath))
    assert.equal(videoMoment.media_url, sourceVideo.publicUrl)
    assert.match(String(videoMoment.music_audio_url), /\/moments\/rendered\/[0-9a-f-]{36}\.m4a/i)
    const videoFeed = await request('/v1/data/moments/feed', { token: viewer.token })
    assert.ok(videoFeed.items.some((item) => item.id === videoMoment.id && item.music_audio_url === videoMoment.music_audio_url))
    const playback = await fetch(videoMoment.media_url)
    assert.equal(playback.status, 200)
    assert.match(String(playback.headers.get('content-type')), /video\/mp4/i)
    const downloadedVideoPath = path.join(tempRoot, 'downloaded-video.mp4')
    fs.writeFileSync(downloadedVideoPath, Buffer.from(await playback.arrayBuffer()))
    const decodeVideo = spawnSync('ffmpeg', [
      '-nostdin', '-hide_banner', '-loglevel', 'error',
      '-i', downloadedVideoPath, '-f', 'null', '-',
    ], { encoding: 'utf8', shell: false })
    assert.equal(decodeVideo.status, 0, decodeVideo.stderr || 'published video is not decodable')

    await request(`/v1/data/moments/${videoMoment.id}`, { token: owner.token, method: 'DELETE' })
    await request(`/v1/data/moments/${moment.id}`, { token: owner.token, method: 'DELETE' })
    const deletedAudio = await fetch(moment.music_audio_url, { headers: { 'cache-control': 'no-cache' } })
    assert.equal(deletedAudio.status, 404, 'rendered soundtrack survived Moment deletion')
    const deletedVideoAudio = await fetch(videoMoment.music_audio_url, { headers: { 'cache-control': 'no-cache' } })
    assert.equal(deletedVideoAudio.status, 404, 'rendered video soundtrack survived Moment deletion')
    process.stdout.write(JSON.stringify({
      ok: true,
      exactImageDurationMs: 6000,
      exactMusicDurationMs: 6000,
      codec: 'aac-lc',
      sampleRate: 44_100,
      channels: 2,
      decodedAudible: true,
      durationSeconds: inspection.durationSeconds,
      maxVolumeDb: inspection.maxVolumeDb,
      viewerReceivedCanonicalAudio: true,
      videoMomentPublishedAndPlayable: true,
      videoMomentAudioPurgedOnDelete: true,
      publishedMomentMediaProtected: true,
      renderedAudioPurgedOnDelete: true,
    }))
  } finally {
    fs.rmSync(tempRoot, { recursive: true, force: true })
  }
}

main()
  .catch((error) => {
    console.error(error)
    process.exitCode = 1
  })
  .finally(async () => {
    for (const object of uploadedObjects.reverse()) {
      await request(`/v1/storage/objects/${object.bucket}/${encodedObjectPath(object.objectPath)}`, {
        token: object.token,
        method: 'DELETE',
      }).catch(() => {})
    }
    for (const token of cleanupTokens.reverse()) await deleteAccount(token)
  })
