import { useMemo, useState } from 'react'
import { textOf, formatDateTime, type GenericRow } from '../core/utils'
import { commentThreads } from '../core/commentThreads'
import { commentAudioUrl } from '../core/commentMedia'
import { SafeAvatar } from './SafeAvatar'
import { CommentAudio } from './CommentAudio'
import { ChatAttachmentMedia } from './ChatAttachmentMedia'
import { MessageText } from './MessageText'
import './comments.css'

const reactions = [['LIKE','👍'],['LOVE','❤️'],['LAUGH','😂'],['WOW','😮'],['SAD','😢']] as const
export function CommentThreadList({ items, accountId, kind, busy, onReply, onRemove, onReaction, onEdit }: {
  items: GenericRow[]; accountId?: string; kind:'video'|'post'; busy:boolean;
  onReply:(item:GenericRow)=>void; onRemove:(id:string)=>void; onReaction:(id:string,type:string|null)=>void; onEdit:(id:string,content:string)=>Promise<void>
}) {
  const [visible,setVisible]=useState(30), [expanded,setExpanded]=useState<Set<string>>(new Set()), [editing,setEditing]=useState(''), [draft,setDraft]=useState('')
  const threads=useMemo(()=>commentThreads(items),[items])
  const mentions=useMemo(()=>items.map(item=>({id:textOf(item,['user_id','userId']),name:textOf(item,['user_name','userName'],'Usuario'),username:textOf(item,['username','user_username'])})).filter(item=>item.username),[items])
  function row(item:GenericRow,child=false) {
    const id=textOf(item,['id']), name=textOf(item,['user_name','userName','display_name'],'Usuario'), own=item.is_mine===true||textOf(item,['user_id','userId'])===accountId
    const media=textOf(item,['media_url','mediaUrl']), type=textOf(item,['content_type','media_type']), content=textOf(item,['content']), audio=commentAudioUrl(item)
    const mine=textOf(item,['my_reaction']), counts=(item.reactions||item.reaction_counts||{}) as Record<string,number>
    const options=kind==='video'?reactions:[['LIKE','👍'],['DISLIKE','👎']] as const
    return <article className={`video-comment threaded-comment${child?' comment-child':''}`} key={id}>
      <SafeAvatar url={textOf(item,['user_profile_image','user_avatar_url','avatar_url','userAvatarUrl'])} name={name}/>
      <div className="comment-body"><header><a href={`/app/profile/${encodeURIComponent(textOf(item,['user_id','userId']))}`}>{name}</a><time dateTime={textOf(item,['created_at'])}>{textOf(item,['created_at'])?formatDateTime(textOf(item,['created_at'])):''}</time></header>
        {editing===id?<form onSubmit={event=>{event.preventDefault();void onEdit(id,draft.trim()).then(()=>setEditing('')).catch(()=>{})}}><textarea className="text-input" aria-label="Editar comentario" value={draft} maxLength={1000} onChange={event=>setDraft(event.target.value)}/><button className="ghost-btn" type="submit" disabled={busy||!draft.trim()}>Guardar</button><button className="ghost-btn" type="button" onClick={()=>setEditing('')}>Cancelar</button></form>:<>
          {content&&!/^\[(audio|sticker)\](\s|$)/i.test(content)?<MessageText text={content} mentions={mentions}/>:null}
          {audio?<CommentAudio url={audio} name={name}/>:media?<ChatAttachmentMedia url={media} kind="image" title="Sticker" imageLink={false} preserveAnimation/>:content==='[Audio]'?<p>Este audio no tiene un archivo disponible.</p>:null}
        </>}
        <div className="comment-reactions" aria-label={`Reacciones al comentario de ${name}`}>{options.map(([reaction,emoji])=><button type="button" key={reaction} disabled={busy} aria-label={`Reaccionar ${reaction} al comentario de ${name}`} aria-pressed={mine===reaction} onClick={()=>onReaction(id,mine===reaction?null:reaction)}>{emoji}{Number(counts[reaction])>0?<span>{counts[reaction]}</span>:null}</button>)}</div>
        <footer><button type="button" onClick={()=>onReply(item)}>Responder</button>{own?<details className="comment-menu"><summary aria-label={`Opciones del comentario de ${name}`}>•••</summary><div>{type!== 'sticker'?<button type="button" disabled={busy} onClick={()=>{setEditing(id);setDraft(content.replace(/^\[Audio\].*$/i,''))}}>Editar</button>:null}<button type="button" disabled={busy} onClick={()=>onRemove(id)}>Eliminar</button></div></details>:null}</footer>
      </div>
    </article>
  }
  return <div className="comment-threads">{threads.slice(-visible).map(thread=>{const id=textOf(thread.root,['id']), open=expanded.has(id);return <section className="comment-thread" key={id}>{row(thread.root)}{thread.replies.length?<button type="button" className="comment-expand" aria-expanded={open} onClick={()=>setExpanded(current=>{const next=new Set(current);if(next.has(id))next.delete(id);else next.add(id);return next})}>{open?'Ocultar respuestas':`Ver ${thread.replies.length} ${thread.replies.length===1?'respuesta':'respuestas'}`}</button>:null}{open?<div className="comment-replies">{thread.replies.map(item=>row(item,true))}</div>:null}</section>})}{threads.length>visible?<button className="ghost-btn" type="button" onClick={()=>setVisible(value=>value+30)}>Ver comentarios anteriores</button>:null}</div>
}
