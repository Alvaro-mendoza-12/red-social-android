import { Folder } from 'lucide-react'
export function FolderAttachmentPicker({onFiles}:{onFiles:(files:FileList|null)=>void}) {
 return <label className="chat-attachment-option" tabIndex={0} role="button" aria-label="Adjuntar carpeta" title="Comprimir y adjuntar carpeta" onKeyDown={event=>{if(event.key==='Enter'||event.key===' '){event.preventDefault();event.currentTarget.querySelector<HTMLInputElement>('input')?.click()}}}><Folder size={22}/><span>Carpeta como ZIP</span><input type="file" multiple hidden {...{webkitdirectory:'',directory:''}} onChange={event=>{onFiles(event.currentTarget.files);event.currentTarget.value='';event.currentTarget.closest('details')?.removeAttribute('open')}}/></label>
}
