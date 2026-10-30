import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { ChatDetailPage } from '../../../features/messages/ChatDetailPage'
import { RoomChatPage } from '../../../features/messages/RoomChatPage'
import { loadFallbackChat, loadFallbackRoomChat, loadFallbackHistoryPage, sendFallbackChatMessage, sendFallbackRoomMessage } from '../../../core/fallbackData'
import { retainAttachmentOwner } from '../../../core/attachmentJobs'
import { writeCachedValue } from '../../../core/localCache'
import { fallbackApiJson } from '../../../core/auth/fallbackAuth'

const mockUser = { id: 'account-a' }
jest.mock('../../../core/auth/AuthProvider', () => ({ useAuth: () => ({ user: mockUser, isFallbackSession: true }) }))
jest.mock('../../../core/ubuntuBackend', () => ({ shouldUseUbuntuBackend: () => true }))
jest.mock('../../../core/supabase', () => ({ supabase: null, resolveSupabasePublicUrl: (_bucket: string, value: string) => value, reportSupabaseServiceRestrictionIfPresent: jest.fn() }))
jest.mock('../../../core/ubuntuRealtime', () => ({ subscribeUbuntuRealtime: () => () => {}, ubuntuRealtimeConnected: () => true }))
jest.mock('../../../core/auth/fallbackAuth', () => ({ fallbackApiJson: jest.fn(async () => []) }))
jest.mock('../../../core/backendRpc', () => ({ backendRpc: jest.fn(async () => ({ data: [] })) }))
jest.mock('../../../core/fallbackData', () => ({
  loadFallbackChat: jest.fn(), loadFallbackRoomChat: jest.fn(),
  loadFallbackHistoryPage: jest.fn(),
  fallbackHistoryAllows: jest.requireActual('../../../core/fallbackData').fallbackHistoryAllows,
  sendFallbackChatMessage: jest.fn(), sendFallbackRoomMessage: jest.fn(),
  markFallbackChatRead: jest.fn(async () => ({ ok: true })),
}))
jest.mock('../../../components/StreamingMedia', () => ({ StreamingImage: (props: object) => <img {...props}/>, StreamingVideo: (props: object) => <video {...props}/> }))
jest.mock('../../../components/MediaViewer', () => ({ MediaViewer: () => null }))
jest.mock('../../../components/ChatImageEditor', () => ({ ChatImageEditor: () => null }))
jest.mock('../../../components/ChatAttachmentMedia', () => ({ ChatAttachmentMedia: () => null }))
jest.mock('../../../components/ChatProfilePanel', () => ({ ChatProfilePanel: () => null }))
jest.mock('../../../components/ProfileStatusBadges', () => ({ ProfileStatusBadges: () => null }))
jest.mock('../../../components/SpellingSuggestions', () => ({ SpellingSuggestions: () => null }))
jest.mock('../../../components/DirectMessageRequestNotice', () => ({ DirectMessageRequestNotice: () => null }))
jest.mock('../../../components/CommunityRaffles', () => ({ CommunityRaffles: () => null }))
jest.mock('../../../components/CommunityQuestion', () => ({ CommunityQuestion: () => null, CollectionQuestionTools: () => null }))
jest.mock('../../../components/CollectionInfo', () => ({ CollectionInfo: () => null }))
jest.mock('../../../components/StickerPicker', () => ({ StickerPicker: () => null }))
jest.mock('../../../components/ForwardMessagesDialog', () => ({ ForwardMessagesDialog: () => null }))
jest.mock('../../../components/ViewOnceMediaDialog', () => ({ ViewOnceMediaDialog: () => null }))

const incoming = { id: 'incoming', sender_id: 'friend', content: 'Mensaje recibido', content_type: 'text', created_at: '2026-10-23T12:00:00Z', status: 'sent' }
const outgoing = { id: 'outgoing', sender_id: 'account-a', content: 'Mensaje propio', content_type: 'text', created_at: '2026-10-23T12:00:01Z', status: 'sent' }
const settings = { isMuted: false, isPinned: false, isArchived: false, isFavorite: false, translateEnabled: false, targetLang: 'es' }
const response = { title: 'Amigo', chat: { nickname: 'Apodo privado' }, otherParticipant: { id: 'friend', name: 'Amigo', avatarUrl: 'https://example.test/avatar.gif', lastReadAt: '' }, messages: [incoming, outgoing], settings, participants: [{ id: 'account-a', name: 'Yo', role: 'member', avatarUrl: '' }, { id: 'friend', name: 'Amigo', role: 'member', avatarUrl: '' }], historyAccess: { isMember: true } }

