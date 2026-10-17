const { loadCollectionMembershipState, collectionHistoryAllows } = require('./collection-membership-policy')
const { normalizeCompatProfileJson } = require('./compat-profile-json')
const { deleteAccountMusic } = require('./music-library-routes')
const { scanEnforcementEnabled, readyStorageSql, trustedStorageUrl } = require('./storage-media-reference')
const scanReadySql = readyStorageSql()
const crypto = require('crypto')
const { redactDeletedMessage, mergeMessageRows, messageTables, isDeletedForAll, refreshChatSummary, notificationMessageScope, deleteMessageForAll, processMessageDeletionOutbox, filterDeletedMessageNotifications, hiddenForIdentity, isRecallPatch, isHidePatch, hideDeletedMessageForActor } = require('./message-deletion-policy')
const { deleteImportedRow, deleteImportedPollVotes, purgeImportedRows } = require('./imported-mutation-capabilities')
const { isRetiredNotification } = require('./retired-notifications')
const { isServerManagedNotification } = require('./notification-event-policy')
const fs = require('fs')
const jwt = require('jsonwebtoken')
const {
  publishChatMessageEvent,
  publishChallengeLiveEvent,
  publishRealtimeEvent,
} = require('./realtime-publish')
const { appAdminHasCapability, isAppAdmin, recordModerationEvent } = require('./admin-moderation-routes')
const { ADMIN_CAPABILITY } = require('./admin-capabilities')
const { accountSuspendedPayload, findActiveSuspension } = require('./account-status')
const { invalidateImportedDataCache, currentUserMayReferenceMessageMedia } = require('./data-routes')
const { sendPushForNotification } = require('./push-notifications')
const { removeStorageObjectFiles } = require('./storage-routes')
const {
  cancelScheduledMessageJob,
  listScheduledMessageJobs,
  scheduleMessageJob,
} = require('./scheduled-message-jobs')
const { liveGiftLimitsConfiguration } = require('./gift-safety-config')
const { googlePlayRtdnConfiguration } = require('./google-play-rtdn')
const { applyGiftToActiveBattle } = require('./live-battle-scoring')
const { stableKey } = require('./request-security')
const { safeErrorLogDetails } = require('./request-observability')
const { publishPollUpdate } = require('./poll-update-publish')
const { cleanUsername, usernameOwnedByAnother, usernameProblem } = require('./username-policy')
const { introPolicyError } = require('./direct-message-request-policy')
const { catalogAllowsStickerMedia, stickerPackReadable, stickerRowDeleted } = require('./sticker-media-policy')
const {
  deleteTrustedContentClassification,
  ingestTrustedContentClassification,
} = require('./algorithm-content-ingestion')
const {
  COIN_SOURCE_TYPES,
  allocateCoinLots,
  netWithdrawableGiftAmount,
  nonNegativeInteger,
  normalizeCoinLot,
  summarizeCoinLots,
} = require('./economic-provenance')

const MAX_READ_ROWS = Number(process.env.POSTGREST_COMPAT_MAX_READ_ROWS || 10000)
const RESERVED_QUERY_KEYS = new Set([
  'select',
  'order',
  'limit',
  'offset',
  'or',
  'and',
  'apikey',
])

const DEFAULT_VIRTUAL_GIFTS = [
  { id: '7a100001-0000-4000-8000-000000000001', name: 'Semilla Estelar', description: 'Una chispa pequeña para iniciar la celebración', image_url: 'conectate://gift-assets/starlight-seed', price_diamonds: 5, price_credits: 0, animation_url: 'conectate://gift-animations/starlight-seed', sort_order: 1 },
  { id: '7a100002-0000-4000-8000-000000000002', name: 'Gota Aurora', description: 'Una gota de luz suave para saludar al creador', image_url: 'conectate://gift-assets/aurora-drop', price_diamonds: 10, price_credits: 0, animation_url: 'conectate://gift-animations/aurora-drop', sort_order: 2 },
  { id: '7a100003-0000-4000-8000-000000000003', name: 'Chispa Prisma', description: 'Un destello multicolor que despierta el directo', image_url: 'conectate://gift-assets/prism-spark', price_diamonds: 15, price_credits: 0, animation_url: 'conectate://gift-animations/prism-spark', sort_order: 3 },
  { id: '7a100004-0000-4000-8000-000000000004', name: 'Mariposa Luminosa', description: 'Alas brillantes para un saludo ligero', image_url: 'conectate://gift-assets/luminous-butterfly', price_diamonds: 25, price_credits: 0, animation_url: 'conectate://gift-animations/luminous-butterfly', sort_order: 4 },
  { id: '7a100005-0000-4000-8000-000000000005', name: 'Flor Cometa', description: 'Pétalos que dejan una estela en el cielo', image_url: 'conectate://gift-assets/comet-flower', price_diamonds: 40, price_credits: 0, animation_url: 'conectate://gift-animations/comet-flower', sort_order: 5 },
  { id: '7a100006-0000-4000-8000-000000000006', name: 'Luna de Cristal', description: 'Una luna tallada en reflejos boreales', image_url: 'conectate://gift-assets/crystal-moon', price_diamonds: 60, price_credits: 0, animation_url: 'conectate://gift-animations/crystal-moon', sort_order: 6 },
  { id: '7a100007-0000-4000-8000-000000000007', name: 'Colibrí Solar', description: 'Un vuelo veloz cargado de energía', image_url: 'conectate://gift-assets/solar-hummingbird', price_diamonds: 90, price_credits: 0, animation_url: 'conectate://gift-animations/solar-hummingbird', sort_order: 7 },
  { id: '7a100008-0000-4000-8000-000000000008', name: 'Medusa Nebulosa', description: 'Una criatura celeste que flota entre estrellas', image_url: 'conectate://gift-assets/nebula-jellyfish', price_diamonds: 130, price_credits: 0, animation_url: 'conectate://gift-animations/nebula-jellyfish', sort_order: 8 },
  { id: '7a100009-0000-4000-8000-000000000009', name: 'Zorro Boreal', description: 'Un compañero ártico envuelto en auroras', image_url: 'conectate://gift-assets/boreal-fox', price_diamonds: 170, price_credits: 0, animation_url: 'conectate://gift-animations/boreal-fox', sort_order: 9 },
  { id: '7a100010-0000-4000-8000-000000000010', name: 'Ballena Estelar', description: 'Una travesía serena por el océano cósmico', image_url: 'conectate://gift-assets/cosmic-whale', price_diamonds: 200, price_credits: 0, animation_url: 'conectate://gift-animations/cosmic-whale', sort_order: 10 },
  { id: '7a100011-0000-4000-8000-000000000011', name: 'Jardín de Estrellas', description: 'Un jardín que florece con luz estelar', image_url: 'conectate://gift-assets/star-garden', price_diamonds: 250, price_credits: 0, animation_url: 'conectate://gift-animations/star-garden', sort_order: 11 },
  { id: '7a100012-0000-4000-8000-000000000012', name: 'Orquídea Prisma', description: 'Cristales florales que reflejan todo el directo', image_url: 'conectate://gift-assets/crystal-orchid', price_diamonds: 300, price_credits: 0, animation_url: 'conectate://gift-animations/crystal-orchid', sort_order: 12 },
  { id: '7a100013-0000-4000-8000-000000000013', name: 'Nave Prisma', description: 'Una nave de colores lista para despegar', image_url: 'conectate://gift-assets/prism-ship', price_diamonds: 350, price_credits: 0, animation_url: 'conectate://gift-animations/prism-ship', sort_order: 13 },
  { id: '7a100014-0000-4000-8000-000000000014', name: 'Guardián del Bosque', description: 'El protector luminoso de un bosque encantado', image_url: 'conectate://gift-assets/forest-guardian', price_diamonds: 450, price_credits: 0, animation_url: 'conectate://gift-animations/forest-guardian', sort_order: 14 },
  { id: '7a100015-0000-4000-8000-000000000015', name: 'Fénix Aurora', description: 'Renace entre fuego frío y luces boreales', image_url: 'conectate://gift-assets/aurora-phoenix', price_diamonds: 600, price_credits: 0, animation_url: 'conectate://gift-animations/aurora-phoenix', sort_order: 15 },
  { id: '7a100016-0000-4000-8000-000000000016', name: 'Tren Estelar', description: 'Un viaje brillante que cruza toda la escena', image_url: 'conectate://gift-assets/star-train', price_diamonds: 750, price_credits: 0, animation_url: 'conectate://gift-animations/star-train', sort_order: 16 },
  { id: '7a100017-0000-4000-8000-000000000017', name: 'Dragón Celeste', description: 'Un guardián majestuoso para grandes celebraciones', image_url: 'conectate://gift-assets/celestial-dragon', price_diamonds: 900, price_credits: 0, animation_url: 'conectate://gift-animations/celestial-dragon', sort_order: 17 },
  { id: '7a100018-0000-4000-8000-000000000018', name: 'Isla Flotante', description: 'Un mundo suspendido que aparece sobre el directo', image_url: 'conectate://gift-assets/floating-island', price_diamonds: 1100, price_credits: 0, animation_url: 'conectate://gift-animations/floating-island', sort_order: 18 },
  { id: '7a100019-0000-4000-8000-000000000019', name: 'Mundo Celeste', description: 'Un planeta completo nacido entre constelaciones', image_url: 'conectate://gift-assets/celestial-world', price_diamonds: 1400, price_credits: 0, animation_url: 'conectate://gift-animations/celestial-world', sort_order: 19 },
  { id: '7a100020-0000-4000-8000-000000000020', name: 'Ópera de Luz', description: 'Una escena musical de luz, color y movimiento', image_url: 'conectate://gift-assets/light-opera', price_diamonds: 1800, price_credits: 0, animation_url: 'conectate://gift-animations/light-opera', sort_order: 20 },
  { id: '7a100021-0000-4000-8000-000000000021', name: 'Palacio Boreal', description: 'La celebración imperial del cielo nocturno', image_url: 'conectate://gift-assets/aurora-palace', price_diamonds: 2200, price_credits: 0, animation_url: 'conectate://gift-animations/aurora-palace', sort_order: 21 },
  { id: '7a100022-0000-4000-8000-000000000022', name: 'Reino del Tiempo', description: 'Un reino fantástico que controla cada instante', image_url: 'conectate://gift-assets/time-kingdom', price_diamonds: 2800, price_credits: 0, animation_url: 'conectate://gift-animations/time-kingdom', sort_order: 22 },
  { id: '7a100023-0000-4000-8000-000000000023', name: 'Ascenso Galáctico', description: 'Una subida épica hacia el centro de la galaxia', image_url: 'conectate://gift-assets/galactic-ascent', price_diamonds: 3600, price_credits: 0, animation_url: 'conectate://gift-animations/galactic-ascent', sort_order: 23 },
  { id: '7a100024-0000-4000-8000-000000000024', name: 'Génesis Aurora', description: 'El gran final Novvin: un universo nace en pantalla', image_url: 'conectate://gift-assets/aurora-genesis', price_diamonds: 5000, price_credits: 0, animation_url: 'conectate://gift-animations/aurora-genesis', sort_order: 24 },
]
const DEFAULT_VIRTUAL_GIFT_IDS = new Set(DEFAULT_VIRTUAL_GIFTS.map((gift) => gift.id))

const SUPPORTED_RPC_NAMES = new Set([
  'challenge_v3_vote_toggle',
  'delete_own_account',
  'get_challenge_submissions_voted',
  'get_or_generate_active_challenge',
  'increment_like_count',
  'increment_post_count',
  'increment_share_count',
  'increment_video_views',
  'is_current_user_admin',
  'register_media_object',
  'resolve_login_email',
  'secure_add_sticker_to_pack',
  'secure_admin_delete_reported_video',
  'secure_admin_set_user_suspension',
  'secure_admin_update_video_report_status',
  'secure_admin_warn_video_owner_from_report',
  'secure_cancel_scheduled_message_job',
  'secure_consume_view_once_message',
  'secure_create_chat_poll_message',
  'secure_create_community_poll_message',
  'secure_create_group_poll_message',
  'secure_create_sticker_pack',
  'secure_delete_post_comment',
  'secure_delete_video_comment',
  'secure_get_chat_scheduled_jobs',
  'secure_get_community_message_receipts',
  'secure_get_group_message_receipts',
  'secure_get_my_suspension_state',
  'secure_get_poll_state',
  'secure_mark_community_messages_delivered',
  'secure_mark_community_messages_read',
  'secure_mark_group_messages_delivered',
  'secure_mark_group_messages_read',
  'secure_mark_messages_delivered',
  'secure_mark_messages_read',
  'secure_mark_notifications_read',
  'secure_schedule_message_job',
  'secure_search_stickers',
  'secure_send_community_message',
  'secure_send_group_message',
  'secure_send_message',
  'secure_set_chat_preferences',
  'secure_set_chat_translation_settings',
  'secure_submit_report',
  'secure_submit_video_report',
  'secure_suspend_video_owner_from_report',
  'secure_toggle_community_message_reaction',
  'secure_toggle_group_message_reaction',
  'secure_toggle_message_reaction',
  'secure_toggle_sticker_favorite',
  'secure_vote_poll_option',
  'send_virtual_gift',
  'get_virtual_gift_receipt',
  'billing_capabilities',
  'verify_google_play_purchase',
  'subscribe_to_plan',
  'toggle_challenge_vote',
  'update_user_online_status',
  'v2026_challenge_vote',
  'wallet_get_or_create',
])

const SUPPORTED_TABLES = new Set([
  'active_challenges',
  'ad_campaigns',
  'ad_impressions',
  'app_telemetry_events',
  'badges',
  'blocked_users',
  'challenge_submissions',
  'challenge_votes',
  'challenges_master',
  'chat_folder_chats',
  'chat_folders',
  'chat_participants',
  'chat_summary_cache',
  'chat_translation_settings',
  'chats',
  'communities',
  'community_members',
  'community_message_receipts',
  'community_messages',
  'creator_earnings',
  'gift_transactions',
  'billing_purchases',
  'creator_payout_accounts',
  'creator_program_applications',
  'fcm_tokens',
  'follows',
  'friends',
  'group_members',
  'group_message_receipts',
  'group_messages',
  'groups',
  'live_comments',
  'live_streams',
  'message_poll_options',
  'message_poll_votes',
  'message_polls',
  'messages',
  'moderation_events',
  'moment_highlight_items',
  'moment_highlights',
  'moment_views',
  'moments',
  'nicknames',
  'notifications',
  'post_comments',
  'post_likes',
  'post_reactions',
  'posts',
  'profile_login_emails',
  'profile_views',
  'profiles',
  'reports',
  'scheduled_message_jobs',
  'shares',
  'sticker_pack_items',
  'sticker_packs',
  'subscription_plans',
  'user_badges',
  'user_sticker_favorites',
  'user_subscriptions',
  'users',
  'video_comments',
  'video_likes',
  'video_reports',
  'videos',
  'virtual_gifts',
  'voice_access_requests',
  'voice_room_participants',
  'voice_rooms',
  'wallets',
  'wallet_transactions',
])

const ADMIN_WRITE_TABLES = new Set([
  'active_challenges',
  'ad_campaigns',
  'badges',
  'challenges_master',
  'moderation_events',
  'sticker_pack_items',
  'sticker_packs',
  'subscription_plans',
  'user_badges',
  'virtual_gifts',
])

// Ledger, balances, poll votes and provider purchase state are never writable through the
// generic PostgREST compatibility surface. They are mutated only by the
// authenticated, transactional RPCs below.
const SERVER_MUTATION_ONLY_TABLES = new Set([
  'billing_purchases',
  'creator_earnings',
  'gift_transactions',
  'message_poll_votes',
  'user_subscriptions',
  'wallet_transactions',
  'wallets',
])

const PRIVATE_OWNER_FIELDS = new Map([
  ['ad_impressions', ['user_id']],
  ['app_telemetry_events', ['user_id']],
  ['blocked_users', ['user_id', 'blocker_id']],
  ['chat_folder_chats', ['user_id']],
  ['chat_folders', ['user_id']],
  ['chat_summary_cache', ['user_id']],
  ['chat_translation_settings', ['user_id']],
  ['creator_earnings', ['user_id', 'creator_id']],
  ['gift_transactions', ['sender_id', 'receiver_id']],
  ['billing_purchases', ['user_id']],
  ['creator_payout_accounts', ['user_id', 'creator_id']],
  ['creator_program_applications', ['user_id', 'creator_id']],
  ['fcm_tokens', ['user_id']],
  ['moment_highlight_items', ['user_id', 'owner_id']],
  ['moment_highlights', ['user_id', 'owner_id']],
  ['nicknames', ['user_id']],
  ['profile_login_emails', ['user_id', 'profile_id']],
  ['profile_views', ['profile_id']],
  ['scheduled_message_jobs', ['user_id', 'sender_id']],
  ['user_sticker_favorites', ['user_id']],
  ['user_subscriptions', ['user_id']],
  ['wallets', ['user_id']],
  ['wallet_transactions', ['user_id']],
  ['voice_access_requests', ['user_id', 'requester_id']],
])

const AUTHORED_TABLE_FIELDS = new Map([
  ['challenge_submissions', 'user_id'],
  ['challenge_votes', 'user_id'],
  ['follows', 'follower_id'],
  ['friends', 'sender_id'],
  ['live_comments', 'user_id'],
  ['live_streams', 'host_user_id'],
  ['moment_views', 'user_id'],
  ['moments', 'user_id'],
  ['notifications', 'actor_id'],
  ['profile_views', 'viewer_id'],
  ['post_comments', 'user_id'],
  ['post_likes', 'user_id'],
  ['post_reactions', 'user_id'],
  ['posts', 'user_id'],
  ['reports', 'reporter_id'],
  ['shares', 'user_id'],
  ['video_comments', 'user_id'],
  ['video_likes', 'user_id'],
  ['video_reports', 'reporter_id'],
  ['videos', 'user_id'],
  ['voice_room_participants', 'user_id'],
])

const COMPOSITE_ROW_ID_FIELDS = new Map([
  ['ad_impressions', [['campaign_id', 'user_id'], ['ad_id', 'user_id']]],
  ['blocked_users', [['user_id', 'blocked_user_id'], ['blocker_id', 'blocked_id']]],
  ['challenge_votes', [['submission_id', 'user_id'], ['challenge_submission_id', 'user_id']]],
  ['chat_folder_chats', [['folder_id', 'chat_id']]],
  ['chat_translation_settings', [['chat_id', 'user_id']]],
  ['chat_participants', [['chat_id', 'user_id']]],
  ['community_members', [['community_id', 'user_id'], ['room_id', 'user_id']]],
  ['community_message_receipts', [['message_id', 'user_id']]],
  ['fcm_tokens', [['user_id', 'token']]],
  ['follows', [['follower_id', 'following_id']]],
  ['friends', [['sender_id', 'receiver_id']]],
  ['group_members', [['group_id', 'user_id'], ['room_id', 'user_id']]],
  ['group_message_receipts', [['message_id', 'user_id']]],
  ['message_poll_options', [['poll_id', 'display_order'], ['poll_id', 'option_text']]],
  ['message_poll_votes', [['poll_id', 'option_id', 'user_id']]],
  ['moment_highlight_items', [['highlight_id', 'moment_id']]],
  ['moment_views', [['moment_id', 'user_id']]],
  ['nicknames', [['user_id', 'target_user_id']]],
  ['post_likes', [['post_id', 'user_id']]],
  ['post_reactions', [['post_id', 'user_id']]],
  ['profile_views', [['profile_id', 'viewer_id']]],
  ['sticker_pack_items', [['pack_id', 'sticker_id']]],
  ['user_sticker_favorites', [['user_id', 'sticker_id'], ['user_id', 'item_id']]],
  ['video_likes', [['video_id', 'user_id']]],
  ['voice_room_participants', [['room_id', 'user_id']]],
])

function safeIdentifier(value) {
  const name = String(value || '').trim()
  return /^[a-zA-Z_][a-zA-Z0-9_]{0,62}$/.test(name) ? name : ''
}

function quoteIdentifier(value) {
  const safe = safeIdentifier(value)
  if (!safe) throw new Error('INVALID_IDENTIFIER')
  return `"${safe.replace(/"/g, '""')}"`
}

function normalizeTableName(value) {
  const tableName = safeIdentifier(String(value || '').trim().toLowerCase())
  return SUPPORTED_TABLES.has(tableName) ? tableName : ''
}

function normalizeId(value) {
  const id = String(value || '').trim()
  return id || crypto.randomUUID()
}

function nowIso() {
  return new Date().toISOString()
}

function asArrayBody(body) {
  if (Array.isArray(body)) return body
  if (body && typeof body === 'object') return [body]
  return [{}]
}

function rowId(row, tableName = '') {
  const explicitId = row?.id ?? row?.uuid
  if (explicitId != null && String(explicitId).trim()) return String(explicitId)

  for (const fields of COMPOSITE_ROW_ID_FIELDS.get(tableName) || []) {
    const values = fields.map((field) => normalizeIdentityValue(row?.[field]))
    if (values.every(Boolean)) {
      return fields.map((field, index) => `${field}:${values[index]}`).join('|')
    }
  }

  return String(
    row?.user_id ??
      row?.profile_id ??
      row?.chat_id ??
      row?.room_id ??
      row?.slug ??
      '',
  )
}

function compactRow(row) {
  if (!row || typeof row !== 'object') return {}
  return Object.fromEntries(Object.entries(row).filter(([, value]) => value !== undefined))
}

function httpError(code, status = 403) {
  const error = new Error(code)
  error.status = status
  if (/^[A-Z][A-Z0-9_]{1,79}$/.test(String(code || ''))) {
    error.code = code
    error.expose = true
  }
  return error
}

function normalizeIdentityValue(value) {
  return String(value ?? '').trim().toLowerCase()
}

async function getAuthIdentity(pool, auth) {
  const localUserId = normalizeIdentityValue(auth?.sub)
  if (!localUserId) return null
  const { rows } = await pool.query(
    `SELECT id::text AS local_user_id,
            NULLIF(supabase_user_id::text, '') AS public_user_id,
            email,
            display_name,
            avatar_url
     FROM fallback_users
     WHERE lower(id::text) = $1
     LIMIT 1`,
    [localUserId],
  )
  if (!rows[0]) return null
  const publicUserId = normalizeIdentityValue(rows[0]?.public_user_id)
  const ids = new Set([localUserId, publicUserId].filter(Boolean))
  return {
    ids,
    avatarUrl: rows[0]?.avatar_url || null,
    displayName: rows[0]?.display_name || rows[0]?.email || 'Usuario',
    email: rows[0]?.email || '',
    localUserId,
    preferredUserId: publicUserId || localUserId,
    values: Array.from(ids),
  }
}

function identityOwnsValue(identity, value) {
  const normalized = normalizeIdentityValue(value)
  return Boolean(normalized && identity?.ids?.has(normalized))
}

function identityOwnsRow(identity, row, fields) {
  return fields.some((field) => identityOwnsValue(identity, row?.[field]))
}

function forceActorField(row, field, identity) {
  const supplied = row?.[field]
  if (supplied != null && String(supplied).trim() && !identityOwnsValue(identity, supplied)) {
    throw httpError('ACTOR_ID_MISMATCH')
  }
  return { ...row, [field]: identity.preferredUserId }
}

function mapUserRow(row) {
  return compactRow({
    id: row.supabase_user_id || row.id,
    local_id: row.id,
    email: row.email,
    display_name: row.display_name || row.email,
    username: row.display_name || row.email,
    full_name: row.display_name || row.email,
    avatar_url: row.avatar_url,
    created_at: row.created_at,
    updated_at: row.password_changed_by_user_at || row.local_password_set_at || row.created_at,
  })
}

async function ensurePostgrestCompatTables(pool) {
  await pool.query(`
    CREATE TABLE IF NOT EXISTS fallback_compat_rows (
      table_name TEXT NOT NULL,
      row_id TEXT NOT NULL,
      row JSONB NOT NULL DEFAULT '{}'::jsonb,
      created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      PRIMARY KEY(table_name, row_id)
    )
  `)
  await pool.query(`
    CREATE INDEX IF NOT EXISTS idx_fallback_compat_rows_updated
    ON fallback_compat_rows(table_name, updated_at DESC)
  `)
  // Provider purchase tokens are one-time credentials.  The JSON fallback
  // store still needs a database-level uniqueness guard in addition to the
  // advisory transaction lock used by the credit path.
  await pool.query(`
    CREATE UNIQUE INDEX IF NOT EXISTS idx_fallback_billing_purchase_token_hash
    ON fallback_compat_rows ((row->>'purchase_token_hash'))
    WHERE table_name = 'billing_purchases'
      AND row ? 'purchase_token_hash'
  `)
  await pool.query(`
    CREATE UNIQUE INDEX IF NOT EXISTS idx_fallback_billing_voided_token_hash
    ON fallback_compat_rows ((row->>'purchase_token_hash'))
    WHERE table_name = 'billing_voided_purchase_events'
      AND row ? 'purchase_token_hash'
  `)
  // A gift request UUID is the receipt ID.  These database guards make the
  // gift, creator earning and wallet debit one-to-one even if a client retries
  // or two API workers process the request concurrently.
  await pool.query(`
    CREATE UNIQUE INDEX IF NOT EXISTS idx_fallback_gift_idempotency_key
    ON fallback_compat_rows ((lower(row->>'idempotency_key')))
    WHERE table_name = 'gift_transactions'
      AND row ? 'idempotency_key'
  `)
  await pool.query(`
    CREATE UNIQUE INDEX IF NOT EXISTS idx_fallback_gift_earning_id
    ON fallback_compat_rows ((lower(row->>'earning_id')))
    WHERE table_name = 'gift_transactions'
      AND row ? 'earning_id'
  `)
  await pool.query(`
    CREATE UNIQUE INDEX IF NOT EXISTS idx_fallback_creator_earning_gift_transaction
    ON fallback_compat_rows ((lower(row->>'gift_transaction_id')))
    WHERE table_name = 'creator_earnings'
      AND row ? 'gift_transaction_id'
  `)
  await pool.query(`
    CREATE UNIQUE INDEX IF NOT EXISTS idx_fallback_wallet_virtual_gift_reference
    ON fallback_compat_rows ((lower(row->>'reference_id')))
    WHERE table_name = 'wallet_transactions'
      AND row->>'transaction_type' = 'virtual_gift'
      AND row ? 'reference_id'
  `)
  await pool.query(`
    CREATE UNIQUE INDEX IF NOT EXISTS idx_fallback_wallet_coin_lot_source
    ON fallback_compat_rows (
      (lower(row->>'user_id')),
      (upper(row->>'source_type')),
      (lower(row->>'source_reference'))
    )
    WHERE table_name = 'wallet_coin_lots'
      AND row ? 'user_id'
      AND row ? 'source_type'
      AND row ? 'source_reference'
  `)
  await pool.query(`
    CREATE UNIQUE INDEX IF NOT EXISTS idx_fallback_gift_coin_allocation
    ON fallback_compat_rows (
      (lower(row->>'gift_transaction_id')),
      (lower(row->>'lot_id'))
    )
    WHERE table_name = 'gift_coin_allocations'
      AND row ? 'gift_transaction_id'
      AND row ? 'lot_id'
  `)
  await pool.query(`
    CREATE UNIQUE INDEX IF NOT EXISTS idx_fallback_economic_reversal_reference
    ON fallback_compat_rows ((lower(row->>'reference_id')))
    WHERE table_name = 'economic_reversals'
      AND row ? 'reference_id'
  `)
  await pool.query(`
    CREATE TABLE IF NOT EXISTS fallback_view_once_consumptions (
      scope_type TEXT NOT NULL CHECK (scope_type IN ('chat', 'group', 'community')),
      message_id UUID NOT NULL,
      user_id TEXT NOT NULL,
      device_id TEXT,
      consumed_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      PRIMARY KEY (scope_type, message_id, user_id)
    )
  `)
  await pool.query(`
    CREATE TABLE IF NOT EXISTS fallback_deleted_identities (
      user_id TEXT PRIMARY KEY,
      deleted_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
    )
  `)
  await pool.query(`
    CREATE INDEX IF NOT EXISTS idx_fallback_view_once_user
    ON fallback_view_once_consumptions(lower(user_id), consumed_at DESC)
  `)
  for (const gift of DEFAULT_VIRTUAL_GIFTS) {
    await storeCompatRow(pool, 'virtual_gifts', { ...gift, is_active: true })
  }
  await pool.query(`
    UPDATE fallback_compat_rows
    SET row = jsonb_set(row, '{is_active}', 'false'::jsonb, true),
        updated_at = NOW()
    WHERE table_name = 'virtual_gifts'
      AND (
        row_id LIKE 'builtin-%'
        OR (
          COALESCE(row->>'image_url', '') LIKE 'conectate://gift-assets/%'
          AND NOT (row_id = ANY($1::text[]))
        )
      )
  `, [Array.from(DEFAULT_VIRTUAL_GIFT_IDS)])
}

async function tableExists(pool, schemaName, tableName) {
  const schema = safeIdentifier(schemaName)
  const table = safeIdentifier(tableName)
  if (!schema || !table) return false
  const { rows } = await pool.query('SELECT to_regclass($1) AS oid', [`${schema}.${table}`])
  return Boolean(rows[0]?.oid)
}

async function readImportedRows(pool, tableName) {
  if (!(await tableExists(pool, 'supabase_import', tableName))) return []
  const sql = `SELECT to_jsonb(t) AS row FROM ${quoteIdentifier('supabase_import')}.${quoteIdentifier(tableName)} t LIMIT $1`
  const { rows } = await pool.query(sql, [MAX_READ_ROWS])
  return rows.map((entry) => entry.row || {})
}

async function readCompatRows(pool, tableName) {
  const { rows } = await pool.query(
    `SELECT row
     FROM fallback_compat_rows
     WHERE table_name = $1
     ORDER BY updated_at DESC
     LIMIT $2`,
    [tableName, MAX_READ_ROWS],
  )
  return rows.map((entry) => entry.row || {})
}

async function readSpecificRows(pool, tableName, auth) {
  switch (tableName) {
    case 'profiles':
    case 'users': {
      const { rows } = await pool.query(`
        SELECT u.id, u.email, u.display_name, u.avatar_url, u.supabase_user_id, u.created_at,
               u.password_changed_by_user_at, u.local_password_set_at, o.profile AS profile_override
        FROM fallback_users u
        LEFT JOIN fallback_profile_overrides o ON o.user_id=u.id
        ORDER BY created_at DESC
        LIMIT $1
      `, [MAX_READ_ROWS])
      return rows.map(row=>({...mapUserRow(row),
        ...(row.profile_override&&typeof row.profile_override==='object'?row.profile_override:{}),
        id:row.supabase_user_id||row.id,local_id:row.id,__profile_override:row.profile_override}))
    }
    case 'live_streams': {
      const { rows } = await pool.query(`
        SELECT id::text, host_user_id, host_name, host_avatar_url, title, source,
               hashtags, viewer_count, peak_viewer_count, total_comments,
               total_reactions, is_active, started_at, ended_at, created_at,
               updated_at
        FROM fallback_live_streams
        ORDER BY created_at DESC
        LIMIT $1
      `, [MAX_READ_ROWS])
      return rows
    }
    case 'posts': {
      const { rows } = await pool.query(`
        SELECT id::text, user_id, user_name, user_profile_image, content, media_url,
               media_urls, media_type, visibility, survey_id,
               (SELECT count(*)::int FROM survey_responses r WHERE r.campaign_id=fallback_posts.survey_id AND r.removed_at IS NULL AND (r.expires_at IS NULL OR r.expires_at>now())) AS survey_response_count,
               created_at, updated_at
        FROM fallback_posts
        ORDER BY created_at DESC
        LIMIT $1
      `, [MAX_READ_ROWS])
      return rows
    }
    case 'post_comments': {
      const { rows } = await pool.query(`
        SELECT id::text, post_id, user_id, content, parent_id, media_url, media_type,
               is_deleted, created_at, updated_at
        FROM fallback_post_comments
        ORDER BY created_at DESC
        LIMIT $1
      `, [MAX_READ_ROWS])
      return rows
    }
    case 'post_reactions':
    case 'post_likes': {
      const { rows } = await pool.query(`
        SELECT (post_id::text || ':' || user_id::text) AS id,
               post_id::text, user_id::text, reaction_type, created_at
        FROM fallback_post_reactions
        ORDER BY created_at DESC
        LIMIT $1
      `, [MAX_READ_ROWS])
      return rows
    }
    case 'shares': {
      const { rows } = await pool.query(`
        SELECT id::text, user_id, post_id, post_id AS content_id, content_type, created_at
        FROM fallback_shares
        ORDER BY created_at DESC
        LIMIT $1
      `, [MAX_READ_ROWS])
      return rows
    }
    case 'follows': {
      const { rows } = await pool.query(`
        SELECT (follower_id::text || ':' || following_id::text) AS id,
               follower_id::text, following_id::text, created_at
        FROM fallback_follows
        ORDER BY created_at DESC
        LIMIT $1
      `, [MAX_READ_ROWS])
      return rows
    }
    case 'reports': {
      const { rows } = await pool.query(`
        SELECT id::text, reporter_id, reporter_name, reported_user_id, reported_user_name,
               reason, description, description AS details, evidence_url, content_type,
               content_id, status, created_at, updated_at
        FROM fallback_reports
        ORDER BY created_at DESC
        LIMIT $1
      `, [MAX_READ_ROWS])
      return rows
    }
    case 'notifications': {
      const { rows } = await pool.query(`
        SELECT id::text, recipient_id AS user_id, actor_id, actor_name, actor_avatar_url,
               type, target_id, target_type, content AS title, content AS body,
               extra_data AS data, is_read,
               CASE WHEN is_read THEN updated_at ELSE NULL END AS read_at,
               created_at, updated_at
        FROM fallback_notifications
        ORDER BY created_at DESC
        LIMIT $1
      `, [MAX_READ_ROWS])
      return rows
    }
    case 'messages': {
      const displayUserId = auth?.supabase_user_id || auth?.sub || ''
      const { rows } = await pool.query(`
        SELECT m.id::text, m.chat_id, m.sender_id, m.content, m.content_type AS message_type,
               m.content_type, m.media_url, m.media_size, m.status, m.reply_to_id, m.poll_id,
               m.delivered_at, m.read_at, m.reactions, m.deleted_for_all, m.deleted_by,
               '{}'::jsonb AS metadata, m.created_at, m.updated_at
        FROM fallback_messages m
        LEFT JOIN fallback_chat_participants cp 
          ON lower(cp.chat_id::text) = lower(m.chat_id) AND lower(cp.user_id) = lower($2)
        WHERE (cp.cleared_at IS NULL OR m.created_at > cp.cleared_at)
        ORDER BY m.created_at DESC
        LIMIT $1
      `, [MAX_READ_ROWS, displayUserId])
      return rows
    }
    case 'moments': {
      const { rows } = await pool.query(`
        SELECT id::text, user_id, media_url, media_type, thumbnail_url, caption, visibility,
               visibility_type, allow_replies, duration_hours, duration_type, excluded_ids,
               audience_ids, display_duration_ms,
               overlay_text, overlay_color, overlay_font, overlay_scale, overlay_x, overlay_y,
               music_url, music_audio_url, music_id, music_title, music_artist, music_presentation, music_artwork_url,
               music_overlay_x, music_overlay_y, music_overlay_scale, music_overlay_rotation, music_lyrics, music_source,
               music_source_url, music_license, music_license_url, music_start_ms,
               music_duration_ms, music_end_ms, music_volume, original_audio_volume,
               expires_at, created_at, updated_at
        FROM fallback_moments
        ORDER BY created_at DESC
        LIMIT $1
      `, [MAX_READ_ROWS])
      return rows
    }
    case 'moment_views': {
      const { rows } = await pool.query(`
        SELECT moment_id::text, user_id, viewed_at
        FROM fallback_moment_views
        ORDER BY viewed_at DESC
        LIMIT $1
      `, [MAX_READ_ROWS])
      return rows
    }
    case 'video_reports': {
      const { rows } = await pool.query(`
        SELECT id::text, video_id, video_owner_id, video_owner_name, video_title,
               reporter_id, reporter_name, reason, details, status, warn_sent, strike_applied,
               action_note, reviewed_at, created_at, updated_at
        FROM fallback_video_reports
        ORDER BY created_at DESC
        LIMIT $1
      `, [MAX_READ_ROWS])
      return rows
    }
    case 'moderation_events': {
      const { rows } = await pool.query(`
        SELECT id::text, actor_id, context, content_ref, verdict, metadata_preview, created_at
        FROM fallback_moderation_events
        ORDER BY created_at DESC
        LIMIT $1
      `, [MAX_READ_ROWS])
      return rows
    }
    case 'group_messages':
    case 'community_messages': {
      const collectionType = tableName === 'group_messages' ? 'group' : 'community'
      const idColumn = collectionType === 'group' ? 'group_id' : 'community_id'
      const { rows } = await pool.query(`
        SELECT id::text, room_id AS ${idColumn}, sender_id, content,
               content_type AS message_type, content_type, media_url, media_size,
               status, delivered_at, read_at, reactions, reply_to_id, poll_id, is_forwarded,
               forwarded_from_message_id, forwarded_from_user_id,
               '{}'::jsonb AS metadata, created_at, updated_at
        FROM fallback_room_messages
        WHERE room_kind = $1
        ORDER BY created_at DESC
        LIMIT $2
      `, [collectionType, MAX_READ_ROWS])
      return rows
    }
    case 'groups':
    case 'communities': {
      const collectionType = tableName === 'groups' ? 'group' : 'community'
      if (collectionType === 'group') {
        const userId = auth?.sub || ''
        const { rows } = await pool.query(`
          SELECT r.id::text, r.creator_id AS owner_id, r.creator_id, r.name, r.description,
                 r.image AS avatar_url, r.image, r.category, r.is_public, r.only_admins_can_talk,
                 '{}'::jsonb AS metadata, r.created_at, r.updated_at
          FROM fallback_collection_rooms r
          INNER JOIN fallback_collection_members m ON m.room_id = r.id::text AND m.room_kind = 'group'
          WHERE r.room_kind = $1 AND r.is_deleted = FALSE AND m.user_id = $3
          ORDER BY r.updated_at DESC
          LIMIT $2
        `, [collectionType, MAX_READ_ROWS, userId])
        return rows
      } else {
        const { rows } = await pool.query(`
          SELECT id::text, creator_id AS owner_id, creator_id, name, description,
                 image AS avatar_url, image, category, is_public, only_admins_can_talk,
                 '{}'::jsonb AS metadata, created_at, updated_at
          FROM fallback_collection_rooms
          WHERE room_kind = $1 AND is_deleted = FALSE
          ORDER BY updated_at DESC
          LIMIT $2
        `, [collectionType, MAX_READ_ROWS])
        return rows
      }
    }
    case 'group_members':
    case 'community_members': {
      const collectionType = tableName === 'group_members' ? 'group' : 'community'
      const idColumn = collectionType === 'group' ? 'group_id' : 'community_id'
      const { rows } = await pool.query(`
        SELECT (room_id || ':' || user_id) AS id,
               room_id AS ${idColumn}, user_id, role, joined_at AS created_at
        FROM fallback_collection_members
        WHERE room_kind = $1
        ORDER BY joined_at DESC
        LIMIT $2
      `, [collectionType, MAX_READ_ROWS])
      return rows
    }
    case 'voice_rooms': {
      const { rows } = await pool.query(`
        SELECT id::text, creator_id AS owner_id, creator_id, name AS title, name,
               description AS topic, description, max_participants, is_public,
               is_active, agora_channel_name, readable_id,
               '{}'::jsonb AS metadata, created_at, updated_at
        FROM fallback_voice_rooms
        ORDER BY updated_at DESC
        LIMIT $1
      `, [MAX_READ_ROWS])
      return rows
    }
    case 'voice_room_participants': {
      const { rows } = await pool.query(`
        SELECT (room_id || ':' || user_id) AS id,
               room_id, user_id, user_name, user_avatar_url, socket_id,
               is_muted, is_speaking, joined_at, updated_at,
               NULL::timestamptz AS left_at
        FROM fallback_voice_room_participants
        ORDER BY joined_at DESC
        LIMIT $1
      `, [MAX_READ_ROWS])
      return rows
    }
    case 'videos': {
      const { rows } = await pool.query(`
        SELECT id::text, user_id, user_name, user_profile_image, title, description,
               video_url, cover_url, thumbnail_url, duration_seconds AS duration,
               duration_seconds, trim_start_seconds, trim_end_seconds,
               sound_id, sound_source_video_id, sound_title, sound_artist, sound_url,
               views, like_count, comment_count, share_count, is_public, tags,
               created_at, updated_at
        FROM fallback_videos
        ORDER BY created_at DESC
        LIMIT $1
      `, [MAX_READ_ROWS])
      return rows
    }
    case 'video_likes': {
      const { rows } = await pool.query(`
        SELECT (video_id || ':' || user_id) AS id, video_id, user_id, created_at
        FROM fallback_video_likes
        ORDER BY created_at DESC
        LIMIT $1
      `, [MAX_READ_ROWS])
      return rows
    }
    case 'friends': {
      const { rows } = await pool.query(`
        SELECT id::text, sender_id, receiver_id, status, created_at
        FROM fallback_friends
        ORDER BY created_at DESC
        LIMIT $1
      `, [MAX_READ_ROWS])
      return rows
    }
    default:
      return []
  }
}

