const crypto = require('crypto')
const SYSTEM_MEMBERSHIP = 'system_membership'
const id = value => String(value || '').trim().toLowerCase()
const yes = value => value === true || value === 'true'
const time = value => value ? new Date(value).getTime() : NaN
const removed = row => yes(row?.is_deleted) || yes(row?.deleted) || ['left','removed','deleted'].includes(id(row?.status || row?.member_status))
const userId = row => id(row?.user_id || row?.profile_id || row?.member_id)
const aliases = user => [...new Set([id(user?.id), id(user?.supabase_user_id)].filter(Boolean))]
const preferred = user => id(user.supabase_user_id || user.id)
const admin = role => ['owner','creator','admin'].includes(id(role))
const fail = (code, status=403) => Object.assign(new Error(code), {status})

async function ensureCollectionMembershipTables(db) {
  await db.query(`CREATE TABLE IF NOT EXISTS fallback_collection_chat_history (
    room_kind TEXT NOT NULL CHECK(room_kind IN ('group','community')), room_id TEXT NOT NULL,
    user_id TEXT NOT NULL, departed_at TIMESTAMPTZ NOT NULL, read_ranges JSONB NOT NULL,
    hidden BOOLEAN NOT NULL DEFAULT FALSE, PRIMARY KEY(room_kind,room_id,user_id))`)
  await db.query(`CREATE TABLE IF NOT EXISTS fallback_collection_policies (
    room_kind TEXT NOT NULL CHECK(room_kind IN ('group','community')), room_id TEXT NOT NULL,
    members_can_add BOOLEAN NOT NULL DEFAULT TRUE, updated_by TEXT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), PRIMARY KEY(room_kind,room_id))`)
  await db.query(`CREATE TABLE IF NOT EXISTS fallback_collection_history_grants (
    room_kind TEXT NOT NULL CHECK(room_kind IN ('group','community')), room_id TEXT NOT NULL,
    user_id TEXT NOT NULL, joined_at TIMESTAMPTZ NOT NULL, history_from TIMESTAMPTZ,
    history_until TIMESTAMPTZ, granted_by TEXT NOT NULL,
    CHECK ((history_from IS NULL AND history_until IS NULL) OR
      (history_from IS NOT NULL AND history_until IS NOT NULL AND history_from <= history_until AND history_until <= joined_at)),
    PRIMARY KEY(room_kind,room_id,user_id))`)
  await db.query('ALTER TABLE fallback_collection_history_grants ADD COLUMN IF NOT EXISTS allow_all_history BOOLEAN')
}

// Query a single room, never a bounded global list that could omit a removal.
async function legacyRows(db, table, kind, roomId, isRoom=false) {
  const key = isRoom ? 'id' : `${kind}_id`
  const result = await db.query('SELECT to_regclass($1) AS oid', [`supabase_import.${table}`])
  const imported = result.rows[0]?.oid ? (await db.query(
    `SELECT to_jsonb(t) AS row FROM supabase_import."${table}" t
     WHERE lower(COALESCE(to_jsonb(t)->>'${key}',to_jsonb(t)->>'room_id',''))=$1`, [roomId])).rows.map(r=>r.row) : []
  const compat = await db.query(`SELECT row FROM fallback_compat_rows WHERE table_name=$1
    AND lower(COALESCE(row->>'${key}',row->>'room_id',''))=$2 ORDER BY updated_at`, [table,roomId])
  return [...imported,...compat.rows.map(r=>r.row)]
}

