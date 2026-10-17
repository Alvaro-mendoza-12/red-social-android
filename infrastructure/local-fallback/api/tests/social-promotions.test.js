'use strict'
const test=require('node:test'),assert=require('node:assert/strict'),express=require('express'),{randomUUID}=require('node:crypto')
const {registerSocialPromotionRoutes,promotionInput}=require('../src/social-promotions')
const {sourceUrls}=require('../src/storage-content-read')
test('carousel image bindings include every image without interpreting arbitrary JSON links',()=>{
 assert.deepEqual(sourceUrls('fallback_social_promotions',{image_url:'cover',media_urls:['one','two'],cards:[{imageUrl:'untrusted'}]}),['cover','one','two'])
 assert.throws(()=>promotionInput({title:'Ad',cards:Array(11).fill({})}))
})
test('promotion creation, update, pause and authorization validate every image',async()=>{
 const owner={id:randomUUID()},member={id:randomUUID()},rows=[],checked=[]
 const pool={query:async(sql,args=[])=>{
   if(sql.includes('SELECT u.email,'))return {rows:[{is_admin:args[0].includes(owner.id),admin_access_level:args[0].includes(owner.id)?'full':'none'}]}
   if(sql.startsWith('INSERT INTO fallback_social_promotions')){rows.push({id:args[0],title:args[1],description:args[2],imageUrl:args[3],destination:args[4],cta:args[5],active:true,cards:JSON.parse(args[7])});return{rows:[]}}
   if(sql.startsWith('UPDATE fallback_social_promotions SET active')){const row=rows.find(r=>r.id===args[0]);if(row)row.active=args[1];return{rows:row?[{id:row.id}]:[]}}
   if(sql.startsWith('UPDATE fallback_social_promotions SET title')){const row=rows.find(r=>r.id===args[0]);if(row)Object.assign(row,{title:args[1],cards:JSON.parse(args[6])});return{rows:row?[{id:row.id}]:[]}}
   if(sql.startsWith('SELECT id,title'))return {rows:sql.includes('WHERE active=TRUE')?rows.filter(r=>r.active):rows}
   throw Error('Unexpected test query')
 }}
 const app=express();app.use(express.json());registerSocialPromotionRoutes(app,{pool,authRequired:(req,res,next)=>next(),getCurrentUser:async(_db,req)=>req.headers['x-actor']==='owner'?owner:member,userIdentity:user=>({ids:new Set([user.id]),displayUserId:user.id}),mayUseImage:async(_user,_identity,url)=>{checked.push(url);return url!=='foreign'}})
 const server=await new Promise(resolve=>{const value=app.listen(0,'127.0.0.1',()=>resolve(value))})
 const call=(method,path='',body,actor='owner')=>fetch(`http://127.0.0.1:${server.address().port}/v1/data/social/promotions${path}`,{method,headers:{'Content-Type':'application/json','x-actor':actor},body:body?JSON.stringify(body):undefined})
 const card={title:'Card',description:'Brief',destination:'https://shop.example/product',cta:'Comprar',imageUrl:'owned1'},body={title:'Carousel',cards:[card,{...card,imageUrl:'owned2'}]}
 try{
   assert.equal((await call('POST','',body,'member')).status,403)
   assert.equal((await call('POST','',{...body,cards:[card,{...card,imageUrl:'foreign'}]})).status,403);assert.equal(rows.length,0)
   const created=await call('POST','',body);assert.equal(created.status,201);const{id}=await created.json();assert.ok(checked.includes('owned2'))
   assert.equal((await(await call('GET')).json()).items[0].cards.length,2)
   assert.equal((await call('PATCH','/'+id,{...body,title:'Updated'})).status,200);assert.equal(rows[0].title,'Updated')
   assert.equal((await call('PATCH','/'+id,{active:false})).status,200)
   assert.equal((await(await call('GET','?manage=1')).json()).items[0].active,false)
   assert.equal((await call('PATCH','/'+id,{active:true},'member')).status,403)
 }finally{await new Promise(resolve=>server.close(resolve))}
})
