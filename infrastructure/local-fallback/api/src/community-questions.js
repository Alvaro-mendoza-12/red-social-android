'use strict'
const { randomUUID } = require('node:crypto')
const { collectionHistoryAllows } = require('./collection-membership-policy')
const { publishRealtimeEvent } = require('./realtime-publish')
const { validateTarget, requireUuid } = require('./raffles/service')

class QuestionError extends Error {
  constructor(code, status = 400) { super(code); this.status = status; this.code = code }
}
const fail = (code, status) => { throw new QuestionError(code, status) }
function shortText(value, max) {
  if (typeof value !== 'string') fail('QUESTION_TEXT_REQUIRED')
  const text = value.trim().normalize('NFC')
  if (!text || [...text].length > max || /[\u0000-\u001f\u007f]/u.test(text)) fail('QUESTION_TEXT_LIMIT')
  return text
}
function answerLimit(value = 40) {
  if (!Number.isInteger(value) || value < 20 || value > 40) fail('QUESTION_ANSWER_LIMIT')
  return value
}
async function ensureCommunityQuestionTables(pool) {
  await pool.query(`CREATE TABLE IF NOT EXISTS fallback_collection_publication_policy (
    room_kind TEXT NOT NULL CHECK(room_kind IN ('group','community')), room_id TEXT NOT NULL,
    only_creator BOOLEAN NOT NULL DEFAULT FALSE, updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY(room_kind,room_id));
    CREATE TABLE IF NOT EXISTS fallback_community_questions (
    id UUID PRIMARY KEY, room_kind TEXT NOT NULL, room_id TEXT NOT NULL, channel_id UUID,
    creator_id TEXT NOT NULL, prompt TEXT NOT NULL, max_length INT NOT NULL CHECK(max_length BETWEEN 20 AND 40),
    message_id UUID UNIQUE NOT NULL, closed BOOLEAN NOT NULL DEFAULT FALSE, created_at TIMESTAMPTZ NOT NULL DEFAULT NOW());
    CREATE INDEX IF NOT EXISTS fallback_questions_room_idx ON fallback_community_questions(room_kind,room_id,created_at);
    CREATE TABLE IF NOT EXISTS fallback_question_answers (
    id UUID PRIMARY KEY, question_id UUID NOT NULL REFERENCES fallback_community_questions(id) ON DELETE CASCADE,
    user_id TEXT NOT NULL, content TEXT NOT NULL CHECK(char_length(content) BETWEEN 1 AND 40),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(), UNIQUE(question_id,user_id));
    CREATE TABLE IF NOT EXISTS fallback_question_votes (
    answer_id UUID NOT NULL REFERENCES fallback_question_answers(id) ON DELETE CASCADE,
    user_id TEXT NOT NULL, value INT NOT NULL CHECK(value IN (-1,1)), PRIMARY KEY(answer_id,user_id));`)
}
async function collectionPublicationAllowed(db, kind, roomId, owner) {
  const { rows } = await db.query('SELECT only_creator FROM fallback_collection_publication_policy WHERE room_kind=$1 AND room_id=$2', [kind, roomId])
  return owner || rows[0]?.only_creator !== true
}

