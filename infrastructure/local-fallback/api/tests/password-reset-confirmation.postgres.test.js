'use strict'
const {test} = require('node:test')
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const crypto = require('node:crypto')
const vm = require('node:vm')
const {inspect} = require('node:util')
const {Pool} = require('pg')
const bcrypt = require('bcrypt')
const express = require('express')
const {requireBcryptPasswordLength} = require('../src/new-password-policy')
const {publishWebSessionsRevoked} = require('../src/web-session-events')

const connectionString = process.env.NOVVIN_PASSWORD_RESET_TEST_DATABASE_URL
const sourceFile = process.env.NOVVIN_PASSWORD_RESET_TEST_SOURCE || path.join(__dirname,'../src/server.js')
const source = fs.readFileSync(sourceFile,'utf8')
const start = source.indexOf("app.post('/v1/auth/password-reset/confirm',")
const end = source.indexOf("app.post('/v1/auth/password-recovery-codes/confirm',",start)
assert(start > 0 && end > start)
const route = source.slice(start,end)
const originalPassword = 'Original-QA169-only'
const nextPassword = 'Updated-QA169-only'
const userId = '11111111-1111-4111-8111-111111111169'
const selectColumns = 'id,email,password_hash,password_enabled,local_password_set_at,password_changed_by_user_at,supabase_user_id,sync_status,sync_source,sync_error,last_login_at'
const hash = value => crypto.createHash('sha256').update(value.trim()).digest('hex')

