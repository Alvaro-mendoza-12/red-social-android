import { constrainPanel } from '../core/floatingPanel'
import { useEffect, useRef, useState, type ReactNode } from 'react'
import { GripVertical } from 'lucide-react'
type Point={x:number;y:number}
export function MovableVoicePanel({accountId,children}:{accountId:string;children:ReactNode}){
  const storageKey=`novvin:voice-panel:${accountId}`,node=useRef<HTMLElement>(null),drag=useRef<{id:number;start:Point;origin:Point}|null>(null)
  const [point,setPoint]=useState<Point|null>(()=>{try{const saved=JSON.parse(sessionStorage.getItem(storageKey)||'null');return Number.isFinite(saved?.x)&&Number.isFinite(saved?.y)?saved:null}catch{return null}})
  const [menu,setMenu]=useState(false), moved=useRef(false)
  const move=(value:Point)=>{const rect=node.current?.getBoundingClientRect();if(!rect)return;setPoint(constrainPanel(value,rect.width,rect.height,innerWidth,innerHeight))}
  useEffect(()=>{if(point)try{sessionStorage.setItem(storageKey,JSON.stringify(point))}catch{/* Position persistence is optional. */}},[point,storageKey])
  useEffect(()=>{const clamp=()=>{const rect=node.current?.getBoundingClientRect();if(!rect)return;setPoint(current=>current?constrainPanel(current,rect.width,rect.height,innerWidth,innerHeight):null)};clamp();window.addEventListener('resize',clamp);return()=>window.removeEventListener('resize',clamp)},[])
  return <aside ref={node} className="voice-floating-controls" aria-label="Sala de voz activa" style={point?{left:point.x,top:point.y,right:'auto',bottom:'auto'}:undefined}>
    <button className="voice-panel-drag" type="button" aria-label="Mover indicador de sala" title="Arrastra o usa las flechas para mover. Clic para elegir posición." aria-expanded={menu} onClick={()=>{if(moved.current){moved.current=false;return}if(!drag.current)setMenu(value=>!value)}}
      onKeyDown={event=>{const directions:Record<string,Point>={ArrowLeft:{x:-24,y:0},ArrowRight:{x:24,y:0},ArrowUp:{x:0,y:-24},ArrowDown:{x:0,y:24}};const delta=directions[event.key],rect=node.current?.getBoundingClientRect();if(delta&&rect){event.preventDefault();move({x:rect.x+delta.x,y:rect.y+delta.y})}if(event.key==='Escape')setMenu(false)}}
      onPointerDown={event=>{if(event.button!==0)return;moved.current=false;const rect=node.current?.getBoundingClientRect();if(!rect)return;drag.current={id:event.pointerId,start:{x:event.clientX,y:event.clientY},origin:{x:rect.x,y:rect.y}};event.currentTarget.setPointerCapture(event.pointerId)}}
      onPointerMove={event=>{const value=drag.current;if(!value||value.id!==event.pointerId)return;const x=event.clientX-value.start.x,y=event.clientY-value.start.y;if(Math.abs(x)+Math.abs(y)>3){event.preventDefault();moved.current=true;setMenu(false);move({x:value.origin.x+x,y:value.origin.y+y})}}}
      onPointerUp={event=>{const value=drag.current;drag.current=null;if(value&&Math.abs(event.clientX-value.start.x)+Math.abs(event.clientY-value.start.y)>3){event.preventDefault();event.currentTarget.releasePointerCapture(event.pointerId)}}} onPointerCancel={()=>{drag.current=null}}><GripVertical size={19}/></button>
    {children}
    {menu?<div className="voice-panel-positions" style={point&&point.y<240?{top:'calc(100% + 6px)',bottom:'auto'}:undefined} aria-label="Posición del indicador">{[['Arriba izquierda',8,8],['Arriba derecha',innerWidth,8],['Abajo izquierda',8,innerHeight-90],['Abajo derecha',innerWidth,innerHeight-90]].map(([label,x,y])=><button type="button" key={String(label)} onClick={()=>{move({x:Number(x),y:Number(y)});setMenu(false)}}>{label}</button>)}<button type="button" onClick={()=>{setPoint(null);sessionStorage.removeItem(storageKey);setMenu(false)}}>Restablecer posición</button></div>:null}
  </aside>
}
