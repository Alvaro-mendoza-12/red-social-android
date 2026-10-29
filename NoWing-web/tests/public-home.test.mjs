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

test('feature illustrations have visible alternatives and discoverable public image URLs',()=>{
 const html=read('funciones.html'),page=graph(html).find(item=>item['@type']==='WebPage')
 const images=[...['chats','grupos','comunidades','videos','social','momentos'],...['chat-android-demo','chat-web-demo','perfil-android-demo']].map(name=>'/images/novvin-'+name+'.png')
 assert.equal(page.image.length,images.length)
 for(const src of images){
  assert.ok(html.includes('src="'+src+'"'))
  assert.ok(fs.existsSync(path.join(root,'dist',src)))
  assert.ok(page.image.some(image=>image.contentUrl===site+src&&image.caption&&image.width>0&&image.height>0))
  assert.ok(read('sitemap.xml').includes('<image:loc>'+site+src+'</image:loc>'))
 }
 assert.match(html,/<figcaption>/)
})

test('real demonstration captures are described honestly and included on relevant canonical guides',()=>{
 for(const [file,sources] of [
  ['mensajes.html',['chat-android-demo','chat-web-demo']],
  ['novvin-web.html',['chat-web-demo']],
  ['descargar.html',['chat-android-demo','perfil-android-demo']],
 ]){
  const html=read(file),images=graph(html).find(item=>item['@type']==='WebPage').image
  for(const name of sources){
   const src='/images/novvin-'+name+'.png',image=images.find(item=>item.contentUrl===site+src)
   assert.ok(image?.caption.includes('demostración'))
   assert.ok(html.includes('src="'+src+'"'))
   const bytes=fs.readFileSync(path.join(root,'dist',src))
   assert.equal(bytes.readUInt32BE(16),image.width)
   assert.equal(bytes.readUInt32BE(20),image.height)
  }
 }
})

test('spelling queries are explained visibly without keyword stuffing or false organization aliases',()=>{
 const html=read('encontrar-novvin.html')
 for(const spelling of ['Novin','Novyn','Novvyn','Ovin','Vvvin','NoWi','Nowi']) assert.ok(html.includes(spelling))
 assert.doesNotMatch(html,/name="keywords"/)
 assert.equal(graph(html).find(item=>item['@type']==='Organization').name,'Novvin')
 assert.deepEqual(graph(html).find(item=>item['@type']==='WebSite').alternateName,['Novvin Web',new URL(site).hostname])
})

test('Google ownership proof is a public verification document and the new help pages contain useful guidance',()=>{
 assert.equal(read('google0c9297c9ccb7a521.html').trim(),'google-site-verification: google0c9297c9ccb7a521.html')
 assert.match(read('stickers-animados.html'),/GIF|WebP/)
 assert.match(read('stickers-animados.html'),/no puede leer al pegar/)
 assert.match(read('privacidad-y-seguridad.html'),/permisos|contraseña/)
 assert.doesNotMatch(read('privacidad-y-seguridad.html'),/certificad[oa] ISO|seguridad garantizada|cifrado de extremo a extremo/)
})