async function fixture(t, failure = '', twoFactor = false) {
  // Never silently fall back to DATABASE_URL or the live database.
  const url = new URL(connectionString)
  assert.equal(url.pathname,'/novvin_audit169')
  assert.equal(url.username,'fixture169')
  const schema = 'reset169_'+crypto.randomUUID().replaceAll('-','')
  const admin = new Pool({connectionString,max:2})
  await admin.query(`CREATE SCHEMA ${schema}`)
  const pool = new Pool({connectionString,max:3,options:`-c search_path=${schema}`})
  t.after(async()=>{await pool.end();await admin.query(`DROP SCHEMA ${schema} CASCADE`);await admin.end()})
  await pool.query(`
    CREATE TABLE fallback_users(id uuid PRIMARY KEY,email text,password_hash text,password_enabled boolean,
      local_password_set_at timestamptz,password_changed_by_user_at timestamptz,supabase_user_id uuid,
      sync_status text,sync_source text,sync_error text,last_login_at timestamptz);
    CREATE TABLE fallback_password_reset_tokens(id uuid PRIMARY KEY,user_id uuid,token_hash text,
      expires_at timestamptz,consumed_at timestamptz,recovery_case_id uuid);
    CREATE TABLE fallback_password_reset_email_codes(user_id uuid,consumed_at timestamptz);
    CREATE TABLE fallback_account_recovery_cases(id uuid,user_id uuid,two_factor_reset boolean,status text);
    CREATE TABLE fallback_totp_recovery_codes(user_id uuid);
    CREATE TABLE fallback_totp_factors(user_id uuid);
    CREATE TABLE fixture_sessions(id uuid PRIMARY KEY,user_id uuid,revoked boolean);
  `)
  await pool.query('INSERT INTO fallback_users(id,email,password_hash) VALUES($1,$2,$3)',[userId,'qa169@example.invalid',await bcrypt.hash(originalPassword,4)])
  const grant = crypto.randomBytes(32).toString('hex')
  await pool.query(`INSERT INTO fallback_password_reset_tokens(id,user_id,token_hash,expires_at)
    VALUES($1,$2,$3,NOW()+interval '5 minutes')`,[crypto.randomUUID(),userId,hash(grant)])
  await pool.query('INSERT INTO fallback_password_reset_email_codes(user_id) VALUES($1)',[userId])
  const sessionId = crypto.randomUUID()
  await pool.query('INSERT INTO fixture_sessions(id,user_id,revoked) VALUES($1,$2,false)',[sessionId,userId])
  const app = express()
  app.use(express.json())
  let sessionAttempts = 0, auditAttempts = 0
  const logged = []
  const revocationEvents = []
  vm.runInNewContext(route,{
    app,pool,userSelectColumns:selectColumns,bcrypt,requireBcryptPasswordLength,
    isWebClientRequest:()=>false,requireTrustedWebSessionRequest:()=>true,
    hashPasswordResetToken:hash,
    revokeAllUserSessions:async(id,client)=>{
      if(failure==='precommit')throw new Error('QA169 before COMMIT')
      const {rows} = await client.query('UPDATE fixture_sessions SET revoked=true WHERE user_id=$1 RETURNING id',[id])
      return rows
    },
    publishWebSessionsRevoked:(id,sessions)=>publishWebSessionsRevoked(id,sessions,async (channel,event)=>{
      revocationEvents.push({channel,event})
      return true
    }),
    hydrateUserRow:async row=>{if(failure==='hydrate')throw new Error('QA169 hydration unavailable');return row},
    sanitizeUser:row=>({id:row.id,email:row.email}),
    recordAudit:async()=>{auditAttempts++;if(failure==='audit')throw new Error('QA169_FAKE_SENSITIVE_MARKER')},
    hasEnabledSecondFactor:async()=>{if(failure==='second-factor')throw new Error('QA169 factor lookup unavailable');return twoFactor},
    issueSessionForRequest:async(_user,_req,res)=>{
      sessionAttempts++
      if(failure==='session')throw new Error('QA169 session unavailable')
      if(failure==='cookie'){
        res.append('Set-Cookie','qa169-session=must-not-be-delivered; HttpOnly; Secure')
        throw new Error('QA169 after session cookie prepared')
      }
      return {accessToken:'qa169-access',refreshToken:'qa169-refresh',user:{id:userId}}
    },
    safeErrorLogDetails:()=>({code:'QA169_INJECTED_FAILURE'}),isProduction:true,
    console:{error:(...args)=>logged.push(args),warn:(...args)=>logged.push(args)},
  },{filename:sourceFile})
  app.use((error,_req,res,_next)=>res.status(500).json({error:'INTERNAL_ERROR'}))
  const server = await new Promise(resolve=>{const s=app.listen(0,'127.0.0.1',()=>resolve(s))})
  t.after(()=>new Promise(resolve=>server.close(resolve)))
  const request=async(token=grant,password=nextPassword)=>{
    const response=await fetch(`http://127.0.0.1:${server.address().port}/v1/auth/password-reset/confirm`,{
      method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({resetToken:token,newPassword:password})
    })
    return {status:response.status,body:await response.json(),cookie:response.headers.get('set-cookie'),cache:response.headers.get('cache-control')}
  }
  const snapshot=async()=>{
    const [{rows:[user]},{rows:[token]},{rows:[session]},{rows:[code]}]=await Promise.all([
      pool.query('SELECT * FROM fallback_users WHERE id=$1',[userId]),
      pool.query('SELECT * FROM fallback_password_reset_tokens WHERE token_hash=$1',[hash(grant)]),
      pool.query('SELECT * FROM fixture_sessions WHERE user_id=$1',[userId]),
      pool.query('SELECT * FROM fallback_password_reset_email_codes WHERE user_id=$1',[userId]),
    ])
    return {newPassword:await bcrypt.compare(nextPassword,user.password_hash),oldPassword:await bcrypt.compare(originalPassword,user.password_hash),consumed:Boolean(token.consumed_at),sessionsRevoked:session.revoked,emailCodeConsumed:Boolean(code.consumed_at)}
  }
  return {pool,request,snapshot,sessionId,revocationEvents,logs:logged,get sessionAttempts(){return sessionAttempts},get auditAttempts(){return auditAttempts}}
}

