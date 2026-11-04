'use strict'

const { fail } = require('./purpose-domain')
const { loadCollectionMembershipSnapshot, collectionMembershipStateFromSnapshot, collectionHistoryAllows } = require('./collection-membership-policy')
const gaming = require('./playmatch-routes')
const aliases = user => [String(user.id).toLowerCase(),String(user.supabase_user_id || '').toLowerCase()].filter(Boolean)
const publicId = user => String(user.supabase_user_id || user.id).toLowerCase()
const deleted = row => [true,1,'true','t','1'].includes(row?.is_deleted ?? row?.deleted)

function createPurposeContexts(dependencies) {
  const { getCurrentUser, userIdentity, loadPostDetailPayload, importedRowsByFieldValues } = dependencies
  async function actor(db,req) {
    const user=await getCurrentUser(db,req)
    if (!user) throw fail('PURPOSE_ACCESS_DENIED',401)
    if (!(await db.query('SELECT id FROM fallback_users WHERE id=$1 FOR KEY SHARE',[user.id])).rows.length) throw fail('PURPOSE_ACCESS_DENIED',401)
    const identity=userIdentity(user)
    return {user,identity,actorId:identity.displayUserId}
  }
  async function usersByIds(db,ids) {
    if (!ids.length) return []
    return (await db.query(`SELECT id::text,supabase_user_id::text,display_name FROM fallback_users
      WHERE lower(id::text)=ANY($1::text[]) OR lower(COALESCE(supabase_user_id::text,''))=ANY($1::text[])`,[ids])).rows
  }
  async function allowedUsers(db,context,users) {
    if (!users.length) return []
    const targets=[...new Set(users.flatMap(aliases))]
    const blocked=(await db.query(`SELECT lower(blocker_id) AS a,lower(blocked_id) AS b FROM fallback_user_blocks
      WHERE (lower(blocker_id)=ANY($1::text[]) AND lower(blocked_id)=ANY($2::text[]))
        OR (lower(blocked_id)=ANY($1::text[]) AND lower(blocker_id)=ANY($2::text[]))`,[context.identity.values,targets])).rows
    const forbidden=new Set(blocked.flatMap(row=>[row.a,row.b]).filter(value=>!context.identity.ids.has(value)))
    return users.filter(user=>!aliases(user).some(value=>forbidden.has(value)))
  }
  async function social(db,req,postId) {
    const context=await actor(db,req)
    const payload=await loadPostDetailPayload(db,context.user,postId,{includeComments:false})
    const post=payload?.post
    if (!post || deleted(post)) throw fail('PURPOSE_ORIGIN_UNAVAILABLE',404)
    const local=(await db.query('SELECT is_deleted FROM fallback_posts WHERE id=$1',[postId])).rows[0]
    if (local?.is_deleted || (!local && (await importedRowsByFieldValues(db,'posts',['id'],[postId],4)).some(deleted))) throw fail('PURPOSE_ORIGIN_UNAVAILABLE',404)
    const authors=await usersByIds(db,[String(post.user_id).toLowerCase()])
    if (!authors.length || !(await allowedUsers(db,context,authors)).length) throw fail('PURPOSE_ORIGIN_UNAVAILABLE',404)
    return {...context,postId,post,isAuthor:context.identity.ids.has(String(post.user_id).toLowerCase())}
  }
  async function contributions(db,context,requestedIds=null) {
    const imported=await importedRowsByFieldValues(db,'post_comments',requestedIds ? ['id'] : ['post_id','postId'],requestedIds || [context.postId],requestedIds ? 20 : 50)
    const local=(await db.query(`SELECT id::text,post_id,user_id,content,created_at,is_deleted FROM fallback_post_comments
      WHERE lower(post_id)=$1 ${requestedIds ? 'AND lower(id::text)=ANY($2::text[])' : ''}
      ORDER BY created_at DESC,id DESC LIMIT 50`,requestedIds ? [context.postId,requestedIds] : [context.postId])).rows
    const rows=new Map([...imported,...local].filter(row=>String(row.post_id || row.postId || '').toLowerCase()===context.postId).map(row=>[String(row.id).toLowerCase(),row]))
    const candidates=[...rows.values()].filter(row=>!deleted(row)).sort((a,b)=>Date.parse(b.created_at)-Date.parse(a.created_at)).slice(0,20)
    const allowed=await allowedUsers(db,context,await usersByIds(db,[...new Set(candidates.map(row=>String(row.user_id || row.userId || '').toLowerCase()))]))
    return candidates.flatMap(row=>{
      const user=allowed.find(user=>aliases(user).includes(String(row.user_id || row.userId || '').toLowerCase()))
      return user ? [{id:String(row.id).toLowerCase(),authorId:publicId(user),authorName:String(user.display_name || 'Usuario').slice(0,80),content:String(row.content || '').slice(0,300)}] : []
    })
  }
  async function community(db,req,communityId) {
    const context=await actor(db,req)
    const snapshot=await loadCollectionMembershipSnapshot(db,'community',communityId)
    const membership=collectionMembershipStateFromSnapshot(snapshot,context.identity.ids)
    if (!membership.member) throw fail('PURPOSE_ACCESS_DENIED',403)
    return {...context,communityId,snapshot,membership}
  }
  async function communityTargets(db,context) {
    const [questions,events,guides]=await Promise.all([
      db.query(`SELECT q.id::text,q.prompt AS title,m.created_at,m.id::text AS message_id FROM fallback_community_questions q
        JOIN fallback_room_messages m ON m.id=q.message_id WHERE q.room_kind='community' AND q.room_id=$1 AND q.closed=FALSE AND m.deleted_for_all=FALSE
        ORDER BY q.created_at DESC,q.id DESC LIMIT 20`,[context.communityId]),
      db.query(`SELECT id::text,title,starts_at FROM fallback_community_events WHERE community_id=$1 AND is_cancelled=FALSE
        AND starts_at>NOW() ORDER BY starts_at,id LIMIT 20`,[context.communityId]),
      db.query(`SELECT state FROM experimental_purpose_objects WHERE kind='guide' AND target_id=$1
        AND state->>'active'='true' AND (state->>'expiresAt')::timestamptz>NOW() ORDER BY updated_at DESC,object_key LIMIT 20`,[context.communityId]),
    ])
    const volunteers=await allowedUsers(db,context,await usersByIds(db,guides.rows.map(row=>row.state.authorId)))
    return [
      ...questions.rows.filter(row=>collectionHistoryAllows(context.membership.history,row)).map(row=>({kind:'question',id:row.id,title:row.title,messageId:row.message_id})),
      ...events.rows.map(row=>({kind:'event',id:row.id,title:row.title,startsAt:new Date(row.starts_at).toISOString()})),
      ...volunteers.filter(user=>!aliases(user).some(value=>context.identity.ids.has(value)) && collectionMembershipStateFromSnapshot(context.snapshot,aliases(user)).member)
        .map(user=>({kind:'guide',id:publicId(user),title:String(user.display_name || 'Usuario').slice(0,80),expiresAt:guides.rows.find(row=>aliases(user).includes(row.state.authorId)).state.expiresAt})),
    ]
  }
  async function game(db,req,otherId) {
    const context=await actor(db,req), other=await gaming.findUserByAlias(db,otherId)
    if (!other || aliases(other).some(value=>context.identity.ids.has(value)) || await gaming.blockedPair(db,context.user,other)) throw fail('PURPOSE_ACCESS_DENIED',403)
    if (!(await db.query('SELECT id FROM fallback_users WHERE id=$1 FOR KEY SHARE',[other.id])).rows.length) throw fail('PURPOSE_ACCESS_DENIED',403)
    if (!await gaming.acceptedConnection(db,context.user.id,other.id)) throw fail('PURPOSE_CONNECTION_REQUIRED',403)
    const [ownBundle,otherBundle]=await Promise.all([gaming.loadBundle(db,context.user.id),gaming.loadBundle(db,other.id)])
    // Keep the existing adult gate even when discovery preferences change.
    if (!ownBundle?.profile.adult_confirmed || !otherBundle?.profile.adult_confirmed) throw fail('PURPOSE_ADULT_REQUIRED',403)
    const games=ownBundle.games.filter(own=>otherBundle.games.some(theirs=>theirs.game_id===own.game_id &&
      (theirs.platform===own.platform || own.cross_play))).map(row=>({id:row.game_id,title:row.game_name}))
    return {...context,otherId:publicId(other),participants:[context.user,other].map(user=>({id:publicId(user),name:String(user.display_name || 'Jugador').slice(0,80)})),
      games:[...new Map(games.map(row=>[row.id,row])).values()],gameIds:new Set(games.map(row=>row.id))}
  }
  async function gameList(db,req) {
    const context=await actor(db,req)
    const rows=(await db.query(`SELECT item.state,COALESCE(game.name,'Juego') AS game_name FROM experimental_purpose_objects item
      LEFT JOIN fallback_game_catalog game ON game.id::text=item.state->>'gameId'
      WHERE item.kind='gaming' AND item.state->'participants' @> $1::jsonb
      ORDER BY item.updated_at DESC,item.object_key LIMIT 20`,[JSON.stringify([{id:context.actorId}])])).rows
    const others=await usersByIds(db,[...new Set(rows.flatMap(row=>row.state.participants.map(member=>member.id)).filter(value=>!context.identity.ids.has(value)))])
    const allowed=await allowedUsers(db,context,others)
    const [profiles,connections]=await Promise.all([
      db.query('SELECT user_id::text,adult_confirmed FROM fallback_gaming_profiles WHERE user_id=ANY($1::uuid[])',[[context.user.id,...allowed.map(user=>user.id)]]),
      db.query(`SELECT requester_user_id::text,recipient_user_id::text FROM fallback_gaming_connection_requests WHERE status='ACCEPTED'
        AND (requester_user_id=$1 OR recipient_user_id=$1)`,[context.user.id]),
    ])
    if (!profiles.rows.some(row=>row.user_id===String(context.user.id) && row.adult_confirmed)) throw fail('PURPOSE_ADULT_REQUIRED',403)
    const accepted=new Set(connections.rows.flatMap(row=>[row.requester_user_id,row.recipient_user_id]))
    return {actorId:context.actorId,plans:rows.flatMap(row=>{
      const other=allowed.find(user=>row.state.participants.some(member=>aliases(user).includes(member.id)))
      if (!other || !accepted.has(other.id) || !profiles.rows.some(profile=>profile.user_id===String(other.id) && profile.adult_confirmed)) return []
      return [{...row.state,gameTitle:row.game_name,participants:[context.user,other].map(user=>({id:publicId(user),name:String(user.display_name || 'Jugador').slice(0,80)}))}]
    })}
  }
  return {actor,social,contributions,community,communityTargets,game,gameList}
}
module.exports={createPurposeContexts}
