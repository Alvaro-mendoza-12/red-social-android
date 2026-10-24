import { MessageTimestamp } from './MessageTimestamp'
import { MessageQuickReaction } from './MessageOptionsMenu'
import { useState } from 'react'
import { ChatAttachmentMedia } from './ChatAttachmentMedia'
import { MediaViewer } from './MediaViewer'
import type { AlbumMessage } from '../core/chatMediaAlbums'

export function ChatMediaAlbum<T extends AlbumMessage>({ items, mine, onReply, onForward, onDelete, onDeleteForAll, onReact, deliveryState, onPhotoOpen }: {
  items: T[]; mine: boolean; onReply: (message: T) => void; onForward: (message: T) => void
  onDelete: (message: T) => void; onDeleteForAll: (message: T) => void; onReact: (message: T, emoji?: string) => void
  deliveryState?: (message: T) => string
  onPhotoOpen?: (message: T) => void
}) {
  const [selected, setSelected] = useState<number | null>(null)
  return <article id={`message-${items[0].id}`} className={`bubble chat-album ${mine ? 'bubble-me' : 'bubble-them'}`} aria-label={`Álbum de ${items.length} fotos`}>
    <div className={`chat-album-grid count-${Math.min(4, items.length)}`}>
      {items.slice(0, 4).map((message, index) => <div key={message.id} className="chat-album-tile">
        <ChatAttachmentMedia url={message.mediaUrl!} kind="image" title={`foto ${index + 1} de ${items.length}`} onImageOpen={() => onPhotoOpen ? onPhotoOpen(message) : setSelected(index)} imageClassName="chat-album-image" inlineDownload={false} />
        <div className="chat-media-overlay"><MessageTimestamp createdAt={message.createdAt} mine={mine} status={deliveryState?.(message) || message.status || 'sent'}/></div>
        <MessageQuickReaction mine={mine} onReact={emoji => onReact(message, emoji)}/>
        {index === 3 && items.length > 4 ? <span className="chat-album-more">+{items.length - 4}</span> : null}
      </div>)}
    </div>
    {items.filter(message => message.content && !/^[^\n]+\.(png|jpe?g|webp|gif|avif)$/i.test(message.content)).map(message => <p className="message-caption" key={message.id}>{message.content}</p>)}
    <div className="chat-album-footer"><details className="chat-album-actions"><summary>Fotos ({items.length})</summary><div>{items.map((message, index) => <section key={message.id}><strong>Foto {index + 1}</strong><button type="button" onClick={() => setSelected(index)}>Abrir</button><button type="button" onClick={() => onReply(message)}>Responder</button><button type="button" onClick={() => onForward(message)}>Reenviar</button><button type="button" onClick={() => onReact(message)}>Me gusta</button><button type="button" onClick={() => onDelete(message)}>Eliminar para mí</button>{mine ? <button type="button" onClick={() => onDeleteForAll(message)}>Eliminar para todos</button> : null}</section>)}</div></details></div>
    {selected !== null ? <MediaViewer items={items.map((message, index) => ({ id: message.id, url: message.mediaUrl!, type: 'image' as const, title: `Foto ${index + 1} de ${items.length} · ${new Date(message.createdAt).toLocaleTimeString('es', { hour: '2-digit', minute: '2-digit' })}` }))} initialIndex={selected} onClose={() => setSelected(null)} /> : null}
  </article>
}
