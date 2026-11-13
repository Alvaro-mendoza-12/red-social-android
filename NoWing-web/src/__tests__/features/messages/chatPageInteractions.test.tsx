import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router-dom'
import { ChatDetailPage } from '../../../features/messages/ChatDetailPage'
import { RoomChatPage } from '../../../features/messages/RoomChatPage'
import { deleteFallbackChatMessage, deleteFallbackRoomMessage, toggleFallbackChatReaction, saveFallbackChatPreferences, loadFallbackChat, loadFallbackRoomChat, loadFallbackHistoryPage, sendFallbackChatMessage, sendFallbackRoomMessage } from '../../../core/fallbackData'
import { retainAttachmentOwner } from '../../../core/attachmentJobs'
import { writeCachedValue } from '../../../core/localCache'
import { backendRpc } from '../../../core/backendRpc'
import { fallbackApiJson } from '../../../core/auth/fallbackAuth'
import { getStorageReadIdentity, updateStorageReadIdentity, invalidateStorageReadBackend } from '../../../core/media/storageReadSession'

const mockUser = { id: 'account-a' }
jest.mock('../../../core/auth/AuthProvider', () => ({ useAuth: () => ({ user: mockUser, isFallbackSession: true }) }))
jest.mock('../../../core/ubuntuBackend', () => ({ shouldUseUbuntuBackend: () => true }))
jest.mock('../../../core/supabase', () => ({ supabase: null, resolveSupabasePublicUrl: (_bucket: string, value: string) => value, reportSupabaseServiceRestrictionIfPresent: jest.fn() }))
jest.mock('../../../core/ubuntuRealtime', () => ({ subscribeUbuntuRealtime: () => () => {}, ubuntuRealtimeConnected: () => true }))
jest.mock('../../../core/auth/fallbackAuth', () => ({ fallbackApiJson: jest.fn(async () => []) }))
jest.mock('../../../core/backendRpc', () => ({ backendRpc: jest.fn(async () => ({ data: [] })) }))
jest.mock('../../../core/fallbackData', () => ({
  deleteFallbackChatMessage: jest.fn(), deleteFallbackRoomMessage: jest.fn(),
  saveFallbackChatPreferences: jest.fn(), toggleFallbackChatReaction: jest.fn(),
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
  mockUser.id = 'account-a'
  updateStorageReadIdentity('account-a', 'fallback')
  jest.mocked(fallbackApiJson).mockImplementation(async () => [])
  jest.mocked(backendRpc).mockResolvedValue({ data: [], error: null })
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

test('a conversation without saved preferences still opens its options', async () => {
  ;(loadFallbackChat as jest.Mock).mockResolvedValue({ ...response, settings: undefined })
  open('chat')
  await screen.findByText('Mensaje recibido')
  fireEvent.click(screen.getByRole('button', { name: 'Opciones del chat' }))
  expect(await screen.findByRole('menuitem', { name: 'Fijar chat' })).toBeVisible()
})

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
    await waitFor(() => expect(fallbackApiJson).toHaveBeenCalledWith('/v1/data/messages/nicknames/friend', { method: 'PUT', body: JSON.stringify({ nickname: 'Mi apodo' }), expectedAccountId: 'account-a', expectedIdentity: getStorageReadIdentity() }))
    expect(await screen.findByRole('button', { name: 'Ver perfil de Mi apodo' })).toBeInTheDocument()
    expect(refresh).toHaveBeenCalledTimes(1)
  } finally { window.removeEventListener('novvin:conversation-preferences-changed', refresh) }
})


test('a late pin response cannot change preferences of the next conversation', async () => {
  let finish!: (value: {ok: boolean}) => void
  jest.mocked(saveFallbackChatPreferences).mockImplementationOnce(() => new Promise(resolve => { finish = resolve }))
  const { rerender } = render(<MemoryRouter><ChatDetailPage chatIdOverride="room-a"/></MemoryRouter>)
  await screen.findByText('Mensaje recibido')
  fireEvent.click(screen.getByRole('button', { name: 'Opciones del chat' }))
  fireEvent.click(screen.getByRole('menuitem', { name: /Fijar/ }))
  jest.mocked(loadFallbackChat).mockResolvedValue({ ...response, title: 'Nuevo contacto', chat: { nickname: '' }, otherParticipant: { ...response.otherParticipant, name: 'Nuevo contacto' } } as never)
  rerender(<MemoryRouter><ChatDetailPage chatIdOverride="room-b"/></MemoryRouter>)
  await screen.findByText('Nuevo contacto')
  await act(async () => { finish({ ok: true }) })
  if (!screen.queryByRole('menu', { name: 'Opciones del chat' })) fireEvent.click(screen.getByRole('button', { name: 'Opciones del chat' }))
  expect(screen.getByRole('menuitem', { name: /^Fijar/ })).toBeEnabled()
})

