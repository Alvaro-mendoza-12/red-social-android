import { useEffect, useRef, useState, type PointerEvent } from 'react'
import { RotateCw, FlipHorizontal, Crop, X, Check, Undo2 } from 'lucide-react'
import { cropSelection, type ImageSelection as Selection } from '../core/imageCrop'
const full: Selection = {x:0,y:0,w:1,h:1}
export function ChatImageEditor({ file, onSave, onClose, asSticker = false }: { file: File; onSave: (file: File) => void; onClose: () => void; asSticker?: boolean }) {
 const dialog=useRef<HTMLElement>(null),canvas=useRef<HTMLCanvasElement>(null),surface=useRef<HTMLDivElement>(null),source=useRef<HTMLImageElement|null>(null)
 const [rotation,setRotation]=useState(0),[flip,setFlip]=useState(false),[cropping,setCropping]=useState(false),[selection,setSelection]=useState(full),[ratio,setRatio]=useState(1),[text,setText]=useState(''),[busy,setBusy]=useState(false),[error,setError]=useState(''),[loadedFile,setLoadedFile]=useState<File|null>(null)
 const [textColor,setTextColor]=useState('#ffffff'),[textSize,setTextSize]=useState(5),[textX,setTextX]=useState(.5),[textY,setTextY]=useState(.85)
 const [drawing,setDrawing]=useState(false)
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
  if(!cropping)return;setDrawing(false);event.preventDefault();event.stopPropagation();event.currentTarget.setPointerCapture(event.pointerId);drag.current={mode,startX:event.clientX,startY:event.clientY,selection:mode==='draw'?{x:(event.clientX-surface.current!.getBoundingClientRect().left)/surface.current!.getBoundingClientRect().width,y:(event.clientY-surface.current!.getBoundingClientRect().top)/surface.current!.getBoundingClientRect().height,w:0,h:0}:selection}
 }
 function pointerMove(event:PointerEvent<HTMLElement>){
  const current=drag.current,rect=surface.current?.getBoundingClientRect();if(!current||!rect)return
  const dx=(event.clientX-current.startX)/rect.width,dy=(event.clientY-current.startY)/rect.height
  setSelection(cropSelection(current.selection,current.mode,dx,dy))
 }
 async function save(){
  const image=canvas.current;if(!loaded||!image||busy)return;setBusy(true);setError('')
  try{
   const s=cropping?selection:full,result=document.createElement('canvas');result.width=Math.max(1,Math.round(image.width*s.w));result.height=Math.max(1,Math.round(image.height*s.h));const ctx=result.getContext('2d')!;ctx.drawImage(image,image.width*s.x,image.height*s.y,image.width*s.w,image.height*s.h,0,0,result.width,result.height)
   if(text.trim()){ctx.font=`bold ${Math.max(18,Math.round(result.width*textSize/100))}px sans-serif`;ctx.textAlign='center';ctx.lineWidth=4;ctx.strokeStyle='#000';ctx.fillStyle=textColor;ctx.strokeText(text.trim(),result.width*textX,result.height*textY,result.width*.9);ctx.fillText(text.trim(),result.width*textX,result.height*textY,result.width*.9)}
   let output=result
   if(asSticker){output=document.createElement('canvas');output.width=512;output.height=512;const fit=Math.min(480/result.width,480/result.height);output.getContext('2d')!.drawImage(result,(512-result.width*fit)/2,(512-result.height*fit)/2,result.width*fit,result.height*fit)}
   const blob=await new Promise<Blob>((resolve,reject)=>output.toBlob(value=>value?resolve(value):reject(Error()),'image/png'));onSave(new File([blob],file.name.replace(/\.[^.]+$/,'')+(asSticker?'-sticker.png':'-editada.png'),{type:'image/png'}));onClose()
  }catch{setError('No se pudo guardar la imagen. Inténtalo de nuevo.')}finally{setBusy(false)}
 }
 return <div className="app-dialog-backdrop"><section ref={dialog} className="image-editor" role="dialog" aria-modal="true" aria-label="Editar imagen" onKeyDown={event=>{if(event.key==='Escape'){event.stopPropagation();onClose()}if(event.key==='Tab'){const nodes=dialog.current?.querySelectorAll<HTMLElement>('button:not(:disabled),input:not(:disabled)');const first=nodes?.[0],last=nodes?.[nodes.length-1];if(event.shiftKey&&document.activeElement===first){event.preventDefault();last?.focus()}else if(!event.shiftKey&&document.activeElement===last){event.preventDefault();first?.focus()}}}}>
  <header><h2>{asSticker?'Editar sticker':'Editar imagen'}</h2><button type="button" className="icon-btn" onClick={onClose} aria-label="Cerrar editor"><X/></button></header>
  <div className="image-editor-preview crop-preview"><div ref={surface} className="image-crop-surface" style={{aspectRatio:ratio,width:`min(100%, ${ratio*30}dvh)`}} onPointerDown={event=>pointerDown(event,'draw')} onPointerMove={pointerMove} onPointerUp={()=>{drag.current=null}} onPointerCancel={()=>{drag.current=null}}><canvas ref={canvas} aria-label="Imagen para enviar"/>{cropping?<div className="image-crop-selection" style={{left:`${selection.x*100}%`,top:`${selection.y*100}%`,width:`${selection.w*100}%`,height:`${selection.h*100}%`,pointerEvents:drawing?'none':'auto'}} onPointerDown={event=>pointerDown(event,'move')} onPointerMove={pointerMove} onPointerUp={()=>{drag.current=null}}><span className="crop-grid"/>{['nw','n','ne','e','se','s','sw','w'].map(corner=><button type="button" className={`crop-handle ${corner}`} key={corner} aria-label={`Ajustar recorte ${corner}`} onKeyDown={event=>{const delta=event.shiftKey ? .05 : .01;if(['ArrowLeft','ArrowRight','ArrowUp','ArrowDown'].includes(event.key)){event.preventDefault();setSelection(s=>cropSelection(s,corner,event.key==='ArrowLeft'?-delta:event.key==='ArrowRight'?delta:0,event.key==='ArrowUp'?-delta:event.key==='ArrowDown'?delta:0))}}} onPointerDown={event=>pointerDown(event,corner)}/>)}</div>:null}{text?<strong style={{left:`${((cropping?selection.x:0)+textX*(cropping?selection.w:1))*100}%`,top:`${((cropping?selection.y:0)+textY*(cropping?selection.h:1))*100}%`,color:textColor,fontSize:`${textSize*(cropping?selection.w:1)}cqw`}}>{text}</strong>:null}</div></div>
  <div className="image-editor-controls">{asSticker?<small>Se guardará un sticker fijo de 512 × 512 px con margen transparente.</small>:null}<nav><button type="button" className="ghost-btn" aria-pressed={cropping} onClick={()=>{setCropping(value=>!value);setDrawing(true)}}><Crop/>Recortar</button>{cropping?<button type="button" className="ghost-btn" aria-pressed={drawing} onClick={()=>setDrawing(true)}>Nuevo recorte</button>:null}<button type="button" className="ghost-btn" onClick={()=>{setRotation(value=>(value+90)%360);setSelection(full)}}><RotateCw/>Girar</button><button type="button" className="ghost-btn" aria-pressed={flip} onClick={()=>setFlip(value=>!value)}><FlipHorizontal/>Reflejar</button><button type="button" className="ghost-btn" onClick={()=>{setRotation(0);setFlip(false);setSelection(full);setCropping(false);setText('');setTextColor('#ffffff');setTextSize(5);setTextX(.5);setTextY(.85)}}><Undo2/>Restablecer</button></nav>
  {cropping?<div className="image-crop-options"><p>Dibuja un recorte nuevo sobre la imagen. Arrastra sus bordes o mueve el recuadro libremente.</p><label>Ancho del recorte<input type="range" min="1" max={Math.round((1-selection.x)*100)} value={Math.round(selection.w*100)} onChange={e=>setSelection(s=>({...s,w:Number(e.target.value)/100}))}/></label><label>Alto del recorte<input type="range" min="1" max={Math.round((1-selection.y)*100)} value={Math.round(selection.h*100)} onChange={e=>setSelection(s=>({...s,h:Number(e.target.value)/100}))}/></label></div>:null}
  <label>Texto sobre la imagen<input className="text-input" maxLength={100} value={text} onChange={event=>setText(event.target.value)}/></label>{text?<div className="image-text-controls"><label>Color del texto<input type="color" value={textColor} onChange={e=>setTextColor(e.target.value)}/></label><label>Tamaño<input type="range" min="2" max="14" value={textSize} onChange={e=>setTextSize(Number(e.target.value))}/></label><label>Posición horizontal<input type="range" min="5" max="95" value={textX*100} onChange={e=>setTextX(Number(e.target.value)/100)}/></label><label>Posición vertical<input type="range" min="8" max="95" value={textY*100} onChange={e=>setTextY(Number(e.target.value)/100)}/></label></div>:null}{error?<p role="alert">{error}</p>:null}<button type="button" className="primary-btn" disabled={busy||!loaded} onClick={()=>void save()}><Check/>{busy?'Guardando…':asSticker?'Usar sticker':'Usar imagen'}</button></div>
 </section></div>
}