async function accessibleChatIds(pool, identity) {
  const ids = new Set()
  if (!identity?.values?.length) return ids
  const { rows } = await pool.query(
    `SELECT id::text AS chat_id
     FROM fallback_chats
     WHERE EXISTS (
       SELECT 1 FROM unnest(participants) AS participant
       WHERE lower(participant) = ANY($1::text[])
     )
     UNION
     SELECT chat_id::text AS chat_id
     FROM fallback_chat_participants
     WHERE lower(user_id) = ANY($1::text[])`,
    [identity.values],
  )
  rows.forEach((row) => ids.add(normalizeIdentityValue(row.chat_id)))

  const [compatChats, compatParticipants] = await Promise.all([
    readCompatRows(pool, 'chats'),
    readCompatRows(pool, 'chat_participants'),
  ])
  for (const chat of compatChats) {
    const participants = Array.isArray(chat.participants) ? chat.participants : []
    if (
      participants.some((value) => identityOwnsValue(identity, value)) ||
      identityOwnsRow(identity, chat, ['creator_id', 'owner_id', 'user_id'])
    ) {
      ids.add(normalizeIdentityValue(chat.id || chat.chat_id))
    }
  }
  for (const participant of compatParticipants) {
    if (identityOwnsRow(identity, participant, ['user_id', 'profile_id'])) {
      ids.add(normalizeIdentityValue(participant.chat_id))
    }
  }
  ids.delete('')
  return ids
}

async function accessibleCollectionIds(pool, kind, identity) {
  const ids = new Set()
  if (!identity?.values?.length) return ids
  const { rows } = await pool.query(
    `SELECT room_id AS id
     FROM fallback_collection_members
     WHERE room_kind = $1 AND lower(user_id) = ANY($2::text[])
     UNION
     SELECT id::text AS id
     FROM fallback_collection_rooms
     WHERE room_kind = $1 AND lower(creator_id) = ANY($2::text[])`,
    [kind, identity.values],
  )
  rows.forEach((row) => ids.add(normalizeIdentityValue(row.id)))

  const roomTable = kind === 'group' ? 'groups' : 'communities'
  const memberTable = kind === 'group' ? 'group_members' : 'community_members'
  const [compatRooms, compatMembers] = await Promise.all([
    importedAndCompatRows(pool, roomTable),
    importedAndCompatRows(pool, memberTable),
  ])
  for (const room of compatRooms) {
    if (identityOwnsRow(identity, room, ['creator_id', 'owner_id', 'user_id'])) {
      ids.add(normalizeIdentityValue(room.id))
    }
  }
  for (const member of compatMembers) {
    if (identityOwnsRow(identity, member, ['user_id', 'profile_id'])) {
      ids.add(normalizeIdentityValue(member[`${kind}_id`] || member.room_id))
    }
  }
  ids.delete('')
  return ids
}

async function importedAndCompatRows(pool, tableName) {
  const [imported, compat] = await Promise.all([
    readImportedRows(pool, tableName),
    readCompatRows(pool, tableName),
  ])
  return mergeRows([imported, compat], tableName)
}

async function ownedFolderIds(pool, identity) {
  const folders = await importedAndCompatRows(pool, 'chat_folders')
  return new Set(
    folders
      .filter((row) => identityOwnsRow(identity, row, ['user_id', 'owner_id']))
      .map((row) => normalizeIdentityValue(row.id))
      .filter(Boolean),
  )
}

async function highlightRows(pool) {
  return importedAndCompatRows(pool, 'moment_highlights')
}

async function readableHighlightIds(pool, identity) {
  const highlights = await highlightRows(pool)
  return new Set(
    highlights
      .filter((row) => row.is_public !== false || identityOwnsRow(identity, row, ['user_id', 'owner_id']))
      .map((row) => normalizeIdentityValue(row.id))
      .filter(Boolean),
  )
}

async function pollRows(pool) {
  return importedAndCompatRows(pool, 'message_polls')
}

async function accessiblePollIds(pool, identity) {
  const [polls, chatIds, groupIds, communityIds] = await Promise.all([
    pollRows(pool),
    accessibleChatIds(pool, identity),
    accessibleCollectionIds(pool, 'group', identity),
    accessibleCollectionIds(pool, 'community', identity),
  ])
  const result = new Set()
  for (const poll of polls) {
    const pollId = normalizeIdentityValue(poll.id)
    const scopeType = normalizeIdentityValue(poll.scope_type || poll.scopeType)
    const scopeId = normalizeIdentityValue(poll.scope_id || poll.scopeId)
    const owned = identityOwnsRow(identity, poll, ['creator_id', 'user_id', 'owner_id'])
    const accessible = scopeType === 'group'
      ? groupIds.has(scopeId)
      : scopeType === 'community'
        ? communityIds.has(scopeId)
        : ['chat', 'direct', 'message'].includes(scopeType)
          ? chatIds.has(scopeId)
          : owned
    if (['group','community'].includes(scopeType)) {
      const state = await loadCollectionMembershipState(pool, scopeType, scopeId, identity.values)
      if (!collectionHistoryAllows(state.history, poll)) continue
    }
    if (pollId && (owned || accessible)) result.add(pollId)
  }
  return result
}

async function findCompatEntity(pool, tableName, id) {
  const cleanId = normalizeIdentityValue(id)
  if (!cleanId) return null
  const rows = await importedAndCompatRows(pool, tableName)
  return rows.find((row) => normalizeIdentityValue(row.id) === cleanId) || null
}

async function canAccessPoll(pool, identity, pollId) {
  const cleanPollId = normalizeIdentityValue(pollId)
  return cleanPollId ? (await accessiblePollIds(pool, identity)).has(cleanPollId) : false
}

async function canManagePoll(pool, identity, pollId) {
  if (await appAdminHasCapability(pool, identity, ADMIN_CAPABILITY.CONTENT_MANAGE)) return true
  const poll = await findCompatEntity(pool, 'message_polls', pollId)
  return Boolean(poll && identityOwnsRow(identity, poll, ['creator_id', 'user_id', 'owner_id']))
}

async function accessibleStickerPackIds(pool, identity) {
  const packs = await importedAndCompatRows(pool, 'sticker_packs')
  return new Set(
    packs
      .filter((pack) => stickerPackReadable(pack, identity?.ids))
      .map((pack) => normalizeIdentityValue(pack.id))
      .filter(Boolean),
  )
}

async function canManageStickerPack(pool, identity, packId) {
  if (await appAdminHasCapability(pool, identity, ADMIN_CAPABILITY.CONTENT_MANAGE)) return true
  const pack = await findCompatEntity(pool, 'sticker_packs', packId)
  return Boolean(pack && identityOwnsRow(identity, pack, ['owner_id', 'user_id', 'creator_id']))
}

async function canManageHighlight(pool, identity, highlightId) {
  if (await appAdminHasCapability(pool, identity, ADMIN_CAPABILITY.CONTENT_MANAGE)) return true
  const highlight = await findCompatEntity(pool, 'moment_highlights', highlightId)
  return Boolean(highlight && identityOwnsRow(identity, highlight, ['user_id', 'owner_id']))
}

async function ownsMoment(pool, identity, momentId) {
  const cleanMomentId = normalizeIdentityValue(momentId)
  if (!cleanMomentId) return false
  const { rows } = await pool.query(
    `SELECT user_id FROM fallback_moments WHERE lower(id::text) = $1 LIMIT 1`,
    [cleanMomentId],
  )
  if (rows[0]) return identityOwnsRow(identity, rows[0], ['user_id'])
  const imported = await readImportedRows(pool, 'moments')
  const moment = imported.find((row) => normalizeIdentityValue(row.id) === cleanMomentId)
  return Boolean(moment && identityOwnsRow(identity, moment, ['user_id', 'owner_id']))
}

function rowScopeId(row, fields) {
  for (const field of fields) {
    const value = normalizeIdentityValue(row?.[field])
    if (value) return value
  }
  return ''
}

function sanitizeProfileRow(row, identity) {
  const result = normalizeCompatProfileJson({...row,
    ...(row.__profile_override&&typeof row.__profile_override==='object'?row.__profile_override:{}),
    id:row.id,local_id:row.local_id})
  delete result.__profile_override
  if (result.profile_view_history_enabled == null && result.profileViewHistoryEnabled == null) {
    result.profile_view_history_enabled = true
  }
  const isSelf = identityOwnsRow(identity, row, ['id', 'local_id', 'supabase_user_id', 'user_id'])
  if (!isSelf) {
    delete result.email
    delete result.secondary_email
    delete result.phone
    delete result.local_id
    delete result.local_password_set_at
    delete result.password_changed_by_user_at
  }
  return result
}

async function filterRowsForRead(pool, tableName, rows, auth) {
  const identity = await getAuthIdentity(pool, auth)
  if (!identity) throw httpError('AUTH_REQUIRED', 401)
  const isAdmin = await isAppAdmin(pool, identity)

  if (tableName === 'profiles') {
    return rows.map((row) => sanitizeProfileRow(row, identity))
  }
  if (tableName === 'profile_views') {
    const ownRows=rows.filter(row=>identityOwnsValue(identity,row.profile_id)&&row.is_deleted!==true&&row.is_deleted!=='true');
    if(!ownRows.length)return [];
    const profiles=await readTableRows(pool,'profiles',{},auth);
    const enabled=p=>p?.profile_view_history_enabled===true;
    const owner=profiles.find(p=>identityOwnsRow(identity,p,['id','user_id','supabase_user_id','local_id']));
    if(!enabled(owner))return [];
    const aliases=p=>['id','user_id','supabase_user_id','local_id'].map(k=>normalizeIdentityValue(p[k])).filter(Boolean);
    const byId=new Map(profiles.flatMap(p=>aliases(p).map(id=>[id,p])));
    // Foreign profile responses intentionally omit local_id. Resolve legacy
    // visitor aliases internally, retaining the public ID in the response.
    const visitorIds=[...new Set(ownRows.map(row=>normalizeIdentityValue(row.viewer_id)).filter(Boolean))];
    const visitorAccounts=(await pool.query(`SELECT lower(id::text) AS local_id,
      lower(COALESCE(supabase_user_id::text,id::text)) AS public_id
      FROM fallback_users WHERE lower(id::text)=ANY($1::text[])
        OR lower(supabase_user_id::text)=ANY($1::text[])`,[visitorIds])).rows;
    for(const account of visitorAccounts){
      const profile=byId.get(account.public_id)||byId.get(account.local_id);
      if(profile){byId.set(account.local_id,profile);byId.set(account.public_id,profile);}
    }
    // Current blocks in either direction also hide an existing visit.
    const blockRows=(await pool.query(`SELECT lower(b.blocker_id) AS blocker_id,lower(b.blocked_id) AS blocked_id,
      lower(COALESCE(a.supabase_user_id::text,a.id::text)) AS blocker_public_id,
      lower(COALESCE(z.supabase_user_id::text,z.id::text)) AS blocked_public_id
      FROM fallback_user_blocks b
      LEFT JOIN fallback_users a ON lower(a.id::text)=lower(b.blocker_id)
      LEFT JOIN fallback_users z ON lower(z.id::text)=lower(b.blocked_id)
      WHERE lower(b.blocker_id)=ANY($1::text[]) OR lower(b.blocked_id)=ANY($1::text[])`,[identity.values])).rows;
    const blocked=new Set(blockRows.flatMap(b=>
      [b.blocker_id,b.blocked_id,b.blocker_public_id,b.blocked_public_id]
        .map(normalizeIdentityValue).filter(id=>id&&!identityOwnsValue(identity,id))));
    const latest=new Map();
    for(const row of ownRows){
      const viewer=byId.get(normalizeIdentityValue(row.viewer_id));
      const time=Date.parse(row.viewed_at);
      if(!enabled(viewer)||!Number.isFinite(time)||aliases(viewer).some(id=>blocked.has(id)))continue;
      const id=normalizeIdentityValue(viewer.id);
      if(!id||identityOwnsValue(identity,id))continue;
      const previous=latest.get(id);
      if(!previous||Date.parse(previous.viewed_at)<time)latest.set(id,{...row,
        profile_id:identity.preferredUserId,viewer_id:id,viewed_at:new Date(time).toISOString()});
    }
    return [...latest.values()];
  }
  if (tableName === 'virtual_gifts') {
    return rows.filter((row) => DEFAULT_VIRTUAL_GIFT_IDS.has(String(row.id || '').trim()) && row.is_active !== false)
  }
  if (tableName === 'users') {
    return rows
      .filter((row) => isAdmin || identityOwnsRow(identity, row, ['id', 'local_id', 'supabase_user_id']))
      .map((row) => sanitizeProfileRow(row, identity))
  }
  if (tableName === 'moderation_events') return isAdmin ? rows : []
  if (tableName === 'reports' || tableName === 'video_reports') {
    return rows.filter((row) => isAdmin || identityOwnsRow(identity, row, ['reporter_id']))
  }
  if (tableName === 'notifications') {
    return rows.filter((row) => identityOwnsRow(identity, row, ['recipient_id', 'user_id']))
  }
  if (tableName === 'videos') {
    const { rows: deletedRows } = await pool.query(
      `SELECT lower(video_id) AS video_id FROM fallback_video_deletions`,
    )
    const deletedIds = new Set(deletedRows.map((row) => normalizeIdentityValue(row.video_id)).filter(Boolean))
    return rows.filter((row) => {
      const videoId = normalizeIdentityValue(row.id)
      if (!videoId || deletedIds.has(videoId)) return false
      return row.is_public !== false || isAdmin || identityOwnsRow(identity, row, ['user_id', 'owner_id', 'creator_id'])
    })
  }
  if (tableName === 'friends') {
    return rows.filter((row) => identityOwnsRow(identity, row, ['sender_id', 'receiver_id']))
  }
  if (tableName === 'chat_folder_chats') {
    const [folderIds, chatIds] = await Promise.all([
      ownedFolderIds(pool, identity),
      accessibleChatIds(pool, identity),
    ])
    return rows.filter((row) =>
      folderIds.has(normalizeIdentityValue(row.folder_id)) &&
      chatIds.has(normalizeIdentityValue(row.chat_id)),
    )
  }
  if (tableName === 'moment_highlights') {
    return rows.filter((row) => row.is_public !== false || identityOwnsRow(identity, row, ['user_id', 'owner_id']))
  }
  if (tableName === 'moment_highlight_items') {
    const highlightIds = await readableHighlightIds(pool, identity)
    return rows.filter((row) => highlightIds.has(normalizeIdentityValue(row.highlight_id)))
  }
  if (tableName === 'message_polls') {
    const pollIds = await accessiblePollIds(pool, identity)
    return rows.filter((row) => pollIds.has(normalizeIdentityValue(row.id)))
  }
  if (tableName === 'message_poll_options' || tableName === 'message_poll_votes') {
    const pollIds = await accessiblePollIds(pool, identity)
    return rows.filter((row) => pollIds.has(normalizeIdentityValue(row.poll_id)))
  }
  if (tableName === 'sticker_packs') {
    const packIds = await accessibleStickerPackIds(pool, identity)
    return rows.filter((row) => packIds.has(normalizeIdentityValue(row.id)))
  }
  if (tableName === 'sticker_pack_items') {
    const packIds = await accessibleStickerPackIds(pool, identity)
    return rows.filter((row) => !stickerRowDeleted(row) && packIds.has(normalizeIdentityValue(row.pack_id)))
  }

  if (['messages', 'chats', 'chat_participants'].includes(tableName)) {
    const chatIds = await accessibleChatIds(pool, identity)
    if (tableName === 'chats') {
      return rows.filter((row) => chatIds.has(rowScopeId(row, ['id', 'chat_id'])))
    }
    return rows.filter((row) => chatIds.has(rowScopeId(row, ['chat_id', 'id'])))
  }

  const collectionKind = tableName.startsWith('group_') || tableName === 'groups'
    ? 'group'
    : tableName.startsWith('community_') || tableName === 'communities'
      ? 'community'
      : null
  if (collectionKind) {
    const roomIds = await accessibleCollectionIds(pool, collectionKind, identity)
    if (tableName === 'communities') {
      return rows.filter((row) => row.is_public !== false || roomIds.has(rowScopeId(row, ['id'])))
    }
    const scopeFields = collectionKind === 'group'
      ? ['group_id', 'room_id', 'id']
      : ['community_id', 'room_id', 'id']
    const scoped = rows.filter(row => roomIds.has(rowScopeId(row, scopeFields)))
    if (tableName === `${collectionKind}_messages`) {
      const states = new Map(await Promise.all([...new Set(scoped.map(row => rowScopeId(row, scopeFields)))].map(async roomId =>
        [roomId, await loadCollectionMembershipState(pool, collectionKind, roomId, identity.values)])))
      return scoped.filter(row => collectionHistoryAllows(states.get(rowScopeId(row, scopeFields))?.history, row))
    }
    return scoped
  }

  const privateFields = PRIVATE_OWNER_FIELDS.get(tableName)
  if (privateFields) {
    return rows.filter((row) => identityOwnsRow(identity, row, privateFields))
  }
  return rows
}

function mergeRows(groups, tableName = '') {
  const byId = new Map()
  const noId = []
  for (const row of groups.flat()) {
    const normalized = compactRow(row)
    const id = rowId(normalized, tableName)
    if (!id) {
      noId.push(normalized)
      continue
    }
    byId.set(id, { ...(byId.get(id) || {}), ...normalized })
  }
  return [...byId.values(), ...noId]
}

function parseFilterValue(raw) {
  const value = String(raw || '')
  const firstDot = value.indexOf('.')
  if (firstDot === -1) return { op: 'eq', value }
  return {
    op: value.slice(0, firstDot),
    value: value.slice(firstDot + 1),
  }
}

function coerceComparable(value) {
  if (value === null || value === undefined) return null
  return String(value)
}

function valueMatches(actual, op, expected) {
  const actualValue = coerceComparable(actual)
  const expectedValue = String(expected || '')
  switch (op) {
    case 'eq':
      return actualValue === expectedValue
    case 'neq':
      return actualValue !== expectedValue
    case 'is':
      return expectedValue === 'null' ? actual === null || actual === undefined : actualValue === expectedValue
    case 'not':
      return expectedValue === 'is.null' ? actual !== null && actual !== undefined : actualValue !== expectedValue
    case 'in': {
      const list = expectedValue.replace(/^\(/, '').replace(/\)$/, '').split(',').map((item) => item.trim())
      return list.includes(actualValue)
    }
    case 'like':
    case 'ilike': {
      const haystack = op === 'ilike' ? String(actualValue || '').toLowerCase() : String(actualValue || '')
      const needle = op === 'ilike' ? expectedValue.toLowerCase() : expectedValue
      const pattern = needle.replace(/%|\*/g, '')
      return haystack.includes(pattern)
    }
    case 'gt':
      return Number(actualValue) > Number(expectedValue)
    case 'gte':
      return Number(actualValue) >= Number(expectedValue)
    case 'lt':
      return Number(actualValue) < Number(expectedValue)
    case 'lte':
      return Number(actualValue) <= Number(expectedValue)
    default:
      return actualValue === expectedValue
  }
}

function matchesOrClause(row, value) {
  const raw = String(value || '').trim().replace(/^\(/, '').replace(/\)$/, '')
  if (!raw) return true
  return raw.split(',').some((part) => {
    const pieces = part.split('.')
    if (pieces.length < 3) return true
    const [field, op, ...rest] = pieces
    return valueMatches(row[field], op, rest.join('.'))
  })
}

function applyFilters(rows, query) {
  return rows.filter((row) => {
    for (const [key, rawValue] of Object.entries(query || {})) {
      if (RESERVED_QUERY_KEYS.has(key)) continue
      const values = Array.isArray(rawValue) ? rawValue : [rawValue]
      const ok = values.every((entry) => {
        const { op, value } = parseFilterValue(entry)
        return valueMatches(row[key], op, value)
      })
      if (!ok) return false
    }
    if (query?.or && !matchesOrClause(row, query.or)) return false
    return true
  })
}

function applyOrder(rows, order) {
  const raw = String(order || '').trim()
  if (!raw) return rows
  const [field, direction] = raw.split('.')
  if (!field) return rows
  const descending = String(direction || '').toLowerCase().startsWith('desc')
  return [...rows].sort((a, b) => {
    const av = a[field]
    const bv = b[field]
    if (av === bv) return 0
    if (av === null || av === undefined) return 1
    if (bv === null || bv === undefined) return -1
    return (String(av) > String(bv) ? 1 : -1) * (descending ? -1 : 1)
  })
}

function applyRange(rows, query) {
  const offset = Math.max(0, Number(query?.offset || 0) || 0)
  const limit = Math.min(MAX_READ_ROWS, Math.max(0, Number(query?.limit || rows.length) || rows.length))
  return rows.slice(offset, offset + limit)
}

function wantsSingleObject(req) {
  return String(req.headers.accept || '').includes('vnd.pgrst.object+json')
}

function sendPostgrestRows(req, res, rows, status = 200, headOnly = false) {
  const total = rows.length
  const offset = Math.max(0, Number(req.query?.offset || 0) || 0)
  const ranged = applyRange(rows, req.query)
  const end = ranged.length > 0 ? offset + ranged.length - 1 : 0
  res.setHeader('Range-Unit', 'items')
  res.setHeader('Content-Range', `${ranged.length > 0 ? offset : 0}-${end}/${total}`)
  res.status(status)
  if (headOnly) return res.end()
  if (wantsSingleObject(req)) {
    return res.json(ranged[0] || null)
  }
  return res.json(ranged)
}

async function readTableRows(pool, tableName, query, auth) {
  const [importedRows, specificRows, compatRows] = await Promise.all([
    readImportedRows(pool, tableName),
    readSpecificRows(pool, tableName, auth),
    readCompatRows(pool, tableName),
  ])
  const merged = messageTables.has(tableName) ? mergeMessageRows([...importedRows, ...specificRows, ...compatRows]) : mergeRows([importedRows, specificRows, compatRows], tableName)
  const readable = await filterRowsForRead(pool, tableName, merged, auth)
  const identityForRead = messageTables.has(tableName) ? await getAuthIdentity(pool, auth) : null
  const visible = tableName === 'notifications'
    ? await filterDeletedMessageNotifications(pool, readable.filter(row => !isRetiredNotification(row)))
    : messageTables.has(tableName) ? readable.filter(row => !hiddenForIdentity(row, identityForRead)) : readable
  let filters=query
  if(tableName==='profile_views'){
    const identity=await getAuthIdentity(pool,auth)
    const ownerFilter=parseFilterValue(query?.profile_id)
    if(ownerFilter.op==='eq'&&identityOwnsValue(identity,ownerFilter.value)){
      filters={...query,profile_id:'eq.'+identity.preferredUserId}
    }
  }
  const filtered = applyFilters(visible, filters)
  return applyOrder(filtered, query?.order)
}

async function storeCompatRow(pool, tableName, row) {
  const cleaned = compactRow(row)
  const id = normalizeId(rowId(cleaned, tableName))
  const stored = {
    ...cleaned,
    id: cleaned.id || id,
    created_at: cleaned.created_at || nowIso(),
    updated_at: nowIso(),
  }
  await pool.query(
    `INSERT INTO fallback_compat_rows(table_name, row_id, row)
     VALUES ($1, $2, $3)
     ON CONFLICT (table_name, row_id)
     DO UPDATE SET row = fallback_compat_rows.row || EXCLUDED.row,
                   updated_at = NOW()`,
    [tableName, id, stored],
  )
  return stored
}

async function canAccessChat(pool, identity, chatId) {
  const cleanChatId = normalizeIdentityValue(chatId)
  if (!cleanChatId) return false
  return (await accessibleChatIds(pool, identity)).has(cleanChatId)
}

async function canAccessCollection(pool, kind, identity, roomId) {
  const cleanRoomId = normalizeIdentityValue(roomId)
  if (!cleanRoomId) return false
  return (await loadCollectionMembershipState(pool, kind, cleanRoomId, identity.values)).member
}

async function canManageCollection(pool, kind, identity, roomId) {
  const cleanRoomId = normalizeIdentityValue(roomId)
  if (!cleanRoomId || !identity?.values?.length) return false
  return (await loadCollectionMembershipState(pool, kind, cleanRoomId, identity.values)).canManage
}

async function requireAdminWrite(pool, identity, tableName) {
  if (!ADMIN_WRITE_TABLES.has(tableName)) return
  if (!(await appAdminHasCapability(pool, identity, ADMIN_CAPABILITY.CONTENT_MANAGE))) {
    throw httpError('ADMIN_REQUIRED')
  }
}

async function canonicalDirectChatParticipants(pool, participants) {
  if (participants.length !== 2) throw httpError('DIRECT_CHAT_REQUIRES_TWO_PARTICIPANTS', 400)
  const { rows } = await pool.query(`SELECT DISTINCT fallback_request_identity(participant) AS id
    FROM unnest($1::text[]) participant ORDER BY id`, [participants])
  const canonical = [...new Set(rows.map(row => normalizeIdentityValue(row.id)).filter(Boolean))].sort()
  if (canonical.length !== 2) throw httpError('DIRECT_CHAT_REQUIRES_TWO_PARTICIPANTS', 400)
  return canonical
}

