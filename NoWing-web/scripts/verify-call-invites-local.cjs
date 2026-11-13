const {chromium}=require('playwright'),fs=require('node:fs'),assert=require('node:assert/strict');
const path=require('node:path');
const data=JSON.parse(fs.readFileSync(process.env.QA_ACCOUNT_FILE)),base='https://novvinapp.com',out=path.resolve(process.env.QA_OUTPUT_DIR||'qa-call-results');
for(const key of ['a','b','c'])assert(data[key]?.email?.endsWith('@example.invalid'),'SYNTHETIC_ACCOUNTS_REQUIRED');
const port=Number(process.env.QA_HTTPS_PORT||55734);assert(Number.isInteger(port)&&port>0&&port<65536);fs.mkdirSync(out,{recursive:true});
const baseline=process.argv.includes('--before');
(async()=>{
 const browser=await chromium.launch({executablePath:process.env.QA_CHROME||'C:/Program Files/Google/Chrome/Application/chrome.exe',headless:true,args:['--no-proxy-server','--host-resolver-rules=MAP novvinapp.com 127.0.0.1:'+port+', MAP * ~NOTFOUND, EXCLUDE localhost','--use-fake-device-for-media-stream','--use-fake-ui-for-media-stream','--autoplay-policy=no-user-gesture-required']});
 const contexts=[],pass=[],errors=[];
 async function login(account){const ctx=await browser.newContext({ignoreHTTPSErrors:true,permissions:['microphone','camera'],viewport:{width:1440,height:900}});contexts.push(ctx);const page=await ctx.newPage();page.setDefaultTimeout(14000);page.on('pageerror',e=>errors.push(e.message.slice(0,180)));await page.goto(base+'/login');await page.getByRole('button',{name:'Contraseña',exact:true}).click();await page.locator('input[type=email]').fill(account.email);await page.locator('input[autocomplete="current-password"]').fill(account.password);await page.getByRole('button',{name:'Iniciar sesión',exact:true}).click();await page.waitForURL('**/app/**');return page;}
 const test=async(name,fn)=>{await fn();pass.push(name);console.log('PASS',name)};
 try{
 const a=await login(data.a);await a.goto(base+'/app/messages/'+data.chat);await a.getByRole('region',{name:'Historial de mensajes'}).waitFor();
 if(baseline){await a.screenshot({path:out+'/before-1440.png'});console.log('Baseline captured');return;}
 await test('desktop collapse, accessible names, persisted layout and restored width',async()=>{
 const toggle=a.getByRole('button',{name:'Contraer menú lateral',exact:true}),nav=a.getByRole('navigation',{name:'Navegación principal',exact:true});
 const expanded=(await nav.boundingBox()).width;await a.screenshot({path:out+'/expanded-1440.png'});await toggle.click();const compact=(await nav.boundingBox()).width;assert(compact<expanded&&compact===76);await a.getByRole('link',{name:'Nowy'}).count();
 for(const name of ['Mensajes','Videos','Social','Juegos','Perfil','Buscar','Notificaciones','Llamadas','Salas de voz','Asistente de Novvin'])assert(await nav.getByRole('link',{name,exact:true}).count());
 await a.screenshot({path:out+'/collapsed-1440.png'});await a.reload();await a.getByRole('button',{name:'Expandir menú lateral',exact:true}).waitFor();assert.equal((await nav.boundingBox()).width,76);await a.getByRole('button',{name:'Expandir menú lateral',exact:true}).click();assert.equal((await nav.boundingBox()).width,expanded);
 });
 for(const [width,height] of [[1355,608],[1153,617],[768,900],[375,812]])await test('responsive layout and independent scroll '+width,async()=>{
 await a.setViewportSize({width,height});await a.goto(base+'/app/messages/'+data.chat);const thread=a.getByRole('region',{name:'Historial de mensajes'});await thread.waitFor();await thread.getByText('Prueba de historial 35',{exact:true}).waitFor();assert(await a.evaluate(()=>document.documentElement.scrollWidth<=innerWidth));const metrics=await thread.evaluate(el=>{el.scrollTop=0;const before=el.scrollTop;el.scrollTop=el.scrollHeight;return {moves:el.scrollTop>before,height:el.clientHeight}});assert(metrics.moves&&metrics.height>100);
 if(width>=1100) {await a.getByRole('button',{name:'Contraer menú lateral',exact:true}).click();await a.screenshot({path:out+'/collapsed-'+width+'.png'});await a.getByRole('button',{name:'Expandir menú lateral',exact:true}).click();}
 else assert.equal(await a.getByRole('button',{name:'Contraer menú lateral',exact:true}).isVisible(),false);
 await a.screenshot({path:out+'/messages-'+width+'.png'});
 });
 const b=await login(data.b),c=await login(data.c);
 await a.setViewportSize({width:1440,height:900});await a.goto(base+'/app/calls?target='+data.b.id+'&name='+encodeURIComponent(data.b.name)+'&contextId='+data.chat+'&kind=audio');
 await b.getByRole('button',{name:'Aceptar',exact:true}).click();
 for(const p of [a,b])await p.waitForFunction(()=>[...document.querySelectorAll('.remote-video-element')].some(e=>e.srcObject?.getAudioTracks().some(t=>t.readyState==='live')),null,{timeout:25000});
 await test('invitation and third participant without replacing existing tracks',async()=>{
 await a.evaluate(()=>{window.__beforeAudio=document.querySelector('.remote-video-element').srcObject.getAudioTracks()[0]});
 await a.getByRole('button',{name:'Invitar personas a la llamada',exact:true}).click();await a.getByRole('searchbox',{name:'Buscar personas para invitar'}).fill(data.c.name);await a.getByRole('button',{name:'Invitar a '+data.c.name,exact:true}).click();await a.getByRole('status').filter({hasText:'Invitación enviada a'}).waitFor();
 await c.getByRole('button',{name:'Aceptar',exact:true}).click();await a.getByRole('button',{name:'Cerrar invitaciones',exact:true}).click();
 for(const p of [a,b,c])await p.waitForFunction(()=>[...document.querySelectorAll('.remote-video-element')].filter(e=>e.srcObject?.getAudioTracks().some(t=>t.readyState==='live')).length===2,null,{timeout:30000});
 assert(await a.evaluate(()=>document.querySelectorAll('.remote-video-element')[0].srcObject.getAudioTracks().includes(window.__beforeAudio)&&window.__beforeAudio.readyState==='live'));
 assert(!new URL(c.url()).searchParams.get('contextId'));
 await a.screenshot({path:out+'/group-call-1440.png'});await a.setViewportSize({width:375,height:812});await a.screenshot({path:out+'/group-call-375.png'});
 });
 await test('camera renegotiation preserves all remote audio streams',async()=>{
 await a.getByRole('button',{name:'Activar cámara',exact:true}).click();
 for(const p of [b,c])await p.waitForFunction(id=>{const el=document.querySelector('[data-participant-id="'+id+'"] video');return el?.srcObject?.getVideoTracks().some(t=>t.readyState==='live')&&el.videoWidth>0},data.a.id,{timeout:25000});
 for(const p of [a,b,c])assert.equal(await p.locator('.remote-video-element').evaluateAll(elements=>elements.filter(el=>el.srcObject?.getAudioTracks().some(t=>t.readyState==='live')).length),2);
 await a.getByRole('button',{name:'Apagar cámara',exact:true}).click();
 });
 await test('one member leaving preserves the other two',async()=>{await c.getByRole('button',{name:'Colgar llamada',exact:true}).last().click();for(const p of [a,b])await p.waitForFunction(()=>document.querySelectorAll('.remote-video-element').length===1&&document.querySelector('.remote-video-element').srcObject?.getAudioTracks().some(t=>t.readyState==='live'));});
 await test('host ending releases all remaining participants',async()=>{await a.getByRole('button',{name:'Colgar llamada',exact:true}).last().click();for(const p of [a,b])await p.waitForFunction(()=>!document.querySelector('.remote-video-element')?.srcObject,null,{timeout:15000});});
 fs.writeFileSync(out+'/browser-results.json',JSON.stringify({pass,errors},null,2));assert.equal(errors.length,0);
 }finally{for(const context of contexts)await context.close();await browser.close()}
})().catch(e=>{console.error(e.stack);process.exitCode=1});
