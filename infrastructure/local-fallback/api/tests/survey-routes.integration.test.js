'use strict'

const test = require('node:test')
const assert = require('node:assert/strict')
const { randomUUID, randomBytes } = require('node:crypto')
const jwt = require('jsonwebtoken')
const { authenticateClientSession, isPublicAuthError } = require('../src/client-auth-session')
const express = require('express')
const { Pool } = require('pg')
const sharp = require('sharp')
const { ensureSurveyTables, registerSurveyRoutes } = require('../src/surveys/routes')
const { runSurveyMaintenance } = require('../src/surveys/maintenance')

// Explicitly opt in to a disposable local database. Never use DATABASE_URL here.
const databaseUrl = process.env.SURVEY_TEST_DATABASE_URL
test('Encuestas: flujo HTTP y PostgreSQL con cuentas sintéticas', { skip: !databaseUrl }, async t => {
  const uri = new URL(databaseUrl)
  assert.ok(['127.0.0.1', 'localhost'].includes(uri.hostname), 'Usa PostgreSQL local de validación')
  assert.equal(uri.pathname, '/novvin_surveys_test', 'La base debe ser exclusiva de estas pruebas')
  const schema = `survey_test_${randomUUID().replaceAll('-', '')}`
  const admin = new Pool({ connectionString: databaseUrl })
  const pool = new Pool({ connectionString: databaseUrl, options: `-c search_path=${schema}`, max: 8 })
  let server
  t.after(async () => {
    if (server) await new Promise(resolve => server.close(resolve))
    await pool.end()
    assert.match(schema, /^survey_test_[0-9a-f]{32}$/)
    await admin.query(`DROP SCHEMA "${schema}" CASCADE`)
    await admin.end()
  })
  await admin.query(`CREATE SCHEMA "${schema}"`)
  await pool.query(`
    CREATE TABLE fallback_users(id uuid PRIMARY KEY, supabase_user_id uuid, display_name text, avatar_url text, email text);
    CREATE TABLE fallback_profile_moderation(user_id text PRIMARY KEY,is_admin boolean,admin_access_level text,admin_is_owner boolean);
    CREATE TABLE fallback_auth_sessions(id uuid PRIMARY KEY,user_id uuid REFERENCES fallback_users(id) ON DELETE CASCADE,
      revoked_at timestamptz,expires_at timestamptz NOT NULL,last_used_at timestamptz);
    CREATE TABLE fallback_posts(id uuid PRIMARY KEY DEFAULT gen_random_uuid(), user_id text NOT NULL,
      user_name text, user_profile_image text, content text, media_type text, visibility text, is_sensitive boolean DEFAULT false,
      created_at timestamptz DEFAULT now(), updated_at timestamptz DEFAULT now());
    CREATE TABLE fallback_user_blocks(blocker_id text, blocked_id text);
    CREATE TABLE fallback_follows(id uuid DEFAULT gen_random_uuid(), follower_id text, following_id text, created_at timestamptz DEFAULT now());
    CREATE TABLE fallback_rate_limit_windows(scope text, key_hash text, window_started_at timestamptz,
      request_count bigint, expires_at timestamptz, PRIMARY KEY(scope,key_hash));
  `)
  await ensureSurveyTables(pool)
  await ensureSurveyTables(pool)
  const owner = randomUUID(), respondent = randomUUID(), other = randomUUID(), moderator = randomUUID(), reviewer = randomUUID()
  const tokens = new Map(), signingKey = randomBytes(32).toString('hex'), issuer = 'conectate-local-fallback'
  for (const [id, name] of [[owner, 'Investigador'], [respondent, 'Participante'], [other, 'Otra cuenta'], [moderator, 'Moderador'], [reviewer, 'Revisor']]) {
    await pool.query('INSERT INTO fallback_users(id,display_name) VALUES($1,$2)', [id, name])
    const sid = randomUUID()
    await pool.query("INSERT INTO fallback_auth_sessions(id,user_id,expires_at) VALUES($1,$2,now()+interval '1 hour')", [sid, id])
    tokens.set(id, jwt.sign({ sub: id, sid, client: 'android' }, signingKey, { issuer, audience: 'conectate-clients', expiresIn: '1h' }))
  }
  await pool.query('INSERT INTO fallback_profile_moderation(user_id,is_admin,admin_access_level) VALUES($1,true,$2),($3,true,$4)', [moderator, 'write', reviewer, 'view'])
  const app = express()
  app.use(express.json({ limit: '6mb' }))
  registerSurveyRoutes(app, { pool, authRequired: async (req, res, next) => {
    try {
      req.auth = await authenticateClientSession({
        token: req.get('Authorization')?.replace(/^Bearer\s+/i, ''),
        verifyToken: token => jwt.verify(token, signingKey, { issuer, audience: 'conectate-clients' }),
        userExists: async id => (await pool.query('SELECT 1 FROM fallback_users WHERE id=$1', [id])).rows.length>0,
        touchSession: async auth => (await pool.query(`UPDATE fallback_auth_sessions SET last_used_at=now()
          WHERE id=$1 AND user_id=$2 AND revoked_at IS NULL AND expires_at>now() RETURNING id`, [auth.sid,auth.sub])).rows.length>0,
      })
      next()
    } catch (error) { if (isPublicAuthError(error)) res.status(error.status).json({ error: error.code }); else next(error) }
  } })
  app.use((error, req, res, next) => res.status(500).json({ error: error.message }))
  server = await new Promise(resolve => { const s = app.listen(0, '127.0.0.1', () => resolve(s)) })
  const base = `http://127.0.0.1:${server.address().port}/v1/surveys`
  async function request(account, path, method = 'GET', body, status = 200, headers = {}) {
    const result = await fetch(base + path, { method, headers: { Authorization: `Bearer ${tokens.get(account) || account}`, 'Content-Type': 'application/json', ...headers }, body: body ? JSON.stringify(body) : undefined })
    const data = await result.json()
    assert.equal(result.status, status, `${method} ${path}: ${JSON.stringify(data)}`)
    return data
  }
  const definition = {
    title: 'Encuesta de validación', purpose: 'Estudio ficticio', contact: 'qa@example.org', retention: 'Solo durante las pruebas', retentionDays: 30, consent: 'Acepto participar', sensitive: true,
    sections: [{ id: 'inicio', title: 'Inicio' }], questions: [{ id: 'edad', section: 'inicio', type: 'number', title: 'Edad', required: true, min: 18, max: 100 }]
  }
  let form, campaign, response
  await t.test('valida firma, emisor, audiencia, vencimiento y revocación de sesiones Android', async () => {
    await request('', '/me', 'GET', null, 401)
    await request(owner+'falso', '/me', 'GET', null, 401)
    await request(jwt.sign({ sub: owner }, signingKey, { issuer, audience: 'conectate-clients' }), '/me', 'GET', null, 401)
    const claims = jwt.decode(tokens.get(owner))
    await request(jwt.sign({ sub: owner, sid: claims.sid }, signingKey, { issuer: 'otro', audience: 'conectate-clients' }), '/me', 'GET', null, 401)
    await request(jwt.sign({ sub: owner, sid: claims.sid }, signingKey, { issuer, audience: 'conectate-guests' }), '/me', 'GET', null, 401)
    await request(jwt.sign({ sub: owner, sid: claims.sid }, signingKey, { issuer, audience: 'conectate-clients', expiresIn: -1 }), '/me', 'GET', null, 401)
    await pool.query('UPDATE fallback_auth_sessions SET revoked_at=now() WHERE user_id=$1', [reviewer])
    await request(reviewer, '/me', 'GET', null, 401)
    await pool.query('UPDATE fallback_auth_sessions SET revoked_at=NULL WHERE user_id=$1', [reviewer])
    assert.equal((await request(owner, '/me')).id, owner)
  })
  await t.test('rechaza publicar sin un plazo explícito y no admite plazos contradictorios', async () => {
    const noDeadline = await request(owner, '/forms', 'POST', { schema: { ...definition, retentionDays: null } })
    await request(owner, `/forms/${noDeadline.id}/publish`, 'POST', { revision: 1, publicationKey: randomUUID(), visibility: 'public' }, 400)
    await request(owner, '/forms', 'POST', { schema: { ...definition, retentionDays: 0 } }, 400)
    await request(owner, '/forms', 'POST', { schema: { ...definition, retentionDays: 1.5 } }, 400)
  })
  await t.test('guarda planilla y publica de forma idempotente ante concurrencia', async () => {
    form = await request(owner, '/forms', 'POST', { schema: definition })
    const publication = { revision: form.revision, publicationKey: randomUUID(), visibility: 'public', social: true }
    const [first, retry] = await Promise.all([request(owner, `/forms/${form.id}/publish`, 'POST', publication), request(owner, `/forms/${form.id}/publish`, 'POST', publication)])
    assert.equal(first.id, retry.id); campaign = first
    assert.equal((await pool.query('SELECT count(*)::int AS n FROM fallback_posts')).rows[0].n, 1)
    assert.equal((await pool.query('SELECT is_sensitive FROM fallback_posts WHERE id=$1', [campaign.post_id])).rows[0].is_sensitive, true)
    await request(other, `/forms/${form.id}`, 'GET', null, 404)
  })
  await t.test('la revisión publicada permanece intacta al modificar la planilla', async () => {
    form = await request(owner, `/forms/${form.id}`, 'PUT', { revision: form.revision, schema: { ...definition, title: 'Planilla modificada' } })
    const published = await request(respondent, `/campaigns/${campaign.id}`)
    assert.equal(published.schema.title, definition.title)
    await request(owner, `/forms/${form.id}`, 'PUT', { revision: 1, schema: definition }, 409)
  })
  await t.test('exige consentimiento y admite una respuesta por cuenta ante envíos concurrentes', async () => {
    await request(respondent, `/campaigns/${campaign.id}/responses`, 'POST', { answers: { edad: 22 }, revision: 1, consent: false }, 400)
    const payload = { answers: { edad: 22 }, revision: 1, consent: true }
    const [first, retry] = await Promise.all([request(respondent, `/campaigns/${campaign.id}/responses`, 'POST', payload), request(respondent, `/campaigns/${campaign.id}/responses`, 'POST', payload)])
    assert.equal(first.id, retry.id); response = first.duplicate ? retry : first
    assert.equal((await pool.query('SELECT count(*)::int AS n FROM survey_responses')).rows[0].n, 1)
    await request(respondent, '/me', 'GET', null, 409, { 'X-Survey-Account': owner })
  })
  await t.test('los resultados y exportaciones son privados del creador', async () => {
    await request(other, `/campaigns/${campaign.id}/results`, 'GET', null, 404)
    await request(respondent, `/campaigns/${campaign.id}/export`, 'POST', { format: 'csv' }, 404)
    const result = await request(owner, `/campaigns/${campaign.id}/results`)
    assert.equal(result.total, 1); assert.equal(result.responses[0].account_id, respondent)
    const csv = await request(owner, `/campaigns/${campaign.id}/export`, 'POST', { format: 'csv' })
    assert.ok(!Buffer.from(csv.data, 'base64').toString().includes(respondent))
  })
  await t.test('edita con control de revisión y rechaza cambios fuera del plazo', async () => {
    response = await request(respondent, `/campaigns/${campaign.id}/responses`, 'POST', { revision: 1, consent: true, editRevision: response.edit_revision, answers: { edad: 23 } })
    assert.equal(response.edit_revision, 2)
    await request(respondent, `/campaigns/${campaign.id}/responses`, 'POST', { revision: 1, consent: true, editRevision: 1, answers: { edad: 24 } }, 409)
    await pool.query("UPDATE survey_responses SET submitted_at=now()-interval '31 minutes' WHERE id=$1", [response.id])
    await request(respondent, `/campaigns/${campaign.id}/responses`, 'POST', { revision: 1, consent: true, editRevision: 2, answers: { edad: 24 } }, 409)
  })
  await t.test('cierra y reabre sin reiniciar la ventana de edición anterior', async () => {
    await pool.query('UPDATE survey_responses SET submitted_at=now() WHERE id=$1', [response.id])
    await request(owner, `/campaigns/${campaign.id}/state`, 'POST', { action: 'close' })
    await request(other, `/campaigns/${campaign.id}/responses`, 'POST', { revision: 1, consent: true, answers: { edad: 24 } }, 409)
    await request(owner, `/campaigns/${campaign.id}/state`, 'POST', { action: 'reopen' })
    await request(respondent, `/campaigns/${campaign.id}/responses`, 'POST', { revision: 1, consent: true, editRevision: 2, answers: { edad: 24 } }, 409)
  })
  await t.test('aplica seguidores, bloqueo y privacidad también al formulario', async () => {
    await request(owner, `/campaigns/${campaign.id}/state`, 'POST', { action: 'audience', visibility: 'followers' })
    await request(other, `/campaigns/${campaign.id}`, 'GET', null, 404)
    await pool.query('INSERT INTO fallback_follows(follower_id,following_id) VALUES($1,$2)', [other, owner])
    await request(other, `/campaigns/${campaign.id}`)
    await pool.query('INSERT INTO fallback_user_blocks(blocker_id,blocked_id) VALUES($1,$2)', [other, owner])
    await request(other, `/campaigns/${campaign.id}`, 'GET', null, 404)
    await request(owner, `/campaigns/${campaign.id}/state`, 'POST', { action: 'audience', visibility: 'private' })
    await request(respondent, `/campaigns/${campaign.id}`, 'GET', null, 404)
    await request(owner, `/campaigns/${campaign.id}/state`, 'POST', { action: 'audience', visibility: 'public' })
  })
  await t.test('respeta cambios de privacidad hechos desde Social al abrir o responder por enlace', async () => {
    await pool.query("UPDATE fallback_posts SET visibility='private' WHERE id=$1", [campaign.post_id])
    await request(respondent, `/campaigns/${campaign.id}`, 'GET', null, 404)
    await request(other, `/campaigns/${campaign.id}/responses`, 'POST', { revision: 1, consent: true, answers: { edad: 24 } }, 404)
    await request(owner, `/campaigns/${campaign.id}`)
    await pool.query("UPDATE fallback_posts SET visibility='followers' WHERE id=$1", [campaign.post_id])
    await request(respondent, `/campaigns/${campaign.id}`, 'GET', null, 404)
    await pool.query('INSERT INTO fallback_follows(follower_id,following_id) VALUES($1,$2)', [respondent, owner])
    await request(respondent, `/campaigns/${campaign.id}`)
    await pool.query("UPDATE fallback_posts SET visibility='public' WHERE id=$1", [campaign.post_id])
  })
  await t.test('regenerar el QR invalida la clave anterior y mantiene la nueva', async () => {
    const old = await request(owner, `/campaigns/${campaign.id}/qr`, 'POST', { format: 'link' })
    const oldKey = new URL(old.link).searchParams.get('key')
    await request(owner, `/campaigns/${campaign.id}/state`, 'POST', { action: 'rotate-link' })
    await request(respondent, `/campaigns/${campaign.id}?key=${oldKey}`, 'GET', null, 404)
    const fresh = await request(owner, `/campaigns/${campaign.id}/qr`, 'POST', { format: 'link' })
    assert.notEqual(fresh.link, old.link)
    await request(respondent, `/campaigns/${campaign.id}?key=${new URL(fresh.link).searchParams.get('key')}`)
  })
  await t.test('programa la apertura y el cierre en el servidor', async () => {
    const scheduled = await request(owner, `/forms/${form.id}/publish`, 'POST', {
      revision: form.revision, publicationKey: randomUUID(), visibility: 'public', social: false,
      opensAt: new Date(Date.now() + 3600000).toISOString(), closesAt: new Date(Date.now() + 7200000).toISOString()
    })
    assert.equal((await request(respondent, `/campaigns/${scheduled.id}`)).status, 'scheduled')
    await request(respondent, `/campaigns/${scheduled.id}/responses`, 'POST', { revision: form.revision, consent: true, answers: { edad: 25 } }, 409)
    await pool.query("UPDATE survey_campaigns SET opens_at=now()-interval '2 hours',closes_at=now()-interval '1 hour' WHERE id=$1", [scheduled.id])
    assert.equal((await request(respondent, `/campaigns/${scheduled.id}`)).status, 'closed')
    await request(respondent, `/campaigns/${scheduled.id}/responses`, 'POST', { revision: form.revision, consent: true, answers: { edad: 25 } }, 409)
  })
  await t.test('elimina una respuesta sin permitir que la cuenta envíe otra', async () => {
    await request(owner, `/campaigns/${campaign.id}/responses/${response.id}`, 'DELETE', { confirm: 'ELIMINAR' })
    assert.equal((await request(owner, `/campaigns/${campaign.id}/results`)).total, 0)
    assert.equal((await request(respondent, `/campaigns/${campaign.id}`)).response_count, 0)
    await request(respondent, `/campaigns/${campaign.id}/responses`, 'POST', { revision: 1, consent: true, answers: { edad: 24 } }, 409)
  })
  await t.test('retirar la publicación conserva datos privados y bloquea a la audiencia', async () => {
    await request(owner, `/campaigns/${campaign.id}/state`, 'POST', { action: 'unpublish' })
    await request(respondent, `/campaigns/${campaign.id}`, 'GET', null, 404)
    await request(owner, `/campaigns/${campaign.id}/results`)
    await request(owner, `/campaigns/${campaign.id}/qr`, 'POST', { format: 'link' }, 409)
  })
  await t.test('los adjuntos son privados y el bloqueo de edición permanece tras reabrir', async () => {
    const fileForm = await request(owner, '/forms', 'POST', { schema: { ...definition, questions: [
      { id: 'documento', section: 'inicio', type: 'file', title: 'Adjunto', required: true }
    ] } })
    const fileCampaign = await request(owner, `/forms/${fileForm.id}/publish`, 'POST', {
      revision: fileForm.revision, publicationKey: randomUUID(), visibility: 'public', social: false
    })
    const image = await sharp({ create: { width: 2, height: 2, channels: 3, background: '#ffffff' } }).png().toBuffer()
    const payload = { name: 'imagen.png', data: image.toString('base64'), campaignId: fileCampaign.id }
    const attachment = await request(respondent, '/assets', 'POST', payload)
    assert.equal(attachment.mime, 'image/webp')
    await request(owner, `/assets/${attachment.id}`, 'GET', null, 404)
    await request(respondent, '/forms', 'POST', { schema: { ...definition, cover: attachment.id } }, 400)
    const submitted = await request(respondent, `/campaigns/${fileCampaign.id}/responses`, 'POST', {
      revision: 1, consent: true, answers: { documento: [attachment.id] }
    })
    await request(other, `/assets/${attachment.id}`, 'GET', null, 404)
    await request(other, `/assets/${attachment.id}?campaignId=${fileCampaign.id}`, 'GET', null, 404)
    await request(owner, `/assets/${attachment.id}`)
    await request(respondent, `/assets/${attachment.id}`)
    await request(owner, `/campaigns/${fileCampaign.id}/state`, 'POST', { action: 'close' })
    await request(owner, `/campaigns/${fileCampaign.id}/state`, 'POST', { action: 'reopen' })
    await request(respondent, '/assets', 'POST', payload, 409)
    await request(owner, `/campaigns/${fileCampaign.id}/responses/${submitted.id}`, 'DELETE', { confirm: 'ELIMINAR' })
    await request(owner, `/assets/${attachment.id}`, 'GET', null, 404)
    await request(respondent, '/assets', 'POST', payload, 409)
  })
  await t.test('excluye datos vencidos inmediatamente y purga adjuntos sin permitir segundo envío', async () => {
    const f = await request(owner, '/forms', 'POST', { schema: { ...definition, questions: [{ id: 'archivo', section: 'inicio', type: 'file', title: 'Adjunto', required: true }] } })
    const c = await request(owner, `/forms/${f.id}/publish`, 'POST', { revision: 1, publicationKey: randomUUID(), visibility: 'public', social: false })
    const image = await sharp({ create: { width: 2, height: 2, channels: 3, background: '#ffffff' } }).png().toBuffer()
    const a = await request(respondent, '/assets', 'POST', { campaignId: c.id, name: 'qa.png', data: image.toString('base64') })
    const r = await request(respondent, `/campaigns/${c.id}/responses`, 'POST', { revision: 1, consent: true, answers: { archivo: [a.id] } })
    assert.ok(Date.parse(r.expires_at) > Date.now()+29*86400000)
    await pool.query("UPDATE survey_responses SET expires_at=now()-interval '1 second' WHERE id=$1", [r.id])
    assert.equal((await request(owner, `/campaigns/${c.id}/results`)).total, 0)
    const mine = await request(respondent, `/campaigns/${c.id}`)
    assert.deepEqual(mine.response.answers, {}); assert.equal(mine.response_count, 0)
    await request(owner, `/assets/${a.id}`, 'GET', null, 404)
    await request(respondent, `/assets/${a.id}`, 'GET', null, 404)
    await request(respondent, `/campaigns/${c.id}/responses`, 'POST', { revision: 1, consent: true, answers: { archivo: [a.id] } }, 409)
    const runs = await Promise.all([runSurveyMaintenance(pool), runSurveyMaintenance(pool)])
    assert.equal(runs.reduce((sum, result) => sum+result.expired, 0), 1)
    assert.equal((await pool.query('SELECT count(*)::int AS n FROM survey_assets WHERE id=$1', [a.id])).rows[0].n, 0)
    assert.deepEqual((await pool.query('SELECT answers FROM survey_responses WHERE id=$1', [r.id])).rows[0].answers, {})
    await request(respondent, `/campaigns/${c.id}/responses`, 'POST', { revision: 1, consent: true, answers: { archivo: [a.id] } }, 409)
    assert.equal((await runSurveyMaintenance(pool)).expired, 0)
  })
  await t.test('reporta por QR y aplica capacidades administrativas sin revelar respuestas', async () => {
    const f = await request(owner, '/forms', 'POST', { schema: definition })
    const c = await request(owner, `/forms/${f.id}/publish`, 'POST', { revision: 1, publicationKey: randomUUID(), visibility: 'public', social: false })
    await request(respondent, `/campaigns/${c.id}/responses`, 'POST', { revision: 1, consent: true, answers: { edad: 25 } })
    const report = { reason: 'privacy', detail: 'Propósito de investigación no suficientemente claro' }
    await request(respondent, `/campaigns/${c.id}/report`, 'POST', report)
    await request(respondent, `/campaigns/${c.id}/report`, 'POST', report)
    await request(owner, `/campaigns/${c.id}/report`, 'POST', report, 404)
    await request(other, '/admin/reports', 'GET', null, 403)
    assert.equal((await request(moderator, '/me')).can_moderate, true)
    assert.equal((await request(reviewer, '/me')).can_moderate, false)
    const reports = await request(reviewer, '/admin/reports')
    assert.equal(reports.reports.length, 1)
    const instrument = await request(reviewer, `/admin/campaigns/${c.id}`)
    assert.ok(!Object.hasOwn(instrument, 'responses'))
    await request(moderator, `/campaigns/${c.id}/results`, 'GET', null, 404)
    const decision = { action: 'block', note: 'Revisar consentimiento y propósito antes de permitir participación' }
    await request(reviewer, `/admin/campaigns/${c.id}/moderation`, 'POST', decision, 403)
    await request(moderator, `/admin/campaigns/${c.id}/moderation`, 'POST', decision)
    await request(respondent, `/campaigns/${c.id}`, 'GET', null, 404)
    await request(owner, `/campaigns/${c.id}/qr`, 'POST', { format: 'link' }, 409)
    await request(owner, `/campaigns/${c.id}/state`, 'POST', { action: 'reopen' })
    await request(respondent, `/campaigns/${c.id}`, 'GET', null, 404)
    assert.equal((await request(owner, `/campaigns/${c.id}/results`)).total, 1)
    await request(moderator, `/admin/campaigns/${c.id}/moderation`, 'POST', { ...decision, action: 'unblock' })
    await request(respondent, `/campaigns/${c.id}`)
    assert.equal((await request(reviewer, '/admin/reports')).reports[0].status, 'reviewed')
  })
  await t.test('conserva imágenes de revisiones anteriores y serializa guardado frente a borrado', async () => {
    const image=await sharp({create:{width:2,height:2,channels:3,background:'#8064d4'}}).png().toBuffer()
    const upload=()=>request(owner,'/assets','POST',{name:'qa.png',data:image.toString('base64')})
    const a=await upload()
    const f=await request(owner,'/forms','POST',{schema:{...definition,cover:a.id}})
    await request(owner,`/forms/${f.id}`,'PUT',{revision:1,schema:definition})
    await request(owner,`/assets/${a.id}`,'DELETE',null,409)
    const b=await upload()
    // Whichever transaction wins, a saved revision can never reference a deleted image.
    const send=async(path,method,body)=>{
      const res=await fetch(base+path,{method,headers:{Authorization:`Bearer ${tokens.get(owner)}`,'Content-Type':'application/json'},body:body?JSON.stringify(body):undefined})
      return {status:res.status,data:await res.json()}
    }
    const [save,remove]=await Promise.all([send(`/forms/${f.id}`,'PUT',{revision:2,schema:{...definition,cover:b.id}}),send(`/assets/${b.id}`,'DELETE')])
    assert.ok((save.status===200 && remove.status===409)||(save.status===400 && remove.status===200),JSON.stringify([save,remove]))
    const latest=await request(owner,`/forms/${f.id}`)
    if(latest.schema.cover)await request(owner,`/assets/${latest.schema.cover}`)
  })
  await t.test('publicar de nuevo restaura Social sin duplicar campaña, respuestas ni ventanas de edición', async () => {
    const f=await request(owner,'/forms','POST',{schema:definition})
    const c=await request(owner,`/forms/${f.id}/publish`,'POST',{revision:1,publicationKey:randomUUID(),visibility:'public',social:true})
    const r=await request(respondent,`/campaigns/${c.id}/responses`,'POST',{revision:1,consent:true,answers:{edad:25}})
    await request(owner,`/campaigns/${c.id}/state`,'POST',{action:'unpublish'})
    await request(respondent,`/campaigns/${c.id}`,'GET',null,404)
    const restored=await Promise.all([1,2].map(()=>request(owner,`/campaigns/${c.id}/state`,'POST',{action:'republish'})))
    assert.equal(restored[0].id,c.id);assert.equal(restored[0].post_id,restored[1].post_id)
    assert.notEqual(restored[0].post_id,c.post_id)
    assert.equal((await pool.query('SELECT count(*)::int AS n FROM fallback_posts WHERE survey_id=$1',[c.id])).rows[0].n,1)
    assert.equal((await request(respondent,`/campaigns/${c.id}`)).response.id,r.id)
    assert.equal((await request(owner,`/campaigns/${c.id}/results`)).total,1)
  })
  await t.test('la misma clave concurrente entre planillas da un conflicto controlado', async () => {
    const first=await request(owner,'/forms','POST',{schema:definition}),second=await request(owner,'/forms','POST',{schema:definition})
    const publication={revision:1,publicationKey:randomUUID(),visibility:'public',social:false}
    const results=await Promise.all([first,second].map(async f=>{
      const r=await fetch(base+`/forms/${f.id}/publish`,{method:'POST',headers:{Authorization:`Bearer ${tokens.get(owner)}`,'Content-Type':'application/json'},body:JSON.stringify(publication)})
      return r.status
    }))
    assert.deepEqual(results.sort(),[200,409])
  })
  await t.test('publicaciones simultáneas de distintas planillas no superan el límite por cuenta', async () => {
    const forms=await Promise.all([1,2].map(()=>request(owner,'/forms','POST',{schema:definition})))
    const count=(await pool.query('SELECT count(*)::int AS n FROM survey_campaigns WHERE owner_id=$1',[owner])).rows[0].n
    await pool.query(`INSERT INTO survey_campaigns(owner_id,schema,revision,visibility,share_key)
      SELECT $1,$2,1,'private',gen_random_uuid()::text FROM generate_series(1,$3)`,[owner,definition,199-count])
    const statuses=await Promise.all(forms.map(async f=>{
      const r=await fetch(base+`/forms/${f.id}/publish`,{method:'POST',headers:{Authorization:`Bearer ${tokens.get(owner)}`,'Content-Type':'application/json'},body:JSON.stringify({revision:1,publicationKey:randomUUID(),visibility:'public',social:false})})
      return r.status
    }))
    assert.deepEqual(statuses.sort(),[200,409])
    assert.equal((await pool.query('SELECT count(*)::int AS n FROM survey_campaigns WHERE owner_id=$1',[owner])).rows[0].n,200)
  })
})