async function prepareInsertRow(pool, tableName, identity, source) {
  let row = compactRow(source)
  if (tableName === 'notifications' && isServerManagedNotification(row)) {
    throw httpError('SERVER_MANAGED_NOTIFICATION', 403)
  }
  if (SERVER_MUTATION_ONLY_TABLES.has(tableName)) {
    throw httpError('SERVER_MANAGED_RESOURCE', 403)
  }
  if (!['sticker_packs', 'sticker_pack_items'].includes(tableName)) {
    await requireAdminWrite(pool, identity, tableName)
  }

  if (tableName === 'profiles' || tableName === 'users') {
    if (row.id != null && !identityOwnsValue(identity, row.id)) throw httpError('PROFILE_ID_MISMATCH')
    return { ...row, id: identity.preferredUserId }
  }

  if (tableName === 'sticker_packs') {
    if (row.owner_id != null && !identityOwnsValue(identity, row.owner_id)) {
      throw httpError('OWNER_ID_MISMATCH')
    }
    return { ...row, owner_id: identity.preferredUserId }
  }

  if (tableName === 'sticker_pack_items') {
    const packId = normalizeIdentityValue(row.pack_id)
    if (!packId || !(await canManageStickerPack(pool, identity, packId))) {
      throw httpError('STICKER_PACK_OWNER_REQUIRED')
    }
    const stickerId = String(row.sticker_id || row.id || '').trim()
    const mediaUrl = String(row.media_url || '').trim()
    if (!stickerId || !mediaUrl || !(await canUseStickerMedia(pool, identity, stickerId, mediaUrl))) {
      throw httpError('STICKER_MEDIA_NOT_OWNED')
    }
    return { ...row, pack_id: packId }
  }

  if (tableName === 'groups' || tableName === 'communities') {
    row = forceActorField(row, 'creator_id', identity)
    if (row.owner_id != null && !identityOwnsValue(identity, row.owner_id)) {
      throw httpError('ACTOR_ID_MISMATCH')
    }
    return { ...row, owner_id: identity.preferredUserId }
  }

  if (tableName === 'group_members' || tableName === 'community_members') {
    throw httpError('COLLECTION_MEMBERSHIP_ENDPOINT_REQUIRED', 400)
  }

  if (tableName === 'messages') {
    const chatId = row.chat_id
    if (!(await canAccessChat(pool, identity, chatId))) throw httpError('CHAT_ACCESS_DENIED')
    return forceActorField(row, 'sender_id', identity)
  }

  if (tableName === 'group_messages' || tableName === 'community_messages') {
    const kind = tableName === 'group_messages' ? 'group' : 'community'
    const roomId = row[`${kind}_id`] || row.room_id
    if (!(await canAccessCollection(pool, kind, identity, roomId))) {
      throw httpError('COLLECTION_ACCESS_DENIED')
    }
    if (String(row.content_type || 'text').toLowerCase().startsWith('system_')) throw httpError('RESERVED_MESSAGE_TYPE',400)
    return forceActorField(row, 'sender_id', identity)
  }

  if (tableName === 'group_message_receipts' || tableName === 'community_message_receipts') {
    const kind = tableName === 'group_message_receipts' ? 'group' : 'community'
    const roomId = row[`${kind}_id`] || row.room_id
    if (roomId && !(await canAccessCollection(pool, kind, identity, roomId))) {
      throw httpError('COLLECTION_ACCESS_DENIED')
    }
    return forceActorField(row, 'user_id', identity)
  }

  if (tableName === 'chats') {
    const participants = Array.isArray(row.participants) ? row.participants : []
    const normalizedParticipants = Array.from(new Set([
      ...participants.map(normalizeIdentityValue).filter(Boolean),
      identity.preferredUserId,
    ]))
    if (row.creator_id != null && !identityOwnsValue(identity, row.creator_id)) {
      throw httpError('ACTOR_ID_MISMATCH')
    }
    return { ...row, creator_id: identity.preferredUserId, participants: normalizedParticipants }
  }

  if (tableName === 'chat_participants') {
    const chatId = row.chat_id
    if (!(await canAccessChat(pool, identity, chatId))) throw httpError('CHAT_ACCESS_DENIED')
    return row
  }

  if (tableName === 'voice_rooms') {
    row = forceActorField(row, 'creator_id', identity)
    if (row.owner_id != null && !identityOwnsValue(identity, row.owner_id)) {
      throw httpError('ACTOR_ID_MISMATCH')
    }
    return { ...row, owner_id: identity.preferredUserId }
  }

  if (tableName === 'chat_folder_chats') {
    const folderId = normalizeIdentityValue(row.folder_id)
    const chatId = normalizeIdentityValue(row.chat_id)
    if (!folderId || !chatId) throw httpError('FOLDER_ID_AND_CHAT_ID_REQUIRED', 400)
    if (!(await ownedFolderIds(pool, identity)).has(folderId)) throw httpError('FOLDER_ACCESS_DENIED')
    if (!(await canAccessChat(pool, identity, chatId))) throw httpError('CHAT_ACCESS_DENIED')
    return { ...row, folder_id: folderId, chat_id: chatId, user_id: identity.preferredUserId }
  }

  if (tableName === 'moment_highlights') {
    return forceActorField(row, 'user_id', identity)
  }

  if (tableName === 'moment_highlight_items') {
    const highlightId = normalizeIdentityValue(row.highlight_id)
    const momentId = normalizeIdentityValue(row.moment_id)
    if (!highlightId || !momentId) throw httpError('HIGHLIGHT_ID_AND_MOMENT_ID_REQUIRED', 400)
    if (!(await canManageHighlight(pool, identity, highlightId))) throw httpError('HIGHLIGHT_ACCESS_DENIED')
    if (!(await ownsMoment(pool, identity, momentId))) throw httpError('MOMENT_NOT_OWNED')
    return {
      ...row,
      highlight_id: highlightId,
      moment_id: momentId,
      user_id: identity.preferredUserId,
    }
  }

  if (tableName === 'message_polls') {
    const scopeType = normalizeIdentityValue(row.scope_type || row.scopeType)
    const scopeId = normalizeIdentityValue(row.scope_id || row.scopeId)
    if (!scopeType || !scopeId) throw httpError('POLL_SCOPE_REQUIRED', 400)
    const canAccessScope = scopeType === 'group'
      ? await canAccessCollection(pool, 'group', identity, scopeId)
      : scopeType === 'community'
        ? await canAccessCollection(pool, 'community', identity, scopeId)
        : ['chat', 'direct', 'message'].includes(scopeType)
          ? await canAccessChat(pool, identity, scopeId)
          : false
    if (!canAccessScope) throw httpError('POLL_SCOPE_ACCESS_DENIED')
    row = forceActorField(row, 'creator_id', identity)
    return { ...row, scope_type: scopeType, scope_id: scopeId }
  }

  if (tableName === 'message_poll_options') {
    const pollId = normalizeIdentityValue(row.poll_id)
    if (!pollId || !(await canManagePoll(pool, identity, pollId))) {
      throw httpError('POLL_CREATOR_REQUIRED')
    }
    return { ...row, poll_id: pollId }
  }

  if (tableName === 'message_poll_votes') {
    const pollId = normalizeIdentityValue(row.poll_id)
    const optionId = normalizeIdentityValue(row.option_id)
    if (!pollId || !optionId || !(await canAccessPoll(pool, identity, pollId))) {
      throw httpError('POLL_ACCESS_DENIED')
    }
    const option = (await importedAndCompatRows(pool, 'message_poll_options'))
      .find((entry) => normalizeIdentityValue(entry.id) === optionId)
    if (!option || normalizeIdentityValue(option.poll_id) !== pollId) {
      throw httpError('POLL_OPTION_NOT_FOUND', 404)
    }
    return forceActorField({ ...row, poll_id: pollId, option_id: optionId }, 'user_id', identity)
  }

  if (tableName === 'reports') {
    const targetId = normalizeIdentityValue(row.reported_user_id)
    const reason = String(row.reason || '').trim().slice(0, 200)
    const evidenceUrl = String(row.evidence_url || '').trim().slice(0, 2000) || null
    if (!targetId || !reason) throw httpError('REPORTED_USER_AND_REASON_REQUIRED', 400)

    const isVoiceAccessRequest = reason.toLowerCase() === 'solicitud de acceso a voz'
    const reportingSelf = identityOwnsValue(identity, targetId)
    if (reportingSelf && !isVoiceAccessRequest) throw httpError('SELF_REPORT_FORBIDDEN')

    const target = reportingSelf ? null : await resolveFallbackUser(pool, targetId)
    if (!reportingSelf && !target) throw httpError('REPORTED_USER_NOT_FOUND', 404)
    if (!(await canUseReportEvidence(pool, identity, evidenceUrl))) {
      throw httpError('REPORT_EVIDENCE_STORAGE_OWNERSHIP_REQUIRED')
    }

    return forceActorField({
      ...row,
      reporter_name: identity.displayName,
      reported_user_id: reportingSelf ? identity.preferredUserId : target.public_user_id,
      reported_user_name: reportingSelf ? identity.displayName : (target.display_name || target.email || 'Usuario'),
      reason,
      evidence_url: evidenceUrl,
      content_type: 'user',
      content_id: reportingSelf ? identity.preferredUserId : target.public_user_id,
      status: 'pendiente',
    }, 'reporter_id', identity)
  }

  if (tableName === 'creator_program_applications') {
    return {
      user_id: identity.preferredUserId,
      status: 'pending',
      niche: String(row.niche || '').trim().slice(0, 120) || null,
      portfolio_url: String(row.portfolio_url || '').trim().slice(0, 2000) || null,
      payout_country: String(row.payout_country || '').trim().toUpperCase().slice(0, 2) || null,
      audience_size: Math.max(0, Math.min(1_000_000_000, Math.trunc(Number(row.audience_size || 0) || 0))),
      reviewed_by: null,
      reviewed_at: null,
      rejection_reason: null,
    }
  }

  if (tableName === 'creator_payout_accounts') {
    const provider = String(row.provider || 'manual').trim().toLowerCase()
    const currency = String(row.currency || 'USD').trim().toUpperCase()
    const externalAccountId = String(row.external_account_id || '').trim().slice(0, 255) || null
    if (!isSafePayoutProviderReference(externalAccountId)) {
      throw httpError('PAYOUT_REFERENCE_UNSAFE', 400)
    }
    return {
      user_id: identity.preferredUserId,
      provider: ['stripe', 'paypal', 'bank', 'manual'].includes(provider) ? provider : 'manual',
      external_account_id: externalAccountId,
      country: String(row.country || '').trim().toUpperCase().slice(0, 2) || null,
      currency: /^[A-Z]{3}$/.test(currency) ? currency : 'USD',
      status: 'pending',
    }
  }

  if (tableName === 'profile_views') {
    const secured = forceActorField(row, 'viewer_id', identity)
    const targetId = normalizeIdentityValue(secured.profile_id)
    if (!targetId || identityOwnsValue(identity, targetId)) {
      throw httpError('PROFILE_VIEW_TARGET_INVALID', 400)
    }
    // readTableRows expects an authenticated token subject; the resolved identity
    // has localUserId/ids instead of sub. Passing it directly rejects valid visits.
    const profiles = await readTableRows(pool, 'profiles', {}, { sub: identity.localUserId })
    const target = profiles.find((profile) =>
      normalizeIdentityValue(rowId(profile, 'profiles')) === targetId,
    )
    const viewer = profiles.find((profile) => identityOwnsRow(identity, profile, [
      'id', 'user_id', 'supabase_user_id', 'local_id',
    ]))
    const enabled = (profile) => profile?.profile_view_history_enabled === true ||
      String(profile?.profile_view_history_enabled || '').toLowerCase() === 'true'
    if (!enabled(target) || !enabled(viewer)) {
      throw httpError('PROFILE_VIEW_HISTORY_DISABLED', 403)
    }
    const blocked=(await pool.query(`WITH target_ids AS (
      SELECT lower(id::text) AS id FROM fallback_users
      WHERE lower(id::text)=$2 OR lower(supabase_user_id::text)=$2
      UNION SELECT $2::text
    ) SELECT 1 FROM fallback_user_blocks
      WHERE (lower(blocker_id)=ANY($1::text[]) AND lower(blocked_id) IN (SELECT id FROM target_ids))
         OR (lower(blocked_id)=ANY($1::text[]) AND lower(blocker_id) IN (SELECT id FROM target_ids)) LIMIT 1`,
      [identity.values,targetId])).rows.length>0
    if(blocked)throw httpError('PROFILE_VIEW_TARGET_UNAVAILABLE',403)
    // Ignore supplied IDs and clocks. A visit is one server-dated pair.
    return {profile_id:targetId,viewer_id:identity.preferredUserId,viewed_at:nowIso(),is_deleted:false}
  }

  const actorField = AUTHORED_TABLE_FIELDS.get(tableName)
  if (actorField) return forceActorField(row, actorField, identity)

  const privateFields = PRIVATE_OWNER_FIELDS.get(tableName)
  if (privateFields) {
    for (const field of privateFields) {
      if (row[field] != null && String(row[field]).trim() && !identityOwnsValue(identity, row[field])) {
        throw httpError('OWNER_ID_MISMATCH')
      }
    }
    return { ...row, [privateFields[0]]: identity.preferredUserId }
  }

  throw httpError('TABLE_WRITE_NOT_ALLOWED')
}

function patchOnlyContains(patch, allowedFields) {
  return Object.keys(patch || {}).every((field) => allowedFields.has(field))
}

function isSafePayoutProviderReference(value) {
  const clean = String(value || '').trim()
  if (!clean) return true
  if (clean.length > 255) return false
  const digitsOnly = clean.replace(/\D/g, '')
  const cardLike = digitsOnly.length >= 12 && digitsOnly.length <= 19 && /^[\d\s-]+$/.test(clean)
  const cvvLike = digitsOnly.length >= 3 && digitsOnly.length <= 4 && /^\d+$/.test(clean)
  return !cardLike && !cvvLike
}

async function authorizeMutation(pool, tableName, identity, row, patch, action) {
  if (SERVER_MUTATION_ONLY_TABLES.has(tableName)) return false
  if (tableName === 'sticker_packs') {
    return (await canManageStickerPack(pool, identity, row.id)) &&
      (action === 'delete' || patchOnlyContains(patch, new Set([
        'name', 'description', 'cover_sticker_id', 'is_public', 'updated_at',
      ])))
  }
  if (tableName === 'sticker_pack_items') {
    if (!(await canManageStickerPack(pool, identity, row.pack_id))) return false
    if (action === 'delete') return true
    if (!patchOnlyContains(patch, new Set([
      'title', 'label', 'media_url', 'thumbnail_url', 'is_animated', 'tags', 'updated_at',
    ]))) return false
    const mediaUrl = patch.media_url || row.media_url
    const stickerId = row.sticker_id || row.id
    return canUseStickerMedia(pool, identity, stickerId, mediaUrl)
  }
  if (ADMIN_WRITE_TABLES.has(tableName)) {
    return appAdminHasCapability(pool, identity, ADMIN_CAPABILITY.CONTENT_MANAGE)
  }
  if (tableName === 'profiles' || tableName === 'users') {
    return identityOwnsRow(identity, row, ['id', 'local_id', 'supabase_user_id', 'user_id'])
  }
  if (tableName === 'groups' || tableName === 'communities') {
    const kind = tableName === 'groups' ? 'group' : 'community'
    return canManageCollection(pool, kind, identity, row.id)
  }
  if (tableName === 'group_members' || tableName === 'community_members') return false
  if (tableName === 'notifications') {
    return identityOwnsRow(identity, row, ['recipient_id', 'user_id']) &&
      (action === 'delete' || patchOnlyContains(patch, new Set([
        'is_read', 'read_at', 'updated_at',
      ])))
  }
  if (tableName === 'friends') {
    return identityOwnsRow(identity, row, ['sender_id', 'receiver_id'])
  }
  if (tableName === 'messages') {
    if (!(await canAccessChat(pool, identity, row.chat_id))) return false
    if (isDeletedForAll(row)) return action === 'delete' ||
      (identityOwnsRow(identity, row, ['sender_id']) && isRecallPatch(patch)) || isHidePatch(row, patch, identity)
    if (identityOwnsRow(identity, row, ['sender_id'])) return true
    if (action === 'delete') return false
    return patchOnlyContains(patch, new Set([
      'delivered_at', 'read_at', 'reactions', 'status', 'updated_at',
    ]))
  }
  if (tableName === 'group_messages' || tableName === 'community_messages') {
    const kind = tableName === 'group_messages' ? 'group' : 'community'
    const roomId = row[`${kind}_id`] || row.room_id
    if (!(await canAccessCollection(pool, kind, identity, roomId))) return false
    const state = await loadCollectionMembershipState(pool, kind, roomId, identity.values)
    if (!collectionHistoryAllows(state.history,row) || row.content_type === 'system_membership' || String(patch?.content_type || '').toLowerCase().startsWith('system_')) return false
    if (isDeletedForAll(row)) return action === 'delete' ||
      (identityOwnsRow(identity, row, ['sender_id']) && isRecallPatch(patch)) || isHidePatch(row, patch, identity)
    if (identityOwnsRow(identity, row, ['sender_id'])) return true
    if (action === 'delete') return false
    return patchOnlyContains(patch, new Set([
      'delivered_at', 'read_at', 'reactions', 'status', 'updated_at',
    ]))
  }
  if (tableName === 'chats' || tableName === 'chat_participants') {
    return canAccessChat(pool, identity, row.chat_id || row.id)
  }
  if (tableName === 'group_message_receipts' || tableName === 'community_message_receipts') {
    return identityOwnsRow(identity, row, ['user_id'])
  }
  if (tableName === 'voice_rooms') {
    return identityOwnsRow(identity, row, ['creator_id', 'owner_id'])
  }
  if (tableName === 'chat_folder_chats') {
    const folderId = normalizeIdentityValue(row.folder_id)
    const chatId = normalizeIdentityValue(row.chat_id)
    return (await ownedFolderIds(pool, identity)).has(folderId) &&
      (await canAccessChat(pool, identity, chatId))
  }
  if (tableName === 'moment_highlights') {
    return identityOwnsRow(identity, row, ['user_id', 'owner_id'])
  }
  if (tableName === 'moment_highlight_items') {
    return canManageHighlight(pool, identity, row.highlight_id)
  }
  if (tableName === 'message_polls') {
    return canManagePoll(pool, identity, row.id)
  }
  if (tableName === 'message_poll_options') {
    return canManagePoll(pool, identity, row.poll_id)
  }
  if (tableName === 'message_poll_votes') {
    return identityOwnsRow(identity, row, ['user_id']) && canAccessPoll(pool, identity, row.poll_id)
  }
  if (tableName === 'creator_program_applications') {
    if (!identityOwnsRow(identity, row, ['user_id', 'creator_id'])) return false
    if (action === 'delete' || normalizeIdentityValue(row.status) !== 'pending') return false
    if (!patchOnlyContains(patch, new Set([
      'niche', 'portfolio_url', 'payout_country', 'audience_size', 'updated_at',
    ]))) return false
    if (patch.niche != null && String(patch.niche).length > 120) return false
    if (patch.portfolio_url != null && String(patch.portfolio_url).length > 2000) return false
    if (patch.payout_country != null && !/^[A-Za-z]{2}$/.test(String(patch.payout_country).trim())) return false
    if (patch.audience_size != null) {
      const audience = Number(patch.audience_size)
      if (!Number.isInteger(audience) || audience < 0 || audience > 1_000_000_000) return false
    }
    return true
  }
  if (tableName === 'creator_payout_accounts') {
    if (!identityOwnsRow(identity, row, ['user_id', 'creator_id'])) return false
    if (action === 'delete') return false
    if (!patchOnlyContains(patch, new Set([
      'provider', 'external_account_id', 'country', 'currency', 'status', 'updated_at',
    ]))) return false
    if (Object.prototype.hasOwnProperty.call(patch || {}, 'status') && patch.status !== 'pending') return false
    if (patch.provider != null && !['stripe', 'paypal', 'bank', 'manual'].includes(String(patch.provider).trim().toLowerCase())) return false
    if (patch.country != null && !/^[A-Za-z]{2}$/.test(String(patch.country).trim())) return false
    if (patch.currency != null && !/^[A-Za-z]{3}$/.test(String(patch.currency).trim())) return false
    return isSafePayoutProviderReference(patch.external_account_id)
  }
  if (tableName === 'profile_views') {
    // New views use the validated POST path. PATCH cannot forge a person or date.
    return action === 'delete' && identityOwnsRow(identity, row, ['profile_id', 'viewer_id'])
  }
  const actorField = AUTHORED_TABLE_FIELDS.get(tableName)
  if (actorField) return identityOwnsRow(identity, row, [actorField])
  const privateFields = PRIVATE_OWNER_FIELDS.get(tableName)
  if (privateFields) return identityOwnsRow(identity, row, privateFields)
  return false
}

async function upsertProfile(pool, auth, row) {
  const identity = await getAuthIdentity(pool, auth)
  if (!identity) throw httpError('AUTH_REQUIRED', 401)
  // Provenance comes from registration, never from a client profile update.
  row = { ...row }
  delete row.username_origin
  const visibleNameFields=['display_name','displayName','full_name','fullName','name']
  if (visibleNameFields.some(key=>Object.hasOwn(row,key)&&row[key]!=null)) {
    const currentProfiles=await readTableRows(pool,'profiles',{},auth)
    const existing=currentProfiles.find(profile=>identityOwnsRow(identity,profile,['id','local_id','user_id']))||{}
    const currentName=existing.display_name||existing.full_name||existing.name||''
    for(const key of visibleNameFields) if(Object.hasOwn(row,key)&&row[key]!=null) {
      row[key]=require('./display-name-policy').validateDisplayNameChange(row[key],existing[key]||currentName)
    }
  }
  if (Object.prototype.hasOwnProperty.call(row, 'username')) {
    const username = cleanUsername(row.username)
    const { rows: currentNames } = await pool.query(
      `SELECT profile->>'username' AS username FROM fallback_profile_overrides
       WHERE user_id = $1::uuid AND NULLIF(trim(profile->>'username'), '') IS NOT NULL
       UNION ALL
       SELECT row->>'username' AS username FROM fallback_compat_rows
       WHERE table_name = 'profiles' AND lower(row_id) = ANY($2::text[])
         AND NULLIF(trim(row->>'username'), '') IS NOT NULL
       LIMIT 1`,
      [identity.localUserId, identity.values],
    )
    let existing = cleanUsername(currentNames[0]?.username)
    if (!existing) {
      const { rows: importedTable } = await pool.query(
        "SELECT to_regclass('supabase_import.profiles') AS table_oid",
      )
      if (importedTable[0]?.table_oid) {
        const { rows: importedNames } = await pool.query(
          `SELECT to_jsonb(t)->>'username' AS username
           FROM supabase_import.profiles t
           WHERE lower(COALESCE(to_jsonb(t)->>'id', '')) = ANY($1::text[])
              OR lower(COALESCE(to_jsonb(t)->>'user_id', '')) = ANY($1::text[])
              OR lower(COALESCE(to_jsonb(t)->>'supabase_user_id', '')) = ANY($1::text[])
           LIMIT 1`,
          [identity.values],
        )
        existing = cleanUsername(importedNames[0]?.username)
      }
    }
    if (existing && existing.toLowerCase() !== username.toLowerCase()) {
      throw httpError('USERNAME_IMMUTABLE', 409)
    }
    const problem = usernameProblem(username)
    if (!existing && problem) throw httpError(problem, 400)
    if (await usernameOwnedByAnother(pool, username, identity.values)) {
      throw httpError('USERNAME_TAKEN', 409)
    }
    row = { ...row, username }
    try {
      await pool.query(
        `INSERT INTO fallback_profile_overrides(user_id, profile, updated_at)
         VALUES ($1::uuid, jsonb_build_object('username', $2::text), NOW())
         ON CONFLICT (user_id) DO UPDATE SET
           profile = fallback_profile_overrides.profile || EXCLUDED.profile,
           updated_at = NOW()`,
        [identity.localUserId, username],
      )
    } catch (error) {
      if (error?.code === '23505' && error?.constraint === 'idx_fallback_profile_username_unique') {
        throw httpError('USERNAME_TAKEN', 409)
      }
      throw error
    }
  }
  const id = row.id || auth?.sub
  const displayName = row.display_name || row.username || row.full_name || row.name || row.email
  const avatarUrl = row.avatar_url || row.avatarUrl
  if (auth?.sub) {
    await pool.query(
      `UPDATE fallback_users
       SET display_name = COALESCE(NULLIF($2, ''), display_name),
           avatar_url = COALESCE(NULLIF($3, ''), avatar_url)
       WHERE id = $1`,
      [auth.sub, displayName || '', avatarUrl || ''],
    )
  }
  return storeCompatRow(pool, 'profiles', {
    ...row,
    id: id || row.id,
    display_name: displayName || row.display_name,
    avatar_url: avatarUrl || row.avatar_url,
  })
}

async function insertVideoRow(pool, auth, row) {
  const userId = row.user_id || row.userId || auth?.sub || ''
  const videoUrl = row.video_url || row.videoUrl || ''
  const videoId = row.id || crypto.randomUUID()
  const artist = row.sound_artist || row.soundArtist || row.user_name || row.username || row.userName || 'Novvin'
  const { rows } = await pool.query(
    `INSERT INTO fallback_videos(
        id, user_id, user_name, user_profile_image, title, description,
        video_url, cover_url, thumbnail_url, duration_seconds, is_public, tags,
        sound_id, sound_source_video_id, sound_title, sound_artist, sound_url
     )
     VALUES (
        $1::uuid, $2, $3, $4, $5, $6, $7, $8, $8, COALESCE($9, 0), COALESCE($10, TRUE),
        COALESCE($11::jsonb, '[]'::jsonb), $12, $13, $14, $15, $16
     )
     RETURNING id::text, user_id, user_name, user_profile_image, title, description,
               video_url, cover_url, thumbnail_url, duration_seconds AS duration,
               sound_id, sound_source_video_id, sound_title, sound_artist, sound_url,
               duration_seconds, views, like_count, comment_count, share_count,
               is_public, tags, created_at, updated_at`,
    [
      videoId,
      userId,
      row.user_name || row.username || row.userName || 'Usuario',
      row.user_profile_image || row.userProfileImage || null,
      row.title || 'Video',
      row.description || '',
      videoUrl,
      row.cover_url || row.thumbnail_url || row.thumbnailUrl || null,
      Number(row.duration || row.duration_seconds || 0) || 0,
      row.is_public ?? row.isPublic ?? true,
      JSON.stringify(Array.isArray(row.tags) ? row.tags : []),
      row.sound_id || row.soundId || videoId,
      row.sound_source_video_id || row.soundSourceVideoId || videoId,
      row.sound_title || row.soundTitle || `Sonido original — ${artist}`,
      artist,
      row.sound_url || row.soundUrl || videoUrl,
    ],
  )
  return rows[0]
}

async function insertFriendRow(pool, auth, row) {
  const senderId = row.sender_id || row.senderId || auth?.sub || ''
  const receiverId = row.receiver_id || row.receiverId || ''
  if (!receiverId || normalizeIdentityValue(senderId) === normalizeIdentityValue(receiverId)) {
    throw httpError('FRIEND_REQUEST_TARGET_INVALID', 400)
  }
  const { rows } = await pool.query(
    `INSERT INTO fallback_friends(sender_id, receiver_id, status)
     VALUES ($1, $2, 'pending')
     ON CONFLICT (sender_id, receiver_id) DO UPDATE SET status = fallback_friends.status
     RETURNING id::text, sender_id, receiver_id, status, created_at`,
    [senderId, receiverId],
  )
  return rows[0]
}

async function handleInsert(pool, tableName, auth, body) {
  const identity = await getAuthIdentity(pool, auth)
  if (!identity) throw httpError('AUTH_REQUIRED', 401)
  const rows = []
  for (const entry of asArrayBody(body)) {
    const base = await prepareInsertRow(pool, tableName, identity, entry)
    if (tableName === 'profiles' || tableName === 'users') {
      rows.push(await upsertProfile(pool, auth, base))
    } else if (tableName === 'chats' && base.is_group !== true) {
      const requestedParticipants = Array.from(new Set(
        (Array.isArray(base.participants) ? base.participants : [])
          .map(normalizeIdentityValue).filter(Boolean),
      )).sort()
      // Both identity aliases must resolve to the same unique direct conversation.
      const participants = await canonicalDirectChatParticipants(pool, requestedParticipants)
      if (participants.length !== 2) throw httpError('DIRECT_CHAT_REQUIRES_TWO_PARTICIPANTS', 400)
      const participantKey = participants.join(':')
      const chatId = validUuidOrNull(base.id) || crypto.randomUUID()
      const inserted = await pool.query(
        `INSERT INTO fallback_chats(id, participants, participant_key, is_group)
         VALUES ($1::uuid, $2::text[], $3, FALSE)
         ON CONFLICT (participant_key) DO UPDATE SET participants = fallback_chats.participants
         RETURNING id::text, participants, is_group, group_name, created_at, updated_at`,
        [chatId, participants, participantKey],
      )
      rows.push(await storeCompatRow(pool, 'chats', { ...base, ...inserted.rows[0] }))
    } else if (tableName === 'messages') {
      rows.push(await insertDirectRpcMessage(pool, auth, {
        chat_id: base.chat_id,
        message_id: base.id || crypto.randomUUID(),
        content: base.content,
        content_type: base.content_type,
        media_url: base.media_url,
        media_size: base.media_size,
        reply_to_id: base.reply_to_id,
        poll_id: base.poll_id,
        is_forwarded: base.is_forwarded,
        forwarded_from_message_id: base.forwarded_from_message_id,
        forwarded_from_user_id: base.forwarded_from_user_id,
      }))
    } else if (tableName === 'videos') {
      const storedVideo = await insertVideoRow(pool, auth, base)
      await ingestTrustedContentClassification(pool, 'video', storedVideo)
      rows.push(storedVideo)
    } else if (tableName === 'friends') {
      rows.push(await insertFriendRow(pool, auth, base))
    } else if (tableName === 'video_likes') {
      const videoId = base.video_id || base.videoId
      const userId = base.user_id || base.userId || auth?.sub
      await pool.query(
        `INSERT INTO fallback_video_likes(video_id, user_id) VALUES ($1, $2) ON CONFLICT DO NOTHING`,
        [videoId, userId],
      )
      rows.push({ id: `${videoId}:${userId}`, video_id: videoId, user_id: userId })
    } else {
      const storedRow = await storeCompatRow(pool, tableName, base)
      if (tableName === 'posts') {
        await ingestTrustedContentClassification(pool, 'post', storedRow)
      }
      rows.push(storedRow)
    }
  }
  if (tableName === 'challenge_submissions') {
    void publishChallengeLiveEvent({ action: 'submission' })
  }
  return rows
}

function extractIdFilter(query) {
  for (const field of ['id', 'user_id', 'profile_id', 'chat_id', 'post_id']) {
    const raw = query?.[field]
    if (!raw) continue
    const { op, value } = parseFilterValue(Array.isArray(raw) ? raw[0] : raw)
    if (op === 'eq' && value) return { field, value }
  }
  return null
}

function profilePatchRow(current, patch) {
  const next = { ...current, ...patch, updated_at: nowIso() }
  // Read projections can contain a legacy display-name fallback as username.
  // Only an explicitly requested username may reach the permanent-handle guard.
  if (!Object.prototype.hasOwnProperty.call(patch, 'username')) delete next.username
  return next
}

async function hideCompatDeletedMessage(pool, tableName, identity, current) {
  const kind=tableName==='messages'?'chat':tableName==='group_messages'?'group':'community'
  const scopeId=normalizeIdentityValue(current[kind+'_id']||current.room_id)
  const message=await hideDeletedMessageForActor(pool,{kind,scopeId,messageId:current.id,
    actorId:identity.preferredUserId,seedMessage:current})
  void publishRealtimeEvent('user:'+identity.preferredUserId,{event:'message',action:'deleted',
    chatId:kind==='chat'?scopeId:'room:'+kind+':'+scopeId,message,scope:'inbox'})
  return serializeCompatMessage(message)
}

async function recallCompatMessage(pool, tableName, identity, auth, current) {
  const kind = tableName === 'messages' ? 'chat' : tableName === 'group_messages' ? 'group' : 'community'
  const scopeId = normalizeIdentityValue(current[kind + '_id'] || current.room_id)
  let recipients = []
  if (kind === 'chat') {
    const chats = await readTableRows(pool, 'chats', { id: 'eq.' + scopeId }, auth)
    const members = await readTableRows(pool, 'chat_participants', { chat_id: 'eq.' + scopeId }, auth)
    recipients = [...(chats[0]?.participants || []), ...members.map(row => row.user_id || row.profile_id)]
  } else {
    const members = await readTableRows(pool, kind + '_members', { [kind + '_id']: 'eq.' + scopeId }, auth)
    recipients = members.map(row => row.user_id || row.profile_id)
  }
  recipients = [...new Set([...recipients, ...identity.values].filter(Boolean))]
  const message = await deleteMessageForAll(pool, { kind, scopeId, messageId: current.id,
    actorIds: identity.values, recipientIds: recipients, seedMessage: current })
  await publishChatMessageEvent({ pool, chatId: kind === 'chat' ? scopeId : 'room:' + kind + ':' + scopeId,
    message, recipientIds: recipients, action: 'deleted' })
  void processMessageDeletionOutbox(pool).catch(() => {})
  return serializeCompatMessage(message)
}

async function handlePatch(pool, tableName, auth, query, body) {
  const identity = await getAuthIdentity(pool, auth)
  if (!identity) throw httpError('AUTH_REQUIRED', 401)
  const currentRows = await readTableRows(pool, tableName, query, auth)
  const patch = compactRow(body || {})
  const targets = currentRows.length > 0 ? currentRows : []

  const rows = []
  for (const current of targets) {
    if (!(await authorizeMutation(pool, tableName, identity, current, patch, 'patch'))) {
      throw httpError('ROW_MUTATION_FORBIDDEN')
    }
    if (messageTables.has(tableName) && isDeletedForAll(current) && isHidePatch(current, patch, identity)) {
      rows.push(await hideCompatDeletedMessage(pool, tableName, identity, current))
      continue
    }
    if (messageTables.has(tableName) && isDeletedForAll(patch)) {
      rows.push(await recallCompatMessage(pool, tableName, identity, auth, current))
      continue
    }
    const next = { ...current, ...patch, updated_at: nowIso() }
    if (tableName === 'profiles' || tableName === 'users') {
      rows.push(await upsertProfile(pool, auth, profilePatchRow(current, patch)))
    } else if (tableName === 'friends') {
      const status = normalizeIdentityValue(patch.status)
      if (!identityOwnsValue(identity, current.receiver_id) ||
          !['accepted', 'rejected'].includes(status) ||
          !patchOnlyContains(patch, new Set(['status', 'updated_at']))) {
        throw httpError('FRIEND_REQUEST_RESPONSE_FORBIDDEN', 403)
      }
      const result = await pool.query(
        `INSERT INTO fallback_friends(sender_id, receiver_id, status)
         VALUES ($1, $2, $3)
         ON CONFLICT (sender_id, receiver_id) DO UPDATE SET status = EXCLUDED.status
         RETURNING id::text, sender_id, receiver_id, status, created_at`,
        [current.sender_id, current.receiver_id, status],
      )
      rows.push(result.rows[0])
    } else if (tableName === 'groups' || tableName === 'communities') {
      await pool.query(`
        UPDATE fallback_collection_rooms
        SET name = $1, description = $2, image = $3, is_public = $4, only_admins_can_talk = $5, updated_at = NOW()
        WHERE lower(id::text) = $6
      `, [
        next.name ?? current.name ?? '',
        next.description ?? current.description ?? '',
        next.image ?? next.avatar_url ?? current.image ?? current.avatar_url ?? '',
        next.is_public ?? current.is_public ?? true,
        next.only_admins_can_talk ?? current.only_admins_can_talk ?? false,
        String(next.id).toLowerCase()
      ])
      rows.push(next)
    } else {
      const storedRow = await storeCompatRow(pool, tableName, next)
      if (tableName === 'posts' || tableName === 'videos') {
        await ingestTrustedContentClassification(pool, tableName === 'posts' ? 'post' : 'video', storedRow)
      }
      rows.push(storedRow)
    }
  }
  return rows
}

async function handleDelete(pool, tableName, auth, query) {
  const identity = await getAuthIdentity(pool, auth)
  if (!identity) throw httpError('AUTH_REQUIRED', 401)
  // Deleting history must still work after opting out, including outgoing views.
  const rows = tableName === 'profile_views'
    ? applyFilters(mergeRows(await Promise.all([
        readImportedRows(pool,tableName),readCompatRows(pool,tableName),
      ]),tableName).filter(row=>identityOwnsRow(identity,row,['profile_id','viewer_id'])).map(row=>{
        const comparison={...row}
        for(const key of ['profile_id','viewer_id']){
          const filter=parseFilterValue(query?.[key])
          if(filter.op==='eq'&&identityOwnsValue(identity,filter.value)&&identityOwnsValue(identity,row[key]))comparison[key]=filter.value
        }
        return {original:row,comparison}
      }).filter(entry=>applyFilters([entry.comparison],query).length).map(entry=>entry.original),{})
    : await readTableRows(pool, tableName, query, auth)
  for (const row of rows) {
    if (!(await authorizeMutation(pool, tableName, identity, row, {}, 'delete'))) {
      throw httpError('ROW_MUTATION_FORBIDDEN')
    }
    const id = rowId(row, tableName)
    if (!id) continue
    if (messageTables.has(tableName)) {
      if (isDeletedForAll(row) && !identityOwnsRow(identity, row, ['sender_id'])) {
        await hideCompatDeletedMessage(pool, tableName, identity, row)
      } else await recallCompatMessage(pool, tableName, identity, auth, row)
      continue
    }
    if (tableName === 'profile_views') {
      // A local tombstone also hides a read-only imported visit after deletion.
      await storeCompatRow(pool,tableName,{...row,is_deleted:true,deleted_at:nowIso()})
      continue
    }
    if (tableName === 'sticker_packs' || tableName === 'sticker_pack_items') {
      // Preserve a local tombstone so deleting an imported catalog row cannot
      // resurrect it on the next imported/local merge or storage read.
      await storeCompatRow(pool, tableName, { ...row, is_deleted: true, deleted_at: new Date().toISOString() })
    } else {
    await pool.query(
      `DELETE FROM fallback_compat_rows
       WHERE table_name = $1 AND row_id = $2`,
      [tableName, id],
    )
    }
    if (tableName === 'posts' || tableName === 'videos') {
      await deleteTrustedContentClassification(pool, tableName === 'posts' ? 'post' : 'video', id)
    }
  }
  return rows
}

async function toggleChallengeVote(pool, auth, submissionId) {
  const cleanSubmissionId = String(submissionId || '').trim()
  const userId = String(auth?.sub || '').trim()
  if (!cleanSubmissionId || !userId) return { ok: false, error: 'INVALID_INPUT' }

  const voteRowId = `${cleanSubmissionId}:${userId}`
  const { rows: existingVotes } = await pool.query(
    `SELECT row_id FROM fallback_compat_rows
     WHERE table_name = 'challenge_votes' AND row_id = $1`,
    [voteRowId],
  )
  const hadVote = existingVotes.length > 0

  const submissions = await readTableRows(
    pool,
    'challenge_submissions',
    { id: `eq.${cleanSubmissionId}` },
    auth,
  )
  const submission = submissions[0]
  if (!submission) return { ok: false, error: 'SUBMISSION_NOT_FOUND' }

  let voteCount = Math.max(0, Number(submission.vote_count ?? submission.voteCount ?? 0) || 0)
  if (hadVote) {
    await pool.query(
      `DELETE FROM fallback_compat_rows
       WHERE table_name = 'challenge_votes' AND row_id = $1`,
      [voteRowId],
    )
    voteCount = Math.max(0, voteCount - 1)
  } else {
    await storeCompatRow(pool, 'challenge_votes', {
      id: voteRowId,
      submission_id: cleanSubmissionId,
      user_id: userId,
      created_at: nowIso(),
    })
    voteCount += 1
  }

  await storeCompatRow(pool, 'challenge_submissions', {
    ...submission,
    id: cleanSubmissionId,
    vote_count: voteCount,
  })
  await publishChallengeLiveEvent({ submissionId: cleanSubmissionId, voteCount })
  return { ok: true, vote_count: voteCount, voted_by_me: !hadVote }
}

