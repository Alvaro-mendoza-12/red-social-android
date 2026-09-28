const assert = require('assert')
const fs = require('fs')
const path = require('path')
const {
  buildProfileForUser,
  buildPublicSemanticSearchCorpus,
  canJoinVoiceRoom,
  cleanSet,
  collectionRoleCapabilities,
  communityChannelPayload,
  communityEventPayload,
  communityMemberProfilePayload,
  createLocalNotificationBestEffort,
  currentUserMayUseReportEvidence,
  currentUserOwnsStorageObject,
  currentUserOwnsStorageObjects,
  feedPostVisible,
  followedProfileIds,
  enrichNotificationTarget,
  isChatActivityNotification,
  isMessagingModuleNotification,
  messageContentOf,
  mapVoiceRoom,
  notificationContentIndexes,
  normalizeMessageRow,
  previewFromMessage,
  normalizeProfileSectionOrder,
  normalizeProfileSectionVisibility,
  normalizeProfileForPublic,
  normalizeProfileVisibility,
  normalizeFeedMode,
  normalizeCollectionRole,
  normalizeCommunityChannelType,
  normalizeSavedCollectionName,
  normalizeSavedTags,
  parsePublicStorageObjectUrl,
  profileClearFields,
  profileForViewer,
  profileVisibleToViewer,
  profilePatchFromBody,
  rankPublicProfiles,
  reportedVideoCommentIds,
  recommendationPreferenceScore,
  legacyVideoRecommendationScore,
  stableVideoExploreScore,
  canonicalVideoSoundId,
  videoMatchesSound,
  importedRecentRows,
  localActivityNotificationsByRecipientIds,
  localVideos,
  localVideosByOwnerIds,
  localPostsByIds,
  localPostsByOwnerIds,
  localMomentsByIds,
  localNotificationsByRecipientIds,
  localReportsByReporterIds,
  importedRowsByFieldValues,
  importedChatsForParticipantIds,
  localChatParticipantsByUserIds,
  localChatsByIds,
  localChatsForParticipantIds,
  localCollectionMembersByRoomIds,
  localCollectionRoomsByIds,
  localFollowsByFollowerIds,
  localFollowsByFollowingIds,
  localFollowsByParticipantIds,
  publicProfilesByIds,
  publicProfileSearchCandidates,
  localVideosByIds,
  localVideosBySoundIds,
  localVoiceRoomsByAliases,
  sameStorageObjectReference,
  storageObjectExists,
  userIdentity,
  voiceRoomRowsByAliases,
} = require('../src/data-routes')

assert.strictEqual(normalizeProfileVisibility('connections'), 'followers')
assert.strictEqual(normalizeProfileVisibility('unexpected-value'), 'everyone')
assert.strictEqual(
  profileVisibleToViewer({ id: 'private-profile', profile_visibility: 'connections' }, {
    viewerIds: new Set(['viewer']),
    isFollowing: false,
  }),
  false,
)
assert.strictEqual(
  profileVisibleToViewer({ id: 'private-profile', profile_visibility: 'connections' }, {
    viewerIds: new Set(['viewer']),
    isFollowing: true,
  }),
  true,
)
assert.strictEqual(
  profileVisibleToViewer({ id: 'private-profile', profile_visibility: 'only_me' }, {
    viewerIds: new Set(['private-profile']),
  }),
  true,
)
assert.deepStrictEqual(
  Array.from(followedProfileIds([
    { follower_id: 'viewer', following_id: 'profile-a' },
    { follower_id: 'someone-else', following_id: 'profile-b' },
    { user_id: 'VIEWER', target_user_id: 'profile-c' },
  ], new Set(['viewer']))).sort(),
  ['profile-a', 'profile-c'],
)

