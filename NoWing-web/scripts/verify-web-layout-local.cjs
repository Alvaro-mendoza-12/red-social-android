// Isolated loopback QA only; synthetic accounts and a pre-seeded demo chat are required.
const {chromium}=require('playwright'),fs=require('node:fs'),assert=require('node:assert/strict');
const path=require('node:path');
const data=JSON.parse(fs.readFileSync(process.env.QA_ACCOUNTS_FILE)),base='https://novvinapp.com',out=path.resolve(process.env.QA_OUTPUT_DIR || 'build/web-layout-qa');
fs.mkdirSync(out,{recursive:true});
const gatewayPort=Number(process.env.QA_GATEWAY_PORT || 55734);assert(Number.isInteger(gatewayPort)&&gatewayPort>1024&&gatewayPort<65536);
(async()=>{
const browser=await chromium.launch({...(process.env.QA_CHROME_EXECUTABLE?{executablePath:process.env.QA_CHROME_EXECUTABLE}:{}),headless:true,args:['--no-proxy-server','--host-resolver-rules=MAP novvinapp.com 127.0.0.1:'+gatewayPort+', MAP * ~NOTFOUND, EXCLUDE localhost']});
const ctx=await browser.newContext({ignoreHTTPSErrors:true,viewport:{width:1440,height:900}}),page=await ctx.newPage();page.setDefaultTimeout(15000);
const pass=[],metrics=[],errors=[];page.on('pageerror',e=>errors.push(e.message));const test=async(name,fn)=>{await fn();pass.push(name);console.log('PASS',name)};
try{
await page.goto(base+'/login');await page.getByRole('button',{name:'Contraseña',exact:true}).click();await page.locator('input[type=email]').fill(data.a.email);await page.locator('input[autocomplete="current-password"]').fill(data.a.password);await page.getByRole('button',{name:'Iniciar sesión',exact:true}).click();await page.waitForURL('**/app/**');
for(const [width,height] of [[1355,608],[1153,617],[1440,900],[1153,480],[768,900],[375,812]]) await test('compact navigation and chat at '+width+'x'+height,async()=>{
 await page.setViewportSize({width,height});await page.goto(base+'/app/messages/'+data.chat);const thread=page.getByRole('region',{name:'Historial de mensajes'});await thread.waitFor();const nav=page.getByRole('navigation',{name:'Navegación principal',exact:true});
 if(width>=1100){
  const dimensions=await nav.evaluate(el=>({height:el.clientHeight,scroll:el.scrollHeight}));assert(dimensions.scroll<=dimensions.height+1,JSON.stringify(dimensions));
  assert.equal(await nav.getByRole('link',{name:'Llamadas',exact:true}).count(),0);assert.equal(await nav.getByRole('link',{name:'Salas de voz',exact:true}).count(),0);
  for(const name of ['Explorar Novvin','Cerrar sesión'])assert(await nav.getByRole('button',{name,exact:true}).isVisible());
  assert.equal(await nav.getByText('Contraer menú',{exact:true}).count(),0);
  await nav.getByRole('button',{name:'Contraer menú lateral',exact:true}).click();await page.screenshot({path:out+'/collapsed-'+width+'x'+height+'.png'});await nav.getByRole('button',{name:'Expandir menú lateral',exact:true}).click();
 }
 const detail=await page.locator('.chat-header-row').evaluate(el=>({header:el.getBoundingClientRect().height,composer:document.querySelector('.chat-composer').getBoundingClientRect().height,scrollbar:getComputedStyle(document.querySelector('.chat-thread')).scrollbarWidth}));assert(detail.header<=62,JSON.stringify(detail));assert(detail.composer<=65,JSON.stringify(detail));assert.equal(detail.scrollbar,'none');metrics.push({width,height,...detail});
 assert(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth));
 await thread.getByText('Prueba de historial 35',{exact:true}).waitFor();assert(await thread.evaluate(el=>{el.scrollTop=0;const before=el.scrollTop;el.scrollTop=el.scrollHeight;return el.scrollTop>before}));await page.screenshot({path:out+'/chat-'+width+'x'+height+'.png'});
});
await test('sticker catalog also omits scrollbar chrome',async()=>{
 await page.setViewportSize({width:1355,height:608});await page.goto(base+'/app/messages/'+data.chat);await page.getByRole('button',{name:'Stickers',exact:true}).click();const grid=page.locator('.sticker-grid-compact');await grid.waitFor();assert.equal(await grid.evaluate(el=>getComputedStyle(el).scrollbarWidth),'none');await page.getByRole('button',{name:'Cerrar stickers',exact:true}).click();
});
await test('chat menu exposes scheduling, translation and polls directly',async()=>{
 await page.setViewportSize({width:1355,height:608});await page.goto(base+'/app/messages/'+data.chat);
 for(const [name,target] of [['Programar mensaje','Mensaje programado...'],['Crear encuesta','Pregunta']]){
  await page.getByRole('button',{name:'Opciones del chat',exact:true}).click();await page.getByRole('menuitem',{name,exact:true}).click();await page.getByRole('dialog',{name:'Más opciones del chat'}).getByPlaceholder(target,{exact:true}).waitFor();await page.getByRole('button',{name:'Cerrar más opciones',exact:true}).click();
 }
 await page.getByRole('button',{name:'Opciones del chat',exact:true}).click();await page.getByRole('menuitem',{name:'Traducción',exact:true}).click();await page.getByRole('textbox',{name:'Idioma de traducción',exact:true}).waitFor();await page.getByRole('button',{name:'Cerrar más opciones',exact:true}).click();
});
await test('assistant transcript and composer stay inside the viewport',async()=>{
 await page.setViewportSize({width:1440,height:900});await page.goto(base+'/app/assistant');await page.getByRole('heading',{name:'Novvin',exact:true}).waitFor();assert.equal(await page.locator('.novvin-topbar').count(),0);
 const box=page.getByRole('textbox',{name:'Mensaje al asistente'});await box.fill('Ayúdame a organizar una publicación');assert(await box.isVisible());
 await page.screenshot({path:out+'/assistant-1440.png'});
 await page.route('**/v1/ai/chat',route=>route.fulfill({status:200,contentType:'application/json',body:JSON.stringify({answer:'Podemos empezar con una idea clara.\n\nEscribe lo que quieres compartir y añade una foto si te ayuda a contarlo.',suggestions:['Preparar el texto','Elegir una foto']})}));
 await page.getByRole('button',{name:'Enviar al asistente',exact:true}).click();await page.getByText('Podemos empezar con una idea clara.',{exact:true}).waitFor();
 await page.screenshot({path:out+'/assistant-conversation-1440.png'});
 for(const width of [768,375]){await page.setViewportSize({width,height:812});assert(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth));const bounds=await box.boundingBox();assert(bounds.y+bounds.height<=812);await page.screenshot({path:out+'/assistant-'+width+'.png'});}
});
await test('real application capture for the official home',async()=>{
 const demo=JSON.parse(fs.readFileSync(process.env.QA_DEMO_CHAT_FILE));await page.setViewportSize({width:1440,height:900});await page.goto(base+'/app/messages/'+demo.chat);await page.getByRole('region',{name:'Historial de mensajes'}).getByText('¡Nos vemos! 💙',{exact:true}).waitFor();await page.getByRole('button',{name:'Ver perfil de Nora · Demo',exact:true}).waitFor();await page.locator('.conversation-row').filter({hasText:'Nora · Demo'}).waitFor();await page.mouse.move(1420,880);await page.screenshot({path:out+'/real-chat-demo.png'});
});
await test('public home is concise and uses only the real demonstration capture',async()=>{
 await page.setViewportSize({width:1440,height:900});await page.goto(base+'/');assert.equal(await page.locator('main img').count(),1);await page.getByRole('link',{name:'Abrir Novvin Web',exact:true}).waitFor();await page.screenshot({path:out+'/home-1440.png',fullPage:true});
 await page.setViewportSize({width:375,height:812});assert(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth));await page.screenshot({path:out+'/home-375.png',fullPage:true});
});
fs.writeFileSync(out+'/browser-results.json',JSON.stringify({pass,metrics,errors,assistantResponse:'Synthetic intercepted response; this UI run does not certify the AI service.'},null,2));assert.equal(errors.length,0);
}finally{await ctx.close();await browser.close()}
})().catch(e=>{console.error(e.stack);process.exitCode=1});