async function loadChallengeSubmissionsVoted(pool, auth, activeChallengeId) {
  const cleanActiveId = String(activeChallengeId || '').trim()
  if (!cleanActiveId) return []

  const submissions = await readTableRows(pool, 'challenge_submissions', {
    active_challenge_id: `eq.${cleanActiveId}`,
  }, auth)
  const votes = await readCompatRows(pool, 'challenge_votes')
  const myVotes = new Set(
    votes
      .filter((vote) => String(vote.user_id || vote.userId || '') === String(auth?.sub || ''))
      .map((vote) => String(vote.submission_id || vote.submissionId || '').trim())
      .filter(Boolean),
  )

  const userIds = Array.from(
    new Set(submissions.map((row) => String(row.user_id || row.userId || '').trim()).filter(Boolean)),
  )
  const profiles = await readTableRows(pool, 'profiles', {}, auth)
  const profileById = new Map(
    profiles.map((profile) => [String(profile.id || '').trim(), profile]).filter(([id]) => id),
  )

  return submissions.map((row) => {
    const userId = String(row.user_id || row.userId || '').trim()
    const profile = profileById.get(userId)
    const ownerName =
      profile?.display_name ||
      profile?.displayName ||
      profile?.full_name ||
      profile?.username ||
      'Usuario'
    return {
      ...row,
      owner_name: ownerName,
      voted_by_me: myVotes.has(String(row.id || '').trim()),
      vote_count: Number(row.vote_count ?? row.voteCount ?? 0) || 0,
    }
  })
}

async function requireAdmin(pool, auth) {
  const identity = await getAuthIdentity(pool, auth)
  if (!identity || !(await appAdminHasCapability(pool, identity, ADMIN_CAPABILITY.MODERATION_MANAGE))) return null
  return identity
}

function validUuidOrNull(value) {
  const text = String(value || '').trim().toLowerCase()
  return /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/.test(text)
    ? text
    : null
}

function messageBodyValue(body, name, fallback = null) {
  return body?.[`p_${name}`] ?? body?.[name] ?? body?.[name.replace(/_([a-z])/g, (_, letter) => letter.toUpperCase())] ?? fallback
}

function serializeCompatMessage(row) {
  if (!row) return null
  row = redactDeletedMessage(row)
  return {
    ...row,
    message_type: row.content_type || row.message_type || 'text',
  }
}

async function publishDirectCompatMessage(pool, identity, chatId, message) {
  try {
    const recipients = await pool.query(
      `SELECT DISTINCT participant AS user_id
       FROM fallback_chats, unnest(participants) AS participant
       WHERE lower(id::text) = $1
         AND NOT (lower(participant) = ANY($2::text[]))
       UNION
       SELECT DISTINCT user_id
       FROM fallback_chat_participants
       WHERE lower(chat_id::text) = $1
         AND NOT (lower(user_id) = ANY($2::text[]))`,
      [chatId, identity.values],
    )
    await publishChatMessageEvent({
      pool,
      chatId,
      message: serializeCompatMessage(message),
      recipientIds: recipients.rows.map((row) => row.user_id).filter(Boolean),
    })
    return true
  } catch (error) {
    console.error('[realtime] direct message could not be published', {
      messageRef: stableKey(message?.id),
      ...safeErrorLogDetails(error),
    })
    return false
  }
}

async function publishCollectionCompatMessage(pool, identity, kind, roomId, message) {
  try {
    const recipients = await pool.query(
      `SELECT DISTINCT user_id
       FROM fallback_collection_members
       WHERE room_kind = $1 AND lower(room_id) = $2
         AND NOT (lower(user_id) = ANY($3::text[]))`,
      [kind, roomId, identity.values],
    )
    await publishChatMessageEvent({
      pool,
      chatId: `room:${kind}:${roomId}`,
      message: serializeCompatMessage(message),
      recipientIds: recipients.rows.map((row) => row.user_id).filter(Boolean),
    })
    return true
  } catch (error) {
    console.error('[realtime] collection message could not be published', {
      kind: String(kind || '').slice(0, 32),
      messageRef: stableKey(message?.id),
      ...safeErrorLogDetails(error),
    })
    return false
  }
}

async function insertDirectRpcMessage(pool, auth, body, { publishEvent = true } = {}) {
  const identity = await getAuthIdentity(pool, auth)
  if (!identity) throw httpError('AUTH_REQUIRED', 401)
  const chatId = normalizeIdentityValue(messageBodyValue(body, 'chat_id', ''))
  const messageId = validUuidOrNull(messageBodyValue(body, 'message_id', ''))
  const content = String(messageBodyValue(body, 'content', '') || '').trim().slice(0, 10_000)
  const mediaUrl = String(messageBodyValue(body, 'media_url', '') || '').trim().slice(0, 2000) || null
  const contentType = String(messageBodyValue(body, 'content_type', mediaUrl ? 'media' : 'text') || 'text').trim().slice(0, 40)
  const mediaSize = Math.max(0, Math.min(Number(messageBodyValue(body, 'media_size', 0)) || 0, 512 * 1024 * 1024))
  const replyToId = validUuidOrNull(messageBodyValue(body, 'reply_to_id', ''))
  const pollId = validUuidOrNull(messageBodyValue(body, 'poll_id', ''))
  const isForwarded = Boolean(messageBodyValue(body, 'is_forwarded', false))
  const forwardedFromMessageId = validUuidOrNull(messageBodyValue(body, 'forwarded_from_message_id', ''))
  const forwardedFromUserId = normalizeIdentityValue(messageBodyValue(body, 'forwarded_from_user_id', '')) || null

  if (!chatId || !messageId || (!content && !mediaUrl)) {
    throw httpError('CHAT_ID_MESSAGE_ID_AND_CONTENT_OR_MEDIA_REQUIRED', 400)
  }
  if (!(await canAccessChat(pool, identity, chatId))) throw httpError('CHAT_ACCESS_DENIED')
  if (pollId) {
    const poll = await findCompatEntity(pool, 'message_polls', pollId)
    if (
      !poll ||
      !['chat', 'direct', 'message'].includes(normalizeIdentityValue(poll.scope_type)) ||
      normalizeIdentityValue(poll.scope_id) !== chatId ||
      !(await canAccessPoll(pool, identity, pollId))
    ) {
      throw httpError('POLL_ACCESS_DENIED')
    }
  }

  let rows
  try {
    ({ rows } = await pool.query(
    `INSERT INTO fallback_messages(
       id, chat_id, sender_id, content, content_type, media_url, media_size,
       reply_to_id, poll_id, is_forwarded, forwarded_from_message_id, forwarded_from_user_id
     )
     VALUES ($1::uuid, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12)
     ON CONFLICT (id) DO NOTHING
     RETURNING id::text, chat_id, sender_id, content, content_type, media_url,
               media_size, status, reply_to_id, poll_id, delivered_at, read_at, reactions,
               deleted_by, deleted_for_all, is_edited, is_forwarded,
               forwarded_from_message_id, forwarded_from_user_id, created_at, updated_at`,
    [
      messageId,
      chatId,
      identity.preferredUserId,
      content,
      contentType,
      mediaUrl,
      mediaSize,
      replyToId,
      pollId,
      isForwarded,
      forwardedFromMessageId,
      forwardedFromUserId,
    ],
    ))
  } catch (error) {
    const policyError = introPolicyError(error)
    if (policyError) throw httpError(policyError, 409)
    throw error
  }

  let message = rows[0]
  if (!message) {
    const existing = await pool.query(
      `SELECT id::text, chat_id, sender_id, content, content_type, media_url,
              media_size, status, reply_to_id, poll_id, delivered_at, read_at, reactions,
              deleted_by, deleted_for_all, is_edited, is_forwarded,
              forwarded_from_message_id, forwarded_from_user_id, created_at, updated_at
       FROM fallback_messages WHERE id = $1::uuid LIMIT 1`,
      [messageId],
    )
    message = existing.rows[0]
    if (
      !message ||
      normalizeIdentityValue(message.chat_id) !== chatId ||
      !identityOwnsValue(identity, message.sender_id) ||
      (pollId && normalizeIdentityValue(message.poll_id) !== pollId)
    ) {
      throw httpError('MESSAGE_ID_CONFLICT', 409)
    }
  }

  await refreshChatSummary(pool, chatId)
  if (publishEvent) await publishDirectCompatMessage(pool, identity, chatId, message)
  return serializeCompatMessage(message)
}

async function insertCollectionRpcMessage(pool, auth, body, kind, { publishEvent = true, participantPoll = false } = {}) {
  const identity = await getAuthIdentity(pool, auth)
  if (!identity) throw httpError('AUTH_REQUIRED', 401)
  const roomId = normalizeIdentityValue(messageBodyValue(body, `${kind}_id`, ''))
  const messageId = validUuidOrNull(messageBodyValue(body, 'message_id', ''))
  const content = String(messageBodyValue(body, 'content', '') || '').trim().slice(0, 10_000)
  const mediaUrl = String(messageBodyValue(body, 'media_url', '') || '').trim().slice(0, 2000) || null
  const contentType = String(messageBodyValue(body, 'content_type', mediaUrl ? 'media' : 'text') || 'text').trim().slice(0, 40)
  const mediaSize = Math.max(0, Math.min(Number(messageBodyValue(body, 'media_size', 0)) || 0, 512 * 1024 * 1024))
  const replyToId = validUuidOrNull(messageBodyValue(body, 'reply_to_id', ''))
  const pollId = validUuidOrNull(messageBodyValue(body, 'poll_id', ''))
  const isForwarded = Boolean(messageBodyValue(body, 'is_forwarded', false))
  const forwardedFromMessageId = validUuidOrNull(messageBodyValue(body, 'forwarded_from_message_id', ''))
  const forwardedFromUserId = normalizeIdentityValue(messageBodyValue(body, 'forwarded_from_user_id', '')) || null
  if (!roomId || !messageId || (!content && !mediaUrl)) {
    throw httpError('ROOM_ID_MESSAGE_ID_AND_CONTENT_OR_MEDIA_REQUIRED', 400)
  }
  if (!(await canAccessCollection(pool, kind, identity, roomId))) {
    throw httpError('COLLECTION_ACCESS_DENIED')
  }
  if (pollId) {
    const poll = await findCompatEntity(pool, 'message_polls', pollId)
    if (
      !poll ||
      normalizeIdentityValue(poll.scope_type) !== kind ||
      normalizeIdentityValue(poll.scope_id) !== roomId ||
      !(await canAccessPoll(pool, identity, pollId))
    ) {
      throw httpError('POLL_ACCESS_DENIED')
    }
  }

  const publicationRoom = await findCompatEntity(pool, kind === 'group' ? 'groups' : 'communities', roomId)
  const isPublicationOwner = ['creator_id','owner_id','user_id'].some(key => identity.values.includes(normalizeIdentityValue(publicationRoom?.[key])))
  // Set only by the transactional poll creator. Client descriptors cannot bypass policy.
  const allowedPoll = participantPoll && contentType === 'poll' && Boolean(pollId) && !mediaUrl && !isForwarded
  if (!allowedPoll && !(await require('./community-questions').collectionPublicationAllowed(pool,kind,roomId,isPublicationOwner))) throw httpError('ONLY_CREATOR_CAN_TALK')
  const roomResult = await pool.query(
    `SELECT only_admins_can_talk
     FROM fallback_collection_rooms
     WHERE room_kind = $1 AND lower(id::text) = $2
     LIMIT 1`,
    [kind, roomId],
  )
  if (!allowedPoll && roomResult.rows[0]?.only_admins_can_talk === true &&
      !(await canManageCollection(pool, kind, identity, roomId))) {
    throw httpError('ONLY_ADMINS_CAN_TALK')
  }

  const { rows } = await pool.query(
    `INSERT INTO fallback_room_messages(
       id, room_kind, room_id, sender_id, content, content_type, media_url, media_size,
       reply_to_id, poll_id, is_forwarded, forwarded_from_message_id, forwarded_from_user_id
     )
     VALUES ($1::uuid, $2, $3, $4, $5, $6, $7, $8, $9, $10, $11, $12, $13)
     ON CONFLICT (id) DO NOTHING
     RETURNING id::text, room_id,
               CASE WHEN room_kind = 'group' THEN room_id ELSE NULL END AS group_id,
               CASE WHEN room_kind = 'community' THEN room_id ELSE NULL END AS community_id,
                sender_id, content, content_type, media_url, media_size, status,
                delivered_at, read_at, reactions,
               reply_to_id, poll_id, is_edited, deleted_for_all, deleted_by,
               is_forwarded, forwarded_from_message_id, forwarded_from_user_id,
               created_at, updated_at`,
    [
      messageId,
      kind,
      roomId,
      identity.preferredUserId,
      content,
      contentType,
      mediaUrl,
      mediaSize,
      replyToId,
      pollId,
      isForwarded,
      forwardedFromMessageId,
      forwardedFromUserId,
    ],
  )
  let message = rows[0]
  if (!message) {
    const existing = await pool.query(
      `SELECT id::text, room_id,
              CASE WHEN room_kind = 'group' THEN room_id ELSE NULL END AS group_id,
              CASE WHEN room_kind = 'community' THEN room_id ELSE NULL END AS community_id,
              sender_id, content, content_type, media_url, media_size, status,
              delivered_at, read_at, reactions,
              reply_to_id, poll_id, is_edited, deleted_for_all, deleted_by,
              is_forwarded, forwarded_from_message_id, forwarded_from_user_id,
              created_at, updated_at
       FROM fallback_room_messages WHERE id = $1::uuid LIMIT 1`,
      [messageId],
    )
    message = existing.rows[0]
    if (
      !message ||
      normalizeIdentityValue(message.room_id) !== roomId ||
      !identityOwnsValue(identity, message.sender_id) ||
      (pollId && normalizeIdentityValue(message.poll_id) !== pollId)
    ) {
      throw httpError('MESSAGE_ID_CONFLICT', 409)
    }
  }

  if (publishEvent) await publishCollectionCompatMessage(pool, identity, kind, roomId, message)
  return serializeCompatMessage(message)
}

function parsePollOptions(rawOptions) {
  let values = rawOptions
  if (!Array.isArray(values) && typeof values === 'string') {
    const raw = values.trim()
    if (raw.startsWith('[')) {
      try {
        values = JSON.parse(raw)
      } catch {
        values = []
      }
    } else if (raw.startsWith('{') && raw.endsWith('}')) {
      const parsed = []
      let current = ''
      let quoted = false
      let escaped = false
      for (const character of raw.slice(1, -1)) {
        if (escaped) {
          current += character
          escaped = false
        } else if (character === '\\') {
          escaped = true
        } else if (character === '"') {
          quoted = !quoted
        } else if (character === ',' && !quoted) {
          parsed.push(current)
          current = ''
        } else {
          current += character
        }
      }
      parsed.push(current)
      values = parsed
    }
  }
  if (!Array.isArray(values)) return []

  const seen = new Set()
  const result = []
  for (const entry of values) {
    const text = String(entry?.option_text ?? entry?.text ?? entry ?? '').trim().slice(0, 120)
    const key = text.toLocaleLowerCase('es')
    if (!text || seen.has(key)) continue
    seen.add(key)
    result.push(text)
    if (result.length === 20) break
  }
  return result
}

function pollMessageMatches(identity, message, kind, scopeId, question) {
  const messageScope = kind === 'chat'
    ? normalizeIdentityValue(message.chat_id)
    : normalizeIdentityValue(message.room_id)
  return messageScope === scopeId &&
    (kind === 'chat' || normalizeIdentityValue(message.room_kind) === kind) &&
    identityOwnsValue(identity, message.sender_id) &&
    normalizeIdentityValue(message.content_type) === 'poll' &&
    String(message.content || '').trim() === question &&
    Boolean(validUuidOrNull(message.poll_id))
}

async function createPollMessageRpc(pool, auth, body, kind) {
  const identity = await getAuthIdentity(pool, auth)
  if (!identity) throw httpError('AUTH_REQUIRED', 401)
  const scopeKey = kind === 'chat' ? 'chat_id' : `${kind}_id`
  const scopeId = normalizeIdentityValue(messageBodyValue(body, scopeKey, ''))
  const messageId = validUuidOrNull(messageBodyValue(body, 'message_id', ''))
  const question = String(messageBodyValue(body, 'question', '') || '').trim().slice(0, 300)
  const options = parsePollOptions(messageBodyValue(body, 'options', []))
  const allowsMultipleRaw = messageBodyValue(body, 'allows_multiple', false)
  const allowsMultiple = allowsMultipleRaw === true || allowsMultipleRaw === 1 || allowsMultipleRaw === '1' || allowsMultipleRaw === 'true'
  if (!scopeId || !messageId || !question || options.length < 2) {
    throw httpError('POLL_SCOPE_MESSAGE_QUESTION_AND_OPTIONS_REQUIRED', 400)
  }
  const hasAccess = kind === 'chat'
    ? await canAccessChat(pool, identity, scopeId)
    : await canAccessCollection(pool, kind, identity, scopeId)
  if (!hasAccess) {
    throw httpError(kind === 'chat' ? 'CHAT_ACCESS_DENIED' : 'COLLECTION_ACCESS_DENIED')
  }

  const client = typeof pool.connect === 'function' ? await pool.connect() : pool
  const releaseClient = client !== pool && typeof client.release === 'function'
  let inTransaction = false
  let message = null
  let created = false
  try {
    await client.query('BEGIN')
    inTransaction = true
    const existingResult = kind === 'chat'
      ? await client.query(
          `SELECT id::text, chat_id, sender_id, content, content_type, media_url, media_size,
                  status, reply_to_id, poll_id, delivered_at, read_at, reactions,
                  deleted_by, deleted_for_all, is_edited, is_forwarded,
                  forwarded_from_message_id, forwarded_from_user_id, created_at, updated_at
           FROM fallback_messages WHERE id = $1::uuid FOR UPDATE`,
          [messageId],
        )
      : await client.query(
          `SELECT id::text, room_kind, room_id,
                  CASE WHEN room_kind = 'group' THEN room_id ELSE NULL END AS group_id,
                  CASE WHEN room_kind = 'community' THEN room_id ELSE NULL END AS community_id,
                  sender_id, content, content_type, media_url, media_size, status,
                  delivered_at, read_at, reactions, reply_to_id, poll_id, is_edited,
                  deleted_for_all, deleted_by, is_forwarded, forwarded_from_message_id,
                  forwarded_from_user_id, created_at, updated_at
           FROM fallback_room_messages WHERE id = $1::uuid FOR UPDATE`,
          [messageId],
        )

    if (existingResult.rows[0]) {
      message = existingResult.rows[0]
      if (!pollMessageMatches(identity, message, kind, scopeId, question)) {
        throw httpError('MESSAGE_ID_CONFLICT', 409)
      }
    } else {
      const pollId = crypto.randomUUID()
      await storeCompatRow(client, 'message_polls', {
        id: pollId,
        scope_type: kind,
        scope_id: scopeId,
        creator_id: identity.preferredUserId,
        question,
        allows_multiple: allowsMultiple,
        is_closed: false,
      })
      for (const [index, optionText] of options.entries()) {
        await storeCompatRow(client, 'message_poll_options', {
          id: crypto.randomUUID(),
          poll_id: pollId,
          option_text: optionText,
          display_order: index,
        })
      }
      const messageBody = {
        [`p_${scopeKey}`]: scopeId,
        p_message_id: messageId,
        p_content: question,
        p_content_type: 'poll',
        p_poll_id: pollId,
      }
      message = kind === 'chat'
        ? await insertDirectRpcMessage(client, auth, messageBody, { publishEvent: false })
        : await insertCollectionRpcMessage(client, auth, messageBody, kind, { publishEvent: false, participantPoll: true })
      created = true
    }
    await client.query('COMMIT')
    inTransaction = false
  } catch (error) {
    if (inTransaction) await client.query('ROLLBACK').catch(() => {})
    throw error
  } finally {
    if (releaseClient) client.release()
  }

  if (created) {
    if (kind === 'chat') {
      await publishDirectCompatMessage(pool, identity, scopeId, message)
    } else {
      await publishCollectionCompatMessage(pool, identity, kind, scopeId, message)
    }
  }
  return serializeCompatMessage(message)
}

async function markDirectRpcMessages(pool, auth, body, state) {
  const identity = await getAuthIdentity(pool, auth)
  if (!identity) throw httpError('AUTH_REQUIRED', 401)
  const chatId = normalizeIdentityValue(messageBodyValue(body, 'chat_id', ''))
  const messageId = validUuidOrNull(messageBodyValue(body, 'message_id', ''))
  if (!chatId) throw httpError('CHAT_ID_REQUIRED', 400)
  if (!(await canAccessChat(pool, identity, chatId))) throw httpError('CHAT_ACCESS_DENIED')

  const read = state === 'read'
  const { rowCount } = await pool.query(
    `UPDATE fallback_messages
     SET status = CASE WHEN $4::boolean THEN 'read'
                       WHEN status = 'sent' THEN 'delivered'
                       ELSE status END,
         delivered_at = COALESCE(delivered_at, NOW()),
         read_at = CASE WHEN $4::boolean THEN COALESCE(read_at, NOW()) ELSE read_at END,
         updated_at = NOW()
     WHERE lower(chat_id) = $1
       AND NOT (lower(sender_id) = ANY($2::text[]))
       AND ($3::uuid IS NULL OR id = $3::uuid)`,
    [chatId, identity.values, messageId, read],
  )

  const uuidChatId = validUuidOrNull(chatId)
  if (read && uuidChatId) {
    await pool.query(
      `INSERT INTO fallback_chat_participants(chat_id, user_id, joined_at, last_read_at, created_at, updated_at)
       VALUES ($1::uuid, $2, NOW(), NOW(), NOW(), NOW())
       ON CONFLICT (chat_id, user_id) DO UPDATE SET
         last_read_at = GREATEST(
           COALESCE(fallback_chat_participants.last_read_at, 'epoch'::timestamptz),
           EXCLUDED.last_read_at
         ),
         updated_at = NOW()`,
      [uuidChatId, identity.preferredUserId],
    )
  }
  return { ok: true, chat_id: chatId, updated_count: rowCount || 0 }
}

async function markCollectionRpcMessages(pool, auth, body, kind, state) {
  const identity = await getAuthIdentity(pool, auth)
  if (!identity) throw httpError('AUTH_REQUIRED', 401)
  const roomId = normalizeIdentityValue(
    messageBodyValue(body, `${kind}_id`, messageBodyValue(body, 'room_id', '')),
  )
  const messageId = validUuidOrNull(messageBodyValue(body, 'message_id', ''))
  if (!roomId) throw httpError('ROOM_ID_REQUIRED', 400)
  if (!(await canAccessCollection(pool, kind, identity, roomId))) {
    throw httpError('COLLECTION_ACCESS_DENIED')
  }

  const read = state === 'read'
  const { rowCount } = await pool.query(
    `UPDATE fallback_room_messages
     SET status = CASE WHEN $5::boolean THEN 'read'
                       WHEN status = 'sent' THEN 'delivered'
                       ELSE status END,
         delivered_at = COALESCE(delivered_at, NOW()),
         read_at = CASE WHEN $5::boolean THEN COALESCE(read_at, NOW()) ELSE read_at END,
         updated_at = NOW()
     WHERE room_kind = $1 AND lower(room_id) = $2
       AND NOT (lower(sender_id) = ANY($3::text[]))
       AND ($4::uuid IS NULL OR id = $4::uuid)`,
    [kind, roomId, identity.values, messageId, read],
  )

  await pool.query(
    `INSERT INTO fallback_collection_message_receipts(
       room_kind, room_id, message_id, user_id, delivered_at, read_at, updated_at
     )
     SELECT room_kind, room_id, id, $4, NOW(), CASE WHEN $6::boolean THEN NOW() ELSE NULL END, NOW()
     FROM fallback_room_messages
     WHERE room_kind = $1 AND lower(room_id) = $2
       AND NOT (lower(sender_id) = ANY($3::text[]))
       AND ($5::uuid IS NULL OR id = $5::uuid)
     ON CONFLICT (room_kind, message_id, user_id) DO UPDATE SET
       delivered_at = COALESCE(fallback_collection_message_receipts.delivered_at, EXCLUDED.delivered_at),
       read_at = CASE WHEN $6::boolean
                      THEN COALESCE(fallback_collection_message_receipts.read_at, EXCLUDED.read_at)
                      ELSE fallback_collection_message_receipts.read_at END,
       updated_at = NOW()`,
    [kind, roomId, identity.values, identity.preferredUserId, messageId, read],
  )

  if (read) {
    await pool.query(
      `INSERT INTO fallback_collection_read_states(room_kind, room_id, user_id, last_read_at, updated_at)
       VALUES ($1, $2, $3, NOW(), NOW())
       ON CONFLICT (room_kind, room_id, user_id) DO UPDATE SET
         last_read_at = GREATEST(fallback_collection_read_states.last_read_at, EXCLUDED.last_read_at),
         updated_at = NOW()`,
      [kind, roomId, identity.preferredUserId],
    )
  }
  return { ok: true, room_id: roomId, updated_count: rowCount || 0 }
}

async function collectionMessageReceipts(pool, auth, body, kind) {
  const identity = await getAuthIdentity(pool, auth)
  if (!identity) throw httpError('AUTH_REQUIRED', 401)
  const messageId = validUuidOrNull(messageBodyValue(body, 'message_id', ''))
  if (!messageId) throw httpError('MESSAGE_ID_REQUIRED', 400)
  const messageResult = await pool.query(
    `SELECT room_id FROM fallback_room_messages WHERE room_kind = $1 AND id = $2::uuid LIMIT 1`,
    [kind, messageId],
  )
  const roomId = normalizeIdentityValue(messageResult.rows[0]?.room_id)
  if (!roomId) throw httpError('MESSAGE_NOT_FOUND', 404)
  if (!(await canAccessCollection(pool, kind, identity, roomId))) {
    throw httpError('COLLECTION_ACCESS_DENIED')
  }
  const { rows } = await pool.query(
    `SELECT r.user_id,
            COALESCE(u.display_name, u.email) AS username,
            COALESCE(u.display_name, u.email) AS full_name,
            u.avatar_url,
            r.delivered_at,
            r.read_at
     FROM fallback_collection_message_receipts r
     LEFT JOIN fallback_users u
       ON lower(u.id::text) = lower(r.user_id)
       OR lower(COALESCE(u.supabase_user_id::text, '')) = lower(r.user_id)
     WHERE r.room_kind = $1 AND r.message_id = $2::uuid
     ORDER BY COALESCE(r.read_at, r.delivered_at) DESC NULLS LAST`,
    [kind, messageId],
  )
  return rows
}

function toggleReactionForIdentity(current, emoji, identity) {
  const next = {}
  let removedSameReaction = false
  for (const [key, rawUsers] of Object.entries(current || {})) {
    const users = Array.isArray(rawUsers) ? rawUsers : []
    const withoutActor = users.filter((userId) => !identityOwnsValue(identity, userId))
    if (key === emoji && withoutActor.length !== users.length) removedSameReaction = true
    if (withoutActor.length > 0) next[key] = Array.from(new Set(withoutActor))
  }
  if (!removedSameReaction) {
    next[emoji] = Array.from(new Set([...(next[emoji] || []), identity.preferredUserId]))
  }
  return next
}

async function toggleMessageReaction(pool, auth, body, collectionKind = null) {
  const identity = await getAuthIdentity(pool, auth)
  if (!identity) throw httpError('AUTH_REQUIRED', 401)
  const messageId = validUuidOrNull(messageBodyValue(body, 'message_id', ''))
  const emoji = String(messageBodyValue(body, 'emoji', '') || '').trim().slice(0, 16)
  if (!messageId || !emoji) throw httpError('MESSAGE_ID_AND_REACTION_REQUIRED', 400)

  const roomResult = await pool.query(
    `SELECT room_kind, room_id, reactions, created_at, content_type FROM fallback_room_messages WHERE id = $1::uuid LIMIT 1`,
    [messageId],
  )
  if (roomResult.rows[0]) {
    const message = roomResult.rows[0]
    if (!(await canAccessCollection(pool, message.room_kind, identity, message.room_id))) {
      throw httpError('COLLECTION_ACCESS_DENIED')
    }
    const state = await loadCollectionMembershipState(pool, message.room_kind, message.room_id, identity.values)
    if (!collectionHistoryAllows(state.history,message) || message.content_type === 'system_membership') throw httpError('MESSAGE_NOT_FOUND',404)
    const reactions = toggleReactionForIdentity(message.reactions, emoji, identity)
    await pool.query(
      `UPDATE fallback_room_messages SET reactions = $2::jsonb, updated_at = NOW() WHERE id = $1::uuid`,
      [messageId, reactions],
    )
    return { message_id: messageId, reactions }
  }

  if (collectionKind === 'group' || collectionKind === 'community') {
    const tableName = collectionKind === 'group' ? 'group_messages' : 'community_messages'
    const importedMessage = (await importedAndCompatRows(pool, tableName))
      .find((row) => normalizeIdentityValue(row.id) === messageId)
    if (importedMessage) {
      const roomId = normalizeIdentityValue(
        importedMessage[`${collectionKind}_id`] || importedMessage.room_id
      )
      if (!(await canAccessCollection(pool, collectionKind, identity, roomId))) {
        throw httpError('COLLECTION_ACCESS_DENIED')
      }
      const state = await loadCollectionMembershipState(pool, collectionKind, roomId, identity.values)
      if (!collectionHistoryAllows(state.history,importedMessage) || importedMessage.content_type === 'system_membership') throw httpError('MESSAGE_NOT_FOUND',404)
      const reactions = toggleReactionForIdentity(importedMessage.reactions, emoji, identity)
      await storeCompatRow(pool, tableName, {
        ...importedMessage,
        id: messageId,
        reactions,
      })
      return { message_id: messageId, reactions }
    }
  }

  const directResult = await pool.query(
    `SELECT chat_id, reactions FROM fallback_messages WHERE id = $1::uuid LIMIT 1`,
    [messageId],
  )
  if (!directResult.rows[0]) throw httpError('MESSAGE_NOT_FOUND', 404)
  if (!(await canAccessChat(pool, identity, directResult.rows[0].chat_id))) {
    throw httpError('CHAT_ACCESS_DENIED')
  }
  const reactions = toggleReactionForIdentity(directResult.rows[0].reactions, emoji, identity)
  await pool.query(
    `UPDATE fallback_messages SET reactions = $2::jsonb, updated_at = NOW() WHERE id = $1::uuid`,
    [messageId, reactions],
  )
  return { message_id: messageId, reactions }
}

async function buildPollState(pool, identity, pollId) {
  const cleanPollId = normalizeIdentityValue(pollId)
  if (!cleanPollId || !(await canAccessPoll(pool, identity, cleanPollId))) {
    throw httpError('POLL_ACCESS_DENIED')
  }
  const poll = await findCompatEntity(pool, 'message_polls', cleanPollId)
  if (!poll) throw httpError('POLL_NOT_FOUND', 404)
  const [options, votes] = await Promise.all([
    importedAndCompatRows(pool, 'message_poll_options'),
    importedAndCompatRows(pool, 'message_poll_votes'),
  ])
  const pollOptions = options
    .filter((row) => normalizeIdentityValue(row.poll_id) === cleanPollId)
    .sort((a, b) => Number(a.display_order || 0) - Number(b.display_order || 0))
  const pollVotes = votes.filter((row) => normalizeIdentityValue(row.poll_id) === cleanPollId)
  const totalVotes = pollVotes.length
  return pollOptions.map((option, index) => {
    const optionId = normalizeIdentityValue(option.id)
    const optionVotes = pollVotes.filter((vote) => normalizeIdentityValue(vote.option_id) === optionId)
    return {
      poll_id: cleanPollId,
      option_id: optionId,
      option_text: option.option_text || '',
      display_order: Number(option.display_order ?? index) || 0,
      vote_count: optionVotes.length,
      voted_by_me: optionVotes.some((vote) => identityOwnsRow(identity, vote, ['user_id'])),
      total_votes: totalVotes,
      allows_multiple: poll.allows_multiple === true,
      is_closed: poll.is_closed === true,
      question: poll.question || '',
    }
  })
}

async function votePollOption(pool, auth, body) {
  const identity = await getAuthIdentity(pool, auth)
  if (!identity) throw httpError('AUTH_REQUIRED', 401)
  const pollId = normalizeIdentityValue(messageBodyValue(body, 'poll_id', ''))
  const optionId = normalizeIdentityValue(messageBodyValue(body, 'option_id', ''))
  const selected = messageBodyValue(body, 'selected', true) !== false
  if (!pollId || !optionId) {
    throw httpError('POLL_ACCESS_DENIED')
  }
  const client = typeof pool.connect === 'function' ? await pool.connect() : pool
  const releaseClient = client !== pool && typeof client.release === 'function'
  let inTransaction = false
  try {
    await client.query('BEGIN')
    inTransaction = true
    // One account may vote from several devices at once. Serialize its changes
    // across workers before reading policy or replacing a single-choice vote.
    await client.query('SELECT pg_advisory_xact_lock(hashtextextended($1, 0))', [
      `poll-vote:${pollId}:${normalizeIdentityValue(identity.preferredUserId)}`,
    ])
    if (!(await canAccessPoll(client, identity, pollId))) throw httpError('POLL_ACCESS_DENIED')
    const poll = await findCompatEntity(client, 'message_polls', pollId)
    if (!poll) throw httpError('POLL_NOT_FOUND', 404)
    if (poll.is_closed === true) throw httpError('POLL_CLOSED', 409)
    const option = (await importedAndCompatRows(client, 'message_poll_options'))
      .find((row) => normalizeIdentityValue(row.id) === optionId)
    if (!option || normalizeIdentityValue(option.poll_id) !== pollId) {
      throw httpError('POLL_OPTION_NOT_FOUND', 404)
    }

    // Replace all aliases of this account's selected vote, including imported
    // votes. Otherwise old votes can reappear after a change or removal.
    const replaceAll = selected && poll.allows_multiple !== true
    const parameters = [pollId, identity.values, replaceAll, optionId]
    await client.query(
      `DELETE FROM fallback_compat_rows
       WHERE table_name = 'message_poll_votes'
         AND lower(row->>'poll_id') = $1
         AND lower(row->>'user_id') = ANY($2::text[])
         AND ($3::boolean OR lower(row->>'option_id') = $4)`,
      parameters,
    )
    if (await tableExists(client, 'supabase_import', 'message_poll_votes')) {
      await deleteImportedPollVotes(client, parameters)
    }
    if (selected) {
      await storeCompatRow(client, 'message_poll_votes', {
        poll_id: pollId,
        option_id: optionId,
        user_id: identity.preferredUserId,
      })
    }
    const state = await buildPollState(client, identity, pollId)
    await client.query('COMMIT')
    inTransaction = false
    void publishPollUpdate(pool, poll)
    return state
  } catch (error) {
    if (inTransaction) await client.query('ROLLBACK').catch(() => {})
    throw error
  } finally {
    if (releaseClient) client.release()
  }
}

function strictBoolean(value, fieldName) {
  if (value === true || value === 'true' || value === 1 || value === '1') return true
  if (value === false || value === 'false' || value === 0 || value === '0') return false
  throw httpError(`INVALID_BOOLEAN_${fieldName}`, 400)
}

async function setChatTranslationRpc(pool, auth, body) {
  const identity = await getAuthIdentity(pool, auth)
  if (!identity) throw httpError('AUTH_REQUIRED', 401)
  const chatId = normalizeIdentityValue(messageBodyValue(body, 'chat_id', ''))
  if (!chatId) throw httpError('CHAT_ID_REQUIRED', 400)
  if (!(await canAccessChat(pool, identity, chatId))) throw httpError('CHAT_ACCESS_DENIED')
  const enabled = strictBoolean(messageBodyValue(body, 'enabled', false), 'ENABLED')
  const rawLanguage = String(messageBodyValue(body, 'target_lang', '') || '').trim().toLowerCase()
  const targetLang = rawLanguage || null
  if (targetLang && !/^[a-z]{2,3}(?:-[a-z]{2})?$/.test(targetLang)) {
    throw httpError('INVALID_TARGET_LANGUAGE', 400)
  }
  if (enabled && !targetLang) throw httpError('TARGET_LANGUAGE_REQUIRED', 400)
  const setting = await storeCompatRow(pool, 'chat_translation_settings', {
    chat_id: chatId,
    user_id: identity.preferredUserId,
    enabled,
    target_lang: targetLang,
  })
  return [setting]
}