function registerCommunityQuestionRoutes(app, deps) {
  const { pool, authRequired, getCurrentUser, userIdentity, loadCollectionRoomAccess, memberUserId, rowMatchesAny,
    publicProfilesByIds, ensureDefaultCommunityChannel, publishRoomCard = async () => {} } = deps
  const base = '/v1/data/questions/:kind/:roomId'
  async function context(db, user, kind, roomId, write = false) {
    const access = await loadCollectionRoomAccess(db, user, kind, roomId, { allowRetainedHistory: !write })
    if (!access) fail('QUESTION_ACCESS_DENIED', 403)
    const identity = userIdentity(user), owner = rowMatchesAny(access.room, ['creator_id','owner_id','user_id'], identity.ids)
    const active = owner || access.roomMembers.some(m => identity.ids.has(memberUserId(m)) && !m.is_deleted && !['left','removed','banned'].includes(m.status))
    if (write && (!active || access.historyAccess?.isMember === false)) fail('QUESTION_ACCESS_DENIED', 403)
    if (!active && !access.historyAccess?.canReadHistory) fail('QUESTION_ACCESS_DENIED', 403)
    return { ...access, identity, owner, active }
  }
  async function question(db, ctx, kind, roomId, id, lock = false) {
    const row = (await db.query(`SELECT * FROM fallback_community_questions WHERE id=$1 AND room_kind=$2 AND room_id=$3${lock ? ' FOR UPDATE' : ''}`, [requireUuid(id),kind,roomId])).rows[0]
    if (!row || !collectionHistoryAllows(ctx.historyAccess, row)) fail('QUESTION_NOT_FOUND', 404)
    const message = (await db.query('SELECT deleted_for_all FROM fallback_room_messages WHERE id=$1', [row.message_id])).rows[0]
    if (!message || message.deleted_for_all) fail('QUESTION_NOT_FOUND', 404)
    return row
  }
  function route(handler, mutation = false) {
    return async (req,res,next) => {
      let db
      try {
        const {kind,roomId} = validateTarget(req.params.kind, req.params.roomId)
        const user = await getCurrentUser(pool,req)
        if (!user) fail('USER_NOT_FOUND',401)
        db = mutation ? await pool.connect() : pool
        if (mutation) await db.query('BEGIN')
        const ctx = await context(db,user,kind,roomId,mutation)
        const result = await handler(db,ctx,kind,roomId,req,user)
        if (mutation) await db.query('COMMIT')
        res.json(result)
        if (mutation && result.messageId) {
          void (async()=>{
            const message=(await pool.query('SELECT * FROM fallback_room_messages WHERE id=$1',[result.messageId])).rows[0]
            if(message) await publishRoomCard({chatId:`room:${kind}:${roomId}`,recipientIds:ctx.roomMembers.map(memberUserId),action:'created',message:{...message,[kind==='group'?'group_id':'community_id']:roomId}})
          })().catch(()=>{})
        }
        if (mutation) void publishRealtimeEvent(`questions:${kind}:${roomId}`,{event:'question_changed',questionId:req.params.id || result.id}).catch(()=>{})
      } catch(error) {
        if (mutation && db) await db.query('ROLLBACK').catch(()=>{})
        if (error.status) res.status(error.status).json({error:error.code || error.message}); else next(error)
      } finally { if (mutation) db?.release() }
    }
  }
  app.get(base+'/access',authRequired,route(async(db,ctx,kind,roomId)=>({canCreate:ctx.owner,canRespond:ctx.active,onlyCreator:!(await collectionPublicationAllowed(db,kind,roomId,false)),isOwner:ctx.owner})))
  app.patch(base+'/policy',authRequired,route(async(db,ctx,kind,roomId,req)=>{
    if (!ctx.owner) fail('QUESTION_OWNER_REQUIRED',403)
    if(typeof req.body?.onlyCreator!=='boolean') fail('QUESTION_POLICY_REQUIRED')
    await db.query(`INSERT INTO fallback_collection_publication_policy(room_kind,room_id,only_creator) VALUES($1,$2,$3)
      ON CONFLICT(room_kind,room_id) DO UPDATE SET only_creator=EXCLUDED.only_creator,updated_at=NOW()`,[kind,roomId,req.body.onlyCreator])
    return {onlyCreator:req.body.onlyCreator,isOwner:true,canCreate:true,canRespond:true}
  },true))
  app.post(base,authRequired,route(async(db,ctx,kind,roomId,req)=>{
    if(!ctx.owner) fail('QUESTION_OWNER_REQUIRED',403)
    const prompt=shortText(req.body?.prompt,300),maxLength=answerLimit(req.body?.maxLength)
    await db.query('SELECT pg_advisory_xact_lock(hashtext($1))',[`question-create:${kind}:${roomId}`])
    const count=(await db.query("SELECT count(*)::int AS count FROM fallback_community_questions WHERE room_kind=$1 AND room_id=$2 AND created_at>NOW()-INTERVAL '1 hour'",[kind,roomId])).rows[0]?.count || 0
    if(count>=30) fail('QUESTION_CREATE_LIMIT',429)
    const id=randomUUID(),messageId=randomUUID(),channel=kind==='community'?await ensureDefaultCommunityChannel(db,roomId,ctx.identity.displayUserId):null
    await db.query('INSERT INTO fallback_community_questions(id,room_kind,room_id,channel_id,creator_id,prompt,max_length,message_id) VALUES($1,$2,$3,$4,$5,$6,$7,$8)',[id,kind,roomId,channel?.id || null,ctx.identity.displayUserId,prompt,maxLength,messageId])
    await db.query(`INSERT INTO fallback_room_messages(id,room_kind,room_id,channel_id,sender_id,content,content_type,media_url)
      VALUES($1,$2,$3,$4,$5,$6,'question',NULL)`,[messageId,kind,roomId,channel?.id || null,ctx.identity.displayUserId,JSON.stringify({questionId:id,prompt,maxLength})])
    // Publish only after commit; the next normal conversation refresh carries the card.
    return {id,messageId,prompt,maxLength}
  },true))
  app.get(base+'/:id',authRequired,route(async(db,ctx,kind,roomId,req)=>{
    const q=await question(db,ctx,kind,roomId,req.params.id)
    const offset=Number(req.query.offset || 0)
    if(!Number.isInteger(offset)||offset<0||offset>10000) fail('QUESTION_PAGE_INVALID')
    const answers=(await db.query(`SELECT a.*,COALESCE(v.likes,0)::int AS likes,COALESCE(v.dislikes,0)::int AS dislikes,
      COALESCE(m.value,0) AS my_vote FROM fallback_question_answers a
      LEFT JOIN LATERAL (SELECT count(*) FILTER(WHERE value=1) AS likes,count(*) FILTER(WHERE value=-1) AS dislikes FROM fallback_question_votes WHERE answer_id=a.id) v ON TRUE
      LEFT JOIN fallback_question_votes m ON m.answer_id=a.id AND m.user_id=$2
      WHERE question_id=$1 ORDER BY a.created_at DESC,a.id DESC LIMIT 50 OFFSET $3`,[q.id,ctx.identity.displayUserId,offset])).rows
    const total=(await db.query('SELECT count(*)::int AS total FROM fallback_question_answers WHERE question_id=$1',[q.id])).rows[0].total
    const mine=(await db.query('SELECT id,user_id,content,created_at FROM fallback_question_answers WHERE question_id=$1 AND user_id=$2 LIMIT 1',[q.id,ctx.identity.displayUserId])).rows[0]
    const profiles=await publicProfilesByIds(db,[...answers.map(a=>a.user_id),...(mine?[mine.user_id]:[])])
    return {id:q.id,prompt:q.prompt,maxLength:q.max_length,closed:q.closed,canRespond:ctx.active,total,nextOffset:offset+answers.length<total?offset+answers.length:null,
      myAnswer:mine?{id:mine.id,userId:mine.user_id,content:mine.content,createdAt:mine.created_at}:null,
      answers:answers.map(a=>{const p=profiles.find(p=>[p.id,p.supabase_user_id,p.user_id].includes(a.user_id));return{id:a.id,userId:a.user_id,username:p?.username || '',name:p?.display_name || p?.full_name || 'Usuario',content:a.content,createdAt:a.created_at,likes:a.likes,dislikes:a.dislikes,myVote:a.my_vote}})}
  }))
  app.post(base+'/:id/answers',authRequired,route(async(db,ctx,kind,roomId,req)=>{
    const q=await question(db,ctx,kind,roomId,req.params.id,true)
    if(q.closed) fail('QUESTION_CLOSED',409)
    const content=shortText(req.body?.content,q.max_length)
    const total=(await db.query('SELECT count(*)::int AS total FROM fallback_question_answers WHERE question_id=$1',[q.id])).rows[0].total
    if(total>=10000 && !(await db.query('SELECT id FROM fallback_question_answers WHERE question_id=$1 AND user_id=$2',[q.id,ctx.identity.displayUserId])).rows.length) fail('QUESTION_FULL',409)
    const row=(await db.query(`INSERT INTO fallback_question_answers(id,question_id,user_id,content) VALUES($1,$2,$3,$4)
      ON CONFLICT(question_id,user_id) DO UPDATE SET content=EXCLUDED.content,updated_at=NOW() RETURNING id`,[randomUUID(),q.id,ctx.identity.displayUserId,content])).rows[0]
    return {id:row.id}
  },true))
  app.post(base+'/:id/votes',authRequired,route(async(db,ctx,kind,roomId,req)=>{
    const q=await question(db,ctx,kind,roomId,req.params.id,true),answerId=requireUuid(req.body?.answerId),value=req.body?.value
    if(![-1,0,1].includes(value)) fail('QUESTION_VOTE_INVALID')
    if(!(await db.query('SELECT id FROM fallback_question_answers WHERE id=$1 AND question_id=$2',[answerId,q.id])).rows.length) fail('QUESTION_ANSWER_NOT_FOUND',404)
    if(value===0) await db.query('DELETE FROM fallback_question_votes WHERE answer_id=$1 AND user_id=$2',[answerId,ctx.identity.displayUserId])
    else await db.query(`INSERT INTO fallback_question_votes(answer_id,user_id,value) VALUES($1,$2,$3) ON CONFLICT(answer_id,user_id) DO UPDATE SET value=EXCLUDED.value`,[answerId,ctx.identity.displayUserId,value])
    return {ok:true}
  },true))
}
module.exports={QuestionError,shortText,answerLimit,ensureCommunityQuestionTables,collectionPublicationAllowed,registerCommunityQuestionRoutes}
