import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import { createHash } from 'node:crypto'
import path from 'node:path'
import { publicRoutes, site } from '../scripts/public-seo.mjs'
const root=path.resolve(import.meta.dirname,'..')
const read=file=>fs.readFileSync(path.join(root,'dist',file),'utf8')
const graph=html=>JSON.parse(/<script type="application\/ld\+json">(.*?)<\/script>/.exec(html)[1])['@graph']
const config=JSON.parse(fs.readFileSync(path.join(root,'vercel.json'),'utf8'))

test('home is readable without JavaScript and provides real access links',()=>{
 const html=read('index.html')
 assert.match(html,/<h1>Novvin/)
 for(const word of ['N-O-V-V-I-N','Novvyn','NoWin','Noving','Nowing'])assert.ok(html.includes(word))
 assert.match(html,/href="\/login"/);assert.match(html,/href="\/register"/)
 assert.doesNotMatch(html,/name="keywords"|accessToken|access_token/)
})
test('the public home loads no application scripts or preload bundles',()=>{
 const html=read('index.html')
 assert.doesNotMatch(html,/<script[^>]*(?:src=|type="module")|rel="modulepreload"/)
 const stylesheet=/<link rel="stylesheet" href="\/([^"]+)"/.exec(html)[1]
 assert.ok(fs.statSync(path.join(root,'dist',stylesheet)).size<15000)
 assert.doesNotMatch(read(stylesheet),/@import|fonts\.googleapis/)
})
test('private shell is noindex, contains no public page and retains its application entry',()=>{
 const html=read('app-shell.html')
 assert.match(html,/<div id="root"><\/div>/);assert.match(html,/type="module"/)
 assert.match(html,/name="robots" content="noindex, follow"/)
 assert.doesNotMatch(html,/<h1>Novvin|application\/ld\+json/)
})
test('sitemap advertises only the canonical public pages',()=>{
 const sitemap=read('sitemap.xml'),urls=[...sitemap.matchAll(/<loc>(.*?)<\/loc>/g)].map(item=>item[1])
 assert.deepEqual(urls,publicRoutes.map(([route])=>site+(route==='/'?'/':route)))
 assert.doesNotMatch(sitemap,/\/app\/|\/login|\/profile/)
 assert.match(read('robots.txt'),/Allow: \//)
 assert.match(read('robots.txt'),/Sitemap: https:\/\/red-social-android\.vercel\.app\/sitemap.xml/)
})
for(const [route,file,label] of publicRoutes){
 test(route+' has its own canonical, metadata and one heading',()=>{
  const html=read(file),url=site+(route==='/'?'/':route)
  assert.equal((html.match(/<h1>/g)||[]).length,1)
  assert.ok(html.includes('rel="canonical" href="'+url+'"'))
  assert.ok(html.includes('property="og:url" content="'+url+'"'))
  assert.match(html,/name="robots" content="index, follow, max-image-preview:large"/)
  assert.doesNotMatch(html,/type="module"|rel="modulepreload"|accessToken|access_token/)
  assert.ok(config.rewrites.some(item=>item.source===route&&item.destination==='/'+file))
 })
 test(route+' describes the actual page and brand with allowed structured data',()=>{
  const html=read(file),nodes=graph(html),url=site+(route==='/'?'/':route)
  const web=nodes.find(item=>item['@type']==='WebSite'),org=nodes.find(item=>item['@type']==='Organization'),page=nodes.find(item=>item['@type']==='WebPage')
  assert.equal(web.name,'Novvin');assert.equal(web.url,site+'/')
  assert.deepEqual(web.alternateName,['Novvin Web']);assert.equal(org.logo.width,512)
  assert.equal(page.url,url);assert.equal(page['@id'],url+'#webpage')
  assert.ok(html.includes('<title>'+page.name+'</title>'))
  assert.doesNotMatch(JSON.stringify(nodes),/aggregateRating|SearchAction|sameAs/)
  if(route!=='/'){const breadcrumb=nodes.find(item=>item['@type']==='BreadcrumbList');assert.equal(breadcrumb.itemListElement.at(-1).name,label);assert.equal(breadcrumb.itemListElement.at(-1).item,url)}
  const raw=/<script type="application\/ld\+json">(.*?)<\/script>/.exec(html)[1],hash=createHash('sha256').update(raw).digest('base64')
  for(const file of ['vercel.json','../vercel.json']){
   const deployment=JSON.parse(fs.readFileSync(path.join(root,file),'utf8')),csp=deployment.headers[0].headers.find(header=>header.key==='Content-Security-Policy').value
   assert.ok(csp.includes("'sha256-"+hash+"'"));assert.doesNotMatch(csp,/script-src[^;]*'unsafe-inline'/)
  }
 })
}
test('each public title and description is unique',()=>{
 const pages=publicRoutes.map(([,file])=>graph(read(file)).find(item=>item['@type']==='WebPage'))
 assert.equal(new Set(pages.map(page=>page.name)).size,publicRoutes.length)
 assert.equal(new Set(pages.map(page=>page.description)).size,publicRoutes.length)
})
test('unknown paths do not rewrite into successful application documents',()=>{
 assert.ok(!config.rewrites.some(item=>item.source==='/(.*)'))
 for(const source of ['/app/:path*','/explore/:path*','/u/:path*','/profile/:path*','/chat/:path*'])assert.ok(config.rewrites.some(item=>item.source===source&&item.destination==='/app-shell.html'))
 const html=read('404.html');assert.match(html,/No encontramos esta página/);assert.match(html,/name="robots" content="noindex/)
 assert.doesNotMatch(html,/rel="canonical"|type="module"/)
})
test('HTML duplicates permanently redirect to the canonical public URL',()=>{
 for(const [route,file] of publicRoutes)assert.ok(config.redirects.some(item=>item.source==='/'+file&&item.destination===route&&item.permanent))
})
test('brand verification, share image and favicon are available',()=>{
 const html=read('index.html');assert.match(html,/name="google-site-verification" content="[A-Za-z0-9_-]+"/)
 assert.match(html,/twitter:card" content="summary_large_image"/)
 for(const [file,width,height] of [['novvin-share.png',1200,630],['novvin-logo.png',512,512],...[48,96,180,192,512].map(size=>['icons/novvin-'+size+'.png',size,size])]){
  const png=fs.readFileSync(path.join(root,'dist',file));assert.equal(png.toString('hex',0,8),'89504e470d0a1a0a')
  assert.equal(png.readUInt32BE(16),width);assert.equal(png.readUInt32BE(20),height)
 }
 const favicon=fs.readFileSync(path.join(root,'dist/favicon.ico'));assert.equal(favicon.readUInt16LE(2),1)
})
test('manifest advertises existing icons and implemented routes only',()=>{
 const manifest=JSON.parse(read('manifest.json'))
 assert.equal(manifest.name,'Novvin Web');assert.equal(manifest.start_url,'/app/messages')
 assert.equal(manifest.share_target,undefined);assert.equal(manifest.file_handlers,undefined)
 assert.equal(manifest.protocol_handlers,undefined);assert.equal(manifest.scope_extensions,undefined)
 for(const icon of manifest.icons)assert.ok(fs.statSync(path.join(root,'dist',icon.src)).isFile())
 for(const shortcut of manifest.shortcuts)assert.ok(['/app/messages','/app/videos','/app/voice','/app/social'].includes(shortcut.url))
})
