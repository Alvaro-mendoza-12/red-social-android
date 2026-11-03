'use strict'
const {test}=require('node:test')
const assert=require('node:assert/strict')
const http=require('node:http')
const fs=require('node:fs/promises')
const os=require('node:os')
const path=require('node:path')
const crypto=require('node:crypto')
const {createRemote}=require('../src/storage-r2-remote')

test('S3 adapter uploads bytes, verifies a download, adopts an interrupted write and removes only its key',async t=>{
  const bytes=Buffer.from('R2 transport fixture'),sha256=crypto.createHash('sha256').update(bytes).digest('hex')
  let stored,puts=0,gets=0,removed=false,corrupt=false,metadata=sha256
  const server=http.createServer(async(req,res)=>{
    assert.match(req.headers.authorization,/AWS4-HMAC-SHA256/)
    assert.equal(req.url.split('?')[0],'/fixture/novvin/v1/test')
    if(req.method==='HEAD') {
      if(!stored){res.writeHead(404);return res.end()}
      res.writeHead(200,{'content-length':stored.length,'x-amz-meta-sha256':metadata,'content-type':'image/jpeg'});return res.end()
    }
    if(req.method==='PUT') {
      const chunks=[];for await(const chunk of req)chunks.push(chunk);stored=Buffer.concat(chunks);puts++
      assert.equal(req.headers['cache-control'],'private, no-store')
      assert.equal(req.headers['x-amz-meta-sha256'],sha256)
      res.writeHead(200,{etag:'"test"'});return res.end()
    }
    if(req.method==='GET') {gets++;res.writeHead(200);return res.end(corrupt?Buffer.from('corrupted'):stored)}
    if(req.method==='DELETE'){removed=true;stored=null;res.writeHead(204);return res.end()}
    res.writeHead(405);res.end()
  })
  await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve))
  t.after(()=>new Promise(resolve=>server.close(resolve)))
  const remote=createRemote({endpoint:`http://127.0.0.1:${server.address().port}`,bucket:'fixture',accessKeyId:'test',secretAccessKey:'test'})
  t.after(()=>remote.close())
  const root=await fs.mkdtemp(path.join(os.tmpdir(),'r2-transport-'));t.after(()=>fs.rm(root,{recursive:true,force:true}))
  const file=path.join(root,'input');await fs.writeFile(file,bytes);const source=await fs.open(file,'r');t.after(()=>source.close())
  const input={key:'novvin/v1/test',source,size:bytes.length,sha256,contentType:'image/jpeg'}
  await remote.putVerified(input);assert.deepEqual(stored,bytes);assert.equal(puts,1);assert.equal(gets,1)
  await remote.putVerified(input);assert.equal(puts,1);assert.equal(gets,2)
  corrupt=true;await assert.rejects(remote.putVerified(input),/REMOTE_HASH_MISMATCH/)
  corrupt=false;metadata='b'.repeat(64);await assert.rejects(remote.putVerified(input),/REMOTE_IDENTITY_MISMATCH/)
  await remote.remove(input.key);assert.equal(removed,true);assert.deepEqual(await fs.readFile(file),bytes)
})

for (const rejectSecondPart of [false,true]) test(`S3 multipart ${rejectSecondPart?'aborts incomplete upload after failure':'verifies a complete large object'}`,async t=>{
  const bytes=Buffer.alloc(9*1024*1024,0x5a),sha256=crypto.createHash('sha256').update(bytes).digest('hex')
  const parts=new Map();let stored,aborted=false,completed=false
  const server=http.createServer(async(req,res)=>{
    const url=new URL(req.url,'http://localhost')
    if(req.method==='HEAD'){res.writeHead(404);return res.end()}
    if(req.method==='POST'&&url.searchParams.has('uploads')){
      res.setHeader('content-type','application/xml');return res.end('<InitiateMultipartUploadResult><Bucket>fixture</Bucket><Key>large</Key><UploadId>test-upload</UploadId></InitiateMultipartUploadResult>')
    }
    if(req.method==='PUT'&&url.searchParams.has('partNumber')){
      const number=Number(url.searchParams.get('partNumber'))
      if(rejectSecondPart&&number===2){req.resume();res.writeHead(403);return res.end('<Error><Code>AccessDenied</Code></Error>')}
      const chunks=[];for await(const chunk of req)chunks.push(chunk);parts.set(number,Buffer.concat(chunks))
      res.setHeader('etag',`"part-${number}"`);return res.end()
    }
    if(req.method==='POST'&&url.searchParams.has('uploadId')){
      req.resume();stored=Buffer.concat([...parts.entries()].sort((a,b)=>a[0]-b[0]).map(entry=>entry[1]));completed=true
      res.setHeader('content-type','application/xml');return res.end('<CompleteMultipartUploadResult><Location>https://fixture.invalid/large</Location><Bucket>fixture</Bucket><Key>large</Key><ETag>"complete"</ETag></CompleteMultipartUploadResult>')
    }
    if(req.method==='DELETE'&&url.searchParams.has('uploadId')){aborted=true;res.writeHead(204);return res.end()}
    if(req.method==='GET'){res.writeHead(200);return res.end(stored)}
    res.writeHead(405);res.end()
  })
  await new Promise(resolve=>server.listen(0,'127.0.0.1',resolve));t.after(()=>new Promise(resolve=>server.close(resolve)))
  const remote=createRemote({endpoint:`http://127.0.0.1:${server.address().port}`,bucket:'fixture',accessKeyId:'test',secretAccessKey:'test'});t.after(()=>remote.close())
  const root=await fs.mkdtemp(path.join(os.tmpdir(),'r2-parts-'));t.after(()=>fs.rm(root,{recursive:true,force:true}))
  const file=path.join(root,'large');await fs.writeFile(file,bytes);const source=await fs.open(file,'r');t.after(()=>source.close())
  const operation=remote.putVerified({key:'large',source,size:bytes.length,sha256,contentType:'video/mp4'})
  if(rejectSecondPart){await assert.rejects(operation);assert.equal(aborted,true);assert.equal(completed,false)}
  else {await operation;assert.equal(parts.size,2);assert.deepEqual(stored,bytes);assert.equal(completed,true)}
  assert.equal((await fs.stat(file)).size,bytes.length)
})
