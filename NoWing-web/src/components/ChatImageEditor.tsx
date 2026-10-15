import { useEffect, useRef, useState } from 'react'
import { RotateCw, FlipHorizontal, Crop, X, Check } from 'lucide-react'
export function ChatImageEditor({ file, onSave, onClose }: { file: File; onSave: (file: File) => void; onClose: () => void }) {
  const dialog = useRef<HTMLElement>(null)
  useEffect(() => { const previous = document.activeElement as HTMLElement | null; dialog.current?.querySelector<HTMLButtonElement>('button')?.focus(); return () => { if (previous?.isConnected) previous.focus() } }, [])
  const image = useRef<HTMLImageElement>(null), [url, setUrl] = useState(''), [rotation, setRotation] = useState(0), [flip, setFlip] = useState(false), [square, setSquare] = useState(false), [text, setText] = useState(''), [busy,setBusy] = useState(false), [error,setError] = useState('')
  useEffect(() => { const source = URL.createObjectURL(file); setUrl(source); return () => URL.revokeObjectURL(source) }, [file])
  async function save() {
    const source = image.current; if (!source?.naturalWidth || busy) return
    setBusy(true); setError('')
    try {
      const turns = rotation % 180 !== 0, originalW = source.naturalWidth, originalH = source.naturalHeight, side = Math.min(originalW,originalH)
      const width = square ? side : originalW, height = square ? side : originalH, factor = Math.min(1, 4096 / Math.max(width,height))
      const canvas = document.createElement('canvas'); canvas.width = Math.round((turns ? height : width)*factor); canvas.height = Math.round((turns ? width : height)*factor)
      const ctx = canvas.getContext('2d')!; ctx.translate(canvas.width/2,canvas.height/2); ctx.rotate(rotation*Math.PI/180); ctx.scale(flip ? -factor : factor,factor)
      ctx.drawImage(source,(originalW-width)/2,(originalH-height)/2,width,height,-width/2,-height/2,width,height); ctx.setTransform(1,0,0,1,0,0)
      if(text.trim()){ctx.font=`bold ${Math.max(18,Math.round(canvas.width/20))}px sans-serif`;ctx.textAlign='center';ctx.lineWidth=4;ctx.strokeStyle='#000';ctx.fillStyle='#fff';ctx.strokeText(text.trim(),canvas.width/2,canvas.height*.85,canvas.width*.9);ctx.fillText(text.trim(),canvas.width/2,canvas.height*.85,canvas.width*.9)}
      const blob = await new Promise<Blob>((resolve,reject) => canvas.toBlob(value=>value?resolve(value):reject(new Error()),'image/png'))
      onSave(new File([blob],file.name.replace(/\.[^.]+$/,'')+'-editada.png',{type:'image/png'}));onClose()
    }catch{setError('No se pudo guardar la imagen. Inténtalo de nuevo.')}finally{setBusy(false)}
  }
  return <div className="app-dialog-backdrop"><section ref={dialog} onKeyDown={event => { if (event.key === 'Escape') { event.stopPropagation(); onClose() }; if (event.key === 'Tab') { const nodes = dialog.current?.querySelectorAll<HTMLElement>('button:not(:disabled),input:not(:disabled)'); const first = nodes?.[0], last = nodes?.[nodes.length-1]; if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last?.focus() } else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first?.focus() } } }} className="image-editor" role="dialog" aria-modal="true" aria-label="Editar imagen"><header><h2>Editar imagen</h2><button type="button" className="icon-btn" onClick={onClose} aria-label="Cerrar editor"><X/></button></header><div className={`image-editor-preview${square?' square':''}`}><img ref={image} src={url||undefined} alt="Imagen para enviar" style={{transform:`rotate(${rotation}deg) scaleX(${flip?-1:1})`}}/>{text?<strong>{text}</strong>:null}</div><nav><button type="button" className="ghost-btn" onClick={()=>setRotation(value=>(value+90)%360)}><RotateCw/>Girar</button><button type="button" className="ghost-btn" aria-pressed={flip} onClick={()=>setFlip(value=>!value)}><FlipHorizontal/>Reflejar</button><button type="button" className="ghost-btn" aria-pressed={square} onClick={()=>setSquare(value=>!value)}><Crop/>Cuadrado</button></nav><label>Texto sobre la imagen<input className="text-input" maxLength={100} value={text} onChange={event=>setText(event.target.value)}/></label>{error?<p role="alert">{error}</p>:null}<button type="button" className="primary-btn" disabled={busy} onClick={()=>void save()}><Check/>{busy?'Guardando…':'Usar imagen'}</button></section></div>
}