test.each(['success', 'failure'])('an old preference %s cannot release the next conversations pending save', async outcome => {
  let finishOld!: () => void
  let finishNew!: (value: {ok: boolean}) => void
  jest.mocked(saveFallbackChatPreferences)
    .mockImplementationOnce(() => new Promise((resolve, reject) => {
      finishOld = () => outcome === 'success' ? resolve({ ok: true }) : reject(new Error('Error del chat anterior'))
    }))
    .mockImplementationOnce(() => new Promise(resolve => { finishNew = resolve }))
  const { rerender } = render(<MemoryRouter><ChatDetailPage chatIdOverride="room-a"/></MemoryRouter>)
  await screen.findByText('Mensaje recibido')
  fireEvent.click(screen.getByRole('button', { name: 'Opciones del chat' }))
  fireEvent.click(screen.getByRole('menuitem', { name: 'Fijar chat' }))
  jest.mocked(loadFallbackChat).mockResolvedValue({ ...response, title: 'Nuevo contacto', chat: { nickname: '' }, otherParticipant: { ...response.otherParticipant, name: 'Nuevo contacto' } } as never)
  rerender(<MemoryRouter><ChatDetailPage chatIdOverride="room-b"/></MemoryRouter>)
  await screen.findByText('Nuevo contacto')
  fireEvent.click(screen.getByRole('button', { name: 'Opciones del chat' }))
  expect(screen.getByRole('menuitem', { name: 'Fijar chat' })).toBeEnabled()
  fireEvent.click(screen.getByRole('menuitem', { name: 'Fijar chat' }))
  await act(async () => { finishOld() })
  expect(screen.queryByText('Error del chat anterior')).not.toBeInTheDocument()
  fireEvent.click(screen.getByRole('button', { name: 'Opciones del chat' }))
  expect(screen.getByRole('menuitem', { name: 'Fijar chat' })).toBeDisabled()
  await act(async () => { finishNew({ ok: true }) })
  expect(screen.getByRole('menuitem', { name: 'Desfijar chat' })).toBeEnabled()
})

test('an old scheduled message acknowledgement cannot erase the next conversation draft', async () => {
  let finish!: (value: unknown) => void
  jest.mocked(fallbackApiJson).mockImplementation(async path => {
    if (path === '/rest/v1/rpc/secure_schedule_message_job') return new Promise(resolve => { finish = resolve })
    return []
  })
  const { rerender } = render(<MemoryRouter><ChatDetailPage chatIdOverride="room-a"/></MemoryRouter>)
  await screen.findByText('Mensaje recibido')
  const showTools = () => {
    fireEvent.click(screen.getByRole('button', { name: 'Opciones del chat' }))
    fireEvent.click(screen.getByRole('menuitem', { name: 'Más opciones' }))
  }
  showTools()
  fireEvent.click(screen.getByRole('button', { name: 'Programar mensaje' }))
  fireEvent.change(screen.getByPlaceholderText('Mensaje programado...'), { target: { value: 'Mensaje para A' } })
  fireEvent.click(screen.getByRole('button', { name: 'Programar', exact: true }))
  await waitFor(() => expect(finish).toBeDefined())
  expect(fallbackApiJson).toHaveBeenCalledWith('/rest/v1/rpc/secure_schedule_message_job', expect.objectContaining({ expectedIdentity: getStorageReadIdentity(), expectedAccountId: 'account-a' }))
  jest.mocked(loadFallbackChat).mockResolvedValue({ ...response, title: 'Nuevo contacto', chat: { nickname: '' }, otherParticipant: { ...response.otherParticipant, name: 'Nuevo contacto' } } as never)
  rerender(<MemoryRouter><ChatDetailPage chatIdOverride="room-b"/></MemoryRouter>)
  await screen.findByText('Nuevo contacto')
  showTools()
  if (!screen.queryByPlaceholderText('Mensaje programado...')) fireEvent.click(screen.getByRole('button', { name: 'Programar mensaje' }))
  fireEvent.change(screen.getByPlaceholderText('Mensaje programado...'), { target: { value: 'Borrador privado B' } })
  await act(async () => { finish({ ok: true }) })
  expect(screen.getByPlaceholderText('Mensaje programado...')).toHaveValue('Borrador privado B')
})