async function markNotificationsReadRpc(pool, auth, body) {
  const identity = await getAuthIdentity(pool, auth)
  if (!identity) throw httpError('AUTH_REQUIRED', 401)
  const markAll = strictBoolean(messageBodyValue(body, 'mark_all', false), 'MARK_ALL')
  const notificationIds = Array.from(new Set(
    (Array.isArray(messageBodyValue(body, 'notification_ids', []))
      ? messageBodyValue(body, 'notification_ids', [])
      : [])
      .map(validUuidOrNull)
      .filter(Boolean),
  ))
  if (!markAll && notificationIds.length === 0) {
    throw httpError('NOTIFICATION_IDS_REQUIRED', 400)
  }

  const updated = await pool.query(
    `UPDATE fallback_notifications
     SET is_read = TRUE, updated_at = NOW()
     WHERE lower(recipient_id) = ANY($1::text[])
       AND is_read = FALSE
       AND ($2::boolean OR id = ANY($3::uuid[]))`,
    [identity.values, markAll, notificationIds],
  )

  const compatRows = await readCompatRows(pool, 'notifications')
  let compatUpdated = 0
  for (const row of compatRows) {
    const rowIdValue = validUuidOrNull(row.id)
    const belongsToUser = identityOwnsRow(identity, row, ['recipient_id', 'user_id'])
    if (!belongsToUser || row.is_read === true || (!markAll && !notificationIds.includes(rowIdValue))) continue
    await storeCompatRow(pool, 'notifications', { ...row, is_read: true, read_at: nowIso() })
    compatUpdated += 1
  }
  return { ok: true, updated_count: (updated.rowCount || 0) + compatUpdated }
}

async function consumeViewOnceRpc(pool, auth, body) {
  const rawScope=normalizeIdentityValue(messageBodyValue(body,'scope_type',''))
  const scopeType=['dm','direct','message'].includes(rawScope)?'chat':rawScope
  const messageId=validUuidOrNull(messageBodyValue(body,'message_id',''))
  const configuration=require('./storage-read-config').storageReadConfiguration()
  if (!configuration.enforced) throw httpError('VIEW_ONCE_STORAGE_ENFORCEMENT_REQUIRED',503)
  return require('./storage-view-once').claimViewOnceLease({
    pool,auth,scopeType,messageId,
    deviceId:messageBodyValue(body,'device_id',''),origins:configuration.origins,
    maxFileBytes:Number(messageBodyValue(body,'max_file_bytes',require('./storage-view-once').MAX_FILE_BYTES)),
  })
}

async function registerMediaObjectRpc(pool, auth, body) {
  const identity = await getAuthIdentity(pool, auth)
  if (!identity) throw httpError('AUTH_REQUIRED', 401)
  const bucket = String(messageBodyValue(body, 'bucket_name', '') || '').trim()
  const objectPath = String(messageBodyValue(body, 'object_path', '') || '').trim().replace(/\\/g, '/')
  const contentType = String(messageBodyValue(body, 'content_type', '') || '').trim().toLowerCase()
  const sizeBytes = Number(messageBodyValue(body, 'size_bytes', -1))
  const sha256 = String(messageBodyValue(body, 'sha256', '') || '').trim().toLowerCase()
  if (!bucket || !objectPath || objectPath.split('/').some((part) => !part || part === '.' || part === '..')) {
    throw httpError('UNSAFE_MEDIA_PATH', 400)
  }
  if (!Number.isSafeInteger(sizeBytes) || sizeBytes <= 0 || !/^[a-f0-9]{64}$/.test(sha256)) {
    throw httpError('INVALID_MEDIA_METADATA', 400)
  }
  const { rows } = await pool.query(
    `UPDATE fallback_storage_objects
     SET metadata = metadata || jsonb_build_object('sha256', $4::text, 'registered_at', NOW()::text),
         updated_at = NOW()
     WHERE bucket_id = $1 AND name = $2
       AND owner_id = $3::uuid
       AND size_bytes = $5
       AND lower(content_type) = $6
     RETURNING id::text, bucket_id, name, content_type, size_bytes, public_url, updated_at`,
    [bucket, objectPath, identity.localUserId, sha256, sizeBytes, contentType],
  )
  if (!rows[0]) throw httpError('MEDIA_OBJECT_NOT_FOUND_OR_MISMATCH', 404)
  return { ok: true, object: rows[0] }
}

async function updateVideoCounterRpc(pool, auth, body, counter) {
  const identity = await getAuthIdentity(pool, auth)
  if (!identity) throw httpError('AUTH_REQUIRED', 401)
  const videoId = validUuidOrNull(messageBodyValue(body, 'video_id', ''))
  if (!videoId) throw httpError('VIDEO_ID_REQUIRED', 400)
  const video = await pool.query(
    `SELECT id::text, user_id, is_public FROM fallback_videos WHERE id = $1::uuid LIMIT 1`,
    [videoId],
  )
  const compatVideo = video.rows[0] || (await findCompatEntity(pool, 'videos', videoId))
  if (!compatVideo || (compatVideo.is_public === false && !identityOwnsValue(identity, compatVideo.user_id))) {
    throw httpError('VIDEO_NOT_FOUND', 404)
  }

  const column = counter === 'views' ? 'views' : counter === 'like_count' ? 'like_count' : 'share_count'
  const requestedDelta = counter === 'views' ? 1 : Number(messageBodyValue(body, 'delta', 1))
  const delta = Math.max(-1, Math.min(1, Number.isFinite(requestedDelta) ? Math.trunc(requestedDelta) : 1))
  const result = await pool.query(
    `UPDATE fallback_videos
     SET ${column} = GREATEST(0, ${column} + $2), updated_at = NOW()
     WHERE id = $1::uuid
     RETURNING ${column} AS value`,
    [videoId, delta],
  )
  if (result.rows[0]) return { ok: true, [column]: Number(result.rows[0].value) || 0 }

  const current = Math.max(0, Number(compatVideo[column] || 0) + delta)
  await storeCompatRow(pool, 'videos', { ...compatVideo, id: videoId, [column]: current })
  return { ok: true, [column]: current }
}

async function refreshProfileContentCountRpc(pool, auth) {
  const identity = await getAuthIdentity(pool, auth)
  if (!identity) throw httpError('AUTH_REQUIRED', 401)
  const [posts, videos, profiles] = await Promise.all([
    readTableRows(pool, 'posts', {}, auth),
    readTableRows(pool, 'videos', {}, auth),
    readTableRows(pool, 'profiles', {}, auth),
  ])
  const postCount = posts.filter((row) => identityOwnsRow(identity, row, ['user_id'])).length +
    videos.filter((row) => identityOwnsRow(identity, row, ['user_id'])).length
  const profile = profiles.find((row) => identityOwnsRow(identity, row, ['id', 'local_id', 'user_id'])) || {
    id: identity.preferredUserId,
  }
  await storeCompatRow(pool, 'profiles', { ...profile, id: identity.preferredUserId, post_count: postCount })
  return { ok: true, post_count: postCount }
}

function publicStorageLocation(rawUrl) {
  if (scanEnforcementEnabled() && !trustedStorageUrl(rawUrl)) return null
  try {
    const pathname = new URL(String(rawUrl || '')).pathname
    const marker = '/storage/v1/object/public/'
    const markerIndex = pathname.indexOf(marker)
    if (markerIndex < 0) return null
    const parts = pathname.slice(markerIndex + marker.length).split('/').map(decodeURIComponent)
    const bucket = parts.shift() || ''
    const objectPath = parts.join('/')
    return bucket && objectPath ? { bucket, objectPath } : null
  } catch {
    return null
  }
}

async function canUseReportEvidence(pool, identity, rawUrl) {
  const cleanUrl = String(rawUrl || '').trim()
  if (!cleanUrl) return true
  const location = publicStorageLocation(cleanUrl)
  if (!location || !['report-evidence', 'avatars'].includes(location.bucket)) return false
  const { rows } = await pool.query(
    `SELECT 1 FROM fallback_storage_objects
     WHERE bucket_id = $1 AND name = $2 AND owner_id = $3::uuid
     ${scanReadySql}
      LIMIT 1`,
    [location.bucket, location.objectPath, identity.localUserId],
  )
  return Boolean(rows[0])
}

async function canReuseCatalogStickerMedia(pool, identity, mediaUrl, stickerId = null) {
  const [items, packs] = await Promise.all([
    importedAndCompatRows(pool, 'sticker_pack_items'),
    importedAndCompatRows(pool, 'sticker_packs'),
  ])
  return catalogAllowsStickerMedia({ items, packs, viewerIds: identity?.ids, mediaUrl, stickerId })
}

async function canUseStickerMedia(pool, identity, stickerId, mediaUrl) {
  const cleanUrl = String(mediaUrl || '').trim()
  if (!cleanUrl) return false
  if (scanEnforcementEnabled()) {
    const location = publicStorageLocation(cleanUrl)
    if (!location) return false
    const scanned = await pool.query(`SELECT 1 FROM fallback_storage_objects WHERE bucket_id=$1 AND name=$2 ${scanReadySql} LIMIT 1`, [location.bucket,location.objectPath])
    if (!scanned.rows[0]) return false
  }
  if (await canReuseCatalogStickerMedia(pool, identity, cleanUrl, stickerId)) return true

  const location = publicStorageLocation(cleanUrl)
  if (!location) return false
  const { rows } = await pool.query(
    `SELECT 1 FROM fallback_storage_objects
     WHERE bucket_id = $1 AND name = $2 AND owner_id = $3::uuid
     ${scanReadySql}
      LIMIT 1`,
    [location.bucket, location.objectPath, identity.localUserId],
  )
  return Boolean(rows[0])
}

async function createStickerPackRpc(pool, auth, body) {
  const identity = await getAuthIdentity(pool, auth)
  if (!identity) throw httpError('AUTH_REQUIRED', 401)
  const name = String(messageBodyValue(body, 'name', '') || '').trim().slice(0, 80)
  const description = String(messageBodyValue(body, 'description', '') || '').trim().slice(0, 500) || null
  const isPublic = strictBoolean(messageBodyValue(body, 'is_public', false), 'IS_PUBLIC')
  if (!name) throw httpError('STICKER_PACK_NAME_REQUIRED', 400)
  return storeCompatRow(pool, 'sticker_packs', {
    id: crypto.randomUUID(),
    owner_id: identity.preferredUserId,
    name,
    description,
    cover_sticker_id: null,
    is_public: isPublic,
  })
}

async function addStickerToPackRpc(pool, auth, body) {
  const identity = await getAuthIdentity(pool, auth)
  if (!identity) throw httpError('AUTH_REQUIRED', 401)
  const packId = normalizeIdentityValue(messageBodyValue(body, 'pack_id', ''))
  const stickerId = String(messageBodyValue(body, 'sticker_id', '') || '').trim().slice(0, 160)
  const mediaUrl = String(messageBodyValue(body, 'media_url', '') || '').trim().slice(0, 2000)
  const title = String(messageBodyValue(body, 'label', 'Sticker') || 'Sticker').trim().slice(0, 80) || 'Sticker'
  const isAnimated = strictBoolean(messageBodyValue(body, 'is_animated', false), 'IS_ANIMATED')
  if (!packId || !stickerId || !mediaUrl) throw httpError('STICKER_FIELDS_REQUIRED', 400)
  if (!(await canManageStickerPack(pool, identity, packId))) throw httpError('STICKER_PACK_OWNER_REQUIRED')
  if (!(await canUseStickerMedia(pool, identity, stickerId, mediaUrl))) {
    throw httpError('STICKER_MEDIA_NOT_OWNED')
  }
  const pack = await findCompatEntity(pool, 'sticker_packs', packId)
  return storeCompatRow(pool, 'sticker_pack_items', {
    id: stickerId,
    sticker_id: stickerId,
    pack_id: packId,
    title,
    label: title,
    media_url: mediaUrl,
    thumbnail_url: mediaUrl,
    is_animated: isAnimated,
    tags: [],
    source: 'custom',
    pack_name: pack?.name || null,
  })
}

function desiredStickerFavoriteState(existing, requestedFavorite) {
  return typeof requestedFavorite === 'boolean' ? requestedFavorite : !existing
}

async function toggleStickerFavoriteRpc(pool, auth, body) {
  const identity = await getAuthIdentity(pool, auth)
  if (!identity) throw httpError('AUTH_REQUIRED', 401)
  const stickerId = String(messageBodyValue(body, 'sticker_id', '') || '').trim().slice(0, 160)
  if (!stickerId) throw httpError('STICKER_ID_REQUIRED', 400)
  const compatFavorites = await readCompatRows(pool, 'user_sticker_favorites')
  const existing = compatFavorites.find((row) =>
    identityOwnsRow(identity, row, ['user_id']) &&
    normalizeIdentityValue(row.sticker_id || row.item_id) === normalizeIdentityValue(stickerId),
  )
  const requestedFavorite = body.p_favorite ?? body.favorite
  const shouldFavorite = desiredStickerFavoriteState(existing, requestedFavorite)
  // A user may remove their own favorite even after its source pack disappears.
  if (!shouldFavorite) {
    if (existing) {
      await pool.query(
        `DELETE FROM fallback_compat_rows WHERE table_name = 'user_sticker_favorites' AND row_id = $1`,
        [rowId(existing, 'user_sticker_favorites')],
      )
    }
    return { ok: true, is_favorite: false }
  }
  const accessiblePacks = await accessibleStickerPackIds(pool, identity)
  let item = (await importedAndCompatRows(pool, 'sticker_pack_items')).find((row) =>
    normalizeIdentityValue(row.sticker_id || row.id) === normalizeIdentityValue(stickerId) &&
    !stickerRowDeleted(row) && accessiblePacks.has(normalizeIdentityValue(row.pack_id)),
  )
  if (!item) {
    const mediaUrl = String(messageBodyValue(body, 'media_url', '') || '').trim().slice(0, 2000)
    if (!mediaUrl || !(await currentUserMayReferenceMessageMedia(pool, { id: identity.localUserId }, identity, {
      mediaUrl, contentType: 'sticker', allowedOwnedBuckets: ['chat-media'],
    }))) throw httpError('STICKER_MEDIA_NOT_OWNED')
    const packId = `saved-stickers:${identity.localUserId}`
    await storeCompatRow(pool, 'sticker_packs', {
      id: packId, owner_id: identity.preferredUserId, name: 'Mis stickers', is_public: false,
    })
    const title = String(messageBodyValue(body, 'title', 'Sticker') || 'Sticker').trim().slice(0, 80)
    item = await storeCompatRow(pool, 'sticker_pack_items', {
      // Two recipients can save the same URL without overwriting each other's pack.
      id: `saved:${identity.localUserId}:${stickerId}`, sticker_id: stickerId, pack_id: packId,
      title, label: title, media_url: mediaUrl, thumbnail_url: mediaUrl,
      is_animated: body.p_is_animated === true, source: 'message', tags: [],
    })
  }
  if (Boolean(existing) === shouldFavorite) {
    return { ok: true, is_favorite: shouldFavorite }
  }
  await storeCompatRow(pool, 'user_sticker_favorites', {
    user_id: identity.preferredUserId,
    sticker_id: stickerId,
  })
  return { ok: true, is_favorite: true }
}

async function searchStickersRpc(pool, auth, body) {
  const identity = await getAuthIdentity(pool, auth)
  if (!identity) throw httpError('AUTH_REQUIRED', 401)
  const query = String(messageBodyValue(body, 'query', '') || '').trim().toLocaleLowerCase('es').slice(0, 100)
  const limit = Math.max(1, Math.min(250, Number(messageBodyValue(body, 'limit', 100)) || 100))
  const [packs, items, favorites, accessiblePacks] = await Promise.all([
    importedAndCompatRows(pool, 'sticker_packs'),
    importedAndCompatRows(pool, 'sticker_pack_items'),
    readCompatRows(pool, 'user_sticker_favorites'),
    accessibleStickerPackIds(pool, identity),
  ])
  const packById = new Map(packs.map((pack) => [normalizeIdentityValue(pack.id), pack]))
  const favoriteIds = new Set(
    favorites
      .filter((row) => identityOwnsRow(identity, row, ['user_id']))
      .map((row) => normalizeIdentityValue(row.sticker_id || row.item_id))
      .filter(Boolean),
  )
  return items
    .filter((item) => !stickerRowDeleted(item) && accessiblePacks.has(normalizeIdentityValue(item.pack_id)))
    .map((item) => {
      const id = String(item.sticker_id || item.id || '').trim()
      const title = String(item.title || item.label || item.name || 'Sticker').trim()
      const tags = Array.isArray(item.tags) ? item.tags.map(String) : []
      const pack = packById.get(normalizeIdentityValue(item.pack_id))
      return {
        ...item,
        id,
        title,
        media_url: item.media_url || item.url || null,
        thumbnail_url: item.thumbnail_url || item.media_url || item.url || null,
        is_animated: item.is_animated === true,
        tags,
        source: item.source || 'remote',
        pack_id: item.pack_id || null,
        pack_name: item.pack_name || pack?.name || null,
        is_favorite: favoriteIds.has(normalizeIdentityValue(id)),
      }
    })
    .filter((item) => item.id && item.media_url)
    .filter((item) => !query || `${item.title} ${item.tags.join(' ')}`.toLocaleLowerCase('es').includes(query))
    .sort((a, b) => Number(b.is_favorite) - Number(a.is_favorite) || a.title.localeCompare(b.title, 'es'))
    .slice(0, limit)
}

function normalizeWallet(row, identity) {
  return {
    ...(row || {}),
    id: identity.preferredUserId,
    user_id: identity.preferredUserId,
    balance_diamonds: Math.max(0, Math.trunc(Number(row?.balance_diamonds || 0) || 0)),
    balance_debt_diamonds: Math.max(0, Math.trunc(Number(row?.balance_debt_diamonds || 0) || 0)),
    balance_credits: Math.max(0, Number(row?.balance_credits || 0) || 0),
    total_spent: Math.max(0, Number(row?.total_spent || 0) || 0),
    created_at: row?.created_at || nowIso(),
    updated_at: nowIso(),
  }
}

async function walletForIdentity(pool, identity) {
  const wallets = await importedAndCompatRows(pool, 'wallets')
  return normalizeWallet(
    wallets.find((row) => identityOwnsRow(identity, row, ['user_id', 'id', 'owner_id'])),
    identity,
  )
}

async function lockWalletForIdentity(client, identity) {
  // A row-level lock cannot protect the first wallet creation. Serialize all
  // balance mutations by canonical user so concurrent purchases/gifts cannot
  // both create or read an unlocked zero-row wallet.
  await client.query(
    'SELECT pg_advisory_xact_lock(hashtextextended($1, 0))',
    [`wallet:${identity.preferredUserId}`],
  )
  const existing = await client.query(
    `SELECT row
     FROM fallback_compat_rows
     WHERE table_name = 'wallets'
       AND (row_id = $1 OR lower(COALESCE(row->>'user_id', '')) = ANY($2::text[]))
     ORDER BY updated_at DESC
     LIMIT 1
     FOR UPDATE`,
    [identity.preferredUserId, identity.values],
  )
  if (existing.rows[0]?.row) return normalizeWallet(existing.rows[0].row, identity)
  const wallet = await walletForIdentity(client, identity)
  await storeCompatRow(client, 'wallets', wallet)
  return wallet
}

async function coinLotsForIdentity(pool, identity) {
  return (await readCompatRows(pool, 'wallet_coin_lots'))
    .filter((row) => identityOwnsRow(identity, row, ['user_id']))
    .map(normalizeCoinLot)
    .filter(Boolean)
}

function walletCoinSourceFields(lots) {
  const summary = summarizeCoinLots(lots)
  return {
    balance_purchased_diamonds: summary.PURCHASED,
    balance_promotional_diamonds: summary.PROMOTIONAL,
    balance_adjustment_diamonds: summary.ADJUSTMENT,
  }
}

async function ensureAttributableCoinLots(client, identity, wallet) {
  const lots = await coinLotsForIdentity(client, identity)
  const available = summarizeCoinLots(lots).total
  if (available > wallet.balance_diamonds) {
    throw httpError('COIN_LEDGER_INCONSISTENT', 503)
  }
  const unattributed = wallet.balance_diamonds - available
  if (unattributed <= 0) return lots
  const reference = `legacy-wallet:${identity.preferredUserId}`
  const existing = lots.find((lot) =>
    lot.source_type === COIN_SOURCE_TYPES.ADJUSTMENT && lot.source_reference === reference,
  )
  const adjusted = normalizeCoinLot({
    ...(existing || {}),
    id: existing?.id || `adjustment:${identity.preferredUserId}`,
    user_id: identity.preferredUserId,
    source_type: COIN_SOURCE_TYPES.ADJUSTMENT,
    source_reference: reference,
    original_amount_diamonds: nonNegativeInteger(existing?.original_amount_diamonds) + unattributed,
    available_amount_diamonds: nonNegativeInteger(existing?.available_amount_diamonds) + unattributed,
    spent_amount_diamonds: nonNegativeInteger(existing?.spent_amount_diamonds),
    reversed_amount_diamonds: nonNegativeInteger(existing?.reversed_amount_diamonds),
    status: 'active',
    created_at: existing?.created_at || nowIso(),
    updated_at: nowIso(),
  })
  await storeCompatRow(client, 'wallet_coin_lots', adjusted)
  return [...lots.filter((lot) => lot.id !== adjusted.id), adjusted]
}

async function storePurchasedCoinLot(client, identity, purchase, creditedCoins) {
  const amount = nonNegativeInteger(creditedCoins)
  if (amount <= 0) return null
  const sourceReference = String(purchase.id || purchase.purchase_token_hash || '').trim()
  if (!sourceReference) throw new Error('PURCHASE_SOURCE_REFERENCE_REQUIRED')
  const existing = (await coinLotsForIdentity(client, identity)).find((lot) =>
    lot.source_type === COIN_SOURCE_TYPES.PURCHASED && lot.source_reference === sourceReference,
  )
  if (existing) return existing
  return storeCompatRow(client, 'wallet_coin_lots', {
    id: `purchase:${sourceReference}`,
    user_id: identity.preferredUserId,
    source_type: COIN_SOURCE_TYPES.PURCHASED,
    source_reference: sourceReference,
    purchase_id: purchase.id || null,
    product_id: purchase.product_id || null,
    original_amount_diamonds: amount,
    available_amount_diamonds: amount,
    spent_amount_diamonds: 0,
    reversed_amount_diamonds: 0,
    status: 'active',
    created_at: purchase.purchased_at || nowIso(),
  })
}

async function storeGiftCoinAllocations(client, giftTransactionId, allocations) {
  for (const allocation of allocations) {
    await storeCompatRow(client, 'gift_coin_allocations', {
      id: `${giftTransactionId}:${allocation.lot_id}`,
      gift_transaction_id: giftTransactionId,
      lot_id: allocation.lot_id,
      source_type: allocation.source_type,
      source_reference: allocation.source_reference || null,
      amount_diamonds: allocation.amount_diamonds,
      reversed_amount_diamonds: 0,
      created_at: nowIso(),
    })
  }
}

function googlePlayServiceAccountRaw() {
  const filePath = String(process.env.GOOGLE_PLAY_SERVICE_ACCOUNT_FILE || '').trim()
  if (filePath) {
    try {
      return fs.readFileSync(filePath, 'utf8').trim()
    } catch (_) {
      return ''
    }
  }
  return String(process.env.GOOGLE_PLAY_SERVICE_ACCOUNT_JSON || '').trim()
}

function googlePlayConfiguration() {
  const enabled = ['1', 'true', 'yes', 'on'].includes(
    String(process.env.LIVE_COIN_PURCHASES_ENABLED || '').trim().toLowerCase(),
  )
  let serviceAccount = null
  let packages = {}
  try {
    serviceAccount = JSON.parse(googlePlayServiceAccountRaw())
    if (serviceAccount?.private_key) {
      serviceAccount.private_key = String(serviceAccount.private_key).replace(/\\n/g, '\n')
    }
  } catch (_) {
    serviceAccount = null
  }
  try {
    const rawPackages = JSON.parse(String(process.env.GOOGLE_PLAY_COIN_PACKAGES_JSON || '{}'))
    packages = Object.fromEntries(
      Object.entries(rawPackages)
        .map(([productId, coins]) => [String(productId).trim(), Math.trunc(Number(coins) || 0)])
        .filter(([productId, coins]) => productId && coins > 0 && coins <= 1_000_000)
        .slice(0, 8),
    )
  } catch (_) {
    packages = {}
  }
  const packageName = String(process.env.GOOGLE_PLAY_PACKAGE_NAME || 'conectate.com').trim()
  const packageCount = Object.keys(packages).length
  const ready = Boolean(
    enabled &&
    packageName &&
    packageCount === 8 &&
    serviceAccount?.client_email &&
    serviceAccount?.private_key,
  )
  return { enabled: ready, packageName, packages, serviceAccount }
}

function liveGiftTransactionsEnabled() {
  return ['1', 'true', 'yes', 'on'].includes(
    String(process.env.LIVE_GIFTS_ENABLED || '').trim().toLowerCase(),
  )
}

async function googlePlayAccessToken(serviceAccount) {
  const now = Math.floor(Date.now() / 1000)
  const assertion = jwt.sign(
    {
      scope: 'https://www.googleapis.com/auth/androidpublisher',
      aud: serviceAccount.token_uri || 'https://oauth2.googleapis.com/token',
      iat: now,
      exp: now + 3600,
    },
    serviceAccount.private_key,
    { algorithm: 'RS256', issuer: serviceAccount.client_email },
  )
  const response = await fetch(serviceAccount.token_uri || 'https://oauth2.googleapis.com/token', {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({
      grant_type: 'urn:ietf:params:oauth:grant-type:jwt-bearer',
      assertion,
    }),
  })
  const payload = await response.json().catch(() => ({}))
  if (!response.ok || !payload.access_token) throw httpError('GOOGLE_PLAY_AUTH_FAILED', 502)
  return payload.access_token
}

async function googlePlayProductPurchase(config, productId, purchaseToken, accessToken) {
  const packageName = encodeURIComponent(config.packageName)
  const product = encodeURIComponent(productId)
  const token = encodeURIComponent(purchaseToken)
  const response = await fetch(`https://androidpublisher.googleapis.com/androidpublisher/v3/applications/${packageName}/purchases/products/${product}/tokens/${token}`, {
    headers: { Authorization: `Bearer ${accessToken}`, Accept: 'application/json' },
  })
  const payload = await response.json().catch(() => ({}))
  if (!response.ok) throw httpError(payload?.error?.message || 'GOOGLE_PLAY_PURCHASE_NOT_FOUND', response.status === 404 ? 404 : 502)
  return payload
}

async function consumeGooglePlayProduct(config, productId, purchaseToken, accessToken) {
  const packageName = encodeURIComponent(config.packageName)
  const product = encodeURIComponent(productId)
  const token = encodeURIComponent(purchaseToken)
  const response = await fetch(`https://androidpublisher.googleapis.com/androidpublisher/v3/applications/${packageName}/purchases/products/${product}/tokens/${token}:consume`, {
    method: 'POST',
    headers: { Authorization: `Bearer ${accessToken}`, Accept: 'application/json' },
  })
  if (!response.ok) {
    const payload = await response.json().catch(() => ({}))
    throw httpError(payload?.error?.message || 'GOOGLE_PLAY_CONSUME_FAILED', 502)
  }
}

async function billingCapabilitiesRpc(pool, auth) {
  const identity = await getAuthIdentity(pool, auth)
  if (!identity) throw httpError('AUTH_REQUIRED', 401)
  const config = googlePlayConfiguration()
  return {
    enabled: config.enabled,
    provider: 'google_play',
    products: Object.entries(config.packages).map(([product_id, coins]) => ({ product_id, coins })),
    gift_limits: liveGiftLimitsConfiguration(),
    rtdn: {
      enabled: googlePlayRtdnConfiguration().enabled,
      ready: googlePlayRtdnConfiguration().ready,
    },
  }
}

function googlePlayRefundReconciliationConfiguration() {
  const configuredValue = String(
    process.env.GOOGLE_PLAY_REFUND_RECONCILIATION_ENABLED || '',
  ).trim()
  // Refund clawback is a required part of a production Billing integration,
  // not an optional background extra. It is on by default whenever Google
  // Play itself is configured; an operator can still explicitly disable it
  // for an isolated maintenance window.
  const enabled = configuredValue
    ? /^(1|true|yes|on)$/i.test(configuredValue)
    : true
  const intervalMinutes = Math.max(5, Math.min(
    Math.trunc(Number(process.env.GOOGLE_PLAY_REFUND_RECONCILIATION_INTERVAL_MINUTES || 5) || 5),
    24 * 60,
  ))
  const lookbackHours = Math.max(1, Math.min(
    Math.trunc(Number(process.env.GOOGLE_PLAY_REFUND_RECONCILIATION_LOOKBACK_HOURS || 720) || 720),
    30 * 24,
  ))
  return { enabled, intervalMinutes, lookbackHours }
}

async function reverseGiftAllocationsForPurchase(client, purchaseLot, purchase, reversedAt) {
  const allocations = (await readCompatRows(client, 'gift_coin_allocations'))
    .filter((row) => normalizeIdentityValue(row.lot_id) === normalizeIdentityValue(purchaseLot.id))
  if (allocations.length === 0) {
    return { reversed_gifts: 0, reversed_earnings_diamonds: 0, affected_creator_ids: [] }
  }
  const gifts = await importedAndCompatRows(client, 'gift_transactions')
  const earnings = await importedAndCompatRows(client, 'creator_earnings')
  let reversedGifts = 0
  let reversedEarnings = 0
  const affectedCreatorIds = new Set()
  for (const allocation of allocations) {
    const allocated = nonNegativeInteger(allocation.amount_diamonds)
    const alreadyReversed = nonNegativeInteger(allocation.reversed_amount_diamonds)
    const amountToReverse = Math.max(0, allocated - alreadyReversed)
    if (amountToReverse <= 0) continue
    const giftId = normalizeIdentityValue(allocation.gift_transaction_id)
    const gift = gifts.find((row) => normalizeIdentityValue(row.id || row.idempotency_key) === giftId)
    if (!gift) throw new Error('GIFT_ALLOCATION_TRANSACTION_MISSING')
    const reversedPurchased = nonNegativeInteger(gift.reversed_purchased_amount_diamonds) + amountToReverse
    const netWithdrawable = netWithdrawableGiftAmount(gift, reversedPurchased)
    const earning = earnings.find((row) =>
      normalizeIdentityValue(row.gift_transaction_id) === giftId ||
      normalizeIdentityValue(row.id) === normalizeIdentityValue(gift.earning_id),
    )
    if (!earning) throw new Error('GIFT_ALLOCATION_EARNING_MISSING')
    const creatorId = normalizeIdentityValue(earning.creator_id || gift.receiver_id)
    if (creatorId) affectedCreatorIds.add(creatorId)

    await storeCompatRow(client, 'gift_coin_allocations', {
      ...allocation,
      reversed_amount_diamonds: allocated,
      reversal_purchase_id: purchase.id,
      reversed_at: reversedAt,
      status: 'reversed',
    })
    await storeCompatRow(client, 'gift_transactions', {
      ...gift,
      reversed_purchased_amount_diamonds: reversedPurchased,
      withdrawable_amount_diamonds: netWithdrawable,
      status: netWithdrawable > 0 ? 'committed' : 'reversed',
      reversed_at: netWithdrawable > 0 ? gift.reversed_at || null : reversedAt,
      reversal_purchase_id: purchase.id,
      reversal_reason: 'google_play_voided_purchase',
    })
    const earningAmount = nonNegativeInteger(
      earning.withdrawable_amount_diamonds ?? earning.amount_diamonds,
    )
    const earningAfter = Math.max(0, earningAmount - amountToReverse)
    await storeCompatRow(client, 'creator_earnings', {
      ...earning,
      amount_diamonds: earningAfter,
      withdrawable_amount_diamonds: earningAfter,
      reversed_amount_diamonds: nonNegativeInteger(earning.reversed_amount_diamonds) + amountToReverse,
      status: earningAfter > 0 ? 'pending' : 'reversed',
      reversed_at: earningAfter > 0 ? earning.reversed_at || null : reversedAt,
      reversal_purchase_id: purchase.id,
      reversal_reason: 'google_play_voided_purchase',
    })
    reversedGifts += 1
    reversedEarnings += amountToReverse
  }
  return {
    reversed_gifts: reversedGifts,
    reversed_earnings_diamonds: reversedEarnings,
    affected_creator_ids: Array.from(affectedCreatorIds),
  }
}

