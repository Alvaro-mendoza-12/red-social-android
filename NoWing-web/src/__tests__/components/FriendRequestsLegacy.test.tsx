import { randomUUID } from 'node:crypto'
import { act, fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { FriendRequestsPage } from '../../features/social/FriendRequestsPage'
import { supabase } from '../../core/supabase'

jest.mock('../../core/auth/AuthProvider', () => ({ useAuth: () => ({ user: mockUser, isFallbackSession: false }) }))
const mockUser = { id: 'legacy-owner' }
jest.mock('../../core/auth/fallbackAuth', () => ({ isFallbackAuthConfigured: false }))
jest.mock('../../core/supabase', () => ({ supabase: { from: jest.fn() }, isSupabaseConfigured: true }))
jest.mock('../../core/fallbackData', () => ({ loadFallbackFriends: jest.fn(), updateFallbackFriendRequest: jest.fn() }))
jest.mock('../../core/publicProfileSummaries', () => ({ publicProfileSummaries: async () => new Map() }))
jest.mock('../../components/SafeAvatar', () => ({ SafeAvatar: () => null }))

test('legacy acceptance keeps sent requests intact after changing tabs', async () => {
  Object.defineProperty(globalThis.crypto, 'randomUUID', { configurable: true, value: randomUUID })
  let finish!: (result: { error: null }) => void
  const pending = new Promise(resolve => { finish = resolve })
  const updateFilter = jest.fn().mockReturnValue({ eq: jest.fn().mockReturnValue(pending) })
  jest.mocked(supabase!.from).mockImplementation(() => ({
    select: () => ({ eq: (field: string) => ({ order: async () => ({ data: [{
      id: 'request', sender_id: field === 'receiver_id' ? 'sender' : mockUser.id,
      receiver_id: field === 'receiver_id' ? mockUser.id : 'recipient', status: 'pending', created_at: '2026-11-10T12:00:00Z',
    }], error: null }) }) }),
    update: () => ({ eq: updateFilter }),
  }) as ReturnType<NonNullable<typeof supabase>['from']>)
  render(<MemoryRouter><FriendRequestsPage /></MemoryRouter>)
  fireEvent.click(await screen.findByRole('button', { name: 'Aceptar' }))
  expect(updateFilter).toHaveBeenCalledWith('id', 'request')
  fireEvent.click(screen.getByRole('button', { name: 'Enviadas' }))
  expect(await screen.findByRole('link', { name: /Usuario de Novvin/ })).toHaveAttribute('href', '/app/profile/recipient')
  await act(async () => finish({ error: null }))
  expect(screen.getByRole('link', { name: /Usuario de Novvin/ })).toHaveAttribute('href', '/app/profile/recipient')
  expect(screen.getByRole('button', { name: 'Enviadas' })).toHaveAttribute('aria-pressed', 'true')
})
