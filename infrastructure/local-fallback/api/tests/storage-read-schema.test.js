'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const { ensureStorageReadSchema } = require('../src/storage-read-schema');
test('schema accepts the connected query-only initializer client', async () => {
  const statements = [];
  const client = { query: async sql => { statements.push(sql); return { rows: [] }; } };
  await ensureStorageReadSchema(client,{origins:['https://schema-audit.example.invalid']});
  assert.equal(statements[0], 'BEGIN'); assert.equal(statements.at(-1), 'COMMIT');
  assert.ok(statements.some(sql => sql.includes('pg_advisory_xact_lock')));
  assert.equal(statements.filter(sql => sql.includes('CREATE OR REPLACE TRIGGER')).length, 8);
});
test('schema failure rolls back its own transaction without releasing caller client', async () => {
  const statements = [];
  const client = { query: async sql => { statements.push(sql); if (sql.includes('CREATE TABLE')) throw new Error('DDL_REJECTED'); return { rows: [] }; } };
  await assert.rejects(ensureStorageReadSchema(client,{origins:['https://schema-audit.example.invalid']}), /DDL_REJECTED/);
  assert.equal(statements.at(-1), 'ROLLBACK');
});
test('explicit existing transaction retains caller commit ownership', async () => {
  const statements = [];
  await ensureStorageReadSchema({ query: async sql => { statements.push(sql); return { rows: [] }; } }, { transactionActive: true,origins:['https://schema-audit.example.invalid'] });
  assert.ok(!statements.some(sql => ['BEGIN','COMMIT','ROLLBACK'].includes(sql)));
});

function importedClient(changes={}) {
  const statements=[];
  return { statements,query:async(sql,params=[])=>{ statements.push(sql);
    if(sql.includes('to_regclass'))return{rows:[{relation:params[0]==='supabase_import.messages'?'supabase_import.messages':null}]};
    if(sql.includes('FROM pg_roles'))return{rows:changes.guardRoleSafe===false?[]:[{one:1}]};
    if(sql.includes('has_schema_privilege'))return{rows:[{readable:changes.readable!==false}]};
    if(sql.includes('FROM pg_attribute'))return{rows:[{name:'media_url',text_type:true}]};
    if(sql.includes('FROM pg_index'))return{rows:[{ready:changes.indexReady!==false}]};
    if(sql.includes('FROM pg_trigger'))return{rows:changes.triggerReady===false?[]:[{prosecdef:changes.triggerDefiner!==false,owner:changes.triggerOwner||'novvin_storage_binding_writer',proconfig:['search_path=pg_catalog, novvin_storage_guard']}]};
    return{rows:[]};
  }};
}
test('runtime validates imported DDL but never grants privileges or creates imported objects',async()=>{
  const client=importedClient(); await ensureStorageReadSchema(client,{origins:['https://schema-audit.example.invalid']});
  assert.ok(client.statements.some(sql=>sql.includes('FROM pg_trigger')));
  assert.ok(client.statements.some(sql=>sql.includes('FROM supabase_import')));
  assert.ok(!client.statements.some(sql=>/^GRANT|^ALTER TABLE/.test(sql)));
  assert.ok(!client.statements.some(sql=>/CREATE (?:INDEX|OR REPLACE TRIGGER)[\s\S]* ON supabase_import/.test(sql)));
});
for(const [setting,code]of[['readable','STORAGE_IMPORT_READ_BOOTSTRAP_REQUIRED'],['indexReady','STORAGE_IMPORT_INDEX_BOOTSTRAP_REQUIRED'],['triggerReady','STORAGE_IMPORT_PROTECTED_TRIGGER_REQUIRED']]) {
  test(`missing imported ${setting} fails schema transaction closed`,async()=>{
    const client=importedClient({[setting]:false}); await assert.rejects(ensureStorageReadSchema(client,{origins:['https://schema-audit.example.invalid']}),{code});
    assert.equal(client.statements.at(-1),'ROLLBACK');
  });
}

for(const [setting,value,code]of[
  ['triggerOwner','novvin_api','STORAGE_IMPORT_PROTECTED_TRIGGER_REQUIRED'],
  ['triggerDefiner',false,'STORAGE_IMPORT_PROTECTED_TRIGGER_REQUIRED'],
  ['guardRoleSafe',false,'STORAGE_IMPORT_GUARD_ROLE_UNSAFE'],
]) {
  test(`unsafe protected trigger ${setting} fails schema transaction closed`,async()=>{
    const client=importedClient({[setting]:value});
    await assert.rejects(ensureStorageReadSchema(client,{origins:['https://schema-audit.example.invalid']}),{code});
    assert.equal(client.statements.at(-1),'ROLLBACK');
  });
}
