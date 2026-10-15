'use strict';
const test=require('node:test'),assert=require('node:assert/strict');
const {VIDEO_CANDIDATE_SQL}=require('../src/algorithm-local-fallback-candidate-source');
const {buildImportedVideoSelector,loadImportedVideoCandidateRows}=require('../src/algorithm-imported-video-candidates');
const video=new Set(['id','user_id','video_url','is_public','created_at','updated_at','title','duration_seconds']);
const profile=new Set(['id','is_private','profile_visibility','break_mode']);
test('imported video selector retains canonical safety and denies private/local overrides',()=>{
 const sql=buildImportedVideoSelector(video,profile,VIDEO_CANDIDATE_SQL);
 for(const pattern of [/classification\.safety_state = 'allowed'/,/fallback_profile_moderation/,/fallback_video_deletions/,/fallback_deleted_identities/,/profile_visibility/,/is_private/,/break_mode/,/NOT EXISTS \(SELECT 1 FROM fallback_videos local_override/,/LIMIT \$3/,/pg_input_is_valid/])assert.match(sql,pattern);
 assert.doesNotMatch(sql,/SELECT \*|to_jsonb|\.tags|email|phone|birth_date|gender|password|fallback_messages/i);
});
test('missing public flag, identity, date or profile privacy descriptor excludes imports',()=>{
 for(const absent of ['id','user_id','video_url','is_public']){const columns=new Set(video);columns.delete(absent);assert.equal(buildImportedVideoSelector(columns,profile,VIDEO_CANDIDATE_SQL),null);}
 const noDate=new Set(video);noDate.delete('created_at');noDate.delete('updated_at');assert.equal(buildImportedVideoSelector(noDate,profile,VIDEO_CANDIDATE_SQL),null);
 assert.equal(buildImportedVideoSelector(video,new Set(['id']),VIDEO_CANDIDATE_SQL),null);
});
test('unknown metadata cannot become a dynamic SQL identifier',()=>{
 const sql=buildImportedVideoSelector(new Set([...video,'password','id); DROP TABLE fallback_users;--']),profile,VIDEO_CANDIDATE_SQL);
 assert.doesNotMatch(sql,/DROP TABLE|password/);assert.match(sql,/source\."id"::text/);
});
test('optional imports retain exact caller snapshot and bound parameters',async()=>{
 const rows=[...video].map(column_name=>({table_name:'videos',column_name})).concat([...profile].map(column_name=>({table_name:'profiles',column_name})));
 const parameters=[['viewer'],'2026-10-08T12:00:00Z',200],calls=[];
 const pool={query:async(sql,args)=>{calls.push({sql,args});return calls.length===1?{rows}:{rows:[{id:'approved-row'}]};}};
 assert.deepEqual(await loadImportedVideoCandidateRows(pool,VIDEO_CANDIDATE_SQL,parameters),[{id:'approved-row'}]);assert.equal(calls[1].args,parameters);
});
test('missing import metadata and database denial fail closed',async()=>{
 assert.deepEqual(await loadImportedVideoCandidateRows({query:async()=>({rows:[]})},VIDEO_CANDIDATE_SQL,[[],null,200]),[]);
 assert.deepEqual(await loadImportedVideoCandidateRows({query:async()=>{throw Object.assign(Error('denied'),{code:'42501'});}},VIDEO_CANDIDATE_SQL,[[],null,200]),[]);
});

test('recognized legacy privacy schema requires current canonical profile and intersects legacy suspension',()=>{
 const known=new Set(['id','privacy_show_online','privacy_show_last_seen','privacy_allow_messages_from','is_suspended','suspended_until','suspension_end_date']);
 const sql=buildImportedVideoSelector(video,known,VIDEO_CANDIDATE_SQL);
 assert.ok(sql);assert.match(sql,/profile\.user_id IS NOT NULL/);
 assert.match(sql,/EXISTS \(SELECT 1 FROM supabase_import\.profiles legacy_profile/);
 assert.match(sql,/legacy_profile\."is_suspended"/);assert.match(sql,/legacy_profile\."suspended_until"/);
 for(const marker of ['privacy_show_online','privacy_show_last_seen','privacy_allow_messages_from','is_suspended']){
  const unknown=new Set(known);unknown.delete(marker);assert.equal(buildImportedVideoSelector(video,unknown,VIDEO_CANDIDATE_SQL),null);
 }
 assert.doesNotMatch(sql,/SELECT \*|email|phone|birth_date|password|privacy_allow_messages_from/);
});
test('private alias is recognized and an absent privacy value is denied',()=>{
 const sql=buildImportedVideoSelector(video,new Set(['id','private']),VIDEO_CANDIDATE_SQL);
 assert.match(sql,/legacy_profile\."private"/);assert.match(sql,/'unknown'/);
 assert.match(sql,/NOT IN \('false','0','no'\)/);
});