for(const failure of ['hydrate','second-factor','session','cookie'])test(`committed password remains acknowledged after ${failure} failure`,{skip:!connectionString},async t=>{
  const current=await fixture(t,failure)
  const response=await current.request()
  assert.deepEqual(await current.snapshot(),{newPassword:true,oldPassword:false,consumed:true,sessionsRevoked:true,emailCodeConsumed:true})
  assert.equal(response.status,200)
  assert.equal(response.body.passwordChanged,true)
  assert.equal(response.body.requiresSignIn,true)
  assert.equal(response.body.accessToken,undefined)
  assert.equal(response.body.refreshToken,undefined)
  assert.equal(response.cookie,null,'a failed session preparation cannot deliver a session cookie')
  assert.match(response.cache,/no-store/)
  const replay=await current.request()
  assert.equal(replay.status,401)
  assert.equal(replay.body.passwordChanged,undefined)
})

test('ordinary confirmation keeps the existing successful session contract',{skip:!connectionString},async t=>{
  const current=await fixture(t)
  const response=await current.request()
  assert.equal(response.status,200)
  assert.equal(response.body.accessToken,'qa169-access')
  assert.equal(response.body.requiresSignIn,undefined)
  assert.equal(current.sessionAttempts,1)
  assert.deepEqual(current.revocationEvents,[{channel:'user:'+userId,event:{event:'web_sessions_revoked',sessionIds:[current.sessionId]}}])
})
test('enabled second factor still prevents session issue',{skip:!connectionString},async t=>{
  const current=await fixture(t,'',true)
  const response=await current.request()
  assert.equal(response.status,200)
  assert.equal(response.body.requiresTwoFactor,true)
  assert.equal(response.body.accessToken,undefined)
  assert.equal(current.sessionAttempts,0)
})
test('precommit failure rolls back password, grants and session revocation without a receipt',{skip:!connectionString},async t=>{
  const current=await fixture(t,'precommit')
  const response=await current.request()
  assert.equal(response.status,500)
  assert.equal(response.body.passwordChanged,undefined)
  assert.deepEqual(await current.snapshot(),{newPassword:false,oldPassword:true,consumed:false,sessionsRevoked:false,emailCodeConsumed:false})
  assert.deepEqual(current.revocationEvents,[])
})
test('expired grant cannot change any state or manufacture a receipt',{skip:!connectionString},async t=>{
  const current=await fixture(t)
  await current.pool.query("UPDATE fallback_password_reset_tokens SET expires_at=NOW()-interval '1 minute'")
  const response=await current.request()
  assert.equal(response.status,401)
  assert.equal(response.body.passwordChanged,undefined)
  assert.deepEqual(await current.snapshot(),{newPassword:false,oldPassword:true,consumed:false,sessionsRevoked:false,emailCodeConsumed:false})
})

test('concurrent confirmations consume the grant once and only the winning password is stored',{skip:!connectionString},async t=>{
  const current=await fixture(t)
  const passwords=[nextPassword,'Another-QA169-only-password']
  const results=await Promise.all(passwords.map(password=>current.request(undefined,password)))
  assert.deepEqual(results.map(response=>response.status).sort(),[200,401])
  const winner=results.findIndex(response=>response.status===200)
  const {rows:[row]}=await current.pool.query('SELECT password_hash FROM fallback_users WHERE id=$1',[userId])
  assert(await bcrypt.compare(passwords[winner],row.password_hash))
  assert.equal(await bcrypt.compare(passwords[1-winner],row.password_hash),false)
})
test('missing assisted recovery case still rolls back and cannot become a confirmed change',{skip:!connectionString},async t=>{
  const current=await fixture(t)
  await current.pool.query('UPDATE fallback_password_reset_tokens SET recovery_case_id=$1',[crypto.randomUUID()])
  const response=await current.request()
  assert.equal(response.status,500)
  assert.equal(response.body.passwordChanged,undefined)
  assert.deepEqual(await current.snapshot(),{newPassword:false,oldPassword:true,consumed:false,sessionsRevoked:false,emailCodeConsumed:false})
})
test('an audit failure is non-fatal and does not print the raw exception',{skip:!connectionString},async t=>{
  const current=await fixture(t,'audit')
  const response=await current.request()
  assert.equal(response.status,200)
  assert.equal(response.body.accessToken,'qa169-access')
  assert.equal(current.auditAttempts,1)
  assert.doesNotMatch(inspect(current.logs),/QA169_FAKE_SENSITIVE_MARKER/)
})
