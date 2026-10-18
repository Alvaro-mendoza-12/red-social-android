const assert = require('node:assert/strict')
const crypto = require('node:crypto')
const { Pool } = require('pg')

const base = String(process.env.E2E_API_URL || 'http://127.0.0.1:3001').replace(/\/$/, '')
const realtime = String(process.env.REALTIME_INTERNAL_URL || 'http://realtime-gateway:3002').replace(/^http/, 'ws')
const pool = new Pool({ connectionString: process.env.DATABASE_URL, max: 1 })
const suffix = crypto.randomUUID(), email = `web-device-e2e-${suffix}@novvin.invalid`
const pairingIds = [], sockets = []
let userId
async function request(path, token, body, method = body ? 'POST' : 'GET', web = false) {
  const response = await fetch(base + path, { method, signal: AbortSignal.timeout(8000), headers: {
    'Content-Type':'application/json', ...(token ? {Authorization:`Bearer ${token}`} : {}),
    ...(web ? {Origin:'https://red-social-android.vercel.app','X-Novvin-Web-Session':'web-v1'} : {}),
  }, ...(body ? {body:JSON.stringify(body)} : {}) })
  const value = await response.json()
  return { status:response.status, value }
}
function nextEvent(socket, type, timeout = 6000) {
  return new Promise((resolve,reject) => {
    const timer = setTimeout(() => { socket.removeEventListener('message',receive); reject(new Error('Timed out waiting for '+type)) },timeout)
    function receive(event) { const data=JSON.parse(String(event.data));if(data.type===type){clearTimeout(timer);socket.removeEventListener('message',receive);resolve(data)} }
    socket.addEventListener('message',receive)
  })
}
function closed(socket) {
  return new Promise((resolve,reject) => { const timer=setTimeout(()=>reject(new Error('Device did not close promptly')),6000);socket.addEventListener('close',event=>{clearTimeout(timer);resolve(event.code)},{once:true}) })
}
async function socketFor(token) {
  const socket = new WebSocket(realtime);sockets.push(socket)
  await new Promise((resolve,reject) => {socket.addEventListener('open',resolve,{once:true});socket.addEventListener('error',()=>reject(new Error('Realtime connection failed')),{once:true})})
  const ready = nextEvent(socket,'connected');socket.send(JSON.stringify({type:'authenticate',token}));await ready
  return socket
}
async function browserSession(mobile, label) {
  const pairing = await request('/v1/auth/web-login-pairing',null,{action:'create',deviceLabel:label})
  assert.equal(pairing.status,200);pairingIds.push(pairing.value.pairId)
  const payload = {pairId:pairing.value.pairId,nonce:pairing.value.nonce}
  const approved = await request('/v1/auth/web-login-pairing',mobile,{...payload,action:'approve'})
  assert.equal(approved.status,200);assert.equal(approved.value.status,'approved')
  const consumed = await request('/v1/auth/web-login-pairing',null,{...payload,action:'consume'},'POST',true)
  assert.equal(consumed.status,200);assert.ok(consumed.value.accessToken)
  const repeated = await request('/v1/auth/web-login-pairing',null,{...payload,action:'consume'},'POST',true)
  assert.equal(repeated.status,409,'A scanned code cannot open another session twice')
  return consumed.value.accessToken
}
async function run() {
  try {
    const registration = await request('/v1/auth/register',null,{email,password:`Device-${suffix}!`,displayName:'QA dispositivos',username:'qadevice'+suffix.replaceAll('-','').slice(0,14)})
    assert.equal(registration.status,201,'Synthetic account registration failed')
    const mobile = registration.value.accessToken;userId=registration.value.user.id
    const first = await browserSession(mobile,'Revisión · primer navegador'), second = await browserSession(mobile,'Revisión · segundo navegador')
    const firstId = JSON.parse(Buffer.from(first.split('.')[1],'base64url').toString()).sid
    const firstSocket = await socketFor(first), secondSocket = await socketFor(second)
    const list = await request('/v1/auth/web-sessions',mobile)
    assert.equal(list.status,200);assert.equal(list.value.sessions.length,2)
    const event = nextEvent(firstSocket,'event'), close = closed(firstSocket), start = performance.now()
    const revoked = await request('/v1/auth/web-sessions/'+firstId,mobile,null,'DELETE')
    assert.equal(revoked.status,200)
    const delivered = await event
    assert.equal(delivered.payload.event,'web_sessions_revoked');assert.ok(delivered.payload.sessionIds.includes(firstId))
    assert.equal(await close,4401)
    const selectedDeviceCloseMs = Math.round(performance.now()-start)
    assert.ok(selectedDeviceCloseMs<2500,'Revocation must not wait for a polling cycle')
    assert.equal(secondSocket.readyState,WebSocket.OPEN)
    assert.equal((await request('/v1/auth/me',first)).status,401)
    assert.equal((await request('/v1/auth/me',second)).status,200)
    assert.equal((await request('/v1/auth/me',mobile)).status,200)
    const closeSecond = closed(secondSocket)
    const all = await request('/v1/auth/web-sessions',mobile,null,'DELETE')
    assert.equal(all.status,200);assert.equal(all.value.revoked,1);assert.equal(await closeSecond,4401)
    assert.equal((await request('/v1/auth/web-sessions',mobile)).value.sessions.length,0)
    return {passed:true,qrApprovalAndConsumption:true,qrReplayRejected:true,linkedDeviceList:true,selectedDeviceCloseMs,otherDevicePreserved:true,mobileSessionPreserved:true,revokeAll:true}
  } finally {
    sockets.forEach(socket=>socket.close())
    if(pairingIds.length)await pool.query('DELETE FROM fallback_web_login_pairings WHERE id=ANY($1::uuid[])',[pairingIds])
    await pool.query('DELETE FROM fallback_users WHERE email=$1 AND ($2::uuid IS NULL OR id=$2::uuid)',[email,userId||null])
    await pool.end()
  }
}
run().then(report=>console.log(JSON.stringify({...report,syntheticFixtureRemoved:true}))).catch(error=>{console.error(error.message);process.exitCode=1})
