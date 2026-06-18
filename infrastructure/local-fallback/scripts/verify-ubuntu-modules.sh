#!/usr/bin/env bash
set -Eeuo pipefail

API_URL="${API_URL:-http://127.0.0.1:3080}"
TEST_EMAIL="${TEST_EMAIL:-codex-smoke-ubuntu@example.local}"
TEST_PASSWORD="${TEST_PASSWORD:-Conectate123!}"

echo "==> Verificando modulos Ubuntu"
echo "API_URL=$API_URL"
echo "TEST_EMAIL=$TEST_EMAIL"

API_URL="$API_URL" TEST_EMAIL="$TEST_EMAIL" TEST_PASSWORD="$TEST_PASSWORD" node <<'NODE'
const fs = require('fs')
const os = require('os')
const path = require('path')

const apiUrl = String(process.env.API_URL || '').replace(/\/+$/, '')
const email = process.env.TEST_EMAIL
const password = process.env.TEST_PASSWORD

function assert(condition, message) {
  if (!condition) throw new Error(message)
}

async function jsonRequest(pathname, options = {}) {
  const response = await fetch(`${apiUrl}${pathname}`, {
    ...options,
    headers: {
      'content-type': 'application/json',
      'ngrok-skip-browser-warning': '1',
      ...(options.headers || {}),
    },
  })
  const text = await response.text()
  let body = {}
  if (text) {
    try {
      body = JSON.parse(text)
    } catch {
      body = { raw: text }
    }
  }
  if (!response.ok) {
    throw new Error(`${options.method || 'GET'} ${pathname} -> ${response.status}: ${text}`)
  }
  return body
}

function authHeaders(token) {
  return { authorization: `Bearer ${token}` }
}

async function upload(token, bucket, name, content, type) {
  const filePath = path.join(os.tmpdir(), name)
  await fs.promises.writeFile(filePath, content)
  const form = new FormData()
  form.append('bucket', bucket)
  form.append('path', `smoke/${Date.now()}-${name}`)
  form.append('file', new Blob([content], { type }), name)
  const response = await fetch(`${apiUrl}/v1/storage/upload`, {
    method: 'POST',
    headers: {
      authorization: `Bearer ${token}`,
      'ngrok-skip-browser-warning': '1',
    },
    body: form,
  })
  const text = await response.text()
  if (!response.ok) throw new Error(`storage upload ${bucket}/${name} -> ${response.status}: ${text}`)
  const body = JSON.parse(text)
  assert(body.object?.publicUrl || body.object?.url, `storage upload ${bucket}/${name} no devolvio URL`)
  return body.object.publicUrl || body.object.url
}

