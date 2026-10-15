'use strict';
const {test}=require('node:test'), assert=require('node:assert/strict'), fs=require('node:fs'), path=require('node:path');
const repo=process.env.SURVEY_TEST_REPO_ROOT || path.resolve(__dirname,'../..');
const {JSDOM}=require(path.join(repo,'Conectate-web/node_modules/jsdom'));
const D=require(path.join(repo,'infrastructure/local-fallback/api/src/surveys/domain'));
const assetRoot=process.env.SURVEY_ASSET_ROOT || path.join(repo,'app/src/main/assets/surveys');
const schema={title:'Encuesta de prueba',sections:[{id:'s',title:'Inicio'}],questions:[
 {id:'day',title:'¿Qué día prefieres?',type:'single',measure:'nominal',role:'descriptive',options:[{id:'sat',label:'Sábado'},{id:'sun',label:'Domingo'}],rows:[]},
 {id:'note',title:'¿Qué sugieres?',type:'paragraph',measure:'nominal',role:'descriptive',options:[],rows:[]},
 {id:'age',title:'Edad',type:'number',measure:'continuous',role:'descriptive',options:[],rows:[]}
]};
const campaign={id:'survey-qa',schema,revision:1,status:'open',response_count:72,visibility:'public',post_exists:true};
const responses=Array.from({length:72},(_,i)=>({id:`reply-${i+1}`,account_id:`synthetic-${i+1}`,display_name:`Persona ${i+1}`,
 submitted_at:'2026-10-08T10:00:00Z',updated_at:'2026-10-08T10:00:00Z',edit_revision:1,
 answers:{day:i%2?'sun':'sat',note:i===0?'<img src=x onerror="window.leaked=true">':`Sugerencia ${i+1}`,age:18+i}}));
