const assert = require('assert')
const {
  buildProfileForUser,
  collectionRoleCapabilities,
  communityChannelPayload,
  communityEventPayload,
  communityMemberProfilePayload,
  createLocalNotificationBestEffort,
  currentUserMayUseReportEvidence,
  currentUserOwnsStorageObject,
  currentUserOwnsStorageObjects,
  feedPostVisible,
  normalizeProfileSectionOrder,
  normalizeProfileSectionVisibility,
  normalizeFeedMode,
  normalizeCollectionRole,
  normalizeCommunityChannelType,
  normalizeSavedCollectionName,
  normalizeSavedTags,
  parsePublicStorageObjectUrl,
  profileClearFields,
  profileForViewer,
  profilePatchFromBody,
  rankPublicProfiles,
  recommendationPreferenceScore,
  sameStorageObjectReference,
  storageObjectExists,
  userIdentity,
} = require('../src/data-routes')

assert.strictEqual(normalizeCollectionRole('creator'), 'owner')
assert.strictEqual(normalizeCollectionRole('MOD'), 'moderator')
assert.strictEqual(normalizeCollectionRole('unknown'), 'member')
assert.deepStrictEqual(
  collectionRoleCapabilities('moderator'),
  {
    role: 'moderator',
    label: 'Moderador',
    canPost: true,
    canModerateMessages: true,
    canManageMembers: false,
    canManageRoles: false,
    canManageChannels: false,
    canManageEvents: false,
    canEditCommunity: false,
    canPauseChannels: true,
  },
)
assert.strictEqual(collectionRoleCapabilities('owner').canManageRoles, true)
assert.strictEqual(collectionRoleCapabilities('member').canModerateMessages, false)
assert.strictEqual(normalizeCommunityChannelType('MEDIA'), 'media')
assert.strictEqual(normalizeCommunityChannelType('unsupported'), 'text')
assert.deepStrictEqual(
  communityChannelPayload(
    {
      id: 'channel-1',
      community_id: 'community-1',
      name: 'Fotos',
      channel_type: 'media',
      position: 2,
      is_default: false,
      paused_until: '2099-01-01T00:00:00.000Z',
      pause_reason: 'Mantenimiento',
    },
    { canManage: true, canPause: true },
  ),
  {
    id: 'channel-1',
    communityId: 'community-1',
    name: 'Fotos',
    description: null,
    type: 'media',
    position: 2,
    isDefault: false,
    pausedUntil: '2099-01-01T00:00:00.000Z',
    pauseReason: 'Mantenimiento',
    isPaused: true,
    canManage: true,
    canPause: true,
  },
)
assert.deepStrictEqual(
  communityMemberProfilePayload(
    {
      community_id: 'community-1',
      user_id: 'user-1',
      alias: 'Ada',
      bio: 'Ciencia y comunidad',
      distinctive: '🔭',
      updated_at: new Date('2026-07-28T14:00:00.000Z'),
    },
  ),
  {
    communityId: 'community-1',
    userId: 'user-1',
    alias: 'Ada',
    bio: 'Ciencia y comunidad',
    distinctive: '🔭',
    updatedAt: '2026-07-28T14:00:00.000Z',
  },
)
assert.deepStrictEqual(
  communityEventPayload(
    {
      id: 'event-1',
      community_id: 'community-1',
      title: 'Astronomía',
      starts_at: new Date('2026-07-29T14:00:00.000Z'),
      created_by: 'user-1',
    },
    {
      attendanceCount: 3,
      currentAttendance: 'going',
      reminderMinutes: 60,
      canManage: true,
    },
  ),
  {
    id: 'event-1',
    communityId: 'community-1',
    title: 'Astronomía',
    description: null,
    startsAt: '2026-07-29T14:00:00.000Z',
    endsAt: null,
    location: null,
    createdBy: 'user-1',
    isCancelled: false,
    attendanceCount: 3,
    currentAttendance: 'going',
    reminderMinutes: 60,
    reminderAt: '2026-07-29T13:00:00.000Z',
    reminderSentAt: null,
    canManage: true,
    createdAt: null,
    updatedAt: null,
  },
)