async function processGooglePlayVoidedPurchase(pool, voidedPurchase) {
  const purchaseToken = String(voidedPurchase?.purchaseToken || '').trim()
  if (!purchaseToken || purchaseToken.length > 4096) return { status: 'invalid', reversed: false }
  const tokenHash = crypto.createHash('sha256').update(purchaseToken).digest('hex')
  const orderId = String(voidedPurchase?.orderId || '').trim()
  const orderIdHash = orderId ? crypto.createHash('sha256').update(orderId).digest('hex') : null
  const client = await pool.connect()
  let inTransaction = false
  try {
    await client.query('BEGIN')
    inTransaction = true
    await client.query('SELECT pg_advisory_xact_lock(hashtextextended($1, 0))', [`refund:${tokenHash}`])
    const purchases = await importedAndCompatRows(client, 'billing_purchases')
    const purchase = purchases.find((row) => row.purchase_token_hash === tokenHash)
    if (!purchase) {
      await storeCompatRow(client, 'billing_voided_purchase_events', {
        id: `voided:${tokenHash}`,
        purchase_token_hash: tokenHash,
        order_id_hash: orderIdHash,
        status: 'unmatched',
        voided_at: voidedPurchase?.voidedTimeMillis
          ? new Date(Number(voidedPurchase.voidedTimeMillis)).toISOString()
          : nowIso(),
      })
      await client.query('COMMIT')
      inTransaction = false
      return { status: 'unmatched', reversed: false }
    }
    if (orderId && purchase.order_id && String(purchase.order_id) !== orderId) {
      await client.query('ROLLBACK')
      inTransaction = false
      return { status: 'order_mismatch', reversed: false }
    }
    if (['refunded', 'reversed'].includes(normalizeIdentityValue(purchase.status))) {
      await client.query('COMMIT')
      inTransaction = false
      return { status: 'refunded', reversed: false, idempotent_replay: true }
    }
    if (normalizeIdentityValue(purchase.status) !== 'credited') {
      await client.query('ROLLBACK')
      inTransaction = false
      return { status: 'not_credited', reversed: false }
    }

    const userId = normalizeIdentityValue(purchase.user_id)
    const identity = {
      ids: new Set([userId]),
      values: [userId],
      preferredUserId: userId,
      localUserId: userId,
    }
    const wallet = await lockWalletForIdentity(client, identity)
    const coins = Math.max(0, Math.trunc(Number(purchase.coins || 0) || 0))
    if (!userId || coins <= 0) throw new Error('INVALID_CREDITED_PURCHASE_LEDGER')
    const creditedCoins = nonNegativeInteger(
      purchase.credited_coins ?? (coins - nonNegativeInteger(purchase.debt_repaid)),
    )
    const lots = await coinLotsForIdentity(client, identity)
    const purchaseLot = lots.find((lot) =>
      lot.source_type === COIN_SOURCE_TYPES.PURCHASED &&
      [purchase.id, purchase.purchase_token_hash].some((reference) =>
        reference && String(lot.source_reference || '') === String(reference),
      ),
    )
    if (!purchaseLot) throw new Error('PURCHASE_PROVENANCE_MISSING')
    const removedCoins = Math.min(wallet.balance_diamonds, purchaseLot.available_amount_diamonds)
    const debtCreated = Math.max(0, creditedCoins - removedCoins)
    const newBalance = wallet.balance_diamonds - removedCoins
    const newDebt = wallet.balance_debt_diamonds + debtCreated
    const reversedAt = nowIso()
    const reversedGiftState = await reverseGiftAllocationsForPurchase(
      client,
      purchaseLot,
      purchase,
      reversedAt,
    )
    const reversedLot = {
      ...purchaseLot,
      available_amount_diamonds: 0,
      reversed_amount_diamonds: purchaseLot.original_amount_diamonds,
      status: 'reversed',
      reversed_at: reversedAt,
      updated_at: reversedAt,
    }
    await storeCompatRow(client, 'wallet_coin_lots', reversedLot)
    const updatedLots = [...lots.filter((lot) => lot.id !== reversedLot.id), reversedLot]

    await storeCompatRow(client, 'wallets', {
      ...wallet,
      balance_diamonds: newBalance,
      balance_debt_diamonds: newDebt,
      ...walletCoinSourceFields(updatedLots),
    })
    await storeCompatRow(client, 'billing_purchases', {
      ...purchase,
      status: 'refunded',
      reversed_at: reversedAt,
      voided_at: voidedPurchase?.voidedTimeMillis
        ? new Date(Number(voidedPurchase.voidedTimeMillis)).toISOString()
        : reversedAt,
      voided_reason: Number.isFinite(Number(voidedPurchase?.voidedReason))
        ? Math.trunc(Number(voidedPurchase.voidedReason))
        : null,
      voided_source: Number.isFinite(Number(voidedPurchase?.voidedSource))
        ? Math.trunc(Number(voidedPurchase.voidedSource))
        : null,
      coins_removed: removedCoins,
      coin_debt_created: debtCreated,
      reversed_gifts: reversedGiftState.reversed_gifts,
      reversed_earnings_diamonds: reversedGiftState.reversed_earnings_diamonds,
    })
    await storeCompatRow(client, 'wallet_transactions', {
      id: `refund:${purchase.id}`,
      user_id: userId,
      direction: 'debit',
      transaction_type: 'google_play_refund',
      amount_diamonds: creditedCoins,
      provider_amount_diamonds: coins,
      applied_amount_diamonds: removedCoins,
      balance_after: newBalance,
      balance_debt_after: newDebt,
      reference_id: purchase.id,
      status: 'refunded',
      coin_source: COIN_SOURCE_TYPES.PURCHASED,
    })
    await storeCompatRow(client, 'economic_reversals', {
      id: `google-play-refund:${purchase.id}`,
      reference_id: String(purchase.id),
      purchase_id: purchase.id,
      user_id: userId,
      source_type: COIN_SOURCE_TYPES.PURCHASED,
      reason: 'google_play_voided_purchase',
      provider_amount_diamonds: coins,
      credited_amount_diamonds: creditedCoins,
      wallet_removed_diamonds: removedCoins,
      debt_created_diamonds: debtCreated,
      creator_earnings_reversed_diamonds: reversedGiftState.reversed_earnings_diamonds,
      reversed_gifts: reversedGiftState.reversed_gifts,
      created_at: reversedAt,
    })
    await storeCompatRow(client, 'billing_voided_purchase_events', {
      id: `voided:${tokenHash}`,
      purchase_token_hash: tokenHash,
      order_id_hash: orderIdHash,
      purchase_id: purchase.id,
      status: 'processed',
      voided_at: voidedPurchase?.voidedTimeMillis
        ? new Date(Number(voidedPurchase.voidedTimeMillis)).toISOString()
        : reversedAt,
    })
    await storeCompatRow(client, 'notifications', {
      id: crypto.randomUUID(),
      recipient_id: userId,
      type: 'google_play_refund_adjustment',
      target_id: String(purchase.id),
      target_type: 'billing_purchase',
      title: 'Saldo ajustado por reembolso',
      body: debtCreated > 0
        ? `Se retiraron ${removedCoins} monedas y quedó un saldo pendiente de ${debtCreated}.`
        : `Se retiraron ${removedCoins} monedas de la compra reembolsada.`,
      is_read: false,
      created_at: reversedAt,
      data: {
        purchase_id: purchase.id,
        coins_removed: removedCoins,
        debt_created: debtCreated,
      },
    })
    for (const creatorId of reversedGiftState.affected_creator_ids) {
      await storeCompatRow(client, 'notifications', {
        id: crypto.randomUUID(),
        recipient_id: creatorId,
        type: 'creator_earning_reversed',
        target_id: String(purchase.id),
        target_type: 'billing_purchase',
        title: 'Ganancia ajustada por reembolso',
        body: `Se revirtieron ${reversedGiftState.reversed_earnings_diamonds} monedas de regalos asociados a una compra reembolsada.`,
        is_read: false,
        created_at: reversedAt,
        data: {
          purchase_id: purchase.id,
          reversed_earnings_diamonds: reversedGiftState.reversed_earnings_diamonds,
        },
      })
    }
    await client.query('COMMIT')
    inTransaction = false
    void publishRealtimeEvent(`user:${userId}`, {
      event: 'wallet_adjusted',
      reason: 'google_play_voided_purchase',
      balanceDiamonds: newBalance,
      balanceDebtDiamonds: newDebt,
      removedCoins,
      debtCreated,
    }).catch(() => {})
    for (const creatorId of reversedGiftState.affected_creator_ids) {
      void publishRealtimeEvent(`user:${creatorId}`, {
        event: 'creator_earning_adjusted',
        reason: 'google_play_voided_purchase',
        reversedEarningsDiamonds: reversedGiftState.reversed_earnings_diamonds,
      }).catch(() => {})
    }
    return {
      status: 'refunded',
      reversed: true,
      coins,
      removed_coins: removedCoins,
      debt_created: debtCreated,
      balance_diamonds: newBalance,
      balance_debt_diamonds: newDebt,
      reversed_gifts: reversedGiftState.reversed_gifts,
      reversed_earnings_diamonds: reversedGiftState.reversed_earnings_diamonds,
    }
  } catch (error) {
    if (inTransaction) await client.query('ROLLBACK').catch(() => {})
    throw error
  } finally {
    client.release()
  }
}

async function reconcileGooglePlayVoidedPurchases(pool, options = {}) {
  const config = googlePlayConfiguration()
  const reconciliation = googlePlayRefundReconciliationConfiguration()
  if (!config.enabled || !reconciliation.enabled) {
    return { enabled: false, fetched: 0, reversed: 0, unmatched: 0 }
  }
  const endTime = Math.min(Number(options.endTime || Date.now()), Date.now())
  // The Voided Purchases API rejects a timestamp that has crossed its exact
  // 30-day boundary by even a few milliseconds while the request is in
  // flight. Keep a full-day safety margin so the production poll never turns
  // a valid reconciliation cycle into INVALID_ARGUMENT.
  const googlePlayMaximumWindowStart = endTime - (29 * 24 * 60 * 60 * 1000)
  const startTime = Math.max(
    endTime - reconciliation.lookbackHours * 60 * 60 * 1000,
    googlePlayMaximumWindowStart,
    Number(options.startTime || 0),
  )
  const accessToken = await googlePlayAccessToken(config.serviceAccount)
  let pageToken = ''
  let fetched = 0
  let reversed = 0
  let unmatched = 0
  do {
    const url = new URL(
      `https://androidpublisher.googleapis.com/androidpublisher/v3/applications/${encodeURIComponent(config.packageName)}/purchases/voidedpurchases`,
    )
    url.searchParams.set('startTime', String(Math.trunc(startTime)))
    url.searchParams.set('endTime', String(Math.trunc(endTime)))
    url.searchParams.set('type', '0')
    url.searchParams.set('includeQuantityBasedPartialRefund', 'false')
    url.searchParams.set('maxResults', '1000')
    if (pageToken) url.searchParams.set('token', pageToken)
    const response = await fetch(url, {
      headers: { Authorization: `Bearer ${accessToken}`, Accept: 'application/json' },
    })
    const payload = await response.json().catch(() => ({}))
    if (!response.ok) throw httpError('GOOGLE_PLAY_VOIDED_PURCHASES_FAILED', 502)
    const events = Array.isArray(payload.voidedPurchases) ? payload.voidedPurchases : []
    fetched += events.length
    for (const event of events) {
      const result = await processGooglePlayVoidedPurchase(pool, event)
      if (result.reversed) reversed += 1
      if (result.status === 'unmatched') unmatched += 1
    }
    pageToken = String(payload?.tokenPagination?.nextPageToken || '').trim()
  } while (pageToken)
  return { enabled: true, fetched, reversed, unmatched, startTime, endTime }
}

async function verifyGooglePlayPurchaseRpc(pool, auth, body) {
  const identity = await getAuthIdentity(pool, auth)
  if (!identity) throw httpError('AUTH_REQUIRED', 401)
  const config = googlePlayConfiguration()
  if (!config.enabled) throw httpError('GOOGLE_PLAY_BILLING_DISABLED', 503)
  const productId = String(messageBodyValue(body, 'product_id', '') || '').trim()
  const purchaseToken = String(messageBodyValue(body, 'purchase_token', '') || '').trim()
  const coins = config.packages[productId]
  if (!coins || !purchaseToken || purchaseToken.length > 4096) throw httpError('GOOGLE_PLAY_PURCHASE_INVALID', 400)

  const tokenHash = crypto.createHash('sha256').update(purchaseToken).digest('hex')
  const existingPurchase = (await importedAndCompatRows(pool, 'billing_purchases'))
    .find((row) => row.purchase_token_hash === tokenHash)
  if (existingPurchase && !identityOwnsValue(identity, existingPurchase.user_id)) {
    throw httpError('GOOGLE_PLAY_PURCHASE_ALREADY_CLAIMED', 409)
  }
  if (existingPurchase && String(existingPurchase.product_id || '').trim() !== productId) {
    throw httpError('GOOGLE_PLAY_PRODUCT_MISMATCH', 409)
  }
  if (existingPurchase && ['refunded', 'reversed'].includes(normalizeIdentityValue(existingPurchase.status))) {
    throw httpError('GOOGLE_PLAY_PURCHASE_REFUNDED', 409)
  }

  const accessToken = await googlePlayAccessToken(config.serviceAccount)
  if (existingPurchase?.status === 'credited') {
    let consumePending = existingPurchase.consumed !== true
    if (existingPurchase.consumed !== true) {
      try {
        await consumeGooglePlayProduct(config, productId, purchaseToken, accessToken)
        await storeCompatRow(pool, 'billing_purchases', { ...existingPurchase, consumed: true, consumed_at: nowIso() })
        consumePending = false
      } catch (error) {
        const consumeFailure = safeErrorLogDetails(error, { production: true })
        await storeCompatRow(pool, 'billing_purchases', {
          ...existingPurchase,
          consume_error: consumeFailure.code,
          consume_error_ref: consumeFailure.messageRef,
        })
      }
    }
    const wallet = await walletForIdentity(pool, identity)
    return { status: 'credited', credited: false, coins: 0, balance_diamonds: wallet.balance_diamonds, consume_pending: consumePending, idempotent_replay: true }
  }

  const purchase = await googlePlayProductPurchase(config, productId, purchaseToken, accessToken)
  const purchaseState = Math.trunc(Number(purchase.purchaseState ?? 1))
  if (purchaseState === 2) return { status: 'pending', credited: false, coins: 0 }
  if (purchaseState !== 0) throw httpError('GOOGLE_PLAY_PURCHASE_NOT_COMPLETED', 409)
  const accountHashes = new Set(identity.values.map((value) =>
    crypto.createHash('sha256').update(String(value)).digest('hex'),
  ))
  const obfuscatedAccountId = String(purchase.obfuscatedExternalAccountId || '').trim().toLowerCase()
  if (!obfuscatedAccountId || !accountHashes.has(obfuscatedAccountId)) {
    throw httpError('GOOGLE_PLAY_ACCOUNT_MISMATCH', 409)
  }

  const client = await pool.connect()
  let inTransaction = false
  let balance = 0
  try {
    await client.query('BEGIN')
    inTransaction = true
    await client.query('SELECT pg_advisory_xact_lock(hashtextextended($1, 0))', [tokenHash])
    const wallet = await lockWalletForIdentity(client, identity)
    const duplicates = await importedAndCompatRows(client, 'billing_purchases')
    const duplicate = duplicates.find((row) => row.purchase_token_hash === tokenHash)
    if (duplicate) {
      if (!identityOwnsValue(identity, duplicate.user_id)) throw httpError('GOOGLE_PLAY_PURCHASE_ALREADY_CLAIMED', 409)
      if (String(duplicate.product_id || '').trim() !== productId) throw httpError('GOOGLE_PLAY_PRODUCT_MISMATCH', 409)
      balance = wallet.balance_diamonds
    } else {
      const debtRepaid = Math.min(wallet.balance_debt_diamonds, coins)
      const creditedCoins = coins - debtRepaid
      balance = wallet.balance_diamonds + creditedCoins
      const purchaseRow = {
        id: crypto.randomUUID(),
        user_id: identity.preferredUserId,
        product_id: productId,
        purchase_token_hash: tokenHash,
        order_id: purchase.orderId || null,
        coins,
        credited_coins: creditedCoins,
        debt_repaid: debtRepaid,
        coin_source: COIN_SOURCE_TYPES.PURCHASED,
        status: 'credited',
        consumed: false,
        purchased_at: purchase.purchaseTimeMillis ? new Date(Number(purchase.purchaseTimeMillis)).toISOString() : nowIso(),
      }
      const existingLots = await ensureAttributableCoinLots(client, identity, wallet)
      const purchasedLot = await storePurchasedCoinLot(client, identity, purchaseRow, creditedCoins)
      const updatedLots = purchasedLot
        ? [...existingLots.filter((lot) => lot.id !== purchasedLot.id), purchasedLot]
        : existingLots
      await storeCompatRow(client, 'wallets', {
        ...wallet,
        balance_diamonds: balance,
        balance_debt_diamonds: wallet.balance_debt_diamonds - debtRepaid,
        ...walletCoinSourceFields(updatedLots),
      })
      await storeCompatRow(client, 'billing_purchases', purchaseRow)
      await storeCompatRow(client, 'wallet_transactions', {
        id: crypto.randomUUID(),
        user_id: identity.preferredUserId,
        direction: 'credit',
        transaction_type: 'google_play_coin_purchase',
        amount_diamonds: coins,
        credited_amount_diamonds: creditedCoins,
        coin_source: COIN_SOURCE_TYPES.PURCHASED,
        balance_after: balance,
        balance_debt_after: wallet.balance_debt_diamonds - debtRepaid,
        reference_id: tokenHash,
      })
    }
    await client.query('COMMIT')
    inTransaction = false
  } catch (error) {
    if (inTransaction) await client.query('ROLLBACK').catch(() => {})
    throw error
  } finally {
    client.release()
  }

  let consumePending = false
  try {
    await consumeGooglePlayProduct(config, productId, purchaseToken, accessToken)
  } catch (error) {
    consumePending = true
  }
  const stored = (await importedAndCompatRows(pool, 'billing_purchases'))
    .find((row) => row.purchase_token_hash === tokenHash)
  if (stored) await storeCompatRow(pool, 'billing_purchases', {
    ...stored,
    consumed: !consumePending,
    consumed_at: consumePending ? null : nowIso(),
  })
  return { status: 'credited', credited: true, coins, balance_diamonds: balance, consume_pending: consumePending, idempotent_replay: false }
}

async function findGooglePlayPurchaserByObfuscatedAccountId(pool, obfuscatedAccountId) {
  const expectedHash = String(obfuscatedAccountId || '').trim().toLowerCase()
  if (!/^[a-f0-9]{64}$/.test(expectedHash)) return null
  const { rows } = await pool.query(
    `SELECT id::text AS local_user_id,
            NULLIF(supabase_user_id::text, '') AS public_user_id
     FROM fallback_users`,
  )
  for (const row of rows) {
    const candidates = [row.local_user_id, row.public_user_id]
      .map(normalizeIdentityValue)
      .filter(Boolean)
    if (candidates.some((candidate) =>
      crypto.createHash('sha256').update(candidate).digest('hex') === expectedHash,
    )) {
      return {
        localUserId: normalizeIdentityValue(row.local_user_id),
        publicUserId: normalizeIdentityValue(row.public_user_id) || null,
      }
    }
  }
  return null
}

async function processGooglePlayOneTimeProductNotification(pool, event) {
  if (event.notificationType === 2) {
    return { status: 'pending_purchase_canceled', credited: false }
  }
  if (event.notificationType !== 1) {
    return { status: 'unsupported_one_time_notification', credited: false }
  }
  const config = googlePlayConfiguration()
  if (!config.enabled) throw httpError('GOOGLE_PLAY_BILLING_DISABLED', 503)
  if (!config.packages[event.productId]) throw httpError('GOOGLE_PLAY_PRODUCT_MISMATCH', 409)
  const accessToken = await googlePlayAccessToken(config.serviceAccount)
  const purchase = await googlePlayProductPurchase(
    config,
    event.productId,
    event.purchaseToken,
    accessToken,
  )
  const account = await findGooglePlayPurchaserByObfuscatedAccountId(
    pool,
    purchase.obfuscatedExternalAccountId,
  )
  if (!account?.localUserId) throw httpError('GOOGLE_PLAY_RTDN_ACCOUNT_NOT_FOUND', 503)
  const result = await verifyGooglePlayPurchaseRpc(pool, { sub: account.localUserId }, {
    p_product_id: event.productId,
    p_purchase_token: event.purchaseToken,
  })
  return {
    status: result.status,
    credited: result.credited === true,
    coins: nonNegativeInteger(result.coins),
    consume_pending: result.consume_pending === true,
  }
}

async function submitGooglePlayRefundReview(config, orderId, request) {
  const accessToken = await googlePlayAccessToken(config.serviceAccount)
  const packageName = encodeURIComponent(config.packageName)
  const encodedOrderId = encodeURIComponent(orderId)
  const response = await fetch(
    `https://androidpublisher.googleapis.com/androidpublisher/v3/applications/${packageName}/orders/${encodedOrderId}:reviewrefund`,
    {
      method: 'POST',
      headers: {
        Authorization: `Bearer ${accessToken}`,
        Accept: 'application/json',
        'Content-Type': 'application/json',
      },
      body: JSON.stringify(request),
    },
  )
  if (!response.ok) {
    const payload = await response.json().catch(() => ({}))
    throw httpError(payload?.error?.message || 'GOOGLE_PLAY_REFUND_REVIEW_FAILED', 502)
  }
}

async function processGooglePlayPendingRefundReview(pool, event, rtdnConfig) {
  const pendingRefundToken = String(event.payload?.pendingRefundToken || '').trim()
  const orderId = String(event.payload?.orderId || '').trim()
  if (!pendingRefundToken || pendingRefundToken.length > 4096 || !orderId || orderId.length > 512) {
    throw httpError('GOOGLE_PLAY_REFUND_REVIEW_INVALID', 400)
  }
  const config = googlePlayConfiguration()
  if (!config.enabled) throw httpError('GOOGLE_PLAY_BILLING_DISABLED', 503)
  const purchases = await importedAndCompatRows(pool, 'billing_purchases')
  const purchase = purchases.find((row) => String(row.order_id || '').trim() === orderId)
  let percentage = 0
  let usageEvents = []
  if (purchase) {
    const lots = await importedAndCompatRows(pool, 'wallet_coin_lots')
    const lot = lots.map(normalizeCoinLot).filter(Boolean).find((candidate) =>
      candidate.source_type === COIN_SOURCE_TYPES.PURCHASED &&
      [purchase.id, purchase.purchase_token_hash].some((reference) =>
        reference && String(candidate.source_reference || '') === String(reference),
      ),
    )
    if (lot) {
      const original = Math.max(1, nonNegativeInteger(lot.original_amount_diamonds))
      const spent = Math.min(original, nonNegativeInteger(lot.spent_amount_diamonds))
      percentage = Math.max(0, Math.min(100_000, Math.round((spent / original) * 100_000)))
      const allocations = (await importedAndCompatRows(pool, 'gift_coin_allocations'))
        .filter((row) => normalizeIdentityValue(row.lot_id) === normalizeIdentityValue(lot.id))
        .slice(0, 1000)
      const obfuscatedAccountId = String(event.payload?.obfuscatedAccountId || '').trim()
      usageEvents = allocations.map((allocation) => compactRow({
        obfuscatedAccountId: obfuscatedAccountId || undefined,
        consumptionTime: allocation.created_at || purchase.purchased_at || undefined,
        consumptionItemDescription: `Regalo virtual Novvin confirmado (${nonNegativeInteger(allocation.amount_diamonds)} monedas)`,
      }))
    }
  }
  const preference = rtdnConfig.pendingRefundPreference || 'NEUTRAL'
  await submitGooglePlayRefundReview(config, orderId, {
    pendingRefundToken,
    sampleContentProvided: false,
    refundPreference: preference,
    consumptionPercentageMilliunits: percentage,
    consumptionUsageEvents: usageEvents,
  })
  await storeCompatRow(pool, 'billing_pending_refund_reviews', {
    id: `review:${crypto.createHash('sha256').update(orderId).digest('hex')}`,
    order_id_hash: crypto.createHash('sha256').update(orderId).digest('hex'),
    purchase_id: purchase?.id || null,
    refund_reason: Number.isFinite(Number(event.payload?.refundReason))
      ? Math.trunc(Number(event.payload.refundReason))
      : null,
    refund_preference: preference,
    consumption_percentage_milliunits: percentage,
    usage_event_count: usageEvents.length,
    status: 'submitted',
    submitted_at: nowIso(),
  })
  return {
    status: 'refund_review_submitted',
    purchase_matched: Boolean(purchase),
    refund_preference: preference,
    consumption_percentage_milliunits: percentage,
    usage_event_count: usageEvents.length,
  }
}

async function processGooglePlayRtdnNotification(pool, event, rtdnConfig) {
  if (event.kind === 'test') return { status: 'test_notification_processed' }
  if (event.kind === 'subscription') {
    return { status: 'subscription_ignored', reason: 'subscriptions_not_sold' }
  }
  if (event.kind === 'one_time_product') {
    return processGooglePlayOneTimeProductNotification(pool, event)
  }
  if (event.kind === 'voided_purchase') {
    const productType = Math.trunc(Number(event.payload?.productType || 0))
    if (productType !== 2) return { status: 'subscription_void_ignored' }
    return processGooglePlayVoidedPurchase(pool, {
      purchaseToken: event.purchaseToken,
      orderId: event.orderId,
      voidedTimeMillis: event.eventTimeMillis,
      voidedReason: event.payload?.refundType,
      voidedSource: 'rtdn',
    })
  }
  if (event.kind === 'pending_refund_review') {
    return processGooglePlayPendingRefundReview(pool, event, rtdnConfig)
  }
  return { status: 'unsupported_notification' }
}

async function sendVirtualGiftRpc(pool, auth, body) {
  const identity = await getAuthIdentity(pool, auth)
  if (!identity) throw httpError('AUTH_REQUIRED', 401)
  if (!liveGiftTransactionsEnabled()) throw httpError('GIFTS_DISABLED', 503)
  const suppliedSender = normalizeIdentityValue(messageBodyValue(body, 'sender_id', ''))
  if (suppliedSender && !identityOwnsValue(identity, suppliedSender)) throw httpError('ACTOR_ID_MISMATCH')
  const receiverId = normalizeIdentityValue(messageBodyValue(body, 'receiver_id', ''))
  const giftId = String(messageBodyValue(body, 'gift_id', '') || '').trim().slice(0, 160)
  const liveStreamId = normalizeIdentityValue(messageBodyValue(body, 'live_stream_id', '')) || null
  const idempotencyKey = validUuidOrNull(messageBodyValue(body, 'idempotency_key', ''))
  const isPublic = messageBodyValue(body, 'is_public', true) !== false
  if (!receiverId || !giftId) throw httpError('GIFT_RECEIVER_AND_ID_REQUIRED', 400)
  if (!idempotencyKey) throw httpError('GIFT_IDEMPOTENCY_KEY_REQUIRED', 400)
  if (identityOwnsValue(identity, receiverId)) throw httpError('SELF_GIFT_NOT_ALLOWED', 409)
  if (!DEFAULT_VIRTUAL_GIFT_IDS.has(giftId)) throw httpError('GIFT_NOT_FOUND', 404)

  const priorTransaction = (await importedAndCompatRows(pool, 'gift_transactions'))
    .find((row) => normalizeIdentityValue(row.idempotency_key) === idempotencyKey)
  if (priorTransaction) {
    if (!identityOwnsValue(identity, priorTransaction.sender_id)) throw httpError('GIFT_IDEMPOTENCY_CONFLICT', 409)
    const samePayload =
      normalizeIdentityValue(priorTransaction.receiver_id) === receiverId &&
      String(priorTransaction.gift_id || '').trim() === giftId &&
      (normalizeIdentityValue(priorTransaction.live_stream_id) || null) === liveStreamId &&
      (priorTransaction.is_public !== false) === isPublic
    if (!samePayload) throw httpError('GIFT_IDEMPOTENCY_CONFLICT', 409)
    return {
      ok: true,
      transaction_id: priorTransaction.id,
      earning_id: priorTransaction.earning_id || null,
      sender_balance_diamonds: priorTransaction.sender_balance_after,
      gift_id: priorTransaction.gift_id,
      gift_name: priorTransaction.gift_name,
      gift_image_url: priorTransaction.gift_image_url || null,
      gift_animation_url: priorTransaction.gift_animation_url || null,
      sender_id: priorTransaction.sender_id,
      sender_name: priorTransaction.sender_name,
      receiver_id: priorTransaction.receiver_id,
      live_stream_id: priorTransaction.live_stream_id || null,
      is_public: priorTransaction.is_public !== false,
      idempotent_replay: true,
    }
  }

  const gift = await findCompatEntity(pool, 'virtual_gifts', giftId)
  if (!gift || gift.is_active === false) throw httpError('GIFT_NOT_FOUND', 404)
  const costDiamonds = Math.max(0, Math.trunc(Number(gift.price_diamonds || 0) || 0))
  if (costDiamonds <= 0) throw httpError('GIFT_PRICE_INVALID', 409)
  const giftLimits = liveGiftLimitsConfiguration()
  if (costDiamonds > giftLimits.max_coins_per_gift) throw httpError('GIFT_VALUE_LIMIT_EXCEEDED', 409)
  const profiles = await readTableRows(pool, 'profiles', {}, auth)
  const receiver = profiles.find((profile) =>
    [profile.id, profile.local_id, profile.user_id, profile.supabase_user_id]
      .some((value) => normalizeIdentityValue(value) === receiverId),
  )
  if (!receiver) throw httpError('GIFT_RECEIVER_NOT_FOUND', 404)
  if (liveStreamId) {
    const live = (await readTableRows(pool, 'live_streams', {}, auth))
      .find((row) => normalizeIdentityValue(row.id) === liveStreamId)
    if (!live) throw httpError('LIVE_NOT_ACTIVE', 409)
    if (normalizeIdentityValue(live.host_user_id || live.user_id) !== receiverId) {
      throw httpError('LIVE_RECEIVER_MISMATCH', 409)
    }
    const rawActive = live.is_active ?? live.isActive
    if (!(rawActive === true || rawActive === 1 || String(rawActive).toLowerCase() === 'true')) {
      throw httpError('LIVE_NOT_ACTIVE', 409)
    }
    const giftsEnabled = live.gifts_enabled ?? live.giftsEnabled
    if (giftsEnabled === false || giftsEnabled === 0 || String(giftsEnabled).toLowerCase() === 'false') {
      throw httpError('LIVE_GIFTS_DISABLED', 409)
    }
  }

  const client = await pool.connect()
  let inTransaction = false
  let newBalance = 0
  const earningId = crypto.randomUUID()
  let transaction = null
  let replay = false
  let battleScoreUpdate = null
  try {
    await client.query('BEGIN')
    inTransaction = true
    await client.query('SELECT pg_advisory_xact_lock(hashtextextended($1, 0))', [idempotencyKey])
    const wallet = await lockWalletForIdentity(client, identity)
    const existingTransactions = await importedAndCompatRows(client, 'gift_transactions')
    const existing = existingTransactions.find((row) =>
      normalizeIdentityValue(row.idempotency_key) === idempotencyKey,
    )
    if (existing) {
      if (!identityOwnsValue(identity, existing.sender_id)) throw httpError('GIFT_IDEMPOTENCY_CONFLICT', 409)
      const samePayload =
        normalizeIdentityValue(existing.receiver_id) === receiverId &&
        String(existing.gift_id || '').trim() === giftId &&
        (normalizeIdentityValue(existing.live_stream_id) || null) === liveStreamId &&
        (existing.is_public !== false) === isPublic
      if (!samePayload) throw httpError('GIFT_IDEMPOTENCY_CONFLICT', 409)
      transaction = existing
      newBalance = Math.max(0, Math.trunc(Number(existing.sender_balance_after || wallet.balance_diamonds) || 0))
      replay = true
      await client.query('COMMIT')
      inTransaction = false
    } else {
    const now = Date.now()
    const senderTransactions = existingTransactions.filter((row) =>
      identityOwnsValue(identity, row.sender_id),
    )
    const giftsLastMinute = senderTransactions.filter((row) => {
      const createdAt = Date.parse(String(row.created_at || ''))
      return Number.isFinite(createdAt) && createdAt >= now - 60_000
    }).length
    if (giftsLastMinute >= giftLimits.max_gifts_per_minute) {
      throw httpError('RATE_LIMITED', 429)
    }
    const spentLastDay = senderTransactions.reduce((total, row) => {
      const createdAt = Date.parse(String(row.created_at || ''))
      if (!Number.isFinite(createdAt) || createdAt < now - 86_400_000) return total
      return total + Math.max(0, Math.trunc(Number(row.amount_diamonds || 0) || 0))
    }, 0)
    if (spentLastDay + costDiamonds > giftLimits.max_coins_per_day) {
      throw httpError('GIFT_DAILY_LIMIT_EXCEEDED', 409)
    }
    // Refunded purchased coins may already have been transferred to creators.
    // Even if another source later adds promotional/admin coins, do not let the
    // account spend again until a verified purchase has repaid the clawback.
    if (wallet.balance_debt_diamonds > 0) throw httpError('WALLET_DEBT_OUTSTANDING', 409)
    if (wallet.balance_diamonds < costDiamonds) throw httpError('INSUFFICIENT_BALANCE', 409)
    const attributableLots = await ensureAttributableCoinLots(client, identity, wallet)
    let allocationPlan
    try {
      allocationPlan = allocateCoinLots(attributableLots, costDiamonds)
    } catch (error) {
      if (error?.code === 'COIN_LEDGER_INSUFFICIENT') throw httpError('COIN_LEDGER_INCONSISTENT', 503)
      throw error
    }
    for (const lot of allocationPlan.updatedLots) {
      await storeCompatRow(client, 'wallet_coin_lots', { ...lot, updated_at: nowIso() })
    }
    const purchasedAmount = allocationPlan.breakdown.PURCHASED
    const promotionalAmount = allocationPlan.breakdown.PROMOTIONAL
    const adjustmentAmount = allocationPlan.breakdown.ADJUSTMENT
    newBalance = wallet.balance_diamonds - costDiamonds
    await storeCompatRow(client, 'wallets', {
      ...wallet,
      balance_diamonds: newBalance,
      total_spent: wallet.total_spent + Math.max(0, Number(gift.price_credits || 0) || 0),
      ...walletCoinSourceFields(allocationPlan.updatedLots),
    })
    await storeCompatRow(client, 'creator_earnings', {
      id: earningId,
      creator_id: receiverId,
      sender_id: identity.preferredUserId,
      source_type: 'virtual_gift',
      source_id: giftId,
      gift_transaction_id: idempotencyKey,
      live_stream_id: liveStreamId,
      amount_credits: Math.max(0, Number(gift.price_credits || 0) || 0),
      amount_diamonds: purchasedAmount,
      gift_face_value_diamonds: costDiamonds,
      withdrawable_amount_diamonds: purchasedAmount,
      non_withdrawable_amount_diamonds: promotionalAmount + adjustmentAmount,
      status: purchasedAmount > 0 ? 'pending' : 'non_withdrawable',
    })
    transaction = await storeCompatRow(client, 'gift_transactions', {
      id: idempotencyKey,
      idempotency_key: idempotencyKey,
      earning_id: earningId,
      sender_id: identity.preferredUserId,
      sender_name: identity.displayName,
      sender_avatar_url: identity.avatarUrl || null,
      receiver_id: receiverId,
      gift_id: giftId,
      gift_name: gift.name || 'Regalo Novvin',
      gift_image_url: gift.image_url || null,
      gift_animation_url: gift.animation_url || null,
      live_stream_id: liveStreamId,
      amount_diamonds: costDiamonds,
      purchased_amount_diamonds: purchasedAmount,
      promotional_amount_diamonds: promotionalAmount,
      adjustment_amount_diamonds: adjustmentAmount,
      withdrawable_amount_diamonds: purchasedAmount,
      reversed_purchased_amount_diamonds: 0,
      sender_balance_after: newBalance,
      status: 'committed',
      is_public: isPublic,
      created_at: nowIso(),
    })
    await storeGiftCoinAllocations(client, transaction.id, allocationPlan.allocations)
    await storeCompatRow(client, 'wallet_transactions', {
      id: crypto.randomUUID(),
      user_id: identity.preferredUserId,
      direction: 'debit',
      transaction_type: 'virtual_gift',
      amount_diamonds: costDiamonds,
      purchased_amount_diamonds: purchasedAmount,
      promotional_amount_diamonds: promotionalAmount,
      adjustment_amount_diamonds: adjustmentAmount,
      balance_after: newBalance,
      reference_id: transaction.id,
    })
    await storeCompatRow(client, 'notifications', {
      id: crypto.randomUUID(),
      recipient_id: receiverId,
      actor_id: identity.preferredUserId,
      actor_name: identity.displayName,
      actor_avatar_url: identity.avatarUrl || null,
      type: 'virtual_gift',
      target_id: liveStreamId || giftId,
      target_type: liveStreamId ? 'live_stream' : 'virtual_gift',
      title: `${identity.displayName} envio ${gift.name || 'un regalo'}`,
      body: `${identity.displayName} envio ${gift.name || 'un regalo'}`,
      is_read: false,
      data: { gift_id: giftId, earning_id: earningId, live_stream_id: liveStreamId },
    })
    battleScoreUpdate = await applyGiftToActiveBattle(client, {
      liveId: liveStreamId,
      giftTransactionId: transaction.id,
      recipientUserId: receiverId,
      purchasedCoins: purchasedAmount,
    })
    await client.query('COMMIT')
    inTransaction = false
    }
  } catch (error) {
    if (inTransaction) await client.query('ROLLBACK').catch(() => {})
    if (error?.expose === true) throw error
    console.error('[gift] database transaction failed', {
      code: String(error?.code || 'UNKNOWN').slice(0, 40),
      table: String(error?.table || '').slice(0, 80) || undefined,
      constraint: String(error?.constraint || '').slice(0, 120) || undefined,
      routine: String(error?.routine || '').slice(0, 120) || undefined,
    })
    const databaseError = httpError('DATABASE_ERROR', 503)
    databaseError.cause = error
    throw databaseError
  } finally {
    client.release()
  }

  if (!replay) void publishRealtimeEvent(`user:${receiverId}`, {
    event: 'virtual_gift_received',
    giftId,
    senderId: identity.preferredUserId,
    liveStreamId,
  })
  if (!replay && battleScoreUpdate && liveStreamId) void publishRealtimeEvent(
    `live-collaboration:${liveStreamId}`,
    {
      event: 'live_collaboration',
      liveId: liveStreamId,
      type: 'battle_score_updated',
      battle: battleScoreUpdate,
      eventId: `gift:${transaction.id}`,
    },
  )
  return {
    ok: true,
    transaction_id: transaction.id,
    earning_id: transaction.earning_id || earningId,
    sender_balance_diamonds: newBalance,
    gift_id: giftId,
    gift_name: transaction.gift_name,
    gift_image_url: transaction.gift_image_url || null,
    gift_animation_url: transaction.gift_animation_url || null,
    sender_id: transaction.sender_id,
    sender_name: transaction.sender_name,
    receiver_id: receiverId,
    live_stream_id: liveStreamId,
    is_public: transaction.is_public !== false,
    idempotent_replay: replay,
  }
}

