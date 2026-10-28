import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { NotificationsPage } from '../../../features/notifications/NotificationsPage'
const mockNavigate = jest.fn()
const mockUser = { id: 'viewer' }
jest.mock('react-router-dom', () => ({ ...jest.requireActual('react-router-dom'), useNavigate: () => mockNavigate }))
jest.mock('../../../core/auth/AuthProvider', () => ({ useAuth: () => ({ user: mockUser }) }))
jest.mock('../../../core/auth/fallbackAuth', () => ({ readFallbackSession: () => ({ user: mockUser }) }))
jest.mock('../../../core/supabase', () => ({ supabase: null, reportSupabaseServiceRestrictionIfPresent: jest.fn() }))
jest.mock('../../../core/ubuntuRealtime', () => ({ subscribeUbuntuRealtime: () => () => {} }))
jest.mock('../../../components/SafeAvatar', () => ({ SafeAvatar: () => null }))
jest.mock('../../../core/fallbackData', () => ({
  loadFallbackNotifications: async () => ({ items: [{ id: 'notice', type: 'like_moment', actor_id: 'friend', actor_name: 'Persona', content: 'Le gustó tu momento.', target_type: 'moment', target_id: 'moment-a', is_read: false }] }),
  markFallbackNotificationRead: async () => ({ ok: true }), markAllFallbackNotificationsRead: async () => ({ ok: true }),
}))

beforeAll(() => Object.defineProperty(globalThis.crypto, 'randomUUID', { configurable: true, value: () => 'test-notification-id' }))

test('a moment like names the action and opens the corresponding moment', async () => {
  render(<NotificationsPage/>)
  expect(await screen.findByText('Like en momento')).toBeInTheDocument()
  fireEvent.click(screen.getByRole('button', { name: 'Ver momento' }))
  await waitFor(() => expect(mockNavigate).toHaveBeenCalledWith('/app/moments/view/moment-a', { state: undefined }))
})
