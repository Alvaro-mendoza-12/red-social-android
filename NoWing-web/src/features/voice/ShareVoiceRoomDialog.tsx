import { useEffect, useState } from 'react'
import { Copy, Share2 } from 'lucide-react'
import {
  loadFallbackMessagesInbox,
  sendFallbackChatMessage,
  sendFallbackRoomMessage,
  type FallbackInboxItem,
} from '../../core/fallbackData'
import { friendlyError } from '../../core/utils'
import { SafeAvatar } from '../../components/SafeAvatar'
import { PublicationDialog } from '../../components/PublicationDialog'
import { dedupeInboxConversations } from '../messages/conversationInboxSupport'
import { voiceRoomShareUrl } from '../../core/voiceRoomLinks'

export function ShareVoiceRoomDialog({
  roomId,
  roomTitle,
  onClose,
}: {
  roomId: string
  roomTitle: string
  onClose: () => void
}) {
  const [items, setItems] = useState<FallbackInboxItem[]>([])
  const [selected, setSelected] = useState<Set<string>>(new Set())
  const [query, setQuery] = useState('')
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(true)
  const [busy, setBusy] = useState(false)
  const [info, setInfo] = useState('')

  const url = voiceRoomShareUrl(roomId)
  const key = (item: FallbackInboxItem) => `${item.kind}/${item.id}`

  useEffect(() => {
    let active = true
    void Promise.all(['chats', 'groups', 'communities'].map((tab) => loadFallbackMessagesInbox(tab)))
      .then((values) => {
        if (!active) return
        setItems(
          dedupeInboxConversations(
            values
              .flatMap((value) => value.items)
              .filter(
                (item) =>
                  ['chat', 'group', 'community'].includes(item.kind) && item.isMember !== false,
              ),
          ),
        )
      })
      .catch((caught) => {
        if (active) setError(friendlyError(caught, 'No se pudieron cargar tus conversaciones.'))
      })
      .finally(() => {
        if (active) setLoading(false)
      })
    return () => {
      active = false
    }
  }, [])

  async function sendInvites() {
    if (busy || !selected.size) return
    setBusy(true)
    setError('')
    const sent = new Set<string>()
    try {
      const text = `Te invito a la sala de voz "${roomTitle}" en Novvin:\n${url}`
      for (const item of items.filter((entry) => selected.has(key(entry)))) {
        const payload = { content: text, contentType: 'text' }
        if (item.kind === 'chat') await sendFallbackChatMessage(item.id, payload)
        else if (item.kind === 'group' || item.kind === 'community') {
          await sendFallbackRoomMessage(item.kind, item.id, payload)
        }
        sent.add(key(item))
      }
      onClose()
    } catch (caught) {
      setSelected((current) => new Set([...current].filter((id) => !sent.has(id))))
      setError(
        friendlyError(caught, 'No se pudieron enviar todas las invitaciones. Puedes reintentar.'),
      )
    } finally {
      setBusy(false)
    }
  }

  function handleExternalShare() {
    setError('')
    if (typeof navigator.share === 'function') {
      void navigator
        .share({
          title: `${roomTitle} · Sala de voz en Novvin`,
          text: `Te invito a la sala de voz "${roomTitle}" en Novvin`,
          url,
        })
        .catch((caught) => {
          if (!(caught instanceof DOMException && caught.name === 'AbortError')) {
            setError('No se pudo abrir compartir. Puedes copiar el enlace.')
          }
        })
      return
    }
    void navigator.clipboard
      .writeText(url)
      .then(() => setInfo('Enlace de la sala copiado para compartir.'))
      .catch(() => setError('No se pudo copiar el enlace.'))
  }

  function handleCopyLink() {
    setError('')
    void navigator.clipboard
      .writeText(url)
      .then(() => setInfo('Enlace de la sala copiado.'))
      .catch(() => setError('No se pudo copiar el enlace.'))
  }

  const normalizedQuery = query
    .trim()
    .normalize('NFD')
    .replace(/[\u0300-\u036f]/g, '')
    .toLowerCase()
  const filteredItems = items.filter((item) => {
    if (!normalizedQuery) return true
    const haystack = `${item.title} ${item.subtitle || ''} ${item.id}`
      .normalize('NFD')
      .replace(/[\u0300-\u036f]/g, '')
      .toLowerCase()
    return haystack.includes(normalizedQuery)
  })

  return (
    <PublicationDialog title="Compartir e invitar a la sala" busy={busy} onClose={onClose}>
      <div className="row-wrap">
        <button type="button" className="ghost-btn" disabled={busy} onClick={handleExternalShare}>
          <Share2 size={16} aria-hidden="true" />
          Compartir fuera de Novvin
        </button>
        <button type="button" className="ghost-btn" disabled={busy} onClick={handleCopyLink}>
          <Copy size={16} aria-hidden="true" />
          Copiar enlace
        </button>
      </div>
      <input
        aria-label="Buscar chats, grupos o comunidades para invitar"
        disabled={busy}
        className="text-input"
        placeholder="Buscar chats, grupos o comunidades"
        value={query}
        onChange={(event) => setQuery(event.target.value)}
      />
      {loading ? <p>Buscando conversaciones recientes…</p> : null}
      {error ? <p role="alert">{error}</p> : null}
      {info ? <p role="status">{info}</p> : null}
      <div className="share-video-recipients">
        {filteredItems.map((item) => {
          const itemKey = key(item)
          const isSelected = selected.has(itemKey)
          const typeLabel =
            item.kind === 'group' ? 'Grupo' : item.kind === 'community' ? 'Comunidad' : 'Chat'
          return (
            <button
              type="button"
              className="share-recipient"
              data-conversation-id={item.id}
              data-conversation-kind={item.kind}
              key={itemKey}
              aria-pressed={isSelected}
              disabled={busy}
              onClick={() =>
                setSelected((current) => {
                  const next = new Set(current)
                  if (next.has(itemKey)) next.delete(itemKey)
                  else next.add(itemKey)
                  return next
                })
              }
            >
              <SafeAvatar
                url={item.avatarUrl}
                name={item.title}
                mediaType={item.avatarMediaType}
              />
              <span>
                <strong>{item.title}</strong>
                <small>
                  {typeLabel}
                  {item.subtitle ? ` · ${item.subtitle}` : ''}
                </small>
              </span>
              <b aria-hidden="true">{isSelected ? '✓' : ''}</b>
            </button>
          )
        })}
      </div>
      {!loading && !filteredItems.length ? (
        <p>No encontramos conversaciones disponibles para invitar.</p>
      ) : null}
      <footer className="row-between">
        <button type="button" className="ghost-btn" disabled={busy} onClick={onClose}>
          Cerrar
        </button>
        <button
          className="primary-btn"
          type="button"
          disabled={busy || !selected.size}
          onClick={() => void sendInvites()}
        >
          {busy ? 'Enviando…' : `Invitar (${selected.size})`}
        </button>
      </footer>
    </PublicationDialog>
  )
}
