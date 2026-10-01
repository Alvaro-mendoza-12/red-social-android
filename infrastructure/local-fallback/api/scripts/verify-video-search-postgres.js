'use strict'
// Exercises reviewed search SQL in a rollback-only schema; no production rows are read or modified.
const assert = require('node:assert/strict')
const crypto = require('node:crypto')
const { Client } = require('pg')
const { searchVisibleVideoCommentIds } = require('../src/data-routes')

async function verify() {
  const client = new Client({ connectionString: process.env.DATABASE_URL })
  await client.connect()
  const schema = `video_search_qa_${crypto.randomBytes(8).toString('hex')}`
  try {
    await client.query('BEGIN')
    await client.query(`CREATE SCHEMA "${schema}"`)
    await client.query(`SET LOCAL search_path TO "${schema}"`)
    await client.query(`CREATE TABLE fallback_video_comments(id UUID PRIMARY KEY, video_id TEXT, content TEXT,
      is_deleted BOOLEAN DEFAULT FALSE, created_at TIMESTAMPTZ DEFAULT NOW())`)
    await client.query('CREATE TABLE fallback_video_comment_reports(comment_id TEXT,video_id TEXT,reporter_id TEXT)')
    // Imported rows are deliberately empty; the adapter must stay inside this schema.
    const pool = { async query(sql, args) {
      if (sql.includes('FROM information_schema.tables') || sql.includes('FROM supabase_import.')) return { rows: [] }
      return client.query(sql, args)
    } }
    const good = crypto.randomUUID(), reported = crypto.randomUUID()
    await client.query(`INSERT INTO fallback_video_comments(id,video_id,content,is_deleted) VALUES
      ($1,'visible','MÚSICA y cocina 100%_literal',FALSE),($2,'reported','Música',FALSE),
      (gen_random_uuid(),'deleted','Música',TRUE),(gen_random_uuid(),'private','Música',FALSE)`, [good, reported])
    await client.query("INSERT INTO fallback_video_comment_reports VALUES($1,'reported','viewer')", [reported])
    const identity = { values: ['viewer'] }
    assert.deepEqual([...await searchVisibleVideoCommentIds(pool, ['visible', 'reported', 'deleted'], identity, 'musica')], ['visible'])
    assert.deepEqual([...await searchVisibleVideoCommentIds(pool, ['visible'], identity, '100%_literal')], ['visible'])
    assert.deepEqual([...await searchVisibleVideoCommentIds(pool, ['visible'], identity, 'absent')], [])
    console.log(JSON.stringify({ passed: true, accents: true, literalWildcards: true, reportedExcluded: true,
      deletedExcluded: true, candidateMembershipEnforced: true, scope: 'Isolated PostgreSQL schema rolled back' }))
  } finally {
    await client.query('ROLLBACK')
    await client.end()
  }
}
verify().catch(() => { console.error('Isolated PostgreSQL video search verification failed'); process.exitCode = 1 })
