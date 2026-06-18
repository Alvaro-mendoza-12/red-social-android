#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT_DIR"

API_URL="${API_URL:-http://127.0.0.1:3080}"
PUBLIC_BASE_URL="${PUBLIC_BASE_URL:-}"
STRICT_PRODUCTION="${STRICT_PRODUCTION:-false}"
REPORT_PATH="${REPORT_PATH:-$ROOT_DIR/production-readiness-report.md}"
RUN_ID="${RUN_ID:-$(date +%Y%m%d%H%M%S)}"
TEST_PASSWORD="${TEST_PASSWORD:-Conectate123!}"
TEST_PASSWORD_NEXT="${TEST_PASSWORD_NEXT:-Conectate123!!}"

PASS_COUNT=0
WARN_COUNT=0
FAIL_COUNT=0

tmp_dir="$(mktemp -d)"
trap 'rm -rf "$tmp_dir"' EXIT
MODULE_REPORT="$tmp_dir/module-report.json"

line() {
  printf '%s\n' "$*" | tee -a "$REPORT_PATH" >/dev/null
}

status_line() {
  local status="$1"
  local message="$2"
  case "$status" in
    PASS) PASS_COUNT=$((PASS_COUNT + 1)) ;;
    WARN) WARN_COUNT=$((WARN_COUNT + 1)) ;;
    FAIL) FAIL_COUNT=$((FAIL_COUNT + 1)) ;;
  esac
  line "- [$status] $message"
}

section() {
  line ""
  line "## $1"
}

run_capture() {
  local title="$1"
  shift
  line ""
  line "### $title"
  line '```text'
  if "$@" >>"$REPORT_PATH" 2>&1; then
    line '```'
    status_line PASS "$title"
  else
    local code=$?
    line '```'
    status_line FAIL "$title (exit $code)"
  fi
}

env_value() {
  local key="$1"
  if [ ! -f .env ]; then
    return 0
  fi
  grep -E "^${key}=" .env | tail -n 1 | cut -d= -f2- || true
}

require_env_ready() {
  local key="$1"
  local value
  value="$(env_value "$key")"
  if [ -z "$value" ]; then
    status_line WARN "$key no esta definido en .env"
    return
  fi
  case "$value" in
    change-this-*|"")
      status_line WARN "$key todavia parece valor de ejemplo"
      ;;
    *)
      status_line PASS "$key definido"
      ;;
  esac
}

curl_status() {
  local url="$1"
  curl -sS -o "$tmp_dir/curl-body.txt" -w '%{http_code}' \
    -H 'ngrok-skip-browser-warning: 1' "$url" || true
}

: >"$REPORT_PATH"
line "# Conectate Ubuntu Production Readiness"
line ""
line "- Fecha: $(date -Is)"
line "- Carpeta: $ROOT_DIR"
line "- API_URL: $API_URL"
line "- PUBLIC_BASE_URL: ${PUBLIC_BASE_URL:-"(tomado de .env si existe)"}"
line "- RUN_ID: $RUN_ID"

section "Entorno"
if command -v docker >/dev/null 2>&1; then
  status_line PASS "docker disponible: $(docker --version)"
else
  status_line FAIL "docker no esta instalado o no esta en PATH"
fi

if docker compose version >/dev/null 2>&1; then
  status_line PASS "docker compose disponible: $(docker compose version --short)"
else
  status_line FAIL "docker compose no responde"
fi

if command -v node >/dev/null 2>&1; then
  status_line PASS "node disponible: $(node --version)"
else
  status_line FAIL "node no esta instalado; instala Node.js LTS para ejecutar pruebas HTTP"
fi

if [ -f .env ]; then
  status_line PASS ".env existe"
else
  status_line FAIL ".env no existe; ejecuta bash scripts/sync-env-from-example.sh"
fi

require_env_ready POSTGRES_PASSWORD
require_env_ready JWT_SECRET
require_env_ready ADMIN_SYNC_TOKEN
require_env_ready PUBLIC_BASE_URL

legacy_enabled="$(env_value LEGACY_SUPABASE_IMPORT_ENABLED)"
if [ "${legacy_enabled:-false}" = "true" ]; then
  status_line WARN "LEGACY_SUPABASE_IMPORT_ENABLED=true; util para migrar, pero debe quedar false en produccion final"
