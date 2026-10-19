import { useEffect, useRef, useState } from 'react'
import { spellingWordAt, replaceSpellingWord, type SpellingWord } from '../core/spellingWord'
import './spellingSuggestions.css'
export function SpellingSuggestions({text,caret,onReplace}:{text:string;caret?:number;onReplace:(text:string,caret:number)=>void}){
  const worker=useRef<Worker|null>(null),sequence=useRef(0),ignored=useRef(new Set<string>())
  const [result,setResult]=useState<{text:string;issue:SpellingWord;suggestions:string[]}|null>(null)
  const [failed,setFailed]=useState(false),[retry,setRetry]=useState(0)
  useEffect(()=>()=>{worker.current?.terminate();worker.current=null},[])
  useEffect(()=>{
    const issue=spellingWordAt(text,caret),id=++sequence.current
    if(!issue||ignored.current.has(issue.word.toLowerCase())||typeof Worker==='undefined')return
    let active=true
    const timer=setTimeout(()=>{void import('../core/createSpellingWorker').then(({createSpellingWorker})=>{
      if(!active)return
      const current=worker.current||(worker.current=createSpellingWorker())
      current.onmessage=(event:MessageEvent<{id:number;error?:boolean;suggestions:string[]}>)=>{
        if(!active||event.data.id!==sequence.current)return
        setFailed(Boolean(event.data.error));setResult({text,issue,suggestions:event.data.suggestions||[]})
      }
      current.onerror=()=>{if(active)setFailed(true);current.terminate();if(worker.current===current)worker.current=null}
      current.postMessage({id,word:issue.word})
    }).catch(()=>{if(active)setFailed(true)})},650)
    return()=>{active=false;clearTimeout(timer)}
  },[text,caret,retry])
  if(!spellingWordAt(text,caret))return null
  if(failed)return <div className="spelling-suggestions"><small>El corrector del navegador sigue disponible.</small><button type="button" onClick={()=>{setFailed(false);setRetry(value=>value+1)}}>Reintentar corrector</button></div>
  if(!result||result.text!==text||result.issue.start!==spellingWordAt(text,caret)?.start||!result.suggestions.length)return null
  return <div className="spelling-suggestions" role="region" aria-label="Sugerencias de escritura"><small>«{result.issue.word}»:</small>{result.suggestions.map(suggestion=><button type="button" key={suggestion} onClick={()=>{const next=replaceSpellingWord(text,result.issue,suggestion);if(next){onReplace(next.text,next.caret);setResult(null)}}}>{suggestion}</button>)}<button type="button" className="spelling-ignore" onClick={()=>{ignored.current.add(result.issue.word.toLowerCase());setResult(null)}}>Ignorar</button></div>
}
