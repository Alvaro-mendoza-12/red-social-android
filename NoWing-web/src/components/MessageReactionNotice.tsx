import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { useAuth } from '../core/auth/AuthProvider'
import { subscribeUbuntuRealtime } from '../core/ubuntuRealtime'
import { textOf, type GenericRow } from '../core/utils'

export function MessageReactionNotice() {
  const { user, isFallbackSession } = useAuth()
  const accountId = user?.id
  const [notice, setNotice] = useState<{ account: string; title: string; content: string; path: string; participant?: { id: string; name: string; avatarUrl: string } } | null>(null)
  useEffect(() => {
    if (!accountId || !isFallbackSession) return
    let timer: ReturnType<typeof setTimeout> | undefined
    const seen = new Set<string>()
    const stop = subscribeUbuntuRealtime({ channel: `user:${accountId}`, onEvent: event => {
      if (event.payload.event !== 'message_reaction') return
      const row = event.payload.notification as GenericRow | undefined
      if (!row) return
      const id = textOf(row, ['id'], ''), kind = textOf(row, ['target_type'], ''), target = textOf(row, ['target_id'], '')
      if (!id || seen.has(id) || !['chat', 'group', 'community'].includes(kind) || !/^[\w-]{1,160}$/.test(target)) return
      seen.add(id); if (seen.size > 100) seen.delete(seen.values().next().value!)
      const extra = (event.payload.extraData || {}) as GenericRow
      const messageId = textOf(extra, ['message_id'], '')
      const path = `/app/messages/${kind === 'chat' ? '' : kind + '/'}${encodeURIComponent(target)}` + (/^[0-9a-f-]{36}$/i.test(messageId) ? `?focusMessageId=${encodeURIComponent(messageId)}` : '')
      const actorId = textOf(row, ['actor_id'], '')
      const actorName = textOf(row, ['actor_name'], '')
      const actorAvatar = textOf(row, ['actor_avatar_url', 'sender_avatar_url'], '')
      setNotice({ account: accountId, title: actorName || 'Alguien', content: textOf(row, ['content'], 'Reaccionó a tu mensaje'), path,
        participant: actorId && actorName && !['usuario', 'alguien', 'novvin'].includes(actorName.toLowerCase()) ? { id: actorId, name: actorName, avatarUrl: actorAvatar } : undefined })
      clearTimeout(timer); timer = setTimeout(() => setNotice(null), 8000)
    } })
    return () => { stop(); clearTimeout(timer) }
  }, [accountId, isFallbackSession])
  if (!notice || notice.account !== user?.id) return null
  return <aside className="message-reaction-notice" aria-label="Reacción recibida">
    <div role="status"><strong>{notice.title}</strong><p>{notice.content}</p></div>
    <Link to={notice.path} state={notice.participant ? { notificationParticipant: notice.participant } : undefined} onClick={() => setNotice(null)}>Ver mensaje</Link>
    <button className="icon-btn" type="button" aria-label="Cerrar aviso de reacción" onClick={() => setNotice(null)}>×</button>
  </aside>
}