else
  status_line PASS "LEGACY_SUPABASE_IMPORT_ENABLED no esta activo"
fi

if [ "$STRICT_PRODUCTION" = "true" ]; then
  for key in POSTGRES_PASSWORD JWT_SECRET ADMIN_SYNC_TOKEN PUBLIC_BASE_URL; do
    value="$(env_value "$key")"
    if [ -z "$value" ] || [[ "$value" == change-this-* ]]; then
      status_line FAIL "STRICT_PRODUCTION: $key debe tener valor real"
    fi
  done
fi

section "Docker, puertos y logs"
run_capture "docker compose ps" docker compose ps
run_capture "puertos TCP locales" bash -lc "ss -ltnp 2>/dev/null | grep -E ':(3001|3002|3003|3080|5433)\\b' || true"
run_capture "logs api edge signaling realtime-gateway" docker compose logs --tail=120 api edge signaling realtime-gateway

section "Postgres y storage local"
run_capture "Postgres ready" docker compose exec -T db pg_isready -U conectate -d conectate_fallback
run_capture "tablas principales" docker compose exec -T db psql -U conectate -d conectate_fallback -c "\\dt"
run_capture "conteos de tablas Ubuntu" docker compose exec -T db psql -U conectate -d conectate_fallback -c "
SELECT 'fallback_users' AS table_name, COUNT(*) FROM fallback_users
UNION ALL SELECT 'fallback_storage_objects', COUNT(*) FROM fallback_storage_objects
UNION ALL SELECT 'fallback_posts', COUNT(*) FROM fallback_posts
UNION ALL SELECT 'fallback_post_comments', COUNT(*) FROM fallback_post_comments
UNION ALL SELECT 'fallback_moments', COUNT(*) FROM fallback_moments
UNION ALL SELECT 'fallback_messages', COUNT(*) FROM fallback_messages
UNION ALL SELECT 'fallback_chats', COUNT(*) FROM fallback_chats
UNION ALL SELECT 'fallback_collection_rooms', COUNT(*) FROM fallback_collection_rooms
UNION ALL SELECT 'fallback_room_messages', COUNT(*) FROM fallback_room_messages
UNION ALL SELECT 'fallback_voice_rooms', COUNT(*) FROM fallback_voice_rooms
UNION ALL SELECT 'fallback_videos', COUNT(*) FROM fallback_videos
UNION ALL SELECT 'fallback_video_comments', COUNT(*) FROM fallback_video_comments
UNION ALL SELECT 'fallback_challenges_master', COUNT(*) FROM fallback_challenges_master
UNION ALL SELECT 'fallback_active_challenges', COUNT(*) FROM fallback_active_challenges
UNION ALL SELECT 'fallback_challenge_submissions', COUNT(*) FROM fallback_challenge_submissions
UNION ALL SELECT 'fallback_challenge_votes', COUNT(*) FROM fallback_challenge_votes
UNION ALL SELECT 'fallback_notifications', COUNT(*) FROM fallback_notifications
UNION ALL SELECT 'fallback_fcm_tokens', COUNT(*) FROM fallback_fcm_tokens;
"
run_capture "permiso de escritura storage /data/storage" docker compose exec -T api sh -lc "test -w /data/storage && touch /data/storage/.production-write-test && rm /data/storage/.production-write-test"

section "Proxy y health endpoints"
for path in /health /ready /realtime/health /turn-credentials; do
  code="$(curl_status "${API_URL}${path}")"
  if [ "$path" = "/turn-credentials" ]; then
    if [ "$code" = "200" ] || [ "$code" = "401" ] || [ "$code" = "503" ]; then
      status_line PASS "$path responde HTTP $code"
    else
      status_line FAIL "$path responde HTTP $code"
      line '```text'
      cat "$tmp_dir/curl-body.txt" | tee -a "$REPORT_PATH" >/dev/null
      line '```'
    fi
  elif [ "$code" = "200" ]; then
    status_line PASS "$path responde HTTP 200"
  else
    status_line FAIL "$path responde HTTP $code"
    line '```text'
    cat "$tmp_dir/curl-body.txt" | tee -a "$REPORT_PATH" >/dev/null
    line '```'
  fi
done

