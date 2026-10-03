'use strict'
// Disposable loopback HTTPS API for Android instrumentation. Never runs on a production database.
const fs=require('node:fs'),path=require('node:path'),https=require('node:https')
const {randomUUID,randomBytes}=require('node:crypto'),express=require('express'),jwt=require('jsonwebtoken'),{Pool}=require('pg')
const {authenticateClientSession,isPublicAuthError}=require('../src/client-auth-session')
const {ensureSurveyTables,registerSurveyRoutes}=require('../src/surveys/routes')
const root=path.resolve(__dirname,'../../../..'),uri=new URL(process.env.SURVEY_TEST_DATABASE_URL || 'http://invalid')
if(!['127.0.0.1','localhost'].includes(uri.hostname) || uri.pathname!=='/novvin_surveys_test') throw new Error('Set SURVEY_TEST_DATABASE_URL to the disposable local database')
const tls={key:fs.readFileSync(path.join(root,'output/survey-validation-tls-key.pem')),cert:fs.readFileSync(path.join(root,'app/src/androidTest/assets/survey-validation-cert.pem'))}
const schema=`survey_native_${randomUUID().replaceAll('-','')}`,admin=new Pool({connectionString:uri.toString()}),pool=new Pool({connectionString:uri.toString(),options:`-c search_path=${schema}`})
let server,closing=false
async function cleanup(){
 if(closing)return;closing=true
 if(server)await new Promise(resolve=>server.close(resolve))
 await pool.end();await admin.query(`DROP SCHEMA IF EXISTS "${schema}" CASCADE`);await admin.end()
}
async function main(){
 await admin.query(`CREATE SCHEMA "${schema}"`)
 await pool.query(`CREATE TABLE fallback_users(id uuid PRIMARY KEY,supabase_user_id uuid,display_name text,avatar_url text,email text);
 CREATE TABLE fallback_profile_moderation(user_id text PRIMARY KEY,is_admin boolean,admin_access_level text,admin_is_owner boolean);
 CREATE TABLE fallback_auth_sessions(id uuid PRIMARY KEY,user_id uuid REFERENCES fallback_users(id),revoked_at timestamptz,expires_at timestamptz,last_used_at timestamptz);
 CREATE TABLE fallback_posts(id uuid PRIMARY KEY DEFAULT gen_random_uuid(),user_id text,user_name text,user_profile_image text,content text,media_type text,visibility text,is_sensitive boolean,created_at timestamptz DEFAULT now(),updated_at timestamptz DEFAULT now());
 CREATE TABLE fallback_user_blocks(blocker_id text,blocked_id text);
 CREATE TABLE fallback_follows(id uuid DEFAULT gen_random_uuid(),follower_id text,following_id text,created_at timestamptz DEFAULT now());
 CREATE TABLE fallback_rate_limit_windows(scope text,key_hash text,window_started_at timestamptz,request_count bigint,expires_at timestamptz,PRIMARY KEY(scope,key_hash));`)
 await ensureSurveyTables(pool)
 const key=randomBytes(32).toString('hex'),issuer='conectate-local-fallback',accounts={}
 for(const role of ['owner','respondent']){
  const id=randomUUID(),sid=randomUUID();await pool.query('INSERT INTO fallback_users(id,display_name) VALUES($1,$2)',[id,`QA ${role}`])
  await pool.query("INSERT INTO fallback_auth_sessions(id,user_id,expires_at) VALUES($1,$2,now()+interval '1 hour')",[sid,id])
  accounts[role]={id,token:jwt.sign({sub:id,sid,client:'android'},key,{issuer,audience:'conectate-clients',expiresIn:'1h'})}
 }
 const app=express();app.use(express.json({limit:'6mb'}))
 app.get('/qa/accounts',(_req,res)=>res.json(accounts))
 registerSurveyRoutes(app,{pool,authRequired:async(req,res,next)=>{
  try{
   req.auth=await authenticateClientSession({token:req.get('Authorization')?.replace(/^Bearer\s+/i,''),
   verifyToken:token=>jwt.verify(token,key,{issuer,audience:'conectate-clients'}),
   userExists:async id=>(await pool.query('SELECT 1 FROM fallback_users WHERE id=$1',[id])).rows.length>0,
   touchSession:async auth=>(await pool.query('UPDATE fallback_auth_sessions SET last_used_at=now() WHERE id=$1 AND user_id=$2 AND revoked_at IS NULL AND expires_at>now() RETURNING id',[auth.sid,auth.sub])).rows.length>0})
   next()
  }catch(error){if(isPublicAuthError(error))res.status(error.status).json({error:error.code});else next(error)}
 }})
 app.use((error,_req,res,_next)=>res.status(500).json({error:'VALIDATION_FAILURE',message:error.message}))
 server=https.createServer(tls,app)
 await new Promise(resolve=>server.listen(55440,'127.0.0.1',resolve))
 console.log(JSON.stringify({ready:true,url:'https://127.0.0.1:55440',schema}))
}
process.on('SIGINT',()=>cleanup().then(()=>process.exit(0)))
process.on('SIGTERM',()=>cleanup().then(()=>process.exit(0)))
process.stdin.setEncoding('utf8')
process.stdin.on('data',data=>{if(String(data).trim()==='stop')cleanup().then(()=>process.exit(0))})
main().catch(async error=>{console.error(error.message);await cleanup();process.exitCode=1})
