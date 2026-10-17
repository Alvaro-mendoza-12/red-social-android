import {useSyncExternalStore} from 'react'
import {readOutboxProgress,subscribeOutboxProgress} from '../core/outboxProgress'
import {outboxOwner} from '../core/webOutbox'
export function OutboxTransferStatus({id}:{id:string}){
 const owner=outboxOwner(),value=useSyncExternalStore(subscribeOutboxProgress,()=>readOutboxProgress(owner,id),()=>null)
 const percent=value?.phase==='uploading'&&value.total?Math.min(100,Math.round((value.loaded||0)/value.total*100)):undefined
 const label=value?.phase==='uploading'?`Subiendo archivo${percent===undefined?'…':` · ${percent}%`}`:value?.phase==='verifying'?'Verificando archivo…':value?.phase==='sending'?'Enviando mensaje…':navigator.onLine?'Pendiente de envío…':'Pendiente · sin conexión'
 return <div className="outbox-transfer-status" role="status"><span>{label}</span><progress aria-label={label} max={100} {...(percent===undefined?{}:{value:percent})}/></div>
}
