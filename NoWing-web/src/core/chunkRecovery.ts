const reloadKey='novvin:module-recovery'
export function isChunkLoadError(error:unknown){
  const message=error instanceof Error?error.message:String(error||'')
  return /Failed to fetch dynamically imported module|Importing a module script failed|error loading dynamically imported module|Loading chunk [\w-]+ failed|Unable to preload CSS/i.test(message)
}
let reloadBlocked=()=>false
export function setChunkReloadGuard(guard:()=>boolean){reloadBlocked=guard}
/** One recovery in a five minute window; never loop or interrupt active media/drafts. */
export function recoverChunkLoad(error:unknown,options:{now?:number;storage?:Storage;reload?:()=>void;online?:boolean;blocked?:boolean}={}){
  if(!isChunkLoadError(error)||(options.online??navigator.onLine)===false||(options.blocked??reloadBlocked()))return false
  const now=options.now??Date.now(),storage=options.storage??sessionStorage
  try{const last=Number(storage.getItem(reloadKey));if(last&&now-last<300000)return false;storage.setItem(reloadKey,String(now))}catch{return false}
  ;(options.reload??(()=>location.reload()))();return true
}
export function startupFailure(error:unknown){
  if(recoverChunkLoad(error))return
  const node=document.getElementById('root');if(!node)return
  node.replaceChildren()
  const section=document.createElement('section'),title=document.createElement('h1'),description=document.createElement('p'),button=document.createElement('button')
  section.style.cssText='max-width:560px;margin:10vh auto;padding:24px;font:16px system-ui;line-height:1.6'
  title.textContent='No pudimos cargar Novvin';description.textContent=navigator.onLine?'Hay una actualización disponible o falló la conexión. Recarga para volver a intentarlo.':'Revisa tu conexión y vuelve a intentarlo.'
  button.textContent='Recargar aplicación';button.onclick=()=>location.reload();section.append(title,description,button);node.append(section)
}