const localIdentity = userIdentity({
  id: '11111111-1111-4111-8111-111111111111',
  supabase_user_id: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
  email: 'admin@example.test',
})
assert.strictEqual(localIdentity.localUserId, '11111111-1111-4111-8111-111111111111')
assert.strictEqual(localIdentity.preferredUserId, 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa')
assert.strictEqual(normalizeFeedMode('FOLLOWING'), 'following')
assert.strictEqual(normalizeFeedMode('unknown'), 'all')
assert.strictEqual(normalizeSavedCollectionName('  Ciencia   y tecnología  '), 'Ciencia y tecnología')
assert.deepStrictEqual(
  normalizeSavedTags([' Ciencia ', 'ciencia', 'Android, WebRTC', 'x']),
  ['ciencia', 'android', 'webrtc'],
)
assert.strictEqual(
  feedPostVisible(
    { user_id: 'followed-user', visibility: 'followers' },
    {
      identityIds: new Set(['me']),
      followingIds: new Set(['followed-user']),
      mode: 'following',
    },
  ),
  true,
  'followers-only posts must be visible in the Following feed',
)
assert.strictEqual(
  feedPostVisible(
    { user_id: 'followed-user', visibility: 'public' },
    {
      identityIds: new Set(['me']),
      followingIds: new Set(['followed-user']),
      mode: 'discover',
    },
  ),
  false,
  'Discover must not duplicate creators already present in Following',
)
assert.strictEqual(
  feedPostVisible(
    { user_id: 'stranger', visibility: 'private' },
    {
      identityIds: new Set(['me']),
      followingIds: new Set(),
      mode: 'discover',
    },
  ),
  false,
  'private posts must never leak into Discover',
)

const removedAvatarProfile = buildProfileForUser(
  { id: 'owner-1', email: 'owner@example.test', display_name: 'Owner' },
  { id: 'owner-1', avatar_url: 'https://api.example.test/old-avatar.jpg' },
  { avatar_url: '', updated_at: '2026-07-18T20:00:00.000Z' },
)
assert.strictEqual(removedAvatarProfile.avatar_url, '')

const stableAvatarUrl = 'https://api.example.test/storage/v1/object/public/avatars/owner-1/avatar.jpg'
assert.strictEqual(
  buildProfileForUser(
    { id: 'owner-1', email: 'owner@example.test', display_name: 'Owner' },
    { id: 'owner-1' },
    { avatar_url: stableAvatarUrl, updated_at: '2026-07-18T20:00:00.000Z' },
  ).avatar_url,
  buildProfileForUser(
    { id: 'owner-1', email: 'owner@example.test', display_name: 'Owner' },
    { id: 'owner-1' },
    { avatar_url: stableAvatarUrl, updated_at: '2026-07-18T20:01:00.000Z' },
  ).avatar_url,
  'unrelated profile updates must not change the avatar URL',
)

const rankedProfiles = rankPublicProfiles({
  profiles: [
    { id: 'user-a', username: 'a', updated_at: '2026-07-21T12:00:00Z' },
    { id: 'user-b', user_id: 'alias-b', username: 'b', updated_at: '2026-07-20T12:00:00Z' },
  ],
  follows: [
    { follower_id: 'user-a', following_id: 'alias-b' },
    { follower_id: 'user-a', following_id: 'alias-b' },
  ],
  posts: [
    { id: 'post-1', user_id: 'alias-b' },
    { id: 'post-1', user_id: 'alias-b' },
  ],
  metric: 'followers',
  limit: 10,
})
assert.strictEqual(rankedProfiles[0].id, 'user-b')
assert.strictEqual(rankedProfiles[0].followers_count, 1)
assert.strictEqual(rankedProfiles[0].post_count, 1)
assert.strictEqual(rankedProfiles[1].following_count, 1)

assert.deepStrictEqual(
  profilePatchFromBody({
    profileWelcome: 'Hola',
    interests: null,
    hobbies: null,
    linkedGames: null,
    avatarDurationMs: null,
    isPrivate: null,
  }),
  { profile_welcome: 'Hola' },
  'null fields in a partial PATCH must be ignored',
)
assert.deepStrictEqual(
  profilePatchFromBody({ interests: [], hobbies: [], profileWelcome: '' }),
  { interests: [], hobbies: [], profile_welcome: '' },
  'explicit empty values must remain intentional clears',
)
assert.deepStrictEqual(
  profileClearFields({ interests: [], hobbies: [], linked_games: ['Minecraft'], profile_welcome: '' }),
  ['interests', 'hobbies', 'profile_welcome'],
)

const parsed = parsePublicStorageObjectUrl(
  'https://api.example.test/storage/v1/object/public/videos/user-1/videos/reel.mp4?v=1',
  ['videos'],
)
assert.deepStrictEqual(parsed, {
  bucket: 'videos',
  objectPath: 'user-1/videos/reel.mp4',
})

assert.strictEqual(
  parsePublicStorageObjectUrl('https://evil.example/reel.mp4', ['videos']),
  null,
)
assert.strictEqual(
  parsePublicStorageObjectUrl(
    'https://api.example.test/storage/v1/object/public/chat-media/user-1/reel.mp4',
    ['videos'],
  ),
  null,
)
assert.strictEqual(
  sameStorageObjectReference(
    'https://api.example.test/storage/v1/object/public/chat-media/user-1/photo.jpg?v=1',
    'https://other-host.test/storage/v1/object/public/chat-media/user-1/photo.jpg?v=2',
  ),
  true,
)
assert.strictEqual(
  sameStorageObjectReference(
    'https://api.example.test/storage/v1/object/public/chat-media/user-1/photo.jpg',
    'https://api.example.test/storage/v1/object/public/chat-media/user-2/photo.jpg',
  ),
  false,
)
assert.strictEqual(
  parsePublicStorageObjectUrl(
    'javascript:/storage/v1/object/public/videos/user-1/reel.mp4',
    ['videos'],
  ),
  null,
)

assert.deepStrictEqual(
  normalizeProfileSectionOrder(['music', 'music', 'invalid', 'welcome']).slice(0, 2),
  ['music', 'welcome'],
)
assert.strictEqual(normalizeProfileSectionVisibility({ music: 'connections' }).music, 'followers')
assert.strictEqual(normalizeProfileSectionVisibility({ hobbies: 'invalid' }).hobbies, 'everyone')

const protectedProfile = {
  profile_welcome: 'Hola',
  favorite_music_url: 'https://example.test/song.mp3',
  hobbies: ['Escalada'],
  interests: ['Fotografía'],
  profile_section_visibility: {
    welcome: 'only_me',
    music: 'followers',
    hobbies: 'followers',
    interests: 'everyone',
  },
}
assert.strictEqual(profileForViewer(protectedProfile).profile_welcome, '')
assert.strictEqual(profileForViewer(protectedProfile).favorite_music_url, '')
assert.deepStrictEqual(profileForViewer(protectedProfile).hobbies, [])
assert.deepStrictEqual(profileForViewer(protectedProfile).interests, ['Fotografía'])
assert.strictEqual(
  profileForViewer(protectedProfile, { isFollowing: true }).favorite_music_url,
  'https://example.test/song.mp3',
)
assert.strictEqual(
  profileForViewer(protectedProfile, { viewerIsTarget: true }).profile_welcome,
  'Hola',
)

assert.ok(
  recommendationPreferenceScore({ profileTermOverlap: 1 }) >
    recommendationPreferenceScore({ behavioralTagOverlap: 2 }),
  'an explicit profile preference must outweigh a single viewed video with two tags',
)
assert.strictEqual(
  recommendationPreferenceScore({ behavioralTagOverlap: 100, profileTermOverlap: 100 }),
  recommendationPreferenceScore({ behavioralTagOverlap: 3, profileTermOverlap: 3 }),
  'preference signals must be capped to prevent tag stuffing',
)

async function runOwnershipChecks() {
  const calls = []
  const pool = {
    query: async (_sql, params) => {
      calls.push(params)
      return { rows: params[1] === 'owner-1' ? [{ '?column?': 1 }] : [] }
    },
  }

  assert.strictEqual(
    await currentUserOwnsStorageObject(
      pool,
      'owner-1',
      'https://api.example.test/storage/v1/object/public/avatars/owner-1/avatar.jpg?v=7',
      ['avatars'],
    ),
    true,
  )
  assert.deepStrictEqual(calls[0], ['avatars', 'owner-1', ['owner-1/avatar.jpg']])
  assert.strictEqual(
    await currentUserOwnsStorageObject(pool, 'owner-1', 'https://evil.example/avatar.jpg', ['avatars']),
    false,
  )
  assert.strictEqual(calls.length, 1, 'invalid URLs must be rejected before querying storage metadata')

  assert.strictEqual(
    await currentUserOwnsStorageObjects(
      pool,
      'owner-1',
      [
        'https://api.example.test/storage/v1/object/public/post-media/owner-1/one.jpg',
        'https://api.example.test/storage/v1/object/public/post-media/owner-1/two.jpg',
      ],
      ['post-media'],
    ),
    true,
  )
  assert.strictEqual(
    await currentUserOwnsStorageObjects(
      pool,
      'owner-2',
      ['https://api.example.test/storage/v1/object/public/post-media/owner-1/one.jpg'],
      ['post-media'],
    ),
    false,
  )
  assert.strictEqual(await currentUserOwnsStorageObjects(pool, 'owner-1', [], ['post-media']), true)
  assert.strictEqual(await currentUserMayUseReportEvidence(pool, 'owner-1', null), true)
  assert.strictEqual(
    await currentUserMayUseReportEvidence(
      pool,
      'owner-1',
      'https://api.example.test/storage/v1/object/public/report-evidence/owner-1/reports/evidence.png',
    ),
    true,
  )
  assert.strictEqual(
    await currentUserMayUseReportEvidence(
      pool,
      'owner-2',
      'https://api.example.test/storage/v1/object/public/report-evidence/owner-1/reports/evidence.png',
    ),
    false,
  )

  const existingObjectPool = {
    query: async (_sql, params) => ({
      rows: params[0] === 'chat-media' && params[1] === 'owner-1/stickers/hola.png' ? [{}] : [],
    }),
  }
  assert.strictEqual(
    await storageObjectExists(
      existingObjectPool,
      'https://api.example.test/storage/v1/object/public/chat-media/owner-1/stickers/hola.png',
      ['chat-media'],
    ),
    true,
  )
  assert.strictEqual(
    await storageObjectExists(existingObjectPool, 'https://evil.example/hola.png', ['chat-media']),
    false,
  )
}

async function runBestEffortNotificationCheck() {
  const originalConsoleError = console.error
  console.error = () => {}
  try {
    const result = await createLocalNotificationBestEffort(
      { query: async () => { throw new Error('notification db unavailable') } },
      { recipientId: 'recipient-1', actorId: 'actor-1', type: 'new_moment' },
    )
    assert.strictEqual(result, null)
  } finally {
    console.error = originalConsoleError
  }
}

Promise.all([runOwnershipChecks(), runBestEffortNotificationCheck()])
  .then(() => console.log('data-routes tests passed'))
  .catch((error) => {
    console.error(error)
    process.exitCode = 1
  })