test.each(['success', 'failure'])('an old schedule %s cannot release a new account schedule', async outcome => {
  let finishOld!: () => void
  let finishNew!: (value: unknown) => void
  let count = 0
  jest.mocked(fallbackApiJson).mockImplementation(async path => {
    if (path !== '/rest/v1/rpc/secure_schedule_message_job') return []
    count += 1
    return new Promise((resolve, reject) => {
      if (count === 1) finishOld = () => outcome === 'success' ? resolve({ ok: true }) : reject(new Error('Fallo privado A'))
      else finishNew = resolve
    })
  })
  const { rerender } = render(<MemoryRouter><ChatDetailPage chatIdOverride="room-a"/></MemoryRouter>)
  await screen.findByText('Mensaje recibido')
  const showTools = () => {
    fireEvent.click(screen.getByRole('button', { name: 'Opciones del chat' }))
    fireEvent.click(screen.getByRole('menuitem', { name: 'Más opciones' }))
    fireEvent.click(screen.getByRole('button', { name: 'Programar mensaje' }))
  }
  showTools()
  fireEvent.change(screen.getByPlaceholderText('Mensaje programado...'), { target: { value: 'Mensaje A' } })
  fireEvent.click(screen.getByRole('button', { name: 'Programar', exact: true }))
  await waitFor(() => expect(finishOld).toBeDefined())
  mockUser.id = 'account-b'
  updateStorageReadIdentity('account-b', 'fallback')
  rerender(<MemoryRouter><ChatDetailPage chatIdOverride="room-b"/></MemoryRouter>)
  await waitFor(() => expect(screen.queryByPlaceholderText('Mensaje programado...')).not.toBeInTheDocument())
  showTools()
  expect(screen.getByPlaceholderText('Mensaje programado...')).toHaveValue('')
  fireEvent.change(screen.getByPlaceholderText('Mensaje programado...'), { target: { value: 'Mensaje B' } })
  fireEvent.click(screen.getByRole('button', { name: 'Programar', exact: true }))
  await waitFor(() => expect(finishNew).toBeDefined())
  await act(async () => { finishOld() })
  expect(screen.getByPlaceholderText('Mensaje programado...')).toHaveValue('Mensaje B')
  expect(screen.getByRole('button', { name: 'Programando...' })).toBeInTheDocument()
  expect(screen.queryByText('Fallo privado A')).not.toBeInTheDocument()
  const calls = jest.mocked(fallbackApiJson).mock.calls.filter(([path]) => path === '/rest/v1/rpc/secure_schedule_message_job')
  expect(JSON.parse(calls[0][1]!.body as string).p_idempotency_key).not.toBe(JSON.parse(calls[1][1]!.body as string).p_idempotency_key)
  expect(calls[1][1]).toEqual(expect.objectContaining({ expectedIdentity: getStorageReadIdentity(), expectedAccountId: 'account-b' }))
  await act(async () => { finishNew({ ok: true }) })
  expect(screen.queryByPlaceholderText('Mensaje programado...')).not.toBeInTheDocument()
})

test('a late scheduled list cannot expose the previous conversation jobs', async () => {
  let finish!: (value: unknown) => void
  jest.mocked(fallbackApiJson).mockImplementation(async (path, init) => {
    if (path.endsWith('secure_get_chat_scheduled_jobs') && JSON.parse(init!.body as string).p_target_id === 'room-a') return new Promise(resolve => { finish = resolve })
    return []
  })
  const { rerender } = render(<MemoryRouter><ChatDetailPage chatIdOverride="room-a"/></MemoryRouter>)
  await screen.findByText('Mensaje recibido')
  rerender(<MemoryRouter><ChatDetailPage chatIdOverride="room-b"/></MemoryRouter>)
  await act(async () => { finish([{ id: 'old-job', content: 'Privado del chat A', status: 'pending' }]) })
  fireEvent.click(screen.getByRole('button', { name: 'Opciones del chat' }))
  fireEvent.click(screen.getByRole('menuitem', { name: 'Más opciones' }))
  fireEvent.click(screen.getByRole('button', { name: 'Programar mensaje' }))
  expect(screen.queryByText(/Privado del chat A/)).not.toBeInTheDocument()
  expect(screen.getByText('Sin mensajes programados')).toBeInTheDocument()
})

test('a late cancellation failure cannot show an error in the next conversation', async () => {
  let finishList!: (value: unknown) => void
  let rejectCancel!: (reason: Error) => void
  jest.mocked(fallbackApiJson).mockImplementation(async (path, init) => {
    if (path.endsWith('secure_get_chat_scheduled_jobs') && JSON.parse(init!.body as string).p_target_id === 'room-a') return new Promise(resolve => { finishList = resolve })
    if (path.endsWith('secure_cancel_scheduled_message_job')) return new Promise((_resolve, reject) => { rejectCancel = reject })
    return []
  })
  const { rerender } = render(<MemoryRouter><ChatDetailPage chatIdOverride="room-a"/></MemoryRouter>)
  await screen.findByText('Mensaje recibido')
  await act(async () => { finishList([{ id: 'old-job', content: 'Programado A', status: 'pending' }]) })
  fireEvent.click(screen.getByRole('button', { name: 'Opciones del chat' }))
  fireEvent.click(screen.getByRole('menuitem', { name: 'Más opciones' }))
  fireEvent.click(screen.getByRole('button', { name: 'Programar mensaje' }))
  const card = screen.getByText(/Programado A/).closest('.surface-subcard')!
  fireEvent.click(card.querySelector('button')!)
  await waitFor(() => expect(rejectCancel).toBeDefined())
  rerender(<MemoryRouter><ChatDetailPage chatIdOverride="room-b"/></MemoryRouter>)
  await act(async () => { rejectCancel(new Error('Error privado de cancelación A')) })
  expect(screen.queryByText('Error privado de cancelación A')).not.toBeInTheDocument()
})


