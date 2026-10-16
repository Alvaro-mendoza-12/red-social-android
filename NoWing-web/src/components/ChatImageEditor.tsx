import { useEffect, useRef, useState, type PointerEvent } from 'react'
import { RotateCw, FlipHorizontal, Crop, X, Check, Undo2 } from 'lucide-react'
type Selection = { x: number; y: number; w: number; h: number }
const full: Selection = {x:0,y:0,w:1,h:1}
export function ChatImageEditor({ file, onSave, onClose }: { file: File; onSave: (file: File) => void; onClose: () => void }) {
 const dialog=useRef<HTMLElement>(null),canvas=useRef<HTMLCanvasElement>(null),surface=useRef<HTMLDivElement>(null),source=useRef<HTMLImageElement|null>(null)
 const [rotation,setRotation]=useState(0),[flip,setFlip]=useState(false),[cropping,setCropping]=useState(false),[selection,setSelection]=useState(full),[ratio,setRatio]=useState(1),[text,setText]=useState(''),[busy,setBusy]=useState(false),[error,setError]=useState(''),[loadedFile,setLoadedFile]=useState<File|null>(null)
 const loaded=loadedFile===file
 const drag=useRef<{mode:string;startX:number;startY:number;selection:Selection}|null>(null)
 useEffect(()=>{const previous=document.activeElement as HTMLElement|null;dialog.current?.querySelector<HTMLButtonElement>('button')?.focus();return()=>{if(previous?.isConnected)previous.focus()}},[])
 useEffect(()=>{
  let active=true;const url=URL.createObjectURL(file),image=new Image()
  image.onload=()=>{if(active){source.current=image;setLoadedFile(file)}};image.onerror=()=>{if(active)setError('No se pudo abrir esta imagen.')};image.src=url
  return()=>{active=false;source.current=null;URL.revokeObjectURL(url)}
 },[file])
 useEffect(()=>{
  const image=source.current,target=canvas.current;if(!loaded||!image||!target)return
  const turns=rotation%180!==0,scale=Math.min(1,4096/Math.max(image.naturalWidth,image.naturalHeight)),w=image.naturalWidth*scale,h=image.naturalHeight*scale
  target.width=Math.round(turns?h:w);target.height=Math.round(turns?w:h)
  const ctx=target.getContext('2d');if(!ctx)return
  ctx.translate(target.width/2,target.height/2);ctx.rotate(rotation*Math.PI/180);ctx.scale(flip?-1:1,1);ctx.drawImage(image,-w/2,-h/2,w,h);ctx.setTransform(1,0,0,1,0,0)
  setRatio(target.width/target.height)
 },[rotation,flip,loaded,loadedFile])
 function pointerDown(event:PointerEvent<HTMLElement>,mode:string){
  if(!cropping)return;event.preventDefault();event.stopPropagation();event.currentTarget.setPointerCapture(event.pointerId);drag.current={mode,startX:event.clientX,startY:event.clientY,selection}
 }
 function pointerMove(event:PointerEvent<HTMLElement>){
  const current=drag.current,rect=surface.current?.getBoundingClientRect();if(!current||!rect)return
  const dx=(event.clientX-current.startX)/rect.width,dy=(event.clientY-current.startY)/rect.height,s=current.selection,clamp=(v:number,min:number,max:number)=>Math.max(min,Math.min(max,v))
  if(current.mode==='move'){setSelection({...s,x:clamp(s.x+dx,0,1-s.w),y:clamp(s.y+dy,0,1-s.h)});return}
  const left=current.mode.includes('w')?clamp(s.x+dx,0,s.x+s.w-.06):s.x,right=current.mode.includes('e')?clamp(s.x+s.w+dx,s.x+.06,1):s.x+s.w
  const top=current.mode.includes('n')?clamp(s.y+dy,0,s.y+s.h-.06):s.y,bottom=current.mode.includes('s')?clamp(s.y+s.h+dy,s.y+.06,1):s.y+s.h
  setSelection({x:left,y:top,w:right-left,h:bottom-top})
 }
 async function save(){
  const image=canvas.current;if(!loaded||!image||busy)return;setBusy(true);setError('')
  try{
   const s=cropping?selection:full,result=document.createElement('canvas');result.width=Math.max(1,Math.round(image.width*s.w));result.height=Math.max(1,Math.round(image.height*s.h));const ctx=result.getContext('2d')!;ctx.drawImage(image,image.width*s.x,image.height*s.y,image.width*s.w,image.height*s.h,0,0,result.width,result.height)
   if(text.trim()){ctx.font=`bold ${Math.max(18,Math.round(result.width/20))}px sans-serif`;ctx.textAlign='center';ctx.lineWidth=4;ctx.strokeStyle='#000';ctx.fillStyle='#fff';ctx.strokeText(text.trim(),result.width/2,result.height*.85,result.width*.9);ctx.fillText(text.trim(),result.width/2,result.height*.85,result.width*.9)}
   const blob=await new Promise<Blob>((resolve,reject)=>result.toBlob(value=>value?resolve(value):reject(Error()),'image/png'));onSave(new File([blob],file.name.replace(/\.[^.]+$/,'')+'-editada.png',{type:'image/png'}));onClose()
  }catch{setError('No se pudo guardar la imagen. Inténtalo de nuevo.')}finally{setBusy(false)}
 }
 return <div className="app-dialog-backdrop"><section ref={dialog} className="image-editor" role="dialog" aria-modal="true" aria-label="Editar imagen" onKeyDown={event=>{if(event.key==='Escape'){event.stopPropagation();onClose()}if(event.key==='Tab'){const nodes=dialog.current?.querySelectorAll<HTMLElement>('button:not(:disabled),input:not(:disabled)');const first=nodes?.[0],last=nodes?.[nodes.length-1];if(event.shiftKey&&document.activeElement===first){event.preventDefault();last?.focus()}else if(!event.shiftKey&&document.activeElement===last){event.preventDefault();first?.focus()}}}}>
  <header><h2>Editar imagen</h2><button type="button" className="icon-btn" onClick={onClose} aria-label="Cerrar editor"><X/></button></header>
  <div className="image-editor-preview crop-preview"><div ref={surface} className="image-crop-surface" style={{aspectRatio:ratio,width:`min(100%, ${ratio*42}dvh)`}}><canvas ref={canvas} aria-label="Imagen para enviar"/>{cropping?<div className="image-crop-selection" style={{left:`${selection.x*100}%`,top:`${selection.y*100}%`,width:`${selection.w*100}%`,height:`${selection.h*100}%`}} onPointerDown={event=>pointerDown(event,'move')} onPointerMove={pointerMove} onPointerUp={()=>{drag.current=null}}><span className="crop-grid"/>{['nw','ne','sw','se'].map(corner=><button type="button" className={`crop-handle ${corner}`} key={corner} aria-label={`Mover esquina ${corner}`} onPointerDown={event=>pointerDown(event,corner)}/>)}</div>:null}{text?<strong>{text}</strong>:null}</div></div>
  <nav><button type="button" className="ghost-btn" aria-pressed={cropping} onClick={()=>{setCropping(value=>!value);setSelection({x:.1,y:.1,w:.8,h:.8})}}><Crop/>Recortar</button><button type="button" className="ghost-btn" onClick={()=>{setRotation(value=>(value+90)%360);setSelection(full)}}><RotateCw/>Girar</button><button type="button" className="ghost-btn" aria-pressed={flip} onClick={()=>setFlip(value=>!value)}><FlipHorizontal/>Reflejar</button><button type="button" className="ghost-btn" onClick={()=>{setRotation(0);setFlip(false);setSelection(full);setCropping(false);setText('')}}><Undo2/>Restablecer</button></nav>
  {cropping?<div className="image-crop-options"><p>Arrastra el recuadro o sus esquinas para elegir qué parte enviar.</p><label>Ancho del recorte<input type="range" min="6" max={Math.round((1-selection.x)*100)} value={Math.round(selection.w*100)} onChange={e=>setSelection(s=>({...s,w:Number(e.target.value)/100}))}/></label><label>Alto del recorte<input type="range" min="6" max={Math.round((1-selection.y)*100)} value={Math.round(selection.h*100)} onChange={e=>setSelection(s=>({...s,h:Number(e.target.value)/100}))}/></label></div>:null}
  <label>Texto sobre la imagen<input className="text-input" maxLength={100} value={text} onChange={event=>setText(event.target.value)}/></label>{error?<p role="alert">{error}</p>:null}<button type="button" className="primary-btn" disabled={busy||!loaded} onClick={()=>void save()}><Check/>{busy?'Guardando…':'Usar imagen'}</button>
 </section></div>
}
