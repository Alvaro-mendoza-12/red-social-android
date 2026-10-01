const test = require('node:test')
const assert = require('node:assert/strict')
const { normalizeAnnouncement, publishAnnouncement, publicNotice, createAnnouncementWorker, registerSystemAnnouncementRoutes } = require('../src/system-announcements')
const { hasAdminCapability, ADMIN_CAPABILITY } = require('../src/admin-capabilities')
const now = Date.parse('2026-10-01T20:00:00Z')
const id = '12345678-1234-4123-8123-123456789012'
function input(overrides = {}) { return { id, kind: 'info', title: 'Novedad de Novvin', body: 'Un mensaje claro para todos.', expiresAt: new Date(now + 86400000).toISOString(), ...overrides } }
test('bounded messages and expiry are required', () => {
  assert.equal(normalizeAnnouncement(input(), now).kind, 'info')
  for (const overrides of [{kind:'other'}, {title:''}, {body:'x'}, {body:'x'.repeat(1201)}, {expiresAt:'invalid'}, {expiresAt:new Date(now).toISOString()}, {id:'forged'}]) {
    assert.throws(() => normalizeAnnouncement(input(overrides), now), { status: 400 })
  }
})
test('maintenance validates both times and preserves timezone as UTC', () => {
  const good = input({kind:'maintenance', startsAt:'2026-10-01T16:00:00-05:00', endsAt:'2026-10-01T17:00:00-05:00'})
  assert.equal(normalizeAnnouncement(good, now).startsAt, '2026-10-01T21:00:00.000Z')
  assert.throws(() => normalizeAnnouncement({...good, endsAt:good.startsAt}, now), /MAINTENANCE_SCHEDULE_INVALID/)
  assert.throws(() => normalizeAnnouncement({...good, expiresAt:'2026-10-01T21:30:00Z'}, now), /MAINTENANCE_SCHEDULE_INVALID/)
})
test('update links cannot be overridden with arbitrary downloads', () => {
  const result = normalizeAnnouncement(input({kind:'update',versionCode:118,updateUrl:'https://evil.invalid/app.apk'}), now)
  assert.equal(result.updateUrl, 'https://play.google.com/store/apps/details?id=conectate.com')
  assert.throws(() => normalizeAnnouncement(input({kind:'update',versionCode:0}), now), /UPDATE_VERSION_INVALID/)
})
test('only full administrators can publish global notices', () => {
  for (const level of ['none','view','write']) assert.equal(hasAdminCapability({level},ADMIN_CAPABILITY.SYSTEM_MANAGE),false)
  assert.equal(hasAdminCapability({level:'full'},ADMIN_CAPABILITY.SYSTEM_MANAGE),true)
})
test('public notice contains no creator identity or delivery recipients', () => {
  const result = publicNotice({id,created_by:'private',recipient_id:'private',kind:'info'})
  assert.equal('created_by' in result,false); assert.equal('recipient_id' in result,false)
})
test('global notification and outbox commit atomically and a duplicate does not broadcast twice', async () => {
  const candidate = input({expiresAt:new Date(Date.now()+86400000).toISOString()})
  const normalized = normalizeAnnouncement(candidate)
  const row = {id,kind:normalized.kind,title:normalized.title,body:normalized.body,expires_at:normalized.expiresAt,created_by:'owner'}
  let created = false, audiences = 0
  const statements = []
  const client = { release() {}, async query(sql) {
    statements.push(sql)
    if (sql.includes('INSERT INTO fallback_system_announcements')) { if (created) return {rows:[]}; created = true; return {rows:[row]} }
    if (sql.includes('SELECT * FROM fallback_system_announcements')) return {rows:[row]}
    if (sql.includes('WITH notifications')) { audiences++; assert.ok(sql.includes('FROM fallback_users')); return {rows:[],rowCount:2} }
    return {rows:[]}
  } }
  const pool = { connect: async () => client }
  assert.equal((await publishAnnouncement(pool,candidate,'owner')).audience,2)
  assert.equal((await publishAnnouncement(pool,candidate,'owner')).duplicate,true)
  assert.equal(audiences,1)
  assert.equal(statements.filter(sql => sql === 'COMMIT').length,2)
  await assert.rejects(publishAnnouncement(pool,{...candidate,body:'Changed body'},'owner'), /ANNOUNCEMENT_ID_CONFLICT/)
  assert.equal(statements.at(-1),'ROLLBACK')
})
test('delivery retries failures and uses the persisted recipient and notification identity', async () => {
  const sqls = [], sent = []
  const notification = {id:'notification',recipient_id:'recipient',content:'a'.repeat(1200),extra_data:{title:'Aviso'}}
  const pool = { async query(sql, params) {
    sqls.push(sql)
    if (sql.includes('WITH candidates')) return {rows:[{announcement_id:id,recipient_id:'recipient',notification_id:'notification'}]}
    if (sql.includes('SELECT * FROM fallback_notifications')) return {rows:[notification]}
    return {rows:[]}
  } }
  const run = createAnnouncementWorker(pool,{publish:async (channel)=>{sent.push(channel);return true},push:async (_pool,row)=>{assert.ok(row.content.length<=240);return {failed:1}}})
  await run()
  assert.deepEqual(sent,['user:recipient'])
  assert.ok(sqls.at(-1).includes('retry_at'))
  assert.ok(sqls[0].includes('SKIP LOCKED'))
})
test('admin mutations always have the authorization middleware and public reads do not', () => {
  const routes = [], auth = () => {}
  const app = Object.fromEntries(['get','post','delete'].map(method=>[method,(route,...handlers)=>routes.push({method,route,handlers})]))
  registerSystemAnnouncementRoutes(app,{pool:{},adminRequired:auth,runWorker:()=>{}})
  for (const route of routes) assert.equal(route.handlers.includes(auth),route.route.startsWith('/v1/admin/'))
})