assert.strictEqual(isChatActivityNotification({ type: 'message' }), true)
assert.strictEqual(isChatActivityNotification({ type: 'group_message' }), true)
assert.strictEqual(
  isChatActivityNotification({
    type: 'message',
    extra_data: { notification_type: 'account_suspended' },
  }),
  false,
)
assert.notStrictEqual(
  stableVideoExploreScore('account-a', 'video-1', '2026-08-21'),
  stableVideoExploreScore('account-b', 'video-1', '2026-08-21'),
  'exploration must be scoped to the authenticated account',
)
assert.notStrictEqual(
  stableVideoExploreScore('account-a', 'video-1', '2026-08-21:session-a'),
  stableVideoExploreScore('account-a', 'video-1', '2026-08-21:session-b'),
  'a new feed session must vary equivalent candidates for the same account',
)
assert.ok(
  legacyVideoRecommendationScore({
    video: { id: 'seen', views: 1_000_000, created_at: '2026-08-21T00:00:00Z' },
    viewerId: 'account-a',
    daySeed: '2026-08-21',
    seen: true,
    now: Date.parse('2026-08-21T12:00:00Z'),
  }) < legacyVideoRecommendationScore({
    video: { id: 'fresh', views: 0, created_at: '2026-08-21T00:00:00Z' },
    viewerId: 'account-a',
    daySeed: '2026-08-21',
    seen: false,
    now: Date.parse('2026-08-21T12:00:00Z'),
  }),
  'a previously seen viral video must not automatically outrank unseen discovery content',
)
assert.equal(canonicalVideoSoundId({ id: 'legacy-video' }), 'legacy-video')
assert.equal(videoMatchesSound({ id: 'source', sound_id: 'root-sound' }, 'root-sound'), true)

