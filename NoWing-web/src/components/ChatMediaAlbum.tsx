import { useState } from 'react'
import { ChatAttachmentMedia } from './ChatAttachmentMedia'
import { MediaViewer } from './MediaViewer'
import type { AlbumMessage } from '../core/chatMediaAlbums'

export function ChatMediaAlbum<T extends AlbumMessage>({ items, mine, onReply, onForward, onDelete, onDeleteForAll, onReact, deliveryState, onPhotoOpen }: {
  items: T[]; mine: boolean; onReply: (message: T) => void; onForward: (message: T) => void
  onDelete: (message: T) => void; onDeleteForAll: (message: T) => void; onReact: (message: T) => void
  deliveryState?: (message: T) => string
  onPhotoOpen?: (message: T) => void
}) {
  const [selected, setSelected] = useState<number | null>(null)
  const last = items.at(-1)!
  const states = items.map(message => deliveryState?.(message) || message.status || 'sent')
  const status = states.every(value => value === 'read') ? 'read' : states.every(value => value === 'read' || value === 'delivered') ? 'delivered' : 'sent'
  return <article className={`bubble chat-album ${mine ? 'bubble-me' : 'bubble-them'}`} aria-label={`Álbum de ${items.length} fotos`}>
    <div className={`chat-album-grid count-${Math.min(4, items.length)}`}>
      {items.slice(0, 4).map((message, index) => <div key={message.id} className="chat-album-tile">
        <ChatAttachmentMedia url={message.mediaUrl!} kind="image" title={`foto ${index + 1} de ${items.length}`} onImageOpen={() => onPhotoOpen ? onPhotoOpen(message) : setSelected(index)} imageClassName="chat-album-image" />
        {index === 3 && items.length > 4 ? <span className="chat-album-more">+{items.length - 4}</span> : null}
      </div>)}
    </div>
    {items.filter(message => message.content && !/^[^\n]+\.(png|jpe?g|webp|gif|avif)$/i.test(message.content)).map(message => <p className="message-caption" key={message.id}>{message.content}</p>)}
    <div className="chat-album-footer"><details className="chat-album-actions"><summary>Fotos ({items.length})</summary><div>{items.map((message, index) => <section key={message.id}><strong>Foto {index + 1}</strong><button type="button" onClick={() => setSelected(index)}>Abrir</button><button type="button" onClick={() => onReply(message)}>Responder</button><button type="button" onClick={() => onForward(message)}>Reenviar</button><button type="button" onClick={() => onReact(message)}>Me gusta</button><button type="button" onClick={() => onDelete(message)}>Eliminar para mí</button>{mine ? <button type="button" onClick={() => onDeleteForAll(message)}>Eliminar para todos</button> : null}</section>)}</div></details><small>{new Date(last.createdAt).toLocaleTimeString('es', { hour: '2-digit', minute: '2-digit' })}</small></div>
    {mine ? <span className={`message-status chat-album-status ${status}`} aria-label={status === 'read' ? 'Leído' : status === 'delivered' ? 'Entregado' : 'Enviado'}>{status === 'sent' ? '✓' : '✓✓'}</span> : null}
    {selected !== null ? <MediaViewer items={items.map((message, index) => ({ url: message.mediaUrl!, type: 'image' as const, title: `Foto ${index + 1} de ${items.length}` }))} initialIndex={selected} onClose={() => setSelected(null)} /> : null}
  </article>
}