beforeAll(() => {
  Object.defineProperty(HTMLElement.prototype, 'scrollTo', { configurable: true, value: jest.fn() })
  Object.defineProperty(URL, 'createObjectURL', { configurable: true, value: jest.fn(() => 'blob:preview') })
  Object.defineProperty(URL, 'revokeObjectURL', { configurable: true, value: jest.fn() })
  Object.defineProperty(globalThis.crypto, 'randomUUID', { configurable: true, value: () => `file-${Math.random()}` })
  Object.defineProperty(Blob.prototype, 'arrayBuffer', { configurable: true, value: function(this: Blob) { return new Promise((resolve, reject) => { const reader = new FileReader(); reader.onload = () => resolve(reader.result); reader.onerror = reject; reader.readAsArrayBuffer(this) }) } })
})
beforeEach(() => {
  jest.clearAllMocks()
  localStorage.clear()
  retainAttachmentOwner('clear-everything')
  ;(loadFallbackChat as jest.Mock).mockResolvedValue(response)
  ;(loadFallbackRoomChat as jest.Mock).mockResolvedValue(response)
  ;(sendFallbackChatMessage as jest.Mock).mockImplementation(async (_id, input) => ({ message: { ...outgoing, id: 'sent', content: input.content, reply_to_id: input.replyToId } }))
  ;(sendFallbackRoomMessage as jest.Mock).mockImplementation(async (_scope, _id, input) => ({ message: { ...outgoing, id: 'sent', content: input.content, reply_to_id: input.replyToId } }))
})

function open(scope: 'chat' | 'group' | 'community' = 'chat', state?: unknown) {
  return render(<MemoryRouter initialEntries={[{ pathname: '/app/messages/room-a', state }]}><Routes><Route path="/app/messages/:chatId" element={scope === 'chat' ? <ChatDetailPage/> : <RoomChatPage scope={scope} roomId="room-a"/>}/></Routes></MemoryRouter>)
}

test.each(['chat', 'group', 'community'] as const)('%s accepts typing immediately after opening without another click', async scope => {
  const user = userEvent.setup()
  open(scope)
  await screen.findByText('Mensaje recibido')
  const input = screen.getByPlaceholderText(scope === 'chat' ? 'Escribe un mensaje...' : `Escribe en ${scope === 'group' ? 'grupo' : 'comunidad'}...`)
  await waitFor(() => expect(input).toHaveFocus())
  await user.keyboard('Hola 😀')
  expect(input).toHaveValue('Hola 😀')
})

test.each(['chat', 'group', 'community'] as const)('%s keeps a reply outside the loaded history in stacked layout', async scope => {
  const reply = { ...incoming, id: 'reply-outside-page', content: 'Mañana prosigo con el código para noticias', reply_to_id: 'older-message' }
  ;(loadFallbackChat as jest.Mock).mockResolvedValue({ ...response, messages: [reply] })
  ;(loadFallbackRoomChat as jest.Mock).mockResolvedValue({ ...response, messages: [reply] })
  const { container } = open(scope)
  await screen.findByText(reply.content)
  expect(screen.getByRole('button', { name: 'Ir al mensaje respondido' })).toBeInTheDocument()
  expect(container.querySelector('#message-reply-outside-page')).not.toHaveClass('text-bubble')
})

test.each(['chat', 'group', 'community'] as const)('%s contact cards keep their time even when the contact has a JPG URL', async scope => {
  const contact = { ...incoming, id: 'contact-message', content: JSON.stringify({ name: 'Contacto de prueba', phone: '+51999999999' }), content_type: 'contact', media_url: 'https://example.test/contact.jpg' }
  ;(loadFallbackChat as jest.Mock).mockResolvedValue({ ...response, messages: [contact] })
  ;(loadFallbackRoomChat as jest.Mock).mockResolvedValue({ ...response, messages: [contact] })
  const { container } = open(scope)
  await waitFor(() => expect(container.querySelector('#message-contact-message time')).toBeInTheDocument())
})