test('renewing the same account identity resets the old scheduling lock', async () => {
  let finish!: (value: unknown) => void
  jest.mocked(fallbackApiJson).mockImplementation(async path => {
    if (path.endsWith('secure_schedule_message_job')) return new Promise(resolve => { finish = resolve })
    return []
  })
  open()
  await screen.findByText('Mensaje recibido')
  fireEvent.click(screen.getByRole('button', { name: 'Opciones del chat' }))
  fireEvent.click(screen.getByRole('menuitem', { name: 'Más opciones' }))
  fireEvent.click(screen.getByRole('button', { name: 'Programar mensaje' }))
  fireEvent.change(screen.getByPlaceholderText('Mensaje programado...'), { target: { value: 'Sesión anterior' } })
  fireEvent.click(screen.getByRole('button', { name: 'Programar', exact: true }))
  await waitFor(() => expect(finish).toBeDefined())
  await act(async () => { invalidateStorageReadBackend(); finish({ ok: true }) })
  expect(screen.queryByPlaceholderText('Mensaje programado...')).not.toBeInTheDocument()
  fireEvent.click(screen.getByRole('button', { name: 'Programar mensaje' }))
  expect(screen.getByPlaceholderText('Mensaje programado...')).toHaveValue('')
  expect(screen.getByRole('button', { name: 'Programar', exact: true })).toBeInTheDocument()
})

function openNicknameEditor() {
  fireEvent.click(screen.getByRole('button', { name: 'Opciones del chat' }))
  fireEvent.click(screen.getByRole('menuitem', { name: 'Cambiar apodo privado' }))
}

test.each(['success', 'failure'])('a late nickname %s cannot change the next conversation', async outcome => {
  let finish!: () => void
  jest.mocked(fallbackApiJson).mockImplementation(async path => {
    if (!path.includes('/nicknames/')) return []
    return new Promise((resolve, reject) => { finish = () => outcome === 'success' ? resolve({}) : reject(new Error('Apodo privado de A falló')) })
  })
  const { rerender } = render(<MemoryRouter><ChatDetailPage chatIdOverride="room-a"/></MemoryRouter>)
  await screen.findByText('Mensaje recibido')
  openNicknameEditor()
  fireEvent.change(screen.getByRole('textbox', { name: 'Apodo' }), { target: { value: 'Privado A' } })
  fireEvent.click(screen.getByRole('button', { name: 'Guardar apodo' }))
  await waitFor(() => expect(finish).toBeDefined())
  jest.mocked(loadFallbackChat).mockResolvedValue({ ...response, chat: {}, title: 'Contacto B', otherParticipant: { ...response.otherParticipant, id: 'friend-b', name: 'Contacto B' } } as never)
  rerender(<MemoryRouter><ChatDetailPage chatIdOverride="room-b"/></MemoryRouter>)
  await screen.findByRole('button', { name: 'Ver perfil de Contacto B' })
  openNicknameEditor()
  fireEvent.change(screen.getByRole('textbox', { name: 'Apodo' }), { target: { value: 'Borrador B' } })
  const changed = jest.fn()
  window.addEventListener('novvin:conversation-preferences-changed', changed)
  try {
    await act(async () => { finish() })
    expect(screen.getByRole('dialog', { name: 'Apodo privado' })).toBeInTheDocument()
    expect(screen.getByRole('textbox', { name: 'Apodo' })).toHaveValue('Borrador B')
    expect(screen.getByRole('button', { name: 'Ver perfil de Contacto B' })).toBeInTheDocument()
    expect(screen.queryByText('Privado A')).not.toBeInTheDocument()
    expect(screen.queryByText('Apodo privado de A falló')).not.toBeInTheDocument()
    expect(changed).not.toHaveBeenCalled()
  } finally { window.removeEventListener('novvin:conversation-preferences-changed', changed) }
})

