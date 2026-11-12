'use strict'
// Private fixture controller. Its namespace shares only the disposable QA database.
const fs=require('node:fs'),http=require('node:http'),https=require('node:https'),net=require('node:net'),crypto=require('node:crypto')
const {Pool}=require('pg'),bcrypt=require('bcryptjs'),WebSocket=require('ws')
const pool=new Pool({connectionString:process.env.DATABASE_URL})
const api='http://127.0.0.1:3001'
const users=[]
let initialized=false
const chatId='11600000-0000-4000-8000-000000000099'
async function request(path,actor,body,method='POST') {
  const response=await fetch(api+path,{method,headers:{'content-type':'application/json',...(actor?{Authorization:'Bearer '+actor.accessToken}:{})},...(body===undefined?{}:{body:JSON.stringify(body)})})
  const payload=await response.json()
  if(!response.ok)throw Object.assign(new Error(String(payload.error||'FIXTURE_HTTP_FAILURE')),{status:response.status})
  return payload
}
async function initialize() {
  const deadline=Date.now()+120000
  while(Date.now()<deadline){try{const r=await fetch(api+'/health');if(r.ok)break}catch{}await new Promise(r=>setTimeout(r,500))}
  const db=(await pool.query('SELECT current_database() AS name')).rows[0].name
  if(db!=='novvin_review116_stage')throw new Error('ISOLATED_DATABASE_REQUIRED')
  for(let i=1;i<=4;i++){
    const id='11600000-0000-4000-8000-'+String(i).padStart(12,'0')
    const email=`review116-${i}@example.invalid`,password=crypto.randomBytes(18).toString('base64url')
    const name=['Equipo de prueba','Compañía de prueba','Invitación de prueba','Prueba móvil'][i-1]
    const hash=await bcrypt.hash(password,10)
    await pool.query(`INSERT INTO fallback_users(id,email,display_name,password_hash,password_enabled,sync_source,sync_status)
      VALUES($1,$2,$3,$4,true,'local','local_only') ON CONFLICT (id) DO UPDATE SET password_hash=EXCLUDED.password_hash`,[id,email,name,hash])
    const session=await request('/v1/auth/login',null,{email,password})
    users.push({id,email,password,name,...session})
  }
  await pool.query("INSERT INTO fallback_friends(sender_id,receiver_id,status) VALUES($1,$2,'accepted') ON CONFLICT DO NOTHING",[users[0].id,users[1].id])
  await pool.query('INSERT INTO fallback_chats(id,participants,is_group) VALUES($1,$2,false) ON CONFLICT DO NOTHING',[chatId,[users[0].id,users[1].id]])
  await pool.query('DELETE FROM fallback_friends WHERE sender_id=$1 AND receiver_id=$2',[users[0].id,users[3].id])
  const web=await request('/v1/data/friends',users[0],{receiverId:users[2].id})
  const mobile=await request('/v1/data/friends',users[0],{receiverId:users[3].id})
  initialized={chatId,webRequestId:web.request.id,mobileRequestId:mobile.request.id}
  console.log(JSON.stringify({fixtureReady:true,actors:users.length,databaseIsolated:true}))
}
const started=initialize().catch(error=>{console.error(JSON.stringify({fixtureReady:false,error:error.message}));process.exitCode=1})
const control=http.createServer(async(req,res)=>{
  res.setHeader('content-type','application/json');res.setHeader('cache-control','no-store')
  try{
    await started
    if(!initialized){res.writeHead(503);res.end(JSON.stringify({error:'FIXTURE_UNAVAILABLE'}));return}
    if(req.url==='/ready'){res.end(JSON.stringify({ok:true,databaseIsolated:true}));return}
    if(req.url==='/bootstrap'){
      res.end(JSON.stringify({...initialized,users,certificate:fs.readFileSync('/qa-tls.crt','utf8')}));return
    }
    if(req.url==='/smoke'&&req.method==='POST'){
      const result={health:(await fetch(api+'/health')).ok,readiness:(await fetch(api+'/ready')).ok}
      const friends=await request('/v1/data/friends?tab=outgoing',users[0],undefined,'GET')
      result.cancellationCapability=friends.capabilities?.cancelOutgoingRequests===true
      const pending=await request('/v1/data/friends',users[0],{receiverId:users[3].id})
      await request('/v1/data/friends/'+pending.request.id+'/cancel',users[0],{})
      result.pendingCancellation=true
      const fresh=await request('/v1/data/friends',users[0],{receiverId:users[3].id})
      await request('/v1/data/friends/'+fresh.request.id,users[3],{status:'accepted'},'PATCH')
      try{await request('/v1/data/friends/'+fresh.request.id+'/cancel',users[0],{});result.acceptedProtected=false}catch(e){result.acceptedProtected=e.status===409}
      const accepted=await request('/v1/data/friends?tab=accepted',users[0],undefined,'GET')
      result.acceptedPreserved=accepted.items.some(r=>r.id===fresh.request.id&&r.status==='accepted')
      const ws=new WebSocket('ws://127.0.0.1:3002/realtime/',{headers:{Authorization:'Bearer '+users[1].accessToken}})
      const messageId=crypto.randomUUID()
      const received=new Promise((resolve,reject)=>{
        const timer=setTimeout(()=>reject(new Error('REALTIME_MESSAGE_TIMEOUT')),10000)
        ws.on('error',reject)
        ws.on('open',()=>ws.send(JSON.stringify({type:'subscribe',channel:'user:'+users[1].id})))
        ws.on('message',async raw=>{
          const frame=JSON.parse(raw.toString())
          if(frame.type==='subscribed'){
            result.websocketSubscription=true
            await request('/v1/data/messages/chats/'+chatId+'/messages',users[0],{id:messageId,content:'Mensaje sintético de verificación',contentType:'text'})
          }
          if(frame.type==='event'&&frame.payload?.event==='message'&&frame.payload.message?.id===messageId){clearTimeout(timer);resolve(true)}
        })
      })
      try{result.realtimeDelivery=await received}finally{ws.close()}
      result.passed=Object.values(result).every(value=>value===true)
      res.end(JSON.stringify(result));return
    }
    if(req.url?.startsWith('/rotate?')){
      const i=Number(new URL(req.url,'http://fixture').searchParams.get('actor'))
      if(!Number.isInteger(i)||i<0||i>=users.length)throw new Error('INVALID_ACTOR')
      const refreshed=await request('/v1/auth/refresh',null,{refreshToken:users[i].refreshToken})
      users[i]={...users[i],...refreshed};res.end(JSON.stringify({accessToken:users[i].accessToken}));return
    }
    res.writeHead(404);res.end(JSON.stringify({error:'NOT_FOUND'}))
  }catch(error){res.writeHead(error.status||500);res.end(JSON.stringify({error:error.message}))}
})
control.listen(3009,'0.0.0.0')
const proxy=https.createServer({key:fs.readFileSync('/qa-tls.key'),cert:fs.readFileSync('/qa-tls.crt')},(req,res)=>{
  const upstream=http.request({hostname:'127.0.0.1',port:3001,path:req.url,method:req.method,headers:req.headers},r=>{res.writeHead(r.statusCode,r.headers);r.pipe(res)})
  upstream.on('error',()=>{res.writeHead(502);res.end()});req.pipe(upstream)
})
proxy.on('upgrade',(req,socket,head)=>{
  const upstream=net.connect(3002,'127.0.0.1',()=>{
    upstream.write(req.method+' '+req.url+' HTTP/1.1\r\n'+req.rawHeaders.reduce((s,v,i)=>s+v+(i%2?'\r\n':': '),'')+'\r\n')
    if(head.length)upstream.write(head)
    socket.pipe(upstream);upstream.pipe(socket)
  })
  upstream.on('error',()=>socket.destroy());socket.on('error',()=>upstream.destroy())
})
proxy.listen(3011,'0.0.0.0')
