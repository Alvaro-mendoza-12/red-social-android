import { useEffect, useRef, useState, type FormEvent } from 'react'
import { Mic, Send, Smile, Square, X } from 'lucide-react'
import { useAuth } from '../core/auth/AuthProvider'
import { uploadFallbackFile, searchFallbackProfiles } from '../core/fallbackData'
import { friendlyError, textOf } from '../core/utils'
import { MentionSuggestions } from './MentionSuggestions'
import { ChatAttachmentMedia } from './ChatAttachmentMedia'
import { StickerPicker } from './StickerPicker'
import type { MentionCandidate } from '../core/mentionSuggestions'

export type CommentDraft = { content: string; mediaUrl?: string; contentType: 'text' | 'audio' | 'sticker' }
export function CommentComposer({ kind, busy, replyName, onCancelReply, onSend }: {
  kind: 'video' | 'post'; busy: boolean; replyName?: string; onCancelReply: () => void; onSend: (value: CommentDraft) => Promise<void>
}) {
  const { user } = useAuth(), owner = useRef(user?.id); owner.current = user?.id
  const [draft, setDraft] = useState(''), [file, setFile] = useState<File | null>(null), [sticker, setSticker] = useState('')
  const [stickers, setStickers] = useState(false), [recording, setRecording] = useState(false), [working, setWorking] = useState(false), [error, setError] = useState('')
  const [candidates, setCandidates] = useState<MentionCandidate[]>([]), [preview, setPreview] = useState('')
  const recorder = useRef<MediaRecorder | null>(null), stream = useRef<MediaStream | null>(null), chunks = useRef<Blob[]>([]), generation = useRef(0), recordTimer = useRef<ReturnType<typeof setTimeout> | undefined>(undefined)
  const locked = working || busy
  const stop = () => { stream.current?.getTracks().forEach(track => track.stop()); stream.current = null; clearTimeout(recordTimer.current) }
  useEffect(() => () => { generation.current++; if(recorder.current)recorder.current.onstop=null; if(recorder.current?.state==='recording')recorder.current.stop(); stop() }, [])
  useEffect(() => { if (!file) { setPreview(''); return }; const url = URL.createObjectURL(file); setPreview(url); return () => URL.revokeObjectURL(url) }, [file])
  useEffect(() => {
    let current = true; const match = /(?:^|\s)@([\p{L}\p{N}_.-]{1,40})$/u.exec(draft)
    if(!match){setCandidates([]);return}
    const timer=setTimeout(()=>{void searchFallbackProfiles(match[1],8).then(result=>{if(current)setCandidates(result.items.map(row=>({id:textOf(row,['id']),username:textOf(row,['username']),name:textOf(row,['display_name','username'],'Usuario'),avatarUrl:textOf(row,['avatar_url'])})).filter(item=>Boolean(item.username)))}).catch(()=>{if(current)setCandidates([])})},220)
    return()=>{current=false;clearTimeout(timer)}
  },[draft])
  async function record() {
    if(recording){recorder.current?.stop();return}
    const current=++generation.current, account=user?.id; setError('')
    try {
      if(!navigator.mediaDevices?.getUserMedia || typeof MediaRecorder==='undefined')throw new Error('Tu navegador no permite grabar audio. Puedes adjuntar un archivo de audio.')
      const media=await navigator.mediaDevices.getUserMedia({audio:true})
      if(current!==generation.current||owner.current!==account){media.getTracks().forEach(track=>track.stop());return}
      stream.current=media
      const mime=['audio/webm;codecs=opus','audio/ogg;codecs=opus','audio/mp4'].find(value=>MediaRecorder.isTypeSupported(value))
      const active=new MediaRecorder(media,mime?{mimeType:mime}:undefined);recorder.current=active;chunks.current=[]
      active.ondataavailable=event=>{if(event.data.size)chunks.current.push(event.data)}
      active.onstop=()=>{stop();recorder.current=null;setRecording(false);if(current!==generation.current||owner.current!==account)return;const type=active.mimeType||'audio/webm',ext=type.includes('ogg')?'ogg':type.includes('mp4')?'m4a':'webm';const blob=new Blob(chunks.current,{type});chunks.current=[];if(blob.size){setFile(new File([blob],`comentario.${ext}`,{type}));setSticker('')}}
      active.onerror=()=>{generation.current++;stop();setRecording(false);setError('No se pudo grabar el audio. Inténtalo de nuevo.')}
      active.start(250);setRecording(true);recordTimer.current=setTimeout(()=>active.state==='recording'&&active.stop(),120000)
    }catch(caught){stop();setError(friendlyError(caught,'No se pudo acceder al micrófono.'))}
  }
  function cancelMedia(){generation.current++;if(recorder.current?.state==='recording')recorder.current.stop();stop();setRecording(false);setFile(null);setSticker('')}
  async function submit(event:FormEvent){
    event.preventDefault();if(locked||recording||!user||(!draft.trim()&&!file&&!sticker))return
    const account=user.id, current=generation.current;setWorking(true);setError('')
    try{
      let mediaUrl=sticker
      const contentType=sticker?'sticker':file?(file.type.startsWith('audio/')?'audio':'sticker'):'text'
      if(file)mediaUrl=(await uploadFallbackFile(kind==='video'?'video-comments-media':'post-comments-media',file,undefined,account)).publicUrl
      if(owner.current!==account||current!==generation.current)return
      await onSend({content:draft.trim(),contentType,...(mediaUrl?{mediaUrl}:{})})
      if(owner.current===account&&current===generation.current){setDraft('');setFile(null);setSticker('');setStickers(false)}
    }catch(caught){if(owner.current===account)setError(friendlyError(caught,'No se pudo enviar el comentario. Puedes reintentarlo.'))}finally{if(owner.current===account)setWorking(false)}
  }
  return <form className="comment-composer" onSubmit={event=>void submit(event)}>
    {replyName?<div className="comment-replying"><span>Respondiendo a {replyName}</span><button type="button" className="icon-btn-sm" aria-label="Cancelar respuesta" onClick={onCancelReply}><X size={16}/></button></div>:null}
    {preview||sticker||recording?<div className="comment-draft-media">{recording?<span role="status">Grabando audio · máximo 2 minutos</span>:file?.type.startsWith('audio/')?<audio src={preview} controls/>:sticker?<ChatAttachmentMedia url={sticker} kind="image" title="Vista previa de sticker" imageLink={false} preserveAnimation/>:<img src={preview} alt="Vista previa de sticker"/>}<button type="button" className="ghost-btn" disabled={locked} onClick={cancelMedia}>Cancelar adjunto</button></div>:null}
    {error?<p role="alert" className="error-text">{error}</p>:null}
    <MentionSuggestions text={draft} candidates={candidates} actorId={user?.id} onSelect={username=>setDraft(value=>value.replace(/@[\p{L}\p{N}_.-]*$/u,`@${username} `))}/>
    <div className="comment-input-row"><textarea className="text-input" placeholder="Añade un comentario…" aria-label="Escribir comentario" maxLength={1000} rows={1} value={draft} onChange={event=>setDraft(event.target.value)} disabled={locked}/><button type="submit" className="icon-btn" aria-label="Enviar comentario" disabled={locked||recording||(!draft.trim()&&!file&&!sticker)}><Send size={19}/></button></div>
    <div className="comment-tools"><button type="button" className="ghost-btn" disabled={locked||recording} aria-label="Stickers" aria-expanded={stickers} onClick={()=>setStickers(value=>!value)}><Smile size={18}/>Sticker</button><button type="button" className="ghost-btn" disabled={locked||Boolean(file)||Boolean(sticker)} onClick={()=>void record()} aria-label={recording?'Terminar grabación':'Grabar audio'}>{recording?<Square size={18}/>:<Mic size={18}/>}Audio</button><label className="ghost-btn">Adjuntar audio<input type="file" hidden accept="audio/*" disabled={locked||recording} onChange={event=>{const selected=event.target.files?.[0];event.target.value='';if(selected&&selected.size<=10*1024*1024){setFile(selected);setSticker('')}else if(selected)setError('El audio debe pesar como máximo 10 MB.')}}/></label></div>
    {stickers?<StickerPicker onClose={()=>setStickers(false)} onSelect={item=>{setSticker(item.mediaUrl);setFile(null);setStickers(false)}} onImport={selected=>{if(selected&&selected.size<=5*1024*1024){setFile(selected);setSticker('');setStickers(false)}else if(selected)setError('El sticker debe pesar como máximo 5 MB.')}}/>:null}
  </form>
}
