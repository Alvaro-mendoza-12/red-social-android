const {test}=require('node:test')
const assert=require('node:assert/strict')
process.env.STORAGE_SCAN_ENFORCE='true'
process.env.STORAGE_READ_ENFORCE='true'
process.env.STORAGE_READ_ORIGINS='https://media.example.test'
process.env.PUBLIC_BASE_URL='https://media.example.test'
const {resolveOwnedStorageUrl}=require('../src/data-routes')
const owner='00000000-0000-4000-8000-000000000041',base='https://media.example.test/storage/v1/object/public/avatars/'
test('avatar ownership retains scan conditions in WHERE, with a valid integer ordering expression',async()=>{
 let query,values
 const pool={query:async(sql,args)=>{query=sql;values=args;return{rows:[{name:owner+'/qa.png'}]}}}
 const result=await resolveOwnedStorageUrl(pool,owner,base+'qa.png',['avatars'])
 assert.equal(result,base+owner+'/qa.png');assert.equal(values[1],owner);assert.deepEqual(values[2],['qa.png',owner+'/qa.png'])
 const [where,order]=query.split('ORDER BY')
 assert.match(where,/owner_id = \$2::uuid/);assert.match(where,/scan_state = 'clean'/);assert.match(where,/scan_sha256/)
 assert.match(order,/^ array_position\(\$3::text\[\], name\)\s+LIMIT 1$/)
 assert.doesNotMatch(order,/AND|scan_state/)
})
test('a missing clean owned object remains forbidden',async()=>assert.equal(await resolveOwnedStorageUrl({query:async()=>({rows:[]})},owner,base+'qa.png',['avatars']),null))
