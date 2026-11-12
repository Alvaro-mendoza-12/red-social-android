import userEvent from '@testing-library/user-event'
import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Routes, Route } from 'react-router-dom'
import { MessagesPage } from '../../../features/messages/MessagesPage'
import { hideFallbackChat, saveFallbackChatPreferences, loadFallbackMessagesInbox, searchFallbackProfiles } from '../../../core/fallbackData'
import { readCachedValue } from '../../../core/localCache'
import { getOrCreateDirectChat } from '../../../features/messages/directChat'
import { markInboxConversationRead, notifyInboxMessage } from '../../../core/inboxActivity'

const mockUser = { id: 'viewer' }
jest.mock('../../../core/auth/AuthProvider', () => ({ useAuth: () => ({ user: mockUser, isFallbackSession: true }) }))
jest.mock('../../../core/auth/fallbackAuth', () => ({ isFallbackAuthConfigured: true, fallbackApiJson: jest.fn(async () => []) }))
jest.mock('../../../core/supabase', () => ({ supabase: null, resolveSupabasePublicUrl: (_b: string, u: string) => u }))
jest.mock('../../../core/fallbackData', () => ({
  hideFallbackChat: jest.fn(),
  saveFallbackChatPreferences: jest.fn(),
  loadFallbackMessagesInbox: jest.fn(async () => ({ items: [] })),
  searchFallbackProfiles: jest.fn(async () => ({ items: [{ id: 'peer', displayName: 'Persona nueva', username: 'persona' }] })),
}))
jest.mock('../../../features/messages/directChat', () => ({ getOrCreateDirectChat: jest.fn(async () => 'empty-chat') }))
jest.mock('../../../features/messages/ChatDetailPage', () => ({ ChatDetailPage: ({ chatIdOverride }: { chatIdOverride: string }) => <div data-testid="opened-chat">{chatIdOverride}<input aria-label="Escribir mensaje"/></div> }))
jest.mock('../../../features/messages/RoomChatPage', () => ({ RoomChatPage: () => null }))
jest.mock('../../../components/SafeAvatar', () => ({ SafeAvatar: () => null }))
jest.mock('../../../components/ProfileStatusBadges', () => ({ ProfileStatusBadges: () => null }))
jest.mock('../../../core/useVoiceDirectoryUpdates', () => ({ useVoiceDirectoryUpdates: () => {} }))

beforeEach(() => {
  localStorage.clear()
  jest.clearAllMocks()
  mockUser.id = 'viewer'
  Object.defineProperty(window, 'matchMedia', { configurable: true, value: () => ({ matches: false, addEventListener() {}, removeEventListener() {} }) })
})

test('incoming activity updates favorite and archive badges despite search and reading clears them', async () => {
  mockUser.id = 'filter-counter-owner'
  const row = (id: string, archived: boolean) => ({id, title:id, subtitle:'Ejemplo ficticio', updatedAt:'2026-11-12T10:00:00Z', kind:'chat', avatarUrl:'', profileId:id, unreadCount:0, isPinned:false, isMuted:false, isArchived:archived, isFavorite:true, folders:[]})
  jest.mocked(loadFallbackMessagesInbox).mockResolvedValue({items:[row('favorite-counter',false), row('archive-counter',true)]} as never)
  render(<MemoryRouter><MessagesPage/></MemoryRouter>)
  await screen.findByText('favorite-counter')
  act(() => {
    for (const id of ['favorite-counter','archive-counter']) notifyInboxMessage(mockUser.id,'chat',id,{id:'message-'+id,sender_id:'peer',created_at:'2026-11-12T10:01:00Z',content:'Mensaje ficticio',status:'sent'},true)
  })
  expect(screen.getByLabelText('1 conversaciones sin leer en Favoritos')).toBeInTheDocument()
  expect(screen.getByLabelText('1 conversaciones sin leer en Archivados')).toBeInTheDocument()
  fireEvent.change(screen.getByPlaceholderText(/Buscar/),{target:{value:'sin coincidencias'}})
  expect(screen.getByLabelText('1 conversaciones sin leer en Favoritos')).toBeInTheDocument()
  act(() => {
    markInboxConversationRead(mockUser.id,'chat','favorite-counter','2026-11-12T10:01:00Z')
    markInboxConversationRead(mockUser.id,'chat','archive-counter','2026-11-12T10:01:00Z')
  })
  expect(screen.queryByLabelText('1 conversaciones sin leer en Favoritos')).not.toBeInTheDocument()
  expect(screen.queryByLabelText('1 conversaciones sin leer en Archivados')).not.toBeInTheDocument()
})

