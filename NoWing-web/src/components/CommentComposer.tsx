import { useEffect, useRef, useState, type FormEvent } from 'react'
import { Send, Smile, X } from 'lucide-react'
import { useAuth } from '../core/auth/AuthProvider'
import { uploadFallbackFile, searchFallbackProfiles } from '../core/fallbackData'
import { friendlyError, textOf } from '../core/utils'
import { MentionSuggestions } from './MentionSuggestions'
import { ChatAttachmentMedia } from './ChatAttachmentMedia'
import { StickerPicker } from './StickerPicker'
import type { MentionCandidate } from '../core/mentionSuggestions'
import { SpellingSuggestions } from './SpellingSuggestions'

export type CommentDraft = { content: string; mediaUrl?: string; contentType: 'text' | 'sticker' }

export function CommentComposer({ kind, busy, replyName, onCancelReply, onSend }: {
  kind: 'video' | 'post'; busy: boolean; replyName?: string; onCancelReply: () => void; onSend: (value: CommentDraft) => Promise<void>
}) {
  const { user } = useAuth(), owner = useRef(user?.id); owner.current = user?.id
  const [draft, setDraft] = useState(''), [file, setFile] = useState<File | null>(null), [sticker, setSticker] = useState('')
  const [stickers, setStickers] = useState(false), [working, setWorking] = useState(false), [error, setError] = useState('')
  const [candidates, setCandidates] = useState<MentionCandidate[]>([]), [preview, setPreview] = useState('')
  const [caret, setCaret] = useState<number | undefined>(), input = useRef<HTMLTextAreaElement>(null)
  const generation = useRef(0)
  const locked = working || busy

  useEffect(() => () => { generation.current++ }, [])
  useEffect(() => {
    if (!file) { setPreview(''); return }
    const url = URL.createObjectURL(file)
    setPreview(url)
    return () => URL.revokeObjectURL(url)
  }, [file])
  useEffect(() => {
    let current = true
    const match = /(?:^|\s)@([\p{L}\p{N}_.-]{1,40})$/u.exec(draft)
    if (!match) { setCandidates([]); return }
    const timer = setTimeout(() => {
      void searchFallbackProfiles(match[1], 8).then(result => {
        if (current) setCandidates(result.items.map(row => ({
          id: textOf(row, ['id']),
          username: textOf(row, ['username']),
          name: textOf(row, ['display_name', 'username'], 'Usuario'),
          avatarUrl: textOf(row, ['avatar_url'])
        })).filter(item => Boolean(item.username)))
      }).catch(() => { if (current) setCandidates([]) })
    }, 220)
    return () => { current = false; clearTimeout(timer) }
  }, [draft])

  function cancelMedia() {
    generation.current++
    setFile(null)
    setSticker('')
  }

  async function submit(event: FormEvent) {
    event.preventDefault()
    if (locked || !user || (!draft.trim() && !file && !sticker)) return
    const account = user.id, current = generation.current
    setWorking(true); setError('')
    try {
      let mediaUrl = sticker
      const contentType = sticker || file ? 'sticker' : 'text'
      if (file) {
        if (!file.type.startsWith('image/')) throw new Error('Solo puedes adjuntar imágenes como sticker.')
        mediaUrl = (await uploadFallbackFile(kind === 'video' ? 'video-comments-media' : 'post-comments-media', file, undefined, account)).publicUrl
      }
      if (owner.current !== account || current !== generation.current) return
      await onSend({ content: draft.trim(), contentType, ...(mediaUrl ? { mediaUrl } : {}) })
      if (owner.current === account && current === generation.current) {
        setDraft(''); setFile(null); setSticker(''); setStickers(false)
      }
    } catch (caught) {
      if (owner.current === account) setError(friendlyError(caught, 'No se pudo enviar el comentario. Puedes reintentarlo.'))
    } finally {
      if (owner.current === account) setWorking(false)
    }
  }

  return <form className="comment-composer" onSubmit={event => void submit(event)}>
    {replyName ? <div className="comment-replying"><span>Respondiendo a {replyName}</span><button type="button" className="icon-btn-sm" aria-label="Cancelar respuesta" onClick={onCancelReply}><X size={16}/></button></div> : null}
    {preview || sticker ? <div className="comment-draft-media">{sticker
      ? <ChatAttachmentMedia url={sticker} kind="image" title="Vista previa de sticker" imageLink={false} preserveAnimation/>
      : <img src={preview} alt="Vista previa del sticker"/>}<button type="button" className="ghost-btn" disabled={locked} onClick={cancelMedia}>Quitar adjunto</button></div> : null}
    {error ? <p role="alert" className="error-text">{error}</p> : null}
    <MentionSuggestions text={draft} candidates={candidates} actorId={user?.id} onSelect={username => setDraft(value => value.replace(/@[\p{L}\p{N}_.-]*$/u, `@${username} `))}/>
    {!locked ? <SpellingSuggestions text={draft} caret={caret} onReplace={(text, position) => { setDraft(text); setCaret(position); requestAnimationFrame(() => { input.current?.focus(); input.current?.setSelectionRange(position, position) }) }}/> : null}
    <div className="comment-input-row">
      <textarea ref={input} className="text-input" placeholder="Añade un comentario…" aria-label="Escribir comentario" lang="es" spellCheck autoCorrect="on" autoCapitalize="sentences" onSelect={event => setCaret(event.currentTarget.selectionStart)} maxLength={1000} rows={1} value={draft} onChange={event => setDraft(event.currentTarget.value)} disabled={locked}/>
      <button type="submit" className="icon-btn" aria-label="Enviar comentario" disabled={locked || (!draft.trim() && !file && !sticker)}><Send size={19}/></button>
    </div>
    <div className="comment-tools">
      <button type="button" className="ghost-btn" disabled={locked} aria-label="Emojis y stickers" aria-expanded={stickers} onClick={() => setStickers(value => !value)}><Smile size={18}/>Emojis y stickers</button>
    </div>
    {stickers ? <StickerPicker
      onClose={() => setStickers(false)}
      onSelect={item => { setSticker(item.mediaUrl); setFile(null); setStickers(false) }}
      onEmoji={emoji => { setDraft(value => `${value}${emoji}`); setStickers(false); input.current?.focus() }}
      onImport={selected => {
        if (selected && selected.type.startsWith('image/') && selected.size > 0 && selected.size <= 5 * 1024 * 1024) {
          setError(''); setFile(selected); setSticker(''); setStickers(false)
        } else if (selected) setError('El sticker debe ser una imagen de hasta 5 MB.')
      }}
    /> : null}
  </form>
}
