'use strict'
const { test } = require('node:test')
const assert = require('node:assert/strict')
const { randomUUID } = require('node:crypto')
const { Pool } = require('pg')
const { activeGuestCount } = require('../src/live-routes')

test('guest capacity counts identities once across historical invitation and participant aliases',
  {skip:!process.env.LIVE_GUEST_COUNT_TEST_DATABASE_URL},async t=>{
    const url=new URL(process.env.LIVE_GUEST_COUNT_TEST_DATABASE_URL)
    assert.ok(['127.0.0.1','localhost'].includes(url.hostname))
    assert.equal(url.pathname,'/novvin_live_guest_count_test')
    const db=new Pool({connectionString:url.href});t.after(()=>db.end())
    await db.query(`CREATE TABLE IF NOT EXISTS fallback_users(id uuid PRIMARY KEY,supabase_user_id uuid);
      CREATE TABLE IF NOT EXISTS fallback_live_guest_invitations(id uuid PRIMARY KEY DEFAULT gen_random_uuid(),
        live_id uuid,invitee_user_id text,status text);
      CREATE TABLE IF NOT EXISTS fallback_live_participants(live_id uuid,user_id text,state text,
        updated_at timestamptz DEFAULT now(),PRIMARY KEY(live_id,user_id))`)
    const live=randomUUID(),local=randomUUID(),alias=randomUUID()
    await db.query('INSERT INTO fallback_users VALUES($1,$2)',[local,alias])
    await db.query("INSERT INTO fallback_live_guest_invitations(live_id,invitee_user_id,status) VALUES($1,$2,'accepted')",[live,local])
    await db.query("INSERT INTO fallback_live_participants(live_id,user_id,state) VALUES($1,$2,'connected')",[live,alias])
    assert.equal(await activeGuestCount(db,live),1,'Historical invite plus canonical presence is one occupied seat')
    assert.equal(await activeGuestCount(db,live,[local,alias]),0,'Rejoining excludes every alias of this person')
    assert.equal(await activeGuestCount(db,live,local),0)
    assert.equal(await activeGuestCount(db,live,alias),0)
    await db.query("INSERT INTO fallback_live_participants(live_id,user_id,state) VALUES($1,$2,'connected')",[live,local])
    await db.query("INSERT INTO fallback_live_guest_invitations(live_id,invitee_user_id,status) VALUES($1,$2,'accepted')",[live,alias])
    assert.equal(await activeGuestCount(db,live),1,'Old and new presence/invitation rows do not consume two seats')
    await db.query("UPDATE fallback_live_participants SET state='invited',updated_at=now()-interval '3 minutes' WHERE live_id=$1",[live])
    assert.equal(await activeGuestCount(db,live),0)
    await db.query("UPDATE fallback_live_participants SET state='invited',updated_at=now() WHERE live_id=$1",[live])
    assert.equal(await activeGuestCount(db,live),1)
    const another=randomUUID()
    await db.query('INSERT INTO fallback_users VALUES($1,NULL)',[another])
    await db.query("INSERT INTO fallback_live_guest_invitations(live_id,invitee_user_id,status) VALUES($1,$2,'accepted')",[live,another])
    await db.query("INSERT INTO fallback_live_participants(live_id,user_id,state) VALUES($1,$2,'connected')",[live,another])
    assert.equal(await activeGuestCount(db,live),2)
    assert.equal(await activeGuestCount(db,live,[local,alias]),1)
  })
