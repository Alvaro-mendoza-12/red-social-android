'use strict'
const test=require('node:test'),assert=require('node:assert/strict'),sharp=require('sharp')
const { readPublicShareImage }=require('../src/public-share-image')
const origin='https://novvinapp.com',path='/storage/v1/object/public/posts/image.png'
test('image fetching rejects foreign origins, private paths and signed URLs before network access',async()=>{
 const original=global.fetch;let requests=0;global.fetch=async()=>{requests++;throw Error('unexpected request')}
 try{for(const url of ['http://127.0.0.1/admin','https://attacker.test/image.png','https://user:password@attacker.test'+path,origin+'/storage/v1/object/private/chat.png',origin+path+'?access_token=secret',origin+path+'?X-Amz-Signature=secret','data:image/png;base64,AAAA'])assert.equal(await readPublicShareImage(url,origin),null);assert.equal(requests,0)}finally{global.fetch=original}
})
test('public raster is re-encoded as JPEG and fetched without ambient credentials',async()=>{
 const original=global.fetch;const png=await sharp({create:{width:12,height:12,channels:3,background:'#2168b5'}}).png().toBuffer();let options
 global.fetch=async(_url,input)=>{options=input;return new Response(png,{headers:{'content-type':'image/png'}})}
 try{const bytes=await readPublicShareImage(origin+path,origin);assert.equal((await sharp(bytes).metadata()).format,'jpeg');assert.equal(options.redirect,'error');assert.equal(options.headers,undefined);assert.equal(options.signal.aborted,false)}finally{global.fetch=original}
})
test('legacy storage locators fetch the owned object without contacting the legacy host',async()=>{
 const original=global.fetch;let destination='';const png=await sharp({create:{width:8,height:8,channels:3,background:'#2168b5'}}).png().toBuffer()
 global.fetch=async url=>{destination=String(url);return new Response(png,{headers:{'content-type':'image/png'}})}
 try{assert(await readPublicShareImage('https://legacy.invalid'+path,origin));assert.equal(destination,origin+path)}finally{global.fetch=original}
})
test('SVG and oversized announced responses are rejected without reading their bodies',async()=>{
 const original=global.fetch
 try{for(const headers of [{'content-type':'image/svg+xml'},{'content-type':'image/png','content-length':'2000001'}]){global.fetch=async()=>new Response('not a raster',{headers});assert.equal(await readPublicShareImage(origin+path,origin),null)}}finally{global.fetch=original}
})
test('an unannounced oversized stream is bounded and cancelled',async()=>{
 const original=global.fetch;let sent=0,cancelled=false
 global.fetch=async()=>new Response(new ReadableStream({pull(controller){sent++;controller.enqueue(new Uint8Array(600000))},cancel(){cancelled=true}}),{headers:{'content-type':'image/jpeg'}})
 try{assert.equal(await readPublicShareImage(origin+path,origin),null);assert(sent<=5);assert(cancelled)}finally{global.fetch=original}
})