test.each(['PUT', 'DELETE'])('nickname %s is bound to its originating account and identity', async method => {
  open()
  await screen.findByText('Mensaje recibido')
  openNicknameEditor()
  fireEvent.change(screen.getByRole('textbox', { name: 'Apodo' }), { target: { value: method === 'PUT' ? 'Mi apodo' : '' } })
  fireEvent.click(screen.getByRole('button', { name: 'Guardar apodo' }))
  await waitFor(() => expect(fallbackApiJson).toHaveBeenCalledWith('/v1/data/messages/nicknames/friend', expect.objectContaining({ method, expectedIdentity: getStorageReadIdentity(), expectedAccountId: 'account-a' })))
  expect(screen.queryByRole('dialog', { name: 'Apodo privado' })).not.toBeInTheDocument()
})

test.each(['renewal', 'account switch'])('identity %s releases the old nickname lock without releasing a newer save', async transition => {
  const pending: ((value: unknown) => void)[] = []
  jest.mocked(fallbackApiJson).mockImplementation(async path => {
    if (!path.includes('/nicknames/')) return []
    return new Promise(resolve => pending.push(resolve))
  })
  open()
  await screen.findByText('Mensaje recibido')
  openNicknameEditor()
  fireEvent.change(screen.getByRole('textbox', { name: 'Apodo' }), { target: { value: 'Anterior' } })
  fireEvent.click(screen.getByRole('button', { name: 'Guardar apodo' }))
  await waitFor(() => expect(pending).toHaveLength(1))
  await act(async () => {
    if (transition === 'account switch') { mockUser.id = 'account-b'; updateStorageReadIdentity('account-b', 'fallback') }
    else invalidateStorageReadBackend()
  })
  expect(screen.queryByRole('dialog', { name: 'Apodo privado' })).not.toBeInTheDocument()
  openNicknameEditor()
  expect(screen.getByRole('textbox', { name: 'Apodo' })).toHaveValue('Apodo privado')
  fireEvent.change(screen.getByRole('textbox', { name: 'Apodo' }), { target: { value: 'Nuevo' } })
  fireEvent.click(screen.getByRole('button', { name: 'Guardar apodo' }))
  await waitFor(() => expect(pending).toHaveLength(2))
  await act(async () => { pending[0]({}) })
  expect(screen.getByRole('textbox', { name: 'Apodo' })).toHaveValue('Nuevo')
  expect(screen.getByRole('button', { name: 'Guardando…' })).toBeDisabled()
  await act(async () => { pending[1]({}) })
  expect(screen.getByRole('button', { name: 'Ver perfil de Nuevo' })).toBeInTheDocument()
  expect(screen.queryByRole('dialog', { name: 'Apodo privado' })).not.toBeInTheDocument()
})


test.each(['success', 'failure'])('a late private reaction %s cannot enter the next conversation', async outcome => {
  let finish!: () => void
  jest.mocked(loadFallbackChat).mockResolvedValue({ ...response, messages: [{ ...incoming, reactions: { '👍': ['friend'] } }] } as never)
  jest.mocked(toggleFallbackChatReaction).mockImplementation(() => new Promise((resolve, reject) => {
    finish = () => outcome === 'success' ? resolve({ message: { ...incoming, id: 'old-private-message', content: 'Contenido privado A' } }) : reject(new Error('Error privado reacción A'))
  }))
  const { rerender } = render(<MemoryRouter><ChatDetailPage chatIdOverride="room-a"/></MemoryRouter>)
  await screen.findByText('Mensaje recibido')
  fireEvent.click(screen.getByRole('button', { name: '👍 1' }))
  await waitFor(() => expect(finish).toBeDefined())
  jest.mocked(loadFallbackChat).mockResolvedValue({ ...response, messages: [{ ...incoming, id: 'message-b', content: 'Conversación B' }] } as never)
  rerender(<MemoryRouter><ChatDetailPage chatIdOverride="room-b"/></MemoryRouter>)
  await screen.findByText('Conversación B')
  await act(async () => { finish() })
  expect(screen.queryByText('Contenido privado A')).not.toBeInTheDocument()
  expect(screen.queryByText('Error privado reacción A')).not.toBeInTheDocument()
  expect(screen.getByText('Conversación B')).toBeInTheDocument()
})

test.each(['renewal', 'switch'])('an identity %s suppresses an old private reaction response', async transition => {
  let finish!: (value: { message: typeof incoming }) => void
  jest.mocked(loadFallbackChat).mockResolvedValue({ ...response, messages: [{ ...incoming, reactions: { '👍': ['friend'] } }] } as never)
  jest.mocked(toggleFallbackChatReaction).mockImplementation(() => new Promise(resolve => { finish = resolve }))
  open()
  await screen.findByText('Mensaje recibido')
  fireEvent.click(screen.getByRole('button', { name: '👍 1' }))
  await waitFor(() => expect(finish).toBeDefined())
  await act(async () => {
    if (transition === 'switch') { mockUser.id = 'account-b'; updateStorageReadIdentity('account-b', 'fallback') }
    else invalidateStorageReadBackend()
    finish({ message: { ...incoming, id: 'private-response', content: 'Respuesta de sesión anterior' } })
  })
  expect(screen.queryByText('Respuesta de sesión anterior')).not.toBeInTheDocument()
})

