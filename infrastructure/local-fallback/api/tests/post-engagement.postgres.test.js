'use strict'
const { test } = require('node:test')
const assert = require('node:assert/strict')
const { Pool } = require('pg')
const express = require('express')
const { registerDataRoutes } = require('../src/data-routes')

const AUTHOR = '11111111-1111-4111-8111-111111111111', VIEWER = '22222222-2222-4222-8222-222222222222'
const AUTHOR_ALIAS = '33333333-3333-4333-8333-333333333333', VIEWER_ALIAS = '44444444-4444-4444-8444-444444444444'
const POST = '55555555-5555-4555-8555-555555555555'

async function fixture(connectionString) {
  const url = new URL(connectionString)
  if (!['127.0.0.1','localhost'].includes(url.hostname) || url.pathname !== '/novvin_engagement_test') throw Error('Requires the isolated loopback novvin_engagement_test database')
  const pool = new Pool({connectionString,max:5})
  await pool.query(`INSERT INTO fallback_users(id,email,password_hash,display_name,supabase_user_id)
    VALUES($1,'qa-author@example.invalid','qa-not-a-real-password','QA author',$2),
          ($3,'qa-viewer@example.invalid','qa-not-a-real-password','QA viewer',$4) ON CONFLICT DO NOTHING`, [AUTHOR,AUTHOR_ALIAS,VIEWER,VIEWER_ALIAS])
  await pool.query(`INSERT INTO fallback_posts(id,user_id,user_name,content,visibility)
    VALUES($1,$2,'QA author','67 de prueba','public') ON CONFLICT DO NOTHING`, [POST,AUTHOR_ALIAS])
  await pool.query('DELETE FROM fallback_post_reactions WHERE post_id=$1',[POST])
  for (const [user, type] of [[VIEWER_ALIAS,'LIKE'],[AUTHOR_ALIAS,'LIKE'],['66666666-6666-4666-8666-666666666666','FIRE'],['77777777-7777-4777-8777-777777777777','HAHA']]) {
    await pool.query('INSERT INTO fallback_post_reactions(post_id,user_id,reaction_type) VALUES($1,$2,$3)',[POST,user,type])
  }
  await pool.query('DELETE FROM fallback_post_comments WHERE post_id=$1',[POST])
  await pool.query(`INSERT INTO fallback_post_comments(post_id,user_id,content) VALUES($1,$2,'Primero'),($1,$3,'Segundo')`,[POST,AUTHOR_ALIAS,VIEWER_ALIAS])
  const classification = (await pool.query(`INSERT INTO fallback_algorithm_content_classifications(content_kind,content_id,creator_id,safety_state)
    VALUES('post',$1,$2,'allowed') ON CONFLICT(content_kind,content_id) DO UPDATE SET safety_state='allowed' RETURNING id`,[POST,AUTHOR_ALIAS])).rows[0]
  await pool.query(`INSERT INTO fallback_algorithm_content_topics(classification_id,topic_key,confidence) VALUES($1,'music',1) ON CONFLICT DO NOTHING`,[classification.id])
  const app = express(); app.use(express.json({limit:'16kb'}))
  const authRequired = (req,res,next)=>{
    const token = String(req.headers.authorization || '')
    const actor = token === 'Bearer qa-viewer' ? VIEWER : token === 'Bearer qa-author' ? AUTHOR : null
    if (!actor) return res.status(401).json({error:'UNAUTHORIZED'})
    req.auth={sub:actor}; next()
  }
  registerDataRoutes(app,{pool,authRequired,appealAuthRequired:authRequired})
  app.use((error,_req,res,_next)=>{console.error('QA failure',{code:error.code,message:error.message});res.status(500).json({error:'QA_FAILURE'})})
  return {pool,app}
}

test('public, personalized and detail HTTP reads retain durable counts after reopening PostgreSQL connections',
  {skip:!process.env.ENGAGEMENT_TEST_DATABASE_URL || process.env.ENGAGEMENT_QA_SERVE === 'true'}, async t=>{
    const current = await fixture(process.env.ENGAGEMENT_TEST_DATABASE_URL)
    const {pool,app}=current
    const server=await new Promise(resolve=>{const value=app.listen(0,'127.0.0.1',()=>resolve(value))})
    t.after(async()=>{await new Promise(resolve=>server.close(resolve));await pool.end()})
    const base=`http://127.0.0.1:${server.address().port}`
    const read = async path => {
      const response=await fetch(base+path,{headers:{Authorization:'Bearer qa-viewer'}})
      const body=await response.json()
      assert.equal(response.status,200,JSON.stringify(body))
      return body
    }
    async function check(expected, liked) {
      const feed=(await read('/v1/data/feed?mode=discover')).items.find(row=>row.id===POST)
      const personalized=(await read('/v1/data/recommendations?surface=social')).items.find(row=>row.id===POST)
      const detail=(await read(`/v1/data/feed/posts/${POST}`)).post
      for (const row of [feed,personalized,detail]) {
        assert.ok(row,'The classified public post must be returned')
        assert.equal(row.like_count,expected);assert.equal(row.comment_count,2);assert.equal(row.liked_by_me,liked)
        assert.equal(row.my_reaction,liked?'LIKE':null)
        assert.equal(row.reaction_counts.FIRE,1);assert.equal(row.reaction_counts.HAHA,1)
      }
    }
    await check(4,true)
    await pool.query('UPDATE fallback_post_reactions SET is_deleted=TRUE,updated_at=NOW() WHERE post_id=$1 AND user_id=$2',[POST,VIEWER_ALIAS])
    await check(3,false)
    const reopened = new Pool({connectionString:process.env.ENGAGEMENT_TEST_DATABASE_URL,max:1})
    try {
      assert.equal((await reopened.query('SELECT count(*)::int AS total FROM fallback_post_reactions WHERE post_id=$1 AND is_deleted=FALSE',[POST])).rows[0].total,3)
      assert.equal((await reopened.query('SELECT count(*)::int AS total FROM fallback_post_comments WHERE post_id=$1 AND is_deleted=FALSE',[POST])).rows[0].total,2)
    } finally {await reopened.end()}
    await check(3,false)
    assert.equal((await fetch(base+'/v1/data/feed')).status,401)
  })

module.exports={fixture}
