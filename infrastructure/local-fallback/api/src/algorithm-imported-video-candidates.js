'use strict'

// Optional legacy catalog adapter. Only explicit public video fields and
// profile visibility descriptors are projected. Canonical local account,
// moderation, tombstone and trusted classification checks remain in the shared
// selector supplied by algorithm-local-fallback-candidate-source.
const VIDEO_FIELDS = Object.freeze({
  id: ['id'], user_id: ['user_id', 'userId', 'creator_id', 'owner_id'],
  title: ['title', 'name'], description: ['description', 'caption'],
  video_url: ['video_url', 'videoUrl'], cover_url: ['cover_url', 'coverUrl'],
  thumbnail_url: ['thumbnail_url', 'thumbnailUrl'], sound_id: ['sound_id', 'soundId'],
  sound_source_video_id: ['sound_source_video_id', 'soundSourceVideoId'],
  sound_title: ['sound_title', 'soundTitle'], sound_artist: ['sound_artist', 'soundArtist'],
  sound_url: ['sound_url', 'soundUrl'], duration_seconds: ['duration_seconds', 'duration'],
  views: ['views'], like_count: ['like_count', 'likes_count'],
  comment_count: ['comment_count', 'comments_count'], share_count: ['share_count', 'shares_count'],
  is_public: ['is_public', 'isPublic'], created_at: ['created_at', 'createdAt'],
  updated_at: ['updated_at', 'updatedAt'],
})
const PROFILE_IDENTITIES = ['id', 'user_id', 'userId', 'profile_id', 'profileId', 'supabase_user_id']
const PROFILE_FIELDS = { private: ['is_private', 'isPrivate', 'private'], visibility: ['profile_visibility', 'profileVisibility'], break: ['break_mode', 'breakMode'] }
// This legacy schema predates profile visibility. Its existing profile route
// uses the current canonical override for visibility/private/break settings.
// Recognize its explicit marker set; unfamiliar schemas remain denied.
const LEGACY_CANONICAL_PROFILE_MARKERS = ['privacy_show_online', 'privacy_show_last_seen', 'privacy_allow_messages_from', 'is_suspended']
const PROFILE_MODERATION_FIELDS = ['is_suspended', 'isSuspended', 'suspended_until', 'suspension_end_date']
const METADATA_SQL = `SELECT table_name, column_name FROM information_schema.columns
  WHERE table_schema='supabase_import' AND table_name=ANY($1::text[])
    AND column_name=ANY($2::text[])`