test('a direct conversation opens in compact mode and restores the inbox beside it on expansion',async()=>{
 let matches=true
 const changes=new Set<()=>void>()
 Object.defineProperty(window,'matchMedia',{configurable:true,value:()=>({get matches(){return matches},addEventListener:(_name:string,fn:()=>void)=>changes.add(fn),removeEventListener:(_name:string,fn:()=>void)=>changes.delete(fn)})})
 render(<MemoryRouter initialEntries={['/app/messages/direct-qa']}><Routes><Route path="/app/messages/:chatId" element={<MessagesPage/>}/></Routes></MemoryRouter>)
 expect(screen.getByTestId('opened-chat')).toHaveTextContent('direct-qa')
 expect(screen.queryByLabelText('Lista de conversaciones')).toBeNull()
 act(()=>{matches=false;changes.forEach(fn=>fn())})
 expect(await screen.findByLabelText('Lista de conversaciones')).toBeInTheDocument()
 expect(screen.getByTestId('opened-chat')).toHaveTextContent('direct-qa')
 act(()=>{matches=true;changes.forEach(fn=>fn())})
 expect(screen.queryByLabelText('Lista de conversaciones')).toBeNull()
 expect(screen.getByTestId('opened-chat')).toHaveTextContent('direct-qa')
})

test('a discovered person without inbox history opens immediately and survives inbox refresh', async () => {
  render(<MemoryRouter><MessagesPage/></MemoryRouter>)
  const input = screen.getByPlaceholderText(/Buscar/)
  fireEvent.change(input, { target: { value: 'persona' } })
  fireEvent.click(await screen.findByRole('button', { name: /Persona nueva/ }))
  expect(await screen.findByTestId('opened-chat')).toHaveTextContent('empty-chat')
  await waitFor(() => expect(loadFallbackMessagesInbox).toHaveBeenCalledTimes(2))
  expect(screen.getByTestId('opened-chat')).toBeVisible()
  expect(getOrCreateDirectChat).toHaveBeenCalledWith('viewer', 'peer')
  expect(document.querySelectorAll('.messages-list-pane .conversation-row:not(.discover-row)')).toHaveLength(0)
})

test('a transient empty-chat selection does not cross accounts', async () => {
  const { rerender } = render(<MemoryRouter><MessagesPage/></MemoryRouter>)
  fireEvent.change(screen.getByPlaceholderText(/Buscar/), { target: { value: 'persona' } })
  fireEvent.click(await screen.findByRole('button', { name: /Persona nueva/ }))
  await screen.findByTestId('opened-chat')
  mockUser.id = 'other-account'
  rerender(<MemoryRouter><MessagesPage/></MemoryRouter>)
  await waitFor(() => expect(screen.queryByTestId('opened-chat')).not.toBeInTheDocument())
})

test('refreshing the same inbox does not hide people or repeat the directory query', async () => {
  render(<MemoryRouter><MessagesPage/></MemoryRouter>)
  fireEvent.change(screen.getByPlaceholderText(/Buscar/), { target: { value: 'persona' } })
  const result = await screen.findByRole('button', { name: /Persona nueva/ })
  expect(searchFallbackProfiles).toHaveBeenCalledTimes(1)
  act(() => { window.dispatchEvent(new Event('novvin:inbox-changed')) })
  await waitFor(() => expect(loadFallbackMessagesInbox).toHaveBeenCalledTimes(2))
  await act(async () => { await new Promise(resolve => setTimeout(resolve, 350)) })
  expect(screen.getByRole('button', { name: /Persona nueva/ })).toBe(result)
  expect(searchFallbackProfiles).toHaveBeenCalledTimes(1)
  expect(screen.queryByText('Buscando…')).not.toBeInTheDocument()
})

