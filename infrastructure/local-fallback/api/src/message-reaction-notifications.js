'use strict'
const clean=value=>String(value||'').trim().toLowerCase()
function reactionNotificationsMuted(rows, ownerIds) {
  const own = rows.filter(row => ownerIds.includes(clean(row.user_id || row.profile_id)))
  const preference = own.at(-1)
  return preference?.is_muted === true || preference?.is_muted === 'true'
}
function reactionNotice({ actorIds, actorId, ownerId, emoji, reactions, deleted, deletedBy = [], ownerIds = [ownerId] }) {
  const ids=new Set([...(actorIds||[]),actorId].map(clean).filter(Boolean))
  const owner=clean(ownerId)
  if(!owner || ids.has(owner) || deleted || deletedBy.some(value=>ownerIds.map(clean).includes(clean(value))) || !(reactions?.[emoji]||[]).some(value=>ids.has(clean(value)))) return null
  return {ownerId:owner,actorId:clean(actorId),emoji:String(emoji).slice(0,16)}
}
async function ensureReactionNoticeTables(db) {
  await db.query(`CREATE TABLE IF NOT EXISTS fallback_message_reaction_notices (
    message_id UUID NOT NULL,actor_id TEXT NOT NULL,notified_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY(message_id,actor_id))`)
  await db.query('CREATE INDEX IF NOT EXISTS fallback_reaction_notices_time_idx ON fallback_message_reaction_notices(notified_at)')
  await db.query("DELETE FROM fallback_message_reaction_notices WHERE notified_at<NOW()-INTERVAL '30 days'")
}
async function notifyMessageReaction(pool,{actorId,actorIds,kind,scopeId,message,emoji,reactions}) {
  const notice=reactionNotice({actorId,actorIds,ownerId:message?.sender_id,emoji,reactions,deleted:message?.deleted_for_all===true})
  if(!notice || !message?.id || !['chat','group','community'].includes(kind))return false
  try {
    const users=await pool.query(`SELECT id::text,supabase_user_id::text,display_name,avatar_url FROM fallback_users
      WHERE lower(id::text)=ANY($1::text[]) OR lower(COALESCE(supabase_user_id::text,''))=ANY($1::text[])`,[[notice.ownerId,notice.actorId]])
    const aliases=row=>[row.id,row.supabase_user_id].map(clean).filter(Boolean)
    const owner=users.rows.find(row=>aliases(row).includes(notice.ownerId)),actor=users.rows.find(row=>aliases(row).includes(notice.actorId))
    if(!owner||!actor || aliases(owner).some(id=>aliases(actor).includes(id)))return false
    if(Array.isArray(message.deleted_by) && message.deleted_by.some(value=>aliases(owner).includes(clean(value))))return false
    const data=require('./data-routes'),identity=data.userIdentity(actor)
    if((await data.blockRelationshipForPair(pool,identity,notice.ownerId)).isBlocked)return false
    let title='Chat'
    if(kind==='chat') {
      const [imported,local] = await Promise.all([
        data.importedRowsByFieldValues(pool,'chat_participants',['chat_id'],[scopeId],1000),
        data.localChatParticipantsByUserIds(pool,aliases(owner)),
      ])
      if(reactionNotificationsMuted([...imported,...local].filter(row=>clean(row.chat_id)===clean(scopeId)),aliases(owner)))return false
    }
    if(kind!=='chat') {
      const {loadCollectionMembershipState,collectionHistoryAllows}=require('./collection-membership-policy')
      const state=await loadCollectionMembershipState(pool,kind,scopeId,aliases(owner))
      if(!state.member || !collectionHistoryAllows(state.history,message))return false
      if(reactionNotificationsMuted(state.members,aliases(owner)))return false
      title=state.room?.name || (kind==='group'?'Grupo':'Comunidad')
    }
    const claim=await pool.query(`INSERT INTO fallback_message_reaction_notices(message_id,actor_id) VALUES($1::uuid,$2)
      ON CONFLICT(message_id,actor_id) DO UPDATE SET notified_at=NOW()
      WHERE fallback_message_reaction_notices.notified_at<NOW()-INTERVAL '30 seconds' RETURNING message_id`,[message.id,clean(actor.supabase_user_id||actor.id)])
    if(!claim.rows.length)return false
    const recipient=clean(owner.supabase_user_id||owner.id)
    const extraData={message_id:message.id,[`${kind}_id`]:scopeId,channel_id:message.channel_id||'',conversation_title:title,reaction_emoji:emoji}
    const notification=await data.createLocalNotificationBestEffort(pool,{recipientId:recipient,actorId:identity.displayUserId,
      actorName:actor.display_name||'Alguien',actorAvatarUrl:actor.avatar_url||'',type:'message_reaction',targetId:scopeId,targetType:kind,
      content:`Reaccionó ${emoji} a tu mensaje`,extraData})
    if(notification) {
      const {publishRealtimeEvent}=require('./realtime-publish')
      await Promise.all(aliases(owner).map(id=>publishRealtimeEvent(`user:${id}`,{event:'message_reaction',notification,extraData})))
    }
    return Boolean(notification)
  } catch(error) { console.error('[reaction-notice] delivery failed',String(error.code||'DELIVERY_FAILED'));return false }
}
module.exports={reactionNotice,reactionNotificationsMuted,ensureReactionNoticeTables,notifyMessageReaction}
