import { act, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Routes, Route } from 'react-router-dom'
import { MessagesPage } from '../../../features/messages/MessagesPage'
import { loadFallbackMessagesInbox, searchFallbackProfiles } from '../../../core/fallbackData'
import { getOrCreateDirectChat } from '../../../features/messages/directChat'

const mockUser = { id: 'viewer' }
jest.mock('../../../core/auth/AuthProvider', () => ({ useAuth: () => ({ user: mockUser, isFallbackSession: true }) }))
jest.mock('../../../core/auth/fallbackAuth', () => ({ isFallbackAuthConfigured: true, fallbackApiJson: jest.fn(async () => []) }))
jest.mock('../../../core/supabase', () => ({ supabase: null, resolveSupabasePublicUrl: (_b: string, u: string) => u }))
jest.mock('../../../core/fallbackData', () => ({
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
