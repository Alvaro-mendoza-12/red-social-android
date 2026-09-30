const test = require('node:test')
const assert = require('node:assert/strict')
const os = require('node:os')
const fs = require('node:fs')
const path = require('node:path')
process.env.STORAGE_ROOT = fs.mkdtempSync(path.join(os.tmpdir(), 'novvin-recovery-test-'))
const express = require('express')
const { evidenceType, safeName, receiveRecoveryEvidence, registerRecoveryEvidenceDownload, MAX_BYTES } = require('../src/recovery-evidence')
test.after(() => fs.rmSync(process.env.STORAGE_ROOT, { recursive:true, force:true }))

test('detect actual file content instead of trusting the declared MIME type', () => {
  assert.equal(evidenceType(Buffer.from([137,80,78,71,13,10,26,10]), 'image.png'), 'image/png')
  assert.equal(evidenceType(Buffer.from('%PDF-1.7\n'), 'proof.pdf'), 'application/pdf')
  assert.equal(evidenceType(Buffer.from('Lo que recuerdo de mi cuenta'), 'prueba.txt'), 'text/plain')
  assert.equal(evidenceType(Buffer.from('<html><script>steal()</script></html>'), 'prueba.txt'), null)
  assert.equal(evidenceType(Buffer.from('MZexecutable'), 'image.png'), null)
  assert.equal(evidenceType(Buffer.from('PKarchive'), 'proof.zip'), null)
  assert(!/[\\/\r\n"]/.test(safeName('../../folder/proof\n".pdf')))
})
async function fixture() {
  const app=express()
  app.post('/upload', receiveRecoveryEvidence, (req,res) => res.json({ count:req.files?.length || 0 }))
  const server=app.listen(0,'127.0.0.1')
  await new Promise(resolve=>server.once('listening',resolve))
  return { url:`http://127.0.0.1:${server.address().port}/upload`, close:()=>new Promise(resolve=>server.close(resolve)) }
}
test('valid evidence uploads and temporary files are removed when not committed', async () => {
  const f=await fixture()
  try {
    const data=new FormData(); data.append('identifier','synthetic'); data.append('evidence',new Blob(['texto de evidencia'],{type:'text/plain'}),'proof.txt')
    const r=await fetch(f.url,{method:'POST',body:data}); assert.equal(r.status,200); assert.equal((await r.json()).count,1)
    await new Promise(resolve=>setTimeout(resolve,40))
    assert.equal(fs.readdirSync(path.join(process.env.STORAGE_ROOT,'.recovery-evidence')).length,0)
  } finally { await f.close() }
})
test('spoofed content, oversized files and more than five files fail without leaving files', async () => {
  const f=await fixture()
  try {
    for (const type of ['spoof','size','count']) {
      const data=new FormData()
      if(type==='spoof') data.append('evidence',new Blob(['MZnot-an-image'],{type:'image/png'}),'image.png')
      if(type==='size') data.append('evidence',new Blob([Buffer.alloc(MAX_BYTES+1)]),'huge.pdf')
      if(type==='count') for(let n=0;n<6;n++) data.append('evidence',new Blob(['valid'],{type:'text/plain'}),`proof${n}.txt`)
      const r=await fetch(f.url,{method:'POST',body:data}); assert.equal(r.status,type==='size'?413:400)
      await r.json()
    }
    assert.equal(fs.readdirSync(path.join(process.env.STORAGE_ROOT,'.recovery-evidence')).length,0)
  } finally { await f.close() }
})

test('authorized administrators can download private dot-directory files; anonymous and wrong-case requests cannot', async () => {
  const app=express()
  const caseId='11111111-1111-4111-8111-111111111111', fileId='22222222-2222-4222-8222-222222222222'
  const proof=Buffer.from('Synthetic private evidence')
  fs.writeFileSync(path.join(process.env.STORAGE_ROOT,'.recovery-evidence',fileId),proof)
  const pool={query:async(sql,values)=>({rows:values?.[0]===fileId && values?.[1]===caseId ? [{id:fileId,name:'proof.txt',mime:'text/plain'}]:[]})}
  registerRecoveryEvidenceDownload(app,{pool,recordAudit:async()=>{},recoveryAdminRequired:(req,res,next)=>req.headers['x-test-admin']==='yes'?next():res.sendStatus(401)})
  const server=app.listen(0,'127.0.0.1'); await new Promise(resolve=>server.once('listening',resolve))
  const url=`http://127.0.0.1:${server.address().port}/v1/data/admin/account-recovery/cases/${caseId}/evidence/${fileId}`
  try {
    assert.equal((await fetch(url)).status,401)
    const headers={'x-test-admin':'yes'}
    const r=await fetch(url,{headers}); assert.equal(r.status,200); assert.deepEqual(Buffer.from(await r.arrayBuffer()),proof)
    assert.match(r.headers.get('cache-control'),/no-store/); assert.equal(r.headers.get('x-content-type-options'),'nosniff')
    assert.equal((await fetch(url.replace(caseId,'33333333-3333-4333-8333-333333333333'),{headers})).status,404)
    assert.equal((await fetch(url.replace(fileId,'------------------------------------'),{headers})).status,400)
  } finally { await new Promise(resolve=>server.close(resolve)) }
})
