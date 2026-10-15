import { useEffect, useState } from 'react'
import { Square, Trash2 } from 'lucide-react'
export function RecordingIndicator({ onStop, onCancel }: { onStop: () => void; onCancel: () => void }) {
  const [seconds,setSeconds] = useState(0)
  useEffect(()=>{const start=Date.now();const timer=window.setInterval(()=>setSeconds(Math.floor((Date.now()-start)/1000)),250);return()=>window.clearInterval(timer)},[])
  return <div className="recording-indicator" role="status" aria-label="Grabando audio"><span className="recording-dot"/><strong>Grabando</strong><time>{Math.floor(seconds/60)}:{String(seconds%60).padStart(2,'0')}</time><span className="recording-waves" aria-hidden="true">{Array.from({length:12},(_,index)=><i key={index} style={{animationDelay:`${index*.08}s`}}/>)}</span><button type="button" className="icon-btn" aria-label="Descartar grabación" onClick={onCancel}><Trash2/></button><button type="button" className="icon-btn" aria-label="Terminar grabación" onClick={onStop}><Square/></button></div>
}