section "Validacion funcional por modulo"
set +e
MODULE_REPORT="$MODULE_REPORT" \
API_URL="$API_URL" \
RUN_ID="$RUN_ID" \
TEST_PASSWORD="$TEST_PASSWORD" \
TEST_PASSWORD_NEXT="$TEST_PASSWORD_NEXT" \
node <<'NODE'
const fs = require('fs')

const apiUrl = String(process.env.API_URL || '').replace(/\/+$/, '')
const runId = process.env.RUN_ID || Date.now().toString()
const password = process.env.TEST_PASSWORD || 'Conectate123!'
const passwordNext = process.env.TEST_PASSWORD_NEXT || 'Conectate123!!'
const moduleReportPath = process.env.MODULE_REPORT

const rows = []
let failures = 0

function record(module, endpoint, android, postgres, storage, ok, detail = '') {
  rows.push({ module, endpoint, android, postgres, storage, ok, detail })
  const label = ok ? 'OK' : 'FAIL'
  console.log(`[${label}] ${module} | ${endpoint}${detail ? ` | ${detail}` : ''}`)
  if (!ok) failures += 1
}

function assert(condition, message) {
  if (!condition) throw new Error(message)
}

async function request(pathname, options = {}) {
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

function auth(token) {
  return { authorization: `Bearer ${token}` }
}

async function step(module, endpoint, android, postgres, storage, fn) {
  try {
    const detail = await fn()
    record(module, endpoint, android, postgres, storage, true, detail || '')
    return detail
  } catch (error) {
    record(module, endpoint, android, postgres, storage, false, error.message)
    throw error
  }
}

async function upload(token, bucket, objectName, content, type) {
  const form = new FormData()
  form.append('bucket', bucket)
  form.append('path', `production/${runId}/${objectName}`)
  form.append('file', new Blob([content], { type }), objectName)
  const response = await fetch(`${apiUrl}/v1/storage/upload`, {
    method: 'POST',
    headers: {
      authorization: `Bearer ${token}`,
      'ngrok-skip-browser-warning': '1',
    },
    body: form,
  })
  const text = await response.text()
  if (!response.ok) throw new Error(`upload ${bucket}/${objectName} -> ${response.status}: ${text}`)
  const body = JSON.parse(text)
  const object = body.object
  assert(object?.publicUrl || object?.url, 'upload sin publicUrl')

  const readUrl = object.publicUrl || object.url
  const readResponse = await fetch(readUrl, { headers: { 'ngrok-skip-browser-warning': '1' } })
  if (!readResponse.ok) throw new Error(`lectura publica ${readUrl} -> ${readResponse.status}`)
  return object
}

async function main() {
  await step('health', 'GET /health', 'N/A', 'N/A', 'N/A', async () => {
    const body = await request('/health')
    assert(body.ok, 'health sin ok')
    return 'API viva'
  })
  await step('ready', 'GET /ready', 'N/A', 'Postgres', 'N/A', async () => {
    const body = await request('/ready')
    assert(body.ok, 'ready sin ok')
    return 'API + Postgres listos'
  })

  const actorEmail = `prod.actor.${runId}@example.local`
  const recipientEmail = `prod.recipient.${runId}@example.local`

  const actor = await step('auth registro/login/sesion', 'POST /v1/auth/register', 'AuthService.register/login/currentSession', 'fallback_users + fallback_profiles', 'N/A', async () => {
    const body = await request('/v1/auth/register', {
      method: 'POST',
      body: JSON.stringify({ email: actorEmail, password, displayName: 'Prod Actor' }),
    })
    assert(body.accessToken, 'sin accessToken')
    return body
  })
  const recipient = await step('auth segundo usuario', 'POST /v1/auth/register', 'AuthService.register', 'fallback_users + fallback_profiles', 'N/A', async () => {
    const body = await request('/v1/auth/register', {
      method: 'POST',
      body: JSON.stringify({ email: recipientEmail, password, displayName: 'Prod Recipient' }),
    })
    assert(body.accessToken, 'sin accessToken')
    return body
  })

  let actorToken = actor.accessToken
  const recipientToken = recipient.accessToken
  const actorId = actor.user.id
  const recipientId = recipient.user.id

  await step('auth cambio password', 'POST /v1/auth/change-password', 'AuthService.changePassword', 'fallback_users.password_hash', 'N/A', async () => {
    const changed = await request('/v1/auth/change-password', {
      method: 'POST',
      headers: auth(actorToken),
      body: JSON.stringify({ currentPassword: password, newPassword: passwordNext }),
    })
    assert(changed.accessToken, 'sin accessToken luego de cambio')
    const login = await request('/v1/auth/login', {
      method: 'POST',
      body: JSON.stringify({ email: actorEmail, password: passwordNext }),
    })
    actorToken = login.accessToken
    return 'password cambiado y login nuevo OK'
  })

  await step('perfil ver/editar/buscar', 'GET/PATCH /v1/data/profile/me + GET /v1/data/profiles/search', 'UserRepository + ProfileViewModel', 'fallback_profile_overrides/fallback_users', 'avatars', async () => {
    const avatar = await upload(actorToken, 'avatars', 'avatar.txt', 'avatar-production', 'text/plain')
    await request('/v1/data/profile/me', { headers: auth(actorToken) })
    await request('/v1/data/profile/me', {
      method: 'PATCH',
      headers: auth(actorToken),
      body: JSON.stringify({ displayName: 'Prod Actor Editado', bio: 'Ubuntu production check', avatarUrl: avatar.publicUrl }),
    })
    const search = await request('/v1/data/profiles/search?q=Prod', { headers: auth(actorToken) })
    assert(Array.isArray(search.items), 'profiles/search sin items')
    return `avatar=${avatar.bucket}/${avatar.path}; search=${search.items.length}`
  })

  const audio = await step('storage audio', 'POST /v1/storage/upload + GET public object', 'StorageService + audio players', 'fallback_storage_objects', 'chat-media', async () => {
    const object = await upload(actorToken, 'chat-media', 'audio.txt', 'audio-production', 'text/plain')
    return `${object.bucket}/${object.path}`
  })
  const videoFile = await step('storage video', 'POST /v1/storage/upload + GET public object', 'StorageService + VideoRepository', 'fallback_storage_objects', 'videos', async () => {
    const object = await upload(actorToken, 'videos', 'video.txt', 'video-production', 'text/plain')
    return `${object.bucket}/${object.path}`
  })
  const image = await step('storage imagen', 'POST /v1/storage/upload + GET public object', 'StorageService + Post/Moment/Profile', 'fallback_storage_objects', 'post-media/moment-media', async () => {
    const object = await upload(actorToken, 'post-media', 'image.txt', 'image-production', 'text/plain')
    return `${object.bucket}/${object.path}`
  })

  const post = await step('publicaciones/comentarios/social', 'POST /v1/data/feed/posts + comments/reaction/share/detail', 'PostRepository + SocialScreen', 'fallback_posts + fallback_post_comments + fallback_post_reactions + fallback_post_shares', 'post-media + chat-media', async () => {
    const created = await request('/v1/data/feed/posts', {
      method: 'POST',
      headers: auth(actorToken),
      body: JSON.stringify({ content: `Prod post ${runId}`, mediaUrl: image.publicUrl, mediaUrls: [image.publicUrl], mediaType: 'image', visibility: 'public' }),
    })
    assert(created.post?.id, 'post sin id')
    await request(`/v1/data/feed/posts/${encodeURIComponent(created.post.id)}/comments`, {
      method: 'POST',
      headers: auth(actorToken),
      body: JSON.stringify({ content: '[Audio]', mediaUrl: audio.publicUrl, mediaType: 'audio' }),
    })
    await request(`/v1/data/feed/posts/${encodeURIComponent(created.post.id)}/reaction`, {
      method: 'POST',
      headers: auth(actorToken),
      body: JSON.stringify({ reactionType: 'LIKE' }),
    })
    await request(`/v1/data/feed/posts/${encodeURIComponent(created.post.id)}/share`, { method: 'POST', headers: auth(actorToken) })
    const detail = await request(`/v1/data/feed/posts/${encodeURIComponent(created.post.id)}`, { headers: auth(actorToken) })
    assert(detail.comments?.length >= 1, 'detalle sin comentario')
    return created.post
  })

  await step('notificaciones', 'POST/GET/PATCH /v1/data/notifications', 'NotificationRepository + UbuntuRealtimeClient', 'fallback_notifications', 'N/A', async () => {
    await request('/v1/data/notifications/fcm-tokens', {
      method: 'POST',
      headers: auth(recipientToken),
      body: JSON.stringify({ token: `fcm-production-${runId}`, platform: 'android' }),
    })
    await request('/v1/data/notifications', {
      method: 'POST',
      headers: auth(actorToken),
      body: JSON.stringify({ recipientId, type: 'production_check', targetId: post.id, targetType: 'post', content: 'Production notification' }),
    })
    const list = await request('/v1/data/notifications?limit=20', { headers: auth(recipientToken) })
    assert(list.items?.some((item) => item.type === 'production_check'), 'notificacion no encontrada')
    await request(`/v1/data/notifications/${encodeURIComponent(list.items[0].id)}/read`, { method: 'PATCH', headers: auth(recipientToken) })
    await request('/v1/data/notifications/read-all', { method: 'POST', headers: auth(recipientToken) })
    await request('/v1/data/notifications/fcm-tokens', {
      method: 'DELETE',
      headers: auth(recipientToken),
      body: JSON.stringify({ token: `fcm-production-${runId}` }),
    })
    return `items=${list.items.length}, unread=${list.unreadCount}, fcm=ok`
  })

  await step('mensajes directos con audio', 'POST /v1/data/messages/chats + /messages', 'MessageRepository + ChatViewModel', 'fallback_chats + fallback_chat_participants + fallback_messages + fallback_notifications', 'chat-media', async () => {
    const chat = await request('/v1/data/messages/chats', {
      method: 'POST',
      headers: auth(actorToken),
      body: JSON.stringify({ participantId: recipientId }),
    })
    assert(chat.chat?.id, 'chat sin id')
    const sent = await request(`/v1/data/messages/chats/${encodeURIComponent(chat.chat.id)}/messages`, {
      method: 'POST',
      headers: auth(actorToken),
      body: JSON.stringify({ content: '[Audio]', contentType: 'audio', mediaUrl: audio.publicUrl, mediaSize: Number(audio.size || 0) }),
    })
    assert(sent.message?.id, 'mensaje sin id')
    const detail = await request(`/v1/data/messages/chats/${encodeURIComponent(chat.chat.id)}`, { headers: auth(recipientToken) })
    assert(detail.messages?.length >= 1, 'chat sin mensajes')
    return `chat=${chat.chat.id}; messages=${detail.messages.length}`
  })

  await step('grupos y comunidades', 'POST /v1/data/messages/groups|communities + messages', 'GroupRepository + CommunityRepository', 'fallback_collection_rooms + fallback_collection_members + fallback_room_messages', 'group-media/community-media/chat-media', async () => {
    const group = await request('/v1/data/messages/groups', {
      method: 'POST',
      headers: auth(actorToken),
      body: JSON.stringify({ name: `Prod Group ${runId}`, memberIds: [recipientId] }),
    })
    assert(group.id, 'grupo sin id')
    await request(`/v1/data/messages/groups/${encodeURIComponent(group.id)}/messages`, {
      method: 'POST',
      headers: auth(actorToken),
      body: JSON.stringify({ content: 'Mensaje grupo Ubuntu', contentType: 'text' }),
    })
    const community = await request('/v1/data/messages/communities', {
      method: 'POST',
      headers: auth(actorToken),
      body: JSON.stringify({ name: `Prod Community ${runId}`, category: 'general', isPublic: true }),
    })
    assert(community.id, 'comunidad sin id')
    await request(`/v1/data/messages/communities/${encodeURIComponent(community.id)}/messages`, {
      method: 'POST',
      headers: auth(actorToken),
      body: JSON.stringify({ content: 'Mensaje comunidad Ubuntu', contentType: 'text' }),
    })
    return `group=${group.id}; community=${community.id}`
  })

  await step('videos busqueda/reproduccion/comentarios', 'POST/GET /v1/data/videos + view/comment/reaction', 'VideoRepository + VideoFeedViewModel', 'fallback_videos + fallback_video_comments + fallback_video_likes', 'videos + video-comments-media', async () => {
    const created = await request('/v1/data/videos', {
      method: 'POST',
      headers: auth(actorToken),
      body: JSON.stringify({ title: `Prod video ${runId}`, description: 'Ubuntu production video', videoUrl: videoFile.publicUrl, thumbnailUrl: image.publicUrl, durationSeconds: 5, isPublic: true, tags: ['production'] }),
    })
    assert(created.video?.id, 'video sin id')
    await request(`/v1/data/videos/${encodeURIComponent(created.video.id)}/view`, { method: 'POST', headers: auth(actorToken) })
    await request(`/v1/data/videos/${encodeURIComponent(created.video.id)}/comments`, {
      method: 'POST',
      headers: auth(actorToken),
      body: JSON.stringify({ content: '[Audio]', contentType: 'audio', mediaUrl: audio.publicUrl }),
    })
    await request(`/v1/data/videos/${encodeURIComponent(created.video.id)}/reaction`, {
      method: 'POST',
      headers: auth(actorToken),
      body: JSON.stringify({ reactionType: 'LIKE' }),
    })
    const search = await request('/v1/data/videos?q=production', { headers: auth(actorToken) })
    assert(Array.isArray(search.items), 'videos search sin items')
    return `video=${created.video.id}; search=${search.items.length}`
  })

  await step('momentos', 'POST/GET /v1/data/moments + view', 'MomentRepository + MomentViewerScreen', 'fallback_moments + fallback_moment_views', 'moment-media/post-media', async () => {
    const created = await request('/v1/data/moments', {
      method: 'POST',
      headers: auth(actorToken),
      body: JSON.stringify({ mediaUrl: image.publicUrl, mediaType: 'image', caption: `Prod moment ${runId}` }),
    })
    const id = created.moment?.id || created.item?.id || created.id
    assert(id, 'momento sin id')
    await request(`/v1/data/moments/${encodeURIComponent(id)}/view`, { method: 'POST', headers: auth(recipientToken) })
    const feed = await request('/v1/data/moments/feed', { headers: auth(actorToken) })
    assert(Array.isArray(feed.items), 'moments feed sin items')
    return `moment=${id}; feed=${feed.items.length}`
  })

  await step('retos y votos', 'GET/POST /v1/data/challenges/*', 'ChallengeRepository + ChallengeViewModel', 'fallback_challenges_master + fallback_active_challenges + fallback_challenge_submissions + fallback_challenge_votes', 'challenges', async () => {
    const active = await request('/v1/data/challenges/active', { headers: auth(actorToken) })
    assert(active.activeChallenge?.id, 'sin reto activo')
    const submission = await request(`/v1/data/challenges/${encodeURIComponent(active.activeChallenge.id)}/submissions`, {
      method: 'POST',
      headers: auth(actorToken),
      body: JSON.stringify({ challengeId: active.activeChallenge.challenge_id, textContent: `Reto production ${runId}` }),
    })
    assert(submission.submission?.id, 'submission sin id')
    const vote = await request(`/v1/data/challenges/submissions/${encodeURIComponent(submission.submission.id)}/vote`, {
      method: 'POST',
      headers: auth(recipientToken),
    })
    assert(typeof vote.vote_count === 'number', 'voto sin conteo')
    return `active=${active.activeChallenge.id}; votes=${vote.vote_count}`
  })

  await step('rankings', 'GET profiles/videos/feed', 'RankingRepository', 'fallback_users + fallback_videos + fallback_posts', 'N/A', async () => {
    const users = await request('/v1/data/profiles/search?q=&limit=100', { headers: auth(actorToken) })
    const videos = await request('/v1/data/videos?limit=100', { headers: auth(actorToken) })
    const feed = await request('/v1/data/feed?limit=100', { headers: auth(actorToken) })
    assert(Array.isArray(users.items) && Array.isArray(videos.items) && Array.isArray(feed.items), 'ranking sources invalidas')
    return `users=${users.items.length}; videos=${videos.items.length}; posts=${feed.items.length}`
  })

  await step('salas de voz', 'POST/GET /v1/data/voice/rooms + join/leave', 'VoiceRoomRepository + signaling Socket.IO', 'fallback_voice_rooms + fallback_voice_participants', 'N/A', async () => {
    const room = await request('/v1/data/voice/rooms', {
      method: 'POST',
      headers: auth(actorToken),
      body: JSON.stringify({ name: `Prod Voice ${runId}`, description: 'Ubuntu voice room', isPublic: true, maxParticipants: 8 }),
    })
    assert(room.room?.id, 'sala sin id')
    await request(`/v1/data/voice/rooms/${encodeURIComponent(room.room.id)}/join`, {
      method: 'POST',
      headers: auth(actorToken),
      body: JSON.stringify({ userName: 'Prod Actor', socketId: `prod-${runId}`, isMuted: false }),
    })
    const detail = await request(`/v1/data/voice/rooms/${encodeURIComponent(room.room.id)}`, { headers: auth(actorToken) })
    assert(Array.isArray(detail.participants), 'sala sin participantes')
    await request(`/v1/data/voice/rooms/${encodeURIComponent(room.room.id)}/leave`, { method: 'POST', headers: auth(actorToken) })
    return `room=${room.room.id}; participants=${detail.participants.length}`
  })

  await step('storage listado y borrado', 'GET/DELETE /v1/storage/objects', 'StorageService.delete/list', 'fallback_storage_objects', 'chat-media', async () => {
    const temp = await upload(actorToken, 'chat-media', 'delete-me.txt', 'delete-me-production', 'text/plain')
    const listed = await request(`/v1/storage/objects?bucket=chat-media&prefix=${encodeURIComponent(`production/${runId}/`)}`, { headers: auth(actorToken) })
    assert(Array.isArray(listed.objects), 'storage list sin objects')
    const deletable = listed.objects.find((object) => object.path === temp.path)
    if (deletable) {
      await request(`/v1/storage/objects/${encodeURIComponent(deletable.bucket)}/${deletable.path.split('/').map(encodeURIComponent).join('/')}`, {
        method: 'DELETE',
        headers: auth(actorToken),
      })
    }
    return `objects=${listed.objects.length}; deleted=${Boolean(deletable)}`
  })

  fs.writeFileSync(moduleReportPath, JSON.stringify({ rows, failures }, null, 2))
  if (failures > 0) process.exit(1)
}

main().catch((error) => {
  console.error(error)
  fs.writeFileSync(moduleReportPath, JSON.stringify({ rows, failures: failures + 1, error: error.message }, null, 2))
  process.exit(1)
})
NODE
node_status=$?
set -e
if [ "$node_status" -eq 0 ]; then
  status_line PASS "validacion funcional por modulo completa"
else
  status_line FAIL "validacion funcional por modulo fallo"
fi

if [ -f "$MODULE_REPORT" ]; then
  line ""
  line "### Matriz endpoint Android Postgres Storage"
  node - "$MODULE_REPORT" "$REPORT_PATH" <<'NODE'
const fs = require('fs')
const [inputPath, reportPath] = process.argv.slice(2)
const data = JSON.parse(fs.readFileSync(inputPath, 'utf8'))
const lines = []
lines.push('| Modulo | OK | Endpoint | Android | Postgres | Storage | Detalle |')
lines.push('| --- | --- | --- | --- | --- | --- | --- |')
for (const row of data.rows || []) {
  const esc = (value) => String(value || '').replace(/\|/g, '\\|').replace(/\n/g, ' ')
  lines.push(`| ${esc(row.module)} | ${row.ok ? 'OK' : 'FAIL'} | \`${esc(row.endpoint)}\` | ${esc(row.android)} | ${esc(row.postgres)} | ${esc(row.storage)} | ${esc(row.detail)} |`)
}
fs.appendFileSync(reportPath, `${lines.join('\n')}\n`)
NODE
fi

section "Referencias Supabase activas esperadas"
line "- Supabase debe quedar desactivado funcionalmente con FALLBACK_API_URL/Ubuntu como primario."
line "- LEGACY_SUPABASE_IMPORT_ENABLED solo debe activarse temporalmente para importar usuarios/storage antiguos."
line "- FCM push remoto todavia requiere proveedor externo si quieres push fuera del proceso local; las notificaciones in-app ya se persisten en fallback_notifications."

section "Resumen"
line "- PASS: $PASS_COUNT"
line "- WARN: $WARN_COUNT"
line "- FAIL: $FAIL_COUNT"

echo "Reporte generado: $REPORT_PATH"

if [ "$FAIL_COUNT" -gt 0 ]; then
  exit 1
fi
