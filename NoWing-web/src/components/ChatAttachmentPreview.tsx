import { useEffect, useRef, useState, type FormEvent } from 'react'
import { FileText, Pencil, Plus, Send, Sticker, X } from 'lucide-react'
import { StreamingImage, StreamingVideo } from './StreamingMedia'
import { isStickerImage } from '../core/chatClipboard'
import { SpellingSuggestions } from './SpellingSuggestions'
import './chatAttachmentPreview.css'

export type AttachmentDraft = { id: string; file: File; contentType: 'image'|'video'|'audio'|'file'|'sticker'; previewUrl: string; caption: string }
type Props = {
  items: AttachmentDraft[]; draft: string; busy: boolean; preparing: boolean; status: string
  onCaption: (id: string, text: string) => void; onRemove: (id: string) => void; onClose: () => void
  onAdd: (files: FileList|null) => void; onEdit: (id: string) => void; onToggleSticker: (id: string) => void
  onSend: (event: FormEvent<HTMLFormElement>) => void
}
export function ChatAttachmentPreview({items,draft,busy,preparing,status,onCaption,onRemove,onClose,onAdd,onEdit,onToggleSticker,onSend}:Props) {
  const tail=items[items.length-1]?.id
  const [selected,setSelected]=useState({id:tail,tail}),[caret,setCaret]=useState<number|undefined>()
  const active=items.find(item=>item.id===(selected.tail===tail?selected.id:tail))||items[items.length-1]
  const fileInput=useRef<HTMLInputElement>(null),caption=useRef<HTMLTextAreaElement>(null)
  useEffect(()=>{const previous=document.activeElement as HTMLElement|null;caption.current?.focus();return()=>{if(previous?.isConnected)previous.focus()}},[])
  if(!active)return null
  const first=active.id===items[0]?.id, image=active.contentType==='image'||active.contentType==='sticker'
  return <section className="chat-attachment-preview" aria-label="Vista previa de adjuntos" onKeyDown={event=>{if(event.key==='Escape'&&!busy){event.stopPropagation();onClose()}}}>
    <header><button className="icon-btn" type="button" aria-label="Cancelar adjuntos" disabled={busy} onClick={onClose}><X aria-hidden="true"/></button><div><strong>{active.contentType==='sticker'?'Preparar sticker':'Vista previa'}</strong><small>{items.findIndex(item=>item.id===active.id)+1} de {items.length} · {active.file.name}</small></div><nav aria-label="Editar adjunto">{image?<><button className="ghost-btn" type="button" aria-label="Editar" disabled={busy} onClick={()=>onEdit(active.id)}><Pencil size={18} aria-hidden="true"/><span>Editar</span></button><button className="ghost-btn" type="button" aria-label={active.contentType==='sticker'?'Como sticker':'Enviar como sticker'} aria-pressed={active.contentType==='sticker'} disabled={busy||!isStickerImage(active.file)} onClick={()=>onToggleSticker(active.id)}><Sticker size={18} aria-hidden="true"/><span>{active.contentType==='sticker'?'Como sticker':'Enviar como sticker'}</span></button></>:null}</nav></header>
    <div className={`attachment-main-preview${active.contentType==='sticker'?' sticker-stage':''}`}>
      {image?<StreamingImage key={active.previewUrl} src={active.previewUrl} alt={active.file.name}/>:active.contentType==='video'?<StreamingVideo key={active.previewUrl} src={active.previewUrl} controls preload="metadata"/>:active.contentType==='audio'?<audio key={active.previewUrl} src={active.previewUrl} controls/>:<div className="attachment-file-card"><FileText size={56}/><strong>{active.file.name}</strong><small>{(active.file.size/1024).toLocaleString(undefined,{maximumFractionDigits:1})} KB</small></div>}
    </div>
    {active.contentType==='sticker' && /^image\/(png|jpeg)$/.test(active.file.type) ? <small className="attachment-preview-status">Si el sticker se ve fijo, adjunta su archivo GIF o WebP original para conservar el movimiento.</small> : null}
    <form className="attachment-preview-footer" onSubmit={event=>{if(busy||preparing){event.preventDefault();return}onSend(event)}}>
      <SpellingSuggestions text={active.caption||(first?draft:'')} caret={caret} onReplace={(text,position)=>{onCaption(active.id,text);setCaret(position);requestAnimationFrame(()=>{caption.current?.focus();caption.current?.setSelectionRange(position,position)})}}/>
      <label className="attachment-caption"><span className="sr-only">{active.contentType==='sticker'?'Nombre del sticker':'Mensaje del adjunto'}</span><textarea ref={caption} onSelect={event=>setCaret(event.currentTarget.selectionStart)} className="chat-input-field" rows={1} lang="es" spellCheck autoCorrect="on" autoCapitalize="sentences" placeholder={active.contentType==='sticker'?'Nombre del sticker (opcional)':'Añade un mensaje…'} value={active.caption||(first?draft:'')} disabled={busy} onChange={event=>onCaption(active.id,event.target.value)} onKeyDown={event=>{if(event.key==='Enter'&&!event.shiftKey&&!event.nativeEvent.isComposing){event.preventDefault();event.currentTarget.form?.requestSubmit()}}}/></label>
      <div className="attachment-preview-bottom"><div className="attachment-thumbnails" aria-label="Adjuntos preparados">{items.map(item=><div className="attachment-thumbnail" key={item.id} data-selected={item.id===active.id}><button type="button" className="attachment-select" aria-label={`Seleccionar ${item.file.name}`} aria-pressed={item.id===active.id} onClick={()=>{setSelected({id:item.id,tail});setCaret(undefined)}}>{item.contentType==='image'||item.contentType==='sticker'?<StreamingImage src={item.previewUrl} alt=""/>:<FileText size={24}/>}</button><button className="attachment-thumbnail-remove" type="button" aria-label={`Quitar ${item.file.name}`} disabled={busy} onClick={()=>onRemove(item.id)}><X size={15}/></button></div>)}<button type="button" className="attachment-add" aria-label="Añadir más archivos" disabled={busy||preparing} onClick={()=>fileInput.current?.click()}><Plus/></button><input ref={fileInput} type="file" multiple hidden onChange={event=>{onAdd(event.target.files);event.currentTarget.value=''}}/></div><button className="attachment-send" type="submit" aria-label={`Enviar ${items.length} adjuntos`} disabled={busy||preparing}><Send size={23}/></button></div>
      <small className="attachment-preview-status" role="status">{busy?'Enviando adjuntos…':preparing?status:`${items.length} adjunto${items.length===1?'':'s'} · Pulsa enviar cuando termines`}</small>
    </form>
  </section>
}