async function main() {
  await jsonRequest('/health')
  await jsonRequest('/ready')

  let auth
  try {
    auth = await jsonRequest('/v1/auth/register', {
      method: 'POST',
      body: JSON.stringify({ email, password, displayName: 'Smoke Ubuntu' }),
    })
  } catch (error) {
    auth = await jsonRequest('/v1/auth/login', {
      method: 'POST',
      body: JSON.stringify({ email, password }),
    })
  }

  const token = auth.accessToken
  assert(token, 'login/register no devolvio accessToken')
  console.log('OK auth register/login')

  const avatarUrl = await upload(token, 'avatars', 'avatar.txt', 'avatar-smoke', 'text/plain')
  const audioUrl = await upload(token, 'chat-media', 'audio.txt', 'audio-smoke', 'text/plain')
  const videoUrl = await upload(token, 'videos', 'video.txt', 'video-smoke', 'text/plain')
  console.log('OK storage local')

  const profile = await jsonRequest('/v1/data/profile/me', {
    headers: authHeaders(token),
  })
  assert(profile.profile, 'profile/me no devolvio profile')
  await jsonRequest('/v1/data/profile/me', {
    method: 'PATCH',
    headers: authHeaders(token),
    body: JSON.stringify({ displayName: 'Smoke Ubuntu Editado', avatarUrl }),
  })
  const search = await jsonRequest('/v1/data/profiles/search?q=Smoke', {
    headers: authHeaders(token),
  })
  assert(Array.isArray(search.items), 'profiles/search no devolvio items')
  console.log('OK perfil y busqueda personas')

  const post = await jsonRequest('/v1/data/feed/posts', {
    method: 'POST',
    headers: authHeaders(token),
    body: JSON.stringify({
      content: `Smoke post ${new Date().toISOString()}`,
      mediaUrl: avatarUrl,
      mediaUrls: [avatarUrl],
      mediaType: 'image',
      visibility: 'public',
    }),
  })
  assert(post.post?.id, 'crear post no devolvio id')
  const comment = await jsonRequest(`/v1/data/feed/posts/${encodeURIComponent(post.post.id)}/comments`, {
    method: 'POST',
    headers: authHeaders(token),
    body: JSON.stringify({ content: 'Smoke comentario', mediaUrl: audioUrl, mediaType: 'audio' }),
  })
  assert(comment.comment?.id, 'crear comentario no devolvio id')
  await jsonRequest('/v1/data/feed?limit=5', { headers: authHeaders(token) })
  console.log('OK publicaciones/comentarios/social')

  const video = await jsonRequest('/v1/data/videos', {
    method: 'POST',
    headers: authHeaders(token),
    body: JSON.stringify({
      title: 'Smoke video',
      description: 'Smoke video Ubuntu',
      videoUrl,
      thumbnailUrl: avatarUrl,
      durationSeconds: 5,
      isPublic: true,
      tags: ['smoke'],
    }),
  })
  assert(video.video?.id, 'crear video no devolvio id')
  await jsonRequest(`/v1/data/videos/${encodeURIComponent(video.video.id)}/view`, {
    method: 'POST',
    headers: authHeaders(token),
  })
  const videoComment = await jsonRequest(`/v1/data/videos/${encodeURIComponent(video.video.id)}/comments`, {
    method: 'POST',
    headers: authHeaders(token),
    body: JSON.stringify({ content: '[Audio]', contentType: 'audio', mediaUrl: audioUrl }),
  })
  assert(videoComment.comment?.id, 'comentario de video no devolvio id')
  await jsonRequest('/v1/data/videos?q=smoke', { headers: authHeaders(token) })
  console.log('OK videos busqueda/vistas/comentarios/audio')

  const moment = await jsonRequest('/v1/data/moments', {
    method: 'POST',
    headers: authHeaders(token),
    body: JSON.stringify({ mediaUrl: avatarUrl, mediaType: 'image', caption: 'Smoke moment' }),
  })
  assert(moment.moment?.id || moment.item?.id || moment.id, 'crear momento no devolvio id')
  await jsonRequest('/v1/data/moments/feed', { headers: authHeaders(token) })
  console.log('OK momentos')

  const active = await jsonRequest('/v1/data/challenges/active', {
    headers: authHeaders(token),
  })
  const activeId = active.activeChallenge?.id
  assert(activeId, 'reto activo no devolvio id')
  const submission = await jsonRequest(`/v1/data/challenges/${encodeURIComponent(activeId)}/submissions`, {
    method: 'POST',
    headers: authHeaders(token),
    body: JSON.stringify({
      challengeId: active.activeChallenge.challenge_id,
      textContent: 'Smoke reto Ubuntu',
    }),
  })
  assert(submission.submission?.id, 'participacion de reto no devolvio id')
  await jsonRequest(`/v1/data/challenges/submissions/${encodeURIComponent(submission.submission.id)}/vote`, {
    method: 'POST',
    headers: authHeaders(token),
  })
  await jsonRequest(`/v1/data/challenges/${encodeURIComponent(activeId)}/submissions`, {
    headers: authHeaders(token),
  })
  console.log('OK retos y ranking de retos')

  console.log('LISTO: smoke test Ubuntu completo.')
}

main().catch((error) => {
  console.error(error)
  process.exit(1)
})
NODE