test('changing the query hides stale people immediately while the next query is pending', async () => {
  render(<MemoryRouter><MessagesPage/></MemoryRouter>)
  const input = screen.getByPlaceholderText(/Buscar/)
  fireEvent.change(input, { target: { value: 'persona' } })
  await screen.findByRole('button', { name: /Persona nueva/ })
  fireEvent.change(input, { target: { value: 'otra' } })
  expect(screen.queryByRole('button', { name: /Persona nueva/ })).not.toBeInTheDocument()
  expect(screen.getByText('Buscando…')).toBeInTheDocument()
})


test.each(['other account', 'return to same account', 'another tab'])('a failed hide cannot restore an obsolete inbox after %s', async (change) => {
  const row = (title: string) => ({ id: 'shared-chat', title, subtitle: '', updatedAt: '', kind: 'chat', avatarUrl: 'avatar.png', profileId: null, unreadCount: 0, isPinned: false, isMuted: false, isArchived: false, isFavorite: false, folders: [], openPath: '' })
  let rejectHide!: (error: Error) => void
  jest.mocked(hideFallbackChat).mockImplementationOnce(() => new Promise((_resolve, reject) => { rejectHide = reject }))
  jest.mocked(loadFallbackMessagesInbox).mockResolvedValueOnce({ items: [row('Chat privado de A')] } as never)
  jest.spyOn(window, 'confirm').mockReturnValue(true)
  const { rerender } = render(<MemoryRouter><MessagesPage/></MemoryRouter>)
  await screen.findByText('Chat privado de A')
  fireEvent.click(screen.getByRole('button', { name: /^Opciones de / }))
  fireEvent.click(screen.getByRole('menuitem', { name: 'Eliminar chat' }))
  expect(screen.queryByText('Chat privado de A')).not.toBeInTheDocument()
  jest.mocked(loadFallbackMessagesInbox).mockResolvedValueOnce({ items: [row('Chat privado de B')] } as never)
  if (change === 'another tab') {
    fireEvent.click(screen.getByRole('button', { name: 'Grupos' }))
  } else {
    mockUser.id = 'account-b'
    rerender(<MemoryRouter><MessagesPage/></MemoryRouter>)
  }
  await screen.findByText('Chat privado de B')
  if (change === 'return to same account') {
    jest.mocked(loadFallbackMessagesInbox).mockResolvedValueOnce({ items: [row('Chat nuevo de A')] } as never)
    mockUser.id = 'viewer'
    rerender(<MemoryRouter><MessagesPage/></MemoryRouter>)
    await screen.findByText('Chat nuevo de A')
  }
  await act(async () => { rejectHide(new Error('No se pudo ocultar el chat A')) })
  expect(screen.queryByText('Chat privado de A')).not.toBeInTheDocument()
  expect(screen.getByText(change === 'return to same account' ? 'Chat nuevo de A' : 'Chat privado de B')).toBeInTheDocument()
  expect(screen.queryByText('No se pudo ocultar el chat A')).not.toBeInTheDocument()
})


