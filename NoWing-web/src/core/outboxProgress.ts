export type OutboxProgress = {owner:string;id:string;phase:'uploading'|'verifying'|'sending'|'waiting';loaded?:number;total?:number}
const values=new Map<string,OutboxProgress>(),listeners=new Set<()=>void>()
let channel:BroadcastChannel|undefined
function accept(value:OutboxProgress|null,key:string){if(value)values.set(key,value);else values.delete(key);listeners.forEach(fn=>fn())}
function connect(){if(!channel&&typeof BroadcastChannel!=='undefined'){channel=new BroadcastChannel('novvin-outbox-progress-v1');channel.onmessage=event=>{const {key,value}=event.data||{};if(typeof key!=='string'||key.length>600)return;if(value===null){accept(null,key);return}if(value&&typeof value.owner==='string'&&typeof value.id==='string'&&key===value.owner+'/'+value.id&&['uploading','verifying','sending','waiting'].includes(value.phase)&&[value.loaded,value.total].every(n=>n===undefined||typeof n==='number'&&Number.isFinite(n)&&n>=0))accept(value,key)}}}
export function setOutboxProgress(value:OutboxProgress){connect();const key=value.owner+'/'+value.id;accept(value,key);channel?.postMessage({key,value})}
export function clearOutboxProgress(owner:string,id?:string){connect();for(const key of [...values.keys()])if(key.startsWith(owner+'/')&&(!id||key===owner+'/'+id)){accept(null,key);channel?.postMessage({key,value:null})}}
export function subscribeOutboxProgress(fn:()=>void){connect();listeners.add(fn);return()=>{listeners.delete(fn)}}
export function readOutboxProgress(owner:string,id:string){return values.get(owner+'/'+id)||null}