async function loadCollectionMembershipSnapshot(db, kind, rawRoomId) {
  if (!['group','community'].includes(kind)) throw fail('ROOM_KIND_INVALID',400)
  const roomId=id(rawRoomId)
  const [oldRooms, oldMembers, localRooms, localMembers, policies, grants] = await Promise.all([
    legacyRows(db,kind==='group'?'groups':'communities',kind,roomId,true),
    legacyRows(db,`${kind}_members`,kind,roomId),
    db.query('SELECT * FROM fallback_collection_rooms WHERE room_kind=$1 AND lower(id::text)=$2',[kind,roomId]),
    db.query('SELECT * FROM fallback_collection_members WHERE room_kind=$1 AND lower(room_id)=$2',[kind,roomId]),
    db.query('SELECT members_can_add FROM fallback_collection_policies WHERE room_kind=$1 AND room_id=$2',[kind,roomId]),
    db.query('SELECT * FROM fallback_collection_history_grants WHERE room_kind=$1 AND room_id=$2',[kind,roomId]),
  ])
  const archives=await db.query('SELECT * FROM fallback_collection_chat_history WHERE room_kind=$1 AND room_id=$2',[kind,roomId])
  return {kind,roomId,oldRooms,oldMembers,localRooms,localMembers,policies,grants,archives}
}
function collectionMembershipStateFromSnapshot(snapshot,viewerIds) {
  const {kind,roomId,oldRooms,oldMembers,localRooms,localMembers,policies,grants,archives}=snapshot
  const ids=[...viewerIds].map(id).filter(Boolean)
  const room=localRooms.rows[0] || oldRooms.at(-1)
  const memberMap=new Map(oldMembers.map(row=>[userId(row),row]))
  localMembers.rows.forEach(row=>memberMap.set(userId(row),row))
  const members=[...memberMap.values()]
  const ownLocal=localMembers.rows.filter(row=>ids.includes(userId(row)))
  const denied=ownLocal.some(removed)
  const own=members.filter(row=>ids.includes(userId(row)) && !removed(row))
  const creator=Boolean(room && ids.includes(id(room.creator_id || room.owner_id || room.user_id)) && !denied)
  // An alias must not resurrect an older membership epoch.
  const ownEffective=(ownLocal.length?ownLocal.filter(row=>!removed(row)):own)
    .sort((a,b)=>(time(b.joined_at || b.created_at)||0)-(time(a.joined_at || a.created_at)||0))[0]
  const member=Boolean(room && !removed(room) && !denied && (creator || ownEffective))
  let joinedAt=ownEffective?.joined_at || ownEffective?.created_at || null
  // Older metadata updates shadowed imported memberships with a fresh NOW().
  // A real departure/rejoin always has an archive or an explicit epoch grant.
  // Repair only a continuous imported membership, never an actual rejoin.
  if(member && ownLocal.length && !grants.rows.some(row=>ids.includes(id(row.user_id))) &&
    !archives.rows.some(row=>ids.includes(id(row.user_id))) &&
    !oldMembers.some(row=>ids.includes(userId(row)) && removed(row))) {
    const original=oldMembers.filter(row=>ids.includes(userId(row)) && !removed(row))
      .map(row=>row.joined_at || row.created_at).filter(value=>Number.isFinite(time(value)))
      .sort((a,b)=>time(a)-time(b))[0]
    if(original && Number.isFinite(time(joinedAt)) && time(original)<time(joinedAt)) joinedAt=original
  }
  if(Number.isFinite(time(joinedAt))) joinedAt=new Date(joinedAt).toISOString()
  const grant=grants.rows.find(row=>ids.includes(id(row.user_id)) && time(row.joined_at)===time(joinedAt))
  const archive=archives.rows.filter(row=>ids.includes(id(row.user_id)))
    .sort((a,b)=>time(b.departed_at)-time(a.departed_at))[0]
  const retainedRanges=Array.isArray(archive?.read_ranges)?archive.read_ranges:[]
  const history={isMember:member,canReadHistory:member || retainedRanges.length>0,retainedRanges,
    hidden:!member && archive?.hidden===true,allHistory:member && creator && grant?.allow_all_history !== false,joinedAt:member?joinedAt:null,
    historyFrom:member?grant?.history_from || null:null,historyUntil:member?grant?.history_until || null:null}
  const canManage=member && (creator || admin(ownEffective?.role))
  const membersCanAdd=policies.rows[0]?.members_can_add !== false
  return {kind,roomId,room,members,history,creator,member,canManage,membersCanAdd,
    canAddMembers:member && (canManage || membersCanAdd), denied}
}