test('a hide failure in the current context restores its own cache and reports failure', async () => {
  const row = { id: 'own-chat', title: 'Conversación actual', subtitle: '', updatedAt: '', kind: 'chat', avatarUrl: 'avatar.png', profileId: null, unreadCount: 0, isPinned: false, isMuted: false, isArchived: false, isFavorite: false, folders: [], openPath: '' }
  jest.mocked(loadFallbackMessagesInbox).mockResolvedValueOnce({ items: [row] } as never)
  jest.mocked(hideFallbackChat).mockRejectedValueOnce(new Error('No se pudo ocultar el chat actual'))
  jest.spyOn(window, 'confirm').mockReturnValue(true)
  render(<MemoryRouter><MessagesPage/></MemoryRouter>)
  await screen.findByText('Conversación actual')
  fireEvent.click(screen.getByRole('button', { name: /^Opciones de / }))
  fireEvent.click(screen.getByRole('menuitem', { name: 'Eliminar chat' }))
  expect(await screen.findByText('No se pudo ocultar el chat actual')).toBeInTheDocument()
  expect(readCachedValue<Array<{id: string}>>('messages:viewer:chats', 60_000)?.map(item => item.id)).toEqual(['own-chat'])
})


test.each(['success', 'failure'])('old preference %s cannot change the new account inbox', async outcome => {
  const row = (title: string) => ({ id: 'shared-chat', title, subtitle: '', updatedAt: '', kind: 'chat', avatarUrl: 'avatar.png', profileId: null, unreadCount: 0, isPinned: false, isMuted: false, isArchived: false, isFavorite: false, folders: [], openPath: '' })
  let finish!: () => void
  jest.mocked(saveFallbackChatPreferences).mockImplementationOnce(() => new Promise((resolve, reject) => {
    finish = () => outcome === 'success' ? resolve({ ok: true }) : reject(new Error('Error de la cuenta anterior'))
  }))
  jest.mocked(loadFallbackMessagesInbox).mockResolvedValueOnce({ items: [row('Chat A')] } as never)
  const { rerender } = render(<MemoryRouter><MessagesPage/></MemoryRouter>)
  await screen.findByText('Chat A')
  fireEvent.click(screen.getByRole('button', { name: /^Opciones de / }))
  fireEvent.click(screen.getByRole('menuitem', { name: 'Fijar chat' }))
  jest.mocked(loadFallbackMessagesInbox).mockResolvedValueOnce({ items: [row('Chat B')] } as never)
  mockUser.id = 'account-b'
  rerender(<MemoryRouter><MessagesPage/></MemoryRouter>)
  await screen.findByText('Chat B')
  await act(async () => { finish() })
  expect(screen.queryByText('Error de la cuenta anterior')).not.toBeInTheDocument()
  fireEvent.click(screen.getByRole('button', { name: /^Opciones de / }))
  expect(screen.getByRole('menuitem', { name: 'Fijar chat' })).toBeInTheDocument()
})


test('inbox options stay separate from opening the chat and restore keyboard focus', async () => {
  const user = userEvent.setup()
  const row = { id: 'separate-actions', title: 'Contacto de prueba', subtitle: 'Hola', updatedAt: '', kind: 'chat', avatarUrl: '', profileId: null, unreadCount: 0, isPinned: false, isMuted: false, isArchived: false, isFavorite: false, folders: [], openPath: '' }
  jest.mocked(loadFallbackMessagesInbox).mockResolvedValue({ items: [row] } as never)
  render(<MemoryRouter><MessagesPage/></MemoryRouter>)
  const open = await screen.findByRole('button', { name: /^Abrir .* Contacto de prueba$/ })
  const options = screen.getByRole('button', { name: 'Opciones de Contacto de prueba' })
  expect(open.contains(options)).toBe(false)
  options.focus()
  await act(async () => { await user.keyboard('{Enter}') })
  expect(await screen.findByRole('menu', { name: 'Opciones del chat con Contacto de prueba' })).toBeVisible()
  expect(screen.queryByTestId('opened-chat')).not.toBeInTheDocument()
  await act(async () => { await user.keyboard('{Escape}') })
  expect(screen.queryByRole('menu')).not.toBeInTheDocument()
  expect(options).toHaveFocus()
  open.focus()
  await act(async () => { await user.keyboard('{Enter}') })
  expect(await screen.findByTestId('opened-chat')).toHaveTextContent('separate-actions')
})
