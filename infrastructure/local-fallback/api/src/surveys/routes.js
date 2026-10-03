'use strict'

const express = require('express')
const sharp = require('sharp')
const { randomUUID, randomBytes } = require('node:crypto')
const { userIdentity, localFollowsByFollowerIds, importedRowsByFieldValues } = require('../data-routes')
const { createDistributedRateLimitMiddleware } = require('../distributed-rate-limit')
const D = require('./domain')
const { exportSurvey, qrExport, exportChart } = require('./exports')
const { scanPdf, exportSlot, imageSlot, analysisSlot, assertExportBudget } = require('./resources')
const { ADMIN_CAPABILITY, hasAdminCapability } = require('../admin-capabilities')

async function ensureSurveyTables(db) {
  await db.query(`
    CREATE TABLE IF NOT EXISTS survey_forms (
      id uuid PRIMARY KEY DEFAULT gen_random_uuid(), owner_id uuid NOT NULL REFERENCES fallback_users(id) ON DELETE CASCADE,
      schema jsonb NOT NULL, revision integer NOT NULL DEFAULT 1, archived boolean NOT NULL DEFAULT false,
      trashed_at timestamptz, created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now()
    );
    CREATE INDEX IF NOT EXISTS survey_forms_owner ON survey_forms(owner_id,updated_at DESC);
    CREATE TABLE IF NOT EXISTS survey_revisions (
      id uuid PRIMARY KEY DEFAULT gen_random_uuid(), form_id uuid NOT NULL REFERENCES survey_forms(id) ON DELETE CASCADE,
      revision integer NOT NULL, schema jsonb NOT NULL, created_at timestamptz NOT NULL DEFAULT now(), UNIQUE(form_id,revision)
    );
    CREATE TABLE IF NOT EXISTS survey_campaigns (
      id uuid PRIMARY KEY DEFAULT gen_random_uuid(), owner_id uuid NOT NULL REFERENCES fallback_users(id) ON DELETE CASCADE,
      form_id uuid REFERENCES survey_forms(id) ON DELETE SET NULL, schema jsonb NOT NULL, revision integer NOT NULL,
      status text NOT NULL DEFAULT 'open' CHECK(status IN ('open','closed','archived','trashed')),
      visibility text NOT NULL CHECK(visibility IN ('public','followers','private')),
      closes_at timestamptz, closed_at timestamptz, trashed_at timestamptz,
      post_id uuid, share_key text NOT NULL, created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now()
    );
    CREATE INDEX IF NOT EXISTS survey_campaigns_owner ON survey_campaigns(owner_id,created_at DESC);
    ALTER TABLE survey_campaigns ADD COLUMN IF NOT EXISTS opens_at timestamptz;
    ALTER TABLE survey_campaigns ADD COLUMN IF NOT EXISTS publication_key text;
    ALTER TABLE survey_campaigns ADD COLUMN IF NOT EXISTS audience_enabled boolean NOT NULL DEFAULT true;
    ALTER TABLE survey_campaigns ADD COLUMN IF NOT EXISTS moderation_blocked boolean NOT NULL DEFAULT false;
    ALTER TABLE survey_campaigns ADD COLUMN IF NOT EXISTS moderation_note text NOT NULL DEFAULT '';
    CREATE UNIQUE INDEX IF NOT EXISTS survey_campaign_publication_key ON survey_campaigns(owner_id,publication_key) WHERE publication_key IS NOT NULL;
    CREATE TABLE IF NOT EXISTS survey_responses (
      id uuid PRIMARY KEY DEFAULT gen_random_uuid(), campaign_id uuid NOT NULL REFERENCES survey_campaigns(id) ON DELETE CASCADE,
      account_id uuid NOT NULL REFERENCES fallback_users(id) ON DELETE CASCADE, answers jsonb NOT NULL,
      consent_revision integer NOT NULL, submitted_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
      edit_revision integer NOT NULL DEFAULT 1, UNIQUE(campaign_id,account_id)
    );
    ALTER TABLE survey_responses ADD COLUMN IF NOT EXISTS edit_locked_at timestamptz;
    ALTER TABLE survey_responses ADD COLUMN IF NOT EXISTS removed_at timestamptz;
    ALTER TABLE survey_responses ADD COLUMN IF NOT EXISTS expires_at timestamptz;
    CREATE INDEX IF NOT EXISTS survey_response_expiry ON survey_responses(expires_at) WHERE removed_at IS NULL;
    ALTER TABLE survey_campaigns ADD COLUMN IF NOT EXISTS dictionary jsonb NOT NULL DEFAULT '{}';
    ALTER TABLE survey_campaigns ADD COLUMN IF NOT EXISTS dictionary_revision integer NOT NULL DEFAULT 1;
    CREATE TABLE IF NOT EXISTS survey_assets (
      id uuid PRIMARY KEY DEFAULT gen_random_uuid(), owner_id uuid NOT NULL REFERENCES fallback_users(id) ON DELETE CASCADE,
      campaign_id uuid REFERENCES survey_campaigns(id) ON DELETE CASCADE, name text NOT NULL, mime text NOT NULL,
      data bytea NOT NULL CHECK(octet_length(data)<=4194304), created_at timestamptz NOT NULL DEFAULT now()
    );
    CREATE INDEX IF NOT EXISTS survey_assets_owner ON survey_assets(owner_id,created_at);
    CREATE TABLE IF NOT EXISTS survey_audit (
      id bigserial PRIMARY KEY, campaign_id uuid REFERENCES survey_campaigns(id) ON DELETE CASCADE,
      form_id uuid REFERENCES survey_forms(id) ON DELETE CASCADE, actor_id uuid REFERENCES fallback_users(id) ON DELETE CASCADE,
      action text NOT NULL, details jsonb NOT NULL DEFAULT '{}', created_at timestamptz NOT NULL DEFAULT now()
    );
    CREATE TABLE IF NOT EXISTS survey_reports (
      id uuid PRIMARY KEY DEFAULT gen_random_uuid(), campaign_id uuid NOT NULL REFERENCES survey_campaigns(id) ON DELETE CASCADE,
      reporter_id uuid NOT NULL REFERENCES fallback_users(id) ON DELETE CASCADE,
      reason text NOT NULL, detail text NOT NULL DEFAULT '', status text NOT NULL DEFAULT 'pending',
      reviewer_id uuid REFERENCES fallback_users(id) ON DELETE SET NULL, reviewed_at timestamptz,
      created_at timestamptz NOT NULL DEFAULT now(), UNIQUE(campaign_id,reporter_id)
    );
    CREATE INDEX IF NOT EXISTS survey_reports_pending ON survey_reports(status,created_at DESC);
    ALTER TABLE fallback_posts ADD COLUMN IF NOT EXISTS survey_id uuid REFERENCES survey_campaigns(id) ON DELETE SET NULL;
  `)
}
async function transaction(pool, task) {
  const db = await pool.connect()
  try { await db.query('BEGIN'); const result = await task(db); await db.query('COMMIT'); return result }
  catch (e) { await db.query('ROLLBACK'); throw e } finally { db.release() }
}
const imageIds = schema => [schema.cover, ...schema.sections.map(s => s.image), ...schema.questions.flatMap(q => [q.image, ...q.options.map(o => o.image)])].filter(Boolean)
async function audit(db, actor, action, { campaign = null, form = null, details = {} } = {}) {
  await db.query('INSERT INTO survey_audit(actor_id,action,campaign_id,form_id,details) VALUES($1,$2,$3,$4,$5)', [actor, action, campaign, form, details])
}
async function ownedForm(db, id, owner, lock = false) {
  if (!D.uuid(id)) D.fail('Planilla no disponible.', 404)
  const { rows } = await db.query(`SELECT * FROM survey_forms WHERE id=$1 AND owner_id=$2 ${lock ? 'FOR UPDATE' : ''}`, [id, owner])
  if (!rows[0]) D.fail('Planilla no disponible.', 404)
  return rows[0]
}
async function campaign(db, id, lock = false) {
  if (!D.uuid(id)) D.fail('Encuesta no disponible.', 404)
  const { rows } = await db.query(`SELECT * FROM survey_campaigns WHERE id=$1 ${lock ? 'FOR UPDATE' : ''}`, [id])
  if (!rows[0]) D.fail('Encuesta no disponible.', 404)
  return rows[0]
}
async function ownerCampaign(db, id, owner, lock = false) {
  const c = await campaign(db, id, lock)
  if (c.owner_id !== owner) D.fail('Encuesta no disponible.', 404)
  return c
}
async function user(db, id) {
  const { rows } = await db.query('SELECT id,supabase_user_id,display_name,avatar_url FROM fallback_users WHERE id=$1', [id])
  if (!rows[0]) D.fail('Inicia sesión para continuar.', 401)
  return rows[0]
}
async function canAccess(db, c, account) {
  if (c.owner_id === account) return true
  if (c.moderation_blocked || ['archived', 'trashed'].includes(c.status) || c.audience_enabled === false) return false
  let visibility = c.visibility
  if (c.post_id) {
    const post = (await db.query('SELECT visibility FROM fallback_posts WHERE id=$1 LIMIT 1', [c.post_id])).rows[0]
    if (!post || post.visibility === 'private') return false
    if (post.visibility === 'followers') visibility = visibility === 'private' ? 'private' : 'followers'
  }
  const viewer = userIdentity(await user(db, account)), creator = userIdentity(await user(db, c.owner_id))
  const blocked = await db.query(`SELECT 1 FROM fallback_user_blocks WHERE
    (lower(blocker_id)=ANY($1::text[]) AND lower(blocked_id)=ANY($2::text[])) OR
    (lower(blocker_id)=ANY($2::text[]) AND lower(blocked_id)=ANY($1::text[])) LIMIT 1`, [viewer.values, creator.values])
  if (blocked.rows.length || visibility === 'private') return false
  if (visibility === 'public') return true
  const [local, imported] = await Promise.all([
    localFollowsByFollowerIds(db, viewer.values),
    importedRowsByFieldValues(db, 'follows', ['follower_id'], viewer.values, 10000),
  ])
  return [...local, ...imported].some(f => creator.ids.has(String(f.following_id || '').toLowerCase()))
}
async function assertImages(db, schema, account) {
  const ids = [...new Set(imageIds(schema))]
  if (!ids.length) return
  const { rows } = await db.query("SELECT id FROM survey_assets WHERE id=ANY($1::uuid[]) AND owner_id=$2 AND campaign_id IS NULL AND mime LIKE 'image/%' ORDER BY id FOR KEY SHARE", [ids, account])
  if (rows.length !== ids.length) D.fail('La imagen no pertenece a tu cuenta.')
}
function finalDate(value) {
  if (!value) return null
  const time = Date.parse(value)
  if (!Number.isFinite(time) || time <= Date.now()) D.fail('La fecha de cierre debe estar en el futuro.')
  return new Date(time).toISOString()
}
function registerSurveyRoutes(app, { pool, authRequired, enabled = true, adminAccess = async (db, account) => require('../admin-moderation-routes').appAdminAccess(db, userIdentity(await user(db, account))) }) {
  const router = express.Router()
  router.use(authRequired)
  router.use((req, res, next) => {
    if (req.get('X-Survey-Account') && req.get('X-Survey-Account') !== req.auth.sub) return res.status(409).json({ error: 'SURVEY_ACCOUNT_CHANGED', message: 'La cuenta cambió. Cierra y vuelve a abrir Encuestas.' })
    next()
  })
  router.use((req, res, next) => { res.set('Cache-Control', 'no-store'); next() })
  router.use((req, res, next) => {
    if (!enabled) return res.status(503).json({ error: 'SURVEY_DISABLED', message: 'Encuestas aún no está habilitado en este servidor. Inténtalo más adelante.' })
    next()
  })
  router.use(createDistributedRateLimitMiddleware({ pool, scope: 'surveys:requests', limit: 180, windowMs: 60000, key: req => req.auth.sub }))
  const wrap = task => async (req, res, next) => {
    try { res.json(await task(req)) } catch (e) {
      if (e.expose) return res.status(e.status || 400).json({ error: e.code, message: e.message })
      next(e)
    }
  }
  router.get('/me', wrap(async req => {
    const u = await user(pool, req.auth.sub)
    const access = await adminAccess(pool, req.auth.sub)
    return { id: u.id, display_name: u.display_name, can_review_reports: hasAdminCapability(access, ADMIN_CAPABILITY.READ), can_moderate: hasAdminCapability(access, ADMIN_CAPABILITY.MODERATION_MANAGE) }
  }))
  const requireAdmin = async (db, account, capability) => {
    if (!hasAdminCapability(await adminAccess(db, account), capability)) D.fail('No tienes permiso para revisar o moderar encuestas.', 403, 'SURVEY_ADMIN_REQUIRED')
  }
  router.get('/admin/reports', wrap(async req => {
    await requireAdmin(pool, req.auth.sub, ADMIN_CAPABILITY.READ)
    return { reports: (await pool.query(`SELECT r.*,c.schema->>'title' AS title,c.moderation_blocked,c.moderation_note
      FROM survey_reports r JOIN survey_campaigns c ON c.id=r.campaign_id
      ORDER BY (r.status='pending') DESC,r.created_at DESC LIMIT 200`)).rows }
  }))
  router.get('/admin/campaigns/:id', wrap(async req => {
    await requireAdmin(pool, req.auth.sub, ADMIN_CAPABILITY.READ)
    const c = await campaign(pool, req.params.id)
    // Moderation can inspect the instrument, never participants' answers.
    return { id: c.id, schema: c.schema, moderation_blocked: c.moderation_blocked, moderation_note: c.moderation_note }
  }))
  router.post('/admin/campaigns/:id/moderation', wrap(async req => transaction(pool, async db => {
    await requireAdmin(db, req.auth.sub, ADMIN_CAPABILITY.MODERATION_MANAGE)
    const c = await campaign(db, req.params.id, true)
    if (!['block', 'unblock', 'dismiss'].includes(req.body.action)) D.fail('Acción de moderación inválida.')
    const note = typeof req.body.note === 'string' ? req.body.note.trim() : ''
    if (note.length < 10 || note.length > 1000) D.fail('Explica la decisión de moderación (10 a 1000 caracteres).')
    if (req.body.action !== 'dismiss') {
      await db.query('UPDATE survey_campaigns SET moderation_blocked=$2,moderation_note=$3,updated_at=now() WHERE id=$1', [c.id, req.body.action === 'block', note])
      if (req.body.action === 'block') await db.query('UPDATE survey_responses SET edit_locked_at=LEAST(coalesce(edit_locked_at,now()),now()) WHERE campaign_id=$1', [c.id])
    }
    await db.query("UPDATE survey_reports SET status='reviewed',reviewer_id=$2,reviewed_at=now() WHERE campaign_id=$1 AND status='pending'", [c.id, req.auth.sub])
    await audit(db, req.auth.sub, `moderation.${req.body.action}`, { campaign: c.id, details: { note } })
    return { reviewed: true }
  })))
  router.post('/campaigns/:id/report', createDistributedRateLimitMiddleware({ pool, scope: 'surveys:reports', limit: 10, windowMs: 60000, key: req => req.auth.sub }), wrap(async req => transaction(pool, async db => {
    const c = await campaign(db, req.params.id, true)
    if (c.owner_id === req.auth.sub || !await canAccess(db, c, req.auth.sub)) D.fail('Encuesta no disponible para reportar.', 404)
    if (!['privacy', 'fraud', 'harassment', 'sensitive', 'spam', 'other'].includes(req.body.reason)) D.fail('Elige un motivo de reporte.')
    const detail = typeof req.body.detail === 'string' ? req.body.detail.trim() : ''
    if (detail.length < 10 || detail.length > 1500) D.fail('Describe el problema (10 a 1500 caracteres).')
    await db.query(`INSERT INTO survey_reports(campaign_id,reporter_id,reason,detail) VALUES($1,$2,$3,$4)
      ON CONFLICT(campaign_id,reporter_id) DO NOTHING`, [c.id, req.auth.sub, req.body.reason, detail])
    return { reported: true }
  })))
  router.get('/forms', wrap(async req => {
    const { rows } = await pool.query('SELECT * FROM survey_forms WHERE owner_id=$1 ORDER BY updated_at DESC LIMIT 200', [req.auth.sub])
    return { forms: rows }
  }))
  router.post('/forms', wrap(async req => transaction(pool, async db => {
    const schema = D.normalizeSchema(req.body.schema)
    await db.query('SELECT pg_advisory_xact_lock(hashtext($1))', [`survey-owner:${req.auth.sub}`])
    const count = await db.query('SELECT count(*)::int AS count FROM survey_forms WHERE owner_id=$1', [req.auth.sub])
    if (count.rows[0].count >= 200) D.fail('Límite de 200 planillas. Elimina las que no necesitas.', 409)
    await assertImages(db, schema, req.auth.sub)
    const { rows } = await db.query('INSERT INTO survey_forms(owner_id,schema) VALUES($1,$2) RETURNING *', [req.auth.sub, schema])
    await db.query('INSERT INTO survey_revisions(form_id,revision,schema) VALUES($1,1,$2)', [rows[0].id, schema])
    await audit(db, req.auth.sub, 'form.created', { form: rows[0].id })
    return rows[0]
  })))
  router.get('/forms/:id', wrap(req => ownedForm(pool, req.params.id, req.auth.sub)))
  router.put('/forms/:id', wrap(async req => transaction(pool, async db => {
    const form = await ownedForm(db, req.params.id, req.auth.sub, true)
    if (form.trashed_at || form.archived) D.fail('Restaura la planilla para editarla.', 409)
    if (req.body.revision !== form.revision) D.fail('La planilla cambió en otro dispositivo. Recarga antes de guardar.', 409, 'SURVEY_CONFLICT')
    const schema = D.normalizeSchema(req.body.schema); await assertImages(db, schema, req.auth.sub)
    const { rows } = await db.query('UPDATE survey_forms SET schema=$2,revision=revision+1,updated_at=now() WHERE id=$1 RETURNING *', [form.id, schema])
    await db.query('INSERT INTO survey_revisions(form_id,revision,schema) VALUES($1,$2,$3)', [form.id, rows[0].revision, schema])
    await audit(db, req.auth.sub, 'form.saved', { form: form.id, details: { revision: rows[0].revision } })
    return rows[0]
  })))
  router.get('/forms/:id/revisions', wrap(async req => {
    await ownedForm(pool, req.params.id, req.auth.sub)
    return { revisions: (await pool.query('SELECT revision,schema,created_at FROM survey_revisions WHERE form_id=$1 ORDER BY revision DESC', [req.params.id])).rows }
  }))
  router.post('/forms/:id/state', wrap(async req => transaction(pool, async db => {
    const f = await ownedForm(db, req.params.id, req.auth.sub, true)
    const action = req.body.action
    if (!['archive', 'trash', 'restore', 'purge'].includes(action)) D.fail('Acción inválida.')
    if (action === 'purge') {
      if (!f.trashed_at || req.body.confirm !== f.schema.title) D.fail('Envía la planilla a la papelera y confirma su título.')
      await db.query('DELETE FROM survey_forms WHERE id=$1', [f.id])
      return { deleted: true }
    }
    const { rows } = await db.query("UPDATE survey_forms SET archived=$2,trashed_at=CASE WHEN $3 THEN now() ELSE NULL END,updated_at=now() WHERE id=$1 RETURNING *", [f.id, action === 'archive', action === 'trash'])
    await audit(db, req.auth.sub, `form.${action}`, { form: f.id }); return rows[0]
  })))
  router.post('/forms/:id/publish', wrap(async req => transaction(pool, async db => {
    await db.query('SELECT pg_advisory_xact_lock(hashtext($1))', [`survey-owner:${req.auth.sub}`])
    const f = await ownedForm(db, req.params.id, req.auth.sub, true)
    if (typeof req.body.publicationKey !== 'string' || !/^[a-zA-Z0-9_-]{16,100}$/.test(req.body.publicationKey)) D.fail('Falta el identificador de publicación.')
    const previous = (await db.query('SELECT * FROM survey_campaigns WHERE owner_id=$1 AND publication_key=$2', [req.auth.sub, req.body.publicationKey])).rows[0]
    if (previous) {
      if (previous.form_id !== f.id || previous.revision !== req.body.revision) D.fail('Identificador de publicación reutilizado con otro contenido.', 409)
      return previous
    }
    if (f.trashed_at || f.archived || req.body.revision !== f.revision) D.fail('Recarga la planilla activa antes de publicarla.', 409)
    const schema = D.normalizeSchema(f.schema)
    if (schema.retentionDays == null) D.fail('Define el plazo de conservación en días antes de publicar.')
    if (!['public', 'followers', 'private'].includes(req.body.visibility)) D.fail('Audiencia inválida.')
    const existing = await db.query('SELECT count(*)::int AS count FROM survey_campaigns WHERE owner_id=$1', [req.auth.sub])
    if (existing.rows[0].count >= 200) D.fail('Límite de 200 encuestas publicadas.', 409)
    await assertImages(db, schema, req.auth.sub)
    const u = await user(db, req.auth.sub), identity = userIdentity(u)
    const id = randomUUID(), share = randomBytes(24).toString('base64url')
    const opensAt = req.body.opensAt ? finalDate(req.body.opensAt) : null, closesAt = finalDate(req.body.closesAt)
    if (opensAt && closesAt && Date.parse(opensAt) >= Date.parse(closesAt)) D.fail('El cierre debe ser posterior al inicio.')
    const { rows } = await db.query('INSERT INTO survey_campaigns(id,owner_id,form_id,schema,revision,visibility,closes_at,share_key,opens_at,publication_key) VALUES($1,$2,$3,$4,$5,$6,$7,$8,$9,$10) RETURNING *', [id, req.auth.sub, f.id, schema, f.revision, req.body.visibility, closesAt, share, opensAt, req.body.publicationKey])
    if (req.body.social !== false) {
      const post = await db.query("INSERT INTO fallback_posts(user_id,user_name,user_profile_image,content,media_type,visibility,survey_id,is_sensitive) VALUES($1,$2,$3,$4,'survey',$5,$6,$7) RETURNING id", [identity.displayUserId, u.display_name || 'Usuario', u.avatar_url, [req.body.caption?.slice(0, 2000), schema.title].filter(Boolean).join('\n'), req.body.visibility, id, schema.sensitive])
      await db.query('UPDATE survey_campaigns SET post_id=$2 WHERE id=$1', [id, post.rows[0].id]); rows[0].post_id = post.rows[0].id
    }
    await audit(db, req.auth.sub, 'campaign.published', { campaign: id, details: { revision: f.revision } })
    return rows[0]
  })))
  router.get('/campaigns', wrap(async req => ({ campaigns: (await pool.query(`SELECT c.*,(SELECT count(*)::int FROM survey_responses r WHERE r.campaign_id=c.id AND r.removed_at IS NULL AND (r.expires_at IS NULL OR r.expires_at>now())) AS response_count,
    EXISTS(SELECT 1 FROM fallback_posts p WHERE p.id=c.post_id) AS post_exists FROM survey_campaigns c WHERE owner_id=$1 ORDER BY created_at DESC LIMIT 200`, [req.auth.sub])).rows })))
  router.get('/campaigns/:id', wrap(async req => {
    const c = await campaign(pool, req.params.id)
    if (!await canAccess(pool, c, req.auth.sub) || (req.query.key && req.query.key !== c.share_key)) D.fail('Encuesta no disponible para esta cuenta.', 404)
    const { rows } = await pool.query('SELECT * FROM survey_responses WHERE campaign_id=$1 AND account_id=$2', [c.id, req.auth.sub])
    const stored = rows[0] || null
    const mine = stored && stored.expires_at && Date.parse(stored.expires_at) <= Date.now()
      ? { ...stored, answers: {}, removed_at: stored.removed_at || stored.expires_at, edit_locked_at: stored.expires_at } : stored
    const count = await pool.query('SELECT count(*)::int AS count FROM survey_responses WHERE campaign_id=$1 AND removed_at IS NULL AND (expires_at IS NULL OR expires_at>now())', [c.id])
    return { id: c.id, schema: c.schema, revision: c.revision, moderation_blocked: c.moderation_blocked, moderation_note: c.owner_id === req.auth.sub ? c.moderation_note : undefined, status: c.moderation_blocked ? 'closed' : D.isOpen(c) ? 'open' : c.status === 'open' && c.opens_at && Date.parse(c.opens_at) > Date.now() ? 'scheduled' : c.status === 'open' ? 'closed' : c.status,
      opens_at: c.opens_at, closes_at: c.closes_at, visibility: c.visibility, creator: (await user(pool, c.owner_id)).display_name,
      owner: c.owner_id === req.auth.sub, response_count: count.rows[0].count,
      response: mine, edit_until: mine ? new Date(D.deadline(c, mine)).toISOString() : null, server_time: new Date().toISOString() }
  }))
  router.post('/campaigns/:id/responses', wrap(async req => transaction(pool, async db => {
    const c = await campaign(db, req.params.id, true)
    if (!await canAccess(db, c, req.auth.sub)) D.fail('Encuesta no disponible para esta cuenta.', 404)
    if (c.moderation_blocked) D.fail('Esta encuesta está restringida por moderación.', 409, 'SURVEY_MODERATED')
    if (!D.isOpen(c)) D.fail('Esta encuesta está cerrada.', 409, 'SURVEY_CLOSED')
    if (req.body.consent !== true || req.body.revision !== c.revision) D.fail('Debes aceptar participar en esta versión de la encuesta.')
    const prior = (await db.query('SELECT * FROM survey_responses WHERE campaign_id=$1 AND account_id=$2 FOR UPDATE', [c.id, req.auth.sub])).rows[0]
    if (prior?.removed_at || (prior?.expires_at && Date.parse(prior.expires_at) <= Date.now())) D.fail('Esta respuesta fue retirada o cumplió su plazo de conservación. Esta cuenta no puede enviar otra a la misma encuesta.', 409, 'SURVEY_RESPONSE_REMOVED')
    const answers = D.validateAnswers(c.schema, req.body.answers)
    if (Buffer.byteLength(JSON.stringify(answers)) > 262144) D.fail('La respuesta supera el tamaño permitido (256 KB). Reduce los textos extensos.', 413)
    const assets = c.schema.questions.filter(q => q.type === 'file').flatMap(q => answers[q.id] || [])
    if (assets.length) {
      const files = await db.query('SELECT id FROM survey_assets WHERE id=ANY($1::uuid[]) AND owner_id=$2 AND campaign_id=$3', [assets, req.auth.sub, c.id])
      if (files.rows.length !== new Set(assets).size) D.fail('Adjunto no autorizado.')
    }
    let result
    if (prior) {
      if (req.body.editRevision == null) return { ...prior, duplicate: true, edit_until: new Date(D.deadline(c, prior)).toISOString() }
      if (D.deadline(c, prior) <= Date.now()) D.fail('Terminó el plazo de 30 minutos para editar.', 409, 'SURVEY_EDIT_EXPIRED')
      if (req.body.editRevision !== prior.edit_revision) D.fail('Tu respuesta cambió en otro dispositivo. Recárgala.', 409, 'SURVEY_CONFLICT')
      result = await db.query('UPDATE survey_responses SET answers=$2,updated_at=now(),edit_revision=edit_revision+1 WHERE id=$1 RETURNING *', [prior.id, answers])
    } else {
      if (req.body.editRevision != null) D.fail('No hay una respuesta para editar.', 409)
      const total = await db.query('SELECT count(*)::int AS count FROM survey_responses WHERE campaign_id=$1', [c.id])
      if (total.rows[0].count >= 20000) D.fail('La encuesta alcanzó el límite de 20.000 envíos por campaña.', 409, 'SURVEY_CAPACITY')
      if (!Number.isInteger(c.schema.retentionDays)) D.fail('El responsable debe actualizar el plazo de conservación antes de recibir respuestas.', 409)
      result = await db.query("INSERT INTO survey_responses(campaign_id,account_id,answers,consent_revision,expires_at) VALUES($1,$2,$3,$4,now()+($5::integer*interval '1 day')) RETURNING *", [c.id, req.auth.sub, answers, c.revision, c.schema.retentionDays])
    }
    await audit(db, req.auth.sub, prior ? 'response.edited' : 'response.submitted', { campaign: c.id, details: { response: result.rows[0].id } })
    return { ...result.rows[0], edit_until: new Date(D.deadline(c, result.rows[0])).toISOString() }
  })))
  router.post('/campaigns/:id/state', wrap(async req => transaction(pool, async db => {
    const c = await ownerCampaign(db, req.params.id, req.auth.sub, true), action = req.body.action
    // A prior scheduled or manual closure permanently ends existing edit windows.
    if (!D.isOpen(c) && c.closes_at && Date.parse(c.closes_at) <= Date.now()) await db.query('UPDATE survey_responses SET edit_locked_at=LEAST(coalesce(edit_locked_at,$2),$2) WHERE campaign_id=$1', [c.id, c.closes_at])
    if (!['close', 'reopen', 'schedule', 'archive', 'trash', 'restore', 'purge', 'rotate-link', 'unpublish', 'republish', 'audience'].includes(action)) D.fail('Acción inválida.')
    if (action === 'purge') {
      if (c.status !== 'trashed' || req.body.confirm !== c.schema.title) D.fail('Envía la encuesta a la papelera y confirma su título.')
      if (c.post_id) await db.query('DELETE FROM fallback_posts WHERE id=$1', [c.post_id])
      await db.query('DELETE FROM survey_campaigns WHERE id=$1', [c.id]); return { deleted: true }
    }
    if (action === 'republish') {
      if (c.moderation_blocked || ['archived','trashed'].includes(c.status)) D.fail('Restaura la encuesta y resuelve cualquier restricción antes de publicarla.', 409)
      const exists = c.post_id && (await db.query('SELECT 1 FROM fallback_posts WHERE id=$1',[c.post_id])).rows.length
      if (!exists) {
        await assertImages(db,c.schema,req.auth.sub)
        const u=await user(db,req.auth.sub), identity=userIdentity(u)
        const caption = typeof req.body.caption === 'string' ? req.body.caption.trim().slice(0,2000) : ''
        const post=await db.query("INSERT INTO fallback_posts(user_id,user_name,user_profile_image,content,media_type,visibility,survey_id,is_sensitive) VALUES($1,$2,$3,$4,'survey',$5,$6,$7) RETURNING id",[identity.displayUserId,u.display_name||'Usuario',u.avatar_url,[caption,c.schema.title].filter(Boolean).join('\n'),c.visibility,c.id,c.schema.sensitive])
        await db.query('UPDATE survey_campaigns SET post_id=$2,audience_enabled=true,updated_at=now() WHERE id=$1',[c.id,post.rows[0].id])
      }
    } else if (action === 'audience') {
      if (!['public', 'followers', 'private'].includes(req.body.visibility)) D.fail('Audiencia inválida.')
      await db.query('UPDATE survey_campaigns SET visibility=$2,updated_at=now() WHERE id=$1', [c.id, req.body.visibility])
      if (c.post_id) await db.query('UPDATE fallback_posts SET visibility=$2,updated_at=now() WHERE id=$1', [c.post_id, req.body.visibility])
    } else if (action === 'unpublish') {
      if (c.post_id) await db.query('DELETE FROM fallback_posts WHERE id=$1', [c.post_id])
      await db.query('UPDATE survey_campaigns SET post_id=NULL,audience_enabled=false,updated_at=now() WHERE id=$1', [c.id])
    } else if (action === 'rotate-link') await db.query('UPDATE survey_campaigns SET share_key=$2,updated_at=now() WHERE id=$1', [c.id, randomBytes(24).toString('base64url')])
    else if (action === 'schedule') {
      if (c.status !== 'open' || (c.closes_at && Date.parse(c.closes_at)<=Date.now())) D.fail('Reabre la encuesta antes de programar su cierre.', 409)
      await db.query('UPDATE survey_campaigns SET closes_at=$2,updated_at=now() WHERE id=$1', [c.id, finalDate(req.body.closesAt)])
    } else {
      const status = { close: 'closed', reopen: 'open', archive: 'archived', trash: 'trashed', restore: 'closed' }[action]
      if (c.status === 'trashed' && action !== 'restore') D.fail('Restaura la encuesta primero.', 409)
      if (['archive', 'trash'].includes(action) && c.post_id) await db.query('DELETE FROM fallback_posts WHERE id=$1', [c.post_id])
      if (status !== 'open') await db.query('UPDATE survey_responses SET edit_locked_at=LEAST(coalesce(edit_locked_at,now()),now()) WHERE campaign_id=$1', [c.id])
      await db.query(`UPDATE survey_campaigns SET status=$2,closes_at=CASE WHEN $2='open' THEN $3 ELSE closes_at END,
        opens_at=CASE WHEN $2='open' THEN NULL ELSE opens_at END,
        closed_at=CASE WHEN $2='open' THEN NULL ELSE now() END,trashed_at=CASE WHEN $2='trashed' THEN now() ELSE NULL END,updated_at=now() WHERE id=$1`, [c.id, status, action === 'reopen' ? finalDate(req.body.closesAt) : null])
    }
    await audit(db, req.auth.sub, `campaign.${action}`, { campaign: c.id }); return ownerCampaign(db, c.id, req.auth.sub)
  })))
  router.put('/campaigns/:id/dictionary', wrap(async req => transaction(pool, async db => {
    const c = await ownerCampaign(db, req.params.id, req.auth.sub, true)
    if (req.body.revision !== c.dictionary_revision) D.fail('El diccionario cambió. Recarga los resultados.', 409, 'SURVEY_CONFLICT')
    const dictionary = {}, source = req.body.dictionary
    if (!source || typeof source !== 'object' || Array.isArray(source)) D.fail('Diccionario inválido.')
    for (const [id, value] of Object.entries(source)) {
      if (!c.schema.questions.some(q => q.id === id) || !value || !['descriptive', 'independent', 'dependent', 'control'].includes(value.role)) D.fail('Variable inválida.')
      dictionary[id] = { role: value.role, unit: String(value.unit || '').slice(0, 60), hypothesis: String(value.hypothesis || '').slice(0, 1500) }
    }
    const { rows } = await db.query('UPDATE survey_campaigns SET dictionary=$2,dictionary_revision=dictionary_revision+1,updated_at=now() WHERE id=$1 RETURNING *', [c.id, dictionary])
    await audit(db, req.auth.sub, 'dictionary.saved', { campaign: c.id, details: { revision: rows[0].dictionary_revision } })
    return rows[0]
  })))
  router.delete('/campaigns/:id/responses/:responseId', wrap(async req => transaction(pool, async db => {
    const c = await ownerCampaign(db, req.params.id, req.auth.sub, true)
    if (!D.uuid(req.params.responseId) || req.body.confirm !== 'ELIMINAR') D.fail('Confirma la eliminación de esta respuesta.')
    const r = (await db.query('SELECT * FROM survey_responses WHERE id=$1 AND campaign_id=$2 FOR UPDATE', [req.params.responseId, c.id])).rows[0]
    if (!r) D.fail('Respuesta no disponible.', 404)
    const attachments = c.schema.questions.filter(q => q.type === 'file').flatMap(q => r.answers[q.id] || [])
    if (attachments.length) await db.query('DELETE FROM survey_assets WHERE id=ANY($1::uuid[]) AND campaign_id=$2', [attachments, c.id])
    await db.query("UPDATE survey_responses SET answers='{}',removed_at=now(),edit_locked_at=now(),updated_at=now() WHERE id=$1", [r.id])
    await audit(db, req.auth.sub, 'response.removed', { campaign: c.id, details: { response: r.id } })
    return { deleted: true }
  })))
  const readResults = async (req, db = pool) => {
    const c = await ownerCampaign(db, req.params.id, req.auth.sub)
    const filters = req.method === 'GET' ? req.query : req.body.filters || {}
    const time = v => { if (!v) return null; if (!Number.isFinite(Date.parse(v))) D.fail('Fecha de filtro inválida.'); return new Date(v).toISOString() }
    const from = time(filters.from), to = time(filters.to), search = String(filters.search || '').slice(0, 160)
    if (from && to && Date.parse(from) > Date.parse(to)) D.fail('El periodo de filtro está invertido.')
    const budget = await db.query(`SELECT coalesce(sum(octet_length(answers::text)),0)::bigint AS bytes FROM
      (SELECT r.answers FROM survey_responses r JOIN fallback_users u ON u.id=r.account_id WHERE campaign_id=$1
      AND r.removed_at IS NULL AND (r.expires_at IS NULL OR r.expires_at>now())
      AND ($2::timestamptz IS NULL OR submitted_at >= $2) AND ($3::timestamptz IS NULL OR submitted_at <= $3)
      AND ($4='' OR strpos(lower(coalesce(u.display_name,'')),lower($4))>0 OR strpos(r.account_id::text,$4)>0 OR strpos(r.id::text,$4)>0)
      LIMIT 20001) selected`, [c.id, from, to, search])
    if (Number(budget.rows[0].bytes) > 16 * 1024 * 1024) D.fail('Reduce el periodo o los filtros para consultar este volumen de respuestas.', 413, 'SURVEY_EXPORT_LIMIT')
    let responses = (await db.query(`SELECT r.*,u.display_name,u.supabase_user_id FROM survey_responses r JOIN fallback_users u ON u.id=r.account_id WHERE campaign_id=$1 AND r.removed_at IS NULL AND (r.expires_at IS NULL OR r.expires_at>now())
      AND ($2::timestamptz IS NULL OR submitted_at >= $2) AND ($3::timestamptz IS NULL OR submitted_at <= $3)
      AND ($4='' OR strpos(lower(coalesce(u.display_name,'')),lower($4))>0 OR strpos(r.account_id::text,$4)>0 OR strpos(r.id::text,$4)>0)
      ORDER BY submitted_at,r.id LIMIT 20001`, [c.id, from, to, search])).rows
    if (filters.question) {
      const q = c.schema.questions.find(q => q.id === filters.question)
      if (!q) D.fail('Pregunta de filtro inválida.')
      responses = responses.filter(r => { const v = r.answers[q.id]; return Array.isArray(v) ? v.map(String).includes(String(filters.value)) : String(v) === String(filters.value) })
    }
    if (responses.length > 20000) D.fail('El volumen requiere exportación administrativa por lotes.', 413, 'SURVEY_EXPORT_LIMIT')
    c.schema = { ...c.schema, questions: c.schema.questions.map(q => ({ ...q, ...(c.dictionary[q.id] || {}) })) }
    c.filters = { from, to, search, question: filters.question || null, value: filters.value || null }
    return { campaign: c, responses }
  }
  router.get('/campaigns/:id/results', wrap(async req => analysisSlot(async () => {
    const { campaign: c, responses } = await readResults(req)
    const page = Math.max(0, Math.floor(Number(req.query.page) || 0))
    return { ...D.analytics(c.schema, responses), filters: c.filters, dictionary_revision: c.dictionary_revision,
      responses: responses.slice(page * 50, (page + 1) * 50), page, pages: Math.ceil(responses.length / 50) }
  })))
  router.get('/campaigns/:id/relations', wrap(async req => analysisSlot(async () => {
    const { campaign: c, responses } = await readResults(req)
    return D.relations(c.schema, responses, req.query.x, req.query.y)
  })))
  router.get('/campaigns/:id/history', wrap(async req => {
    await ownerCampaign(pool, req.params.id, req.auth.sub)
    return { events: (await pool.query('SELECT action,details,created_at FROM survey_audit WHERE campaign_id=$1 ORDER BY created_at DESC LIMIT 1000', [req.params.id])).rows }
  }))
  router.post('/campaigns/:id/export', createDistributedRateLimitMiddleware({ pool, scope: 'surveys:export', limit: 10, windowMs: 60000, key: req => req.auth.sub }), wrap(async req => exportSlot(async () => {
    // One database snapshot for the whole workbook/report, even while participants edit.
    const { campaign: c, responses } = await transaction(pool, async db => {
      await db.query('SET TRANSACTION ISOLATION LEVEL REPEATABLE READ')
      const result = await readResults(req, db)
      if (['docx', 'pdf'].includes(req.body.format)) {
        const ids = [...new Set(imageIds(result.campaign.schema))]
        const size = await db.query('SELECT coalesce(sum(octet_length(data)),0)::bigint AS bytes FROM survey_assets WHERE id=ANY($1::uuid[]) AND owner_id=$2', [ids, req.auth.sub])
        if (ids.length>100 || Number(size.rows[0].bytes)>16*1024*1024) D.fail('Las imágenes del instrumento superan el tamaño de una descarga móvil. Usa una planilla con imágenes más pequeñas.', 413, 'SURVEY_EXPORT_LIMIT')
        const images = await db.query("SELECT id,data FROM survey_assets WHERE id=ANY($1::uuid[]) AND owner_id=$2 AND campaign_id IS NULL AND mime LIKE 'image/%'", [ids, req.auth.sub])
        if (images.rows.length!==ids.length) D.fail('Falta una imagen del instrumento. Revisa la planilla antes de exportar.', 409)
        result.campaign.instrumentImages = new Map(images.rows.map(a => [a.id,a.data]))
      }
      return result
    })
    assertExportBudget(c, responses, req.body)
    const result = await exportSurvey(c, responses, req.body)
    if (responses.some(r => r.expires_at && Date.parse(r.expires_at) <= Date.now())) D.fail('Cambió el conjunto por vencimiento de respuestas. Repite la exportación.', 409, 'SURVEY_CONFLICT')
    await ownerCampaign(pool, c.id, req.auth.sub)
    await audit(pool, req.auth.sub, 'campaign.exported', { campaign: c.id, details: { format: req.body.format, identity: req.body.identity === true, filters: c.filters } })
    return result
  })))
  router.post('/campaigns/:id/charts/:questionId/export', createDistributedRateLimitMiddleware({ pool, scope: 'surveys:export', limit: 10, windowMs: 60000, key: req => req.auth.sub }), wrap(async req => exportSlot(async () => {
    const { campaign: c, responses } = await readResults(req)
    const question = D.analytics(c.schema, responses).questions.find(item => item.id === req.params.questionId)
    if (!question) D.fail('Pregunta no disponible.', 404)
    return exportChart(c, question, req.body.format, req.body.kind)
  })))
  router.post('/campaigns/:id/qr', wrap(async req => exportSlot(async () => {
    const c = await ownerCampaign(pool, req.params.id, req.auth.sub)
    if (c.moderation_blocked || c.audience_enabled === false || ['archived', 'trashed'].includes(c.status) || (c.post_id && !(await pool.query('SELECT 1 FROM fallback_posts WHERE id=$1', [c.post_id])).rows.length)) {
      D.fail('Esta encuesta no está disponible para compartir. Revisa su publicación y audiencia.', 409)
    }
    const link = `conectate://survey/${c.id}?key=${c.share_key}`
    return req.body.format === 'link' ? { link } : qrExport(c, link, req.body.format)
  })))
  router.post('/assets', createDistributedRateLimitMiddleware({ pool, scope: 'surveys:assets', limit: 20, windowMs: 60000, key: req => req.auth.sub }), wrap(async req => {
    const input = req.body.data
    if (typeof input !== 'string' || input.length > 5600000 || !/^[A-Za-z0-9+/]*={0,2}$/.test(input)) D.fail('Archivo inválido o superior a 4 MB.')
    let bytes = Buffer.from(input, 'base64'), mime
    if (bytes.length > 4194304 || !bytes.length) D.fail('Archivo vacío o superior a 4 MB.')
    if (bytes.subarray(0, 5).toString() === '%PDF-') { await scanPdf(bytes); mime = 'application/pdf' }
    else {
      bytes = await imageSlot(async () => {
        try { return await sharp(bytes, { limitInputPixels: 25000000, animated: false }).rotate().resize(1600, 1600, { fit: 'inside', withoutEnlargement: true }).webp({ quality: 85 }).toBuffer() }
        catch { D.fail('Solo se permiten imágenes válidas o PDF.') }
      }); mime = 'image/webp'
    }
    return transaction(pool, async db => {
      await db.query('SELECT pg_advisory_xact_lock(hashtext($1))', [`survey-assets:${req.auth.sub}`])
      const total = await db.query('SELECT coalesce(sum(octet_length(data)),0)::bigint AS bytes FROM survey_assets WHERE owner_id=$1', [req.auth.sub])
      if (Number(total.rows[0].bytes) + bytes.length > 104857600) D.fail('Límite de 100 MB de archivos de encuestas por cuenta.', 413)
      let campaignId = null
      if (req.body.campaignId) {
        const c = await campaign(db, req.body.campaignId, true)
        if (c.moderation_blocked || !D.isOpen(c) || !await canAccess(db, c, req.auth.sub)) D.fail('Encuesta no disponible.', 404)
        const prior = (await db.query('SELECT submitted_at,edit_locked_at,removed_at,expires_at FROM survey_responses WHERE campaign_id=$1 AND account_id=$2', [c.id, req.auth.sub])).rows[0]
        if (prior && (prior.removed_at || (prior.expires_at && Date.parse(prior.expires_at) <= Date.now()) || D.deadline(c, prior) <= Date.now())) D.fail('Terminó el plazo para adjuntar archivos.', 409)
        campaignId = c.id
      } else if (mime === 'application/pdf') D.fail('Las preguntas y opciones admiten imágenes; adjunta PDF al responder.')
      const { rows } = await db.query('INSERT INTO survey_assets(owner_id,campaign_id,name,mime,data) VALUES($1,$2,$3,$4,$5) RETURNING id,name,mime', [req.auth.sub, campaignId, String(req.body.name || 'archivo').replace(/[\x00-\x1f/\\]/g, '').slice(0, 160), mime, bytes])
      return rows[0]
    })
  }))
  router.get('/assets/:id', wrap(async req => {
    if (!D.uuid(req.params.id)) D.fail('Archivo no disponible.', 404)
    const a = (await pool.query('SELECT * FROM survey_assets WHERE id=$1', [req.params.id])).rows[0]
    if (!a) D.fail('Archivo no disponible.', 404)
    let access = !a.campaign_id && a.owner_id === req.auth.sub
    if (a.campaign_id) {
      const c = await campaign(pool, a.campaign_id)
      const response = (await pool.query(`SELECT account_id,removed_at,expires_at,answers FROM survey_responses
        WHERE campaign_id=$1 AND account_id=$2`, [c.id, a.owner_id])).rows[0]
      const active = response && !response.removed_at && (!response.expires_at || Date.parse(response.expires_at)>Date.now())
      const linked = active && c.schema.questions.filter(q => q.type === 'file').some(q => (response.answers[q.id] || []).includes(a.id))
      access = !!linked && [a.owner_id, c.owner_id].includes(req.auth.sub)
      // The creator cannot read files from a draft that has not been submitted.
      if (!access && a.owner_id === req.auth.sub && !response && Date.parse(a.created_at)>Date.now()-48*60*60*1000) access = await canAccess(pool,c,req.auth.sub)
    }
    if (!access && !a.campaign_id && req.query.campaignId) {
      const c = await campaign(pool, req.query.campaignId)
      access = imageIds(c.schema).includes(a.id) && await canAccess(pool, c, req.auth.sub)
    }
    if (!access) D.fail('Archivo no disponible.', 404)
    return { name: a.name, mime: a.mime, data: a.data.toString('base64') }
  }))
  router.delete('/assets/:id', wrap(async req => transaction(pool, async db => {
    if (!D.uuid(req.params.id)) D.fail('Archivo no disponible.', 404)
    const asset = await db.query('SELECT id FROM survey_assets WHERE id=$1 AND owner_id=$2 FOR UPDATE', [req.params.id, req.auth.sub])
    if (!asset.rows.length) D.fail('Archivo no disponible.', 404)
    const used = await db.query(`SELECT 1 FROM survey_forms WHERE schema::text LIKE $1 UNION ALL SELECT 1 FROM survey_revisions WHERE schema::text LIKE $1 UNION ALL SELECT 1 FROM survey_campaigns WHERE schema::text LIKE $1 UNION ALL SELECT 1 FROM survey_responses WHERE answers::text LIKE $1 LIMIT 1`, [`%${req.params.id}%`])
    if (used.rows.length) D.fail('El archivo está vinculado a una planilla o respuesta.', 409)
    await db.query('DELETE FROM survey_assets WHERE id=$1 AND owner_id=$2', [req.params.id, req.auth.sub]); return { deleted: true }
  })))
  app.use('/v1/surveys', router)
}
module.exports = { ensureSurveyTables, registerSurveyRoutes, canAccess, transaction }