async function loadCollectionMembershipState(db,kind,roomId,viewerIds) {
  return collectionMembershipStateFromSnapshot(await loadCollectionMembershipSnapshot(db,kind,roomId),viewerIds)
}
async function collectionMessageRecipients(db,kind,roomId,message,recipientIds) {
  if(!recipientIds.length) return []
  const snapshot=await loadCollectionMembershipSnapshot(db,kind,roomId)
  const requested=[...new Set(recipientIds.map(id).filter(Boolean))]
  const users=await db.query(`SELECT id::text,supabase_user_id::text FROM fallback_users
    WHERE lower(id::text)=ANY($1::text[]) OR lower(COALESCE(supabase_user_id::text,''))=ANY($1::text[])`,[requested])
  const allowed=new Set()
  for(const user of users.rows) {
    const identities=aliases(user)
    const history=collectionMembershipStateFromSnapshot(snapshot,identities).history
    if(history.isMember && collectionHistoryAllows(history,message))
      identities.filter(value=>requested.includes(value)).forEach(value=>allowed.add(value))
  }
  return requested.filter(value=>allowed.has(value))
}
function collectionHistoryAllows(history, message) {
  if (!history?.isMember && !history?.canReadHistory) return false
  const at=time(message.created_at || message.createdAt)
  if (!Number.isFinite(at)) return false
  if ((history.retainedRanges || []).some(range=>
    (range.from==null || (Number.isFinite(time(range.from)) && at>=time(range.from))) &&
    Number.isFinite(time(range.until)) && at<=time(range.until))) return true
  if (!history.isMember) return false
  if (history.allHistory) return true
  const joined=time(history.joinedAt)
  if (!Number.isFinite(joined)) return false
  if (at>=joined) return true
  const from=time(history.historyFrom), until=time(history.historyUntil)
  return Number.isFinite(from) && Number.isFinite(until) && at>=from && at<=until
}
function freezeCollectionHistory(history, departedAt) {
  const until=new Date(departedAt).toISOString()
  const ranges=[...(history.retainedRanges || [])]
  if(history.allHistory) ranges.push({from:null,until})
  else {
    if(Number.isFinite(time(history.joinedAt))) ranges.push({from:new Date(history.joinedAt).toISOString(),until})
    if(Number.isFinite(time(history.historyFrom)) && Number.isFinite(time(history.historyUntil)))
      ranges.push({from:new Date(history.historyFrom).toISOString(),until:new Date(history.historyUntil).toISOString()})
  }
  // Coalesce overlapping intervals without filling a gap between membership epochs.
  ranges.sort((a,b)=>(a.from==null?-Infinity:time(a.from))-(b.from==null?-Infinity:time(b.from)))
  const merged=[]
  for(const range of ranges) {
    const last=merged.at(-1)
    if(last && (range.from==null || time(range.from)<=time(last.until))) {
      if(time(range.until)>time(last.until)) last.until=range.until
    } else merged.push({...range})
  }
  return merged
}
function parseHistoryGrant(body, actorHistory, joinedAt) {
  const from=body?.historyFrom, until=body?.historyUntil
  if (from == null && until == null) return {from:null,until:null}
  const start=time(from), end=time(until), joined=time(joinedAt)
  if (!Number.isFinite(start) || !Number.isFinite(end) || start>end || end>joined) throw fail('HISTORY_RANGE_INVALID',400)
  // Sharing cannot increase the adding member's own visibility.
  if (!actorHistory?.allHistory) {
    const ranges=[[time(actorHistory?.joinedAt),joined],[time(actorHistory?.historyFrom),time(actorHistory?.historyUntil)],
      ...(actorHistory?.retainedRanges || []).map(range=>[range.from==null?-8640000000000000:time(range.from),time(range.until)])]
      .filter(([a,b])=>Number.isFinite(a)&&Number.isFinite(b)&&a<=b).sort((a,b)=>a[0]-b[0])
    let covered=null
    for(const [a,b] of ranges) {
      if(b<start) continue
      if(a>(covered ?? start)) break
      covered=Math.max(covered ?? start,b)
      if(covered>=end) return {from:new Date(start).toISOString(),until:new Date(end).toISOString()}
    }
    throw fail('HISTORY_RANGE_NOT_ACCESSIBLE')
  }
  return {from:new Date(start).toISOString(),until:new Date(end).toISOString()}
}
async function lockRoom(db,kind,roomId) {
  await db.query('SELECT pg_advisory_xact_lock(hashtextextended($1,0))',[`membership/${kind}/${roomId}`])
}
async function findUser(db,rawId) {
  const result=await db.query(`SELECT id::text,supabase_user_id::text,display_name,avatar_url FROM fallback_users
    WHERE lower(id::text)=$1 OR lower(COALESCE(supabase_user_id::text,''))=$1 LIMIT 1`,[id(rawId)])
  if(!result.rows[0]) throw fail('MEMBER_USER_NOT_FOUND',404)
  return result.rows[0]
}
const name = user => String(user.display_name || 'Usuario').replace(/[\r\n\u0000-\u001f]/g,' ').trim().slice(0,80) || 'Usuario'

