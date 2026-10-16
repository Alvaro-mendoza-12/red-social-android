import { File, Film, Image, Mic } from 'lucide-react'
import { StreamingImage } from './StreamingMedia'
import { runtimeStoragePreviewUrl } from '../core/runtimeBackend'
export function MessageReplyPreview({message,name='Mensaje'}:{message:{id:string;content:string;contentType:string;mediaUrl:string|null;deletedForAll:boolean};name?:string}) {
 const kind=message.contentType,photo=kind==='image'||kind.startsWith('image/'),video=kind==='video'||kind.startsWith('video/'),audio=kind==='audio'||kind.startsWith('audio/'),deleted=message.deletedForAll
 const label=deleted?'Mensaje eliminado':photo?'Foto':video?'Video':audio?'Audio':kind==='sticker'?'Sticker':message.mediaUrl?'Archivo':message.content
 return <button className="reply-preview message-reply-preview" type="button" aria-label="Ir al mensaje respondido" onClick={()=>{const node=document.getElementById(`message-${message.id}`);node?.scrollIntoView({block:'center',behavior:'smooth'})}}><span><strong>{name}</strong><small>{!deleted&&(photo?<Image size={14}/>:video?<Film size={14}/>:audio?<Mic size={14}/>:message.mediaUrl?<File size={14}/>:null)}{label}</small></span>{!deleted&&photo&&message.mediaUrl?<StreamingImage src={runtimeStoragePreviewUrl(message.mediaUrl,120)} alt="Foto respondida" loading="lazy"/>:null}</button>
}