test.each(['chat', 'group', 'community'] as const)('%s double click focuses the composer and submits the quoted message ID', async scope => {
  open(scope)
  const message = await screen.findByText('Mensaje recibido')
  fireEvent.doubleClick(message)
  expect(screen.getByText('Respondiendo a: Mensaje recibido')).toBeInTheDocument()
  const input = screen.getByPlaceholderText(scope === 'chat' ? 'Escribe un mensaje...' : `Escribe en ${scope === 'group' ? 'grupo' : 'comunidad'}...`)
  expect(input).toHaveFocus()
  fireEvent.change(input, { target: { value: 'Mi respuesta' } })
  fireEvent.click(screen.getByRole('button', { name: 'Enviar mensaje' }))
  await waitFor(() => expect(scope === 'chat' ? sendFallbackChatMessage : sendFallbackRoomMessage).toHaveBeenCalledWith(...(scope === 'chat' ? ['room-a'] : [scope, 'room-a']), expect.objectContaining({ content: 'Mi respuesta', replyToId: 'incoming' })))
})

test.each(['chat', 'group', 'community'] as const)('%s pastes native text and stages image/document files without auto-send', async scope => {
  const user = userEvent.setup()
  open(scope)
  await screen.findByText('Mensaje recibido')
  const input = screen.getByPlaceholderText(scope === 'chat' ? 'Escribe un mensaje...' : `Escribe en ${scope === 'group' ? 'grupo' : 'comunidad'}...`)
  await user.click(input)
  await user.paste('Texto pegado 😀')
  expect(input).toHaveValue('Texto pegado 😀')
  const files = [new File(['image'], 'foto.jpg', { type: 'image/jpeg' }), new File(['pdf'], 'documento.pdf', { type: 'application/pdf' })]
  fireEvent.paste(input, { clipboardData: { files, items: [], types: ['Files'], getData: () => '' } })
  expect(await screen.findByRole('button', { name: 'Seleccionar foto.jpg' })).toBeInTheDocument()
  expect(await screen.findByRole('button', { name: 'Seleccionar documento.pdf' })).toBeInTheDocument()
  expect(screen.getByRole('button', { name: 'Enviar 2 adjuntos' })).toBeInTheDocument()
  expect(sendFallbackChatMessage).not.toHaveBeenCalled()
  expect(sendFallbackRoomMessage).not.toHaveBeenCalled()
})

test.each(['chat', 'group', 'community'] as const)('%s renders every saved emoji reaction, including the last three quick choices', async scope => {
  const loader = (scope === 'chat' ? loadFallbackChat : loadFallbackRoomChat) as jest.Mock
  const reactions = { '👍': ['friend-1'], '❤️': ['friend-2'], '😂': ['friend-3'], '😮': ['friend-4', 'account-a'], '😢': ['friend-5'], '🙏': ['friend-6'], '🎉': ['friend-7'], '👎': [] }
  loader.mockResolvedValue({ ...response, messages: [{ ...incoming, reactions }] })
  open(scope)
  const message = (await screen.findByText('Mensaje recibido')).closest('article')!
  const chips = Array.from(message.querySelectorAll('.reaction-chip')).map(chip => chip.textContent)
  expect(chips).toEqual(['👍 1', '❤️ 1', '😂 1', '😮 2', '😢 1', '🙏 1', '🎉 1'])
})

test('notification entry renders cached nickname and avatar while the server is still pending', async () => {
  writeCachedValue('messages:account-a:chats', [{ id: 'room-a', profileId: 'friend', title: 'Apodo privado', avatarUrl: 'https://example.test/avatar.gif' }])
  let finish!: (value: typeof response) => void
  ;(loadFallbackChat as jest.Mock).mockImplementation(() => new Promise(resolve => { finish = resolve }))
  open('chat', { notificationParticipant: { id: 'friend', name: 'Nombre público', avatarUrl: '' } })
  expect(screen.getByRole('button', { name: 'Ver perfil de Apodo privado' })).toBeInTheDocument()
  expect(screen.getByRole('img', { name: 'Apodo privado' })).toHaveAttribute('src', 'https://example.test/avatar.gif')
  expect(screen.getByRole('status')).toHaveTextContent('Cargando mensajes')
  expect(screen.queryByText('Todavía no hay mensajes en este chat.')).toBeNull()
  expect(screen.getByRole('button', { name: 'Enviar mensaje' })).toBeDisabled()
  await act(async () => { finish(response) })
  expect(await screen.findByText('Mensaje recibido')).toBeInTheDocument()
  expect(screen.getByRole('button', { name: 'Ver perfil de Apodo privado' })).toBeInTheDocument()
})

