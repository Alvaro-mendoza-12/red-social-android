'use strict'
const test = require('node:test'), assert = require('node:assert/strict'), express = require('express'), sharp = require('sharp')
const { contentShareHtml, contentShareCard, registerPublicContentShareRoutes } = require('../src/public-content-share')
const { publicShareProfileAllowed } = require('../src/public-share-policy')
const id = 'ce9b85b6-277b-47ac-bbb1-8d7b8510e6e0'
const content = { id, user_id: 'author', content: '<script>Texto público</script> & una foto', email: 'secret@example.invalid' }
const profile = { id: 'author', display_name: 'Nombre <b>público</b>', email: 'private@example.invalid' }
test('metadata identifies the shared post and escapes content without exposing private fields',()=>{
 const html=contentShareHtml('posts',content,profile)
 assert(html.includes('og:url" content="https://novvinapp.com/app/feed/post/'+id+'"'))
 assert(html.includes('/v1/public/posts/'+id+'/share.jpg?v=1'))
 assert(html.includes('&lt;script&gt;Texto público&lt;/script&gt;'))
 assert(!html.includes('<script>'));assert(!html.includes('secret@example.invalid'));assert(!html.includes('private@example.invalid'))
 assert(!html.includes('og:url" content="https://novvinapp.com/"'));assert(!html.includes('/novvin-share.png'))
})
test('video metadata identifies its title and exact video destination',()=>{
 const html=contentShareHtml('videos',{...content,title:'Un video propio',description:'Descripción del video',content:undefined},profile)
 assert(html.includes('og:title" content="Un video propio"'));assert(html.includes('/app/reels/'+id));assert(html.includes('video.other'))
})
test('post cards are 1200x630 JPEGs and use only authorized raster input',async()=>{
 const image=await sharp({create:{width:20,height:20,channels:3,background:'#cb6633'}}).png().toBuffer()
 let requested=''
 const card=await contentShareCard('posts',{...content,media_url:'https://novvinapp.com/storage/v1/object/public/posts/photo.jpg'},profile,'https://novvinapp.com',async url=>{requested=url;return image})
 assert(requested.endsWith('/posts/photo.jpg'))
 const metadata=await sharp(card).metadata();assert.equal(metadata.width,1200);assert.equal(metadata.height,630);assert.equal(metadata.format,'jpeg')
 const noPhoto=await contentShareCard('posts',{...content,content:'こんにちは مرحباً 👩🏽‍💻 '.repeat(1000)},profile,'https://novvinapp.com')
 assert.equal((await sharp(noPhoto).metadata()).width,1200)
})
test('creator safety policy checks all canonical and local identity aliases',async()=>{
 let candidates=[]
 const pool={async query(sql,params){if(sql.includes('FROM fallback_users'))return {rows:[{id:'local',supabase_user_id:'canonical'}]};candidates=params[1];return {rows:[{id:'canonical'}]}}}
 assert.equal(await publicShareProfileAllowed(pool,{id:'canonical'},'local'),false)
 assert(candidates.includes('local')&&candidates.includes('canonical'))
})
async function serve(overrides, run){
 const app=express();registerPublicContentShareRoutes(app,{pool:{},publicBaseUrl:'https://novvinapp.com',loadContent:async()=>content,loadProfile:async()=>profile,profileAllowed:async()=>true,...overrides})
 app.use((error,req,res,next)=>res.status(503).send('Servicio no disponible.'))
 const server=app.listen(0,'127.0.0.1');await new Promise(resolve=>server.once('listening',resolve))
 try{await run('http://127.0.0.1:'+server.address().port)}finally{server.closeAllConnections();await new Promise(resolve=>server.close(resolve))}
}
for(const scenario of [
 ['private or missing post',{loadContent:async()=>null}],
 ['hidden creator',{loadProfile:async()=>null}],
 ['private creator',{loadProfile:async()=>({...profile,is_private:true})}],
 ['suspended or deleted creator',{profileAllowed:async()=>false}],
])test('HTML and image reveal no object when '+scenario[0],async()=>serve(scenario[1],async base=>{
 for(const tail of ['share','share.jpg']){const r=await fetch(base+'/v1/public/posts/'+id+'/'+tail);assert.equal(r.status,404);assert.equal(await r.text(),'Contenido no disponible.');assert.equal(r.headers.get('cache-control'),'no-store')}
}))
test('public routes serve object-specific HTML without requiring an account',async()=>serve({},async base=>{
 const r=await fetch(base+'/v1/public/posts/'+id+'/share');assert.equal(r.status,200);assert.match(r.headers.get('content-type'),/text\/html/);assert.equal(r.headers.get('cache-control'),'no-store');assert.match(r.headers.get('x-robots-tag'),/noindex/)
 assert((await r.text()).includes('Texto público'))
}))
test('invalid object IDs do not reach SQL or the content loader',async()=>{
 let reads=0;await serve({loadContent:async()=>{reads++;return content}},async base=>{
  assert.equal((await fetch(base+'/v1/public/posts/not-an-id/share')).status,404)
  assert.equal((await fetch(base+'/v1/public/videos/not-an-id/share.jpg')).status,404)
 });assert.equal(reads,0)
})
test('a failed safety lookup never publishes a partial preview',async()=>serve({profileAllowed:async()=>{throw Error('database unavailable')}},async base=>{
 const r=await fetch(base+'/v1/public/posts/'+id+'/share');assert.equal(r.status,503);assert.equal(await r.text(),'Servicio no disponible.')
}))
