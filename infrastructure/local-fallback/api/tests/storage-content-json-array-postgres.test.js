'use strict';
const test=require('node:test'),assert=require('node:assert/strict'),fs=require('node:fs');
const {Pool}=require('pg');
const {CONTENT_FUNCTION_STATEMENTS}=require('../src/storage-content-schema');
const {sourceUrls}=require('../src/storage-content-read');
const url=process.env.STORAGE_CONTENT_ARRAY_TEST_DATABASE_URL;
if(url){const parsed=new URL(url);assert.equal(parsed.pathname,'/novvin_content_array_audit','Use only the dedicated disposable content-array test database');}
test('PostgreSQL content URL arrays preserve policy and transactional boundaries',{skip:!url},async t=>{
 const adminPool=new Pool({connectionString:url,max:1}),admin=await adminPool.connect();let runtime;
 const trusted='https://array-audit.example.invalid/storage/v1/object/public/media/owned.png';
 const second='https://array-audit.example.invalid/storage/v1/object/public/media/second.png';
 const deep='['.repeat(12000)+'0'+']'.repeat(12000),overflow='[1e1000000]';
 const protectedSql=CONTENT_FUNCTION_STATEMENTS[0].replaceAll('public.novvin_','novvin_storage_guard.novvin_').replaceAll('SET search_path=pg_catalog,public','SET search_path=pg_catalog,novvin_storage_guard');
 const install=async sql=>admin.query(sql);
 const extracted=async(schema,table,row)=>(await admin.query('SELECT reference_url FROM '+schema+'.novvin_storage_content_urls($1,$2::jsonb)',[table,JSON.stringify(row)])).rows.map(r=>r.reference_url);
 try{
  if(process.env.STORAGE_CONTENT_ARRAY_BASELINE_SQL){
   await t.test('old actual helper reproduces serialized-array mismatch',async()=>{
    await install(fs.readFileSync(process.env.STORAGE_CONTENT_ARRAY_BASELINE_SQL,'utf8'));
    const row={media_urls:JSON.stringify([trusted,second])};const actual=await extracted('public','posts',row);
    assert.notDeepEqual(actual,sourceUrls('posts',row));assert.deepEqual(actual,[row.media_urls]);
   });
  }
  await admin.query('CREATE ROLE content_array_runtime LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS');
  await admin.query('CREATE ROLE novvin_storage_binding_writer NOLOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS');
  await admin.query('CREATE SCHEMA novvin_storage_guard');
  await install(CONTENT_FUNCTION_STATEMENTS[0]);await install(protectedSql);
  await admin.query('ALTER FUNCTION public.novvin_storage_content_urls(text,jsonb) OWNER TO content_array_runtime');
  await admin.query('ALTER FUNCTION novvin_storage_guard.novvin_storage_content_urls(text,jsonb) OWNER TO novvin_storage_binding_writer');
  await admin.query('REVOKE ALL ON SCHEMA novvin_storage_guard FROM PUBLIC,content_array_runtime');
  await admin.query('GRANT USAGE ON SCHEMA novvin_storage_guard TO novvin_storage_binding_writer');
  await admin.query('REVOKE ALL ON FUNCTION novvin_storage_guard.novvin_storage_content_urls(text,jsonb) FROM PUBLIC,content_array_runtime');
  const runtimeUrl=new URL(url);runtimeUrl.username='content_array_runtime';runtime=new Pool({connectionString:runtimeUrl.toString(),max:1});
  await t.test('serialized arrays match JavaScript selection for native and imported descriptors',async()=>{
   for(const table of ['posts','fallback_posts'])for(const media_urls of [JSON.stringify([trusted,second]),JSON.stringify([trusted,42,null]),'  '+JSON.stringify([trusted])+'  ']){
    const row={media_urls};const expected=sourceUrls(table,row);
    assert.deepEqual(await extracted('public',table,row),expected);assert.deepEqual(await extracted('novvin_storage_guard',table,row),expected);
   }
  });
  await t.test('JSON arrays and scalar URL behavior remain unchanged',async()=>{
   for(const [table,row] of [['posts',{media_urls:[trusted,null,42,second]}],['fallback_posts',{media_url:trusted}],['fallback_profile_overrides',{profile:{favoriteMusicUrl:trusted}}]]){
    assert.deepEqual(await extracted('public',table,row),sourceUrls(table,row));
   }
  });
  await t.test('PostgreSQL cast records Unicode numeric errors and accepted deep input independently',async()=>{
   await admin.query('BEGIN');const states=[];
   try{for(const [raw,expected] of [[JSON.stringify(['\u0000']),'22P05'],['["\\uD800"]','22P02'],[overflow,'22003'],[deep,null]]){
    await admin.query('SAVEPOINT invalid_array_cast');let observed=null;
    try{await admin.query('SELECT $1::jsonb',[raw]);}catch(error){observed=error.code;}
    await admin.query('ROLLBACK TO SAVEPOINT invalid_array_cast');assert.equal(observed,expected);states.push(observed);
   }
   console.log('OBSERVED_ARRAY_CAST_SQLSTATES='+JSON.stringify(states));
   }finally{await admin.query('ROLLBACK');}
  });
  await t.test('malformed or empty serialized arrays yield no references without aborting caller transaction',async()=>{
   await admin.query('BEGIN');
   try{for(const malformed of ['[invalid','["'+trusted+'",]','[]',' [null,42] ',JSON.stringify(['\u0000']),'["\\uD800"]',overflow,deep])assert.deepEqual(await extracted('public','posts',{media_urls:malformed}),[]);
    assert.equal((await admin.query('SELECT 1 AS healthy')).rows[0].healthy,1);
   }finally{await admin.query('ROLLBACK');}
  });
  await t.test('ordinary text unknown tables and foreign fields never become media references',async()=>{
   assert.deepEqual(await extracted('public','posts',{content:trusted,about:trusted}),[]);
   assert.deepEqual(await extracted('public','unknown_source',{media_urls:JSON.stringify([trusted])}),[]);
   assert.deepEqual(await extracted('public','fallback_profile_overrides',{profile:{about:trusted}}),[]);
  });
  const metadata=async()=>(await admin.query(`SELECT n.nspname,pg_get_userbyid(p.proowner) AS owner,p.prosecdef,p.proconfig,p.proacl::text
   FROM pg_proc p JOIN pg_namespace n ON n.oid=p.pronamespace WHERE p.proname='novvin_storage_content_urls' AND n.nspname IN ('public','novvin_storage_guard') ORDER BY n.nspname`)).rows;
  await t.test('function replacement preserves owners ACL invoker mode and fixed search path',async()=>{
   const before=await metadata();await install(CONTENT_FUNCTION_STATEMENTS[0]);await install(protectedSql);assert.deepEqual(await metadata(),before);
   assert.equal(before.find(r=>r.nspname==='novvin_storage_guard').owner,'novvin_storage_binding_writer');
   assert.ok(before.every(r=>r.prosecdef===false&&r.proconfig.some(x=>x.startsWith('search_path=pg_catalog,'))));
   const result=await runtime.query('SELECT reference_url FROM public.novvin_storage_content_urls($1,$2::jsonb)',['posts',JSON.stringify({media_urls:JSON.stringify([trusted])})]);assert.equal(result.rows[0].reference_url,trusted);
   await assert.rejects(runtime.query('SELECT * FROM novvin_storage_guard.novvin_storage_content_urls($1,$2::jsonb)',['posts','{}']),e=>e.code==='42501');
  });
  await t.test('function DDL respects caller rollback with unchanged metadata and definition',async()=>{
   const before=(await admin.query("SELECT md5(pg_get_functiondef('public.novvin_storage_content_urls(text,jsonb)'::regprocedure)) AS digest")).rows[0].digest;
   await admin.query('BEGIN');await install(CONTENT_FUNCTION_STATEMENTS[0].replace('-- Imports store','-- Synthetic rollback proof imports store'));
   assert.notEqual((await admin.query("SELECT md5(pg_get_functiondef('public.novvin_storage_content_urls(text,jsonb)'::regprocedure)) AS digest")).rows[0].digest,before);
   await admin.query('ROLLBACK');
   assert.equal((await admin.query("SELECT md5(pg_get_functiondef('public.novvin_storage_content_urls(text,jsonb)'::regprocedure)) AS digest")).rows[0].digest,before);
  });
 }finally{if(runtime)await runtime.end();admin.release();await adminPool.end();}
});