async function harness(rows=responses) {
 const index=path.join(assetRoot,'index.html');
 const dom=new JSDOM(fs.readFileSync(fs.existsSync(index)?index:path.join(repo,'app/src/main/assets/surveys/index.html'),'utf8'),{url:'https://surveys.novvin.invalid/index.html',runScripts:'outside-only',pretendToBeVisual:true});
 const w=dom.window, calls=[], downloads=[]; let failNext=false, exportFailure=false, downloadFailure=false;
 w.HTMLElement.prototype.scrollIntoView=function(){this.dataset.scrolled="true"};
 w.ResizeObserver=class{observe(){} disconnect(){}}; w.scrollTo=()=>{};
 w.NovvinSurveys={ready(){}, download(raw){
   const value=JSON.parse(raw);downloads.push(value);
   queueMicrotask(()=>w.surveyReceive(downloadFailure?{id:value.requestId,error:'No hay espacio para guardar el archivo.'}:{id:value.requestId,value:{name:value.name,location:'Descargas/Novvin'}}));
 }, request(raw){const input=JSON.parse(raw);calls.push(input);
   queueMicrotask(()=>{try {
    let value; const p=input.path.replace('/v1/surveys','');
    if(p==='/me') value={id:'owner-qa'};
    else if(p==='/forms') value={forms:[]};
    else if(p==='/campaigns') value={campaigns:[{...campaign,response_count:rows.length}]};
    else if(p.startsWith('/campaigns/survey-qa/results?')) {
      if(failNext){failNext=false;w.surveyReceive({id:input.id,error:'Fallo temporal de prueba'});return;}
      const q=new URLSearchParams(p.split('?')[1]), filtered=rows.filter(r=>!q.get('search')||r.display_name.toLowerCase().includes(q.get('search').toLowerCase()));
      const page=Number(q.get('page')||0);
      value={...D.analytics(schema,filtered),responses:filtered.slice(page*50,(page+1)*50),page,pages:Math.ceil(filtered.length/50)};
    } else if(p==='/campaigns/survey-qa/export') {
      if(exportFailure)throw Error('Exportación temporalmente no disponible.');
      value={name:'respuesta.'+input.body.format,mime:input.body.format==='csv'?'text/csv':'application/octet-stream',data:Buffer.from('respuesta sintética').toString('base64')};
    } else throw Error('Unexpected fixture route '+p);
    w.surveyReceive({id:input.id,value});
   }catch(error){w.surveyReceive({id:input.id,error:error.message});}});
 }};
 w.eval(fs.readFileSync(path.join(assetRoot,'workbench.js'),'utf8'));
 const wait=async(fn)=>{for(let i=0;i<500;i++){if(fn())return;await new Promise(r=>setTimeout(r,2));}throw Error('UI did not settle: '+w.document.getElementById('app').textContent);};
 await wait(()=>w.document.querySelector('[data-action=manage]'));
 async function click(action){const b=w.document.querySelector(`[data-action="${action}"]`);assert.ok(b,'Missing action '+action);b.click();await wait(()=>w.document.getElementById('app').getAttribute('aria-busy')==='false');}
 await click('manage');
 return {dom,w,calls,downloads,click,wait,fail(){failNext=true;},exportFailure(value){exportFailure=value;},downloadFailure(value){downloadFailure=value;}};
}
test('Ver respuestas opens summary immediately with three focused tabs and collapsed advanced controls',async()=>{
 const h=await harness();try{const d=h.w.document;
  assert.equal(d.querySelectorAll('.result-tabs button').length,3);
  assert.deepEqual([...d.querySelectorAll('.result-tabs button')].map(b=>b.textContent),['Resumen','Pregunta','Individual']);
  assert.equal(d.querySelector('[data-action=results]').getAttribute('aria-pressed'),'true');
  assert.equal(d.querySelector('.response-count strong').textContent,'72');
  for(const s of ['.participation-settings','.result-filters','.result-tools','.result-analysis']) assert.equal(d.querySelector(s).open,false);
  assert.equal(d.querySelectorAll('.response-question').length,3);
  assert.ok(h.calls.some(r=>r.path.includes('/results?')));
  assert.ok(!d.querySelector('[onerror]'));
 }finally{h.dom.window.close();}
});
test('Pregunta shows one question and supports previous, next and the picker',async()=>{
 const h=await harness();try{await h.click('questions');const d=h.w.document;
  assert.equal(d.querySelectorAll('.response-question').length,1); assert.match(d.querySelector('.response-question h2').textContent,/día/);
  await h.click('question-result-next');assert.equal(d.querySelector('.response-question h2').textContent,'¿Qué sugieres?');
  d.getElementById('result-question-picker').value='2';d.getElementById('result-question-picker').dispatchEvent(new h.w.Event('change',{bubbles:true}));
  assert.equal(d.querySelector('.response-question h2').textContent,'Edad');
  await h.click('question-result-prev'); assert.equal(d.querySelector('.response-question h2').textContent,'¿Qué sugieres?');
 }finally{h.dom.window.close();}
});
test('Individual displays one answer, escapes user text, and crosses the 50-response boundary both directions',async()=>{
 const h=await harness();try{await h.click('individuals');const d=h.w.document;
  assert.equal(d.querySelectorAll('.individual-response').length,1);assert.equal(d.querySelector('.individual-response h2').textContent,'Persona 1');
  assert.ok(d.querySelector('.answer-value').textContent);assert.ok(!d.querySelector('.individual-response img'));assert.equal(h.w.leaked,undefined);
  for(let i=0;i<50;i++)await h.click('responses-next');
  assert.equal(d.querySelector('.individual-response h2').textContent,'Persona 51');assert.match(d.querySelector('.response-navigation').textContent,/51 de 72/);
  await h.click('responses-prev');assert.equal(d.querySelector('.individual-response h2').textContent,'Persona 50');
  await h.click('responses-next');for(let i=51;i<72;i++)await h.click('responses-next');
  assert.equal(d.querySelector('.individual-response h2').textContent,'Persona 72');assert.equal(d.querySelector('[data-action=responses-next]'),null);
  assert.equal(d.querySelector('.individual-response details').open,false);
 }finally{h.dom.window.close();}
});
test('Filters retain Individual view, reset pagination, validate dates, and can be cleared',async()=>{
 const h=await harness();try{await h.click('individuals');const d=h.w.document;
  d.getElementById('filter-search').value='Persona 72';await h.click('apply-result-filters');
  assert.equal(d.querySelector('.response-count strong').textContent,'1');assert.equal(d.querySelector('.individual-response h2').textContent,'Persona 72');
  assert.equal(d.querySelector('[data-action=individuals]').getAttribute('aria-pressed'),'true');
  const n=h.calls.length;d.getElementById('filter-from').value='2026-10-09T10:00';d.getElementById('filter-to').value='2026-10-08T10:00';
  await h.click('apply-result-filters');assert.match(d.getElementById('result-filter-error').textContent,/Revisa las fechas/);assert.equal(h.calls.length,n);
  await h.click('clear-result-filters');assert.equal(d.querySelector('.response-count strong').textContent,'72');assert.equal(d.querySelector('.individual-response h2').textContent,'Persona 1');
 }finally{h.dom.window.close();}
});
test('Empty responses remain readable and a failed request offers a working retry',async()=>{
 const h=await harness([]);try{const d=h.w.document;
  assert.equal(d.querySelector('.response-count strong').textContent,'0');assert.match(d.querySelector('.empty').textContent,/Aún no hay respuestas/);
  await h.click('individuals');assert.equal(d.querySelector('.individual-response'),null);
  h.fail();await h.click('results');assert.ok(d.querySelector('[data-action=retry-results]'));
  await h.click('retry-results');assert.equal(d.querySelector('.response-count strong').textContent,'0');
 }finally{h.dom.window.close();}
});

