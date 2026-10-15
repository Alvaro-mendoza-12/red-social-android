'use strict';
const test=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),os=require('node:os'),path=require('node:path'),crypto=require('node:crypto');
const storageRoot=fs.mkdtempSync(path.join(os.tmpdir(),'novvin-clean-delivery-'));
process.env.STORAGE_ROOT=storageRoot;process.env.STORAGE_SCAN_ENFORCE='false';process.env.STORAGE_READ_ENFORCE='false';
const express=require('express');
const {registerStorageRoutes}=require('../src/storage-routes');
const {revisionFilePath}=require('../src/storage-scan-coordinator');
test('clean immutable revisions deliver original, HEAD and Range only after admission',async()=>{
 const bytes=Buffer.from('isolated immutable clean object fixture');
 const revision=crypto.randomUUID(),file=revisionFilePath(storageRoot,revision);
 fs.mkdirSync(path.dirname(file),{recursive:true});fs.writeFileSync(file,bytes);
 const row={bucket_id:'chat-media',name:'owner/clean.txt',owner_id:'owner',content_type:'text/plain',size_bytes:bytes.length,local_path:file,
  object_revision:revision,scan_state:'clean',scan_sha256:crypto.createHash('sha256').update(bytes).digest('hex'),scan_bytes:bytes.length,
  scan_engine:'synthetic-controller-fixture',scan_signature_version:'1',scan_at:new Date().toISOString(),scan_policy_sha256:'a'.repeat(64)};
 const pool={query:async()=>({rows:[row]})},app=express();
 registerStorageRoutes(app,{pool,authRequired:(_req,_res,next)=>next(),storageScanCoordinator:{enforced:true},
  storageObjectReadGuard:async req=>({allowed:req.headers.authorization==='Bearer fixture-owner',private:true})});
 app.use((error,_req,res,_next)=>res.status(error.status||500).json({error:error.message}));
 const server=await new Promise(resolve=>{const s=app.listen(0,'127.0.0.1',()=>resolve(s));});
 try{
  const base=`http://127.0.0.1:${server.address().port}`,url=base+'/storage/v1/object/public/chat-media/owner/clean.txt';
  const authorized={authorization:'Bearer fixture-owner'};
  const response=await fetch(url,{headers:authorized});assert.equal(response.status,200);assert.deepEqual(Buffer.from(await response.arrayBuffer()),bytes);assert.equal(response.headers.get('cache-control'),'private, no-store');
  const head=await fetch(url,{method:'HEAD',headers:authorized});assert.equal(head.status,200);assert.equal((await head.arrayBuffer()).byteLength,0);
  const range=await fetch(url,{headers:{...authorized,range:'bytes=0-3'}});assert.equal(range.status,206);assert.deepEqual(Buffer.from(await range.arrayBuffer()),bytes.subarray(0,4));
  for(const headers of [{},{range:'bytes=0-3'},{'if-none-match':response.headers.get('etag')}]){const denied=await fetch(url,{headers});assert.equal(denied.status,404);assert.notEqual(denied.status,206);assert.notEqual(denied.status,304);}
  row.scan_state='rejected';const rejected=await fetch(url,{headers:authorized});assert.equal(rejected.status,404);row.scan_state='clean';
  const direct=await fetch(base+'/.objects/'+revision.slice(0,2)+'/'+revision+'.blob',{headers:authorized});assert.equal(direct.status,404);
  row.object_revision=null;row.local_path=null;row.name='owner/.hidden.txt';row.scan_state='legacy_unverified';
  const legacy=path.join(storageRoot,'chat-media','owner','.hidden.txt');fs.mkdirSync(path.dirname(legacy),{recursive:true});fs.writeFileSync(legacy,bytes);
  const uncheckedApp=express();registerStorageRoutes(uncheckedApp,{pool,authRequired:(_req,_res,next)=>next(),storageScanCoordinator:{enforced:false}});
  uncheckedApp.use((error,_req,res,_next)=>res.status(error.status||500).end());
  const unchecked=await new Promise(resolve=>{const s=uncheckedApp.listen(0,'127.0.0.1',()=>resolve(s));});
  try{const hidden=await fetch(`http://127.0.0.1:${unchecked.address().port}/storage/v1/object/public/chat-media/owner/.hidden.txt`);assert.equal(hidden.status,404);}finally{await new Promise(resolve=>unchecked.close(resolve));}
 }finally{
  await new Promise(resolve=>server.close(resolve));
  const resolved=path.resolve(storageRoot);assert.equal(path.dirname(resolved),path.resolve(os.tmpdir()));assert.ok(path.basename(resolved).startsWith('novvin-clean-delivery-'));
  fs.rmSync(resolved,{recursive:true,force:true});
 }
});
