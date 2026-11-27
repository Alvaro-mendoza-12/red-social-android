import { act, render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { VoiceRoomsPage } from '../../../features/voice/VoiceRoomsPage'
import { loadFallbackVoiceRooms, type FallbackVoiceRoomsResponse } from '../../../core/fallbackData'

let mockRefresh: () => void
jest.mock('../../../core/auth/AuthProvider', () => ({
  useAuth: () => ({ user: { id: 'voice-fixture' }, isFallbackSession: true }),
}))
jest.mock('../../../core/useVoiceDirectoryUpdates', () => ({
  useVoiceDirectoryUpdates: (callback: () => void) => { mockRefresh = callback },
}))
jest.mock('../../../core/fallbackData', () => ({ loadFallbackVoiceRooms: jest.fn() }))
jest.mock('../../../core/supabase', () => ({ resolveSupabasePublicUrl: (_bucket: string, url: string) => url, supabase: null }))
jest.mock('../../../components/SafeAvatar', () => ({ SafeAvatar: () => null }))

test('a late directory fetch cannot replace a newer list received after a live update', async () => {
  localStorage.clear()
  let finishOld: (response: FallbackVoiceRoomsResponse) => void = () => {}
  const old = new Promise<FallbackVoiceRoomsResponse>(resolve => { finishOld = resolve })
  jest.mocked(loadFallbackVoiceRooms)
    .mockReturnValueOnce(old)
    .mockResolvedValueOnce({ rooms: [
      { id: 'new', name: 'Sala actual', current_participants: 2 },
      { name: 'Sala sin identificador' },
    ] })
  render(<MemoryRouter><VoiceRoomsPage /></MemoryRouter>)
  await act(async () => mockRefresh())
  expect(await screen.findByText('Sala actual')).toBeInTheDocument()
  expect(screen.queryByText('Sala sin identificador')).not.toBeInTheDocument()
  await act(async () => finishOld({ rooms: [{ id: 'old', name: 'Sala anterior' }] }))
  expect(screen.queryByText('Sala anterior')).not.toBeInTheDocument()
  expect(screen.getByText('Sala actual')).toBeInTheDocument()
})
