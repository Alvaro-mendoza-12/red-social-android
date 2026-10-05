'use strict'
// Explicit QA fixture: only localhost, own disposable schema, synthetic accounts.
// Does not run inside the production server or alter production configuration.
const { Pool } = require('pg')
const express = require('express')
const https = require('node:https')
const fs = require('node:fs')
const { randomUUID, randomBytes } = require('node:crypto')
const jwt = require('jsonwebtoken')
const { authenticateClientSession } = require('../src/client-auth-session')
const { ensureRaffleTables } = require('../src/raffles/service')
const { registerRaffleRoutes, startRaffleWorker } = require('../src/raffles/routes')

async function main() {
  const connectionString = process.env.RAFFLE_TEST_DATABASE_URL
  if (!connectionString || !['127.0.0.1', 'localhost'].includes(new URL(connectionString).hostname)) throw Error('Local disposable PostgreSQL required')
  const schema = `raffle_device_${randomUUID().replaceAll('-', '')}`
  const rootPool = new Pool({ connectionString })
  await rootPool.query(`CREATE SCHEMA ${schema}`)
  const pool = new Pool({ connectionString, options: `-c search_path=${schema},public` })
  let server, stopWorker
  async function stop() {
    stopWorker?.()
    if (server?.listening) await new Promise((resolve) => server.close(resolve))
    await pool.end()
    if (!/^raffle_device_[a-f0-9]{32}$/.test(schema)) throw Error('Invalid owned schema')
    await rootPool.query(`DROP SCHEMA ${schema} CASCADE`)
    await rootPool.end()
  }
  try {
    await pool.query(`CREATE TABLE fallback_users(id UUID PRIMARY KEY,display_name TEXT NOT NULL);
      CREATE TABLE fallback_collection_rooms(id UUID PRIMARY KEY,room_kind TEXT,creator_id TEXT,is_deleted BOOLEAN DEFAULT FALSE);
      CREATE TABLE fallback_collection_members(room_kind TEXT,room_id TEXT,user_id TEXT,role TEXT,status TEXT DEFAULT 'joined');
      CREATE TABLE fallback_room_messages(id UUID PRIMARY KEY,room_kind TEXT,room_id TEXT,channel_id TEXT,sender_id TEXT,content TEXT,content_type TEXT,media_url TEXT,created_at TIMESTAMPTZ DEFAULT NOW(),updated_at TIMESTAMPTZ DEFAULT NOW());
      CREATE TABLE qa_sessions(id UUID PRIMARY KEY,user_id UUID NOT NULL,expires_at TIMESTAMPTZ NOT NULL);`)
    await ensureRaffleTables(pool)
    const secret = randomBytes(48).toString('hex'), accounts = {}
    for (const role of ['owner', 'admin', 'member', 'outsider']) {
      const id = randomUUID(), sid = randomUUID()
      await pool.query('INSERT INTO fallback_users VALUES($1,$2)', [id, `QA ${role}`])
      await pool.query("INSERT INTO qa_sessions VALUES($1,$2,NOW()+INTERVAL '1 hour')", [sid, id])
      accounts[role] = { id, token: jwt.sign({ sub: id, sid }, secret, { issuer: 'raffle-device-qa', audience: 'conectate-clients', expiresIn: '1h' }) }
    }
    const rooms = {}
    for (const kind of ['group', 'community']) {
      const id = randomUUID(); rooms[kind] = id
      await pool.query('INSERT INTO fallback_collection_rooms(id,room_kind,creator_id) VALUES($1,$2,$3)', [id, kind, accounts.owner.id])
      for (const role of ['owner', 'admin', 'member']) await pool.query('INSERT INTO fallback_collection_members(room_kind,room_id,user_id,role) VALUES($1,$2,$3,$4)', [kind, id, accounts[role].id, role])
    }
    const app = express(); app.use(express.json({ limit: '100kb' }))
    const authRequired = async (req, res, next) => {
      try {
        req.auth = await authenticateClientSession({
          token: String(req.headers.authorization || '').replace(/^Bearer\s+/i, ''),
          verifyToken: (token) => jwt.verify(token, secret, { issuer: 'raffle-device-qa', audience: 'conectate-clients' }),
          userExists: async (id) => Boolean((await pool.query('SELECT 1 FROM fallback_users WHERE id=$1', [id])).rows[0]),
          touchSession: async (auth) => Boolean((await pool.query('SELECT 1 FROM qa_sessions WHERE id=$1 AND user_id=$2 AND expires_at>NOW()', [auth.sid, auth.sub])).rows[0]),
        }); next()
      } catch (error) { res.status(error.status || 401).json({ error: error.code || 'INVALID_TOKEN' }) }
    }
    app.get('/qa/accounts', (_req, res) => res.json({ accounts, rooms }))
    const service = registerRaffleRoutes(app, {
      pool, authRequired,
      getCurrentUser: async (db, req) => (await db.query('SELECT * FROM fallback_users WHERE id=$1', [req.auth.sub])).rows[0],
      userIdentity: (u) => ({ ids: new Set([u.id]), localUserId: u.id, displayUserId: u.id }),
      loadCollectionRoomAccess: async (db, _user, kind, id) => {
        const room = (await db.query('SELECT * FROM fallback_collection_rooms WHERE id=$1 AND room_kind=$2 AND is_deleted=FALSE', [id, kind])).rows[0]
        return room ? { room, roomMembers: (await db.query('SELECT * FROM fallback_collection_members WHERE room_kind=$1 AND room_id=$2', [kind, id])).rows } : null
      },
      memberUserId: (m) => m.user_id, rowMatchesAny: (row, keys, ids) => keys.some((key) => ids.has(row[key])),
      publicProfilesByIds: async (db, ids) => (await db.query('SELECT * FROM fallback_users WHERE id::text=ANY($1)', [ids])).rows,
      ensureDefaultCommunityChannel: async () => ({ id: 'qa-general' }),
      publish: async () => true, publishRoomCard: async () => {},
    })
    app.use((_error, _req, res, _next) => res.status(500).json({ error: 'QA_INTERNAL_ERROR' }))
    const cert = fs.readFileSync(process.env.RAFFLE_QA_CERT_PATH)
    server = https.createServer({ cert, key: fs.readFileSync(process.env.RAFFLE_QA_KEY_PATH) }, app)
    await new Promise((resolve, reject) => { server.once('error', reject); server.listen(55441, '127.0.0.1', resolve) })
    stopWorker = startRaffleWorker(service)
    console.log('Raffle HTTPS fixture ready on localhost:55441; JWT sessions and own PostgreSQL schema')
    for (const signal of ['SIGINT', 'SIGTERM']) process.once(signal, () => { stop().then(() => process.exit(0)) })
    process.stdin.on('data', (data) => { if (data.toString().trim() === 'stop') stop().then(() => process.exit(0)) })
  } catch (error) { await stop(); throw error }
}
main().catch((error) => { console.error(error.name); process.exitCode = 1 })
