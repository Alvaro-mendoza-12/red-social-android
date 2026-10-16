import { browserStreamingSource } from './browserStreaming'
import { getStorageReadIdentity, onStorageReadInvalidated, requireStorageReadIdentity } from './storageReadSession'
import { trustedStorageBackend } from './storageReadClient'
import { rewriteRuntimeStorageUrl } from '../runtimeBackend'

export function originalFileName(url: string, label = '') {
 const clean=(value:string)=>value.replace(/[<>:"/\\|?*]/g,'_').split('').filter(c=>c.charCodeAt(0)>=32&&c.charCodeAt(0)!==127).join('').trim().slice(0,180)
 if(/\.[a-z0-9]{1,10}$/i.test(label.trim()))return clean(label)
 try {const name=decodeURIComponent(new URL(url,location.origin).pathname.split('/').at(-1)||'');if(/\.[a-z0-9]{1,10}$/i.test(name))return clean(name)}catch{/* Use a neutral attachment name. */}
 return clean(label)||'archivo'
}

/** Fetch from the page's authorized client: browser download navigations have no clientId. */
export async function downloadMedia(raw:string,label='') {
 const source=rewriteRuntimeStorageUrl(raw),identity=getStorageReadIdentity(),controller=new AbortController()
 const name=originalFileName(source,label),protectedSource=Boolean(trustedStorageBackend(source))
 const guard=()=>{if(identity.accountId)requireStorageReadIdentity(identity)}
 const unsubscribe=onStorageReadInvalidated(()=>controller.abort())
 try {
  if(!/^(https?:|blob:|\/__novvin_media\/)/i.test(source))throw Error('INVALID_DOWNLOAD')
  guard()
  const url=protectedSource?await browserStreamingSource(source,identity):source
  const response=await fetch(url,{signal:controller.signal,credentials:'omit',cache:'no-store',redirect:'error'})
  if(!response.ok)throw Error('DOWNLOAD_FAILED')
  const maximum=128*1024*1024
  if(Number(response.headers.get('Content-Length'))>maximum){await response.body?.cancel();throw Error('DOWNLOAD_TOO_LARGE')}
  const parts:Uint8Array<ArrayBuffer>[]=[],reader=response.body?.getReader();let bytes=0
  if(!reader)throw Error('DOWNLOAD_EMPTY')
  try {while(true){const chunk=await reader.read();if(chunk.done)break;guard();bytes+=chunk.value.byteLength;if(bytes>maximum)throw Error('DOWNLOAD_TOO_LARGE');parts.push(new Uint8Array(chunk.value))}}finally{await reader.cancel().catch(()=>{});reader.releaseLock()}
  guard()
  const blob=new Blob(parts,{type:'application/octet-stream'}),objectUrl=URL.createObjectURL(blob)
  const link=document.createElement('a');link.href=objectUrl;link.download=name;link.rel='noopener noreferrer';document.body.append(link);link.click();link.remove()
  window.setTimeout(()=>URL.revokeObjectURL(objectUrl),60000)
  return {name,bytes}
 }finally{unsubscribe()}
}
