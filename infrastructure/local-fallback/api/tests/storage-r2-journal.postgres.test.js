'use strict'
const {test}=require('node:test')
const assert=require('node:assert/strict')
const fs=require('node:fs/promises')
const os=require('node:os')
const path=require('node:path')
const crypto=require('node:crypto')
const {Client}=require('pg')
const {SCHEMA,batch}=require('../src/storage-r2-journal')
const {revisionFilePath}=require('../src/storage-scan-coordinator')

test('durable PostgreSQL journal: replay, replacement, deletion, retry, target isolation, budget and multi-worker lock',
  {skip:!process.env.R2_TEST_DATABASE_URL},async t=>{
    const url=new URL(process.env.R2_TEST_DATABASE_URL)
    if(!['127.0.0.1','localhost'].includes(url.hostname)||url.pathname!=='/novvin_r2_test')throw Error('Requires isolated local-forwarded novvin_r2_test database')
    const db=new Client({connectionString:url.href}),other=new Client({connectionString:url.href})
    await db.connect();await other.connect();t.after(()=>db.end());t.after(()=>other.end())
    const root=await fs.mkdtemp(path.join(os.tmpdir(),'r2-pg-'));t.after(()=>fs.rm(root,{recursive:true,force:true}))
    await db.query(`CREATE TABLE fallback_storage_objects(id uuid PRIMARY KEY,bucket_id text,name text,object_revision uuid,
      scan_state text,scan_sha256 text,scan_bytes bigint,size_bytes bigint,content_type text,scan_engine text,
      scan_signature_version text,scan_at timestamptz,scan_policy_sha256 text,local_path text,created_at timestamptz DEFAULT now())`)
    await db.query(SCHEMA);await db.query(SCHEMA)
    const bytes=Buffer.from('isolated test'),id=crypto.randomUUID(),revision=crypto.randomUUID()
    const file=revisionFilePath(root,revision);await fs.mkdir(path.dirname(file),{recursive:true});await fs.writeFile(file,bytes)
    const source={id,bucket_id:'chat-media',name:'owner/private.jpg',object_revision:revision,scan_state:'clean',
      scan_sha256:crypto.createHash('sha256').update(bytes).digest('hex'),scan_bytes:bytes.length,size_bytes:bytes.length,
      content_type:'image/jpeg',scan_engine:'test',scan_signature_version:'test',scan_at:new Date(),scan_policy_sha256:'a'.repeat(64),local_path:file}
    async function insert(row){const keys=Object.keys(row);await db.query(`INSERT INTO fallback_storage_objects(${keys.join(',')}) VALUES(${keys.map((_,i)=>'$'+(i+1)).join(',')})`,Object.values(row))}
    await insert(source)
    const config={root,maxBytes:1000,batchSize:8,endpoint:'https://fixture.r2.cloudflarestorage.com',bucket:'fixture'}
    let puts=0,deletes=0,offline=false
    const remote={putVerified:async()=>{puts++;if(offline)throw Error('offline')},remove:async()=>{deletes++}}
    assert.equal((await batch(db,config,remote)).ready,1);assert.equal(puts,1)
    assert.equal((await batch(db,config,remote)).ready,0);assert.equal(puts,1)
    await db.query('UPDATE fallback_storage_objects SET content_type=$2 WHERE id=$1',[id,'image/png'])
    assert.equal((await batch(db,config,remote)).ready,1);assert.equal(puts,2);assert.equal(deletes,1)
    await db.query('DELETE FROM fallback_storage_objects WHERE id=$1',[id])
    assert.equal((await batch(db,config,remote)).deleted,1);assert.equal(deletes,2);assert.deepEqual(await fs.readFile(file),bytes)
    await insert(source);offline=true
    assert.equal((await batch(db,config,remote)).retry,1)
    const retried=(await db.query("SELECT attempts,last_error,retry_at>now() AS delayed FROM fallback_storage_r2_mirrors WHERE state='pending'")).rows[0]
    assert.equal(retried.attempts,1);assert.equal(retried.delayed,true);assert.equal(retried.last_error,'R2_REMOTE_OR_DATABASE_FAILURE')
    const before=puts;assert.equal((await batch(db,config,remote)).ready,0);assert.equal(puts,before)
    offline=false;await db.query("UPDATE fallback_storage_r2_mirrors SET retry_at=now() WHERE state='pending'")
    assert.equal((await batch(db,config,remote)).ready,1)
    assert.equal((await batch(db,{...config,bucket:'second-target'},remote)).ready,1)
    assert.equal((await db.query("SELECT count(*)::int AS n FROM fallback_storage_r2_mirrors WHERE state='ready'")).rows[0].n,2)
    assert.equal((await batch(db,{...config,bucket:'small-budget',maxBytes:1},remote)).budgetPaused,1)
    await other.query("SELECT pg_advisory_lock(hashtextextended('novvin-r2-mirror-v1',0))")
    try{assert.deepEqual(await batch(db,config,remote),{busy:true})}
    finally{await other.query("SELECT pg_advisory_unlock(hashtextextended('novvin-r2-mirror-v1',0))")}
    assert.deepEqual(await fs.readFile(file),bytes)
  })
