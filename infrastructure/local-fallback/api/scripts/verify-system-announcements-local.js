const assert = require('node:assert/strict')
const { randomUUID } = require('node:crypto')
const { Client } = require('pg')
const { publishAnnouncement, createAnnouncementWorker, registerSystemAnnouncementRoutes } = require('../src/system-announcements')

// Every table used below is temporary on one connection. No production user,
// notification, push token or announcement is inserted or changed.
async function main() {
  const client = new Client({ connectionString: process.env.DATABASE_URL, connectionTimeoutMillis: 5000 })
  await client.connect()
  try {
    await client.query('SET search_path = pg_temp, public')
    for (const table of ['fallback_users','fallback_notifications','fallback_system_announcements','fallback_system_announcement_deliveries']) {
      await client.query(`CREATE TEMP TABLE ${table} (LIKE public.${table} INCLUDING ALL)`)
    }
    const owners = [randomUUID(), randomUUID(), randomUUID()]
    for (const [index, id] of owners.entries()) await client.query(`INSERT INTO fallback_users(id,email,password_hash,display_name)
      VALUES($1,$2,'isolated fixture','QA Temporal')`, [id,`qa-system-${index}-${randomUUID()}@example.invalid`])
    // A production pool has multiple connections. Serialize calls here because
    // every isolated query deliberately shares one temporary-table connection.
    let queue = Promise.resolve()
    const query = (sql, params) => {
      const pending = queue.then(() => client.query(sql, params))
      queue = pending.catch(() => {})
      return pending
    }
    const pool = { query, connect: async () => ({ query, release() {} }) }
    const id = randomUUID(), expiresAt = new Date(Date.now()+3600000).toISOString()
    const notice = {id,kind:'update',title:'Actualización de prueba aislada',body:'No se envía a ningún usuario real.',expiresAt,versionCode:118}
    assert.equal((await publishAnnouncement(pool,notice,owners[0])).audience,3)
    assert.equal((await publishAnnouncement(pool,notice,owners[0])).duplicate,true)
    assert.equal(Number((await client.query('SELECT count(*) AS n FROM fallback_notifications')).rows[0].n),3)
    await assert.rejects(publishAnnouncement(pool,{...notice,body:'Otro mensaje'},owners[0]),/ANNOUNCEMENT_ID_CONFLICT/)
    const targets = [], pushed = []
    const worker = createAnnouncementWorker(pool,{
      publish:async channel=>{targets.push(channel);return true},
      push:async (_pool,notification)=>{pushed.push(notification.recipient_id);return {sent:1,failed:0}},
    })
    await worker(); await worker()
    assert.deepEqual(pushed.sort(),owners.sort())
    assert.equal(targets.length,3)
    assert.equal(Number((await client.query('SELECT count(*) AS n FROM fallback_system_announcement_deliveries WHERE delivered_at IS NOT NULL')).rows[0].n),3)
    const routes = []
    const app = Object.fromEntries(['get','post','delete'].map(method=>[method,(route,...handlers)=>routes.push({method,route,handler:handlers.at(-1)})]))
    registerSystemAnnouncementRoutes(app,{pool,adminRequired:()=>{},runWorker:worker})
    const read = routes.find(r=>r.route==='/v1/system/announcements').handler
    for (const [versionCode, expected] of [[117,1],[118,0],[119,0]]) {
      let response, failure
      await read({query:{versionCode}},{set(){},json(value){response=value}},error=>{failure=error})
      if (failure) throw failure
      assert.equal(response.items.length,expected)
    }
    await client.query('UPDATE fallback_system_announcements SET revoked_at=NOW() WHERE id=$1',[id])
    let revoked
    await read({query:{versionCode:117}},{set(){},json(value){revoked=value}},error=>{throw error})
    assert.equal(revoked.items.length,0)
    console.log(JSON.stringify({ok:true,temporaryRecipients:3,notifications:3,idempotent:true,pushAndRealtime:3,installedVersionFilter:true,revocation:true,productionWrites:0}))
  } finally { await client.end() }
}
main().catch(error=>{ console.error(error.message); process.exitCode=1 })
