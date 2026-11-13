import { lazy, Suspense, useEffect, useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { Headphones, Phone, Search, Video } from 'lucide-react'
import { SafeAvatar } from '../../components/SafeAvatar'
import { StatusView } from '../../components/StatusView'
import { useAuth } from '../../core/auth/AuthProvider'
import { loadFallbackMessagesInbox, searchFallbackProfiles } from '../../core/fallbackData'
import { profileAvatarSource } from '../../core/profileMediaFields'
import { textOf } from '../../core/utils'
import { CallInterface } from './CallInterface'
import './calls.css'

const ChatDetailPage = lazy(() => import('../messages/ChatDetailPage').then(module => ({ default: module.ChatDetailPage })))

type CallContact = { id: string; name: string; avatar: string; chatId?: string }

function callPath(contact: CallContact, kind: 'audio' | 'video') {
  const params = new URLSearchParams({ target: contact.id, name: contact.name, kind })
  if (contact.chatId) params.set('contextId', contact.chatId)
  return '/app/calls?' + params
}

function CallContacts({ accountId }: { accountId: string }) {
  const [query, setQuery] = useState('')
  const [retry, setRetry] = useState(0)
  const [result, setResult] = useState<{ query: string; items: CallContact[]; failed: boolean } | null>(null)
  const term = query.trim()
  useEffect(() => {
    let active = true
    const timer = window.setTimeout(async () => {
      try {
        const contacts: CallContact[] = term.length >= 2
          ? (await searchFallbackProfiles(term, 30)).items.map(row => ({
              id: textOf(row, ['supabase_user_id', 'id', 'user_id']), name: textOf(row, ['display_name', 'full_name', 'username'], 'Perfil'),
              avatar: profileAvatarSource(row),
            }))
          : (await loadFallbackMessagesInbox('chats')).items
              .filter(item => item.kind === 'chat' && item.profileId)
              .map(item => ({ id: item.profileId!, name: item.title, avatar: item.avatarUrl, chatId: item.id }))
        const unique = new Map<string, CallContact>()
        for (const item of contacts) {
          if (item.id && item.id !== accountId && !unique.has(item.id) &&
              (term.length !== 1 || item.name.toLocaleLowerCase().includes(term.toLocaleLowerCase()))) unique.set(item.id, item)
        }
        if (active) setResult({ query: term, items: [...unique.values()], failed: false })
      } catch {
        if (active) setResult({ query: term, items: [], failed: true })
      }
    }, term.length >= 2 ? 220 : 0)
    return () => { active = false; window.clearTimeout(timer) }
  }, [accountId, term, retry])
  const visible = result?.query === term ? result : null
  return <section className="calls-page call-directory">
    <h2>Nueva llamada</h2>
    <label className="call-contact-search"><Search size={18} aria-hidden="true"/>
      <input type="search" value={query} onChange={event => setQuery(event.target.value)} placeholder="Buscar por nombre o usuario" aria-label="Buscar contacto para llamar"/>
    </label>
    <h3>{term ? 'Resultados' : 'Conversaciones recientes'}</h3>
    {!visible ? <p role="status">Buscando contactos…</p> : visible.failed ? <div role="status">No se pudieron cargar los contactos. <button className="ghost-btn" onClick={() => { setResult(null); setRetry(value => value + 1) }}>Reintentar</button></div>
      : !visible.items.length ? <p role="status">{term ? 'No hay coincidencias. Prueba con otro nombre.' : 'Busca a una persona para iniciar una llamada.'}</p>
      : <ul className="call-contact-list">{visible.items.map(contact => <li key={contact.id}>
          <SafeAvatar size="sm" name={contact.name} url={contact.avatar}/><strong>{contact.name}</strong>
          <Link className="icon-btn" to={callPath(contact, 'audio')} aria-label={`Llamar a ${contact.name}`} title="Llamada de voz"><Phone size={19}/></Link>
          <Link className="icon-btn" to={callPath(contact, 'video')} aria-label={`Videollamar a ${contact.name}`} title="Videollamada"><Video size={19}/></Link>
        </li>)}</ul>}
    <Link className="call-directory-rooms" to="/app/voice"><Headphones size={18}/> Salas de voz</Link>
  </section>
}

export function CallsPage() {
  const { user } = useAuth()
  const [params] = useSearchParams()
  const context = params.get('contextId')?.trim() || ''
  const callId = params.get('callId')?.trim() || ''
  const target = params.get('target')?.trim() || ''
  if (!user) return <StatusView title="Inicia sesión" description="Necesitas una cuenta para llamar."/>
  if (!target && !callId) return <CallContacts key={user.id} accountId={user.id}/>
  return <section className="section-stack calls-page">
    {context ? <div className="call-chat-background"><Suspense fallback={null}><ChatDetailPage chatIdOverride={context} embedded/></Suspense></div> : null}
    <section className="calls-live-shell">
      <CallInterface key={`${user.id}/${params}`} initialContextId={context} initialCallId={callId}
        initialRemoteUserId={target} initialRemoteName={params.get('name')?.trim() || ''}
        initialVideoEnabled={params.get('kind') === 'video'} joinExisting={params.get('join') === '1'}/>
    </section>
  </section>
}