test('double click during editing returns to reply mode instead of editing the original message', async () => {
  open()
  const message = await screen.findByText('Mensaje recibido')
  const mine = screen.getByText('Mensaje propio').closest('article')!
  fireEvent.click(mine.querySelector('summary')!)
  const edit = Array.from(mine.querySelectorAll('button')).find(button => button.textContent?.trim() === 'Editar')!
  fireEvent.click(edit)
  expect(screen.getByPlaceholderText('Edita tu mensaje...')).toBeInTheDocument()
  fireEvent.doubleClick(message)
  expect(screen.getByPlaceholderText('Escribe un mensaje...')).toHaveFocus()
  expect(screen.queryByText('Editando mensaje')).toBeNull()
})

test.each(['chat', 'group', 'community'] as const)('%s loads older fallback pages and preserves them after a latest-page recovery', async scope => {
  const latest = { ...response, historyPage: { hasMore: true, nextCursor: 'opaque-cursor' } }
  const loader = (scope === 'chat' ? loadFallbackChat : loadFallbackRoomChat) as jest.Mock
  loader.mockResolvedValue(latest)
  const old = { ...incoming, id: 'older', content: 'Texto histórico', created_at: '2026-10-22T12:00:00Z' }
  ;(loadFallbackHistoryPage as jest.Mock).mockResolvedValue({ ...response, messages: [old], historyPage: { hasMore: false, nextCursor: null } })
  open(scope)
  await screen.findByText('Mensaje recibido')
  fireEvent.click(screen.getByRole('button', { name: 'Ver mensajes anteriores' }))
  expect(await screen.findByText('Texto histórico')).toBeInTheDocument()
  expect(loadFallbackHistoryPage).toHaveBeenCalledWith(scope, 'room-a', 'opaque-cursor', 80)
  expect(screen.getByText('Mensaje propio')).toBeInTheDocument()
  expect(screen.queryByRole('button', { name: 'Ver mensajes anteriores' })).toBeNull()
  window.dispatchEvent(new Event('novvin:realtime-ready'))
  await waitFor(() => expect(loader.mock.calls.length).toBeGreaterThan(1))
  expect(screen.getByText('Texto histórico')).toBeInTheDocument()
})

test.each(['chat', 'group', 'community'] as const)('%s cannot discard an older page opened from cache while its initial network read is pending', async scope => {
  const latest = { ...response, historyPage: { hasMore: true, nextCursor: 'cached-cursor' } }
  const loader = (scope === 'chat' ? loadFallbackChat : loadFallbackRoomChat) as jest.Mock
  let finish!: (value: typeof latest) => void
  loader.mockImplementation((...args: unknown[]) => {
    const showCache = args[scope === 'chat' ? 1 : 2] as (value: typeof latest) => void
    showCache(latest)
    return new Promise(resolve => { finish = resolve })
  })
  const old = { ...incoming, id: 'older-cached', content: 'Historia abierta desde caché', created_at: '2026-10-22T12:00:00Z' }
  ;(loadFallbackHistoryPage as jest.Mock).mockResolvedValue({ ...response, messages: [old], historyPage: { hasMore: false, nextCursor: null } })
  open(scope)
  await screen.findByText('Mensaje recibido')
  fireEvent.click(screen.getByRole('button', { name: 'Ver mensajes anteriores' }))
  await screen.findByText('Historia abierta desde caché')
  await act(async () => finish(latest))
  expect(screen.getByText('Historia abierta desde caché')).toBeInTheDocument()
  expect(screen.queryByRole('button', { name: 'Ver mensajes anteriores' })).toBeNull()
})

test('a private nickname is saved with the existing account-scoped endpoint and refreshes the inbox', async () => {
  const refresh = jest.fn()
  window.addEventListener('novvin:conversation-preferences-changed', refresh)
  try {
    open()
    await screen.findByText('Mensaje recibido')
    fireEvent.click(screen.getByRole('button', { name: 'Opciones del chat' }))
    fireEvent.click(screen.getByRole('menuitem', { name: 'Cambiar apodo privado' }))
    fireEvent.change(screen.getByRole('textbox', { name: 'Apodo' }), { target: { value: 'Mi apodo' } })
    fireEvent.click(screen.getByRole('button', { name: 'Guardar apodo' }))
    await waitFor(() => expect(fallbackApiJson).toHaveBeenCalledWith('/v1/data/messages/nicknames/friend', { method: 'PUT', body: JSON.stringify({ nickname: 'Mi apodo' }) }))
    expect(await screen.findByRole('button', { name: 'Ver perfil de Mi apodo' })).toBeInTheDocument()
    expect(refresh).toHaveBeenCalledTimes(1)
  } finally { window.removeEventListener('novvin:conversation-preferences-changed', refresh) }
})