async function addCollectionMember(db,{kind,roomId,actor,target,body={},allowInvite=false}) {
  const actorIds=aliases(actor), targetIds=aliases(target), targetId=preferred(target)
  const state=await loadCollectionMembershipState(db,kind,roomId,actorIds)
  if(!state.room || removed(state.room)) throw fail('ROOM_NOT_FOUND',404)
  const self=targetIds.some(value=>actorIds.includes(value))
  const publicRoom=state.room.is_public == null ? kind==='community' : yes(state.room.is_public)
  if(!state.canAddMembers && !(self && publicRoom && !state.denied) && !allowInvite) throw fail('ADD_MEMBER_DENIED')
  const targetState=await loadCollectionMembershipState(db,kind,roomId,targetIds)
  const role=id(body.role || 'member')
  if(!['member','moderator','admin'].includes(role)) throw fail('ROLE_INVALID',400)
  const explicitRole=body.role!=null
  if((role!=='member' || (explicitRole && targetState.member && !self)) && !state.canManage) throw fail('ROLE_CHANGE_DENIED')
  if(kind==='community' && explicitRole && !self && !state.creator) throw fail('COMMUNITY_OWNER_ROLE_REQUIRED')
  if(targetState.creator && explicitRole && !self) throw fail('CREATOR_ROLE_IMMUTABLE')
  if(targetState.member) {
    if(body.historyFrom!=null || body.historyUntil!=null) throw fail('MEMBER_ALREADY_JOINED',409)
    if(explicitRole && !self && state.canManage) {
      // Preserve the current epoch when a role changes on an imported member.
      await db.query(`INSERT INTO fallback_collection_members(room_kind,room_id,user_id,role,joined_at,is_deleted)
        VALUES($1,$2,$3,$4,$5,FALSE) ON CONFLICT(room_kind,room_id,user_id) DO UPDATE SET role=EXCLUDED.role`,
        [kind,roomId,targetId,role,targetState.history.joinedAt])
    }
    return {ok:true,added:false,rolesChanged:explicitRole && !self,historyAccess:targetState.history}
  }
  const clock=await db.query('SELECT clock_timestamp() AS at')
  const joinedAt=new Date(clock.rows[0].at).toISOString()
  const grant=parseHistoryGrant(body,state.history,joinedAt)
  // Collapse historical aliases, making both removal and a new epoch authoritative.
  await db.query(`DELETE FROM fallback_collection_members WHERE room_kind=$1 AND lower(room_id)=$2 AND lower(user_id)=ANY($3::text[])`,[kind,roomId,targetIds])
  await db.query(`INSERT INTO fallback_collection_members(room_kind,room_id,user_id,role,joined_at,is_deleted)
    VALUES($1,$2,$3,$4,$5,FALSE)`,[kind,roomId,targetId,self?'member':role,joinedAt])
  await db.query(`DELETE FROM fallback_collection_history_grants WHERE room_kind=$1 AND room_id=$2 AND lower(user_id)=ANY($3::text[])`,[kind,roomId,targetIds])
  await db.query(`INSERT INTO fallback_collection_history_grants(room_kind,room_id,user_id,joined_at,history_from,history_until,granted_by)
    VALUES($1,$2,$3,$4,$5,$6,$7)`,[kind,roomId,targetId,joinedAt,grant.from,grant.until,preferred(actor)])
  await db.query(`UPDATE fallback_collection_chat_history SET hidden=FALSE
    WHERE room_kind=$1 AND room_id=$2 AND lower(user_id)=ANY($3::text[])`,[kind,roomId,targetIds])
  const destination=kind==='group'?'al grupo':'a la comunidad'
  const content=allowInvite?`${name(target)} se unió ${destination} con el enlace de invitación`
    :self?`${name(target)} se unió ${destination}`:`${name(actor)} añadió a ${name(target)}`
  const eventId=crypto.randomUUID()
  await db.query(`INSERT INTO fallback_room_messages(id,room_kind,room_id,sender_id,content,content_type,created_at)
    VALUES($1,$2,$3,'',$4,$5,$6)`,[eventId,kind,roomId,content,SYSTEM_MEMBERSHIP,joinedAt])
  return {ok:true,added:true,eventId,historyAccess:{isMember:true,allHistory:false,joinedAt,historyFrom:grant.from,historyUntil:grant.until}}
}

