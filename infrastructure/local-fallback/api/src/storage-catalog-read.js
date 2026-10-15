'use strict';

// Catalog membership is evaluated after private-message and content history.
// Saving a private attachment must never turn it into a public catalog object.
const norm=value=>typeof value==='string'?value.trim().toLowerCase():'';
const failure=()=>Object.assign(new Error('STORAGE_REFERENCE_LIMIT'),{code:'STORAGE_REFERENCE_LIMIT',status:503});
const {stickerRowDeleted}=require('./sticker-media-policy');
async function rows(db,table,predicate,params,imported=false) {
  if(!['sticker_packs','sticker_pack_items'].includes(table))throw new Error('CATALOG_SOURCE_INVALID');
  if(imported) {
    const exists=await db.query('SELECT to_regclass($1) AS relation',['supabase_import.'+table]);
    if(!exists.rows[0]?.relation)return[];
  }
  const result=await db.query(imported?
    `SELECT to_jsonb(t) AS row FROM supabase_import."${table}" t WHERE ${predicate.replaceAll('row->>','to_jsonb(t)->>')} LIMIT 65`:
    `SELECT row FROM fallback_compat_rows WHERE table_name=$${params.length+1} AND (${predicate}) LIMIT 65`,imported?params:[...params,table]);
  if(result.rows.length>64)throw failure();
  return result.rows.map(entry=>entry.row);
}
const merge=(legacy,current)=>[...new Map([...legacy,...current].map(row=>[norm(row.id),row])).values()];
function matchesObject(item,object,origins) {
  const ref=require('./storage-read-access').parseObjectReference(item.media_url||item.url,origins);
  return ref?.bucket===object.bucket_id&&ref?.name===object.name;
}
function musicMatchesObject(track,object,origins) {
  return ['audio_url','preview_url','cover_url','waveform_url'].some(field=>{
    const ref=require('./storage-read-access').parseObjectReference(track?.[field],origins);
    return ref?.bucket===object.bucket_id&&ref?.name===object.name;
  });
}
async function loadCommunityMusicReferences(db,object,origins) {
  const urls=require('./storage-read-access').objectReferenceUrls(object,origins).map(url=>url.split('?')[0]);
  const result=await db.query(`SELECT * FROM fallback_music_tracks WHERE
    split_part(COALESCE(audio_url,''),'?',1)=ANY($1::text[]) OR
    split_part(COALESCE(preview_url,''),'?',1)=ANY($1::text[]) OR
    split_part(COALESCE(cover_url,''),'?',1)=ANY($1::text[]) OR
    split_part(COALESCE(waveform_url,''),'?',1)=ANY($1::text[]) LIMIT 65`,[urls]);
  if(result.rows.length>64)throw failure();
  return {known:result.rows.length>0,references:result.rows.filter(track=>musicMatchesObject(track,object,origins)).map(track=>({kind:'music',track}))};
}
async function musicReferenceAllows(db,{track},object,origins,viewerIds,now) {
  if(!track||track.removed_at||track.disabled_at||!musicMatchesObject(track,object,origins))return false;
  if(!['PENDING','PROCESSING','PENDING_REVIEW','CHANGES_REQUIRED','APPROVED'].includes(track.moderation_state))return false;
  const context=await require('./storage-content-read').creatorContext(db,norm(track.owner_id),viewerIds,now);
  if(!context||!context.visible||norm(context.user.id)!==norm(object.owner_id))return false;
  return context.isMine||track.moderation_state==='APPROVED'&&
    ['PUBLIC_LISTEN_ONLY','PUBLIC_REUSABLE','PUBLIC_REMIXABLE'].includes(track.visibility);
}
async function loadCatalogReferences(db,object,origins) {
  if(object.bucket_id==='community-music')return loadCommunityMusicReferences(db,object,origins);
  const music=object.bucket_id==='thumbnails'?await loadCommunityMusicReferences(db,object,origins):{known:false,references:[]};
  const urls=require('./storage-read-access').objectReferenceUrls(object,origins).map(url=>url.split('?')[0]);
  const predicate="split_part(COALESCE(row->>'media_url',row->>'url',''), '?', 1)=ANY($1::text[])";
  const legacy=await rows(db,'sticker_pack_items',predicate,[urls],true);
  const current=await rows(db,'sticker_pack_items',predicate,[urls]);
  // A local replacement with a different URL still overrides the imported row.
  const overrides=legacy.length?await rows(db,'sticker_pack_items',"lower(COALESCE(row->>'id',''))=ANY($1::text[])",[legacy.map(item=>norm(item.id))]):[];
  const items=merge(legacy,[...current,...overrides]);
  const ids=[...new Set(items.map(item=>norm(item.pack_id)).filter(Boolean))];
  const packs=ids.length?merge(await rows(db,'sticker_packs',"lower(COALESCE(row->>'id',''))=ANY($1::text[])",[ids],true),await rows(db,'sticker_packs',"lower(COALESCE(row->>'id',''))=ANY($1::text[])",[ids])):[];
  return {known:music.known||legacy.length>0||current.length>0,references:[...music.references,...items.filter(item=>matchesObject(item,object,origins)).map(item=>({item,pack:packs.find(pack=>norm(pack.id)===norm(item.pack_id))}))]};
}
async function catalogReferenceAllows(db,reference,object,origins,viewerIds,now) {
  if(reference.kind==='music')return musicReferenceAllows(db,reference,object,origins,viewerIds,now);
  const {item,pack}=reference;
  if(stickerRowDeleted(item)||stickerRowDeleted(pack)||!matchesObject(item,object,origins))return false;
  const owner=norm(pack.owner_id||pack.user_id||pack.creator_id);
  const context=await require('./storage-content-read').creatorContext(db,owner,viewerIds,now);
  if(!context||!context.visible||norm(context.user.id)!==norm(object.owner_id))return false;
  // Explicit public packs are readable by authenticated viewers. Private packs
  // remain accessible only to their uploader; copied message media takes the
  // earlier message authorization path and never reaches this branch.
  return context.isMine||pack.is_public===true;
}
module.exports={loadCatalogReferences,catalogReferenceAllows,matchesObject,musicMatchesObject,musicReferenceAllows};
