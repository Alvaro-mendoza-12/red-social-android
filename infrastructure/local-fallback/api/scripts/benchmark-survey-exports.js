'use strict'
// Local synthetic benchmark only. Does not connect to any API or database.
const {performance}=require('node:perf_hooks')
const D=require('../src/surveys/domain'),{exportSurvey}=require('../src/surveys/exports'),{assertExportBudget}=require('../src/surveys/resources')
async function main(){
 const c={id:'11111111-1111-4111-8111-111111111111',revision:1,created_at:new Date().toISOString(),schema:D.normalizeSchema({title:'Benchmark ficticio',purpose:'Validación local',contact:'qa@example.org',retention:'Datos sintéticos',retentionDays:30,consent:'Acepto',sections:[{id:'inicio',title:'Inicio'}],questions:Array.from({length:4},(_,i)=>({id:`q${i}`,section:'inicio',title:`Variable ${i+1}`,type:'number',measure:'continuous'}))})}
 const responses=Array.from({length:20000},(_,i)=>({id:`respuesta-${i}`,account_id:`cuenta-${i}`,display_name:`QA ${i}`,submitted_at:'2026-10-03T12:00:00Z',updated_at:'2026-10-03T12:00:00Z',answers:Object.fromEntries(c.schema.questions.map((q,j)=>[q.id,i+j*.01]))}))
 assertExportBudget(c,responses)
 const results=[]
 for(const format of ['csv','xlsx']){
  const start=performance.now();const file=await exportSurvey(c,responses,{format});const memory=process.memoryUsage()
  results.push({format,responses:responses.length,questions:4,ms:Math.round(performance.now()-start),bytes:Buffer.from(file.data,'base64').length,rssMiB:Math.round(memory.rss/1048576),heapMiB:Math.round(memory.heapUsed/1048576),maxRssKiB:process.resourceUsage().maxRSS})
 }
 console.log(JSON.stringify({machine:'local Windows development host; not production performance',results},null,2))
}
main().catch(error=>{console.error(error.message);process.exitCode=1})
