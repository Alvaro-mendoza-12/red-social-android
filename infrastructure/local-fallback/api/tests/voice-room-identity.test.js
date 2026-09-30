'use strict'
const test = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const { ensureVoiceRoomCodes } = require('../src/voice-room-identity')

test('short codes are assigned in one locked migration and preserve old aliases', async () => {
  const queries = []
  await ensureVoiceRoomCodes({ query: async (sql) => { queries.push(sql); return { rows: [] } } })
  assert.equal(queries.length, 1)
  assert.match(queries[0], /pg_advisory_xact_lock/)
  assert.match(queries[0], /MAXVALUE 9999999/)
  assert.match(queries[0], /NO CYCLE/)
  assert.match(queries[0], /legacy_readable_id = COALESCE/)
  assert.match(queries[0], /SET NOT NULL/)
  assert.match(queries[0], /CREATE UNIQUE INDEX/)
})

test('room creation rejects owner changes atomically and assigns server codes', () => {
  const source = fs.readFileSync(path.join(__dirname, '../src/data-routes.js'), 'utf8')
  const route = source.split("app.post('/v1/data/voice/rooms',")[1].split("app.get('/v1/data/voice/rooms',")[0]
  assert.match(route, /voiceRoomRowsByAliases\(pool, \[requestedId\]\)/)
  assert.match(route, /VOICE_ROOM_CREATOR_REQUIRED/)
  assert.match(route, /WHERE lower\(fallback_voice_rooms.creator_id\) = ANY\(\$10::text\[\]\)/)
  assert.match(route, /nextval\('fallback_voice_room_code_seq'\)::text/)
  assert.doesNotMatch(route, /readable_id = EXCLUDED.readable_id/)
  assert.doesNotMatch(route, /req.body\?\.(readableId|readable_id)/)
})

test('legacy codes and channel aliases remain indexed and resolvable', () => {
  const source = fs.readFileSync(path.join(__dirname, '../src/data-routes.js'), 'utf8')
  const matcher = source.split('function voiceRoomMatches(')[1].split('function voiceRoomMode(')[0]
  assert.match(matcher, /legacy_readable_id/)
  assert.match(matcher, /agora_channel_name/)
  assert.match(source, /lower\(COALESCE\(legacy_readable_id, ''\)\) = ANY/)
})
