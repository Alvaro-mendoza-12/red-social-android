'use strict'
const {randomUUID}=require('node:crypto')
const {appAdminAccess}=require('./admin-moderation-routes')
const {hasAdminCapability,ADMIN_CAPABILITY}=require('./admin-capabilities')
const invalid=code=>{throw Object.assign(new Error(code),{status:400})}
function promotionDestination(value) {
  const destination=String(value || '/app/messages').trim()
  if(/^\/app(?:\/[a-zA-Z0-9_/?=&.%~-]*)?$/.test(destination) || ['/download','/features'].includes(destination))return destination
  try { const url=new URL(destination);if(url.protocol==='https:'&&!url.username&&!url.password&&!url.port&&url.hostname.includes('.')&&!/^(localhost|127\.|0\.|10\.|192\.168\.|169\.254\.)/i.test(url.hostname)&&destination.length<=2000)return url.href }catch{}
  invalid('PROMOTION_DESTINATION_INVALID')
}
function promotionInput(body={}) {
  const title=String(body.title || '').trim(),description=String(body.description || '').trim(),cta=String(body.cta || 'Abrir').trim(),imageUrl=String(body.imageUrl || '').trim()
  if(!title || title.length>100 || description.length>500 || !cta || cta.length>30)invalid('PROMOTION_TEXT_INVALID')
  const destination=promotionDestination(body.destination)
  let cards=[]
  if(body.cards!==undefined) {
    if(!Array.isArray(body.cards)||body.cards.length<1||body.cards.length>10)invalid('PROMOTION_CARDS_INVALID')
    cards=body.cards.map(card=>{
      if(!card||typeof card!=='object'||Array.isArray(card))invalid('PROMOTION_CARDS_INVALID')
      const parsed=promotionInput({...card,cards:undefined})
      if(!parsed.imageUrl)invalid('PROMOTION_CARD_IMAGE_REQUIRED')
      const {cards:_,...result}=parsed;return result
    })
  }
  return {title,description,cta,destination,imageUrl,cards}
}
async function ensureSocialPromotionTables(pool) {
  await pool.query(`CREATE TABLE IF NOT EXISTS fallback_social_promotions (
    id UUID PRIMARY KEY, title TEXT NOT NULL, description TEXT NOT NULL DEFAULT '', image_url TEXT NOT NULL DEFAULT '',
    destination TEXT NOT NULL, cta TEXT NOT NULL, active BOOLEAN NOT NULL DEFAULT TRUE,
    created_by TEXT,created_at TIMESTAMPTZ NOT NULL DEFAULT NOW());
    ALTER TABLE fallback_social_promotions ADD COLUMN IF NOT EXISTS cards JSONB NOT NULL DEFAULT '[]'::jsonb;
    ALTER TABLE fallback_social_promotions ADD COLUMN IF NOT EXISTS media_urls JSONB NOT NULL DEFAULT '[]'::jsonb;`)
}
function registerSocialPromotionRoutes(app,{pool,authRequired,getCurrentUser,userIdentity,mayUseImage}) {
  async function run(req,res,next,write=false) {
    try {
      const user=await getCurrentUser(pool,req)
      if(!user)return res.status(401).json({error:'AUTH_REQUIRED'})
      const identity=userIdentity(user),access=await appAdminAccess(pool,identity),canManage=hasAdminCapability(access,ADMIN_CAPABILITY.MODERATION_MANAGE)
      if(write) {
        if(!canManage)return res.status(403).json({error:'ADMIN_CAPABILITY_REQUIRED'})
        const patch=req.method==='PATCH', deleting=req.method==='DELETE', id=patch||deleting?req.params.id:randomUUID()
        if((patch||deleting)&&!/^[a-f0-9-]{36}$/i.test(id))return res.status(400).json({error:'PROMOTION_INVALID'})
        if(deleting) {
          const result=await pool.query('DELETE FROM fallback_social_promotions WHERE id=$1 RETURNING id',[id])
          return res.status(result.rows.length?200:404).json(result.rows.length?{ok:true,id}:{error:'PROMOTION_NOT_FOUND'})
        }
        if(patch&&Object.keys(req.body || {}).length===1&&typeof req.body.active==='boolean') {
          const result=await pool.query('UPDATE fallback_social_promotions SET active=$2 WHERE id=$1 RETURNING id',[id,req.body.active])
          return res.status(result.rows.length?200:404).json({ok:result.rows.length>0})
        }
        const p=promotionInput(req.body)
        for(const url of new Set([p.imageUrl,...p.cards.map(card=>card.imageUrl)].filter(Boolean)))if(!(await mayUseImage(user,identity,url)))return res.status(403).json({error:'PROMOTION_IMAGE_FORBIDDEN'})
        const media=JSON.stringify(p.cards.map(card=>card.imageUrl)),cards=JSON.stringify(p.cards)
        if(patch) {
          const result=await pool.query('UPDATE fallback_social_promotions SET title=$2,description=$3,image_url=$4,destination=$5,cta=$6,cards=$7::jsonb,media_urls=$8::jsonb WHERE id=$1 RETURNING id',[id,p.title,p.description,p.imageUrl,p.destination,p.cta,cards,media])
          return res.status(result.rows.length?200:404).json({ok:result.rows.length>0,id})
        }
        await pool.query('INSERT INTO fallback_social_promotions(id,title,description,image_url,destination,cta,created_by,cards,media_urls) VALUES($1,$2,$3,$4,$5,$6,$7,$8::jsonb,$9::jsonb)',[id,p.title,p.description,p.imageUrl,p.destination,p.cta,identity.displayUserId,cards,media])
        return res.status(201).json({id})
      }
      const {rows}=await pool.query(`SELECT id,title,description,image_url AS "imageUrl",destination,cta,active,cards FROM fallback_social_promotions ${canManage&&req.query.manage==='1'?'':'WHERE active=TRUE'} ORDER BY created_at DESC LIMIT 100`)
      res.json({canManage,items:rows.length?rows:[{id:'novvin-welcome',title:'Descubre todo lo que puedes hacer en Novvin',description:'Conversa, descubre videos y comparte con tu comunidad.',imageUrl:'',destination:'/app/messages',cta:'Explorar',active:true,cards:[
        {title:'Conversa a tu manera',description:'Chats privados y grupos.',imageUrl:'https://red-social-android.vercel.app/images/promotion-messages.svg',destination:'/app/messages',cta:'Abrir mensajes'},
        {title:'Descubre nuevos videos',description:'Encuentra creadores y comparte.',imageUrl:'https://red-social-android.vercel.app/images/promotion-videos.svg',destination:'/app/videos',cta:'Ver videos'},
        {title:'Encuentra tu comunidad',description:'Ideas, preguntas y novedades.',imageUrl:'https://red-social-android.vercel.app/images/promotion-social.svg',destination:'/app/social',cta:'Explorar Social'}]}]})
    }catch(error){if(error.status)res.status(error.status).json({error:error.message});else next(error)}
  }
  app.get('/v1/data/social/promotions',authRequired,(req,res,next)=>run(req,res,next))
  app.post('/v1/data/social/promotions',authRequired,(req,res,next)=>run(req,res,next,true))
  app.patch('/v1/data/social/promotions/:id',authRequired,(req,res,next)=>run(req,res,next,true))
  app.delete('/v1/data/social/promotions/:id',authRequired,(req,res,next)=>run(req,res,next,true))
}
module.exports={promotionInput,promotionDestination,ensureSocialPromotionTables,registerSocialPromotionRoutes}
