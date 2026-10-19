import { ExternalLink, Link as LinkIcon } from 'lucide-react'
import { Link } from 'react-router-dom'
import type { MentionCandidate } from '../core/mentionSuggestions'
import { useId, useState } from 'react'
import './messageText.css'
const pattern=/https?:\/\/[^\s<>]+/gi
export function MessageText({text,className,mentions=[]}:{text:string;className?:string;mentions?:MentionCandidate[]}) {
 const [expandedText,setExpandedText]=useState<string|null>(null),id=useId()
 const long=text.length>600||text.split('\n').length>9,expanded=expandedText===text
 function plain(value:string) {
  const expression=/(^|[\s(])@([\p{L}\p{N}_.-]+)/gu
  const result=[];let end=0
  for(const match of value.matchAll(expression)) {
   const candidates=mentions.filter(person=>person.username.toLowerCase()===match[2].toLowerCase())
   if(candidates.length!==1)continue
   const start=match.index!+match[1].length
   result.push(value.slice(end,start),<Link className="message-mention" key={start} to={`/app/profile/${encodeURIComponent(candidates[0].id)}`}>@{match[2]}</Link>);end=start+match[2].length+1
  }
  result.push(value.slice(end));return result
 }
 const matches=[...text.matchAll(pattern)].map(match=>({start:match.index!,value:match[0].replace(/[.,;!?\])}]+$/,'')})).filter(match=>{try{const url=new URL(match.value);return !url.username&&!url.password}catch{return false}})
 const first=matches[0];let offset=0
 return <div className={['message-rich-text',className].filter(Boolean).join(' ')}>{first?<a className="message-link-preview" href={first.value} target="_blank" rel="noopener noreferrer"><LinkIcon size={22}/><span><strong>{new URL(first.value).hostname.replace(/^www\./,'')}</strong><small>{new URL(first.value).pathname==='/'?'Abrir enlace':new URL(first.value).pathname.replace(/%20/g,' ').slice(0,160)}</small></span><ExternalLink size={15}/></a>:null}<p id={id} className={long&&!expanded?'message-text-collapsed':undefined}>{matches.map(match=>{const before=text.slice(offset,match.start);offset=match.start+match.value.length;return <span key={match.start}>{plain(before)}<a href={match.value} target="_blank" rel="noopener noreferrer">{match.value}</a></span>})}{plain(text.slice(offset))}</p>{long?<button className="message-text-more" type="button" aria-controls={id} aria-expanded={expanded} onClick={()=>setExpandedText(expanded?null:text)}>{expanded?'Ver menos':'Leer más'}</button>:null}</div>
}
