import { useEffect, useRef, useState } from 'react'
import { X } from 'lucide-react'
import { PublicComments } from './PublicComments'
export function VideoCommentsPanel({videoId,onClose,onCountChange}:{videoId:string;onClose:()=>void;onCountChange?:(count:number)=>void}) {
  const panel=useRef<HTMLElement>(null),[count,setCount]=useState(0),callback=useRef(onCountChange)
  useEffect(()=>{callback.current=onCountChange},[onCountChange])
  useEffect(()=>{
    const previous=document.activeElement as HTMLElement|null,node=panel.current,feed=node?.parentElement?.querySelector<HTMLElement>('.reels-vertical-feed')
    node?.focus({preventScroll:true})
    return()=>{const target=previous&&feed?.contains(previous)?feed:previous;if(target?.isConnected)target.focus({preventScroll:true})}
  },[])
  return <aside ref={panel} tabIndex={-1} className="video-comments-panel" role="dialog" aria-label="Comentarios del video" onKeyDown={event=>{if(event.key==='Escape')onClose()}}><header><h2>Comentarios <small>{count}</small></h2><button type="button" className="icon-btn" aria-label="Cerrar comentarios" onClick={onClose}><X/></button></header><PublicComments kind="video" targetId={videoId} onCountChange={value=>{setCount(value);callback.current?.(value)}}/></aside>
}