async function getVirtualGiftReceiptRpc(pool, auth, body) {
  const identity = await getAuthIdentity(pool, auth)
  if (!identity) throw httpError('AUTH_REQUIRED', 401)
  const transactionId = validUuidOrNull(messageBodyValue(body, 'transaction_id', ''))
  if (!transactionId) throw httpError('GIFT_TRANSACTION_ID_REQUIRED', 400)
  const transaction = (await importedAndCompatRows(pool, 'gift_transactions'))
    .find((row) => normalizeIdentityValue(row.id) === transactionId)
  if (!transaction || transaction.status !== 'committed') throw httpError('GIFT_TRANSACTION_NOT_FOUND', 404)
  if (!identityOwnsValue(identity, transaction.sender_id)) throw httpError('GIFT_TRANSACTION_FORBIDDEN', 403)
  return transaction
}

async function subscribeToPlanRpc(pool, auth, body) {
  const identity = await getAuthIdentity(pool, auth)
  if (!identity) throw httpError('AUTH_REQUIRED', 401)
  const suppliedUser = normalizeIdentityValue(messageBodyValue(body, 'user_id', ''))
  if (suppliedUser && !identityOwnsValue(identity, suppliedUser)) throw httpError('ACTOR_ID_MISMATCH')
  const planId = String(messageBodyValue(body, 'plan_id', '') || '').trim().slice(0, 160)
  const paymentMethod = normalizeIdentityValue(messageBodyValue(body, 'payment_method', 'wallet'))
  if (!planId) throw httpError('PLAN_ID_REQUIRED', 400)
  if (paymentMethod !== 'wallet') throw httpError('PAYMENT_METHOD_NOT_SUPPORTED', 501)
  const plan = await findCompatEntity(pool, 'subscription_plans', planId)
  if (!plan || plan.is_active === false) throw httpError('PLAN_NOT_FOUND', 404)
  const price = Math.max(0, Number(plan.price_monthly || 0) || 0)

  const client = await pool.connect()
  let inTransaction = false
  let subscription = null
  try {
    await client.query('BEGIN')
    inTransaction = true
    const subscriptions = await importedAndCompatRows(client, 'user_subscriptions')
    const active = subscriptions.find((row) =>
      identityOwnsRow(identity, row, ['user_id']) &&
      normalizeIdentityValue(row.plan_id) === normalizeIdentityValue(planId) &&
      normalizeIdentityValue(row.status) === 'active' &&
      (!row.current_period_end || new Date(row.current_period_end).getTime() > Date.now()),
    )
    if (active) {
      subscription = active
    } else {
      const wallet = await lockWalletForIdentity(client, identity)
      if (wallet.balance_credits < price) throw httpError('INSUFFICIENT_CREDITS', 409)
      await storeCompatRow(client, 'wallets', {
        ...wallet,
        balance_credits: wallet.balance_credits - price,
        total_spent: wallet.total_spent + price,
      })
      for (const row of subscriptions.filter((entry) =>
        identityOwnsRow(identity, entry, ['user_id']) && normalizeIdentityValue(entry.status) === 'active',
      )) {
        await storeCompatRow(client, 'user_subscriptions', { ...row, status: 'replaced' })
      }
      const start = new Date()
      const end = new Date(start.getTime() + 30 * 24 * 60 * 60 * 1000)
      subscription = await storeCompatRow(client, 'user_subscriptions', {
        id: crypto.randomUUID(),
        user_id: identity.preferredUserId,
        plan_id: planId,
        status: 'active',
        current_period_start: start.toISOString(),
        current_period_end: end.toISOString(),
        cancel_at_period_end: false,
        payment_method: 'wallet',
      })
    }
    await client.query('COMMIT')
    inTransaction = false
  } catch (error) {
    if (inTransaction) await client.query('ROLLBACK').catch(() => {})
    throw error
  } finally {
    client.release()
  }
  return {
    ...subscription,
    subscription_id: subscription.id,
    plan_name: plan.name || null,
  }
}

async function deleteCommentRpc(pool, auth, body, kind) {
  const identity = await getAuthIdentity(pool, auth)
  if (!identity) throw httpError('AUTH_REQUIRED', 401)
  const commentId = validUuidOrNull(messageBodyValue(body, 'comment_id', ''))
  if (!commentId) throw httpError('COMMENT_ID_REQUIRED', 400)
  const isVideo = kind === 'video'
  const tableName = isVideo ? 'video_comments' : 'post_comments'
  const fallbackTable = isVideo ? 'fallback_video_comments' : 'fallback_post_comments'
  const contentIdField = isVideo ? 'video_id' : 'post_id'

  const localResult = await pool.query(
    `SELECT * FROM ${fallbackTable}
     WHERE id = $1::uuid AND is_deleted = FALSE
     LIMIT 1`,
    [commentId],
  )
  let comment = localResult.rows[0] || null
  let imported = false
  if (!comment) {
    comment = (await readImportedRows(pool, tableName))
      .find((row) => normalizeIdentityValue(row.id) === commentId && row.is_deleted !== true) || null
    imported = Boolean(comment)
  }
  if (!comment) throw httpError('COMMENT_NOT_FOUND', 404)

  const contentId = normalizeIdentityValue(comment[contentIdField])
  let contentOwnerId = ''
  if (isVideo) {
    const localVideo = await pool.query(
      `SELECT user_id FROM fallback_videos WHERE lower(id::text) = $1 LIMIT 1`,
      [contentId],
    )
    contentOwnerId = normalizeIdentityValue(localVideo.rows[0]?.user_id)
    if (!contentOwnerId) contentOwnerId = normalizeIdentityValue((await findCompatEntity(pool, 'videos', contentId))?.user_id)
  } else {
    const localPost = await pool.query(
      `SELECT user_id FROM fallback_posts WHERE lower(id::text) = $1 LIMIT 1`,
      [contentId],
    )
    contentOwnerId = normalizeIdentityValue(localPost.rows[0]?.user_id)
    if (!contentOwnerId) contentOwnerId = normalizeIdentityValue((await findCompatEntity(pool, 'posts', contentId))?.user_id)
  }
  const canDelete = identityOwnsRow(identity, comment, ['user_id', 'profile_id', 'author_id', 'owner_id']) ||
    identityOwnsValue(identity, contentOwnerId) ||
    await appAdminHasCapability(pool, identity, ADMIN_CAPABILITY.CONTENT_MANAGE)
  if (!canDelete) throw httpError('COMMENT_DELETE_DENIED')

  if (imported) {
    if (!(await tableExists(pool, 'supabase_import', tableName))) throw httpError('COMMENT_NOT_FOUND', 404)
    await deleteImportedRow(pool, tableName, commentId)
  } else {
    await pool.query(
      `UPDATE ${fallbackTable}
       SET is_deleted = TRUE, content = '', media_url = NULL, updated_at = NOW()
       WHERE id = $1::uuid`,
      [commentId],
    )
  }
  if (isVideo) {
    await pool.query(`DELETE FROM fallback_video_comment_reactions WHERE lower(comment_id) = $1`, [commentId])
    const count = await pool.query(
      `SELECT COUNT(*)::int AS total FROM fallback_video_comments
       WHERE lower(video_id) = $1 AND is_deleted = FALSE`,
      [contentId],
    )
    await pool.query(
      `UPDATE fallback_videos SET comment_count = $2, updated_at = NOW() WHERE lower(id::text) = $1`,
      [contentId, Number(count.rows[0]?.total) || 0],
    )
    void publishRealtimeEvent(`video-comments:${contentId}`, {
      event: 'video_comment_deleted',
      videoId: contentId,
      commentId,
    })
  } else {
    await pool.query(`DELETE FROM fallback_post_comment_reactions WHERE lower(comment_id) = $1`, [commentId])
  }
  return 1
}

async function updateOnlineStatusRpc(pool, auth, body) {
  const identity = await getAuthIdentity(pool, auth)
  if (!identity) throw httpError('AUTH_REQUIRED', 401)
  const suppliedUser = normalizeIdentityValue(body.user_id || body.p_user_id || '')
  if (suppliedUser && !identityOwnsValue(identity, suppliedUser)) throw httpError('ACTOR_ID_MISMATCH')
  const isOnline = strictBoolean(body.online_status ?? body.p_online_status ?? false, 'ONLINE_STATUS')
  const updatedAt = nowIso()
  const profilePatch = { is_online: isOnline, last_seen: updatedAt, updated_at: updatedAt }
  await pool.query(
    `INSERT INTO fallback_profile_overrides(user_id, profile, updated_at)
     VALUES ($1::uuid, $2::jsonb, NOW())
     ON CONFLICT (user_id) DO UPDATE SET
       profile = fallback_profile_overrides.profile || EXCLUDED.profile,
       updated_at = NOW()`,
    [identity.localUserId, profilePatch],
  )
  await pool.query(
    `UPDATE fallback_users SET last_login_at = $2::timestamptz, updated_at = $2::timestamptz
     WHERE id = $1::uuid`,
    [identity.localUserId, updatedAt],
  )
  const profile = await findCompatEntity(pool, 'profiles', identity.preferredUserId)
  await storeCompatRow(pool, 'profiles', {
    ...(profile || {}),
    id: identity.preferredUserId,
    ...profilePatch,
  })
  return { ok: true, is_online: isOnline, last_seen: updatedAt }
}

const VIDEO_REPORT_STATUSES = new Set([
  'pendiente',
  'en_revision',
  'revisado',
  'resuelto',
  'ignorado',
  'finalizado',
  'eliminado',
  'advertido',
  'rechazado',
  'accion_aplicada',
])

async function runInTransaction(pool, operation) {
  const client = typeof pool.connect === 'function' ? await pool.connect() : pool
  const releaseClient = client !== pool && typeof client.release === 'function'
  let inTransaction = false
  try {
    await client.query('BEGIN')
    inTransaction = true
    const result = await operation(client)
    await client.query('COMMIT')
    inTransaction = false
    return result
  } catch (error) {
    if (inTransaction) await client.query('ROLLBACK').catch(() => {})
    throw error
  } finally {
    if (releaseClient) client.release()
  }
}

async function requireAdminIdentity(pool, auth) {
  const identity = await requireAdmin(pool, auth)
  if (!identity) throw httpError('ADMIN_REQUIRED', 403)
  return identity
}

async function resolveFallbackUser(pool, rawUserId) {
  const userId = normalizeIdentityValue(rawUserId)
  if (!userId) return null
  const { rows } = await pool.query(
    `SELECT id::text AS local_user_id,
            COALESCE(NULLIF(supabase_user_id::text, ''), id::text) AS public_user_id,
            email,
            display_name,
            avatar_url
     FROM fallback_users
     WHERE lower(id::text) = $1
        OR lower(COALESCE(supabase_user_id::text, '')) = $1
     LIMIT 1`,
    [userId],
  )
  return rows[0] || null
}

async function assertUserCanBeModerated(pool, target) {
  if (!target) throw httpError('USER_NOT_FOUND', 404)
  const targetIdentity = {
    localUserId: target.local_user_id,
    preferredUserId: target.public_user_id || target.local_user_id,
  }
  if (await isAppAdmin(pool, targetIdentity)) throw httpError('ADMIN_CANNOT_BE_SUSPENDED', 403)
}

function suspensionStateFromBody(body, suspended) {
  if (!suspended) return { permanent: false, suspendedUntil: null }
  const permanent = strictBoolean(body.p_permanent ?? body.permanent ?? false, 'PERMANENT')
  if (permanent) return { permanent: true, suspendedUntil: null }

  const explicitUntil = String(
    body.p_suspended_until || body.suspended_until || body.suspension_end_date || '',
  ).trim()
  if (explicitUntil) {
    const timestamp = Date.parse(explicitUntil)
    const maxTimestamp = Date.now() + (3650 * 24 * 60 * 60 * 1000)
    if (!Number.isFinite(timestamp) || timestamp <= Date.now() || timestamp > maxTimestamp) {
      throw httpError('INVALID_SUSPENSION_END', 400)
    }
    return { permanent: false, suspendedUntil: new Date(timestamp).toISOString() }
  }

  const rawValue = body.p_duration_value ?? body.duration_value ?? body.p_days ?? body.days ?? 7
  const durationValue = Number(rawValue)
  if (!Number.isInteger(durationValue) || durationValue <= 0) {
    throw httpError('INVALID_SUSPENSION_DURATION', 400)
  }
  const unit = normalizeIdentityValue(body.p_duration_unit || body.duration_unit || 'days')
  const multipliers = {
    seconds: 1000,
    minutes: 60 * 1000,
    hours: 60 * 60 * 1000,
    days: 24 * 60 * 60 * 1000,
    years: 365 * 24 * 60 * 60 * 1000,
  }
  const multiplier = multipliers[unit]
  const durationMs = multiplier ? durationValue * multiplier : NaN
  const maxDurationMs = 3650 * 24 * 60 * 60 * 1000
  if (!Number.isFinite(durationMs) || durationMs <= 0 || durationMs > maxDurationMs) {
    throw httpError('INVALID_SUSPENSION_DURATION', 400)
  }
  return { permanent: false, suspendedUntil: new Date(Date.now() + durationMs).toISOString() }
}

async function insertAdminNotification(client, adminIdentity, input) {
  const recipientId = normalizeIdentityValue(input.recipientId)
  if (!recipientId) return null
  const content = String(input.content || '').trim().slice(0, 700)
  const { rows } = await client.query(
    `INSERT INTO fallback_notifications(
       recipient_id, actor_id, actor_name, actor_avatar_url, type,
       target_id, target_type, content, extra_data
     ) VALUES ($1, $2, $3, $4, $5, $6, $7, $8, $9::jsonb)
     RETURNING id::text, recipient_id, actor_id, actor_name, actor_avatar_url,
               type, target_id, target_type, content, is_read, extra_data,
               created_at, updated_at`,
    [
      recipientId,
      adminIdentity.preferredUserId,
      adminIdentity.displayName || 'Administracion',
      adminIdentity.avatarUrl || null,
      String(input.type || 'admin_notice').trim().slice(0, 80),
      String(input.targetId || '').trim().slice(0, 160) || null,
      String(input.targetType || '').trim().slice(0, 80) || null,
      content || null,
      JSON.stringify(input.extraData || {}),
    ],
  )
  return rows[0] || null
}

function dispatchAdminNotification(pool, notification) {
  if (!notification) return
  const extraData = notification.extra_data || {}
  void publishRealtimeEvent(`user:${notification.recipient_id}`, {
    event: 'notification',
    notification,
    extraData,
  }).catch((error) => {
    console.warn('[admin] realtime notification failed', safeErrorLogDetails(error))
  })
  void sendPushForNotification(pool, notification, extraData).catch((error) => {
    console.warn('[admin] push notification failed', safeErrorLogDetails(error))
  })
}

async function adminSetUserSuspensionRpc(pool, auth, body) {
  const adminIdentity = await requireAdminIdentity(pool, auth)
  const target = await resolveFallbackUser(pool, body.p_user_id || body.user_id || body.target_user_id)
  await assertUserCanBeModerated(pool, target)
  const suspended = strictBoolean(body.p_suspend ?? body.is_suspended ?? body.suspend ?? true, 'SUSPEND')
  const reason = String(body.p_reason || body.reason || '').trim().slice(0, 500)
  if (suspended && !reason) throw httpError('SUSPENSION_REASON_REQUIRED', 400)
  const state = suspensionStateFromBody(body, suspended)
  const targetId = normalizeIdentityValue(target.public_user_id || target.local_user_id)

  const notification = await runInTransaction(pool, async (client) => {
    await client.query(
      `INSERT INTO fallback_profile_moderation(
         user_id, is_suspended, suspended_until, suspension_end_date,
         suspension_reason, suspension_count, updated_at
       ) VALUES ($1, $2, $3::timestamptz, $3::timestamptz, $4,
                 CASE WHEN $2 THEN 1 ELSE 0 END, NOW())
       ON CONFLICT (user_id) DO UPDATE SET
         is_suspended = EXCLUDED.is_suspended,
         suspended_until = EXCLUDED.suspended_until,
         suspension_end_date = EXCLUDED.suspension_end_date,
         suspension_reason = EXCLUDED.suspension_reason,
         suspension_count = CASE WHEN EXCLUDED.is_suspended
           THEN fallback_profile_moderation.suspension_count + 1
           ELSE fallback_profile_moderation.suspension_count END,
         updated_at = NOW()`,
      [targetId, suspended, state.suspendedUntil, suspended ? reason : null],
    )
    await recordModerationEvent(client, adminIdentity.preferredUserId, {
      context: 'suspension',
      contentRef: targetId,
      verdict: suspended ? (state.permanent ? 'suspended_permanently' : 'suspended') : 'unsuspended',
      metadataPreview: reason,
    })
    return insertAdminNotification(client, adminIdentity, {
      recipientId: targetId,
      type: suspended ? 'account_suspended' : 'account_enabled',
      targetId,
      targetType: 'profile',
      content: suspended
        ? `Tu cuenta fue suspendida. Motivo: ${reason}`
        : 'Tu cuenta fue reactivada por el equipo de administracion.',
      extraData: {
        notification_type: suspended ? 'account_suspended' : 'account_enabled',
        suspended_until: state.suspendedUntil,
        permanent: state.permanent,
        suspension_reason: suspended ? reason : null,
      },
    })
  })

  dispatchAdminNotification(pool, notification)
  void publishRealtimeEvent('admin:live', {
    event: 'moderation',
    action: suspended ? 'suspension' : 'unsuspension',
    userId: targetId,
  }).catch(() => {})
  return {
    ok: true,
    is_suspended: suspended,
    suspended_until: state.suspendedUntil,
    suspension_end_date: state.suspendedUntil,
    permanent: state.permanent,
  }
}

async function videoReportForUpdate(client, reportId) {
  const id = validUuidOrNull(reportId)
  if (!id) throw httpError('REPORT_ID_REQUIRED', 400)
  const { rows } = await client.query(
    `SELECT id::text, video_id, video_owner_id, video_owner_name, video_title,
            reporter_id, reporter_name, reason, details, status, warn_sent,
            strike_applied, action_note, reviewed_at, created_at, updated_at
     FROM fallback_video_reports
     WHERE id = $1::uuid
     FOR UPDATE`,
    [id],
  )
  if (!rows[0]) throw httpError('REPORT_NOT_FOUND', 404)
  return rows[0]
}

async function adminUpdateVideoReportStatusRpc(pool, auth, body) {
  const adminIdentity = await requireAdminIdentity(pool, auth)
  const reportId = validUuidOrNull(body.p_report_id || body.report_id)
  if (!reportId) throw httpError('REPORT_ID_REQUIRED', 400)
  const status = normalizeIdentityValue(body.p_status || body.status || '')
  if (!VIDEO_REPORT_STATUSES.has(status)) throw httpError('INVALID_REPORT_STATUS', 400)
  const actionNote = String(body.p_action_note || body.action_note || '').trim().slice(0, 1000) || null

  const report = await runInTransaction(pool, async (client) => {
    await videoReportForUpdate(client, reportId)
    const { rows } = await client.query(
      `UPDATE fallback_video_reports
       SET status = $2, action_note = COALESCE($3, action_note),
           reviewed_at = NOW(), updated_at = NOW()
       WHERE id = $1::uuid
       RETURNING *`,
      [reportId, status, actionNote],
    )
    await recordModerationEvent(client, adminIdentity.preferredUserId, {
      context: 'video_report_status',
      contentRef: reportId,
      verdict: status,
      metadataPreview: actionNote || '',
    })
    return rows[0]
  })
  void publishRealtimeEvent('admin:live', {
    event: 'moderation',
    action: 'video_report_status',
    reportId,
  }).catch(() => {})
  return { ok: true, report }
}

async function adminWarnVideoOwnerRpc(pool, auth, body) {
  const adminIdentity = await requireAdminIdentity(pool, auth)
  const reportId = validUuidOrNull(body.p_report_id || body.report_id)
  if (!reportId) throw httpError('REPORT_ID_REQUIRED', 400)
  const warning = String(
    body.p_message || body.message ||
      'Tu video fue reportado por incumplir las normas. Revisa el contenido para evitar nuevas sanciones.',
  ).trim().slice(0, 700)
  if (!warning) throw httpError('WARNING_MESSAGE_REQUIRED', 400)

  const result = await runInTransaction(pool, async (client) => {
    const report = await videoReportForUpdate(client, reportId)
    const owner = await resolveFallbackUser(client, report.video_owner_id)
    if (!owner) throw httpError('VIDEO_OWNER_NOT_FOUND', 404)
    const notification = await insertAdminNotification(client, adminIdentity, {
      recipientId: owner.public_user_id || owner.local_user_id,
      type: 'admin_warning_video',
      targetId: report.video_id,
      targetType: 'video',
      content: warning,
      extraData: {
        notification_type: 'admin_warning_video',
        report_id: report.id,
        video_id: report.video_id,
      },
    })
    const { rows } = await client.query(
      `UPDATE fallback_video_reports
       SET warn_sent = TRUE, status = 'advertido', action_note = $2,
           reviewed_at = NOW(), updated_at = NOW()
       WHERE id = $1::uuid
       RETURNING *`,
      [reportId, warning],
    )
    await recordModerationEvent(client, adminIdentity.preferredUserId, {
      context: 'video_report',
      contentRef: reportId,
      verdict: 'owner_warned',
      metadataPreview: warning,
    })
    return { notification, report: rows[0] }
  })
  dispatchAdminNotification(pool, result.notification)
  void publishRealtimeEvent('admin:live', {
    event: 'moderation', action: 'video_owner_warned', reportId,
  }).catch(() => {})
  return { ok: true, report: result.report }
}

async function adminSuspendVideoOwnerFromReportRpc(pool, auth, body) {
  const adminIdentity = await requireAdminIdentity(pool, auth)
  const reportId = validUuidOrNull(body.p_report_id || body.report_id)
  if (!reportId) throw httpError('REPORT_ID_REQUIRED', 400)
  const { rows } = await pool.query(
    `SELECT id::text, video_id, video_owner_id
     FROM fallback_video_reports
     WHERE id = $1::uuid
     LIMIT 1`,
    [reportId],
  )
  const report = rows[0]
  if (!report) throw httpError('REPORT_NOT_FOUND', 404)
  const reason = String(
    body.p_reason || body.reason || 'Incumplimiento de normas detectado en un video reportado.',
  ).trim().slice(0, 500)
  const suspension = await adminSetUserSuspensionRpc(pool, auth, {
    ...body,
    p_user_id: report.video_owner_id,
    p_suspend: true,
    p_reason: reason,
    p_days: body.p_days ?? body.days ?? 7,
  })
  const updatedReport = await runInTransaction(pool, async (client) => {
    await videoReportForUpdate(client, reportId)
    const result = await client.query(
      `UPDATE fallback_video_reports
       SET strike_applied = TRUE, status = 'accion_aplicada', action_note = $2,
           reviewed_at = NOW(), updated_at = NOW()
       WHERE id = $1::uuid
       RETURNING *`,
      [reportId, reason],
    )
    await recordModerationEvent(client, adminIdentity.preferredUserId, {
      context: 'video_report',
      contentRef: reportId,
      verdict: 'owner_suspended',
      metadataPreview: reason,
    })
    return result.rows[0]
  })
  return { ...suspension, report: updatedReport }
}

async function ownedStorageRowsForMedia(client, mediaUrls, ownerLocalUserId) {
  if (!validUuidOrNull(ownerLocalUserId)) return []
  const found = new Map()
  for (const mediaUrl of mediaUrls) {
    const location = publicStorageLocation(mediaUrl)
    if (!location) continue
    const { rows } = await client.query(
      `SELECT *, id::text
       FROM fallback_storage_objects
       WHERE bucket_id = $1 AND name = $2 AND owner_id = $3::uuid
       LIMIT 1`,
      [location.bucket, location.objectPath, ownerLocalUserId],
    )
    if (rows[0]) found.set(rows[0].id, rows[0])
  }
  return Array.from(found.values())
}

async function adminDeleteReportedVideoRpc(pool, auth, body) {
  const adminIdentity = await requireAdminIdentity(pool, auth)
  const reportId = validUuidOrNull(body.p_report_id || body.report_id)
  if (!reportId) throw httpError('REPORT_ID_REQUIRED', 400)
  const actionNote = String(
    body.p_action_note || body.action_note || 'Video eliminado por moderacion.',
  ).trim().slice(0, 1000)

  const result = await runInTransaction(pool, async (client) => {
    const report = await videoReportForUpdate(client, reportId)
    const videoId = normalizeIdentityValue(report.video_id)
    if (!videoId) throw httpError('VIDEO_ID_REQUIRED', 400)
    const localResult = await client.query(
      `SELECT id::text, user_id, video_url, cover_url, thumbnail_url
       FROM fallback_videos
       WHERE lower(id::text) = $1
       LIMIT 1
       FOR UPDATE`,
      [videoId],
    )
    const compatVideo = await findCompatEntity(client, 'videos', videoId)
    const video = localResult.rows[0] || compatVideo
    const deletion = await client.query(
      `SELECT 1 FROM fallback_video_deletions WHERE lower(video_id) = $1 LIMIT 1`,
      [videoId],
    )
    if (!video && !deletion.rows[0]) throw httpError('VIDEO_NOT_FOUND', 404)

    const owner = await resolveFallbackUser(client, video?.user_id || report.video_owner_id)
    const mediaRows = await ownedStorageRowsForMedia(
      client,
      [video?.video_url, video?.cover_url, video?.thumbnail_url].filter(Boolean),
      owner?.local_user_id,
    )

    await client.query(
      `INSERT INTO fallback_video_deletions(video_id, user_id, deleted_at)
       VALUES ($1, $2, NOW())
       ON CONFLICT (video_id) DO UPDATE SET user_id = EXCLUDED.user_id, deleted_at = NOW()`,
      [videoId, adminIdentity.preferredUserId],
    )
    await client.query(
      `DELETE FROM fallback_video_comment_reactions
       WHERE comment_id IN (
         SELECT id::text FROM fallback_video_comments WHERE lower(video_id) = $1
       )`,
      [videoId],
    )
    for (const statement of [
      `DELETE FROM fallback_video_likes WHERE lower(video_id) = $1`,
      `DELETE FROM fallback_video_comments WHERE lower(video_id) = $1`,
      `DELETE FROM fallback_video_favorites WHERE lower(video_id) = $1`,
      `DELETE FROM fallback_video_views WHERE lower(video_id) = $1`,
      `DELETE FROM fallback_video_comment_reports WHERE lower(video_id) = $1`,
      `DELETE FROM fallback_shares WHERE content_type = 'video' AND lower(post_id) = $1`,
      `DELETE FROM fallback_video_metrics WHERE lower(video_id) = $1`,
      `DELETE FROM fallback_videos WHERE lower(id::text) = $1`,
    ]) {
      await client.query(statement, [videoId])
    }
    await client.query(
      `DELETE FROM fallback_compat_rows
       WHERE (table_name = 'videos' AND lower(COALESCE(row->>'id', row_id)) = $1)
          OR (table_name IN ('video_likes', 'video_comments', 'video_favorites', 'video_views')
              AND lower(COALESCE(row->>'video_id', '')) = $1)
          OR (table_name = 'shares'
              AND lower(COALESCE(row->>'content_type', '')) = 'video'
              AND lower(COALESCE(row->>'video_id', row->>'content_id', row->>'post_id', '')) = $1)`,
      [videoId],
    )
    if (mediaRows.length > 0) {
      await client.query(
        `DELETE FROM fallback_storage_objects WHERE id = ANY($1::uuid[])`,
        [mediaRows.map((row) => row.id)],
      )
    }

    const notification = owner
      ? await insertAdminNotification(client, adminIdentity, {
          recipientId: owner.public_user_id || owner.local_user_id,
          type: 'admin_video_deleted',
          targetId: videoId,
          targetType: 'video',
          content: actionNote,
          extraData: {
            notification_type: 'admin_video_deleted',
            report_id: report.id,
            video_id: videoId,
          },
        })
      : null
    const { rows } = await client.query(
      `UPDATE fallback_video_reports
       SET status = 'eliminado', action_note = $2, reviewed_at = NOW(), updated_at = NOW()
       WHERE id = $1::uuid
       RETURNING *`,
      [reportId, actionNote],
    )
    await recordModerationEvent(client, adminIdentity.preferredUserId, {
      context: 'video_report',
      contentRef: reportId,
      verdict: deletion.rows[0] ? 'video_already_deleted' : 'video_deleted',
      metadataPreview: actionNote,
    })
    return { mediaRows, notification, report: rows[0], videoId }
  })

  const cleanup = await removeStorageObjectFiles(result.mediaRows)
  if (cleanup.failures.length > 0) {
    console.warn('[admin] deleted video has storage cleanup failures', { count: cleanup.failures.length })
  }
  dispatchAdminNotification(pool, result.notification)
  void publishRealtimeEvent('admin:live', {
    event: 'moderation', action: 'reported_video_deleted', reportId, videoId: result.videoId,
  }).catch(() => {})
  return {
    ok: true,
    report: result.report,
    media_removed: cleanup.removed.length,
    media_cleanup_failures: cleanup.failures.length,
  }
}

const IDENTITY_REFERENCE_FIELDS = [
  'actor_id',
  'author_id',
  'blocked_id',
  'blocked_user_id',
  'blocker_id',
  'creator_id',
  'follower_id',
  'following_id',
  'forwarded_from_user_id',
  'from_user_id',
  'host_user_id',
  'member_id',
  'owner_id',
  'profile_id',
  'receiver_id',
  'recipient_id',
  'reported_user_id',
  'reporter_id',
  'requester_id',
  'sender_id',
  'target_user_id',
  'user_a',
  'user_b',
  'user_id',
  'voter_id',
]

const IDENTITY_ARRAY_FIELDS = [
  'excluded_ids',
  'member_ids',
  'participant_ids',
  'participants',
  'user_ids',
]

async function purgeImportedIdentityRows(client, identityValues) {
  return purgeImportedRows(client, identityValues, 'identity')
}

async function purgeCompatIdentityRows(client, identityValues) {
  const patterns = identityValues.map((value) => `%${value}%`)
  const result = await client.query(
    `DELETE FROM fallback_compat_rows c
     WHERE lower(c.row_id) = ANY($1::text[])
        OR EXISTS (
          SELECT 1
          -- Keep unrelated payloads as JSONB. Expanding every body/media value
          -- to text twice made account cleanup stall on legacy chat rows.
          FROM jsonb_each(c.row) AS field(key, value)
          WHERE (field.key = ANY($2::text[])
                 AND lower(field.value #>> '{}') = ANY($1::text[]))
             OR (field.key = ANY($3::text[])
                 AND lower(field.value::text) LIKE ANY($4::text[]))
        )`,
    [identityValues, IDENTITY_REFERENCE_FIELDS, IDENTITY_ARRAY_FIELDS, patterns],
  )
  return Number(result.rowCount) || 0
}

