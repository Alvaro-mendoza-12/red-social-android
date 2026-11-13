'use strict'

// Dedicated local API + database only. Synthetic accounts and evidence are retained.
const assert = require('node:assert/strict')
const { randomUUID, randomBytes } = require('node:crypto')
const { Pool } = require('pg')
const bcrypt = require('bcrypt')

async function main() {
  const api = new URL(process.env.LIVE_INVITATION_QA_API_URL || 'invalid:')
  const database = new URL(process.env.LIVE_INVITATION_QA_DATABASE_URL || 'invalid:')
  assert.equal(api.protocol,'http:')
  assert.ok(['127.0.0.1','localhost'].includes(api.hostname))
  assert.ok(['127.0.0.1','localhost'].includes(database.hostname))
  assert.ok(['/novvin_124','/novvin_live_invitation_test'].includes(database.pathname))
  const pool = new Pool({ connectionString:database.href })
  async function call(token,path,body,method=body?'POST':'GET',expected=200) {
    const response = await fetch(new URL(path,api),{method,headers:{
      'Content-Type':'application/json',...(token?{Authorization:'Bearer '+token}:{}),
    },body:body?JSON.stringify(body):undefined,signal:AbortSignal.timeout(15000)})
    const data = await response.json()
    assert.ok((Array.isArray(expected)?expected:[expected]).includes(response.status),
      method+' HTTP '+response.status+' '+String(data.error || ''))
    return data
  }
  async function account(name) {
    const email='qa-invitation-'+randomUUID()+'@example.invalid'
    const password='Aa9!'+randomBytes(18).toString('hex')
    // Fixture preparation uses only the explicitly isolated database. Logging
    // in still exercises real authentication; no registration limit is reset.
    const inserted=await pool.query(`INSERT INTO fallback_users(email,password_hash,password_enabled,
      display_name,sync_source,sync_status,local_password_set_at,password_changed_by_user_at)
      VALUES($1,$2,true,$3,'local','local_only',now(),now()) RETURNING id::text`,
    [email,await bcrypt.hash(password,12),name])
    const value=await call(null,'/v1/auth/login',{email,password})
    assert.equal(value.user.id,inserted.rows[0].id)
    return {id:value.user.id,email,token:value.accessToken,alias:randomUUID()}
  }
  async function alias(user) {
    const result=await pool.query('UPDATE fallback_users SET supabase_user_id=$2::uuid WHERE id::text=$1 AND email=$3',
      [user.id,user.alias,user.email])
    assert.equal(result.rowCount,1,'The HTTP account must belong to this isolated database')
  }
  const lives=[]
  try {
    const host=await account('Anfitrión de prueba'),guest=await account('Invitado de prueba')
    await alias(host);await alias(guest)
    async function createLive() {
      const id=randomUUID(),base='/v1/data/live/'+id
      await call(host.token,'/v1/data/live',{id,title:'Permisos LIVE de prueba',visibility:'private',giftsEnabled:false,battlesEnabled:false},'POST',201)
      lives.push({base,token:host.token})
      await call(host.token,base+'/state',{isActive:true},'PATCH')
      return {id,base}
    }
    const {id,base}=await createLive()
    const nonce=randomUUID()
    const first=await call(host.token,base+'/guests/invitations',{inviteeUserId:guest.id,requestNonce:nonce},'POST',201)
    assert.equal(first.invitation.invitee_user_id,guest.alias)
    const retried=await Promise.all([guest.id,guest.alias].map(inviteeUserId=>call(host.token,base+'/guests/invitations',{inviteeUserId,requestNonce:nonce})))
    assert.ok(retried.every(value=>value.invitation.id===first.invitation.id))
    await call(guest.token,base+'/guests/invitations/'+first.invitation.id,{status:'accepted'},'PATCH')
    await call(guest.token,base)
    await pool.query('UPDATE fallback_live_guest_invitations SET invitee_user_id=$2 WHERE id=$1',
      [first.invitation.id,guest.id])
    await call(guest.token,base)
    await call(guest.token,base+'/games/draw-guess/access')
    assert.equal((await call(host.token,base+'/guests/invitations',{inviteeUserId:guest.alias,requestNonce:nonce})).invitation.id,first.invitation.id)
    await call(host.token,base+'/guests/invitations',{inviteeUserId:guest.alias,requestNonce:randomUUID()},'POST',409)
    console.log('PASS alias local/importado, reintentos concurrentes y acceso con una invitación histórica')

    await pool.query('INSERT INTO fallback_user_blocks(blocker_id,blocked_id) VALUES($1,$2)',[guest.id,host.id])
    await call(guest.token,base,undefined,'GET',404)
    await call(guest.token,base+'/audience',undefined,'GET',404)
    await call(guest.token,base+'/games/draw-guess/access',undefined,'GET',404)
    await call(host.token,base+'/guests/invitations',{inviteeUserId:guest.id,requestNonce:randomUUID()},'POST',400)
    await call(host.token,base+'/guests/invitations',{inviteeUserId:randomUUID(),requestNonce:randomUUID()},'POST',400)
    assert.equal((await pool.query('SELECT count(*)::int AS total FROM fallback_live_guest_invitations WHERE live_id=$1',[id])).rows[0].total,1)
    console.log('PASS bloqueo con IDs antiguos revoca lectura y juego; objetivos bloqueados o inexistentes no crean filas')

    const nextGuest=await account('Invitado dos')
    const pendingNonce=randomUUID()
    const pending=await call(host.token,base+'/guests/invitations',{inviteeUserId:nextGuest.id,requestNonce:pendingNonce},'POST',201)
    await pool.query("UPDATE fallback_live_guest_invitations SET expires_at=now()-interval '1 second' WHERE id=$1",[pending.invitation.id])
    const next=await call(host.token,base+'/guests/invitations',{inviteeUserId:nextGuest.id,requestNonce:randomUUID()},'POST',201)
    assert.notEqual(next.invitation.id,pending.invitation.id)
    const expired=await call(host.token,base+'/guests/invitations',{inviteeUserId:nextGuest.id,requestNonce:pendingNonce})
    assert.equal(expired.invitation.status,'expired')
    await call(nextGuest.token,base+'/guests/invitations/'+pending.invitation.id,{status:'accepted'},'PATCH',409)
    await call(host.token,base+'/state',{isActive:false},'PATCH')
    await call(host.token,base+'/guests/invitations',{inviteeUserId:nextGuest.id,requestNonce:randomUUID()},'POST',409)
    console.log('PASS vencimiento, nuevo intento independiente y rechazo después del cierre')

    const limitLive=await createLive()
    // Allow one fixed-window boundary while keeping the exercise bounded.
    let limited=false
    for(let index=0;index<61;index++) {
      const result=await call(host.token,limitLive.base+'/guests/invitations',{
        inviteeUserId:randomUUID(),requestNonce:randomUUID(),
      },'POST',[400,429])
      if(result.error==='LIVE_INVITATION_RATE_LIMITED'){limited=true;break}
      assert.equal(result.error,'LIVE_INVITATION_TARGET_INVALID')
    }
    assert.equal(limited,true)
    assert.equal((await pool.query('SELECT count(*)::int AS total FROM fallback_live_guest_invitations WHERE live_id=$1',[limitLive.id])).rows[0].total,0)
    console.log('PASS límite de 30 intentos por minuto sin insertar invitaciones inválidas')
  } finally {
    for (const {base,token} of lives) await call(token,base+'/state',{isActive:false},'PATCH').catch(()=>{})
    await pool.end()
  }
}

main().catch(error=>{console.error('FAIL',String(error.message).slice(0,800));process.exitCode=1})
