'use strict';
const test = require('node:test');
const assert = require('node:assert/strict');
const { assertSafeRuntimeDatabaseRole, UNSAFE_ROLE_QUERY } = require('../src/database-role-policy');

test('restricted runtime role is accepted', async () => {
  let statement;
  await assertSafeRuntimeDatabaseRole({query: async sql => {statement=sql;return {rows:[{unsafe_role_exists:false}]};}});
  assert.equal(statement,UNSAFE_ROLE_QUERY);
});
for (const [name,rows] of [['administrative capability',[{unsafe_role_exists:true}]],['missing role',[]],['unknown result',[{}]],['null capability',[{unsafe_role_exists:null}]]]) {
  test(`production startup refuses ${name}`,async()=>{
    await assert.rejects(assertSafeRuntimeDatabaseRole({query:async()=>({rows})}),{code:'RUNTIME_DATABASE_ROLE_UNSAFE'});
  });
}
test('database query failure prevents startup',async()=>{
  await assert.rejects(assertSafeRuntimeDatabaseRole({query:async()=>{throw new Error('fixture database offline');}}),/fixture database offline/);
});
test('development fixture opt out is explicit',async()=>{
  await assertSafeRuntimeDatabaseRole({query:async()=>{throw new Error('must not query');}},false);
});
test('role policy includes inherited and SET ROLE administrative parents and server file roles',()=>{
  assert.match(UNSAFE_ROLE_QUERY,/RECURSIVE/);
  assert.match(UNSAFE_ROLE_QUERY,/pg_auth_members/);
  for(const field of ['rolsuper','rolcreatedb','rolcreaterole','rolreplication','rolbypassrls','pg_read_server_files','pg_write_server_files','pg_execute_server_program'])assert.ok(UNSAFE_ROLE_QUERY.includes(field));
});
