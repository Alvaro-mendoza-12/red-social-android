export type UploadProgress = { loaded:number; total:number; phase:'uploading'|'verifying' }
/** Uses the same authenticated request as fetch, with actual network byte progress. */
export function uploadWithProgress(url:string,init:RequestInit,progress:(value:UploadProgress)=>void):Promise<Response>{
 return new Promise((resolve,reject)=>{
  const xhr=new XMLHttpRequest();let done=false
  const clean=()=>init.signal?.removeEventListener('abort',abort)
  const fail=(error:unknown)=>{if(done)return;done=true;clean();reject(error)}
  const abort=()=>{fail(init.signal?.reason||new DOMException('Aborted','AbortError'));xhr.abort()}
  xhr.upload.onprogress=e=>{if(!done)progress({phase:'uploading',loaded:e.loaded,total:e.lengthComputable?e.total:0})}
  xhr.upload.onload=e=>{if(!done)progress({phase:'verifying',loaded:e.loaded,total:e.total})}
  xhr.onerror=()=>fail(new TypeError('Network request failed'))
  xhr.onabort=()=>fail(new DOMException('Aborted','AbortError'))
  xhr.ontimeout=()=>fail(new DOMException('Upload timed out','TimeoutError'))
  xhr.onload=()=>{if(done)return;const headers=new Headers();xhr.getAllResponseHeaders().trim().split(/[\r\n]+/).filter(Boolean).forEach(line=>{const n=line.indexOf(':');if(n>0)headers.append(line.slice(0,n),line.slice(n+1).trim())});try{const response=new Response([204,205,304].includes(xhr.status)?null:xhr.responseText,{status:xhr.status,statusText:xhr.statusText,headers});done=true;clean();resolve(response)}catch(error){fail(error)}}
  if(init.signal?.aborted){fail(init.signal.reason);return}
  init.signal?.addEventListener('abort',abort,{once:true})
  try{xhr.open(init.method||'POST',url);xhr.withCredentials=init.credentials==='include';new Headers(init.headers).forEach((value,key)=>xhr.setRequestHeader(key,value));xhr.send(init.body as XMLHttpRequestBodyInit)}catch(error){fail(error)}
 })
}
