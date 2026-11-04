'use strict'

const domain=require('./purpose-domain')
const {readPurpose,writePurpose}=require('./purpose-store')
const {createPurposeContexts}=require('./purpose-access')

function projectGame(state,actorId,now=new Date().toISOString()) {
  const {outcomes,...visible}=state
  return {...visible,status:domain.gamingStatus(state,now),myOutcome:outcomes?.[actorId] || null,serverTime:now}
}
function registerPurposeRoutes(app,dependencies) {
  const {pool,authRequired,enabled=false,publish=async()=>{},persistInvitation=async()=>null,dispatchInvitation=()=>{}}=dependencies
  const base='/v1/experimental/purpose'
  app.get(base+'/capabilities',authRequired,(_req,res)=>res.set('Cache-Control','no-store').json({supported:enabled,schema:1}))
  if (!enabled) return
  const contexts=createPurposeContexts(dependencies)
  const handle=fn=>async(req,res,next)=>{
    res.set('Cache-Control','no-store')
    try {await fn(req,res)} catch(error) {
      if (error.status && String(error.code).startsWith('PURPOSE_')) res.status(error.status).json({error:error.code})
      else next(error)
    }
  }
  const announce=(context,objectKind,objectId)=>{
    const recipients=context.participants?.map(member=>member.id) || [context.actorId]
    for(const recipient of recipients) void Promise.resolve().then(()=>publish(`user:${recipient}`,{event:'purpose_changed',objectKind,objectId}))
      .catch(()=>console.warn('[purpose] change publication failed',{objectKind}))
  }
  async function socialPayload(req,postId) {
    const context=await contexts.social(pool,req,postId), state=await readPurpose(pool,'social',postId)
    const [references,eligible]=await Promise.all([
      state?.contributionIds?.length ? contexts.contributions(pool,context,state.contributionIds) : [],
      context.isAuthor ? contexts.contributions(pool,context) : [],
    ])
    return {outcome:state,postId,postText:String(context.post.content || '').slice(0,500),canEdit:context.isAuthor,eligible,
      contributions:(state?.contributionIds || []).map(id=>references.find(row=>row.id===id) || {id,available:false})}
  }
  app.get(base+'/posts/:postId/outcome',authRequired,handle(async(req,res)=>res.json(await socialPayload(req,domain.id(req.params.postId)))))
  app.post(base+'/posts/:postId/outcome',authRequired,handle(async(req,res)=>{
    const postId=domain.id(req.params.postId), command=domain.socialCommand(req.body)
    let authorized
    await writePurpose(pool,{kind:'social',key:postId,targetId:postId,command,apply:domain.changeSocial,
      authorize:async db=>{
        const context=await contexts.social(db,req,postId)
        const references=command.contributionIds?.length ? await contexts.contributions(db,context,command.contributionIds) : []
        authorized=context
        return {...context,contributionIds:new Set(references.map(row=>row.id))}
      }})
    announce(authorized,'social',postId)
    res.json(await socialPayload(req,postId))
  }))
  async function communityPayload(req,communityId) {
    const context=await contexts.community(pool,req,communityId), key=`${communityId}:${context.actorId}`
    const [targets,journey,guide]=await Promise.all([contexts.communityTargets(pool,context),readPurpose(pool,'community_start',key),readPurpose(pool,'guide',key)])
    return {communityId,targets,journey,guide,serverTime:new Date().toISOString()}
  }
  app.get(base+'/communities/:communityId',authRequired,handle(async(req,res)=>res.json(await communityPayload(req,domain.id(req.params.communityId)))))
  for (const kind of ['guide','community_start']) app.post(base+`/communities/:communityId/${kind}`,authRequired,handle(async(req,res)=>{
    const communityId=domain.id(req.params.communityId), command=kind==='guide' ? domain.guideCommand(req.body) : domain.startCommand(req.body)
    const actor=await contexts.actor(pool,req), key=`${communityId}:${actor.actorId}`
    await writePurpose(pool,{kind,key,targetId:communityId,command,apply:kind==='guide' ? domain.changeGuide : domain.changeStart,
      authorize:async db=>{
        const context=await contexts.community(db,req,communityId)
        if (context.actorId!==actor.actorId) throw domain.fail('PURPOSE_ACCESS_DENIED',403)
        const targets=kind==='community_start' ? await contexts.communityTargets(db,context) : []
        return {...context,targets:new Set(targets.map(row=>`${row.kind}:${row.id}`))}
      }})
    announce(actor,'community',communityId)
    res.json(await communityPayload(req,communityId))
  }))
  app.get(base+'/gaming',authRequired,handle(async(req,res)=>{
    const catalog=await contexts.gameList(pool,req)
    res.json({plans:catalog.plans.map(row=>projectGame(row,catalog.actorId)),recentLimit:20})
  }))
  app.get(base+'/gaming/with/:playerId',authRequired,handle(async(req,res)=>{
    const context=await contexts.game(pool,req,domain.id(req.params.playerId))
    res.json({participants:context.participants,games:context.games})
  }))
  async function gamePayload(req,planId) {
    const state=await readPurpose(pool,'gaming',planId)
    if (!state) throw domain.fail('PURPOSE_NOT_FOUND',404)
    const actor=await contexts.actor(pool,req)
    if (!state.participants.some(member=>member.id===actor.actorId)) throw domain.fail('PURPOSE_NOT_FOUND',404)
    const other=state.participants.find(member=>member.id!==actor.actorId)
    const context=await contexts.game(pool,req,other.id)
    return {plan:projectGame({...state,participants:context.participants,gameTitle:context.games.find(game=>game.id===state.gameId)?.title || 'Juego'},context.actorId),games:context.games}
  }
  app.get(base+'/gaming/:planId',authRequired,handle(async(req,res)=>res.json(await gamePayload(req,domain.id(req.params.planId)))))
  app.post(base+'/gaming/with/:playerId/:planId',authRequired,handle(async(req,res)=>{
    const playerId=domain.id(req.params.playerId), planId=domain.id(req.params.planId), command=domain.gamingCommand(req.body)
    if (command.action!=='propose') throw domain.fail('PURPOSE_INVALID_COMMAND')
    let authorized
    const result=await writePurpose(pool,{kind:'gaming',key:planId,targetId:playerId,command,
      apply:(previous,cmd,context,now)=>({...domain.changeGaming(previous,cmd,context,now),id:planId}),
      afterApply:persistInvitation,
      authorize:async db=>{
        const context=await contexts.game(db,req,playerId); authorized=context
        await db.query('SELECT pg_advisory_xact_lock(hashtextextended($1,0))',[`purpose:gaming-limit:${context.actorId}`])
        if ((await db.query(`SELECT count(*)::int AS total FROM experimental_purpose_objects WHERE kind='gaming' AND author_id=$1
          AND state->>'cancelledAt' IS NULL AND (state->>'startsAt')::timestamptz>NOW()`,[context.actorId])).rows[0].total>=10 && !await readPurpose(db,'gaming',planId)) throw domain.fail('PURPOSE_PLAN_LIMIT',409)
        return context
      }})
    if(result.effect) dispatchInvitation(result.effect)
    announce(authorized,'gaming',planId)
    res.json(await gamePayload(req,planId))
  }))
  app.post(base+'/gaming/:planId/actions',authRequired,handle(async(req,res)=>{
    const planId=domain.id(req.params.planId), command=domain.gamingCommand(req.body)
    if (command.action==='propose') throw domain.fail('PURPOSE_INVALID_COMMAND')
    let authorized
    const result=await writePurpose(pool,{kind:'gaming',key:planId,targetId:planId,command,apply:domain.changeGaming,
      afterApply:command.action==='replan' ? persistInvitation : async()=>null,
      authorize:async db=>{
        const state=await readPurpose(db,'gaming',planId),actor=await contexts.actor(db,req)
        if (!state || !state.participants.some(member=>member.id===actor.actorId)) throw domain.fail('PURPOSE_NOT_FOUND',404)
        authorized=await contexts.game(db,req,state.participants.find(member=>member.id!==actor.actorId).id)
        return authorized
      }})
    if(result.effect) dispatchInvitation(result.effect)
    announce(authorized,'gaming',planId)
    res.json(await gamePayload(req,planId))
  }))
}
module.exports={registerPurposeRoutes,projectGame}