test('a private reaction cannot start while auth and transport identities disagree', async () => {
  jest.mocked(loadFallbackChat).mockResolvedValue({ ...response, messages: [{ ...incoming, reactions: { '👍': ['friend'] } }] } as never)
  jest.mocked(toggleFallbackChatReaction).mockResolvedValue({ message: incoming })
  open()
  await screen.findByText('Mensaje recibido')
  act(() => updateStorageReadIdentity('account-b', 'fallback'))
  fireEvent.click(screen.getByRole('button', { name: '👍 1' }))
  expect(toggleFallbackChatReaction).not.toHaveBeenCalled()
})


const deleteScopes = ['chat', 'group', 'community'] as const
function chooseDelete(scope: typeof deleteScopes[number], forEveryone: boolean) {
  const article = screen.getByText('Mensaje propio').closest('article')!
  fireEvent.click(article.querySelector('summary')!)
  const label = scope === 'chat' ? (forEveryone ? 'Eliminar para todos' : 'Eliminar para mí') : (forEveryone ? 'Borrar todos' : 'Borrar mi vista')
  fireEvent.click(Array.from(article.querySelectorAll('button')).find(button => button.textContent?.trim() === label)!)
}
const deletionCases = deleteScopes.flatMap(scope => [true, false].flatMap(forEveryone => ['success', 'failure'].map(outcome => [scope, forEveryone, outcome] as const)))
test.each(deletionCases)('%s delete all=%s ignores a stale %s in the next account/conversation', async (scope, forEveryone, outcome) => {
  let finish!: () => void
  const deleter = scope === 'chat' ? deleteFallbackChatMessage : deleteFallbackRoomMessage
  jest.mocked(deleter).mockImplementation(() => new Promise((resolve, reject) => {
    finish = () => outcome === 'success' ? resolve({ message: { ...outgoing, content: forEveryone ? '' : outgoing.content, deleted_for_all: forEveryone, deleted_by: forEveryone ? [] : ['account-a'] } }) : reject(new Error('Error privado borrado A'))
  }))
  const page = (id: string) => <MemoryRouter>{scope === 'chat' ? <ChatDetailPage chatIdOverride={id}/> : <RoomChatPage scope={scope} roomId={id}/>}</MemoryRouter>
  const { rerender, container } = render(page('room-a'))
  await screen.findByText('Mensaje propio')
  chooseDelete(scope, forEveryone)
  await waitFor(() => expect(finish).toBeDefined())
  const loader = scope === 'chat' ? loadFallbackChat : loadFallbackRoomChat
  jest.mocked(loader).mockResolvedValue({ ...response, messages: [{ ...incoming, id: 'new-message', content: 'Cuenta B' }] } as never)
  await act(async () => { mockUser.id = 'account-b'; updateStorageReadIdentity('account-b', 'fallback') })
  rerender(page('room-b'))
  await screen.findByText('Cuenta B')
  await act(async () => { finish() })
  expect(container.querySelector('#message-' + outgoing.id)).not.toBeInTheDocument()
  expect(screen.queryByText('Error privado borrado A')).not.toBeInTheDocument()
  expect(screen.getByText('Cuenta B')).toBeInTheDocument()
})

test.each(deleteScopes)('%s delete cannot start with mismatching auth and transport identities', async scope => {
  const deleter = scope === 'chat' ? deleteFallbackChatMessage : deleteFallbackRoomMessage
  jest.mocked(deleter).mockResolvedValue({ message: outgoing })
  open(scope)
  await screen.findByText('Mensaje propio')
  act(() => updateStorageReadIdentity('account-b', 'fallback'))
  chooseDelete(scope, true)
  expect(deleter).not.toHaveBeenCalled()
})

