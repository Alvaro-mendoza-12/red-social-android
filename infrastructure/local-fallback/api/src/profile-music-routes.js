'use strict'

const { createHash } = require('node:crypto')
const REACTIONS = Object.freeze(['LIKE', 'LOVE', 'FIRE', 'HAHA', 'WOW', 'SAD'])
const EMOJI = Object.freeze({ LIKE: '👍', LOVE: '❤️', FIRE: '🔥', HAHA: '😂', WOW: '😮', SAD: '😢' })
const clean = value => String(value || '').trim().toLowerCase()
const failure = (status, code) => Object.assign(new Error(code), { status, code })

function normalizedReaction(value) {
  if (value === null || value === '') return null
  if (typeof value !== 'string') throw failure(400, 'REACTION_INVALID')
  const reaction = value.trim().toUpperCase()
  if (!REACTIONS.includes(reaction)) throw failure(400, 'REACTION_INVALID')
  return reaction
}

function normalizedPresentation(value) {
  if (!['audio', 'disc'].includes(value)) throw failure(400, 'PRESENTATION_INVALID')
  return value
}

function visibleMusicUrl(profile, access, project) {
  // Clear the canonical field before consulting it. Falling back to a camelCase
  // alias after profileForViewer hides music would bypass section privacy.
  const canonical = { ...profile, favorite_music_url: String(profile.favorite_music_url || profile.favoriteMusicUrl || '').trim() }
  return String(project(canonical, { viewerIsTarget: access.viewerIsOwner, isFollowing: access.isFollowing })?.favorite_music_url || '').trim()
}

/** Signed transport parameters and backend origin rotation do not create another song. */
function profileMusicKey(ownerId, musicUrl) {
  let parsed
  try { parsed = new URL(musicUrl) } catch { throw failure(404, 'PROFILE_MUSIC_UNAVAILABLE') }
  if (parsed.protocol !== 'https:' || parsed.username || parsed.password) throw failure(404, 'PROFILE_MUSIC_UNAVAILABLE')
  const storage = parsed.pathname.match(/^\/storage\/v1\/object\/(?:public|sign|authenticated)\/(.+)$/)
  let object
  try { object = storage ? `storage:${decodeURIComponent(storage[1])}` : `${parsed.origin.toLowerCase()}${parsed.pathname}${parsed.search}` }
  catch { throw failure(404, 'PROFILE_MUSIC_UNAVAILABLE') }
  if (storage) {
    const revisions = ['v', 'rev', 'version', 'versionId', 'etag'].filter(key => parsed.searchParams.has(key))
    object += revisions.map(key => `\u0000${key}=${parsed.searchParams.get(key)}`).join('')
  }
  return createHash('sha256').update(`${clean(ownerId)}\u0000${object}`).digest('hex')
}

function reactionSummary(rows) {
  const counts = Object.fromEntries(REACTIONS.map(reaction => [reaction, 0]))
  let reaction = null
  for (const row of rows) {
    if (!REACTIONS.includes(row.reaction)) continue
    counts[row.reaction] = Math.max(0, Math.min(Number(row.count) || 0, Number.MAX_SAFE_INTEGER))
    if (row.mine === true) reaction = row.reaction
  }
  return { counts, reaction, total: Object.values(counts).reduce((sum, count) => sum + count, 0) }
}

async function ensureProfileMusicTables(pool) {
  await pool.query(`CREATE TABLE IF NOT EXISTS fallback_profile_music_reactions (
    owner_id TEXT NOT NULL, music_key TEXT NOT NULL, actor_id TEXT NOT NULL,
    reaction TEXT CHECK (reaction IS NULL OR reaction IN ('LIKE','LOVE','FIRE','HAHA','WOW','SAD')),
    notified_at TIMESTAMPTZ, updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY(owner_id,music_key,actor_id))`)
  await pool.query(`CREATE INDEX IF NOT EXISTS idx_profile_music_reaction_summary
    ON fallback_profile_music_reactions(owner_id,music_key,reaction) WHERE reaction IS NOT NULL`)
  await pool.query(`CREATE TABLE IF NOT EXISTS fallback_profile_music_preferences (
    owner_id TEXT NOT NULL, music_key TEXT NOT NULL,
    presentation TEXT NOT NULL DEFAULT 'audio' CHECK (presentation IN ('audio','disc')),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), PRIMARY KEY(owner_id,music_key))`)
}

async function loadSummary(db, context) {
  const [reactions, preferences] = await Promise.all([
    db.query(`SELECT reaction,COUNT(*)::int AS count,bool_or(actor_id=$3) AS mine
      FROM fallback_profile_music_reactions WHERE owner_id=$1 AND music_key=$2 AND reaction IS NOT NULL
      GROUP BY reaction`, [context.ownerId, context.musicKey, context.actorId]),
    db.query('SELECT presentation FROM fallback_profile_music_preferences WHERE owner_id=$1 AND music_key=$2',
      [context.ownerId, context.musicKey]),
  ])
  return { profileId: context.ownerId, musicKey: context.musicKey, musicUrl: context.musicUrl,
    presentation: preferences.rows[0]?.presentation === 'disc' ? 'disc' : 'audio',
    canEditPresentation: context.viewerIsOwner, ...reactionSummary(reactions.rows) }
}