const dataRoutesSource = fs.readFileSync(path.join(__dirname, '..', 'src', 'data-routes.js'), 'utf8')
assert.match(dataRoutesSource, /app\.post\('\/v1\/data\/feed\/posts\/:postId\/share'/)
assert.match(dataRoutesSource, /app\.delete\('\/v1\/data\/feed\/posts\/:postId\/share'/)
const videoDeletionTablePosition = dataRoutesSource.indexOf('CREATE TABLE IF NOT EXISTS fallback_video_deletions')
const videoDeletionIndexPosition = dataRoutesSource.indexOf('CREATE INDEX IF NOT EXISTS idx_fallback_video_deletions_lookup')
assert.ok(videoDeletionTablePosition >= 0, 'video deletion table DDL must exist')
assert.ok(
  videoDeletionIndexPosition > videoDeletionTablePosition,
  'fresh database startup must create fallback_video_deletions before indexing it',
)
const firstTablePositions = new Map()
for (const match of dataRoutesSource.matchAll(/CREATE\s+TABLE\s+IF\s+NOT\s+EXISTS\s+([a-zA-Z_][a-zA-Z0-9_]*)/gi)) {
  const table = match[1].toLowerCase()
  if (!firstTablePositions.has(table)) firstTablePositions.set(table, match.index)
}
for (const match of dataRoutesSource.matchAll(/CREATE\s+(?:UNIQUE\s+)?INDEX\s+IF\s+NOT\s+EXISTS\s+[a-zA-Z_][a-zA-Z0-9_]*\s+ON\s+([a-zA-Z_][a-zA-Z0-9_]*)/gi)) {
  const table = match[1].toLowerCase()
  const tablePosition = firstTablePositions.get(table)
  if (tablePosition !== undefined) {
    assert.ok(match.index > tablePosition, `fresh database startup must create ${table} before indexing it`)
  }
}
const postDetailSource = dataRoutesSource.slice(
  dataRoutesSource.indexOf('async function loadPostDetailPayload'),
  dataRoutesSource.indexOf('function collectionConfig'),
)
assert.doesNotMatch(postDetailSource, /importedRows\(pool,\s*'(?:profiles|posts|post_likes|post_reactions|post_comments|shares|follows)'/)
assert.doesNotMatch(postDetailSource, /\b(?:localPosts|combinedFollows)\(pool/)
const ownProfileRouteSource = dataRoutesSource.slice(
  dataRoutesSource.indexOf("app.get('/v1/data/profile/me'"),
  dataRoutesSource.indexOf("app.patch('/v1/data/profile/me'"),
)
assert.doesNotMatch(ownProfileRouteSource, /\b(?:combinedFollows|localPosts|localVideos|localReports)\(pool/)
assert.doesNotMatch(ownProfileRouteSource, /importedRows\(pool,\s*'(?:profiles|posts|videos|chats|follows|reports)'/)
const profileDetailRouteSource = dataRoutesSource.slice(
  dataRoutesSource.indexOf("app.get('/v1/data/profiles/:profileId'"),
  dataRoutesSource.indexOf("app.get('/v1/data/follows'"),
)
assert.doesNotMatch(profileDetailRouteSource, /\b(?:allPublicProfiles|combinedFollows|localPosts)\(pool/)
assert.doesNotMatch(profileDetailRouteSource, /importedRows\(pool,\s*'(?:profiles|posts|post_likes|post_reactions|post_comments|follows)'/)
const profileSearchRouteSource = dataRoutesSource.slice(
  dataRoutesSource.indexOf("app.get('/v1/data/profiles/search'"),
  dataRoutesSource.indexOf("app.get('/v1/data/profiles/ranking'"),
)
assert.doesNotMatch(profileSearchRouteSource, /\b(?:allPublicProfiles|combinedFollows)\(pool/)
const followsRouteSource = dataRoutesSource.slice(
  dataRoutesSource.indexOf("app.get('/v1/data/follows'"),
  dataRoutesSource.indexOf("app.post('/v1/data/profiles/:profileId/follow'"),
)
assert.doesNotMatch(followsRouteSource, /\b(?:allPublicProfiles|combinedFollows|localFollows)\(pool/)
const notificationsRouteSource = dataRoutesSource.slice(
  dataRoutesSource.indexOf("app.get('/v1/data/notifications'"),
  dataRoutesSource.indexOf("app.post('/v1/data/notifications'"),
)
assert.doesNotMatch(notificationsRouteSource, /\b(?:allPublicProfiles|localNotifications|localPosts|localVideos|localMoments)\(pool/)
assert.doesNotMatch(notificationsRouteSource, /importedRows\(pool,\s*'(?:notifications|profiles|posts|videos|moments)'/)
assert.doesNotMatch(notificationsRouteSource, /localNotificationsByRecipientIds\(/)
assert.doesNotMatch(notificationsRouteSource, /20_000/)
assert.match(notificationsRouteSource, /localActivityNotificationsByRecipientIds\(/)
const savedCollectionsRouteSource = dataRoutesSource.slice(
  dataRoutesSource.indexOf("app.get('/v1/data/saved-collections'"),
  dataRoutesSource.indexOf("app.post('/v1/data/saved-collections'"),
)
assert.doesNotMatch(savedCollectionsRouteSource, /\b(?:allPublicProfiles|combinedFollows|localPosts|localFollows)\(pool/)
assert.doesNotMatch(savedCollectionsRouteSource, /importedRows\(pool,\s*'(?:profiles|posts|follows)'/)
const privateInboxStart = dataRoutesSource.indexOf('        directImportedChats,')
assert.ok(privateInboxStart >= 0, 'private inbox targeted read block must exist')
const privateInboxRouteSource = dataRoutesSource.slice(
  privateInboxStart,
  dataRoutesSource.indexOf("app.post('/v1/data/messages/chats'"),
)
assert.doesNotMatch(privateInboxRouteSource, /\b(?:allPublicProfiles|localChats)\(pool/)
assert.doesNotMatch(privateInboxRouteSource, /importedRows\(pool,\s*'(?:profiles|chats|chat_participants|messages)'/)
const privateChatDetailRouteSource = dataRoutesSource.slice(
  dataRoutesSource.indexOf("app.get('/v1/data/messages/chats/:chatId'"),
  dataRoutesSource.indexOf("app.post('/v1/data/messages/chats/:chatId/messages'"),
)
assert.doesNotMatch(privateChatDetailRouteSource, /\b(?:allPublicProfiles|localChats)\(pool/)
assert.doesNotMatch(privateChatDetailRouteSource, /importedRows\(pool,\s*'(?:profiles|chats|chat_participants|messages)'/)
const collectionRoomPayloadSource = dataRoutesSource.slice(
  dataRoutesSource.indexOf('async function loadCollectionRoomPayload'),
  dataRoutesSource.indexOf('function voiceRoomId'),
)
assert.doesNotMatch(collectionRoomPayloadSource, /\b(?:localCollectionRooms|localCollectionMembers)\(pool/)
assert.doesNotMatch(collectionRoomPayloadSource, /importedRows\(pool/)
assert.doesNotMatch(collectionRoomPayloadSource, /roomMembers\.length\s*===\s*0/)
const forwardedMediaSource = dataRoutesSource.slice(
  dataRoutesSource.indexOf('async function forwardedMessageMediaIsAccessible'),
  dataRoutesSource.indexOf('async function currentUserMayReferenceMessageMedia'),
)
assert.doesNotMatch(forwardedMediaSource, /importedRows\(pool/)
const friendsRouteSource = dataRoutesSource.slice(
  dataRoutesSource.indexOf("app.get('/v1/data/friends'"),
  dataRoutesSource.indexOf("app.post('/v1/data/friends'"),
)
assert.doesNotMatch(friendsRouteSource, /importedRows\(pool/)
assert.equal(videoMatchesSound({ id: 'other', sound_id: 'another-sound' }, 'root-sound'), false)
assert.strictEqual(isChatActivityNotification({ type: 'comment_post' }), false)
assert.strictEqual(isMessagingModuleNotification({ type: 'call_invite' }), true)
assert.strictEqual(isMessagingModuleNotification({ type: 'call_response' }), true)
assert.strictEqual(isMessagingModuleNotification({ type: 'community_member_added' }), true)
assert.strictEqual(
  isMessagingModuleNotification({
    type: 'activity',
    target_type: 'call',
    content: 'Llamada entrante',
  }),
  true,
)
assert.strictEqual(isMessagingModuleNotification({ type: 'admin_access_audit', target_type: 'profile' }), false)

const notificationIndexes = notificationContentIndexes({
  posts: [
    {
      id: 'post-1',
      content: 'Publicación sin foto',
      media_urls: ['https://cdn.example.com/post-1.jpg'],
    },
  ],
  videos: [
    {
      id: 'video-1',
      thumbnail_url: 'https://cdn.example.com/video-1.jpg',
    },
  ],
  moments: [
    {
      id: 'moment-1',
      media_url: 'https://cdn.example.com/moment-1.jpg',
    },
  ],
})
assert.deepStrictEqual(
  enrichNotificationTarget(
    {
      type: 'comment_video',
      target_id: 'video-1',
      target_type: 'video',
      extra_data: { comment_id: 'comment-1' },
    },
    notificationIndexes,
  ).extra_data,
  {
    comment_id: 'comment-1',
    video_id: 'video-1',
    content_id: 'video-1',
    content_type: 'video',
    target_thumbnail_url: 'https://cdn.example.com/video-1.jpg',
  },
)
assert.strictEqual(
  enrichNotificationTarget(
    {
      type: 'like_post',
      target_id: 'post-1',
      target_type: 'post',
      extra_data: {},
    },
    notificationIndexes,
  ).extra_data.target_thumbnail_url,
  'https://cdn.example.com/post-1.jpg',
)
assert.strictEqual(
  enrichNotificationTarget(
    {
      type: 'comment_post',
      target_id: 'post-1',
      target_type: 'post',
      extra_data: { comment_id: 'comment-post-1' },
    },
    notificationIndexes,
  ).extra_data.target_preview_text,
  'Publicación sin foto',
)

const contactJson = '{"name":"Ada","phones":["+593999999999"],"emails":[]}'
assert.strictEqual(messageContentOf({ content: contactJson }), contactJson)
assert.strictEqual(
  messageContentOf({ content: { name: 'Ada', phones: ['+593999999999'], emails: [] } }),
  contactJson,
)
assert.strictEqual(normalizeMessageRow({ content: contactJson, content_type: 'contact' }).content, contactJson)
assert.strictEqual(previewFromMessage({ content: contactJson, content_type: 'contact' }), 'Contacto')

const semanticCorpus = buildPublicSemanticSearchCorpus({
  profiles: [
    { id: 'public-owner', display_name: 'Pública', break_mode: 'off' },
    { id: 'hidden-owner', display_name: 'Oculta', break_mode: 'hidden' },
  ],
  posts: [
    { id: 'post-public', user_id: 'public-owner', content: 'Recetas nutritivas', visibility: 'public' },
    { id: 'post-private', user_id: 'public-owner', content: 'Secreto', visibility: 'private' },
    { id: 'post-hidden', user_id: 'hidden-owner', content: 'No indexar', visibility: 'public' },
  ],
  videos: [
    { id: 'video-public', user_id: 'public-owner', title: 'Cocina saludable', is_public: true },
    { id: 'video-private', user_id: 'public-owner', title: 'Privado', is_public: false },
  ],
})
assert.deepStrictEqual(
  semanticCorpus.map((item) => item.searchId).sort(),
  ['post:post-public', 'video:video-public'],
)

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
      voice_room_id: 'voice-room-1',
      voice_participant_count: 3,
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
    voiceRoomId: 'voice-room-1',
    voiceParticipantCount: 3,
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
  true,
  'Discover must include eligible public posts even when the creator is followed',
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
  profilePatchFromBody({ username_origin: 'chosen', bio: 'Descripción' }),
  { bio: 'Descripción' },
  'a client cannot change username provenance through a profile PATCH',
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

const recentPresenceTimestamp = new Date(Date.now() - 5_000).toISOString()
const publicOnlineOnly = normalizeProfileForPublic({
  id: 'presence-user',
  is_online: true,
  last_seen: recentPresenceTimestamp,
  online_status_visibility: 'everyone',
  last_seen_visibility: 'only_me',
})
assert.strictEqual(publicOnlineOnly.is_online, true)
assert.strictEqual(publicOnlineOnly.last_seen, null)
const publicLastSeenOnly = normalizeProfileForPublic({
  id: 'presence-user',
  is_online: true,
  last_seen: recentPresenceTimestamp,
  online_status_visibility: 'only_me',
  last_seen_visibility: 'everyone',
})
assert.strictEqual(publicLastSeenOnly.is_online, false)
assert.strictEqual(publicLastSeenOnly.last_seen, recentPresenceTimestamp)

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
assert.strictEqual(
  profileForViewer({ favorite_music_url: 'https://example.test/public-song.mp3' }).favorite_music_url,
  'https://example.test/public-song.mp3',
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

async function runVoiceRoomPrivacyChecks() {
  const privateRoom = {
    id: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
    creator_id: 'owner-1',
    is_public: false,
    password: 'legacy-secret',
  }
  const publicPayload = mapVoiceRoom(privateRoom, new Map(), 0)
  assert.strictEqual(publicPayload.password, undefined)
  assert.strictEqual(publicPayload.password_hash, undefined)
  assert.strictEqual(publicPayload.password_required, true)

  const pool = { query: async () => ({ rows: [] }) }
  const visitor = { id: 'visitor-1', email: 'visitor@example.test' }
  assert.strictEqual(await canJoinVoiceRoom(pool, visitor, privateRoom, 'wrong'), false)
  assert.strictEqual(await canJoinVoiceRoom(pool, visitor, privateRoom, 'legacy-secret'), true)
  assert.strictEqual(await canJoinVoiceRoom(pool, { id: 'owner-1' }, privateRoom, ''), true)
}

async function runLocalVideoSoundColumnsCheck() {
  let capturedSql = ''
  const pool = {
    query: async (sql) => {
      capturedSql = String(sql)
      return { rows: [] }
    },
  }
  await localVideos(pool, 20)
  for (const column of [
    'sound_id',
    'sound_source_video_id',
    'sound_title',
    'sound_artist',
    'sound_url',
  ]) {
    assert.match(capturedSql, new RegExp(`\\b${column}\\b`), `local video reads must preserve ${column}`)
  }
}

async function runTargetedVideoReadChecks() {
  const calls = []
  const pool = {
    query: async (sql, params) => {
      calls.push({ sql: String(sql), params })
      if (String(sql).includes('information_schema.tables')) return { rows: [{ '?column?': 1 }] }
      return { rows: [] }
    },
  }
  await importedRowsByFieldValues(pool, 'videos', ['id', 'sound_id'], ['video-1'], 5)
  const importedRead = calls.find((call) => call.sql.includes('supabase_import."videos"'))
  assert.ok(importedRead)
  assert.match(importedRead.sql, /to_jsonb\(t\)->>'id'/)
  assert.match(importedRead.sql, /to_jsonb\(t\)->>'sound_id'/)
  assert.deepStrictEqual(importedRead.params, [['video-1'], 5])

  await localVideosByIds(pool, ['video-1'])
  const localIdRead = calls.find((call) => call.sql.includes('FROM fallback_videos') && call.sql.includes('lower(id::text) = ANY'))
  assert.deepStrictEqual(localIdRead.params, [['video-1']])

  await localVideosBySoundIds(pool, ['sound-1'])
  const soundRead = calls.find((call) => call.sql.includes("lower(COALESCE(sound_id, ''))"))
  assert.deepStrictEqual(soundRead.params, [['sound-1']])
}

async function runTargetedPublicProfileReadChecks() {
  const calls = []
  const pool = {
    query: async (sql, params) => {
      calls.push({ sql: String(sql), params })
      if (String(sql).includes('information_schema.tables')) return { rows: [{ '?column?': 1 }] }
      return { rows: [] }
    },
  }
  await publicProfilesByIds(pool, ['profile-1', 'PROFILE-2'])
  const importedRead = calls.find((call) => call.sql.includes('supabase_import."profiles"'))
  const fallbackRead = calls.find((call) => call.sql.includes('FROM fallback_users u'))
  assert.ok(importedRead, 'targeted imported profile read must be issued')
  assert.ok(fallbackRead, 'targeted fallback profile read must be issued')
  assert.deepStrictEqual(importedRead.params[0], ['profile-1', 'profile-2'])
  assert.deepStrictEqual(fallbackRead.params[0], ['profile-1', 'profile-2'])
  assert.match(fallbackRead.sql, /lower\(u\.id::text\) = ANY/)

  assert.deepStrictEqual(
    Array.from(cleanSet(new Set(['PROFILE-2', 'profile-1', 'PROFILE-2']))),
    ['profile-2', 'profile-1'],
  )

  await publicProfileSearchCandidates(pool, 'Ana', 25)
  const importedSearch = calls.find((call) => call.sql.includes('FROM supabase_import.profiles AS t'))
  const fallbackSearch = calls.find((call) => call.sql.includes('FROM fallback_users u') && call.sql.includes("$1 = ''"))
  assert.deepStrictEqual(importedSearch.params, ['ana', '%ana%', 25])
  assert.deepStrictEqual(fallbackSearch.params, ['ana', '%ana%', 25])
}

async function runTargetedSocialFeedReadChecks() {
  const calls = []
  const pool = {
    query: async (sql, params) => {
      calls.push({ sql: String(sql), params })
      if (String(sql).includes('information_schema.tables')) return { rows: [{ '?column?': 1 }] }
      return { rows: [] }
    },
  }

  await importedRecentRows(pool, 'posts', 240)
  const recentPosts = calls.find((call) => call.sql.includes('supabase_import."posts"'))
  assert.ok(recentPosts)
  assert.match(recentPosts.sql, /ORDER BY COALESCE/)
  assert.deepStrictEqual(recentPosts.params, [240])

  await localPostsByOwnerIds(pool, ['creator-1'], 80)
  const ownerPosts = calls.find((call) => call.sql.includes('FROM fallback_posts') && call.sql.includes('lower(user_id) = ANY'))
  assert.deepStrictEqual(ownerPosts.params, [['creator-1'], 80])

  await localPostsByIds(pool, ['post-1'])
  const postById = calls.find((call) => call.sql.includes('FROM fallback_posts') && call.sql.includes('lower(id::text) = ANY'))
  assert.deepStrictEqual(postById.params, [['post-1']])

  await localVideosByOwnerIds(pool, ['creator-1'], 80)
  const ownerVideos = calls.find((call) => call.sql.includes('FROM fallback_videos') && call.sql.includes('lower(user_id) = ANY'))
  assert.deepStrictEqual(ownerVideos.params, [['creator-1'], 80])

  await localReportsByReporterIds(pool, ['viewer-1'], 40)
  const reports = calls.find((call) => call.sql.includes('FROM fallback_reports') && call.sql.includes('lower(reporter_id) = ANY'))
  assert.deepStrictEqual(reports.params, [['viewer-1'], 40])

  await localNotificationsByRecipientIds(pool, ['viewer-1'], 60)
  const notifications = calls.find((call) => call.sql.includes('FROM fallback_notifications') && call.sql.includes('lower(recipient_id) = ANY'))
  assert.deepStrictEqual(notifications.params, [['viewer-1'], 60])

  await localMomentsByIds(pool, ['moment-1'])
  const moments = calls.find((call) => call.sql.includes('FROM fallback_moments') && call.sql.includes('lower(id::text) = ANY'))
  assert.deepStrictEqual(moments.params, [['moment-1']])

  await localFollowsByFollowerIds(pool, ['viewer-1'], 100)
  const follows = calls.find((call) => call.sql.includes('FROM fallback_follows') && call.sql.includes('lower(follower_id) = ANY'))
  assert.deepStrictEqual(follows.params, [['viewer-1'], 100])

  await localFollowsByFollowingIds(pool, ['creator-1'], 100)
  const followers = calls.find((call) => call.sql.includes('FROM fallback_follows') && call.sql.includes('lower(following_id) = ANY'))
  assert.deepStrictEqual(followers.params, [['creator-1'], 100])

  await localFollowsByParticipantIds(pool, ['creator-1'], 100)
  const participantFollows = calls.find((call) => (
    call.sql.includes('FROM fallback_follows') &&
    call.sql.includes('lower(follower_id) = ANY') &&
    call.sql.includes('lower(following_id) = ANY')
  ))
  assert.deepStrictEqual(participantFollows.params, [['creator-1'], 100])
}

async function runNotificationScalingChecks() {
  const calls = []
  const pool = {
    query: async (sql, params) => {
      calls.push({ sql: String(sql), params })
      return {
        rows: [{
          id: 'notification-1',
          recipient_id: 'viewer-1',
          type: 'new_follower',
          is_read: false,
          extra_data: {},
          created_at: '2026-08-27T12:00:00.000Z',
          updated_at: '2026-08-27T12:00:00.000Z',
          local_only: true,
          unread_count_total: '17',
        }],
      }
    },
  }

  const result = await localActivityNotificationsByRecipientIds(
    pool,
    ['VIEWER-1', 'viewer-1'],
    { limit: 25, unreadOnly: true },
  )
  assert.strictEqual(calls.length, 1)
  assert.match(calls[0].sql, /COUNT\(\*\) FILTER \(WHERE is_read = FALSE\) OVER \(\)/)
  assert.match(calls[0].sql, /ORDER BY created_at DESC, id DESC/)
  assert.match(calls[0].sql, /LIMIT \$6/)
  assert.deepStrictEqual(calls[0].params[0], ['viewer-1'])
  assert.strictEqual(calls[0].params[4], true)
  assert.strictEqual(calls[0].params[5], 25)
  assert.strictEqual(result.unreadCount, 17)
  assert.strictEqual(result.rows.length, 1)
  assert.strictEqual(result.rows[0].unread_count_total, undefined)

  const empty = await localActivityNotificationsByRecipientIds(pool, [], { limit: 500 })
  assert.deepStrictEqual(empty, { rows: [], unreadCount: 0 })
  assert.strictEqual(calls.length, 1)
}

async function runTargetedChatReadChecks() {
  const calls = []
  const pool = {
    query: async (sql, params) => {
      calls.push({ sql: String(sql), params })
      if (String(sql).includes('information_schema.tables')) return { rows: [{ '?column?': 1 }] }
      return { rows: [] }
    },
  }

  await importedChatsForParticipantIds(pool, ['viewer-1'], 80)
  const importedChats = calls.find((call) => call.sql.includes('FROM supabase_import.chats AS t'))
  assert.ok(importedChats)
  assert.match(importedChats.sql, /jsonb_array_elements_text/)
  assert.deepStrictEqual(importedChats.params, [['viewer-1'], 80])

  await localChatsForParticipantIds(pool, ['viewer-1'], 80)
  const participantChats = calls.find((call) => call.sql.includes('FROM fallback_chats c'))
  assert.match(participantChats.sql, /fallback_chat_participants cp/)
  assert.deepStrictEqual(participantChats.params, [['viewer-1'], 80])

  await localChatsByIds(pool, ['chat-1'])
  const chatsById = calls.find((call) => call.sql.includes('FROM fallback_chats') && call.sql.includes('lower(id::text) = ANY'))
  assert.deepStrictEqual(chatsById.params, [['chat-1']])

  await localChatParticipantsByUserIds(pool, ['viewer-1'], 80)
  const participants = calls.find((call) => call.sql.includes('FROM fallback_chat_participants') && call.sql.includes('lower(user_id) = ANY'))
  assert.deepStrictEqual(participants.params, [['viewer-1'], 80])
}

async function runTargetedRoomReadChecks() {
  const calls = []
  const pool = {
    query: async (sql, params) => {
      calls.push({ sql: String(sql), params })
      if (String(sql).includes('information_schema.tables')) return { rows: [{ '?column?': 1 }] }
      return { rows: [] }
    },
  }

  await localCollectionRoomsByIds(pool, 'community', ['room-1'])
  const collectionRooms = calls.find((call) => call.sql.includes('FROM fallback_collection_rooms'))
  assert.deepStrictEqual(collectionRooms.params, ['community', ['room-1']])

  await localCollectionMembersByRoomIds(pool, 'community', ['room-1'], 80)
  const collectionMembers = calls.find((call) => call.sql.includes('FROM fallback_collection_members'))
  assert.deepStrictEqual(collectionMembers.params, ['community', ['room-1'], 80])

  await localVoiceRoomsByAliases(pool, ['voice-1'])
  const localVoiceRoomsRead = calls.find((call) => call.sql.includes('FROM fallback_voice_rooms'))
  assert.deepStrictEqual(localVoiceRoomsRead.params, [['voice-1']])

  await voiceRoomRowsByAliases(pool, ['voice-2'])
  const importedVoiceRoomsRead = calls.find((call) => call.sql.includes('supabase_import."voice_rooms"'))
  assert.ok(importedVoiceRoomsRead)
  assert.deepStrictEqual(importedVoiceRoomsRead.params, [['voice-2'], 8])
}

async function runReportedVideoCommentPrivacyChecks() {
  const queries = []
  const pool = { query: async (sql, params) => {
    queries.push({ sql: String(sql), params })
    return { rows: [{ comment_id: 'COMMENT-ONE' }] }
  } }
  const reported = await reportedVideoCommentIds(pool, 'VIDEO-ONE', {
    values: ['VIEWER-ONE', 'VIEWER-ALIAS'],
  })
  assert.deepStrictEqual(Array.from(reported), ['comment-one'])
  assert.deepStrictEqual(queries[0].params, ['video-one', ['viewer-one', 'viewer-alias']])
  assert.match(queries[0].sql, /lower\(video_id\) = \$1 AND lower\(reporter_id\) = ANY/)
  assert.deepStrictEqual(Array.from(await reportedVideoCommentIds(pool, '', { values: ['viewer-one'] })), [])
  assert.strictEqual(queries.length, 1)
}

Promise.all([
  runOwnershipChecks(),
  runBestEffortNotificationCheck(),
  runVoiceRoomPrivacyChecks(),
  runLocalVideoSoundColumnsCheck(),
  runTargetedVideoReadChecks(),
  runTargetedPublicProfileReadChecks(),
  runTargetedSocialFeedReadChecks(),
  runNotificationScalingChecks(),
  runTargetedChatReadChecks(),
  runTargetedRoomReadChecks(),
  runReportedVideoCommentPrivacyChecks(),
])
  .then(() => console.log('data-routes tests passed'))
  .catch((error) => {
    console.error(error)
    process.exitCode = 1
  })