test('a stale bulk deletion cannot block or release the next conversation batch', async () => {
  const pending: (() => void)[] = []
  jest.mocked(deleteFallbackChatMessage).mockImplementation((_chat, id) => new Promise(resolve => { pending.push(() => resolve({ message: { ...incoming, id, deleted_by: ['account-a'] } })) }))
  const confirm = jest.spyOn(window, 'confirm').mockReturnValue(true)
  const { rerender, container } = render(<MemoryRouter><ChatDetailPage chatIdOverride="room-a"/></MemoryRouter>)
  const selectAll = () => {
    const article = screen.getByText('Mensaje propio').closest('article')!
    fireEvent.click(Array.from(article.querySelectorAll('button')).find(button => button.textContent?.trim() === 'Seleccionar mensaje')!)
    fireEvent.click(screen.getByRole('button', { name: 'Seleccionar visibles' }))
  }
  const batchButton = () => Array.from(container.querySelector('[aria-label="Mensajes seleccionados"]')!.querySelectorAll('button')).find(button => /Eliminar para mí|Eliminando…/.test(button.textContent || ''))!
  try {
    await screen.findByText('Mensaje propio')
    selectAll(); fireEvent.click(batchButton())
    await waitFor(() => expect(pending).toHaveLength(1))
    rerender(<MemoryRouter><ChatDetailPage chatIdOverride="room-b"/></MemoryRouter>)
    await screen.findByText('Mensaje propio')
    selectAll()
    expect(batchButton()).toBeEnabled()
    fireEvent.click(batchButton())
    await waitFor(() => expect(pending).toHaveLength(2))
    await act(async () => { pending[0]() })
    expect(batchButton()).toBeDisabled()
    expect(batchButton()).toHaveTextContent('Eliminando…')
    await act(async () => { pending[1]() })
    await waitFor(() => expect(pending).toHaveLength(3))
    await act(async () => { pending[2]() })
    expect(screen.queryByRole('region', { name: 'Mensajes seleccionados' })).not.toBeInTheDocument()
  } finally { confirm.mockRestore() }
})

test.each(['renewed-login', 'backend-change'])('bulk %s stops the old batch and preserves the new busy state in the same conversation', async mode => {
  const pending: (() => void)[] = []
  jest.mocked(deleteFallbackChatMessage).mockImplementation((_chat, id) => new Promise(resolve => { pending.push(() => resolve({ message: { ...incoming, id, deleted_by: ['account-a'] } })) }))
  const confirm = jest.spyOn(window, 'confirm').mockReturnValue(true)
  const { container } = render(<MemoryRouter><ChatDetailPage chatIdOverride="room-a"/></MemoryRouter>)
  const selectAll = () => {
    const article = screen.getByText('Mensaje propio').closest('article')!
    fireEvent.click(Array.from(article.querySelectorAll('button')).find(button => button.textContent?.trim() === 'Seleccionar mensaje')!)
    fireEvent.click(screen.getByRole('button', { name: 'Seleccionar visibles' }))
  }
  const batchButton = () => Array.from(container.querySelector('[aria-label="Mensajes seleccionados"]')!.querySelectorAll('button')).find(button => /Eliminar para mí|Eliminando…/.test(button.textContent || ''))!
  try {
    await screen.findByText('Mensaje propio')
    selectAll(); fireEvent.click(batchButton())
    await waitFor(() => expect(pending).toHaveLength(1))
    await act(async () => {
      if(mode === 'renewed-login') { updateStorageReadIdentity('', ''); updateStorageReadIdentity('account-a', 'fallback') }
      else invalidateStorageReadBackend()
    })
    expect(screen.queryByRole('region', { name: 'Mensajes seleccionados' })).not.toBeInTheDocument()
    selectAll()
    expect(batchButton()).toBeEnabled()
    fireEvent.click(batchButton())
    await waitFor(() => expect(pending).toHaveLength(2))
    await act(async () => { pending[0]() })
    expect(pending).toHaveLength(2)
    expect(batchButton()).toBeDisabled()
    expect(batchButton()).toHaveTextContent('Eliminando…')
    await act(async () => { pending[1]() })
    await waitFor(() => expect(pending).toHaveLength(3))
    await act(async () => { pending[2]() })
    expect(screen.queryByRole('region', { name: 'Mensajes seleccionados' })).not.toBeInTheDocument()
    expect(deleteFallbackChatMessage).toHaveBeenCalledTimes(3)
  } finally { confirm.mockRestore() }
})


function openPollEditor(scope: 'chat' | 'group' | 'community') {
  if (scope === 'chat') {
    fireEvent.click(screen.getByRole('button', { name: 'Opciones del chat' }))
    fireEvent.click(screen.getByRole('menuitem', { name: 'Más opciones' }))
    const create = screen.queryByRole('button', { name: 'Nueva encuesta' })
    if (create) fireEvent.click(create)
  } else if (!screen.queryByPlaceholderText('Pregunta')) fireEvent.click(screen.getByTitle('Nueva encuesta'))
}