test('the rendered home preserves the ownership proofs for Google and Bing',()=>{
 const html=read('index.html')
 assert.match(html,/<meta name="google-site-verification" content="[^"]+"/)
 assert.match(html,/<meta name="msvalidate\.01" content="6AF16B2EBFF5AB4AA7EDD93B1C90DE3F"/)
 assert.match(html,/name="robots" content="index, follow/)
})

test('home is readable without JavaScript and provides real access links',()=>{
 const html=read('index.html')
 assert.match(html,/<h1>Novvin/)
 assert.ok(html.includes('Novvin es una red social para conectar con tu gente.'))
 assert.doesNotMatch(html,/N-O-V-V-I-N|Novvyn|NoWin|Noving|Nowing/)
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

test('home describes the product without spelling phrases that displace the search snippet',()=>{
 const html=read('index.html')
 assert.match(html,/Novvin es una red social para conectar con tu gente/)
 assert.match(html,/href="\/funciones"/)
 assert.match(html,/href="\/ayuda\/encontrar-novvin"/)
 assert.doesNotMatch(html,/N-O-V-V-I-N|Novvyn|Nowing/)
 assert.doesNotMatch(html,/<(?:main|div)[^>]+data-nosnippet/)
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
  assert.deepEqual(web.alternateName,['Novvin Web',new URL(site).hostname]);assert.equal(org.logo.width,512)
  assert.equal(page.url,url);assert.equal(page['@id'],url+'#webpage')
  assert.ok(html.includes('<title>'+page.name+'</title>'))
  assert.doesNotMatch(JSON.stringify(nodes),/aggregateRating|SearchAction|sameAs/)
  if(route!=='/'){const breadcrumb=nodes.find(item=>item['@type']==='BreadcrumbList');assert.equal(breadcrumb.itemListElement.at(-1).name,label);assert.equal(breadcrumb.itemListElement.at(-1).item,url)}
  const raw=/<script type="application\/ld\+json">(.*?)<\/script>/.exec(html)[1],hash=createHash('sha256').update(raw).digest('base64')
  for(const file of ['vercel.json','../vercel.json']){
   const deployment=JSON.parse(fs.readFileSync(path.join(root,file),'utf8'))
   const csp=deployment.headers.find(rule=>rule.source==='/(.*)')?.headers.find(header=>header.key==='Content-Security-Policy')?.value
   assert.ok(csp,'Global Content-Security-Policy is required in '+file)
   assert.ok(csp.includes("'sha256-"+hash+"'"));assert.doesNotMatch(csp,/script-src[^;]*'unsafe-inline'/)
  }
 })
}

test('group, community and voice invitations remain private without displacing global security headers',()=>{
 for(const file of ['vercel.json','../vercel.json']){
  const deployment=JSON.parse(fs.readFileSync(path.join(root,file),'utf8'))
  const headers=deployment.headers.find(rule=>rule.source==='/(group|community|voice)/:path*')?.headers
  assert.ok(headers,'Invitation headers are required in '+file)
  assert.equal(headers.find(header=>header.key==='Cache-Control')?.value,'no-store')
  assert.equal(headers.find(header=>header.key==='X-Robots-Tag')?.value,'noindex, nofollow, noarchive')
  const global=deployment.headers.find(rule=>rule.source==='/(.*)')?.headers
  assert.equal(global?.find(header=>header.key==='X-Content-Type-Options')?.value,'nosniff')
  assert.ok(global?.find(header=>header.key==='Content-Security-Policy')?.value)
  assert.equal(deployment.rewrites.find(rule=>rule.source==='/voice/:path*')?.destination,'/app-shell.html')
 }
})
test('each public title and description is unique',()=>{
 const pages=publicRoutes.map(([,file])=>graph(read(file)).find(item=>item['@type']==='WebPage'))
 assert.equal(new Set(pages.map(page=>page.name)).size,publicRoutes.length)
 assert.equal(new Set(pages.map(page=>page.description)).size,publicRoutes.length)
 const headings=publicRoutes.map(([,file])=>/<h1>([\s\S]*?)<\/h1>/.exec(read(file))[1])
 assert.equal(new Set(headings).size,publicRoutes.length)
})

test('site name signals consistently prefer Novvin and offer the site hostname as last fallback',()=>{
 const html=read('index.html'),web=graph(html).find(item=>item['@type']==='WebSite')
 assert.equal(web.name,'Novvin')
 assert.match(html,/<meta property="og:site_name" content="Novvin"/)
 assert.deepEqual(web.alternateName,['Novvin Web','red-social-android.vercel.app'])
 assert.ok(!web.alternateName.includes('Vercel'))
 assert.ok(!web.alternateName.includes('Novvyn'))
})

test('the home links to the official-access guide without repeating typo keywords',()=>{
 const html=read('index.html')
 assert.match(html,/<details open=""><summary>¿Cómo encuentro el acceso oficial a Novvin\?<\/summary>/)
 assert.doesNotMatch(html,/Si llegaste escribiendo/)
 assert.match(html,/href="\/ayuda\/encontrar-novvin">los enlaces oficiales de Novvin/)
})

test('the web guide describes actual access and features while spelling help has its own guide',()=>{
 const html=read('novvin-web.html')
 assert.match(html,/<h2>El acceso oficial a Novvin Web<\/h2>/)
 assert.match(html,/misma cuenta|tu cuenta de Android/)
 assert.doesNotMatch(html,/N-O-V-V-I-N/)
 assert.equal(graph(html).find(item=>item['@type']==='Organization').name,'Novvin')
})

test('every public page links to the complete grouped guide directory',()=>{
 for(const [,file] of publicRoutes){
  const html=read(file)
  assert.match(html,/aria-label="Guías de Novvin"/)
  for(const [route] of publicRoutes.filter(([route])=>route!=='/'))assert.ok(html.includes('href="'+route+'"'),file+' misses '+route)
  for(const group of ['Conversar','Descubrir','Tu cuenta'])assert.ok(html.includes('<h3>'+group+'</h3>'))
 }
})

test('visible breadcrumbs and active guide match the canonical public page',()=>{
 for(const [route,file,label] of publicRoutes.filter(([route])=>route!=='/')){
  const html=read(file)
  assert.ok(html.includes('<li aria-current="page">'+label+'</li>'))
  assert.ok(html.includes('href="'+route+'" aria-current="page"'))
 }
})

test('download page sends visitors to the actual Android app without fake package links or ratings',()=>{
 const html=read('descargar.html')
 assert.match(html,/href="https:\/\/play.google.com\/store\/apps\/details\?id=conectate.com"/)
 assert.match(html,/Ver Novvin en Google Play/)
 assert.doesNotMatch(html,/\.apk["?]|\.exe["?]|AggregateRating|ratingCount/)
})

test('find-Novvin guide distinguishes browsers, search engines and shared links',()=>{
 const html=read('encontrar-novvin.html')
 for(const term of ['Google','Brave Search','Bing','DuckDuckGo','Chrome','Edge','Opera','X u otra aplicación','Novvyn'])assert.ok(html.includes(term),term)
 assert.match(html,/no hacer|sin hacer otra búsqueda/)
 assert.doesNotMatch(html,/primera posición garantizada|todos los navegadores están certificados/)
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

test('product entities identify the Android listing and browser app using visible facts',()=>{
 for(const file of ['index.html','que-es-novvin.html','novvin-web.html','descargar.html','preguntas-frecuentes.html']){
  const html=read(file),nodes=graph(html)
  const mobile=nodes.find(node=>node['@type']==='MobileApplication'),web=nodes.find(node=>node['@type']==='WebApplication')
  assert.equal(mobile.name,'Novvin');assert.equal(mobile.operatingSystem,'Android')
  assert.equal(mobile.installUrl,'https://play.google.com/store/apps/details?id=conectate.com')
  assert.ok(html.includes('href="'+mobile.installUrl+'"'))
  assert.equal(web.name,'Novvin Web');assert.equal(web.url,site+'/novvin-web')
  assert.deepEqual(nodes.find(node=>node['@type']==='WebPage').about,[{'@id':mobile['@id']},{'@id':web['@id']}])
  assert.doesNotMatch(JSON.stringify([mobile,web]),/aggregateRating|review|offers|softwareVersion|downloadCount/)
 }
 assert.ok(!graph(read('videos.html')).some(node=>node['@type']==='MobileApplication'))
})

test('product overview gives an explicit definition, real platforms and usable access links',()=>{
 const html=read('que-es-novvin.html')
 for(const text of ['Qué es Novvin','Novvin reúne mensajes privados','conectate.com','misma cuenta','Novvin Web','Google Play'])assert.ok(html.includes(text),text)
 assert.match(read('index.html'),/Novvin es una red social/)
 assert.match(read('index.html'),/href="\/que-es-novvin"/)
})

test('public questions are readable directly and describe current linking and upload rules',()=>{
 const html=read('preguntas-frecuentes.html')
 for(const text of ['¿Qué es Novvin?','¿Cómo vinculo Novvin Web con mi teléfono?','vincula automáticamente','500 MiB','60 MiB','no es tu contraseña'])assert.ok(html.includes(text),text)
 assert.doesNotMatch(html,/<details|primera posición garantizada/)
 assert.ok(read('ayuda.html').includes('500 MiB'))
 assert.ok(read('novvin-web.html').includes('vinculada automáticamente'))
 assert.ok(read('comunidades.html').includes('permiso para publicar'))
})