test('Response exports generate each selected format and wait for native save acknowledgment',async()=>{
 const h=await harness();try{await h.click('exports');const d=h.w.document;
  assert.equal(d.getElementById('result-panel').dataset.scrolled,'true');
  for(const format of ['xlsx','csv','docx','pdf']) {
   d.getElementById('export-format').value=format;await h.click('download-export');
   const request=h.calls.at(-1);assert.equal(request.path,'/v1/surveys/campaigns/survey-qa/export');
   assert.equal(request.body.format,format);assert.equal(request.body.identity,false);
   assert.equal(request.body.x,'');assert.equal(request.body.y,'');
   assert.ok(h.downloads.at(-1).requestId);assert.equal(h.downloads.at(-1).name,'respuesta.'+format);
   assert.match(d.getElementById('notice').textContent,/Archivo guardado/);
  }
 }finally{h.dom.window.close();}
});
test('Response export failure is visible and a retry only saves a successful export',async()=>{
 const h=await harness();try{await h.click('exports');h.exportFailure(true);await h.click('download-export');
  assert.equal(h.downloads.length,0);assert.match(h.w.document.getElementById('notice').textContent,/temporalmente/);
  h.exportFailure(false);await h.click('download-export');assert.equal(h.downloads.length,1);
  assert.match(h.w.document.getElementById('notice').textContent,/Archivo guardado/);
 }finally{h.dom.window.close();}
});
test('Native save failure produces no success notice and leaves a usable download button',async()=>{
 const h=await harness();try{await h.click('exports');h.downloadFailure(true);await h.click('download-export');
  assert.match(h.w.document.getElementById('notice').textContent,/No hay espacio/);
  assert.equal(h.w.document.querySelector('[data-action=download-export]').disabled,false);
  h.downloadFailure(false);await h.click('download-export');assert.match(h.w.document.getElementById('notice').textContent,/Archivo guardado/);
 }finally{h.dom.window.close();}
});
test('Export respects selected participant filters and optional identity consent',async()=>{
 const h=await harness();try{h.w.document.getElementById('filter-search').value='Persona 72';await h.click('apply-result-filters');await h.click('exports');
  h.w.document.getElementById('export-format').value='csv';h.w.document.getElementById('export-identity').checked=true;await h.click('download-export');
  assert.equal(h.calls.at(-1).body.filters.search,'Persona 72');assert.equal(h.calls.at(-1).body.identity,true);
 }finally{h.dom.window.close();}
});
