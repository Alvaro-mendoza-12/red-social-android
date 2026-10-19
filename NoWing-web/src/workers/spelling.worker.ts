/// <reference lib="webworker" />
import nspell from 'nspell'
let dictionary:Promise<ReturnType<typeof nspell>>|undefined
async function load(){
  if(!dictionary)dictionary=Promise.all(['index.aff','index.dic'].map(async name=>{
    const response=await fetch(`/dictionaries/es-4.0.0/${name}`,{cache:'force-cache'})
    if(!response.ok)throw Error('dictionary')
    const value=await response.text()
    if(value.length>5000000||(name==='index.aff'?!/SET UTF-8/.test(value):!/^\d+\r?\n/.test(value)))throw Error('dictionary')
    return value
  })).then(([aff,dic])=>nspell(aff,dic)).catch(error=>{dictionary=undefined;throw error})
  return dictionary
}
let latest=0
self.onmessage=async(event:MessageEvent<{id:number;word:string}>)=>{
  const {id,word}=event.data;latest=id
  if(typeof word!=='string'||word.length>32||!/^\p{L}[\p{L}\p{M}]+$/u.test(word))return
  try{const spell=await load();if(latest!==id)return;const suggestions=spell.correct(word)?[]:spell.suggest(word).slice(0,4);self.postMessage({id,suggestions})}
  catch{if(latest===id)self.postMessage({id,error:true,suggestions:[]})}
}
