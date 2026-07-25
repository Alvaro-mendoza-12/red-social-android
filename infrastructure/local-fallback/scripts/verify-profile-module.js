const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')

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
      email: `module-profile.${label}.${runId}@example.local`,
      password,
      displayName: `Profile ${label}`,
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

async function uploadOwnedFile(token, { bucket, contentType, objectPath, sourceFile, sourceLabel }) {
  assert.ok(sourceFile, `${sourceLabel} is required`)
  const sourcePath = path.resolve(sourceFile)
  assert.ok(fs.statSync(sourcePath).isFile(), `${sourceLabel} is not a file: ${sourcePath}`)
  const bytes = fs.readFileSync(sourcePath)
  const response = await fetch(`${apiUrl}/storage/v1/object/${encodeURIComponent(bucket)}/${encodedObjectPath(objectPath)}`, {
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
  assert.ok(payload.path)
  assert.ok(payload.publicUrl)
  return payload
}

async function deleteOwnedObject(token, bucket, objectPath) {
  await request(`/v1/storage/objects/${encodeURIComponent(bucket)}/${encodedObjectPath(objectPath)}`, {
    token,
    method: 'DELETE',
  })
}

async function main() {
  const owner = await register('owner')
  const viewer = await register('viewer')
  const additionalEmail = `module-profile.alias.${runId}@example.local`
  const avatar = await uploadOwnedFile(owner.token, {
    bucket: 'avatars',
    contentType: 'image/png',
    objectPath: `profile-module/${runId}-avatar.png`,
    sourceFile: mediaTestImage,
    sourceLabel: 'MEDIA_TEST_IMAGE',
  })
  const cover = await uploadOwnedFile(owner.token, {
    bucket: 'avatars',
    contentType: 'image/png',
    objectPath: `profile-module/${runId}-cover.png`,
    sourceFile: mediaTestImage,
    sourceLabel: 'MEDIA_TEST_IMAGE',
  })

  await request('/v1/data/profile/login-emails', {
    token: owner.token,
    method: 'POST',
    expected: 201,
    body: { email: additionalEmail },
  })
  const loginEmails = await request('/v1/data/profile/login-emails', { token: owner.token })
  assert.deepEqual(loginEmails.items, [additionalEmail])
  const aliasLogin = await request('/v1/auth/login', {
    method: 'POST',
    body: { email: additionalEmail, password },
  })
  assert.equal(aliasLogin.user.id, owner.id)
  await request('/v1/data/profile/login-emails', {
    token: viewer.token,
    method: 'POST',
    expected: 409,
    body: { email: additionalEmail },
  })
  for (const suffix of ['second', 'third']) {
    await request('/v1/data/profile/login-emails', {
      token: owner.token,
      method: 'POST',
      expected: 201,
      body: { email: `module-profile.${suffix}.${runId}@example.local` },
    })
  }
  await request('/v1/data/profile/login-emails', {
    token: owner.token,
    method: 'POST',
    expected: 409,
    body: { email: `module-profile.fourth.${runId}@example.local` },
  })
  await request(`/v1/data/profile/login-emails/${encodeURIComponent(additionalEmail)}`, {
    token: owner.token,
    method: 'DELETE',
  })
  await request('/v1/auth/login', {
    method: 'POST',
    expected: 404,
    body: { email: additionalEmail, password },
  })
  const webSessions = await request('/v1/auth/web-sessions', { token: owner.token })
  assert.ok(Array.isArray(webSessions.sessions))

  const updated = await request('/v1/data/profile/me', {
    token: owner.token,
    method: 'PATCH',
    body: {
      displayName: 'Perfil validado',
      bio: 'Biografía persistida',
      country: 'Colombia',
      city: 'Bogotá',
      avatarUrl: avatar.publicUrl,
      coverUrl: cover.publicUrl,
      isPrivate: true,
      profileVisibility: 'connections',
      favoritesPublic: false,
      profileTheme: 'noche',
      profileEffect: 'orbit',
      profileWelcome: 'Bienvenido a mi ambiente',
      interests: ['fotografía', 'astronomía'],
      hobbies: ['senderismo', 'dibujo'],
      linkedGames: ['Minecraft'],
      gameLogoUrls: { Minecraft: 'https://images.igdb.com/igdb/image/upload/t_cover_big/co8fu7.webp' },
      favoriteMusicUrl: 'https://example.local/audio/perfil.mp3',
      favoriteBook: 'Cien años de soledad',
      favoriteSport: 'Ciclismo',
      career: 'Ingeniería de software',
      availabilitySchedule: 'Tardes y fines de semana',
    },
  })
  assert.equal(updated.profile.display_name, 'Perfil validado')
  assert.equal(updated.profile.bio, 'Biografía persistida')
  assert.equal(updated.profile.city, 'Bogotá')
  assert.equal(updated.profile.is_private, true)
  assert.equal(updated.profile.profile_visibility, 'connections')
  assert.equal(updated.profile.favorites_public, false)
  assert.equal(updated.profile.profile_theme, 'noche')
  assert.equal(updated.profile.profile_effect, 'orbit')
  assert.equal(updated.profile.profile_welcome, 'Bienvenido a mi ambiente')
  assert.deepEqual(updated.profile.interests, ['fotografía', 'astronomía'])
  assert.deepEqual(updated.profile.hobbies, ['senderismo', 'dibujo'])
  assert.deepEqual(updated.profile.linked_games, ['Minecraft'])
  assert.equal(updated.profile.game_logo_urls.Minecraft, 'https://images.igdb.com/igdb/image/upload/t_cover_big/co8fu7.webp')
  assert.equal(updated.profile.favorite_music_url, 'https://example.local/audio/perfil.mp3')
  assert.equal(updated.profile.favorite_book, 'Cien años de soledad')
  assert.equal(updated.profile.favorite_sport, 'Ciclismo')
  assert.equal(updated.profile.career, 'Ingeniería de software')
  assert.equal(updated.profile.availability_schedule, 'Tardes y fines de semana')
  assert.equal(new URL(updated.profile.avatar_url).pathname, new URL(avatar.publicUrl).pathname)
  assert.equal(new URL(updated.profile.cover_url).pathname, new URL(cover.publicUrl).pathname)

  const persistedMedia = await request('/v1/data/profile/me', { token: owner.token })
  assert.equal(new URL(persistedMedia.profile.avatar_url).pathname, new URL(avatar.publicUrl).pathname)
  assert.equal(new URL(persistedMedia.profile.cover_url).pathname, new URL(cover.publicUrl).pathname)

  const partialUpdate = await request('/v1/data/profile/me', {
    token: owner.token,
    method: 'PATCH',
    body: { profileWelcome: 'Bienvenida actualizada' },
  })
  assert.equal(partialUpdate.profile.profile_welcome, 'Bienvenida actualizada')
  assert.deepEqual(partialUpdate.profile.interests, ['fotografía', 'astronomía'])
  assert.deepEqual(partialUpdate.profile.hobbies, ['senderismo', 'dibujo'])
  assert.deepEqual(partialUpdate.profile.linked_games, ['Minecraft'])
  assert.equal(partialUpdate.profile.avatar_url, persistedMedia.profile.avatar_url)
  assert.equal(partialUpdate.profile.cover_url, persistedMedia.profile.cover_url)

  const persistedPartialUpdate = await request('/v1/data/profile/me', { token: owner.token })
  assert.equal(persistedPartialUpdate.profile.profile_welcome, 'Bienvenida actualizada')
  assert.deepEqual(persistedPartialUpdate.profile.interests, ['fotografía', 'astronomía'])
  assert.deepEqual(persistedPartialUpdate.profile.hobbies, ['senderismo', 'dibujo'])
  assert.deepEqual(persistedPartialUpdate.profile.linked_games, ['Minecraft'])
  assert.equal(persistedPartialUpdate.profile.avatar_url, persistedMedia.profile.avatar_url)
  assert.equal(persistedPartialUpdate.profile.cover_url, persistedMedia.profile.cover_url)

  const cleared = await request('/v1/data/profile/me', {
    token: owner.token,
    method: 'PATCH',
    body: { removeAvatar: true, removeCover: true },
  })
  assert.equal(cleared.profile.avatar_url, '')
  assert.equal(cleared.profile.cover_url, '')

  const uploadedVideo = await uploadOwnedFile(owner.token, {
    bucket: 'videos',
    contentType: 'video/mp4',
    objectPath: `profile-module/${runId}.mp4`,
    sourceFile: mediaTestVideo,
    sourceLabel: 'MEDIA_TEST_VIDEO',
  })
  const video = await request('/v1/data/videos', {
    token: owner.token,
    method: 'POST',
    expected: 201,
    body: {
      title: `Profile favorite ${runId}`,
      videoUrl: uploadedVideo.publicUrl,
      durationSeconds: 15,
      isPublic: true,
    },
  })
  await request(`/v1/data/videos/${video.video.id}/favorite`, {
    token: owner.token,
    method: 'POST',
  })

  const ownFavorites = await request(`/v1/data/videos?favoritedBy=${owner.id}`, { token: owner.token })
  assert.ok(ownFavorites.items.some((item) => item.id === video.video.id))
  await request(`/v1/data/videos?favoritedBy=${owner.id}`, { token: viewer.token, expected: 403 })

  await request('/v1/data/profile/me', {
    token: owner.token,
    method: 'PATCH',
    body: { favoritesPublic: true },
  })
  const visibleProfile = await request(`/v1/data/profiles/${owner.id}`, { token: viewer.token })
  assert.equal(visibleProfile.profile.favorites_public, true)
  assert.equal(visibleProfile.profile.profile_theme, 'noche')
  assert.equal(visibleProfile.profile.profile_effect, 'orbit')
  assert.equal(visibleProfile.profile.profile_welcome, 'Bienvenida actualizada')
  assert.deepEqual(visibleProfile.profile.interests, ['fotografía', 'astronomía'])
  assert.deepEqual(visibleProfile.profile.hobbies, ['senderismo', 'dibujo'])
  assert.deepEqual(visibleProfile.profile.linked_games, ['Minecraft'])
  assert.equal(visibleProfile.profile.game_logo_urls.Minecraft, 'https://images.igdb.com/igdb/image/upload/t_cover_big/co8fu7.webp')
  const publicFavorites = await request(`/v1/data/videos?favoritedBy=${owner.id}`, { token: viewer.token })
  assert.ok(publicFavorites.items.some((item) => item.id === video.video.id))

  await request('/v1/data/profile/me', {
    token: owner.token,
    method: 'PATCH',
    body: { breakMode: 'mute' },
  })
  await request(`/v1/data/profiles/${owner.id}/follow`, { token: viewer.token, method: 'POST' })
  const mutedNotifications = await request('/v1/data/notifications', { token: owner.token })
  assert.ok(!mutedNotifications.items.some((item) => item.actor_id === viewer.id))

  await request('/v1/data/profile/me', {
    token: owner.token,
    method: 'PATCH',
    body: { breakMode: 'hidden' },
  })
  await request(`/v1/data/profiles/${owner.id}`, { token: viewer.token, expected: 404 })
  const hiddenSearch = await request(`/v1/data/profiles/search?q=${encodeURIComponent('Perfil validado')}`, {
    token: viewer.token,
  })
  assert.ok(!hiddenSearch.items.some((item) => item.id === owner.id))
  const hiddenOwnerProfile = await request('/v1/data/profile/me', { token: owner.token })
  assert.equal(hiddenOwnerProfile.profile.break_mode, 'hidden')

  await request('/v1/data/profile/me', {
    token: owner.token,
    method: 'PATCH',
    body: { breakMode: 'off' },
  })
  await request(`/v1/data/profiles/${owner.id}`, { token: viewer.token })

  await request(`/v1/data/videos/${video.video.id}`, { token: owner.token, method: 'DELETE' })
  await deleteOwnedObject(owner.token, 'videos', uploadedVideo.path)
  await deleteOwnedObject(owner.token, 'avatars', avatar.path)
  await deleteOwnedObject(owner.token, 'avatars', cover.path)

  process.stdout.write(JSON.stringify({
    ok: true,
    profileFieldsPersisted: true,
    privacyPersisted: true,
    mediaRemovalPersisted: true,
    ownedProfileMediaPersisted: true,
    favoritesPrivateByDefault: true,
    favoritesVisibilityEnforced: true,
    publicFavoriteFlagVisibleCrossAccount: true,
    profileAppearanceVisibleCrossAccount: true,
    profilePersonalizationPersisted: true,
    breakMuteSuppressesNotifications: true,
    breakHiddenProtectsProfile: true,
    breakCanBeDisabled: true,
    additionalLoginEmails: true,
    additionalEmailLogin: true,
    additionalEmailLimits: true,
    webSessionsReadable: true,
  }))
}

main().catch((error) => {
  console.error(error)
  process.exit(1)
})
