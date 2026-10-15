const test=require('node:test'),assert=require('node:assert/strict');
const {normalizeCompatProfileJson:n}=require('../src/compat-profile-json');
test('CSV structured cells become typed JSON without modifying stored data',()=>{
 const row={interests:'["music"]',linked_games:'[]',game_logo_urls:'{}',social_links:'{"x":"https://x.com/example"}'};
 const before=JSON.stringify(row);const result=n(row);
 assert.deepEqual(result.interests,['music']);assert.deepEqual(result.linked_games,[]);
 assert.deepEqual(result.game_logo_urls,{});assert.deepEqual(result.social_links,{x:'https://x.com/example'});
 assert.equal(JSON.stringify(row),before);
});
test('empty and malformed collection cells never break typed Android profiles',()=>{
 assert.deepEqual(n({interests:'',hobbies:'null',linked_games:'broken',profile_section_order:'{}'}),
   {interests:[],hobbies:[],linked_games:[],profile_section_order:[]});
 assert.deepEqual(n({game_logo_urls:'[]',social_links:'bad'}),{game_logo_urls:{},social_links:{}});
});
test('legacy booleans and numeric cells use real JSON types',()=>{
 const result=n({is_verified:'true',is_admin:'false',numeric_id:'40213',following_count:'1',
   suspension_count:'',balance:'null',avatar_duration_ms:'2000',is_online:'false'});
 assert.deepEqual(result,{is_verified:true,is_admin:false,numeric_id:40213,following_count:1,
   suspension_count:0,balance:0,avatar_duration_ms:2000,is_online:false});
});
test('visit privacy accepts a known boolean and fails closed on malformed explicit preferences',()=>{
 assert.equal(n({profile_view_history_enabled:'false'}).profile_view_history_enabled,false);
 assert.equal(n({profile_view_history_enabled:'true'}).profile_view_history_enabled,true);
 assert.equal(n({profile_view_history_enabled:'bad'}).profile_view_history_enabled,false);
 assert.equal(n({profile_view_history_enabled:false}).profile_view_history_enabled,false);
});
test('typed current fields retain their values',()=>{
 const row={id:'id',interests:['music'],is_private:true,is_admin:false,numeric_id:42,
   profile_section_visibility:{music:'everyone'},avatar_duration_ms:2000};assert.deepEqual(n(row),row);
});
test('invalid numeric values do not become NaN, unsafe integers or negative schema types',()=>{
 assert.deepEqual(n({numeric_id:'bad',following_count:'bad',suspension_count:'1.2',balance:'Infinity'}),
   {numeric_id:null,following_count:0,suspension_count:0,balance:0});
});
test('CSV provenance fields are not part of the public profile response',()=>{
 assert.deepEqual(n({id:'owner',__source_file:'private-path',__imported_at:'date'}),{id:'owner'});
});
test('optional badge cells are safe for the Android schema',()=>{
 assert.equal(n({badges:''}).badges,null);
 assert.deepEqual(n({badges:'[{"id":"x"}]'}).badges,[]);
});

test('PostgreSQL timestamps retain their ISO value',()=>{
 const date=new Date('2026-10-11T18:00:00Z');
 assert.equal(n({created_at:date}).created_at,date.toISOString());
});