test.each(deleteScopes.flatMap(scope => ['success', 'failure'].map(outcome => [scope, outcome] as const)))('%s poll creation ignores old %s after changing conversation', async (scope, outcome) => {
  let finish!: () => void
  jest.mocked(backendRpc).mockImplementation(async name => {
    if (!/^secure_create_(chat|group|community)_poll_message$/.test(name)) return { data: [], error: null }
    return new Promise(resolve => { finish = () => resolve(outcome === 'success' ? { data: {}, error: null } : { data: null, error: new Error('Error encuesta A') }) })
  })
  const page = (id: string) => <MemoryRouter>{scope === 'chat' ? <ChatDetailPage chatIdOverride={id}/> : <RoomChatPage scope={scope} roomId={id}/>}</MemoryRouter>
  const { rerender } = render(page('room-a'))
  await screen.findByText('Mensaje recibido')
  openPollEditor(scope)
  fireEvent.change(screen.getByPlaceholderText('Pregunta'), { target: { value: 'Encuesta A' } })
  fireEvent.click(screen.getByRole('button', { name: scope === 'chat' ? 'Crear' : 'Crear encuesta', exact: true }))
  await waitFor(() => expect(finish).toBeDefined())
  const loader = scope === 'chat' ? loadFallbackChat : loadFallbackRoomChat
  jest.mocked(loader).mockResolvedValue({ ...response, messages: [{ ...incoming, id: 'b-message', content: 'Conversación B' }] } as never)
  rerender(page('room-b'))
  await screen.findByText('Conversación B')
  openPollEditor(scope)
  fireEvent.change(screen.getByPlaceholderText('Pregunta'), { target: { value: 'Borrador B' } })
  const requestsBeforeOldResponse = jest.mocked(loader).mock.calls.length
  await act(async () => { finish() })
  expect(screen.getByPlaceholderText('Pregunta')).toHaveValue('Borrador B')
  expect(screen.queryByText('Error encuesta A')).not.toBeInTheDocument()
  expect(jest.mocked(loader).mock.calls).toHaveLength(requestsBeforeOldResponse)
})

test.each(deleteScopes)('%s poll cannot start while auth and transport disagree', async scope => {
  open(scope)
  await screen.findByText('Mensaje recibido')
  openPollEditor(scope)
  fireEvent.change(screen.getByPlaceholderText('Pregunta'), { target: { value: 'No enviar con B' } })
  act(() => updateStorageReadIdentity('account-b', 'fallback'))
  // A context reset may close the form; reopening must still refuse this old auth user.
  if (!screen.queryByPlaceholderText('Pregunta')) {
    if (scope === 'chat') fireEvent.click(screen.getByRole('button', { name: 'Nueva encuesta' }))
    else openPollEditor(scope)
    fireEvent.change(screen.getByPlaceholderText('Pregunta'), { target: { value: 'No enviar con B' } })
  }
  fireEvent.click(screen.getByRole('button', { name: scope === 'chat' ? 'Crear' : 'Crear encuesta', exact: true }))
  expect(jest.mocked(backendRpc).mock.calls.filter(([name]) => /^secure_create_(chat|group|community)_poll_message$/.test(name))).toHaveLength(0)
})

test.each(deleteScopes)('%s poll identity renewal resets its lock without releasing a newer creation', async scope => {
  const pending: (() => void)[] = []
  jest.mocked(backendRpc).mockImplementation(async name => {
    if (!/^secure_create_(chat|group|community)_poll_message$/.test(name)) return { data: [], error: null }
    return new Promise(resolve => pending.push(() => resolve({ data: {}, error: null })))
  })
  open(scope)
  await screen.findByText('Mensaje recibido')
  openPollEditor(scope)
  fireEvent.change(screen.getByPlaceholderText('Pregunta'), { target: { value: 'Encuesta antigua' } })
  fireEvent.click(screen.getByRole('button', { name: scope === 'chat' ? 'Crear' : 'Crear encuesta', exact: true }))
  await waitFor(() => expect(pending).toHaveLength(1))
  act(() => invalidateStorageReadBackend())
  expect(screen.queryByPlaceholderText('Pregunta')).not.toBeInTheDocument()
  if (scope === 'chat') fireEvent.click(screen.getByRole('button', { name: 'Nueva encuesta' }))
  else openPollEditor(scope)
  expect(screen.getByPlaceholderText('Pregunta')).toHaveValue('')
  fireEvent.change(screen.getByPlaceholderText('Pregunta'), { target: { value: 'Encuesta nueva' } })
  fireEvent.click(screen.getByRole('button', { name: scope === 'chat' ? 'Crear' : 'Crear encuesta', exact: true }))
  await waitFor(() => expect(pending).toHaveLength(2))
  await act(async () => { pending[0]() })
  expect(screen.getByPlaceholderText('Pregunta')).toHaveValue('Encuesta nueva')
  expect(screen.getByRole('button', { name: 'Creando...' })).toBeInTheDocument()
  await act(async () => { pending[1]() })
  expect(screen.queryByPlaceholderText('Pregunta')).not.toBeInTheDocument()
})
