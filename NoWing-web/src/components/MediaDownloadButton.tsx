import { useState } from 'react'
import { Download } from 'lucide-react'
import { downloadMedia } from '../core/media/downloadMedia'
export function MediaDownloadButton({url,name='',iconOnly=false}:{url:string;name?:string;iconOnly?:boolean}) {
 const [busy,setBusy]=useState(false),[failed,setFailed]=useState(false)
 return <span className="media-download-action"><button type="button" className={iconOnly?'icon-btn':'mini-action-btn'} aria-label="Descargar archivo" title="Descargar archivo" disabled={busy} onClick={()=>{if(busy)return;setBusy(true);setFailed(false);void downloadMedia(url,name).catch(()=>setFailed(true)).finally(()=>setBusy(false))}}><Download size={18}/>{!iconOnly?(busy?'Descargando…':'Descargar'):null}</button>{failed?<small role="status">No se pudo descargar. Inténtalo de nuevo.</small>:null}</span>
}
