'use strict';

// This is a relationship policy, never a grant inferred from a bucket or URL.
// All lookups run on the caller's already connected authorization transaction.
const {parseObjectReference, MAX_REFERENCES}=require('./storage-read-access');
const SOURCES=Object.freeze({
  fallback_posts:{kind:'post',key:'id',fields:['media_url','media_urls']},
  fallback_social_promotions:{kind:'post',key:'id',fields:['image_url','media_urls']},
  fallback_videos:{kind:'video',key:'id',fields:['video_url','cover_url','thumbnail_url','sound_url']},
  fallback_moments:{kind:'moment',key:'id',fields:['media_url','thumbnail_url','music_audio_url','music_url']},
  fallback_users:{kind:'profile',key:'id',fields:['avatar_url']},
  fallback_profile_overrides:{kind:'profile',key:'user_id',nested:'profile',fields:['avatar_url','avatarUrl','cover_url','coverUrl','favorite_music_url','favoriteMusicUrl']},
  posts:{kind:'post',key:'id',fields:['media_url','media_urls','image_url']},
  videos:{kind:'video',key:'id',fields:['video_url','videoUrl','cover_url','coverUrl','thumbnail_url','thumbnailUrl','sound_url','soundUrl']},
  moments:{kind:'moment',key:'id',fields:['media_url','thumbnail_url','music_audio_url','music_url']},
  profiles:{kind:'profile',key:'id',fields:['avatar_url','avatarUrl','photo_url','profile_image','cover_url','coverUrl','favorite_music_url','favoriteMusicUrl']},
});
const LOCAL_TABLES=Object.freeze(Object.keys(SOURCES).filter(name=>name.startsWith('fallback_')));
const IMPORTED_TABLES=Object.freeze(['posts','videos','moments','profiles']);
const norm=value=>typeof value==='string'?value.trim().toLowerCase():'';
const scalar=(row,fields)=>fields.map(field=>row?.[field]).find(value=>typeof value==='string'&&value.trim())?.trim()||'';
const list=value=>{if(Array.isArray(value))return value;if(typeof value==='string'){try{const p=JSON.parse(value);return Array.isArray(p)?p:[];}catch{}}return[];};
const flag=value=>[true,'true','1',1,'yes'].includes(value);
const failure=(code='STORAGE_READ_DENIED',status=404)=>Object.assign(new Error(code),{code,status});
function sourceUrls(table,row) {
  const spec=SOURCES[table];if(!spec)return[];
  const source=spec.nested?row?.[spec.nested]:row;
  if(!source||typeof source!=='object'||Array.isArray(source))return[];
  return spec.fields.flatMap(field=>{
    const parsed=list(source[field]),array=Array.isArray(source[field])||parsed.length>0;
    const values=array?parsed:[source[field]];
    return values.map(value=>{
      if(typeof value==='string')return value;
      // Historical post galleries store explicit {url,...} or {media_url,...}
      // descriptors. Only this declared media array may carry those objects.
      if(!array||field!=='media_urls'||!value||typeof value!=='object'||Array.isArray(value))return null;
      return ['url','media_url'].map(key=>value[key]).find(url=>typeof url==='string'&&url.trim());
    });
  }).filter(value=>typeof value==='string');
}
function referencesObject(table,row,object,origins) {
  return sourceUrls(table,row).some(url=>{const ref=parseObjectReference(url,origins);return ref?.bucket===object.bucket_id&&ref?.name===object.name;});
}
async function importedByIds(db,table,ids,fields=['id']) {
  if(!IMPORTED_TABLES.includes(table)&&!['follows','chats'].includes(table))throw failure('STORAGE_CONTENT_SOURCE_INVALID',503);
  if(!ids.length)return[];
  const exists=await db.query('SELECT to_regclass($1) AS relation',[`supabase_import.${table}`]);if(!exists.rows[0]?.relation)return[];
  const predicates=fields.map(field=>`lower(COALESCE(to_jsonb(t)->>'${field}',''))=ANY($1::text[])`).join(' OR ');
  const result=await db.query(`SELECT to_jsonb(t) AS row FROM supabase_import."${table}" t WHERE ${predicates} LIMIT ${MAX_REFERENCES+1}`,[ids]);
  if(result.rows.length>MAX_REFERENCES)throw failure('STORAGE_REFERENCE_LIMIT',503);
  return result.rows.map(entry=>entry.row);
}
async function loadContentReferences(db,object,origins) {
  const found=await db.query(`SELECT source_schema,source_table,record_id,access_kind FROM public.fallback_storage_content_bindings
    WHERE bucket_id=$1 AND object_name=$2 LIMIT ${MAX_REFERENCES+1}`,[object.bucket_id,object.name]);
  if(found.rows.length>MAX_REFERENCES)throw failure('STORAGE_REFERENCE_LIMIT',503);
  const references=[];
  for(const binding of found.rows) {
    const spec=SOURCES[binding.source_table];
    if(!spec||binding.access_kind!==spec.kind||!['public','supabase_import'].includes(binding.source_schema)||
      (binding.source_schema==='public')!==LOCAL_TABLES.includes(binding.source_table))throw failure('STORAGE_CONTENT_SOURCE_INVALID',503);
    let row;
    if(binding.source_schema==='public') {
      row=(await db.query(`SELECT to_jsonb(t) AS row FROM public."${binding.source_table}" t WHERE lower(t."${spec.key}"::text)=$1 LIMIT 1`,[norm(binding.record_id)])).rows[0]?.row;
    } else {
      row=(await importedByIds(db,binding.source_table,[norm(binding.record_id)]))[0];
      if(spec.kind!=='profile') {
        const localTable=({post:'fallback_posts',video:'fallback_videos',moment:'fallback_moments'})[spec.kind];
        const local=(await db.query(`SELECT to_jsonb(t) AS row FROM public."${localTable}" t WHERE lower(t.id::text)=$1 LIMIT 1`,[norm(binding.record_id)])).rows[0]?.row;
        const authority=await db.query(`SELECT 1 FROM fallback_storage_content_authority WHERE access_kind=$1 AND record_id=$2 LIMIT 1`,[spec.kind,norm(binding.record_id)]);
        if(local||authority.rows.length)continue; // Current local bindings carry the authoritative row, including a replacement URL.
      }
    }
    if(row&&referencesObject(binding.source_table,row,object,origins))references.push({...binding,row});
  }
  const history=await db.query('SELECT 1 FROM fallback_storage_content_history WHERE bucket_id=$1 AND object_name=$2 LIMIT 1',[object.bucket_id,object.name]);
  return {references,known:found.rows.length>0||history.rows.length>0};
}
function profileAliases(user) {return [...new Set([norm(user.id),norm(user.supabase_user_id)].filter(Boolean))];}
function activeSuspension(row,now) {
  const value=row?.is_suspended??row?.isSuspended;
  if(value==null||[false,'false',0,'0','no'].includes(value))return false;
  if(!flag(value))return true;
  const raw=row?.suspended_until??row?.suspension_end_date;
  if(!raw)return true;
  const timestamp=Date.parse(raw);return !Number.isFinite(timestamp)||timestamp>now;
}
async function creatorContext(db,rawId,viewerIds,now) {
  const id=norm(rawId);if(!id)return null;
  const result=await db.query(`SELECT u.id::text,u.supabase_user_id::text,u.avatar_url,COALESCE(o.profile,'{}'::jsonb) AS profile,o.user_id AS override_user_id
    FROM fallback_users u LEFT JOIN fallback_profile_overrides o ON o.user_id=u.id
    WHERE lower(u.id::text)=$1 OR lower(COALESCE(u.supabase_user_id::text,''))=$1 LIMIT 1`,[id]);
  const user=result.rows[0];if(!user)return null;
  const ids=profileAliases(user);
  const removed=await db.query('SELECT 1 FROM fallback_deleted_identities WHERE lower(user_id)=ANY($1::text[]) LIMIT 1',[ids]);if(removed.rows.length)return null;
  const moderation=await db.query('SELECT is_suspended,suspended_until,suspension_end_date FROM fallback_profile_moderation WHERE lower(user_id)=ANY($1::text[])',[ids]);
  if(moderation.rows.some(row=>activeSuspension(row,now)))return null;
  const blocked=await db.query(`SELECT 1 FROM fallback_user_blocks WHERE (lower(blocker_id)=ANY($1::text[]) AND lower(blocked_id)=ANY($2::text[]))
    OR (lower(blocker_id)=ANY($2::text[]) AND lower(blocked_id)=ANY($1::text[])) LIMIT 1`,[viewerIds,ids]);if(blocked.rows.length)return null;
  const legacy=await importedByIds(db,'profiles',ids,['id','user_id','userId','profile_id','profileId','supabase_user_id']);
  if(legacy.some(row=>activeSuspension(row,now)))return null;
  const local=user.profile||{};
  // Explicit clears and explicit privacy controls must not be resurrected by a legacy row.
  const merged={...(legacy[0]||{}),...local,id:user.id,supabase_user_id:user.supabase_user_id};
  if(!Object.hasOwn(local,'avatar_url')&&!Object.hasOwn(local,'avatarUrl'))merged.avatar_url=user.avatar_url||scalar(merged,['avatar_url','avatarUrl','photo_url','profile_image']);
  const isMine=viewerIds.some(value=>ids.includes(value));
  const followRows=(await db.query(`SELECT follower_id,following_id FROM fallback_follows WHERE
    (lower(follower_id)=ANY($1::text[]) AND lower(following_id)=ANY($2::text[])) OR
    (lower(follower_id)=ANY($2::text[]) AND lower(following_id)=ANY($1::text[]))`,[viewerIds,ids])).rows;
  const importedFollows=await importedByIds(db,'follows',[...new Set([...viewerIds,...ids])],['follower_id','user_id']);
  const follows=[...followRows,...importedFollows].filter(row=>!flag(row.is_deleted)&&!flag(row.deleted));
  const following=follows.some(row=>viewerIds.includes(norm(row.follower_id??row.user_id))&&ids.includes(norm(row.following_id??row.target_user_id)));
  const followedBy=follows.some(row=>ids.includes(norm(row.follower_id??row.user_id))&&viewerIds.includes(norm(row.following_id??row.target_user_id)));
  // Unknown imported profile descriptors never imply public visibility. This
  // is the same recognized pre-visibility schema used by the legacy video feed.
  const knownLegacy=row=>['privacy_show_online','privacy_show_last_seen','privacy_allow_messages_from','is_suspended'].every(field=>Object.hasOwn(row,field))&&Boolean(user.override_user_id);
  const descriptorAllows=(profile,imported=false)=>{
    const privateValue=profile.is_private??profile.isPrivate??profile.private;
    const visibility=norm(profile.profile_visibility??profile.profileVisibility)||(!imported||knownLegacy(profile)?'everyone':'unknown');
    const known=privateValue!=null||!imported||knownLegacy(profile);
    if(!known||(!isMine&&privateValue!=null&&![false,'false',0,'0','no'].includes(privateValue)&&!following))return false;
    const breakMode=norm(profile.break_mode??profile.breakMode)||'off';
    if(!isMine&&!['off','mute'].includes(breakMode))return false;
    return isMine||['everyone','public'].includes(visibility)||(['followers','connections'].includes(visibility)&&following);
  };
  const visible=descriptorAllows(local)&&legacy.every(profile=>descriptorAllows(profile,true));
  return {user,ids,profile:merged,isMine,following,followedBy,visible};
}
async function contentReferenceAllows(db,reference,object,origins,viewerIds,now=Date.now()) {
  const {row,source_table:table,access_kind:kind}=reference;
  if(flag(row.is_deleted)||flag(row.deleted)||flag(row.deleted_for_all))return false;
  if(table==='fallback_social_promotions' && row.active!==true)return false;
  const owner=kind==='profile'?(table==='fallback_profile_overrides'?row.user_id:scalar(row,['id','user_id','profile_id'])):scalar(row,['user_id','userId','owner_id','author_id','creator_id','profile_id','created_by']);
  const context=await creatorContext(db,owner,viewerIds,now);if(!context||!context.visible)return false;
  // A URL assignment cannot publish an upload belonging to another account.
  if(norm(object.owner_id)!==norm(context.user.id))return false;
  if(kind==='profile') {
    const effective=sourceUrls('profiles',context.profile).filter(url=>{const ref=parseObjectReference(url,origins);return ref?.bucket===object.bucket_id&&ref?.name===object.name;});
    if(!effective.length)return false;
    const music=scalar(context.profile,['favorite_music_url','favoriteMusicUrl']);
    const musicRef=parseObjectReference(music,origins);
    if(musicRef?.bucket===object.bucket_id&&musicRef?.name===object.name&&!context.isMine) {
      const visibility=norm(context.profile.profile_section_visibility?.music)||'everyone';
      if(visibility!=='everyone'&&!(visibility==='followers'&&context.following))return false;
    }
    return true;
  }
  const recordId=norm(row.id);
  if(kind==='post') {
    const visibility=norm(row.visibility)||'public';
    return context.isMine||visibility==='public'||visibility==='followers'&&context.following;
  }
  if(kind==='video') {
    if((await db.query('SELECT 1 FROM fallback_video_deletions WHERE lower(video_id)=$1 LIMIT 1',[recordId])).rows.length)return false;
    const value=row.is_public??row.isPublic;
    return context.isMine||[true,'true',1,'1','yes'].includes(value);
  }
  if(kind==='moment') {
    if((await db.query('SELECT 1 FROM fallback_moment_deletions WHERE lower(moment_id)=$1 LIMIT 1',[recordId])).rows.length)return false;
    if(row.expires_at!=null&&row.expires_at!=='') {const expires=Date.parse(row.expires_at);if(!Number.isFinite(expires)||expires<=now)return false;}
    if(context.isMine)return true;
    const type=norm(row.visibility_type)||'public';if(type!=='public')return false;
    const audience=norm(row.visibility)||'all';
    const selected=list(row.audience_ids??row.audienceIds).map(norm);
    const excluded=list(row.excluded_ids??row.excludedIds).map(norm);
    if(audience==='only_selected')return viewerIds.some(id=>selected.includes(id));
    if(audience==='except_selected')return !viewerIds.some(id=>excluded.includes(id));
    if(audience==='followers')return context.following;
    if(audience==='following')return context.followedBy;
    if(audience==='all'||audience==='public')return true;
    if(audience==='chats') {
      const chats=await db.query(`SELECT participants FROM fallback_chats WHERE is_group=FALSE AND participants && $1::text[] AND participants && $2::text[]`,[context.ids,viewerIds]);
      return chats.rows.some(chat=>list(chat.participants).map(norm).some(id=>context.ids.includes(id))&&list(chat.participants).map(norm).some(id=>viewerIds.includes(id)));
    }
    return false;
  }
  return false;
}
module.exports={SOURCES,LOCAL_TABLES,IMPORTED_TABLES,sourceUrls,referencesObject,loadContentReferences,creatorContext,contentReferenceAllows,activeSuspension};
