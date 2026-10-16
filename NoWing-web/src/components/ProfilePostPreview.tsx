import { useEffect, useId, useRef, useState, type ReactNode } from 'react'
import { createPortal } from 'react-dom'
import { StreamingImage, StreamingVideo } from './StreamingMedia'
import { runtimeStoragePreviewUrl } from '../core/runtimeBackend'

/** Pointer and keyboard preview; touch users keep the normal publication link. */
export function ProfilePostPreview({media,text,children}:{media?:{url:string;mediaType:string};text:string;children:ReactNode}) {
 const article=useRef<HTMLElement>(null),timer=useRef<ReturnType<typeof setTimeout>|undefined>(undefined),id=useId()
 const [position,setPosition]=useState<{left:number;top:number;width:number}|null>(null)
 const eligible=Boolean(media?.url&&['image','video'].includes(media.mediaType))
 const hide=()=>{clearTimeout(timer.current);setPosition(null)}
 useEffect(()=>()=>clearTimeout(timer.current),[])
 useEffect(()=>{if(!position)return;const close=()=>setPosition(null);window.addEventListener('resize',close);window.addEventListener('scroll',close,true);return()=>{window.removeEventListener('resize',close);window.removeEventListener('scroll',close,true)}},[position])
 function show(){
  if(!eligible)return
  clearTimeout(timer.current)
  timer.current=setTimeout(()=>{
   const rect=article.current?.getBoundingClientRect();if(!rect)return
   const width=Math.min(360,window.innerWidth-24),height=Math.min(360,window.innerHeight-24)
   const left=rect.right+width+24<=window.innerWidth?rect.right+12:Math.max(12,rect.left-width-12)
   setPosition({left,top:Math.max(12,Math.min(rect.top,window.innerHeight-height-12)),width})
  },250)
 }
 return <article ref={article} className="mobile-profile-post" aria-describedby={position?id:undefined} onMouseEnter={()=>{if(window.matchMedia('(hover: hover) and (pointer: fine)').matches)show()}} onMouseLeave={hide} onFocus={show} onBlur={event=>{if(!event.currentTarget.contains(event.relatedTarget))hide()}} onKeyDown={event=>{if(event.key==='Escape')hide()}}>{children}{position&&media?createPortal(<aside id={id} role="tooltip" className="profile-hover-preview" style={position}>{media.mediaType==='video'?<StreamingVideo src={media.url} autoPlay muted playsInline loop preload="metadata" onError={hide}/>:<StreamingImage src={runtimeStoragePreviewUrl(media.url,640,75)} alt="Vista previa de la publicación" onError={hide}/>}<p>{text||'Vista previa de la publicación'}</p><small>Abre la publicación para verla completa.</small></aside>,document.body):null}</article>
}
