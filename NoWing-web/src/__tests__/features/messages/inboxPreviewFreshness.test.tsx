import { act, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { MessagesPage } from '../../../features/messages/MessagesPage'
import { loadFallbackMessagesInbox } from '../../../core/fallbackData'
import { fallbackApiJson } from '../../../core/auth/fallbackAuth'
import { acknowledgeInboxPreview, latestInboxMessage, notifyInboxMessage } from '../../../core/inboxActivity'

const mockUser = { id: '' }
jest.mock('../../../core/auth/AuthProvider', () => ({ useAuth: () => ({ user: mockUser, isFallbackSession: true }) }))
jest.mock('../../../core/auth/fallbackAuth', () => ({ isFallbackAuthConfigured: true, fallbackApiJson: jest.fn(async () => []) }))
jest.mock('../../../core/supabase', () => ({ supabase: null, resolveSupabasePublicUrl: (_b: string, u: string) => u }))
jest.mock('../../../core/fallbackData', () => ({ loadFallbackMessagesInbox: jest.fn(), searchFallbackProfiles: jest.fn(async () => ({ items: [] })) }))
jest.mock('../../../features/messages/ChatDetailPage', () => ({ ChatDetailPage: () => null }))
jest.mock('../../../features/messages/RoomChatPage', () => ({ RoomChatPage: () => null }))
jest.mock('../../../components/SafeAvatar', () => ({ SafeAvatar: () => null }))
jest.mock('../../../components/ProfileStatusBadges', () => ({ ProfileStatusBadges: () => null }))
jest.mock('../../../core/useVoiceDirectoryUpdates', () => ({ useVoiceDirectoryUpdates: () => {} }))

let account = 0
beforeEach(() => {
  localStorage.clear()
  jest.resetAllMocks()
  mockUser.id = `fresh-inbox-${++account}`
  jest.mocked(fallbackApiJson).mockResolvedValue([])
  Object.defineProperty(window, 'matchMedia', { configurable: true, value: () => ({ matches: false, addEventListener() {}, removeEventListener() {} }) })
})

const row = (at = '2026-11-16T10:00:00.123Z') => ({
  id: 'chat', title: 'Contacto ficticio', subtitle: 'Mensaje anterior de ella', kind: 'chat' as const,
  updatedAt: at, lastMessageId: 'incoming-old', lastMessageSenderId: 'peer', lastMessageIsOwn: false,
  avatarUrl: 'avatar.png', profileId: null, unreadCount: 0, isPinned: false, isMuted: false,
  isArchived: false, isFavorite: false, folders: [],
})
const sent = (at: string) => ({ id: 'outgoing', sender_id: mockUser.id, content: 'Mi respuesta nueva', content_type: 'text', created_at: at, status: 'sent' })

test('an older inbox response in the same millisecond cannot replace an acknowledged outgoing preview', async () => {
  const old = row('2026-11-16T10:00:00.123100Z')
  jest.mocked(loadFallbackMessagesInbox).mockResolvedValue({ items: [old] })
  render(<MemoryRouter><MessagesPage /></MemoryRouter>)
  await screen.findByText('Mensaje anterior de ella')
  act(() => notifyInboxMessage(mockUser.id, 'chat', 'chat', sent('2026-11-16T10:00:00.123900Z')))
  expect(screen.getByText('Tú: Mi respuesta nueva')).toBeInTheDocument()
  await waitFor(() => expect(loadFallbackMessagesInbox).toHaveBeenCalledTimes(2))
  // Wait for the scheduled HTTP response to commit, not just its request to start.
  await waitFor(() => expect(screen.queryByLabelText('Sincronizando conversaciones')).not.toBeInTheDocument())
  expect(screen.getByText('Tú: Mi respuesta nueva')).toBeInTheDocument()
})

test('a late older realtime event in the same millisecond cannot replace the newer message', () => {
  notifyInboxMessage(mockUser.id, 'chat', 'chat', sent('2026-11-16T10:00:00.123900Z'))
  notifyInboxMessage(mockUser.id, 'chat', 'chat', { id: 'incoming-old', sender_id: 'peer', content: 'Mensaje anterior de ella', created_at: '2026-11-16T10:00:00.123100Z' }, true)
  expect(latestInboxMessage(mockUser.id, 'chat', 'chat')?.id).toBe('outgoing')
})

test('conversation text becomes visible while an independent missing-avatar request remains pending', async () => {
  jest.mocked(fallbackApiJson).mockImplementation(() => new Promise(() => {}))
  jest.mocked(loadFallbackMessagesInbox).mockResolvedValue({ items: [{ ...row(), avatarUrl: '', profileId: 'peer' }] })
  render(<MemoryRouter><MessagesPage /></MemoryRouter>)
  await waitFor(() => expect(fallbackApiJson).toHaveBeenCalled())
  expect(screen.getByText('Mensaje anterior de ella')).toBeInTheDocument()
})

test('an ACK uses server time even when the optimistic clock was ahead, and the next incoming message wins', async () => {
  jest.mocked(loadFallbackMessagesInbox).mockResolvedValue({ items: [row()] })
  render(<MemoryRouter><MessagesPage /></MemoryRouter>)
  await screen.findByText('Mensaje anterior de ella')
  act(() => notifyInboxMessage(mockUser.id, 'chat', 'chat', { ...sent('2026-11-16T10:00:08Z'), status: 'pending' }))
  expect(screen.getByText('Tú: Mi respuesta nueva')).toBeInTheDocument()
  act(() => notifyInboxMessage(mockUser.id, 'chat', 'chat', sent('2026-11-16T10:00:01Z')))
  expect(latestInboxMessage(mockUser.id, 'chat', 'chat')?.status).toBe('sent')
  act(() => notifyInboxMessage(mockUser.id, 'chat', 'chat', { id: 'incoming-new', sender_id: 'peer', content: 'Respuesta posterior', created_at: '2026-11-16T10:00:02Z' }, true))
  expect(screen.getByText('Respuesta posterior')).toBeInTheDocument()
})

test.each(['chat', 'group', 'community'] as const)('an explicit clear cursor retires an older %s preview', scope => {
  notifyInboxMessage(mockUser.id, scope, 'chat', sent('2026-11-16T10:00:01Z'))
  acknowledgeInboxPreview(mockUser.id, scope, 'chat', { updatedAt: '2026-11-16T10:00:02Z', clearedAt: '2026-11-16T10:00:02Z' })
  expect(latestInboxMessage(mockUser.id, scope, 'chat')).toBeUndefined()
})

test('empty row metadata cannot acknowledge a message, but a matching ID and a later visible message can', () => {
  const message = sent('2026-11-16T10:00:01Z')
  notifyInboxMessage(mockUser.id, 'chat', 'chat', message)
  acknowledgeInboxPreview(mockUser.id, 'chat', 'chat', { updatedAt: '2026-11-16T10:01:00Z' })
  expect(latestInboxMessage(mockUser.id, 'chat', 'chat')?.id).toBe(message.id)
  acknowledgeInboxPreview(mockUser.id, 'chat', 'chat', { updatedAt: '2026-11-16T10:00:01Z', lastMessageId: message.id })
  expect(latestInboxMessage(mockUser.id, 'chat', 'chat')).toBeUndefined()
  notifyInboxMessage(mockUser.id, 'chat', 'chat', message)
  acknowledgeInboxPreview(mockUser.id, 'chat', 'chat', { updatedAt: '2026-11-16T10:00:02Z', lastMessageId: 'newer' })
  expect(latestInboxMessage(mockUser.id, 'chat', 'chat')).toBeUndefined()
})

test('an older avatar lookup cannot restore old text or a removed conversation', async () => {
  let resolveAvatar!: (value: unknown) => void
  jest.mocked(fallbackApiJson).mockImplementationOnce(() => new Promise(resolve => { resolveAvatar = resolve }))
  jest.mocked(loadFallbackMessagesInbox).mockResolvedValue({ items: [{ ...row(), avatarUrl: '', profileId: 'peer' }] })
  render(<MemoryRouter><MessagesPage /></MemoryRouter>)
  await screen.findByText('Mensaje anterior de ella')
  await waitFor(() => expect(fallbackApiJson).toHaveBeenCalled())
  jest.mocked(loadFallbackMessagesInbox).mockResolvedValue({ items: [] })
  act(() => window.dispatchEvent(new Event('novvin:inbox-changed')))
  await waitFor(() => expect(screen.queryByText('Contacto ficticio')).not.toBeInTheDocument())
  await act(async () => resolveAvatar([{ id: 'peer', avatar_url: 'late.png' }]))
  expect(screen.queryByText('Contacto ficticio')).not.toBeInTheDocument()
})

test('an old account avatar lookup cannot publish its conversation into a different account', async () => {
  let resolveAvatar!: (value: unknown) => void
  jest.mocked(fallbackApiJson).mockImplementationOnce(() => new Promise(resolve => { resolveAvatar = resolve }))
  jest.mocked(loadFallbackMessagesInbox).mockResolvedValueOnce({ items: [{ ...row(), avatarUrl: '', profileId: 'peer' }] })
  const { rerender } = render(<MemoryRouter><MessagesPage /></MemoryRouter>)
  await screen.findByText('Mensaje anterior de ella')
  jest.mocked(loadFallbackMessagesInbox).mockResolvedValue({ items: [] })
  mockUser.id = 'different-account'
  rerender(<MemoryRouter><MessagesPage /></MemoryRouter>)
  await waitFor(() => expect(screen.queryByText('Contacto ficticio')).not.toBeInTheDocument())
  await act(async () => resolveAvatar([{ id: 'peer', avatar_url: 'late.png' }]))
  expect(screen.queryByText('Contacto ficticio')).not.toBeInTheDocument()
  expect(latestInboxMessage(mockUser.id, 'chat', 'chat')).toBeUndefined()
})