async function setReaction(pool, context, reaction, createNotification) {
  const db = await pool.connect()
  let notification = null
  let changed = false
  try {
    await db.query('BEGIN')
    await db.query('SELECT pg_advisory_xact_lock(hashtextextended($1,0))',
      [`profile-music:${context.ownerId}:${context.musicKey}:${context.actorId}`])
    const previous = (await db.query(`SELECT reaction,notified_at FROM fallback_profile_music_reactions
      WHERE owner_id=$1 AND music_key=$2 AND actor_id=$3 FOR UPDATE`,
    [context.ownerId, context.musicKey, context.actorId])).rows[0]
    changed = (previous?.reaction ?? null) !== reaction
    if (changed) {
      const shouldNotify = reaction !== null && !context.viewerIsOwner &&
        (!previous?.notified_at || Date.now() - new Date(previous.notified_at).getTime() >= 60_000)
      await db.query(`INSERT INTO fallback_profile_music_reactions(owner_id,music_key,actor_id,reaction,notified_at)
        VALUES($1,$2,$3,$4,CASE WHEN $5 THEN NOW() ELSE NULL END)
        ON CONFLICT(owner_id,music_key,actor_id) DO UPDATE SET reaction=EXCLUDED.reaction,
          notified_at=CASE WHEN $5 THEN NOW() ELSE fallback_profile_music_reactions.notified_at END,updated_at=NOW()`,
      [context.ownerId, context.musicKey, context.actorId, reaction, shouldNotify])
      if (shouldNotify) notification = await createNotification(db, context, reaction, EMOJI[reaction])
    }
    await db.query('COMMIT')
  } catch (error) { await db.query('ROLLBACK').catch(() => {}); throw error }
  finally { db.release() }
  return { changed, notification }
}

function registerProfileMusicRoutes(app, {
  pool, authRequired, getCurrentUser, loadAccess, createNotification, dispatchNotification, onChanged,
}) {
  const base = '/v1/data/profiles/:profileId/music'
  async function contextFor(req, res, ownerOnly = false) {
    const actor = await getCurrentUser(pool, req)
    if (!actor) { res.status(401).json({ error: 'AUTH_REQUIRED' }); return null }
    const context = await loadAccess(pool, actor, clean(req.params.profileId))
    if (!context) { res.status(404).json({ error: 'PROFILE_MUSIC_UNAVAILABLE' }); return null }
    if (ownerOnly && !context.viewerIsOwner) { res.status(403).json({ error: 'PROFILE_MUSIC_OWNER_REQUIRED' }); return null }
    return { ...context, actor, actorId: clean(actor.id), musicKey: profileMusicKey(context.ownerId, context.musicUrl) }
  }
  function checkRevision(req, context) {
    if (typeof req.body?.musicKey !== 'string' || !/^[a-f0-9]{64}$/.test(req.body.musicKey)) throw failure(400, 'MUSIC_REVISION_REQUIRED')
    if (req.body.musicKey !== context.musicKey) throw failure(409, 'PROFILE_MUSIC_CHANGED')
  }
  function privateResponse(res) { res.setHeader('Cache-Control', 'private, no-store'); res.vary?.('Authorization') }
  app.get(`${base}/reactions`, authRequired, async (req, res, next) => {
    try {
      const context = await contextFor(req, res)
      if (!context) return
      privateResponse(res)
      res.json(await loadSummary(pool, context))
    } catch (error) { if (error.status) res.status(error.status).json({ error: error.code }); else next(error) }
  })
  app.put(`${base}/reactions`, authRequired, async (req, res, next) => {
    try {
      const context = await contextFor(req, res)
      if (!context) return
      checkRevision(req, context)
      if (!Object.prototype.hasOwnProperty.call(req.body || {}, 'reaction')) throw failure(400, 'REACTION_REQUIRED')
      const reaction = normalizedReaction(req.body.reaction)
      const result = await setReaction(pool, context, reaction, createNotification)
      if (result.notification) void Promise.resolve().then(() => dispatchNotification(result.notification)).catch(() => {})
      if (result.changed) void Promise.resolve().then(() => onChanged(context)).catch(() => {})
      privateResponse(res)
      res.json(await loadSummary(pool, context))
    } catch (error) { if (error.status) res.status(error.status).json({ error: error.code }); else next(error) }
  })
  app.put(`${base}/presentation`, authRequired, async (req, res, next) => {
    try {
      const context = await contextFor(req, res, true)
      if (!context) return
      checkRevision(req, context)
      const presentation = normalizedPresentation(req.body?.presentation)
      await pool.query(`INSERT INTO fallback_profile_music_preferences(owner_id,music_key,presentation) VALUES($1,$2,$3)
        ON CONFLICT(owner_id,music_key) DO UPDATE SET presentation=EXCLUDED.presentation,updated_at=NOW()`,
      [context.ownerId, context.musicKey, presentation])
      void Promise.resolve().then(() => onChanged(context)).catch(() => {})
      privateResponse(res)
      res.json(await loadSummary(pool, context))
    } catch (error) { if (error.status) res.status(error.status).json({ error: error.code }); else next(error) }
  })
}

module.exports = { REACTIONS, normalizedReaction, normalizedPresentation, visibleMusicUrl, profileMusicKey, reactionSummary,
  ensureProfileMusicTables, registerProfileMusicRoutes, setReaction }