const APPROVED_COLUMNS = [...new Set([...Object.values(VIDEO_FIELDS).flat(), ...PROFILE_IDENTITIES, ...Object.values(PROFILE_FIELDS).flat(), ...LEGACY_CANONICAL_PROFILE_MARKERS, ...PROFILE_MODERATION_FIELDS])]
const quoted = name => '"' + name + '"'
const pick = (columns, names) => names.find(name => columns.has(name)) || null
const expression = (alias, name) => name ? `${alias}.${quoted(name)}::text` : 'NULL::text'
function dateExpression(column) {
  const value = expression('source', column)
  // PG16+ validates imported textual dates without allowing one bad row to
  // crash the whole feed. The cast is evaluated only inside the valid branch.
  return `CASE WHEN pg_input_is_valid(${value}, 'timestamptz') THEN ${value}::timestamptz ELSE NULL::timestamptz END`
}
function buildImportedVideoSelector(videoColumns, profileColumns, canonicalSelector) {
  const fields = Object.fromEntries(Object.entries(VIDEO_FIELDS).map(([key, choices]) => [key, pick(videoColumns, choices)]))
  const identities = PROFILE_IDENTITIES.filter(name => profileColumns.has(name))
  const isPrivate = pick(profileColumns, PROFILE_FIELDS.private)
  const visibility = pick(profileColumns, PROFILE_FIELDS.visibility)
  const breakMode = pick(profileColumns, PROFILE_FIELDS.break)
  const canonicalLegacySchema = !isPrivate && !visibility && LEGACY_CANONICAL_PROFILE_MARKERS.every(name => profileColumns.has(name))
  const suspension = pick(profileColumns, ['is_suspended', 'isSuspended'])
  const suspensionUntil = pick(profileColumns, ['suspended_until', 'suspension_end_date'])
  if (!fields.id || !fields.user_id || !fields.video_url || !fields.is_public
    || (!fields.created_at && !fields.updated_at) || identities.length === 0 || (!isPrivate && !visibility && !canonicalLegacySchema)) return null
  if (typeof canonicalSelector !== 'string' || canonicalSelector.split('FROM fallback_videos video').length !== 2
    || canonicalSelector.split('WHERE video.is_public = TRUE').length !== 2) throw new TypeError('canonical video selector required')
  const projections = Object.keys(VIDEO_FIELDS).map(key => {
    let value = expression('source', fields[key])
    if (key === 'created_at') value = dateExpression(fields.created_at || fields.updated_at)
    else if (key === 'updated_at') value = dateExpression(fields.updated_at || fields.created_at)
    else if (key === 'is_public') value = `lower(trim(COALESCE(${value}, 'false'))) IN ('true','1','yes')`
    else if (['title', 'description', 'video_url', 'cover_url', 'thumbnail_url', 'sound_title', 'sound_artist', 'sound_url'].includes(key)) {
      const max = key === 'description' ? 5000 : key.endsWith('url') ? 2048 : 240
      value = `CASE WHEN length(${value}) <= ${max} THEN ${value} ELSE NULL::text END`
    }
    return `${value} AS ${key}`
  }).join(',\n')
  const identity = expression('source', fields.id)
  const relation = `(SELECT ${projections} FROM supabase_import.videos source
    WHERE length(trim(${identity})) BETWEEN 1 AND 180
      AND length(trim(${expression('source', fields.user_id)})) BETWEEN 1 AND 180
      AND NOT EXISTS (SELECT 1 FROM fallback_videos local_override WHERE lower(local_override.id::text)=lower(${identity}))) video`
  const aliases = `ARRAY[lower(video.user_id),lower(account.id::text),lower(COALESCE(account.supabase_user_id::text,''))]::text[]`
  const match = identities.map(name => `lower(${expression('legacy_profile', name)})=ANY(${aliases})`).join(' OR ')
  // Intersect legacy privacy with current canonical privacy. A stale private
  // descriptor may hide content until migration reconciles it; it never opens
  // a private profile merely because another source says public.
  const privateDenial = isPrivate
    ? `lower(trim(COALESCE(${expression('legacy_profile', isPrivate)},'unknown'))) NOT IN ('false','0','no')`
    : 'FALSE'
  const visibilityDenial = visibility
    ? `lower(trim(COALESCE(NULLIF(${expression('legacy_profile', visibility)},''),'unknown'))) NOT IN ('everyone','public')`
    : 'FALSE'
  const breakDenial = breakMode
    ? `lower(trim(COALESCE(NULLIF(${expression('legacy_profile', breakMode)},''),'off'))) NOT IN ('off','mute')`
    : 'FALSE'
  const suspensionValue = `lower(trim(COALESCE(${expression('legacy_profile', suspension)},'false')))`
  const suspensionDate = expression('legacy_profile', suspensionUntil)
  const activeSuspension = suspensionUntil
    ? `CASE WHEN pg_input_is_valid(${suspensionDate},'timestamptz') THEN ${suspensionDate}::timestamptz > NOW() ELSE TRUE END`
    : 'TRUE'
  const moderationDenial = suspension
    ? `(${suspensionValue} NOT IN ('false','0','no','true','1','yes') OR (${suspensionValue} IN ('true','1','yes') AND ${activeSuspension}))`
    : 'FALSE'
  const privacy = `EXISTS (SELECT 1 FROM supabase_import.profiles legacy_profile WHERE (${match}))
    AND ${canonicalLegacySchema ? 'profile.user_id IS NOT NULL' : 'TRUE'}
    AND NOT EXISTS (SELECT 1 FROM supabase_import.profiles legacy_profile WHERE (${match}) AND (
      ${privateDenial} OR ${visibilityDenial} OR ${breakDenial} OR ${moderationDenial}))`

  return canonicalSelector.replace('FROM fallback_videos video', 'FROM ' + relation)
    .replace('WHERE video.is_public = TRUE', 'WHERE video.is_public = TRUE\n    AND ' + privacy)
}
async function loadImportedVideoCandidateRows(pool, canonicalSelector, parameters) {
  try {
    const metadata = await pool.query(METADATA_SQL, [['videos', 'profiles'], APPROVED_COLUMNS])
    const videoColumns = new Set(), profileColumns = new Set()
    for (const row of metadata.rows || []) {
      if (!APPROVED_COLUMNS.includes(row.column_name)) continue
      if (row.table_name === 'videos') videoColumns.add(row.column_name)
      else if (row.table_name === 'profiles') profileColumns.add(row.column_name)
    }
    const sql = buildImportedVideoSelector(videoColumns, profileColumns, canonicalSelector)
    if (!sql) return []
    const result = await pool.query(sql, parameters)
    return Array.isArray(result.rows) ? result.rows : []
  } catch {
    // Incompatible/missing imports or permissions must never relax privacy.
    return []
  }
}
module.exports = { VIDEO_FIELDS, METADATA_SQL, APPROVED_COLUMNS, buildImportedVideoSelector, loadImportedVideoCandidateRows }