async function deleteOwnAccountRpc(pool, auth) {
  const identity = await getAuthIdentity(pool, auth)
  if (!identity) throw httpError('AUTH_REQUIRED', 401)
  const identityValues = Array.from(new Set(identity.values.map(normalizeIdentityValue).filter(Boolean)))
  const localUserId = validUuidOrNull(identity.localUserId)
  if (!localUserId) throw httpError('INVALID_USER_ID', 400)

  const result = await runInTransaction(pool, async (client) => {
    const lockedUser = await client.query(
      `SELECT id::text, NULLIF(supabase_user_id::text, '') AS public_user_id
       FROM fallback_users
       WHERE id = $1::uuid
       FOR UPDATE`,
      [localUserId],
    )
    if (!lockedUser.rows[0]) throw httpError('USER_NOT_FOUND', 404)

    const storage = await client.query(
      `SELECT *, id::text
       FROM fallback_storage_objects
       WHERE owner_id = $1::uuid
       FOR UPDATE`,
      [localUserId],
    )
    const chatIdsResult = await client.query(
      `SELECT DISTINCT c.id::text
       FROM fallback_chats c
       LEFT JOIN fallback_chat_participants p ON p.chat_id = c.id
       WHERE EXISTS (
         SELECT 1 FROM unnest(c.participants) participant
         WHERE lower(participant) = ANY($1::text[])
       ) OR lower(COALESCE(p.user_id, '')) = ANY($1::text[])`,
      [identityValues],
    )
    const roomIdsResult = await client.query(
      `SELECT id::text FROM fallback_collection_rooms
       WHERE lower(creator_id) = ANY($1::text[])`,
      [identityValues],
    )
    const voiceRoomIdsResult = await client.query(
      `SELECT id::text FROM fallback_voice_rooms
       WHERE lower(creator_id) = ANY($1::text[])`,
      [identityValues],
    )
    const postIdsResult = await client.query(
      `SELECT id::text FROM fallback_posts WHERE lower(user_id) = ANY($1::text[])`,
      [identityValues],
    )
    const videoIdsResult = await client.query(
      `SELECT id::text FROM fallback_videos WHERE lower(user_id) = ANY($1::text[])`,
      [identityValues],
    )
    const momentIdsResult = await client.query(
      `SELECT id::text FROM fallback_moments WHERE lower(user_id) = ANY($1::text[])`,
      [identityValues],
    )
    const liveIdsResult = await client.query(
      `SELECT id::text FROM fallback_live_streams WHERE lower(host_user_id) = ANY($1::text[])`,
      [identityValues],
    )
    const submissionIdsResult = await client.query(
      `SELECT id::text FROM fallback_challenge_submissions WHERE lower(user_id) = ANY($1::text[])`,
      [identityValues],
    )
    const randomSessionIdsResult = await client.query(
      `SELECT id::text FROM fallback_random_chat_sessions
       WHERE lower(user_a) = ANY($1::text[]) OR lower(user_b) = ANY($1::text[])`,
      [identityValues],
    )

    const chatIds = chatIdsResult.rows.map((row) => normalizeIdentityValue(row.id)).filter(Boolean)
    const roomIds = roomIdsResult.rows.map((row) => normalizeIdentityValue(row.id)).filter(Boolean)
    const voiceRoomIds = voiceRoomIdsResult.rows.map((row) => normalizeIdentityValue(row.id)).filter(Boolean)
    const postIds = postIdsResult.rows.map((row) => normalizeIdentityValue(row.id)).filter(Boolean)
    const videoIds = videoIdsResult.rows.map((row) => normalizeIdentityValue(row.id)).filter(Boolean)
    const momentIds = momentIdsResult.rows.map((row) => normalizeIdentityValue(row.id)).filter(Boolean)
    const liveIds = liveIdsResult.rows.map((row) => normalizeIdentityValue(row.id)).filter(Boolean)
    const submissionIds = submissionIdsResult.rows.map((row) => normalizeIdentityValue(row.id)).filter(Boolean)
    const randomSessionIds = randomSessionIdsResult.rows.map((row) => normalizeIdentityValue(row.id)).filter(Boolean)

    for (const userId of identityValues) {
      await client.query(
        `INSERT INTO fallback_deleted_identities(user_id, deleted_at)
         VALUES ($1, NOW())
         ON CONFLICT (user_id) DO UPDATE SET deleted_at = NOW()`,
        [userId],
      )
    }
    for (const videoId of videoIds) {
      await client.query(
        `INSERT INTO fallback_video_deletions(video_id, user_id, deleted_at)
         VALUES ($1, $2, NOW())
         ON CONFLICT (video_id) DO UPDATE SET user_id = EXCLUDED.user_id, deleted_at = NOW()`,
        [videoId, identity.preferredUserId],
      )
    }
    for (const momentId of momentIds) {
      await client.query(
        `INSERT INTO fallback_moment_deletions(moment_id, user_id, deleted_at)
         VALUES ($1, $2, NOW())
         ON CONFLICT (moment_id) DO UPDATE SET user_id = EXCLUDED.user_id, deleted_at = NOW()`,
        [momentId, identity.preferredUserId],
      )
    }

    if (postIds.length > 0) {
      await client.query(
        `DELETE FROM fallback_post_comment_reactions
         WHERE comment_id IN (SELECT id::text FROM fallback_post_comments WHERE lower(post_id) = ANY($1::text[]))`,
        [postIds],
      )
      await client.query(`DELETE FROM fallback_post_comments WHERE lower(post_id) = ANY($1::text[])`, [postIds])
      await client.query(`DELETE FROM fallback_post_reactions WHERE lower(post_id) = ANY($1::text[])`, [postIds])
      await client.query(`DELETE FROM fallback_shares WHERE content_type = 'post' AND lower(post_id) = ANY($1::text[])`, [postIds])
      await client.query(`DELETE FROM fallback_posts WHERE lower(id::text) = ANY($1::text[])`, [postIds])
    }
    if (videoIds.length > 0) {
      await client.query(
        `DELETE FROM fallback_video_comment_reactions
         WHERE comment_id IN (SELECT id::text FROM fallback_video_comments WHERE lower(video_id) = ANY($1::text[]))`,
        [videoIds],
      )
      for (const statement of [
        `DELETE FROM fallback_video_likes WHERE lower(video_id) = ANY($1::text[])`,
        `DELETE FROM fallback_video_comments WHERE lower(video_id) = ANY($1::text[])`,
        `DELETE FROM fallback_video_favorites WHERE lower(video_id) = ANY($1::text[])`,
        `DELETE FROM fallback_video_views WHERE lower(video_id) = ANY($1::text[])`,
        `DELETE FROM fallback_video_comment_reports WHERE lower(video_id) = ANY($1::text[])`,
        `DELETE FROM fallback_shares WHERE content_type = 'video' AND lower(post_id) = ANY($1::text[])`,
        `DELETE FROM fallback_video_metrics WHERE lower(video_id) = ANY($1::text[])`,
        `DELETE FROM fallback_videos WHERE lower(id::text) = ANY($1::text[])`,
      ]) {
        await client.query(statement, [videoIds])
      }
    }
    if (momentIds.length > 0) {
      await client.query(`DELETE FROM fallback_moment_views WHERE lower(moment_id::text) = ANY($1::text[])`, [momentIds])
      await client.query(`DELETE FROM fallback_moments WHERE lower(id::text) = ANY($1::text[])`, [momentIds])
    }
    if (submissionIds.length > 0) {
      await client.query(`DELETE FROM fallback_challenge_votes WHERE lower(submission_id) = ANY($1::text[])`, [submissionIds])
      await client.query(`DELETE FROM fallback_challenge_submissions WHERE lower(id::text) = ANY($1::text[])`, [submissionIds])
    }
    if (liveIds.length > 0) {
      await client.query(`DELETE FROM fallback_live_streams WHERE lower(id::text) = ANY($1::text[])`, [liveIds])
    }
    if (randomSessionIds.length > 0) {
      await client.query(`DELETE FROM fallback_random_chat_sessions WHERE lower(id::text) = ANY($1::text[])`, [randomSessionIds])
    }
    const markerRoomIds = [...chatIds, ...roomIds]
    for (const statement of [
      `DELETE FROM fallback_saved_messages WHERE lower(user_id) = ANY($1::text[]) OR lower(room_id) = ANY($2::text[])
        OR lower(message_id) IN (SELECT lower(id::text) FROM fallback_messages WHERE lower(sender_id) = ANY($1::text[])
          UNION SELECT lower(id::text) FROM fallback_room_messages WHERE lower(sender_id) = ANY($1::text[]))`,
      `DELETE FROM fallback_message_pins WHERE lower(pinned_by) = ANY($1::text[]) OR lower(room_id) = ANY($2::text[])
        OR lower(message_id) IN (SELECT lower(id::text) FROM fallback_messages WHERE lower(sender_id) = ANY($1::text[])
          UNION SELECT lower(id::text) FROM fallback_room_messages WHERE lower(sender_id) = ANY($1::text[]))`,
      `DELETE FROM fallback_message_pin_events WHERE lower(actor_id) = ANY($1::text[]) OR lower(room_id) = ANY($2::text[])
        OR lower(message_id) IN (SELECT lower(id::text) FROM fallback_messages WHERE lower(sender_id) = ANY($1::text[])
          UNION SELECT lower(id::text) FROM fallback_room_messages WHERE lower(sender_id) = ANY($1::text[]))`,
    ]) await client.query(statement, [identityValues, markerRoomIds])
    if (chatIds.length > 0) {
      await client.query(`DELETE FROM fallback_messages WHERE lower(chat_id) = ANY($1::text[])`, [chatIds])
      await client.query(`DELETE FROM fallback_chat_hidden WHERE lower(chat_id::text) = ANY($1::text[])`, [chatIds])
      await client.query(`DELETE FROM fallback_chats WHERE lower(id::text) = ANY($1::text[])`, [chatIds])
    }
    if (roomIds.length > 0) {
      await client.query(`DELETE FROM fallback_room_messages WHERE lower(room_id) = ANY($1::text[])`, [roomIds])
      await client.query(`DELETE FROM fallback_collection_message_receipts WHERE lower(room_id) = ANY($1::text[])`, [roomIds])
      await client.query(`DELETE FROM fallback_collection_members WHERE lower(room_id) = ANY($1::text[])`, [roomIds])
      await client.query(`DELETE FROM fallback_collection_invites WHERE lower(room_id) = ANY($1::text[])`, [roomIds])
      await client.query(`DELETE FROM fallback_collection_read_states WHERE lower(room_id) = ANY($1::text[])`, [roomIds])
      await client.query(`DELETE FROM fallback_collection_rooms WHERE lower(id::text) = ANY($1::text[])`, [roomIds])
    }
    if (voiceRoomIds.length > 0) {
      await client.query(`DELETE FROM fallback_voice_room_participants WHERE lower(room_id) = ANY($1::text[])`, [voiceRoomIds])
      await client.query(`DELETE FROM fallback_voice_rooms WHERE lower(id::text) = ANY($1::text[])`, [voiceRoomIds])
    }

    const identityDeleteStatements = [
      `DELETE FROM fallback_post_comment_reactions WHERE lower(user_id) = ANY($1::text[])`,
      `DELETE FROM fallback_post_comments WHERE lower(user_id) = ANY($1::text[])`,
      `DELETE FROM fallback_post_reactions WHERE lower(user_id) = ANY($1::text[])`,
      `DELETE FROM fallback_saved_posts WHERE lower(user_id) = ANY($1::text[])`,
      `DELETE FROM fallback_saved_collections WHERE lower(user_id) = ANY($1::text[])`,
      `DELETE FROM fallback_shares WHERE lower(user_id) = ANY($1::text[])`,
      `DELETE FROM fallback_follows WHERE lower(follower_id) = ANY($1::text[]) OR lower(following_id) = ANY($1::text[])`,
      `DELETE FROM fallback_reports WHERE lower(reporter_id) = ANY($1::text[]) OR lower(reported_user_id) = ANY($1::text[])`,
      `DELETE FROM fallback_notifications WHERE lower(recipient_id) = ANY($1::text[]) OR lower(COALESCE(actor_id, '')) = ANY($1::text[])`,
      `DELETE FROM fallback_notification_digest_preferences WHERE lower(user_id) = ANY($1::text[])`,
      `DELETE FROM fallback_fcm_tokens WHERE lower(user_id) = ANY($1::text[])`,
      `DELETE FROM fallback_messages WHERE lower(sender_id) = ANY($1::text[]) OR lower(COALESCE(forwarded_from_user_id, '')) = ANY($1::text[])`,
      `DELETE FROM fallback_room_messages WHERE lower(sender_id) = ANY($1::text[]) OR lower(COALESCE(forwarded_from_user_id, '')) = ANY($1::text[])`,
      `DELETE FROM fallback_collection_message_receipts WHERE lower(user_id) = ANY($1::text[])`,
      `DELETE FROM fallback_collection_members WHERE lower(user_id) = ANY($1::text[])`,
      `DELETE FROM fallback_collection_read_states WHERE lower(user_id) = ANY($1::text[])`,
      `DELETE FROM fallback_collection_invites WHERE lower(created_by) = ANY($1::text[])`,
      `DELETE FROM fallback_voice_room_participants WHERE lower(user_id) = ANY($1::text[])`,
      `DELETE FROM fallback_video_comment_reactions WHERE lower(user_id) = ANY($1::text[])`,
      `DELETE FROM fallback_video_comments WHERE lower(user_id) = ANY($1::text[])`,
      `DELETE FROM fallback_video_likes WHERE lower(user_id) = ANY($1::text[])`,
      `DELETE FROM fallback_video_favorites WHERE lower(user_id) = ANY($1::text[])`,
      `DELETE FROM fallback_video_collections WHERE lower(user_id) = ANY($1::text[])`,
      `DELETE FROM fallback_video_views WHERE lower(user_id) = ANY($1::text[])`,
      `DELETE FROM fallback_video_playback_history WHERE lower(user_id) = ANY($1::text[])`,
      `DELETE FROM fallback_video_watch_later WHERE lower(user_id) = ANY($1::text[])`,
      `DELETE FROM fallback_video_comment_reports WHERE lower(reporter_id) = ANY($1::text[])`,
      `DELETE FROM fallback_challenge_votes WHERE lower(voter_id) = ANY($1::text[])`,
      `DELETE FROM fallback_challenge_submissions WHERE lower(user_id) = ANY($1::text[])`,
      `DELETE FROM fallback_friends WHERE lower(sender_id) = ANY($1::text[]) OR lower(receiver_id) = ANY($1::text[])`,
      `DELETE FROM fallback_moment_views WHERE lower(user_id) = ANY($1::text[])`,
      `DELETE FROM fallback_live_comments WHERE lower(user_id) = ANY($1::text[])`,
      `DELETE FROM fallback_proximity_presence WHERE lower(user_id) = ANY($1::text[])`,
      `DELETE FROM fallback_random_bottle_availability WHERE lower(user_id) = ANY($1::text[])`,
      `DELETE FROM fallback_random_chat_bottles WHERE lower(sender_id) = ANY($1::text[]) OR lower(COALESCE(current_recipient_id, '')) = ANY($1::text[])`,
      `DELETE FROM fallback_random_chat_queue WHERE lower(user_id) = ANY($1::text[])`,
      `DELETE FROM fallback_random_chat_messages WHERE lower(sender_id) = ANY($1::text[])`,
      `DELETE FROM fallback_chat_participants WHERE lower(user_id) = ANY($1::text[])`,
      `DELETE FROM fallback_chat_hidden WHERE lower(user_id) = ANY($1::text[])`,
      `DELETE FROM fallback_nicknames WHERE lower(user_id) = ANY($1::text[]) OR lower(target_user_id) = ANY($1::text[])`,
      `DELETE FROM fallback_user_blocks WHERE lower(blocker_id) = ANY($1::text[]) OR lower(blocked_id) = ANY($1::text[])`,
      `DELETE FROM fallback_moderation_appeals WHERE lower(user_id) = ANY($1::text[]) OR decision_id IN (SELECT id FROM fallback_moderation_decisions WHERE lower(user_id) = ANY($1::text[]))`,
      `DELETE FROM fallback_moderation_decisions WHERE lower(user_id) = ANY($1::text[])`,
      `DELETE FROM fallback_profile_moderation WHERE lower(user_id) = ANY($1::text[])`,
      `DELETE FROM fallback_video_reports WHERE lower(reporter_id) = ANY($1::text[]) OR lower(video_owner_id) = ANY($1::text[])`,
      `DELETE FROM fallback_view_once_consumptions WHERE lower(user_id) = ANY($1::text[])`,
    ]
    for (const statement of identityDeleteStatements) {
      await client.query(statement, [identityValues])
    }

    await client.query('DELETE FROM fallback_question_votes WHERE lower(user_id)=ANY($1::text[])',[identityValues])
    await client.query('DELETE FROM fallback_question_answers WHERE lower(user_id)=ANY($1::text[])',[identityValues])
    await client.query('DELETE FROM fallback_community_questions WHERE lower(creator_id)=ANY($1::text[])',[identityValues])
    await client.query('DELETE FROM fallback_social_promotions WHERE lower(created_by)=ANY($1::text[])',[identityValues])
    const musicTracksDeleted = await deleteAccountMusic(client, localUserId)
    const importedRowsDeleted = await purgeImportedIdentityRows(client, identityValues)
    const compatRowsDeleted = await purgeCompatIdentityRows(client, identityValues)
    if (storage.rows.length > 0) {
      await client.query(`DELETE FROM fallback_storage_objects WHERE owner_id = $1::uuid`, [localUserId])
    }
    await client.query(
      `INSERT INTO fallback_audit_events(event_name, user_id, metadata)
       VALUES ('account_deleted', $1::uuid, $2::jsonb)`,
      [localUserId, JSON.stringify({ importedRowsDeleted, compatRowsDeleted, musicTracksDeleted })],
    )

    const restoredAuthId = validUuidOrNull(lockedUser.rows[0].public_user_id)
    if (restoredAuthId) {
      const authTable = await client.query(`SELECT to_regclass('auth.users') AS oid`)
      if (authTable.rows[0]?.oid) {
        await client.query('SAVEPOINT delete_restored_auth_user')
        try {
          await client.query(`DELETE FROM auth.users WHERE id = $1::uuid`, [restoredAuthId])
          await client.query('RELEASE SAVEPOINT delete_restored_auth_user')
        } catch (error) {
          await client.query('ROLLBACK TO SAVEPOINT delete_restored_auth_user')
          await client.query('RELEASE SAVEPOINT delete_restored_auth_user')
          console.warn('[account-delete] restored auth cleanup deferred', safeErrorLogDetails(error))
        }
      }
    }
    const deleted = await client.query(`DELETE FROM fallback_users WHERE id = $1::uuid`, [localUserId])
    if (deleted.rowCount !== 1) throw httpError('ACCOUNT_DELETE_CONFLICT', 409)

    return {
      compatRowsDeleted,
      importedRowsDeleted,
      storageRows: storage.rows,
    }
  })

  invalidateImportedDataCache()
  const cleanup = await removeStorageObjectFiles(result.storageRows)
  if (cleanup.failures.length > 0) {
    console.warn('[account-delete] storage cleanup failures', { count: cleanup.failures.length })
  }
  void publishRealtimeEvent(`user:${identity.preferredUserId}`, {
    event: 'account_deleted',
  }).catch(() => {})
  return {
    ok: true,
    deleted: true,
    media_removed: cleanup.removed.length,
    media_cleanup_failures: cleanup.failures.length,
  }
}

async function submitReportRpc(pool, auth, body, videoReport) {
  const identity = await getAuthIdentity(pool, auth)
  if (!identity) throw httpError('AUTH_REQUIRED', 401)
  const reason = String(messageBodyValue(body, 'reason', '') || '').trim().slice(0, 200)
  if (!reason) throw httpError('REPORT_REASON_REQUIRED', 400)

  if (!videoReport) {
    const targetId = normalizeIdentityValue(messageBodyValue(body, 'reported_user_id', ''))
    if (!targetId) throw httpError('REPORTED_USER_REQUIRED', 400)
    if (identityOwnsValue(identity, targetId)) throw httpError('SELF_REPORT_FORBIDDEN')
    const target = await resolveFallbackUser(pool, targetId)
    if (!target) throw httpError('REPORTED_USER_NOT_FOUND', 404)
    const evidenceUrl = String(messageBodyValue(body, 'evidence_url', '') || '').trim().slice(0, 2000) || null
    if (!(await canUseReportEvidence(pool, identity, evidenceUrl))) {
      throw httpError('REPORT_EVIDENCE_STORAGE_OWNERSHIP_REQUIRED')
    }
    const { rows } = await pool.query(
      `INSERT INTO fallback_reports(
         reporter_id, reporter_name, reported_user_id, reported_user_name,
         reason, description, evidence_url, status
       ) VALUES ($1, $2, $3, $4, $5, $6, $7, 'pendiente')
       RETURNING id::text, reporter_id, reporter_name, reported_user_id, reported_user_name,
                 reason, description, evidence_url, status, created_at, updated_at`,
      [
        identity.preferredUserId,
        identity.displayName,
        target.public_user_id,
        target.display_name || target.email || 'Usuario',
        reason,
        String(messageBodyValue(body, 'description', '') || '').trim().slice(0, 2000) || null,
        evidenceUrl,
      ],
    )
    return rows[0]
  }

  const videoId = normalizeIdentityValue(messageBodyValue(body, 'video_id', ''))
  if (!videoId) throw httpError('VIDEO_ID_REQUIRED', 400)
  const videoResult = await pool.query(
    `SELECT id::text, user_id, user_name, title FROM fallback_videos WHERE lower(id::text) = $1 LIMIT 1`,
    [videoId],
  )
  const video = videoResult.rows[0] || (await findCompatEntity(pool, 'videos', videoId))
  if (!video) throw httpError('VIDEO_NOT_FOUND', 404)
  if (identityOwnsValue(identity, video.user_id)) throw httpError('SELF_REPORT_FORBIDDEN')
  const duplicate = await pool.query(
    `SELECT 1 FROM fallback_video_reports
     WHERE lower(video_id) = $1 AND lower(reporter_id) = ANY($2::text[])
     LIMIT 1`,
    [videoId, identity.values],
  )
  if (duplicate.rows[0]) throw httpError('VIDEO_ALREADY_REPORTED', 409)
  const { rows } = await pool.query(
    `INSERT INTO fallback_video_reports(
       video_id, video_owner_id, video_owner_name, video_title,
       reporter_id, reporter_name, reason, details, status
     ) VALUES ($1, $2, $3, $4, $5, $6, $7, $8, 'pendiente')
     RETURNING *`,
    [
      videoId,
      video.user_id || '',
      video.user_name || 'Usuario',
      video.title || 'Video',
      identity.preferredUserId,
      identity.displayName,
      reason,
      String(messageBodyValue(body, 'details', '') || '').trim().slice(0, 2000) || null,
    ],
  )
  return rows[0]
}

async function handleRpc(pool, req, rpcName) {
  const body = req.body || {}
  const auth = req.auth || null

  switch (rpcName) {
    case 'is_current_user_admin':
      return Boolean(await requireAdmin(pool, auth))
    case 'resolve_login_email': {
      // Compatibility response contains only caller-supplied data. Local login
      // already resolves fallback_profile_login_emails inside password verification.
      return require('./login-identifier-privacy').publicLoginIdentifier(body)
    }
    case 'secure_get_my_suspension_state':
      {
        const identity = await getAuthIdentity(pool, auth)
        if (!identity) throw httpError('AUTH_REQUIRED', 401)
        const { rows } = await pool.query(
          `SELECT is_suspended, suspended_until, suspension_end_date, suspension_reason
           FROM fallback_profile_moderation
           WHERE lower(user_id) = ANY($1::text[])
           ORDER BY updated_at DESC
           LIMIT 1`,
          [identity.values],
        )
        const row = rows[0] || {}
        const until = row.suspended_until || row.suspension_end_date || null
        const active = row.is_suspended === true && (!until || new Date(until).getTime() > Date.now())
        return {
          is_suspended: active,
          suspended: active,
          reason: active ? row.suspension_reason || null : null,
          until: active ? until : null,
        }
      }
    case 'secure_send_message':
      return insertDirectRpcMessage(pool, auth, body)
    case 'secure_send_group_message':
      return insertCollectionRpcMessage(pool, auth, body, 'group')
    case 'secure_send_community_message':
      return insertCollectionRpcMessage(pool, auth, body, 'community')
    case 'secure_create_chat_poll_message':
      return createPollMessageRpc(pool, auth, body, 'chat')
    case 'secure_create_group_poll_message':
      return createPollMessageRpc(pool, auth, body, 'group')
    case 'secure_create_community_poll_message':
      return createPollMessageRpc(pool, auth, body, 'community')
    case 'get_or_generate_active_challenge': {
      const rows = await readTableRows(pool, 'active_challenges', {}, auth)
      const active = rows
        .filter((row) => ['active', 'voting', 'completed'].includes(String(row.status || '').toLowerCase()))
        .sort((a, b) => String(b.created_at || '').localeCompare(String(a.created_at || '')))
      return active[0] ? [active[0]] : []
    }
    case 'get_challenge_submissions_voted':
      return loadChallengeSubmissionsVoted(pool, auth, body.p_active_challenge_id)
    case 'toggle_challenge_vote':
    case 'v2026_challenge_vote':
    case 'challenge_v3_vote_toggle':
      return toggleChallengeVote(
        pool,
        auth,
        body.p_submission_id || body.p_id || body.target_id_text,
      )
    case 'secure_admin_set_user_suspension':
      return adminSetUserSuspensionRpc(pool, auth, body)
    case 'secure_admin_update_video_report_status':
      return adminUpdateVideoReportStatusRpc(pool, auth, body)
    case 'secure_admin_warn_video_owner_from_report':
      return adminWarnVideoOwnerRpc(pool, auth, body)
    case 'secure_suspend_video_owner_from_report':
      return adminSuspendVideoOwnerFromReportRpc(pool, auth, body)
    case 'secure_admin_delete_reported_video':
      return adminDeleteReportedVideoRpc(pool, auth, body)
    case 'secure_mark_messages_read':
      return markDirectRpcMessages(pool, auth, body, 'read')
    case 'secure_mark_messages_delivered':
      return markDirectRpcMessages(pool, auth, body, 'delivered')
    case 'secure_mark_group_messages_read':
      return markCollectionRpcMessages(pool, auth, body, 'group', 'read')
    case 'secure_mark_group_messages_delivered':
      return markCollectionRpcMessages(pool, auth, body, 'group', 'delivered')
    case 'secure_mark_community_messages_read':
      return markCollectionRpcMessages(pool, auth, body, 'community', 'read')
    case 'secure_mark_community_messages_delivered':
      return markCollectionRpcMessages(pool, auth, body, 'community', 'delivered')
    case 'secure_get_group_message_receipts':
      return collectionMessageReceipts(pool, auth, body, 'group')
    case 'secure_get_community_message_receipts':
      return collectionMessageReceipts(pool, auth, body, 'community')
    case 'secure_submit_report':
      return submitReportRpc(pool, auth, body, false)
    case 'secure_submit_video_report':
      return submitReportRpc(pool, auth, body, true)
    case 'delete_own_account':
      return deleteOwnAccountRpc(pool, auth)
    case 'secure_delete_post_comment':
      return deleteCommentRpc(pool, auth, body, 'post')
    case 'secure_delete_video_comment':
      return deleteCommentRpc(pool, auth, body, 'video')
    case 'update_user_online_status':
      return updateOnlineStatusRpc(pool, auth, body)
    case 'secure_mark_notifications_read':
      return markNotificationsReadRpc(pool, auth, body)
    case 'secure_consume_view_once_message':
      return consumeViewOnceRpc(pool, auth, body)
    case 'wallet_get_or_create': {
      const identity = await getAuthIdentity(pool, auth)
      if (!identity) throw httpError('AUTH_REQUIRED', 401)
      const wallet = await walletForIdentity(pool, identity)
      await storeCompatRow(pool, 'wallets', wallet)
      return wallet
    }
    case 'secure_get_poll_state': {
      const identity = await getAuthIdentity(pool, auth)
      if (!identity) throw httpError('AUTH_REQUIRED', 401)
      return buildPollState(pool, identity, messageBodyValue(body, 'poll_id', ''))
    }
    case 'secure_schedule_message_job':
      return scheduleMessageJob(pool, auth, body)
    case 'secure_get_chat_scheduled_jobs':
      return listScheduledMessageJobs(pool, auth, body)
    case 'secure_cancel_scheduled_message_job':
      return cancelScheduledMessageJob(pool, auth, body)
    case 'secure_vote_poll_option':
      return votePollOption(pool, auth, body)
    case 'secure_toggle_message_reaction':
      return toggleMessageReaction(pool, auth, body)
    case 'secure_toggle_group_message_reaction':
      return toggleMessageReaction(pool, auth, body, 'group')
    case 'secure_toggle_community_message_reaction':
      return toggleMessageReaction(pool, auth, body, 'community')
    case 'secure_set_chat_translation_settings':
      return setChatTranslationRpc(pool, auth, body)
    case 'increment_post_count':
      return refreshProfileContentCountRpc(pool, auth)
    case 'increment_video_views':
      return updateVideoCounterRpc(pool, auth, body, 'views')
    case 'increment_like_count':
      return updateVideoCounterRpc(pool, auth, body, 'like_count')
    case 'increment_share_count':
      return updateVideoCounterRpc(pool, auth, body, 'share_count')
    case 'register_media_object':
      return registerMediaObjectRpc(pool, auth, body)
    case 'secure_create_sticker_pack':
      return createStickerPackRpc(pool, auth, body)
    case 'secure_add_sticker_to_pack':
      return addStickerToPackRpc(pool, auth, body)
    case 'secure_toggle_sticker_favorite':
      return toggleStickerFavoriteRpc(pool, auth, body)
    case 'secure_search_stickers':
      return searchStickersRpc(pool, auth, body)
    case 'send_virtual_gift':
      return sendVirtualGiftRpc(pool, auth, body)
    case 'get_virtual_gift_receipt':
      return getVirtualGiftReceiptRpc(pool, auth, body)
    case 'billing_capabilities':
      return billingCapabilitiesRpc(pool, auth)
    case 'verify_google_play_purchase':
      return verifyGooglePlayPurchaseRpc(pool, auth, body)
    case 'subscribe_to_plan':
      return subscribeToPlanRpc(pool, auth, body)
    case 'secure_set_chat_preferences': {
      const authenticatedUserId = String(auth?.sub || '').trim()
      const chatId = String(body.p_chat_id || body.chat_id || '').trim().toLowerCase()
      if (!authenticatedUserId) throw new Error('AUTH_REQUIRED')
      if (!/^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(chatId)) {
        throw new Error('INVALID_CHAT_ID')
      }

      const { rows: userRows } = await pool.query(
        `SELECT id::text AS local_id, NULLIF(supabase_user_id::text, '') AS public_id
         FROM fallback_users
         WHERE lower(id::text) = lower($1)
            OR lower(COALESCE(supabase_user_id::text, '')) = lower($1)
         LIMIT 1`,
        [authenticatedUserId],
      )
      const userRow = userRows[0] || {}
      const aliases = [...new Set([
        authenticatedUserId,
        userRow.local_id,
        userRow.public_id,
      ].map((value) => String(value || '').trim()).filter(Boolean))]

      const { rows: chatRows } = await pool.query(
        `SELECT id::text, participants
         FROM fallback_chats
         WHERE id = $1::uuid
         LIMIT 1`,
        [chatId],
      )
      const chat = chatRows[0]
      if (!chat) throw new Error('CHAT_NOT_FOUND')

      const { rows: existingParticipantRows } = await pool.query(
        `SELECT user_id
         FROM fallback_chat_participants
         WHERE chat_id = $1::uuid
           AND lower(user_id) = ANY($2::text[])
         LIMIT 1`,
        [chatId, aliases.map((value) => value.toLowerCase())],
      )
      const participantFromChat = (chat.participants || []).find((participantId) =>
        aliases.some((alias) => alias.toLowerCase() === String(participantId || '').trim().toLowerCase()),
      )
      if (!existingParticipantRows[0] && !participantFromChat) throw new Error('CHAT_ACCESS_DENIED')

      const participantId = existingParticipantRows[0]?.user_id || participantFromChat || userRow.public_id || authenticatedUserId
      const supplied = (name) => Object.prototype.hasOwnProperty.call(body, name)
      const boolOrNull = (name) => {
        if (!supplied(name) || body[name] === null) return null
        if (body[name] === true || body[name] === 'true') return true
        if (body[name] === false || body[name] === 'false') return false
        throw new Error(`INVALID_BOOLEAN_${name.toUpperCase()}`)
      }
      const { rows: preferenceRows } = await pool.query(
        `INSERT INTO fallback_chat_participants(
           chat_id, user_id, is_muted, is_pinned, is_archived, is_favorite
         )
         VALUES ($1::uuid, $2, COALESCE($3::boolean, FALSE), COALESCE($4::boolean, FALSE),
                 COALESCE($5::boolean, FALSE), COALESCE($6::boolean, FALSE))
         ON CONFLICT (chat_id, user_id) DO UPDATE SET
           is_muted = COALESCE($3::boolean, fallback_chat_participants.is_muted),
           is_pinned = COALESCE($4::boolean, fallback_chat_participants.is_pinned),
           is_archived = COALESCE($5::boolean, fallback_chat_participants.is_archived),
           is_favorite = COALESCE($6::boolean, fallback_chat_participants.is_favorite)
         RETURNING is_muted, is_pinned, is_archived, is_favorite`,
        [
          chatId,
          participantId,
          boolOrNull('p_is_muted'),
          boolOrNull('p_is_pinned'),
          boolOrNull('p_is_archived'),
          boolOrNull('p_is_favorite'),
        ],
      )
      return { ok: true, chat_id: chatId, ...(preferenceRows[0] || {}) }
    }
    default:
      {
        const error = httpError(`Could not find the function ${rpcName}`, 404)
        error.postgrestMissingFunction = rpcName
        throw error
      }
  }
}

function requireRouteAuth(req, res) {
  if (req.auth?.sub) return true
  res.status(401).json({ error: 'AUTH_REQUIRED' })
  return false
}

async function requireActiveRouteAuth(pool, req, res, { allowSuspended = false } = {}) {
  if (!requireRouteAuth(req, res)) return false
  if (allowSuspended) return true
  const suspension = await findActiveSuspension(pool, req.auth.sub)
  if (!suspension) return true
  res.status(403).json(accountSuspendedPayload(suspension))
  return false
}

function registerPostgrestCompatRoutes(app, { pool, authOptional }) {
  const tableRoute = /^\/rest\/v1\/([A-Za-z_][A-Za-z0-9_]*)$/
  const rpcRoute = /^\/rest\/v1\/rpc\/([A-Za-z_][A-Za-z0-9_]*)$/

  app.post(rpcRoute, authOptional, async (req, res, next) => {
    try {
      const rpcName = safeIdentifier(req.params[0])
      // Fixed labels identify slow RPCs without logging user identifiers or query data.
      req.observabilityRoute = rpcName === 'delete_own_account'
        ? '/rest/v1/rpc/delete_own_account' : '/rest/v1/rpc/:operation'
      if (!rpcName) return res.status(404).json({ error: 'NOT_FOUND' })
      if (rpcName !== 'resolve_login_email') {
        if (!requireRouteAuth(req, res)) return
        if (!SUPPORTED_RPC_NAMES.has(rpcName)) {
          return res.status(404).json({
            code: 'PGRST202',
            message: `Could not find the function ${rpcName}`,
          })
        }
        const allowSuspended = ['secure_get_my_suspension_state', 'delete_own_account'].includes(rpcName)
        if (!(await requireActiveRouteAuth(pool, req, res, { allowSuspended }))) return
      }
      const result = await handleRpc(pool, req, rpcName)
      res.json(result)
    } catch (error) {
      if (error?.postgrestMissingFunction) {
        return res.status(404).json({
          code: 'PGRST202',
          message: `Could not find the function ${error.postgrestMissingFunction}`,
        })
      }
      next(error)
    }
  })

  app.get(tableRoute, authOptional, async (req, res, next) => {
    try {
      if (!requireRouteAuth(req, res)) return
      const tableName = normalizeTableName(req.params[0])
      if (!tableName) return res.status(404).json({ error: 'NOT_FOUND' })
      if (!(await requireActiveRouteAuth(pool, req, res))) return
      const rows = await readTableRows(pool, tableName, req.query, req.auth)
      sendPostgrestRows(req, res, rows)
    } catch (error) {
      next(error)
    }
  })

  app.head(tableRoute, authOptional, async (req, res, next) => {
    try {
      if (!requireRouteAuth(req, res)) return
      const tableName = normalizeTableName(req.params[0])
      if (!tableName) return res.status(404).end()
      if (!(await requireActiveRouteAuth(pool, req, res))) return
      const rows = await readTableRows(pool, tableName, req.query, req.auth)
      sendPostgrestRows(req, res, rows, 200, true)
    } catch (error) {
      next(error)
    }
  })

  app.post(tableRoute, authOptional, async (req, res, next) => {
    try {
      if (!requireRouteAuth(req, res)) return
      const tableName = normalizeTableName(req.params[0])
      if (!tableName) return res.status(404).json({ error: 'NOT_FOUND' })
      if (!(await requireActiveRouteAuth(pool, req, res))) return
      const rows = await handleInsert(pool, tableName, req.auth, req.body)
      sendPostgrestRows(req, res, rows, 201)
    } catch (error) {
      next(error)
    }
  })

  app.patch(tableRoute, authOptional, async (req, res, next) => {
    try {
      if (!requireRouteAuth(req, res)) return
      const tableName = normalizeTableName(req.params[0])
      if (!tableName) return res.status(404).json({ error: 'NOT_FOUND' })
      if (!(await requireActiveRouteAuth(pool, req, res))) return
      const rows = await handlePatch(pool, tableName, req.auth, req.query, req.body)
      sendPostgrestRows(req, res, rows)
    } catch (error) {
      next(error)
    }
  })

  app.delete(tableRoute, authOptional, async (req, res, next) => {
    try {
      if (!requireRouteAuth(req, res)) return
      const tableName = normalizeTableName(req.params[0])
      if (!tableName) return res.status(404).json({ error: 'NOT_FOUND' })
      if (!(await requireActiveRouteAuth(pool, req, res))) return
      const rows = await handleDelete(pool, tableName, req.auth, req.query)
      sendPostgrestRows(req, res, rows)
    } catch (error) {
      next(error)
    }
  })
}

module.exports = {
  canReuseCatalogStickerMedia,
  _test: {
    profilePatchRow,
    votePollOption,
    buildPollState,
    desiredStickerFavoriteState,
    toggleStickerFavoriteRpc,
    DEFAULT_VIRTUAL_GIFTS,
    SERVER_MUTATION_ONLY_TABLES,
    sanitizeProfileRow,
    authorizeMutation,
    canUseReportEvidence,
    liveGiftTransactionsEnabled,
    liveGiftLimitsConfiguration,
    parsePollOptions,
    prepareInsertRow,
    canonicalDirectChatParticipants,
    purgeImportedIdentityRows,
    purgeCompatIdentityRows,
    readSpecificRows,
    rowId,
    sendVirtualGiftRpc,
    googlePlayConfiguration,
    googlePlayServiceAccountRaw,
    verifyGooglePlayPurchaseRpc,
    findGooglePlayPurchaserByObfuscatedAccountId,
    processGooglePlayOneTimeProductNotification,
    processGooglePlayPendingRefundReview,
    processGooglePlayRtdnNotification,
    googlePlayRefundReconciliationConfiguration,
    processGooglePlayVoidedPurchase,
    reconcileGooglePlayVoidedPurchases,
  },
  ensurePostgrestCompatTables,
  googlePlayConfiguration,
  processGooglePlayRtdnNotification,
  reconcileGooglePlayVoidedPurchases,
  registerPostgrestCompatRoutes,
}