function registerCollectionMembershipRoutes(app,{pool,authRequired,getCurrentUser,onChanged=async()=>{},invalidate=()=>{}}) {
  const paths=['/v1/data/messages/groups/:roomId','/v1/data/messages/communities/:roomId']
  const kindOf=req=>req.path.includes('/communities/')?'community':'group'
  const transact=handler=>async(req,res,next)=>{
    let db
    try {
      const user=await getCurrentUser(pool,req)
      if(!user) return res.status(404).json({error:'USER_NOT_FOUND'})
      db=await pool.connect();await db.query('BEGIN')
      const kind=kindOf(req), roomId=id(req.params.roomId)
      if(!roomId) throw fail('ROOM_ID_REQUIRED',400)
      await lockRoom(db,kind,roomId)
      const result=await handler(db,user,kind,roomId,req)
      await db.query('COMMIT');invalidate()
      res.json(result)
      if(result.eventId || result.rolesChanged) await onChanged({user,kind,roomId,targetUserId:result.targetUserId,eventId:result.eventId,added:result.added===true,removed:result.removed===true}).catch(()=>{})
    } catch(error) {
      if(db) await db.query('ROLLBACK').catch(()=>{})
      if(error.status) res.status(error.status).json({error:error.message});else next(error)
    } finally {db?.release()}
  }
  app.get(paths.map(p=>p+'/membership-policy'),authRequired,async(req,res,next)=>{
    try {
      const user=await getCurrentUser(pool,req)
      if(!user) return res.status(404).json({error:'USER_NOT_FOUND'})
      const state=await loadCollectionMembershipState(pool,kindOf(req),req.params.roomId,aliases(user))
      if(!state.member) return res.status(403).json({error:'ROOM_ACCESS_DENIED'})
      res.json({membersCanAdd:state.membersCanAdd,canAddMembers:state.canAddMembers,canManagePolicy:state.canManage,canAssignRoles:state.creator,onlyAdminsCanTalk:yes(state.room.only_admins_can_talk),weeklyMessageLimit:Number(state.room.weekly_message_limit)||0,historyAccess:state.history})
    }catch(error){next(error)}
  })
  app.patch(paths.map(p=>p+'/membership-policy'),authRequired,transact(async(db,user,kind,roomId,req)=>{
    if(typeof req.body?.membersCanAdd!=='boolean') throw fail('MEMBERS_CAN_ADD_REQUIRED',400)
    const state=await loadCollectionMembershipState(db,kind,roomId,aliases(user))
    if(!state.canManage) throw fail('COLLECTION_ADMIN_REQUIRED')
    await db.query(`INSERT INTO fallback_collection_policies(room_kind,room_id,members_can_add,updated_by)
      VALUES($1,$2,$3,$4) ON CONFLICT(room_kind,room_id) DO UPDATE SET members_can_add=EXCLUDED.members_can_add,
      updated_by=EXCLUDED.updated_by,updated_at=NOW()`,[kind,roomId,req.body.membersCanAdd,preferred(user)])
    return {membersCanAdd:req.body.membersCanAdd,canAddMembers:true,canManagePolicy:true,historyAccess:state.history}
  }))
  app.post(paths.map(p=>p+'/leave'),authRequired,transact(async(db,user,kind,roomId,req)=>{
    const target=await findUser(db,req.body?.userId || preferred(user))
    const targetIds=aliases(target), self=targetIds.some(value=>aliases(user).includes(value))
    const state=await loadCollectionMembershipState(db,kind,roomId,aliases(user))
    const targetState=await loadCollectionMembershipState(db,kind,roomId,targetIds)
    if(!state.room || removed(state.room)) throw fail('ROOM_NOT_FOUND',404)
    // An acknowledged voluntary exit may be retried after its membership was removed.
    if(self && !targetState.member && targetState.denied) return {ok:true,removed:false}
    if(!state.member || (!self && !state.canManage)) throw fail('REMOVE_MEMBER_DENIED')
    if(targetState.creator && !self) throw fail('CREATOR_CANNOT_BE_REMOVED')
    if(!targetState.member) return {ok:true,removed:false}
    const departedAt=new Date((await db.query('SELECT clock_timestamp() AS at')).rows[0].at).toISOString()
    const ranges=freezeCollectionHistory(targetState.history,departedAt)
    await db.query(`DELETE FROM fallback_collection_chat_history WHERE room_kind=$1 AND room_id=$2 AND lower(user_id)=ANY($3::text[])`,[kind,roomId,targetIds])
    await db.query(`INSERT INTO fallback_collection_chat_history(room_kind,room_id,user_id,departed_at,read_ranges)
      VALUES($1,$2,$3,$4,$5::jsonb)`,[kind,roomId,preferred(target),departedAt,JSON.stringify(ranges)])
    if(targetState.creator && self) {
      let successor=null
      const candidates=targetState.members.filter(row=>!removed(row) && !targetIds.includes(userId(row)))
        .sort((a,b)=>Number(admin(b.role))-Number(admin(a.role)) || (time(a.joined_at)||0)-(time(b.joined_at)||0) || userId(a).localeCompare(userId(b)))
      for(const candidate of candidates) {
        const candidateUser=await findUser(db,userId(candidate)).catch(error=>{if(error.status===404)return null;throw error})
        const candidateState=candidateUser?await loadCollectionMembershipState(db,kind,roomId,aliases(candidateUser)):null
        if(candidateState?.member) {
          successor={...candidate,user_id:preferred(candidateUser),aliases:aliases(candidateUser),history:candidateState.history};break
        }
      }
      if(successor) {
        await db.query(`DELETE FROM fallback_collection_history_grants WHERE room_kind=$1 AND room_id=$2 AND lower(user_id)=ANY($3::text[])`,[kind,roomId,successor.aliases])
        await db.query(`INSERT INTO fallback_collection_history_grants(room_kind,room_id,user_id,joined_at,history_from,history_until,granted_by,allow_all_history)
          VALUES($1,$2,$3,$4,$5,$6,$7,FALSE)`,[kind,roomId,successor.user_id,successor.history.joinedAt || successor.joined_at || departedAt,
          successor.history.historyFrom,successor.history.historyUntil,preferred(user)])
        const room=targetState.room
        await db.query(`INSERT INTO fallback_collection_rooms(id,room_kind,name,image,description,category,is_public,creator_id,only_admins_can_talk,daily_message_limit,weekly_message_limit,created_at)
          VALUES($1,$2,$3,$4,$5,$6,$7,$8,$9,$10,$11,$12) ON CONFLICT(id) DO UPDATE SET creator_id=EXCLUDED.creator_id,updated_at=NOW()`,
          [roomId,kind,room.name || room.title || 'Grupo',room.image || room.avatar_url || null,room.description || null,room.category || null,
           room.is_public==null?kind==='community':yes(room.is_public),userId(successor),yes(room.only_admins_can_talk),Number(room.daily_message_limit)||0,Number(room.weekly_message_limit)||0,room.created_at || departedAt])
        await db.query(`INSERT INTO fallback_collection_members(room_kind,room_id,user_id,role,joined_at,is_deleted)
          VALUES($1,$2,$3,'owner',$4,FALSE) ON CONFLICT(room_kind,room_id,user_id) DO UPDATE SET role='owner'`,
          [kind,roomId,userId(successor),successor.joined_at || departedAt])
      }
    }
    for(const targetId of targetIds) await db.query(`INSERT INTO fallback_collection_members(room_kind,room_id,user_id,role,is_deleted)
      VALUES($1,$2,$3,'member',TRUE) ON CONFLICT(room_kind,room_id,user_id) DO UPDATE SET is_deleted=TRUE`,[kind,roomId,targetId])
    const destination=kind==='group'?'del grupo':'de la comunidad'
    const content=self?`${name(target)} salió ${destination}`:`${name(user)} expulsó a ${name(target)} ${destination}`
    const eventId=crypto.randomUUID()
    await db.query(`INSERT INTO fallback_room_messages(id,room_kind,room_id,sender_id,content,content_type,created_at)
      VALUES($1,$2,$3,'',$4,$5,$6)`,[eventId,kind,roomId,content,SYSTEM_MEMBERSHIP,departedAt])
    return {ok:true,removed:true,eventId,targetUserId:preferred(target),historyAccess:{isMember:false,canReadHistory:true,retainedRanges:ranges}}
  }))
  app.post(paths.map(p=>p+'/hide'),authRequired,transact(async(db,user,kind,roomId)=>{
    const state=await loadCollectionMembershipState(db,kind,roomId,aliases(user))
    if(state.member || !state.history.canReadHistory) throw fail('CHAT_HIDE_DENIED')
    await db.query(`UPDATE fallback_collection_chat_history SET hidden=TRUE
      WHERE room_kind=$1 AND room_id=$2 AND lower(user_id)=ANY($3::text[])`,[kind,roomId,aliases(user)])
    return {ok:true,hidden:true}
  }))
  app.post(paths.map(p=>p+'/join'),authRequired,transact(async(db,user,kind,roomId,req)=>{
    const target=await findUser(db,req.body?.userId || preferred(user))
    const result=await addCollectionMember(db,{kind,roomId,actor:user,target,body:req.body})
    return {...result,targetUserId:preferred(target)}
  }))
  app.post(paths.map(p=>p+'/invites'),authRequired,transact(async(db,user,kind,roomId,req)=>{
    const state=await loadCollectionMembershipState(db,kind,roomId,aliases(user))
    if(!state.member) throw fail('GROUP_INVITE_CREATE_DENIED')
    const hours=Math.max(1,Math.min(Math.trunc(Number(req.body?.expiresInHours)||72),168))
    const maxUses=Math.max(1,Math.min(Math.trunc(Number(req.body?.maxUses)||100),500))
    const token=crypto.randomBytes(32).toString('base64url')
    const result=await db.query(`INSERT INTO fallback_collection_invites(room_kind,room_id,token_hash,created_by,expires_at,max_uses)
      VALUES($6,$1,$2,$3,NOW()+($4::text||' hours')::interval,$5) RETURNING id::text,expires_at`,
      [roomId,crypto.createHash('sha256').update(token).digest('hex'),preferred(user),String(hours),maxUses,kind])
    return {token,roomId,maxUses,inviteId:result.rows[0].id,expiresAt:result.rows[0].expires_at}
  }))
  app.post(paths.map(p=>p+'/invites/revoke'),authRequired,transact(async(db,user,kind,roomId)=>{
    const state=await loadCollectionMembershipState(db,kind,roomId,aliases(user))
    if(!state.canManage) throw fail('GROUP_INVITE_CREATE_DENIED')
    await db.query('UPDATE fallback_collection_invites SET revoked_at=NOW() WHERE room_kind=$1 AND lower(room_id)=$2 AND revoked_at IS NULL',[kind,roomId])
    return {ok:true}
  }))
  app.post(paths.map(p=>p+'/join-invite'),authRequired,transact(async(db,user,kind,roomId,req)=>{
    const token=String(req.body?.token||'').trim()
    if(token.length<32 || token.length>200) throw fail('GROUP_INVITE_INVALID',400)
    const existingMember=await loadCollectionMembershipState(db,kind,roomId,aliases(user))
    if(existingMember.member) return {ok:true,added:false,roomId,targetUserId:preferred(user),historyAccess:existingMember.history}
    const invite=await db.query(`SELECT * FROM fallback_collection_invites WHERE room_kind=$3 AND lower(room_id)=$1
      AND token_hash=$2 AND revoked_at IS NULL AND expires_at>NOW() AND use_count<max_uses FOR UPDATE`,
      [roomId,crypto.createHash('sha256').update(token).digest('hex'),kind])
    if(!invite.rows[0]) throw fail('GROUP_INVITE_EXPIRED_OR_USED',410)
    const inviter=await findUser(db,invite.rows[0].created_by)
    const state=await loadCollectionMembershipState(db,kind,roomId,aliases(inviter))
    if(!state.member) throw fail('GROUP_INVITE_CREATE_DENIED')
    const result=await addCollectionMember(db,{kind,roomId,actor:inviter,target:user,allowInvite:true})
    if(result.added) await db.query('UPDATE fallback_collection_invites SET use_count=use_count+1 WHERE id=$1',[invite.rows[0].id])
    return {...result,roomId,targetUserId:preferred(user)}
  }))
}
module.exports={SYSTEM_MEMBERSHIP,ensureCollectionMembershipTables,loadCollectionMembershipSnapshot,loadCollectionMembershipState,collectionMembershipStateFromSnapshot,collectionMessageRecipients,
  collectionHistoryAllows,freezeCollectionHistory,parseHistoryGrant,addCollectionMember,registerCollectionMembershipRoutes,lockRoom}
