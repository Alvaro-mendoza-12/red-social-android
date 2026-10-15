'use strict'
const test=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs'),os=require('node:os'),path=require('node:path'),crypto=require('node:crypto')
const {Pool}=require('pg')
const {createStorageUploadAdmission,ensureStorageUploadAdmissionSchema}=require('../src/storage-upload-admission')
const url=process.env.STORAGE_ADMISSION_TEST_DATABASE_URL
test('persistent upload admission across API replicas on PostgreSQL', {skip:!url}, async t=>{
  const root=fs.mkdtempSync(path.join(os.tmpdir(),'novvin-admission-owned-')),apiA=new Pool({connectionString:url,max:4}),apiB=new Pool({connectionString:url,max:4})
  const a=crypto.randomUUID(),b=crypto.randomUUID(),c=crypto.randomUUID(),held=[]
  fs.mkdirSync(path.join(root,'.tmp','resumable'),{recursive:true})
  const options={root,globalBytes:32*1024**2,userBytes:16*1024**2,globalActiveBytes:16*1024**2,userActiveBytes:8*1024**2,globalSlots:4,userSlots:2,minFreeBytes:0}
  const instanceA=createStorageUploadAdmission({...options,pool:apiA}),instanceB=createStorageUploadAdmission({...options,pool:apiB})
  const reserve=async(instance,ownerId,bytes=1024)=>{const lease=await instance.reserve({ownerId,bytes,kind:'raw'});held.push(lease);return lease}
  async function clean(){for(const l of held.splice(0))await l.finish();await apiA.query('DELETE FROM fallback_storage_upload_reservations');await apiA.query('DELETE FROM fallback_resumable_uploads')}
  try{
    await apiA.query(`CREATE TABLE IF NOT EXISTS fallback_resumable_uploads(id uuid PRIMARY KEY,owner_id uuid NOT NULL,
      state text NOT NULL,upload_length bigint NOT NULL,expires_at timestamptz NOT NULL)`)
    await t.test('two schema initializations serialize and are idempotent',async()=>{await Promise.all([ensureStorageUploadAdmissionSchema(apiA),ensureStorageUploadAdmissionSchema(apiB)]);await ensureStorageUploadAdmissionSchema(apiA)})
    await t.test('global and per-user active slots are shared by two replicas',async()=>{
      await Promise.all([reserve(instanceA,a),reserve(instanceB,a),reserve(instanceA,b),reserve(instanceB,b)])
      await assert.rejects(()=>reserve(instanceA,a),e=>e.status===429&&e.code==='STORAGE_UPLOAD_USER_CAPACITY')
      await assert.rejects(()=>reserve(instanceB,c),e=>e.status===503&&e.code==='STORAGE_UPLOAD_GLOBAL_CAPACITY'&&e.retryAfterSeconds===2)
      assert.equal((await apiB.query('SELECT count(*)::int AS n FROM fallback_storage_upload_reservations WHERE active')).rows[0].n,4)
      await clean()
    })
    await t.test('four leases do not consume API pool of four connections',async()=>{
      await Promise.all([reserve(instanceA,a),reserve(instanceA,a),reserve(instanceA,b),reserve(instanceA,b)])
      const started=Date.now();assert.equal((await apiA.query('SELECT 42 AS value')).rows[0].value,42);assert.ok(Date.now()-started<1000)
      await clean()
    })
    await t.test('large active bytes reject before creating a temporary file',async()=>{
      await reserve(instanceA,a,2*1024**2);await reserve(instanceB,b,2*1024**2)
      await assert.rejects(()=>reserve(instanceB,c,2*1024**2),e=>e.status===503)
      assert.deepEqual(fs.readdirSync(path.join(root,'.tmp')).filter(n=>n!=='resumable'),[])
      await clean()
    })
    await t.test('disk free space reserve fails closed',async()=>{
      const full=createStorageUploadAdmission({...options,pool:apiB,statfs:async()=>({bavail:0n,bsize:4096n})})
      await assert.rejects(()=>reserve(full,c),e=>e.status===503&&e.code==='STORAGE_UPLOAD_DISK_CAPACITY')
      assert.equal((await apiA.query('SELECT count(*)::int AS n FROM fallback_storage_upload_reservations')).rows[0].n,0)
    })
    await t.test('finally removes only its own raw temporary prefix',async()=>{
      const l=await reserve(instanceA,a),own=path.join(root,'.tmp','admitted-'+l.id+'-'+crypto.randomUUID()),other=path.join(root,'.tmp','unrelated-retained')
      fs.writeFileSync(own,'inert owned bytes');fs.writeFileSync(other,'other');await l.finish();held.splice(held.indexOf(l),1)
      assert.equal(fs.existsSync(own),false);assert.equal(fs.readFileSync(other,'utf8'),'other');fs.unlinkSync(other)
    })
    await t.test('idle TUS retain bytes and resumability but free active slots',async()=>{
      for(let i=0;i<6;i++){
        const ownerId=i%2?a:b,id=crypto.randomUUID(),expiry=new Date(Date.now()+86400000),lease=await instanceA.reserve({ownerId,bytes:1024,kind:'tus',id,expiresAt:expiry})
        await apiA.query('INSERT INTO fallback_resumable_uploads VALUES($1,$2,$3,$4,$5)',[id,ownerId,'uploading',1024,expiry]);fs.writeFileSync(path.join(root,'.tmp','resumable',id+'.part'),'inert partial')
        await lease.finish({keep:true})
      }
      const stats=(await apiA.query('SELECT count(*)::int AS n,count(*) FILTER(WHERE active)::int AS active,sum(reserved_bytes)::int AS bytes FROM fallback_storage_upload_reservations')).rows[0]
      assert.deepEqual(stats,{n:6,active:0,bytes:24576});const l=await reserve(instanceB,c);await l.finish();held.splice(held.indexOf(l),1)
      const row=(await apiA.query('SELECT id,owner_id FROM fallback_resumable_uploads LIMIT 1')).rows[0],claim=await instanceB.claimTus(row.id,row.owner_id)
      await assert.rejects(()=>instanceA.claimTus(row.id,c),e=>e.status===404)
      assert.equal(fs.existsSync(path.join(root,'.tmp','resumable',row.id+'.part')),true);await claim.finish({keep:true})
      for(const r of (await apiA.query('SELECT id FROM fallback_resumable_uploads')).rows)fs.unlinkSync(path.join(root,'.tmp','resumable',r.id+'.part'))
      await clean()
    })
    await t.test('expiration never removes a currently held raw lease',async()=>{
      const lease=await reserve(instanceA,a),file=path.join(root,'.tmp','admitted-'+lease.id+'-'+crypto.randomUUID());fs.writeFileSync(file,'active inert bytes')
      await apiA.query("UPDATE fallback_storage_upload_reservations SET expires_at=now()-interval '1 second' WHERE id=$1",[lease.id])
      const result=await instanceB.reconcileExpired();assert.equal(result.activeSkipped,1);assert.equal(result.released,0);assert.equal(fs.existsSync(file),true);await clean()
    })
    await t.test('expired unleased TUS removes only owned partial and releases quota',async()=>{
      const id=crypto.randomUUID(),expiry=new Date(Date.now()+10000),lease=await instanceA.reserve({ownerId:a,bytes:1024,kind:'tus',id,expiresAt:expiry}),file=path.join(root,'.tmp','resumable',id+'.part')
      await apiA.query('INSERT INTO fallback_resumable_uploads VALUES($1,$2,$3,$4,$5)',[id,a,'uploading',1024,expiry]);fs.writeFileSync(file,'partial');await lease.finish({keep:true})
      await apiA.query("UPDATE fallback_resumable_uploads SET expires_at=now()-interval '1 second' WHERE id=$1",[id]);await apiA.query("UPDATE fallback_storage_upload_reservations SET expires_at=now()-interval '1 second' WHERE id=$1",[id])
      assert.equal((await instanceB.reconcileExpired()).released,1);assert.equal(fs.existsSync(file),false);assert.equal((await apiA.query('SELECT count(*)::int AS n FROM fallback_resumable_uploads')).rows[0].n,0)
    })
    await t.test('an unexpired renewed TUS is not deleted by stale reservation expiry',async()=>{
      const id=crypto.randomUUID(),expiry=new Date(Date.now()+86400000),lease=await instanceA.reserve({ownerId:a,bytes:1024,kind:'tus',id,expiresAt:expiry}),file=path.join(root,'.tmp','resumable',id+'.part')
      await apiA.query('INSERT INTO fallback_resumable_uploads VALUES($1,$2,$3,$4,$5)',[id,a,'uploading',1024,expiry]);fs.writeFileSync(file,'partial');await lease.finish({keep:true});await apiA.query("UPDATE fallback_storage_upload_reservations SET expires_at=now()-interval '1 second' WHERE id=$1",[id])
      assert.equal((await instanceB.reconcileExpired()).released,0);assert.equal(fs.existsSync(file),true);fs.unlinkSync(file);await clean()
    })
    await t.test('lost dedicated lease connection prevents publication work',async()=>{
      const ownPool=new Pool({connectionString:url,max:2,application_name:'novvin-owned-admission-lost'}),lost=createStorageUploadAdmission({...options,pool:apiB,leasePool:ownPool}),lease=await reserve(lost,a)
      await apiA.query("SELECT pg_terminate_backend(pid) FROM pg_stat_activity WHERE application_name='novvin-owned-admission-lost' AND pid<>pg_backend_pid()")
      await new Promise(r=>setTimeout(r,100));assert.throws(()=>lease.assertAlive(),e=>e.code==='STORAGE_UPLOAD_RESERVATION_LOST')
      await lease.finish().catch(()=>{});held.splice(held.indexOf(lease),1);await ownPool.end();await clean()
    })
    await t.test('database connection outage is controlled503 with Retry-After',async()=>{
      const unavailable=createStorageUploadAdmission({...options,pool:apiA,leasePool:{connect:async()=>{throw Error('synthetic unavailable database')}}})
      await assert.rejects(()=>reserve(unavailable,c),e=>e.status===503&&e.expose===true&&e.retryAfterSeconds===2)
    })
  }finally{
    await clean().catch(()=>{});await apiA.end();await apiB.end()
    const target=path.resolve(root);assert.equal(path.dirname(target),path.resolve(os.tmpdir()));assert.ok(path.basename(target).startsWith('novvin-admission-owned-'));fs.rmSync(target,{recursive:true,force:true})
  }
})
