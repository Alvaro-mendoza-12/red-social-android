'use strict'
const {randomUUID}=require('node:crypto')
const {appAdminAccess}=require('./admin-moderation-routes')
const {hasAdminCapability,ADMIN_CAPABILITY}=require('./admin-capabilities')
function promotionInput(body={}) {
  const title=String(body.title || '').trim(),description=String(body.description || '').trim(),cta=String(body.cta || 'Abrir').trim(),destination=String(body.destination || '/app/messages').trim(),imageUrl=String(body.imageUrl || '').trim()
  if(!title || title.length>100 || description.length>500 || !cta || cta.length>30) throw Object.assign(new Error('PROMOTION_TEXT_INVALID'),{status:400})
  if(!/^\/app(?:\/[a-zA-Z0-9_/?=&.%~-]*)?$/.test(destination) && !['/download','/features','https://play.google.com/store/apps/details?id=conectate.com'].includes(destination)) throw Object.assign(new Error('PROMOTION_DESTINATION_INVALID'),{status:400})
  return {title,description,cta,destination,imageUrl}
}
async function ensureSocialPromotionTables(pool) {
  await pool.query(`CREATE TABLE IF NOT EXISTS fallback_social_promotions (
    id UUID PRIMARY KEY, title TEXT NOT NULL, description TEXT NOT NULL DEFAULT '', image_url TEXT NOT NULL DEFAULT '',
    destination TEXT NOT NULL, cta TEXT NOT NULL, active BOOLEAN NOT NULL DEFAULT TRUE,
    created_by TEXT,created_at TIMESTAMPTZ NOT NULL DEFAULT NOW());`)
}
function registerSocialPromotionRoutes(app,{pool,authRequired,getCurrentUser,userIdentity,mayUseImage}) {
  async function run(req,res,next,write=false) {
    try {
      const user=await getCurrentUser(pool,req)
      if(!user) return res.status(401).json({error:'AUTH_REQUIRED'})
      const identity=userIdentity(user),access=await appAdminAccess(pool,identity),canManage=hasAdminCapability(access,ADMIN_CAPABILITY.MODERATION_MANAGE)
      if(write) {
        if(!canManage) return res.status(403).json({error:'ADMIN_CAPABILITY_REQUIRED'})
        if(req.method==='PATCH') {
          if(typeof req.body?.active!=='boolean' || !/^[a-f0-9-]{36}$/i.test(req.params.id)) return res.status(400).json({error:'PROMOTION_INVALID'})
          const result=await pool.query('UPDATE fallback_social_promotions SET active=$2 WHERE id=$1 RETURNING id',[req.params.id,req.body.active])
          return res.status(result.rows.length?200:404).json({ok:result.rows.length>0})
        }
        const p=promotionInput(req.body)
        if(p.imageUrl && !(await mayUseImage(user,identity,p.imageUrl))) return res.status(403).json({error:'PROMOTION_IMAGE_FORBIDDEN'})
        const id=randomUUID()
        await pool.query('INSERT INTO fallback_social_promotions(id,title,description,image_url,destination,cta,created_by) VALUES($1,$2,$3,$4,$5,$6,$7)',[id,p.title,p.description,p.imageUrl,p.destination,p.cta,identity.displayUserId])
        return res.status(201).json({id})
      }
      const {rows}=await pool.query(`SELECT id,title,description,image_url AS "imageUrl",destination,cta,active FROM fallback_social_promotions ${canManage && req.query.manage==='1'?'':'WHERE active=TRUE'} ORDER BY created_at DESC LIMIT 20`)
      res.json({canManage,items:rows.length?rows:[{id:'novvin-welcome',title:'Conecta con tu comunidad en Novvin',description:'Mensajes, videos y salas de voz en un mismo lugar.',imageUrl:'',destination:'/app/messages',cta:'Abrir mensajes',active:true}]})
    }catch(error){if(error.status) res.status(error.status).json({error:error.message});else next(error)}
  }
  app.get('/v1/data/social/promotions',authRequired,(req,res,next)=>run(req,res,next))
  app.post('/v1/data/social/promotions',authRequired,(req,res,next)=>run(req,res,next,true))
  app.patch('/v1/data/social/promotions/:id',authRequired,(req,res,next)=>run(req,res,next,true))
}
module.exports={promotionInput,ensureSocialPromotionTables,registerSocialPromotionRoutes}
