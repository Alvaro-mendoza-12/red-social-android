import { ExternalLink, Link as LinkIcon } from 'lucide-react'
const pattern=/https?:\/\/[^\s<>]+/gi
export function MessageText({text,className}:{text:string;className?:string}) {
 const matches=[...text.matchAll(pattern)].map(match=>({start:match.index!,value:match[0].replace(/[.,;!?\])}]+$/,'')})).filter(match=>{try{const url=new URL(match.value);return !url.username&&!url.password}catch{return false}})
 const first=matches[0];let offset=0
 return <div className={['message-rich-text',className].filter(Boolean).join(' ')}>{first?<a className="message-link-preview" href={first.value} target="_blank" rel="noopener noreferrer"><LinkIcon size={22}/><span><strong>{new URL(first.value).hostname.replace(/^www\./,'')}</strong><small>{new URL(first.value).pathname==='/'?'Abrir enlace':new URL(first.value).pathname.replace(/%20/g,' ').slice(0,160)}</small></span><ExternalLink size={15}/></a>:null}<p>{matches.map(match=>{const before=text.slice(offset,match.start);offset=match.start+match.value.length;return <span key={match.start}>{before}<a href={match.value} target="_blank" rel="noopener noreferrer">{match.value}</a></span>})}{text.slice(offset)}</p></div>
}
